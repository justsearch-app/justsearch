/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.HeadSearchDeadline;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Spec D §1.2: a search call's budget is {@code min(category budget, D - now)} inside the head's
 * deadline {@code D}, and the category budget unchanged outside one.
 */
final class EngineKnowledgeClientHeadBudgetTest {

  @Test
  void withoutAHeadDeadlineTheCategoryBudgetApplies() {
    assertEquals(15_000L, EngineKnowledgeClient.headBounded(15_000L));
  }

  @Test
  void insideAHeadDeadlineTheBudgetIsWhatRemainsOfIt() {
    long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000);
    long budget = HeadSearchDeadline.within(d, () -> EngineKnowledgeClient.headBounded(15_000L));
    assertTrue(budget > 0 && budget <= 2_000L, "min(15 000, D - now): " + budget);
  }

  @Test
  void aCategoryBudgetShorterThanTheRemainderIsKept() {
    long d = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    assertEquals(200L, HeadSearchDeadline.within(d, () -> EngineKnowledgeClient.headBounded(200L)));
  }

  @Test
  void aPassedHeadDeadlineLeavesTheSmallestBudget() {
    long d = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
    assertEquals(1L, HeadSearchDeadline.within(d, () -> EngineKnowledgeClient.headBounded(15_000L)));
  }
}
