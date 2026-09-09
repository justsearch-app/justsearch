/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.SearchResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.torture.ReadWhileWriteTest} ("Search latency remains low during heavy
 * indexing") and for {@code ChaosSuiteTest.ProtocolStressTests} ("Multiple concurrent health checks
 * succeed").
 *
 * <p><b>The retired read-while-write test asserted almost nothing, and that is why this one is
 * bigger than it.</b> Its name claimed searching under heavy indexing; its body ran ten threads
 * calling {@code client.isHealthy()} — annotated in its own source as a "Proxy for search" — while
 * its "indexing" loop was fifty iterations of {@code Thread.sleep(50)} that indexed nothing at all
 * (ReadWhileWriteTest.java:126-165). Its single assertion was that more than 100 health checks
 * completed in 2.5 s. Neither Lucene NRT nor SQLite WAL — the two subjects its own javadoc named —
 * was touched. So this is not a translation; it is the test the name always claimed: real documents
 * indexed while real searches run concurrently from several threads, with the index checked for
 * consistency afterwards.
 *
 * <p><b>What the timing claim became.</b> The original logged (never asserted) a 200 ms per-call
 * warning. A wall-clock latency threshold is not assertable on a loaded CI box, and this class now
 * runs in the unit suite, so the property is expressed without the clock: searches issued
 * concurrently with indexing must <em>succeed</em>, and the writer must still drain while they do.
 * A read path serialised behind the writer fails that as surely as a slow one, and it fails for a
 * reason the assertion can name.
 *
 * <p><b>The "protocol" half of ProtocolStressTests died with the wire; the concurrency half
 * survives.</b> That test opened ten separate {@code GrpcTestClient} channels to a published port
 * and tolerated a 5% failure rate, because a channel can fail. There is no channel, no port and no
 * per-thread client. What survives is the property underneath — many concurrent callers all get
 * served — which is now a property of {@code EngineKnowledgeClient}'s bounded call pool
 * ({@code CALL_THREAD_CAP} = 64, comfortably above the 10 used here, so a refusal would be a defect
 * rather than back-pressure). The converted form keeps the original's 10 threads × 20 requests
 * shape, mixes real searches in with the health checks so the gated path is covered too, and adds
 * two assertions the wire test could not make: every search moved {@code ForegroundLoad}, and the
 * gauge came back to exactly zero. An unbalanced gate leaves indexing permanently throttled and
 * nothing else in the suite would notice.
 *
 * <p><b>Dropped as process properties:</b> {@code processManager.spawnWorker()},
 * {@code awaitPortWithRetries}, the heartbeat-keeper thread and every {@code mmfHarness.keepAlive()}
 * (the suicide pact is deleted with the memory-mapped bus), and the per-thread channel
 * construction. Nothing about the product went with them.
 */
@Timeout(300)
final class EngineReadWhileWriteTest {

  private static final int CORPUS_SIZE = 400;

  /** One {@code submitBatch} enqueues its paths into SQLite under a 30 s STANDARD budget while
   * the loop is already indexing; chunking keeps a single call well inside that. */
  private static final int SUBMIT_CHUNK = 100;

  private static final int SEARCH_THREADS = 6;

  /** The ProtocolStress shape, unchanged: 10 concurrent callers, 20 requests each. */
  private static final int STRESS_THREADS = 10;

  private static final int STRESS_REQUESTS_PER_THREAD = 20;

  private EngineRoot root;
  private KnowledgeClient client;
  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
    client = null;
    server = null;
  }

  @Test
  @DisplayName("concurrent searches succeed while documents are being indexed, and the index is"
      + " consistent afterwards")
  void searchesSucceedWhileTheIndexIsBeingWritten(@TempDir Path tempDir) throws Exception {
    start(tempDir);
    List<Path> corpus = writeCorpus(tempDir, CORPUS_SIZE);

    AtomicBoolean searching = new AtomicBoolean(true);
    AtomicInteger completedSearches = new AtomicInteger();
    AtomicReference<Throwable> searchError = new AtomicReference<>();
    ExecutorService searchPool = Executors.newFixedThreadPool(SEARCH_THREADS);
    boolean drained;
    try {
      for (int i = 0; i < SEARCH_THREADS; i++) {
        searchPool.submit(
            () -> {
              while (searching.get()) {
                try {
                  SearchResponse response = client.search("read while write probe", 10, TestEngineContexts.FOREGROUND);
                  // Reading the payload is deliberate: a response object nobody touches would let
                  // a half-built response pass for a successful search.
                  if (response.getResultsCount() < 0) {
                    throw new IllegalStateException("negative result count");
                  }
                  completedSearches.incrementAndGet();
                } catch (RuntimeException e) {
                  // Unlike the retired test, a failing search is a FAILURE, not a logged datapoint:
                  // the property is that the read path stays available while the writer works.
                  //
                  // The one exception is teardown. `searching` is cleared before the pool is shut
                  // down, and a reader already inside search() when that happens is released with
                  // "search interrupted" (EngineKnowledgeClient.withBudget catches
                  // InterruptedException). Recording that would fail the test for the test's own
                  // stop signal — a wrong-reason red, and it fired on the first full run of this
                  // class. Only a failure while we still WANT searches running is a failure.
                  if (searching.get()) {
                    searchError.compareAndSet(null, e);
                  }
                  return;
                }
              }
            });
      }

      // The real writer the retired test never started, submitted while the readers are already
      // running so the two paths overlap for the whole ingest.
      int accepted = 0;
      for (int from = 0; from < corpus.size(); from += SUBMIT_CHUNK) {
        int to = Math.min(from + SUBMIT_CHUNK, corpus.size());
        accepted += client.submitBatch(corpus.subList(from, to), TestEngineContexts.FOREGROUND).getAcceptedCount();
      }
      assertTrue(accepted > 0, "the corpus must have been accepted for indexing");

      // Wait for the writer to drain WHILE the readers keep hammering — the overlap this test
      // exists for. The bound is a deadline on "the writer finished", not a latency assertion,
      // and it is generous because the readers compete for the same machine.
      drained = awaitQueueDrained(180_000, searchError);
    } finally {
      // Stop cooperatively first — the readers exit their loop on this flag — and only interrupt if
      // one is still inside a call after the grace period. shutdownNow() as the FIRST move
      // interrupts a search in flight, and the reader would report the test's own stop signal as a
      // failed search.
      searching.set(false);
      searchPool.shutdown();
      if (!searchPool.awaitTermination(60, TimeUnit.SECONDS)) {
        searchPool.shutdownNow();
        searchPool.awaitTermination(30, TimeUnit.SECONDS);
      }
    }

    failIfSearchFailed(searchError);
    assertTrue(drained, "indexing must finish while concurrent searches run");
    assertTrue(
        completedSearches.get() > 0,
        "the concurrent readers must actually have searched during the write");

    // Consistency afterwards: what was written is findable, and the count is non-zero.
    assertTrue(
        awaitSearchable("read while write probe", 120_000),
        "the documents written during the concurrent read load must be findable afterwards");
    assertTrue(
        client.getStatus(TestEngineContexts.FOREGROUND).getCore().getDocCount() > 0,
        "the index must report the documents it accepted");
    // The gauge must come back to rest. Polled rather than read once: shutdownNow interrupts the
    // readers, and an interrupted CALLER is released before the worker thread it handed the search
    // to has finished unwinding, so a bare read here would be a race.
    assertTrue(
        awaitGaugeAtRest(30_000),
        "the foreground gauge must return to zero once every concurrent search unwinds; it was at "
            + server.foregroundLoad().inFlight());
  }

  @Test
  @DisplayName("many concurrent callers are all served, and the foreground gate balances")
  void manyConcurrentCallersAreAllServed(@TempDir Path tempDir) throws Exception {
    start(tempDir);
    assertTrue(client.isHealthy(TestEngineContexts.FOREGROUND), "the engine must be healthy before the concurrent phase");

    CountDownLatch done = new CountDownLatch(STRESS_THREADS);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger failCount = new AtomicInteger();
    long startedBefore = server.foregroundLoad().startedTotal();
    ExecutorService executor = Executors.newFixedThreadPool(STRESS_THREADS);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < STRESS_THREADS; t++) {
        futures.add(
            executor.submit(
                () -> {
                  try {
                    for (int r = 0; r < STRESS_REQUESTS_PER_THREAD; r++) {
                      // Both sides of the port: a health call (ungated) and a search (gated), so
                      // the concurrency covers the ForegroundLoadGate path as well.
                      boolean healthy = client.isHealthy(TestEngineContexts.FOREGROUND);
                      SearchResponse response = client.search("concurrent caller probe", 5, TestEngineContexts.FOREGROUND);
                      // This phase has an empty index; a successful search must return no hits.
                      if (healthy && response.getResultsCount() == 0) {
                        successCount.incrementAndGet();
                      } else {
                        failCount.incrementAndGet();
                      }
                    }
                  } finally {
                    done.countDown();
                  }
                }));
      }

      assertTrue(
          done.await(180, TimeUnit.SECONDS), "every concurrent caller must finish its requests");
      // Propagate any thread exception that would otherwise be silently lost — the retired test
      // did this, and it is the only thing separating "20 calls failed" from "a thread died on
      // its first call".
      for (Future<?> f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    int total = STRESS_THREADS * STRESS_REQUESTS_PER_THREAD;
    assertEquals(
        total,
        successCount.get(),
        "every concurrent call must be served — there is no channel left to blame for a failure,"
            + " so the retired test's 95% tolerance would now only hide a real defect ("
            + failCount.get() + " failed of " + total + ")");
    assertEquals(
        startedBefore + total,
        server.foregroundLoad().startedTotal(),
        "each of the " + total + " searches must have passed the foreground gate exactly once — a"
            + " shortfall means the production path is not gated, a surplus means a nested call is"
            + " double-counting");
    assertTrue(
        awaitGaugeAtRest(30_000),
        "the foreground gauge must balance to zero after " + total + " concurrent gated calls — a"
            + " leaked increment would throttle indexing forever; it was at "
            + server.foregroundLoad().inFlight());
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Boots the Engine's index half through the {@link EngineRoot} test seam, so the test can read
   * the {@code ForegroundLoad} gauge off the {@link KnowledgeServer} field that owns it — the
   * assertion the wire tests had no way to make.
   */
  private void start(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(
        dataDir,
        dataDir.resolve("index"),
        Map.of(
            // Publish counts promptly enough that writer progress is observable inside this
            // test's window. Observation granularity, not behaviour.
            "justsearch.backfill.commit_interval_ms", "1000",
            "justsearch.backfill.max_docs_before_commit", "50"));
    KnowledgeServer[] built = new KnowledgeServer[1];
    root =
        new EngineRoot(
            g -> {
              built[0] = new KnowledgeServer(WorkerConfig.load(), new InProcessWorkerSignalBus(g));
              return built[0];
            },
            30_000L,
            5_000);
    client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    server = built[0];
  }

  private void failIfSearchFailed(AtomicReference<Throwable> searchError) {
    Throwable failed = searchError.get();
    if (failed != null) {
      throw new AssertionError("a search failed while the index was being written", failed);
    }
  }

  /** Polls until the ingest queue is empty, giving up early if a reader has already failed. */
  private boolean awaitQueueDrained(long timeoutMs, AtomicReference<Throwable> searchError)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline && searchError.get() == null) {
      try {
        if (client.getStatus(TestEngineContexts.FOREGROUND).getCore().getQueueDepth() == 0) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // A status call can fail while the index half swaps a writer; the loop re-reads.
      }
      Thread.sleep(250);
    }
    return false;
  }

  private boolean awaitSearchable(String marker, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        if (client.search(marker, 10, TestEngineContexts.FOREGROUND).getResultsCount() > 0) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // Same: a search during a generation swap can fail; the loop re-reads.
      }
      Thread.sleep(250);
    }
    return false;
  }

  private boolean awaitGaugeAtRest(long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (server.foregroundLoad().inFlight() == 0) {
        return true;
      }
      Thread.sleep(50);
    }
    return server.foregroundLoad().inFlight() == 0;
  }

  private static List<Path> writeCorpus(Path tempDir, int count) throws IOException {
    Path corpusDir = tempDir.resolve("rww-corpus");
    Files.createDirectories(corpusDir);
    List<Path> paths = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Path file = corpusDir.resolve("rww-doc-" + i + ".txt");
      Files.writeString(
          file,
          "read while write probe document " + i + "\n"
              + "searches must keep being served while this corpus is written\n");
      paths.add(file);
    }
    return paths;
  }
}
