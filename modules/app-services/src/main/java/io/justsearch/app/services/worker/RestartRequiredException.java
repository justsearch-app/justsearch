/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Thrown where the product used to restart the Worker process and now cannot (lane F stage A item
 * A11, §10 "restart-as-reload").
 *
 * <p>Three call sites used to reach for {@code WorkerSpawner.restart()}: the
 * {@code core.restart-worker} operation behind {@code POST /api/worker/restart}, the config-apply
 * that follows an AI install, and the same after a model-pack import. All three existed because
 * the index half was a child process that could be replaced without taking the Head down. It is
 * not a child process any more — restarting it means restarting the Engine, which is the user's
 * action, not the product's.
 *
 * <p>So the honest answer is this, not a silent no-op and not a fake success: the operation is
 * <b>accepted and answered</b> with "a restart is required", the surface says so, and the process
 * stays up. Stage A §10 records it as a deliberate loss until stage B gives the Engine a
 * supervisor (design 7.1) that can restart it for real.
 *
 * <p>The name is deliberately not {@code UnsupportedOperationException}: the operation IS
 * supported, its precondition simply moved to the user.
 */
public final class RestartRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The stable code surfaces read. Kept as a constant rather than a message-substring so a
   * consumer matching on it cannot be broken by wording.
   */
  public static final String CODE = "restart_required";

  public RestartRequiredException(String what) {
    super(what + " requires an Engine restart: the index half now runs in this process (lane F"
        + " stage A), so it cannot be replaced without replacing the process. Restart JustSearch"
        + " to apply.");
  }

  /** The stable code, for a surface that reports a code rather than a message. */
  public String code() {
    return CODE;
  }
}
