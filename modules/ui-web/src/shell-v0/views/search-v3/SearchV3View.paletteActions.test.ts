// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell S6 SP7 — Search's own palette is retired. Its two working commands are actions in the app's
 * one palette, under their ids, with exactly one owning Search window; the seven inert entries are
 * gone; Ctrl+K reaches the app's one palette.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import '../../commands/CommandPalette.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import type { CommandPalette } from '../../commands/CommandPalette.js';
import { getAction } from '../../substrates/actions/index.js';
import { invokeCommandWithResult } from '../../commands/CommandRegistry.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetModalityForTest } from '../../primitives/modality.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../../controllers/navigateRequest.js';

const INERT = ['Scope to all sources', 'Scope to the last 7 days', 'Find documents like this one',
  'Explain why this result ranked', 'Manage indexed folders', 'Reindex this folder', 'Open indexing diagnostics'];
let http: ReturnType<typeof vi.fn<(url: unknown, init?: RequestInit) => Promise<Response>>>;
const composer = (view: SearchV3View): Sv3Composer => view.shadowRoot!.querySelector('jf-sv3-composer')!;
const searches = (): string[] => http.mock.calls.filter(call => String(call[0]).includes('/api/knowledge/search'))
  .map(call => String((JSON.parse(String(call[1]?.body ?? '{}')) as { query?: string }).query ?? ''));
async function settle(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}
async function mount(parent: ParentNode = document.body): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  parent.append(view); await settle(view); return view;
}
async function palette(): Promise<CommandPalette> {
  const shown = document.createElement('jf-command-palette') as CommandPalette;
  document.body.append(shown); shown.show(); await shown.updateComplete; return shown;
}
async function type(shown: CommandPalette, text: string): Promise<void> {
  const field = shown.shadowRoot!.querySelector('input')!;
  field.value = text; field.dispatchEvent(new Event('input')); await shown.updateComplete;
}
const press = (shown: CommandPalette, key: string): void => {
  shown.shadowRoot!.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
};
const labels = (shown: CommandPalette): string[] => shown.results.map(row => row.entry.label);

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest(); __resetModalityForTest();
  http = vi.fn(async (url: unknown) => String(url).includes('/api/knowledge/search')
    ? Response.json({ results: [{ id: 'chosen', fields: { path: '/chosen.txt' } }], totalHits: 1, matchCount: 1 })
    : String(url).includes('/api/indexing/forgets?') ? Response.json({ items: [], complete: true, nextId: null }) : Response.json({}));
  vi.stubGlobal('fetch', http);
  __feedForTest({ inference: { available: true } as never, status: {
    inference: { chatEnabledSpec: true }, worker: { core: { indexedDocuments: 42 } },
    operating: { chat: { state: 'READY' } },
    readiness: { composites: { aiFeatures: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot });
  __feedContactForTest();
});
afterEach(() => {
  document.body.replaceChildren(); resetSearchState(); __resetAiStateForTest(); __resetModalityForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it('SP7 Search\'s working commands are app palette actions under their ids; the seven inert entries and the second palette are gone', async () => {
  const view = await mount();
  expect(getAction('cmd-everything')?.title).toBe('Search everything');
  expect(getAction('cmd-memory')?.title).toBe('Open AI Brain › Memory');
  const shown = await palette();
  await type(shown, '>');
  expect(labels(shown)).toEqual(expect.arrayContaining(['Search everything', 'Open AI Brain › Memory']));
  for (const words of INERT) {
    await type(shown, words);
    expect(labels(shown), words).not.toContain(words);
  }
  expect(view.shadowRoot!.querySelector('jf-sv3-palette, [role="dialog"], [role="combobox"]')).toBeNull();
});

it('SP7 Search has no second title band or palette toggle button', async () => {
  const view = await mount();
  expect(view.shadowRoot!.querySelector('jf-sv3-topbar, [data-testid="sv3-topbar-palette"]')).toBeNull();
});

it('SP7 exactly one owner across departure, retained return, split-layout re-parenting and previews; teardown removes only its own', async () => {
  const first = await mount();
  const owned = getAction('cmd-everything');
  expect(owned).toBeDefined();
  // A preview of Search (Alt peek) never owns the actions.
  const preview = document.createElement('jf-sv3-window') as SearchV3View;
  preview.setAttribute('data-preview-mode', 'true');
  document.body.append(preview); await settle(preview);
  expect(getAction('cmd-everything')).toBe(owned);
  preview.remove();
  expect(getAction('cmd-everything')).toBe(owned);
  // A split-layout re-parent moves the same window: the same registration stays.
  const pane = document.createElement('div'); document.body.append(pane);
  pane.append(first); await settle(first);
  expect(getAction('cmd-everything')).toBe(owned);
  // Departure takes them away; a retained window's return brings back its own.
  first.remove();
  expect(getAction('cmd-everything')).toBeUndefined();
  expect(getAction('cmd-memory')).toBeUndefined();
  pane.append(first); await settle(first);
  expect(getAction('cmd-everything')).toBe(owned);
  // A second window owns them while connected; the first's teardown leaves the second's alone.
  const second = await mount();
  const secondOwned = getAction('cmd-everything');
  expect(secondOwned).not.toBe(owned);
  composer(first).draft = 'first draft'; composer(second).draft = 'second draft';
  await settle(first); await settle(second);
  expect((await invokeCommandWithResult('cmd-everything')).ok).toBe(true);
  await settle(second);
  expect(searches()).toEqual(['second draft']);
  first.remove();
  expect(getAction('cmd-everything')).toBe(secondOwned);
  second.remove();
  expect(getAction('cmd-everything')).toBeUndefined();
  // With both connected, the second leaving hands the actions back to the first.
  pane.append(first); await settle(first);
  const again = await mount();
  again.remove();
  expect(getAction('cmd-everything')).toBe(owned);
});

it('SP7 "Search everything" searches the composer draft and "Search for" the palette text; opening, filtering or cancelling submits neither', async () => {
  const view = await mount();
  composer(view).draft = 'draft words'; await settle(view);
  const arrivals: unknown[] = [];
  const listen = (e: Event): void => { arrivals.push((e as CustomEvent).detail); };
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listen);
  try {
    let shown = await palette();
    await type(shown, 'palette words'); await settle(view);
    press(shown, 'Escape'); await settle(view);
    expect(shown.open).toBe(false);
    expect(searches()).toEqual([]);
    expect(arrivals).toEqual([]);

    shown.show(); await shown.updateComplete;
    await type(shown, 'Search everything');
    expect(shown.results[shown.selectedIndex]?.entry.id).toBe('cmd-everything');
    press(shown, 'Enter'); await settle(view);
    expect(searches()).toEqual(['draft words']);
    expect(composer(view).draft).toBe('draft words');

    shown.remove(); shown = await palette();
    await type(shown, 'palette words');
    shown.selectedIndex = shown.results.length - 1;
    expect(shown.results[shown.selectedIndex]?.entry.id).toBe('intent.search');
    press(shown, 'Enter'); await settle(view);
    expect(arrivals).toEqual([{ surfaceId: 'core.search-v3-surface', state: { query: 'palette words' } }]);
  } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listen); }
});
