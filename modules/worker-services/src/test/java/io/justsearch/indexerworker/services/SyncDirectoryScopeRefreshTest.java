/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.PruneOps;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SyncDirectoryScopeRefreshTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test
  void declarationBeforePrunePresenceProtectsTheChildDocument() throws Exception {
    Path root = Files.createDirectory(directory.resolve("parent"));
    Path child = Files.createDirectory(root.resolve("child"));
    String parentFile = PathNormalizer.normalizeKey(root.resolve("missing.txt"));
    String childFile = PathNormalizer.normalizeKey(child.resolve("missing.txt"));
    var childDeclared = new AtomicBoolean();
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      for (String path : List.of(parentFile, childFile)) {
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, path, SchemaFields.DOC_UID, path, SchemaFields.PATH, path,
            SchemaFields.CONTENT, path.equals(childFile) ? "child" : "parent")));
      }
      runtime.commitOps().commitAndTrack();
      var ops = new SyncDirectoryOps(runtime.readPathOps(), runtime.pruneOps(), runtime.commitOps(),
          mock(JobQueue.class), IndexingPacing.unthrottled());
      ops.setPrefixScopeFactory(path -> new io.justsearch.adapters.lucene.runtime.PathPrefixScope(
          path.toString(), childDeclared.get() ? List.of(child.toString()) : List.of()));
      ops.setPruneGuardFactory(path -> {
        childDeclared.set(true); // Declared after sync's initial snapshot, before absence is judged.
        return PruneOps.PresenceGuard.forRoot(path);
      });

      var result = ops.execute(root.toString(), true, null, () -> false, "parent");

      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("", result.getError());
      assertEquals("child", runtime.documentFieldOps().getDocumentField(childFile, SchemaFields.CONTENT));
      assertNull(runtime.documentFieldOps().getDocumentField(parentFile, SchemaFields.CONTENT));
      assertEquals(1, result.getFilesDeleted());
    }
  }

  @Test
  void declarationBetweenBatchesPreventsParentAdmissionOfChildFiles() throws Exception {
    Path root = Files.createDirectory(directory.resolve("parent"));
    for (int i = 0; i < 2000; i++) Files.writeString(root.resolve("%04d.txt".formatted(i)), "parent");
    Path child = Files.createDirectory(root.resolve("z-child"));
    Path childFile = Files.writeString(child.resolve("keep.txt"), "child");
    var childDeclared = new AtomicBoolean();
    var admitted = new ArrayList<JobQueue.EnqueueEntry>();
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList(), eq("parent"))).thenAnswer(invocation -> {
      List<JobQueue.EnqueueEntry> batch = invocation.getArgument(0);
      admitted.addAll(batch);
      childDeclared.set(true); // Child's declaration arrives before the second batch is admitted.
      return batch.size();
    });
    var ops = new SyncDirectoryOps(null, null, null, queue, IndexingPacing.unthrottled());
    ops.setPrefixScopeFactory(path -> new io.justsearch.adapters.lucene.runtime.PathPrefixScope(
        path.toString(), childDeclared.get() ? List.of(child.toString()) : List.of()));

    var result = ops.execute(root.toString(), true, null, () -> false, "parent");

    assertEquals("", result.getError());
    assertTrue(childDeclared.get());
    assertEquals(2000, result.getFilesAdded(), "the new child declaration keeps its own admission");
    assertEquals(2000, admitted.size());
    assertTrue(admitted.stream().noneMatch(entry -> entry.path().equals(childFile)));
    assertTrue(admitted.stream().allMatch(entry -> root.equals(entry.ingestionRoot())));
  }
}
