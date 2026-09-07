/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for the three nested classes of
 * {@code soakTest/.../SoakSuiteTest}: {@code SearchStressSoakTest},
 * {@code WorkerRestartSoakTest} and {@code LongRunningStabilityTest}.
 *
 * <p><b>Scale, and why it changed.</b> The retired suite was a nightly tier: a 4-hour class
 * timeout, 5 000 search iterations, 20 process-restart cycles, a 10-minute sustained-load run, and
 * it was explicitly "NOT run as part of normal CI". It also never ran at all after item A9, because
 * every path into it went through a spawned Worker. These tests run in
 * {@code :modules:app-engine:test} now, so the counts are scaled to a unit tier — 400 search
 * iterations, 4 open/close cycles, a 30-second sustained run. The <em>shape</em> of each property is
 * unchanged.
 *
 * <p><b>What was actually lost.</b> An earlier draft of this comment said "nothing else was
 * dropped", which was wrong: the iteration counts are the smallest of the changes, and the oracles
 * themselves were substituted (see the next paragraph). Stated plainly, four sensitivities are
 * gone, and none of them is recovered by any other test in the repo:
 *
 * <ul>
 *   <li><b>Slow leaks.</b> 400 iterations instead of 5 000, 30 seconds instead of 10 minutes. A
 *       leak small enough to need thousands of iterations to clear a threshold now passes.
 *   <li><b>Native memory.</b> {@code NmtMemoryTracker} measured the JVM's native footprint; the
 *       replacement measures used heap after a forced collection. Off-heap growth — ORT session
 *       arenas, Lucene {@code MMapDirectory} mappings, direct byte buffers — is invisible to a heap
 *       oracle. This is the substitution most likely to matter, because the index and encoder stacks
 *       are exactly where native allocation lives.
 *   <li><b>Handles nothing re-opens.</b> The retired detector enumerated a PID's open files, so it
 *       saw every leaked handle. The reopen-succeeds check only catches a handle on a path a later
 *       cycle opens again. A descriptor leaked on, say, a rotated log or a one-shot temp file is no
 *       longer detectable here.
 *   <li><b>Process-boundary restart.</b> 20 real Worker restarts became 4 in-process open/close
 *       cycles. Anything that only manifests when the OS tears a process down — native library
 *       re-initialisation, a lock released by process exit rather than by {@code close()} — is out
 *       of reach. The trade is not one-directional: statics now survive a cycle instead of being
 *       reset by a fresh process, so state that should be cleared on close and is not will be
 *       caught here and would have been missed before.
 * </ul>
 *
 * <p>Recovering the first three needs an out-of-process tier, which is what
 * {@code IsolatedBackendFixture} exists for; recording that here rather than implying this file is
 * a like-for-like replacement.
 *
 * <p><b>Why neither {@code HandleLeakDetector} nor {@code NmtMemoryTracker} was copied across.</b>
 * Both instruments take a <em>PID</em> and shell out to another program about it —
 * {@code HandleLeakDetector} runs PowerShell (or {@code lsof}) to enumerate a process's open files,
 * {@code NmtMemoryTracker} runs {@code jcmd VM.native_memory} against a JVM started with
 * {@code -XX:NativeMemoryTracking}. Both were built for a process the test could point at from
 * outside. In one JVM the only PID is the Gradle test worker's, and its handle count and native
 * footprint are dominated by the harness itself — the classloaders, the JARs, the other tests in
 * the same fork — so the same numbers would measure noise and would need an out-of-process
 * subprocess spawn per sample to obtain. The sounder in-process oracle is to observe the thing the
 * instruments were proxies for:
 *
 * <ul>
 *   <li><b>Handles:</b> a leaked handle on the index directory or the SQLite database makes the
 *       NEXT open fail — {@code IndexRootLock} cannot re-acquire a lock file whose channel was
 *       never closed, and Windows refuses a second writer on a file with a live handle. So each
 *       cycle re-opens on the same data directory and asserts it works AND that the documents
 *       written by earlier cycles are still there. That is a direct test of "everything the last
 *       generation opened was released", not a proxy for it.
 *   <li><b>Threads:</b> {@code EngineKnowledgeClient.closeTransport} shuts down three named pools
 *       ({@code engine-call-deadlines}, {@code engine-call}, {@code engine-stream}). Counting live
 *       threads by that prefix is exact — they are the Engine's and nobody else's — so a pool that
 *       survived its owner is caught by name rather than by a statistical bound.
 *   <li><b>Memory:</b> used heap after a forced collection, with a budget stated relative to the
 *       384 MB cap the convention plugin sets for every Test task
 *       (JvmBaseConventionsPlugin.kt:115). Under that cap a genuine per-iteration leak surfaces as
 *       {@code OutOfMemoryError} long before the budget does; the budget catches the slower kind.
 * </ul>
 *
 * <p><b>Dropped as process properties:</b> {@code processManager.spawnWorker()} /
 * {@code forceKill} / {@code waitForTermination} / {@code isProcessAlive} (the restart is now a
 * composition-root open-and-close, which is what {@link EngineTestHarness#restart()} does and what
 * the Engine itself does on a deferred-runtime upgrade), {@code enableNmt()}, the per-cycle
 * {@code MmfTestHarness} and every {@code keepAlive()}.
 *
 * <p><b>One assertion was deliberately not converted.</b> The retired search soak asserted
 * {@code result.avgIterationMs() < 50} — a wall-clock latency threshold, which is exactly what a
 * loaded shared CI box makes meaningless. See the comment at its site.
 */
@Timeout(420)
final class EngineSoakTest {

  /** Scaled from the retired suite's 5 000; see the class javadoc. */
  private static final int SEARCH_ITERATIONS = 400;

  /** Scaled from the retired suite's 20 process-restart cycles. */
  private static final int RESTART_CYCLES = 4;

  /** Scaled from the retired suite's 10 minutes. */
  private static final long SUSTAINED_LOAD_MS = 30_000L;

  private static final int CORPUS_SIZE = 60;

  /**
   * Heap-growth budget: a third of the 384 MB the convention plugin gives every Test task. Chosen
   * to be unmistakably above ordinary cache warm-up and unmistakably below "the searches are
   * retaining something per iteration".
   */
  private static final long LEAK_BUDGET_BYTES = 128L * 1024 * 1024;

  private EngineTestHarness engine;

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  @DisplayName("repeated searches do not leak: " + SEARCH_ITERATIONS + " iterations all succeed and"
      + " the heap comes back")
  void repeatedSearchesDoNotLeak(@TempDir Path tempDir) throws Exception {
    engine = EngineTestHarness.start(tempDir.resolve("data"), commitConfig());
    indexCorpus(tempDir, "soak-search");

    long heapBefore = usedHeapAfterCollection();
    int succeeded = 0;
    for (int i = 0; i < SEARCH_ITERATIONS; i++) {
      // A mix, as the retired soak intended but never achieved (its "mix of search types" was one
      // isHealthy() call): a real query against a real corpus, and every tenth iteration a status
      // read, so the ingest side of the port is exercised too.
      SearchResponse response = engine.client().search("engine soak probe", 10);
      if (response.getResultsCount() > 0) {
        succeeded++;
      }
      if (i % 10 == 0) {
        assertTrue(
            engine.client().getStatus().getCore().getIsHealthy(),
            "the engine must stay healthy through the search soak (iteration " + i + ")");
      }
    }

    // A12: not converted — `assertTrue(result.avgIterationMs() < 50)`. It is a wall-clock latency
    // threshold, and this class now runs in the shared unit suite on a loaded box, where such a
    // bound measures the machine rather than the product. The property that remains assertable is
    // that every iteration produced a correct answer, which is asserted next.
    assertTrue(
        succeeded == SEARCH_ITERATIONS,
        "every one of the " + SEARCH_ITERATIONS + " searches must find the indexed corpus; "
            + succeeded + " did — a search that degrades to zero results partway through a soak is"
            + " the failure this test exists for");

    long heapAfter = usedHeapAfterCollection();
    long growth = heapAfter - heapBefore;
    assertTrue(
        growth < LEAK_BUDGET_BYTES,
        "used heap grew by " + (growth / (1024 * 1024)) + " MB over " + SEARCH_ITERATIONS
            + " searches (budget " + (LEAK_BUDGET_BYTES / (1024 * 1024)) + " MB) — a per-iteration"
            + " retention is the leak this test exists for");
  }

  @Test
  @DisplayName("repeated open/close cycles release what they opened, and the index survives each"
      + " one")
  void repeatedOpenAndCloseReleasesEverythingItOpened(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    engine = EngineTestHarness.start(dataDir, commitConfig());
    indexCorpus(tempDir, "soak-restart");
    assertTrue(
        engine.awaitSearchable("engine soak probe", 180_000),
        "the corpus must be searchable before the first cycle");

    int engineThreadsBefore = liveEngineThreads();
    int allThreadsBefore = Thread.getAllStackTraces().size();

    for (int cycle = 0; cycle < RESTART_CYCLES; cycle++) {
      // The whole point. If the previous generation leaked the index-root lock channel, a Lucene
      // directory handle or the SQLite connection, THIS is where it shows: IndexRootLock cannot
      // re-acquire a lock file whose channel is still open, and on Windows a live handle blocks
      // the reopen outright. A green reopen is a direct statement that everything was released.
      engine.restart();

      assertTrue(
          engine.client().isHealthy(),
          "the engine must be healthy after open/close cycle " + (cycle + 1));
      // Durability across the cycle: the reopened generation reads the same on-disk state a
      // respawned process used to read. A cycle that came back healthy but empty would be a
      // silent data loss the retired handle-count assertion could never have seen.
      assertTrue(
          engine.awaitSearchable("engine soak probe", 180_000),
          "the corpus indexed before cycle " + (cycle + 1) + " must still be searchable after it");
      // And the reopened generation must still accept work, not just serve reads.
      Path extra = tempDir.resolve("cycle-" + cycle + ".txt");
      Files.writeString(extra, "engine soak probe cycle marker " + cycle + "\n");
      assertTrue(
          engine.client().submitBatch(List.of(extra)).getAcceptedCount() > 0,
          "the reopened engine must accept work in cycle " + (cycle + 1));
    }

    engine.close();
    engine = null;

    // The three pools EngineKnowledgeClient.closeTransport shuts down are named, so this is exact
    // rather than statistical. Polled because shutdownNow interrupts; the threads die shortly
    // after, not synchronously.
    assertTrue(
        awaitEngineThreadsAtMost(engineThreadsBefore, 30_000),
        "every engine-owned thread pool must be gone after " + RESTART_CYCLES
            + " open/close cycles and a final close; " + liveEngineThreads()
            + " engine-* threads are still live (was " + engineThreadsBefore + " at the start)");

    // The looser backstop for everything NOT named engine-*: the index half's own loop, merge and
    // scheduler threads. The bound is generous on purpose — JIT, GC and ForkJoin populations move
    // on their own — but a per-cycle leak of loop threads would clear it easily.
    int allThreadsAfter = Thread.getAllStackTraces().size();
    int allowance = RESTART_CYCLES * 5;
    assertTrue(
        allThreadsAfter - allThreadsBefore < allowance,
        "thread population grew by " + (allThreadsAfter - allThreadsBefore) + " over "
            + RESTART_CYCLES + " open/close cycles (allowance " + allowance + ") — a generation"
            + " that leaves its threads behind leaks everything they hold");
  }

  @Test
  @DisplayName("the engine stays healthy and correct under sustained mixed load")
  void staysHealthyUnderSustainedLoad(@TempDir Path tempDir) throws Exception {
    engine = EngineTestHarness.start(tempDir.resolve("data"), commitConfig());
    indexCorpus(tempDir, "soak-sustained");
    assertTrue(
        engine.awaitSearchable("engine soak probe", 180_000),
        "the corpus must be searchable before the sustained load begins");

    int successCount = 0;
    int failCount = 0;
    long endTime = System.currentTimeMillis() + SUSTAINED_LOAD_MS;
    while (System.currentTimeMillis() < endTime) {
      try {
        // The retired test polled isHealthy() only. Health plus a real search plus a status read
        // is the load an actual Head applies, and it covers both sides of the port.
        boolean healthy = engine.client().isHealthy();
        boolean found = engine.client().search("engine soak probe", 5).getResultsCount() > 0;
        boolean statusOk = engine.client().getStatus().getCore().getIsHealthy();
        if (healthy && found && statusOk) {
          successCount++;
        } else {
          failCount++;
        }
      } catch (RuntimeException e) {
        failCount++;
      }
      Thread.sleep(50);
    }

    assertTrue(
        successCount + failCount > 50,
        "the sustained-load phase must actually have issued calls: " + (successCount + failCount));
    // The retired suite's 95% bar, kept rather than tightened to 100%: a search issued while the
    // index half swaps a generation can legitimately fail, which is why EngineTestHarness's own
    // await loops tolerate it. Tightening here would buy nothing and cost flakiness.
    double successRate = (double) successCount / (successCount + failCount);
    assertTrue(
        successRate >= 0.95,
        "success rate under sustained load must be at least 95%, was "
            + String.format(Locale.ROOT, "%.1f%%", successRate * 100) + " ("
            + successCount + " succeeded, " + failCount + " failed)");
    assertTrue(
        engine.client().isHealthy(), "the engine must still be healthy after the sustained load");
    assertTrue(
        engine.client().search("engine soak probe", 5).getResultsCount() > 0,
        "the index must still serve its corpus after the sustained load");
  }

  // ---------------------------------------------------------------------------------------------

  private static Map<String, String> commitConfig() {
    return Map.of(
        // Observation granularity: publish counts and segments promptly enough that these tests
        // measure the engine rather than the commit timer.
        "justsearch.backfill.commit_interval_ms", "1000",
        "justsearch.backfill.max_docs_before_commit", "50");
  }

  private void indexCorpus(Path tempDir, String name) throws Exception {
    Path corpusDir = tempDir.resolve("soak-corpus").resolve(name);
    Files.createDirectories(corpusDir);
    List<Path> paths = new ArrayList<>(CORPUS_SIZE);
    for (int i = 0; i < CORPUS_SIZE; i++) {
      Path file = corpusDir.resolve(name + "-" + i + ".txt");
      Files.writeString(
          file,
          "engine soak probe document " + i + "\n"
              + "a corpus the soak searches actually have to retrieve\n");
      paths.add(file);
    }
    assertTrue(
        engine.client().submitBatch(paths).getAcceptedCount() > 0,
        "the soak corpus must be accepted for indexing");
    assertTrue(
        engine.awaitSearchable("engine soak probe", 180_000),
        "the soak corpus must become searchable before the soak begins");
  }

  /** Live threads owned by {@code EngineKnowledgeClient}'s three named pools. */
  private static int liveEngineThreads() {
    int count = 0;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.isAlive() && t.getName().startsWith("engine-")) {
        count++;
      }
    }
    return count;
  }

  private static boolean awaitEngineThreadsAtMost(int ceiling, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (liveEngineThreads() <= ceiling) {
        return true;
      }
      Thread.sleep(100);
    }
    return liveEngineThreads() <= ceiling;
  }

  /**
   * Used heap after a best-effort collection. {@code System.gc()} is a hint, so it is asked twice
   * with a settle between — enough for the growth budget above to be about retention rather than
   * about whether a collection happened to run.
   */
  private static long usedHeapAfterCollection() throws InterruptedException {
    for (int i = 0; i < 2; i++) {
      System.gc();
      Thread.sleep(250);
    }
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
