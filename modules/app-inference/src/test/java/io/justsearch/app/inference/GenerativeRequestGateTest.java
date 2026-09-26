/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class GenerativeRequestGateTest {
  @Test
  void replacementRefusesNewUsersAndWaitsForIssuedLease() throws Exception {
    var gate = new GenerativeRequestGate();
    var issued = gate.acquire();
    var started = new CountDownLatch(1);
    var replacement = CompletableFuture.runAsync(() -> {
      started.countDown();
      try {
        var hold = gate.closeAndDrain(Duration.ofSeconds(5));
        try {
          assertThrows(IllegalStateException.class, gate::acquire);
        } finally {
          hold.close();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean closed = false;
    while (System.nanoTime() < deadline) {
      try {
        gate.requireOpen();
      } catch (IllegalStateException expected) {
        closed = true;
        break;
      }
      Thread.onSpinWait();
    }
    assertTrue(closed);
    assertFalse(replacement.isDone(), "issued resource user must hold replacement");
    issued.close();
    replacement.get(5, TimeUnit.SECONDS);
    var reopened = gate.acquire();
    try {
      gate.requireOpen();
    } finally {
      reopened.close();
    }
  }

  @Test
  void drainTimeoutReopensAdmissionWithoutDestroyingHolder() {
    var gate = new GenerativeRequestGate();
    var issued = gate.acquire();
    try {
      assertThrows(IllegalStateException.class,
          () -> gate.closeAndDrain(Duration.ZERO));
      var second = gate.acquire();
      try {
        gate.requireOpen();
      } finally {
        second.close();
      }
    } finally {
      issued.close();
    }
  }

  @Test
  void recoveryFenceRemainsClosedAfterAnExistingCandidateHoldCloses() throws Exception {
    var gate = new GenerativeRequestGate();
    var hold = gate.closeAndDrain(Duration.ofSeconds(1));
    gate.fenceForRecovery();
    hold.close();
    assertThrows(IllegalStateException.class, gate::acquire);
    assertThrows(IllegalStateException.class, gate::requireOpen);
  }
}
