/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;

/**
 * Minimal introspection surface for the Online AI runtime configuration.
 *
 * <p>This exists to support debugging/runtime grounding (effective-config) without leaking
 * app-services implementation types across module boundaries.
 */
public interface OnlineAiRuntimeIntrospection {

  /**
   * Snapshot of the current requested inference runtime configuration.
   *
   * <p>Notes:
   * <ul>
   *   <li>{@code usingExternalLlamaServer=true} means Online Mode uses the user's own llama-server,
   *       named by {@code justsearch.inference.external_endpoint}; the app has no owned process
   *       handle and never stops it (ADR-0051 decision 2).</li>
   *   <li>{@code serverPort} is where the running server listens: a managed launch's fresh
   *       loopback port or the external endpoint's port; the configured port while none runs.</li>
   *   <li>Paths are returned as raw strings. Callers producing shareable artifacts MUST redact.</li>
   * </ul>
   */
  record RuntimeInfo(
      String serverExecutable,
      String modelPath,
      String mmprojPath,
      int serverPort,
      int contextSize,
      int gpuLayers,
      boolean usingExternalLlamaServer) {}

  /**
   * Safe, low-cardinality diagnostics for the user's external llama-server.
   *
   * <p>This is intended for UI/health surfaces. It must not include full filesystem paths or other
   * high-cardinality values.
   *
   * <p>{@code note} is {@code external_server_ignored} while a healthy server answers on the
   * configured port but is not used, because {@code justsearch.inference.external_endpoint} is not
   * set (ADR-0051 decision 2); null otherwise.
   */
  record ExternalServerStatus(
      boolean usingExternalLlamaServer,
      boolean verified,
      String verificationError,
      String modelId,
      Integer contextTokens,
      boolean modelMismatch,
      boolean contextTooSmall,
      long adoptedAtMs,
      long lastHealthOkAtMs,
      String lastHealthError,
      int consecutiveHealthFailures,
      String note) {}

  /**
   * The context window this process launched llama-server with, and why (tempdoc 883 decision 1).
   *
   * <p>The window is a DERIVED resource: a ladder rung chosen from the backend, stepped down when
   * the server refuses it, or an operator's explicit override. This record is the intent — what was
   * asked for. What the server reports back ({@code /props} {@code n_ctx}, and the {@code n_ctx_seq}
   * in its log) stays the authority for what a request actually gets; never present one as the
   * other.
   *
   * @param rung the {@code -c} value passed at launch
   * @param reason {@code top-rung} (the ladder's top rung loaded), {@code override}, or
   *     {@code stepped-from:<planned top rung>}
   * @param freeVramBytes NVML free VRAM at plan time — recorded for diagnosis, not an input
   * @param slots the {@code -np} value passed at launch
   * @param kvType the {@code -ctk}/{@code -ctv} cache type passed at launch
   */
  record ContextWindow(
      int rung, String reason, Long freeVramBytes, int slots, String kvType) {}

  /**
   * Returns a best-effort runtime info snapshot, or {@code null} if unavailable.
   */
  RuntimeInfo runtimeInfo();

  /**
   * Returns the launched context-window record, or {@code null} when this process launched no
   * server (nothing started yet, or an adopted external instance whose window it did not choose).
   */
  default ContextWindow contextWindow() {
    return null;
  }

  /**
   * Returns safe diagnostics about external server adoption, or {@code null} if unavailable.
   */
  default ExternalServerStatus externalServerStatus() {
    return null;
  }

  /**
   * Returns a warning message if CUDA runtime is missing for GPU mode, or {@code null} if OK.
   *
   * <p>This is surfaced in /api/inference/status for UI visibility. When the dynamically-linked
   * ggml-cuda.dll is present but cudart64_*.dll is missing, GPU acceleration will silently fail.
   */
  default String cudaRuntimeWarning() {
    return null;
  }

  /**
   * Returns the elapsed ms for the last inference startup cycle (startLlamaServer +
   * waitForServerHealth), or -1 if no startup has completed yet.
   */
  default long lastStartupDurationMs() {
    return -1;
  }

  /**
   * Returns {@code true} if the inference runtime has vision capability (mmproj configured).
   *
   * <p>Used by the frontend to gate VDU features. When {@code false}, the UI should disable
   * VDU buttons rather than letting requests fail at the VduProcessor guard.
   */
  default boolean hasVisionCapability() {
    return false;
  }

  /**
   * Returns the active model ID (best-effort, from {@code /props} or config filename),
   * or {@code null} if unknown.
   *
   * <p>Used by the frontend to display the current model name in the Brain panel.
   */
  default String activeModelId() {
    return null;
  }

  /**
   * One row of the mode-transition timeline ring buffer (tempdoc 518 Appendix F W3.2).
   *
   * <p>Records both successful and failed transitions. {@code fromMode} / {@code toMode} are
   * the wireValue() of {@link Mode}. {@code reason} is the wireValue of the transition reason
   * (USER_SWITCH / CONFIG_APPLY / CRASH_RECOVERY / etc.). {@code success} distinguishes
   * commit vs rollback. {@code wireCode} is non-null only when {@code success=false}, mirroring
   * the value used in the metric tag.
   */
  record TransitionRecord(
      long timestampMs,
      String fromMode,
      String toMode,
      String reason,
      boolean success,
      long durationMs,
      String wireCode) {}

  /**
   * Returns the N most recent mode transitions, newest first, capped at the runtime's internal
   * buffer size (currently 20). {@code limit} of 0 or negative returns the empty list.
   *
   * <p>Recorded at the runner's {@code emitTransition} site so every {@code run()} commit or
   * rollback and every {@code runForceOffline} writes one row. The buffer is in-memory only.
   */
  default List<TransitionRecord> recentTransitions(int limit) {
    return List.of();
  }

  /**
   * One row of the failure-history ring buffer, surfaced for the Brain panel + diagnostic UI.
   *
   * <p>Tempdoc 518 Appendix F W2.1. {@code timestampMs} is wall-clock from
   * {@link System#currentTimeMillis()}. {@code category} is one of {@code "startup"},
   * {@code "health"}, {@code "config"}, {@code "transition"} (the
   * {@link InferenceFailure} subtype's tag). {@code wireCode} is the same canonical wire form
   * used as a metric tag value. {@code detail} is human-readable; never null.
   */
  record FailureRecord(long timestampMs, String category, String wireCode, String detail) {}

  /**
   * Returns the runtime's monotonic generation counter. Bumped on every transition
   * (start, stop, config-reload, forced-offline, etc.). Frontends use this to detect "inference
   * was restarted during this chat session, context may be stale" — by caching the value at
   * session start and comparing on subsequent polls. Tempdoc 518 Appendix F W3.3.
   *
   * <p>Returns {@code -1} when the introspection has no current value (default).
   */
  default long currentGeneration() {
    return -1L;
  }

  /**
   * Returns the N most recent failures recorded on the runtime, newest first, capped at the
   * runtime's internal buffer size (currently 20). A {@code limit} of 0 or negative returns the
   * empty list.
   *
   * <p>Failures are recorded from three sites in {@code TransitionRunner}: rollback installs
   * (transition body failure), {@code runForceOffline} (crash recovery), and
   * {@code recordFailureOutsideTransition} (pre-guard fast-fail before a transition begins).
   * The buffer is in-memory only; it does not persist across process restarts.
   */
  default List<FailureRecord> recentFailures(int limit) {
    return List.of();
  }
}
