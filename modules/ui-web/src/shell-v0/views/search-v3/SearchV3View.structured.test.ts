// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';
import { setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

type ElementView = HTMLElement & { updateComplete: Promise<unknown> };
let fetchMock: ReturnType<typeof vi.fn>;
const schema = '{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}';
const answer = '{"text":"**literal** <b>HTML</b> [1]"}';
const q = <T extends Element = HTMLElement>(host: ElementView, id: string): T | null =>
  host.shadowRoot?.querySelector<T>(`[data-testid="${id}"]`) ?? null;
const dispatches = (): unknown[][] => fetchMock.mock.calls.filter(c => String(c[0]).includes('/api/chat/dispatch'));
async function drain(view: ElementView): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}
async function composer(view: ElementView): Promise<ElementView> {
  const result = view.shadowRoot!.querySelector('jf-sv3-composer') as ElementView;
  await result.updateComplete;
  return result;
}
async function mount(): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view);
  await drain(view);
  return view;
}
async function choose(view: ElementView, tier: string): Promise<void> {
  const c = await composer(view);
  q<HTMLButtonElement>(c, 'sv3-composer-tier')?.click();
  await c.updateComplete;
  const option = c.shadowRoot!.querySelector<HTMLButtonElement>(`[data-tier="${tier}"]`);
  expect(option, `mode ${tier} is reachable`).not.toBeNull();
  option!.click();
  await view.updateComplete;
  await c.updateComplete;
}
async function fill(view: ElementView, value = schema): Promise<ElementView> {
  const c = await composer(view);
  const box = q<HTMLTextAreaElement>(c, 'sv3-composer-schema');
  expect(box, 'editable labelled schema').not.toBeNull();
  box!.value = value;
  box!.dispatchEvent(new Event('input'));
  const input = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  input.value = 'extract the text';
  input.dispatchEvent(new Event('input'));
  await view.updateComplete;
  await c.updateComplete;
  return c;
}
beforeEach(() => {
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  fetchMock = vi.fn(async (url: unknown) => {
    if (!String(url).includes('/api/chat/dispatch')) return { ok: true, status: 200, json: async () => ({}) };
    const encoder = new TextEncoder();
    const frames = [
      ['rag.citations', { citations: [{ parentDocId: 'docs/a.md', chunkIndex: 0, excerpt: 'source' }] }],
      ['chunk', { text: '{rejected}' }],
      ['chunk', { text: answer }],
      ['done', { finalResponse: answer }],
    ].map(([event, data]) => ({ done: false, value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }));
    return { ok: true, status: 200, body: { getReader: () => ({
      read: async () => frames.shift() ?? { done: true }, releaseLock: () => {}, cancel: () => {},
    }) } };
  });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  document.body.replaceChildren();
  // Disconnect closes the window's subscriptions and stream; settle its outstanding promises.
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});
it('XT1 XT9 offers Structured by keyboard with a labelled multiline schema kept within this window', async () => {
  const view = await mount();
  const c = await composer(view);
  expect(c.getAttribute('tier')).toBe('search');
  const trigger = q(c, 'sv3-composer-tier')!;
  trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  await c.updateComplete;
  const option = c.shadowRoot!.querySelector<HTMLButtonElement>('[data-tier="structured"]');
  expect(option).not.toBeNull();
  expect(option!.getAttribute('role')).toBe('menuitemradio');
  option!.click(); await view.updateComplete; await c.updateComplete;
  await fill(view);
  const box = q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!;
  expect(box.labels?.length || box.getAttribute('aria-label')).toBeTruthy();
  box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, composed: true }));
  await drain(view);
  expect(dispatches()).toHaveLength(0);
  await choose(view, 'ask'); await choose(view, 'structured');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!.value).toBe(schema);
  const other = await mount(); await choose(other, 'structured');
  expect(q<HTMLTextAreaElement>(await composer(other), 'sv3-composer-schema')!.value).not.toBe(schema);
});
it('XT2 XT4 sends extraction schema and instruction and renders only terminal JSON literally with sources', async () => {
  const view = await mount(); await choose(view, 'structured');
  const c = await fill(view);
  expect(q(c, 'sv3-composer-structured-inputs')?.textContent).toContain('this conversation');
  q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await drain(view);
  expect(dispatches()).toHaveLength(1);
  const body = JSON.parse((dispatches()[0]![1] as RequestInit).body as string);
  expect(body).toMatchObject({ shapeId: 'core.extract', prompt: 'extract the text', schema });
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(q(main, 'sv3-turn-json')?.textContent).toBe(answer);
  expect(main.shadowRoot!.querySelector('jf-markdown-block')).toBeNull();
  expect(main.shadowRoot!.textContent).not.toContain('{rejected}');
  expect(main.shadowRoot!.querySelector('[data-testid="sv3-turn-sources"]')).not.toBeNull();
});
it.each(['{bad}', '[]', 'null'])('XT5 rejects schema %s at the box without sending or clearing', async value => {
  const view = await mount(); await choose(view, 'structured');
  const c = await fill(view, value);
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
  expect(q(c, 'sv3-composer-schema-error')?.textContent).toBeTruthy();
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!.getAttribute('aria-invalid')).toBe('true');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('extract the text');
});
it('XT5 XD1 keeps undelivered extraction drafts and drops the unrecorded failed turn on reload', async () => {
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('/api/chat/dispatch')) throw new TypeError('Failed to fetch');
    return previous(url, init);
  });
  const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  (view as unknown as { search: { prepare(scope: { paths: string[] }): void } }).search.prepare({ paths: ['docs/a.md'] });
  await view.updateComplete; await c.updateComplete;
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(1);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(q(main, 'sv3-turn')?.getAttribute('data-status')).toBe('failed');
  expect(q(main, 'sv3-turn')?.textContent).toContain('Failed to fetch');
  expect(q(main, 'sv3-turn-json')).toBeNull();
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('extract the text');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!.value).toBe(schema);
  expect(q(c, 'sv3-composer-structured-inputs')?.textContent).toContain('a.md');

  // Exercise the existing pagehide save point and a fresh view, with no Engine record to reload.
  window.dispatchEvent(new Event('pagehide'));
  view.remove(); await drain(view);
  __resetConversationListForTest(); __resetDraftProvidersForTest();
  const reloaded = await mount(); const restored = await composer(reloaded);
  expect(q<HTMLTextAreaElement>(restored, 'sv3-composer-input')!.value).toBe('extract the text');
  const reloadedMain = reloaded.shadowRoot!.querySelector('jf-sv3-main') as ElementView;
  await reloadedMain.updateComplete;
  expect(q(reloadedMain, 'sv3-turn')).toBeNull();
  expect(q(reloadedMain, 'sv3-turn-json')).toBeNull();
  expect(dispatches()).toHaveLength(1);
});
it('XT6 keeps schema and instruction when chat is unavailable', async () => {
  __resetAiStateForTest();
  const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!.value).toBe(schema);
  expect(q(c, 'sv3-composer-notice')?.textContent?.trim()).toBeTruthy();
});

const savedUserId = '11111111-2222-4333-8444-555555555550';
const savedAnswerId = '11111111-2222-4333-8444-555555555551';
function savedBackend(historyOverrides: Record<string, unknown> = {}): void {
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  const messages = [
    { id: savedUserId, role: 'user', content: 'saved extraction', shapeId: 'core.extract' },
    { id: savedAnswerId, role: 'assistant', content: answer, shapeId: 'core.extract' },
  ];
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    const href = String(url);
    const reply = (body: unknown): object => ({ ok: true, status: 200, json: async () => body });
    if (href.includes('/branch?')) return reply({ sessionId: 'fork' });
    if (href.includes('/history')) return reply({ sessionId: href.includes('/saved/') ? 'saved' : 'fork',
      messages: href.includes('/saved/') ? messages : [], ...historyOverrides });
    if (href.includes('/api/thread/')) return reply({ conversationId: 'saved', events: href.endsWith('/saved')
      ? messages.map((m, i) => ({ id: m.id, occurredAt: `2026-08-19T10:00:0${i}Z`,
          kind: i === 0 ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE', originator: m.role,
          content: m.content, attributes: { shapeId: m.shapeId } })) : [] });
    if (href.endsWith('/api/chat/conversations')) return reply({ sessions: [{ sessionId: 'saved',
      createdAtMs: 1, lastActiveAtMs: 2, messageCount: 2, firstUserMessage: 'saved extraction', shapeId: 'core.extract' }] });
    return previous(url, init);
  });
  setLastViewedConversation('saved');
}

it.each(['retry', 'edit', 'branch'])('DS1 XT8 restored Structured %s preserves B and requires exact own inputs', async action => {
  savedBackend();
  const view = await mount();
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(q(main, 'sv3-turn-json')?.textContent).toBe(answer);
  expect(main.shadowRoot!.textContent).toContain('Original Structured inputs are unavailable');
  const c = await composer(view); const field = q<HTMLTextAreaElement>(c, 'sv3-composer-input')!;
  field.value = 'unrelated B'; field.dispatchEvent(new Event('input')); await drain(view);
  const turnId = (q(main, 'sv3-turn-question')!.getAttribute('data-item-id') ?? '').replace(/:q$/, '');
  main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
    detail: { action, turnId, ...(action === 'edit' ? { text: 'edited extraction' } : {}) } }));
  await drain(view);
  expect(fetchMock.mock.calls.some(c => String(c[0]).includes('/branch?'))).toBe(action === 'branch');
  expect(dispatches()).toHaveLength(0);
  if (action === 'branch') expect(c.getAttribute('tier')).toBe('structured');
  else expect(field.value).toBe('unrelated B');
});

it('XT4 retains instruction schema and files when the chosen file count is refused', async () => {
  const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  const search = (view as unknown as { search: { prepare(scope: { paths: string[] }): void } }).search;
  const paths = ['a', 'b', 'c', 'd', 'e', 'f']; search.prepare({ paths });
  await view.updateComplete; await c.updateComplete;
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  expect(dispatches()).toHaveLength(0);
  expect(c.shadowRoot!.textContent).toContain('at most 5');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-input')!.value).toBe('extract the text');
  expect(q<HTMLTextAreaElement>(c, 'sv3-composer-schema')!.value).toBe(schema);
  expect(q(c, 'sv3-composer-structured-inputs')?.textContent).toContain('6 files');
});

it('XT4 sends deduplicated chosen files as selection inputs', async () => {
  const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  (view as unknown as { search: { prepare(scope: { paths: string[] }): void } }).search.prepare({ paths: ['a', 'a', 'b'] });
  await view.updateComplete; await c.updateComplete;
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  const body = JSON.parse((dispatches()[0]![1] as RequestInit).body as string);
  expect(body.selection).toEqual({ kind: 'result-set', items: [
    { id: 'a', kind: 'search-hit' }, { id: 'b', kind: 'search-hit' },
  ] });
});

it('XT10 Structured forwards the same saved conversation context as Ask', async () => {
  savedBackend(); const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  const body = JSON.parse((dispatches()[0]![1] as RequestInit).body as string);
  expect(body.context).toEqual([{ role: 'user', content: 'saved extraction' }, { role: 'assistant', content: answer }]);
});

it('XT10 honours both the context floor and excluded messages', async () => {
  savedBackend({ contextFloor: savedAnswerId, excludedMessageIds: [savedAnswerId] });
  const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  const body = JSON.parse((dispatches()[0]![1] as RequestInit).body as string);
  expect(body.context).toBeUndefined();
});

it('XT7 Stop ends Structured as stopped without presenting partial JSON as complete', async () => {
  const previous = fetchMock.getMockImplementation()! as (url: unknown, init?: RequestInit) => Promise<unknown>;
  let stopped!: () => void;
  const stop = new Promise<void>(resolve => { stopped = resolve; });
  const encoder = new TextEncoder();
  const frame = (event: string, data: unknown): object => ({ done: false,
    value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) });
  let read = 0;
  fetchMock.mockImplementation(async (url: unknown, init?: RequestInit) => {
    if (String(url).endsWith('/stop')) {
      stopped();
      return { ok: true, status: 200, json: async () => ({ stopped: true }) };
    }
    if (!String(url).includes('/api/chat/dispatch')) return previous(url, init);
    return { ok: true, status: 200, body: { getReader: () => ({ read: async () => {
      if (read++ === 0) return frame('chunk', { text: '{"partial":' });
      if (read === 2) { await stop; return frame('error', { error: 'Stopped', errorCode: 'CANCELLED' }); }
      return { done: true };
    }, releaseLock: () => {}, cancel: () => { stopped(); } }) } };
  });
  const view = await mount(); await choose(view, 'structured'); const c = await fill(view);
  q<HTMLButtonElement>(c, 'sv3-composer-send')!.click(); await drain(view);
  const stopButton = q<HTMLButtonElement>(c, 'sv3-composer-stop');
  expect(stopButton).not.toBeNull();
  stopButton!.click(); await drain(view);
  const main = view.shadowRoot!.querySelector('jf-sv3-main') as ElementView; await main.updateComplete;
  expect(q(main, 'sv3-turn')?.getAttribute('data-status')).toBe('halted');
  expect(q(main, 'sv3-turn-json')).toBeNull();
});
