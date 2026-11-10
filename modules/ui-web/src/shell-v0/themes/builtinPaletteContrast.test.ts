/**
 * builtinPaletteContrast — tempdoc 558 (the accessible color-pair, realized for the BUILT-IN palettes).
 *
 * 567 gave CUSTOM themes readable accent text by baking `deriveForeground` results into the saved
 * theme (the Token Editor). The BUILT-IN palettes never got that treatment: they override the accent
 * FILLS (`--accent-*`) but not the on-colors (`--accent-on-*`), so the on-color fell through to the
 * appearance-flipping base values in tokens.css (near-white under `[data-theme="light"]`). A bright
 * palette accent (Nord cyan, High-Vis oklch) + that near-white on-color = ~1.4–4.1:1 white-on-bright —
 * illegible, and nothing caught it (live on main, exactly the 558 #1 prediction).
 *
 * This test gates every built-in palette: for each role in the shared ROLE_CATALOG that a palette
 * overrides a FILL for, the palette MUST declare an `--accent-on-<role>` whose contrast against that
 * fill meets WCAG AA. It REUSES the production contrast authority (`contrast.ts` — `contrastRatio`)
 * and the role catalog (`themeRoles.ts`) — no second math/role copy.
 *
 * Why a per-palette PAIR check covers the whole appearance cross-product: the fix bakes the on-color
 * INTO the palette, and a palette's fill + baked on-color are BOTH invariant across light/dark and
 * ±high-contrast (HC overrides surfaces/text, not `--accent-*`). So the pair's ratio is identical in
 * all four appearance states — gating the pair gates every state. (The DEFAULT no-palette theme's
 * base accents are out of scope here; main's 559 work owns those.)
 *
 * Color resolution: hex/rgb fills go through `contrast.ts`'s `parseColor`. The High-Vis palette
 * authors its fills as `oklch()`; `contrast.ts` intentionally delegates oklch to the browser
 * (`getComputedStyle`), unavailable in a unit test, so we resolve oklch→sRGB here with the standard
 * Oklab matrix. This is a bounded, cross-validated COLOUR-SPACE shim (the WCAG math stays in
 * `contrast.ts`); a browser-resolved Playwright check is the future hardening (tempdoc 558 §11.4).
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, it, expect } from 'vitest';
import { parseColor, contrastRatio, WCAG_AA, type Rgb } from './contrast.js';
import { ROLE_CATALOG } from './themeRoles.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const THEMES_DIR = join(HERE, '../../../public/themes');

/** oklch(L C H) → sRGB [0–255], standard Oklab→linear-sRGB matrix + gamma. CI-only colour-space shim. */
function oklchToRgb(L: number, C: number, H: number): Rgb {
  const hr = (H * Math.PI) / 180;
  const a = C * Math.cos(hr);
  const b = C * Math.sin(hr);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const l = l_ ** 3;
  const m = m_ ** 3;
  const s = s_ ** 3;
  const lin2srgb = (v: number): number => {
    const c = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    return Math.max(0, Math.min(255, Math.round(c * 255)));
  };
  return [
    lin2srgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    lin2srgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    lin2srgb(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ];
}

/**
 * Resolve a token value to [r,g,b]: hex/rgb via contrast.ts; oklch via the local shim. The oklch hue
 * may be a literal number OR `var(--h-<name>)` (the default theme authors accents this way) — resolved
 * from {@link hueSeeds} (the `--h-*` map read from `:root`).
 */
function resolveColor(raw: string, hueSeeds: Record<string, number> = {}): Rgb | null {
  const direct = parseColor(raw);
  if (direct) return direct;
  const m = raw.trim().match(/^oklch\(\s*([\d.]+)%?\s+([\d.]+)\s+(.+?)\s*\)$/i);
  if (m) {
    const L = parseFloat(m[1] as string);
    const hueRaw = (m[3] as string).trim();
    const hueVar = hueRaw.match(/^var\(\s*--(h-[\w-]+)\s*\)$/);
    const H = hueVar ? hueSeeds[hueVar[1] as string] : parseFloat(hueRaw);
    if (H === undefined || Number.isNaN(H)) return null;
    return oklchToRgb(raw.includes('%') ? L / 100 : L, parseFloat(m[2] as string), H);
  }
  return null;
}

/** A built-in palette as a flat token map (bare names, no `--`), parsed from CSS or JSON. */
function loadPaletteTokens(file: string): Record<string, string> {
  const text = readFileSync(join(THEMES_DIR, file), 'utf8');
  if (file.endsWith('.json')) {
    const tree = JSON.parse(text) as { tokens?: Record<string, string> };
    return tree.tokens ?? {};
  }
  const out: Record<string, string> = {};
  for (const m of text.matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) out[m[1] as string] = (m[2] as string).trim();
  return out;
}

const BUILT_IN_PALETTES = readdirSync(THEMES_DIR)
  .filter((file) => /\.(css|json)$/.test(file) && !file.startsWith('manifest.'))
  .sort();

describe('built-in palette accent on-colors meet WCAG AA against their own fills', () => {
  for (const file of BUILT_IN_PALETTES) {
    const tokens = loadPaletteTokens(file);
    describe(file, () => {
      for (const role of ROLE_CATALOG) {
        const fillRaw = tokens[role.bgToken];
        // Only assert roles the palette actually overrides a fill for.
        if (fillRaw === undefined) continue;

        it(`${role.bgToken} → ${role.fgToken} is legible (≥ AA)`, () => {
          const fill = resolveColor(fillRaw);
          expect(fill, `fill ${role.bgToken}="${fillRaw}" must resolve`).not.toBeNull();

          const onRaw = tokens[role.fgToken];
          expect(
            onRaw,
            `${file} sets a fill for --${role.bgToken} but no --${role.fgToken}; the on-color falls ` +
              `through to the appearance-flipping base value and goes illegible in the opposite mode. ` +
              `Bake the derived foreground into the palette (deriveForeground).`,
          ).not.toBeUndefined();

          const on = resolveColor(onRaw as string);
          expect(on, `on-color --${role.fgToken}="${onRaw}" must resolve`).not.toBeNull();

          const ratio = contrastRatio(fill as Rgb, on as Rgb);
          expect(
            ratio,
            `--${role.fgToken} on --${role.bgToken} is ${ratio.toFixed(2)}:1 — below AA ${WCAG_AA}:1`,
          ).toBeGreaterThanOrEqual(WCAG_AA);
        });
      }
    });
  }
});

/** Brace-match the first `<selector> { … }` block; return its inner declaration text. */
function sliceBlock(css: string, selectorRe: RegExp): string {
  const m = selectorRe.exec(css);
  if (!m) return '';
  const start = m.index + m[0].length;
  let depth = 0;
  for (let i = start; i < css.length; i++) {
    if (css[i] === '{') depth++;
    else if (css[i] === '}') {
      if (depth === 0) return css.slice(start, i);
      depth--;
    }
  }
  return '';
}

function parseDecls(block: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of block.matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) out[m[1] as string] = (m[2] as string).trim();
  return out;
}

/** Follow token aliases (including the base focus ring's accent reference), rejecting cycles. */
function resolveTokenColor(
  name: string,
  tokens: Record<string, string>,
  seen: readonly string[] = [],
): Rgb | null {
  if (seen.includes(name)) return null;
  const raw = tokens[name];
  if (raw === undefined) return null;
  const ref = raw.match(/^var\(\s*--([\w-]+)\s*\)$/);
  if (ref) return resolveTokenColor(ref[1] as string, tokens, [...seen, name]);
  const hueSeeds: Record<string, number> = {};
  for (const [key, value] of Object.entries(tokens)) {
    if (key.startsWith('h-')) hueSeeds[key] = parseFloat(value);
  }
  return resolveColor(raw, hueSeeds);
}

describe('built-in palette focus rings contrast with their surrounding surfaces (≥ 3:1)', () => {
  const css = readFileSync(join(HERE, '../../styles/tokens.css'), 'utf8');
  const dark = parseDecls(sliceBlock(css, /:root\s*\{/));
  const light = { ...dark, ...parseDecls(sliceBlock(css, /\[data-theme="light"\]\s*\{/)) };
  const surfaces = Array.from({ length: 5 }, (_, i) => `surface-${i}`);

  for (const file of BUILT_IN_PALETTES) {
    const palette = loadPaletteTokens(file);
    const ownSurfaces = surfaces.filter((name) => palette[name] !== undefined);
    // Palette surfaces do not flip with appearance. With none, check the inherited base surfaces.
    const surroundingSurfaces = ownSurfaces.length > 0 ? ownSurfaces : surfaces;
    for (const [mode, base] of [['dark', dark], ['light', light]] as const) {
      const tokens = { ...base, ...palette };
      for (const surfaceName of surroundingSurfaces) {
        it(`${file}: focus ring on --${surfaceName} in ${mode} (≥ 3:1)`, () => {
          // The base ring aliases --accent-tint, so an unset ring uses the palette's accent first.
          const ring = resolveTokenColor('focus-ring-color', tokens);
          const surface = resolveTokenColor(surfaceName, tokens);
          expect(ring, `${file}: --focus-ring-color must resolve`).not.toBeNull();
          expect(surface, `${file}: --${surfaceName} must resolve`).not.toBeNull();

          const ratio = contrastRatio(ring as Rgb, surface as Rgb);
          expect(
            ratio,
            `${file} (${mode}): focus ring on --${surfaceName} is ${ratio.toFixed(2)}:1 — below 3:1`,
          ).toBeGreaterThanOrEqual(3);
        });
      }
    }
  }
});

/**
 * The DEFAULT (no-palette) theme is mode-VARIANT: `tokens.css` `:root` carries the dark accents +
 * on-colors (and the `--h-*` hue seeds), and `[data-theme="light"]` overrides them for light. Unlike a
 * palette (where the baked on-color is mode-invariant), the default theme's pair must be checked in BOTH
 * modes. The pair is high-contrast-INVARIANT (`.high-contrast` overrides surfaces/text, not `--accent-*`),
 * so two modes suffice. main's 559 hand-tuned these; this locks that fix against silent regression and
 * closes the same coverage gap (an ungated color-pair) that let the palette defect ship. (oklch + the
 * `var(--h-*)` hue ref are resolved by the shim; a browser-faithful Playwright check is future hardening.)
 */
describe('default (no-palette) theme accent on-colors meet WCAG AA in both modes', () => {
  const css = readFileSync(join(HERE, '../../styles/tokens.css'), 'utf8');
  const dark = parseDecls(sliceBlock(css, /:root\s*\{/));
  const light = parseDecls(sliceBlock(css, /\[data-theme="light"\]\s*\{/));
  const hueSeeds: Record<string, number> = {};
  for (const [k, v] of Object.entries(dark)) if (k.startsWith('h-')) hueSeeds[k] = parseFloat(v);

  for (const role of ROLE_CATALOG) {
    for (const [mode, decls] of [['dark', dark], ['light', light]] as const) {
      it(`${role.bgToken} → ${role.fgToken} is legible in ${mode} (≥ AA)`, () => {
        const fillRaw = decls[role.bgToken] ?? dark[role.bgToken];
        const onRaw = decls[role.fgToken] ?? dark[role.fgToken];
        expect(fillRaw, `default ${mode}: --${role.bgToken} must be defined`).not.toBeUndefined();
        expect(onRaw, `default ${mode}: --${role.fgToken} must be defined`).not.toBeUndefined();

        const fill = resolveColor(fillRaw as string, hueSeeds);
        const on = resolveColor(onRaw as string, hueSeeds);
        expect(fill, `fill --${role.bgToken}="${fillRaw}" must resolve`).not.toBeNull();
        expect(on, `on --${role.fgToken}="${onRaw}" must resolve`).not.toBeNull();

        const ratio = contrastRatio(fill as Rgb, on as Rgb);
        expect(
          ratio,
          `default ${mode}: --${role.fgToken} on --${role.bgToken} is ${ratio.toFixed(2)}:1 — below AA`,
        ).toBeGreaterThanOrEqual(WCAG_AA);
      });
    }
  }
});
