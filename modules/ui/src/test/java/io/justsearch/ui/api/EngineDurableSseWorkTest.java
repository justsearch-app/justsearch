/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.engine.ForegroundLoadGate;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.core.execution.TestEngineExecutors;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real HTTP disconnect coverage for durable run-stream work and foreground pacing. */
@Timeout(30)
final class EngineDurableSseWorkTest {

  @Test
  void creatingClientDisconnectDetachesDurableWorkWithoutCancellingIt() throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<InputStream> response = fixture.openRun();
      assertEquals(200, response.statusCode());
      assertTrue(fixture.bodyEntered.await(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.load.inFlight());

      response.body().close();

      assertTrue(fixture.backgrounded.await(10, TimeUnit.SECONDS), "disconnect was not detected");
      assertEquals(1, fixture.backgroundTransitions.get());
      assertEquals(0, fixture.load.inFlight(), "durable work stopped pacing after detachment");
      assertFalse(fixture.bodyExited.await(0, TimeUnit.SECONDS), "the work itself must continue");
      assertTrue(fixture.observedWork.get().cancellationReason().isEmpty());
      assertEquals(EngineContext.Urgency.BACKGROUND, fixture.observedWork.get().context().urgency());
      fixture.assertCapacityOccupied();

      fixture.releaseBody.countDown();
      assertTrue(fixture.bodyExited.await(5, TimeUnit.SECONDS));
      fixture.assertCapacityReleased();
    }
  }

  @Test
  void normalServerRetirementDoesNotMasqueradeAsClientDetachment() throws Exception {
    try (var fixture = new Fixture()) {
      HttpResponse<InputStream> response = fixture.openRun();
      assertTrue(fixture.bodyEntered.await(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.load.inFlight());

      fixture.releaseBody.countDown();
      assertTrue(fixture.bodyExited.await(5, TimeUnit.SECONDS));
      try (InputStream body = response.body()) {
        body.readAllBytes();
      }

      assertEquals(0, fixture.backgroundTransitions.get());
      assertEquals(0, fixture.load.inFlight());
      fixture.assertCapacityReleased();
    }
  }

  private static final class Fixture implements AutoCloseable {
    private static final String PATH = "/api/chat/runs";
    private static final int MAX_HEARTBEATS = 1_000;

    private final EngineAdmissionController admission = new EngineAdmissionController(1, 1, 1);
    private final ForegroundLoad load = new ForegroundLoad();
    private final ForegroundLoadGate foreground = new ForegroundLoadGate(load);
    private final CountDownLatch bodyEntered = new CountDownLatch(1);
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private final CountDownLatch bodyExited = new CountDownLatch(1);
    private final CountDownLatch backgrounded = new CountDownLatch(1);
    private final AtomicInteger backgroundTransitions = new AtomicInteger();
    private final AtomicReference<EngineWorkHandle> observedWork = new AtomicReference<>();
    private final TestEngineExecutors processExecutors = new TestEngineExecutors();
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor();
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final RunStreamController runs;
    private final Javalin app;

    private Fixture() {
      ConversationEngine engine = mock(ConversationEngine.class);
      doAnswer(
              invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<SseEvent> sink = invocation.getArgument(3, Consumer.class);
                EngineContext context = invocation.getArgument(4, EngineContext.class);
                try (EngineWorkHandle work = admission.attach(context)) {
                  observedWork.set(work);
                  foreground.run(
                      work,
                      () -> {
                        // Register after the real gate installs its transition listener, so this
                        // signal observes completed gauge cleanup rather than merely its start.
                        try (var ignored = work.onBackground(() -> {
                          backgroundTransitions.incrementAndGet();
                          backgrounded.countDown();
                        })) {
                        bodyEntered.countDown();
                        try {
                          for (int beat = 0;
                              beat < MAX_HEARTBEATS
                                  && !releaseBody.await(5, TimeUnit.MILLISECONDS);
                              beat++) {
                            sink.accept(new SseEvent("fixture_heartbeat", Map.of("beat", beat)));
                          }
                          if (releaseBody.getCount() != 0) {
                            throw new AssertionError("bounded disconnect heartbeat exhausted");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError("fixture interrupted", interrupted);
                        }
                        }
                      });
                } finally {
                  bodyExited.countDown();
                }
                return null;
              })
          .when(engine)
          .run(any(), anyMap(), any(), any(), any());

      runs =
          new RunStreamController(
            processExecutors,
              new RunChannelRegistry(), new ChatController(new TestEngineExecutors(), engine, new SseWriter(null), null));
      app =
          Javalin.create(
              config -> {
                config.showJavalinBanner = false;
                config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
              });
      app.exception(
          io.javalin.http.HttpResponseException.class,
          (failure, context) -> context.status(failure.getStatus()));

      // C2 has not shipped the durable-row producer yet. This trusted fixture supplies exactly the
      // context that producer will project, before the real filter admits and owns it.
      app.before(
          PATH,
          context ->
              context.attribute(
                  RequestEngineContext.ATTRIBUTE,
                  new EngineContext(
                      EngineContext.ClientKind.WEBVIEW,
                      "durable-sse-test",
                      Optional.of("session-1"),
                      Optional.empty(),
                      "TRUSTED",
                      "BUTTON",
                      EngineContext.Survival.DURABLE,
                      EngineContext.Urgency.FOREGROUND)));
      new ApiSecurityFilters(false, null, new EventBuffer(), filterExecutor, null, admission, admission)
          .install(app);
      RunRoutes.register(app, runs, null);
      app.start("127.0.0.1", 0);
    }

    private HttpResponse<InputStream> openRun() throws Exception {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + PATH))
              .header("Accept", "text/event-stream")
              .timeout(Duration.ofSeconds(15))
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"shapeId\":\"core.test\",\"sessionId\":\"session-1\"}"))
              .build();
      return client
          .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
          .get(5, TimeUnit.SECONDS);
    }

    private void assertCapacityOccupied() {
      EngineAdmissionException refused =
          assertThrows(
              EngineAdmissionException.class,
              () -> admission.admit(TestRequestContexts.mcp("other-client"), false));
      assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refused.reason());
    }

    private void assertCapacityReleased() {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        try (EngineWorkHandle next =
            admission.admit(TestRequestContexts.mcp("other-client"), false)) {
          assertTrue(next.context().workId().isPresent());
          return;
        } catch (EngineAdmissionException stillOwned) {
          java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
      }
      throw new AssertionError("admission capacity was not released after the real request exited");
    }

    @Override
    public void close() {
      releaseBody.countDown();
      runs.shutdown();
      app.stop();
      client.close();
      filterExecutor.shutdownNow();
      processExecutors.close();
    }
  }
}
