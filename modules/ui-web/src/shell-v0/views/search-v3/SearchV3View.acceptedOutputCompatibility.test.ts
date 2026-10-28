// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';
import { setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

type Region = HTMLElement & { updateComplete: Promise<unknown> };
const accepted = '{"city":"Paris"}';
const schema = '{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}';
let chunks: string[];
let terminal: { finalResponse?: unknown };
let records: ThreadEvent[];
let streamError: string;
let meta: Record<string, string> | null;
let transportFailure: '' | 'connection' | 'eof' | 'timeout';
let branched: boolean;
let requests: Record<string, unknown>[];

async function settle(view: Region): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  transportFailure = ''; branched = false; chunks = [accepted]; terminal = {}; requests = []; records = []; streamError = ''; meta = null;
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
    if (!String(url).includes('/api/chat/dispatch')) {
      const href = String(url);
      if (href.includes('/branch?')) { branched = true; return new Response(JSON.stringify({ sessionId: 'accepted-branch' })); }
      const body = href.includes('/api/thread/') ? { conversationId: 'saved', events: records, lifecycles: [] }
        : href.endsWith('/api/chat/conversations') ? { sessions: records.length ? [{ sessionId: branched ? 'accepted-branch' : 'saved', createdAtMs: 1, lastActiveAtMs: 2, messageCount: records.length }] : [] }
        : { sessionId: branched ? 'accepted-branch' : 'saved', shapeId: 'core.extract', messages: records.filter(r => ['USER_MESSAGE', 'ASSISTANT_MESSAGE'].includes(r.kind)).map(r => ({ id: r.id, role: r.kind === 'USER_MESSAGE' ? 'user' : 'assistant', content: r.content, shapeId: r.attributes.shapeId })) };
      return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
    }
    requests.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
    if (transportFailure) {
      let reads = 0;
      return new Response(new ReadableStream<Uint8Array>({ pull(controller) {
        if (!reads++) controller.enqueue(new TextEncoder().encode(chunks.map(text => `event: chunk\ndata: ${JSON.stringify({ text })}\n\n`).join('')));
        else if (transportFailure === 'eof') controller.close();
        else controller.error(new Error(transportFailure === 'timeout' ? 'Stream timed out' : 'Network connection lost'));
      } }), { headers: { 'Content-Type': 'text/event-stream' } });
    }
    const wire = (meta ? `event: rag.meta\ndata: ${JSON.stringify(meta)}\n\n` : '') + chunks.map(text => `event: chunk\ndata: ${JSON.stringify({ text })}\n\n`).join('')
      + (streamError ? `event: error\ndata: ${JSON.stringify({ error: streamError, errorCode: 'NO_CONTENT' })}\n\n` : `event: done\ndata: ${JSON.stringify(terminal)}\n\n`);
    return new Response(wire, { headers: { 'Content-Type': 'text/event-stream' } });
  }));
});

afterEach(async () => {
  document.body.replaceChildren();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals();
});

async function sendStructured(): Promise<Region> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view);
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer') as Region;
  await composer.updateComplete;
  composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-tier"]')!.click();
  await composer.updateComplete;
  composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-tier="structured"]')!.click();
  await view.updateComplete; await composer.updateComplete;
  const box = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-schema"]')!;
  box.value = schema; box.dispatchEvent(new Event('input'));
  const input = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-input"]')!;
  input.value = 'extract city'; input.dispatchEvent(new Event('input'));
  await view.updateComplete; await composer.updateComplete;
  composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')!.click();
  await settle(view);
  expect(requests).toHaveLength(1);
  expect(requests[0]).toMatchObject({ shapeId: 'core.extract', prompt: 'extract city', schema });
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region;
  await main.updateComplete;
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn"]'), 'positive mounted Search turn witness').not.toBeNull();
  return main;
}

it('XT3 accepted terminal JSON replaces rejected streamed attempts in mounted Search', async () => {
  chunks = ['{bad}', accepted]; terminal = { finalResponse: accepted };
  const main = await sendStructured();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')?.textContent).toBe(accepted);
  expect(main.shadowRoot!.textContent).not.toContain('{bad}');
});

// XN1's old-host chunk fallback is obsolete (D-T43): only accepted output is an answer.
function loadedRows(answer: string | undefined, shapeId = 'core.extract', disposition?: string): ThreadEvent[] {
  return [{ id: 'question', occurredAt: '2026-10-28T00:00:00Z', kind: 'USER_MESSAGE', originator: 'user',
    content: 'extract city', attributes: { shapeId } },
    ...(answer === undefined ? [] : [{ id: 'answer', occurredAt: '2026-10-28T00:00:01Z', kind: 'ASSISTANT_MESSAGE' as const,
      originator: 'agent', content: answer, attributes: { shapeId, ...(disposition ? { disposition } : {}) } }])];
}
async function mountSaved(): Promise<Region> {
  document.body.replaceChildren(); await new Promise<void>(resolve => setTimeout(resolve, 0));
  setLastViewedConversation('saved');
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region; await main.updateComplete;
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn"]'), 'positive reloaded Search witness').not.toBeNull();
  return main;
}
function noAcceptedResult(main: Region): void {
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')).toBeNull();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('failed');
  expect(main.shadowRoot!.textContent).toContain('No result');
  expect(main.shadowRoot!.textContent).not.toContain('(empty response)');
  expect(main.shadowRoot!.textContent).not.toContain('{bad}');
}
for (const plane of ['live', 'reloaded'] as const) {
  it.each([
    ['missing', {}], ['explicitly empty', { finalResponse: '' }], ['non-string', { finalResponse: { city: 'Paris' } }],
  ] as const)(`D-T43 %s terminal output fails honestly ${plane}`, async (name, done) => {
    if (plane === 'live') { chunks = ['{bad}', accepted]; terminal = done; noAcceptedResult(await sendStructured()); }
    else { records = loadedRows(name === 'explicitly empty' ? '' : undefined); noAcceptedResult(await mountSaved()); }
  });
}
it.each(['{bad}', '{"city":42,"fact":"REJECTED_ROME_OLD"}'].flatMap(rejected => ['structured', 'ask'].map(mode => [rejected, mode])))('XT10 accepted output alone enters %s follow-up context in %s', async (rejected, mode) => {
  chunks = [rejected, accepted]; terminal = { finalResponse: accepted }; await sendStructured();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string, model?: boolean): Promise<void> }).runAsk('use the city', null, mode === 'structured' ? schema : undefined, mode === 'model');
  await settle(view);
  expect(requests).toHaveLength(2);
  expect(requests[1]!.shapeId).toBe(mode === 'structured' ? 'core.extract' : mode === 'model' ? 'core.free-chat' : 'core.rag-ask');
  expect(requests[1]!.context).toEqual([{ role: 'user', content: 'extract city' }, { role: 'assistant', content: accepted }]);
  expect(JSON.stringify(requests[1])).not.toContain(rejected);
});
it.each([undefined, ''])('D-T43 failed output contributes nothing to follow-up context (%s)', async output => {
  chunks = ['{bad}']; terminal = output === undefined ? {} : { finalResponse: output }; await sendStructured();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  terminal = { finalResponse: accepted };
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string): Promise<void> }).runAsk('new extraction', null, schema);
  expect(requests).toHaveLength(2);
  expect(requests[1]).not.toHaveProperty('context');
});
it.each(['connection lost', 'Extraction validation exhausted'])('XT3 interrupted/exhausted Structured output accepts no attempt: %s', async error => {
  streamError = error; chunks = ['{bad}'];
  const main = await sendStructured();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')).toBeNull();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('failed');
  expect(main.shadowRoot!.textContent).not.toContain('{bad}');
});
it('XT3 terminal-only ephemeral Structured output renders accepted JSON without a store record', async () => {
  chunks = []; terminal = { finalResponse: accepted }; const main = await sendStructured();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')?.textContent).toBe(accepted);
});
it('XT3 recorded accepted Structured output renders identically after remount', async () => {
  terminal = { finalResponse: accepted }; await sendStructured(); records = loadedRows(accepted);
  const main = await mountSaved();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')?.textContent).toBe(accepted);
});
it.each(['core.rag-ask', 'core.free-chat', 'core.summarize'])('XT3 ordinary %s retains streamed partial text on an empty terminal', async shapeId => {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view); chunks = ['Ordinary streamed prose']; terminal = { finalResponse: '' };
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string, model?: boolean, shape?: 'core.summarize'): Promise<void> })
    .runAsk('ordinary', null, undefined, shapeId === 'core.free-chat', shapeId === 'core.summarize' ? 'core.summarize' : undefined);
  await settle(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region; await main.updateComplete;
  const markdown = main.shadowRoot!.querySelector('jf-markdown-block') as HTMLElement & { text: string };
  expect(markdown?.text).toBe('Ordinary streamed prose');
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('complete');
});

it.each(['connection', 'eof', 'timeout'] as const)('XT7 Structured %s discards partial attempts through the real stream reader', async failure => {
  transportFailure = failure; chunks = ['{bad}'];
  const main = await sendStructured();
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')).toBeNull();
  expect(main.shadowRoot!.textContent).not.toContain('{bad}');
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe('failed');
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-note"]')?.textContent).toMatch(/lost|interrupt|timed out|completion/i);
});
it.each([false, true])('XN1 ordinary partial answer survives connection loss (model=%s)', async model => {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view); chunks = ['Ordinary partial answer']; transportFailure = 'connection';
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string, model?: boolean): Promise<void> }).runAsk('ordinary', null, undefined, model);
  await settle(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region; await main.updateComplete;
  expect((main.shadowRoot!.querySelector('jf-markdown-block') as HTMLElement & { text: string }).text).toBe('Ordinary partial answer');
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-note"]')?.textContent).toContain('Network connection lost');
});
it.each(['{bad}', '{"city":42}'])('XN1 branch inherits only the accepted output after %s', async rejected => {
  chunks = [rejected, accepted]; terminal = { finalResponse: accepted }; await sendStructured();
  records = loadedRows(accepted); await mountSaved();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  const branch = view as unknown as { branchInto(id: string, message: string, resend: null, tier: string): Promise<boolean>; runAsk(text: string, scope: null, schema?: string): Promise<void> };
  expect(await branch.branchInto('saved', 'answer', null, 'structured')).toBe(true);
  await branch.runAsk('use inherited city', null, schema); await settle(view);
  expect(branched, 'the real branch endpoint was used').toBe(true);
  expect(requests.at(-1)).toMatchObject({ conversationId: 'accepted-branch', context: [{ role: 'user', content: 'extract city' }, { role: 'assistant', content: accepted }] });
  expect(JSON.stringify(requests.at(-1))).not.toContain(rejected);
});

it('XN1 model follow-up keeps the persistent conversation without injecting rejected client context', async () => {
  chunks = ['{bad}', accepted]; terminal = { finalResponse: accepted }; await sendStructured();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string, model?: boolean): Promise<void> }).runAsk('use the city', null, undefined, true);
  expect(requests).toHaveLength(2);
  expect(requests[1]).toMatchObject({ shapeId: 'core.free-chat', conversationId: requests[0]!.conversationId });
  expect(requests[1]).not.toHaveProperty('context');
  expect(JSON.stringify(requests[1])).not.toContain('{bad}');
});
it.each(['recorded', 'ephemeral'])('XT3 an existing conversation keeps prior prose and terminal-only %s JSON', async storage => {
  records = loadedRows('Prior prose', 'core.rag-ask'); await mountSaved();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  if (storage === 'recorded') records = [...records, ...loadedRows(accepted).map(r => ({ ...r, id: r.id + '-extract', occurredAt: r.occurredAt.replace('00:00:', '00:01:') }))];
  chunks = []; terminal = { finalResponse: accepted };
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string): Promise<void> }).runAsk('extract city', null, schema); await settle(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region; await main.updateComplete;
  expect(main.shadowRoot!.querySelectorAll('[data-testid="sv3-turn"]')).toHaveLength(2);
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')?.textContent).toBe(accepted);
  expect((main.shadowRoot!.querySelector('jf-markdown-block') as HTMLElement & { text: string }).text).toBe('Prior prose');
});
it('XT3 a terminal-only answer counts as server content before enumeration catches up', async () => {
  chunks = []; terminal = { finalResponse: accepted }; await sendStructured();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  const { loadConversations } = await import('../../state/conversationListStore.js');
  await loadConversations(); await settle(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region; await main.updateComplete;
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-json"]')?.textContent).toBe(accepted);
});
it.each([false, true])('XT3 mounted Search follows a new result unless the reader scrolled back (reader=%s)', async reader => {
  terminal = { finalResponse: accepted }; const main = await sendStructured();
  const scroller = main.shadowRoot!.querySelector<HTMLElement>('[data-testid="sv3-main-scroller"]')!;
  expect(scroller).not.toBeNull();
  let height = 2000;
  Object.defineProperty(scroller, 'scrollHeight', { configurable: true, get: () => height });
  Object.defineProperty(scroller, 'clientHeight', { configurable: true, value: 500 });
  Object.defineProperty(scroller, 'scrollTop', { configurable: true, writable: true, value: 0 });
  (main as Region & { requestUpdate(): void }).requestUpdate(); await main.updateComplete;
  expect(scroller.scrollTop).toBe(2000);
  if (reader) { scroller.scrollTop = 100; scroller.dispatchEvent(new Event('scroll')); }
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  await (view as unknown as { runAsk(text: string, scope: null, schema?: string): Promise<void> }).runAsk('another extraction', null, schema);
  height = 3000; (main as Region & { requestUpdate(): void }).requestUpdate(); await main.updateComplete;
  expect(main.shadowRoot!.querySelectorAll('[data-testid="sv3-turn-json"]')).toHaveLength(2);
  expect(scroller.scrollTop).toBe(reader ? 100 : 3000);
});
it('XT3 a Structured dispatch with no chunks and no terminal output shows a failure', async () => {
  chunks = []; terminal = {};
  noAcceptedResult(await sendStructured());
});
it.each([undefined, ''])('D-T43 failed saved output contributes nothing after branching (%s)', async output => {
  records = loadedRows(output); await mountSaved();
  const view = document.querySelector('jf-sv3-window') as SearchV3View;
  const branch = view as unknown as { branchInto(id: string, message: string, resend: null, tier: string): Promise<boolean>; runAsk(text: string, scope: null, schema?: string): Promise<void> };
  expect(await branch.branchInto('saved', 'question', null, 'structured')).toBe(true);
  terminal = { finalResponse: accepted }; await branch.runAsk('new extraction', null, schema);
  expect(branched).toBe(true);
  expect(requests).toHaveLength(1);
  expect(requests[0]).toMatchObject({ conversationId: 'accepted-branch', shapeId: 'core.extract' });
  expect(requests[0]).not.toHaveProperty('context');
});
