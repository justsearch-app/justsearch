// SPDX-License-Identifier: Apache-2.0
/**
 * View Transitions for surface switching — tempdoc 609 §R (T1.1 / NEW-3).
 *
 * Instance-retention keeps each surface's DOM node alive across navigation, so a surface switch is a pure
 * node swap in the Stage. Wrapping that swap in the same-document View Transitions API animates the visual
 * delta for free (browser-default cross-fade on `::view-transition-old/new(root)`), with no animation
 * library.
 *
 * Render-safety: we do NOT defer the state change into the transition callback (that would change when
 * `activeId` becomes observable and could desync callers + the RetainedScroll controller, which depends on
 * each surface's `updateComplete` timing). Instead the caller sets state SYNCHRONOUSLY as before, then calls
 * {@link startSurfaceTransition} — Lit's resulting update is still microtask-pending, so the API captures
 * the "before" snapshot now and the callback simply awaits the flush (host updateComplete + one animation
 * frame so the child Stage's own update paints) to capture "after". Pure progressive enhancement: a no-op
 * where the API is unavailable or the user prefers reduced motion, and a failed/garbage-collected
 * transition never breaks the app (worst case: no animation).
 */
import { appLog } from '../../utils/logger.js';

interface ViewTransitionDocument {
  startViewTransition?: (cb: () => Promise<void> | void) => unknown;
}

/**
 * The three promise faces a same-document `ViewTransition` exposes. Every one of them can REJECT, and
 * a `ViewTransition` handed back and dropped leaves all three unhandled.
 */
interface ViewTransitionHandle {
  readonly ready?: Promise<unknown>;
  readonly finished?: Promise<unknown>;
  readonly updateCallbackDone?: Promise<unknown>;
}

/**
 * A transition that was SKIPPED rather than one that went wrong. The spec rejects `ready` with an
 * `AbortError` when a second transition supersedes this one or the document is hidden, and with a
 * `TimeoutError` when the update callback outruns the API's own 4 s DOM-update budget — which is a
 * statement about how long a Lit flush took, not about a broken app.
 */
const BENIGN_TRANSITION_ABORTS = new Set(['AbortError', 'TimeoutError']);

/**
 * Module-private on purpose. It was briefly exported so the regression test could assert the
 * classifier directly, which made this module's only cross-module consumer of the symbol its own
 * test — the export-for-testing shape the `dead-code` gate exists to keep out. The test reaches the
 * same classification through {@link adoptTransitionPromises}, which is the behaviour that actually
 * matters (silent vs reported), so nothing was lost by narrowing it.
 */
function isBenignTransitionAbort(reason: unknown): boolean {
  const name = (reason as { name?: unknown } | null | undefined)?.name;
  return typeof name === 'string' && BENIGN_TRANSITION_ABORTS.has(name);
}

/**
 * Tempdoc 859 (live console, 2026-08-25) — `SES_UNHANDLED_REJECTION: TimeoutError: Transition was
 * aborted because of timeout in DOM update`, on nearly every Search v3 load and again while a run sat
 * at the budget gate. The cause was structural, not local: `startViewTransition` returns a
 * `ViewTransition` whose `ready` / `finished` / `updateCallbackDone` are live promises, and every call
 * site here DROPPED the handle. A dropped rejected promise is an unhandled rejection, so a transition
 * the browser legitimately skipped surfaced to the reader's console as an app fault — and, through
 * `main.jsx`'s global `unhandledrejection` listener, as an App-level "Unhandled promise rejection".
 *
 * <p>So every face is adopted here, in ONE place both call sites go through: a benign skip is
 * swallowed (the transition is progressive enhancement — worst case, no animation), and anything else
 * still reaches the app's own diagnostic channel rather than being silently eaten. That distinction is
 * the point: this handler must not become a blanket `.catch(() => {})`.
 *
 * <p>ONE REPORT PER TRANSITION (859 review F2). All three faces have to be ADOPTED — an unadopted
 * rejected promise is the unhandled rejection this function exists to stop — but they are three views
 * of one transition, not three failures. A throwing update callback rejects `updateCallbackDone` and
 * takes `ready` and `finished` down with it, all carrying the same reason, so a per-face log would
 * put the same fault in the reader's console three times and make one broken transition look like a
 * storm. The latch is per CALL, so a second, genuinely separate transition still reports.
 */
export function adoptTransitionPromises(transition: unknown): void {
  const handle = transition as ViewTransitionHandle | null | undefined;
  if (handle === null || typeof handle !== 'object') return;
  let reported = false;
  for (const face of [handle.ready, handle.finished, handle.updateCallbackDone] as const) {
    if (typeof face?.catch !== 'function') continue;
    void face.catch((reason: unknown) => {
      if (isBenignTransitionAbort(reason)) return;
      if (reported) return; // the sibling faces are the same failure, seen from another side
      reported = true;
      appLog.error('View transition failed', {
        reason:
          reason instanceof Error ? { name: reason.name, message: reason.message } : String(reason),
      });
    });
  }
}

/** True iff same-document View Transitions are available AND the user has not requested reduced motion. */
export function surfaceTransitionsEnabled(): boolean {
  const doc = document as unknown as ViewTransitionDocument;
  if (typeof doc.startViewTransition !== 'function') return false;
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false;
  return !reduce;
}

/**
 * Animate the surface swap that the host's pending Lit update is about to perform. Call AFTER the
 * synchronous state change that drives the swap. No-op (returns false) when transitions are disabled.
 */
export function startSurfaceTransition(host: { updateComplete: Promise<unknown> }): boolean {
  if (!surfaceTransitionsEnabled()) return false;
  const doc = document as unknown as Required<ViewTransitionDocument>;
  // The handle is adopted, not dropped: this callback awaits a Lit flush plus a frame, so on a slow
  // load it can outrun the API's DOM-update budget and reject `ready` — see adoptTransitionPromises.
  adoptTransitionPromises(
    doc.startViewTransition(async () => {
      await host.updateComplete; // shell render → sets the Stage's surface prop
      // One frame so the child Stage's own (separately-scheduled) update flushes + lays out before the
      // API captures the "after" snapshot.
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    }),
  );
  return true;
}
