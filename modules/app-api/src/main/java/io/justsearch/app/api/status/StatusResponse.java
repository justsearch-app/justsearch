/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;

/**
 * Top-level response record for the /api/status endpoint.
 *
 * <p>384: Worker operational data is nested under {@code "worker"} key. Sub-records within
 * {@code WorkerOperationalView} are also nested (no {@code @JsonUnwrapped} anywhere).
 *
 * <p>330 §4: Grouped sub-objects ({@code embedding}, {@code schema}, {@code chunkCoverage},
 * {@code queueHealth}, {@code migration}) provide structured access derived from the worker
 * sub-records. These are retained for frontend convenience alongside the full worker object.
 *
 * <p>Stability: stable (API contract)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusResponse(
    // Lifecycle snapshot fields (snake_case to match existing wire format)
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("observed_at") String observedAt,
    LifecycleSnapshotV2.Lifecycle lifecycle,
    LifecycleSnapshotV2.Components components,

    // Head-level fields
    String status,
    String service,
    String indexBasePath,
    long uptimeMs,
    long memoryUsedBytes,
    long memoryTotalBytes,
    long memoryMaxBytes,
    String diskPressure,

    // 384: Worker operational data — nested under "worker" key (was @JsonUnwrapped for flat compat)
    WorkerOperationalView worker,

    // Index availability
    boolean indexAvailable,
    String knowledgeServerStartError,
    String indexStatusReason,

    // 630: OS energy-intent (Head-side poll) — drives the calm "Paused — saving energy" Queue card.
    PowerStatusView power,
    // 630: true for a brief window after an OS resume while the eager reconcile runs — drives the
    // transient "Catching up after sleep" verdict; auto-clears (computed against the request clock).
    boolean catchingUp,

    // Subsystem status — Tempdoc 412 Phase 3: `llm` (LlmStatusView) and `onlineAi` (OnlineAiView)
    // are replaced by a single `inference` (InferenceRuntimeView). The phase value
    // (OFFLINE/ONLINE/INDEXING/TRANSITIONING) subsumes the prior dual-source pair: OnlineAiView's
    // (available, starting) booleans are derivable from inference.phase, and the legacy
    // queue/active-slots/tokens-per-second fields on LlmStatusView are now in inference.queue
    // and inference.generation sub-records.
    InferenceRuntimeView inference,
    ReadinessEnvelopeView readiness,

    // Legacy readiness booleans derived from the readiness envelope
    boolean aiReady,
    boolean embeddingReady,

    // 330 §4: Grouped sub-objects (same data as flat fields, structured for easier consumption)
    EmbeddingStatusGroup embedding,
    SchemaStatusGroup schema,
    ChunkCoverageGroup chunkCoverage,
    QueueHealthGroup queueHealth,
    MigrationStatusGroup migration,

    // 335 §9: GPU utilization and VRAM (Head-side NVML probe)
    GpuStatusView gpu,

    // 629 (FLOOR): coarse OS-disk-encryption state of the data-dir volume (Head-side shell-property probe)
    AtRestProtectionView atRestProtection,

    // 629 (#2): conversation (AUTHORED-store) encryption state (not_configured|locked|unlocked) — the
    // reactive single-authority status the Health card + locked-chat gate read (replaces the one-shot fetch)
    ConversationProtectionView conversationProtection,

    // 381: Model distribution status (install profile, per-model variants, degradation)
    ModelDistributionStatusView modelDistribution,

    // 415: Currently-active agent session count (sourced from agent.session.active_count gauge)
    AgentSessionView agentSessions,

    // 419 C3 V1: telemetry-subsystem health counters (TelemetryHealthState snapshot).
    TelemetryHealthView telemetryHealth,

    // 333 §5: Freshness and provenance metadata
    StatusMeta meta) {

  public StatusResponse {
    observedAt = observedAt == null ? "" : observedAt;
    status = status == null ? "" : status;
    service = service == null ? "" : service;
    indexBasePath = indexBasePath == null ? "" : indexBasePath;
    knowledgeServerStartError = knowledgeServerStartError == null ? "" : knowledgeServerStartError;
  }
}
