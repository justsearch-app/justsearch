/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.util.TokenEstimation;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/** Per-operation guard for source material sent to a single-pass summary request. */
final class SummaryInputLimit {
  static final int DEFAULT_MAX_TOKENS = ResolvedConfig.Summary.DEFAULT_MAX_TOKENS;

  private final IntSupplier configuredMaxTokens;

  SummaryInputLimit(IntSupplier configuredMaxTokens) {
    this.configuredMaxTokens = Objects.requireNonNull(configuredMaxTokens, "configuredMaxTokens");
  }

  /** Returns a terminal error when untruncated source exceeds the configured source-input limit. */
  SseEvent rejection(String source) {
    int maxTokens = Math.max(1, configuredMaxTokens.getAsInt());
    int estimatedTokens = TokenEstimation.estimateTokens(source);
    if (estimatedTokens <= maxTokens) return null;
    return new SseEvent(
        "error",
        Map.of(
            "error",
            "Summary input is approximately " + estimatedTokens
                + " tokens, exceeding the configured summary source-input limit of "
                + maxTokens + " tokens",
            "errorCode", ApiErrorCode.CONTEXT_TOO_LARGE.name(),
            "estimatedTokens", estimatedTokens,
            "maxTokens", maxTokens));
  }
}
