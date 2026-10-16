/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

/**
 * The exit codes a supervisor passes to {@code TerminateProcess} / {@code TerminateJobObject}, so a
 * forced stop is recognisable afterwards. A process ended by the closure of its kill-on-close job
 * reports 0 on Windows (measured, {@code evidence/F/job-close-code.md}) and {@code taskkill /F}
 * reports 1, so neither tells a forced stop from an ordinary exit; these values do. The Node dev
 * runner and the Rust shell carry the same two values.
 */
public final class TerminateCodes {
  /** The Engine forcing one of its own children: {@code 0x4A530001} ("JS" 0001). */
  public static final int JS_CHILD_STOP = 0x4A530001;

  /** A supervisor forcing the Engine (and its incarnation job): {@code 0x4A530002}. */
  public static final int JS_ENGINE_STOP = 0x4A530002;

  private TerminateCodes() {}
}
