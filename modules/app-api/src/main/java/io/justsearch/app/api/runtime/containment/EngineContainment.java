/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import java.util.Optional;

/**
 * The platform backend binding launched children to the Engine's lifetime.
 *
 * <p>Linux uses PR_SET_PDEATHSIG through one permanent spawn thread. Every bound direct child
 * dies with this JVM; parser JVMs bind their own Tesseract children through the same seam.
 * Uninstrumented forks do not inherit this guarantee. This is not Windows job membership.
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
public sealed interface EngineContainment permits WindowsEngineJob, NoEngineContainment, LinuxEngineContainment {

  /** The containment this process has. */
  enum Status {
    /** The platform lifetime backend is active (Windows job or Linux bound launch seam). */
    CONTAINED,
    /** Containment applies on this platform but could not be established. */
    UNAVAILABLE,
    /** No lifetime backend applies on this platform. */
    NOT_APPLICABLE
  }

  Status status();

  /**
   * Why containment is {@link Status#UNAVAILABLE}. Windows reports {@code <call>:<error>};
   * Linux identifies the failed bound-exec backend. Empty otherwise.
   */
  Optional<String> unavailableReason();

  /**
   * Whether {@code child} belongs to the Windows job, or has an identity-matched Linux bound
   * launch receipt. Linux has no inherited job membership. Empty when containment or identity is
   * unavailable. {@code ChildControl} records this as {@code contained} on child-start. Never throws.
   */
  Optional<Boolean> contains(ProcessHandle child);

  /** Attaches the Engine's single children writer after app.lock is held; boot witnesses drain here. */
  default void recordProcesses(io.justsearch.app.api.runtime.child.LedgerSink ledger,
      io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity owner) {}

  /** Drains observed helper exits and freezes the writer before app.lock is released. */
  default void finishProcessRecording(java.time.Duration max) {}

  /**
   * Installs the platform backend (Windows job or Linux spawn thread/helper). Idempotent: the
   * first call decides. Must precede helper launches at the Engine entry point. Never throws.
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
