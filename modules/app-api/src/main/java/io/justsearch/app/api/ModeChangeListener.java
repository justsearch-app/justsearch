/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Listener notified when the inference runtime's mode changes. Tempdoc 518 P4 — promoted from
 * the deleted {@code InferenceLifecycleManager.ModeChangeListener} nested interface so external
 * observers in {@code app-services} / {@code ui} can subscribe via
 * {@link OnlineAiLifecycleControl#addModeChangeListener(ModeChangeListener)} without importing
 * the implementation package.
 *
 * <p>The legacy listener path observes the intermediate {@link Mode#TRANSITIONING} state. The
 * typed telemetry {@code onTransition} event suppresses the half-event; see
 * {@code io.justsearch.app.inference.telemetry.InferenceTelemetryEvents} for the canonical
 * contract.
 *
 * <h4>When to use this versus the async channels (tempdoc 518 Appendix G Wave C.1)</h4>
 *
 * <p>The codebase has three transition-observation channels with distinct contracts:
 *
 * <ul>
 *   <li><b>{@code ModeChangeListener} (this interface)</b> — synchronous, in-process,
 *       runs <i>under the runner's lock</i> as part of the transition's commit. Use this
 *       channel when the side effect <b>must complete before the next transition can start</b>
 *       (e.g., GPU broadcast to the Worker via {@code WorkerSignalBus.writeGpuActive}, where
 *       the next transition would be incorrect if the worker hadn't yet received the flag).
 *       Listeners that throw are caught + logged via {@code ObservableNotifier}; the runner
 *       continues to the next listener. Side effects should be fast (shared-gauge update,
 *       capability transition) — long work blocks the transition's apparent duration.
 *   <li><b>{@code inference.transition} OTel span</b> (tempdoc 518 Appendix G S3) — async
 *       observability. Carries strictly more data (reason, duration, success, wire_code,
 *       generation). Use for cross-correlated tracing (e.g., "which generation was active
 *       when this chat request fired?").
 *   <li><b>{@code inference-transitions.ndjson} sidecar</b> (tempdoc 518 Appendix G W4.B.1)
 *       — async persistence. Survives JVM restart. Use for forensic replay (drive a fresh
 *       {@code ModeStateMachine} through recorded transitions to reproduce a stuck state).
 * </ul>
 *
 * <p>Rule of thumb: pick {@code ModeChangeListener} only when your subscriber needs the
 * <i>happens-before</i> guarantee with respect to the next transition. For pure
 * observation, prefer the span (live) or sidecar (post-hoc).
 */
@FunctionalInterface
public interface ModeChangeListener {
  void onModeChange(Mode from, Mode to);
}
