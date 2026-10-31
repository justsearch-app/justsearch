/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

final class DeletionCommitObligationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  private RunningRuntime open(Path path) {
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer(); return runtime;
  }

  private void seed(RunningRuntime runtime, Path file) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, file.toString(),
        SchemaFields.DOC_UID, "uid", SchemaFields.PATH, file.toString(), SchemaFields.CONTENT, "original text", SchemaFields.COLLECTION, "docs")));
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
  }

  private IndexingLoop loop(SqliteJobQueue queue, RunningRuntime runtime, IndexingCoordinator writer, Path file) throws Exception {
    var identity = mock(DocumentIdentityStore.class);
    when(identity.resolve(anyString(), anyLong())).thenAnswer(call ->
        new DocumentIdentityStore.Identity(call.getArgument(0), "uid", 1L, 1L));
    var extraction = mock(TimeboxedContentExtractor.class);
    when(extraction.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    when(extraction.extractArtifact(file)).thenReturn(ExtractionArtifact.full(
        new ContentExtractor.ExtractionResult("original text", null, "text/plain"), "test-parser"));
    var loop = new IndexingLoop(mock(EngineExecutorRegistry.Registration.class), mock(EngineExecutorRegistry.Registration.class),
        queue, writer, runtime.commitOps(), runtime.documentFieldOps(), runtime.indexCountOps(), () -> null,
        mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null, extraction, null,
        new IndexingLoopOptions(false, null, identity, null, null, null));
    loop.useRootCheck(new SourcePresence.RootCheck() {
      @Override public boolean vouches(Path folder) { return true; }
      @Override public boolean admits(Path folder) { return true; }
    });
    var field = IndexingLoop.class.getDeclaredField("running"); field.setAccessible(true);
    ((AtomicBoolean) field.get(loop)).set(true); return loop;
  }

  private void wireActive(IndexingLoop loop, RunningRuntime active) throws Exception {
    var field = IndexingLoop.class.getDeclaredField("running"); field.setAccessible(true);
    var running = (AtomicBoolean) field.get(loop);
    running.set(false); loop.wireActiveLexicalSource(active); running.set(true);
  }

  private void missing(IndexingLoop loop, JobQueue.IndexJob claim, Path file, boolean afterExtraction) throws Exception {
    if (afterExtraction) {
      var extracted = loop.getExtractor().extractAll(List.of(claim));
      assertEquals(1, extracted.size()); Files.delete(file);
      loop.getWriter().write(extracted.getFirst(), null);
    } else {
      Files.delete(file); assertTrue(loop.getExtractor().extractAll(List.of(claim)).isEmpty());
    }
  }

  @org.junit.jupiter.api.Test
  void D37AlreadyExtractedWorkLosesPublicationAuthorityWhenItsCollectionIsRemoved() throws Exception {
    Path file = Files.writeString(directory.resolve("source.txt"), "original text");
    try (var runtime = open(directory.resolve("index")); var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open(); seed(runtime, file);
      var loop = loop(queue, runtime, runtime.indexingCoordinator(), file);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(directory)), "docs", "scan");
      var claim = queue.pollPending(1).getFirst();
      var extracted = loop.getExtractor().extractAll(List.of(claim)); assertEquals(1, extracted.size());
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue, loop, null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      var result = service.deleteByCollection(io.justsearch.ipc.DeleteByCollectionRequest.newBuilder()
          .setCollection("docs").build(), io.justsearch.indexerworker.services.CallContext.none());
      assertTrue(result.getError().isEmpty(), result.getError());
      loop.getWriter().write(extracted.getFirst(), null);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"*.txt", "source.txt"})
  void C14ApplyExcludesRemovesAdHocFamilyAndRejectsAlreadyExtractedPublication(String pattern) throws Exception {
    Path file = Files.writeString(directory.resolve("source.txt"), "original text");
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.configuration.resolved.ResolvedConfig.builder()
            .putDefault("justsearch.ui.exclude_patterns", "[]").build());
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(config);
    try (var runtime = open(directory.resolve("index")); var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open(); seed(runtime, file);
      var loop = loop(queue, runtime, runtime.indexingCoordinator(), file);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(file)), "docs", "scan");
      var claim = queue.pollPending(1).getFirst();
      var extracted = loop.getExtractor().extractAll(List.of(claim)); assertEquals(1, extracted.size());
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "old-child", SchemaFields.DOC_UID, "old-child",
          SchemaFields.PATH, file.toString(), SchemaFields.PARENT_DOC_ID, file.toString(),
          SchemaFields.IS_CHUNK, "true", SchemaFields.COLLECTION, "docs")));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(queue, loop, null,
          IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
      config.update(io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .putDefault("justsearch.ui.exclude_patterns", "[\"" + pattern + "\"]").build());
      var indexing = mock(io.justsearch.app.api.IndexingService.class, call -> {
        if (call.getMethod().getName().equals("forEachIndexedSource")) {
          java.util.function.Consumer<String> consumer = call.getArgument(0);
          consumer.accept(file.toString()); return 0L;
        }
        if (call.getMethod().getName().equals("deleteDocByIdWithOutcome")) {
          var response = service.deleteById(io.justsearch.ipc.DeleteByIdRequest.newBuilder()
              .setDocId(call.getArgument(0)).setRequireSettled(true).build(), io.justsearch.indexerworker.services.CallContext.none());
          return call.getMethod().getReturnType().getConstructor(boolean.class, String.class)
              .newInstance(response.getSuccess(), response.getError());
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var result = new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing)
          .applyExcludes(false, io.justsearch.indexerworker.services.CallContext.none().engineContext());
      assertEquals(1, result.confirmedRemovals(), result.toString()); assertTrue(result.uncheckedScopes().isEmpty());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      loop.getWriter().write(extracted.getFirst(), null);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
    } finally { io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(config, previous); }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void D33FailedDeleteAtEitherEntryRetainsRecoverableWork(boolean afterExtraction, boolean failServing) throws Exception {
    Path file = Files.writeString(directory.resolve("source.txt"), "original text");
    try (var a = open(directory.resolve("A")); var b = open(directory.resolve("B"));
        var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open(); seed(a, file); seed(b, file);
      var writer = spy(b.indexingCoordinator());
      var servingWriter = spy(a.indexingCoordinator());
      var observedServing = spy(a); doReturn(servingWriter).when(observedServing).indexingCoordinator();
      var failing = failServing ? servingWriter : writer;
      doThrow(new IllegalStateException("injected deletion failure")).when(failing).deleteByIdAndChunks(file.toString());
      var loop = loop(queue, b, writer, file); wireActive(loop, observedServing);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(directory)), "docs", "scan");
      var claim = queue.pollPending(1).getFirst();
      missing(loop, claim, file, afterExtraction);
      assertNotEquals("DONE", queue.watcherReconciliationRow(file.toString()).get("state"));
      assertFalse(loop.getJournal().ownsPendingCommit(claim), "a failed effect cannot be handed to the commit owner");
      assertEquals(1, b.indexCountOps().countByIdAndChunksStrict(file.toString()));
      doCallRealMethod().when(failing).deleteByIdAndChunks(file.toString());
      // Advance only this isolated queue's retry clock; retain the same durable admission.
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("jobs.db"));
          var reset = connection.prepareStatement("UPDATE jobs SET retry_after=0 WHERE path=?")) {
        reset.setString(1, file.toString()); assertEquals(1, reset.executeUpdate());
      }
      queue.returnUnfinishedClaims(List.of(claim));
      var retry = queue.pollPending(1).getFirst();
      assertEquals(claim.unitRevision(), retry.unitRevision());
      assertTrue(loop.getExtractor().extractAll(List.of(retry)).isEmpty());
      assertTrue(finishIdle(loop));
      a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
      assertEquals("DONE", queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals(0, a.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals(0, b.indexCountOps().countByIdAndChunksStrict(file.toString()));
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void D33AcknowledgementWaitsForBothGenerationCommitsAndQueueRestartRecoversTheObligation(boolean afterExtraction) throws Exception {
    Path file = Files.writeString(directory.resolve("source.txt"), "original text");
    Path database = directory.resolve("jobs.db");
    try (var a = open(directory.resolve("A")); var b = open(directory.resolve("B"));
        var queue = new SqliteJobQueue(database)) {
      seed(a, file); seed(b, file); queue.open();
      var loop = loop(queue, b, b.indexingCoordinator(), file); wireActive(loop, a);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(directory)), "docs", "scan");
      var claim = queue.pollPending(1).getFirst(); missing(loop, claim, file, afterExtraction);
      assertEquals("PROCESSING", queue.watcherReconciliationRow(file.toString()).get("state"));
      assertTrue(loop.getJournal().ownsPendingCommit(claim));
      rollback(a); rollback(b);
    }
    try (var a = open(directory.resolve("A")); var b = open(directory.resolve("B"));
        var queue = new SqliteJobQueue(database)) {
      assertEquals(1, a.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals(1, b.indexCountOps().countByIdAndChunksStrict(file.toString()));
      queue.open(); assertEquals(1, queue.recoverStuckJobs());
      var claim = queue.pollPending(1).getFirst();
      var loop = loop(queue, b, b.indexingCoordinator(), file); wireActive(loop, a);
      assertTrue(loop.getExtractor().extractAll(List.of(claim)).isEmpty());
      assertTrue(finishIdle(loop));
      a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
      assertEquals("DONE", queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals(0, a.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals(0, b.indexCountOps().countByIdAndChunksStrict(file.toString()));
    }
  }

  private static void rollback(RunningRuntime runtime) throws Exception {
    var sessionField = RunningRuntime.class.getDeclaredField("session"); sessionField.setAccessible(true);
    Object session = sessionField.get(runtime);
    var snapshotField = session.getClass().getDeclaredField("snapshot"); snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    var writerMethod = snapshot.getClass().getDeclaredMethod("writer"); writerMethod.setAccessible(true);
    ((org.apache.lucene.index.IndexWriter) writerMethod.invoke(snapshot)).rollback();
  }

  private static boolean finishIdle(IndexingLoop loop) throws Exception {
    var method = IndexingLoop.class.getDeclaredMethod("finishIdleCommit"); method.setAccessible(true);
    return (boolean) method.invoke(loop);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void C3EitherGenerationCommitFailureRetainsTheDeletionUntilBothCommit(boolean failServing) throws Exception {
    Path file = Files.writeString(directory.resolve("source.txt"), "original text");
    try (var a = open(directory.resolve("A")); var b = open(directory.resolve("B"));
        var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open(); seed(a, file); seed(b, file);
      var observedA = spy(a); var observedB = spy(b);
      var commitsA = spy(a.commitOps()); var commitsB = spy(b.commitOps());
      doReturn(commitsA).when(observedA).commitOps(); doReturn(commitsB).when(observedB).commitOps();
      var loop = loop(queue, observedB, b.indexingCoordinator(), file); wireActive(loop, observedA);
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(directory)), "docs", "scan");
      var claim = queue.pollPending(1).getFirst(); missing(loop, claim, file, false);
      var commits = failServing ? commitsA : commitsB;
      var reason = io.justsearch.adapters.lucene.runtime.CommitReason.INDEXING_LOOP_IDLE;
      doThrow(new IllegalStateException("injected generation commit failure")).when(commits).commitAndTrack(reason);
      assertFalse(finishIdle(loop));
      assertEquals("PROCESSING", queue.watcherReconciliationRow(file.toString()).get("state"));
      assertTrue(loop.getJournal().ownsPendingCommit(claim));
      doCallRealMethod().when(commits).commitAndTrack(reason);
      assertTrue(finishIdle(loop));
      a.commitOps().maybeRefreshBlocking(); b.commitOps().maybeRefreshBlocking();
      assertEquals("DONE", queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals(0, a.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals(0, b.indexCountOps().countByIdAndChunksStrict(file.toString()));
    }
  }
}
