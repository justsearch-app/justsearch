/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * WP6.2 S8.2 (review 4) — POST /api/chat/agent/steer takes an optional {@code clientRequestId} the
 * window generates per send and hands it to the run with the direction, which echoes it in that
 * direction's queued report. It is validated at this boundary; a body without one (an older window)
 * is accepted as before.
 */
final class AgentControllerSteerTest {

  /** Records every direction the controller hands to the agent service, with its request id. */
  private static final class SteerRecordingService implements AgentService {
    final List<List<String>> directions = new ArrayList<>();

    @Override
    public boolean injectSteeringDirective(String sessionId, String text) {
      directions.add(Arrays.asList(sessionId, text, null));
      return true;
    }

    @Override
    public boolean injectSteeringDirective(String sessionId, String text, String clientRequestId) {
      directions.add(Arrays.asList(sessionId, text, clientRequestId));
      return true;
    }

    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {}

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    @Override
    public List<Operation> offeredOperations() {
      return List.of();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  private static Context post(String body) {
    var ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.path()).thenReturn("/api/chat/agent/steer");
    return ctx;
  }

  private static Map<?, ?> response(Context ctx) {
    var payload = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(payload.capture());
    return assertInstanceOf(Map.class, payload.getValue());
  }

  private static AgentController controller(AgentService service) {
    return new AgentController(new io.justsearch.core.execution.TestEngineExecutors(), () -> service,
        null, null, null);
  }

  @Test
  void handsTheRequestIdToTheRunWithTheDirection() {
    var service = new SteerRecordingService();
    var ctx = post("{\"sessionId\":\"s1\",\"text\":\"Answer in French.\","
        + "\"clientRequestId\":\"3f2b8c1e-9a4d-4c7e-8b1a-2d5e6f708192\"}");
    controller(service).handleSteeringDirective(ctx);

    verify(ctx, never()).status(anyInt());
    assertEquals(Map.of("status", "injected"), response(ctx));
    assertEquals(List.of(List.of("s1", "Answer in French.", "3f2b8c1e-9a4d-4c7e-8b1a-2d5e6f708192")),
        service.directions);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"", ",\"clientRequestId\":null"})
  void acceptsADirectionWithoutARequestId(String idField) {
    var service = new SteerRecordingService();
    var ctx = post("{\"sessionId\":\"s1\",\"text\":\"Be brief.\"" + idField + "}");
    controller(service).handleSteeringDirective(ctx);

    verify(ctx, never()).status(anyInt());
    assertEquals(Map.of("status", "injected"), response(ctx));
    assertEquals(List.of(Arrays.asList("s1", "Be brief.", null)), service.directions);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"\"\"", "\"has space\"", "\"<script>\"", "42", "{\"a\":1}",
      "\"0123456789012345678901234567890123456789012345678901234567890123x\""})
  void refusesAnInvalidRequestIdWithoutQueueingTheDirection(String requestId) {
    var service = new SteerRecordingService();
    var ctx = post("{\"sessionId\":\"s1\",\"text\":\"Be brief.\",\"clientRequestId\":" + requestId + "}");
    controller(service).handleSteeringDirective(ctx);

    assertEquals(List.of(), service.directions, "an invalid request id queues nothing");
    verify(ctx).status(400);
    assertTrue(String.valueOf(response(ctx).get("error")).contains("clientRequestId"), response(ctx).toString());
  }
}
