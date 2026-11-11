// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * S6 Activity — the journal keeps each entry's backend invocation (operationKey) and execution
 * (executionId) across a reload. Entries stored before these fields existed, or with values that
 * are not identities, restore unlinked and otherwise unchanged; a restored execution still lets the
 * person ask for its backend undo (the Engine decides whether it is still available).
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  getUndoableOperation,
  listJournal,
  restoreJournalFromStorage,
  __resetJournalForTest,
} from './index.js';

const KEY = 'justsearch.effect-journal.v1';
const provenance = { tier: 'CORE', contributorId: 'core', version: '1.0' };

function stored(entries: unknown[]): void {
  localStorage.setItem(KEY, JSON.stringify({ version: 1, nextId: entries.length + 1, entries }));
}

function invocation(id: number, extra: Record<string, unknown>): Record<string, unknown> {
  return { id, ledgerId: `fe-effect:00000000-0000-4000-8000-00000000000${id}`, invokedAt: '2026-11-01T10:00:00.000Z',
    effect: { kind: 'invoke-operation', operationId: 'core_file_operations' }, invokedBy: provenance,
    inverse: null, originator: 'agent', ...extra };
}

afterEach(() => { __resetJournalForTest(); });

describe('journal invocation associations survive a reload', () => {
  it('a restored execution still offers its backend undo, and its invocation key is kept', () => {
    stored([invocation(1, { operationKey: 'key-1', executionId: 'exec-1' })]);
    restoreJournalFromStorage();
    expect(listJournal()[0]?.operationKey).toBe('key-1');
    expect(getUndoableOperation(1)).toEqual({ operationId: 'core_file_operations', executionId: 'exec-1' });
  });

  it('legacy entries restore unlinked; malformed associations are dropped, the entry kept as it was', () => {
    stored([
      { id: 1, invokedAt: '2026-11-01T10:00:00Z', effect: { kind: 'open-pane', paneId: 'a' }, invokedBy: provenance,
        inverse: { kind: 'close-pane', paneId: 'a' }, originator: 'user' },
      invocation(2, { operationKey: '', executionId: 42 }),
      invocation(3, { operationKey: '   ', executionId: 'exec-3' }),
    ]);
    restoreJournalFromStorage();
    const [legacy, malformed, partly] = listJournal();
    expect(legacy).toEqual({ id: 1, invokedAt: '2026-11-01T10:00:00Z', effect: { kind: 'open-pane', paneId: 'a' },
      invokedBy: provenance, inverse: { kind: 'close-pane', paneId: 'a' }, originator: 'user' });
    expect(malformed?.operationKey).toBeUndefined();
    expect(malformed?.executionId).toBeUndefined();
    expect(getUndoableOperation(2)).toBeUndefined();
    expect(partly?.operationKey).toBeUndefined();
    expect(partly?.executionId).toBe('exec-3');
    expect(listJournal().map((e) => e.id)).toEqual([1, 2, 3]);
  });
});
