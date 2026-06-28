// Groq incidental classifier — shared logic (reused later by the Cloudflare Worker).
// No external deps; uses Node 18+ built-in fetch.

// Condensed type list = the AI's context. Source of truth is docs/incidental-types.md.
// Keep this in sync with that doc. [key, one-line definition]
export const INCIDENTAL_TYPES = [
  ["bobtail",             "truck head runs without its chassis/container (left behind)"],
  ["chassis_rental",      "our chassis tied up holding client's empty (no pre-advise where to return)"],
  ["truck_demurrage",     "truck stuck holding the empty too long, no return slot/pre-advise"],
  ["detention",           "shipping-line 'Det'/detention charge for holding container/equipment past free time"],
  ["diversion_fee",       "empty re-used/diverted to another port or far location; diversion registration/charge"],
  ["lolo_charges",        "lift-on / lift-off charges at a CY"],
  ["safekeeping_charges", "CY charge for temporarily grounding a container at a non-pre-advise CY"],
  ["storage_fees",        "CY/port storage of a container (~safekeeping; use the literal word)"],
  ["foul_trip",           "trip cancelled after truck was ready (client gave no docs/confirmation)"],
  ["pullout_charges",     "cost to fetch/pull out a container again from grounding"],
  ["weighing_fee",        "truck/cargo weighed at a warehouse/port/CY"],
  ["overtime_charges",    "truck waiting very long with no one to offload"],
  ["xray_dea_charges",    "x-ray / DEA scan charge"],
  ["processing_fee",      "port/CY processing/paperwork charge"],
  ["entry_coupon",        "entry coupon paperwork charge"],
  ["entry_fee",           "entry fee paperwork charge"],
  ["mano_fee",            "manual ('mano') offloading charge by workers"],
  ["overweight_fee",      "over weight-limit charge"],
  ["lalamove_fee",        "using Lalamove/courier to send docs/seal/selyo (counts even if framed as saving money or no amount)"],
  ["delivery_permit",     "delivery permit paperwork charge"],
  ["documentation_fee",   "generic port/CY documentation charge"],
];

const TYPE_KEYS = INCIDENTAL_TYPES.map(([k]) => k);

export function buildSystemPrompt() {
  const typeLines = INCIDENTAL_TYPES.map(([k, d]) => `  ${k} - ${d}`).join("\n");
  return `You read messages from a Philippine container-trucking group chat (Taglish: mixed
Tagalog/English, with shorthand and typos). Most messages are routine operations.

GOAL: detect when a message discusses an INCIDENTAL cost on a trip (an extra charge/cost beyond the
base haul), and label each one as CONFIRMED or POSSIBLE. The peso AMOUNT DOES NOT MATTER and is
usually absent — never require an amount.

CONFIRMED = the cost is actually being incurred or already happened. e.g. they DID rent a chassis,
they HAD to bobtail, the empty IS stuck and cannot be returned, they ARE sending docs via Lalamove,
detention IS being charged.

POSSIBLE = the incidental is only discussed, anticipated, at risk, or being AVOIDED/prevented — not
confirmed as incurred. e.g. "to avoid Det charges", "baka may singil", rescheduling to dodge a fee,
a booking-slot/allocation shortage that might lead to demurrage, a wrong container pulled out that
might cost to fix. Capture it, but mark it possible.

Incidental types (output the key EXACTLY, or null):
${typeLines}

RULES:
- Every incidental you list MUST have status "confirmed" or "possible".
- Avoidance / prevention / risk talk ("avoid", "iwas", "para hindi", "baka", "might", "to prevent")
  = POSSIBLE, never confirmed (no charge has actually been incurred).
- Sending documents/seal/"selyo" via Lalamove or any courier = lalamove_fee, CONFIRMED — even if
  framed as saving money and with no amount (the courier cost IS being incurred).
- "Det" / detention = detention (confirmed only if actually being charged; avoiding it = possible).
- NOT incidentals at all (return an empty array): pure coordination — bookings, schedules/"tabs",
  gate passes, manifests, asking for truck location/address/details, job-sheet templates, plain
  status updates, driver/helper edits.

MULTIPLE INCIDENTALS: one message can mention more than one incidental type (e.g. "we rented a
chassis because we had to bobtail" = chassis_rental AND bobtail). List EVERY distinct type you
find. If none, return an empty array.

TRIP REFERENCE: the incidentals in a message almost always belong to ONE trip, identified by
container_number (e.g. TXGU5040257), consignee/client name, plate_number, or booking/trip ref. The
message often omits it — infer from the recent chat history. Use null if you truly cannot tell.

Return ONLY a JSON object with exactly these fields:
{
  "is_incidental": boolean,             // true iff incidentals[] is non-empty
  "incidentals": [                      // [] if none
    {
      "incidental_type": string,        // one of the keys above
      "status": "confirmed" | "possible",
      "confidence": number              // 0.0-1.0: certainty this is this incidental type at all
    }
  ],
  "trip_reference": string | null,      // the identifier VALUE, e.g. "TXGU5040257" or "Song Trading"
  "reference_type": string | null,      // "container_number" | "consignee" | "plate_number" | "trip_id" | "other"
  "reference_source": string | null     // "message" | "history" | null
}`;
}

function buildUserPrompt({ history = [], sender, message }) {
  let out = "";
  if (history.length) {
    out += "Recent chat history (oldest first, for context only):\n";
    for (const h of history) out += `${h.sender}: ${h.message}\n`;
    out += "\n";
  }
  out += "Classify THIS message:\n";
  out += `${sender ?? "unknown"}: ${message}`;
  return out;
}

// Minimal .env loader (no dependency). Reads KEY=VALUE lines from the given path if it exists.
export async function loadEnvFile(path) {
  try {
    const { readFile } = await import("node:fs/promises");
    const text = await readFile(path, "utf8");
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([\w.]+)\s*=\s*(.*)\s*$/);
      if (!m) continue;
      let v = m[2];
      if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
      if (!(m[1] in process.env)) process.env[m[1]] = v;
    }
  } catch { /* no .env, fine */ }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// log() prints only when verbose is on. Tagged so output is easy to scan.
const log = (verbose, ...args) => { if (verbose) console.log(...args); };

export async function classify({ apiKey, model, history, sender, message, maxRetries = 4, verbose = false }) {
  const systemPrompt = buildSystemPrompt();
  const userPrompt = buildUserPrompt({ history, sender, message });

  log(verbose, "  ── REQUEST ─────────────────────────────────────────");
  log(verbose, `  model: ${model}  temp: 0  json_mode: on`);
  log(verbose, `  system prompt: ${systemPrompt.length} chars`);
  if (history?.length) {
    log(verbose, `  history (${history.length} msgs):`);
    for (const h of history) log(verbose, `     · ${h.sender}: ${h.message}`);
  } else {
    log(verbose, "  history: (none)");
  }
  log(verbose, `  TARGET → ${sender ?? "unknown"}: ${message}`);

  const reqStart = Date.now();
  let res;
  for (let attempt = 0; ; attempt++) {
    if (attempt > 0) log(verbose, `  → attempt #${attempt + 1}`);
    res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model,
        temperature: 0,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: systemPrompt },
          { role: "user", content: userPrompt },
        ],
      }),
    });

    log(verbose, `  HTTP ${res.status} ${res.statusText} (${Date.now() - reqStart}ms)`);

    if (res.status === 429 && attempt < maxRetries) {
      const body = await res.text();
      // Groq tells us how long to wait: "Please try again in 1.42s"
      const m = body.match(/try again in ([\d.]+)s/i);
      const waitMs = m ? Math.ceil(parseFloat(m[1]) * 1000) + 250 : 2000 * (attempt + 1);
      log(verbose, `  ⏳ rate limited (429). waiting ${waitMs}ms then retrying...`);
      await sleep(waitMs);
      continue;
    }
    break;
  }

  if (!res.ok) {
    const body = await res.text();
    log(verbose, `  ✗ ERROR body: ${body}`);
    throw new Error(`Groq API ${res.status}: ${body}`);
  }

  const data = await res.json();
  const content = data.choices?.[0]?.message?.content ?? "";

  // Groq reports remaining quota in response headers (the leaky-bucket state).
  const h = res.headers;
  const rl = {
    reqLimit: h.get("x-ratelimit-limit-requests"),
    reqLeft: h.get("x-ratelimit-remaining-requests"),
    reqReset: h.get("x-ratelimit-reset-requests"),
    tokLimit: h.get("x-ratelimit-limit-tokens"),
    tokLeft: h.get("x-ratelimit-remaining-tokens"),
    tokReset: h.get("x-ratelimit-reset-tokens"),
  };

  log(verbose, "  ── RESPONSE ────────────────────────────────────────");
  if (data.usage) {
    log(verbose, `  tokens used this call: prompt=${data.usage.prompt_tokens} completion=${data.usage.completion_tokens} total=${data.usage.total_tokens}`);
  }
  log(verbose, `  quota left → requests: ${rl.reqLeft}/${rl.reqLimit} (resets in ${rl.reqReset}) | tokens: ${rl.tokLeft}/${rl.tokLimit} (resets in ${rl.tokReset})`);
  log(verbose, `  raw content: ${content}`);

  let parsed;
  try {
    parsed = JSON.parse(content);
  } catch {
    // strip code fences if the model wrapped it
    log(verbose, "  ⚠ direct JSON.parse failed, stripping code fences and retrying");
    const cleaned = content.replace(/```json|```/g, "").trim();
    parsed = JSON.parse(cleaned);
  }

  // light validation — flag any unknown type keys in the array
  const items = Array.isArray(parsed.incidentals) ? parsed.incidentals : [];
  const unknown = items.map((x) => x?.incidental_type).filter((t) => t && !TYPE_KEYS.includes(t));
  if (unknown.length) {
    parsed._warning = `unknown incidental_type(s): ${unknown.join(", ")}`;
    log(verbose, `  ⚠ ${parsed._warning}`);
  }

  // attach meta for callers that want it
  parsed._meta = { latencyMs: Date.now() - reqStart, usage: data.usage ?? null, rateLimit: rl };
  return parsed;
}

export { TYPE_KEYS };
