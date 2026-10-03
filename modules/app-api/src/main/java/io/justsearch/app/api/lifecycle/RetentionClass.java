/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

/**
 * Tempdoc 837 §D.1 — how much a HELD reason code is worth defending against the next write.
 *
 * <p>A capability's reason slot is last-writer-wins by default, and that loses causes: a precise
 * activation failure ({@code inference.model_not_found}) is overwritten by the generic
 * {@code inference.offline} the very next mode change stamps, and the corrupt-index cause is
 * destroyed permanently because the marker that produced it was deleted as it was read.
 *
 * <p>The first draft of the rule keyed on the INCOMING code ("is the arriving write generic?") and
 * was a wrong-gate: it retained {@code index.starting} as the reported cause of a later failure.
 * What actually licenses retention is a property of the HELD code — is it evidence of a fault that
 * the newer write would destroy? — so the classification lives on the vocabulary itself
 * ({@link LifecycleReasonCode#retentionClass()}), and the decision procedure reads it.
 */
public enum RetentionClass {
  /**
   * Unrepeatable evidence: the signal that produced it is already gone, so an overwrite loses it
   * forever, or terminal evidence that must not be replaced before READY. Retained against every
   * incoming reason while the capability is non-READY. Fatal index marker causes are consumed as
   * they are read; {@code component.recovery_exhausted} remains held until recovery succeeds.
   */
  STICKY,
  /**
   * A real cause. Retained against a calmer or vaguer incoming code, and overwritten by a newer
   * fault (better information) or by STICKY evidence (strictly better information).
   */
  FAULT,
  /**
   * Progress narration, a scheduled state, or an intentional one: self-clearing, stale by
   * construction the moment anything else happens, and never worth retaining. Also the class of any
   * code that a capability never holds at all, so the classification stays total without a fifth
   * class (never-retained is the safe default for a code that cannot be held anyway).
   */
  TRANSIENT,
  /**
   * The "I know nothing" fallback a producer stamps when it has no specific cause. Never retained,
   * and never allowed to overwrite a fault.
   */
  GENERIC
}
