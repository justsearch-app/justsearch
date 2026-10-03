/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Physical child ownership, independent of transition, health and registry-persistence locks. */
final class LlamaServerProcessOwnership {
  @FunctionalInterface
  interface ProcessStarter {
    Process start() throws IOException;
  }

  // Only reservations and the terminal fence are changed under this gate. No OS calls or I/O.
  private final Object gate = new Object();
  private final Set<CompletableFuture<ProcessHandle>> owned = new LinkedHashSet<>();
  private boolean terminal;

  void requireLaunchAllowed() throws IOException {
    synchronized (gate) {
      if (terminal) throw new IOException("llama-server ownership is closing");
    }
  }

  Process launch(ProcessStarter starter) throws IOException {
    var reservation = new CompletableFuture<ProcessHandle>();
    synchronized (gate) {
      if (terminal) throw new IOException("llama-server ownership is closing");
      owned.add(reservation);
    }
    try {
      Process started = starter.start();
      ProcessHandle handle = started.toHandle();
      // Publish before any registration or health wait. A terminal snapshot owns this launch
      // even if ProcessBuilder.start itself outlives the shutdown deadline.
      reservation.complete(handle);
      removeAfterExit(reservation, handle);
      synchronized (gate) {
        if (!terminal) return started;
      }
      handle.destroyForcibly();
      throw new IOException("llama-server launch completed after terminal shutdown");
    } finally {
      if (reservation.complete(null)) {
        synchronized (gate) { owned.remove(reservation); }
      }
    }
  }

  void adoptManaged(ProcessHandle handle) throws IOException {
    var reservation = CompletableFuture.completedFuture(handle);
    synchronized (gate) {
      if (terminal) throw new IOException("llama-server ownership is closing");
      owned.add(reservation);
    }
    removeAfterExit(reservation, handle);
  }

  /** Capture already-held handles too, including unregistered rollback children. */
  void capture(ProcessHandle handle) {
    if (handle == null) return;
    synchronized (gate) {
      if (owned.stream().anyMatch(reservation -> handle.equals(reservation.getNow(null)))) return;
      owned.add(CompletableFuture.completedFuture(handle));
    }
  }

  private void removeAfterExit(CompletableFuture<ProcessHandle> reservation, ProcessHandle handle) {
    handle.onExit().thenRun(() -> {
      synchronized (gate) { owned.remove(reservation); }
    });
  }

  void stopTerminal(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Terminal server stop requires a positive timeout");
    }
    long budget = timeout.toNanos();
    long startedAt = System.nanoTime();
    long deadline = startedAt + budget;
    long gracefulDeadline = startedAt + Math.min(TimeUnit.SECONDS.toNanos(5), budget / 2);
    List<CompletableFuture<ProcessHandle>> snapshot;
    synchronized (gate) {
      terminal = true;
      snapshot = List.copyOf(owned);
    }
    // Signal every available child first; no child gets its own full wait budget. A launch still
    // inside the OS gets an unconditional forced-stop callback, which survives caller timeout.
    for (var reservation : snapshot) {
      ProcessHandle handle = reservation.getNow(null);
      if (handle != null) {
        try { handle.destroy(); } catch (RuntimeException refused) { /* force and verify below */ }
      } else reservation.thenAccept(late -> { if (late != null) late.destroyForcibly(); });
    }
    try {
      for (var reservation : snapshot) awaitExit(reservation, gracefulDeadline);
    } catch (InterruptedException interrupted) {
      forceAll(snapshot);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Terminal llama-server stop interrupted", interrupted);
    }
    forceAll(snapshot);
    try {
      boolean exited = true;
      for (var reservation : snapshot) exited &= awaitExit(reservation, deadline);
      if (!exited) throw new IllegalStateException("Managed llama-server survived terminal cleanup deadline");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Terminal llama-server stop interrupted", interrupted);
    }
  }

  private static void forceAll(List<CompletableFuture<ProcessHandle>> snapshot) {
    for (var reservation : snapshot) {
      ProcessHandle handle = reservation.getNow(null);
      if (handle != null && handle.isAlive()) {
        try { handle.destroyForcibly(); } catch (RuntimeException refused) { /* report survival below */ }
      }
    }
  }

  private static boolean awaitExit(CompletableFuture<ProcessHandle> reservation, long deadline)
      throws InterruptedException {
    try {
      ProcessHandle handle = reservation.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (handle == null || !handle.isAlive()) return true;
      handle.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      return !handle.isAlive();
    } catch (TimeoutException timeout) {
      return false;
    } catch (ExecutionException failure) {
      // An exit-observer failure cannot bypass the forced-stop phase or count as proof of exit.
      return false;
    }
  }
}
