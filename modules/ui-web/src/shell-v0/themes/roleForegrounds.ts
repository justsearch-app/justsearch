// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 3 / 558 — semantic colour roles + the foreground co-projection.
 *
 * A colour role is a (background, on-colour) PAIR, not two independent tokens. The
 * on-colour is DERIVED from the role's resolved background to a contrast floor
 * ({@link deriveForeground}) and written into the topmost cascade layer, so it follows
 * the fill — making "white text on a bright fill" (the live ~1.95:1 defect, obs #331)
 * un-rendered by construction. The static `--accent-on-*` token becomes the fallback;
 * the derived value wins.
 *
 * Called by the single appearance writer (themeState) after every theme/palette change.
 */

import { parseColor, deriveTintedForeground, contrastRatio, relativeLuminance, WCAG_AA, type Rgb } from './contrast.js';
import { ROLE_CATALOG } from './themeRoles.js';

/** A semantic colour role whose foreground derives from its background. */
export interface ColorRole {
  /** Role name. */
  readonly name: string;
  /** Background token (CSS custom-property name, without the `--`). */
  readonly bg: string;
  /** On-colour token to derive (CSS custom-property name, without the `--`). */
  readonly on: string;
  /** The WCAG contrast floor the derived foreground must clear over the background. */
  readonly floor: number;
}

/**
 * The colour roles whose on-colour is a projection of the background (558). 574 §25 Phase 1:
 * this is now a PROJECTION of the single role authority {@link ROLE_CATALOG} (themeRoles), so the
 * deriver and the catalog can never drift — every role the catalog declares is derived here, and a
 * new role joins by editing ONE list. (Previously this hand-listed only `accent`/`accent-on-tint`
 * while the catalog already declared all six; the other five on-colours were static-only.)
 */
export const COLOR_ROLES: readonly ColorRole[] = ROLE_CATALOG.map((r) => ({
  name: r.id,
  bg: r.bgToken,
  on: r.fgToken,
  floor: r.floor,
}));

const foregroundSheets = new WeakMap<Document, CSSStyleSheet>();

/**
 * Tempdoc 567 §8 (deferred → built) — the contrast floor the TINTED role foreground is derived to. Set
 * to 6:1 (deliberately between AA 4.5 and AAA 7): high enough that every tinted on-colour stays a
 * comfortable 33% above the AA standard, low enough that the hue-bearing tint is actually visible on the
 * calibrated accent palette (measured — at the AAA floor most accents lacked the headroom to tint, so the
 * effect was near-invisible). Accents without 6:1 headroom (e.g. danger) keep the maximal-contrast pole.
 */
const ROLE_TINT_FLOOR = 6;

/**
 * Pure core: given a way to resolve a background token to a colour string, produce the
 * derived on-colour declarations (one per role whose background resolves). Factored out
 * of the DOM probe so the derivation is unit-testable without a layout engine.
 */
export function deriveOnColorDecls(resolve: (bgToken: string) => string): string[] {
  const decls: string[] = [];
  for (const role of COLOR_ROLES) {
    const bg = parseColor(resolve(role.bg));
    if (!bg) continue;
    // Tempdoc 567 §8 (deferred → built) — derive a hue-bearing TINTED on-colour (a darkened/lightened
    // version of the fill's own hue) rather than flat black/white, where the fill has contrast headroom.
    // Self-regulating: it falls back to the maximal-contrast black/white on mid-tone fills with no AAA
    // headroom, so the on-colour never drops below the role floor — it is only ever MORE designed, never
    // less legible. The static `--accent-on-*` baked into themes/palettes stays the pure-b/w fallback.
    const fg = deriveTintedForeground(bg, role.floor, ROLE_TINT_FLOOR);
    decls.push(`--${role.on}: ${fg.fg};`);
  }
  return decls;
}

/** A resolved colour with its opacity (0–1). */
export interface Rgba {
  readonly rgb: Rgb;
  readonly alpha: number;
}

/** `top` painted over the opaque `under`. */
function over(top: Rgba, under: Rgb): Rgb {
  return [0, 1, 2].map((i) => top.rgb[i]! * top.alpha + under[i]! * (1 - top.alpha)) as unknown as Rgb;
}

/** The sRGB luminance below which a ground is dark: where black and white text contrast equally with it. */
const DARK_GROUND = 0.18;
/** The open result row's raised tint: 6% of the text colour over the ground (ResultsCard `.row.open`). */
const RAISED_ROW = 0.06;

/**
 * The found-word pair (first-minutes fm-04/fm-05) derived from the theme's OWN ground and ink, the way
 * {@link deriveOnColorDecls} derives the accent pairs. A found word is drawn in the normal text colour
 * (`--text-primary`) on a faint warm wash over the ground (`--surface-0`); the pair must read at
 * >= 4.5:1 on the bare ground and on the open row's raised tint.
 *
 * 1. The wash is the one authored for the ground's polarity (`--highlight-wash-on-dark|light`), chosen
 *    by the ground's own luminance and not by the light/dark setting: a palette that keeps its dark
 *    ground in Light keeps the dark wash under its light ink. Where it reads, it is written as a
 *    reference to the authored token, so the default themes paint exactly the picture's tint.
 * 2. Where it does not, the wash keeps its hue and loses opacity until the word reads (its ring with it).
 * 3. Where even the bare ground does not give the theme's ink 4.5:1 (a user theme whose own text is
 *    unreadable), the word keeps the authored wash and takes a derived ink (`--highlight-ink`).
 */
export function deriveHighlightWashDecls(read: (token: string) => Rgba | null): string[] {
  const groundRead = read('surface-0');
  const ink = read('text-primary');
  if (!groundRead || !ink) return [];
  const ground = over(groundRead, [0, 0, 0]);
  const side = relativeLuminance(ground) < DARK_GROUND ? 'dark' : 'light';
  const wash = read(`highlight-wash-on-${side}`);
  const ring = read(`highlight-wash-ring-on-${side}`);
  if (!wash || !ring) return [];
  const grounds = [ground, over({ rgb: ink.rgb, alpha: RAISED_ROW * ink.alpha }, ground)];
  const reads = (alpha: number): boolean => grounds.every((under) => {
    const fill = over({ rgb: wash.rgb, alpha }, under);
    return contrastRatio(over(ink, fill), fill) >= WCAG_AA;
  });
  if (reads(wash.alpha)) {
    return [`--highlight-wash: var(--highlight-wash-on-${side});`, `--highlight-wash-ring: var(--highlight-wash-ring-on-${side});`];
  }
  const rgba = ({ rgb: [r, g, b] }: Rgba, alpha: number): string =>
    `rgb(${Math.round(r)} ${Math.round(g)} ${Math.round(b)} / ${(alpha * 100).toFixed(1)}%)`;
  for (let step = Math.floor(wash.alpha * 100) - 1; step > 0; step--) {
    const alpha = step / 100;
    if (reads(alpha)) {
      return [`--highlight-wash: ${rgba(wash, alpha)};`, `--highlight-wash-ring: ${rgba(ring, ring.alpha * alpha / wash.alpha)};`];
    }
  }
  const fills = grounds.map((under) => over(wash, under));
  const [best] = ([[0, 0, 0], [255, 255, 255]] as const)
    .map((candidate) => ({ candidate, ratio: Math.min(...fills.map((fill) => contrastRatio(candidate, fill))) }))
    .sort((a, b) => b.ratio - a.ratio);
  return [
    `--highlight-wash: var(--highlight-wash-on-${side});`,
    `--highlight-wash-ring: var(--highlight-wash-ring-on-${side});`,
    `--highlight-ink: rgb(${best!.candidate.join(' ')});`,
  ];
}

/**
 * Co-project every role's foreground from its resolved background, and the found-word wash from the
 * theme's ground and ink ({@link deriveHighlightWashDecls}), and inject the derived values into the
 * `user-override` layer (topmost), idempotently. Uses a hidden probe so the browser resolves each
 * token (incl. oklch) to an rgb. None of the tokens it reads is one it writes.
 */
export function deriveRoleForegrounds(doc: Document = document): void {
  const probe = doc.createElement('div');
  probe.style.position = 'absolute';
  probe.style.visibility = 'hidden';
  probe.style.pointerEvents = 'none';
  doc.body.appendChild(probe);
  // A 1×1 canvas resolves ANY computed colour function (oklch/lab/color-mix/…) to rgb;
  // getComputedStyle returns `oklch(…)` verbatim in modern engines, which parseColor
  // (rgb/hex only) cannot read — so we paint it and read back the rgb pixel.
  const canvas = doc.createElement('canvas');
  canvas.width = 1;
  canvas.height = 1;
  const ctx = canvas.getContext('2d');
  let decls: string[];
  try {
    decls = deriveOnColorDecls((bgToken) => {
      probe.style.backgroundColor = `var(--${bgToken})`;
      const computed = getComputedStyle(probe).backgroundColor;
      if (!ctx) return computed;
      ctx.fillStyle = '#000';
      ctx.fillStyle = computed; // invalid value leaves fillStyle unchanged (the #000 reset)
      ctx.fillRect(0, 0, 1, 1);
      const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
      return `rgb(${r}, ${g}, ${b})`;
    });
    // The same probe with the opacity kept: the found-word wash and the text over it are translucent.
    decls.push(...deriveHighlightWashDecls((token) => {
      probe.style.backgroundColor = '';
      probe.style.backgroundColor = `var(--${token})`;
      const computed = getComputedStyle(probe).backgroundColor;
      if (!ctx) {
        const rgb = parseColor(computed);
        return rgb ? { rgb, alpha: 1 } : null;
      }
      ctx.clearRect(0, 0, 1, 1);
      ctx.fillStyle = 'rgba(0, 0, 0, 0)';
      ctx.fillStyle = computed;
      ctx.fillRect(0, 0, 1, 1);
      const [r = 0, g = 0, b = 0, a = 0] = ctx.getImageData(0, 0, 1, 1).data;
      return a === 0 ? null : { rgb: [r, g, b], alpha: a / 255 };
    }));
  } finally {
    probe.remove();
  }

  let sheet = foregroundSheets.get(doc);
  if (!sheet) {
    const Sheet = doc.defaultView?.CSSStyleSheet ?? CSSStyleSheet;
    sheet = new Sheet();
    foregroundSheets.set(doc, sheet);
  }
  sheet.replaceSync(decls.length
    ? `@layer user-override {\n  :root {\n    ${decls.join('\n    ')}\n  }\n}`
    : '');
  if (!doc.adoptedStyleSheets.includes(sheet)) {
    doc.adoptedStyleSheets = [...doc.adoptedStyleSheets, sheet];
  }
}
