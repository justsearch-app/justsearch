-- Frozen operations schema v2 from df4bedf07 - retain independently of production DDL.
CREATE TABLE operations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  operation_key TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL,
  survival TEXT NOT NULL,
  urgency TEXT NOT NULL,
  state TEXT NOT NULL,
  phase TEXT,
  operation_ref TEXT,
  identity_json TEXT NOT NULL CHECK(length(CAST(identity_json AS BLOB)) <= 262144),
  grant_ref TEXT,
  client_kind TEXT, client_id TEXT, session_id TEXT, source_tier TEXT,
  transport TEXT, executor TEXT, initiator TEXT, correlation_id TEXT,
  checkpoint_cursor TEXT CHECK(length(CAST(checkpoint_cursor AS BLOB)) <= 4096),
  units_completed INTEGER NOT NULL DEFAULT 0,
  units_failed INTEGER NOT NULL DEFAULT 0,
  attempts INTEGER NOT NULL DEFAULT 0,
  accepted_at INTEGER NOT NULL,
  started_at INTEGER,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER,
  urgency_detached_at INTEGER,
  failure_reason TEXT, failure_detail TEXT,
  result_json TEXT CHECK(length(CAST(result_json AS BLOB)) <= 4096),
  accepted_settings_revision INTEGER,
  building_generation_id TEXT,
  target_settings_json TEXT,
  gaps_json TEXT,
  processing_history_json TEXT,
  processing_history_counts_json TEXT,
  gaps_accepted_at INTEGER,
  gaps_accepted_by TEXT,
  gaps_list_hash TEXT,
  journal_replayed_entries INTEGER,
  superseded_from INTEGER
);
CREATE TABLE operations_meta (
  singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
  history_since_ms INTEGER NOT NULL,
  created_at_ms INTEGER NOT NULL,
  schema_note TEXT
);
CREATE INDEX operations_state ON operations(state);
CREATE INDEX operations_completed ON operations(completed_at);
CREATE INDEX operations_kind_state ON operations(kind, state);
INSERT INTO operations_meta(singleton,history_since_ms,created_at_ms) VALUES(1,17,0);
PRAGMA user_version=2;

-- Frozen v3 additions at 7fbc3df8d - do not derive from current production DDL.
ALTER TABLE operations ADD COLUMN preparation_nonce TEXT;
ALTER TABLE operations ADD COLUMN preparation_sealed INTEGER;
ALTER TABLE operations ADD COLUMN preparation_payload TEXT
  CHECK(length(CAST(preparation_payload AS BLOB)) <= 750000)
  CHECK((preparation_nonce IS NULL AND preparation_sealed IS NULL AND preparation_payload IS NULL)
    OR (preparation_nonce IS NOT NULL AND length(preparation_nonce) = 36
      AND preparation_sealed IS NOT NULL AND preparation_sealed IN (0,1)
      AND preparation_payload IS NOT NULL AND length(preparation_payload) > 0));
CREATE TABLE operation_preparations (
  operation_key TEXT PRIMARY KEY NOT NULL,
  kind TEXT NOT NULL, operation_ref TEXT,
  identity_json TEXT NOT NULL CHECK(length(CAST(identity_json AS BLOB)) <= 262144),
  nonce TEXT NOT NULL CHECK(length(nonce) = 36),
  sealed INTEGER NOT NULL CHECK(sealed IN (0,1)),
  payload TEXT NOT NULL CHECK(length(payload) > 0 AND length(CAST(payload AS BLOB)) <= 750000),
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
CREATE INDEX operation_preparations_expiry ON operation_preparations(expires_at);
ALTER TABLE operations ADD COLUMN history_mode TEXT NOT NULL DEFAULT 'NONE' CHECK(history_mode IN ('NONE','STANDARD','UNDOABLE','UNDO'));
ALTER TABLE operations ADD COLUMN provenance_occurred_at TEXT;
PRAGMA user_version = 4;
