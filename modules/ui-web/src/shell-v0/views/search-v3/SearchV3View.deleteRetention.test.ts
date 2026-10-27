// @vitest-environment happy-dom

/** CD3: live draft ownership through the real view/store/dialog path; only HTTP is scheduled. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __resetConversationListForTest, loadConversations, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { DraftPersistence, __resetDraftProvidersForTest, __draftStorageKey } from '../../controllers/draftPersistence.js';
import { readLastViewedConversation, setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { SV3_DRAFT_KEY } from './fixtures.js';
import { parkSearchV3Handoff, takeSearchV3Handoff } from '../../utils/compose.js';
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
const readReleases: (() => void)[] = [];
let release: () => void;
let deleteGate: Promise<void>;
let lost = false;
let refused = false;
let existenceUnavailable = false;
let historyGate: Promise<void> | null = null;
let dispatchGate: Promise<void> | null = null;
let discoveryGate: Promise<void> | null = null;
let memoryGate: Promise<void> | null = null;
let refuseId: string | null = null;

function holdDelete(): void { deleteGate = new Promise(resolve => { release = resolve; }); }
const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  records.clear(); deletes.length = 0; dispatches.length = 0;
  lost = false; refused = false; existenceUnavailable = false; historyGate = null; dispatchGate = null; holdDelete();
  discoveryGate = null; memoryGate = null; refuseId = null;
  vi.stubGlobal('fetch', vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = new URL(String(input), 'http://engine.test');
    const id = decodeURIComponent(url.pathname.split('/conversations/')[1]?.split('/')[0] ?? '');
    if (init?.method === 'DELETE') {
      deletes.push(id); await deleteGate;
      if (refused || refuseId === id) return json({ error: 'Running work', errorCode: 'CONVERSATION_BUSY' }, 409);
      records.delete(id);
      if (lost) throw Error('Response lost after deletion');
      return json({ ok: true });
    }
    if (url.pathname.endsWith('/dispatch')) { dispatches.push(init?.body); await dispatchGate; return json({ error: 'Unavailable' }, 503); }
    if (url.pathname === '/api/memory') { await memoryGate; return json({ locked: false, memories: [] }); }
    if (url.searchParams.has('childrenOf')) {
      await discoveryGate;
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
afterEach(async () => {
  release();
  for (const done of readReleases.splice(0)) done();
  for (const dialog of document.querySelectorAll('jf-confirm-dialog')) dialog.dispatchEvent(new CustomEvent('cancel'));
  document.body.replaceChildren(); resetSearchState();
  __resetConversationListForTest(); __resetAiStateForTest(); status.listeners.clear();
  takeSearchV3Handoff();
  await new Promise(resolve => setTimeout(resolve, 220));
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


it('CD3 an unchanged live follow-up of the deleted place is retired before progress resets the view', async () => {
  seed(1); const view = await mount('removal-0'); await type(view, 'Old live follow-up');
  await begin(view, 'removal-0'); release(); await settle(view);
  expect(field(view).value).toBe(''); expect(activeId(view)).toBeNull();
  expect(readLastViewedConversation()).toBeNull();
  window.dispatchEvent(new Event('pagehide')); view.remove();
  const next = await mount(null); expect(field(next).value).toBe('');
});
it('CD3 a confirmed child retires only its unchanged live draft when the parent refuses', async () => {
  seed(3); const view = await mount('removal-2'); await type(view, 'Child follow-up');
  refuseId = 'removal-1'; await begin(view, 'removal-0'); release(); await settle(view);
  expect(field(view).value).toBe(''); expect(activeId(view)).toBeNull();
  expect(records.has('removal-0')).toBe(true); expect(records.has('removal-1')).toBe(true);
  expect(records.has('removal-2')).toBe(false);
});
it('CD3 a recovered draft remains ambiguous even beside a matching restore pointer', async () => {
  seed(1); localStorage.setItem(__draftStorageKey(SV3_DRAFT_KEY), 'Recovered text');
  const view = await mount('removal-0'); await begin(view, 'removal-0'); release(); await settle(view);
  expect(field(view).value).toBe('Recovered text'); expect(activeId(view)).toBeNull();
});
for (const replacement of ['identical programmatic', 'edit away and back', 'IME'] as const) {
  it(`CD3 preserves ${replacement} replacement after original Remove activation`, async () => {
    seed(1); const view = await mount('removal-0'); await type(view, 'Original text');
    await begin(view, 'removal-0');
    if (replacement === 'identical programmatic') composer(view).draft = 'Original text';
    else if (replacement === 'edit away and back') { await type(view, 'Different'); await type(view, 'Original text'); }
    else { field(view).value = 'Original text'; field(view).dispatchEvent(new InputEvent('input', { isComposing: true })); }
    release(); await settle(view);
    expect(field(view).value).toBe('Original text'); expect(activeId(view)).toBeNull();
  });
}
for (const phase of ['discovery', 'memory'] as const) {
  it(`CD3 draft protection begins before the ${phase} read`, async () => {
    seed(1); const view = await mount('removal-0'); await type(view, 'Old draft');
    let resume!: () => void; const gate = new Promise<void>(resolve => { resume = resolve; });
    readReleases.push(resume);
    if (phase === 'discovery') discoveryGate = gate; else memoryGate = gate;
    const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
    sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'removal-0' }, bubbles: true, composed: true }));
    await settle(view); await type(view, 'New draft during reads'); resume(); await settle(view);
    const dialog = document.querySelector('jf-confirm-dialog') as Updatable;
    expect(dialog).not.toBeNull(); dialog.dispatchEvent(new CustomEvent('confirm'));
    await settle(view); release(); await settle(view);
    expect(field(view).value).toBe('New draft during reads'); expect(activeId(view)).toBeNull();
  });
}
it('CD3 duplicate Remove activations share one consent and one deletion', async () => {
  seed(1); const view = await mount('removal-0');
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  for (let i = 0; i < 2; i++) sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'removal-0' }, bubbles: true, composed: true }));
  await settle(view); expect(document.querySelectorAll('jf-confirm-dialog')).toHaveLength(1);
  document.querySelector('jf-confirm-dialog')!.dispatchEvent(new CustomEvent('confirm'));
  await settle(view); release(); await settle(view); expect(deletes).toEqual(['removal-0']);
});
it('CD3 a second window keeps its draft when the initiating window deletes its own unchanged draft', async () => {
  // Separate windows share localStorage, but each has its own sessionStorage recovery journal.
  // Both views live in this test document, so scope just that browser resource per controller.
  const sessions = new WeakMap<DraftPersistence, Storage>();
  const open = DraftPersistence.prototype.openSessionAttachment;
  const save = DraftPersistence.prototype.saveSessionAttachment;
  function inWindow<T>(owner: DraftPersistence, action: () => T): T {
    let storage = sessions.get(owner);
    if (storage === undefined) { storage = new Storage(); sessions.set(owner, storage); }
    const previous = sessionStorage;
    vi.stubGlobal('sessionStorage', storage);
    try { return action(); } finally { vi.stubGlobal('sessionStorage', previous); }
  }
  vi.spyOn(DraftPersistence.prototype, 'openSessionAttachment').mockImplementation(function (this: DraftPersistence, ...args) {
    return inWindow(this, () => open.apply(this, args));
  });
  vi.spyOn(DraftPersistence.prototype, 'saveSessionAttachment').mockImplementation(function (this: DraftPersistence, ...args) {
    return inWindow(this, () => save.apply(this, args));
  });
  seed(1); const first = await mount('removal-0'); await type(first, 'First draft');
  const second = await mount('removal-0'); await type(second, 'Second draft');
  await begin(first, 'removal-0'); release(); await settle(first); await settle(second);
  expect(field(first).value).toBe(''); expect(field(second).value).toBe('Second draft');
  expect(activeId(first)).toBeNull(); expect(activeId(second)).toBeNull();
});
it('CD3 changing place during discovery cancels the pending consent', async () => {
  seed(1); const view = await mount('removal-0'); let resume!: () => void;
  discoveryGate = new Promise(resolve => { resume = resolve; }); readReleases.push(resume);
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'removal-0' }, bubbles: true, composed: true }));
  await settle(view); restoreActiveConversation('elsewhere'); await settle(view);
  resume(); await settle(view);
  expect(document.querySelector('jf-confirm-dialog[open]')).toBeNull(); expect(deletes).toEqual([]);
});
it('CD3 returning to the hero with a recovered draft cancels discovery consent', async () => {
  seed(1); localStorage.setItem(__draftStorageKey(SV3_DRAFT_KEY), 'Recovered text');
  const view = await mount('removal-0'); let resume!: () => void;
  discoveryGate = new Promise(resolve => { resume = resolve; }); readReleases.push(resume);
  view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-remove', {
    detail: { id: 'removal-0' }, bubbles: true, composed: true,
  }));
  await settle(view); restoreActiveConversation(null); await settle(view);
  expect(activeId(view)).toBeNull(); expect(field(view).value).toBe('Recovered text');
  resume(); await settle(view);
  expect(document.querySelector('jf-confirm-dialog[open]')).toBeNull(); expect(deletes).toEqual([]);
});
it('CD3 leaving the screen during a memory read cancels consent and ignores the late count', async () => {
  seed(1); const view = await mount('removal-0'); let resume!: () => void;
  memoryGate = new Promise(resolve => { resume = resolve; }); readReleases.push(resume);
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'removal-0' }, bubbles: true, composed: true }));
  await settle(view); view.remove(); resume(); await settle(view);
  expect(document.querySelector('jf-confirm-dialog[open]')).toBeNull(); expect(deletes).toEqual([]);
});

for (const outcome of ['cancel', 'refusal', 'different tree'] as const) {
  it(`CD3 ${outcome} cannot lend draft-discard authority to another deletion`, async () => {
    seed(1); const view = await mount('removal-0'); await type(view, 'Kept here');
    refused = outcome === 'refusal';
    await begin(view, outcome === 'different tree' ? 'elsewhere' : 'removal-0', outcome !== 'cancel');
    release(); await settle(view);
    records.delete('removal-0');
    await loadConversations(); await settle(view);
    expect(field(view).value).toBe('Kept here'); expect(activeId(view)).toBeNull();
  });
}
it('CD3 another window storage write during discovery makes draft ownership ambiguous', async () => {
  seed(1); const view = await mount('removal-0'); await type(view, 'Live text');
  let resume!: () => void; discoveryGate = new Promise(resolve => { resume = resolve; }); readReleases.push(resume);
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'removal-0' }, bubbles: true, composed: true }));
  await settle(view);
  window.dispatchEvent(new StorageEvent('storage', { key: __draftStorageKey(SV3_DRAFT_KEY), newValue: 'Another window text' }));
  resume(); await settle(view);
  document.querySelector('jf-confirm-dialog')!.dispatchEvent(new CustomEvent('confirm'));
  await settle(view); release(); await settle(view);
  expect(field(view).value).toBe('Live text'); expect(activeId(view)).toBeNull();
});

it('CD3 identical programmatic replacement before Remove has no provable live owner', async () => {
  seed(1); const view = await mount('removal-0'); await type(view, 'Same text');
  composer(view).draft = 'Same text'; await begin(view, 'removal-0'); release(); await settle(view);
  expect(field(view).value).toBe('Same text'); expect(activeId(view)).toBeNull();
});
it('CD3 an identical-text handoff during deletion retains the new place draft', async () => {
  seed(1); const view = await mount('removal-0'); await type(view, 'Same handoff text');
  await begin(view, 'removal-0');
  parkSearchV3Handoff({ query: 'Same handoff text', forceShapeId: 'core.rag-ask' });
  await settle(view); release(); await settle(view);
  expect(field(view).value).toBe('Same handoff text'); expect(activeId(view)).toBeNull();
});
it('CD3 a handoff during a slow memory read cancels the old consent', async () => {
  seed(1); const view = await mount('removal-0'); let resume!: () => void;
  memoryGate = new Promise(resolve => { resume = resolve; }); readReleases.push(resume);
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  sidebar.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'removal-0' }, bubbles: true, composed: true }));
  await settle(view); parkSearchV3Handoff({ query: 'Prepared elsewhere', forceShapeId: 'core.rag-ask' });
  resume(); await settle(view);
  expect(document.querySelector('jf-confirm-dialog[open]')).toBeNull(); expect(deletes).toEqual([]);
  expect(field(view).value).toBe('Prepared elsewhere');
});
it('CD3 later reconciliation retires an unchanged live draft only when absence is confirmed', async () => {
  seed(1); lost = true; existenceUnavailable = true;
  const view = await mount('removal-0'); await type(view, 'Unchanged pending follow-up');
  await begin(view, 'removal-0'); release(); await settle(view);
  expect(field(view).value).toBe('Unchanged pending follow-up'); expect(activeId(view)).toBe('removal-0');
  existenceUnavailable = false;
  for (const listener of status.listeners) listener({} as StatusSnapshot);
  await settle(view);
  expect(field(view).value).toBe(''); expect(activeId(view)).toBeNull();
});
