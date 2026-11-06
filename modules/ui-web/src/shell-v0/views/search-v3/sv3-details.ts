// SPDX-License-Identifier: Apache-2.0
/**
 * What Search's details pane offers for an answer (design system §7): a tab per cited source, numbered
 * as the answer numbers it, plus Context. Pure: the window holds the turn and the selection.
 */
import { filenameOf } from '../../components/chat/evidenceProjection.js';
import { sourceKey } from '../../state/selectedSource.js';
import type { DetailsTab } from '../../components/detailsPane/DetailsPane.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';
import type { Sv3Turn } from './sv3-sessions.js';

export const SV3_PANE_CONTEXT_TAB = 'context';
/** A document that is not one of a turn's sources: a search result, or a plugin's selection. */
export const SV3_PANE_DOCUMENT_TAB = 'document';
const SOURCE_TAB_PREFIX = 'source:';

export const sv3SourceTab = (sourceIndex: number): string => `${SOURCE_TAB_PREFIX}${sourceIndex}`;

/** The source index a tab names, or null when the tab is not a source's. */
export function sv3TabSourceIndex(tab: string): number | null {
  if (!tab.startsWith(SOURCE_TAB_PREFIX)) return null;
  const index = Number(tab.slice(SOURCE_TAB_PREFIX.length));
  return Number.isInteger(index) && index >= 0 ? index : null;
}

/** The number the answer's mark gives each source it cites, keyed by source index. */
function citedNumbers(turn: Sv3Turn): Map<number, string> {
  const sources = turn.evidence?.sources ?? [];
  const numbers = new Map<number, string>();
  for (const mark of turn.evidence?.marks ?? []) {
    const index = sources.findIndex((source) =>
      sourceKey(source.parentDocId, source.startLine) === sourceKey(mark.detail.parentDocId, mark.detail.startLine));
    if (index >= 0 && !numbers.has(index)) numbers.set(index, String(mark.label));
  }
  return numbers;
}

/**
 * One tab per cited source, in the answer's order, plus the source on show when the reader opened one
 * the answer did not cite. A turn with no marks falls back to the sources a sentence matched.
 */
export function sv3SourceTabs(turn: Sv3Turn | null, openSourceIndex: number | null): DetailsTab[] {
  if (turn === null) return [];
  const sources = turn.evidence?.sources ?? [];
  const numbers = citedNumbers(turn);
  const cited = new Set(numbers.keys());
  if (cited.size === 0) for (const match of turn.evidence?.matches ?? []) if (match.sourceIndex >= 0) cited.add(match.sourceIndex);
  if (openSourceIndex !== null) cited.add(openSourceIndex);
  return [...cited]
    .filter((index) => sources[index] !== undefined)
    .sort((a, b) => Number(numbers.get(a) ?? a + 1) - Number(numbers.get(b) ?? b + 1))
    .map((index) => {
      const source = sources[index]!;
      const name = filenameOf(source.parentDocId);
      return { id: sv3SourceTab(index), label: name, title: source.parentDocId, number: numbers.get(index) ?? String(index + 1) };
    });
}

/** The selection a source tab stands for, as the citation that would have opened it. */
export function sv3SourceSelection(turn: Sv3Turn | null, sourceIndex: number): CitationSelectDetail | null {
  const source = turn?.evidence?.sources[sourceIndex];
  if (source === undefined) return null;
  return {
    parentDocId: source.parentDocId,
    startLine: source.startLine,
    endLine: source.endLine,
    ...(source.startChar !== undefined ? { startChar: source.startChar } : {}),
    ...(source.endChar !== undefined ? { endChar: source.endChar } : {}),
    excerpt: source.excerpt,
  };
}
