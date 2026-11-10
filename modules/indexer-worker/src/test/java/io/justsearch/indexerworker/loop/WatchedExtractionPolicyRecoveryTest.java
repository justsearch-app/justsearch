/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WatchedExtractionPolicyRecoveryTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void realBudgetFailureBecomesSearchableAfterPolicyChangeAndRestart() throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), "searchablepolicytoken ".repeat(400));
    Path db = temporary.resolve("jobs.db");
    String key = PathNormalizer.normalizeKey(file);
    var limited = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(1000, 8));
    try (var runtime = open(); var queue = queue(db, limited)) {
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary))));
      assertEquals(0, process(runtime, queue, limited));
      assertEquals("FAILED", queue.watcherReconciliationRow(key).get("state"));
      assertEquals("BUDGET_EXCEEDED", queue.watcherReconciliationRow(key).get("last_outcome_class"));
      assertEquals(limited.policyId(), queue.watcherReconciliationRow(key).get("extraction_policy_id"));
      assertEquals(0, hits(runtime));
    }
    var lowered = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(1000, 4));
    try (var runtime = open(); var queue = queue(db, lowered)) {
      assertEquals(1, queue.enqueueMaintenanceScanEntries(
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), null, null));
      assertEquals(0, process(runtime, queue, lowered));
      assertEquals("BUDGET_EXCEEDED", queue.watcherReconciliationRow(key).get("last_outcome_class"));
      assertEquals("INPUT_TOO_LARGE", queue.watcherReconciliationRow(key).get("last_reason_code"));
      assertEquals(0, queue.enqueueMaintenanceScanEntries(
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), null, null));
      assertEquals(0, hits(runtime));
    }
    try (var runtime = open(); var queue = queue(db, TikaExtractionPolicy.defaults())) {
      assertEquals(1, queue.enqueueMaintenanceScanEntries(
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), null, null));
      assertEquals(1, process(runtime, queue, TikaExtractionPolicy.defaults()));
      assertTrue(hits(runtime) > 0);
      assertTrue(runtime.documentFieldOps().getDocumentContent(key).contains("searchablepolicytoken"));
      assertEquals("SUCCESS_FULL", queue.watcherReconciliationRow(key).get("last_outcome_class"));
      var chunks = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, key, 1000);
      assertFalse(chunks.isEmpty());
      for (var child : chunks) assertEquals(
          runtime.documentFieldOps().getDocumentField(key, SchemaFields.CHUNK_PARENT_CONTENT_SHA256),
          runtime.documentFieldOps().getDocumentField(child, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
    }
    try (var runtime = open(); var queue = queue(db, TikaExtractionPolicy.defaults())) {
      assertTrue(hits(runtime) > 0, "committed text remains searchable after restart");
      assertTrue(runtime.documentFieldOps().getDocumentContent(key).contains("searchablepolicytoken"));
      assertTrue(queue.pollPending(1).isEmpty());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var events = statement.executeQuery("SELECT outcome_class, policy_id FROM ingestion_ledger ORDER BY id")) {
      assertTrue(events.next());
      assertEquals("BUDGET_EXCEEDED", events.getString(1));
      assertEquals(limited.policyId(), events.getString(2));
      assertTrue(events.next());
      assertEquals("BUDGET_EXCEEDED", events.getString(1));
      assertEquals(lowered.policyId(), events.getString(2));
      assertTrue(events.next());
      assertEquals("SUCCESS_FULL", events.getString(1));
      assertEquals(TikaExtractionPolicy.defaults().policyId(), events.getString(2));
      assertFalse(events.next());
    }
  }

  private SqliteJobQueue queue(Path db, TikaExtractionPolicy policy) throws Exception {
    var queue = new SqliteJobQueue(db);
    queue.useExtractionPolicy(policy.policyId());
    queue.useDeclaredCollectionRoots(() -> List.of(new IngestCollectionPolicy.RootBinding(temporary, null)));
    queue.open();
    return queue;
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static long hits(RunningRuntime runtime) throws Exception {
    return runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.CONTENT, "searchablepolicytoken")),
        10, Set.of(SchemaFields.DOC_ID), null, null).totalHits();
  }

  private static int process(RunningRuntime runtime, SqliteJobQueue queue, TikaExtractionPolicy policy) throws Exception {
    try (var owners = new TestEngineExecutors();
        var parser = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool, policy);
        var timebox = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "policy-recovery", 1), parser, Duration.ofSeconds(20), null)) {
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
          mock(io.justsearch.telemetry.catalog.CounterMetric.class), () -> false);
      var authority = new WorkerIngestionAuthority();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var fence = new FileMutationFence();
      var extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
      var extracted = extractor.extractAll(queue.pollPending(2));
      for (var job : extracted) writer.write(job, null);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      journal.drainPending();
      return extracted.size();
    }
  }
}
