// SPDX-License-Identifier: Apache-2.0
/**
 * The frame's places (shell slice 2), shared by the top bar and Settings › Layout so the two can
 * never list them differently.
 */
import type { Surface } from '../../api/types/surface.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';
import { getLayout } from './LayoutManifest.js';

/** The switcher's areas in their fixed order; Ctrl+1/2/3 follow it. */
export const FRAME_AREAS: ReadonlyArray<{ readonly id: string; readonly shortcut: string }> = [
  { id: 'core.search-v3-surface', shortcut: 'Ctrl+1' },
  { id: 'core.library-surface', shortcut: 'Ctrl+2' },
  { id: 'core.brain-surface', shortcut: 'Ctrl+3' },
];

/** Windows with a fixed row of their own (System in the More menu): never an area, never listed. */
export const FRAME_FIXED_WINDOWS: ReadonlySet<string> = new Set(['core.system-surface', 'core.settings-surface']);

/**
 * The windows the frame lists, shared by the frame and the palette: rail placement, not a host's
 * member, never Settings (it has one fixed affordance), the active layout's rail zone, user
 * visibility, then user order clamped to altitude bands.
 */
export function frameWindows(
  all: readonly Surface[],
  config: RendererUserConfig,
): Surface[] {
  const members = new Set(all.flatMap(surface => surface.members ?? []));
  const layout = config.activeLayoutId ? getLayout(config.activeLayoutId) : undefined;
  const allowed = layout?.zones?.rail?.surfaces;
  const eligible = all.filter(surface => surface.placement === 'RAIL' && !members.has(surface.id)
    && surface.id !== 'core.settings-surface' && config.surfaceVisibility?.[surface.id] !== false
    && (!allowed?.length || allowed.includes(surface.id)));
  const order = config.surfaceOrder ?? [];
  const ordered = eligible.sort((a, b) => {
    const left = order.indexOf(a.id), right = order.indexOf(b.id);
    return (left < 0 ? Infinity : left) - (right < 0 ? Infinity : right);
  });
  return order.length ? clampReorderToAltitudeBands(ordered) : ordered;
}
// Tempdoc 571 §4b — altitude-band rank for the cross-altitude move-ban. PRODUCT + TRUST share the
// product region; DIAGNOSTIC
// sinks to the diagnostics region; TOOL (headless) last. A user reorder is clamped to keep each
// surface in its own band, so a saved surfaceOrder cannot drag a DIAGNOSTIC surface into the product
// region (or vice versa) — altitude, not user drag, owns which band a surface lives in.
function altitudeBandRank(altitude: string | undefined): number {
  switch (altitude) {
    case 'DIAGNOSTIC':
      return 1;
    case 'TOOL':
      return 2;
    default:
      return 0; // PRODUCT + TRUST
  }
}

/**
 * Clamp a user-reordered surface list to within-altitude bands (tempdoc 571 §4b). A STABLE sort by
 * {@link altitudeBandRank} preserves the user's within-band order but re-groups surfaces by band, so a
 * reorder can only permute within an altitude — never move a surface across one.
 */
export function clampReorderToAltitudeBands(
  surfaces: Surface[],
): Surface[] {
  return [...surfaces].sort((a, b) => altitudeBandRank(a.altitude) - altitudeBandRank(b.altitude));
}
