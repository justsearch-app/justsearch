// @vitest-environment happy-dom

/**
 * Token enforcement (tempdoc 822 slice 1).
 *
 * The design spec's geometry tokens exist "so sidebar, palette, tooltip and toolbar controls cannot
 * quietly drift apart" — and the spec makes that real with unit tests asserting each component's
 * USE of the token, not just the token's existence. These are those tests: a component that
 * re-hardcodes 52px, 8px or a radius fails here even though it still renders correctly.
 */
import { describe, it, expect } from 'vitest';
import { sv3Tokens } from './sv3-tokens.css.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { SearchV3View } from './SearchV3View.js';
import { Sv3Topbar } from './Sv3Topbar.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';
import { Sv3ContextBar } from './Sv3ContextBar.js';
import { Sv3Palette } from './Sv3Palette.js';
import { Sv3Empty } from './Sv3Empty.js';
// Tempdoc 859 — the contrast assertions below compute ratios with the product's own authority
// (the same `contrastRatio` conformanceGate and the palette tests use), never by eye.
import { contrastRatio, type Rgb } from '../../themes/contrast.js';
import { ReasoningBlock } from '../../components/chat/ReasoningBlock.js';
import {
  SV3_HEADLINE_EXIT_MS,
  SV3_MORPH_DURATION_MS,
  SV3_MORPH_EASING,
  SV3_MORPH_ROOT_ATTR,
  SV3_MORPH_SHEET_TEXT,
} from './sv3-composer-morph.js';

const tokens = sv3Tokens.cssText;
const shared = sv3Shared.cssText;
/**
 * A component's OWN stylesheet — the last entry in its `static styles`, after the shared sheets it
 * adopts. Asserting against the whole array would let a token declared in `sv3Tokens` satisfy a
 * "this region reads the token" claim.
 */
const styleTextOf = (ctor: { styles?: unknown }): string => {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
};

describe('the token sheet is host-scoped, never global', () => {
  it('declares the palette on the window host and nowhere else', () => {
    expect(tokens).toContain(':host {');
    expect(tokens).not.toContain(':root');
    expect(tokens).not.toContain('html');
  });

  it('carries both color sets, dark as the default and light behind the theme attribute', () => {
    expect(tokens).toContain('color-scheme: dark');
    expect(tokens).toContain(":host([theme='light'])");
    expect(tokens).toContain('color-scheme: light');
    // The dark base is the near-black the surfaces lift FROM.
    expect(tokens).toContain('--background: var(--color-neutral-950)');
  });

  it('keeps the reference brand hue out and JustSearch accent in, with the structure intact', () => {
    expect(tokens).toContain('--primary: var(--color-teal-accent)');
    expect(tokens).toContain('--color-teal-accent: oklch(75% 0.15 180)');
    expect(tokens).toContain('--ring: var(--primary)');
    expect(tokens).toContain('--update: var(--primary)');
    // The reference brand blue, in either mode.
    expect(tokens).not.toContain('0.217 264');
    expect(tokens).not.toContain('0.21 264');
  });
});

describe('the geometry tokens the window is built from', () => {
  it('declares the spec geometry set verbatim', () => {
    for (const decl of [
      '--control-radius: 0.5rem',
      '--sidebar-content-inset: 0.5rem',
      '--sidebar-control-gap: 0.5rem',
      '--sidebar-row-content-inset: 0.625rem',
      '--command-shell-inset: 0.5rem',
      '--command-content-inset: 1rem',
      '--floating-content-inset: 0.75rem',
      '--workspace-topbar-height: 52px',
      '--sidebar-width: 16rem',
      // The collapsed icon rail (tempdoc 822 Phase F5).
      '--sidebar-width-icon: 3rem',
      '--glass-blur: 16px',
      '--glass-opacity: 80%',
      '--glass-saturation: 1.08',
      '--app-scrollbar-width: 6px',
    ]) {
      expect(tokens).toContain(decl);
    }
  });

  it('derives the radius ladder additively off one knob, with controls on a second', () => {
    expect(tokens).toContain('--radius: 0.625rem');
    expect(tokens).toContain('--radius-sm: calc(var(--radius) - 4px)');
    expect(tokens).toContain('--radius-md: calc(var(--radius) - 2px)');
    expect(tokens).toContain('--radius-lg: var(--radius)');
    expect(tokens).toContain('--radius-xl: calc(var(--radius) + 4px)');
    expect(tokens).toContain('--radius-2xl: calc(var(--radius) + 8px)');
    expect(tokens).toContain('--radius-3xl: calc(var(--radius) + 12px)');
    expect(tokens).toContain('--radius-4xl: calc(var(--radius) + 16px)');
    // The second knob is independent of the ladder — a control is not a surface.
    expect(tokens).not.toContain('--control-radius: var(--radius');
  });

  it('declares the three improvements: z-scale, spacing ladder, pad compensation', () => {
    for (const name of [
      '--z-content',
      '--z-sticky',
      '--z-overlay',
      '--z-dialog',
      '--z-tooltip',
      '--z-toast',
    ]) {
      expect(tokens).toContain(`${name}:`);
    }
    // Tooltips sit above dialogs.
    expect(tokens).toContain('--z-dialog: 50');
    expect(tokens).toContain('--z-tooltip: 70');

    for (let step = 1; step <= 12; step += 1) {
      expect(tokens).toContain(`--space-${step}: ${step * 4}px`);
    }

    expect(tokens).toContain('--control-pad-3: calc(0.75rem - 1px)');
  });

  it('keeps the platform type stack and no shipped face', () => {
    expect(tokens).toContain(
      "--font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif",
    );
    expect(tokens).toContain('--font-mono: ui-monospace');
    expect(tokens).not.toContain('@font-face');
  });

  it('leaves the Electron titlebar env() values behind, keeping only the indirection', () => {
    expect(tokens).toContain('--workspace-controls-left: 0.75rem');
    expect(tokens).toContain('--workspace-controls-right: 0.75rem');
    expect(tokens).not.toContain('titlebar-area');
  });
});

describe('every region reads the tokens rather than re-hardcoding them', () => {
  it('the topbar takes its height from the workspace token, both floor and ceiling', () => {
    const styles = styleTextOf(Sv3Topbar);
    expect(styles).toContain('height: var(--workspace-topbar-height)');
    expect(styles).toContain('min-height: var(--workspace-topbar-height)');
    expect(styles).not.toContain('52px');
    // The icon controls read the control knob, not the surface ladder.
    expect(styles).toContain('border-radius: var(--control-radius)');
    expect(styles).toContain('background: var(--toolbar-control-hover)');
  });

  it('the sidebar row reads the control radius and the second-level inset', () => {
    const styles = styleTextOf(Sv3SessionRow);
    expect(styles).toContain('border-radius: var(--control-radius)');
    expect(styles).toContain('padding-inline: var(--sidebar-row-content-inset)');
    expect(styles).toContain('background: var(--sidebar-row-hover)');
    expect(styles).toContain('gap: var(--sidebar-control-gap)');
    // ...and the panel's own inset is the first level, so the fill reads as a pill.
    expect(styleTextOf(Sv3Sidebar)).toContain('padding: var(--sidebar-content-inset)');
  });

  it('the group label row is the spec ladder read through tokens', () => {
    const styles = styleTextOf(Sv3Sidebar);
    const rule = styles.slice(styles.indexOf('.group-label {'), styles.indexOf('.groups {'));
    expect(rule).toContain('height: var(--space-8)');
    expect(rule).toContain('padding-inline: var(--space-2)');
    expect(rule).toContain('font-size: var(--font-size-sv3-xs)');
    expect(rule).toContain('font-weight: 500');
    expect(rule).toContain('color: var(--sidebar-muted-foreground)');
  });

  it('the window sizes the sidebar from the token and does not let it flex', () => {
    const styles = styleTextOf(SearchV3View);
    expect(styles).toContain('flex: 0 0 var(--sidebar-width)');
    expect(styles).toContain('width: var(--sidebar-width)');
    expect(styles).not.toContain('16rem');
  });

  it('the collapsed rail and the grip that sits on it read the SAME boundary token', () => {
    // The one drift the geometry-token law exists to prevent: a panel that ends at 48px with a grip
    // drawn somewhere else. Both the collapsed width and the grip's collapsed position are the token.
    const styles = styleTextOf(SearchV3View);
    expect(styles).toContain('flex-basis: var(--sidebar-width-icon)');
    expect(styles).toContain('left: var(--sidebar-width-icon)');
    expect(styles).toContain('left: var(--sidebar-width)');
    expect(styles).not.toContain('3rem');
    expect(styles).not.toContain('48px');
  });

  it('the composer reads the floating inset, the surface ladder and the pad compensation', () => {
    const styles = styleTextOf(Sv3Composer);
    expect(styles).toContain('padding: var(--floating-content-inset)');
    // The glass silhouette is the top of the radius ladder, not a re-typed 22px.
    expect(styles).toContain('border-radius: var(--radius-3xl)');
    expect(styles).not.toContain('22px');
    // The 1px border still comes out of the control inset (per the design spec) — off the
    // ladder step the SPEC's composer control uses (px-2.5), since Phase F10 moved the row from
    // the placeholder chips' menu-button referent to `ComposerControl`'s own.
    expect(styles).toContain('padding-inline: calc(var(--space-2-5) - 1px)');
    // The hero composer hangs off the topbar's own height rather than a second copy of 52px.
    expect(styles).toContain('inset: var(--workspace-topbar-height) 0 0 0');
    expect(styles).not.toContain('52px');
  });

  it('the main surface reads the semantic colors, not literals', () => {
    const styles = styleTextOf(Sv3Main);
    expect(styles).toContain('background: var(--background)');
    expect(styles).toContain('color: var(--foreground)');
  });
});

/**
 * A geometry token names a TOTAL, not a floor. Live measurement caught the sidebar at 273px
 * (256 + the 8px inset on both sides + a 1px border, all added outside the token) and the topbar at
 * 53px (52 + its rule) — the default content-box quietly turning both tokens into "at least".
 *
 * happy-dom runs no layout engine, so these pin the box MATH that produces the rendered total: the
 * border-box rule that makes padding and border count inward, plus the absence of anything (margin,
 * outer padding, gap) that would add width outside the sized box. The rendered pixels themselves
 * are measured live.
 */
describe('the sized regions render at exactly their token, not the token plus trim', () => {
  const hostRuleOf = (styles: string): string =>
    styles.slice(styles.indexOf(':host {'), styles.indexOf('}', styles.indexOf(':host {')));

  it('the shared sheet makes padding and border count inward, for hosts and content alike', () => {
    const rule = shared.slice(shared.indexOf(':host,'), shared.indexOf('@keyframes'));
    expect(rule).toContain('*');
    expect(rule).toContain('box-sizing: border-box');
  });

  it('the sidebar keeps its inset and its border inside the 256px region', () => {
    const host = hostRuleOf(styleTextOf(Sv3Sidebar));
    expect(host).toContain('padding: var(--sidebar-content-inset)');
    expect(host).toContain('border-right: 1px solid var(--sidebar-border)');
    // Anything outside the box would widen the region past the token.
    expect(host).not.toContain('margin');
    expect(host).not.toContain('min-width');
  });

  it('the window sizes the sidebar by the token alone and adds no gap beside it', () => {
    const styles = styleTextOf(SearchV3View);
    expect(styles).toContain('flex: 0 0 var(--sidebar-width)');
    expect(styles).not.toContain('gap:');
    const host = hostRuleOf(styles);
    expect(host).toContain('display: flex');
    expect(host).not.toContain('padding');
  });

  // The spec's SESSION row is the slim 36px (h-9); the 32px this was built at came from the
  // menu-button ladder — the wrong referent for a list of sessions (822 sidebar-comparison finding 1).
  it('the session row is 36px total, with both insets counting inward', () => {
    const styles = styleTextOf(Sv3SessionRow);
    const start = styles.indexOf('button.row {');
    const rule = styles.slice(start, styles.indexOf('}', start));
    expect(rule).toContain('height: var(--space-9)');
    expect(rule).not.toContain('height: var(--space-8)');
    expect(rule).toContain('padding-inline: var(--sidebar-row-content-inset)');
    // Anything outside the box would push the row past the 36px the ladder claims.
    expect(rule).not.toContain('margin');
    expect(rule).not.toContain('min-height');
    expect(rule).not.toContain('border-width');
    expect(rule).toContain('border: 0');
    // The intrinsic size a skipped row reports must equal the size it actually renders at.
    expect(rule).toContain('contain-intrinsic-size: auto var(--space-9)');
    // The row host adds nothing around the button.
    const host = hostRuleOf(styles);
    expect(host).toContain('display: block');
    expect(host).not.toContain('padding');
    expect(host).not.toContain('margin');
  });

  it('the topbar keeps its rule inside the 52px band', () => {
    const host = hostRuleOf(styleTextOf(Sv3Topbar));
    expect(host).toContain('height: var(--workspace-topbar-height)');
    expect(host).toContain('border-bottom: 1px solid var(--toolbar-border)');
    expect(host).not.toContain('margin');
    // A max-height below the declared height would shrink the band instead.
    expect(host).not.toContain('max-height');
  });
});

/**
 * The row model, as mechanism rather than appearance. happy-dom runs no cascade over adopted
 * sheets, so these pin the SELECTORS that decide the outcome: a fill that wins because it was
 * declared later is one edit away from stacking two fills, while a fill guarded by `:not()` cannot.
 */
describe('the session row spends one fill and three colours, by construction', () => {
  const styles = styleTextOf(Sv3SessionRow);
  /** The declaration block of the first rule whose selector starts with `sel`. */
  const ruleFor = (sel: string): string => {
    const at = styles.indexOf(sel);
    expect(at, `no rule for ${sel}`).toBeGreaterThan(-1);
    return styles.slice(at, styles.indexOf('}', at));
  };

  it('ranks active over selected over hover, each guarded out of the one above it', () => {
    expect(ruleFor(':host([active]) button.row')).toContain(
      'background: var(--sidebar-row-active)',
    );
    // Selected only applies where active does not — precedence lives in the selector.
    expect(ruleFor(':host([selected]:not([active])) button.row')).toContain(
      'background: var(--sidebar-row-selected)',
    );
    // ...and hover only where NEITHER claimed the row, so hover+selected cannot show two fills.
    expect(ruleFor(':host(:not([active]):not([selected])) button.row:hover')).toContain(
      'background: var(--sidebar-row-hover)',
    );

    // No hover rule anywhere in the row is UNguarded: an unguarded one would paint over the
    // active fill the moment the pointer crossed the claimed row.
    const hoverSelectors = [...styles.matchAll(/[^\n]*button\.row:hover[^\n]*/g)].map((m) => m[0]);
    expect(hoverSelectors.length).toBeGreaterThanOrEqual(2);
    for (const selector of hoverSelectors) {
      expect(selector, `unguarded hover rule: ${selector.trim()}`).toContain(':not(');
    }
  });

  it('keeps the in-flight dim orthogonal to the fill ladder and off the claimed row', () => {
    expect(ruleFor(':host([inflight]:not([active]):not([selected])) button.row {')).toContain(
      'opacity: 0.7',
    );
    expect(ruleFor(':host([inflight]:not([active]):not([selected])) button.row:hover')).toContain(
      'opacity: 1',
    );
  });

  it('spends colour on exactly the three status states, through semantic tokens', () => {
    expect(ruleFor(":host([status='act-now']) .dot")).toContain('background: var(--success)');
    expect(ruleFor(":host([status='in-motion']) .dot")).toContain('background: var(--warning)');
    expect(ruleFor(":host([status='broken']) .dot")).toContain('background: var(--destructive)');
    // A fourth status colour, or a literal instead of a token, is what this forbids.
    expect(styles).not.toContain("[status='resting']");
    expect(styles).not.toMatch(/#[0-9a-fA-F]{3}/);
    for (const literal of ['rgb(', 'hsl(', 'oklch(']) {
      expect(styles).not.toContain(literal);
    }
  });

  it('carries emphasis as foreground alpha, never as a second hue', () => {
    expect(ruleFor('.row-label {')).toContain(
      'color: color-mix(in srgb, var(--foreground) 90%, transparent)',
    );
    expect(ruleFor(':host([unread]) .row-label')).toContain('color: var(--foreground)');
    expect(ruleFor(":host([status='broken']) .row-label")).toContain(
      'color: color-mix(in srgb, var(--foreground) 95%, transparent)',
    );
    expect(ruleFor(':host([receded]:not([active]):not([selected])) .row-label')).toContain(
      'color: color-mix(in srgb, var(--sidebar-muted-foreground) 75%, transparent)',
    );
    // Truncation is the default for a row title (density §7).
    expect(ruleFor('.row-label {')).toContain('text-overflow: ellipsis');
    expect(ruleFor('.row-label {')).toContain('white-space: nowrap');
  });

  it('sizes the status dot and its ping off the spacing ladder', () => {
    expect(ruleFor('.dot-box {')).toContain('inline-size: var(--space-3)');
    expect(ruleFor('.dot {')).toContain('inline-size: var(--space-2)');
    expect(ruleFor(":host([status='in-motion']) .ping")).toContain(
      'background: color-mix(in srgb, var(--warning) 60%, transparent)',
    );
    // The slot reserves a floor so a row does not jitter when its contents change width.
    expect(ruleFor('.status-slot {')).toContain('min-inline-size: var(--space-8)');
  });

  it('870 item 6b: the slot WIDENS on the same clock the status fades on', () => {
    // The two halves of one swap. The widening (32px floor → the action set's width, three rules
    // below) used to land in a single frame while its sibling `.slot-content` faded over 150ms, so
    // the title's clip jumped before the status it was making room for had finished leaving. Both
    // ends of the width are real lengths, which is what makes `min-inline-size` interpolable at all
    // — a rule that dropped the resting floor would silently stop animating rather than fail.
    const slot = ruleFor('.status-slot {');
    expect(slot).toContain('min-inline-size: var(--space-8)');
    expect(slot).toContain(
      'transition: min-inline-size var(--duration-sv3-micro) var(--ease-sv3-enter)',
    );
    // Same duration and curve as the fade it is paired with — one swap, one clock.
    expect(ruleFor('.slot-content {')).toContain(
      'transition: opacity var(--duration-sv3-micro) var(--ease-sv3-enter)',
    );
    // …and it joins the reduced-motion opt-out with its sibling, rather than being the one animated
    // property that ignores the setting.
    const reduced = styles.slice(styles.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduced).toContain('.status-slot');
    expect(reduced).toContain('transition: none');
  });

  /**
   * The status→action slot swap (Phase F3) as MECHANISM: happy-dom runs no cascade and
   * no hover, so the properties pinned here are the ones that decide the outcome — the hidden state
   * leaving the flow, the width floor that makes the swap jitter-free, and the guards that carry the
   * spec's never-yields exception.
   */
  it('swaps the status for the action on hover AND on keyboard focus, out of flow', () => {
    // Selectors are read per RULE with their whitespace normalised, not per line: a selector long
    // enough to wrap (the keyboard halves both are) would otherwise fall out of a line-based scrape
    // and take its never-yields guard with it — silently, which is the failure this case is for.
    const yielders = styles
      .split('}')
      .map((block) => block.slice(0, block.indexOf('{')).replace(/\s+/g, ' ').trim())
      .filter((selector) => selector.includes('.slot-content') && selector.includes(':host('));
    expect(yielders.length).toBeGreaterThanOrEqual(3);
    // Two ways in: the pointer and the keyboard. A hover-only swap is unreachable by keyboard.
    expect(yielders.some((s) => s.includes(':hover'))).toBe(true);
    expect(yielders.some((s) => s.includes(':focus-visible'))).toBe(true);
    for (const selector of yielders) {
      // THE NEVER-YIELDS EXCEPTION: an act-now or broken status is the spec's PR badge — it stays
      // visible while the row is hovered. An unguarded yield rule would hide exactly the two facts
      // the reader most needs, and only while they are pointing at the row.
      expect(selector, `unguarded yield rule: ${selector.trim()}`).toContain(
        ":not([status='act-now']):not([status='broken'])",
      );
    }
    // The hidden state leaves the FLOW, which is what lets the title reclaim the width.
    const yielded = ruleFor(":host(:hover:not([compact]):not([status='act-now'])");
    expect(yielded).toContain('position: absolute');
    expect(yielded).toContain('opacity: 0');
  });

  it('nests no :has() inside :host() — Chrome rejects it and drops the whole selector list', () => {
    // Live-measured defect (822 F3): `:host(:has(:focus-visible)) …` is a SyntaxError in Chrome, and
    // an invalid member invalidates its entire selector list — so the sibling `:host(:hover)` rule
    // died with it and the swap did nothing in the browser while this file's CSS-text cases stayed
    // green. The shape is banned here because the text assertions above cannot see it.
    expect(styleTextOf(Sv3SessionRow)).not.toContain(':host(:has(');
    expect(sv3Shared.cssText).not.toContain(':host(:has(');
  });

  it('reserves the ACTION gutter at REST for the statuses that never yield', () => {
    // Reserved at rest, not on hover: a gutter that appeared under the pointer would move the dot,
    // which is the jitter the slot floor exists to prevent. The figure is the action set's own
    // width (tempdoc 831) — reserved through the token rather than as a copy of it, so growing or
    // shrinking the set cannot leave the never-yields gutter measuring the old one.
    const reserved = ruleFor(":host([status='act-now']) .status-slot");
    expect(reserved).toContain('padding-inline-end: var(--sv3-row-actions-inline)');
    expect(styles).toContain(":host([status='broken']) .status-slot");
    // And the token is the SET's width, off the spacing ladder: three squares, or two on a
    // conversation with work in flight, which offers no discard.
    expect(ruleFor(':host {')).toContain('--sv3-row-actions-inline: calc(3 * var(--space-6))');
    expect(ruleFor(':host([live])')).toContain(
      '--sv3-row-actions-inline: calc(2 * var(--space-6))',
    );
  });

  it('keeps every row action out of the pointer\'s way at rest, but never out of the tab order', () => {
    const act = ruleFor('button.act {');
    expect(act).toContain('opacity: 0');
    // `pointer-events: none` and not `display: none` / `visibility: hidden`: those two would take
    // the control out of the tab order, and the swap has to be reachable by keyboard.
    expect(act).toContain('pointer-events: none');
    expect(act).not.toContain('display: none');
    expect(act).not.toContain('visibility: hidden');
    // The rest state is worn by the SHARED class, so a fourth action cannot be added visible-at-rest
    // by forgetting a rule — every action in the set is `button.act`.
    expect(styleTextOf(Sv3SessionRow)).not.toMatch(/\n\s*button\.(pin|rename|remove)\s*\{/);
    expect(ruleFor(':host(:hover) button.act')).toContain('opacity: 1');
    // Keyboard reveal, in two halves — the row focused, and an action itself focused.
    expect(styles).toContain('button.row:focus-visible ~ .actions button.act');
    expect(styles).toContain('button.act:focus-visible');
    // The pin's pressed state is foreground weight, not a fourth colour.
    expect(ruleFor(':host([pinned]) button.pin')).toContain('color: var(--sidebar-foreground)');
  });

  it('reserves the action width on the YIELDING rows too, so no icon paints over a title', () => {
    // Tempdoc 831 D1, found by the independent measured audit: the never-yields gutter covered only
    // act-now/broken, so every other row reserved the slot's 32px floor while the revealed set is
    // 72px wide — 32px of title text under the icons at the default sidebar width, at 2.91:1
    // against them. The fix reserves the SET'S OWN width for exactly as long as the set is shown.
    const reserving = styles
      .split('}')
      .map((block) => ({
        selector: block.slice(0, block.indexOf('{')).replace(/\s+/g, ' ').trim(),
        body: block.slice(block.indexOf('{')),
      }))
      .filter(
        (rule) =>
          rule.selector.includes('.status-slot') &&
          rule.body.includes('min-inline-size: var(--sv3-row-actions-inline)'),
      )
      .map((rule) => rule.selector);
    // The same three triggers the yield has: a swap reachable by pointer but not by keyboard would
    // leave the keyboard reader with the overlap this fixes.
    expect(reserving).toHaveLength(3);
    expect(reserving.some((s) => s.includes(':hover'))).toBe(true);
    expect(reserving.filter((s) => s.includes(':focus-visible'))).toHaveLength(2);
    for (const selector of reserving) {
      // ...and each one is guarded OUT of the never-yields rows, which reserve their gutter at rest
      // instead — widening theirs on hover would move the dot the guard exists to hold still.
      expect(selector, `unguarded reservation: ${selector}`).toContain(
        ":not([status='act-now']):not([status='broken'])",
      );
      expect(selector).toContain(':not([compact])');
    }
    // The reservation is the TOKEN, never a copied figure, so it cannot describe a different set
    // than the one that is rendered.
    expect(styles).not.toMatch(/min-inline-size:\s*(72px|calc\(3)/);
  });

  it('never lets the action GROUP take a hit the row should have had', () => {
    // The set is an absolutely-positioned box over the row's trailing strip. Left targetable, it
    // would swallow the claim click there even at rest, when it shows nothing to press — the row's
    // right-hand edge would simply stop working. Live-measured with `elementFromPoint` (831); pinned
    // here because CI has no browser.
    expect(ruleFor('.actions {')).toContain('pointer-events: none');
  });
});

/**
 * Keyframes resolve per shadow root: an `animation` naming a keyframe that is not in THIS
 * component's own adopted sheets fails silently — the element simply never moves. So every
 * animation the row can run is checked against the sheets the row actually adopts.
 */
describe('every animation the row can run resolves inside its own adopted sheets', () => {
  const adopted = (Sv3SessionRow.styles as ReadonlyArray<{ cssText: string }>)
    .map((s) => s.cssText)
    .join('\n');

  it('names only keyframes declared in the sheets the component adopts', () => {
    const classes = [...adopted.matchAll(/sv3-anim-([a-z-]+)/g)].map((m) => m[1]);
    // The row runs the in-motion ping; if it ever runs more, each one is checked here too.
    expect(classes).toContain('status-ping');
    const shorthand = [...adopted.matchAll(/animation:\s*([a-z-]+)\s/g)].map((m) => m[1]);
    expect(shorthand.length).toBeGreaterThan(0);
    for (const name of shorthand) {
      if (name === 'none') continue;
      expect(adopted).toContain(`@keyframes ${name}`);
    }
  });
});

/**
 * The composer material (slice 3), as mechanism. The composer's whole material is token-fed for
 * one structural reason: the spec writes its dark mode as rules keyed on a class on the document
 * element, and a selector inside a shadow root cannot see that class. Any colour literal appearing
 * in the component is therefore a mode the window cannot invert.
 */
describe('the composer glass is token-fed material, so dark inverts without a component rule', () => {
  const composer = styleTextOf(Sv3Composer);
  const ruleFor = (sel: string): string => {
    const at = composer.indexOf(sel);
    expect(at, `no rule for ${sel}`).toBeGreaterThan(-1);
    return composer.slice(at, composer.indexOf('}', at));
  };

  it('puts the whole recipe on ONE node: the radius, the fill, the blur and the elevation', () => {
    // Live measurement found the split form unreachable: the element carrying the 22px radius
    // reported `backdrop-filter: none` and a transparent background, because the material sat on a
    // sibling layer's pseudo-element. Whatever renders the silhouette must also render the glass.
    const start = composer.indexOf('.glass {');
    const end = composer.indexOf('}', start);
    const rule = composer.slice(start, end);
    expect(rule).toContain('border-radius: var(--radius-3xl)');
    // Tempdoc 859 §B (D2/D14) — the fill and the blur are DERIVED from one multiplier now, and the
    // assertions move with them ON PURPOSE. This is a change to the thing the test guards, not a
    // test weakened to fit: `--glass-blur-scale` is the shipped app's single blur knob, reached by
    // `[data-surface-mode="solid"]` AND (as of this slice) `prefers-reduced-transparency: reduce`,
    // and search-v3 was deaf to both. Deriving the OPACITY from the same multiplier is what keeps
    // the two halves inseparable — zeroing the blur alone would ship the unreadable half-state the
    // `@supports` companion below was written to prevent. Pinning the derived forms is what stops a
    // later edit from silently un-wiring the seam; the token DECLARATIONS (--glass-blur: 16px,
    // --glass-opacity: 80%) are untouched and still pinned by the declaration list above.
    // Tempdoc 864 Layer 1(d) — the SURFACE term is now `--composer-rest-surface`, and this assertion
    // moves with it for the same reason 859 §B moved it: the seam being guarded (one multiplier
    // driving blur AND translucency) is untouched, and the resting knob is DERIVED from
    // `--composer-glass-surface` one line above rather than forking it. The derivation itself is
    // pinned in `sv3-focus.test.ts`, next to the affordance it exists for.
    expect(rule).toContain(
      'var(--composer-rest-surface)\n            calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale))',
    );
    expect(rule).toContain(
      '--composer-rest-surface: color-mix(\n          in srgb,\n          var(--composer-glass-surface) calc(100% - 65% * var(--composer-rest)),\n          var(--background)\n        )',
    );
    expect(rule).toContain('box-shadow: var(--composer-shadow)');
    expect(rule).toContain(
      'backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))',
    );
    expect(rule).toContain('saturate(var(--glass-saturation))');
    expect(rule).toContain('-webkit-backdrop-filter:');

    // ...and no blur declaration of the GLASS's own recipe lives anywhere else — a node split is
    // what put it out of reach. The 859 live-leg added a SECOND blurred surface, the banner box
    // shared by the availability notice and the degradation strip, which floats over the transcript
    // with the composer and so needs the same material. Each is admitted by name and by rule
    // bounds, so a blur that escaped every silhouette still fails here. The matcher pins the
    // MULTIPLIED form (859 §B), so a site that reverted to a bare `blur(var(--glass-blur))` would
    // drop out of the count and fail rather than pass unnoticed.
    //
    // Tempdoc 870 item 1 retired the THIRD surface's blur — Phase F10's control menu. `.glass`
    // declares `isolation: isolate`, so a backdrop-filter on a descendant of it can only ever
    // sample the composer's own paint; the menu is opaque now and its two declarations (webkit +
    // standard) came out of this count with it.
    const menuStart = composer.indexOf('.menu {');
    const menuEnd = composer.indexOf('}', menuStart);
    const bannerStart = composer.indexOf('.notice,\n      .degradation {');
    expect(bannerStart, 'the notice and the degradation strip must share ONE box rule').toBeGreaterThan(-1);
    const bannerEnd = composer.indexOf('}', bannerStart);
    const declarations = [
      ...composer.matchAll(
        /backdrop-filter: blur\(calc\(var\(--glass-blur\) \* var\(--glass-blur-scale\)\)\)/g,
      ),
    ];
    expect(declarations).toHaveLength(4);
    // Nothing anywhere in the component blurs WITHOUT the multiplier — the direct statement of
    // "search-v3 is no longer deaf to the user's own solid-surfaces setting".
    expect(composer).not.toMatch(/blur\(var\(--glass-blur\)\)/);
    for (const declaration of declarations) {
      const at = declaration.index ?? -1;
      const inGlass = at > start && at < end;
      const inBanner = at > bannerStart && at < bannerEnd;
      expect(inGlass || inBanner, 'a blur declaration escaped every silhouette node').toBe(true);
    }
    // …and the menu is not one of them any more, which is the half a count alone would not say.
    // Matched WITH the colon: the rule's own prose names the property it retired.
    expect(composer.slice(menuStart, menuEnd)).not.toContain('backdrop-filter:');
    // The composer's ambient glass is still the only surface that saturates.
    expect(composer.slice(menuStart, menuEnd)).not.toContain('saturate(');
  });

  it('goes opaque where blur is unsupported, which is the mandatory companion to any glass', () => {
    const at = composer.indexOf('@supports not ((-webkit-backdrop-filter: blur(1px))');
    expect(at).toBeGreaterThan(-1);
    expect(composer.slice(at, at + 300)).toContain('background: var(--composer-glass-surface)');
  });

  it('carries elevation and outline as tokens, and spends no colour literal of its own', () => {
    expect(ruleFor('.glass {')).toContain('box-shadow: var(--composer-shadow)');
    expect(ruleFor('.glass::after {')).toContain('border: 1px solid var(--composer-outline)');
    expect(ruleFor('.glass::after {')).toContain('box-shadow: var(--composer-highlight)');
    for (const literal of ['rgb(', 'hsl(', 'oklch(', '#']) {
      expect(composer).not.toContain(literal);
    }
  });

  it('inverts the elevation between the two modes in the token sheet, not in the component', () => {
    const split = tokens.indexOf(":host([theme='light'])");
    const dark = tokens.slice(0, split);
    const light = tokens.slice(split);
    // Dark catches light on its top edge and casts nothing.
    expect(dark).toContain('--composer-shadow: none');
    expect(dark).toContain('--composer-highlight: inset 0 1px rgb(255 255 255 / 3%)');
    expect(dark).toContain(
      '--composer-glass-surface: color-mix(in srgb, var(--background) 96%, var(--color-white))',
    );
    // Tempdoc 859 — 5% → 35%: the ENGAGED edge's WCAG 1.4.11 floor, pinned as a value here and as a
    // computed ratio in the contrast block below (this line would happily accept a value that still
    // failed). The RESTING edge remains open — see the "KNOWN OPEN" case in that block.
    expect(dark).toContain(
      '--composer-outline: color-mix(in srgb, var(--color-white) 35%, transparent)',
    );
    // Light casts a tight contact shadow down and catches nothing.
    expect(light).toContain('--composer-shadow: 0 12px 28px -18px rgb(0 0 0 / 40%)');
    expect(light).toContain('--composer-highlight: none');
    expect(light).toContain('--composer-glass-surface: var(--card)');
    expect(light).toContain('--composer-outline: rgb(0 0 0 / 45%)'); // 859 — 8% → 45%, same floor
  });

  it('reads focus and validity off the wrapper, so ONE mark shows at a time', () => {
    // Tempdoc 864 Layer 1(d) — these rules were re-keyed from `:host(:has(…)) .glass::after` onto
    // `.glass:has(…)::after`. 822 F3 measured `:host(:has(` as a Chrome syntax error that takes
    // its whole selector list down, and `:host()` matches its argument against the host in the OUTER
    // tree, where a shadow `<textarea>` does not live — so the ring these draw was unreachable. The
    // wrapper reads the field in its own tree.
    expect(ruleFor('.glass:has(textarea:focus-visible)::after')).toContain(
      'border-color: var(--ring)',
    );
    // Tempdoc 870 item 2 — and the mark is the FRAME, alone. The 3px `--ring` halo that used to sit
    // outside it is gone: with the ambient `:focus-visible` outline also landing on the <textarea>
    // (closed below), a keyboard focus painted THREE concentric marks. What survives still carries
    // the state in two channels — the frame's hue AND the resting→engaged surface lift — so this
    // pins "no outline anywhere on the wrapper's ::after rules", not "no indication".
    for (const selector of [
      '.glass:has(textarea:focus-visible)::after',
      ".glass:has(textarea[aria-invalid='true'])::after",
    ]) {
      expect(ruleFor(selector), `${selector} re-opened the halo`).not.toContain('outline');
    }
    // The OTHER half of the double ring: the app-global ambient rule (primitives/ambientStyles.ts)
    // is adopted into this shadow root at (0,1,0) and out-specifies the bare `textarea` reset, so
    // the reset has to be restated ON the pseudo-class — (0,1,1) — to take the field back. A sheet
    // that dropped this rule would silently paint the ambient ring again.
    expect(ruleFor('textarea:focus-visible {')).toContain('outline: none');
    expect(ruleFor(".glass:has(textarea[aria-invalid='true'])::after")).toContain(
      'border-color: color-mix(in srgb, var(--destructive) 36%, transparent)',
    );
    // Invalid must be declared AFTER focus, or a focused invalid field would show the focus hue.
    expect(composer.indexOf(".glass:has(textarea[aria-invalid='true'])")).toBeGreaterThan(
      composer.indexOf('.glass:has(textarea:focus-visible)'),
    );
    // The banned shape is gone from this sheet's RULES entirely, not merely unused (the prose above
    // the re-keyed rules names it, which is why the comments are stripped before the check).
    expect(composer.replace(/\/\*[\s\S]*?\*\//g, '')).not.toContain(':host(:has(');
  });

  it('gives the primary action the spec size, material and press physics', () => {
    const rule = ruleFor('button.send {');
    expect(rule).toContain('inline-size: var(--space-8)');
    expect(rule).toContain('block-size: var(--space-8)');
    expect(rule).toContain('background: var(--message-action)');
    expect(rule).toContain('color: var(--message-action-foreground)');
    expect(rule).toContain('var(--control-inset-highlight)');
    expect(ruleFor('button.send:hover:not(:disabled)')).toContain('transform: scale(1.05)');
    // Pressing flips the highlight dark AND drops the drop shadow — into the surface, not merely dim.
    expect(ruleFor('button.send:active:not(:disabled)')).toContain(
      'box-shadow: var(--control-inset-pressed)',
    );
    const off = ruleFor('button.send:disabled');
    expect(off).toContain('pointer-events: none');
    expect(off).toContain('opacity: 0.3');
    expect(off).toContain('box-shadow: none');
  });

  it('guards every send reaction out of the disabled state, in the selector', () => {
    // An unguarded hover rule would let a disabled control grow under the pointer — the button
    // would look live while refusing every click.
    const reactive = [...composer.matchAll(/[^\n]*button\.send:(?:hover|active)[^\n]*/g)].map(
      (m) => m[0],
    );
    expect(reactive.length).toBeGreaterThanOrEqual(3);
    for (const selector of reactive) {
      expect(selector, `unguarded reaction: ${selector.trim()}`).toContain(':not(:disabled)');
    }
  });

  /**
   * THE CHIP-REFERENT DECISION, pinned (tempdoc 822 Phase F10; the polish pass's open item (a)).
   * Slice 3's inert chips were 24px off the spec's menu-button ladder; a REAL composer
   * control takes the spec's own composer referent — the composer control's h-7
   * on the button `sm` desktop row — which is
   * 28px, with gap-1.5 and px-2.5.
   */
  it('sizes the composer control off the spec composer referent, not the menu ladder', () => {
    const control = ruleFor('button.composer-control {');
    expect(control).toContain('height: var(--space-7)');
    expect(control).toContain('min-height: var(--space-7)');
    expect(control).not.toContain('height: var(--space-6)');
    // Per the design spec — the inset is reduced by exactly the border it sits inside.
    expect(control).toContain('padding-inline: calc(var(--space-2-5) - 1px)');
    expect(control).toContain('gap: var(--space-1-5)');
    expect(control).toContain('font-size: var(--font-size-sv3-sm)');
    expect(control).toContain('border: 1px solid transparent');
    expect(control).toContain('color: var(--secondary-label)');
    expect(control).toContain('--control-icon-color: var(--icon-muted)');
    const hover = ruleFor('button.composer-control:hover');
    expect(hover).toContain('background: var(--accent-surface)');
    expect(hover).toContain('color: var(--foreground)');
    // A button eases its ELEVATION only; a hover fill is instant.
    expect(control).toContain('transition: box-shadow var(--duration-sv3-micro)');
  });

  /**
   * The control's menu is the window's THIRD glass recipe, and the spec gives it its own:
   * a denser tint than the composer's ambient glass, no saturate, and the
   * geometry of the menu popup / radio item / group label.
   */
  it('builds the control menu on the spec dropdown recipe, by token', () => {
    const menu = ruleFor('.menu {');
    // Tempdoc 870 item 1 — OPAQUE, and by the token the no-blur fallback already used. `.glass`
    // isolates, so a backdrop-filter here samples only the composer's own paint: the placeholder
    // glyphs under an open menu blurred and then composited through `--dropdown-surface`'s ~16 %
    // transparency. The geometry tokens below are the parts of the spec recipe that still apply.
    expect(menu).toContain('background: var(--popover)');
    expect(menu).not.toContain('background: var(--dropdown-surface)');
    // The blur went with it rather than being tuned down: it could never sample the page from
    // inside the isolated context, so it was dead weight that only cost a repaint.
    expect(menu).not.toContain('backdrop-filter:');
    expect(menu).toContain('border: 1px solid var(--dropdown-border)');
    expect(menu).toContain('box-shadow: var(--dropdown-shadow)');
    expect(menu).toContain('border-radius: var(--radius-lg)');
    expect(menu).toContain('padding: var(--space-1)');
    // The spec's positioner sideOffset = 4, opening upward from a bottom-docked bar.
    expect(menu).toContain('bottom: calc(100% + var(--space-1))');
    expect(menu).toContain('inset-inline-start: 0');
    // Intra-component stacking, but still off the window's z-scale: no raw rung is typed here.
    expect(menu).toContain('z-index: var(--z-sticky)');

    const item = ruleFor('button.menu-item {');
    expect(item).toContain('min-height: var(--space-7)');
    expect(item).toContain('padding: var(--space-1) var(--space-2)');
    expect(item).toContain('border-radius: var(--radius-sm)');
    expect(item).toContain('font-size: var(--font-size-sv3-sm)');
    expect(ruleFor("button.menu-item[aria-checked='true']")).toContain(
      'background: color-mix(in srgb, var(--foreground) 8%, transparent)',
    );
    const label = ruleFor('.menu-label {');
    expect(label).toContain('padding: var(--space-1-5) var(--space-2)');
    expect(label).toContain('color: var(--muted-foreground)');
    expect(label).toContain('font-size: var(--font-size-sv3-xs)');
  });

  /**
   * A glass surface without its no-blur fallback is unreadable, not subtle — the window's own rule,
   * now that a SECOND blurred surface lives in this file.
   */
  it('gives every blurred surface in the composer an opaque fallback', () => {
    const blurred = [...composer.matchAll(/([.\w-]+)\s*\{[^}]*backdrop-filter:\s*blur/g)].map(
      (m) => m[1],
    );
    expect(blurred).toContain('.glass');
    // Tempdoc 870 item 1 — the control menu is no longer on this list, and that is the point: it is
    // opaque in EVERY mode now, so it needs no fallback because it has no translucency to lose.
    // Asserted as an absence rather than deleted, so a re-added blur would have to answer the
    // fallback requirement here again.
    expect(blurred).not.toContain('.menu');
    const fallback = composer.slice(
      composer.indexOf('@supports not ((-webkit-backdrop-filter: blur(1px))'),
    );
    expect(fallback).toContain('.glass');
    // Every surface that IS blurred appears in a fallback block.
    for (const surface of blurred) {
      expect(fallback, `${surface} blurs with no opaque companion`).toContain(surface);
    }
  });

  it('evaporates the control labels leftward on docking, and only fades them under reduced motion', () => {
    // Width on the outer (collapses in one frame), motion on the inner — the two halves of §5.9.
    expect(ruleFor(":host([state='docked']) .control-label {")).toContain('max-inline-size: 0');
    const motion = ruleFor(":host([state='docked']) .control-label-motion {");
    expect(motion).toContain('transform: translateX(-0.25rem) scaleX(0.95)');
    expect(motion).toContain('opacity: 0');
    expect(ruleFor('.control-label-motion {')).toContain('transform-origin: left');
    const reduced = composer.slice(composer.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduced).toContain('transform: none');
    // The fade survives: reduce drops the transform half, not the affordance.
    expect(reduced).toContain('transition: opacity var(--duration-sv3-morph)');
  });

  /**
   * The spec's two composer forms differ in INTERNAL layout, not only in position — its own
   * view-transition comment gives that as the reason for the crossfade ("The expanded and compact
   * composers have different internal layouts. Use a brief crossfade while their shared wrapper moves
   * so the layout change does not read as a single-frame cut", `index.css:33-35`). A docked form that
   * merely MOVED would keep hero proportions and the crossfade would cover nothing.
   */
  it('gives the docked form a genuinely smaller field floor than the hero form', () => {
    expect(ruleFor('textarea {')).toContain('min-block-size: var(--composer-field-min-hero)');
    expect(ruleFor(":host([state='docked']) textarea {")).toContain(
      'min-block-size: var(--composer-field-min-docked)',
    );
    // The spec's `min-h-17.5` on the one editor against a compact form
    // that is a single truncating line. Compared as NUMBERS, so a token
    // edit that made the two equal — or inverted them — fails here rather than merely looking odd.
    const declared = (name: string): string => {
      const at = tokens.indexOf(`${name}:`);
      expect(at, `no ${name}`).toBeGreaterThan(-1);
      return tokens.slice(at + name.length + 1, tokens.indexOf(';', at)).trim();
    };
    const hero = declared('--composer-field-min-hero');
    const docked = declared('--composer-field-min-docked');
    expect(hero).toBe('4.375rem');
    expect(Number.parseFloat(hero) * 16).toBe(70);
    // 1lh resolves against the field's own leading (1.625 x 14px), well under the 70px hero floor.
    expect(docked).toBe('1lh');
    expect(1.625 * 16 * Number.parseFloat(docked)).toBeLessThan(Number.parseFloat(hero) * 16);
  });

  it('moves only the FLOOR when docking, so a docked draft still grows', () => {
    // The ceiling and the growth mode stay on the base rule. Pinning either of them in the docked
    // rule would freeze the field at one line — compact, and unusable for a two-line query.
    const base = ruleFor('textarea {');
    expect(base).toContain('field-sizing: content');
    expect(base).toContain('max-block-size: var(--composer-field-max)');
    const dockedRule = ruleFor(":host([state='docked']) textarea {");
    expect(dockedRule).not.toContain('max-block-size');
    expect(dockedRule).not.toContain('field-sizing');
    expect(dockedRule).not.toContain('height:');
    expect(dockedRule).not.toContain('overflow');
  });

  it('tightens BOTH rows on docking, to the spec compact inset', () => {
    // The spec's compact row: `px-3 py-2` against the expanded `px-4 pt-4 pb-2`
    // (`:2854-2855`). A field floor that shrank while the padding stayed hero-sized would still
    // render a tall, mostly-empty band.
    expect(ruleFor('.field {')).toContain(
      'padding: var(--space-4) var(--space-4) var(--space-2)',
    );
    expect(ruleFor(":host([state='docked']) .field {")).toContain(
      'padding: var(--space-2) var(--space-3) var(--space-1)',
    );
    expect(ruleFor('.footer {')).toContain('padding: 0 var(--space-4) var(--space-4)');
    expect(ruleFor(":host([state='docked']) .footer {")).toContain(
      'padding: 0 var(--space-3) var(--space-2)',
    );
  });

  it('leaves the control glyph its own colour and no placeholder box', () => {
    const glyph = ruleFor('.control-glyph {');
    expect(glyph).toContain('color: var(--control-icon-color)');
    // The slice-3 placeholder was a filled swatch; a real stroke glyph must not keep its box.
    expect(glyph).not.toContain('background');
    expect(glyph).not.toContain('border-radius');
  });

  it('spends only budgeted motion, by token name', () => {
    const values = [...composer.matchAll(/transition:\s*([^;]+);/g)].map((m) => m[1]);
    expect(values.length).toBeGreaterThanOrEqual(4);
    for (const value of values) {
      expect(value, `untokenized transition: ${value}`).toMatch(/var\(--duration-sv3-|^none$/);
    }
    expect(composer).toContain('var(--duration-sv3-morph) var(--ease-sv3-morph)');
    expect(composer).toContain('var(--duration-sv3-micro) var(--ease-sv3-enter)');
  });

  it('names the hero headline off the one display size, at the spec weight', () => {
    const rule = ruleFor('\n      .headline {');
    expect(rule).toContain('font-size: var(--font-size-sv3-display)');
    expect(rule).toContain('font-weight: 400');
    expect(rule).toContain('letter-spacing: -0.025em');
    expect(tokens).toContain('--font-size-sv3-display: 1.875rem');
  });
});

/**
 * The view-transition sheet is DOCUMENT-level (§8.1): a `::view-transition-*` pseudo resolves custom
 * properties against the document root, where this window's host-scoped tokens deliberately declare
 * nothing. Its numbers are therefore literals — and these cases are what stops them drifting from
 * the tokens of the same name, plus the containment guard that keeps the sheet off the shipped app.
 */
describe('the document-level morph sheet is pinned to the token budget and to its own scope', () => {
  it('animates the container for exactly the duration the morph token declares', () => {
    expect(SV3_MORPH_DURATION_MS).toBe(180);
    expect(tokens).toContain(`--duration-sv3-morph: ${SV3_MORPH_DURATION_MS}ms`);
    expect(SV3_MORPH_SHEET_TEXT).toContain(`animation-duration: ${SV3_MORPH_DURATION_MS}ms`);
    expect(SV3_MORPH_SHEET_TEXT).toContain(`animation-timing-function: ${SV3_MORPH_EASING}`);
  });

  it('kills the root transition and crosses the images only in the middle third', () => {
    expect(SV3_MORPH_SHEET_TEXT).toContain('::view-transition-old(root)');
    expect(SV3_MORPH_SHEET_TEXT).toContain('::view-transition-new(root)');
    const old = SV3_MORPH_SHEET_TEXT.slice(
      SV3_MORPH_SHEET_TEXT.indexOf('@keyframes sv3-composer-old'),
      SV3_MORPH_SHEET_TEXT.indexOf('@keyframes sv3-composer-new'),
    );
    // Hold at 1 through the first 35%, hold at 0 from 65% — the box moves the whole time, the
    // content swaps only while the eye is tracking the movement.
    expect(old).toMatch(/0%,\s*35%\s*{\s*opacity: 1/);
    expect(old).toMatch(/65%,\s*100%\s*{\s*opacity: 0/);
  });

  it('lets the departing headline leave before the container settles', () => {
    expect(SV3_HEADLINE_EXIT_MS).toBe(130);
    expect(SV3_HEADLINE_EXIT_MS).toBeLessThan(SV3_MORPH_DURATION_MS);
    expect(SV3_MORPH_SHEET_TEXT).toContain(`animation-duration: ${SV3_HEADLINE_EXIT_MS}ms`);
    expect(SV3_MORPH_SHEET_TEXT).toContain('translateY(-6px)');
  });

  it('gates EVERY rule on the morph attribute, so the shipped app keeps its own transitions', () => {
    // Ungated, `animation: none` on the root pair would disable the shell's surface cross-fade for
    // as long as this window stayed mounted — the leak the sheet's lifetime alone cannot prevent.
    const selectors = SV3_MORPH_SHEET_TEXT.split('\n').filter((l) => l.includes('::view-transition'));
    expect(selectors.length).toBeGreaterThanOrEqual(10);
    for (const selector of selectors) {
      expect(selector, `ungated rule: ${selector.trim()}`).toContain(
        `[${SV3_MORPH_ROOT_ATTR}='true']`,
      );
    }
  });

  it('declares every keyframe it names, in the same document-level sheet', () => {
    const named = [...SV3_MORPH_SHEET_TEXT.matchAll(/animation:\s*([a-z0-9-]+)\s/g)].map((m) => m[1]);
    expect(named.length).toBeGreaterThan(0);
    for (const name of named) {
      if (name === 'none') continue;
      expect(SV3_MORPH_SHEET_TEXT).toContain(`@keyframes ${name}`);
    }
  });
});

/**
 * The palette (slice 4), as mechanism. happy-dom runs no cascade over adopted sheets and no layout, so
 * these pin the SELECTORS and the token references that decide the palette's outcome — the two insets
 * that must stay different, the single-fill guard, the conditional corner, and the fact that the
 * scroll fade is a mask rather than an overlay node.
 */
describe('the palette is built from the geometry tokens, not re-typed numbers', () => {
  const palette = styleTextOf(Sv3Palette);
  const ruleFor = (sel: string): string => {
    const at = palette.indexOf(sel);
    expect(at, `no rule for ${sel}`).toBeGreaterThan(-1);
    return palette.slice(at, palette.indexOf('}', at));
  };

  it('declares the popup box and the two scroll-fade constants as tokens', () => {
    for (const decl of [
      '--command-popup-max-height: 26.25rem',
      '--command-popup-max-width: 36rem',
      '--command-scroll-fade-height: 1.5rem',
      '--space-1-5: 6px',
      '--space-2-5: 10px',
      '--font-size-sv3-xl: 1.25rem',
    ]) {
      expect(tokens).toContain(decl);
    }
    const popup = ruleFor('.popup {');
    expect(popup).toContain('max-inline-size: var(--command-popup-max-width)');
    expect(popup).toContain('max-block-size: var(--command-popup-max-height)');
    // The spec's 420 / 576 must not reappear as literals anywhere in the component.
    expect(palette).not.toContain('26.25rem');
    expect(palette).not.toContain('36rem');
  });

  it('spends the TIGHT inset on the input row and the WIDE one on the footer', () => {
    // The whole point of §3.3(a): the field is tighter than the chrome around it. One token used for
    // both would erase the effect while every screenshot still looked plausible.
    expect(ruleFor('.shell {')).toContain('padding-inline: var(--command-shell-inset)');
    expect(ruleFor('.shell {')).toContain('padding-block: var(--space-1-5)');
    expect(ruleFor('.footer {')).toContain('padding-inline: var(--command-content-inset)');
    expect(ruleFor('.footer {')).toContain('padding-block: var(--space-2-5)');
    expect(ruleFor('.footer {')).not.toContain('var(--command-shell-inset)');
    expect(ruleFor('.shell {')).not.toContain('var(--command-content-inset)');
    // The search glyph hangs off the SHELL inset, so moving that one token moves both together.
    expect(ruleFor('.field-glyph {')).toContain(
      'inset-inline-start: calc(var(--command-shell-inset) + 1px)',
    );
    expect(ruleFor('input {')).toContain(
      'padding-inline-start: calc(var(--command-shell-inset) + var(--space-6))',
    );
    // Live measurement caught this: the ambient `:focus-visible` sheet outranks a bare `input` rule,
    // so without an equally specific override the field renders as a boxed control across the row.
    expect(ruleFor('input:focus-visible {')).toContain('outline: none');
  });

  it('takes its stacking rung from the z-scale, never a literal', () => {
    expect(ruleFor(':host([open]) {')).toContain('z-index: var(--z-overlay)');
    const zIndexes = [...palette.matchAll(/z-index:\s*([^;]+);/g)].map((m) => (m[1] ?? '').trim());
    expect(zIndexes.length).toBeGreaterThan(0);
    for (const value of zIndexes) {
      expect(value, `untokenized z-index: ${value}`).toMatch(/^var\(--z-/);
    }
    // Window-scoped by construction: a fixed layer would escape the window onto the shipped chrome.
    expect(palette).not.toContain('position: fixed');
    expect(ruleFor(':host([open]) {')).toContain('position: absolute');
    expect(ruleFor(':host([open]) {')).toContain('inset: 0');
    // ...and the host it covers is what gives it a containing block.
    expect(styleTextOf(SearchV3View)).toContain('position: relative');
  });

  it('shows ONE fill: selected is guarded out of highlighted, in the selector', () => {
    expect(ruleFor('.item[data-selected]:not([data-highlighted])')).toContain(
      'background: color-mix(in srgb, var(--foreground) 6%, transparent)',
    );
    expect(ruleFor('.item[data-highlighted] {')).toContain(
      'background: color-mix(in srgb, var(--foreground) 9%, transparent)',
    );
    expect(ruleFor('.item[data-highlighted] {')).toContain('color: var(--foreground)');

    // Every selected-keyed fill is guarded — an unguarded one would stack 6% under the 9% the moment
    // the keyboard landed on the current choice, which is exactly the two-fill state §3.3 forbids.
    const selectedRules = [...palette.matchAll(/[^\n]*\[data-selected\][^\n]*/g)].map((m) => m[0]);
    expect(selectedRules.length).toBeGreaterThanOrEqual(1);
    for (const selector of selectedRules) {
      expect(selector, `unguarded selected fill: ${selector.trim()}`).toContain(
        ':not([data-highlighted])',
      );
    }
    // And there is no THIRD fill: the pointer moves the highlight instead of painting its own hover.
    expect(palette).not.toContain('.item:hover');
  });

  it('rounds the panel bottom off the FOOTER’s presence, not off a flag', () => {
    const panel = ruleFor('.panel {');
    expect(panel).toContain('border-start-start-radius: var(--radius-xl)');
    expect(panel).toContain('border-start-end-radius: var(--radius-xl)');
    // Unconditionally rounding the bottom here would make the conditional rule below unobservable.
    expect(panel).not.toContain('border-end-start-radius');
    expect(panel).not.toContain('border-end-end-radius');

    const conditional = ruleFor('.panel:not(:has(+ .footer))');
    expect(conditional).toContain('border-end-start-radius: var(--radius-2xl)');
    expect(conditional).toContain('border-end-end-radius: var(--radius-2xl)');
    // With a footer present, IT carries the outer corner minus the popup's own 1px border.
    expect(ruleFor('.footer {')).toContain(
      'border-end-start-radius: calc(var(--radius-2xl) - 1px)',
    );
    expect(ruleFor('.footer {')).toContain('border-end-end-radius: calc(var(--radius-2xl) - 1px)');
  });

  it('fades the list with a MASK, and carries no gradient overlay to do it with', () => {
    const list = ruleFor('.list {');
    expect(list).toContain('mask-image:');
    expect(list).toContain('mask-repeat: no-repeat');
    expect(list).toContain('100% var(--command-scroll-fade-height)');
    expect(list).toContain('calc(100% - var(--command-scroll-fade-height))');
    // The third layer holds the scrollbar column at full opacity.
    expect(list).toContain('var(--app-scrollbar-width) 100%');
    // Seven stops, an eased ramp rather than a linear one.
    for (const stop of ['10%', '24%', '42%', '62%', '82%']) {
      expect(list).toContain(stop);
    }
    // An overlay gradient is the form the spec explicitly rejected: it only works while the chrome
    // and the content share a background, and this list sits on glass.
    expect(palette).not.toMatch(/background(-image)?:\s*linear-gradient/);
    expect(palette).not.toContain('.list::before');
    expect(palette).not.toContain('.list::after');
  });

  it('keeps the palette on the item ladder: spec sizes, tokenized', () => {
    const item = ruleFor('.item {');
    expect(item).toContain('min-height: var(--space-7)');
    expect(item).toContain('padding-inline: var(--space-2)');
    expect(item).toContain('padding-block: var(--space-1-5)');
    expect(item).toContain('border-radius: var(--radius-sm)');
    expect(ruleFor('.list {')).toContain('padding: var(--space-2)');
    expect(ruleFor('.list {')).toContain('scroll-padding-block: var(--space-2)');
    expect(ruleFor('.empty {')).toContain('padding-block: var(--space-6)');
    expect(ruleFor('.separator {')).toContain('margin-block: var(--space-2)');
    expect(ruleFor('.group-label {')).toContain('padding-block: var(--space-1-5)');
    // The one place the spec spends tracking in its whole app: the palette's keyboard hints.
    expect(ruleFor('.shortcut {')).toContain('letter-spacing: 0.1em');
    expect(ruleFor('.shortcut {')).toContain('font-size: var(--font-size-sv3-xs)');
    expect(ruleFor('.shortcut {')).toContain('font-weight: 500');
    expect(ruleFor('.shortcut {')).toContain('color: var(--secondary-label)');
    expect(ruleFor('.key {')).toContain('background: color-mix(in srgb, var(--foreground) 8%');
    expect(ruleFor('.footer {')).toContain(
      'background: color-mix(in srgb, var(--foreground) 2.5%, transparent)',
    );
  });

  it('puts the whole dialog material on ONE node, with the mandatory no-blur fallback', () => {
    // Slice 3's remediation, applied ahead of the fact: a split silhouette reports no glass on
    // whichever node carries the radius.
    const start = palette.indexOf('.popup {');
    const end = palette.indexOf('}', start);
    const rule = palette.slice(start, end);
    expect(rule).toContain('border-radius: var(--radius-2xl)');
    // Tempdoc 859 §B (D2) — the fill's translucency and the blur are now DERIVED from the same
    // `--glass-blur-scale`, so the two escapes (`[data-surface-mode="solid"]` and
    // `prefers-reduced-transparency`) can never leave this surface see-through with nothing blurred
    // behind it. Pinning the derived forms, not the bare token, is the point: an edit that dropped
    // the multiplier would silently un-wire both escapes and no other assertion would notice.
    expect(rule).toContain(
      'var(--background) calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale))',
    );
    expect(rule).toContain(
      'backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))',
    );
    expect(rule).toContain('saturate(var(--glass-saturation))');
    expect(rule).toContain('box-shadow: var(--dialog-shadow)');
    expect(rule).toContain('border: 1px solid var(--dialog-border)');
    const at = palette.indexOf('@supports not ((-webkit-backdrop-filter: blur(1px))');
    expect(at).toBeGreaterThan(-1);
    expect(palette.slice(at, at + 200)).toContain('background: var(--background)');
    // The backdrop is the spec's own recipe, token-fed like every other material in the window —
    // and since 859 §B it honours the same multiplier. Its FILL is deliberately NOT derived: a
    // scrim is a dimming layer, not a readable surface, and an opaque scrim would hide the window.
    expect(ruleFor('.backdrop {')).toContain('background: var(--dialog-backdrop)');
    expect(ruleFor('.backdrop {')).toContain(
      'blur(calc(var(--dialog-backdrop-blur) * var(--glass-blur-scale)))',
    );
  });

  it('inverts the dialog elevation in the token sheet, not in the component', () => {
    const split = tokens.indexOf(":host([theme='light'])");
    const dark = tokens.slice(0, split);
    const light = tokens.slice(split);
    // A dialog is the ONE dark surface that keeps its drop shadow: it must separate from a live
    // window behind it, so it catches light on the top edge AND casts.
    expect(dark).toContain('inset 0 1px rgb(255 255 255 / 4%), 0 24px 72px -20px rgb(0 0 0 / 90%)');
    expect(dark).toContain('--dialog-border: color-mix(in srgb, var(--color-white) 8%, transparent)');
    expect(light).toContain('--dialog-shadow: 0 24px 64px -24px rgb(0 0 0 / 65%)');
    expect(light).toContain('--dialog-border: color-mix(in srgb, var(--foreground) 10%, transparent)');
    // One backdrop formula in both modes — it resolves against whichever --background is live.
    expect(light).not.toContain('--dialog-backdrop:');
    // The component spends no colour literal of its own; only the alpha stops of the fade mask do.
    const withoutMask = palette.replace(/mask-image:[\s\S]*?;/g, '');
    for (const literal of ['hsl(', 'oklch(', '#']) {
      expect(withoutMask).not.toContain(literal);
    }
    expect(withoutMask).not.toContain('rgb(');
  });
});

describe('the empty state is the spec anatomy, in tokens', () => {
  const empty = styleTextOf(Sv3Empty);
  const ruleFor = (sel: string): string => {
    const at = empty.indexOf(sel);
    expect(at, `no rule for ${sel}`).toBeGreaterThan(-1);
    return empty.slice(at, empty.indexOf('}', at));
  };

  it('measures the header rather than boxing it, and spends the spec gaps', () => {
    const host = empty.slice(empty.indexOf(':host {'), empty.indexOf('}', empty.indexOf(':host {')));
    expect(host).toContain('gap: var(--space-6)');
    expect(host).toContain('padding: var(--space-6)');
    expect(host).toContain('justify-content: center');
    expect(ruleFor('.header {')).toContain('max-inline-size: 24rem');
    expect(ruleFor('.media {')).toContain('margin-bottom: var(--space-6)');
    expect(ruleFor('.title + .description {')).toContain('margin-top: var(--space-1)');
    // The roomier variant is the spec's single breakpoint on this component.
    expect(empty).toContain('@media (min-width: 48rem)');
    expect(empty).toContain('padding: var(--space-12)');
  });

  it('fans the three cards with individual transform properties, ghosts unshadowed', () => {
    const tile = ruleFor('.tile {');
    expect(tile).toContain('inline-size: var(--space-9)');
    expect(tile).toContain('block-size: var(--space-9)');
    expect(tile).toContain('border-radius: var(--radius-md)');
    expect(tile).toContain('background: var(--card)');
    expect(tile).toContain('box-shadow: var(--empty-tile-shadow)');
    // The hairline edge is the elevation inversion at its smallest, and it is a TOKEN both ways.
    expect(ruleFor('.tile::before {')).toContain('border-radius: calc(var(--radius-md) - 1px)');
    expect(ruleFor('.tile::before {')).toContain('box-shadow: var(--empty-tile-edge)');
    expect(ruleFor('.tile.ghost {')).toContain('scale: 0.84');
    expect(ruleFor('.tile.ghost {')).toContain('box-shadow: none');
    expect(ruleFor('.tile.ghost-start {')).toContain('rotate: -10deg');
    expect(ruleFor('.tile.ghost-start {')).toContain('transform-origin: bottom left');
    expect(ruleFor('.tile.ghost-end {')).toContain('rotate: 10deg');
    expect(ruleFor('.tile.ghost-end {')).toContain('transform-origin: bottom right');
    // A shorthand would order translate/rotate/scale by hand; the spec composes three properties.
    expect(empty).not.toContain('transform:');
  });

  it('reads type and colour off the ramp, spending no literal of its own', () => {
    expect(ruleFor('.title {')).toContain('font-size: var(--font-size-sv3-xl)');
    expect(ruleFor('.title {')).toContain('font-weight: 600');
    // Anchored on the line start: `.description {` also occurs inside `.title + .description {`.
    expect(ruleFor('\n      .description {')).toContain('color: var(--muted-foreground)');
    expect(ruleFor('\n      .description {')).toContain('font-size: var(--font-size-sv3-sm)');
    for (const literal of ['rgb(', 'hsl(', 'oklch(', '#']) {
      expect(empty).not.toContain(literal);
    }
  });
});

describe('the shared sheet carries what tokens cannot', () => {
  it('declares the four looping keyframes, because keyframes do not inherit into shadow roots', () => {
    for (const name of ['skeleton', 'ghost-pulse', 'status-pulse', 'status-ping']) {
      expect(shared).toContain(`@keyframes ${name}`);
    }
  });

  it('duty-cycles them: stepped ramps and a hold at each extreme', () => {
    expect(shared).toContain('steps(4)');
    expect(shared).toContain('steps(6)');
    expect(shared).toContain('steps(8)');
    // The holds — an extreme is carried across a span, never a single stop.
    expect(shared).toContain('40% {');
    expect(shared).toContain('42% {');
  });

  it('reads the scrollbar tokens through the inherited standard property', () => {
    expect(shared).toContain('scrollbar-color: var(--app-scrollbar-thumb) transparent');
  });

  it('drops the looping animations under reduced motion', () => {
    const guard = shared.slice(shared.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(guard).toContain('animation: none');
    for (const name of ['skeleton', 'ghost-pulse', 'status-pulse', 'status-ping']) {
      expect(guard).toContain(`.sv3-anim-${name}`);
    }
  });
});

/**
 * The budget gate's measured a11y defects (live audit 2026-08-25, D1 + D2).
 *
 * Both were found by a LIVE, measured pass — axe for the contrast, a pixel measurement for the hit
 * area — because neither is visible to a static gate: `check-contrast-matrix` reads
 * `styles/tokens.css` and cannot see this window's own palette, and no gate measures target size at
 * all. So the pins here are the DECLARATIONS (happy-dom lays out no shadow content, so there is no
 * honest computed box or composited colour to read), each scoped to its own rule body so a stray
 * match elsewhere in the sheet cannot satisfy it. The measured proof stays with the next live audit.
 */
describe('the budget gate clears the a11y floors the live audit measured', () => {
  const ruleBodyOf = (styles: string, selector: string): string => {
    const m = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styles);
    if (m === null) throw new Error(`Sv3Main.styles has no \`${selector}\` rule`);
    return m[1] as string;
  };

  it('D1: the facts-row labels wear the LABEL role, not the quiet tier that failed AA on the wash', () => {
    const dt = ruleBodyOf(styleTextOf(Sv3Main), '\\.run-prompt-facts dt');
    // --secondary-label measured 4.38:1 against this surface (an 8% --success wash over the light
    // background) — seven serious axe color-contrast nodes, light palette only. --foreground is the
    // role `.run-note-label` already uses for a label in this same family, and measures 13.51:1.
    expect(dt).toMatch(/color:\s*var\(--foreground\)/);
    expect(dt).not.toMatch(/--secondary-label/);
    // The label/value distinction the quiet ink used to carry now rides on weight, so the panel is
    // still a list of labelled facts rather than one run of undifferentiated text.
    expect(dt).toMatch(/font-weight:\s*500/);
  });

  it('D1 (completing #555, closing-window findings 2026-08-25): the fine-print/auto-continue/cut-short siblings clear the same wash', () => {
    // .run-prompt-fine, .run-prompt-auto and .cut-short all carried the same --secondary-label ink as
    // the dt fix above, on the same 8% --success wash: .run-prompt-fine/.run-prompt-auto measured
    // 4.37:1 (the D1 sweep this test's title completes); .cut-short was never rendered during the
    // original audit but carries the identical token on the identical wash, so it fails identically.
    // All three must clear AA on the wash via --foreground, the same role token the dt fix used — and
    // must stay quiet through size/weight, not through a failing color (an earlier #555 observation
    // flagged --foreground as possibly too loud for this quiet-by-design fine print; the measured
    // audit settled it in favour of the fix, so this pins the settled answer).
    const styles = styleTextOf(Sv3Main);
    const fine = ruleBodyOf(styles, '\\.run-prompt-fine');
    expect(fine).toMatch(/color:\s*var\(--foreground\)/);
    expect(fine).not.toMatch(/--secondary-label/);

    const auto = ruleBodyOf(styles, '\\.run-prompt-auto');
    expect(auto).toMatch(/color:\s*var\(--foreground\)/);
    expect(auto).not.toMatch(/--secondary-label/);

    const cutShort = ruleBodyOf(styles, '\\.cut-short');
    expect(cutShort).toMatch(/color:\s*var\(--foreground\)/);
    expect(cutShort).not.toMatch(/--secondary-label/);
  });

  it('D2: the auto-continue control clears the 24px target floor in both axes', () => {
    const styles = styleTextOf(Sv3Main);
    // The checkbox measured 13 x 13. The label IS the target (it activates the control it wraps), so
    // the row carries the block axis...
    expect(ruleBodyOf(styles, '\\.run-prompt-auto')).toMatch(/min-height:\s*24px/);
    // ...and a hit box around the glyph carries a pointer aimed at the checkbox itself. inline-flex +
    // centring grows the box WITHOUT resizing the native control inside it.
    const hit = ruleBodyOf(styles, '\\.run-prompt-auto-hit');
    expect(hit).toMatch(/min-width:\s*24px/);
    expect(hit).toMatch(/min-height:\s*24px/);
    expect(hit).toMatch(/display:\s*inline-flex/);
  });
});

/* ── Tempdoc 874: one width authority for the chat column ────────────────────────────────────── */

/**
 * The transcript, the composer band and the context bar are three components that must share ONE
 * edge — the design spec gives them all 'max-w-3xl', and until 874 each restated it as a literal
 * `48rem`. Three literals is three chances to drift, and it also made the width unadjustable: an
 * inline override on the window host cannot reach a hard-coded declaration. These pin the
 * consolidation — each component reads the token, none re-hardcodes the number, and the token sheet
 * still carries the shipped default the preset system overrides.
 */
describe('the chat column caps on one token, not three literals', () => {
  for (const [name, ctor] of [
    ['Sv3Main (.transcript)', Sv3Main],
    ['Sv3Composer (.band)', Sv3Composer],
    ['Sv3ContextBar (.bar)', Sv3ContextBar],
  ] as const) {
    it(`${name} caps on --measure-prose and re-hardcodes no width`, () => {
      const styles = styleTextOf(ctor);
      expect(styles).toContain('max-inline-size: var(--measure-prose)');
      // Bare `var()` with NO fallback: a `var(--measure-prose, 48rem)` would pass the line above
      // while quietly restoring the literal. This assertion is the regression pin for that.
      expect(styles).not.toContain('48rem');
      // EXCLUSIVITY. A consumer must only ever READ the property, never DECLARE it. Its own
      // `:host { --measure-prose: … }` would shadow the inline value `SearchV3View.applyChatWidth`
      // writes on the view host — the preset would stop moving that component, silently, while
      // every assertion above still passed. The unit environment (happy-dom) does not compute the
      // cascade, so no runtime test in this suite can observe that regression; this static pin on
      // the declaration is the only place it is catchable.
      expect(styles).not.toMatch(/--measure-prose\s*:/);
    });
  }

  it('the token sheet still declares the shipped default the preset overrides', () => {
    // The inline host write (SearchV3View.applyChatWidth) is what moves it; this declaration is what
    // a reader who never chose a preset gets, and what a detached/preview render falls back to.
    expect(tokens).toContain('--measure-prose: 48rem');
  });
});

/* ── Tempdoc 859 (live audit 2026-08-25/26): the two measured token defects ──────────────────── */

/**
 * Two findings from the live audits of the shipped window, pinned as MATHS rather than as values so
 * a later re-tune of the token cannot pass the letter of the fix and fail its point. (Contrast here
 * came from canvas-resolved colour sampling, not from axe: axe's colour rules cover TEXT, and 1.4.11
 * is about a non-text boundary.)
 *
 * The ratios are computed with the product's own contrast authority (`themes/contrast.ts` —
 * `contrastRatio`, the same function `conformanceGate` and the palette tests use), never by eye.
 * `parseColor` deliberately does not implement `oklch()` (its own doc says: resolve those in a
 * browser and pass the `rgb()`), and happy-dom computes no cascade here, so the two `oklch()` page
 * bases are pinned at the values the LIVE audit measured on the running window:
 * dark `--background` = rgb(10,10,10), light `--background` = rgb(252,252,252). The pin is guarded —
 * the first case re-checks that the sheet still derives those bases from the same primitives, so a
 * palette change cannot leave this arithmetic quietly describing a window that no longer exists.
 *
 * SCOPE. `.glass::after` paints three arms: RESTING, FOCUSED (the border re-points to `--ring`, so
 * this token is uninvolved) and INVALID (`--destructive`). Since the owner decision of 2026-09-07
 * (tempdoc 948) removed tempdoc 864 Layer 1(d)'s 55% resting fade, the resting arm takes this token
 * at FULL alpha — so the token's own ratio and the ratio of the state a reader actually meets are
 * now the same number, and the resting case at the bottom of this block asserts it on the surfaces
 * that state really composites over.
 */
describe('859: the composer outline meets WCAG 1.4.11 in both themes', () => {
  const WCAG_NON_TEXT = 3;

  /** Live-measured sRGB of the two page bases (see the block comment for why they are pinned). */
  const DARK_PAGE: Rgb = [10, 10, 10];
  const LIGHT_PAGE: Rgb = [252, 252, 252];
  const WHITE: Rgb = [255, 255, 255];
  const BLACK: Rgb = [0, 0, 0];

  /** Source-over compositing of `fg` at `alpha` onto an opaque `bg` — what a 1px translucent border does. */
  const over = (fg: Rgb, alpha: number, bg: Rgb): Rgb =>
    [0, 1, 2].map((i) =>
      Math.round((fg[i] as number) * alpha + (bg[i] as number) * (1 - alpha)),
    ) as unknown as Rgb;

  /** `color-mix(in srgb, A p%, B)` on two opaque colours. */
  const mix = (a: Rgb, b: Rgb, pa: number): Rgb =>
    [0, 1, 2].map((i) =>
      Math.round((a[i] as number) * pa + (b[i] as number) * (1 - pa)),
    ) as unknown as Rgb;

  const themeSplit = tokens.indexOf(":host([theme='light'])");
  const darkBlock = tokens.slice(0, themeSplit);
  const lightBlock = tokens.slice(themeSplit);

  const alphaOf = (block: string): number => {
    const m =
      /--composer-outline: color-mix\(in srgb, var\(--color-white\) (\d+)%/.exec(block) ??
      /--composer-outline: rgb\(0 0 0 \/ (\d+)%\)/.exec(block);
    if (m === null) throw new Error('the --composer-outline declaration changed shape');
    return Number(m[1]) / 100;
  };

  it('the page bases this arithmetic assumes are still the ones the sheet declares', () => {
    expect(darkBlock).toContain('--background: var(--color-neutral-950)');
    expect(tokens).toContain('--color-neutral-950: oklch(14.5% 0 0)');
    expect(lightBlock).toContain('--background: var(--color-zinc-25)');
    expect(tokens).toContain('--color-zinc-25: oklch(99.2% 0 0)');
    // …and the surface the edge is drawn OVER, in each theme.
    expect(darkBlock).toContain(
      '--composer-glass-surface: color-mix(in srgb, var(--background) 96%, var(--color-white))',
    );
    expect(lightBlock).toContain('--composer-glass-surface: var(--card)');
    expect(lightBlock).toContain('--card: var(--color-white)');
  });

  it('dark: the ENGAGED edge clears 3:1 against BOTH adjacent colours (5% white was 1.21:1)', () => {
    const glass = mix(DARK_PAGE, WHITE, 0.96); // --composer-glass-surface
    const edge = over(WHITE, alphaOf(darkBlock), glass);
    // The OUTSIDE — the failure the audit reported, at 1.21:1.
    expect(contrastRatio(edge, DARK_PAGE)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    // The INSIDE. A boundary that only cleared the floor on one side would have moved the failure,
    // not closed it — 1.4.11 is about the adjacent colours, plural.
    expect(contrastRatio(edge, glass)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    // The old value recomputed: it lands on the audit's measured 1.21:1, which is what validates this
    // arithmetic against the running window rather than against itself.
    expect(contrastRatio(over(WHITE, 0.05, glass), DARK_PAGE)).toBeCloseTo(1.215, 3);
  });

  it('light: the ENGAGED edge clears 3:1 against both, which 8% black did not either', () => {
    const card = WHITE; // --composer-glass-surface: var(--card) → --color-white
    const edge = over(BLACK, alphaOf(lightBlock), card);
    expect(contrastRatio(edge, LIGHT_PAGE)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    expect(contrastRatio(edge, card)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    // "The light theme's 8% black edge is the stronger half" was true AND still nowhere near 3:1 —
    // pinned so the audit's wording is never read as "light was fine".
    expect(contrastRatio(over(BLACK, 0.08, card), LIGHT_PAGE)).toBeLessThan(WCAG_NON_TEXT);
  });

  /**
   * THE RESTING EDGE — the state a reader meets before touching anything, and the one 1.4.11 is
   * really about. This is the case the whole item turns on, so it asserts the composite rather than
   * the token: the two cases above would stay green over a resting edge nobody could see.
   *
   * TWO surfaces, named because an earlier cut conflated them: the resting border composites over the
   * RESTING glass, not the engaged one. `--composer-rest-surface` mixes the glass 35% back toward the
   * page while `--composer-rest` is 1, so the backdrop is rgb(14,14,14) dark / rgb(253,253,253)
   * light, not the rgb(20,20,20) / rgb(255,255,255) the engaged cases use.
   *
   * WHAT CHANGED (owner decision 2026-09-07, tempdoc 948). Tempdoc 864 Layer 1(d) shipped TWO derived
   * declarations off `--composer-rest`: the material (the surface lift) and the frame, whose
   * `border-color` "fades to 45% of `--composer-outline` at rest". The frame half is removed; the
   * material half is untouched. 864's own principle is what decides it — that layer "deliberately
   * spends no text contrast", and the frame fade was the half spending NON-text contrast, which is
   * what 1.4.11 measures. The de-emphasis survives on the surface lift plus the `--ring` hand-off at
   * focus (9.69:1 dark / 5.84:1 light against this edge's 3.25 / 3.32), so resting-versus-focused is
   * still a colour change AND a material change.
   */
  it('the RESTING edge clears 3:1 against the page AND the resting glass, in both themes', () => {
    // The frame no longer reads the knob at all: full token alpha in every unfocused state.
    const composer = styleTextOf(Sv3Composer).replace(/\s+/g, ' ');
    expect(
      composer,
      'the 864 frame fade is back — the resting edge would drop under the floor again',
    ).not.toContain('var(--composer-outline) calc(100% - 55% * var(--composer-rest))');
    // …while the SURFACE half of the same knob is deliberately still there. Removing both would have
    // taken the resting affordance with it, which is not what was decided.
    expect(composer).toContain('var(--composer-glass-surface) calc(100% - 65% * var(--composer-rest))');

    const restGlassDark = mix(mix(DARK_PAGE, WHITE, 0.96), DARK_PAGE, 0.35);
    const restGlassLight = mix(WHITE, LIGHT_PAGE, 0.35);
    const restingDark = over(WHITE, alphaOf(darkBlock), restGlassDark);
    const restingLight = over(BLACK, alphaOf(lightBlock), restGlassLight);

    // Against the PAGE — "can a reader find the box on the screen".
    expect(contrastRatio(restingDark, DARK_PAGE)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    expect(contrastRatio(restingLight, LIGHT_PAGE)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    // Against the RESTING GLASS — the inside half of the same boundary. 1.4.11 is about the adjacent
    // colours, plural, and this half is the one the resting surface lift makes hardest.
    expect(contrastRatio(restingDark, restGlassDark)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);
    expect(contrastRatio(restingLight, restGlassLight)).toBeGreaterThanOrEqual(WCAG_NON_TEXT);

    // The measured numbers, so a re-tune that still technically clears the floor cannot quietly walk
    // the margin down to nothing.
    expect(contrastRatio(restingDark, DARK_PAGE)).toBeCloseTo(3.25, 2);
    expect(contrastRatio(restingLight, LIGHT_PAGE)).toBeCloseTo(3.32, 2);

    // Anti-vacuity: with the 864 fade in place these same surfaces were under the floor, so the
    // assertions above are testing the removal and not the arithmetic's own generosity.
    expect(contrastRatio(over(WHITE, alphaOf(darkBlock) * 0.45, restGlassDark), DARK_PAGE)).toBeLessThan(
      WCAG_NON_TEXT,
    );
    expect(
      contrastRatio(over(BLACK, alphaOf(lightBlock) * 0.45, restGlassLight), LIGHT_PAGE),
    ).toBeLessThan(WCAG_NON_TEXT);
  });

  it('the FOCUSED edge is --ring, so this token never governs it (measured audit)', () => {
    // Why the full-alpha cases above are a token property and not a screen state: the focus arm
    // re-points the border away from `--composer-outline` entirely, and in the shipped Chromium a
    // focused textarea always matches `:focus-visible` — so `--composer-rest: 0` (full alpha) and
    // "not focus-visible" cannot both hold. The focused edge measured 9.69:1 dark / 5.84:1 light,
    // unchanged by this PR.
    const composer = styleTextOf(Sv3Composer);
    const focusRule = /\.glass:has\(textarea:focus-visible\)::after\s*\{([^}]*)\}/.exec(composer);
    expect(focusRule, 'the focus arm moved — re-check which token paints the focused edge').not.toBeNull();
    expect(focusRule?.[1]).toContain('border-color: var(--ring)');
    // …and the knob that would restore full alpha is spent by the plain `:focus` sibling, so the two
    // conditions are entangled by construction rather than by coincidence.
    expect(composer).toContain('.glass:has(textarea:focus) {');
  });
});

/**
 * Tempdoc 859 (2026-08-26) — the `jf-reasoning-block` bridge pointed `--text-primary` AND
 * `--text-secondary` at `--foreground`, so `ReasoningBlock`'s `.disclosure:hover` colour shift
 * resolved to the value it was already showing: a hover state that changed nothing, in the one
 * window that renders the model's thinking most.
 */
describe('859: the reasoning-block bridge gives the hover somewhere to go', () => {
  const bridge = (): string => {
    const m = /jf-reasoning-block\s*\{([^}]*)\}/.exec(styleTextOf(Sv3Main));
    if (m === null) throw new Error('Sv3Main.styles has no `jf-reasoning-block` bridge');
    return m[1] as string;
  };

  const valueOf = (block: string, prop: string): string => {
    const m = new RegExp(`${prop}:\\s*([^;]+);`).exec(block);
    if (m === null) throw new Error(`the bridge declares no \`${prop}\``);
    return (m[1] as string).trim();
  };

  it('resting and hover rungs resolve to DIFFERENT tokens', () => {
    const block = bridge();
    const resting = valueOf(block, '--text-secondary'); // ReasoningBlock `.container`
    const hover = valueOf(block, '--text-primary'); // ReasoningBlock `.disclosure:hover`
    expect(resting).not.toBe(hover);
    // Named, not merely different: the hover takes the window's full ink, and the resting rung takes
    // its aside grade lifted the smallest step toward that ink — the lift is what keeps the copy
    // control (which reads the same rung) above AA on the block's doubled wash, measured by the
    // window's contrast oracle in `Sv3Main.imports.test.ts`. A bridge that made the two differ by
    // pointing at two EQUAL aliases would satisfy `not.toBe` while still painting one colour, so
    // both ends are pinned by name and the case below pins that the names are not the same value.
    expect(resting).toBe('color-mix(in srgb, var(--secondary-label) 90%, var(--foreground))');
    expect(hover).toBe('var(--foreground)');
    // …and the resting rung is genuinely DERIVED from the quiet grade, not a second hand-picked one.
    expect(resting).toContain('var(--secondary-label)');
  });

  it('reads the rungs the component actually uses, not the ones the bridge used to name', () => {
    // The bridge's original comment claimed `--text-muted` was ReasoningBlock's resting body. It is
    // not — the component reads that token nowhere, which is how the two live rungs came to be
    // aliased together unnoticed. Pinned against the component's own sheet so the mapping cannot
    // drift back to a name-based guess.
    // ReasoningBlock's `static styles` is ONE CSSResult, not the array `styleTextOf` walks.
    const block = (ReasoningBlock.styles as unknown as { cssText: string }).cssText;
    expect(block, 'the component sheet moved').toContain('.container {');
    expect(/\.container\s*\{[^}]*color:\s*var\(--text-secondary\)/.test(block)).toBe(true);
    expect(/\.disclosure:hover\s*\{[^}]*color:\s*var\(--text-primary\)/.test(block)).toBe(true);
    // …and it reads --text-muted nowhere, which is the claim the old bridge comment got wrong.
    expect(block.replace(/\/\*[\s\S]*?\*\//g, '')).not.toContain('--text-muted');
  });

  it('the two tokens the bridge names really are different values in both themes', () => {
    // The alias check above is about the bridge; this is about the palette behind it. If a theme ever
    // pointed --secondary-label at --foreground the hover would be a no-op again with the bridge
    // still reading correctly, so the sheet is asserted too.
    const themeSplit = tokens.indexOf(":host([theme='light'])");
    for (const [name, block] of [
      ['dark', tokens.slice(0, themeSplit)],
      ['light', tokens.slice(themeSplit)],
    ] as const) {
      const secondary = /--secondary-label: ([^;]+);/.exec(block)?.[1]?.trim();
      const foreground = /--foreground: ([^;]+);/.exec(block)?.[1]?.trim();
      expect(secondary, `${name}: --secondary-label`).toBeDefined();
      expect(foreground, `${name}: --foreground`).toBeDefined();
      expect(secondary, `${name}: the two rungs collapsed onto one token`).not.toBe(foreground);
    }
  });
});

/* ── Tempdoc 859: the resize grips' WCAG 2.2 2.5.8 floor ─────────────────────────────────────── */

/**
 * WCAG 2.2 2.5.8 (Target Size, Minimum): `button.sidebar-grip` measured 16 CSS px wide against the
 * 24 px floor, and the pane grip is the same anatomy on the other edge, deliberately ("one window may
 * not have two differently-sized grips"), so both carry the floor or the anatomy forks.
 *
 * THIS TEST IS THE ENFORCEMENT, and the measured audit (2026-09-07) is why that sentence is here
 * rather than "axe caught it". The repo's `ui_measure.py` runs axe without the `wcag22aa` tag, so
 * target-size is never evaluated in the normal run; and adding the tag makes the 16px grip PASS,
 * because 2.5.8's spacing exception covers an undersized target with enough clear space around it.
 * Neither of those makes the floor uninteresting — the exception depends on neighbouring layout a
 * later change can remove without touching this rule — but it does mean no live tool is watching it.
 * These assertions are.
 *
 * Asserted on the declaration rather than on a measured box: happy-dom computes no cascade and no
 * layout, so a `getBoundingClientRect` here would report 0 for a passing AND a failing grip.
 */
describe('859: both resize grips clear the 24px target floor', () => {
  const ruleBodyOf = (styles: string, selector: string): string => {
    const m = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styles);
    if (m === null) throw new Error(`SearchV3View.styles has no \`${selector}\` rule`);
    return m[1] as string;
  };

  for (const grip of ['sidebar-grip', 'pane-grip'] as const) {
    it(`button.${grip} pins a 24px inline floor without resizing its visible line`, () => {
      const body = ruleBodyOf(styleTextOf(SearchV3View), `button\\.${grip}`);
      expect(body).toMatch(/min-inline-size:\s*24px/);
      // The block axis is already full-height; the target grows in ONE axis only.
      expect(body).toMatch(/inset-block:\s*0/);
      // The VISUAL is unchanged: the 16px anatomy stays the declared size, the box paints nothing,
      // and the 2px line is drawn by `::after` positioned against the box's centre. A "fix" that
      // widened the drawn line instead would pass a size assertion and change the design.
      expect(body).toMatch(/inline-size:\s*var\(--space-4\)/);
      expect(body).toMatch(/background:\s*transparent/);
      const after = ruleBodyOf(styleTextOf(SearchV3View), `button\\.${grip}::after`);
      expect(after).toMatch(/inline-size:\s*2px/);
      expect(after).toMatch(/left:\s*50%/);
    });
  }
});
