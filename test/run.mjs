// Test runner: sends each case to Groq and prints the classification — verbosely.
//
// Usage:
//   $env:GROQ_API_KEY="gsk_..."   # PowerShell  (or put it in a .env file at repo root)
//   node test/run.mjs
//
// Env knobs:
//   GROQ_MODEL  - override model (default llama-3.1-8b-instant)
//   QUIET=1     - turn OFF the per-request verbose logging

import { classify, loadEnvFile, buildSystemPrompt } from "./classifier.mjs";
import { CASES } from "./cases.mjs";

await loadEnvFile(new URL("../.env", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));

const apiKey = process.env.GROQ_API_KEY;
const model = process.env.GROQ_MODEL || "llama-3.1-8b-instant";
const verbose = process.env.QUIET !== "1";
// Proactive throttle: wait this long BEFORE each call so we stay under ~10/min (12k TPM).
// ~6.5s ≈ 9 calls/min. Does NOT change usage — only pacing. Set THROTTLE_MS=0 to disable.
const throttleMs = process.env.THROTTLE_MS !== undefined ? Number(process.env.THROTTLE_MS) : 6500;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (!apiKey) {
  console.error("\n  Missing GROQ_API_KEY.");
  console.error("  PowerShell:  $env:GROQ_API_KEY=\"gsk_...\"; node test/run.mjs");
  console.error("  or put it in a .env file at the repo root: GROQ_API_KEY=gsk_...\n");
  process.exit(1);
}

const line = (c = "=") => c.repeat(72);

console.log(`\n${line()}`);
console.log(`  GROQ INCIDENTAL CLASSIFIER — TEST RUN`);
console.log(`  model: ${model}`);
console.log(`  cases: ${CASES.length}`);
console.log(`  verbose: ${verbose}  (set QUIET=1 to silence per-request logs)`);
console.log(line());

// Show the exact system prompt (the AI's full context) once.
console.log("\n### SYSTEM PROMPT SENT WITH EVERY CALL ###\n");
console.log(buildSystemPrompt());
console.log(`\n${line()}`);

let isIncidentalCorrect = 0;
let typeCorrect = 0;
let typeChecked = 0;
let totalTokens = 0;
const results = [];

for (let i = 0; i < CASES.length; i++) {
  const c = CASES[i];
  console.log(`\n${line("-")}`);
  console.log(`CASE ${i + 1}/${CASES.length}: ${c.name}`);
  console.log(line("-"));

  if (i > 0 && throttleMs > 0) {
    console.log(`  (throttle: waiting ${throttleMs}ms to stay under ~10/min)`);
    await sleep(throttleMs);
  }

  try {
    const r = await classify({ apiKey, model, history: c.history, sender: c.sender, message: c.message, verbose });

    const types = (r.incidentals ?? []).map((x) => x.incidental_type);
    const incOk = c.expect?.is_incidental === undefined || r.is_incidental === c.expect.is_incidental;
    if (incOk) isIncidentalCorrect++;

    let typeMark = "—";
    if (c.expect?.incidental_type) {
      typeChecked++;
      const ok = types.includes(c.expect.incidental_type);
      if (ok) typeCorrect++;
      typeMark = ok ? `OK (${c.expect.incidental_type} in [${types.join(", ")}])`
                    : `WANT ${c.expect.incidental_type} / GOT [${types.join(", ")}]`;
    }
    if (r._meta?.usage) totalTokens += r._meta.usage.total_tokens;

    console.log("  ── PARSED RESULT ───────────────────────────────────");
    console.log(`  message       : "${c.message}"`);
    console.log(`  is_incidental : ${r.is_incidental}   [${incOk ? "PASS" : "FAIL"}]`);
    console.log(`  incidentals   : [${(r.incidentals ?? []).map((x) => `${x.incidental_type}/${x.status}@${x.confidence}`).join(", ")}]`);
    console.log(`  type check    : ${typeMark}`);
    console.log(`  trip_reference: ${r.trip_reference}  (${r.reference_type}, source=${r.reference_source})`);
    console.log(`  latency       : ${r._meta?.latencyMs}ms`);

    results.push({ message: c.message.slice(0, 40), incOk, types: types.join(", "), trip: r.trip_reference });
  } catch (e) {
    console.log(`  [ERROR] ${e.message}`);
    results.push({ case: c.name, error: e.message });
  }
}

console.log(`\n${line()}`);
console.log("  SUMMARY");
console.log(line());
console.log(`  is_incidental correct : ${isIncidentalCorrect}/${CASES.length}`);
if (typeChecked) console.log(`  incidental_type correct: ${typeCorrect}/${typeChecked} (of incidentals)`);
console.log(`  total tokens used     : ${totalTokens}`);
console.log(`\n  RESULTS TABLE (compact):`);
console.table(results.map((r) => ({
  message: r.message,
  ok: r.incOk,
  types: r.types,
  trip: r.trip,
})));
console.log("");
