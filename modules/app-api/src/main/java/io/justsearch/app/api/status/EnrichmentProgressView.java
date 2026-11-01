/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import java.util.Map;

/** Enrichment coverage for all subsystems (embedding, SPLADE, chunk, NER). */
@RecordBuilder
public record EnrichmentProgressView(
    ChunkCoverageView chunk,
    long embeddingDocCount,
    long embeddingCompletedCount,
    long embeddingPendingCount,
    long embeddingFailedCount,
    double embeddingCoveragePercent,
    long spladeDocCount,
    long spladeCompletedCount,
    long spladePendingCount,
    long spladeFailedCount,
    double spladeCoveragePercent,
    long pendingNerCount,
    long completedNerCount,
    // Tempdoc 821 §3-C3 — NER reported only pending/completed; the other three stages have
    // carried a failed count since 354, so a stalled NER population was invisible here.
    long failedNerCount,
    // Tempdoc 821 §3-C3 — the per-stage completeness audit, and the two thresholds the auditor
    // owns. Publishing the thresholds is what lets off-process oracles (jseval's
    // chunk_completeness guard) read them instead of mirroring the Java constants and drifting.
    List<StageCompletenessView> completeness,
    Boolean completenessKnown,
    int chunkMinChars,
    double vectorReadyPercent,
    Map<String, Long> enrichmentCompleted,
    BatchTimingView batchTiming,
    Map<String, EncoderProfileView> encoderProfiles,
    // Per-stage enabled state — consumers polling for completion must skip
    // disabled stages (their coverage stays at 0 forever). See tempdoc 394
    // for the investigation that surfaced this.
    boolean embeddingEnabled,
    boolean spladeEnabled,
    boolean nerEnabled,
    // Tempdoc 710 Move 2 item 4: which backfill pass ran last idle cycle —
    // "combined" | "individual" | "idle". Previously unobservable: batchTiming/
    // enrichmentCompleted counters froze in individual mode with no signal explaining why
    // (710 S-B3 finding).
    String backfillMode,
    // Spec E step E0b (task b6d536): whole documents BOTH embedded and sparse-encoded, one joint
    // Lucene count. The S5 throughput unit; not derivable from the per-stage counters above.
    long embeddedAndSparseCount,
    // Spec E step E1 (§1.7): whether each stage can drain its backlog right now, by the same
    // provider checks the backfill loop uses. The *Enabled flags above are wire-time provider
    // presence. Operating status skips a backlog in a disabled or unavailable stage when deciding
    // IDLE. Null (not reported) normalizes to true: a producer that reports a backlog without
    // saying the stage is stuck is read as working on it, never as idle.
    Boolean embeddingAvailable,
    Boolean spladeAvailable,
    Boolean nerAvailable) {
  public EnrichmentProgressView {
    backfillMode = backfillMode == null ? "idle" : backfillMode;
    completeness = completeness == null ? List.of() : List.copyOf(completeness);
    embeddingAvailable = embeddingAvailable == null ? Boolean.TRUE : embeddingAvailable;
    spladeAvailable = spladeAvailable == null ? Boolean.TRUE : spladeAvailable;
    nerAvailable = nerAvailable == null ? Boolean.TRUE : nerAvailable;
  }

  public static EnrichmentProgressView empty() {
    return EnrichmentProgressViewBuilder.builder()
        .chunk(ChunkCoverageView.empty())
        .completeness(List.of())
        .enrichmentCompleted(Map.of())
        .batchTiming(BatchTimingView.empty())
        .encoderProfiles(Map.of())
        .embeddingEnabled(true)
        .spladeEnabled(true)
        .nerEnabled(true)
        .backfillMode("idle")
        .build();
  }
}
