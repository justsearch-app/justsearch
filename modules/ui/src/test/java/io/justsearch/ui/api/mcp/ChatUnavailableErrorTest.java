/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.ui.api.ApiErrorHandler;
import io.justsearch.ui.api.ContractSchemaAssertions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * CHAT_UNAVAILABLE and {@code retryAfterMs}: one retryable refusal, the same facts on the HTTP body
 * and the MCP projection, and a body the published error schema accepts.
 */
@DisplayName("CHAT_UNAVAILABLE carries retryAfterMs on HTTP and MCP")
final class ChatUnavailableErrorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("the code is TRANSIENT, and the HTTP body with retryAfterMs conforms to the schema")
  void httpBodyConforms() throws Exception {
    assertTrue(ApiErrorCode.CHAT_UNAVAILABLE.isRetryable());

    Map<String, Object> body = ApiErrorHandler.toResponse(
        ApiErrorCode.CHAT_UNAVAILABLE, "Chat is paused", Duration.ofSeconds(30));

    assertEquals("CHAT_UNAVAILABLE", body.get("errorCode"));
    assertEquals("TRANSIENT", body.get("errorClass"));
    assertEquals(true, body.get("retryable"));
    assertEquals(30_000L, body.get("retryAfterMs"));
    ContractSchemaAssertions.assertConforms(
        "CHAT_UNAVAILABLE refusal", "api-error-response.v1.json", JSON.writeValueAsString(body));
  }

  @Test
  @DisplayName("an unknown retry time is absent, never zero")
  void unknownRetryTimeIsAbsent() {
    Map<String, Object> body =
        ApiErrorHandler.toResponse(ApiErrorCode.CHAT_UNAVAILABLE, "Chat is paused", null);
    assertFalse(body.containsKey("retryAfterMs"));
  }

  @Test
  @DisplayName("a permanent code cannot carry a retry time, and a negative one is refused")
  void retryTimeOnlyForRetryableCodes() {
    assertThrows(IllegalArgumentException.class, () -> ApiErrorHandler.toResponse(
        ApiErrorCode.AI_OFFLINE, "off", Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> ApiErrorHandler.toResponse(
        ApiErrorCode.CHAT_UNAVAILABLE, "paused", Duration.ofMillis(-1)));
  }

  @Test
  @DisplayName("the MCP projection carries retryAfterMs in structuredContent and words it in text")
  @SuppressWarnings("unchecked")
  void mcpProjection() {
    Map<String, Object> result = McpToolSurface.errorContent(
        "Chat is paused", ApiErrorCode.CHAT_UNAVAILABLE, Duration.ofMillis(1500));

    Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
    assertEquals("CHAT_UNAVAILABLE", structured.get("errorCode"));
    assertEquals(true, structured.get("retryable"));
    assertEquals(1500L, structured.get("retryAfterMs"));
    String text = (String) ((List<Map<String, Object>>) result.get("content")).get(0).get("text");
    assertTrue(text.contains("Retry after: 1500 ms."), text);
    assertEquals(true, result.get("isError"));

    Map<String, Object> plain = (Map<String, Object>) McpToolSurface.errorContent(
        "Chat is paused", ApiErrorCode.CHAT_UNAVAILABLE).get("structuredContent");
    assertFalse(plain.containsKey("retryAfterMs"), "absent metadata stays absent");
  }
}
