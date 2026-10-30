// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { getAgentSessionController, __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import { __resetConversationListForTest, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import { TURN_STOP_FAILED } from './fixtures.js';
import { dispatchRunControl } from '../../controllers/runControlIntent.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

const STOP = 'You stopped this run';
const RUN = 'run-1';
let events: unknown[];
let threadReads: number;
let threadAddresses: string[];
let startFailure: 'http' | 'network' | undefined;
let runState: string;
let discoveredRuns: unknown[];
let discoveryGate: Promise<void> | undefined;
let threadFailure: boolean;
let observeFails: boolean;
let finishDelete: () => void;
let deleteStatus: number;
let deleteNetworkFailure: boolean;
let deleteResolvers: Array<() => void>;
let emit: (event: string, payload: object) => void;
let close: () => void;
let conversationId: string;
let pendingControl: Promise<unknown> | undefined;
let announcements: string[];
let notice: (e: Event) => void;
const wire = (id: string, kind: string, content: string, attributes = {}): object => ({
  id, kind, content, attributes, originator: 'agent', occurredAt: new Date(Number(id) * 1000).toISOString(),
});
const prompt = (): object => wire('1', 'USER_MESSAGE', 'Inspect this folder', { shapeId: 'core.agent-run' });
const stop = (): object => wire('4', 'PROGRESS', STOP, { phase: 'stop_requested', severity: 'info' });
const cancelled = (): object => wire('3', 'ERROR', 'Session cancelled', { errorCode: 'CANCELLED' });

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetAgentSessionStore(); __resetConversationListForTest(); __resetAiStateForTest();
  __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  events = []; threadReads = 0; threadAddresses = []; startFailure = undefined; runState = 'READY_FOR_LLM'; discoveredRuns = []; discoveryGate = undefined; threadFailure = false; observeFails = false; conversationId = ''; pendingControl = undefined;
  deleteStatus = 200; deleteNetworkFailure = false; deleteResolvers = [];
  finishDelete = () => {}; close = () => {};
  announcements = [];
  notice = (e) => announcements.push((e as CustomEvent<{ message: string }>).detail.message);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, notice);
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init: RequestInit = {}) => {
    const url = String(input);
    if (init.method === 'DELETE') {
      await new Promise<void>((resolve) => { finishDelete = resolve; deleteResolvers.push(resolve); });
      if (deleteNetworkFailure) throw new Error('connection lost');
      return Response.json({ ok: deleteStatus === 200 }, { status: deleteStatus });
    }
    if (url.includes('/observe') && observeFails) return new Response('{}', { status: 500 });
    if (url.includes('/api/chat/dispatch') || url.endsWith('/api/chat/agent') || url.includes('/observe') || url.endsWith('/resume')) {
      if (startFailure === 'http') return new Response('{}', { status: 503 });
      if (startFailure === 'network') throw new Error('connection lost');
      const body = new ReadableStream<Uint8Array>({ start(controller) {
        let closed = false;
        emit = (event, payload) => {
          if (!closed) controller.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`));
        };
        close = () => { if (!closed) { closed = true; controller.close(); } };
        init.signal?.addEventListener('abort', close, { once: true });
      }});
      return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
    }
    if (url.includes('/api/thread/')) {
      threadReads++; threadAddresses.push(url);
      if (!url.endsWith(`/api/thread/${conversationId}`)) return Response.json({ events: [], lifecycles: [] });
      if (threadFailure) return new Response('{}', { status: 503 });
      return Response.json({ conversationId, events, lifecycles: [{ sessionId: RUN, state: runState, actor: 'primary', turns: 1, iterations: 1, toolCalls: 0, actors: [], budget: { initial: 100, consumed: 0, remaining: 100, overBudget: false } }] });
    }
    if (url.includes('/history')) return Response.json({ messages: [], exists: true });
    if (url.includes('/api/chat/conversations')) return Response.json({ sessions: conversationId ? [{ sessionId: conversationId, createdAtMs: 1, lastActiveAtMs: 2, messageCount: 1, firstUserMessage: 'Inspect this folder', shapeId: 'core.agent-run' }] : [] });
    if (url.includes('/api/chat/runs/live')) { await discoveryGate; return Response.json({ runs: discoveredRuns }); }
    return Response.json({ available: true, results: [] });
  }));
  __feedForTest({ inference: { mode: 'online', available: true } as never, status: { worker: { core: { indexedDocuments: 1 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
});

afterEach(async () => {
  for (const el of document.querySelectorAll('jf-sv3-window')) el.remove();
  for (const resolve of deleteResolvers) resolve();
  close(); __resetAgentSessionStore();
  await pendingControl; await settle();
  document.removeEventListener(EPHEMERAL_TOAST_EVENT, notice);
  resetSearchState(); __resetAiStateForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>((r) => setTimeout(r, 0));
}
async function mount(): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window');
  document.body.append(el); await settle(); return el;
}
function main(el: SearchV3View): HTMLElement {
  return el.shadowRoot!.querySelector('jf-sv3-main')!;
}
function text(el: SearchV3View): string { return main(el).shadowRoot!.textContent ?? ''; }
function answer(el: SearchV3View): string { return [...main(el).shadowRoot!.querySelectorAll('jf-markdown-block')].map((block) => block.shadowRoot?.textContent ?? '').join(' '); }
function noteCount(el: SearchV3View): number { return text(el).split(STOP).length - 1; }
async function start(el: SearchV3View, identity = true): Promise<void> {
  el.tier = 'delegate'; await settle();
  const composer = el.shadowRoot!.querySelector('jf-sv3-composer')!;
  composer.dispatchEvent(new CustomEvent('sv3-composer-submit', { detail: { query: 'Inspect this folder', tier: 'delegate' }, bubbles: true, composed: true }));
  await settle();
  const ctrl = getAgentSessionController('');
  // The send is owned by the real control-intent seam; the stream is closed in teardown.
  conversationId = ctrl.conversationId!;
  if (identity) {
    emit('run_started', { runId: RUN, shapeId: 'core.agent-run', conversationId });
    emit('session_started', { sessionId: RUN });
  }
  events = [prompt()]; await settle();
  expect(el.sessions.sessions.flatMap((s) => s.turns)).toHaveLength(1);
}
function clickStop(el: SearchV3View): void {
  el.shadowRoot!.querySelector('jf-sv3-composer')!.dispatchEvent(new CustomEvent('sv3-composer-stop', { bubbles: true, composed: true }));
}
async function saveStop(completed = false): Promise<void> {
  runState = completed ? 'DONE' : 'CANCELLED';
  events = [prompt(), ...(completed ? [wire('2', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' })] : [cancelled()]), stop()];
}

describe('Delegate Stop reconciles the addressed live turn with the durable record', () => {
  it.each(['CANCELLED', 'DONE', 'READY_FOR_LLM'] as const)(
    'S8 P5 reload projects an existing record with stop_requested before %s as on the baseline',
    async (state) => {
      // These records predate the fix; reload must preserve P5 even when the stop note
      // precedes the terminal. Replace both the view and the in-memory controller/store.
      conversationId = 'saved-conversation';
      runState = state;
      let releaseDiscovery!: () => void;
      discoveryGate = new Promise<void>((resolve) => { releaseDiscovery = resolve; });
      events = [prompt(), stop()];
      if (state === 'CANCELLED') events.push(wire('5', 'ERROR', 'Session cancelled', { errorCode: 'CANCELLED' }));
      if (state === 'DONE') events.push(wire('5', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' }));
      if (state === 'READY_FOR_LLM') {
        discoveredRuns = [{ runId: RUN, shapeId: 'core.agent-run', conversationId, state: 'running', park: null }];
      }
      const previous = await mount();
      restoreActiveConversation(conversationId); await settle();
      previous.remove(); close(); __resetAgentSessionStore(); __resetConversationListForTest();
      const el = await mount();
      restoreActiveConversation(conversationId); await settle();
      const turn = el.sessions.sessions.find((session) => session.id === conversationId)?.turns[0];
      expect(threadReads).toBeGreaterThan(0);
      // Before discovery answers, the existing record projection takes the stop note as
      // halt evidence even without a terminal. This is the unchanged P5 behavior;
      // claiming S8's stronger no-unconfirmed-receipt rule here would contradict it.
      expect(turn?.status).toBe('halted');
      expect(noteCount(el)).toBe(1);
      expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0);
      expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).includes('/api/chat/dispatch'))).toHaveLength(0);
      if (state === 'DONE') {
        expect(turn?.answer).toBe('Finished answer');
        expect(turn?.disposition).toBe('COMPLETED');
      }
      releaseDiscovery(); await settle();
      if (state === 'READY_FOR_LLM') {
        expect(el.sessions.sessions.find((session) => session.id === conversationId)?.turns[0]?.status).toBe('streaming');
        expect(noteCount(el)).toBe(0);
      }
    },
  );

  it('S1 stopping uses Ask Stop presentation and retains arriving output until the terminal', async () => {
    const el = await mount(); await start(el); clickStop(el); await settle();
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent ?? '').toContain('Stopping');
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-run-receipt"]')).toBeNull();
    emit('reasoning_chunk', { text: 'Still reasoning' });
    emit('chunk', { text: 'Output after Stop' }); await settle();
    expect(answer(el)).toContain('Output after Stop');
    finishDelete(); await settle();
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-run-receipt"]')).toBeNull();
    emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }); await settle();
    // The channel can remain open for late direction reports after the terminal.
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('halted');
  });

  it.each(['refused', 'network'] as const)('S3 %s uses Ask Stop unconfirmed presentation and keeps Stop retryable', async (failure) => {
    const el = await mount(); await start(el);
    deleteStatus = failure === 'refused' ? 409 : 200; deleteNetworkFailure = failure === 'network';
    clickStop(el); clickStop(el); await settle();
    const deletes = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE');
    expect(deletes()).toHaveLength(1); finishDelete(); await settle();
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent).toBe(TURN_STOP_FAILED);
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('streaming');
    emit('chunk', { text: 'Answer continues' }); await settle(); expect(answer(el)).toContain('Answer continues');
    clickStop(el); await settle(); expect(deletes()).toHaveLength(2);
    emit('done', { finalResponse: 'Answer continues', disposition: 'COMPLETED' }); close(); await settle();
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('complete');
  });

  it.each(['http', 'network', 'lost'].flatMap((failure) => [false, true].map((stopping) => [failure, stopping] as const)))('N2 %s start failure with Stop %s shows a failed receipt and releases the composer without polling', async (failure, stopping) => {
    const el = await mount();
    // Hold the original admission until the reader has pressed Stop.
    if (failure !== 'lost') {
      const originalFetch = fetch;
      let release!: () => void;
      const held = new Promise<void>((resolve) => { release = resolve; });
      vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        if (String(input).includes('/api/chat/dispatch') || String(input).endsWith('/api/chat/agent')) await held;
        return originalFetch(input, init);
      }));
      await start(el, false); if (stopping) clickStop(el); await settle();
      events = []; startFailure = failure as 'http' | 'network'; release();
    } else {
      await start(el, false); if (stopping) clickStop(el); await settle(); events = []; close();
    }
    await settle();
    const ctrl = getAgentSessionController('');
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('failed');
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.querySelector('[data-testid="sv3-composer-stop"]')).toBeNull();
    expect(noteCount(el)).toBe(0); if (stopping) expect(threadReads).toBe(0);
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0);
  });

  it('G1 a start that never answers releases the composer at the Stop deadline with a failed receipt and no polling', async () => {
    const el = await mount();
    const originalFetch = fetch;
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      if (String(input).includes('/api/chat/dispatch') || String(input).endsWith('/api/chat/agent')) {
        return new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
        });
      }
      return originalFetch(input, init);
    }));
    await start(el, false);
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      clickStop(el); await vi.advanceTimersByTimeAsync(10_001);
      const ctrl = getAgentSessionController('');
      expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
      expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('failed');
      expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')).toBeNull();
      expect(el.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.querySelector('[data-testid="sv3-composer-stop"]')).toBeNull();
      expect(noteCount(el)).toBe(0); expect(threadReads).toBe(0);
      expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0);
    } finally {
      el.remove(); await vi.advanceTimersByTimeAsync(0);
      vi.clearAllTimers(); vi.useRealTimers();
    }
  });

  it('N2 early Stop then completion before identity releases the composer without polling', async () => {
    const el = await mount(); await start(el, false); clickStop(el); await settle();
    emit('done', { finalResponse: 'Finished answer', disposition: 'COMPLETED' }); close(); await settle();
    const ctrl = getAgentSessionController('');
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('complete');
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.querySelector('[data-testid="sv3-composer-stop"]')).toBeNull();
    expect(noteCount(el)).toBe(0); expect(threadReads).toBe(0);
  });

  it.each(['known', 'unknown'] as const)('N3 resumed %s conversation reconciles a later CANCELLED only at its actual address', async (address) => {
    const el = await mount();
    const ctrl = getAgentSessionController('');
    pendingControl = dispatchRunControl(ctrl, { kind: 'resume', sessionId: 'prior-run', resumable: true });
    await settle();
    const adopted = el.sessions.sessions[0]!.id;
    el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
      detail: { id: adopted }, bubbles: true, composed: true,
    }));
    await settle();
    threadAddresses = [];
    conversationId = address === 'known' ? 'actual-conversation' : '';
    emit('run_started', { runId: RUN, ...(conversationId ? { conversationId } : {}) });
    emit('session_started', { sessionId: RUN }); await settle();
    clickStop(el); await settle(); finishDelete(); await settle();
    observeFails = true; close(); await settle();
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent).toBe(TURN_STOP_FAILED);
    if (address === 'known') {
      expect(threadAddresses.some((url) => url.endsWith('/api/thread/actual-conversation'))).toBe(true);
      await saveStop();
      await expect.poll(() => el.sessions.sessions.flatMap((s) => s.turns)[0]?.status, { timeout: 1800 }).toBe('halted');
    } else {
      expect(threadAddresses.every((url) => url.endsWith('/api/thread/run-1'))).toBe(true);
      const deletes = (): number => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE').length;
      expect(deletes()).toBe(1); clickStop(el); await settle(); expect(deletes()).toBe(2);
      expect(el.sessions.sessions.flatMap((s) => s.turns)[0]?.status).toBe('streaming');
    }
  });

  it('S3 S6 no stop response becomes unconfirmed, keeps output and allows one new attempt', async () => {
    const el = await mount(); await start(el);
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      const deletes = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE');
      clickStop(el); clickStop(el); await vi.advanceTimersByTimeAsync(0);
      expect(deletes()).toHaveLength(1);
      expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent ?? '').toContain('Stopping');
      await vi.advanceTimersByTimeAsync(10_000);
      expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent).toBe(TURN_STOP_FAILED);
      expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('streaming');
      emit('chunk', { text: 'Output after an unanswered Stop' }); await vi.advanceTimersByTimeAsync(0);
      expect(answer(el)).toContain('Output after an unanswered Stop');
      clickStop(el); clickStop(el); await vi.advanceTimersByTimeAsync(0);
      expect(deletes()).toHaveLength(2);
      emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }); close();
      await vi.advanceTimersByTimeAsync(0);
    } finally {
      vi.clearAllTimers(); vi.useRealTimers();
    }
  });

  it.each(['CANCELLED', 'DONE', 'READY_FOR_LLM'] as const)(
    'S4 losing the stream while stopping follows the %s lifecycle without inventing confirmation',
    async (state) => {
      const el = await mount(); await start(el); clickStop(el); await settle(); finishDelete(); await settle();
      runState = state;
      events = [prompt()];
      if (state === 'DONE') events.push(wire('5', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' }));
      observeFails = true; close(); await settle();
      const turn = el.sessions.sessions.flatMap((session) => session.turns)[0]!;
      expect(turn.status).toBe(state === 'CANCELLED' ? 'halted' : state === 'DONE' ? 'complete' : 'streaming');
      if (state === 'READY_FOR_LLM') {
        expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent).toBe(TURN_STOP_FAILED);
        clickStop(el); await settle();
        expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(2);
      }
      if (state === 'DONE') expect(turn.answer).toBe('Finished answer');
    },
  );

  it('S11 stopping closes the held decision and suppresses later automatic budget continuation', async () => {
    const el = await mount(); await start(el);
    emit('budget_gate', { tokensNeeded: 100, tokensRemaining: 0, totalTokensConsumed: 100 }); await settle();
    main(el).dispatchEvent(new CustomEvent('sv3-run-decision', { detail: {
      kind: 'budget', decision: 'raise', addTokens: 4096, autoContinue: true,
    }, bubbles: true, composed: true })); await settle();
    emit('budget_update', { phase: 'iteration_start', tokensConsumed: 1, tokensRemaining: 4095 });
    emit('context_gate', { promptTokens: 100, contextWindow: 100 }); await settle();
    clickStop(el); await settle();
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-turn-stopping"]')?.textContent ?? '').toContain('Stopping');
    const writes = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST');
    const beforeLateFrames = writes().length;
    emit('budget_gate', { tokensNeeded: 100, tokensRemaining: 0, totalTokensConsumed: 100 });
    emit('context_gate', { promptTokens: 100, contextWindow: 100 }); await settle();
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-run-prompt"]')).toBeNull();
    expect(writes()).toHaveLength(beforeLateFrames);
    emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }); await settle();
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('halted');
  });

  it('S12 one confirmed receipt and announcement retain the final tool count and evidence through refresh', async () => {
    const el = await mount(); await start(el); clickStop(el); await settle();
    emit('tool_call_proposed', { callId: 'last-call', toolName: 'core.search', arguments: '{}', risk: 'LOW' });
    emit('tool_exec_started', { callId: 'last-call', toolName: 'core.search' });
    emit('tool_exec_completed', { callId: 'last-call', success: true, output: 'Found evidence', structuredData: {
      grounding: [{ path: '/evidence.txt', title: 'Evidence', chunkId: 'chunk-1', snippet: 'Retained evidence' }],
    } });
    await settle(); await saveStop();
    emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }); close(); finishDelete();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    const turn = () => el.sessions.sessions.flatMap((s) => s.turns)[0]!;
    expect(turn().toolCalls).toBe(1); expect(turn().evidence?.sources).toHaveLength(1);
    getAgentSessionController('').onProgress({ phase: 'heartbeat', message: '' }); await settle();
    expect(main(el).shadowRoot!.querySelectorAll('[data-testid="sv3-run-receipt"]')).toHaveLength(1);
    expect(announcements.filter((message) => message === STOP)).toHaveLength(1);
    expect(turn().toolCalls).toBe(1); expect(turn().evidence?.sources).toHaveLength(1);
  });

  it('DS1 DS2 DS3 DS8 delayed cancellation appears once without reload or focus movement', async () => {
    const el = await mount(); await start(el);
    const input = el.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!;
    input.focus(); clickStop(el); await settle();
    expect(threadReads).toBeGreaterThan(0); expect(el.recordNotice).toBe(false); expect(noteCount(el)).toBe(0);
    await saveStop();
    // The server has saved Stop, but its HTTP response is still held. Polling must follow truth.
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    expect(document.activeElement).toBe(el);
    expect(el.shadowRoot!.activeElement?.shadowRoot!.activeElement).toBe(input);
    expect(text(el)).not.toContain('Working…');
    expect(announcements.filter((m) => m === STOP)).toHaveLength(1);
    finishDelete(); await settle();
    // Ordinary refresh/replay cannot announce the same saved Stop again.
    getAgentSessionController('').onProgress({ phase: 'heartbeat', message: '' }); await settle();
    expect(noteCount(el)).toBe(1); expect(announcements.filter((m) => m === STOP)).toHaveLength(1);
  });

  it('DS4 keeps completed output and disposition when completion wins Stop', async () => {
    const el = await mount(); await start(el); clickStop(el); await settle();
    await saveStop(true); finishDelete();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    const turn = el.sessions.sessions.flatMap((s) => s.turns)[0]!;
    expect(turn.answer).toBe('Finished answer'); expect(turn.disposition).toBe('COMPLETED');
    expect(turn.status).toBe('halted');
  });

  it('DS4 DS5 an observer reconciles Stop saved after its completed record read', async () => {
    const observer = await mount(); await start(observer);
    runState = 'DONE';
    events = [prompt(), wire('2', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' })];
    const readsBeforeCompletion = threadReads;
    emit('done', { finalResponse: 'Finished answer', disposition: 'COMPLETED' }); close();
    await settle();
    expect(threadReads).toBeGreaterThan(readsBeforeCompletion);
    const turn = () => observer.sessions.sessions.flatMap((s) => s.turns)[0]!;
    expect(turn().status).toBe('complete'); expect(noteCount(observer)).toBe(0);
    // Another window's DELETE can reach the Engine after this observer's DONE read.
    // This observer never requests Halt; the durable record is its only Stop authority.
    await saveStop(true);
    await expect.poll(() => noteCount(observer), { timeout: 1800 }).toBe(1);
    expect(turn().answer).toBe('Finished answer'); expect(turn().disposition).toBe('COMPLETED');
    expect(turn().status).toBe('halted'); expect(text(observer)).toContain('stopped by you');
    expect(announcements.filter((m) => m === STOP)).toHaveLength(1);
    // Once Stop is confirmed, this refresh retires rather than polling or announcing forever.
    const confirmedReads = threadReads;
    await new Promise<void>((resolve) => setTimeout(resolve, 650));
    expect(threadReads).toBe(confirmedReads);
    expect(announcements.filter((m) => m === STOP)).toHaveLength(1);
  });

  it('DN1 clean DONE stops record reads after the five-second grace, also after navigation', async () => {
    const owner = await mount(); await start(owner);
    const observer = await mount();
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      runState = 'DONE';
      events = [prompt(), wire('2', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' })];
      emit('done', { finalResponse: 'Finished answer', disposition: 'COMPLETED' }); close();
      await vi.advanceTimersByTimeAsync(0);
      expect(owner.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('complete');
      expect(observer.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('complete');
      const initialReads = threadReads;
      owner.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
      await vi.advanceTimersByTimeAsync(4900);
      expect(owner.sessions.activeId).toBeNull();
      expect(threadReads).toBeGreaterThan(initialReads);
      await vi.advanceTimersByTimeAsync(100);
      const readsAtGraceEnd = threadReads;
      await vi.advanceTimersByTimeAsync(20_000);
      expect(threadReads).toBe(readsAtGraceEnd);
      expect(noteCount(observer)).toBe(0);
      expect(announcements.filter((m) => m === STOP)).toHaveLength(0);
    } finally {
      owner.remove(); observer.remove();
      await vi.advanceTimersByTimeAsync(0);
      vi.clearAllTimers(); vi.useRealTimers();
    }
  });

  it.each(['accepted', 'refused', 'network', 'no-answer'] as const)('N4 %s Stop bounds record polling to the five-second grace even after further notifications', async (outcome) => {
    const owner = await mount(); await start(owner);
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      deleteStatus = outcome === 'refused' ? 409 : 200; deleteNetworkFailure = outcome === 'network';
      clickStop(owner); await vi.advanceTimersByTimeAsync(0);
      if (outcome !== 'no-answer') { finishDelete(); await vi.advanceTimersByTimeAsync(0); }
      const initialReads = threadReads;
      await vi.advanceTimersByTimeAsync(4900);
      expect(threadReads).toBeGreaterThan(initialReads);
      await vi.advanceTimersByTimeAsync(100);
      const atGraceEnd = threadReads;
      getAgentSessionController('').onProgress({ phase: 'heartbeat', message: '' });
      await vi.advanceTimersByTimeAsync(20_000);
      expect(threadReads).toBe(atGraceEnd);
      expect(noteCount(owner)).toBe(0);
      expect(owner.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('streaming');
    } finally {
      owner.remove(); await vi.advanceTimersByTimeAsync(0);
      vi.clearAllTimers(); vi.useRealTimers();
    }
  });

  it('N4 an accepted Halt keeps the completed answer and disposition when its saved confirmation arrives within the grace', async () => {
    const owner = await mount(); await start(owner);
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      runState = 'DONE';
      events = [prompt(), wire('2', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' })];
      clickStop(owner); await vi.advanceTimersByTimeAsync(0);
      finishDelete(); await vi.advanceTimersByTimeAsync(4000);
      expect(noteCount(owner)).toBe(0);
      await saveStop(true);
      await vi.advanceTimersByTimeAsync(500);
      expect(noteCount(owner)).toBe(1);
      expect(owner.sessions.sessions.flatMap((s) => s.turns)[0]!.disposition).toBe('COMPLETED');
    } finally {
      owner.remove(); await vi.advanceTimersByTimeAsync(0);
      vi.clearAllTimers(); vi.useRealTimers();
    }
  });

  it.each(['pending', 'CANCELLED', 'DONE'] as const)('G2 a resumed run adopted into another conversation bounds accepted Stop polling after %s', async (terminal) => {
    const owner = await mount();
    const ctrl = getAgentSessionController('');
    pendingControl = dispatchRunControl(ctrl, { kind: 'resume', sessionId: 'prior-run', resumable: true });
    await settle();
    const adopted = owner.sessions.sessions[0]!.id;
    owner.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
      detail: { id: adopted }, bubbles: true, composed: true,
    }));
    await settle();
    conversationId = 'actual-conversation';
    emit('run_started', { runId: RUN, conversationId }); emit('session_started', { sessionId: RUN });
    await settle();
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      clickStop(owner); await vi.advanceTimersByTimeAsync(0);
      finishDelete(); await vi.advanceTimersByTimeAsync(0);
      if (terminal === 'CANCELLED') emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' });
      if (terminal === 'DONE') emit('done', { finalResponse: 'Finished answer', disposition: 'COMPLETED' });
      await vi.advanceTimersByTimeAsync(0);
      const initialReads = threadReads;
      await vi.advanceTimersByTimeAsync(4900);
      expect(threadReads).toBeGreaterThan(initialReads);
      expect(threadAddresses.some((url) => url.endsWith('/api/thread/actual-conversation'))).toBe(true);
      await vi.advanceTimersByTimeAsync(100);
      const atGraceEnd = threadReads;
      ctrl.onProgress({ phase: 'heartbeat', message: '' });
      await vi.advanceTimersByTimeAsync(20_000);
      expect(threadReads).toBe(atGraceEnd);
      expect(noteCount(owner)).toBe(0);
    } finally {
      owner.remove(); await vi.advanceTimersByTimeAsync(0);
      vi.clearAllTimers(); vi.useRealTimers();
    }
  });

  it('DS5 DS7 remote cancellation with an early record read recovers once without generic failure', async () => {
    const el = await mount(); await start(el);
    emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }); close(); await settle();
    await saveStop();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('halted');
    expect(text(el)).not.toContain('Session cancelled');
  });

  it('DS5 both windows reconcile one Stop and keep a different conversation untouched', async () => {
    const el = await mount(); await start(el);
    const observer = await mount();
    clickStop(observer); await settle();
    const addressedId = conversationId;
    el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
    await settle(); await saveStop(); finishDelete();
    await expect.poll(() => noteCount(observer), { timeout: 1800 }).toBe(1);
    await expect.poll(() => el.sessions.sessions.find((s) => s.id === addressedId)?.turns[0]?.activity.filter((a) => a.kind === 'note' && a.text === STOP).length, { timeout: 1800 }).toBe(1);
    expect(el.sessions.activeId).toBeNull(); expect(noteCount(el)).toBe(0);
    expect(observer.sessions.sessions.flatMap((s) => s.turns)).toHaveLength(1);
  });

  it('DS7 a dropped observer retries a failed record fallback without inventing Stop', async () => {
    const el = await mount(); await start(el);
    threadFailure = true;
    getAgentSessionController('').onAttachNotLive({}); close(); await settle();
    expect(noteCount(el)).toBe(0); expect(el.recordNotice).toBe(true);
    threadFailure = false; await saveStop();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    expect(el.recordNotice).toBe(false);
  });

  it('DS5 DS7 cold attach keeps the named conversation and its already loaded record turn', async () => {
    let releaseDiscovery!: () => void;
    discoveryGate = new Promise<void>((resolve) => { releaseDiscovery = resolve; });
    conversationId = 'saved-conversation'; events = [prompt()];
    discoveredRuns = [{ runId: RUN, shapeId: 'core.agent-run', conversationId, state: 'running', park: null }];
    const el = await mount();
    restoreActiveConversation(conversationId); await settle();
    expect(el.sessions.sessions.find((s) => s.id === conversationId)?.turns).toHaveLength(1);
    releaseDiscovery(); await settle();
    // The attachment already knows the conversation from discovery, before its first stream frame.
    expect(el.sessions.sessions.map((s) => s.id)).toEqual([conversationId]);
    emit('run_started', { runId: RUN, shapeId: 'core.agent-run', conversationId });
    emit('session_started', { sessionId: RUN }); await settle();
    expect(el.sessions.sessions.find((s) => s.id === conversationId)?.turns).toHaveLength(1);
    emit('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }); close(); await settle();
    await saveStop();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    expect(el.sessions.sessions.find((s) => s.id === conversationId)?.turns).toHaveLength(1);
  });

  it('DN2 an old-window Halt updates the observing Search turn and preserves saved partial output', async () => {
    const el = await mount(); await start(el);
    emit('chunk', { text: 'A partial answer' }); await settle();
    pendingControl = dispatchRunControl(getAgentSessionController(''), { kind: 'halt' }); await settle();
    await saveStop(); events.splice(1, 0, wire('2', 'ASSISTANT_MESSAGE', 'A partial answer', { shapeId: 'core.agent-run' })); finishDelete();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.answer).toBe('A partial answer');
    expect(getAgentSessionController('').runInFlight).toBe(false);
  });

  it.each(['budget', 'context', 'approval'] as const)('DS6 clears a held %s decision when Stop settles', async (gate) => {
    const el = await mount(); await start(el);
    if (gate === 'budget') emit('budget_gate', { tokensNeeded: 100, tokensRemaining: 0, totalTokensConsumed: 100 });
    if (gate === 'context') emit('context_gate', { promptTokens: 100, contextWindow: 100 });
    if (gate === 'approval') emit('tool_call_pending', { callId: 'c1', toolName: 'core.search', arguments: '{}', gateBehavior: 'inline_confirm', risk: 'LOW' });
    await settle(); clickStop(el); clickStop(el); await saveStop(); finishDelete();
    await expect.poll(() => noteCount(el), { timeout: 1800 }).toBe(1);
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-run-prompt"]')).toBeNull();
    expect(el.sessions.sessions.flatMap((s) => s.turns)[0]!.status).toBe('halted');
  });
});


describe('Delegate Stop preserves other terminal meanings', () => {
  it('DN1 a dropped transport alone does not remove the held decision', async () => {
    const el = await mount(); await start(el);
    emit('budget_gate', { tokensNeeded: 100, tokensRemaining: 0, totalTokensConsumed: 100 });
    await settle(); observeFails = true; close(); await settle();
    expect(getAgentSessionController('').runInFlight).toBe(false);
    expect(main(el).shadowRoot!.querySelector('[data-testid="sv3-run-budget-raise-little"]')).not.toBeNull();
  });

  it.each(['complete', 'failure'] as const)('DN1 preserves genuine %s without inventing Stop', async (outcome) => {
    const el = await mount(); await start(el);
    runState = outcome === 'complete' ? 'DONE' : 'ERROR';
    if (outcome === 'complete') {
      events = [prompt(), wire('2', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' })];
      emit('done', { finalResponse: 'Finished answer', disposition: 'COMPLETED', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 1 });
    } else {
      events = [prompt(), wire('2', 'ERROR', 'The tool failed', { errorCode: 'TOOL_FAILURE' })];
      emit('error', { error: 'The tool failed', errorCode: 'TOOL_FAILURE' });
    }
    close(); await settle();
    expect(noteCount(el)).toBe(0); expect(announcements.filter((m) => m === STOP)).toHaveLength(0);
    const turn = el.sessions.sessions.flatMap((s) => s.turns)[0]!;
    expect(turn.status).toBe(outcome === 'complete' ? 'complete' : 'failed');
    if (outcome === 'complete') expect(turn.answer).toBe('Finished answer');
    else expect(text(el)).toContain('The tool failed');
  });
});
