/**
 * Tempdoc 847 S4 — THE ANCHORING SHAPE MATRIX (T2 / T2b / T2c).
 *
 * S0 measured 18 markdown shapes through the production backend segmenter
 * (`CitationMatchOps.splitSentences`) and computed §2.1 tier 1 against a commonmark stand-in for the
 * rendered DOM. This file resurrects that harness as a permanent test and closes S0's own modelling
 * gap: the shapes below run through the REAL renderer (`marked` → DOMPurify → text nodes), and the
 * `sentenceText` of each citation is the key `splitSentences` actually emitted for that shape —
 * fusion included, verbatim from the S0 evidence record. Nothing here is idealized input.
 *
 * What each assertion is for:
 *   T2  — WHICH keys anchor. The class, not the observed case: a bullet, an ordinal, a table pipe, a
 *         blockquote marker or a heading may not decide whether a verified sentence gets its mark.
 *   T2b — THE SPAN GUARD (H4). 7 of the eligible runs match CONTIGUOUSLY at 100 % coverage across
 *         2–5 rendered blocks, because the backend fused a whole block into one key. No acceptance
 *         threshold can see that; only the block-ancestor comparison can.
 *   T2c — the acceptance rule's boundaries, in both regimes of `max(4, 0.4 × keyWordChars)`.
 *
 * The CJK / Japanese shapes are the Hard-Invariant-6 guard: they run through the SAME code path with
 * no per-language branch, and they are exactly the shapes a token-count floor would have made
 * structurally unmarkable (§2.1a).
 *
 * @vitest-environment happy-dom
 */

import { describe, expect, it } from 'vitest';
import { type MarkdownBlock, type MarkdownCitation } from './MarkdownBlock.js';
import './MarkdownBlock.js';
import { sourceKey } from '../../state/selectedSource.js';

/** The block elements a `.cite-sentence` span may not straddle (H4's oracle, stated independently). */
const BLOCK_SELECTOR = 'p,li,td,th,blockquote,pre,h1,h2,h3,h4,h5,h6,figcaption,dd,dt';

interface Shape {
  id: string;
  /** The answer as the model wrote it. */
  markdown: string;
  /** The keys `splitSentences` emitted for it (S0, verbatim — fusion and junk included). */
  keys: string[];
  /** Indices of the keys that must anchor a tier-1 mark. */
  anchors: number[];
  /**
   * For a key whose accepted run crosses a block: text from the SECOND block that the clamped
   * `.cite-sentence` span must NOT contain. Without the guard the span swallows it at 100 % coverage.
   */
  clampedAway?: Record<number, string>;
}

const SHAPES: Shape[] = [
  {
    id: 'A-prose-baseline',
    markdown:
      'The retrieval pipeline has three stages. Query expansion runs first [2]. ' +
      'A cross-encoder then rescores the top candidates [1].',
    keys: [
      'The retrieval pipeline has three stages.',
      'Query expansion runs first [2].',
      'A cross-encoder then rescores the top candidates [1].',
    ],
    anchors: [0, 1, 2],
  },
  {
    id: 'B-numbered-bold-loose',
    markdown:
      'The pipeline has three stages:\n\n' +
      '1. **Query Expansion**: the query is expanded with synonyms before retrieval [2].\n\n' +
      '2. **Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].\n\n' +
      '3. **Reranking**: a cross-encoder rescores the top candidates by relevance [1].',
    keys: [
      'The pipeline has three stages:\n\n1.',
      '**Query Expansion**: the query is expanded with synonyms before retrieval [2].\n\n2.',
      '**Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].\n\n3.',
      '**Reranking**: a cross-encoder rescores the top candidates by relevance [1].',
    ],
    anchors: [0, 1, 2, 3],
  },
  {
    id: 'C-numbered-bold-tight',
    markdown:
      'The pipeline has three stages:\n\n' +
      '1. **Query Expansion**: the query is expanded with synonyms before retrieval [2].\n' +
      '2. **Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].\n' +
      '3. **Reranking**: a cross-encoder rescores the top candidates by relevance [1].',
    keys: [
      'The pipeline has three stages:\n1.',
      '**Query Expansion**: the query is expanded with synonyms before retrieval [2].\n2.',
      '**Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].\n3.',
      '**Reranking**: a cross-encoder rescores the top candidates by relevance [1].',
    ],
    anchors: [0, 1, 2, 3],
  },
  {
    id: 'D-numbered-plain',
    markdown:
      'Three stages run in order:\n\n' +
      '1. The query is expanded with synonyms before retrieval [2].\n\n' +
      '2. Candidates are fetched by hybrid BM25 and dense vector search [1][3].\n\n' +
      '3. A cross-encoder rescores the top candidates by relevance [1].',
    keys: [
      'Three stages run in order:\n\n1.',
      'The query is expanded with synonyms before retrieval [2].\n\n2.',
      'Candidates are fetched by hybrid BM25 and dense vector search [1][3].\n\n3.',
      'A cross-encoder rescores the top candidates by relevance [1].',
    ],
    anchors: [0, 1, 2, 3],
  },
  {
    id: 'E-bullet-dash',
    markdown:
      'Key properties of the index:\n\n' +
      '- The index is written only by the index component [1].\n' +
      '- The API gateway delegates all index IO through ports [2].\n' +
      '- Search analysis is locale invariant by construction [3].',
    // ONE key for the whole block — `BreakIterator` never breaks before a `- ` marker (S0 §Mechanism).
    keys: [
      'Key properties of the index:\n\n- The index is written only by the index component [1].\n' +
        '- The API gateway delegates all index IO through ports [2].\n' +
        '- Search analysis is locale invariant by construction [3].',
    ],
    anchors: [0],
    clampedAway: { 0: 'The index is written only by the index component' },
  },
  {
    id: 'F-bullet-star-bold',
    markdown:
      'Key properties:\n\n' +
      '* **Ownership**: the index is written only by the index component [1].\n' +
      '* **Transport**: the Head delegates all index IO through ports [2].',
    keys: [
      'Key properties:\n\n* **Ownership**: the index is written only by the index component [1].\n' +
        '* **Transport**: the Head delegates all index IO through ports [2].',
    ],
    anchors: [0],
    clampedAway: { 0: 'the index is written only by the index component' },
  },
  {
    id: 'G-nested-list',
    markdown:
      'Retrieval works in two layers:\n\n' +
      '1. **Lexical**: BM25 over the analyzed text field [1].\n' +
      '   - Stopwords are not removed per language [3].\n' +
      '   - Case folding is applied after NFC normalization [3].\n\n' +
      '2. **Dense**: cosine similarity over the embedding field [2].',
    keys: [
      'Retrieval works in two layers:\n\n1.',
      '**Lexical**: BM25 over the analyzed text field [1]',
      '.', // orphan terminator — junk the backend emits today (S5 removes it; here it must not anchor)
      '- Stopwords are not removed per language [3]',
      '.',
      '- Case folding is applied after NFC normalization [3].\n2.',
      '**Dense**: cosine similarity over the embedding field [2].',
    ],
    anchors: [0, 1, 3, 5, 6],
  },
  {
    id: 'H-heading-then-list',
    markdown:
      '## Retrieval\n\nThe pipeline fuses two retrievers [1].\n\n### Stages\n\n' +
      '1. Lexical retrieval scores the analyzed text field [1].\n' +
      '2. Dense retrieval scores the embedding field [2].',
    keys: [
      '## Retrieval\n\nThe pipeline fuses two retrievers [1].\n\n### Stages\n\n1.',
      'Lexical retrieval scores the analyzed text field [1].\n2.',
      'Dense retrieval scores the embedding field [2].',
    ],
    anchors: [0, 1, 2],
    clampedAway: { 0: 'The pipeline fuses two retrievers' },
  },
  {
    id: 'I-table-rows',
    markdown:
      '| Stage | Component | Source |\n| --- | --- | --- |\n' +
      '| Expansion | Query rewriter expands synonyms. | [2] |\n' +
      '| Retrieval | Hybrid BM25 and dense search runs. | [1] |\n' +
      '| Reranking | Cross-encoder rescores candidates. | [3] |',
    keys: [
      '| Stage | Component | Source |\n| --- | --- | --- |\n' +
        '| Expansion | Query rewriter expands synonyms. | [2] |\n' +
        '| Retrieval | Hybrid BM25 and dense search runs. | [1] |\n' +
        '| Reranking | Cross-encoder rescores candidates. | [3] |',
    ],
    anchors: [0],
    clampedAway: { 0: 'Query rewriter expands synonyms' },
  },
  {
    id: 'J-short-items',
    markdown:
      'Does it rebuild the index?\n\n' +
      '1. It does not.\n2. It reuses the segment.\n3. It re-opens the reader.',
    keys: [
      'Does it rebuild the index?',
      '1.', // standalone ordinal — one word-like character, below the eligibility floor
      'It does not.\n2.',
      'It reuses the segment.\n3.',
      'It re-opens the reader.',
    ],
    anchors: [0, 2, 3, 4],
  },
  {
    id: 'K-blockquote',
    markdown:
      'The design states:\n\n> The Head never touches Lucene [1].\n> All index IO is delegated to the Worker [2].',
    keys: [
      'The design states:\n\n> The Head never touches Lucene [1].\n' +
        '> All index IO is delegated to the Worker [2].',
    ],
    anchors: [0],
    clampedAway: { 0: 'The Head never touches Lucene' },
  },
  {
    id: 'L-fence-between-items',
    markdown:
      'To enable the flag:\n\n' +
      '1. Set the flag in the configuration file [1].\n\n' +
      '```json\n{ "citations": { "enabled": true } }\n```\n\n' +
      '2. Restart JustSearch so the change is applied [2].',
    keys: [
      'To enable the flag:\n\n1.',
      'Set the flag in the configuration file [1].',
      // The fence key: its FIRST token is the info string, which is a class attribute in the DOM and
      // not text — so the prefix match is zero and tier 3 (no mark) is the right outcome.
      '```json\n{ "citations": { "enabled": true } }\n```\n\n2.',
      'Restart JustSearch so the change is applied [2].',
    ],
    anchors: [0, 1, 3],
  },
  {
    id: 'M-cjk-numbered',
    markdown:
      '检索管道包含三个阶段：\n\n' +
      '1. **查询扩展**：在检索之前使用同义词扩展查询 [2]。\n\n' +
      '2. **混合检索**：系统同时使用稀疏和稠密两种方式召回候选文档 [1][3]。\n\n' +
      '3. **重新排序**：交叉编码器对候选结果重新打分 [1]。',
    keys: [
      '检索管道包含三个阶段：\n\n1.',
      '**查询扩展**：在检索之前使用同义词扩展查询 [2]。',
      '2.', // the CJK junk class: a standalone ordinal, which would match an arbitrary `[2]` at 100 %
      '**混合检索**：系统同时使用稀疏和稠密两种方式召回候选文档 [1][3]。',
      '3.',
      '**重新排序**：交叉编码器对候选结果重新打分 [1]。',
    ],
    anchors: [0, 1, 3, 5],
  },
  {
    id: 'N-cjk-bullets',
    markdown: '索引的关键属性：\n\n- 索引只由索引组件写入 [1]。\n- API 网关通过进程内端口委托所有索引读写 [2]。',
    keys: ['索引的关键属性：\n\n- 索引只由索引组件写入 [1]。', '- API 网关通过进程内端口委托所有索引读写 [2]。'],
    anchors: [0, 1],
    clampedAway: { 0: '索引只由索引组件写入' },
  },
  {
    id: 'O-japanese-numbered',
    markdown:
      '検索パイプラインは三つの段階から成ります。\n\n' +
      '1. **クエリ拡張**：検索の前に同義語でクエリを拡張します [2]。\n\n' +
      '2. **再ランキング**：クロスエンコーダが候補を並べ替えます [1]。',
    keys: [
      '検索パイプラインは三つの段階から成ります。',
      '1.',
      '**クエリ拡張**：検索の前に同義語でクエリを拡張します [2]。',
      '2.',
      '**再ランキング**：クロスエンコーダが候補を並べ替えます [1]。',
    ],
    anchors: [0, 2, 4],
  },
  {
    id: 'P-live-observed',
    markdown:
      'Here is how the system answers a question:\n\n' +
      '1. **Query Understanding**: the question is analyzed and expanded [2].\n\n' +
      '2. **Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].\n\n' +
      '3. **Answer Synthesis**: the model writes the answer from the retrieved context [1].',
    keys: [
      'Here is how the system answers a question:\n\n1.',
      '**Query Understanding**: the question is analyzed and expanded [2].\n\n2.',
      '**Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].\n\n3.',
      '**Answer Synthesis**: the model writes the answer from the retrieved context [1].',
    ],
    anchors: [0, 1, 2, 3],
  },
  {
    id: 'Q-two-digit-short-items',
    markdown:
      '9. It does not.\n10. It reuses the reader.\n11. It is cached.\n12. No.\n' +
      '13. The Worker owns the index and never yields it to the Head [1].',
    keys: [
      '9.',
      'It does not.\n10.',
      'It reuses the reader.\n11.',
      'It is cached.\n12.',
      'No.\n13.', // 50 % coverage — the measured false reject of any pure ratio (§2.1b)
      'The Worker owns the index and never yields it to the Head [1].',
    ],
    anchors: [1, 2, 3, 4, 5],
  },
  {
    id: 'R-bullets-with-links',
    markdown:
      'Two rules apply:\n\n' +
      '- See [the architecture overview](docs/explanation/01-system-overview.md) for details [1].\n' +
      '- The API gateway delegates all index IO through ports [2].',
    keys: [
      'Two rules apply:\n\n- See [the architecture overview](docs/explanation/01-system-overview.md) ' +
        'for details [1].\n- The API gateway delegates all index IO through ports [2].',
    ],
    anchors: [0],
    clampedAway: { 0: 'the architecture overview' },
  },
  {
    id: 'S-hardwrapped-prose',
    // The precision case: three physical lines, ONE paragraph, ONE sentence. S0 modelled a block
    // boundary as "a newline in the flattened text" and this shape was its false positive — the real
    // ancestor comparison must NOT clamp here, or a legitimately soft-wrapped sentence loses its body.
    markdown:
      'The index component owns Lucene and the API gateway delegates every index read\n' +
      'and write to it through ports, so no index handle ever exists in the API\n' +
      'layer [1]. That boundary is enforced by an ArchUnit rule [2].',
    keys: [
      'The index component owns Lucene and the API gateway delegates every index read\n' +
        'and write to it through ports, so no index handle ever exists in the API\nlayer [1].',
      'That boundary is enforced by an ArchUnit rule [2].',
    ],
    anchors: [0, 1],
  },
];

/**
 * One citation per key, in the backend's own sentence order. Labels start at 91 deliberately: the
 * shapes are full of literal `[1]`/`[2]`/`[3]` tokens, and a label matching one of them would pull
 * the TIER-2 literal upgrade into a test about tier 1.
 */
function citationsFor(shape: Shape): MarkdownCitation[] {
  return shape.keys.map((key, i) => ({
    sentenceText: key,
    similarity: 0.8,
    sentenceIndex: i,
    label: 91 + i,
    detail: {
      parentDocId: `docs/${shape.id}-s${i}.md`,
      startLine: i + 1,
      endLine: i + 2,
      startChar: 0,
      endChar: 0,
      excerpt: 'x',
    },
    hover: { excerpt: 'x', title: shape.id, headingText: '' },
  }));
}

const keyOf = (shape: Shape, i: number): string => sourceKey(`docs/${shape.id}-s${i}.md`, i + 1);

async function render(markdown: string, citations: MarkdownCitation[]): Promise<MarkdownBlock> {
  const el = document.createElement('jf-markdown-block') as MarkdownBlock;
  el.text = markdown;
  el.citations = citations;
  document.body.appendChild(el);
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  return el;
}

function spansByKey(el: MarkdownBlock): Map<string, HTMLElement[]> {
  const out = new Map<string, HTMLElement[]>();
  for (const s of el.renderRoot.querySelectorAll<HTMLElement>('.cite-sentence')) {
    const k = s.dataset.citeKey!;
    out.set(k, [...(out.get(k) ?? []), s]);
  }
  return out;
}

describe('847 T2 — the anchoring shape matrix (which keys anchor, on the REAL renderer)', () => {
  for (const shape of SHAPES) {
    it(`${shape.id}: anchors exactly the eligible, accepted keys`, async () => {
      const el = await render(shape.markdown, citationsFor(shape));
      const anchored = [...spansByKey(el).keys()].sort();
      const expected = shape.anchors.map((i) => keyOf(shape, i)).sort();
      expect(anchored).toEqual(expected);
      el.remove();
    });
  }

  it('the junk keys the backend emits today anchor NOTHING (the honesty half of the floor)', async () => {
    // A standalone ordinal key measures 100 % coverage against an arbitrary `[2]` elsewhere in the
    // answer — a mark on unrelated prose. Only the ≥ 4-word-character eligibility floor excludes it,
    // which is why that floor is load-bearing for CJK honesty rather than a legacy carry-over.
    const cjk = SHAPES.find((s) => s.id === 'M-cjk-numbered')!;
    const el = await render(cjk.markdown, citationsFor(cjk));
    const anchored = new Set(spansByKey(el).keys());
    expect(anchored.has(keyOf(cjk, 2))).toBe(false); // key "2."
    expect(anchored.has(keyOf(cjk, 4))).toBe(false); // key "3."
    // …and the real CJK sentences DO anchor: no per-language path, no token-count floor (HI-6).
    expect(anchored.has(keyOf(cjk, 1))).toBe(true);
    expect(anchored.has(keyOf(cjk, 3))).toBe(true);
    el.remove();
  });

  it('a list-shaped answer puts its mark INSIDE the rendered <li> (the observed defect, §1.1)', async () => {
    const p = SHAPES.find((s) => s.id === 'P-live-observed')!;
    const el = await render(p.markdown, citationsFor(p));
    const marks = [...el.renderRoot.querySelectorAll<HTMLElement>('.cite-ref')];
    expect(marks.length).toBe(4);
    // Three of the four keys are list items; before 847 every one of them missed.
    expect(marks.filter((m) => m.closest('li') !== null).length).toBe(3);
    el.remove();
  });
});

describe('847 T2b — the span guard (H4): a mark may not underline text it did not ground', () => {
  for (const shape of SHAPES) {
    it(`${shape.id}: every cited-sentence span lies within ONE rendered block`, async () => {
      const el = await render(shape.markdown, citationsFor(shape));
      for (const [key, spans] of spansByKey(el)) {
        const blocks = new Set(spans.map((s) => s.closest(BLOCK_SELECTOR)));
        expect(blocks.size, `${shape.id} / ${key} spans ${blocks.size} blocks`).toBe(1);
      }
      el.remove();
    });
  }

  for (const shape of SHAPES.filter((s) => s.clampedAway)) {
    it(`${shape.id}: the fused key is CLAMPED to its first block, not underlined whole`, async () => {
      const el = await render(shape.markdown, citationsFor(shape));
      const byKey = spansByKey(el);
      for (const [idx, forbidden] of Object.entries(shape.clampedAway!)) {
        const spans = byKey.get(keyOf(shape, Number(idx))) ?? [];
        expect(spans.length).toBeGreaterThan(0);
        const text = spans.map((s) => s.textContent ?? '').join('');
        // Without the guard this key matches CONTIGUOUSLY at 100 % coverage and the span swallows
        // the whole list / quote / table below its lead-in.
        expect(text).not.toContain(forbidden);
      }
      el.remove();
    });
  }

  it('does NOT clamp a soft-wrapped paragraph — the newline proxy S0 used would have', async () => {
    const s = SHAPES.find((sh) => sh.id === 'S-hardwrapped-prose')!;
    const el = await render(s.markdown, citationsFor(s));
    const spans = spansByKey(el).get(keyOf(s, 0)) ?? [];
    const text = spans.map((x) => x.textContent ?? '').join('');
    // The whole three-line sentence keeps its body: the run never leaves the one <p>.
    expect(text).toContain('The index component owns Lucene');
    expect(text).toContain('layer [1]');
    el.remove();
  });
});

describe('847 T2c — the acceptance rule at its boundaries', () => {
  const cite = (sentenceText: string, i = 0): MarkdownCitation => ({
    sentenceText,
    similarity: 0.8,
    sentenceIndex: i,
    label: 91 + i,
    detail: {
      parentDocId: `docs/t2c-${i}.md`,
      startLine: i + 1,
      endLine: i + 2,
      startChar: 0,
      endChar: 0,
      excerpt: 'x',
    },
    hover: { excerpt: 'x', title: 'x', headingText: '' },
  });

  it('ACCEPTS "No.\\n13." at 50 % coverage — the tax is absolute, so the slack is too', async () => {
    // keyWordChars = 4, matched = 2 ("No"): a pure ≥ 60 % ratio rejects this, and it is a shape a
    // model really produces (a two-digit ordinal fused onto a two-character answer).
    const el = await render(
      '12. No.\n13. The Worker owns the index and never yields it to the Head [1].',
      [cite('No.\n13.')],
    );
    expect(el.renderRoot.querySelectorAll('.cite-sentence').length).toBe(1);
    expect(el.renderRoot.querySelector('.cite-sentence')?.textContent).toContain('No');
    el.remove();
  });

  it('REJECTS a fenced key whose leading info-string is not text — a fence has no sentence', async () => {
    const el = await render(
      'To enable the flag:\n\n```json\n{ "citations": { "enabled": true } }\n```\n',
      [cite('```json\n{ "citations": { "enabled": true } }\n```\n\n2.')],
    );
    // Prefix matching is asymmetric: a trailing ordinal costs ≤ 2 characters, one LEADING foreign
    // token zeroes the match. Tier 3 (no mark) is the right outcome for a code block.
    expect(el.renderRoot.querySelectorAll('.cite-sentence').length).toBe(0);
    expect(el.renderRoot.querySelectorAll('.cite-ref').length).toBe(0);
    el.remove();
  });

  it('REJECTS an ambiguous short numeric key via the uniqueness clause', async () => {
    // The hole the 4-character floor alone does not close: a numeric key clears eligibility, and a
    // one-token run carries no sequence evidence about WHICH occurrence it is. Two candidates ⇒ no
    // mark, rather than a mark on whichever came first.
    const el = await render('The plan targets 2026 for the rollout. A second 2026 milestone follows.', [
      cite('2026.'),
    ]);
    expect(el.renderRoot.querySelectorAll('.cite-sentence').length).toBe(0);
    el.remove();
  });

  it('ACCEPTS the same key when the window holds exactly one candidate', async () => {
    // Non-vacuity for the clause above: it rejects AMBIGUITY, not short keys as such.
    const el = await render('The plan targets 2026 for the rollout. The rest is unchanged.', [
      cite('2026.'),
    ]);
    expect(el.renderRoot.querySelectorAll('.cite-sentence').length).toBe(1);
    el.remove();
  });

  it('a SINGLE-TOKEN sentence anchors when its word occurs once, and not when it recurs', async () => {
    // The boundary S0's matrix was silent on rather than supportive of: a one-word sentence clears
    // the character floor and the 4-char matched threshold, so only the uniqueness clause decides.
    // Unique ⇒ the run IS the sentence. Recurring ⇒ nothing in the key says which occurrence the
    // cross-encoder scored, so the mark is withheld — a NEW rejection class, and it fails closed.
    const unique = await render('Is the index rebuilt? Correct. The worker reopens the reader.', [
      cite('Correct.'),
    ]);
    expect(unique.renderRoot.querySelectorAll('.cite-sentence').length).toBe(1);
    expect(unique.renderRoot.querySelector('.cite-sentence')?.textContent).toContain('Correct');
    unique.remove();

    const ambiguous = await render('Correct. The worker reopens the reader. Correct.', [
      cite('Correct.'),
    ]);
    expect(ambiguous.renderRoot.querySelectorAll('.cite-sentence').length).toBe(0);
    ambiguous.remove();
  });

  it('REJECTS a key whose match is too small a share of it (the 0.4 slack, upper regime)', async () => {
    // keyWordChars ≈ 60, matched = "The kernel" (9): the slack is 24, the shortfall is ~51.
    const el = await render('The kernel is a shared substrate for every governed projection.', [
      cite('The kernel of an entirely different answer about unrelated matters entirely.'),
    ]);
    expect(el.renderRoot.querySelectorAll('.cite-sentence').length).toBe(0);
    el.remove();
  });
});
