/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.chat.ChatReasonCodes;
import io.justsearch.app.api.chat.ChatStatus;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentState;
import java.util.Objects;

/**
 * The projections of one {@link ChatStatus} (Spec A §3.1, step 7): the generative component the
 * lifecycle snapshot publishes and the legacy {@link Mode}. Pure functions; the reason codes come
 * from {@link ChatReasonCodes} only.
 */
public final class ChatStatusProjection {

  /** A generative component observation: its state and reason code (null when it has none). */
  public record Component(ComponentState state, String reasonCode) {
    public Component {
      Objects.requireNonNull(state, "state");
    }
  }

  private ChatStatusProjection() {}

  /**
   * Spec A §3.1: LOADING is STARTING, or RELOADING when the component was serving; READY is READY;
   * FAILED is FAILED with the classified failure's code; every other state is UNAVAILABLE with its
   * reason code.
   *
   * @param current the component's state before this observation
   */
  public static Component component(ChatStatus status, ComponentState current) {
    Objects.requireNonNull(status, "status");
    String code = ChatReasonCodes.toLifecycle(status).map(LifecycleReasonCode::code).orElse(null);
    return switch (status.state()) {
      case LOADING -> new Component(
          current == ComponentState.READY || current == ComponentState.RELOADING
              ? ComponentState.RELOADING : ComponentState.STARTING, code);
      case READY -> new Component(ComponentState.READY, null);
      case FAILED -> new Component(ComponentState.FAILED, code);
      case OFF, UNLOADED, REFUSED -> new Component(ComponentState.UNAVAILABLE, code);
    };
  }

  /** The FAILED observation of a latched failure (Spec A §7.1). */
  public static Component failed(FailedCode code) {
    return new Component(ComponentState.FAILED, ChatReasonCodes.forFailure(code).code());
  }

  /**
   * The READY observation of a serving server, with the {@code inference.context_reduced} notice
   * when its context window was stepped down (Spec A §7.2).
   *
   * @param contextWindowReason the reason the launched window was chosen, or null
   */
  public static Component ready(String contextWindowReason) {
    return new Component(ComponentState.READY, ChatReasonCodes.readyNotice(contextWindowReason)
        .map(LifecycleReasonCode::code).orElse(null));
  }

  /**
   * The legacy mode (kept for VDU pacing, the GPU lease and availability readers): READY is
   * ONLINE, LOADING is TRANSITIONING, and a chat that does not run is OFFLINE. Reconciliation F4: a
   * non-running chat never projects to {@link Mode#INDEXING}.
   */
  public static Mode mode(ChatStatus status) {
    Objects.requireNonNull(status, "status");
    return switch (status.state()) {
      case READY -> Mode.ONLINE;
      case LOADING -> Mode.TRANSITIONING;
      case OFF, UNLOADED, REFUSED, FAILED -> Mode.OFFLINE;
    };
  }
}
