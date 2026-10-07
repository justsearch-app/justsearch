/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildRegistrationRefusedException;
import io.justsearch.app.api.runtime.child.StopRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Physical child ownership, independent of transition, health and registry-persistence locks: the
 * terminal fence and the set of launched llama-servers it ends (Spec B §2.8). Every kill goes
 * through {@link ChildControl}, so each one is a recorded stop with the Engine's reason.
 */
final class LlamaServerProcessOwnership {
  @FunctionalInterface
  interface ChildStarter {
    ChildHandle start() throws IOException;
  }

  private final ChildControl control;

  // Only reservations and the terminal fence are changed under this gate. No OS calls or I/O.
  private final Object gate = new Object();
  private final Set<CompletableFuture<ChildHandle>> owned =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private boolean terminal;
  private StopRequest terminalRequest;

  LlamaServerProcessOwnership(ChildControl control) {
    this.control = Objects.requireNonNull(control, "control");
  }

  void requireLaunchAllowed() throws IOException {
    synchronized (gate) {
      if (terminal) throw new IOException("llama-server ownership is closing");
    }
  }

  ChildHandle launch(ChildStarter starter) throws IOException {
    var reservation = new CompletableFuture<ChildHandle>();
    synchronized (gate) {
      if (terminal) throw new IOException("llama-server ownership is closing");
      owned.add(reservation);
    }
    try {
      ChildHandle started;
      try {
        started = starter.start();
      } catch (ChildRegistrationRefusedException refused) {
        // A rolled-back server whose stop did not confirm its exit is still ours to end: the
        // terminal stop owns it until it exits.
        var survivor = refused.survivor();
        if (survivor.isPresent()) {
          own(reservation, survivor.get());
          StopRequest late;
          synchronized (gate) {
            late = terminal ? terminalRequest : null;
          }
          if (late != null) {
            var _ = control.stop(survivor.get(), late);
          }
        }
        throw refused;
      }
      // Publish before any health wait. A terminal snapshot owns this launch even if the start
      // itself outlives the shutdown deadline.
      own(reservation, started);
      StopRequest late;
      synchronized (gate) {
        if (!terminal) return started;
        late = terminalRequest;
      }
      var _ = control.stop(started, late);
      throw new IOException("llama-server launch completed after terminal shutdown");
    } finally {
      if (reservation.complete(null)) {
        synchronized (gate) {
          owned.remove(reservation);
        }
      }
    }
  }

  /** Owns {@code child} through {@code reservation} until the child exits. */
  private void own(CompletableFuture<ChildHandle> reservation, ChildHandle child) {
    reservation.complete(child);
    child.exit().whenComplete((ignored, failure) -> {
      synchronized (gate) {
        owned.remove(reservation);
      }
    });
  }

  /**
   * Fences further launches and stops every owned server with {@code request} (one {@code stopAll}
   * for those already started; a launch still inside the OS is stopped by {@link #launch} when it
   * returns). Throws when one is still alive at the deadline.
   */
  void stopTerminal(StopRequest request, Duration timeout) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Terminal server stop requires a positive timeout");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    List<CompletableFuture<ChildHandle>> snapshot;
    StopRequest stop;
    synchronized (gate) {
      terminal = true;
      if (terminalRequest == null) terminalRequest = request;
      stop = request;
      snapshot = List.copyOf(owned);
    }
    List<ChildHandle> started = new ArrayList<>();
    List<CompletableFuture<?>> waits = new ArrayList<>();
    for (var reservation : snapshot) {
      ChildHandle handle = reservation.getNow(null);
      if (handle != null) {
        started.add(handle);
      } else {
        waits.add(reservation.thenCompose(late -> late == null
            ? CompletableFuture.completedFuture(null)
            : late.exit().toCompletableFuture()));
      }
    }
    if (!started.isEmpty()) waits.add(control.stopAll(started, stop).toCompletableFuture());
    try {
      CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
          .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException survived) {
      throw new IllegalStateException("Managed llama-server survived terminal cleanup deadline");
    } catch (ExecutionException survived) {
      throw new IllegalStateException(
          "Managed llama-server survived terminal cleanup deadline", survived.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Terminal llama-server stop interrupted", interrupted);
    }
  }
}
