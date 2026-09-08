/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

/**
 * The Engine's deliberate exit codes and the class a supervisor reads them as (design 7.1, stage B
 * item B1).
 *
 * <p><b>Why a table at all.</b> Stage A left the Engine with three {@code System.exit} calls and no
 * way for anything outside the process to tell them apart, because nothing outside the process was
 * watching: the dev-runner exits itself when the child dies and Tauri only notices a death that
 * happens before a port is bound. Stage B's supervisors have to decide, from an integer, whether to
 * retry. Three retries of a data directory another instance holds is forty seconds of flapping
 * before the same answer; refusing to retry a crash is a product that stays down.
 *
 * <p><b>The classes are design 7.1's, not new.</b> <em>requested</em> — the exit was asked for, so
 * it is not charged to the crash budget. <em>transient</em> — a crash, the out-of-memory exit, a
 * hang; counted and retried under cooldown. <em>non-transient</em> — the same input will fail the
 * same way, so the supervisor goes to {@code exhausted} at once with that reason.
 *
 * <p><b>Unknown codes fail OPEN, to retry.</b> {@link #classify(int)} answers {@link
 * ExitClass#TRANSIENT} for anything it does not recognise. The alternative — treating the unknown
 * as non-transient — turns every unclassified death, including a native crash whose code is a
 * Windows exception status, into a permanent stop. A wrong retry costs one cooldown; a wrong
 * {@code exhausted} costs the product.
 */
public final class EngineExit {

  private EngineExit() {}

  /** How a supervisor should treat an exit (design 7.1's budget row). */
  public enum ExitClass {
    /** Asked for. Not charged to the crash budget. */
    REQUESTED,
    /** A crash, the out-of-memory exit, or a hang. Counted; retried under cooldown. */
    TRANSIENT,
    /** The same input fails the same way. No retry; {@code exhausted} at once with the reason. */
    NON_TRANSIENT
  }

  /** A clean, ordered shutdown ran to completion (design 7.3 step 8). */
  public static final int OK = 0;

  /**
   * A fatal error or an uncaught exception on any thread.
   *
   * <p><b>Ambiguous, and classified accordingly.</b> The default uncaught-exception handler
   * installed before anything else boots ({@code HeadlessApp.java:901}), the catch around the whole
   * run ({@code :1129}), and the active-writer tragedy owner all produce this code. Those paths
   * cover crashes, terminal runtime faults, and boot failures. Nothing in the integer distinguishes
   * them, so it is classified {@link
   * ExitClass#TRANSIENT} — the failure that costs more is refusing to restart after a crash.
   *
   * <p>This is where the table grows: a boot failure that genuinely cannot succeed on a retry
   * should get its own code and a {@link ExitClass#NON_TRANSIENT} row, the way {@link
   * #DATA_DIR_LOCKED} already has. Stage A's checklist described all three existing exits as "plain
   * boot failures, i.e. non-transient"; reading the sites shows that is true of exactly one of them.
   */
  public static final int FATAL_OR_UNCAUGHT = 1;

  /**
   * Another instance already holds the data directory's lock ({@code HeadlessApp.java:955}).
   *
   * <p>The one genuinely non-transient exit the Engine has today: the lock is held by a live
   * process, so the next attempt reads the same lock and prints the same message.
   */
  public static final int DATA_DIR_LOCKED = 2;

  /**
   * The JVM exited on the first {@code OutOfMemoryError} because of {@code
   * -XX:+ExitOnOutOfMemoryError}.
   *
   * <p><b>Measured, not assumed</b> (Temurin 25.0.2, 2026-09-08): a JVM run with the flag and a heap
   * too small for its allocation exits <b>3</b>; the same program WITHOUT the flag exits <b>1</b>,
   * because the OOM propagates to the uncaught-exception handler. That is the whole argument for
   * carrying the flag at both spawn sites — without it an out-of-memory death is indistinguishable
   * from a boot failure, and a supervisor cannot price a memory problem it cannot see.
   */
  public static final int OUT_OF_MEMORY = 3;

  /** A clean Engine-local restart; the host replaces it without charging the crash budget. */
  public static final int REQUESTED_RESTART = 4;

  /**
   * Classifies an observed exit code.
   *
   * @param code the child's exit code as the supervisor observed it
   * @return the class to budget it under; {@link ExitClass#TRANSIENT} for unrecognised codes
   */
  public static ExitClass classify(int code) {
    return switch (code) {
      case OK, REQUESTED_RESTART -> ExitClass.REQUESTED;
      case DATA_DIR_LOCKED -> ExitClass.NON_TRANSIENT;
      case FATAL_OR_UNCAUGHT, OUT_OF_MEMORY -> ExitClass.TRANSIENT;
      default -> ExitClass.TRANSIENT;
    };
  }

  /**
   * A short, stable label for an exit code, for the supervisor state file and logs.
   *
   * <p>Unknown codes render as {@code unknown(<code>)} rather than being folded into a named
   * reason: a state file that says {@code unknown(-1073741819)} sends a reader to the Windows
   * exception status, and one that says {@code crash} does not.
   */
  public static String describe(int code) {
    return switch (code) {
      case OK -> "ok";
      case REQUESTED_RESTART -> "requested_restart";
      case FATAL_OR_UNCAUGHT -> "fatal_or_uncaught";
      case DATA_DIR_LOCKED -> "data_dir_locked";
      case OUT_OF_MEMORY -> "out_of_memory";
      default -> "unknown(" + code + ")";
    };
  }
}
