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
Batches buffered messages and POSTs to Cloudflare Worker every 30 minutes. Network-aware with retry and exponential backoff on failure. Marks messages as synced after confirmed POST.

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

### AI — Groq (Free)
Text classification model: llama-3.1-8b-instant. Image OCR and classification model: llama-3.2-11b-vision-preview. Approximately 48 API calls per day at 30-minute intervals, well within free tier.

Groq classification prompt:
Given this message from a logistics group chat, determine if it discusses an incidental charge on a trip. If yes, extract the details. Return JSON only with these fields: is_incidental (bool), amount (float or null), incidental_type (string or null), trip_reference (string or null), confidence (float). Message: {message}

---

### Database — Cloudflare D1 (Free SQLite)

Schema:
CREATE TABLE captured_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, group_name TEXT NOT NULL, sender TEXT NOT NULL, message TEXT, image_url TEXT, timestamp TEXT NOT NULL, is_incidental INTEGER DEFAULT 0, amount REAL, incidental_type TEXT, trip_reference TEXT, confidence REAL, raw_notif INTEGER DEFAULT 1, synced_at TEXT);

source is either 'viber' or 'messenger'. raw_notif is 1 if captured from notification, 0 if from accessibility service.

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
- Groq API: free
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