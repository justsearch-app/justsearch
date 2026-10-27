// SPDX-License-Identifier: Apache-2.0
/**
 * surfaceIcons — shared mapping from surfaceId to IconName.
 *
 * Extracted from Shell.ts so both the Rail (via Shell) and
 * BookmarksPopover can resolve icons for a given surface.
 */

import type { IconName } from '../components/Icon.js';

const SURFACE_ICONS: Record<string, IconName> = {
  // Search Thread S5b — the standalone `core.search-surface` is retired (folded into
  // `core.search-v3-surface` below); a historical NavigationJournal entry recorded with the old
  // id falls back to the generic 'hard-drive' icon, matching the established pattern for other
  // retired surface ids (core.agent-surface, core.system-self-view) — none get a dedicated entry.
  'core.library-surface': 'folder-plus',
  'core.browse-surface': 'folder-tree',
  'core.brain-surface': 'cpu',
  'core.ask-surface': 'database',
  'core.free-chat-surface': 'layers',
  'core.extract-surface': 'list',
  'core.search-v3-surface': 'message-square',
  'core.health-surface': 'server',
  'core.help-surface': 'help-circle',
  'core.settings-surface': 'settings',
  'core.logs-surface': 'file-text',
  'core.activity-surface': 'history',
};

export function surfaceIcon(surfaceId: string): IconName {
  return SURFACE_ICONS[surfaceId] ?? 'hard-drive';
}

/**
 * Tempdoc 602 R8 — per-surface rail accessible-name override.
 *
 * The rail buttons are icon-only, so the glyph + accessible name ARE the
 * affordance. The Library entry's `folder-plus` glyph reads as a generic
 * "new/add" rather than "manage what's indexed" (593 §1 R8). For surfaces
 * whose glyph under-specifies intent, the icon-only rail control names the
 * intent instead of the bare surface label. Sibling of {@link SURFACE_ICONS}
 * (per-surface chrome metadata); empty for surfaces whose label already reads.
 */
const RAIL_INTENT_HINT: Record<string, string> = {
  'core.library-surface': 'Manage indexed folders',
};

/** Rail control's accessible name: the intent hint where one exists, else the surface label. */
export function railAccessibleName(surfaceId: string, label: string): string {
  return RAIL_INTENT_HINT[surfaceId] ?? label;
}

export { SURFACE_ICONS, RAIL_INTENT_HINT };
