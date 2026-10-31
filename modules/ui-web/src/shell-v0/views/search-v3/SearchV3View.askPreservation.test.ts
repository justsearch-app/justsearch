// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** Preservation checks through the mounted composer and production SSE reader; no catalog binding. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';

let requests: Record<string, unknown>[];
let streams: ReadableStreamDefaultController<Uint8Array>[];
const schema = '{"type":"object"}';
const composer = (view: SearchV3View): Sv3Composer => view.shadowRoot!.querySelector('jf-sv3-composer')!;
const field = (view: SearchV3View): HTMLTextAreaElement => composer(view).shadowRoot!.querySelector('[data-testid="sv3-composer-input"]')!;
async function drain(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
  await composer(view).updateComplete;
}
function emit(view: SearchV3View, name: string, detail: unknown): void {
  composer(view).dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
}
async function mount(): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view);
  await drain(view);
  emit(view, 'sv3-tier-change', { tier: 'ask' });
  await drain(view);
  return view;
}
async function send(view: SearchV3View, text: string): Promise<void> {
  field(view).value = text;
  field(view).dispatchEvent(new Event('input'));
  await drain(view);
  composer(view).shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')!.click();
  await drain(view);
}
function frame(index: number, event: string, data: unknown): void {
  streams[index]!.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
}
async function main(view: SearchV3View): Promise<ShadowRoot> {
  const region = view.shadowRoot!.querySelector('jf-sv3-main')!;
  await (region as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete;
  return region.shadowRoot!;
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  requests = []; streams = [];
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
    if (!String(url).includes('/api/chat/dispatch')) return new Response('{}');
    requests.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
    return new Response(new ReadableStream<Uint8Array>({ start(controller) { streams.push(controller); } }));
  }));
});
afterEach(async () => {
  document.body.replaceChildren();
  for (const stream of streams) { try { stream.close(); } catch { /* Already closed by the case. */ } }
  await new Promise(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it('keeps questions and streaming answers interleaved in actual transcript DOM order', async () => {
  const view = await mount();
  await send(view, 'Question A');
  frame(0, 'chunk', { text: 'Answer A' }); frame(0, 'done', {}); streams[0]!.close();
  await drain(view);
  await send(view, 'Question B');
  frame(1, 'chunk', { text: 'Answer ' }); frame(1, 'chunk', { text: 'B' });
  await drain(view);
  const root = await main(view);
  const ordered = [...root.querySelectorAll('[data-testid="sv3-turn-question"], [data-testid="sv3-turn-markdown"]')];
  expect(ordered.map(node => node.getAttribute('data-testid'))).toEqual([
    'sv3-turn-question', 'sv3-turn-markdown', 'sv3-turn-question', 'sv3-turn-markdown',
  ]);
  expect(ordered.map(node => node.getAttribute('data-testid') === 'sv3-turn-markdown'
    ? (node as HTMLElement & { text: string }).text : node.textContent)).toEqual([
    'Question A', 'Answer A', 'Question B', 'Answer B',
  ]);
});

it.each(['CANCELLED', 'INTERRUPTED'] as const)(
  'a subsequent Ask receives no rejected Structured output after %s', async terminal => {
    const view = await mount();
    emit(view, 'sv3-tier-change', { tier: 'structured' });
    emit(view, 'sv3-schema-change', { text: schema });
    await drain(view); await send(view, 'Extract A');
    expect(requests[0]).toMatchObject({ shapeId: 'core.extract', prompt: 'Extract A', schema });
    frame(0, 'chunk', { text: '{"rejected":"SECRET_PARTIAL"}' });
    frame(0, 'error', { error: terminal === 'CANCELLED' ? 'Stopped' : 'Connection interrupted', errorCode: terminal });
    streams[0]!.close(); await drain(view);
    const root = await main(view);
    expect(root.querySelector('[data-testid="sv3-turn"]')?.getAttribute('data-status')).toBe(terminal === 'CANCELLED' ? 'halted' : 'failed');
    expect(root.querySelector('[data-testid="sv3-turn-json"]')).toBeNull();
    expect(root.textContent).not.toContain('SECRET_PARTIAL');
    expect(field(view).value).toBe('Extract A');
    // An interrupted delivery requires explicit retirement before another question.
    if (terminal === 'INTERRUPTED') {
      const discard = view.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-residency-discard"]');
      expect(discard).not.toBeNull(); discard!.click(); await drain(view);
    }
    emit(view, 'sv3-tier-change', { tier: 'ask' });
    await drain(view); await send(view, 'Question B');
    expect(requests).toHaveLength(2);
    expect(requests[1]).toMatchObject({ shapeId: 'core.rag-ask', question: 'Question B', conversationId: requests[0]!.conversationId });
    expect(requests[1]).not.toHaveProperty('context');
    expect(JSON.stringify(requests[1])).not.toContain('SECRET_PARTIAL');
  },
);

it.each(['{', 'null', '[]'])(
  'rejects invalid Structured schema %s before issuance and preserves its draft', async invalid => {
    const view = await mount();
    emit(view, 'sv3-tier-change', { tier: 'structured' });
    emit(view, 'sv3-schema-change', { text: invalid });
    await drain(view); await send(view, 'Extract A');
    expect(requests).toEqual([]);
    expect(field(view).value).toBe('Extract A');
    expect(composer(view).shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-schema"]')!.value).toBe(invalid);
    expect(composer(view).shadowRoot!.textContent).toContain('Enter a JSON schema object.');
  },
);

it.each(['{', 'null', '[]'])(
  'the Ask owner rejects invalid Structured schema %s at the submit-event boundary and keeps its draft', async invalid => {
    const view = await mount();
    emit(view, 'sv3-tier-change', { tier: 'structured' });
    emit(view, 'sv3-schema-change', { text: invalid });
    await drain(view);
    field(view).value = 'Extract A';
    field(view).dispatchEvent(new Event('input'));
    await drain(view);
    // The composer validates Send independently; exercise the owner's guard via its emitted event.
    emit(view, 'sv3-composer-submit', { query: 'Extract A', tier: 'structured' });
    await drain(view);
    expect(composer(view).shadowRoot!.textContent).toContain('Enter a JSON schema object.');
    expect(requests).toEqual([]);
    expect(field(view).value).toBe('Extract A');
    expect(composer(view).shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid="sv3-composer-schema"]')!.value).toBe(invalid);
  },
);
