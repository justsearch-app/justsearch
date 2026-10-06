/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.QueryPrewarmFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.ipc.TraceDegradation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D step 7 (S21): "Search models are warm before the app reports ready; the first search
 * after activation does not fall back."
 *
 * <p>Real seam: the production deferred model initialization ({@code initDeferredModels}) composes
 * and wires the embedding encoder into the production services, and readiness is read from the
 * production health service ({@code embeddingReady}, the EMBEDDING readiness input) and the
 * model-ready latch that releases searches. Only the native session owner is replaced (see
 * {@link QueryPrewarmFixture.SlowGpuSessions}).
 *
 * <p>Uses only API that exists before the change, so each check runs on the before-state.
 */
@Timeout(120)
final class KnowledgeServerQueryPrewarmTest {
  @TempDir Path dir;

  @Test
  // S21: readiness stays false until the query session is warm; the first search does not load
  void queryEncodersAreWarmBeforeReadyAndTheFirstSearchDoesNotFallBack() throws Exception {
    var sessions = new SlowGpuSessions(CREATION_MS);
    try (var boot = new Boot(dir, "s21", sessions, 10_000L)) {
      var observations = boot.initializeWhileObservingReadiness();

      assertEquals(0, boot.modelReadyLatch().getCount(), "model initialization completed");
      assertEquals(List.of(), observations.readyWhileCold(),
          "the embedding was reported ready while its query session was not warm");
      assertTrue(boot.embeddingReady(), "the embedding is ready after model initialization");
      assertTrue(sessions.isQueryWarm(), "the query session is warm when models report ready");

      int creationsAtReady = sessions.creations.get();
      int acquisitionsAtReady = sessions.acquisitions.get();
      TraceDegradation degradation = boot.hybridSearch();

      assertTrue(sessions.acquisitions.get() > acquisitionsAtReady,
          "the search did not run its dense leg");
      assertEquals(0, sessions.queryRefusals.get(),
          "the first search leg found no warm session (cause loading)");
      assertNotEquals("loading", degradation.getHybridFallbackCause(),
          "the first search after activation fell back because models were loading");
      assertFalse(degradation.getHybridFallback(),
          () -> "the first search after activation fell back: "
              + degradation.getHybridFallbackReason());
      assertEquals(creationsAtReady, sessions.creations.get(),
          "the first search created a session");
    }
  }

  @Test
  // S21 after a GPU release: the query session is warmed again without a search creating it
  void aReleasedQuerySessionIsWarmedAgainWithoutASearch() throws Exception {
    var sessions = new SlowGpuSessions(CREATION_MS);
    try (var boot = new Boot(dir, "rewarm", sessions, 10_000L)) {
      boot.initializeWhileObservingReadiness();
      int creationsBefore = sessions.creations.get();

      sessions.releaseGpu();

      assertTrue(waitFor(Duration.ofSeconds(15), sessions::isQueryWarm),
          "the released query session was not warmed again");
      assertTrue(sessions.creations.get() > creationsBefore, "a new session was created");
      assertTrue(waitFor(Duration.ofSeconds(5), boot::embeddingReady),
          "the embedding is ready once its new session is warm");
      int creationsAtReady = sessions.creations.get();
      int acquisitionsAtReady = sessions.acquisitions.get();
      TraceDegradation degradation = boot.hybridSearch();
      assertTrue(sessions.acquisitions.get() > acquisitionsAtReady,
          "the search did not run its dense leg");
      assertEquals(0, sessions.queryRefusals.get(), "a search leg found no warm session");
      assertFalse(degradation.getHybridFallback(),
          () -> "the search fell back: " + degradation.getHybridFallbackReason());
      assertEquals(creationsAtReady, sessions.creations.get(), "the search created a session");
    }
  }

  @Test
  // Bounded: a stalled prewarm releases the models at its bound; the embedding waits for warmth
  void aStalledPrewarmReleasesModelsAtItsBoundBeforeTheEmbeddingIsReady() throws Exception {
    var sessions = new SlowGpuSessions(Duration.ofMinutes(5).toMillis());
    long boundMs = 1_500L;
    try (var boot = new Boot(dir, "stalled", sessions, boundMs)) {
      long start = System.nanoTime();
      boot.initializeWhileObservingReadiness();
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertEquals(0, boot.modelReadyLatch().getCount(), "models were not released");
      assertTrue(elapsedMs < boundMs + 10_000L,
          () -> "model readiness waited " + elapsedMs + "ms on a stalled prewarm");
      assertFalse(sessions.isQueryWarm());
      assertFalse(boot.embeddingReady(),
          "the embedding was ready with a cold query session before its readiness bound");
    }
  }

  @Test
  // Bounded: a failed prewarm releases the models at once; the embedding waits for warmth
  void aFailedPrewarmReleasesModelsBeforeTheEmbeddingIsReady() throws Exception {
    var sessions = new SlowGpuSessions(0L);
    sessions.creationFailure = new IllegalStateException("CUDA session creation failed");
    try (var boot = new Boot(dir, "failed", sessions, 30_000L)) {
      long start = System.nanoTime();
      boot.initializeWhileObservingReadiness();
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertEquals(0, boot.modelReadyLatch().getCount(), "models were not released");
      assertTrue(elapsedMs < 20_000L,
          () -> "model readiness waited " + elapsedMs + "ms after the prewarm failed");
      assertTrue(sessions.creationAttempts.get() >= 1, "the prewarm tried to create the session");
      assertFalse(boot.embeddingReady(),
          "the embedding was ready after a failed prewarm before its readiness bound");
    }
  }

  @Test
  // P2: a query session that runs without creation (CPU) is not waited on and not re-run
  void aWarmCpuQuerySessionIsNotWaitedOn() throws Exception {
    var sessions = new SlowGpuSessions(CREATION_MS);
    sessions.cpuOnly = true;
    try (var boot = new Boot(dir, "cpu-only", sessions, 30_000L)) {
      long start = System.nanoTime();
      var observations = boot.initializeWhileObservingReadiness();
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertEquals(0, boot.modelReadyLatch().getCount());
      assertEquals(List.of(), observations.readyWhileCold());
      assertEquals(0, sessions.acquisitions.get(),
          "model initialization ran an encode on a session that was already usable");
      assertTrue(elapsedMs < 20_000L, () -> "CPU-only initialization took " + elapsedMs + "ms");
      assertTrue(boot.embeddingReady(), "the CPU embedding is ready");
      TraceDegradation degradation = boot.hybridSearch();
      assertTrue(sessions.acquisitions.get() > 0, "the search did not run its dense leg");
      assertFalse(degradation.getHybridFallback(),
          () -> "the CPU search fell back: " + degradation.getHybridFallbackReason());
    }
  }

  @Test
  // Semantic search disabled: model initialization does not wait on any prewarm
  void semanticSearchDisabledDoesNotWaitOnPrewarm() throws Exception {
    var previous = ConfigStore.globalOrNull();
    Path models = dir.resolve("disabled-models");
    createEmbeddingModel(models);
    var configuration = configuration(dir, "disabled", models, false);
    ConfigStore.setGlobal(new ConfigStore(configuration));
    try (var executors = new TestEngineExecutors()) {
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(configuration), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null,
          configuration));
      doNothing().when(server).startDeferredModelInitialization(any());
      doReturn(60_000L).when(server).queryPreparationWarmupBudgetMillis();
      try {
        server.start();
        long start = System.nanoTime();
        invokeDeferredModelInitialization(server);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(0, ((CountDownLatch) field(server, "modelReadyLatch")).getCount());
        assertTrue(elapsedMs < 20_000L,
            () -> "initialization without semantic search took " + elapsedMs + "ms");
      } finally {
        server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }
}
