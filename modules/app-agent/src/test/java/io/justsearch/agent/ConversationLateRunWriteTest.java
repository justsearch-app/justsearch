/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationLateRunWriteTest {
  @TempDir Path root;

  @Test void checkpointCannotRecreateDeletedRun() throws Exception {
    var store = new AgentRunStore(root);
    Files.createDirectories(root.resolve("run"));
    Files.writeString(root.resolve("run/meta.json"),
        "{\"schemaVersion\":4,\"sessionId\":\"run\",\"conversationId\":\"target\"}");
    store.deleteConversationRuns("target");
    store.updateCheckpoint("run", "DONE", List.of(Map.of("role", "assistant", "content", "secret")),
        1, 0, 1, "late completion");
    assertFalse(Files.exists(root.resolve("run")), "checkpoint recreated deleted run");
    assertTrue(new AgentRunStore(root).listSessions(10).isEmpty());
  }

  @Test void metadataUpdateCannotRecreateDeletedRun() throws Exception {
    var events = new RunEventStore(root);
    var meta = Map.<String, Object>of("sessionId", "run", "conversationId", "target");
    events.writeRunMeta("run", meta);
    new AgentRunStore(root).deleteConversationRuns("target");
    assertThrows(IllegalArgumentException.class, () -> events.updateRunMeta("run", meta));
    assertFalse(Files.exists(root.resolve("run")));
  }

  @Test void liveEventCannotCreateMissingRun() {
    var events = new RunEventStore(root);
    assertThrows(IllegalArgumentException.class,
        () -> events.appendEvent("run", "core.agent-run", "done", Map.of("content", "secret")));
    assertFalse(Files.exists(root.resolve("run")));
  }

  @Test void importedEventsCannotCreateMissingRun() {
    var events = new RunEventStore(root);
    assertThrows(IllegalArgumentException.class, () -> events.appendRawEvents("run", List.of(
        Map.of("timestamp", "2020-01-01T00:00:00Z", "eventType", "done", "payload", Map.of()))));
    assertFalse(Files.exists(root.resolve("run")));
  }
}
