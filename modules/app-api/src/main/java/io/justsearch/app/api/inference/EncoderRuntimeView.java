/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.inference;

import io.justsearch.app.api.status.OrtCudaView;
import java.util.Map;

/**
 * Per-encoder derived runtime accelerator state (tempdoc 422).
 *
 * <p>Aggregates the policy-snapshot view of how an encoder was configured with the runtime
 * OrtCuda probe view of what actually happened, exposing a single human-readable explanation
 * of why a given encoder is currently on CPU/GPU/unavailable. Backs the
 * {@code GET /api/inference/encoders} endpoint.
 *
 * <p>{@code currentAccelerator} values: {@code "cuda"}, {@code "cpu"}, {@code "unavailable"}.
 *
 * <p>{@code configuredAccelerator} mirrors the policy {@code variant.executionProvider} field
 * (typically {@code "CUDA"} or {@code "CPU"}; {@code ""} when policy is missing).
 *
 * <p>{@code policy} is the raw per-encoder sub-map from
 * {@code KnowledgeClient.getSessionPolicies()} (untyped per §14.28 U4); callers can
 * traverse it to inspect arena cap, device id, etc. without re-shaping.
 */
public record EncoderRuntimeView(
    String currentAccelerator,
    String configuredAccelerator,
    boolean available,
    String explanation,
    Map<String, Object> policy,
    OrtCudaView details) {

  public EncoderRuntimeView {
    currentAccelerator = currentAccelerator == null ? "" : currentAccelerator;
    configuredAccelerator = configuredAccelerator == null ? "" : configuredAccelerator;
    explanation = explanation == null ? "" : explanation;
    policy = policy == null ? Map.of() : Map.copyOf(policy);
    details = details == null ? OrtCudaView.notConfigured() : details;
  }
}
