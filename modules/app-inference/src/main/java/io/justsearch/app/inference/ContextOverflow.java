/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.chat.ContextExceededException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact llama-server context exhaustion, including errors carried inside a successful SSE body. */
final class ContextOverflow {
  private static final ObjectMapper JSON = new ObjectMapper();
  private ContextOverflow() {}

  static RuntimeException http(int status, String body) {
    if ((status == 400 || status == 500) && body != null) {
      try {
        JsonNode node = JSON.readTree(body);
        if (node.path("error").path("code").asInt(-1) == status && matches(node)) {
          return new ContextExceededException();
        }
      } catch (Exception malformed) {
        // An unrelated or malformed server error retains the existing status/body handling.
      }
    }
    return new LlmServerException(status, body);
  }

  static void checkStream(JsonNode node) {
    if (matches(node)) throw new ContextExceededException();
  }

  private static boolean matches(JsonNode node) {
    JsonNode error = node.path("error");
    if (!error.isObject()) return false;
    // b8571 uses this typed 400 for both splittable and non-splittable oversized input.
    if (error.path("code").asInt(-1) == 400
        && "exceed_context_size_error".equals(error.path("type").asText(""))) return true;
    return error.path("code").asInt(-1) == 500
        && "server_error".equals(error.path("type").asText(""))
        && "Context size has been exceeded.".equals(error.path("message").asText(""));
  }
}
