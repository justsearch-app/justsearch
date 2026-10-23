// @vitest-environment happy-dom

/**
 * Search v3's conversational core (tempdoc 822 Phase F1).
 *
 * Nothing here reaches the network: the global fetch is stubbed with a Response-shaped stub whose
 * body is a real SSE stream, so `consumeShapeStream` is exercised for real (the parser, the terminal
 * rule, the abort path) while the socket is imaginary. The stub honours the abort signal exactly as
 * fetch does — its reader REJECTS once the signal aborts — because a stub that ignored the signal
 * would let a broken Stop pass.
 *
 * The properties asserted as MECHANISMS rather than appearances:
 *  - **One send, one dispatch.** A second issuance site would show up as a second fetch.
 *  - **The slot holds exactly one control.** Both-rendered fails, not just "Stop is somewhere".
 *  - **A refusal keeps the draft.** The text is read back character for character.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { TURN_HALTED, TURN_STOP_FAILED, TURN_STOPPING } from './fixtures.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** The observed state in which the ask tier is genuinely available — the window's own precondition. */
function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

interface FakeStream {
  emit(event: string, data: unknown): void;
  end(): void;
}

/** How the Engine answers the Stop's own request: a status and body, or no answer at all. */
type StopReply = { readonly status: number; readonly body: unknown } | 'unreachable';

/**
 * Stub the dispatch with an SSE body the test drives frame by frame. `read()` waits when the queue
 * is empty, so a streaming assertion can observe the half-finished answer instead of racing it.
 */
function stubStream(
  stopReply: StopReply = { status: 200, body: { stopped: true, pending: false } },
): FakeStream {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  let signal: AbortSignal | null = null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  fetchMock.mockImplementation(async (url: unknown, init: { signal?: AbortSignal }) => {
    // The stream stands in for the dispatch alone; the Stop's own request is answered as the case
    // says, and every other read plainly.
    if (/\/api\/chat\/conversations\/[^/]+\/stop$/.test(String(url))) {
      if (stopReply === 'unreachable') throw new TypeError('Failed to fetch');
      return {
        ok: stopReply.status >= 200 && stopReply.status < 300,
        status: stopReply.status,
        json: () => Promise.resolve(stopReply.body),
      };
    }
    if (!String(url).includes('/api/chat/dispatch')) {
      return { ok: true, status: 200, json: () => Promise.resolve({}) };
    }
    signal = init?.signal ?? null;
    return {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => {
            while (queued.length === 0) {
              if (signal?.aborted === true) throw new Error('The operation was aborted.');
              await new Promise<void>((resolve) => {
                wake = resolve;
                signal?.addEventListener('abort', () => resolve(), { once: true });
              });
            }
            return queued.shift();
          },
          releaseLock: () => {},
        }),
      },
    };
  });
  return {
    emit: (event, data) =>
      push({ done: false, value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }),
    end: () => push({ done: true }),
  };
}

/** Fail the dispatch with an HTTP status, the shape `consumeShapeStream` stamps onto its error. */
function stubStatus(status: number): void {
  fetchMock.mockResolvedValue({
    ok: false,
    status,
    text: () => Promise.resolve(''),
    body: null,
  });
}

/**
 * The calls to the ONE issuance exit. Since Phase F6 the window also READS on mount and on a claim
 * (the app-wide conversation list, the canonical thread record), so a bare call count would answer
 * a different question than "how many times did this window issue?" — the filter keeps the
 * assertion on issuance, which is the thing that must be exactly one.
 */
const dispatches = (): unknown[][] =>
  fetchMock.mock.calls
    // The conversation store's own auto-titling (Phase F7, inventory A11) used to be a second POST
    // here, a throwaway `_title_…` turn; it has its own `…/auto-title` route now, so every call to
    // this endpoint is an issuance.
    .filter((call) => String(call[0]).includes('/api/chat/dispatch'));

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) });
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  // WP1.1 — a fresh window starts in Search mode; these cases are about Ask, so they choose it.
  (el as unknown as { tier: string }).tier = 'ask';
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

async function type(el: Mounted, draft: string): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  return field;
}

/** Type and press the send control — the affordance a reader has, not an internal call. */
async function ask(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  const composer = await region(el, 'jf-sv3-composer');
  (q(composer, 'sv3-composer-send') as HTMLButtonElement | null)?.click();
  await settle(el);
}

/** Drain the microtask/macrotask turns the dispatch and the two renders need. */
async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

const turnsOf = (main: Mounted): HTMLElement[] => [
  ...(main.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-turn"]') ?? []),
];

const textIn = (turn: HTMLElement, testid: string): string =>
  turn.querySelector(`[data-testid="${testid}"]`)?.textContent?.trim() ?? '';

/**
 * The answer as RENDERED — read out of the shared markdown block's own root (Phase F4 moved the
 * response one shadow level down), so what is asserted is the text a reader actually sees rather
 * than the source string the window was handed.
 */
/** One retrieval source in the shape the backend mints (`rag.citations`). */
const source = (i: number): Record<string, unknown> => ({
  parentDocId: `f:/docs/note-${i}.md`,
  chunkIndex: i,
  chunkTotal: 2,
  startChar: 0,
  endChar: 40,
  score: 0.8,
  excerpt: `excerpt ${i}`,
  startLine: 1,
  endLine: 4,
  headingText: 'Notes',
  headingLevel: 2,
});

/** The shared panel's own header line — its count is the one the window shows. */
/**
 * The tail's own disclosure (Phase F11) — the count lives in its ACCESSIBLE NAME, since the imported
 * panel no longer heads itself inside this window.
 */
const sourcesName = (turn: HTMLElement): string =>
  turn.querySelector('[data-testid="sv3-turn-sources"]')?.getAttribute('aria-label') ?? '';

const answerTextIn = (turn: HTMLElement): string =>
  (
    turn
      .querySelector('[data-testid="sv3-turn-markdown"]')
      ?.shadowRoot?.querySelector('.md-content')?.textContent ?? ''
  ).trim();

describe('the window has exactly ONE ask-issuance site', () => {
  const here = dirname(fileURLToPath(import.meta.url));

  it('lets no file but sv3-ask.ts consume the shared shape stream', () => {
    const offenders = readdirSync(here)
      .filter((name) => name.endsWith('.ts'))
      // This file names the symbol in order to forbid it — the one allowed mention.
      .filter((name) => name !== 'sv3-ask.ts' && name !== 'SearchV3View.ask.test.ts')
      .filter((name) => readFileSync(join(here, name), 'utf8').includes('consumeShapeStream'));
    expect(offenders).toEqual([]);
    // Another window's client is the other way this could go wrong: no import may cross into a
    // sibling WINDOW, whose ask path this module MINED rather than imported. Phase F2 widened the
    // scan from the static `from '../<window>/…'` form to any IMPORT SHAPE — a deep-relative path,
    // a dynamic `import()`, a `require()` — because the coupling is the same however it is spelled,
    // and a rule that only catches one spelling teaches the other one. Tempdoc 851 retired the
    // search-v2 window, so the surviving sibling this can cross into is the shipped window.
    const crossImports = readdirSync(here)
      .filter((name) => name.endsWith('.ts'))
      // This file writes the pattern in order to forbid it — the one allowed mention.
      .filter((name) => name !== 'SearchV3View.ask.test.ts')
      .filter((name) =>
        /(?:\bfrom\s*|\bimport\s*\(|\brequire\s*\()\s*['"][^'"]*UnifiedChatView[^'"]*['"]/.test(
          readFileSync(join(here, name), 'utf8'),
        ),
      );
    expect(crossImports).toEqual([]);
  });
});

describe('a send asks the local model, once, through the one site', () => {
  it('dispatches the shape with the question and this session as the conversation', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'Because the lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    expect(dispatches()).toHaveLength(1);
    const [url, init] = dispatches()[0] as [unknown, { body: string }];
    expect(String(url)).toContain('/api/chat/dispatch');
    const body = JSON.parse(init.body);
    expect(body.shapeId).toBe('core.rag-ask');
    expect(body.question).toBe('why did the renewal fail?');
    // The id is the app-wide store's, not this window's (Phase F6): a v3 session IS a conversation,
    // so the dispatch is stamped with the same identity the conversation list will carry.
    expect(body.conversationId).toBe(el.sessions.sessions[0]?.id);
    expect(String(body.conversationId).startsWith('uc-')).toBe(true);
    // Open retrieval by construction: this window scopes an answer to no committed document set.
    expect(body.docIds).toEqual([]);
    // The send is an ask AND a state change, not one at the cost of the other.
    expect(el.getAttribute('composer-state')).toBe('docked');
  });

  it('sends on Enter and leaves Shift+Enter to the field', async () => {
    aiOnline();
    stubStream();
    const el = await mount();
    const field = await type(el, 'vendor risk');
    field.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true }),
    );
    await settle(el);
    expect(dispatches()).toHaveLength(0);
    expect(el.getAttribute('composer-state')).toBe('hero');

    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(el);
    expect(dispatches()).toHaveLength(1);
    expect(el.getAttribute('composer-state')).toBe('docked');
  });

  it('empties the draft on a send that was accepted', async () => {
    aiOnline();
    stubStream();
    const el = await mount();
    await ask(el, 'what changed?');
    expect((await fieldOf(el)).value).toBe('');
  });
});

describe('the transcript is the session, in order', () => {
  it('checks only from the Engine signal through the citation result, preserving the streamed text', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'who owns Cobalt?');
    const main = await region(el, 'jf-sv3-main');
    stream.emit('rag.citations', { citations: [source(0)] });
    stream.emit('chunk', { text: 'Ada owns Cobalt [1].' });
    await settle(el);
    expect(q(main, 'sv3-citation-checking')).toBeNull();
    const textBefore = answerTextIn(turnsOf(main)[0] as HTMLElement);
    stream.emit('rag.citation_checking', {});
    await settle(el);
    const checking = q(main, 'sv3-citation-checking');
    expect(checking?.textContent).toContain('Checking the answer against your sources');
    expect(answerTextIn(turnsOf(main)[0] as HTMLElement)).toBe(textBefore);
    const live = q(main, 'sv3-checking-announcement');
    expect(live?.getAttribute('role')).toBe('status');
    expect(live?.closest('[aria-busy="true"]')).toBeNull();
    expect(live?.textContent).toContain('Checking the answer against your sources');
    stream.emit('rag.citation_checking', {});
    await settle(el);
    expect(q(main, 'sv3-checking-announcement')).toBe(live);
    expect(q(main, 'sv3-citation-checking')).toBe(checking);
    stream.emit('rag.citation_matches', { matches: [], groundingIncomplete: true });
    await settle(el);
    expect(q(main, 'sv3-citation-checking')).toBeNull();
    expect(q(main, 'sv3-checking-announcement')?.textContent?.trim()).toBe('');
    // A later finishing step still holds the turn open; checking has already ended.
    expect(turnsOf(main)[0]?.getAttribute('data-status')).not.toBe('complete');
    stream.emit('done', {});
    stream.end();
    await settle(el);
    expect(q(main, 'sv3-citation-checking')).toBeNull();
    expect(textIn(turnsOf(main)[0] as HTMLElement, 'sv3-turn-coverage')).toContain('Grounding check did not complete');
    const next = stubStream();
    await ask(el, 'and when?');
    expect(q(main, 'sv3-citation-checking')).toBeNull();
    next.emit('chunk', { text: 'Since Tuesday.' });
    next.emit('rag.citation_checking', {});
    await settle(el);
    expect(turnsOf(main)[0]?.querySelector('[data-testid="sv3-citation-checking"]')).toBeNull();
    expect(turnsOf(main)[1]?.querySelector('[data-testid="sv3-citation-checking"]')).not.toBeNull();
    next.emit('done', {});
    next.end();
    await settle(el);
  });

  it.each(['error', 'CANCELLED', 'done', 'drop'])(
    'clears checking and its announcement on %s even without a result', async (outcome) => {
      aiOnline();
      const stream = stubStream();
      const el = await mount();
      await ask(el, 'who owns Cobalt?');
      const main = await region(el, 'jf-sv3-main');
      stream.emit('chunk', { text: 'Ada owns Cobalt.' });
      stream.emit('rag.citation_checking', {});
      await settle(el);
      expect(q(main, 'sv3-citation-checking')).not.toBeNull();
      if (outcome === 'done') stream.emit('done', {});
      else if (outcome !== 'drop') stream.emit('error', { error: 'ended', errorCode: outcome });
      stream.end();
      await settle(el);
      expect(q(main, 'sv3-citation-checking')).toBeNull();
      expect(q(main, 'sv3-checking-announcement')?.textContent?.trim()).toBe('');
    },
  );

  it('renders each turn as question-then-answer, oldest first, and appends deltas as they arrive', async () => {
    aiOnline();
    const first = stubStream();
    const el = await mount();
    await ask(el, 'first question');
    const main = await region(el, 'jf-sv3-main');

    // Mid-stream: the partial answer is on screen, not withheld until the terminal.
    first.emit('chunk', { text: 'Partly ' });
    await settle(el);
    expect(answerTextIn(turnsOf(main)[0] as HTMLElement)).toBe('Partly');
    first.emit('chunk', { text: 'because of the lock.' });
    await settle(el);
    expect(answerTextIn(turnsOf(main)[0] as HTMLElement)).toBe('Partly because of the lock.');
    first.emit('rag.citations', { citations: [source(0), source(1)] });
    first.emit('done', {});
    first.end();
    await settle(el);
    // Phase F4 — the count moved to the surface that shows the sources; the note no longer repeats
    // it. Phase F11 — that surface is now the tail's own disclosure, which carries the count in its
    // accessible name.
    const settled = turnsOf(main)[0] as HTMLElement;
    expect(textIn(settled, 'sv3-turn-note')).toBe('');
    expect(sourcesName(settled)).toContain('2');

    const second = stubStream();
    await ask(el, 'second question');
    second.emit('chunk', { text: 'And so.' });
    second.emit('done', {});
    second.end();
    await settle(el);

    const turns = turnsOf(main);
    expect(turns).toHaveLength(2);
    expect(turns.map((t) => textIn(t, 'sv3-turn-question'))).toEqual([
      'first question',
      'second question',
    ]);
    expect(turns.map((t) => answerTextIn(t))).toEqual([
      'Partly because of the lock.',
      'And so.',
    ]);
    // The conversation owns the region: the search projection is not what is rendering here.
    expect(q(main, 'sv3-main-count')).toBeNull();
  });
});

describe('the primary slot renders exactly one of Send and Stop', () => {
  it('is Send while idle and Stop while a response streams — never both', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-send')).not.toBeNull();
    expect(q(composer, 'sv3-composer-stop')).toBeNull();

    await ask(el, 'a long one');
    await composer.updateComplete;
    // The mutation probe: a slot that RENDERED BOTH (or disabled Send behind Stop) fails here.
    expect(q(composer, 'sv3-composer-stop')).not.toBeNull();
    expect(q(composer, 'sv3-composer-send')).toBeNull();

    stream.emit('done', {});
    stream.end();
    await settle(el);
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-send')).not.toBeNull();
    expect(q(composer, 'sv3-composer-stop')).toBeNull();
  });

  it('halts on Stop, keeps what streamed, and says the reader stopped it', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'a long one');
    stream.emit('chunk', { text: 'Half an ans' });
    await settle(el);

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    // The Engine ends the turn as stopped: that terminal, not the press, is what halts it.
    stream.emit('error', { error: 'Stopped by the user', errorCode: 'CANCELLED' });
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const turn = turnsOf(main)[0] as HTMLElement;
    expect(turn.dataset.status).toBe('halted');
    // What arrived was really received — a halt does not erase it.
    expect(answerTextIn(turn)).toBe('Half an ans');
    expect(textIn(turn, 'sv3-turn-note')).toBe(TURN_HALTED);
    // The slot is back to Send, so the next question is sendable.
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-send')).not.toBeNull();
  });
});

describe('Stop is told to the Engine, so the saved turn says what the live one did (S2.3)', () => {
  const stops = (): unknown[][] =>
    fetchMock.mock.calls.filter((call) =>
      /\/api\/chat\/conversations\/[^/]+\/stop$/.test(String(call[0])),
    );
  const sent = (call: unknown[] | undefined): Record<string, unknown> =>
    JSON.parse(String((call?.[1] as RequestInit | undefined)?.body ?? '{}')) as Record<string, unknown>;

  it('asks the Engine to stop the very turn it dispatched, and halts it on screen', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'and who owns it?');
    stream.emit('rag.rewrite', { standalone: 'Who owns Project Cobalt?' });
    await settle(el);

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    stream.emit('error', { error: 'Stopped by the user', errorCode: 'CANCELLED' });
    stream.end();
    await settle(el);

    const dispatched = sent(dispatches()[0]);
    expect(typeof dispatched.turnId).toBe('string');
    expect(dispatched.turnId).not.toBe('');
    expect(stops()).toHaveLength(1);
    const stop = stops()[0];
    expect(String(stop?.[0])).toBe(
      `http://127.0.0.1:9999/api/chat/conversations/${encodeURIComponent(String(dispatched.conversationId))}/stop`,
    );
    expect((stop?.[1] as RequestInit).method).toBe('POST');
    expect(sent(stop)).toEqual({ turnId: dispatched.turnId });
    const turn = turnsOf(await region(el, 'jf-sv3-main'))[0] as HTMLElement;
    expect(turn.dataset.status).toBe('halted');
    expect(textIn(turn, 'sv3-turn-note')).toBe(TURN_HALTED);
  });

  it('says "Stopping…" until the Engine ends the turn, and keeps every word it sent before that', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'and who owns it?');
    stream.emit('chunk', { text: 'Half' });
    await settle(el);

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    let turn = turnsOf(main)[0] as HTMLElement;
    // Asked, not yet done: nothing says "stopped" before the Engine has saved it so.
    expect(turn.dataset.status).toBe('streaming');
    expect(textIn(turn, 'sv3-turn-stopping')).toBe(TURN_STOPPING);
    expect(turn.querySelector('[data-testid="sv3-turn-stopping"]')?.getAttribute('role')).toBe('status');

    // What the Engine sent before its Stop landed is what it saves, so the reader sees it too.
    stream.emit('chunk', { text: ' an ans' });
    stream.emit('rag.rewrite', { standalone: 'Who owns Project Cobalt?' });
    stream.emit('error', { error: 'Stopped by the user', errorCode: 'CANCELLED' });
    stream.end();
    await settle(el);

    turn = turnsOf(main)[0] as HTMLElement;
    expect(turn.dataset.status).toBe('halted');
    expect(answerTextIn(turn)).toBe('Half an ans');
    expect(textIn(turn, 'sv3-turn-note')).toBe(TURN_HALTED);
    expect(el.sessions.sessions[0]?.turns[0]?.standaloneQuestion).toBe('Who owns Project Cobalt?');
    expect(turn.querySelector('[data-testid="sv3-turn-stopping"]')).toBeNull();
  });

  it('shows the finished answer, not "Stopped by you.", when the Stop came after it was saved', async () => {
    aiOnline();
    const stream = stubStream({ status: 200, body: { stopped: false, pending: false } });
    const el = await mount();
    await ask(el, 'what changed?');
    stream.emit('chunk', { text: 'Every' });
    await settle(el);

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    stream.emit('chunk', { text: 'thing.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const turn = turnsOf(await region(el, 'jf-sv3-main'))[0] as HTMLElement;
    expect(turn.dataset.status).toBe('complete');
    expect(answerTextIn(turn)).toBe('Everything.');
    expect(textIn(turn, 'sv3-turn-note')).not.toBe(TURN_HALTED);
  });

  it.each<[string, StopReply]>([
    ['refused', { status: 500, body: { errorCode: 'INTERNAL' } }],
    ['unanswered', 'unreachable'],
  ])('says so when the Stop was %s, and lets the answer go on', async (_, reply) => {
    aiOnline();
    const stream = stubStream(reply);
    const el = await mount();
    await ask(el, 'what changed?');
    stream.emit('chunk', { text: 'Every' });
    await settle(el);

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    let turn = turnsOf(main)[0] as HTMLElement;
    expect(turn.dataset.status).toBe('streaming');
    expect(textIn(turn, 'sv3-turn-stopping')).toBe(TURN_STOP_FAILED);
    // Stop is still there to press again.
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-stop')).not.toBeNull();

    stream.emit('chunk', { text: 'thing.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    turn = turnsOf(main)[0] as HTMLElement;
    expect(turn.dataset.status).toBe('complete');
    expect(answerTextIn(turn)).toBe('Everything.');
  });

  it('still sends a question stopped while it waits for Engine capacity, so the Engine saves it stopped', async () => {
    aiOnline();
    const stream = stubStream();
    const streamed = fetchMock.getMockImplementation() as ((url: unknown, init: unknown) => unknown) | undefined;
    let refused = false;
    // The first dispatch is refused for capacity; the window waits, then sends it again.
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => {
      if (String(url).includes('/api/chat/dispatch') && !refused) {
        refused = true;
        return new Response(
          JSON.stringify({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }),
          { status: 429, headers: { 'Retry-After': '0' } },
        );
      }
      return streamed?.(url, init);
    });
    const el = await mount();
    await ask(el, 'what changed?');
    expect(dispatches()).toHaveLength(1);

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 150));
    await settle(el);

    // The question went out after all, naming the turn the Stop named.
    expect(dispatches()).toHaveLength(2);
    expect(stops().map(sent)).toEqual([{ turnId: sent(dispatches()[1]).turnId }]);
    stream.emit('error', { error: 'Stopped by the user', errorCode: 'CANCELLED' });
    stream.end();
    await settle(el);
    const turn = turnsOf(await region(el, 'jf-sv3-main'))[0] as HTMLElement;
    expect(turn.dataset.status).toBe('halted');
  });

  it('reads the saved record after a stopped turn, as after a finished one', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'what changed?');
    stream.emit('chunk', { text: 'Every' });
    await settle(el);
    const records = (): number =>
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/thread/')).length;
    const before = records();

    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);
    stream.emit('error', { error: 'Stopped by the user', errorCode: 'CANCELLED' });
    stream.end();
    await settle(el);

    expect(records()).toBe(before + 1);
  });

  it('names each turn apart, so a Stop can only reach the turn it was pressed on', async () => {
    aiOnline();
    const first = stubStream();
    const el = await mount();
    await ask(el, 'first');
    first.emit('done', {});
    first.end();
    await settle(el);
    // The second answer streams and is stopped; the stream's own frames do not matter here.
    stubStream();
    await ask(el, 'second');
    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement).click();
    await settle(el);

    const [firstSent, secondSent] = dispatches().map(sent);
    expect(firstSent?.turnId).not.toBe(secondSent?.turnId);
    expect(stops().map(sent)).toEqual([{ turnId: secondSent?.turnId }]);
  });

  it('sends no stop for an answer that finished, nor when the window goes away mid-answer', async () => {
    aiOnline();
    let stream = stubStream();
    const el = await mount();
    await ask(el, 'what changed?');
    stream.emit('chunk', { text: 'Everything.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    expect(stops()).toHaveLength(0);

    // Leaving is not stopping: the Engine keeps the turn running and saves its answer.
    stream = stubStream();
    await ask(el, 'and then?');
    stream.emit('chunk', { text: 'Half' });
    await settle(el);
    el.remove();
    await settle(el);
    expect(stops()).toHaveLength(0);
    stream.end();
    await settle(el);
  });
});

describe('every terminal is distinct, and says only what happened', () => {
  it('words the session lock through the shared vocabulary, not a local phrase', async () => {
    aiOnline();
    stubStatus(423);
    const el = await mount();
    await ask(el, 'what changed?');
    // The TURN still reaches its own distinct terminal, worded by the one vocabulary — asserted on
    // the model because Phase F7 (inventory E4) makes the locked store's transcript unreadable, so
    // the refusal is now said in the locked view rather than under a turn nobody may read.
    const turn = el.sessions.sessions[0]?.turns[0];
    expect(turn?.status).toBe('refused');
    expect(turn?.detail).toBe(reasonFor('conversations.locked').wording);
    const main = await region(el, 'jf-sv3-main');
    expect(turnsOf(main)).toHaveLength(0);
    expect(q(main, 'sv3-history-locked')).not.toBeNull();
  });

  it('reports a failure with the stream\'s own words, and never as a halt', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'what changed?');
    stream.emit('error', { error: 'model unloaded mid-answer' });
    stream.end();
    await settle(el);

    const turn = turnsOf(await region(el, 'jf-sv3-main'))[0] as HTMLElement;
    expect(turn.dataset.status).toBe('failed');
    expect(textIn(turn, 'sv3-turn-note')).toContain('model unloaded mid-answer');
    expect(textIn(turn, 'sv3-turn-note')).not.toBe(TURN_HALTED);
  });

  it('says an empty completed response is empty rather than leaving a blank slot', async () => {
    aiOnline();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'anything?');
    stream.emit('done', {});
    stream.end();
    await settle(el);
    const turn = turnsOf(await region(el, 'jf-sv3-main'))[0] as HTMLElement;
    expect(turn.dataset.status).toBe('complete');
    expect(turn.querySelector('[data-testid="sv3-turn-answer-empty"]')).not.toBeNull();
  });
});

describe('an unreachable model refuses the send and keeps the draft', () => {
  it('shows the availability authority\'s reason and dispatches nothing', async () => {
    // No `aiOnline()`: the store has reported nothing, which is the honest "cannot answer" state
    // a backend-less window is really in.
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const notice = q(composer, 'sv3-composer-notice');
    expect(notice?.textContent?.trim()).not.toBe('');

    const draft = 'what changed in the renewal?';
    await type(el, draft);
    (q(composer, 'sv3-composer-send') as HTMLButtonElement).click();
    await settle(el);

    expect(dispatches()).toHaveLength(0);
    // The draft is the reader's and nothing is holding it — back, character for character.
    expect((await fieldOf(el)).value).toBe(draft);
    // Still the empty window: a refused send is not a session.
    expect(el.getAttribute('composer-state')).toBe('hero');
    expect(turnsOf(await region(el, 'jf-sv3-main'))).toHaveLength(0);
    // Soft, not disabled: the control stays focusable so its reason stays reachable.
    const send = q(composer, 'sv3-composer-send') as HTMLButtonElement;
    expect(send.disabled).toBe(false);
    expect(send.dataset.unavailable).toBe('true');
    expect(send.getAttribute('aria-describedby')).toBe(notice?.id);
  });

  it('drops the notice once the model is available', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-notice')).not.toBeNull();
    aiOnline();
    await settle(el);
    await composer.updateComplete;
    expect(q(composer, 'sv3-composer-notice')).toBeNull();
  });
});
