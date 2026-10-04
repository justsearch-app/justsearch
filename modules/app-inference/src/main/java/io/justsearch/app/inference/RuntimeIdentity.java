/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.util.Objects;

/**
 * Identifies a specific generation of the inference runtime. Surfaced on the status record (via
 * {@code RuntimeIdentityView}) and on {@code onStartupComplete} for cross-restart correlation.
 *
 * <p>Components:
 * <ul>
 *   <li>{@code generationId}: monotonic, increments on every successful {@code complete()}
 *       transition. Stable across the lifetime of one running process; resets on JVM restart.
 *   <li>{@code modelId}: model file name (e.g. {@code Qwen3-8B-Instruct-Q4_K_M.gguf}) when a
 *       process-backed phase is active; empty otherwise.
 *   <li>{@code port}: llama-server port when a process-backed phase is active; 0 otherwise.
 *   <li>{@code loadedAtEpochMs}: wall-clock at construction. Useful for cross-process drift
 *       detection when an external operator refreshes the runtime through reconfigure.
 * </ul>
 *
 * <p>Use {@link #nonProcess(long)} for {@code OFFLINE} / {@code INDEXING} phases (no
 * llama-server is running but a generationId is still meaningful for status correlation).
 */
public record RuntimeIdentity(long generationId, String modelId, int port, long loadedAtEpochMs) {

  public RuntimeIdentity {
    Objects.requireNonNull(modelId, "modelId");
  }

  /**
   * Identity for a phase that has no llama-server process backing it ({@code OFFLINE} or
   * {@code INDEXING}). Carries an empty model ID and port {@code 0}.
   */
  public static RuntimeIdentity nonProcess(long generationId) {
    return new RuntimeIdentity(generationId, "", 0, System.currentTimeMillis());
  }
}
