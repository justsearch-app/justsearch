/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.Map;
import java.util.HashMap;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class WatchedExtractionFailurePolicyTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest
  @EnumSource(value = IngestionOutcomeClass.class,
      names = {"BUDGET_EXCEEDED", "PARSER_FAILED", "PARSER_TIMEOUT", "UNSUPPORTED"})
  void terminalExtractionGetsOneAttemptPerPolicyAcrossRestartAndPruning(IngestionOutcomeClass failure) throws Exception {
    check(failure, "older-policy", false, false);
  }

  @Test
  void legacyUnknownPolicyGetsOneDurableAttempt() throws Exception {
    check(IngestionOutcomeClass.PARSER_FAILED, null, false, false);
  }

  @Test
  void unrelatedLatestLedgerPolicyCannotStrandOlderFailedWork() throws Exception {
    check(IngestionOutcomeClass.PARSER_FAILED, "older-policy", true, false);
  }

  @Test
  void reusableIndexedParentCannotHideAnOlderTerminalExtractionFailure() throws Exception {
    check(IngestionOutcomeClass.PARSER_FAILED, "older-policy", false, true);
  }

  @Test
  void exclusionsKeepTheFailureUntilTheFileIsAuthorizedAgain() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("file.txt"), "unchanged source");
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
        var queue = open(temporary.resolve("jobs.db"), root)) {
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs", "scan");
      queue.markFailed(file, failed(IngestionOutcomeClass.PARSER_FAILED), ledger(file, "old"));
      var before = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
      var sync = new SyncDirectoryOps(runtime.readPathOps(), null, null, queue, IndexingPacing.unthrottled());
      sync.setDocumentFieldOps(runtime.documentFieldOps());
      sync.setUserExclusions((candidate, boundary) -> true);
      assertEquals(0, sync.execute(root.toString(), false, null, () -> false, null,
          SyncDirectoryOps.AdmissionMode.MAINTENANCE).getFilesAdded());
      assertEquals(before, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
      sync.setUserExclusions((candidate, boundary) -> false);
      assertEquals(1, sync.execute(root.toString(), false, null, () -> false, null,
          SyncDirectoryOps.AdmissionMode.MAINTENANCE).getFilesAdded());
    }
  }

  private void check(IngestionOutcomeClass failure, String priorPolicy, boolean unrelatedEvent, boolean indexedParent) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("file.txt"), "unchanged source");
    Path db = temporary.resolve("jobs.db");
    String key = PathNormalizer.normalizeKey(file);
    String policy = TikaExtractionPolicy.defaults().policyId();
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      if (indexedParent) {
        var fields = new HashMap<String, Object>(Map.of(
            SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, "uid", SchemaFields.PATH, key,
            SchemaFields.CONTENT, "old searchable text", SchemaFields.COLLECTION, "docs",
            SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
            SchemaFields.SIZE_BYTES, Files.size(file), SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file),
            SchemaFields.EXTRACTION_POLICY_ID, policy));
        String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("old searchable text");
        fields.put(SchemaFields.CONTENT_SHA256, revision);
        fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
        fields.put(SchemaFields.CHUNK_TOTAL, 0);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
        runtime.commitOps().commitAndTrack();
        runtime.commitOps().maybeRefreshBlocking();
        assertTrue(runtime.documentFieldOps().isUnmodified(key, Files.getLastModifiedTime(file).toMillis(),
            Files.size(file), SourceContentHash.sha256(file), policy), "fixture must be a reusable indexed family");
      }
      try (var queue = open(db, root)) {
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file,
            new JobQueue.EnqueueProvenance("user", "HTTP")).withinRoot(root)), "docs", "original-scan");
        queue.markFailed(file, failed(failure), priorPolicy == null ? null : ledger(file, priorPolicy));
        if (unrelatedEvent) queue.recordIngestionEvent(file,
            IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "unrelated.event", IngestionRetryPolicy.NONE),
            ledger(file, policy));
      }
      for (int restart = 0; restart < 3; restart++) {
        try (var queue = open(db, root)) {
          var sync = new SyncDirectoryOps(runtime.readPathOps(), null, null, queue, IndexingPacing.unthrottled());
          sync.setDocumentFieldOps(runtime.documentFieldOps());
          sync.setExtractionPolicyId(policy);
          if (indexedParent) {
            try (var difference = sync.discoverCandidateDifference(root.toString(), false, () -> false)) {
              assertEquals(restart == 0 ? List.of(file) : List.of(), difference.additions());
            }
          }
          var result = sync.execute(root.toString(), false, null, () -> false, null,
              SyncDirectoryOps.AdmissionMode.MAINTENANCE);
          assertEquals("", result.getError());
          assertEquals(restart == 0 ? 1 : 0, result.getFilesAdded(),
              "older or unknown extraction policy must admit exactly one attempt, durably");
          if (restart == 0) {
            var claim = queue.pollPending(1).getFirst();
            assertEquals("docs", claim.collection());
            assertEquals("original-scan", claim.scanId());
            assertEquals("HTTP", claim.provenance().transport());
            assertTrue(queue.markClaimFailed(claim, failed(failure), ledger(file, policy)));
          } else assertTrue(queue.pollPending(1).isEmpty());
          assertEquals("FAILED", queue.watcherReconciliationRow(key).get("state"));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var statement = connection.createStatement()) {
          statement.execute("DELETE FROM ingestion_ledger");
        }
      }
    }
  }

  private static SqliteJobQueue open(Path db, Path root) throws Exception {
    var queue = new SqliteJobQueue(db);
    queue.useDeclaredCollectionRoots(() -> List.of(new IngestCollectionPolicy.RootBinding(root, "docs")));
    queue.open();
    return queue;
  }

  private static IngestionOutcome failed(IngestionOutcomeClass failure) {
    return IngestionOutcome.of(failure, "fixture.extraction", IngestionRetryPolicy.NONE);
  }

  private static JobQueue.IngestionLedgerEntry ledger(Path file, String policy) throws Exception {
    return new JobQueue.IngestionLedgerEntry(null, "docs", Files.size(file),
        Files.getLastModifiedTime(file).toMillis(), "FILE", "FAILED", policy, "fixture-parser", "user", "HTTP");
  }
}
