/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

/**
 * Completes a stop exceptionally when a child is still alive after both terminate attempts. The
 * handle stays owned, so a later stop retries under the original intent.
 */
public final class ChildSurvivedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient ChildView child;

  public ChildSurvivedException(ChildView child) {
    super("child " + child.childId() + " (" + child.kind() + ", pid " + child.pid()
        + ") survived termination");
    this.child = child;
  }

  public ChildView child() {
    return child;
  }
}
