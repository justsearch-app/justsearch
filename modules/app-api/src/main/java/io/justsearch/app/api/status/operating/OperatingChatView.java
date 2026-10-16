/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import io.justsearch.app.api.chat.BlockedBy;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.LoadingCause;
import io.justsearch.app.api.chat.RefusedCode;
import io.justsearch.app.api.chat.UnloadReason;
import io.justsearch.app.api.runtime.child.StopDetail;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code operating.chat} block: the chat server's state as one closed value.
 *
 * <p>{@code reason} is the constant name from the vocabulary of the state: {@link LoadingCause} for
 * LOADING, {@link UnloadReason} for UNLOADED, {@link RefusedCode} for REFUSED and {@link FailedCode}
 * for FAILED. OFF and READY carry none. {@code reasonCode} is the lifecycle reason code the UI
 * words, the same value as {@code components.generative.reason_code} in the same response.
 *
 * <p>Fields a producer cannot observe yet are {@code null}, never a guessed value.
 */
public record OperatingChatView(
    ChatState state,
    @Nullable String reason,
    @Nullable StopDetail detail,
    @Nullable String reasonCode,
    String since,
    boolean managed,
    @Nullable Boolean generating,
    @Nullable Integer inFlight,
    @Nullable Integer queued,
    @Nullable BlockedBy blockedBy,
    @Nullable ChatLoadView load,
    @Nullable Boolean reloadAllowed,
    @Nullable String nextReloadNotBefore,
    String sampledAt)
    implements PreciseWire {

  public OperatingChatView {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(since, "since");
    Objects.requireNonNull(sampledAt, "sampledAt");
    if (reason != null && !reasonsFor(state).contains(reason)) {
      throw new IllegalArgumentException(
          "reason " + reason + " is not in the vocabulary of state " + state);
    }
    if (load != null && state != ChatState.LOADING) {
      throw new IllegalArgumentException("load is reported only while LOADING");
    }
    if (blockedBy != null && state != ChatState.UNLOADED && state != ChatState.REFUSED) {
      throw new IllegalArgumentException("blockedBy is reported only while UNLOADED or REFUSED");
    }
    if ((inFlight != null && inFlight < 0) || (queued != null && queued < 0)) {
      throw new IllegalArgumentException("inFlight and queued must be non-negative");
    }
    if (generating != null && inFlight != null && generating != (inFlight > 0)) {
      throw new IllegalArgumentException("generating must equal inFlight > 0");
    }
  }

  /** The legal {@code reason} values for a state. */
  public static Set<String> reasonsFor(ChatState state) {
    return switch (state) {
      case OFF, READY -> Set.of();
      case LOADING -> names(LoadingCause.class);
      case UNLOADED -> names(UnloadReason.class);
      case REFUSED -> names(RefusedCode.class);
      case FAILED -> names(FailedCode.class);
    };
  }

  private static Set<String> names(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
  }
}
