/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import tools.jackson.databind.JsonNode;

/** Token-bearing OpenAI chunks, before filtering visible text or dispatching callbacks. */
public final class CompletionTokenProgress {
  private CompletionTokenProgress() {}

  public static boolean hasToken(JsonNode chunk) {
    JsonNode delta = chunk.path("choices").path(0).path("delta");
    if (!delta.path("content").asString("").isEmpty()
        || !delta.path("reasoning_content").asString("").isEmpty()) return true;
    for (JsonNode call : delta.path("tool_calls")) {
      if (!call.path("id").asString("").isEmpty()
          || !call.path("function").path("name").asString("").isEmpty()
          || !call.path("function").path("arguments").asString("").isEmpty()) return true;
    }
    return false;
  }
}
