// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 859 §3 — the ONE delegate-plane evidence projection: a `done` event's grounding record
 * (`AgentSource[]` + `AgentSentenceCite[]` + the producer stamp) → the same three-part evidence
 * record the RAG plane produces (sources + matches + inline marks).
 *
 * <p>Three callers, one function, because the projection happens THREE times and the three answers
 * must agree: the live Search v3 terminal (`SearchV3View.concludeRun`), the Search v3 record reader
 * (`sv3-record.recordEvidenceOf`), and the legacy window (`UnifiedChatView`). Before this module
 * each of them either dropped the evidence or re-derived part of it, which is how a delegate turn
 * could show a source count the backend never reported.
 *
 * <p>It is a PROJECTION, not a carrier: it derives every field of its output from the wire record,
 * so it is registered as such in `governance/execution-surfaces.v1.json`.
 */
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';
import type { AnswerEvidenceSource, CitationMatch } from './citationTypes.js';
import type { Citation } from './MarkdownBlock.js';
import { agentSentenceOrdinals, resolveAnswerCitations } from './citationResolve.js';

/** What one delegate answer stood on — the same three parts `Sv3TurnEvidence` carries. */
export interface AgentAnswerEvidence {
  readonly sources: readonly AnswerEvidenceSource[];
  readonly matches: readonly CitationMatch[];
  readonly marks: readonly Citation[];
}

/**
 * The delegate plane's `AgentSource` → the shared {@link AnswerEvidenceSource}.
 *
 * <p>Tempdoc 859 §5b — the five retrieval-only fields (`chunkTotal`, `startChar`, `endChar`,
 * `score`, `headingLevel`) are LEFT ABSENT, never zero-filled. This producer does not report them,
 * and the panel groups and grades by them: `score: 0` is a "low relevance" verdict about a number
 * nobody produced, and `startChar: 0` is a claim about the document's opening characters that the
 * citation-anchor join would then act on.
 */
function toAnswerEvidenceSource(source: AgentSource): AnswerEvidenceSource {
  return {
    parentDocId: source.parentDocId,
    chunkIndex: source.chunkIndex,
    excerpt: source.excerpt,
    startLine: source.startLine,
    endLine: source.endLine,
    headingText: source.headingText,
  };
}

/**
 * The delegate plane's per-sentence cites → the shared `CitationMatch[]`.
 *
 * <p>Tempdoc 859 §3a — these are PROJECTED, never left empty. `sourceGrounding` counts the matches
 * whose `sourceIndex` equals a source's position; with an empty list every source lands `cited:
 * false` and the panel renders **"Retrieved · not cited"** — a verdict, on every source the matcher
 * demonstrably DID cite. The matcher reported; throwing the report away and letting the panel assert
 * the opposite is not honesty, it is a confident wrong answer.
 *
 * <p>A cite whose `sourceIndex` addresses no source mints NO match — the same fail-closed rule the
 * mark follows (`citationResolve.resolveAnswerCitations`), and the rule `sourceGrounding`'s own
 * `parentDocId` correctness guard needs an answer for.
 */
function toCitationMatches(
  sources: readonly AgentSource[],
  cites: readonly AgentSentenceCite[],
): CitationMatch[] {
  const out: CitationMatch[] = [];
  for (const { cite, sentenceIndex } of agentSentenceOrdinals(cites)) {
    const source = sources[cite.sourceIndex];
    if (!source) continue;
    out.push({
      sentenceText: cite.sentenceText,
      sourceIndex: cite.sourceIndex,
      similarity: cite.similarity,
      sentenceIndex,
      parentDocId: source.parentDocId,
      // Tempdoc 859 §2 — STATED, not left absent. `AgentCitationResolver` calls the 3-arg
      // `matchCitations` overload, which supplies a blank `literalText`, so the Worker re-fetches
      // each chunk by `(parentDocId, chunkIndex)` and scores against re-fetched chunk text — never
      // the literal excerpt the model saw. Absent would mean "a record older than the field", which
      // is a different claim.
      textSource: 'CHUNK_LOOKUP',
    });
  }
  return out;
}

/**
 * Project a delegate run's grounding record into the window's one evidence shape.
 *
 * @param scorer which producer wrote the similarities (`DocumentService.ScorerKind`'s wire name), or
 *   `null`/`undefined` for a record persisted before the stamp existed. Passed straight to the 836
 *   §4 producer gate inside {@link resolveAnswerCitations}: a known non-cross-encoder producer mints
 *   no marks (the sources still stand), while an absent stamp keeps the pre-stamp allowance.
 */
export function agentAnswerEvidence(
  sources: readonly AgentSource[],
  cites: readonly AgentSentenceCite[],
  scorer: string | null | undefined,
): AgentAnswerEvidence {
  return {
    sources: sources.map(toAnswerEvidenceSource),
    matches: toCitationMatches(sources, cites),
    marks: resolveAnswerCitations(sources, cites, scorer),
  };
}
