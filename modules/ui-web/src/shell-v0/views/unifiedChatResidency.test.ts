// @vitest-environment happy-dom
/**
 * Spec A step 9 (T26, D8: "Load anyway" is UI-only) — with the chat model unloaded for another GPU
 * user, the chat window keeps Send live (a person's send loads it again), says why, and offers
 * "Load anyway", which calls `POST /api/chat/load-anyway` with `{allowLoad: true}`. A send the
 * Engine refuses under pressure is taken back (bubble removed, text back in the composer) and
 * worded, not shown as a raw HTTP error; "Load anyway" then sends it again.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import './UnifiedChatView.js';
import type { UnifiedChatView } from './UnifiedChatView.js';
import { consumeShapeStream } from '../../api/streams.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { resetUnifiedChatState } from '../state/unifiedChatState.js';
import { __resetUiModeForTest } from '../state/uiModeState.js';
import { __resetModalityForTest } from '../primitives/modality.js';

const ai = vi.hoisted(() => ({
  state: {
    capabilities: { chat: true, rag: true, extract: false, embedding: false },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    snapshotLive: true,
  } as Record<string, unknown>,
}));

/** Chat is not answering: the Engine unloaded the model for another GPU user. */
const UNLOADED = {
  capabilities: { chat: false, rag: false, extract: false, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  snapshotLive: true,
  chatUnloadedReason: 'inference.unloaded_gpu_pressure',
};

vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(),
  stopAiStateStore: vi.fn(),
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    listener(ai.state);
    return () => {};
  }),
  setAiActivity: vi.fn(),
  getAiState: () => ai.state,
}));

vi.mock('../../api/streams.js', () => ({
  consumeShapeStream: vi.fn(() => new Promise<void>(() => { /* in flight */ })),
  dispatchShapeEventToHandlers: vi.fn(),
  RUN_STARTED_EVENT: 'run_started',
}));

vi.mock('../api/authorizedFetch.js', () => ({
  authorizedFetch: vi.fn(),
}));

vi.mock('../state/searchState.js', () => ({
  subscribeSearch: vi.fn((listener: (s: unknown) => void) => {
    listener({ query: '', results: [], totalHits: 0, isSearching: false, processingTimeMs: null,
      error: null, searchTrace: null });
    return () => {};
  }),
  setQuery: vi.fn(),
  submitSearch: vi.fn(),
  setSearchApiBase: vi.fn(),
  recordOpenDisposition: vi.fn(),
  getSearchState: vi.fn(() => ({ query: '', results: [], totalHits: 0, isSearching: false,
    processingTimeMs: null, error: null, searchTrace: null })),
  subscribeScopeChips: vi.fn((listener: (chips: unknown) => void) => {
    listener([]);
    return () => {};
  }),
  addScopeChip: vi.fn(),
  removeScopeChip: vi.fn(),
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
    generateConversationTitle: vi.fn(),
    getRecentSessions: vi.fn(() => []),
    recordRecentSession: vi.fn(),
    createConversationId: () => 'uc-test-' + Math.random().toString(16).slice(2),
  };
});

/** The view's private state and send path, as the other chat-view tests reach them. */
interface Internals {
  inputDraft: string;
  thread: Array<{ role: string; content: string }>;
  errorMessage: string;
  send(): Promise<void>;
}

type View = UnifiedChatView;

function inner(view: View): Internals {
  return view as unknown as Internals;
}

function mountView(): View {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  const view = document.createElement('jf-unified-chat-view') as View;
  view.apiBase = 'http://localhost:5173';
  document.body.appendChild(view);
  return view;
}

function composer(view: View): Element {
  const el = view.shadowRoot!.querySelector('jf-composer');
  expect(el).not.toBeNull();
  return el!;
}

/** What `consumeShapeStream` throws for the Engine's 503 CHAT_UNAVAILABLE refusal. */
function pressureRefusal(): Error {
  return Object.assign(new Error('consumeShapeStream: HTTP 503 from /api/chat/dispatch — {...}'), {
    status: 503,
    chatRefusal: {
      errorCode: 'CHAT_UNAVAILABLE',
      reason: 'GPU_BUSY',
      message: 'Chat is paused: another application needs the graphics memory.',
      forceAllowed: true,
    },
  });
}

function loadAnywayButton(view: View): HTMLElement | null {
  return view.shadowRoot!.querySelector('.chat-residency-notice .load-anyway');
}

async function settle(view: View): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await view.updateComplete;
  }
}

describe('Spec A step 9 — the chat window with chat unloaded for another GPU user', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    ai.state = UNLOADED;
  });

  afterEach(() => {
    __resetUiModeForTest();
    __resetModalityForTest();
    document.body.innerHTML = '';
  });

  it('keeps Send live, says why chat is paused and offers Load anyway', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    inner(view).inputDraft = 'what does the contract say about notice?';
    await view.updateComplete;

    expect(composer(view).hasAttribute('submit-disabled')).toBe(false);
    expect(composer(view).getAttribute('submit-title')).toBe('');
    const notice = view.shadowRoot!.querySelector('.chat-residency-notice');
    expect(notice?.textContent ?? '').toContain('Chat was paused to free graphics memory');
    expect(loadAnywayButton(view)?.textContent).toContain('Load anyway');
    view.remove();
  });

  it('a send refused under pressure is taken back and worded; Load anyway loads and sends it again', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    const text = 'summarise the lease';
    inner(view).inputDraft = text;
    vi.mocked(consumeShapeStream).mockImplementationOnce(() => Promise.reject(pressureRefusal()));

    await inner(view).send();
    await settle(view);

    expect(inner(view).thread.some((m) => m.role === 'user' && m.content === text)).toBe(false);
    expect(inner(view).inputDraft).toBe(text);
    expect(inner(view).errorMessage).toBe('');
    const notice = view.shadowRoot!.querySelector('.chat-residency-notice');
    expect(notice?.getAttribute('role')).toBe('alert');
    expect(notice?.textContent ?? '').toContain('another application needs the graphics memory');
    expect(notice?.textContent ?? '').toContain('Your message was not sent');

    // Other parts of the window use the same fetch seam; only the load-anyway call is answered here.
    vi.mocked(authorizedFetch).mockImplementation(async (input) =>
      String(input).endsWith('/api/chat/load-anyway')
        ? new Response(JSON.stringify({ loading: true }), { status: 202 })
        : new Response('{}', { status: 200 }),
    );
    const button = loadAnywayButton(view) as (HTMLElement & { onActivate?: () => void }) | null;
    expect(button).not.toBeNull();
    button!.onActivate!();
    await settle(view);

    const loads = vi
      .mocked(authorizedFetch)
      .mock.calls.filter(([input]) => String(input).includes('/api/chat/load-anyway'));
    expect(loads).toHaveLength(1);
    const [url, init] = loads[0]!;
    expect(String(url)).toBe('http://localhost:5173/api/chat/load-anyway');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ allowLoad: true });

    expect(vi.mocked(consumeShapeStream)).toHaveBeenCalledTimes(2);
    const resent = vi.mocked(consumeShapeStream).mock.calls[1]![1] as Record<string, unknown>;
    // The same turn goes out again (the shape decides the text's field name).
    expect(Object.values(resent)).toContain(text);
    expect(resent['allowLoad']).toBe(true);
    view.remove();
  });

  it('a refusal Load anyway does not apply to says when to try again and offers nothing', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    inner(view).inputDraft = 'hello';
    const refusal = pressureRefusal() as Error & { chatRefusal: Record<string, unknown> };
    refusal.chatRefusal = {
      errorCode: 'CHAT_UNAVAILABLE',
      reason: 'RELOAD_BACKOFF',
      message: 'Chat was paused a moment ago to free graphics memory; it can load again shortly.',
      forceAllowed: false,
      retryAfterMs: 90_000,
    };
    vi.mocked(consumeShapeStream).mockImplementationOnce(() => Promise.reject(refusal));

    await inner(view).send();
    await settle(view);

    const notice = view.shadowRoot!.querySelector('.chat-residency-notice');
    expect(notice?.textContent ?? '').toContain('Try again in about 90 s.');
    expect(loadAnywayButton(view)).toBeNull();
    view.remove();
  });

  it('negative control: with chat loaded there is no residency notice', async () => {
    ai.state = { ...UNLOADED, capabilities: { chat: true, rag: true, extract: false, embedding: false },
      chatUnloadedReason: null };
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.chat-residency-notice')).toBeNull();
    view.remove();
  });
});
