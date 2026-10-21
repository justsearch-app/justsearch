package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.conversation.ConversationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileConversationStoreSafeIdsTest {
  @TempDir Path tmp;

  @Test
  void refusesWindowsAliasesBeforeConversationAccess() throws Exception {
    Path root = tmp.resolve("conversations");
    var store = new FileConversationStore(root);
    try (var indexer = new io.justsearch.app.services.agenthistory.AgentHistoryIndexer(
        new io.justsearch.core.execution.TestEngineExecutors(), tmp.resolve("history"), () -> null)) {
      for (String id : List.of("...", "a.", "a ", "CON", "con.txt", "PrN", "AUX", "NUL", "nul.txt",
          "COM1", "com9.ext", "LPT1", "lpt9.ext")) {
        assertAll(id,
            () -> assertThrows(IllegalArgumentException.class, () -> store.loadHistory(id)),
            () -> assertThrows(IllegalArgumentException.class, () -> store.deleteSession(id)),
            () -> assertThrows(IllegalArgumentException.class, () -> store.appendMessage(id, "core.free-chat", Map.of())),
            () -> assertThrows(IllegalArgumentException.class, () -> indexer.reindexRestoredRun(id, List.of())),
            () -> assertFalse(Files.exists(root)));
      }
    }
  }

  @Test
  void refusesUnsafeIdsBeforeAnyOperationAndPreservesSentinels() throws Exception {
    Path parent = Files.createDirectories(tmp.resolve("parent"));
    Path root = parent.resolve("conversations");
    Path file = Files.writeString(parent.resolve("sentinel"), "keep");
    Path folder = Files.createDirectories(tmp.resolve("above"));
    Path nested = Files.writeString(folder.resolve("keep"), "keep");
    var store = new FileConversationStore(root);
    for (String id : unsafeIds()) {
      List<org.junit.jupiter.api.function.Executable> operations = List.of(
          () -> store.loadHistory(id), () -> store.loadEffectiveContext(id),
          () -> store.getSessionMeta(id), () -> store.appendMessage(id, "free-chat", Map.of("role", "user", "content", "x")),
          () -> store.setContextFloor(id, "m"), () -> store.setTitle(id, "title", "user"),
          () -> store.setTitleIfUntitled(id, "title", "auto"), () -> store.compactContext(id, "m", "summary"),
          () -> store.excludeMessage(id, "m", true), () -> store.excludedMessageIds(id),
          () -> store.excludeSource(id, "s", true), () -> store.excludedSourceIds(id),
          () -> store.setTurnOpen(id, true), () -> store.isTurnOpen(id),
          () -> store.branchFrom(id, ConversationStore.EMPTY_PREFIX_SENTINEL, "child"),
          () -> store.branchFrom("parent", ConversationStore.EMPTY_PREFIX_SENTINEL, id),
          () -> store.deleteSession(id));
      for (var operation : operations) {
        assertThrows(IllegalArgumentException.class, operation, "unsafe id: " + id);
        assertFalse(Files.exists(root), "refusal must precede even creation of the store root");
        assertEquals("keep", Files.readString(file));
        assertEquals("keep", Files.readString(nested));
      }
    }
  }

  private static List<String> unsafeIds() {
    return List.of("", " ", ".", "..", "../sentinel", "../../above", "a/b", "a\\b",
        "..\\..", "C:\\outside", "C:outside", "name:stream", "/absolute", "\\\\host\\share", "a\u0000b", "a\nb", "a\u007fb");
  }

  @Test
  void recursiveDeleteCannotRemoveSentinelsBesideAndAboveRoot() throws Exception {
    int attempt = 0;
    for (String id : List.of("..", "../..", "..\\..")) {
      Path area = Files.createDirectory(tmp.resolve("attempt-" + attempt++));
      Path parent = Files.createDirectory(area.resolve("parent"));
      Path root = Files.createDirectory(parent.resolve("conversations"));
      Path beside = Files.writeString(parent.resolve("sentinel"), "keep");
      Path above = Files.writeString(Files.createDirectory(area.resolve("above")).resolve("sentinel"), "keep");
      try {
        new FileConversationStore(root).deleteSession(id);
      } catch (IllegalArgumentException refused) {
        // A refusal is expected; survival is observed on disk below.
      }
      assertTrue(Files.isRegularFile(beside), "recursive delete removed the adjacent sentinel: " + id);
      assertTrue(Files.isRegularFile(above), "recursive delete removed the sentinel above the root: " + id);
      assertEquals("keep", Files.readString(beside));
      assertEquals("keep", Files.readString(above));
    }
  }

  @Test
  void validatesBranchDestinationBeforeReadingParent() throws Exception {
    var store = new FileConversationStore(tmp);
    Path parent = Files.createDirectory(tmp.resolve("parent"));
    Files.writeString(parent.resolve("meta.json"), "corrupt");
    assertThrows(IllegalArgumentException.class,
        () -> store.branchFrom("parent", ConversationStore.EMPTY_PREFIX_SENTINEL, "../outside"));
  }

  @Test
  void preservesCreatedAndLegacyIdsWithoutRewritingSavedData() throws Exception {
    var store = new FileConversationStore(tmp);
    for (String id : List.of("550e8400-e29b-41d4-a716-446655440000", "uc-550e8400-e29b-41d4-a716-446655440000",
        "s-550e8400-e29b-41d4-a716-446655440000", "run-550e8400-e29b-41d4-a716-446655440000", ConversationStore.THROWAWAY_SESSION_PREFIX + "123", "session_1", "sess-1", "wf-1", "旧会話 1", "a.b")) {
      Path dir = Files.createDirectory(tmp.resolve(id));
      String saved = "{\"role\":\"user\",\"content\":\"legacy\"}\n";
      Path messages = Files.writeString(dir.resolve("messages.jsonl"), saved);
      assertEquals("legacy", store.loadHistory(id).getFirst().get("content"));
      assertEquals(saved, Files.readString(messages));
      store.appendMessage(id, "free-chat", Map.of("id", "m", "role", "user", "content", "new"));
      assertEquals(2, store.loadHistory(id).size());
      store.branchFrom(id, "m", id + "-branch");
      assertEquals(2, store.loadHistory(id + "-branch").size());
      store.setTitle(id, "title", "user");
      assertEquals("title", store.getSessionMeta(id).orElseThrow().title());
      store.deleteSession(id + "-branch");
      store.deleteSession(id);
      assertFalse(Files.exists(dir));
    }
  }
}
