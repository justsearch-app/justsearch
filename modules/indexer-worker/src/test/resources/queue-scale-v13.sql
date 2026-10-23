-- v0.3.0 SqliteSchema DDL (git tag v0.3.0), actual migrated version 13.
CREATE TABLE IF NOT EXISTS jobs (
  path TEXT PRIMARY KEY,
  state TEXT NOT NULL DEFAULT 'PENDING',
  attempts INTEGER NOT NULL DEFAULT 0,
  last_updated INTEGER NOT NULL,
  error_message TEXT,
  retry_after INTEGER,
  first_failed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_jobs_state ON jobs(state);
CREATE INDEX IF NOT EXISTS idx_jobs_state_updated ON jobs(state, last_updated);
CREATE TABLE IF NOT EXISTS switch_buffer (
  key TEXT PRIMARY KEY,
  op TEXT NOT NULL,
  payload TEXT NOT NULL,
  last_updated INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_switch_buffer_updated ON switch_buffer(last_updated);
CREATE TABLE IF NOT EXISTS ingestion_ledger (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  path_hash TEXT NOT NULL,
  collection TEXT,
  outcome_class TEXT NOT NULL,
  reason_code TEXT NOT NULL,
  retry_policy TEXT NOT NULL,
  diagnostic_summary TEXT,
  observed_at INTEGER NOT NULL,
  source_size_bytes INTEGER,
  source_modified_at INTEGER,
  source_kind TEXT,
  artifact_status TEXT,
  policy_id TEXT,
  parser_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_ingestion_ledger_path_time
ON ingestion_ledger(path_hash, observed_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_ledger_outcome
ON ingestion_ledger(outcome_class, reason_code, observed_at);
CREATE TABLE IF NOT EXISTS path_resolution (
  path_hash TEXT PRIMARY KEY,
  normalized_path TEXT NOT NULL,
  last_seen_at INTEGER NOT NULL,
  removed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_path_resolution_normalized_path
ON path_resolution(normalized_path);
CREATE INDEX IF NOT EXISTS idx_path_resolution_removed_at
ON path_resolution(removed_at);
CREATE TABLE IF NOT EXISTS document_identity (
  path_hash TEXT PRIMARY KEY,
  doc_uid TEXT NOT NULL,
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_document_identity_uid
ON document_identity(doc_uid);
CREATE TABLE IF NOT EXISTS document_identity_import (
  generation_id TEXT PRIMARY KEY,
  imported_at INTEGER NOT NULL,
  parents_seen INTEGER NOT NULL,
  parents_imported INTEGER NOT NULL,
  parents_skipped INTEGER NOT NULL
);
ALTER TABLE jobs ADD COLUMN collection TEXT DEFAULT NULL;
ALTER TABLE jobs ADD COLUMN last_outcome_class TEXT;
ALTER TABLE jobs ADD COLUMN last_reason_code TEXT;
ALTER TABLE jobs ADD COLUMN last_retry_policy TEXT;
ALTER TABLE jobs ADD COLUMN last_diagnostic_summary TEXT;
ALTER TABLE jobs ADD COLUMN last_outcome_at INTEGER;
ALTER TABLE jobs ADD COLUMN size_bytes INTEGER DEFAULT NULL;
ALTER TABLE jobs ADD COLUMN scan_id TEXT;
ALTER TABLE document_identity ADD COLUMN deleted_at INTEGER;
PRAGMA user_version = 13;
