/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

/**
 * Exhaustive registry of readiness dimensions in the readiness envelope.
 *
 * <p>Each constant maps to one component in the {@code /api/status} readiness envelope. The
 * {@link #composite()} field declares which composite the dimension contributes to. Adding a new
 * constant without handling it in the exhaustive switch inside {@code
 * StatusLifecycleHandler.computeComponent()} is a compile error (Java 21+).
 *
 * <p>Stability: stable (API contract — keys appear in the readiness envelope JSON).
 */
public enum ReadinessDimension {
  // C2-1: retain the readiness JSON key; the Java symbol now names the Engine index component.
  INDEX_CONTROL_PLANE("workerControlPlane", "lifecycle_snapshot", "retrieval"),
  INDEX_SERVING("indexServing", "worker_status", "retrieval"),
  AI("ai", "lifecycle_inference", "aiFeatures"),
  EMBEDDING("embedding", "worker_health_check", "aiFeatures"),
  CHUNK_EMBEDDING("chunkEmbedding", "worker_status", "retrieval"),
  VISUAL_TEXT_EXTRACTION("visualTextExtraction", "worker_status", "retrieval"),
  VISUAL_DOCUMENT_UNDERSTANDING("visualDocumentUnderstanding", "head_vdu_status", "aiFeatures"),
  LAMBDAMART_MODEL("lambdamartModel", "head_gpl_status", "retrieval"),
  /**
   * Tempdoc 419 C3 V2 P1: telemetry-subsystem health (failure rate, export staleness, disk
   * space). Backed by {@link io.justsearch.telemetry.TelemetryHealthSnapshot} via
   * {@code TelemetryHealthClassifier}. Composite {@code "telemetry"} keeps subsystem failures
   * out of {@code aiFeatures} readiness so a flush hiccup doesn't cascade into "AI not ready."
   */
  TELEMETRY("telemetry", "telemetry_health", "telemetry"),
  /**
   * Tempdoc 419 C3 V2 P3: head-side GPU saturation (sustained high utilization while no
   * activity gate is open). Backed by {@code GpuSaturationMonitor} fed by a 15s scheduled
   * sampler in {@code LocalApiServer}. Composite {@code "aiFeatures"} since GPU saturation
   * affects AI-features performance, not retrieval correctness.
   */
  GPU("gpu", "gpu_saturation_monitor", "aiFeatures");

  private final String key;
  private final String source;
  private final String composite;

  ReadinessDimension(String key, String source, String composite) {
    this.key = key;
    this.source = source;
    this.composite = composite;
  }

  /** JSON key in {@code readiness.components}. */
  public String key() {
    return key;
  }

  /** Data source that feeds this dimension (for provenance tracking). */
  public String source() {
    return source;
  }

  /** Composite this dimension contributes to ({@code "retrieval"} or {@code "aiFeatures"}). */
  public String composite() {
    return composite;
  }
}
