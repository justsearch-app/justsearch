/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.torture.WindowsTortureTest} ("Worker survives file locking attack during
 * startup" / "… during heavy indexing").
 *
 * <p><b>The property is Windows-hostile-environment survival, and it is entirely about the
 * filesystem.</b> On a corporate Windows machine an antivirus scanner, a backup agent or a search
 * indexer will open and lock files under the data directory at random while the index half is
 * booting and while it writes. Nothing about that involved the second process: {@link FileIntruder}
 * (copied here from the retired torture package) locks files on disk, and the Engine's index half
 * opens exactly the files the Worker process opened.
 *
 * <p><b>The retired test was a sham in the same way {@code ReadWhileWriteTest} was.</b> Its
 * indexing-under-contention body carried the comment "We don't have a clean indexDocument method in
 * GrpcTestClient yet / Using health check as proxy" and looped 100 times on
 * {@code client.isHealthy()}, asserting only {@code successCount > 0} and that the process was still
 * alive (WindowsTortureTest.java:170-195). Nothing was indexed, so a Lucene writer that could not
 * create a segment because the intruder held its file would have passed. Both converted tests index
 * <em>real documents</em> under contention and assert they became searchable — the writer path is
 * what the intruder actually attacks.
 *
 * <p><b>{@code assertTrue(isWindows())} became {@code assumeTrue(isWindows())}.</b> The retired
 * startup test <em>failed</em> on any non-Windows host, which is dishonest: a POSIX box cannot
 * exhibit the mandatory-locking behaviour the test is about, and reporting that as a product defect
 * is noise. It is now skipped there and stated as skipped. (The retired test left its second method
 * unguarded and this one keeps that: advisory locks on POSIX make it a weaker but still valid
 * "does concurrent third-party file access break indexing" check.)
 *
 * <p><b>Dropped as process properties:</b> {@code processManager.spawnWorker()},
 * {@code awaitPortWithRetries} (which existed only because the intruder could lock the memory-mapped
 * signal file the port was published through), {@code isProcessAlive(pid)}, the heartbeat keeper and
 * every {@code mmfHarness.keepAlive()}. "The worker process survived" has no in-process meaning —
 * if the Engine died, the test JVM died with it and there would be no result to assert on. What
 * replaces it is stronger and product-facing: the documents submitted under contention are findable
 * afterwards.
 *
 * <p><b>Why this one class is {@code @Tag("stress")} when the other A12 conversions are not.</b>
 * Measured on 2026-09-07, in this order: both methods pass in ~10 s under
 * {@code :modules:app-engine:test} (three consecutive runs, isolated and whole-module), and both
 * blew their 180 s liveness budget during a concurrent whole-repo {@code ./gradlew test}, where
 * other modules' forked test JVMs load the machine. The cause is in this test's own instrument, not
 * in the product: {@link FileIntruder} promises "holds a lock for ~10-50 ms", and that promise is a
 * claim about the OS scheduler. When the box is saturated the holding thread is not scheduled to
 * release on time, the holds stretch, and the writer starves — so the test stops measuring "the
 * index half survives an antivirus-shaped intruder" and starts measuring "how loaded is this
 * machine". Quarantining it into the opt-in stress runner
 * ({@code -PincludeStress=true}, registered in {@code scripts/ci/stress-suite-policy.v1.json}) keeps
 * the assertions at full strength rather than stretching the budget until a real starvation defect
 * would also pass — the same trade the repo already made for the {@code load-sensitive} latency
 * gates (modules/app-services/build.gradle.kts:151-178).
 */
@Timeout(300)
@Tag("stress")
final class EngineFileLockContentionTest {

  private static final int CORPUS_SIZE = 100;

  private EngineTestHarness engine;
  private FileIntruder intruder;

  @AfterEach
  void tearDown() {
    if (intruder != null) {
      intruder.close();
      intruder = null;
    }
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  @DisplayName("the index half boots and indexes while an intruder holds locks under the data"
      + " directory")
  void engineBootsAndIndexesWhileAnIntruderHoldsLocks(@TempDir Path tempDir) throws Exception {
    // Mandatory locking is a Windows property. On POSIX the JDK's locks are advisory and this
    // test would assert nothing about the hostile environment it is named for — so it is skipped,
    // not failed (which is what the retired test did).
    assumeTrue(isWindows(), "file-locking contention at boot is only meaningful on Windows");

    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    // The config has to exist before the intruder starts walking, and the intruder has to be
    // running before the index half opens a single file — that ordering IS the test.
    EngineTestHarness.publishConfig(dataDir, indexBase, commitConfig());

    intruder = new FileIntruder(dataDir);
    intruder.start(5, 10); // 5 threads, ~10 ms holds — the retired test's "very annoying" setting

    engine = EngineTestHarness.start(dataDir, indexBase, commitConfig());
    assertTrue(engine.client().isHealthy(), "the index half must become healthy despite the chaos");

    // And it must actually work, not merely answer a health call.
    List<Path> corpus = writeCorpus(tempDir, "boot-contention", CORPUS_SIZE);
    assertTrue(
        engine.client().submitBatch(corpus).getAcceptedCount() > 0,
        "documents submitted under lock contention must be accepted");
    assertTrue(
        engine.awaitSearchable("boot contention probe", 180_000),
        "documents indexed while an intruder held locks under the data directory must become"
            + " searchable — a writer that silently lost a segment to a locked file is exactly what"
            + " the retired health-check proxy could not see");
  }

  @Test
  @DisplayName("indexing survives an intruder that starts locking files mid-ingest")
  void indexingSurvivesAnIntruderThatStartsMidIngest(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    engine = EngineTestHarness.start(dataDir, indexBase, commitConfig());
    assertTrue(engine.client().isHealthy(), "the index half must be healthy before the attack");

    // The intruder starts NOW — with a populated data directory to walk, which is when it is at
    // its most hostile (the retired test's ordering, kept).
    intruder = new FileIntruder(dataDir);
    intruder.start(3, 50);

    List<Path> corpus = writeCorpus(tempDir, "ingest-contention", CORPUS_SIZE);
    assertTrue(
        engine.client().submitBatch(corpus).getAcceptedCount() > 0,
        "documents submitted under lock contention must be accepted");
    assertTrue(
        engine.awaitSearchable("ingest contention probe", 180_000),
        "documents indexed while an intruder locked files under the data directory must become"
            + " searchable");
    assertTrue(
        engine.client().isHealthy(),
        "the index half must still be healthy after the locking attack");
  }

  /**
   * Publishes counts and segments promptly enough that the assertions above are about the writer
   * rather than about commit timing. Observation granularity, not behaviour.
   */
  private static Map<String, String> commitConfig() {
    return Map.of(
        "justsearch.backfill.commit_interval_ms", "1000",
        "justsearch.backfill.max_docs_before_commit", "50");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  private static List<Path> writeCorpus(Path tempDir, String name, int count) throws IOException {
    Path corpusDir = tempDir.resolve("contention-corpus").resolve(name);
    Files.createDirectories(corpusDir);
    List<Path> paths = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Path file = corpusDir.resolve(name + "-" + i + ".txt");
      Files.writeString(
          file,
          name.replace('-', ' ') + " probe document " + i + "\n"
              + "the index half must survive a hostile filesystem while writing this\n");
      paths.add(file);
    }
    return paths;
  }
}
