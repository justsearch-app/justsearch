/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP/futures/transport boundaries. No reference to a newly introduced helper is required. */
@Timeout(40)
final class RequestReleaseTransportTest {
  @Test
  void blockedProjectionLeavesTerminalCallbackAndAnotherAfterFreeToProgress() throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var leases = spy(OperationLeaseServiceImpl.processLocal());
    var releaseEntered = new CountDownLatch(1);
    var releaseGate = new CountDownLatch(1);
    var secondTerminalReturned = new CountDownLatch(1);
    var releaseThread = new AtomicReference<Thread>();
    var attempts = new AtomicInteger();
    doAnswer(call -> {
      OperationLeaseHandle actual = (OperationLeaseHandle) call.callRealMethod();
      if (!"/api/test/held-release".equals(((java.util.Map<?, ?>) call.getArgument(3)).get("path"))) return actual;
      var wrapped = mock(OperationLeaseHandle.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      doAnswer(release -> {
        attempts.incrementAndGet();
        releaseThread.set(Thread.currentThread());
        releaseEntered.countDown();
        assertTrue(releaseGate.await(10, TimeUnit.SECONDS));
        actual.release(release.getArgument(0));
        return null;
      }).when(wrapped).release(any());
      return wrapped;
    }).when(leases).register(anyString(), any(), anyLong(), anyMap());
    var app = app();
    var client = HttpClient.newHttpClient();
    try (var events = Executors.newSingleThreadExecutor()) {
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases, admission).install(app);
      app.events(eventsConfig -> eventsConfig.serverStarting(() -> app.jettyServer().server().addBeanToAllConnectors(
          new HttpChannel.Listener() {
            @Override public void onComplete(Request request) {
              if ("/api/test/second-terminal".equals(request.getRequestURI())) secondTerminalReturned.countDown();
            }
          })));
      io.javalin.http.Handler failed = ctx -> ctx.future(() -> CompletableFuture.failedFuture(
          new IllegalStateException("controlled user-future failure")));
      app.post("/api/test/held-release", failed);
      app.get("/api/test/second-terminal", failed);
      app.get("/api/test/ordinary", ctx -> ctx.result("ordinary"));
      app.start("127.0.0.1", 0);
      assertEquals(500, send(client, app, "/api/test/held-release", true).statusCode());
      assertTrue(releaseEntered.await(5, TimeUnit.SECONDS));
      assertEquals(1, admission.activeWorkCount());
      assertEquals("http-request-release", releaseThread.get().getName());
      assertFalse(releaseThread.get().isVirtual());
      assertTrue(releaseThread.get().isDaemon());
      assertEquals(500, send(client, app, "/api/test/second-terminal", false).statusCode());
      assertTrue(secondTerminalReturned.await(5, TimeUnit.SECONDS), "actual listener after the owner's callback returned while projection remains blocked");
      assertEquals(1, releaseGate.getCount());
      assertEquals(200, send(client, app, "/api/test/ordinary", false).statusCode(), "another normal after can finish while projection is blocked");
      assertEquals(2, admission.activeWorkCount(), "both exceptional fronts stay owned until real release fanout");
      releaseGate.countDown();
      await(() -> admission.activeWorkCount() == 0 && leases.snapshot().activeLeases().isEmpty());
      assertEquals(1, attempts.get());
      LocalApiServer.stopHttpTransport(app);
    } finally {
      releaseGate.countDown();
      app.stop();
      client.close();
    }
  }

  @Test
  void registryClosureBeforeExceptionalTerminalDoesNotRetireTheReleaseOwner() throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var leases = OperationLeaseServiceImpl.processLocal();
    var answer = new CompletableFuture<Void>();
    var entered = new CountDownLatch(1);
    var app = app();
    var client = HttpClient.newHttpClient();
    try (var registry = new DefaultEngineExecutorRegistry()) {
      var owner = registry.register(new EngineExecutorSpec("test.http-release", EngineExecutorSpec.Kind.BACKGROUND,
          EngineExecutorSpec.Mode.PLATFORM, 1, 1, 1));
      var events = owner.open(Thread.ofPlatform().daemon().factory());
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases, admission).install(app);
      app.post("/api/test/held", ctx -> { ctx.future(() -> answer); entered.countDown(); });
      app.get("/api/test/ordinary", ctx -> ctx.result("ordinary"));
      app.start("127.0.0.1", 0);
      var response = client.sendAsync(request(app, "/api/test/held", true), HttpResponse.BodyHandlers.ofString());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, admission.activeWorkCount());
      registry.close();
      assertTrue(events.isShutdown());
      answer.completeExceptionally(new IllegalStateException("controlled failure after registry closure"));
      assertEquals(500, response.get(5, TimeUnit.SECONDS).statusCode());
      await(() -> admission.activeWorkCount() == 0 && leases.snapshot().activeLeases().isEmpty());
      assertEquals(200, send(client, app, "/api/test/ordinary", false).statusCode());
      LocalApiServer.stopHttpTransport(app);
    } finally {
      answer.cancel(false);
      app.stop();
      client.close();
    }
  }

  @Test
  @Timeout(60)
  void liveBodyAndStillRunningStopAttemptRemainOwnedAcrossBoundedRetry() throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var leases = OperationLeaseServiceImpl.processLocal();
    var bodyEntered = new CountDownLatch(1);
    var bodyGate = new CountDownLatch(1);
    var bodyExited = new CountDownLatch(1);
    var stopEntered = new CountDownLatch(1);
    var stopGate = new CountDownLatch(1);
    var stopCalls = new AtomicInteger();
    var app = app();
    var client = HttpClient.newHttpClient();
    Throwable primaryFailure = null;
    try (var events = Executors.newSingleThreadExecutor()) {
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases, admission).install(app);
      app.events(config -> config.serverStopping(stopCalls::incrementAndGet));
      app.jettyServer().server().addEventListener(new LifeCycle.Listener() {
        @Override public void lifeCycleStopping(LifeCycle ignored) {
          stopEntered.countDown();
          awaitIgnoringInterrupts(stopGate);
          assertTrue(Thread.interrupted(), "this recoverable fixture consumes its injected stop interruption");
        }
      });
      app.post("/api/test/live-body", ctx -> {
        // Real body ownership is retained before blocking, independently of the front/stop future.
        try (var producer = RequestEngineWork.get(ctx).retain()) {
          bodyEntered.countDown();
          awaitIgnoringInterrupts(bodyGate);
          ctx.result("body actually exited");
        } finally { bodyExited.countDown(); }
      });
      app.start("127.0.0.1", 0);
      var response = client.sendAsync(request(app, "/api/test/live-body", true), HttpResponse.BodyHandlers.ofString());
      assertTrue(bodyEntered.await(5, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(app));
      assertEquals(0, stopEntered.getCount());
      assertEquals(1, bodyExited.getCount());
      assertEquals(1, admission.activeWorkCount(), "timeout/cancellation is not actual body or producer exit");
      assertFalse(leases.snapshot().activeLeases().isEmpty());
      assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(app));
      assertEquals(1, stopCalls.get(), "retry must reuse a stop invocation that still ignores interruption");
      assertEquals(1, bodyExited.getCount());
      bodyGate.countDown();
      assertTrue(bodyExited.await(5, TimeUnit.SECONDS));
      stopGate.countDown();
      // The still-running interrupted stop may report its original failure on the first retry.
      // Keep that visible; a subsequent call can start again only after that real attempt exits.
      try {
        LocalApiServer.stopHttpTransport(app);
      } catch (IllegalStateException previousAttemptFailure) {
        System.out.println("Observed previous stop-attempt failure: " + previousAttemptFailure);
        LocalApiServer.stopHttpTransport(app);
      }
      assertTrue(app.jettyServer().server().isStopped());
      await(() -> admission.activeWorkCount() == 0 && leases.snapshot().activeLeases().isEmpty());
      assertTrue(stopCalls.get() <= 2, "only an actually exited failed attempt may be replaced");
      await(response::isDone);
    } catch (Exception | Error failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      bodyGate.countDown();
      stopGate.countDown();
      Throwable cleanupFailure = null;
      try {
        if (!app.jettyServer().server().isStopped()) app.stop();
      } catch (RuntimeException | Error failure) {
        cleanupFailure = failure;
      }
      try {
        client.close();
      } catch (RuntimeException | Error failure) {
        if (cleanupFailure == null) cleanupFailure = failure;
        else if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
      }
      if (cleanupFailure != null) {
        if (primaryFailure != null) {
          if (cleanupFailure != primaryFailure) primaryFailure.addSuppressed(cleanupFailure);
        } else if (cleanupFailure instanceof RuntimeException failure) {
          throw failure;
        } else if (cleanupFailure instanceof Error failure) {
          throw failure;
        }
      }
    }
  }

  @Test
  void genuineInterruptedTransportStopRemainsUncleanWithoutFabricatedDrain(@TempDir Path directory)
      throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var leases = OperationLeaseServiceImpl.processLocal();
    var bodyEntered = new CountDownLatch(1);
    var bodyGate = new CountDownLatch(1);
    var bodyExited = new CountDownLatch(1);
    var stopEntered = new CountDownLatch(1);
    var stopGate = new CountDownLatch(1);
    var stopCalls = new AtomicInteger();
    var app = app();
    var client = HttpClient.newHttpClient();
    Throwable primaryFailure = null;
    try (var events = Executors.newSingleThreadExecutor();
        var supervisor = Executors.newSingleThreadExecutor()) {
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, leases, admission).install(app);
      app.events(config -> config.serverStopping(stopCalls::incrementAndGet));
      app.jettyServer().server().addEventListener(new LifeCycle.Listener() {
        @Override public void lifeCycleStopping(LifeCycle ignored) {
          stopEntered.countDown();
          awaitIgnoringInterrupts(stopGate); // Intentionally restore the real injected interrupt.
        }
      });
      app.post("/api/test/interrupted-stop", ctx -> {
        try (var producer = RequestEngineWork.get(ctx).retain()) {
          bodyEntered.countDown();
          awaitIgnoringInterrupts(bodyGate);
          ctx.result("body actually exited");
        } finally { bodyExited.countDown(); }
      });
      app.start("127.0.0.1", 0);
      var response = client.sendAsync(request(app, "/api/test/interrupted-stop", true), HttpResponse.BodyHandlers.ofString());
      assertTrue(bodyEntered.await(5, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, () -> LocalApiServer.stopHttpTransport(app));
      assertEquals(0, stopEntered.getCount());
      assertEquals(1, bodyExited.getCount());
      assertEquals(1, admission.activeWorkCount());
      assertFalse(leases.snapshot().activeLeases().isEmpty());
      bodyGate.countDown();
      assertTrue(bodyExited.await(5, TimeUnit.SECONDS));
      var observed = new AtomicReference<RuntimeException>();
      var exit = new AtomicInteger(-1);
      var supervisingThread = new AtomicReference<Thread>();
      var sequence = new EngineShutdownSequence(directory, java.util.List.of(
          new EngineShutdownSequence.Step("http", reason -> {
            supervisingThread.set(Thread.currentThread());
            try { LocalApiServer.stopHttpTransport(app); }
            catch (RuntimeException failure) { observed.set(failure); throw failure; }
            return null;
          })), exit::set);
      var finished = supervisor.submit(() -> sequence.runAndExit(ShutdownRequest.Reason.QUIT));
      await(() -> {
        var thread = supervisingThread.get();
        return thread != null && thread.getState() == Thread.State.TIMED_WAITING
            && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("io.justsearch.ui.api.RequestReleaseOwner")
                    && frame.getMethodName().equals("waitUntil"));
      });
      assertEquals(1, stopCalls.get(), "the observer is waiting on the still-running original attempt");
      stopGate.countDown();
      finished.get(5, TimeUnit.SECONDS);
      var failure = assertInstanceOf(io.javalin.util.JavalinException.class, observed.get());
      assertInstanceOf(InterruptedException.class, failure.getCause());
      assertTrue(java.util.Arrays.stream(failure.getSuppressed()).anyMatch(problem ->
          problem instanceof IllegalStateException && problem.getMessage().contains("supervision budget")),
          "the original exhausted drain result is retained alongside the real transport failure");
      assertFalse(sequence.resultIfRun().clean());
      assertEquals(java.util.List.of("http"), sequence.resultIfRun().errors());
      assertEquals(1, exit.get());
      assertEquals(1, stopCalls.get(), "the still-running failed attempt was observed, not replaced");
      assertFalse(app.jettyServer().server().isStopped());
      assertTrue(app.jettyServer().server().isFailed());
      Class<?> ownerType = Class.forName("io.justsearch.ui.api.RequestReleaseOwner");
      Object releaseOwner = app.jettyServer().server().getBean(ownerType);
      assertNotNull(releaseOwner);
      var drained = ownerType.getDeclaredMethod("drained");
      drained.setAccessible(true);
      assertEquals(Boolean.FALSE, drained.invoke(releaseOwner),
          "FAILED transport is not a fabricated stop/drain acknowledgement");
      await(response::isDone);
    } catch (Exception | Error failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      bodyGate.countDown();
      stopGate.countDown();
      // The real FAILED graph/owner is retained until this isolated test worker ends.
      // Do not synthesize lifeCycleStopped or retry that partially stopped graph here.
      try { client.close(); }
      catch (RuntimeException | Error cleanup) {
        if (primaryFailure == null) throw cleanup;
        if (cleanup != primaryFailure) primaryFailure.addSuppressed(cleanup);
      }
    }
  }

  private static Javalin app() {
    return Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jetty.modifyServer(LocalApiServer::configureTransportShutdown);
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
  }

  private static HttpRequest request(Javalin app, String route, boolean post) {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + route)).timeout(Duration.ofSeconds(30));
    return (post ? builder.POST(HttpRequest.BodyPublishers.ofString("{}")) : builder.GET()).build();
  }

  private static HttpResponse<String> send(HttpClient client, Javalin app, String route, boolean post) throws Exception {
    return client.send(request(app, route, post), HttpResponse.BodyHandlers.ofString());
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }

  private static void awaitIgnoringInterrupts(CountDownLatch gate) {
    boolean interrupted = false;
    for (;;) {
      try { gate.await(); break; }
      catch (InterruptedException expected) { interrupted = true; }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
