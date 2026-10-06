/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the activation self-test's candidate as a {@code LLAMA_SELF_TEST} child through {@link
 * ChildControl} (Spec B step 6). The chat controller calls it with the main server stopped. It
 * returns only after the candidate exited, stopped with {@code SELF_TEST}, and after the GPU
 * barrier, so the candidate's adapter memory is released before the main server loads again
 * (S19).
 */
public final class ChildSelfTestLauncher implements SelfTestLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(ChildSelfTestLauncher.class);

  /** Windows exit code for missing DLL dependencies at load time (STATUS_DLL_NOT_FOUND). */
  static final int WINDOWS_STATUS_DLL_NOT_FOUND = -1073741515;

  /** The ledger token of the stop: the chat controller runs the self-test. */
  static final String REQUESTED_BY = "chat-controller";

  private static final long HEALTH_INTERVAL_MS = 500;
  /** A stop's two terminate waits (5 s each) and a margin for the exit record. */
  private static final Duration STOP_WAIT = Duration.ofSeconds(12);
  /** The GPU barrier's bound: one release poll's maximum (Spec B §2.6). */
  private static final Duration GPU_RELEASE_WAIT = Duration.ofSeconds(5);

  private final ChildControl control;

  public ChildSelfTestLauncher(ChildControl control) {
    this.control = Objects.requireNonNull(control, "control");
  }

  @Override
  public SelfTestRun run(SelfTestSpec spec) {
    Objects.requireNonNull(spec, "spec");
    ChildHandle candidate;
    try {
      candidate = start(spec);
    } catch (IOException | RuntimeException failure) {
      return SelfTestRun.failed("llama-server could not start: " + message(failure));
    }
    try {
      String unhealthy = awaitHealthy(candidate, spec);
      if (unhealthy != null) return SelfTestRun.failed(unhealthy);
      spec.probe().exercise(spec.port());
      return SelfTestRun.passed();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return SelfTestRun.failed("Self-test interrupted");
    } catch (Exception failure) {
      return SelfTestRun.failed(message(failure));
    } finally {
      stopAndRelease(candidate);
    }
  }

  private ChildHandle start(SelfTestSpec spec) throws IOException {
    Path log = spec.logFile();
    if (log.getParent() != null) Files.createDirectories(log.getParent());
    Path workingDirectory = spec.workingDirectory() != null
        && Files.isDirectory(spec.workingDirectory()) ? spec.workingDirectory() : null;
    ChildSpec child = new ChildSpec(
        ChildKind.LLAMA_SELF_TEST,
        spec.command(),
        workingDirectory,
        Map.of(),
        new ChildSpec.Stdio.AppendToLog(log),
        flagValue(spec.command(), "-ngl").map(ChildSelfTestLauncher::positive).orElse(false),
        OptionalLong.empty(),
        Optional.empty(),
        ChildSpec.CooperativeStop.NONE,
        "llama-self-test",
        spec.pathPrefix().stream().map(dir -> dir.toAbsolutePath().normalize()).toList());
    LOG.info("Self-test starting llama-server: {}", String.join(" ", spec.command()));
    return control.start(child, new ChildControl.ManifestEntry(
        "http://127.0.0.1:" + spec.port(), flagValue(spec.command(), "-m").orElse(null), null));
  }

  /** Null when healthy, otherwise why not. */
  private String awaitHealthy(ChildHandle candidate, SelfTestSpec spec)
      throws InterruptedException {
    long deadline = System.nanoTime() + spec.healthTimeout().toNanos();
    while (System.nanoTime() < deadline) {
      ExitOutcome exited = candidate.exit().toCompletableFuture().getNow(null);
      if (exited != null) {
        int exitCode = exited.code().isPresent() ? exited.code().getAsInt() : -1;
        if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
          note(candidate, "dll_not_found");
          return "llama-server failed to start (missing DLL dependencies, 0xC0000135). See "
              + spec.logFile().getFileName();
        }
        note(candidate, "startup_failed");
        return "llama-server exited before healthy (exit code " + exitCode + ")";
      }
      if (spec.probe().healthy(spec.port())) return null;
      Thread.sleep(HEALTH_INTERVAL_MS);
    }
    return "Server health check timeout after " + spec.healthTimeout().toMillis() + "ms";
  }

  /**
   * Stops the candidate ({@code SELF_TEST}), waits for its recorded exit, for every process it
   * started and then for its GPU release, also when this thread is interrupted (the controller's
   * shutdown): the next launch must not overlap it.
   */
  private void stopAndRelease(ChildHandle candidate) {
    boolean interrupted = Thread.interrupted();
    try {
      List<ProcessHandle> started = StartedProcesses.of(candidate);
      CompletableFuture<ExitOutcome> stop = control.stop(candidate,
          StopRequest.of(StopReason.SELF_TEST, StopMode.NOW, REQUESTED_BY)).toCompletableFuture();
      long deadline = System.nanoTime() + STOP_WAIT.toNanos();
      while (true) {
        try {
          stop.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          break;
        } catch (InterruptedException interrupt) {
          interrupted = true;
        } catch (ExecutionException | TimeoutException survived) {
          LOG.warn("Self-test llama-server (pid {}) did not exit after a forced stop",
              candidate.pid());
          return;
        }
      }
      if (!StartedProcesses.awaitExit(started, STOP_WAIT)) {
        LOG.warn("A process the self-test llama-server (pid {}) started is still running",
            candidate.pid());
      }
      while (true) {
        try {
          if (!control.awaitGpuRelease(GPU_RELEASE_WAIT)) {
            LOG.debug("Self-test llama-server GPU release still pending after {}",
                GPU_RELEASE_WAIT);
          }
          break;
        } catch (InterruptedException interrupt) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void note(ChildHandle candidate, String token) {
    try {
      control.note(candidate, token);
    } catch (RuntimeException unrecorded) {
      LOG.debug("Could not record the self-test note: {}", message(unrecorded));
    }
  }

  private static Optional<String> flagValue(List<String> command, String flag) {
    int index = command.indexOf(flag);
    return index < 0 || index + 1 >= command.size()
        ? Optional.empty() : Optional.of(command.get(index + 1));
  }

  private static boolean positive(String value) {
    try {
      return Integer.parseInt(value.trim()) > 0;
    } catch (NumberFormatException unreadable) {
      return false;
    }
  }

  private static String message(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }
}
