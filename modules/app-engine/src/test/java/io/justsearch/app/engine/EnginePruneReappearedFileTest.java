/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file recreated after the prune's last checks loses its rows to the prune (some window between
 * the filesystem and the index always remains), and gets them back: the sync's walk, which runs
 * after the prune, finds it on disk and queues it, and the re-ingest restores it.
 *
 * <p>The file is recreated at the latest point the prune can be affected at all: the moment its
 * removal is published, after every check and the delete itself, while the batch still holds the
 * file-mutation fence.
 */
@Timeout(300)
final class EnginePruneReappearedFileTest {

  @TempDir static Path tempDir;
  private static EngineTestHarness harness;
  private static AutoCloseable volumeEvidence;
  private static Path root;

  @BeforeAll
  static void startEngine() throws Exception {
    volumeEvidence = EngineTestHarness.knownVolume();
    root = Files.createDirectories(tempDir.resolve("reappear-root"));
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    assertTrue(harness.client().isHealthy(TestEngineContexts.FOREGROUND));
    harness.addRootAsThePerson(root);
  }

  @AfterAll
  static void stopEngine() throws Exception {
    try {
      if (harness != null) harness.close();
    } finally {
      if (volumeEvidence != null) volumeEvidence.close();
    }
  }

  @Test
  void aFileRecreatedAsItsRemovalIsPublishedIsIndexedAgainByTheSync() throws Exception {
    String marker = "zqreappear731";
    Path file = Files.writeString(root.resolve("comes-back.txt"),
        EngineTestHarness.chunkedContent(marker));
    String docId = PathNormalizer.normalizeKey(file);
    long baseline = harness.status().getCore().getDocCount();
    assertEquals(1, harness.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND)
        .getAcceptedCount());
    assertTrue(harness.awaitIndexed(baseline + 1, 60_000), "precondition: the file is indexed");
    assertTrue(harness.awaitIngestChunks(docId, marker, false, 60_000),
        "precondition: the file has several chunks");

    AtomicBoolean armed = new AtomicBoolean();
    AtomicBoolean recreated = new AtomicBoolean();
    SearcherManager manager;
    try (var view = harness.captureServingView()) {
      manager = searcherManager(view.ingestRuntime());
    }
    ReferenceManager.RefreshListener recreateOnRemoval = new ReferenceManager.RefreshListener() {
      @Override public void beforeRefresh() {}

      @Override public void afterRefresh(boolean didRefresh) throws IOException {
        if (!armed.get() || recreated.get()) {
          return;
        }
        IndexSearcher searcher = manager.acquire();
        try {
          if (searcher.count(rowsOf(docId)) == 0) {
            Files.writeString(file, EngineTestHarness.chunkedContent(marker));
            recreated.set(true);
          }
        } finally {
          manager.release(searcher);
        }
      }
    };
    manager.addListener(recreateOnRemoval);
    try {
      Files.delete(file);
      armed.set(true);

      SyncDirectoryResponse response =
          harness.client().syncDirectory(root.toString(), true, TestEngineContexts.FOREGROUND);

      assertTrue(recreated.get(), "precondition: the file came back as its removal was published");
      assertEquals("", response.getError());
      assertTrue(response.getFilesDeleted() >= 1,
          "the prune removed the file's rows: " + response.getFilesDeleted());
      assertEquals(1, response.getFilesAdded(), "the walk queued the file that is on disk again");
      assertTrue(harness.awaitSearchable(marker, 60_000),
          "the queued re-ingest makes the file searchable again");
      assertTrue(harness.awaitIngestChunks(docId, marker, false, 60_000),
          "the queued re-ingest restores the file's chunks");
    } finally {
      manager.removeListener(recreateOnRemoval);
    }
  }

  private static BooleanQuery rowsOf(String docId) {
    return new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, docId)), BooleanClause.Occur.SHOULD)
        .build();
  }

  /** The search view the prune refreshes and publishes through; not exposed outside its package. */
  private static SearcherManager searcherManager(Object runtime)
      throws ReflectiveOperationException {
    Method session = runtime.getClass().getDeclaredMethod("session");
    session.setAccessible(true);
    Object opened = session.invoke(runtime);
    Field snapshotField = opened.getClass().getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(opened);
    Method searcherManager = snapshot.getClass().getDeclaredMethod("searcherManager");
    searcherManager.setAccessible(true);
    return (SearcherManager) searcherManager.invoke(snapshot);
  }
}
