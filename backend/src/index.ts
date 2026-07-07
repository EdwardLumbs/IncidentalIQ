// Trip Ops Incidental Monitor — Cloudflare Worker entrypoint.
//   fetch()     → REST API:  POST /messages  (phone upload) | GET /incidentals (dashboard)
//   scheduled() → cron every 6h (+15m): classify unclassified messages with Groq, per chat, batched.
import {
  classifyBatch, CONTAINER_RE, PLATE_RE, type BatchMessage,
} from "./classifier.js";
import {
  insertMessages, chatsWithUnclassified, unclassifiedFor, getSummary, getTrips,
  saveSummary, saveChunkResults, batchUpsertTrips, queryIncidentals, queryIncidentalsByTrip,
  queryMessages, setSystemStatus, getSystemStatus, bumpMetrics,
  getTripLinks, upsertTripLinks, applyTripLinks,
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

// Parse a JSON request body, tolerating raw C0 control characters (e.g. a TAB pasted into a chat
// message) that older clients may have failed to escape. Such chars are illegal inside JSON strings
// and make a strict parse throw — which, on the upload path, used to deadlock the phone's queue
// behind one poison message. On failure we escape any raw controls to \uXXXX (lossless — a real tab
// round-trips back to a tab) and retry once. Returns null if it still can't be parsed.
async function parseJsonTolerant(request: Request): Promise<any> {
  const raw = await request.text().catch(() => "");
  try {
    return JSON.parse(raw);
  } catch {
    try {
      const safe = raw.replace(new RegExp("[\\u0000-\\u001f]", "g"), (m) => "\\u" + m.charCodeAt(0).toString(16).padStart(4, "0"));
      return JSON.parse(safe);
    } catch {
      return null;
    }
  }
}

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
        const body = await parseJsonTolerant(request);
        const msgs = Array.isArray(body?.messages) ? body.messages : Array.isArray(body) ? body : null;
        if (!msgs) return json({ ok: false, error: "expected { messages: [...] }" }, 400);
        if (msgs.length > MAX_UPLOAD) {
          return json({ ok: false, error: `too many messages; max ${MAX_UPLOAD} per request` }, 413);
        }
        const { inserted, skipped } = await insertMessages(env.DB, msgs);
        return json({ ok: true, received: msgs.length, inserted, skipped });
      }

      // Dashboard: classified incidentals. Default = detail rows; ?view=trips = per-trip rollup
      // ("which trips have incidentals"). Filters: trip, status, group, since, until, limit.
      if (request.method === "GET" && url.pathname === "/incidentals") {
        const p = url.searchParams;
        if (p.get("view") === "trips") {
          const trips = await queryIncidentalsByTrip(env.DB, { status: p.get("status"), group: p.get("group") });
          return json({ ok: true, count: trips.length, trips });
        }
        const rows = await queryIncidentals(env.DB, {
          trip: p.get("trip"), status: p.get("status"), group: p.get("group"),
          since: p.get("since"), until: p.get("until"),
          limit: Number(p.get("limit")) || undefined,
        });
        return json({ ok: true, count: rows.length, incidentals: rows });
      }

      // History browser: raw stored messages. Filters: group, since, until (date or ISO),
      // incidental=1 (only flagged), limit. Mirrors the stored_messages export.
      if (request.method === "GET" && url.pathname === "/messages") {
        const p = url.searchParams;
        const rows = await queryMessages(env.DB, {
          group: p.get("group"), since: p.get("since"), until: p.get("until"),
          incidental: p.get("incidental"), limit: Number(p.get("limit")) || undefined,
        });
        return json({ ok: true, count: rows.length, messages: rows });
      }

      // Manual trigger for testing the classifier without waiting for the cron. Bumps the same
      // lifetime metrics the cron does, so a manual run is reflected at /health too.
      if (request.method === "POST" && url.pathname === "/run") {
        const r = await runClassifier(env);
        await bumpMetrics(env.DB, {
          batches: r.batches, tokens: r.tokens, incidentals: r.incidentals, processed: r.processed,
        }).catch(() => {});
        return json({ ok: true, ...r });
      }

      // Liveness + last-cron health (#5). Open (no token) but exposes no message data.
      if (url.pathname === "/" || url.pathname === "/health") {
        const status = await getSystemStatus(env.DB).catch(() => ({}));
        return json({ ok: true, service: "tripops-monitor", status });
      }

      return json({ ok: false, error: "not found" }, 404);
    } catch (e: any) {
      // Log the real detail server-side; never leak DB/Groq internals to the caller.
      console.error("request error:", String(e?.message ?? e));
      return json({ ok: false, error: "internal error" }, 500);
    }
  },

  // ── Cron: every 6h, +15 min offset (00:15/06:15/12:15/18:15 UTC) ──
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      runClassifier(env)
        .then(async (r) => {
          await setSystemStatus(env.DB, "last_cron", JSON.stringify({ ok: true, ...r, at: new Date().toISOString() }));
          // Accumulate lifetime totals (cron count, batches, Groq tokens, incidentals) for /health.
          const totals = await bumpMetrics(env.DB, {
            batches: r.batches, tokens: r.tokens, incidentals: r.incidentals, processed: r.processed,
          }).catch(() => null);
          console.log("cron done:", JSON.stringify(r), "totals:", JSON.stringify(totals));
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
async function runClassifier(env: Env): Promise<{ chats: number; batches: number; incidentals: number; processed: number; tokens: number }> {
  if (!env.GROQ_API_KEY) throw new Error("GROQ_API_KEY not set");
  const model = env.GROQ_MODEL || "llama-3.3-70b-versatile";
  const batchSize = Math.max(1, Number(env.BATCH_SIZE) || 25);
  const maxPerRun = Math.max(1, Number(env.MAX_MSGS_PER_RUN) || MAX_MSGS_PER_RUN_DEFAULT);
  const contextTail = 5; // last N messages of prior chunk carried as reference

  let batches = 0, incidentalsTotal = 0, processed = 0, tokens = 0;
  const chats = await chatsWithUnclassified(env.DB);

  for (const group of chats) {
    if (processed >= maxPerRun) break;
    const msgs = (await unclassifiedFor(env.DB, group)).slice(0, maxPerRun - processed);
    if (!msgs.length) continue;

    let summary = await getSummary(env.DB, group);
    const trips = await getTrips(env.DB, group);

    // Build/refresh plate↔container bindings from this chat's job sheets (any message naming BOTH a
    // plate and a container binds them; the container is the canonical trip id). Scanning all of the
    // run's messages upfront means the binding is known even if the job sheet sits later in the run.
    const links = await getTripLinks(env.DB, group);
    const newPairs: { alias: string; canonical: string }[] = [];
    for (const m of msgs) {
      const plates = [...new Set([...m.message.matchAll(PLATE_RE)].map((x) => x[0].toUpperCase()))];
      const conts = [...new Set([...m.message.matchAll(CONTAINER_RE)].map((x) => x[0].toUpperCase()))];
      // ONLY a real job sheet — EXACTLY one plate + one container — is a trustworthy binding. A
      // multi-container manifest lists many containers plus warehouse codes that look like plates;
      // binding those wrongly merges unrelated trips (learned the hard way: NEG5077 → SEGU2978251).
      if (plates.length === 1 && conts.length === 1 && plates[0] !== conts[0]) {
        links.set(plates[0], conts[0]);
        newPairs.push({ alias: plates[0], canonical: conts[0] });
      }
    }
    if (newPairs.length) {
      await upsertTripLinks(env.DB, group, newPairs);
      await applyTripLinks(env.DB, group, newPairs); // fix rows already stored under the plate
    }
    // Resolve a trip reference through the binding map: a plate with a known container → the container.
    const resolveRef = (ref: string | null, type: string | null) => {
      const canonical = ref ? links.get(ref.toUpperCase()) : undefined;
      return canonical ? { ref: canonical, type: "container_number" } : { ref, type };
    };

    for (let i = 0; i < msgs.length; i += batchSize) {
      const chunk = msgs.slice(i, i + batchSize);
      // Prepend a few prior messages as context so a container# from the last chunk still links.
      const ctxTail = msgs.slice(Math.max(0, i - contextTail), i);
      const batchMsgs: BatchMessage[] = [
        ...ctxTail.map((m) => ({ id: `ctx${m.id}`, sender: m.sender, content: m.message })),
        ...chunk.map((m) => ({ id: String(m.id), sender: m.sender, content: m.message })),
      ];

      const { out, meta } = await classifyBatch({
        apiKey: env.GROQ_API_KEY, model, summary, trips, messages: batchMsgs,
      });
      tokens += meta?.usage?.total_tokens ?? 0;

      // Only persist results for THIS chunk's real ids (context ids are prefixed "ctx").
      const chunkIds = chunk.map((m) => m.id);
      const chunkIdSet = new Set(chunkIds.map(String));
      const chunkResults = out.results.filter((r) => chunkIdSet.has(String(r.id)));

      // Collapse plate references to their bound container before persisting, so all of a trip's
      // incidentals land under one identity.
      for (const r of chunkResults) {
        const resolved = resolveRef(r.trip_reference, r.reference_type);
        r.trip_reference = resolved.ref;
        r.reference_type = resolved.type;
      }

      const saved = await saveChunkResults(env.DB, chunkIds, chunkResults);
      incidentalsTotal += saved.incidentals;

      // Update the trip registry (batched): from classifier trip_references + container/plate regex.
      const seenAt = new Date().toISOString();
      const tripRefs: { ref: string; type: string | null }[] = [];
      for (const r of chunkResults) {
        if (r.trip_reference) tripRefs.push({ ref: r.trip_reference, type: r.reference_type });
      }
      for (const m of chunk) {
        for (const c of m.message.matchAll(CONTAINER_RE)) {
          tripRefs.push({ ref: c[0], type: "container_number" });
        }
        // Plates are what field reports actually name — seed them so later reports link (#trip-linking).
        for (const c of m.message.matchAll(PLATE_RE)) {
          tripRefs.push({ ref: c[0].toUpperCase(), type: "plate_number" });
        }
      }
      await batchUpsertTrips(env.DB, group, tripRefs, seenAt);

      // Feed newly-seen ids into the in-memory registry so LATER chunks in THIS run can link to them
      // too (getTrips only queried once, before the loop — a plate first seen in chunk 1 must be
      // available to Groq when it classifies chunk 2's delay reports).
      for (const { ref, type } of tripRefs) {
        if (ref && !trips.some((t) => t.trip_reference === ref)) {
          trips.push({ trip_reference: ref, reference_type: type });
        }
      }

      // Carry the updated rolling summary into the next chunk / next run.
      summary = out.situation_summary;
      batches++;
      processed += chunk.length;
    }

    if (summary) await saveSummary(env.DB, group, summary);
  }

  return { chats: chats.length, batches, incidentals: incidentalsTotal, processed, tokens };
}
