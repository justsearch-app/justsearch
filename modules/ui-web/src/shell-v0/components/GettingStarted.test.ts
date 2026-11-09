// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import './WalkthroughCard.js';
import { __resetUserStateForTest, getWalkthroughProgress, markWalkthroughStepComplete, setWalkthroughActiveStep } from '../state/UserStateDocument.js';
import { registerWalkthrough, unregisterWalkthrough, __resetWalkthroughsForTest } from '../commands/WalkthroughRegistry.js';
import { makeCoreProvenance } from '../primitives/provenance.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from '../state/aiStateStore.js';
import { submitSearch, setQuery, resetSearchState } from '../state/searchState.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
const files = import.meta.glob('./GettingStarted.ts', { eager: true });
const feature = Object.values(files)[0] as typeof import('./GettingStarted.js') | undefined;
const states = import.meta.glob('../state/firstUse.ts', { eager: true });
const state = Object.values(states)[0] as typeof import('../state/firstUse.js') | undefined;
const response = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
const success = vi.fn(async () => ({ success: true }));
const pick = vi.fn(async (): Promise<string | null> => null);
const capabilities = new Set<string>();
const host = { platform: { capabilities, pickFolder: pick }, data: {
  invokeOperation: success, fetch: vi.fn(async () => response({ exists: true, isDir: true })) },
} as unknown as PluginHostApi;
type Card = HTMLElement & { updateComplete: Promise<unknown>; host_: PluginHostApi };
async function pump(el: Card) { for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; } }
async function mount() {
  const card = document.createElement('jf-getting-started') as Card;
  card.host_ = host; document.body.append(card); await pump(card); return card;
}
function feed(count: number, pending = 0) {
  __feedForTest({ status: { worker: { core: { indexState: pending ? 'INDEXING' : 'IDLE', indexedDocuments: count, searchableDocuments: count, pendingJobs: pending } } } as never });
  __feedContactForTest();
}
async function activate(card: Card, label: string) {
  const action = [...card.shadowRoot?.querySelectorAll('jf-button') ?? []].find(b => b.getAttribute('label') === label) as HTMLElement & { onActivate?: () => unknown };
  expect(action, `${label} must be reachable`).toBeTruthy();
  action?.onActivate?.(); await pump(card);
}
function type(card: Card, path: string) {
  const field = card.shadowRoot?.querySelector<HTMLInputElement>('[aria-label="Folder path"]');
  expect(field).toBeTruthy();
  if (field) { field.value = path; field.dispatchEvent(new Event('input')); }
}
beforeEach(() => {
  document.body.replaceChildren(); __resetUserStateForTest(); __resetAiStateForTest();
  __resetWalkthroughsForTest();
  state?.setFirstUseRoots([], new Map()); feature?.resetFirstUseAddForTest();
  success.mockReset().mockResolvedValue({ success: true }); pick.mockReset().mockResolvedValue(null);
  capabilities.clear();
  vi.stubGlobal('fetch', vi.fn(async () => response({ results: [], suggestions: [{ label: 'Documents', path: '/Documents' }] })));
  feed(0);
});
afterEach(() => { document.body.replaceChildren(); resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); });
describe('Getting started interactions', () => {
  it('a pending plugin tour appears on its own after core completion and keeps saved progress on removal', async () => {
    state?.setFirstUseRoots([{ pathHash: 'notes' }], new Map([['notes', '/notes']])); feed(2);
    markWalkthroughStepComplete('core.getting-started', 'try-search');
    markWalkthroughStepComplete('sample.tour', 'one'); setWalkthroughActiveStep('sample.tour', 1);
    const card = await mount();
    registerWalkthrough({ id: 'sample.tour', title: 'Sample tour', source: 'plugin', priority: 0,
      provenance: makeCoreProvenance(), steps: [{ id: 'one', title: 'One', body: 'First' }, { id: 'two', title: 'Two', body: 'Second' }] });
    await pump(card);
    const plugin = card.shadowRoot?.querySelector('jf-walkthrough-card') as Card | null;
    expect(plugin, 'pending plugin must remain discoverable without Help').toBeTruthy();
    await plugin?.updateComplete;
    expect(plugin?.shadowRoot?.textContent).toContain('Sample tour');
    expect(plugin?.shadowRoot?.textContent).toContain('Two');
    expect(card.shadowRoot?.textContent).not.toContain('Try a search');
    unregisterWalkthrough('sample.tour'); await pump(card);
    expect(card.shadowRoot?.textContent?.trim()).toBe('');
    expect(getWalkthroughProgress('sample.tour')).toMatchObject({ activeStepIndex: 1, completedStepIds: ['one'] });
  });
  it('suggestions are deduplicated and watched paths are excluded even without a worker', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ suggestions: [
      { label: 'Documents', path: '/Documents' }, { label: 'Again', path: '/Documents/' }, { label: 'Notes', path: '/notes' },
    ] })));
    state?.setFirstUseRoots([], new Map());
    const card = await mount();
    expect(card.shadowRoot?.querySelectorAll('.suggestions jf-button')).toHaveLength(2);
    state?.setFirstUseRoots([{ pathHash: 'documents' }], new Map([['documents', '/Documents']]));
    __resetAiStateForTest(); await pump(card);
    expect(card.shadowRoot?.textContent).not.toContain('/Documents');
    expect(success).not.toHaveBeenCalled();
  });
  for (const payload of [[], null]) it(`missing suggestions (${payload === null ? 'failure' : 'empty'}) leave Add a folder usable`, async () => {
    vi.stubGlobal('fetch', vi.fn(async () => payload === null ? new Response('', { status: 503 }) : response({ suggestions: payload })));
    const card = await mount();
    expect(card.shadowRoot?.querySelector('.suggestions')).toBeNull();
    await activate(card, 'Add a folder');
    expect(card.shadowRoot?.querySelector('[aria-label="Folder path"]')).toBeTruthy();
  });
  it('a suggestion repeated during and after acceptance adds exactly once', async () => {
    const card = await mount();
    let resolve!: (value: { success: boolean }) => void;
    success.mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    await activate(card, 'Add Documents'); await activate(card, 'Add Documents');
    expect(success).toHaveBeenCalledTimes(1);
    resolve({ success: true }); await pump(card);
    expect(card.shadowRoot?.textContent).not.toContain('/Documents');
    expect(success).toHaveBeenCalledTimes(1);
  });
  it('adds through the Library operation, guards duplicate activation and never calls acceptance ready', async () => {
    const card = await mount(); await activate(card, 'Add a folder'); type(card, '/notes');
    let resolve!: (value: { success: boolean }) => void;
    success.mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    await activate(card, 'Add'); await activate(card, 'Add');
    expect(success).toHaveBeenCalledTimes(1); expect(success).toHaveBeenCalledWith('core.add-watched-root', { path: '/notes' });
    resolve({ success: true }); await pump(card);
    expect(card.shadowRoot?.textContent).not.toContain('ready to search');
  });
  it('desktop cancel leaves facts alone and restores focus to Add a folder', async () => {
    capabilities.add('folder-picker'); const card = await mount();
    await activate(card, 'Add a folder');
    expect(pick).toHaveBeenCalledTimes(1); expect(success).not.toHaveBeenCalled();
    expect(card.shadowRoot?.querySelector('[aria-label="Folder path"]')).toBeNull();
    expect(card.shadowRoot?.activeElement?.getAttribute('label')).toBe('Add a folder');
  });
  it('failure stays actionable after leaving and returning during the pending add', async () => {
    const card = await mount(); await activate(card, 'Add a folder'); type(card, '/missing');
    let reject!: (e: Error) => void;
    success.mockImplementationOnce(() => new Promise((_, r) => { reject = r; }));
    await activate(card, 'Add'); card.remove();
    reject(new Error('Path not found')); const again = await mount(); await pump(again);
    expect(again.shadowRoot?.textContent).toContain('Path not found');
    expect(again.shadowRoot?.querySelector('[aria-label="Folder path"]')).toBeTruthy();
    expect(again.shadowRoot?.activeElement?.getAttribute('aria-label')).toBe('Folder path');
    await activate(again, 'Add'); expect(success).toHaveBeenCalledTimes(2);
  });
  for (const reason of ['Path not found', 'Already watched', 'This folder contains a watched folder']) {
    it(`suggested folder refusal: ${reason}`, async () => {
      const card = await mount(); success.mockResolvedValueOnce({ success: false, message: reason } as never);
      await activate(card, 'Add Documents');
      expect(success).toHaveBeenCalledTimes(1); expect(card.shadowRoot?.textContent).toContain(reason);
      expect((getWalkthroughProgress('core.getting-started')?.completedStepIds ?? [])).not.toContain('try-search');
    });
  }
  it('zero searchable, a returned search, then a first document does not finish until another search returns', async () => {
    const card = await mount(); state?.setFirstUseRoots([{ pathHash: 'notes' }], new Map([['notes', '/notes']]));
    setQuery('notes'); submitSearch(); await pump(card); feed(1); await pump(card);
    expect((getWalkthroughProgress('core.getting-started')?.completedStepIds ?? [])).not.toContain('try-search');
    expect(card.shadowRoot?.textContent).toContain('Try a search');
    setQuery('notes again'); submitSearch(); await pump(card);
    expect(getWalkthroughProgress('core.getting-started')?.completedStepIds).toContain('try-search');
    expect(card.shadowRoot?.textContent?.trim()).toBe('');
  });
  it('reload during indexing uses the live count and a watched folder, not saved step counters', async () => {
    state?.setFirstUseRoots([{ pathHash: 'notes' }], new Map([['notes', '/notes']])); feed(2, 8);
    const card = await mount(); expect(card.shadowRoot?.textContent).toContain('2 files searchable so far');
    card.remove(); const reloaded = await mount();
    expect(reloaded.shadowRoot?.textContent).toContain('8 waiting to be read');
    expect(reloaded.shadowRoot?.textContent).toContain('Try a search');
  });
});
