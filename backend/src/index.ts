// Trip Ops Incidental Monitor — Cloudflare Worker entrypoint.
//   fetch()     → REST API:  POST /messages  (phone upload) | GET /incidentals (dashboard)
//   scheduled() → cron every 6h: classify unclassified messages with Groq, per chat, batched.
import {
  classifyBatch, contentHash, CONTAINER_RE, type BatchMessage,
} from "./classifier.js";
import {
  insertMessages, chatsWithUnclassified, unclassifiedFor, getSummary, getTrips,
  saveSummary, saveChunkResults, upsertTrip, queryIncidentals,
} from "./db.js";

export interface Env {
  DB: D1Database;
  GROQ_API_KEY: string;
  GROQ_MODEL: string;
  BATCH_SIZE?: string;
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

export default {
  // ── REST API ──────────────────────────────────────────────────────
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    try {
      // Phone uploads a batch of captured messages.
      if (request.method === "POST" && url.pathname === "/messages") {
        const body = await request.json().catch(() => null) as any;
        const msgs = Array.isArray(body?.messages) ? body.messages : Array.isArray(body) ? body : null;
        if (!msgs) return json({ ok: false, error: "expected { messages: [...] }" }, 400);
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

      if (url.pathname === "/" || url.pathname === "/health") {
        return json({ ok: true, service: "tripops-monitor" });
      }

      return json({ ok: false, error: "not found" }, 404);
    } catch (e: any) {
      return json({ ok: false, error: String(e?.message ?? e) }, 500);
    }
  },

  // ── Cron: every 6h (00/06/12/18 UTC) ──────────────────────────────
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(runClassifier(env).then((r) => console.log("cron done:", JSON.stringify(r))));
  },
};

// Core classification pass: for each chat with unclassified messages, batch → Groq → persist.
async function runClassifier(env: Env): Promise<{ chats: number; batches: number; incidentals: number }> {
  const model = env.GROQ_MODEL || "llama-3.3-70b-versatile";
  const batchSize = Math.max(1, Number(env.BATCH_SIZE) || 25);
  const contextTail = 5; // last N messages of prior chunk carried as reference

  let batches = 0, incidentalsTotal = 0;
  const chats = await chatsWithUnclassified(env.DB);

  for (const group of chats) {
    const msgs = await unclassifiedFor(env.DB, group);
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

      // Update the trip registry: from classifier trip_references + container-number regex.
      const seenAt = new Date().toISOString();
      for (const r of chunkResults) {
        if (r.trip_reference) await upsertTrip(env.DB, group, r.trip_reference, r.reference_type, seenAt);
      }
      for (const m of chunk) {
        for (const c of m.message.matchAll(CONTAINER_RE)) {
          await upsertTrip(env.DB, group, c[0], "container_number", seenAt);
        }
      }

      // Carry the updated rolling summary into the next chunk / next run.
      summary = out.situation_summary;
      batches++;
    }

    if (summary) await saveSummary(env.DB, group, summary);
  }

  return { chats: chats.length, batches, incidentals: incidentalsTotal };
}
