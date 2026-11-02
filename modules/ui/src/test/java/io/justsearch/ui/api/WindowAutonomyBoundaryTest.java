/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** S5: all HTTP shape-entry paths limit autonomy before passing a request to the engine. */
final class WindowAutonomyBoundaryTest {
  private Context request(boolean verified, String level) {
    Context ctx = mock(Context.class);
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("__window_authority_required__", true);
    attributes.put("__window_authority_verified__", verified);
    attributes.put(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser());
    when(ctx.attribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
    doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
        .when(ctx).attribute(anyString(), any());
    when(ctx.body()).thenReturn("{\"autonomyLevel\":\"" + level + "\",\"message\":\"question\"}");
    return ctx;
  }

  @Test
  void s5ChatDispatchAndRunCreationDowngradeAutoButKeepWatchAndWindowPreferences() throws Exception {
    for (Class<?> controller : new Class<?>[] {ChatController.class, RunStreamController.class}) {
      var read = controller.getDeclaredMethod("readBody", Context.class);
      read.setAccessible(true);
      for (String level : new String[] {"auto", " AUTO ", "assist", "watch"}) {
        var body = (Map<?, ?>) read.invoke(null, request(false, level));
        assertEquals(level.trim().equalsIgnoreCase("auto") ? "assist" : level, body.get("autonomyLevel"));
      }
      assertEquals("auto", ((Map<?, ?>) read.invoke(null, request(true, "auto"))).get("autonomyLevel"));
    }
  }

  @Test
  void s5LegacyAgentEntryAlsoDowngradesBeforeRunning() {
    try (var executors = new TestEngineExecutors()) {
      var agent = mock(AgentService.class);
      when(agent.isAvailable()).thenReturn(true);
      var engine = mock(ConversationEngine.class);
      var seen = new AtomicReference<Map<?, ?>>();
      doAnswer(call -> { seen.set(call.getArgument(1)); return null; })
          .when(engine).run(any(), any(), any(), any(), any());
      var controller = new AgentController(executors, () -> agent, engine, mock(AgentSseWriter.class), null);
      try {
        controller.handleRunStream(request(false, "auto"));
        assertEquals("assist", seen.get().get("autonomyLevel"));
      } finally {
        controller.shutdown();
      }
    }
  }
}
