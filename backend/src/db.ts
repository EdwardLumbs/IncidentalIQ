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

const DEDUP_WINDOW_MS = 10 * 60 * 1000; // 10 min, matches on-device dedup

function normalizeSource(s: string): string {
  const v = (s || "").toLowerCase();
  if (v.includes("viber")) return "viber";
  if (v.includes("messenger") || v.includes("orca")) return "messenger";
  return v || "unknown";
}

function toIso(t?: string): string {
  if (!t) return new Date().toISOString();
  // Phone writes "2026-07-02T10:00:00" (no zone) — treat as UTC.
  const s = /[zZ]|[+-]\d{2}:?\d{2}$/.test(t) ? t : `${t}Z`;
  const d = new Date(s);
  return isNaN(d.getTime()) ? new Date().toISOString() : d.toISOString();
}

// Insert a batch of uploaded messages, skipping durable duplicates (same content_hash within 10 min).
export async function insertMessages(
  DB: D1Database,
  msgs: IncomingMessage[],
): Promise<{ inserted: number; skipped: number }> {
  let inserted = 0, skipped = 0;
  const now = new Date().toISOString();

  for (const m of msgs) {
    const source = normalizeSource(m.source);
    const group = (m.group_name ?? m.chat ?? "").trim();
    const message = (m.message ?? m.content ?? "").toString();
    const ts = toIso(m.timestamp ?? m.ts);
    const rawNotif = m.raw_notif ?? (m.via_accessibility ? 0 : 1);
    if (!group || !message) { skipped++; continue; }

    const hash = await contentHash(source, group, message);

    // Dedup: same hash within +/- 10 min already stored?
    const lo = new Date(new Date(ts).getTime() - DEDUP_WINDOW_MS).toISOString();
    const hi = new Date(new Date(ts).getTime() + DEDUP_WINDOW_MS).toISOString();
    const dup = await DB.prepare(
      "SELECT id FROM captured_messages WHERE content_hash = ?1 AND timestamp >= ?2 AND timestamp <= ?3 LIMIT 1",
    ).bind(hash, lo, hi).first();
    if (dup) { skipped++; continue; }

    await DB.prepare(
      `INSERT INTO captured_messages
        (source, group_name, sender, message, image_url, timestamp, raw_notif, content_hash, synced_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`,
    ).bind(source, group, m.sender ?? null, message, m.image_url ?? null, ts, rawNotif, hash, now).run();
    inserted++;
  }

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

// Unclassified messages for one chat, oldest first.
export async function unclassifiedFor(DB: D1Database, group: string): Promise<DbMessage[]> {
  const rs = await DB.prepare(
    `SELECT id, sender, message, timestamp FROM captured_messages
     WHERE group_name = ?1 AND classified_at IS NULL ORDER BY timestamp ASC, id ASC`,
  ).bind(group).all();
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
  const cutoff = new Date(Date.now() - staleDays * 86400_000).toISOString();
  const rs = await DB.prepare(
    `SELECT trip_reference, reference_type, driver, helper FROM trips
     WHERE group_name = ?1 AND (last_seen IS NULL OR last_seen >= ?2)
     ORDER BY last_seen DESC LIMIT ?3`,
  ).bind(group, cutoff, limit).all();
  return rs.results as unknown as TripRef[];
}

export async function saveSummary(DB: D1Database, group: string, summary: string): Promise<void> {
  const now = new Date().toISOString();
  await DB.prepare(
    `INSERT INTO chat_state (group_name, situation_summary, updated_at) VALUES (?1, ?2, ?3)
     ON CONFLICT(group_name) DO UPDATE SET situation_summary = ?2, updated_at = ?3`,
  ).bind(group, summary, now).run();
}

export async function upsertTrip(
  DB: D1Database, group: string, ref: string, type: string | null, seenAt: string,
): Promise<void> {
  await DB.prepare(
    `INSERT INTO trips (group_name, trip_reference, reference_type, first_seen, last_seen)
     VALUES (?1, ?2, ?3, ?4, ?4)
     ON CONFLICT(group_name, trip_reference) DO UPDATE SET
       last_seen = ?4,
       reference_type = COALESCE(trips.reference_type, ?3)`,
  ).bind(group, ref, type, seenAt).run();
}

// Persist one chunk's classification: mark all chunk messages classified, and for the
// incidental-bearing ones set trip fields + insert incidental rows.
export async function saveChunkResults(
  DB: D1Database,
  chunkIds: number[],
  results: BatchResult[],
): Promise<{ incidentals: number }> {
  const now = new Date().toISOString();
  const byId = new Map(results.map((r) => [String(r.id), r]));
  let incidentalCount = 0;

  for (const id of chunkIds) {
    const r = byId.get(String(id));
    if (!r) {
      // Clean message — just mark it processed.
      await DB.prepare(
        "UPDATE captured_messages SET classified_at = ?2, is_incidental = 0 WHERE id = ?1",
      ).bind(id, now).run();
      continue;
    }
    await DB.prepare(
      `UPDATE captured_messages SET classified_at = ?2, is_incidental = 1,
         trip_reference = ?3, reference_type = ?4, reference_source = ?5 WHERE id = ?1`,
    ).bind(id, now, r.trip_reference, r.reference_type, r.reference_source).run();

    for (const inc of r.incidentals) {
      await DB.prepare(
        `INSERT INTO incidentals (message_id, incidental_type, status, confidence, amount, trip_reference)
         VALUES (?1, ?2, ?3, ?4, NULL, ?5)`,
      ).bind(id, inc.incidental_type, inc.status, inc.confidence, r.trip_reference).run();
      incidentalCount++;
    }
  }
  return { incidentals: incidentalCount };
}

// GET /incidentals — join incidentals to their message, newest first, optional filters.
export async function queryIncidentals(
  DB: D1Database,
  opts: { trip?: string | null; status?: string | null; limit?: number },
): Promise<any[]> {
  const clauses: string[] = [];
  const binds: any[] = [];
  if (opts.trip) { binds.push(opts.trip); clauses.push(`i.trip_reference = ?${binds.length}`); }
  if (opts.status) { binds.push(opts.status); clauses.push(`i.status = ?${binds.length}`); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  binds.push(Math.min(opts.limit ?? 200, 1000));

  const rs = await DB.prepare(
    `SELECT i.id, i.incidental_type, i.status, i.confidence, i.trip_reference,
            m.group_name, m.source, m.sender, m.message, m.timestamp
     FROM incidentals i JOIN captured_messages m ON m.id = i.message_id
     ${where}
     ORDER BY m.timestamp DESC LIMIT ?${binds.length}`,
  ).bind(...binds).all();
  return rs.results as any[];
}
