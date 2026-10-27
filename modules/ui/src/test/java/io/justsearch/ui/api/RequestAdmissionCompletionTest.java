/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual Javalin user futures and loopback HTTP, with the production admission filters. */
@Timeout(20)
final class RequestAdmissionCompletionTest {
  @TempDir Path directory;
  @ParameterizedTest(name = "exceptionalUserFutureReleasesOnlyItsHttpWorkAndMutationLease({0})")
  @ValueSource(strings = {"failure", "cancel", "committed-failure"})
  void exceptionalUserFutureReleasesOnlyItsHttpWorkAndMutationLease(String outcome) throws Exception {
    try (var fixture = new Fixture();
        var independent = fixture.admission.admit(TestRequestContexts.internal(), false)) {
      int baseline = fixture.admission.activeWorkCount();
      var response = fixture.send("/api/test/future", true, outcome.equals("committed-failure"));
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      assertEquals(baseline + 1, fixture.admission.activeWorkCount());
      assertEquals(1, fixture.leases.snapshot().activeLeases().size());
      assertEquals(0, fixture.after.get(), "the real user future has not completed");
      if (outcome.equals("cancel")) fixture.answer.cancel(false);
      else fixture.answer.completeExceptionally(new IllegalStateException("controlled user-future failure"));
      var actual = response.get(5, TimeUnit.SECONDS);
      assertEquals(outcome.equals("committed-failure") ? 200 : 500, actual.statusCode());
      await(() -> fixture.admission.activeWorkCount() == baseline, "only failed request work releases");
      await(() -> fixture.leases.snapshot().activeLeases().isEmpty(), "mutation lease releases at real terminal completion");
      assertTrue(independent.cancellationReason().isEmpty());
      try (var retained = independent.retain()) {
        assertEquals(independent.context().workId(), retained.context().workId());
      }
      assertEquals(200, fixture.ordinary().statusCode(), "same running server admits a later request");
      await(() -> fixture.admission.activeWorkCount() == baseline, "later finite request cleans up its own reference");
    }
  }

  @ParameterizedTest(name = "failedOrCancelledStatusUserFutureReleasesOnlyItsReservation({0})")
  @ValueSource(booleans = {false, true})
  void failedOrCancelledStatusUserFutureReleasesOnlyItsReservation(boolean cancel) throws Exception {
    try (var fixture = new Fixture();
        var shared = fixture.admission.reserveStatusAdmission(TestRequestContexts.internal())) {
      int baseline = fixture.admission.activeWorkCount();
      var response = fixture.send("/api/status?fresh=true", false, false);
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      assertEquals(baseline + 1, fixture.admission.activeWorkCount());
      assertTrue(fixture.leases.snapshot().activeLeases().isEmpty());
      if (cancel) fixture.answer.cancel(false);
      else fixture.answer.completeExceptionally(new IllegalStateException("controlled status serialization failure"));
      assertEquals(500, response.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> fixture.admission.activeWorkCount() == baseline, "only the failed status reservation releases");
      assertEquals(0, fixture.published.get(), "failure must not publish a false status value");
      // The separately held lane must still occupy exactly its original reservation.
      assertEquals(1, baseline);
      assertEquals(200, fixture.ordinary().statusCode());
      await(() -> fixture.admission.activeWorkCount() == baseline, "ordinary request leaves the shared lane intact");
      shared.close();
      await(() -> fixture.admission.activeWorkCount() == baseline - 1,
          "only the shared reservation's actual owner releases its remaining slot");
    }
  }

  @ParameterizedTest(name = "exceptionalLeaseProjectionKeepsExactOpRemovalUpdatedAtAndHttpStatusOutcome({0})")
  @ValueSource(strings = {"failure", "committed-failure"})
  void exceptionalLeaseProjectionKeepsExactOpRemovalUpdatedAtAndHttpStatusOutcome(String outcome) throws Exception {
    assertLeaseProjection(outcome);
  }

  @Test
  void successfulLeaseProjectionKeepsExactOpRemovalUpdatedAtAndHttpStatusOutcome() throws Exception {
    assertLeaseProjection("success");
  }

  private void assertLeaseProjection(String outcome) throws Exception {
    Path file = directory.resolve("op-leases.json");
    var constructor = OperationLeaseServiceImpl.class.getDeclaredConstructor(Path.class);
    constructor.setAccessible(true); // Existing package-private explicit-file fixture seam; no production configuration.
    var leases = constructor.newInstance(file);
    var json = new tools.jackson.databind.ObjectMapper();
    try (var unrelated = leases.register("fixture.independent", io.justsearch.app.api.OpCriticality.MUST_COMPLETE,
            60, java.util.Map.of());
        var fixture = new Fixture(0, leases)) {
      var response = fixture.send("/api/test/future", true, outcome.equals("committed-failure"));
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      var before = json.readTree(Files.readString(file));
      var currentId = leases.snapshot().activeLeases().stream()
          .filter(lease -> lease.opClass().equals("api.mutation")).findFirst().orElseThrow().opId();
      assertEquals(2, before.path("opLeases").size());
      if (outcome.equals("success")) fixture.answer.complete("done");
      else fixture.answer.completeExceptionally(new IllegalStateException("controlled lease response failure"));
      assertEquals(outcome.equals("failure") ? 500 : 200, response.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> leases.snapshot().activeLeases().size() == 1, "only exact HTTP mutation lease leaves the active map");
      await(() -> {
        try { return json.readTree(Files.readString(file)).path("opLeases").size() == 1; }
        catch (java.io.IOException failure) { return false; }
      }, "actual atomic projection finishes");
      var after = json.readTree(Files.readString(file));
      assertEquals(unrelated.opId(), after.path("opLeases").get(0).path("opId").asText());
      assertNotEquals(currentId, after.path("opLeases").get(0).path("opId").asText());
      assertNotEquals(before.path("updatedAt").asText(), after.path("updatedAt").asText());
      assertEquals(outcome.equals("success") ? "SUCCESS" : "FAILURE", after.path("lastReleaseOutcome").asText(),
          "wire200 can coexist with current servlet500; the existing servlet-status rule is not producer outcome");
    }
  }

  @Test
  void synchronousCommittedWire200PreservesInternal500LeaseFailure() throws Exception {
    Path file = directory.resolve("synchronous-op-leases.json");
    var constructor = OperationLeaseServiceImpl.class.getDeclaredConstructor(Path.class);
    constructor.setAccessible(true);
    var leases = constructor.newInstance(file);
    var json = new tools.jackson.databind.ObjectMapper();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var afterObserved = new CountDownLatch(1);
    var internalStatus = new AtomicInteger(-1);
    var committed = new java.util.concurrent.atomic.AtomicBoolean();
    String prefix = "committed prefix";
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    var events = Executors.newSingleThreadExecutor();
    var client = HttpClient.newHttpClient();
    try (var unrelated = leases.register("fixture.independent", io.justsearch.app.api.OpCriticality.MUST_COMPLETE,
        60, java.util.Map.of())) {
      app.exception(Exception.class, (failure, ctx) -> {
        var code = ApiErrorHandler.resolve(failure);
        ctx.status(ApiErrorHandler.httpStatusFor(code)).json(java.util.Map.of("errorCode", code.name()));
      });
      app.after(ctx -> {
        internalStatus.set(ctx.statusCode());
        afterObserved.countDown();
      });
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases).install(app);
      app.post("/api/test/committed-sync", ctx -> {
        ctx.res().getWriter().write(prefix);
        ctx.res().flushBuffer();
        committed.set(ctx.res().isCommitted());
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("synchronous committed gate not released");
        throw new IllegalStateException("controlled synchronous error after committed response");
      });
      app.start("127.0.0.1", 0);
      var response = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
              + "/api/test/committed-sync")).timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream())
          .get(5, TimeUnit.SECONDS);
      try (var body = response.body()) {
        assertEquals(200, response.statusCode(), "the real client has the committed wire status");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(committed.get());
        var before = json.readTree(Files.readString(file));
        String httpId = leases.snapshot().activeLeases().stream()
            .filter(lease -> lease.opClass().equals("api.mutation")).findFirst().orElseThrow().opId();
        assertEquals(2, before.path("opLeases").size());
        release.countDown();
        assertTrue(afterObserved.await(5, TimeUnit.SECONDS), "synchronous error must reach the actual AFTER phase");
        assertEquals(500, internalStatus.get(), "error projection changes current servlet status after commitment");
        assertTrue(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).startsWith(prefix));
        await(() -> leases.snapshot().activeLeases().size() == 1, "only the HTTP lease retires");
        await(() -> {
          try { return json.readTree(Files.readString(file)).path("opLeases").size() == 1; }
          catch (java.io.IOException failure) { return false; }
        }, "real atomic lease projection completes");
        var after = json.readTree(Files.readString(file));
        assertEquals(unrelated.opId(), after.path("opLeases").get(0).path("opId").asText());
        assertNotEquals(httpId, after.path("opLeases").get(0).path("opId").asText());
        assertNotEquals(before.path("updatedAt").asText(), after.path("updatedAt").asText());
        assertEquals("FAILURE", after.path("lastReleaseOutcome").asText(),
            "existing current-servlet500 rule, independent of wire200 or producer outcome");
        System.out.println("Synchronous preservation wire=" + response.statusCode() + " internal=" + internalStatus.get()
            + " retiredHttp=" + httpId + " retainedUnrelated=" + unrelated.opId() + " outcome=FAILURE");
      }
    } finally {
      release.countDown();
      app.stop();
      client.close();
      events.shutdownNow();
    }
  }

  @Test
  void configuredTestServerTimeoutReleasesRequestResourcesAtActualCompletion() throws Exception {
    try (var fixture = new Fixture(500)) {
      var response = fixture.send("/api/test/future", true, false);
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.admission.activeWorkCount());
      assertEquals(1, fixture.leases.snapshot().activeLeases().size());
      assertEquals(500, response.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> fixture.admission.activeWorkCount() == 0, "actual configured async timeout releases work");
      await(() -> fixture.leases.snapshot().activeLeases().isEmpty(), "actual configured async timeout releases mutation lease");
      assertEquals(200, fixture.ordinary().statusCode());
    }
  }

  @Test
  void nativeMcpAdmissionInstalledInsideHandlerIsReleasedOnExceptionalFuture() throws Exception {
    try (var fixture = new Fixture()) {
      var request = HttpRequest.newBuilder(fixture.uri("/mcp"))
          .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":\"late-mcp\",\"method\":\"ping\"}"))
          .build();
      var response = fixture.client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.admission.activeWorkCount());
      assertEquals(1, fixture.leases.snapshot().activeLeases().size());
      fixture.answer.completeExceptionally(new IllegalStateException("controlled late MCP future failure"));
      assertEquals(500, response.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> fixture.admission.activeWorkCount() == 0, "late native MCP work is captured at terminal completion");
      await(() -> fixture.leases.snapshot().activeLeases().isEmpty(), "native MCP HTTP mutation lease releases");
      assertEquals(200, fixture.ordinary().statusCode());
    }
  }

  @Test
  void successfulAsyncAndOrdinaryFiniteResponsesReleaseTheirOwnReferences() throws Exception {
    try (var fixture = new Fixture()) {
      var response = fixture.send("/api/test/future", true, false);
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.admission.activeWorkCount());
      fixture.answer.complete("real answer");
      var actual = response.get(5, TimeUnit.SECONDS);
      assertEquals(200, actual.statusCode());
      assertEquals("real answer", actual.body());
      await(() -> fixture.admission.activeWorkCount() == 0, "successful future releases");
      await(() -> fixture.leases.snapshot().activeLeases().isEmpty(), "successful mutation lease releases");
      assertEquals(1, fixture.after.get(), "ordinary after queue executes once");
      assertEquals(200, fixture.ordinary().statusCode());
      await(() -> fixture.admission.activeWorkCount() == 0, "ordinary finite request releases");
    }
  }

  @Test
  void negativeCleanupAcknowledgementRefusesNewWorkButExistingCleanupAndProducerRemainOwned() throws Exception {
    try (var fixture = new Fixture(0, org.mockito.Mockito.spy(OperationLeaseServiceImpl.processLocal()));
        var producer = fixture.admission.admit(TestRequestContexts.internal(), false)) {
      var unknown = HttpRequest.newBuilder(fixture.uri("/not-a-route-stream-characterization"))
          .timeout(Duration.ofSeconds(5)).GET().build();
      assertEquals(404, fixture.client.send(unknown, HttpResponse.BodyHandlers.ofString()).statusCode(),
          "healthy unmatched GET retains its ordinary 404");
      await(() -> fixture.admission.activeWorkCount() == 1, "healthy 404 releases its own tracking");
      var existing = fixture.send("/api/test/future", true, false);
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      var broken = fixture.client.send(HttpRequest.newBuilder(fixture.uri("/api/test/failed-close"))
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(500, broken.statusCode());
      assertEquals(2, fixture.admission.activeWorkCount(), "faulting request really closed, existing request and producer remain");
      org.mockito.Mockito.clearInvocations(fixture.admission, fixture.leases);
      for (int i = 0; i < 3; i++) {
        var refused = fixture.ordinary();
        assertEquals(500, refused.statusCode(), "negative cleanup acknowledgement blocks a new relevant request");
        assertTrue(refused.body().contains("INVALID_STATE"));
        var unknownRefused = fixture.client.send(unknown, HttpResponse.BodyHandlers.ofString());
        assertEquals(500, unknownRefused.statusCode(), "failed early tracking also refuses an unmatched GET");
        assertTrue(unknownRefused.body().contains("INVALID_STATE"));
        assertEquals(2, retainedRequestCount(fixture.app), "failed and existing LIVE records remain; refused traffic adds none");
      }
      org.mockito.Mockito.verify(fixture.admission, org.mockito.Mockito.never()).admitRequest(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
          org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyLong());
      org.mockito.Mockito.verify(fixture.leases, org.mockito.Mockito.never()).register(
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyMap());
      assertTrue(producer.cancellationReason().isEmpty());
      try (var stillLive = producer.retain()) {
        assertEquals(producer.context().workId(), stillLive.context().workId());
      }
      fixture.answer.complete("existing real response");
      assertEquals(200, existing.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> fixture.admission.activeWorkCount() == 1, "already LIVE cleanup continues after owner becomes unavailable");
      assertTrue(fixture.leases.snapshot().activeLeases().isEmpty());
      assertEquals(200, fixture.client.send(HttpRequest.newBuilder(fixture.uri("/api/health")).GET().build(),
          HttpResponse.BodyHandlers.ofString()).statusCode());
      assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(fixture.app));
    }
  }

  @Test
  void throwingReleaseDoesNotPreventOtherReleasesAndRefusesNewAcquisitions() throws Exception {
    try (var fixture = new Fixture(0, org.mockito.Mockito.spy(OperationLeaseServiceImpl.processLocal()))) {
      var response = fixture.send("/api/test/release-failure", true, false);
      assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
      assertEquals(2, fixture.admission.activeWorkCount(), "one HTTP work plus one separately installed reservation");
      fixture.answer.complete("handler succeeded; release is the controlled failure");
      assertEquals(500, response.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> fixture.admission.activeWorkCount() == 0, "reservation failure cannot strand the work reference");
      await(() -> fixture.leases.snapshot().activeLeases().isEmpty(), "reservation failure cannot strand mutation lease");
      assertTrue(fixture.releaseAttempts.get() > 0);
      org.mockito.Mockito.clearInvocations(fixture.admission, fixture.leases);
      for (int i = 0; i < 3; i++) {
        var refused = fixture.ordinary();
        assertEquals(500, refused.statusCode());
        assertTrue(refused.body().contains("INVALID_STATE"), refused.body());
      }
      var post = fixture.client.send(HttpRequest.newBuilder(fixture.uri("/api/test/future"))
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(500, post.statusCode());
      assertTrue(post.body().contains("INVALID_STATE"));
      org.mockito.Mockito.verify(fixture.admission, org.mockito.Mockito.never()).admitRequest(
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
          org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyLong());
      org.mockito.Mockito.verify(fixture.admission, org.mockito.Mockito.never()).reserveStatusAdmission(org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.verify(fixture.leases, org.mockito.Mockito.never()).register(
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyMap());
      assertEquals(0, fixture.admission.activeWorkCount());
      assertTrue(fixture.leases.snapshot().activeLeases().isEmpty());
      var health = fixture.client.send(HttpRequest.newBuilder(fixture.uri("/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, health.statusCode());
      var options = fixture.client.send(HttpRequest.newBuilder(fixture.uri("/api/test/ordinary"))
          .header("Origin", "tauri://localhost").header("Access-Control-Request-Method", "POST")
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, options.statusCode());
      assertEquals(0, fixture.admission.activeWorkCount());
      assertTrue(fixture.leases.snapshot().activeLeases().isEmpty());
      var stop = assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(fixture.app));
      assertTrue(stop.getMessage().contains("release"), stop.toString());
      assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(fixture.app),
          "retry cannot erase a failed acknowledgement with idempotent no-ops");
    }
  }

  private static int retainedRequestCount(Javalin app) throws Exception {
    // Supplementary inspection follows the real public refusal assertion; no new baseline API.
    Class<?> type = Class.forName("io.justsearch.ui.api.RequestReleaseOwner");
    Object owner = app.jettyServer().server().getBean(type);
    assertNotNull(owner);
    var monitor = type.getDeclaredField("monitor");
    var records = type.getDeclaredField("requests");
    monitor.setAccessible(true);
    records.setAccessible(true);
    synchronized (monitor.get(owner)) { return ((java.util.Set<?>) records.get(owner)).size(); }
  }

  private static void await(BooleanSupplier condition, String reason) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), reason);
  }

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission = org.mockito.Mockito.spy(new EngineAdmissionController(4, 8, 1));
    final OperationLeaseServiceImpl leases;
    final java.util.concurrent.ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final CompletableFuture<String> answer = new CompletableFuture<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicInteger after = new AtomicInteger();
    final AtomicInteger published = new AtomicInteger();
    final AtomicInteger releaseAttempts = new AtomicInteger();
    final Javalin app;

    Fixture() { this(0); }

    Fixture(long timeoutMs) { this(timeoutMs, OperationLeaseServiceImpl.processLocal()); }

    Fixture(long timeoutMs, OperationLeaseServiceImpl leases) {
      this.leases = leases;
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.http.asyncTimeout = timeoutMs;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      app.exception(Exception.class, (failure, ctx) -> {
        var code = ApiErrorHandler.resolve(failure);
        ctx.status(ApiErrorHandler.httpStatusFor(code)).json(java.util.Map.of("errorCode", code.name()));
      });
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases, admission).install(app);
      io.javalin.http.Handler future = ctx -> {
        if (ctx.path().endsWith("release-failure")) {
          EngineAdmissionService.StatusReservation reservation = admission.reserveStatusAdmission(TestRequestContexts.internal());
          ctx.attribute("status-admission-reservation", (EngineAdmissionService.StatusReservation) () -> {
            releaseAttempts.incrementAndGet();
            reservation.close();
            throw new IllegalStateException("controlled release failure after actual reservation close");
          });
        }
        if ("true".equals(ctx.queryParam("committed"))) {
          ctx.res().getWriter().write("committed prefix");
          ctx.res().flushBuffer();
        }
        ctx.future(() -> answer.thenAccept(value -> {
          published.incrementAndGet();
          ctx.result(value);
        }));
        entered.countDown();
      };
      app.post("/api/test/future", future);
      app.post("/api/test/failed-close", ctx -> {
        var actual = RequestEngineWork.get(ctx);
        var wrapped = org.mockito.Mockito.mock(io.justsearch.app.api.EngineWorkHandle.class,
            org.mockito.AdditionalAnswers.delegatesTo(actual));
        org.mockito.Mockito.doAnswer(call -> {
          actual.close();
          throw new IllegalStateException("controlled failure after real work close");
        }).when(wrapped).close();
        ctx.attribute(RequestEngineWork.ATTRIBUTE, wrapped);
        ctx.result("finite before cleanup fault");
      });
      app.post("/api/test/release-failure", future);
      app.get("/api/status", future);
      var surface = org.mockito.Mockito.mock(io.justsearch.ui.api.mcp.McpToolSurface.class);
      var protocol = new io.justsearch.ui.api.mcp.McpProtocolHandler(
          surface, java.util.List.of(), java.time.Clock.systemUTC(), admission);
      app.post("/mcp", ctx -> {
        assertNull(RequestEngineWork.get(ctx), "native MCP admission belongs to protocol parsing, not the early filter");
        protocol.handlePost(ctx);
        assertNotNull(RequestEngineWork.get(ctx), "actual native MCP protocol installed its late request owner");
        future.handle(ctx);
      });
      app.get("/api/test/ordinary", ctx -> ctx.result("ordinary"));
      app.get("/api/health", ctx -> ctx.result("health"));
      app.after(ctx -> after.incrementAndGet());
      app.start("127.0.0.1", 0);
    }

    CompletableFuture<HttpResponse<String>> send(String route, boolean post, boolean committed) {
      var request = HttpRequest.newBuilder(uri(route + (route.contains("?") ? "&" : "?") + "committed=" + committed))
          .timeout(Duration.ofSeconds(10));
      if (post) request.POST(HttpRequest.BodyPublishers.ofString("{}"));
      else request.GET();
      return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> ordinary() throws Exception {
      return client.send(HttpRequest.newBuilder(uri("/api/test/ordinary")).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String route) { return URI.create("http://127.0.0.1:" + app.port() + route); }

    @Override public void close() {
      answer.cancel(false);
      app.stop();
      client.close();
      events.shutdownNow();
    }
  }
}
