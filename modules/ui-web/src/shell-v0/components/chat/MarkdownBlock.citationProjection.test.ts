/**
 * Tempdoc 869 §2.5 — the CONFORMANCE guard for `evidence-fe-markdown-block`, the registered
 * projection of `ContextCitation` (→ `RetrievalCitation` → `Citation`, via `claimsToCitations`) into
 * the answer's inline marks.
 *
 * This is where 869's invariants live, rather than paperwork beside them. The renderer is the one
 * surface where three upstream authorities meet — the verifier (a `Citation`'s similarity and
 * sentence span), the model (the literal `[n]` it wrote), and the frame (577 Move 3) — and every
 * class it mints must be traceable to exactly one of them:
 *
 *  - I-2 REACHABILITY (C2, redisposed by 867 §7): which citation-shaped text each frame may touch,
 *    and how. A ref that resolves to a source and was NOT verified is REMOVED from a sourced
 *    answer's prose — the owner's decision (867 family A), superseding 869's mute of the same ref;
 *    an unresolvable `[7]`, a parenthesised `(2)`, and a label the weave rendered are prose or
 *    evidence and stay. The `ungrounded` frame keeps 577's mute, predicate and wording unchanged.
 *  - I-2 ORDER: a literal the tier-2 upgrade claims is never stripped instead.
 *  - The muted span (ungrounded only) SAYS what it is — colour is not the carrier.
 *  - I-1 POSITION (C1): the model's literal is transparent to the weave and its whitespace is the
 *    model's, so a mark lands where the same text would put it with no literal written at all, and
 *    a strip leaves the prose the model's spacing rather than the renderer's.
 *  - I-1 COLOUR (C3): a mark's tier is a POSITIVE class. Tier 1 states its grounding; tier 2 states
 *    none, because none was measured — and "no class" therefore has to paint neutral, not strongest.
 *
 * @vitest-environment happy-dom
 */

import { afterEach, describe, expect, it } from 'vitest';
import { loadHighlighter, resetHighlighterForTest } from '../markdown/markdownHighlight.js';
import {
  MarkdownBlock as MarkdownBlockClass,
  type MarkdownBlock,
  type MarkdownCitation,
} from './MarkdownBlock.js';
import './MarkdownBlock.js';
// Tempdoc 869 F10 — the OTHER surface this guard is registered for (`evidence-fe-record-evidence`).
import { admittedMatches } from './recordEvidence.js';
import { VERIFIED_SCORER } from './evidenceProjection.js';
import type { CitationMatch } from './citationTypes.js';

/** The flattened `styles` array (846 §2.3 — the shared sheets plus this component's own rules). */
function markdownBlockCssText(): string {
  const styles = MarkdownBlockClass.styles as unknown;
  const sheets = Array.isArray(styles) ? styles : [styles];
  return sheets.map((s) => (s as { cssText: string }).cssText).join('\n');
}

/**
 * A resolved citation, shaped as the producers build it (`MarkdownBlock.test.ts`'s `mark`). The
 * `sentenceText` is what decides the tier: text the answer contains anchors a tier-1 mark, text it
 * does not contain anchors nothing and leaves the label to the tier-2 literal upgrade.
 */
function mark(sentenceText: string, label: number, sentenceIndex: number): MarkdownCitation {
  return {
    sentenceText,
    similarity: 0.8,
    sentenceIndex,
    label,
    detail: {
      parentDocId: `f:/docs/${label}.md`,
      startLine: 1,
      endLine: 5,
      startChar: 0,
      endChar: 0,
      excerpt: 'x',
    },
    hover: { excerpt: 'an excerpt', title: 'X', headingText: '' },
  };
}

interface Mount {
  text: string;
  citations?: MarkdownCitation[];
  sourceCount?: number;
  frame?: MarkdownBlock['frame'];
  streaming?: boolean;
  format?: MarkdownBlock['format'];
}

async function mounted(opts: Mount): Promise<MarkdownBlock> {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  el.text = opts.text;
  el.citations = opts.citations ?? [];
  el.sourceCount = opts.sourceCount ?? 0;
  if (opts.frame !== undefined) el.frame = opts.frame;
  if (opts.format !== undefined) el.format = opts.format;
  if (opts.streaming === true) el.isStreaming = true;
  document.body.appendChild(el);
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  return el;
}

/** Let a property change reach the settled-render pipeline (the same wait `mounted` uses). */
async function settled(el: MarkdownBlock): Promise<void> {
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
}

const content = (el: MarkdownBlock): Element => el.renderRoot.querySelector('.md-content')!;
const muted = (el: MarkdownBlock): HTMLElement[] =>
  [...content(el).querySelectorAll<HTMLElement>('.pseudo-cite')];

/** The answer sentence the verifier grounded in every fixture below — it anchors a tier-1 mark. */
const ANCHORED = 'The kernel is a shared substrate.';
/** A sentence the answer does not contain: its label reaches the reader only through tier 2. */
const UNANCHORED = 'A sentence this answer never wrote.';

describe('I-2 reachability — the strip reaches a SOURCED answer, and only what resolves', () => {
  it('removes an unverified resolvable ref and leaves prose, out-of-range refs and marks alone', async () => {
    // Five sources; the verifier stood behind two labels (5 anchored to a sentence, 4 reaching the
    // reader through the tier-2 upgrade of the model's own literal). The answer also carries `[2]`
    // (resolves to source 2 — nothing verified it), `[7]` (resolves to nothing: there is no seventh
    // source) and `(2)` (a number, not a ref notation this product ever mints).
    const el = await mounted({
      text: `${ANCHORED} The model wrote [2], then [7], then (2), then [4].`,
      citations: [mark(ANCHORED, 5, 0), mark(UNANCHORED, 4, 1)],
      sourceCount: 5,
    });

    // THE DECISION (867 §7, family A). `[2]` is gone from the prose — not greyed, not annotated.
    // Nothing muted survives on a sourced answer at all: the mute is the ungrounded frame's now.
    expect(muted(el)).toHaveLength(0);
    expect(content(el).textContent).not.toContain('[2]');
    // …and it took the space the literal was holding, not the comma that closes the clause.
    expect(content(el).textContent).toContain('The model wrote, then');

    // Untouched, both of them — an unresolvable label and a parenthesised number are prose here.
    // This is the whole safety of a strip: it deletes only what the source list answers for.
    expect(content(el).textContent).toContain('[7]');
    expect(content(el).textContent).toContain('(2)');

    // The verified labels are MARKS: 5 woven at its sentence, 4 upgraded from the literal.
    const refs = [...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent);
    expect(refs.sort()).toEqual(['4', '5']);
    el.remove();
  });

  it('strips nothing when the block was given no source list (the default, and every citation-less site)', async () => {
    // `sourceCount = 0` makes the strip vacuous BY CONSTRUCTION: nothing resolves, so nothing is
    // claimed and nothing is deleted. This is what keeps the citation-less consumers rendering
    // exactly as they did — and it is the direction a strip has to fail in.
    const el = await mounted({ text: 'A grounded answer [2].' });
    expect(muted(el)).toHaveLength(0);
    expect(content(el).textContent).toContain('[2]');
    el.remove();
  });

  it('changes nothing while the answer is still streaming — no pass runs before it settles', async () => {
    const el = await mounted({
      text: `${ANCHORED} The model wrote [2].`,
      citations: [mark(ANCHORED, 5, 0)],
      sourceCount: 5,
      streaming: true,
    });
    expect(muted(el)).toHaveLength(0);
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
    // The literal is still there mid-stream: a ref the reader watches being typed is not yet a
    // claim the block can settle, and deleting text under a live stream is the visible version.
    expect(content(el).textContent).toContain('[2]');
    el.remove();
  });
});

describe('I-2 order — a literal the tier-2 upgrade claims is never stripped instead', () => {
  it('upgrades the only literal rather than removing it', async () => {
    // The outcome is secured by the ORDER of the two fates inside one pass: a label with a citation
    // is upgraded (or deduped against a mark already rendered), and only a label no citation answers
    // for is removed. A pass that asked "was it verified?" second would delete the evidence.
    const el = await mounted({
      text: 'The model wrote [4] and nothing else worth citing.',
      citations: [mark(UNANCHORED, 4, 0)],
      sourceCount: 5,
    });
    expect(muted(el)).toHaveLength(0);
    expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['4']);
    expect(text(el)).toBe('The model wrote 4 and nothing else worth citing.\n');
    el.remove();
  });
});

describe('the ungrounded frame keeps its broad rule (577 Move 3, unchanged by 867 §7)', () => {
  it('mutes every citation-shaped token and names each one', async () => {
    const el = await mounted({
      text: 'JustSearch uses a 4-tier strategy [1] and chaos testing (2).',
      frame: 'ungrounded',
    });
    const spans = muted(el);
    expect(spans.map((s) => s.textContent)).toEqual(['[1]', '(2)']);
    for (const span of spans) {
      expect(span.title).toBe('Citation-shaped text in an answer that is not grounded in your files');
      expect(span.getAttribute('aria-label')).toBe(span.title);
    }
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
    el.remove();
  });

  it('mutes rather than strips even when a source list would resolve the ref', async () => {
    // THE FRAME BOUNDARY, stated rather than left to the default. `sourceCount` is what makes a ref
    // resolvable, and the strip is off here anyway: this frame's predicate cannot tell a ref from a
    // bracketed number (`(2)` is muted beside `[1]`), and a predicate that broad may grey text, never
    // delete it. The text the model wrote is still all there.
    const el = await mounted({
      text: 'JustSearch uses a 4-tier strategy [1] and chaos testing (2).',
      frame: 'ungrounded',
      sourceCount: 5,
    });
    expect(muted(el).map((s) => s.textContent)).toEqual(['[1]', '(2)']);
    expect(text(el)).toBe('JustSearch uses a 4-tier strategy [1] and chaos testing (2).\n');
    el.remove();
  });
});

describe('the strip predicate is POSITIVE about the frame — every frame but ungrounded (867 §7)', () => {
  // The predicate reads `frame !== 'ungrounded'`, and the frames a sourced answer actually settles
  // in are named here rather than left to the default: a narrowing to `frame === 'grounded'` looks
  // green against every fixture that never sets a frame, and would silently leave the reader an
  // unverified ref on exactly the answers 867 §7 is about.
  for (const frame of ['sourced', 'partially-grounded'] as const) {
    it(`strips the unverified ref in the ${frame} frame`, async () => {
      const el = await mounted({ text: 'Alpha [2] beta.', sourceCount: 5, frame });
      expect(muted(el)).toHaveLength(0);
      expect(text(el)).toBe('Alpha beta.\n');
      el.remove();
    });
  }
});

/* ── I-1 POSITION (C1) — the literal is transparent, and its whitespace is the model's ────────── */

/** A `.md-content` text dump, marker glyphs included — what the reader actually sees. */
const text = (el: MarkdownBlock): string => content(el).textContent ?? '';

/** Every text node under `.md-content`, so a remnant can be found wherever a split left it. */
const textNodes = (el: MarkdownBlock): string[] => {
  const walker = document.createTreeWalker(content(el), NodeFilter.SHOW_TEXT);
  const out: string[] = [];
  let n: Node | null;
  while ((n = walker.nextNode())) out.push((n as Text).data);
  return out;
};

describe('I-1 position — the STRIP arm: the mark lands where no literal was written', () => {
  /**
   * Each row is a shape the derisk pass (§3.1 R1) rendered on the real renderer. The KEY is what the
   * verifier scored, so it is the sentence WITHOUT the model's literal — except the last row, the
   * shape the backend actually emits (867 §1.3), where the sentence iterator swept the literal into
   * the key. `expected` is the whole `.md-content` text: the mark's glyph is the label, and the
   * trailing newline is markdown-it's block separator.
   *
   * Two of these were broken before 869 C1: `word[1].` put the marker INSIDE the brackets, which
   * split the text node and left the tier-2 regex nothing to match (`word[11].` — the digit twice,
   * the brackets orphaned); `[1] The kernel…` left the block starting with a space.
   *
   * `word [1], and more.` moves: the mark now lands after the comma, exactly where the same prose
   * without the literal has always put it. That is what "transparent" means — the literal is not a
   * boundary the walk respects, so `word [1], x` and `word, x` place the mark identically.
   */
  const SHAPES: ReadonlyArray<{ id: string; input: string; key: string; expected: string }> = [
    { id: 'space before, period after', input: 'word [1].', key: 'word', expected: 'word.1\n' },
    { id: 'space on both sides', input: 'word [1] .', key: 'word', expected: 'word1 .\n' },
    { id: 'glued to the word (the corruption)', input: 'word[1].', key: 'word', expected: 'word.1\n' },
    {
      id: 'comma after',
      input: 'word [1], and more.',
      key: 'word',
      expected: 'word,1 and more.\n',
    },
    {
      id: 'block-leading (the orphaned space)',
      input: '[1] The kernel is a shared substrate.',
      key: 'The kernel is a shared substrate.',
      expected: 'The kernel is a shared substrate.1\n',
    },
    {
      id: 'the backend shape — the literal is IN the key',
      input: 'Trade-offs matter here [1]. Next sentence.',
      key: 'Trade-offs matter here [1].',
      expected: 'Trade-offs matter here.1 Next sentence.\n',
    },
  ];

  for (const shape of SHAPES) {
    it(`${shape.id}: "${shape.input}" renders "${shape.expected.replace(/\n/g, '\\n')}"`, async () => {
      const el = await mounted({ text: shape.input, citations: [mark(shape.key, 1, 0)] });
      // The citation anchored, so this is the STRIP arm: one woven mark, the literal removed.
      expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['1']);
      expect(text(el)).toBe(shape.expected);
      el.remove();
    });
  }

  it('leaves no bracket remnant in any text node — the split-inside-the-literal defect', async () => {
    for (const shape of SHAPES) {
      const el = await mounted({ text: shape.input, citations: [mark(shape.key, 1, 0)] });
      for (const data of textNodes(el)) {
        expect(data, `${shape.id} — a text node kept a bracket`).not.toMatch(/[[\]]/);
      }
      el.remove();
    }
  });

  it('never creates a space before punctuation that the model did not write', async () => {
    // `word [1] .` is excluded on purpose: its space before the period is the MODEL's, and rule 3's
    // whole point is that the renderer does not edit the model's typography — only the one space the
    // literal it removed was holding.
    for (const shape of SHAPES.filter((s) => !/\s[.,;:!?]/.test(s.input.replace(/\s?\[\d+\]/g, '')))) {
      const el = await mounted({ text: shape.input, citations: [mark(shape.key, 1, 0)] });
      expect(text(el), `${shape.id}`).not.toMatch(/\s[.,;:!?]/);
      el.remove();
    }
  });
});

describe('I-1 position — the strip at a TEXT NODE EDGE (867 F1)', () => {
  /**
   * A literal that ends (or opens) its text node because an inline ELEMENT sits next to it is not at
   * the end of the prose. `closeLiteralGap` read the empty half as "nothing there" and closed a gap
   * that was holding two words apart: `Cited [2]*ital* tail` rendered `Citedital tail`, and the
   * mirror hole read the literal in `**bold**[2] tail` as block-leading and ate the space after it.
   * No citation is attached in any row, so every `[2]` here takes the STRIP arm.
   */
  const EDGES: ReadonlyArray<{ id: string; input: string; expected: string }> = [
    {
      id: 'an emphasis follows the literal with no space between',
      input: 'Cited [2]*ital* tail',
      expected: 'Cited ital tail\n',
    },
    {
      id: 'a space, then a strong',
      input: 'Cited [2] **bold** here',
      expected: 'Cited bold here\n',
    },
    {
      id: 'a strong precedes the literal with no space between',
      input: '**bold**[2] tail',
      expected: 'bold tail\n',
    },
  ];

  for (const edge of EDGES) {
    it(`${edge.id}: "${edge.input}" renders "${edge.expected.replace(/\n/g, '\\n')}"`, async () => {
      const el = await mounted({ text: edge.input, citations: [], sourceCount: 5 });
      expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
      expect(muted(el)).toHaveLength(0);
      expect(text(el)).toBe(edge.expected);
      el.remove();
    });
  }

  it('closes a gap the model spaced on BOTH sides to one space, not to three', async () => {
    // `plain` renders with `white-space: pre-wrap`, so the run the strip leaves is one the reader
    // sees. Removing a single character left `a` and `b` three spaces apart.
    const el = await mounted({ format: 'plain', text: 'a  [2]  b', sourceCount: 5 });
    expect(text(el)).toBe('a b');
    el.remove();
  });
});

describe('I-1 position — the UPGRADE arm: the marker inherits the literal\'s exact place', () => {
  // The old `/\s?\[(\d+)\]/` consumed the space BEFORE the literal too, so a marker replacing a
  // mid-sentence `[4]` glued itself to the previous word (`trade-offs⁴` — 869 C1's headline). The
  // model's spacing is the model's: it survives on both sides of the swap.
  it('keeps the space the model wrote: "word [4] more" → "word 4 more"', async () => {
    const el = await mounted({ text: 'word [4] more', citations: [mark(UNANCHORED, 4, 0)] });
    expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['4']);
    expect(text(el)).toBe('word 4 more\n');
    el.remove();
  });

  it('and invents none where the model wrote none: "word[4] more" → "word4 more"', async () => {
    const el = await mounted({ text: 'word[4] more', citations: [mark(UNANCHORED, 4, 0)] });
    expect(text(el)).toBe('word4 more\n');
    el.remove();
  });
});

/* ── I-1 COLOUR (C3) — the tier is a positive class, and tier 2 makes no claim ─────────────────── */

const GROUNDING_CLASSES = ['cite-grounded', 'cite-weak', 'cite-ungrounded'] as const;

describe('I-1 colour — tier 1 states its grounding, tier 2 states none', () => {
  /** One verified sentence (label 1) and one label reaching the reader only through its literal. */
  const both = async (): Promise<MarkdownBlock> =>
    mounted({
      text: `${ANCHORED} And a bare token [2] inline.`,
      citations: [mark(ANCHORED, 1, 0), mark(UNANCHORED, 2, 1)],
    });

  it('mints one mark of each tier, distinguishable without reading a colour', async () => {
    const el = await both();
    const refs = [...content(el).querySelectorAll<HTMLElement>('.cite-ref')];
    expect(refs.map((r) => r.textContent)).toEqual(['1', '2']);
    expect(refs.map((r) => r.dataset.citeTier)).toEqual(['sentence', 'source']);
    el.remove();
  });

  it('tier 1: exactly one grounding class, and the sentence it grounds is underlined', async () => {
    const el = await both();
    const tier1 = content(el).querySelector<HTMLElement>('[data-cite-tier="sentence"]')!;
    expect(GROUNDING_CLASSES.filter((c) => tier1.classList.contains(c))).toHaveLength(1);
    const spans = [...content(el).querySelectorAll('.cite-sentence')];
    expect(spans.map((s) => s.textContent)).toEqual([ANCHORED]);
    el.remove();
  });

  it('tier 2: NO grounding class, no underline, and a title that says what it asserts', async () => {
    const el = await both();
    const tier2 = content(el).querySelector<HTMLElement>('[data-cite-tier="source"]')!;
    // THE INVARIANT. A grounding class here would be a claim about a sentence nothing located; the
    // base rule's ink is what a mark that makes no such claim wears (the CSS half is asserted below).
    expect(GROUNDING_CLASSES.filter((c) => tier2.classList.contains(c))).toEqual([]);
    expect(tier2.className).toContain('cite-ref');
    expect(tier2.closest('.cite-sentence')).toBeNull();
    expect(tier2.title).toContain('the model cited this source');
    // …and the accessible NAME is untouched by the tier: it is a stable description of the control
    // (822 F6), so the two tiers announce identically and only the tooltip differs.
    expect(tier2.getAttribute('aria-label')).toBe('Citation 2 — open the cited passage');
    el.remove();
  });
});

describe('the tier ink is a POSITIVE class — absence is neutral, not strongest (869 §2.3)', () => {
  /** A rule's DECLARATIONS — comments stripped, because a comment naming the token it replaced is
   *  not a declaration and must not satisfy (or fail) an assertion about what the rule paints. */
  const ruleBody = (selector: string): string => {
    const m = new RegExp(`${selector.replace(/\./g, '\\.')}\\s*\\{([^}]*)\\}`).exec(
      markdownBlockCssText(),
    );
    expect(m, `${selector} must declare a rule`).toBeTruthy();
    return (m![1] as string).replace(/\/\*[\s\S]*?\*\//g, '');
  };

  it('the base rule paints the SOURCE tier, not the tint a verified mark earns', () => {
    const base = ruleBody('.cite-ref');
    expect(base).toContain('color: var(--md-cite-source-color');
    // The regression this exists to catch: putting the tint back on the base rule silently repaints
    // every tier-2 mark as the strongest tier, with no class and no test having changed.
    expect(base).not.toContain('--text-tint');
  });

  it('the grounded tier has a rule of its own, at the ink it always shipped', () => {
    expect(ruleBody('.cite-ref.cite-grounded')).toContain('--text-primary');
  });
});

/* ── VERBATIM CONTENT — neither pass may edit or annotate what the reader must read exactly ──── */

describe('code is not prose — the strip and the weave both stop at a fence (869 F1/F3)', () => {
  /** An inline code span and a fenced block, each carrying a subscript the language wrote. */
  const CODE_ANSWER = [
    'Use `argv[1]` here, and the model wrote [1] in prose.',
    '',
    '```js',
    'const x = items[2];',
    '```',
  ].join('\n');

  it('F1: leaves a subscript inside code alone while removing the same token from prose', async () => {
    // `sourceCount: 5` makes 1 and 2 both "resolvable", and nothing verified either — so the strip's
    // predicate is TRUE for `argv[1]` and `items[2]` as surely as for the prose `[1]`. What has to
    // stop it is the walker's skip, not the predicate: a frame is a statement about the answer's
    // prose, and a code sample is quoted, not asserted. Under 869's mute this wrapped a fragment of
    // the reader's own code in "not verified"; under 867's strip the same miss would DELETE it.
    const el = await mounted({ text: CODE_ANSWER, sourceCount: 5 });

    expect(content(el).querySelector('code')!.textContent).toContain('argv[1]');
    expect(content(el).querySelector('pre')!.textContent).toContain('items[2]');

    // …and the prose token in the SAME answer is gone, so this is a skip, not a dead pass.
    expect(muted(el)).toHaveLength(0);
    expect(text(el)).toContain('Use argv[1] here, and the model wrote in prose.');
    el.remove();
  });

  it('F3: a literal inside code is opaque to the weave — the mark stops at the prose it matched', async () => {
    // The flattened text the anchoring walk reads spans EVERY text node, so a `[1]` the reader is
    // meant to see verbatim looked exactly like one the model wrote. The literal-transparent
    // extension stepped over it and inserted the marker (and the sentence span) INSIDE the `<code>`
    // element — the renderer editing content whose whole contract is to be reproduced exactly.
    const el = await mounted({
      text: 'The kernel is shared `[1]` and more.',
      citations: [mark('The kernel is shared', 1, 0)],
    });

    const code = content(el).querySelector('code')!;
    expect(code.querySelectorAll('.cite-ref'), 'a mark woven into code').toHaveLength(0);
    expect(code.querySelectorAll('.cite-sentence'), 'a grounding span over code').toHaveLength(0);
    expect(code.textContent).toBe('[1]');

    // The literal survives (it is the reader's text), and the mark is the ONE digit outside it.
    expect([...(content(el).textContent ?? '').matchAll(/\[1\]/g)]).toHaveLength(1);
    const refs = [...content(el).querySelectorAll<HTMLElement>('.cite-ref')];
    expect(refs.map((r) => r.textContent)).toEqual(['1']);
    expect(refs[0]!.closest('pre, code')).toBeNull();
    el.remove();
  });
});

/* ── F2 — the SENTENCE span and the MARK are two positions, not one ──────────────────────────── */

describe('the cited sentence ends where the verifier stopped reading (869 F2)', () => {
  it('closes the span before a skipped literal, and lands the mark after it', async () => {
    // The literal-transparent extension moved ONE number, and the span rode along on it: the mark
    // correctly cleared `[9].`, and the `.cite-sentence` grew to cover the literal too — a span
    // saying "the cross-encoder scored this text" over text it never saw. 867 §7 removes the `[9]`
    // rather than muting it, and the span boundary is still the verifier's: what it wraps is the
    // scored sentence, with or without a literal sitting after it.
    const el = await mounted({
      text: 'The kernel is a shared substrate [9]. Next.',
      citations: [mark('The kernel is a shared substrate', 1, 0)],
      sourceCount: 9,
    });

    const sentence = content(el).querySelector<HTMLElement>('.cite-sentence')!;
    expect(sentence.textContent).toBe('The kernel is a shared substrate');

    // The unverified ref is GONE, and it took its own leading space so the period closes the word.
    expect(muted(el)).toHaveLength(0);
    expect(text(el)).toBe('The kernel is a shared substrate.1 Next.\n');

    // The mark keeps its 847 position: after the sentence's own period, past where the literal was.
    const ref = content(el).querySelector<HTMLElement>('.cite-ref')!;
    expect(ref.textContent).toBe('1');
    expect(ref.closest('.cite-sentence')).toBeNull();
    el.remove();
  });

  it('keeps a tier-2 mark out of the tier-1 sentence span when their literals are adjacent', async () => {
    // `word [1][2].` — the model wrote both refs together. Label 1 anchors a sentence, label 2
    // anchors nothing and reaches the reader through its literal (tier 2). The span used to run to
    // the MARK's index, which the extension had walked past both literals, so the tier-2 marker
    // replaced its literal INSIDE the tier-1 sentence span: a mark that states no grounding class
    // (869 C3) sitting inside a span that states one.
    const el = await mounted({
      text: 'word [1][2].',
      citations: [mark('word', 1, 0), mark(UNANCHORED, 2, 1)],
    });

    const tier2 = content(el).querySelector<HTMLElement>('[data-cite-tier="source"]')!;
    expect(tier2.textContent).toBe('2');
    expect(tier2.closest('.cite-sentence')).toBeNull();
    const tier1 = content(el).querySelector<HTMLElement>('[data-cite-tier="sentence"]')!;
    expect(tier1.textContent).toBe('1');
    expect(content(el).querySelector('.cite-sentence')!.textContent).toBe('word');

    // The rendered order is the §2.1e rule's, stated rather than assumed: the tier-2 marker
    // INHERITS its literal's place (869 C1 rule 3) while the tier-1 mark keeps the run's boundary
    // past the period, so the two do not group — and neither leaves a bracket behind.
    expect(text(el)).toBe('word2.1\n');
    for (const data of textNodes(el)) expect(data).not.toMatch(/[[\]]/);
    el.remove();
  });
});

/* ── F4b — the strip is a claim about the CURRENT inputs, so it must be undoable ───────────────── */

describe('every settle re-derives the strip from scratch (869 F4b, 867 §7)', () => {
  /** The cold-load shape: five sources known, the matcher's citations not attached yet. */
  const COLD: Mount = { text: 'Alpha [4] beta [2].', sourceCount: 5 };
  /** The same block once the evidence lands: label 4 verified, label 2 never was. */
  const EVIDENCE = (): MarkdownCitation[] => [mark(UNANCHORED, 4, 0)];

  it('restores a stripped literal and mints its mark when the citation that answers it arrives', async () => {
    const el = await mounted(COLD);
    // Both refs resolve and neither is verified yet, so the cold answer carries neither.
    expect(text(el)).toBe('Alpha beta.\n');

    el.citations = EVIDENCE();
    await settled(el);

    // THE INVARIANT, and what makes a strip harder than a mute: the removed text is not in the DOM
    // to be un-removed, so the settle has to re-derive the content from `text` before the tier-2
    // upgrade can find the literal at all. A strip that could not be undone would silently cost the
    // reader the very mark the newly-arrived evidence had earned.
    const refs = [...content(el).querySelectorAll<HTMLElement>('.cite-ref')];
    expect(refs.map((r) => r.textContent)).toEqual(['4']);
    expect(refs[0]!.dataset.citeTier).toBe('source');
    // `[2]` is still unverified, so it is still stripped — the pass re-derived, it did not just
    // restore the text.
    expect(text(el)).toBe('Alpha 4 beta.\n');
    expect(muted(el)).toHaveLength(0);
    el.remove();
  });

  it('F9: a block that receives its evidence late renders identically to one that had it at mount', async () => {
    // The cold-load parity check. Two arrival orders, one DOM — which is the whole content of
    // "the settled passes are a pure function of the current inputs". Before F4b the late arrivals
    // carried the first paint's edits forward, so the reloaded turn and the live one disagreed
    // about the same answer: the 561 P-A divergence shape, one layer down.
    const atMount = await mounted({ ...COLD, citations: EVIDENCE() });
    const expected = content(atMount).innerHTML;

    // (a) text first; the source list and the citations arrive together.
    const textFirst = await mounted({ text: COLD.text });
    textFirst.sourceCount = COLD.sourceCount!;
    textFirst.citations = EVIDENCE();
    await settled(textFirst);
    expect(content(textFirst).innerHTML).toBe(expected);

    // (b) the real cold load: the retrieval's source list is known at first paint, the matcher's
    // citations only at AgentDone — so the first settle DOES strip, and the second must take it
    // back. NON-VACUITY: that first settle removed both literals before the evidence landed.
    const countFirst = await mounted(COLD);
    expect(text(countFirst)).toBe('Alpha beta.\n');
    countFirst.citations = EVIDENCE();
    await settled(countFirst);
    expect(content(countFirst).innerHTML).toBe(expected);

    atMount.remove();
    textFirst.remove();
    countFirst.remove();
  });
});

/* ── F7 — the strip reclaims a space, never a line ────────────────────────────────────────────── */

describe('a stripped literal never costs the answer a line break (869 F7)', () => {
  it('leaves the newline before a line-leading literal, and takes the space after it', async () => {
    // `plain` format renders with `white-space: pre-wrap`, so this newline is a line the reader
    // sees. The gap-closing rule tested `/\s/`, which matches `\n` — a literal opening a line had
    // the break deleted and the two lines ran together. A break is not a gap the literal held open.
    const el = await mounted({
      format: 'plain',
      text: 'Alpha beta gamma\n[1] next line',
      citations: [mark('Alpha beta gamma', 1, 0)],
    });

    expect(text(el)).toContain('\n');
    expect(text(el)).toBe('Alpha beta gamma1\nnext line');
    expect([...content(el).querySelectorAll('.cite-ref')].map((r) => r.textContent)).toEqual(['1']);
    el.remove();
  });
});

/* ── F10 — the OTHER surface this guard is registered for ─────────────────────────────────────── */

describe('the producer gate the registered record→evidence projection owns (869 F10)', () => {
  // `evidence-fe-record-evidence` points its guard at THIS file, and until now this file never
  // imported the module — a registered surface with a green gate and no exercise of its contract.
  // `admittedMatches` is that contract: the 836 §4 verdict, applied to the match list before any
  // similarity reaches a grounding tier, so the sources panel and the answer's marks answer to one
  // producer decision (847 §2.3).
  const MATCHES: readonly CitationMatch[] = [
    { sentenceIndex: 0, sentenceText: 'The kernel is a shared substrate.', sourceIndex: 1, similarity: 0.91, parentDocId: 'f:/docs/1.md' },
  ];

  it('admits the cross-encoder and drops a producer that never verified anything', () => {
    expect(VERIFIED_SCORER).toBe('CROSS_ENCODER');
    expect(admittedMatches(MATCHES, VERIFIED_SCORER)).toEqual(MATCHES);
    // Dropped WHOLE, not handed over with the number intact: `sourceGrounding` reads `similarity`
    // straight into a tier, so there is no field a lexical score could survive in.
    expect(admittedMatches(MATCHES, 'LEXICAL_OVERLAP')).toEqual([]);
  });

  it('admits an envelope that predates the field — an absent fact, not an unknown producer', () => {
    expect(admittedMatches(MATCHES, null)).toEqual(MATCHES);
    expect(admittedMatches(MATCHES, undefined)).toEqual(MATCHES);
  });
});

/* ── R2 — the settled DOM re-derives from the inputs, in whatever order they arrive ───────────── */

describe('a decoration input that changes after the settle re-derives the content (869 R2)', () => {
  /** A verified first sentence, plus a second ref the model wrote as a literal of its own. */
  const ANSWER = `${ANCHORED} The model also wrote [2] here.`;
  const ONE = (): MarkdownCitation[] => [mark(ANCHORED, 1, 0)];
  const BOTH = (): MarkdownCitation[] => [mark(ANCHORED, 1, 0), mark(UNANCHORED, 2, 1)];

  it('R2a: mints the mark for evidence that lands after its literal was stripped', async () => {
    const el = await mounted({ text: ANSWER, citations: ONE(), sourceCount: 5 });
    // The cold-load shape: label 2 resolves to a source and nothing verified it, so it is removed.
    expect(text(el)).toBe(`${ANCHORED}1 The model also wrote here.\n`);

    el.citations = BOTH();
    await settled(el);

    // THE INVARIANT, and the half that was missing: the edit is taken back AND the mark it stood in
    // for is minted. Taking it back alone is what shipped — `decorateCitations` early-returns on a
    // root that already carries a mark, so the tier-2 upgrade never saw the literal. Under a strip
    // the failure is worse than a stale grey: the literal is not in the DOM to be re-read, so the
    // rebuild from `text` is the only thing that can hand it back.
    const late = content(el).querySelector<HTMLElement>('.cite-ref[data-cite-tier="source"]');
    expect(late, 'the late citation never got a mark').toBeTruthy();
    expect(late!.textContent).toBe('2');
    expect(muted(el)).toHaveLength(0);
    expect(text(el)).toBe(`${ANCHORED}1 The model also wrote 2 here.\n`);
    el.remove();
  });

  it('R2b: withdraws the marks and strips the restored literal when the evidence goes away', async () => {
    const el = await mounted({
      text: `${ANCHORED} The model wrote [1] too.`,
      citations: ONE(),
      sourceCount: 5,
    });
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(1);
    // The tier-2 dedupe removed the model's duplicate of a label the weave had already rendered.
    expect(text(el)).not.toContain('[1]');

    el.citations = [];
    await settled(el);

    // A mark and a `.cite-sentence` are claims about a citation set the block no longer holds, so
    // both go. The literal comes BACK with the rebuild and is then stripped again for the other
    // reason — label 1 still resolves against the five sources, and now nothing verifies it. Same
    // absent `[1]` on screen, arrived at from the opposite fact, which is why the marks are what
    // this asserts and not just the text.
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
    expect(content(el).querySelectorAll('.cite-sentence')).toHaveLength(0);
    expect(muted(el)).toHaveLength(0);
    expect(text(el)).toBe(`${ANCHORED} The model wrote too.\n`);
    el.remove();
  });

  it('is idempotent in the strong sense: identical inputs, identical DOM', async () => {
    const el = await mounted({ text: ANSWER, citations: BOTH(), sourceCount: 5 });
    const first = content(el).innerHTML;

    // An equal-but-DISTINCT array is a change as far as Lit is concerned, so this is a real second
    // rebuild — "same inputs, same DOM", whatever the DOM it replaced happened to be.
    el.citations = BOTH();
    await settled(el);
    expect(content(el).innerHTML).toBe(first);
    el.remove();
  });

  it('re-derives a `plain` answer through the same two facts render() states', async () => {
    // The rebuild has a format split of its own, so the verbatim format needs its own exercise: a
    // `plain` answer is the extract/summary/navigate shape, and its whitespace is the reader's
    // content (`white-space: pre-wrap`), not markdown to re-parse.
    const el = await mounted({
      format: 'plain',
      text: 'Alpha beta gamma\n  indented [2] line',
      citations: [mark('Alpha beta gamma', 1, 0)],
      sourceCount: 5,
    });
    expect(text(el)).toBe('Alpha beta gamma1\n  indented line');

    el.citations = [mark('Alpha beta gamma', 1, 0), mark(UNANCHORED, 2, 1)];
    await settled(el);

    expect(muted(el)).toHaveLength(0);
    // The indent is the reader's content and survives both the strip and the rebuild.
    expect(text(el)).toBe('Alpha beta gamma1\n  indented 2 line');
    el.remove();
  });

  it('keeps its rebuild across an unrelated re-render, and still yields to a real text change', async () => {
    // The Lit-cache assumption, asserted rather than trusted. `unsafeHTML` caches on the source
    // STRING: a re-render that leaves it unchanged does not touch the directive's DOM (so the
    // rebuild stands), while a changed one renders through the part markers the rebuild kept — the
    // one thing a manual `innerHTML` reset could have broken.
    const el = await mounted({ text: ANSWER, citations: BOTH(), sourceCount: 5 });
    el.citations = BOTH();
    await settled(el);
    const rebuilt = content(el).innerHTML;

    el.prose = true;
    await settled(el);
    expect(content(el).innerHTML).toBe(rebuilt);

    el.text = 'A different answer entirely.';
    await settled(el);
    expect(text(el).trim()).toBe('A different answer entirely.');
    expect(content(el).querySelectorAll('.cite-ref')).toHaveLength(0);
    el.remove();
  });
});

describe('F9 generalised — a first settle that ALREADY decorated is corrected too', () => {
  const TEXT = `${ANCHORED} The model wrote [2] and [4].`;
  const PARTIAL = (): MarkdownCitation[] => [mark(ANCHORED, 1, 0)];
  const FULL = (): MarkdownCitation[] => [mark(ANCHORED, 1, 0), mark(UNANCHORED, 4, 1)];

  it('matches a block that had the whole citation set at mount', async () => {
    const atMount = await mounted({ text: TEXT, citations: FULL(), sourceCount: 5 });
    const expected = content(atMount).innerHTML;

    const late = await mounted({ text: TEXT, citations: PARTIAL(), sourceCount: 5 });
    // NON-VACUITY: the first settle really did decorate — a mark stands and BOTH literals are gone
    // before the rest of the evidence lands. The original F9 fixture started from an undecorated
    // first settle, where the unwrap-only pass was enough; this one is the case it could not
    // correct, and under 867's strip the DOM no longer even shows that an edit was made.
    expect(content(late).querySelectorAll('.cite-ref')).toHaveLength(1);
    expect(text(late)).toBe(`${ANCHORED}1 The model wrote and.\n`);

    late.citations = FULL();
    await settled(late);
    expect(content(late).innerHTML).toBe(expected);

    atMount.remove();
    late.remove();
  });
});

describe('the highlight pass keeps its place ahead of the weave on a rebuilt tree (T19)', () => {
  afterEach(() => {
    resetHighlighterForTest();
  });

  it('re-highlights the fence the rebuild replaced and keeps the mark out of it', async () => {
    const answer = [ANCHORED, '', '```js', 'const other = 2;', '```', '', 'And [2] closes it.'].join(
      '\n',
    );
    await loadHighlighter();
    const el = await mounted({ text: answer, sourceCount: 5 });
    expect(content(el).querySelector('pre code .hljs-keyword'), 'the first settle').toBeTruthy();
    expect(text(el)).toContain('And closes it.');

    el.citations = [mark(ANCHORED, 1, 0)];
    await settled(el);

    // The rebuild discards the highlighted markup with everything else, so `updated()`'s ordering
    // has to hold on the rebuilt tree as well: highlight first (it rewrites a code block's
    // innerHTML wholesale), then weave into what it left.
    expect(
      content(el).querySelector('pre code .hljs-keyword'),
      'the rebuilt fence lost its highlighting',
    ).toBeTruthy();
    const refs = [...content(el).querySelectorAll<HTMLElement>('.cite-ref')];
    expect(refs.map((r) => r.textContent)).toEqual(['1']);
    expect(refs[0]!.closest('pre, code')).toBeNull();
    el.remove();
  });
});

/* ── R1 — the override case: the model's ref inside the sentence the verifier scored ────────── */

describe('the OVERRIDE case: the model cited one source mid-sentence, the verifier another (867 S3)', () => {
  it('removes the model ref from inside the scored sentence and leaves the span whole', async () => {
    // 869 rendered this as a mute NESTED in the grounding span — both statements true at once, the
    // outer "the cross-encoder scored this text", the inner "this ref was not verified". 867 §7 is
    // the owner's disposition of exactly that shape: the reader gets the scored sentence and the
    // verifier's mark, and the model's failed claim is not in the prose to be decoded. The span
    // itself does not move — F2's rule is about EXTENSION over a TRAILING literal, not containment.
    const el = await mounted({
      text: 'Alpha beta [2] gamma delta.',
      citations: [mark('Alpha beta [2] gamma delta.', 1, 0)],
      sourceCount: 5,
    });

    expect(muted(el)).toHaveLength(0);
    const sentence = content(el).querySelector<HTMLElement>('.cite-sentence')!;
    expect(sentence.textContent, 'the scored sentence, minus the claim').toBe(
      'Alpha beta gamma delta.',
    );

    const ref = content(el).querySelector<HTMLElement>('.cite-ref')!;
    expect(ref.textContent).toBe('1');
    expect(ref.dataset.citeTier).toBe('sentence');
    // The mark sits after the sentence's period, outside the span it labels (847 §2.1e).
    expect(ref.closest('.cite-sentence')).toBeNull();
    expect(text(el)).toBe('Alpha beta gamma delta.1\n');
    el.remove();
  });
});

/* ── R3 — a code span with no space before it ─────────────────────────────────────────────────── */

describe('a code span glued to the word it follows (869 F3, the no-space shape)', () => {
  it('stops the mark at the element edge and leaves the literal verbatim', async () => {
    const el = await mounted({ text: 'shared`[1]` more.', citations: [mark('shared', 1, 0)] });
    const code = content(el).querySelector('code')!;
    // THE GUARD (`!inCode` in the punctuation walk). Without it the walk crosses the `[` — the
    // model wrote no whitespace to stop it — and the mark is inserted BETWEEN `[` and `1`, inside
    // the one element whose contract is to be reproduced exactly (`[11]` on screen).
    expect(code.textContent, 'the reader\'s own code was edited').toBe('[1]');
    const ref = content(el).querySelector<HTMLElement>('.cite-ref')!;
    expect(ref.textContent).toBe('1');
    expect(ref.closest('pre, code'), 'a mark woven into code').toBeNull();
    expect(ref.nextElementSibling?.tagName, 'the mark stopped at the element edge').toBe('CODE');
    expect(text(el)).toBe('shared1[1] more.\n');
    el.remove();
  });
});

describe('the muted idiom is READ, not dimmed (869 §3.6)', () => {
  it('takes its ink from a bridgeable hook and declares no opacity', () => {
    const rule = /\.pseudo-cite\s*\{([^}]*)\}/.exec(markdownBlockCssText());
    expect(rule, 'the .pseudo-cite rule').toBeTruthy();
    const body = rule![1] as string;
    // `opacity: .7` was a second, invisible authority over the composite contrast: on the v3 window
    // it put the muted token at ~3.0:1 dark / ~2.7:1 light. A window cannot lift what a fixed
    // opacity multiplies, so the value moves to a hook the window can re-point.
    expect(body).not.toContain('opacity');
    expect(body).toContain('color: var(--md-pseudo-cite-color');
  });
});
