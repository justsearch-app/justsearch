/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.services.chat.ChatServerControllerImpl;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec A step 9 on the HTTP chat entries, with the real chat controller behind them: the typed
 * refusal under GPU pressure (I7, reconciliation F8/CH1), the person's reload, "load anyway" and the
 * dev-only pressure trigger.
 */
final class ChatResidencyRoutesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final StandInActuator actuator = new StandInActuator();
  private final ChatServerControllerImpl controller =
      new ChatServerControllerImpl(executors, actuator);
  private final ChatResidencyController routes = new ChatResidencyController(() -> controller, null);

  @AfterEach
  void tearDown() {
    System.clearProperty("justsearch.eval.mode");
    controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
    executors.close();
  }

  /** The server ran, then unloaded for {@code signal}; the two-minute dwell runs. */
  private void unloadedUnder(PressureSignal signal) throws Exception {
    controller.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    controller.reportPressure(signal);
    barrier();
    assertTrue(controller.unloaded().isPresent());
  }

  /** CH7: a start under pressure was refused into UNLOADED; nothing loaded, so no dwell. */
  private void startRefusedUnder(PressureSignal signal) throws Exception {
    controller.reportPressure(signal);
    var start = controller.setActivation(
        new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START)).toCompletableFuture();
    try {
      start.get(5, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException superseded) {
      // expected: the start is not acted on
    }
    barrier();
    assertTrue(controller.unloaded().isPresent());
  }

  private void barrier() throws Exception {
    controller.route(ChatActuation.of(ChatActuation.Kind.APPLY, drained -> null));
  }

  // ==================== I7: the OpenAI-compatible proxy ====================

  @Test
  void i7ChatCompletionsWhileUnloadedUnderPressureIs503ChatUnavailableWithReasonGpuBusy()
      throws Exception {
    unloadedUnder(PressureSignal.EXTERNAL_MEMORY);
    HttpClient http = mock(HttpClient.class);
    // An unloaded server is not listening: reaching it would be a refused connection.
    when(http.send(any(), any())).thenThrow(new java.net.ConnectException("Connection refused"));
    var proxy = new OpenAiCompatController(http, () -> 8081, null);
    proxy.setResidencyGate(routes.gate());
    var exchange = new Exchange("/v1/chat/completions", "{}", null);

    proxy.handleChatCompletions(exchange.ctx);

    assertEquals(503, exchange.status.get());
    Map<String, Object> body = exchange.body();
    assertEquals("CHAT_UNAVAILABLE", body.get("errorCode"));
    assertEquals("TRANSIENT", body.get("errorClass"));
    assertEquals(true, body.get("retryable"));
    assertEquals("GPU_BUSY", body.get("reason"));
    assertEquals(false, body.get("forceAllowed"), "an agent is never offered a forced load");
    long retryAfterMs = ((Number) body.get("retryAfterMs")).longValue();
    assertTrue(retryAfterMs > 30_000 && retryAfterMs <= 120_000,
        "inside the dwell the agent is told to wait for it: " + retryAfterMs);
    assertTrue(exchange.headers.containsKey("Retry-After"));
    ContractSchemaAssertions.assertConforms(
        "I7 refusal", "api-error-response.v1.json", JSON.writeValueAsString(body));
    verify(http, never()).send(any(), any());
    assertEquals(Optional.of(StopDetail.GPU_BUSY), controller.unloaded(), "nothing loaded");
  }

  // ==================== F8/CH1: who is a person ====================

  @Test
  void aHeaderLessRunWithoutAllowLoadIsAnAgentsAndIsRefusedWithoutARun() throws Exception {
    unloadedUnder(PressureSignal.EXTERNAL_MEMORY);
    var engine = mock(ConversationEngine.class);
    var chat = new ChatController(new TestEngineExecutors(), engine, new SseWriter(null), null);
    chat.setResidencyGate(routes.gate());
    var registry = new RunChannelRegistry();
    var runs = new RunStreamController(new TestEngineExecutors(), registry, chat);
    try {
      var exchange = new Exchange("/api/chat/runs",
          "{\"shapeId\":\"core.free-chat\",\"prompt\":\"hi\",\"sessionId\":\"s1\"}", null);
      runs.handleCreate(exchange.ctx);

      assertEquals(503, exchange.status.get());
      assertEquals("CHAT_UNAVAILABLE", exchange.body().get("errorCode"));
      assertEquals("GPU_BUSY", exchange.body().get("reason"));
      assertEquals(false, exchange.body().get("forceAllowed"));
      assertEquals(0, registry.size(), "no run was spawned");
      assertEquals(Optional.of(StopDetail.GPU_BUSY), controller.unloaded());
    } finally {
      runs.shutdown();
      chat.shutdown();
    }
  }

  @Test
  void aPersonsChatUnderPressureMayBeLoadedAnywayButAnAgentClaimingAllowLoadMayNot()
      throws Exception {
    controller.setProfile(Profile.P12);
    unloadedUnder(PressureSignal.GAME);
    var gate = routes.gate();

    var person = new Exchange("/api/chat/dispatch", "", null);
    assertFalse(gate.admitChat(person.ctx, Map.of("allowLoad", true)));
    assertEquals(503, person.status.get());
    assertEquals("GAME_MODE", person.body().get("reason"));
    assertEquals(true, person.body().get("forceAllowed"), "T24: the app may offer load anyway");

    var agent = new Exchange("/api/chat/dispatch", "", mcpContext());
    assertFalse(gate.admitChat(agent.ctx, Map.of("allowLoad", true)));
    assertEquals("GAME_MODE", agent.body().get("reason"));
    assertEquals(false, agent.body().get("forceAllowed"), "allowLoad from an MCP client is ignored");
  }

  @Test
  void aPersonsChatReloadsAnUnloadedModelAndGoesOnOnceItIsReady() throws Exception {
    startRefusedUnder(PressureSignal.EXTERNAL_MEMORY);
    controller.reportPressure(PressureSignal.NONE);
    var person = new Exchange("/api/chat/dispatch", "", null);

    assertTrue(routes.gate().admitChat(person.ctx, Map.of("allowLoad", true)),
        "the person's request waits for the reload and goes on");
    assertTrue(actuator.running.get());
    assertEquals(List.of("unload:gpu_busy", "up:RESIDENCY_RELOAD"), actuator.log);
    assertEquals(Optional.empty(), controller.unloaded());
  }

  // ==================== T26: load anyway ====================

  @Test
  void t26LoadAnywayFromTheAppLoadsUnderPressure() throws Exception {
    unloadedUnder(PressureSignal.EXTERNAL_MEMORY);
    var exchange = new Exchange("/api/chat/load-anyway", "{\"allowLoad\":true}", null);

    routes.handleLoadAnyway(exchange.ctx);

    assertTrue(exchange.status.get() == 200 || exchange.status.get() == 202,
        "accepted: " + exchange.status.get());
    barrier();
    assertTrue(actuator.running.get(), "the model loads in spite of the pressure");
    assertEquals(Optional.empty(), controller.unloaded());
    assertTrue(actuator.log.contains("up:RESIDENCY_RELOAD"), actuator.log.toString());
  }

  @Test
  void f8LoadAnywayWithoutThePersonsEvidenceIsRefused() throws Exception {
    unloadedUnder(PressureSignal.EXTERNAL_MEMORY);

    var noAllowLoad = new Exchange("/api/chat/load-anyway", "{}", null);
    routes.handleLoadAnyway(noAllowLoad.ctx);
    assertEquals(403, noAllowLoad.status.get());
    assertEquals("REFUSED", noAllowLoad.body().get("reason"));
    assertEquals("AI_OFFLINE", noAllowLoad.body().get("errorCode"));

    var agent = new Exchange("/api/chat/load-anyway", "{\"allowLoad\":true}", mcpContext());
    routes.handleLoadAnyway(agent.ctx);
    assertEquals(403, agent.status.get());
    assertEquals("REFUSED", agent.body().get("reason"));
    ContractSchemaAssertions.assertConforms(
        "load-anyway refusal", "api-error-response.v1.json", JSON.writeValueAsString(agent.body()));

    barrier();
    assertFalse(actuator.running.get());
    assertEquals(Optional.of(StopDetail.GPU_BUSY), controller.unloaded());
  }

  // ==================== the dev-only pressure trigger ====================

  @Test
  void theDevPressureTriggerIsAbsentOutsideEvalMode() throws Exception {
    controller.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    System.clearProperty("justsearch.eval.mode");
    var exchange = new Exchange("/api/debug/chat-pressure", "{\"signal\":\"EXTERNAL_MEMORY\"}", null);
    routes.handleDevPressure(exchange.ctx);
    assertEquals(404, exchange.status.get());
    assertEquals(PressureSignal.NONE, controller.reportedPressure());
    assertTrue(actuator.running.get());
  }

  @Test
  void theDevPressureTriggerReportsPressureAndChatUnloads() throws Exception {
    controller.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    System.setProperty("justsearch.eval.mode", "true");
    var exchange = new Exchange("/api/debug/chat-pressure", "{\"signal\":\"external_memory\"}", null);
    routes.handleDevPressure(exchange.ctx);
    assertEquals(200, exchange.status.get());
    assertEquals("EXTERNAL_MEMORY", exchange.body().get("signal"));
    barrier();
    assertEquals(PressureSignal.EXTERNAL_MEMORY, controller.reportedPressure());
    assertEquals(Optional.of(StopDetail.GPU_BUSY), controller.unloaded());

    var invalid = new Exchange("/api/debug/chat-pressure", "{\"signal\":\"LOUD\"}", null);
    routes.handleDevPressure(invalid.ctx);
    assertEquals(400, invalid.status.get());
  }

  @Test
  void theDevTriggerTriesTheGameRuleOnANamedProfile() throws Exception {
    controller.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START))
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
    System.setProperty("justsearch.eval.mode", "true");
    var unresolved = new Exchange("/api/debug/chat-pressure", "{\"signal\":\"GAME\"}", null);
    routes.handleDevPressure(unresolved.ctx);
    barrier();
    assertEquals(200, unresolved.status.get());
    assertEquals(Optional.empty(), controller.unloaded(), "no profile yet: a game alone keeps chat");

    var p12 = new Exchange("/api/debug/chat-pressure", "{\"signal\":\"GAME\",\"profile\":\"p12\"}", null);
    routes.handleDevPressure(p12.ctx);
    barrier();
    assertEquals("P12", p12.body().get("profile"));
    assertEquals(Optional.of(StopDetail.GAME_MODE), controller.unloaded());
  }

  // ==================== helpers ====================

  private static EngineContext mcpContext() {
    return TestRequestContexts.mcp("session-1");
  }

  /** A mocked request: its body, its Engine context (null resolves from headers) and its answer. */
  private static final class Exchange {
    final Context ctx = mock(Context.class);
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<Object> json = new AtomicReference<>();
    final Map<String, String> headers = Collections.synchronizedMap(new HashMap<>());

    Exchange(String path, String body, EngineContext context) {
      when(ctx.path()).thenReturn(path);
      when(ctx.endpointHandlerPath()).thenReturn(path);
      when(ctx.method()).thenReturn(HandlerType.POST);
      when(ctx.body()).thenReturn(body);
      when(ctx.bodyAsBytes()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(context);
      when(ctx.contentType(anyString())).thenReturn(ctx);
      doAnswer(call -> {
        status.set(call.getArgument(0, Integer.class));
        return ctx;
      }).when(ctx).status(anyInt());
      doAnswer(call -> {
        json.set(call.getArgument(0));
        return ctx;
      }).when(ctx).json(any(Object.class));
      doAnswer(call -> {
        headers.put(call.getArgument(0), call.getArgument(1));
        return ctx;
      }).when(ctx).header(anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> body() {
      Object value = json.get();
      assertTrue(value instanceof Map, "a JSON object was written: " + value);
      return JSON.convertValue(value, Map.class);
    }
  }

  /** A GPU chat server stand-in with the manager's activation and residency steps. */
  private static final class StandInActuator implements ChatActuator {
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean running = new AtomicBoolean();

    @Override
    public DrainHold drain(Duration bound) {
      return () -> { };
    }

    /** Nothing generates here, so every drain policy closes admission at once. */
    @Override
    public DrainHold drain(io.justsearch.app.inference.chat.ChatDrainPolicy policy) {
      return () -> { };
    }

    @Override
    public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
      return ChatActuation.<Void>coalescing(
          activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE,
          "mode", null, null,
          drained -> {
            running.set(activation.active());
            log.add((activation.active() ? "up:" : "down:") + activation.reason());
            return null;
          });
    }

    @Override
    public ChatActuation<ResidencyUnload> residencyUnload(
        StopDetail detail, BooleanSupplier stillWanted) {
      return ChatActuation.draining(ChatActuation.Kind.RESIDENCY_UNLOAD,
          notIdle -> ResidencyUnload.BUSY,
          drained -> {
            if (!stillWanted.getAsBoolean()) return ResidencyUnload.NOT_WANTED;
            running.set(false);
            log.add("unload:" + detail.token());
            return ResidencyUnload.UNLOADED;
          });
    }

    @Override
    public boolean serverRunning() {
      return running.get();
    }

    @Override
    public boolean gpuChat() {
      return true;
    }

    @Override
    public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
      try {
        return step.run(drained);
      } finally {
        if (drained != null) drained.close();
      }
    }

    @Override
    public void terminalStop(Duration timeout) {
      running.set(false);
    }
  }
}
