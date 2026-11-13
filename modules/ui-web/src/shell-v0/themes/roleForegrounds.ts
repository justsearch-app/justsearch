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

import { parseColor, deriveTintedForeground } from './contrast.js';
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

/**
 * Co-project every role's foreground from its resolved background and inject the
 * derived on-colours into the `user-override` layer (topmost), idempotently. Uses a
 * hidden probe so the browser resolves the role background var (incl. oklch) to an rgb.
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
