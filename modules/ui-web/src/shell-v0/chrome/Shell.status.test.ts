// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './Shell.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';

afterEach(() => {
  document.body.replaceChildren(); __resetAiStateForTest(); __resetBootstrapForTest();
  __resetUserConfigForTest(); vi.unstubAllGlobals();
});
it('ST10 the frame has one status destination and no retired presenter mounts', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  const shell = document.createElement('jf-shell') as HTMLElement & { updateComplete: Promise<unknown> };
  document.body.append(shell); await shell.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
  expect(shell.shadowRoot!.querySelectorAll('jf-status-pill'), 'one always-mounted title-bar status owner').toHaveLength(1);
  expect(shell.shadowRoot!.querySelectorAll('jf-status-deck,jf-app-update-banner,jf-advisory-rail-badge,jf-advisory-toast-host,jf-advisory-inbox-drawer,jf-ai-activity-digest,jf-task-list,jf-pending-effect-queue,jf-agent-activity-panel,jf-plugin-error-overlay,jf-resolution-toast,jf-indexing-overlay-host')).toHaveLength(0);
  const pill = shell.shadowRoot!.querySelector('jf-status-pill') as HTMLElement & { updateComplete: Promise<unknown> };
  await pill.updateComplete;
  expect(pill.shadowRoot!.querySelectorAll('jf-status-panel')).toHaveLength(1);
});
it('ref-07 the shell Undo command (Ctrl+Z) reverses the undo notice showing in the pill, not while typing, after a shell remount too', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  const { getAction } = await import('../substrates/actions/index.js');
  const { emitEphemeralToast } = await import('../components/advisory/ephemeralToast.js');
  for (const round of [1, 2]) {
    const shell = document.createElement('jf-shell') as HTMLElement & { updateComplete: Promise<unknown> };
    document.body.append(shell); await shell.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
    const undo = vi.fn();
    emitEphemeralToast({ message: `Skip rule added ${round}`, actionLabel: 'Undo', onAction: undo });
    await new Promise(resolve => setTimeout(resolve, 0));
    // While typing, Undo keeps its text meaning and the notice is left alone (C8).
    const field = document.createElement('input'); document.body.append(field); field.focus();
    getAction('core.action.shell.undo')!.handler!({}, null);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(undo, `round ${round}: typing`).not.toHaveBeenCalled();
    field.remove();
    // The typing fallback posted its own receipt; a fresh undo notice takes the slot again.
    emitEphemeralToast({ message: `Skip rule added again ${round}`, actionLabel: 'Undo', onAction: undo });
    await new Promise(resolve => setTimeout(resolve, 0));
    getAction('core.action.shell.undo')!.handler!({}, null);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(undo, `round ${round}`).toHaveBeenCalledTimes(1);
    shell.remove();
  }
});
it.each(['lightweight', 'typed', 'capability'] as const)('C12 a %s ceremony opens on arrival in the shell, is listed in Needs you, and Review returns to it', async (kind) => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  const { requestAuthorization } = await import('../operations/authorizationBroker.js');
  const { requestCapability, listPendingConsentRequests, resolveConsentRequest } = await import('../substrates/consent/index.js');
  type Updating = HTMLElement & { updateComplete: Promise<unknown> };
  const shell = document.createElement('jf-shell') as Updating;
  document.body.append(shell); await shell.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
  const host = shell.shadowRoot!.querySelector('jf-authorization-host') as Updating;
  const pill = shell.shadowRoot!.querySelector('jf-status-pill') as Updating & { status: { refresh(): void } };
  const settle = async () => {
    await host.updateComplete; await Promise.resolve(); await host.updateComplete;
    pill.status.refresh(); await pill.updateComplete;
    await (pill.shadowRoot!.querySelector('jf-status-panel') as Updating).updateComplete;
  };
  try {
    const decision = kind === 'capability'
      ? requestCapability({ contributorId: 'plugin.arrival', capability: 'net.fetch' })
      : requestAuthorization({ pendingId: 'arrival', operationId: 'core.arrival',
        gateBehavior: kind === 'typed' ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM' });
    await settle();
    const dialog = host.shadowRoot!.querySelector('dialog')!;
    expect(dialog.open, 'opens on arrival, before any Review').toBe(true);
    expect(dialog.getAttribute('data-testid')).toBe(kind === 'capability' ? 'capability-ceremony' : 'authorization-ceremony');
    expect(dialog.classList.contains('lightweight')).toBe(kind === 'lightweight');
    const { modalOwnsFocus } = await import('../primitives/modality.js');
    expect(modalOwnsFocus(), 'typed and capability ceremonies are modal').toBe(kind !== 'lightweight');
    const panel = pill.shadowRoot!.querySelector('jf-status-panel')!;
    const row = panel.shadowRoot!.querySelector(kind === 'capability' ? '[data-fact^="consent:"]' : '[data-fact="authorization:arrival"]');
    expect(row, 'listed in Needs you').not.toBeNull();
    // Dismissed by some other path: Review reopens the same ceremony and focuses it.
    dialog.close(); expect(dialog.open).toBe(false);
    [...row!.querySelectorAll('button')].find(b => b.textContent!.trim().startsWith('Review'))!.click();
    await settle();
    expect(host.shadowRoot!.querySelector('dialog')).toBe(dialog);
    expect(dialog.open, 'Review reopens it').toBe(true);
    expect(modalOwnsFocus()).toBe(kind !== 'lightweight');
    const focused = host.shadowRoot!.activeElement;
    expect(focused && dialog.contains(focused), 'Review focuses it').toBe(true);
    expect(focused?.getAttribute('data-testid')).toBe(kind === 'typed' ? 'authorization-typed-input' : kind === 'capability' ? 'capability-deny' : 'authorization-deny');
    void decision;
  } finally {
    for (const request of listPendingConsentRequests()) resolveConsentRequest(request.id, 'deny');
    shell.remove();
  }
});
