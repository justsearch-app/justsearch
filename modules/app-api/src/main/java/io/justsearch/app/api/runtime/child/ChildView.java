/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.time.Instant;
import java.util.Objects;

/** A read-only snapshot of one live held child, for the status surface. */
public record ChildView(
    String childId,
    ChildKind kind,
    long pid,
    Instant processStartedAt,
    ChildHandle.State state,
    boolean gpu,
    String label) {

  public ChildView {
    Objects.requireNonNull(childId, "childId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(processStartedAt, "processStartedAt");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(label, "label");
    if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
  }
}
