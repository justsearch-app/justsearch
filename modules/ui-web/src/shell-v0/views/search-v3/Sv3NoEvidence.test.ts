// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import { projectSv3TurnLineage } from './sv3-branch.js';
import { loadConversations, setActiveConversation } from '../../state/conversationListStore.js';
import { projectSv3AnswerFrame } from './sv3-honesty.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
const base = 'http://127.0.0.1:9999';
const question = 'What is a lunar eclipse?';
const noMatch = 'No matching documents found in the index';
const records = (attributes: Record<string, unknown>, content = noMatch): ThreadEvent[] => [
  { id: 'user-1', kind: 'USER_MESSAGE', content: question, originator: 'user',
    occurredAt: '2026-10-19T00:00:00Z', attributes: { shapeId: 'core.rag-ask' } },
  { id: 'outcome-1', kind: 'ERROR', content, originator: 'agent',
    occurredAt: '2026-10-19T00:00:01Z', attributes: { shapeId: 'core.rag-ask', ...attributes } },
];

let fetchMock: ReturnType<typeof vi.fn>;
const openStreams = new Set<ReadableStreamDefaultController<Uint8Array>>();
beforeEach(() => {
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  __resetAiStateForTest();
  fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) }));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  for (const controller of openStreams) controller.close();
  openStreams.clear();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  document.body.replaceChildren(); resetSearchState(); __resetAiStateForTest();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
function online(docs = 42): void {
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: docs } } } as unknown as StatusSnapshot });
  __feedContactForTest();
}
async function settle(el: Mounted): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
  for (const child of el.shadowRoot?.querySelectorAll<Mounted>('jf-sv3-main, jf-sv3-composer') ?? []) await child.updateComplete;
}
async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', base); document.body.appendChild(el);
  (el as unknown as { tier: string }).tier = 'ask'; await settle(el); return el;
}
function root(el: Mounted, tag = 'jf-sv3-main'): ShadowRoot {
  const found = el.shadowRoot?.querySelector(tag)?.shadowRoot;
  if (!found) throw new Error(`Missing ${tag}`); return found;
}
function frames(events: Array<[string, unknown]>): Response {
  return new Response(events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join(''),
    { headers: { 'Content-Type': 'text/event-stream' } });
}
function dispatches(): Record<string, unknown>[] {
  return fetchMock.mock.calls.filter(call => String(call[0]).endsWith('/api/chat/dispatch'))
    .map(call => JSON.parse((call[1] as RequestInit).body as string) as Record<string, unknown>);
}
async function send(el: Mounted): Promise<void> {
  const composer = root(el, 'jf-sv3-composer');
  const input = composer.querySelector<HTMLTextAreaElement>('textarea')!;
  input.value = question; input.dispatchEvent(new Event('input')); await settle(el);
  composer.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')!.click(); await settle(el);
}
function action(el: Mounted): HTMLButtonElement | null {
  return root(el).querySelector('[data-testid="sv3-answer-without-files"]');
}

describe('WP4.2b record classification (NE1, NE3, NE4, NE8, F2)', () => {
  it('reloads only unscoped NO_CONTENT as no-evidence, including legacy records', () => {
    for (const attributes of [{ errorCode: 'NO_CONTENT', retrievalScoped: false }, { errorCode: 'NO_CONTENT' }]) {
      const [turn] = projectSv3RecordTurns(records(attributes));
      expect(turn?.status).toBe('no-evidence');
      expect(projectSv3AnswerFrame(turn!, null, false)).toBeNull();
    }
  });
  it('uses the persisted scope fact even when the message changes', () => {
    expect(projectSv3RecordTurns(records({ errorCode: 'NO_CONTENT', retrievalScoped: false }, 'New wording'))[0]?.status)
      .toBe('no-evidence');
  });
  it('preserves scoped and other errors, and the fallback facts beside no-evidence', () => {
    for (const [code, message, scope] of [
      ['NO_CONTENT', 'No content in selected files', undefined],
      ['NO_CONTENT', noMatch, true],
      ['RETRIEVAL_TIMEOUT', 'Still working on it', false],
      ['RETRIEVAL_FAILED', 'Retrieval could not run', false],
      ['FETCH_FAILED', 'RAG context retrieval failed', false],
    ] as const) {
      const [turn] = projectSv3RecordTurns(records({ errorCode: code, retrievalScoped: scope }, message));
      expect(turn?.status).toBe('failed'); expect(turn?.detail).toBe(message);
    }
    const [fallback] = projectSv3RecordTurns(records({ errorCode: 'NO_CONTENT', retrievalScoped: false,
      retrievalMode: 'BM25', retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED', retrievalModeCause: 'loading' }));
    expect(fallback?.status).toBe('no-evidence');
    expect(fallback?.evidence?.retrievalModeReason).toBe('SEMANTIC_BUDGET_EXCEEDED');
  });
  it('frames free chat from the old window as a model answer and preserves ids', () => {
    const events = records({}).map((event, index) => ({ ...event,
      kind: index === 0 ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE',
      content: index === 0 ? question : 'The Earth blocks sunlight.', attributes: { shapeId: 'core.free-chat' } })) as ThreadEvent[];
    const [turn] = projectSv3RecordTurns(events);
    expect(turn?.kind).toBe('model'); expect(turn?.recordId).toBe('user-1');
    expect(projectSv3AnswerFrame(turn!, null, false)?.verdict).toBe('Model answer');
    expect(projectSv3AnswerFrame(turn!, null, false)?.elaboration).toContain('does not search');
  });
  it('an unanswered saved model question is unfinished, never completed empty (F2)', () => {
    const [turn] = projectSv3RecordTurns([{ ...records({})[0]!, attributes: { shapeId: 'core.free-chat' } }]);
    expect(turn?.kind).toBe('model'); expect(turn?.status).toBe('failed');
    expect(turn?.detail).toMatch(/no answer.*saved|unfinished|interrupted/i);
  });
});

describe('WP4.2b live action (NE1, NE2, NE5, NE9, NE11)', () => {
  it('offers one accessible action and dispatches free chat once without client history or documents', async () => {
    online();
    fetchMock.mockImplementation(async (url: unknown) => String(url).endsWith('/api/chat/dispatch')
      ? frames(dispatches().length <= 1 ? [['rag.meta', { retrieval_mode: 'BM25' }], ['error', { errorCode: 'NO_CONTENT', error: noMatch }]]
        : [['chunk', { text: 'The Earth blocks sunlight.' }], ['done', {}]])
      : { ok: true, status: 200, json: async () => ({}) });
    const el = await mount(); await send(el);
    expect(root(el).textContent).toContain('Nothing in your files matched this question.');
    expect(root(el).textContent).not.toContain('The answer failed');
    expect(root(el).querySelector('[data-testid="sv3-turn-answer-empty"]')).toBeNull();
    const button = action(el); expect(button).not.toBeNull();
    expect(button?.textContent?.trim()).toBe('Answer without your files');
    online(0); await settle(el); button!.focus(); button!.click(); button!.click(); await settle(el);
    expect(dispatches()).toHaveLength(2);
    expect(dispatches()[1]).toMatchObject({ shapeId: 'core.free-chat', prompt: question, sessionId: dispatches()[0]?.conversationId });
    expect(dispatches()[1]).not.toHaveProperty('context'); expect(dispatches()[1]).not.toHaveProperty('docIds');
    expect(root(el).textContent).toContain('Model answer');
    expect(root(el).querySelector('[data-testid="sv3-turn-sources"]')).toBeNull();
    expect(root(el, 'jf-sv3-composer').activeElement?.tagName).toBe('TEXTAREA');
  });
  it.each(['LLM_ERROR', 'CANCELLED'])('keeps no-evidence and allows retry after model %s', async code => {
    online();
    fetchMock.mockImplementation(async (url: unknown) => String(url).endsWith('/api/chat/dispatch')
      ? frames([['error', { errorCode: dispatches().length <= 1 ? 'NO_CONTENT' : code,
        error: dispatches().length <= 1 ? noMatch : 'Model attempt ended' }]])
      : { ok: true, status: 200, json: async () => ({}) });
    const el = await mount(); await send(el); expect(action(el)).not.toBeNull();
    action(el)!.click(); await settle(el);
    expect(root(el).textContent).toContain('Nothing in your files matched this question.');
    expect(root(el).querySelector('[data-testid="sv3-model-answer-retry"]')).not.toBeNull();
  });
});


describe('WP4.2b model identity and dispatch on reload (NE6, NE7, NE11, D13)', () => {
  const errorId = '00000000-0000-4000-8000-000000000002';
  const userId = '00000000-0000-4000-8000-000000000001';
  const modelUserId = '00000000-0000-4000-8000-000000000003';
  const modelAnswerId = '00000000-0000-4000-8000-000000000004';
  const saved = (): ThreadEvent[] => [
    ...records({ errorCode: 'NO_CONTENT', retrievalScoped: false }).map((event, index) => ({
      ...event, id: index === 0 ? userId : errorId,
    })),
    { id: modelUserId, kind: 'USER_MESSAGE', content: question, originator: 'user',
      occurredAt: '2026-10-19T00:00:02Z', attributes: { shapeId: 'core.free-chat' } },
    { id: modelAnswerId, kind: 'ASSISTANT_MESSAGE', content: 'A model answer.', originator: 'agent',
      occurredAt: '2026-10-19T00:00:03Z', attributes: { shapeId: 'core.free-chat' } },
  ];
  it('keeps the stored error id so a later model turn can Retry/Edit after no-evidence', () => {
    const turns = projectSv3RecordTurns(saved());
    const lineage = projectSv3TurnLineage(turns, null, 'saved', []);
    expect(turns[0]?.assistantRecordId).toBe(errorId);
    expect(lineage[1]?.forkKey).toBe(errorId);
    expect(lineage[1]?.canEdit).toBe(true);
  });
  it('labels a streaming model answer and says it does not search', () => {
    const turn = projectSv3RecordTurns(saved())[1]!;
    const frame = projectSv3AnswerFrame({ ...turn, kind: 'model', status: 'streaming' }, null, false);
    expect(frame?.verdict).toBe('Model answer');
    expect(frame?.elaboration).toContain('does not search');
  });

  async function loaded(events: ThreadEvent[]): Promise<SearchV3View & Mounted> {
    fetchMock.mockImplementation(async (raw: unknown) => {
      const url = String(raw);
      if (url.endsWith('/branch?fromMsgId=' + errorId) || url.includes('/branch?')) return {
        ok: true, status: 200, json: async () => ({ sessionId: 'fork' }),
      };
      if (url.endsWith('/api/chat/dispatch')) return frames([['chunk', { text: 'New answer.' }], ['done', {}]]);
      if (url.includes('/api/thread/')) {
        const id = url.split('/').at(-1)!;
        return { ok: true, status: 200, json: async () => ({ conversationId: id, events: id === 'saved' ? events : [] }) };
      }
      if (url.endsWith('/history')) return { ok: true, status: 200, json: async () => ({ sessionId: url.includes('/fork/') ? 'fork' : 'saved',
        messages: events.map(event => ({ id: event.id, role: event.kind === 'USER_MESSAGE' ? 'user' : 'assistant', content: event.content })),
      }) };
      if (/\/api\/chat\/conversations(?:\?|$)/.test(url)) return { ok: true, status: 200,
        json: async () => ({ sessions: [{ sessionId: 'saved', createdAtMs: 1, lastActiveAtMs: 2,
          firstUserMessage: question, shapeId: 'core.rag-ask', messageCount: events.length },
          { sessionId: 'other', createdAtMs: 1, lastActiveAtMs: 2, firstUserMessage: 'Other conversation', shapeId: 'core.rag-ask', messageCount: 0 }] }) };
      return { ok: true, status: 200, json: async () => ({}) };
    });
    await loadConversations(); setActiveConversation('saved');
    return mount();
  }

  it.each(['retry', 'edit'] as const)('a reloaded model answer %s sends free chat without client history', async choice => {
    online(); const el = await loaded(saved());
    const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
    main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
      detail: { action: choice, turnId: modelUserId, ...(choice === 'edit' ? { text: 'Edited model question' } : {}) } }));
    await settle(el);
    expect(dispatches()).toHaveLength(1);
    expect(dispatches()[0]).toMatchObject({ shapeId: 'core.free-chat', sessionId: 'fork',
      prompt: choice === 'edit' ? 'Edited model question' : question });
    expect(dispatches()[0]).not.toHaveProperty('context');
  });
  it.each(['retry', 'edit'] as const)('DS1 restored no-evidence Ask %s refuses unknown scope before fork', async choice => {
    online(); const el = await loaded(saved().slice(0, 2));
    const composer = el.shadowRoot!.querySelector('jf-sv3-composer')!;
    const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid=sv3-composer-input]')!;
    field.value = 'unrelated B'; field.dispatchEvent(new Event('input')); await settle(el);
    const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
    main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
      detail: { action: choice, turnId: userId, ...(choice === 'edit' ? { text: 'Edited Ask' } : {}) } }));
    await settle(el);
    expect(field.value).toBe('unrelated B');
    expect(dispatches()).toHaveLength(0);
    expect(fetchMock.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(0);
    expect(el.shadowRoot!.textContent).toContain('original Ask inputs are not known');
  });
  it('offers the action only on the latest turn after a cold load', async () => {
    online(); const el = await loaded(saved());
    expect(root(el).querySelectorAll('[data-testid="sv3-no-evidence"]')).toHaveLength(1);
    expect(action(el)).toBeNull();
    expect(root(el).textContent).toContain('Model answer');
  });
  it('explains the model gate and rechecks it on activation, without dispatching an Ask', async () => {
    online(); const el = await loaded(saved().slice(0, 2));
    const button = action(el)!; expect(button).not.toBeNull(); button.focus();
    __feedForTest({ inference: { mode: 'indexing', available: false } as never });
    await settle(el);
    expect(action(el)?.disabled).toBe(true);
    expect(action(el)?.getAttribute('aria-describedby')).toBeTruthy();
    action(el)?.click(); await settle(el); expect(dispatches()).toHaveLength(0);
    expect(root(el, 'jf-sv3-composer').activeElement?.tagName).toBe('TEXTAREA');
  });
  it('a model answer settling after a conversation switch lands in the original conversation', async () => {
    online(); const el = await loaded(saved().slice(0, 2));
    let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
    const regular = fetchMock.getMockImplementation() as (...args: unknown[]) => Promise<unknown>;
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => {
      if (String(url).endsWith('/api/chat/dispatch')) return new Response(new ReadableStream<Uint8Array>({ start(c) { controller = c; openStreams.add(c); } }));
      return regular(url, init);
    });
    expect(action(el)).not.toBeNull();
    action(el)!.click(); await settle(el);
    // Select on the region so the event travels the sidebar's real composed boundary.
    el.shadowRoot!.querySelector('jf-sv3-sidebar')?.dispatchEvent(new CustomEvent('sv3-session-select', {
      detail: { id: 'other' }, bubbles: true, composed: true,
    }));
    await settle(el);
    expect((el as unknown as { sessions: { activeId: string } }).sessions.activeId).toBe('other');
    controller!.enqueue(new TextEncoder().encode('event: chunk\ndata: {"text":"Late model answer"}\n\nevent: done\ndata: {}\n\n'));
    controller!.close(); openStreams.delete(controller!); await settle(el);
    expect(dispatches()[0]?.sessionId).toBe('saved');
    const sessions = (el as unknown as { sessions: { sessions: Array<{ id: string; turns: Array<{ answer: string }> }> } }).sessions;
    expect(sessions.sessions.find(session => session.id === 'saved')?.turns.at(-1)?.answer).toBe('Late model answer');
  });
  it('a lock refusal after activation keeps the original outcome and can retry after unlock (NE11)', async () => {
    online(); const el = await loaded(saved().slice(0, 2));
    const regular = fetchMock.getMockImplementation() as (...args: unknown[]) => Promise<unknown>;
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => String(url).endsWith('/api/chat/dispatch')
      ? new Response('locked', { status: 423 }) : regular(url, init));
    expect(action(el)).not.toBeNull(); action(el)!.click(); await settle(el);
    const state = (el as unknown as { sessions: { sessions: Array<{ id: string; turns: Array<{ status: string }> }> } }).sessions;
    expect(state.sessions.find(session => session.id === 'saved')?.turns.map(turn => turn.status))
      .toEqual(['no-evidence', 'refused']);
    __feedForTest({ status: { worker: { core: { indexedDocuments: 42 } },
      conversationProtection: { state: 'unlocked' } } as unknown as StatusSnapshot });
    await settle(el);
    expect(root(el).textContent).toContain('Nothing in your files matched this question.');
    expect(root(el).querySelector('[data-testid="sv3-model-answer-retry"]')).not.toBeNull();
    expect(dispatches()).toHaveLength(1);
  });

  it('waits for a context write before allowing a model answer to consume saved history (NE6)', async () => {
    online(); const el = await loaded(saved().slice(0, 2));
    const regular = fetchMock.getMockImplementation() as (...args: unknown[]) => Promise<unknown>;
    let release: (() => void) | undefined;
    const writing = new Promise<void>(resolve => { release = resolve; });
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => {
      if (String(url).endsWith('/summary')) await writing;
      return regular(url, init);
    });
    try {
      const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
      main.dispatchEvent(new CustomEvent('sv3-context-action', { bubbles: true, composed: true,
        detail: { action: 'summary', text: 'A saved context summary' } }));
      await settle(el);
      expect(action(el)).not.toBeNull();
      expect(action(el)?.disabled).toBe(true);
      action(el)?.click(); await settle(el); expect(dispatches()).toHaveLength(0);
      release!(); await settle(el);
      expect(action(el)?.disabled).toBe(false);
      action(el)?.click(); await settle(el);
      expect(dispatches()[0]?.shapeId).toBe('core.free-chat');
    } finally { release?.(); await settle(el); }
  });

  it('keeps the existing follow-up rewrite note beside no-evidence (HN9)', async () => {
    online();
    const events = saved().slice(0, 2).map((event, index) => index === 1
      ? { ...event, attributes: { ...event.attributes, standaloneQuestion: 'What causes a lunar eclipse?' } } : event);
    const el = await loaded(events);
    expect(root(el).querySelector('[data-testid="sv3-turn-rewrite"]')).not.toBeNull();
    expect(root(el).querySelector('[data-testid="sv3-turn-rewrite"]')?.textContent)
      .toContain('What causes a lunar eclipse?');
  });

  it('the action answers its own question and preserves an unsent composer draft (NE2)', async () => {
    online(); const el = await loaded(saved().slice(0, 2));
    const field = root(el, 'jf-sv3-composer').querySelector<HTMLTextAreaElement>('textarea')!;
    field.value = 'Keep this unsent follow-up'; field.dispatchEvent(new Event('input'));
    await settle(el);
    expect(action(el)).not.toBeNull(); action(el)!.click(); await settle(el);
    expect(dispatches()[0]).toMatchObject({ shapeId: 'core.free-chat', prompt: question });
    expect(root(el, 'jf-sv3-composer').querySelector<HTMLTextAreaElement>('textarea')?.value)
      .toBe('Keep this unsent follow-up');
  });

});
