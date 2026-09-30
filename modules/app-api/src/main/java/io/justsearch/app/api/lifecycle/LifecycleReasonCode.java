/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stable reason-code taxonomy for {@link LifecycleSnapshotV2} (schema 2).
 *
 * <p>Reason codes must be low-cardinality, stable, and suitable for automation. They must not
 * include dynamic details like file paths, exception messages, or IDs.
 *
 * <p>Stability: stable (API contract)
 */
public enum LifecycleReasonCode {
  COMPONENT_START_DEADLINE("component.start_deadline"),
  COMPONENT_RECOVERING("component.recovering"),
  COMPONENT_RECOVERY_FAILED("component.recovery_failed"),
  COMPONENT_RECOVERY_EXHAUSTED("component.recovery_exhausted"),
  // --- Engine components ---
  ENGINE_NOT_STARTED("engine.not_started"),
  ENCODERS_RELOADING("encoders.reloading"),
  ENCODERS_HEALTH_EMBEDDING_NOT_READY("encoders.health.embedding_not_ready"),
  ENCODERS_HEALTH_EMBEDDING_PROBE_MISSING("encoders.health.embedding_probe_missing"),

  // --- Index serving / embedding compatibility ---
  INDEX_STARTING("index.starting"),
  INDEX_ACTIVATING("index.activating"),
  INDEX_FAILED("index.failed"),
  INDEX_CORRUPT("index.corrupt"),
  INDEX_SHUT_DOWN("index.shut_down"),
  INDEX_UNAVAILABLE("index.unavailable"),
  INDEX_THROUGHPUT_STALLED("index.throughput_stalled"),
  INDEX_THROUGHPUT_DEGRADED("index.throughput_degraded"),
  INDEX_MODEL_NOT_INSTALLED("index.model_not_installed"),
  INDEX_NOT_HEALTHY("index.not_healthy"),
  INDEX_BLOCKED_LEGACY("index.blocked_legacy"),
  INDEX_SCHEMA_MISMATCH("index.schema_mismatch"),
  INDEX_SCHEMA_OPEN_REFUSED("index.schema_open_refused"),
  MIGRATION_AWAITING_GAP_ACCEPTANCE("migration.awaiting_gap_acceptance"),
  // Tempdoc 915 §C: the Worker gave up on automatic rebuilds for this index shape after
  // MAX_AUTO_REBUILD_ATTEMPTS. Search still serves the existing index read-only; ingestion does
  // not resume without an operator.
  INDEX_REBUILD_BRAKE_EXHAUSTED("index.rebuild_brake_exhausted"),
  // Tempdoc 837 S6 (§2.1/§2.2) RETIRED the INDEX_REBUILDING member here. (Its code string is
  // deliberately not repeated in this comment: the gate's enum extractor does not strip comments, so
  // a prose mention in the NAME-plus-quoted-string shape would re-create the phantom it deleted.)
  // It was emitted
  // only while migrationState ∈ {MIGRATING, SWITCHING} — which is exactly the window the FE verdict
  // forces to `transitioning`, where the readiness notice returns null — so it reached the wire and
  // no surface ever worded it. A generation rebuild in progress is a TRANSITION (self-clearing, has
  // progress, no user action), and WHY it is running is a facet of that transition carried by
  // io.justsearch.app.api.status.MigrationSource, not a second verdict about it.
  //
  // An in-place embedding rebuild is running (`embeddingCompatState=REBUILDING`): the Worker
  // refuses dense queries with `REBUILD_IN_PROGRESS` until it finishes, so keyword results are
  // complete but semantic ranking is off. Distinct from a GENERATION rebuild (which moves the
  // generation and so leaves stability provisional); this one leaves it settled, which is why this
  // code is reachable and its retired neighbour was not.
  INDEX_EMBEDDING_REBUILDING("index.embedding_rebuilding"),
  INDEX_EMBEDDING_LEGACY("index.embedding_legacy"),
  INDEX_EMBEDDING_MISMATCH("index.embedding_mismatch"),
  // Tempdoc 598 reopen (B-3): dense/semantic retrieval cannot run for a reason a rebuild does NOT
  // fix — the embedding model is not loaded (`UNAVAILABLE` compat) or the embedder is unavailable on
  // an otherwise-COMPATIBLE index (`embeddingReady=false`). Distinct from the BLOCKED_* legacy/mismatch
  // codes (whose remedy is a reindex). Emitted on the `retrieval` composite so the search banner stops
  // over-claiming "fully semantic" while AUTO has degraded to keyword (the §59 over-claim hole).
  INDEX_DENSE_UNAVAILABLE("index.dense_unavailable"),

  // --- Inference ---
  INFERENCE_STARTING("inference.starting"),
  INFERENCE_OFFLINE("inference.offline"),
  // Tempdoc 656: the AI/Inference capability was the one capability whose failure reasons never
  // reached this closed taxonomy — RuntimeActivationService already detected these causes precisely
  // but only reported them to the immediate ai_activate RPC caller, never to InferenceCapability, so
  // the runtime manifest's ai.pendingReason stayed on generic prose. These mirror the existing
  // VDU_MISSING_MMPROJ precedent for "a required artifact is absent".
  INFERENCE_MODEL_NOT_CONFIGURED("inference.model_not_configured"),
  INFERENCE_MODEL_NOT_FOUND("inference.model_not_found"),
  INFERENCE_RUNTIME_NOT_INSTALLED("inference.runtime_not_installed"),
  INFERENCE_POLICY_ONLINE_AI_DISABLED("inference.policy_online_ai_disabled"),
  INFERENCE_POLICY_GPU_DISABLED("inference.policy_gpu_disabled"),
  INFERENCE_ACTIVATION_FAILED("inference.activation_failed"),
  // Tempdoc 837 S4 (fix a) — the GPU was handed to indexing (Mode.INDEXING). No new signal was
  // needed: it is its own mode arm. The collapsed wording said the model was "offline", which
  // reads as a fault; this state is scheduled, self-clearing, and expected.
  INFERENCE_GPU_YIELDED_TO_INDEXING("inference.gpu_yielded_to_indexing"),
  // Tempdoc 837 S4 — engine ONLINE with the user's chat spec off: a background procedure (VDU) is
  // using the engine while chat is intentionally not offered. The previous wording ("Inference
  // offline") was FALSE, not merely vague — the engine is up. Also the closed-vocabulary home of
  // RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND, so the capability and the ENGINE condition
  // (tempdoc 737 §12c item 2) keep naming this state identically.
  INFERENCE_UP_FOR_BACKGROUND("inference.up_for_background"),
  // Tempdoc 837 S5 (fix a) — the engine STOPPED on its own: the periodic health threshold tripped and
  // crash recovery forced the runtime OFFLINE (TransitionReason.CRASH_RECOVERY). Nobody chose this,
  // the remedy is different (reload / check logs), and it reads differently to a user than the
  // deactivation it was collapsed with. The one code in fix (a) that genuinely needed a new signal.
  INFERENCE_CRASHED("inference.crashed"),
  // Tempdoc 837 S5 — the user (or an admin action) turned the local AI off: an OFFLINE landing under
  // TransitionReason.USER_SWITCH / ADMIN_TRIGGERED. The most FREQUENT of the four collapsed cases, so
  // it is the one that trained alarm-blindness: the banner told a user who had just switched chat off
  // that something was broken.
  INFERENCE_DEACTIVATED("inference.deactivated"),

  // --- Visual text extraction (OCR/VDU) ---
  OCR_DISABLED("ocr.disabled"),
  OCR_ENGINE_MISSING("ocr.engine_missing"),
  OCR_LANGUAGE_MISSING("ocr.language_missing"),
  VDU_AI_OFFLINE("vdu.ai_offline"),
  VDU_INSUFFICIENT_VRAM("vdu.insufficient_vram"),
  VDU_MISSING_MMPROJ("vdu.missing_mmproj"),
  VDU_CIRCUIT_OPEN("vdu.circuit_open"),

  // --- Telemetry ---
  TELEMETRY_UNAVAILABLE("telemetry.unavailable"),
  TELEMETRY_METRICS_STALE("telemetry.metrics.stale"),
  TELEMETRY_METRICS_HIGH_FAILURE_RATE("telemetry.metrics.high_failure_rate"),
  TELEMETRY_DISK_SPACE_LOW("telemetry.disk_space_low"),

  // --- Chunk Embedding (Phase 2 backfill) ---
  CHUNK_EMBEDDING_NOT_READY("chunk_embedding.not_ready"),
  CHUNK_EMBEDDING_IN_PROGRESS("chunk_embedding.in_progress"),

  // --- LambdaMART (reranking model) ---
  LAMBDAMART_NOT_CONFIGURED("lambdamart.not_configured"),
  LAMBDAMART_TRAINING("lambdamart.training"),
  LAMBDAMART_FAILED("lambdamart.failed"),

  // --- Settings ---
  // Tempdoc 882 item 24: the user's ui/settings.json could not be read, so it was moved aside to a
  // timestamped .corrupt- sibling and defaults were loaded (ADR-0008). Not a fault to recover from:
  // the preferences are already gone, and the only thing left to do is tell the user so they can
  // re-author them. Deliberately NOT emitted for a FUTURE schemaVersion, which stays fail-loud.
  DATA_NEWER_BUILD("data.newer_build"),
  SETTINGS_RESET_FROM_CORRUPT("settings.reset_from_corrupt"),
  SETTINGS_RECOVERY_REQUIRED("settings.recovery_required"),
  OPERATIONS_HISTORY_RESET("operations.history_reset"),
  OPERATIONS_PERSISTENCE_FAILED("operations.persistence_failed"),

  // --- Local API (trust boundary) ---
  // Tempdoc 884 item 23: prod mode was configured but no session token was supplied, so the ONE
  // control gating mutating loopback requests could not be installed. The Head refuses to start
  // the local API rather than binding it open to every local process (fail closed). Never held in
  // a capability reason slot: ApiSecurityFilters throws from its constructor, before the loopback
  // bind, so no readiness envelope, ConditionStore or SSE stream exists to carry it — the operator
  // sees it in the fatal log line and the non-zero exit.
  LOCAL_API_SESSION_TOKEN_MISSING("local_api.session_token_missing"),

  // --- GPU saturation (419 C3 V2 P3) ---
  // GPU pinned at high utilization with no current workload (idle leak detection). Monitored
  // by GpuSaturationMonitor + sampler in modules/ui (head-side NVML probe).
  GPU_SATURATED("gpu.saturated");

  private static final Set<String> ALLOWED_CODES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.stream(values()).map(LifecycleReasonCode::code).toList()));

  private final String code;

  LifecycleReasonCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static boolean isKnown(String code) {
    return code != null && ALLOWED_CODES.contains(code);
  }

  /**
   * Tempdoc 837 §D.1 — how much this code is worth defending when it is the HELD reason and a new
   * write arrives (see {@link RetentionClass}).
   *
   * <p>Deliberately a {@code switch} EXPRESSION with no {@code default} arm: Java then requires
   * exhaustiveness, so adding a future reason code is a compile error until it is classified. The
   * discipline this replaces was review-caught, and it was missed once already.
   *
   * <p>Deliberately NOT a constructor parameter. The readiness-reason-codes gate extracts the
   * vocabulary with {@code \b[A-Z][A-Z0-9_]*\s*\(\s*"([^"]+)"\s*\)}
   * ({@code scripts/ci/check-readiness-reason-codes.mjs}), which requires the closing paren
   * immediately after the quoted string; a second constant argument would extract ZERO codes and
   * fail the gate with a misleading "the producer/consumer seam moved".
   *
   * <p>Codes a {@link Capability} never holds — the {@code index.*} / {@code ocr.*} / {@code vdu.*}
   * / {@code telemetry.*} / {@code lambdamart.*} / {@code gpu.*} / {@code chunk_embedding.*} /
   * {@code index.throughput_*} / {@code encoders.health.*} / {@code engine.not_started} /
   * {@code index.unavailable} families are computed per-request in {@code StatusLifecycleHandler}
   * from worker views and never enter a reason slot — are classified {@link RetentionClass#TRANSIENT}
   * so the classification stays total: never-retained is the safe default for a code that cannot be
   * held anyway. The {@code local_api.*} family is never held for a different reason (tempdoc 884
   * item 23) but takes the same class: it names a boot refusal thrown before the loopback bind, so
   * the process is exiting and there is no reason slot to defend.
   */
  public RetentionClass retentionClass() {
    return switch (this) {
      // Fatal marker evidence is consumed once; exhausted recovery is terminal until READY.
      case INDEX_CORRUPT, INDEX_SCHEMA_OPEN_REFUSED,
          COMPONENT_RECOVERY_EXHAUSTED -> RetentionClass.STICKY;

      // Tempdoc 882 item 24: observed exactly once, at load, and never re-derived. The file that
      // proved it has already been moved aside. A later TRANSIENT write must not erase the only
      // notice the user gets that their preferences were reset.
      case DATA_NEWER_BUILD, SETTINGS_RESET_FROM_CORRUPT, SETTINGS_RECOVERY_REQUIRED, OPERATIONS_HISTORY_RESET, OPERATIONS_PERSISTENCE_FAILED -> RetentionClass.STICKY;

      // Physical failure is a real cause, whether the index never opened or was lost after READY.
      case COMPONENT_START_DEADLINE, COMPONENT_RECOVERY_FAILED,
          INDEX_FAILED, INDEX_MODEL_NOT_INSTALLED,
          INFERENCE_CRASHED,
          INFERENCE_MODEL_NOT_CONFIGURED,
          INFERENCE_MODEL_NOT_FOUND,
          INFERENCE_RUNTIME_NOT_INSTALLED,
          INFERENCE_POLICY_ONLINE_AI_DISABLED,
          INFERENCE_POLICY_GPU_DISABLED,
          INFERENCE_ACTIVATION_FAILED -> RetentionClass.FAULT;

      // The one "I know nothing" fallback in this vocabulary.
      case INFERENCE_OFFLINE -> RetentionClass.GENERIC;

      // Progress / scheduled / intentional, plus every code no capability ever holds.
      case COMPONENT_RECOVERING, INDEX_STARTING, INDEX_ACTIVATING,
          INDEX_SHUT_DOWN,
          INDEX_UNAVAILABLE,
          ENGINE_NOT_STARTED,
          ENCODERS_RELOADING,
          INDEX_THROUGHPUT_STALLED,
          INDEX_THROUGHPUT_DEGRADED,
          ENCODERS_HEALTH_EMBEDDING_NOT_READY,
          ENCODERS_HEALTH_EMBEDDING_PROBE_MISSING,
          INFERENCE_STARTING,
          INFERENCE_GPU_YIELDED_TO_INDEXING,
          INFERENCE_UP_FOR_BACKGROUND,
          INFERENCE_DEACTIVATED,
          INDEX_NOT_HEALTHY,
          INDEX_BLOCKED_LEGACY,
          INDEX_SCHEMA_MISMATCH,
          INDEX_REBUILD_BRAKE_EXHAUSTED,
          MIGRATION_AWAITING_GAP_ACCEPTANCE,
          INDEX_EMBEDDING_REBUILDING,
          INDEX_EMBEDDING_LEGACY,
          INDEX_EMBEDDING_MISMATCH,
          INDEX_DENSE_UNAVAILABLE,
          OCR_DISABLED,
          OCR_ENGINE_MISSING,
          OCR_LANGUAGE_MISSING,
          VDU_AI_OFFLINE,
          VDU_INSUFFICIENT_VRAM,
          VDU_MISSING_MMPROJ,
          VDU_CIRCUIT_OPEN,
          TELEMETRY_UNAVAILABLE,
          TELEMETRY_METRICS_STALE,
          TELEMETRY_METRICS_HIGH_FAILURE_RATE,
          TELEMETRY_DISK_SPACE_LOW,
          CHUNK_EMBEDDING_NOT_READY,
          CHUNK_EMBEDDING_IN_PROGRESS,
          LAMBDAMART_NOT_CONFIGURED,
          LAMBDAMART_TRAINING,
          LAMBDAMART_FAILED,
          // Tempdoc 884 item 23: a boot-refusal cause, not a held reason. The Head throws before
          // the loopback bind and exits, so no Capability ever holds it and no later write could
          // erase it — TRANSIENT is this vocabulary's documented total-classification default for
          // a code that cannot be held at all (see the class doc on RetentionClass#TRANSIENT).
          LOCAL_API_SESSION_TOKEN_MISSING,
          GPU_SATURATED -> RetentionClass.TRANSIENT;
    };
  }

  /**
   * The {@link RetentionClass} of an arbitrary reason-slot string. A {@code null} or unrecognized
   * value — a legacy prose sentence, or a code from a newer build — is {@link
   * RetentionClass#TRANSIENT}: never retained, which is the conservative answer for something we
   * cannot classify (prose has no precedence, per tempdoc 837 §1.4).
   */
  public static RetentionClass retentionClassOf(String code) {
    if (code == null) {
      return RetentionClass.TRANSIENT;
    }
    for (LifecycleReasonCode member : values()) {
      if (member.code.equals(code)) {
        return member.retentionClass();
      }
    }
    return RetentionClass.TRANSIENT;
  }

  public static Set<String> allowedCodes() {
    return ALLOWED_CODES;
  }
}
