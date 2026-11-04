/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection.ContextWindow;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection.ExternalServerStatus;
import io.soabase.recordbuilder.core.RecordBuilder;

/**
 * Response for {@code GET /api/inference/status}.
 *
 * <p>Tempdoc 663 §L/Stage 4 — replaces the hand-built {@code Map<String,Object>} previously
 * assembled in {@code InferenceHandlers.handleInferenceStatus}. Every field below existed on the
 * wire before this record was introduced; none were added or dropped, only typed. {@code mode}
 * stays a plain {@code String} (not the {@code Mode} enum) — {@code OnlineAiService.getCurrentMode()}
 * is itself declared to return {@code String} (its own concrete implementations compute the wire
 * value directly), so enforcing the mode *value* set at compile time would mean changing that
 * interface's contract, a larger and separately-scoped change; this record's contribution is the
 * JSON *shape* (field names/types) becoming schema-generated and cross-language-fixture-checked,
 * closing the "hand-built endpoint feeding a hand-typed FE field, no schema between them" gap the
 * design named — not a mode-value compile-time guarantee.
 *
 * <p>Stability: stable (API contract) — {@code HttpModeTransitionTest} asserts on
 * {@code mode}/{@code available}/{@code starting}; {@code docs/reference/api-contract-map.md}
 * documents this endpoint.
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InferenceStatusResponse(
    String mode,
    boolean available,
    boolean starting,
    Integer llmContextTokens,
    Integer configuredContextTokens,
    int embeddingQueueSize,
    int vduQueueSize,
    ExternalServerStatus externalServer,
    String cudaRuntimeWarning,
    Long lastStartupDurationMs,
    boolean hasVisionCapability,
    String activeModelId,
    Long generation,
    InferenceGpuView gpu,
    String tier,
    // Tempdoc 883 decision 1 — additive: the window this process launched with and why (rung,
    // fit/override/stepped-from, free VRAM at plan time, slots, KV type). Null when the process
    // launched no server. `llmContextTokens` above stays the OBSERVED window from /props; this is
    // the INTENT, and the two are deliberately separate fields.
    ContextWindow contextWindow,
    Boolean answersPausedWhileIndexing) {}
