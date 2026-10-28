/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 822 §C2/§2.2 (slice S4) — THE CONTAINMENT PROOF for the `--md-*` geometry vocabulary.
 *
 * S4 renames fifteen hard-coded literals in `MarkdownBlock`'s stylesheet into custom properties an
 * outer-tree consumer can re-point. Its whole claim is that shipped surfaces render BYTE-IDENTICALLY,
 * so the slice is only as good as the assertion that nothing moved. Three assertions carry it:
 *
 *  1. **Frozen defaults** — each of the fifteen defaults equals the literal the design table recorded
 *     from the pre-tokenization source. A typo in a default is caught against the RECORDED literal,
 *     not against itself.
 *  2. **Resolved-declaration equality** — for every selector the fifteen tokens touch, the stylesheet
 *     with the defaults substituted back in equals the pre-tokenization declaration set, property by
 *     property. `BEFORE` below is that set, transcribed verbatim from the parent commit's source
 *     (`git show HEAD:…/MarkdownBlock.ts`, rules at :286-364 of that revision). Three declarations
 *     are ADDED by the slice; each is listed in `COMPUTED_NOOP_ADDITIONS` with the reason it cannot
 *     move a pixel, and the test pins its exact value so it can never silently become a real one.
 *  3. **Call-site enumeration** — the ten shipped call sites, each with the test that covers it.
 *
 * Slice S5 adds the other half of §C2 — the `:host([prose])` variant, which carries the rules that do
 * not exist today (headings, tables, `hr`, `img`, task lists, the between-items rhythm). Its
 * containment is the SELECTOR's, not a value's, so it is proved differently and the last three
 * sections carry it:
 *
 *  4. **Selector gating** — no selector naming a variant element appears outside a `:host([prose])`
 *     rule, and no default-path rule reads a variant-only token. A leaked rule on a selector the
 *     fifteen tokens already touch (`blockquote`, `li`, `p`) additionally fails assertion 2 above.
 *  5. **Frozen variant tokens** — the variant declares its OWN defaults (on `:host([prose])`, never
 *     on `:host`, which would put declarations back on the default path), each pinned to the design
 *     §2.3 value and each resolving to a token that is really defined, so a bare host with no sv3
 *     sheet still renders a heading ramp rather than an unset one.
 *  6. **Truncate/expand** — the design spec's table rule as a pair: the clamped cell and its
 *     complement, gated on a user-interaction pseudo-class.
 *
 * These are source-level assertions on purpose: happy-dom does not compute cascaded shadow styles.
 * The live half of the proof was measured in a real browser against the running dev server (both
 * sides of one fixture, before and after the tokenization): the shipped-side probe — a bare
 * `<jf-markdown-block>` in the app's light DOM — reported **0 changed properties** across host, p,
 * inline code, pre, pre code, ul, li, a and blockquote, while the sv3-side probe (the same fixture
 * inside `<jf-sv3-main>`'s shadow root wearing `.sv3-markdown`) reported 40, all of them the targets
 * of the design's override column.
 */

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { MarkdownBlock } from './MarkdownBlock.js';
import './MarkdownBlock.js';
import '../../views/NavigateView.js';
import '../../views/SummarizeView.js';
import './ReasoningBlock.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

/* ── The stylesheet, parsed ───────────────────────────────────────────────────────────────────── */

const cssText = ((): string => {
  const styles = MarkdownBlock.styles as unknown;
  const sheets = Array.isArray(styles) ? styles : [styles];
  return sheets.map((s) => (s as { cssText: string }).cssText).join('\n');
})();

type Decls = ReadonlyMap<string, string>;

/** `prop: value;` pairs of one rule body, comments stripped, whitespace collapsed. */
function declarations(body: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const match of body.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([\w-]+)\s*:([^;]+);/g)) {
    out.set((match[1] as string).trim(), (match[2] as string).trim().replace(/\s+/g, ' '));
  }
  return out;
}

/** Every top-level rule as `selector → declarations` (at-rule bodies are walked, not indexed). */
function rulesOf(css: string): Map<string, Decls> {
  const out = new Map<string, Decls>();
  const walk = (text: string): void => {
    let i = 0;
    while (i < text.length) {
      const open = text.indexOf('{', i);
      if (open < 0) return;
      let depth = 1;
      let close = open + 1;
      for (; close < text.length && depth > 0; close += 1) {
        if (text[close] === '{') depth += 1;
        else if (text[close] === '}') depth -= 1;
      }
      const selector = text
        .slice(i, open)
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .trim()
        .replace(/\s+/g, ' ');
      const body = text.slice(open + 1, close - 1);
      if (selector.startsWith('@')) {
        if (!selector.startsWith('@keyframes')) walk(body);
      } else {
        out.set(selector, declarations(body));
      }
      i = close;
    }
  };
  walk(css);
  return out;
}

const RULES = rulesOf(cssText);
const HOST = RULES.get(':host') ?? new Map<string, string>();

/** The declared default of a `--md-*` token. */
const tokenDefault = (name: string): string => {
  const value = HOST.get(name);
  if (value === undefined) throw new Error(`:host declares no ${name}`);
  return value;
};

/** A declaration with every `var(--md-*)` replaced by its `:host` default — what a consumer that
 *  overrides NOTHING gets, which is exactly the shipped case this slice must not move. */
const resolve = (value: string): string =>
  value.replace(/var\((--md-[\w-]+)\)/g, (_all, name: string) => tokenDefault(name));

function resolvedRule(selector: string): Map<string, string> {
  const rule = RULES.get(selector);
  if (rule === undefined) throw new Error(`no rule for ${selector}`);
  const out = new Map<string, string>();
  for (const [prop, value] of rule) {
    if (prop.startsWith('--md-')) continue;
    out.set(prop, resolve(value));
  }
  return out;
}

/* ── 1. The frozen defaults (design §2.2, "identical default" column) ─────────────────────────── */

/**
 * Tempdoc 873 — the defaults DELIBERATELY retuned after the 822 freeze, recorded as
 * `name → [the 873 value, the superseded 822 literal]` — CURRENT first, because these tuples are
 * spread into `it.each` titles and a test name that states the old value as the current one is a
 * decision log that lies. The freeze was never "this value is correct forever";
 * it was "no value moves without a decision that says so". This list is that decision written down,
 * and the assertions below pin BOTH ends: the current default must be the 873 value AND must no
 * longer be the 822 one, so a revert is as loud as a drift.
 *
 * Diagnosis (873 §1): the chat read as a wall of uniform text. Bold at 600 was a half-step against
 * a 400 body; the heading weight was that same 600, so emphasis and structure looked alike; a
 * heading's 20px of air above was the same order as the gaps around it, so it did not end the
 * previous section; a 20px list indent left bullets hanging inside the paragraph column; and 4px
 * between list items was swallowed by a multi-line item's own leading.
 */
const RETUNED_873: ReadonlyArray<readonly [string, string, string]> = [
  // name                          now                    was (822)
  ['--md-list-indent', '1.75rem', '1.25rem'], //                        the list column becomes a column
  ['--md-heading-weight', '700', '600'], //                             a step a scanning reader can see
  ['--md-heading-margin', '1.75rem 0 0.5rem', '1.25rem 0 0.5rem'], //   the ABOVE half only
  ['--md-item-adjacent-gap', '0.375rem', '0.25rem'], //                 items stop running together
];

/**
 * Tempdoc 873 — the retunes that are NOT token defaults but RAW DECLARATIONS, which `RETUNED_873`
 * structurally cannot reach: its lookup resolves a name on `:host` / `:host([prose])`, and a rule's
 * own property lives in neither. `.md-content strong` is the case — the 822 tokenization never
 * touched it, so it is absent from `BEFORE` and from both frozen-default tables, and the weight
 * could be reverted to 600 with the whole suite still green. Same both-ends discipline, applied to
 * a property instead of a name: `[selector, property, the 873 value, the superseded literal]`.
 */
const RETUNED_873_DECLARATIONS: ReadonlyArray<readonly [string, string, string, string]> = [
  // selector             property       now    was
  ['.md-content strong', 'font-weight', '700', '600'],
];

/**
 * name → the literal this stylesheet carries. Every entry was the pre-tokenization literal at the
 * design's cited line; the four names in `RETUNED_873` now carry their retuned value instead, and
 * are marked below with the literal they superseded.
 */
const FROZEN_DEFAULTS: ReadonlyArray<readonly [string, string]> = [
  ['--md-line-height', '1.6'], //                                      :host line-height (:290)
  ['--md-block-gap', '0.25em'], //                                     p, ul, ol margin (:317, :345)
  ['--md-block-gap-wide', '0.5em'], //                                 pre, blockquote margin (:337, :362)
  ['--md-item-gap', '0.125em'], //                                     li margin (:349)
  ['--md-list-indent', '1.75rem'], //                                  ul, ol padding-left (873; was 1.25rem)
  ['--md-code-border', 'none'], //                                     inline code (absent before)
  ['--md-code-radius', '0.25rem'], //                                  inline code (:328)
  ['--md-code-padding', '0.125rem 0.375rem'], //                       inline code (:327)
  ['--md-code-size', 'var(--font-size-sm)'], //                        inline code (:330)
  ['--md-code-font', 'monospace'], //                                  inline code (:329)
  ['--md-pre-radius', '0.375rem'], //                                  pre (:335)
  ['--md-pre-padding', '0.625rem 0.75rem'], //                         pre (:334)
  ['--md-quote-border', '3px solid var(--border-subtle)'], //          blockquote border-left (:360)
  ['--md-quote-padding', '0.75rem'], //                                blockquote padding-left (:361)
  ['--md-link-decoration', 'underline'], //                            a (:353)
];

describe('the fifteen geometry tokens keep the literals the shipped stylesheet already carried', () => {
  it('declares all fifteen on :host — a name only some consumer defines is an undefined default', () => {
    const declared = [...HOST.keys()].filter((n) => n.startsWith('--md-')).sort();
    expect(declared).toEqual(FROZEN_DEFAULTS.map(([name]) => name).sort());
  });

  it.each(FROZEN_DEFAULTS)('%s defaults to the recorded literal %s', (name, literal) => {
    expect(tokenDefault(name)).toBe(literal);
  });

  it('nests the type token rather than copying its rem value (design §2.2 note on #9)', () => {
    // `0.8125rem` here would fork the ramp: the bridge re-points `--font-size-sm` and would miss it.
    expect(tokenDefault('--md-code-size')).toBe('var(--font-size-sm)');
  });

  it('keeps the code border a SHORTHAND whose default computes to zero width', () => {
    // `1px solid transparent` would shift every inline chip by 2px — the design's named trap.
    expect(tokenDefault('--md-code-border')).toBe('none');
  });
});

describe('the tempdoc-873 retunes are the only defaults that moved off the 822 freeze', () => {
  /** A retuned name lives on `:host` or on `:host([prose])` — the lookup follows it either way. */
  const currentDefault = (name: string): string | undefined =>
    HOST.get(name) ?? VARIANT_HOST.get(name);

  it.each(RETUNED_873)('%s is %s now, and is no longer the 822 literal %s', (name, now, was) => {
    expect(currentDefault(name), `${name} did not take its 873 value`).toBe(now);
    // The second half is the one that catches a revert: a "tidy-up" back to the frozen literal
    // would otherwise satisfy nothing but this line.
    expect(currentDefault(name), `${name} reverted to the superseded 822 literal`).not.toBe(was);
  });

  it.each(RETUNED_873_DECLARATIONS)(
    '%s { %s } is %s now, and is no longer %s',
    (selector, property, now, was) => {
      const rule = RULES.get(selector);
      expect(rule, `no rule for ${selector}`).toBeDefined();
      expect((rule as Decls).get(property), `${selector} { ${property} }`).toBe(now);
      expect(
        (rule as Decls).get(property),
        `${selector} { ${property} } reverted to the superseded ${was}`,
      ).not.toBe(was);
    },
  );

  it('retunes nothing the list does not name', () => {
    // Every OTHER default is still the 822 literal, asserted by the two frozen-default blocks
    // above; this pins the retune SET so a fifth silent change cannot hide beside four declared
    // ones. Both records are read here, so a name added to one must be added to the other.
    const declared = RETUNED_873.map(([name]) => name).sort();
    const pinned = [...FROZEN_DEFAULTS, ...VARIANT_DEFAULTS]
      .filter(([name, value]) => RETUNED_873.some(([n, now]) => n === name && value === now))
      .map(([name]) => name)
      .sort();
    expect(pinned).toEqual(declared);
  });
});

/* ── 2. Resolved-declaration equality against the pre-tokenization stylesheet ─────────────────── */

/**
 * The declarations of every touched selector, verbatim from the parent commit — except the ONE
 * property a `RETUNED_873` name resolves into (`ul, ol { padding-left }`), which carries its retuned
 * value and is marked inline. Everything else is still the pre-tokenization set, so this table keeps
 * doing its job for the other forty-odd properties.
 */
const BEFORE: ReadonlyArray<readonly [string, Readonly<Record<string, string>>]> = [
  [
    ':host',
    {
      display: 'block',
      'font-family': 'system-ui, -apple-system, sans-serif',
      'font-size': 'var(--font-size-sm)',
      'line-height': '1.6',
      color: 'var(--text-primary)',
      'word-wrap': 'break-word',
    },
  ],
  ['.md-content p', { margin: '0.25em 0' }],
  [
    '.md-content code',
    {
      background: 'var(--surface-tertiary)',
      padding: '0.125rem 0.375rem',
      'border-radius': '0.25rem',
      'font-family': 'monospace',
      'font-size': 'var(--font-size-sm)',
    },
  ],
  [
    '.md-content pre',
    {
      background: 'var(--surface-tertiary)',
      padding: '0.625rem 0.75rem',
      'border-radius': '0.375rem',
      'overflow-x': 'auto',
      margin: '0.5em 0',
    },
  ],
  [
    '.md-content pre code',
    { background: 'none', padding: '0', 'font-size': 'var(--font-size-xs)' },
  ],
  // `padding-left` is the 873 retune (was `1.25rem`); the margin is still the parent commit's.
  ['.md-content ul, .md-content ol', { margin: '0.25em 0', 'padding-left': '1.75rem' }],
  ['.md-content li', { margin: '0.125em 0' }],
  ['.md-content a', { color: 'var(--text-tint)', 'text-decoration': 'underline' }],
  [
    '.md-content blockquote',
    {
      'border-left': '3px solid var(--border-subtle)',
      'padding-left': '0.75rem',
      margin: '0.5em 0',
      color: 'var(--text-secondary)',
    },
  ],
];

/**
 * Declarations the slice ADDS. Each is a computed no-op on a consumer that overrides nothing, and
 * each is pinned so it cannot quietly become a real value:
 *  - `border: none` on the inline chip — the token's use site; zero width, identical to no border.
 *  - `border: none` on the block's inner code — the rule whose existing job is shedding the chip's
 *    clothes; the chip's own default is already `none`, so this moves nothing shipped, and it keeps
 *    a consumer that gives the CHIP an edge from drawing a second frame inside the code block.
 */
const COMPUTED_NOOP_ADDITIONS: Readonly<Record<string, Readonly<Record<string, string>>>> = {
  '.md-content code': { border: 'none' },
  '.md-content pre code': { border: 'none' },
};

describe('with no consumer override, every touched rule resolves to its pre-tokenization value', () => {
  it.each(BEFORE)('%s is unchanged, property by property', (selector, before) => {
    const after = resolvedRule(selector);
    for (const [prop, value] of Object.entries(before)) {
      expect(after.get(prop), `${selector} { ${prop} }`).toBe(value);
    }
  });

  it.each(BEFORE)('%s gained nothing beyond the declared computed no-ops', (selector, before) => {
    const additions = COMPUTED_NOOP_ADDITIONS[selector] ?? {};
    const extra = [...resolvedRule(selector).keys()].filter(
      (prop) => !(prop in before) && !(prop in additions),
    );
    expect(extra, `${selector} gained an undeclared declaration`).toEqual([]);
    for (const [prop, value] of Object.entries(additions)) {
      expect(resolvedRule(selector).get(prop), `${selector} { ${prop} }`).toBe(value);
    }
  });

  it('adds exactly one new rule — the hover affordance, identical to the resting default', () => {
    // Unconditional rather than variant-gated: shipped links are already underlined at rest, so
    // this changes nothing there; it restores the affordance for a consumer whose override removes
    // the resting underline (design §2.2 note on #15).
    const hover = RULES.get('.md-content a:hover');
    expect(hover).toBeDefined();
    expect(Object.fromEntries(hover as Decls)).toEqual({ 'text-decoration': 'underline' });
    expect(tokenDefault('--md-link-decoration')).toBe('underline');
  });

  it('leaves no geometry literal behind at a use site', () => {
    // The point of the exercise: a consumer can only re-point what the rule READS.
    for (const [selector, decls] of [
      ['.md-content p', ['margin']],
      ['.md-content code', ['padding', 'border', 'border-radius', 'font-family', 'font-size']],
      ['.md-content pre', ['padding', 'border-radius', 'margin']],
      ['.md-content ul, .md-content ol', ['margin', 'padding-left']],
      ['.md-content li', ['margin']],
      ['.md-content a', ['text-decoration']],
      ['.md-content blockquote', ['border-left', 'padding-left', 'margin']],
    ] as ReadonlyArray<readonly [string, readonly string[]]>) {
      const rule = RULES.get(selector) as Decls;
      for (const prop of decls) {
        expect(rule.get(prop), `${selector} { ${prop} }`).toMatch(/var\(--md-/);
      }
    }
    expect(RULES.get(':host')?.get('line-height')).toBe('var(--md-line-height)');
  });
});

/* ── 3. The ten shipped call sites, and what covers each ──────────────────────────────────────── */

/**
 * Every `<jf-markdown-block>` outside the Search v3 window. NONE of them re-points a `--md-*`
 * name and none sets an opt-in attribute, which is why the equality above is the whole story for
 * them. The two sv3 call sites (`Sv3Main.ts` — the turn answer and the agent-run text item) carry
 * `class="sv3-markdown"` and are covered by `Sv3Main.imports.test.ts` + `SearchV3View.markdown.test.ts`.
 *
 *  | # | Call site                     | What it renders              | Covered by                                        |
 *  |---|-------------------------------|------------------------------|---------------------------------------------------|
 *  | 1 | `UnifiedChatView.ts:4505`     | streaming agent answer       | `UnifiedChatView.test.ts` (:907, :917)            |
 *  | 2 | `UnifiedChatView.ts:5134`     | settled agent answer + marks | `UnifiedChatView.test.ts` (:2001, :2102, :2160)   |
 *  | 3 | `UnifiedChatView.ts:5477`     | extract answer (plain)       | `UnifiedChatView.test.ts` (:5596)                 |
 *  | 4 | `UnifiedChatView.ts:5482`     | RAG answer + claim marks     | `UnifiedChatView.test.ts` (:1863-1866)            |
 *  | 5 | `UnifiedChatView.ts:5489`     | chat/agent answer            | `UnifiedChatView.test.ts` (:1850-1866)            |
 *  | 6 | `UnifiedChatView.ts:5593`     | streaming extract (plain)    | `UnifiedChatView.test.ts` (:5596)                 |
 *  | 7 | `UnifiedChatView.ts:5598`     | streaming answer + claims    | `UnifiedChatView.test.ts` (:2102 computed-style)  |
 *  | 8 | `SummarizeView.ts:235`        | summary stream (plain)       | this file — no render assertion existed           |
 *  | 9 | `NavigateView.ts:143`         | navigate stream (plain)      | this file — no render assertion existed           |
 *  |10 | `ReasoningBlock.ts:181`       | the reasoning trace (nested) | this file — the component had no test file at all |
 *
 * The inventory itself is asserted below, so an eleventh call site cannot appear uncovered.
 */
const HERE = dirname(fileURLToPath(import.meta.url));
const source = (relative: string): string => readFileSync(join(HERE, '..', '..', relative), 'utf8');

/** file → the number of `<jf-markdown-block>` call sites it renders (the table above). */
const SHIPPED_CONSUMERS: ReadonlyArray<readonly [string, number]> = [
  ['views/SummarizeView.ts', 1],
  ['views/NavigateView.ts', 1],
  ['components/chat/ReasoningBlock.ts', 1],
];

/**
 * Tempdoc 869 §2.5 — the same inventory, EXTENDED (not forked) with the Search v3 window, for the
 * assertions that are about the citation props rather than the geometry containment. Sv3 is absent
 * from the coverage table above because its two sites are covered by `Sv3Main.imports.test.ts` +
 * `SearchV3View.markdown.test.ts`; it is present here because it is the surface 869 C2b wires, and
 * an inventory of "who renders marks" that omitted it would be the omission this row guards against.
 */
const CITATION_CONSUMERS: readonly string[] = [
  ...SHIPPED_CONSUMERS.map(([file]) => file),
  'views/search-v3/Sv3Main.ts',
];

describe('the shipped consumer inventory is closed', () => {
  it.each(SHIPPED_CONSUMERS)('%s renders exactly %i call site(s)', (file, count) => {
    const text = source(file);
    expect(text.match(/<jf-markdown-block/g)?.length ?? 0).toBe(count);
  });

  it('counts three shipped geometry call sites in total — another must claim its coverage row', () => {
    const total = SHIPPED_CONSUMERS.reduce((sum, [, count]) => sum + count, 0);
    expect(total).toBe(3);
  });

  it('gives every citation-passing call site the source list those citations are labelled against', () => {
    // Tempdoc 869 §2.5 (T-sites) — the source-level half of I-2, and the reason it is source-level:
    // the defect it guards is a call site that FORGETS a prop, which no DOM test of the renderer can
    // see. `.citations` and `.sourceCount` are two halves of one fact — the labels are `index + 1`
    // over the same source array — so a site that passes marks without the count tells the renderer
    // the model referenced nothing and silently turns the mute off for that surface. That is exactly
    // how the Search v3 window shipped without a `frame` (§2.0-1).
    for (const file of CITATION_CONSUMERS) {
      const text = source(file);
      for (const template of text.match(/<jf-markdown-block\s[\s\S]*?<\/jf-markdown-block>/g) ?? []) {
        if (!template.includes('.citations=')) continue;
        expect(template, `${file} passes .citations without .sourceCount`).toContain('.sourceCount=');
      }
    }
  });

  it('gives the Search v3 blocks the frame their own tail line words', () => {
    // The window computes the frame already (`sv3AnswerFrame`) and rendered its blocks at the
    // renderer's default `'grounded'` — so the receipt under the answer could say "per-sentence
    // grounding not verified" while the answer above it framed itself as grounded. One computation,
    // two projections: a site that binds marks here binds the frame too.
    const sv3 = source('views/search-v3/Sv3Main.ts');
    const sites = sv3.match(/<jf-markdown-block\s[\s\S]*?<\/jf-markdown-block>/g) ?? [];
    expect(sites).toHaveLength(2);
    for (const template of sites) {
      expect(template, 'a Search v3 block without its frame').toMatch(/\bframe=\$\{/);
    }
  });

  it('has no shipped consumer opting in or re-pointing a geometry token', () => {
    for (const [file] of SHIPPED_CONSUMERS) {
      const text = source(file);
      // The opt-in attribute (slice S5) and any `--md-*` declaration are both containment failures
      // here: a shipped surface that reaches the variant or moves a token is the thing S4 forbids.
      expect(text, `${file} sets the prose variant`).not.toMatch(/<jf-markdown-block[^>]*\sprose/);
      expect(text, `${file} re-points a geometry token`).not.toMatch(/--md-[\w-]+\s*:/);
    }
  });

  it('has the ONE opted-in consumer set it at BOTH its call sites — the positive control', () => {
    // Without this, the assertion above passes just as well against an attribute nobody sets and a
    // variant nothing reaches: "no shipped surface opts in" only means something once some surface
    // does. Search v3 is that surface (design §2.3/§2.4), at the settled answer and the agent-run
    // text item — and NOT at the reasoning trace, which keeps the shipped geometry on purpose.
    const sv3 = source('views/search-v3/Sv3Main.ts');
    // Matched on the class the window dresses the renderer with, so the third `<jf-markdown-block`
    // in that file — a mention inside a comment — is not counted as a call site.
    const sites = sv3.match(/<jf-markdown-block[^>]*\sclass="sv3-markdown"/g)?.length ?? 0;
    const opted = sv3.match(/<jf-markdown-block[^>]*\sprose/g)?.length ?? 0;
    expect(sites).toBe(2);
    expect(opted).toBe(2);
    expect(source('components/chat/ReasoningBlock.ts')).not.toMatch(
      /<jf-markdown-block[^>]*\sprose/,
    );
  });
});

describe('the ten shipped call sites render the block with no override of any kind', () => {
  it('renders SummarizeView’s summary stream through a plain, unattributed block', async () => {
    const el = document.createElement('jf-summarize-view');
    document.body.appendChild(el);
    await settle(el);
    (el as unknown as { streamingText: string }).streamingText = 'A `token` and a summary.';
    await settle(el);
    const block = el.shadowRoot?.querySelector('jf-markdown-block');
    expect(block).toBeTruthy();
    expect(block?.getAttribute('format')).toBe('plain');
    expect(block?.hasAttribute('prose')).toBe(false);
    expect(block?.className).toBe('');
    el.remove();
  });

  it('renders NavigateView’s stream through a plain, unattributed block', async () => {
    const el = document.createElement('jf-navigate-view');
    document.body.appendChild(el);
    await settle(el);
    (el as unknown as { streamingText: string }).streamingText = 'Navigating to `docs`.';
    await settle(el);
    const block = el.shadowRoot?.querySelector('jf-markdown-block');
    expect(block).toBeTruthy();
    expect(block?.getAttribute('format')).toBe('plain');
    expect(block?.hasAttribute('prose')).toBe(false);
    expect(block?.className).toBe('');
    el.remove();
  });

  it('renders the reasoning trace through a nested, unattributed block', async () => {
    // The nested block sits in ANOTHER shadow root, so no consumer stylesheet can select it: the
    // `:host` defaults are its geometry by construction (design §2.1, the stated known limit).
    const el = document.createElement('jf-reasoning-block');
    (el as unknown as { text: string }).text = 'Thinking about `x`.';
    document.body.appendChild(el);
    await settle(el);
    const block = el.shadowRoot?.querySelector('jf-markdown-block');
    expect(block).toBeTruthy();
    expect(block?.hasAttribute('prose')).toBe(false);
    expect(block?.className).toBe('');
    el.remove();
  });
});

/* ── 4. Selector gating — the variant's containment (slice S5) ────────────────────────────────── */

/** A selector naming an element the variant exists FOR: nothing outside the variant may style it. */
const VARIANT_ELEMENT = /(^|[\s,>+~(])(h[1-6]|table|thead|tbody|tfoot|tr|th|td|hr|img)(?![\w-])/;

const VARIANT_PREFIX = ':host([prose])';

const variantRules = (): string[] => [...RULES.keys()].filter((s) => s.startsWith(VARIANT_PREFIX));
const defaultRules = (): string[] => [...RULES.keys()].filter((s) => !s.startsWith(VARIANT_PREFIX));

/** Every `--md-*` name the variant block declares. */
const VARIANT_HOST = RULES.get(VARIANT_PREFIX) ?? new Map<string, string>();

describe('the prose variant is reachable ONLY through the attribute', () => {
  it('exists at all — a variant with no rules would pass every containment check below', () => {
    expect(variantRules().length).toBeGreaterThan(15);
  });

  it('has a detector that really detects — the gate below is not vacuous', () => {
    // `VARIANT_ELEMENT` is the whole force of the next assertion: if it matched nothing, "no leaked
    // rule" would be true of every stylesheet ever written. So it is first shown to match the
    // variant's own selectors (each of which WOULD be a leak if it lost its `:host([prose])`).
    const matched = variantRules().filter((s) => VARIANT_ELEMENT.test(s));
    for (const tag of ['h1', 'h2', 'h3', 'table', 'th, td', 'hr', 'img']) {
      expect(
        matched.some((s) => s.includes(tag)),
        `nothing the detector matched mentions ${tag}`,
      ).toBe(true);
    }
    expect(VARIANT_ELEMENT.test('.md-content h2')).toBe(true);
    expect(VARIANT_ELEMENT.test('.md-content table')).toBe(true);
    // …and does not fire on the default path's own selectors (`pre` is not `tr`, `code` is not `td`).
    expect(VARIANT_ELEMENT.test('.md-content pre code')).toBe(false);
    expect(VARIANT_ELEMENT.test('.md-content ul, .md-content ol')).toBe(false);
    expect(VARIANT_ELEMENT.test('.md-content strong')).toBe(false);
  });

  it('styles no heading, table, hr or img outside a :host([prose]) rule', () => {
    // The design's mechanism test: containment is a property of the SELECTOR, because a token cannot
    // express "this rule exists" and any declared default would change shipped rendering the moment
    // a model emits a heading. A consumer that never sets the attribute cannot be reached.
    const leaked = defaultRules().filter((selector) => VARIANT_ELEMENT.test(selector));
    expect(leaked, 'a variant element is styled on the default path').toEqual([]);
  });

  it('reads no variant-only token from a default-path rule', () => {
    // The other direction of the same leak: a default rule reading `--md-h2-size` would inherit the
    // variant's geometry through the back door, and the token's default lives in the variant block,
    // so it would resolve to nothing at all on a shipped surface.
    const variantOnly = [...VARIANT_HOST.keys()].filter((name) => name.startsWith('--md-'));
    for (const selector of defaultRules()) {
      const body = [...(RULES.get(selector) as Decls).values()].join(' ');
      for (const name of variantOnly) {
        expect(body, `${selector} reads the variant-only ${name}`).not.toContain(`var(${name})`);
      }
    }
  });

  it('declares the variant tokens on :host([prose]), never on :host', () => {
    // On `:host` they would be declarations ADDED to the default path — exactly what S4 froze (and
    // what the fifteen-name inventory assertion at the top of this file would fail on).
    const onDefaultHost = [...HOST.keys()].filter((n) => n.startsWith('--md-'));
    expect(onDefaultHost.sort()).toEqual(FROZEN_DEFAULTS.map(([name]) => name).sort());
    expect([...VARIANT_HOST.keys()].every((n) => n.startsWith('--md-'))).toBe(true);
  });
});

/* ── 5. The variant's own frozen defaults (design §2.3) ───────────────────────────────────────── */

/** name → the value design §2.3 records for it (or tempdoc 873's retune of it), and what it is for. */
const VARIANT_DEFAULTS: ReadonlyArray<readonly [string, string]> = [
  ['--md-heading-weight', '700'], //                           873 retune (was 600)
  ['--md-heading-line-height', '1.3'],
  ['--md-heading-margin', '1.75rem 0 0.5rem'], //              asymmetric: a heading owns what follows
  ['--md-table-size', 'var(--font-size-xs)'],
  ['--md-table-cell-padding', '0.45rem 0.75rem'],
  ['--md-table-rule', '1px solid var(--border-subtle)'],
  ['--md-table-cell-max', '24rem'], //                         the spec's truncation cap
  ['--md-rule', '1px solid var(--border-subtle)'],
  // 873 §3 — an `hr` no longer shares `--md-block-gap-wide` with `pre`/`blockquote`. A section
  // break spaced like a code fence read as one more block instead of as the end of something, so
  // it has its own name and the largest rhythm in prose.
  ['--md-rule-margin', '1.5rem'],
  ['--md-item-adjacent-gap', '0.375rem'], //                   873 retune (was 0.25rem)
];

/** The shipped token sheet — the definition site the variant's defaults resolve through. */
const TOKENS_CSS = readFileSync(join(HERE, '..', '..', '..', 'styles', 'tokens.css'), 'utf8');

/**
 * The spec dialect: every construct the variant exists for, in one answer. This is the gap report's
 * decisive experiment (§C2/§5.3) promoted to a fixture — the shape a model emits once the answer
 * grammar asks for structure, and the shape that renders as UA defaults without the variant.
 */
const SPEC_FIXTURE = [
  // The lead paragraph is load-bearing for the ASSERTIONS, not for the fixture's realism: under
  // happy-dom, DOMPurify's `<remove></remove>` prefix trick mis-parses and the sanitiser drops the
  // FIRST element of every fragment (verified: `<h1>a</h1><h2>b</h2>` sanitizes to `a<h2>b</h2>`).
  // A fixture opening with the heading would therefore assert against an artifact of the test DOM.
  'The lease is renewed on every acquire.',
  '',
  '## The lock',
  '',
  '| Step | Result |',
  '| --- | --- |',
  '| acquire | held |',
  '| release | free |',
  '',
  '---',
  '',
  '- [ ] renew the lease',
  '- [x] release the lock',
  '  - the nested note',
  '',
  '![the lease diagram](lease.png)',
].join('\n');

describe('the variant carries its own defaults, so it renders under any host', () => {
  it('declares exactly the recorded set', () => {
    expect([...VARIANT_HOST.keys()].sort()).toEqual(VARIANT_DEFAULTS.map(([n]) => n).sort());
  });

  it.each(VARIANT_DEFAULTS)('%s defaults to %s', (name, value) => {
    expect(VARIANT_HOST.get(name)).toBe(value);
  });

  it('spaces the section break by its OWN name, not the wide-block gap (873 §3)', () => {
    // The mechanism, not just the value: reading `--md-block-gap-wide` here is what made an `hr`
    // sit in exactly as much air as a `pre`, and a consumer re-pointing the wide gap for code
    // fences would drag the section break along with it. Both halves are pinned so neither can be
    // quietly undone.
    const hr = RULES.get(`${VARIANT_PREFIX} .md-content hr`) as Decls;
    expect(hr.get('margin')).toBe('var(--md-rule-margin) 0');
    expect(hr.get('margin')).not.toContain('--md-block-gap-wide');
  });

  it('takes the heading scale from the SHIPPED type ramp, step for step', () => {
    // The ramp is read DIRECTLY, not wrapped in a per-heading name: a second name for one value is
    // a fork, and it is what made the style-literal ratchet (rightly) stop reading these as
    // tokenized at all. A consumer retunes by re-pointing the ramp inside its own bridge — sv3's
    // `--font-size-sv3-*` steps already equal the spec's heading scale, which is how the spec's
    // numbers arrive without a single rem literal crossing into this component (§2.1).
    const ramp: ReadonlyArray<readonly [string, string]> = [
      ['h1', 'var(--font-size-xl)'],
      ['h2', 'var(--font-size-lg)'],
      ['h3', 'var(--font-size-md)'],
    ];
    for (const [tag, step] of ramp) {
      const rule = RULES.get(`${VARIANT_PREFIX} .md-content ${tag}`) as Decls;
      expect(rule, `no ${tag} rule`).toBeDefined();
      expect(rule.get('font-size'), `${tag} size`).toBe(step);
    }
    const deep = RULES.get(`${VARIANT_PREFIX} .md-content :is(h4, h5, h6)`) as Decls;
    expect(deep.get('font-size')).toBe('var(--font-size-sm)');
    // …and no DECLARATION anywhere in the sheet names one window's private vocabulary (the comments
    // may explain sv3's role; a value that referenced `--font-size-sv3-*` would BE the fork).
    for (const [selector, decls] of RULES) {
      for (const [prop, value] of decls) {
        expect(`${selector} { ${prop}: ${value} }`).not.toContain('sv3');
      }
    }
  });

  it('resolves every token it references — no unset value under a host with no sheet of its own', () => {
    // The failure this forbids is silent: an undefined custom property makes the whole declaration
    // invalid-at-computed-value-time, so a heading would fall back to the UA's 21px/700 — the very
    // rendering the variant exists to replace, and nothing would look broken enough to notice.
    const referenced = new Set<string>();
    for (const selector of [VARIANT_PREFIX, ...variantRules()]) {
      for (const value of (RULES.get(selector) as Decls).values()) {
        for (const m of value.matchAll(/var\((--[\w-]+)\)/g)) referenced.add(m[1] as string);
      }
    }
    expect(referenced.size).toBeGreaterThan(0);
    for (const name of referenced) {
      const definedHere = HOST.has(name) || VARIANT_HOST.has(name);
      const definedInTokens = new RegExp(`${name}\\s*:`).test(TOKENS_CSS);
      expect(definedHere || definedInTokens, `${name} is referenced but defined nowhere`).toBe(true);
    }
  });

  it('renders a heading, a table, a rule and a task list from the spec dialect', async () => {
    // The rules above only mean something against markup that actually appears: `md.parse` + the
    // sanitiser must emit each element, or the variant is styling nothing (the design's own reason
    // for leaving footnotes and GFM alerts out — no markup, no rule).
    const el = document.createElement('jf-markdown-block') as HTMLElement & { text: string };
    el.setAttribute('prose', '');
    el.text = SPEC_FIXTURE;
    document.body.appendChild(el);
    await settle(el);
    const content = el.shadowRoot?.querySelector('.md-content') as HTMLElement;
    expect(el.hasAttribute('prose')).toBe(true);
    expect(content.querySelector('h2')?.textContent).toBe('The lock');
    expect(content.querySelectorAll('table th')).toHaveLength(2);
    expect(content.querySelectorAll('table td')).toHaveLength(4);
    expect(content.querySelector('hr')).toBeTruthy();
    expect(content.querySelectorAll('li input[type="checkbox"]')).toHaveLength(2);
    expect(content.querySelectorAll('ul ul li')).toHaveLength(1);
    el.remove();
  });

  it('renders the SAME markup without the attribute — the variant changes clothes, not structure', async () => {
    const el = document.createElement('jf-markdown-block') as HTMLElement & { text: string };
    el.text = SPEC_FIXTURE;
    document.body.appendChild(el);
    await settle(el);
    const content = el.shadowRoot?.querySelector('.md-content') as HTMLElement;
    expect(el.hasAttribute('prose')).toBe(false);
    // Unstyled, not unrendered: the shipped surfaces have always emitted this markup and let the UA
    // dress it. S5 does not change what a shipped consumer renders, only what a variant one wears.
    expect(content.querySelector('h2')).toBeTruthy();
    expect(content.querySelector('table')).toBeTruthy();
    el.remove();
  });
});

/* ── 6. The spec's table rule: truncate, and its complement ───────────────────────────────────── */

const CELL = `${VARIANT_PREFIX} .md-content :is(th, td)`;
const EXPANDED = `${VARIANT_PREFIX} .md-content tr:hover :is(th, td), ${VARIANT_PREFIX} .md-content tr:focus-within :is(th, td)`;

describe('a table cell truncates by default and expands on demand', () => {
  it('clamps the cell to one line at the recorded cap', () => {
    const cell = RULES.get(CELL) as Decls;
    expect(cell, 'the cell rule is not where the test expects it').toBeDefined();
    expect(cell.get('max-inline-size')).toBe('var(--md-table-cell-max)');
    expect(VARIANT_HOST.get('--md-table-cell-max')).toBe('24rem');
    expect(cell.get('white-space')).toBe('nowrap');
    expect(cell.get('overflow')).toBe('hidden');
    expect(cell.get('text-overflow')).toBe('ellipsis');
  });

  it('restores word-boundary wrapping inside the cell, so a column’s floor is its longest word', () => {
    // The second half of the spec note. Search v3 sets `word-break: break-word` on the block (an
    // unbroken token in prose must not widen the measure) — inherited into a table that would let a
    // column collapse mid-word. These two declarations are what stop that at the cell.
    const cell = RULES.get(CELL) as Decls;
    expect(cell.get('word-break')).toBe('normal');
    expect(cell.get('overflow-wrap')).toBe('normal');
  });

  it('expands through a user-interaction pseudo-class, and undoes exactly what it clamped', () => {
    // The spec's expand is a button on a table component we do not port (the design forbids DOM
    // post-processing: `unsafeHTML` re-renders would fight it), so the affordance is the row. What
    // this pins is that the pair is COMPLEMENTARY — every property the expanded rule sets is one the
    // clamp set, to a different value. A rule that expanded without releasing the clamp, or that
    // released it unconditionally, fails here.
    const expanded = RULES.get(EXPANDED) as Decls;
    expect(expanded, 'the expand rule is not where the test expects it').toBeDefined();
    const cell = RULES.get(CELL) as Decls;
    for (const [prop, value] of expanded) {
      expect(cell.has(prop), `expanded sets ${prop}, which the clamp never set`).toBe(true);
      expect(value, `expanded ${prop} repeats the clamped value`).not.toBe(cell.get(prop));
    }
    expect(expanded.get('white-space')).toBe('normal');
    expect(EXPANDED).toContain(':hover');
    expect(EXPANDED).toContain(':focus-within');
  });

  it('scrolls a table that is wider than the column instead of widening the column', () => {
    // The bare `<table>` is its own scroll container (no wrapper is synthesized), so a wide table
    // cannot push the answer past the reading measure.
    const table = RULES.get(`${VARIANT_PREFIX} .md-content table`) as Decls;
    expect(table.get('display')).toBe('block');
    expect(table.get('overflow-x')).toBe('auto');
    expect(table.get('max-inline-size')).toBe('100%');
    expect(table.get('border-collapse')).toBe('collapse');
  });
});
