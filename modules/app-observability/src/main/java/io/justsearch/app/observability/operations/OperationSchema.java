/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

/** The operations database's independent version ladder; jobs.db keeps its own identity. */
final class OperationSchema {
  static final int VERSION = 1;

  static final String CREATE_OPERATIONS = """
      CREATE TABLE operations (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        operation_key TEXT NOT NULL UNIQUE,
        kind TEXT NOT NULL,
        survival TEXT NOT NULL,
        urgency TEXT NOT NULL,
        state TEXT NOT NULL,
        phase TEXT,
        operation_ref TEXT,
        identity_json TEXT NOT NULL,
        grant_ref TEXT,
        client_kind TEXT, client_id TEXT, session_id TEXT, source_tier TEXT,
        transport TEXT, executor TEXT, initiator TEXT, correlation_id TEXT,
        checkpoint_cursor TEXT,
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
      )
      """;

  static final String CREATE_META = """
      CREATE TABLE operations_meta (
        singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
        history_since_ms INTEGER NOT NULL,
        created_at_ms INTEGER NOT NULL,
        schema_note TEXT
      )
      """;

  private OperationSchema() {}
}
