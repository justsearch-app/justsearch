/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * Keeps the chat server running with its vision-safe VDU flags while a document-understanding batch
 * uses it (Spec A §2.2: RUNNING with VDU flags). Closing it is the exit from that mode: the server
 * returns to its configured flags and to the user's intent.
 */
public interface VduHold extends Hold {
  /** True when the controller needs the GPU back and asks the holder to finish early. */
  boolean interruptRequested();
}
