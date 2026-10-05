/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

/**
 * The accounting class of one observed process exit: did its owner ask for it?
 *
 * <p>This is the <em>exit class</em>, recorded per exit in the exit ledger. It is distinct from the
 * <em>restart class</em> in {@code governance/supervision-contract.v1.json} ({@code
 * exitCodes[].class}: REQUESTED, TRANSIENT, NON_TRANSIENT), which decides whether a supervisor
 * restarts. The two vocabularies share only {@link #REQUESTED}, and it means the same thing in
 * both. A survivor is an outcome state ({@link ChildHandle.State#SURVIVED}), not a class.
 */
public enum ExitClass {
  /** An owner intent preceded the exit, and the exit is attributable to it. */
  REQUESTED,
  /** No owner intent preceded the exit: a crash, or the process ending on its own. */
  UNEXPECTED,
  /** The owner died first; the next owner closed the record after an OS identity check. */
  OWNER_DIED,
  /** The evidence does not decide the class. */
  UNKNOWN
}
