// @vitest-environment happy-dom

/**
 * Effect Journal substrate unit tests — Tempdoc 543 §13.2.2.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  recordEffect,
  listJournal,
  journalEventId,
  listJournalFor,
  getJournalSize,
  undoLastEffect,
  subscribeJournal,
  restoreJournalFromStorage,
  syncBackendOperationInverses,
  __clearBackendOperationInversesForTest,
  __resetJournalForTest,
} from './index.js';
import { CORE_PROVENANCE, makePluginProvenance } from '../../primitives/provenance.js';
import type { Effect } from '../effect.js';

beforeEach(() => {
  __resetJournalForTest();
  __clearBackendOperationInversesForTest();
});

describe('recordEffect + listJournal (§13.2.2)', () => {
  it('appends an entry with the given effect + provenance + ISO timestamp', () => {
    const eff: Effect = { kind: 'navigate', to: '#a' };
    const entry = recordEffect(eff, CORE_PROVENANCE);
    expect(entry.effect).toBe(eff);
    expect(entry.invokedBy).toBe(CORE_PROVENANCE);
    expect(entry.invokedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(listJournal()).toHaveLength(1);
    expect(getJournalSize()).toBe(1);
  });

  it('assigns monotonic ids starting at 1', () => {
    const a = recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    const b = recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    expect(a.id).toBe(1);
    expect(b.id).toBe(2);
  });

  it('notifies subscribers on append', () => {
    const listener = vi.fn();
    subscribeJournal(listener);
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('listJournal returns a defensive copy (mutating it does not affect store)', () => {
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    const snap = listJournal() as JournalEntry[];
    snap.length = 0;
    expect(getJournalSize()).toBe(1);
  });
});

describe('Inverse derivation (§13.2.2)', () => {
  it('noop → null inverse (irreversible)', () => {
    const e = recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    expect(e.inverse).toBeNull();
  });

  it('open-pane ↔ close-pane symmetric inverse', () => {
    const opened = recordEffect(
      { kind: 'open-pane', paneId: 'inspector' },
      CORE_PROVENANCE,
    );
    expect(opened.inverse).toEqual({ kind: 'close-pane', paneId: 'inspector' });
    const closed = recordEffect(
      { kind: 'close-pane', paneId: 'inspector' },
      CORE_PROVENANCE,
    );
    expect(closed.inverse).toEqual({ kind: 'open-pane', paneId: 'inspector' });
  });

  it('toast → null inverse (advisory, auto-dismisses)', () => {
    const e = recordEffect(
      { kind: 'toast', message: 'hi' },
      CORE_PROVENANCE,
    );
    expect(e.inverse).toBeNull();
  });

  it('invoke-operation → null inverse when no declared inverse (§32 R-P2)', () => {
    // R-P2: previously returned a misleading toast("Undo: …") that fired on
    // undo WITHOUT reversing anything. Now null, so the undo cursor walks
    // past it. Backend-undoable ops are reversed via the executionId bridge
    // (§32 U2), not via an Effect inverse.
    const e = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.reindex' },
      CORE_PROVENANCE,
    );
    expect(e.inverse).toBeNull();
  });

  it('invoke-operation → backend-declared inverse materializes as invoke-operation (tempdoc 560 WS3)', () => {
    // The backend Operation declares OperationPolicy.inverseOperationId; the
    // catalog bridge syncs it; deriveInverse materializes the sibling op id into
    // an invoke-operation inverse Effect so undo re-issues the declared inverse.
    syncBackendOperationInverses([
      { id: 'core.add-watched-root', policy: { inverseOperationId: 'core.remove-watched-root' } },
      { id: 'core.remove-watched-root', policy: { inverseOperationId: 'core.add-watched-root' } },
      { id: 'core.reindex', policy: { inverseOperationId: null } },
    ]);
    const added = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.add-watched-root' },
      CORE_PROVENANCE,
    );
    expect(added.inverse).toEqual({
      kind: 'invoke-operation',
      operationId: 'core.remove-watched-root',
    });
    // An op declaring a null inverse stays irreversible at the Effect-inverse layer.
    const reindexed = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.reindex' },
      CORE_PROVENANCE,
    );
    expect(reindexed.inverse).toBeNull();
  });

  it('navigate captures previous target as inverse (sequence test)', () => {
    // First navigate primes _previousNavTarget but inverse may be null
    // when no `before` exists. Second navigate has a real before.
    recordEffect({ kind: 'navigate', to: '#first' }, CORE_PROVENANCE);
    const second = recordEffect(
      { kind: 'navigate', to: '#second' },
      CORE_PROVENANCE,
    );
    expect(second.inverse?.kind).toBe('navigate');
    if (second.inverse?.kind === 'navigate') {
      expect(second.inverse.to).toBe('#first');
    }
  });
});

describe('Undo cursor (§13.2.2)', () => {
  it('undo walks back over irreversible entries', () => {
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE);
    recordEffect({ kind: 'toast', message: 'x' }, CORE_PROVENANCE); // null inverse
    const applied = vi.fn<(e: Effect) => void>();
    const inv = undoLastEffect(applied);
    // Toast had null inverse — cursor walks past it to the navigate inverse.
    expect(inv?.kind).toBe('navigate');
    if (inv?.kind === 'navigate') {
      expect(inv.to).toBe('#a');
    }
    expect(applied).toHaveBeenCalledTimes(1);
  });

  it('returns null when journal is empty', () => {
    expect(undoLastEffect()).toBeNull();
  });

  it('successive undos walk further back', () => {
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE);
    recordEffect({ kind: 'navigate', to: '#c' }, CORE_PROVENANCE);
    // 3rd navigate inverse points to #b; 2nd points to #a; 1st has no before.
    const first = undoLastEffect();
    const second = undoLastEffect();
    expect(first?.kind).toBe('navigate');
    expect(second?.kind).toBe('navigate');
  });

  // Regression (observations.md): rejectPending records the proposed effect
  // tagged pendingOutcome:'rejected' WITH a derivable inverse, so the global
  // undo cursor must walk past it — the effect was never dispatched.
  it('undo walks past vetoed (pendingOutcome:rejected) entries', () => {
    // Real (dispatched) open-pane — the canonical undoable record.
    recordEffect({ kind: 'open-pane', paneId: 'real' }, CORE_PROVENANCE, { originator: 'user' });
    // Vetoed open-pane: has a derivable inverse (close-pane) but never rendered.
    recordEffect({ kind: 'open-pane', paneId: 'vetoed' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    const applied = vi.fn<(e: Effect) => void>();
    const inv = undoLastEffect(applied);
    // Skips the vetoed close-pane; lands on the real one.
    expect(inv).toEqual({ kind: 'close-pane', paneId: 'real' });
    expect(applied).toHaveBeenCalledTimes(1);
    expect(applied).toHaveBeenCalledWith({ kind: 'close-pane', paneId: 'real' });
  });

  // Regression (observations.md): an accepted reversible pending records BOTH
  // a markerless dispatch (via acceptPending's applyFn) AND an 'accepted'
  // marker, each carrying the same derivable inverse. Global undo must reverse
  // it exactly once — via the dispatch — and skip the duplicate marker.
  it('undo does not double-apply an accepted pending (skips the marker)', () => {
    recordEffect({ kind: 'open-pane', paneId: 'x' }, CORE_PROVENANCE, { originator: 'user' }); // dispatch (markerless)
    recordEffect({ kind: 'open-pane', paneId: 'x' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'accepted' }); // lifecycle marker
    const applied = vi.fn<(e: Effect) => void>();
    const first = undoLastEffect(applied);
    const second = undoLastEffect(applied);
    expect(first).toEqual({ kind: 'close-pane', paneId: 'x' });
    expect(second).toBeNull(); // marker skipped — no phantom second undo
    expect(applied).toHaveBeenCalledTimes(1); // reversed exactly once, not twice
  });
});

describe('Per-Provenance filtering (§13.5 rule 2)', () => {
  it('listJournalFor returns only entries by that contributor', () => {
    const plug = makePluginProvenance('acme', '1.0');
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    recordEffect({ kind: 'noop' }, plug);
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    expect(listJournalFor('core')).toHaveLength(2);
    expect(listJournalFor('acme')).toHaveLength(1);
    expect(listJournalFor('nobody')).toHaveLength(0);
  });
});

describe('Cross-session persistence (§13.7 q.3)', () => {
  it('preserves the minted ledger id across reload while new clients have distinct identities', () => {
    const first = recordEffect({ kind: 'navigate', to: '#first' }, CORE_PROVENANCE);
    const identity = journalEventId(first);
    expect(identity).toMatch(/^fe-effect:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    _resetInMemoryOnly();
    restoreJournalFromStorage();
    expect(journalEventId(listJournal()[0]!)).toBe(identity);
    __resetJournalForTest();
    const otherClient = recordEffect({ kind: 'navigate', to: '#other' }, CORE_PROVENANCE);
    expect(otherClient.id).toBe(first.id);
    expect(journalEventId(otherClient)).not.toBe(identity);
  });

  it('keeps the previous wire id for restored legacy entries instead of re-ingesting a duplicate', () => {
    recordEffect({ kind: 'navigate', to: '#legacy' }, CORE_PROVENANCE);
    const storage = globalThis.localStorage;
    const payload = JSON.parse(storage.getItem('justsearch.effect-journal.v1')!) as { entries: Array<{ ledgerId?: string }> };
    delete payload.entries[0]!.ledgerId;
    storage.setItem('justsearch.effect-journal.v1', JSON.stringify(payload));
    _resetInMemoryOnly();
    restoreJournalFromStorage();
    expect(journalEventId(listJournal()[0]!)).toBe('fe-effect:1');
    const next = recordEffect({ kind: 'navigate', to: '#new' }, CORE_PROVENANCE);
    expect(next.id).toBe(2);
    expect(journalEventId(next)).not.toBe('fe-effect:2');
  });

  it('round-trips entries through localStorage', () => {
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    // Simulate restart: reset in-memory store but keep localStorage.
    const storage = (globalThis as { localStorage?: Storage }).localStorage;
    const raw = storage?.getItem('justsearch.effect-journal.v1');
    expect(raw).toBeTruthy(); // persisted
    _resetInMemoryOnly();
    expect(getJournalSize()).toBe(0);
    restoreJournalFromStorage();
    expect(getJournalSize()).toBe(2);
  });

  it('restoreJournalFromStorage is idempotent (second call no-op)', () => {
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    _resetInMemoryOnly();
    restoreJournalFromStorage();
    const after1 = getJournalSize();
    restoreJournalFromStorage();
    expect(getJournalSize()).toBe(after1);
  });
});

// Helper: reset in-memory store ONLY (preserves localStorage so the
// round-trip test can verify rehydration).
function _resetInMemoryOnly(): void {
  // Equivalent of __resetJournalForTest minus the persistence clear.
  // Recompose via the public reset + re-persist; but our reset clears
  // storage, so we save+restore.
  const storage = (globalThis as { localStorage?: Storage }).localStorage;
  const raw = storage?.getItem('justsearch.effect-journal.v1') ?? null;
  __resetJournalForTest();
  if (raw && storage) storage.setItem('justsearch.effect-journal.v1', raw);
}

// Imported above; keep types referenced.
type JournalEntry = import('./index.js').JournalEntry;

describe('§21.D Effect union v2 — UI kinds inverse derivation', () => {
  it('open-modal ↔ close-modal symmetric inverse', () => {
    const opened = recordEffect(
      { kind: 'open-modal', modalId: 'settings.confirm-reset' },
      CORE_PROVENANCE,
    );
    expect(opened.inverse).toEqual({
      kind: 'close-modal',
      modalId: 'settings.confirm-reset',
    });
    const closed = recordEffect(
      { kind: 'close-modal', modalId: 'settings.confirm-reset' },
      CORE_PROVENANCE,
    );
    expect(closed.inverse).toEqual({
      kind: 'open-modal',
      modalId: 'settings.confirm-reset',
    });
  });

  it('set-selection with previousIds restores prior selection', () => {
    const e = recordEffect(
      {
        kind: 'set-selection',
        surfaceId: 'search-results',
        ids: ['hit-3', 'hit-5'],
        previousIds: ['hit-1'],
      },
      CORE_PROVENANCE,
    );
    expect(e.inverse).toEqual({
      kind: 'set-selection',
      surfaceId: 'search-results',
      ids: ['hit-1'],
    });
  });

  it('set-selection without previousIds inverts to clear-selection', () => {
    const e = recordEffect(
      {
        kind: 'set-selection',
        surfaceId: 'search-results',
        ids: ['hit-3'],
      },
      CORE_PROVENANCE,
    );
    expect(e.inverse).toEqual({
      kind: 'clear-selection',
      surfaceId: 'search-results',
    });
  });

  it('clear-selection with previousIds restores; without is irreversible', () => {
    const restored = recordEffect(
      {
        kind: 'clear-selection',
        surfaceId: 's',
        previousIds: ['a', 'b'],
      },
      CORE_PROVENANCE,
    );
    expect(restored.inverse).toEqual({
      kind: 'set-selection',
      surfaceId: 's',
      ids: ['a', 'b'],
    });
    const empty = recordEffect(
      { kind: 'clear-selection', surfaceId: 's' },
      CORE_PROVENANCE,
    );
    expect(empty.inverse).toBeNull();
  });

  it('focus-element + scroll-to + copy-to-clipboard are irreversible (null inverse)', () => {
    const focus = recordEffect(
      { kind: 'focus-element', selector: '#search-input' },
      CORE_PROVENANCE,
    );
    expect(focus.inverse).toBeNull();

    const scroll = recordEffect(
      { kind: 'scroll-to', selector: '#row-7' },
      CORE_PROVENANCE,
    );
    expect(scroll.inverse).toBeNull();

    const copy = recordEffect(
      { kind: 'copy-to-clipboard', text: 'hello' },
      CORE_PROVENANCE,
    );
    expect(copy.inverse).toBeNull();
  });

  it('set-form-value with previousValue restores; without is irreversible', () => {
    const restored = recordEffect(
      {
        kind: 'set-form-value',
        formId: 'settings',
        path: '/foo',
        value: 'new',
        previousValue: 'old',
      },
      CORE_PROVENANCE,
    );
    expect(restored.inverse).toEqual({
      kind: 'set-form-value',
      formId: 'settings',
      path: '/foo',
      value: 'old',
    });

    const unrestorable = recordEffect(
      {
        kind: 'set-form-value',
        formId: 'settings',
        path: '/foo',
        value: 'new',
      },
      CORE_PROVENANCE,
    );
    expect(unrestorable.inverse).toBeNull();
  });
});

describe('§21.D Effect union v2 — applyEffect dispatch', () => {
  it('set-selection dispatches jf-set-selection on document', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    document.addEventListener('jf-set-selection', handler);
    try {
      applyEffect({
        kind: 'set-selection',
        surfaceId: 'search-results',
        ids: ['hit-1'],
      });
      expect(events).toHaveLength(1);
      expect(events[0]!.detail).toEqual({
        surfaceId: 'search-results',
        ids: ['hit-1'],
      });
    } finally {
      document.removeEventListener('jf-set-selection', handler);
    }
  });

  it('open-modal + close-modal dispatch corresponding jf-* events', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const opens: CustomEvent[] = [];
    const closes: CustomEvent[] = [];
    const o = (e: Event) => opens.push(e as CustomEvent);
    const c = (e: Event) => closes.push(e as CustomEvent);
    document.addEventListener('jf-open-modal', o);
    document.addEventListener('jf-close-modal', c);
    try {
      applyEffect({
        kind: 'open-modal',
        modalId: 'confirm',
        payload: { context: 'x' },
      });
      applyEffect({ kind: 'close-modal', modalId: 'confirm' });
      expect(opens).toHaveLength(1);
      expect(opens[0]!.detail.modalId).toBe('confirm');
      expect(opens[0]!.detail.payload).toEqual({ context: 'x' });
      expect(closes).toHaveLength(1);
      expect(closes[0]!.detail.modalId).toBe('confirm');
    } finally {
      document.removeEventListener('jf-open-modal', o);
      document.removeEventListener('jf-close-modal', c);
    }
  });

  it('set-form-value dispatches jf-set-form-value with full detail', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    document.addEventListener('jf-set-form-value', handler);
    try {
      applyEffect({
        kind: 'set-form-value',
        formId: 'settings',
        path: '/maxResults',
        value: 50,
      });
      expect(events).toHaveLength(1);
      expect(events[0]!.detail).toEqual({
        formId: 'settings',
        path: '/maxResults',
        value: 50,
      });
    } finally {
      document.removeEventListener('jf-set-form-value', handler);
    }
  });

  it('focus-element calls .focus() on the matched HTMLElement', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const btn = document.createElement('button');
    btn.id = 'jf-test-focus-target';
    document.body.appendChild(btn);
    try {
      applyEffect({ kind: 'focus-element', selector: '#jf-test-focus-target' });
      expect(document.activeElement).toBe(btn);
    } finally {
      document.body.removeChild(btn);
    }
  });
});

describe('§28.W4 — originator-grouped undo', () => {
  it('undoLastEffectByOriginator finds the most recent matching reversible entry', async () => {
    const { undoLastEffectByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, { originator: 'user' });
    recordEffect({ kind: 'open-pane', paneId: 'inspector' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE, { originator: 'user' });
    recordEffect({ kind: 'open-modal', modalId: 'm1' }, CORE_PROVENANCE, { originator: 'agent' });
    let applied: import('../effect.js').Effect | null = null;
    const inverse = undoLastEffectByOriginator('agent', (e) => { applied = e; });
    expect(inverse).toEqual({ kind: 'close-modal', modalId: 'm1' });
    expect(applied).toEqual({ kind: 'close-modal', modalId: 'm1' });
  });

  it('undoLastEffectByOriginator skips irreversible entries', async () => {
    const { undoLastEffectByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'toast', message: 'hi' }, CORE_PROVENANCE, { originator: 'agent' });
    const inverse = undoLastEffectByOriginator('agent');
    // toast is null inverse; we walked past it to the open-pane.
    expect(inverse).toEqual({ kind: 'close-pane', paneId: 'a' });
  });

  it('undoLastEffectByOriginator returns null when no matching reversible entry', async () => {
    const { undoLastEffectByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, { originator: 'user' });
    expect(undoLastEffectByOriginator('agent')).toBeNull();
    expect(undoLastEffectByOriginator('system')).toBeNull();
  });

  it('undoAllByOriginator dispatches every reversible inverse for the originator', async () => {
    const { undoAllByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, { originator: 'user' });
    recordEffect({ kind: 'open-pane', paneId: 'c' }, CORE_PROVENANCE, { originator: 'agent' });
    const applied: import('../effect.js').Effect[] = [];
    const count = undoAllByOriginator('agent', (e) => { applied.push(e); });
    expect(count).toBe(3);
    // Reverse chronological — last agent action first.
    expect(applied.map((e) => e.kind)).toEqual(['close-pane', 'close-modal', 'close-pane']);
  });

  // Regression (observations.md): a vetoed proposal was never dispatched, so
  // its derivable inverse must NOT be applied by "Undo all AI actions" —
  // mirrors the pendingOutcome:'rejected' skip in summarizeAgentActivity.
  it('undoAllByOriginator skips vetoed (pendingOutcome:rejected) entries', async () => {
    const { undoAllByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'accepted' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'accepted' });
    // Vetoed: has a derivable inverse (close-pane) but was never dispatched.
    recordEffect({ kind: 'open-pane', paneId: 'vetoed' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    const applied: import('../effect.js').Effect[] = [];
    const count = undoAllByOriginator('agent', (e) => { applied.push(e); });
    expect(count).toBe(1);
    expect(applied).toEqual([{ kind: 'close-pane', paneId: 'accepted' }]);
  });

  it('undoLastEffectByOriginator skips vetoed (pendingOutcome:rejected) entries', async () => {
    const { undoLastEffectByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'accepted' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'accepted' });
    recordEffect({ kind: 'open-modal', modalId: 'vetoed' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    // The most-recent agent entry is vetoed — skip it, fall back to the accepted one.
    const inverse = undoLastEffectByOriginator('agent');
    expect(inverse).toEqual({ kind: 'close-pane', paneId: 'accepted' });
  });

  // 543-fwd #7 — grouped/atomic undo of an agent turn (shared causation root).
  describe('getGroupRoot / previewUndoGroup / undoGroup (543-fwd #7)', () => {
    it('groups a causation chain by its root and reverses the whole turn', async () => {
      const { recordEffect, getGroupRoot, previewUndoGroup, undoGroup } = await import('./index.js');
      const a = recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' }); // turn root
      const b = recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE, { originator: 'agent', causation: a.id });
      const read = recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE, { originator: 'agent', causation: b.id }); // non-reversible
      const other = recordEffect({ kind: 'open-pane', paneId: 'other' }, CORE_PROVENANCE, { originator: 'user' }); // separate group
      // Group root resolution.
      expect(getGroupRoot(a.id)).toBe(a.id);
      expect(getGroupRoot(b.id)).toBe(a.id);
      expect(getGroupRoot(read.id)).toBe(a.id);
      expect(getGroupRoot(other.id)).toBe(other.id);
      // Preview: reversible members of the turn, newest-first (read skipped).
      expect(previewUndoGroup(a.id).map((e) => e.id)).toEqual([b.id, a.id]);
      // Undo the turn atomically.
      const applied: import('../effect.js').Effect[] = [];
      const count = undoGroup(a.id, (e) => { applied.push(e); });
      expect(count).toBe(2);
      expect(applied).toEqual([
        { kind: 'close-modal', modalId: 'm' },
        { kind: 'close-pane', paneId: 'a' },
      ]);
    });

    it('a compensable mutation in the turn is reversed via undo-operation', async () => {
      const { recordEffect, markUndoableOperation, undoGroup } = await import('./index.js');
      const root = recordEffect({ kind: 'open-pane', paneId: 'r' }, CORE_PROVENANCE, { originator: 'agent' });
      const mut = recordEffect({ kind: 'invoke-operation', operationId: 'core_file_operations' }, CORE_PROVENANCE, { originator: 'agent', causation: root.id });
      markUndoableOperation(mut.id, 'core_file_operations', 'ex9');
      const applied: import('../effect.js').Effect[] = [];
      undoGroup(root.id, (e) => { applied.push(e); });
      expect(applied).toEqual([
        { kind: 'undo-operation', operationId: 'core_file_operations', executionId: 'ex9' },
        { kind: 'close-pane', paneId: 'r' },
      ]);
    });
  });

  // 543-fwd #19 — journal export / archive / replay round-trip.
  describe('exportJournalArchive / importJournalArchive (543-fwd #19)', () => {
    it('round-trips the effect sequence through a portable JSON archive', async () => {
      const { recordEffect, exportJournalArchive, importJournalArchive } = await import('./index.js');
      recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, { originator: 'user' });
      recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'x' } }, CORE_PROVENANCE, { originator: 'agent' });
      const json = exportJournalArchive();
      // Portable, human-readable JSON.
      expect(() => JSON.parse(json)).not.toThrow();
      const effects = importJournalArchive(json);
      expect(effects).toEqual([
        { kind: 'navigate', to: '#a' },
        { kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'x' } },
      ]);
    });

    it('skips pending-lifecycle markers (proposal bookkeeping, not replayable)', async () => {
      const { recordEffect, exportJournalArchive, importJournalArchive } = await import('./index.js');
      recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
      recordEffect({ kind: 'open-pane', paneId: 'veto' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
      const effects = importJournalArchive(exportJournalArchive());
      expect(effects).toEqual([{ kind: 'open-pane', paneId: 'a' }]);
    });

    it('returns [] for malformed JSON or a version mismatch (no throw)', async () => {
      const { importJournalArchive } = await import('./index.js');
      expect(importJournalArchive('not json{')).toEqual([]);
      expect(importJournalArchive(JSON.stringify({ version: 99, entries: [] }))).toEqual([]);
      expect(importJournalArchive(JSON.stringify({ version: 1, entries: 'nope' }))).toEqual([]);
    });

    it('exports only the provided subset when entries are passed', async () => {
      const { recordEffect, listJournal, exportJournalArchive, importJournalArchive } = await import('./index.js');
      const a = recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE);
      recordEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE);
      const onlyA = listJournal().filter((e) => e.id === a.id);
      expect(importJournalArchive(exportJournalArchive(onlyA))).toEqual([{ kind: 'navigate', to: '#a' }]);
    });
  });

  // 543-fwd #5 — scoped time-travel: reverse everything after a chosen entry.
  describe('undoToEntry / previewUndoToEntry (543-fwd #5)', () => {
    it('reverses every reversible entry after the target (newest-first), skipping reads', async () => {
      const { recordEffect, markUndoableOperation, undoToEntry, previewUndoToEntry } = await import('./index.js');
      const a = recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
      const b = recordEffect({ kind: 'open-modal', modalId: 'm' }, CORE_PROVENANCE);
      recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE); // read — non-reversible
      const mut = recordEffect({ kind: 'invoke-operation', operationId: 'core_file_operations' }, CORE_PROVENANCE);
      markUndoableOperation(mut.id, 'core_file_operations', 'ex1');
      // Preview: everything after `a` that is reversible, newest-first.
      expect(previewUndoToEntry(a.id).map((e) => e.id)).toEqual([mut.id, b.id]);
      const applied: import('../effect.js').Effect[] = [];
      const count = undoToEntry(a.id, (e) => { applied.push(e); });
      expect(count).toBe(2);
      expect(applied).toEqual([
        { kind: 'undo-operation', operationId: 'core_file_operations', executionId: 'ex1' }, // compensate the mutation
        { kind: 'close-modal', modalId: 'm' }, // FE inverse
      ]);
      // The target `a` itself is NOT reversed (we restore to just after it).
    });

    it('returns 0 for the last entry (nothing after) or an unknown id', async () => {
      const { recordEffect, undoToEntry } = await import('./index.js');
      const only = recordEffect({ kind: 'open-pane', paneId: 'x' }, CORE_PROVENANCE);
      expect(undoToEntry(only.id, () => {})).toBe(0);
      expect(undoToEntry(99999, () => {})).toBe(0);
    });
  });

  // 543-fwd P1 — the compensating (saga) undo path. An agent invoke-operation
  // mutation has a NULL FE inverse but a backend executionId; the originator-
  // scoped undos must reverse it via an undo-operation effect, not skip it.
  describe('compensating undo for agent mutations (543-fwd P1)', () => {
    it('undoAllByOriginator reverses a compensable agent op via undo-operation', async () => {
      const { undoAllByOriginator, recordEffect, markUndoableOperation } = await import('./index.js');
      // A mutating agent op (file-operations): null FE inverse, but executionId captured.
      const mut = recordEffect(
        { kind: 'invoke-operation', operationId: 'core_file_operations' },
        CORE_PROVENANCE,
        { originator: 'agent' },
      );
      markUndoableOperation(mut.id, 'core_file_operations', 'exec-42');
      // A read (search): no executionId — non-compensable, must be skipped.
      recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE, { originator: 'agent' });
      const applied: import('../effect.js').Effect[] = [];
      const count = undoAllByOriginator('agent', (e) => { applied.push(e); });
      expect(count).toBe(1); // only the compensable mutation
      expect(applied).toEqual([
        { kind: 'undo-operation', operationId: 'core_file_operations', executionId: 'exec-42' },
      ]);
    });

    it('previewUndoAllByOriginator + peek + undoLast include the compensable entry', async () => {
      const { previewUndoAllByOriginator, peekLastUndoableByOriginator, undoLastEffectByOriginator, recordEffect, markUndoableOperation } =
        await import('./index.js');
      const mut = recordEffect({ kind: 'invoke-operation', operationId: 'core_file_operations' }, CORE_PROVENANCE, { originator: 'agent' });
      markUndoableOperation(mut.id, 'core_file_operations', 'exec-7');
      // Preview lists it.
      expect(previewUndoAllByOriginator('agent').map((e) => e.id)).toEqual([mut.id]);
      // Peek names it.
      expect(peekLastUndoableByOriginator('agent')?.id).toBe(mut.id);
      // Undo-last dispatches the compensating effect.
      let applied: import('../effect.js').Effect | null = null;
      const ret = undoLastEffectByOriginator('agent', (e) => { applied = e; });
      expect(ret).toEqual({ kind: 'undo-operation', operationId: 'core_file_operations', executionId: 'exec-7' });
      expect(applied).toEqual(ret);
    });

    it('a read (no executionId) stays non-compensable — correctly nothing to undo', async () => {
      const { undoAllByOriginator, previewUndoAllByOriginator, recordEffect } = await import('./index.js');
      recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE, { originator: 'agent' });
      expect(previewUndoAllByOriginator('agent')).toEqual([]);
      const applied: import('../effect.js').Effect[] = [];
      expect(undoAllByOriginator('agent', (e) => { applied.push(e); })).toBe(0);
      expect(applied).toEqual([]);
    });
  });

  // 543-fwd #4 (undo-label) — peek names the entry the undo will reverse,
  // following the SAME walk as undoLastEffectByOriginator, without dispatching.
  it('peekLastUndoableByOriginator returns the entry undo would reverse (no dispatch)', async () => {
    const { peekLastUndoableByOriginator, undoLastEffectByOriginator, recordEffect } =
      await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'toast', message: 'irreversible' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-modal', modalId: 'm1' }, CORE_PROVENANCE, { originator: 'agent' });
    const peeked = peekLastUndoableByOriginator('agent');
    expect(peeked?.effect).toEqual({ kind: 'open-modal', modalId: 'm1' });
    // Peek does not dispatch — a subsequent undo still finds the same target.
    let applied: import('../effect.js').Effect | null = null;
    undoLastEffectByOriginator('agent', (e) => { applied = e; });
    expect(applied).toEqual({ kind: 'close-modal', modalId: 'm1' });
  });

  it('peekLastUndoableByOriginator skips irreversible + vetoed, returns null when none match', async () => {
    const { peekLastUndoableByOriginator, recordEffect } = await import('./index.js');
    recordEffect({ kind: 'toast', message: 'x' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'vetoed' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    expect(peekLastUndoableByOriginator('agent')).toBeNull();
    recordEffect({ kind: 'navigate', to: '#u' }, CORE_PROVENANCE, { originator: 'user' });
    expect(peekLastUndoableByOriginator('agent')).toBeNull();
  });

  // 543-fwd #1 (redo) — cursor-based undo then redo round-trips the original.
  it('redoLastEffect re-applies the original effect undo retracted (round-trip)', async () => {
    const { recordEffect, undoLastEffect, redoLastEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE);
    const undoApplied: import('../effect.js').Effect[] = [];
    const redoApplied: import('../effect.js').Effect[] = [];
    // Undo dispatches the inverse (close-modal b).
    expect(undoLastEffect((e) => undoApplied.push(e))).toEqual({ kind: 'close-modal', modalId: 'b' });
    // Redo re-applies the ORIGINAL (open-modal b), not the inverse.
    expect(redoLastEffect((e) => redoApplied.push(e))).toEqual({ kind: 'open-modal', modalId: 'b' });
    expect(undoApplied).toEqual([{ kind: 'close-modal', modalId: 'b' }]);
    expect(redoApplied).toEqual([{ kind: 'open-modal', modalId: 'b' }]);
  });

  // 543-fwd #1 (live-found integration bug) — the global Undo/Redo kernel
  // Actions return {kind:'noop'}, which invokeAndApply JOURNALS. If that noop
  // reset the cursor it would kill the redo stack the undo just created (the
  // browser symptom: Ctrl+Z works, Ctrl+Shift+Z says "Nothing to redo"). A noop
  // must be transparent to the cursor.
  it('redo survives a noop journaled after undo (the Action-return path)', async () => {
    const { recordEffect, undoLastEffect, redoLastEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE);
    expect(undoLastEffect(() => {})).toEqual({ kind: 'close-modal', modalId: 'b' });
    // Simulate the undo Action's `return {kind:'noop'}` being journaled.
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    // Redo must still re-apply the open-modal — the noop did not truncate.
    expect(redoLastEffect(() => {})).toEqual({ kind: 'open-modal', modalId: 'b' });
  });

  it('redoLastEffect returns null when nothing was undone', async () => {
    const { recordEffect, redoLastEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    expect(redoLastEffect()).toBeNull();
  });

  it('redoLastEffect: a new appended effect truncates the redo stack', async () => {
    const { recordEffect, undoLastEffect, redoLastEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    undoLastEffect(() => {}); // cursor retracts past 'a'
    // A brand-new effect appends → recordEffect resets the cursor to the end.
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE);
    // The previously-undone 'a' is no longer redoable (two-stack truncate rule).
    expect(redoLastEffect()).toBeNull();
  });

  // 543-fwd #1 — regression for the live-found defect: a navigate inverse
  // dispatched during undo is re-journaled by NavigationJournal (router →
  // recordEffect), which would reset the cursor and truncate redo. The
  // replay-suppression flag must prevent that one re-record so redo still works.
  it('redo survives a navigate inverse that feeds back into recordEffect (suppression)', async () => {
    const { recordEffect, undoLastEffect, redoLastEffect, consumeReplayNavSuppression } =
      await import('./index.js');
    recordEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE); // first nav: null inverse
    recordEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE); // inverse → navigate #a
    // Undo with an applyFn that models NavigationJournal's feedback: every
    // dispatched navigate would re-journal UNLESS the suppression flag is set.
    const router = (e: import('../effect.js').Effect) => {
      if (e.kind === 'navigate' && !consumeReplayNavSuppression(e.to)) {
        recordEffect(e, CORE_PROVENANCE);
      }
    };
    expect(undoLastEffect(router)).toEqual({ kind: 'navigate', to: '#a' });
    // Without suppression the cursor would be reset here → this would be null.
    expect(redoLastEffect(router)).toEqual({ kind: 'navigate', to: '#b' });
  });

  it('redoLastEffect skips irreversible + pending-lifecycle markers, mirroring undo', async () => {
    const { recordEffect, undoLastEffect, redoLastEffect } = await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE);
    recordEffect({ kind: 'toast', message: 'irreversible' }, CORE_PROVENANCE);
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE);
    // Undo twice: reverses open-modal b, then (walking past toast) open-pane a.
    expect(undoLastEffect()).toEqual({ kind: 'close-modal', modalId: 'b' });
    expect(undoLastEffect()).toEqual({ kind: 'close-pane', paneId: 'a' });
    // Redo twice mirrors forward: open-pane a, then open-modal b (toast skipped).
    expect(redoLastEffect()).toEqual({ kind: 'open-pane', paneId: 'a' });
    expect(redoLastEffect()).toEqual({ kind: 'open-modal', modalId: 'b' });
    expect(redoLastEffect()).toBeNull();
  });

  // 543-fwd #8 (mass-undo-confirm) — the preview matches what undoAll reverses,
  // in the same reverse-chronological order, without dispatching.
  it('previewUndoAllByOriginator lists exactly what undoAllByOriginator would reverse', async () => {
    const { previewUndoAllByOriginator, undoAllByOriginator, recordEffect } =
      await import('./index.js');
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'toast', message: 'irreversible' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'vetoed' }, CORE_PROVENANCE, { originator: 'agent', pendingOutcome: 'rejected' });
    recordEffect({ kind: 'navigate', to: '#u' }, CORE_PROVENANCE, { originator: 'user' });
    const preview = previewUndoAllByOriginator('agent');
    // Reverse-chron: open-modal b, then open-pane a. Toast (null inverse) and
    // vetoed and user entries excluded.
    expect(preview.map((e) => e.effect)).toEqual([
      { kind: 'open-modal', modalId: 'b' },
      { kind: 'open-pane', paneId: 'a' },
    ]);
    // Preview did not dispatch — the actual undoAll still finds the same count.
    const applied: import('../effect.js').Effect[] = [];
    const count = undoAllByOriginator('agent', (e) => { applied.push(e); });
    expect(count).toBe(preview.length);
    expect(applied).toEqual([
      { kind: 'close-modal', modalId: 'b' },
      { kind: 'close-pane', paneId: 'a' },
    ]);
  });
});

describe('§25.β5 DataEffect arm — inverse + dispatch', () => {
  it('data-result has null inverse (data returns are non-reversible)', () => {
    const e = recordEffect(
      {
        kind: 'data-result',
        operationId: 'core.search',
        resultKey: 'latestSearch',
        result: { hits: [] },
      },
      CORE_PROVENANCE,
    );
    expect(e.inverse).toBeNull();
  });

  it('data-error has null inverse', () => {
    const e = recordEffect(
      {
        kind: 'data-error',
        operationId: 'core.search',
        resultKey: 'latestSearch',
        error: 'network down',
      },
      CORE_PROVENANCE,
    );
    expect(e.inverse).toBeNull();
  });

  it('data-result dispatches jf-data-result event', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    document.addEventListener('jf-data-result', handler);
    try {
      applyEffect({
        kind: 'data-result',
        operationId: 'core.search',
        resultKey: 'latestSearch',
        result: { hits: ['a', 'b'] },
      });
      expect(events).toHaveLength(1);
      expect(events[0]!.detail.operationId).toBe('core.search');
      expect(events[0]!.detail.resultKey).toBe('latestSearch');
      expect(events[0]!.detail.result).toEqual({ hits: ['a', 'b'] });
    } finally {
      document.removeEventListener('jf-data-result', handler);
    }
  });

  it('data-result populates EvaluationContext data cache (getLatestDataResult)', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const {
      getLatestDataResult,
      __resetDataResultsForTest,
    } = await import('../evaluationContext/index.js');
    __resetDataResultsForTest();
    applyEffect({
      kind: 'data-result',
      operationId: 'core.search',
      resultKey: 'latestSearch',
      result: { hits: ['a'] },
    });
    // Cache write is now synchronous (static import).
    const latest = getLatestDataResult('latestSearch');
    expect(latest).toBeDefined();
    expect(latest!.operationId).toBe('core.search');
    expect(latest!.result).toEqual({ hits: ['a'] });
    expect(typeof latest!.at).toBe('number');
  });

  it('data-error dispatches jf-data-error event', async () => {
    const { applyEffect } = await import('../actions/index.js');
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    document.addEventListener('jf-data-error', handler);
    try {
      applyEffect({
        kind: 'data-error',
        operationId: 'core.search',
        resultKey: 'latestSearch',
        error: 'timeout',
      });
      expect(events).toHaveLength(1);
      expect(events[0]!.detail.error).toBe('timeout');
    } finally {
      document.removeEventListener('jf-data-error', handler);
    }
  });
});
