/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Boot-level proof for committed B root replay and predecessor retention. */
@Timeout(180)
@DisplayName("Committed boot root reconciliation")
final class CommittedBootRootReconciliationTest {

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort
      }
      server = null;
    }
  }

  @Test
  @DisplayName("a missing declared root fails boot while retaining both committed generations")
  void missingDeclaredRootFailsBootAndRetainsPredecessor(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    String building =
        layout.genManager().startMigration("committed_boot_missing_root").building_generation();
    Path green = layout.genManager().resolveGenerationPathStrict(building);
    WorkerBootFixture.seedDocument(green, null, "committed-b.txt", "committed-b#0", "B");
    IndexGenerationManager.State committed = layout.genManager().promoteBuildingGenerationToActive();
    Path missingRoot = tempDir.resolve("declared-root-that-is-absent");
    assertFalse(Files.exists(missingRoot));

    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(List.of(new RootBinding(missingRoot, null)));

    IOException failure = assertThrows(IOException.class, server::start);
    assertTrue(
        failure.getMessage().contains("Failed to start KnowledgeServer"),
        "missing root must fail the committed successor boot: " + failure);

    IndexGenerationManager.State after = layout.genManager().readStateBestEffort();
    assertNotNull(after);
    assertEquals(committed.active_generation(), after.active_generation());
    assertEquals(committed.previous_generation(), after.previous_generation());
    assertTrue(
        Files.isDirectory(
            layout.genManager().resolveGenerationPathStrict(after.active_generation())));
    assertTrue(
        Files.isDirectory(
            layout.genManager().resolveGenerationPathStrict(after.previous_generation())));
  }

  @Test
  @DisplayName("committed boot reconciles additions and deletions before retiring A")
  void committedBootReconcilesDiskDifferenceBeforeRetiringPredecessor(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    Path root = Files.createDirectories(tempDir.resolve("watched-root"));
    Path addition = root.resolve("added.txt");
    Files.writeString(addition, "added on disk after B was built");
    Path relabeled = root.resolve("relabeled.txt");
    Files.writeString(relabeled, "unchanged bytes with an obsolete B collection");
    Path changed = root.resolve("changed.txt");
    Files.writeString(changed, "B had these old bytes");
    Path preservedTime = root.resolve("preserved-time.txt");
    Files.writeString(preservedTime, "old bytes");
    var originalTime = Files.getLastModifiedTime(preservedTime);
    Path delayedCollection = root.resolve("delayed-collection.txt");
    Files.writeString(delayedCollection, "B already has the right bytes and label");
    Path stale = root.resolve("stale.txt");
    String staleDocId = PathNormalizer.normalizeKey(stale);

    String building =
        layout.genManager().startMigration("committed_boot_root_replay").building_generation();
    Path green = layout.genManager().resolveGenerationPathStrict(building);
    WorkerBootFixture.seedDocument(green, null, staleDocId, "stale#0", "stale B document");
    seedFileWithCollection(green, relabeled, "obsolete");
    seedFileWithCollection(green, changed, "obsolete");
    seedFileWithCollection(green, preservedTime, null);
    seedFileWithCollection(green, delayedCollection, null);
    IndexGenerationManager.State committed = layout.genManager().promoteBuildingGenerationToActive();
    Files.writeString(changed, "disk changed at the same path after B was built");
    Files.writeString(preservedTime, "new bytes");
    Files.setLastModifiedTime(preservedTime, originalTime);

    Path unrelatedPending = tempDir.resolve("unrelated-pending.txt");
    Files.writeString(unrelatedPending, "unrelated durable backlog");
    try (SqliteJobQueue queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"), 1)) {
      queue.open();
      assertEquals(1, queue.enqueue(List.of(unrelatedPending)));
      assertEquals(1, queue.enqueue(List.of(delayedCollection), "obsolete"));
      assertTrue(queue.putSwitchBufferForGeneration(committed.active_generation(),
          "path:" + staleDocId, "DELETE", staleDocId));
    }
    // Keep unrelated durable PENDING work outside this root non-runnable throughout boot.
    // Retirement must be scoped to this committed generation's receipts and watched roots.
    try (var connection = DriverManager.getConnection(
            "jdbc:sqlite:" + layout.dataDir().resolve("jobs.db").toAbsolutePath());
        var update = connection.prepareStatement(
            "UPDATE jobs SET retry_after = ? WHERE path = ?")) {
      update.setLong(1, System.currentTimeMillis() + 600_000);
      update.setString(2, PathNormalizer.normalizeKey(unrelatedPending));
      assertEquals(1, update.executeUpdate());
      update.setString(2, PathNormalizer.normalizeKey(delayedCollection));
      assertEquals(1, update.executeUpdate());
    }

    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(List.of(new RootBinding(root, null)));
    server.start();

    var fields = server.lifecycleManagerForTests().documentFieldOps();
    String additionDocId = PathNormalizer.normalizeKey(addition);
    assertEquals(SourceContentHash.sha256(addition), fields.getDocumentField(
        additionDocId, SchemaFields.SOURCE_SHA256));
    assertNull(fields.getDocumentField(staleDocId, SchemaFields.DOC_UID));
    assertEquals(SourceContentHash.sha256(relabeled), fields.getDocumentField(
        PathNormalizer.normalizeKey(relabeled), SchemaFields.SOURCE_SHA256));
    assertNull(fields.getDocumentField(
        PathNormalizer.normalizeKey(relabeled), SchemaFields.COLLECTION),
        "the persisted default root label must clear B's obsolete collection");
    assertEquals(SourceContentHash.sha256(changed), fields.getDocumentField(
        PathNormalizer.normalizeKey(changed), SchemaFields.SOURCE_SHA256));
    assertEquals(SourceContentHash.sha256(preservedTime), fields.getDocumentField(
        PathNormalizer.normalizeKey(preservedTime), SchemaFields.SOURCE_SHA256),
        "changed bytes must converge even with unchanged collection, size and mtime");
    assertNull(fields.getDocumentField(
        PathNormalizer.normalizeKey(delayedCollection), SchemaFields.COLLECTION));

    IndexGenerationManager.State after = layout.genManager().readStateBestEffort();
    assertNotNull(after);
    assertEquals(committed.active_generation(), after.active_generation());
    assertTrue(after.previous_generation() == null || after.previous_generation().isBlank());
    assertFalse(
        Files.exists(
            layout.genManager().resolveGenerationPathStrict(committed.previous_generation())));
    assertEquals(1, server.jobQueueForTests().jobStateCountsStrict().pendingCount());
    assertTrue(((SqliteJobQueue) server.jobQueueForTests()).listSwitchBufferOpsStrictForGeneration(
        committed.active_generation()).isEmpty());
  }

  private static void seedFileWithCollection(Path generation, Path file, String collection)
      throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var luceneExecutors =
            new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        RunningRuntime runtime = IndexSchema.fromCatalog(
                WorkerBootFixture.productionCatalog(),
                () -> new SsotCommitMetadataSource().build(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(generation).withExecutorRegistrations(luceneExecutors).open()) {
      String id = PathNormalizer.normalizeKey(file);
      var indexed = new java.util.HashMap<String, Object>(Map.of(
          SchemaFields.DOC_ID, id,
          SchemaFields.PATH, id,
          SchemaFields.DOC_UID, "relabeled#0",
          SchemaFields.CONTENT, Files.readString(file),
          SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
          SchemaFields.SIZE_BYTES, Files.size(file),
          SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file)));
      if (collection != null) indexed.put(SchemaFields.COLLECTION, collection);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(indexed));
      runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
  }
}
