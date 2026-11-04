/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class IndexingPauseMcpTest {
  @Test
  void mcpProjectionCarriesTheSamePausedReasonAndWords() {
    var result = McpToolSurface.errorContent(new ChatRefusal(
        RefusalReason.INDEXING, Optional.empty(), false));
    assertEquals(true, result.get("isError"));
    var structured = assertInstanceOf(Map.class, result.get("structuredContent"));
    assertEquals("CHAT_UNAVAILABLE", structured.get("errorCode"));
    assertEquals("INDEXING", structured.get("reason"));
    assertEquals(false, structured.get("forceAllowed"));
    var content = assertInstanceOf(List.class, result.get("content"));
    var text = assertInstanceOf(Map.class, content.getFirst()).get("text").toString();
    assertTrue(text.contains("Answers paused while indexing"));
  }
}
