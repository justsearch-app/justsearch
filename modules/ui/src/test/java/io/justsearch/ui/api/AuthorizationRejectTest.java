/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.intent.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class AuthorizationRejectTest {
  private static final String WINDOW = "reject-test-window";
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T18:00:00Z"));
  private final Clock clock = new Clock() {
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now.get(); }
  };
  private final HttpClient client = HttpClient.newHttpClient();
  private final PendingAuthorizationStore pending = new PendingAuthorizationStore(clock, Duration.ofMinutes(5));
  private final ConsentCapsuleService capsules = new ConsentCapsuleService(clock, Duration.ofMinutes(5));
  private final OperationDispatcher dispatcher = mock(OperationDispatcher.class);
  private final AuthorizationOutcomeStore outcomes = new AuthorizationOutcomeStore();
  private final PendingAuthorizationChangeRegistry changes = new PendingAuthorizationChangeRegistry();
  private final List<PendingAuthorizationEvent> events = new CopyOnWriteArrayList<>();
  private Javalin app;
  private io.justsearch.app.observability.stream.SseStreamChannel.Subscription subscription;

  @BeforeEach
  void start() throws Exception {
    // Baseline compatibility only: missing production wiring remains missing, never simulated.
    try {
      PendingAuthorizationStore.class.getMethod("setLifecycleSinks", AuthorizationOutcomeStore.class,
          PendingAuthorizationChangeRegistry.class).invoke(pending, outcomes, changes);
    } catch (NoSuchMethodException beforeReject) {
      // The baseline has no lifecycle sinks; the tests then fail on the missing behaviour.
    }
    subscription = changes.subscribeTyped(events::add);
    var controller = new AuthorizationController(capsules, pending, null, dispatcher, List.of());
    app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    WindowAuthority.install(app, WINDOW);
    WindowAuthority.installMutationGuard(app);
    app.post("/api/authorizations/approve", controller::handleApprove);
    app.get("/api/authorizations/pending/{id}", controller::handlePeekPending);
    try {
      var reject = AuthorizationController.class.getMethod("handleReject", Context.class);
      app.post("/api/authorizations/reject", ctx -> reject.invoke(controller, ctx));
    } catch (NoSuchMethodException beforeReject) {
      // The baseline has no reject route; the tests then fail on the missing route.
    }
    app.start(0);
  }

  @AfterEach
  void stop() throws Exception {
    if (app != null) app.stop();
    if (subscription != null) subscription.unsubscribe();
    changes.channel().close();
  }

  private String create() {
    var context = TestRequestContexts.mcp("reject-test");
    return pending.create("core.ingest-files", "{\"paths\":[\"/test/exact-target\"]}",
        SourceTier.UNTRUSTED, RiskTier.MEDIUM, GateBehavior.INLINE_CONFIRM, "Requires confirmation",
        context, TestRequestContexts.provenance(context, ExecutorTag.AGENT));
  }
  private HttpResponse<String> post(String route, String id, String credential) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/authorizations/" + route))
        .header("Content-Type", "application/json");
    if (credential != null) request.header(WindowAuthority.HEADER, credential);
    return client.send(request.POST(HttpRequest.BodyPublishers.ofString("{\"pendingId\":\"" + id + "\"}"))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectionIsTerminalIdempotentAndVisibleToEverySubscriber() throws Exception {
    String id = create();
    assertEquals(200, post("reject", id, WINDOW).statusCode());
    assertTrue(pending.peek(id).isEmpty());
    for (String action : List.of("reject", "approve")) {
      var replay = post(action, id, WINDOW);
      assertEquals(410, replay.statusCode());
      assertTrue(replay.body().contains("denied by the user"), replay.body());
      assertFalse(replay.body().contains("capsule"));
    }
    var peek = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
        + "/api/authorizations/pending/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, peek.statusCode());
    assertTrue(peek.body().contains("denied by the user"));
    assertEquals(1, events.size());
    assertEquals(id, events.getFirst().pendingId());
    assertEquals("DENIED", events.getFirst().getClass().getMethod("disposition").invoke(events.getFirst()));
    assertEquals(List.of(AuthorizationDisposition.DENIED),
        outcomes.recent().stream().map(AuthorizationOutcomeEntry::disposition).toList());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void tokenOnlyAndWrongWindowCannotCancelOrConsume() throws Exception {
    String id = create();
    for (String credential : Arrays.asList(null, "wrong-window")) {
      assertEquals(403, post("reject", id, credential).statusCode());
      assertTrue(pending.peek(id).isPresent());
    }
    assertEquals(200, post("reject", id, WINDOW).statusCode());
  }

  @Test
  void unknownExpiredUsedAndRestartedIdsAlwaysFailClosed() throws Exception {
    assertEquals(410, post("reject", "pa-from-previous-engine", WINDOW).statusCode());
    String used = create();
    assertEquals(200, post("approve", used, WINDOW).statusCode());
    assertEquals(410, post("reject", used, WINDOW).statusCode());
    String expired = create();
    now.updateAndGet(value -> value.plus(Duration.ofMinutes(6)));
    assertEquals(410, post("reject", expired, WINDOW).statusCode());
    assertEquals(410, post("approve", expired, WINDOW).statusCode());
    assertTrue(pending.peek(expired).isEmpty());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void concurrentApproveAndRejectHaveExactlyOneWinner() throws Exception {
    String id = create();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var allow = pool.submit(() -> { start.await(); return post("approve", id, WINDOW).statusCode(); });
      var deny = pool.submit(() -> { start.await(); return post("reject", id, WINDOW).statusCode(); });
      start.countDown();
      assertEquals(Set.of(200, 410), Set.of(allow.get(10, TimeUnit.SECONDS), deny.get(10, TimeUnit.SECONDS)));
    }
    assertEquals(410, post("approve", id, WINDOW).statusCode());
    assertTrue(pending.peek(id).isEmpty());
    assertEquals(1, events.size());
  }

  @Test
  void rejectRacingExpiryNeverLeavesExecutionAuthority() throws Exception {
    String id = create();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var deny = pool.submit(() -> { start.await(); return post("reject", id, WINDOW).statusCode(); });
      var expire = pool.submit(() -> {
        start.await(); now.updateAndGet(value -> value.plus(Duration.ofMinutes(6)));
        return pending.peek(id);
      });
      start.countDown();
      assertTrue(Set.of(200, 410).contains(deny.get(10, TimeUnit.SECONDS)));
      assertTrue(expire.get(10, TimeUnit.SECONDS).isEmpty());
    }
    assertEquals(410, post("approve", id, WINDOW).statusCode());
    assertEquals(1, events.size());
    verifyNoInteractions(dispatcher);
  }
}
