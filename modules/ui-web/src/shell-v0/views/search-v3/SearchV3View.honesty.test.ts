// @vitest-environment happy-dom

/**
 * The Search v3 window's honesty pack (tempdoc 822 Phase F7) — the inventory rows that make the
 * window say true things about itself: the lock (E4/E5), the answer frame (C1), the citation marks
 * and their preview (C4/C3), the rewrite note (C8), reasoning (C9), friendly failures (E9), the
 * corpus remedy (E10), copy (A9), export (A10) and auto-titling (A11).
 *
 * Everything reaches through real seams: the SSE body is a real stream the case drives frame by
 * frame, `claimsToCitations` is the real resolver, and the answer frame's wording is compared against
 * the SHARED authority's own output rather than a literal. Where a case could pass for the wrong
 * reason, the wrong reason is asserted absent — the locked cases assert the transcript is GONE, not
 * merely that a notice appeared, and the frame cases assert the number is the one the window
 * measured rather than any number at all.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
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
import { __resetUiModeForTest, setUiMode } from '../../state/uiModeState.js';
import { answerFrameLabel } from '../../components/chat/evidenceProjection.js';
import { claimsToCitations } from '../../components/chat/citationResolve.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../../controllers/navigateRequest.js';
import { applySv3Record } from './sv3-sessions.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import {
  CORPUS_ADD_FOLDERS,
  CORPUS_REMEDY_TARGET,
  HISTORY_LOCKED_REFUSED,
  REWRITE_NOTE_LABEL,
  TURN_COPY_DONE,
  TURN_COPY_LABEL,
} from './fixtures.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;
let clipboard: ReturnType<typeof vi.fn>;

/** A status snapshot the store will stamp a settled index from — the shape `stampSettledIndex` reads. */
function status(over: {
  documents?: number;
  searchable?: number | null;
  lock?: 'locked' | 'unlocked';
}): StatusSnapshot {
  return {
    worker: {
      core: {
        indexedDocuments: over.documents ?? 42,
        ...(over.searchable === undefined ? {} : { searchableDocuments: over.searchable }),
      },
    },
    ...(over.lock === undefined ? {} : { conversationProtection: { state: over.lock } }),
  } as unknown as StatusSnapshot;
}

/** The observed state in which the ask tier is genuinely available. */
function feed(over: Parameters<typeof status>[0] & { model?: string } = {}): void {
  __feedForTest({
    inference: {
      mode: 'online',
      available: true,
      activeModelId: over.model ?? 'Qwen_Qwen3.5-9B.Q4_K_M.gguf',
    } as never,
    status: status(over),
  });
  __feedContactForTest();
}

interface FakeStream {
  emit(event: string, data: unknown): void;
  end(): void;
}

/** The dispatch's SSE body, driven frame by frame; the reader rejects once the signal aborts. */
function stubStream(): FakeStream {
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
    // The auto-title POST (A11) is a different request against the same endpoint; it must not eat
    // the frames this case is feeding the answer stream.
    if (!String(url).includes('/api/chat/dispatch')) {
      return { ok: true, status: 200, body: null, json: async () => ({}) };
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

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, body: null, json: async () => ({}) });
  vi.stubGlobal('fetch', fetchMock);
  clipboard = vi.fn(async () => {});
  vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: clipboard } });
  __resetAiStateForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  __resetUiModeForTest();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

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

const all = (host: Mounted, testid: string): HTMLElement[] => [
  ...(host.shadowRoot?.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`) ?? []),
];

/**
 * Press a control the way a reader does — through the native button inside it. The two REMEDIES this
 * suite presses are born on the shared operability primitive (852 S4's jf-control adoption) and
 * render their button in their own shadow root, so a click on the host reaches no handler; a
 * hand-rolled native button is clicked directly.
 */
async function press(host: Element | null | undefined): Promise<void> {
  if (!host) throw new Error('press: no control');
  if (host.localName === 'button') {
    (host as HTMLButtonElement).click();
    return;
  }
  await (host as Mounted).updateComplete;
  const button = host.shadowRoot?.querySelector('button');
  if (!button) throw new Error('press: the control rendered no button');
  button.click();
}

const textOf = (el: HTMLElement | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

async function type(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
}

async function ask(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  const composer = await region(el, 'jf-sv3-composer');
  (q(composer, 'sv3-composer-send') as HTMLButtonElement | null)?.click();
  await settle(el);
}

/** The reader names a row — the sidebar's own commit phase, the way the panel raises it. */
async function rename(el: Mounted, id: string, title: string): Promise<void> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  sidebar.dispatchEvent(
    new CustomEvent('sv3-session-rename', {
      detail: { id, phase: 'commit', title },
      bubbles: true,
      composed: true,
    }),
  );
  await settle(el);
}

/** One retrieval source in the shape the backend mints. */
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

/** Ask, stream one grounded answer with a per-sentence match, and settle. */
async function askGrounded(el: Mounted, stream: FakeStream, answer = 'The lock held.'): Promise<void> {
  await ask(el, 'why did the renewal fail?');
  stream.emit('rag.citations', { citations: [source(0)] });
  stream.emit('chunk', { text: answer });
  stream.emit('rag.citation_matches', {
    matches: [{ sentenceIndex: 0, sentenceText: answer, similarity: 0.9, sourceIndex: 0 }],
  });
  stream.emit('done', {});
  stream.end();
  await settle(el);
}

/* ── E5 + E4: a lock taken elsewhere, and a transcript that stops being readable ─────────────── */

describe('a lock taken ELSEWHERE reaches this window, and locks the transcript', () => {
  it('picks the lock up from the poll and makes the transcript unreadable', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    const main = await region(el, 'jf-sv3-main');
    // Precondition: the answer really is on screen, so the assertion below is about the LOCK.
    expect(all(main, 'sv3-turn')).toHaveLength(1);

    // Nothing this window did — an idle auto-lock, another tab, the Security surface.
    feed({ lock: 'locked' });
    await settle(el);
    await main.updateComplete;

    // NOT stale-readable: the transcript is gone, not merely captioned (tempdoc 734's own defect).
    expect(all(main, 'sv3-turn')).toHaveLength(0);
    expect(q(main, 'sv3-transcript')).toBeNull();
    expect(main.shadowRoot?.textContent).not.toContain('The lock held.');
    // ...and locked never looks deleted (tempdoc 629 §L4): the state is named, in the one vocabulary.
    const locked = q(main, 'sv3-history-locked');
    expect(locked).not.toBeNull();
    expect(locked?.getAttribute('heading')).toBe(reasonFor('conversations.locked').wording);
  });

  it('gives the reader the cause\'s own remedy, and navigates through the one seam', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    // The existing transcript predates the external lock; a known locked store takes no new Ask.
    feed({ lock: 'locked' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    const navigations: string[] = [];
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, (e) =>
      navigations.push((e as CustomEvent<{ surfaceId: string }>).detail.surfaceId),
    );

    const remedy = q(main, 'sv3-history-locked-remedy') as HTMLButtonElement | null;
    const nav = reasonFor('conversations.locked').remedy;
    expect(nav?.kind).toBe('navigate');
    expect(textOf(remedy)).toContain(nav?.kind === 'navigate' ? nav.label : '');
    await press(remedy);
    // The target is the cause's declared one — the surface that OWNS the unlock, not one hop short.
    expect(navigations).toEqual([nav?.kind === 'navigate' ? nav.target : '']);
  });

  it('gives the transcript back when the lock lifts, and drops the refusal with it', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    feed({ lock: 'locked' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-history-locked')).not.toBeNull();

    feed({ lock: 'unlocked' });
    await settle(el);
    await main.updateComplete;
    expect(q(main, 'sv3-history-locked')).toBeNull();
    expect(all(main, 'sv3-turn')).toHaveLength(1);
  });

  it('does not unlock itself on a snapshot that never mentions the lock', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    feed({ lock: 'locked' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-history-locked')).not.toBeNull();

    // A perfectly ordinary status frame with no protection field at all.
    feed();
    await settle(el);
    await main.updateComplete;
    expect(q(main, 'sv3-history-locked')).not.toBeNull();
  });

  it('says what became of a send the lock refused, without claiming to have kept the text', async () => {
    feed();
    const el = await mount();
    fetchMock.mockImplementation(async (url: unknown) =>
      String(url).includes('/api/chat/dispatch')
        ? { ok: false, status: 423, text: async () => '', body: null }
        : { ok: true, status: 200, body: null, json: async () => ({}) },
    );
    await ask(el, 'what changed?');
    const main = await region(el, 'jf-sv3-main');
    // The 423 is newer than any poll, so the window adopts the lock without waiting for one.
    expect(q(main, 'sv3-history-locked')).not.toBeNull();
    expect(textOf(q(main, 'sv3-history-locked-refusal'))).toBe(HISTORY_LOCKED_REFUSED);

    // The refusal describes a lock; when the lock is gone, so is the refusal.
    feed({ lock: 'unlocked' });
    await settle(el);
    await main.updateComplete;
    expect(q(main, 'sv3-history-locked-refusal')).toBeNull();
  });
});

/* ── C1: the honest answer frame ─────────────────────────────────────────────────────────────── */

describe('a settled answer carries its basis, its duration and its model', () => {
  it('derives the whole line — the wording from the shared authority, the model from the snapshot', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    // Sources arrive but no sentence is matched, so the SETTLED frame is `sourced` and not "grounded".
    await ask(el, 'why did the renewal fail?');
    stream.emit('rag.citations', { citations: [source(0)] });
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    // The accessible half carries the authority's WHOLE label; the resting half carries the verdict.
    const line = textOf(q(main, 'sv3-answer-frame'));
    expect(line).toContain(answerFrameLabel('sourced', false));
    // The model is the one the OBSERVED-STATE authority reported, not a literal in this window...
    const model = el.aiSnapshot?.runtime.modelLabel;
    expect(model === null || model === undefined ? '' : model).not.toBe('');
    // ...and it is NOT repeated in the tail, because the composer is already naming that same model
    // (Phase F11). The mutation probe for the suppression: remove the equality test and this fails.
    expect(line).not.toContain(String(model));
    expect(textOf(q(await region(el, 'jf-sv3-composer'), 'sv3-composer-model'))).toBe(String(model));
    // The duration is MEASURED — a real elapsed value on the turn, not a rendered placeholder.
    const turn = el.sessions.sessions[0]?.turns[0];
    expect(typeof turn?.durationMs).toBe('number');
    expect(line).toMatch(/\d+(\.\d)? (s|ms)/);
  });

  it('re-states the model in the tail when it is NOT the one the composer names', async () => {
    // Inventory E3 — a model id is a Detailed-mode fact, so the re-statement this case is about can
    // only be observed there. The Simple direction has its own case in `SearchV3View.degradation.test.ts`.
    setUiMode('advanced');
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('rag.citations', { citations: [source(0)] });
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    const stamped = el.sessions.sessions[0]?.turns[0]?.modelLabel;
    expect(stamped).not.toBeNull();

    // The model is swapped AFTER the answer landed. The composer now names the new one, so the turn
    // must re-state the one that actually wrote it — the stale-attribution defect the per-turn stamp
    // exists to prevent, which moving the name into the composer would otherwise re-open.
    feed({ model: 'Meta_Llama-4-Scout.Q4_K_M.gguf' });
    await settle(el);
    const current = el.aiSnapshot?.runtime.modelLabel ?? null;
    expect(current).not.toBe(stamped);
    const main = await region(el, 'jf-sv3-main');
    await main.updateComplete;
    expect(textOf(q(main, 'sv3-answer-frame'))).toContain(String(stamped));
    const composer = await region(el, 'jf-sv3-composer');
    expect(textOf(q(composer, 'sv3-composer-model'))).toBe(String(current));
  });

  it('says nothing about grounding when the answer IS grounded — the marks already do', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    const main = await region(el, 'jf-sv3-main');
    const line = textOf(q(main, 'sv3-answer-frame'));
    expect(line).not.toContain(answerFrameLabel('sourced', false));
    expect(line).not.toContain(answerFrameLabel('ungrounded', true));
    // The receipt half still renders: it is the part that is always measured.
    expect(line).toMatch(/\d+(\.\d)? (s|ms)/);
  });

  it('frames nothing while the answer is still arriving', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'Partial…' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-answer-frame')).toBeNull();
  });
});

/* ── C4 + C3: the shared resolver's marks, and their preview ─────────────────────────────────── */

describe('the inline marks are the SHARED resolver\'s output, and they preview on hover', () => {
  it('hands the markdown block exactly what claimsToCitations resolved — nothing ad hoc', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);

    const evidence = el.sessions.sessions[0]?.turns[0]?.evidence;
    expect(evidence).not.toBeNull();
    const resolved = claimsToCitations(
      [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          // 822 §3d — the fixture mirrors what the window accumulated from `rag.citation_matches`
          // (cross-encoder verified); a lexical-only claim would resolve to no mark at all.
          verifiedScore: 0.9,
          lexicalScore: 0,
          verifiedRefs: [0],
          lexicalRefs: [],
        },
      ],
      evidence?.sources ?? [],
    );
    // The stored marks ARE the resolver's answer for the same claims + sources. A window that wove
    // its own `[n]` would differ here even when it happened to number them the same way.
    expect(evidence?.marks).toEqual(resolved);
    expect(resolved.length).toBeGreaterThan(0);

    const main = await region(el, 'jf-sv3-main');
    const block = q(main, 'sv3-turn-markdown') as (HTMLElement & { citations: unknown }) | null;
    expect(block?.citations).toEqual(evidence?.marks);
  });

  it('shows the product\'s ONE hover card, from the mark\'s own source', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    const main = await region(el, 'jf-sv3-main');
    const card = q(main, 'sv3-citation-hover') as (Mounted & { visible: boolean; data: unknown }) | null;
    expect(card).not.toBeNull();
    expect(card?.visible).toBe(false);

    const rect = { left: 12, bottom: 40 } as DOMRect;
    const hover = { excerpt: 'excerpt 0', parentDocId: 'f:/docs/note-0.md', score: 0.9, headingText: 'Notes' };
    q(main, 'sv3-turn-markdown')?.dispatchEvent(
      new CustomEvent('cite-ref-hover', {
        detail: { rect, source: hover },
        bubbles: true,
        composed: true,
      }),
    );
    await card?.updateComplete;
    expect(card?.visible).toBe(true);
    expect(card?.data).toEqual(hover);

    q(main, 'sv3-turn-markdown')?.dispatchEvent(
      new CustomEvent('cite-ref-leave', { bubbles: true, composed: true }),
    );
    await card?.updateComplete;
    expect(card?.visible).toBe(false);
  });

  it('shows nothing for an event carrying no resolved source', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    const main = await region(el, 'jf-sv3-main');
    const card = q(main, 'sv3-citation-hover') as (Mounted & { visible: boolean }) | null;
    q(main, 'sv3-turn-markdown')?.dispatchEvent(
      new CustomEvent('cite-ref-hover', { detail: {}, bubbles: true, composed: true }),
    );
    await card?.updateComplete;
    expect(card?.visible).toBe(false);
  });

  /* Tempdoc 822 §3d — THE PROVENANCE GATE, asserted at this window's own accumulator (the shipped
     window's copy is asserted in `UnifiedChatView.test.ts`: one defect, two accumulators). */

  it('mints NO mark from a lexical citation_delta, whatever its word-overlap score says', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('rag.citations', { citations: [source(0)] });
    stream.emit('chunk', { text: 'The lock held.' });
    // 1.0 overlap — every significant word of a short passage present. On the cross-encoder scale
    // this would be the STRONGEST tier; it is a coverage ratio, so it earns no tier at all.
    stream.emit('rag.citation_delta', {
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      citations: [{ sourceIndex: 0, score: 1 }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const evidence = el.sessions.sessions[0]?.turns[0]?.evidence;
    expect(evidence?.marks).toEqual([]);
    const main = await region(el, 'jf-sv3-main');
    const block = q(main, 'sv3-turn-markdown') as (HTMLElement & { citations: unknown }) | null;
    expect(block?.citations).toEqual([]);
  });

  it('keeps the verified score when BOTH producers scored one sentence (no cross-scale max)', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('rag.citations', { citations: [source(0)] });
    stream.emit('chunk', { text: 'The lock held.' });
    // The delta arrives FIRST (it streams) and scores higher on its own scale…
    stream.emit('rag.citation_delta', {
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      citations: [{ sourceIndex: 0, score: 0.95 }],
    });
    // …then the authoritative cross-encoder lands at 0.52 — a WEAK sentence.
    stream.emit('rag.citation_matches', {
      matches: [{ sentenceIndex: 0, sentenceText: 'The lock held.', similarity: 0.52, sourceIndex: 0 }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const marks = el.sessions.sessions[0]?.turns[0]?.evidence?.marks ?? [];
    expect(marks).toHaveLength(1);
    // The old `Math.max` across the two scales would read 0.95 here and render 'grounded'.
    expect(marks[0]?.similarity).toBe(0.52);
  });

  /* Tempdoc 822 §3b — THE NUMBERING CONTRACT at this window's accumulator. The same two assertions
     run against `UnifiedChatView`'s merge: one defect, two accumulators. */

  it('a doubly-matched sentence resolves through the VERIFIED ref, not the delta that arrived first', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('rag.citations', { citations: [source(0), source(1), source(2)] });
    stream.emit('chunk', { text: 'The lock held.' });
    // The delta streams first and guesses source 0 …
    stream.emit('rag.citation_delta', {
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      citations: [{ sourceIndex: 0, score: 0.9 }],
    });
    // … the authoritative matcher ties it to source 2.
    stream.emit('rag.citation_matches', {
      matches: [{ sentenceIndex: 0, sentenceText: 'The lock held.', similarity: 0.8, sourceIndex: 2 }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const marks = el.sessions.sessions[0]?.turns[0]?.evidence?.marks ?? [];
    expect(marks).toHaveLength(1);
    // A single merged ref set put the delta's guess first, so the mark read [1] and deep-linked to
    // the wrong passage.
    expect(marks[0]?.label).toBe(3);
    expect(marks[0]?.detail.parentDocId).toBe('f:/docs/note-2.md');
  });

  it('an out-of-range index mints NO mark — no wrong-target link is constructible', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('rag.citations', { citations: [0, 1, 2, 3, 4].map((i) => source(i)) });
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('rag.citation_matches', {
      matches: [{ sentenceIndex: 0, sentenceText: 'The lock held.', similarity: 0.9, sourceIndex: 59 }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    // BEFORE: a mark labelled 60 pointing at source 0 (the removed `sources[refIdx] ?? sources[0]`).
    expect(el.sessions.sessions[0]?.turns[0]?.evidence?.marks).toEqual([]);
    const main = await region(el, 'jf-sv3-main');
    const block = q(main, 'sv3-turn-markdown') as (HTMLElement & { citations: unknown }) | null;
    expect(block?.citations).toEqual([]);
  });
});

/* ── C8: the standalone question ─────────────────────────────────────────────────────────────── */

describe('the question retrieval actually ran on is shown back', () => {
  it('renders the rewrite as a transparency note, and keeps it past the stream', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'and the second one?');
    stream.emit('rag.rewrite', {
      original: 'and the second one?',
      standalone: 'what caused the second renewal failure?',
    });
    stream.emit('chunk', { text: 'It expired.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const note = textOf(q(main, 'sv3-turn-rewrite'));
    expect(note).toBe(`${REWRITE_NOTE_LABEL} what caused the second renewal failure?`);
    // Pinned onto the TURN, so it survives the stream that produced it.
    expect(el.sessions.sessions[0]?.turns[0]?.standaloneQuestion).toBe(
      'what caused the second renewal failure?',
    );
  });

  it('shows no note when the backend rewrote nothing', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-turn-rewrite')).toBeNull();
  });
});

/* ── C9: reasoning as its own controlled block ───────────────────────────────────────────────── */

describe('the model\'s thinking is its own block, never mixed into the answer', () => {
  it('renders the shared block while thinking and keeps it on the settled turn', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('reasoning_chunk', { text: 'checking the renewal log' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-reasoning')).not.toBeNull();
    // It is a BLOCK, not answer text: the answer must not have absorbed it.
    expect(textOf(q(main, 'sv3-turn-answer'))).not.toContain('checking the renewal log');

    stream.emit('chunk', { text: 'It expired.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    await main.updateComplete;
    expect(el.sessions.sessions[0]?.turns[0]?.reasoning).toHaveLength(1);
    expect(el.sessions.sessions[0]?.turns[0]?.reasoning[0]?.text).toContain('checking the renewal log');
    expect(q(main, 'sv3-turn-reasoning')).not.toBeNull();
  });

  it('a record refresh never blanks the thinking the window observed (tempdoc 848)', async () => {
    // The record is authoritative for what it HOLDS, never for what has not reached it. A refresh
    // that arrived before the turn's reasoning was persisted (or from a record that carries none)
    // must keep the live blocks — the same rule `evidence` has had since Phase F7.
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('reasoning_chunk', { text: 'checking the renewal log' });
    stream.emit('chunk', { text: 'It expired.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const sessionId = el.sessions.sessions[0]!.id;
    const turnId = el.sessions.sessions[0]!.turns[0]!.id;
    // The REAL projection over a record that carries no reasoning attribute — not a hand-built turn.
    const recorded = projectSv3RecordTurns([
      {
        id: turnId, occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
        originator: 'user', content: 'why did the renewal fail?', attributes: {},
      },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE',
        originator: 'agent', content: 'It expired.', attributes: {},
      },
    ]);
    el.sessions = applySv3Record(el.sessions, sessionId, recorded);
    await settle(el);

    expect(el.sessions.sessions[0]?.turns[0]?.reasoning).toHaveLength(1);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-turn-reasoning')).not.toBeNull();
  });

  it('renders no block at all for a turn the model did not think out loud in', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-turn-reasoning')).toBeNull();
  });
});

/* ── E9: a failure in the reader's words; a halt is not one ──────────────────────────────────── */

describe('a stream failure is worded by the shared mapping, and an abort is not a failure', () => {
  it('maps an interrupted stream to the shared sentence', async () => {
    feed();
    const el = await mount();
    const failure = Object.assign(new Error('stream ended before done'), {
      code: 'STREAM_INCOMPLETE',
    });
    fetchMock.mockImplementation(async (url: unknown) => {
      if (!String(url).includes('/api/chat/dispatch')) {
        return { ok: true, status: 200, body: null, json: async () => ({}) };
      }
      throw failure;
    });
    await ask(el, 'what changed?');
    const turn = el.sessions.sessions[0]?.turns[0];
    expect(turn?.status).toBe('failed');
    // The shared vocabulary, not the raw technical message the throw carried.
    expect(turn?.detail).toBe('Connection lost — the response was interrupted.');
    expect(turn?.detail).not.toContain('stream ended before done');
  });

  it('never routes the reader\'s own Stop through that mapping', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'Half an ans' });
    await settle(el);
    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement | null)?.click();
    await settle(el);
    // The Engine ends the turn it was told to stop with CANCELLED, its own word for a Stop.
    stream.emit('error', { error: 'Stopped by the user', errorCode: 'CANCELLED' });
    stream.end();
    await settle(el);

    const turn = el.sessions.sessions[0]?.turns[0];
    expect(turn?.status).toBe('halted');
    expect(turn?.detail).toBe('');
    // What arrived is kept, and nothing about it reads as a failure.
    expect(turn?.answer).toBe('Half an ans');
  });
});

/* ── E10: a corpus of zero offers the fix ────────────────────────────────────────────────────── */

describe('the landing says what there is to search, or offers the way to get some', () => {
  it('states the default-scope population when there is one', async () => {
    feed({ documents: 900, searchable: 12 });
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const line = q(composer, 'sv3-composer-corpus');
    expect(line?.dataset.kind).toBe('documents');
    expect(textOf(line)).toContain('12');
    expect(q(composer, 'sv3-composer-corpus-remedy')).toBeNull();
  });

  it('offers the remedy on a REPORTED zero, and navigates to the surface that owns it', async () => {
    feed({ documents: 0, searchable: 0 });
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const remedy = q(composer, 'sv3-composer-corpus-remedy') as HTMLButtonElement | null;
    expect(textOf(remedy)).toBe(CORPUS_ADD_FOLDERS);
    const navigations: string[] = [];
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, (e) =>
      navigations.push((e as CustomEvent<{ surfaceId: string }>).detail.surfaceId),
    );
    await press(remedy);
    expect(navigations).toEqual([CORPUS_REMEDY_TARGET]);
  });

  it('claims nothing before a settled poll — the case the shipped landing gets wrong', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    // Neither "Searching 0 files" nor the remedy: the window has not been told, so it says nothing.
    expect(q(composer, 'sv3-composer-corpus')).toBeNull();
  });

  it('spends no chrome on the corpus once the window is working', async () => {
    feed({ documents: 900, searchable: 12 });
    const stream = stubStream();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    // Present on the landing…
    expect(q(composer, 'sv3-composer-corpus')).not.toBeNull();
    await askGrounded(el, stream);
    await composer.updateComplete;
    // …and gone once the conversation owns the region. The fact belongs to the empty window.
    expect(composer.getAttribute('state')).toBe('docked');
    expect(q(composer, 'sv3-composer-corpus')).toBeNull();
  });
});

/* ── A9 + A10: copy an answer, export a conversation ─────────────────────────────────────────── */

describe('an answer can be copied and a conversation exported, through the shared utilities', () => {
  it('copies the settled answer and confirms it', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream, 'The renewal lock held past its window.');
    const main = await region(el, 'jf-sv3-main');
    const copy = q(main, 'sv3-turn-copy') as HTMLButtonElement | null;
    expect(copy).not.toBeNull();
    // Icon-only since Phase F11, so the NAME is the action and never becomes the confirmation —
    // a control renamed to "Copied" would be reporting the act by renaming itself.
    expect(copy?.getAttribute('aria-label')).toBe(TURN_COPY_LABEL);
    expect(textOf(q(main, 'sv3-turn-copy-status'))).toBe('');
    copy?.click();
    await settle(el);
    await main.updateComplete;

    expect(clipboard).toHaveBeenCalledWith('The renewal lock held past its window.');
    expect(copy?.getAttribute('aria-label')).toBe(TURN_COPY_LABEL);
    expect(textOf(q(main, 'sv3-turn-copy-status'))).toBe(TURN_COPY_DONE);
  });

  it('offers no copy for a turn with no settled answer to copy', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'Half an ans' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-copy')).toBeNull();
  });

  it('replaces the Markdown-only palette action with the conversation export control', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream, 'It expired.');
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot!.querySelector('jf-sv3-session-row')!;
    await row.updateComplete;
    expect(row.shadowRoot!.querySelector('[data-testid="sv3-session-row-export"]')).not.toBeNull();
    const palette = await region(el, 'jf-sv3-palette');
    expect(palette.shadowRoot!.textContent).not.toContain('Copy this conversation as Markdown');
    expect(clipboard).not.toHaveBeenCalled();
  });

  it('offers no conversation export trigger without a claimed conversation', async () => {
    feed();
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(sidebar.shadowRoot!.querySelector('jf-sv3-session-row')).toBeNull();
    expect(clipboard).not.toHaveBeenCalled();
  });
});

/* ── A11: the model names the conversation, unless the reader already did ────────────────────── */

/**
 * The auto-title request the conversation store makes: the conversation's own `auto-title` route,
 * which names it from its stored first exchange and never counts as a person acting.
 */
const titleCalls = (): unknown[][] =>
  fetchMock.mock.calls.filter((call) => String(call[0]).endsWith('/auto-title'));

/**
 * Tempdoc 838 — every name written THROUGH to the conversation's own record, in order, for one
 * conversation. This is where a title lives now; the browser-local map it replaced is gone.
 */
const titleWrites = (id: string): Array<{ title: string; source: string }> =>
  fetchMock.mock.calls
    .filter((call) => String(call[0]).endsWith(`/conversations/${encodeURIComponent(id)}/title`))
    .map((call) => JSON.parse(String((call[1] as { body: string }).body)));

describe('a conversation gets a model-generated name, and a rename beats it', () => {
  it('asks the store\'s naming authority once the first answer has landed', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);

    expect(titleCalls()).toHaveLength(1);
    // The conversation that was answered is named, and nothing else rides along: the server builds
    // the prompt from that conversation's stored first exchange (ChatControllerAutoTitleTest).
    const id = el.sessions.sessions[0]?.id ?? '';
    expect(String(titleCalls()[0]?.[0])).toBe(
      `http://127.0.0.1:9999/api/chat/conversations/${encodeURIComponent(id)}/auto-title`,
    );
    const init = titleCalls()[0]?.[1] as { method?: string; body?: unknown } | undefined;
    expect(init?.method).toBe('POST');
    expect(init?.body).toBeUndefined();
  });

  it('never asks twice for the same conversation', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await askGrounded(el, stream);
    expect(titleCalls()).toHaveLength(1);

    stream.emit('chunk', { text: 'more' });
    await ask(el, 'and then?');
    stream.emit('chunk', { text: 'It expired.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    expect(titleCalls()).toHaveLength(1);
  });

  it('NEVER names a conversation the reader named first — the rename wins', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    // Renamed while the answer is still streaming, so the guard is consulted at the terminal rather
    // than being made moot by the once-per-conversation latch.
    const id = el.sessions.sessions[0]?.id ?? '';
    await rename(el, id, 'Renewal postmortem');
    expect(el.sessions.sessions[0]?.renamed).toBe(true);

    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    expect(titleCalls()).toHaveLength(0);
    expect(el.sessions.sessions[0]?.title).toBe('Renewal postmortem');
  });

  it('still names the NEXT conversation, so the guard is a refusal and not a breakage', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    await rename(el, el.sessions.sessions[0]?.id ?? '', 'Renewal postmortem');
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    expect(titleCalls()).toHaveLength(0);

    (q(await region(el, 'jf-sv3-sidebar'), 'sv3-sidebar-new') as HTMLButtonElement | null)?.click();
    await settle(el);
    await ask(el, 'a second question');
    stream.emit('chunk', { text: 'a second answer' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    expect(titleCalls()).toHaveLength(1);
    const second = el.sessions.sessions.find((sv) => sv.title !== 'Renewal postmortem')?.id ?? '';
    expect(second).not.toBe('');
    expect(String(titleCalls()[0]?.[0])).toContain(`/conversations/${encodeURIComponent(second)}/auto-title`);
  });

  it('writes the reader\'s name back when they rename while the model is still naming it', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    // The naming POST is held open until this case releases it, which is the race: the generation is
    // already in flight when the reader answers the same question themselves.
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const answerFetch = fetchMock.getMockImplementation();
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => {
      if (String(url).endsWith('/auto-title')) {
        await held;
        return {
          ok: true,
          status: 200,
          json: async () => ({ ok: true, generated: true, title: 'Renewal Lock Failure', titleSource: 'auto' }),
        };
      }
      return (answerFetch as (u: unknown, i: unknown) => Promise<unknown>)(url, init);
    });

    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    const id = el.sessions.sessions[0]?.id ?? '';
    expect(titleCalls()).toHaveLength(1);

    await rename(el, id, 'Renewal postmortem');
    release();
    await settle(el);

    // Tempdoc 838 — the name the STORE was told last is the reader's, not the one that landed after
    // they had answered. This used to read a browser-local map; the durable authority is the
    // conversation's own record now, so the assertion is on what was written to it — and it carries
    // the `user` provenance, which is what keeps a later reload's first ask from renaming it back.
    const written = titleWrites(id);
    expect(written.at(-1)).toEqual({ title: 'Renewal postmortem', source: 'user' });
    expect(el.sessions.sessions[0]?.title).toBe('Renewal postmortem');
    // And nothing was left in the retired localStorage map — that key is gone, not merely unread.
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
  });

  it('a late automatic title never writes this window\'s rename over a newer rename from another tab', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    // The server's record of the name. The automatic title is stored when its request arrives
    // (only over no name, as the route does); its answer is held until this case releases it.
    let stored: { title: string; source: string } | null = null;
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const answerFetch = fetchMock.getMockImplementation();
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => {
      if (String(url).endsWith('/auto-title')) {
        stored ??= { title: 'Renewal Lock Failure', source: 'auto' };
        await held;
        return {
          ok: true,
          status: 200,
          json: async () => ({ ok: true, generated: true, title: 'Renewal Lock Failure', titleSource: 'auto' }),
        };
      }
      if (String(url).endsWith('/title')) {
        stored = JSON.parse(String((init as { body: string }).body));
        return { ok: true, status: 200, json: async () => ({ ok: true }) };
      }
      return (answerFetch as (u: unknown, i: unknown) => Promise<unknown>)(url, init);
    });

    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'The lock held.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);
    const id = el.sessions.sessions[0]?.id ?? '';
    expect(titleCalls()).toHaveLength(1);
    expect(stored).toEqual({ title: 'Renewal Lock Failure', source: 'auto' });

    // R1 in this tab, then R2 in another tab of the same browser, before the answer lands here.
    await rename(el, id, 'Renewal postmortem');
    expect(stored).toEqual({ title: 'Renewal postmortem', source: 'user' });
    stored = { title: 'Lock incident review', source: 'user' };
    release();
    await settle(el);

    // The newer rename survives, and this untouched window sent no further title write (a title
    // write counts as a person acting).
    expect(stored).toEqual({ title: 'Lock incident review', source: 'user' });
    expect(titleWrites(id)).toEqual([{ title: 'Renewal postmortem', source: 'user' }]);
  });

  it('never re-names a conversation the reader named BEFORE a reload (tempdoc 838)', async () => {
    // The regression this reproduces: `renamed` was a fact only the tab's memory held, so a
    // conversation merged back from the store after a reload started unflagged — and the next ask in
    // it handed the model the right to overwrite the name the reader had chosen. The provenance is on
    // the wire now, and the merge seeds the flag from it.
    feed();
    const stream = stubStream();
    const answerFetch = fetchMock.getMockImplementation();
    const listed = {
      sessionId: 'uc-reloaded',
      createdAtMs: 1,
      lastActiveAtMs: 2,
      messageCount: 2,
      firstUserMessage: 'why did the renewal fail?',
      shapeId: 'core.rag-ask',
      title: 'Renewal postmortem',
      titleSource: 'user',
    };
    fetchMock.mockImplementation(async (url: unknown, init: unknown) => {
      if (String(url).includes('/api/chat/conversations?')) {
        return { ok: true, status: 200, body: null, json: async () => ({ sessions: [listed] }) };
      }
      return (answerFetch as (u: unknown, i: unknown) => Promise<unknown>)(url, init);
    });

    // A FRESH window — the tab that did the renaming is gone, exactly as after a reload.
    const el = await mount();
    await settle(el);
    expect(el.sessions.sessions.map((s) => s.id)).toEqual(['uc-reloaded']);
    expect(el.sessions.sessions[0]?.renamed).toBe(true);

    (await region(el, 'jf-sv3-sidebar')).dispatchEvent(
      new CustomEvent('sv3-session-select', {
        detail: { id: 'uc-reloaded' },
        bubbles: true,
        composed: true,
      }),
    );
    await settle(el);
    await ask(el, 'and what did the renewal cost?');
    stream.emit('chunk', { text: 'Nine months.' });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    // The model was never asked to name it, and the reader's name is still on the row.
    expect(titleCalls()).toHaveLength(0);
    expect(titleWrites('uc-reloaded')).toEqual([]);
    expect(el.sessions.sessions[0]?.title).toBe('Renewal postmortem');
  });

  it('does not name a conversation whose only turn was stopped by the reader', async () => {
    feed();
    const stream = stubStream();
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'Half an ans' });
    await settle(el);
    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement | null)?.click();
    await settle(el);
    expect(titleCalls()).toHaveLength(0);
  });
});

/* ── E8: "offline" keeps its one sense in this window's copy ─────────────────────────────────── */

describe('this window re-splits no word the product has already settled', () => {
  it('spends no copy on "offline" at all (tempdoc 813 §6; inventory E8)', async () => {
    const { readdirSync, readFileSync } = await import('node:fs');
    const { dirname, join } = await import('node:path');
    const { fileURLToPath } = await import('node:url');
    const here = dirname(fileURLToPath(import.meta.url));
    // The repo-wide copy lint (`scripts/ci/check-offline-single-sense.mjs`, retired 930 chunk H) kept
    // a per-file allow-list of sanctioned uses; search-v3 had NO entry, and this test keeps that true
    // directly now. The window's every "the model is unavailable" phrase comes from
    // `readinessNotice`, so it needs the word nowhere.
    const offenders = readdirSync(here)
      .filter((name) => name.endsWith('.ts'))
      // This file writes the token in order to forbid it — the one allowed mention.
      .filter((name) => name !== 'SearchV3View.honesty.test.ts')
      .filter((name) =>
        readFileSync(join(here, name), 'utf8')
          // Comments are not copy, and the gate strips them before scanning; do the same here.
          .replace(/\/\*[\s\S]*?\*\//g, '')
          .replace(/^\s*\/\/.*$/gm, '')
          .toLowerCase()
          .includes('offline'),
      );
    expect(offenders).toEqual([]);
  });

  it('keeps the composer model label to IDENTITY, never a second sense of "offline"', async () => {
    // Phase F11 put a model NAME in the composer's control row, one box away from the availability
    // notice. The name states which model; the notice states whether it can be used, in the ONE
    // readiness vocabulary's own words. Two senses of availability in the same box is exactly the
    // duplicate this window measures zero of — asserted HERE because this is the only file in the
    // window allowed to spell the word.
    feed();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const label = textOf(q(composer, 'sv3-composer-model')).toLowerCase();
    expect(label).not.toBe('');
    for (const word of ['offline', 'unavailable', 'not available', 'no model']) {
      expect(label).not.toContain(word);
    }
  });
});
