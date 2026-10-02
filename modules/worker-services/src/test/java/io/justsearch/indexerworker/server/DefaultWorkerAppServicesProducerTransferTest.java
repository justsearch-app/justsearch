/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.extract.ExtractionConfiguration;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.OcrMetricCatalog;
import io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog;
import io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog;
import io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class DefaultWorkerAppServicesProducerTransferTest {

  @Test
  void watcherTargetChangeAfterEffectFailureDoesNotRetryTheEffect() throws Exception {
    Object owner = new Object();
    var admission = new io.justsearch.indexerworker.services.WorkerMutationAdmission(owner);
    var oldIngest = mock(io.justsearch.indexerworker.services.WorkerIngestService.class);
    var successorIngest = mock(io.justsearch.indexerworker.services.WorkerIngestService.class);
    var marker = mock(io.justsearch.indexerworker.services.ConfirmedDeletionMarker.class);
    var runtime = mock(RunningRuntime.class);
    var witness = mock(
        io.justsearch.indexerworker.services.RootWatcherRegistry.Subscription.class);

    Class<?> targetType = Class.forName(
        "io.justsearch.indexerworker.server.DefaultWorkerAppServices$WatcherCallbacks$Target");
    Constructor<?> targetConstructor = targetType.getDeclaredConstructor(
        RunningRuntime.class,
        io.justsearch.indexerworker.services.WorkerIngestService.class,
        io.justsearch.indexerworker.services.ConfirmedDeletionMarker.class,
        io.justsearch.indexerworker.services.WorkerMutationAdmission.class,
        Object.class);
    targetConstructor.setAccessible(true);
    Object oldTarget = targetConstructor.newInstance(
        runtime, oldIngest, marker, admission, owner);
    Object successorTarget = targetConstructor.newInstance(
        runtime, successorIngest, marker, admission, owner);
    Class<?> callbacksType = Class.forName(
        "io.justsearch.indexerworker.server.DefaultWorkerAppServices$WatcherCallbacks");
    Constructor<?> callbacksConstructor = callbacksType.getDeclaredConstructor(targetType);
    callbacksConstructor.setAccessible(true);
    Object callbacks = callbacksConstructor.newInstance(oldTarget);
    Field selectedTarget = callbacksType.getDeclaredField("target");
    selectedTarget.setAccessible(true);
    var effects = new AtomicInteger();
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(1)).run();
      selectedTarget.set(callbacks, successorTarget);
      throw new IllegalStateException("queue effect failed after it started");
    }).when(oldIngest).acceptWatcherEvent(eq(witness), any(Runnable.class));
    Method route = callbacksType.getDeclaredMethod(
        "route",
        io.justsearch.indexerworker.services.RootWatcherRegistry.Subscription.class,
        Runnable.class);
    route.setAccessible(true);

    var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> route.invoke(callbacks, witness, (Runnable) effects::incrementAndGet));

    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals(1, effects.get());
    verify(successorIngest, never()).acceptWatcherEvent(any(), any());
  }

  @Test
  void abandonedDeferredWriterUpgradeLeavesWatcherOwnershipWithIncumbent(
      @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices deferred = fixture.newDeferredIncumbent();
      DefaultWorkerAppServices candidate = DefaultWorkerAppServices.prepareDeferredWriterUpgrade(
          fixture.executors, fixture.greenContext(), () -> false, null,
          IndexingPacing.unthrottled(),
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), fixture.configuration,
          deferred);

      try (DefaultWorkerAppServices.WatcherHandoff ignored =
          deferred.prepareDeferredWatcherHandoffTo(candidate)) {
        // Simulates DeferredRuntime.PreparedUpgrade.markPublished() refusing publication.
      }
      candidate.close();
      assertFalse(fixture.watcherExecutor.isShutdown(),
          "an abandoned candidate owns only its newly-created loop");

      deferred.close();
      assertTrue(fixture.watcherExecutor.isShutdown(),
          "the incumbent remains the exact physical watcher closer after refusal");
    }
  }

  @Test
  void deferredWriterUpgradeKeepsRegisteredPhysicalWatcherAndRoutesBlockedEventToSuccessor(
      @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      Path root = Files.createDirectory(tempDir.resolve("watched"));
      Path created = Files.writeString(root.resolve("created.txt"), "content");
      DefaultWorkerAppServices deferred = fixture.newDeferredIncumbent();
      EmbeddingProvider deferredProvider = mock(EmbeddingProvider.class);
      io.justsearch.indexerworker.splade.SpladeEncoder deferredSplade =
          mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
      deferred.wireEmbeddingProvider(deferredProvider);
      deferred.wireSpladeEncoder(deferredSplade);
      var watched = deferred.ingestService().watchRoot(
          io.justsearch.ipc.WatchRootRequest.newBuilder()
              .setRootPath(root.toString())
              .setCollection("docs")
              .build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertTrue(watched.getWatching(), watched.getErrorMessage());

      Object registry = field(deferred, "rootWatcherRegistry");
      Object watcher = field(deferred, "workerWatcher");
      Object subscription =
          invokeDeclared(registry, "subscription", new Class<?>[] {Path.class}, root);
      assertNotNull(subscription);

      DefaultWorkerAppServices successor = DefaultWorkerAppServices.prepareDeferredWriterUpgrade(
          fixture.executors, fixture.greenContext(), () -> false, null,
          IndexingPacing.unthrottled(),
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), fixture.configuration,
          deferred);
      assertSame(registry, field(successor, "rootWatcherRegistry"));
      assertSame(watcher, field(successor, "workerWatcher"));
      assertSame(deferredProvider, queryEmbeddingProvider(successor));
      assertSame(deferredProvider, producerEmbeddingProvider(successor));
      assertSame(deferredSplade, queryBindings(successor).spladeEncoder());
      assertSame(subscription,
          invokeDeclared(registry, "subscription", new Class<?>[] {Path.class}, root));

      var admitted = new CountDownLatch(1);
      when(fixture.jobQueue.enqueueEntries(any(), eq("docs"))).thenAnswer(ignored -> {
        admitted.countDown();
        return 1;
      });
      var callbackFailure = new AtomicReference<Throwable>();
      Thread callback;
      try (DefaultWorkerAppServices.WatcherHandoff handoff =
          deferred.prepareDeferredWatcherHandoffTo(successor)) {
        callback = Thread.ofVirtual().start(() -> {
          try {
            invokeWatcherEvent(watcher, subscription, "CREATE", created);
          } catch (Throwable failure) {
            callbackFailure.set(failure);
          }
        });
        assertFalse(admitted.await(100, TimeUnit.MILLISECONDS),
            "the publication fence must hold a callback until its target changes");
        handoff.install();
      }
      assertTrue(admitted.await(2, TimeUnit.SECONDS));
      callback.join(2_000L);
      assertFalse(callback.isAlive());
      assertNull(callbackFailure.get(), String.valueOf(callbackFailure.get()));

      deferred.close();
      assertFalse(fixture.watcherExecutor.isShutdown(),
          "retiring deferred services must not close the transferred physical watcher");
      assertSame(subscription,
          invokeDeclared(registry, "subscription", new Class<?>[] {Path.class}, root));

      DefaultWorkerAppServices green = DefaultWorkerAppServices.prepareNativeGreen(
          fixture.executors, fixture.nativeGreenContext(), () -> true, null,
          successor.indexingPacing(), io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          fixture.configuration, fixture.candidateConfiguration, successor);
      assertSame(registry, field(green, "rootWatcherRegistry"));
      assertSame(watcher, field(green, "workerWatcher"));
      green.close();
      successor.close();
      assertTrue(fixture.watcherExecutor.isShutdown());
    }
  }

  @Test
  void abortedSuccessorBorrowsProducerAndInstalledSuccessorBecomesItsOnlyCloser(
      @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newIncumbent();
      incumbent.startIndexingLoop();
      assertTrue(incumbent.recordedWriterReady());

      DefaultWorkerAppServices aborted = incumbent.prepareServingSuccessor(fixture.greenContext());
      aborted.close();

      assertTrue(incumbent.recordedWriterReady(), "aborting a borrower must keep the loop alive");
      assertFalse(
          fixture.watcherExecutor.isShutdown(), "aborting a borrower must keep the watcher alive");

      DefaultWorkerAppServices successor =
          incumbent.prepareServingSuccessor(fixture.greenContext());
      try (DefaultWorkerAppServices.ProducerTransfer transfer =
          incumbent.prepareProducerTransferTo(successor)) {
        transfer.install();
        transfer.install();
      }

      incumbent.close();
      assertTrue(successor.recordedWriterReady(), "the former owner must not close transferred loop");
      assertFalse(
          fixture.watcherExecutor.isShutdown(), "the former owner must not close transferred watcher");

      successor.close();
      assertFalse(successor.recordedWriterReady(), "the installed successor closes the shared loop");
      assertTrue(
          fixture.watcherExecutor.isShutdown(), "the installed successor closes the shared watcher");
    }
  }

  @Test
  void queryOnlySuccessorRetainsRunningProducerAcrossSearchViewRetirement(
      @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newIncumbent();
      incumbent.startIndexingLoop();
      assertTrue(incumbent.recordedWriterReady());

      DefaultWorkerAppServices aborted = incumbent.prepareQueryServingSuccessor(
          fixture.greenContext(), fixture.configuration.chunkReranker(),
          fixture.configuration.citationScorer());
      assertNotSame(incumbent.searchService(), aborted.searchService());
      aborted.close();
      assertTrue(incumbent.recordedWriterReady());
      assertFalse(fixture.watcherExecutor.isShutdown());

      var querySnapshot = new ResolvedConfigBuilder()
          .putSettings("justsearch.rerank.chunks.top_k", "13")
          .putSettings("justsearch.citation.scorer.threshold", "0.7")
          .build();
      var queryChunk = RerankerConfig.ChunkRerankerConfig.from(querySnapshot);
      var queryCitation = CitationScorerConfig.from(querySnapshot);
      DefaultWorkerAppServices successor = incumbent.prepareQueryServingSuccessor(
          fixture.greenContext(), queryChunk, queryCitation);
      assertSame(producerBindings(incumbent), producerBindings(successor));
      assertEquals(13, successor.chunkRerankerConfig().topK());
      assertEquals(10, incumbent.chunkRerankerConfig().topK());
      assertSame(queryCitation, field(successor, "citationScorerConfig"));
      try (DefaultWorkerAppServices.ProducerTransfer transfer =
          incumbent.prepareProducerTransferTo(successor)) {
        transfer.install();
      }

      incumbent.close();
      assertTrue(successor.recordedWriterReady());
      assertFalse(fixture.watcherExecutor.isShutdown());
      successor.close();
      assertFalse(successor.recordedWriterReady());
      assertTrue(fixture.watcherExecutor.isShutdown());
    }
  }

  @Test
  void transferRejectsWrongThreadAndInstallationAfterRelease(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newIncumbent();
      DefaultWorkerAppServices successor =
          incumbent.prepareServingSuccessor(fixture.greenContext());
      DefaultWorkerAppServices.ProducerTransfer transfer =
          incumbent.prepareProducerTransferTo(successor);

      AtomicReference<Throwable> wrongThreadFailure = new AtomicReference<>();
      Thread wrongThread =
          new Thread(
              () -> {
                try {
                  transfer.install();
                } catch (Throwable failure) {
                  wrongThreadFailure.set(failure);
                }
              },
              "wrong-producer-transfer-owner");
      wrongThread.start();
      wrongThread.join(5_000L);

      assertFalse(wrongThread.isAlive());
      assertInstanceOf(IllegalStateException.class, wrongThreadFailure.get());
      transfer.close();
      assertThrows(IllegalStateException.class, transfer::install);

      successor.close();
      incumbent.close();
      assertTrue(fixture.watcherExecutor.isShutdown());
    }
  }

  @Test
  void retiredAServiceCannotMutateAfterAdmissionTransfersToB(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newIncumbent();
      DefaultWorkerAppServices successor =
          incumbent.prepareServingSuccessor(fixture.greenContext());
      var request = io.justsearch.ipc.ClearFailedJobsRequest.getDefaultInstance();
      var call = io.justsearch.indexerworker.services.CallContext.none();
      try (var fence = incumbent.mutationAdmission().beginFinalFence(
          incumbent.mutationOwnerToken(), 1_000);
           var transfer = incumbent.prepareProducerTransferTo(successor)) {
        assertNotNull(fence);
        fence.install(successor.mutationOwnerToken());
        transfer.install();
        fence.certifySuccessor();
      }

      assertThrows(io.justsearch.indexerworker.services.WorkerServiceException.class,
          () -> incumbent.ingestService().clearFailedJobs(request, call));
      successor.ingestService().clearFailedJobs(request, call);
      verify(fixture.jobQueue).clearFailedJobs();
      successor.close();
    }
  }

  @Test
  void installerServingSuccessorKeepsAppliedConfigurationAndAllowsEncoderRecovery(
      @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newCandidateIncumbent();
      incumbent.wireCandidateProducer(null, EncoderBindings.Snapshot.empty());
      DefaultWorkerAppServices successor = incumbent.prepareServingSuccessor(fixture.greenContext());
      try {
        assertNull(field(successor, "candidateConfiguration"));
        assertSame(fixture.candidateConfiguration.snapshot(), field(successor, "resolvedConfig"));
        assertSame(fixture.candidateConfiguration.extraction(), field(successor, "extractionConfiguration"));
        assertSame(fixture.candidateConfiguration, field(incumbent, "candidateConfiguration"));
        try (var fence = incumbent.mutationAdmission().beginFinalFence(
            incumbent.mutationOwnerToken(), 1_000);
             var transfer = incumbent.prepareProducerTransferTo(successor)) {
          assertNotNull(fence);
          fence.install(successor.mutationOwnerToken());
          transfer.install();
          fence.certifySuccessor();
        }
        assertNotNull(successor.prepareTextOnlyEncoderRecoveryView(fixture.runtime));
        successor.parkProducerModelsForEncoderRecovery();
        var recovered = mock(EmbeddingProvider.class);
        successor.wireRecoveredEncoders(recovered, EncoderBindings.Snapshot.empty(), null,
            null, null, new GpuDiagnosticSuppliers(null, null, null, null, null, null, null, null, null));
        assertSame(recovered, producerEmbeddingProvider(successor));
        assertSame(recovered, queryEmbeddingProvider(successor));
        var querySuccessor = successor.prepareQueryServingSuccessor(fixture.greenContext(),
            fixture.candidateConfiguration.chunkReranker(), fixture.candidateConfiguration.citationScorer());
        try {
          assertNull(field(querySuccessor, "candidateConfiguration"));
          assertSame(fixture.candidateConfiguration.snapshot(), field(querySuccessor, "resolvedConfig"));
        } finally {
          querySuccessor.close();
        }
      } finally {
        successor.close();
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,false", "false,true"})
  void successorControllerPreparationPreservesBorrowedProducerUntilTransfer(
      boolean installer, boolean query, @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = installer
          ? fixture.newCandidateIncumbent() : fixture.newIncumbent();
      var controllerA = mock(EmbeddingCompatibilityController.class, "controller-A");
      var controllerB = mock(EmbeddingCompatibilityController.class, "controller-B");
      when(controllerA.allowEmbeddingWrites()).thenReturn(true);
      incumbent.wireEmbeddingCompatController(controllerA);
      wireProducerEmbeddings(incumbent, installer, controllerB);
      EmbeddingProviderLifecycle lifecycle = producerLifecycle(incumbent);
      DefaultWorkerAppServices successor = prepareSuccessor(fixture, incumbent, query);
      try {
        assertNull(field(successor, "candidateConfiguration"));
        assertSame(controllerB, lifecycle.embeddingCompatController());
        // KnowledgeServer post-construction wiring applies A before restoring B locally.
        successor.wireEmbeddingCompatController(controllerA);
        assertSame(controllerA, field(successor.searchService(), "embeddingCompatController"));
        assertSame(controllerB, lifecycle.embeddingCompatController());
        assertFalse(lifecycle.allowEmbeddingWrites(), "Green must keep B's write gate");
        successor.wireEmbeddingCompatController(controllerB);
        try (var transfer = incumbent.prepareProducerTransferTo(successor)) {
          transfer.install();
        }
        successor.wireEmbeddingCompatController(controllerA);
        assertSame(controllerA, lifecycle.embeddingCompatController());
        assertTrue(lifecycle.allowEmbeddingWrites(), "the transferred owner may change its gate");
        incumbent.wireEmbeddingCompatController(controllerB);
        assertSame(controllerA, lifecycle.embeddingCompatController(),
            "the retired service must not reconfigure its former producer");
      } finally {
        successor.close();
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,false", "false,true"})
  void failedSuccessorControllerWiringAndAbandonmentPreserveBorrowedProducer(
      boolean installer, boolean query, @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = installer
          ? fixture.newCandidateIncumbent() : fixture.newIncumbent();
      var controllerA = mock(EmbeddingCompatibilityController.class, "controller-A");
      var controllerB = mock(EmbeddingCompatibilityController.class, "controller-B");
      when(controllerA.allowEmbeddingWrites()).thenReturn(true);
      incumbent.wireEmbeddingCompatController(controllerA);
      wireProducerEmbeddings(incumbent, installer, controllerB);
      EmbeddingProviderLifecycle lifecycle = producerLifecycle(incumbent);
      DefaultWorkerAppServices successor = prepareSuccessor(fixture, incumbent, query);
      var search = spy(successor.searchService());
      var wiringFailure = new IllegalStateException("successor-local wiring failed before B restoration");
      doThrow(wiringFailure).when(search).setEmbeddingCompatController(controllerA);
      Field searchField = DefaultWorkerAppServices.class.getDeclaredField("searchService");
      searchField.setAccessible(true);
      searchField.set(successor, search);
      try {
        assertSame(wiringFailure, assertThrows(IllegalStateException.class,
            () -> successor.wireEmbeddingCompatController(controllerA)));
        assertSame(controllerB, lifecycle.embeddingCompatController());
        assertFalse(lifecycle.allowEmbeddingWrites());
      } finally {
        successor.close();
      }
      assertSame(controllerB, lifecycle.embeddingCompatController(),
          "abandonment must leave the incumbent's exact controller installed");
      assertFalse(lifecycle.allowEmbeddingWrites());
      assertFalse(fixture.watcherExecutor.isShutdown());
    }
  }

  @ParameterizedTest
  @CsvSource({"true,false", "true,true", "false,false", "false,true"})
  void successorProviderAndListenerPreparationPreservesBorrowedProducer(
      boolean installer, boolean query, @TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = installer
          ? fixture.newCandidateIncumbent() : fixture.newIncumbent();
      var providerB = mock(EmbeddingProvider.class, "provider-B");
      when(providerB.isAvailable()).thenReturn(true);
      if (installer) incumbent.wireCandidateProducer(providerB, EncoderBindings.Snapshot.empty());
      else incumbent.wireEmbeddingProvider(providerB);
      var incumbentQuery = queryEmbeddingProvider(incumbent);
      EmbeddingProviderLifecycle lifecycle = producerLifecycle(incumbent);
      Object listener = field(lifecycle, "embeddingProviderChangeListener");
      Object notificationTarget = field(incumbent, "embeddingProviderTarget");
      Object currentNotification = field(notificationTarget, "current");
      DefaultWorkerAppServices successor = prepareSuccessor(fixture, incumbent, query);
      var localProvider = mock(EmbeddingProvider.class, "successor-local-provider");
      var notifications = new AtomicInteger();
      try {
        successor.wireEmbeddingProvider(localProvider);
        successor.addEmbeddingProviderChangeListener(ignored -> notifications.incrementAndGet());
        assertSame(localProvider, queryEmbeddingProvider(successor));
        assertSame(incumbentQuery, queryEmbeddingProvider(incumbent));
        assertSame(providerB, lifecycle.embeddingProvider());
        assertSame(listener, field(lifecycle, "embeddingProviderChangeListener"));
        assertSame(currentNotification, field(notificationTarget, "current"));
        invokeDeclared(lifecycle, "notifyEmbeddingProviderChange",
            new Class<?>[] {EmbeddingProvider.class}, providerB);
        assertEquals(0, notifications.get(), "preparation must not register a producer listener");
        try (var transfer = incumbent.prepareProducerTransferTo(successor)) {
          transfer.install();
        }
        successor.wireEmbeddingProvider(localProvider);
        successor.addEmbeddingProviderChangeListener(ignored -> notifications.incrementAndGet());
        assertSame(localProvider, lifecycle.embeddingProvider());
        invokeDeclared(lifecycle, "notifyEmbeddingProviderChange",
            new Class<?>[] {EmbeddingProvider.class}, localProvider);
        assertEquals(1, notifications.get());
      } finally {
        successor.close();
      }
    }
  }

  @Test
  void newlyOwnedGreenLoopCanBeWiredBeforeWatcherTransfer(@TempDir Path tempDir) throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newIncumbent();
      var controllerA = mock(EmbeddingCompatibilityController.class, "controller-A");
      var controllerB = mock(EmbeddingCompatibilityController.class, "controller-B");
      incumbent.wireEmbeddingCompatController(controllerA);
      DefaultWorkerAppServices green = DefaultWorkerAppServices.prepareNativeGreen(
          fixture.executors, fixture.nativeGreenContext(), () -> true, null,
          incumbent.indexingPacing(), io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          fixture.configuration, null, incumbent);
      try {
        green.wireEmbeddingCompatController(controllerB);
        assertSame(controllerB, producerLifecycle(green).embeddingCompatController());
        assertSame(controllerA, producerLifecycle(incumbent).embeddingCompatController());
      } finally {
        green.close();
      }
    }
  }

  @Test
  void candidateProducerBindingsStayDetachedUntilTransfer(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newCandidateIncumbent();
      var aReleases = new AtomicInteger();
      var bReleases = new AtomicInteger();
      incumbent.replaceProducerModelLease(aReleases::incrementAndGet);
      EmbeddingProvider providerA = mock(EmbeddingProvider.class, "provider-A");
      EmbeddingProvider providerB = mock(EmbeddingProvider.class, "provider-B");
      io.justsearch.indexerworker.splade.SpladeEncoder spladeA =
          mock(io.justsearch.indexerworker.splade.SpladeEncoder.class, "splade-A");
      io.justsearch.indexerworker.splade.SpladeEncoder spladeB =
          mock(io.justsearch.indexerworker.splade.SpladeEncoder.class, "splade-B");

      incumbent.wireEmbeddingProvider(providerA);
      incumbent.wireSpladeEncoder(spladeA);
      assertSame(providerA, queryEmbeddingProvider(incumbent));
      assertSame(spladeA, queryBindings(incumbent).spladeEncoder());

      incumbent.replaceProducerModelLease(bReleases::incrementAndGet);
      assertEquals(1, aReleases.get(), "the B producer no longer uses A's model set");
      incumbent.wireCandidateProducer(
          providerB, new EncoderBindings.Snapshot(spladeB, null, null, null));

      // Publishing Green's producer set must not retarget Blue's still-serving query view.
      assertSame(providerA, queryEmbeddingProvider(incumbent));
      assertSame(spladeA, queryBindings(incumbent).spladeEncoder());
      assertSame(providerB, producerEmbeddingProvider(incumbent));
      assertSame(spladeB, producerBindings(incumbent).spladeEncoder());
      assertNotSame(queryBindings(incumbent), producerBindings(incumbent));

      incumbent.parkCandidateProducerModels();
      assertFalse(producerEmbeddingProvider(incumbent).isAvailable(),
          "Green must use the no-op provider after B is unloaded for approval");
      assertNull(producerBindings(incumbent).spladeEncoder());
      assertSame(providerA, queryEmbeddingProvider(incumbent),
          "parking Green must retain A's semantic query binding");
      incumbent.wireCandidateProducer(
          providerB, new EncoderBindings.Snapshot(spladeB, null, null, null));

      DefaultWorkerAppServices successor =
          incumbent.prepareServingSuccessor(fixture.greenContext());
      try (DefaultWorkerAppServices.ProducerTransfer transfer =
          incumbent.prepareProducerTransferTo(successor)) {
        transfer.install();

        assertSame(providerB, queryEmbeddingProvider(successor));
        assertSame(spladeB, queryBindings(successor).spladeEncoder());
        assertSame(providerB, producerEmbeddingProvider(successor));
        assertSame(spladeB, producerBindings(successor).spladeEncoder());

        // The retired view keeps its A snapshot, even after ownership moves to B.
        assertSame(providerA, queryEmbeddingProvider(incumbent));
        assertSame(spladeA, queryBindings(incumbent).spladeEncoder());
      }
      incumbent.close();
      assertEquals(0, bReleases.get(), "retiring A must transfer B's producer hold");
      successor.close();
      assertEquals(1, bReleases.get(), "the installed successor releases B after producer exit");
    }
  }

  @Test
  void textOnlyCandidateBuildKeepsIssuedAQueriesAndGreenProducerIndependent(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newCandidateIncumbent();
      EmbeddingProvider providerA = mock(EmbeddingProvider.class, "provider-A");
      EmbeddingProvider providerB = mock(EmbeddingProvider.class, "provider-B");
      var spladeA = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class, "splade-A");
      var spladeB = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class, "splade-B");
      incumbent.wireEmbeddingProvider(providerA);
      incumbent.wireSpladeEncoder(spladeA);
      incumbent.wireCandidateProducer(
          providerB, new EncoderBindings.Snapshot(spladeB, null, null, null));
      incumbent.wireGpuDiagnostics(new GpuDiagnosticSuppliers(
          null, null, null, () -> "A-backend", () -> 1, null, null, null, null));
      Object statusOps = field(incumbent.ingestService(), "statusOps");
      assertNotNull(field(statusOps, "embedBackendSupplier"));

      WorkerAppServices lexical = incumbent.prepareTextOnlyCandidateView(fixture.runtime);

      assertFalse(((EmbeddingProvider) field(lexical.searchService(), "embeddingProvider"))
          .isAvailable());
      assertNotSame(incumbent.healthService(), lexical.healthService());
      assertSame(providerA, queryEmbeddingProvider(incumbent));
      assertSame(spladeA, queryBindings(incumbent).spladeEncoder());
      assertNotNull(field(statusOps, "embedBackendSupplier"));
      incumbent.clearCandidateSourceStatusDiagnostics();
      assertNull(field(statusOps, "embedBackendSupplier"));
      assertNull(field(statusOps, "embedGpuLayersSupplier"));
      assertSame(providerB, producerEmbeddingProvider(incumbent));
      assertSame(spladeB, producerBindings(incumbent).spladeEncoder());

      incumbent.close();
    }
  }

  @Test
  void inPlaceSourceRetirementAndRecompositionKeepGreenProducerDetached(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices incumbent = fixture.newCandidateIncumbent();
      var aReleases = new AtomicInteger();
      var bReleases = new AtomicInteger();
      var providerA = mock(EmbeddingProvider.class, "provider-A");
      var providerB = mock(EmbeddingProvider.class, "provider-B");
      var spladeA = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class, "splade-A");
      var spladeB = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class, "splade-B");
      incumbent.replaceProducerModelLease(aReleases::incrementAndGet);
      incumbent.wireEmbeddingProvider(providerA);
      incumbent.wireSpladeEncoder(spladeA);
      WorkerAppServices lexical = incumbent.prepareTextOnlyCandidateView(fixture.runtime);
      assertSame(providerA, queryEmbeddingProvider(incumbent));
      assertFalse(((EmbeddingProvider) field(lexical.searchService(), "embeddingProvider"))
          .isAvailable());
      incumbent.clearProducerModelLease();
      assertEquals(1, aReleases.get());

      incumbent.replaceProducerModelLease(bReleases::incrementAndGet);
      incumbent.wireCandidateProducer(
          providerB, new EncoderBindings.Snapshot(spladeB, null, null, null));
      incumbent.restoreCandidateSourceQueryConfiguration();
      incumbent.wireRestoredSourceEncoders(
          new EncoderBindings.Snapshot(spladeA, null, null, null));
      incumbent.wireEmbeddingProvider(providerA);

      assertSame(providerA, queryEmbeddingProvider(incumbent));
      assertSame(spladeA, queryBindings(incumbent).spladeEncoder());
      assertSame(providerB, producerEmbeddingProvider(incumbent));
      assertSame(spladeB, producerBindings(incumbent).spladeEncoder());
      incumbent.close();
      assertEquals(1, aReleases.get(), "A's retired lease is released exactly once");
      assertEquals(1, bReleases.get(), "B's producer lease remains held until producer exit");
    }
  }

  @Test
  void transferredModelLeaseOutlivesBlockedProducerClose() throws Exception {
    var source = new DefaultWorkerAppServices.SharedProducerOwnership(true);
    var successor = new DefaultWorkerAppServices.SharedProducerOwnership(false);
    var released = new AtomicInteger();
    source.replaceModelLease(released::incrementAndGet);
    source.transferTo(successor);
    source.close(null, null);
    assertEquals(0, released.get());

    var entered = new CountDownLatch(1);
    var exit = new CountDownLatch(1);
    var loop = mock(io.justsearch.indexerworker.loop.IndexingLoop.class);
    doAnswer(ignored -> {
      entered.countDown();
      assertTrue(exit.await(5, TimeUnit.SECONDS));
      return null;
    }).when(loop).close();
    var closeFailure = new AtomicReference<Throwable>();
    Thread closer = Thread.ofVirtual().start(() -> {
      try { successor.close(null, loop); }
      catch (Throwable failure) { closeFailure.set(failure); }
    });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(0, released.get(), "native owner stays held until the producer joins");
    } finally {
      exit.countDown();
      closer.join(5_000);
    }
    assertFalse(closer.isAlive());
    assertNull(closeFailure.get());
    assertEquals(1, released.get());
  }

  @Test
  void nativeToGreenHandoffReplacesLoopButKeepsWatcherAndAQueryModels(@TempDir Path tempDir)
      throws Exception {
    try (Fixture fixture = new Fixture(tempDir)) {
      DefaultWorkerAppServices source = fixture.newIncumbent();
      var nerA = mock(io.justsearch.indexerworker.ner.NerService.class);
      source.wireNerService(nerA);
      var releasedA = new AtomicInteger();
      source.replaceProducerModelLease(releasedA::incrementAndGet);
      DefaultWorkerAppServices green = DefaultWorkerAppServices.prepareNativeGreen(
          fixture.executors, fixture.nativeGreenContext(), () -> true, null,
          source.indexingPacing(), io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          fixture.configuration, fixture.candidateConfiguration, source);
      source.validateNativeProducerSuccessor(green);
      assertNotSame(field(source, "indexingLoop"), field(green, "indexingLoop"));
      assertSame(field(source, "workerWatcher"), field(green, "workerWatcher"));
      assertSame(field(source, "rootWatcherRegistry"), field(green, "rootWatcherRegistry"));
      assertSame(nerA, queryBindings(green).nerService());

      try (var fence = source.mutationAdmission().beginFinalFence(
          source.mutationOwnerToken(), 1_000)) {
        assertNotNull(fence);
        source.closeNativeLoopRetainingModels();
        verify(nerA, never()).close();
        source.installNativeWatcherHandoffTo(green);
        fence.install(green.mutationOwnerToken());
        fence.certifySuccessor();
      }
      source.close();
      assertEquals(1, releasedA.get(), "A's old producer lease leaves with its retired view");
      verify(nerA, never()).close();
      assertFalse(fixture.watcherExecutor.isShutdown(), "Green now owns the same watcher");
      green.close();
      assertTrue(fixture.watcherExecutor.isShutdown());
      verify(nerA, never()).close();
    }
  }

  private static EncoderBindings queryBindings(DefaultWorkerAppServices services)
      throws ReflectiveOperationException {
    Object searchService = field(services, "searchService");
    Object orchestrator = field(searchService, "searchOrchestrator");
    return (EncoderBindings) field(orchestrator, "encoderBindings");
  }

  private static EncoderBindings producerBindings(DefaultWorkerAppServices services)
      throws ReflectiveOperationException {
    EncoderBindings producer = (EncoderBindings) field(services, "producerEncoderBindings");
    var loop = (io.justsearch.indexerworker.loop.IndexingLoop) field(services, "indexingLoop");
    assertSame(producer, field(loop, "encoderBindings"));
    return producer;
  }

  private static EmbeddingProvider queryEmbeddingProvider(DefaultWorkerAppServices services)
      throws ReflectiveOperationException {
    return (EmbeddingProvider) field(field(services, "searchService"), "embeddingProvider");
  }

  private static EmbeddingProvider producerEmbeddingProvider(DefaultWorkerAppServices services)
      throws ReflectiveOperationException {
    return producerLifecycle(services).embeddingProvider();
  }

  private static EmbeddingProviderLifecycle producerLifecycle(DefaultWorkerAppServices services)
      throws ReflectiveOperationException {
    var loop = (io.justsearch.indexerworker.loop.IndexingLoop) field(services, "indexingLoop");
    return loop.getEmbeddingLifecycle();
  }

  private static DefaultWorkerAppServices prepareSuccessor(
      Fixture fixture, DefaultWorkerAppServices incumbent, boolean query) {
    return query ? incumbent.prepareQueryServingSuccessor(fixture.greenContext(),
        incumbent.chunkRerankerConfig(), incumbent.citationScorerConfig())
        : incumbent.prepareServingSuccessor(fixture.greenContext());
  }

  private static void wireProducerEmbeddings(DefaultWorkerAppServices incumbent,
      boolean installer, EmbeddingCompatibilityController controller) {
    var provider = mock(EmbeddingProvider.class, "available-Green-provider");
    when(provider.isAvailable()).thenReturn(true);
    if (installer) {
      incumbent.wireCandidateProducer(provider, EncoderBindings.Snapshot.empty());
      incumbent.wireCandidateEmbeddingCompatController(controller);
    } else {
      incumbent.wireEmbeddingProvider(provider);
      incumbent.wireEmbeddingCompatController(controller);
    }
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object invokeDeclared(
      Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void invokeWatcherEvent(
      Object watcher, Object subscription, String kindName, Path path) throws Exception {
    Class<?> subscriptionType = Class.forName(
        "io.justsearch.indexerworker.services.RootWatcherRegistry$Subscription");
    Class<? extends Enum> kindType = (Class<? extends Enum>) Class.forName(
        "io.justsearch.indexerworker.services.WorkerMethvinWatcher$Kind");
    Method method = watcher.getClass().getDeclaredMethod(
        "dispatchEvent", subscriptionType, kindType, Path.class);
    method.setAccessible(true);
    method.invoke(watcher, subscription, Enum.valueOf(kindType, kindName), path);
  }

  private static final class Fixture implements AutoCloseable {
    private final io.justsearch.core.execution.TestEngineExecutors engineExecutors =
        new io.justsearch.core.execution.TestEngineExecutors();
    private final EngineExecutorRegistry.Registration ocr =
        engineExecutors.register(
            new EngineExecutorSpec("producer-test-ocr", Kind.BACKGROUND, Mode.PLATFORM, 1, 8, 1));
    private final EngineExecutorRegistry.Registration timebox =
        engineExecutors.register(
            new EngineExecutorSpec(
                "producer-test-timebox", Kind.BACKGROUND, Mode.PLATFORM, 1, 8, 1));
    private final ScheduledExecutorService watcherExecutor =
        Executors.newSingleThreadScheduledExecutor();
    private final WorkerExecutorRegistrations executors = mock(WorkerExecutorRegistrations.class);
    private final RunningRuntime runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    private final RunningRuntime greenRuntime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    private final DeferredRuntime deferredRuntime = mock(DeferredRuntime.class, RETURNS_DEEP_STUBS);
    private final JobQueue jobQueue = mock(JobQueue.class);
    private final WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
    private final ResolvedConfig snapshot =
        new ResolvedConfigBuilder()
            .putDefault("justsearch.extraction.sandbox.mode", "in_process")
            .build();
    private final NoopMetricRegistry metricRegistry = new NoopMetricRegistry(metricDefinitions());
    private final WorkerServiceConfiguration configuration =
        new WorkerServiceConfiguration(
            snapshot,
            ExtractionConfiguration.capture(
                snapshot, 1, DefaultWorkerAppServices.buildSkipPolicy(snapshot)),
            RerankerConfig.ChunkRerankerConfig.from(snapshot),
            CitationScorerConfig.from(snapshot),
            List.of());
    private final WorkerServiceConfiguration candidateConfiguration =
        new WorkerServiceConfiguration(
            snapshot,
            ExtractionConfiguration.capture(
                snapshot, 1, DefaultWorkerAppServices.buildSkipPolicy(snapshot)),
            RerankerConfig.ChunkRerankerConfig.from(snapshot),
            CitationScorerConfig.from(snapshot),
            List.of());
    private final InfraContext context;
    private DefaultWorkerAppServices incumbent;

    private Fixture(Path tempDir) {
      EngineExecutorRegistry.Registration watcher = mock(EngineExecutorRegistry.Registration.class);
      when(watcher.openScheduled(any())).thenReturn(watcherExecutor);
      when(executors.pdfOcr()).thenReturn(ocr);
      when(executors.extractionTimebox()).thenReturn(timebox);
      when(executors.watcherReconcile()).thenReturn(watcher);
      when(runtime.resolvedConfig()).thenReturn(snapshot);
      when(runtime.latestCommitUserDataBestEffort()).thenReturn(Map.of());
      context =
          new InfraContext(
              new WorkerConfig(tempDir, 1_000L, "test", Map.of(), "test", 500L),
              jobQueue,
              () -> runtime,
              () -> runtime,
              signalBus,
              null,
              metricRegistry,
              tempDir,
              tempDir.resolve("green"),
              () -> null,
              5_000L);
    }

    private DefaultWorkerAppServices newIncumbent() {
      incumbent =
          new DefaultWorkerAppServices(
              executors,
              context,
              () -> false,
              null,
              IndexingPacing.unthrottled(),
              io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
              configuration);
      return incumbent;
    }

    private DefaultWorkerAppServices newCandidateIncumbent() {
      incumbent =
          new DefaultWorkerAppServices(
              executors,
              context,
              () -> false,
              null,
              IndexingPacing.unthrottled(),
              io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
              configuration,
              candidateConfiguration);
      return incumbent;
    }

    private DefaultWorkerAppServices newDeferredIncumbent() {
      InfraContext deferredContext = new InfraContext(
          context.config(), jobQueue, () -> deferredRuntime, () -> deferredRuntime, signalBus,
          null, metricRegistry, context.indexBasePath(), context.activeIndexPath(),
          () -> null, 5_000L);
      incumbent = new DefaultWorkerAppServices(
          executors, deferredContext, () -> false, null, IndexingPacing.unthrottled(),
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), configuration);
      return incumbent;
    }

    private InfraContext greenContext() {
      return context;
    }

    private InfraContext nativeGreenContext() {
      when(greenRuntime.resolvedConfig()).thenReturn(snapshot);
      when(greenRuntime.latestCommitUserDataBestEffort()).thenReturn(Map.of());
      return new InfraContext(
          context.config(), jobQueue, () -> runtime, () -> greenRuntime, signalBus,
          null, metricRegistry, context.indexBasePath(), context.activeIndexPath(),
          () -> null, 5_000L);
    }

    @Override
    public void close() throws Exception {
      if (incumbent != null) incumbent.close();
      watcherExecutor.shutdownNow();
      engineExecutors.close();
    }
  }

  private static List<MetricDefinition> metricDefinitions() {
    List<MetricDefinition> definitions = new ArrayList<>();
    definitions.addAll(IndexingPipelineMetricCatalog.DEFINITIONS);
    definitions.addAll(ExtractionMetricCatalog.DEFINITIONS);
    definitions.addAll(OcrMetricCatalog.DEFINITIONS);
    definitions.addAll(IngestionOutcomeMetricCatalog.DEFINITIONS);
    definitions.addAll(WorkerWatcherMetricCatalog.DEFINITIONS);
    return definitions;
  }
}
