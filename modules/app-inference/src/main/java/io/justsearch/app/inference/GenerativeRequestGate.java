/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Admits actual generative resource users and drains them before in-place replacement. */
final class GenerativeRequestGate {
  private boolean reloading;
  private boolean recoveryClosed;
  private int active;

  synchronized void requireOpen() {
    if (recoveryClosed || reloading) throw new IllegalStateException("Generative component is unavailable");
  }

  /** Boot recovery failure cannot reopen admission through a late candidate-hold close. */
  synchronized void fenceForRecovery() {
    recoveryClosed = true;
    reloading = true;
    notifyAll();
  }

  synchronized Lease acquire() {
    requireOpen();
    active++;
    return new Lease();
  }

  Hold closeAndDrain(Duration timeout) throws InterruptedException {
    long remaining = timeout.toNanos();
    long deadline = System.nanoTime() + remaining;
    synchronized (this) {
      if (reloading) throw new IllegalStateException("Generative replacement already owns admission");
      reloading = true;
      while (active != 0) {
        if (remaining <= 0) {
          reloading = false;
          notifyAll();
          throw new IllegalStateException("Generative requests did not drain before replacement");
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException interrupted) {
          reloading = false;
          notifyAll();
          throw interrupted;
        }
        remaining = deadline - System.nanoTime();
      }
      return new Hold();
    }
  }

  final class Lease implements AutoCloseable {
    private boolean released;

    @Override
    public void close() {
      synchronized (GenerativeRequestGate.this) {
        if (released) return;
        released = true;
        active--;
        GenerativeRequestGate.this.notifyAll();
      }
    }
  }

  final class Hold implements AutoCloseable {
    private boolean released;

    @Override
    public void close() {
      synchronized (GenerativeRequestGate.this) {
        if (released) return;
        released = true;
        if (!recoveryClosed) reloading = false;
        GenerativeRequestGate.this.notifyAll();
      }
    }
  }
}
