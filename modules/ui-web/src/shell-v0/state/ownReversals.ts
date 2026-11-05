// SPDX-License-Identifier: Apache-2.0
/**
 * Own-action reversals — design system §1 principle 4 "Reversible beats confirmed" and §7 "Undo
 * notice" (ref-07).
 *
 * When one of the person's own actions has a real reversal (a skip-rule change re-saved as it was,
 * a removed folder added again), the action offers it here instead of asking first. The offer:
 *  - shows in the status pill as "<what happened> · <reversal>" for a few seconds, held while the
 *    pill or its action is hovered or focused (the pill's transient slot, `core.own-action`);
 *  - stays in the status panel's Earlier with its action while the reversal is still possible;
 *  - is what Ctrl+Z runs after the notice has gone (the most recent one still possible), unless a
 *    newer reversible effect in the Effect Journal is the more recent action.
 *
 * A reversal runs at most once at a time and never after it succeeded. A reversal that finds the
 * world changed since (another window saved over the skip rules, the folder is already back) closes
 * itself by throwing {@link ReversalClosedError}: it is reported and not offered again. Any other
 * failure is reported and the offer stays, so it can be tried again.
 *
 * Only real reversals belong here. An action without one confirms in place instead
 * (`jf-confirm-band`); Undo is never shown where nothing would be reversed.
 */
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';

/** The reversal is no longer possible (the reversal window closed); not offered again. */
export class ReversalClosedError extends Error {}

export interface OwnReversalSpec {
  /** What happened, in the person's words: "Skip rule added", "Removed Notes from your library". */
  readonly message: string;
  /** The consequence of reversing, shown with the record in Earlier. */
  readonly detail?: string;
  /** The reversal's button: "Undo", or a plainer verb when that is what really happens ("Add Notes again"). */
  readonly actionLabel?: string;
  /** Performs the reversal. Throw {@link ReversalClosedError} when it is no longer possible. */
  readonly reverse: () => Promise<void>;
  /** Said when the reversal succeeded. */
  readonly reversed: string;
  /** A removed folder this reversal adds back (lets other records of the same removal defer to it). */
  readonly restoresFolder?: string;
}

interface OwnReversal extends OwnReversalSpec {
  readonly at: number;
  state: 'open' | 'running' | 'done' | 'closed';
}

const MAX_OFFERS = 20;
let offers: OwnReversal[] = [];
const listeners = new Set<() => void>();
function changed(): void { for (const listener of listeners) listener(); }

/** Called whenever an offer appears, runs or closes. Returns the unsubscribe. */
export function subscribeReversals(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Offer a real reversal of the person's own action (pill, Earlier, Ctrl+Z). */
export function offerReversal(spec: OwnReversalSpec): void {
  const offer: OwnReversal = { ...spec, at: Date.now(), state: 'open' };
  offers = [...offers, offer].slice(-MAX_OFFERS);
  changed();
  emitEphemeralToast({
    classId: 'core.own-action',
    message: spec.message,
    ...(spec.detail ? { detail: spec.detail } : {}),
    actionLabel: spec.actionLabel ?? 'Undo',
    reversal: true,
    actionAvailable: () => offer.state === 'open',
    ...(spec.restoresFolder ? { restoresFolder: spec.restoresFolder } : {}),
    onAction: () => { void runReversal(offer); },
  });
}

async function runReversal(offer: OwnReversal): Promise<void> {
  if (offer.state !== 'open') return;
  offer.state = 'running';
  changed();
  try {
    await offer.reverse();
    offer.state = 'done';
    emitEphemeralToast({ classId: 'core.own-action', message: offer.reversed, severity: 'success' });
  } catch (error) {
    const closed = error instanceof ReversalClosedError;
    offer.state = closed ? 'closed' : 'open';
    const reason = error instanceof Error ? error.message : String(error);
    // Named by the reversal's own verb: "Could not add Notes again. …", "Can't undo: …".
    const verb = (offer.actionLabel ?? 'Undo').replace(/^./, first => first.toLowerCase());
    emitEphemeralToast({ classId: 'core.own-action', severity: closed ? 'warning' : 'error',
      message: closed ? `Can't ${verb}: ${reason}` : `Could not ${verb}. ${reason}` });
  } finally {
    changed();
  }
}

/** When the most recent still-possible reversal was offered (ms epoch), or null when there is none. */
export function latestReversalAt(): number | null {
  for (let i = offers.length - 1; i >= 0; i--) if (offers[i]!.state === 'open') return offers[i]!.at;
  return null;
}

/** Ctrl+Z after the notice has gone: run the most recent still-possible reversal. False when none. */
export function undoLatestReversal(): boolean {
  for (let i = offers.length - 1; i >= 0; i--) {
    const offer = offers[i]!;
    if (offer.state === 'running') return true; // one at a time: a repeat press waits for the running one
    if (offer.state === 'open') { void runReversal(offer); return true; }
  }
  return false;
}

/** Still-possible reversals, newest first (System › Activity lists them with their action). */
export function listOpenReversals(): readonly { readonly message: string; readonly actionLabel: string; readonly at: number; readonly run: () => void }[] {
  return offers.filter(offer => offer.state === 'open').reverse()
    .map(offer => ({ message: offer.message, actionLabel: offer.actionLabel ?? 'Undo', at: offer.at, run: () => { void runReversal(offer); } }));
}

/** Test-only: forget every offer. */
export function __resetOwnReversalsForTest(): void {
  offers = [];
  changed();
}
