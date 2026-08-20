// SPDX-License-Identifier: Apache-2.0
/**
 * Pure citation data shapes for the chat / RAG surfaces.
 *
 * Extracted from `CitationsPanel.ts` to break the import cycle
 * (tempdoc 530 UI-cycle gate): `evidenceProjection.ts` needs the
 * `RetrievalCitation` shape, but importing it from `CitationsPanel.ts`
 * (which imports `evidenceProjection.ts` for its projection helpers)
 * formed a cycle. These are plain data interfaces with no dependency
 * on the renderer/component graph, so they live in this leaf and both
 * sides import from here.
 */

/**
 * A grounded claim — the RAG path's per-sentence accumulation model (sentence + score + the source
 * indices it grounds to). Tempdoc 565 §15.B relocated this from the retired `StreamingTextBlock`; it
 * is the internal RAG model that `UnifiedChatView` builds from `rag.citation_matches` and then maps
 * into the one `Citation` render shape (`MarkdownBlock`). A leaf data type, no renderer dependency.
 *
 * Tempdoc 822 §3d (the score-scale mismatch) — the single `score` field is GONE, split by producer.
 * Two events feed this model and they do not measure the same quantity: `rag.citation_matches`
 * carries a cross-encoder relevance probability, `rag.citation_delta` carries the streaming lexical
 * matcher's word-overlap coverage ratio (`hits / significantWords`, whose denominator is the
 * passage's vocabulary size). `Math.max`-ing them into one number fed word overlap into thresholds
 * calibrated on the cross-encoder cutoff — a 2-of-4-word passage read "grounded". Keeping them
 * apart is the gate: only {@link verifiedScore} may reach a grounding tier.
 */
export interface Claim {
  sentenceIndex: number;
  sentenceText: string;
  /**
   * The cross-encoder similarity from `rag.citation_matches` — the ONLY score a grounding tier may
   * be computed from. `null` means no authoritative matcher verified this sentence, and such a claim
   * mints no citation, no mark, no underline, and no grounded/weak count.
   */
  verifiedScore: number | null;
  /**
   * The streaming lexical matcher's word-overlap ratio from `rag.citation_delta`. Kept because it is
   * what arrived, never because it is comparable: it is not on the cross-encoder scale and no
   * monotone mapping onto it exists. Diagnostic only — never a tier input.
   */
  lexicalScore: number;
  /**
   * Tempdoc 822 §3b — the source positions the AUTHORITATIVE matcher (`rag.citation_matches`) tied
   * this sentence to. The ONLY set a mark may resolve through: deltas arrive first, so a single
   * merged ref list made the first ref of any doubly-matched sentence the lexical one.
   */
  verifiedRefs: number[];
  /**
   * The source positions the streaming lexical matcher (`rag.citation_delta`) guessed. Kept because
   * it is what arrived — never a resolution source, exactly as {@link lexicalScore} is never a tier
   * input. Same producer, same standing.
   */
  lexicalRefs: number[];
  /**
   * Tempdoc 836 §4 — which producer wrote {@link verifiedScore}: `CROSS_ENCODER` |
   * `EMBEDDING_COSINE` | `NONE`. Carried on the claim so the resolver can enforce the producer gate
   * where a claim becomes a `Citation`, not only where the claim was built. Absent on a claim from
   * a record persisted before the field existed.
   */
  scorer?: string;
}

/**
 * Citation match (mirrors `CitationMatch` in streams.ts).
 *
 * <p>Tempdoc 822 §3b — `sourceIndex` (renamed from `chunkIndex`) is the matched source's POSITION in
 * this turn's `rag.citations` array, which is what the `[n]` labels and the sources panel index by.
 * A chunk's ordinal inside its parent document is a different fact and never travels on a match.
 */
export interface CitationMatch {
  sentenceIndex: number;
  sentenceText: string;
  sourceIndex: number;
  similarity: number;
  parentDocId: string;
  excerpt?: string;
  /**
   * Tempdoc 836 §4 — which TEXT was scored: `SUPPLIED` (the literal passage the caller showed the
   * model) or `CHUNK_LOOKUP` (text re-fetched by chunk identity). Optional: absent on a record
   * persisted before the field existed.
   */
  textSource?: string;
}

/**
 * Tempdoc 836 S2S3-A.1 — how much of ONE source's text the matcher actually looked at.
 *
 * <p>Admission control preserves SENTENCE coverage by cutting WINDOWS, so "every sentence scored"
 * can be true while most of a source's text was never read. `windowsConsidered > 0 &&
 * windowsScored === 0` is the discriminator: that source was NEVER EXAMINED, which is a budget
 * fact, not the evidence verdict "this source supports nothing". Both produce the same empty match
 * list, and without this they are indistinguishable.
 */
export interface SourceCoverage {
  sourceIndex: number;
  windowsConsidered: number;
  windowsScored: number;
}

/**
 * Tempdoc 849 §5.1 — whether the passage a citation names actually REACHED the model, as opposed
 * to merely having been retrieved.
 *
 * <p>Every retrieved passage gets a citation; the head's token budget then decides how much of that
 * set the prompt could hold. `dropped` is a source the model never saw, `partial` one it saw with
 * its tail cut. The mirror of Java `DocumentService.ContextInclusion.State`, minus its `ABSENT`
 * member: absence is expressed by the field being missing, never by a fourth string.
 *
 * <p>Orthogonal to {@link SourceExamination} (836), which answers whether the MATCHER scored the
 * source — one pipeline stage later. Like it, this is a BUDGET fact: it never feeds a grounding
 * tier, a grounding count, or a relevance score.
 */
export type ContextInclusion = 'included' | 'partial' | 'dropped';

/**
 * Tempdoc 859 §5b — a source an ANSWER stands on, on either plane.
 *
 * <p>Two producers supply evidence sources and they do not carry the same facts. The RAG plane's
 * `rag.citations` reports a retrieval passage in full (char span, chunk total, retrieval score,
 * heading level). The delegate plane's `AgentEvent.AgentSource` reports a chunk-identified local
 * passage and NONE of those five — the agent search hits carry chunk identity and lines, and the
 * matcher re-fetches by that identity rather than by span.
 *
 * <p>So the shared fields are REQUIRED here and the retrieval-only ones are OPTIONAL, with
 * {@link RetrievalCitation} re-requiring them for its own plane. The alternative — widening
 * `RetrievalCitation` and zero-filling the agent plane — fabricates retrieval facts into a panel
 * that groups and grades by them (849's forbidden class). The same reasoning the backend's own
 * `ContextCitation` doc encodes: *"0 is not 'unknown' — it is a claim that the text is the
 * document's FIRST chunk"* (`DocumentService.java:268-273`).
 *
 * <p>ABSENT therefore means "this producer does not report that", and every reader of an optional
 * field must state its absence answer rather than defaulting one.
 */
export interface AnswerEvidenceSource {
  parentDocId: string;
  chunkIndex: number;
  excerpt: string;
  startLine: number;
  endLine: number;
  headingText: string;
  /** Retrieval-only: how many chunks the parent document has. */
  chunkTotal?: number;
  /**
   * Retrieval-only: the passage's character span in the parent document. Absent on the delegate
   * plane, which never reports one — and MUST NOT be zero-filled to make a join work: `(0, 0)` on
   * every source of a document resolves a followed citation to that document's FIRST passage, which
   * is exactly the wrong-target deep link 822 §3b removed.
   */
  startChar?: number;
  endChar?: number;
  /** Retrieval-only: the retriever's own score. Never a grounding tier input. */
  score?: number;
  /** Retrieval-only: the markdown heading depth of {@link headingText}. */
  headingLevel?: number;
  /**
   * Tempdoc 849 — ABSENT ⇒ the producer said nothing about inclusion, and the reader says nothing.
   * Not "included": every turn persisted before 849 is absent, and describing those retroactively
   * is exactly the fabrication this field exists to remove.
   */
  contextInclusion?: ContextInclusion;
  /** Characters of this passage that reached the model. Absent together with `contextInclusion`. */
  contextIncludedChars?: number;
}

/**
 * Retrieval-time citation from rag.citations event — {@link AnswerEvidenceSource} with the five
 * retrieval facts REQUIRED, because this plane's producer always reports them.
 */
export interface RetrievalCitation extends AnswerEvidenceSource {
  chunkTotal: number;
  startChar: number;
  endChar: number;
  score: number;
  headingLevel: number;
}

/** Emitted on citation click for navigate-to-source. */
export interface CitationSelectDetail {
  parentDocId: string;
  startLine: number;
  endLine: number;
  /**
   * Tempdoc 859 §5b — ABSENT when the source that was followed reports no character span (the
   * delegate plane never does). `sv3CitationAnchor` already answers that case: it refuses a
   * non-finite span and the pane shows the document while highlighting nothing, rather than
   * anchoring on a span nobody reported. Never zero-filled — `(0, 0)` would claim the document's
   * opening characters.
   */
  startChar?: number;
  endChar?: number;
  /** Tempdoc 526 §14.5 T2 — excerpt for G21 kind-flip into a typed citation selection. */
  excerpt: string;
}
