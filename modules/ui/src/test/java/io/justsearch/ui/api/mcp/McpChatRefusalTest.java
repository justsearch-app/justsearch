/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Spec A step 9: a chat refusal keeps its typed facts on MCP: the error code, the retry time, the
 * refusal reason and whether a person may load anyway.
 */
final class McpChatRefusalTest {

  @Test
  @SuppressWarnings("unchecked")
  void theMcpProjectionCarriesTheRefusalReasonRetryTimeAndForceAllowed() throws Exception {
    var refusal = new ChatRefusal(RefusalReason.GPU_BUSY, Optional.of(Duration.ofSeconds(30)), false);

    // A tool's failure reaches the MCP client through toolFailureContent, wrapped as join() wraps it.
    Method toolFailure =
        McpToolSurface.class.getDeclaredMethod("toolFailureContent", String.class, Exception.class);
    toolFailure.setAccessible(true);
    Map<String, Object> result = (Map<String, Object>)
        toolFailure.invoke(null, "Chat", new CompletionException(refusal));

    Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
    assertEquals("CHAT_UNAVAILABLE", structured.get("errorCode"));
    assertEquals("TRANSIENT", structured.get("errorClass"));
    assertEquals(true, structured.get("retryable"));
    assertEquals(30_000L, structured.get("retryAfterMs"));
    assertEquals("GPU_BUSY", structured.get("reason"));
    assertEquals(false, structured.get("forceAllowed"));
    String text = (String) ((List<Map<String, Object>>) result.get("content")).get(0).get("text");
    assertTrue(text.contains("Retry after: 30000 ms."), text);
    assertTrue(text.contains("Reason: GPU_BUSY."), text);
    assertEquals(true, result.get("isError"));
  }
}
