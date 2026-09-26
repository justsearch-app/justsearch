/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generation-owned inference resources and their exact index-time model identity.
 *
 * <p>Each set has an independent readiness barrier and lease domain. Retirement stops new
 * acquisitions, lets already-issued work retain the set through {@link Lease#fork()}, waits for
 * those leases to leave, and only then asks the owned {@link InferenceSurface} to retire its native
 * handles. A refused surface close retains the set in retirement and may be retried.
 */
public final class EncoderSet implements AutoCloseable {
  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

  private final InferenceSurface surface;
  private final ModelIdentity modelIdentity;
  private final long closeTimeoutNanos;
  private final CountDownLatch modelReadyLatch = new CountDownLatch(1);
  private final Object lifecycleMonitor = new Object();

  private int holders;
  private boolean retiring;
  private boolean closeRunning;
  private boolean closed;

  public EncoderSet(InferenceSurface surface, ModelIdentity modelIdentity) {
    this(surface, modelIdentity, DEFAULT_CLOSE_TIMEOUT);
  }

  EncoderSet(InferenceSurface surface, ModelIdentity modelIdentity, Duration closeTimeout) {
    this.surface = Objects.requireNonNull(surface, "surface");
    this.modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
    Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isNegative()) {
      throw new IllegalArgumentException("closeTimeout must not be negative");
    }
    this.closeTimeoutNanos = closeTimeout.toNanos();
  }

  /** Exact immutable identity used to build this set and its generation's fingerprint inputs. */
  public ModelIdentity modelIdentity() {
    return modelIdentity;
  }

  /** Owner-only access for wiring the published generation's diagnostics and policies. */
  InferenceSurface surfaceForOwner() {
    return surface;
  }

  /** Per-set barrier wired to consumers that must wait until all model services are installed. */
  public CountDownLatch modelReadyLatch() {
    return modelReadyLatch;
  }

  /** Releases this set's readiness barrier after model wiring either succeeds or degrades. */
  public void releaseModelReady() {
    modelReadyLatch.countDown();
  }

  /** Captures this exact set for one logical operation. */
  public Lease acquire() {
    synchronized (lifecycleMonitor) {
      if (retiring) {
        throw new IllegalStateException("Encoder set is retiring");
      }
      holders++;
      return new Lease();
    }
  }

  /** True after retirement starts; this state is monotonic even when native close is refused. */
  public boolean isRetiring() {
    synchronized (lifecycleMonitor) {
      return retiring;
    }
  }

  /** True only after all set leases drained and the owned inference surface closed successfully. */
  public boolean isClosed() {
    synchronized (lifecycleMonitor) {
      return closed;
    }
  }

  /**
   * Retires this set without allowing its native handles to race an issued set lease.
   *
   * <p>Interruption retains ownership and the monotonic retiring state. A subsequent call may
   * finish the drain and retry a refused native close.
   */
  @Override
  public void close() {
    long deadline = System.nanoTime() + closeTimeoutNanos;
    synchronized (lifecycleMonitor) {
      retiring = true;
      while (holders != 0 || closeRunning) {
        if (closed) {
          return;
        }
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
          throw new IllegalStateException(
              "Encoder-set retirement exceeded its close deadline; owner retained");
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remainingNanos);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted waiting for encoder-set leases; owner retained", interrupted);
        }
      }
      if (closed) {
        return;
      }
      closeRunning = true;
    }

    boolean completed = false;
    try {
      surface.close();
      completed = true;
    } finally {
      synchronized (lifecycleMonitor) {
        closed = completed;
        closeRunning = false;
        lifecycleMonitor.notifyAll();
      }
    }
  }

  /** A hold on this set's exact surface and identity. */
  public final class Lease implements AutoCloseable {
    private final AtomicBoolean released = new AtomicBoolean();

    private Lease() {}

    public InferenceSurface surface() {
      if (released.get()) {
        throw new IllegalStateException("Encoder-set lease already released");
      }
      return surface;
    }

    public ModelIdentity modelIdentity() {
      if (released.get()) {
        throw new IllegalStateException("Encoder-set lease already released");
      }
      return modelIdentity;
    }

    /** Child work may retain its already-issued set after ordinary acquisitions stop. */
    public Lease fork() {
      synchronized (lifecycleMonitor) {
        if (released.get()) {
          throw new IllegalStateException("Encoder-set lease already released");
        }
        holders++;
        return new Lease();
      }
    }

    @Override
    public void close() {
      if (!released.compareAndSet(false, true)) {
        return;
      }
      synchronized (lifecycleMonitor) {
        holders--;
        lifecycleMonitor.notifyAll();
      }
    }
  }

  /**
   * Model inputs captured for one representation generation.
   *
   * <p>The three fingerprints preserve configured, absent, and indeterminate states. BGE-M3's
   * current index identity is the sparse selection plus its effective vector dimension, as defined
   * by the D1 fingerprint contract; it is deliberately not inferred from process statics.
   */
  public record ModelIdentity(
      IndexFingerprint.ModelFingerprint embeddingModel,
      IndexFingerprint.ModelFingerprint spladeModel,
      IndexFingerprint.ModelFingerprint nerModel,
      boolean bgeM3Selected,
      int vectorDimension) {
    public ModelIdentity {
      Objects.requireNonNull(embeddingModel, "embeddingModel");
      Objects.requireNonNull(spladeModel, "spladeModel");
      Objects.requireNonNull(nerModel, "nerModel");
      if (vectorDimension <= 0) {
        throw new IllegalArgumentException("vectorDimension must be positive");
      }
    }
  }
}
