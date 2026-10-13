/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in llama-server for tests that need a managed launch to become healthy without a model.
 * Run through the launcher script {@link #install} writes, it binds the {@code --port} it is given
 * (the Engine's fresh loopback port), answers {@code /health} and a llama-shaped {@code /props}
 * naming the {@code -m} model and the {@code -c} window, and exits when the process that launched it
 * ends, so stopping the launched process stops the server. It records each launch's argv, and can
 * fail the way llama-server does (Spec A §9.1 F1, {@link #install(Path, String...)}).
 *
 * <p>It also answers a one-token {@code /completion} (Spec A §7.4's resume probe). Behaviours for
 * step 10: {@code --completion-fails} answers it 500; {@code --completion-cuda-failure} prints a
 * device-lost CUDA error and answers 500; {@code --loading-until-signal} answers {@code /health}
 * 503 until {@link #signalReady}. {@code --spawn-lingerer} starts one more process once serving,
 * which outlives the server (as a crash reporter or a wrapper's child can) until {@link
 * #releaseLingerers}, or 90 s.
 */
public final class HealthyFakeLlamaServer {
  private static final String HOME_PROPERTY = "fake.llama.home";
  private static final String PIDS = "pids.txt";
  private static final String IDENTITIES = "identities.txt";
  private static final String LAUNCHES = "launches.txt";
  private static final String BEHAVIOUR = "behaviour.txt";
  private static final String OOM_SIGNAL = "oom-signal";
  private static final String READY_SIGNAL = "ready-signal";
  private static final String LINGERERS = "lingerers.txt";
  private static final String LINGERER_EXIT = "lingerer-exit";

  /**
   * With {@code --loading-until-signal} installed, ends the model load: {@code /health} answers
   * 200 from now on instead of 503 "Loading model".
   */
  static void signalReady(Path dir) throws IOException {
    Files.writeString(dir.resolve(READY_SIGNAL), "ready", StandardCharsets.US_ASCII);
  }

  /**
   * With {@code --oom-on-signal} installed, makes the running server fail as a CUDA allocation
   * failure while serving does: it prints {@code CUDA error: out of memory} and exits 1.
   */
  static void signalOutOfMemory(Path dir) throws IOException {
    Files.writeString(dir.resolve(OOM_SIGNAL), "oom", StandardCharsets.US_ASCII);
  }

  private HealthyFakeLlamaServer() {}

  /**
   * Ends every server launched from {@code dir} and waits for it. On Windows the launched process
   * is the launcher script's shell; once that is stopped the server is no longer this JVM's
   * descendant. Each server records its PID and creation time at startup; cleanup holds a native
   * handle and stops it only when that handle's creation time matches. {@code dir} lies in the
   * test's temporary directory; this returns once nothing the launches held there is still held,
   * so that directory can be deleted.
   */
  static void endAll(Path dir) throws IOException {
    Path identities = dir.resolve(IDENTITIES);
    if (Files.exists(identities)) {
      for (String line : Files.readAllLines(identities, StandardCharsets.US_ASCII)) {
        if (line.isBlank()) continue;
        stopRecorded(line, false);
      }
    }
    awaitReleased(dir.getParent());
  }

  /**
   * Waits until no file under {@code root} (the test's temporary directory, which holds {@code
   * dir} and the logs the launches wrote to) is still held. On Windows an ended launch's handles
   * on the output it was redirected to can outlive its reported exit for some milliseconds, and a
   * held file cannot be deleted, so the test directory could not be removed. Fails naming the file
   * if it is still held after five seconds.
   */
  private static void awaitReleased(Path root) throws IOException {
    if (root == null || !Files.isDirectory(root)) return;
    List<Path> files;
    try (var walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).toList();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    for (Path file : files) {
      Path probe = file.resolveSibling(file.getFileName() + ".release-probe");
      while (true) {
        try {
          Files.move(file, probe);
          Files.move(probe, file);
          break;
        } catch (java.nio.file.NoSuchFileException gone) {
          break;
        } catch (IOException held) {
          if (System.nanoTime() > deadline) {
            throw new IOException("still held after every fake llama-server ended: " + file, held);
          }
          try {
            Thread.sleep(5);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + file + " to be released", held);
          }
        }
      }
    }
  }

  /**
   * The most recently launched server from {@code dir}, ended as a crash would end it. Only a
   * creation-matched producer identity is ended, so a recycled pid is never touched: the birth
   * written at fixture start is checked on a held native handle. Fails when that server no longer
   * runs. Returns its pid.
   */
  static long crashLatest(Path dir) throws Exception {
    Path pids = dir.resolve(PIDS);
    List<String> lines = Files.exists(pids)
        ? Files.readAllLines(pids, StandardCharsets.US_ASCII).stream()
            .filter(line -> !line.isBlank()).toList()
        : List.of();
    if (lines.isEmpty()) throw new AssertionError("no fake llama-server has started in " + dir);
    long pid = Long.parseLong(lines.getLast().trim());
    String identity = Files.readAllLines(dir.resolve(IDENTITIES), StandardCharsets.US_ASCII).stream()
        .filter(line -> line.startsWith(pid + " ")).reduce((first, last) -> last).orElseThrow();
    stopRecorded(identity, true);
    return pid;
  }

  private static ProcessHandle ownedHandle(String identity) {
    String[] fields = identity.split(" ", 2);
    var handle = ProcessHandle.of(Long.parseLong(fields[0])).orElse(null);
    return handle != null && sameBirth(handle, identity) ? handle : null;
  }

  private static boolean sameBirth(ProcessHandle handle, String identity) {
    String[] fields = identity.split(" ", 2);
    return handle.pid() == Long.parseLong(fields[0])
        && handle.info().startInstant().map(birth -> birth.equals(java.time.Instant.parse(fields[1])))
            .orElse(false);
  }

  /**
   * The lingering processes launches from {@code dir} started, as each recorded its own PID and
   * creation instant; a handle only for an identity that still matches.
   */
  static List<ProcessHandle> lingerers(Path dir) throws IOException {
    Path file = dir.resolve(LINGERERS);
    if (!Files.exists(file)) return List.of();
    List<ProcessHandle> found = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;
      ProcessHandle owned = ownedHandle(line.trim());
      if (owned != null) found.add(owned);
    }
    return found;
  }

  /** Asks every lingerer from {@code dir} to exit and waits for each (test cleanup; no kill). */
  static void releaseLingerers(Path dir) throws Exception {
    if (!Files.isDirectory(dir)) return;
    Files.writeString(dir.resolve(LINGERER_EXIT), "exit", StandardCharsets.US_ASCII);
    for (ProcessHandle lingerer : lingerers(dir)) lingerer.onExit().get(10, TimeUnit.SECONDS);
  }

  /** The lingering process: records its identity, then waits for its exit signal. */
  public static final class Lingerer {
    private Lingerer() {}

    public static void main(String[] args) throws Exception {
      Path home = Path.of(args[0]);
      var self = ProcessHandle.current();
      Files.writeString(home.resolve(LINGERERS), self.pid() + " "
          + self.info().startInstant().orElseThrow() + "\n", StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (!Files.exists(home.resolve(LINGERER_EXIT)) && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
    }
  }

  /** Every launch's argv, in order, as the fake recorded them. */
  static List<List<String>> launches(Path dir) throws IOException {
    Path launches = dir.resolve(LAUNCHES);
    if (!Files.exists(launches)) return List.of();
    List<List<String>> all = new ArrayList<>();
    for (String line : Files.readAllLines(launches, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) all.add(List.of(line.split("\t")));
    }
    return all;
  }

  /**
   * Stops the fixture process an {@code identity} line ({@code pid birth}) recorded at startup,
   * only if that birth matches the birth read on a held native handle, so a recycled pid is never
   * touched. With {@code mustBeRunning}, a gone or recycled identity fails instead of being skipped.
   */
  private static void stopRecorded(String identity, boolean mustBeRunning) {
    String[] fields = identity.trim().split(" ", 2);
    long pid = Long.parseLong(fields[0]);
    String birth = fields[1];
    try {
      var process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
      if (process.isEmpty()) {
        if (mustBeRunning) throw new AssertionError("the recorded fake llama-server is no longer running");
        return;
      }
      var held = io.justsearch.app.api.runtime.child.ProcessTerminator.forCurrentOs().open(process.get());
      try {
        String actual = held.creationFileTime().orElseGet(() -> "instant:"
            + process.get().info().startInstant().orElseThrow());
        if (!actual.equals(birth)) {
          if (mustBeRunning) throw new AssertionError("the recorded fake llama-server is no longer running");
          return;
        }
        held.terminate(1);
        held.awaitExit(java.time.Duration.ofSeconds(10));
      } finally { held.close(); }
    } catch (AssertionError failure) {
      throw failure;
    } catch (Exception failure) {
      throw new AssertionError("could not stop recorded fixture identity " + pid, failure);
    }
  }

  private static void recordIdentity(Path dir) throws IOException {
    long pid = ProcessHandle.current().pid();
    String birth = io.justsearch.app.api.runtime.child.ProcessTerminator.forCurrentOs()
        .creationFileTime(pid).orElseGet(() -> "instant:"
            + ProcessHandle.current().info().startInstant().orElseThrow());
    Files.writeString(dir.resolve(IDENTITIES), pid + " " + birth + "\n",
        StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
  }

  public static void main(String[] args) throws Exception {
    String home = System.getProperty(HOME_PROPERTY);
    List<String> behaviour = home == null ? List.of() : readBehaviour(Path.of(home));
    if (home != null) {
      recordIdentity(Path.of(home));
      Files.writeString(Path.of(home, LAUNCHES), String.join("\t", args) + "\n",
          StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
    failLikeLlamaServerWhenAsked(behaviour, value(args, "-c", "4096"));
    int port = Integer.parseInt(value(args, "--port", "-1"));
    String model = value(args, "-m", "model.gguf").replace('\\', '/');
    String window = value(args, "-c", "4096");
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    Path readySignal = home == null ? null : Path.of(home, READY_SIGNAL);
    server.createContext("/health", exchange -> {
      boolean loading = behaviour.contains("--loading-until-signal")
          && (readySignal == null || !Files.exists(readySignal));
      byte[] body = (loading
          ? "{\"error\":{\"code\":503,\"message\":\"Loading model\"}}"
          : "{\"status\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(loading ? 503 : 200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/completion", exchange -> {
      exchange.getRequestBody().readAllBytes();
      int status = 200;
      String answer = "{\"content\":\"ok\",\"stop\":true}";
      if (behaviour.contains("--completion-cuda-failure")) {
        // A kernel launch that fails after a driver reset, as b8571 prints it; the process lives.
        System.out.println("CUDA error: unspecified launch failure");
        System.out.println("  current device: 0, in function ggml_cuda_op_mul_mat at"
            + " D:\\a\\llama.cpp\\llama.cpp\\ggml\\src\\ggml-cuda\\ggml-cuda.cu:1504");
        System.out.flush();
        status = 500;
        answer = "{\"error\":{\"code\":500,\"message\":\"compute failed\"}}";
      } else if (behaviour.contains("--completion-fails")) {
        status = 500;
        answer = "{\"error\":{\"code\":500,\"message\":\"compute failed\"}}";
      }
      byte[] body = answer.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/props", exchange -> {
      byte[] body = ("{\"model_path\":\"" + model + "\",\"n_ctx\":" + window + "}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    if (home != null && behaviour.contains("--spawn-lingerer")) {
      new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
          System.getProperty("java.class.path"), Lingerer.class.getName(), home)
          .directory(new java.io.File(System.getProperty("java.io.tmpdir")))
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    if (home != null) {
      Files.writeString(Path.of(home, PIDS), ProcessHandle.current().pid() + "\n",
          StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
    ProcessHandle launcher = ProcessHandle.current().parent().orElse(null);
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    Path oomSignal = home == null ? null : Path.of(home, OOM_SIGNAL);
    while (launcher != null && launcher.isAlive() && System.nanoTime() < deadline) {
      if (behaviour.contains("--oom-on-signal") && Files.deleteIfExists(oomSignal)) {
        // A KV or compute allocation that fails while serving, as b8571 reports it.
        System.out.println("CUDA error: out of memory");
        System.out.println(
            "D:\\a\\llama.cpp\\llama.cpp\\ggml\\src\\ggml-cuda\\ggml-cuda.cu:98: CUDA error");
        System.out.flush();
        server.stop(0);
        System.exit(1);
      }
      Thread.sleep(100);
    }
    server.stop(0);
    System.exit(0);
  }

  private static String value(String[] args, String flag, String fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (flag.equals(args[i])) return args[i + 1];
    }
    return fallback;
  }

  private static List<String> readBehaviour(Path home) throws IOException {
    Path file = home.resolve(BEHAVIOUR);
    return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
  }

  /**
   * Spec A §9.1 F1. {@code --max-ctx N}: a window above N fails the way b8571 does when the KV
   * cache does not fit ({@code cudaMalloc failed: out of memory}, exit 1). {@code --log-line L}
   * (repeatable) and {@code --exit-code C}: print the lines and exit with C (default 1). Output goes
   * to stdout, which the Engine redirects into llama-server.log. The short pause keeps the process
   * alive while the Engine registers it, as a real start does.
   */
  // This disposable child JVM must reproduce the native server exit code.
  @SuppressWarnings("PMD.DoNotTerminateVM")
  private static void failLikeLlamaServerWhenAsked(List<String> behaviour, String window)
      throws InterruptedException {
    String[] options = behaviour.toArray(String[]::new);
    List<String> lines = new ArrayList<>();
    for (int i = 0; i + 1 < options.length; i++) {
      if ("--log-line".equals(options[i])) lines.add(options[i + 1]);
    }
    int exitCode = Integer.parseInt(value(options, "--exit-code", "1"));
    String maxCtx = value(options, "--max-ctx", null);
    if (maxCtx != null && Integer.parseInt(window) > Integer.parseInt(maxCtx)) {
      lines.add("llama_kv_cache:      CUDA0 KV buffer size = 16604.75 MiB");
      lines.add("ggml_backend_cuda_buffer_type_alloc_buffer: allocating 16604.75 MiB on device 0:"
          + " cudaMalloc failed: out of memory");
      exitCode = 1;
    } else if (lines.isEmpty()) {
      return;
    }
    Thread.sleep(500);
    lines.forEach(System.out::println);
    System.out.flush();
    System.exit(exitCode);
  }

  /**
   * Writes {@code llama-server.cmd} (Windows) or {@code llama-server} into {@code dir}: a launcher
   * that runs this class on the test classpath with the arguments it receives. Returns its path,
   * to be configured as the llama-server executable.
   */
  static Path install(Path dir) throws IOException {
    return install(dir, new String[0]);
  }

  /**
   * As {@link #install(Path)}, with the F1 failure options (Spec A §9.1) every launch from
   * {@code dir} follows: {@code --max-ctx N}, {@code --log-line L} (repeatable), {@code --exit-code
   * C}. They are kept out of llama-server's own argv.
   */
  static Path install(Path dir, String... behaviour) throws IOException {
    Files.createDirectories(dir);
    Files.write(dir.resolve(BEHAVIOUR), List.of(behaviour), StandardCharsets.UTF_8);
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    Path argFile = dir.resolve("classpath.args");
    String classPath = System.getProperty("java.class.path").replace('\\', '/');
    String home = dir.toAbsolutePath().toString().replace('\\', '/');
    Files.writeString(
        argFile,
        "-D" + HOME_PROPERTY + "=\"" + home + "\"\n-cp \"" + classPath + "\"\n",
        StandardCharsets.UTF_8);
    Path script = dir.resolve(windows ? "llama-server.cmd" : "llama-server");
    String main = HealthyFakeLlamaServer.class.getName();
    String body =
        windows
            ? "@echo off\r\n\"" + java + "\" @\"" + argFile + "\" " + main + " %*\r\n"
            : "#!/bin/sh\nexec \"" + java + "\" @\"" + argFile + "\" " + main + " \"$@\"\n";
    Files.writeString(script, body, StandardCharsets.UTF_8);
    if (!script.toFile().setExecutable(true)) {
      throw new IOException("could not make " + script + " executable");
    }
    return script;
  }
}
