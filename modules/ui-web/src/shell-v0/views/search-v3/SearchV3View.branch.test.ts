// @vitest-environment happy-dom

/**
 * Branch, edit / retry and the version pager in Search v3 (852 S3) — end to end.
 *
 * The backend is FAKE but it is a real one, the same way S2's is: `POST …/branch?fromMsgId=` mints a
 * session whose record really carries the inherited prefix, the conversation list really gains the
 * new row with its parent pointers, `DELETE` really refuses a conversation with children, and every
 * `/history` read is served from state the writes mutated. That is what makes these round-trips
 * rather than click-assertions — a window that navigated optimistically, or that forked from the
 * wrong message, passes a rendering test and fails every case here.
 *
 * The properties asserted as MECHANISMS:
 *  - **An edit forks BEFORE the question it replaces.** The assertion is on the `fromMsgId` that
 *    goes out, against the id that would look entirely plausible (the turn's own answer).
 *  - **The re-send lands in the BRANCH, never in the conversation being replaced.** Asserted on the
 *    conversation id the dispatch carries, because the wrong one appends the rewrite below the old
 *    exchange and looks right on screen.
 *  - **Every mutation re-reads `/history` through the ORDER guard** (S2's F5, fixed here): the fake
 *    can delay one read past another, and the stale answer must not stand.
 *  - **Delete asks about branches.** The store refuses with 409 + the children; the reader is asked,
 *    and declining leaves everything.
 *  - **A turn that names no store message offers no affordance** — the honest null, on the ids these
 *    acts need rather than the ones S2's acts need.
 *
 * EVERY case here fails before this slice: the window imported `branchConversation` nowhere,
 * rendered no pager, had no editor, and called the non-cascade `deleteConversation`. Where a case
 * pins something subtler than "the feature exists", its own comment says what.
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
  // The delegate dispatch (`dispatchRunControl` → `initiate`) — what an agent Retry/Edit must reach.
  send: vi.fn(async (_prompt: string, _effort?: string) => {}),
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
import { __resetContextInspectorDrawer } from '../../state/contextInspectorDrawer.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import { EMPTY_PREFIX_SENTINEL } from '../unifiedChatRequest.js';
import {
  BRANCH_EDIT_WAIT,
  BRANCH_FAILED,
  BRANCH_MENU_BRANCH,
  BRANCH_MENU_RETRY,
  CONTEXT_MENU_RESET,
  DELETE_FAILED,
  VERSION_AT_FIRST,
  VERSION_NEXT,
  VERSION_PAGER_LABEL,
  VERSION_PREVIOUS,
} from './fixtures.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };
type Updatable = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

interface HistoryRecord {
  sessionId: string;
  messages: Array<Record<string, unknown>>;
  parentSessionId?: string;
  branchPointMessageId?: string;
  contextFloor?: string;
}

interface Backend {
  conversations: Array<Record<string, unknown>>;
  threads: Record<string, { conversationId: string; events: unknown[] }>;
  histories: Record<string, HistoryRecord>;
  /** Every `/history` GET, in order — what the re-load assertions count. */
  historyReads: string[];
  /** Every branch POST, in order: which conversation, and the `fromMsgId` it named. */
  branches: Array<{ parent: string; fromMsgId: string }>;
  /** Every dispatch, in order: which conversation it named and what it asked. */
  dispatches: Array<{ conversationId: string; question: string }>;
  /** Every DELETE, in order. */
  deletes: string[];
  /** The fake refuses these acts, so the failure paths run against a real refusal. */
  refuse: Set<'branch' | 'delete'>;
  /**
   * A `/history` read for one of these conversations is HELD until it is released — the controllable
   * latency the request-ordering case needs. Keyed by conversation, value is the release trigger.
   */
  heldHistories: Map<string, () => void>;
  holdHistoryFor: Set<string>;
  /** How many branches have been minted, so each gets its own id. */
  minted: number;
  /** When set, the next branch response waits for this gate — availability can change meanwhile. */
  branchGate: Promise<void> | null;
}
let backend: Backend;

interface Stream {
  emit(event: string, data: unknown): void;
  end(): void;
}
let stream: Stream;

const storedId = (n: number): string => `11111111-2222-4333-8444-55555555555${n}`;

const conversationRow = (
  id: string,
  first: string,
  lineage: { parentSessionId?: string; branchPointMessageId?: string } = {},
): Record<string, unknown> => ({
  sessionId: id,
  createdAtMs: 1,
  lastActiveAtMs: 2,
  messageCount: 4,
  firstUserMessage: first,
  shapeId: 'core.rag-ask',
  ...lineage,
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
      const sent = bodyOf(init);
      backend.dispatches.push({
        conversationId: String(sent.conversationId ?? ''),
        // `core.rag-ask` carries its text as `question` (`unifiedChatRequest.ts:97`).
        question: String(sent.question ?? ''),
      });
      // The re-sent turn REACHES A TERMINAL rather than streaming forever, so the assertions below
      // read a settled transcript — a perpetually-streaming turn would suppress the window-wide
      // controls and quietly weaken every case that follows a re-send.
      stream.end();
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
    const idIn = (tail: string): string =>
      decodeURIComponent(href.slice(0, -tail.length).split('/').pop() ?? '');
    // ── The branch endpoint (slice 513) ─────────────────────────────────────────────────────────
    if (href.includes('/branch?fromMsgId=')) {
      const parent = decodeURIComponent(
        href.slice(0, href.indexOf('/branch?')).split('/').pop() ?? '',
      );
      const fromMsgId = decodeURIComponent(href.slice(href.indexOf('fromMsgId=') + 'fromMsgId='.length));
      backend.branches.push({ parent, fromMsgId });
      if (backend.refuse.has('branch')) return { ok: false, status: 500 };
      if (backend.branchGate !== null) await backend.branchGate;
      backend.minted += 1;
      const child = `${parent}-branch-${backend.minted}`;
      // The child's record REALLY carries the inherited prefix, up to and including `fromMsgId` —
      // which is what makes "the edited turn is gone from the branch" a fact rather than a hope.
      const parentThread = backend.threads[parent]?.events ?? [];
      const cut =
        fromMsgId === EMPTY_PREFIX_SENTINEL
          ? 0
          : parentThread.findIndex((e) => (e as { id?: string }).id === fromMsgId) + 1;
      backend.threads[child] = { conversationId: child, events: parentThread.slice(0, cut) };
      backend.histories[child] = {
        sessionId: child,
        messages: (backend.histories[parent]?.messages ?? []).slice(0, cut),
        parentSessionId: parent,
        branchPointMessageId: fromMsgId,
      };
      backend.conversations = [
        ...backend.conversations,
        conversationRow(child, 'branched', {
          parentSessionId: parent,
          branchPointMessageId: fromMsgId,
        }),
      ];
      return { ok: true, status: 200, json: () => Promise.resolve({ sessionId: child }) };
    }
    if (href.endsWith('/context-floor')) {
      const id = idIn('/context-floor');
      const sent = bodyOf(init);
      const record = backend.histories[id];
      if (record !== undefined) record.contextFloor = String(sent.floorMessageId ?? '');
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/history')) {
      const id = idIn('/history');
      backend.historyReads.push(id);
      const record = backend.histories[id];
      if (record === undefined) return { ok: false, status: 404, json: () => Promise.resolve({}) };
      // SNAPSHOT AT SERVE TIME, before any hold — a real server answers with what it read when it
      // handled the request. Without this a held read would "catch up" to the writes that landed
      // while it waited, and the stale answer the ordering case is about could not exist.
      const snapshot = { ...record };
      // CONTROLLABLE LATENCY. A held read resolves only when the case releases it, which is how a
      // stale answer is made to land AFTER a fresher one.
      if (backend.holdHistoryFor.has(id)) {
        backend.holdHistoryFor.delete(id);
        await new Promise<void>((resolve) => backend.heldHistories.set(id, resolve));
      }
      return { ok: true, status: 200, json: () => Promise.resolve(snapshot) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/title')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true }) };
    }
    // ── Delete, with the slice 515/516 orphan guard the cascade port exists for ──────────────────
    if (href.includes('/api/chat/conversations/') && method === 'DELETE') {
      const id = decodeURIComponent(href.split('/').pop() ?? '');
      backend.deletes.push(id);
      if (backend.refuse.has('delete')) return { ok: false, status: 500 };
      const children = backend.conversations
        .filter((c) => c.parentSessionId === id)
        .map((c) => String(c.sessionId));
      if (children.length > 0) {
        return {
          ok: false,
          status: 409,
          json: () => Promise.resolve({ childSessionIds: children }),
        };
      }
      backend.conversations = backend.conversations.filter((c) => c.sessionId !== id);
      delete backend.threads[id];
      delete backend.histories[id];
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
    branches: [],
    dispatches: [],
    deletes: [],
    refuse: new Set(),
    heldHistories: new Map(),
    holdHistoryFor: new Set(),
    minted: 0,
    branchGate: null,
  };
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  for (const el of [...document.querySelectorAll('jf-context-menu')]) el.remove();
  for (const el of [...document.querySelectorAll('jf-confirm-dialog')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  __resetContextInspectorDrawer();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 12; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
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

/** The two-turn conversation most cases work on: both turns store-minted on both halves. */
function twoTurnConversation(id = 'uc-branch'): void {
  backend.conversations = [conversationRow(id, 'why did the renewal fail?')];
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

/** A sidebar row, found by the label the reader sees (the list carries no id attribute). */
async function sidebarRow(el: Mounted, label: string): Promise<Updatable> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])];
  for (const row of rows) await (row as Updatable).updateComplete;
  const found = rows.find((row) =>
    (row.shadowRoot?.querySelector('[data-testid="sv3-session-row-button"]')?.textContent ?? '')
      .includes(label),
  );
  if (found === undefined) throw new Error(`no sidebar row labelled ${label}`);
  return found as Updatable;
}

/**
 * THREE turns, and the reason there are three: at two, "the previous turn's answer" and "the FIRST
 * turn's answer" are the same message, so an edit forked at either looks identical on the wire. Only
 * a third turn can tell them apart.
 */
function threeTurnConversation(id = 'uc-branch'): void {
  backend.conversations = [conversationRow(id, 'why did the renewal fail?')];
  backend.threads[id] = {
    conversationId: id,
    events: [
      wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?'),
      wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.'),
      wireEvent(storedId(2), 'USER_MESSAGE', 'and the second one?'),
      wireEvent(storedId(3), 'ASSISTANT_MESSAGE', 'The same lock.'),
      wireEvent(storedId(4), 'USER_MESSAGE', 'and the third?'),
      wireEvent(storedId(5), 'ASSISTANT_MESSAGE', 'The lock again.'),
    ],
  };
  backend.histories[id] = {
    sessionId: id,
    messages: [
      { role: 'user', content: 'why did the renewal fail?', id: storedId(0) },
      { role: 'assistant', content: 'The lock held.', id: storedId(1) },
      { role: 'user', content: 'and the second one?', id: storedId(2) },
      { role: 'assistant', content: 'The same lock.', id: storedId(3) },
      { role: 'user', content: 'and the third?', id: storedId(4) },
      { role: 'assistant', content: 'The lock again.', id: storedId(5) },
    ],
  };
}

async function openConversation(el: Mounted, label = 'why did the renewal fail?'): Promise<void> {
  const row = await sidebarRow(el, label);
  row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
  await settle(el);
}

async function discardConversation(el: Mounted, label: string): Promise<void> {
  const row = await sidebarRow(el, label);
  row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-remove"]')?.click();
  await settle(el);
}

const turnsIn = (main: Updatable): HTMLElement[] =>
  [...(main.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-turn"]') ?? [])];

/** Press a `jf-control` / `jf-button` the way a reader does: through the native button inside it. */
async function press(host: Element | null | undefined): Promise<void> {
  // An assertion, not a throw: a control the scenario needs and the window does not offer is the
  // failure under test.
  assert(host, 'press: no control');
  await (host as Updatable).updateComplete;
  const button = host.shadowRoot?.querySelector('button');
  if (button) {
    button.click();
    return;
  }
  // `jf-button` composes `jf-control` rather than rendering its own button (`Button.ts:176-186`),
  // so the dialog's actions are one shadow root deeper than a bare control's.
  const nested = host.shadowRoot?.querySelector('jf-control');
  if (!nested) throw new Error('press: the control rendered no button');
  await press(nested);
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

/** Rewrite a turn's question through the inline editor and send it. */
async function editTurn(el: Mounted, index: number, text: string): Promise<void> {
  const main = await region(el, 'jf-sv3-main');
  await press(turnsIn(main)[index]?.querySelector('[data-testid="sv3-turn-edit"]'));
  await settle(el);
  const input = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-turn-edit-input"]',
  );
  if (!input) throw new Error('no edit input');
  input.value = text;
  input.dispatchEvent(new Event('input'));
  await settle(el);
  const send = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector(
    '[data-testid="sv3-turn-edit-send"]',
  );
  await press(send);
  await settle(el);
}

/** Answer the confirm dialog the cascade raises. */
async function answerConfirm(el: Mounted, accept: boolean): Promise<string> {
  const dialog = document.querySelector('jf-confirm-dialog') as Updatable | null;
  if (!dialog) throw new Error('no confirm dialog');
  await dialog.updateComplete;
  const message = dialog.shadowRoot?.textContent ?? '';
  await press(dialog.shadowRoot?.querySelector(accept ? '.confirm' : '.cancel'));
  await settle(el);
  return message;
}

const toasts = (): string[] => {
  const seen: string[] = [];
  document.addEventListener(EPHEMERAL_TOAST_EVENT, (e) => {
    seen.push(String((e as CustomEvent<{ message?: string }>).detail?.message ?? ''));
  });
  return seen;
};

/* ── Branch here ─────────────────────────────────────────────────────────────────────────────── */

describe('branching a conversation from a turn', () => {
  it('forks at the turn’s OWN answer, opens the branch, and loads both of its records', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);

    // THE FORK POINT. Branch-here continues PAST this exchange, so it names the turn's answer —
    // message 1, not the question (message 0) a floor or an edit would name on the same turn.
    expect(backend.branches).toEqual([{ parent: 'uc-branch', fromMsgId: storedId(1) }]);
    const child = 'uc-branch-branch-1';
    // The window is IN the branch — claimed, not merely created — and reading its records.
    expect(el.sessions.activeId).toBe(child);
    expect(backend.historyReads).toContain(child);
    // And the branch's transcript is the inherited prefix: one turn, the one it forked through.
    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)).toHaveLength(1);
    // The lineage the load carries is what the pager is built on; without it the fork is invisible.
    const opened = el.sessions.sessions.find((s) => s.id === child);
    expect(opened?.history?.parentSessionId).toBe('uc-branch');
    expect(opened?.history?.branchPointMessageId).toBe(storedId(1));
  });

  it('says so and changes nothing when the store refuses the branch', async () => {
    twoTurnConversation();
    backend.refuse.add('branch');
    const el = await mount();
    await openConversation(el);
    const seen = toasts();

    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);

    // The act really REACHED the store and was really refused — without this the assertions below
    // would also pass for a menu entry that never fired.
    expect(backend.branches).toEqual([{ parent: 'uc-branch', fromMsgId: storedId(1) }]);
    // No local fallback: a window that "continued anyway" in the current conversation would do the
    // one thing the reader did not ask for, and the transcript would look entirely plausible.
    expect(el.sessions.activeId).toBe('uc-branch');
    expect(seen).toContain(BRANCH_FAILED);
    expect(backend.dispatches).toEqual([]);
  });
});

/* ── 857 D4 (drafted as 854) — the citation pane belongs to the conversation it was opened against ─ */

describe('branching a conversation closes a citation pane open on the parent', () => {
  it('closes the pane once "Branch" claims the child conversation (route #4 -> openBranch)', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    // The citation-OPEN mechanism is pane.test.ts's concern; here the pane's presence is set up
    // directly on the same four fields `closePane()` nulls, so this case isolates the switch-guard
    // in `openBranch` (shared by the version pager and every branch/retry/edit act) rather than
    // re-proving the citation click already covered elsewhere.
    el.paneDocPath = 'f:/docs/note.md';
    el.paneCitation = { startChar: 0, endChar: 10, excerpt: 'x', sentenceText: null };
    el.paneSource = { turnId: storedId(1), sourceIndex: 0 };
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).not.toBeNull(); // present BEFORE the branch

    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);

    // A REAL switch happened — same assertion the plain branch case above makes — so this is exactly
    // route #4's leaking case, not a no-op branch.
    expect(el.sessions.activeId).toBe('uc-branch-branch-1');
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull(); // absent AFTER
  });
});

/* ── The version pager ───────────────────────────────────────────────────────────────────────── */

describe('the version pager', () => {
  it('shows the base as version 1 of the fork and pages forward into the branch', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);
    // Fork after turn 0's answer, then come back to the base. The fork is now visible FROM THE BASE:
    // its turn 1 and the branch are two continuations of the same exchange.
    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);
    await openConversation(el);

    let main = await region(el, 'jf-sv3-main');
    const counts = [
      ...(main.shadowRoot?.querySelectorAll('[data-testid="sv3-version-count"]') ?? []),
    ].map((n) => n.textContent?.trim());
    // ONE pager, on the turn that actually diverges. A pager keyed on "this conversation has
    // branches" rather than on the fork's own message would put one on every turn.
    expect(counts).toEqual(['1 / 2']);

    await press(main.shadowRoot?.querySelector('[data-testid="sv3-version-next"]'));
    await settle(el);

    // Paging is an OPEN: the other version is claimed and its own records are read, so what comes
    // back is the store's transcript rather than whatever the window still held.
    expect(el.sessions.activeId).toBe('uc-branch-branch-1');
    main = await region(el, 'jf-sv3-main');
    // The branch inherited one turn and has none of its own yet, so the divergence point is not on
    // screen and neither is a pager — the same place the reference window ends up, because both
    // gate it on a turn the conversation OWNS.
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-version-count"]')).toBeNull();
  });

  it('shows a branch as version 2 once it has a turn of its own', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    // An EDIT is the ordinary way a branch acquires an own turn: it forks and re-asks in one act.
    await editTurn(el, 1, 'and what about the third?');

    const main = await region(el, 'jf-sv3-main');
    // Case A of the pager: this conversation IS a branch, and its first own turn is version 2 of the
    // set it belongs to. Read from `/history`'s parent pointers, not from anything the act
    // remembered locally — the window could have navigated here from a reload just as well.
    expect(
      main.shadowRoot?.querySelector('[data-testid="sv3-version-count"]')?.textContent?.trim(),
    ).toBe('2 / 2');
  });

  it('re-edits a branch’s own question as a THIRD version of the base, not a branch of the branch', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);
    await editTurn(el, 1, 'and what about the third?');
    expect(el.sessions.activeId).toBe('uc-branch-branch-1');
    // The branch's own turn, as the store recorded it.
    backend.threads['uc-branch-branch-1']!.events.push(
      wireEvent(storedId(6), 'USER_MESSAGE', 'and what about the third?'),
      wireEvent(storedId(7), 'ASSISTANT_MESSAGE', 'A third lock.'),
    );
    await openConversation(el);
    await openConversation(el, 'branched');
    expect(el.sessions.activeId).toBe('uc-branch-branch-1');

    await editTurn(el, 1, 'and what about the fourth?');

    expect(backend.branches).toEqual([
      { parent: 'uc-branch', fromMsgId: storedId(1) },
      { parent: 'uc-branch', fromMsgId: storedId(1) },
    ]);
    expect(el.sessions.activeId).toBe('uc-branch-branch-2');
    const main = await region(el, 'jf-sv3-main');
    expect(
      main.shadowRoot?.querySelector('[data-testid="sv3-version-count"]')?.textContent?.trim(),
    ).toBe('3 / 3');
  });

  it('renders NO pager on a root conversation nothing forked from', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    // THE ROOT EDGE, live: a conversation with no parent and no children has one version of every
    // turn, and "1 / 1" would be a control that says nothing and moves nowhere.
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-version-count"]')).toBeNull();
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-version-previous"]')).toBeNull();
  });

  it('refuses to step past the first version', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);
    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);
    await openConversation(el);
    const main = await region(el, 'jf-sv3-main');

    // At the FIRST version, Previous is unavailable. Asserting only that nothing moved would pass
    // for a control that is silently inert — the exact thing this window's honest-null rule refuses
    // — so the REASON is asserted too: `jf-control` renders `aria-disabled` on its own button and
    // hangs the reason off it (`Control.ts:571-580`).
    const previous = main.shadowRoot?.querySelector('[data-testid="sv3-version-previous"]');
    await press(previous);
    await settle(el);
    expect(el.sessions.activeId).toBe('uc-branch');
    await (previous as Updatable).updateComplete;
    expect(previous?.shadowRoot?.querySelector('button')?.getAttribute('aria-disabled')).toBe('true');
    expect(previous?.shadowRoot?.textContent).toContain(VERSION_AT_FIRST);

    // The other end is available in the same render, so the assertion above is about THIS end rather
    // than about a pager that is inert all over.
    const next = main.shadowRoot?.querySelector('[data-testid="sv3-version-next"]');
    await (next as Updatable).updateComplete;
    expect(next?.shadowRoot?.querySelector('button')?.getAttribute('aria-disabled')).toBeNull();
    // Both directions name themselves, and the group names what it pages (icon-only controls, so
    // these labels ARE the accessible names rather than decoration).
    expect(next?.getAttribute('label')).toBe(VERSION_NEXT);
    expect(previous?.getAttribute('label')).toBe(VERSION_PREVIOUS);
    expect(
      main.shadowRoot?.querySelector('.version-pager')?.getAttribute('aria-label'),
    ).toBe(VERSION_PAGER_LABEL);
  });
});

/* ── Edit and retry ──────────────────────────────────────────────────────────────────────────── */

describe('editing a question re-asks it in a branch', () => {
  it('forks BEFORE the question, not after its answer, and re-sends into the branch', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    await editTurn(el, 1, 'and what about the third?');

    // THE ASSERTION THIS CASE EXISTS FOR. Turn 1's question is message 3 and its answer is message
    // 4; the fork must be at message 1 — the PREVIOUS answer — so the rewrite is the first divergent
    // message. Forking at 3 or 4 would inherit the exchange being replaced, and the branch would
    // read as the old answer followed by the new question: plausible, and wrong.
    expect(backend.branches).toEqual([{ parent: 'uc-branch', fromMsgId: storedId(1) }]);
    // And the re-send goes to the BRANCH. The conversation id is the whole point: a dispatch naming
    // `uc-branch` would append the rewrite below the exchange the reader was replacing.
    expect(backend.dispatches).toEqual([
      { conversationId: 'uc-branch-branch-1', question: 'and what about the third?' },
    ]);
    expect(el.sessions.activeId).toBe('uc-branch-branch-1');
  });

  it('forks a THIRD turn at the turn before it, not at the conversation’s first answer', async () => {
    threeTurnConversation();
    const el = await mount();
    await openConversation(el);

    await editTurn(el, 2, 'and what about the fourth?');

    // THE CASE A TWO-TURN CONVERSATION CANNOT SEE. Turn 3's question is message 4; the fork must be
    // at message 3 — the answer immediately above it. Message 1 (the conversation's first answer) is
    // the plausible wrong id, and forking there would silently drop turn 2 out of the branch while
    // the transcript still looked like a sensible conversation.
    expect(backend.branches).toEqual([{ parent: 'uc-branch', fromMsgId: storedId(3) }]);
    expect(backend.dispatches).toEqual([
      { conversationId: 'uc-branch-branch-1', question: 'and what about the fourth?' },
    ]);

    // And the branch really inherited TWO turns, which is the same fact seen from the transcript:
    // the re-asked turn plus the two above it.
    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)).toHaveLength(3);
  });

  it('forks the FIRST question at the empty-prefix sentinel', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    await editTurn(el, 0, 'why did the renewal fail, exactly?');

    // Nothing precedes the opening question, and the sentinel is the contract's answer for that
    // (`ConversationStore.EMPTY_PREFIX_SENTINEL`, whose Java doc pins its FE producer to this act —
    // 852 S3 is where that pointer now resolves). The branch inherits an EMPTY prefix.
    expect(backend.branches).toEqual([
      { parent: 'uc-branch', fromMsgId: EMPTY_PREFIX_SENTINEL },
    ]);
    const main = await region(el, 'jf-sv3-main');
    // One turn on screen: the re-asked one. Nothing was inherited.
    expect(turnsIn(main)).toHaveLength(1);
  });

  it('keeps the rewrite in the editor when the branch is refused', async () => {
    twoTurnConversation();
    backend.refuse.add('branch');
    const el = await mount();
    await openConversation(el);
    const seen = toasts();

    await editTurn(el, 1, 'and what about the third?');

    // S2's own lesson, applied to this editor: closing on the PRESS would discard the reader's
    // rewrite exactly when the write was refused and it is hardest to reproduce. The editor answers
    // to the transcript instead — and the transcript did not change, so it stays open with the text.
    const main = await region(el, 'jf-sv3-main');
    const input = main.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-turn-edit-input"]',
    );
    expect(input?.value).toBe('and what about the third?');
    expect(seen).toContain(BRANCH_FAILED);
    // The fork was really ATTEMPTED at the right point and really refused; an editor that never
    // raised the act would leave the text sitting there too.
    expect(backend.branches).toEqual([{ parent: 'uc-branch', fromMsgId: storedId(1) }]);
    expect(backend.dispatches).toEqual([]);
  });

  it('holds the rewrite and says why when a stream starts under the open editor', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    // Open the editor, then start a prompt from the composer underneath it. The Edit TRIGGER is
    // withheld while streaming, but an editor already open is the gap that gate cannot see.
    const main = await region(el, 'jf-sv3-main');
    await press(turnsIn(main)[1]?.querySelector('[data-testid="sv3-turn-edit"]'));
    await settle(el);
    // A REWRITE IS TYPED before the stream starts, so "survives the wait" is a claim about the
    // reader's own text rather than about an empty box that would look the same either way.
    const typed = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-turn-edit-input"]',
    );
    if (!typed) throw new Error('no edit input');
    typed.value = 'and what about the third?';
    typed.dispatchEvent(new Event('input'));
    await settle(el);
    (el as unknown as { streaming: boolean }).streaming = true;
    await settle(el);

    const send = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector(
      '[data-testid="sv3-turn-edit-send"]',
    );
    await press(send);
    await settle(el);

    // Nothing forked, and the control is UNAVAILABLE-with-a-reason rather than silently inert: a
    // Send that quietly does nothing is exactly the "control that fails when pressed" this window's
    // honest-null rule refuses, and the rewrite must survive the wait.
    expect(backend.branches).toEqual([]);
    // The REASON is what distinguishes this from a control that silently no-ops: `jf-control` renders
    // `aria-disabled` on its own button and hangs the reason off it (`Control.ts:571-580`), so the
    // refusal is reachable rather than merely effective.
    await (send as Updatable).updateComplete;
    const button = send?.shadowRoot?.querySelector('button');
    expect(button?.getAttribute('aria-disabled')).toBe('true');
    expect(send?.shadowRoot?.textContent).toContain(BRANCH_EDIT_WAIT);
    const input = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-turn-edit-input"]',
    );
    expect(input?.value).toBe('and what about the third?');

    // The KEYBOARD path agrees with the button. Ctrl+Enter reaches the same act, and a shortcut that
    // fired while the control beside it explained why it could not would be the same refusal told
    // two different ways.
    input?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }),
    );
    await settle(el);
    expect(backend.branches).toEqual([]);
  });

  it('retries a turn by re-sending its ORIGINAL question from the same fork point', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    await chooseFromTurnMenu(el, 1, BRANCH_MENU_RETRY);

    // Retry is an edit that changed nothing — the reference's own construction (there is no retry
    // endpoint). Same fork point, and the question goes out unchanged.
    expect(backend.branches).toEqual([{ parent: 'uc-branch', fromMsgId: storedId(1) }]);
    expect(backend.dispatches).toEqual([
      { conversationId: 'uc-branch-branch-1', question: 'and the second one?' },
    ]);
  });
});

/* ── The honest null ─────────────────────────────────────────────────────────────────────────── */

describe('a turn that names no store message offers no branch affordance', () => {
  it('offers neither Edit nor Retry on a turn whose fork point is a run-plane id', async () => {
    const id = 'uc-agent';
    backend.conversations = [conversationRow(id, 'delegate this')];
    backend.threads[id] = {
      conversationId: id,
      events: [
        // The run plane's own mints (`${runId}:user`, `${conversationId}:assistant:${stamp}`) —
        // messages that exist in no conversation store and that `?fromMsgId=` would reject.
        wireEvent('run-7:user', 'USER_MESSAGE', 'delegate this'),
        wireEvent('uc-agent:assistant:1731', 'ASSISTANT_MESSAGE', 'done'),
        wireEvent(storedId(3), 'USER_MESSAGE', 'and the second one?'),
        wireEvent(storedId(4), 'ASSISTANT_MESSAGE', 'The same lock.'),
      ],
    };
    backend.histories[id] = { sessionId: id, messages: [] };
    const el = await mount();
    await openConversation(el, 'delegate this');
    const main = await region(el, 'jf-sv3-main');
    const turns = turnsIn(main);

    // The agent turn offers no pencil — its question is not a store message.
    expect(turns[0]?.querySelector('[data-testid="sv3-turn-edit"]')).toBeNull();
    // NOR DOES THE TURN AFTER IT, which is the subtler half: its own ids are fine, but the message
    // it would fork BEFORE is the run plane's, so an Edit here would 404 when pressed. The honest
    // form of that is no control — the same rule S1 set for ids and S2 applied to the context acts.
    expect(turns[1]?.querySelector('[data-testid="sv3-turn-edit"]')).toBeNull();

    // Branch-here survives on turn 1, because it needs only that turn's OWN answer. Two acts, two
    // gates, on the two ids they actually use.
    await press(turns[1]?.querySelector('[data-testid="sv3-turn-context-menu"]'));
    await settle(el);
    const menu = document.querySelector('jf-context-menu') as Updatable | null;
    await menu?.updateComplete;
    const labels = [
      ...(menu?.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.item') ?? []),
    ].map((b) => (b.textContent ?? '').trim());
    expect(labels).toContain(BRANCH_MENU_BRANCH);
    expect(labels).not.toContain(BRANCH_MENU_RETRY);
  });
});

/* ── A Delegate turn re-runs as a Delegate (WP1.2 S2.5 / S2.6) ─────────────────────────────────── */

const AGENT = { shapeId: 'core.agent-run' };
const ASK = { shapeId: 'core.rag-ask' };

/**
 * An ask turn, then a Delegate turn that called NO tool — its kind is known only from the record's
 * per-message `shapeId` (863 F1), which is the case the old activity inference read as an ask.
 */
function askThenDelegate(id = 'uc-delegate'): void {
  backend.conversations = [conversationRow(id, 'why did the renewal fail?')];
  backend.threads[id] = {
    conversationId: id,
    events: [
      wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?', ASK),
      wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.', ASK),
      wireEvent(storedId(3), 'USER_MESSAGE', 'draft the renewal letter', AGENT),
      wireEvent(storedId(4), 'ASSISTANT_MESSAGE', 'Drafted.', AGENT),
    ],
  };
  backend.histories[id] = {
    sessionId: id,
    messages: [
      { role: 'user', content: 'why did the renewal fail?', id: storedId(0) },
      { role: 'assistant', content: 'The lock held.', id: storedId(1) },
      { role: 'user', content: 'draft the renewal letter', id: storedId(3) },
      { role: 'assistant', content: 'Drafted.', id: storedId(4) },
    ],
  };
}

/** A conversation whose FIRST turn is a Delegate turn. */
function delegateFirst(id = 'uc-delegate'): void {
  backend.conversations = [conversationRow(id, 'draft the renewal letter')];
  backend.threads[id] = {
    conversationId: id,
    events: [
      wireEvent(storedId(0), 'USER_MESSAGE', 'draft the renewal letter', AGENT),
      wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'Drafted.', AGENT),
    ],
  };
  backend.histories[id] = {
    sessionId: id,
    messages: [
      { role: 'user', content: 'draft the renewal letter', id: storedId(0) },
      { role: 'assistant', content: 'Drafted.', id: storedId(1) },
    ],
  };
}

const menuLabels = async (el: Mounted, index: number): Promise<string[]> => {
  const main = await region(el, 'jf-sv3-main');
  await press(turnsIn(main)[index]?.querySelector('[data-testid="sv3-turn-context-menu"]'));
  await settle(el);
  const menu = document.querySelector('jf-context-menu') as Updatable | null;
  await menu?.updateComplete;
  const labels = [
    ...(menu?.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.item') ?? []),
  ].map((b) => (b.textContent ?? '').trim());
  menu?.remove();
  return labels;
};

describe('Retry and Edit on a Delegate turn run the agent again, in a new version', () => {
  beforeEach(() => {
    ctrl.send.mockClear();
    ctrl.runInFlight = false;
  });

  it('retries a tool-less Delegate turn as a Delegate, after reload, while the composer shows Ask', async () => {
    askThenDelegate();
    const el = await mount();
    await openConversation(el);
    expect((el as unknown as { tier: string }).tier).toBe('ask');
    const original = JSON.stringify(backend.threads['uc-delegate']);

    await chooseFromTurnMenu(el, 1, BRANCH_MENU_RETRY);

    // Forked where an Ask retry forks — before the question, after the previous answer…
    expect(backend.branches).toEqual([{ parent: 'uc-delegate', fromMsgId: storedId(1) }]);
    // …and the AGENT ran, in the branch, with the original text. No Ask went out.
    expect(ctrl.send).toHaveBeenCalledTimes(1);
    expect(ctrl.send.mock.calls[0]?.[0]).toBe('draft the renewal letter');
    expect(backend.dispatches).toEqual([]);
    expect(el.sessions.activeId).toBe('uc-delegate-branch-1');
    expect(ctrl.conversationId).toBe('uc-delegate-branch-1');
    // The conversation the retry came from is untouched.
    expect(JSON.stringify(backend.threads['uc-delegate'])).toBe(original);
  });

  it('edits the FIRST question of a Delegate conversation at the empty-prefix sentinel', async () => {
    delegateFirst();
    const el = await mount();
    await openConversation(el, 'draft the renewal letter');

    await editTurn(el, 0, 'draft a shorter renewal letter');

    expect(backend.branches).toEqual([
      { parent: 'uc-delegate', fromMsgId: EMPTY_PREFIX_SENTINEL },
    ]);
    expect(ctrl.send.mock.calls.map((c) => c[0])).toEqual(['draft a shorter renewal letter']);
    expect(backend.dispatches).toEqual([]);
  });

  it('runs even when no documents are indexed — the Ask-only gate does not apply', async () => {
    askThenDelegate();
    __feedForTest({
      inference: { mode: 'online', available: true, llmContextTokens: 4096 } as never,
      status: { worker: { core: { indexedDocuments: 0 } } } as unknown as StatusSnapshot,
    });
    __feedContactForTest();
    const el = await mount();
    await openConversation(el);

    await editTurn(el, 1, 'draft a shorter renewal letter');

    expect(ctrl.send.mock.calls.map((c) => c[0])).toEqual(['draft a shorter renewal letter']);
  });

  it('re-edits a branch’s own Delegate turn as a SIBLING of that branch', async () => {
    askThenDelegate('uc-root');
    // `uc-child` was forked after the root's first answer; its own first turn is a Delegate turn.
    backend.conversations.push(
      conversationRow('uc-child', 'branched', {
        parentSessionId: 'uc-root',
        branchPointMessageId: storedId(1),
      }),
    );
    backend.threads['uc-child'] = {
      conversationId: 'uc-child',
      events: [
        wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?', ASK),
        wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.', ASK),
        wireEvent(storedId(6), 'USER_MESSAGE', 'draft the letter', AGENT),
        wireEvent(storedId(7), 'ASSISTANT_MESSAGE', 'Drafted.', AGENT),
      ],
    };
    backend.histories['uc-child'] = {
      sessionId: 'uc-child',
      messages: [],
      parentSessionId: 'uc-root',
      branchPointMessageId: storedId(1),
    };
    const el = await mount();
    await openConversation(el, 'branched');

    // The inherited Ask turn is the parent's: no Retry/Edit there.
    expect(turnsIn(await region(el, 'jf-sv3-main'))[0]?.querySelector('[data-testid="sv3-turn-edit"]')).toBeNull();

    await editTurn(el, 1, 'draft a formal letter');

    // The fork key of a branch's first own turn is the branch point it was itself forked at, and the
    // fork is taken from the conversation it was forked FROM — a sibling, not a branch of the branch.
    expect(backend.branches).toEqual([{ parent: 'uc-root', fromMsgId: storedId(1) }]);
    expect(ctrl.send.mock.calls.map((c) => c[0])).toEqual(['draft a formal letter']);
  });

  it('re-edits a retry’s own first turn as a third version of the original, not a branch of the retry', async () => {
    delegateFirst();
    // `uc-delegate-retry` is a Retry of the first question: an empty-prefix fork of the original,
    // whose own first turn the agent answered — reopened after a reload.
    backend.conversations.push(
      conversationRow('uc-delegate-retry', 'retried', {
        parentSessionId: 'uc-delegate',
        branchPointMessageId: EMPTY_PREFIX_SENTINEL,
      }),
    );
    backend.threads['uc-delegate-retry'] = {
      conversationId: 'uc-delegate-retry',
      events: [
        wireEvent(storedId(6), 'USER_MESSAGE', 'draft the renewal letter', AGENT),
        wireEvent(storedId(7), 'ASSISTANT_MESSAGE', 'Drafted again.', AGENT),
      ],
    };
    backend.histories['uc-delegate-retry'] = {
      sessionId: 'uc-delegate-retry',
      messages: [],
      parentSessionId: 'uc-delegate',
      branchPointMessageId: EMPTY_PREFIX_SENTINEL,
    };
    const el = await mount();
    await openConversation(el, 'retried');
    let main = await region(el, 'jf-sv3-main');
    expect(
      main.shadowRoot?.querySelector('[data-testid="sv3-version-count"]')?.textContent?.trim(),
    ).toBe('2 / 2');

    await editTurn(el, 0, 'draft a shorter renewal letter');

    // Forked from the ORIGINAL at the empty prefix — a sibling of the retry, not a child of it.
    expect(backend.branches).toEqual([{ parent: 'uc-delegate', fromMsgId: EMPTY_PREFIX_SENTINEL }]);
    expect(ctrl.send.mock.calls.map((c) => c[0])).toEqual(['draft a shorter renewal letter']);
    expect(backend.dispatches).toEqual([]);
    expect(el.sessions.activeId).toBe('uc-delegate-branch-1');
    backend.threads['uc-delegate-branch-1'] = {
      conversationId: 'uc-delegate-branch-1',
      events: [
        wireEvent(storedId(8), 'USER_MESSAGE', 'draft a shorter renewal letter', AGENT),
        wireEvent(storedId(9), 'ASSISTANT_MESSAGE', 'Drafted, shorter.', AGENT),
      ],
    };
    // All three versions of the first question, paged from the original…
    await openConversation(el, 'draft the renewal letter');
    main = await region(el, 'jf-sv3-main');
    expect(
      main.shadowRoot?.querySelector('[data-testid="sv3-version-count"]')?.textContent?.trim(),
    ).toBe('1 / 3');
    // …and the original is unchanged.
    expect(backend.threads['uc-delegate']?.events).toHaveLength(2);
  });

  it('keeps Ask turns re-running as Ask, even when the composer shows Delegate', async () => {
    askThenDelegate();
    const el = await mount();
    await openConversation(el);
    (el as unknown as { tier: string }).tier = 'delegate';

    await chooseFromTurnMenu(el, 0, BRANCH_MENU_RETRY);

    expect(backend.dispatches).toEqual([
      { conversationId: 'uc-delegate-branch-1', question: 'why did the renewal fail?' },
    ]);
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('branches from a Delegate turn without running anything, and leaves the composer in Delegate', async () => {
    askThenDelegate();
    const el = await mount();
    await openConversation(el);

    await chooseFromTurnMenu(el, 1, BRANCH_MENU_BRANCH);

    expect(backend.branches).toEqual([{ parent: 'uc-delegate', fromMsgId: storedId(4) }]);
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(backend.dispatches).toEqual([]);
    expect((el as unknown as { tier: string }).tier).toBe('delegate');
    const composer = await region(el, 'jf-sv3-composer');
    expect(composer.getAttribute('tier')).toBe('delegate');
  });
});

describe('when the agent cannot run, a Delegate Retry/Edit says why and never asks instead', () => {
  beforeEach(() => {
    ctrl.send.mockClear();
    ctrl.runInFlight = false;
  });

  it('refuses while the model is unavailable, keeps the edited text, and forks nothing', async () => {
    askThenDelegate();
    const el = await mount();
    await openConversation(el);
    __feedForTest({
      inference: { mode: 'unknown', available: false } as never,
      status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
    });
    __feedContactForTest();
    await settle(el);
    const seen = toasts();

    await editTurn(el, 1, 'draft a shorter renewal letter');

    expect(backend.branches).toEqual([]);
    expect(backend.dispatches).toEqual([]);
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(seen).toHaveLength(1);
    expect(seen[0]).not.toBe('');
    const input = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-turn-edit-input"]',
    );
    expect(input?.value).toBe('draft a shorter renewal letter');
  });

  it('keeps the edited text and says why when the agent becomes unavailable during the fork', async () => {
    askThenDelegate();
    const el = await mount();
    await openConversation(el);
    let release!: () => void;
    backend.branchGate = new Promise<void>((resolve) => (release = resolve));
    const seen = toasts();

    // Edit is pressed with the agent available; the fork is still in flight…
    await editTurn(el, 1, 'draft a shorter renewal letter');
    expect(backend.branches).toEqual([{ parent: 'uc-delegate', fromMsgId: storedId(1) }]);
    // …when chat becomes unavailable, and only then does the fork land.
    __feedForTest({
      inference: { mode: 'unknown', available: false } as never,
      status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
    });
    __feedContactForTest();
    await settle(el);
    release();
    await settle(el);

    // Neither the agent nor an Ask ran.
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(backend.dispatches).toEqual([]);
    // The reader is told why…
    expect(seen).toHaveLength(1);
    expect(seen[0]).not.toBe('');
    // …and the edited text waits in the new version's composer, still a Delegate.
    expect(el.sessions.activeId).toBe('uc-delegate-branch-1');
    const composer = (await region(el, 'jf-sv3-composer')) as Updatable & { draft: string };
    expect(composer.draft).toBe('draft a shorter renewal letter');
    expect(composer.getAttribute('tier')).toBe('delegate');
  });

  it('refuses while another run is active, and says so', async () => {
    askThenDelegate();
    const el = await mount();
    await openConversation(el);
    ctrlExists = true;
    ctrl.runInFlight = true;
    const seen = toasts();

    await chooseFromTurnMenu(el, 1, BRANCH_MENU_RETRY);

    expect(backend.branches).toEqual([]);
    expect(backend.dispatches).toEqual([]);
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(seen).toHaveLength(1);
    expect(seen[0]).toMatch(/run/i);
  });

  it('says the branch failed and runs nothing when the store refuses the fork', async () => {
    askThenDelegate();
    backend.refuse.add('branch');
    const el = await mount();
    await openConversation(el);
    const seen = toasts();

    await editTurn(el, 1, 'draft a shorter renewal letter');

    expect(seen).toContain(BRANCH_FAILED);
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(backend.dispatches).toEqual([]);
    const input = (await region(el, 'jf-sv3-main')).shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-turn-edit-input"]',
    );
    expect(input?.value).toBe('draft a shorter renewal letter');
  });

  it('gives an INHERITED Delegate turn no Retry or Edit — it belongs to the parent', async () => {
    delegateFirst('uc-root');
    backend.conversations.push(
      conversationRow('uc-child', 'branched', {
        parentSessionId: 'uc-root',
        branchPointMessageId: storedId(1),
      }),
    );
    backend.threads['uc-child'] = {
      conversationId: 'uc-child',
      events: [
        wireEvent(storedId(0), 'USER_MESSAGE', 'draft the renewal letter', AGENT),
        wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'Drafted.', AGENT),
        wireEvent(storedId(6), 'USER_MESSAGE', 'and the cover note?', ASK),
        wireEvent(storedId(7), 'ASSISTANT_MESSAGE', 'Here it is.', ASK),
      ],
    };
    backend.histories['uc-child'] = {
      sessionId: 'uc-child',
      messages: [],
      parentSessionId: 'uc-root',
      branchPointMessageId: storedId(1),
    };
    const el = await mount();
    await openConversation(el, 'branched');
    const main = await region(el, 'jf-sv3-main');
    expect(turnsIn(main)[0]?.querySelector('[data-testid="sv3-turn-edit"]')).toBeNull();
    expect(await menuLabels(el, 0)).not.toContain(BRANCH_MENU_RETRY);
  });
});

/* ── Cascade-aware delete ────────────────────────────────────────────────────────────────────── */

describe('deleting a conversation that has branches', () => {
  it('asks about the branches, names them, and deletes them with it on consent', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);
    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);
    await discardConversation(el, 'why did the renewal fail?');

    // THE ROW IS STILL THERE while the question is on screen. Asking "delete this and its branches?"
    // about a row that has already vanished behind the dialog asks about something the reader can no
    // longer see — which is what the optimistic removal this act replaced did.
    expect(el.sessions.sessions.map((s) => s.id)).toContain('uc-branch');

    // THE PORT. The store refuses with 409 + the children (the 515/516 orphan guard); before this
    // slice the window called the non-cascade function, which reports that as a bare `false` — the
    // row vanished, the conversation stayed on disk, and nothing was said.
    const message = await answerConfirm(el, true);
    // It NAMES what will go, by the label the reader knows the branch by rather than by its id —
    // the whole value of the second question is that it can be answered without guessing.
    expect(message).toContain('has 1 branch forked from it');
    expect(message).toContain('• branched');
    expect(message).toContain('"why did the renewal fail?"');
    // Children first, then the parent retried — the store function's own order, exercised.
    expect(backend.deletes).toEqual(['uc-branch', 'uc-branch-branch-1', 'uc-branch']);
    expect(backend.conversations).toEqual([]);
    // Only NOW do the rows go — parent and children together, because the store says both are gone.
    expect(el.sessions.sessions.map((s) => s.id)).not.toContain('uc-branch');
    expect(el.sessions.sessions.map((s) => s.id)).not.toContain('uc-branch-branch-1');
  });

  it('promises ONE level, because a branch that was forked again is not deleted', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);
    // A three-level lineage: base → branch-1 → grandchild. The first level is forked for real; the
    // third is seeded straight into the store, because what is under test is what DELETE does with
    // the lineage, and the store's rows are the only thing the delete path reads. (Forking the
    // branch again through the UI would need it to have a turn of its own first — a different act.)
    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);
    backend.conversations = [
      ...backend.conversations,
      conversationRow('uc-grandchild', 'forked again', {
        parentSessionId: 'uc-branch-branch-1',
        branchPointMessageId: storedId(1),
      }),
    ];
    const seen = toasts();

    await discardConversation(el, 'why did the renewal fail?');
    const message = await answerConfirm(el, true);

    // THE COPY MATCHES THE BEHAVIOUR. "Deletes those branches too" would promise a depth this act
    // refuses to perform, and the reader would be told a deletion happened that did not.
    expect(message).toContain('A branch that has been forked again is not deleted');
    // And it really does not: the child's own delete is refused, the cascade aborts, the parent is
    // retried nowhere, and everything is still on the store.
    expect(seen).toContain(DELETE_FAILED);
    expect(backend.conversations.map((c) => c.sessionId)).toEqual([
      'uc-branch',
      'uc-branch-branch-1',
      'uc-grandchild',
    ]);
    expect(el.sessions.sessions.map((s) => s.id)).toContain('uc-branch');
  });

  it('leaves everything when the reader declines, and says nothing', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);
    await chooseFromTurnMenu(el, 0, BRANCH_MENU_BRANCH);
    const seen = toasts();

    await discardConversation(el, 'why did the renewal fail?');
    await answerConfirm(el, false);

    // Nothing was deleted, and the reader is NOT told an act failed — they were asked and said no.
    // Only the parent's own refused DELETE was ever issued.
    expect(backend.deletes).toEqual(['uc-branch']);
    expect(backend.conversations.map((c) => c.sessionId)).toEqual([
      'uc-branch',
      'uc-branch-branch-1',
    ]);
    expect(seen).not.toContain(DELETE_FAILED);
    // The row never left, so there is nothing to put back. The window never claims a deletion that
    // did not happen — and no longer has to un-claim one either.
    expect(el.sessions.sessions.map((s) => s.id)).toContain('uc-branch');
    expect(el.sessions.sessions.map((s) => s.id)).toContain('uc-branch-branch-1');
  });

  it('says so when the store refuses the delete outright', async () => {
    twoTurnConversation();
    backend.refuse.add('delete');
    const el = await mount();
    await openConversation(el);
    const seen = toasts();

    await discardConversation(el, 'why did the renewal fail?');

    // A plain refusal is NOT the declined case and must not borrow its silence — the reader pressed
    // delete, nothing was deleted, and only one of those two facts is on screen without this.
    expect(document.querySelector('jf-confirm-dialog')).toBeNull();
    expect(seen).toContain(DELETE_FAILED);
    expect(backend.deletes).toEqual(['uc-branch']);
    expect(el.sessions.sessions.map((s) => s.id)).toContain('uc-branch');
  });
});

/* ── The request-ordering guard (S2's F5) ────────────────────────────────────────────────────── */

describe('two reloads of the same conversation cannot land out of order', () => {
  it('discards the SUPERSEDED /history answer however late it arrives', async () => {
    twoTurnConversation();
    const el = await mount();
    await openConversation(el);

    // Two mutations of the same conversation, back to back — which branch and edit make ordinary:
    // every act is a write followed by a reload, and the reader can press twice inside one round
    // trip. The FIRST reload is held; the second runs to completion; then the first is released.
    backend.holdHistoryFor.add('uc-branch');
    await chooseFromTurnMenu(el, 0, CONTEXT_MENU_RESET);
    await chooseFromTurnMenu(el, 1, CONTEXT_MENU_RESET);
    backend.heldHistories.get('uc-branch')?.();
    await settle(el);

    // The floor the reader set LAST is the one that stands. Before the order guard the stale answer
    // — a snapshot taken when the floor was still message 0 — landed after the fresh one and put the
    // window back a step, with the backend holding something else entirely. The session guard S1
    // shipped cannot see this: both answers name the conversation on screen.
    expect(backend.histories['uc-branch']?.contextFloor).toBe(storedId(3));
    expect(el.sessions.sessions.find((s) => s.id === 'uc-branch')?.history?.contextFloor).toBe(
      storedId(3),
    );
  });
});
