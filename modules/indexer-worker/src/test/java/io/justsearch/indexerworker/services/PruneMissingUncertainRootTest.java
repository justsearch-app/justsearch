/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenario S2 through the prune-first reindex caller ({@code pruneMissing}):
 * a root that is not there vouches for no deletion. Before the change this caller ran the prune
 * with no root check at all, so a folder renamed or a drive unplugged pruned every document under
 * it. The root is really renamed away; nothing is injected.
 */
@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
final class PruneMissingUncertainRootTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private WorkerIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
    var layout = new IndexGenerationManager(tempDir.resolve("indexBase")).initializeOrLoad();
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(layout.activeGenerationPath())
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
    service = new WorkerIngestService(
        jobQueue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
        runtime, runtime, null, 0L);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void aRootRenamedAwayIsNotPruned() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("archive"));
    String id = index(Files.writeString(root.resolve("contract.txt"), "signed contract"));
    Files.move(root, tempDir.resolve("archive-renamed"));

    PruneResponse response = service.pruneMissing(
        PruneRequest.newBuilder().setPathPrefix(root.toString()).build(),
        CallContext.none());

    assertEquals(0, response.getPrunedCount(), "nothing may be pruned under a missing root");
    runtime.commitOps().maybeRefreshBlocking();
    assertNotNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT),
        "the document must stay searchable while its root is away");
    assertFalse(response.getError().isEmpty(), "the run reports itself incomplete");
  }

  @Test
  void aGenuinelyDeletedFileUnderAPresentRootIsPruned() throws Exception {
    // P1 (must not break).
    Path root = Files.createDirectory(tempDir.resolve("present"));
    Path gone = Files.writeString(root.resolve("gone.txt"), "obsolete");
    String goneId = index(gone);
    String keptId = index(Files.writeString(root.resolve("kept.txt"), "current"));
    addRoot(root);
    Files.delete(gone);

    PruneResponse response = service.pruneMissing(
        PruneRequest.newBuilder().setPathPrefix(root.toString()).build(),
        CallContext.none());

    assertEquals(1, response.getPrunedCount());
    runtime.commitOps().maybeRefreshBlocking();
    assertNull(runtime.documentFieldOps().getDocumentField(goneId, SchemaFields.CONTENT));
    assertNotNull(runtime.documentFieldOps().getDocumentField(keptId, SchemaFields.CONTENT));
  }

  private String index(Path file) throws Exception {
    String id = PathNormalizer.normalizePath(file.toAbsolutePath().toString());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id + "#0",
        SchemaFields.PATH, id,
        SchemaFields.CONTENT, Files.readString(file))));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return id;
  }

  /**
   * The person's add of {@code root}: its watch registration with the add marker. Round 8 (refined
   * decision 2): only the add records a root's volume; a build without the marker recorded it at
   * the first prune.
   */
  private void addRoot(Path root) {
    var request = io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(root.toString());
    var marker = io.justsearch.ipc.WatchRootRequest.getDescriptor().findFieldByName("root_added");
    if (marker != null) {
      request.setField(marker, Boolean.TRUE);
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        service.watchRoot(request.build(), CallContext.none()).getWatching(),
        "precondition: the person adds the root");
  }
}
