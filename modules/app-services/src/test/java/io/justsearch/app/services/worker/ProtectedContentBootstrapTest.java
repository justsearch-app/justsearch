/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real Head startup lifecycle and its owned asynchronous cleanup. */
@Timeout(30)
final class ProtectedContentBootstrapTest {
  @Test
  void healthyStartupRetriesWriterReadinessWithoutBlockingSearchAndHonorsCompletionOnRestart(
      @TempDir Path data) throws Exception {
    var writerReady = new AtomicBoolean();
    var probed = new CountDownLatch(1);
    var scanning = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var scans = new AtomicInteger();
    // Name-based answer keeps the behavioral test compilable on the baseline API as well.
    var client = mock(KnowledgeClient.class, call -> {
      if (call.getMethod().getName().equals("forEachIndexedSourceWithCollection")) {
        scans.incrementAndGet();
        scanning.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
        return 0L;
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
    });
    when(client.isHealthy(any())).thenReturn(true);
    when(client.captureServingGeneration(any())).thenAnswer(call -> {
      boolean ready = writerReady.get();
      probed.countDown();
      if (!ready) throw new IllegalStateException("writer still opening");
      return "serving";
    });
    var host = mock(WorkerHost.class);
    when(host.start(any(), any())).thenReturn(client);
    var config = new KnowledgeServerConfig(false, data, data, data,
        5_000L, 2_000L, 3, 2_000L, 1_000L, 300_000L, 100, 0L, 0);
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config, host)) {
      var bootstrap = fixture.bootstrap();
      bootstrap.start();
      assertTrue(probed.await(5, TimeUnit.SECONDS), "healthy Head startup must schedule protected cleanup");
      assertEquals(0, scans.get(), "a read-only startup must not spend the cleanup attempt");
      writerReady.set(true);
      assertTrue(bootstrap.checkHealth());
      assertTrue(scanning.await(5, TimeUnit.SECONDS), "later healthy polling retries writer readiness");
      assertTrue(bootstrap.checkHealth(), "health and search readiness remain available during cleanup");
      assertFalse(Files.exists(data.resolve(".protected-content-version")));
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(data.resolve(".protected-content-version")) && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(Files.exists(data.resolve(".protected-content-version")), "successful cleanup records completion");
      bootstrap.closeForUpgrade();
      bootstrap.start();
      assertTrue(bootstrap.checkHealth());
      // close drains the owned asynchronous worker, so this is an actual completed restart check.
      bootstrap.closeForUpgrade();
      assertEquals(1, scans.get(), "a completed version does no enumeration on restart");
    } finally { release.countDown(); }
  }
}
