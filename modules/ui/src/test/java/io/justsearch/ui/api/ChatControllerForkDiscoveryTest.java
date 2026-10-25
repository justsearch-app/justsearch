// SPDX-License-Identifier: Apache-2.0
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.ConversationWork;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** WP1.4a S16.1/S16.2 and BN4; WP1.4b complete child reads and active-work observations. */
final class ChatControllerForkDiscoveryTest {
  @Test
  void discoversOnlyOneForkBeyondTheRecentCap() {
    List<ConversationStore.SessionSummary> rows = new ArrayList<>();
    for (int i = 0; i < 110; i++) rows.add(row("recent-" + i, null, null));
    rows.add(row("base", null, null));
    for (int i = 0; i < 120; i++) rows.add(row("branch-" + i, "base", "answer-1"));
    rows.add(row("other-turn", "base", "answer-2"));
    rows.add(row("grandchild", "branch-0", "answer-1"));
    rows.add(row("empty", "base", "__empty_prefix__"));

    var answer = list(rows, Map.of("parentSessionId", "base", "branchPointMessageId", "answer-1", "limit", "1"));
    assertEquals(200, answer.status());
    assertEquals(121, answer.sessions().size(), "base and ALL 120 versions, beyond both list caps");
    assertTrue(answer.sessions().stream().allMatch(r -> "base".equals(r.get("sessionId"))
        || "answer-1".equals(r.get("branchPointMessageId")) && "base".equals(r.get("parentSessionId"))));
    assertEquals(List.of("base", "empty"), list(rows,
        Map.of("parentSessionId", "base", "branchPointMessageId", "__empty_prefix__")).ids());
    assertEquals(List.of("branch-0", "grandchild"), list(rows,
        Map.of("parentSessionId", "branch-0", "branchPointMessageId", "answer-1")).ids());
  }

  @Test
  void filterReflectsCreationDeletionAndHiddenContent() {
    var rows = new ArrayList<>(List.of(row("base", null, null)));
    var query = Map.of("parentSessionId", "base", "branchPointMessageId", "point");
    assertEquals(List.of("base"), list(rows, query).ids());
    assertEquals(query, list(rows, query).fork(), "a complete answer identifies the requested fork");
    rows.add(row("child", "base", "point"));
    assertEquals(List.of("base", "child"), list(rows, query).ids());
    assertEquals("", list(rows, query).sessions().get(0).get("firstUserMessage"), "hidden preview stays hidden");
    rows.remove(1);
    assertEquals(List.of("base"), list(rows, query).ids());
  }

  @Test
  void rejectsPartialOrUnsafeForkFilters() {
    for (var query : List.of(Map.of("parentSessionId", "base"), Map.of("branchPointMessageId", "p"),
        Map.of("parentSessionId", "../escape", "branchPointMessageId", "p"),
        Map.of("parentSessionId", "base", "branchPointMessageId", " "))) {
      assertEquals(400, list(List.of(), query).status(), query.toString());
    }
  }

  @Test
  void unfilteredListKeepsDefaultLimitCapOrderAndShapeFilter() {
    List<ConversationStore.SessionSummary> rows = new ArrayList<>();
    for (int i = 0; i < 130; i++) rows.add(row("recent-" + i, null, null));
    assertEquals(20, list(rows, Map.of()).sessions().size());
    assertEquals(100, list(rows, Map.of("limit", "1000")).sessions().size());
    assertEquals(List.of("recent-0", "recent-1"), list(rows, Map.of("limit", "2")).ids());
    assertEquals(List.of(), list(rows, Map.of("shapeId", "other")).ids());
  }

  @Test
  void completeChildReadIncludesOldBaseAndAllBranchPointsBeyondRecentCap() {
    var rows = new ArrayList<ConversationStore.SessionSummary>();
    for (int n = 0; n < 110; n++) rows.add(row("recent-" + n, null, null));
    rows.add(row("root", null, null));
    rows.add(row("child", "root", "answer"));
    rows.add(row("empty", "root", "__empty_prefix__"));
    rows.add(row("grandchild", "child", "answer"));
    var answer = list(rows, Map.of("childrenOf", "root"));
    assertEquals("root", answer.childrenOf());
    assertEquals(List.of("root", "child", "empty"), answer.ids());
  }

  @Test
  void childReadReportsActiveWorkIncludingApprovalWaitOwnership() {
    var work = new ConversationWork();
    var pendingDecision = work.start("child");
    try (pendingDecision) {
      var answer = list(List.of(row("root", null, null), row("child", "root", "point")),
          Map.of("childrenOf", "root"), work);
      assertEquals(Boolean.FALSE, answer.sessions().getFirst().get("busy"));
      assertEquals(Boolean.TRUE, answer.sessions().getLast().get("busy"));
    }
    var answer = list(List.of(row("child", null, null)), Map.of("childrenOf", "child"), work);
    assertEquals(Boolean.FALSE, answer.sessions().getFirst().get("busy"));
  }

  @Test
  void unsafeChildReadIsRefusedBeforeStoreAccess() {
    assertEquals(400, list(List.of(), Map.of("childrenOf", "../escape")).status());
  }

  private static ConversationStore.SessionSummary row(String id, String parent, String point) {
    return new ConversationStore.SessionSummary(id, "core.rag-ask", 1, 2, 0, "", parent, point, null, null, null, null);
  }

  private record Answer(int status, List<Map<String, Object>> sessions, Map<String, Object> fork, String childrenOf) {
    List<String> ids() { return sessions.stream().map(r -> (String) r.get("sessionId")).toList(); }
  }

  private static Answer list(List<ConversationStore.SessionSummary> rows, Map<String, String> query) {
    return list(rows, query, new ConversationWork());
  }

  @SuppressWarnings("unchecked")
  private static Answer list(List<ConversationStore.SessionSummary> rows, Map<String, String> query,
      ConversationWork work) {
    var store = mock(ConversationStore.class);
    when(store.conversationWork()).thenReturn(work);
    when(store.listSessions(any(), anyInt())).thenAnswer(inv -> rows.stream()
        .filter(r -> inv.getArgument(0) == null || inv.getArgument(0).equals(r.shapeId()))
        .limit(inv.getArgument(1, Integer.class)).toList());
    try (var executors = new TestEngineExecutors()) {
      var controller = new ChatController(executors, null, new SseWriter(null), null, store,
          OnlineAiService::unavailable, () -> null);
      var ctx = mock(Context.class);
      when(ctx.queryParam(anyString())).thenAnswer(inv -> query.get(inv.getArgument(0)));
      var status = new AtomicInteger(200);
      var body = new AtomicReference<Map<String, Object>>(Map.of());
      when(ctx.status(anyInt())).thenAnswer(inv -> { status.set(inv.getArgument(0)); return ctx; });
      doAnswer(inv -> { body.set(inv.getArgument(0)); return ctx; }).when(ctx).json(any(Object.class));
      controller.handleListSessions(ctx);
      return new Answer(status.get(), (List<Map<String, Object>>) body.get().getOrDefault("sessions", List.of()),
          (Map<String, Object>) body.get().get("fork"), (String) body.get().get("childrenOf"));
    }
  }
}
