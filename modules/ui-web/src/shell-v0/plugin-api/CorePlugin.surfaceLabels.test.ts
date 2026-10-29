// @vitest-environment happy-dom
/**
 * Tempdoc 557 (Q10 root cause) — surface-label single authority.
 *
 * Every core surface's labelKey/descriptionKey MUST point at the
 * `registry-surface.<id-without-core.>.{label,description}` message catalog
 * (the documented convention in registry-surface.en.properties). The earlier
 * `surface.<x>.label` keys had NO backing message, so every core surface label
 * silently fell back to deriveTitleFromSurfaceId — an id-derivation shadow
 * authority. This test pins the convention so it can't drift back.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { createCorePluginManifest } from './CorePlugin.js';
import { present } from '../display/present.js';
import { getSurface, mergePluginSurfaceContributions } from '../../api/registry/SurfaceCatalogClient.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import {
  __seedForTest as __seedResourceCatalog,
  __resetForTest as __resetResourceCatalog,
} from '../../i18n/resourceCatalog.js';

const coreSurfaces = createCorePluginManifest().capabilities.surfaces ?? [];

describe('CorePlugin surface labels — single authority (557 Q10)', () => {
  beforeEach(() => __resetResourceCatalog());

  it('every core surface labelKey/descriptionKey follows the registry-surface convention', () => {
    expect(coreSurfaces.length).toBeGreaterThan(0);
    for (const s of coreSurfaces) {
      const stem = s.id.replace(/^core\./, '');
      expect(s.labelKey, `labelKey for ${s.id}`).toBe(`registry-surface.${stem}.label`);
      expect(s.descriptionKey, `descriptionKey for ${s.id}`).toBe(`registry-surface.${stem}.description`);
      // The previous (buggy) `surface.<x>.label` namespace must never return.
      expect(s.labelKey.startsWith('surface.')).toBe(false);
    }
  });

  it('present() resolves a core surface label from the seeded catalog, not id-derivation', () => {
    // Seed the registry-surface catalog as bootSurfaceCatalog would at runtime.
    __seedResourceCatalog({
      'registry-surface.unified-chat-surface.label': 'Search',
      'registry-surface.brain-surface.label': 'AI Brain',
    });
    mergePluginSurfaceContributions([
      {
        pluginId: 'core',
        contribution: {
          id: 'core.unified-chat-surface',
          mountTag: 'jf-sv3-window',
          labelKey: 'registry-surface.unified-chat-surface.label',
          descriptionKey: 'registry-surface.unified-chat-surface.description',
          audience: 'USER',
          placement: 'RAIL',
        },
        effectiveAudience: 'USER',
        provenance: CORE_PROVENANCE,
      },
    ]);
    expect(getSurface('core.unified-chat-surface')?.presentation.labelKey).toBe(
      'registry-surface.unified-chat-surface.label',
    );
    // "Search" comes from the catalog — NOT the derived "Unified Chat".
    expect(present({ kind: 'surface', id: 'core.unified-chat-surface' }).label).toBe('Search');
    expect(present({ kind: 'surface', id: 'core.unified-chat-surface' }).label).not.toBe('Unified Chat');
  });

  it('923 F-22 — keeps the canonical Search name when the message catalog is unavailable', () => {
    mergePluginSurfaceContributions([
      {
        pluginId: 'core',
        contribution: {
          id: 'core.unified-chat-surface',
          mountTag: 'jf-sv3-window',
          labelKey: 'registry-surface.unified-chat-surface.label',
          descriptionKey: 'registry-surface.unified-chat-surface.description',
          audience: 'USER',
          placement: 'RAIL',
        },
        effectiveAudience: 'USER',
        provenance: CORE_PROVENANCE,
      },
    ]);

    expect(present({ kind: 'surface', id: 'core.unified-chat-surface' }).label).toBe('Search');
  });

  it('557 Q7 — a navigate effect to a surface route uses the catalog label, not the raw route', () => {
    __seedResourceCatalog({ 'registry-surface.unified-chat-surface.label': 'Search' });
    mergePluginSurfaceContributions([
      {
        pluginId: 'core',
        contribution: {
          id: 'core.unified-chat-surface',
          mountTag: 'jf-sv3-window',
          labelKey: 'registry-surface.unified-chat-surface.label',
          descriptionKey: 'registry-surface.unified-chat-surface.description',
          audience: 'USER',
          placement: 'RAIL',
        },
        effectiveAudience: 'USER',
        provenance: CORE_PROVENANCE,
      },
    ]);
    const label = present({
      kind: 'effect',
      effect: { kind: 'navigate', to: 'justsearch://surface/core.unified-chat-surface' },
    }).label;
    expect(label).toBe('Navigate to Search');
    expect(label).not.toContain('justsearch://');
    expect(label).not.toContain('Unified Chat');
  });
});
