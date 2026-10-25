/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.*;
import io.justsearch.ipc.StatusRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifier D5 I7: every earlier outage batch must wait through a later capped service pause. */
final class VerifierCappedBacklogStatusTest {
  @TempDir Path temp;
  @Test void previouslyDeferredBacklogIsNotReadyDuringLaterCappedPause() throws Exception {
    var h=new Scenario(); h.temp=temp; h.init(); h.openStore(true);
    try {
      h.enqueue(java.util.stream.IntStream.range(0,64).mapToObj(i->"capped-backlog-"+i+".txt").toArray(String[]::new));
      h.loop=h.newLoop();
      var service=new WorkerIngestService(h.queue,h.loop,mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),temp,temp.resolve("index"),null,null,null,0L);
      h.loop.start();
      long end=System.nanoTime()+12000_000_000L;
      io.justsearch.ipc.StatusResponse observed=null; long expiredDeferred=0, deferred=0;
      while(System.nanoTime()<end) {
        var rows=h.rows(); long now=System.currentTimeMillis();
        boolean capped=rows.stream().anyMatch(r->r.state().equals("PENDING") && "DEFER_WITHOUT_ATTEMPT".equals(r.policy()) && r.retry()-now>6500);
        if(capped && h.inRetryPause() && rows.stream().allMatch(r->r.state().equals("PENDING"))) {
          var status=service.indexStatus(StatusRequest.getDefaultInstance(),CallContext.none());
          if(status.getMigration().getProcessingJobsCount()==0) {
            observed=status;
            expiredDeferred=rows.stream().filter(r->"DEFER_WITHOUT_ATTEMPT".equals(r.policy()) && r.retry()<now).count();
            deferred=rows.stream().filter(r->"DEFER_WITHOUT_ATTEMPT".equals(r.policy())).count();
            break;
          }
        }
        Thread.sleep(15);
      }
      assertNotNull(observed,"must reach a proven-outage capped pause through the running loop");
      System.out.println("capped multi-batch status="+observed.getCore().getState()+" ready="+observed.getMigration().getPendingReadyJobsCount()+" processing="+observed.getMigration().getProcessingJobsCount()+" deferred="+deferred+" expiredPreviouslyDeferred="+expiredDeferred+" next="+observed.getFailure().getNextRetryAtMs()+" rows="+h.rows());
      assertEquals("WAITING",observed.getCore().getState(),"I7 includes earlier outage batches, not just rows in the current batch");
      assertEquals(0,expiredDeferred,"previously deferred outage rows cannot become claimable while the service still pauses");
      assertEquals(0,observed.getMigration().getPendingReadyJobsCount());
      assertEquals(64, deferred, "all four batches must have reached the actual read-only store");
      long resumeAt = h.rows().stream().mapToLong(Row::retry).max().orElseThrow();
      assertTrue(observed.getFailure().getNextRetryAtMs() >= System.currentTimeMillis(),
          "next retry time cannot be in the past during the capped pause");
      assertEquals(resumeAt, observed.getFailure().getNextRetryAtMs());
      assertTrue(h.rows().stream().allMatch(row -> row.retry() == resumeAt && row.attempts() == 0),
          "every outage batch shares the current pause deadline without attempts");
      assertTrue(resumeAt <= System.currentTimeMillis() + 8_000, "resume remains within seconds");
      h.openStore(false);
      long recoveryEnd = System.nanoTime() + 9_000_000_000L;
      while (h.written.get() < 64 && System.nanoTime() < recoveryEnd) Thread.sleep(10);
      assertEquals(64, h.written.get(), "all earlier batches recover promptly at the shared deadline");
      assertTrue(h.firstWriteAt.get() >= resumeAt, "outage rows must not be claimed before resume");
      h.loop.close(); h.loop = null;
      assertEquals(64, h.rows().size());
      assertTrue(h.rows().stream().allMatch(row -> row.state().equals("DONE") && row.attempts() == 0));
    } finally { h.close(); }
  }

  @Test
  void extendingServiceDeferralsPreservesMixedFileRetriesAndNeverMovesDeadlinesBackwards() throws Exception {
    var h = new Scenario(); h.temp = temp; h.init();
    try {
      h.enqueue("outage-write.txt", "outage-draining.txt", "later-outage.txt", "ladder.txt",
          "until-available.txt", "stale.txt", "fresh.txt", "done.txt", "processing.txt");
      long now = System.currentTimeMillis();
      long deadline = now + 8_000;
      h.seed("outage-write.txt", "PENDING", "WRITE_FAILED", "DEFER_WITHOUT_ATTEMPT", now - 1_000);
      h.seed("outage-draining.txt", "PENDING", "WRITE_UNAVAILABLE_DRAINING", "DEFER_WITHOUT_ATTEMPT", now + 1_000);
      h.seed("later-outage.txt", "PENDING", "WRITE_FAILED", "DEFER_WITHOUT_ATTEMPT", deadline + 2_000);
      h.seed("ladder.txt", "PENDING", "WRITE_FAILED", "RETRY_WITH_BACKOFF", now + 2_500);
      h.seed("until-available.txt", "PENDING", "IO_FAILED", "RETRY_UNTIL_AVAILABLE", now + 3_000);
      h.seed("stale.txt", "PENDING", "STALE_SOURCE", "DEFER_WITHOUT_ATTEMPT", now + 1_000);
      h.seed("done.txt", "DONE", "WRITE_FAILED", "DEFER_WITHOUT_ATTEMPT", now - 1_000);
      h.seed("processing.txt", "PROCESSING", "WRITE_FAILED", "DEFER_WITHOUT_ATTEMPT", now - 1_000);
      Map<String, Row> before = h.rows().stream().collect(Collectors.toMap(row -> Path.of(row.path()).getFileName().toString(), row -> row));
      h.queue.extendServiceOutageDeferrals(deadline);
      Map<String, Row> after = h.rows().stream().collect(Collectors.toMap(row -> Path.of(row.path()).getFileName().toString(), row -> row));
      assertEquals(deadline, after.get("outage-write.txt").retry(), "earlier writer outages extend to the pause deadline");
      assertEquals(deadline, after.get("outage-draining.txt").retry(), "draining is also a service outage");
      for (String name : List.of("later-outage.txt", "ladder.txt", "until-available.txt", "stale.txt", "fresh.txt", "done.txt", "processing.txt")) {
        assertEquals(before.get(name), after.get(name), "only earlier PENDING service deferrals can move: " + name);
      }
      for (String name : List.of("outage-write.txt", "outage-draining.txt")) {
        Row old = before.get(name); Row updated = after.get(name);
        assertEquals(new Row(old.path(), old.state(), old.attempts(), deadline, old.policy(),
            old.firstFailure(), old.outcomeClass(), old.lastUpdated()), updated);
      }
      assertEquals(1, h.queue.jobStateCounts().pendingReadyCount(), "fresh work remains honestly ready");
      h.queue.extendServiceOutageDeferrals(deadline - 1_000);
      h.queue.extendServiceOutageDeferrals(deadline);
      assertEquals(List.copyOf(after.values()).stream().sorted(java.util.Comparator.comparing(Row::path)).toList(), h.rows(),
          "a repeated/earlier pause never moves a deadline backwards or changes another column");
      System.out.println("mixed retry deadlines before=" + before + " after=" + after);
    } finally { h.close(); }
  }

  private record Row(String path, String state, int attempts, Long retry, String policy,
      long firstFailure, String outcomeClass, long lastUpdated) {}

  private static final class Scenario {
    Path temp;
    Path db;
    Path corpus;
    SqliteJobQueue queue;
    SqliteDocumentIdentityStore store;
    IndexingLoop loop;
    final AtomicReference<DocumentIdentityStore> activeStore = new AtomicReference<>();
    final AtomicInteger written = new AtomicInteger();
    final AtomicLong firstWriteAt = new AtomicLong();

    void init() throws Exception {
      db = temp.resolve("jobs.db");
      corpus = Path.of(System.getProperty("justsearch.test.outageCorpus", temp.resolve("sources").toString()));
      Files.createDirectories(corpus);
      queue = new SqliteJobQueue(db); queue.open();
    }

    void openStore(boolean readOnly) throws java.io.IOException {
      if (store != null) store.close();
      store = new SqliteDocumentIdentityStore(readOnly ? Path.of("file:" + db + "?mode=ro") : db);
      activeStore.set(store);
    }

    IndexingLoop newLoop() throws Exception {
      var writer = mock(IndexingCoordinator.class);
      doAnswer(call -> { firstWriteAt.compareAndSet(0, System.currentTimeMillis()); written.incrementAndGet(); return null; })
          .when(writer).indexSingle(any());
      var identities = mock(DocumentIdentityStore.class);
      when(identities.resolve(anyString(), anyLong())).thenAnswer(call ->
          activeStore.get().resolve(call.getArgument(0), call.getArgument(1)));
      var content = mock(TimeboxedContentExtractor.class);
      when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(content.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
          new ContentExtractor.ExtractionResult("healthy readable content", null, "text/plain"),
          TikaExtractionPolicy.defaults(), "verifier", false));
      var configBuilder = new ResolvedConfigBuilder();
      configBuilder.putDefault("justsearch.backfill.poll_batch_size", "16");
      var config = configBuilder.build();
      assertEquals(16, config.ai().backfillPacing().pollBatchSize());
      return new IndexingLoop(null, null, queue, writer, mock(CommitOps.class),
          mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> config,
          mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null,
          content, null, new IndexingLoopOptions(false, null, identities, null, null, null));
    }

    void enqueue(String... names) throws Exception {
      var entries = new ArrayList<JobQueue.EnqueueEntry>();
      for (String name : names) {
        Path file = corpus.resolve(name);
        if (!Files.exists(file)) Files.writeString(file, "healthy readable content");
        entries.add(JobQueue.EnqueueEntry.stat(file).withinRoot(corpus));
      }
      queue.enqueueEntries(entries);
    }

    List<Row> rows() throws Exception {
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var query = connection.createStatement();
          var result = query.executeQuery("SELECT path,state,attempts,retry_after,last_retry_policy,first_failed_at,last_outcome_class,last_updated FROM jobs ORDER BY path")) {
        var rows = new ArrayList<Row>();
        while (result.next()) {
          long retry = result.getLong(4); Long retryAt = result.wasNull() ? null : retry;
          rows.add(new Row(result.getString(1), result.getString(2), result.getInt(3), retryAt,
              result.getString(5), result.getLong(6), result.getString(7), result.getLong(8)));
        }
        return rows;
      }
    }

    void seed(String name, String state, String outcome, String policy, long retryAt) throws Exception {
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var update = connection.prepareStatement("UPDATE jobs SET state=?,last_outcome_class=?,last_retry_policy=?,retry_after=?,attempts=2,first_failed_at=12345 WHERE path=?")) {
        update.setString(1, state); update.setString(2, outcome); update.setString(3, policy);
        update.setLong(4, retryAt); update.setString(5, corpus.resolve(name).toString());
        assertEquals(1, update.executeUpdate());
      }
    }

    boolean inRetryPause() throws Exception {
      var field = IndexingLoop.class.getDeclaredField("loopThread"); field.setAccessible(true);
      Thread owner = (Thread) field.get(loop);
      return Arrays.stream(owner.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("sleepAfterError"));
    }

    void close() throws Exception {
      if (loop != null) loop.close();
      if (store != null) store.close();
      if (queue != null) queue.close();
    }
  }
}
