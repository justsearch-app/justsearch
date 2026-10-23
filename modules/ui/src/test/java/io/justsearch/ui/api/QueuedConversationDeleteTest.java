/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.BackgroundRunService;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueuedConversationDeleteTest {
  @TempDir Path temp;

  @Test
  void acceptedQueuedRunRefusesDeleteUntilItFinishes() throws Exception {
    try (var fixture = new Fixture(temp)) {
      fixture.queueTarget();
      var response = fixture.delete();
      assertEquals(409, response.statusCode(), response.body());
      assertTrue(response.body().contains("CONVERSATION_BUSY"), response.body());
      assertTrue(fixture.store.getSessionMeta("conversation").isPresent());
      fixture.drain();
      assertEquals(1, fixture.targetCalls.get());
      assertEquals(200, fixture.delete().statusCode());
      assertTrue(fixture.store.getSessionMeta("conversation").isEmpty());
    }
  }

  @Test
  void deferredRunRechecksExistenceBeforeEnteringAgent() throws Exception {
    try (var fixture = new Fixture(temp)) {
      fixture.queueTarget();
      // External removal models an absent conversation at dispatch, independently of refusal.
      try (var paths = Files.walk(temp.resolve("conversations"))) {
        for (Path path : paths.filter(p -> p.getFileName().toString().equals("meta.json")).toList()) {
          Files.delete(path);
        }
      }
      fixture.drain();
      assertEquals(0, fixture.targetCalls.get(), "a deferred run must not recreate an absent conversation");
    }
  }

  @Test
  void missingConversationIsRefusedBeforeAcceptance() throws Exception {
    try (var fixture = new Fixture(temp)) {
      fixture.store.deleteSession("conversation");
      var response = fixture.post("conversation");
      assertEquals(404, response.statusCode(), response.body());
      fixture.drain();
      assertEquals(0, fixture.targetCalls.get());
    }
  }

  @Test
  void delayedDuplicateAndShutdownReleaseEveryReservation() throws Exception {
    try (var fixture = new Fixture(temp)) {
      var request = new AgentRequest(List.of(Map.of("role", "user", "content", "target")),
          List.of(), 5, List.of(), null, null, "conversation");
      var key = io.justsearch.app.api.operations.OperationKeys.generate(Clock.systemUTC());
      var first = fixture.background.schedule(key, request, Duration.ofDays(1), fixture.context);
      assertTrue(fixture.background.schedule(key, request, Duration.ofDays(1), fixture.context).existing());
      assertEquals(409, fixture.delete().statusCode());
      fixture.controller.shutdown();
      assertEquals(io.justsearch.app.api.operations.OperationState.FAILED,
          first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).state());
      assertEquals(200, fixture.delete().statusCode());
      assertEquals(0, fixture.targetCalls.get());
    }
  }

  @Test
  void deferredRunRefusesAnIncompleteDeletion() throws Exception {
    try (var fixture = new Fixture(temp)) {
      fixture.queueTarget();
      fixture.store.conversationWork().markIncomplete("conversation");
      fixture.drain();
      assertEquals(0, fixture.targetCalls.get(), "a deferred run must recheck deletion state");
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final FileConversationStore store;
    private final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
    private final SqliteOperationStore operations;
    private final InteractionThreadController controller;
    private final ResourceApiModule resources;
    private final BackgroundRunService background;
    private final Javalin app;
    private final ChatController chat;
    private final HttpClient client = HttpClient.newHttpClient();
    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger targetCalls = new AtomicInteger();
    private final EngineContext context = io.justsearch.app.services.intent.EngineProvenance.context(
        EngineContext.ClientKind.WEBVIEW, "test", java.util.Optional.empty(), java.util.Optional.empty(),
        io.justsearch.agent.api.registry.TransportTag.AGENT_LOOP,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);

    Fixture(Path temp) throws Exception {
      store = new FileConversationStore(temp.resolve("conversations"));
      store.createSession("conversation", "core.agent-run");
      store.appendMessage("conversation", "core.agent-run",
          Map.of("role", "user", "content", "private question"));
      var runs = new io.justsearch.agent.AgentRunStore(temp.resolve("runs"));
      store.useConversationWork(runs.conversationWork());
      operations = new SqliteOperationStore(temp.resolve("operations.db"));
      var attempts = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(io.justsearch.agent.api.registry.OperationKind.SCHEDULED_RUN));
      var agent = mock(AgentService.class);
      when(agent.sessionSnapshot(anyString())).thenReturn(Map.of("state", "DONE"));
      doAnswer(call -> {
        AgentRequest request = call.getArgument(0);
        if ("block".equals(request.messages().get(0).get("content"))) {
          blocked.countDown();
          assertTrue(release.await(10, TimeUnit.SECONDS));
        }
        if (request.conversationId() != null) targetCalls.incrementAndGet();
        Consumer<AgentEvent> sink = call.getArgument(1);
        sink.accept(new AgentEvent.SessionStarted(java.util.UUID.randomUUID().toString()));
        return null;
      }).when(agent).runAgent(any(), any(), eq(true), any());
      // Production constructs a separate store for presence. Exercise its actual composition,
      // rather than accidentally sharing the delete route's FileConversationStore instance.
      var head = mock(io.justsearch.app.services.HeadAssembly.class, RETURNS_DEEP_STUBS);
      when(head.executors()).thenReturn(executors);
      when(head.operationAttempts()).thenReturn(attempts);
      when(head.core().agent()).thenReturn(agent);
      when(head.agentRunStore()).thenReturn(runs);
      when(head.storeCipher(any())).thenReturn(io.justsearch.agent.api.encryption.StoreCipher.disabled());
      resources = new ResourceApiModule(head, null, null, temp.resolve("index"), null);
      var controllerField = ResourceApiModule.class.getDeclaredField("interactionThreadController");
      controllerField.setAccessible(true);
      controller = (InteractionThreadController) controllerField.get(resources);
      var field = InteractionThreadController.class.getDeclaredField("backgroundRunService");
      field.setAccessible(true);
      background = (BackgroundRunService) field.get(controller);
      chat = new ChatController(executors, null, new SseWriter(null), null, store,
          io.justsearch.app.api.OnlineAiService::unavailable, () -> agent);
      app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      app.before(ctx -> ctx.attribute(RequestEngineContext.ATTRIBUTE, context));
      app.post("/presence/run", controller::handlePresenceRun);
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.start(0);
    }

    void queueTarget() throws Exception {
      background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "block"))),
          Duration.ZERO, context);
      assertTrue(blocked.await(5, TimeUnit.SECONDS));
      var response = post("conversation");
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(response.body().contains("\"scheduled\":true"), response.body());
    }

    HttpResponse<String> post(String id) throws Exception {
      return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/presence/run"))
          .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"target\",\"conversationId\":\"" + id + "\"}"))
          .build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> delete() throws Exception {
      return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/conversations/conversation"))
          .DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    void drain() throws Exception {
      release.countDown();
      background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "barrier"))),
          Duration.ZERO, context).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    public void close() throws Exception {
      release.countDown();
      app.stop();
      resources.shutdown();
      chat.shutdown();
      executors.close();
      client.close();
      operations.close();
    }
  }
}
