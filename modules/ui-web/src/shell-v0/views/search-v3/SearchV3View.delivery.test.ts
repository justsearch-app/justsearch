// @vitest-environment happy-dom
/** NR15/RR15: real composer and SSE parser; only HTTP is controlled. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { restoreActiveConversation, loadConversations, __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';

type Http = (url: unknown, init?: RequestInit) => Promise<Response>;
let http: ReturnType<typeof vi.fn<Http>>;
const dispatches = () => http.mock.calls.filter(c => String(c[0]).includes('/api/chat/dispatch'));
const body = (i = 0) => JSON.parse(dispatches()[i]![1]!.body as string) as Record<string, unknown>;
function state(docs = 42, unloaded = false, enabled = true): void {
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { inference: { chatEnabledSpec: enabled }, worker: { core: { indexedDocuments: docs } },
      operating: { chat: { state: unloaded ? 'UNLOADED' : 'READY' } },
      readiness: { composites: { aiFeatures: { state: unloaded ? 'DEGRADED' : 'READY', reasonCodes: unloaded ? ['inference.unloaded_gpu_pressure'] : [] } } },
    } as unknown as StatusSnapshot });
  __feedContactForTest();
}
const attachmentKey = 'justsearch.draft.attachment.v1.search-v3.main';
async function settle(el: SearchV3View): Promise<void> {
  for (let i = 0; i < 7; i++) {
    if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
    else await new Promise(r => setTimeout(r, 0));
  }
  await el.updateComplete;
  await composer(el).updateComplete;
}
function composer(el: SearchV3View): Sv3Composer { return el.shadowRoot!.querySelector('jf-sv3-composer')!; }
function field(el: SearchV3View): HTMLTextAreaElement { return composer(el).shadowRoot!.querySelector('[data-testid=sv3-composer-input]')!; }
async function type(el: SearchV3View, text: string): Promise<void> {
  field(el).value = text; field(el).dispatchEvent(new Event('input')); await composer(el).updateComplete;
}
async function mount(shape?: string): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window') as SearchV3View;
  el.apiBase = 'http://127.0.0.1:9999';
  if (shape) el.setAttribute('shape-id', shape);
  document.body.append(el);
  (el as unknown as { tier: string }).tier = shape === 'core.extract' ? 'structured' : 'ask';
  await settle(el); return el;
}
async function send(el: SearchV3View, text: string): Promise<void> {
  await type(el, text);
  composer(el).shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-composer-send]')!.click();
  await settle(el);
}
function action(el: SearchV3View, id: string): HTMLButtonElement | null { return el.shadowRoot!.querySelector(`[data-testid=${id}]`); }
function retained(): { payload: { task: Record<string, unknown> | null } } {
  const bytes = sessionStorage.getItem(attachmentKey); expect(bytes).toEqual(expect.any(String)); return JSON.parse(bytes!);
}
beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  http = vi.fn<Http>();
  vi.stubGlobal('fetch', http); state();
});
afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState(); __resetAiStateForTest(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'content-type': 'application/json' } });
let rows: ThreadEvent[];
let stream: ReadableStreamDefaultController<Uint8Array>;
const turnId = () => body().turnId as string;
const target = () => body().conversationId as string;
const row = (id: string, kind: ThreadEvent['kind'], content: string, dispatch: string, extra = {}): ThreadEvent => ({
  id, kind, content, occurredAt: new Date(1700000000000 + rows.length * 1000).toISOString(),
  originator: kind === 'USER_MESSAGE' ? 'user' : 'agent', attributes: { shapeId: 'core.rag-ask', turnId: dispatch, ...extra },
});
function transcript(el: SearchV3View): ShadowRoot {
  return el.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!;
}
function answer(el: SearchV3View): string {
  return transcript(el).querySelector('jf-markdown-block')?.shadowRoot?.textContent ?? '';
}
function saveOutcome(dispatch = turnId(), extra = {}, kind: ThreadEvent['kind'] = 'ASSISTANT_MESSAGE'): void {
  rows.push(row('question-1', 'USER_MESSAGE', 'same words', dispatch));
  rows.push(row('answer-1', kind, 'Saved answer', dispatch, extra));
}
function newConversation(el: SearchV3View): void {
  el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
}
const recordReads = () => http.mock.calls.filter(c => String(c[0]).includes('/api/thread/'));
async function waitForRead(el: SearchV3View): Promise<void> {
  await new Promise(r => setTimeout(r, 650)); await settle(el);
}
beforeEach(() => {
  rows = [];
  http.mockImplementation(async (url: unknown, init?: RequestInit) => {
    const path = String(url);
    if (path.includes('/dispatch')) return new Response(new ReadableStream<Uint8Array>({ start(c) { stream = c;
      init?.signal?.addEventListener('abort', () => c.error(new DOMException('Aborted', 'AbortError')), { once: true });
    } }),
      { headers: { 'content-type': 'text/event-stream' } });
    if (path.includes('/api/thread/')) return json({ conversationId: decodeURIComponent(path.split('/api/thread/')[1]!), events: rows, lifecycles: [] });
    if (path.includes('/history')) return json({ messages: rows.map(r => ({ id: r.id, role: r.kind === 'USER_MESSAGE' ? 'user' : 'assistant', content: r.content, ...r.attributes })) });
    if (path.endsWith('/api/chat/conversations')) return json({ conversations: [] });
    return json({});
  });
});

it('addresses progress to the exact live attempt after opening another conversation', async () => {
  const el = await mount(); await send(el, 'same words');
  const original = target();
  restoreActiveConversation('other'); await settle(el);
  expect(el.shadowRoot!.textContent).not.toContain('Delivery unknown');
  expect(el.shadowRoot!.textContent).toContain('Request in progress');
  restoreActiveConversation(original); await settle(el);
  expect(composer(el).slotKind).toBe('stop');
  expect(http.mock.calls.filter(c => String(c[0]).endsWith('/stop'))).toHaveLength(0);
  stream.enqueue(new TextEncoder().encode('event: content\ndata: {"text":"Answer"}\n\nevent: done\ndata: {}\n\n')); stream.close();
  await settle(el);
  expect(retained().payload.task).toBeNull(); expect(dispatches()).toHaveLength(1);
});

it.each(['New', 'reload'] as const)('resolves the retained exact saved outcome after %s without sending again', async departure => {
  let el = await mount(); await send(el, 'same words');
  const original = target();
  if (departure === 'New') { newConversation(el); await settle(el); }
  else { el.remove(); el = await mount(); }
  await type(el, 'newer B');
  saveOutcome(); await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  expect(field(el).value).toBe('newer B'); expect(dispatches()).toHaveLength(1);
  if (departure === 'New') expect(composer(el).slotKind).not.toBe('stop');
  restoreActiveConversation(original); await settle(el);
  expect(answer(el)).toContain('Saved answer');
  expect(dispatches()).toHaveLength(1);
});

it.each(['question only', 'different dispatch', 'synthetic incomplete', 'empty', 'failed read', 'legacy'] as const)(
  'preserves uncertainty without exact terminal evidence: %s', async evidence => {
    const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
    if (evidence === 'question only') rows.push(row('question-1', 'USER_MESSAGE', 'same words', turnId()));
    if (evidence === 'different dispatch') saveOutcome('another-window');
    if (evidence === 'synthetic incomplete') saveOutcome(turnId(), { errorCode: 'INCOMPLETE_TURN' }, 'ERROR');
    if (evidence === 'legacy') { saveOutcome(); rows = rows.map(r => ({ ...r, attributes: { shapeId: 'core.rag-ask' } })); }
    if (evidence === 'failed read') http.mockImplementation(async () => new Response('unavailable', { status: 503 }));
    await waitForRead(el);
    expect(retained().payload.task).not.toBeNull();
    expect(el.shadowRoot!.textContent).toContain('Delivery unknown');
    expect(action(el, 'sv3-residency-retry')).toBeNull();
    expect(action(el, 'sv3-residency-force')).toBeNull();
    expect(dispatches()).toHaveLength(1);
    // Dispose before the next test can satisfy this retained address.
    el.remove();
  });

it('reads a retained address even before the conversation appears in the list', async () => {
  const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
  const count = recordReads().length;
  await waitForRead(el);
  expect(recordReads().length).toBeGreaterThan(count);
  expect(dispatches()).toHaveLength(1);
});


it('reload after a lost terminal shows the saved answer and retires only its attempt', async () => {
  let el = await mount(); await send(el, 'same words');
  const original = target(); saveOutcome();
  el.remove(); el = await mount(); await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  restoreActiveConversation(original); await settle(el);
  expect(answer(el)).toContain('Saved answer');
  expect(dispatches()).toHaveLength(1);
});

it.each(['error', 'no evidence', 'stopped'] as const)('a saved %s resolves delivery and shows the actual outcome', async outcome => {
  const el = await mount(); await send(el, 'same words'); const original = target();
  newConversation(el); await settle(el);
  if (outcome === 'error') saveOutcome(turnId(), { errorCode: 'LLM_ERROR' }, 'ERROR');
  if (outcome === 'no evidence') saveOutcome(turnId(), { errorCode: 'NO_CONTENT', retrievalScoped: false }, 'ERROR');
  if (outcome === 'stopped') saveOutcome(turnId(), { disposition: 'CANCELLED' });
  await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  restoreActiveConversation(original); await settle(el);
  if (outcome === 'error') expect(transcript(el).textContent).toContain('Saved answer');
  if (outcome === 'no evidence') {
    expect(transcript(el).textContent).toContain('Answer without your files');
    expect(answer(el)).toBe('');
  }
  if (outcome === 'stopped') {
    expect(transcript(el).textContent).toContain('Stopped');
    expect(answer(el)).toContain('Saved answer');
  }
  expect(dispatches()).toHaveLength(1);
});

it('completion saved before the Stop response wins even when its live terminal is lost', async () => {
  const fallback = http.getMockImplementation()!;
  let release!: (r: Response) => void;
  http.mockImplementation((url: unknown, init?: RequestInit) => String(url).endsWith('/stop')
    ? new Promise<Response>(r => { release = r; }) : fallback(url, init));
  const el = await mount(); await send(el, 'same words'); const original = target();
  composer(el).shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-composer-stop]')!.click();
  await settle(el); saveOutcome();
  newConversation(el); await settle(el);
  release(json({ status: 'NOT_RUNNING' })); await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  restoreActiveConversation(original); await settle(el);
  expect(answer(el)).toContain('Saved answer');
  expect(transcript(el).textContent).not.toContain('Stopped by you');
  expect(dispatches()).toHaveLength(1);
});

it('a failed retirement ACK retains recoverable A and companion B until a successful check', async () => {
  const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
  await type(el, 'newer B');
  const storage = sessionStorage;
  const original = storage.setItem;
  const descriptor = Object.getOwnPropertyDescriptor(storage, 'setItem')
    ?? { configurable: true, writable: true, enumerable: false, value: original };
  let faults = 0;
  Object.defineProperty(storage, 'setItem', { ...descriptor, value: (key: string, value: string) => {
    if (key === attachmentKey && JSON.parse(value).payload.task === null) {
      faults++; throw new DOMException('injected retirement failure', 'QuotaExceededError');
    }
    original(key, value);
  } });
  try {
    saveOutcome(); await waitForRead(el);
    expect(faults).toBeGreaterThan(0);
    expect(retained().payload.task).toMatchObject({ raw: 'same words', dispatchTurnId: turnId() });
    expect(sessionStorage.getItem(attachmentKey)).toContain('newer B');
    expect(field(el).value).toBe('newer B');
    expect(el.shadowRoot!.textContent).toContain('could not be saved safely');
    await send(el, 'newer B'); expect(dispatches()).toHaveLength(1);
  } finally { Object.defineProperty(storage, 'setItem', descriptor); }
  action(el, 'sv3-residency-check')!.click(); await settle(el);
  expect(retained().payload.task).toBeNull();
  expect(field(el).value).toBe('newer B');
  expect(sessionStorage.getItem(attachmentKey)).toContain('newer B');
});

it('resolving saved A after reload never authorizes restored B to send before review', async () => {
  let el = await mount(); await send(el, 'same words'); await type(el, 'newer B');
  el.remove(); el = await mount();
  expect(field(el).value).toBe('newer B');
  saveOutcome(); await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  expect(action(el, 'sv3-draft-review-confirm')).not.toBeNull();
  composer(el).shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-composer-send]')!.click(); await settle(el);
  expect(field(el).value).toBe('newer B');
  expect(dispatches()).toHaveLength(1);
});

it('each authorized admission continuation retains a fresh identity before its POST', async () => {
  const fallback = http.getMockImplementation()!;
  const ids: unknown[] = [];
  const retainedIds: unknown[] = [];
  http.mockImplementation((url: unknown, init?: RequestInit) => {
    if (String(url).includes('/dispatch')) {
      const sent = JSON.parse(init!.body as string);
      ids.push(sent.turnId);
      retainedIds.push(retained().payload.task?.dispatchTurnId);
      return Promise.resolve(new Response(JSON.stringify({ errorCode: 'CHAT_UNAVAILABLE',
        error: 'Chat is paused.', reason: 'GPU_BUSY', forceAllowed: true }), { status: 503 }));
    }
    if (String(url).includes('/load-anyway')) return Promise.resolve(json({}));
    return fallback(url, init);
  });
  const el = await mount(); await send(el, 'same words');
  expect(action(el, 'sv3-residency-force')).not.toBeNull();
  action(el, 'sv3-residency-force')!.click(); await settle(el);
  action(el, 'sv3-residency-retry')!.click(); await settle(el);
  expect(ids).toHaveLength(3);
  expect(ids.every(id => typeof id === 'string' && id.length > 0)).toBe(true);
  expect(new Set(ids).size).toBe(3);
  expect(retainedIds).toEqual(ids);
  const before = dispatches().length;
  newConversation(el); await waitForRead(el);
  expect(action(el, 'sv3-residency-force')).toBeNull();
  expect(action(el, 'sv3-residency-retry')).toBeNull();
  expect(dispatches()).toHaveLength(before);
});


it.each(['manual', 'open', 'reconnect'] as const)('S15 finite checking budget retains uncertainty until %s rearms it', async rearm => {
  vi.useFakeTimers();
  const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
  const first = recordReads().length;
  await vi.advanceTimersByTimeAsync(31000); await settle(el);
  const exhausted = recordReads().length;
  expect(exhausted).toBeGreaterThan(first);
  expect(retained().payload.task).not.toBeNull();
  expect(action(el, 'sv3-residency-check')).not.toBeNull();
  // Unrelated arrivals and rerenders cannot extend the budget.
  for (let i = 0; i < 3; i++) { restoreActiveConversation('other-' + i); await settle(el); }
  const afterNavigation = recordReads().length;
  el.requestUpdate(); await vi.advanceTimersByTimeAsync(60000); await settle(el);
  expect(recordReads()).toHaveLength(afterNavigation);
  expect(retained().payload.task).not.toBeNull();
  if (rearm === 'manual') action(el, 'sv3-residency-check')!.click();
  if (rearm === 'open') restoreActiveConversation(target());
  if (rearm === 'reconnect') { __feedContactForTest(0); await settle(el); __feedContactForTest(); }
  await vi.advanceTimersByTimeAsync(1000); await settle(el);
  expect(retained().payload.task).not.toBeNull();
  saveOutcome(); await vi.advanceTimersByTimeAsync(1000); await settle(el);
  expect(retained().payload.task).toBeNull();
  const settled = recordReads().length;
  await vi.advanceTimersByTimeAsync(60000); await settle(el);
  expect(recordReads()).toHaveLength(settled);
  expect(dispatches()).toHaveLength(1);
});

it('S15 a slow record read survives polling ticks and can settle its own attempt', async () => {
  vi.useFakeTimers();
  const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
  const fallback = http.getMockImplementation()!;
  const signals: AbortSignal[] = [];
  let release!: (r: Response) => void;
  http.mockImplementation((url: unknown, init?: RequestInit) => {
    if (String(url).includes('/api/thread/')) {
      signals.push(init!.signal!);
      return new Promise<Response>(r => { release = r; });
    }
    return fallback(url, init);
  });
  restoreActiveConversation(target()); await settle(el);
  await vi.advanceTimersByTimeAsync(750); await settle(el);
  expect(signals).toHaveLength(1);
  await vi.advanceTimersByTimeAsync(2500); await settle(el);
  expect(signals).toHaveLength(1);
  expect(signals[0]!.aborted).toBe(false);
  saveOutcome(); release(json({ conversationId: target(), events: rows, lifecycles: [] })); await settle(el);
  expect(retained().payload.task).toBeNull();
  expect(dispatches()).toHaveLength(1);
});

it('S15 a timed-out abort-ignoring read cannot stop checking or override a newer result', async () => {
  vi.useFakeTimers();
  const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
  const fallback = http.getMockImplementation()!;
  let release!: (r: Response) => void;
  let signal!: AbortSignal;
  http.mockImplementation((url: unknown, init?: RequestInit) => {
    if (String(url).includes('/api/thread/') && !release) {
      signal = init!.signal!;
      return new Promise<Response>(r => { release = r; });
    }
    return fallback(url, init);
  });
  await vi.advanceTimersByTimeAsync(750); await settle(el);
  expect(release).toBeTypeOf('function');
  await vi.advanceTimersByTimeAsync(11000); await settle(el);
  expect(signal.aborted).toBe(true);
  saveOutcome(); await vi.advanceTimersByTimeAsync(1000); await settle(el);
  expect(retained().payload.task).toBeNull();
  restoreActiveConversation(target()); await settle(el);
  expect(answer(el)).toContain('Saved answer');
  release(json({ conversationId: target(), events: [], lifecycles: [] })); await settle(el);
  expect(answer(el)).toContain('Saved answer');
  expect(dispatches()).toHaveLength(1);
});

it.each(['navigate', 'delete'] as const)('S15 a late retained-address read cannot undo %s', async departure => {
  vi.useFakeTimers();
  const el = await mount(); await send(el, 'same words'); newConversation(el); await settle(el);
  const original = target();
  const fallback = http.getMockImplementation()!;
  let release!: (r: Response) => void;
  http.mockImplementation((url: unknown, init?: RequestInit) => {
    if (String(url).includes('/api/thread/' + original)) return new Promise<Response>(r => { release = r; });
    if (String(url).includes('/api/chat/conversations?')) return Promise.resolve(json({ sessions: [], deletedIds: departure === 'delete' ? [original] : [] }));
    return fallback(url, init);
  });
  restoreActiveConversation(original); await settle(el);
  await vi.advanceTimersByTimeAsync(750); await settle(el);
  expect(release).toBeTypeOf('function');
  restoreActiveConversation('other'); await settle(el); await type(el, 'B elsewhere');
  if (departure === 'delete') { await loadConversations(); await settle(el); }
  saveOutcome(); release(json({ conversationId: target(), events: rows, lifecycles: [] })); await settle(el);
  expect(el.sessions.activeId).toBe('other');
  expect(field(el).value).toBe('B elsewhere');
  expect(answer(el)).not.toContain('Saved answer');
  if (departure === 'delete') {
    expect(el.sessions.sessions.some(s => s.id === original)).toBe(false);
    const stopped = recordReads().length;
    await vi.advanceTimersByTimeAsync(60000); await settle(el);
    expect(recordReads()).toHaveLength(stopped);
  }
  expect(dispatches()).toHaveLength(1);
});


it('S15 an empty view without a retained attempt never starts automatic record reads', async () => {
  vi.useFakeTimers();
  const el = await mount();
  const before = recordReads().length;
  await vi.advanceTimersByTimeAsync(60000); await settle(el);
  expect(recordReads()).toHaveLength(before);
  expect(dispatches()).toHaveLength(0);
});

it('a Retry gets a fresh retained dispatch and resolves only in the fork actually sent to', async () => {
  const el = await mount(); await send(el, 'same words');
  const originalId = turnId();
  saveOutcome();
  rows = rows.map((r, i) => ({ ...r, id: `11111111-2222-4333-8444-${String(i + 1).padStart(12, '0')}` }));
  stream.enqueue(new TextEncoder().encode('event: done\ndata: {}\n\n')); stream.close(); await settle(el);
  expect(retained().payload.task).toBeNull();
  const turn = el.sessions.sessions.find(s => s.id === target())!.turns[0]!;
  expect(turn.recordId).toBe(rows[0]!.id);
  const fallback = http.getMockImplementation()!;
  let forkRows: ThreadEvent[] = [];
  const retainedAtPost: Record<string, unknown>[] = [];
  http.mockImplementation((url: unknown, init?: RequestInit) => {
    const path = String(url);
    if (path.includes('/branch?')) return Promise.resolve(json({ sessionId: 'child-fork' }));
    if (path.includes('/api/thread/child-fork')) return Promise.resolve(json({ conversationId: 'child-fork', events: forkRows, lifecycles: [] }));
    if (path.includes('/dispatch')) retainedAtPost.push({ ...retained().payload.task });
    return fallback(url, init);
  });
  el.shadowRoot!.querySelector('jf-sv3-main')!.dispatchEvent(new CustomEvent('sv3-branch-action', {
    detail: { action: 'retry', turnId: turn.id }, bubbles: true, composed: true,
  })); await settle(el);
  expect(dispatches()).toHaveLength(2);
  expect(body(1).conversationId).toBe('child-fork');
  expect(body(1).turnId).not.toBe(originalId);
  expect(retainedAtPost).toEqual([expect.objectContaining({ target: 'child-fork', dispatchTurnId: body(1).turnId })]);
  newConversation(el); await settle(el);
  // A parent's inherited stamp cannot confirm this new child dispatch.
  forkRows = [...rows]; await waitForRead(el);
  expect(retained().payload.task?.dispatchTurnId).toBe(body(1).turnId);
  forkRows = [row('child-question', 'USER_MESSAGE', 'same words', String(body(1).turnId)),
    row('child-answer', 'ASSISTANT_MESSAGE', 'Child saved answer', String(body(1).turnId))];
  await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  expect(dispatches()).toHaveLength(2);
});

it('saved completion invalidates cached companion history before a follow-up consumes context', async () => {
  let el = await mount(); await send(el, 'same words');
  el.remove(); el = await mount();
  const historyReads = () => http.mock.calls.filter(c => String(c[0]).includes('/history'));
  expect(historyReads().length).toBeGreaterThan(0);
  const fallback = http.getMockImplementation()!;
  http.mockImplementation((url: unknown, init?: RequestInit) => String(url).includes('/history')
    ? Promise.resolve(json({ messages: rows.map(r => ({ id: r.id, role: r.kind === 'USER_MESSAGE' ? 'user' : 'assistant', content: r.content, ...r.attributes })),
      contextFloorSummary: 'Fresh companion summary' })) : fallback(url, init));
  saveOutcome(); await waitForRead(el);
  expect(retained().payload.task).toBeNull();
  action(el, 'sv3-draft-review-confirm')?.click(); await settle(el);
  const before = historyReads().length;
  await send(el, 'follow up');
  expect(dispatches()).toHaveLength(2);
  expect(historyReads().length).toBeGreaterThan(before);
  expect(body(1).context).toEqual(expect.arrayContaining([
    expect.objectContaining({ role: 'system', content: expect.stringContaining('Fresh companion summary') }),
    { role: 'assistant', content: 'Saved answer' },
  ]));
});

it('a stale admission refusal cannot overwrite the replacement attempt after explicit discard', async () => {
  const fallback = http.getMockImplementation()!;
  let release!: (r: Response) => void;
  http.mockImplementation((url: unknown, init?: RequestInit) => {
    if (String(url).includes('/dispatch') && !release) return new Promise<Response>(r => { release = r; });
    return fallback(url, init);
  });
  const el = await mount(); await send(el, 'A waiting for admission');
  restoreActiveConversation('other'); await settle(el);
  action(el, 'sv3-residency-discard')!.click(); await settle(el);
  await send(el, 'B replacement');
  expect(dispatches()).toHaveLength(2);
  expect(retained().payload.task).toMatchObject({ raw: 'B replacement', dispatchTurnId: body(1).turnId });
  release(new Response(JSON.stringify({ errorCode: 'CHAT_UNAVAILABLE', error: 'Chat is paused.',
    reason: 'GPU_BUSY', forceAllowed: true }), { status: 503 })); await settle(el);
  expect(retained().payload.task).toMatchObject({ raw: 'B replacement', dispatchTurnId: body(1).turnId, delivery: 'unknown' });
  expect(composer(el).slotKind).toBe('stop');
  expect(action(el, 'sv3-residency-force')).toBeNull();
});
