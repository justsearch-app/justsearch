package io.justsearch.systemtests.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Per-class isolated backend fixture (tempdoc 419 / T6.2).
 *
 * <p>Spawns {@code io.justsearch.ui.HeadlessApp} in a child JVM with:
 *
 * <ul>
 *   <li>{@code JUSTSEARCH_LITE_MODE=true} — skip the AI stack (T6.1 substrate)
 *   <li>A fresh tempdir as {@code JUSTSEARCH_DATA_DIR}
 *   <li>{@code JUSTSEARCH_API_PORT=0} — request an OS-assigned ephemeral port
 *   <li>The test JVM's full classpath, passed via Java's {@code @argfile} syntax to
 *       sidestep the Windows 8191-character command-line limit
 * </ul>
 *
 * <p>Tests use the fixture as a {@code @BeforeAll} / {@code @AfterAll} pair:
 *
 * <pre>{@code
 * class MyTest {
 *   static final IsolatedBackendFixture backend = new IsolatedBackendFixture();
 *   @BeforeAll static void setup() throws Exception { backend.start(); }
 *   @AfterAll  static void teardown()              { backend.stop(); }
 *   @Test void myTest() throws Exception {
 *     int port = backend.port();
 *     // ...HTTP calls against localhost:port...
 *   }
 * }
 * }</pre>
 *
 * <p>The startup sequence is two-phase: poll {@code dataDir/runtime/manifest.json} for the
 * port (HeadlessApp writes it after Javalin binds, per tempdoc 501), then poll
 * {@code /api/health} until it returns 200. Empirical measurement (PR8 spike report, commit
 * {@code e7ceeba8e}): the manifest appears ~200&nbsp;ms before the health endpoint accepts
 * requests on Windows; both phases are required.
 *
 * <p>Cleanup uses {@link Process#destroyForcibly()} (~0.42&nbsp;s on Windows in the spike).
 * The spawned Head used to spawn its own Worker subprocess, and killing the parent killed the child
 * too via {@code WorkerSpawner}'s Job-Object cleanup; lane F stage A item A11 deleted both, so there
 * is one process to kill. SQLite WAL files released
 * cleanly in the spike — the tempdir delete retry loop is defense-in-depth, not load-bearing.
 *
 * <p><strong>Known limitation:</strong> if the test JVM itself crashes, the spawned Head is
 * orphaned (no Job Object binds it to the test JVM). The orphan listens on an ephemeral
 * port (no stuck-port issue) but must be killed manually. Test-JVM crashes are rare;
 * mitigation is not in scope for T6.2.
 */
public final class IsolatedBackendFixture {

  private static final long PORT_FILE_TIMEOUT_MS = 60_000L;
  // Tempdoc 821 P4 set this to 90s against a measured port-file->health delta of
  // min 2.45s / p50 2.74s / max 7.02s (48 samples, windows-latest, 2026-08-13). Later the
  // same day 90s proved marginal anyway: three integration-lane failures raised THIS
  // budget's own error ("/api/health did not return 200 within 90000ms") rather than the
  // JUnit cap, so the excursion is real and larger than 90s —
  //   - run 31730197618 (PR #429): OperationPreviewE2ETest exhausted all 3 attempts,
  //     ~100s apart; a full rerun of the same lane was green.
  //   - run 31732439890 (PR #430): IngestStarvationE2ETest failed once and passed on the
  //     in-run retry ~40s later.
  //   - run 31732439890 (rerun): IndexingLedgerCoherenceTest failed once and passed on the
  //     in-run retry ~50s later.
  // The retry passes prove the backend does boot on those runners, so the first attempt was
  // losing a race against a contended 4-vCPU host, not hitting a dead backend. How far past
  // 90s the excursion actually goes is still unmeasured — the fixture is killed at the
  // budget, so the tail is censored. 240s is therefore a headroom choice, not a measured
  // one; the preserved boot logs this change ships to CI artifacts are what will replace it
  // with a measured value. A genuinely dead backend still fails fast via the
  // process.isAlive() check below, and the enclosing budgets
  // (modules/system-tests/build.gradle.kts) are sized to keep this one binding.
  private static final long HEALTH_TIMEOUT_MS = 240_000L;
  private static final long WORKER_READY_TIMEOUT_MS = 90_000L;
  private static final long POLL_INTERVAL_MS = 200L;
  private static final long STOP_GRACE_MS = 5_000L;
  private static final int CLEANUP_ATTEMPTS = 3;
  private static final long CLEANUP_BACKOFF_MS = 500L;
  private static final int LOG_TAIL_LINES = 500;
  private static final int HEALTH_BODY_EXCERPT_CHARS = 500;

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

  /**
   * Tempdoc 825 (charter item 3): the TERMINAL worker reason code. Its whole reason for existing is
   * that it is unambiguous — {@code worker.spawn.failed} means "failed, recovery pending or in
   * flight" and would fail fast on a boot that is about to succeed, which is why tempdoc 836
   * deliberately left this fixture blind. Once this appears in the body, the Head has stopped
   * trying, so every remaining millisecond of the health budget is spent waiting for nothing.
   */
  private static final List<String> TERMINAL_WORKER_REASONS =
      List.of("worker.spawn_recovery_exhausted");

  private final String ownerLabel = resolveOwnerLabel();

  /**
   * Extra {@code -D} system properties for the spawned Head (tempdoc 825): the boot fault injector
   * is the only way to exercise recovery deterministically, and it is a launch-time config value.
   */
  private final Map<String, String> extraSystemProperties = new LinkedHashMap<>();

  /**
   * Extra environment variables for the spawned Head (lane F stage A item A12 closure). A system
   * property would do for most settings, but not for
   * {@code JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND}: its value is a quoted argv
   * ({@code "…\java.exe" "@…\args.txt"}), and putting embedded double quotes through a Windows
   * {@code -Dkey=value} command-line argument is exactly the quoting hazard the argfile exists to
   * avoid. The environment block has no such rule, so it is the honest channel.
   */
  private final Map<String, String> extraEnv = new LinkedHashMap<>();

  private Path dataDir;
  private Path runtimeDir;
  private Path backendLog;
  private Process process;
  private int port = -1;
  private boolean logsPreserved;

  /**
   * Adds a {@code -D} system property to the spawned Head. Must be called before {@link #start()}.
   * Returns {@code this} so a test can configure and start in one expression.
   */
  public IsolatedBackendFixture withSystemProperty(String key, String value) {
    extraSystemProperties.put(key, value);
    return this;
  }

  /**
   * Adds an environment variable to the spawned Head. Must be called before {@link #start()}.
   * Applied LAST in {@link #buildEnv}, so a test can deliberately override a fixture default; the
   * test owns the consequences of doing so.
   */
  public IsolatedBackendFixture withEnv(String key, String value) {
    extraEnv.put(key, value);
    return this;
  }

  /** Spawns the backend and blocks until {@code /api/health} returns 200. */
  public void start() throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    dataDir = Files.createTempDirectory("isolated-backend-");
    runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    backendLog = runtimeDir.resolve("backend.log");

    Path repoRoot = resolveRepoRoot();
    Path argfile = writeArgfile();
    Map<String, String> env = buildEnv(repoRoot);

    List<String> cmd = new ArrayList<>();
    cmd.add(resolveJavaExecutable());
    cmd.add("@" + argfile.toAbsolutePath());
    // Hand the child JVM the same repo and data context the test JVM resolved, so the
    // configuration loader and SSOT discovery don't have to walk up from user.dir.
    cmd.add("-Djustsearch.repo.root=" + repoRoot.toAbsolutePath());
    cmd.add("-Djustsearch.data.dir=" + dataDir.toAbsolutePath());
    extraSystemProperties.forEach((k, v) -> cmd.add("-D" + k + "=" + v));
    cmd.add("io.justsearch.ui.HeadlessApp");

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.environment().clear();
    pb.environment().putAll(env);
    pb.directory(repoRoot.toFile());
    pb.redirectErrorStream(true);
    pb.redirectOutput(backendLog.toFile());

    System.err.println(
        "[IsolatedBackendFixture] phase=spawn dataDir=" + dataDir + " log=" + backendLog);
    process = pb.start();

    try {
      int observedPort = awaitPortFile();
      System.err.println(
          "[IsolatedBackendFixture] phase=port-file port=" + observedPort
              + " elapsedMs=" + ((System.nanoTime() - t0) / 1_000_000));
      awaitHealthOk(observedPort);
      this.port = observedPort;
      System.err.println(
          "[IsolatedBackendFixture] phase=health-ok port=" + observedPort
              + " elapsedMs=" + ((System.nanoTime() - t0) / 1_000_000));
      // /api/health=200 only proves Javalin bound. Lite mode reports DEGRADED while the
      // Worker subprocess is still connecting; ingest accepts the request but the index
      // never receives the doc until worker.state=READY. Block on that explicitly so
      // tests don't have to.
      awaitWorkerReady(observedPort);
      System.err.println(
          "[IsolatedBackendFixture] phase=ready port=" + observedPort
              + " elapsedMs=" + ((System.nanoTime() - t0) / 1_000_000));
    } catch (Exception startupFailure) {
      tailLogToStderr();
      preserveLogOnFailure();
      stop();
      throw new IllegalStateException(
          "Backend failed to become ready: " + startupFailure.getMessage(), startupFailure);
    }
  }

  /**
   * Returns the ephemeral port the backend bound to. Only valid after {@link #start()}
   * returns successfully.
   */
  public int port() {
    if (port < 0) {
      throw new IllegalStateException("port() called before successful start()");
    }
    return port;
  }

  /**
   * Returns the tempdir used as {@code JUSTSEARCH_DATA_DIR}. Tests can place corpora under
   * here so the same tempdir cleanup nukes them.
   */
  public Path dataDir() {
    return dataDir;
  }

  /**
   * The spawned Head's PID. Lane F stage A item A12 closure needs it to enumerate the Head's
   * descendants: the extraction sandbox child is spawned by this process, and its parent-PID gate
   * is keyed on this number.
   */
  public long pid() {
    if (process == null) {
      throw new IllegalStateException("pid() called before start()");
    }
    return process.pid();
  }

  /**
   * Force-kills the backend WITHOUT deleting the tempdir, and blocks until it is gone.
   *
   * <p>Separate from {@link #stop()} because a test that asserts on what happens <em>after</em> the
   * Head dies (orphaned children, files left on disk) has to be able to kill it and then keep
   * looking. Returns true if the process terminated within the grace period.
   */
  public boolean kill() {
    if (process == null) {
      return true;
    }
    process.destroyForcibly();
    try {
      return process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Force-kills the backend and removes the tempdir. Safe to call even if start() failed. */
  public void stop() {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
      try {
        if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
          System.err.println(
              "[IsolatedBackendFixture] WARNING: process did not exit within "
                  + STOP_GRACE_MS + "ms after destroyForcibly()");
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (!logsPreserved && Boolean.getBoolean("isolatedBackend.preserveLogs")) {
      preserveLogOnFailure();
    }
    deleteDataDirWithRetry();
  }

  // --------------------------------------------------------------------------------
  // Spawn helpers
  // --------------------------------------------------------------------------------

  private Path writeArgfile() throws IOException {
    String classpath = System.getProperty("java.class.path", "");
    if (classpath.isBlank()) {
      throw new IllegalStateException("java.class.path is empty — cannot spawn child JVM");
    }
    // Java's @argfile syntax: tokens are whitespace-separated. Wrap the classpath in
    // double-quotes so embedded spaces (e.g. "Program Files") are preserved as a single
    // token. Backslashes are literal inside quoted argfile strings — no escaping needed.
    String quoted = "\"" + classpath.replace("\"", "\\\"") + "\"";
    String body = "-classpath " + quoted + System.lineSeparator();
    Path argfile = runtimeDir.resolve("argfile");
    Files.writeString(argfile, body, StandardCharsets.UTF_8);
    return argfile;
  }

  private Map<String, String> buildEnv(Path repoRoot) {
    Map<String, String> env = new LinkedHashMap<>(System.getenv());
    env.put("JUSTSEARCH_LITE_MODE", "true");
    env.put("JUSTSEARCH_AI_DISABLED", "true");
    env.put("JUSTSEARCH_DATA_DIR", dataDir.toAbsolutePath().toString());
    env.put("JUSTSEARCH_API_PORT", "0");
    env.put("JUSTSEARCH_REPO_ROOT", repoRoot.toAbsolutePath().toString());
    // Lane F stage A item A13 removed the JUSTSEARCH_WORKER_LIB_DIR forward that used to sit here.
    // It existed because the spawned HeadlessApp went on to spawn a Worker from an installDist
    // tree, and this test JVM's working dir is the module rather than the repo root, so
    // KnowledgeServerConfig.resolveWorkerLibDir could not find it by relative walk. There is one
    // process now: the child JVM launched below runs the index half off the classpath in
    // writeArgfile(), and both the resolver and the distribution are deleted.
    env.putAll(extraEnv);
    return env;
  }

  private static Path resolveRepoRoot() {
    Path current = Path.of(System.getProperty("user.dir", "."));
    while (current != null) {
      if (Files.isDirectory(current.resolve("SSOT").resolve("catalogs"))) {
        return current.toAbsolutePath();
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Repo root with SSOT/catalogs not found by walking up from user.dir="
            + System.getProperty("user.dir"));
  }

  private static String resolveJavaExecutable() {
    String javaHome = System.getProperty("java.home");
    if (javaHome == null || javaHome.isBlank()) {
      return isWindows() ? "java.exe" : "java";
    }
    Path bin = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
    return bin.toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  // --------------------------------------------------------------------------------
  // Await helpers
  // --------------------------------------------------------------------------------

  private int awaitPortFile() throws IOException, InterruptedException {
    // Tempdoc 501 Phase 18: read from the canonical manifest.json instead of the
    // deprecated api-port.txt. The manifest's head.apiPort carries the same value the
    // legacy mirror used to expose; reading from the canonical source removes the last
    // integration-test dependency on the deprecated file.
    Path manifestPath = runtimeDir.resolve("manifest.json");
    long deadline = System.currentTimeMillis() + PORT_FILE_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(
            "Backend process exited before writing manifest (exit code "
                + process.exitValue() + ")");
      }
      if (Files.exists(manifestPath)) {
        try {
          String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
          var node = new tools.jackson.databind.ObjectMapper().readTree(json);
          var headNode = node.get("head");
          if (headNode != null && headNode.get("apiPort") != null) {
            int port = headNode.get("apiPort").asInt();
            if (port > 0) {
              return port;
            }
          }
        } catch (Exception parseErr) {
          // partial write — keep polling
        }
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    throw new IllegalStateException(
        "Manifest " + manifestPath + " did not appear within " + PORT_FILE_TIMEOUT_MS + "ms");
  }

  private void awaitWorkerReady(int observedPort) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WORKER_READY_TIMEOUT_MS;
    URI uri = URI.create("http://localhost:" + observedPort + "/api/health");
    HttpRequest req =
        HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build();
    String lastBody = "<no response>";
    while (System.currentTimeMillis() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(
            "Backend process exited while waiting for worker.state=READY (exit code "
                + process.exitValue() + ")");
      }
      try {
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        lastBody = resp.body();
        // The response shape (LifecycleSnapshotV1) serialises components in declaration
        // order — head, worker, inference — and Jackson omits null fields, so the worker
        // component always begins with "worker":{"state":"<STATE>". We avoid pulling
        // Jackson into the fixture for a single readiness probe. Tempdoc 548 (§4.1) collapsed
        // LifecycleState onto the proto enum, so the wire value is now the prefixed
        // "LIFECYCLE_STATE_READY"; accept both the prefixed and the legacy short form so the
        // probe is robust across that serialization change.
        if (resp.statusCode() == 200
            && (lastBody.contains("\"worker\":{\"state\":\"LIFECYCLE_STATE_READY\"")
                || lastBody.contains("\"worker\":{\"state\":\"READY\""))) {
          return;
        }
        failFastOnTerminalWorkerReason(lastBody, "worker.state=READY");
      } catch (IOException ioe) {
        // keep polling
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    throw new IllegalStateException(
        "components.worker.state did not reach READY within " + WORKER_READY_TIMEOUT_MS
            + "ms. Last /api/health body: " + lastBody);
  }

  private void awaitHealthOk(int observedPort) throws InterruptedException {
    long deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS;
    URI uri = URI.create("http://localhost:" + observedPort + "/api/health");
    HttpRequest req =
        HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build();
    Throwable lastError = null;
    int lastStatus = -1;
    String lastBody = "<no response>";
    while (System.currentTimeMillis() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(
            "Backend process exited before /api/health responded (exit code "
                + process.exitValue() + ")");
      }
      try {
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
          return;
        }
        // Lite mode reports DEGRADED with status 503; that's fine for tests that only need
        // diagnostics + ingestion endpoints. We accept any 2xx as ready, but per the spike
        // /api/health returns 200 in lite mode (DEGRADED is reported in the body).
        // The non-200 body carries the LifecycleSnapshotV1 that says WHY (lifecycle.reason_code
        // and components.worker.reason_code), so keep the most recent one for the timeout
        // message — without it the failure reads as a bare timeout with no cause.
        lastStatus = resp.statusCode();
        lastBody = resp.body();
        failFastOnTerminalWorkerReason(lastBody, "/api/health 200");
      } catch (IOException ioe) {
        lastError = ioe;
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    String hint = lastError == null ? "" : " (last error: " + lastError + ")";
    throw new IllegalStateException(
        "/api/health did not return 200 within " + HEALTH_TIMEOUT_MS + "ms" + hint
            + ". Last response: status=" + lastStatus + " body=" + truncate(lastBody));
  }

  /**
   * Tempdoc 825 (charter item 3): stop waiting the moment the Head says it has stopped trying — on
   * EITHER terminal path (review F2(b)): this tempdoc's boot-recovery give-up, and supervision's own
   * give-up, which boot recovery deliberately does not supersede. Before the terminal code existed
   * there was nothing safe to key on — {@code worker.spawn.failed} is emitted mid-recovery too — so a
   * bricked boot burned the whole {@value #HEALTH_TIMEOUT_MS}ms budget and reported a bare timeout.
   * This turns that into an immediate, causally-named failure.
   */
  static void failFastOnTerminalWorkerReason(String body, String waitingFor) {
    if (body == null) {
      return;
    }
    for (String terminal : TERMINAL_WORKER_REASONS) {
      if (body.contains(terminal)) {
        throw new IllegalStateException(
            "Worker recovery is terminal ("
                + terminal
                + ") — the Head has stopped retrying, so waiting for "
                + waitingFor
                + " cannot succeed. Last /api/health body: "
                + truncate(body));
      }
    }
  }

  /** Caps a response body so a timeout message stays readable in the JUnit XML. */
  private static String truncate(String body) {
    if (body == null) {
      return "<null>";
    }
    return body.length() <= HEALTH_BODY_EXCERPT_CHARS
        ? body
        : body.substring(0, HEALTH_BODY_EXCERPT_CHARS)
            + "...[truncated, " + body.length() + " chars total]";
  }

  // --------------------------------------------------------------------------------
  // Cleanup helpers
  // --------------------------------------------------------------------------------

  private void deleteDataDirWithRetry() {
    if (dataDir == null || !Files.exists(dataDir)) {
      return;
    }
    for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
      try {
        deleteRecursively(dataDir);
        return;
      } catch (IOException io) {
        if (attempt == CLEANUP_ATTEMPTS) {
          System.err.println(
              "[IsolatedBackendFixture] WARNING: failed to delete tempdir " + dataDir
                  + " after " + CLEANUP_ATTEMPTS + " attempts: " + io.getMessage());
          return;
        }
        try {
          Thread.sleep(CLEANUP_BACKOFF_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (RuntimeException re) {
      if (re.getCause() instanceof IOException io) {
        throw io;
      }
      throw re;
    }
  }

  /**
   * Copies boot or test-body failure evidence into {@link #resolveFailureLogDir()}. Call before
   * {@link #stop()} when a live assertion fails, so teardown cannot delete its diagnostics.
   *
   * <p>{@code backend.log} is only the child JVM's redirected stdout/stderr — the Engine's actual
   * application log is {@code <dataDir>/logs/engine.log}
   * ({@code modules/ui/src/main/resources/logback.xml}), rolling to
   * {@code engine.%d{yyyy-MM-dd}.%i.log.gz} at 10&nbsp;MB, so the whole {@code logs/} directory is
   * swept rather than a fixed filename list.
   *
   * <p>There is one application log now, not two. This javadoc named {@code headless-backend.log}
   * for the Head and {@code worker.log} for the Worker; lane F items A13 and A16 deleted the second
   * process, its {@code logback.xml} and the file itself, then renamed the survivor. Sweeping the
   * directory rather than a filename list is what kept this method WORKING through all of that —
   * only its explanation was wrong, which is the failure mode a directory sweep is prone to: the
   * code keeps collecting the right evidence while the comment describes a layout that no longer
   * exists. The previous {@code app.log} copy could never fire: that file is written by the
   * app-launcher process, which this fixture never spawns.
   */
  public void preserveLogOnFailure() {
    logsPreserved = true;
    Path dest = resolveFailureLogDir();
    copyIfPresent(backendLog, dest.resolve("backend.log"));
    if (dataDir != null) {
      Path logsDir = dataDir.resolve("logs");
      if (Files.isDirectory(logsDir)) {
        try (Stream<Path> logs = Files.list(logsDir)) {
          logs.filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".log") || name.endsWith(".log.gz");
                  })
              .forEach(p -> copyIfPresent(p, dest.resolve(p.getFileName().toString())));
        } catch (IOException io) {
          System.err.println("[IsolatedBackendFixture] failed to list logs dir: " + io);
        }
      }
      Path crashesDir = dataDir.resolve("crashes");
      if (Files.exists(crashesDir)) {
        try (Stream<Path> walk = Files.walk(crashesDir)) {
          walk.filter(Files::isRegularFile)
              .forEach(p -> copyIfPresent(p, dest.resolve("crash-" + p.getFileName())));
        } catch (IOException io) {
          System.err.println(
              "[IsolatedBackendFixture] failed to walk crashes dir: " + io);
        }
      }
    }
  }

  /**
   * Resolves the directory failure logs are copied into.
   *
   * <p>Prefers the workspace-relative directory the Gradle task hands down via
   * {@code isolatedBackend.failureLogDir}, because that is the only location a hosted CI
   * runner can upload as a build artifact — logs written under {@code java.io.tmpdir} die
   * with the runner, which is why the 2026-08-13 boot flakes (runs 31730197618 and
   * 31732439890) could not be diagnosed remotely. Falls back to the tempdir when the
   * property is absent, so the fixture still behaves for ad-hoc local runs.
   *
   * <p>Each failure gets its own timestamped, class-labelled subdirectory: a stalled
   * {@code @BeforeAll} is retried twice more in CI, and a flat destination would leave only
   * the last attempt's logs behind — exactly the evidence loss that made the 3/3 stall in
   * run 31730197618 unreadable.
   */
  private Path resolveFailureLogDir() {
    String configured = System.getProperty("isolatedBackend.failureLogDir");
    Path base =
        configured == null || configured.isBlank()
            ? Path.of(System.getProperty("java.io.tmpdir"))
            : Path.of(configured);
    String stamp =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now());
    Path dir = base.resolve(stamp + "-" + ownerLabel);
    try {
      return Files.createDirectories(dir);
    } catch (IOException io) {
      System.err.println("[IsolatedBackendFixture] failed to create " + dir + ": " + io);
      return base;
    }
  }

  /**
   * Best-effort simple name of the class that constructed this fixture, used to label the
   * preserved-log directory so an artifact bundle says which E2E class stalled without
   * having to correlate timestamps against the JUnit XML.
   */
  private static String resolveOwnerLabel() {
    try {
      return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
          .walk(
              frames ->
                  frames
                      .map(StackWalker.StackFrame::getDeclaringClass)
                      .filter(c -> c != IsolatedBackendFixture.class)
                      .findFirst()
                      .map(Class::getSimpleName)
                      .filter(name -> !name.isBlank())
                      .orElse("unknown"));
    } catch (RuntimeException re) {
      return "unknown";
    }
  }

  private void copyIfPresent(Path source, Path target) {
    if (source == null || !Files.exists(source)) {
      return;
    }
    try {
      Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      System.err.println("[IsolatedBackendFixture] log preserved at " + target);
    } catch (IOException io) {
      System.err.println("[IsolatedBackendFixture] failed to copy " + source + ": " + io);
    }
  }

  private void tailLogToStderr() {
    if (backendLog == null || !Files.exists(backendLog)) {
      System.err.println("[IsolatedBackendFixture] no backend log to tail");
      return;
    }
    try {
      List<String> lines = Files.readAllLines(backendLog, StandardCharsets.UTF_8);
      int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
      System.err.println(
          "[IsolatedBackendFixture] last " + (lines.size() - from)
              + " lines of " + backendLog + ":");
      for (int i = from; i < lines.size(); i++) {
        System.err.println("  | " + lines.get(i));
      }
    } catch (IOException io) {
      System.err.println("[IsolatedBackendFixture] failed to read log " + backendLog + ": " + io);
    }
  }
}
