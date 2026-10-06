/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.Hold;
import io.justsearch.app.api.chat.SelfTestSession;
import io.justsearch.app.api.chat.VduHold;
import java.time.Instant;
import java.util.Objects;

/**
 * The holds and the self-test session of the chat controller (Spec A §2.1, §4.1, §4.2 steps 5 and
 * 6). A machine actor that needs the chat server in a state other than the user's intent takes one
 * of these instead of driving the server itself:
 *
 * <ul>
 *   <li>{@link #beginSelfTest}: the main server is stopped while an activation self-test runs.
 *   <li>{@link #holdVdu}: the server runs with video-document understanding flags until the hold
 *       closes; closing it is the exit from that mode.
 *   <li>{@link #holdRunning}: the server runs (install smoke test).
 *   <li>{@link #freezeConvergence}: the user's intent is recorded but not acted on (install
 *       acquisition). Holds taken inside a freeze still act.
 * </ul>
 *
 * <p>While any of them is open the controller does not act on a forwarded activation; when the
 * last one closes, {@link Listener#kindClosed} lets the intent's forwarder return chat to the
 * intent. A caller must hold no lock the controller thread takes.
 */
public interface ChatHolds {

  /** The kinds of open holds. */
  enum Kind {
    FREEZE,
    SELF_TEST,
    VDU,
    RUNNING
  }

  /** One open hold, for status. */
  record OpenHold(Kind kind, String owner, Instant since) {
    public OpenHold {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(since, "since");
    }
  }

  /**
   * Told when the first hold of a kind opens and when the last one of a kind closes, in that order,
   * on the thread that opened or closed it. It must not call back into the controller.
   */
  interface Listener {
    void kindOpened(OpenHold first);

    void kindClosed(Kind kind, boolean noneOpen);
  }

  /**
   * Starts the server with video-document understanding flags and keeps it so until the hold
   * closes. Blocks until the server is in that mode.
   *
   * @throws ChatRefusal FAILED when a failure is latched (reconciliation F11), SELF_TEST while a
   *     self-test runs, BUSY while another VDU hold is open or replies did not finish within 30 s,
   *     ENGINE_STOPPING when the controller is terminal, LOADING when the server did not start
   */
  VduHold holdVdu(String owner) throws ChatRefusal;

  /**
   * Starts the server if it is not running and keeps it running until the hold closes.
   *
   * @throws ChatRefusal FAILED when a failure is latched (reconciliation F11), SELF_TEST while a
   *     self-test runs, ENGINE_STOPPING when the controller is terminal, LOADING when the server
   *     did not start
   */
  Hold holdRunning(String owner) throws ChatRefusal;

  /** Records the user's intent without acting on it until the hold closes. */
  Hold freezeConvergence(String owner);

  /**
   * Stops the main server (after replies finish, at most 30 s) and keeps it stopped until the
   * session closes. From an idle state it goes straight to the session (reconciliation F17).
   *
   * @throws ChatRefusal BUSY when replies did not finish in 30 s or another hold is open,
   *     ENGINE_STOPPING when the controller is terminal
   */
  SelfTestSession<SelfTestSpec, SelfTestRun> beginSelfTest(String owner) throws ChatRefusal;

  /**
   * Whether an automatic VDU pass may start now (Spec A §5.4, U13): chat is not running, nothing
   * holds it, no self-test is open and no failure is latched. The trigger reads this, never the
   * server being down, so a server stopped for a self-test or after a failure never starts a pass.
   */
  boolean vduMayStart();

  /** Registers a listener; closing the result removes it. */
  AutoCloseable addListener(Listener listener);
}
