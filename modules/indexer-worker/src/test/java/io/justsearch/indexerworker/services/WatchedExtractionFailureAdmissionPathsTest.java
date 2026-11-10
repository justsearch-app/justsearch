/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.Map;
import java.util.HashMap;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
final class WatchedExtractionFailureAdmissionPathsTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"watcher", "committed-reload", "watcher-current-parent", "committed-reload-current-parent"})
  void realAutomaticAdmissionRetainsTheOneAttemptAcrossReload(String path) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("file.txt"), "source text");
    Path base = temporary.resolve("index-base");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    Path db = temporary.resolve("jobs.db");
    var binding = new IngestCollectionPolicy.RootBinding(root, "docs");
    var failure = IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("user", "HTTP")).withinRoot(root)), "docs", "scan");
      queue.markFailed(file, failure, ledger("old"));
    }
    for (int restart = 0; restart < 3; restart++) {
      try (var queue = new SqliteJobQueue(db);
          var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
              .atPath(layout.activeGenerationPath()).withExecutorRegistrations(testLuceneExecutors()).open()) {
        queue.open();
        if (restart == 0 && path.endsWith("current-parent")) {
          String key = PathNormalizer.normalizeKey(file);
          var fields = new HashMap<String, Object>(Map.of(
              SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, "uid", SchemaFields.PATH, key,
              SchemaFields.CONTENT, "earlier text", SchemaFields.COLLECTION, "docs",
              SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file),
              SchemaFields.EXTRACTION_POLICY_ID, TikaExtractionPolicy.defaults().policyId(),
              SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(), SchemaFields.SIZE_BYTES, Files.size(file)));
          String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("earlier text");
          fields.put(SchemaFields.CONTENT_SHA256, revision);
          fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
          fields.put(SchemaFields.CHUNK_TOTAL, 0);
          runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();
          assertTrue(runtime.documentFieldOps().isUnmodified(key, Files.getLastModifiedTime(file).toMillis(),
              Files.size(file), SourceContentHash.sha256(file), TikaExtractionPolicy.defaults().policyId()));
        }
        queue.useDeclaredCollectionRoots(() -> List.of(binding));
        var registry = new RootWatcherRegistry();
        var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null, IndexingPacing.unthrottled(),
            base, runtime.openedIndexPath(), runtime, runtime, null, 0L);
        service.setRootWatcherRegistry(registry);
        service.setExtractionPolicy(TikaExtractionPolicy.defaults());
        assertTrue(((RootWitness) service.rootCheck()).recordAdded(root));
        try {
          if (path.startsWith("watcher")) {
            assertTrue(registry.watch(root.toString(), "docs").watching());
            service.acceptWatcherUpsert(registry.subscription(root), "docs", file);
          } else assertTrue(service.reconcileCommittedBootRoot(binding));
          var claims = queue.pollPending(2);
          assertEquals(restart == 0 ? 1 : 0, claims.size());
          if (restart == 0) {
            assertEquals("scan", claims.getFirst().scanId());
            assertEquals("user", claims.getFirst().provenance().originator());
            assertEquals("HTTP", claims.getFirst().provenance().transport());
            assertTrue(queue.markClaimFailed(claims.getFirst(), failure,
                ledger(TikaExtractionPolicy.defaults().policyId())));
          }
          var row = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
          assertEquals("FAILED", row.get("state"));
          assertEquals("user", row.get("originator"));
          assertEquals("HTTP", row.get("transport"));
        } finally { registry.unwatch(root.toString()); }
      }
    }
  }

  private static JobQueue.IngestionLedgerEntry ledger(String policy) {
    return new JobQueue.IngestionLedgerEntry(null, "docs", null, null, "FILE", "FAILED", policy, "parser");
  }
}
