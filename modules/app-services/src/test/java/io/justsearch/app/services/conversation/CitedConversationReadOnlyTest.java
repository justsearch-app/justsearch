/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CitedConversationReadOnlyTest {
  @TempDir Path root;

  @Test
  void loadingSavedAskAndDelegateCitationsDoesNotRewriteAnyConversationBytes() throws Exception {
    var store = new FileConversationStore(root);
    var source = Map.of("parentDocId", "/last-known/gone.txt", "path", "/last-known/gone.txt",
        "chunkIndex", 0, "excerpt", "Recorded evidence");
    for (String shape : List.of("core.rag-ask", "core.agent-run")) {
      store.createSession(shape, shape);
      store.appendMessage(shape, shape, Map.of("role", "user", "content", "What did the source say?"));
      store.appendMessage(shape, shape, Map.of("role", "assistant", "content", "Recorded answer [1]",
          shape.equals("core.rag-ask") ? "citations" : "sources", List.of(source)));
    }
    Map<String, String> saved = bytes(root);
    for (String shape : List.of("core.rag-ask", "core.agent-run")) {
      assertEquals(2, store.loadHistory(shape).size());
      assertEquals(2, store.loadHistory(shape).size());
      assertEquals(1, store.listSessions(shape, 10).size());
    }
    assertEquals(saved, bytes(root));
  }

  private static Map<String, String> bytes(Path root) throws IOException {
    Map<String, String> result = new LinkedHashMap<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        result.put(root.relativize(file).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
      }
    }
    return result;
  }
}
