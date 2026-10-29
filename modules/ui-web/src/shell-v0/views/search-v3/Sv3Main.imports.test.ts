// SPDX-License-Identifier: Apache-2.0

/**
 * The SHIPPED components rendered inside the Search v3 window, dressed in the window's tokens
 * (tempdoc 822 Phase F9 — the F-series fit audit's action 1).
 *
 * The window and the shipped app's `:root` are two different scales — and until 852 S4 wired the
 * window's theme attribute they could also be in opposite MODES — so every custom property a nested
 * shipped component reads and the window does NOT re-point is not a taste
 * difference but a polarity inversion. The audit measured two of them on screen: a tool-call card
 * painting `rgb(248,249,252)` under text at `oklch(0.97 0 0)` (white on white) and a reasoning
 * block painting `rgba(15,23,42,.58)` slate on `oklch(14.5% 0 0)`.
 *
 * This file is the standing guard, and it computes rather than looks:
 *
 *  1. **Closure** — every `var(--…)` the component's own source reads appears in its bridge block.
 *     A token the window forgets is exactly how the defect arrives, so the component's source is
 *     the checklist, not a hand-copied list.
 *  2. **Contrast** — every text/surface pair those components can paint resolves through the real
 *     token graph (oklch → sRGB, `color-mix` with alpha composited over the layers beneath it) and
 *     clears WCAG AA. The pairs are read from the components' own stylesheets, cited per case.
 *  3. **Polarity** — the surfaces are darker than the text they carry, which is the one thing the
 *     white-on-white card could not have claimed.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Sv3Main } from './Sv3Main.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import { contrastRatio, relativeLuminance, WCAG_AA, type Rgb } from '../../themes/contrast.js';

/* ── The colour engine the browser would run, in the small part of CSS these tokens use ───────── */

interface Rgba {
  readonly rgb: Rgb;
  readonly a: number;
}

const clamp01 = (v: number): number => Math.min(1, Math.max(0, v));

/** oklch() → sRGB (Björn Ottosson's matrices), gamut-clipped the way a display clips it. */
function oklchToRgb(l: number, c: number, hDeg: number): Rgb {
  const h = (hDeg * Math.PI) / 180;
  const a = c * Math.cos(h);
  const b = c * Math.sin(h);
  const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const lin = [
    4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
    -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
    -0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_,
  ];
  return lin.map((v) => {
    const u = clamp01(v);
    const encoded = u <= 0.0031308 ? 12.92 * u : 1.055 * u ** (1 / 2.4) - 0.055;
    return Math.round(encoded * 255);
  }) as unknown as Rgb;
}

/** Split a comma list at top level (commas inside nested parens belong to the nested function). */
function splitTop(input: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < input.length; i += 1) {
    const ch = input[i];
    if (ch === '(') depth += 1;
    else if (ch === ')') depth -= 1;
    else if (ch === ',' && depth === 0) {
      parts.push(input.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(input.slice(start));
  return parts.map((p) => p.trim());
}

/** Everything between `name(` and its matching `)`. */
function argsOf(value: string, name: string): string {
  const open = value.indexOf(`${name}(`) + name.length + 1;
  let depth = 1;
  for (let i = open; i < value.length; i += 1) {
    if (value[i] === '(') depth += 1;
    else if (value[i] === ')') {
      depth -= 1;
      if (depth === 0) return value.slice(open, i);
    }
  }
  throw new Error(`unbalanced ${name}() in ${value}`);
}

/** Premultiplied sRGB mix, which is what `color-mix(in srgb, …)` computes. */
function mix(one: Rgba, weight: number, two: Rgba): Rgba {
  const a = weight * one.a + (1 - weight) * two.a;
  if (a === 0) return { rgb: [0, 0, 0], a: 0 };
  const channel = (i: number): number =>
    (weight * one.a * (one.rgb[i] as number) + (1 - weight) * two.a * (two.rgb[i] as number)) / a;
  return { rgb: [channel(0), channel(1), channel(2)], a };
}

function composite(top: Rgba, under: Rgb): Rgb {
  return [0, 1, 2].map(
    (i) => top.a * (top.rgb[i] as number) + (1 - top.a) * (under[i] as number),
  ) as unknown as Rgb;
}

/* ── The token graph, read from the sheets that ship ──────────────────────────────────────────── */

/** The declarations of the FIRST `:host { … }` block — the window's dark set, its default. */
function hostTokens(cssText: string): Map<string, string> {
  const body = cssText.slice(cssText.indexOf(':host {') + 7, cssText.indexOf('\n  }'));
  return declarations(body);
}

/**
 * The LIGHT set (`:host([theme='light'])`), which {@link hostTokens} deliberately skips. A palette
 * whose contrast is only ever computed in its default theme is half-checked: the light set inverts
 * `--foreground`, so every alpha wash keyed to it composites the other way and the subdued text
 * roles sit at completely different ratios (measurably the WORSE ones — see the tier-ink case below).
 */
function lightTokens(cssText: string): Map<string, string> {
  const at = cssText.indexOf(":host([theme='light']) {");
  if (at < 0) throw new Error('no light token block');
  return declarations(cssText.slice(at, cssText.indexOf('\n  }', at)));
}

/** `--name: value;` pairs, comments stripped. */
function declarations(body: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const match of body.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(--[\w-]+):([^;]+);/g)) {
    out.set((match[1] as string).trim(), (match[2] as string).trim().replace(/\s+/g, ' '));
  }
  return out;
}

/** A component's OWN stylesheet — the last entry in `static styles`. */
function ownStyles(ctor: { styles?: unknown }): string {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
}

/** The `<tag> { … }` bridge block inside a stylesheet. */
function bridgeFor(cssText: string, tag: string): Map<string, string> {
  const blocks = [...cssText.matchAll(new RegExp(`(^|[\\s}])${tag}\\s*\\{([^}]+)\\}`, 'gm'))];
  if (!blocks.length) throw new Error(`no bridge block for <${tag}>`);
  return new Map(blocks.flatMap(block => [...declarations(block[2]!)]));
}

const TOKENS = hostTokens(sv3Tokens.cssText);
const LIGHT = lightTokens(sv3Tokens.cssText);
const MAIN = ownStyles(Sv3Main);

const HERE = dirname(fileURLToPath(import.meta.url));
const componentSource = (file: string): string =>
  readFileSync(join(HERE, '..', '..', 'components', 'chat', file), 'utf8');

/** Resolve a CSS value to sRGB + alpha, following `var()` through the bridge and the token sheet. */
function color(expr: string, scope: Map<string, string>): Rgba {
  const value = expr.trim();
  if (value === 'transparent') return { rgb: [0, 0, 0], a: 0 };
  if (value.startsWith('var(')) {
    // The FALLBACK arm matters, it is not convenience: the cite rules are authored as
    // `var(--md-cite-…, <today's value>)`, so a bridge line DELETED from the window does not raise —
    // it silently falls back and repaints. A resolver that threw on the missing name would report
    // that regression as an error rather than as the wrong colour it actually is.
    const [name, fallback] = splitTop(argsOf(value, 'var')) as [string, string | undefined];
    const next = scope.get(name) ?? TOKENS.get(name) ?? fallback;
    if (next === undefined) throw new Error(`unresolved ${name}`);
    return color(next, scope);
  }
  if (value.startsWith('color-mix(')) {
    const [, first, second] = splitTop(argsOf(value, 'color-mix')) as [string, string, string];
    const percent = first.match(/\s(\d+(?:\.\d+)?)%$/);
    if (percent === null) throw new Error(`no weight in ${value}`);
    const weight = Number(percent[1]) / 100;
    return mix(
      color(first.slice(0, first.length - (percent[0] as string).length), scope),
      weight,
      color(second, scope),
    );
  }
  if (value.startsWith('oklch(')) {
    const [l, c, h] = argsOf(value, 'oklch').trim().split(/\s+/) as [string, string, string];
    return { rgb: oklchToRgb(Number.parseFloat(l) / 100, Number(c), Number(h)), a: 1 };
  }
  throw new Error(`unhandled colour value: ${value}`);
}

/** Paint a stack of (possibly translucent) surfaces from the window background up. */
function surface(layers: readonly string[], scope: Map<string, string>): Rgb {
  let under = composite(color('var(--background)', scope), [0, 0, 0]);
  for (const layer of layers) under = composite(color(layer, scope), under);
  return under;
}

interface Pair {
  readonly what: string;
  readonly text: string;
  readonly on: readonly string[];
  /** A FILLED control inverts on purpose (dark ink on the accent); only its ratio is checked. */
  readonly filled?: boolean;
}

function check(tag: string, pairs: readonly Pair[]): void {
  const scope = bridgeFor(MAIN, tag);
  for (const pair of pairs) {
    const bg = surface(pair.on, scope);
    const fg = composite(color(pair.text, scope), bg);
    const ratio = contrastRatio(fg, bg);
    expect(ratio, `${tag} — ${pair.what} (${ratio.toFixed(2)}:1)`).toBeGreaterThanOrEqual(WCAG_AA);
    if (pair.filled === true) continue;
    expect(
      relativeLuminance(bg),
      `${tag} — ${pair.what}: the surface is LIGHTER than its text (the light-theme fall-through)`,
    ).toBeLessThan(relativeLuminance(fg));
  }
}

/**
 * Every custom property the component reads must be re-pointed by the window — unless the bridge
 * DELIBERATELY leaves it at the component's own default, which is a decision that has to be written
 * down. `exempt` is that written-down list: a name may only be skipped with a reason, so "the window
 * forgot it" (the defect this file exists for) can never look like "the window meant it".
 */
function assertClosed(
  tag: string,
  source: string,
  alsoRead: readonly string[] = [],
  exempt: Readonly<Record<string, string>> = {},
): void {
  const scope = bridgeFor(MAIN, tag);
  const consumed = new Set<string>(alsoRead);
  for (const match of source.matchAll(/var\((--[\w-]+)/g)) consumed.add(match[1] as string);
  const missing = [...consumed].filter((name) => !scope.has(name) && !(name in exempt)).sort();
  expect(missing, `<${tag}> reads tokens the window never re-points`).toEqual([]);
}

/**
 * The renderer's block-geometry vocabulary (tempdoc 822 §2.2, slice S4). Declared on the component's
 * own `:host` with the shipped literals, so an unbridged name is a deliberate "keep the shipped
 * value", not a fall-through to another theme.
 */
const MD_GEOMETRY = [
  '--md-line-height',
  '--md-block-gap',
  '--md-block-gap-wide',
  '--md-item-gap',
  '--md-list-indent',
  '--md-code-border',
  '--md-code-radius',
  '--md-code-padding',
  '--md-code-size',
  '--md-code-font',
  '--md-pre-radius',
  '--md-pre-padding',
  '--md-quote-border',
  '--md-quote-padding',
  '--md-link-decoration',
] as const;

/**
 * The prose variant's own vocabulary (tempdoc 822 §2.3, slice S5) — declared on the renderer's
 * `:host([prose])` rather than its `:host`, because these rules do not exist on the default path at
 * all. Same closure discipline as above: this window OPTS IN at both transcript call sites, so every
 * name here is either re-pointed or carries a written reason for keeping the variant's own default.
 */
const MD_PROSE = [
  '--md-heading-weight',
  '--md-heading-line-height',
  '--md-heading-margin',
  '--md-table-size',
  '--md-table-cell-padding',
  '--md-table-rule',
  '--md-table-cell-max',
  '--md-rule',
  '--md-rule-margin',
  '--md-item-adjacent-gap',
] as const;

/** The shipped ramp steps the variant's headings read directly — h1/h2/h3 (h4-h6 take `-sm`). */
const MD_PROSE_RAMP = ['--font-size-xl', '--font-size-lg', '--font-size-md'] as const;

/**
 * The citation mark's own vocabulary (tempdoc 822 citation-mark presentation §5.2/§5.3). Unlike the
 * block geometry above these are declared as inline `var(name, default)` fallbacks in the cite rules
 * themselves, not on the renderer's `:host` — that block belongs to the block-geometry workstream,
 * whose containment proof enumerates its fifteen names exactly.
 */
const MD_CITE = [
  // Tempdoc 869 C3 — the two tiers the renderer states POSITIVELY. `--md-cite-grounded-color` is the
  // strongest tier's ink, moved off the base `.cite-ref` rule so that "no grounding class" resolves
  // to neutral instead of strongest; `--md-cite-source-color` is that neutral, on the base rule.
  '--md-cite-grounded-color',
  '--md-cite-pad-x',
  '--md-cite-pad-x-rest',
  '--md-cite-radius',
  '--md-cite-region-bg',
  '--md-cite-region-inset-x',
  '--md-cite-region-pad-x',
  '--md-cite-selected-bg',
  '--md-cite-selected-edge',
  '--md-cite-source-color',
  '--md-cite-ungrounded-color',
  '--md-cite-weak-color',
] as const;

const reasons = (names: readonly string[], why: string): Record<string, string> =>
  Object.fromEntries(names.map((name) => [name, why]));

/* ── 1. Closure: nothing falls through to the shipped app's light `:root` ─────────────────────── */

describe('the three imported components read NO token the window leaves unbridged', () => {
  it('dresses <jf-tool-call-card>, including the tokens the nested <jf-run-node> glyph resolves', () => {
    // Tempdoc 867 removed the card's own inline-coloured status word (status is the glyph now), but
    // the NESTED `<jf-run-node>` (`RunNode.ts`) still colours its glyph from `statusTone.ts`'s
    // `toneAccent`, which returns `var(--accent-<tone>)` — a custom property the naive source scan
    // cannot see (it is JS-computed, not literal text in either file), yet it still resolves through
    // ToolCallCard's OWN bridge scope: shadow-DOM custom-property inheritance carries it from the
    // `jf-tool-call-card` host selector down into the nested element's shadow tree.
    assertClosed('jf-tool-call-card', componentSource('ToolCallCard.ts'), [
      '--accent-success',
      '--accent-warning',
      '--accent-danger',
    ]);
  });

  it('dresses <jf-reasoning-block> and the markdown block nested inside it', () => {
    // The nested renderer is outside the `.sv3-markdown` class bridge, so its tokens arrive through
    // the reasoning block's host or not at all.
    assertClosed(
      'jf-reasoning-block',
      componentSource('ReasoningBlock.ts') + componentSource('MarkdownBlock.ts'),
      [],
      {
        ...reasons(
          MD_GEOMETRY,
          'the reasoning trace keeps the SHIPPED geometry (tempdoc 822 §2.1): a compact trace ' +
            'should not adopt prose rhythm, and a :host declaration blocks inheritance, so this is ' +
            'the outcome by construction — the remedy, if it is ever wanted, is a forwarding block ' +
            'in ReasoningBlock.ts, not a re-point here.',
        ),
        ...reasons(
          [...MD_PROSE, ...MD_PROSE_RAMP],
          'the prose variant (tempdoc 822 §2.3, slice S5) is UNREACHABLE here: the reasoning block ' +
            'never sets the `prose` attribute, so every rule reading these names — the variant ' +
            'defaults on the renderer\'s own `:host([prose])`, and the ramp steps its headings read ' +
            'directly — applies to no element in the trace. Re-pointing them from this bridge would ' +
            'dress a surface that cannot render.',
        ),
        ...reasons(
          ['--md-pseudo-cite-color'],
          'the MUTED-ref ink (tempdoc 869 §3.6) is UNREACHABLE here for the same reason one step ' +
            'further out: `ReasoningBlock.ts:181` passes no `.citations`, no `frame` and no ' +
            '`.sourceCount`, so the block frames itself `grounded` and the mute — the ungrounded ' +
            'frame\'s alone since 867 §7 — never runs. No `.pseudo-cite` element exists in the ' +
            'trace to ink. The ANSWER blocks, which do reach it, are inside the `.sv3-markdown` ' +
            'bridge, where it is re-pointed.',
        ),
        ...reasons(
          MD_CITE,
          'the citation-mark vocabulary (tempdoc 822 citation-mark presentation) is UNREACHABLE ' +
            'here for the same reason: `ReasoningBlock.ts:181` renders the block with no ' +
            '`.citations`, so `decorateCitations` never runs and no `.cite-ref` / `.cite-sentence` ' +
            'element exists in the trace. A reasoning trace cites nothing — it is the model ' +
            'thinking, not an answer with sources.',
        ),
      },
    );
  });

  it('dresses the transcript markdown itself — the geometry half of the .sv3-markdown bridge', () => {
    // The answer prose is the one place the spec's block geometry applies, and it arrives through
    // the class bridge on the host element (an outer-tree rule on the host beats the component's
    // own `:host`). Two of the fifteen are deliberately absent.
    const KEEPS_SHIPPED: Readonly<Record<string, string>> = {
      '--md-list-indent':
        'sv3 keeps the shipped value (1.75rem since tempdoc 873 §3 retuned the shared default ' +
        'for every prose surface — sv3 takes whatever it is, which is the point of not forking it)',
      '--md-pre-padding': 'sv3 keeps the shipped value (0.625rem 0.75rem)',
    };
    const scope = bridgeFor(MAIN, '\\.sv3-markdown');
    const missing = MD_GEOMETRY.filter((name) => !scope.has(name) && !(name in KEEPS_SHIPPED));
    expect(missing, 'the transcript prose reads a geometry token the window never re-points').toEqual(
      [],
    );
    for (const name of Object.keys(KEEPS_SHIPPED)) {
      expect(scope.has(name), `${name} is allow-listed but ALSO re-pointed`).toBe(false);
    }
    // The spec targets that make the difference visible, pinned so a later tidy cannot flatten them
    // back onto the shipped values (design §2.2's override column).
    expect(scope.get('--md-code-size')).toBe('var(--font-size-sv3-xs)');
    expect(scope.get('--md-code-font')).toBe('var(--font-mono)');
    expect(scope.get('--md-code-border')).toBe('1px solid var(--border)');
    // Tempdoc 873 §3 — the two gaps are DIFFERENT names for a reason, so they are pinned as a pair
    // and as an inequality. Collapsing them onto one value (both were `--space-2-5`) is what made a
    // code fence, a table and a quote sit in exactly the air of the paragraph before them; a later
    // "tidy" that re-flattens them would pass two equalities but not the third assertion.
    expect(scope.get('--md-block-gap')).toBe('var(--space-3)');
    expect(scope.get('--md-block-gap-wide')).toBe('var(--space-5)');
    expect(scope.get('--md-block-gap')).not.toBe(scope.get('--md-block-gap-wide'));
    expect(scope.get('--md-pre-radius')).toBe('var(--radius)');
    expect(scope.get('--md-link-decoration')).toBe('none');
  });

  it('dresses the prose variant it opts into — the heading ramp re-pointed, the rest by decision', () => {
    // Slice S5. The window sets `prose` on both transcript blocks, so the variant's names are LIVE
    // here and the same closure rule applies: re-pointed, or written down. What is deliberately not
    // re-pointed is the majority — because the variant's own defaults already ARE the spec's
    // numbers, or they read a token this bridge has already re-pointed one line above.
    const KEEPS_VARIANT: Readonly<Record<string, string>> = {
      '--md-heading-weight':
        'the variant default (700 since tempdoc 873 §2) is the value this window wants, and it is ' +
        'wanted on every prose surface — re-pointing it here would fork one window off the shared ' +
        'decision instead of carrying it',
      '--md-heading-line-height': 'the variant default (1.3) is already the spec value',
      '--md-heading-margin':
        'the variant default (1.75rem 0 0.5rem since tempdoc 873 §3) is the asymmetric margin this ' +
        'window wants: 28px above ends the previous section, 8px below keeps the heading with its own',
      '--md-table-size':
        'the variant reads --font-size-xs, which this bridge re-points to --font-size-sv3-xs ' +
        '(12px) — re-pointing it here would be a second authority for one value',
      '--md-table-cell-padding': 'the variant default (0.45rem 0.75rem) is already the spec value',
      '--md-table-rule':
        'the variant reads --border-subtle, which this bridge re-points to the window --border',
      '--md-rule': 'same as --md-table-rule: the rule hue arrives through the re-pointed --border-subtle',
      '--md-table-cell-max': 'the variant default (24rem) is already the spec truncation cap',
      '--md-rule-margin':
        'tempdoc 873 §3 gave the section break its own name so it stops sharing the wide-block gap; ' +
        'the shared default (1.5rem) is the rhythm this window wants, and it is the LARGEST in prose ' +
        'by design — an hr ends a section, it is not one more block',
      '--md-item-adjacent-gap':
        'the variant default (0.375rem since tempdoc 873 §3) is the li + li gap this window wants',
    };
    const scope = bridgeFor(MAIN, '\\.sv3-markdown');
    const missing = MD_PROSE.filter((name) => !scope.has(name) && !(name in KEEPS_VARIANT));
    expect(missing, 'the transcript prose reads a variant token the window never re-points').toEqual(
      [],
    );
    for (const name of Object.keys(KEEPS_VARIANT)) {
      expect(scope.has(name), `${name} is allow-listed but ALSO re-pointed`).toBe(false);
    }
    // What DOES move: the heading scale, re-pointed as the RAMP the variant reads directly — the
    // same idiom as the two steps the bridge already carries for the inline chip and the code block.
    // The spec's scale IS this window's ramp, step for step, which is why no
    // rem literal of the spec's is copied into the shared renderer (design §2.1).
    expect(scope.get('--font-size-xl')).toBe('var(--font-size-sv3-xl)');
    expect(scope.get('--font-size-lg')).toBe('var(--font-size-sv3-lg)');
    expect(scope.get('--font-size-md')).toBe('var(--font-size-sv3-base)');
    expect(TOKENS.get('--font-size-sv3-xl')).toBe('1.25rem'); //   spec h1
    expect(TOKENS.get('--font-size-sv3-lg')).toBe('1.125rem'); //  spec h2
    expect(TOKENS.get('--font-size-sv3-base')).toBe('1rem'); //    spec h3
  });

  it('lifts the answer prose to 15px WITHOUT dragging the window ramp with it (873 §4)', () => {
    // Tempdoc 873 §4. `--font-size-sm` is the ONE name that carries both halves of the body-size
    // change: the renderer's `:host` reads it for the block's own `font-size`, and the variant's
    // h4-h6 read it too. So this single re-point lifts body text off 14px and, in the same move,
    // stops the ramp's bottom step from landing BELOW the body it leads — h4-h6 sit AT body size,
    // distinguished by weight, which is what that step has always meant.
    const prose = bridgeFor(MAIN, '\\.sv3-markdown');
    expect(prose.get('--font-size-sm')).toBe('0.9375rem');

    // The containment half, and the reason this is not just "make the chat bigger": the window's
    // own ramp step is untouched, so every OTHER surface — tool cards, the reasoning trace, the
    // sources panel, the hover card — stays at 14px. A future edit that lifted the token instead
    // of the bridge would pass the line above and fail this one.
    expect(TOKENS.get('--font-size-sv3-sm')).toBe('0.875rem');
    for (const tag of ['jf-tool-call-card', 'jf-reasoning-block', 'jf-citation-hover-card']) {
      expect(bridgeFor(MAIN, tag).get('--font-size-sm'), tag).toBe('var(--font-size-sv3-sm)');
    }
    // …and the shared colour/size bridge (which `.sv3-citations` rides) still carries the 14px
    // step: the re-point lives in the `.sv3-markdown`-only rule, which is later and therefore wins
    // for the answer prose alone.
    expect(bridgeFor(MAIN, '\\.sv3-citations').get('--font-size-sm')).toBe(
      'var(--font-size-sv3-sm)',
    );
  });


  it('caps the answer column at the spec measure, on the window and not in the renderer', () => {
    // Tempdoc 822 §2.5 — the measure is the COLUMN's property; the shipped chat sets it on its own
    // container for the same reason. The name is the shipped concept's, re-pointed, not a second
    // measure vocabulary.
    expect(MAIN).toMatch(/max-inline-size: var\(--measure-prose\)/);
    expect(TOKENS.get('--measure-prose')).toBe('48rem');
  });

  it('dresses <jf-citation-hover-card>', () => {
    assertClosed('jf-citation-hover-card', componentSource('CitationHoverCard.ts'));
  });
});

/* ── 2. Contrast, computed from the token values ──────────────────────────────────────────────── */

describe('every text/surface pair those components can paint clears WCAG AA on the dark window', () => {
  it('keeps the tool-call card legible — the white-on-white case the audit measured', () => {
    // Tempdoc 867 flattened the card to one header disclosure and replaced the nested
    // `<jf-results-card>` with a level-2 search body; the inline-coloured status word and the
    // (already-unrendered since 550 C3) `.tool-actions` buttons are gone. Pairs read from the
    // current `ToolCallCard.ts`: the card fill (`.tool-card`), its wells (`.tool-args`/`.tool-output`),
    // the quoted frame (`.tool-output.lineage-corpus-quoted` + `.lineage-frame-label`), the subdued
    // rungs (`.tool-target`/`.tool-card-accessory`/`.risk-word`/`.risk-why`/`.because`/the search
    // body's `.search-scope`/`.search-row-path`/`.search-row-locator`/`.search-more`), the "Open in
    // Search" pill (`.search-open-in-search`), the rejected reason (`.rejected-reason`) and the
    // resource link (`.tool-resource a`).
    check('jf-tool-call-card', [
      { what: 'tool name / search-row filename on the card', text: 'var(--foreground)', on: ['var(--surface-secondary)'] },
      { what: 'tool target, risk tier, risk-why, because, accessory, search scope/path/locator/footer', text: 'var(--text-secondary)', on: ['var(--surface-secondary)'] },
      { what: 'args / output text in a well', text: 'var(--foreground)', on: ['var(--surface-secondary)', 'var(--surface-tertiary)'] },
      { what: 'Open in Search pill label', text: 'var(--text-primary)', on: ['var(--surface-secondary)', 'var(--surface-tertiary)'] },
      // Tempdoc 871 §3b — the level-2 "used" marking paints a tinted wash UNDER the row and a
      // deeper one behind the tag, so both stacks are measured on top of the card fill.
      // A used row's path/locator inherit the row colour rather than staying `--text-secondary`:
      // this oracle measured that pair at 4.31:1 on the tint, which is why the rule exists.
      { what: 'used-row title and path/locator on the tinted row', text: 'var(--text-primary)', on: ['var(--surface-secondary)', 'var(--accent-tint-08)'] },
      { what: 'the "used" tag on its own tint', text: 'var(--text-tint)', on: ['var(--surface-secondary)', 'var(--accent-tint-08)', 'var(--accent-tint-16)'] },
      { what: 'lineage frame label on the quoted frame', text: 'var(--text-secondary)', on: ['var(--surface-secondary)', 'var(--surface-2)'] },
      { what: 'rejected reason', text: 'var(--text-warning)', on: ['var(--surface-secondary)'] },
      { what: 'resource link', text: 'var(--accent)', on: ['var(--surface-secondary)'] },
    ]);
  });

  it('keeps the reasoning block legible — the slate-on-black case the audit measured', () => {
    // Pairs read from `ReasoningBlock.ts`: the container (:65-71), the hover emphasis (:80,105),
    // and the nested markdown whose text it re-points to `--text-muted` (:120-123).
    check('jf-reasoning-block', [
      { what: 'thinking text on the container', text: 'var(--text-muted)', on: ['var(--surface-subtle)'] },
      { what: 'header under the pointer', text: 'var(--text-secondary)', on: ['var(--surface-subtle)'] },
      { what: 'copy button under the pointer', text: 'var(--text-secondary)', on: ['var(--surface-subtle)', 'var(--surface-subtle)'] },
      { what: 'nested markdown body', text: 'var(--text-muted)', on: ['var(--surface-subtle)'] },
      { what: 'nested markdown code', text: 'var(--text-muted)', on: ['var(--surface-subtle)', 'var(--surface-tertiary)'] },
      { what: 'nested markdown link', text: 'var(--text-tint)', on: ['var(--surface-subtle)'] },
    ]);
  });

  it('keeps the citation preview legible', () => {
    // Pairs read from `CitationHoverCard.ts`: the card (:57-64), the document name (:66-72), the
    // excerpt (:74) and the match score (:82-84).
    check('jf-citation-hover-card', [
      { what: 'card text', text: 'var(--text-primary)', on: ['var(--surface-2)'] },
      { what: 'document name', text: 'var(--text-tint)', on: ['var(--surface-2)'] },
      { what: 'excerpt', text: 'var(--text-secondary)', on: ['var(--surface-2)'] },
      { what: 'match score', text: 'var(--text-muted)', on: ['var(--surface-2)'] },
    ]);
  });
});

/* ── 2b. The v3 selection bridge — the lines the whole citation-mark design hangs from ────────── */

/**
 * Tempdoc 822 citation-mark presentation §5.2/§5.3/§5.4, hardened after independent review.
 *
 * Every rule the design touches is authored as `var(--name, <today's value>)`, which is what makes
 * the shipped windows byte-identical — and it is also why DELETING any one of these bridge lines is
 * silent: the rule falls back, the mark reverts to `--accent-tint` (= `--primary`, the composer's
 * send button — the act-now spend the whole design exists to avoid), and every other test in the
 * repo stays green. That is the same shape as the defect this slice was written to fix, so the
 * bridge itself needs an assertion, not just the things it feeds.
 */
const CITE_BRIDGE: Readonly<Record<string, string>> = {
  '--md-cite-selected-bg': 'var(--sv3-selected)',
  '--md-cite-selected-edge': 'var(--sv3-selected-edge)',
  '--md-cite-weak-color': 'var(--sv3-cite-weak)',
  '--md-cite-ungrounded-color': 'var(--sv3-cite-ungrounded)',
  // Tempdoc 869 C3 — the two POSITIVE tiers. `--md-cite-grounded-color` restates the ink this
  // window already painted through `--text-tint`; `--md-cite-source-color` is the tier-2 mark's
  // neutral, the body foreground rather than the renderer's `--text-secondary` default (which this
  // window re-points to `--muted-foreground`, under AA for a 12px glyph).
  '--md-cite-grounded-color': 'var(--info-foreground)',
  '--md-cite-source-color': 'var(--foreground)',
  '--md-cite-region-bg': 'var(--sv3-selected-region)',
  '--md-cite-pad-x-rest': '0.25em',
  '--md-cite-pad-x': '0.25em',
  '--md-cite-radius': '0.25em',
  '--md-cite-region-pad-x': '0.25em',
  '--md-cite-region-inset-x': '-0.25em',
  '--cp-selected-region': 'var(--sv3-selected-region)',
  '--cp-selected-edge': 'var(--sv3-selected-edge-strong)',
  '--cp-selected': 'var(--sv3-selected-region)',
  '--cp-hover-edge': 'var(--sv3-selected-edge)',
  '--cp-selected-hover-edge': 'var(--sv3-selected-edge-strong)',
};

describe('the citation-selection bridge exists and points at the window\'s own material', () => {
  const scope = bridgeFor(MAIN, '\\.sv3-citations');

  it('declares every bridged name, at the value the design assigns it', () => {
    for (const [name, value] of Object.entries(CITE_BRIDGE)) {
      expect(scope.get(name), `${name} is missing from the .sv3-markdown/.sv3-citations bridge`).toBe(
        value,
      );
    }
  });

  it('bridges EXACTLY the selection names the two components read — no more, no fewer', () => {
    // The components' own sources are the checklist (same discipline as `assertClosed`), so a name
    // added to a cite rule without a bridge line, or a bridge line left behind after its rule went
    // away, both fail here instead of drifting.
    const read = new Set<string>();
    for (const file of ['MarkdownBlock.ts', 'CitationsPanel.ts']) {
      for (const m of componentSource(file).matchAll(/var\((--(?:md-cite|cp)-[\w-]+)/g)) {
        read.add(m[1] as string);
      }
    }
    expect([...read].sort(), 'the selection vocabulary the components actually read').toEqual(
      Object.keys(CITE_BRIDGE).sort(),
    );
  });

  it('resolves every bridged colour through the token sheet in BOTH themes', () => {
    // A `--sv3-*` target that does not exist would leave the property invalid-at-computed-value and
    // the rule would paint its fallback — the silent revert again, one layer down.
    const colours = Object.keys(CITE_BRIDGE).filter((n) => !CITE_BRIDGE[n]?.endsWith('em'));
    for (const theme of ['dark', 'light'] as const) {
      const themed = themeScope(theme, scope);
      for (const name of colours) {
        expect(() => color(`var(${name})`, themed), `${name} on the ${theme} window`).not.toThrow();
      }
    }
  });
});

/* ── 2c. The tier ink survives the wash painted behind it (the §7.5 item) ─────────────────────── */

/** Token lookup for one theme, with a component bridge layered on top. */
function themeScope(theme: 'dark' | 'light', bridge: Map<string, string>): Map<string, string> {
  const base = theme === 'light' ? [...TOKENS, ...LIGHT] : [...TOKENS];
  return new Map<string, string>([...base, ...bridge]);
}

describe('a SELECTED citation mark keeps its grounding tier above the AA floor', () => {
  /**
   * The slice's thesis is that the honesty signal must survive the moment of scrutiny. It survived
   * in HUE — `.cite-selected` no longer sets `color` — and broke in CONTRAST: selecting a low tier
   * paints a 9 % wash behind a 12 px numeral, and both subdued tiers were measured under 4.5:1 on
   * that composite (dark grey 4.22, light grey 3.97, light amber 4.14). A naive foreground-on-
   * BACKGROUND check passes all four and sees none of it, so the wash has to be composited first.
   *
   * The declarations are written the way the renderer writes them — fallback included — so removing
   * the bridge line does not error, it computes the un-lifted value and fails on the ratio.
   */
  const TIERS = [
    { what: 'weak (grey)', ink: 'var(--md-cite-weak-color, var(--text-secondary))' },
    { what: 'ungrounded (amber)', ink: 'var(--md-cite-ungrounded-color, var(--text-warning))' },
  ] as const;
  const NORMAL = 'var(--text-tint)';
  const SELECTED_MARK = ['var(--md-cite-selected-bg, var(--accent-tint))'] as const;

  const ratioOn = (ink: string, layers: readonly string[], scope: Map<string, string>): number => {
    const bg = surface(layers, scope);
    return contrastRatio(composite(color(ink, scope), bg), bg);
  };

  for (const theme of ['dark', 'light'] as const) {
    for (const tier of TIERS) {
      // One case per (theme × tier) on purpose: a single loop stops at the first failure, and the
      // review found FOUR distinct measurements — three failing — that each need to be seen.
      it(`clears AA on the ${theme} window for the ${tier.what} tier, resting AND selected`, () => {
        const scope = themeScope(theme, bridgeFor(MAIN, '\\.sv3-citations'));
        const resting = ratioOn(tier.ink, [], scope);
        const selected = ratioOn(tier.ink, SELECTED_MARK, scope);
        expect(
          resting,
          `${theme} — ${tier.what} at rest (${resting.toFixed(2)}:1)`,
        ).toBeGreaterThanOrEqual(WCAG_AA);
        expect(
          selected,
          `${theme} — ${tier.what} SELECTED, over the composited wash (${selected.toFixed(2)}:1)`,
        ).toBeGreaterThanOrEqual(WCAG_AA);
      });
    }

    it(`keeps a weak mark reading as WEAK on the ${theme} window`, () => {
      // The lift is a floor repair, not a promotion: clearing AA by repainting the subdued tiers at
      // the normal mark's strength would close the contrast defect by re-opening F2 in slower motion.
      const scope = themeScope(theme, bridgeFor(MAIN, '\\.sv3-citations'));
      const normal = ratioOn(NORMAL, SELECTED_MARK, scope);
      for (const tier of TIERS.slice(0, 1)) {
        const selected = ratioOn(tier.ink, SELECTED_MARK, scope);
        expect(
          selected,
          `${theme} — ${tier.what} (${selected.toFixed(2)}:1) must stay below a normal mark (${normal.toFixed(2)}:1)`,
        ).toBeLessThan(normal);
      }
    });
  }

  it('carries the lift on the TIER, never on the wash (the design\'s named remedy, §7.5)', () => {
    // "The weak tier's colour moves, not the wash." The three rungs are measured material the panel
    // and the region share; raising them to rescue a 12px glyph would repaint every selected surface
    // in the window. So the rungs are pinned here, beside the ratios they are allowed to constrain.
    expect(TOKENS.get('--sv3-selected')).toBe('color-mix(in srgb, var(--foreground) 9%, transparent)');
    expect(LIGHT.get('--sv3-selected')).toBe('color-mix(in srgb, var(--foreground) 9%, transparent)');
    // …and the tier inks are keyed to --foreground, the wash's own anchor, so the lift tracks a
    // theme change instead of being a second hand-picked grey per palette.
    for (const set of [TOKENS, LIGHT]) {
      expect(set.get('--sv3-cite-weak')).toMatch(/^color-mix\(in srgb, var\(--muted-foreground\) \d+%, var\(--foreground\)\)$/);
    }
  });
});

/* ── 3. The bridges carry the window's ramps, not the shipped app's ───────────────────────────── */

describe('the bridges also carry the window type and motion budget', () => {
  it('re-points the type ramp on all three, so an import cannot render at 13/11 px', () => {
    for (const tag of ['jf-tool-call-card', 'jf-reasoning-block', 'jf-citation-hover-card']) {
      const scope = bridgeFor(MAIN, tag);
      expect(scope.get('--font-size-sm'), tag).toBe('var(--font-size-sv3-sm)');
      expect(scope.get('--font-size-xs'), tag).toBe('var(--font-size-sv3-xs)');
    }
  });

  it('re-points every duration and easing a bridged component reads onto the window budget', () => {
    for (const tag of ['jf-tool-call-card', 'jf-reasoning-block', 'jf-citation-hover-card']) {
      const scope = bridgeFor(MAIN, tag);
      for (const [name, value] of scope) {
        if (name.startsWith('--duration')) expect(value, `${tag} ${name}`).toMatch(/--duration-sv3-/);
        if (name.startsWith('--ease')) expect(value, `${tag} ${name}`).toMatch(/--ease-sv3-/);
      }
    }
  });

  it('spends no fourth colour role: the risk edges are the window destructive/warning', () => {
    const scope = bridgeFor(MAIN, 'jf-tool-call-card');
    expect(scope.get('--accent-danger-45')).toContain('var(--destructive)');
    expect(scope.get('--accent-warning-45')).toContain('var(--warning)');
  });
});

/* ── 3b. The window's OWN tail row (tempdoc 822 Phase F11) ────────────────────────────────────── */

describe('the answer tail clears the same floor as the components beside it', () => {
  // The bridge clause applies to the row's own colours too: the tail is 12px secondary-label text
  // carrying the honesty facts, and its one hovered control paints foreground on the 4 %-white wash.
  // No component scope here — these are the window's own tokens, resolved straight off `:host`.
  const NO_SCOPE = new Map<string, string>();

  const pairs = [
    { what: 'the facts, the note and the disclosure at rest', text: 'var(--secondary-label)', on: [] },
    { what: 'the disclosure under the pointer', text: 'var(--foreground)', on: [] },
    { what: 'the copy control under the pointer', text: 'var(--foreground)', on: ['var(--accent-surface)'] },
    { what: 'a broken turn note', text: 'var(--error-foreground)', on: [] },
  ] as const;

  it('keeps every text/surface pair the row can paint at or above WCAG AA', () => {
    for (const pair of pairs) {
      const bg = surface(pair.on, NO_SCOPE);
      const fg = composite(color(pair.text, NO_SCOPE), bg);
      const ratio = contrastRatio(fg, bg);
      expect(ratio, `tail — ${pair.what} (${ratio.toFixed(2)}:1)`).toBeGreaterThanOrEqual(WCAG_AA);
      expect(
        relativeLuminance(bg),
        `tail — ${pair.what}: the surface is LIGHTER than its text`,
      ).toBeLessThan(relativeLuminance(fg));
    }
  });
});

/* ── 4. The name collision that made a link invisible (audit DEFECT-6) ────────────────────────── */

describe('the window does not re-use a shipped token NAME for a different meaning', () => {
  it('names its hover material outside the shipped vocabulary', () => {
    // The shipped `:root` defines `--accent` as a colour (`styles/tokens.css:124`) and
    // `ToolCallCard.ts:245` paints a link with it. While sv3 defined `--accent` as a 4 %-white
    // fill, that link rendered at 4 % opacity inside the window.
    expect(sv3Tokens.cssText).not.toMatch(/^\s*--accent:/m);
    expect(TOKENS.get('--accent-surface')).toBe('color-mix(in srgb, var(--color-white) 4%, transparent)');
    // …and where a shipped component genuinely wants a colour there, the bridge gives it one.
    expect(bridgeFor(MAIN, 'jf-tool-call-card').get('--accent')).toBe('var(--info-foreground)');
  });
});

it('HO5 source preview text clears AA in both themes and every card state', () => {
  // Explain has no document quotation: its visible preview is the no-excerpt text. Read the
  // actual component ink and its four existing card surfaces, including the selected state.
  const preview = componentSource('CitationsPanel.ts').match(/^\s*\.source \.preview\s*\{([^}]+)\}/m)?.[1] ?? '';
  const ink = preview.match(/\bcolor:\s*([^;]+);/)?.[1];
  expect(ink, 'the source preview must declare readable text').toBeDefined();
  const cards = [
    'var(--surface-2)', 'var(--surface-3)',
    'var(--cp-selected-region, var(--surface-2))', 'var(--cp-selected, var(--surface-3))',
  ];
  for (const theme of ['light', 'dark'] as const) {
    const scope = themeScope(theme, bridgeFor(MAIN, '\\.sv3-citations'));
    for (const card of cards) {
      const bg = surface([card], scope);
      const ratio = contrastRatio(composite(color(ink!, scope), bg), bg);
      expect(ratio, `${theme} source preview on ${card}: ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(WCAG_AA);
    }
  }
});

for (const theme of ['light', 'dark'] as const) {
  it(`D-T46 Sources hover breadcrumb and preview detail clear AA with margin in ${theme}`, () => {
    const source = componentSource('CitationsPanel.ts');
    const scope = themeScope(theme, bridgeFor(MAIN, '\\.sv3-citations'));
    // The hovered card, and the card that is both selected and hovered (`.source[data-selected]:hover`):
    // the second was the measured light-theme failure (4.27:1), so it is pinned explicitly.
    for (const card of ['var(--surface-3)', 'var(--cp-selected, var(--surface-3))']) {
      const bg = surface([card], scope);
      const primary = contrastRatio(composite(color('var(--text-primary)', scope), bg), bg);
      for (const selector of ['.heading-breadcrumb', '.preview .detail']) {
        const block = source.slice(source.indexOf(selector + ' {')).split('}')[0]!;
        const ink = block.match(/\bcolor:\s*([^;]+);/)?.[1];
        expect(ink, selector).toBeDefined();
        const ratio = contrastRatio(composite(color(ink!, scope), bg), bg);
        expect(ratio, `${theme} ${selector} on ${card}: ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.7);
        expect(ratio, `${selector} remains below primary ink`).toBeLessThan(primary);
      }
    }
  });
}
