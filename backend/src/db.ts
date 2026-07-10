// D1 data layer — all SQL lives here so index.ts/classifier stay clean.
import type { BatchResult, TripRef } from "./classifier.js";
import { contentHash } from "./classifier.js";

// A message row as uploaded by the phone (matches captured_messages.jsonl on the device).
export interface IncomingMessage {
  source: string;            // "VIBER" | "MESSENGER" (any case) → stored lowercase
  group_name?: string;       // preferred key
  chat?: string;             // phone uses "chat" — accepted as an alias for group_name
  sender?: string | null;
  message?: string;          // preferred key
  content?: string;          // phone uses "content" — alias for message
  image_url?: string | null;
  is_image?: boolean;
  timestamp?: string;        // preferred key
  ts?: string;               // phone uses "ts" — alias for timestamp
  raw_notif?: number;        // 1 notif / 0 accessibility
  via_accessibility?: boolean; // phone field — inverse of raw_notif
}

// Dedup windows (content-hash + time). Two tiers, because the accessibility catch-up read re-reads
// messages ALREADY captured and stamps them with its READ-time — often >10 min after the original —
// so a single 10-min window let those re-reads through as duplicates. A SUBSTANTIVE message (a job
// sheet / field report) that reappears byte-for-byte within hours is virtually always such a re-read,
// so it gets a wide window; SHORT chatter ("ok po", "👍") legitimately repeats, so it keeps a tighter
// window (dropping a duplicate of it is harmless anyway — it carries no incidental).
const SUBSTANTIVE_LEN = 30;                     // messages this long or longer use the wide window
const DEDUP_WINDOW_LONG_MS = 6 * 60 * 60 * 1000; // 6 h — for substantive messages
const DEDUP_WINDOW_SHORT_MS = 60 * 60 * 1000;    // 1 h — for short chatter
const DEDUP_LOOKUP_MS = DEDUP_WINDOW_LONG_MS;    // widest window → bounds the candidate lookback
const dedupWindowFor = (message: string) =>
  message.trim().length >= SUBSTANTIVE_LEN ? DEDUP_WINDOW_LONG_MS : DEDUP_WINDOW_SHORT_MS;
// D1 caps bound parameters at 100 PER STATEMENT (stricter than SQLite's default). So each
// statement must stay ≤100 binds — but we bundle many statements into one DB.batch() call, which
// is a single subrequest. Net: a whole upload costs ~2 subrequests regardless of message count.
const INSERT_ROWS_PER_STMT = 11;  // 11 rows × 9 cols = 99 binds (≤100)
const IN_HASHES_PER_STMT = 99;    // 99 hashes + 1 window bound = 100 binds (≤100)
const STMTS_PER_BATCH = 50;       // statements bundled into one DB.batch() (= one subrequest)

function normalizeSource(s: string): string {
  const v = (s || "").toLowerCase();
  if (v.includes("viber")) return "viber";
  if (v.includes("messenger") || v.includes("orca")) return "messenger";
  return v || "unknown";
}

// ── Time: the WHOLE system stores + displays Philippine time (UTC+8), never UTC. ──
// Every stored timestamp is a PH wall-clock ISO string like "2026-07-08T20:56:01+08:00".
const PH_OFFSET_MS = 8 * 3600_000;
const pad2 = (n: number) => String(n).padStart(2, "0");
// Format an epoch (ms) as a PH wall-clock ISO string. Shift by +8h then read UTC getters so the
// digits are PH local time; the "+08:00" makes it unambiguous (and still reads as PH, not UTC).
export function phFromEpoch(ms: number): string {
  const d = new Date(ms + PH_OFFSET_MS);
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}T` +
         `${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:${pad2(d.getUTCSeconds())}+08:00`;
}
export function nowPh(): string { return phFromEpoch(Date.now()); }
// Normalize any incoming timestamp to a PH ISO string. The phone now sends PH (with +08:00); a bare
// no-zone string is also treated as PH wall-clock (NOT UTC — that was the old clamp bug).
function toPh(t?: string | null): string {
  if (!t) return nowPh();
  const s = /[zZ]|[+-]\d{2}:?\d{2}$/.test(t) ? t : `${t}+08:00`;
  const d = new Date(s);
  return isNaN(d.getTime()) ? nowPh() : phFromEpoch(d.getTime());
}

interface PreparedMsg {
  source: string; group: string; sender: string | null; message: string;
  image_url: string | null; ts: string; tsMs: number; rawNotif: number; hash: string;
}

// Insert a batch of uploaded messages, skipping duplicates. Duplicate = same content_hash within
// ±10 min of the message's timestamp (against rows already in D1 AND earlier rows in THIS batch).
// Cost: ~1 bulk dedup lookup + a few chunked multi-row INSERTs, regardless of how many messages —
// NOT one-subrequest-per-row (which would blow the Worker's free-tier subrequest cap).
export async function insertMessages(
  DB: D1Database,
  msgs: IncomingMessage[],
): Promise<{ inserted: number; skipped: number }> {
  let inserted = 0, skipped = 0;
  const now = nowPh();
  const nowMs = Date.now();

  // 1) Normalize + validate + hash. Clamp a wild timestamp to server time so a bad/spoofed ts
  //    can't slide a message outside the dedup window (#6).
  const prepared: PreparedMsg[] = [];
  for (const m of msgs) {
    const source = normalizeSource(m.source);
    const group = (m.group_name ?? m.chat ?? "").trim();
    const message = (m.message ?? m.content ?? "").toString();
    if (!group || !message) { skipped++; continue; }

    let tsMs = new Date(toPh(m.timestamp ?? m.ts)).getTime();
    if (!isFinite(tsMs) || tsMs > nowMs + 3_600_000 || tsMs < nowMs - 2 * 86_400_000) tsMs = nowMs;
    const ts = phFromEpoch(tsMs);
    const rawNotif = m.raw_notif ?? (m.via_accessibility ? 0 : 1);
    const hash = await contentHash(source, group, message);
    prepared.push({ source, group, sender: m.sender ?? null, message, image_url: m.image_url ?? null, ts, tsMs, rawNotif, hash });
  }
  if (!prepared.length) return { inserted, skipped };

  // 2) ONE bulk lookup of existing (hash, timestamp) for all candidate hashes, bounded to the
  //    earliest relevant window so we don't drag back the whole table for a common hash.
  const minTsMs = Math.min(...prepared.map((p) => p.tsMs)) - DEDUP_LOOKUP_MS;
  const loIso = phFromEpoch(minTsMs); // PH-format bound so string compare vs stored PH timestamps is chronological
  const uniqueHashes = [...new Set(prepared.map((p) => p.hash))];
  const seenTimes = new Map<string, number[]>(); // hash → [tsMs, ...] already present

  // Build ≤100-bind SELECT statements, then run them bundled in DB.batch() (one subrequest/batch).
  const lookupStmts: D1PreparedStatement[] = [];
  for (let i = 0; i < uniqueHashes.length; i += IN_HASHES_PER_STMT) {
    const slice = uniqueHashes.slice(i, i + IN_HASHES_PER_STMT);
    const placeholders = slice.map((_, j) => `?${j + 2}`).join(",");
    lookupStmts.push(DB.prepare(
      `SELECT content_hash, timestamp FROM captured_messages
       WHERE timestamp >= ?1 AND content_hash IN (${placeholders})`,
    ).bind(loIso, ...slice));
  }
  for (let i = 0; i < lookupStmts.length; i += STMTS_PER_BATCH) {
    const batchRes = await DB.batch(lookupStmts.slice(i, i + STMTS_PER_BATCH));
    for (const res of batchRes) {
      for (const r of (res.results as any[])) {
        const arr = seenTimes.get(r.content_hash) ?? [];
        arr.push(new Date(r.timestamp).getTime());
        seenTimes.set(r.content_hash, arr);
      }
    }
  }

  // 3) Filter: skip if a same-hash row (already in D1, or earlier in THIS batch) is within the
  //    message's window — wide for substantive re-reads, tight for short chatter.
  const fresh: PreparedMsg[] = [];
  for (const p of prepared) {
    const times = seenTimes.get(p.hash) ?? [];
    const win = dedupWindowFor(p.message);
    if (times.some((t) => Math.abs(t - p.tsMs) < win)) { skipped++; continue; }
    fresh.push(p);
    times.push(p.tsMs);              // makes later identical msgs in this same batch dedup too
    seenTimes.set(p.hash, times);
  }
  if (!fresh.length) return { inserted, skipped };

  // 4) Multi-row INSERT statements (≤100 binds each), bundled into DB.batch() calls.
  const COLS = 9;
  const insertStmts: D1PreparedStatement[] = [];
  for (let i = 0; i < fresh.length; i += INSERT_ROWS_PER_STMT) {
    const slice = fresh.slice(i, i + INSERT_ROWS_PER_STMT);
    const rowsSql = slice.map((_, r) => {
      const b = r * COLS;
      return `(${Array.from({ length: COLS }, (_, c) => `?${b + c + 1}`).join(",")})`;
    }).join(",");
    const binds: any[] = [];
    for (const p of slice) {
      binds.push(p.source, p.group, p.sender, p.message, p.image_url, p.ts, p.rawNotif, p.hash, now);
    }
    insertStmts.push(DB.prepare(
      `INSERT INTO captured_messages
        (source, group_name, sender, message, image_url, timestamp, raw_notif, content_hash, synced_at)
       VALUES ${rowsSql}`,
    ).bind(...binds));
  }
  for (let i = 0; i < insertStmts.length; i += STMTS_PER_BATCH) {
    await DB.batch(insertStmts.slice(i, i + STMTS_PER_BATCH));
  }
  inserted = fresh.length;

  return { inserted, skipped };
}

// Distinct chats that have unclassified messages waiting.
export async function chatsWithUnclassified(DB: D1Database): Promise<string[]> {
  const rs = await DB.prepare(
    "SELECT DISTINCT group_name FROM captured_messages WHERE classified_at IS NULL",
  ).all();
  return (rs.results as any[]).map((r) => r.group_name);
}

export interface DbMessage { id: number; sender: string | null; message: string; timestamp: string; }

// Unclassified messages for one chat, oldest first. `limit` caps how many we pull so one cron run
// can't accumulate unbounded subrequests on a big backlog (the rest waits for the next run).
export async function unclassifiedFor(DB: D1Database, group: string, limit = 500): Promise<DbMessage[]> {
  const rs = await DB.prepare(
    `SELECT id, sender, message, timestamp FROM captured_messages
     WHERE group_name = ?1 AND classified_at IS NULL ORDER BY timestamp ASC, id ASC LIMIT ?2`,
  ).bind(group, limit).all();
  return rs.results as unknown as DbMessage[];
}

export async function getSummary(DB: D1Database, group: string): Promise<string | null> {
  const row = await DB.prepare(
    "SELECT situation_summary FROM chat_state WHERE group_name = ?1",
  ).bind(group).first<{ situation_summary: string }>();
  return row?.situation_summary ?? null;
}

// Recent trips for a chat (registry fed into the prompt). Capped + freshness-filtered.
export async function getTrips(DB: D1Database, group: string, limit = 40, staleDays = 21): Promise<TripRef[]> {
  const cutoff = phFromEpoch(Date.now() - staleDays * 86400_000);
  const rs = await DB.prepare(
    `SELECT trip_reference, reference_type, driver, helper FROM trips
     WHERE group_name = ?1 AND (last_seen IS NULL OR last_seen >= ?2)
     ORDER BY last_seen DESC LIMIT ?3`,
  ).bind(group, cutoff, limit).all();
  return rs.results as unknown as TripRef[];
}

export async function saveSummary(DB: D1Database, group: string, summary: string): Promise<void> {
  const now = nowPh();
  await DB.prepare(
    `INSERT INTO chat_state (group_name, situation_summary, updated_at) VALUES (?1, ?2, ?3)
     ON CONFLICT(group_name) DO UPDATE SET situation_summary = ?2, updated_at = ?3`,
  ).bind(group, summary, now).run();
}

// Upsert many trips in as few subrequests as possible (batched, deduped within the call).
export async function batchUpsertTrips(
  DB: D1Database, group: string, refs: { ref: string; type: string | null }[], seenAt: string,
): Promise<void> {
  const seen = new Set<string>();
  const stmts: D1PreparedStatement[] = [];
  for (const { ref, type } of refs) {
    if (!ref || seen.has(ref)) continue;
    seen.add(ref);
    stmts.push(DB.prepare(
      `INSERT INTO trips (group_name, trip_reference, reference_type, first_seen, last_seen)
       VALUES (?1, ?2, ?3, ?4, ?4)
       ON CONFLICT(group_name, trip_reference) DO UPDATE SET
         last_seen = ?4,
         reference_type = COALESCE(trips.reference_type, ?3)`,
    ).bind(group, ref, type, seenAt));
  }
  for (let i = 0; i < stmts.length; i += STMTS_PER_BATCH) {
    await DB.batch(stmts.slice(i, i + STMTS_PER_BATCH));
  }
}

// ── Trip identity binding (plate ↔ container) ────────────────────────
// A job sheet names BOTH a truck plate and its container — proof they're the SAME trip. We remember
// that pairing so an incidental reported under a plate collapses under the container (the canonical,
// client-facing trip id) instead of showing up as a separate trip.

// Load this chat's known alias→canonical map (e.g. NJR7871 → ONEU3027491).
export async function getTripLinks(DB: D1Database, group: string): Promise<Map<string, string>> {
  const rs = await DB.prepare(
    "SELECT alias, canonical FROM trip_links WHERE group_name = ?1",
  ).bind(group).all();
  const m = new Map<string, string>();
  for (const r of rs.results as any[]) m.set(r.alias, r.canonical);
  return m;
}

// Remember new alias→canonical pairs (batched, idempotent).
export async function upsertTripLinks(
  DB: D1Database, group: string, pairs: { alias: string; canonical: string }[],
): Promise<void> {
  const now = nowPh();
  const seen = new Set<string>();
  const stmts: D1PreparedStatement[] = [];
  for (const { alias, canonical } of pairs) {
    if (!alias || !canonical || alias === canonical || seen.has(alias)) continue;
    seen.add(alias);
    stmts.push(DB.prepare(
      `INSERT INTO trip_links (group_name, alias, canonical, updated_at) VALUES (?1, ?2, ?3, ?4)
       ON CONFLICT(group_name, alias) DO UPDATE SET canonical = ?3, updated_at = ?4`,
    ).bind(group, alias, canonical, now));
  }
  for (let i = 0; i < stmts.length; i += STMTS_PER_BATCH) {
    await DB.batch(stmts.slice(i, i + STMTS_PER_BATCH));
  }
}

// Rewrite already-stored rows that used an alias so they point at the canonical id — so a job sheet
// arriving AFTER an incidental was logged still merges the history (not just future messages).
export async function applyTripLinks(
  DB: D1Database, group: string, pairs: { alias: string; canonical: string }[],
): Promise<void> {
  const seen = new Set<string>();
  const stmts: D1PreparedStatement[] = [];
  for (const { alias, canonical } of pairs) {
    if (!alias || !canonical || alias === canonical || seen.has(alias)) continue;
    seen.add(alias);
    stmts.push(DB.prepare(
      `UPDATE captured_messages SET trip_reference = ?3, reference_type = 'container_number'
       WHERE group_name = ?1 AND trip_reference = ?2`,
    ).bind(group, alias, canonical));
    stmts.push(DB.prepare(
      `UPDATE incidentals SET trip_reference = ?2
       WHERE trip_reference = ?1 AND message_id IN (SELECT id FROM captured_messages WHERE group_name = ?3)`,
    ).bind(alias, canonical, group));
  }
  for (let i = 0; i < stmts.length; i += STMTS_PER_BATCH) {
    await DB.batch(stmts.slice(i, i + STMTS_PER_BATCH));
  }
}

// Persist one chunk's classification: mark all chunk messages classified, and for the
// incidental-bearing ones set trip fields + insert incidental rows. All writes are batched into
// DB.batch() calls (few subrequests) instead of one await per row. INSERT OR IGNORE + the UNIQUE
// constraint make a re-classified message idempotent (no duplicate incidental rows).
export async function saveChunkResults(
  DB: D1Database,
  chunkIds: number[],
  results: BatchResult[],
  tripStamp?: Map<number, { ref: string; type: string }>,
): Promise<{ incidentals: number }> {
  const now = nowPh();
  const byId = new Map(results.map((r) => [String(r.id), r]));
  let incidentalCount = 0;
  const stmts: D1PreparedStatement[] = [];

  // Canonicalize a plate trip_reference to uppercase so "Mat4846" and "MAT4846" are the SAME trip
  // (the regex seeds plates uppercased, but Groq may echo one in mixed case → they'd split otherwise).
  const canonTrip = (ref: string | null, type: string | null) =>
    ref && type === "plate_number" ? ref.toUpperCase() : ref;

  for (const id of chunkIds) {
    const r = byId.get(String(id));
    const stamp = tripStamp?.get(Number(id));
    if (!r) {
      // Clean message (no incidental). Still stamp its trip_reference from the container/plate regex
      // so plain job sheets / status updates link to their trip — not only incidental-bearing rows.
      if (stamp) {
        stmts.push(DB.prepare(
          `UPDATE captured_messages SET classified_at = ?2, is_incidental = 0,
             trip_reference = ?3, reference_type = ?4, reference_source = 'regex' WHERE id = ?1`,
        ).bind(id, now, stamp.ref, stamp.type));
      } else {
        stmts.push(DB.prepare(
          "UPDATE captured_messages SET classified_at = ?2, is_incidental = 0 WHERE id = ?1",
        ).bind(id, now));
      }
      continue;
    }
    // Prefer Groq's trip reference; fall back to the regex stamp when Groq gave none.
    let trip = canonTrip(r.trip_reference, r.reference_type);
    let refType = r.reference_type;
    let refSource = r.reference_source;
    if (!trip && stamp) { trip = stamp.ref; refType = stamp.type; refSource = "regex"; }
    stmts.push(DB.prepare(
      `UPDATE captured_messages SET classified_at = ?2, is_incidental = 1,
         trip_reference = ?3, reference_type = ?4, reference_source = ?5 WHERE id = ?1`,
    ).bind(id, now, trip, refType, refSource));

    for (const inc of r.incidentals) {
      stmts.push(DB.prepare(
        `INSERT OR IGNORE INTO incidentals
           (message_id, incidental_type, status, confidence, amount, trip_reference)
         VALUES (?1, ?2, ?3, ?4, NULL, ?5)`,
      ).bind(id, inc.incidental_type, inc.status, inc.confidence, trip));
      incidentalCount++;
    }
  }

  for (let i = 0; i < stmts.length; i += STMTS_PER_BATCH) {
    await DB.batch(stmts.slice(i, i + STMTS_PER_BATCH));
  }
  return { incidentals: incidentalCount };
}

// ── Ops health (#5) ──────────────────────────────────────────────────
// Record the outcome of the last cron run so a silent failure (broken parser feeding empty text,
// dead/expired Groq key, etc.) is visible at GET /health instead of vanishing into the logs.
export async function setSystemStatus(DB: D1Database, key: string, value: string): Promise<void> {
  await DB.prepare(
    `INSERT INTO system_status (key, value, updated_at) VALUES (?1, ?2, ?3)
     ON CONFLICT(key) DO UPDATE SET value = ?2, updated_at = ?3`,
  ).bind(key, value, nowPh()).run();
}

export async function getSystemStatus(DB: D1Database): Promise<Record<string, { value: string; updated_at: string }>> {
  const rs = await DB.prepare("SELECT key, value, updated_at FROM system_status").all();
  const out: Record<string, { value: string; updated_at: string }> = {};
  for (const r of rs.results as any[]) out[r.key] = { value: r.value, updated_at: r.updated_at };
  return out;
}

export interface Metrics {
  cron_runs: number;       // how many classifier runs have completed
  total_batches: number;   // Groq API calls made (usage scales with this, not messages)
  total_tokens: number;    // Groq tokens consumed (prompt + completion), across all runs
  total_incidentals: number;
  total_processed: number; // messages classified
  last_run_at: string;
}

// Read-modify-write the cumulative metrics row. Cron runs are sequential and 6h apart, so there's
// no race here. Stored as one JSON blob under the 'metrics' key in system_status; surfaced at /health.
export async function bumpMetrics(
  DB: D1Database,
  delta: { batches: number; tokens: number; incidentals: number; processed: number },
): Promise<Metrics> {
  const row = await DB.prepare("SELECT value FROM system_status WHERE key = 'metrics'").first<{ value: string }>();
  const m: Metrics = {
    cron_runs: 0, total_batches: 0, total_tokens: 0, total_incidentals: 0, total_processed: 0, last_run_at: "",
  };
  if (row?.value) { try { Object.assign(m, JSON.parse(row.value)); } catch {} }
  m.cron_runs += 1;
  m.total_batches += delta.batches;
  m.total_tokens += delta.tokens;
  m.total_incidentals += delta.incidentals;
  m.total_processed += delta.processed;
  m.last_run_at = nowPh();
  await setSystemStatus(DB, "metrics", JSON.stringify(m));
  return m;
}

// Normalize a date filter to a PH bound: a bare "2026-07-07" becomes the start (or end) of that PH
// day; a full datetime is normalized to PH. Lets ?since=/&until= accept either form. Stored
// timestamps are PH "...+08:00" strings, so these bounds must be too for the string compare to hold.
function normDate(s: string, endOfDay = false): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s + (endOfDay ? "T23:59:59+08:00" : "T00:00:00+08:00");
  return toPh(s);
}

type IncFilter = { trip?: string | null; status?: string | null; group?: string | null; since?: string | null; until?: string | null; limit?: number };

// GET /incidentals — join incidentals to their message, newest first, optional filters.
export async function queryIncidentals(DB: D1Database, opts: IncFilter): Promise<any[]> {
  const clauses: string[] = [];
  const binds: any[] = [];
  if (opts.trip) { binds.push(opts.trip); clauses.push(`i.trip_reference = ?${binds.length}`); }
  if (opts.status) { binds.push(opts.status); clauses.push(`i.status = ?${binds.length}`); }
  if (opts.group) { binds.push(`%${opts.group}%`); clauses.push(`m.group_name LIKE ?${binds.length}`); }
  if (opts.since) { binds.push(normDate(opts.since)); clauses.push(`m.timestamp >= ?${binds.length}`); }
  if (opts.until) { binds.push(normDate(opts.until, true)); clauses.push(`m.timestamp <= ?${binds.length}`); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  binds.push(Math.min(opts.limit ?? 200, 10000));

  const rs = await DB.prepare(
    `SELECT i.id, i.incidental_type, i.status, i.confidence, i.trip_reference,
            m.group_name, m.source, m.sender, m.message, m.timestamp
     FROM incidentals i JOIN captured_messages m ON m.id = i.message_id
     ${where}
     ORDER BY m.timestamp DESC LIMIT ?${binds.length}`,
  ).bind(...binds).all();
  return rs.results as any[];
}

// GET /incidentals?view=trips — one row per trip: how many incidentals, split confirmed/possible,
// the distinct types, and the time span. Answers "which trips have incidentals" at a glance.
export async function queryIncidentalsByTrip(
  DB: D1Database,
  opts: { status?: string | null; group?: string | null },
): Promise<any[]> {
  const clauses: string[] = [];
  const binds: any[] = [];
  if (opts.status) { binds.push(opts.status); clauses.push(`i.status = ?${binds.length}`); }
  if (opts.group) { binds.push(`%${opts.group}%`); clauses.push(`m.group_name LIKE ?${binds.length}`); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";

  const rs = await DB.prepare(
    `SELECT COALESCE(i.trip_reference, '(unlinked)') AS trip_reference,
            m.group_name,
            COUNT(*) AS incidentals,
            SUM(CASE WHEN i.status = 'confirmed' THEN 1 ELSE 0 END) AS confirmed,
            SUM(CASE WHEN i.status = 'possible'  THEN 1 ELSE 0 END) AS possible,
            GROUP_CONCAT(DISTINCT i.incidental_type) AS types,
            MIN(m.timestamp) AS first_seen, MAX(m.timestamp) AS last_seen
     FROM incidentals i JOIN captured_messages m ON m.id = i.message_id
     ${where}
     GROUP BY i.trip_reference, m.group_name
     ORDER BY incidentals DESC, last_seen DESC`,
  ).bind(...binds).all();
  return rs.results as any[];
}

// GET /messages — full stored message history: each message PLUS its trip link and any incidentals
// (type + status) it carries, so one call gives the complete picture (the stored_messages export as
// an API). incidentals is an array so a message with multiple is fully represented.
export async function queryMessages(
  DB: D1Database,
  opts: { group?: string | null; since?: string | null; until?: string | null; incidental?: string | null; limit?: number },
): Promise<any[]> {
  const clauses: string[] = [];
  const binds: any[] = [];
  if (opts.group) { binds.push(`%${opts.group}%`); clauses.push(`m.group_name LIKE ?${binds.length}`); }
  if (opts.since) { binds.push(normDate(opts.since)); clauses.push(`m.timestamp >= ?${binds.length}`); }
  if (opts.until) { binds.push(normDate(opts.until, true)); clauses.push(`m.timestamp <= ?${binds.length}`); }
  if (opts.incidental === "1" || opts.incidental === "true") { clauses.push(`m.is_incidental = 1`); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  binds.push(Math.min(opts.limit ?? 200, 10000));

  // LEFT JOIN so clean messages still return; GROUP_CONCAT rolls a message's incidentals into one
  // "type:status|type:status" string we split back into an array below.
  const rs = await DB.prepare(
    `SELECT m.id, m.source, m.group_name, m.sender, m.message, m.timestamp,
            m.is_incidental, m.trip_reference, m.reference_type,
            GROUP_CONCAT(i.incidental_type || ':' || i.status, '|') AS inc_list
     FROM captured_messages m
     LEFT JOIN incidentals i ON i.message_id = m.id
     ${where}
     GROUP BY m.id
     ORDER BY m.timestamp DESC LIMIT ?${binds.length}`,
  ).bind(...binds).all();

  return (rs.results as any[]).map((r) => {
    const { inc_list, ...rest } = r;
    const incidentals = inc_list
      ? String(inc_list).split("|").map((s) => {
          const [incidental_type, status] = s.split(":");
          return { incidental_type, status };
        })
      : [];
    return { ...rest, incidentals };
  });
}
