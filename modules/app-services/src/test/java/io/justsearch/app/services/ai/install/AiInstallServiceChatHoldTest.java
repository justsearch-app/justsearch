/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Hold;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.inference.chat.ChatHolds;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 6, row 10: the post-install smoke test takes a running hold on the chat controller
 * instead of switching the engine itself, and releases it on every exit, so chat returns to the
 * user's intent exactly once.
 */
@Timeout(20)
final class AiInstallServiceChatHoldTest {

  @TempDir Path tmp;

  /** Answers the smoke-test question with {@code reply}; counts direct mode switches. */
  private static final class AnsweringAi implements OnlineAiService {
    final CompletableFuture<String> answer = new CompletableFuture<>();
    final AtomicInteger directSwitches = new AtomicInteger();

    @Override
    public CompletableFuture<String> summarize(String content) {
      return answer;
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return answer;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public void switchToOnlineMode() {
      directSwitches.incrementAndGet();
    }
  }

  @Test
  void aPassingSmokeTestRunsInsideARunningHoldAndReleasesIt() throws Exception {
    var ai = new AnsweringAi();
    ai.answer.complete("OK");
    ChatHolds holds = mock(ChatHolds.class);
    Hold hold = mock(Hold.class);
    when(holds.holdRunning("post-install-smoke-test")).thenReturn(hold);

    assertTrue(runSmokeTest(new AiInstallService(ai, null, null, null, tmp, holds)));

    verify(holds).holdRunning("post-install-smoke-test");
    verify(hold, times(1)).close();
    assertEquals(0, ai.directSwitches.get(), "the smoke test no longer switches the engine itself");
  }

  @Test
  void aFailingOrCancelledSmokeTestStillReleasesItsHold() throws Exception {
    var empty = new AnsweringAi();
    empty.answer.complete(" ");
    ChatHolds holds = mock(ChatHolds.class);
    Hold hold = mock(Hold.class);
    when(holds.holdRunning("post-install-smoke-test")).thenReturn(hold);
    var failing = new AiInstallService(empty, null, null, null, tmp.resolve("a"), holds);
    assertFalse(runSmokeTest(failing));
    assertEquals("SMOKE_TEST_FAILED", statusOf(failing).errorCode);
    verify(hold, times(1)).close();

    var silent = new AnsweringAi();
    var cancelled = new AiInstallService(silent, null, null, null, tmp.resolve("b"), holds);
    cancelled.cancel();
    assertFalse(runSmokeTest(cancelled));
    assertEquals("cancelled", statusOf(cancelled).state);
    assertTrue(silent.answer.isCancelled());
    verify(hold, times(2)).close();
  }

  @Test
  void aRefusedHoldFailsTheSmokeTestWithTheReasonAndAsksNothing() throws Exception {
    var ai = new AnsweringAi();
    ChatHolds holds = mock(ChatHolds.class);
    when(holds.holdRunning("post-install-smoke-test"))
        .thenThrow(new ChatRefusal(RefusalReason.FAILED, Optional.empty(), false));
    var svc = new AiInstallService(ai, null, null, null, tmp, holds);

    assertFalse(runSmokeTest(svc));

    AiInstallStatus status = statusOf(svc);
    assertEquals("SMOKE_TEST_FAILED", status.errorCode);
    assertTrue(status.lastError.contains("FAILED"), status.lastError);
    assertFalse(ai.answer.isDone(), "no question is asked of a server that was not started");
    assertEquals(0, ai.directSwitches.get());
    verify(holds, never()).freezeConvergence(org.mockito.ArgumentMatchers.anyString());
  }

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static boolean runSmokeTest(AiInstallService svc) throws Exception {
    Method m = AiInstallService.class.getDeclaredMethod("smokeTestBestEffort");
    m.setAccessible(true);
    return (Boolean) m.invoke(svc);
  }
}
