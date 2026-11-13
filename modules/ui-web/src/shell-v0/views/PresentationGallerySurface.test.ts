// @vitest-environment happy-dom

/**
 * Skin-gallery swatch rendering.
 *
 * Sandbox round 6 — every card rendered a bare `<div class="swatch">` styled
 * `background: var(--accent-primary)`, an alias of the document-scoped `--accent-tint` that
 * applying a skin overwrites globally. So all nine swatches showed whichever skin was ACTIVE and
 * the gallery could preview nothing. The swatch is now bound per card from the declaration's own
 * `theme.tokens['accent-tint']`.
 */

import { describe, expect, it } from 'vitest';
import './PresentationGallerySurface.js';
import { BUILTIN_PRESENTATIONS } from '../themes/builtinPresentations.js';

interface GalleryHost extends HTMLElement {
  updateComplete: Promise<boolean>;
}

/** Mount the gallery and return one `background:` value per rendered card, keyed by declaration id. */
async function swatchBackgroundsById(): Promise<Map<string, string>> {
  const el = document.createElement('jf-presentation-gallery-surface') as GalleryHost;
  document.body.appendChild(el);
  await el.updateComplete;
  const out = new Map<string, string>();
  for (const card of Array.from(el.shadowRoot?.querySelectorAll('.card') ?? [])) {
    const id = card.getAttribute('data-presentation-id') ?? '';
    // Custom properties retain the declared colour verbatim, including oklch(...).
    const swatch = card.querySelector<HTMLElement>('.swatch');
    out.set(id, swatch?.style.getPropertyValue('--swatch-tint').trim() ?? '');
  }
  document.body.removeChild(el);
  return out;
}

describe('PresentationGallerySurface swatches (round 6 — every card previewed the ACTIVE skin)', () => {
  it('renders each declared accent-tint on its OWN card', async () => {
    const byId = await swatchBackgroundsById();
    const declared = BUILTIN_PRESENTATIONS.filter(
      (d) => d.theme?.tokens['accent-tint'] !== undefined,
    );
    // Guard the fixture itself: if the built-ins ever stop declaring accents, the loop below
    // would pass vacuously.
    expect(declared.length).toBeGreaterThan(0);
    for (const decl of declared) {
      expect(byId.get(decl.id)).toBe(decl.theme?.tokens['accent-tint']);
    }
  });

  it('gives distinct built-in variations DISTINCT swatches — the round-6 defect made them identical', async () => {
    const byId = await swatchBackgroundsById();
    const violet = byId.get('builtin.core-violet');
    const amber = byId.get('builtin.core-amber');
    expect(violet).toBeTruthy();
    expect(amber).toBeTruthy();
    expect(violet).not.toBe(amber);
  });

  it('gives the three theme-less built-ins a defined, non-transparent fallback fill', async () => {
    const byId = await swatchBackgroundsById();
    const themeless = BUILTIN_PRESENTATIONS.filter((d) => d.theme === undefined);
    expect(themeless.map((d) => d.id).sort()).toEqual([
      'builtin.core-declared',
      'builtin.settings-declared',
      'builtin.three-surface-spike',
    ]);
    for (const decl of themeless) {
      const fill = byId.get(decl.id);
      expect(fill).toBeTruthy();
      expect(fill).not.toBe('transparent');
      // It must be a real colour, not an echo of the document's live accent token — echoing
      // `var(--accent-tint)` is the round-6 defect in fallback clothing.
      expect(fill).not.toContain('--accent-tint');
      expect(fill).not.toContain('--accent-primary');
    }
  });

  it('never emits a swatch without a background', async () => {
    const byId = await swatchBackgroundsById();
    expect(byId.size).toBe(BUILTIN_PRESENTATIONS.length);
    for (const [, fill] of byId) expect(fill).not.toBe('');
  });
});
