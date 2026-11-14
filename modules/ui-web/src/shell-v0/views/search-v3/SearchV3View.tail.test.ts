// @vitest-environment happy-dom

/**
 * THE ANSWER TAIL (tempdoc 822 Phase F11) — everything below a settled answer, in ONE row.
 *
 * Three stacked rows (the frame line, the imported panel's own uppercase disclosure, the action bar)
 * became one 24px row at the design spec's own footer geometry. What is pinned here is not the appearance
 * but the four properties that make the compression honest:
 *
 *  - the SWEEP is real — the retired rows are gone, not hidden (a structural assertion, so residue
 *    would fail rather than merely look wrong);
 *  - L14's boundary holds — exactly ONE thing in the row yields to hover, and its reveal has the
 *    three SEPARATE focus rules the F3 defect taught (a nested `:has(:focus-visible)` is a Chrome
 *    syntax error that silently kills every rule after it);
 *  - the count is told exactly ONCE per turn, whichever surface tells it (F7's suppression rule,
 *    re-pointed at the new trigger);
 *  - a turn that was never told something says nothing — no dash, no "unknown", no stray separator.
 *
 * The turns are FIXTURES handed straight to the surface: the tail is a rendering of a settled turn,
 * so driving a live stream to reach it would test the stream. The stream-driven cases live next door
 * in `SearchV3View.honesty.test.ts`, which is where the derivation is checked end to end.
 */
import { describe, it, expect, afterEach } from 'vitest';
import './Sv3Main.js';
import './Sv3Composer.js';
import '../../components/chat/CitationsPanel.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';
import { answerFrameLabel } from '../../components/chat/evidenceProjection.js';
import { splitSv3FrameLabel } from './sv3-honesty.js';
import { TURN_COPY_LABEL } from './fixtures.js';
import type { Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';
import type { CitationMatch, RetrievalCitation } from '../../components/chat/citationTypes.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

const source = (chunkIndex: number): RetrievalCitation =>
  ({
    parentDocId: `f:/docs/note-${chunkIndex}.md`,
    chunkIndex,
    chunkTotal: 3,
    startChar: 0,
    endChar: 90,
    score: 0.7,
    excerpt: 'An excerpt.',
    startLine: 0,
    endLine: 4,
    headingText: '',
    headingLevel: 0,
  }) as RetrievalCitation;

const match = (sentenceIndex: number): CitationMatch =>
  ({
    sentenceIndex,
    sentenceText: 'The lock held.',
    sourceIndex: sentenceIndex,
    similarity: 0.9,
    parentDocId: `f:/docs/note-${sentenceIndex}.md`,
  }) as CitationMatch;

const evidence = (over: Partial<Sv3TurnEvidence> = {}): Sv3TurnEvidence => ({
  sources: [source(0), source(1), source(2), source(3), source(4)],
  matches: [],
  marks: [],
  retrievalMode: 'HYBRID',
  ...over,
});

const turn = (over: Partial<Sv3Turn> = {}): Sv3Turn => ({
  id: 't1',
  recordId: null,
  assistantRecordId: null,
  recordOpenedByUser: false,
  kind: 'ask',
  question: 'why did the renewal fail?',
  answer: 'Because the lock held.',
  status: 'complete',
  evidence: evidence(),
  detail: '',
  toolCalls: 0,
  activity: [],
  askedAt: 0,
  standaloneQuestion: '',
  reasoning: [],
  durationMs: 45_700,
  modelLabel: 'Qwen3',
  disposition: null,
  ...over,
});

async function mountMain(
  turns: readonly Sv3Turn[],
  currentModelLabel: string | null = 'Qwen3',
  detailed = false,
): Promise<Sv3Main & Mounted> {
  const el = document.createElement('jf-sv3-main') as Sv3Main & Mounted;
  el.state = 'docked';
  el.turns = turns;
  el.currentModelLabel = currentModelLabel;
  el.detailed = detailed;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

const all = (host: Mounted, testid: string): HTMLElement[] => [
  ...(host.shadowRoot?.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`) ?? []),
];

const turnsOf = (host: Mounted): HTMLElement[] => all(host, 'sv3-turn');

const inTurn = (el: HTMLElement, selector: string): HTMLElement | null =>
  el.querySelector<HTMLElement>(selector);

const text = (el: Element | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

/** The window's OWN stylesheet — the last entry in `static styles`, as the imports gate reads it. */
const ownStyles = (ctor: { styles?: unknown }): string => {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
};

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
});

/* ── The sweep: three rows became one, and the old ones are GONE ─────────────────────────────── */

describe('a settled answer carries ONE tail row, and nothing of the three it replaced', () => {
  it('renders exactly one tail and no residue of the retired frame line or action bar', async () => {
    const el = await mountMain([turn()]);
    expect(all(el, 'sv3-turn-tail')).toHaveLength(1);
    // Structural, so RESIDUE fails rather than merely looking wrong: an old row left behind would
    // still be in the tree even if it happened to render at zero height.
    expect(el.shadowRoot?.querySelector('p.answer-frame')).toBeNull();
    expect(el.shadowRoot?.querySelector('.answer-frame')).toBeNull();
    expect(el.shadowRoot?.querySelector('.turn-actions')).toBeNull();
    expect(el.shadowRoot?.querySelector('.turn-action')).toBeNull();
    // ...and the retired rules are gone from the sheet too, not merely unreferenced.
    const css = ownStyles(Sv3Main);
    expect(css).not.toContain('.answer-frame');
    expect(css).not.toContain('.turn-actions');
    expect(css).not.toContain('.turn-note');
  });

  it('holds all three occupants of the row: the facts, the disclosure and the copy', async () => {
    const el = await mountMain([turn()]);
    const tail = q(el, 'sv3-turn-tail') as HTMLElement;
    expect(inTurn(tail, '[data-testid="sv3-answer-frame"]')).not.toBeNull();
    expect(inTurn(tail, '[data-testid="sv3-turn-sources"]')).not.toBeNull();
    expect(inTurn(tail, '[data-testid="sv3-turn-copy"]')).not.toBeNull();
  });

  it('renders no row at all while the answer is still arriving', async () => {
    const el = await mountMain([turn({ status: 'streaming', evidence: null })]);
    expect(q(el, 'sv3-turn-tail')).toBeNull();
  });
});

/* ── L14: exactly one thing yields, and its reveal survives the keyboard ─────────────────────── */

describe('the row keeps every honesty fact resting and hides only the action', () => {
  it('sets opacity 0 on the copy control and on nothing else in the row', async () => {
    const css = ownStyles(Sv3Main);
    // Every `opacity: 0` in the sheet, with the selector that owns it.
    const hidden = [...css.matchAll(/([^{}\n]+)\{[^{}]*opacity:\s*0;/g)].map((m) =>
      (m[1] as string).trim(),
    );
    const inTail = hidden.filter((selector) => selector.includes('tail'));
    expect(inTail).toEqual(['.tail-copy']);
  });

  it('reveals it through THREE SEPARATE rules — the F3 keeper', async () => {
    const css = ownStyles(Sv3Main);
    // A keyboard reader gets no hover, and focus must not wait on the reveal having finished.
    expect(css).toContain('.turn:hover .tail-copy');
    expect(css).toContain('.turn:focus-within .tail-copy');
    expect(css).toContain('.tail-copy:focus-visible');
    // THE defect this pins: a nested `:has(:focus-visible)` is a Chrome parse error that silently
    // kills the whole rule list after it — static-green, live-broken (tempdoc 822 §5, Phase F3).
    expect(css).not.toContain(':has(:focus-visible)');
    expect(css).not.toMatch(/:has\([^)]*:focus/);
  });

  it('reserves the row height so nothing resizes when the action appears', async () => {
    const css = ownStyles(Sv3Main);
    expect(css).toMatch(/\.tail\s*\{[^}]*min-height:\s*var\(--space-6\)/);
  });

  it('authors no uppercase anywhere — the window speaks the imported dialect nowhere', async () => {
    // Question D, made permanent: the disclosure that carried `▸ N SOURCES` into this window is the
    // window's own now, and it is sentence case at the tail's 12px.
    expect(ownStyles(Sv3Main)).not.toContain('text-transform: uppercase');
    expect(ownStyles(Sv3Composer)).not.toContain('text-transform: uppercase');
  });
});

/* ── The two halves of one fact ──────────────────────────────────────────────────────────────── */

describe('the verdict rests and the elaboration extends, re-wording neither', () => {
  it('rests the verdict + receipt, and keeps the WHOLE authority string in the accessible name', async () => {
    const el = await mountMain([turn()]);
    const facts = q(el, 'sv3-answer-frame') as HTMLElement;
    const label = answerFrameLabel('sourced', false) as string;
    const { verdict } = splitSv3FrameLabel(label);

    // The RESTING half is the verdict and the receipt, and NOT the elaboration.
    const resting = facts.querySelector('[aria-hidden="true"]');
    expect(text(resting)).toBe(`${verdict} · 45.7 s`);
    expect(text(resting)).not.toContain(splitSv3FrameLabel(label).elaboration);

    // The ACCESSIBLE half is the authority's whole string — nothing was hidden from assistive tech,
    // so nothing has to be revealed to it.
    const spoken = facts.querySelector('.visually-hidden');
    expect(text(spoken)).toBe(`${label} · 45.7 s`);
    // ...and the pointer route says the same thing.
    expect(facts.getAttribute('title')).toBe(`${label} · 45.7 s`);
  });

  it('drops the model when the composer already names it, and re-states it when it does not', async () => {
    // Inventory E3 — the model is a Detailed-mode fact, so the re-statement is checked in the mode
    // that can show it at all; the Simple direction is the case below.
    const same = await mountMain([turn()], 'Qwen3', true);
    expect(text(q(same, 'sv3-answer-frame'))).not.toContain('Qwen3');
    same.remove();
    const swapped = await mountMain([turn()], 'Llama-4', true);
    expect(text(q(swapped, 'sv3-answer-frame'))).toContain('Qwen3');
  });
});

/* ── Never-told stays never-told ─────────────────────────────────────────────────────────────── */

describe('a record-restored turn says nothing it was not told', () => {
  it('renders the row with only the revealed copy — no dash, no "unknown", no stray separator', async () => {
    // The F6 shape: a turn read back from the record carries no receipt and no evidence.
    const el = await mountMain([
      turn({ durationMs: null, modelLabel: null, evidence: null }),
    ]);
    const tail = q(el, 'sv3-turn-tail') as HTMLElement;
    expect(tail).not.toBeNull();
    expect(inTurn(tail, '[data-testid="sv3-answer-frame"]')).toBeNull();
    expect(inTurn(tail, '[data-testid="sv3-turn-sources"]')).toBeNull();
    expect(inTurn(tail, '[data-testid="sv3-turn-copy"]')).not.toBeNull();
    // Visually empty at rest: the row's only text is the (empty) copy live region.
    expect(text(tail)).toBe('');
    expect(text(tail)).not.toContain('·');
    expect(text(tail)).not.toContain('—');
  });
});

/* ── F7's suppression rule, re-pointed at the new trigger ────────────────────────────────────── */

describe('the source count is told exactly once per turn', () => {
  it('lets the disclosure own it when the panel speaks — the note says nothing', async () => {
    // A duration with no digit `5` in it, so the count is the ONLY 5 the turn could render.
    const el = await mountMain([turn({ durationMs: 2_000 })]);
    const row = turnsOf(el)[0] as HTMLElement;
    expect(row.querySelector('[data-testid="sv3-turn-note"]')).toBeNull();
    const trigger = row.querySelector('[data-testid="sv3-turn-sources"]') as HTMLElement;
    // Bare word on the resting surface (the owner's direction); the count is in the name.
    expect(text(trigger)).toBe('Sources');
    expect(trigger.getAttribute('aria-label')).toBe('Sources: 5');
    // The mutation probe for F7's guard: drop `panelSpeaks` from `turnNote` and "5 sources" appears
    // in this subtree a second time. Everything the turn SAYS — its text and every accessible name
    // in it — holds exactly one `5`, and it is the trigger's.
    const spoken = [
      row.textContent ?? '',
      ...[...row.querySelectorAll('[aria-label]')].map((el) => el.getAttribute('aria-label') ?? ''),
    ].join(' ');
    expect(spoken.match(/5/g) ?? []).toHaveLength(1);
    expect(row.textContent).not.toContain('5');
  });

  it('lets the note own it when the panel is silent, and says nothing when never told', async () => {
    const reported = await mountMain([turn({ evidence: evidence({ sources: [] }) })]);
    const row = turnsOf(reported)[0] as HTMLElement;
    // A REPORTED empty set is a real claim, and with no panel to head it the note carries it.
    expect(row.querySelector('[data-testid="sv3-turn-sources"]')).toBeNull();
    expect(text(row.querySelector('[data-testid="sv3-turn-note"]'))).toBe('0 sources');
    reported.remove();

    const silent = await mountMain([turn({ evidence: null })]);
    const quiet = turnsOf(silent)[0] as HTMLElement;
    // Never told is not zero.
    expect(quiet.querySelector('[data-testid="sv3-turn-note"]')).toBeNull();
    expect(quiet.querySelector('[data-testid="sv3-turn-sources"]')).toBeNull();
  });

  it('calls a match-only panel Citations, because no retrieval was ever reported', async () => {
    const el = await mountMain([
      turn({ evidence: evidence({ sources: [], matches: [match(0), match(1)] }) }),
    ]);
    const trigger = q(el, 'sv3-turn-sources') as HTMLElement;
    expect(text(trigger)).toBe('Citations');
    expect(trigger.getAttribute('aria-label')).toBe('Citations: 2');
  });
});

/* ── The disclosure the window now owns ──────────────────────────────────────────────────────── */

describe('the disclosure is the window\'s own, per turn, and it opens the SHARED panel', () => {
  it('toggles false → true → false, and the panel exists only while open', async () => {
    const el = await mountMain([turn()]);
    const trigger = q(el, 'sv3-turn-sources') as HTMLButtonElement;
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(q(el, 'sv3-turn-citations')).toBeNull();
    expect(trigger.getAttribute('aria-controls')).toBeNull();

    trigger.click();
    await el.updateComplete;
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const panel = q(el, 'sv3-turn-citations');
    expect(panel).not.toBeNull();
    // The trigger points AT everything it opened. The disclosure reveals TWO elements — the mark
    // legend and the panel — so naming only the panel put the key outside the announced
    // relationship: a reader following `aria-controls` landed past the legend, which sits before it.
    // `aria-controls` is an ID LIST, so both are named, in DOM order.
    const legend = q(el, 'sv3-cite-legend');
    expect(legend).not.toBeNull();
    expect(trigger.getAttribute('aria-controls')).toBe(`${legend?.id} ${panel?.id}`);
    for (const id of (trigger.getAttribute('aria-controls') ?? '').split(' ')) {
      expect(id, 'every id named by aria-controls resolves in this shadow root').not.toBe('');
      expect(el.renderRoot.querySelector(`#${id}`), `#${id}`).not.toBeNull();
    }

    trigger.click();
    await el.updateComplete;
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(q(el, 'sv3-turn-citations')).toBeNull();
  });

  it('swaps the chevron rather than rotating one glyph — the spec\'s own inline disclosure', async () => {
    // The shared icon set's Lucide paths (`components/Icon.ts`), which is the only thing that tells
    // the two glyphs apart in the DOM. The imported panel ROTATES one glyph; the spec's inline
    // disclosure swaps two, and this window takes the spec's.
    const CHEVRON_RIGHT = 'm9 18 6-6-6-6';
    const CHEVRON_DOWN = 'm6 9 6 6 6-6';
    const el = await mountMain([turn()]);
    const trigger = q(el, 'sv3-turn-sources') as HTMLButtonElement;
    const glyph = (): string | null =>
      trigger.querySelector('.tail-chevron path')?.getAttribute('d') ?? null;
    expect(glyph()).toBe(CHEVRON_RIGHT);
    trigger.click();
    await el.updateComplete;
    expect(glyph()).toBe(CHEVRON_DOWN);
    // ...and the chevron is sized at the spec's 14px, one step up from the 12px text beside it.
    expect(trigger.querySelector('.tail-chevron')?.getAttribute('width')).toBe('14');
  });

  // Tempdoc 822 §5.7 (F7) — five mark types and two dotted underlines with no key anywhere, and the
  // two GREYS mean opposite things. The legend lives INSIDE this disclosure, so it costs zero
  // resting chrome; a legend that rendered while the disclosure was shut would be a 16th chrome row.
  it('keys the marks — but only while the disclosure is OPEN', async () => {
    const el = await mountMain([turn()]);
    expect(q(el, 'sv3-cite-legend')).toBeNull();

    (q(el, 'sv3-turn-sources') as HTMLButtonElement).click();
    await el.updateComplete;
    const legend = q(el, 'sv3-cite-legend') as HTMLElement;
    expect(text(legend)).toBe(
      'Select a source to see the sentences it supports. A dotted underline marks a sentence the ' +
        'evidence supports weakly; amber marks one it does not support. A grey number is a weak ' +
        'reference.',
    );
    // Sentence case, per the window's copy law: v3 uses UPPERCASE nowhere.
    expect(text(legend)).not.toMatch(/\b[A-Z]{2,}\b/);

    (q(el, 'sv3-turn-sources') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'sv3-cite-legend')).toBeNull();
  });

  it('is PER TURN: opening one leaves the other collapsed', async () => {
    const el = await mountMain([turn(), turn({ id: 't2' })]);
    const triggers = all(el, 'sv3-turn-sources') as HTMLButtonElement[];
    expect(triggers).toHaveLength(2);
    (triggers[0] as HTMLButtonElement).click();
    await el.updateComplete;
    expect(triggers[0]?.getAttribute('aria-expanded')).toBe('true');
    expect(triggers[1]?.getAttribute('aria-expanded')).toBe('false');
    expect(all(el, 'sv3-turn-citations')).toHaveLength(1);
  });

  it('mounts the shared panel HEADERLESS here, while the shipped default still heads itself', async () => {
    const el = await mountMain([turn()]);
    (q(el, 'sv3-turn-sources') as HTMLButtonElement).click();
    await el.updateComplete;
    const panel = q(el, 'sv3-turn-citations') as Mounted;
    await panel.updateComplete;
    // The mutation probe: drop `externalDisclosure` from the sv3 mount and the imported uppercase
    // `▸ N SOURCES` header comes back, on its own row, and the tail is two rows again.
    expect(panel.shadowRoot?.querySelector('.panel-header')).toBeNull();
    // ...and the SAME component, mounted plainly, is unchanged for the two shipped consumers.
    const plain = document.createElement('jf-citations-panel') as Mounted & {
      sources: readonly unknown[];
      citations: readonly unknown[];
    };
    plain.sources = [source(0), source(1)];
    plain.citations = [match(0), match(1)];
    document.body.appendChild(plain);
    await plain.updateComplete;
    expect(plain.shadowRoot?.querySelector('.panel-header')).not.toBeNull();
  });
});

/* ── A9: the copy control, icon-only ─────────────────────────────────────────────────────────── */

describe('copy is the only yielding element, and it reports without renaming itself', () => {
  it('keeps its accessible name and announces through the row\'s live region', async () => {
    const el = await mountMain([turn()]);
    const copy = q(el, 'sv3-turn-copy') as HTMLButtonElement;
    expect(copy.getAttribute('aria-label')).toBe(TURN_COPY_LABEL);
    // Icon-only: no text of its own, so the row's resting width is the facts' alone.
    expect(text(copy)).toBe('');
    // The spec's glyph pair, told apart by their own shapes: `copy` (lucide, two rounded rects
    // since 870 item 5) opens with a <rect>, the confirmation `check-circle-2` with a <circle>
    // (`components/Icon.ts`).
    expect(copy.querySelector('svg rect')).not.toBeNull();
    expect(copy.querySelector('svg circle')).toBeNull();
    expect(copy.querySelector('svg')?.getAttribute('width')).toBe('12');
    const live = q(el, 'sv3-turn-copy-status') as HTMLElement;
    expect(live.getAttribute('aria-live')).toBe('polite');
    expect(text(live)).toBe('');
  });

  it('is offered only for an answer there IS', async () => {
    const halted = await mountMain([turn({ status: 'halted' })]);
    expect(q(halted, 'sv3-turn-copy')).toBeNull();
    halted.remove();
    const empty = await mountMain([turn({ answer: '' })]);
    expect(q(empty, 'sv3-turn-copy')).toBeNull();
  });
});

/* ── The model, relocated into the composer ──────────────────────────────────────────────────── */

describe('the composer states which model would answer — identity only, never state', () => {
  async function mountComposer(over: Record<string, string> = {}): Promise<Sv3Composer & Mounted> {
    const el = document.createElement('jf-sv3-composer') as Sv3Composer & Mounted;
    el.tier = 'ask';
    for (const [name, value] of Object.entries(over)) el.setAttribute(name, value);
    document.body.appendChild(el);
    await el.updateComplete;
    return el;
  }

  it('renders the runtime label verbatim, with the full string on the pointer route', async () => {
    const el = await mountComposer({ 'model-label': 'Qwen Qwen3.5-9B' });
    const label = el.shadowRoot?.querySelector('[data-testid="sv3-composer-model"]') ?? null;
    expect(text(label)).toBe('Qwen Qwen3.5-9B');
    expect(label?.getAttribute('title')).toBe('Qwen Qwen3.5-9B');
    // A FACT, not a control: no role invented for it, and nothing to click.
    expect(label?.tagName).toBe('SPAN');
    expect(label?.hasAttribute('role')).toBe(false);
    expect(label?.hasAttribute('tabindex')).toBe(false);
  });

  it('is ABSENT when nothing was reported — no "no model", no placeholder, no em dash', async () => {
    const el = await mountComposer();
    expect(el.shadowRoot?.querySelector('[data-testid="sv3-composer-model"]')).toBeNull();
    // IDENTITY ONLY: the label states which model, never whether one is there. The availability
    // notice above the box is the ONE place a STATE is said, and the vocabulary keeper for that
    // lives in `SearchV3View.honesty.test.ts` (E8), which is the one file allowed to spell the word.
    const shown = text(el.shadowRoot?.querySelector('.controls') ?? null).toLowerCase();
    for (const word of ['no model', 'not available', 'unavailable', 'unknown', '—']) {
      expect(shown).not.toContain(word);
    }
  });

  it('does NOT evaporate when docked — that is exactly when the reader asks which model', async () => {
    const el = await mountComposer({ 'model-label': 'Qwen Qwen3.5-9B', state: 'docked' });
    const label = el.shadowRoot?.querySelector('[data-testid="sv3-composer-model"]');
    expect(label).not.toBeNull();
    // A sibling of the effort control, never inside its label — `.control-label` is the thing that
    // collapses to `max-inline-size: 0` on dock, and the model must not ride along.
    expect(label?.closest('.control-label')).toBeNull();
    expect(label?.closest('button')).toBeNull();
    const css = ownStyles(Sv3Composer);
    expect(css).not.toMatch(/\.model-label[^{}]*\{[^}]*max-inline-size:\s*0/);
    expect(css).not.toMatch(/docked'\]\)\s*\.model-label/);
  });
});
