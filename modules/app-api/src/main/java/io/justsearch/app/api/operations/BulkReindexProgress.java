/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationResult;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Typed, immutable projection of one accepted bulk-reindex operation's owner evidence. */
public record BulkReindexProgress(String generationId, IndexTargetSnapshot target, Phase phase,
    Capture capture, Settlement settlement, String refusalCode) {
  public static final int MAX_PROCESSING_HISTORY = 200;
  private static final Set<String> REFUSALS = Set.of("cancelled", "RECOVERY_AUTHORIZATION_REFUSED",
      "RECOVERY_SCOPE_REFUSED", "RECOVERY_BINDING_INVALID", "BULK_GENERATION_REFUSED",
      "BULK_CAPTURE_FAILED", "INGEST_RECOVERY_ATTEMPTS_EXHAUSTED",
      "ACTIVATION_PRECOMMIT_REFUSED");

  public BulkReindexProgress(String generationId, IndexTargetSnapshot target, Phase phase,
      Capture capture, Settlement settlement) {
    this(generationId, target, phase, capture, settlement, null);
  }

  /** First durable refusal wins; it survives settlement and forbids renewed claims/promotion. */
  public BulkReindexProgress withRefusal(String code) {
    Objects.requireNonNull(code, "refusalCode");
    if (refusalCode != null && !refusalCode.equals(code)) {
      throw new IllegalArgumentException("Bulk refusal cannot be replaced");
    }
    return new BulkReindexProgress(generationId, target, phase, capture, settlement, code);
  }

  public BulkReindexProgress {
    validateGenerationId(generationId);
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(phase, "phase");
    if (refusalCode != null && !REFUSALS.contains(refusalCode)) throw new IllegalArgumentException("Unknown bulk refusal");
    switch (phase) {
      case CAPTURING -> {
        if (capture != null || settlement != null) {
          throw new IllegalArgumentException("Capturing progress cannot contain closed evidence");
        }
      }
      case BUILDING -> {
        Objects.requireNonNull(capture, "building capture");
        if (settlement != null) {
          throw new IllegalArgumentException("Building progress cannot contain a settlement");
        }
      }
      case SETTLED -> {
        Objects.requireNonNull(capture, "settled capture");
        Objects.requireNonNull(settlement, "settled evidence");
        if (settlement.gaps().size() > capture.plannedUnits()) {
          throw new IllegalArgumentException("Settled gaps exceed the captured plan");
        }
      }
    }
  }

  public long unitsCompleted() {
    return phase == Phase.SETTLED ? capture.plannedUnits() - settlement.gaps().size() : 0;
  }

  public long unitsFailed() {
    return phase == Phase.SETTLED ? settlement.gaps().size() : 0;
  }

  public String cursor() {
    return phase == Phase.SETTLED
        ? "bulk-receipt:" + settlement.revision() + ":" + settlement.sha256()
        : "bulk:" + phase.wire();
  }

  private static void validateGenerationId(String generationId) {
    if (generationId == null || !generationId.startsWith("g-")) {
      throw new IllegalArgumentException("Bulk generation must be derived from a UUIDv7 operation key");
    }
    OperationKeys.timestampMillis(generationId.substring(2));
  }

  private static void requireSha256(String value, String label) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be lowercase SHA-256 hex");
    }
  }

  private static void requireOutcomeCode(String value, String label) {
    if (!OperationResult.isDurableOutcomeCode(value)) {
      throw new IllegalArgumentException(label + " must be a bounded durable outcome token");
    }
  }

  public enum Phase {
    CAPTURING,
    BUILDING,
    SETTLED;

    public String wire() { return name().toLowerCase(Locale.ROOT); }
  }

  public record Capture(String manifestSha256, long plannedUnits) {
    public Capture {
      requireSha256(manifestSha256, "Capture manifest digest");
      if (plannedUnits < 0) throw new IllegalArgumentException("Planned unit count cannot be negative");
    }
  }

  public record Settlement(long revision, String sha256, long failedEvents, long supersededEvents,
      List<OperationOutcomeView.Gap> gaps, List<ProcessingEvent> processingHistory) {
    public Settlement {
      if (revision < 1) throw new IllegalArgumentException("Settlement revision must be positive");
      requireSha256(sha256, "Settlement digest");
      if (failedEvents < 0 || supersededEvents < 0) {
        throw new IllegalArgumentException("Settlement event counts cannot be negative");
      }
      gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
      processingHistory = List.copyOf(Objects.requireNonNull(processingHistory, "processingHistory"));
      if (processingHistory.size() > MAX_PROCESSING_HISTORY) {
        throw new IllegalArgumentException("Processing history exceeds its bounded sample");
      }
      Set<String> unitIds = new HashSet<>();
      for (OperationOutcomeView.Gap gap : gaps) {
        if (!unitIds.add(gap.unitId())) {
          throw new IllegalArgumentException("Settlement gaps must have unique unit identities");
        }
      }
    }
  }

  /** Privacy-safe unit evidence; queue outcome/retry compatibility remains the queue's authority. */
  public record ProcessingEvent(String pathHash, String unitRevision, String plannedSourceSha256,
      String contentHash, String coverage, String outcomeClass, String reasonCode, String retryPolicy) {
    public ProcessingEvent {
      requireSha256(pathHash, "Processing path hash");
      if (unitRevision == null || !unitRevision.matches("[A-Za-z0-9_.:-]{1,128}")) {
        throw new IllegalArgumentException("Unit revision must be a bounded nonblank token");
      }
      requireSha256(plannedSourceSha256, "Planned source hash");
      if (contentHash != null) requireSha256(contentHash, "Content hash");
      if (coverage == null || !Set.of("INDEXED", "FAILED", "SKIPPED").contains(coverage)) {
        throw new IllegalArgumentException("Processing coverage is unknown");
      }
      requireOutcomeCode(outcomeClass, "Outcome class");
      requireOutcomeCode(reasonCode, "Reason code");
      requireOutcomeCode(retryPolicy, "Retry policy");
    }
  }
}
