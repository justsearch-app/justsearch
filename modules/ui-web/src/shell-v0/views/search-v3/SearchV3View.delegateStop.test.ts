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
import { dispatchRunControl } from '../../controllers/runControlIntent.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

const STOP = 'You stopped this run';
const RUN = 'run-1';
let events: unknown[];
let threadReads: number;
let runState: string;
let discoveredRuns: unknown[];
let discoveryGate: Promise<void> | undefined;
let threadFailure: boolean;
let observeFails: boolean;
let finishDelete: () => void;
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
  events = []; threadReads = 0; runState = 'READY_FOR_LLM'; discoveredRuns = []; discoveryGate = undefined; threadFailure = false; observeFails = false; conversationId = ''; pendingControl = undefined;
  finishDelete = () => {}; close = () => {};
  announcements = [];
  notice = (e) => announcements.push((e as CustomEvent<{ message: string }>).detail.message);
  document.addEventListener(EPHEMERAL_TOAST_EVENT, notice);
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init: RequestInit = {}) => {
    const url = String(input);
    if (init.method === 'DELETE') {
      await new Promise<void>((resolve) => { finishDelete = resolve; });
      return Response.json({ ok: true });
    }
    if (url.includes('/observe') && observeFails) return new Response('{}', { status: 500 });
    if (url.includes('/api/chat/dispatch') || url.endsWith('/api/chat/agent') || url.includes('/observe')) {
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
      threadReads++;
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
  finishDelete(); close(); __resetAgentSessionStore();
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
function noteCount(el: SearchV3View): number { return text(el).split(STOP).length - 1; }
async function start(el: SearchV3View): Promise<void> {
  el.tier = 'delegate'; await settle();
  const composer = el.shadowRoot!.querySelector('jf-sv3-composer')!;
  composer.dispatchEvent(new CustomEvent('sv3-composer-submit', { detail: { query: 'Inspect this folder', tier: 'delegate' }, bubbles: true, composed: true }));
  await settle();
  const ctrl = getAgentSessionController('');
  // The send is owned by the real control-intent seam; the stream is closed in teardown.
  conversationId = ctrl.conversationId!;
  emit('run_started', { runId: RUN, shapeId: 'core.agent-run', conversationId });
  emit('session_started', { sessionId: RUN });
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

  it('DS3 DS4 this window own Halt still waits beyond the clean-DONE grace', async () => {
    const owner = await mount(); await start(owner);
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    try {
      runState = 'DONE';
      events = [prompt(), wire('2', 'ASSISTANT_MESSAGE', 'Finished answer', { disposition: 'COMPLETED', shapeId: 'core.agent-run' })];
      clickStop(owner); await vi.advanceTimersByTimeAsync(0);
      const initialReads = threadReads;
      await vi.advanceTimersByTimeAsync(20_000);
      expect(threadReads).toBeGreaterThan(initialReads);
      expect(noteCount(owner)).toBe(0);
      await saveStop(true); finishDelete();
      await vi.advanceTimersByTimeAsync(500);
      expect(noteCount(owner)).toBe(1);
      expect(owner.sessions.sessions.flatMap((s) => s.turns)[0]!.disposition).toBe('COMPLETED');
    } finally {
      owner.remove();
      await vi.advanceTimersByTimeAsync(0);
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
