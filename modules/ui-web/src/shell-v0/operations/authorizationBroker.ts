// SPDX-License-Identifier: Apache-2.0
/**
 * authorizationBroker — the seam between a gated dispatch and the one FE ceremony surface
 * (tempdoc 550 C3, invoke-first). When an invocation hits the backend's 428 trust gate, the
 * dispatcher asks the broker to obtain a human decision for the backend-issued
 * {@link AuthorizationPrompt}; the broker delegates to the registered presenter (the
 * `<jf-authorization-host>` modal). One presenter, all gated paths — instead of a bespoke
 * confirm UX per surface.
 *
 * Fail-closed: with no presenter registered (no host mounted), {@link requestAuthorization}
 * resolves `false` (deny) — a gated action is never silently approved just because the UI
 * wasn't there to ask.
 *
 * <p><b>Mount coupling (load-bearing).</b> The gated paths that depend on the broker — the
 * agent tool-call approval (`AgentSessionController`) and the gated effect/emission dispatch
 * (`Shell`'s invoke-operation listener) — require a `<jf-authorization-host>` to be mounted,
 * else fail-closed denies them. The Shell mounts exactly one host in its chrome, alongside
 * the surfaces that originate these paths; keep that invariant if either the host or those
 * surfaces are ever relocated. (This replaced the old self-contained inline approve/reject
 * buttons on each tool-call card — the trade is one consistent ceremony for a mount
 * dependency.)
 */

/** What the ceremony needs to render a consistent approve/deny prompt for a gated action. */
export interface AuthorizationPrompt {
  /** Backend-issued id; the only thing the approval references. */
  readonly pendingId: string;
  /** The gated operation, for display + the typed-confirm word. */
  readonly operationId: string;
  /** 'INLINE_CONFIRM' (one-click) | 'TYPED_CONFIRM' (type the op id) — drives the ceremony. */
  readonly gateBehavior: string;
  // Tempdoc 550 P1: optional decision context the ceremony shows so the user judges the action,
  // not just its id. All sourced from what the gate already knows (PendingAuthorization / the
  // tool-call event); absent fields simply aren't rendered.
  /** 'LOW' | 'MEDIUM' | 'HIGH' — the operation's declared risk. */
  readonly riskTier?: string;
  /** Whether the action is reversible (undo-supported) — drives a "can't be undone" warning. */
  readonly undoSupported?: boolean;
  /** Display-safe raw-argument summary or complete bounded frozen target/scope metadata. */
  readonly argsSummary?: string;
  /** Human rationale / message: why this needs approval. */
  readonly purpose?: string;
  /**
   * Tempdoc 875 — whether this ceremony's approval route can actually record an "allow always"
   * durable grant. Absent = yes (the `/api/authorizations/approve` route, which carries
   * `allowAlways`). The agent tool-call route posts to `/api/chat/{approve,reject}`, which has no
   * `allowAlways` field, so it passes `false` and the checkbox is not offered rather than rendered
   * and silently dropped. A control that does nothing is the same defect as a control that lies.
   */
  readonly allowAlwaysSupported?: boolean;
  /**
   * Tempdoc 655 — the calling MCP client's self-reported name (from its `initialize` handshake's
   * `clientInfo`), when this gate fired via MCP. Display-only, transparency-only — absent for a
   * browser-originated gate or an MCP client that omitted `clientInfo`; never used for any trust
   * decision.
   */
  readonly requestedBy?: string;
  /**
   * Tempdoc 605 — the id of the run that issued this gated call (the agent run's sessionId). The
   * ceremony "dies with its run" (577 Move 1 / 550 Thesis II): when that run reaches a terminal,
   * {@link cancelAuthorizationsForRun} fail-closed-denies its still-open ceremonies so the NEXT
   * run's ceremony can surface. Absent ⇒ a run-less gated dispatch (e.g. a Shell effect/emission);
   * such ceremonies are never drained by a run conclusion.
   */
  readonly owningRunId?: string;
}

/**
 * The human's decision on a gated action. Tempdoc 550 thesis IV: besides approve/deny, the user
 * may choose "allow always" — recorded as a durable grant so future invocations auto-approve.
 */
export interface AuthorizationDecision {
  readonly approved: boolean;
  readonly allowAlways: boolean;
  /**
   * Tempdoc 605 — set when the decision was auto-denied because the OWNING RUN concluded
   * (the Move 2 drain), not because the human denied. The caller skips the backend reject POST
   * (the run is already gone) and lets {@link cancelAuthorizationsForRun}'s caller surface the one
   * legible "previous run's pending action was cancelled" notice instead of a per-ceremony error.
   */
  readonly superseded?: boolean;
  /**
   * Tempdoc 807 item 4 — set when the deny is a FAIL-CLOSED outcome rather than a human decision:
   * no presenter was mounted when the ceremony was requested, or a mounted host was torn down with
   * the ceremony still open. Both resolve `approved: false` by design (550: a gated action is never
   * silently approved because the UI wasn't there to ask) — but they are failures, not refusals,
   * and a caller that treats them as "the user said no" tells the user nothing. Sandbox round 13's
   * residual finding: the modal dismissed, nothing dispatched, and no visible error said why.
   */
  readonly failedClosed?: boolean;
}

/** Resolves to the user's {@link AuthorizationDecision}. */
export type AuthorizationPresenter = (prompt: AuthorizationPrompt) => Promise<AuthorizationDecision>;

/**
 * Tempdoc 605 — fail-closed-deny every still-open AUTHORIZE ceremony owned by `runId` and return
 * how many were denied. The host implements it (capability-consent and other-owner items are left
 * untouched). Lets a concluding run satisfy the liveness invariant (550 Thesis II) so a stuck
 * predecessor ceremony can no longer block the next run's ceremony from surfacing.
 */
export type AuthorizationCanceller = (runId: string) => number;

let presenter: AuthorizationPresenter | null = null;
let canceller: AuthorizationCanceller | null = null;

/**
 * Register the ceremony presenter (the mounted `<jf-authorization-host>`). Passing null
 * unregisters (on host teardown). Last-registered wins — there is one host at a time.
 */
export function setAuthorizationPresenter(fn: AuthorizationPresenter | null): void {
  presenter = fn;
}

/**
 * Tempdoc 605 — register the host's run-scoped ceremony canceller (mounted host) / unregister on
 * teardown. Mirrors {@link setAuthorizationPresenter}; one host at a time.
 */
export function setAuthorizationCanceller(fn: AuthorizationCanceller | null): void {
  canceller = fn;
}

/**
 * Tempdoc 605 — drain a concluding run's open ceremonies (fail-closed). Returns the count denied,
 * so the caller can surface a single legible notice only when ≥1 was cancelled. No-op (0) when no
 * host is mounted — its teardown already fail-closes any pending ceremony.
 */
export function cancelAuthorizationsForRun(runId: string): number {
  if (!canceller || !runId) return 0;
  return canceller(runId);
}

/**
 * Ask the human to decide a gated action. Delegates to the registered presenter; resolves
 * `false` (deny, fail-closed) when no presenter is mounted.
 */
export async function requestAuthorization(
  prompt: AuthorizationPrompt,
): Promise<AuthorizationDecision> {
  if (!presenter) {
    // Fail-closed: no host mounted. Flagged (tempdoc 807 item 4) so a caller can tell this apart
    // from a human deny and say so, instead of ending the ceremony in silence.
    return { approved: false, allowAlways: false, failedClosed: true };
  }
  return presenter(prompt);
}
