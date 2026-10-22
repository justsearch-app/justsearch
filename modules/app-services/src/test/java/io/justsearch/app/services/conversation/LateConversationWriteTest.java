/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.conversation.ConversationWork;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LateConversationWriteTest {
  @TempDir Path temp;

  @Test
  void deletedIdentityCannotBeRecreatedByAFirstMessageOrAnyControl() {
    var store = new FileConversationStore(temp);
    store.createSession("conversation", "core.free-chat");
    store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "private"));
    store.deleteSession("conversation");
    store.conversationWork().completed("conversation");
    List<Runnable> writers = List.of(
        () -> store.createSession("conversation", "core.free-chat"),
        () -> store.appendMessage("conversation", "core.free-chat", Map.of("role", "user", "content", "late")),
        () -> store.setTitle("conversation", "late", "user"),
        () -> store.setTitleIfUntitled("conversation", "late", "auto"),
        () -> store.setTurnOpen("conversation", true),
        () -> store.branchFrom("conversation", "message", "branch"),
        () -> store.setContextFloor("conversation", null),
        () -> store.compactContext("conversation", "message", "late"),
        () -> store.excludeMessage("conversation", "message", true),
        () -> store.excludeSource("conversation", "source", true));
    assertAll(writers.stream().<org.junit.jupiter.api.function.Executable>map(writer -> () -> {
      var failure = assertThrows(ConversationWork.Refused.class, writer::run);
      assertEquals("CONVERSATION_DELETED", failure.code());
      assertFalse(failure.getMessage().isBlank());
    }));
    assertTrue(store.listSessions(null, 20).isEmpty());
    assertFalse(java.nio.file.Files.exists(temp.resolve("conversation")));
    store.createSession("fresh-conversation", "core.free-chat");
    store.appendMessage("fresh-conversation", "core.free-chat", Map.of("role", "user", "content", "first write"));
    assertEquals(1, store.loadHistory("fresh-conversation").size());
  }
}
