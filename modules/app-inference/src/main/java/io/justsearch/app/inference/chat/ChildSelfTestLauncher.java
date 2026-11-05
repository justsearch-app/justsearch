/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildRegistrationRefusedException;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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

  /** Runs inside {@link #run} (a candidate may be starting, serving or stopping). */
  private final AtomicInteger running = new AtomicInteger();
  /** Candidates whose stop did not confirm them and everything they started gone. */
  private final Set<Unreleased> unreleased = ConcurrentHashMap.newKeySet();

  /** A candidate that outlived its self-test, with the processes it was seen to start. */
  private record Unreleased(ChildHandle candidate, Set<ProcessHandle> started) {
    /** Confirmed gone: exited, nothing it started still runs, and its scope reads empty. */
    boolean released() {
      if (started.stream().anyMatch(io.justsearch.app.api.runtime.child.ProcessTerminator::isRunning)) {
        return false;
      }
      return StartedProcesses.released(candidate);
    }
  }

  public ChildSelfTestLauncher(ChildControl control) {
    this.control = Objects.requireNonNull(control, "control");
  }

  /**
   * Design D4: whether a self-test candidate may still hold the answer model's files: a run is in
   * progress, or a candidate outlived its run (its stop was not confirmed, or something it started
   * still runs) and is not yet seen gone. This outlives the controller's self-test hold.
   */
  public boolean candidatesMayHoldFiles() {
    unreleased.removeIf(Unreleased::released);
    return running.get() > 0 || !unreleased.isEmpty();
  }

  @Override
  public SelfTestRun run(SelfTestSpec spec) {
    Objects.requireNonNull(spec, "spec");
    running.incrementAndGet();
    try {
      return runCandidate(spec);
    } finally {
      running.decrementAndGet();
    }
  }

  private SelfTestRun runCandidate(SelfTestSpec spec) {
    ChildHandle candidate;
    try {
      candidate = start(spec);
    } catch (ChildRegistrationRefusedException refused) {
      // A candidate whose registration was refused is retained with the processes it was seen to
      // start until it and they are confirmed gone: one whose rollback stop did not confirm its
      // exit still runs (repair round 3), and one whose root exited may have left a descendant its
      // rollback could not end, which may still hold the answer model's files.
      ChildHandle rolledBack = refused.rolledBack();
      refused.survivor().ifPresent(survivor -> LOG.error("Self-test llama-server PID {} survived"
          + " registration rollback; retaining its handle", survivor.pid()));
      unreleased.add(new Unreleased(rolledBack, startedSoFar(rolledBack)));
      return SelfTestRun.failed("llama-server could not start: " + message(refused));
    } catch (IOException | RuntimeException failure) {
      return SelfTestRun.failed("llama-server could not start: " + message(failure));
    }
    Set<ProcessHandle> started = new LinkedHashSet<>();
    SelfTestRun outcome;
    String survived = "Self-test cleanup unverified";
    try {
      outcome = exercise(candidate, spec, started);
    } finally {
      try {
        survived = stopAndRelease(candidate, started);
      } finally {
        if (survived != null) unreleased.add(new Unreleased(candidate, Set.copyOf(started)));
      }
    }
    // A candidate that may still run (and hold the GPU) is not a passed self-test (S19).
    if (survived == null) return outcome;
    return SelfTestRun.failed(outcome.exercised() ? survived : outcome.failure() + "; " + survived);
  }

  /** The processes a survivor was seen to start; its scope is read again until it is released. */
  private static Set<ProcessHandle> startedSoFar(ChildHandle survivor) {
    try {
      return Set.copyOf(StartedProcesses.of(survivor));
    } catch (RuntimeException unreadable) {
      return Set.of();
    }
  }

  private SelfTestRun exercise(ChildHandle candidate, SelfTestSpec spec, Set<ProcessHandle> started) {
    try {
      String unhealthy = awaitHealthy(candidate, spec, started);
      if (unhealthy != null) return SelfTestRun.failed(unhealthy);
      // Capture the serving processes before the probe can let its wrapper exit.
      started.addAll(StartedProcesses.of(candidate));
      spec.probe().exercise(spec.port());
      return SelfTestRun.passed();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return SelfTestRun.failed("Self-test interrupted");
    } catch (Exception failure) {
      return SelfTestRun.failed(message(failure));
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
        spec.environment(),
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
  private String awaitHealthy(ChildHandle candidate, SelfTestSpec spec, Set<ProcessHandle> started)
      throws InterruptedException {
    long deadline = System.nanoTime() + spec.healthTimeout().toNanos();
    while (System.nanoTime() < deadline) {
      started.addAll(StartedProcesses.of(candidate));
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
   * shutdown): the next launch must not overlap it. Returns null when the candidate and every
   * process it started exited, otherwise why the stop is incomplete.
   */
  private String stopAndRelease(ChildHandle candidate, Set<ProcessHandle> started) {
    boolean interrupted = Thread.interrupted();
    try {
      String enumerationFailure = null;
      try {
        started.addAll(StartedProcesses.of(candidate));
      } catch (RuntimeException unreadable) {
        enumerationFailure = "Self-test cleanup unverified: " + message(unreadable);
      }
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
          return "Self-test llama-server (pid " + candidate.pid()
              + ") did not exit after a forced stop";
        }
      }
      if (enumerationFailure != null) return enumerationFailure;
      try {
        // Include descendants observed during the stop, not only the pre-stop snapshot.
        started.addAll(StartedProcesses.of(candidate));
      } catch (RuntimeException unreadable) {
        return "Self-test cleanup unverified: " + message(unreadable);
      }
      // On POSIX a killed wrapper cannot run its exit trap. End its retained serving handles
      // before waiting, including when the probe rejected an unauthenticated runtime (K8).
      if (!io.justsearch.configuration.PlatformPaths.isWindows()
          && started.stream().anyMatch(io.justsearch.app.api.runtime.child.ProcessTerminator::isRunning)) {
        try {
          int ended = control.endRemaining(candidate,
              StopRequest.of(StopReason.SELF_TEST, StopMode.NOW, REQUESTED_BY));
          // Preserve S19 for a process that outlived an already-exited candidate. A normal
          // stop has already cleaned its scope in control. Zero can also mean it just exited;
          // drain the earlier observations and recheck the retained scope below in that case.
          if (ended > 0) return "A process the self-test llama-server (pid " + candidate.pid()
              + ") started is still running after its root exited; ended " + ended + " left running";
        } catch (IOException | RuntimeException notEnded) {
          return "Self-test cleanup unverified: " + message(notEnded);
        }
      }
      if (!StartedProcesses.awaitExit(List.copyOf(started), STOP_WAIT)) {
        return endLeftRunning(candidate);
      }
      try {
        // A descendant can fork while the earlier snapshot drains. Read the retained scope once
        // more before certifying cleanup, rather than declaring success from an obsolete list.
        if (!StartedProcesses.of(candidate).isEmpty()) return endLeftRunning(candidate);
      } catch (RuntimeException unreadable) {
        return "Self-test cleanup unverified: " + message(unreadable);
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
      return null;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /**
   * A process the candidate started outlived it: the self-test fails (S19), and ChildControl ends
   * what was left in the candidate's own job or retained descendant handles, recording each as an
   * intended cleanup. Any survivor stays the next chat start's barrier.
   */
  private String endLeftRunning(ChildHandle candidate) {
    String failure = "A process the self-test llama-server (pid " + candidate.pid()
        + ") started is still running";
    LOG.warn("{}", failure);
    try {
      int ended = control.endRemaining(candidate,
          StopRequest.of(StopReason.SELF_TEST, StopMode.NOW, REQUESTED_BY));
      return failure + " after it exited; ended " + ended + " left running";
    } catch (IOException | RuntimeException notEnded) {
      return failure + " and could not be ended: " + message(notEnded);
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
