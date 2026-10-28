/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.FileMutationFence;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Actual Lucene deletion, queue invalidation and physical fence cleanup, without a model. */
@Timeout(30)
final class ForgetMutationBoundaryTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();

  private Object call(Object owner, Class<?> port, String name, Class<?>[] signature, Object... arguments) {
    try { return port.getMethod(name, signature).invoke(owner, arguments); }
    catch (InvocationTargetException failed) {
      if (failed.getCause() instanceof RuntimeException failure) throw failure;
      if (failed.getCause() instanceof Error fatal) throw fatal;
      throw new AssertionError(failed.getCause());
    } catch (ReflectiveOperationException missing) {
      fail("The bounded FORGET mutation boundary must be available", missing); return null;
    }
  }

  private Optional<?> tryMutation(WorkerIngestService service) {
    return (Optional<?>) call(service, WorkerIngestService.class, "tryBeginForgetMutation", new Class<?>[0]);
  }

  private WorkerIngestService service(SqliteJobQueue queue, FileMutationFence file,
      RunningRuntime runtime, WorkerMutationAdmission admission, Object owner) {
    var loop = mock(IndexingLoop.class);
    when(loop.fileMutationFence()).thenReturn(file);
    var service = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
    service.setMutationAdmission(admission, owner);
    return service;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void Q55ContendedFileAndQueueBothRefuseWithoutHoldingOuterOwners(boolean holdFile) throws Exception {
    assertDoesNotThrow(() -> WorkerIngestService.class.getMethod("tryBeginForgetMutation"));
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open(); runtime.commitOps().stopCommitTimer();
      var file = new FileMutationFence();
      Object owner = new Object(); var admission = new WorkerMutationAdmission(owner);
      var service = service(queue, file, runtime, admission, owner);
      var publication = new ReentrantReadWriteLock();
      AutoCloseable queueLease = null;
      if (holdFile) file.lock();
      else queueLease = (AutoCloseable) ((Optional<?>) call(queue, JobQueue.class,
          "tryForgetQueueFence", new Class<?>[0])).orElseThrow();
      try {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
          publication.writeLock().lock();
          try (var ignoredOuter = admission.enter(owner)) { assertTrue(tryMutation(service).isEmpty()); }
          finally { publication.writeLock().unlock(); }
        });
        assertTrue(publication.writeLock().tryLock(), "a refused inner acquisition released configuration ownership");
        publication.writeLock().unlock();
      } finally {
        if (holdFile) file.unlock();
        else if (queueLease != null) queueLease.close();
      }
      assertTrue((Boolean) call(queue, JobQueue.class, "forgetEntryAllowed", new Class<?>[0]));
      // The failed queue attempt also releases the already-acquired file fence.
      var held = tryMutation(service).orElseThrow();
      call(held, held.getClass(), "releaseFile", new Class<?>[0]);
      call(held, held.getClass(), "afterOwnersReleased", new Class<?>[0]);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void RF55RealParentChunksAndQueueAreAbsentAndQ55DeliveryFollowsOwnersEvenOnException(boolean failAfterCommit)
      throws Exception {
    assertDoesNotThrow(() -> WorkerIngestService.class.getMethod("tryBeginForgetMutation"));
    Path source = Files.writeString(directory.resolve("literal*[x]\\name .txt"), "original source");
    Path sibling = Files.writeString(directory.resolve("literal*[x]\\name .txt.other"), "sibling source");
    String id = PathNormalizer.normalizeKey(source);
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open(); runtime.commitOps().stopCommitTimer();
      for (String docId : List.of(id, id + "#chunk-0", id + "#chunk-1")) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(SchemaFields.DOC_ID, docId); fields.put(SchemaFields.DOC_UID, "forget-parent-uid");
        fields.put(SchemaFields.PATH, id); fields.put(SchemaFields.CONTENT, "indexed source");
        if (!docId.equals(id)) {
          fields.put(SchemaFields.PARENT_DOC_ID, id); fields.put(SchemaFields.IS_CHUNK, true);
        }
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID,
          PathNormalizer.normalizeKey(sibling), SchemaFields.PATH, PathNormalizer.normalizeKey(sibling),
          SchemaFields.DOC_UID, "forget-sibling-uid", SchemaFields.CONTENT, "indexed sibling")));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(3, runtime.indexCountOps().countByIdAndChunksStrict(id),
          "the deletion probe begins with one parent and two real owned chunks");
      queue.enqueue(List.of(source, sibling));
      var file = new FileMutationFence(); Object owner = new Object();
      var admission = new WorkerMutationAdmission(owner);
      var service = service(queue, file, runtime, admission, owner);
      var publication = new ReentrantReadWriteLock();
      var delivered = new AtomicInteger();
      var subscription = queue.changeStream().subscribe(delta -> {
        assertFalse(publication.isWriteLockedByCurrentThread());
        assertFalse((Boolean) call(file, FileMutationFence.class, "isHeldByCurrentThread", new Class<?>[0]));
        file.lock(); file.unlock();
        try {
          var finalFence = admission.beginFinalFence(owner, 0);
          assertNotNull(finalFence, "queue observer runs after the real mutation read lease releases");
          finalFence.close();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        delivered.incrementAndGet();
      });
      try (subscription) {
        var plan = new LinkedHashMap<String, Object>();
        plan.put("version", 1); plan.put("path", source.toString()); plan.put("scope", "FILE");
        plan.put("keepOut", null); plan.put("watchedRoots", List.of()); plan.put("source", "FILE_SYSTEM");
        Class<?> planType = assertDoesNotThrow(() -> Class.forName("io.justsearch.app.api.operations.ForgetPlan"));
        var accepted = JSON.readValue(JSON.writeValueAsString(plan), planType);
        Object[] mutation = new Object[1];
        Runnable segment = () -> {
          try {
            publication.writeLock().lock();
            try (var ignoredOuter = admission.enter(owner)) {
              mutation[0] = tryMutation(service).orElseThrow();
              try {
                assertTrue((Boolean) call(mutation[0], mutation[0].getClass(), "removeAndVerify", new Class<?>[] {planType}, accepted));
                assertEquals(0, delivered.get());
                if (failAfterCommit) throw new IllegalStateException("private fixture: after committed removal");
              } finally { call(mutation[0], mutation[0].getClass(), "releaseFile", new Class<?>[0]); }
            } finally { publication.writeLock().unlock(); }
          } finally {
            if (mutation[0] != null) call(mutation[0], mutation[0].getClass(), "afterOwnersReleased", new Class<?>[0]);
          }
        };
        if (failAfterCommit) assertThrows(IllegalStateException.class, segment::run);
        else segment.run();
        assertTrue(delivered.get() > 0);
        assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(id));
        assertEquals("indexed sibling", runtime.documentFieldOps().getDocumentContent(PathNormalizer.normalizeKey(sibling)));
        assertEquals(1, queue.queueDepth());
        assertEquals("original source", Files.readString(source));
        assertEquals("sibling source", Files.readString(sibling));
      }
    }
  }
}
