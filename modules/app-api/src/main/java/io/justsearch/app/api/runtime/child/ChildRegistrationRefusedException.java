/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by {@link ChildControl#start} when the child's manifest registration was refused: the
 * child was stopped with {@code ROLLBACK} and not returned as started. When that stop did not
 * confirm its exit, the child is the {@link #survivor()}: still held by the control (SURVIVED, or
 * STOPPING), so its caller can keep owning it, retry its stop and refuse to start another beside
 * it. The {@link #rolledBack()} child is always handed over, also when its root exited: what it
 * started may outlive the root when ending that scope failed, so a caller that owns what its
 * children hold (design D4) keeps it until its root and descendants are confirmed released.
 */
public final class ChildRegistrationRefusedException extends IOException {
  private static final long serialVersionUID = 1L;

  private final transient ChildHandle survivor;
  private final transient ChildHandle rolledBack;

  /**
   * @param refused why the registration was refused
   * @param child the rolled-back child; it is the survivor unless its exit was recorded
   */
  public ChildRegistrationRefusedException(Throwable refused, ChildHandle child) {
    super(messageOf(refused), refused);
    Objects.requireNonNull(child, "child");
    this.rolledBack = child;
    this.survivor = child.state() == ChildHandle.State.EXITED ? null : child;
  }

  /** The rolled-back child whose exit was not confirmed; empty when it exited. */
  public Optional<ChildHandle> survivor() {
    return Optional.ofNullable(survivor);
  }

  /**
   * The rolled-back child, whether or not its exit was recorded. Its {@link ChildHandle#descendants()}
   * still lists what it started that its rollback could not confirm ended.
   */
  public ChildHandle rolledBack() {
    return rolledBack;
  }

  private static String messageOf(Throwable refused) {
    String message = refused == null ? null : refused.getMessage();
    return message == null || message.isBlank()
        ? "manifest registration of the child was refused"
        : message;
  }
}
