/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/** Manual entry to the health monitor's single component-recovery slot. */
public interface ComponentRecoveryAuthority {
  enum Outcome {
    ACCEPTED,
    ALREADY_RUNNING,
    EXHAUSTED,
    NOT_APPLICABLE,
    UNKNOWN_COMPONENT,
    OWNER_UNAVAILABLE
  }

  /** Schedules one local attempt without holding the caller through physical composition. */
  Outcome requestComponentRecovery(String name);
}
