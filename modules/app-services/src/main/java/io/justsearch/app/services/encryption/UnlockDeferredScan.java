/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs idempotent store reconciliation off DataKeyManager's synchronized unlock listener.
 * One registered worker coalesces unlock notifications into a pending scan. No queue of scans
 * or resubmission window is needed: a notification during a scan requests one subsequent pass.
 */
public final class UnlockDeferredScan implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(UnlockDeferredScan.class);
  private final Runnable scan;
  private final ExecutorService executor;
  private final EngineExecutorRegistry.Registration executorOwner;
  private final Object monitor = new Object();
  private boolean pending;
  private boolean running;
  private boolean closed;
  private volatile Thread worker;

  public UnlockDeferredScan(EngineExecutorRegistry executors, String threadName, Runnable scan) {
    this.scan = Objects.requireNonNull(scan, "scan");
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    executorOwner = executors.register(new EngineExecutorSpec("head." + threadName,
        EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM,
        1, limits.maxQueue(), 1));
    try {
      executor = executorOwner.open(Thread.ofPlatform().daemon().name(threadName).factory());
      executor.execute(this::drain);
    } catch (RuntimeException | Error failure) {
      try { executorOwner.close(); }
      catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** Subscribe to transitions into UNLOCKED; listener work never scans under the key monitor. */
  public UnlockDeferredScan attachTo(DataKeyManager keys) {
    Objects.requireNonNull(keys, "keys").addListener((from, to) -> {
      if (to == DataKeyManager.State.UNLOCKED) schedule();
    });
    return this;
  }

  /** Request a scan, coalescing repeated notifications while one is pending or running. */
  public void schedule() {
    synchronized (monitor) {
      if (closed) return;
      pending = true;
      monitor.notifyAll();
    }
  }

  private void drain() {
    worker = Thread.currentThread();
    try {
      for (;;) {
        synchronized (monitor) {
          while (!pending && !closed) monitor.wait();
          if (!pending) return;
          pending = false;
          running = true;
        }
        try { scan.run(); }
        catch (RuntimeException | Error failure) { LOG.warn("Deferred unlock scan failed", failure); }
        finally {
          synchronized (monitor) {
            running = false;
            monitor.notifyAll();
          }
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      synchronized (monitor) {
        closed = true;
        running = false;
        monitor.notifyAll();
      }
    }
  }

  /** Waits on actual scan state, including a notification received during the previous pass. */
  boolean awaitQuiescence(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (pending || running) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
        catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  /** Stop accepting notifications, drain accepted scans briefly, then retire the owned worker. */
  @Override
  public void close() {
    synchronized (monitor) {
      closed = true;
      monitor.notifyAll();
    }
    executor.shutdown();
    if (Thread.currentThread() != worker) awaitQuiescence(Duration.ofSeconds(5));
    executorOwner.close();
  }
}
