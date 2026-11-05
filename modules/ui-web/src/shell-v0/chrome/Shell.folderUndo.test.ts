// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './Shell.js';
import '../views/ActivitySurface.js';
import type { Shell } from './Shell.js';
import { applyEffect } from '../substrates/actions/index.js';
import { __clearBackendOperationInversesForTest, __resetJournalForTest, listJournal,
  peekLastEffectUndo, previewUndoAllByOriginator, restoreJournalFromStorage,
  syncBackendOperationInverses } from '../substrates/effects/index.js';
import { __resetOwnReversalsForTest } from '../state/ownReversals.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';

const removal = 'core.remove-watched-root';
const addition = 'core.add-watched-root';
const folder = { path: '/home/sam/Notes', collection: 'reports' };
const tick = () => new Promise(resolve => setTimeout(resolve, 20));
function ctrlZ() {
  const key = new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true });
  document.body.dispatchEvent(key);
  expect(key.defaultPrevented, 'the shell owns Ctrl+Z outside a text field').toBe(true);
}

afterEach(() => {
  document.body.replaceChildren();
  __resetJournalForTest(); __clearBackendOperationInversesForTest(); __resetOwnReversalsForTest();
  __resetAiStateForTest(); __resetBootstrapForTest(); __resetUserConfigForTest();
  vi.unstubAllGlobals();
});

async function mount(removedFolder: unknown = folder, failAdd = false) {
  const watched = new Set([folder.path]);
  const calls: { operationId: string; args: Record<string, unknown> }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const match = String(input).match(/\/api\/operations\/([^/]+)\/invoke$/);
    if (!match) return new Response(JSON.stringify({ entries: [] }));
    const operationId = decodeURIComponent(match[1]!);
    const { args } = JSON.parse(String(init?.body)) as { args: Record<string, unknown> };
    calls.push({ operationId, args });
    if (operationId === removal) {
      watched.delete(folder.path);
      return new Response(JSON.stringify({ success: true, message: 'Removed', structuredData: { removedFolder } }));
    }
    if (failAdd || typeof args.path !== 'string' || !args.path) {
      return new Response(JSON.stringify({ success: false, message: 'Folder could not be added' }));
    }
    watched.add(args.path);
    return new Response(JSON.stringify({ success: true, message: 'Added' }));
  }));
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell); await shell.updateComplete; await tick();
  __resetJournalForTest(); __resetOwnReversalsForTest();
  syncBackendOperationInverses([{ id: removal, policy: { inverseOperationId: addition } }]);
  return { shell, watched, calls };
}

it.each(['user', 'agent'] as const)('a %s removal followed by Ctrl+Z re-adds the folder from the successful receipt', async originator => {
  const { watched, calls } = await mount();
  // The receipt, rather than the supplied arguments, owns the resolved path and collection.
  applyEffect({ kind: 'invoke-operation', operationId: removal, args: { path: './Notes' } }, CORE_PROVENANCE, originator);
  await tick();
  expect(watched.has(folder.path)).toBe(false);
  ctrlZ(); await tick();
  expect(watched.has(folder.path), 'Ctrl+Z must really restore the removed folder').toBe(true);
  expect(calls).toEqual([{ operationId: removal, args: { path: './Notes' } }, { operationId: addition, args: folder }]);
});

it('a successful removal without a recorded path offers no Undo or Ctrl+Z target', async () => {
  const { calls } = await mount(null);
  applyEffect({ kind: 'invoke-operation', operationId: removal, args: folder }, CORE_PROVENANCE, 'agent');
  await tick();
  expect(listJournal().find(entry => entry.effect.kind === 'invoke-operation')!.inverse).toBeNull();
  expect(peekLastEffectUndo()).toBeNull();
  expect(previewUndoAllByOriginator('agent')).toEqual([]);
  ctrlZ(); await tick();
  expect(calls.map(call => call.operationId)).toEqual([removal]);
});

it('Activity offers no Undo when the agent only removed a folder without a recorded path', async () => {
  await mount(null);
  applyEffect({ kind: 'invoke-operation', operationId: removal, args: folder }, CORE_PROVENANCE, 'agent');
  await tick();
  const activity = document.createElement('jf-activity-surface') as HTMLElement & { updateComplete: Promise<unknown> };
  document.body.append(activity); await activity.updateComplete;
  expect(activity.shadowRoot!.querySelector('[data-testid="activity-undo-all"]')).toBeNull();
  expect(activity.shadowRoot!.querySelector('[data-testid="activity-save-macro"]')).not.toBeNull();
});

it('an older persisted removal with an argument-less inverse offers no Undo after restore', () => {
  __resetJournalForTest();
  localStorage.setItem('justsearch.effect-journal.v1', JSON.stringify({ version: 1, nextId: 2, entries: [{
    id: 1, effect: { kind: 'invoke-operation', operationId: removal, args: folder },
    inverse: { kind: 'invoke-operation', operationId: addition },
    invokedBy: CORE_PROVENANCE, invokedAt: '2026-11-05T09:00:00Z', originator: 'agent',
  }] }));
  restoreJournalFromStorage();
  expect(listJournal()[0]!.inverse).toBeNull();
  expect(peekLastEffectUndo()).toBeNull();
  expect(previewUndoAllByOriginator('agent')).toEqual([]);
});

it('a removal receipt keeps its working re-add inverse across a journal restore', async () => {
  await mount();
  applyEffect({ kind: 'invoke-operation', operationId: removal, args: folder }); await tick();
  const persisted = localStorage.getItem('justsearch.effect-journal.v1')!;
  __resetJournalForTest(); localStorage.setItem('justsearch.effect-journal.v1', persisted);
  restoreJournalFromStorage();
  expect(peekLastEffectUndo()!.inverse).toEqual({ kind: 'invoke-operation', operationId: addition, args: folder });
});

it('a failed re-add reports the failure in the status pill', async () => {
  const { shell, watched } = await mount(folder, true);
  applyEffect({ kind: 'invoke-operation', operationId: removal, args: folder }); await tick();
  const notices: EphemeralToastSpec[] = [];
  const listener = (event: Event) => notices.push((event as CustomEvent<EphemeralToastSpec>).detail);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
  try {
    ctrlZ(); await tick();
    expect(watched.has(folder.path)).toBe(false);
    const failure = notices.find(notice => notice.severity === 'error');
    expect(failure?.message).toContain('could not be completed');
    const pill = shell.shadowRoot!.querySelector('jf-status-pill') as HTMLElement & { updateComplete: Promise<unknown> };
    await pill.updateComplete;
    expect(pill.shadowRoot!.textContent).toContain(failure!.message);
  } finally {
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
  }
});
