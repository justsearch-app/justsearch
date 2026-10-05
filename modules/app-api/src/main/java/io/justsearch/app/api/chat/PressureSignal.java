/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** External GPU pressure reported to the chat controller. {@link #NONE} clears it. */
public enum PressureSignal {
  NONE,
  EXTERNAL_MEMORY,
  GAME
}
