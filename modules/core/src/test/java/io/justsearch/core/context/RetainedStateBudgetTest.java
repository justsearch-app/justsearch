/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RetainedStateBudgetTest {
  @Test
  void concurrentOwnersCannotExceedTheCap() throws Exception {
    var budget = new RetainedStateBudget();
    budget.declare("fixture-resource", 3, "test");
    budget.activate("fixture-resource");
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(12)) {
      var attempts = new java.util.ArrayList<java.util.concurrent.Future<java.util.Optional<RetainedStateBudget.Permit>>>();
      for (int i = 0; i < 12; i++) {
        attempts.add(executor.submit(() -> {
          start.await();
          return budget.tryAcquire("fixture-resource");
        }));
      }
      start.countDown();
      var permits = new java.util.ArrayList<RetainedStateBudget.Permit>();
      for (var attempt : attempts) attempt.get(5, java.util.concurrent.TimeUnit.SECONDS).ifPresent(permits::add);
      assertEquals(3, permits.size());
      assertEquals(3, budget.snapshot().getFirst().count());
      permits.forEach(RetainedStateBudget.Permit::close);
      assertEquals(0, budget.snapshot().getFirst().count());
    }
  }

  @Test
  void futureProducerIsNotPresentedAsAZeroLiveCount() {
    var budget = new RetainedStateBudget();
    budget.declare("fixture-resource", 1, "D1");
    assertNull(budget.snapshot().getFirst().count());
    assertEquals("D1", budget.snapshot().getFirst().awaitingProducer());
    assertThrows(IllegalStateException.class, () -> budget.tryAcquire("fixture-resource"));
    assertThrows(IllegalArgumentException.class, () -> budget.declare("fixture-resource", 2, "D1"));
  }

  @Test
  void syntheticOwnerRefusesAtCapAndReleasesExactlyOnce() {
    var budget = new RetainedStateBudget();
    budget.declare("fixture-resource", 1, "test");
    budget.activate("fixture-resource");
    var permit = budget.tryAcquire("fixture-resource").orElseThrow();
    assertTrue(budget.tryAcquire("fixture-resource").isEmpty());
    assertEquals(1, budget.snapshot().getFirst().count());
    permit.close();
    var replacement = budget.tryAcquire("fixture-resource").orElseThrow();
    permit.close();
    assertEquals(1, budget.snapshot().getFirst().count());
    replacement.close();
    assertEquals(0, budget.snapshot().getFirst().count());
    assertNull(budget.snapshot().getFirst().awaitingProducer());
    assertThrows(IllegalStateException.class, () -> budget.activate("fixture-resource"));
    assertThrows(IllegalArgumentException.class, () -> budget.tryAcquire("undeclared"));
  }
}
