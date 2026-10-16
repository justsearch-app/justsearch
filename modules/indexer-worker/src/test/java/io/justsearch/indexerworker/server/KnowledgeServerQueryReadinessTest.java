/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.QueryPrewarmFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.TraceDegradation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D step 7: the embedding readiness that the query prewarm gates is bounded and one-way.
 *
 * <p>Same real seam as {@link KnowledgeServerQueryPrewarmTest} (the production deferred model
 * initialization, health service and search service; only the native session owner is a test
 * double). These checks read {@code query_encoders_cold} and stub the readiness bound, which exist
 * only with the change.
 */
@Timeout(120)
final class KnowledgeServerQueryReadinessTest {
  private static final long READINESS_BOUND_MS = 4_000L;

  @TempDir Path dir;

  @Test
  // A prewarm that always fails reaches ready within the readiness bound, with the cold reason
  void aPrewarmThatAlwaysFailsReachesReadyWithinTheBoundWithTheReason() throws Exception {
    var sessions = new SlowGpuSessions(0L);
    sessions.creationFailure = new IllegalStateException("CUDA session creation failed");
    try (var boot = new Boot(dir, "always-fails", sessions, 30_000L,
        server -> doReturn(READINESS_BOUND_MS).when(server).queryReadinessBoundMillis())) {
      long start = System.nanoTime();
      boot.initializeWhileObservingReadiness();

      assertTrue(waitFor(Duration.ofMillis(READINESS_BOUND_MS + 10_000L), boot::embeddingReady),
          "a prewarm that keeps failing held the embedding readiness past its bound");
      long readyAfterMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(readyAfterMs >= READINESS_BOUND_MS,
          () -> "the embedding was ready after " + readyAfterMs + "ms, before its bound,"
              + " with a query session that never warmed");

      HealthCheckResponse health = boot.health();
      assertTrue(health.getEmbeddingReady());
      assertTrue(health.getQueryEncodersCold(),
          "health reports the embedding ready without saying its query encoders are cold");
      assertFalse(sessions.isQueryWarm());
      assertTrue(sessions.creationAttempts.get() >= 1, "the prewarm tried to create the session");

      TraceDegradation degradation = boot.hybridSearch();
      assertTrue(degradation.getHybridFallback(),
          "a search without a warm query session did not fall back");
      assertEquals("loading", degradation.getHybridFallbackCause(),
          () -> "the fallback cause is not loading: " + degradation.getHybridFallbackReason());
    }
  }

  @Test
  // After readiness, a GPU release and re-creation change only the component state
  void aGpuReleaseAfterReadyChangesOnlyTheComponentStateNotTheReadiness() throws Exception {
    var sessions = new SlowGpuSessions(CREATION_MS);
    try (var boot = new Boot(dir, "no-flip", sessions, 10_000L)) {
      boot.initializeWhileObservingReadiness();
      assertTrue(boot.embeddingReady(), "the embedding is ready after model initialization");
      assertFalse(boot.health().getQueryEncodersCold());

      var notReadySamples = new AtomicInteger();
      var coldSamples = new AtomicInteger();
      var done = new AtomicBoolean();
      try (var observer = Executors.newSingleThreadExecutor()) {
        var observing = observer.submit(() -> {
          while (!done.get()) {
            HealthCheckResponse health = boot.health();
            if (!health.getEmbeddingReady()) notReadySamples.incrementAndGet();
            if (health.getQueryEncodersCold()) coldSamples.incrementAndGet();
            Thread.sleep(5);
          }
          return null;
        });
        try {
          sessions.releaseGpu();
          assertTrue(waitFor(Duration.ofSeconds(15), sessions::isQueryWarm),
              "the released query session was not warmed again");
          assertTrue(waitFor(Duration.ofSeconds(5), () -> !boot.health().getQueryEncodersCold()),
              "health still reports the query encoders cold after they were warmed again");
        } finally {
          done.set(true);
          observing.get(10, TimeUnit.SECONDS);
        }
      }

      assertEquals(0, notReadySamples.get(),
          "the embedding readiness flipped back to not ready while its session was re-created");
      assertTrue(coldSamples.get() > 0,
          "the component state did not report the cold query session during re-creation");
      assertTrue(boot.embeddingReady());
    }
  }
}
