// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** SA15/S2 ports use the mounted production window and real clients, with only HTTP replaced. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3AskDomain } from './sv3-ask-domain.js';
import type { Sv3ContextDomain } from './sv3-context-domain.js';
import { __feedContactForTest, getAiState, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest, loadConversations } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { setLastViewedConversation, readLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

type Region = HTMLElement & { updateComplete: Promise<unknown> };
type View = Omit<SearchV3View, 'askDomain' | 'contextDomain'> & { askDomain: Sv3AskDomain; contextDomain: Sv3ContextDomain };
let records: ThreadEvent[];
let failed: boolean;
let gone: boolean;
let requests: Record<string, unknown>[];
let frames: Array<[string, unknown]>;
let stopStatus: number;
let stopCalls: number;
let stopBodies: unknown[];
let finish: (() => void) | null;
let threadWait: Promise<void> | null;
let historyWait: Promise<void> | null;
let release: (() => void)[];
let lock: 'locked' | 'unlocked';
const schema = '{"type":"object"}';
const fallback = { retrieval_mode: 'BM25', retrieval_mode_reason: 'SEMANTIC_BUDGET_EXCEEDED', retrieval_mode_cause: 'loading' };
const output = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)';
const row = (id: string, kind: ThreadEvent['kind'], content: string, shapeId: string, extra = {}): ThreadEvent => ({
  id, kind, content, occurredAt: `2026-10-28T00:00:0${id === 'q' ? 0 : 1}Z`, originator: kind === 'USER_MESSAGE' ? 'user' : 'agent', attributes: { shapeId, ...extra },
});
async function settle(view: Region): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
  for (const child of view.shadowRoot!.querySelectorAll<Region>('jf-sv3-main, jf-sv3-composer')) await child.updateComplete;
}
const main = (view: Region): Region => view.shadowRoot!.querySelector('jf-sv3-main')! as Region;
async function mount(saved = false): Promise<View> {
  if (saved) setLastViewedConversation('saved');
  const view = document.createElement('jf-sv3-window') as unknown as View;
  document.body.append(view); await settle(view);
  expect(view.shadowRoot!.querySelector('jf-sv3-composer'), 'positive production Search witness').not.toBeNull();
  return view;
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); __resetAgentSessionStore();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot }); __feedContactForTest();
  records = []; requests = []; frames = []; failed = false; gone = false; stopStatus = 200; stopCalls = 0; stopBodies = []; finish = null;
  threadWait = null; historyWait = null; release = []; lock = 'unlocked';
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
    const href = String(url);
    if (href.endsWith('/stop')) { stopCalls++; stopBodies.push({ url: href, body: JSON.parse(String(init?.body)) }); if (stopStatus === 200) finish?.(); return new Response('{}', { status: stopStatus }); }
    if (href.endsWith('/api/chat/dispatch')) {
      requests.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      const wire = frames.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('');
      if (finish !== null) return new Response(new ReadableStream({ start(c) {
        c.enqueue(new TextEncoder().encode(wire)); finish = () => { finish = null; c.enqueue(new TextEncoder().encode('event: error\ndata: {"errorCode":"CANCELLED","error":"Stopped"}\n\n')); c.close(); };
      } }), { headers: { 'Content-Type': 'text/event-stream' } });
      return new Response(wire, { headers: { 'Content-Type': 'text/event-stream' } });
    }
    if (href.includes('/api/thread/')) { const snapshot = [...records]; await threadWait;
      return new Response(JSON.stringify({ conversationId: 'saved', events: snapshot, lifecycles: [] }), { status: failed ? 500 : 200 }); }
    if (href.includes('/history?existsOnly=true')) return new Response(JSON.stringify({ exists: !gone }));
    if (href.includes('/history')) { await historyWait; return new Response(JSON.stringify({ sessionId: 'saved', messages: records.filter(r => ['USER_MESSAGE', 'ASSISTANT_MESSAGE'].includes(r.kind)).map(r => ({ id: r.id, role: r.kind === 'USER_MESSAGE' ? 'user' : 'assistant', content: r.content, shapeId: r.attributes.shapeId })) }), { status: lock === 'locked' ? 423 : 200 }); }
    if (href.endsWith('/api/chat/conversations')) return new Response(JSON.stringify({ sessions: gone || !records.length ? [] : [{ sessionId: 'saved', createdAtMs: 1, lastActiveAtMs: 2, messageCount: records.length, firstUserMessage: records[0]!.content }] }));
    return new Response('{}');
  }));
});
afterEach(async () => {
  for (const resolve of release) resolve(); finish?.(); finish = null;
  document.body.replaceChildren(); await new Promise<void>(resolve => setTimeout(resolve, 0));
  __resetAgentSessionStore(); resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});
it.each(['done', 'error'])('S2 Ask fallback wording survives a live %s terminal', async terminal => {
  frames = [['rag.meta', fallback], ...(terminal === 'done' ? [['chunk', { text: 'The keyword answer.' }] as [string, unknown]] : []), [terminal, terminal === 'done' ? {} : { error: 'No matching documents found in the index', errorCode: 'NO_CONTENT' }]];
  const view = await mount(); await view.askDomain.runAsk('What do the documents say?', null); await settle(view);
  const root = main(view).shadowRoot!;
  expect(root.textContent).toContain('semantic search did not answer in time');
  expect(root.textContent).toContain('search models still loading');
  if (terminal === 'done') expect((root.querySelector('jf-markdown-block') as HTMLElement & { text: string })?.text).toBe('The keyword answer.');
  else expect(root.querySelector('[data-testid="sv3-no-evidence"]')?.textContent).toContain('Nothing in your files matched');
});
it('D-T44 live document-too-small fallback explains its retrieval mode', async () => {
  frames = [['rag.meta', { retrieval_mode: 'FULLTEXT_FALLBACK' }], ['chunk', { text: 'Ada built it.' }], ['done', {}]];
  const view = await mount(); await view.askDomain.runAsk('Who built it?', null); await settle(view);
  expect(main(view).shadowRoot!.textContent).toContain('document too small for chunking');
  expect((main(view).shadowRoot!.querySelector('jf-markdown-block') as HTMLElement & { text: string }).text).toBe('Ada built it.');
});
it.each(['complete', 'early stop', 'partial stop', 'model', 'document fallback', 'no evidence'])('S2 terminal reload renders %s with its own wording and content', async outcome => {
  const shape = outcome === 'model' ? 'core.free-chat' : 'core.rag-ask';
  const answer = outcome === 'early stop' ? '' : outcome === 'partial stop' ? 'It ships on' : 'Ada built it.';
  records = [row('q', 'USER_MESSAGE', 'Who built it?', shape), row('a', outcome === 'no evidence' ? 'ERROR' : 'ASSISTANT_MESSAGE', outcome === 'no evidence' ? 'No matching documents found in the index' : answer, shape,
    { ...(outcome.includes('stop') ? { disposition: 'CANCELLED', standaloneQuestion: 'Who owns Project Cobalt?' } : {}),
      ...(outcome === 'no evidence' ? { errorCode: 'NO_CONTENT', retrievalScoped: false } : {}),
      retrievalMode: outcome === 'document fallback' ? 'FULLTEXT_FALLBACK' : 'BM25', ...(outcome === 'document fallback' ? { sources: [{ parentDocId: 'doc', excerpt: 'Ada built it.' }] } : { retrievalModeReason: fallback.retrieval_mode_reason, retrievalModeCause: 'loading' }) })];
  const view = await mount(true); const root = main(view).shadowRoot!;
  expect(root.querySelector('[data-testid="sv3-turn"]')).not.toBeNull();
  expect(root.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe(outcome.includes('stop') ? 'halted' : outcome === 'no evidence' ? 'no-evidence' : 'complete');
  expect(root.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-kind')).toBe(outcome === 'model' ? 'model' : 'ask');
  if (outcome.includes('stop')) { expect(root.textContent).toMatch(/stopped by you/i); expect(root.textContent).toContain('Who owns Project Cobalt?'); }
  if (outcome === 'early stop' || outcome === 'no evidence') expect(root.querySelector('jf-markdown-block')).toBeNull();
  else expect((root.querySelector('jf-markdown-block') as HTMLElement & { text: string })?.text).toBe(answer);
  if (outcome === 'document fallback') expect(root.textContent).toContain('document too small for chunking');
  else if (outcome !== 'model') { expect(root.textContent).toContain('semantic search did not answer in time'); expect(root.textContent).toContain('search models still loading'); }
});
it('S2 reloaded bulk-reindex refusal remains expanded, self-contained and occurs once', async () => {
  records = [row('q', 'USER_MESSAGE', 'Rebuild', 'core.agent-run'), row('a', 'TOOL_ACTIVITY', '', 'core.agent-run', { callId: 'call', toolName: 'core_bulk_reindex', status: 'completed', success: false, output })];
  const view = await mount(true); const card = main(view).shadowRoot!.querySelector('jf-tool-call-card') as Region & { expanded: boolean };
  expect(card).not.toBeNull(); await card.updateComplete;
  expect(card.expanded).toBe(true); expect(card.shadowRoot!.querySelector('.tool-output')?.textContent).toBe(output);
  expect(card.shadowRoot!.textContent!.split('Code: BULK_GENERATION_REFUSED')).toHaveLength(2);
});
it('S2 failed refresh notice clears on success without replacing the transcript with an empty state', async () => {
  records = [row('q', 'USER_MESSAGE', 'Saved question', 'core.rag-ask'), row('a', 'ASSISTANT_MESSAGE', 'Saved answer', 'core.rag-ask')];
  const view = await mount(true); failed = true; await view.contextDomain.refreshRecord('saved'); await settle(view);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-record-notice"]')).not.toBeNull();
  failed = false; await view.contextDomain.refreshRecord('saved'); await settle(view);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-record-notice"]')).toBeNull();
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')).not.toBeNull();
});
it.each(['thread', 'history'])('S2 confirmed deletion cannot be resurrected by a late %s response', async channel => {
  records = [row('q', 'USER_MESSAGE', 'Private question', 'core.rag-ask'), row('a', 'ASSISTANT_MESSAGE', 'Private answer', 'core.rag-ask')];
  const view = await mount(true); expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')).not.toBeNull();
  const wait = new Promise<void>(resolve => release.push(resolve));
  if (channel === 'thread') threadWait = wait; else historyWait = wait;
  const pending = channel === 'thread' ? view.contextDomain.refreshRecord('saved') : view.contextDomain.refreshHistory('saved');
  gone = true; await loadConversations(); await settle(view);
  release[0]!(); await pending; await settle(view);
  expect(view.sessions.sessions.some(session => session.id === 'saved')).toBe(false);
  expect(readLastViewedConversation()).toBeNull();
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')).toBeNull();
  expect(main(view).shadowRoot!.textContent).not.toContain('Private answer');
});
it('S2 null or incomplete status never unlocks a locked transcript; disconnect tears down delivery', async () => {
  records = [row('q', 'USER_MESSAGE', 'Private question', 'core.rag-ask'), row('a', 'ASSISTANT_MESSAGE', 'Private answer', 'core.rag-ask')];
  const view = await mount(true);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')).not.toBeNull();
  lock = 'locked'; __feedForTest({ status: { conversationProtection: { state: 'locked' } } as StatusSnapshot }); await settle(view);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-history-locked"]')).not.toBeNull();
  __feedForTest({ status: null }); await settle(view);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-history-locked"]')).not.toBeNull();
  __feedForTest({ status: {} as StatusSnapshot }); await settle(view);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-history-locked"]')).not.toBeNull();
  view.remove(); lock = 'unlocked'; __feedForTest({ status: { conversationProtection: { state: 'unlocked' } } as StatusSnapshot }); await settle(view);
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-history-locked"]')).not.toBeNull();
});
it('S2 refused Structured Stop keeps streaming and permits a successful retry naming the same turn', async () => {
  finish = () => {}; frames = [['chunk', { text: '{bad}' }]];
  const view = await mount(); const sending = view.askDomain.runAsk('extract', null, schema); await settle(view);
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer') as Region;
  const stop = (): void => { composer.dispatchEvent(new CustomEvent('sv3-composer-stop', { bubbles: true, composed: true })); };
  stopStatus = 503; stop(); await settle(view);
  expect(stopCalls).toBe(1); expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('streaming');
  stopStatus = 200; stop(); await settle(view);
  expect(stopCalls, 'retry reaches the Engine before waiting for its terminal').toBe(2);
  await sending; await settle(view);
  expect(stopBodies[1]).toEqual(stopBodies[0]);
  expect(stopBodies[0]).toEqual({ url: expect.stringContaining(`/api/chat/conversations/${String(requests[0]!.conversationId)}/stop`), body: { turnId: requests[0]!.turnId } });
  expect(stopCalls).toBe(2); expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('halted');
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')).toBeNull();
});

it('XT7 activity cancellation coalesces repeated Structured Stop presses', async () => {
  finish = () => {}; frames = [['chunk', { text: '{bad}' }]];
  const view = await mount(); const sending = view.askDomain.runAsk('extract', null, schema); await settle(view);
  const cancel = getAiState().activity.cancel;
  expect(cancel, 'the real activity authority exposes Stop').toEqual(expect.any(Function));
  cancel!(); cancel!(); await sending; await settle(view);
  expect(stopCalls).toBe(1); expect(stopBodies[0]).toMatchObject({ body: { turnId: requests[0]!.turnId } });
  expect(main(view).shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('halted');
});
it('S2 a record-only conversation renders without companion messages and an empty record keeps Search usable', async () => {
  records = [row('q', 'USER_MESSAGE', 'Saved question', 'core.agent-run'), row('a', 'ASSISTANT_MESSAGE', 'Saved answer', 'core.agent-run')];
  const original = fetch;
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => String(url).includes('/history')
    ? new Response(JSON.stringify({ sessionId: 'saved', messages: [] })) : original(String(url), init)));
  const view = await mount(true);
  expect(main(view).shadowRoot!.querySelectorAll('[data-testid="sv3-turn"]')).toHaveLength(1);
  expect((main(view).shadowRoot!.querySelector('jf-markdown-block') as HTMLElement & { text: string }).text).toBe('Saved answer');
  records = []; view.remove(); const empty = await mount(true);
  expect(main(empty).shadowRoot!.querySelector('[data-testid="sv3-turn"]')).toBeNull();
  const composer = empty.shadowRoot!.querySelector('jf-sv3-composer') as Region; await composer.updateComplete;
  expect(composer.shadowRoot!.querySelector('[data-testid="sv3-composer-input"]')).not.toBeNull();
});
it('S2 reloaded no-evidence and its model answer retain separate outcome frames in one conversation', async () => {
  records = [row('q', 'USER_MESSAGE', 'Who built it?', 'core.rag-ask'), row('a', 'ERROR', 'No matching documents found in the index', 'core.rag-ask', { errorCode: 'NO_CONTENT', retrievalScoped: false }),
    { ...row('q2', 'USER_MESSAGE', 'Who built it?', 'core.free-chat'), occurredAt: '2026-10-28T00:00:02Z' },
    { ...row('a2', 'ASSISTANT_MESSAGE', 'Model-only answer', 'core.free-chat'), occurredAt: '2026-10-28T00:00:03Z' }];
  const view = await mount(true); const root = main(view).shadowRoot!;
  const turns = [...root.querySelectorAll('[data-testid="sv3-turn"]')];
  expect(turns.map(t => [t.getAttribute('data-kind'), t.getAttribute('data-status')])).toEqual([['ask', 'no-evidence'], ['model', 'complete']]);
  expect(turns[0]!.querySelector('[data-testid="sv3-no-evidence"]')).not.toBeNull();
  expect((turns[1]!.querySelector('jf-markdown-block') as HTMLElement & { text: string }).text).toBe('Model-only answer');
});
