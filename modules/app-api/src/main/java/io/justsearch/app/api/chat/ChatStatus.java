/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import io.justsearch.app.api.runtime.child.StopDetail;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One consistent snapshot of the chat controller: the single source that the status surface,
 * {@code RuntimeStatus} and the readiness projection derive from.
 *
 * <p>Each reason slot is legal only in its own state, so an impossible combination (an unload
 * reason on a READY server, say) cannot be constructed:
 *
 * <ul>
 *   <li>{@code loadingCause} only in {@link ChatState#LOADING};
 *   <li>{@code unloadReason} only in, and required by, {@link ChatState#UNLOADED};
 *   <li>{@code refusedCode} only in {@link ChatState#REFUSED};
 *   <li>{@code failedCode} only in {@link ChatState#FAILED};
 *   <li>{@code blockedBy} only in UNLOADED or REFUSED.
 * </ul>
 *
 * <p>A {@link UnloadReason#RESIDENCY_PRESSURE} unload carries the detail {@code gpu_busy} or
 * {@code game_mode}. {@code generating} is exactly {@code inFlight > 0}.
 *
 * @param since when the controller entered {@code state}
 * @param managed false for a user-configured external server, which is reported, never unloaded
 * @param inFlight open GENERATE leases (AUX leases are not counted)
 * @param queued admitted demands waiting for a lease
 * @param nextReloadNotBefore the earliest time a reload may start, when one is blocked
 * @param version incremented on every published change
 */
public record ChatStatus(
    ChatState state,
    Optional<LoadingCause> loadingCause,
    Optional<UnloadReason> unloadReason,
    Optional<RefusedCode> refusedCode,
    Optional<FailedCode> failedCode,
    Optional<StopDetail> detail,
    Optional<BlockedBy> blockedBy,
    Instant since,
    boolean managed,
    boolean generating,
    int inFlight,
    int queued,
    Optional<Instant> nextReloadNotBefore,
    long version) {

  private static final Set<StopDetail> PRESSURE_DETAILS =
      EnumSet.of(StopDetail.GPU_BUSY, StopDetail.GAME_MODE);

  public ChatStatus {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(loadingCause, "loadingCause");
    Objects.requireNonNull(unloadReason, "unloadReason");
    Objects.requireNonNull(refusedCode, "refusedCode");
    Objects.requireNonNull(failedCode, "failedCode");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(blockedBy, "blockedBy");
    Objects.requireNonNull(since, "since");
    Objects.requireNonNull(nextReloadNotBefore, "nextReloadNotBefore");
    requireOnlyIn(loadingCause, state == ChatState.LOADING, "loadingCause", state);
    requireOnlyIn(unloadReason, state == ChatState.UNLOADED, "unloadReason", state);
    requireOnlyIn(refusedCode, state == ChatState.REFUSED, "refusedCode", state);
    requireOnlyIn(failedCode, state == ChatState.FAILED, "failedCode", state);
    requireOnlyIn(
        blockedBy,
        state == ChatState.UNLOADED || state == ChatState.REFUSED,
        "blockedBy",
        state);
    if (state == ChatState.UNLOADED && unloadReason.isEmpty()) {
      throw new IllegalArgumentException("an UNLOADED status names its unload reason");
    }
    if (unloadReason.orElse(null) == UnloadReason.RESIDENCY_PRESSURE
        && !detail.map(PRESSURE_DETAILS::contains).orElse(false)) {
      throw new IllegalArgumentException(
          "a RESIDENCY_PRESSURE unload carries the detail gpu_busy or game_mode");
    }
    if (inFlight < 0 || queued < 0) {
      throw new IllegalArgumentException("inFlight and queued must be non-negative");
    }
    if (generating != (inFlight > 0)) {
      throw new IllegalArgumentException("generating must equal inFlight > 0");
    }
    if (version < 0) throw new IllegalArgumentException("version must be non-negative");
  }

  /**
   * The state's reason as its wire name, from whichever slot the state allows; empty when the state
   * has none.
   */
  public Optional<String> reasonName() {
    return switch (state) {
      case OFF, READY -> Optional.empty();
      case LOADING -> loadingCause.map(Enum::name);
      case UNLOADED -> unloadReason.map(Enum::name);
      case REFUSED -> refusedCode.map(Enum::name);
      case FAILED -> failedCode.map(Enum::name);
    };
  }

  private static void requireOnlyIn(
      Optional<?> slot, boolean allowed, String name, ChatState state) {
    if (slot.isPresent() && !allowed) {
      throw new IllegalArgumentException(name + " is not allowed in state " + state);
    }
  }
}
