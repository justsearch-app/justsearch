// SPDX-License-Identifier: Apache-2.0
/**
 * surfaceIcons — shared mapping from surfaceId to IconName.
 *
 * Extracted from Shell.ts; the top bar's More menu resolves its window icons here (shell slice 2).
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
