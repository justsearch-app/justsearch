/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.RootWitness;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerMutationAdmission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ordinary scan keys share the publication path, but are not recorded-operation identities. */
final class OrdinaryScanOriginTest {
  @TempDir Path temp;

  private final class Environment implements AutoCloseable {
    final SqliteOperationStore operations;
    final RecordedIngestionCoordinator coordinator;
    final SqliteJobQueue queue;
    final io.justsearch.indexerworker.server.RecordedIngestionLifecycle.Attachment attachment;

    Environment() throws Exception {
      Files.createDirectories(temp.resolve("authority"));
      operations = spy(new SqliteOperationStore(temp.resolve("operations.db")));
      var runner = new OperationAttemptRunnerImpl(operations, Clock.systemUTC(),
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
          new RecordedIngestPlanResolver());
      coordinator = new RecordedIngestionCoordinator(operations, runner,
          new EngineAdmissionController(2, 2, 1), OperationAuthority.load(temp.resolve("authority")));
      queue = new SqliteJobQueue(temp.resolve("jobs.db"));
      queue.open();
      attachment = coordinator.attach(queue, () -> Optional.of("ordinary-generation"), () -> true);
    }

    @Override public void close() throws Exception {
      coordinator.stopProducers(1000);
      attachment.close();
      queue.close();
      operations.close();
    }
  }

  @Test
  void missingOrdinaryV4ScanOperationStaysUnknownInsteadOfThrowing() throws Exception {
    try (var f = new Environment()) {
      Path root = Files.createDirectory(temp.resolve("root"));
      Path file = Files.writeString(root.resolve("ordinary.txt"), "ordinary content");
      String key = "00000000-0000-4000-8000-000000000001";
      assertEquals(4, UUID.fromString(key).version());
      f.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "documents", key);
      var claim = f.queue.pollPending(1).getFirst();
      assertEquals(Optional.empty(), assertDoesNotThrow(() -> f.queue.sourceOriginId(claim),
          "ordinary scan identifiers are not operation UUIDv7 keys; a missing record means unknown origin"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("ordinaryScanKeys")
  void nonImportScanKeysRetainUnknownOriginAndAllowEveryBoundary(String key) throws Exception {
    try (var f = new Environment()) {
      Path root = Files.createDirectory(temp.resolve("root"));
      Path file = Files.writeString(root.resolve("ordinary.txt"), "ordinary content");
      f.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "documents", key);
      var saved = f.queue.pollPending(1).getFirst();
      // Enqueue normalizes empty scan keys; also exercise each callback with the raw identity.
      var raw = new JobQueue.IndexJob(file, "documents", null, key, "ordinary-unit", null, false, null, root);
      for (var claim : List.of(saved, raw)) {
        assertEquals(Optional.empty(), assertDoesNotThrow(() -> f.queue.sourceOriginId(claim)));
        for (var boundary : JobQueue.IngestionBoundary.values()) {
          assertTrue(assertDoesNotThrow(() -> f.queue.allowsIngestionBoundary(boundary, key, claim)),
              boundary.toString());
        }
      }
    }
  }

  private static java.util.stream.Stream<String> ordinaryScanKeys() {
    return java.util.stream.Stream.concat(java.util.stream.Stream.of(null, "", "scan-id", "1-1-7000-8000-1",
        "00000000-0000-7000-0000-000000000001", "00000000-0000-7000-C000-000000000001",
        "00000000-0000-7000-8000-00000000000A"),
        java.util.stream.IntStream.rangeClosed(0, 8)
            .mapToObj(version -> "00000000-0000-" + version + "000-8000-000000000001"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = OperationKind.class, names = {"INGEST", "REINDEX"})
  void nonImportRecordedParentsAndChildrenKeepTheirBoundaryRules(OperationKind kind) throws Exception {
    try (var f = new Environment()) {
      Path root = Files.createDirectory(temp.resolve("root"));
      Path file = Files.writeString(root.resolve("ordinary.txt"), "ordinary content");
      var context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "non-import-test",
          Optional.empty(), Optional.of(new OperationAuthorizationBasis.StructuralAuto().encode()),
          TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(Clock.systemUTC()),
          OperationDescriptor.invocation(kind, "core.other-scan", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, java.time.Instant.now(), Optional.empty()));
      var plan = new RecordedRootPlan("ordinary-generation", List.of(
          new RecordedRootPlan.Root(root, "documents", false, false, List.of(), List.of())));
      var prepared = RecordedParentFixture.prepare(request, plan);
      f.operations.savePreparation(request.key(), request.descriptor(), prepared);
      var parent = f.operations.acceptPrepared(request.key(), request.descriptor(), context, request.provenance(), prepared.nonce()).record();
      assertTrue(f.operations.start(parent.id()));
      var child = f.operations.acceptIngestChild(parent.key(), OperationKeys.generate(Clock.systemUTC()), prepared, plan).record();
      for (String key : List.of(parent.key(), child.key())) {
        var claim = new JobQueue.IndexJob(file, "documents", null, key, "ordinary-unit", null, false, null, root);
        assertEquals(Optional.empty(), f.queue.sourceOriginId(claim));
        for (var boundary : JobQueue.IngestionBoundary.values()) {
          assertTrue(f.queue.allowsIngestionBoundary(boundary, key, claim), boundary.toString());
        }
      }
      var failure = new IllegalArgumentException("recorded parent lookup failed");
      doThrow(failure).when(f.operations).find(parent.key());
      assertSame(failure, assertThrows(IllegalArgumentException.class, () -> f.queue.allowsIngestionBoundary(
          JobQueue.IngestionBoundary.TRAVERSAL, child.key(), null)));
      doCallRealMethod().when(f.operations).find(parent.key());
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
          var delete = connection.prepareStatement("DELETE FROM operations WHERE id=?")) {
        delete.setLong(1, parent.id());
        assertEquals(1, delete.executeUpdate());
      }
      assertThrows(JobQueue.IngestionApprovalInterruptedException.class, () -> f.queue.allowsIngestionBoundary(
          JobQueue.IngestionBoundary.TRAVERSAL, child.key(), null),
          "a recorded child with a missing owner cannot become an ordinary scan");
    }
  }

  @Test
  void actualOrdinaryScanPublishesParentAndChunksWithUnknownOrigin() throws Exception {
    try (var f = new Environment()) {
      Path root = Files.createDirectory(temp.resolve("root"));
      Files.writeString(root.resolve("ordinary.txt"), "ordinary scan publication content and provenance.\n".repeat(600));
      var loop = mock(IndexingLoop.class);
      when(loop.withFileMutationFence(any()))
          .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
      var service = new WorkerIngestService(f.queue, loop, null, IndexingPacing.unthrottled(),
          null, null, null, null, null, 0L);
      Object owner = new Object();
      service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
      // Control only the root-volume precondition absent in this sandbox, not scan identity or publication.
      var witness = WorkerIngestService.class.getDeclaredField("rootWitness");
      witness.setAccessible(true);
      witness.set(service, mock(RootWitness.class));
      var progress = new ArrayList<io.justsearch.ipc.ScanRootProgress>();
      service.scanRoot(io.justsearch.ipc.ScanRootRequest.newBuilder().setRootPath(root.toString())
          .setCollection("documents").setRootAdded(true).build(), progress::add, CallContext.none());
      assertFalse(progress.isEmpty());
      String scan = progress.getLast().getScanId();
      assertEquals(4, UUID.fromString(scan).version(), "the actual worker allocates the ordinary UUIDv4 scan key");
      var claims = f.queue.pollPending(1);
      assertEquals(1, claims.size(), progress.toString());
      assertEquals(scan, claims.getFirst().scanId());
      publishOrdinaryScan(f.queue, claims, temp.resolve("index"));
      assertEquals(0, f.queue.queueDepth(), "ordinary scan publication must be acknowledged after commit");
    }
  }

  @Test
  void recordedOperationLookupFailureStillSurfaces() throws Exception {
    try (var f = new Environment()) {
      Path root = Files.createDirectory(temp.resolve("root"));
      Path file = Files.writeString(root.resolve("recorded.txt"), "recorded content");
      var context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "ordinary-scan-test",
          Optional.empty(), Optional.of(new OperationAuthorizationBasis.StructuralAuto().encode()),
          TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      String key = OperationKeys.generate(Clock.systemUTC());
      var request = new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, java.time.Instant.now(), Optional.empty()));
      var runner = new OperationAttemptRunnerImpl(f.operations, Clock.systemUTC(),
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
          new RecordedIngestPlanResolver());
      var plan = new RecordedRootPlan("ordinary-generation", List.of(
          new RecordedRootPlan.Root(root, "documents", false, false, List.of(), List.of())));
      var prepared = RecordedParentFixture.prepare(request, plan);
      runner.savePreparation(request, prepared);
      runner.acceptPrepared(request, prepared.nonce());
      assertTrue(f.operations.find(key).isPresent());
      f.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "documents", key);
      var claim = f.queue.pollPending(1).getFirst();
      // In particular, a store-side IllegalArgumentException must not be mistaken for a v4 key.
      var failure = new IllegalArgumentException("recorded operation lookup failed");
      doThrow(failure).when(f.operations).find(key);
      assertSame(failure, assertThrows(IllegalArgumentException.class, () -> f.queue.sourceOriginId(claim)));
      doCallRealMethod().doThrow(failure).when(f.operations).find(key);
      assertSame(failure, assertThrows(IllegalArgumentException.class, () -> f.queue.sourceOriginId(claim)),
          "the import-approval lookup must also propagate storage errors");
      assertSame(failure, assertThrows(IllegalArgumentException.class, () -> f.queue.allowsIngestionBoundary(
          JobQueue.IngestionBoundary.PUBLICATION, key, claim)));
    }
  }

  private static void publishOrdinaryScan(JobQueue queue, List<JobQueue.IndexJob> claims, Path index) throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var lucene = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
            new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog())
            .atPath(index).withExecutorRegistrations(lucene).open()) {
      runtime.commitOps().stopCommitTimer();
      var parser = mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
      var policy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
      when(parser.extractionPolicy()).thenReturn(policy);
      when(parser.extractArtifact(any())).thenAnswer(call ->
          io.justsearch.indexerworker.extract.ExtractionArtifact.full(new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(
              Files.readString(call.getArgument(0)), "long", "text/plain"), policy, "ordinary-scan", false));
      var metrics = mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class);
      var journal = new io.justsearch.indexerworker.loop.IngestionOutcomeJournal(queue, metrics, null, () -> false);
      var authorityType = Class.forName("io.justsearch.indexerworker.loop.WorkerIngestionAuthority");
      var authorityConstructor = authorityType.getDeclaredConstructor(); authorityConstructor.setAccessible(true);
      Object authority = authorityConstructor.newInstance();
      var roots = mock(io.justsearch.indexerworker.loop.SourcePresence.RootCheck.class);
      when(roots.admits(any())).thenReturn(true);
      when(roots.admitsFile(any())).thenReturn(true);
      var useRootCheck = authorityType.getDeclaredMethod("useRootCheck", io.justsearch.indexerworker.loop.SourcePresence.RootCheck.class);
      useRootCheck.setAccessible(true); useRootCheck.invoke(authority, roots);
      var identities = mock(io.justsearch.indexerworker.identity.DocumentIdentityStore.class);
      when(identities.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(new io.justsearch.indexerworker.identity.DocumentIdentityStore.Identity("hash", "ordinary-stable-uid", 1, 1));
      var fence = new io.justsearch.indexerworker.loop.FileMutationFence();
      var stats = new io.justsearch.indexerworker.loop.ops.BatchStats();
      var stale = mock(io.justsearch.indexerworker.loop.StaleSnapshotResolver.class);
      var extractor = (io.justsearch.indexerworker.loop.JobBatchExtractor)
          io.justsearch.indexerworker.loop.JobBatchExtractor.class.getConstructors()[0].newInstance(authority, journal, queue, parser,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(io.justsearch.indexerworker.loop.StaleSourceHandler.class),
          IndexingPacing.unthrottled(), new AtomicBoolean(true), new java.util.HashSet<>(),
          (java.util.function.Supplier<io.justsearch.indexerworker.path.PathResolutionStore>) () ->
              mock(io.justsearch.indexerworker.path.PathResolutionStore.class),
          (java.util.function.Supplier<io.justsearch.indexerworker.identity.DocumentIdentityStore>) () -> identities,
          (io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.StageRecorder) (stage, ms, reason) -> {},
          (java.util.function.BooleanSupplier) () -> false, (java.util.function.LongConsumer) ignored -> {}, fence);
      var writer = (io.justsearch.indexerworker.loop.JobBatchWriter)
          io.justsearch.indexerworker.loop.JobBatchWriter.class.getConstructors()[0].newInstance(authority, runtime.indexingCoordinator(),
          mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
          mock(io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle.class),
          (java.util.function.Supplier<io.justsearch.indexerworker.splade.SpladeEncoder>) () -> null,
          journal, queue, parser, metrics, stats, stale, (java.util.function.LongConsumer) ignored -> {},
          (io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.StageRecorder) (stage, ms, reason) -> {},
          (java.util.function.BooleanSupplier) () -> false, (java.util.function.BooleanSupplier) () -> false,
          (java.util.function.Supplier<io.justsearch.adapters.lucene.runtime.RunningRuntime>) () -> null, fence);
      for (var job : extractor.extractAll(claims)) writer.write(job, null);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      try (var directory = org.apache.lucene.store.FSDirectory.open(index); var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
        var searcher = new org.apache.lucene.search.IndexSearcher(reader);
        var rows = searcher.search(new org.apache.lucene.search.MatchAllDocsQuery(), 1000);
        assertTrue(rows.totalHits.value() > 2, "inspect a committed parent and multiple chunks");
        for (var hit : rows.scoreDocs) {
          var stored = searcher.storedFields().document(hit.doc);
          assertNull(stored.get("origin_id"), "ordinary scans without a declaration retain unknown origin");
          assertEquals("1", stored.get("envelope_version"));
          assertEquals("documents", stored.get("collection"));
        }
      }
      journal.drainPending();
    }
  }

}
