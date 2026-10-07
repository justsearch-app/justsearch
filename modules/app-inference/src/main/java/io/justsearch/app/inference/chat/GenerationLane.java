/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.chat.GenerationDeadline;
import io.justsearch.app.api.chat.GenerationUnavailableException;
import io.justsearch.core.context.EngineContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One serving generation exchange at a time. No lifecycle lease is held while waiting.
 * Foreground passes background until the oldest background has waited two seconds; that entry
 * then passes new foreground arrivals. Urgency is read from the work owner on every selection.
 * A lifecycle closure invalidates existing waiters even if admission is reopened before they wake.
 * The lane is retained across replacements: an old producer can only release its own permit.
 */
public final class GenerationLane {
  private static final int MAX_WAITERS = 256;
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
  private final GenerationTracker lifecycle;
  private final long agingNanos;
  private final ArrayList<Entry> waiters = new ArrayList<>();
  private Permit active;
  private long sequence;

  public GenerationLane(GenerationTracker lifecycle) {
    this(lifecycle, Duration.ofSeconds(2));
  }

  GenerationLane(GenerationTracker lifecycle, Duration aging) {
    this.lifecycle = lifecycle;
    agingNanos = aging.toNanos();
  }

  private record Entry(long order, long arrived, EngineWorkHandle work) {
    boolean foreground() {
      return work != null && work.context().urgency() == EngineContext.Urgency.FOREGROUND;
    }
  }

  public Permit acquire(EngineWorkHandle work, long deadline, BooleanSupplier abandoned)
      throws InterruptedException {
    long submitted = System.nanoTime();
    long version = lifecycle.admissionVersion();
    synchronized (this) {
      lifecycle.requireOpen();
      if (waiters.size() >= MAX_WAITERS) throw GenerationDeadline.busy();
      Entry entry = new Entry(sequence++, System.nanoTime(), work);
      waiters.add(entry);
      try {
        for (;;) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
          if (work != null) work.cancellationReason().ifPresent(reason -> {
            throw new EngineWorkCancelledException(reason);
          });
          if (abandoned.getAsBoolean()) throw new java.util.concurrent.CancellationException();
          lifecycle.requireOpen();
          if (version != lifecycle.admissionVersion()) {
            throw new GenerationUnavailableException("Generation waiter superseded by a lifecycle transition");
          }
          long now = System.nanoTime();
          long remaining = deadline - now;
          if (remaining <= 0) throw GenerationDeadline.busy();
          Entry next = waiters.stream().min(Comparator
              .comparingInt((Entry e) -> now - e.arrived >= agingNanos ? 0 : e.foreground() ? 1 : 2)
              .thenComparingLong(Entry::order)).orElseThrow();
          if (active == null && next == entry) {
            active = new Permit(System.nanoTime() - submitted);
            return active;
          }
          TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, POLL_NANOS));
        }
      } finally {
        waiters.remove(entry);
        notifyAll();
      }
    }
  }

  public final class Permit implements AutoCloseable {
    private boolean released;
    private final long waitNanos;
    private Permit(long waitNanos) { this.waitNanos = waitNanos; }

    /** Admission cost/wait only, excluding executor, upstream and consumer delivery. */
    public long waitNanos() { return waitNanos; }

    @Override public void close() {
      synchronized (GenerationLane.this) {
        if (released) return;
        released = true;
        if (active == this) active = null;
        GenerationLane.this.notifyAll();
      }
    }
  }
}
