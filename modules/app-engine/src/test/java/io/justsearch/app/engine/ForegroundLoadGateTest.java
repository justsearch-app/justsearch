/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.ops.ForegroundLoadInterceptor;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.SearchServiceGrpc;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Item A4. The gate must feed {@link ForegroundLoad} exactly as
 * {@code ForegroundLoadInterceptor} does today, so the pacing policy sees the same gauge before and
 * after the wire is deleted at A9.
 */
final class ForegroundLoadGateTest {

  private static final String NOT_FOREGROUND_INDEX_STATUS = "IndexStatus";
  private static final String NOT_FOREGROUND_LIST_ALL = "ListAllDocumentIds";

  /** Bare RPC method name of a full gRPC method name ({@code pkg.Service/Method}). */
  private static String bareMethod(String fullMethodName) {
    return fullMethodName.substring(fullMethodName.lastIndexOf('/') + 1);
  }

  @Test
  @DisplayName("the gate's operation set is identical to the interceptor's, name for name")
  void operationSetMatchesTheInterceptor() {
    Set<String> fromInterceptor =
        ForegroundLoadInterceptor.foregroundMethods().stream()
            .map(ForegroundLoadGateTest::bareMethod)
            .collect(Collectors.toUnmodifiableSet());

    // Identity in both directions: a rename, an addition or a removal on either side reds this
    // until A9 deletes the interceptor and leaves the gate as the only producer.
    assertEquals(fromInterceptor, ForegroundLoadGate.foregroundOperations());
    assertEquals(9, ForegroundLoadGate.foregroundOperations().size());
  }

  @Test
  @DisplayName("the two deliberate exclusions are excluded on both sides")
  void exclusionsAreMirrored() {
    assertFalse(ForegroundLoadGate.isForeground(NOT_FOREGROUND_INDEX_STATUS));
    assertFalse(ForegroundLoadGate.isForeground(NOT_FOREGROUND_LIST_ALL));
    assertFalse(
        ForegroundLoadInterceptor.isForeground(
            IngestServiceGrpc.getIndexStatusMethod().getFullMethodName()));
    assertFalse(
        ForegroundLoadInterceptor.isForeground(
            SearchServiceGrpc.getListAllDocumentIdsMethod().getFullMethodName()));
  }

  @Test
  @DisplayName("each of the nine operations increments during the call and decrements after it")
  void eachForegroundOperationIncrementsAndDecrements() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    int expectedTotal = 0;
    for (String operation : ForegroundLoadGate.foregroundOperations()) {
      String result = gate.call(operation, () -> "in-flight=" + load.inFlight());
      assertEquals("in-flight=1", result, operation + " must be counted while it executes");
      assertEquals(0, load.inFlight(), operation + " must not leak an in-flight count");
      expectedTotal++;
      assertEquals(expectedTotal, load.startedTotal());
    }
    assertEquals(9, expectedTotal);
  }

  @Test
  @DisplayName("the Runnable form counts the same nine operations")
  void runnableFormCountsTheSameOperations() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    for (String operation : ForegroundLoadGate.foregroundOperations()) {
      AtomicInteger observed = new AtomicInteger(-1);
      gate.run(operation, () -> observed.set(load.inFlight()));
      assertEquals(1, observed.get(), operation + " must be counted while it executes");
      assertEquals(0, load.inFlight());
    }
    assertEquals(9, load.startedTotal());
  }

  @Test
  @DisplayName("a thrown exception still decrements, for every operation and both forms")
  void exceptionStillDecrements() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    for (String operation : ForegroundLoadGate.foregroundOperations()) {
      assertThrows(
          IllegalStateException.class,
          () ->
              gate.call(
                  operation,
                  () -> {
                    throw new IllegalStateException("boom in " + operation);
                  }));
      assertEquals(0, load.inFlight(), operation + " must not leak on a thrown exception");

      assertThrows(
          IllegalStateException.class,
          () ->
              gate.run(
                  operation,
                  () -> {
                    throw new IllegalStateException("boom in " + operation);
                  }));
      assertEquals(0, load.inFlight());
    }
    assertEquals(18, load.startedTotal());
  }

  @Test
  @DisplayName("a cancellation still decrements, for every operation")
  void cancellationStillDecrements() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    for (String operation : ForegroundLoadGate.foregroundOperations()) {
      assertThrows(
          CancellationException.class,
          () ->
              gate.call(
                  operation,
                  () -> {
                    throw new CancellationException("cancelled " + operation);
                  }));
      assertEquals(0, load.inFlight(), operation + " must not leak on cancellation");
    }
    assertEquals(9, load.startedTotal());
  }

  @Test
  @DisplayName("an Error still decrements (the finally covers Throwable, not just Exception)")
  void errorStillDecrements() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    assertThrows(
        StackOverflowError.class,
        () ->
            gate.call(
                "Search",
                () -> {
                  throw new StackOverflowError("deep");
                }));
    assertEquals(0, load.inFlight());
  }

  @Test
  @DisplayName("IndexStatus and ListAllDocumentIds do not touch the gauge at all")
  void excludedOperationsDoNotTouchTheGauge() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    for (String excluded : Set.of(NOT_FOREGROUND_INDEX_STATUS, NOT_FOREGROUND_LIST_ALL)) {
      int seenDuringCall = gate.call(excluded, load::inFlight);
      assertEquals(0, seenDuringCall, excluded + " must not be counted");
      gate.run(excluded, () -> assertEquals(0, load.inFlight()));
    }
    assertEquals(0, load.inFlight());
    assertEquals(0L, load.startedTotal(), "an excluded operation must not move startedTotal");
    assertEquals(0L, load.lastForegroundAtMs(), "an excluded operation must not touch the cooldown");
  }

  @Test
  @DisplayName("the live gauge is never negative and settles at zero under concurrent use")
  void concurrentUseNeverGoesNegative() throws InterruptedException {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadGate gate = new ForegroundLoadGate(load);

    int threads = 8;
    int callsPerThread = 200;
    String[] operations = ForegroundLoadGate.foregroundOperations().toArray(new String[0]);
    AtomicInteger negativeReadings = new AtomicInteger();
    AtomicInteger zeroWhileInFlight = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int t = 0; t < threads; t++) {
        final int offset = t;
        pool.execute(
            () -> {
              try {
                start.await();
                for (int i = 0; i < callsPerThread; i++) {
                  String operation = operations[(offset + i) % operations.length];
                  boolean fail = (offset + i) % 3 == 0;
                  try {
                    gate.run(
                        operation,
                        () -> {
                          int seen = load.inFlight();
                          if (seen < 0) {
                            negativeReadings.incrementAndGet();
                          }
                          if (seen == 0) {
                            zeroWhileInFlight.incrementAndGet();
                          }
                          if (fail) {
                            throw new CancellationException("cancelled");
                          }
                        });
                  } catch (CancellationException expected) {
                    // the failing third of the calls: the gauge must still balance
                  }
                  if (load.inFlight() < 0) {
                    negativeReadings.incrementAndGet();
                  }
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "workers must finish");
    } finally {
      pool.shutdownNow();
    }

    assertEquals(0, negativeReadings.get(), "the gauge must never read negative");
    assertEquals(
        0, zeroWhileInFlight.get(), "a call must always see itself counted while it executes");
    assertEquals(0, load.inFlight(), "every increment must be balanced by exactly one decrement");
    assertEquals((long) threads * callsPerThread, load.startedTotal());
  }
}
