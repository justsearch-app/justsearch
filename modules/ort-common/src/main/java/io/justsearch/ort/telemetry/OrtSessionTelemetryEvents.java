/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

/**
 * Events-interface seam between {@code ort-common} (no telemetry dep) and the
 * {@code worker-services} metric catalog. {@code NativeSessionHandle} consumes only this
 * interface; the production binding is {@code OrtSessionTelemetryAdapter}.
 *
 * <p>Three categories of events:
 *
 * <ul>
 *   <li>{@link #onTransition(TransitionReason)} — counter-shaped handle lifecycle transitions.
 *       The sealed permit set on {@link TransitionReason} is the contract for the metric set.
 *   <li>{@link #onAssemblerEvent(AssemblerEvent)} — counter-shaped construction-time events
 *       fired from {@code OrtSessionAssembler} before any handle exists.
 *   <li>{@link #onSemaphoreWait(String, long)} — histogram-shaped GPU-only semaphore wait
 *       latency. Hot path; the production adapter caches projected attributes per
 *       {@code consumer} to avoid per-emit allocation.
 * </ul>
 *
 * <p>Default-void methods make {@link #NOOP} a single anonymous-class instance with no override
 * cost. Matches the {@code LuceneRuntimeTypes.TelemetryEvents} precedent in
 * {@code modules/adapters-lucene}.
 */
public interface OrtSessionTelemetryEvents {

  /**
   * Records a handle lifecycle transition. The {@link TransitionReason} permit determines which
   * counter increments and what tag values it carries.
   */
  default void onTransition(TransitionReason reason) {}

  /**
   * Records a construction-time event fired by {@code OrtSessionAssembler} before any handle is
   * returned. Separated from {@link #onTransition(TransitionReason)} because assembler-time
   * events aren't transitions of an existing handle.
   */
  default void onAssemblerEvent(AssemblerEvent event) {}

  /**
   * Records the wait time for a seat of the GPU device gate (formerly the per-handle inference
   * semaphore; the metric keeps its name). Fires only on the GPU acquire path (CPU acquires are
   * not gated). Microsecond resolution captures the no-contention
   * fast path; millisecond would truncate to zero.
   *
   * @param consumer encoder name (matches {@link TransitionReason#consumer()})
   * @param waitUs wait time in microseconds
   */
  default void onSemaphoreWait(String consumer, long waitUs) {}

  /** No-op binding for tests + non-instrumented contexts. Single shared instance. */
  OrtSessionTelemetryEvents NOOP = new OrtSessionTelemetryEvents() {};
}
