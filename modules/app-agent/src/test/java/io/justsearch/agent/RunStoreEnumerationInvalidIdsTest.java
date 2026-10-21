/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoreEnumerationInvalidIdsTest {
  @TempDir Path tmp;

  @Test
  void everyRunProjectionAndDeletionSkipsInvalidFolders() throws Exception {
    Path root = tmp.resolve("runs");
    var store = new AgentRunStore(root);
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "keep")),
        List.of(), 5, List.of(), null, null, "conv-valid");
    store.startRun("run-valid", request, request.messages(), 100);
    store.markBackground("run-valid");
    var queries = new AgentRunQueryService(store, null, null, null,
        (sink, message, code, errorClass, retry, attempt) -> fail(message),
        (resumed, sink, context) -> fail("read must not start work"));
    var invalidIds = List.of(" ", "\t", "\u2028", "CON", "NUL.txt", "bad\\id", "bad:stream", "trailing.");
    for (String id : invalidIds) {
      Path dir = Files.createDirectory(root.resolve(id));
      Files.writeString(dir.resolve("meta.json"), "not metadata");
      Files.writeString(dir.resolve("events.ndjson"), "untouched events\n");
    }
    assertAll(
        () -> assertEquals(List.of("run-valid"), assertDoesNotThrow(() -> store.listSessions(1))
            .stream().map(m -> m.get("sessionId")).toList()),
        () -> assertEquals(List.of("conv-valid"), assertDoesNotThrow(() -> store.listConversations(1))
            .stream().map(m -> m.get("conversationId")).toList()),
        () -> assertEquals(List.of("run-valid"), assertDoesNotThrow(() -> store.presenceRunsSince(null))
            .stream().map(m -> m.get("sessionId")).toList()),
        () -> assertEquals(List.of("run-valid"), assertDoesNotThrow(() -> store.listRunIdsByConversation("conv-valid"))),
        () -> assertEquals(1, assertDoesNotThrow(() -> queries.lifecycles("conv-valid")).size()),
        () -> assertEquals(1, assertDoesNotThrow(() -> queries.presenceSince(null)).size()),
        () -> assertTrue(assertDoesNotThrow(() -> queries.threadEvents("conv-valid")).stream()
            .anyMatch(e -> e.content().equals("keep"))));
    assertEquals("run-valid", store.readSnapshot("run-valid").get("sessionId"));
    assertEquals(1, assertDoesNotThrow(() -> store.deleteConversationRuns("conv-valid")));
    assertFalse(Files.exists(root.resolve("run-valid")));
    for (String id : invalidIds) {
      assertEquals("not metadata", Files.readString(root.resolve(id).resolve("meta.json")));
      assertEquals("untouched events\n", Files.readString(root.resolve(id).resolve("events.ndjson")));
    }
  }

  @Test
  void startupRetentionLeavesInvalidRunFoldersUntouched() throws Exception {
    Path root = Files.createDirectory(tmp.resolve("runs"));
    var old = FileTime.from(Instant.now().minus(java.time.Duration.ofDays(40)));
    for (String id : List.of(" ", "\t", "CON")) {
      Path dir = Files.createDirectory(root.resolve(id));
      Files.writeString(dir.resolve("sentinel"), "keep");
      Files.setLastModifiedTime(dir, old);
    }
    new AgentRunStore(root);
    for (String id : List.of(" ", "\t", "CON")) {
      assertTrue(Files.exists(root.resolve(id).resolve("sentinel")), "invalid run was pruned: " + id);
      assertEquals("keep", Files.readString(root.resolve(id).resolve("sentinel")));
      assertEquals(old, Files.getLastModifiedTime(root.resolve(id)));
    }
  }
}
