// SPDX-License-Identifier: Apache-2.0
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ChatControllerDeleteTreeCaseTest {
  @Test
  @SuppressWarnings("unchecked")
  void completeChildReadUsesTheStoresCaseInsensitiveParentComparison() {
    try (var executors = new TestEngineExecutors()) {
      for (var pair : List.of(List.of("Root", "rOoT"), List.of("id-i", "ID-İ"),
          List.of("id-σ", "ID-ς"), List.of("id-ß", "ID-ẞ"))) {
        String root = pair.getFirst();
        var rows = List.of(
            new ConversationStore.SessionSummary(root, "core.free-chat", 1, 2, 0, "", null, null, null, null, null, null),
            new ConversationStore.SessionSummary("child", "core.free-chat", 1, 2, 0, "", pair.getLast(), "a", null, null, null, null));
        var store = mock(ConversationStore.class);
        when(store.listSessions(any(), anyInt())).thenReturn(rows);
        var controller = new ChatController(executors, null, new SseWriter(null), null, store,
            OnlineAiService::unavailable, () -> null);
        var ctx = mock(Context.class);
        when(ctx.queryParam(anyString())).thenAnswer(inv -> "childrenOf".equals(inv.getArgument(0)) ? root : null);
        var body = new AtomicReference<Map<String, Object>>();
        doAnswer(inv -> { body.set(inv.getArgument(0));return ctx; }).when(ctx).json(any(Object.class));
        controller.handleListSessions(ctx);
        var sessions = (List<Map<String, Object>>) body.get().get("sessions");
        assertEquals(List.of(root, "child"), sessions.stream().map(r -> r.get("sessionId")).toList());
        assertEquals(pair.getLast(), sessions.getLast().get("parentSessionId"));
        assertEquals(root, body.get().get("childrenOf"));
      }
    }
  }
}
