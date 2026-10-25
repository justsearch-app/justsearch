package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.conversation.BranchesPreventDeletionException;
import io.justsearch.agent.api.conversation.ConversationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileConversationStoreCaseIdsTest {
  @Test
  void savedParentCaseVariantsPreventDeletion(@TempDir Path tmp) throws Exception {
    var store = new FileConversationStore(tmp);
    for (String parent : List.of("parent", "_title_parent")) {
      store.createSession(parent, "core.free-chat");
      store.appendMessage(parent, "core.free-chat", Map.of("id", "a", "role", "assistant", "content", "answer"));
      Path child = Files.createDirectory(tmp.resolve("child-" + parent));
      Files.writeString(child.resolve("meta.json"),
          "{\"parentSessionId\":\"" + parent.toUpperCase(java.util.Locale.ROOT)
              + "\",\"branchPointMessageId\":\"a\",\"shapeId\":\"core.free-chat\"}");

      assertThrows(BranchesPreventDeletionException.class, () -> store.deleteSession(parent));
      assertEquals(1, store.loadHistory(parent).size());
    }
  }

  @Test
  void throwawayPrefixCaseVariantsCannotAcquireBranches(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    for (String parent : List.of("_TITLE_x", "_TiTlE_x")) {
      store.createSession(parent, "core.free-chat");
      store.appendMessage(parent, "core.free-chat", Map.of("id", "a", "role", "assistant", "content", "answer"));
      for (String point : List.of("a", ConversationStore.EMPTY_PREFIX_SENTINEL)) {
        assertThrows(IllegalArgumentException.class, () -> store.branchFrom(parent, point, "uc-child"));
        assertFalse(Files.exists(tmp.resolve("uc-child")));
      }
      store.deleteSession(parent);
      assertFalse(Files.exists(tmp.resolve(parent)));
    }
  }
}
