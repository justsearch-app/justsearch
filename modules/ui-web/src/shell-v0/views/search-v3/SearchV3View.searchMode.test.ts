// @vitest-environment happy-dom

/**
 * Search mode in Search v3 (WP1.1) — end to end against a fake backend.
 *
 * The search store is the REAL shared one (`state/searchState.ts`), so every request counted here is
 * the request the shipped window's store would send for the same input; only `fetch` is stubbed. The
 * fake answers four routes: `/api/knowledge/search` from a small corpus, `/api/chat/dispatch` with an
 * SSE stream the case drives, the conversation list/record/history reads, and the branch POST.
 *
 * Mechanisms asserted, not appearances:
 *  - **No turn from a search.** The conversation list and the dispatch endpoint are read back.
 *  - **Scope is paths, fixed when asked.** The dispatch body's `docIds` must hold exactly the chosen
 *    documents (as a set: membership and count, not order), after the list under it has been
 *    re-ranked and filtered.
 *  - **A search never steers.** The fake agent controller's `steer` is read back.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';

interface FakeCtrl {
  conversation: unknown[];
  toolCalls: Record<string, unknown>;
  streamingText: string;
  isStreaming: boolean;
  runInFlight: boolean;
  runKind: 'agent' | 'workflow' | 'background' | null;
  conversationId: string | null;
  sessionId: string | null;
  iterationsUsed: number;
  toolCallsExecuted: number;
  budgetUpdates: unknown[];
  reasoning: ReasoningController;
  budgetGate: null;
  contextGate: null;
  runPark: null;
  groundingDeltaPaths: Set<string>;
  send: ReturnType<typeof vi.fn>;
  steer: ReturnType<typeof vi.fn>;
  cancelSession: ReturnType<typeof vi.fn>;
  reattachActiveRunOnLoad: ReturnType<typeof vi.fn>;
}

const makeCtrl = (): FakeCtrl => ({
  conversation: [],
  toolCalls: {},
  streamingText: '',
  isStreaming: false,
  runInFlight: false,
  runKind: null,
  conversationId: null,
  sessionId: null,
  iterationsUsed: 0,
  toolCallsExecuted: 0,
  budgetUpdates: [],
  reasoning: new ReasoningController(() => {}),
  budgetGate: null,
  contextGate: null,
  runPark: null,
  groundingDeltaPaths: new Set(),
  send: vi.fn(async () => {}),
  steer: vi.fn(async () => true),
  cancelSession: vi.fn(async () => {}),
  reattachActiveRunOnLoad: vi.fn(async () => {}),
});

let ctrl: FakeCtrl = makeCtrl();
let ctrlExists = false;

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => {
    ctrlExists = true;
    return ctrl as unknown as AgentSessionController;
  },
  peekAgentSessionController: () =>
    ctrlExists ? (ctrl as unknown as AgentSessionController) : null,
  subscribeAgentSession: () => () => {},
}));

import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import {
  addScopeChip,
  buildSearchIntent,
  getScopeChips,
  clearScopeChips,
  resetSearchState,
  setQuery,
  submitSearch,
} from '../../state/searchState.js';
import {
  __resetSearchFiltersForTest,
  getFacetSelections,
  getFilters,
  setFilterRange,
  toggleFacetValue,
} from '../../state/searchFiltersState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import {
  __resetConversationListForTest,
  restoreActiveConversation,
} from '../../state/conversationListStore.js';
import {
  __draftStorageKey,
  __resetDraftProvidersForTest,
} from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { BRANCH_MENU_RETRY, SV3_DRAFT_KEY } from './fixtures.js';
import { SV3_COMPOSER_SUBMIT, type Sv3ComposerSubmit } from './Sv3Composer.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

/* ── The fake backend ──────────────────────────────────────────────────────────────────────────── */

interface WireHit {
  id: string;
  fields: { path: string; content_preview?: string };
}
const wireHit = (id: string, path: string): WireHit => ({ id, fields: { path } });

interface Stream {
  readonly body: Record<string, unknown>;
  readonly signal: AbortSignal | null;
  emit(event: string, data: unknown): void;
  end(): void;
}

interface Backend {
  /** What the search endpoint answers, by the query it was asked. Unknown queries match nothing. */
  corpus: Record<string, WireHit[]>;
  /** Hold every search response until released — the out-of-order and in-flight cases. */
  holdSearches: boolean;
  held: Array<() => void>;
  /** Every search body, in order. */
  searches: Array<Record<string, unknown>>;
  /** Every dispatch stream, in order; the case drives each one. */
  streams: Stream[];
  /** Conversation records, written when a dispatch for a conversation lands. */
  threads: Record<string, { conversationId: string; events: unknown[] }>;
  histories: Record<string, { sessionId: string; messages: unknown[] }>;
  conversations: Array<Record<string, unknown>>;
  branches: Array<{ parent: string; fromMsgId: string }>;
  minted: number;
}
let backend: Backend;
let fetchMock: ReturnType<typeof vi.fn>;
let clock = 0;

const bodyOf = (init: unknown): Record<string, unknown> =>
  JSON.parse(String((init as { body?: unknown })?.body ?? '{}')) as Record<string, unknown>;

const json = (value: unknown, status = 200): unknown => ({
  ok: status < 400,
  status,
  json: () => Promise.resolve(value),
  text: () => Promise.resolve(JSON.stringify(value)),
});

const wireEvent = (id: string, kind: string, content: string): Record<string, unknown> => ({
  id,
  occurredAt: new Date(Date.parse('2026-10-19T10:00:00Z') + clock++ * 1000).toISOString(),
  kind,
  originator: 'agent',
  content,
  attributes: {},
});

/** Store-minted message ids are UUIDs; Edit and Retry are offered only on store-minted turns. */
const storedId = (n: number): string => `11111111-2222-4333-8444-${String(n).padStart(12, '0')}`;

/** The record a completed ask leaves behind: the question and the answer, both store-minted. */
function recordTurn(conversationId: string, question: string, answer: string): void {
  const thread = backend.threads[conversationId] ?? { conversationId, events: [] };
  const n = thread.events.length;
  const userId = storedId(Object.keys(backend.threads).length * 100 + n);
  const answerId = storedId(Object.keys(backend.threads).length * 100 + n + 1);
  thread.events = [
    ...thread.events,
    wireEvent(userId, 'USER_MESSAGE', question),
    wireEvent(answerId, 'ASSISTANT_MESSAGE', answer),
  ];
  backend.threads[conversationId] = thread;
  const history = backend.histories[conversationId] ?? { sessionId: conversationId, messages: [] };
  history.messages = [
    ...history.messages,
    { role: 'user', content: question, id: userId },
    { role: 'assistant', content: answer, id: answerId },
  ];
  backend.histories[conversationId] = history;
  if (!backend.conversations.some((c) => c.sessionId === conversationId)) {
    backend.conversations = [
      ...backend.conversations,
      {
        sessionId: conversationId,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: question,
        shapeId: 'core.rag-ask',
      },
    ];
  }
}

function openStream(init: { signal?: AbortSignal; body?: unknown }): unknown {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  const signal = init.signal ?? null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  backend.streams.push({
    body: bodyOf(init),
    signal,
    emit: (event, data) =>
      push({ done: false, value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }),
    end: () => push({ done: true }),
  });
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async () => {
          while (queued.length === 0) {
            if (signal?.aborted === true) throw new Error('The operation was aborted.');
            await new Promise<void>((resolve) => {
              wake = resolve;
              signal?.addEventListener('abort', () => resolve(), { once: true });
            });
          }
          return queued.shift();
        },
        releaseLock: () => {},
      }),
    },
  };
}

function stubFetch(): void {
  fetchMock.mockImplementation(async (url: unknown, init: Record<string, unknown> = {}) => {
    const href = String(url);
    if (href.includes('/api/knowledge/search')) {
      const body = bodyOf(init);
      backend.searches.push(body);
      const hits = backend.corpus[String(body.query ?? '').trim()] ?? [];
      const answer = json({ results: hits, totalHits: hits.length, matchCount: hits.length });
      if (!backend.holdSearches) return answer;
      return new Promise((resolve) => backend.held.push(() => resolve(answer)));
    }
    if (href.includes('/api/chat/dispatch')) return openStream(init as { signal?: AbortSignal });
    if (href.includes('/api/chat/runs/live')) return json({ runs: [] });
    if (href.includes('/branch?fromMsgId=')) {
      const parent = decodeURIComponent(href.slice(0, href.indexOf('/branch?')).split('/').pop() ?? '');
      const fromMsgId = decodeURIComponent(href.slice(href.indexOf('fromMsgId=') + 'fromMsgId='.length));
      backend.branches.push({ parent, fromMsgId });
      backend.minted += 1;
      const child = `${parent}-branch-${backend.minted}`;
      backend.threads[child] = { conversationId: child, events: [] };
      backend.histories[child] = { sessionId: child, messages: [] };
      return json({ sessionId: child });
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/history')) {
      const id = decodeURIComponent(href.slice(0, -'/history'.length).split('/').pop() ?? '');
      const record = backend.histories[id];
      return record === undefined ? json({}, 404) : json(record);
    }
    if (href.includes('/api/chat/conversations')) return json({ sessions: backend.conversations });
    if (href.includes('/api/thread/')) {
      const id = decodeURIComponent(href.slice(href.lastIndexOf('/api/thread/') + '/api/thread/'.length));
      const record = backend.threads[id];
      return record === undefined ? json({ conversationId: id, events: [] }) : json(record);
    }
    return json({});
  });
}

/** Finish the latest dispatch with an answer, and write the record a real store would hold. */
async function answerLatest(el: Mounted, answer = 'It is in the lease.'): Promise<void> {
  const stream = backend.streams.at(-1);
  if (!stream) throw new Error('nothing was dispatched');
  recordTurn(String(stream.body.conversationId), String(stream.body.question ?? ''), answer);
  stream.emit('chunk', { text: answer });
  stream.emit('done', {});
  stream.end();
  await settle(el);
}

/* ── Harness ───────────────────────────────────────────────────────────────────────────────────── */

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true, llmContextTokens: 4096 } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

/** The no-GPU install: a reachable backend, documents indexed, and no chat model at all. */
function chatUnavailable(): void {
  __feedForTest({
    inference: { mode: 'unknown', available: false } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  __resetSearchFiltersForTest();
  clearScopeChips();
  ctrl = makeCtrl();
  ctrlExists = false;
  clock = 0;
  backend = {
    corpus: {},
    holdSearches: false,
    held: [],
    searches: [],
    streams: [],
    threads: {},
    histories: {},
    conversations: [],
    branches: [],
    minted: 0,
  };
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  for (const el of [...document.querySelectorAll('jf-context-menu')]) el.remove();
  resetSearchState();
  clearScopeChips();
  __resetSearchFiltersForTest();
  __resetAiStateForTest();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Mounted, turns = 12): Promise<void> {
  for (let turn = 0; turn < turns; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

/** Advance the store's debounce and settle windows (fake timers), then let the renders land. */
async function passTime(el: Mounted, ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
  await el.updateComplete;
}

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const q = <T extends HTMLElement = HTMLElement>(host: Element, testid: string): T | null =>
  host.shadowRoot?.querySelector<T>(`[data-testid="${testid}"]`) ?? null;

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = q<HTMLTextAreaElement>(composer, 'sv3-composer-input');
  if (!field) throw new Error('no field in the composer');
  return field;
}

async function type(el: Mounted, draft: string): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  await el.updateComplete;
  return field;
}

async function pressEnter(
  el: Mounted,
  opts: { ctrlKey?: boolean; shiftKey?: boolean; isComposing?: boolean } = {},
): Promise<void> {
  const field = await fieldOf(el);
  field.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: 'Enter',
      ctrlKey: opts.ctrlKey === true,
      shiftKey: opts.shiftKey === true,
      isComposing: opts.isComposing === true,
      bubbles: true,
    }),
  );
  await el.updateComplete;
}

async function chooseTier(el: Mounted, tier: 'search' | 'ask' | 'delegate'): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  q<HTMLButtonElement>(composer, 'sv3-composer-tier')?.click();
  await composer.updateComplete;
  const rung = [
    ...(composer.shadowRoot?.querySelectorAll<HTMLButtonElement>(
      '[data-testid="sv3-composer-tier-option"]',
    ) ?? []),
  ].find((item) => item.dataset.tier === tier);
  if (!rung) throw new Error(`no rung for ${tier}`);
  rung.click();
  await composer.updateComplete;
  await el.updateComplete;
}

/** The results region, wherever the main surface mounts it. */
async function results(el: Mounted): Promise<Mounted | null> {
  const main = await region(el, 'jf-sv3-main');
  const found = main.shadowRoot?.querySelector('jf-sv3-results') as Mounted | null;
  if (found) await found.updateComplete;
  return found;
}

async function card(el: Mounted): Promise<Mounted | null> {
  const host = await results(el);
  const found = host?.shadowRoot?.querySelector('jf-results-card') as Mounted | null;
  if (found) await found.updateComplete;
  return found ?? null;
}

async function rows(el: Mounted): Promise<HTMLElement[]> {
  const found = await card(el);
  return [
    ...(found?.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="search-result-row"]') ?? []),
  ];
}

const rowTitles = async (el: Mounted): Promise<string[]> =>
  (await rows(el)).map((row) => row.querySelector('.title-text')?.textContent?.trim() ?? '');

async function clickRow(el: Mounted, title: string, opts: MouseEventInit = {}): Promise<void> {
  const row = (await rows(el)).find(
    (candidate) => candidate.querySelector('.title-text')?.textContent?.trim() === title,
  );
  if (!row) throw new Error(`no row titled ${title}`);
  row.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, ...opts }));
  await settle(el, 2);
}

/** Press a `jf-control` the way a reader does: through the native button inside it. */
async function press(host: Element | null | undefined): Promise<void> {
  if (!host) throw new Error('press: no control');
  await (host as Mounted).updateComplete;
  const button = host.shadowRoot?.querySelector('button');
  if (!button) throw new Error('press: the control rendered no button');
  button.click();
}

/** Open the first conversation in the sidebar, the way a reader does. */
async function openFromSidebar(el: Mounted): Promise<void> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const row = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])][0] as
    | Mounted
    | undefined;
  if (!row) throw new Error('no conversation in the sidebar');
  await row.updateComplete;
  row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
  await settle(el);
}

const conversationCount = (el: SearchV3View): number => el.sessions.sessions.length;

const dispatches = (): Stream[] => backend.streams;

/* ── S1.1 / S1.2 — the modes, the keys, a fresh window ──────────────────────────────────────── */

describe('S1.1/S1.2 — a fresh window is in Search mode, and reload runs nothing', () => {
  it('starts in Search, says so to assistive tech, and words what Enter does', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-tier')?.getAttribute('aria-label')).toBe('Mode: Search');
    await type(el, 'lease');
    expect(q(composer, 'sv3-composer-send')?.getAttribute('aria-label')).toMatch(
      /^Search · Enter searches/,
    );
  });

  it('restores the draft into the composer without searching, asking or delegating', async () => {
    vi.useFakeTimers();
    localStorage.setItem(__draftStorageKey(SV3_DRAFT_KEY), 'northfield lease');
    aiOnline();
    const el = await mount();
    await passTime(el, 2000);
    expect((await fieldOf(el)).value).toBe('northfield lease');
    expect(backend.searches).toEqual([]);
    expect(dispatches()).toEqual([]);
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('keeps Shift+Enter a newline and an IME Enter the IME’s, in Search mode too', async () => {
    vi.useFakeTimers();
    aiOnline();
    const el = await mount();
    const submits: Sv3ComposerSubmit[] = [];
    el.addEventListener(SV3_COMPOSER_SUBMIT, (e) => submits.push((e as CustomEvent).detail));
    await type(el, 'lease');
    await pressEnter(el, { shiftKey: true });
    await pressEnter(el, { isComposing: true });
    expect(submits).toEqual([]);
    await pressEnter(el);
    await pressEnter(el, { ctrlKey: true });
    expect(submits.map((s) => s.tier)).toEqual(['search', 'delegate']);
  });

  it('does not search a half-composed IME string, and searches once composition ends', async () => {
    vi.useFakeTimers();
    aiOnline();
    const el = await mount();
    const field = await fieldOf(el);
    field.value = 'にほ';
    field.dispatchEvent(new InputEvent('input', { isComposing: true }));
    await passTime(el, 1000);
    expect(backend.searches).toEqual([]);
    field.value = '日本';
    field.dispatchEvent(new CompositionEvent('compositionend', { data: '日本' }));
    await passTime(el, 250);
    expect(backend.searches.map((s) => s.query)).toEqual(['日本']);
  });
});

/* ── S1.3 — typing searches; Enter runs the full pass; nothing becomes a turn ─────────────────── */

describe('S1.3 — the same staged searches the default window sends, and no turn', () => {
  it('sends the quick pass while typing and the refined pass on settle, through the one intent seam', async () => {
    vi.useFakeTimers();
    backend.corpus.northfield = [wireHit('d1', 'Contracts/Northfield.pdf')];
    aiOnline();
    const el = await mount();
    await type(el, 'northfield');
    await passTime(el, 250);
    expect(backend.searches).toEqual([buildSearchIntent('northfield', {}, 'quick')]);
    await passTime(el, 700);
    expect(backend.searches).toEqual([
      buildSearchIntent('northfield', {}, 'quick'),
      buildSearchIntent('northfield', {}, 'refined'),
    ]);
    expect(await rowTitles(el)).toEqual(['Northfield.pdf']);
    // No answer, no agent, no conversation, no turn.
    expect(dispatches()).toEqual([]);
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(conversationCount(el)).toBe(0);
    expect(el.getAttribute('composer-state')).toBe('docked');
  });

  it('runs the full pass at once on Enter, superseding the staged ones', async () => {
    vi.useFakeTimers();
    aiOnline();
    const el = await mount();
    await type(el, 'northfield');
    await pressEnter(el);
    await passTime(el, 2000);
    expect(backend.searches).toEqual([buildSearchIntent('northfield', {}, 'refined')]);
    expect(dispatches()).toEqual([]);
    expect(conversationCount(el)).toBe(0);
    // The query stays in the composer: a search is not a send that consumes the draft.
    expect((await fieldOf(el)).value).toBe('northfield');
  });

  it('typing in Ask mode issues no search at all', async () => {
    vi.useFakeTimers();
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'ask');
    await type(el, 'northfield');
    await passTime(el, 2000);
    expect(backend.searches).toEqual([]);
  });
});

/* ── S1.4 — no chat: Search still does everything ─────────────────────────────────────────────── */

describe('S1.4 — with chat unavailable, Search works and every ask says why it cannot', () => {
  it('types, lists, selects and opens; Ask keeps the query and states the reason', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md'), wireHit('b', 'docs/b.md')];
    chatUnavailable();
    const el = await mount();
    await type(el, 'lease');
    await pressEnter(el);
    await settle(el);
    // Both hits are listed; their order is not what this checks.
    expect((await rowTitles(el)).sort()).toEqual(['a.md', 'b.md']);

    await clickRow(el, 'a.md', { ctrlKey: true });
    await clickRow(el, 'b.md');
    await settle(el);
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).not.toBeNull();

    // The ask-about control carries the reason and does nothing when pressed.
    const host = await results(el);
    const askAbout = q(host as Element, 'sv3-results-ask');
    expect(askAbout).not.toBeNull();
    const button = askAbout?.shadowRoot?.querySelector('button');
    expect(button?.getAttribute('aria-disabled')).toBe('true');
    await press(askAbout);
    await settle(el);
    expect(el.tier).toBe('search');

    // Choosing Ask shows the existing reason, keeps the query and the selection.
    await chooseTier(el, 'ask');
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-notice')?.textContent?.trim()).not.toBe('');
    await pressEnter(el);
    await settle(el);
    expect(dispatches()).toEqual([]);
    expect((await fieldOf(el)).value).toBe('lease');
    expect(q(host as Element, 'sv3-results-selection')?.textContent).toContain('1');
  });
});

/* ── S1.5 — the shared card, opening without losing the chosen set ───────────────────────────── */

describe('S1.5 — results use the shared results card and open in the document pane', () => {
  it('renders the shared card, opens a hit in the pane, and keeps a multi-file choice', async () => {
    backend.corpus.lease = [
      wireHit('a', 'docs/a.md'),
      wireHit('b', 'docs/b.md'),
      wireHit('c', 'docs/c.md'),
    ];
    aiOnline();
    const el = await mount();
    await type(el, 'lease');
    await pressEnter(el);
    await settle(el);
    expect(await card(el)).not.toBeNull();

    await clickRow(el, 'a.md', { ctrlKey: true });
    await clickRow(el, 'b.md', { ctrlKey: true });
    // A plain click OPENS; it does not throw away the two files already chosen.
    await clickRow(el, 'c.md');
    await settle(el);
    const pane = el.shadowRoot?.querySelector('jf-sv3-pane') as (Mounted & { docPath: string }) | null;
    expect(pane?.docPath).toBe('docs/c.md');
    const chosen = (await rows(el)).filter((row) => row.dataset.selected === 'true');
    // The two chosen files stay chosen; the order the rows show them in is not specified.
    expect(chosen.map((row) => row.querySelector('.title-text')?.textContent?.trim()).sort()).toEqual([
      'a.md',
      'b.md',
    ]);
  });
});

/* ── S1.6 — Ask about the chosen files ────────────────────────────────────────────────────────── */

async function searchFor(el: Mounted, query: string): Promise<void> {
  await type(el, query);
  await pressEnter(el);
  await settle(el);
}

async function askAboutChosen(el: Mounted): Promise<void> {
  const host = await results(el);
  await press(q(host as Element, 'sv3-results-ask'));
  await settle(el);
}

async function askNow(el: Mounted, question: string): Promise<void> {
  await type(el, question);
  await pressEnter(el);
  await settle(el);
}

describe('S1.6 — an Ask about the chosen files retrieves from exactly those files', () => {
  it('sends the chosen documents as paths, each document once', async () => {
    backend.corpus.lease = [
      wireHit('a#1', 'docs/a.md'),
      wireHit('b#1', 'docs/b.md'),
      wireHit('a#2', 'docs/a.md'),
      wireHit('u#1', 'docs/unique-answer.md'),
    ];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true });
    await clickRow(el, 'b.md', { ctrlKey: true });
    // Every hit is a row, two of them from a.md; the row order is not what this checks.
    expect((await rowTitles(el)).sort()).toEqual(['a.md', 'a.md', 'b.md', 'unique-answer.md']);
    // Choosing the other, not yet chosen hit from a.md adds no document.
    const secondA = (await rows(el)).find(
      (row) =>
        row.querySelector('.title-text')?.textContent?.trim() === 'a.md' &&
        row.dataset.selected !== 'true',
    );
    expect(secondA).toBeDefined();
    secondA?.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, ctrlKey: true }));
    await settle(el);

    const host = await results(el);
    expect(q(host as Element, 'sv3-results-ask')?.textContent).toContain('Ask about these 2 files');
    await askAboutChosen(el);
    expect(el.tier).toBe('ask');
    const composer = await region(el, 'jf-sv3-composer');
    const scope = q(composer, 'sv3-composer-scope')?.textContent ?? '';
    expect(scope).toContain('2 files');
    expect(scope).toContain('a.md');
    expect(scope).toContain('b.md');

    await askNow(el, 'when does the lease end?');
    expect(dispatches()).toHaveLength(1);
    // Exactly the chosen documents, each once; their order is not specified.
    const docIds = (dispatches()[0]?.body.docIds as string[] | undefined) ?? [];
    expect([...docIds].sort()).toEqual(['docs/a.md', 'docs/b.md']);
    expect(dispatches()[0]?.body.question).toBe('when does the lease end?');
  });

  it('fixes the set when asked: a later re-rank or filter does not change it', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md'), wireHit('b', 'docs/b.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    await clickRow(el, 'b.md', { ctrlKey: true });
    await askAboutChosen(el);
    // The list under the scope changes after the ask was prepared.
    backend.corpus.lease = [wireHit('c', 'docs/c.md'), wireHit('a', 'docs/a.md')];
    toggleFacetValue('file_kind', 'text');
    await settle(el);
    await askNow(el, 'what changed?');
    expect(dispatches()[0]?.body.docIds).toEqual(['docs/b.md']);
  });

  it('drops a chosen row that a new result list no longer holds — visibly', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md'), wireHit('b', 'docs/b.md')];
    backend.corpus['lease 2026'] = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true });
    await clickRow(el, 'b.md', { ctrlKey: true });
    await searchFor(el, 'lease 2026');
    const host = await results(el);
    expect(q(host as Element, 'sv3-results-ask')?.textContent).toContain('Ask about this file');
    expect(q(host as Element, 'sv3-results-selection')?.textContent).toContain('1');
  });

  it('offers no ask about an empty choice, and never sends an empty set as a whole-index ask', async () => {
    backend.corpus.lease = [wireHit('chunk:1', '')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    const host = await results(el);
    expect(q(host as Element, 'sv3-results-ask')).toBeNull();
    // "Ask about this file" on a hit with no document path refuses rather than widening.
    const cardEl = await card(el);
    cardEl?.dispatchEvent(
      new CustomEvent('card-scope-file', {
        detail: { id: 'chunk:1', path: '', title: 'Untitled' },
        bubbles: true,
        composed: true,
      }),
    );
    await settle(el);
    expect(el.tier).toBe('search');
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-scope')).toBeNull();
  });

  it('scopes "Ask about this file" from the card menu to that one document', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md'), wireHit('b', 'docs/b.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    const cardEl = await card(el);
    cardEl?.dispatchEvent(
      new CustomEvent('card-scope-file', {
        detail: { id: 'b', path: 'docs/b.md', title: 'b.md' },
        bubbles: true,
        composed: true,
      }),
    );
    await settle(el);
    await askNow(el, 'who signed?');
    expect(dispatches()[0]?.body.docIds).toEqual(['docs/b.md']);
  });
});

/* ── S1.7 — the scope's lifetime ──────────────────────────────────────────────────────────────── */

describe('S1.7 — chosen files belong to one Ask and its re-sends, nothing later', () => {
  it('does not carry the files into a later ordinary Ask', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true });
    await askAboutChosen(el);
    await askNow(el, 'first?');
    await answerLatest(el);
    await askNow(el, 'and then?');
    expect(dispatches().map((s) => s.body.docIds)).toEqual([['docs/a.md'], []]);
  });

  it('drops a prepared scope on New session', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true });
    await askAboutChosen(el);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    q(sidebar, 'sv3-sidebar-new')?.click();
    await settle(el);
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-scope')).toBeNull();
    await chooseTier(el, 'ask');
    await askNow(el, 'anything?');
    expect(dispatches()[0]?.body.docIds).toEqual([]);
  });

  it('Retry of a scoped answer keeps the same files, and says so', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md'), wireHit('b', 'docs/b.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true });
    await clickRow(el, 'b.md', { ctrlKey: true });
    await askAboutChosen(el);
    await askNow(el, 'when does it end?');
    await answerLatest(el);
    await chooseTier(el, 'delegate'); await type(el, 'unrelated B');
    const original = el.sessions.activeId;
    const { createNavigationHandler } = await import('../../router/navigationHandler.js');
    await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
      { kind: 'navigate', target: 'core.search-v3-surface', state: { query: 'old journal A' } },
      { push: false, taskOrigin: 'restore' });
    await settle(el);
    expect((await fieldOf(el)).value).toBe('unrelated B');
    expect(el.shadowRoot!.querySelector('[data-testid=sv3-draft-review]')).not.toBeNull();

    const main = await region(el, 'jf-sv3-main');
    const turn = main.shadowRoot?.querySelector('[data-testid="sv3-turn"]');
    // The turn names both files and their count; the order it lists them in is not specified.
    const turnScope = turn?.querySelector('[data-testid="sv3-turn-scope"]')?.textContent ?? '';
    expect(turnScope).toContain('2 files');
    expect(turnScope).toContain('a.md');
    expect(turnScope).toContain('b.md');
    await press(turn?.querySelector('[data-testid="sv3-turn-context-menu"]'));
    await settle(el);
    const menu = document.querySelector('jf-context-menu') as Mounted | null;
    await menu?.updateComplete;
    const retry = [...(menu?.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.item') ?? [])].find(
      (b) => (b.textContent ?? '').includes(BRANCH_MENU_RETRY),
    );
    expect(retry?.textContent).toContain('Uses the same 2 files');
    retry?.click();
    await settle(el);
    expect(backend.branches).toHaveLength(1);
    // The Ask and its Retry each send exactly the two chosen files, in whatever order.
    const sent = dispatches().map((s) => [...((s.body.docIds as string[] | undefined) ?? [])].sort());
    expect(sent).toEqual([
      ['docs/a.md', 'docs/b.md'],
      ['docs/a.md', 'docs/b.md'],
    ]);
    expect(ctrl.send).not.toHaveBeenCalled();
    restoreActiveConversation(original); await settle(el);
    expect((await fieldOf(el)).value).toBe('unrelated B');
  });

  it('DS1 known live chosen-file Ask Edit forks its own exact inputs while unrelated review-required B remains recoverable', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md'), wireHit('b', 'docs/b.md'), wireHit('decoy', 'docs/decoy.md')];
    aiOnline(); const el = await mount(); await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true }); await clickRow(el, 'b.md', { ctrlKey: true });
    await askAboutChosen(el); await askNow(el, 'original chosen question'); await answerLatest(el);
    const original = el.sessions.activeId;
    await chooseTier(el, 'delegate'); await type(el, 'unrelated B');
    const { createNavigationHandler } = await import('../../router/navigationHandler.js');
    await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle(
      { kind: 'navigate', target: 'core.search-v3-surface', state: { query: 'old journal A' } },
      { push: false, taskOrigin: 'restore' });
    await settle(el);
    expect((await fieldOf(el)).value).toBe('unrelated B');
    expect(el.shadowRoot!.querySelector('[data-testid=sv3-draft-review]')).not.toBeNull();
    const main = await region(el, 'jf-sv3-main');
    const turn = main.shadowRoot!.querySelector('[data-testid=sv3-turn]')!;
    await press(turn.querySelector('[data-testid=sv3-turn-edit]')); await settle(el);
    const editor = main.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid=sv3-turn-edit-input]');
    expect(editor).not.toBeNull(); editor!.value = 'exact rewritten chosen question';
    editor!.dispatchEvent(new Event('input')); await settle(el);
    await press(main.shadowRoot!.querySelector('[data-testid=sv3-turn-edit-send]')); await settle(el);
    expect(backend.branches).toHaveLength(1); expect(backend.branches[0]!.parent).toBe(original);
    expect(backend.branches[0]!.fromMsgId).toEqual(expect.any(String));
    expect(backend.streams).toHaveLength(2);
    expect(backend.streams[1]!.body).toMatchObject({ shapeId: 'core.rag-ask', question: 'exact rewritten chosen question',
      conversationId: `${original}-branch-${backend.minted}`, allowLoad: true });
    expect([...(backend.streams[1]!.body.docIds as string[])].sort()).toEqual(['docs/a.md', 'docs/b.md']);
    expect(ctrl.send).not.toHaveBeenCalled();
    await answerLatest(el); restoreActiveConversation(original); await settle(el);
    expect((await fieldOf(el)).value).toBe('unrelated B');
    expect(backend.streams).toHaveLength(2);
  });

  it('DS1 after reload Retry/Edit say the original Ask inputs are unknown', async () => {
    recordTurn('uc-old', 'when does it end?', 'In May.');
    aiOnline();
    const el = await mount();
    await settle(el);
    await openFromSidebar(el);
    const main = await region(el, 'jf-sv3-main');
    const turn = main.shadowRoot?.querySelector('[data-testid="sv3-turn"]');
    expect(turn).not.toBeNull();
    expect(turn?.querySelector('[data-testid="sv3-turn-scope"]')).toBeNull();
    await press(turn?.querySelector('[data-testid="sv3-turn-edit"]'));
    await settle(el);
    expect(q(main, 'sv3-turn-edit-scope')?.textContent).toContain('Original Ask inputs are unknown');
  });
});

/* ── S1.8 — a search beside a conversation ────────────────────────────────────────────────────── */

describe('S1.8 — a search shows results and keeps the conversation one click away', () => {
  it('leaves a streaming answer streaming, in the sidebar, and reopens it with one click', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'ask');
    await askNow(el, 'what is due?');
    expect(dispatches()).toHaveLength(1);
    const conversationId = el.sessions.activeId;
    expect(conversationId).not.toBeNull();

    await chooseTier(el, 'search');
    await searchFor(el, 'lease');
    expect(await rowTitles(el)).toEqual(['a.md']);
    expect(dispatches()[0]?.signal?.aborted).toBe(false);
    expect(el.sessions.sessions.map((s) => s.id)).toContain(conversationId);

    await openFromSidebar(el);
    expect(await results(el)).toBeNull();
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-turn"]')).not.toBeNull();
    // Opening a conversation is where you ask: Enter must not search away from it.
    expect(el.tier).toBe('ask');
  });

  it('never steers a live delegated run', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'delegate');
    await askNow(el, 'tidy the folder');
    expect(ctrl.send).toHaveBeenCalledTimes(1);
    ctrl.runKind = 'agent';
    ctrl.runInFlight = true;
    ctrl.isStreaming = true;
    el.requestUpdate();
    await settle(el);

    await chooseTier(el, 'search');
    await searchFor(el, 'lease');
    expect(ctrl.steer).not.toHaveBeenCalled();
    expect(ctrl.cancelSession).not.toHaveBeenCalled();
    expect(backend.searches.at(-1)?.query).toBe('lease');
    expect(await rowTitles(el)).toEqual(['a.md']);
  });
});

/* ── S1.9 — loading, nothing, failure; never an older query's rows ───────────────────────────── */

describe('S1.9 — an older query’s results are never shown as the current ones', () => {
  it('stops showing the previous rows the moment the query changes', async () => {
    vi.useFakeTimers();
    backend.corpus.alpha = [wireHit('a', 'docs/alpha.md')];
    aiOnline();
    const el = await mount();
    await type(el, 'alpha');
    await pressEnter(el);
    await passTime(el, 50);
    expect(await rowTitles(el)).toEqual(['alpha.md']);
    await type(el, 'beta');
    expect(await rowTitles(el)).toEqual([]);
    const host = await results(el);
    expect(q(host as Element, 'sv3-results-loading')).not.toBeNull();
  });

  it('shows nothing for a cleared query, even when the earlier request answers late', async () => {
    backend.corpus.alpha = [wireHit('a', 'docs/alpha.md')];
    backend.holdSearches = true;
    aiOnline();
    const el = await mount();
    await type(el, 'alpha');
    await pressEnter(el);
    await settle(el);
    await type(el, '');
    for (const release of backend.held) release();
    await settle(el);
    expect(await rowTitles(el)).toEqual([]);
  });

  it('says "no documents matched" and "search failed" in different words', async () => {
    aiOnline();
    const el = await mount();
    await searchFor(el, 'zzzz');
    const host = await results(el);
    // `jf-sv3-empty` renders its heading in its own shadow root; the heading is what is read out.
    const said = (node: Element | null): string =>
      `${node?.getAttribute('heading') ?? ''} ${node?.textContent ?? ''}`;
    const empty = q(host as Element, 'sv3-results-empty');
    expect(said(empty)).toMatch(/No documents matched/i);

    fetchMock.mockImplementationOnce(async () => {
      throw new Error('Failed to fetch');
    });
    await searchFor(el, 'yyyy');
    const failed = await results(el);
    const failure = q(failed as Element, 'sv3-results-failed');
    expect(said(failure)).toMatch(/Search could not be completed/i);
    expect(said(failure)).toContain('Search could not be completed. Open Health');
    expect(failure?.getAttribute('role')).toBe('alert');
    expect(q(failed as Element, 'sv3-results-empty')).toBeNull();
  });
});

/* ── S1.10 — shared filters are visible and removable ─────────────────────────────────────────── */

describe('S1.10 — nothing restricts results invisibly', () => {
  it('shows a scope chip the default window pinned, and removing it re-runs the search unrestricted', async () => {
    addScopeChip({ kind: 'file', label: 'a.md', docIds: ['docs/a.md'] });
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    expect(backend.searches.at(-1)?.filters).toEqual({ docIds: ['docs/a.md'] });
    const host = await results(el);
    const chip = q(host as Element, 'sv3-results-filters');
    expect(chip?.textContent).toContain('a.md');
    const remove = chip?.querySelector<HTMLButtonElement>('[data-filter="scope-0"]');
    remove?.click();
    await settle(el);
    expect(getScopeChips()).toEqual([]);
    expect(backend.searches.at(-1)?.filters).toBeUndefined();
  });

  it('keeps a facet filter removable when it leaves zero hits', async () => {
    toggleFacetValue('file_kind', 'pdf');
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    const host = await results(el);
    const filters = q(host as Element, 'sv3-results-filters');
    expect(filters?.textContent).toContain('pdf');
    filters?.querySelector<HTMLButtonElement>('[data-filter="facet-file_kind-pdf"]')?.click();
    await settle(el);
    expect(getFacetSelections()).toEqual({});
    expect(backend.searches.at(-1)?.filters).toBeUndefined();
  });

  it('shows a date range set elsewhere, and clears it', async () => {
    setFilterRange(Date.parse('2026-01-01'), undefined);
    aiOnline();
    const el = await mount();
    await searchFor(el, 'lease');
    const host = await results(el);
    const filters = q(host as Element, 'sv3-results-filters');
    expect(filters?.textContent).toMatch(/Modified/i);
    filters?.querySelector<HTMLButtonElement>('[data-filter="date"]')?.click();
    await settle(el);
    expect(getFilters()).toEqual({});
    expect(backend.searches.at(-1)?.filters).toBeUndefined();
  });
});

/* ── S1.10 — the query shared with the default window ──────────────────────────────────────── */

/** The default window searching: the same shared store, while this window is not on screen. */
async function searchInOtherWindow(query: string): Promise<void> {
  setQuery(query);
  submitSearch();
  for (let turn = 0; turn < 12; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
}

async function reenter(el: Mounted): Promise<void> {
  document.body.appendChild(el);
  await settle(el);
}

async function headingOf(el: Mounted): Promise<string | null> {
  const host = await results(el);
  return host === null ? null : (q(host, 'sv3-results-heading')?.textContent ?? null);
}

describe('S1.10 — the composer, the heading and the rows describe the one shared query', () => {
  it('follows a search made in the other window when the reader comes back', async () => {
    backend.corpus.amber = [wireHit('a', 'docs/a.md')];
    backend.corpus.cobalt = [wireHit('c', 'docs/c.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'amber');
    expect(await rowTitles(el)).toEqual(['a.md']);

    el.remove();
    await searchInOtherWindow('cobalt');
    await reenter(el);

    expect((await fieldOf(el)).value).toBe('cobalt');
    expect(await headingOf(el)).toBe('Results for “cobalt”');
    expect(await rowTitles(el)).toEqual(['c.md']);
  });

  it('shows the shared query on a first entry, in the composer and in the list', async () => {
    backend.corpus.cobalt = [wireHit('c', 'docs/c.md')];
    aiOnline();
    await searchInOtherWindow('cobalt');
    const searched = backend.searches.length;
    const el = await mount();
    await settle(el);

    expect(el.tier).toBe('search');
    expect((await fieldOf(el)).value).toBe('cobalt');
    expect(await headingOf(el)).toBe('Results for “cobalt”');
    expect(await rowTitles(el)).toEqual(['c.md']);
    // Showing the shared list is not running a search of its own.
    expect(backend.searches.length).toBe(searched);
  });

  it('returns to the list with the shared query after a conversation, too', async () => {
    recordTurn('uc-old', 'who is the landlord?', 'Acme.');
    backend.corpus.amber = [wireHit('a', 'docs/a.md')];
    backend.corpus.cobalt = [wireHit('c', 'docs/c.md')];
    aiOnline();
    const el = await mount();
    await settle(el);
    await searchFor(el, 'amber');
    restoreActiveConversation('uc-old');
    await settle(el);
    await type(el, 'a follow-up I am drafting');

    el.remove();
    await searchInOtherWindow('cobalt');
    await reenter(el);
    // Arriving back in the conversation leaves it, and its draft, alone.
    expect(el.sessions.activeId).toBe('uc-old');
    expect((await fieldOf(el)).value).toBe('a follow-up I am drafting');

    restoreActiveConversation(null);
    await settle(el);
    expect(el.tier).toBe('search');
    expect((await fieldOf(el)).value).toBe('cobalt');
    expect(await headingOf(el)).toBe('Results for “cobalt”');
    expect(await rowTitles(el)).toEqual(['c.md']);
  });

  it('never overwrites an unsent Ask draft; Search mode then searches what the composer holds', async () => {
    backend.corpus.amber = [wireHit('a', 'docs/a.md')];
    backend.corpus.cobalt = [wireHit('c', 'docs/c.md')];
    backend.corpus['what is the rent?'] = [wireHit('r', 'docs/rent.md')];
    aiOnline();
    const el = await mount();
    await searchFor(el, 'amber');
    await chooseTier(el, 'ask');
    await type(el, 'what is the rent?');

    el.remove();
    await searchInOtherWindow('cobalt');
    await reenter(el);
    expect(el.tier).toBe('ask');
    expect((await fieldOf(el)).value).toBe('what is the rent?');
    expect(dispatches()).toEqual([]);

    // Back in Search mode, the list is the search for the text the composer holds.
    await chooseTier(el, 'search');
    await settle(el);
    expect((await fieldOf(el)).value).toBe('what is the rent?');
    expect(await headingOf(el)).toBe('Results for “what is the rent?”');
    // The staged quick pass, once its debounce has run.
    await vi.waitFor(async () => expect(await rowTitles(el)).toEqual(['rent.md']));
  });

  it('keeps a restored draft on a first entry and shows no list it would contradict', async () => {
    localStorage.setItem(__draftStorageKey(SV3_DRAFT_KEY), 'northfield lease');
    backend.corpus.cobalt = [wireHit('c', 'docs/c.md')];
    aiOnline();
    await searchInOtherWindow('cobalt');
    const el = await mount();
    await settle(el);
    expect((await fieldOf(el)).value).toBe('northfield lease');
    expect(await results(el)).toBeNull();
  });
});

/* ── S1.11 — the palette searches through Search mode ─────────────────────────────────────────── */

describe('S1.11 — the palette’s search uses Search mode', () => {
  it('has no developer "Search this text" entry, and "Search everything" shows results', async () => {
    backend.corpus.lease = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await chooseTier(el, 'ask');
    await type(el, 'lease');
    const palette = el.shadowRoot?.querySelector('jf-sv3-palette') as
      | (Mounted & { show(i: HTMLElement | null): Promise<void> })
      | null;
    await palette?.show(null);
    await palette?.updateComplete;
    expect(palette?.shadowRoot?.querySelector('#sv3-palette-item-cmd-search-text')).toBeNull();
    palette?.shadowRoot?.querySelector<HTMLElement>('#sv3-palette-item-cmd-everything')?.click();
    await settle(el);
    expect(el.tier).toBe('search');
    expect(await rowTitles(el)).toEqual(['a.md']);
  });
});

/* ── S1.13 — Back/Forward keep each place's own state ─────────────────────────────────────────── */

describe('S1.13 — Back and Forward between results and a conversation', () => {
  it('keeps the results, the query and the chosen scope with the results, and none in the conversation', async () => {
    recordTurn('uc-old', 'who is the landlord?', 'Acme.');
    backend.corpus.lease = [wireHit('a', 'docs/a.md')];
    aiOnline();
    const el = await mount();
    await settle(el);
    restoreActiveConversation('uc-old');
    await settle(el);
    expect(el.tier).toBe('ask');
    await type(el, 'a follow-up I am drafting');

    await chooseTier(el, 'search');
    await searchFor(el, 'lease');
    await clickRow(el, 'a.md', { ctrlKey: true });
    await askAboutChosen(el);
    expect(el.sessions.activeId).toBeNull();

    // Back: the conversation, with its own draft and without the results' scope.
    restoreActiveConversation('uc-old');
    await settle(el);
    expect(await results(el)).toBeNull();
    expect((await fieldOf(el)).value).toBe('a follow-up I am drafting');
    let composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-scope')).toBeNull();

    // Forward: the results again, with their query and the scope that was chosen there.
    restoreActiveConversation(null);
    await settle(el);
    expect(await rowTitles(el)).toEqual(['a.md']);
    expect((await fieldOf(el)).value).toBe('lease');
    composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-scope')?.textContent).toContain('a.md');
  });
});


// DS1: raw persistence has no task provenance; requests are observed at the real shared-store seam.
describe('DS1 ambiguous restored draft ownership', () => {
  it.each(['search-v3.composer', 'unified-chat.composer'])('does not issue an unscoped Enter from restored %s', async (key) => {
    aiOnline();
    localStorage.setItem('justsearch.draft.' + key, 'Boundary needle');
    const el = await mount(); await settle(el);
    expect((await fieldOf(el)).value).toBe('Boundary needle');
    await pressEnter(el); await settle(el);
    expect(backend.searches).toHaveLength(0);
    expect(backend.streams).toHaveLength(0);
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('does not stage an edited restored draft through the typing debounce', async () => {
    aiOnline();
    localStorage.setItem('justsearch.draft.search-v3.composer', 'Boundary needle');
    const el = await mount(); await settle(el);
    vi.useFakeTimers();
    await type(el, 'Boundary needle edited');
    await passTime(el, 1500);
    expect(backend.searches).toHaveLength(0);
    expect((await fieldOf(el)).value).toBe('Boundary needle edited');
  });
});

const ds1Attachment = 'justsearch.draft.attachment.v1.search-v3.main';
function ds1SavedProposal(text: string, preparation: unknown): void {
  sessionStorage.setItem(ds1Attachment, JSON.stringify({ version: 1, incarnation: 'saved-data', payload: {
    version: 2, task: null, drafts: [{ place: null, text, preparation }],
  } }));
}
function ds1Button(el: Mounted, id: string): HTMLButtonElement {
  const button = el.shadowRoot?.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
  if (!button) throw new Error(`missing ${id}`);
  return button;
}

describe('DS1 review establishes current intent without issuing it', () => {
  it.each(['same bytes A', 'newer raw B'])('keeps typed A separate from %s until current review', async raw => {
    aiOnline(); ds1SavedProposal('same bytes A', { kind: 'ask', scope: { kind: 'files', paths: ['docs/alpha.md'] } });
    localStorage.setItem('justsearch.draft.search-v3.composer', raw);
    const el = await mount(); await settle(el);
    expect((await fieldOf(el)).value).toBe(raw);
    expect(el.shadowRoot!.textContent).toContain('docs/alpha.md');
    for (const opts of [{}, { ctrlKey: true }]) await pressEnter(el, opts);
    await chooseTier(el, 'delegate'); await pressEnter(el);
    expect(backend.searches).toHaveLength(0); expect(backend.streams).toHaveLength(0); expect(ctrl.send).not.toHaveBeenCalled();
    // A mode click configures a proposal, and never confirms it. Pick the retained Ask data explicitly.
    ds1Button(el, 'sv3-recovery-review-copy').click(); await settle(el);
    expect((await fieldOf(el)).value).toBe('same bytes A');
    ds1Button(el, 'sv3-draft-review-confirm').click(); await settle(el);
    expect(backend.searches).toHaveLength(0); expect(backend.streams).toHaveLength(0);
    await type(el, 'reviewed live edit'); await pressEnter(el); await settle(el);
    expect(backend.streams).toHaveLength(1);
    expect(backend.streams[0]!.body).toMatchObject({ shapeId: 'core.rag-ask', question: 'reviewed live edit', docIds: ['docs/alpha.md'] });
    if (raw !== 'same bytes A') expect(sessionStorage.getItem(ds1Attachment)).toContain(raw);
  });
  it('explicit Search uses current effective filters and starts only on the next deliberate operation', async () => {
    aiOnline(); addScopeChip({ kind: 'file', label: 'current', docIds: ['docs/current'] });
    ds1SavedProposal('old A', { kind: 'ask', scope: { kind: 'files', paths: ['docs/alpha.md'] } });
    localStorage.setItem('justsearch.draft.search-v3.composer', 'newer B');
    const el = await mount(); await settle(el); const filters = [...getScopeChips()];
    ds1Button(el, 'sv3-draft-review-search').click(); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-testid=sv3-draft-review]'), el.shadowRoot!.textContent ?? '').toBeNull();
    expect(getScopeChips()).toEqual(filters); expect(backend.searches).toHaveLength(0);
    expect((await fieldOf(el)).value).toBe('newer B');
    await pressEnter(el); await settle(el);
    expect(backend.searches, el.shadowRoot!.textContent ?? '').toHaveLength(1); expect(backend.streams).toHaveLength(0);
    expect(JSON.stringify(backend.searches[0])).toContain('docs/current');
  });
  it('an independent shared search C runs while restored B stays in its composer', async () => {
    aiOnline(); localStorage.setItem('justsearch.draft.search-v3.composer', 'ambiguous B');
    const el = await mount(); await settle(el);
    await searchInOtherWindow('independent C'); await settle(el);
    expect(backend.searches.length).toBeGreaterThan(0);
    expect((await fieldOf(el)).value).toBe('ambiguous B');
    await pressEnter(el); await settle(el);
    expect(backend.searches.every(body => !JSON.stringify(body).includes('ambiguous B'))).toBe(true);
  });
  it('real chosen-row preparation survives same-tab reload as data requiring explicit review', async () => {
    aiOnline(); backend.corpus.lease = [wireHit('a', 'docs/alpha.md')];
    const first = await mount(); await searchFor(first, 'lease'); await clickRow(first, 'alpha.md', { ctrlKey: true });
    await askAboutChosen(first); await type(first, 'Boundary needle');
    window.dispatchEvent(new Event('pagehide')); first.remove(); __resetDraftProvidersForTest();
    const el = await mount(); await settle(el);
    expect((await fieldOf(el)).value).toBe('Boundary needle');
    expect(el.shadowRoot!.textContent).toContain('docs/alpha.md');
    const before = backend.searches.length; await pressEnter(el); await settle(el);
    expect(backend.searches).toHaveLength(before); expect(backend.streams).toHaveLength(0);
    ds1Button(el, 'sv3-draft-review-confirm').click(); await settle(el);
    expect(backend.streams).toHaveLength(0);
    await pressEnter(el); await settle(el);
    expect(backend.streams[0]!.body).toMatchObject({ question: 'Boundary needle', docIds: ['docs/alpha.md'] });
  });
});

it('DS1 a known live unrestricted Ask still retries as Ask when the unrelated composer is Delegate', async () => {
  aiOnline(); const el = await mount(); await chooseTier(el, 'ask'); await askNow(el, 'known all-files A'); await answerLatest(el);
  await chooseTier(el, 'delegate'); await type(el, 'unrelated B');
  const original = el.sessions.activeId;
  const main = await region(el, 'jf-sv3-main');
  const turn = main.shadowRoot!.querySelector('[data-testid=sv3-turn]')!;
  await press(turn.querySelector('[data-testid=sv3-turn-context-menu]')); await settle(el);
  const menu = document.querySelector('jf-context-menu') as Mounted; await menu.updateComplete;
  const retry = [...menu.shadowRoot!.querySelectorAll<HTMLButtonElement>('button.item')].find(b => (b.textContent ?? '').includes(BRANCH_MENU_RETRY))!;
  expect(retry.textContent).toContain('Searches all your files'); retry.click(); await settle(el);
  expect(backend.streams).toHaveLength(2); expect(backend.streams[1]!.body.shapeId).toBe('core.rag-ask');
  expect((backend.streams[1]!.body.docIds as string[] | undefined) ?? []).toEqual([]); expect(ctrl.send).not.toHaveBeenCalled();
  restoreActiveConversation(original); await settle(el); expect((await fieldOf(el)).value).toBe('unrelated B');
});
