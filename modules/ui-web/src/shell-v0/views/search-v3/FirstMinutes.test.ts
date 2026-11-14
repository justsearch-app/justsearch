// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Sv3Composer } from './Sv3Composer.js';
import { setFirstUseRoots } from '../../state/firstUse.js';
import { __resetAiStateForTest } from '../../state/aiStateStore.js';
import { setShowDeveloperTools } from '../../state/developerToolsState.js';
import { FolderAddFlow, emptyFolderAddState } from '../../controllers/FolderAddFlow.js';
import type { PluginHostApi } from '../../plugin-api/plugin-types.js';

beforeEach(() => { __resetAiStateForTest(); setFirstUseRoots(null, new Map()); setShowDeveloperTools(false); });
afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); });
const mount = async (tier: 'search' | 'ask' | 'structured' | 'delegate' = 'search') => {
  const el = new Sv3Composer(); el.tier = tier; el.state = 'hero';
  document.body.append(el); await el.updateComplete; return el;
};
const get = (el: Sv3Composer, id: string) => el.shadowRoot!.querySelector(`[data-testid="${id}"]`);

it('S4-13 Search has a magnifier and headline label, with no prompt, send or effort', async () => {
  const el = await mount();
  const field = get(el, 'sv3-composer-input')!;
  expect(field.getAttribute('aria-labelledby')).toBe('sv3-search-headline');
  expect(get(el, 'sv3-search-icon')).not.toBeNull();
  for (const id of ['sv3-composer-placeholder', 'sv3-composer-send', 'sv3-composer-effort', 'sv3-composer-tier']) expect(get(el, id), id).toBeNull();
  el.state = 'docked'; await el.updateComplete;
  expect(get(el, 'sv3-composer-input')!.getAttribute('aria-label')).toBe('Search');
});
it('S4-13 newlines are stopped in Search, modifier chords never silently Delegate; restored drafts survive', async () => {
  const el = await mount(); el.draft = 'old\nexact draft'; await el.updateComplete;
  expect((get(el, 'sv3-composer-input') as HTMLTextAreaElement).value).toBe('old\nexact draft');
  const sends: unknown[] = []; el.addEventListener('sv3-composer-submit', e => sends.push((e as CustomEvent).detail));
  const field = get(el, 'sv3-composer-input')!;
  for (const modifiers of [{ shiftKey: true }, { ctrlKey: true }, { metaKey: true }, { altKey: true }, { isComposing: true }]) {
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...modifiers }));
  }
  expect(sends).toEqual([]);
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
  expect(sends).toEqual([{ query: 'old\nexact draft', tier: 'search' }]);
});
it('S4-11 recents are an in-flow list of searches with count/time, keyboard reachable, never empty chrome', async () => {
  const el = await mount(); expect(get(el, 'sv3-recents')).toBeNull();
  el.recentItems = [{ kind: 'search', label: 'budget', value: 'budget', count: '1,000+ results', at: 100 } as never];
  await el.updateComplete;
  expect(get(el, 'sv3-recents')?.textContent).toContain('1,000+ results');
  expect(get(el, 'sv3-recents')?.querySelector('time')).not.toBeNull();
  expect(get(el, 'sv3-recents')?.querySelector('button[tabindex="-1"]')).toBeNull();
});
for (const tier of ['ask', 'structured', 'delegate'] as const) it(`S4-14 restored ${tier} always offers Search without discarding draft/schema`, async () => {
  const el = await mount(tier); el.draft = 'keep\nthis'; el.schemaDraft = '{"type":"object"}'; await el.updateComplete;
  (get(el, 'sv3-composer-tier') as HTMLButtonElement).click(); await el.updateComplete;
  expect([...el.shadowRoot!.querySelectorAll('[data-tier]')].map(e => e.getAttribute('data-tier'))).toEqual(['search', tier]);
  expect(el.draft).toBe('keep\nthis'); expect(el.schemaDraft).toBe('{"type":"object"}');
  expect(get(el, 'sv3-composer-effort')).not.toBeNull();
});

function flow(pickFolder: () => Promise<string | null>, firstLaunch: boolean) {
  const state = emptyFolderAddState();
  const invokeOperation = vi.fn(async () => ({ success: true })); const focus = vi.fn(async () => {});
  const api = { platform: { capabilities: new Set(['folder-picker']), pickFolder },
    data: { invokeOperation, fetch: vi.fn(async () => new Response('{}')) } } as unknown as PluginHostApi;
  const instance = new FolderAddFlow({ state, api: () => api, current: () => () => true, changed() {},
    open: async () => {}, focus, added: async () => {} }, firstLaunch);
  return { instance, state, invokeOperation, focus };
}
it('S4-3 native first-launch selection adds once immediately, without confirmation', async () => {
  const f = flow(async () => '/notes', true); await f.instance.open();
  expect(f.invokeOperation).toHaveBeenCalledExactlyOnceWith('core.add-watched-root', { path: '/notes' });
  expect(f.state.showManualInput).toBe(false); f.instance.dispose();
});
it('S4-4 native cancel restores focus; picker error exposes an actionable manual fallback', async () => {
  const cancel = flow(async () => null, true); await cancel.instance.open();
  expect(cancel.invokeOperation).not.toHaveBeenCalled(); expect(cancel.focus).toHaveBeenCalledWith('button');
  const fail = flow(async () => { throw new Error('dialog unavailable'); }, true); await fail.instance.open();
  expect(fail.state.error).toContain('dialog unavailable'); expect(fail.state.showManualInput).toBe(true);
  expect(fail.focus).toHaveBeenCalledWith('path'); cancel.instance.dispose(); fail.instance.dispose();
});
it('S4-20 Library selection still requires confirmation and keeps Collection', async () => {
  const f = flow(async () => '/notes', false); await f.instance.open();
  expect(f.invokeOperation).not.toHaveBeenCalled(); expect(f.state.showManualInput).toBe(true);
  expect(f.state.pendingPath).toBe('/notes'); f.instance.dispose();
});
