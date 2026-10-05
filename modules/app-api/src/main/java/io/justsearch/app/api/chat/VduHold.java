/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * Keeps the chat server stopped while video-document understanding uses the GPU. Closing it is the
 * exit from that mode.
 */
public interface VduHold extends Hold {
  /** True when the controller needs the GPU back and asks the holder to finish early. */
  boolean interruptRequested();
}
