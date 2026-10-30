// @vitest-environment happy-dom

/** TD10a: the real view/store/dialog path, with only HTTP scheduling controlled here.
 * Browser focus/caret coverage also runs against the private Engine; happy-dom is not its oracle.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __resetConversationListForTest, loadConversations, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetDraftProvidersForTest, __draftStorageKey } from '../../controllers/draftPersistence.js';
import { readLastViewedConversation, setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { SV3_DRAFT_KEY } from './fixtures.js';
import { resetSearchState } from '../../state/searchState.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

const status = vi.hoisted(() => ({ listeners: new Set<(snapshot: StatusSnapshot | null) => void>() }));
vi.mock('../../utils/statusPoll.js', async importOriginal => ({
  ...await importOriginal<typeof import('../../utils/statusPoll.js')>(),
  subscribeStatus: (listener: (snapshot: StatusSnapshot | null) => void) => {
    status.listeners.add(listener); return () => status.listeners.delete(listener);
  },
}));

type Updatable = HTMLElement & { updateComplete: Promise<unknown> };
interface Stored { sessionId: string; parentSessionId?: string; firstUserMessage: string; busy: boolean }
const records = new Map<string, Stored>();
const deletes: string[] = [];
const dispatches: unknown[] = [];
let release: () => void;
let deleteGate: Promise<void>;
let lost = false;
let refused = false;
let existenceUnavailable = false;
let historyGate: Promise<void> | null = null;
let dispatchGate: Promise<void> | null = null;

function holdDelete(): void { deleteGate = new Promise(resolve => { release = resolve; }); }
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  records.clear(); deletes.length = 0; dispatches.length = 0;
  lost = false; refused = false; existenceUnavailable = false; historyGate = null; dispatchGate = null; holdDelete();
  vi.stubGlobal('fetch', vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = new URL(String(input), 'http://engine.test');
    const id = decodeURIComponent(url.pathname.split('/conversations/')[1]?.split('/')[0] ?? '');
    if (init?.method === 'DELETE') {
      deletes.push(id); await deleteGate;
      if (refused) return json({ error: 'Running work', errorCode: 'CONVERSATION_BUSY' }, 409);
      records.delete(id);
      if (lost) throw Error('Response lost after deletion');
      return json({ ok: true });
    }
    if (url.pathname.endsWith('/dispatch')) { dispatches.push(init?.body); await dispatchGate; return json({ error: 'Unavailable' }, 503); }
    if (url.searchParams.has('childrenOf')) {
      const parent = url.searchParams.get('childrenOf')!;
      return json({ childrenOf: parent, locked: false, sessions: [...records.values()].filter(r => r.sessionId === parent || r.parentSessionId === parent) });
    }
    if (url.searchParams.has('existsOnly')) return existenceUnavailable ? json({}, 503) : json({ exists: records.has(id) });
    if (url.pathname.endsWith('/history')) {
      const present = records.has(id); await historyGate;
      return present ? json({ sessionId: id, messages: [] }) : json({}, 404);
    }
    if (url.pathname.startsWith('/api/thread/')) return json({ conversationId: url.pathname.split('/').at(-1), events: [] });
    if (url.pathname.endsWith('/conversations')) return json({ sessions: [...records.values()] });
    return json({ runs: [] });
  }));
});
afterEach(() => {
  release();
  document.body.replaceChildren(); resetSearchState();
  __resetConversationListForTest(); __resetAiStateForTest(); status.listeners.clear();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

function seed(depth: number): string[] {
  const ids = Array.from({ length: depth }, (_, i) => `removal-${i}`);
  for (const [i, id] of ids.entries()) records.set(id, { sessionId: id, ...(i ? { parentSessionId: ids[i - 1] } : {}), firstUserMessage: id, busy: false });
  records.set('elsewhere', { sessionId: 'elsewhere', firstUserMessage: 'Elsewhere', busy: false });
  return ids;
}
async function settle(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
  await composer(view).updateComplete;
}
function composer(view: SearchV3View): Sv3Composer { return view.shadowRoot!.querySelector<Sv3Composer>('jf-sv3-composer')!; }
function field(view: SearchV3View): HTMLTextAreaElement { return composer(view).shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!; }
async function type(view: SearchV3View, text: string): Promise<void> {
  const input = field(view); input.value = text; input.dispatchEvent(new Event('input')); await settle(view);
}
async function mount(active: string | null): Promise<SearchV3View> {
  if (active) setLastViewedConversation(active);
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view); return view;
}
async function begin(view: SearchV3View, id: string, accept = true): Promise<void> {
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id }, bubbles: true, composed: true }));
  await settle(view);
  const dialog = document.querySelector('jf-confirm-dialog') as Updatable;
  expect(dialog).not.toBeNull(); await dialog.updateComplete;
  const button = dialog.shadowRoot!.querySelector('jf-button.' + (accept ? 'confirm' : 'cancel')) as Updatable;
  await button.updateComplete;
  const control = button.shadowRoot!.querySelector('jf-control') as Updatable;
  await control.updateComplete; control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
  await settle(view);
}
const activeId = (view: SearchV3View): string | null => (view as unknown as { sessions: { activeId: string | null } }).sessions.activeId;

it.each(['unchanged', 'identical assignment', 'A-B-A', 'composition start', 'other window', 'late unchanged', 'late identical'])(
  'CP1d confirmed removal clears only its captured owning draft: %s', async change => {
    seed(1); const view = await mount('removal-0'); await type(view, 'owned A');
    if (change.startsWith('late')) { lost = true; existenceUnavailable = true; }
    await begin(view, 'removal-0');
    if (change === 'identical assignment') composer(view).draft = 'owned A';
    if (change === 'A-B-A') { composer(view).draft = 'B'; composer(view).draft = 'owned A'; }
    if (change === 'composition start') field(view).dispatchEvent(new Event('compositionstart'));
    if (change === 'other window') window.dispatchEvent(new StorageEvent('storage', { key: 'justsearch.draft.search-v3.composer', newValue: 'other window B' }));
    release(); await settle(view);
    if (change.startsWith('late')) {
      expect(field(view).value).toBe('owned A'); expect(activeId(view)).toBe('removal-0');
      if (change === 'late identical') composer(view).draft = 'owned A';
      existenceUnavailable = false;
      for (const listener of status.listeners) listener({} as StatusSnapshot);
      await settle(view);
    }
    expect(field(view).value).toBe(change === 'unchanged' || change === 'late unchanged' ? '' : 'owned A');
    expect(activeId(view)).toBeNull(); expect(dispatches).toEqual([]);
  });

for (const depth of [1, 3]) {
  it(`TD10a ${depth}-level active removal keeps the draft typed before completion`, async () => {
    const ids = seed(depth); const view = await mount(ids.at(-1)!);
    await begin(view, ids[0]!); expect(deletes).toEqual([ids.at(-1)]);
    await type(view, 'Unsent while deletion waits — café');
    const input = field(view); input.focus(); input.setSelectionRange(7, 12);
    release(); await settle(view);
    expect(input.value).toBe('Unsent while deletion waits — café');
    expect(field(view)).toBe(input);
    expect([input.selectionStart, input.selectionEnd]).toEqual([7, 12]);
    expect(activeId(view)).toBeNull(); expect(readLastViewedConversation()).toBeNull();
    expect(deletes).toEqual([...ids].reverse()); expect(dispatches).toEqual([]);
    // Repeat terminal observations must not restore cleared text or alter a later edit.
    await type(view, ''); await loadConversations(); await settle(view);
    expect(input.value).toBe(''); expect(activeId(view)).toBeNull();
    await type(view, 'Durable new draft');
    window.dispatchEvent(new Event('pagehide'));
    expect(localStorage.getItem(__draftStorageKey(SV3_DRAFT_KEY))).toBe('Durable new draft');
    view.remove(); const reloaded = await mount(null);
    expect(field(reloaded).value).toBe('Durable new draft'); expect(activeId(reloaded)).toBeNull();
  });
}

it('TD10a inactive removal and late completion preserve a newer conversation draft', async () => {
  seed(3); const view = await mount('removal-2'); await begin(view, 'removal-0');
  restoreActiveConversation('elsewhere'); await settle(view); await type(view, 'Newer conversation draft');
  release(); await settle(view);
  expect(field(view).value).toBe('Newer conversation draft'); expect(activeId(view)).toBe('elsewhere');
  expect(readLastViewedConversation()).toBe('elsewhere'); expect(dispatches).toEqual([]);
});

it('TD10a removal while the screen is disconnected preserves the retained draft', async () => {
  seed(3); const view = await mount('removal-2'); await begin(view, 'removal-0');
  await type(view, 'Draft on another screen'); view.remove(); release();
  await new Promise(resolve => setTimeout(resolve, 20));
  document.body.append(view); await settle(view);
  expect(field(view).value).toBe('Draft on another screen'); expect(activeId(view)).toBeNull();
  expect(dispatches).toEqual([]);
});

it('TD10a a lost response closes the confirmed transcript without erasing the draft', async () => {
  seed(3); lost = true; const view = await mount('removal-2'); await begin(view, 'removal-0');
  await type(view, 'Lost response draft'); release(); await settle(view);
  expect(field(view).value).toBe('Lost response draft'); expect(activeId(view)).toBeNull();
  expect(readLastViewedConversation()).toBeNull(); expect(deletes).toEqual(['removal-2']);
  expect(records.has('removal-0')).toBe(true); expect(records.has('removal-1')).toBe(true);
});

it('TD10a reload during removal keeps the draft when authoritative deletion arrives', async () => {
  seed(1); const old = await mount('removal-0'); await begin(old, 'removal-0');
  await type(old, 'Reload in progress draft'); old.remove();
  const next = await mount('removal-0');
  expect(field(next).value).toBe('Reload in progress draft');
  release(); await settle(next);
  expect(field(next).value).toBe('Reload in progress draft'); expect(activeId(next)).toBeNull();
  expect(dispatches).toEqual([]);
});

for (const outcome of ['cancel', 'refused', 'empty'] as const) {
  it(`TD10a ${outcome} never invents or restores draft text`, async () => {
    seed(1); const view = await mount('removal-0');
    if (outcome !== 'empty') await type(view, 'Kept unsent');
    refused = outcome === 'refused'; await begin(view, 'removal-0', outcome !== 'cancel');
    release(); await settle(view);
    expect(field(view).value).toBe(outcome === 'empty' ? '' : 'Kept unsent');
    expect(activeId(view)).toBe(outcome === 'empty' ? null : 'removal-0');
    expect(deletes).toHaveLength(outcome === 'cancel' ? 0 : 1); expect(dispatches).toEqual([]);
  });
}

it('TD10a reconnect and duplicate reports preserve the current draft without continuing deletion', async () => {
  seed(3); lost = true; existenceUnavailable = true;
  const view = await mount('removal-2'); await begin(view, 'removal-0');
  await type(view, 'Unconfirmed draft'); release(); await settle(view);
  expect(field(view).value).toBe('Unconfirmed draft'); expect(activeId(view)).toBe('removal-2');
  existenceUnavailable = false;
  for (const listener of status.listeners) listener({} as StatusSnapshot);
  await settle(view);
  expect(field(view).value).toBe('Unconfirmed draft'); expect(activeId(view)).toBeNull();
  await type(view, 'New edit after reconnect');
  for (const listener of status.listeners) listener({} as StatusSnapshot);
  await loadConversations(); await settle(view);
  expect(field(view).value).toBe('New edit after reconnect'); expect(deletes).toEqual(['removal-2']);
  expect(readLastViewedConversation()).toBeNull(); expect(dispatches).toEqual([]);
});

function online(): void {
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
}
function send(view: SearchV3View): void {
  field(view).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
}
it('TD10a a follow-up waiting on context cannot resurrect the removed conversation or send its draft', async () => {
  seed(1); online(); let historyReply!: () => void;
  historyGate = new Promise(resolve => { historyReply = resolve; });
  const view = await mount('removal-0'); await begin(view, 'removal-0');
  await type(view, 'Question waiting on context'); send(view); await settle(view);
  expect(dispatches).toEqual([]);
  release(); await settle(view); historyReply(); await settle(view);
  expect(field(view).value).toBe('Question waiting on context'); expect(activeId(view)).toBeNull();
  expect(readLastViewedConversation()).toBeNull(); expect(dispatches).toEqual([]);
});
it('TD10a a concurrent send elsewhere and its late failure cannot overwrite the next draft', async () => {
  seed(1); online(); let dispatchReply!: () => void;
  dispatchGate = new Promise(resolve => { dispatchReply = resolve; });
  const view = await mount('removal-0'); await begin(view, 'removal-0');
  restoreActiveConversation('elsewhere'); await settle(view);
  await type(view, 'Explicit concurrent send'); send(view); await settle(view);
  expect(dispatches).toHaveLength(1); expect(field(view).value).toBe('');
  await type(view, 'Next unsent draft'); release(); await settle(view); dispatchReply(); await settle(view);
  expect(field(view).value).toBe('Next unsent draft'); expect(activeId(view)).toBe('elsewhere');
  expect(readLastViewedConversation()).toBe('elsewhere'); expect(dispatches).toHaveLength(1);
});
