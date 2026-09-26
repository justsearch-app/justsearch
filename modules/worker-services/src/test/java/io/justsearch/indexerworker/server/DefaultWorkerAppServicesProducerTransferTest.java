/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.extract.ExtractionConfiguration;
import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.OcrMetricCatalog;
import io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog;
import io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.NoopMetricRegistry;
import java.lang.reflect.Field;
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

final class DefaultWorkerAppServicesProducerTransferTest {

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
    var loop = (io.justsearch.indexerworker.loop.IndexingLoop) field(services, "indexingLoop");
    return loop.getEmbeddingLifecycle().embeddingProvider();
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
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

    private InfraContext greenContext() {
      return context;
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
