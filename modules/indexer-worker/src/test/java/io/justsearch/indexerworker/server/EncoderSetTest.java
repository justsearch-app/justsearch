/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EncoderSetTest {
  private static final EncoderSet.ModelIdentity IDENTITY_A = identity("a", 768);
  private static final EncoderSet.ModelIdentity IDENTITY_B = identity("b", 1024);

  @Test
  void coResidentSetRetirementWaitsForItsExactLeaseWithoutBlockingTheOtherSet()
      throws Exception {
    InferenceSurface surfaceA = mock(InferenceSurface.class);
    InferenceSurface surfaceB = mock(InferenceSurface.class);
    EncoderSet setA = new EncoderSet(surfaceA, IDENTITY_A);
    EncoderSet setB = new EncoderSet(surfaceB, IDENTITY_B);
    EncoderSet.Lease leaseA = setA.acquire();
    CountDownLatch closeStarted = new CountDownLatch(1);

    Thread closer =
        Thread.ofVirtual()
            .start(
                () -> {
                  closeStarted.countDown();
                  setA.close();
                });

    assertTrue(closeStarted.await(1, TimeUnit.SECONDS));
    awaitRetiring(setA);
    assertThrows(IllegalStateException.class, setA::acquire);
    try (EncoderSet.Lease leaseB = setB.acquire()) {
      assertSame(surfaceB, leaseB.surface());
      assertEquals(IDENTITY_B, leaseB.modelIdentity());
    }
    verify(surfaceA, never()).close();

    leaseA.close();
    closer.join(1_000);
    assertFalse(closer.isAlive());
    assertTrue(setA.isClosed());
    verify(surfaceA).close();
    verify(surfaceB, never()).close();

    setB.close();
  }

  @Test
  void issuedLeaseCanForkDuringRetirementAndBothHoldsMustLeave() throws Exception {
    InferenceSurface surface = mock(InferenceSurface.class);
    EncoderSet set = new EncoderSet(surface, IDENTITY_A);
    EncoderSet.Lease parent = set.acquire();
    Thread closer = Thread.ofVirtual().start(set::close);
    awaitRetiring(set);

    EncoderSet.Lease child = parent.fork();
    parent.close();
    verify(surface, never()).close();
    child.close();

    closer.join(1_000);
    assertFalse(closer.isAlive());
    verify(surface).close();
  }

  @Test
  void readinessBarrierAndIdentityBelongToOneSet() throws Exception {
    EncoderSet setA = new EncoderSet(mock(InferenceSurface.class), IDENTITY_A);
    EncoderSet setB = new EncoderSet(mock(InferenceSurface.class), IDENTITY_B);

    assertEquals(1, setA.modelReadyLatch().getCount());
    assertEquals(1, setB.modelReadyLatch().getCount());
    assertEquals(IDENTITY_A, setA.modelIdentity());
    assertEquals(IDENTITY_B, setB.modelIdentity());

    setA.releaseModelReady();

    assertTrue(setA.modelReadyLatch().await(10, TimeUnit.MILLISECONDS));
    assertFalse(setB.modelReadyLatch().await(10, TimeUnit.MILLISECONDS));
    setA.close();
    setB.close();
  }

  @Test
  void refusedSurfaceCloseRetainsRetirementAndCanBeRetried() {
    InferenceSurface surface = mock(InferenceSurface.class);
    doThrow(new IllegalStateException("native handle retained"))
        .doNothing()
        .when(surface)
        .close();
    EncoderSet set = new EncoderSet(surface, IDENTITY_A);

    assertThrows(IllegalStateException.class, set::close);
    assertTrue(set.isRetiring());
    assertFalse(set.isClosed());
    assertThrows(IllegalStateException.class, set::acquire);

    set.close();

    assertTrue(set.isClosed());
    verify(surface, times(2)).close();
  }

  @Test
  void ownedWrapperClosesAfterTheExactLeaseAndNativeSurface() throws Exception {
    InferenceSurface surface = mock(InferenceSurface.class);
    AutoCloseable wrapper = mock(AutoCloseable.class);
    EncoderSet set = new EncoderSet(surface, IDENTITY_A);
    set.own(wrapper);
    EncoderSet.Lease lease = set.acquire();

    Thread closer = Thread.ofVirtual().start(set::close);
    awaitRetiring(set);
    verify(surface, never()).close();
    verify(wrapper, never()).close();
    lease.close();
    closer.join(1_000);

    assertFalse(closer.isAlive());
    assertTrue(set.isClosed());
    var order = inOrder(surface, wrapper);
    order.verify(surface).close();
    order.verify(wrapper).close();
  }

  @Test
  void failedWrapperCloseRetainsRetirementAndRetriesOnlyThatWrapper() throws Exception {
    InferenceSurface surface = mock(InferenceSurface.class);
    AutoCloseable wrapper = mock(AutoCloseable.class);
    doThrow(new IllegalStateException("wrapper retained")).doNothing().when(wrapper).close();
    EncoderSet set = new EncoderSet(surface, IDENTITY_A);
    set.own(wrapper);

    assertThrows(IllegalStateException.class, set::close);
    assertTrue(set.isRetiring());
    assertFalse(set.isClosed());
    assertThrows(IllegalStateException.class, set::acquire);

    set.close();
    assertTrue(set.isClosed());
    verify(wrapper, times(2)).close();
  }

  @Test
  void interruptedRetirementKeepsTheWrapperUntilTheIssuedLeaseLeaves() throws Exception {
    InferenceSurface surface = mock(InferenceSurface.class);
    AutoCloseable wrapper = mock(AutoCloseable.class);
    EncoderSet set = new EncoderSet(surface, IDENTITY_A);
    set.own(wrapper);
    EncoderSet.Lease issued = set.acquire();
    java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread closer = Thread.ofVirtual().start(() -> {
      try { set.close(); }
      catch (Throwable interrupted) { failure.set(interrupted); }
    });

    awaitRetiring(set);
    closer.interrupt();
    closer.join(1_000);
    assertFalse(closer.isAlive());
    assertTrue(failure.get() instanceof IllegalStateException);
    assertTrue(set.isRetiring());
    assertFalse(set.isClosed());
    verify(surface, never()).close();
    verify(wrapper, never()).close();

    issued.close();
    set.close();
    var order = inOrder(surface, wrapper);
    order.verify(surface).close();
    order.verify(wrapper).close();
  }

  @Test
  void closeDeadlineRefusesWithLeaseHeldAndRetriesAfterItDrains() {
    InferenceSurface surface = mock(InferenceSurface.class);
    EncoderSet set = new EncoderSet(surface, IDENTITY_A, Duration.ZERO);
    EncoderSet.Lease lease = set.acquire();

    assertThrows(IllegalStateException.class, set::close);
    assertTrue(set.isRetiring());
    assertFalse(set.isClosed());
    assertThrows(IllegalStateException.class, set::acquire);
    verify(surface, never()).close();

    lease.close();
    set.close();

    assertTrue(set.isClosed());
    verify(surface).close();
  }

  @Test
  void releasedLeaseCannotExposeOrRetainTheSetAgain() {
    EncoderSet set = new EncoderSet(mock(InferenceSurface.class), IDENTITY_A);
    EncoderSet.Lease lease = set.acquire();

    lease.close();
    lease.close();

    assertThrows(IllegalStateException.class, lease::surface);
    assertThrows(IllegalStateException.class, lease::modelIdentity);
    assertThrows(IllegalStateException.class, lease::fork);
    set.close();
  }

  private static EncoderSet.ModelIdentity identity(String sha, int vectorDimension) {
    IndexFingerprint.ModelFingerprint model = IndexFingerprint.ModelFingerprint.present(sha);
    return new EncoderSet.ModelIdentity(
        model, model, model, vectorDimension == 1024, vectorDimension);
  }

  private static void awaitRetiring(EncoderSet set) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!set.isRetiring() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(set.isRetiring(), "retirement did not start");
  }
}
