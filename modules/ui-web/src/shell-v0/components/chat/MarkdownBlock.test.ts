/**
 * Slice 497 — MarkdownBlock mend pass tests + Tempdoc 565 §3.C citation decoration.
 *
 * Tests the mendMarkdown function that auto-closes unclosed markdown
 * syntax during streaming, and the inline `[n]` citation decoration.
 *
 * @vitest-environment happy-dom
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FRAME_LATCH_FALLBACK_MS } from '../../primitives/frameLatch.js';
import {
  mendMarkdown,
  stripTrailingCitationBlock,
  MarkdownBlock as MarkdownBlockClass,
  type MarkdownBlock,
  type MarkdownCitation,
} from './MarkdownBlock.js';
import './MarkdownBlock.js';
import {
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../../state/selectedSource.js';
// Tempdoc 822 §3d — the density fixtures resolve through the REAL gate, not a hand-built citation
// list, so the test fails if the provenance split is ever undone upstream of the renderer.
import { claimsToCitations } from './citationResolve.js';
import type { Claim, RetrievalCitation } from './citationTypes.js';
// Tempdoc 847 T19 — the 846 highlight pass runs over the same root the citation weave writes into,
// and it arrives ASYNCHRONOUSLY, so the ordering is asserted rather than assumed.
import {
  highlightCodeBlocks,
  isHighlighterLoaded,
  loadHighlighter,
  resetHighlighterForTest,
} from '../markdown/markdownHighlight.js';

/**
 * Tempdoc 846 §2.3 — `styles` is an ARRAY now (the shared typography ramp + the shared code theme +
 * this component's own chat rules), so the assertions below read the flattened text rather than one
 * `CSSResult`. Same string, one join wider: a rule that moved to the shared sheet is still found,
 * which is the point — these assertions are about what the component RENDERS with, not about which
 * file a rule is typed in.
 */
function markdownBlockCssText(): string {
  const styles = MarkdownBlockClass.styles as unknown;
  const sheets = Array.isArray(styles) ? styles : [styles];
  return sheets.map((s) => (s as { cssText: string }).cssText).join('\n');
}

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function mark(
  sentenceText: string,
  similarity = 0.8,
  label = 1,
  // 847 §2.1d — the producer's sentence ordinal, required on a `Citation`. Every fixture using this
  // helper carries ONE citation, so the default is the answer's first sentence.
  sentenceIndex = 0,
): MarkdownCitation {
  return {
    sentenceText,
    similarity,
    sentenceIndex,
    label,
    detail: { parentDocId: 'f:/docs/x.md', startLine: 1, endLine: 5, startChar: 0, endChar: 0, excerpt: 'x' },
    hover: { excerpt: 'an excerpt', title: 'X', headingText: '' },
  };
}

describe('mendMarkdown', () => {
  it('returns text unchanged when no unclosed syntax', () => {
    const text = '# Hello\n\nThis is **bold** and `code`.\n\n```js\nconst x = 1;\n```\n';
    expect(mendMarkdown(text)).toBe(text);
  });

  it('closes an unclosed code fence with matching backticks', () => {
    const text = 'paragraph\n\n```javascript\nconst x = 1;\nmore code';
    const mended = mendMarkdown(text);
    expect(mended).toContain('```');
    expect(mended.match(/```/g)!.length).toBe(2);
  });

  it('closes an unclosed tilde fence', () => {
    const text = 'paragraph\n\n~~~python\nimport os';
    const mended = mendMarkdown(text);
    expect(mended).toContain('~~~');
    expect(mended.match(/~~~/g)!.length).toBe(2);
  });

  it('matches fence length for longer fences', () => {
    const text = 'paragraph\n\n````\ncode here';
    const mended = mendMarkdown(text);
    expect(mended.endsWith('````')).toBe(true);
  });

  it('does not close fences that are already paired', () => {
    const text = '```js\ncode\n```\n\nMore text.';
    expect(mendMarkdown(text)).toBe(text);
  });

  it('closes unclosed bold (**)', () => {
    const text = 'This is **bold text without closing';
    const mended = mendMarkdown(text);
    expect(mended).toBe(text + '**');
  });

  it('does not close bold when already paired', () => {
    const text = 'This is **bold** text.';
    expect(mendMarkdown(text)).toBe(text);
  });

  it('closes unclosed italic (*)', () => {
    const text = 'This is *italic without closing';
    const mended = mendMarkdown(text);
    expect(mended).toBe(text + '*');
  });

  it('closes unclosed inline code (`)', () => {
    const text = 'This has `unclosed code';
    const mended = mendMarkdown(text);
    expect(mended).toBe(text + '`');
  });

  it('does not close inline markers when inside a code fence', () => {
    const text = '```\nThis has **unclosed bold inside code';
    const mended = mendMarkdown(text);
    // Should close the fence but NOT close the bold (it's inside code)
    expect(mended).toContain('```\nThis has **unclosed bold inside code\n```');
    expect(mended.match(/\*\*/g)!.length).toBe(1);
  });

  it('handles empty text', () => {
    expect(mendMarkdown('')).toBe('');
  });

  it('handles text with no markdown', () => {
    const text = 'Just plain text with no special characters.';
    expect(mendMarkdown(text)).toBe(text);
  });
});

describe('stripTrailingCitationBlock (565 §13.8 — UI is the source authority)', () => {
  it('strips a trailing scored "Citations:" list, preserving the answer + inline marks', () => {
    const text =
      'The kernel is a shared substrate [1]. It composes into governed projections [2].\n\n' +
      'Citations: [1] AI Architecture (score: 1.00)\n[2] Governed Projections (score: 0.91)';
    expect(stripTrailingCitationBlock(text)).toBe(
      'The kernel is a shared substrate [1]. It composes into governed projections [2].',
    );
  });

  it('strips a "Sources:" and a "References:" heading too (case-insensitive)', () => {
    expect(stripTrailingCitationBlock('Answer text [1].\n\nSources:\n- [1] Doc A')).toBe('Answer text [1].');
    expect(stripTrailingCitationBlock('Answer text [1].\n\nREFERENCES:\n[1] Doc A')).toBe('Answer text [1].');
  });

  it('strips an ATX-heading or bold-wrapped citation list', () => {
    expect(stripTrailingCitationBlock('Body [1].\n\n## Citations\n[1] Doc')).toBe('Body [1].');
    expect(stripTrailingCitationBlock('Body [1].\n\n**Sources:**\n[1] Doc')).toBe('Body [1].');
  });

  it('does NOT strip a heading that lacks any [n] reference (not a citation list)', () => {
    const text = 'See the design.\n\nSources of truth:\nThe single authority is the projection.';
    expect(stripTrailingCitationBlock(text)).toBe(text);
  });

  it('869 — does NOT strip a PROSE paragraph that merely starts with "Citation…" and cites later', () => {
    // Live finding (869 §4): this shape matched as a bibliography and deleted half the answer.
    const text =
      'The "sourced" state renders with a secondary tone [5].\n\n' +
      'Citation verification scores supplied passage text instead of re-fetching, and this is a ' +
      'Worker-facing seam [4]. The Worker already knows these states [2].\n\n' +
      'The pane lets a reader check a claim [1].';
    expect(stripTrailingCitationBlock(text)).toBe(text);
    const sources =
      'Body [1].\n\nSource documents are the authority here [2]; the model only summarizes them.';
    expect(stripTrailingCitationBlock(sources)).toBe(sources);
  });

  it('does NOT strip a mid-prose "Sources:" sentence (no trailing-to-EOF list shape)', () => {
    const text = 'Sources: the index and the model. We fuse them [1], then rerank. Final answer here.';
    expect(stripTrailingCitationBlock(text)).toBe(text);
  });

  it('leaves text with inline marks but no trailing list untouched', () => {
    const text = 'The answer cites two passages [1] and [2] inline, with no appended list.';
    expect(stripTrailingCitationBlock(text)).toBe(text);
  });

  it('handles empty / undefined-ish input', () => {
    expect(stripTrailingCitationBlock('')).toBe('');
  });
});

describe('MarkdownBlock citation decoration (565 §3.C)', () => {
  it('weaves an inline [n] mark for a matched sentence', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The kernel is a shared substrate. A second sentence.';
    el.citations = [mark('The kernel is a shared substrate.')];
    document.body.appendChild(el);
    await settle(el);
    const marks = el.shadowRoot?.querySelectorAll('.cite-ref');
    expect(marks?.length).toBe(1);
    expect(marks?.[0]?.textContent).toBe('1');
    el.remove();
  });

  it('skips gracefully when the cited sentence is absent from the answer', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'Totally different content here.';
    el.citations = [mark('A sentence that does not appear at all.')];
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelectorAll('.cite-ref').length ?? 0).toBe(0);
    el.remove();
  });

  it('matches across markdown emphasis markers', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The **kernel** is a shared substrate.';
    el.citations = [mark('The **kernel** is a shared substrate.')];
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelectorAll('.cite-ref').length).toBe(1);
    el.remove();
  });

  // Tempdoc 565 §15.C fix — a cited sentence that crosses inline markup now still tier-colors its
  // text runs (multi-node body wrap), instead of silently getting the mark only.
  it('tier-colors the cited-sentence body across inline markup (multi-node wrap)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The **kernel** is a shared substrate.';
    el.citations = [mark('The **kernel** is a shared substrate.', 0.8)]; // 0.8 → high → 'grounded'
    document.body.appendChild(el);
    await settle(el);
    const wraps = el.shadowRoot?.querySelectorAll('.cite-sentence.grounding-grounded');
    // the text runs flanking the <strong> are each wrapped — more than one, all grounded-tier
    expect((wraps?.length ?? 0)).toBeGreaterThan(1);
    // the <strong> markup survives intact (no cross-element corruption)
    expect(el.shadowRoot?.querySelector('.md-content strong')?.textContent).toBe('kernel');
    el.remove();
  });

  it('dispatches citation-select with the deep-link detail on click', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'Click target sentence.';
    el.citations = [mark('Click target sentence.')];
    let detail: { parentDocId?: string } | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent).detail as { parentDocId?: string };
    });
    document.body.appendChild(el);
    await settle(el);
    (el.shadowRoot?.querySelector('.cite-ref') as HTMLElement | null)?.click();
    expect(detail).toBeTruthy();
    expect(detail!.parentDocId).toBe('f:/docs/x.md');
    el.remove();
  });
});

// Tempdoc 577 §2.12 Move 3 — pseudo-citation neutralization in an ungrounded answer.
describe('MarkdownBlock ungrounded frame — citation-shaped text is neutralized', () => {
  it('wraps model-authored [n]/(n) markers in a muted, non-interactive span when frame=ungrounded', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'JustSearch uses a 4-tier strategy [1] and chaos testing (2).';
    el.frame = 'ungrounded';
    document.body.appendChild(el);
    await settle(el);
    const pseudo = el.shadowRoot?.querySelectorAll('.pseudo-cite');
    expect(pseudo?.length).toBe(2); // [1] and (2)
    expect(Array.from(pseudo ?? []).map((s) => s.textContent)).toEqual(['[1]', '(2)']);
    // They are NOT the real clickable cite-refs — no accent superscript, no handlers.
    expect(el.shadowRoot?.querySelectorAll('.cite-ref').length ?? 0).toBe(0);
    el.remove();
  });

  it('leaves citation-shaped text untouched in a grounded answer (default frame)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'A grounded answer [1].';
    // default frame = 'grounded' — no neutralization.
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelectorAll('.pseudo-cite').length ?? 0).toBe(0);
    el.remove();
  });
});

// ---------------------------------------------------------------------------
// Tempdoc 687 R3a — literal "[n]" token normalization (one citation notation)
// + R1c grounding-mark inversion (P2: mark the exception, not the rule).
// ---------------------------------------------------------------------------
describe('MarkdownBlock 687 R3a — literal [n] normalization', () => {
  async function mounted(text: string, cites = [mark('The kernel is a shared substrate.')]) {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = text;
    el.citations = cites;
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    return el;
  }

  it('strips a literal [n] whose citation already carries a rendered marker', async () => {
    const el = await mounted('The kernel is a shared substrate. [1] More prose.');
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1); // the woven marker only
    expect(content.textContent).not.toContain('[1]');
    el.remove();
  });

  it('upgrades a literal [n] to a marker when the sentence match failed', async () => {
    // sentenceText will not match, so no woven marker — the literal token becomes the marker.
    const el = await mounted('Unmatched prose with a bare token [1] inline.', [
      mark('A sentence that does not appear at all.'),
    ]);
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);
    expect(content.textContent).not.toContain('[1]');
    el.remove();
  });

  it('leaves [n] untouched inside code and when no citation matches the number', async () => {
    const el = await mounted('Use `arr[1]` in code. A quote says [7] here. The kernel is a shared substrate.');
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelector('code')!.textContent).toContain('arr[1]');
    expect(content.textContent).toContain('[7]'); // only 1 citation exists — [7] is not a citation
    el.remove();
  });
});

describe('MarkdownBlock 687 R1c — grounding-mark inversion', () => {
  it('a high-similarity sentence gets the grounded class (whose CSS is now unmarked)', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The kernel is a shared substrate.';
    el.citations = [mark('The kernel is a shared substrate.', 0.9)];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const span = el.renderRoot.querySelector('.cite-sentence');
    expect(span?.className).toContain('grounding-grounded');
    el.remove();
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 822 §3c + §3d — the tier palette and THE UNDERLINE-DENSITY ACCEPTANCE TEST.
 *
 * §3d makes 687 R1c's falsifier executable: the component's own principle is "mark the exception,
 * not the rule", and the gap report measured 98.5 % of body characters underlined. X = 25 % — one
 * sentence in four on a typical 4-8 sentence answer, the largest density at which "look at the
 * marked one" is still an instruction. The bound is NOT a cap on the renderer: the adversarial
 * fixture below proves a genuinely weak answer still renders weak everywhere.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */

/** The X of §3d — a design bound on a fixture, not a claim about the live score distribution. */
const MAX_UNDERLINE_DENSITY = 0.25;

const DENSITY_SOURCES: RetrievalCitation[] = [0, 1, 2].map((i) => ({
  parentDocId: `docs/s${i}.md`,
  chunkIndex: i,
  chunkTotal: 3,
  startChar: 0,
  endChar: 80,
  score: 0.8,
  excerpt: `passage ${i}`,
  startLine: 1 + i,
  endLine: 4 + i,
  headingText: 'Notes',
  headingLevel: 2,
}));

/**
 * The realistic fixture of §3d: a 6-sentence answer the cross-encoder verified on five sentences
 * (0.81, 0.74, 0.69, 0.63, 0.55) while the streaming lexical matcher ALSO fired low-overlap deltas —
 * three on sentences the cross-encoder had already scored (the doubly-matched case, where the old
 * `Math.max` let the wrong scale win) and one on the sentence it never verified. This is the shape
 * the gap report measured, minus the defect.
 *
 * DEVIATION, stated: §3d's prose asks for "4 lexical-only deltas on sentences the matcher did not
 * verify" while also specifying 5 verified claims in a 6-sentence answer — only one such sentence
 * exists. Both routes a lexical score could take are therefore exercised instead (doubly-matched and
 * never-verified), which is strictly the stronger fixture.
 */
const REALISTIC_SENTENCES = [
  'The kernel is a shared substrate for every governed projection.',
  'Its catalogs are generated from one schema and never hand-edited.',
  'The worker owns all index IO and the head never touches Lucene.',
  'Search analysis stays locale invariant across the whole corpus.',
  'Reranking runs on the cross encoder before the answer is drafted.',
  'Operators can tune the retrieval budget from the settings surface.',
];

/** Verified similarity per sentence index; a missing entry = the matcher never verified it. */
const REALISTIC_VERIFIED = new Map<number, number>([
  [0, 0.81],
  [1, 0.74],
  [2, 0.69],
  [3, 0.63],
  [4, 0.55],
]);
/** Lexical word-overlap ratios that arrived as `rag.citation_delta` — never a tier input. */
const REALISTIC_LEXICAL = new Map<number, number>([
  [1, 0.1],
  [2, 0.18],
  [3, 0.25],
  [5, 0.33],
]);

function realisticClaims(): Claim[] {
  return REALISTIC_SENTENCES.map((sentenceText, i) => ({
    sentenceIndex: i,
    sentenceText,
    verifiedScore: REALISTIC_VERIFIED.get(i) ?? null,
    lexicalScore: REALISTIC_LEXICAL.get(i) ?? 0,
    verifiedRefs: [i % 3],
    lexicalRefs: [],
  }));
}

/**
 * The pre-822 merge, reproduced exactly: ONE score per claim, `Math.max` across the two scales. Used
 * to measure the same fixture's density BEFORE the gate, so the test states what it fixed rather
 * than merely passing.
 */
function preGateClaims(): Claim[] {
  return realisticClaims().map((c) => ({
    ...c,
    verifiedScore: Math.max(c.verifiedScore ?? 0, c.lexicalScore),
  }));
}

async function renderAnswer(sentences: readonly string[], claims: Claim[]): Promise<MarkdownBlock> {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  el.text = sentences.join(' ');
  el.citations = claimsToCitations(claims, DENSITY_SOURCES);
  document.body.appendChild(el);
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  return el;
}

/**
 * The gap report's measure, made runnable: the fraction of the answer BODY's characters that carry a
 * dotted underline. Only `grounding-weak` and `grounding-ungrounded` draw one (`grounding-grounded`
 * is `border-bottom: none` since 687 R1c). The `[n]` superscripts are excluded from the denominator:
 * they are marks, not body prose.
 */
function underlineDensity(el: MarkdownBlock): { underlined: number; body: number; ratio: number } {
  const content = el.renderRoot.querySelector('.md-content')!;
  const markChars = [...content.querySelectorAll('.cite-ref')].reduce(
    (n, m) => n + (m.textContent?.length ?? 0),
    0,
  );
  const body = (content.textContent?.length ?? 0) - markChars;
  const underlined = [
    ...content.querySelectorAll('.cite-sentence.grounding-weak, .cite-sentence.grounding-ungrounded'),
  ].reduce((n, s) => n + (s.textContent?.length ?? 0), 0);
  return { underlined, body, ratio: body === 0 ? 0 : underlined / body };
}

describe('MarkdownBlock 822 §3d — the underline-density acceptance test', () => {
  it('realistic answer: exactly one weak span, no ungrounded span, and underlines ≤ 25 % of the body', async () => {
    const el = await renderAnswer(REALISTIC_SENTENCES, realisticClaims());
    const content = el.renderRoot.querySelector('.md-content')!;

    // (1) the 0.55 sentence is the one weak span; nothing renders ungrounded.
    expect(content.querySelectorAll('.cite-sentence.grounding-weak').length).toBe(1);
    expect(content.querySelectorAll('.cite-sentence.grounding-ungrounded').length).toBe(0);
    expect(
      content.querySelector('.cite-sentence.grounding-weak')?.textContent?.trim(),
    ).toBe(REALISTIC_SENTENCES[4]);

    // (2) the density bound.
    const { ratio } = underlineDensity(el);
    expect(ratio).toBeLessThanOrEqual(MAX_UNDERLINE_DENSITY);

    // (3) no mark traces to a lexical-only claim: five verified claims ⇒ five marks, and the
    // never-verified sentence carries neither a mark nor a tier wrap.
    expect(content.querySelectorAll('.cite-ref').length).toBe(5);
    const lexicalOnly = REALISTIC_SENTENCES[5]!;
    const wrapped = [...content.querySelectorAll('.cite-sentence')].map((s) => s.textContent?.trim());
    expect(wrapped).not.toContain(lexicalOnly);
    expect(content.textContent).toContain(lexicalOnly); // the prose is intact — it is simply unmarked
    el.remove();
  });

  it('the SAME fixture breached the bound before the gate — the density is the defect being fixed', async () => {
    const el = await renderAnswer(REALISTIC_SENTENCES, preGateClaims());
    const { ratio } = underlineDensity(el);
    // With one score per claim and `Math.max` across the two scales, the never-verified sentence's
    // 0.33 word overlap reads `ungrounded` and the lexical deltas underline prose the cross-encoder
    // never backed. The bound fails — which is exactly what 687 R1c's falsifier is for.
    expect(ratio).toBeGreaterThan(MAX_UNDERLINE_DENSITY);
    el.remove();
  });

  it('adversarial answer: an all-weak answer marks EVERY sentence — the bound is not a blanket cap', async () => {
    const sentences = [
      'The first claim rests on a passage that only partly supports it.',
      'The second claim is likewise thin against its cited passage.',
      'A third statement sits just above the matcher cutoff as well.',
      'The fourth is no better supported than the three before it.',
      'A fifth assertion also scores barely over the cutoff line.',
      'The sixth and last claim is equally weakly grounded.',
    ];
    const claims: Claim[] = sentences.map((sentenceText, i) => ({
      sentenceIndex: i,
      sentenceText,
      verifiedScore: [0.52, 0.54, 0.55, 0.56, 0.57, 0.58][i]!,
      lexicalScore: 0,
      verifiedRefs: [i % 3],
      lexicalRefs: [],
    }));
    const el = await renderAnswer(sentences, claims);
    const content = el.renderRoot.querySelector('.md-content')!;

    expect(content.querySelectorAll('.cite-sentence.grounding-weak').length).toBe(6);
    const { ratio } = underlineDensity(el);
    // A genuinely weak answer must LOOK weak: the honest render exceeds X, and no render-time rule
    // collapses it (822 §3d rejects B11 — a density cap would hide a miscalibrated scorer).
    expect(ratio).toBeGreaterThan(MAX_UNDERLINE_DENSITY);
    expect(ratio).toBeGreaterThan(0.9);
    el.remove();
  });

  it('a lexical-only answer renders as plain prose — no marks, no underlines, no counts', async () => {
    const sentences = REALISTIC_SENTENCES.slice(0, 3);
    const claims: Claim[] = sentences.map((sentenceText, i) => ({
      sentenceIndex: i,
      sentenceText,
      verifiedScore: null,
      // Word overlap can reach 1.0 on a short passage — under the pre-822 merge that read
      // `grounded`, the STRONGEST tier, from a coverage ratio.
      lexicalScore: [0.33, 0.75, 1][i]!,
      verifiedRefs: [0],
      lexicalRefs: [],
    }));
    const el = await renderAnswer(sentences, claims);
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(0);
    expect(content.querySelectorAll('.cite-sentence').length).toBe(0);
    expect(underlineDensity(el).ratio).toBe(0);
    el.remove();
  });
});

describe('MarkdownBlock 822 §3c — the ungrounded mark has its own color', () => {
  it('a claim below the cutoff renders a mark classed cite-ungrounded', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'The kernel is a shared substrate.';
    el.citations = [mark('The kernel is a shared substrate.', 0.2)];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const ref = el.renderRoot.querySelector('.cite-ref');
    expect(ref?.className).toContain('cite-ungrounded');
    el.remove();
  });

  /**
   * The tier presentations remain distinct: grounded ≠ weak ≠ ungrounded. Asserted on
   * the stylesheet text rather than on computed values because happy-dom does not cascade shadow
   * styles — the computed-value check runs live in the browser (§5.4 S2). Before 822 §3c there was
   * no `cite-ungrounded` rule at all and the WEAKEST tier inherited the STRONGEST tier's blue.
   */
  it('declares a distinct colour and weight presentation for all four mark tiers', () => {
    const cssText = markdownBlockCssText();
    const colorOf = (selector: string): string => {
      const m = new RegExp(`${selector.replace(/[.]/g, '\\.')}\\s*\\{([^}]*)\\}`).exec(cssText);
      expect(m, `${selector} must declare a rule`).not.toBeNull();
      const c = /color:\s*([^;]+);/.exec(m![1]!);
      expect(c, `${selector} must declare a color`).not.toBeNull();
      return c![1]!.trim();
    };
    // The two subdued tiers are opt-in tokens now (the contrast repair: a wash painted behind the
    // glyph changes what its colour is measured against, so a window that paints one also lifts the
    // ink). What identifies the tier is therefore the token it ULTIMATELY falls back to — the
    // shipped ink — not the outermost name.
    const shippedInk = (selector: string): string => {
      const names = [...colorOf(selector).matchAll(/var\((--[\w-]+)/g)].map((m) => m[1] as string);
      expect(names.length, `${selector} must name a token`).toBeGreaterThan(0);
      return names[names.length - 1] as string;
    };
    // Tempdoc 869 C3 re-bases this from `.cite-ref` to the tier's OWN rule: the base rule no longer
    // carries the strongest tier's ink for a mark to fall through to, because falling through was
    // the defect (a tier-2 mark states no grounding class and used to inherit the tint anyway).
    const weightOf = (selector: string): string => {
      const body = new RegExp(`${selector.replace(/[.]/g, '\\.')}\\s*\\{([^}]*)\\}`).exec(cssText)![1]!;
      return /font-weight:\s*([^;]+);/.exec(body)?.[1]?.trim()
        ?? (selector === '.cite-ref' ? '400' : weightOf('.cite-ref'));
    };
    const presentationOf = (selector: string): string => `${shippedInk(selector)}/${weightOf(selector)}`;
    const grounded = presentationOf('.cite-ref.cite-grounded');
    const weak = presentationOf('.cite-ref.cite-weak');
    const ungrounded = presentationOf('.cite-ref.cite-ungrounded');
    expect(weightOf('.cite-ref.cite-grounded')).toBe('500');
    expect(new Set([grounded, weak, ungrounded]).size).toBe(3);
    // Tempdoc 869 F6 — FOUR presentations, because there are four things a mark can be, and the SOURCE tier
    // (the base rule, worn by a tier-2 mark that states no grounding class) is one of them. C3's
    // first cut gave the base rule `--text-secondary`, which is `.cite-weak`'s own shipped ink: in
    // any window that re-points neither hook — the shipped shell — a mark asserting "the model
    // named this source" and a mark asserting "the verifier scored this sentence WEAKLY" resolved
    // to one colour. Moving the tint off the base rule is only half the inversion; the other half
    // is that what falls through must not collide with a tier that states itself. The neutral
    // source and grounded tiers share primary ink but differ by weight (600 vs 500).
    const sourceTier = presentationOf('.cite-ref');
    expect(new Set([sourceTier, grounded, weak, ungrounded]).size).toBe(4);
    // The warning role's TEXT member — an `--accent-*` fill token is not a text color, and sv3
    // bridges `--text-warning` and `--accent-warning` to one `--warning-foreground`.
    expect(shippedInk('.cite-ref.cite-ungrounded')).toBe('--text-warning');
    expect(shippedInk('.cite-ref.cite-weak')).toBe('--text-secondary');
    // …and the opt-in knobs are the cite vocabulary's own, so a window re-points the INK without
    // touching `--text-secondary` / `--text-warning`, which dress half the renderer besides.
    expect(colorOf('.cite-ref.cite-weak')).toBe('var(--md-cite-weak-color, var(--text-secondary))');
    expect(colorOf('.cite-ref.cite-ungrounded')).toBe(
      'var(--md-cite-ungrounded-color, var(--text-warning))',
    );
  });

  it('keeps the mark palette and the sentence-body palette saying the same thing', () => {
    const cssText = markdownBlockCssText();
    // The body channel's weakest tier is already the warning role; the mark now agrees with it.
    expect(cssText).toContain('1px dotted var(--accent-warning)');
    expect(cssText).toMatch(
      /\.cite-ref\.cite-ungrounded\s*\{\s*color:\s*var\(--md-cite-ungrounded-color, var\(--text-warning\)\);/,
    );
  });

  it('gives the mark a >=24px hit area WITHOUT resizing the glyph (2.5.8; live audit D4)', () => {
    const cssText = markdownBlockCssText();
    const ruleBody = (selector: string): string => {
      const m = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(cssText);
      expect(m, `${selector} must declare a rule`).not.toBeNull();
      return m![1] as string;
    };
    // Measured 13 x 16 CSS px, under the WCAG 2.2 2.5.8 floor in both axes. The floor is carried by
    // an overlay centred on the glyph, scoped to its own rule body so a `24px` elsewhere in the
    // sheet cannot satisfy this.
    const after = ruleBody('\\.cite-ref::after');
    expect(after).toMatch(/width:\s*24px/);
    expect(after).toMatch(/height:\s*24px/);
    expect(after).toMatch(/position:\s*absolute/);
    expect(after).toMatch(/transform:\s*translate\(-50%, ?-50%\)/);
    // …anchored by the mark, which needs a containing block and nothing else: `position: relative`
    // on an inline box moves no pixel.
    expect(ruleBody('\\.cite-ref')).toMatch(/position:\s*relative/);
    // The GLYPH is untouched. A padding or min-size fix would have grown the superscript, and a
    // mark's species (a verified `.cite-ref` vs an inert `.pseudo-cite`) is read off its size and
    // ink — so growing the ink would change what the mark CLAIMS, not just how big it is.
    expect(ruleBody('\\.cite-ref')).not.toMatch(/min-width|min-height/);
    expect(ruleBody('\\.cite-ref')).toMatch(/font-size:\s*var\(--font-size-xs\)/);
    // The overlay is invisible: it must not paint over the selected mark's own wash.
    expect(after).not.toMatch(/background|box-shadow|border(?!-)/);
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 822 citation-mark presentation — SELECTION PAINTS SURFACE, THE TIER KEEPS THE INK.
 *
 * Nothing in this repo asserted `.cite-selected` before these tests, which is how F2 survived: the
 * selected rule set `color` at the same specificity as `.cite-weak` / `.cite-ungrounded` and later
 * in source, so clicking the amber "not supported" numeral repainted it and HID that it was
 * unsupported — at the exact moment the reader clicked to check. The state was never entered by any
 * harness, so no test, screenshot or audit could see it.
 *
 * These run against COMPUTED style, not the stylesheet text: the cascade order is the defect, and a
 * source-level assertion cannot see cascade order. happy-dom resolves inherited custom properties
 * into a shadow tree, so the tier inks are fed from the host as literal colours below.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */

/** Resolved tier inks (design §2's computed sRGB set), fed as literals so `color` computes. */
const INK = {
  '--text-tint': 'rgb(79, 162, 255)',
  '--text-secondary': 'rgb(160, 160, 160)',
  '--text-warning': 'rgb(255, 176, 0)',
  '--accent-tint': 'rgb(0, 204, 178)',
  '--accent-on-tint': 'rgb(0, 35, 28)',
} as const;

const WEAK = 0.55; // → groundingClass 'weak'
const UNGROUNDED = 0.2; // → groundingClass 'ungrounded'

function citeAt(sentenceText: string, similarity: number, label: number, docId: string): MarkdownCitation {
  return {
    sentenceText,
    similarity,
    // These fixtures pair one sentence with one source, in order, so the label's 0-based twin IS the
    // sentence ordinal (847 §2.1d).
    sentenceIndex: label - 1,
    label,
    detail: { parentDocId: docId, startLine: 1, endLine: 5, startChar: 0, endChar: 0, excerpt: 'x' },
    hover: { excerpt: 'an excerpt', title: 'X', headingText: '' },
  };
}

async function mountCites(
  text: string,
  citations: MarkdownCitation[],
  vars: Readonly<Record<string, string>> = {},
): Promise<MarkdownBlock> {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  for (const [k, v] of Object.entries({ ...INK, ...vars })) el.style.setProperty(k, v);
  el.text = text;
  el.citations = citations;
  document.body.appendChild(el);
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  return el;
}

const ONE = 'The kernel is a shared substrate.';
const TWO = 'The worker owns all index IO.';
const DOC_A = 'f:/docs/a.md';
const DOC_B = 'f:/docs/b.md';

/** The horizontal box the mark occupies beyond its glyphs. happy-dom has no layout engine, so an
 *  `offsetWidth` comparison is vacuously 0 === 0; padding IS the whole width delta between the two
 *  states (same text, same font, same margin, no border), which is what F5 is about. */
function padX(el: Element): number {
  const cs = getComputedStyle(el);
  return parseFloat(cs.paddingLeft || '0') + parseFloat(cs.paddingRight || '0');
}

describe('822 F2 — a selected mark keeps its grounding tier', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  /**
   * The tier ink each subdued rule falls back to, read off the sheet.
   *
   * WHY this is not `getComputedStyle`: the contrast repair made both subdued tiers opt-in
   * (`var(--md-cite-<tier>-color, var(--text-…))`), and happy-dom ABANDONS any declaration whose
   * value carries a `var()` fallback — probed at every shape, including a flat literal fallback and
   * an indirection through a second custom property. So the two halves of T1/T2 split: the ink is
   * pinned at source, and the invariant that selection does NOT change it stays computed, which is
   * where its whole power lay — a `color` restored to `.cite-selected` is a flat declaration that
   * happy-dom resolves, so the equality still breaks on exactly the F2 regression.
   */
  const shippedInk = (selector: string): string => {
    const cssText = markdownBlockCssText();
    const rule = new RegExp(`${selector.replace(/\./g, '\\.')}\\s*\\{([^}]*)\\}`).exec(cssText);
    const decl = /color:\s*([^;]+);/.exec(rule?.[1] ?? '')?.[1];
    const names = [...(decl ?? '').matchAll(/var\((--[\w-]+)/g)].map((m) => m[1] as string);
    expect(names.length, `${selector} must declare a color naming a token`).toBeGreaterThan(0);
    return names[names.length - 1] as string;
  };

  it('T1: a WEAK mark computes the same color selected as unselected', async () => {
    const el = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)]);
    const ref = el.renderRoot.querySelector('.cite-ref') as HTMLElement;
    expect(ref.className).toContain('cite-weak');
    // NON-VACUITY, the half happy-dom cannot compute: the weak rule exists and its shipped ink is
    // the secondary role — not the grounded tier's blue, which is what deleting this rule would
    // leave a weak mark wearing, and the failure the computed equality below could not tell apart.
    // (869 C3 moved that blue off the base rule and onto `.cite-ref.cite-grounded`; the comparison
    // is against the tier it must not be confused with, which is what it always meant.)
    expect(shippedInk('.cite-ref.cite-weak')).toBe('--text-secondary');
    expect(shippedInk('.cite-ref.cite-weak')).not.toBe(shippedInk('.cite-ref.cite-grounded'));
    const resting = getComputedStyle(ref).color;

    setSelectedSource(sourceKey(DOC_A, 1));
    // The state really was entered AND the selected rule really applied — otherwise the equality
    // below is vacuous. `padX` moving is that rule's own declaration computing. Its FILL is not
    // readable here for the same reason as the ink — the frozen-defaults test below covers the fill
    // at source level, and the design's §7 live check covers it in a real browser.
    expect(ref.classList.contains('cite-selected')).toBe(true);
    expect(padX(ref)).toBeGreaterThan(0);

    // THE HEADLINE. Before the fix this read `--accent-on-tint` (#00231c): the honesty tier was
    // erased by the selection fill. A `color` back in `.cite-selected` is a flat declaration, so
    // happy-dom computes it and this equality still fails the moment F2 returns.
    expect(getComputedStyle(ref).color).toBe(resting);
    el.remove();
  });

  it('T2: an UNGROUNDED mark keeps its amber selected', async () => {
    const el = await mountCites(ONE, [citeAt(ONE, UNGROUNDED, 1, DOC_A)]);
    const ref = el.renderRoot.querySelector('.cite-ref') as HTMLElement;
    expect(ref.className).toContain('cite-ungrounded');
    expect(shippedInk('.cite-ref.cite-ungrounded')).toBe('--text-warning');
    // 869 C3, as above: the tier it must not be confused with is the GROUNDED one, which now
    // states itself instead of being the base rule an unclassed mark fell through to.
    expect(shippedInk('.cite-ref.cite-ungrounded')).not.toBe(shippedInk('.cite-ref.cite-grounded'));
    const resting = getComputedStyle(ref).color;

    setSelectedSource(sourceKey(DOC_A, 1));
    expect(ref.classList.contains('cite-selected')).toBe(true);
    expect(getComputedStyle(ref).color).toBe(resting);
    el.remove();
  });

  it('keeps the hover underline on the selected mark (F8 — no text-decoration: none)', () => {
    const cssText = markdownBlockCssText();
    const rule = /\.cite-ref\.cite-selected\s*\{([^}]*)\}/.exec(cssText);
    expect(rule).not.toBeNull();
    expect(rule![1]).not.toMatch(/text-decoration/);
    expect(rule![1]).not.toMatch(/(^|[^-])color\s*:/);
  });
});

describe('822 F5 — selecting a citation does not reflow the prose', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  it('T3: shipped default reserves nothing (selection widens); the v3 opt-in makes the two equal', async () => {
    // Shipped: no `--md-cite-pad-x-rest`, so the mark grows on click. Recorded, not fixed — closing
    // it by default would move every citation mark in the shipped window.
    const shipped = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)]);
    const shippedRef = shipped.renderRoot.querySelector('.cite-ref') as HTMLElement;
    const shippedResting = padX(shippedRef);
    setSelectedSource(sourceKey(DOC_A, 1));
    expect(padX(shippedRef)).toBeGreaterThan(shippedResting);
    shipped.remove();

    __resetSelectedSource();

    // v3: the rest padding is reserved, so selecting changes paint and nothing else.
    const v3 = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)], {
      '--md-cite-pad-x-rest': '0.25em',
    });
    const v3Ref = v3.renderRoot.querySelector('.cite-ref') as HTMLElement;
    const v3Resting = padX(v3Ref);
    expect(v3Resting).toBeGreaterThan(0);
    setSelectedSource(sourceKey(DOC_A, 1));
    expect(v3Ref.classList.contains('cite-selected')).toBe(true);
    expect(padX(v3Ref)).toBe(v3Resting);
    v3.remove();
  });
});

describe('822 F6 — the selection exists for assistive tech', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  it('T4: aria-current is on the selected mark and ABSENT on the others; the NAME never moves', async () => {
    const el = await mountCites(`${ONE} ${TWO}`, [
      citeAt(ONE, WEAK, 1, DOC_A),
      citeAt(TWO, WEAK, 2, DOC_B),
    ]);
    const refs = [...el.renderRoot.querySelectorAll<HTMLElement>('.cite-ref')];
    expect(refs.length).toBe(2);
    for (const r of refs) expect(r.hasAttribute('aria-current')).toBe(false);

    setSelectedSource(sourceKey(DOC_A, 1));
    const selected = refs.find((r) => r.dataset.citeKey === sourceKey(DOC_A, 1))!;
    const other = refs.find((r) => r.dataset.citeKey === sourceKey(DOC_B, 1))!;

    expect(selected.getAttribute('aria-current')).toBe('true');
    // Removed, not `aria-current="false"` — the false value is still an announced property.
    expect(other.hasAttribute('aria-current')).toBe(false);
    // The state lives in `aria-current` and NOWHERE ELSE. The first cut also appended "— selected"
    // to the accessible name, so a reader met the state twice in one announcement — a state in the
    // NAME plus a real ARIA state is the standard double-announcement anti-pattern, and it makes the
    // name (what a voice-control user speaks to click the mark) move under them. So both marks keep
    // the same, stable name whether selected or not.
    expect(selected.getAttribute('aria-label')).toBe('Citation 1 — open the cited passage');
    expect(other.getAttribute('aria-label')).toBe('Citation 2 — open the cited passage');

    // Deselecting takes the state back off — and, again, leaves the name where it was.
    setSelectedSource(null);
    expect(selected.hasAttribute('aria-current')).toBe(false);
    expect(selected.getAttribute('aria-label')).toBe('Citation 1 — open the cited passage');
    el.remove();
  });

  it('a mark rendered into an already-selected state announces it from the start', async () => {
    setSelectedSource(sourceKey(DOC_A, 1));
    const el = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)]);
    const ref = el.renderRoot.querySelector('.cite-ref') as HTMLElement;
    expect(ref.getAttribute('aria-current')).toBe('true');
    // …through the property, not the name: a mark built straight into the selected state announces
    // the state exactly the way one toggled into it does.
    expect(ref.getAttribute('aria-label')).toBe('Citation 1 — open the cited passage');
    el.remove();
  });
});

describe('822 §5.3 — selection highlights the sentences the source supports', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  it('T5: only the .cite-sentence whose data-cite-key matches gains the region class', async () => {
    const el = await mountCites(`${ONE} ${TWO}`, [
      citeAt(ONE, WEAK, 1, DOC_A),
      citeAt(TWO, WEAK, 2, DOC_B),
    ]);
    const sentences = [...el.renderRoot.querySelectorAll<HTMLElement>('.cite-sentence')];
    expect(sentences.length).toBe(2);
    // The key is the mark's key, computed through the one `sourceKey` authority — a second key
    // function here would silently never match, and the region would simply never light up.
    expect(sentences.map((s) => s.dataset.citeKey)).toEqual([
      sourceKey(DOC_A, 1),
      sourceKey(DOC_B, 1),
    ]);
    for (const s of sentences) expect(s.classList.contains('cite-sentence-selected')).toBe(false);

    setSelectedSource(sourceKey(DOC_A, 1));
    const [first, second] = sentences as [HTMLElement, HTMLElement];
    expect(first.classList.contains('cite-sentence-selected')).toBe(true);
    expect(second.classList.contains('cite-sentence-selected')).toBe(false);
    // It is surface, not content: the tier underline is untouched by the wash.
    expect(first.className).toContain('grounding-weak');

    setSelectedSource(sourceKey(DOC_B, 1));
    expect(first.classList.contains('cite-sentence-selected')).toBe(false);
    expect(second.classList.contains('cite-sentence-selected')).toBe(true);
    el.remove();
  });

  /* The region's horizontal inset (F2). Live at 3x the wash painted flush to the glyphs and read as
     a text-selection smear rather than a designed region. The fix is a tokenized padding cancelled
     by an equal negative margin: it paints wider and occupies the same width. happy-dom has NO
     layout engine (offsetWidth / getBoundingClientRect are 0 for everything), so — like `padX` above
     — the invariant is asserted on the computed box, never on a measured width. */
  it('T5b: the region inset is 0 by default and cancels its own padding when a window opts in', async () => {
    const shipped = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)]);
    setSelectedSource(sourceKey(DOC_A, 1));
    const shippedRegion = shipped.renderRoot.querySelector('.cite-sentence-selected') as HTMLElement;
    expect(shippedRegion).not.toBeNull();

    // CONTAINMENT: no `--md-cite-region-*` set, so the region occupies exactly the glyphs — every
    // shipped surface (UnifiedChatView, SummarizeView) is byte-identical to before.
    const shippedCs = getComputedStyle(shippedRegion);
    expect(shippedCs.paddingLeft).toBe('0px');
    expect(shippedCs.paddingRight).toBe('0px');
    expect(shippedCs.marginLeft).toBe('0px');
    expect(shippedCs.marginRight).toBe('0px');
    shipped.remove();

    __resetSelectedSource();

    // OPT-IN: the two names `Sv3Main.ts` bridges. Padding and inset are equal in magnitude and
    // opposite in sign — the "paints wider, occupies the same width" invariant. If either token were
    // ever changed alone the wash would start shifting glyphs, which is the whole thing this buys.
    const v3 = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)], {
      '--md-cite-region-pad-x': '0.25em',
      '--md-cite-region-inset-x': '-0.25em',
    });
    setSelectedSource(sourceKey(DOC_A, 1));
    const region = v3.renderRoot.querySelector('.cite-sentence-selected') as HTMLElement;
    const cs = getComputedStyle(region);
    const padL = parseFloat(cs.paddingLeft);
    const padR = parseFloat(cs.paddingRight);
    const marL = parseFloat(cs.marginLeft);
    const marR = parseFloat(cs.marginRight);
    expect(padL).toBeGreaterThan(0); // non-vacuity: the opt-in really computed
    expect(padR).toBe(padL);
    expect(marL).toBe(-padL);
    expect(marR).toBe(-padR);

    // HORIZONTAL ONLY. `.grounding-weak` / `.grounding-ungrounded` draw their dotted `border-bottom`
    // on THIS element, so vertical padding would sit a selected sentence's underline lower than an
    // unselected one's — a real regression, not a nicety.
    expect(region.className).toContain('grounding-weak');
    expect(cs.paddingTop).toBe('0px');
    expect(cs.paddingBottom).toBe('0px');
    v3.remove();
  });

  it('leaves box-decoration-break at slice (a wrapped sentence is one highlight, not pills)', () => {
    // `slice` rounds the start of the first line-fragment and the end of the last, giving one
    // continuous region; `clone` would repeat the full box per fragment.
    const cssText = markdownBlockCssText();
    const rule = /\.cite-sentence-selected\s*\{([^}]*)\}/.exec(cssText)![1]!;
    expect(rule).not.toMatch(/box-decoration-break/);
    expect(rule).toContain('padding: 0 var(--md-cite-region-pad-x, 0);');
    expect(rule).toContain('margin: 0 var(--md-cite-region-inset-x, 0);');
  });
});

describe('822 §5.2 — frozen defaults: the shipped window is unmoved (S4-style containment)', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  it('T6: with no --md-cite-* token set, the selected fill is --accent-tint and rest padding is 0px', async () => {
    const el = await mountCites(ONE, [citeAt(ONE, WEAK, 1, DOC_A)]);
    const ref = el.renderRoot.querySelector('.cite-ref') as HTMLElement;

    // Rest: the containment proof. A default of 0.25em here would move every mark in every shipped
    // surface — the trap the design names explicitly.
    expect(getComputedStyle(ref).paddingLeft).toBe('0px');
    expect(getComputedStyle(ref).paddingRight).toBe('0px');

    setSelectedSource(sourceKey(DOC_A, 1));
    expect(ref.classList.contains('cite-selected')).toBe(true);
    expect(padX(ref)).toBeGreaterThan(0); // the selected pad-x default survives
    el.remove();
  });

  it('freezes the un-overridden fill, edge and region at the values the shipped window had', () => {
    // Source-level, deliberately: a nested var() fallback is the one thing happy-dom does not
    // resolve, and the fill's default IS one. The live half of this proof is the browser (the
    // design's §7 live check), the same split the S4 containment test records.
    const cssText = markdownBlockCssText();
    const rule = /\.cite-ref\.cite-selected\s*\{([^}]*)\}/.exec(cssText)![1]!;
    expect(rule).toContain('background: var(--md-cite-selected-bg, var(--surface-active));');
    expect(rule).toContain('padding: 0 var(--md-cite-pad-x, 0.25em);');
    // A transparent edge is a computed no-op — the hairline is the window's opt-in, not a default.
    expect(rule).toContain('box-shadow: inset 0 0 0 1px var(--md-cite-selected-edge, transparent);');
    // And the region default is transparent, so §5.3 ships dark on surfaces that do not opt in.
    expect(cssText).toContain('var(--md-cite-region-bg, transparent)');
  });

  it('mints no ink override — an ink escape hatch is an F2 escape hatch', () => {
    const cssText = markdownBlockCssText();
    expect(cssText).not.toContain('--md-cite-selected-ink');
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 847 S4 — WORD-SEQUENCE ANCHORING: the mark lands, and it never outruns its evidence.
 *
 * The pre-847 anchorer matched a marker-stripped regex against the flattened DOM — two
 * representations of one text, normalized by DIFFERENT rules, so every block-level markdown shape
 * desynced them and a cross-encoder-verified sentence rendered nothing. What is pinned here is the
 * three-tier contract: tier 1 anchors by word sequence (this block), tier 2 upgrades a literal `[n]`
 * the model placed WITHOUT claiming a sentence (H3), tier 3 marks nothing at all.
 *
 * The shape matrix that proves the CLASS (18 markdown shapes, CJK included) is a file of its own:
 * `MarkdownBlock.anchoring.test.ts`.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */

const TWO_SOURCE_SENTENCE = 'The retrieval pipeline fuses two rankers.';

function claim(
  sentenceIndex: number,
  sentenceText: string,
  verifiedRefs: number[],
  verifiedScore = 0.8,
): Claim {
  return { sentenceIndex, sentenceText, verifiedScore, lexicalScore: 0, verifiedRefs, lexicalRefs: [] };
}

describe('847 T1 — a list-shaped answer anchors inside the rendered <li>', () => {
  it('anchors the FUSED key of a bulleted item, marker and all', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = 'Items:\n\n- **Foo**: bar baz qux [1].\n- **Bar**: another item [2].';
    // Both halves of §1.1 in one fixture: the key carries a bullet marker and bold markers the DOM
    // does not have, AND the next item's ordinal fused onto its tail by the backend's segmenter.
    el.citations = [{ ...mark('- **Foo**: bar baz qux [1].\n\n2. ', 0.8, 91), sentenceIndex: 0 }];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const ref = el.renderRoot.querySelector('.cite-ref');
    expect(ref).not.toBeNull();
    expect(ref!.closest('li')).not.toBeNull();
    // …and the sentence body it underlines is that item, not the list.
    const span = el.renderRoot.querySelector('.cite-sentence')!;
    expect(span.closest('li')).not.toBeNull();
    expect(span.textContent).not.toContain('another item');
    el.remove();
  });
});

describe('847 T3 — a sentence two sources support renders BOTH marks', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  it('resolves two citations and renders two marks at one boundary, ascending by label', async () => {
    // Through the REAL resolver: the render half is only reachable because `claimsToCitations` now
    // emits per verified ref. A hand-built pair would prove nothing about that.
    const citations = claimsToCitations([claim(0, TWO_SOURCE_SENTENCE, [0, 2])], DENSITY_SOURCES);
    expect(citations).toHaveLength(2);
    const el = await renderAnswer([TWO_SOURCE_SENTENCE, 'A second sentence follows it.'], []);
    el.citations = citations;
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const content = el.renderRoot.querySelector('.md-content')!;
    const refs = [...content.querySelectorAll('.cite-ref')];
    expect(refs.map((r) => r.textContent)).toEqual(['1', '3']);
    // ONE sentence, so ONE underline — two spans would double-count the same prose.
    expect(content.querySelectorAll('.cite-sentence').length).toBe(1);
    el.remove();
  });

  it('lets EITHER source light the sentence it supports (§5.3 with two keys)', async () => {
    const citations = claimsToCitations([claim(0, TWO_SOURCE_SENTENCE, [0, 2])], DENSITY_SOURCES);
    const el = await renderAnswer([TWO_SOURCE_SENTENCE], []);
    el.citations = citations;
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const span = el.renderRoot.querySelector<HTMLElement>('.cite-sentence')!;
    expect(span.classList.contains('cite-sentence-selected')).toBe(false);

    // The SECOND source — the one the pre-847 resolver dropped entirely.
    setSelectedSource(sourceKey(DENSITY_SOURCES[2]!.parentDocId, DENSITY_SOURCES[2]!.startLine));
    expect(span.classList.contains('cite-sentence-selected')).toBe(true);
    setSelectedSource(sourceKey(DENSITY_SOURCES[0]!.parentDocId, DENSITY_SOURCES[0]!.startLine));
    expect(span.classList.contains('cite-sentence-selected')).toBe(true);
    setSelectedSource('docs/unrelated.md:9');
    expect(span.classList.contains('cite-sentence-selected')).toBe(false);
    el.remove();
  });
});

describe('847 T3b — a repeated sentence is marked at EACH occurrence, never stacked', () => {
  it('anchors the second citation after the first match, not on top of it', async () => {
    const repeated = 'The kernel is a shared substrate.';
    const el = await renderAnswer([repeated, 'Then some intervening prose.', repeated], [
      claim(0, repeated, [0]),
      claim(1, repeated, [1]),
    ]);
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(2);
    // The ORDER is the assertion: without the consume-and-advance rule both marks resolve at
    // occurrence 1 and the per-label dedupe happily inserts both there — strictly worse than the
    // single mark the pre-847 renderer produced.
    expect(content.textContent).toMatch(
      /substrate\.1 Then some intervening prose\. The kernel is a shared substrate\.2/,
    );
    el.remove();
  });
});

describe('847 F1 — anchoring order is established here, not assumed of the input', () => {
  it('marks both sentences when the citations arrive in DESCENDING sentence order', async () => {
    // §2.1d's consume-and-advance is a statement about the ANSWER'S sentence order. Two of the four
    // construction paths sort; the two that project a persisted array inherit whatever order was
    // stored, and nothing pins that contract on the producer side. Unsorted, the later sentence
    // anchors first, the cursor advances past it, and the EARLIER sentence can no longer match —
    // its mark vanishes, which is precisely the defect this slice exists to close.
    const first = 'The kernel is a shared substrate.';
    const second = 'The worker owns all index IO.';
    const el = await renderAnswer([first, second], [claim(1, second, [1]), claim(0, first, [0])]);
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(2);
    // …and each mark sits on ITS OWN sentence, not merely present somewhere.
    expect(content.textContent).toMatch(/substrate\.1 The worker owns all index IO\.2/);
    el.remove();
  });

  it('an untyped citation gets its own ordinal — it never merges into the sentence at its index', async () => {
    // The positional fallback for an object arriving from JS without the (now required) field. A
    // naive `sentenceIndex ?? position` would have let citation @1 group with the real sentence 1
    // and put its mark on prose it never grounded; the fallback key is per-position and negative,
    // so it can collide with no real ordinal.
    const first = 'The kernel is a shared substrate.';
    const second = 'The worker owns all index IO.';
    const untyped = { ...mark(second, 0.8, 2) } as Record<string, unknown>;
    delete untyped.sentenceIndex;
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = `${first} ${second}`;
    el.citations = [mark(first, 0.8, 1, 0), untyped as unknown as MarkdownCitation];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-sentence').length).toBe(2);
    expect(content.textContent).toMatch(/substrate\.1 The worker owns all index IO\.2/);
    el.remove();
  });
});

describe('847 T3c/T4/T7 — what the rewrite must NOT regress', () => {
  it('T3c: a three-token sentence still anchors (the floor is CHARACTERS, not tokens)', async () => {
    const el = await renderAnswer(['Does it rebuild the index?', 'It does not.', 'It reuses the segment.'], [
      claim(1, 'It does not.', [0]),
    ]);
    expect(el.renderRoot.querySelectorAll('.cite-ref').length).toBe(1);
    expect(el.renderRoot.querySelector('.cite-sentence')?.textContent).toContain('It does not');
    el.remove();
  });

  it('T4: an answer with NO literal [n] anywhere still gets its tier-1 marks', async () => {
    const el = await renderAnswer(
      ['The kernel is a shared substrate for every projection.', 'The worker owns all index IO.'],
      [claim(0, 'The kernel is a shared substrate for every projection.', [0]), claim(1, 'The worker owns all index IO.', [1])],
    );
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(2);
    expect(content.textContent).not.toContain('[1]');
    el.remove();
  });

  it('T7: a sentence whose first words repeat earlier anchors at the RIGHT occurrence', async () => {
    // Longest-run, not first-hit: the prefix `The kernel is a` matches the earlier sentence too.
    const el = await renderAnswer(
      ['The kernel is a shared substrate.', 'The kernel is a governed projection.'],
      [claim(1, 'The kernel is a governed projection.', [0])],
    );
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);
    expect(content.textContent).toMatch(/governed projection\.1/);
    expect(content.textContent).not.toMatch(/shared substrate\.1/);
    el.remove();
  });
});

describe('847 T5 — tier 2 asserts SOURCE attribution, so it claims no sentence (H3)', () => {
  it('upgrades the literal [1] and leaves the prose without a cited-sentence span', async () => {
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    // Tier 1 finds nothing (the key is absent from the answer), so the model's own literal token is
    // all the evidence there is about WHERE the source applies — source-level, not sentence-level.
    el.text = 'Unmatched prose with a bare token [1] inline.';
    el.citations = [mark('A sentence that does not appear at all.', 0.8, 1)];
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);
    // THE INVARIANT: a tier-2 mark carries no underline. The sentence was never identified, so no
    // span may claim it was — the difference between "this source supports the answer" and "this
    // source supports THIS sentence", which is the whole content of a `.cite-sentence`.
    expect(content.querySelectorAll('.cite-sentence').length).toBe(0);
    el.remove();
  });
});

describe('847 §2.1 × 846 §2.4 — the deferred highlight pass must not eat the weave (T19)', () => {
  beforeEach(() => {
    resetHighlighterForTest();
  });

  it('keeps a cited sentence inside a fence after the highlighter resolves', async () => {
    const cited = 'const kernel = "a shared substrate";';
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = `Config:\n\n\`\`\`js\n${cited}\n\`\`\`\n\nAnd a second, uncited block:\n\n\`\`\`js\nconst other = 2;\n\`\`\`\n`;
    el.citations = [{ ...mark(cited, 0.8, 91), sentenceIndex: 0 }];
    document.body.appendChild(el);
    // The ordering that matters is the ASYNC one: `updated()` calls `highlightCodeBlocks` before the
    // weave, but the highlighter chunk is not loaded yet, so the pass that actually rewrites the
    // code blocks runs AFTER the marks are in.
    expect(isHighlighterLoaded()).toBe(false);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const content = el.renderRoot.querySelector('.md-content') as HTMLElement;
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);

    await loadHighlighter();
    highlightCodeBlocks(content);
    await new Promise((r) => setTimeout(r, 0));

    // The weave survived — `markdownHighlight`'s children guard yielded on the block that carries it.
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);
    expect(content.querySelector('.cite-sentence')).not.toBeNull();
    expect(content.querySelector('pre code')!.textContent).toContain('a shared substrate');
    // NON-VACUITY: the pass really ran — the uncited block beside it did get highlighted.
    const blocks = [...content.querySelectorAll<HTMLElement>('pre code')];
    expect(blocks.length).toBe(2);
    expect(blocks.some((b) => b.querySelector('.hljs-keyword') !== null)).toBe(true);
    el.remove();
  });
});

describe('847 × 846 §2.5 — a RESTORED turn flips the trailing-list strip from 0 to N citations', () => {
  it('preserves the model list while evidence is absent, strips it once evidence rehydrates', async () => {
    const answer = 'The kernel is a shared substrate.\n\nSources:\n[1] The kernel doc';
    const el = document.createElement('jf-markdown-block') as MarkdownBlock;
    el.text = answer;
    document.body.appendChild(el);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    // The restored turn as search v3 rendered it before its evidence came back: no citations, so the
    // interface presents no sources and the model's own list is the only source information there is.
    expect(el.renderRoot.querySelector('.md-content')!.textContent).toContain('The kernel doc');

    // …and the moment the record's evidence is projected in, the UI owns the source presentation
    // again: the list goes, and the mark it was standing in for arrives.
    el.citations = claimsToCitations([claim(0, 'The kernel is a shared substrate.', [0])], DENSITY_SOURCES);
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
    const content = el.renderRoot.querySelector('.md-content')!;
    expect(content.textContent).not.toContain('The kernel doc');
    expect(content.querySelectorAll('.cite-ref').length).toBe(1);
    el.remove();
  });
});

describe('MarkdownBlock — the streaming coalescer drains a page without frames (860 §6.4 / P3)', () => {
  /**
   * This site had NO fallback of any kind before P3: the latch was the raw rAF handle, so a stream
   * arriving while the page stopped rendering held it until the block disconnected. Adopting the
   * shared latch changes that here too — deliberately, which is what this case pins.
   *
   * The coalesced repaint is an argument-less `requestUpdate()`; Lit's own property sets pass three
   * arguments, so counting by arity separates the latch's pass from the delta that scheduled it.
   */
  function coalescedRepaints(spy: { mock: { calls: unknown[][] } }): number {
    return spy.mock.calls.filter((c) => c.length === 0).length;
  }

  it('lands the coalesced repaint on the fallback, and a disconnect withdraws it', async () => {
    // Order matters: vitest's fake timers install their OWN rAF (one that fires as the clock is
    // advanced), so the never-firing stub has to go on afterwards or the page still renders.
    vi.useFakeTimers();
    const realRaf = globalThis.requestAnimationFrame;
    globalThis.requestAnimationFrame = ((): number => 1) as typeof requestAnimationFrame;
    try {
      const el = document.createElement('jf-markdown-block') as MarkdownBlock;
      el.isStreaming = true;
      el.text = 'first';
      document.body.appendChild(el);
      await el.updateComplete;

      const repaint = vi.spyOn(el, 'requestUpdate');
      el.text = 'first second';
      await el.updateComplete;
      el.text = 'first second third';
      await el.updateComplete;
      expect(coalescedRepaints(repaint), 'both deltas are latched behind a frame that never comes').toBe(0);

      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS);
      expect(coalescedRepaints(repaint), 'the fallback paints the LATEST delta, once').toBe(1);

      el.text = 'first second third fourth';
      await el.updateComplete;
      el.remove();
      const atDisconnect = coalescedRepaints(repaint);
      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS * 3);
      expect(coalescedRepaints(repaint), 'a disconnected block is never repainted a second later').toBe(atDisconnect);
    } finally {
      globalThis.requestAnimationFrame = realRaf;
      vi.useRealTimers();
    }
  });
});
