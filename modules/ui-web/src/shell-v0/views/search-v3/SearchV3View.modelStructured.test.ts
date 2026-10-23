// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { resetSearchState } from '../../state/searchState.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

type View = SearchV3View & { updateComplete: Promise<unknown> };
type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
const schema = '{"type":"object","properties":{"text":{"type":"string"}}}';
const question = 'Answer the same question';
let fetchMock: ReturnType<typeof vi.fn>;
let terminal: 'done' | 'empty';
const messages = ['core.rag-ask', 'core.free-chat', 'core.extract'].flatMap((shapeId, i) => [
  { id: `00000000-0000-4000-8000-00000000000${i * 2 + 1}`, role: 'user', content: question, shapeId },
  { id: `00000000-0000-4000-8000-00000000000${i * 2 + 2}`, role: 'assistant',
    content: i === 0 ? 'No matching documents found in the index' : i === 1 ? 'Model prose' : '{"text":"JSON"}',
    shapeId, ...(i === 0 ? { terminalError: true, errorCode: 'NO_CONTENT', retrievalScoped: false } : {}) },
]);
const dispatches = (): Record<string, unknown>[] => fetchMock.mock.calls
  .filter(c => String(c[0]).endsWith('/api/chat/dispatch'))
  .map(c => JSON.parse((c[1] as RequestInit).body as string) as Record<string, unknown>);
async function settle(view: Mounted): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
  for (const child of view.shadowRoot?.querySelectorAll<Mounted>('jf-sv3-main, jf-sv3-composer') ?? []) await child.updateComplete;
}
function part(view: View, tag: string): Mounted { return view.shadowRoot!.querySelector(tag)! as Mounted; }
async function mount(saved = false): Promise<View> {
  if (saved) setLastViewedConversation('mixed');
  const view = document.createElement('jf-sv3-window') as View;
  document.body.append(view); await settle(view); return view;
}
async function send(view: View, text: string, requestedSchema?: string, modelAnswer = false): Promise<void> {
  await (view as unknown as { runAsk(q: string, scope: null, schema?: string, modelAnswer?: boolean): Promise<void> })
    .runAsk(text, null, requestedSchema, modelAnswer);
  await settle(view);
}
beforeEach(() => {
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest(); __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest(); terminal = 'done';
  fetchMock = vi.fn(async (url: unknown) => {
    const href = String(url);
    const reply = (body: unknown): object => ({ ok: true, status: 200, json: async () => body });
    if (href.endsWith('/api/chat/dispatch')) return new Response(terminal === 'empty'
      ? 'event: error\ndata: {"errorCode":"NO_CONTENT","error":"No matching documents found in the index"}\n\n'
      : 'event: done\ndata: {"finalResponse":"{\\"text\\":\\"JSON\\"}"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } });
    if (href.includes('/branch?')) return reply({ sessionId: 'fork' });
    if (href.includes('/history')) return reply({ sessionId: href.includes('/mixed/') ? 'mixed' : 'fork', messages: href.includes('/mixed/') ? messages : [] });
    if (href.includes('/api/thread/')) return reply({ conversationId: 'mixed', events: href.endsWith('/mixed')
      ? messages.map((m, i) => ({ id: m.id, occurredAt: `2026-10-23T00:00:0${i}Z`,
        kind: m.role === 'user' ? 'USER_MESSAGE' : m.terminalError ? 'ERROR' : 'ASSISTANT_MESSAGE',
        content: m.content, originator: m.role, attributes: { ...m } })) : [] });
    if (href.endsWith('/api/chat/conversations')) return reply({ sessions: [{ sessionId: 'mixed', createdAtMs: 1,
      lastActiveAtMs: 2, messageCount: messages.length, firstUserMessage: question, shapeId: 'core.rag-ask' }] });
    return reply({});
  });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  document.body.replaceChildren();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
it('keeps schema third and model choice fourth, with distinct request and live turn identities', async () => {
  const view = await mount();
  await send(view, 'extract', schema);
  expect(dispatches()[0]).toMatchObject({ shapeId: 'core.extract', schema, prompt: 'extract' });
  expect(part(view, 'jf-sv3-main').shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')).not.toBeNull();
  await send(view, question, undefined, true);
  expect(dispatches()[1]).toMatchObject({ shapeId: 'core.free-chat', prompt: question, sessionId: dispatches()[0]?.conversationId });
  expect(dispatches()[1]).not.toHaveProperty('schema'); expect(dispatches()[1]).not.toHaveProperty('context');
  const kinds = [...part(view, 'jf-sv3-main').shadowRoot!.querySelectorAll('[data-testid="sv3-turn"]')].map(t => t.getAttribute('data-kind'));
  expect(kinds).toEqual(['ask', 'model']);
});
it('never classifies a live Structured NO_CONTENT as no-evidence or offers the model action', async () => {
  terminal = 'empty'; const view = await mount(); await send(view, 'extract', schema);
  const root = part(view, 'jf-sv3-main').shadowRoot!;
  expect(root.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('failed');
  expect(root.querySelector('[data-testid="sv3-answer-without-files"]')).toBeNull();
  expect(root.querySelector('[data-testid="sv3-no-evidence"]')).toBeNull();
});
it.each(['retry', 'edit'])('cold %s keeps the model turn a persistent model answer', async action => {
  const view = await mount(true); const main = part(view, 'jf-sv3-main');
  const turnId = main.shadowRoot!.querySelectorAll('[data-testid="sv3-turn-question"]')[1]!.getAttribute('data-item-id')!.replace(/:q$/, '');
  main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
    detail: { action, turnId, ...(action === 'edit' ? { text: 'edited model' } : {}) } }));
  await settle(view);
  expect(dispatches()).toHaveLength(1);
  expect(dispatches()[0]).toMatchObject({ shapeId: 'core.free-chat', sessionId: 'fork', prompt: action === 'edit' ? 'edited model' : question });
  expect(dispatches()[0]).not.toHaveProperty('schema'); expect(dispatches()[0]).not.toHaveProperty('context');
});
it.each(['retry', 'edit'])('cold %s keeps Structured, awaiting schema review without sending a model answer', async action => {
  const view = await mount(true); const main = part(view, 'jf-sv3-main');
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-answer-without-files"]')).toBeNull();
  const turnId = main.shadowRoot!.querySelectorAll('[data-testid="sv3-turn-question"]')[2]!.getAttribute('data-item-id')!.replace(/:q$/, '');
  main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
    detail: { action, turnId, ...(action === 'edit' ? { text: 'edited extraction' } : {}) } }));
  await settle(view);
  expect(dispatches()).toHaveLength(0);
  const c = part(view, 'jf-sv3-composer'); expect(c.getAttribute('tier')).toBe('structured');
  expect(c.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!.value)
    .toBe(action === 'edit' ? 'edited extraction' : question);
});
it('remembered Structured Retry reuses its schema without becoming a model answer', async () => {
  const view = await mount(true);
  const main = part(view, 'jf-sv3-main');
  const turnId = main.shadowRoot!.querySelectorAll('[data-testid="sv3-turn-question"]')[2]!.getAttribute('data-item-id')!.replace(/:q$/, '');
  (view as unknown as { structuredInputs: Map<string, unknown> }).structuredInputs = new Map([[turnId, { schema, scope: null }]]);
  main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true, detail: { action: 'retry', turnId } }));
  await settle(view);
  expect(dispatches()).toHaveLength(1);
  expect(dispatches()[0]).toMatchObject({ shapeId: 'core.extract', schema });
});
