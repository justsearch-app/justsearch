/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Process-local final fence for Worker mutation producers.
 *
 * <p>Each accepted producer keeps a read lease through its complete queue or index effect. The
 * cutover owner takes the write lease only after Green is prepared, then drains the already
 * accepted effects and performs replay and publication before handing admission to B. A producer
 * that was waiting on the fence with an A reference is refused on wakeup; it cannot write retired A.
 * This class owns no durable state: the existing switch buffer and generation pointer own replay
 * and commitment.
 */
public final class WorkerMutationAdmission {
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
  private final AtomicBoolean replayUncertain = new AtomicBoolean();
  private final Object fencedOwner = new Object();
  private Object owner;

  public WorkerMutationAdmission(Object initialOwner) {
    owner = Objects.requireNonNull(initialOwner, "initialOwner");
  }

  /** A lost watcher routing effect cannot be certified by the switch-buffer replay. */
  public void markReplayUncertain() { replayUncertain.set(true); }

  public boolean replayCertain() { return !replayUncertain.get(); }

  public Lease enter(Object expectedOwner) {
    Objects.requireNonNull(expectedOwner, "expectedOwner");
    lock.readLock().lock();
    if (owner != expectedOwner) {
      lock.readLock().unlock();
      throw new IllegalStateException("Mutation producer belongs to a retired serving view");
    }
    return new Lease();
  }

  /** Waits for issued effects to finish; timeout leaves the serving owner unchanged. */
  public FinalFence beginFinalFence(Object expectedOwner, long timeoutMs)
      throws InterruptedException {
    Objects.requireNonNull(expectedOwner, "expectedOwner");
    if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must be nonnegative");
    if (!lock.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return null;
    if (owner != expectedOwner) {
      lock.writeLock().unlock();
      throw new IllegalStateException("Cutover owner is no longer serving");
    }
    return new FinalFence(expectedOwner);
  }

  public final class Lease implements AutoCloseable {
    private final Thread holder = Thread.currentThread();
    private boolean closed;

    private Lease() {}

    @Override public void close() {
      if (Thread.currentThread() != holder) {
        throw new IllegalStateException("Mutation lease must close on its owning thread");
      }
      if (closed) return;
      closed = true;
      lock.readLock().unlock();
    }
  }

  public final class FinalFence implements AutoCloseable {
    private final Object prior;
    private final Thread holder = Thread.currentThread();
    private boolean installed;
    private boolean certified;
    private boolean closed;

    private FinalFence(Object prior) { this.prior = prior; }

    /** Changes only process-local admission after the durable pointer commits. */
    public void install(Object successor) {
      requireOpen();
      Objects.requireNonNull(successor, "successor");
      if (successor == prior) throw new IllegalArgumentException("Successor must be distinct");
      if (installed) throw new IllegalStateException("Successor already installed");
      owner = successor;
      installed = true;
    }

    /** Only exact replay cleanup and publication may release B to new mutation producers. */
    public void certifySuccessor() {
      requireOpen();
      if (!installed) throw new IllegalStateException("Successor has not been installed");
      certified = true;
    }

    private void requireOpen() {
      if (closed || Thread.currentThread() != holder) {
        throw new IllegalStateException("Final fence belongs to its preparing thread");
      }
    }

    @Override public void close() {
      requireOpen();
      closed = true;
      if (installed && !certified) owner = fencedOwner;
      lock.writeLock().unlock();
    }
  }
}
