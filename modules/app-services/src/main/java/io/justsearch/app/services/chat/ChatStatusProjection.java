/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import io.justsearch.app.api.chat.ChatReasonCodes;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentState;
import java.util.Objects;

/**
 * The generative component observations of chat (Spec A §3.1, step 7) that the lifecycle snapshot
 * publishes: a FAILED chat with its classified failure and a READY server with its notice. Pure
 * functions; the reason codes come from {@link ChatReasonCodes} only.
 */
public final class ChatStatusProjection {

  /** A generative component observation: its state and reason code (null when it has none). */
  public record Component(ComponentState state, String reasonCode) {
    public Component {
      Objects.requireNonNull(state, "state");
    }
  }

  private ChatStatusProjection() {}

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
}
