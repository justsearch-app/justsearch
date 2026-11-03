// SPDX-License-Identifier: Apache-2.0
/**
 * §32 #2B + tempdoc 550 C3 — shared accept helpers for the PendingEffect surfaces
 * (PendingEffectQueue, AgentActivityPanel).
 *
 * Centralises three concerns so the two components don't drift:
 *   - `operationRisk(effect)`: synchronous risk lookup for an invoke-operation
 *     effect (via the already-loaded operations catalog).
 *   - `needsTypedConfirm(effect)`: whether accepting needs the typed-confirm gesture.
 *   - `acceptPendingNow` / `acceptPendingWithConsent`: dispatch an accepted pending,
 *     optionally marking it user-consented.
 *
 * <p>Consent model (tempdoc 550 C3): the user's accept-after-typed-confirm sets a
 * `consented` flag on the invoke-operation effect. The Shell's dispatch
 * (`jf-invoke-operation` → `OperationClient.invokeWithConsent`) then recovers the backend's
 * 428 trust gate by approving the backend-issued `PendingAuthorization` by id and
 * re-invoking with the minted capsule. There is NO client-side capsule mint here — the FE
 * never asks the backend to mint for an arbitrary op; it can only approve what the backend
 * actually gated (closing the Tier-0 "approve an un-gated op" hole, WA-5). An agent's own
 * dispatch never sets `consented`, so the backend still gates it.
 *
 * Lives in components/ (not pending-effects/) because it depends on both
 * acceptPending and applyEffect — pending-effects must not import actions
 * (actions imports proposeEffect from pending-effects → would be a cycle).
 */

import { acceptPending, listPending } from '../substrates/pending-effects/index.js';
import { applyEffect } from '../substrates/actions/index.js';
import { getOperation } from '../../api/registry/OperationCatalogClient.js';
import type { Effect } from '../substrates/effect.js';
import type { EffectOriginator } from '../substrates/effects/index.js';

/** Risk tier of an invoke-operation effect, or undefined for other effects. */
export function operationRisk(effect: Effect): string | undefined {
  return effect.kind === 'invoke-operation'
    ? getOperation(effect.operationId)?.policy?.risk
    : undefined;
}

/**
 * True when accepting this effect needs an inline typed-confirm before dispatch — i.e. the
 * backend trust lattice would gate the re-dispatch (a non-AUTO gate). Keyed on the proposal's
 * originator, mirroring the lattice: an agent-proposed effect dispatches as UNTRUSTED, so
 * MEDIUM and HIGH both gate; a user/system-proposed effect dispatches as TRUSTED, so only
 * HIGH gates. (Tempdoc 550 C3 fix: previously HIGH-only, which left agent-proposed MEDIUM ops
 * falling through to the modal host — a split ceremony. The queue is now the consistent
 * effect ceremony; the modal host remains the backstop for any mis-prediction.)
 */
export function needsTypedConfirm(effect: Effect, originator: EffectOriginator): boolean {
  const risk = operationRisk(effect);
  return risk === 'HIGH' || (originator === 'agent' && risk === 'MEDIUM');
}

/**
 * Accept a pending and dispatch it as-is — no consent marker. Used for the immediate-accept
 * path (LOW/MEDIUM operations and non-operation effects), where the backend gate is AUTO and
 * no 428 recovery is needed.
 */
export function acceptPendingNow(id: number): void {
  acceptPending(id, (effect, invokedBy) => applyEffect(effect, invokedBy));
}

/**
 * Tempdoc 550 C3 (Authorize ceremony, Effect path): accept a typed-confirmed invoke-operation by
 * marking the effect `consented`, then dispatching. The Shell's invoke-operation listener
 * sees the flag and uses {@link OperationClient.invokeWithConsent}: on the backend's 428 it
 * approves the backend-issued {@link PendingAuthorization} by id and re-invokes with the
 * minted capsule — bound to the backend-stored op+args. No client-side mint; the consent is
 * just a flag.
 *
 * Centralised here — not duplicated into PendingEffectQueue and AgentActivityPanel — so the
 * two confirm surfaces cannot drift in how user consent is expressed. Non-operation /
 * effects requiring no typed confirmation accept without the flag (identical to {@link acceptPendingNow}).
 */
export function acceptPendingWithConsent(id: number): void {
  const pending = listPending().find(item => item.id === id);
  if (!pending) return;
  acceptPending(id, (effect, invokedBy) => {
    const e =
      effect.kind === 'invoke-operation' && needsTypedConfirm(effect, pending.originator)
        ? { ...effect, consented: true }
        : effect;
    applyEffect(e, invokedBy);
  });
}
