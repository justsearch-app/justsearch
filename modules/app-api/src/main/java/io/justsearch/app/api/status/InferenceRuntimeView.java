/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Inference runtime status sub-view for {@code /api/status}. Tempdoc 412 replaced the prior
 * {@code LlmStatusView} + {@code OnlineAiView} pair with this single record; the tempdoc 412
 * follow-up dropped the {@code queue} and {@code generation} sub-records (no scraper exists
 * to populate them) — they will return when a Prometheus {@code /metrics} scraper is wired
 * (the {@code --metrics} flag is already enabled on the llama-server launch).
 *
 * <h3>Phase values</h3>
 *
 * <ul>
 *   <li>{@code OFFLINE} — no llama-server is running; {@link #identity} is null.
 *   <li>{@code TRANSITIONING} — holder is mid-swap; identity is null.
 *   <li>{@code ONLINE} — llama-server is running, request lock is open. The
 *       {@code schema.vduMode} flag distinguishes normal vs VDU sub-modes.
 *   <li>{@code INDEXING} — GPU yielded to worker; no llama-server.
 * </ul>
 *
 * <h3>Runtime-authority projection (tempdoc 737 §12c, Phase 2a)</h3>
 *
 * <p>{@code chatEnabledSpec} / {@code engineState} / {@code engineReason} / {@code procedure} /
 * {@code leaseHolder} additively project the Head-side runtime authority
 * ({@code RuntimeSpec}/{@code RuntimeStatus}, {@code modules/app-services/.../runtimestate/}, tempdoc
 * 737 §12a) onto this wire view. All five are optional in the sense that an absent authority (e.g.
 * inference not configured) projects them at their empty/default value — {@code false} / {@code ""}
 * — never {@code null}, so existing consumers that ignore them are unaffected.
 *
 * <ul>
 *   <li>{@code chatEnabledSpec} — the user's persisted chat-enabled intent
 *       ({@code RuntimeSpec.chatEnabled()}), independent of whether the engine is currently up.
 *   <li>{@code engineState} — the ENGINE axis condition status: {@code Down | Starting | Healthy |
 *       Recovering} (see {@code RuntimeStatus.ENGINE_*} constants).
 *   <li>{@code engineReason} — the ENGINE axis condition's internal reason code (empty when no
 *       authority observation exists yet).
 *   <li>{@code procedure} — the active machine-actor {@code RuntimeStatus.ProcedureKind} (e.g.
 *       {@code VDU_BATCH}), or {@code ""} when no procedure is in flight.
 *   <li>{@code leaseHolder} — the GPU lease holder: {@code CHAT | INDEXING | NONE}
 *       ({@code RuntimeGpuLease.Holder}).
 * </ul>
 *
 * <p>Stability: stable (API contract)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InferenceRuntimeView(
    String phase,
    RuntimeIdentityView identity,
    boolean usingExternal,
    InferenceFailureView lastFailure,
    LifecycleCounters counters,
    boolean chatEnabledSpec,
    String engineState,
    String engineReason,
    String procedure,
    String leaseHolder) {

  public InferenceRuntimeView {
    phase = phase == null ? "OFFLINE" : phase;
    engineState = engineState == null ? "" : engineState;
    engineReason = engineReason == null ? "" : engineReason;
    procedure = procedure == null ? "" : procedure;
    leaseHolder = leaseHolder == null ? "" : leaseHolder;
  }

  /**
   * @deprecated superseded by {@link #engineState()} (tempdoc 737 §12c item 3 — {@code phase}'s
   *     {@code TRANSITIONING} value and the sibling {@code starting} wire alias on {@code
   *     InferenceStatusResponse} both collapse into the ENGINE axis vocabulary). Retirement
   *     trigger (tempdoc 737 §12d): after the FE has cut over to {@code engineState} and a public
   *     deprecation window has elapsed. Kept byte-identical in the meantime — this projection does
   *     not change {@code phase}'s derivation or its accessor.
   */
  @Deprecated
  @Override
  public String phase() {
    return phase;
  }
}
