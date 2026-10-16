/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.runtime.RuntimeManifest.IncarnationInfo;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.QuitBudget;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.ui.api.LifecycleShutdownRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec F §4.3: how the quit's budget is cut for producers, live work, the chat stop and the POST. */
@DisplayName("HeadlessApp quit windows")
final class HeadlessAppQuitWindowTest {

  @Test
  void recordedProducersGetTheCooperativeWindowInAQuitAndTheirOwnDeadlineOtherwise(
      @TempDir Path dataDir) {
    var drains = new ArrayList<Duration>();
    var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
    when(attempts.awaitDrained(any(Duration.class))).thenReturn(true);
    var admission = mock(EngineAdmissionService.class);
    when(admission.awaitDrained(any(Duration.class))).thenReturn(true);
    List<EngineShutdownSequence.Step> steps = HeadlessApp.orderedShutdownSteps(null, null, null,
        null, null, null, null, mock(AppInstanceLock.class), mock(OperationLeaseService.class),
        admission, mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
        mock(io.justsearch.app.api.operations.OperationStore.class), attempts, drains::add);

    new EngineShutdownSequence(dataDir.resolve("quit"), steps, ignored -> {})
        .run(Reason.QUIT, QuitBudget.of(System.currentTimeMillis() + 8_000L));
    assertTrue(drains.get(0).toMillis() <= QuitBudget.QB2_COOPERATIVE_MS && drains.get(0).toMillis() > 0,
        "the producers get QB2, not " + drains.get(0));

    List<EngineShutdownSequence.Step> restartSteps = HeadlessApp.orderedShutdownSteps(null, null,
        null, null, null, null, null, mock(AppInstanceLock.class), mock(OperationLeaseService.class),
        admission, mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
        mock(io.justsearch.app.api.operations.OperationStore.class), attempts, drains::add);
    new EngineShutdownSequence(dataDir.resolve("restart"), restartSteps, ignored -> {})
        .run(Reason.RESTART);
    assertNull(drains.get(1), "a restart keeps the producers' own deadline");
  }

  @Test
  void liveWorkSharesOneWindowCountedFromTheProducerDrain() {
    assertEquals(Duration.ofSeconds(5), HeadlessApp.liveWorkWait(QuitBudget.unbounded(), new AtomicLong(-1)));
    var budget = QuitBudget.of(System.currentTimeMillis() + 8_000L);
    var start = new AtomicLong(budget.elapsedMs());
    long window = QuitBudget.QB2_COOPERATIVE_MS + QuitBudget.QB2_CANCEL_ACK_MS;
    assertTrue(HeadlessApp.liveWorkWait(budget, start).toMillis() <= window);
    // A window that began long ago has nothing left.
    assertEquals(Duration.ZERO, HeadlessApp.liveWorkWait(budget, new AtomicLong(budget.elapsedMs() - window)));
    var unset = new AtomicLong(-1);
    HeadlessApp.liveWorkWait(budget, unset);
    assertTrue(unset.get() >= 0, "the first drain wait opens the window when no producer drain did");
  }

  @Test
  void aLifecycleQuitWithoutADeadlineGetsTheContractDeadline() {
    var named = HeadlessApp.lifecycleQuitBudget(
        new LifecycleShutdownRequest(System.currentTimeMillis() + 5_000L, "run-q/2", "quit"),
        new IncarnationInfo("run-q", 2));
    assertTrue(named.bounded());
    assertTrue(named.engineBudgetMs() <= 4_700L);
    var none = HeadlessApp.lifecycleQuitBudget(LifecycleShutdownRequest.NONE, null);
    assertTrue(none.bounded());
    assertTrue(none.engineBudgetMs() > 4_700L && none.engineBudgetMs() <= 7_700L, none.toString());
    // Another incarnation's POST still quits: the session token already names this Engine's owner.
    assertTrue(HeadlessApp.lifecycleQuitBudget(
        new LifecycleShutdownRequest(null, "run-q/1", null), new IncarnationInfo("run-q", 2)).bounded());
  }

  @Test
  void anExhaustedChatStopStillAsksForOneMillisecondAndReportsFailures() throws Exception {
    var budgets = new ArrayList<Duration>();
    HeadlessApp.startTerminalChatStop((reason, budget) -> budgets.add(budget), Duration.ZERO)
        .get(2, TimeUnit.SECONDS);
    assertEquals(List.of(Duration.ofMillis(1)), budgets);
    var failed = HeadlessApp.startTerminalChatStop((reason, budget) -> {
      throw new IllegalStateException("injected");
    }, Duration.ofSeconds(1));
    assertTrue(failed.handle((ok, error) -> error != null).get(2, TimeUnit.SECONDS));
    // Awaiting a failed or absent stop never throws into the ordered close.
    HeadlessApp.awaitTerminalChatStop(failed, QuitBudget.of(System.currentTimeMillis() + 8_000L));
    HeadlessApp.awaitTerminalChatStop(null, QuitBudget.unbounded());
    assertFalse(HeadlessApp.terminalChatStopOf(null) != null);
  }
}
