# Viber & Messenger UI Tree Structure (Phase 1 reverse-engineering)

Captured from a real device (Vivo V2126, Android 14) on 2026-06-29 via the
AccessibilityService tree dumps. Raw logs: `raw-tree-dumps.log`. This is the
spec the Phase 2 parsers are built from.

Resolution of the test device: 1080 × 2400. Bounds shown as `left,top,right,bottom`.

> **Key principle:** the *notification* truncates (~100 chars); the *accessibility
> tree* contains the FULL message. The truncation flow = open chat → read tree →
> full content. The parser never sees truncated text.

---

## VIBER — `com.viber.voip`  (resource IDs are READABLE)

### Chat list screen
- Scrollable list: `RecyclerView id=messages`
- Each chat row = a **clickable `ViewGroup`** with children:
  | id | meaning |
  |---|---|
  | `from` | chat / group name (e.g. "FUEL REFILLING TOTAL") |
  | `subject` | last message preview |
  | `date` | last message time ("10:10 PM", "Fri") |
  | `unread_messages_count` | unread badge |
- **Navigate:** scroll `messages` → find row whose child `TextView id=from` == target group → click the row's ViewGroup.
- Skip non-chat rows: ads (`googleAdView`, `adTitleView`), "live scores" banner, "Business Inbox", "My Notes".

### Inside a chat
- Toolbar:
  - `TextView id=title` = current chat name → **use to verify we opened the right chat**
  - `TextView id=subtitle` = "6 participants" (group) or "Last seen..." (1-on-1)
  - back button = `ImageButton cd="Navigate up"`
- Message list: `RecyclerView id=conversation_recycler_view` (scrollable)
- Each message row (`ViewGroup`) contains:
  | id | meaning |
  |---|---|
  | **`nameView`** | **sender name** (GROUP only) — see inheritance rule below |
  | **`textMessageView`** | **full message text** |
  | `imageView` / `fm_media_view`+`preview` | image/media message → OCR flow |
  | `emojiView` | emoji-only message |
  | `timestampView` | per-message time |
  | `dateHeaderView` | date separator ("Today", "Thu, 14 May") |
  | `newMessageHeaderView` | "New messages" unread divider |
  | `avatarView` | sender avatar (GROUP) |

- **⚠ Sender inheritance rule (GROUP):** `nameView` appears only on the FIRST
  message of a consecutive run from the same sender. Messages without a
  `nameView` belong to the **last-seen sender**. The parser must carry it forward.
- **1-on-1 chats have NO `nameView`** — sender is the other party (from `title`).

---

## MESSENGER — `com.facebook.orca`  (all resource IDs OBFUSCATED → `(name removed)`)

Parse by **`content-desc` (cd)** and `text`, NEVER by viewId. Class hierarchy +
content-desc only.

### Chat list screen
- Each chat row = a clickable **`Button`** whose `cd` = `"<Name>, <last message preview>"`
  - e.g. `cd="TVL DOCUMENTATION / REPORTING, 8 new messages"`, `cd="Jadline Truck Parts Center, 2800 universal"`
  - Unread rows prefix the name with `"Unread "` and add trailing `"."` (strip both)
- Inside each Button, separate nodes carry the parts: `text="<name>."`, `text="<time>"`, `text="<preview>"`.
- **Navigate:** find clickable `Button` whose `cd` starts with target group name → click it.
- Bottom tabs identifiable by cd: `"Chats, ... Tab 1 of 4"`, `"People tab... Tab 2 of 4"`, etc.

### Inside a chat
- Toolbar:
  - `Button cd="<Name>, Thread details"` → name before the comma = **current chat name** (verify)
  - `ViewGroup text="<Name>"` (also present), `text="8 active now"` (group) / `text="Active now"` (1-on-1)
  - back = `Button cd="Back"`; group call = `cd="Group audio call"` vs 1-on-1 `cd="Audio call"`
- **Messages** — each bubble = a node whose `cd`/`text` matches this EXACT format:
  ```
  "<Sender>, <Message content>, double tap to see sent/receive date and time, double tap and hold to react on message"
  ```
  Examples (real):
  - `"Edward, Test, double tap to see sent/receive date and time, double tap and hold to react on message"`
  - `"Ancel, Nakasalang npo kme dto s diskargahan nila.. ⏎Fly Ace Bodega.., double tap to see..."`
  - `"Victoria, Anyways bibiyahe pa naman daw sya..., double tap to see..."`

  **Parse:** find every node whose `cd` ends with `"double tap to see sent/receive date and time..."`
  (this suffix is the reliable "is-a-message" marker) → remove that suffix →
  split on the FIRST `", "` → `[sender, content]`.

- Extra attribution signals:
  | Signal | Node cd | Use |
  |---|---|---|
  | Full sender name | `"Open Ancel Remo's profile"` (avatar ImageView) | map display "Ancel" → "Ancel Remo" |
  | Sender label | `ViewGroup text="Ancel"` above a grouped run | backup sender |
  | Image message | `Button cd="Image"` + `"Forward photo sent by Ancel Remo on 9:47 PM"` | image + who/when → OCR |
  | Timestamp | `ViewGroup text="9:47 PM"` | time |
  | Read receipt | `"Seen by Khelby Jun Pielago, Ancel Remo, ..."` | group membership |
- Compose box (to confirm we're in a chat): `EditText cd="Type a message"`. Send: `Button cd="Send ..."`.

---

## Phase 2 implications
- **Open the right chat:** prefer firing the notification's own `contentIntent`
  (lands directly in the chat). Chat-list navigation (above) is for the 8am
  catch-up when there's no notification to ride on.
- **Verify** the opened chat via Viber `toolbar/title` or Messenger
  `Button cd="..., Thread details"` before reading.
- **Read** by collecting message nodes per the maps above; for media nodes,
  trigger the image/OCR flow.
- **Both apps update frequently** — these IDs/strings can change on app updates;
  keep `auto-update disabled` for Viber/Messenger on the dedicated phone, and
  treat empty/garbage dumps as a "parser broke" alarm.
