/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PresenceRunOperationKeyTest {
  @TempDir Path temp;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void publicKeySurvivesSchedulingAndTerminalRetryWithoutAnotherRun() throws Exception {
    try (var fixture = new Fixture(temp)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      String body = JSON.writeValueAsString(Map.of("prompt", "find invoices", "conversationId", "conversation", "operationKey", key));
      var first = invoke(fixture.controller, body);
      assertEquals(200, first.status());
      assertEquals(key, first.body().path("operationKey").asText());
      assertTrue(first.body().path("scheduled").asBoolean());
      var observed = fixture.store.find(key).orElseThrow();
      var completed = fixture.attempts.lookup(new OperationAttemptRunner.Request(key,
          observed.descriptor(), observed.context(), null)).orElseThrow()
          .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(OperationState.COMPLETE, completed.state());
      var replay = invoke(fixture.controller, body);
      assertEquals(200, replay.status());
      assertEquals("COMPLETE", replay.body().path("state").asText());
      assertEquals("SUCCESS", replay.body().path("outcomeCode").asText());
      assertTrue(replay.body().path("replayed").asBoolean());
      assertFalse(replay.body().path("scheduled").asBoolean());
      assertEquals(key, replay.body().path("operationKey").asText());
      var changed = invoke(fixture.controller, body.replace("find invoices", "find receipts"));
      assertEquals(409, changed.status());
      assertEquals("OPERATION_KEY_REUSED", changed.body().path("errorCode").asText());
      verify(fixture.agent, times(1)).runAgent(any(), any(), eq(true), any());
    }
  }

  @Test
  void missingKeyIsGeneratedAndCallerCannotChooseAuthority() throws Exception {
    try (var fixture = new Fixture(temp)) {
      var response = invoke(fixture.controller, JSON.writeValueAsString(Map.of(
          "prompt", "work", "survival", "DURABLE", "transport", "BUTTON",
          "sourceTier", "SYSTEM", "clientId", "forged-client")));
      assertEquals(200, response.status());
      assertTrue(response.body().path("scheduled").asBoolean());
      String key = response.body().path("operationKey").asText();
      assertTrue(key.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
      var record = fixture.store.find(key).orElseThrow();
      assertEquals("test-webview", record.context().clientId());
      assertEquals(EngineContext.Survival.INTERACTIVE, record.context().survival());
      assertEquals("AGENT_LOOP", record.context().transport());
      fixture.attempts.lookup(new OperationAttemptRunner.Request(key,
          record.descriptor(), record.context(), null)).orElseThrow()
          .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      verify(fixture.agent, times(1)).runAgent(any(), any(), eq(true), any());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "number", "invalid", "empty"})
  void invalidKeyRefusesBeforeAnyRun(String keyType) throws Exception {
    try (var fixture = new Fixture(temp)) {
      var body = JSON.createObjectNode().put("prompt", "work");
      switch (keyType) {
        case "null" -> body.putNull("operationKey");
        case "number" -> body.put("operationKey", 123);
        case "invalid" -> body.put("operationKey", "not-a-key");
        case "empty" -> body.put("operationKey", "");
        default -> throw new IllegalArgumentException(keyType);
      }
      var response = invoke(fixture.controller, body.toString());
      assertEquals(400, response.status());
      assertEquals("OPERATION_KEY_INVALID", response.body().path("errorCode").asText());
      verify(fixture.agent, never()).runAgent(any(), any(), anyBoolean(), any());
      assertTrue(fixture.store.openRecords().isEmpty());
    }
  }

  @Test
  void acceptanceStorageFailureCannotLookLikeScheduledSuccess() throws Exception {
    try (var fixture = new Fixture(temp)) {
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER refuse_accept BEFORE INSERT ON operations "
            + "BEGIN SELECT RAISE(ABORT, 'unavailable'); END");
      }
      var response = invoke(fixture.controller, JSON.writeValueAsString(Map.of("prompt", "work")));
      assertEquals(500, response.status());
      assertEquals("OPERATION_STORAGE_FAILED", response.body().path("errorCode").asText());
      verify(fixture.agent, never()).runAgent(any(), any(), anyBoolean(), any());
    }
  }

  private static Response invoke(InteractionThreadController controller, String body) {
    Context ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    var context = io.justsearch.app.services.intent.EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "test-webview",
        Optional.empty(), Optional.empty(), io.justsearch.agent.api.registry.TransportTag.BUTTON,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(context);
    AtomicInteger status = new AtomicInteger(200);
    when(ctx.status(anyInt())).thenAnswer(call -> { status.set(call.getArgument(0)); return ctx; });
    AtomicReference<JsonNode> response = new AtomicReference<>();
    doAnswer(call -> { response.set(JSON.valueToTree(call.getArgument(0))); return ctx; }).when(ctx).json(any());
    controller.handlePresenceRun(ctx);
    return new Response(status.get(), response.get());
  }

  private record Response(int status, JsonNode body) {}

  private static final class Fixture implements AutoCloseable {
    private final SqliteOperationStore store;
    private final OperationAttemptRunner attempts;
    private final DefaultEngineExecutorRegistry registry = new DefaultEngineExecutorRegistry();
    private final AgentService agent = mock(AgentService.class);
    private final InteractionThreadController controller;

    private Fixture(Path temp) throws Exception {
      store = new SqliteOperationStore(temp.resolve("operations.db"));
      attempts = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of(OperationKind.SCHEDULED_RUN));
      doAnswer(call -> {
        Consumer<AgentEvent> events = call.getArgument(1);
        String id = "scheduled-test";
        events.accept(new AgentEvent.SessionStarted(id));
        return null;
      }).when(agent).runAgent(any(), any(), eq(true), any());
      when(agent.sessionSnapshot("scheduled-test")).thenReturn(Map.of("state", "DONE"));
      controller = new InteractionThreadController(attempts, mock(ConversationStore.class), agent, registry);
    }

    @Override public void close() throws Exception {
      controller.shutdown();
      registry.close();
      store.close();
    }
  }
}
