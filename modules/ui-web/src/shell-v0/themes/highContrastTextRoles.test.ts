/**
 * highContrastTextRoles — tempdoc 853 (F-07), the high-contrast half of the text-grade ramp.
 *
 * The 2026-08-19 UX audit found the sharp version of a contrast failure: a user who explicitly asked
 * for HIGH CONTRAST was served 4.40:1 body text. The mechanism is a closure gap, not a bad colour —
 * the two `.high-contrast` blocks in `styles/tokens.css` redeclare `--text-primary`,
 * `--text-secondary` and `--text-tertiary` as opaque hex, but NOT `--text-muted`, which therefore
 * kept the base palette's `rgba(var(--p-text), 0.55)` and composited that alpha over an OPAQUE HC
 * surface. Measured: 6.27:1 hc-dark, 4.40:1 hc-light (below the AA floor entirely).
 *
 * This gates the closure rather than one component's use of it: HC must redeclare every text grade,
 * each grade must clear AAA (7:1) against its own palette's HC surface — AA is the floor for the base
 * palettes; a palette whose entire purpose is contrast is held to the higher one — and the grades
 * must stay ORDERED, so `muted` still reads as dimmer than `tertiary` after the opaque override.
 *
 * `check-contrast-matrix.mjs` cannot see this: it parses `:root` and `[data-theme="light"]` only,
 * which is precisely why the gap survived to an audit. Reuses the production contrast authority
 * (`contrast.ts`) — no second copy of the WCAG maths.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseColor, contrastRatio, WCAG_AAA, WCAG_AA, type Rgb } from './contrast.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const TOKENS_CSS = join(HERE, '../../styles/tokens.css');

/** Read one selector's custom properties. Token blocks contain no nested braces. */
function parseBlock(css: string, selector: string): Record<string, string> {
  const at = css.indexOf(selector);
  if (at < 0) throw new Error(`tokens.css no longer contains the selector \`${selector}\``);
  const open = css.indexOf('{', at);
  const close = css.indexOf('}', open);
  const out: Record<string, string> = {};
  for (const line of css.slice(open + 1, close).split('\n')) {
    const d = line.match(/^\s*--([\w-]+)\s*:\s*([^;]+);/);
    if (d) out[d[1] as string] = (d[2] as string).trim();
  }
  return out;
}

const css = readFileSync(TOKENS_CSS, 'utf8');

/** The two HC palettes, each with the darkest/lightest surface a grade actually lands on. */
const PALETTES = [
  {
    name: 'hc-dark',
    block: parseBlock(css, '.high-contrast {'),
    // `--glass-surface: #000000` is the HC panel; `--glass-surface-hover: #111111` is the same panel
    // under the pointer. Both are real backgrounds for the same text, so both are held to the floor.
    surfaces: ['#000000', '#111111'],
  },
  {
    name: 'hc-light',
    block: parseBlock(css, '[data-theme="light"].high-contrast,'),
    // `--glass-surface: #ffffff`, and `--surface-1: #f5f5f5` — the reasoning block's own family.
    surfaces: ['#ffffff', '#f5f5f5', '#eeeeee'],
  },
] as const;

/** Dimmest last. The ordering assertion below walks this in order. */
const GRADES = ['--text-primary', '--text-secondary', '--text-tertiary', '--text-muted'] as const;

function rgb(value: string | undefined, what: string): Rgb {
  const parsed = value ? parseColor(value) : null;
  if (!parsed) throw new Error(`${what} is not an opaque colour literal: ${String(value)}`);
  return parsed;
}

describe('high-contrast text grades — tokens.css closure', () => {
  for (const palette of PALETTES) {
    describe(palette.name, () => {
      it('redeclares every text grade (the F-07 gap: --text-muted was missing)', () => {
        for (const grade of GRADES) {
          expect(palette.block[grade.slice(2)], `${palette.name} must redeclare ${grade}`).toBeTruthy();
        }
      });

      it('declares each grade as an opaque literal, not an alpha over an opaque surface', () => {
        for (const grade of GRADES) {
          const raw = palette.block[grade.slice(2)] as string;
          // The exact shape of the bug: `rgba(var(--p-text), 0.55)` composited over `#ffffff`.
          expect(raw, `${palette.name}/${grade}`).not.toMatch(/rgba|var\(/);
          expect(parseColor(raw), `${palette.name}/${grade}`).toBeTruthy();
        }
      });

      it(`clears WCAG AAA (${WCAG_AAA}:1) on every HC surface`, () => {
        for (const grade of GRADES) {
          const fg = rgb(palette.block[grade.slice(2)], `${palette.name}/${grade}`);
          for (const surface of palette.surfaces) {
            const ratio = contrastRatio(fg, rgb(surface, surface));
            expect(
              ratio,
              `${palette.name}/${grade} on ${surface} = ${ratio.toFixed(2)}:1`,
            ).toBeGreaterThanOrEqual(WCAG_AAA);
          }
        }
      });

      it('keeps the grades ordered — muted stays dimmer than tertiary, and so on', () => {
        const primarySurface = palette.surfaces[0] as string;
        const bg = rgb(primarySurface, primarySurface);
        const ratios = GRADES.map((g) => contrastRatio(rgb(palette.block[g.slice(2)], g), bg));
        for (let i = 1; i < ratios.length; i += 1) {
          expect(
            ratios[i] as number,
            `${palette.name}: ${GRADES[i]} (${(ratios[i] as number).toFixed(2)}) must not out-contrast ` +
              `${GRADES[i - 1]} (${(ratios[i - 1] as number).toFixed(2)})`,
          ).toBeLessThan(ratios[i - 1] as number);
        }
      });
    });
  }
});

// Ordinary light metadata uses an alpha grade. Validate its actual composited contrast on
// every declared elevation, preserving the dimmer-than-tertiary hierarchy.
describe('ordinary light muted text', () => {
  it('clears AA across all light elevations without outshining tertiary', () => {
    const light = parseBlock(css, '[data-theme="light"] {');
    const base = rgb(`rgb(${light['p-text']})`, 'light text base');
    const opacity = (grade: string): number => {
      const match = light[grade]?.match(/rgba\(var\(--p-text\),\s*([\d.]+)\)/);
      if (!match) throw new Error(`Unrecognized light text grade: ${grade}`);
      return Number(match[1]);
    };
    const muted = opacity('text-muted');
    const tertiary = opacity('text-tertiary');
    expect(muted).toBeLessThan(tertiary);
    for (let level = 0; level <= 4; level += 1) {
      const bg = rgb(light[`surface-${level}`], `light surface-${level}`);
      const blend = (i: 0 | 1 | 2): number => Math.round(base[i] * muted + bg[i] * (1 - muted));
      const fg: Rgb = [blend(0), blend(1), blend(2)];
      expect(contrastRatio(fg, bg), `light muted on surface-${level}`).toBeGreaterThanOrEqual(WCAG_AA);
    }
  });
});
