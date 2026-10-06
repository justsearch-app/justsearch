/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.RetentionClass;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/*
 * Spec A §9.2 U15 (the reason-code half): every classified chat failure maps to one lifecycle
 * reason code, the failures are told apart, and a stepped-down server carries its notice.
 */
final class ChatReasonCodesTest {

  /* Each classified failure (Spec A §7.1) reads as itself; the rest keep crashed. */
  @Test
  void classifiedFailuresHaveTheirOwnCodes() {
    Map<FailedCode, String> expected = Map.of(
        FailedCode.RESOURCE_EXHAUSTED, "inference.out_of_memory",
        FailedCode.STARTUP_TIMEOUT, "inference.startup_timeout",
        FailedCode.START_FAILED, "inference.start_failed",
        FailedCode.MODEL_LOAD_FAILED, "inference.model_load_failed",
        FailedCode.MISSING_DLL, "inference.runtime_not_installed",
        FailedCode.CRASH_LOOP, "inference.crashed",
        FailedCode.STOP_FAILED, "inference.crashed",
        FailedCode.EXTERNAL_UNHEALTHY, "inference.crashed",
        FailedCode.DEVICE_LOST_LIMIT, "inference.crashed");
    assertEquals(FailedCode.values().length, expected.size());
    for (FailedCode code : FailedCode.values()) {
      LifecycleReasonCode mapped = ChatReasonCodes.forFailure(code);
      assertEquals(expected.get(code), mapped.code(), code.name());
      // A failure is a held cause: a later generic write must not erase it.
      assertEquals(RetentionClass.FAULT, mapped.retentionClass(), code.name());
    }
  }

  /* The code names the failure back only when exactly one failure produces it. */
  @Test
  void aCodeNamesItsFailureOnlyWhenUnshared() {
    assertEquals(Optional.of(FailedCode.RESOURCE_EXHAUSTED),
        ChatReasonCodes.failedCodeOf("inference.out_of_memory"));
    assertEquals(Optional.of(FailedCode.STARTUP_TIMEOUT),
        ChatReasonCodes.failedCodeOf("inference.startup_timeout"));
    assertEquals(Optional.of(FailedCode.START_FAILED),
        ChatReasonCodes.failedCodeOf("inference.start_failed"));
    assertEquals(Optional.of(FailedCode.MODEL_LOAD_FAILED),
        ChatReasonCodes.failedCodeOf("inference.model_load_failed"));
    assertEquals(Optional.of(FailedCode.MISSING_DLL),
        ChatReasonCodes.failedCodeOf("inference.runtime_not_installed"));
    assertEquals(Optional.empty(), ChatReasonCodes.failedCodeOf("inference.crashed"));
    assertEquals(Optional.empty(), ChatReasonCodes.failedCodeOf("inference.offline"));
    assertEquals(Optional.empty(), ChatReasonCodes.failedCodeOf(null));
  }

  /* Spec A §7.2: only a stepped-down window carries the context_reduced notice, as a TRANSIENT. */
  @Test
  void onlyASteppedDownWindowCarriesTheContextReducedNotice() {
    assertEquals(Optional.of(LifecycleReasonCode.INFERENCE_CONTEXT_REDUCED),
        ChatReasonCodes.readyNotice("stepped-from:8192"));
    assertEquals(Optional.empty(), ChatReasonCodes.readyNotice("top-rung"));
    assertEquals(Optional.empty(), ChatReasonCodes.readyNotice("override"));
    assertEquals(Optional.empty(), ChatReasonCodes.readyNotice(null));
    assertEquals(RetentionClass.TRANSIENT,
        LifecycleReasonCode.INFERENCE_CONTEXT_REDUCED.retentionClass());
    assertTrue(LifecycleReasonCode.allowedCodes().contains("inference.context_reduced"));
  }
}
