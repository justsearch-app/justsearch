/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boot and owner-lifetime proof for the Indexer-local recorded-ingestion attachment.
 *
 * <p>The boot cases use a real {@link KnowledgeServer} and a real SQLite queue. The generation
 * cases call only the package-private observation seam because replacing a live generation while a
 * full Worker is booting would test migration rather than the authority boundary. Boot cases
 * suppress only deferred model initialization; SQLite, Lucene, app services and index drain are real.
 */
@Timeout(180)
final class KnowledgeServerRecordedIngestionTest {

  private static final String OPERATION = "recorded-boot-operation";
  private static final String PLAN_HASH = "a".repeat(64);

  @Test
  @DisplayName("real boot attaches before recorded recovery and closes attachment after services")
  void realBootAttachesBeforeRecoveryAndClosesAfterDrain(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    seedProcessingRecordedClaim(layout.dataDir(), tempDir.resolve("watched/file.txt"));

    BootLifecycle lifecycle = new BootLifecycle();
    KnowledgeServer server = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
    server = withoutDeferredModels(server);
    lifecycle.server.set(server);
    org.mockito.Mockito.doAnswer(call -> {
      assertTrue(lifecycle.attached.get(), "the reaper must not start before attach");
      return call.callRealMethod();
    }).when(server).startStuckJobReaper(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(call -> {
      var services = org.mockito.Mockito.spy((DefaultWorkerAppServices) call.callRealMethod());
      org.mockito.Mockito.doAnswer(start -> {
        assertTrue(lifecycle.attached.get(), "index polling must not start before attach");
        return start.callRealMethod();
      }).when(services).startIndexingLoop();
      return services;
    }).when(server).newAppServices();
    try {
      server.start();

      assertTrue(lifecycle.attached.get(), "the real boot must publish the owner before recovery");
      assertEquals(0, lifecycle.earlyClaims.get(),
          "recorded authority must never be consulted before attach returns");
      assertTrue(lifecycle.events.indexOf("attach") < lifecycle.events.indexOf("claim"),
          "recoverStuckJobs must run after the lifecycle attachment");
      assertTrue(lifecycle.claims.get() > 0,
          "the seeded PROCESSING recorded member must exercise the recovery authority callback");

      server.close();
      assertTrue(lifecycle.attachmentClosedAfterServices,
          "attachment close must follow the app-services drain");
      assertTrue(lifecycle.queueWasOpenAtAttachmentClose,
          "attachment close must precede queue close");
    } finally {
      if (!server.awaitClosed(0)) server.close();
    }
  }

  @Test
  @DisplayName("legacy constructor denies recorded recovery during a real boot")
  void legacyConstructorUsesDeniedRecordedLifecycle(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    seedProcessingRecordedClaim(layout.dataDir(), tempDir.resolve("watched/denied.txt"));

    KnowledgeServer server = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null);
    server = withoutDeferredModels(server);
    try {
      server.start();
      assertEquals(1L, server.jobQueueForTests().jobStateCounts().processingCount(),
          "the default denied attachment must leave recorded PROCESSING work fenced");
      assertTrue(server.appServices() != null, "denial must not prevent the Worker from booting");
    } finally {
      server.close();
    }
  }

  @Test
  @DisplayName("attachment failure cleans up a real boot and preserves fatal Error identity")
  void attachmentFailureCleansUpRealBoot(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout runtimeLayout = preparedLayout(tempDir.resolve("runtime"));
    FailingLifecycle runtimeFailure = new FailingLifecycle(new IllegalStateException("attach failed"));
    KnowledgeServer runtimeServer = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(runtimeLayout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), runtimeFailure);
    IOException wrapped = assertThrows(IOException.class, runtimeServer::start);
    assertSame(runtimeFailure.failure, wrapped.getCause(), "runtime attach failure must remain the cause");
    assertTrue(runtimeServer.awaitClosed(0), "failed startup must finish queue and service cleanup");

    WorkerBootFixture.Layout fatalLayout = preparedLayout(tempDir.resolve("fatal"));
    Error fatal = new AssertionError("fatal attach failure");
    FailingLifecycle fatalFailure = new FailingLifecycle(fatal);
    KnowledgeServer fatalServer = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(fatalLayout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), fatalFailure);
    assertSame(fatal, assertThrows(AssertionError.class, fatalServer::start),
        "fatal attach failure identity must cross the real boot boundary unchanged");
    assertTrue(fatalServer.awaitClosed(0), "fatal startup must still complete cleanup");
  }

  @Test
  @DisplayName("a failed attachment close retains queue and owner until retry")
  void attachmentCloseFailureRetainsOwnersUntilRetry(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    CloseOnceLifecycle lifecycle = new CloseOnceLifecycle();
    KnowledgeServer server = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
    server = withoutDeferredModels(server);
    try {
      server.start();
      IOException failure = assertThrows(IOException.class, server::close);
      assertSame(lifecycle.closeFailure, failure, "the attachment close failure must be reported");
      assertFalse(server.awaitClosed(0), "a failed attachment close is incomplete shutdown");
      assertNotNull(server.jobQueueForTests(), "the queue owner must remain published for retry");
      assertTrue(server.jobQueueForTests().queueDepth() >= 0,
          "the queue must remain open while attachment cleanup is retryable");

      server.close();
      assertTrue(server.awaitClosed(0), "retry must finish the retained attachment and queue");
      assertEquals(2, lifecycle.closeCalls.get(), "the retained attachment must be retried once");
    } finally {
      if (!server.awaitClosed(0)) server.close();
    }
  }

  @Test
  @DisplayName("generation observation follows authoritative state, not captured Blue")
  void generationObservationFollowsAuthoritativeState(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    KnowledgeServer server = helperServer(layout);
    RunningRuntime running = org.mockito.Mockito.mock(RunningRuntime.class);
    org.mockito.Mockito.when(running.isAcceptingWrites()).thenReturn(true);
    server.appServices = org.mockito.Mockito.mock(WorkerAppServices.class);
    org.mockito.Mockito.when(server.appServices.recordedWriterReady()).thenReturn(true);
    setField(server, "ingestLifecycle", running);
    setField(server, "searchLifecycle", running);
    setField(server, "indexGenerationManager", layout.genManager());
    setField(server, "activeIndexPath", layout.activePath());
    try {
      String blue = layout.genManager().initializeOrLoad().activeGenerationId();
      assertEquals(Optional.of(blue), server.currentRecordedServingGeneration(),
          "an idle active generation is eligible while the captured path is current");

      layout.genManager().startMigration("test-generation-transition");
      assertEquals(Optional.empty(), server.currentRecordedServingGeneration(),
          "migration state must fence recorded authority");

      IndexGenerationManager.State promoted = layout.genManager().promoteBuildingGenerationToActive();
      String green = promoted.active_generation();
      assertNotEquals(blue, green, "promotion must advance the authoritative pointer");
      assertEquals(Optional.empty(), server.currentRecordedServingGeneration(),
          "a captured Blue path must not remain eligible after promotion");

      setField(server, "activeIndexPath",
          layout.genManager().resolveGenerationPathStrict(green));
      assertEquals(Optional.of(green), server.currentRecordedServingGeneration(),
          "the current authoritative active generation must be observed dynamically");
    } finally {
      server.close();
    }
  }

  @Test
  @DisplayName("migration, deferred runtime, and rebuild brake all fence recorded serving")
  void migrationDeferredAndBrakeFenceRecordedServing(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    KnowledgeServer server = helperServer(layout);
    server.appServices = org.mockito.Mockito.mock(WorkerAppServices.class);
    org.mockito.Mockito.when(server.appServices.recordedWriterReady()).thenReturn(true);
    RunningRuntime running = org.mockito.Mockito.mock(RunningRuntime.class);
    org.mockito.Mockito.when(running.isAcceptingWrites()).thenReturn(true);
    setField(server, "ingestLifecycle", running);
    setField(server, "searchLifecycle", running);
    setField(server, "indexGenerationManager", layout.genManager());
    setField(server, "activeIndexPath", layout.activePath());
    try {
      server.appServices = null;
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "unpublished services must fence an otherwise writable idle runtime");
      server.appServices = org.mockito.Mockito.mock(WorkerAppServices.class);
      org.mockito.Mockito.when(server.appServices.recordedWriterReady()).thenReturn(true);
      setField(server, "searchLifecycle", org.mockito.Mockito.mock(RunningRuntime.class));
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "different writable ingest and search owners must remain fenced");
      setField(server, "searchLifecycle", running);
      assertTrue(server.currentRecordedServingGeneration().isPresent(),
          "the identical published writable owner is the positive control");
      layout.genManager().startMigration("test-fence");
      assertTrue(server.currentRecordedServingGeneration().isEmpty(), "MIGRATING must fence serving");
      IndexGenerationManager.State promoted = layout.genManager().promoteBuildingGenerationToActive();
      setField(server, "activeIndexPath",
          layout.genManager().resolveGenerationPathStrict(promoted.active_generation()));

      DeferredRuntime deferred = org.mockito.Mockito.mock(DeferredRuntime.class);
      setField(server, "ingestLifecycle", deferred);
      setField(server, "searchLifecycle", deferred);
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "a deferred runtime is not an online serving owner");

      setField(server, "ingestLifecycle", running);
      setField(server, "searchLifecycle", running);
      setField(server, "rebuildBrakeExhausted", true);
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "an exhausted rebuild brake must fence recorded serving");
    } finally {
      server.close();
    }
  }

  @Test
  @DisplayName("recorded generation waits for the replacement writer and resumes after publication")
  void deferredReplacementPublishesWriterBeforeNotifyingRecordedOwner(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    ReplacementLifecycle lifecycle = new ReplacementLifecycle();
    KnowledgeServer server = org.mockito.Mockito.spy(new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle));
    lifecycle.server.set(server);

    WorkerAppServices deferred = org.mockito.Mockito.mock(WorkerAppServices.class);
    DefaultWorkerAppServices replacement = org.mockito.Mockito.mock(
        DefaultWorkerAppServices.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    CountDownLatch incumbentCloseEntered = new CountDownLatch(1);
    CountDownLatch releaseIncumbent = new CountDownLatch(1);
    AtomicBoolean replacementRunning = new AtomicBoolean();
    org.mockito.Mockito.when(deferred.recordedWriterReady()).thenReturn(false);
    org.mockito.Mockito.when(replacement.recordedWriterReady())
        .thenAnswer(ignored -> replacementRunning.get());
    org.mockito.Mockito.doAnswer(ignored -> {
      incumbentCloseEntered.countDown();
      assertTrue(releaseIncumbent.await(5, TimeUnit.SECONDS),
          "the test must release the blocked incumbent close");
      return null;
    }).when(deferred).close();
    org.mockito.Mockito.doAnswer(ignored -> {
      replacementRunning.set(true);
      return null;
    }).when(replacement).startIndexingLoop();
    org.mockito.Mockito.doReturn(replacement).when(server).newAppServices();

    RunningRuntime running = org.mockito.Mockito.mock(RunningRuntime.class);
    org.mockito.Mockito.when(running.isAcceptingWrites()).thenReturn(true);
    server.appServices = deferred;
    setField(server, "ingestLifecycle", running);
    setField(server, "searchLifecycle", running);
    setField(server, "indexGenerationManager", layout.genManager());
    setField(server, "activeIndexPath", layout.activePath());
    setField(server, "jobQueue", org.mockito.Mockito.mock(JobQueue.class));
    server.attachRecordedIngestion();

    var reconstruct = KnowledgeServer.class.getDeclaredMethod(
        "reconstructAppServicesAfterDeferredUpgrade");
    reconstruct.setAccessible(true);
    AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
    Thread replacementThread = Thread.ofVirtual().start(() -> {
      try {
        reconstruct.invoke(server);
      } catch (Throwable failure) {
        replacementFailure.set(failure);
      }
    });
    try {
      assertTrue(incumbentCloseEntered.await(5, TimeUnit.SECONDS),
          () -> "replacement must reach the incumbent close; failure=" + replacementFailure.get());
      assertSame(deferred, server.appServices(),
          "the incumbent remains published while its close is in progress");
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "new RunningRuntime globals cannot authorize a stale deferred service");
      assertEquals(0, lifecycle.publications.get(),
          "the recorded owner cannot resume before the replacement is published");

      releaseIncumbent.countDown();
      replacementThread.join(5_000L);
      assertFalse(replacementThread.isAlive(), "replacement must finish after close is released");
      assertTrue(replacementFailure.get() == null, String.valueOf(replacementFailure.get()));
      assertSame(replacement, server.appServices());
      assertEquals(Optional.of(layout.genManager().initializeOrLoad().activeGenerationId()),
          server.currentRecordedServingGeneration(),
          "the published started replacement restores recorded writer authority");
      assertEquals(1, lifecycle.publications.get(),
          "successful publication must notify the same recorded attachment exactly once");
      assertTrue(lifecycle.publicationFailure.get() == null,
          String.valueOf(lifecycle.publicationFailure.get()));
      assertSame(replacement, lifecycle.publishedServices.get(),
          "the notification must observe the replacement, not the closed incumbent");
      assertEquals(server.currentRecordedServingGeneration(), lifecycle.publishedGeneration.get(),
          "the notification must observe the newly available generation");
      var order = org.mockito.Mockito.inOrder(deferred, replacement);
      order.verify(deferred).close();
      order.verify(replacement).startIndexingLoop();
    } finally {
      releaseIncumbent.countDown();
      replacementThread.join(5_000L);
      server.close();
    }
  }

  @Test
  @DisplayName("running runtime swap fences generation until unlock and replacement publication")
  void runningSwapNotifiesRecordedOwnerOnlyAfterUnlock(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    ReplacementLifecycle lifecycle = new ReplacementLifecycle();
    KnowledgeServer server = org.mockito.Mockito.spy(new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle));
    lifecycle.server.set(server);

    WorkerAppServices incumbent = org.mockito.Mockito.mock(WorkerAppServices.class);
    DefaultWorkerAppServices replacement = org.mockito.Mockito.mock(
        DefaultWorkerAppServices.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    RunningRuntime oldRuntime = org.mockito.Mockito.mock(RunningRuntime.class);
    RunningRuntime freshRuntime = org.mockito.Mockito.mock(RunningRuntime.class);
    CountDownLatch drainEntered = new CountDownLatch(1);
    CountDownLatch releaseDrain = new CountDownLatch(1);
    AtomicBoolean replacementRunning = new AtomicBoolean();
    org.mockito.Mockito.when(oldRuntime.isAcceptingWrites()).thenReturn(true);
    org.mockito.Mockito.when(freshRuntime.isAcceptingWrites()).thenReturn(true);
    org.mockito.Mockito.when(incumbent.recordedWriterReady()).thenReturn(true);
    org.mockito.Mockito.when(replacement.recordedWriterReady())
        .thenAnswer(ignored -> replacementRunning.get());
    org.mockito.Mockito.doAnswer(ignored -> {
      drainEntered.countDown();
      assertTrue(releaseDrain.await(5, TimeUnit.SECONDS),
          "the test must release the blocked runtime drain");
      return null;
    }).when(oldRuntime).drainAndClose(
        org.mockito.ArgumentMatchers.any(java.time.Duration.class),
        org.mockito.ArgumentMatchers.eq(
            io.justsearch.adapters.lucene.runtime.SwapReason.CONFIG_RELOAD));
    org.mockito.Mockito.doAnswer(ignored -> {
      replacementRunning.set(true);
      return null;
    }).when(replacement).startIndexingLoop();
    org.mockito.Mockito.doReturn(replacement).when(server).newAppServices();

    server.appServices = incumbent;
    setField(server, "ingestLifecycle", oldRuntime);
    setField(server, "searchLifecycle", oldRuntime);
    setField(server, "indexGenerationManager", layout.genManager());
    setField(server, "activeIndexPath", layout.activePath());
    setField(server, "jobQueue", org.mockito.Mockito.mock(JobQueue.class));
    server.attachRecordedIngestion();
    assertTrue(server.currentRecordedServingGeneration().isPresent(),
        "the incumbent is the ready positive control before swap ownership begins");

    AtomicReference<Throwable> swapFailure = new AtomicReference<>();
    Thread swapThread = Thread.ofVirtual().start(() -> {
      try {
        server.swapRuntime(() -> freshRuntime, java.time.Duration.ofSeconds(5),
            io.justsearch.adapters.lucene.runtime.SwapReason.CONFIG_RELOAD);
      } catch (Throwable failure) {
        swapFailure.set(failure);
      }
    });
    try {
      assertTrue(drainEntered.await(5, TimeUnit.SECONDS),
          () -> "swap must reach the incumbent drain; failure=" + swapFailure.get());
      assertSame(incumbent, server.appServices(),
          "the incumbent services remain published while their runtime drains");
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "the swap lock must fence generation while the writable incumbent drains");
      assertEquals(0, lifecycle.publications.get(),
          "the recorded owner cannot be notified while swap ownership is held");

      releaseDrain.countDown();
      swapThread.join(5_000L);
      assertFalse(swapThread.isAlive(), "runtime swap must finish after drain is released");
      assertTrue(swapFailure.get() == null, String.valueOf(swapFailure.get()));
      assertSame(replacement, server.appServices());
      assertEquals(Optional.of(layout.genManager().initializeOrLoad().activeGenerationId()),
          server.currentRecordedServingGeneration());
      assertEquals(1, lifecycle.publications.get(),
          "the unlocked successful swap must notify its attachment once");
      assertTrue(lifecycle.publicationFailure.get() == null,
          String.valueOf(lifecycle.publicationFailure.get()));
      assertSame(replacement, lifecycle.publishedServices.get());
      assertEquals(server.currentRecordedServingGeneration(), lifecycle.publishedGeneration.get(),
          "the notification must run after unlock and observe available generation");
      var order = org.mockito.Mockito.inOrder(oldRuntime, incumbent, replacement);
      order.verify(oldRuntime).drainAndClose(
          org.mockito.ArgumentMatchers.any(java.time.Duration.class),
          org.mockito.ArgumentMatchers.eq(
              io.justsearch.adapters.lucene.runtime.SwapReason.CONFIG_RELOAD));
      order.verify(incumbent).close();
      order.verify(replacement).startIndexingLoop();
    } finally {
      releaseDrain.countDown();
      swapThread.join(5_000L);
      server.close();
    }
  }

  @Test
  @DisplayName("failed runtime drain cannot restore recorded authority after unlock")
  void failedRunningDrainLeavesRecordedGenerationFenced(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    ReplacementLifecycle lifecycle = new ReplacementLifecycle();
    KnowledgeServer server = new KnowledgeServer(
        new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
    lifecycle.server.set(server);

    WorkerAppServices incumbent = org.mockito.Mockito.mock(WorkerAppServices.class);
    RunningRuntime oldRuntime = org.mockito.Mockito.mock(RunningRuntime.class);
    AtomicBoolean acceptingWrites = new AtomicBoolean(true);
    IllegalStateException drainFailure = new IllegalStateException("incumbent drain failed after close");
    org.mockito.Mockito.when(incumbent.recordedWriterReady()).thenReturn(true);
    org.mockito.Mockito.when(oldRuntime.isAcceptingWrites())
        .thenAnswer(ignored -> acceptingWrites.get());
    org.mockito.Mockito.doAnswer(ignored -> {
      acceptingWrites.set(false);
      throw drainFailure;
    }).when(oldRuntime).drainAndClose(
        org.mockito.ArgumentMatchers.any(java.time.Duration.class),
        org.mockito.ArgumentMatchers.eq(
            io.justsearch.adapters.lucene.runtime.SwapReason.CONFIG_RELOAD));

    server.appServices = incumbent;
    setField(server, "ingestLifecycle", oldRuntime);
    setField(server, "searchLifecycle", oldRuntime);
    setField(server, "indexGenerationManager", layout.genManager());
    setField(server, "activeIndexPath", layout.activePath());
    setField(server, "jobQueue", org.mockito.Mockito.mock(JobQueue.class));
    server.attachRecordedIngestion();
    try {
      assertTrue(server.currentRecordedServingGeneration().isPresent(),
          "the incumbent is writable before its failed drain starts");
      assertSame(drainFailure, assertThrows(IllegalStateException.class,
          () -> server.swapRuntime(
              () -> org.mockito.Mockito.mock(RunningRuntime.class),
              java.time.Duration.ofSeconds(5),
              io.justsearch.adapters.lucene.runtime.SwapReason.CONFIG_RELOAD)));
      assertFalse(acceptingWrites.get(), "the failed drain reached its non-writable state");
      assertSame(incumbent, server.appServices(),
          "failed replacement retains the incumbent composed services");
      assertTrue(server.currentRecordedServingGeneration().isEmpty(),
          "unlock cannot re-authorize an incumbent that stopped accepting writes");
      assertEquals(0, lifecycle.publications.get(),
          "a failed swap cannot announce a successful service publication");
    } finally {
      server.close();
    }
  }

  @Test
  @DisplayName("invalid or missing generation state throws, while readiness never queries jobs")
  void invalidGenerationStateThrowsAndReadinessIsIndexOnly(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = preparedLayout(tempDir);
    KnowledgeServer server = helperServer(layout);
    try {
      assertThrows(IOException.class, server::currentRecordedServingGeneration,
          "missing generation wiring must be an unavailable state, not a grant");

      setField(server, "indexGenerationManager", layout.genManager());
      setField(server, "activeIndexPath", layout.activePath());
      Files.writeString(layout.indexBase().resolve("state.json"), "{\"active_generation\":\"bad");
      IOException malformed = assertThrows(IOException.class, server::currentRecordedServingGeneration,
          "malformed authoritative state must propagate as unavailable");
      assertTrue(malformed.getCause() instanceof tools.jackson.core.JacksonException);
      assertEquals("{\"active_generation\":\"bad",
          Files.readString(layout.indexBase().resolve("state.json")),
          "strict observation must not repair malformed authority state");
      JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
      setField(server, "jobQueue", queue);

      assertFalse(server.recordedWorkerOnline(), "readiness starts false without index services");
      server.appServices = org.mockito.Mockito.mock(WorkerAppServices.class);
      setField(server, "ingestLifecycle", org.mockito.Mockito.mock(RunningRuntime.class));
      setField(server, "searchLifecycle", org.mockito.Mockito.mock(RunningRuntime.class));
      assertTrue(server.recordedWorkerOnline(),
          "readiness depends only on index runtime/service presence and needs no queue query");
      org.mockito.Mockito.verifyNoInteractions(queue);
    } finally {
      server.close();
    }
  }

  @Test
  void laterStartupFailureRetainsFailedAttachmentCleanupForRetry(@TempDir Path tempDir)
      throws Exception {
    var layout = preparedLayout(tempDir);
    var lifecycle = new CloseOnceLifecycle();
    var server = withoutDeferredModels(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle));
    var later = new IllegalStateException("reaper startup failure after attach returned");
    org.mockito.Mockito.doThrow(later).when(server)
        .startStuckJobReaper(org.mockito.ArgumentMatchers.any());
    try {
      var failure = assertThrows(IOException.class, server::start);
      assertSame(later, failure.getCause());
      assertEquals(1, later.getSuppressed().length);
      assertSame(lifecycle.closeFailure, later.getSuppressed()[0]);
      assertFalse(server.awaitClosed(0));
      assertTrue(server.jobQueueForTests().queueDepth() >= 0, "retry retains the open queue");
      server.close();
      assertTrue(server.awaitClosed(0));
      assertEquals(2, lifecycle.closeCalls.get());
    } finally {
      if (!server.awaitClosed(0)) server.close();
    }
  }

  @Test
  void malformedStateBeforeAttachmentAbortsRealBootWithoutRepair(@TempDir Path tempDir)
      throws Exception {
    var layout = preparedLayout(tempDir);
    var lifecycle = new BootLifecycle();
    var server = withoutDeferredModels(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle));
    lifecycle.server.set(server);
    String malformed = "{unfinished";
    org.mockito.Mockito.doAnswer(call -> {
      Files.writeString(layout.indexBase().resolve("state.json"), malformed);
      return call.callRealMethod();
    }).when(server).attachRecordedIngestion();
    try {
      IOException failure = assertThrows(IOException.class, server::start);
      assertTrue(failure.getCause() instanceof IOException);
      assertTrue(failure.getCause().getCause() instanceof tools.jackson.core.JacksonException);
      assertFalse(lifecycle.attached.get(), "strict preflight precedes the collaborator");
      assertEquals(malformed, Files.readString(layout.indexBase().resolve("state.json")));
      assertTrue(server.awaitClosed(0), "preflight failure still closes the real boot owners");
    } finally {
      if (!server.awaitClosed(0)) server.close();
    }
  }

  @Test
  void corruptionRestoreKeepsConstructorBoundAuthority(@TempDir Path tempDir) throws Exception {
    var layout = preparedLayout(tempDir);
    seedProcessingRecordedClaim(layout.dataDir(), tempDir.resolve("watched/restored.txt"));
    Path database = layout.dataDir().resolve("jobs.db");
    Files.copy(database, layout.dataDir().resolve("jobs.db.bak"));
    // Corrupt the schema b-tree, preserving SQLite's fixed header (JobQueueTest's fixture).
    try (var file = new java.io.RandomAccessFile(database.toFile(), "rw")) {
      assertTrue(file.length() > 1024);
      file.seek(100L);
      byte[] garbage = new byte[512];
      java.util.Arrays.fill(garbage, (byte) 0xAB);
      file.write(garbage);
    }
    var lifecycle = new BootLifecycle();
    var server = withoutDeferredModels(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle));
    lifecycle.server.set(server);
    try {
      server.start();
      assertTrue(Files.exists(layout.dataDir().resolve("jobs.db.corrupt")),
          "boot must have actually taken the corruption restore path");
      assertTrue(lifecycle.attached.get());
      assertEquals(0, lifecycle.earlyClaims.get());
      assertTrue(lifecycle.claims.get() > 0, "the restored row consults the original callback");
      assertEquals(1L, server.jobQueueForTests().jobStateCounts().processingCount(),
          "the reopened same queue remains fenced by that callback");
    } finally {
      server.close();
    }
  }

  @Test
  void recordedOwnershipPrecedesNativePrevRepairInRealBoot(@TempDir Path tempDir) throws Exception {
    var layout = preparedLayout(tempDir);
    String key = "01994180-0000-7000-8000-000000000199";
    layout.genManager().startRecordedMigration(key, "recorded-boot-test", "a".repeat(64));
    byte[] previous = Files.readAllBytes(layout.indexBase().resolve("state.json.prev"));
    String corrupt = "{unfinished recorded current";
    Files.writeString(layout.indexBase().resolve("state.json"), corrupt);
    var observed = new AtomicBoolean();
    var lifecycle = fencedBootLifecycle(observed);
    var server = withoutDeferredModels(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle));
    try {
      assertThrows(IOException.class, server::start);
      assertTrue(observed.get(), "application ownership is observed on the opened queue before initialization");
      assertEquals(corrupt, Files.readString(layout.indexBase().resolve("state.json")));
      assertArrayEquals(previous, Files.readAllBytes(layout.indexBase().resolve("state.json.prev")));
      try (var generations = Files.list(layout.indexBase().resolve("indices"))) {
        assertEquals(2, generations.count(), "boot cannot allocate a fallback generation");
      }
    } finally {
      if (!server.awaitClosed(0)) server.close();
    }
  }

  @Test
  void fencedRealBootServesCurrentBlueWithoutPollingOrReplacingRecordedGreen(@TempDir Path tempDir)
      throws Exception {
    var layout = preparedLayout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    layout.genManager().startRecordedMigration(
        "01994180-0000-7000-8000-000000000199", "recorded-boot-test", "a".repeat(64));
    byte[] state = Files.readAllBytes(layout.indexBase().resolve("state.json"));
    var observed = new AtomicBoolean();
    var server = withoutDeferredModels(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), fencedBootLifecycle(observed)));
    var loopStarts = new AtomicInteger();
    org.mockito.Mockito.doAnswer(call -> {
      var services = org.mockito.Mockito.spy((DefaultWorkerAppServices) call.callRealMethod());
      org.mockito.Mockito.doAnswer(start -> {
        loopStarts.incrementAndGet();
        return start.callRealMethod();
      }).when(services).startIndexingLoop();
      return services;
    }).when(server).newAppServices();
    try {
      server.start();
      assertTrue(observed.get());
      assertEquals(0, loopStarts.get(), "fenced Blue must not consume queued work through a read-only runtime");
      assertTrue(server.currentRecordedServingGeneration().isEmpty(), "fenced Blue grants no writer authority");
      assertArrayEquals(state, Files.readAllBytes(layout.indexBase().resolve("state.json")));
      Path target = layout.indexBase().resolve("indices/g-01994180-0000-7000-8000-000000000199");
      try (var contents = Files.list(target)) {
        assertEquals(2, contents.count(), "fenced Green remains unopened with only its ownership metadata");
      }
    } finally { server.close(); }
  }

  private static RecordedIngestionLifecycle fencedBootLifecycle(AtomicBoolean observed) {
    return new RecordedIngestionLifecycle() {
      @Override public IndexGenerationManager.BootOwnership bootOwnership(JobQueue queue) {
        assertTrue(queue.queueDepth() >= 0, "ownership observation receives the already opened queue");
        observed.set(true);
        return new IndexGenerationManager.BootOwnership.Fenced();
      }
      @Override public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
        return JobQueue.RecordedClaimDecision.DENY;
      }
      @Override public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
          java.util.function.BooleanSupplier online) { return () -> {}; }
    };
  }

  private static KnowledgeServer withoutDeferredModels(KnowledgeServer original) {
    var server = org.mockito.Mockito.spy(original);
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    return server;
  }

  private static WorkerBootFixture.Layout preparedLayout(Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    return layout;
  }

  private static void seedProcessingRecordedClaim(Path dataDir, Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "recorded boot fixture");
    try (SqliteJobQueue queue = new SqliteJobQueue(dataDir.resolve("jobs.db"), ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      JobQueue.WalkProgress walk = queue.beginRecordedWalk(OPERATION, PLAN_HASH, true);
      queue.enqueueRecordedEntries(OPERATION, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(path)), null);
      assertEquals(1, queue.pollPending(1).size(), "fixture must issue a durable recorded claim");
    }
  }

  private static KnowledgeServer helperServer(WorkerBootFixture.Layout layout) {
    return new KnowledgeServer(new TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()), null);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class BootLifecycle implements RecordedIngestionLifecycle {
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean attached = new AtomicBoolean();
    private final AtomicInteger claims = new AtomicInteger();
    private final AtomicInteger earlyClaims = new AtomicInteger();
    private final AtomicReference<KnowledgeServer> server = new AtomicReference<>();
    private volatile boolean attachmentClosedAfterServices;
    private volatile boolean queueWasOpenAtAttachmentClose;

    @Override
    public JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey) {
      events.add("claim");
      claims.incrementAndGet();
      if (!attached.get()) earlyClaims.incrementAndGet();
      return JobQueue.RecordedClaimDecision.DENY;
    }

    @Override
    public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
        java.util.function.BooleanSupplier workerOnline) {
      events.add("attach");
      attached.set(true);
      return () -> {
        events.add("attachment-close");
        attachmentClosedAfterServices = server.get().appServices() == null;
        queue.queueDepth();
        queueWasOpenAtAttachmentClose = true;
      };
    }
  }

  private static final class FailingLifecycle implements RecordedIngestionLifecycle {
    private final Throwable failure;

    FailingLifecycle(Throwable failure) { this.failure = failure; }

    @Override
    public JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey) { return JobQueue.RecordedClaimDecision.DENY; }

    @Override
    public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
        java.util.function.BooleanSupplier workerOnline) throws IOException {
      if (failure instanceof Error fatal) throw fatal;
      if (failure instanceof RuntimeException runtime) throw runtime;
      throw (IOException) failure;
    }
  }

  private static final class CloseOnceLifecycle implements RecordedIngestionLifecycle {
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final IOException closeFailure = new IOException("attachment close still live");

    @Override
    public JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey) { return JobQueue.RecordedClaimDecision.DENY; }

    @Override
    public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
        java.util.function.BooleanSupplier workerOnline) {
      return () -> {
        if (closeCalls.incrementAndGet() == 1) throw closeFailure;
      };
    }
  }

  private static final class ReplacementLifecycle implements RecordedIngestionLifecycle {
    private final AtomicReference<KnowledgeServer> server = new AtomicReference<>();
    private final AtomicInteger publications = new AtomicInteger();
    private final AtomicReference<WorkerAppServices> publishedServices = new AtomicReference<>();
    private final AtomicReference<Optional<String>> publishedGeneration = new AtomicReference<>();
    private final AtomicReference<Throwable> publicationFailure = new AtomicReference<>();

    @Override
    public JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey) {
      return JobQueue.RecordedClaimDecision.DENY;
    }

    @Override
    public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
        java.util.function.BooleanSupplier workerOnline) {
      return new Attachment() {
        @Override
        public void servicesPublished() {
          publications.incrementAndGet();
          publishedServices.set(server.get().appServices());
          try {
            publishedGeneration.set(generation.current());
          } catch (IOException failure) {
            publicationFailure.set(failure);
          }
        }

        @Override
        public void close() {}
      };
    }
  }
}
