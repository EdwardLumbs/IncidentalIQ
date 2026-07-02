-- Trip Ops Incidental Monitor — D1 (SQLite) schema.
-- Apply locally:  npm run db:local     Apply to cloud:  npm run db:remote
-- Safe to re-run: every table/index uses IF NOT EXISTS.

-- Raw messages uploaded by the phone. One row per captured message.
CREATE TABLE IF NOT EXISTS captured_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source TEXT NOT NULL,            -- 'viber' | 'messenger'
  group_name TEXT NOT NULL,
  sender TEXT,
  message TEXT,
  image_url TEXT,
  timestamp TEXT NOT NULL,         -- ISO 8601 UTC
  is_incidental INTEGER DEFAULT 0,
  trip_reference TEXT,             -- container# | consignee | plate | booking ref (may be inferred)
  reference_type TEXT,             -- 'container_number' | 'consignee' | 'plate_number' | 'trip_id' | 'other'
  reference_source TEXT,           -- 'message' | 'history' | null
  raw_notif INTEGER DEFAULT 1,     -- 1 = from notification, 0 = from accessibility service
  content_hash TEXT,               -- sha256(source|group|message) — durable dedup key
  classified_at TEXT,              -- null until the cron classifier has processed it
  synced_at TEXT                   -- when the backend received it
);

-- One row per DETECTED incidental (a message can have several).
CREATE TABLE IF NOT EXISTS incidentals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id INTEGER NOT NULL REFERENCES captured_messages(id),
  incidental_type TEXT NOT NULL,   -- one of the 21 keys in docs/incidental-types.md
  status TEXT NOT NULL,            -- 'confirmed' | 'possible'
  confidence REAL,
  amount REAL,                     -- usually NULL (amount doesn't matter for the use case)
  trip_reference TEXT              -- denormalized for fast GROUP BY per trip
);

-- Cross-batch memory (Groq is stateless). One row per chat: the latest rolling summary.
CREATE TABLE IF NOT EXISTS chat_state (
  group_name TEXT PRIMARY KEY,
  situation_summary TEXT,
  updated_at TEXT
);

-- Trip registry: durable per-chat list of known trip identifiers, so far-back trips stay linkable.
CREATE TABLE IF NOT EXISTS trips (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_name TEXT NOT NULL,
  trip_reference TEXT NOT NULL,
  reference_type TEXT,
  driver TEXT,
  helper TEXT,
  first_seen TEXT,
  last_seen TEXT,
  UNIQUE(group_name, trip_reference)
);

CREATE INDEX IF NOT EXISTS idx_incidentals_trip ON incidentals(trip_reference);
CREATE INDEX IF NOT EXISTS idx_msg_timestamp ON captured_messages(timestamp);
CREATE INDEX IF NOT EXISTS idx_msg_unclassified ON captured_messages(group_name, classified_at);
CREATE INDEX IF NOT EXISTS idx_msg_hash ON captured_messages(content_hash);
