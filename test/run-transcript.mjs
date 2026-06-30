// Runs the BATCHED classifier over the full real chat transcript (chat-transcript.json).
// Messages are grouped into batches and each batch is classified in ONE Groq call, with a
// few trailing messages from the previous batch carried over as read-only context. This is
// the production shape: one call per batch (not per message), paying the rulebook once.
// Results saved to test/transcript-results.json.
//
// Usage:
//   GROQ_MODEL="openai/gpt-oss-120b" node test/run-transcript.mjs
//
// Env knobs:
//   GROQ_MODEL   - model (default llama-3.3-70b-versatile)
//   BATCH_SIZE   - messages classified per Groq call (default 25)
//   CONTEXT      - trailing messages from the previous batch used as context (default 5)
//   THROTTLE_MS  - wait between batch calls (default 2000)
//   LIMIT        - only process the first N text messages (quick partial run)

import { readFile, writeFile } from "node:fs/promises";
import { classifyBatch, loadEnvFile } from "./classifier.mjs";

await loadEnvFile(new URL("../.env", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));

const apiKey = process.env.GROQ_API_KEY;
const model = process.env.GROQ_MODEL || "llama-3.3-70b-versatile";
const BATCH_SIZE = process.env.BATCH_SIZE !== undefined ? Number(process.env.BATCH_SIZE) : 25;
const CONTEXT = process.env.CONTEXT !== undefined ? Number(process.env.CONTEXT) : 5;
const throttleMs = process.env.THROTTLE_MS !== undefined ? Number(process.env.THROTTLE_MS) : 2000;
const LIMIT = process.env.LIMIT !== undefined ? Number(process.env.LIMIT) : Infinity;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (!apiKey) { console.error("Missing GROQ_API_KEY (.env or env var)"); process.exit(1); }

const transcript = JSON.parse(await readFile(new URL("./chat-transcript.json", import.meta.url)));

// A message is worth classifying if it has real text (not a pure [thumbs up]/[image]/[file] marker).
const hasText = (m) => {
  const t = (m.text || "").trim();
  return t.length > 0 && !/^\[[^\]]*\]$/.test(t);
};

const toProcess = transcript.messages.filter(hasText).slice(0, LIMIT);
const nBatches = Math.ceil(toProcess.length / BATCH_SIZE);
console.log(`Transcript: ${transcript.messages.length} total, ${toProcess.length} with real text.`);
console.log(`Model: ${model} | batch: ${BATCH_SIZE} → ${nBatches} call(s) | context: ${CONTEXT} | throttle: ${throttleMs}ms\n`);

// id → original message, so we can map results back after classifying.
const byId = new Map(toProcess.map((m) => [String(m.i), m]));
const results = [];

for (let b = 0; b < nBatches; b++) {
  const start = b * BATCH_SIZE;
  const slice = toProcess.slice(start, start + BATCH_SIZE);
  const ctxSlice = toProcess.slice(Math.max(0, start - CONTEXT), start);

  const messages = slice.map((m) => ({ id: String(m.i), sender: m.sender, content: m.text }));
  const context = ctxSlice.map((m) => ({ sender: m.sender, message: m.text }));

  if (b > 0 && throttleMs > 0) await sleep(throttleMs);

  console.log(`── batch ${b + 1}/${nBatches} (messages ${slice[0].i}…${slice[slice.length - 1].i}) ──`);
  try {
    const { results: r, _meta } = await classifyBatch({ apiKey, model, context, messages, verbose: false });
    const hit = new Map(r.map((x) => [x.id, x]));
    for (const m of slice) {
      const x = hit.get(String(m.i));
      if (x) {
        const types = x.incidentals.map((y) => `${y.incidental_type}/${y.status}@${y.confidence}`);
        console.log(`  🔴 #${m.i} ${m.sender}: ${m.text.slice(0, 60)}\n       [${types.join(", ")}] trip=${x.trip_reference}`);
        results.push({ i: m.i, date: m.date, time: m.time, sender: m.sender, message: m.text,
          is_incidental: true, incidentals: x.incidentals,
          trip_reference: x.trip_reference, reference_type: x.reference_type, reference_source: x.reference_source });
      } else {
        results.push({ i: m.i, date: m.date, time: m.time, sender: m.sender, message: m.text,
          is_incidental: false, incidentals: [], trip_reference: null, reference_type: null, reference_source: null });
      }
    }
    if (_meta?.rateLimit) console.log(`     quota left → req ${_meta.rateLimit.reqLeft}/${_meta.rateLimit.reqLimit} | tok ${_meta.rateLimit.tokLeft}/${_meta.rateLimit.tokLimit}`);
  } catch (e) {
    console.log(`  batch ERROR: ${e.message}`);
    for (const m of slice) results.push({ i: m.i, sender: m.sender, message: m.text, error: e.message });
  }
}

results.sort((a, b) => a.i - b.i);
await writeFile(new URL("./transcript-results.json", import.meta.url), JSON.stringify(results, null, 2));

// Summary: incidentals grouped by trip.
const flagged = results.filter((r) => r.is_incidental);
console.log(`\n${"=".repeat(70)}`);
console.log(`SUMMARY: ${flagged.length} of ${results.length} classified messages flagged as incidentals`);
console.log(`Groq calls made: ${nBatches} (vs ${toProcess.length} if one-per-message)`);

const byTrip = {};
for (const r of flagged) {
  const key = r.trip_reference || "(unknown trip)";
  byTrip[key] ??= { confirmed: new Set(), possible: new Set() };
  for (const x of r.incidentals) byTrip[key][x.status === "confirmed" ? "confirmed" : "possible"].add(x.incidental_type);
}
console.log(`\nIncidentals grouped by trip:`);
for (const [trip, s] of Object.entries(byTrip)) {
  console.log(`  ${trip}:`);
  if (s.confirmed.size) console.log(`     CONFIRMED: ${[...s.confirmed].join(", ")}`);
  if (s.possible.size)  console.log(`     possible : ${[...s.possible].join(", ")}`);
}
console.log(`\nFull results saved to test/transcript-results.json`);
