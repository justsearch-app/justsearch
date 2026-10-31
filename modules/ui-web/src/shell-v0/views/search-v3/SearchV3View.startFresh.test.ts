// @vitest-environment happy-dom
/** Stage 2: start-fresh ownership and unfinished saved conversations, through the real window. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { restoreActiveConversation, __resetConversationListForTest } from '../../state/conversationListStore.js';
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
async function mount(shape?: string, initialNavigationPending = false): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window') as SearchV3View;
  el.apiBase = 'http://127.0.0.1:9999';
  el.initialNavigationPending = initialNavigationPending;
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
function newConversation(el: SearchV3View): void {
  el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
}
const recordReads = () => http.mock.calls.filter(c => String(c[0]).includes('/api/thread/'));
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

it('New preserves the old live Ask and its draft while fresh input stays isolated', async () => {
  vi.useFakeTimers();
  const el = await mount(); await send(el, 'original question');
  const original = target();
  stream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"Before"}\n\n'));
  await settle(el); await type(el, 'unsent follow-up');
  newConversation(el); await settle(el);
  expect(field(el).value).toBe(''); expect(el.sessions.activeId).toBeNull();
  expect(composer(el).slotKind).not.toBe('stop');
  expect(el.shadowRoot!.textContent).toContain('Request in progress');
  expect(el.shadowRoot!.textContent).not.toContain('Delivery unknown');
  const reads = recordReads().length;
  await vi.advanceTimersByTimeAsync(2000); await settle(el);
  expect(recordReads()).toHaveLength(reads);
  await type(el, 'fresh B');
  stream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":" while away"}\n\nevent: rag.citations\ndata: {"citations":[{"parentDocId":"old.md","chunkIndex":0,"excerpt":"old source"}]}\n\n'));
  await settle(el);
  expect(field(el).value).toBe('fresh B'); expect(answer(el)).not.toContain('while away');
  restoreActiveConversation(original); await settle(el);
  expect(field(el).value).toBe('unsent follow-up'); expect(composer(el).slotKind).toBe('stop');
  expect(answer(el)).toContain('Before while away');
  composer(el).dispatchEvent(new CustomEvent('sv3-composer-stop', { bubbles: true, composed: true }));
  await settle(el);
  expect(http.mock.calls.filter(c => String(c[0]).endsWith('/stop'))).toHaveLength(1);
  expect(dispatches()).toHaveLength(1);
});

it.each(['ask', 'structured'] as const)('New retains %s preparation with empty input and restores it only in its old conversation', async tier => {
  const el = await mount(); restoreActiveConversation('old-conversation'); await settle(el);
  composer(el).dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier } }));
  if (tier === 'structured') composer(el).dispatchEvent(new CustomEvent('sv3-schema-change', {
    bubbles: true, composed: true, detail: { text: '{"type":"object","properties":{"kept":{"type":"string"}}}' },
  }));
  el.shadowRoot!.querySelector('jf-sv3-main')!.dispatchEvent(new CustomEvent('card-scope-file', {
    bubbles: true, composed: true, detail: { path: 'chosen.md' },
  }));
  await settle(el);
  // Set the tier after choosing files, which deliberately prepares Ask.
  composer(el).dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier } }));
  await settle(el); newConversation(el); await settle(el);
  expect(field(el).value).toBe(''); expect(composer(el).schemaDraft).toBe('');
  restoreActiveConversation('old-conversation'); await settle(el);
  expect(composer(el).tier).toBe(tier);
  expect(composer(el).scopeLabel).toContain('chosen.md');
  if (tier === 'structured') expect(composer(el).schemaDraft).toContain('kept');
  expect(dispatches()).toHaveLength(0);
});

it.each(['core.rag-ask', 'core.free-chat', 'core.summarize'] as const)('an unanswered saved %s remains unfinished and checks for the saved outcome without issuing', async shape => {
  vi.useFakeTimers();
  rows = [row('question-only', 'USER_MESSAGE', 'saved question', 'saved-dispatch', { shapeId: shape })];
  const el = await mount(); restoreActiveConversation('saved-conversation'); await settle(el);
  const turn = () => el.sessions.sessions.find(s => s.id === 'saved-conversation')!.turns[0]!;
  expect(turn().status).toBe('unfinished');
  expect(transcript(el).textContent).toContain('No saved outcome yet');
  expect(transcript(el).textContent).not.toContain('The answer failed');
  expect(composer(el).slotKind).not.toBe('stop');
  await send(el, 'follow-up before outcome');
  expect(dispatches()).toHaveLength(0);
  rows.push(row('saved-answer', 'ASSISTANT_MESSAGE', 'Outcome found later', 'saved-dispatch', { shapeId: shape }));
  await vi.advanceTimersByTimeAsync(500); await settle(el);
  expect(turn().status).toBe('complete'); expect(answer(el)).toContain('Outcome found later');
  expect(field(el).value).toBe('follow-up before outcome'); expect(dispatches()).toHaveLength(0);
});

const attachmentKey = 'justsearch.draft.attachment.v1.search-v3.main';
const rawKey = 'justsearch.draft.search-v3.composer';
const pointerKey = 'justsearch.lastViewedConversation.v1';
function drafts(): { place: string | null; text: string; preparation: unknown }[] {
  return JSON.parse(sessionStorage.getItem(attachmentKey)!).payload.drafts;
}

it.each([false, true])('New then immediate reload keeps the draft at its owner, with lifecycle flush=%s', async flush => {
  let el = await mount(); restoreActiveConversation('old-conversation'); await settle(el);
  await type(el, 'old draft');
  newConversation(el); await settle(el);
  expect(localStorage.getItem(rawKey)).toBeNull(); expect(sessionStorage.getItem(pointerKey)).toBeNull();
  expect(drafts()).toEqual([expect.objectContaining({ place: 'old-conversation', text: 'old draft' })]);
  if (flush) { window.dispatchEvent(new Event('pagehide')); await settle(el); }
  expect(drafts()).toEqual([expect.objectContaining({ place: 'old-conversation', text: 'old draft' })]);
  const snapshot = sessionStorage.getItem(attachmentKey)!;
  el.remove(); __resetDraftProvidersForTest(); __resetConversationListForTest();
  // Reload consumes the bytes left by New, without giving teardown an extra save.
  sessionStorage.setItem(attachmentKey, snapshot); localStorage.removeItem(rawKey);
  el = await mount();
  expect(field(el).value).toBe(''); expect(el.sessions.activeId).toBeNull();
  restoreActiveConversation('old-conversation'); await settle(el);
  expect(field(el).value).toBe('old draft');
  expect(el.shadowRoot!.querySelector('[data-testid=sv3-draft-review-confirm]')).not.toBeNull();
  await send(el, 'old draft'); expect(dispatches()).toHaveLength(0);
});

it('New retains two owned drafts and refuses the third without eviction', async () => {
  const el = await mount();
  for (const owner of ['A', 'B']) {
    restoreActiveConversation(owner); await settle(el); await type(el, 'identical draft');
    el.shadowRoot!.querySelector('jf-sv3-main')!.dispatchEvent(new CustomEvent('card-scope-file', {
      bubbles: true, composed: true, detail: { path: `${owner}.md` },
    })); await settle(el);
    newConversation(el); await settle(el); expect(field(el).value).toBe('');
  }
  expect(drafts()).toHaveLength(2);
  expect(drafts()).toEqual(expect.arrayContaining(['A', 'B'].map(place => expect.objectContaining({ place, text: 'identical draft',
    preparation: { kind: 'ask', scope: { kind: 'files', paths: [`${place}.md`] } } }))));
  window.dispatchEvent(new Event('pagehide')); await settle(el);
  expect(drafts()).toHaveLength(2);
  expect(el.shadowRoot!.textContent).not.toContain('Recovery could not be saved safely');
  restoreActiveConversation('C'); await settle(el); await type(el, 'draft C');
  const before = sessionStorage.getItem(attachmentKey);
  newConversation(el); await settle(el);
  expect(el.sessions.activeId).toBe('C'); expect(field(el).value).toBe('draft C');
  expect(sessionStorage.getItem(attachmentKey)).toBe(before);
  expect(el.shadowRoot!.textContent).toContain('Only two draft preparations');
  expect(dispatches()).toHaveLength(0);
});

it('two immediate New presses and another on the hero never duplicate the owned draft', async () => {
  const el = await mount(); restoreActiveConversation('A'); await settle(el); await type(el, 'draft A');
  newConversation(el); newConversation(el); await settle(el);
  expect(drafts()).toEqual([expect.objectContaining({ place: 'A', text: 'draft A' })]);
  await type(el, 'hero work without an owner'); newConversation(el); await settle(el);
  expect(field(el).value).toBe('');
  expect(drafts()).toEqual([expect.objectContaining({ place: 'A', text: 'draft A' })]);
  restoreActiveConversation('A'); await settle(el); expect(field(el).value).toBe('draft A');
});

it('return before completion merges the saved dispatch into one authoritative live turn', async () => {
  const el = await mount(); await send(el, 'original question'); const original = target();
  stream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"Live part"}\n\n'));
  await settle(el); newConversation(el); await settle(el);
  rows.push(row('q', 'USER_MESSAGE', 'original question', body().turnId as string),
    row('a', 'ASSISTANT_MESSAGE', 'saved snapshot that must not replace the stream', body().turnId as string));
  restoreActiveConversation(original); await settle(el);
  const turns = () => el.sessions.sessions.find(s => s.id === original)!.turns;
  expect(turns()).toHaveLength(1); expect(turns()[0]?.status).toBe('streaming');
  expect(answer(el)).toContain('Live part'); expect(answer(el)).not.toContain('saved snapshot');
  stream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":" and end"}\n\n'));
  rows[1] = row('a', 'ASSISTANT_MESSAGE', 'Live part and end', body().turnId as string);
  stream.enqueue(new TextEncoder().encode('event: done\ndata: {}\n\n')); stream.close(); await settle(el);
  expect(turns()).toHaveLength(1); expect(turns()[0]?.status).toBe('complete');
  expect(answer(el)).toContain('Live part and end'); expect(dispatches()).toHaveLength(1);
});

it('discarding retained A releases B without losing A deltas, completion or its saved answer', async () => {
  const fallback = http.getMockImplementation()!;
  const records = new Map<string, ThreadEvent[]>();
  http.mockImplementation((url, init) => {
    const path = String(url);
    if (path.includes('/api/thread/')) return Promise.resolve(json({
      conversationId: decodeURIComponent(path.split('/api/thread/')[1]!),
      events: records.get(decodeURIComponent(path.split('/api/thread/')[1]!)) ?? [], lifecycles: [],
    }));
    return fallback(url, init);
  });
  const el = await mount(); await send(el, 'question A');
  const a = target(); const aDispatch = body().turnId as string; const aStream = stream;
  aStream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"A begins"}\n\n'));
  await settle(el); newConversation(el); await settle(el);
  await send(el, 'question B'); expect(dispatches()).toHaveLength(1);
  el.shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-residency-discard]')!.click();
  await settle(el); await send(el, 'question B');
  expect(dispatches()).toHaveLength(2);
  const b = body(1).conversationId as string; const bDispatch = body(1).turnId as string; const bStream = stream;
  const retainedB = JSON.parse(sessionStorage.getItem(attachmentKey)!).payload.task;
  expect(retainedB).toMatchObject({ target: b, dispatchTurnId: bDispatch });
  restoreActiveConversation(a); await settle(el);
  expect(composer(el).slotKind).toBe('stop'); expect(answer(el)).toContain('A begins');
  aStream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":" and finishes"}\n\n'));
  await settle(el); expect(answer(el)).toContain('A begins and finishes');
  records.set(a, [row('qa', 'USER_MESSAGE', 'question A', aDispatch),
    row('aa', 'ASSISTANT_MESSAGE', 'A begins and finishes', aDispatch)]);
  const reads = recordReads().filter(c => String(c[0]).endsWith(a)).length;
  aStream.enqueue(new TextEncoder().encode('event: done\ndata: {}\n\n')); aStream.close(); await settle(el);
  expect(recordReads().filter(c => String(c[0]).endsWith(a))).toHaveLength(reads + 1);
  expect(el.sessions.sessions.find(s => s.id === a)!.turns).toEqual([
    expect.objectContaining({ status: 'complete', answer: 'A begins and finishes', recordId: 'qa',
      answerMessages: [{ id: 'aa', content: 'A begins and finishes' }], savedOutcome: true }),
  ]);
  expect(transcript(el).querySelectorAll('jf-markdown-block')).toHaveLength(1);
  expect(composer(el).slotKind).not.toBe('stop');
  expect(JSON.parse(sessionStorage.getItem(attachmentKey)!).payload.task).toEqual(retainedB);
  restoreActiveConversation(b); await settle(el); expect(composer(el).slotKind).toBe('stop');
  bStream.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"B alone"}\n\n'));
  await settle(el); expect(answer(el)).toContain('B alone'); expect(answer(el)).not.toContain('A begins');
  records.set(b, [row('qb', 'USER_MESSAGE', 'question B', bDispatch), row('ab', 'ASSISTANT_MESSAGE', 'B alone', bDispatch)]);
  bStream.enqueue(new TextEncoder().encode('event: done\ndata: {}\n\n')); bStream.close(); await settle(el);
  expect(el.sessions.sessions.find(s => s.id === b)!.turns).toEqual([
    expect.objectContaining({ status: 'complete', answer: 'B alone' }),
  ]);
  expect(dispatches().map((_, i) => body(i).question)).toEqual(['question A', 'question B']);
  expect(JSON.parse(sessionStorage.getItem(attachmentKey)!).payload.task).toBeNull();
});

it('unfinished B checks its own saved outcome while retained A has a separate checking budget', async () => {
  vi.useFakeTimers();
  let el = await mount(); await send(el, 'retained A'); const a = target();
  const fallback = http.getMockImplementation()!;
  http.mockImplementation((url, init) => String(url).endsWith(`/api/thread/${a}`)
    ? Promise.resolve(json({ conversationId: a, events: [], lifecycles: [] })) : fallback(url, init));
  el.remove(); __resetDraftProvidersForTest(); __resetConversationListForTest();
  el = await mount(); newConversation(el); await settle(el);
  await vi.advanceTimersByTimeAsync(20000); await settle(el);
  rows = [row('qb', 'USER_MESSAGE', 'unfinished B', 'dispatch-b')];
  restoreActiveConversation('conversation-b'); await settle(el);
  const readsFor = (id: string) => recordReads().filter(c => String(c[0]).endsWith(id)).length;
  const bReads = readsFor('conversation-b');
  await vi.advanceTimersByTimeAsync(500); await settle(el);
  expect(readsFor('conversation-b')).toBeGreaterThan(bReads);
  expect(transcript(el).textContent).toContain('Checking the saved conversation');
  await vi.advanceTimersByTimeAsync(30000); await settle(el);
  expect(transcript(el).textContent).not.toContain('Checking the saved conversation');
  const check = transcript(el).querySelector<HTMLElement>('[data-testid=sv3-record-check]');
  expect(check).not.toBeNull();
  // Rearming A must not claim B's indicator or hide B's own Check again.
  el.shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-residency-check]')!.click(); await settle(el);
  expect(transcript(el).textContent).not.toContain('Checking the saved conversation');
  expect(transcript(el).querySelector('[data-testid=sv3-record-check]')).not.toBeNull();
  const aReads = readsFor(a); const bExhausted = readsFor('conversation-b');
  check!.click(); await settle(el);
  expect(readsFor('conversation-b')).toBeGreaterThan(bExhausted);
  expect(transcript(el).textContent).toContain('Checking the saved conversation');
  await vi.advanceTimersByTimeAsync(500); await settle(el);
  expect(readsFor(a)).toBeGreaterThan(aReads);
  rows.push(row('ab', 'ASSISTANT_MESSAGE', 'B saved later', 'dispatch-b'));
  await vi.advanceTimersByTimeAsync(500); await settle(el);
  expect(answer(el)).toContain('B saved later'); expect(dispatches()).toHaveLength(1);
  expect(JSON.parse(sessionStorage.getItem(attachmentKey)!).payload.task.target).toBe(a);
});

it('unfinished saved outcome checking exhausts its budget truthfully and Check again rearms it', async () => {
  vi.useFakeTimers(); rows = [row('q', 'USER_MESSAGE', 'pending saved question', 'pending')];
  const el = await mount(); restoreActiveConversation('pending-conversation'); await settle(el);
  await vi.advanceTimersByTimeAsync(31000); await settle(el);
  const exhausted = recordReads().length;
  expect(transcript(el).textContent).toContain('No saved outcome yet');
  expect(transcript(el).textContent).not.toContain('The answer failed');
  expect(transcript(el).querySelector('[data-testid=sv3-record-check]')).not.toBeNull();
  await vi.advanceTimersByTimeAsync(5000); await settle(el); expect(recordReads()).toHaveLength(exhausted);
  rows.push(row('a', 'ASSISTANT_MESSAGE', 'saved after the budget', 'pending'));
  transcript(el).querySelector<HTMLElement>('[data-testid=sv3-record-check]')!.click(); await settle(el);
  expect(answer(el)).toContain('saved after the budget'); expect(dispatches()).toHaveLength(0);
  expect(composer(el).slotKind).not.toBe('stop');
});

it('New keeps Model preparation at its owner and returns with Model selected', async () => {
  const el = await mount('core.free-chat'); restoreActiveConversation('model-conversation'); await settle(el);
  await type(el, 'model draft'); newConversation(el); await settle(el);
  expect(field(el).value).toBe('');
  const key = 'justsearch.draft.attachment.v1.search-v3.core.free-chat';
  expect(JSON.parse(sessionStorage.getItem(key)!).payload.drafts)
    .toEqual([expect.objectContaining({ place: 'model-conversation', text: 'model draft', preparation: { kind: 'model' } })]);
  restoreActiveConversation('model-conversation'); await settle(el);
  expect(field(el).value).toBe('model draft'); expect(composer(el).tier).toBe('ask');
  expect(dispatches()).toHaveLength(0);
  await send(el, 'model draft');
  expect(body().shapeId).toBe('core.free-chat'); expect(body()).not.toHaveProperty('docIds');
});

it('New preserves a schema-only draft without consuming an empty hero recovery slot', async () => {
  const el = await mount(); restoreActiveConversation('schema-conversation'); await settle(el);
  composer(el).dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'structured' } }));
  composer(el).dispatchEvent(new CustomEvent('sv3-schema-change', { bubbles: true, composed: true, detail: { text: '{"type":"object","title":"Only schema"}' } }));
  await settle(el); newConversation(el); await settle(el);
  expect(drafts()).toEqual([expect.objectContaining({ place: 'schema-conversation', text: '',
    preparation: { kind: 'structured', schema: '{"type":"object","title":"Only schema"}', scope: { kind: 'typed' } } })]);
  expect(composer(el).schemaDraft).toBe('');
  restoreActiveConversation('schema-conversation'); await settle(el);
  expect(composer(el).schemaDraft).toContain('Only schema'); expect(dispatches()).toHaveLength(0);
});

it('unfinished snapshots reject Retry, branch and context writes while preserving authored input', async () => {
  rows = [row('00000000-0000-4000-8000-000000000001', 'USER_MESSAGE', 'earlier question', 'complete', { shapeId: 'core.free-chat' }),
    row('00000000-0000-4000-8000-000000000002', 'ASSISTANT_MESSAGE', 'earlier answer', 'complete', { shapeId: 'core.free-chat' }),
    row('00000000-0000-4000-8000-000000000003', 'USER_MESSAGE', 'pending question', 'pending', { shapeId: 'core.free-chat' })];
  const el = await mount(); restoreActiveConversation('unfinished'); await settle(el);
  const turn = el.sessions.sessions.find(s => s.id === 'unfinished')!.turns[0]!;
  await type(el, 'my later draft'); const before = http.mock.calls.length;
  for (const action of ['retry', 'edit', 'branch']) transcript(el).host.dispatchEvent(new CustomEvent('sv3-branch-action', {
    bubbles: true, composed: true, detail: { action, turnId: turn.id, text: 'replacement' },
  }));
  for (const action of ['exclude', 'floor', 'compact']) transcript(el).host.dispatchEvent(new CustomEvent('sv3-context-action', {
    bubbles: true, composed: true, detail: { action, turnId: turn.id },
  }));
  await settle(el);
  expect(http.mock.calls.slice(before).filter(c => c[1]?.method === 'POST')).toHaveLength(0);
  expect(field(el).value).toBe('my later draft'); expect(composer(el).slotKind).not.toBe('stop');
});

it.each([false, true])('New abandons a pending context send even after returning to its owner=%s', async returnToOwner => {
  const fallback = http.getMockImplementation()!;
  const releases: Array<(response: Response) => void> = [];
  http.mockImplementation((url, init) => String(url).includes('/history')
    ? new Promise<Response>(resolve => { releases.push(resolve); }) : fallback(url, init));
  const el = await mount(); restoreActiveConversation('context-origin'); await settle(el);
  await send(el, 'question awaiting context'); expect(dispatches()).toHaveLength(0);
  newConversation(el); await settle(el); await type(el, 'fresh draft');
  if (returnToOwner) { restoreActiveConversation('context-origin'); await settle(el); }
  for (const release of releases) release(json({ sessionId: 'context-origin', messages: [] }));
  await settle(el); expect(dispatches()).toHaveLength(0); expect(composer(el).slotKind).not.toBe('stop');
  expect(field(el).value).toBe(returnToOwner ? 'question awaiting context' : 'fresh draft');
});

it.each([false, true])('New latches abandonment during a queued saved-history read, with ABA return=%s', async returnToOwner => {
  const fallback = http.getMockImplementation()!;
  const releases: Array<(response: Response) => void> = [];
  const el = await mount(); restoreActiveConversation('context-origin'); await settle(el);
  http.mockImplementation((url, init) => String(url).includes('/history')
    ? new Promise<Response>(resolve => { releases.push(resolve); }) : fallback(url, init));
  transcript(el).host.dispatchEvent(new CustomEvent('sv3-context-action', {
    bubbles: true, composed: true, detail: { action: 'restore' },
  }));
  await settle(el); expect(releases).toHaveLength(1);
  await send(el, 'question awaiting queued context'); expect(dispatches()).toHaveLength(0);
  newConversation(el); await settle(el); await type(el, 'fresh draft');
  expect(composer(el).slotKind).not.toBe('stop');
  if (returnToOwner) {
    restoreActiveConversation('context-origin'); await settle(el);
    await type(el, 'newer draft at the origin');
  }
  // Returning can supersede the write's history read. Also resolve the confirmation
  // read queued behind that write so a stranded await cannot masquerade as abandonment.
  for (let i = 0; i < 4 && releases.length > 0; i++) {
    for (const release of releases.splice(0)) release(json({ sessionId: 'context-origin', messages: [] }));
    await settle(el);
  }
  expect(releases).toHaveLength(0);
  expect(dispatches()).toHaveLength(0); expect(composer(el).slotKind).not.toBe('stop');
  expect(field(el).value).toBe(returnToOwner ? 'newer draft at the origin' : 'fresh draft');
});

it.each(['query', 'chosen-files', 'schema'] as const)('New retains authored results work as a reviewable results proposal: %s', async preparation => {
  const el = await mount();
  composer(el).dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'search' } }));
  await settle(el); await type(el, 'results query');
  field(el).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true })); await settle(el);
  expect(http.mock.calls.some(c => String(c[0]).includes('/knowledge/search'))).toBe(true);
  if (preparation !== 'query') {
    el.shadowRoot!.querySelector('jf-sv3-main')!.dispatchEvent(new CustomEvent('card-scope-file', {
      bubbles: true, composed: true, detail: { path: 'results-file.md' },
    })); await settle(el); await type(el, 'authored results work');
    if (preparation === 'schema') {
      composer(el).dispatchEvent(new CustomEvent('sv3-tier-change', { bubbles: true, composed: true, detail: { tier: 'structured' } }));
      composer(el).dispatchEvent(new CustomEvent('sv3-schema-change', {
        bubbles: true, composed: true, detail: { text: '{"type":"object","title":"Results schema"}' },
      })); await settle(el);
    }
  }
  newConversation(el); await settle(el);
  expect(el.sessions.activeId).toBeNull(); expect(field(el).value).toBe('');
  expect(drafts()).toEqual([expect.objectContaining({ place: '\u0000results', text: preparation === 'query' ? 'results query' : 'authored results work' })]);
  const review = el.shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-recovery-review-copy]');
  expect(review).not.toBeNull(); review!.click(); await settle(el);
  expect(field(el).value).toBe(preparation === 'query' ? 'results query' : 'authored results work');
  expect(el.shadowRoot!.querySelector('[data-testid=sv3-draft-review-confirm]')).not.toBeNull();
  expect(dispatches()).toHaveLength(0);
});
