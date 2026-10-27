/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.engine.RuntimeReloadProducersHttpIntegrationTest.Fixture;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A real send refusal before handler entry must still retire the private fixture. */
@Timeout(45)
final class OutstandingHttpFailureCleanupIntegrationTest {
  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;

  @Test
  void budgetCleanupOnlyObservesItsOwnCloserThreads() throws Exception {
    var release = new CountDownLatch(1);
    Thread foreign = new Thread(() -> {
      try { release.await(); }
      catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }, "fixture-http-stop");
    foreign.start();
    try {
      var target = new LinuxWatchedDriveReturnIntegrationTest();
      target.directory = directory;
      target.outstandingHttpCleanupSharesTheBudgetAndRetainsOwnersUntilRetry(0);
      assertTrue(foreign.isAlive(), "the other fixture's closer is still independently owned");
    } finally {
      release.countDown();
      foreign.join(10_000);
    }
    assertFalse(foreign.isAlive());
  }

  @ParameterizedTest(name = "preEntryExchangeFailureKeepsItsIdentityAndClosesTheFixture({arguments})")
  @ValueSource(booleans = {false, true})
  void preEntryExchangeFailureKeepsItsIdentityAndClosesTheFixture(boolean errorBody) throws Exception {
    var host = new RuntimeReloadProducersHttpIntegrationTest();
    host.directory = directory;
    try (var fixture = host.new Fixture()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var exited = new CountDownLatch(1);
      fixture.http.get("/never-entered", context -> {
        entered.countDown();
        try { context.result("unexpected entry"); }
        finally { exited.countDown(); }
      });
      fixture.transport.close();
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
          + fixture.http.port() + "/never-entered")).GET().build();
      var exchange = fixture.transport.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      var sendRefusal = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> exchange.get(5, TimeUnit.SECONDS));
      Throwable original = errorBody ? new AssertionError("body error after send refusal", sendRefusal) : sendRefusal;
      var closeRefusal = new java.io.IOException("forced asynchronous close-completion failure");
      CompletableFuture<?> closing = errorBody ? CompletableFuture.failedFuture(closeRefusal) : null;
      var observed = assertThrows(original.getClass(), () -> {
        Throwable primary = null;
        try { throw original; }
        catch (Exception | Error failure) { primary = failure; throw failure; }
        finally { finishExchange(fixture, entered, release, exited, closing, primary); }
      });
      assertSame(original, observed, "pre-entry cleanup must retain the original send refusal");
      assertEquals(errorBody ? 1 : 0, observed.getSuppressed().length);
      if (errorBody) assertSame(closeRefusal, observed.getSuppressed()[0].getCause());
      assertEquals(1, entered.getCount(), "the refused exchange never entered its handler");
      assertEquals(1, exited.getCount(), "cleanup cannot await an exit for an unentered handler");
      assertTrue(fixture.closed, "fixture closure must be attempted even before handler entry");
      assertTrue(fixture.server.awaitClosed(0));
      assertEquals(0, fixture.root.admission().activeWorkCount());
      assertFalse(fixture.clientCloser.isAlive());
      assertFalse(fixture.httpCloser.isAlive());
      assertFalse(fixture.transportCloser.isAlive());
      assertSame(fixture.previous, ConfigStore.globalOrNull());
      assertEquals(fixture.previousHome, System.getProperty("user.home"));
      assertEquals(fixture.previousDataDir, System.getProperty(EnvRegistry.DATA_DIR.configKey()));
    }
  }

  private static void finishExchange(Fixture fixture, CountDownLatch entered,
      CountDownLatch release, CountDownLatch exited, CompletableFuture<?> closing, Throwable primary) throws Exception {
    try {
      var cleanup = LinuxWatchedDriveReturnIntegrationTest.class.getDeclaredMethod(
          "finishOutstandingHttpExchange", Fixture.class, CountDownLatch.class,
          CountDownLatch.class, CountDownLatch.class, CompletableFuture.class, Throwable.class);
      cleanup.invoke(null, fixture, entered, release, exited, closing, primary);
    } catch (NoSuchMethodException beforeFix) {
      // Snapshot d97's original finally executes here as the test-only before adapter.
      // It must execute and mask the real refusal, not fail merely because the helper is absent.
      release.countDown();
      assertTrue(exited.await(10, TimeUnit.SECONDS));
      if (closing != null) closing.get(10, TimeUnit.SECONDS);
      fixture.close(java.time.Duration.ofSeconds(10));
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception exception) throw exception;
      if (failure.getCause() instanceof Error error) throw error;
      throw new AssertionError(failure.getCause());
    }
  }
}
