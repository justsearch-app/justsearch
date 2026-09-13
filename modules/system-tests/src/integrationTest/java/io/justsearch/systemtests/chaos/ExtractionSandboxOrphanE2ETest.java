/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.fixtures.ChaosExtractionSandboxChild;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.RuntimeManifest;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

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
 * <p>A harmless request prewarms the production pool, and the hanging request must reuse that
 * exact parser instance. Exact target, direct-parent, manifest ownership and live-native witnesses
 * prevent vacuous success if the startup probe fails. Production preserves process isolation on probe failure; it never falls back to parsing
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
  private static Path prewarmDir;
  private static Path enteredFile;

  @BeforeAll
  static void setup() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(isWindows(), "native containment is Windows-only");
    scratchDir = Files.createTempDirectory("extraction-orphan-");
    corpusDir = Files.createDirectories(scratchDir.resolve("corpus"));
    prewarmDir = Files.createDirectories(scratchDir.resolve("prewarm"));
    enteredFile = scratchDir.resolve("entered.txt");
    Files.writeString(prewarmDir.resolve("orphan-prewarm.txt"), "warm the persistent parser");
    // The wedged file has to exist before the root is added: adding a root walks it immediately.
    Files.writeString(
        corpusDir.resolve("chaos-hang-native-descendant.txt"),
        "this parse never returns",
        StandardCharsets.UTF_8);

    backend
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_MODE", "process")
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_POOL", "1")
        .withEnv("JUSTSEARCH_EXTRACTION_SANDBOX_MAX_REQUESTS", "500")
        .withEnv("JUSTSEARCH_PROCESSING_TEST_ENTERED", enteredFile.toString())
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
    ProcessInstance engine = ProcessInstance.capture(ProcessHandle.of(backend.pid()).orElseThrow());
    ProcessInstance ownedParser = null;
    ProcessInstance nativeInstance = null;
    Throwable primaryFailure = null;
    try {
      assertTrue(addRoot(prewarmDir.toAbsolutePath().toString()), "the prewarm root must be accepted");
      Path prewarmWitness = prewarmDir.resolve("orphan-prewarm.txt.parser-identity");
      awaitFile(prewarmWitness, "the harmless request must finish in the production parser pool");
      ProcessInstance warmed = readIdentity(prewarmWitness);
      assertTrue(warmed.isCurrentAndAlive(), "the response witness must name the same live parser instance");
      assertEquals(engine, parentOf(warmed), "the warmed parser must be a direct Engine child");
      awaitRegisteredExtraction(warmed);
      ownedParser = warmed;
      List<String> before = descendantDescriptions(engine.pid());
      System.err.println("[ExtractionSandboxOrphanE2ETest] prewarm parser=" + warmed
          + "; Engine=" + engine + "; before=" + before);

      assertTrue(addRoot(corpusDir.toAbsolutePath().toString()),
          "the Engine must accept the chaos corpus as an indexing root");
      Path target = corpusDir.resolve("chaos-hang-native-descendant.txt");
      Path nativePidFile = Path.of(target + ".pid");
      awaitFile(nativePidFile, "the parser must start a real native descendant");
      awaitFile(enteredFile, "the exact chaos request must enter the wedged parser");
      assertEquals(target.toAbsolutePath().normalize(),
          Path.of(Files.readString(enteredFile)).toAbsolutePath().normalize(),
          "the fixture witness must belong to this exact target, not a probe or unrelated request");
      ProcessHandle nativeChild = ProcessHandle.of(Long.parseLong(Files.readString(nativePidFile)))
          .orElseThrow();
      nativeInstance = readIdentity(Path.of(target + ".native-identity"));
      assertTrue(nativeInstance.matches(nativeChild), "native PID must match the instance published by its creator");
      ProcessInstance parser = parentOf(nativeInstance);
      System.err.println("[ExtractionSandboxOrphanE2ETest] native=" + nativeInstance
          + "; native parent=" + parser + "; parser parent=" + parentOf(parser)
          + "; registered extraction=" + extractionChildren());
      assertEquals(warmed, parser, "the hanging request must reuse the prewarmed process instance");
      assertFalse(parser.pid() == engine.pid(), "the parser must be separate from the Engine");
      assertEquals(engine, parentOf(parser), "the parser's direct parent must be this Engine instance");
      awaitRegisteredExtraction(parser);
      assertTrue(parser.isCurrentAndAlive(), "the parser must be alive before Engine kill");
      assertTrue(nativeInstance.isCurrentAndAlive(), "native descendant must be alive before Engine kill");
      var descendants = ProcessHandle.of(engine.pid()).orElseThrow().descendants().toList();
      assertTrue(descendants.stream().anyMatch(parser::matches), "parser belongs to the live Engine");
      ProcessInstance witnessedNative = nativeInstance;
      assertTrue(descendants.stream().anyMatch(witnessedNative::matches), "native belongs to the live Engine");

      // Forced death must exercise the parent-PID gate, not the graceful shutdown hook.
      assertTrue(backend.kill(), "the Engine must terminate after destroyForcibly()");
      assertFalse(engine.isCurrentAndAlive(), "the exact Engine instance must be gone first");
      long started = System.nanoTime();
      long deadline = started + Duration.ofMillis(ORPHAN_REAP_TIMEOUT_MS).toNanos();
      while (parser.isCurrentAndAlive() && System.nanoTime() < deadline) Thread.sleep(250);
      assertFalse(parser.isCurrentAndAlive(), "the exact parser must halt after Engine death: " + parser);
      nativeChild.onExit().get(10, TimeUnit.SECONDS);
      assertFalse(nativeInstance.isCurrentAndAlive(), "the exact native descendant must die with its parser job");
      System.err.println("[ExtractionSandboxOrphanE2ETest] reused parser and native reaped after "
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "ms; parser=" + parser
          + "; native=" + nativeInstance);
    } catch (Exception | AssertionError failure) {
      primaryFailure = failure;
      backend.preserveLogOnFailure();
      throw failure;
    } finally {
      try {
        cleanupOwned(nativeInstance, ownedParser);
      } catch (Exception | AssertionError cleanupFailure) {
        backend.preserveLogOnFailure();
        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
        else throw cleanupFailure;
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

  /** Strict identity: unknown start/executable data fails proof instead of claiming an exit. */
  private record ProcessInstance(long pid, Instant startedAt, String executable) {
    static ProcessInstance capture(ProcessHandle handle) {
      var info = handle.info();
      return new ProcessInstance(handle.pid(), info.startInstant().orElseThrow(),
          ManagedChild.normalizePath(Path.of(info.command().orElseThrow())));
    }

    boolean matches(ProcessHandle handle) {
      if (handle.pid() != pid) return false;
      var info = handle.info();
      if (info.startInstant().isEmpty() || info.command().isEmpty()) {
        assertFalse(handle.isAlive(), "a live process with unavailable identity cannot prove an exit");
        return false;
      }
      return info.startInstant().orElseThrow().equals(startedAt)
          && ManagedChild.normalizePath(Path.of(info.command().orElseThrow())).equals(executable);
    }

    boolean isCurrentAndAlive() {
      return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(this::matches).orElse(false);
    }

    void destroyIfCurrent() throws Exception {
      var current = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(this::matches);
      if (current.isEmpty()) return;
      ProcessHandle handle = current.orElseThrow();
      handle.destroyForcibly();
      handle.onExit().get(10, TimeUnit.SECONDS);
      assertFalse(isCurrentAndAlive(), "owned process must exit during bounded cleanup");
    }
  }

  private static ProcessInstance readIdentity(Path witness) throws IOException {
    List<String> fields = Files.readAllLines(witness);
    assertEquals(3, fields.size(), "identity witness must be complete");
    return new ProcessInstance(Long.parseLong(fields.get(0)), Instant.parse(fields.get(1)), fields.get(2));
  }

  private static void cleanupOwned(ProcessInstance nativeChild, ProcessInstance parser) throws Exception {
    Throwable failure = null;
    for (ProcessInstance child : new ProcessInstance[] {nativeChild, parser}) {
      if (child == null) continue;
      try {
        child.destroyIfCurrent();
      } catch (Exception | AssertionError cleanupFailure) {
        if (failure == null) failure = cleanupFailure;
        else failure.addSuppressed(cleanupFailure);
      }
    }
    if (failure instanceof Exception exception) throw exception;
    if (failure instanceof AssertionError error) throw error;
  }

  private static ProcessInstance parentOf(ProcessInstance child) {
    ProcessHandle handle = ProcessHandle.of(child.pid()).orElseThrow();
    assertTrue(child.matches(handle), "child identity changed before its parent was observed");
    return ProcessInstance.capture(handle.parent().orElseThrow());
  }

  private static List<String> descendantDescriptions(long pid) {
    return ProcessHandle.of(pid).orElseThrow().descendants()
        .map(handle -> handle.pid() + "@" + handle.info().startInstant()
            + ":" + handle.info().command()).toList();
  }

  private static void awaitFile(Path path, String message) throws Exception {
    long deadline = System.nanoTime() + Duration.ofMillis(CHILD_SPAWN_TIMEOUT_MS).toNanos();
    while ((!Files.exists(path) || Files.size(path) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
    assertTrue(Files.exists(path) && Files.size(path) > 0, message);
  }

  private static List<ManagedChild> extractionChildren() throws IOException {
    var manifest = JsonMapper.builder().build().readValue(
        Files.readString(backend.dataDir().resolve("runtime/manifest.json")), RuntimeManifest.class);
    return manifest.children() == null ? List.of() : manifest.children().stream()
        .filter(child -> child.kind() == ManagedChild.Kind.EXTRACTION).toList();
  }

  private static void awaitRegisteredExtraction(ProcessInstance parser) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    List<ManagedChild> children;
    do {
      children = extractionChildren();
      if (children.stream().anyMatch(child -> child.pid() == parser.pid()
          && Instant.parse(child.startedAt()).equals(parser.startedAt())
          && child.executable().equals(parser.executable()))) return;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Parser instance absent from private EXTRACTION manifest: "
        + parser + "; children=" + children);
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
