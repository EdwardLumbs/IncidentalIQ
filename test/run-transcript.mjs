// Runs the classifier over the FULL real chat transcript (chat-transcript.json),
// not the hand-picked cases. Each message is classified with the previous few
// messages as context. Results saved to test/transcript-results.json.
//
// Usage:
//   GROQ_MODEL="llama-3.3-70b-versatile" node test/run-transcript.mjs
//
// Env knobs:
//   GROQ_MODEL   - model (default llama-3.3-70b-versatile)
//   THROTTLE_MS  - wait between calls (default 6500 = ~9/min, safe under 12k TPM)
//   HISTORY      - how many prior text messages to include as context (default 5)
//   LIMIT        - only process the first N messages (for a quick partial run)

import { readFile, writeFile } from "node:fs/promises";
import { classify, loadEnvFile } from "./classifier.mjs";

await loadEnvFile(new URL("../.env", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));

const apiKey = process.env.GROQ_API_KEY;
const model = process.env.GROQ_MODEL || "llama-3.3-70b-versatile";
const throttleMs = process.env.THROTTLE_MS !== undefined ? Number(process.env.THROTTLE_MS) : 6500;
const HISTORY = process.env.HISTORY !== undefined ? Number(process.env.HISTORY) : 5;
const LIMIT = process.env.LIMIT !== undefined ? Number(process.env.LIMIT) : Infinity;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (!apiKey) { console.error("Missing GROQ_API_KEY (.env or env var)"); process.exit(1); }

const transcript = JSON.parse(await readFile(new URL("./chat-transcript.json", import.meta.url)));
const msgs = transcript.messages;

// A message is worth classifying if it has real text (not a pure [thumbs up]/[image]/[file] marker).
const hasText = (m) => {
  const t = (m.text || "").trim();
  return t.length > 0 && !/^\[[^\]]*\]$/.test(t);
};

const toProcess = msgs.filter(hasText).slice(0, LIMIT);
console.log(`Transcript: ${msgs.length} total messages, ${toProcess.length} have real text to classify.`);
console.log(`Model: ${model} | throttle: ${throttleMs}ms | history: ${HISTORY}`);
console.log(`Estimated time: ~${Math.ceil((toProcess.length * Math.max(throttleMs, 600)) / 60000)} min\n`);

const results = [];
let done = 0;

for (let i = 0; i < msgs.length; i++) {
  const m = msgs[i];
  if (!hasText(m)) continue;
  if (done >= LIMIT) break;

  // Build history: the previous HISTORY text messages, oldest first.
  const hist = [];
  for (let j = i - 1; j >= 0 && hist.length < HISTORY; j--) {
    if (hasText(msgs[j])) hist.unshift({ sender: msgs[j].sender, message: msgs[j].text });
  }

  if (done > 0 && throttleMs > 0) await sleep(throttleMs);

  try {
    const r = await classify({ apiKey, model, history: hist, sender: m.sender, message: m.text, verbose: false });
    const types = (r.incidentals ?? []).map((x) => `${x.incidental_type}/${x.status}@${x.confidence}`);
    const tag = r.is_incidental ? `🔴 [${types.join(", ")}] trip=${r.trip_reference}` : "·";
    console.log(`#${m.i} (${m.date} ${m.time}) ${m.sender}: ${m.text.slice(0, 70)}\n     ${tag}`);
    results.push({
      i: m.i, date: m.date, time: m.time, sender: m.sender, message: m.text,
      is_incidental: r.is_incidental, incidentals: r.incidentals ?? [],
      trip_reference: r.trip_reference, reference_type: r.reference_type, reference_source: r.reference_source,
    });
  } catch (e) {
    console.log(`#${m.i} ERROR: ${e.message}`);
    results.push({ i: m.i, sender: m.sender, message: m.text, error: e.message });
  }
  done++;
}

await writeFile(new URL("./transcript-results.json", import.meta.url), JSON.stringify(results, null, 2));

// Summary: incidentals grouped by trip.
const flagged = results.filter((r) => r.is_incidental);
console.log(`\n${"=".repeat(70)}`);
console.log(`SUMMARY: ${flagged.length} of ${results.length} classified messages flagged as incidentals`);

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
