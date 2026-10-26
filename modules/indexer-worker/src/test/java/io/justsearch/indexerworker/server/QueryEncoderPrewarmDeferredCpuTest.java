/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.QueryDevice;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Delegated K2/K15: readiness and repeated serving prewarm leave deferred backups absent. */
final class QueryEncoderPrewarmDeferredCpuTest {
  @Test void repeatedGpuWarmPassesDoNotWarmSpladeOrRerankerCpuBackups() throws Exception {
    var backupCalls = new AtomicInteger();
    var servingCalls = new AtomicInteger();
    var ready = new CountDownLatch(2);
    var executor = Executors.newSingleThreadScheduledExecutor();
    var targets = List.of(EncoderRole.SPLADE, EncoderRole.RERANKER).stream().map(role ->
        new QueryEncoderPrewarm.Target(role, new Object(), () -> true, () -> QueryDevice.GPU,
            listener -> {}, (text, request) -> { servingCalls.incrementAndGet(); return true; },
            request -> { backupCalls.incrementAndGet(); return true; })).toList();
    try (var prewarm = new QueryEncoderPrewarm(
        () -> new QueryEncoderPrewarm.Selection(targets, () -> {}), Duration.ofSeconds(1), executor)) {
      prewarm.addListener(new QueryEncoderPrewarm.QueryPrewarmListener() {
        @Override public void onReady(Set<EncoderRole> warmed, QueryDevice device) {
          assertEquals(Set.of(EncoderRole.SPLADE, EncoderRole.RERANKER), warmed);
          assertEquals(QueryDevice.GPU, device);
          ready.countDown();
        }
        @Override public void onFailed(EncoderRole role, String reason) { fail(reason); }
      });
      prewarm.start();
      executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
      prewarm.requestPass();
      assertTrue(ready.await(2, TimeUnit.SECONDS));
      executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
      assertTrue(prewarm.allWarm());
      assertEquals(0, servingCalls.get(), "already warm serving sessions must not run again");
      assertEquals(0, backupCalls.get(), "ready/generation/periodic passes cannot create backups");
    }
  }
}
