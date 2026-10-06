/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the self-test candidate as a plain process (moved from {@code
 * RuntimeActivationService}, which launched it beside a running main server). The chat controller
 * calls it with the main server stopped, and it returns only after the candidate exited, so the two
 * never hold the GPU together. Spec B step 6 replaces it with a {@code LLAMA_SELF_TEST} child.
 */
public final class ProcessSelfTestLauncher implements SelfTestLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessSelfTestLauncher.class);

  /** Windows exit code for missing DLL dependencies at load time (STATUS_DLL_NOT_FOUND). */
  static final int WINDOWS_STATUS_DLL_NOT_FOUND = -1073741515;

  private static final long HEALTH_INTERVAL_MS = 500;
  private static final long STOP_GRACE_MS = 5_000;
  private static final long EXIT_WAIT_MS = 10_000;

  @Override
  public SelfTestRun run(SelfTestSpec spec) {
    Process process;
    try {
      process = start(spec);
    } catch (IOException | RuntimeException failure) {
      return SelfTestRun.failed("llama-server could not start: " + message(failure));
    }
    try {
      String unhealthy = awaitHealthy(process, spec);
      if (unhealthy != null) return SelfTestRun.failed(unhealthy);
      spec.probe().exercise(spec.port());
      return SelfTestRun.passed();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return SelfTestRun.failed("Self-test interrupted");
    } catch (Exception failure) {
      return SelfTestRun.failed(message(failure));
    } finally {
      stopAndAwait(process);
    }
  }

  private static Process start(SelfTestSpec spec) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(spec.command());
    if (spec.workingDirectory() != null && Files.isDirectory(spec.workingDirectory())) {
      builder.directory(spec.workingDirectory().toFile());
    }
    if (!spec.pathPrefix().isEmpty()) {
      Map<String, String> env = builder.environment();
      String key = env.containsKey("Path") ? "Path" : (env.containsKey("PATH") ? "PATH" : "Path");
      String existing = env.getOrDefault(key, "");
      String prefix = spec.pathPrefix().stream()
          .map(dir -> dir.toAbsolutePath().normalize().toString())
          .collect(Collectors.joining(java.io.File.pathSeparator));
      env.put(key, existing == null || existing.isBlank()
          ? prefix : prefix + java.io.File.pathSeparator + existing);
    }
    Path log = spec.logFile();
    if (log.getParent() != null) Files.createDirectories(log.getParent());
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
    builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
    LOG.info("Self-test starting llama-server: {}", String.join(" ", spec.command()));
    return builder.start();
  }

  /** Null when healthy, otherwise why not. */
  private static String awaitHealthy(Process process, SelfTestSpec spec)
      throws InterruptedException {
    long deadline = System.nanoTime() + spec.healthTimeout().toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        int exitCode = process.exitValue();
        if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
          return "llama-server failed to start (missing DLL dependencies, 0xC0000135). See "
              + spec.logFile().getFileName();
        }
        return "llama-server exited before healthy (exit code " + exitCode + ")";
      }
      if (spec.probe().healthy(spec.port())) return null;
      Thread.sleep(HEALTH_INTERVAL_MS);
    }
    return "Server health check timeout after " + spec.healthTimeout().toMillis() + "ms";
  }

  /**
   * Stops the candidate and every process it started, and waits for them to exit, also when this
   * thread is interrupted (the controller's shutdown): the next launch must not overlap it.
   */
  private static void stopAndAwait(Process process) {
    List<ProcessHandle> tree = new ArrayList<>();
    tree.add(process.toHandle());
    try {
      process.descendants().forEach(tree::add);
    } catch (RuntimeException ignored) {
      // the root alone is still stopped and awaited
    }
    boolean[] interrupted = {Thread.interrupted()};
    try {
      process.destroy();
      if (!awaitExit(tree, STOP_GRACE_MS, interrupted)) {
        if (isWindows()) {
          try {
            new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS);
          } catch (InterruptedException failure) {
            interrupted[0] = true;
          } catch (IOException failure) {
            LOG.debug("taskkill could not run: {}", message(failure));
          }
        }
        for (ProcessHandle handle : tree) handle.destroyForcibly();
        if (!awaitExit(tree, EXIT_WAIT_MS, interrupted)) {
          LOG.warn("Self-test llama-server (pid {}) did not exit after a forced stop", process.pid());
        }
      }
    } finally {
      if (interrupted[0]) Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for every process in {@code tree} to exit. An interrupt does not end the wait, since
   * returning early would let the next launch overlap this one; it is recorded for the caller.
   */
  private static boolean awaitExit(List<ProcessHandle> tree, long timeoutMs, boolean[] interrupted) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    for (ProcessHandle handle : tree) {
      while (handle.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
          handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupt) {
          interrupted[0] = true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
            ignored) {
          // re-checked by the loop
        }
      }
    }
    return true;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  private static String message(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }
}
