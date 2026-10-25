/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.core.execution.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

final class PipelineTestFixture {
  static EngineExecutorRegistry.Registration registration(TestEngineExecutors owners,String name,int threads) {
    return owners.register(new EngineExecutorSpec(name,EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.PLATFORM,threads,8,2));
  }
  static IndexingLoop withExtractor(PipelineTestFixture h, TimeboxedContentExtractor extractor) throws Exception {
    return new IndexingLoop(null,null,h.queue,h.writer,h.commitOps,mock(DocumentFieldOps.class),
        mock(IndexCountOps.class),()->new io.justsearch.configuration.resolved.ResolvedConfigBuilder().build(),
        mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),null,null,null,null,extractor,null,
        new IndexingLoopOptions(false,null,h.store,null,null,null));
  }

  Path temp;
  SqliteJobQueue queue;
  SqliteDocumentIdentityStore store;
  IndexingLoop loop;
  IndexingCoordinator writer;
  Path db;
  String extractedText="a readable source";
  CommitOps commitOps=mock(CommitOps.class);

  void init() throws Exception {
    db = temp.resolve("jobs.db");
    openQueue();
    writer = mock(IndexingCoordinator.class);
  }
  void openQueue() throws Exception { queue = new SqliteJobQueue(db); queue.open(); }
  void openStore(boolean readOnly) {
    store = new SqliteDocumentIdentityStore(readOnly ? Path.of("file:" + db + "?mode=ro") : db);
  }
  IndexingLoop newLoop() throws Exception { return newLoop(store); }
  IndexingLoop newLoop(DocumentIdentityStore selectedStore) throws Exception {
    var content = mock(TimeboxedContentExtractor.class);
    when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    when(content.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
        new ContentExtractor.ExtractionResult(extractedText, null, "text/plain"),
        TikaExtractionPolicy.defaults(), "verifier", false));
    return new IndexingLoop(null, null, queue, writer, commitOps,
        mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> new io.justsearch.configuration.resolved.ResolvedConfigBuilder().build(),
        mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null,
        content, null, new IndexingLoopOptions(false, null, selectedStore, null, null, null));
  }
  void close() throws Exception {
    if (loop != null) loop.close();
    if (store != null) store.close();
    if (queue != null) queue.close();
  }
  // Shutdown has its own preservation check. Retrying retained ownership here allows
  // independent persistence/logging assertions to run even if that check exposes a defect.
  void stopLoopForOtherAssertions() throws Exception {
    try { loop.close(); }
    catch (java.io.IOException e) {
      if (!e.getMessage().equals("Indexing loop still running; resources retained for shutdown retry")) throw e;
      System.out.println("Separate shutdown finding: " + e);
      loop.close();
    }
    loop = null;
  }
  List<Path> enqueue(String... names) throws Exception {
    var files = new ArrayList<Path>();
    for (String name : names) files.add(Files.writeString(temp.resolve(name), "healthy readable content"));
    queue.enqueueEntries(files.stream().map(p -> JobQueue.EnqueueEntry.stat(p).withinRoot(temp)).toList());
    return files;
  }
  record Row(String path, String state, int attempts, long retry, String policy, long firstFailure) {}
  List<Row> rows() throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
         var s = c.createStatement();
         var rs = s.executeQuery("SELECT path,state,attempts,retry_after,last_retry_policy,first_failed_at FROM jobs ORDER BY path")) {
      var result = new ArrayList<Row>();
      while (rs.next()) result.add(new Row(rs.getString(1), rs.getString(2), rs.getInt(3),
          rs.getLong(4), rs.getString(5), rs.getLong(6)));
      return result;
    }
  }
  void sql(String text) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.executeUpdate(text); }
  }
  void invoke(String method, Class<?>[] types, Object... args) throws Exception {
    var m = IndexingLoop.class.getDeclaredMethod(method, types); m.setAccessible(true); m.invoke(loop, args);
  }
  void process() throws Exception {
    invoke("processBatch", new Class<?>[]{List.class}, queue.pollPending(100));
  }
  void manual() throws Exception {
    var f = IndexingLoop.class.getDeclaredField("running"); f.setAccessible(true);
    ((AtomicBoolean) f.get(loop)).set(true);
  }
  void awaitPolicy(int maxSeconds) throws Exception {
    long end = System.nanoTime() + maxSeconds * 1_000_000_000L;
    do {
      var r = rows();
      if (!r.isEmpty() && r.stream().allMatch(x -> x.policy() != null)) return;
      Thread.sleep(20);
    } while (System.nanoTime() < end);
    fail("no durable outcome: " + rows());
  }
  void assertFree(List<Row> state) {
    assertTrue(state.stream().allMatch(r -> r.state().equals("PENDING") && r.attempts() == 0
        && "DEFER_WITHOUT_ATTEMPT".equals(r.policy())), "actual durable rows=" + state);
  }

  void awaitAllState(String expected,int seconds) throws Exception {
    long end=System.nanoTime()+seconds*1_000_000_000L;
    while(System.nanoTime()<end) {
      if(rows().stream().allMatch(r -> r.state().equals(expected))) return;
      Thread.sleep(20);
    }
    fail("expected "+expected+" actual "+rows());
  }
  void awaitPathState(String suffix,String expected,int seconds) throws Exception {
    long end=System.nanoTime()+seconds*1_000_000_000L;
    while(System.nanoTime()<end) {
      if(rows().stream().anyMatch(r -> r.path().endsWith(suffix) && r.state().equals(expected))) return;
      Thread.sleep(20);
    }
    fail("expected "+suffix+" "+expected+" actual "+rows());
  }
}
