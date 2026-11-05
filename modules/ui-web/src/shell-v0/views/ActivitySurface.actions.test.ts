// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivitySurface } from './ActivitySurface.js';
import { recordEffect, __resetJournalForTest } from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetElicitForTest, listPendingElicits, resolveElicit } from '../substrates/elicit/index.js';
import { __resetMacrosForTest, listMacros } from '../substrates/macros/index.js';
import { __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
let host: ActivitySurface;
function mount() { host = new ActivitySurface(); document.body.append(host); }
beforeEach(() => {
  __resetJournalForTest(); __resetElicitForTest(); __resetMacrosForTest(); __resetActionsForTest(); __resetRecallCursor();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
});
afterEach(() => { host?.remove(); __resetJournalForTest(); vi.unstubAllGlobals(); });
async function flush() { await Promise.resolve(); await host.updateComplete; await Promise.resolve(); }
async function activateJfButton(el: Element | null | undefined) {
  if (!el) throw new Error('Missing action');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}
describe('Activity owns the retired digest actions', () => {
  it('543-fwd #8 — "Undo all AI actions" stages a confirm preview, then confirm undoes', async () => {
    // The undo authority is the journal; the digest visibility derives from the one log. So record
    // the reversible agent effects (journal) AND emit matching ledger rows (the production ingest).
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE, { originator: 'agent' });
    mount();
    await flush();
    const closes: string[] = [];
    const onClosePane = ((e: Event) => closes.push(`pane:${(e as CustomEvent).detail.paneId}`)) as EventListener;
    const onCloseModal = ((e: Event) => closes.push(`modal:${(e as CustomEvent).detail.modalId}`)) as EventListener;
    document.addEventListener('jf-close-pane', onClosePane);
    document.addEventListener('jf-close-modal', onCloseModal);
    try {
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="activity-undo-all"]'));
      await flush();
      const rows = host.shadowRoot?.querySelectorAll('[data-testid="activity-undo-row"]') ?? [];
      expect(rows.length).toBe(2);
      expect(closes).toEqual([]);
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="activity-undo-confirm-yes"]'));
      await flush();
      expect(closes).toEqual(['modal:b', 'pane:a']);
      expect(host.shadowRoot?.querySelector('[data-testid="activity-undo-confirm"]')).toBeNull();
    } finally {
      document.removeEventListener('jf-close-pane', onClosePane);
      document.removeEventListener('jf-close-modal', onCloseModal);
    }
  });
  it('543-fwd #9 — "Save as macro" captures the agent\'s actions into a macro', async () => {
    recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, { originator: 'agent' });
    mount();
    await flush();
    const btn = host.shadowRoot?.querySelector('[data-testid="activity-save-macro"]');
    expect(btn).not.toBeNull();
    await activateJfButton(btn);
    await flush();
    const reqs = listPendingElicits();
    expect(reqs).toHaveLength(1);
    resolveElicit(reqs[0]!.id, { label: 'What the AI did' });
    await flush();
    const macros = listMacros();
    expect(macros).toHaveLength(1);
    expect(macros[0]!.label).toBe('What the AI did');
    expect(macros[0]!.effects.map((e) => e.kind)).toEqual(['invoke-operation', 'navigate']);
  });
  it('577 #16 — the undo-confirm names the irreversible remainder (honest partial undo)', async () => {
    // open-pane is reversible (inverse derivable); toast is irreversible (no inverse). The confirm
    // preview lists the reversible one AND must say the irreversible one will remain.
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'toast', message: 'noted' }, CORE_PROVENANCE, { originator: 'agent' });
    mount();
    await flush();
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="activity-undo-all"]'));
    await flush();
    const rows = host.shadowRoot?.querySelectorAll('[data-testid="activity-undo-row"]') ?? [];
    expect(rows.length, 'only the reversible action is previewed for undo').toBe(1);
    const note = host.shadowRoot?.querySelector('[data-testid="activity-irreversible"]');
    expect(note, 'the irreversible remainder is named').not.toBeNull();
    expect(note?.textContent).toContain("can't be undone");
  });
  it('DE6 confirming the undo preview reverses exactly the previewed set, once, not an action that arrived since', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    mount();
    await flush();
    const closes: string[] = [];
    const onClosePane = ((e: Event) => closes.push(`pane:${(e as CustomEvent).detail.paneId}`)) as EventListener;
    document.addEventListener('jf-close-pane', onClosePane);
    try {
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="activity-undo-all"]'));
      await flush();
      expect(host.shadowRoot?.querySelectorAll('[data-testid="activity-undo-row"]').length).toBe(1);
      // The agent acts again while the preview is open: it was not shown, so it is not undone.
      recordEffect({ kind: 'open-pane', paneId: 'late' }, CORE_PROVENANCE, { originator: 'agent' });
      await flush();
      const yes = host.shadowRoot?.querySelector('[data-testid="activity-undo-confirm-yes"]');
      await activateJfButton(yes);
      await activateJfButton(yes);
      await flush();
      expect(closes).toEqual(['pane:a']);
    } finally {
      document.removeEventListener('jf-close-pane', onClosePane);
    }
  });
});
