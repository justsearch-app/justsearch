// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell S6 candidate B — the one palette's overlay: Tab actions (SP5), keyboard and accessibility
 * (SP8), dismissal (SP9) and the Alt peek's cleanup (SP10 c). Providers are candidate A's.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { CommandPalette } from './CommandPalette.js';
import { registerAction, unregisterAction, __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetForTest as resetCommands } from './CommandRegistry.js';
import { __resetForTest as resetBindings } from './KeybindingRegistry.js';
import { __resetShellContextForTest } from '../state/shellContextState.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetModalityForTest } from '../primitives/modality.js';
import { __resetInMemoryStateForTest } from '../state/UserStateDocument.js';
import { __resetConversationListForTest, loadConversations } from '../state/conversationListStore.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { subscribeLibraryRoot, type LibraryRootRequest } from '../router/libraryRootIntent.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';
import type { Surface } from '../../api/types/surface.js';

let roots: { pathHash: string; fileCount: number }[];
let sessions: { sessionId: string; title: string; firstUserMessage: string; lastActiveAt?: number }[];
let rootsStatus = 200;
let conversationsHeld: Promise<void> | null = null;
const flush = async (): Promise<void> => { for (let i = 0; i < 40; i++) await Promise.resolve(); };
const action = (id: string, title: string) => ({ id, title, provenance: CORE_PROVENANCE, handler: vi.fn(() => ({ kind: 'noop' as const })) });
const surface = (id: string): Surface => ({ id, mountTag: 'jf-overlay-probe', presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } });

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetInMemoryStateForTest(); __resetShellContextForTest(); __resetActionsForTest(); resetCommands(); resetBindings();
  __resetConversationListForTest(); __resetAiStateForTest(); __resetModalityForTest(); resetCatalog();
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: [surface('core.search-v3-surface'), surface('core.library-surface'), surface('core.brain-surface')] });
  roots = []; sessions = []; rootsStatus = 200; conversationsHeld = null;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
    const url = String(input);
    if (url.includes('/api/indexing-roots/substrate')) return rootsStatus === 200 ? Response.json({ items: roots }) : new Response('', { status: rootsStatus });
    if (url.includes('/api/chat/conversations?')) { if (conversationsHeld) await conversationsHeld; return Response.json({ sessions }); }
    if (url.includes('/api/settings/v2')) return Response.json({ ui: {}, settingsMode: 'read_write' });
    return Response.json({});
  });
});
afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); resetCatalog(); __resetModalityForTest(); });

const shadow = (palette: CommandPalette): ShadowRoot => palette.shadowRoot!;
const field = (palette: CommandPalette): HTMLInputElement => shadow(palette).querySelector('input')!;
function key(palette: CommandPalette, init: KeyboardEventInit): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, composed: true, cancelable: true, ...init });
  const target = shadow(palette).querySelector('input');
  expect(target, `the palette's field, for ${init.key}`).not.toBeNull();
  target!.dispatchEvent(event);
  return event;
}
async function open(query = ''): Promise<CommandPalette> {
  const palette = new CommandPalette(); document.body.append(palette); palette.show(); await flush();
  if (query) await type(palette, query);
  await palette.updateComplete;
  return palette;
}
async function type(palette: CommandPalette, query: string): Promise<void> {
  field(palette).value = query; field(palette).dispatchEvent(new Event('input')); await palette.updateComplete;
}
async function select(palette: CommandPalette, id: string): Promise<void> {
  palette.selectedIndex = palette.results.findIndex(row => row.entry.id === id);
  expect(palette.selectedIndex, `no result ${id}`).toBeGreaterThanOrEqual(0);
  await palette.updateComplete;
}
const actionLabels = (palette: CommandPalette): string[] =>
  [...shadow(palette).querySelectorAll('#palette-actions [role="option"]')].map(row => row.textContent?.trim() ?? '');
const status = (palette: CommandPalette): string => shadow(palette).querySelector('[role="status"]')?.textContent ?? '';

it('SP5 Tab shows each result kind\'s actions, primary first; Escape returns to the results', async () => {
  registerAction(action('core.action.shell.demo-tool', 'Demo tool'));
  roots = [{ pathHash: 'notes', fileCount: 3 }];
  sessions = [{ sessionId: 'conv-a', title: 'Remote work policy', firstUserMessage: 'Remote work?', lastActiveAt: 5 }];
  await loadConversations();
  const palette = await open();
  const firstSettingsRow = palette.results.find(row => row.entry.group === 'Settings')!.entry.id;
  for (const [id, labels] of [
    ['shell.demo-tool', ['Run']],
    [firstSettingsRow, ['Open']],
    ['folder:notes', ['Open in Library', 'Remove from Library']],
    ['conversation:conv-a', ['Open']],
    ['area:core.library-surface', ['Open']],
  ] as const) {
    await select(palette, id);
    expect(key(palette, { key: 'Tab' }).defaultPrevented).toBe(true);
    await palette.updateComplete;
    expect(actionLabels(palette), id).toEqual(labels);
    expect(field(palette).getAttribute('aria-controls')).toBe('palette-actions');
    key(palette, { key: 'Escape' }); await palette.updateComplete;
    expect(palette.open).toBe(true);
    expect(palette.results[palette.selectedIndex]?.entry.id).toBe(id);
    expect(shadow(palette).querySelector('#palette-actions')).toBeNull();
  }
});

it('SP5 Escape or Shift Tab returns with query, selection and scroll kept, and a second Escape closes', async () => {
  for (let index = 0; index < 6; index++) registerAction(action(`core.action.shell.tool-${index}`, `Tool ${index}`));
  const palette = await open('>Tool');
  for (const back of [{ key: 'Escape' }, { key: 'Tab', shiftKey: true }]) {
    await select(palette, 'shell.tool-4');
    const list = shadow(palette).querySelector<HTMLElement>('#palette-listbox');
    expect(list, 'the listbox the field controls, which is the scroll region').not.toBeNull();
    list!.scrollTop = 48;
    key(palette, { key: 'Tab' }); await palette.updateComplete;
    expect(actionLabels(palette)).toEqual(['Run']);
    key(palette, back); await palette.updateComplete; await palette.updateComplete;
    expect(field(palette).value).toBe('>Tool');
    expect(palette.results[palette.selectedIndex]?.entry.id).toBe('shell.tool-4');
    expect(shadow(palette).querySelector<HTMLElement>('#palette-listbox')?.scrollTop).toBe(48);
    expect(palette.open).toBe(true);
  }
  key(palette, { key: 'Escape' }); await palette.updateComplete;
  expect(palette.open).toBe(false);
});

it('SP5 a folder\'s Remove from Library hands off to Library\'s confirmation and never deletes from the palette', async () => {
  roots = [{ pathHash: 'notes', fileCount: 3 }, { pathHash: 'other', fileCount: 1 }];
  const requests: LibraryRootRequest[] = [], arrivals: unknown[] = [];
  const stop = subscribeLibraryRoot(request => requests.push(request));
  const listen = (e: Event): void => { arrivals.push((e as CustomEvent).detail); };
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listen);
  try {
    const palette = await open();
    await select(palette, 'folder:notes');
    key(palette, { key: 'Tab' }); await palette.updateComplete;
    key(palette, { key: 'ArrowDown' }); await palette.updateComplete;
    expect(field(palette).getAttribute('aria-activedescendant')).toBe('palette-action-1');
    key(palette, { key: 'Enter' }); await flush();
    expect(palette.open).toBe(false);
    expect(requests).toEqual([expect.objectContaining({ pathHash: 'notes', action: 'remove' })]);
    expect(arrivals).toEqual([{ surfaceId: 'core.library-surface' }]);
    const methods = vi.mocked(globalThis.fetch).mock.calls.map(call => (call[1]?.method ?? 'GET').toUpperCase());
    expect(methods.every(method => method === 'GET')).toBe(true);
  } finally { stop(); document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listen); }
});

it('SP5 with no result, Tab opens no stale actions and keeps focus in the dialog', async () => {
  registerAction(action('core.action.shell.demo-tool', 'Demo tool'));
  const palette = await open('>Demo');
  expect(palette.results.map(row => row.entry.id)).toEqual(['shell.demo-tool']);
  await type(palette, '>nothing-matches-this');
  expect(palette.results).toEqual([]);
  expect(key(palette, { key: 'Tab' }).defaultPrevented).toBe(true);
  await palette.updateComplete;
  expect(shadow(palette).querySelector('#palette-actions')).toBeNull();
  expect(key(palette, { key: 'Tab', shiftKey: true }).defaultPrevented).toBe(true);
  expect(palette.open).toBe(true);
});

it('SP5 keys that steer an IME composition invoke, move, show actions or dismiss nothing', async () => {
  const first = action('core.action.shell.alpha-tool', 'Alpha tool'), second = action('core.action.shell.beta-tool', 'Beta tool');
  registerAction(first); registerAction(second);
  const palette = await open('>tool');
  const selected = palette.selectedIndex;
  for (const name of ['ArrowDown', 'ArrowUp', 'Enter', 'Tab', 'Escape']) {
    key(palette, { key: name, isComposing: true }); await flush(); await palette.updateComplete;
  }
  key(palette, { key: 'Enter', keyCode: 229 } as KeyboardEventInit); await flush();
  expect(palette.open).toBe(true);
  expect(palette.selectedIndex).toBe(selected);
  expect(shadow(palette).querySelector('#palette-actions')).toBeNull();
  expect(first.handler).not.toHaveBeenCalled(); expect(second.handler).not.toHaveBeenCalled();
});

it('SP4-SP9 shown actions follow their result: a removed or replaced registration runs nothing and says so', async () => {
  const original = action('core.action.shell.demo-tool', 'Demo tool');
  registerAction(original);
  const palette = await open('>Demo');
  await select(palette, 'shell.demo-tool');
  key(palette, { key: 'Tab' }); await palette.updateComplete;
  expect(actionLabels(palette)).toEqual(['Run']);
  unregisterAction(original.id); await flush(); await palette.updateComplete;
  expect(actionLabels(palette)).toEqual([]);
  expect(status(palette)).toContain('Demo tool is no longer available.');
  const replacement = action('core.action.shell.demo-tool', 'Demo tool');
  registerAction(replacement); await flush(); await palette.updateComplete;
  expect(actionLabels(palette)).toEqual([]);
  key(palette, { key: 'Enter' }); await flush();
  expect(original.handler).not.toHaveBeenCalled(); expect(replacement.handler).not.toHaveBeenCalled();
  key(palette, { key: 'Escape' }); await palette.updateComplete;
  expect(palette.results.some(row => row.entry.id === 'shell.demo-tool')).toBe(true);
});

it('SP8 a modal dialog with a labelled combobox, a listbox of labelled groups, positions and count, and the active option scrolled into view', async () => {
  const scrolled = vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(() => {});
  for (let index = 0; index < 3; index++) registerAction(action(`core.action.shell.tool-${index}`, `Tool ${index}`));
  const palette = await open();
  const dialog = shadow(palette).querySelector('[role="dialog"]');
  expect(dialog, 'a dialog').not.toBeNull();
  expect(dialog!.getAttribute('aria-modal')).toBe('true');
  expect(dialog!.getAttribute('aria-label')).toBe('Command palette');
  const combobox = shadow(palette).querySelector('input[role="combobox"]');
  expect(combobox, 'a combobox').not.toBeNull();
  expect(combobox!.getAttribute('aria-label')).toBeTruthy();
  expect(combobox!.getAttribute('aria-expanded')).toBe('true');
  const listbox = shadow(palette).querySelector(`#${combobox!.getAttribute('aria-controls')}`)!;
  expect(listbox.getAttribute('role')).toBe('listbox');
  const groups = [...listbox.querySelectorAll('[role="group"]')];
  expect(groups.map(group => shadow(palette).getElementById(group.getAttribute('aria-labelledby') ?? '')?.textContent?.trim()))
    .toEqual(['Actions', 'Settings', 'Areas']);
  const options = [...listbox.querySelectorAll('[role="option"]')];
  const total = String(options.length > 0 ? Number(options[0]!.getAttribute('aria-setsize')) : 0);
  expect(Number(total)).toBeGreaterThanOrEqual(options.length);
  expect(options.map(option => option.getAttribute('aria-setsize'))).toEqual(options.map(() => total));
  expect(options.slice(0, 3).map(option => option.getAttribute('aria-posinset'))).toEqual(['1', '2', '3']);
  expect(combobox!.getAttribute('aria-activedescendant')).toBe(options[0]!.id);
  scrolled.mockClear();
  key(palette, { key: 'ArrowDown' }); await palette.updateComplete;
  expect(combobox!.getAttribute('aria-activedescendant')).toBe(options[1]!.id);
  expect(options[1]!.getAttribute('aria-selected')).toBe('true');
  expect(scrolled.mock.contexts).toContain(shadow(palette).querySelector('#palette-opt-1'));
});

it('SP8 the result count and the empty, loading and error states are announced', async () => {
  registerAction(action('core.action.shell.demo-tool', 'Demo tool'));
  let release!: () => void;
  conversationsHeld = new Promise<void>(resolve => { release = resolve; });
  const palette = await open('>Demo');
  expect(status(palette)).toContain('1 result.');
  expect(status(palette)).toContain('Still loading folders and conversations.');
  release(); await flush(); await palette.updateComplete;
  expect(status(palette)).not.toContain('Still loading');
  await type(palette, '>nothing-matches-this');
  expect(status(palette)).toContain('No results for ">nothing-matches-this".');
  palette.hide();
  rootsStatus = 500;
  const failing = await open('>Demo');
  await flush(); await failing.updateComplete;
  expect(status(failing)).toContain('Folders could not be loaded, so results may be incomplete.');
});

it('SP8 every match is reachable by arrow keys alone: moving past a group\'s first rows shows the rest', async () => {
  for (let index = 0; index < 20; index++) registerAction(action(`core.action.shell.reach-${index}`, `Reach ${String(index).padStart(2, '0')}`));
  const palette = await open('>Reach');
  expect(palette.results.length).toBeLessThan(20);
  for (let step = 0; step < 19; step++) { key(palette, { key: 'ArrowDown' }); await palette.updateComplete; }
  expect(palette.results[palette.selectedIndex]?.entry.label).toBe('Reach 19');
  expect(shadow(palette).querySelector('[data-selected] .result-label')?.textContent).toBe('Reach 19');
});

it('SP8 forced colours keep the active option and the field\'s focus distinguishable, and a short window keeps field and list', () => {
  const styles = (CommandPalette.styles as unknown as { cssText: string }).cssText;
  const forced = styles.slice(styles.indexOf('@media (forced-colors: active)'));
  expect(styles).toContain('@media (forced-colors: active)');
  expect(forced).toMatch(/\.result-row\[data-selected\]\s*\{\s*outline: 2px solid Highlight/);
  expect(forced).toMatch(/\.search-row input:focus-visible\s*\{\s*outline-color: Highlight/);
  const results = styles.slice(styles.indexOf('.results {'), styles.indexOf('}', styles.indexOf('.results {')));
  expect(results).toContain('min-height: 0');
  expect(results).toContain('overflow-y: auto');
  // The scroll region is the listbox the field controls (the combobox's popup), so keyboard users
  // scroll it from the field; it shrinks before the area around it does.
  const rule = '.results > [role="listbox"] {';
  expect(styles).toContain(rule);
  const listbox = styles.slice(styles.indexOf(rule), styles.indexOf('}', styles.indexOf(rule)));
  expect(listbox).toContain('min-height: 0');
  expect(listbox).toContain('overflow-y: auto');
  expect(styles).toContain('max-height: calc(100dvh - var(--palette-top) - 0.5rem)');
  expect(styles).toMatch(/@media \(max-height: 30rem\)\s*\{[^@]*\.footer \{ display: none; \}/);
});

it('SP9 dismissal returns focus to the invoker, or to the main region when the invoker is gone', async () => {
  const main = document.createElement('div'); main.setAttribute('role', 'main');
  const invoker = document.createElement('button'); invoker.textContent = 'Invoker';
  main.append(invoker); document.body.append(main);
  for (const dismiss of ['Escape', 'backdrop'] as const) {
    invoker.focus();
    const palette = await open();
    expect(document.activeElement).toBe(palette);
    if (dismiss === 'Escape') key(palette, { key: 'Escape' }); else shadow(palette).querySelector<HTMLElement>('.backdrop')!.click();
    expect(document.activeElement, dismiss).toBe(invoker);
    palette.remove();
  }
  invoker.focus();
  const palette = await open();
  invoker.remove();
  key(palette, { key: 'Escape' });
  expect(document.activeElement).toBe(main);
});

it('SP9 the palette\'s Escape is its own: nothing beneath it hears the key', async () => {
  const beneath = vi.fn();
  document.addEventListener('keydown', beneath);
  try {
    const palette = await open();
    key(palette, { key: 'Escape' });
    expect(palette.open).toBe(false);
    expect(beneath).not.toHaveBeenCalled();
  } finally { document.removeEventListener('keydown', beneath); }
});

it.each(['Alt release', 'leaving the row', 'removal of the row', 'dismissal', 'disconnect'] as const)(
  'SP10c an Alt peek ends on %s and leaves no listener behind', async ending => {
    const go = action('core.action.shell.go-to-library', 'Go to Library');
    registerAction(go);
    const requests: unknown[] = [];
    let dismissals = 0;
    const onRequest = (e: Event): void => { requests.push((e as CustomEvent).detail); };
    const onDismiss = (): void => { dismissals++; };
    document.addEventListener('jf-peek-request', onRequest);
    document.addEventListener('jf-peek-dismiss', onDismiss);
    try {
      const palette = await open('>Library');
      const row = [...shadow(palette).querySelectorAll<HTMLElement>('[role="option"]')].find(option => option.textContent?.includes('Go to Library'))!;
      row.dispatchEvent(new MouseEvent('mouseenter', { altKey: true }));
      expect(requests).toEqual([{ surfaceId: 'core.library-surface' }]);
      if (ending === 'Alt release') document.dispatchEvent(new KeyboardEvent('keyup', { key: 'Alt' }));
      else if (ending === 'leaving the row') row.dispatchEvent(new MouseEvent('mouseleave'));
      else if (ending === 'removal of the row') { unregisterAction(go.id); await flush(); }
      else if (ending === 'dismissal') key(palette, { key: 'Escape' });
      else palette.remove();
      expect(dismissals).toBe(1);
      // The Alt listener went with the peek: a later release says nothing more.
      document.dispatchEvent(new KeyboardEvent('keyup', { key: 'Alt' }));
      palette.remove();
      expect(dismissals).toBe(1);
    } finally {
      document.removeEventListener('jf-peek-request', onRequest);
      document.removeEventListener('jf-peek-dismiss', onDismiss);
    }
  },
);
