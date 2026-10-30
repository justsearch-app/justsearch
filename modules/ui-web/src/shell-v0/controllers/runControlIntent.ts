// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 565 §30 — the DIRECTION authority's ONE control-intent seam for the PER-RUN directives.
 *
 * The human's direction over a live agent run is a single channel of typed directives that act on the
 * run via its controller: `initiate` (the composer prompt → `send`), `interject` (the live-run steer
 * input → `steer`), and `halt` (the session stop → `cancelSession`; the composer's inline cancel does a
 * lighter `abortController.abort()` stream teardown, a distinct mechanism). Every run-control affordance in the agent
 * window dispatches THROUGH this function rather than poking the controller directly — so a hand-rolled
 * steer OR stop that bypasses the channel is exactly what the `steering-surfaces` register-gate forbids
 * (`scripts/ci/check-steering-arbitration.mjs` bans a direct `.steer(`/`cancelSession(` outside this
 * seam). The per-run affordances become projections of one model.
 *
 * Honest seam boundary: `set-posture` (the autonomy dial) is NOT a per-run controller directive — it is
 * the GLOBAL autonomy STORE (the established 561 P-D issuance channel: `setAutonomyLevel` +
 * `subscribeAutonomy`, which the controller then pushes to the live run). Posture is a peer channel the
 * run's gate CONSUMES, written by the dial directly; it is deliberately out of this per-run seam (folding
 * a global store into a per-run dispatch would be the conflation, not the unification).
 */
import type { AgentSessionController } from './AgentSessionController.js';

/** One per-run human directive — the DIRECTION authority's control-intent value (§30). */
export type RunDirective =
  /**
   * Tempdoc 859 §D §2.1 — `effort` is the run's rung NAME (`quick|standard|thorough`), not a token
   * count: the backend owns sizing because only it can see the model's `n_ctx`. Optional, and an
   * omitted rung is Standard by backend policy, not by an FE-side default written here.
   */
  | { readonly kind: 'initiate'; readonly prompt: string; readonly effort?: string }
  | { readonly kind: 'interject'; readonly text: string }
  | { readonly kind: 'halt' }
  /** Tempdoc 577 Ext III — the over-budget remedy: grant the live run more tokens. A per-run
   * directive (it acts on THIS session's budget), so it belongs to this seam like halt does. */
  | { readonly kind: 'raise-budget'; readonly addTokens: number }
  /** Tempdoc 577 §2.12 Move 1 — resume a persisted agent session. Carries the session's declared
   * `resumable` flag so the predicate and the dispatch read the SAME lifecycle fact (the
   * Resume-on-an-evicted-session 500 class). */
  | { readonly kind: 'resume'; readonly sessionId: string; readonly resumable: boolean }
  /** Tempdoc 577 §2.12 Move 2 — resolve the HELD budget gate. CONTINUE is not a value here:
   * raising the budget IS the continue decision (the raise-budget directive). */
  | { readonly kind: 'budget-decision'; readonly decision: 'finalize' | 'stop' }
  /** Tempdoc 577 §2.14 Root II — resolve the HELD context-pressure gate (continue with the large
   * prompt / compact older turns / stop). A per-run directive like budget-decision. */
  | { readonly kind: 'context-decision'; readonly decision: 'continue' | 'summarize' | 'stop' };

/**
 * Tempdoc 577 §2.12 Move 1 — THE per-directive lifecycle predicate: a per-run affordance is a
 * projection of (capability × lifecycle), so the affordance's visibility and the dispatch share ONE
 * predicate. Twice an affordance rendered without consulting the state it acts on (raise-budget on
 * a DONE run → 404; Resume on an evicted session → 500); this seam makes that class structural:
 * render sites call this to decide visibility, and {@link dispatchRunControl} refuses when it fails.
 * The predicate table is mirrored in `governance/steering-surfaces.v1.json` (`lifecyclePredicates`).
 */
export function directiveAvailable(
  ctrl: AgentSessionController,
  directive: RunDirective,
): boolean {
  switch (directive.kind) {
    case 'initiate':
      return true;
    case 'halt':
      return ctrl.runInFlight || (ctrl.stopAttempt?.intent === true
        && ctrl.stopObservationLost && ctrl.terminalOutcome === null);
    case 'interject':
    case 'raise-budget':
      // These act on the LIVE run; the backend evicts finished sessions.
      return ctrl.runInFlight;
    case 'resume':
      // Only a session the record declares resumable, and never over a live run.
      return directive.resumable && !ctrl.runInFlight;
    case 'budget-decision':
      // Only while the run is actually parked at a held budget gate.
      return ctrl.budgetGate != null;
    case 'context-decision':
      // Only while the run is actually parked at a held context-pressure gate.
      return ctrl.contextGate != null;
  }
}

/** The seam's typed refusal — a directive whose lifecycle predicate failed (never an HTTP error). */
export interface RunControlRefusal {
  readonly refused: true;
  readonly kind: RunDirective['kind'];
}

/**
 * Dispatch a per-run control directive through the one seam. Returns a promise that resolves when the
 * directive has been delivered (its EFFECT lands at the run's next step boundary, §30). A directive
 * whose lifecycle predicate fails resolves to a {@link RunControlRefusal} without touching the
 * controller (Move 1: the dispatch cannot outrun the lifecycle).
 */
export function dispatchRunControl(
  ctrl: AgentSessionController,
  directive: RunDirective,
): Promise<unknown> {
  if (!directiveAvailable(ctrl, directive)) {
    return Promise.resolve({ refused: true, kind: directive.kind } satisfies RunControlRefusal);
  }
  switch (directive.kind) {
    case 'initiate':
      return ctrl.send(directive.prompt, directive.effort);
    case 'interject':
      return ctrl.steer(directive.text);
    case 'halt':
      return ctrl.cancelSession();
    case 'raise-budget':
      return ctrl.raiseBudget(directive.addTokens);
    case 'resume':
      return ctrl.resumeSession(directive.sessionId);
    case 'budget-decision':
      return ctrl.resolveBudgetGate(directive.decision);
    case 'context-decision':
      return ctrl.resolveContextGate(directive.decision);
  }
}
