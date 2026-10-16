/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineShutdownSequence.Step;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec F §4.3: the quit's one deadline, cut into the QB sub-budgets the ordered close waits by. */
@DisplayName("QuitBudget")
final class QuitBudgetTest {

  @Test
  void theEngineBudgetIsTheDeadlineLessTheForceMarginLessNow() {
    var nanos = new AtomicLong(0L);
    var budget = QuitBudget.of(10_000L + 8_000L, 10_000L, nanos::get);
    assertTrue(budget.bounded());
    assertEquals(18_000L, budget.deadlineAtEpochMs());
    assertEquals(8_000L - EngineSupervisionPolicy.QUIT_FORCE_MARGIN_MS, budget.engineBudgetMs());
    assertEquals(7_700L, budget.engineBudgetMs());
    // Every wait keeps the JVM exit reserve back.
    assertEquals(7_400L, budget.remainingMs());
    nanos.set(5_000L * 1_000_000L);
    assertEquals(5_000L, budget.elapsedMs());
    assertEquals(2_400L, budget.remainingMs());
  }

  @Test
  void aWaitIsTheSmallerOfItsSubBudgetAndWhatIsLeft() {
    var nanos = new AtomicLong(0L);
    var budget = QuitBudget.of(8_000L, 0L, nanos::get);
    assertEquals(Duration.ofMillis(QuitBudget.QB3_CHAT_STOP_MS),
        budget.waitFor(Duration.ofSeconds(12), QuitBudget.QB3_CHAT_STOP_MS));
    nanos.set(6_500L * 1_000_000L);
    assertEquals(Duration.ofMillis(900L),
        budget.waitFor(Duration.ofSeconds(12), QuitBudget.QB3_CHAT_STOP_MS));
    nanos.set(9_000L * 1_000_000L);
    assertEquals(Duration.ZERO, budget.waitFor(Duration.ofSeconds(12), QuitBudget.QB3_CHAT_STOP_MS));
  }

  @Test
  void aDeadlineAlreadyPastLeavesNothingToWait() {
    var budget = QuitBudget.of(1_000L, 5_000L, () -> 0L);
    assertEquals(0L, budget.engineBudgetMs());
    assertEquals(0L, budget.remainingMs());
    assertEquals(Duration.ZERO, budget.waitFor(Duration.ofSeconds(5), QuitBudget.QB2_COOPERATIVE_MS));
  }

  @Test
  void extremeDeadlinesDoNotOverflow() {
    assertEquals(Long.MAX_VALUE, QuitBudget.of(Long.MAX_VALUE, Long.MIN_VALUE, () -> 0L).engineBudgetMs());
    assertEquals(0L, QuitBudget.of(Long.MIN_VALUE, Long.MAX_VALUE, () -> 0L).engineBudgetMs());
  }

  @Test
  void unboundedKeepsTodaysWaits() {
    var unbounded = QuitBudget.unbounded();
    assertFalse(unbounded.bounded());
    assertEquals(Duration.ofSeconds(12), unbounded.waitFor(Duration.ofSeconds(12), QuitBudget.QB3_CHAT_STOP_MS));
    assertEquals(Long.MAX_VALUE, unbounded.remainingMs());
  }

  @Test
  void aQuitWithoutADeadlineGetsTheContractDeadlineFromNow() {
    var budget = QuitBudget.fromNow();
    assertTrue(budget.bounded());
    long expected = EngineSupervisionPolicy.QUIT_DEADLINE_MS - EngineSupervisionPolicy.QUIT_FORCE_MARGIN_MS;
    assertTrue(budget.engineBudgetMs() <= expected && budget.engineBudgetMs() >= expected - 1_000L,
        budget.toString());
  }

  @Test
  void theSubBudgetsFitTheEngineBudget() {
    // QB3 runs beside QB2 from QB1; QB4 and QB5 are serial after QB2.
    long serial = QuitBudget.QB1_ADMISSION_MS + QuitBudget.QB2_COOPERATIVE_MS
        + QuitBudget.QB2_CANCEL_ACK_MS + QuitBudget.QB4_EXTRACTION_MS + QuitBudget.QB5_LEDGER_MS
        + QuitBudget.QB_JVM_EXIT_RESERVE_MS;
    assertTrue(serial <= EngineSupervisionPolicy.QUIT_DEADLINE_MS - EngineSupervisionPolicy.QUIT_FORCE_MARGIN_MS);
    assertTrue(QuitBudget.QB1_ADMISSION_MS + QuitBudget.QB3_CHAT_STOP_MS
        <= QuitBudget.QB1_ADMISSION_MS + QuitBudget.QB2_COOPERATIVE_MS + QuitBudget.QB2_CANCEL_ACK_MS);
  }

  @Test
  void onlyAQuitHandsItsBudgetToTheSteps(@TempDir Path dataDir) {
    var seen = new ArrayList<QuitBudget>();
    var plain = new AtomicInteger();
    var quitBudget = QuitBudget.of(System.currentTimeMillis() + 8_000L);
    List<Step> steps = List.of(
        new Step("budgeted", (EngineShutdownSequence.BudgetedStepAction) (reason, budget) -> {
          seen.add(budget);
          return null;
        }),
        new Step("plain", reason -> {
          plain.incrementAndGet();
          return null;
        }));

    var quit = new EngineShutdownSequence(dataDir.resolve("quit"), steps, ignored -> {});
    assertTrue(quit.run(Reason.QUIT, quitBudget).clean());
    assertSame(quitBudget, seen.get(0));

    var restart = new EngineShutdownSequence(dataDir.resolve("restart"), steps, ignored -> {});
    assertTrue(restart.run(Reason.RESTART, quitBudget).clean());
    assertSame(QuitBudget.unbounded(), seen.get(1), "a restart keeps today's waits");

    var legacy = new EngineShutdownSequence(dataDir.resolve("legacy"), steps, ignored -> {});
    assertTrue(legacy.run(Reason.QUIT).clean());
    assertSame(QuitBudget.unbounded(), seen.get(2), "a quit that names no budget keeps today's waits");
    assertEquals(3, plain.get(), "a step that takes no budget runs as before");
  }

  @Test
  void aBudgetedQuitExitsThroughTheSameRunOnceSequence(@TempDir Path dataDir) {
    var exitCode = new AtomicInteger(-1);
    var runs = new AtomicInteger();
    var sequence = new EngineShutdownSequence(dataDir, List.of(new Step("only",
        (EngineShutdownSequence.BudgetedStepAction) (reason, budget) -> {
          runs.incrementAndGet();
          assertTrue(budget.bounded());
          return null;
        })), exitCode::set);
    sequence.runAndExit(Reason.QUIT, QuitBudget.of(System.currentTimeMillis() + 8_000L));
    assertEquals(0, exitCode.get());
    sequence.runAndExit(Reason.QUIT);
    assertEquals(1, runs.get(), "the second quit trigger joins the first close");
  }
}
