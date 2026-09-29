/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.util.Objects;
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
  private final Object replayCertificateMonitor = new Object();
  private final Object fencedOwner = new Object();
  private boolean replayUncertain;
  private Object owner;

  public WorkerMutationAdmission(Object initialOwner) {
    owner = Objects.requireNonNull(initialOwner, "initialOwner");
  }

  /** A lost watcher routing effect cannot be certified by the switch-buffer replay. */
  public void markReplayUncertain() {
    synchronized (replayCertificateMonitor) {
      replayUncertain = true;
    }
  }

  public boolean replayCertain() {
    synchronized (replayCertificateMonitor) {
      return !replayUncertain;
    }
  }

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
    private boolean replayCertificateArmed;
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

    /**
     * Arms the replay certificate before durable pointer publication.
     *
     * <p>Once armed, any replay uncertainty marked before this fence closes prevents successor
     * admission. A false result is a pre-pointer refusal: the caller must not publish or install the
     * successor.
     */
    public boolean armReplayCertificate() {
      requireOpen();
      if (installed) {
        throw new IllegalStateException("Replay certificate must arm before successor installation");
      }
      if (replayCertificateArmed) {
        throw new IllegalStateException("Replay certificate already armed");
      }
      synchronized (replayCertificateMonitor) {
        if (replayUncertain) return false;
        replayCertificateArmed = true;
        return true;
      }
    }

    /** Only exact replay cleanup and publication may release B to new mutation producers. */
    public void certifySuccessor() {
      requireOpen();
      if (!installed) throw new IllegalStateException("Successor has not been installed");
      if (replayCertificateArmed) {
        throw new IllegalStateException("Armed replay certificate requires conditional certification");
      }
      certified = true;
    }

    /**
     * Certifies an installed successor only while the armed replay witness remains certain.
     *
     * <p>The fence rechecks the witness when it closes, so a concurrent failure that follows this
     * check but linearizes before admission release still leaves the installed successor fenced. A
     * failure after admission release belongs to the successor's live fault-recovery path; it does
     * not retroactively fail this cutover.
     */
    public boolean certifySuccessorIfReplayCertain() {
      requireOpen();
      if (!installed) throw new IllegalStateException("Successor has not been installed");
      if (!replayCertificateArmed) {
        throw new IllegalStateException("Replay certificate has not been armed");
      }
      synchronized (replayCertificateMonitor) {
        if (replayUncertain) return false;
        certified = true;
        return true;
      }
    }

    private void requireOpen() {
      if (closed || Thread.currentThread() != holder) {
        throw new IllegalStateException("Final fence belongs to its preparing thread");
      }
    }

    @Override public void close() {
      requireOpen();
      closed = true;
      synchronized (replayCertificateMonitor) {
        if (installed && (!certified || (replayCertificateArmed && replayUncertain))) {
          owner = fencedOwner;
        }
        // Release admission inside the certificate decision's critical section. A watcher failure
        // can therefore linearize either before release (and fence B) or after release (as a live-B
        // fault), never between the final certainty check and producer admission.
        lock.writeLock().unlock();
      }
    }
  }
}
