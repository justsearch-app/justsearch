/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.runtime.child.StopDetail;
import java.util.Objects;
import java.util.Optional;

/**
 * The one mapping from a chat failure, or a serving server's notice, to the lifecycle reason code
 * (V1) that {@code components.generative.reasonCode} carries (Spec A step 7), so no projection
 * invents its own string.
 */
public final class ChatReasonCodes {

  /** The context-window reason a stepped-down launch records ({@code stepped-from:<rung>}). */
  public static final String STEPPED_FROM_PREFIX = "stepped-from:";

  private ChatReasonCodes() {}

  /** The code that tells the user why chat failed (Spec A §7.1 classification). */
  public static LifecycleReasonCode forFailure(FailedCode code) {
    Objects.requireNonNull(code, "code");
    return switch (code) {
      case RESOURCE_EXHAUSTED -> LifecycleReasonCode.INFERENCE_OUT_OF_MEMORY;
      case STARTUP_TIMEOUT -> LifecycleReasonCode.INFERENCE_STARTUP_TIMEOUT;
      case START_FAILED -> LifecycleReasonCode.INFERENCE_START_FAILED;
      case MODEL_LOAD_FAILED -> LifecycleReasonCode.INFERENCE_MODEL_LOAD_FAILED;
      case MISSING_DLL -> LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED;
      case CRASH_LOOP, STOP_FAILED, EXTERNAL_UNHEALTHY, DEVICE_LOST_LIMIT ->
          LifecycleReasonCode.INFERENCE_CRASHED;
    };
  }

  /**
   * The failure a reason code names, when exactly one {@link FailedCode} produces it. Shared codes
   * ({@code inference.crashed}, {@code inference.runtime_not_installed}) name no single failure.
   */
  public static Optional<FailedCode> failedCodeOf(String reasonCode) {
    if (reasonCode == null) return Optional.empty();
    for (FailedCode code : FailedCode.values()) {
      String mapped = forFailure(code).code();
      if (!mapped.equals(reasonCode)) continue;
      for (FailedCode other : FailedCode.values()) {
        if (other != code && forFailure(other).code().equals(mapped)) return Optional.empty();
      }
      return Optional.of(code);
    }
    return Optional.empty();
  }

  /**
   * The code of a chat unloaded for another GPU user (Spec A T21, UNLOADED RESIDENCY_PRESSURE), by
   * its detail.
   *
   * @throws IllegalArgumentException for a detail that is not a residency detail
   */
  public static LifecycleReasonCode forResidencyUnload(StopDetail detail) {
    Objects.requireNonNull(detail, "detail");
    return switch (detail) {
      case GPU_BUSY -> LifecycleReasonCode.INFERENCE_UNLOADED_GPU_PRESSURE;
      case GAME_MODE -> LifecycleReasonCode.INFERENCE_UNLOADED_GAME_MODE;
      default -> throw new IllegalArgumentException("not a residency unload detail: " + detail);
    };
  }

  /** The residency detail an unload code ({@link #forResidencyUnload}) names; otherwise empty. */
  public static Optional<StopDetail> residencyUnloadDetailOf(String reasonCode) {
    if (LifecycleReasonCode.INFERENCE_UNLOADED_GPU_PRESSURE.code().equals(reasonCode)) {
      return Optional.of(StopDetail.GPU_BUSY);
    }
    if (LifecycleReasonCode.INFERENCE_UNLOADED_GAME_MODE.code().equals(reasonCode)) {
      return Optional.of(StopDetail.GAME_MODE);
    }
    return Optional.empty();
  }

  /**
   * The notice a READY server carries, from the reason its context window was chosen: {@code
   * inference.context_reduced} after a step-down (Spec A §7.2), otherwise none.
   */
  public static Optional<LifecycleReasonCode> readyNotice(String contextWindowReason) {
    return contextWindowReason != null && contextWindowReason.startsWith(STEPPED_FROM_PREFIX)
        ? Optional.of(LifecycleReasonCode.INFERENCE_CONTEXT_REDUCED)
        : Optional.empty();
  }
}
