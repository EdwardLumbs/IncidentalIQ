# Trip Ops Incidental Monitor

## Project Overview
A system to automatically detect incidental charges discussed in client group chats across Viber and Messenger. Zero friction for clients or staff. Fully automated. Free to run.

## The Core Problem
Clients and staff discuss trip incidentals (extra charges) in 5 group chats across Viber and Messenger. We need to auto-detect these discussions and link them to trips without adding any steps for clients or staff.

## Why Not Viber/Messenger Bots
Viber and Messenger official APIs do NOT allow bots to read group chats. Bots only receive messages when users message the bot directly (1-on-1). Confirmed via Viber developer docs. This is by architectural design, not a missing feature. Will never change via official APIs.

## Solution
A dedicated Android phone running a custom Kotlin app. The phone is a silent member of all 5 group chats via a "Trip Ops" company account added manually to each group. The app reads messages two ways and forwards them to a backend for AI classification.

## Why Kotlin Not React Native
NotificationListenerService and AccessibilityService are pure Android OS-level APIs. React Native cannot access them natively — you would still need to write all the hard parts in Kotlin as Native Modules anyway. The app has no meaningful UI so React Native adds zero value and doubles complexity. Go full Kotlin.

---

## Tech Stack

### Android App (Kotlin)
Single APK containing UI + all background services.

**1. NotificationListenerService**
Catches all incoming message notifications instantly. Works with screen off and apps backgrounded. Extracts sender, group name, message preview, timestamp. Package filters: com.viber.voip for Viber, com.facebook.orca for Messenger. LIMITATION: notifications truncate at ~100-150 characters. If content is truncated or message is an image, triggers AccessibilityService flow.

**2. AccessibilityService**
Reads actual Viber/Messenger UI tree for full message content. On-demand: fires when notification content is truncated or is an image. Flow is: WakeLock wakes screen, launch app, navigate to chat, read full content, verify no new messages at bottom, close app, screen off. Also used for 8am catch-up: iterates through all tracked chats and reads all messages since last_seen_timestamp. Viber has unobfuscated UI tree, easy to parse by resource IDs. Messenger obfuscates ALL resource IDs to "(name removed)" — parse by class hierarchy and content-desc attributes instead. Contact name leaks via content-desc on toolbar Button element in Messenger.

**3. Message Queue + State Machine**
States: IDLE → WAKING_SCREEN → LAUNCHING_APP → NAVIGATING_TO_CHAT → READING → VERIFYING → CLOSING → IDLE. Processes one chat at a time sequentially. NotificationListener writes to queue instantly and is never blocked by worker state. Deduplicate by (package + notification_id + timestamp + content hash). Before closing any chat, re-scan bottom for messages that arrived during the read since in-chat messages do not fire notifications. Each state has a timeout and fallback to prevent hangs. On hang, hard reset to IDLE and re-queue the message.

**4. WakeLock + Screen Control**
Uses PowerManager.FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP to wake screen. Uses KeyguardManager.requestDismissKeyguard() to dismiss lock screen. Phone has NO PIN set since it is a dedicated device in a secure location. After read cycle is complete, release WakeLock and screen times out naturally. Required permissions: WAKE_LOCK, DISABLE_KEYGUARD, SYSTEM_ALERT_WINDOW.

**5. Local Buffer — Room/SQLite**
All captured messages written to local DB immediately on capture. Prevents data loss if network is down at sync time. Schema mirrors backend DB.

**6. WorkManager**
Batches buffered messages and POSTs to Cloudflare Worker every 30 minutes. Network-aware with retry and exponential backoff on failure. Marks messages as synced after confirmed POST. NOTE: this UPLOAD cadence (raw messages → D1) is decoupled from CLASSIFICATION cadence — the backend classifies via a Cloudflare Cron Trigger every 6 hours (00/06/12/18), batched per chat. Phone uploads often (cheap); Groq runs rarely (cheap on tokens).

**7. Boot Receiver**
RECEIVE_BOOT_COMPLETED broadcasts auto-restart all services on phone reboot. On boot, runs catch-up routine that opens each tracked chat and reads since last_seen.

**8. Foreground Service**
Persistent notification saying "Trip Ops Monitor Active". Required on Android 8+ to prevent OS from killing background services. Requires FOREGROUND_SERVICE permission.

**9. Image Handling**
AccessibilityService detects image node in chat UI. Taps save image flow to download. Sends image bytes to backend. Backend sends to Groq vision model for OCR and classification.

**10. Minimal UI — One Screen Only**
Toggle for Monitoring ON/OFF. Configurable list of 5 tracked group chats. Status showing last synced time and messages captured today. Button to run catch-up manually. Button to test backend connection.

---

### Backend — Cloudflare Worker (JavaScript/TypeScript)
Free tier: 100,000 requests/day forever. Receives POST of batched messages from Android app. Calls Groq API for each message. Saves classified results to Cloudflare D1.

Endpoints:
- POST /messages — receive batch from Android app
- GET /incidentals — query classified incidentals for optional dashboard use

---

### AI — Groq (Free) — VALIDATED on real chat data

**Status: PROVEN.** Tested against a real 274-message Taglish dispatch chat (TVL X BEST). Groq does genuine contextual understanding, not keyword matching — it catches implied incidentals with no charge word (e.g. "ang tagal nakatengga ng truck... wala pa pre advise" → overtime + demurrage) and correctly distinguishes incurred vs avoided ("to avoid Det charges" → possible, not confirmed).

**Model decision:**
- **Text classification: `openai/gpt-oss-120b`** (best judgment — ignores complaints/noise, confident on real incidentals) OR **`llama-3.3-70b-versatile`** (good, higher recall but more eager → flags borderline as "possible"). Both free. Do NOT use llama-3.1-8b-instant for classification — it misses nuance and over-flags (kept only as cheap overflow).
- **Image OCR/classification: a current Groq vision model** — verify the exact id at build time via `GET /v1/models`; vision model names rotate frequently. Keep the model in an env var (`GROQ_MODEL`), never hardcode.
- Endpoint is OpenAI-compatible: `POST https://api.groq.com/openai/v1/chat/completions`. Use `response_format: { type: "json_object" }` and `temperature: 0`.

**Classification approach (see /test for the working reference implementation):**
- The full rulebook lives in `docs/incidental-types.md` (21 incidental types). The code injects a CONDENSED copy (~1KB) into the system prompt on every call — Groq has no memory and cannot read files. Keep doc and prompt in sync (long-term: derive both from a `types.json`).
- **BATCHED, NOT one-per-message** (changed — the original 1-msg-per-call was only a POC to validate Groq). Production classifies a whole chat's new messages in ONE call: group by chat, chunk to ~25 messages (tunable `BATCH_SIZE`), each message tagged with a stable `id` the model must echo back so results map cleanly. Wins: pays the ~1KB rulebook ONCE per batch (not per message), slashes request count (RPD) and per-minute tokens (TPM), and gives the model whole-conversation context so it links incidentals to the right trip across messages. `classifyBatch()` in `test/classifier.mjs` is the reference; it filters out any result whose `id` wasn't in the batch (drops hallucinated ids).
- **Output is an array per message** — one message can name multiple incidentals (e.g. "rented chassis because we had to bobtail" → chassis_rental + bobtail). The batch response is `{ "results": [...] }` with one entry PER INCIDENTAL-BEARING message only (clean messages omitted, since incidentals are sparse).
- Each incidental carries a **status: `confirmed`** (cost actually incurred/happened) **or `possible`** (anticipated, at-risk, or being avoided). This is the key reliability lever: auto-trust `confirmed`, route `possible` to human review.
- The peso AMOUNT does not matter and is usually absent — the goal is detecting that a trip HAS an incidental and its type, not the value.
- **Sender hint:** every trip is assigned a driver + helper (named on the job-sheet/dispatch slip). The prompt tells Groq to use the message SENDER to help link an incidental to a trip (match sender ≈ a recent job sheet's driver/helper) — a hint, not proof (chat account names ≠ job-sheet names exactly).

**Context across batches — Groq has ZERO memory, so we re-feed everything each call.** Persistence is OUR job. The design (production / backend):
- **Rolling situation summary** — each batch call ALSO returns an updated `situation_summary` (~150 output tokens); we store it and feed only the LATEST one into the next batch. It compresses ALL prior history (who's stuck, what's pending) into ~300-400 tokens → unlimited memory reach at a fixed tiny cost, and auto-forgets finished trips. This is far cheaper than carrying N raw message batches (which scale with message count and blow TPM).
- **Trip registry** — durable per-chat list of seen container#/plate/client/driver (extracted free from each batch's `trip_reference` output, or by regex on container# `[A-Z]{4}\d{7}`). Fed in as a few hundred tokens so far-back trips stay linkable.
- Per call we send: **rulebook + latest summary + trip registry + the new messages** → get back **incidentals + a fresh summary**.

**Run cadence: every 6 hours (00:00 / 06:00 / 12:00 / 18:00), ~4 runs/day.** Incidentals can sit a while — no need for tighter. Fewer runs = the per-call fixed costs (rulebook, summary) are paid fewer times = fewer total tokens. ~4 runs × N chats ≈ tens of calls/day, miles under the 1,000/day cap.

**Response schema (batch):**
```json
{
  "results": [
    {
      "id": "m18",
      "incidentals": [
        { "incidental_type": "chassis_rental", "status": "confirmed", "confidence": 0.9 }
      ],
      "trip_reference": "TXGU5040257",
      "reference_type": "container_number",
      "reference_source": "message"
    }
  ],
  "situation_summary": "CAP7500 stuck outside harbor since 4am, no docs. ..."
}
```

**Reality found in real data:** incidental chatter is SPARSE (most messages are coordination noise) and almost always has NO amount — incidentals are discussed qualitatively. One trip accumulates multiple incidentals across many messages/days → aggregate by `trip_reference` at query time.

### Groq free-tier limits — VERIFIED from response headers (per model, separate buckets)

| Model | Requests/day | Tokens/min |
|---|---|---|
| llama-3.3-70b-versatile | 1,000 | 12,000 |
| openai/gpt-oss-120b | 1,000 | 8,000 |
| llama-3.1-8b-instant | 14,400 | 6,000 |

- Limits are a continuously-refilling leaky bucket (NOT a reset). Each call draws ~1,100-1,200 tokens of rulebook (paid on EVERY call) + the context (summary + registry, ~few hundred tokens) + the batch's messages + ~150 output for the summary.
- **USAGE = number of BATCHES (calls), not messages** (changed by batching). One call classifies ~25 messages. So usage now scales with batch count, not message count — dramatically lower than the old 1-msg-1-request model.
- **Per-call token budget ≈ ~3,000-3,500** (rulebook + summary + registry + ~25 msgs + output) — fits comfortably under TPM in a single call. AVOID carrying many raw message batches as context (~6,000+ tokens, breaks the 8,000 TPM on 120b) — that's why we use the compressed summary instead.
- **Per-minute (TPM):** with batching, one call/min is trivially within limits; chunks within a run go back-to-back with a small throttle. The classifier already retries on 429.
- **Per-day (RPD) — 1,000/day on the smart models — is now a non-issue.** 4 runs/day × N chats ≈ tens of calls/day. Even 10+ chats stays far under the cap.
- **Optional extra headroom (probably unneeded now):** LOCAL noise pre-filter before batching (skip reactions, pure images, "ok po"); and/or round-robin 70b + 120b (separate buckets, 2,000/day combined). Still dedupe aggressively on-device (chat + content, 10-min window) and durably in D1 before classifying.

---

### Database — Cloudflare D1 (Free SQLite)

NOTE: the original single-row design (one incidental_type/amount column per message) is REPLACED — a single message can carry MULTIPLE incidentals, so incidentals are a separate table (one row per detected incidental). Aggregate per trip by grouping `incidentals` on `trip_reference`. The `amount` column is kept but is usually NULL (amount doesn't matter for the use case).

```sql
CREATE TABLE captured_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source TEXT NOT NULL,            -- 'viber' | 'messenger'
  group_name TEXT NOT NULL,
  sender TEXT,
  message TEXT,
  image_url TEXT,
  timestamp TEXT NOT NULL,         -- ISO 8601 UTC
  is_incidental INTEGER DEFAULT 0,
  trip_reference TEXT,             -- container_number | consignee | plate | booking ref (may be inferred from history)
  reference_type TEXT,             -- 'container_number' | 'consignee' | 'plate_number' | 'trip_id' | 'other'
  reference_source TEXT,           -- 'message' | 'history' | null
  raw_notif INTEGER DEFAULT 1,     -- 1 = from notification, 0 = from accessibility service
  classified_at TEXT,
  synced_at TEXT
);

CREATE TABLE incidentals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id INTEGER NOT NULL REFERENCES captured_messages(id),
  incidental_type TEXT NOT NULL,   -- one of the 21 keys in docs/incidental-types.md
  status TEXT NOT NULL,            -- 'confirmed' | 'possible'
  confidence REAL,
  amount REAL,                     -- usually NULL
  trip_reference TEXT              -- denormalized for fast GROUP BY per trip
);

CREATE INDEX idx_incidentals_trip ON incidentals(trip_reference);
CREATE INDEX idx_msg_timestamp ON captured_messages(timestamp);

-- Cross-batch memory (since Groq is stateless). One row per chat: the latest rolling summary.
CREATE TABLE chat_state (
  group_name TEXT PRIMARY KEY,
  situation_summary TEXT,           -- the LATEST rolling summary, fed into the next batch
  updated_at TEXT
);

-- Trip registry: durable per-chat list of known trip identifiers, so far-back trips stay linkable.
CREATE TABLE trips (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_name TEXT NOT NULL,
  trip_reference TEXT NOT NULL,     -- container# | plate | client | booking ref
  reference_type TEXT,
  driver TEXT,                      -- from the job sheet, helps sender→trip linking
  helper TEXT,
  first_seen TEXT,
  last_seen TEXT,                   -- stale trips age out of the context we feed in
  UNIQUE(group_name, trip_reference)
);
```

source is either 'viber' or 'messenger'. raw_notif is 1 if captured from notification, 0 if from accessibility service.

**Dedup (changed):** key on **(source + group + content)** within a ~10-min window — NOT sender, because the two capture paths report sender differently (notification = "group: Name", accessibility = bare "Name"), which let the same message through as a "new" sender. On-device dedup is in-memory (resets on app restart); the durable guard is content-hash dedup in D1 before any Groq call.

---

## Phone Setup Checklist
- No screen lock or PIN: Settings → Security → None
- Disable battery optimization for Trip Ops app
- Allow Draw over other apps for Trip Ops app
- Allow notification access for Trip Ops app
- Allow accessibility access for Trip Ops app
- Disable auto-update for Viber and Messenger to prevent UI tree changes breaking the parser
- Disable Do Not Disturb
- Lock screen rotation to portrait for consistent UI tree
- Phone plugged in and powered on 24/7
- Use Samsung or Pixel phone — avoid Xiaomi, Oppo, Vivo which aggressively kill background services
- Login Trip Ops company account on both Viber and Messenger
- Add Trip Ops account to all 5 group chats manually

---

## Known Gotchas
- Messenger obfuscates ALL resource IDs. Never use findAccessibilityNodeInfosByViewId() for Messenger. Use class name, content-desc, and hierarchy position instead.
- New messages arriving in the currently open chat do not fire notifications. Catch them via live AccessibilityService scan before closing the chat.
- Viber sometimes fires duplicate notifications for delivered and read receipts. Deduplicate aggressively.
- Android OEM battery killers will terminate your foreground service. Samsung and Pixel are safest choices.
- Accessibility tree is only readable when the chat is on screen. NotificationListener handles background capture. Accessibility is only needed for full content retrieval.
- Cold starting Viber or Messenger takes 2-5 seconds. Always poll for UI elements to appear rather than hardcoding Thread.sleep().
- Images in notifications appear as Photo only. Always trigger AccessibilityService for image messages.
- WakeLock requires the phone to have no PIN otherwise KeyguardManager cannot dismiss the lock screen without user interaction.

---

## Cost
- Cloudflare Workers: free
- Cloudflare D1: free
- Groq API: free — VERIFIED stays within free tier up to ~7 chats outright, and 8-10 chats with local noise pre-filtering and/or round-robin across 70b+120b. Even if free limits were exceeded, Groq paid tier ≈ cents/day.
- Android phone (used, one-time): 1500 to 3000 Philippine Pesos
- Monthly ongoing cost: zero

---

## Build Order
1. Cloudflare Worker + D1 — set up and test with Postman — 1 day
2. Android project setup + NotificationListenerService — 2 to 3 days
3. AccessibilityService — build Viber parser first then Messenger — 1 to 2 weeks
4. WakeLock + state machine + queue — 3 to 5 days
5. Image handling — 2 to 3 days
6. Minimal UI — 1 day
7. End to end testing — 2 to 3 days

Total estimated time with AI assistance: 3 weeks