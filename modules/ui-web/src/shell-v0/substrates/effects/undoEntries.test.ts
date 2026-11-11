// SPDX-License-Identifier: Apache-2.0
/**
 * DE6 — an undo preview executes exactly the previewed set: entries recorded after the preview are
 * left alone, an entry that is gone by the time of the confirmation is reported rather than
 * silently counted, and a failing reversal stops the run and reports what was not undone.
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  recordEffect, previewUndoAllByOriginator, undoEntries, settleOperationRequest, syncBackendOperationInverses,
  __clearBackendOperationInversesForTest, __resetJournalForTest,
} from './index.js';
import type { Effect } from '../effect.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';

afterEach(() => { __resetJournalForTest(); __clearBackendOperationInversesForTest(); });

describe('undoEntries (DE6)', () => {
  it('reverses the previewed entries newest first and nothing recorded after the preview', () => {
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'b' }, CORE_PROVENANCE, { originator: 'agent' });
    const preview = previewUndoAllByOriginator('agent');
    recordEffect({ kind: 'open-pane', paneId: 'late' }, CORE_PROVENANCE, { originator: 'agent' });
    const applied: Effect[] = [];
    const outcome = undoEntries(preview, (effect) => applied.push(effect));
    expect(applied).toEqual([{ kind: 'close-pane', paneId: 'b' }, { kind: 'close-pane', paneId: 'a' }]);
    expect(outcome).toEqual({ reversed: 2, requested: [], skipped: [] });
  });

  it('an entry gone by the confirmation is skipped and reported, not counted', () => {
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    const preview = previewUndoAllByOriginator('agent');
    __resetJournalForTest();
    const applied: Effect[] = [];
    const outcome = undoEntries(preview, (effect) => applied.push(effect));
    expect(applied).toEqual([]);
    expect(outcome.reversed).toBe(0);
    expect(outcome.skipped.map((entry) => entry.id)).toEqual(preview.map((entry) => entry.id));
  });

  it('a failing reversal stops the run; it and the rest are reported as not undone', () => {
    for (const paneId of ['a', 'b', 'c']) recordEffect({ kind: 'open-pane', paneId }, CORE_PROVENANCE, { originator: 'agent' });
    const preview = previewUndoAllByOriginator('agent');
    let calls = 0;
    const outcome = undoEntries(preview, () => { calls += 1; if (calls === 2) throw new Error('refused'); });
    expect(outcome.reversed).toBe(1);
    expect(outcome.skipped.map((entry) => entry.id)).toEqual(preview.slice(1).map((entry) => entry.id));
  });

  // S6 Activity (broken before): a reversal the Engine performs was counted as done on dispatch.
  it('a reversal the Engine performs is returned as requested, never reversed, and settles with the Engine answer', async () => {
    syncBackendOperationInverses([{ id: 'core.add-watched-root', policy: { inverseOperationId: 'core.remove-watched-root' } }]);
    recordEffect({ kind: 'invoke-operation', operationId: 'core.add-watched-root' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    const dispatched: number[] = [];
    const outcome = undoEntries(previewUndoAllByOriginator('agent'),
      (effect) => { dispatched.push(recordEffect(effect, CORE_PROVENANCE).id); });
    expect(outcome.reversed).toBe(1);
    expect(outcome.requested.map((request) => request.entry.effect)).toEqual([
      { kind: 'invoke-operation', operationId: 'core.add-watched-root' }]);
    settleOperationRequest(dispatched[1]!, 'not-done');
    await expect(outcome.requested[0]!.settled).resolves.toBe('not-done');
  });
});
