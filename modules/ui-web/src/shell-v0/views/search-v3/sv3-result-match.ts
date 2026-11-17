// SPDX-License-Identifier: Apache-2.0
/**
 * Where the details pane opens a search result: at the passage the result's row shows, so the reader
 * sees the place that matched instead of the file's beginning.
 *
 * The position is taken from the result list in memory at the moment the result is opened, and
 * nothing of it is stored: the shared selection keeps its shape, and a pane opened from anywhere else
 * (Library, Browse, another window, a restored conversation, a reload) has no passage and opens as it
 * always has. The passage is a HINT: the reader confirms its text at its offsets and, when it cannot,
 * opens at the file's beginning without saying anything (`DocumentPane.match`).
 */
import type { DocumentMatchAnchor } from '../../components/documentPane/DocumentPane.js';
import type { SearchHit } from '../../state/searchState.js';

/**
 * The hit's first returned passage — the one its row shows, the first with any text — or `null` when
 * it has none, or when that passage's offsets are not a span of the document (missing, not whole
 * numbers, negative, empty or inverted). A bad first passage is not replaced by a later one: the
 * pane would then open at a place the row does not show.
 */
export function sv3ResultMatch(hit: Pick<SearchHit, 'excerptRegions'>): DocumentMatchAnchor | null {
  const regions = Array.isArray(hit.excerptRegions) ? hit.excerptRegions : [];
  const region = regions.find(candidate => typeof candidate?.text === 'string' && candidate.text.trim() !== '');
  if (region === undefined) return null;
  const { startChar, endChar, text } = region;
  if (typeof startChar !== 'number' || typeof endChar !== 'number') return null;
  if (!Number.isSafeInteger(startChar) || !Number.isSafeInteger(endChar) || startChar < 0 || endChar <= startChar) return null;
  return { startChar, endChar, excerpt: text ?? '' };
}

/**
 * The passage the pane keeps when a result is opened: opening the file the pane already shows at a
 * passage leaves that passage in place, so choosing the same result again — or the same file after a
 * newer answer — does not move what the reader is looking at. Anything else takes the result's own.
 */
export function sv3NextPaneMatch(
  open: { readonly docPath: string | null; readonly match: DocumentMatchAnchor | null },
  docPath: string,
  match: DocumentMatchAnchor | null,
): DocumentMatchAnchor | null {
  return open.docPath === docPath && open.match !== null ? open.match : match;
}
