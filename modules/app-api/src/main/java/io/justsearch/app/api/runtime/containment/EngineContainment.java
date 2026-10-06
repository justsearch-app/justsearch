/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import java.util.Optional;

/**
 * Whether every process the Engine starts ends with the Engine, however the Engine ends.
 *
 * <p>On Windows the Engine creates a kill-on-close job and joins it as the first statement of its
 * entry point, before anything can start a process ({@link WindowsEngineJob}). Every later child,
 * grandchild and console host is created inside that job, and the job forbids breakaway, so when
 * the Engine process dies for any reason Windows closes the job handle and ends them all. Under the
 * desktop shell the job nests inside the shell's per-incarnation job; under the dev runner it nests
 * inside Node's job.
 *
 * <p>{@link #installAtBoot} never throws. A failure degrades to {@link Status#UNAVAILABLE} with the
 * failing call and its error, which the Engine reports in the runtime manifest and as the lifecycle
 * reason {@code engine.containment_unavailable}; boot continues (the shell's job still contains the
 * Engine's helpers in production).
 */
public sealed interface EngineContainment permits WindowsEngineJob, NoEngineContainment {

  /** The containment this process has. */
  enum Status {
    /** The Engine is in its own kill-on-close job; every process it starts is contained. */
    CONTAINED,
    /** Containment applies on this platform but could not be established. */
    UNAVAILABLE,
    /** Not Windows: there is no job object to join. */
    NOT_APPLICABLE
  }

  Status status();

  /**
   * Why containment is {@link Status#UNAVAILABLE}, as {@code <call>:<error>} (for example {@code
   * AssignProcessToJobObject:5}); empty otherwise.
   */
  Optional<String> unavailableReason();

  /**
   * Creates and joins the Engine job. Idempotent: the first call decides and later calls return
   * the same value. Must be the first statement of the Engine's entry point. Never throws.
   */
  static EngineContainment installAtBoot() {
    return BootContainment.install();
  }

  /**
   * What {@link #installAtBoot} returned, or {@link Status#UNAVAILABLE} with reason {@code
   * not_installed} in a process that never installed it.
   */
  static EngineContainment current() {
    return BootContainment.current();
  }
}
