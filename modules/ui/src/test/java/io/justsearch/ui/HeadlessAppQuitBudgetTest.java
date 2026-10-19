/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.util.AppInstanceLock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Spec F §4.3 (S16): a quit closes within its own deadline. The supervisor forces at the deadline
 * minus its margin, so every wait the ordered close makes for a quit comes out of the Engine budget
 * (deadline − 300 ms − now): the live-work drain gets the cooperative window (QB2) and the chat
 * server's stop runs beside it from the moment interactive work is refused (QB1, QB3), through the
 * chat controller's terminal stop.
 *
 * <p>Driven through the production dispatcher with a host request file's shape, so this class
 * compiles against the before-state, where the same quit waits 5 s + 5 s + 12 s.
 */
@DisplayName("HeadlessApp quit budget")
final class HeadlessAppQuitBudgetTest {

  /** The Engine budget of a quit with the contract's 8 s deadline. */
  private static final long ENGINE_BUDGET_MS = 8_000L - 300L;

  @Test
  void aQuitWhoseWorkNeverDrainsStillEndsWithinTheEngineBudget(@TempDir Path dataDir) {
    var admission = mock(EngineAdmissionService.class);
    var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
    var head = mock(HeadAssembly.class);
    var chatBudgets = new CopyOnWriteArrayList<Duration>();
    // Work that never relinquishes, and a chat server that never stops: each waits its full time.
    when(admission.awaitDrained(any(Duration.class))).thenAnswer(call -> sleep(call.getArgument(0)));
    when(attempts.awaitDrained(any(Duration.class))).thenAnswer(call -> sleep(call.getArgument(0)));
    doAnswer(call -> {
      chatBudgets.add(call.getArgument(0));
      sleep(call.getArgument(0));
      return null;
    }).when(head).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
    var exitCode = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dataDir, steps(head, admission, attempts), exitCode::set);

    long started = System.nanoTime();
    HeadlessApp.shutdownRequestDispatcher(sequence).accept(quitWithin(8_000L));
    long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertTrue(tookMs < ENGINE_BUDGET_MS,
        "the quit took " + tookMs + " ms; the supervisor forces at " + ENGINE_BUDGET_MS + " ms");
    // S16-D1 as ruled (D1 design): work that did not drain is abandoned to its durable replay, and
    // the close reports that honestly. The steps that need the drain refuse, so the Engine ends
    // itself unclean, FATAL_OR_UNCAUGHT, never 0 with live work behind it.
    assertEquals(io.justsearch.app.engine.EngineExit.FATAL_OR_UNCAUGHT, exitCode.get(),
        "the Engine exited on its own, before the force, and reported the undrained close");
    assertEquals(1, chatBudgets.size(), "the chat server is stopped exactly once");
    assertTrue(chatBudgets.get(0).toMillis() <= 2_000L,
        "the chat stop gets QB3 (2 s), not " + chatBudgets.get(0));
  }

  @Test
  void theChatServerStopsBesideTheDrainThroughTheTerminalStop(@TempDir Path dataDir) {
    var admission = mock(EngineAdmissionService.class);
    var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
    var head = mock(HeadAssembly.class);
    var chatStopEntered = new CountDownLatch(1);
    when(admission.awaitDrained(any(Duration.class))).thenReturn(true);
    // The operation body yields only once the chat server is being stopped: the stop must not wait
    // for the drain to end.
    when(attempts.awaitDrained(any(Duration.class))).thenAnswer(call ->
        chatStopEntered.await(((Duration) call.getArgument(0)).toMillis(), TimeUnit.MILLISECONDS));
    doAnswer(call -> {
      chatStopEntered.countDown();
      return null;
    }).when(head).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
    var exitCode = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dataDir, steps(head, admission, attempts), exitCode::set);

    HeadlessApp.shutdownRequestDispatcher(sequence).accept(quitWithin(8_000L));

    assertEquals(0, exitCode.get(), "the chat stop ran while the drain waited, so the quit is clean");
    var order = Mockito.inOrder(attempts, head);
    order.verify(attempts, Mockito.calls(1)).beginClosing();
    // Recorded as the Engine's quit before the stop, so the child ledger reads REQUESTED(QUIT).
    order.verify(head, Mockito.calls(1)).recordTerminalStop(Mockito.argThat((StopRequest stop) ->
        stop != null && stop.reason() == StopReason.QUIT));
    order.verify(head, Mockito.calls(1)).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
    Mockito.verify(head, Mockito.times(1)).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
    Mockito.verify(head).close();
  }

  @Test
  void aRestartKeepsTodaysWaits(@TempDir Path dataDir) {
    var admission = mock(EngineAdmissionService.class);
    var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
    var head = mock(HeadAssembly.class);
    when(admission.awaitDrained(any(Duration.class))).thenReturn(false);
    when(attempts.awaitDrained(any(Duration.class))).thenReturn(false);
    var exitCode = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dataDir, steps(head, admission, attempts), exitCode::set);

    HeadlessApp.shutdownRequestDispatcher(sequence).accept(new ShutdownRequest(Reason.RESTART,
        System.currentTimeMillis() + 8_000L, null, "supervisor", null));

    Mockito.verify(admission).awaitDrained(Duration.ofSeconds(5));
    Mockito.verify(attempts).awaitDrained(Duration.ofSeconds(5));
    Mockito.verify(head).stopGenerativeBackendForTerminalShutdown(Duration.ofSeconds(12));
  }

  private static List<EngineShutdownSequence.Step> steps(HeadAssembly head,
      EngineAdmissionService admission, io.justsearch.app.api.operations.OperationAttemptRunner attempts) {
    return HeadlessApp.orderedShutdownSteps(null, head, null, null, null, null, null,
        mock(AppInstanceLock.class), mock(OperationLeaseService.class), admission,
        mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
        mock(io.justsearch.app.api.operations.OperationStore.class), attempts, null);
  }

  private static ShutdownRequest quitWithin(long deadlineMs) {
    return new ShutdownRequest(Reason.QUIT, System.currentTimeMillis() + deadlineMs, null,
        "supervisor", null);
  }

  private static boolean sleep(Duration wait) throws InterruptedException {
    Thread.sleep(Math.max(0L, wait.toMillis()));
    return false;
  }
}
