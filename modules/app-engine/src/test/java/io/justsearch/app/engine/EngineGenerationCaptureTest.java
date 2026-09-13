/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real generation authority and Engine call boundary, including replacement of the composed service. */
final class EngineGenerationCaptureTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"missing-manager", "missing-runtime", "different-runtime", "missing-state",
      "malformed-state", "migrating", "stale-target"})
  void unavailableAuthorityCannotBecomeARecordedTarget(String condition) throws Exception {
    var generations = new IndexGenerationManager(directory.resolve("index"));
    var layout = generations.initializeOrLoad();
    var runtime = runtime();
    var services = mock(WorkerAppServices.class);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var registry = new DefaultEngineExecutorRegistry()) {
      queue.open();
      var worker = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          condition.equals("missing-manager") ? null : layout.basePath(), layout.activeGenerationPath(),
          condition.equals("missing-runtime") ? null : runtime,
          condition.equals("different-runtime") ? runtime() : runtime, null, 0L);
      when(services.ingestService()).thenReturn(worker);
      if (!condition.equals("missing-manager")) {
        assertEquals(layout.activeGenerationId(), worker.activeGenerationSupplier().get(), "prime the status cache");
      }
      switch (condition) {
        case "missing-state" -> Files.delete(layout.statePath());
        case "malformed-state" -> Files.writeString(layout.statePath(), "{broken-state");
        case "migrating" -> generations.startMigration("capture-test");
        case "stale-target" -> {
          generations.startMigration("capture-test");
          generations.promoteBuildingGenerationToActive();
        }
        default -> { }
      }
      byte[] before = Files.exists(layout.statePath()) ? Files.readAllBytes(layout.statePath()) : null;
      try (var client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
        var failure = assertThrows(KnowledgeClientException.class,
            () -> client.captureServingGeneration(TestEngineContexts.FOREGROUND));
        assertEquals(KnowledgeClientException.Status.UNAVAILABLE, failure.status());
        assertEquals(0, admission.activeWorkCount());
      }
      if (before == null) assertFalse(Files.exists(layout.statePath()), "capture must not recover missing state");
      else assertArrayEquals(before, Files.readAllBytes(layout.statePath()), "capture must not rewrite authority");
      verifyNoInteractions(runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps());
    }
  }

  @Test
  void capturesFreshIdentityAndCallerContextAcrossServiceReplacement() throws Exception {
    var generations = new IndexGenerationManager(directory.resolve("index"));
    var layout = generations.initializeOrLoad();
    var runtime = runtime();
    var current = new AtomicReference<WorkerAppServices>();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var registry = new DefaultEngineExecutorRegistry()) {
      queue.open();
      var worker = spy(new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          layout.basePath(), layout.activeGenerationPath(), runtime, runtime, null, 0L));
      var services = mock(WorkerAppServices.class);
      when(services.ingestService()).thenReturn(worker);
      current.set(services);
      try (var client = new EngineKnowledgeClient(registry, current::get,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
        try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false)) {
          assertEquals(layout.activeGenerationId(), client.captureServingGeneration(owner.context()));
          var captured = org.mockito.ArgumentCaptor.forClass(CallContext.class);
          verify(worker).captureServingGeneration(captured.capture());
          // Admission returns a fresh immutable view so detach can change urgency.
          assertEquals(owner.context(), captured.getValue().engineContext());
        }
        generations.startMigration("replacement-test");
        var promoted = generations.promoteBuildingGenerationToActive();
        var stale = assertThrows(KnowledgeClientException.class,
            () -> client.captureServingGeneration(TestEngineContexts.FOREGROUND));
        assertEquals(KnowledgeClientException.Status.UNAVAILABLE, stale.status());
        current.set(null);
        var replacing = assertThrows(KnowledgeClientException.class,
            () -> client.captureServingGeneration(TestEngineContexts.FOREGROUND));
        assertEquals(KnowledgeClientException.Status.UNAVAILABLE, replacing.status());
        assertEquals(0, admission.activeWorkCount());
        var replacement = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
            layout.basePath(), generations.resolveGenerationPathStrict(promoted.active_generation()),
            runtime, runtime, null, 0L);
        var replacementServices = mock(WorkerAppServices.class);
        when(replacementServices.ingestService()).thenReturn(replacement);
        current.set(replacementServices);
        assertEquals(promoted.active_generation(), client.captureServingGeneration(TestEngineContexts.FOREGROUND));
        assertEquals(0, admission.activeWorkCount());
      }
      verifyNoInteractions(runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps());
    }
  }

  @Test
  void cancelledWorkCannotReachGenerationAuthority() {
    var services = mock(WorkerAppServices.class);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission);
        var work = admission.admit(TestEngineContexts.FOREGROUND, false)) {
      work.cancel("user_stop");
      assertThrows(EngineWorkCancelledException.class, () -> client.captureServingGeneration(work.context()));
      verifyNoInteractions(services);
    }
    assertEquals(0, admission.activeWorkCount());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void absentComposedOrSelectedServiceIsUnavailableAcrossPorts(boolean composed) throws Exception {
    var services = composed ? mock(WorkerAppServices.class) : null;
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var admission = new EngineAdmissionController(8, 8, 1);
    var load = new ForegroundLoad();
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> {
          reads.incrementAndGet();
          return services;
        }, new ForegroundLoadGate(load), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      var observed = new CompletableFuture<Throwable>();
      var completed = new CompletableFuture<Void>();
      // All five calls retain the same request owner. A closed scan handoff can still be
      // exiting after the port returns; waiting only for the later stream misses that owner.
      try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        owner.onCompletion(() -> completed.complete(null));
        var context = owner.context();
        assertUnavailable(() -> client.search("replacement", 1, context));
        assertUnavailable(() -> client.captureServingGeneration(context));
        assertUnavailable(() -> client.executeHealthRpc("replacement-health", 5_000,
            service -> fail("absent health service reached body"), context));
        assertUnavailable(() -> client.executeScanRoot(io.justsearch.ipc.ScanRootRequest.getDefaultInstance(),
            null, event -> fail("absent scan service emitted progress"), context));
        try (var _ = client.subscribeIndexingJobs(frame -> fail("absent service emitted frame"),
            observed::complete, () -> fail("absent service completed stream"), context)) {
          var failure = assertInstanceOf(KnowledgeClientException.class, observed.get(3, TimeUnit.SECONDS));
          assertEquals(KnowledgeClientException.Status.UNAVAILABLE, failure.status());
        }
      }
      completed.get(3, TimeUnit.SECONDS);
      assertEquals(5, reads.get(), "each call resolves its service exactly once");
      assertEquals(0, admission.activeWorkCount());
      assertEquals(0, load.inFlight());
    }
  }

  private static void assertUnavailable(org.junit.jupiter.api.function.Executable call) {
    var failure = assertThrows(KnowledgeClientException.class, call);
    assertEquals(KnowledgeClientException.Status.UNAVAILABLE, failure.status());
  }

  @Test
  void cancellationDuringReadWinsOverAnOtherwiseValidIdentity() throws Exception {
    var generations = new IndexGenerationManager(directory.resolve("index"));
    var layout = generations.initializeOrLoad();
    var runtime = runtime();
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      var worker = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          layout.basePath(), layout.activeGenerationPath(), runtime, runtime, null, 0L);
      var polls = new java.util.concurrent.atomic.AtomicInteger();
      var base = CallContext.none();
      var context = new CallContext(null, null, () -> polls.incrementAndGet() > 1,
          base.engineContext(), base.provenance(), base.childLifetime());
      var failure = assertThrows(WorkerServiceException.class, () -> worker.captureServingGeneration(context));
      assertEquals(WorkerServiceException.Status.CANCELLED, failure.status());
    }
  }

  private static RunningRuntime runtime() {
    var runtime = mock(RunningRuntime.class);
    when(runtime.documentFieldOps()).thenReturn(mock(DocumentFieldOps.class));
    when(runtime.indexingCoordinator()).thenReturn(mock(IndexingCoordinator.class));
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));
    return runtime;
  }
}
