// @vitest-environment happy-dom

/**
 * §32 S3 — EffectAuditLog causation-chip render test (R-E3).
 *
 * Drives the REAL component through the REAL substrate (single module
 * instance in the test env — no Vite dual-instance issue): accept a
 * causation-chained sequence, open the log, assert the "↳ #N" chips.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EffectAuditLog } from './EffectAuditLog.js';
import {
  proposeEffectSequence,
  acceptSequence,
  __resetPendingForTest,
} from '../substrates/pending-effects/index.js';
import {
  __resetJournalForTest,
  recordEffect,
  associateOperation,
  exportJournalArchive,
} from '../substrates/effects/index.js';
import { applyEffect } from '../substrates/actions/index.js';
import {
  resolveElicit,
  listPendingElicits,
  __resetElicitForTest,
} from '../substrates/elicit/index.js';
import { listMacros, __resetMacrosForTest } from '../substrates/macros/index.js';
import { __resetActionsForTest } from '../substrates/actions/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

void EffectAuditLog;

let host: HTMLElement;

beforeEach(() => {
  __resetPendingForTest();
  __resetJournalForTest();
  __resetElicitForTest();
  __resetMacrosForTest();
  __resetActionsForTest();
  host = document.createElement('jf-effect-audit-log');
  document.body.appendChild(host);
});

afterEach(() => {
  document.body.removeChild(host);
});

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/**
 * 574 B1 — activate a migrated <jf-button> the way a user does: the action fires
 * from the native <button> inside the composed <jf-control>, two shadow roots deep,
 * NOT from a click on the jf-button host. Awaits both render passes, then clicks.
 */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('<jf-effect-audit-log> causation chip (§32 R-E3)', () => {
  it('renders a "↳ #N" chip for causation-chained entries, none for the root', async () => {
    const { sequenceId } = proposeEffectSequence(
      [
        { effect: { kind: 'navigate', to: '#a' } },
        { effect: { kind: 'navigate', to: '#b' } },
      ],
      CORE_PROVENANCE,
    );
    acceptSequence(sequenceId, (e, p) => applyEffect(e, p));
    await flush();
    // Two 'accepted'-outcome entries; only the 2nd is chained → one chip.
    const chips =
      host.shadowRoot?.querySelectorAll('[data-testid^="causation-"]') ?? [];
    expect(chips.length).toBe(1);
    expect((chips[0] as HTMLElement).textContent?.trim()).toMatch(/↳ #\d+/);
  });

  it('543-fwd #6 — a causation turn renders a collapsible header over its member rows', async () => {
    const a = recordEffect({ kind: 'open-pane', paneId: 'p' }, CORE_PROVENANCE, { originator: 'agent' });
    const b = recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE, { originator: 'agent', causation: a.id });
    await flush();
    const toggle = host.shadowRoot?.querySelector(
      `[data-testid="turn-toggle-${a.id}"]`,
    ) as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    expect(toggle.textContent).toContain('2-step turn');
    // Member rows are visible by default (expanded).
    expect(host.shadowRoot?.querySelector(`[data-entry-id="${b.id}"]`)).not.toBeNull();
    // Collapsing the turn hides its member rows…
    toggle.click();
    await flush();
    expect(host.shadowRoot?.querySelector(`[data-entry-id="${b.id}"]`)).toBeNull();
    // …and re-expanding restores them.
    (host.shadowRoot?.querySelector(`[data-testid="turn-toggle-${a.id}"]`) as HTMLButtonElement).click();
    await flush();
    expect(host.shadowRoot?.querySelector(`[data-entry-id="${b.id}"]`)).not.toBeNull();
  });

  it('543-fwd #7 — "undo turn" reverses the whole causation group via a confirm', async () => {
    const a = recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' }); // root
    const b = recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE, { originator: 'agent', causation: a.id });
    await flush();
    const closes: string[] = [];
    const onPane = ((e: Event) => closes.push(`pane:${(e as CustomEvent).detail.paneId}`)) as EventListener;
    const onModal = ((e: Event) => closes.push(`modal:${(e as CustomEvent).detail.modalId}`)) as EventListener;
    document.addEventListener('jf-close-pane', onPane);
    document.addEventListener('jf-close-modal', onModal);
    try {
      // "undo turn" shows on the chained member (b), not the root.
      await activateJfButton(host.shadowRoot?.querySelector(`[data-testid="undo-turn-${b.id}"]`));
      await flush();
      const rows = host.shadowRoot?.querySelectorAll('[data-testid="undo-turn-row"]') ?? [];
      expect(rows.length).toBe(2); // whole turn (a + b)
      expect(closes).toEqual([]); // nothing reversed yet
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="undo-turn-confirm-yes"]'));
      await flush();
      expect(closes).toEqual(['modal:m', 'pane:a']); // both reversed, newest-first
    } finally {
      document.removeEventListener('jf-close-pane', onPane);
      document.removeEventListener('jf-close-modal', onModal);
    }
  });

  it('543-fwd #19 / 613 §6 — "Export selected" copies an archive and flashes an in-control RECEIPT, not a window toast', async () => {
    const e = recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, { originator: 'agent' });
    (host as unknown as { selectedIds: Set<number> }).selectedIds = new Set([e.id]);
    await (host as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const toast = vi.fn();
    document.addEventListener('jf-advisory-ephemeral', toast as EventListener);
    try {
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="export-archive"]'));
      await flush();
      await (host as unknown as { updateComplete: Promise<unknown> }).updateComplete;
      // Tempdoc 613 §6 — a copy/export confirmation is a RECEIPT (locality: at-control), flashed in the
      // Export button; it must NOT travel the window-toast channel.
      expect(toast).not.toHaveBeenCalled();
      const btn = host.shadowRoot?.querySelector('[data-testid="export-archive"]');
      expect(btn?.textContent).toContain('Exported 1 entr');
    } finally {
      document.removeEventListener('jf-advisory-ephemeral', toast as EventListener);
    }
  });

  it('543-fwd #19 — "Import archive" replays a pasted archive as a macro', async () => {
    // Build a real archive from a couple of effects.
    const a = recordEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, { originator: 'agent' });
    const b = recordEffect({ kind: 'open-pane', paneId: 'p' }, CORE_PROVENANCE, { originator: 'agent' });
    const archive = exportJournalArchive([a, b]);
    await flush();
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="import-archive"]'));
    await flush();
    const reqs = listPendingElicits();
    expect(reqs).toHaveLength(1);
    resolveElicit(reqs[0]!.id, { json: archive, label: 'Replayed session' });
    await flush();
    const macros = listMacros();
    expect(macros).toHaveLength(1);
    expect(macros[0]!.label).toBe('Replayed session');
    expect(macros[0]!.effects.map((eff) => eff.kind)).toEqual(['navigate', 'open-pane']);
  });

  it('543-fwd #6 — clicking "why?" expands the bottom-up causation trace to the root', async () => {
    const root = recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE, { originator: 'agent' });
    const mid = recordEffect({ kind: 'invoke-operation', operationId: 'core_browse_folders' }, CORE_PROVENANCE, { originator: 'agent', causation: root.id });
    const leaf = recordEffect({ kind: 'open-pane', paneId: 'results' }, CORE_PROVENANCE, { originator: 'agent', causation: mid.id });
    await flush();
    // No trace until "why?" is clicked.
    expect(host.shadowRoot?.querySelector(`[data-testid="trace-${leaf.id}"]`)).toBeNull();
    const why = host.shadowRoot?.querySelector(`[data-testid="causation-${leaf.id}"]`) as HTMLButtonElement;
    why.click();
    await flush();
    const steps = host.shadowRoot?.querySelectorAll(`[data-testid="trace-step-${leaf.id}"]`) ?? [];
    // Full lineage root → mid → leaf (3 steps), root first.
    const text = [...steps].map((s) => s.textContent?.replace(/\s+/g, ' ').trim());
    expect(steps.length).toBe(3);
    expect(text[0]).toContain('Run core_search_index'); // root
    expect(text[2]).toContain('Open pane "results"'); // this entry
  });

  it('543-fwd #5 — "restore to here" stages a confirm of later actions, then reverses them', async () => {
    const a = recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE); // after a → reversible
    await flush();
    const closes: string[] = [];
    const onClose = ((e: Event) => closes.push((e as CustomEvent).detail.modalId)) as EventListener;
    document.addEventListener('jf-close-modal', onClose);
    try {
      await activateJfButton(host.shadowRoot?.querySelector(`[data-testid="restore-to-${a.id}"]`));
      await flush();
      // Confirm bar lists the 1 action after `a`; nothing reversed yet.
      const rows = host.shadowRoot?.querySelectorAll('[data-testid="restore-row"]') ?? [];
      expect(rows.length).toBe(1);
      expect(closes).toEqual([]);
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="restore-confirm-yes"]'));
      await flush();
      expect(closes).toEqual(['m']); // the open-modal after `a` was reversed
      expect(host.shadowRoot?.querySelector('[data-testid="restore-confirm"]')).toBeNull();
    } finally {
      document.removeEventListener('jf-close-modal', onClose);
    }
  });

  it('DE6 — "restore to here" says what cannot be undone and reverses exactly the previewed actions', async () => {
    const a = recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE);
    recordEffect({ kind: 'toast', message: 'noted' }, CORE_PROVENANCE); // irreversible
    await flush();
    const closes: string[] = [];
    const onClose = ((e: Event) => closes.push((e as CustomEvent).detail.modalId)) as EventListener;
    document.addEventListener('jf-close-modal', onClose);
    try {
      await activateJfButton(host.shadowRoot?.querySelector(`[data-testid="restore-to-${a.id}"]`));
      await flush();
      expect(host.shadowRoot?.querySelector('[data-testid="undo-irreversible"]')?.textContent?.replace(/\s+/g, ' '))
        .toContain("1 other action can't be undone and will remain.");
      // Something new happens while the preview is open: it was not previewed, so it stays.
      recordEffect({ kind: 'open-modal', modalId: 'late' }, CORE_PROVENANCE);
      await flush();
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="restore-confirm-yes"]'));
      await flush();
      expect(closes).toEqual(['m']);
    } finally {
      document.removeEventListener('jf-close-modal', onClose);
    }
  });

  it('§32 U2 — renders an Undo button for an undoable entry; click dispatches undo-operation', async () => {
    const entry = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.file-operations' },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );
    associateOperation(entry.id, { executionId: 'exec-77' });
    await flush();
    const undoBtn = host.shadowRoot?.querySelector(
      `[data-testid="undo-op-${entry.id}"]`,
    ) as HTMLButtonElement | null;
    expect(undoBtn).not.toBeNull();

    const l = vi.fn();
    document.addEventListener('jf-undo-operation', l as EventListener);
    try {
      await activateJfButton(undoBtn);
      expect(l).toHaveBeenCalledTimes(1);
      expect((l.mock.calls[0]![0] as CustomEvent).detail).toMatchObject({
        operationId: 'core.file-operations',
        executionId: 'exec-77',
      });
    } finally {
      document.removeEventListener('jf-undo-operation', l as EventListener);
    }
  });

  it('543-fwd #4 — "Undo last AI action" fires a labelled confirmation toast naming the reversed effect', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'knowledge' }, CORE_PROVENANCE, {
      originator: 'agent',
    });
    await flush();
    const toast = vi.fn();
    document.addEventListener('jf-advisory-ephemeral', toast as EventListener);
    try {
      const btn = host.shadowRoot?.querySelector(
        '[data-testid="undo-last-agent"]',
      ) as HTMLButtonElement;
      await activateJfButton(btn);
      await flush();
      const messages = toast.mock.calls.map(
        (c) => (c[0] as CustomEvent).detail.message as string,
      );
      expect(messages).toContain('Undid: Open pane "knowledge"');
    } finally {
      document.removeEventListener('jf-advisory-ephemeral', toast as EventListener);
    }
  });

  it('543-fwd #8 — "Undo all AI actions" stages a confirm preview; no undo until confirmed', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE, { originator: 'agent' });
    await flush();
    const toast = vi.fn();
    document.addEventListener('jf-advisory-ephemeral', toast as EventListener);
    try {
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="undo-all-agent"]'));
      await flush();
      // Confirm bar appears listing the 2 reversible agent actions; nothing undone yet.
      const rows = host.shadowRoot?.querySelectorAll('[data-testid="undo-all-row"]') ?? [];
      expect(rows.length).toBe(2);
      const rowText = [...rows].map((r) => r.textContent?.trim());
      expect(rowText).toContain('Open dialog "b"');
      expect(toast).not.toHaveBeenCalled();

      // Confirming fires the inverses + the count toast.
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="undo-all-confirm-yes"]'));
      await flush();
      const messages = toast.mock.calls.map((c) => (c[0] as CustomEvent).detail.message as string);
      expect(messages).toContain('Undid 2 AI actions');
      // Confirm bar dismissed.
      expect(host.shadowRoot?.querySelector('[data-testid="undo-all-confirm"]')).toBeNull();
    } finally {
      document.removeEventListener('jf-advisory-ephemeral', toast as EventListener);
    }
  });


  it('543-fwd #3 — AI-undo buttons soft-unavailable when no reversible agent entries; operable + counted otherwise', async () => {
    // Tempdoc 596 — the empty-state gate is now TYPED availability (aria-disabled + a reachable reason),
    // not a hard native `disabled`. Intent unchanged: nothing to undo → not actionable.
    const innerAriaDisabled = async (
      el: HTMLElement & { updateComplete: Promise<unknown> },
    ): Promise<string | null> => {
      await el.updateComplete;
      const c = el.shadowRoot!.querySelector('jf-control') as HTMLElement & {
        updateComplete: Promise<unknown>;
      };
      await c.updateComplete;
      return c.shadowRoot!.querySelector('button')!.getAttribute('aria-disabled');
    };
    // Empty journal (reset in beforeEach): the AI has nothing to undo.
    await flush();
    type JfBtn = HTMLElement & { updateComplete: Promise<unknown> };
    const undoAll = () => host.shadowRoot?.querySelector('[data-testid="undo-all-agent"]') as JfBtn;
    const undoLast = () => host.shadowRoot?.querySelector('[data-testid="undo-last-agent"]') as JfBtn;
    expect(await innerAriaDisabled(undoAll())).toBe('true');
    expect(await innerAriaDisabled(undoLast())).toBe('true');

    // A reversible agent action makes them operable and surfaces the count.
    recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE, { originator: 'agent' });
    await flush();
    expect(await innerAriaDisabled(undoAll())).toBeNull();
    expect(await innerAriaDisabled(undoLast())).toBeNull();
    expect(undoAll().textContent).toContain('(1)');
  });

  it('543-fwd #8 — Cancel dismisses the confirm without undoing', async () => {
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    await flush();
    const applied = vi.fn();
    document.addEventListener('jf-close-pane', applied as EventListener);
    try {
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="undo-all-agent"]'));
      await flush();
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="undo-all-confirm-cancel"]'));
      await flush();
      expect(host.shadowRoot?.querySelector('[data-testid="undo-all-confirm"]')).toBeNull();
      expect(applied).not.toHaveBeenCalled();
    } finally {
      document.removeEventListener('jf-close-pane', applied as EventListener);
    }
  });

  it('§32 U2 — marking an entry undoable AFTER the log is open re-renders the Undo button', async () => {
    // Proves the side-map's listener re-fire path (associateOperation →
    // journal listeners → audit-log re-render) and the capture→render
    // integration the Shell jf-invoke-operation .then() callback drives.
    const entry = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.file-operations' },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );
    await flush();
    const sel = `[data-testid="undo-op-${entry.id}"]`;
    expect(host.shadowRoot?.querySelector(sel)).toBeNull(); // not yet undoable
    associateOperation(entry.id, { executionId: 'exec-after' });
    await flush();
    expect(host.shadowRoot?.querySelector(sel)).not.toBeNull(); // re-rendered
  });

  it('543-fwd FE-tail — a genuinely empty journal shows the "nothing yet" copy, not the filter copy', async () => {
    // Journal reset in beforeEach → 0 entries, no filter active.
    await flush();
    const empty = host.shadowRoot?.querySelector('.empty');
    expect(empty).not.toBeNull();
    expect(empty?.textContent ?? '').toMatch(/No actions recorded yet/i);
    expect(empty?.textContent ?? '').not.toMatch(/match the current filter/i);
  });

  it('543-fwd FE-tail — a filter that hides every entry shows the filter copy, not the "nothing yet" copy', async () => {
    recordEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, {
      originator: 'user',
    });
    const h = host as HTMLElement & { open: boolean; originator: string };
    h.open = true;
    h.originator = 'agent'; // hides the lone 'user' entry → filtered empty, total > 0
    await flush();
    const empty = host.shadowRoot?.querySelector('.empty');
    expect(empty).not.toBeNull();
    expect(empty?.textContent ?? '').toMatch(/match the current filter/i);
    expect(empty?.textContent ?? '').not.toMatch(/No actions recorded yet/i);
  });

});
