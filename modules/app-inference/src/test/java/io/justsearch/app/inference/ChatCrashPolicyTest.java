/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.inference.ChatCrashPolicy.Action;
import io.justsearch.app.inference.ChatCrashPolicy.Decision;
import io.justsearch.app.inference.LlamaServerExitClassifier.Classification;
import io.justsearch.app.inference.LlamaServerExitClassifier.CrashClass;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Spec A §9.2 U9 and U10 as a decision table (§3.2 T6-T10 and T27-T29): RESOURCE steps down one rung
 * and never below an override; OTHER while starting fails at once; a crash while READY earns one
 * same-configuration restart only after ten minutes of READY, and only once.
 */
final class ChatCrashPolicyTest {

  private static final Duration JUST_UNDER = Duration.ofMinutes(10).minusMillis(1);
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

  private static Classification of(CrashClass crashClass, FailedCode code) {
    return new Classification(crashClass, code, "fixture");
  }

  private static final Classification RESOURCE = of(CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED);
  private static final Classification OTHER = of(CrashClass.OTHER, FailedCode.START_FAILED);
  private static final Classification CONFIG = of(CrashClass.CONFIG, FailedCode.MODEL_LOAD_FAILED);
  private static final Classification DLL = of(CrashClass.CONFIG, FailedCode.MISSING_DLL);
  private static final Classification DEVICE = of(CrashClass.DEVICE_SUSPECT, FailedCode.START_FAILED);
  private static final Classification PORT = of(CrashClass.PORT_LOST, FailedCode.START_FAILED);

  private static void assertDecision(Action action, FailedCode code, String rule, Decision actual) {
    assertEquals(action, actual.action(), rule);
    assertEquals(code, actual.failedCode(), rule);
    assertEquals(rule, actual.rule());
  }

  @Test
  void startingResourceStepsDownWhileARungIsLeftAndFailsLoudOtherwise() {
    // U9 / T6: a derived window with a rung below steps down.
    assertDecision(Action.STEP_DOWN, null, "T6",
        ChatCrashPolicy.afterStartExit(RESOURCE, true, false));
    // T7: an override (no lower rung offered) or the last rung is resource_exhausted, no step-down.
    assertDecision(Action.FAIL, FailedCode.RESOURCE_EXHAUSTED, "T7",
        ChatCrashPolicy.afterStartExit(RESOURCE, false, false));
  }

  @Test
  void startingNonResourceExitsFailAtOnceWithTheirCode() {
    // U10 / T10: OTHER in STARTING fails at once, even with rungs left.
    assertDecision(Action.FAIL, FailedCode.START_FAILED, "T10",
        ChatCrashPolicy.afterStartExit(OTHER, true, false));
    // T9: CONFIG fails with its own code, no ladder.
    assertDecision(Action.FAIL, FailedCode.MODEL_LOAD_FAILED, "T9",
        ChatCrashPolicy.afterStartExit(CONFIG, true, false));
    assertDecision(Action.FAIL, FailedCode.MISSING_DLL, "T9",
        ChatCrashPolicy.afterStartExit(DLL, true, false));
    // §7.1: a suspect device is never retried in place.
    assertDecision(Action.FAIL, FailedCode.START_FAILED, "7.1 DEVICE_SUSPECT",
        ChatCrashPolicy.afterStartExit(DEVICE, true, false));
  }

  @Test
  void aLostBindIsRelaunchedOnceAndNeverStepsDown() {
    assertDecision(Action.RESTART_SAME, null, "CH3",
        ChatCrashPolicy.afterStartExit(PORT, true, false));
    assertDecision(Action.FAIL, FailedCode.START_FAILED, "CH3",
        ChatCrashPolicy.afterStartExit(PORT, true, true));
  }

  @Test
  void aReadyCrashRestartsOnceOnlyAfterTheStabilityWindow() {
    // U10 / T29: READY under ten minutes is a crash loop.
    assertDecision(Action.FAIL, FailedCode.CRASH_LOOP, "T29",
        ChatCrashPolicy.afterReadyFailure(OTHER, JUST_UNDER, false, true));
    assertDecision(Action.FAIL, FailedCode.CRASH_LOOP, "T29",
        ChatCrashPolicy.afterReadyFailure(OTHER, Duration.ZERO, false, true));
    // T28: at ten minutes, one same-configuration restart.
    assertDecision(Action.RESTART_SAME, null, "T28",
        ChatCrashPolicy.afterReadyFailure(OTHER, TEN_MINUTES, false, true));
    // ... and only one: the next crash is a crash loop however long it served.
    assertDecision(Action.FAIL, FailedCode.CRASH_LOOP, "T29",
        ChatCrashPolicy.afterReadyFailure(OTHER, Duration.ofHours(5), true, true));
  }

  @Test
  void aReadyResourceExitStepsDownAndAnOverrideFails() {
    // T27, whatever the READY time and even after the T28 restart was spent.
    assertDecision(Action.STEP_DOWN, null, "T27",
        ChatCrashPolicy.afterReadyFailure(RESOURCE, Duration.ZERO, true, true));
    assertDecision(Action.FAIL, FailedCode.RESOURCE_EXHAUSTED, "T27",
        ChatCrashPolicy.afterReadyFailure(RESOURCE, TEN_MINUTES, false, false));
    // A suspect device is never restarted in place, even after a stable window.
    assertDecision(Action.FAIL, FailedCode.CRASH_LOOP, "7.1 DEVICE_SUSPECT",
        ChatCrashPolicy.afterReadyFailure(DEVICE, TEN_MINUTES, false, true));
  }

  @Test
  void theStabilityWindowIsTheDeclaredTenMinutes() {
    assertEquals(Duration.ofMinutes(10), ChatCrashPolicy.STABILITY_WINDOW);
    assertNull(ChatCrashPolicy.afterReadyFailure(OTHER, TEN_MINUTES, false, false).failedCode());
  }
}
