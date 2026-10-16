/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import io.justsearch.app.api.runtime.child.StopReason;
import java.time.Duration;

/**
 * The quit's one way to stop the managed chat server (Spec F §4.3 QB3, RV2 F2): the ordered close
 * depends on this narrow type, not on the controller, so its binding can change without a change to
 * the close. It is bound to the chat controller's terminal {@link ChatServerController#shutdown},
 * which makes the controller terminal first — refusing every later admission, relaunch or recovery
 * start — and then stops the server within {@code budget}.
 */
@FunctionalInterface
public interface TerminalChatStop {
  void shutdown(StopReason reason, Duration budget);
}
