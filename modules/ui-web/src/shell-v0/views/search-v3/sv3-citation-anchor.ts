// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 849 §3-§4 — how a followed citation becomes something the reading pane can anchor on.
 *
 * Two callers, one authority, because the resolution happens TWICE and the two answers must agree:
 * once when the reader follows the citation (`Sv3Main`), and again when `rag.citation_matches`
 * arrives afterwards for a source whose pane is already open (`SearchV3View`). `rag.citations` is
 * emitted at retrieval time and the matches only after the answer streams, so the second pass is
 * the common case, not a repair.
 *
 * What each tier means (§4):
 *
 *  - The **chunk** is the citation's own `startChar`/`endChar` — the passage the retriever selected.
 *  - The **matched sentence** is the answer sentence a claim match tied to this source, which the
 *    reader then locates INSIDE that chunk. No claim match ⇒ no sentence, and the pane tints the
 *    chunk without inventing one.
 */
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';
import {
  citationHeader,
  sourceGrounding,
  type CitationHeader,
} from '../../components/chat/evidenceProjection.js';
// The ONE source-identity authority (tempdoc 822 §5.4) — the same import `MarkdownBlock.ts` and
// `CitationsPanel.ts` take, because a second key function here would silently never agree with them.
import { sourceKey } from '../../state/selectedSource.js';
import type { Sv3Turn } from './sv3-sessions.js';

/** The source is not part of this turn's retrieval set (or the turn is gone). */
export const SV3_SOURCE_INDEX_ABSENT = -1;

/**
 * Which source in `turn.evidence.sources` a followed citation is, matched on the identity the whole
 * citation system already keys by: `sourceKey(parentDocId, startLine)`. Position in the array IS the
 * `sourceIndex` a `CitationMatch` links by (`components/chat/citationTypes.ts`), so this lookup is
 * what joins the two events.
 *
 * <p>Tempdoc 859 §5c — this used to join on the char span, which is a fact only the RETRIEVAL plane
 * reports. A delegate turn's sources carry none, so `undefined === 0` was `false` for every agent
 * mark: the mark rendered, the reader clicked, and the source pane resolved nothing. TypeScript
 * reported nothing, because comparing `number | undefined` to `number` is legal — the defect
 * compiles clean, which is why the join moved rather than being patched.
 *
 * <p>The tempting repair is the trap, and is forbidden: zero-filling `startChar`/`endChar` on the
 * delegate plane makes every one of a document's sources `(0, 0)`, so `findIndex` returns that
 * document's FIRST source — resurrecting exactly the wrong-target deep link 822 §3b removed.
 * `sourceKey` is instead the ONE identity `MarkdownBlock.makeMarker` keys its marks on and
 * `CitationsPanel` keys its cards on, "so the card and the inline `[n]` resolve to one identity and
 * the two surfaces cannot silently disagree" — it works on both planes, and it removes a third
 * identity vocabulary from this window rather than adding one. Char spans remain what
 * {@link sv3CitationAnchor} highlights with, which is a different question.
 */
export function sv3SourceIndex(turn: Sv3Turn | null, detail: CitationSelectDetail): number {
  const sources = turn?.evidence?.sources;
  if (!sources) return SV3_SOURCE_INDEX_ABSENT;
  const wanted = sourceKey(detail.parentDocId, detail.startLine);
  return sources.findIndex((source) => sourceKey(source.parentDocId, source.startLine) === wanted);
}

/**
 * The answer sentence a claim match tied to this source, or `null` when none did. The FIRST match is
 * taken when several sentences ground to the same source: the pane emphasises one span, and the
 * alternative — emphasising the union — would tint text between two matched sentences that nothing
 * matched.
 */
export function sv3MatchedSentence(turn: Sv3Turn | null, sourceIndex: number): string | null {
  if (sourceIndex === SV3_SOURCE_INDEX_ABSENT) return null;
  const match = turn?.evidence?.matches.find((candidate) => candidate.sourceIndex === sourceIndex);
  const sentence = match?.sentenceText ?? '';
  return sentence.length > 0 ? sentence : null;
}

/**
 * The citation's character anchor, or `null` when it carried no usable span — in which case the pane
 * shows the document and highlights nothing, rather than falling back to the line numbers the
 * producer derived. Those are 1-based and this reader is 0-based, and re-admitting them here would
 * re-admit exactly the off-by-one the char anchor removes.
 */
export function sv3CitationAnchor(
  detail: CitationSelectDetail,
  sentenceText: string | null,
): DocumentCitationAnchor | null {
  const { startChar, endChar } = detail;
  // Tempdoc 859 §5b — ABSENT joins the same refusal an unusable span already got: a followed
  // delegate citation reports no char span, and `Number.isFinite(undefined)` is false, so the pane
  // shows the document and highlights nothing. The `typeof` guards are what narrow the optional for
  // the compiler; they add no case the predicate did not already answer.
  if (typeof startChar !== 'number' || typeof endChar !== 'number') return null;
  if (!Number.isFinite(startChar) || !Number.isFinite(endChar) || endChar <= startChar) return null;
  return { startChar, endChar, excerpt: detail.excerpt ?? '', sentenceText };
}

/**
 * Tempdoc 849 §7 — the followed citation's HEADER, joined here and worded in `evidenceProjection.ts`.
 *
 * <p>This is what slice 2's deviation bought. The event carries IDENTITY (`turnId` + `sourceIndex`)
 * and nothing else, so the header reads `chunkIndex`/`chunkTotal`/`score`/`contextInclusion` off the
 * ONE citation record the turn already holds. Copying them onto the event would have minted a second
 * `RetrievalCitation` in flight — the fork `governance/execution-surfaces.v1.json` exists to prevent,
 * and the same projection-not-fork rule §5.3 applied on the backend.
 *
 * <p>`spanUnusable` is derived from the anchor rather than passed in: {@link sv3CitationAnchor} is
 * already the one authority on what a usable span is, and a second predicate here could disagree
 * with it about the citation the pane is currently showing.
 */
export function sv3CitationHeader(
  turn: Sv3Turn | null,
  sourceIndex: number,
  anchor: DocumentCitationAnchor | null,
): CitationHeader | null {
  const evidence = turn?.evidence ?? null;
  const citation =
    evidence !== null && sourceIndex !== SV3_SOURCE_INDEX_ABSENT
      ? (evidence.sources[sourceIndex] ?? null)
      : null;
  return citationHeader({
    citation,
    // The matcher's own join, not a second one. `sourceCoverage` is NOT part of `Sv3TurnEvidence`
    // (the window never carried it), so the examination state stays the established binary — a
    // producer that said nothing about coverage does not get "unexamined" assumed on its behalf.
    grounding:
      citation === null
        ? null
        : sourceGrounding(sourceIndex, evidence?.matches ?? [], citation.parentDocId),
    question: turn?.question ?? null,
    spanUnusable: anchor === null,
  });
}
