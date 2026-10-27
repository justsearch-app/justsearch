// @vitest-environment happy-dom
/** IR15: production component and request parser, controlled HTTP; no runtime acceptance. */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { restoreActiveConversation, __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { resetSearchState } from '../../state/searchState.js';
import { parkSearchV3Handoff } from '../../utils/compose.js';

const raw = '  Captured A 界  ';
const key = 'justsearch.draft.attachment.v1.search-v3.main';
let http: ReturnType<typeof vi.fn>;
let historyOpen: boolean;
let releases: Array<(status: number) => void>;
let historyReplies: number[];
const dispatches = () => http.mock.calls.filter(c => String(c[0]).includes('/dispatch'));
const stops = () => http.mock.calls.filter(c => String(c[0]).endsWith('/stop'));
const composer = (el: SearchV3View): Sv3Composer => el.shadowRoot!.querySelector('jf-sv3-composer')!;
const field = (el: SearchV3View): HTMLTextAreaElement => composer(el).shadowRoot!.querySelector('[data-testid=sv3-composer-input]')!;
const emit = (el: SearchV3View, name: string, detail: unknown) => composer(el).dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
async function settle(el: SearchV3View): Promise<void> {
  for (let i = 0; i < 10; i++) await new Promise(r => setTimeout(r, 0));
  await el.updateComplete; await composer(el).updateComplete;
}
async function type(el: SearchV3View, text: string): Promise<void> {
  field(el).value = text; field(el).dispatchEvent(new Event('input')); await composer(el).updateComplete;
}
async function mount(shape?: string): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window') as SearchV3View;
  el.apiBase = 'http://127.0.0.1:9999';
  if (shape) el.setAttribute('shape-id', shape);
  document.body.append(el); await settle(el);
  emit(el, 'sv3-tier-change', { tier: shape === 'core.extract' ? 'structured' : 'ask' });
  if (shape === 'core.extract') emit(el, 'sv3-schema-change', { text: '{"type":"object"}' });
  await settle(el); return el;
}
async function send(el: SearchV3View): Promise<void> {
  await type(el, raw);
  composer(el).shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-composer-send]')!.click();
  await settle(el);
}
function releaseHistory(status = 200): void { historyOpen = true; for (const release of releases.splice(0)) release(status); }
function lifecycle(mode: string): void {
  if (mode.startsWith('pagehide')) {
    window.dispatchEvent(new Event('pagehide'));
    if (mode.includes('return')) window.dispatchEvent(new Event('pageshow'));
  } else {
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    visibility.mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'));
    if (mode.includes('return')) { visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); }
  }
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  historyOpen = false; releases = []; historyReplies = [];
  http = vi.fn(async (url: unknown) => {
    const href = String(url);
    if (href.includes('/history')) {
      const response = (status = 200) => {
        historyReplies.push(status);
        return new Response(JSON.stringify({ sessionId: href.split('/conversations/')[1]!.split('/')[0], messages: [] }), { status });
      };
      return historyOpen ? response() : new Promise<Response>(resolve => releases.push(status => resolve(response(status))));
    }
    if (href.includes('/dispatch')) return new Response('event: chunk\ndata: {"text":"Answer"}\n\nevent: done\ndata: {}\n\n', { headers: { 'content-type': 'text/event-stream' } });
    return new Response('{}');
  });
  vi.stubGlobal('fetch', http);
  __feedForTest({ inference: { available: true } as never, status: { inference: { chatEnabledSpec: true }, worker: { core: { indexedDocuments: 42 } },
    operating: { chat: { state: 'READY' } }, readiness: { composites: { aiFeatures: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot }); __feedContactForTest();
  setLastViewedConversation('origin');
});
afterEach(() => {
  releaseHistory(); for (const child of [...document.body.children]) child.remove();
  resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

describe('IR15 initial context confirmation', () => {
  it.each(['pagehide', 'pagehide-return', 'hidden', 'hidden-return'])(
    '%s latches abandonment before a delayed initial Send can issue or clear', async mode => {
      const el = await mount(); await send(el);
      expect(releases.length).toBeGreaterThan(0); expect(dispatches()).toHaveLength(0);
      lifecycle(mode); releaseHistory(); await settle(el);
      expect(dispatches(), 'abandoned initial question must not issue').toHaveLength(0);
      expect(field(el).value).toBe(raw); expect(stops()).toHaveLength(0);
      expect(sessionStorage.getItem(key)).toContain(JSON.stringify(raw));
      expect(el.shadowRoot!.querySelector('[data-testid=sv3-residency-force]')).toBeNull();
    });

  it.each(['pagehide-return', 'hidden-return'])(
    '%s keeps both captured A and newer B through return and reload without sending', async mode => {
      const el = await mount(); await send(el); await type(el, 'newer B 界');
      field(el).focus(); field(el).setSelectionRange(2, 5);
      lifecycle(mode); releaseHistory(); await settle(el);
      expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe('newer B 界');
      expect(field(el).selectionStart).toBe(2); expect(field(el).selectionEnd).toBe(5);
      expect(composer(el).shadowRoot!.activeElement).toBe(field(el));
      const bytes = sessionStorage.getItem(key)!; expect(bytes).toContain(JSON.stringify(raw)); expect(bytes).toContain('newer B 界');
      el.remove(); const restored = await mount();
      expect(field(restored).value).toBe('newer B 界'); expect(restored.shadowRoot!.textContent).toContain(raw);
      expect(dispatches()).toHaveLength(0); expect(stops()).toHaveLength(0);
    });

  it.each(['disconnect-return', 'navigation-ABA', 'search-placement-ABA', 'New', 'handoff', 'discard'])(
    '%s abandons a pending initial Send without a late dispatch', async mode => {
      const el = await mount(); await send(el); expect(dispatches()).toHaveLength(0);
      if (mode === 'disconnect-return') { el.remove(); document.body.append(el); await settle(el); }
      if (mode === 'navigation-ABA') { restoreActiveConversation('elsewhere'); await settle(el); restoreActiveConversation('origin'); await settle(el); }
      if (mode === 'search-placement-ABA') {
        emit(el, 'sv3-tier-change', { tier: 'search' }); await settle(el); await type(el, 'search B');
        composer(el).shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-composer-send]')!.click(); await settle(el);
        restoreActiveConversation('origin'); await settle(el);
      }
      if (mode === 'New') el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
      if (mode === 'handoff') parkSearchV3Handoff({ query: 'incoming B', mode: 'ask', docIds: ['/B.txt'] });
      if (mode === 'discard') {
        const discard = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-residency-discard]'); expect(discard).not.toBeNull(); discard!.click();
      }
      await settle(el); const current = field(el).value; releaseHistory(); await settle(el);
      expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe(current); expect(stops()).toHaveLength(0);
      if (mode !== 'discard') expect(sessionStorage.getItem(key)).toContain(JSON.stringify(raw));
    });

  it.each(['unchanged', 'newer text', 'same-text assignment', 'edit-away-back', 'empty', 'IME', 'future parameters'])(
    '%s preserves initial captured A semantics when no placement is abandoned', async change => {
      const el = await mount(); await send(el); expect(dispatches()).toHaveLength(0);
      if (change === 'newer text') await type(el, 'newer B');
      if (change === 'same-text assignment') composer(el).draft = raw;
      if (change === 'edit-away-back') { await type(el, 'B'); await type(el, raw); }
      if (change === 'empty') await type(el, '');
      if (change === 'IME') field(el).dispatchEvent(new Event('compositionstart'));
      if (change === 'future parameters') { emit(el, 'sv3-effort-change', { effort: 'thorough' }); emit(el, 'sv3-tier-change', { tier: 'structured' }); }
      await settle(el); const current = field(el).value;
      field(el).focus(); field(el).setSelectionRange(1, Math.min(3, current.length));
      const start = field(el).selectionStart; const end = field(el).selectionEnd;
      releaseHistory(); await settle(el);
      expect(dispatches()).toHaveLength(1);
      const body = JSON.parse(dispatches()[0]![1].body as string);
      expect(body).toMatchObject({ shapeId: 'core.rag-ask', question: raw.trim(), conversationId: 'origin', allowLoad: true });
      expect(body.maxTokens).toBeUndefined(); expect(body.topK).toBeUndefined();
      expect(field(el).value).toBe(change === 'unchanged' || change === 'future parameters' ? '' : current);
      if (change !== 'unchanged' && change !== 'future parameters') {
        expect(field(el).selectionStart).toBe(start); expect(field(el).selectionEnd).toBe(end);
        expect(composer(el).shadowRoot!.activeElement).toBe(field(el));
      }
    });

  it.each(['core.rag-ask', 'core.extract'])(
    '%s compatibility initial context wait honors latched hide and preserves schema/raw', async shape => {
      const el = await mount(shape);
      // Compatibility mounts intentionally do not restore the main window's last-viewed pointer.
      restoreActiveConversation('origin'); await settle(el); await send(el); expect(dispatches()).toHaveLength(0);
      lifecycle('hidden-return'); releaseHistory(); await settle(el);
      expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe(raw); expect(stops()).toHaveLength(0);
      const bytes = sessionStorage.getItem(`justsearch.draft.attachment.v1.search-v3.${shape}`)!;
      expect(bytes).toContain(JSON.stringify(raw));
      if (shape === 'core.extract') expect(JSON.parse(bytes).payload.task.schema).toBe('{"type":"object"}');
    });

  it.each(['core.rag-ask', 'core.extract'])(
    '%s keeps captured scope schema and effort while newer input changes future parameters', async shape => {
      const el = await mount();
      parkSearchV3Handoff({ query: raw, forceShapeId: shape, docIds: ['/A.txt'] }); await settle(el);
      restoreActiveConversation('origin'); await settle(el);
      emit(el, 'sv3-tier-change', { tier: shape === 'core.extract' ? 'structured' : 'ask' });
      // Choosing the file again makes it the current preparation after the place change.
      emit(el, 'card-scope-file', { path: '/A.txt' });
      emit(el, 'sv3-tier-change', { tier: shape === 'core.extract' ? 'structured' : 'ask' });
      if (shape === 'core.extract') emit(el, 'sv3-schema-change', { text: '{"type":"object"}' });
      emit(el, 'sv3-effort-change', { effort: 'quick' }); await settle(el); await send(el);
      expect(dispatches()).toHaveLength(0);
      await type(el, 'newer B'); emit(el, 'card-scope-file', { path: '/B.txt' });
      emit(el, 'sv3-schema-change', { text: '{"type":"array"}' }); emit(el, 'sv3-effort-change', { effort: 'thorough' });
      field(el).focus(); field(el).setSelectionRange(2, 4); releaseHistory(); await settle(el);
      expect(dispatches()).toHaveLength(1); expect(field(el).value).toBe('newer B');
      expect(field(el).selectionStart).toBe(2); expect(field(el).selectionEnd).toBe(4);
      const body = JSON.parse(dispatches()[0]![1].body as string);
      expect(body).toMatchObject({ shapeId: shape, allowLoad: true, maxTokens: 512, enableThinking: false });
      if (shape === 'core.extract') expect(body).toMatchObject({ schema: '{"type":"object"}', prompt: raw.trim(), selection: { items: [{ id: '/A.txt' }] } });
      else expect(body).toMatchObject({ question: raw.trim(), docIds: ['/A.txt'] });
    });

  it.each(['failed response', 'still pending'])(
    '%s after hide preserves raw input and never invents dispatch or Stop', async response => {
      const el = await mount(); await send(el); lifecycle('hidden-return');
      if (response === 'failed response') {
        http.mockImplementation(async () => new Response('{}', { status: 500 }));
        releaseHistory(500);
      }
      await settle(el);
      expect(historyReplies).toEqual(response === 'failed response' ? expect.arrayContaining([500]) : []);
      expect(dispatches()).toHaveLength(0); expect(stops()).toHaveLength(0);
      expect(field(el).value).toBe(raw); expect(sessionStorage.getItem(key)).toContain(JSON.stringify(raw));
    });

  it('confirmed removal abandons an initial context wait and retains the draft without Stop', async () => {
    const original = http.getMockImplementation()! as (url: unknown) => Promise<Response>;
    http.mockImplementation(async (url: unknown, init?: RequestInit) => {
      if (init?.method === 'DELETE') return new Response('{}');
      if (String(url).includes('childrenOf=')) return new Response(JSON.stringify({ childrenOf: 'origin', locked: false,
        sessions: [{ sessionId: 'origin', createdAtMs: 1, lastActiveAtMs: 2, messageCount: 0, firstUserMessage: 'A', busy: false }] }));
      return original(url);
    });
    const el = await mount(); await send(el); expect(dispatches()).toHaveLength(0);
    el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: 'origin' }, bubbles: true, composed: true }));
    await settle(el);
    const dialog = document.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-confirm-dialog');
    expect(dialog).not.toBeNull(); await dialog!.updateComplete;
    const confirm = dialog!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-button.confirm')!;
    await confirm.updateComplete;
    const control = confirm.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-control')!;
    await control.updateComplete; control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(el); expect(http.mock.calls.some(c => c[1]?.method === 'DELETE')).toBe(true);
    releaseHistory(); await settle(el);
    expect(dispatches()).toHaveLength(0); expect(stops()).toHaveLength(0); expect(field(el).value).toBe(raw);
    expect(sessionStorage.getItem(key)).toContain(JSON.stringify(raw));
  });

  it.each(['core.rag-ask', 'core.free-chat', 'core.extract'])(
    '%s already issued Send keeps its stream on hide and sends no Stop or replacement', async shape => {
      historyOpen = true;
      let finish!: (response: Response) => void;
      const original = http.getMockImplementation()! as (url: unknown) => Promise<Response>;
      http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
        ? new Promise<Response>(resolve => { finish = resolve; }) : original(url));
      const el = await mount(shape); await send(el); expect(dispatches()).toHaveLength(1);
      const body = JSON.parse(dispatches()[0]![1].body as string);
      expect(body.shapeId).toBe(shape); expect(body.allowLoad).toBe(true);
      await type(el, 'newer B'); lifecycle('hidden-return');
      finish(new Response('event: chunk\ndata: {"text":"Answer after hide"}\n\nevent: done\ndata: {"finalResponse":"Answer after hide"}\n\n', { headers: { 'content-type': 'text/event-stream' } }));
      await settle(el);
      expect(dispatches()).toHaveLength(1); expect(stops()).toHaveLength(0); expect(field(el).value).toBe('newer B');
      const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
      const answer = shape === 'core.extract' ? main.shadowRoot!.querySelector('[data-testid=sv3-turn-json]')?.textContent
        : main.shadowRoot!.querySelector('[data-testid=sv3-turn-markdown]')?.shadowRoot?.textContent;
      expect(answer).toContain('Answer after hide');
    });
});

describe('IR15 initial Retry/Edit fork continuation', () => {
  it.each([
    ['core.free-chat', 'retry', 'unchanged'], ['core.free-chat', 'edit', 'unchanged'],
    ['core.free-chat', 'retry', 'pagehide-return'], ['core.free-chat', 'edit', 'pagehide-return'],
    ['core.free-chat', 'edit', 'hidden-return'], ['core.free-chat', 'retry', 'hidden-return'],
    ['core.free-chat', 'retry', 'navigation-ABA'], ['core.free-chat', 'edit', 'disconnect-return'],
    ['core.free-chat', 'edit', 'discard'],
  ] as const)('%s %s with %s while the fork waits cannot issue an abandoned task', async (shape, action, change) => {
    const messages = [
      { id: '11111111-2222-4333-8444-555555555551', role: 'user', content: 'saved A', shapeId: shape },
      { id: '11111111-2222-4333-8444-555555555552', role: 'assistant', content: 'saved answer', shapeId: shape },
    ];
    let releaseFork!: (response: Response) => void;
    http.mockImplementation(async (url: unknown) => {
      const href = String(url);
      if (href.includes('/branch?')) return new Promise<Response>(resolve => { releaseFork = resolve; });
      if (href.includes('/history')) return new Response(JSON.stringify({ sessionId: href.includes('/saved/') ? 'saved' : 'child', messages: href.includes('/saved/') ? messages : [] }));
      if (href.includes('/api/thread/')) return new Response(JSON.stringify({ conversationId: 'saved', events: href.endsWith('/saved') ? messages.map((m, i) => ({
        id: m.id, occurredAt: `2026-10-23T00:00:0${i}Z`, kind: m.role === 'user' ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE',
        content: m.content, originator: m.role, attributes: { shapeId: shape },
      })) : [] }));
      if (href.includes('/dispatch')) return new Response('event: chunk\ndata: {"text":"Answer"}\n\nevent: done\ndata: {}\n\n');
      return new Response('{}');
    });
    setLastViewedConversation('saved'); const el = await mount(); await type(el, 'unrelated B');
    const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
    const question = main.shadowRoot!.querySelector('[data-testid=sv3-turn-question]'); expect(question).not.toBeNull();
    const turnId = question!.getAttribute('data-item-id')!.replace(/:q$/, '');
    main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
      detail: { action, turnId, ...(action === 'edit' ? { text: raw } : {}) } })); await settle(el);
    expect(http.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(1); expect(dispatches()).toHaveLength(0);
    if (change.includes('return') && change !== 'disconnect-return') lifecycle(change);
    if (change === 'navigation-ABA') { restoreActiveConversation('elsewhere'); await settle(el); restoreActiveConversation('saved'); await settle(el); }
    if (change === 'disconnect-return') { el.remove(); document.body.append(el); await settle(el); }
    if (change === 'discard') el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-residency-discard]')!.click();
    const current = field(el).value;
    releaseFork(new Response(JSON.stringify({ sessionId: 'child' }))); await settle(el);
    expect(dispatches()).toHaveLength(change === 'unchanged' ? 1 : 0); expect(stops()).toHaveLength(0);
    if (change !== 'unchanged') expect(field(el).value).toBe(current);
    if (change === 'unchanged') {
      const body = JSON.parse(dispatches()[0]![1].body as string);
      expect(body).toMatchObject({ shapeId: shape, allowLoad: true, conversationId: 'child',
        [shape === 'core.free-chat' ? 'prompt' : 'question']: action === 'edit' ? raw.trim() : 'saved A' });
      restoreActiveConversation('saved'); await settle(el); expect(field(el).value).toBe('unrelated B');
    }
  });
});
