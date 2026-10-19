/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

/**
 * The boot-order gate of the chat controller (Spec A §6.2, T2 and T3). After an Engine start, the
 * first GPU start of chat waits until the query encoders are warm, or until the gate expires, so
 * the two loads do not compete for the graphics card. CPU chat never waits.
 *
 * <p>The signal is one-way: the encoder side reports readiness and the controller never reads or
 * touches an encoder. A waiting activation is not run from memory: when the gate opens, the
 * activation's forwarder is asked to send its then-current intent again, so chat switched off
 * during the wait stays off.
 */
public interface ChatBootOrder {

  /**
   * Closes the gate for this Engine start; it expires on its own after the controller's bound
   * (45 s). Called once by the composition root, before the first activation is forwarded. A second
   * call, or a call after the gate opened, does nothing.
   *
   * @param replay asks the activation's forwarder to send its intent again; run once, when the gate
   *     opens with an activation waiting, on the thread that opened it (the encoder publication or
   *     the controller thread). It must only schedule the intent, never wait for it.
   */
  void armBootOrderGate(Runnable replay);

  /**
   * The query encoders are warm, so the first load after an Engine start may proceed. Opens the
   * gate and replays a waiting activation. Idempotent; a report before the gate was armed leaves it
   * open.
   */
  void reportQueryEncodersReady();
}
