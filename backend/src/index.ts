// Trip Ops Incidental Monitor — Express entrypoint (converted from the Cloudflare Worker).
//   HTTP     → REST API:  POST /messages  (phone upload) | GET /incidentals (dashboard) | ...
//   node-cron → same cadence as the old Workers cron (twice daily, UTC): classify unclassified
//               messages with Groq, per chat, batched.
//
// Behavior is UNCHANGED from the Worker version — this is a host conversion, not a redesign. The one
// thing that's genuinely gone is the reason MAX_MSGS_PER_RUN existed: on Workers it capped a single
// invocation's subrequests under Cloudflare's per-invocation ceiling. There's no such ceiling in a
// long-lived Node process — it's kept here only as a general pacing knob (don't let one run hammer
// Groq's own rate limits), not because anything requires it anymore.
import "dotenv/config";
import express, { type Request, type Response, type NextFunction } from "express";
import cron from "node-cron";
import { run as pgRun } from "./pg.js";
import { initSchema } from "./schema.js";
import {
  classifyBatch, CONTAINER_RE, PLATE_RE, normContainer, type BatchMessage,
} from "./classifier.js";
import {
  insertMessages, chatsWithUnclassified, unclassifiedFor, getSummary, getTrips,
  saveSummary, saveChunkResults, batchUpsertTrips, queryIncidentals, queryIncidentalsByTrip,
  queryMessages, queryTrips, setSystemStatus, getSystemStatus, bumpMetrics,
  getTripLinks, upsertTripLinks, applyTripLinks, nowPh,
} from "./db.js";

// Hard limits.
const MAX_UPLOAD = 1000;              // reject absurd uploads — the phone self-limits to 500
const MAX_MSGS_PER_RUN_DEFAULT = 300; // per-run pacing cap (see file header — no longer a hard ceiling)

const PORT = Number(process.env.PORT) || 8790;

// One CSV cell: stringify (objects/arrays → JSON), then RFC-4180-quote if it contains a comma,
// quote, or newline. Lets non-developers open any list endpoint directly in Excel/Sheets.
function csvCell(v: unknown): string {
  const s = v == null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}
// Turn an array of flat row objects into a CSV string (header from the first row's keys).
function toCsv(rows: any[]): string {
  if (!rows.length) return "";
  const cols = Object.keys(rows[0]);
  const head = cols.map(csvCell).join(",");
  const body = rows.map((r) => cols.map((c) => csvCell(r[c])).join(",")).join("\n");
  return `${head}\n${body}`;
}
// Send `rows` as CSV when ?format=csv, else the normal JSON envelope. `key` names the JSON array.
function listResponse(res: Response, rows: any[], key: string, format: string | undefined) {
  if (format === "csv") {
    res.set("content-type", "text/csv; charset=utf-8");
    res.set("content-disposition", `attachment; filename="${key}.csv"`);
    return res.send(toCsv(rows));
  }
  return res.json({ ok: true, count: rows.length, [key]: rows });
}

// Parse a JSON request body, tolerating raw C0 control characters (e.g. a TAB pasted into a chat
// message) that older clients may have failed to escape. Such chars are illegal inside JSON strings
// and make a strict parse throw — which, on the upload path, used to deadlock the phone's queue
// behind one poison message. On failure we escape any raw controls to \uXXXX (lossless — a real tab
// round-trips back to a tab) and retry once. Returns null if it still can't be parsed.
function parseJsonTolerant(raw: string): any {
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

// Wraps an async route handler so a rejected promise reaches the error middleware below instead of
// becoming an unhandled rejection. Express 4 only auto-catches SYNCHRONOUS throws from a route handler
// — every route here is async, so without this, an error inside one (a bad Groq response, a dropped
// Postgres connection, anything) doesn't 500 the one request, it crashes the whole process. Learned
// this the hard way testing /run against a since-retired Groq model: same shape as trip-monitoring's
// own middleware/errorHandler.js asyncHandler, ported here since this backend is one file, not a
// middleware/ directory.
function asyncHandler(fn: (req: Request, res: Response, next: NextFunction) => Promise<any>) {
  return (req: Request, res: Response, next: NextFunction) => {
    fn(req, res, next).catch(next);
  };
}

// Endpoints anyone may hit without the token (no data, no cost).
const AUTH_EXEMPT = new Set(["/", "/health"]);

// Constant-time-ish token check. Returns true if the request carries the right shared secret.
function authorized(req: Request): boolean {
  const token = process.env.API_TOKEN;
  if (!token) return false; // fail closed: no token configured → nobody gets in
  const provided = (req.headers.authorization ?? "").replace(/^Bearer\s+/i, "").trim();
  return provided.length > 0 && provided === token;
}

const app = express();
app.use((req: Request, res: Response, next: NextFunction) => {
  if (AUTH_EXEMPT.has(req.path) || authorized(req)) return next();
  res.status(401).json({ ok: false, error: "unauthorized" });
});

// Raw text body, parsed manually via parseJsonTolerant — only /messages carries a body.
app.use(express.text({ type: () => true, limit: "5mb" }));

app.post("/messages", asyncHandler(async (req: Request, res: Response) => {
  const body = parseJsonTolerant(typeof req.body === "string" ? req.body : "");
  const msgs = Array.isArray(body?.messages) ? body.messages : Array.isArray(body) ? body : null;
  if (!msgs) return res.status(400).json({ ok: false, error: "expected { messages: [...] }" });
  if (msgs.length > MAX_UPLOAD) {
    return res.status(413).json({ ok: false, error: `too many messages; max ${MAX_UPLOAD} per request` });
  }
  const { inserted, skipped } = await insertMessages(msgs);
  res.json({ ok: true, received: msgs.length, inserted, skipped });
}));

// Dashboard: classified incidentals. Default = detail rows; ?view=trips = per-trip rollup
// ("which trips have incidentals"). Filters: trip, status, group, since, until, limit, offset.
// Add ?format=csv on any list endpoint for a spreadsheet-ready download.
app.get("/incidentals", asyncHandler(async (req: Request, res: Response) => {
  const p = req.query as Record<string, string | undefined>;
  const format = p.format;
  if (p.view === "trips") {
    const trips = await queryIncidentalsByTrip({
      status: p.status ?? null, group: p.group ?? null, since: p.since ?? null, until: p.until ?? null,
    });
    return listResponse(res, trips, "trips", format);
  }
  const rows = await queryIncidentals({
    trip: p.trip ?? null, status: p.status ?? null, group: p.group ?? null,
    since: p.since ?? null, until: p.until ?? null,
    limit: Number(p.limit) || undefined, offset: Number(p.offset) || undefined,
  });
  return listResponse(res, rows, "incidentals", format);
}));

// History browser: raw stored messages. Filters: trip, group, since, until (date or ISO),
// incidental=1 (only flagged), limit, offset. ?trip=<container|plate> pulls a trip's whole
// conversation timeline. Add ?format=csv for a spreadsheet download.
app.get("/messages", asyncHandler(async (req: Request, res: Response) => {
  const p = req.query as Record<string, string | undefined>;
  const rows = await queryMessages({
    trip: p.trip ?? null, group: p.group ?? null, since: p.since ?? null, until: p.until ?? null,
    incidental: p.incidental ?? null, store_only: p.store_only ?? null,
    limit: Number(p.limit) || undefined, offset: Number(p.offset) || undefined,
  });
  return listResponse(res, rows, "messages", p.format);
}));

// Trip registry: every known trip (container#/plate) whether or not it has incidentals, with
// driver/helper, plate↔container binding, and an incidental count. Filters: group, since, until
// (on last_seen), limit, offset. Add ?format=csv for a spreadsheet download.
app.get("/trips", asyncHandler(async (req: Request, res: Response) => {
  const p = req.query as Record<string, string | undefined>;
  const rows = await queryTrips({
    group: p.group ?? null, since: p.since ?? null, until: p.until ?? null,
    limit: Number(p.limit) || undefined, offset: Number(p.offset) || undefined,
  });
  return listResponse(res, rows, "trips", p.format);
}));

// Manual trigger for testing the classifier without waiting for the cron. Bumps the same
// lifetime metrics the cron does, so a manual run is reflected at /health too.
app.post("/run", asyncHandler(async (_req: Request, res: Response) => {
  const r = await runClassifier();
  await bumpMetrics({
    batches: r.batches, tokens: r.tokens, incidentals: r.incidentals, processed: r.processed,
  }).catch(() => {});
  res.json({ ok: true, ...r });
}));

// Liveness + last-cron health. Open (no token) but exposes no message data.
app.get(["/", "/health"], asyncHandler(async (_req: Request, res: Response) => {
  const status = await getSystemStatus().catch(() => ({}));
  res.json({ ok: true, service: "tripops-monitor", status });
}));

app.use((_req: Request, res: Response) => res.status(404).json({ ok: false, error: "not found" }));

// Log the real detail server-side; never leak DB/Groq internals to the caller.
app.use((err: any, _req: Request, res: Response, _next: NextFunction) => {
  console.error("request error:", String(err?.message ?? err));
  res.status(500).json({ ok: false, error: "internal error" });
});

// Extract the single trip identity a message names — a container# (canonical), or a plate resolved
// to its bound container when the binding is known, else the bare plate. Returns null when the
// message names ZERO or MORE THAN ONE distinct trip (a multi-container manifest is ambiguous), so a
// stamp never mislabels a row. Used to link plain job-sheet / status messages, not just incidentals.
function stampFor(message: string, links: Map<string, string>): { ref: string; type: string } | null {
  const conts = [...new Set([...message.matchAll(CONTAINER_RE)].map((x) => normContainer(x[0])))];
  const plates = [...new Set([...message.matchAll(PLATE_RE)].map((x) => x[0].toUpperCase()))];
  const resolved = [...new Set([...conts, ...plates.map((p) => links.get(p) ?? p)])];
  if (resolved.length !== 1) return null;
  const ref = resolved[0];
  const type = /^[A-Z]{4}\d{7}$/.test(ref) ? "container_number" : "plate_number";
  return { ref, type };
}

// Core classification pass: for each chat with unclassified messages, batch → Groq → persist.
async function runClassifier(): Promise<{ chats: number; batches: number; incidentals: number; processed: number; tokens: number }> {
  const apiKey = process.env.GROQ_API_KEY;
  if (!apiKey) throw new Error("GROQ_API_KEY not set");
  const model = process.env.GROQ_MODEL || "llama-3.3-70b-versatile";
  const batchSize = Math.max(1, Number(process.env.BATCH_SIZE) || 25);
  const maxPerRun = Math.max(1, Number(process.env.MAX_MSGS_PER_RUN) || MAX_MSGS_PER_RUN_DEFAULT);
  const contextTail = 5; // last N messages of prior chunk carried as reference

  let batches = 0, incidentalsTotal = 0, processed = 0, tokens = 0;
  const chats = await chatsWithUnclassified();

  for (const group of chats) {
    if (processed >= maxPerRun) break;
    const msgs = (await unclassifiedFor(group)).slice(0, maxPerRun - processed);
    if (!msgs.length) continue;

    let summary = await getSummary(group);
    const trips = await getTrips(group);

    // Build/refresh plate↔container bindings from this chat's job sheets (any message naming BOTH a
    // plate and a container binds them; the container is the canonical trip id). Scanning all of the
    // run's messages upfront means the binding is known even if the job sheet sits later in the run.
    const links = await getTripLinks(group);
    const newPairs: { alias: string; canonical: string }[] = [];
    for (const m of msgs) {
      const plates = [...new Set([...m.message.matchAll(PLATE_RE)].map((x) => x[0].toUpperCase()))];
      const conts = [...new Set([...m.message.matchAll(CONTAINER_RE)].map((x) => normContainer(x[0])))];
      // ONLY a real job sheet — EXACTLY one plate + one container — is a trustworthy binding. A
      // multi-container manifest lists many containers plus warehouse codes that look like plates;
      // binding those wrongly merges unrelated trips (learned the hard way: NEG5077 → SEGU2978251).
      if (plates.length === 1 && conts.length === 1 && plates[0] !== conts[0]) {
        links.set(plates[0], conts[0]);
        newPairs.push({ alias: plates[0], canonical: conts[0] });
      }
    }
    if (newPairs.length) {
      await upsertTripLinks(group, newPairs);
      await applyTripLinks(group, newPairs); // fix rows already stored under the plate
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
        apiKey, model, summary, trips, messages: batchMsgs,
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

      // Regex-stamp EVERY message with its trip id (container#, or plate→container via binding), so
      // plain job sheets / status updates link too — not only the messages Groq flags as incidentals.
      // Only stamp when a message names EXACTLY ONE trip; a multi-container manifest is ambiguous.
      const tripStamp = new Map<number, { ref: string; type: string }>();
      for (const m of chunk) {
        const stamp = stampFor(m.message, links);
        if (stamp) tripStamp.set(m.id, stamp);
      }

      const saved = await saveChunkResults(chunkIds, chunkResults, tripStamp);
      incidentalsTotal += saved.incidentals;

      // Update the trip registry (batched): from classifier trip_references + container/plate regex.
      const seenAt = nowPh();
      const tripRefs: { ref: string; type: string | null }[] = [];
      for (const r of chunkResults) {
        if (r.trip_reference) tripRefs.push({ ref: r.trip_reference, type: r.reference_type });
      }
      for (const m of chunk) {
        for (const c of m.message.matchAll(CONTAINER_RE)) {
          tripRefs.push({ ref: normContainer(c[0]), type: "container_number" });
        }
        // Plates are what field reports actually name — seed them so later reports link.
        for (const c of m.message.matchAll(PLATE_RE)) {
          tripRefs.push({ ref: c[0].toUpperCase(), type: "plate_number" });
        }
      }
      await batchUpsertTrips(group, tripRefs, seenAt);

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

    if (summary) await saveSummary(group, summary);
  }

  return { chats: chats.length, batches, incidentals: incidentalsTotal, processed, tokens };
}

async function runClassifierAndRecord(): Promise<void> {
  try {
    const r = await runClassifier();
    await setSystemStatus("last_cron", JSON.stringify({ ok: true, ...r, at: nowPh() }));
    const totals = await bumpMetrics({
      batches: r.batches, tokens: r.tokens, incidentals: r.incidentals, processed: r.processed,
    }).catch(() => null);
    console.log("cron done:", JSON.stringify(r), "totals:", JSON.stringify(totals));
  } catch (e: any) {
    const msg = String(e?.message ?? e);
    await setSystemStatus("last_cron", JSON.stringify({ ok: false, error: msg, at: nowPh() })).catch(() => {});
    console.error("cron FAILED:", msg);
  }
}

async function main() {
  await initSchema(async (sql: string) => pgRun(sql));

  // Same cadence as the old Workers cron: twice daily, UTC 01:15 and 06:15 (PH 09:15 / 14:15).
  cron.schedule("15 1,6 * * *", () => { runClassifierAndRecord(); }, { timezone: "UTC" });

  app.listen(PORT, () => console.log(`tripops-monitor listening on :${PORT}`));
}

main().catch((e) => {
  console.error("failed to start:", e);
  process.exit(1);
});
