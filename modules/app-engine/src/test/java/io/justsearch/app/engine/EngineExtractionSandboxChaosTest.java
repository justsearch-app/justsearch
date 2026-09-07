/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.indexerworker.fixtures.ChaosExtractionSandboxChild;
import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Lane F stage A item A12 closure — the in-process half of the retired chaos-tier
 * {@code systemtests.ExtractionSandboxChaosTest} ("Hanging, crashing and OOM children are each
 * contained; the next file indexes and the Worker never restarts").
 *
 * <p><b>The property is containment of a hostile extraction child, and it never involved the
 * Head/Worker process split.</b> The parent under test is the production
 * {@code PersistentExtractionSandbox}; only the child's <b>parser</b> is substituted, through the
 * production {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND} operator override pointing at
 * {@link ChaosExtractionSandboxChild}. That substitution is necessary, not convenient: no real
 * input wedges a parser (the tempdoc-410 adversarial corpus fails fast), so the wedge has to be
 * synthesised — and the stub runs the real {@code PolicyDrivenTikaExtractor} for every file that is
 * not marked, so "the next file extracts normally" is a real extraction rather than a canned
 * answer. {@code mode=process} forces every file out of process; under the shipped {@code auto}
 * default a {@code .txt} file is parsed in-process and would never reach the pool.
 *
 * <p><b>How the overrides are reached without a child JVM to set env vars on.</b>
 * {@code EnvRegistry.get()} checks the system property before the environment variable
 * (EnvRegistry.java:1413-1419), so {@code justsearch.extraction.sandbox.{mode,pool,command}} set on
 * this JVM are exactly the values {@code DefaultWorkerAppServices.buildContentExtractor} reads
 * (DefaultWorkerAppServices.java:501-518). The properties are set before
 * {@link EngineTestHarness#start} publishes the resolved config, because
 * {@code ResolvedConfigBuilder.contributeBaseSources()} snapshots {@code EnvRegistry} at that point
 * (ResolvedConfigBuilder.java:280-284).
 *
 * <p><b>The retired class's central polling gate was already dead, and is not carried over.</b> It
 * waited for {@code status.failure.failed_count} to advance after each chaos file. That counter is
 * {@code SELECT COUNT(*) FROM jobs WHERE state IN ('FAILED','RETRY_EXHAUSTED')}
 * (SqliteJobQueue.java:1622-1626), and tempdoc 885 items 21a/21b removed the attempts cap for
 * {@code IngestionRetryPolicy.RETRY_WITH_BACKOFF}: such a job now stays {@code PENDING} on the
 * retry ladder for a SEVEN-DAY window before {@code RETRY_EXHAUSTED}
 * (SqliteJobQueue.java:954-1000). {@code PARSER_TIMEOUT} and {@code SANDBOX_FAILED} — two of the
 * three scenarios here — are exactly that policy, so the gate could only ever time out on them.
 * Measured, not assumed: the first run of this conversion failed at 240&nbsp;s on
 * {@code chaos-hang-1.txt} with the wedge, the 60&nbsp;s kill, the {@code reason=timeout} recycle
 * and the {@code PARSER_TIMEOUT} classification all correct in the log. What replaces it is
 * per-scenario and sharper: the {@code JobBatchExtractor} branch for that file, plus a terminality
 * assertion in BOTH directions — the two retryable scenarios must leave the terminal count
 * unchanged, and the OOM ({@code IngestionRetryPolicy.NONE}, terminal on the first attempt,
 * SqliteJobQueue.java:993-994) must raise it by exactly one and name its own path.
 *
 * <p><b>Two readers the retired class carried were dropped, for opposite reasons.</b>
 * <ul>
 *   <li>{@code workerLog()} / {@code workerLogLines()} read {@code <dataDir>/logs/worker.log}.
 *       Item A13 deleted the Worker's {@code logback.xml} and there is no second process, so
 *       nothing writes that file. What it was actually asking — <em>which catch clause in
 *       {@code JobBatchExtractor} ran</em>, because that IS the retry policy — is asked directly
 *       here by attaching {@link CapturingAppender} to that logger. The assertion is stronger:
 *       it reads the events the branch emitted rather than a file a logback config happened to
 *       route them into.</li>
 *   <li>{@code restartReasons()} reads {@code <dataDir>/telemetry/metrics-worker.ndjson} and is
 *       <b>kept verbatim</b>. A13's note that "no separate process populates" it is wrong for this
 *       home: {@code KnowledgeServer.start()} constructs that {@code LocalTelemetry} itself
 *       (KnowledgeServer.java:378-384) and {@code EngineRoot} composes {@code KnowledgeServer}
 *       in-process, so the file is written by this JVM at {@code <dataDir>} — the same dataDir
 *       {@link EngineTestHarness} publishes.</li>
 * </ul>
 *
 * <p><b>"The Worker never restarts" has no in-process form, and its replacement is stronger.</b>
 * The retired test compared {@code grpcClient.getWorkerPid()} before and after each failure. Here
 * the index half is this JVM: if it died there would be no result to assert on
 * (the same reasoning {@link EngineFileLockContentionTest} recorded for its own PID checks). What
 * replaces it is the product-facing claim the PID check stood in for — the SAME
 * {@code KnowledgeClient} keeps answering, and the file submitted after each failure is indexed and
 * becomes searchable, three times over, with a monotonically rising doc count.
 *
 * <p><b>The probe-fallback trap is asserted against explicitly.</b>
 * {@code buildContentExtractor} silently falls back to in-process extraction when the child command
 * fails its startup probe (DefaultWorkerAppServices.java:532-546) — under which every assertion
 * below would pass while testing nothing. {@code probe_failed} is therefore asserted absent from
 * the restart reasons before the first chaos file is submitted.
 *
 * <p><b>Why {@code @Tag("stress")}.</b> This test deliberately wedges a parser for a whole
 * {@code TimeboxedContentExtractor.DEFAULT_TIMEOUT} (60&nbsp;s) and then exhausts a child heap, so
 * it runs for minutes by construction and its budgets are wall-clock. That is the same trade
 * {@link EngineFileLockContentionTest} made: the opt-in stress runner
 * ({@code -PincludeStress=true}, registered in {@code scripts/ci/stress-suite-policy.v1.json})
 * keeps the assertions at full strength instead of putting a multi-minute wall-clock test in the
 * default inner loop.
 */
@Tag("stress")
@Timeout(value = 20, unit = TimeUnit.MINUTES)
final class EngineExtractionSandboxChaosTest {

  /** Production extraction deadline ({@code TimeboxedContentExtractor.DEFAULT_TIMEOUT}). */
  private static final long EXTRACTION_DEADLINE_MS = 60_000L;

  /** The logger whose branch selection is the retry-policy claim. */
  private static final String EXTRACTOR_LOGGER = "io.justsearch.indexerworker.loop.JobBatchExtractor";

  /**
   * Per-file search markers, and they are nonsense words on purpose.
   *
   * <p>The first cut of this test searched for "healthy content one" / "two" / "three". Lucene's
   * query parser ORs those terms, so the query for the THIRD file matched the FIRST file's document
   * and {@code awaitSearchable} returned true before the third file had been extracted at all --
   * caught only because the doc-count assertion at the end still said 2. A marker that can only
   * match its own document is what makes "the file after a sandbox failure extracts normally" an
   * assertion rather than a coincidence.
   */
  private static final String MARKER_AFTER_TIMEOUT = "zulufixtureaftertimeout";

  private static final String MARKER_AFTER_CRASH = "zulufixtureaftercrash";

  private static final String MARKER_AFTER_OOM = "zulufixtureafteroom";

  private final Map<String, String> savedProperties = new LinkedHashMap<>();

  private EngineTestHarness engine;
  private CapturingAppender extractorLog;
  private Path dataDir;
  private Path corpusDir;

  @AfterEach
  void tearDown() {
    if (extractorLog != null) {
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EXTRACTOR_LOGGER);
      logger.detachAppender(extractorLog);
      extractorLog.stop();
      extractorLog = null;
    }
    if (engine != null) {
      engine.close();
      engine = null;
    }
    savedProperties.forEach(
        (key, value) -> {
          if (value == null) {
            System.clearProperty(key);
          } else {
            System.setProperty(key, value);
          }
        });
    savedProperties.clear();
  }

  @Test
  @DisplayName("Hanging, crashing and OOM children are each contained; the next file indexes and "
      + "the index half keeps serving")
  @Timeout(value = 15, unit = TimeUnit.MINUTES)
  void sandboxFailuresAreContainedAndTheIndexHalfSurvives(@TempDir Path tempDir) throws Exception {
    dataDir = Files.createDirectories(tempDir.resolve("data"));
    corpusDir = Files.createDirectories(tempDir.resolve("corpus"));

    override(EnvRegistry.EXTRACTION_SANDBOX_MODE.sysProp(), "process");
    override(EnvRegistry.EXTRACTION_SANDBOX_POOL.sysProp(), "1");
    override(EnvRegistry.EXTRACTION_SANDBOX_COMMAND.sysProp(), chaosChildCommand());
    // Flush fast enough that a per-scenario assertion does not wait a whole default interval for
    // extraction.sandbox_restart_total to reach the NDJSON file.
    override(EnvRegistry.TELEMETRY_FLUSH_MS.sysProp(), "1000");

    engine = EngineTestHarness.start(dataDir, dataDir.resolve("index"), Map.of());
    attachExtractorLog();
    assertTrue(engine.client().isHealthy(), "the index half must be healthy before the chaos");

    // The silent-fallback trap: if the child command had failed its startup probe, extraction would
    // be in-process and every assertion below would pass while testing nothing.
    assertFalse(restartReasons().contains("probe_failed"),
        "the chaos child must have passed its startup probe, or the sandbox silently falls back to "
            + "in-process extraction and this test asserts nothing; command="
            + System.getProperty(EnvRegistry.EXTRACTION_SANDBOX_COMMAND.sysProp()));

    long baselineTerminal = engine.status().getFailure().getFailedCount();
    assertEquals(0L, baselineTerminal, "a fresh data dir must start with no terminally failed jobs");

    // --- 1. Hanging child: killed at the deadline, RETRYABLE, next file extracts normally.
    Path hanging = corpusFile("chaos-hang-1.txt", "this parse never returns");
    List<String> hangLines =
        submitAndAwaitExtractorBranch(
            hanging, "Extraction timeout for", EXTRACTION_DEADLINE_MS + 180_000);
    assertTrue(hangLines.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("timed out")),
        "the failure reason must name the timeout; got: " + hangLines);
    awaitRestartReason("timeout", 30_000);
    assertEquals(baselineTerminal, engine.status().getFailure().getFailedCount(),
        "PARSER_TIMEOUT is IngestionRetryPolicy.RETRY_WITH_BACKOFF, so the job must stay PENDING "
            + "on the retry ladder — NOT terminal (SqliteJobQueue.java:994-1000)");

    retireChaosFile(hanging);
    submitAndAwaitSearchable(
        corpusFile("after-timeout.txt", "healthy content " + MARKER_AFTER_TIMEOUT),
        MARKER_AFTER_TIMEOUT);

    // --- 2. Crashing child: exit code in the reason, RETRYABLE, next file extracts normally.
    Path crashing = corpusFile("chaos-crash-1.txt", "this parse exits 3");
    List<String> crashLines =
        submitAndAwaitExtractorBranch(crashing, "Extraction sandbox failed for", 180_000);
    awaitRestartReason("crash", 30_000);
    assertTrue(crashLines.stream().anyMatch(l -> l.contains("exited with code 3")),
        "the sandbox failure reason must carry the child exit code; got: " + crashLines);
    assertEquals(baselineTerminal, engine.status().getFailure().getFailedCount(),
        "SANDBOX_FAILED is RETRY_WITH_BACKOFF too, so this one must not be terminal either");

    retireChaosFile(crashing);
    submitAndAwaitSearchable(
        corpusFile("after-crash.txt", "healthy content " + MARKER_AFTER_CRASH),
        MARKER_AFTER_CRASH);

    // --- 3. OOM child: PERMANENT parse failure, next file extracts normally.
    Path oom = corpusFile("chaos-oom-1.txt", "this parse exhausts the child heap");
    // Which catch clause in JobBatchExtractor ran IS the retry policy: a plain ExtractionException
    // is PARSER_FAILED + IngestionRetryPolicy.NONE ("Content extraction failed for",
    // JobBatchExtractor.java:353-370), while a SandboxExtractionException is SANDBOX_FAILED +
    // RETRY_WITH_BACKOFF ("Extraction sandbox failed for", JobBatchExtractor.java:336-352).
    // Asserting the branch is the permanence claim, and the terminal-count assertion below is the
    // same claim read off the queue — two independent signals for one property.
    List<String> oomLines =
        submitAndAwaitExtractorBranch(oom, "Content extraction failed for", 240_000);
    awaitRestartReason("oom", 30_000);
    assertTrue(oomLines.stream().anyMatch(l -> l.contains("exhausted its heap")),
        "child OOM must be reported as heap exhaustion, not a generic sandbox failure; got: "
            + oomLines);
    assertTrue(extractorLogLines("Extraction sandbox failed for").stream()
            .noneMatch(l -> l.contains(oom.getFileName().toString())),
        "child OOM must NOT be classified as a retryable sandbox failure; got: "
            + extractorLogLines("Extraction sandbox failed for"));
    awaitTerminalFailure(oom, baselineTerminal + 1, 60_000);

    retireChaosFile(oom);
    submitAndAwaitSearchable(
        corpusFile("after-oom.txt", "healthy content " + MARKER_AFTER_OOM),
        MARKER_AFTER_OOM);

    // --- The headline: one index half served through all three failure classes.
    assertTrue(engine.client().isHealthy(),
        "the index half must still be healthy after three sandbox failures");
    assertTrue(engine.awaitSearchable(MARKER_AFTER_TIMEOUT, 30_000)
            && engine.awaitSearchable(MARKER_AFTER_CRASH, 30_000)
            && engine.awaitSearchable(MARKER_AFTER_OOM, 30_000),
        "all three post-failure files must still be findable at the end, not just at the moment "
            + "each was written");
    awaitDocCount(3, 120_000);
  }

  // =========================================================================
  // Overrides
  // =========================================================================

  /** Sets a system property for the duration of the test, remembering what to put back. */
  private void override(String key, String value) {
    savedProperties.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  /**
   * The operator-override argv for the chaos child. The classpath goes into a JVM {@code @argfile}
   * because a fully expanded Gradle test classpath clears the Windows 32,767-character command-line
   * limit on its own — the same reason {@code ExtractionSandboxCommand} has an argfile fallback.
   * That is a LENGTH constraint, not a quoting one: since tempdoc 885's residue fix the override is
   * quote-aware, so the two paths below are quoted and may contain spaces.
   */
  private String chaosChildCommand() throws IOException {
    String javaExe = Path.of(System.getProperty("java.home"), "bin",
        isWindows() ? "java.exe" : "java").toString();
    Path argFile = dataDir.resolve("chaos-sandbox-child-args.txt");
    // 128m: large enough for Tika on the small text fixtures, small enough that the chaos-oom
    // file exhausts the child heap in seconds.
    String args = "-Xmx128m\n"
        + "-Dfile.encoding=UTF-8\n"
        + "-cp\n"
        + "\"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n"
        + ChaosExtractionSandboxChild.class.getName() + "\n";
    Files.writeString(argFile, args, StandardCharsets.UTF_8);
    return "\"" + javaExe + "\" \"@" + argFile.toAbsolutePath() + "\"";
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }

  // =========================================================================
  // Drivers
  // =========================================================================

  private Path corpusFile(String name, String content) throws IOException {
    Path file = corpusDir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  /**
   * Submits {@code file} and waits until {@code JobBatchExtractor} has logged {@code branchPrefix}
   * for it; returns every captured line naming this file.
   *
   * <p><b>This replaces the retired class's "wait for {@code status.failure.failed_count} to
   * advance", which is unreachable and had been for some time.</b> That counter is
   * {@code SELECT COUNT(*) FROM jobs WHERE state IN ('FAILED','RETRY_EXHAUSTED')}
   * (SqliteJobQueue.java:1622-1626), and tempdoc 885 items 21a/21b stopped a
   * {@code RETRY_WITH_BACKOFF} outcome from ever reaching either state inside a test's lifetime:
   * it stays {@code PENDING} on the retry ladder for a SEVEN-DAY window before
   * {@code RETRY_EXHAUSTED} (SqliteJobQueue.java:954-1000). Two of the three chaos scenarios here
   * are {@code RETRY_WITH_BACKOFF}, so the retired gate could only ever time out on them — as it
   * did on first run of this conversion, at 240&nbsp;s, with the wedge, the kill and the
   * classification all correct in the log. The branch the extractor took is the thing the test was
   * really asking about; this asks for it directly.
   */
  private List<String> submitAndAwaitExtractorBranch(
      Path file, String branchPrefix, long timeoutMs) throws Exception {
    assertEquals(1, engine.client().submitBatch(List.of(file.toAbsolutePath())).getAcceptedCount(),
        "the index half must accept " + file.getFileName());
    String name = file.getFileName().toString();
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<String> matched =
          extractorLogLines(branchPrefix).stream().filter(l -> l.contains(name)).toList();
      if (!matched.isEmpty()) {
        return matched;
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "JobBatchExtractor never logged \"" + branchPrefix + "\" for " + name + " within "
            + timeoutMs + "ms. Everything it did log: " + extractorLogLines(""));
  }

  /**
   * Waits until {@code status.core.doc_count} reaches {@code expected}.
   *
   * <p>Polled rather than read once, because it is not the same reader the searches above went
   * through: {@code IndexStatusOps} derives it as {@code writtenOrServedCountOps.docCount() -
   * chunkDocs} (IndexStatusOps.java:291-297), which trails the near-real-time searcher. Measured on
   * this test: all three markers were findable while the count still said 2. A single read there is
   * a race, not an assertion — but dropping the count entirely would lose the one check that
   * catches a marker matching the WRONG document, which is exactly the bug that made the first
   * version of this test green with only two of the three files extracted.
   */
  private void awaitDocCount(long expected, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long seen = -1;
    while (System.currentTimeMillis() < deadline) {
      seen = engine.status().getCore().getDocCount();
      if (seen >= expected) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "the doc count must carry all " + expected + " post-failure files; it stalled at " + seen
            + " after " + timeoutMs + "ms even though all three markers were searchable");
  }

  /**
   * Waits until the queue reports exactly {@code expectedTerminal} terminally failed jobs AND names
   * {@code file} as the most recent one.
   *
   * <p>Only {@code IngestionRetryPolicy.NONE} reaches {@code STATE_FAILED} on the first attempt
   * (SqliteJobQueue.java:993-994), which is precisely why this is the OOM scenario's assertion and
   * not the other two's: it distinguishes "permanent" from "retryable" through the queue rather
   * than through a log line, so the permanence claim does not rest on one signal.
   */
  private void awaitTerminalFailure(Path file, long expectedTerminal, long timeoutMs)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    StatusResponse last = null;
    while (System.currentTimeMillis() < deadline) {
      last = engine.status();
      // Paths are normalised to lower case on Windows, so compare case-insensitively.
      if (last.getFailure().getFailedCount() == expectedTerminal
          && file.toAbsolutePath().toString()
              .equalsIgnoreCase(last.getFailure().getLastFailedPath())) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "Expected exactly " + expectedTerminal + " terminally failed job(s), the newest being "
            + file.toAbsolutePath() + ". Last status: failedCount="
            + (last == null ? "?" : last.getFailure().getFailedCount()) + " lastFailedPath="
            + (last == null ? "?" : last.getFailure().getLastFailedPath()));
  }

  /**
   * Deletes a chaos file once its failure has been recorded.
   *
   * <p>{@code PARSER_TIMEOUT} and {@code SANDBOX_FAILED} are {@code RETRY_WITH_BACKOFF} outcomes,
   * so the job comes back. With a pool of one, a retrying hang re-wedges the only child for another
   * whole deadline and the next file queues behind it — which would make this test measure the
   * retry ladder rather than the containment property it is about.
   */
  private void retireChaosFile(Path file) throws IOException {
    Files.deleteIfExists(file);
  }

  /**
   * Submits {@code file} and waits until its content is findable.
   *
   * <p>Searchability rather than {@code EngineTestHarness#awaitIndexed}: that helper waits for
   * {@code queueDepth == 0}, and a retired chaos file's own retry sits PENDING on the ladder for
   * the rest of the run, so the queue is legitimately non-empty. Searchability is also the stronger
   * claim — it says the file was extracted by a fresh child AND written by a writer that survived
   * the previous failure, which is what "the next file extracts normally" means.
   */
  private void submitAndAwaitSearchable(Path file, String marker) throws Exception {
    assertEquals(1, engine.client().submitBatch(List.of(file.toAbsolutePath())).getAcceptedCount(),
        "the index half must accept " + file.getFileName());
    assertTrue(engine.awaitSearchable(marker, 180_000),
        "the file after a sandbox failure must extract normally and become searchable: "
            + file.getFileName());
  }

  // =========================================================================
  // Evidence
  // =========================================================================

  /**
   * All {@code extraction.sandbox_restart_total} reason tags flushed so far, read verbatim from the
   * retired class. The file is written by this JVM's own {@code KnowledgeServer}-owned
   * {@code LocalTelemetry} (KnowledgeServer.java:378-384).
   */
  private List<String> restartReasons() throws IOException {
    Path metrics = dataDir.resolve("telemetry").resolve("metrics-worker.ndjson");
    List<String> reasons = new ArrayList<>();
    if (!Files.exists(metrics)) {
      return reasons;
    }
    for (String line : Files.readAllLines(metrics, StandardCharsets.UTF_8)) {
      if (!line.contains("\"name\":\"extraction.sandbox_restart_total\"")) {
        continue;
      }
      int at = line.indexOf("\"reason\":\"");
      if (at >= 0) {
        int start = at + "\"reason\":\"".length();
        int end = line.indexOf('"', start);
        if (end > start) {
          reasons.add(line.substring(start, end));
        }
      }
    }
    return reasons;
  }

  private void awaitRestartReason(String reason, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    List<String> seen = List.of();
    while (System.currentTimeMillis() < deadline) {
      seen = restartReasons();
      if (seen.contains(reason)) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("extraction.sandbox_restart_total{reason=" + reason
        + "} never reached the metrics file; observed reasons=" + seen);
  }

  private void attachExtractorLog() {
    extractorLog = new CapturingAppender();
    extractorLog.start();
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EXTRACTOR_LOGGER);
    logger.addAppender(extractorLog);
  }

  /** {@code JobBatchExtractor} lines containing {@code needle} — the branch a failure took. */
  private List<String> extractorLogLines(String needle) {
    return extractorLog.events.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(line -> line.contains(needle))
        .toList();
  }

  /**
   * The in-process stand-in for reading a log FILE back (the retired class read
   * {@code logs/worker.log}; item A16 left one log, {@code logs/engine.log}, and this test does not
   * read it). A plain logback {@code ListAppender} backs
   * onto an {@code ArrayList}, and the indexing loop appends from its own thread while the test
   * thread polls — so this uses a copy-on-write list rather than risking a
   * {@code ConcurrentModificationException} that would read as a product failure.
   */
  private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      events.add(event);
    }
  }
}
