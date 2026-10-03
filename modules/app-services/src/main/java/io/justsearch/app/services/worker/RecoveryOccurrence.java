/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.Objects;

/** One recovery decision, delivered directly rather than inferred from coalesced readiness. */
public record RecoveryOccurrence(Kind kind, RecoveryContext context) {
  public RecoveryOccurrence {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(context, "context");
  }

  public enum Kind { ATTEMPTED, RECOVERED }
}
