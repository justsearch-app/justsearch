/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.RetentionClass;
import io.justsearch.app.api.runtime.child.StopDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/*
 * Spec A §9.2 U15 (the reason-code half): every chat state and reason maps to one lifecycle reason
 * code (Spec C R2), and the classified failures are told apart.
 */
final class ChatReasonCodesTest {

  private static final Instant SINCE = Instant.parse("2026-10-06T08:00:00Z");

  /* Total: every state with every reason its slot allows has an answer; only READY has none. */
  @Test
  void everyStateAndReasonMapsToOneLifecycleCode() {
    for (ChatStatus status : everyStatus()) {
      Optional<LifecycleReasonCode> code = ChatReasonCodes.toLifecycle(status);
      assertEquals(status.state() != ChatState.READY, code.isPresent(), status.toString());
    }
  }

  /* The observable mapping of Spec A §3.1 for the states with a fixed code. */
  @Test
  void statesMapToTheirLifecycleCodes() {
    assertEquals(Optional.of(LifecycleReasonCode.INFERENCE_DEACTIVATED),
        ChatReasonCodes.toLifecycle(status(ChatState.OFF)));
    assertEquals(Optional.of(LifecycleReasonCode.INFERENCE_STARTING),
        ChatReasonCodes.toLifecycle(loading(LoadingCause.BOOT_ORDER)));
    assertEquals(Optional.empty(), ChatReasonCodes.toLifecycle(status(ChatState.READY)));
    assertEquals(Optional.of(LifecycleReasonCode.INFERENCE_OUT_OF_MEMORY),
        ChatReasonCodes.toLifecycle(failed(FailedCode.RESOURCE_EXHAUSTED)));
    assertEquals(Optional.of(LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED),
        ChatReasonCodes.toLifecycle(refused(RefusedCode.EXECUTABLE_MISSING)));
  }

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

  private static List<ChatStatus> everyStatus() {
    List<ChatStatus> all = new ArrayList<>();
    all.add(status(ChatState.OFF));
    all.add(status(ChatState.READY));
    for (LoadingCause cause : LoadingCause.values()) all.add(loading(cause));
    for (UnloadReason reason : UnloadReason.values()) all.add(unloaded(reason));
    for (RefusedCode code : RefusedCode.values()) all.add(refused(code));
    for (FailedCode code : FailedCode.values()) all.add(failed(code));
    all.add(new ChatStatus(ChatState.REFUSED, Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0));
    all.add(new ChatStatus(ChatState.FAILED, Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0));
    return all;
  }

  private static ChatStatus status(ChatState state) {
    return new ChatStatus(state, Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0);
  }

  private static ChatStatus loading(LoadingCause cause) {
    return new ChatStatus(ChatState.LOADING, Optional.of(cause), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true, false,
        0, 0, Optional.empty(), 0);
  }

  private static ChatStatus unloaded(UnloadReason reason) {
    Optional<StopDetail> detail = reason == UnloadReason.RESIDENCY_PRESSURE
        ? Optional.of(StopDetail.GPU_BUSY) : Optional.empty();
    return new ChatStatus(ChatState.UNLOADED, Optional.empty(), Optional.of(reason),
        Optional.empty(), Optional.empty(), detail, Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0);
  }

  private static ChatStatus refused(RefusedCode code) {
    return new ChatStatus(ChatState.REFUSED, Optional.empty(), Optional.empty(), Optional.of(code),
        Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0);
  }

  private static ChatStatus failed(FailedCode code) {
    return new ChatStatus(ChatState.FAILED, Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.of(code), Optional.empty(), Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0);
  }
}
