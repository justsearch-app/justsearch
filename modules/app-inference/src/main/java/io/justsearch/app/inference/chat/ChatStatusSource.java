/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.chat.ChatStatus;

/** The chat controller's one consistent status snapshot (Spec A §3.1, step 7). */
public interface ChatStatusSource {

  /** The current snapshot. Never blocks on a lifecycle step. */
  ChatStatus status();
}
