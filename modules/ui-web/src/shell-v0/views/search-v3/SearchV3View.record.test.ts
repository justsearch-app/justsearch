// @vitest-environment happy-dom

/**
 * Search v3 on the product's RECORD (tempdoc 822 Phase F6 — inventory A1, A3, A4, D1, D2, D3,
 * A13-abort, A14, A15, G11).
 *
 * The shared authorities are the REAL ones — the conversation store, the thread client + projector,
 * `DraftPersistence`, `draftKeptHint`, `aiStateStore` — stubbed only at their single exit, `fetch`,
 * routed by URL. Mocking the store would have made every case here a test of the mock; the point of
 * the slice is that this window stopped being an authority, and that is only observable against the
 * authorities themselves.
 *
 * The properties asserted as MECHANISMS:
 *  - **A reload projects the list back.** The window is unmounted and a FRESH one is mounted — the
 *    in-memory list is genuinely gone, so a case that passed would have to be reading the store.
 *  - **The record wins on settle; the live turn does not.** Both directions, in one case.
 *  - **A failed refresh is said, and says something DIFFERENT from empty.** The two copies are
 *    compared, so wording them the same would fail.
 *  - **A late delta after New session appends nothing.** The stream is left open and fed AFTER the
 *    new-session act — the mutation probe, not the presence of an abort call.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';

let ctrlExists = false;
let agentListener: (() => void) | null = null;
const reattach = vi.fn(async () => {});
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
  reattachActiveRunOnLoad: reattach,
};

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => {
    ctrlExists = true;
    return ctrl as unknown as AgentSessionController;
  },
  peekAgentSessionController: () =>
    ctrlExists ? (ctrl as unknown as AgentSessionController) : null,
  subscribeAgentSession: (listener: () => void) => {
    agentListener = listener;
    return () => {
      agentListener = null;
    };
  },
}));

import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
  getAiState,
  setAiActivity,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import {
  __resetConversationListForTest,
  getConversationListState,
  loadConversations,
  setActiveConversation,
} from '../../state/conversationListStore.js';
import { __draftStorageKey, __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import { MAIN_EMPTY, RECORD_UNREACHABLE, SV3_DRAFT_KEY, TURN_HALTED } from './fixtures.js';
// Tempdoc 852 §2.3b — the ONE turn↔message-id lookup, so the case below resolves the companion
// record's floor the way a ported affordance will, rather than re-deciding it here.
import { sv3TurnByMessageId } from './sv3-sessions.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };
/** Any Lit element inside the window — a region, a row. Not the window itself. */
type Updatable = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** What the fake backend currently holds, per exit. Cases set these before mounting. */
interface Backend {
  conversations: Array<Record<string, unknown>>;
  threads: Record<string, { conversationId: string; events: unknown[] } | 'fail'>;
  /** Tempdoc 852 S1 — what `GET …/{id}/history` answers; `'locked'` is the 423. */
  histories: Record<string, Record<string, unknown> | 'locked'>;
  /** Tempdoc 834 §5.1 — what `GET /api/chat/runs/live` answers, newest-first. */
  liveRuns: Array<Record<string, unknown>>;
}
let backend: Backend;

/** The open dispatch stream, so a case can feed a delta at a moment of its choosing. */
interface Stream {
  emit(event: string, data: unknown): void;
  end(): void;
}
let stream: Stream;

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
    if (href.includes('/api/chat/dispatch')) {
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
    // Tempdoc 838 — a name is a fact the BACKEND holds now, so the fake one holds it too: the write
    // lands on the row the list then serves, which is what makes the reload assertions below a test
    // of durability rather than of a browser-local cache.
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ runs: backend.liveRuns }) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/title')) {
      const id = decodeURIComponent(href.slice(0, -'/title'.length).split('/').pop() ?? '');
      const row = backend.conversations.find((c) => c.sessionId === id);
      if (row === undefined) return { ok: false, status: 404, json: () => Promise.resolve({}) };
      const sent = JSON.parse(String((init as { body?: unknown }).body ?? '{}'));
      row.title = sent.title;
      row.titleSource = sent.source;
      return { ok: true, status: 200, json: () => Promise.resolve({ ok: true, ...sent }) };
    }
    // Tempdoc 852 S1 — the `/history` COMPANION record, the one the shipped window reads beside the
    // thread. It carries what the thread endpoint structurally cannot (branch lineage, the context
    // floor, the two exclusion ledgers), so the fake backend serves it as its own exit; falling
    // through to the conversations LIST below would have made the case a test of the wrong payload.
    if (href.includes('/api/chat/conversations') && href.endsWith('/history')) {
      const id = decodeURIComponent(href.slice(0, -'/history'.length).split('/').pop() ?? '');
      const record = backend.histories[id];
      if (record === undefined) return { ok: false, status: 404, json: () => Promise.resolve({}) };
      if (record === 'locked') return { ok: false, status: 423, json: () => Promise.resolve({}) };
      // A per-conversation delay, so a case can make two loads land OUT OF ORDER — the only way to
      // reproduce a superseded companion load reclaiming the shared active-conversation pointer.
      const delayMs = typeof record.__delayMs === 'number' ? record.__delayMs : 0;
      if (delayMs > 0) await new Promise<void>((resolve) => setTimeout(resolve, delayMs));
      return { ok: true, status: 200, json: () => Promise.resolve(record) };
    }
    if (href.includes('/api/chat/conversations')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ sessions: backend.conversations }) };
    }
    if (href.includes('/api/thread/')) {
      const id = decodeURIComponent(href.slice(href.lastIndexOf('/api/thread/') + '/api/thread/'.length));
      const record = backend.threads[id];
      if (record === undefined || record === 'fail') return { ok: false, status: 500 };
      return { ok: true, status: 200, json: () => Promise.resolve(record) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ results: [] }) };
  });
}

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

let clock = 0;
const wireEvent = (
  id: string,
  kind: string,
  content: string,
  attributes: Record<string, unknown> = {},
): Record<string, unknown> => ({
  id,
  occurredAt: new Date(Date.parse('2026-08-13T10:00:00Z') + clock++ * 1000).toISOString(),
  kind,
  originator: 'agent',
  content,
  attributes,
});

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  ctrlExists = false;
  agentListener = null;
  reattach.mockClear();
  Object.assign(ctrl, {
    conversation: [],
    isStreaming: false,
    runInFlight: false,
    conversationId: null,
    sessionId: null,
  });
  clock = 0;
  backend = { conversations: [], threads: {}, histories: {}, liveRuns: [] };
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  // WP1.1 — a fresh window starts in Search mode; these cases are about Ask, so they choose it.
  (el as unknown as { tier: string }).tier = 'ask';
  await el.updateComplete;
  await settle(el);
  return el;
}

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function region(el: Mounted, tag: string): Promise<Updatable> {
  const found = el.shadowRoot?.querySelector(tag) as Updatable | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

async function type(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
}

async function send(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  const composer = await region(el, 'jf-sv3-composer');
  composer.shadowRoot
    ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
    ?.click();
  await settle(el);
}

/** Ask and let the answer land, so the conversation comes to rest. */
async function ask(el: Mounted, draft: string): Promise<void> {
  await send(el, draft);
  stream.emit('chunk', { text: 'An answer.' });
  stream.emit('done', {});
  stream.end();
  await settle(el);
}

const rowLabels = async (el: Mounted): Promise<string[]> => {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])] as Updatable[];
  await Promise.all(rows.map((r) => r.updateComplete));
  return rows.map((r) => r.shadowRoot?.querySelector('.row-label')?.textContent?.trim() ?? '');
};

const q = (el: Updatable, id: string): HTMLElement | null =>
  el.shadowRoot?.querySelector<HTMLElement>(`[data-testid="${id}"]`) ?? null;


describe('a v3 session IS a conversation in the app-wide store (A1 + A4)', () => {
  it('mints the conversation through the store and stamps the dispatch with it', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    // The store's own id format — this window mints none of its own any more.
    expect(id.startsWith('uc-')).toBe(true);
    const dispatch = fetchMock.mock.calls.find((c) => String(c[0]).includes('/api/chat/dispatch'));
    expect(JSON.parse((dispatch?.[1] as { body: string }).body).conversationId).toBe(id);
  });

  it('projects the same conversations back into a FRESH window — the reload round-trip', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    expect(await rowLabels(el)).toEqual(['why did the renewal fail?']);

    // The backend now holds the conversation the window opened...
    backend.conversations = [
      {
        sessionId: id,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: 'why did the renewal fail?',
        shapeId: 'core.rag-ask',
      },
    ];
    // ...and the window is DESTROYED, so its in-memory list is genuinely gone.
    el.remove();
    const reloaded = await mount();
    expect(reloaded.sessions.sessions.map((s) => s.id)).toEqual([id]);
    expect(await rowLabels(reloaded)).toEqual(['why did the renewal fail?']);
  });

  it('writes a rename THROUGH to the store, so it survives the window', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    // The conversation exists server-side from its first appended message, which the ask above just
    // made — so the rename below is a write against a row the backend already has.
    backend.conversations = [
      {
        sessionId: id,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: 'why did the renewal fail?',
        shapeId: 'core.rag-ask',
      },
    ];
    el.shadowRoot
      ?.querySelector('jf-sv3-sidebar')
      ?.dispatchEvent(
        new CustomEvent('sv3-session-rename', {
          detail: { id, phase: 'commit', title: 'Lease terms' },
          bubbles: true,
          composed: true,
        }),
      );
    await settle(el);
    // Tempdoc 838 — the name reached the BACKEND, which is the whole point: it is not in a browser
    // map any more, and it carries the provenance that stops the next ask renaming it.
    expect(backend.conversations[0]?.title).toBe('Lease terms');
    expect(backend.conversations[0]?.titleSource).toBe('user');

    el.remove();
    const reloaded = await mount();
    // The store persists titles; the reloaded window shows the chosen one, not the question.
    expect(await rowLabels(reloaded)).toEqual(['Lease terms']);
  });

  it('puts a REFUSED rename back, and says so — the row never keeps a name nothing remembers', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    backend.conversations = [
      {
        sessionId: id,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: 'why did the renewal fail?',
        shapeId: 'core.rag-ask',
      },
    ];
    // The store's data key is locked, so the write 423s — the one refusal this product already has a
    // vocabulary for.
    fetchMock.mockImplementationOnce(async () => ({
      ok: false,
      status: 423,
      json: () => Promise.resolve({ errorCode: 'STORE_LOCKED', locked: true }),
    }));
    const toasts: string[] = [];
    const handler = (event: Event): void => {
      toasts.push((event as CustomEvent<{ message: string }>).detail.message);
    };
    document.addEventListener(EPHEMERAL_TOAST_EVENT, handler);
    try {
      el.shadowRoot?.querySelector('jf-sv3-sidebar')?.dispatchEvent(
        new CustomEvent('sv3-session-rename', {
          detail: { id, phase: 'commit', title: 'Lease terms' },
          bubbles: true,
          composed: true,
        }),
      );
      await settle(el);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, handler);
    }

    expect(backend.conversations[0]?.title).toBeUndefined();
    expect(await rowLabels(el)).toEqual(['why did the renewal fail?']);
    // The lock is named by the ONE reason vocabulary, not re-phrased here.
    expect(toasts).toHaveLength(1);
    expect(toasts[0]).toContain('encrypted and locked');
    // A refused rename must not leave the row flagged as renamed — that flag outranks auto-titling,
    // and it would be claiming a rename that never landed.
    expect(el.sessions.sessions[0]?.renamed).toBe(false);
  });
});

describe('the transcript projects from the canonical record (D1)', () => {
  /**
   * An id the CONVERSATION STORE minted — the UUID `FileConversationStore.enrichMessage` writes
   * before every append (`:213-219`). The message endpoints address these and nothing else, so a
   * case about `/history`'s message-keyed fields has to use them (tempdoc 852 S1).
   */
  const storedId = (n: number): string => `11111111-2222-4333-8444-55555555555${n}`;
  const conversationRow = (id: string, first: string): Record<string, unknown> => ({
    sessionId: id,
    createdAtMs: 1,
    lastActiveAtMs: 2,
    messageCount: 4,
    firstUserMessage: first,
    shapeId: 'core.rag-ask',
  });

  it('renders chat turns and agent activity INTERLEAVED, in the record’s order', async () => {
    backend.conversations = [conversationRow('uc-rec', 'index the vendor folder')];
    backend.threads['uc-rec'] = {
      conversationId: 'uc-rec',
      events: [
        wireEvent('e1', 'USER_MESSAGE', 'index the vendor folder'),
        wireEvent('e2', 'ASSISTANT_MESSAGE', 'Looking first.'),
        wireEvent('e3', 'TOOL_ACTIVITY', 'core_search', {
          callId: 'c1',
          toolName: 'core_search',
          arguments: '{}',
          risk: 'low',
          status: 'completed',
        }),
        wireEvent('e4', 'ASSISTANT_MESSAGE', 'Indexed.'),
      ],
    };
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn')?.dataset.kind).toBe('agent');
    const feed = q(main, 'sv3-record-activity');
    expect(feed).not.toBeNull();
    // ONE ordered sequence — a projection that grouped prose and tools separately would read
    // text/text/tool here instead.
    const kinds = [...(feed?.children ?? [])].map((child) =>
      child.getAttribute('data-testid') ?? child.tagName.toLowerCase(),
    );
    expect(kinds).toEqual(['sv3-run-text', 'sv3-run-tool', 'sv3-run-text']);
  });

  it('reads the /history COMPANION record when a conversation is opened (852 §2.3c)', async () => {
    // The gap this closes: the shipped window loads BOTH records at adjacent lines
    // (`views/UnifiedChatView.ts:2048-2049`) and this one loaded only the thread — so the branch
    // lineage, the effective-context floor and the two exclusion ledgers were not on the wire it
    // listened to. Nothing renders them yet (S2/S3 do); this asserts they ARRIVE, because a window
    // that never asks cannot grow an affordance that needs them.
    backend.conversations = [conversationRow('uc-branch', 'why did the renewal fail?')];
    backend.threads['uc-branch'] = {
      conversationId: 'uc-branch',
      events: [
        wireEvent(storedId(0), 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent(storedId(1), 'ASSISTANT_MESSAGE', 'The lock held.'),
        wireEvent(storedId(3), 'USER_MESSAGE', 'and the second one?'),
        wireEvent(storedId(4), 'ASSISTANT_MESSAGE', 'The same lock.'),
      ],
    };
    backend.histories['uc-branch'] = {
      sessionId: 'uc-branch',
      shapeId: 'core.rag-ask',
      // The transcript the companion ALSO carries — and which this window must keep ignoring, the
      // canonical record being the one authority for what happened.
      messages: [
        { role: 'user', content: 'why did the renewal fail?', id: storedId(0) },
        { role: 'assistant', content: 'The lock held.', id: storedId(1) },
        { role: 'user', content: 'and the second one?', id: storedId(3) },
        { role: 'assistant', content: 'The same lock.', id: storedId(4) },
      ],
      parentSessionId: 'uc-parent',
      branchPointMessageId: storedId(1),
      parentFirstUserMessage: 'the original question',
      contextFloor: storedId(3),
      contextFloorSummary: 'Everything above was compacted.',
      excludedMessageIds: [storedId(4)],
      excludedSourceIds: ['docs/lease.md0'],
    };
    const el = await mount();
    await openTheOnlyConversation(el);

    const session = el.sessions.sessions[0];
    expect(session?.history).toEqual({
      parentSessionId: 'uc-parent',
      branchPointMessageId: storedId(1),
      parentFirstUserMessage: 'the original question',
      contextFloor: storedId(3),
      contextFloorSummary: 'Everything above was compacted.',
      excludedMessageIds: [storedId(4)],
      excludedSourceIds: ['docs/lease.md0'],
      // The companion's message ORDER, as ids only — what places a floor no turn names (WP1.2 S2.8).
      // Its content is still not read: the transcript stays the record's (asserted below).
      messageIds: [storedId(0), storedId(1), storedId(3), storedId(4)],
      locked: undefined,
    });
    // The two records MEET: the floor names a message, and the turn it belongs to is found by id.
    const turns = session?.turns ?? [];
    expect(turns).toHaveLength(2);
    expect(sv3TurnByMessageId(turns, storedId(3))).toBe(turns[1]);
    expect(sv3TurnByMessageId(turns, storedId(4))).toBe(turns[1]);
    // ...and the companion's own message list wrote nothing: the transcript is still the record's.
    expect(turns[1]?.question).toBe('and the second one?');
    expect(turns[1]?.answer).toBe('The same lock.');
  });

  it('reads the companion record WITHOUT claiming the shared active-conversation pointer', async () => {
    // `resumeConversation` claims the app-wide active conversation as a side effect of a successful
    // read — right for the shipped window's open path, wrong for a companion load. The other window
    // claims the SAME pointer, so a v3 load still in flight when that happens would hand the product
    // back a conversation the reader has left. v3 passes `claim: false` and claims at open instead;
    // the delayed history below lands after another window's claim and must not touch it.
    backend.conversations = [conversationRow('uc-mine', 'the one v3 opened')];
    backend.threads['uc-mine'] = { conversationId: 'uc-mine', events: [] };
    backend.histories['uc-mine'] = {
      sessionId: 'uc-mine',
      messages: [],
      contextFloor: 'x9',
      __delayMs: 250,
    };
    const el = await mount();
    await openTheOnlyConversation(el);
    // The load must still be IN THE AIR here, or the case proves nothing about a claim landing late.
    expect(el.sessions.sessions.find((s) => s.id === 'uc-mine')?.history).toBeNull();
    // The OTHER window claims the shared pointer while v3's companion load is still in the air.
    setActiveConversation('uc-elsewhere');
    await new Promise<void>((resolve) => setTimeout(resolve, 400));
    await settle(el);

    expect(getConversationListState().activeId).toBe('uc-elsewhere');
    // ...and v3 still recorded what it asked for: the load is discarded only when the READER moved,
    // which is a different question from who owns the pointer.
    expect(el.sessions.sessions.find((s) => s.id === 'uc-mine')?.history?.contextFloor).toBe('x9');
  });

  it('drops a SUPERSEDED companion load, and leaves the pointer on the conversation in view', async () => {
    // The other half: the reader moves within v3 while a load is in flight. The late load must not
    // write its fields onto a conversation that is no longer on screen — the two loads here are made
    // to land out of order on purpose.
    backend.conversations = [
      conversationRow('uc-left', 'the first one'),
      conversationRow('uc-here', 'the second one'),
    ];
    backend.threads['uc-left'] = { conversationId: 'uc-left', events: [] };
    backend.threads['uc-here'] = { conversationId: 'uc-here', events: [] };
    backend.histories['uc-left'] = { sessionId: 'uc-left', messages: [], contextFloor: 'x1', __delayMs: 30 };
    backend.histories['uc-here'] = { sessionId: 'uc-here', messages: [], contextFloor: 'x2' };
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])] as Updatable[];
    const open = (row: Updatable): void => {
      row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    };
    open(rows[0] as Updatable);
    open(rows[1] as Updatable);
    await settle(el);
    await new Promise<void>((resolve) => setTimeout(resolve, 60));
    await settle(el);

    expect(el.sessions.activeId).toBe('uc-here');
    // The late load wrote nothing onto the conversation it was for...
    expect(el.sessions.sessions.find((s) => s.id === 'uc-left')?.history).toBeNull();
    expect(el.sessions.sessions.find((s) => s.id === 'uc-here')?.history?.contextFloor).toBe('x2');
    // ...and the product still thinks the reader is where the reader is.
    expect(getConversationListState().activeId).toBe('uc-here');
  });

  it('a COLD-LOADED grounded answer comes back WITH its sources, note or no note (847 §1.3/§1.7)', async () => {
    // Two defects in one fixture, both end-to-end over the real `/api/thread/{id}` round trip:
    // the window discarded the record's evidence entirely, and `panelSpeaks` then hid whatever
    // survived behind `kind === 'ask'` — which the progress note below flips to `agent`.
    backend.conversations = [conversationRow('uc-grounded', 'why did the renewal fail?')];
    backend.threads['uc-grounded'] = {
      conversationId: 'uc-grounded',
      events: [
        wireEvent('g1', 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent('g2', 'PROGRESS', 'Searching the lease folder'),
        wireEvent('g3', 'ASSISTANT_MESSAGE', 'The lock held.', {
          citations: [
            {
              parentDocId: 'docs/lease.md',
              chunkIndex: 0,
              chunkTotal: 1,
              startChar: 0,
              endChar: 40,
              score: 0.9,
              excerpt: 'The lock held past the renewal date.',
              startLine: 1,
              endLine: 2,
              headingText: 'Renewal',
              headingLevel: 2,
            },
          ],
          claimMatches: {
            scorer: 'CROSS_ENCODER',
            sentencesTotal: 1,
            sentencesScored: 1,
            matches: [
              {
                sentenceIndex: 0,
                sentenceText: 'The lock held.',
                sourceIndex: 0,
                similarity: 0.94,
                parentDocId: 'docs/lease.md',
              },
            ],
          },
        }),
      ],
    };
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);

    const restored = el.sessions.sessions[0]?.turns[0];
    expect(restored?.evidence?.sources).toHaveLength(1);
    expect(restored?.evidence?.marks).toHaveLength(1);
    const main = await region(el, 'jf-sv3-main');
    // The turn really is `agent`-kind here, which is exactly why the disclosure must not be gated
    // on kind: the fact on screen is the evidence, and the evidence is there.
    expect(q(main, 'sv3-turn')?.dataset.kind).toBe('agent');
    expect(q(main, 'sv3-turn-sources')).not.toBeNull();
  });

  /** A restored answer whose ONLY evidence is per-sentence matches from the named producer. */
  const matchesOnlyRecord = (scorer: string): { conversationId: string; events: unknown[] } => ({
    conversationId: 'uc-matched',
    events: [
      wireEvent('m1', 'USER_MESSAGE', 'why did the renewal fail?'),
      wireEvent('m2', 'ASSISTANT_MESSAGE', 'The lock held.', {
        claimMatches: {
          scorer,
          sentencesTotal: 1,
          sentencesScored: 1,
          matches: [
            {
              sentenceIndex: 0,
              sentenceText: 'The lock held.',
              sourceIndex: 0,
              similarity: 0.94,
              parentDocId: 'docs/lease.md',
            },
          ],
        },
      }),
    ],
  });

  async function openTheOnlyConversation(el: Mounted): Promise<void> {
    const sidebar = await region(el, 'jf-sv3-sidebar');
    (sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable | null)?.shadowRoot
      ?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')
      ?.click();
    await settle(el);
  }

  it('opens NO grounding panel for a restored answer whose producer was not admitted (847 T17b)', async () => {
    // The counterweight to the loosened `panelSpeaks`: a restored turn whose only evidence is a
    // non-admitted producer's matches must not open a disclosure that asserts grounding. Its twin
    // below proves the same fixture DOES open one when the producer is admitted, so this empty is
    // the gate rather than an inert record.
    backend.conversations = [conversationRow('uc-matched', 'why did the renewal fail?')];
    backend.threads['uc-matched'] = matchesOnlyRecord('EMBEDDING_COSINE');
    const el = await mount();
    await openTheOnlyConversation(el);
    expect(el.sessions.sessions[0]?.turns[0]?.evidence?.matches).toEqual([]);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-turn-sources')).toBeNull();
  });

  it('opens one for the SAME record under the admitted producer (the T17b twin)', async () => {
    backend.conversations = [conversationRow('uc-matched', 'why did the renewal fail?')];
    backend.threads['uc-matched'] = matchesOnlyRecord('CROSS_ENCODER');
    const el = await mount();
    await openTheOnlyConversation(el);
    expect(el.sessions.sessions[0]?.turns[0]?.evidence?.matches).toHaveLength(1);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-turn-sources')).not.toBeNull();
  });

  it('lets the LIVE turn stand while it streams, and yields to the record when it settles', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    // The record already disagrees with the live turn — and must NOT win while it streams.
    backend.threads[id] = {
      conversationId: id,
      events: [
        wireEvent('r1', 'USER_MESSAGE', 'why did the renewal fail?', { turnId: el.sessions.sessions[0]!.turns[0]!.dispatchTurnId }),
        wireEvent('r2', 'ASSISTANT_MESSAGE', 'The record’s answer.', { turnId: el.sessions.sessions[0]!.turns[0]!.dispatchTurnId }),
      ],
    };
    stream.emit('chunk', { text: 'The live answer.' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-markdown')?.getAttribute('text') ?? el.sessions.sessions[0]?.turns[0]?.answer)
      .toContain('The live answer.');
    expect(el.sessions.sessions[0]?.turns[0]?.id).not.toBe('r1');

    stream.emit('done', {});
    stream.end();
    await settle(el);
    // Settled: the record is the authority for the CONTENT, and its identity is stamped as
    // `recordId` — the turn's own id is never rewritten (tempdoc 847 §1.6b: the live run's `turnId`,
    // `expandedSources` and `copiedTurnId` are all keyed on it).
    expect(el.sessions.sessions[0]?.turns[0]?.answer).toBe('The record’s answer.');
    expect(el.sessions.sessions[0]?.turns[0]?.id).not.toBe('r1');
    expect(el.sessions.sessions[0]?.turns[0]?.recordId).toBe('r1');
  });

  it('says a failed refresh out loud, in words that are NOT the empty state’s (D2)', async () => {
    backend.conversations = [conversationRow('uc-broken', 'a conversation')];
    backend.threads['uc-broken'] = 'fail';
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const notice = q(main, 'sv3-record-notice');
    expect(notice).not.toBeNull();
    expect(notice?.textContent).toContain(RECORD_UNREACHABLE.title);
    // Distinct from "nothing matched" — a record that could not be read says nothing about content.
    expect(RECORD_UNREACHABLE.title).not.toBe(MAIN_EMPTY.title);
    expect(notice?.textContent).not.toContain(MAIN_EMPTY.title);
    expect(q(main, 'sv3-main-empty')).toBeNull();
  });

  it('does not raise the notice when the record simply has nothing in it', async () => {
    backend.conversations = [conversationRow('uc-fresh', 'a conversation')];
    backend.threads['uc-fresh'] = { conversationId: 'uc-fresh', events: [] };
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-record-notice')).toBeNull();
  });
});

describe('a reload restores the thread THIS TAB was reading (A3)', () => {
  it('claims the per-tab conversation on a fresh mount and fetches its record', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    backend.threads[id] = {
      conversationId: id,
      events: [
        wireEvent('r1', 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent('r2', 'ASSISTANT_MESSAGE', 'The lock held.'),
      ],
    };
    el.remove();

    // A fresh instance with an EMPTY store list: the only thing that can restore the thread is the
    // per-tab pointer, so this cannot pass by accident through the conversation list.
    backend.conversations = [];
    const reloaded = await mount();
    expect(reloaded.sessions.activeId).toBe(id);
    const main = await region(reloaded, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-question')?.textContent?.trim()).toBe('why did the renewal fail?');
  });

  it('forgets the pointer on New session, so the next load lands cold', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot
      ?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')
      ?.click();
    await settle(el);
    el.remove();

    backend.conversations = [];
    const reloaded = await mount();
    expect(reloaded.sessions.activeId).toBeNull();
    expect(reloaded.sessions.sessions).toHaveLength(0);
  });
});

describe('a cold load re-attaches to a live run (D3)', () => {
  it('asks the shared controller to reattach when the enumeration says a run is live', async () => {
    backend.liveRuns = [
      {
        runId: 'run-7',
        shapeId: 'core.agent-run',
        conversationId: null,
        state: 'running',
        park: null,
        startedAtEpochMs: 1,
        updatedAtEpochMs: 1,
        observerCount: 0,
        snapshot: null,
      },
    ];
    // The controller comes back attached to the live run, exactly as a real reattach leaves it.
    Object.assign(ctrl, {
      runInFlight: true,
      sessionId: 'run-7',
      conversation: [{ id: 'u1', type: 'user', content: 'reindex the archive', timestamp: 0 }],
    });
    const el = await mount();
    expect(reattach).toHaveBeenCalledTimes(1);
    // ...and PRESENCE turns that into something the reader can see: a session, on the Active shelf.
    expect(el.sessions.sessions.map((s) => s.title)).toEqual(['reindex the archive']);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(
      [...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? [])]
        .map((g) => g.textContent?.trim()),
    ).toContain('Active');
  });

  it('starts NO controller at all when the enumeration is empty — a mounted window does not poll', async () => {
    const el = await mount();
    expect(reattach).not.toHaveBeenCalled();
    expect(ctrlExists).toBe(false);
    expect(el.sessions.sessions).toHaveLength(0);
  });
});

describe('New session detaches from everything in flight (A13, abort half)', () => {
  it('appends NOTHING to the fresh thread when a late delta arrives', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const firstId = el.sessions.sessions[0]?.id ?? '';
    stream.emit('chunk', { text: 'early text' });
    await settle(el);

    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')?.click();
    await settle(el);
    expect(el.sessions.activeId).toBeNull();

    // The ABORT is the mechanism, and it is verified as one rather than assumed: the stream is
    // already torn down and the unsaved live turn left the parked conversation, whose record shows
    // what the Engine saved when it is opened again. (Dropping `abortAsk()` from `onSessionNew`
    // fails right here, and then again below.)
    const parkedNow = el.sessions.sessions.find((s) => s.id === firstId);
    expect(parkedNow?.turns).toEqual([]);

    // THE PROBE: emit anyway — a delta AND an evidence event, the two writes an ask makes. Neither
    // may reach anything: not the parked turn, and not the fresh conversation.
    stream.emit('chunk', { text: ' LATE TEXT' });
    stream.emit('rag.citations', {
      citations: [{ parentDocId: 'f:/late.md', chunkIndex: 0, excerpt: 'late' }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const parked = el.sessions.sessions.find((s) => s.id === firstId);
    expect(parked?.turns).toEqual([]);
    // The fresh conversation is untouched: nothing was created and the window is back at the hero.
    expect(el.sessions.sessions).toHaveLength(1);
    expect(el.getAttribute('composer-state')).toBe('hero');
  });

  it('does not call leaving mid-answer a Stop: back in the conversation, the saved answer shows', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    stream.emit('chunk', { text: 'Half an ans' });
    await settle(el);
    // Leaving tells the Engine nothing, so it answers in full and saves that.
    backend.conversations = [
      {
        sessionId: id,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: 'why did the renewal fail?',
        shapeId: 'core.rag-ask',
      },
    ];
    backend.threads[id] = {
      conversationId: id,
      events: [
        wireEvent('r1', 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent('r2', 'ASSISTANT_MESSAGE', 'Half an answer, and the rest of it.'),
      ],
    };

    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')?.click();
    await settle(el);
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    await row.updateComplete;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);

    expect(el.sessions.activeId).toBe(id);
    const turns = el.sessions.sessions.find((s) => s.id === id)?.turns ?? [];
    expect(turns).toHaveLength(1);
    expect(turns[0]?.status).toBe('complete');
    expect(turns[0]?.answer).toBe('Half an answer, and the rest of it.');
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.textContent ?? '').not.toContain(TURN_HALTED);
    stream.end();
    await settle(el);
  });

  it('DETACHES from a live run without halting it', async () => {
    const el = await mount();
    Object.assign(ctrl, {
      runInFlight: true,
      sessionId: 'run-9',
      conversation: [{ id: 'u1', type: 'user', content: 'audit the folder', timestamp: 0 }],
    });
    ctrlExists = true;
    agentListener?.();
    await settle(el);
    expect(el.sessions.sessions).toHaveLength(1);

    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')?.click();
    await settle(el);

    // The run keeps running — this window merely stopped rendering it. Halting a product-wide run
    // because one dev window opened a new conversation would be this window deciding for everyone.
    expect(ctrl.runInFlight).toBe(true);
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-send')).not.toBeNull();
    expect(q(composer, 'sv3-composer-stop')).toBeNull();
  });
});

describe('the composer draft and the global activity indicator (A14, A15, G11)', () => {
  it('persists the draft under this window’s OWN key and restores it on a fresh mount', async () => {
    const el = await mount();
    await type(el, 'a half-written question');
    el.remove();
    // The shared controller flushes on host-disconnect; the key is v3's own, never the shipped one's.
    expect(localStorage.getItem(__draftStorageKey(SV3_DRAFT_KEY))).toBe('a half-written question');
    expect(localStorage.getItem(__draftStorageKey('unified-chat.composer'))).toBeNull();

    const reloaded = await mount();
    expect((await fieldOf(reloaded)).value).toBe('a half-written question');
  });

  it('says "Draft kept" ONCE when leaving with a draft, and not at all without one', async () => {
    const toasts: string[] = [];
    const handler = (event: Event): void => {
      toasts.push((event as CustomEvent<{ message: string }>).detail.message);
    };
    document.addEventListener(EPHEMERAL_TOAST_EVENT, handler);
    try {
      const empty = await mount();
      empty.remove();
      expect(toasts).toEqual([]);

      const first = await mount();
      await type(first, 'unsent thought');
      first.remove();
      expect(toasts).toEqual(['Draft kept']);

      // Teach, don't nag: a second leave with a draft is silent.
      const second = await mount();
      await type(second, 'another unsent thought');
      second.remove();
      expect(toasts).toEqual(['Draft kept']);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, handler);
    }
  });

  it('settles the app-wide activity indicator when a stream is torn down (G11)', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'partial' });
    await settle(el);
    // Raised for the duration, with a working cancel — so the settle below is settling something.
    expect(getAiState().activity.state).toBe('streaming');
    expect(getAiState().activity.canCancel).toBe(true);

    el.remove();
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(getAiState().activity.state).toBe('idle');
    expect(getAiState().activity.shapeId).toBeNull();
  });

  it('settles it at the stream’s own terminal too, not only on teardown', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    expect(getAiState().activity.state).toBe('idle');
  });

  it('leaves the indicator alone when this window was never streaming', async () => {
    setAiActivity({ state: 'streaming', shapeId: 'core.free-chat', startedAtMs: 1 });
    const el = await mount();
    el.remove();
    await new Promise<void>((r) => setTimeout(r, 0));
    // Another surface's activity is not this window's to settle on its way out.
    expect(getAiState().activity.shapeId).toBe('core.free-chat');
  });
});

it('a streamed answer counts as server content before any list or record read returns it', async () => {
  const fetchBefore = globalThis.fetch;
  let absent = false;
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    if (String(url).includes('/history?existsOnly=true')) return new Response(JSON.stringify({ exists: !absent }));
    return fetchBefore(url, init);
  }));
  const el = await mount();
  await send(el, 'private question');
  const id = el.sessions.activeId;
  expect(id).not.toBeNull();
  stream.emit('chunk', { text: 'private streamed answer' });
  await settle(el);
  expect(el.sessions.sessions.find(s => s.id === id)?.turns[0]?.answer).toBe('private streamed answer');
  stream.emit('done', {}); stream.end();
  await settle(el);
  absent = true;
  await loadConversations();
  await settle(el);
  expect(el.sessions.sessions.map(s => s.id)).not.toContain(id);
  expect(el.sessions.activeId).toBeNull();
  expect(sessionStorage.getItem('justsearch.lastViewedConversation.v1')).toBeNull();
});
