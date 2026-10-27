// @vitest-environment happy-dom
/** NR15/RR15: real composer and SSE parser; only HTTP is controlled. */
import { Window } from 'happy-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { restoreActiveConversation, __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';
import { setLastViewedConversation } from '../../controllers/lastViewedConversation.js';
import { parkSearchV3Handoff, setPendingSelection } from '../../utils/compose.js';

let http: ReturnType<typeof vi.fn>;
const dispatches = () => http.mock.calls.filter(c => String(c[0]).includes('/api/chat/dispatch'));
const loads = () => http.mock.calls.filter(c => String(c[0]).includes('/api/chat/load-anyway'));
const body = (i = 0) => JSON.parse(dispatches()[i]![1].body as string) as Record<string, unknown>;
function state(docs = 42, unloaded = false, enabled = true): void {
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { inference: { chatEnabledSpec: enabled }, worker: { core: { indexedDocuments: docs } },
      operating: { chat: { state: unloaded ? 'UNLOADED' : 'READY' } },
      readiness: { composites: { aiFeatures: { state: unloaded ? 'DEGRADED' : 'READY', reasonCodes: unloaded ? ['inference.unloaded_gpu_pressure'] : [] } } },
    } as unknown as StatusSnapshot });
  __feedContactForTest();
}
const refusal = (forceAllowed = true, reason = 'GPU_BUSY', retryAfterMs?: number) => new Response(JSON.stringify({
  errorCode: 'CHAT_UNAVAILABLE', error: 'Chat is paused for another application.', reason, forceAllowed, retryAfterMs,
}), { status: 503 });
const done = () => new Response('event: content\ndata: {"text":"Answer"}\n\nevent: done\ndata: {}\n\n', { headers: { 'content-type': 'text/event-stream' } });
const passage = (selectionText: string) => ({ kind: 'text-range',
  address: { coords: 'canonical', docId: '/chosen.txt', startChar: 0, endChar: selectionText.length },
  selectionText, hostEntity: { kind: 'doc', id: '/chosen.txt' } } as const);
const attachmentKey = 'justsearch.draft.attachment.v1.search-v3.main';
/** Inject a write fault at the actual instance seam (happy-dom binds own methods). */
async function withAttachmentWriteFault(fail: (value: string) => boolean, run: (hits: string[]) => Promise<void>): Promise<void> {
  const storage = sessionStorage;
  const original = storage.setItem;
  // Storage's Proxy hides method descriptors, so restore its bound callable explicitly.
  const descriptor = Object.getOwnPropertyDescriptor(storage, 'setItem')
    ?? { configurable: true, writable: true, enumerable: false, value: original };
  const hits: string[] = [];
  Object.defineProperty(storage, 'setItem', { ...descriptor, value: (key: string, value: string) => {
    if (key === attachmentKey) {
      hits.push(key);
      if (fail(value)) throw new DOMException('injected write failure', 'QuotaExceededError');
    }
    original(key, value);
  } });
  try { await run(hits); } finally { Object.defineProperty(storage, 'setItem', descriptor); }
}
async function settle(el: SearchV3View): Promise<void> {
  for (let i = 0; i < 7; i++) await new Promise(r => setTimeout(r, 0));
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
function clickAction(el: SearchV3View, id: string): void {
  const control = action(el, id); expect(control, id).not.toBeNull(); control!.click();
}
function emit(el: SearchV3View, name: string, detail: unknown): void {
  composer(el).dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
}
beforeEach(() => {
  sessionStorage.clear(); localStorage.clear(); __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  http = vi.fn(async (url: unknown) => String(url).includes('/api/chat/dispatch') ? refusal() : new Response('{}'));
  vi.stubGlobal('fetch', http); state();
});
afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});
describe('NR15 human admission and RR15 refusal recovery', () => {
  it('marks explicit human Send and removes an unaccepted optimistic answer', async () => {
    const el = await mount(); await send(el, '  original A  ');
    expect(body().allowLoad).toBe(true);
    const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
    expect(main.shadowRoot!.querySelectorAll('[data-testid=sv3-turn]')).toHaveLength(0);
    expect(el.shadowRoot!.textContent).toContain('Question not sent');
    expect(el.shadowRoot!.textContent).toContain('  original A  ');
    expect(http.mock.calls.filter(c => String(c[0]).includes('/auto-title'))).toHaveLength(0);
  });
  it('allows human Send from positively unloaded chat without granting Delegate or force', async () => {
    state(42, true); const el = await mount(); await send(el, 'unloaded question');
    expect(dispatches()).toHaveLength(1);
    expect(composer(el).delegateUnavailableReason).not.toBe('');
    expect(loads()).toHaveLength(0);
  });
  it.each([['core.free-chat', false], ['core.free-chat', true], ['core.extract', false], ['core.extract', true]] as const)('zero documents do not block human %s (unloaded=%s)', async (shape, unloaded) => {
    state(0, unloaded); const el = await mount(shape);
    if (shape === 'core.extract') {
      const schemaField = composer(el).shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid=sv3-composer-schema]')!;
      schemaField.value = '{"type":"object"}'; schemaField.dispatchEvent(new Event('input')); await settle(el);
    }
    await send(el, 'typed input');
    expect(dispatches()).toHaveLength(1); expect(body().shapeId).toBe(shape); expect(body().allowLoad).toBe(true);
    if (shape === 'core.free-chat') { expect(body().docIds).toBeUndefined(); expect(body().context).toBeUndefined(); }
  });
  it('retains A and newer B across same-tab reload, review-only', async () => {
    let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/api/chat/dispatch') ? new Promise<Response>(r => { release = r; }) : new Response('{}'));
    const el = await mount(); await send(el, 'original A'); await type(el, 'newer B'); release(refusal()); await settle(el);
    expect(field(el).value).toBe('newer B'); el.remove();
    const restored = await mount();
    expect(field(restored).value).toBe('newer B');
    expect(restored.shadowRoot!.textContent).toContain('original A');
    expect(restored.shadowRoot!.textContent).toContain('tab session');
    expect(dispatches()).toHaveLength(1); expect(action(restored, 'sv3-residency-force')).toBeNull();
  });
  it.each([200, 202])('one explicit force continues captured A exactly once after %s without a READY poll', async status => {
    const el = await mount(); await send(el, 'original A');
    let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway') ? new Promise<Response>(r => { release = r; }) : String(url).includes('/dispatch') ? done() : new Response('{}'));
    const force = action(el, 'sv3-residency-force'); expect(force).not.toBeNull(); force!.click(); force!.click(); await settle(el);
    expect(loads()).toHaveLength(1); state(42, true); release(new Response('{}', { status })); await settle(el);
    expect(dispatches()).toHaveLength(2); expect(body(1).question).toBe('original A');
    expect(loads()[0]![1].signal).toBeInstanceOf(AbortSignal);
  });
  it('unknown transport delivery never claims not sent or offers blind retry', async () => {
    http.mockImplementation(async (url: unknown) => { if (String(url).includes('/dispatch')) throw new TypeError('Failed to fetch'); return new Response('{}'); });
    const el = await mount(); await send(el, 'unknown A');
    expect(el.shadowRoot!.textContent).toContain('Delivery unknown');
    expect(el.shadowRoot!.textContent).not.toContain('Question not sent');
    expect(action(el, 'sv3-residency-retry')).toBeNull(); expect(action(el, 'sv3-residency-force')).toBeNull();
  });
  it.each([
    ['GPU_BUSY', true], ['GPU_BUSY', false], ['GAME_MODE', true], ['GAME_MODE', false],
    ['RELOAD_BACKOFF', true], ['RELOAD_BACKOFF', false], ['LOADING', false], ['HELD_MODEL', false],
  ] as const)('uses authoritative %s forceAllowed=%s without automatic dispatch', async (reason, force) => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch') ? refusal(force, reason, 1000) : new Response('{}'));
    const el = await mount(); await send(el, 'A');
    expect(dispatches()).toHaveLength(1); expect(loads()).toHaveLength(0);
    expect(action(el, 'sv3-residency-force') !== null).toBe(force);
    expect(action(el, 'sv3-residency-retry')?.disabled).toBe(true);
    expect(el.shadowRoot!.textContent).toContain('Try again in about 1 s.');
  });
  it.each(['different', 'same', 'away-and-back', 'empty', 'ime', 'hide', 'pagehide', 'disconnect', 'New', 'Stop'] as const)(
    '%s during force revokes continuation and preserves the retained question', async change => {
      const el = await mount(); await send(el, 'original A');
      let release!: (v: Response) => void;
      http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway') ? new Promise<Response>(r => { release = r; }) : done());
      clickAction(el, 'sv3-residency-force'); await settle(el);
      if (change === 'different') await type(el, 'newer B');
      if (change === 'same') composer(el).draft = composer(el).draft;
      if (change === 'away-and-back') { composer(el).draft = 'B'; composer(el).draft = ''; }
      if (change === 'empty') await type(el, '');
      if (change === 'ime') field(el).dispatchEvent(new Event('compositionstart'));
      if (change === 'hide') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); }
      if (change === 'pagehide') window.dispatchEvent(new Event('pagehide'));
      if (change === 'disconnect') el.remove();
      if (change === 'New') el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
      if (change === 'Stop') clickAction(el, 'sv3-residency-stop');
      release(new Response('{}', { status: 202 })); await settle(el);
      expect(dispatches()).toHaveLength(1);
      if (change === 'different') expect(field(el).value).toBe('newer B');
      const stored = sessionStorage.getItem('justsearch.draft.attachment.v1.search-v3.main')!;
      expect(stored).toContain('original A'); expect(stored).not.toContain('forceAllowed');
      if (el.isConnected) expect(action(el, 'sv3-residency-force')).toBeNull();
    });
  it('Stop during admission cannot be undone by a late typed LOADING refusal', async () => {
    let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch') ? new Promise<Response>(r => { release = r; }) : new Response('{}'));
    const el = await mount(); await send(el, 'stopped A');
    composer(el).dispatchEvent(new CustomEvent('sv3-composer-stop', { bubbles: true, composed: true }));
    await settle(el); release(refusal(true, 'LOADING')); await settle(el);
    expect(action(el, 'sv3-residency-force')).toBeNull();
    expect(dispatches()).toHaveLength(1); expect(loads()).toHaveLength(0);
    expect(el.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!.textContent).not.toContain('Stopped by you');
  });
  it('a second unresolved submission cannot replace retained A with newer B', async () => {
    const el = await mount(); await send(el, 'A'); await send(el, 'B');
    expect(dispatches()).toHaveLength(1); expect(field(el).value).toBe('B');
    expect(el.shadowRoot!.textContent).toContain('explicitly discard');
    expect(sessionStorage.getItem('justsearch.draft.attachment.v1.search-v3.main')).toContain('"raw":"A"');
  });
  it('refuses issuance when session retention fails, preserving raw and live input', async () => {
    const el = await mount(); localStorage.setItem('justsearch.draft.search-v3.composer', 'durable original');
    await withAttachmentWriteFault(() => true, async hits => {
      await send(el, 'only live A');
      expect(hits).toEqual([attachmentKey]);
      expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe('only live A');
      expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('durable original');
      expect(el.shadowRoot!.textContent).toContain('could not be saved safely');
    });
  });
  it.each(['shape', 'effort', 'delivery'] as const)('rejects singleton-array %s without touching any stored or live text', async property => {
    const task = { id: 'old', place: null, target: null, shape: 'core.rag-ask', raw: 'retained A', schema: null,
      scope: [], record: null, needsReselection: false, effort: 'quick', delivery: 'unknown' };
    const bytes = JSON.stringify({ version: 1, incarnation: 'prior', payload: {
      task: { ...task, [property]: [task[property]] }, drafts: [{ place: null, text: 'session B' }],
    } });
    sessionStorage.setItem(attachmentKey, bytes); localStorage.setItem('justsearch.draft.search-v3.composer', 'raw survivor');
    const el = await mount(); expect(field(el).value).toBe('raw survivor');
    await send(el, 'new live');
    expect(dispatches()).toHaveLength(0); expect(sessionStorage.getItem(attachmentKey)).toBe(bytes);
    expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('raw survivor');
    expect(field(el).value).toBe('new live'); expect(el.shadowRoot!.textContent).toContain('could not be saved safely');
  });
  it('EOF without a terminal remains unknown delivery and blocks a second submission', async () => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? new Response('event: content\ndata: {"text":"partial"}\n\n', { headers: { 'content-type': 'text/event-stream' } }) : new Response('{}'));
    const el = await mount(); await send(el, 'A'); await send(el, 'B');
    expect(dispatches()).toHaveLength(1); expect(field(el).value).toBe('B');
    expect(el.shadowRoot!.textContent).toContain('Delivery unknown');
    expect(action(el, 'sv3-residency-retry')).toBeNull(); expect(action(el, 'sv3-residency-force')).toBeNull();
    expect(sessionStorage.getItem(attachmentKey)).toContain('"raw":"A"');
  });
  it('changing the chosen file through the card action during force revokes the old task', async () => {
    const el = await mount(); await send(el, 'A');
    let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway')
      ? new Promise<Response>(r => { release = r; }) : String(url).includes('/dispatch') ? done() : new Response('{}'));
    clickAction(el, 'sv3-residency-force'); await settle(el);
    el.shadowRoot!.querySelector('jf-sv3-main')!.dispatchEvent(new CustomEvent('card-scope-file', {
      detail: { path: '/chosen-B.txt' }, bubbles: true, composed: true,
    }));
    await settle(el);
    release(new Response('{}', { status: 202 })); await settle(el);
    expect(dispatches()).toHaveLength(1); expect(action(el, 'sv3-residency-force')).toBeNull();
    expect(sessionStorage.getItem(attachmentKey)).toContain('"raw":"A"');
  });
  it('compatibility free-chat composer owns clearing its submitted draft', async () => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch') ? done() : new Response('{}'));
    const el = await mount('core.free-chat'); await send(el, 'model composer A');
    expect(body().shapeId).toBe('core.free-chat'); expect(field(el).value).toBe('');
    expect(dispatches()).toHaveLength(1);
  });
  it.each(['malformed', 'version', 'unknown-field', 'oversize'] as const)('%s attachment preserves its bytes and blocks unsafe issuance', async variant => {
    const key = 'justsearch.draft.attachment.v1.search-v3.main';
    const bytes = variant === 'malformed' ? '{broken' : variant === 'oversize' ? 'x'.repeat(262145)
      : JSON.stringify({ version: variant === 'version' ? 2 : 1, incarnation: 'prior', payload: { task: null, drafts: [] }, ...(variant === 'unknown-field' ? { extra: true } : {}) });
    sessionStorage.setItem(key, bytes); localStorage.setItem('justsearch.draft.search-v3.composer', 'raw survivor');
    const el = await mount(); expect(field(el).value).toBe('raw survivor'); await send(el, 'new live');
    expect(dispatches()).toHaveLength(0); expect(sessionStorage.getItem(key)).toBe(bytes); expect(field(el).value).toBe('new live');
  });
  it('UTF8 input bounds refuse before issuance, without truncation', async () => {
    const el = await mount(); const text = '界'.repeat(21846); await send(el, text);
    expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe(text);
  });
  it('ready then explicit off keeps human Send unavailable and draft intact', async () => {
    state(42, true, false); const el = await mount(); await send(el, 'off draft');
    expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe('off draft');
  });

  it('unknown-delivery retention ACK failure blocks issuance before clearing the composer', async () => {
    const el = await mount(); let reserved = '';
    await withAttachmentWriteFault(value => {
      if (value.includes('"delivery":"unknown"')) return true;
      reserved = value; return false;
    }, async hits => {
      await send(el, 'only live A');
      expect(hits).toEqual([attachmentKey, attachmentKey]);
      expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe('only live A');
      expect(sessionStorage.getItem(attachmentKey)).toBe(reserved);
      expect(reserved).toContain('"raw":"only live A"');
      expect(el.shadowRoot!.textContent).toContain('could not be saved safely');
    });
  });
  it('failed retirement keeps A and the newer B companion in existing bytes and live input', async () => {
    let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? new Promise<Response>(r => { release = r; }) : new Response('{}'));
    const el = await mount(); await send(el, 'A'); await type(el, 'newer B');
    const previous = sessionStorage.getItem(attachmentKey);
    await withAttachmentWriteFault(value => JSON.parse(value).payload.task === null, async hits => {
      release(done()); await settle(el);
      expect(hits).toEqual([attachmentKey]); expect(field(el).value).toBe('newer B');
      expect(sessionStorage.getItem(attachmentKey)).toBe(previous);
      expect(previous).toContain('"raw":"A"'); expect(previous).toContain('newer B');
      expect(el.shadowRoot!.textContent).toContain('could not be saved safely');
      await send(el, 'another C'); expect(dispatches()).toHaveLength(1);
    });
  });
  it('retiring a task after navigation retains the originating newer draft', async () => {
    const el = await mount(); await send(el, 'A'); await type(el, 'origin B');
    restoreActiveConversation('elsewhere'); await settle(el);
    clickAction(el, 'sv3-residency-discard'); await settle(el);
    const bytes = sessionStorage.getItem(attachmentKey)!;
    expect(JSON.parse(bytes).payload.task).toBeNull(); expect(bytes).toContain('origin B');
    el.remove(); const restored = await mount();
    expect(restored.shadowRoot!.textContent).toContain('origin B'); expect(dispatches()).toHaveLength(1);
  });
  it.each(['newer text', 'same-text assignment', 'navigation away and back', 'discard'] as const)(
    'initial context wait respects %s without losing captured A', async change => {
      const releases: Array<(v: Response) => void> = [];
      http.mockImplementation(async (url: unknown) => String(url).includes('/history')
        ? new Promise<Response>(r => { releases.push(r); })
        : String(url).includes('/dispatch') ? done() : new Response('{}'));
      setLastViewedConversation('origin'); const el = await mount(); await send(el, 'A');
      expect(dispatches()).toHaveLength(0);
      if (change === 'newer text') await type(el, 'newer B');
      if (change === 'same-text assignment') composer(el).draft = 'A';
      if (change === 'navigation away and back') {
        restoreActiveConversation('elsewhere'); await settle(el);
        restoreActiveConversation('origin'); await settle(el);
      }
      if (change === 'discard') clickAction(el, 'sv3-residency-discard');
      for (const release of releases) release(new Response(JSON.stringify({ sessionId: 'origin', messages: [] })));
      await settle(el);
      expect(dispatches()).toHaveLength(change === 'discard' || change === 'navigation away and back' ? 0 : 1);
      if (change === 'newer text') { expect(body().question).toBe('A'); expect(field(el).value).toBe('newer B'); }
      if (change === 'same-text assignment') expect(field(el).value).toBe('A');
    });
  it.each(['done', 'CANCELLED'] as const)('%s retires the unresolved slot and allows another fresh submission', async terminal => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? terminal === 'done' ? done() : new Response('event: error\ndata: {"errorCode":"CANCELLED","error":"Stopped"}\n\n') : new Response('{}'));
    const el = await mount(); await send(el, 'A');
    expect(retained().payload.task).toBeNull();
    await send(el, 'B'); expect(dispatches()).toHaveLength(2); expect(body(1).turnId).not.toBe(body().turnId);
  });
  it('overlapping same-owner mounts reject a stale writer without overwriting the first task', async () => {
    const first = await mount(); const second = await mount(); await send(first, 'first A');
    const bytes = sessionStorage.getItem(attachmentKey); await send(second, 'second B');
    expect(dispatches()).toHaveLength(1); expect(field(second).value).toBe('second B');
    expect(sessionStorage.getItem(attachmentKey)).toBe(bytes);
    expect(second.shadowRoot!.textContent).toContain('could not be saved safely');
  });
  it('main and known compatibility shape owners retain independent unresolved slots', async () => {
    const first = await mount(); const second = await mount('core.free-chat');
    await send(first, 'main A'); await send(second, 'compat B');
    expect(dispatches()).toHaveLength(2);
    expect(sessionStorage.getItem(attachmentKey)).toContain('"raw":"main A"');
    expect(sessionStorage.getItem('justsearch.draft.attachment.v1.search-v3.core.free-chat')).toContain('"raw":"compat B"');
  });
  it.each(['schema', 'effort', 'tier', 'selection', 'clear selection', 'context', 'handoff', 'navigation', 'reload'] as const)(
    '%s during force requires review and never dispatches the abandoned continuation', async change => {
      const el = await mount(); await send(el, 'original A'); let release!: (v: Response) => void;
      http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway')
        ? new Promise<Response>(r => { release = r; }) : String(url).includes('/dispatch') ? done() : new Response('{}'));
      clickAction(el, 'sv3-residency-force'); await settle(el);
      if (change === 'schema') emit(el, 'sv3-schema-change', { text: '{"type":"object"}' });
      if (change === 'effort') emit(el, 'sv3-effort-change', { effort: 'thorough' });
      if (change === 'tier') emit(el, 'sv3-tier-change', { tier: 'structured' });
      if (change === 'selection') emit(el, 'card-selection', { ids: ['new-file'] });
      if (change === 'clear selection') emit(el, 'sv3-results-clear-selection', {});
      if (change === 'context') emit(el, 'sv3-context-action', { action: 'restore' });
      if (change === 'handoff') parkSearchV3Handoff({ query: 'incoming B', mode: 'ask', docIds: ['/B.txt'] });
      if (change === 'navigation') restoreActiveConversation('elsewhere');
      if (change === 'reload') { el.remove(); await mount(); }
      await settle(el); release(new Response('{}', { status: 202 })); await settle(el);
      expect(dispatches()).toHaveLength(1);
      expect(sessionStorage.getItem(attachmentKey)).toContain('original A');
      if (change === 'handoff') expect(field(el).value).toBe('incoming B');
    });
  it('free-chat compatibility sends subsequent turns with zero documents and preserves newer input', async () => {
    state(0); let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? dispatches().length === 1 ? done() : new Promise<Response>(r => { release = r; }) : new Response('{}'));
    const el = await mount('core.free-chat'); await send(el, 'first A'); await send(el, 'second B');
    expect(dispatches()).toHaveLength(2);
    await type(el, 'unrelated C'); release(done()); await settle(el);
    expect(dispatches()).toHaveLength(2); expect(body(1).shapeId).toBe('core.free-chat');
    expect(body(1).docIds).toBeUndefined(); expect(body(1).context).toBeUndefined();
    expect(field(el).value).toBe('unrelated C');
  });

  it.each(['success', 'refusal'] as const)('a model-answer turn action never clears or restores unrelated composer text on %s', async outcome => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? dispatches().length === 1 ? new Response('event: error\ndata: {"errorCode":"NO_CONTENT","error":"No matching documents"}\n\n')
        : outcome === 'success' ? done() : refusal() : new Response('{}'));
    const el = await mount(); await send(el, 'saved question A'); await type(el, 'unrelated B');
    const button = el.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-answer-without-files]');
    expect(button).not.toBeNull(); button!.click(); await settle(el);
    expect(dispatches()).toHaveLength(2); expect(body(1)).toMatchObject({ shapeId: 'core.free-chat', prompt: 'saved question A', allowLoad: true });
    expect(body(1).docIds).toBeUndefined(); expect(body(1).selection).toBeUndefined(); expect(body(1).context).toBeUndefined();
    expect(field(el).value).toBe('unrelated B');
    if (outcome === 'refusal') {
      const task = retained().payload.task;
      expect(task?.raw).toBe(''); expect(task?.record).toEqual(expect.any(String));
    }
  });
  it('grounded Ask still requires indexed documents and never falls through to model or Delegate', async () => {
    state(0, true); const el = await mount(); await send(el, 'grounded A');
    expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe('grounded A'); expect(loads()).toHaveLength(0);
  });
  it.each(['scope-count', 'scope-id-UTF8', 'schema-UTF8', 'serialized-UTF8'] as const)('DS1 %s cap refuses before replacing current text/settings or any request', async variant => {
    const el = await mount(); await type(el, 'current B');
    window.dispatchEvent(new Event('pagehide')); await settle(el);
    localStorage.setItem('justsearch.draft.search-v3.composer', 'raw survivor');
    const previous = sessionStorage.getItem(attachmentKey);
    if (variant === 'scope-count') parkSearchV3Handoff({ query: 'incoming A', mode: 'ask', docIds: Array.from({ length: 129 }, (_, i) => '/file-' + i) });
    if (variant === 'scope-id-UTF8') parkSearchV3Handoff({ query: 'incoming A', mode: 'ask', docIds: ['/' + '界'.repeat(1366)] });
    if (variant === 'serialized-UTF8') {
      const query = '界'.repeat(21000);
      const docIds = Array.from({ length: 128 }, (_, i) => '/' + i + '界'.repeat(600));
      const bytes = (value: string) => new TextEncoder().encode(value).length;
      expect(bytes(query)).toBeLessThanOrEqual(65536); expect(docIds).toHaveLength(128);
      expect(docIds.every(id => bytes(id) <= 4096)).toBe(true);
      expect(bytes(JSON.stringify({ query, docIds }))).toBeGreaterThan(262144);
      parkSearchV3Handoff({ query, mode: 'ask', docIds });
    }
    if (variant === 'schema-UTF8') {
      emit(el, 'sv3-tier-change', { tier: 'structured' }); await settle(el);
      const beforeSchema = sessionStorage.getItem(attachmentKey);
      const input = composer(el).shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid=sv3-composer-schema]')!;
      const authoredSchema = JSON.stringify({ description: '界'.repeat(5500) });
      input.value = authoredSchema; input.dispatchEvent(new Event('input')); await settle(el);
      expect(input.value).toBe(authoredSchema);
      expect(new TextEncoder().encode(authoredSchema).length).toBeGreaterThan(16384);
      expect(sessionStorage.getItem(attachmentKey)).toBe(beforeSchema);
      composer(el).shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-composer-send]')!.click(); await settle(el);
      expect(dispatches()).toHaveLength(0); expect(input.value).toBe(authoredSchema);
      expect(sessionStorage.getItem(attachmentKey)).toBe(beforeSchema);
    }
    await settle(el);
    expect(dispatches()).toHaveLength(0); expect(field(el).value).toBe('current B');
    if (variant !== 'schema-UTF8') expect(sessionStorage.getItem(attachmentKey)).toBe(previous);
    expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('raw survivor');
    expect(el.shadowRoot!.textContent).toContain('could not be saved safely');
    if (variant !== 'schema-UTF8') {
      // Refused incoming A does not revoke the known, in-bound current B.
      composer(el).shadowRoot!.querySelector<HTMLElement>('[data-testid=sv3-composer-send]')!.click(); await settle(el);
      expect(dispatches()).toHaveLength(1);
      expect(body()).toMatchObject({ shapeId: 'core.rag-ask', question: 'current B', allowLoad: true });
      expect((body().docIds as string[] | undefined) ?? []).toEqual([]);
      expect(JSON.stringify(body())).not.toContain('incoming A');
      expect(JSON.stringify(body())).not.toContain('/file-');
    }
  });
  it.each(['task-field', 'draft-field', 'payload-field', 'scope-type', 'scope-count', 'raw-type', 'raw-UTF8',
    'draft-UTF8', 'schema-type', 'schema-UTF8', 'place-type', 'record-type', 'reselection-type', 'effort-value',
    'delivery-value', 'three-places', 'typed-version', 'incarnation-type', 'preparation-kind-array', 'preparation-scope-kind-array'] as const)(
    'invalid %s attachment fails closed and leaves exact durable bytes and raw text', async variant => {
      const task: Record<string, unknown> = { id: 'old', place: null, target: 'original', shape: 'core.rag-ask', raw: 'A', schema: null,
        scope: [], record: null, needsReselection: false, effort: 'quick', delivery: 'unknown' };
      const payload: Record<string, unknown> = { task, drafts: [{ place: null, text: 'B' }] };
      const row: Record<string, unknown> = { version: 1, incarnation: 'prior', payload };
      if (variant === 'task-field') task.progress = 'forbidden';
      if (variant === 'draft-field') payload.drafts = [{ place: null, text: 'B', extra: true }];
      if (variant === 'payload-field') payload.history = [];
      if (variant === 'scope-type') task.scope = [42];
      if (variant === 'scope-count') task.scope = Array.from({ length: 129 }, (_, i) => String(i));
      if (variant === 'raw-type') task.raw = ['A'];
      if (variant === 'raw-UTF8') task.raw = '界'.repeat(21846);
      if (variant === 'draft-UTF8') payload.drafts = [{ place: null, text: '界'.repeat(21846) }];
      if (variant === 'schema-type') task.schema = {};
      if (variant === 'schema-UTF8') task.schema = '界'.repeat(5462);
      if (variant === 'place-type') task.place = ['elsewhere'];
      if (variant === 'record-type') task.record = 42;
      if (variant === 'reselection-type') task.needsReselection = 'false';
      if (variant === 'effort-value') task.effort = 'maximal';
      if (variant === 'delivery-value') task.delivery = 'sent';
      if (variant === 'three-places') payload.drafts = [null, 'one', 'two'].map(place => ({ place, text: 'B' }));
      if (variant === 'typed-version') row.version = '1';
      if (variant === 'incarnation-type') row.incarnation = ['prior'];
      if (variant === 'preparation-kind-array' || variant === 'preparation-scope-kind-array') {
        payload.version = 2;
        payload.drafts = [{ place: null, text: 'B', preparation: variant === 'preparation-kind-array'
          ? { kind: ['search'] } : { kind: 'ask', scope: { kind: ['files'], paths: ['id'] } } }];
      }
      const bytes = JSON.stringify(row); sessionStorage.setItem(attachmentKey, bytes);
      localStorage.setItem('justsearch.draft.search-v3.composer', 'raw survivor'); const el = await mount();
      expect(field(el).value).toBe('raw survivor'); await send(el, 'live C');
      expect(dispatches()).toHaveLength(0); expect(sessionStorage.getItem(attachmentKey)).toBe(bytes);
      expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('raw survivor'); expect(field(el).value).toBe('live C');
    });
  it.each(['raw', 'incoming'] as const)('DS1 %s preserves raw C and session B before refusing a third incoming snapshot', async owner => {
    sessionStorage.setItem(attachmentKey, JSON.stringify({ version: 1, incarnation: 'prior', payload: {
      task: { id: 'old', place: null, target: 'old-target', shape: 'core.rag-ask', raw: 'retained A', schema: null,
        scope: [], record: null, needsReselection: false, effort: 'quick', delivery: 'unknown' }, drafts: [{ place: null, text: 'session B' }],
    } }));
    localStorage.setItem('justsearch.draft.search-v3.composer', 'raw C');
    if (owner === 'incoming') parkSearchV3Handoff({ query: 'incoming D', mode: 'ask', docIds: ['/D.txt'] });
    const before = sessionStorage.getItem(attachmentKey);
    const el = await mount(); expect(field(el).value).toBe('raw C');
    expect(sessionStorage.getItem(attachmentKey)).toBe(before);
    expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('raw C');
    expect(el.shadowRoot!.textContent).toContain('session B'); expect(el.shadowRoot!.textContent).toContain('retained A');
    expect(dispatches()).toHaveLength(0); expect(action(el, 'sv3-residency-force')).toBeNull();
  });
  it('tab-isolated Storage realms retain distinct tasks and a copied attachment restores only for review', async () => {
    const tab1 = new Window(); const tab2 = new Window(); const copy = new Window();
    try {
      vi.stubGlobal('sessionStorage', tab1.sessionStorage); const first = await mount(); await send(first, 'tab one A'); first.remove();
      __resetConversationListForTest(); __resetDraftProvidersForTest();
      vi.stubGlobal('sessionStorage', tab2.sessionStorage); const second = await mount(); await send(second, 'tab two B'); second.remove();
      expect(tab1.sessionStorage.getItem(attachmentKey)).toContain('"raw":"tab one A"');
      expect(tab2.sessionStorage.getItem(attachmentKey)).toContain('"raw":"tab two B"');
      copy.sessionStorage.setItem(attachmentKey, tab1.sessionStorage.getItem(attachmentKey)!);
      __resetConversationListForTest(); __resetDraftProvidersForTest(); vi.stubGlobal('sessionStorage', copy.sessionStorage);
      const restored = await mount(); expect(restored.shadowRoot!.textContent).toContain('tab one A');
      expect(dispatches()).toHaveLength(2); expect(action(restored, 'sv3-residency-force')).toBeNull();
      restored.remove();
    } finally { await tab1.happyDOM.close(); await tab2.happyDOM.close(); await copy.happyDOM.close(); }
  });
  it('summarize preserves its exact live passage but persists no excerpt, history or force authority', async () => {
    const el = await mount();
    setPendingSelection(passage('PRIVATE_SELECTED_EXCERPT'));
    parkSearchV3Handoff({ query: 'user-authored A', forceShapeId: 'core.summarize' }); await settle(el);
    await send(el, 'user-authored A');
    expect(body()).toMatchObject({ shapeId: 'core.summarize', allowLoad: true,
      selection: passage('PRIVATE_SELECTED_EXCERPT') });
    const bytes = sessionStorage.getItem(attachmentKey)!; expect(bytes).toContain('user-authored A');
    expect(bytes).not.toContain('PRIVATE_SELECTED_EXCERPT'); expect(bytes).not.toContain('forceAllowed'); expect(bytes).not.toContain('history');
    expect(JSON.parse(bytes).payload.task.needsReselection).toBe(true);
  });

  it('Explain retention excludes the embedded backend ranking trace and preserves user instructions', async () => {
    const el = await mount(); const trace = 'BACKEND_RANKING_TRACE_731';
    setPendingSelection({ kind: 'search-trace', scope: 'hit', summary: trace });
    parkSearchV3Handoff({ query: 'Explain this ranking\n\nRanking trace:\n' + trace, forceShapeId: 'core.summarize' });
    await settle(el);
    await send(el, field(el).value);
    expect(body().prompt).toBe('Explain this ranking\n\nRanking trace:\n' + trace);
    const bytes = sessionStorage.getItem(attachmentKey)!;
    expect(bytes).toContain('Explain this ranking'); expect(bytes).not.toContain(trace);
    expect(retained().payload.task).toMatchObject({ raw: 'Explain this ranking', needsReselection: true });
  });

  it('an overlapping stale owner cannot retire a task after its live owner retries back to the same inputs', async () => {
    const first = await mount(); await send(first, 'A');
    const stale = await mount(); const old = sessionStorage.getItem(attachmentKey);
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway') ? new Response('{}')
      : String(url).includes('/dispatch') ? refusal() : new Response('{}'));
    clickAction(first, 'sv3-residency-force'); await settle(first);
    expect(dispatches()).toHaveLength(2);
    const current = sessionStorage.getItem(attachmentKey); expect(current).not.toBe(old);
    clickAction(stale, 'sv3-residency-discard'); await settle(stale);
    expect(sessionStorage.getItem(attachmentKey)).toBe(current);
    expect(stale.shadowRoot!.textContent).toContain('could not be saved safely');
    expect(action(stale, 'sv3-residency-discard')).not.toBeNull();
  });

  it.each(['core.rag-ask', 'core.free-chat'] as const)('DS1 saved %s Retry/Edit require their own exact scope and preserve B', async shape => {
    const messages = [
      { id: '11111111-2222-4333-8444-555555555551', role: 'user', content: 'saved A', shapeId: shape },
      { id: '11111111-2222-4333-8444-555555555552', role: 'assistant', content: 'saved answer', shapeId: shape },
    ];
    http.mockImplementation(async (url: unknown) => {
      const href = String(url);
      if (href.includes('/dispatch')) return done();
      if (href.includes('/branch?')) return new Response(JSON.stringify({ sessionId: 'child' }));
      if (href.includes('/history')) return new Response(JSON.stringify({ sessionId: href.includes('/saved/') ? 'saved' : 'child', messages: href.includes('/saved/') ? messages : [] }));
      if (href.includes('/api/thread/')) return new Response(JSON.stringify({ conversationId: 'saved', events: href.endsWith('/saved') ? messages.map((m, i) => ({
        id: m.id, occurredAt: `2026-10-23T00:00:0${i}Z`, kind: m.role === 'user' ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE',
        content: m.content, originator: m.role, attributes: { shapeId: shape },
      })) : [] }));
      return new Response('{}');
    });
    setLastViewedConversation('saved'); const el = await mount(); await type(el, 'unrelated B');
    for (const action of ['retry', 'edit'] as const) {
      restoreActiveConversation('saved'); await settle(el);
      const main = el.shadowRoot!.querySelector('jf-sv3-main')!;
      const question = main.shadowRoot!.querySelector('[data-testid=sv3-turn-question]'); expect(question).not.toBeNull();
      const turnId = question!.getAttribute('data-item-id')!.replace(/:q$/, '');
      main.dispatchEvent(new CustomEvent('sv3-branch-action', { bubbles: true, composed: true,
        detail: { action, turnId, ...(action === 'edit' ? { text: 'edited B' } : {}) } })); await settle(el);
      if (shape === 'core.rag-ask') {
        expect(dispatches()).toHaveLength(0);
        expect(http.mock.calls.filter(c => String(c[0]).includes('/branch?'))).toHaveLength(0);
        expect(field(el).value).toBe('unrelated B');
        expect(el.shadowRoot!.textContent).toContain('original Ask inputs are not known');
        continue;
      }
      expect(dispatches()).toHaveLength(action === 'retry' ? 1 : 2);
      expect(body(action === 'retry' ? 0 : 1)).toMatchObject({ shapeId: shape, allowLoad: true, conversationId: 'child',
        [shape === 'core.free-chat' ? 'prompt' : 'question']: action === 'retry' ? 'saved A' : 'edited B' });
      restoreActiveConversation('saved'); await settle(el);
      expect(field(el).value).toBe('unrelated B');
    }
  });
  it('an explicit force retains its lock through the follow-up dispatch', async () => {
    const el = await mount(); await send(el, 'A'); let finish!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway') ? new Response('{}', { status: 202 })
      : String(url).includes('/dispatch') ? new Promise<Response>(r => { finish = r; }) : new Response('{}'));
    const force = action(el, 'sv3-residency-force'); expect(force).not.toBeNull(); force!.click(); await settle(el);
    force!.click(); field(el).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await settle(el);
    expect(loads()).toHaveLength(1); expect(dispatches()).toHaveLength(2);
    expect(body(1).question).toBe('A'); finish(done()); await settle(el);
  });
  it('a stale force control cannot load or dispatch after explicit retirement', async () => {
    const el = await mount(); await send(el, 'A'); const force = action(el, 'sv3-residency-force'); expect(force).not.toBeNull();
    clickAction(el, 'sv3-residency-discard'); await settle(el); await type(el, 'newer B'); force!.click(); await settle(el);
    expect(loads()).toHaveLength(0); expect(dispatches()).toHaveLength(1); expect(field(el).value).toBe('newer B');
  });
  it.each(['transport', 'http', 'typed'] as const)('a %s load failure retains A and newer input without a follow-up dispatch', async failure => {
    const el = await mount(); await send(el, 'A');
    http.mockImplementation(async (url: unknown) => {
      if (String(url).includes('/load-anyway')) {
        if (failure === 'transport') throw new TypeError('Failed to fetch');
        return failure === 'typed' ? refusal(false, 'RELOAD_BACKOFF', 5000) : new Response('{}', { status: 500 });
      }
      return new Response('{}');
    });
    clickAction(el, 'sv3-residency-force'); await settle(el);
    expect(loads()).toHaveLength(1); expect(dispatches()).toHaveLength(1);
    expect(sessionStorage.getItem(attachmentKey)).toContain('"raw":"A"');
    if (failure === 'typed') {
      expect(action(el, 'sv3-residency-force')).toBeNull(); expect(action(el, 'sv3-residency-retry')?.disabled).toBe(true);
    }
    await type(el, 'newer B'); expect(field(el).value).toBe('newer B');
  });
  it('untyped HTTP rejection remains delivery unknown with no inferred force authority', async () => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch') ? new Response('{"error":"untyped refusal"}', { status: 503 }) : new Response('{}'));
    const el = await mount(); await send(el, 'A');
    expect(el.shadowRoot!.textContent).toContain('Delivery unknown'); expect(action(el, 'sv3-residency-force')).toBeNull();
    await send(el, 'B'); expect(dispatches()).toHaveLength(1); expect(field(el).value).toBe('B');
  });
  it.each(['off', 'failed', 'loading'] as const)('force continuation rechecks %s status without granting background readiness', async update => {
    const el = await mount(); await send(el, 'A'); let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway')
      ? new Promise<Response>(r => { release = r; }) : String(url).includes('/dispatch') ? done() : new Response('{}'));
    clickAction(el, 'sv3-residency-force'); await settle(el);
    __feedForTest({ inference: { available: false, starting: update === 'loading' } as never,
      status: { inference: { chatEnabledSpec: update !== 'off' }, worker: { core: { indexedDocuments: 42 } },
        operating: { chat: { state: update === 'loading' ? 'LOADING' : update === 'off' ? 'OFF' : 'FAILED' } },
        readiness: { composites: { aiFeatures: { state: 'DEGRADED', reasonCodes: update === 'failed' ? ['inference.model_load_failed'] : [] } } },
      } as unknown as StatusSnapshot }); __feedContactForTest();
    release(new Response('{}', { status: 202 })); await settle(el);
    expect(dispatches()).toHaveLength(update === 'loading' ? 2 : 1);
    expect(composer(el).delegateUnavailableReason).not.toBe('');
  });
  it('exact UTF8 user-text and scope limits are accepted, with captured effort and scope intact', async () => {
    const el = await mount(); const text = '界'.repeat(21845) + 'a';
    const ids = Array.from({ length: 128 }, (_, i) => i === 0 ? '/'+ '界'.repeat(1365) : '/file-' + i);
    parkSearchV3Handoff({ query: text, mode: 'ask', docIds: ids }); await settle(el);
    emit(el, 'sv3-effort-change', { effort: 'thorough' }); await settle(el); await send(el, text);
    expect(dispatches()).toHaveLength(1); expect(body()).toMatchObject({ question: text, docIds: ids, allowLoad: true, maxTokens: 3072, topK: 12 });
    expect(retained().payload.task).toMatchObject({ raw: text, scope: ids, effort: 'thorough' });
  });
  it('exact schema UTF8 limit is accepted for typed Structured', async () => {
    state(0); const el = await mount('core.extract');
    const schema = JSON.stringify({ description: 'a'.repeat(16384 - JSON.stringify({ description: '' }).length) });
    const input = composer(el).shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid=sv3-composer-schema]')!;
    input.value = schema; input.dispatchEvent(new Event('input')); await settle(el); await send(el, 'typed A');
    expect(dispatches()).toHaveLength(1); expect(body()).toMatchObject({ shapeId: 'core.extract', schema, prompt: 'typed A', allowLoad: true });
  });
  it('Stop through late transport loss stays revoked and cannot become a saved stopped claim', async () => {
    let reject!: (reason: Error) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? new Promise<Response>((_resolve, fail) => { reject = fail; }) : new Response('{}'));
    const el = await mount(); await send(el, 'A'); emit(el, 'sv3-composer-stop', {}); await settle(el);
    reject(new TypeError('Failed to fetch')); await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Delivery unknown'); expect(action(el, 'sv3-residency-force')).toBeNull();
    expect(el.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!.textContent).not.toContain('Stopped by you');
    expect(dispatches()).toHaveLength(1);
  });

  it('choosing a file after a free-chat handoff replaces its model-only preparation', async () => {
    const el = await mount(); parkSearchV3Handoff({ query: 'A', forceShapeId: 'core.free-chat' }); await settle(el);
    emit(el, 'card-scope-file', { id: 'chosen', path: '/chosen.txt', title: 'chosen.txt' }); await settle(el);
    await send(el, 'A'); expect(body()).toMatchObject({ shapeId: 'core.rag-ask', question: 'A', docIds: ['/chosen.txt'], allowLoad: true });
    expect(body().selection).toBeUndefined();
  });
  it('supported Summarize with a passage does not acquire an indexed-document prerequisite', async () => {
    state(0, true); const el = await mount();
    setPendingSelection(passage('chosen passage'));
    parkSearchV3Handoff({ query: 'Summarize A', forceShapeId: 'core.summarize' }); await settle(el);
    await send(el, 'Summarize A'); expect(dispatches()).toHaveLength(1);
    expect(body()).toMatchObject({ shapeId: 'core.summarize', allowLoad: true, selection: passage('chosen passage') });
  });

  it('the real chosen-result Ask control remains operable for human admission while unloaded', async () => {
    state(42, true);
    http.mockImplementation(async (url: unknown) => String(url).includes('/api/knowledge/search')
      ? new Response(JSON.stringify({ results: [{ id: 'chosen', fields: { path: '/chosen.txt' } }], totalHits: 1, matchCount: 1 }))
      : String(url).includes('/dispatch') ? refusal() : new Response('{}'));
    const el = await mount(); emit(el, 'sv3-tier-change', { tier: 'search' }); await settle(el);
    emit(el, 'sv3-composer-submit', { tier: 'search', query: 'find A' }); await settle(el);
    emit(el, 'card-selection', { ids: ['chosen'] }); await settle(el);
    const results = el.shadowRoot!.querySelector('jf-sv3-main')!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-sv3-results');
    expect(results).not.toBeNull(); await results!.updateComplete;
    const control = results!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown>; availability: { kind: string } }>('[data-testid=sv3-results-ask]');
    expect(control).not.toBeNull(); await control!.updateComplete;
    expect(control!.availability.kind).not.toBe('unavailable');
    control!.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await settle(el);
    await send(el, 'scoped A'); expect(dispatches()).toHaveLength(1);
    expect(body()).toMatchObject({ shapeId: 'core.rag-ask', question: 'scoped A', docIds: ['/chosen.txt'], allowLoad: true });
  });
  it('confirmed deletion during force revokes the old target without sending Stop', async () => {
    const el = await mount(); await send(el, 'A'); const target = String(body().conversationId);
    let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown, init?: RequestInit) => {
      const href = String(url);
      if (href.includes('/load-anyway')) return new Promise<Response>(r => { release = r; });
      if (init?.method === 'DELETE') return new Response('{}');
      if (href.includes('childrenOf=')) return new Response(JSON.stringify({ childrenOf: target, locked: false,
        sessions: [{ sessionId: target, createdAtMs: 1, lastActiveAtMs: 2, messageCount: 0, firstUserMessage: 'A', busy: false }] }));
      return new Response('{}');
    });
    clickAction(el, 'sv3-residency-force'); await settle(el);
    el.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-remove', { detail: { id: target }, bubbles: true, composed: true }));
    await settle(el);
    const dialog = document.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-confirm-dialog');
    expect(dialog).not.toBeNull(); await dialog!.updateComplete;
    const confirm = dialog!.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-button.confirm')!;
    await confirm.updateComplete;
    const control = confirm.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-control')!;
    await control.updateComplete; control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(el); expect(http.mock.calls.some(c => c[1]?.method === 'DELETE')).toBe(true);
    release(new Response('{}', { status: 202 })); await settle(el);
    expect(dispatches()).toHaveLength(1); expect(action(el, 'sv3-residency-force')).toBeNull();
    expect(http.mock.calls.filter(c => String(c[0]).endsWith('/stop'))).toHaveLength(0);
  });

  it('ordinary Retry becomes available after its interval but the timer never submits', async () => {
    http.mockImplementation(async (url: unknown) => String(url).includes('/dispatch')
      ? dispatches().length === 1 ? refusal(false, 'RELOAD_BACKOFF', 80) : done() : new Response('{}'));
    const el = await mount(); await send(el, 'A');
    expect(action(el, 'sv3-residency-retry')?.disabled).toBe(true);
    await new Promise(r => setTimeout(r, 100)); await settle(el);
    expect(dispatches()).toHaveLength(1); expect(loads()).toHaveLength(0);
    expect(action(el, 'sv3-residency-retry')?.disabled).toBe(false);
    clickAction(el, 'sv3-residency-retry'); await settle(el); expect(dispatches()).toHaveLength(2);
  });
  it('context that changes during the follow-up confirmation cannot replace the captured force context', async () => {
    const el = await mount(); await send(el, 'A'); let release!: (v: Response) => void;
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway') ? new Response('{}', { status: 202 })
      : String(url).includes('/history') ? new Promise<Response>(r => { release = r; })
        : String(url).includes('/dispatch') ? done() : new Response('{}'));
    clickAction(el, 'sv3-residency-force'); await settle(el);
    expect(http.mock.calls.some(c => String(c[0]).endsWith('/history'))).toBe(true);
    release(new Response(JSON.stringify({ sessionId: body().conversationId, messages: [], contextFloorSummary: 'changed backend context' })));
    await settle(el); expect(dispatches()).toHaveLength(1);
    expect(el.shadowRoot!.textContent).toContain('Saved context changed');
    expect(sessionStorage.getItem(attachmentKey)).not.toContain('changed backend context');
  });

  it('a forced Structured success consumes only its original one-send file preparation', async () => {
    const el = await mount(); parkSearchV3Handoff({ query: 'A', forceShapeId: 'core.extract', docIds: ['/A.txt'] }); await settle(el);
    const schema = composer(el).shadowRoot!.querySelector<HTMLTextAreaElement>('[data-testid=sv3-composer-schema]')!;
    schema.value = '{"type":"object"}'; schema.dispatchEvent(new Event('input')); await settle(el); await send(el, 'A');
    http.mockImplementation(async (url: unknown) => String(url).includes('/load-anyway') ? new Response('{}', { status: 202 })
      : String(url).includes('/dispatch') ? done() : new Response('{}'));
    clickAction(el, 'sv3-residency-force'); await settle(el);
    expect(dispatches()).toHaveLength(2); expect(body(1)).toMatchObject({ shapeId: 'core.extract', selection: { kind: 'result-set', items: [{ id: '/A.txt', kind: 'doc' }] } });
    expect(composer(el).scopeLabel).toBe('');
    await send(el, 'B'); expect(dispatches()).toHaveLength(3); expect(body(2).selection).toBeUndefined();
  });

});
