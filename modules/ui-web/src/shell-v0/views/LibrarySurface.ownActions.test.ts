// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE1/DE6 at the Library's confirm and undo points: Remove asks in place (band under the folder or
 * under the problem that offered it), and a saved skip-rule change offers a real Undo that writes the
 * rules back as they were, bound to the save it reverses.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';
import { __resetOwnReversalsForTest, undoLatestReversal } from '../state/ownReversals.js';

type Answer = HTMLElement & { onActivate: () => unknown };
type Band = HTMLElement & { heading: string; consequence: string; actionLabel: string; updateComplete: Promise<unknown> };
const root = (pathHash: string, extra: Partial<IndexedRootView> = {}): IndexedRootView =>
  ({ pathHash, collection: 'default', fileCount: 1420, walkCompleted: true, ...extra });
async function pump(el: LibrarySurface) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
function notices(): { list: EphemeralToastSpec[]; stop: () => void } {
  const list: EphemeralToastSpec[] = [];
  const listen = (event: Event) => list.push((event as CustomEvent<EphemeralToastSpec>).detail);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
  return { list, stop: () => document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen) };
}
const bands = (el: LibrarySurface) => [...el.shadowRoot!.querySelectorAll<Band>('jf-confirm-band')];
async function answer(band: Band, which: 'confirm' | 'keep') {
  await band.updateComplete;
  band.shadowRoot!.querySelector<Answer>(`jf-button.${which}`)!.onActivate();
}
function button(el: LibrarySurface, label: string) {
  const found = [...el.shadowRoot!.querySelectorAll<Answer>('jf-button')].find(b => b.getAttribute('label') === label);
  expect(found, label).toBeDefined();
  return found!;
}

async function mountFolders(rowsAtStart: IndexedRootView[], invoke = vi.fn().mockResolvedValue({ success: true })) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{}', { status: 503 }));
  __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: 0, indexHealthy: true }, migration: { migrationState: 'IDLE' } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  let rows = rowsAtStart;
  const fetch = vi.fn(async (path: string) => path.startsWith('/api/indexing-roots/substrate')
    ? new Response(JSON.stringify({ items: rows })) : new Response('{}', { status: 503 }));
  const confirm = vi.fn().mockResolvedValue(true);
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = { platform: { capabilities: new Set() }, utilities: { formatRelativeTime: () => 'just now' },
    data: { fetch, invokeOperation: invoke }, ui: { showConfirmDialog: confirm } } as unknown as PluginHostApi;
  el.resolvedPaths = { a: '/home/sam/Notes', b: '/home/sam/Projects' };
  document.body.append(el);
  await pump(el);
  for (const toggle of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')) toggle.click();
  await pump(el);
  return { el, invoke, confirm, setRows: (next: IndexedRootView[]) => { rows = next; } };
}

afterEach(() => {
  document.body.replaceChildren(); __resetAiStateForTest(); __resetOwnReversalsForTest(); vi.restoreAllMocks();
});

describe('Library Remove asks in place (DE1)', () => {
  it('opens the band under the folder with its consequence and verb, focuses Keep it, and keeping removes nothing', async () => {
    const { el, invoke, confirm } = await mountFolders([root('a'), root('b')]);
    const remove = button(el, 'Remove from library');
    remove.focus();
    remove.onActivate(); await pump(el);
    const [band] = bands(el);
    expect(band!.closest('.card')?.textContent).toContain('Notes');
    expect(band!.heading).toBe('Remove Notes from your library?');
    expect(band!.consequence).toContain('Its 1,420 files leave search');
    expect(band!.consequence).toContain('Your files on disk stay where they are');
    expect(band!.consequence).toContain('adding Notes again reads it from scratch');
    expect(band!.actionLabel).toBe('Remove Notes');
    await band!.updateComplete; await pump(el);
    expect(band!.shadowRoot!.activeElement).toBe(band!.shadowRoot!.querySelector('jf-button.keep'));
    await answer(band!, 'keep'); await pump(el);
    expect(bands(el)).toHaveLength(0);
    expect(invoke).not.toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
  });

  it('a double-click on the verb removes once; Escape while it runs does not cancel it', async () => {
    let finish!: (value: { success: boolean }) => void;
    const invoke = vi.fn(() => new Promise<{ success: boolean }>(resolve => { finish = resolve; }));
    const { el, setRows } = await mountFolders([root('a'), root('b')], invoke);
    button(el, 'Remove from library').onActivate(); await pump(el);
    const [band] = bands(el);
    await answer(band!, 'confirm'); await answer(band!, 'confirm'); await pump(el);
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(invoke).toHaveBeenCalledWith('core.remove-watched-root', { path: '/home/sam/Notes' });
    expect(bands(el)).toHaveLength(0);
    el.shadowRoot!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    setRows([root('b')]); finish({ success: true }); await pump(el);
    expect(el.roots.map(r => r.pathHash)).toEqual(['b']);
    expect(invoke).toHaveBeenCalledTimes(1);
  });

  it('a second window removed the same folder while the band was open: the band goes and nothing is removed twice', async () => {
    const { el, invoke, setRows } = await mountFolders([root('a'), root('b')]);
    button(el, 'Remove from library').onActivate(); await pump(el);
    expect(bands(el)).toHaveLength(1);
    setRows([root('b')]);
    await (el as unknown as { refresh(o?: object): Promise<boolean> }).refresh({ live: true, force: true }); await pump(el);
    expect(bands(el)).toHaveLength(0);
    expect(el.removeConfirm).toBeNull();
    // The folder comes back (added again elsewhere): no stale question reappears with it.
    setRows([root('a'), root('b')]);
    await (el as unknown as { refresh(o?: object): Promise<boolean> }).refresh({ live: true, force: true }); await pump(el);
    expect(bands(el)).toHaveLength(0);
    expect(invoke).not.toHaveBeenCalled();
  });

  it('Remove offered by a missing folder\'s problem asks under that problem', async () => {
    const { el, invoke } = await mountFolders([root('a', { status: 'unavailable' }), root('b')]);
    const fromProblem = [...el.shadowRoot!.querySelectorAll<Answer>('.attention-item jf-button[label="Remove from library"]')][0]!;
    fromProblem.onActivate(); await pump(el);
    const [band] = bands(el);
    expect(band!.closest('.attention-item')).not.toBeNull();
    expect(band!.closest('.card')).toBeNull();
    await answer(band!, 'confirm'); await pump(el);
    expect(invoke).toHaveBeenCalledWith('core.remove-watched-root', { path: '/home/sam/Notes' });
  });
});

describe('Library skip-rule Undo (DE6)', () => {
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  function skipRules(refuseUndo?: { errorCode: string }) {
    const writes: { patterns: unknown[]; revision: number }[] = [];
    let revision = 0; let saved: unknown[] = ['node_modules/'];
    const fetch: PluginHostApi['data']['fetch'] = async (path, init) => {
      if (path === '/api/indexing/excludes') return json({ source: 'settings.json', sourceDetail: null, patterns: saved });
      if (init?.body) {
        const attempt = JSON.parse(String(init.body));
        writes.push({ patterns: attempt.ui.excludePatterns, revision: attempt.witness.acceptedRevision });
        if (refuseUndo && writes.length > 1) return json(refuseUndo, 409);
        revision += 1; saved = attempt.ui.excludePatterns;
        return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
          witness: { acceptedRevision: revision, lastCommittedOperationKey: attempt.operationKey } });
      }
      return json({ ui: { excludePatterns: saved }, witness: { acceptedRevision: revision, lastCommittedOperationKey: null } });
    };
    const el = document.createElement('jf-library-surface') as unknown as HTMLElement & {
      host_: PluginHostApi; excludesText: string; updateComplete: Promise<unknown>;
      loadExcludes(): Promise<void>; handleSaveExcludes(): Promise<void>; refresh(): Promise<void> };
    el.host_ = createMockHostApi({ data: { fetch, invokeOperation: vi.fn() } });
    el.refresh = vi.fn().mockResolvedValue(undefined);
    return { el, writes };
  }

  it('saving an added rule offers Undo in the pill; Undo writes the rules back once, bound to that save', async () => {
    const seen = notices();
    try {
      const { el, writes } = skipRules();
      await el.loadExcludes();
      el.excludesText = 'node_modules/\n*.log';
      await el.handleSaveExcludes();
      const offer = seen.list.find(n => n.message === 'Skip rule added')!;
      expect(offer.classId).toBe('core.own-action');
      expect(offer.actionLabel).toBe('Undo');
      expect(offer.reversal).toBe(true);
      expect(offer.detail).toContain('*.log is skipped from now on');
      expect(offer.actionAvailable!()).toBe(true);
      offer.onAction!(); offer.onAction!();
      await vi.waitFor(() => expect(seen.list.map(n => n.message)).toContain('Skip rules are back as they were'));
      expect(writes).toEqual([{ patterns: ['node_modules/', '*.log'], revision: 0 }, { patterns: ['node_modules/'], revision: 1 }]);
      expect(offer.actionAvailable!()).toBe(false);
      expect(undoLatestReversal()).toBe(false);
    } finally { seen.stop(); }
  });

  it('a removed rule says so, and Ctrl+Z after the notice runs the same reversal', async () => {
    const seen = notices();
    try {
      const { el, writes } = skipRules();
      await el.loadExcludes();
      el.excludesText = '';
      await el.handleSaveExcludes();
      expect(seen.list.find(n => n.message === 'Skip rule removed')?.detail).toContain('node_modules/ is no longer skipped');
      expect(undoLatestReversal()).toBe(true);
      await vi.waitFor(() => expect(writes).toHaveLength(2));
      expect(writes[1]!.patterns).toEqual(['node_modules/']);
    } finally { seen.stop(); }
  });

  it('after the rules were saved again elsewhere, Undo closes and says why instead of overwriting', async () => {
    const seen = notices();
    try {
      const { el, writes } = skipRules({ errorCode: 'VERSION_CONFLICT' });
      await el.loadExcludes();
      el.excludesText = 'node_modules/\n*.log';
      await el.handleSaveExcludes();
      const offer = seen.list.find(n => n.message === 'Skip rule added')!;
      offer.onAction!();
      await vi.waitFor(() => expect(seen.list.some(n => n.message.startsWith("Can't undo:"))).toBe(true));
      expect(seen.list.find(n => n.message.startsWith("Can't undo:"))!.message).toContain('saved again since');
      expect(offer.actionAvailable!()).toBe(false);
      offer.onAction!(); await Promise.resolve();
      expect(writes).toHaveLength(2);
    } finally { seen.stop(); }
  });

  it('saving with nothing changed offers no Undo', async () => {
    const seen = notices();
    try {
      const { el } = skipRules();
      await el.loadExcludes();
      el.excludesText = 'node_modules/\n';
      await el.handleSaveExcludes();
      expect(seen.list.filter(n => n.reversal)).toEqual([]);
    } finally { seen.stop(); }
  });
});
