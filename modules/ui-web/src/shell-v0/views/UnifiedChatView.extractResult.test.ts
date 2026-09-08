// @vitest-environment happy-dom

/**
 * Tempdoc 941 round 19, finding F1 — a Structured (core.extract) dispatch returned HTTP 200 on a
 * long-running shell, cleared the composer, and rendered nothing at all: no result frame, no
 * notice, no error.
 *
 * Cases A-C and F are the controls that eliminated the record/live-overlay dedup and
 * history-projection hypotheses: given the record the sandbox actually captured, the extraction
 * renders on the old code too. So the answer was never lost — it was never brought into view.
 *
 * - D/E: `onDone` must not discard a turn that arrived only on the `done` payload, and must SAY so
 *   when the dispatch really produced nothing (the composer is cleared either way).
 * - G/H: the transcript must follow its newest turn, and must not yank a reader who scrolled back.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import './UnifiedChatView.js';
import type { UnifiedChatView } from './UnifiedChatView.js';
import { consumeShapeStream, dispatchShapeEventToHandlers } from '../../api/streams.js';

const AI_STATE_READY = {
  capabilities: { chat: true, rag: true, extract: true, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  snapshotLive: true,
};
vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(),
  stopAiStateStore: vi.fn(),
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    listener(AI_STATE_READY);
    return () => {};
  }),
  setAiActivity: vi.fn(),
  getAiState: () => AI_STATE_READY,
}));

vi.mock('../../api/streams.js', () => ({
  consumeShapeStream: vi.fn(
    () => new Promise<void>(() => { /* never resolves */ }),
  ),
  dispatchShapeEventToHandlers: vi.fn(),
  RUN_STARTED_EVENT: 'run_started',
}));

const recordState: { events: unknown[] } = { events: [] };
vi.mock('./unifiedThreadClient.js', () => ({
  fetchUnifiedThread: vi.fn(async () => ({ events: recordState.events, lifecycles: [] })),
}));

vi.mock('../state/conversationListStore.js', async () => {
  const actual = await vi.importActual<typeof import('../state/conversationListStore.js')>(
    '../state/conversationListStore.js',
  );
  return {
    ...actual,
    setConversationApiBase: vi.fn(),
    resumeConversation: vi.fn(async (sessionId: string, shapeId: string) => ({
      sessionId,
      shapeId,
      messages: [],
    })),
    fetchMessageIds: vi.fn(async () => null),
    branchConversation: vi.fn(async () => 'uc-branch-new'),
    generateConversationTitle: vi.fn(),
    getRecentSessions: vi.fn(() => []),
    recordRecentSession: vi.fn(),
    createConversationId: () => 'uc-test-fixed',
    exportConversationMarkdown: vi.fn(() => ''),
    editContextFloorSummary: vi.fn(async () => true),
    setMessageExcluded: vi.fn(async () => true),
    setSourceExcluded: vi.fn(async () => true),
  };
});

function mountView(): UnifiedChatView {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  const view = document.createElement('jf-unified-chat-view') as UnifiedChatView;
  view.apiBase = 'http://localhost:5173';
  document.body.appendChild(view);
  return view;
}

interface Handlers {
  onChunk?(p: unknown): void;
  onDone?(p: unknown): void;
}

function renderedAnswerText(view: UnifiedChatView): string {
  const blocks = Array.from(
    view.shadowRoot!.querySelectorAll('jf-markdown-block'),
  ) as unknown as Array<{ text?: string }>;
  return blocks.map((b) => b.text ?? '').join(' | ');
}

function errorText(view: UnifiedChatView): string {
  return Array.from(view.shadowRoot!.querySelectorAll('.error'))
    .map((n) => n.textContent ?? '')
    .join(' | ')
    .trim();
}

async function submitAndCapture(view: UnifiedChatView, text: string): Promise<Handlers> {
  view.inputDraft = text;
  await view.updateComplete;
  view.shadowRoot?.querySelector('jf-composer')?.dispatchEvent(new CustomEvent('composer-submit'));
  await view.updateComplete;
  const onEvent = vi.mocked(consumeShapeStream).mock.calls.at(-1)![2] as (
    e: string,
    p: unknown,
  ) => void;
  const dispatchMock = vi.mocked(dispatchShapeEventToHandlers);
  dispatchMock.mockClear();
  onEvent('probe', {});
  return dispatchMock.mock.calls.at(-1)![0] as Handlers;
}

const PRIOR_RECORD = [
  {
    id: 'r-u1',
    occurredAt: '2026-01-01T00:00:01Z',
    kind: 'USER_MESSAGE',
    originator: 'user',
    content: 'what do my docs say?',
    attributes: {},
  },
  {
    id: 'r-a1',
    occurredAt: '2026-01-01T00:00:02Z',
    kind: 'ASSISTANT_MESSAGE',
    originator: 'assistant',
    content: 'They say a lot of things.',
    attributes: {},
  },
];

const JSON_RESULT = '{"phrase":"quick brown fox"}';

/**
 * The verbatim record shape the round-19 sandbox captured for the failing conversation
 * (`uc-a7d904b3-…`, `tmp/sandbox/share/evidence/post-round/round19-f1-followup/`): a rag-ask pair
 * followed by the extract pair. The backend DID generate and persist the extraction; the shell
 * still showed the previous Document Q&A card.
 */
const CAPTURED_RAG_USER = {
  id: '2f0bb1f9-0001-0000-0000-000000000001',
  occurredAt: '2026-09-07T05:24:51.000000000Z',
  kind: 'USER_MESSAGE',
  originator: 'user',
  content: 'quick brown fox',
  attributes: { shapeId: 'core.rag-ask' },
};
const CAPTURED_RAG_ANSWER = {
  id: '2f0bb1f9-0002-0000-0000-000000000002',
  occurredAt: '2026-09-07T05:24:53.000000000Z',
  kind: 'ASSISTANT_MESSAGE',
  originator: 'agent',
  content: 'The phrase "quick brown fox" appears in the hello.txt file.',
  attributes: { shapeId: 'core.rag-ask' },
};
const CAPTURED_EXTRACT_USER = {
  id: '96182584-2312-4917-a9b8-8bac0407ace6',
  occurredAt: '2026-09-07T05:29:39.336925800Z',
  kind: 'USER_MESSAGE',
  originator: 'user',
  content: 'Extract the phrase quick brown fox from the hello fixture.',
  attributes: { shapeId: 'core.extract' },
};
const CAPTURED_EXTRACT_ANSWER = {
  id: '8cab4a47-1b74-40fa-9cbc-06d26bef43ee',
  occurredAt: '2026-09-07T05:29:40.369844400Z',
  kind: 'ASSISTANT_MESSAGE',
  originator: 'agent',
  content: '{\n  "phrase": "quick brown fox"\n}\n',
  attributes: { shapeId: 'core.extract' },
};
const CAPTURED_PRIOR_RAG = [CAPTURED_RAG_USER, CAPTURED_RAG_ANSWER];
const CAPTURED_RECORD = [
  CAPTURED_RAG_USER,
  CAPTURED_RAG_ANSWER,
  CAPTURED_EXTRACT_USER,
  CAPTURED_EXTRACT_ANSWER,
];


describe('941 F1 — Structured result on a long-running conversation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(consumeShapeStream).mockImplementation(
      () => new Promise<void>(() => {}),
    );
    recordState.events = [];
  });

  it('A: fresh conversation, empty record — control', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; schemaDraft: string };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    await view.updateComplete;

    const h = await submitAndCapture(view, 'extract the phrase');
    h.onChunk?.({ text: JSON_RESULT });
    h.onDone?.({});
    await view.updateComplete;
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).toContain('quick brown fox');
    view.remove();
  });

  it('B: conversation already holds a rag-ask turn; record gains the extract turns', async () => {
    recordState.events = [...PRIOR_RECORD];
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      schemaDraft: string;
      thread: unknown[];
      unifiedEvents: unknown[];
    };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    v.unifiedEvents = [...PRIOR_RECORD];
    v.thread = [
      { role: 'user', content: 'what do my docs say?', shapeId: 'core.rag-ask', id: 'r-u1' },
      {
        role: 'assistant',
        content: 'They say a lot of things.',
        shapeId: 'core.rag-ask',
        id: 'r-a1',
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const h = await submitAndCapture(view, 'extract the phrase');
    // The backend recorded both turns (recordsToThread = true), so the refresh in onDone sees them.
    recordState.events = [
      ...PRIOR_RECORD,
      {
        id: 'r-u2',
        occurredAt: '2026-01-01T00:00:03Z',
        kind: 'USER_MESSAGE',
        originator: 'user',
        content: 'extract the phrase',
        attributes: {},
      },
      {
        id: 'r-a2',
        occurredAt: '2026-01-01T00:00:04Z',
        kind: 'ASSISTANT_MESSAGE',
        originator: 'assistant',
        content: JSON_RESULT,
        attributes: {},
      },
    ];
    h.onChunk?.({ text: JSON_RESULT });
    h.onDone?.({});
    await view.updateComplete;
    await Promise.resolve();
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).toContain('quick brown fox');
    view.remove();
  });

  it('C: same, but the backend recorded nothing new (EPHEMERAL)', async () => {
    recordState.events = [...PRIOR_RECORD];
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      schemaDraft: string;
      thread: unknown[];
      unifiedEvents: unknown[];
    };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    v.unifiedEvents = [...PRIOR_RECORD];
    v.thread = [
      { role: 'user', content: 'what do my docs say?', shapeId: 'core.rag-ask', id: 'r-u1' },
      {
        role: 'assistant',
        content: 'They say a lot of things.',
        shapeId: 'core.rag-ask',
        id: 'r-a1',
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const h = await submitAndCapture(view, 'extract the phrase');
    h.onChunk?.({ text: JSON_RESULT });
    h.onDone?.({});
    await view.updateComplete;
    await Promise.resolve();
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).toContain('quick brown fox');
    view.remove();
  });

  it('D: no chunk events reached the view, but done carries finalResponse — the answer still renders', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; schemaDraft: string };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    await view.updateComplete;

    // No onChunk at all: the chunk frames never accumulated in this view (a throwing handler is
    // swallowed to console.warn by consumeShapeStream; a mid-stream reconnect loses them too).
    // The substrate's authoritative answer is on the done payload either way.
    const h = await submitAndCapture(view, 'extract the phrase');
    h.onDone?.({ finalResponse: JSON_RESULT, iterationsUsed: 1 });
    await view.updateComplete;
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).toContain('quick brown fox');
    expect(errorText(view)).toBe('');
    view.remove();
  });

  it('E: the dispatch produced nothing at all — the view says so instead of clearing silently', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; schemaDraft: string };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    await view.updateComplete;

    const h = await submitAndCapture(view, 'extract the phrase');
    expect(view.inputDraft).toBe(''); // the composer was cleared optimistically on submit
    h.onDone?.({});
    await view.updateComplete;
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).not.toContain('quick brown fox');
    expect(errorText(view)).toMatch(/no result/i);
    view.remove();
  });

  it('F: the captured round-19 record — no chunks reached the view, the record holds the extraction', async () => {
    recordState.events = CAPTURED_PRIOR_RAG;
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      schemaDraft: string;
      thread: unknown[];
      unifiedEvents: unknown[];
    };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    v.unifiedEvents = CAPTURED_PRIOR_RAG;
    v.thread = [
      { role: 'user', content: 'quick brown fox', shapeId: 'core.rag-ask', id: CAPTURED_RAG_USER.id },
      {
        role: 'assistant',
        content: 'The phrase "quick brown fox" appears in the hello.txt file.',
        shapeId: 'core.rag-ask',
        id: CAPTURED_RAG_ANSWER.id,
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const h = await submitAndCapture(view, CAPTURED_EXTRACT_USER.content);
    // The backend persisted BOTH extract turns before answering the dispatch (occurredAt
    // 05:29:40.369 vs the 200 at 05:29:40.373), so the refresh in `onDone` sees all four.
    recordState.events = [...CAPTURED_RECORD];
    // No `chunk` events reach this view — the observed failure.
    h.onDone?.({ finalResponse: CAPTURED_EXTRACT_ANSWER.content });
    await view.updateComplete;
    await Promise.resolve();
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).toContain('quick brown fox');
    view.remove();
  });

  /**
   * happy-dom does no layout, so the scroll region reports 0/0/0 and nothing can be "below the
   * fold". Give the column the geometry the sandbox shell had — content taller than the viewport —
   * so the follow-the-tail contract is actually exercised.
   */
  function makeScrollable(view: UnifiedChatView, scrollHeight = 2000, clientHeight = 500): HTMLElement {
    const el = view.shadowRoot!.querySelector('.conversation') as HTMLElement;
    Object.defineProperty(el, 'scrollHeight', { value: scrollHeight, configurable: true });
    Object.defineProperty(el, 'clientHeight', { value: clientHeight, configurable: true });
    Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true });
    return el;
  }

  it('G: a long conversation follows its newest turn (the observed "answer is lost" surface)', async () => {
    recordState.events = CAPTURED_PRIOR_RAG;
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; schemaDraft: string; unifiedEvents: unknown[] };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    v.unifiedEvents = CAPTURED_PRIOR_RAG;
    view.requestUpdate();
    await view.updateComplete;

    const el = makeScrollable(view);
    // The reader is parked on the previous Document Q&A card, as in the round-19 shell.
    el.scrollTop = 0;

    const h = await submitAndCapture(view, CAPTURED_EXTRACT_USER.content);
    h.onChunk?.({ text: JSON_RESULT });
    h.onDone?.({ finalResponse: JSON_RESULT });
    await view.updateComplete;
    await Promise.resolve();
    await view.updateComplete;

    expect(renderedAnswerText(view)).toContain('quick brown fox');
    expect(el.scrollTop).toBe(1500); // scrollHeight - clientHeight, the browser-clamped tail
    view.remove();
  });

  it('H: a reader who scrolls back mid-stream is not yanked forward', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; schemaDraft: string };
    v.affordance = 'extract';
    v.schemaDraft = '{"type":"object","properties":{"phrase":{"type":"string"}}}';
    await view.updateComplete;

    const el = makeScrollable(view);
    const h = await submitAndCapture(view, 'extract the phrase');

    // The reader scrolls back to re-read while the answer streams.
    el.scrollTop = 100;
    el.dispatchEvent(new Event('scroll'));

    h.onChunk?.({ text: JSON_RESULT });
    h.onDone?.({ finalResponse: JSON_RESULT });
    await view.updateComplete;
    await Promise.resolve();
    await view.updateComplete;

    expect(el.scrollTop).toBe(100);
    view.remove();
  });
});

/**
 * Tempdoc 859 review F4 — the SECOND arrival of a loaded conversation's content.
 *
 * `loadConversation` leaves the retrieve base tier once the load produced something to read, but its
 * gate reads `thread`, which is built from `resumeConversation`'s messages. The record projection is
 * a different, LATER arrival: `refreshUnifiedThread()` is fired `void` before the resume is awaited,
 * so its events land after that gate has already run. A record whose content lives only in
 * `unifiedEvents` — a delegate run with no chat messages of its own — therefore satisfied
 * `renderResumePrompt`'s "there is content" test (so no resume card) while the thread branch stayed
 * gated behind `retrieve` (so no transcript): the blank stage again, by a second route.
 *
 * This file is the harness for it because it already mocks BOTH halves — `resumeConversation` with
 * empty `messages`, and `unifiedThreadClient` with a mutable record — which is exactly the split the
 * defect needs.
 */
describe('859 F4: a record that lives only in unifiedEvents also leaves the retrieve tier', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    recordState.events = [];
  });

  const selectFromHistory = async (view: UnifiedChatView, sessionId: string): Promise<void> => {
    const history = view.shadowRoot?.querySelector('jf-conversation-history');
    expect(history, 'the History dropdown is not mounted').not.toBeNull();
    history!.dispatchEvent(
      new CustomEvent('conversation-select', {
        detail: { sessionId, shapeId: 'core.agent-run' },
        bubbles: true,
        composed: true,
      }),
    );
    for (let i = 0; i < 8; i += 1) await new Promise<void>((r) => setTimeout(r, 0));
    await view.updateComplete;
  };

  it('renders the record instead of a blank stage when resumed.messages is empty', async () => {
    recordState.events = PRIOR_RECORD;
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    (view as unknown as { searchSnapshot: unknown }).searchSnapshot = {
      query: 'worker',
      results: [],
      isSearching: false,
      error: null,
    };
    view.requestUpdate();
    await view.updateComplete;
    expect(view.affordance, 'the fixture did not reach the retrieve tier').toBe('retrieve');

    await selectFromHistory(view, 'uc-record-only');

    // The split this case exists for: NOTHING arrived through the thread…
    expect((view as unknown as { thread: unknown[] }).thread).toHaveLength(0);
    // …and the content arrived through the record instead.
    expect(
      (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents.length,
    ).toBeGreaterThan(0);
    // …so the stage still has to show it.
    expect(view.affordance).not.toBe('retrieve');
    expect(view.shadowRoot?.querySelector('[data-testid="retrieve-tier"]')).toBeNull();
    expect(
      view.shadowRoot?.querySelectorAll('.message').length ?? 0,
      'the stage rendered blank — the F4 defect',
    ).toBeGreaterThan(0);
    view.remove();
  });

  it('an EMPTY record leaves the reader on the search floor', async () => {
    // The other half of the predicate. The exit is keyed on there being something to read, so a load
    // that produced nothing at all must not yank a reader off the tier they chose.
    recordState.events = [];
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    view.requestUpdate();
    await view.updateComplete;

    await selectFromHistory(view, 'uc-empty');

    expect((view as unknown as { unifiedEvents: unknown[] }).unifiedEvents).toHaveLength(0);
    expect(view.affordance).toBe('retrieve');
    view.remove();
  });
});
