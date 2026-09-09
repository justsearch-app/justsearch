package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.BackendRequest;
import io.justsearch.aibackend.backend.BackendResponse;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingRequest;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingResult;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.Provenance;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 413 followup (#4): runnable test for {@link IndexingLoop#unloadEmbeddingService} GPU
 * handoff emit. Constructs a minimal IndexingLoop with a recording
 * {@link EmbeddingTelemetryEvents}, wires a real {@link EmbeddingService} with a mock backend,
 * invokes the private {@code unloadEmbeddingService} via reflection, and asserts the
 * {@code GPU_HANDOFF} unload was recorded *before* the backend's close was observed.
 */
final class IndexingLoopUnloadTelemetryEmitTest {

  @TempDir Path tempDir;

  @Test
  void unloadEmbeddingService_emitsGpuHandoffBeforeClose() throws Exception {
    var events = new RecordingEvents();
    var backend = new TimingMockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, tempDir.resolve("fake-model"), "auto", false, 0, 0L, 2048, false, 8192);
    EmbeddingService service = EmbeddingService.createWithBackend(backend, config, events);
    assertTrue(service.isAvailable());

    IndexingLoop loop = newLoop();
    loop.getEmbeddingLifecycle().setEmbeddingTelemetryEvents(events);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(service);

    // Snapshot timestamps to verify ordering.
    long beforeUnloadNanos = System.nanoTime();
    invokeUnloadEmbeddingService(loop);
    long afterUnloadNanos = System.nanoTime();

    // Recording captured the GPU_HANDOFF unload exactly once.
    assertEquals(1L, events.count(RecordingEvents.Kind.UNLOAD));
    var unload = events.firstOf(RecordingEvents.Kind.UNLOAD);
    assertEquals(UnloadReason.GPU_HANDOFF, unload.unloadReason);

    // Backend close ran (service was unloaded for real, not just metric-emitted).
    assertNotNull(backend.closeNanos, "backend.close() must run during unload");
    assertTrue(
        backend.closeNanos >= beforeUnloadNanos && backend.closeNanos <= afterUnloadNanos,
        "backend close should happen within the unload call window");

    // Emit-before-close ordering: the recorded UNLOAD timestamp predates backend.closeNanos.
    assertTrue(
        unload.timestampNanos <= backend.closeNanos,
        "GPU_HANDOFF emit must fire before backend.close()");
  }

  @Test
  void unloadEmbeddingService_firesPrimaryAndAdditionalListeners() throws Exception {
    // observations.md fix regression: addEmbeddingProviderChangeListener
    // registers an additional listener that must fire alongside the primary
    // setEmbeddingProviderChangeListener. KnowledgeServer uses this to null
    // its `embeddingService` field when the GPU-handoff unload triggers a
    // NoOpEmbeddingProvider transition.
    var events = new RecordingEvents();
    var backend = new TimingMockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, tempDir.resolve("fake-model"), "auto", false, 0, 0L, 2048, false, 8192);
    EmbeddingService service = EmbeddingService.createWithBackend(backend, config, events);
    IndexingLoop loop = newLoop();
    loop.getEmbeddingLifecycle().setEmbeddingTelemetryEvents(events);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(service);

    AtomicBoolean primaryFired = new AtomicBoolean(false);
    AtomicBoolean additional1Fired = new AtomicBoolean(false);
    AtomicBoolean additional2Fired = new AtomicBoolean(false);
    loop.getEmbeddingLifecycle().setEmbeddingProviderChangeListener(p -> primaryFired.set(true));
    loop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(p -> additional1Fired.set(true));
    loop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(p -> additional2Fired.set(true));

    invokeUnloadEmbeddingService(loop);

    assertTrue(primaryFired.get(), "primary listener must fire");
    assertTrue(additional1Fired.get(), "first additional listener must fire");
    assertTrue(additional2Fired.get(), "second additional listener must fire");
  }

  @Test
  void unloadEmbeddingService_listenerNullsKnowledgeServerStyleFieldHolder() throws Exception {
    // observations.md fix behavioral test: KnowledgeServer registers a listener
    // that nulls its `embeddingService` field when the provider transitions to
    // NoOpEmbeddingProvider. Mirror the production wiring with a mutable holder
    // and assert the holder ends up null after unload — i.e., the bug fix
    // (field becomes null) actually works, not just that listeners fire.
    var events = new RecordingEvents();
    var backend = new TimingMockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, tempDir.resolve("fake-model"), "auto", false, 0, 0L, 2048, false, 8192);
    EmbeddingService service = EmbeddingService.createWithBackend(backend, config, events);
    IndexingLoop loop = newLoop();
    loop.getEmbeddingLifecycle().setEmbeddingTelemetryEvents(events);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(service);

    java.util.concurrent.atomic.AtomicReference<EmbeddingService> embeddingFieldHolder =
        new java.util.concurrent.atomic.AtomicReference<>(service);
    loop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(
        provider -> {
          if (provider == null
              || provider
                  instanceof io.justsearch.indexerworker.embed.NoOpEmbeddingProvider) {
            embeddingFieldHolder.set(null);
          }
        });

    invokeUnloadEmbeddingService(loop);

    assertEquals(
        null,
        embeddingFieldHolder.get(),
        "embeddingService field-equivalent holder must be nulled after unload — this is "
            + "the actual KnowledgeServer.embeddingService null-out behavior the production "
            + "listener mirrors. Asserts the bug fix end-to-end, not just that listeners fire.");
  }

  @Test
  void unloadEmbeddingService_additionalListenerExceptionDoesNotBlockPrimary() throws Exception {
    // A throwing additional listener must not stop notification of other
    // listeners (defensive isolation).
    var events = new RecordingEvents();
    var backend = new TimingMockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, tempDir.resolve("fake-model"), "auto", false, 0, 0L, 2048, false, 8192);
    EmbeddingService service = EmbeddingService.createWithBackend(backend, config, events);
    IndexingLoop loop = newLoop();
    loop.getEmbeddingLifecycle().setEmbeddingTelemetryEvents(events);
    loop.getEmbeddingLifecycle().setEmbeddingProvider(service);

    AtomicBoolean second = new AtomicBoolean(false);
    loop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(
        p -> {
          throw new RuntimeException("first additional throws");
        });
    loop.getEmbeddingLifecycle().addEmbeddingProviderChangeListener(p -> second.set(true));

    invokeUnloadEmbeddingService(loop);

    assertTrue(second.get(), "second listener must run after first throws");
  }

  @Test
  void unloadEmbeddingService_isNoOpWhenNoServiceWired() throws Exception {
    var events = new RecordingEvents();
    IndexingLoop loop = newLoop();
    loop.getEmbeddingLifecycle().setEmbeddingTelemetryEvents(events);
    // Note: no setEmbeddingProvider(service) call.

    invokeUnloadEmbeddingService(loop);

    assertEquals(
        0L,
        events.count(RecordingEvents.Kind.UNLOAD),
        "unloadEmbeddingService should not emit when no service is wired");
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 598 R4: the GPU handoff now RELEASES the embedding GPU session (yielding
  // VRAM) but keeps the EmbeddingService + provider alive so query embedding survives
  // Online — the search path keeps the same provider (no NoOp swap) and embedQuery
  // falls to the CPU session. These guard that release ≠ the former unload-to-NoOp.
  // ---------------------------------------------------------------------------

  @Test
  void releaseEmbeddingGpuSession_keepsProviderAndDoesNotNotify() {
    var events = new RecordingEvents();
    var backend = new TimingMockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, tempDir.resolve("fake-model"), "auto", false, 0, 0L, 2048, false, 8192);
    EmbeddingService service = EmbeddingService.createWithBackend(backend, config, events);

    EmbeddingProviderLifecycle lifecycle = newLifecycle(false);
    lifecycle.setEmbeddingTelemetryEvents(events);
    lifecycle.setEmbeddingProvider(service);

    AtomicBoolean primaryFired = new AtomicBoolean(false);
    AtomicBoolean additionalFired = new AtomicBoolean(false);
    lifecycle.setEmbeddingProviderChangeListener(p -> primaryFired.set(true));
    lifecycle.addEmbeddingProviderChangeListener(p -> additionalFired.set(true));

    lifecycle.releaseEmbeddingGpuSession();

    // GPU_HANDOFF telemetry still fires (VRAM is yielded for the Online chat model) ...
    assertEquals(1L, events.count(RecordingEvents.Kind.UNLOAD));
    assertEquals(
        UnloadReason.GPU_HANDOFF, events.firstOf(RecordingEvents.Kind.UNLOAD).unloadReason);
    // ... but the service stays the live provider (NOT NoOp) so embedQuery falls to CPU ...
    assertEquals(
        service,
        lifecycle.embeddingProvider(),
        "release must keep the EmbeddingService as the provider so query-embed survives Online");
    assertTrue(service.isAvailable(), "service must remain available after GPU release");
    // ... and listeners are NOT notified — search keeps issuing dense legs (no NoOp swap) ...
    assertFalse(primaryFired.get(), "release must NOT notify the primary listener (no NoOp swap)");
    assertFalse(
        additionalFired.get(), "release must NOT notify additional listeners (no NoOp swap)");
    // ... and the backend is NOT closed (unlike unloadEmbeddingService).
    assertEquals(null, backend.closeNanos, "release must NOT close the backend");
  }

  @Test
  void handleGpuStateTransition_mainClaimsGpu_releasesButKeepsProvider() {
    var events = new RecordingEvents();
    var backend = new TimingMockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, tempDir.resolve("fake-model"), "auto", /* gpuEnabled= */ true, 0, 0L, 2048, false,
            8192);
    EmbeddingService service = EmbeddingService.createWithBackend(backend, config, events);

    EmbeddingProviderLifecycle lifecycle = newLifecycle(/* mainGpuActive= */ true);
    lifecycle.setEmbeddingTelemetryEvents(events);
    lifecycle.setEmbeddingProvider(service);

    // The handoff release path is gated on embeddingProvider.isUsingGpu() (EmbeddingConfig's 4th
    // arg = gpuEnabled, set true above): a GPU embedder must yield its session; a CPU-only one is
    // left untouched.
    assertTrue(service.isUsingGpu(), "test fixture must be a GPU embedder to exercise release");

    lifecycle.handleGpuStateTransition();

    // Rising edge routes to RELEASE, not unload-to-NoOp: the live service stays the provider.
    assertEquals(service, lifecycle.embeddingProvider());
    assertTrue(service.isAvailable());
    assertEquals(1L, events.count(RecordingEvents.Kind.UNLOAD));
    assertEquals(
        null, backend.closeNanos, "handoff must release (not close) the embedding service");
  }

  @Test
  void releaseEmbeddingGpuSession_isNoOpWhenNoServiceWired() {
    var events = new RecordingEvents();
    EmbeddingProviderLifecycle lifecycle = newLifecycle(false);
    lifecycle.setEmbeddingTelemetryEvents(events);

    lifecycle.releaseEmbeddingGpuSession();

    assertEquals(0L, events.count(RecordingEvents.Kind.UNLOAD));
  }

  private EmbeddingProviderLifecycle newLifecycle(boolean mainGpuActive) {
    WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
    when(signalBus.isMainGpuActive()).thenReturn(mainGpuActive);
    return new EmbeddingProviderLifecycle(
        signalBus, mock(JobQueue.class), mock(IndexCountOps.class), mock(CommitOps.class));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private IndexingLoop newLoop() {
    JobQueue queue = mock(JobQueue.class);
    IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
    CommitOps commitOps = mock(CommitOps.class);
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
    return new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
        queue,
        coordinator,
        commitOps,
        documentFieldOps,
        indexCountOps,
        () -> null,
        signalBus,
        IndexingPacing.unthrottled(),
        null, // embeddingService — wired via setEmbeddingProvider in the test
        null, // pipelineCatalog
        null, // extractionCatalog
        null, // ingestionOutcomeCatalog
        null, // contentExtractor
        null, // encoderBindings (W7.2 — default-construct; test exercises embeddingLifecycle path)
        null  // options (W7.2 followup — default IndexingLoopOptions)
        );
  }

  private static void invokeUnloadEmbeddingService(IndexingLoop loop) throws Exception {
    // Tempdoc 516 Slice 4c: unloadEmbeddingService moved to EmbeddingProviderLifecycle.
    Method m =
        EmbeddingProviderLifecycle.class.getDeclaredMethod(
            "unloadEmbeddingService");
    m.setAccessible(true);
    m.invoke(loop.getEmbeddingLifecycle());
  }

  /** Mock {@link AiBackend} that records the wall-clock at close. */
  static final class TimingMockBackend implements AiBackend {
    Long closeNanos;

    @Override
    public BackendResponse translate(BackendRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Session createSession() {
      return new Session() {
        @Override
        public BackendResponse translate(BackendRequest request) {
          throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
          int dim = request.dimension() > 0 ? request.dimension() : 384;
          List<Double> v = new ArrayList<>(dim);
          for (int i = 0; i < dim; i++) v.add(0.1);
          return new EmbeddingResult(v, dim, Map.of());
        }

        @Override
        public List<EmbeddingResult> embedBatch(List<EmbeddingRequest> requests)
            throws BackendException {
          List<EmbeddingResult> out = new ArrayList<>(requests.size());
          for (EmbeddingRequest req : requests) {
            out.add(embed(req));
          }
          return out;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Provenance provenance() {
      return new Provenance("", "mock", 0, 384, 2048);
    }

    @Override
    public void close() {
      this.closeNanos = System.nanoTime();
    }
  }

  /** Recording {@link EmbeddingTelemetryEvents} that tracks call order with timestamps. */
  static final class RecordingEvents implements EmbeddingTelemetryEvents {

    enum Kind { CACHE_HIT, CACHE_MISS, CHUNKED, INVOKE_FAILURE, UNLOAD }

    static final class Record {
      final Kind kind;
      final UnloadReason unloadReason;
      final long timestampNanos;

      Record(Kind kind, UnloadReason unloadReason) {
        this.kind = kind;
        this.unloadReason = unloadReason;
        this.timestampNanos = System.nanoTime();
      }
    }

    final List<Record> records = new ArrayList<>();
    private final AtomicBoolean unused = new AtomicBoolean();

    @Override
    public synchronized void onCacheHit() {
      records.add(new Record(Kind.CACHE_HIT, null));
    }

    @Override
    public synchronized void onCacheMiss() {
      records.add(new Record(Kind.CACHE_MISS, null));
    }

    @Override
    public synchronized void onChunked(int chunkCount) {
      records.add(new Record(Kind.CHUNKED, null));
    }

    @Override
    public synchronized void onInvokeFailure(Operation operation, InvokeFailureReason reason) {
      unused.set(true); // suppress unused-field warning; kept for future expansion
      records.add(new Record(Kind.INVOKE_FAILURE, null));
    }

    @Override
    public synchronized void onUnload(UnloadReason reason) {
      records.add(new Record(Kind.UNLOAD, reason));
    }

    long count(Kind kind) {
      return records.stream().filter(r -> r.kind == kind).count();
    }

    Record firstOf(Kind kind) {
      return records.stream()
          .filter(r -> r.kind == kind)
          .findFirst()
          .orElseThrow(() -> new AssertionError("no record of kind " + kind));
    }
  }
}
