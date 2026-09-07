/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for {@code ChaosSuiteTest.TimeLordTests}
 * ("Indexing is throttled — not stopped — while foreground searches are in flight").
 *
 * <p><b>The two properties, both of which survive the collapse intact.</b>
 *
 * <ol>
 *   <li><b>Status/health polling never throttles indexing</b> — the wrong-gate arm. Tempdoc 885
 *       item 3 removed "an observer counts as a user": {@code indexStatus} and the health check are
 *       deliberately NOT in {@code ForegroundLoadGate.foregroundOperations()}. If either ever leaks
 *       back into the foreground set, a Head that merely polls its own status would stall its own
 *       indexing, which is the pre-885 defect wearing a different hat.
 *   <li><b>Real foreground search load throttles indexing without stopping it</b> — the duty cycle.
 *       The 885 baseline arm (c) indexed 699 of 5184 documents in 22 minutes under the old
 *       breath-hold pause; the property that replaced it is that the loop yields <em>and keeps
 *       going</em>.
 * </ol>
 *
 * <p><b>Nothing about the second process is carried over.</b> The retired test spent most of its
 * body on the rig: {@code mmfHarness.open/resetAll/keepAlive}, {@code spawnWorkerAndAwaitPort}, and
 * a dedicated heartbeat-keeper thread whose only job was to stop the Worker from honouring the
 * suicide pact ~16.5 s into a 180 s test. The heartbeat, the pact and the port are all deleted, so
 * all of that is gone. What is left is the part that was about the product.
 *
 * <p><b>What got stronger, because in-process the gauge is visible.</b> The wire test could only
 * infer the duty cycle from a sampled {@code worker_state} string, so its load-phase assertion was
 * "a {@code PAUSED} sample was seen at least once in 20 s" — a race dressed as an assertion. Here
 * the same claim is made against {@link IndexingPacing#pacedIntervalsTotal()} and
 * {@link IndexingPacing#yieldedMsTotal()}, monotonic counters that only advance when the loop
 * actually yielded to foreground load, and against {@link ForegroundLoad#startedTotal()}, which is
 * the only assertion that can distinguish "the poll RPCs are outside the foreground set" from "the
 * gauge is not wired at all" (reference case {@code wrong-gate}: the gate was dead from A6 to A9
 * and no test could see it). {@code worker_state} is still read and still asserted on — it moves
 * (IndexingLoop.java:740-744 sets {@code PAUSED} for the duration of the yield) and it is the field
 * an operator sees — but it is no longer the sole witness.
 *
 * <p><b>Two batches, deliberately — the comment the retired test asked to keep.</b> The poll-only
 * phase drains the first corpus, so with a single corpus the load phase would start with an empty
 * queue and "no progress under load" would mean "no work", not "starved". The load phase therefore
 * gets its own corpus, submitted immediately before it, and asserts a non-zero queue depth at its
 * start so that a drained queue fails loudly instead of passing quietly.
 *
 * <p><b>Scaled down, and why the scaling is safe.</b> 400 + 1600 files became 300 + 500: this runs
 * in {@code :modules:app-engine:test} now, which the retired test never did. The structural
 * protection against a too-small corpus is the queue-depth assertion above, not the file count.
 * {@code justsearch.backfill.commit_interval_ms} is lowered to 1 s so that indexing progress
 * becomes <em>observable</em> within the test's window (the 10 s default would make a 12 s load
 * phase a coin flip on commit timing); it changes when the count is published, not whether the loop
 * runs.
 *
 * <p><b>No wall-clock thresholds.</b> Every bound here is a deadline on "did X ever happen", not an
 * assertion that X happened fast. Each phase runs for a minimum duration (so it really sampled) and
 * then exits as soon as its property is satisfied, with a generous ceiling for a loaded CI box.
 */
@Timeout(240)
final class EngineForegroundPacingTest {

  /** Poll-only corpus: drained by phase 1, which is exactly why phase 2 needs its own. */
  private static final int POLL_CORPUS = 300;

  /** Load-phase corpus, submitted immediately before the search load starts. */
  private static final int LOAD_CORPUS = 500;

  /** Submitted in chunks: one {@code submitBatch} enqueues N paths into SQLite under a 30 s
   * STANDARD budget while the loop is already indexing, and a single 500-path call is close
   * enough to that budget on a loaded box to be worth splitting. */
  private static final int SUBMIT_CHUNK = 100;

  private EngineRoot root;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
  }

  @Test
  @DisplayName("indexing is throttled — not stopped — under foreground search load, and polling"
      + " never throttles it at all")
  void indexingRunsAtAReducedDutyUnderForegroundSearchLoad(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(
        dataDir,
        dataDir.resolve("index"),
        Map.of(
            // Observation granularity only — see the class javadoc.
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
    KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());

    // THE gauge the indexing loop paces off, read off the KnowledgeServer field that owns it (the
    // same identity EngineRootInProcessPortsTest pins). A copy would throttle nothing and would
    // make every assertion below vacuous.
    ForegroundLoad foregroundLoad = built[0].foregroundLoad();

    List<Path> pollBatch = writeThrottlingCorpus(tempDir, "poll-batch", 0, POLL_CORPUS);
    List<Path> loadBatch =
        writeThrottlingCorpus(tempDir, "load-batch", POLL_CORPUS, POLL_CORPUS + LOAD_CORPUS);

    // ---- Phase 1 (wrong-gate): polling must NOT throttle indexing. ------------------------------
    long foregroundBeforePolling = foregroundLoad.startedTotal();
    assertTrue(
        submitInChunks(client, pollBatch) > 0, "the poll-phase corpus should have been accepted");

    // Both poll calls are hammered at 100 ms with no search traffic at all: getHealthCheck() is
    // HealthService.Check and getStatus() is IngestService.IndexStatus — the exact call whose
    // observer-counts-as-a-user behaviour tempdoc 885 item 3 removed. If either entered the
    // foreground set, this loop alone would pin the gauge and the duty cycle would engage with
    // nobody waiting on a search.
    long pollDeadline = System.currentTimeMillis() + 30_000;
    long pollMinimumUntil = System.currentTimeMillis() + 5_000;
    int pollSamples = 0;
    long docsAfterPolling = 0;
    while (System.currentTimeMillis() < pollDeadline) {
      String state = client.getHealthCheck().getWorkerState();
      assertNotEquals(
          "PAUSED",
          state,
          "status/health polling must never throttle indexing — a PAUSED sample here means an"
              + " ingest or health operation leaked into ForegroundLoadGate's foreground set");
      docsAfterPolling = client.getStatus().getCore().getDocCount();
      pollSamples++;
      if (docsAfterPolling > 0 && System.currentTimeMillis() >= pollMinimumUntil) {
        break;
      }
      Thread.sleep(100);
    }
    assertTrue(pollSamples > 10, "the poll-only phase must actually have polled: " + pollSamples);
    // The precise form of the same claim, and the one the wire test could not make: the gauge did
    // not move. A PAUSED-never-seen assertion passes just as well when nothing feeds the gauge.
    assertEquals(
        foregroundBeforePolling,
        foregroundLoad.startedTotal(),
        "no status or health call may enter the foreground set — " + pollSamples
            + " poll samples moved ForegroundLoad.startedTotal, which only a foreground-labelled"
            + " operation may do");
    assertTrue(
        docsAfterPolling > 0,
        "indexing must run at full speed while only status/health polling happens — no document"
            + " became visible in " + pollSamples + " samples");

    // ---- Phase 2: real foreground search load drives the gauge and the duty cycle. --------------
    assertTrue(
        submitInChunks(client, loadBatch) > 0, "the load-phase corpus should have been accepted");
    long docsBeforeLoad = client.getStatus().getCore().getDocCount();
    long queueDepthAtLoadStart = client.getStatus().getCore().getQueueDepth();
    assertTrue(
        queueDepthAtLoadStart > 0,
        "the load phase must start with work queued, otherwise it cannot tell a throttled loop from"
            + " an idle one");

    AtomicBoolean loadRunning = new AtomicBoolean(true);
    AtomicInteger queriesIssued = new AtomicInteger();
    AtomicBoolean observedPaused = new AtomicBoolean(false);
    ExecutorService loadPool = Executors.newFixedThreadPool(2);
    long docsUnderLoad;
    try {
      for (int i = 0; i < 2; i++) {
        loadPool.submit(
            () -> {
              while (loadRunning.get()) {
                try {
                  client.search("chaos throttling probe", 5);
                  queriesIssued.incrementAndGet();
                } catch (RuntimeException underLoad) {
                  // A search failing under load is not what this test measures; the queries that
                  // DID run are what drives the gauge, and startedTotal counts them below.
                }
              }
            });
      }

      long loadDeadline = System.currentTimeMillis() + 45_000;
      long loadMinimumUntil = System.currentTimeMillis() + 12_000;
      docsUnderLoad = docsBeforeLoad;
      while (System.currentTimeMillis() < loadDeadline) {
        if ("PAUSED".equals(client.getHealthCheck().getWorkerState())) {
          observedPaused.set(true); // sampled inside a duty-cycle yield window
        }
        docsUnderLoad = client.getStatus().getCore().getDocCount();
        // Exit as soon as every property this phase asserts has been observed, and not before —
        // otherwise the assertions below would be graded on a 12 s window rather than on the full
        // deadline, which is how "X happened" degrades into "X happened fast enough".
        if (docsUnderLoad > docsBeforeLoad
            && observedPaused.get()
            && pacing(built[0]).pacedIntervalsTotal() > 0
            && System.currentTimeMillis() >= loadMinimumUntil) {
          break;
        }
        Thread.sleep(100);
      }
    } finally {
      loadRunning.set(false);
      loadPool.shutdownNow();
      loadPool.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertTrue(queriesIssued.get() > 0, "the foreground load must actually have run");
    assertTrue(
        foregroundLoad.startedTotal() >= queriesIssued.get(),
        "every search the load threads completed must have passed the foreground gate: "
            + queriesIssued.get() + " searches vs startedTotal "
            + foregroundLoad.startedTotal());

    // (a) THROTTLED — the loop yielded. Read from the pacing policy rather than sampled off a
    // status string: pacedIntervalsTotal only advances inside IndexingPacing.pace() when a yield
    // actually happened while foregroundBusy(), so a non-zero value cannot come from anything else.
    // Read fresh rather than cached: KnowledgeServer REPLACES its WorkerAppServices on a
    // deferred-runtime upgrade, and a cached IndexingPacing would then be a dead object.
    IndexingPacing pacing = pacing(built[0]);
    assertTrue(
        pacing.pacedIntervalsTotal() > 0,
        "the indexing loop must be observed yielding to foreground load — zero paced intervals"
            + " means the duty cycle never engaged and this test proves nothing about throttling");
    assertTrue(
        pacing.yieldedMsTotal() > 0,
        "a paced interval that yielded no wall time is not a duty cycle: yieldedMsTotal was "
            + pacing.yieldedMsTotal());
    assertTrue(
        observedPaused.get(),
        "worker_state must report PAUSED for the duration of a yield — an operator watching the"
            + " health call has to be able to see the loop throttling (IndexingLoop.java:740-744)");

    // (b) NOT STOPPED — the loop kept indexing the load-phase corpus while all of that happened.
    // This is the arm that fails if the duty cycle regresses to the pre-885 breath-hold pause.
    assertTrue(
        docsUnderLoad > docsBeforeLoad,
        "indexing must keep making progress on the load-phase corpus under continuous foreground"
            + " load — a full stop is the pre-885 breath-hold behaviour tempdoc 885 item 3 removed"
            + " (queue depth at the start of the load phase was " + queueDepthAtLoadStart + ")");

    // With the load gone the loop must leave the yield state. The bound is a deadline on "it
    // happened", not a latency assertion: foregroundBusy() clears one cooldown (500 ms default)
    // after the last search, and 30 s is generous room for the loop to notice on a loaded box.
    String resumedState = awaitWorkerState(client, 30_000);
    assertNotEquals(
        "PAUSED", resumedState, "the loop must resume once the foreground load drains");
  }

  /**
   * The live pacing policy. Not cached — see the call site: {@code appServices()} is replaced on a
   * deferred-runtime upgrade and on dev hot-reload.
   */
  private static IndexingPacing pacing(KnowledgeServer server) {
    return server.appServices().indexingPacing();
  }

  /** Polls until the loop reports something other than {@code PAUSED}, or the deadline passes. */
  private static String awaitWorkerState(KnowledgeClient client, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    String state = client.getHealthCheck().getWorkerState();
    while (System.currentTimeMillis() < deadline && "PAUSED".equals(state)) {
      Thread.sleep(100);
      state = client.getHealthCheck().getWorkerState();
    }
    return state;
  }

  private static int submitInChunks(KnowledgeClient client, List<Path> corpus) {
    int accepted = 0;
    for (int from = 0; from < corpus.size(); from += SUBMIT_CHUNK) {
      int to = Math.min(from + SUBMIT_CHUNK, corpus.size());
      accepted += client.submitBatch(corpus.subList(from, to)).getAcceptedCount();
    }
    return accepted;
  }

  /** Writes a synthetic corpus slice for the throttling probe. */
  private static List<Path> writeThrottlingCorpus(
      Path tempDir, String name, int from, int toExclusive) throws IOException {
    Path corpusDir = tempDir.resolve("throttle-corpus").resolve(name);
    Files.createDirectories(corpusDir);
    List<Path> paths = new ArrayList<>(toExclusive - from);
    for (int i = from; i < toExclusive; i++) {
      Path file = corpusDir.resolve("doc-" + i + ".txt");
      Files.writeString(
          file,
          "chaos throttling probe document " + i + "\n"
              + "the duty cycle must keep indexing this corpus while searches run\n");
      paths.add(file);
    }
    return paths;
  }
}
