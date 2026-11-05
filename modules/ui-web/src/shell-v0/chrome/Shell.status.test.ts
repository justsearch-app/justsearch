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
  // DE6 — an undo notice is a real reversal offered by the person's own action (ownReversals).
  const { offerReversal, __resetOwnReversalsForTest } = await import('../state/ownReversals.js');
  const emitUndo = (message: string, undo: () => void) =>
    offerReversal({ message, reverse: async () => { undo(); }, reversed: 'Undone' });
  for (const round of [1, 2]) {
    __resetOwnReversalsForTest();
    const shell = document.createElement('jf-shell') as HTMLElement & { updateComplete: Promise<unknown> };
    document.body.append(shell); await shell.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
    const undo = vi.fn();
    emitUndo(`Skip rule added ${round}`, undo);
    await new Promise(resolve => setTimeout(resolve, 0));
    // While typing, Undo keeps its text meaning and the notice is left alone (C8).
    const field = document.createElement('input'); document.body.append(field); field.focus();
    getAction('core.action.shell.undo')!.handler!({}, null);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(undo, `round ${round}: typing`).not.toHaveBeenCalled();
    field.remove();
    // The typing fallback posted its own receipt; a fresh undo notice takes the slot again.
    emitUndo(`Skip rule added again ${round}`, undo);
    await new Promise(resolve => setTimeout(resolve, 0));
    getAction('core.action.shell.undo')!.handler!({}, null);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(undo, `round ${round}`).toHaveBeenCalledTimes(1);
    shell.remove();
  }
});
it('DE6 after the undo notice has gone, Ctrl+Z runs the latest own reversal unless the journal holds a newer reversible effect', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  const { getAction } = await import('../substrates/actions/index.js');
  const { offerReversal, __resetOwnReversalsForTest } = await import('../state/ownReversals.js');
  const { recordEffect, __resetJournalForTest } = await import('../substrates/effects/index.js');
  const { CORE_PROVENANCE } = await import('../primitives/provenance.js');
  __resetOwnReversalsForTest(); __resetJournalForTest();
  const shell = document.createElement('jf-shell') as HTMLElement & { updateComplete: Promise<unknown> };
  document.body.append(shell); await shell.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 30)); await shell.updateComplete;
  const pill = shell.shadowRoot!.querySelector('jf-status-pill') as HTMLElement & { status: { transientKey: string | null } };
  const tick = () => new Promise(resolve => setTimeout(resolve, 5));
  const first = vi.fn();
  offerReversal({ message: 'Skip rule added', reverse: async () => { first(); }, reversed: 'Undone' });
  await tick();
  pill.status.transientKey = null; // the notice's few seconds are over
  getAction('core.action.shell.undo')!.handler!({}, null); await tick();
  expect(first).toHaveBeenCalledTimes(1);
  // A newer reversible journal effect is the last action: Ctrl+Z reverses it, not the older reversal.
  const second = vi.fn();
  offerReversal({ message: 'Skip rule removed', reverse: async () => { second(); }, reversed: 'Undone' });
  await tick();
  pill.status.transientKey = null;
  recordEffect({ kind: 'open-pane', paneId: 'details' }, CORE_PROVENANCE);
  getAction('core.action.shell.undo')!.handler!({}, null); await tick();
  expect(second).not.toHaveBeenCalled();
  // Then the reversal is next.
  getAction('core.action.shell.undo')!.handler!({}, null); await tick();
  expect(second).toHaveBeenCalledTimes(1);
  shell.remove(); __resetOwnReversalsForTest(); __resetJournalForTest();
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
    // Closed natively without a decision: modality and scroll lock end, the request stays listed,
    // and the real pill opens the panel, whose Review reopens the same ceremony and focuses it.
    dialog.close(); expect(dialog.open).toBe(false);
    await settle();
    expect(modalOwnsFocus(), 'a native close releases modality').toBe(false);
    expect(document.documentElement.style.overflow, 'and the scroll lock').toBe('');
    expect(panel.shadowRoot!.querySelector(kind === 'capability' ? '[data-fact^="consent:"]' : '[data-fact="authorization:arrival"]'),
      'still listed in Needs you').not.toBeNull();
    const panelHidden = () => pill.shadowRoot!.querySelector('jf-status-panel')!.hasAttribute('hidden');
    expect(panelHidden()).toBe(true);
    pill.shadowRoot!.querySelector<HTMLButtonElement>('.trigger')!.click();
    await settle();
    expect(panelHidden(), 'the pill opens the panel').toBe(false);
    const review = [...panel.shadowRoot!.querySelector(kind === 'capability' ? '[data-fact^="consent:"]' : '[data-fact="authorization:arrival"]')!
      .querySelectorAll('button')].find(b => b.textContent!.trim().startsWith('Review'))!;
    review.click();
    await settle();
    expect(panelHidden(), 'Review closes the panel').toBe(true);
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
