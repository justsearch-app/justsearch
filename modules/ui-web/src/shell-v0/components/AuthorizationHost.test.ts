// @vitest-environment happy-dom
/**
 * Tempdoc 550 C3: <jf-authorization-host> + authorizationBroker — the one ceremony surface.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './AuthorizationHost.js';
import type { AuthorizationHost } from './AuthorizationHost.js';
import {
  requestAuthorization,
  setAuthorizationPresenter,
  cancelAuthorizationsForRun,
} from '../operations/authorizationBroker.js';
import {
  requestCapability,
  checkCapability,
  listPendingConsentRequests,
  resolveConsentRequest,
} from '../substrates/consent/index.js';

let host: AuthorizationHost;

beforeEach(() => {
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.appendChild(host);
});

afterEach(() => {
  if (host.parentNode) host.remove();
  setAuthorizationPresenter(null);
  // Test isolation: some tests deliberately leave a plugin consent request pending; drain the global
  // consent state so a later test's fresh host doesn't inherit it as a queued capability ceremony.
  for (const r of listPendingConsentRequests()) resolveConsentRequest(r.id, 'deny');
});

async function settle(): Promise<void> {
  await host.updateComplete;
  await Promise.resolve();
  await host.updateComplete;
}

/**
 * 574 B (remediation) — the decision buttons are <jf-button>; the action fires from the native
 * <button> inside the composed <jf-control>, not a host click. Awaits both render passes, clicks.
 */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('authorizationBroker', () => {
  it('fails closed (deny) when no presenter is registered', async () => {
    setAuthorizationPresenter(null);
    // Tempdoc 807 item 4: still a deny, now marked `failedClosed` — the ceremony was never shown,
    // which is a failure the caller must be able to report, not a refusal to pass over in silence.
    await expect(requestAuthorization({ pendingId: 'pa-1', operationId: 'core.x', gateBehavior: 'INLINE_CONFIRM' })).resolves.toEqual({ approved: false, allowAlways: false, failedClosed: true });
  });
});

describe('<jf-authorization-host> enriched prompt (tempdoc 550 P1)', () => {
  it('renders risk, purpose, args summary, and an irreversibility warning when provided', async () => {
    const decision = requestAuthorization({
      pendingId: 'pa-rich',
      operationId: 'core.bulk-reindex',
      gateBehavior: 'TYPED_CONFIRM',
      riskTier: 'HIGH',
      purpose: 'Rebuild the entire index',
      argsSummary: '{"scope":"all"}',
      undoSupported: false,
    });
    await settle();
    const root = host.shadowRoot!;
    expect(root.querySelector('[data-testid="authorization-risk"]')?.textContent).toContain('HIGH');
    expect(root.querySelector('[data-testid="authorization-purpose"]')?.textContent).toContain('Rebuild');
    expect(root.querySelector('[data-testid="authorization-args"]')?.textContent).toContain('scope');
    expect(root.querySelector('[data-testid="authorization-irreversible"]')).not.toBeNull();
    await activateJfButton(root.querySelector('[data-testid="authorization-deny"]'));
    await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
  });

  it('omits the enriched rows when the prompt carries no context (back-compat)', async () => {
    const decision = requestAuthorization({ pendingId: 'pa-bare', operationId: 'core.x', gateBehavior: 'INLINE_CONFIRM' });
    await settle();
    const root = host.shadowRoot!;
    expect(root.querySelector('[data-testid="authorization-risk"]')).toBeNull();
    expect(root.querySelector('[data-testid="authorization-irreversible"]')).toBeNull();
    await activateJfButton(root.querySelector('[data-testid="authorization-approve"]'));
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
  });
});

describe('<jf-authorization-host> (tempdoc 550 C3)', () => {
  it('registers as the broker presenter and resolves true on Approve (INLINE)', async () => {
    const decision = requestAuthorization({
      pendingId: 'pa-1',
      operationId: 'core.recover-component',
      gateBehavior: 'INLINE_CONFIRM',
    });
    await settle();
    const ceremony = host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]');
    expect(ceremony).not.toBeNull();
    // INLINE: no typed input; Approve is enabled immediately.
    expect(host.shadowRoot!.querySelector('[data-testid="authorization-typed-input"]')).toBeNull();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="authorization-approve"]'));
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
  });

  it('thesis IV: checking "always allow" then Approve resolves allowAlways=true', async () => {
    const decision = requestAuthorization({
      pendingId: 'pa-aa',
      operationId: 'core.x',
      gateBehavior: 'INLINE_CONFIRM',
    });
    await settle();
    const cb = host.shadowRoot!.querySelector(
      '[data-testid="authorization-allow-always"] input',
    ) as HTMLInputElement;
    cb.checked = true;
    cb.dispatchEvent(new Event('change'));
    await settle();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="authorization-approve"]'));
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: true });
  });

  /**
   * Tempdoc 875 C.2 — the backend's DurableGrantStore refuses RiskTier.HIGH before consulting any
   * grant set, so an "always allow" on a HIGH-risk operation is a promise it will not keep. A control
   * that lies is worse than no control: the checkbox is hidden for HIGH and present below it.
   */
  it('875: hides the allow-always checkbox for a HIGH-risk prompt (the backend will not honour it)', async () => {
    const decision = requestAuthorization({
      pendingId: 'pa-high',
      operationId: 'core.file-operations',
      gateBehavior: 'TYPED_CONFIRM',
      riskTier: 'HIGH',
    });
    await settle();
    expect(host.shadowRoot!.querySelector('[data-testid="authorization-allow-always"]')).toBeNull();
    // Right-reason check: the ceremony IS rendered — it is the checkbox that is withheld.
    expect(host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]')).not.toBeNull();
    const input = host.shadowRoot!.querySelector('[data-testid="authorization-typed-input"]') as HTMLInputElement;
    input.value = 'core.file-operations';
    input.dispatchEvent(new Event('input'));
    await settle();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="authorization-approve"]'));
    // Approving a HIGH prompt can never resolve with allowAlways: true.
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
  });

  it('875: keeps the allow-always checkbox for a MEDIUM-risk prompt (the grant is still honoured)', async () => {
    const decision = requestAuthorization({
      pendingId: 'pa-medium',
      operationId: 'core.ingest-files',
      gateBehavior: 'INLINE_CONFIRM',
      riskTier: 'MEDIUM',
    });
    await settle();
    const cb = host.shadowRoot!.querySelector(
      '[data-testid="authorization-allow-always"] input',
    ) as HTMLInputElement;
    expect(cb).not.toBeNull();
    cb.checked = true;
    cb.dispatchEvent(new Event('change'));
    await settle();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="authorization-approve"]'));
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: true });
  });

  it('875: hides the allow-always checkbox when the route cannot carry it (agent tool-call ceremony)', async () => {
    // /api/chat/approve has no `allowAlways` field, so a tick there would be silently dropped.
    // A control that does nothing is the same defect as one that lies — it is not offered.
    const decision = requestAuthorization({
      pendingId: 'pa-agent',
      operationId: 'core.ingest-files',
      gateBehavior: 'INLINE_CONFIRM',
      riskTier: 'MEDIUM',
      allowAlwaysSupported: false,
    });
    await settle();
    expect(
      host.shadowRoot!.querySelector('[data-testid="authorization-allow-always"]'),
    ).toBeNull();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="authorization-approve"]'));
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
  });

  it('resolves false on Deny', async () => {
    const decision = requestAuthorization({ pendingId: 'pa-2', operationId: 'core.x', gateBehavior: 'INLINE_CONFIRM' });
    await settle();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="authorization-deny"]'));
    await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
  });

  it('TYPED gate: Approve is disabled until the op id is typed', async () => {
    const decision = requestAuthorization({
      pendingId: 'pa-3',
      operationId: 'core.bulk-reindex',
      gateBehavior: 'TYPED_CONFIRM',
    });
    await settle();
    // 574 B — approve is a <jf-button>; disabled is its reflected boolean attribute.
    const approve = () => host.shadowRoot!.querySelector('[data-testid="authorization-approve"]')!;
    expect(approve().hasAttribute('disabled')).toBe(true);
    const input = host.shadowRoot!.querySelector('[data-testid="authorization-typed-input"]') as HTMLInputElement;
    input.value = 'core.bulk-reindex';
    input.dispatchEvent(new Event('input'));
    await settle();
    expect(approve().hasAttribute('disabled')).toBe(false);
    await activateJfButton(approve());
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
  });

  it('denies an in-flight prompt when the host is torn down (fail-closed)', async () => {
    const decision = requestAuthorization({ pendingId: 'pa-4', operationId: 'core.x', gateBehavior: 'INLINE_CONFIRM' });
    await settle();
    host.remove();
    // Tempdoc 807 item 4: still a deny (never silently approved), but flagged `failedClosed` so a
    // caller can tell it apart from a human refusal and say so. Round 13's residual was exactly
    // this shape read as a deny: the modal dismissed, nothing dispatched, nothing said.
    await expect(decision).resolves.toEqual({
      approved: false,
      allowAlways: false,
      failedClosed: true,
    });
  });
});

describe('<jf-authorization-host> capability consent (tempdoc 550 G9)', () => {
  it('presents a jf-consent-request through the one ceremony surface and resolves allow-always', async () => {
    const decision = requestCapability({
      contributorId: 'plugin.acme',
      capability: 'fs.read',
      rationale: 'read your notes',
    });
    await settle();
    const ceremony = host.shadowRoot!.querySelector('[data-testid="capability-ceremony"]');
    expect(ceremony).not.toBeNull();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="capability-allow-always"]'));
    await expect(decision).resolves.toBe('allow-always');
    // Federated grant model preserved: the consent substrate recorded the durable grant.
    expect(checkCapability('plugin.acme', 'fs.read')).toBe('allow-always');
  });

  it('resolves deny on the capability Deny button', async () => {
    const decision = requestCapability({ contributorId: 'plugin.bad', capability: 'net.fetch' });
    await settle();
    await activateJfButton(host.shadowRoot!.querySelector('[data-testid="capability-deny"]'));
    await expect(decision).resolves.toBe('deny');
  });

  it('leaves a pending capability request UNTOUCHED on teardown (no persisted deny)', async () => {
    // Regression guard: teardown must NOT resolve a capability request via resolveConsentRequest,
    // which would record + persist a durable deny the user never chose. The request stays pending
    // and is re-presented by a remounted host (the federated grant model owns the decision).
    void requestCapability({ contributorId: 'plugin.x', capability: 'cap.y' });
    await settle();
    expect(listPendingConsentRequests().some(r => r.capability === 'cap.y')).toBe(true);

    host.remove();

    // No decision was recorded/persisted, and the request is still pending (re-presentable).
    expect(checkCapability('plugin.x', 'cap.y')).toBeUndefined();
    expect(listPendingConsentRequests().some(r => r.capability === 'cap.y')).toBe(true);

    // A remounted host re-drains the pending request and presents it again.
    const host2 = document.createElement('jf-authorization-host') as AuthorizationHost;
    document.body.appendChild(host2);
    await host2.updateComplete;
    await Promise.resolve();
    await host2.updateComplete;
    expect(host2.shadowRoot!.querySelector('[data-testid="capability-ceremony"]')).not.toBeNull();
    host2.remove();
  });
});

describe('<jf-authorization-host> run-scoped drain (tempdoc 605 Move 2)', () => {
  function shownOp(): string {
    return host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]')?.textContent ?? '';
  }

  it('a concluding run drains its own ceremony fail-closed (superseded) so the NEXT run surfaces — the M1 fix', async () => {
    // run #1 raises a HIGH-risk ceremony and never gets a human decision (stream ended mid-ceremony).
    const d1 = requestAuthorization({
      pendingId: 'r1-call', operationId: 'core.file-operations',
      gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH', owningRunId: 'run-1',
    });
    await settle();
    expect(shownOp()).toContain('core.file-operations');

    // run #2's ceremony enqueues behind it (pre-fix: would never surface).
    const d2 = requestAuthorization({
      pendingId: 'r2-call', operationId: 'core.delete-everything',
      gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH', owningRunId: 'run-2',
    });
    await settle();
    expect(shownOp()).toContain('core.file-operations'); // still run #1 — blocked

    // run #1 concludes → drain its ceremony. Returns 1; d1 resolves superseded (not a human deny).
    const denied = cancelAuthorizationsForRun('run-1');
    expect(denied).toBe(1);
    await expect(d1).resolves.toEqual({ approved: false, allowAlways: false, superseded: true });

    // run #2's ceremony now SURFACES on the freed host.
    await settle();
    expect(shownOp()).toContain('core.delete-everything');
    expect(shownOp()).not.toContain('core.file-operations');

    // clean up the still-pending run #2 decision (teardown fail-closes it).
    void d2;
  });

  it('does NOT drain another run\'s ceremony or a plugin capability request', async () => {
    const dKeep = requestAuthorization({
      pendingId: 'keep', operationId: 'core.keep', gateBehavior: 'INLINE_CONFIRM', owningRunId: 'run-A',
    });
    void requestCapability({ contributorId: 'plugin.x', capability: 'fs.read' });
    await settle();

    // Conclude a DIFFERENT run — nothing matches.
    expect(cancelAuthorizationsForRun('run-OTHER')).toBe(0);
    await settle();
    // The run-A ceremony is still the active prompt (untouched); the capability request still pending.
    expect(host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]')).not.toBeNull();
    expect(listPendingConsentRequests().some((r) => r.capability === 'fs.read')).toBe(true);
    void dKeep;
  });

  it('a run-less ceremony (no owningRunId, e.g. a Shell effect dispatch) is never drained by a run conclusion', async () => {
    const d = requestAuthorization({ pendingId: 'effect', operationId: 'core.effect', gateBehavior: 'INLINE_CONFIRM' });
    await settle();
    expect(cancelAuthorizationsForRun('run-anything')).toBe(0);
    await settle();
    expect(host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]')).not.toBeNull();
    void d;
  });
});
