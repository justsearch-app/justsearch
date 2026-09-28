/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.reranker.CitationScorer;
import io.justsearch.reranker.CrossEncoderReranker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reranker and citation sessions with a lease lifetime independent of index-time models. */
final class QueryRoleSet implements AutoCloseable {
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  private final InferenceSurface surface;
  private final Object monitor = new Object();
  private final List<AutoCloseable> wrappers = new ArrayList<>();
  private int holders;
  private boolean retiring;
  private boolean closeRunning;
  private boolean closed;
  private CrossEncoderReranker reranker;
  private CitationScorer citation;

  QueryRoleSet(InferenceSurface surface) {
    this.surface = Objects.requireNonNull(surface, "surface");
    if (surface.embedding().isPresent() || surface.ner().isPresent()
        || surface.splade().isPresent() || surface.bgeM3().isPresent()) {
      throw new IllegalArgumentException("Query owner cannot retain index-role assemblies");
    }
  }

  InferenceSurface surfaceForOwner() { return surface; }
  CrossEncoderReranker reranker() { return reranker; }
  CitationScorer citation() { return citation; }

  <T extends AutoCloseable> T own(T wrapper) {
    Objects.requireNonNull(wrapper, "wrapper");
    synchronized (monitor) {
      if (retiring || wrappers.stream().anyMatch(owned -> owned == wrapper)) {
        throw new IllegalStateException("Query wrapper cannot be registered twice or after retirement");
      }
      wrappers.add(wrapper);
    }
    return wrapper;
  }

  void bindReranker(CrossEncoderReranker wrapper) {
    requireOwned(wrapper);
    reranker = wrapper;
  }

  void bindCitation(CitationScorer wrapper) {
    requireOwned(wrapper);
    citation = wrapper;
  }

  private void requireOwned(AutoCloseable wrapper) {
    Objects.requireNonNull(wrapper, "wrapper");
    synchronized (monitor) {
      if (retiring || wrappers.stream().noneMatch(owned -> owned == wrapper)) {
        throw new IllegalStateException("Query wrapper is not owned by this active set");
      }
    }
  }

  Lease acquire() {
    synchronized (monitor) {
      if (retiring) throw new IllegalStateException("Query role set is retiring");
      holders++;
      return new Lease();
    }
  }

  boolean isClosed() {
    synchronized (monitor) { return closed; }
  }

  @Override
  public void close() {
    long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
    synchronized (monitor) {
      retiring = true;
      while (holders != 0 || closeRunning) {
        if (closed) return;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new IllegalStateException("Query-role retirement exceeded its close deadline"
              + " (holders=" + holders + ", closeRunning=" + closeRunning + ")");
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted waiting for query-role leases", interrupted);
        }
      }
      if (closed) return;
      closeRunning = true;
    }
    boolean completed = false;
    try {
      surface.close();
      closeWrappers();
      completed = true;
    } finally {
      synchronized (monitor) {
        closed = completed;
        closeRunning = false;
        monitor.notifyAll();
      }
    }
  }

  private void closeWrappers() {
    List<AutoCloseable> pending;
    synchronized (monitor) { pending = List.copyOf(wrappers); }
    IllegalStateException failure = null;
    for (AutoCloseable wrapper : pending) {
      try {
        wrapper.close();
        synchronized (monitor) { wrappers.removeIf(owned -> owned == wrapper); }
      } catch (Exception closeFailure) {
        if (failure == null) failure = new IllegalStateException("Query wrapper retirement incomplete");
        failure.addSuppressed(closeFailure);
      }
    }
    if (failure != null) throw failure;
  }

  final class Lease implements AutoCloseable {
    private final AtomicBoolean released = new AtomicBoolean();

    private Lease() {}

    @Override public void close() {
      if (!released.compareAndSet(false, true)) return;
      synchronized (monitor) {
        holders--;
        monitor.notifyAll();
      }
    }
  }
}
