/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.fixtures.ChaosExtractionSandboxChild;
import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lane F stage A item A12 closure — the surviving process-boundary half of the retired chaos-tier
 * {@code systemtests.ExtractionSandboxChaosTest} ("Killing the Worker mid-parse leaves no orphaned
 * extraction child").
 *
 * <p><b>Why this one did NOT convert in-process.</b> The property is the extraction child's
 * parent-PID gate ({@code ExtractionSandboxChild#initializeProcessBoundary}): when the process that
 * spawned it disappears, the child must halt itself, because a wedged child is not reading stdin
 * and so the pipe-EOF exit cannot reach it. Testing that needs a <em>killable parent</em>. Composed
 * in-process against {@code EngineRoot}, that parent is the JUnit JVM — killing it destroys the
 * test run and there is nothing left to assert with. {@link IsolatedBackendFixture} already spawns
 * a killable {@code io.justsearch.ui.HeadlessApp} child JVM, which since item A11 runs the index
 * half itself, so it is the Engine-era stand-in for the Worker process this test used to kill.
 *
 * <p><b>What is substituted and what is production.</b> Only the sandbox child's <em>parser</em> is
 * replaced, through the production {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND} operator override
 * pointing at {@link ChaosExtractionSandboxChild}; the parent is production
 * {@code PersistentExtractionSandbox}, and the orphan gate the assertion is about is production
 * code the stub calls directly. {@code JUSTSEARCH_EXTRACTION_SANDBOX_MODE=process} is required:
 * under the shipped {@code auto} default a {@code .txt} file is parsed in-process and no child
 * would ever exist. Pool size 1 keeps the descendant set unambiguous.
 *
 * <p><b>Dropped from the retired class.</b> {@code workerLog()} (it read
 * {@code <dataDir>/logs/worker.log}, which item A13 stopped writing when it deleted the Worker's
 * logback config) and {@code restartReasons()} (the retired class read the metrics NDJSON to name
 * WHY the pool recycled; this test never asserted on a restart reason — its sibling, the in-process
 * {@code io.justsearch.app.engine.EngineExtractionSandboxChaosTest}, is where that evidence lives).
 * The heartbeat keeper and {@code MmfTestHarness.awaitPort} went with the memory-mapped bus at item
 * A10; the fixture's manifest/health/worker-ready poll replaces them.
 *
 * <p>The child-PID and live-native-PID witnesses prevent vacuous success if the startup probe
 * fails. Production preserves process isolation on probe failure; it never falls back to parsing
 * these files in-process.
 */
@DisplayName("Chaos: killing the Engine mid-parse leaves no orphaned extraction child")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ExtractionSandboxOrphanE2ETest {

  private static final Logger log =
      LoggerFactory.getLogger(ExtractionSandboxOrphanE2ETest.class);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final long CHILD_SPAWN_TIMEOUT_MS = 180_000L;
  private static final long ORPHAN_REAP_TIMEOUT_MS = 30_000L;

  private static final IsolatedBackendFixture backend = new IsolatedBackendFixture();
  private static final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static Path scratchDir;
  private static Path corpusDir;

  @BeforeAll
  static void setup() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(isWindows(), "native containment is Windows-only");
    scratchDir = Files.createTempDirectory("extraction-orphan-");
    corpusDir = Files.createDirectories(scratchDir.resolve("corpus"));
    // The wedged file has to exist before the root is added: adding a root walks it immediately.
    Files.writeString(
        corpusDir.resolve("chaos-hang-native-descendant.txt"),
        "this parse never returns",
        StandardCharsets.UTF_8);

    backend
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_MODE", "process")
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_POOL", "1")
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND", chaosChildCommand())
        .start();
  }

  @AfterAll
  static void teardown() {
    backend.stop();
    deleteRecursively(scratchDir);
  }

  @Test
  void killingTheEngineMidParseLeavesNoOrphanChild() throws Exception {
    long enginePid = backend.pid();
    Set<Long> before = descendantPids(enginePid);

    assertTrue(addRoot(corpusDir.toAbsolutePath().toString()),
        "the Engine must accept the chaos corpus as an indexing root");

    Path nativePidFile = corpusDir.resolve("chaos-hang-native-descendant.txt.pid");
    long nativeDeadline = System.nanoTime() + Duration.ofMillis(CHILD_SPAWN_TIMEOUT_MS).toNanos();
    while (!Files.exists(nativePidFile) && System.nanoTime() < nativeDeadline) {
      Thread.sleep(20);
    }
    assertTrue(Files.exists(nativePidFile), "the parser must start a real native descendant");
    ProcessHandle nativeChild = ProcessHandle.of(Long.parseLong(Files.readString(nativePidFile)))
        .orElseThrow();
    long childPid = nativeChild.parent().orElseThrow().pid();
    assertTrue(!before.contains(childPid) && childPid != enginePid,
        "the native process must come from a newly spawned parser, not the Engine or boot probe");
    Set<Long> witnessed = descendantPids(enginePid);
    assertTrue(witnessed.contains(childPid) && witnessed.contains(nativeChild.pid()),
        "both parser and native child must belong to the live Engine before its kill");
    assertTrue(isAlive(childPid), "the parser must still be alive before Engine kill");
    assertTrue(nativeChild.isAlive(), "native descendant must be alive BEFORE Engine kill");
    System.err.println("[ExtractionSandboxOrphanE2ETest] extraction child " + childPid
        + " is wedged under Engine " + enginePid);
    System.err.println("[ExtractionSandboxOrphanE2ETest] native descendant " + nativeChild.pid()
        + " is live before Engine kill");
    try {
      // Kill the Engine outright, so the assertion cannot pass on the graceful shutdown-hook path.
      assertTrue(backend.kill(), "the Engine must terminate after destroyForcibly()");
      assertFalse(isAlive(enginePid),
          "the Engine process must be gone before the orphan window opens");

      long deadline = System.currentTimeMillis() + ORPHAN_REAP_TIMEOUT_MS;
      boolean gone = false;
      while (System.currentTimeMillis() < deadline) {
        if (!isAlive(childPid)) {
          gone = true;
          break;
        }
        Thread.sleep(250);
      }
      if (!gone) {
        ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
      }
      System.err.println(
          "[ExtractionSandboxOrphanE2ETest] child " + childPid + " reaped after "
              + (ORPHAN_REAP_TIMEOUT_MS - (deadline - System.currentTimeMillis())) + "ms");
      assertTrue(gone, "extraction child " + childPid
          + " must halt itself once the Engine is gone (parent-PID gate)");
      nativeChild.onExit().get(10, TimeUnit.SECONDS);
      assertFalse(nativeChild.isAlive(), "native descendant must die with its parser job");
      System.err.println("[ExtractionSandboxOrphanE2ETest] native descendant " + nativeChild.pid()
          + " reaped after Engine kill");
    } finally {
      if (nativeChild.isAlive()) {
        nativeChild.destroyForcibly();
        nativeChild.onExit().get(10, TimeUnit.SECONDS);
      }
    }
  }

  // =========================================================================
  // Chaos child wiring
  // =========================================================================

  /**
   * The operator-override argv for the chaos child. The classpath goes into a JVM {@code @argfile}
   * because a fully expanded Gradle test classpath clears the Windows 32,767-character command-line
   * limit on its own — the same reason {@code ExtractionSandboxCommand} has an argfile fallback.
   * That is a LENGTH constraint, not a quoting one: since tempdoc 885's residue fix the override is
   * quote-aware, so the two paths below are quoted and may contain spaces.
   *
   * <p>This JVM's {@code java.class.path} is the right one to hand the child: the fixture spawns the
   * Head off that same classpath ({@code IsolatedBackendFixture#writeArgfile}), so a class this JVM
   * can load is a class the Head's child can load.
   */
  private static String chaosChildCommand() throws IOException {
    String javaExe = Path.of(System.getProperty("java.home"), "bin",
        isWindows() ? "java.exe" : "java").toString();
    Path argFile = scratchDir.resolve("chaos-sandbox-child-args.txt");
    // 128m matches the sibling in-process test; this one never OOMs a child, but keeping the two
    // child JVMs identical means a failure here is not explained away by a heap difference.
    String args = "-Xmx128m\n--enable-native-access=ALL-UNNAMED\n"
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
  // Process observation
  // =========================================================================

  private static Set<Long> descendantPids(long pid) {
    return ProcessHandle.of(pid)
        .map(handle -> handle.descendants().map(ProcessHandle::pid)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new)))
        .orElseGet(HashSet::new);
  }

  private static boolean isAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  // =========================================================================
  // API
  // =========================================================================

  private static boolean addRoot(String path) throws Exception {
    String jsonBody = "{\"path\":" + jsonString(path) + ",\"collection\":\"chaos-orphan\"}";
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + backend.port() + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      log.error("Failed to add root ({}): {}", response.statusCode(), response.body());
      return false;
    }
    return true;
  }

  /** Minimal JSON string escaping — the only dynamic value here is a Windows filesystem path. */
  private static String jsonString(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
      for (Path path : paths) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          // A file a dying child still holds open; the OS temp sweep reclaims it.
        }
      }
    } catch (IOException e) {
      log.warn("Failed to clean scratch dir {}: {}", root, e.getMessage());
    }
  }
}
