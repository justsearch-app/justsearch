// SPDX-License-Identifier: Apache-2.0
/**
 * deriveRichLabel — surface title enriched with state content.
 *
 * Produces labels like "Search: rust ownership" by parsing the URL's
 * state parameters and appending the most relevant value to the surface
 * title. Falls back to the bare surface title when no useful state
 * parameter is present.
 *
 * Slice 501: addresses the §2.1 motivating example — tooltips like
 * "Go back to Search: rust ownership" instead of just "Go back to Search".
 */

import { parseUrl } from '../router/parser.js';

const MAX_STATE_CHARS = 30;

// Search Thread S5b — `core.search-surface` is retired (folded into `core.unified-chat-surface`
// below); a historical NavigationJournal entry with the old id falls back to the bare surface title.
const STATE_KEY_BY_SURFACE: Record<string, string> = {
  'core.ask-surface': 'query',
  'core.unified-chat-surface': 'query',
  'core.search-v3-surface': 'query',
};

export function deriveTitleFromSurfaceId(surfaceId: string): string {
  if (!surfaceId) return '';
  const segments = surfaceId.split('.');
  const last = segments[segments.length - 1] ?? '';
  const trimmed = last.endsWith('-surface') ? last.slice(0, -'-surface'.length) : last;
  if (!trimmed) return '';
  return trimmed
    .split('-')
    .filter((s) => s.length > 0)
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}

export function deriveRichLabel(surfaceId: string, url: string, surfaceTitle: string): string {
  // The caller supplies the title from the presentation authority. Deriving it again here created
  // a user-visible shadow label ("Unified Chat") after the catalog renamed the place to "Search".
  const title = surfaceTitle;
  if (!url) return title || surfaceId;

  const stateKey = STATE_KEY_BY_SURFACE[surfaceId];
  if (!stateKey) return title || surfaceId;

  const parsed = parseUrl(url);
  if (!parsed || parsed.kind !== 'navigate') return title || surfaceId;

  const value = parsed.state[stateKey];
  if (typeof value !== 'string' || value.trim().length === 0) return title || surfaceId;

  const truncated = value.length > MAX_STATE_CHARS
    ? value.slice(0, MAX_STATE_CHARS) + '...'
    : value;

  return `${title}: ${truncated}`;
}
