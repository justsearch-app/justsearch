/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

final class CommittedRootSyncSurvivorsTest extends LuceneExecutorTestBase {
  private static final String GENERATION = "g-20261028-000000";
  private static final String HASH = "a".repeat(64);
  @TempDir Path temp;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"prefix", "collection"})
  void restoringSyncRetainsParentAndChunkAcrossTwoRestarts(String deletion) throws Exception {
    Path root = Files.createDirectories(temp.resolve("root"));
    Path file = Files.writeString(root.resolve("restored.txt"), "restored file");
    Path database = temp.resolve("jobs.db");
    try (var queue = queue(); var runtime = runtime()) {
      delete(queue, deletion, root);
      sync(queue, root);
      index(queue, runtime, file, root, "archive");
    }
    // The source need not still be online for its accepted, committed sync effect to survive.
    Files.move(root, temp.resolve("offline-root"));
    try (var raw = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = raw.createStatement()) {
      sql.execute("CREATE TRIGGER hold_receipts BEFORE DELETE ON switch_buffer BEGIN SELECT RAISE(IGNORE); END");
    }
    for (int restart = 0; restart < 2; restart++) {
      try (var queue = queue(); var runtime = runtime()) {
        assertFalse(settle(queue, runtime), "refused acknowledgement retains the whole snapshot");
        assertEquals(2, queue.listSwitchBufferOpsStrict().size());
        assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      }
    }
    try (var raw = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = raw.createStatement()) {
      sql.execute("DROP TRIGGER hold_receipts");
    }
    try (var queue = queue(); var runtime = runtime()) {
      assertTrue(settle(queue, runtime), "the restored sync effect is an accepted survivor of its earlier broad delete");
      assertTrue(queue.listSwitchBufferOpsStrict().isEmpty());
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"prefix", "collection"})
  void syncCannotExcuseAFileOutsideItsQueueRoot(String deletion) throws Exception {
    Path root = Files.createDirectories(temp.resolve("root"));
    Path nested = Files.createDirectories(root.resolve("nested"));
    Path file = Files.writeString(nested.resolve("unrelated.txt"), "another root owns it");
    try (var queue = queue(); var runtime = runtime()) {
      delete(queue, deletion, root);
      sync(queue, root);
      index(queue, runtime, file, nested, "archive");
      var receipts = queue.listSwitchBufferOpsStrict();
      assertFalse(settle(queue, runtime));
      assertEquals(receipts, queue.listSwitchBufferOpsStrict());
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    }
  }

  private SqliteJobQueue queue() throws Exception {
    var queue = new SqliteJobQueue(temp.resolve("jobs.db")); queue.open(); return queue;
  }

  private RunningRuntime runtime() {
    var fields = new java.util.ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256, "keyword", true, false,
        List.of(), null, null, false));
    return withTestExecutors(IndexSchema.fromCatalog(new FieldCatalogDef("sync-survivors", fields))
        .atPath(temp.resolve("index"))).open();
  }

  private static boolean settle(SqliteJobQueue queue, RunningRuntime runtime) {
    return KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue, runtime,
        GENERATION, List.of(), LoggerFactory.getLogger(CommittedRootSyncSurvivorsTest.class));
  }

  private static void delete(SqliteJobQueue queue, String kind, Path root) {
    String prefix = root.toString();
    assertTrue(kind.equals("prefix")
        ? queue.putSwitchBufferForGeneration(GENERATION, "prefix:" + prefix, "DELETE_PREFIX",
            new PathPrefixScope(prefix, List.of()).encode())
        : queue.putSwitchBufferForGeneration(GENERATION, "collection:archive", "DELETE_COLLECTION", "archive"));
  }

  private static void sync(SqliteJobQueue queue, Path root) {
    assertTrue(queue.putSyncRootForGeneration(GENERATION, "sync_root:" + root,
        new SwitchBufferSyncRoot(root.toString(), true, null)));
  }

  private static void index(SqliteJobQueue queue, RunningRuntime runtime, Path file,
      Path root, String collection) {
    queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file, 1).withinRoot(root)), collection);
    var claim = queue.pollPending(1).getFirst();
    String path = file.toString();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, path, SchemaFields.DOC_UID, path + "#0", SchemaFields.PATH, path,
        SchemaFields.COLLECTION, collection, SchemaFields.CONTENT, "restored",
        SchemaFields.SOURCE_SHA256, HASH)));
    String chunk = io.justsearch.indexing.chunking.ChunkIds.newChunkDocId();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, chunk, SchemaFields.DOC_UID, chunk,
        SchemaFields.PATH, path, SchemaFields.COLLECTION, collection,
        SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, path,
        SchemaFields.CONTENT, "restored chunk")));
    runtime.commitOps().commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, HASH)),
        IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE));
  }
}
