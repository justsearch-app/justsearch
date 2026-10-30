// @vitest-environment happy-dom

/**
 * The tempdoc-610 context set in Search v3 (852 S2) — the five acts, end to end.
 *
 * The backend is FAKE but it is a real one: the five endpoints below hold state, and every case
 * writes through the shared store functions and then reads the conversation's `/history` back. That
 * is what makes these round-trips rather than click-assertions — a window that patched its own copy
 * of the floor and never re-read it would pass a rendering test and fail every case here.
 *
 * The properties asserted as MECHANISMS:
 *  - **Every write is followed by a re-load of `/history`** (the obligation 852 S1 recorded when it
 *    loaded that record without rendering it). Counted per act, including the acts that FAILED —
 *    a partially-applied bulk exclusion is exactly when the window's own idea of the ledger is
 *    least trustworthy.
 *  - **The backend, not the window, decides what is on screen.** The fake serves the mutated record
 *    on the reload, and the assertions read the DOM after it lands.
 *  - **A turn that names no store message offers no affordance at all** — the agent-turn case, on
 *    the run plane's own id shapes.
 *
 * EVERY case here fails before this slice: the window imported none of the five store functions and
 * rendered no context affordance. Where a case pins something subtler than "the feature exists",
 * its own comment says what.
 */
import { assert, describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';

let ctrlExists = false;
const ctrl = {
  conversation: [] as unknown[],
  toolCalls: {},
  streamingText: '',
  isStreaming: false,
  runInFlight: false,
  runKind: 'agent' as const,
  conversationId: null as string | null,
  sessionId: null as string | null,
  iterationsUsed: 0,
  toolCallsExecuted: 0,
  budgetUpdates: [],
  // Tempdoc 859 §A — the live run feed derives its open-region item from the real controller.
  reasoning: new ReasoningController(() => {}),
  budgetGate: null,
  contextGate: null,
  reattachActiveRunOnLoad: vi.fn(async () => {}),
};

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
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import {
  __resetContextInspectorDrawer,
  getContextInspectorView,
  isContextInspectorOpen,
} from '../../state/contextInspectorDrawer.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import {
  CONTEXT_COMPACT_FAILED,
  CONTEXT_EXCLUDE_FAILED,
  CONTEXT_FLOOR_COMPACTED,
  CONTEXT_FLOOR_FAILED,
  CONTEXT_FLOOR_RESET,
  CONTEXT_MENU_COMPACT,
  CONTEXT_MENU_EXCLUDE,
  CONTEXT_MENU_INCLUDE,
  CONTEXT_MENU_RESET,
  CONTEXT_RESTORE_FAILED,
} from './fixtures.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };
type Updatable = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

interface HistoryRecord {
  sessionId: string;
  messages: Array<Record<string, unknown>>;
  contextFloor?: string;
  contextFloorSummary?: string;
  excludedMessageIds?: string[];
  excludedSourceIds?: string[];
}

interface Backend {
  conversations: Array<Record<string, unknown>>;
  threads: Record<string, { conversationId: string; events: unknown[] }>;
  histories: Record<string, HistoryRecord>;
  /** Every `/history` GET, in order — what the re-load assertions count. */
  historyReads: string[];
  /** Every context WRITE, in order: the method, the endpoint tail and the parsed body. */
  writes: Array<{ path: string; method: string; body: Record<string, unknown> }>;
  /** Which acts the fake refuses, so the failure paths are exercised against a real refusal. */
  refuse: Set<'floor' | 'clear' | 'compact' | 'summary' | 'exclude'>;
  /** Exclusion toggles in flight right now, and the HIGH-WATER MARK — the concurrency witness. */
  excludesInFlight: number;
  peakConcurrentExcludes: number;
  /** What a successful compaction returns — `null` makes it the "nothing came back" case. */
  compaction: string | null;
  /** Every ask dispatch's parsed body, in order — what the follow-up context cases read. */
  dispatches: Array<Record<string, unknown>>;
  /** Exclusion toggles the fake refuses for ONE message id only — a partially refused bulk undo. */
  refuseExcludeIds: Set<string>;
  /** `/history` reads that fail outright (500) for these conversations. */
  failHistoryFor: Set<string>;
  /** `/history` reads held until the case releases them — out-of-order and navigate-away cases. */
  holdHistoryFor: Set<string>;
  heldHistories: Map<string, () => void>;
  /** When set, exclusion toggles wait for this gate before they land. */
  excludeGate: Promise<void> | null;
}
let backend: Backend;

interface Stream {
  emit(event: string, data: unknown): void;
  end(): void;
}
let stream: Stream;

const storedId = (n: number): string => `11111111-2222-4333-8444-55555555555${n}`;

const conversationRow = (id: string, first: string): Record<string, unknown> => ({
  sessionId: id,
  createdAtMs: 1,
  lastActiveAtMs: 2,
  messageCount: 4,
  firstUserMessage: first,
  shapeId: 'core.rag-ask',
});

let clock = 0;
const wireEvent = (
  id: string,
  kind: string,
  content: string,
  attributes: Record<string, unknown> = {},
): Record<string, unknown> => ({
  id,
  occurredAt: new Date(Date.parse('2026-08-19T10:00:00Z') + clock++ * 1000).toISOString(),
  kind,
  originator: 'agent',
  content,
  attributes,
});

const bodyOf = (init: unknown): Record<string, unknown> =>
  JSON.parse(String((init as { body?: unknown })?.body ?? '{}')) as Record<string, unknown>;

function stubFetch(): void {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  stream = {
    emit: (event, data) =>
      push({
        done: false,
        value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
      }),
    end: () => push({ done: true }),
  };
  fetchMock.mockImplementation(async (url: unknown, init: { signal?: AbortSignal } = {}) => {
    const href = String(url);
    const method = String((init as { method?: string }).method ?? 'GET');
    if (href.includes('/api/chat/dispatch')) {
      backend.dispatches.push(bodyOf(init));
      const signal = init.signal ?? null;
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
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ runs: [] }) };
    }
    // ── The five tempdoc-610 endpoints, holding state ───────────────────────────────────────────
    const idIn = (tail: string): string =>
      decodeURIComponent(href.slice(0, -tail.length).split('/').pop() ?? '');
    if (href.endsWith('/context-floor/summary')) {
      const id = idIn('/context-floor/summary');
      const sent = bodyOf(init);
      backend.writes.push({ path: 'context-floor/summary', method, body: sent });
      if (backend.refuse.has('summary')) return { ok: false, status: 500 };
      const record = backend.histories[id];
      if (record !== undefined) record.contextFloorSummary = String(sent.summaryText ?? '');
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    if (href.endsWith('/context-floor')) {
      const id = idIn('/context-floor');
      const record = backend.histories[id];
      if (method === 'DELETE') {
        backend.writes.push({ path: 'context-floor', method, body: {} });
        if (backend.refuse.has('clear')) return { ok: false, status: 500 };
        if (record !== undefined) {
          delete record.contextFloor;
          delete record.contextFloorSummary;
        }
        return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
      }
      const sent = bodyOf(init);
      backend.writes.push({ path: 'context-floor', method, body: sent });
      if (backend.refuse.has('floor')) return { ok: false, status: 500 };
      if (record !== undefined) {
        record.contextFloor = String(sent.floorMessageId ?? '');
        // A plain rewind carries no summary, and the backend drops the one that was standing.
        delete record.contextFloorSummary;
      }
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    if (href.endsWith('/compact')) {
      const id = idIn('/compact');
      const sent = bodyOf(init);
      backend.writes.push({ path: 'compact', method, body: sent });
      if (backend.refuse.has('compact')) return { ok: false, status: 500 };
      const record = backend.histories[id];
      if (backend.compaction === null) {
        return { ok: true, status: 200, json: () => Promise.resolve({}) };
      }
      if (record !== undefined) {
        record.contextFloor = String(sent.floorMessageId ?? '');
        record.contextFloorSummary = backend.compaction;
      }
      return {
        ok: true,
        status: 200,
        json: () => Promise.resolve({ contextFloorSummary: backend.compaction }),
      };
    }
    if (href.includes('/messages/') && href.endsWith('/exclude')) {
      const messageId = decodeURIComponent(
        href.slice(href.indexOf('/messages/') + '/messages/'.length, -'/exclude'.length),
      );
      const id = decodeURIComponent(
        href.slice(0, href.indexOf('/messages/')).split('/').pop() ?? '',
      );
      const sent = bodyOf(init);
      backend.writes.push({ path: `exclude:${messageId}`, method, body: sent });
      if (backend.refuse.has('exclude')) return { ok: false, status: 500 };
      if (backend.refuseExcludeIds.has(messageId)) return { ok: false, status: 500 };
      if (backend.excludeGate !== null) await backend.excludeGate;
      // THE CONCURRENCY WITNESS. The real endpoint is a read-modify-write over one unlocked
      // `meta.json`, so two toggles overlapping is the bug; this fake cannot lose an update, so it
      // measures the overlap instead. The await is what makes the window real — without it the
      // handler would finish in one microtask and even a parallel caller would never overlap.
      backend.excludesInFlight += 1;
      backend.peakConcurrentExcludes = Math.max(
        backend.peakConcurrentExcludes,
        backend.excludesInFlight,
      );
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
      const record = backend.histories[id];
      if (record !== undefined) {
        const held = new Set(record.excludedMessageIds ?? []);
        if (sent.excluded === true) held.add(messageId);
        else held.delete(messageId);
        record.excludedMessageIds = [...held];
      }
      backend.excludesInFlight -= 1;
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/history')) {
      const id = idIn('/history');
      backend.historyReads.push(id);
      if (backend.failHistoryFor.has(id)) {
        return { ok: false, status: 500, json: () => Promise.resolve({}) };
      }
      const record = backend.histories[id];
      if (record === undefined) return { ok: false, status: 404, json: () => Promise.resolve({}) };
      // A COPY, so the window can never be reading the object the fake mutates in place — taken at
      // serve time, before any hold, as a real server answers with what it read when it handled it.
      const snapshot = { ...record };
      if (backend.holdHistoryFor.has(id)) {
        backend.holdHistoryFor.delete(id);
        await new Promise<void>((resolve) => backend.heldHistories.set(id, resolve));
      }
      return { ok: true, status: 200, json: () => Promise.resolve(snapshot) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/title')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    if (href.includes('/api/chat/conversations')) {
      return {
        ok: true,
        status: 200,
        json: () => Promise.resolve({ sessions: backend.conversations }),
      };
    }
    if (href.includes('/api/thread/')) {
      const id = decodeURIComponent(
        href.slice(href.lastIndexOf('/api/thread/') + '/api/thread/'.length),
      );
      const record = backend.threads[id];
      if (record === undefined) return { ok: false, status: 500 };
      return { ok: true, status: 200, json: () => Promise.resolve(record) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ results: [] }) };
  });
}

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true, llmContextTokens: 4096 } as never,
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
  __resetContextInspectorDrawer();
  ctrlExists = false;
  clock = 0;
  backend = {
    conversations: [],
    threads: {},
    histories: {},
    historyReads: [],
    writes: [],
    refuse: new Set(),
    excludesInFlight: 0,
    peakConcurrentExcludes: 0,
    compaction: 'The renewal thread, summarized.',
    dispatches: [],
    refuseExcludeIds: new Set(),
    failHistoryFor: new Set(),
    holdHistoryFor: new Set(),
    heldHistories: new Map(),
    excludeGate: null,
  };
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  for (const el of [...document.querySelectorAll('jf-context-menu')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  __resetContextInspectorDrawer();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 10; turn += 1) {
    if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
    else await new Promise<void>((r) => setTimeout(r, 0));
  }
  await el.updateComplete;
}

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  // WP1.1 — a fresh window starts in Search mode; these cases are about Ask, so they choose it.
  (el as unknown as { tier: string }).tier = 'ask';
  await el.updateComplete;
  await settle(el);
  return el;
}

async function region(el: Mounted, tag: string): Promise<Updatable> {
  const found = el.shadowRoot?.querySelector(tag) as Updatable | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

/** The two-turn conversation every case works on: both turns are store-minted on both halves. */
function twoTurnConversation(id = 'uc-ctx', opening = 'why did the renewal fail?'): void {
  backend.conversations = [conversationRow(id, opening)];
  backend.threads[id] = {
    conversationId: id,
    events: [
      wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?'),
      wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.'),
      wireEvent(storedId(3), 'USER_MESSAGE', 'and the second one?'),
      wireEvent(storedId(4), 'ASSISTANT_MESSAGE', 'The same lock.'),
    ],
  };
  backend.histories[id] = {
    sessionId: id,
    messages: [
      { role: 'user', content: 'why did the renewal fail?', id: storedId(0) },
      { role: 'assistant', content: 'The lock held.', id: storedId(1) },
      { role: 'user', content: 'and the second one?', id: storedId(3) },
      { role: 'assistant', content: 'The same lock.', id: storedId(4) },
    ],
  };
}

async function openTheOnlyConversation(el: Mounted): Promise<void> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  (sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable | null)?.shadowRoot
    ?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')
    ?.click();
  await settle(el);
}

const turnsIn = (main: Updatable): HTMLElement[] =>
  [...(main.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-turn"]') ?? [])];

/** Press a `jf-control` the way a reader does: through the native button inside it. */
async function press(host: Element | null | undefined): Promise<void> {
  // An assertion, not a throw: a control the scenario needs and the window does not offer is the
  // failure under test.
  assert(host, 'press: no control');
  await (host as Updatable).updateComplete;
  const button = host.shadowRoot?.querySelector('button');
  if (!button) throw new Error('press: the control rendered no button');
  (button as HTMLButtonElement).click();
}

/** Open a turn's ⋯ menu and choose an entry by its label. */
async function chooseFromTurnMenu(el: Mounted, index: number, label: string): Promise<void> {
  const main = await region(el, 'jf-sv3-main');
  const trigger = turnsIn(main)[index]?.querySelector('[data-testid="sv3-turn-context-menu"]');
  await press(trigger);
  await settle(el);
  const menu = document.querySelector('jf-context-menu') as Updatable | null;
  if (!menu) throw new Error('no context menu opened');
  await menu.updateComplete;
  const item = [...(menu.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.item') ?? [])].find(
    (b) => (b.textContent ?? '').includes(label),
  );
  assert(item, `no menu entry ${label}`);
  item.click();
  await settle(el);
}

/** Delay one response AFTER the stateful endpoint has saved its write, as on the real HTTP race. */
function holdWriteResponse(tail: string, method = 'POST', phase: 'request' | 'response' = 'response'): () => void {
  const serve = fetchMock.getMockImplementation() as (
    url: unknown, init: { method?: string },
  ) => Promise<unknown>;
  let release!: () => void;
  const held = new Promise<void>((resolve) => (release = resolve));
  let claimed = false;
  fetchMock.mockImplementation(async (url: unknown, init: { method?: string } = {}) => {
    const hold = !claimed && String(url).endsWith(tail) && init.method === method;
    if (hold) claimed = true;
    if (hold && phase === 'request') await held;
    const response: unknown = await serve(url, init);
    if (hold && phase === 'response') await held;
    return response;
  });
  return release;
}

/** A context control's public event; queued actions keep their original target turn. */
function contextAction(el: Mounted, action: string, index = 0, text?: string): void {
  const main = el.shadowRoot!.querySelector('jf-sv3-main') as Updatable;
  main.dispatchEvent(new CustomEvent('sv3-context-action', {
    detail: { action, turnId: turnsIn(main)[index]?.querySelector('[data-turn-id]')?.getAttribute('data-turn-id'), text },
    bubbles: true,
    composed: true,
  }));
}

async function contextNotice(el: Mounted): Promise<string> {
  const bar = await region(el, 'jf-sv3-context-bar');
  return bar.shadowRoot?.querySelector('[role="status"]')?.textContent?.trim() ?? '';
}

/** RR15: visibly retire a known unissued question before a DIFFERENT submission. */
async function discardRetainedQuestion(el: Mounted): Promise<void> {
  const control = el.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid=sv3-residency-discard]');
  expect(control).not.toBeNull();
  control!.click();
  await settle(el);
}

/** Read the next prompt, then finish its stream so no work survives the case. */
async function nextContext(el: Mounted): Promise<unknown> {
  await ask(el, 'and the third?');
  const context = backend.dispatches.at(-1)?.context;
  stream.emit('done', {});
  stream.end();
  await settle(el);
  return context;
}

const toasts = (): string[] => {
  const seen: string[] = [];
  document.addEventListener(EPHEMERAL_TOAST_EVENT, (e) => {
    seen.push(String((e as CustomEvent<{ message?: string }>).detail?.message ?? ''));
  });
  return seen;
};

/* ── The floor: set, render, restore ─────────────────────────────────────────────────────────── */

describe('the effective-context FLOOR round-trips against the store', () => {
  it('sets the floor on the turn’s own question, re-reads /history, and dims what is above', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    const readsBefore = backend.historyReads.length;

    await chooseFromTurnMenu(el, 1, CONTEXT_MENU_RESET);

    // The WRITE names the second turn's USER message: "reset to here" starts the next prompt at
    // this turn's question, so the turn the reader pointed at is still in context.
    expect(backend.writes).toEqual([
      { path: 'context-floor', method: 'POST', body: { floorMessageId: storedId(3) } },
    ]);
    // The RE-LOAD (852 S1's obligation on this slice). Without it the window would be rendering its
    // own guess at a floor the backend may never have accepted.
    expect(backend.historyReads.length).toBeGreaterThan(readsBefore);
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(storedId(3));

    const main = await region(el, 'jf-sv3-main');
    const turns = turnsIn(main);
    expect(turns[0]?.hasAttribute('data-out-of-context')).toBe(true);
    expect(turns[1]?.hasAttribute('data-out-of-context')).toBe(false);
    const divider = main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-label"]');
    expect(divider?.textContent?.trim()).toBe(CONTEXT_FLOOR_RESET);
    // The divider sits ABOVE the floor turn — the boundary is drawn where it actually is.
    expect(divider?.closest('.context-floor')?.nextElementSibling).toBe(turns[1]);
  });

  it('keeps the divider’s controls REACHABLE — separator is on the hairline, not the row', async () => {
    // `role="separator"` is children-presentational: a conforming screen reader prunes everything
    // inside the node carrying it. On the control row that would hide Restore — the only way back
    // from a floor — from assistive tech entirely, while every gate stayed green (no gate models
    // role inheritance). So the role lives on an empty rule and the row is a labelled group.
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    const el = await mount();
    await openTheOnlyConversation(el);
    const main = await region(el, 'jf-sv3-main');

    const separators = [...(main.shadowRoot?.querySelectorAll('[role="separator"]') ?? [])];
    expect(separators).toHaveLength(1);
    expect(separators[0]?.querySelector('jf-control')).toBeNull();
    const restore = main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-restore"]');
    expect(restore).not.toBeNull();
    expect(restore?.closest('[role="separator"]')).toBeNull();
    const group = restore?.closest('[role="group"]');
    expect(group).not.toBeNull();
    expect(group?.getAttribute('aria-label')).toBe('Effective context');
  });

  it('restores the full context from the divider’s own control', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    const el = await mount();
    await openTheOnlyConversation(el);
    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.hasAttribute('data-out-of-context')).toBe(true);

    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-restore"]'));
    await settle(el);

    expect(backend.writes).toEqual([{ path: 'context-floor', method: 'DELETE', body: {} }]);
    expect(backend.histories['uc-ctx']?.contextFloor).toBeUndefined();
    const after = await region(el, 'jf-sv3-main');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-floor"]')).toBeNull();
    expect(turnsIn(after)[0]?.hasAttribute('data-out-of-context')).toBe(false);
  });

  it('re-reads /history even when the write was REFUSED, and says which act failed', async () => {
    // The window's copy is least trustworthy exactly when a write did not land, so the reload is
    // unconditional; and the toast names the act, not "something went wrong".
    twoTurnConversation();
    backend.refuse.add('floor');
    const el = await mount();
    await openTheOnlyConversation(el);
    const seen = toasts();
    const readsBefore = backend.historyReads.length;

    await chooseFromTurnMenu(el, 1, CONTEXT_MENU_RESET);

    expect(backend.historyReads.length).toBeGreaterThan(readsBefore);
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBeUndefined();
    expect(seen).toContain(CONTEXT_FLOOR_FAILED);
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor"]')).toBeNull();
  });
});

/* ── Compaction and the summary the reader may correct ───────────────────────────────────────── */

describe('COMPACTION and its summary', () => {
  it('compacts up to a turn, then discloses and EDITS the summary in place', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);

    await chooseFromTurnMenu(el, 1, CONTEXT_MENU_COMPACT);
    expect(backend.writes[0]).toEqual({
      path: 'compact',
      method: 'POST',
      body: { floorMessageId: storedId(3) },
    });
    expect(el.sessions.sessions[0]?.history?.contextFloorSummary).toBe(
      'The renewal thread, summarized.',
    );

    let main = await region(el, 'jf-sv3-main');
    // A compacted floor says something DIFFERENT from a plain rewind — the turns above were kept as
    // a summary, not dropped, and the line the reader sees has to distinguish the two.
    expect(
      main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-label"]')?.textContent?.trim(),
    ).toBe(CONTEXT_FLOOR_COMPACTED);
    // The summary is DISCLOSED, not resting: it is a paragraph of the model's words.
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary"]')).toBeNull();

    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-toggle"]'));
    await settle(el);
    main = await region(el, 'jf-sv3-main');
    expect(
      main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary"]')?.textContent,
    ).toContain('The renewal thread, summarized.');

    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-edit"]'));
    await settle(el);
    main = await region(el, 'jf-sv3-main');
    const input = main.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-context-floor-summary-input"]',
    );
    expect(input?.value).toBe('The renewal thread, summarized.');
    input!.value = 'It was the lease lock, twice.';
    input!.dispatchEvent(new Event('input'));
    await settle(el);
    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-save"]'));
    await settle(el);

    expect(backend.writes.at(-1)).toEqual({
      path: 'context-floor/summary',
      method: 'POST',
      body: { summaryText: 'It was the lease lock, twice.' },
    });
    // The corrected text is read BACK from the store, not kept locally: the floor is unchanged and
    // only the stored summary was replaced.
    expect(backend.histories['uc-ctx']?.contextFloorSummary).toBe('It was the lease lock, twice.');
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(storedId(3));
    expect(el.sessions.sessions[0]?.history?.contextFloorSummary).toBe(
      'It was the lease lock, twice.',
    );
  });

  it('keeps a REFUSED summary edit open, with the reader’s correction still in it', async () => {
    // The one state in which the text is hardest to reproduce is the one where a naive editor
    // discards it: the write failed, so the editor answers to the STORE (it closes when the saved
    // text comes back) rather than to the press.
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    backend.histories['uc-ctx']!.contextFloorSummary = 'The renewal thread, summarized.';
    backend.refuse.add('summary');
    const el = await mount();
    await openTheOnlyConversation(el);
    const seen = toasts();
    let main = await region(el, 'jf-sv3-main');
    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-toggle"]'));
    await settle(el);
    main = await region(el, 'jf-sv3-main');
    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-edit"]'));
    await settle(el);
    main = await region(el, 'jf-sv3-main');
    const input = main.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-context-floor-summary-input"]',
    );
    input!.value = 'It was the lease lock, twice.';
    input!.dispatchEvent(new Event('input'));
    await settle(el);
    await press(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-save"]'));
    await settle(el);

    expect(seen).toContain('The summary could not be saved');
    main = await region(el, 'jf-sv3-main');
    const still = main.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-context-floor-summary-input"]',
    );
    expect(still).not.toBeNull();
    expect(still?.value).toBe('It was the lease lock, twice.');
    // ...and the stored summary is untouched, so nothing on screen claims the edit landed.
    expect(backend.histories['uc-ctx']?.contextFloorSummary).toBe('The renewal thread, summarized.');
  });

  it('says the summarization did not happen when the compaction came back empty', async () => {
    // The endpoint answered 200 with no summary — a success by HTTP and a failure by meaning. The
    // window reports the ACT, and words nothing about the model's availability (that has one
    // vocabulary in this product and it is not this one).
    twoTurnConversation();
    backend.compaction = null;
    const el = await mount();
    await openTheOnlyConversation(el);
    const seen = toasts();

    await chooseFromTurnMenu(el, 1, CONTEXT_MENU_COMPACT);

    expect(seen).toContain(CONTEXT_COMPACT_FAILED);
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-context-floor"]')).toBeNull();
  });
});

/* ── Per-turn exclusion, and the aggregate that makes it visible ─────────────────────────────── */

describe('hiding a TURN from the next prompt', () => {
  it('excludes both of the turn’s messages, dims it, and counts it in the bar', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);

    await chooseFromTurnMenu(el, 0, CONTEXT_MENU_EXCLUDE);

    // BOTH halves, and IN ORDER — the assertion is unsorted on purpose. The endpoint's write is a
    // read-modify-write over one shared `meta.json` and the store takes no lock
    // (`FileConversationStore.toggleStringInMeta:503-527`), so two toggles in flight together race
    // on one snapshot and the loser's id is dropped — leaving the turn HALF excluded, which the
    // transcript cannot show (`hasExcluded` is true on one id as on two). The fake backend cannot
    // lose an update, so only the ORDER of the writes can witness the serialization here.
    expect(backend.writes.map((w) => w.path)).toEqual([
      `exclude:${storedId(0)}`,
      `exclude:${storedId(1)}`,
    ]);
    expect(backend.writes.every((w) => w.body.excluded === true)).toBe(true);
    // ...and never OVERLAPPING. `Promise.all` would send both in the same tick and peak at 2.
    expect(backend.peakConcurrentExcludes).toBe(1);
    expect(new Set(backend.histories['uc-ctx']?.excludedMessageIds ?? [])).toEqual(
      new Set([storedId(0), storedId(1)]),
    );

    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.hasAttribute('data-excluded')).toBe(true);
    expect(turnsIn(main)[1]?.hasAttribute('data-excluded')).toBe(false);
    const bar = await region(el, 'jf-sv3-context-bar');
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')?.textContent,
    ).toContain('1 turn hidden from context');
  });

  it('offers INCLUDE for a turn already hidden, and puts it back', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), storedId(1)];
    const el = await mount();
    await openTheOnlyConversation(el);
    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.hasAttribute('data-excluded')).toBe(true);

    await chooseFromTurnMenu(el, 0, CONTEXT_MENU_INCLUDE);

    expect(backend.writes.every((w) => w.body.excluded === false)).toBe(true);
    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual([]);
    const after = await region(el, 'jf-sv3-main');
    expect(turnsIn(after)[0]?.hasAttribute('data-excluded')).toBe(false);
  });

  it('puts every hidden turn back in one act from the bar', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), storedId(4)];
    const el = await mount();
    await openTheOnlyConversation(el);
    const bar = await region(el, 'jf-sv3-context-bar');
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')?.textContent,
    ).toContain('2 turns hidden from context');

    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);

    expect(backend.writes.map((w) => w.path)).toEqual([
      `exclude:${storedId(0)}`,
      `exclude:${storedId(4)}`,
    ]);
    // The bulk undo is the WORST case for the unlocked read-modify-write — it can carry every id in
    // the conversation — so it is serialized on the same path and witnessed the same way.
    expect(backend.peakConcurrentExcludes).toBe(1);
    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual([]);
    const after = await region(el, 'jf-sv3-context-bar');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')).toBeNull();
  });
});

/* ── The honest null: a turn that names no store message ─────────────────────────────────────── */

describe('a turn the endpoints cannot address offers NO context affordance', () => {
  it('renders no ⋯ trigger on an agent turn, whose ids belong to the run plane', async () => {
    // `${runId}:user` and `${conversationId}:assistant:${stamp}` are minted by the agent-run
    // projection and stored as messages nowhere, so no floor or exclusion can name them. The turn
    // is rendered in full — only the acts that need a store id are withheld.
    backend.conversations = [conversationRow('uc-run', 'index the vendor folder')];
    backend.threads['uc-run'] = {
      conversationId: 'uc-run',
      events: [
        wireEvent('run-7:user', 'USER_MESSAGE', 'index the vendor folder'),
        wireEvent('uc-run:assistant:1755', 'ASSISTANT_MESSAGE', 'Indexed 42 files.'),
      ],
    };
    backend.histories['uc-run'] = { sessionId: 'uc-run', messages: [] };
    const el = await mount();
    await openTheOnlyConversation(el);

    const main = await region(el, 'jf-sv3-main');
    const turns = turnsIn(main);
    expect(turns).toHaveLength(1);
    expect(turns[0]?.querySelector('[data-testid="sv3-turn-question"]')?.textContent).toContain(
      'index the vendor folder',
    );
    expect(turns[0]?.querySelector('[data-testid="sv3-turn-context-menu"]')).toBeNull();
  });

  it('withholds the trigger on SETTLED turns too while the window is streaming', async () => {
    // The two gates have different scopes: the menu's entries are withheld window-wide while a
    // prompt is in flight, so a trigger gated only on THIS turn's status would render on every
    // settled turn during a stream and open nothing. That is the "control that fails when pressed"
    // the honest-null rule refuses — the same rule, one scope out.
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    let main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.querySelector('[data-testid="sv3-turn-context-menu"]')).not.toBeNull();

    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'and the third?';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await settle(el);

    main = await region(el, 'jf-sv3-main');
    const settled = turnsIn(main).filter(
      (t) => t.getAttribute('data-status') !== 'streaming',
    );
    expect(settled.length).toBeGreaterThan(0);
    for (const t of settled) {
      expect(t.querySelector('[data-testid="sv3-turn-context-menu"]')).toBeNull();
    }

    // ...and it comes back when the stream ends, so the withholding is the STATE, not a teardown.
    stream.emit('done', {});
    stream.end();
    await settle(el);
    main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.querySelector('[data-testid="sv3-turn-context-menu"]')).not.toBeNull();
  });

  it('renders no ⋯ trigger on a LIVE turn — its handle is positional until the record speaks', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'why did the renewal fail?';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await settle(el);
    stream.emit('chunk', { text: 'Half an ans' });
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.querySelector('[data-testid="sv3-turn-context-menu"]')).toBeNull();
    stream.emit('done', {});
    stream.end();
    await settle(el);
  });
});

/* ── The meter and the inspector ─────────────────────────────────────────────────────────────── */

describe('how full the context is, and what is in it', () => {
  it('keeps the previous occupancy when a later terminal omits usage and redraws against the current runtime window', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    await ask(el, 'Measured question');
    stream.emit('done', { promptTokens: 2048 });
    stream.end();
    await settle(el);
    await ask(el, 'Unmeasured question');
    stream.emit('done', {});
    stream.end();
    await settle(el);
    const bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]')?.textContent).toContain('2048 / 4096');
    __feedForTest({ inference: { mode: 'online', available: true, llmContextTokens: 8192 } as never });
    await settle(el);
    await bar.updateComplete;
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]')?.textContent).toContain('2048 / 8192');
    expect(bar.shadowRoot?.querySelector('[role="meter"]')?.getAttribute('aria-valuenow')).toBe('25');
  });

  it('renders no meter until a turn has reported an occupancy', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    const bar = await region(el, 'jf-sv3-context-bar');
    // A restored conversation was never measured by THIS window, and a confident 0% would be a
    // number the backend never sent.
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]')).toBeNull();
  });

  it('reads the occupancy off the turn’s own terminal and draws it against n_ctx', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'why did the renewal fail?';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await settle(el);
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {
      promptTokens: 2048,
      contextBreakdown: { system: 300, conversation: 1500, retrieved: 248 },
    });
    stream.end();
    await settle(el);

    const bar = await region(el, 'jf-sv3-context-bar');
    const meter = bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]');
    // 2048 of the 4096 the runtime reported = 50%, which is also the first rung the shared
    // fullness→colour authority calls `yellow`.
    expect(meter?.textContent).toContain('50%');
    expect(meter?.textContent).toContain('2048 / 4096');
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter-fill"]')?.getAttribute('data-color'),
    ).toBe('yellow');
    expect(bar.shadowRoot?.querySelector('[role="meter"]')?.getAttribute('aria-valuenow')).toBe('50');
  });

  it('leaves the meter behind when the reader claims another conversation', async () => {
    // The occupancy is a property of the conversation that spent it, so it is stored on the session
    // rather than on the window. A window-level reading would follow the reader into a conversation
    // whose prompt it never measured — a confident number about the wrong thing.
    // A DIFFERENT opening question, so the row this case claims is unambiguous: the ask below mints
    // its own conversation titled by what was asked.
    twoTurnConversation('uc-other', 'what did the lease say?');
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'why did the renewal fail?';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await settle(el);
    stream.emit('done', { promptTokens: 2048 });
    stream.end();
    await settle(el);
    let bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]')).not.toBeNull();

    // Claim the OTHER conversation — one this window has measured nothing for.
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])];
    for (const row of rows) {
      await (row as Updatable).updateComplete;
      const button = row.shadowRoot?.querySelector<HTMLElement>(
        '[data-testid="sv3-session-row-button"]',
      );
      if (!(button?.textContent ?? '').includes('what did the lease say?')) continue;
      button?.click();
      break;
    }
    await settle(el);

    expect(el.sessions.activeId).toBe('uc-other');
    bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]')).toBeNull();
  });

  it('opens the shared inspector on a prompt that agrees with the transcript', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    backend.histories['uc-ctx']!.contextFloorSummary = 'Everything above was compacted.';
    const el = await mount();
    await openTheOnlyConversation(el);
    // The meter needs an occupancy to render at all, and the inspector is reached through it.
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'and the third?';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await settle(el);
    stream.emit('done', { promptTokens: 1024 });
    stream.end();
    await settle(el);

    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]'));
    await settle(el);

    expect(isContextInspectorOpen()).toBe(true);
    const view = getContextInspectorView();
    const conversation = view?.phases.find((p) => p.name === 'Conversation');
    const texts = conversation?.segments.map((s) => s.text) ?? [];
    // The first turn is above the floor, so the inspector lists the SUMMARY that stands in for it —
    // never the turn itself, which is exactly what the transcript's dimming claims.
    expect(texts[0]).toBe('Everything above was compacted.');
    expect(texts).not.toContain('why did the renewal fail?');
    expect(texts).toContain('and the second one?');
    expect(view?.totalTokens).toBe(1024);
    expect(view?.windowTokens).toBe(4096);
  });
});

describe('record and history transport preservation', () => {
  it('uses the current API base and encoded conversation ID for initial and later record and history reads', async () => {
    const id = 'saved /?% conversation';
    twoTurnConversation(id);
    const el = await mount();
    el.apiBase = 'https://first.example';
    await settle(el);
    await openTheOnlyConversation(el);
    const encoded = encodeURIComponent(id);
    const urls = (): string[] => fetchMock.mock.calls.map(call => String(call[0]));
    expect(urls()).toContain(`https://first.example/api/thread/${encoded}`);
    expect(urls()).toContain(`https://first.example/api/chat/conversations/${encoded}/history`);
    el.apiBase = 'https://later.example';
    await settle(el);
    const { restoreActiveConversation } = await import('../../state/conversationListStore.js');
    restoreActiveConversation(null);
    restoreActiveConversation(id);
    await settle(el);
    expect(urls()).toContain(`https://later.example/api/thread/${encoded}`);
    expect(urls()).toContain(`https://later.example/api/chat/conversations/${encoded}/history`);
    contextAction(el, 'floor', 1);
    await settle(el);
    expect(urls()).toContain(`https://later.example/api/chat/conversations/${encoded}/context-floor`);
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(storedId(3));
  });

  it.each([404, 500, 423])('keeps history HTTP %s empty, uncertain or locked respectively', async status => {
    twoTurnConversation();
    const el = await mount();
    const serve = fetchMock.getMockImplementation() as (url: unknown, init: unknown) => Promise<unknown>;
    fetchMock.mockImplementation((url: unknown, init: unknown) => String(url).endsWith('/history')
      ? Promise.resolve({ ok: false, status, json: async () => ({}) }) : serve(url, init));
    await openTheOnlyConversation(el);
    if (status === 404) {
      expect(el.sessions.sessions[0]?.history).not.toBeNull();
      const history = el.sessions.sessions[0]!.history!;
      expect(history.contextFloor).toBeUndefined();
      expect(history.contextFloorSummary).toBeUndefined();
      expect(history.excludedMessageIds ?? []).toEqual([]);
      expect(history.excludedSourceIds ?? []).toEqual([]);
      expect(history.messageIds).toEqual([]);
      expect(await contextNotice(el)).toBe('');
      expect((await region(el, 'jf-sv3-main')).shadowRoot?.querySelector('[data-testid="sv3-history-locked"]')).toBeNull();
      expect(await nextContext(el)).toEqual([
        { role: 'user', content: 'why did the renewal fail?' },
        { role: 'assistant', content: 'The lock held.' },
        { role: 'user', content: 'and the second one?' },
        { role: 'assistant', content: 'The same lock.' },
      ]);
      expect(backend.dispatches).toHaveLength(1);
    } else if (status === 500) {
      expect(el.sessions.sessions[0]?.history).toBeNull();
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
      expect((await region(el, 'jf-sv3-main')).shadowRoot?.querySelector('[data-testid="sv3-history-locked"]')).toBeNull();
      expect(el.historyLocked).toBe(false);
    } else {
      await ask(el, 'Can this locked conversation answer?');
      expect(backend.dispatches).toHaveLength(0);
      expect((await region(el, 'jf-sv3-main')).shadowRoot?.querySelector('[data-testid="sv3-history-locked"]')).not.toBeNull();
    }
  });
});

/* ── WP1.2 — the follow-up carries the conversation, and hidden context stays visible ─────────── */

/** Type a question into the composer and send it, the way a reader does. */
async function ask(el: Mounted, text: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  field!.value = text;
  field!.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  composer.shadowRoot
    ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
    ?.click();
  await settle(el);
}

const SUMMARY_PREFIX = 'Summary of earlier conversation:\n';

describe('a follow-up ask is sent WITH the conversation (S2.0)', () => {
  it('sends the earlier turns as context, oldest first, on the conversation’s own id', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    expect(backend.dispatches).toHaveLength(1);
    const body = backend.dispatches[0]!;
    expect(body.question).toBe('and the third?');
    expect(body.conversationId).toBe('uc-ctx');
    expect(body.context).toEqual([
      { role: 'user', content: 'why did the renewal fail?' },
      { role: 'assistant', content: 'The lock held.' },
      { role: 'user', content: 'and the second one?' },
      { role: 'assistant', content: 'The same lock.' },
    ]);
  });

  it('sends no context with the first question of a new conversation', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    expect(backend.dispatches).toHaveLength(1);
    expect(backend.dispatches[0]?.context).toBeUndefined();
  });

  it('leaves out excluded messages and everything above the floor, and leads with the summary', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    backend.histories['uc-ctx']!.contextFloorSummary = 'The first renewal failed on the lock.';
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(4)];
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'system', content: `${SUMMARY_PREFIX}The first renewal failed on the lock.` },
      { role: 'user', content: 'and the second one?' },
    ]);
  });

  it('does not bring older turns back when the floor’s own message is excluded (S2.9)', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(3)];
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'assistant', content: 'The same lock.' },
    ]);
  });

  it('places a floor that sits on a message not shown here by the saved order', async () => {
    // The second turn's answer is TWO stored messages; only the last is the turn's handle, so a
    // floor on the first of them names a message no turn on screen resolves to.
    backend.conversations = [conversationRow('uc-ctx', 'why did the renewal fail?')];
    backend.threads['uc-ctx'] = {
      conversationId: 'uc-ctx',
      events: [
        wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.'),
        wireEvent(storedId(3), 'USER_MESSAGE', 'and the second one?'),
        wireEvent(storedId(4), 'ASSISTANT_MESSAGE', 'The same lock.'),
        wireEvent(storedId(5), 'ASSISTANT_MESSAGE', 'It was never released.'),
      ],
    };
    backend.histories['uc-ctx'] = {
      sessionId: 'uc-ctx',
      messages: [
        { role: 'user', content: 'why did the renewal fail?', id: storedId(0) },
        { role: 'assistant', content: 'The lock held.', id: storedId(1) },
        { role: 'user', content: 'and the second one?', id: storedId(3) },
        { role: 'assistant', content: 'The same lock.', id: storedId(4) },
        { role: 'assistant', content: 'It was never released.', id: storedId(5) },
      ],
      contextFloor: storedId(4),
    };
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    // Each saved message is sent as itself, the way the store holds it.
    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'assistant', content: 'The same lock.' },
      { role: 'assistant', content: 'It was never released.' },
    ]);
    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.hasAttribute('data-out-of-context')).toBe(true);
  });

  /** The second turn's answer is THREE saved messages; only the last is the turn's own handle. */
  function combinedAnswerConversation(): void {
    backend.conversations = [conversationRow('uc-ctx', 'why did the renewal fail?')];
    backend.threads['uc-ctx'] = {
      conversationId: 'uc-ctx',
      events: [
        wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.'),
        wireEvent(storedId(3), 'USER_MESSAGE', 'and the second one?'),
        wireEvent(storedId(4), 'ASSISTANT_MESSAGE', 'The same lock.'),
        wireEvent(storedId(5), 'ASSISTANT_MESSAGE', 'Its key was SECRET-7.'),
        wireEvent(storedId(6), 'ASSISTANT_MESSAGE', 'It was never released.'),
      ],
    };
    backend.histories['uc-ctx'] = {
      sessionId: 'uc-ctx',
      messages: [
        { role: 'user', content: 'why did the renewal fail?', id: storedId(0) },
        { role: 'assistant', content: 'The lock held.', id: storedId(1) },
        { role: 'user', content: 'and the second one?', id: storedId(3) },
        { role: 'assistant', content: 'The same lock.', id: storedId(4) },
        { role: 'assistant', content: 'Its key was SECRET-7.', id: storedId(5) },
        { role: 'assistant', content: 'It was never released.', id: storedId(6) },
      ],
    };
  }

  it('leaves out a hidden message from INSIDE a combined answer, as the bar counts it', async () => {
    combinedAnswerConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(5)];
    const el = await mount();
    await openTheOnlyConversation(el);
    const bar = await region(el, 'jf-sv3-context-bar');
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')?.textContent?.trim(),
    ).toBe('1 hidden message not shown here');

    await ask(el, 'and the third?');

    const sent = backend.dispatches[0]?.context as Array<{ content: string }>;
    expect(sent.map((m) => m.content)).toEqual([
      'why did the renewal fail?',
      'The lock held.',
      'and the second one?',
      'The same lock.',
      'It was never released.',
    ]);
    expect(JSON.stringify(sent)).not.toContain('SECRET-7');
  });

  it('starts at a floor set INSIDE a combined answer, sending nothing saved before it', async () => {
    combinedAnswerConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(5);
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'assistant', content: 'Its key was SECRET-7.' },
      { role: 'assistant', content: 'It was never released.' },
    ]);
  });

  it('starts at a floor on the LAST message of a combined answer, sending only that message', async () => {
    combinedAnswerConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(6);
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'assistant', content: 'It was never released.' },
    ]);
  });

  it('does not send older turns before the conversation’s context settings have arrived', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(4)];
    // The transcript arrives; the floor and the exclusion do not, yet.
    backend.holdHistoryFor.add('uc-ctx');
    const el = await mount();
    await openTheOnlyConversation(el);
    expect(el.sessions.sessions[0]?.history ?? null).toBeNull();

    await ask(el, 'and the third?');
    // Nothing went out with more than the saved settings allow — at most, nothing went out yet.
    for (const sent of backend.dispatches) {
      expect(sent.context).toEqual([{ role: 'user', content: 'and the second one?' }]);
    }
    backend.heldHistories.get('uc-ctx')?.();
    await settle(el);

    expect(backend.dispatches).toHaveLength(1);
    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'user', content: 'and the second one?' },
    ]);
  });

  it('refuses the follow-up when the conversation’s context settings cannot be read', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(1)];
    backend.failHistoryFor.add('uc-ctx');
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');

    expect(backend.dispatches).toHaveLength(0);
    expect(await contextNotice(el)).toContain('Question not sent');
    const composer = await region(el, 'jf-sv3-composer');
    expect(composer.shadowRoot?.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('and the third?');
  });

  it('shows in the inspector exactly the messages it sends — one hidden answer, not its turn', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(1)];
    const el = await mount();
    await openTheOnlyConversation(el);
    await ask(el, 'and the third?');
    expect(backend.dispatches[0]?.context).toEqual([
      { role: 'user', content: 'why did the renewal fail?' },
      { role: 'user', content: 'and the second one?' },
      { role: 'assistant', content: 'The same lock.' },
    ]);
    stream.emit('chunk', { text: 'A third lock.' });
    stream.emit('done', { promptTokens: 1024 });
    stream.end();
    await settle(el);

    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-meter"]'));
    await settle(el);
    const conversation = getContextInspectorView()?.phases.find((p) => p.name === 'Conversation');
    expect(conversation?.segments.map((s) => s.text)).toEqual([
      'why did the renewal fail?',
      'and the second one?',
      'The same lock.',
      'and the third?',
      'A third lock.',
    ]);
  });
});

describe('the "Interpreted as" note survives a cold reload (S2.1)', () => {
  it('shows each note on its own turn live, after the record refresh, and after a reload', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);

    await ask(el, 'and the third?');
    stream.emit('rag.rewrite', {
      original: 'and the third?',
      standalone: 'Why did the third renewal fail?',
    });
    stream.emit('chunk', { text: 'A third lock.' });
    // The record the backend now holds for this turn: the note rides on the answer row.
    backend.threads['uc-ctx']!.events.push(
      wireEvent(storedId(6), 'USER_MESSAGE', 'and the third?'),
      wireEvent(storedId(7), 'ASSISTANT_MESSAGE', 'A third lock.', {
        standaloneQuestion: 'Why did the third renewal fail?',
      }),
    );
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const notes = async (): Promise<string[]> => {
      const main = await region(el, 'jf-sv3-main');
      return turnsIn(main).map(
        (t) => t.querySelector('[data-testid="sv3-turn-rewrite"]')?.textContent?.trim() ?? '',
      );
    };
    const live = await notes();
    expect(live[2]).toContain('Why did the third renewal fail?');
    expect(live.filter((n) => n !== '')).toHaveLength(1);

    el.remove();
    const reloaded = await mount();
    await openTheOnlyConversation(reloaded);
    const main = await region(reloaded, 'jf-sv3-main');
    const after = turnsIn(main).map(
      (t) => t.querySelector('[data-testid="sv3-turn-rewrite"]')?.textContent?.trim() ?? '',
    );
    expect(after).toEqual(live);
  });
});

describe('hidden context that is not on screen stays visible and reversible (S2.7 / S2.8)', () => {
  it('counts unique hidden messages not shown here beside the hidden turns on screen', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [
      storedId(0),
      'gone-1',
      'gone-2',
      'gone-1',
      storedId(0),
    ];
    const el = await mount();
    await openTheOnlyConversation(el);
    const bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')?.textContent).toContain(
      '1 turn hidden from context',
    );
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')?.textContent?.trim(),
    ).toBe('2 hidden messages not shown here');
  });

  it('shows and undoes exclusions that are ALL off screen, without inventing a turn count', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = ['gone-1'];
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    backend.histories['uc-ctx']!.contextFloorSummary = 'Earlier, summarized.';
    backend.histories['uc-ctx']!.excludedSourceIds = ['doc-1\u001f0'];
    const el = await mount();
    await openTheOnlyConversation(el);
    let bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')).toBeNull();
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')?.textContent?.trim(),
    ).toBe('1 hidden message not shown here');

    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);

    expect(backend.writes.map((w) => w.path)).toEqual(['exclude:gone-1']);
    const saved = backend.histories['uc-ctx']!;
    expect(saved.excludedMessageIds).toEqual([]);
    // Include all is about MESSAGES: the floor, its summary and the hidden sources are untouched.
    expect(saved.contextFloor).toBe(storedId(3));
    expect(saved.contextFloorSummary).toBe('Earlier, summarized.');
    expect(saved.excludedSourceIds).toEqual(['doc-1\u001f0']);
    bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')).toBeNull();
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]')).toBeNull();
  });

  it('clears every recorded exclusion once each, on screen and off', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), 'gone-1', 'gone-1'];
    const el = await mount();
    await openTheOnlyConversation(el);
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);
    expect(backend.writes.map((w) => w.path).sort()).toEqual(
      [`exclude:${storedId(0)}`, 'exclude:gone-1'].sort(),
    );
    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual([]);
  });

  it('says the context starts at a message not shown here, shows and edits its summary, and restores', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = 'gone-floor';
    backend.histories['uc-ctx']!.contextFloorSummary = 'An older thread, summarized.';
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0)];
    const el = await mount();
    await openTheOnlyConversation(el);
    let bar = await region(el, 'jf-sv3-context-bar');
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-unseen"]')?.textContent,
    ).toContain('Context starts at a message not shown here');

    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-toggle"]'));
    await bar.updateComplete;
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary"]')?.textContent?.trim(),
    ).toBe('An older thread, summarized.');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-edit"]'));
    await bar.updateComplete;
    const input = bar.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-context-floor-summary-input"]',
    );
    input!.value = 'An older thread, corrected.';
    input!.dispatchEvent(new Event('input'));
    await bar.updateComplete;
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary-save"]'));
    await settle(el);
    expect(backend.histories['uc-ctx']?.contextFloorSummary).toBe('An older thread, corrected.');
    bar = await region(el, 'jf-sv3-context-bar');
    expect(
      bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-summary"]')?.textContent?.trim(),
    ).toBe('An older thread, corrected.');

    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-restore"]'));
    await settle(el);
    const saved = backend.histories['uc-ctx']!;
    expect(saved.contextFloor).toBeUndefined();
    expect(saved.contextFloorSummary).toBeUndefined();
    expect(saved.excludedMessageIds).toEqual([storedId(0)]);
    bar = await region(el, 'jf-sv3-context-bar');
    expect(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-unseen"]')).toBeNull();
  });
});

describe('the undo never leaves the bar showing a state that is not saved (S2.10)', () => {
  it('reports a PARTIALLY refused Include all as a failure and shows what was really saved', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), 'gone-1'];
    backend.refuseExcludeIds.add('gone-1');
    const el = await mount();
    await openTheOnlyConversation(el);
    const seen = toasts();
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);

    expect(seen).toHaveLength(1);
    expect(seen[0]).toMatch(/could not/i);
    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual(['gone-1']);
    const after = await region(el, 'jf-sv3-context-bar');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')).toBeNull();
    expect(
      after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')?.textContent?.trim(),
    ).toBe('1 hidden message not shown here');
  });

  it('does not wipe the bar when the reload after Include all fails', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), 'gone-1'];
    backend.refuse.add('exclude');
    const el = await mount();
    await openTheOnlyConversation(el);
    backend.failHistoryFor.add('uc-ctx');
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);

    // Nothing was put back, and the failed read must not be taken for "nothing is hidden".
    const after = await region(el, 'jf-sv3-context-bar');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')?.textContent).toContain(
      '1 turn hidden from context',
    );
    expect(
      after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')?.textContent?.trim(),
    ).toBe('1 hidden message not shown here');
  });

  it('marks Include all unconfirmed when its reload failed', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), 'gone-1'];
    const el = await mount();
    await openTheOnlyConversation(el);
    backend.failHistoryFor.add('uc-ctx');
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);

    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual([]);
    expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
    const after = await region(el, 'jf-sv3-context-bar');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')).not.toBeNull();
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden-unseen"]')).not.toBeNull();
  });

  it('marks Restore unconfirmed when its reload failed', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = 'gone-floor';
    const el = await mount();
    await openTheOnlyConversation(el);
    backend.failHistoryFor.add('uc-ctx');
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-restore"]'));
    await settle(el);

    expect(backend.histories['uc-ctx']?.contextFloor).toBeUndefined();
    expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
    const after = await region(el, 'jf-sv3-context-bar');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-floor-unseen"]')).not.toBeNull();
  });

  it('leaves the bar on the saved floor when Restore is refused AND its reload fails', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = 'gone-floor';
    backend.histories['uc-ctx']!.contextFloorSummary = 'An older thread, summarized.';
    const el = await mount();
    await openTheOnlyConversation(el);
    backend.refuse.add('clear');
    backend.failHistoryFor.add('uc-ctx');
    const seen = toasts();
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-floor-restore"]'));
    await settle(el);

    expect(seen).toEqual([CONTEXT_RESTORE_FAILED]);
    expect(backend.histories['uc-ctx']?.contextFloor).toBe('gone-floor');
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBe('gone-floor');
    expect(el.sessions.sessions[0]?.history?.contextFloorSummary).toBe('An older thread, summarized.');
    const after = await region(el, 'jf-sv3-context-bar');
    expect(
      after.shadowRoot?.querySelector('[data-testid="sv3-context-floor-unseen"]')?.textContent,
    ).toContain('Context starts at a message not shown here');
  });

  it('queues the next write until the previous refresh has settled', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    const el = await mount();
    await openTheOnlyConversation(el);
    backend.holdHistoryFor.add('uc-ctx');
    contextAction(el, 'restore');
    await settle(el);
    contextAction(el, 'floor', 0);
    await settle(el);
    try {
      expect(backend.writes).toHaveLength(1);
    } finally {
      backend.heldHistories.get('uc-ctx')?.();
      await settle(el);
    }
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(storedId(0));
  });

  it('still attempts the answer exclusion after a refused question and displays the saved partial result', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    backend.refuseExcludeIds.add(storedId(0));
    const seen = toasts();
    contextAction(el, 'exclude', 0);
    await settle(el);
    expect(backend.writes.map(write => write.path)).toEqual([`exclude:${storedId(0)}`, `exclude:${storedId(1)}`]);
    expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual([storedId(1)]);
    expect(seen).toContain(CONTEXT_EXCLUDE_FAILED);
    expect(await nextContext(el)).toEqual([
      { role: 'user', content: 'why did the renewal fail?' },
      { role: 'user', content: 'and the second one?' },
      { role: 'assistant', content: 'The same lock.' },
    ]);
  });

  it('keeps Exclude Restore Include in selection order through an overlapping open read and final confirmation', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    const el = await mount();
    await openTheOnlyConversation(el);
    const release = holdWriteResponse(`/messages/${storedId(0)}/exclude`);
    contextAction(el, 'exclude', 0);
    await settle(el);
    contextAction(el, 'restore');
    contextAction(el, 'include', 0);
    // A second window's store notification reopens this conversation during the write queue.
    const { restoreActiveConversation } = await import('../../state/conversationListStore.js');
    restoreActiveConversation(null);
    restoreActiveConversation('uc-ctx');
    await settle(el);
    expect(backend.writes).toHaveLength(1);
    backend.holdHistoryFor.add('uc-ctx');
    release();
    await settle(el);
    try {
      await ask(el, 'Wait for all three actions');
      expect(backend.dispatches).toHaveLength(0);
      expect(backend.writes).toHaveLength(2);
    } finally {
      backend.holdHistoryFor.add('uc-ctx');
      backend.heldHistories.get('uc-ctx')?.();
      await settle(el);
    }
    expect(backend.dispatches).toHaveLength(0);
    expect(backend.writes).toHaveLength(3);
    backend.holdHistoryFor.add('uc-ctx');
    backend.heldHistories.get('uc-ctx')?.();
    await settle(el);
    expect(backend.dispatches).toHaveLength(0);
    expect(backend.writes.map(write => [write.method, write.path, write.body.excluded])).toEqual([
      ['POST', `exclude:${storedId(0)}`, true],
      ['POST', `exclude:${storedId(1)}`, true],
      ['DELETE', 'context-floor', undefined],
      ['POST', `exclude:${storedId(0)}`, false],
      ['POST', `exclude:${storedId(1)}`, false],
    ]);
    backend.heldHistories.get('uc-ctx')?.();
    await settle(el);
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBeUndefined();
    expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual([]);
    expect(backend.dispatches).toHaveLength(1);
    expect(backend.dispatches[0]?.question).toBe('Wait for all three actions');
    expect(backend.dispatches[0]?.context).toHaveLength(4);
    stream.emit('done', {});
    stream.end();
    await settle(el);
  });

  it('keeps uncertainty through an overlapping open read until the pending write confirms its own result', async () => {
    twoTurnConversation();
    backend.failHistoryFor.add('uc-ctx');
    const el = await mount();
    await openTheOnlyConversation(el);
    expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
    backend.failHistoryFor.clear();
    const release = holdWriteResponse(`/messages/${storedId(0)}/exclude`);
    try {
      contextAction(el, 'exclude', 0);
      await settle(el);
      expect(backend.writes).toHaveLength(1);
      const reads = backend.historyReads.length;
      const { restoreActiveConversation } = await import('../../state/conversationListStore.js');
      restoreActiveConversation(null);
      restoreActiveConversation('uc-ctx');
      await settle(el);
      expect(backend.historyReads).toHaveLength(reads + 1);
      expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual([storedId(0)]);
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
    } finally {
      release();
      await settle(el);
    }
    expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual([storedId(0), storedId(1)]);
    expect(await contextNotice(el)).toBe('');
  });

  it.each([
    { race: 'R1', first: 'restore', second: 'floor', tail: '/context-floor', method: 'DELETE', phase: 'response' },
    { race: 'R4', first: 'restore', second: 'exclude', tail: '/context-floor', method: 'DELETE', phase: 'request' },
    { race: 'R5', first: 'exclude', second: 'exclude', tail: `/messages/${storedId(0)}/exclude`, method: 'POST', phase: 'request' },
    { race: 'R10', first: 'include-all', second: 'floor', tail: `/messages/${storedId(0)}/exclude`, method: 'POST', phase: 'request' },
    { race: 'include', first: 'include', second: 'exclude', tail: `/messages/${storedId(0)}/exclude`, method: 'POST', phase: 'response' },
    { race: 'compact', first: 'compact', second: 'floor', tail: '/compact', method: 'POST', phase: 'response' },
    { race: 'summary', first: 'summary', second: 'summary', tail: '/context-floor/summary', method: 'POST', phase: 'response' },
  ] as const)('serializes $race and confirms saved context before the next Ask after refresh failure', async ({ first, second, tail, method, phase }) => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(0);
    backend.histories['uc-ctx']!.contextFloorSummary = 'Original summary';
    backend.histories['uc-ctx']!.excludedMessageIds = first === 'include-all' || first === 'include'
      ? [storedId(0), storedId(1)] : [];
    const el = await mount();
    await openTheOnlyConversation(el);
    const release = holdWriteResponse(tail, method, phase);
    contextAction(el, first, 0, 'First correction');
    await settle(el);
    const writesBeforeSecond = backend.writes.length;
    contextAction(el, second, 1, 'Final correction');
    await settle(el);
    try {
      expect(backend.writes.length, 'the newer write overtook an unsettled write').toBe(writesBeforeSecond);
      backend.failHistoryFor.add('uc-ctx');
    } finally {
      release();
      await settle(el);
    }
    expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
    backend.failHistoryFor.clear();
    const context = await nextContext(el);
    const saved = backend.histories['uc-ctx']!;
    const expectedFloor = second === 'floor' ? storedId(3) : first === 'restore' ? undefined : storedId(0);
    expect(saved.contextFloor).toBe(expectedFloor);
    expect(saved.contextFloorSummary).toBe(second === 'summary' ? 'Final correction'
      : second === 'floor' || first === 'restore' ? undefined : 'Original summary');
    expect(saved.excludedMessageIds).toEqual(second === 'exclude'
      ? [...(first === 'exclude' ? [storedId(0), storedId(1)] : []), storedId(3), storedId(4)] : []);
    expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(saved.contextFloor);
    expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual(saved.excludedMessageIds);
    expect(context).toEqual([
      ...(saved.contextFloorSummary ? [{ role: 'system', content: `${SUMMARY_PREFIX}${saved.contextFloorSummary}` }] : []),
      ...(saved.contextFloor !== storedId(3) && !saved.excludedMessageIds?.includes(storedId(0)) ? [
        { role: 'user', content: 'why did the renewal fail?' },
        { role: 'assistant', content: 'The lock held.' },
      ] : []),
      ...(!saved.excludedMessageIds?.includes(storedId(3)) ? [
        { role: 'user', content: 'and the second one?' },
        { role: 'assistant', content: 'The same lock.' },
      ] : []),
    ]);
    expect(await contextNotice(el)).toBe('');
  });

  it.each(['floor', 'compact', 'summary', 'restore', 'exclude', 'include', 'include-all'] as const)(
    'marks a saved %s unconfirmed when refresh fails and blocks Ask without losing its draft', async (action) => {
      twoTurnConversation();
      backend.histories['uc-ctx']!.contextFloor = storedId(0);
      backend.histories['uc-ctx']!.contextFloorSummary = 'Old summary';
      backend.histories['uc-ctx']!.excludedMessageIds = action === 'include' || action === 'include-all'
        ? [storedId(0), storedId(1)] : [];
      const el = await mount();
      await openTheOnlyConversation(el);
      backend.failHistoryFor.add('uc-ctx');
      contextAction(el, action, 1, 'New summary');
      await settle(el);
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
      const turnCount = el.sessions.sessions[0]?.turns.length;
      await ask(el, 'Keep this question');
      expect(backend.dispatches).toHaveLength(0);
      expect(el.sessions.sessions[0]?.turns).toHaveLength(turnCount!);
      const composer = await region(el, 'jf-sv3-composer');
      expect(composer.shadowRoot?.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('Keep this question');
      expect(await contextNotice(el)).toContain('Question not sent');
      backend.failHistoryFor.clear();
      await discardRetainedQuestion(el);
      await nextContext(el);
      expect(backend.dispatches).toHaveLength(1);
      expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(backend.histories['uc-ctx']?.contextFloor);
      expect(el.sessions.sessions[0]?.history?.contextFloorSummary).toBe(backend.histories['uc-ctx']?.contextFloorSummary);
      expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual(backend.histories['uc-ctx']?.excludedMessageIds);
    },
  );

  it.each(['', 'A different draft'])(
    'keeps an honest refusal after context recovers and the composer becomes "%s" (X20)', async (draft) => {
      twoTurnConversation();
      const el = await mount();
      await openTheOnlyConversation(el);
      vi.useFakeTimers();
      try {
        backend.failHistoryFor.add('uc-ctx');
        contextAction(el, 'restore');
        await settle(el);
        await ask(el, 'The refused question');
        expect(backend.dispatches).toHaveLength(0);
        expect(await contextNotice(el)).toContain('Question not sent');
        backend.failHistoryFor.clear();
        window.dispatchEvent(new Event('online'));
        await settle(el);
        expect(await contextNotice(el)).not.toContain('could not be confirmed');
        const composer = await region(el, 'jf-sv3-composer');
        const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!;
        field.value = draft;
        field.dispatchEvent(new Event('input'));
        await composer.updateComplete;
        await settle(el);
        expect(field.value).toBe(draft);
        const notice = await contextNotice(el);
        expect(notice).toContain('Question not sent');
        expect(notice).toContain('Unsent question (copy to reuse): “The refused question”');
        expect(notice).not.toContain('Your draft is kept');
        expect(backend.dispatches).toHaveLength(0);
      } finally {
        el.remove();
        vi.useRealTimers();
      }
    },
  );

  it('requires explicit retirement before sending in another conversation while context waits (X28/RR15)', async () => {
    twoTurnConversation();
    backend.conversations.push(conversationRow('uc-other', 'The other conversation'));
    backend.threads['uc-other'] = {
      conversationId: 'uc-other',
      events: [
        wireEvent(storedId(5), 'USER_MESSAGE', 'The other conversation'),
        wireEvent(storedId(6), 'ASSISTANT_MESSAGE', 'The other answer'),
      ],
    };
    backend.histories['uc-other'] = {
      sessionId: 'uc-other',
      messages: [
        { role: 'user', content: 'The other conversation', id: storedId(5) },
        { role: 'assistant', content: 'The other answer', id: storedId(6) },
      ],
    };
    // Finish each dispatched answer immediately, so only context confirmation can block Send.
    const serve = fetchMock.getMockImplementation() as (url: unknown, init: unknown) => Promise<unknown>;
    fetchMock.mockImplementation((url: unknown, init: unknown) => {
      if (String(url).includes('/api/chat/dispatch')) {
        backend.dispatches.push(bodyOf(init));
        return Promise.resolve(new Response('event: done\ndata: {}\n\n'));
      }
      return serve(url, init);
    });
    const el = await mount();
    await openTheOnlyConversation(el);
    const release = holdWriteResponse(`/messages/${storedId(0)}/exclude`);
    contextAction(el, 'exclude', 0);
    await settle(el);
    try {
      await ask(el, 'Waiting question in the first conversation');
      const sidebar = await region(el, 'jf-sv3-sidebar');
      for (const row of sidebar.shadowRoot!.querySelectorAll<Updatable>('jf-sv3-session-row')) {
        await row.updateComplete;
        const button = row.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-session-row-button"]')!;
        if (button.textContent?.includes('The other conversation')) button.click();
      }
      await settle(el);
      expect(el.sessions.activeId).toBe('uc-other');
      await ask(el, 'Question in the other conversation');
      expect(backend.dispatches).toHaveLength(0);
      expect(el.shadowRoot!.textContent).toContain('explicitly discard');
      await discardRetainedQuestion(el);
      for (const button of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.notice button')) {
        if (button.textContent?.includes('Discard this extra recovery copy')) button.click();
      }
      await settle(el);
      await ask(el, 'Question in the other conversation');
      expect(backend.dispatches.filter((d) => d.conversationId === 'uc-other')).toEqual([
        expect.objectContaining({ question: 'Question in the other conversation' }),
      ]);
      const composer = await region(el, 'jf-sv3-composer');
      expect(composer.shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('');
    } finally {
      release();
      await settle(el);
    }
    expect(backend.dispatches.filter((d) => d.conversationId === 'uc-other')).toHaveLength(1);
    expect(backend.dispatches.some((d) => d.conversationId === 'uc-other'
      && d.question === 'Waiting question in the first conversation')).toBe(false);
  });

  it('keeps the refused-send reason through failed background retries (X19)', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    vi.useFakeTimers();
    try {
      backend.failHistoryFor.add('uc-ctx');
      contextAction(el, 'restore');
      await settle(el);
      await ask(el, 'My refused question');
      expect(await contextNotice(el)).toContain('Question not sent');
      await vi.advanceTimersByTimeAsync(1000);
      await settle(el);
      expect(await contextNotice(el)).toContain('Question not sent');
      expect(backend.dispatches).toHaveLength(0);
    } finally {
      el.remove();
      vi.useRealTimers();
    }
  });

  it('keeps a refused question visible beside newer input through retry and recovery (X15)', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    const release = holdWriteResponse('/context-floor');
    contextAction(el, 'floor', 1);
    await settle(el);
    await ask(el, 'The submitted question to recover');
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!;
    field.value = 'My newer draft';
    field.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    vi.useFakeTimers();
    try {
      backend.failHistoryFor.add('uc-ctx');
      release();
      await settle(el);
      await vi.advanceTimersByTimeAsync(1000);
      await settle(el);
      expect(backend.dispatches).toHaveLength(0);
      expect(field.value).toBe('My newer draft');
      expect(await contextNotice(el)).toContain('Question not sent');
      expect(await contextNotice(el)).toContain('The submitted question to recover');
      backend.failHistoryFor.clear();
      window.dispatchEvent(new Event('online'));
      await settle(el);
      expect(await contextNotice(el)).toContain('The submitted question to recover');
      expect(await contextNotice(el)).not.toContain('could not be confirmed');
      expect(field.value).toBe('My newer draft');
      await discardRetainedQuestion(el);
      await nextContext(el);
      expect(await contextNotice(el)).toBe('');
    } finally {
      release();
      el.remove();
      vi.useRealTimers();
    }
  });

  it.each(['screen switch', 'reparent'] as const)(
    're-reads interrupted first history on reattach after %s and retries failures (X14a)', async (move) => {
      twoTurnConversation();
      backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), storedId(1)];
      const el = await mount();
      backend.holdHistoryFor.add('uc-ctx');
      await openTheOnlyConversation(el);
      vi.useFakeTimers();
      try {
        el.remove();
        if (move === 'screen switch') await Promise.resolve();
        backend.failHistoryFor.add('uc-ctx');
        document.body.appendChild(el);
        await settle(el);
        backend.heldHistories.get('uc-ctx')?.();
        await settle(el);
        expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
        const reads = backend.historyReads.length;
        backend.failHistoryFor.clear();
        await vi.advanceTimersByTimeAsync(1000);
        await settle(el);
        expect(backend.historyReads.length).toBeGreaterThan(reads);
        expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual([storedId(0), storedId(1)]);
        expect(await contextNotice(el)).toBe('');
      } finally {
        backend.heldHistories.get('uc-ctx')?.();
        await settle(el);
        el.remove();
        vi.useRealTimers();
      }
    },
  );

  it('refuses follow-up after an interrupted first history read and a failed re-read (X14b)', async () => {
    twoTurnConversation();
    const el = await mount();
    backend.holdHistoryFor.add('uc-ctx');
    await openTheOnlyConversation(el);
    try {
      el.remove();
      await Promise.resolve();
      backend.failHistoryFor.add('uc-ctx');
      document.body.appendChild(el);
      await settle(el);
      await ask(el, 'Do not send without saved context');
      expect(backend.dispatches).toHaveLength(0);
      expect(el.sessions.sessions[0]?.turns).toHaveLength(2);
      expect(await contextNotice(el)).toContain('Question not sent');
      const composer = await region(el, 'jf-sv3-composer');
      expect(composer.shadowRoot?.querySelector<HTMLTextAreaElement>('textarea')?.value)
        .toBe('Do not send without saved context');
    } finally {
      backend.heldHistories.get('uc-ctx')?.();
      stream.emit('done', {});
      stream.end();
      await settle(el);
    }
  });

  it('confirms a lost write response by reading history before sending Ask', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    const serve = fetchMock.getMockImplementation() as (
      url: unknown, init: { method?: string },
    ) => Promise<unknown>;
    fetchMock.mockImplementation(async (url: unknown, init: { method?: string } = {}) => {
      const result = await serve(url, init);
      if (String(url).endsWith('/context-floor') && init.method === 'POST') throw new Error('response lost');
      return result;
    });
    backend.failHistoryFor.add('uc-ctx');
    contextAction(el, 'floor', 1);
    await settle(el);
    expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
    backend.failHistoryFor.clear();
    expect(await nextContext(el)).toEqual([
      { role: 'user', content: 'and the second one?' },
      { role: 'assistant', content: 'The same lock.' },
    ]);
  });

  it('retries unconfirmed context on backoff and reconnect and cancels retries on disconnect', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    vi.useFakeTimers();
    try {
      backend.failHistoryFor.add('uc-ctx');
      contextAction(el, 'floor', 1);
      await settle(el);
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
      const count = backend.historyReads.length;
      await vi.advanceTimersByTimeAsync(1000);
      expect(backend.historyReads.length).toBe(count + 1);
      await vi.advanceTimersByTimeAsync(1000);
      expect(backend.historyReads.length).toBe(count + 1);
      backend.failHistoryFor.clear();
      window.dispatchEvent(new Event('online'));
      await settle(el);
      expect(await contextNotice(el)).toBe('');
      expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(storedId(3));
      backend.failHistoryFor.add('uc-ctx');
      contextAction(el, 'restore');
      await settle(el);
      el.remove();
      const final = backend.historyReads.length;
      await vi.advanceTimersByTimeAsync(60000);
      expect(backend.historyReads).toHaveLength(final);
    } finally {
      vi.useRealTimers();
    }
  });

  it('caps uncertain-history retries at thirty seconds and online resets the cadence without duplicate listeners', async () => {
    twoTurnConversation();
    const added = vi.spyOn(window, 'addEventListener');
    const removed = vi.spyOn(window, 'removeEventListener');
    const el = await mount();
    const onlineListener = added.mock.calls.find(([type]) => type === 'online')?.[1];
    expect(onlineListener).toEqual(expect.any(Function));
    await openTheOnlyConversation(el);
    vi.useFakeTimers();
    try {
      backend.failHistoryFor.add('uc-ctx');
      contextAction(el, 'restore');
      await settle(el);
      for (const delay of [1000, 2000, 4000, 8000, 16000, 30000, 30000]) {
        const reads = backend.historyReads.length;
        await vi.advanceTimersByTimeAsync(delay - 1);
        expect(backend.historyReads).toHaveLength(reads);
        await vi.advanceTimersByTimeAsync(1);
        expect(backend.historyReads).toHaveLength(reads + 1);
      }
      const beforeOnline = backend.historyReads.length;
      window.dispatchEvent(new Event('online'));
      await settle(el);
      expect(backend.historyReads).toHaveLength(beforeOnline + 1);
      await vi.advanceTimersByTimeAsync(999);
      expect(backend.historyReads).toHaveLength(beforeOnline + 1);
      await vi.advanceTimersByTimeAsync(1);
      expect(backend.historyReads).toHaveLength(beforeOnline + 2);
      el.remove();
      expect(removed).toHaveBeenCalledWith('online', onlineListener);
      const detached = backend.historyReads.length;
      window.dispatchEvent(new Event('online'));
      await vi.advanceTimersByTimeAsync(60000);
      expect(backend.historyReads).toHaveLength(detached);
      document.body.append(el);
      await settle(el);
      const reattached = backend.historyReads.length;
      window.dispatchEvent(new Event('online'));
      await settle(el);
      expect(backend.historyReads).toHaveLength(reattached + 1);
    } finally {
      el.remove();
      vi.useRealTimers();
    }
  });

  it.each(['A newer unsent question', 'Submitted question'])(
    'sends the submitted question after a context wait and keeps later input: %s', async (later) => {
      twoTurnConversation();
      const el = await mount();
      await openTheOnlyConversation(el);
      const release = holdWriteResponse('/context-floor');
      contextAction(el, 'floor', 1);
      await settle(el);
      try {
        await ask(el, 'Submitted question');
        expect(backend.dispatches).toHaveLength(0);
        const composer = await region(el, 'jf-sv3-composer');
        const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!;
        // Editing back to the submitted words is still a new draft, owned by the reader.
        field.value = 'An intermediate edit';
        field.dispatchEvent(new Event('input'));
        field.value = later;
        field.dispatchEvent(new Event('input'));
        await composer.updateComplete;
      } finally {
        release();
        await settle(el);
      }
      expect(backend.dispatches).toHaveLength(1);
      expect(backend.dispatches[0]?.question).toBe('Submitted question');
      expect(backend.dispatches[0]?.context).toEqual([
        { role: 'user', content: 'and the second one?' },
        { role: 'assistant', content: 'The same lock.' },
      ]);
      const composer = await region(el, 'jf-sv3-composer');
      expect(composer.shadowRoot?.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe(later);
      stream.emit('done', {});
      stream.end();
      await settle(el);
    },
  );

  it.each(['load', 'reload'] as const)(
    'warns after a failed history read on %s and refuses Ask with its draft kept', async (entry) => {
      twoTurnConversation();
      backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0), storedId(1)];
      let el = await mount();
      if (entry === 'reload') {
        await openTheOnlyConversation(el);
        el.remove();
      }
      backend.failHistoryFor.add('uc-ctx');
      if (entry === 'reload') el = await mount();
      else await openTheOnlyConversation(el);
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
      const review = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-draft-review-confirm]');
      if (review) {
        expect(backend.dispatches).toHaveLength(0); review.click(); await settle(el);
        expect(backend.dispatches).toHaveLength(0);
      }
      const reads = backend.historyReads.length;
      await ask(el, 'Keep this follow-up');
      expect(backend.historyReads.length).toBeGreaterThan(reads);
      expect(backend.dispatches).toHaveLength(0);
      expect(el.sessions.sessions[0]?.turns).toHaveLength(2);
      expect(await contextNotice(el)).toContain('Question not sent');
      const composer = await region(el, 'jf-sv3-composer');
      expect(composer.shadowRoot?.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('Keep this follow-up');
      backend.failHistoryFor.clear();
      await discardRetainedQuestion(el);
      expect(await nextContext(el)).toEqual([
        { role: 'user', content: 'and the second one?' },
        { role: 'assistant', content: 'The same lock.' },
      ]);
      expect(await contextNotice(el)).toBe('');
    },
  );

  it('refuses Ask when a load read is still pending and its confirmation read fails', async () => {
    twoTurnConversation();
    const el = await mount();
    backend.holdHistoryFor.add('uc-ctx');
    await openTheOnlyConversation(el);
    expect(el.sessions.sessions[0]?.history ?? null).toBeNull();
    backend.failHistoryFor.add('uc-ctx');
    try {
      await ask(el, 'Keep the pending follow-up');
      expect(backend.dispatches).toHaveLength(0);
      expect(el.sessions.sessions[0]?.turns).toHaveLength(2);
      expect(await contextNotice(el)).toContain('Question not sent');
      const composer = await region(el, 'jf-sv3-composer');
      expect(composer.shadowRoot?.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('Keep the pending follow-up');
    } finally {
      backend.heldHistories.get('uc-ctx')?.();
      await settle(el);
    }
  });

  it('recovers a failed load history read on backoff without another action', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(3);
    const el = await mount();
    vi.useFakeTimers();
    try {
      backend.failHistoryFor.add('uc-ctx');
      await openTheOnlyConversation(el);
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
      const reads = backend.historyReads.length;
      backend.failHistoryFor.clear();
      await vi.advanceTimersByTimeAsync(1000);
      await settle(el);
      expect(backend.historyReads).toHaveLength(reads + 1);
      expect(await contextNotice(el)).toBe('');
      expect(el.sessions.sessions[0]?.history?.contextFloor).toBe(storedId(3));
      await vi.advanceTimersByTimeAsync(60000);
      expect(backend.historyReads).toHaveLength(reads + 1);
    } finally {
      el.remove();
      vi.useRealTimers();
    }
  });

  it('waits for queued context writes before Ask and sends the question once', async () => {
    twoTurnConversation();
    const el = await mount();
    await openTheOnlyConversation(el);
    const release = holdWriteResponse('/context-floor');
    contextAction(el, 'floor', 1);
    await settle(el);
    try {
      await ask(el, 'and the third?');
      await ask(el, 'and the third?');
      expect(backend.dispatches, 'Ask used context while its write was unsettled').toHaveLength(0);
      contextAction(el, 'exclude', 1);
      await settle(el);
    } finally {
      release();
      await settle(el);
    }
    expect(backend.dispatches).toHaveLength(1);
    expect(backend.dispatches[0]).not.toHaveProperty('context');
    stream.emit('done', {});
    stream.end();
    await settle(el);
  });

  it('Include all uses the exclusions saved by an earlier queued action after its failed refresh', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.excludedMessageIds = ['gone-1'];
    const el = await mount();
    await openTheOnlyConversation(el);
    const release = holdWriteResponse(`/messages/${storedId(1)}/exclude`);
    contextAction(el, 'exclude', 0);
    await settle(el);
    backend.failHistoryFor.add('uc-ctx');
    contextAction(el, 'include-all');
    // Only the earlier write's refresh fails; the next history read is available again.
    const serve = fetchMock.getMockImplementation() as (
      url: unknown, init: { method?: string },
    ) => Promise<unknown>;
    fetchMock.mockImplementation(async (url: unknown, init: { method?: string } = {}) => {
      const response = await serve(url, init);
      if (String(url).endsWith('/history')) backend.failHistoryFor.clear();
      return response;
    });
    release();
    await settle(el);
    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual([]);
    expect(el.sessions.sessions[0]?.history?.excludedMessageIds).toEqual([]);
    expect(await contextNotice(el)).toBe('');
  });

  it('automatically confirms saved context when a backoff retry succeeds', async () => {
    twoTurnConversation();
    backend.histories['uc-ctx']!.contextFloor = storedId(0);
    backend.histories['uc-ctx']!.contextFloorSummary = 'Old summary';
    const el = await mount();
    await openTheOnlyConversation(el);
    vi.useFakeTimers();
    try {
      backend.failHistoryFor.add('uc-ctx');
      contextAction(el, 'summary', 0, 'Recovered summary');
      await settle(el);
      expect(await contextNotice(el)).toContain('Saved context could not be confirmed');
      backend.failHistoryFor.clear();
      await vi.advanceTimersByTimeAsync(1000);
      await settle(el);
      expect(await contextNotice(el)).toBe('');
      expect(el.sessions.sessions[0]?.history?.contextFloorSummary).toBe('Recovered summary');
      const count = backend.historyReads.length;
      await vi.advanceTimersByTimeAsync(60000);
      expect(backend.historyReads).toHaveLength(count);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the conversation the reader moved to on ITS saved state while the undo finishes', async () => {
    twoTurnConversation('uc-ctx', 'why did the renewal fail?');
    backend.histories['uc-ctx']!.excludedMessageIds = [storedId(0)];
    backend.conversations.push(conversationRow('uc-other', 'what did the lease say?'));
    backend.threads['uc-other'] = {
      conversationId: 'uc-other',
      events: [
        wireEvent('22222222-2222-4333-8444-555555555550', 'USER_MESSAGE', 'what did the lease say?'),
        wireEvent('22222222-2222-4333-8444-555555555551', 'ASSISTANT_MESSAGE', 'It runs to 2027.'),
      ],
    };
    backend.histories['uc-other'] = {
      sessionId: 'uc-other',
      messages: [],
      excludedMessageIds: ['22222222-2222-4333-8444-555555555550'],
    };
    const el = await mount();
    const rowFor = async (label: string): Promise<HTMLElement | null> => {
      const sidebar = await region(el, 'jf-sv3-sidebar');
      for (const row of [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])]) {
        await (row as Updatable).updateComplete;
        const button = row.shadowRoot?.querySelector<HTMLElement>(
          '[data-testid="sv3-session-row-button"]',
        );
        if ((button?.textContent ?? '').includes(label)) return button ?? null;
      }
      return null;
    };
    (await rowFor('why did the renewal fail?'))?.click();
    await settle(el);

    let release!: () => void;
    backend.excludeGate = new Promise<void>((resolve) => (release = resolve));
    const bar = await region(el, 'jf-sv3-context-bar');
    await press(bar.shadowRoot?.querySelector('[data-testid="sv3-context-include-all"]'));
    await settle(el);

    // Mid-undo, the reader claims the other conversation, whose /history read is still in flight
    // when the undo finishes.
    backend.holdHistoryFor.add('uc-other');
    (await rowFor('what did the lease say?'))?.click();
    await settle(el);
    release();
    await settle(el);
    backend.heldHistories.get('uc-other')?.();
    await settle(el);

    expect(el.sessions.activeId).toBe('uc-other');
    const after = await region(el, 'jf-sv3-context-bar');
    expect(after.shadowRoot?.querySelector('[data-testid="sv3-context-hidden"]')?.textContent).toContain(
      '1 turn hidden from context',
    );
    expect(backend.histories['uc-ctx']?.excludedMessageIds).toEqual([]);
  });
});
