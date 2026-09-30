/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded set of attribution values for {@code CommitOps.commitAndTrack(reason)} and the
 * {@code index.runtime.commit_ms} histogram. Tempdoc 417 Phase 1: replaces ~19 free-form strings
 * scattered across {@link CommitOps}, {@link RunningRuntime}, indexing loop ops, and gRPC
 * service handlers with a compile-time-bounded enum.
 *
 * <p>Each value's {@link #wireValue()} returns the existing NDJSON tag value verbatim, so the
 * wire format is byte-stable across the migration (Q3 default per tempdoc 417 plan).
 */
public enum CommitReason {
  DRAIN("drain"),
  TIMER("timer"),
  PRUNE("prune"),
  RESET("reset"),
  SETTLE("settle"),
  SYNC_PRUNE("sync/prune"),
  GRPC_DELETE_BY_PATH("grpc/deleteByPath"),
  GRPC_DELETE_BY_ID("grpc/deleteById"),
  GRPC_DELETE_BY_COLLECTION("grpc/deleteByCollection"),
  GRPC_UPDATE_PATHS("grpc/updatePaths"),
  VDU_UPDATE("vdu/update"),
  VDU_MARK_PROCESSING("vdu/mark-processing"),
  VDU_RECOVERY("vdu/recovery"),
  INDEXING_LOOP_IDLE("indexing-loop/idle"),
  INDEXING_LOOP_REBUILD_STAMP("indexing-loop/rebuild-stamp"),
  INDEXING_LOOP_FRESH_STAMP("indexing-loop/fresh-stamp"),
  INDEXING_LOOP_SHUTDOWN("indexing-loop/shutdown"),
  INDEXING_LOOP_TIME("indexing-loop/time"),
  INDEXING_LOOP_BUFFER("indexing-loop/buffer"),
  BACKFILL_SPLADE("backfill/splade"),
  BACKFILL_NER("backfill/ner"),
  BACKFILL_EMBEDDING("backfill/embedding"),
  BACKFILL_EMBEDDING_CHUNK("backfill/embedding-chunk"),
  BACKFILL_COMBINED("backfill/combined"),
  BACKFILL_COMBINED_FINAL("backfill/combined-final"),
  BACKFILL_BGE_M3("backfill/bge-m3"),
  MIGRATION_CUTOVER("migration/cutover"),
  SWITCH_BUFFER_REPLAY("migration/switch-buffer-replay"),
  UNKNOWN("unknown");

  private static final Map<String, CommitReason> BY_WIRE;

  static {
    java.util.HashMap<String, CommitReason> m = new java.util.HashMap<>();
    for (CommitReason r : values()) {
      m.put(r.wireValue, r);
    }
    BY_WIRE = Map.copyOf(m);
  }

  private final String wireValue;

  CommitReason(String wireValue) {
    this.wireValue = Objects.requireNonNull(wireValue, "wireValue");
  }

  /** Returns the existing free-form NDJSON tag value (preserved verbatim across migration). */
  public String wireValue() {
    return wireValue;
  }

  /**
   * Parses a wire-format string back to a typed reason. Unknown values map to
   * {@link #UNKNOWN} — a typed sentinel distinct from a typo'd metric series, so REST/gRPC
   * boundaries can accept legacy values defensively without crashing.
   */
  public static CommitReason fromWire(String wire) {
    if (wire == null || wire.isBlank()) return UNKNOWN;
    CommitReason r = BY_WIRE.get(wire);
    return r != null ? r : UNKNOWN;
  }
}
