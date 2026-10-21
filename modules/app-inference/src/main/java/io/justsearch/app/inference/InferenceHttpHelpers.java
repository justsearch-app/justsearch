/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import tools.jackson.databind.JsonNode;
import io.justsearch.app.api.chat.GenerationLease;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared HTTP request helpers for {@link InferenceLifecycleManager} companion classes.
 *
 * <p>All methods are pure static with no instance state. Extracted to avoid duplicating URI/request
 * construction across {@link TokenEndpointOps}, {@link OnlineModeOps}, and {@link
 * LlamaServerProcessOps}.
 */
final class InferenceHttpHelpers {
  private static final Logger LOG = LoggerFactory.getLogger(InferenceHttpHelpers.class);

  private InferenceHttpHelpers() {}

  /** Safely parse a JSON node as an {@link Integer}, returning {@code null} on failure. */
  static Integer asIntOrNull(JsonNode node) {
    if (node == null || node.isNull()) return null;
    try {
      if (node.isInt() || node.isLong() || node.isNumber()) {
        return node.asInt();
      }
      if (node.isTextual()) {
        String s = node.asText().trim();
        if (s.isBlank()) return null;
        return Integer.parseInt(s);
      }
    } catch (Exception e) {
      LOG.debug("asIntOrNull: parsing failed: {}", e.getMessage());
      return null;
    }
    return null;
  }

  static URI serverUri(LlamaServerEndpoint endpoint, String path) {
    return URI.create("http://127.0.0.1:" + endpoint.port() + path);
  }

  private static HttpRequest.Builder requestBuilder(LlamaServerEndpoint endpoint, String path) {
    HttpRequest.Builder builder = HttpRequest.newBuilder().uri(serverUri(endpoint, path));
    if (endpoint.apiKey() != null) {
      builder.header("Authorization", "Bearer " + endpoint.apiKey());
    }
    return builder;
  }

  /**
   * The one builder for llama-server exchanges (chat, vision, tokenize, templates). It takes the
   * exchange's lease so that no exchange can be built outside one (Spec A §5.2, U14).
   */
  static HttpRequest buildJsonPostRequest(
      GenerationLease lease, LlamaServerEndpoint endpoint, String path, String json, Duration timeout) {
    Objects.requireNonNull(lease, "a llama-server exchange needs a generation lease");
    return requestBuilder(endpoint, path)
        .header("Content-Type", "application/json")
        .timeout(timeout)
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
  }

  /** Lifecycle probes only ({@code /health}, {@code /props}); never a generation exchange. */
  static HttpRequest buildGetRequest(LlamaServerEndpoint endpoint, String path, Duration timeout) {
    return requestBuilder(endpoint, path).timeout(timeout).GET().build();
  }
}
