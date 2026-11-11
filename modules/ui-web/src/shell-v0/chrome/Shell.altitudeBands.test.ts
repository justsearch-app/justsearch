// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { clampReorderToAltitudeBands } from '../layout/frameAreas.js';
import type { Surface } from '../../api/types/surface.js';

/** Minimal Surface fixture — only id + altitude drive the cross-altitude move-ban clamp. */
function surface(id: string, altitude?: string): Surface {
  return {
    id,
    presentation: { labelKey: 'x', descriptionKey: 'y' },
    audience: 'USER',
    placement: 'RAIL',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag: `jf-${id}`,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    ...(altitude ? { altitude } : {}),
  } as unknown as Surface;
}

const ids = (ss: Surface[]): string[] => ss.map((s) => s.id);

describe('clampReorderToAltitudeBands (tempdoc 571 §4b — the cross-altitude move-ban)', () => {
  it('re-groups a band-interleaving reorder so DIAGNOSTIC surfaces cannot enter the product region', () => {
    // A user reorder that interleaves a DIAGNOSTIC surface (logs) between product surfaces.
    const reordered = [
      surface('core.unified-chat-surface'), // PRODUCT
      surface('core.logs-surface', 'DIAGNOSTIC'),
      surface('core.search-surface'), // PRODUCT
      surface('core.activity-surface', 'TRUST'),
      surface('core.health-surface', 'DIAGNOSTIC'),
    ];
    const clamped = clampReorderToAltitudeBands(reordered);
    // PRODUCT + TRUST stay in the product region (rank 0), DIAGNOSTIC sinks below — no surface crosses
    // its band. Within each band, the user's relative order is preserved (stable sort).
    expect(ids(clamped)).toEqual([
      'core.unified-chat-surface',
      'core.search-surface',
      'core.activity-surface',
      'core.logs-surface',
      'core.health-surface',
    ]);
  });

  it('preserves the within-product-region order of a reorder (PRODUCT + TRUST share the band)', () => {
    const reordered = [
      surface('core.activity-surface', 'TRUST'),
      surface('core.search-surface'), // PRODUCT
      surface('core.unified-chat-surface'), // PRODUCT
    ];
    const clamped = clampReorderToAltitudeBands(reordered);
    // No DIAGNOSTIC/TOOL here → all rank 0 → order is unchanged (a within-band reorder is honored).
    expect(ids(clamped)).toEqual([
      'core.activity-surface',
      'core.search-surface',
      'core.unified-chat-surface',
    ]);
  });

  it('sinks TOOL (headless) below the diagnostics region', () => {
    const reordered = [
      surface('core.agent-tool', 'TOOL'),
      surface('core.logs-surface', 'DIAGNOSTIC'),
      surface('core.search-surface'), // PRODUCT
    ];
    const clamped = clampReorderToAltitudeBands(reordered);
    expect(ids(clamped)).toEqual([
      'core.search-surface',
      'core.logs-surface',
      'core.agent-tool',
    ]);
  });
});
