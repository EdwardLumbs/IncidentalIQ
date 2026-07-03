// Trip Ops Incidental Monitor — Cloudflare Worker entrypoint.
//   fetch()     → REST API:  POST /messages  (phone upload) | GET /incidentals (dashboard)
//   scheduled() → cron every 6h (+15m): classify unclassified messages with Groq, per chat, batched.
import {
  classifyBatch, CONTAINER_RE, type BatchMessage,
} from "./classifier.js";
import {
  insertMessages, chatsWithUnclassified, unclassifiedFor, getSummary, getTrips,
  saveSummary, saveChunkResults, batchUpsertTrips, queryIncidentals,
  setSystemStatus, getSystemStatus,
} from "./db.js";

export interface Env {
  DB: D1Database;
  GROQ_API_KEY: string;
  GROQ_MODEL: string;
  API_TOKEN: string;          // shared secret the phone must present (Authorization: Bearer <token>)
  BATCH_SIZE?: string;
  MAX_MSGS_PER_RUN?: string;
}

// Hard limits.
const MAX_UPLOAD = 1000;           // reject absurd uploads (#3) — the phone self-limits to 500
const MAX_MSGS_PER_RUN_DEFAULT = 300; // cap messages classified per cron run so subrequests stay bounded

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

// Endpoints anyone may hit without the token (no data, no cost).
const AUTH_EXEMPT = new Set(["/", "/health"]);

// Constant-time-ish token check. Returns true if the request carries the right shared secret.
function authorized(request: Request, env: Env): boolean {
  if (!env.API_TOKEN) return false; // fail closed: no token configured → nobody gets in
  const provided = (request.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  return provided.length > 0 && provided === env.API_TOKEN;
}

export default {
  // ── REST API ──────────────────────────────────────────────────────
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // Auth gate (#1): everything except the health/root check requires the shared secret.
    if (!AUTH_EXEMPT.has(url.pathname) && !authorized(request, env)) {
      return json({ ok: false, error: "unauthorized" }, 401);
    }

    try {
      // Phone uploads a batch of captured messages.
      if (request.method === "POST" && url.pathname === "/messages") {
        const body = await request.json().catch(() => null) as any;
        const msgs = Array.isArray(body?.messages) ? body.messages : Array.isArray(body) ? body : null;
        if (!msgs) return json({ ok: false, error: "expected { messages: [...] }" }, 400);
        if (msgs.length > MAX_UPLOAD) {
          return json({ ok: false, error: `too many messages; max ${MAX_UPLOAD} per request` }, 413);
        }
        const { inserted, skipped } = await insertMessages(env.DB, msgs);
        return json({ ok: true, received: msgs.length, inserted, skipped });
      }

      // Dashboard / spot-check: query classified incidentals.
      if (request.method === "GET" && url.pathname === "/incidentals") {
        const rows = await queryIncidentals(env.DB, {
          trip: url.searchParams.get("trip"),
          status: url.searchParams.get("status"),
          limit: Number(url.searchParams.get("limit")) || undefined,
        });
        return json({ ok: true, count: rows.length, incidentals: rows });
      }

      // Manual trigger for testing the classifier without waiting for the cron.
      if (request.method === "POST" && url.pathname === "/run") {
        const summary = await runClassifier(env);
        return json({ ok: true, ...summary });
      }

      // Liveness + last-cron health (#5). Open (no token) but exposes no message data.
      if (url.pathname === "/" || url.pathname === "/health") {
        const status = await getSystemStatus(env.DB).catch(() => ({}));
        return json({ ok: true, service: "tripops-monitor", status });
      }

      return json({ ok: false, error: "not found" }, 404);
    } catch (e: any) {
      return json({ ok: false, error: String(e?.message ?? e) }, 500);
    }
  },

  // ── Cron: every 6h, +15 min offset (00:15/06:15/12:15/18:15 UTC) ──
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      runClassifier(env)
        .then(async (r) => {
          await setSystemStatus(env.DB, "last_cron", JSON.stringify({ ok: true, ...r, at: new Date().toISOString() }));
          console.log("cron done:", JSON.stringify(r));
        })
        .catch(async (e) => {
          // Record the failure so it's visible at /health instead of vanishing silently.
          const msg = String(e?.message ?? e);
          await setSystemStatus(env.DB, "last_cron", JSON.stringify({ ok: false, error: msg, at: new Date().toISOString() }))
            .catch(() => {});
          console.error("cron FAILED:", msg);
        }),
    );
  },
};

// Core classification pass: for each chat with unclassified messages, batch → Groq → persist.
// Bounded to MAX_MSGS_PER_RUN messages total so a big backlog can't exceed the Worker's
// subrequest cap in a single invocation (the remainder is picked up by the next run).
async function runClassifier(env: Env): Promise<{ chats: number; batches: number; incidentals: number; processed: number }> {
  if (!env.GROQ_API_KEY) throw new Error("GROQ_API_KEY not set");
  const model = env.GROQ_MODEL || "llama-3.3-70b-versatile";
  const batchSize = Math.max(1, Number(env.BATCH_SIZE) || 25);
  const maxPerRun = Math.max(1, Number(env.MAX_MSGS_PER_RUN) || MAX_MSGS_PER_RUN_DEFAULT);
  const contextTail = 5; // last N messages of prior chunk carried as reference

  let batches = 0, incidentalsTotal = 0, processed = 0;
  const chats = await chatsWithUnclassified(env.DB);

  for (const group of chats) {
    if (processed >= maxPerRun) break;
    const msgs = (await unclassifiedFor(env.DB, group)).slice(0, maxPerRun - processed);
    if (!msgs.length) continue;

    let summary = await getSummary(env.DB, group);
    const trips = await getTrips(env.DB, group);

    for (let i = 0; i < msgs.length; i += batchSize) {
      const chunk = msgs.slice(i, i + batchSize);
      // Prepend a few prior messages as context so a container# from the last chunk still links.
      const ctxTail = msgs.slice(Math.max(0, i - contextTail), i);
      const batchMsgs: BatchMessage[] = [
        ...ctxTail.map((m) => ({ id: `ctx${m.id}`, sender: m.sender, content: m.message })),
        ...chunk.map((m) => ({ id: String(m.id), sender: m.sender, content: m.message })),
      ];

      const { out } = await classifyBatch({
        apiKey: env.GROQ_API_KEY, model, summary, trips, messages: batchMsgs,
      });

      // Only persist results for THIS chunk's real ids (context ids are prefixed "ctx").
      const chunkIds = chunk.map((m) => m.id);
      const chunkIdSet = new Set(chunkIds.map(String));
      const chunkResults = out.results.filter((r) => chunkIdSet.has(String(r.id)));

      const saved = await saveChunkResults(env.DB, chunkIds, chunkResults);
      incidentalsTotal += saved.incidentals;

      // Update the trip registry (batched): from classifier trip_references + container-number regex.
      const seenAt = new Date().toISOString();
      const tripRefs: { ref: string; type: string | null }[] = [];
      for (const r of chunkResults) {
        if (r.trip_reference) tripRefs.push({ ref: r.trip_reference, type: r.reference_type });
      }
      for (const m of chunk) {
        for (const c of m.message.matchAll(CONTAINER_RE)) {
          tripRefs.push({ ref: c[0], type: "container_number" });
        }
      }
      await batchUpsertTrips(env.DB, group, tripRefs, seenAt);

      // Carry the updated rolling summary into the next chunk / next run.
      summary = out.situation_summary;
      batches++;
      processed += chunk.length;
    }

    if (summary) await saveSummary(env.DB, group, summary);
  }

  return { chats: chats.length, batches, incidentals: incidentalsTotal, processed };
}
