package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoresSafeIdsTest {
  @TempDir Path tmp;

  @Test
  void refusesUnsafeIdsAcrossRunAndEventOperationsBeforeFilesystemAccess() throws Exception {
    Path parent = Files.createDirectory(tmp.resolve("parent"));
    Path root = parent.resolve("runs");
    Path next = Files.writeString(parent.resolve("sentinel"), "keep");
    Path above = Files.writeString(Files.createDirectory(tmp.resolve("above")).resolve("sentinel"), "keep");
    var store = new AgentRunStore(root);
    var events = store.runEvents();
    var queries = new AgentRunQueryService(store, null, null, null,
        (sink, message, code, errorClass, retry, attempt) -> fail("unsafe id reached resume/fork: " + message),
        (request, sink, context) -> fail("unsafe id reached the runner"));
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "hello")), List.of(), 5);
    for (String id : List.of("", " ", ".", "..", "../sentinel", "../../above", "a/b", "a\\b", "..\\..",
        "C:\\outside", "C:outside", "x:stream", "/absolute", "\\\\host\\share", "a\u0000b", "a\nb", "a\u007fb")) {
      List<org.junit.jupiter.api.function.Executable> operations = List.of(
          () -> store.startRun(id, request, request.messages(), 100),
          () -> store.readSnapshot(id), () -> store.readEvents(id),
          () -> queries.resumeSession(id, ignored -> {}, null),
          () -> queries.forkSession(id, "", ignored -> {}, null),
          () -> store.updateCheckpoint(id, "READY_FOR_LLM", request.messages(), 0, 0, 0, ""),
          () -> store.setHandoffState(id, "a", List.of()),
          () -> store.setTerminationReason(id, new TerminationReason(TerminalDisposition.CANCELLED, null, CancelTrigger.USER)),
          () -> store.appendEvent(id, new AgentEvent.SessionStarted(id)),
          () -> store.markBackground(id), () -> store.markInterrupted(id, Instant.EPOCH),
          () -> store.listRunIdsByConversation(id), () -> store.deleteConversationRuns(id),
          () -> events.appendEvent(id, "core.agent-run", "done", Map.of()),
          () -> events.appendRawEvents(id, List.of(Map.of("eventType", "done"))),
          () -> events.appendRawEvents(id, List.of()),
          () -> events.readEvents(id), () -> events.writeRunMeta(id, Map.of()),
          () -> events.runDir(id), () -> events.eventsPath(id), () -> events.metaPath(id));
      if (id.isBlank()) assertNull(store.appendSearchEvent(id, Map.of()));
      else assertThrows(IllegalArgumentException.class, () -> store.appendSearchEvent(id, Map.of()));
      for (var operation : operations) {
        assertThrows(IllegalArgumentException.class, operation, "unsafe id: " + id);
        assertFalse(Files.exists(root), "no store root created by refusal");
        assertEquals("keep", Files.readString(next));
        assertEquals("keep", Files.readString(above));
      }
    }
  }

  @Test
  void appendSearchEventWithoutConversationCreatesNoFiles() {
    Path root = tmp.resolve("runs");
    var store = new AgentRunStore(root);
    for (String id : java.util.Arrays.asList(null, "", " \t")) {
      assertNull(assertDoesNotThrow(() -> store.appendSearchEvent(id, Map.of("query", "hello"))));
      assertFalse(Files.exists(root));
    }
  }

  @Test
  void refusesWindowsAliasesBeforeRunOrEventAccess() {
    Path root = tmp.resolve("runs");
    var store = new AgentRunStore(root);
    var events = store.runEvents();
    for (String id : List.of("...", "a.", "a ", "CON", "con.txt", "PrN", "AUX", "NUL", "nul.txt",
        "COM1", "com9.ext", "LPT1", "lpt9.ext")) {
      assertAll(id,
          () -> assertThrows(IllegalArgumentException.class, () -> store.readSnapshot(id)),
          () -> assertThrows(IllegalArgumentException.class, () -> store.deleteConversationRuns(id)),
          () -> assertThrows(IllegalArgumentException.class, () -> store.appendSearchEvent(id, Map.of())),
          () -> assertThrows(IllegalArgumentException.class, () -> events.readEvents(id)),
          () -> assertThrows(IllegalArgumentException.class, () -> events.writeRunMeta(id, Map.of())),
          () -> assertFalse(Files.exists(root)));
    }
  }

  @Test
  void preservesRunIdFormatsAndConversationJoins() {
    var store = new AgentRunStore(tmp.resolve("runs"));
    for (String id : List.of("550e8400-e29b-41d4-a716-446655440000", "uc-123", "run-550e8400-e29b-41d4-a716-446655440000", "s-123", "session_1", "sess-1", "wf-1", io.justsearch.agent.api.conversation.ConversationStore.THROWAWAY_SESSION_PREFIX + "123", "旧会話 1")) {
      var request = new AgentRequest(List.of(Map.of("role", "user", "content", "hello")), List.of(), 5,
          List.of(), null, null, id);
      store.startRun(id, request, request.messages(), 100);
      store.appendEvent(id, new AgentEvent.SessionStarted(id));
      assertEquals(id, store.readSnapshot(id).get("sessionId"));
      assertEquals(1, store.readEvents(id).size());
      assertEquals(List.of(id), store.listRunIdsByConversation(id));
      store.markBackground(id);
      assertEquals(true, store.readSnapshot(id).get("background"));
      store.updateCheckpoint(id, "READY_FOR_LLM", request.messages(), 0, 0, 0, "");
      var reconstructed = new java.util.ArrayList<AgentRequest>();
      var queries = new AgentRunQueryService(store, null, null, null,
          (sink, message, code, errorClass, retry, attempt) -> fail(message),
          (resumed, sink, context) -> reconstructed.add(resumed));
      queries.resumeSession(id, ignored -> {}, null);
      queries.forkSession(id, "edited", ignored -> {}, null);
      assertEquals(2, reconstructed.size());
      assertEquals("hello", reconstructed.get(0).messages().getFirst().get("content"));
      assertEquals("edited", reconstructed.get(1).messages().getLast().get("content"));
      assertEquals(1, store.deleteConversationRuns(id));
      assertNull(store.readSnapshot(id));
    }
  }
}
