/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Sub-category axis on {@link DiagnosticChannel}.
 *
 * <p>Per slice 448 §4: classifies a diagnostic emission's structural origin so consumers
 * can subscribe selectively without per-channel filter discipline. Maps cleanly to
 * logger-namespace prefixes (slice 448 §0 D2): {@code io.justsearch.*} → CORE_DIAGNOSTIC,
 * {@code org.apache.lucene.*} / {@code io.netty.*} / {@code io.grpc.*} → LIBRARY_TRACE,
 * {@code ch.qos.logback.*} → BOOT_TRACE, explicit MDC marker → DELIVERY_INTERNAL.
 *
 * <p>Closed enum: new sub-category sets are added via substrate amendment, not per-channel
 * configuration.
 */
public enum SubCategory {

  /** Application code we own ({@code io.justsearch.*}). Default-subscribed. */
  CORE_DIAGNOSTIC,

  /**
   * Third-party libraries (Lucene, Netty, Tika, ORT). Off by default; opt-in via
   * subscription parameter.
   */
  LIBRARY_TRACE,

  /**
   * Logback's own boot output ({@code ch.qos.logback.*}). Off by default; opt-in for
   * Logback configuration debugging.
   */
  BOOT_TRACE,

  /**
   * The substrate's own SSE-emit code, marked explicitly to prevent self-observation
   * recursion. Excluded by default from any subscription via Logback {@code MarkerFilter};
   * opt-in only with explicit override.
   */
  DELIVERY_INTERNAL
}
