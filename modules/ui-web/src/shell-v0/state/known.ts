// SPDX-License-Identifier: Apache-2.0
/**
 * known.ts — tri-state value primitive (tempdoc 557 §2.B).
 *
 * A status value is either **Known** (we have real data from the backend) or
 * **Unknown** (no data has arrived yet, or the stream was lost). Surfaces must
 * branch on this, so "no data" can never be rendered as a confident concrete
 * value (the "0 files" / "Not Installed" / "All operational" defect class).
 *
 * This is the model-level half of §2.B's prevention (tier-2 at the seam):
 * a `Maybe<T>` cannot be read as a `T` without handling the unknown case.
 */

export type Known<T> = { readonly known: true; readonly value: T };
export type Unknown = { readonly known: false };
export type Maybe<T> = Known<T> | Unknown;

export const UNKNOWN: Unknown = { known: false };
export const known = <T>(value: T): Known<T> => ({ known: true, value });

export function isKnown<T>(m: Maybe<T>): m is Known<T> {
  return m.known;
}

/** Total fold over the tri-state — the render-side seam. */
export function whenKnown<T, R>(m: Maybe<T>, onKnown: (value: T) => R, onUnknown: () => R): R {
  return m.known ? onKnown(m.value) : onUnknown();
}

/** Project a Known value through a function; Unknown passes through. */
export function mapKnown<T, R>(m: Maybe<T>, f: (value: T) => R): Maybe<R> {
  return m.known ? known(f(m.value)) : UNKNOWN;
}

/**
 * Total fold over `(value, stability)` — tempdoc 595 §4.3. `whenKnown` is binary
 * (known vs unknown), but a value can be Known yet PROVISIONAL: a rebuild's worker
 * fallback reports `known(0)` docs, which the binary fold renders as a settled "0"
 * (the "0 docs = data loss" misread). This 3-branch fold makes the provisional
 * case its own branch, so a transient value can never be shown as a settled fact.
 *
 * `provisional` is the caller's projection of the system `Stability` axis
 * (`stability.kind === 'provisional'`); kept as a boolean so this primitive stays
 * dependency-free (no import of the verdict module).
 */
export function renderObserved<T, R>(
  m: Maybe<T>,
  provisional: boolean,
  onSettled: (value: T) => R,
  onProvisional: () => R,
  onUnknown: () => R,
): R {
  if (!m.known) return onUnknown();
  return provisional ? onProvisional() : onSettled(m.value);
}
