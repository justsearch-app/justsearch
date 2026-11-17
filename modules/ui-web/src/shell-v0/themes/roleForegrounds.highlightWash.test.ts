// SPDX-License-Identifier: Apache-2.0
/**
 * The found-word pair (first-minutes fm-04/fm-05, decision PA1'): the wash behind a found word is
 * derived from each theme's own ground (--surface-0) and ink (--text-primary), so the word reads at
 * 4.5:1 or more in every palette in Light and Dark and in a user theme. The pure core; the browser
 * check over every built-in palette is FirstMinutes.resultsPane-lockdown.test.ts.
 */
import { describe, expect, it } from 'vitest';
import { deriveHighlightWashDecls, type Rgba } from './roleForegrounds.js';
import { contrastRatio, WCAG_AA } from './contrast.js';

describe('the found-word wash is derived from the theme\'s own ground and ink', () => {
  /** The authored washes (tokens.css), resolved: amber at 22% / 8% for a dark ground, a pale amber at 55% / 20% for a light one. */
  const authored: Record<string, Rgba> = {
    'highlight-wash-on-dark': { rgb: [240, 184, 96], alpha: 0.22 },
    'highlight-wash-ring-on-dark': { rgb: [240, 184, 96], alpha: 0.08 },
    'highlight-wash-on-light': { rgb: [250, 205, 92], alpha: 0.55 },
    'highlight-wash-ring-on-light': { rgb: [250, 205, 92], alpha: 0.2 },
  };
  const theme = (ground: Rgba['rgb'], ink: Rgba) => (token: string): Rgba | null =>
    token === 'surface-0' ? { rgb: ground, alpha: 1 } : token === 'text-primary' ? ink : authored[token] ?? null;
  const value = (decls: string[], name: string): string | undefined =>
    decls.find((decl) => decl.startsWith(`--${name}:`))?.slice(name.length + 3, -1).trim();

  it('keeps the authored wash of the ground\'s side as a reference where the word reads', () => {
    const dark = deriveHighlightWashDecls(theme([10, 10, 12], { rgb: [255, 255, 255], alpha: 0.95 }));
    expect(value(dark, 'highlight-wash')).toBe('var(--highlight-wash-on-dark)');
    expect(value(dark, 'highlight-wash-ring')).toBe('var(--highlight-wash-ring-on-dark)');
    expect(value(dark, 'highlight-ink')).toBeUndefined();
    const light = deriveHighlightWashDecls(theme([255, 255, 255], { rgb: [15, 23, 42], alpha: 0.95 }));
    expect(value(light, 'highlight-wash')).toBe('var(--highlight-wash-on-light)');
  });

  it('chooses the wash by the ground, not the light/dark setting: a dark palette in Light keeps the dark wash', () => {
    // Nord keeps its dark ground and light ink in Light: the light (pale, opaque) wash under light ink read 2.9:1.
    const nord = deriveHighlightWashDecls(theme([46, 52, 64], { rgb: [236, 239, 244], alpha: 0.95 }));
    expect(value(nord, 'highlight-wash')).toBe('var(--highlight-wash-on-dark)');
    // Sepia Focus keeps its cream ground and dark ink in Dark.
    const sepia = deriveHighlightWashDecls(theme([245, 234, 216], { rgb: [50, 30, 20], alpha: 0.95 }));
    expect(value(sepia, 'highlight-wash')).toBe('var(--highlight-wash-on-light)');
  });

  it('lets the wash give way, keeping its hue, where the authored one would sink the word below 4.5:1', () => {
    const ground: Rgba['rgb'] = [100, 100, 100];
    const ink: Rgba = { rgb: [255, 255, 255], alpha: 1 };
    const decls = deriveHighlightWashDecls(theme(ground, ink));
    const wash = value(decls, 'highlight-wash');
    const match = wash?.match(/^rgb\((\d+) (\d+) (\d+) \/ ([\d.]+)%\)$/);
    expect(match, String(wash)).not.toBeNull();
    const [, r, g, b, percent] = match!.map(Number);
    expect([r, g, b]).toEqual([240, 184, 96]);
    expect(percent).toBeGreaterThan(0);
    expect(percent).toBeLessThan(22);
    for (const under of [ground, ground.map((c) => c + (255 - c) * 0.06)]) {
      const fill = under.map((c, i) => [r, g, b][i]! * percent! / 100 + c * (1 - percent! / 100)) as unknown as Rgba['rgb'];
      expect(contrastRatio(fill, ink.rgb)).toBeGreaterThanOrEqual(WCAG_AA);
    }
    expect(value(decls, 'highlight-ink')).toBeUndefined();
  });

  it('gives the word a derived ink only where the theme\'s own text does not read on its own ground', () => {
    const decls = deriveHighlightWashDecls(theme([250, 248, 240], { rgb: [255, 255, 255], alpha: 1 }));
    expect(value(decls, 'highlight-wash')).toBe('var(--highlight-wash-on-light)');
    expect(value(decls, 'highlight-ink')).toBe('rgb(0 0 0)');
  });

  it('derives nothing when the ground or the ink does not resolve', () => {
    expect(deriveHighlightWashDecls(() => null)).toEqual([]);
  });
});
