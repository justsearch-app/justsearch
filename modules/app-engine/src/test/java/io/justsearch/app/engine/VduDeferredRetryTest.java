/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.vdu.VduBatchProcessor;
import io.justsearch.app.services.vdu.VduCapabilityState;
import io.justsearch.app.services.vdu.VduMetricCatalog;
import io.justsearch.app.services.vdu.VduOfflineTriggerSampler;
import io.justsearch.app.services.vdu.VduPacingPolicy;
import io.justsearch.app.services.vdu.VduProcessor;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.app.util.EnergyState;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A picture-reading pass whose vision call meets a closed generation gate (a generative settings
 * change drains chat while the page renders) defers the document: it is not the document's failure.
 * Over the real index half (Lucene index, WorkerIngestService, the Engine's in-process client) and
 * the production pass owner and idle trigger, the deferred document is picked up again by a later
 * idle tick, and temporary refusals never spend the attempts that protect against a document that
 * really fails.
 */
@Timeout(120)
final class VduDeferredRetryTest {
  @TempDir Path directory;

  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final LuceneExecutorRegistrations luceneExecutors = new LuceneExecutorRegistrations(executors);
  private final GenerationTracker generation = new GenerationTracker(work -> () -> {}, Runnable::run);
  private final AtomicInteger passes = new AtomicInteger();
  private final AtomicInteger attempts = new AtomicInteger();
  /** The automatic-pass breaker's back-off clock (b100); advanced by the test, never slept on. */
  private final java.util.concurrent.atomic.AtomicLong breakerClock = new java.util.concurrent.atomic.AtomicLong();
  private SqliteJobQueue queue;
  private RunningRuntime runtime;
  private DefaultEngineExecutorRegistry registry;
  private EngineKnowledgeClient client;
  private OfflineCoordinator coordinator;
  private VduOfflineTriggerSampler sampler;
  private String docId;

  @BeforeEach
  void setUp() throws Exception {
    Path picture = Files.writeString(directory.resolve("scan.png"), "scan");
    docId = io.justsearch.indexerworker.util.PathNormalizer.normalizePath(picture.toAbsolutePath().toString());
    assertTrue(Files.exists(Path.of(docId)), "the pass reads the document at its id");
    var layout = new IndexGenerationManager(directory.resolve("index")).initializeOrLoad();
    var catalogFields=new java.util.ArrayList<>(FieldCatalogDef.forChunkTestingWithVduRetryCount(0).fields());
    catalogFields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256,"keyword",true,false,java.util.List.of(),null,null,false));
    runtime = IndexSchema.fromCatalog(new FieldCatalogDef("deferred-vdu-revision",catalogFields))
        .atPath(layout.activeGenerationPath()).withExecutorRegistrations(luceneExecutors).open();
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString());
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.CONTENT, "baseline");
    fields.put(SchemaFields.CONTENT_PREVIEW, "baseline");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);
    fields.put(SchemaFields.SOURCE_SHA256,"a".repeat(64));
    fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    queue = new SqliteJobQueue(directory.resolve("jobs.db"));
    queue.open();
    var worker = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        layout.basePath(), layout.activeGenerationPath(), runtime, runtime, null, 0L);
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(worker);
    registry = new DefaultEngineExecutorRegistry();
    var admission = new EngineAdmissionController(8, 8, 1);
    client = new EngineKnowledgeClient(registry, () -> services,
        new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
        admission, WatchedRootsState.inMemory());

    // The vision call takes generation admission as the production model call does: a closed gate
    // refuses it with GenerationUnavailableException, wrapped as VduProcessor wraps model failures.
    var processor = mock(VduProcessor.class);
    when(processor.hasVisionCapability()).thenReturn(true);
    org.mockito.Mockito.doAnswer(call -> { passes.incrementAndGet(); return null; })
        .when(processor).enterVduMode();
    when(processor.process(any(), any())).thenAnswer(call -> {
      attempts.incrementAndGet();
      try {
        generation.requireOpen();
      } catch (RuntimeException refused) {
        throw new VduProcessor.VduException("vision call failed", refused);
      }
      return new VduProcessor.VduResult("page text", "{}", 1);
    });
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(new GpuCapabilities(null, null, new GpuCapabilities.Effective(
        true, "test", GpuCapabilities.Confidence.HIGH, "1.0", 1, 0, 1, 24_000_000_000L,
        24_000_000_000L, 0L, GpuCapabilities.Cuda.unknown())));
    var capability = new VduCapabilityState();
    var batch = new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(), capability);
    coordinator = coordinator(admission, batch, capability);

    // Nobody has used the product for longer than the idle threshold, on mains power.
    var idle = mock(KnowledgeServerBootstrap.class);
    when(idle.msSinceLastUserActivity(anyLong())).thenReturn(VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS + 60_000);
    when(idle.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    sampler = idleSampler(idle);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (sampler != null) sampler.stop();
      if (coordinator != null) coordinator.close();
      if (client != null) client.close();
      if (registry != null) registry.close();
      if (queue != null) queue.close();
    } finally {
      try { if (runtime != null) runtime.close(); }
      finally {
        try { luceneExecutors.close(); }
        finally { executors.close(); }
      }
    }
  }

  @ParameterizedTest(name = "{0} refused vision call(s), then admission reopens")
  @ValueSource(ints = {1, 3})
  void lastDeferredVduDocumentIsAutomaticallyRetriedAfterConfigDrain(int refusals) throws Exception {
    try (ChatActuator.DrainHold _ = generation.closeNow()) {
      for (int tick = 1; tick <= refusals; tick++) {
        idleTick();
        assertEquals(tick, passes.get(), "idle tick " + tick + " starts a pass for the deferred document");
        assertEquals(tick, attempts.get(), "the pass reaches the document's vision call");
        assertEquals(SchemaFields.VDU_STATUS_PENDING, status(),
            "a refused vision call leaves the document waiting for a later pass, not stuck mid-processing");
      }
    }
    // Adaptation (b100): three refused automatic passes in a row pause automatic picture reading;
    // the half-open probe after the five-minute back-off is the pass that reads the document.
    if (refusals >= 3) breakerClock.addAndGet(TimeUnit.MINUTES.toNanos(5));
    idleTick();
    assertEquals(refusals + 1, passes.get(), "the next idle tick after the drain retries the document");
    assertEquals(SchemaFields.VDU_STATUS_COMPLETED, status(),
        "the deferred document is read once generation is admitted again");
  }

  @Test
  void deferralsDoNotSpendTheRetryBudget() throws Exception {
    // Passes started directly (as a person's Run now does) recover stuck documents first, so this
    // isolates the attempt count from automatic scheduling.
    try (ChatActuator.DrainHold _ = generation.closeNow()) {
      for (int pass = 1; pass <= SchemaFields.VDU_MAX_RETRIES; pass++) {
        var outcome = runPass();
        assertEquals(OfflineProcessingOutcome.BlockReason.AI_OFFLINE, outcome.blockedReason());
        assertEquals(0, outcome.failed(), "a refused admission is not the document's failure");
      }
    }
    var admitted = runPass();
    assertEquals(1, admitted.processed(), "the first admitted attempt reads the document");
    assertEquals(SchemaFields.VDU_STATUS_COMPLETED, status(),
        "temporary refusals do not make a healthy document fail for want of retries");
  }

  /**
   * The sampler's idle time is capped by process uptime (a fresh process is not idle); this JVM has
   * been running for less than the idle threshold, so the test supplies an uptime past it.
   */
  private VduOfflineTriggerSampler idleSampler(KnowledgeServerBootstrap idle) throws Exception {
    java.util.function.Supplier<OfflineCoordinator> coordinators = () -> coordinator;
    java.util.function.Supplier<KnowledgeServerBootstrap> hosts = () -> idle;
    java.util.function.BooleanSupplier unclaimed = () -> false;
    try {
      var clocked = VduOfflineTriggerSampler.class.getDeclaredConstructor(
          io.justsearch.core.execution.EngineExecutorRegistry.class, java.util.function.Supplier.class,
          java.util.function.Supplier.class, java.util.function.BooleanSupplier.class,
          java.util.function.Supplier.class, java.util.function.LongSupplier.class);
      clocked.setAccessible(true);
      return clocked.newInstance(registry, coordinators, hosts, unclaimed,
          (java.util.function.Supplier<?>) () -> null,
          (java.util.function.LongSupplier) () -> VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS + 60_000);
    } catch (NoSuchMethodException beforeUptimeFloor) {
      return new VduOfflineTriggerSampler(registry, coordinators, hosts, unclaimed);
    }
  }

  private OfflineCoordinator coordinator(EngineAdmissionController admission, VduBatchProcessor batch,
      VduCapabilityState capability) throws Exception {
    java.util.function.Supplier<KnowledgeClient> clients = () -> client;
    try {
      return OfflineCoordinator.class.getConstructor(io.justsearch.core.execution.EngineExecutorRegistry.class,
              io.justsearch.app.api.EngineAdmissionService.class, VduBatchProcessor.class,
              java.util.function.Supplier.class, VduCapabilityState.class,
              java.util.function.LongSupplier.class)
          .newInstance(registry, admission, batch, clients, capability,
              (java.util.function.LongSupplier) breakerClock::get);
    } catch (NoSuchMethodException beforeBreaker) {
      return new OfflineCoordinator(registry, admission, batch, clients, capability);
    }
  }

  private void idleTick() throws Exception {
    var checkOnce = VduOfflineTriggerSampler.class.getDeclaredMethod("checkOnce");
    checkOnce.setAccessible(true);
    checkOnce.invoke(sampler);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (coordinator.isProcessing()) {
      if (System.nanoTime() > deadline) throw new AssertionError("the pass did not finish");
      Thread.sleep(10);
    }
  }

  private OfflineProcessingOutcome runPass() throws Exception {
    return coordinator.startOfflineProcessing(TestEngineContexts.BACKGROUND, outcome -> {})
        .toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  private String status() throws Exception {
    runtime.commitOps().maybeRefreshBlocking();
    return runtime.documentFieldOps().getDocumentFieldOrThrow(docId, SchemaFields.VDU_STATUS);
  }
}
