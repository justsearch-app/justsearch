// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import * as projection from './CommandPaletteProjection.js';
import { CommandPalette } from './CommandPalette.js';
import { registerAction, unregisterAction, __resetActionsForTest } from '../substrates/actions/index.js';
import { registerCommand, unregisterCommand, invokeCommand, invokeCommandWithResult, __resetForTest as resetCommands } from './CommandRegistry.js';
import { registerKeybinding, __resetForTest as resetBindings, rebindUserKeybindings, attachKeybindingDispatcher } from './KeybindingRegistry.js';
import { __resetShellContextForTest, updateShellContext } from '../state/shellContextState.js';
import { CORE_PROVENANCE, makePluginProvenance } from '../primitives/provenance.js';
import { __resetInMemoryStateForTest, getDocument, recordRecentCommandId, getRecentCommandIds, createProfile, setActiveProfileId } from '../state/UserStateDocument.js';
import { __resetConversationListForTest, loadConversations, getConversationListState, getRecentSessions, recordRecentSession } from '../state/conversationListStore.js';
import { __seedForTest as seedPaths, __resetForTest as resetPaths } from '../hooks/resolvePathLazy.js';
import { __resetAiStateForTest, __feedForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { __seedForTest as seedWords, __resetForTest as resetWords } from '../../i18n/resourceCatalog.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';
import { registerTemplate, __resetTemplateCatalogForTest as resetTemplates } from './TemplateCatalog.js';
import { setShowDeveloperTools } from '../state/developerToolsState.js';
import { defineMacro, restoreMacrosFromStorage, __resetMacrosForTest } from '../substrates/macros/index.js';
import { createRegistrationApi } from '../plugin-api/capabilities/registration.js';
import { setViewerAudience } from '../state/viewerAudienceState.js';
import { __seedForTest as seedResources, __resetForTest as resetResources } from '../../api/registry/ResourceCatalogClient.js';
import type { ResourceCatalog } from '../../api/types/registry.js';

let stop: (() => void) | undefined;
let roots: { pathHash: string; fileCount: number }[];
let sessions: { sessionId: string; title: string; firstUserMessage: string; lastActiveAt?: number; storeBacked?: boolean }[];
const action = (id: string, title: string, extra = {}) => ({ id, title, provenance: CORE_PROVENANCE,
  handler: vi.fn(() => ({ kind: 'noop' as const })), ...extra });
const flush = async (): Promise<void> => { for (let i = 0; i < 40; i++) await Promise.resolve(); };
const entries = (query: string) => projection.searchPaletteEntries(query).map(row => row.entry);
const start = async (): Promise<void> => { stop = projection.watchPaletteEntries?.(() => {}); await flush(); };

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetInMemoryStateForTest(); __resetShellContextForTest(); __resetActionsForTest(); resetCommands(); resetBindings();
  __resetConversationListForTest(); __resetAiStateForTest(); resetPaths(); resetWords(); resetTemplates(); __resetMacrosForTest();
  roots = []; sessions = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
    const url = String(input);
    if (url.includes('/api/indexing-roots/substrate')) return Response.json({ items: roots });
    if (url.includes('/api/chat/conversations?')) return Response.json({ sessions });
    if (url.includes('/api/settings/v2')) return Response.json({ ui: {}, settingsMode: 'read_write' });
    return Response.json({});
  });
});
afterEach(() => { stop?.(); stop = undefined; document.body.replaceChildren(); vi.restoreAllMocks(); });

it('SP3 rejects interior and scattered matches while keeping Unicode word starts and any word order', () => {
  registerAction(action('library', 'Library'));
  registerAction(action('install', 'Go to AI Brain — Install AI'));
  registerAction(action('unicode', 'Überblick öffnen'));
  expect(entries('lib').map(entry => entry.id)).toContain('library');
  expect(entries('ibrary').map(entry => entry.id)).not.toContain('library');
  expect(entries('lbr').map(entry => entry.id)).not.toContain('library');
  expect(entries('install ai').map(entry => entry.id)).toContain('install');
  expect(entries('öff über').map(entry => entry.id)).toContain('unicode');
});
it('SP3 recency cannot promote a prefix above an exact label', () => {
  registerAction(action('exact', 'Library')); registerAction(action('prefix', 'Library tools'));
  recordRecentCommandId('prefix');
  expect(entries('Library')[0]?.id).toBe('exact');
});
it('SP3 exact whole-word tier beats a label prefix even when the prefix was recently invoked', () => {
  registerAction(action('whole', 'Open lib')); registerAction(action('prefix', 'Library'));
  recordRecentCommandId('prefix');
  expect(entries('lib')[0]?.id).toBe('whole');
});
it('SP2 keeps plugin wording and removes core implementation suffixes', () => {
  registerAction(action('core.action.demo', 'Go to Brain action'));
  registerAction(action('plugin.action.demo', 'Plugin action', { provenance: makePluginProvenance('demo', '1') }));
  expect(entries('')[0]?.label).toBe('Go to AI Brain');
  expect(entries('').map(entry => entry.label)).toContain('Plugin action');
});
it('SP2 hints follow winning bindings with visible modifier names and leave saved overrides intact', () => {
  registerAction(action('one', 'One')); registerAction(action('two', 'Two'));
  registerKeybinding({ key: 'ctrl+k', commandId: 'one', source: 'default', provenance: CORE_PROVENANCE });
  registerKeybinding({ key: 'ctrl+k', commandId: 'two', source: 'plugin', provenance: CORE_PROVENANCE });
  registerKeybinding({ key: 'ctrl+shift+p', commandId: 'one', source: 'user', provenance: CORE_PROVENANCE });
  const saved = JSON.stringify(getDocument().keybindingOverrides);
  expect(entries('').find(entry => entry.id === 'one')?.shortcut).toBe('Ctrl Shift P');
  expect(entries('').find(entry => entry.id === 'two')?.shortcut).toBe('Ctrl K');
  expect(JSON.stringify(getDocument().keybindingOverrides)).toBe(saved);
});
it('SP6 prefixes exclude other providers and never offer a prefixed search fallback', () => {
  registerAction(action('run', 'Run'));
  registerAction(action('core.action.shell.go-to-library', 'Library'));
  for (const query of ['> a', '# a', '@ a']) expect(entries(query).some(entry => entry.origin === 'intent')).toBe(false);
  expect(entries('>').every(entry => entry.group === 'Actions')).toBe(true);
  expect(entries('#').map(entry => entry.id)).toContain('shell.go-to-library');
  expect(entries('anything').at(-1)?.id).toBe('intent.search');
});
it('SP4 settings rows use capabilities, tools, audience and settings mode from the real viewer', () => {
  seedWords({ 'settings.section.desktop': 'Desktop', 'settings.category.general': 'General', 'settings.section.keyboard': 'Keyboard' });
  updateShellContext({ platformCapabilities: 'native-notifications' });
  const hit = entries('global shortcut').find(entry => entry.id === 'settings-row:general:desktop');
  expect(hit?.target).toEqual({ kind: 'settings', destination: { kind: 'settings', section: 'general', anchor: 'desktop' } });
  updateShellContext({ platformCapabilities: '' });
  expect(entries('global shortcut').some(entry => entry.id === 'settings-row:general:desktop')).toBe(false);
  setViewerAudience('USER'); setShowDeveloperTools(false);
  expect(entries('api access').some(entry => entry.id.includes('api-access'))).toBe(false);
  const visible = projection.searchPaletteEntries('', { capabilities: new Set(['native-notifications']), settingsRecovery: false, settingsReadOnly: true });
  expect(visible.some(row => row.entry.id === 'settings-row:advanced:reset-settings')).toBe(false);
});
it('SP4 Settings dispatch uses the returned arrival destination without recording a command invocation', async () => {
  seedWords({ 'settings.section.keyboard': 'Keyboard' });
  const hit = entries('keyboard').find(entry => entry.id === 'settings-row:general:keyboard');
  expect(hit).toBeDefined();
  const navigate = vi.fn(); document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate);
  try {
    if (hit) await projection.invokePaletteEntry?.(hit);
    expect(navigate).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ detail: { surfaceId: 'core.settings-surface', state: { section: 'general', anchor: 'keyboard' } } }));
    expect(getRecentCommandIds()).toEqual([]);
  } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate); }
});
it('SP10a arbitrary Actions invoke their actual target once and legacy commands retain their handlers', async () => {
  const target = action('demo.action.real', 'Actual target'); registerAction(target);
  const legacy = vi.fn(); registerCommand({ id: 'legacy', label: 'Legacy', source: 'plugin', provenance: CORE_PROVENANCE, handler: legacy });
  const hit = entries('Actual')[0]!;
  await projection.invokePaletteEntry?.(hit);
  expect(target.handler).toHaveBeenCalledTimes(1);
  const command = entries('Legacy')[0]!; await projection.invokePaletteEntry?.(command);
  expect(legacy).toHaveBeenCalledTimes(1);
  expect(getRecentCommandIds()).toEqual(['legacy', 'demo.action.real']);
});
it('SP10a a canonical collision shows once and its ineligibility cannot be bypassed by the duplicate', () => {
  registerAction(action('core.action.shell.same', 'Same'));
  registerCommand({ id: 'shell.same', label: 'Same legacy', source: 'plugin', provenance: CORE_PROVENANCE, handler: vi.fn() });
  expect(entries('same').filter(entry => entry.id === 'shell.same')).toHaveLength(1);
  unregisterAction('core.action.shell.same'); registerAction(action('core.action.shell.same', 'Same', { enabled: () => false }));
  expect(entries('same').some(entry => entry.id === 'shell.same')).toBe(false);
});
it('SP4-SP9 a removed or replaced registration cannot run from its captured result', async () => {
  const original = action('demo', 'Demo'); registerAction(original);
  const selected = entries('Demo')[0]!;
  unregisterAction('demo'); const replacement = action('demo', 'Replacement'); registerAction(replacement);
  expect(await projection.invokePaletteEntry?.(selected)).toBe(false);
  expect(original.handler).not.toHaveBeenCalled(); expect(replacement.handler).not.toHaveBeenCalled();
});
it('SP4-SP9 legacy replacement also invalidates the captured handler', async () => {
  const old = vi.fn(), replacement = vi.fn();
  registerCommand({ id: 'plugin.command', label: 'Plugin', source: 'plugin', provenance: CORE_PROVENANCE, handler: old });
  const selected = entries('Plugin')[0]!; unregisterCommand('plugin.command');
  registerCommand({ id: 'plugin.command', label: 'Plugin', source: 'plugin', provenance: CORE_PROVENANCE, handler: replacement });
  expect(await projection.invokePaletteEntry?.(selected)).toBe(false);
  expect(old).not.toHaveBeenCalled(); expect(replacement).not.toHaveBeenCalled();
});
it('SP4-SP9 an open palette refreshes registrations and keeps a removed selection empty on Enter', async () => {
  const one = action('one', 'One'), two = action('two', 'Two'); registerAction(one); registerAction(two);
  const palette = new CommandPalette(); document.body.append(palette); palette.show(); await flush();
  palette.selectedIndex = palette.results.findIndex(row => row.entry.id === 'one');
  unregisterAction('one'); await flush(); await palette.updateComplete;
  expect(palette.results.some(row => row.entry.id === 'one')).toBe(false);
  const field = palette.shadowRoot!.querySelector('input')!;
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await flush();
  expect(two.handler).not.toHaveBeenCalled();
});
it('SP4a recent conversations searches only the loaded twenty and labels its source honestly', async () => {
  sessions = Array.from({ length: 20 }, (_, index) => ({ sessionId: `session-${index}`, title: `Recent ${index}`, firstUserMessage: 'Opening message', lastActiveAt: index }));
  await loadConversations();
  const hits = entries('Recent').filter(entry => entry.origin === 'conversation');
  expect(hits).toHaveLength(20);
  expect(hits.every(entry => entry.group === 'Recent conversations')).toBe(true);
  expect(hits[0]?.description).toContain('Opening');
});
it('SP5 conversation action data contains Open only for run-only conversations', async () => {
  sessions = [{ sessionId: 'run-conversation', title: 'Run subject', firstUserMessage: '', storeBacked: false }]; await loadConversations();
  expect(entries('Run subject').find(entry => entry.origin === 'conversation')?.actions.map(action => action.label)).toEqual(['Open']);
});
it('SP4c an observed lock removes conversation titles, descriptions and action data immediately', async () => {
  sessions = [{ sessionId: 'conversation', title: 'Private subject', firstUserMessage: 'Private opening' }]; await loadConversations();
  expect(entries('Private').some(entry => entry.origin === 'conversation')).toBe(true);
  __feedForTest({ status: { conversationProtection: { state: 'locked' } } as unknown as StatusSnapshot }); await flush();
  expect(JSON.stringify(projection.searchPaletteEntries(''))).not.toContain('Private');
});
it('SP4c a list read started before a lock cannot restore its content after that observation', async () => {
  let release!: (value: Response) => void;
  vi.mocked(globalThis.fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  const read = loadConversations(); await flush();
  __feedForTest({ status: { conversationProtection: { state: 'locked' } } as unknown as StatusSnapshot }); await flush();
  release(Response.json({ sessions: [{ sessionId: 'late', title: 'Old private subject', firstUserMessage: 'Opening' }] })); await read;
  expect(JSON.stringify(getConversationListState().conversations)).not.toContain('Old private');
  // The old palette has no conversation provider either: observing a lock must advance the read epoch.
  expect(await loadConversations()).toBe(true);
});
it('SP5 folder results expose only Library opening and its existing removal confirmation handoff', async () => {
  roots = [{ pathHash: 'folder-one', fileCount: 2 }]; seedPaths('core.indexed-roots', 'folder-one', '/notes/Research'); await start();
  const hit = entries('Research').find(entry => entry.origin === 'folder');
  expect(hit?.actions.map(action => action.label)).toEqual(['Open in Library', 'Remove from Library']);
  expect(hit?.actions[1]?.target).toEqual({ kind: 'folder', pathHash: 'folder-one', action: 'remove' });
});
it('SP5-SP9 a root removed during activation never navigates to its neighbour', async () => {
  roots = [{ pathHash: 'folder-one', fileCount: 2 }]; seedPaths('core.indexed-roots', 'folder-one', '/notes/Research'); await start();
  const selected = entries('Research').find(entry => entry.origin === 'folder'); expect(selected).toBeDefined();
  roots = [{ pathHash: 'other', fileCount: 1 }];
  expect(selected && await projection.invokePaletteEntry?.(selected)).toBe(false);
});
it('SP6-SP8 bounded rendering keeps every source match reachable through Show more', async () => {
  for (let index = 0; index < 57; index++) registerAction(action(`row-${index}`, `Reachable ${String(index).padStart(2, '0')}`));
  const palette = new CommandPalette(); document.body.append(palette); palette.show(); await flush();
  const field = palette.shadowRoot!.querySelector('input')!; field.value = '>Reachable'; field.dispatchEvent(new Event('input')); await palette.updateComplete;
  expect(palette.shadowRoot!.querySelectorAll('[role="option"]').length).toBeLessThan(57);
  for (let count = 0; count < 3; count++) {
    [...palette.shadowRoot!.querySelectorAll('button')].find(button => button.textContent?.includes('Show more'))?.click(); await palette.updateComplete;
  }
  expect(palette.results.some(row => row.entry.id === 'row-56')).toBe(true);
  palette.selectedIndex = palette.results.findIndex(row => row.entry.id === 'row-56'); await palette.updateComplete;
  expect(palette.shadowRoot!.querySelector('[data-selected]')?.textContent).toContain('Reachable 56');
});
it('SP10d profile switch refreshes shortcut hints without rewriting multiple saved bindings', async () => {
  registerAction(action('one', 'One'));
  createProfile('other', 'Other');
  registerKeybinding({ key: 'ctrl+p', commandId: 'one', source: 'user', provenance: CORE_PROVENANCE });
  registerKeybinding({ key: 'ctrl+o', commandId: 'one', source: 'user', provenance: CORE_PROVENANCE });
  setActiveProfileId('other'); rebindUserKeybindings();
  expect(entries('').find(entry => entry.id === 'one')?.shortcut).toBeUndefined();
  setActiveProfileId('default'); rebindUserKeybindings();
  expect(entries('').find(entry => entry.id === 'one')?.shortcut).toBe('Ctrl P');
  expect(getDocument().keybindingOverrides).toHaveLength(2);
});
it('SP10 saved state keeps invocation recency at ten and destination rows outside both recents stores', async () => {
  recordRecentSession('existing-conversation');
  const snapshot = JSON.stringify(getRecentSessions());
  for (let i = 0; i < 12; i++) { const target = action(`invoked-${i}`, `Invoked ${i}`); registerAction(target); await invokeCommandWithResult(target.id); }
  expect(getRecentCommandIds()).toHaveLength(10);
  expect(getRecentCommandIds()[0]).toBe('invoked-11');
  expect(JSON.stringify(getRecentSessions())).toBe(snapshot);
  expect(localStorage.getItem('jf-chat-recent-sessions')).not.toContain('Invoked');
});
it('SP10b multi-slot templates complete once and close after the final slot', async () => {
  const execute = vi.fn(); registerTemplate({ id: 'multi', label: 'Multi', template: '{argument name="first" default="one"} {argument name="second" default="two"}', provenance: CORE_PROVENANCE, source: 'core', onInvoke: execute });
  const palette = new CommandPalette(); document.body.append(palette);
  const running = invokeCommandWithResult('template.multi'); await flush(); await palette.updateComplete;
  expect(palette.wizardInput).toBe('one');
  palette.shadowRoot!.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); await flush(); await palette.updateComplete;
  expect(palette.wizardInput).toBe('two');
  palette.shadowRoot!.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); await running; await flush();
  expect(execute).toHaveBeenCalledExactlyOnceWith('one two'); expect(palette.open).toBe(false);
});
it.each(['Escape', 'toggle', 'backdrop', 'disconnect'])('SP10b %s cancels template slots and reopening has no stale slot', async method => {
  const execute = vi.fn(); registerTemplate({ id: 'cancel', label: 'Cancel', template: '{argument name="first" default="one"} {argument name="second" default="two"}', provenance: CORE_PROVENANCE, source: 'core', onInvoke: execute });
  const palette = new CommandPalette(); document.body.append(palette);
  const running = invokeCommandWithResult('template.cancel'); await flush(); await palette.updateComplete;
  if (method === 'Escape') palette.shadowRoot!.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  else if (method === 'backdrop') palette.shadowRoot!.querySelector<HTMLElement>('.backdrop')!.click();
  else if (method === 'disconnect') palette.remove();
  else palette.hide();
  await flush();
  expect(execute).not.toHaveBeenCalled();
  expect(palette.wizardActive).toBe(false);
  await running;
  if (!palette.isConnected) document.body.append(palette);
  palette.show(); await palette.updateComplete;
  expect(palette.wizardCompletedLabels).toEqual([]);
});

it('SP10a a restored macro invokes its actual registered Action once', async () => {
  defineMacro({ id: 'restored', label: 'Restored macro', effects: [{ kind: 'noop' }] });
  const saved = localStorage.getItem('justsearch.macros.v1')!;
  __resetMacrosForTest(); localStorage.setItem('justsearch.macros.v1', saved); restoreMacrosFromStorage();
  const selected = entries('Restored')[0]!;
  expect(await projection.invokePaletteEntry?.(selected)).toBe(true);
  expect(getRecentCommandIds()).toEqual(['core.action.macro.restored']);
});
it('SP4d trusted plugin commands use Actions while the Host API denies untrusted registration', () => {
  const register = (id: string, label: string, handler: () => void): void => {
    registerCommand({ id, label, handler, source: 'plugin', provenance: makePluginProvenance('demo', '1') });
  };
  createRegistrationApi('TRUSTED_PLUGIN', 'trusted', { registerSurfacePort: vi.fn(), registerCommand: register }).registerCommand('trusted', 'Given action', vi.fn());
  createRegistrationApi('UNTRUSTED_PLUGIN', 'untrusted', { registerSurfacePort: vi.fn(), registerCommand: register }).registerCommand('untrusted', 'Denied command', vi.fn());
  expect(entries('Given')[0]?.group).toBe('Actions');
  expect(entries('').some(entry => entry.id === 'untrusted')).toBe(false);
});
it.each([
  ['a weaker action match', 'Keyboard tools', ['Settings', 'Actions']],
  ['an exact tie created by suffix cleanup', 'Keyboard action', ['Actions', 'Settings']],
] as const)('SP3-SP6 groups stay contiguous and ordered by best tier then provider order for %s; the fallback is outside every group', async (_case, label, order) => {
  registerAction(action('weak', label));
  registerCommand({ id: 'legacy-key', label: 'Keyboard command', category: 'Other', source: 'plugin', provenance: CORE_PROVENANCE, handler: vi.fn() });
  seedWords({ 'settings.section.keyboard': 'Keyboard' });
  const palette = new CommandPalette(); document.body.append(palette); palette.show(); await flush();
  const field = palette.shadowRoot!.querySelector('input')!; field.value = 'keyboard'; field.dispatchEvent(new Event('input')); await palette.updateComplete;
  const visual = [...palette.shadowRoot!.querySelectorAll('[role="option"]')].map(row => row.id);
  expect(visual).toEqual(palette.results.map((_, index) => `palette-opt-${index}`));
  const groups = palette.results.slice(0, -1).map(row => row.entry.group);
  expect(groups.filter((group, index) => group !== groups[index - 1])).toEqual(order);
  expect(palette.results.at(-1)?.entry.id).toBe('intent.search');
});
it('SP4c unlock restores content only after a fresh accepted list read', async () => {
  sessions = [{ sessionId: 'conversation', title: 'Original subject', firstUserMessage: 'Opening' }]; await loadConversations();
  __feedForTest({ status: { conversationProtection: { state: 'locked' } } as unknown as StatusSnapshot }); await flush();
  expect(entries('Original').some(entry => entry.origin === 'conversation')).toBe(false);
  let release: ((value: Response) => void) | undefined;
  vi.mocked(globalThis.fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  __feedForTest({ status: { conversationProtection: { state: 'unlocked' } } as unknown as StatusSnapshot }); await flush();
  expect(entries('Original').some(entry => entry.origin === 'conversation')).toBe(false);
  expect(release).toBeDefined();
  release?.(Response.json({ sessions: [{ sessionId: 'conversation', title: 'Fresh subject', firstUserMessage: 'Opening' }] })); await flush();
  expect(entries('Fresh').find(entry => entry.origin === 'conversation')?.label).toBe('Fresh subject');
});
it('SP5-SP9 failed folder refresh reports unconfirmed availability and never claims deletion', async () => {
  roots = [{ pathHash: 'folder-failed', fileCount: 1 }]; seedPaths('core.indexed-roots', 'folder-failed', '/notes/Unconfirmed'); await start();
  const selected = entries('Unconfirmed').find(entry => entry.origin === 'folder'); expect(selected).toBeDefined();
  vi.mocked(globalThis.fetch).mockResolvedValueOnce(new Response('{}', { status: 503 }));
  expect(selected && await projection.invokePaletteEntry?.(selected)).toBe(false);
  expect(entries('Unconfirmed').some(entry => entry.origin === 'folder')).toBe(true);
});

const field = (palette: CommandPalette): HTMLInputElement => palette.shadowRoot!.querySelector('input')!;
async function openPalette(query = ''): Promise<CommandPalette> {
  const palette = new CommandPalette(); document.body.append(palette); palette.show(); await flush();
  if (query) { field(palette).value = query; field(palette).dispatchEvent(new Event('input')); }
  await palette.updateComplete;
  return palette;
}
function held(): { promise: Promise<Response>; release: (body: unknown) => void } {
  let release!: (body: unknown) => void;
  const promise = new Promise<Response>(resolve => { release = body => resolve(Response.json(body)); });
  return { promise, release };
}

it('SP4a the 21st conversation, outside the loaded twenty, is described as outside the recent list and never as missing', async () => {
  // The Engine answers limit=20 with the twenty newest; the oldest of twenty-one is not in the roster.
  sessions = Array.from({ length: 20 }, (_, index) => ({ sessionId: `newer-${index}`, title: `Newer ${index}`, firstUserMessage: 'Opening', lastActiveAt: 100 + index }));
  await loadConversations();
  expect(vi.mocked(globalThis.fetch).mock.calls.some(call => String(call[0]).includes('/api/chat/conversations?limit=20'))).toBe(true);
  const palette = await openPalette('Oldest planning notes');
  expect(palette.results.some(row => row.entry.origin === 'conversation')).toBe(false);
  const text = palette.shadowRoot!.textContent ?? '';
  expect(text).toMatch(/Older conversations are outside this list/);
  expect(text).not.toMatch(/not exist|no longer|deleted|not found|no such/i);
});
it('SP6-SP8 a late folder label updates an open palette without retyping and an older roots read never overwrites a newer one', async () => {
  seedResources({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [{
    id: 'core.indexed-roots', primaryKey: 'pathHash',
    privacy: { pathPolicy: 'HASHED_REQUIRES_RESOLVER', loopbackOnly: true, resolver: 'core.resolve-path-hash' },
  }] } as unknown as ResourceCatalog);
  const label = held(), older = held();
  const base = vi.mocked(globalThis.fetch).getMockImplementation()!;
  let rootsReads = 0;
  vi.mocked(globalThis.fetch).mockImplementation(async (input, init) => {
    const url = String(input);
    if (url.includes('/api/library/path-label')) return label.promise;
    if (url.includes('/api/indexing-roots/substrate') && ++rootsReads === 1) return older.promise;
    return base(input, init);
  });
  try {
    roots = [{ pathHash: 'late', fileCount: 1 }];
    // The first opening's read stays pending; reopening starts a newer read that lands first.
    const first = await openPalette(); first.hide(); first.remove();
    const palette = await openPalette('Research');
    expect(palette.results.some(row => row.entry.id === 'folder:late')).toBe(false);
    expect(projection.searchPaletteEntries('').some(row => row.entry.id === 'folder:late')).toBe(true);
    older.release({ items: [{ pathHash: 'stale', fileCount: 1 }] }); await flush();
    label.release({ found: true, path: '/notes/Research' }); await flush(); await palette.updateComplete;
    expect(palette.results.map(row => row.entry.id)).toContain('folder:late');
    expect(projection.searchPaletteEntries('').some(row => row.entry.id === 'folder:stale')).toBe(false);
    expect(field(palette).value).toBe('Research');
  } finally { resetResources(); }
});
it('SP4-SP9 eligibility changes update an open palette in results and actions and are rechecked on activation', async () => {
  const gated = action('gated', 'Gated tool', { when: 'inspectorOpen' }); registerAction(gated);
  updateShellContext({ inspectorOpen: true });
  const palette = await openPalette('Gated');
  const shown = palette.results.find(row => row.entry.id === 'gated')?.entry;
  expect(shown?.actions?.map(row => row.label)).toEqual(['Run']);
  updateShellContext({ inspectorOpen: false }); await flush(); await palette.updateComplete;
  expect(palette.results.some(row => row.entry.id === 'gated')).toBe(false);
  expect(shown && await projection.invokePaletteEntry?.(shown)).toBe(false);
  expect(shown && await projection.invokePaletteEntry?.(shown, shown.actions[0]!.target)).toBe(false);
  expect(gated.handler).not.toHaveBeenCalled();
  updateShellContext({ inspectorOpen: true }); await flush(); await palette.updateComplete;
  expect(palette.results.some(row => row.entry.id === 'gated')).toBe(true);
  expect(field(palette).value).toBe('Gated');
});
it('SP10d a profile switch with the palette open drops the previous scope and its pending reads', async () => {
  createProfile('other', 'Other');
  sessions = [{ sessionId: 'first-profile', title: 'First profile subject', firstUserMessage: 'Opening' }];
  await loadConversations();
  const firstRoots = held();
  const base = vi.mocked(globalThis.fetch).getMockImplementation()!;
  let rootsReads = 0;
  vi.mocked(globalThis.fetch).mockImplementation(async (input, init) => {
    if (String(input).includes('/api/indexing-roots/substrate') && ++rootsReads === 1) return firstRoots.promise;
    return base(input, init);
  });
  const palette = await openPalette();
  const captured = palette.results.find(row => row.entry.id === 'conversation:first-profile')?.entry;
  expect(captured).toBeDefined();
  roots = [{ pathHash: 'second-root', fileCount: 1 }]; seedPaths('core.indexed-roots', 'second-root', '/other/Second');
  sessions = [{ sessionId: 'second-profile', title: 'Second profile subject', firstUserMessage: 'Opening' }];
  setActiveProfileId('other'); await flush(); await palette.updateComplete;
  expect(JSON.stringify(projection.searchPaletteEntries(''))).not.toContain('First profile');
  seedPaths('core.indexed-roots', 'first-root', '/first/Earlier');
  firstRoots.release({ items: [{ pathHash: 'first-root', fileCount: 1 }] }); await flush(); await palette.updateComplete;
  const ids = palette.results.map(row => row.entry.id);
  expect(ids).toContain('folder:second-root'); expect(ids).toContain('conversation:second-profile');
  expect(ids).not.toContain('folder:first-root'); expect(ids).not.toContain('conversation:first-profile');
  const navigate = vi.fn(); document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate);
  try {
    expect(captured && await projection.invokePaletteEntry?.(captured)).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate); }
});
it('SP10 saved state records failed, cancelled and shortcut invocations once each', async () => {
  registerAction(action('failing', 'Failing work', { handler: vi.fn(() => { throw new Error('refused'); }) }));
  registerTemplate({ id: 'cancelled', label: 'Cancelled work', template: '{argument name="first" default="one"}', provenance: CORE_PROVENANCE, source: 'core', onInvoke: vi.fn() });
  const shortcut = action('shortcut', 'Shortcut work'); registerAction(shortcut);
  const failing = entries('Failing')[0]!;
  expect(await projection.invokePaletteEntry?.(failing)).toBe(false);
  expect(getRecentCommandIds()).toEqual(['failing']);
  const palette = await openPalette('Cancelled');
  field(palette).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); await flush(); await palette.updateComplete;
  expect(palette.wizardActive).toBe(true);
  field(palette).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await flush();
  expect(getRecentCommandIds()).toEqual(['template.cancelled', 'failing']);
  const detach = attachKeybindingDispatcher(id => invokeCommand(id));
  try {
    registerKeybinding({ key: 'ctrl+j', commandId: 'shortcut', source: 'default', provenance: CORE_PROVENANCE });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', ctrlKey: true, bubbles: true, cancelable: true })); await flush();
    expect(shortcut.handler).toHaveBeenCalledTimes(1);
  } finally { detach(); }
  expect(getRecentCommandIds()).toEqual(['shortcut', 'template.cancelled', 'failing']);
});
it('SP10 saved state survives a reload: recency, overrides with their source and multiple bindings, and no title cache', async () => {
  registerAction(action('one', 'One')); registerAction(action('two', 'Two'));
  registerKeybinding({ key: 'ctrl+p', commandId: 'one', source: 'user', provenance: CORE_PROVENANCE });
  registerKeybinding({ key: 'ctrl+o', commandId: 'one', source: 'user', provenance: CORE_PROVENANCE });
  sessions = [{ sessionId: 'titled', title: 'Saved subject', firstUserMessage: 'Private opening' }]; await loadConversations();
  await projection.invokePaletteEntry?.(entries('Two')[0]!);
  const palette = await openPalette(); palette.hide();
  const saved = (): unknown => (getDocument().keybindingOverrides ?? []).map(({ key, commandId, source }) => ({ key, commandId, source }));
  const overrides = JSON.stringify(saved());
  expect(saved()).toEqual([{ key: 'ctrl+p', commandId: 'one', source: 'user' }, { key: 'ctrl+o', commandId: 'one', source: 'user' }]);
  __resetInMemoryStateForTest(); resetBindings(); rebindUserKeybindings();
  const raw = localStorage.getItem('justsearch.userState.v2');
  expect(JSON.stringify(saved())).toBe(overrides);
  expect(getRecentCommandIds()).toEqual(['two']);
  expect(['Ctrl P', 'Ctrl O']).toContain(entries('').find(entry => entry.id === 'one')?.shortcut);
  expect(localStorage.getItem('justsearch.userState.v2')).toBe(raw); expect(raw).toContain('ctrl+o');
  const one = action('one', 'One'); unregisterAction('one'); registerAction(one);
  const detach = attachKeybindingDispatcher(id => invokeCommand(id));
  try {
    for (const key of ['p', 'o']) window.dispatchEvent(new KeyboardEvent('keydown', { key, ctrlKey: true, bubbles: true, cancelable: true }));
    await flush(); expect(one.handler).toHaveBeenCalledTimes(2);
  } finally { detach(); }
  const stored = Object.keys(localStorage).map(key => `${key}=${localStorage.getItem(key)}`).join('\n');
  expect(stored).not.toMatch(/Saved subject|Private opening/);
});
it('SP3 equal tiers without recency order by name then identity', () => {
  registerAction(action('b-id', 'Same name')); registerAction(action('a-id', 'Same name')); registerAction(action('c-id', 'Same named'));
  expect(entries('same').filter(entry => entry.origin === 'action').map(entry => entry.id)).toEqual(['a-id', 'b-id', 'c-id']);
});
