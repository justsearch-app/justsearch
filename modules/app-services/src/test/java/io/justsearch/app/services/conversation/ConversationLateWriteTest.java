/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.conversation.ConversationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationLateWriteTest {
  @TempDir Path root;

  @Test void compactionCannotCreateAbsentOrDeletedConversation() throws Exception {
    refusesMissing(store -> store.compactContext("target", "m", "private summary"));
  }

  @Test void rewindCannotCreateAbsentOrDeletedConversation() throws Exception {
    refusesMissing(store -> store.setContextFloor("target", "m"));
  }

  @Test void messageExclusionCannotCreateAbsentOrDeletedConversation() throws Exception {
    refusesMissing(store -> store.excludeMessage("target", "m", true));
  }

  @Test void sourceExclusionCannotCreateAbsentOrDeletedConversation() throws Exception {
    refusesMissing(store -> store.excludeSource("target", "private-source", true));
  }

  @Test void turnStatusCannotCreateAbsentOrDeletedConversation() throws Exception {
    refusesMissing(store -> store.setTurnOpen("target", true));
  }

  @Test void messageAppendCannotCreateAbsentOrDeletedConversation() throws Exception {
    refusesMissing(store -> store.appendMessage("target", "core.free-chat",
        Map.of("role", "assistant", "content", "late private answer")));
  }

  @Test void turnStatusOwnsConversationWhileWriting() throws Exception {
    seed();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var cipher = org.mockito.Mockito.mock(io.justsearch.agent.api.encryption.StoreCipher.class);
    org.mockito.Mockito.when(cipher.enabled()).thenReturn(true);
    org.mockito.Mockito.when(cipher.open(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> call.getArgument(0));
    org.mockito.Mockito.when(cipher.seal(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
      entered.countDown();
      assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
      return call.getArgument(0);
    });
    var store = new FileConversationStore(root, cipher);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var writing = executor.submit(() -> store.setTurnOpen("target", true));
      try {
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(io.justsearch.agent.api.conversation.ConversationWork.Refused.class,
            () -> store.deleteSession("target"));
      } finally { release.countDown(); writing.get(3, java.util.concurrent.TimeUnit.SECONDS); }
    }
    assertTrue(store.isTurnOpen("target"));
  }

  private void refusesMissing(Consumer<ConversationStore> write) throws Exception {
    var store = new FileConversationStore(root);
    assertThrows(IllegalArgumentException.class, () -> write.accept(store), "unknown conversation");
    assertFalse(Files.exists(root.resolve("target")), "unknown write left content");
    seed();
    store.deleteSession("target");
    // New store models restart: no in-memory tombstone may be needed for protection.
    var restarted = new FileConversationStore(root);
    assertThrows(IllegalArgumentException.class, () -> write.accept(restarted), "deleted conversation");
    assertTrue(restarted.listSessions(null, 10).isEmpty());
    assertFalse(Files.exists(root.resolve("target")), "late write recreated content");
  }

  private void seed() throws Exception {
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve("target/meta.json"),
        "{\"schemaVersion\":1,\"shapeId\":\"core.free-chat\",\"messageCount\":1,\"firstUserMessage\":\"private question\"}");
    Files.writeString(root.resolve("target/messages.jsonl"),
        "{\"id\":\"m\",\"role\":\"user\",\"content\":\"private question\"}\n");
  }
}
