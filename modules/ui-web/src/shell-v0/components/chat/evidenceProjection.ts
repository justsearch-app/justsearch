// SPDX-License-Identifier: Apache-2.0
/**
 * Evidence projection — tempdoc 559 Authority IV (evidence as a registered projection).
 *
 * THE typed projection of a retrieval-evidence record (`RetrievalCitation` ←
 * Java `ContextCitation`, the RAG-retrieval sibling of `SearchTrace` in the
 * execution-surface register, governance/execution-surfaces.v1.json) into the
 * shape the citation UI renders. Before 559 the citation panel read record
 * fields ad-hoc and showed a bare "100%" — an unlabeled backend scalar with no
 * declared meaning. This collapses that: the panel renders an {@link EvidenceItem},
 * and the score is a {@link EvidenceScore} that *carries its metric's meaning*
 * (a declared label — "Relevance" — not a naked percentage). Score math, tier
 * thresholds, and filename derivation live here once, so the render site cannot
 * drift its own thresholds.
 *
 * Scope (559 §5, recorded decision): `EvidenceScore` is RETRIEVAL-evidence-only by
 * design. The search-RANKING side has no user-facing per-result relevance score —
 * `SearchHit.score` is fetched but never rendered, and the ranking "why"
 * (`searchTraceExplain.ts`) shows pipeline-stage timings/status, not a relevance
 * metric. Projecting `EvidenceScore` onto it would misrepresent stage signals as a
 * relevance score (over-DRY). The authority SHARED across ranking + RAG is the
 * execution-surface register (sibling evidence records), not a shared FE score.
 *
 * Honest limit (559 §5): the excerpt's clean sentence/word boundary is owned at
 * the PRODUCER (the worker that mints `ContextCitation`); the FE never
 * re-windows. The mid-word clip is a cross-process (Worker/Java) follow-up — see
 * docs/observations.md — not an FE deliverable. This projection carries the
 * boundary-aware fields (startLine/endLine/headingText) verbatim for navigation.
 */
import type {
  AnswerEvidenceSource,
  CitationMatch,
  SourceCoverage,
  ContextInclusion,
} from './citationTypes.js';
import type { CoreInteractionShapeId } from '../../plugin-api/coreInteractionShapes.js';

/**
 * Tempdoc 577 §2.12 Move 3 — the epistemic answer FRAME authority.
 *
 * <p>The free-chat fabricated-citations defect (§2.11 #4) is the absence of provenance as a
 * first-class facet: an ungrounded model answer rendered in the IDENTICAL bubble as a grounded one,
 * letting the LLM borrow the index's credibility (Goal 3 constraint 3, here implemented
 * window-internally ahead of unification). The frame combines a shape's DECLARED grounding class
 * with the run's ACTUAL grounding outcome, so every answer is framed by what it can support.
 *
 * <p>The declared class is a per-shape PRESENTATION property (how to frame trust), so it lives here
 * as the one presentation authority — exhaustively over the {@link CoreInteractionShapeId} union
 * (the compiler's totality check IS the "every shape declares" guarantee; a new shape that omits a
 * class fails to compile).
 */
export type AnswerGroundingClass = 'grounded-index' | 'ungrounded-llm' | 'transform';

/** THE shape → declared grounding class map (exhaustive; the single declaration site). */
export function declaredGroundingClass(shapeId: CoreInteractionShapeId): AnswerGroundingClass {
  switch (shapeId) {
    case 'core.rag-ask':
    case 'core.agent-run':
    // A workflow run grounds in its tool outputs (agent-path render); treat as index-grounded.
    case 'core.workflow-run':
      return 'grounded-index';
    case 'core.free-chat':
      return 'ungrounded-llm';
    case 'core.extract':
      // A structured transform of given input — not a grounded claim, not a bare opinion.
      return 'transform';
  }
}

/**
 * The answer's epistemic frame: the declared class refined by the actual grounding outcome. An
 * index-grounded shape whose run produced NO citable sources is honestly `ungrounded` (the agent
 * zero-sources case §2.9 V1); one with partial coverage is `partially-grounded`. Ungrounded /
 * transform shapes carry their class regardless of (absent) evidence.
 *
 * <p>Tempdoc 603 D-4 — the `sourced` frame: the answer drew on retrieved DOCUMENTS but per-sentence
 * grounding was NOT verified (the sources are document-level — no chunk identity — so the answer↔source
 * matcher could not run; e.g. the agent's main BM25/keyword pipeline under BLOCKED_LEGACY). It is a
 * PEER of `grounded`, not a degraded form: provenance is real, sentence-precision is absent. This is the
 * honest middle the binary (grounded ↔ ungrounded) lacked — it is why a doc-level answer must NOT read
 * "Grounded · 0 of N" (the over-confidence) NOR "found nothing to cite" (the over-conservatism, 603 D-1).
 *
 * <p>Tempdoc 720 — `sourced` also covers a SETTLED, CHUNK-PRECISE answer whose matcher tied NO sentence
 * to a passage (zero cites). Mid-stream that is "marks pending ⇒ grounded"; but once the run has finished
 * (`settled`) the matcher has run and matched nothing, so provenance-without-verification is the honest
 * frame — the same "Grounded · 0 of N" over-confidence, previously reachable only via this settled path.
 */
export type AnswerFrame = 'grounded' | 'partially-grounded' | 'sourced' | 'ungrounded' | 'transform';

/**
 * Tempdoc 603 D-4 — the document-level sentinel (mirror of {@code AgentSession.DOC_LEVEL_SENTINEL}): a
 * source whose {@code chunkIndex} is this value is DOCUMENT-LEVEL (provenance only — no chunk identity,
 * no precise line). Any other value (a real `>= 0` ordinal, or `undefined` for a source type that does
 * not carry a chunk index — e.g. a chunk-native RAG `RetrievalCitation`) is treated as chunk-precise.
 */
export const DOC_LEVEL_CHUNK_SENTINEL = -1;

/**
 * Tempdoc 603 D-4 — are the grounding sources CHUNK-PRECISE (line-precise, matcher-eligible) or only
 * DOCUMENT-LEVEL (provenance)? TRUE unless EVERY source is the document-level sentinel — so a chunk-native
 * RAG source list (no sentinel) and any list with at least one real chunk are chunk-precise, while an
 * all-document-level agent list (every {@code chunkIndex === -1}) is not. An empty list is vacuously not
 * chunk-precise. The predicate lives in the authority (not the view) so the SOURCED↔GROUNDED distinction is
 * derived in ONE place.
 */
export function sourcesAreChunkPrecise(
  sources: ReadonlyArray<{ readonly chunkIndex?: number }>,
): boolean {
  return sources.some((s) => s.chunkIndex !== DOC_LEVEL_CHUNK_SENTINEL);
}

export function answerFrame(
  shapeId: CoreInteractionShapeId,
  sourceCount: number,
  coverage: GroundingCoverage,
  // Tempdoc 603 D-4 — whether the attached sources are chunk-precise (matcher-eligible) or document-level
  // (provenance only). Defaults TRUE so every existing caller (the Documents/RAG tier, always chunk-native)
  // keeps its behavior; the agent tier passes the real predicate so doc-level sources frame as `sourced`.
  chunkPrecise = true,
  // Tempdoc 720 — has the run FINISHED (no more citation-matches coming)? Defaults FALSE so a mid-stream
  // render still treats a zero-cite chunk-precise answer as "marks pending ⇒ grounded". A SETTLED render
  // can no longer make that excuse: the matcher has run and matched nothing, so it is
  // provenance-without-verification (`sourced`), never "grounded" over zero cited sentences.
  settled = false,
): AnswerFrame {
  const declared = declaredGroundingClass(shapeId);
  if (declared === 'transform') return 'transform';
  if (declared === 'ungrounded-llm') return 'ungrounded';
  // grounded-index: refine by the actual outcome.
  // No citable sources at all ⇒ honestly ungrounded (the agent zero-sources case, §2.9 V1).
  if (sourceCount === 0) return 'ungrounded';
  // Sources attached but SOME sentences cite and others do not ⇒ partially grounded.
  if (coverage.cited > 0 && coverage.cited < coverage.total) return 'partially-grounded';
  if (coverage.cited === 0) {
    // Tempdoc 603 D-4 — DOCUMENT-LEVEL sources: the matcher can NEVER run (no chunk identity), so
    // cited===0 is provenance, not "pending" — the `sourced` frame regardless of settle state.
    if (!chunkPrecise) return 'sourced';
    // Tempdoc 720 — CHUNK-PRECISE, cited===0: mid-stream this is "marks pending ⇒ grounded"; but once
    // SETTLED the matcher has finished and tied no sentence to a passage — provenance without
    // per-sentence verification (`sourced`), never the self-contradictory "Grounded · 0 of N".
    return settled ? 'sourced' : 'grounded';
  }
  return 'grounded';
}

/**
 * Tempdoc 577 §2.16 — the precise grounding-degraded flag: TRUE when the shape was DECLARED to
 * ground in the index (a grounded-index shape: agent / RAG) yet the run produced zero citable
 * sources — i.e. it SEARCHED but found nothing above the match threshold. This is distinct from an
 * ungrounded-llm shape (free-chat), which never searches at all. Both render the `ungrounded` frame;
 * this flag lets the "Why uncited?" disclosure say WHICH happened — "searched but uncitable" vs
 * "this mode doesn't search" — instead of one ambiguous "not grounded" for two different facts.
 */
export function groundingDegraded(
  shapeId: CoreInteractionShapeId,
  sourceCount: number,
): boolean {
  return declaredGroundingClass(shapeId) === 'grounded-index' && sourceCount === 0;
}

/**
 * The one-line trust label for a frame (the answer's epistemic header). `null` for a fully-grounded
 * answer — the inline marks + the grounding badge already carry it, so no banner is added.
 *
 * <p>Tempdoc 603 C3 — a `transform` (extraction) is NO LONGER silent. The Structured tier does not
 * retrieve from the index (no RAGContext), so its values are the model's own structuring, not sourced
 * data — yet clean schema-shaped JSON reads as authoritative to a skimming user (593 ADD2: extracted
 * `120` vs the real `5s`). The marker makes that honest and unmissable, rendered prominently on the
 * artifact (see `.answer-frame-transform`), superseding the prior "transform is its own legible form".
 *
 * <p>Tempdoc 577 §2.16 — the `ungrounded` label is refined by {@link groundingDegraded}: a shape
 * that SEARCHED but found nothing to cite is honest about having tried, distinct from a mode that
 * never searches. Callers that lack the (shapeId × sourceCount) inputs pass `degraded=false` for the
 * legacy "model answer" wording.
 */
export function answerFrameLabel(frame: AnswerFrame, degraded = false): string | null {
  switch (frame) {
    case 'grounded':
      return null;
    case 'transform':
      return 'Model-generated structure — not retrieved from your documents';
    case 'partially-grounded':
      return 'Partly grounded — some statements are not backed by your documents';
    case 'sourced':
      // Tempdoc 603 D-4 — provenance without per-sentence verification: the answer drew on these
      // documents, but the matcher could not tie each statement to a passage (document-level retrieval).
      return 'Based on your documents — per-sentence grounding not verified';
    case 'ungrounded':
      return degraded
        ? 'Searched your documents but found nothing to cite — treat this as the model’s own answer'
        : 'Model answer — this mode does not search your documents';
  }
}

/** The declared meaning of a retrieval-evidence score (not a bare scalar). */
export interface EvidenceScore {
  /** Clamped 0..1 value. */
  readonly value: number;
  /** Rounded 0..100 for display. */
  readonly pct: number;
  /** Confidence tier — the branded {@link GroundingTier} (tempdoc 565 §15.D.1 seam). */
  readonly tier: GroundingTier;
  /** The metric's MEANING — e.g. "Relevance". The fix for the unlabeled "100%". */
  readonly label: string;
}

/** Source location for navigate-to-source (carried verbatim from the record). */
export interface EvidenceLocation {
  readonly parentDocId: string;
  readonly startLine: number;
  readonly endLine: number;
  /** Tempdoc 859 §5b — absent when the producer reports no character span (the delegate plane). */
  readonly startChar?: number;
  readonly endChar?: number;
}

/** The view-model the citation UI projects from a retrieval-evidence record. */
export interface EvidenceItem {
  readonly docId: string;
  readonly filename: string;
  /**
   * Tempdoc 859 §5b — `null` when the producer reported no score. The delegate plane's
   * `AgentSource` deliberately carries none (it is uncalibrated, 559), and `evidenceScore(0)` would
   * project that absence as a real "low" tier — a grade over a number nobody produced.
   */
  readonly score: EvidenceScore | null;
  readonly excerpt: string;
  readonly headingText: string;
  readonly location: EvidenceLocation;
  /**
   * Tempdoc 849 §5.1 — whether this passage reached the model, when the producer resolved it.
   * `null` is ABSENCE, not a state: a surface must render nothing for it, exactly as
   * {@link CoverageHonesty} returns `null` when the producer said nothing.
   *
   * <p>Containment (same rule {@link SourceExamination} carries): a budget fact never feeds a
   * grounding tier, a grounding count, or {@link EvidenceScore}.
   */
  readonly inclusion: ContextInclusion | null;
}

/** Default declared metric for retrieval evidence — the relevance of the chunk. */
export const RELEVANCE_METRIC = 'Relevance';

function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v));
}

/**
 * The ONE grounding-tier threshold authority (tempdoc 565 §15.A). Private by
 * design: no renderer may hold these literals — the only way to a tier is
 * {@link evidenceTier}, and the only way to a grounding presentation is
 * {@link groundingClass} / {@link groundingLabel}, both derived from it.
 *
 * Before §15 the score→tier mapping was forked across FOUR sites with TWO
 * threshold sets (this file 0.7/0.3; MarkdownBlock + StreamingTextBlock
 * `groundingStatus` 0.5/0.2; CitationHoverCard `scoreLabel` 0.5/0.2) for ONE
 * answer↔source similarity — proven a single scorer on a single [0,1] scale
 * (§15.G: `AgentCitationResolver`→same `matchCitations`→`CitationMatchOps`). The
 * displayed-tier divergence (a 0.6 read "grounded" inline but "medium" in the
 * rail) was DRIFT, not calibration. The numeric pair stays an evidence-backed
 * calibration (one knob here); the UNIFICATION is the structural fix.
 *
 * §15.C-fix coherence pass: the tiers are now ANCHORED TO THE MATCHER CUTOFF
 * (`DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD = 0.5`). Every *cited*
 * sentence has similarity ≥ 0.5, so `TIER_MEDIUM = 0.5` (= the cutoff) makes a
 * cited sentence read at least "weak" and never "ungrounded" (which is reserved
 * for below-cutoff, i.e. UNcited prose); `TIER_HIGH = 0.6` sits just above the
 * cutoff so a real cross-encoder match reads "grounded". The previous 0.7/0.3
 * was inherited from the pre-§15 retrieval-only use and left cited sentences in
 * [0.5,0.7) reading "weak". The EXACT high bar is still the §15.A residual
 * (the production cross-encoder distribution); this anchoring is the coherent
 * default until that calibration lands.
 */
const TIER_HIGH = 0.6;
const TIER_MEDIUM = 0.5;

/**
 * The grounding tier — tempdoc 565 §15.D.1 typed seam. BRANDED (the `present.ts` `DisplayLabel`
 * idiom) so it is only constructible by {@link evidenceTier}: a renderer cannot fabricate a tier and
 * pass it where a tier is expected; it must obtain it from this authority. Paired with the
 * `groundingSemantics` register-gate (which forbids a re-derivation `score >= 0.X ? 'grounded'`
 * outside this file), the tier leaf is unforkable by construction, not by convention.
 */
export type GroundingTier = ('high' | 'medium' | 'low') & { readonly __groundingTier: unique symbol };

/** The actual threshold logic — internal, unbranded, so this file can switch on the raw tier. */
function computeTier(value: number): 'high' | 'medium' | 'low' {
  const s = clamp01(value);
  if (s >= TIER_HIGH) return 'high';
  if (s >= TIER_MEDIUM) return 'medium';
  return 'low';
}

/** The single tier-threshold authority — the ONLY mint of a {@link GroundingTier}. */
export function evidenceTier(value: number): GroundingTier {
  return computeTier(value) as GroundingTier;
}

/** Grouping vocabulary for the rail/panel (the CitationsPanel "High confidence / Supporting / Weak"). */
export function tierGroup(tier: GroundingTier): 'high' | 'supporting' | 'weak' {
  switch (tier as 'high' | 'medium' | 'low') {
    case 'high':
      return 'high';
    case 'medium':
      return 'supporting';
    default:
      return 'weak';
  }
}

/** The CSS-class stem for a tier (so the raw tier never leaks into a template as an authority bypass). */
export function tierClass(tier: GroundingTier): 'high' | 'medium' | 'low' {
  return tier as 'high' | 'medium' | 'low';
}

/**
 * The grounding CSS-class stem for an inline citation mark — the ONE replacement
 * for `MarkdownBlock.groundingStatus` / `StreamingTextBlock.groundingStatus`
 * (tempdoc 565 §15.A). Derived from {@link evidenceTier} so the inline marks and
 * the rail/chips classify one similarity identically.
 */
export function groundingClass(value: number): 'grounded' | 'weak' | 'ungrounded' {
  switch (computeTier(value)) {
    case 'high':
      return 'grounded';
    case 'medium':
      return 'weak';
    default:
      return 'ungrounded';
  }
}

/**
 * The grounding human label for a hover card — the ONE replacement for
 * `CitationHoverCard`'s inline `score >= 0.5 ? 'strong' : …` (tempdoc 565 §15.A).
 * Same tier authority as {@link groundingClass}; only the words differ.
 */
export function groundingLabel(value: number): 'strong' | 'moderate' | 'weak' {
  switch (computeTier(value)) {
    case 'high':
      return 'strong';
    case 'medium':
      return 'moderate';
    default:
      return 'weak';
  }
}

/**
 * Tempdoc 565 §14 ④/⑤ — the grounding-HONESTY read: "is this answer grounded, and how much of it?"
 * The §14/§15.A design dissolves the two backlog items (④ a grounding-readiness signal, ⑤ an
 * "N of M sentences grounded" coverage indicator) into ONE read of the grounding verdict over the
 * answer's per-sentence cites — NOT a new signal bolted on. `cited` are the sentences the matcher
 * grounded (similarity ≥ the cutoff, so each classifies `grounded` or `weak` via {@link groundingClass});
 * `total` is the answer's sentence count (M). `ready` is ④ (the answer carries grounding at all).
 */
export interface GroundingCoverage {
  /** ④ — the answer is grounded (at least one sentence is backed by a source). */
  readonly ready: boolean;
  /** Cited sentences classified `grounded` (similarity ≥ the high bar). */
  readonly grounded: number;
  /** Cited sentences classified `weak` (above the cutoff, below the high bar). */
  readonly weak: number;
  /** Sentences carrying any citation (`grounded` + `weak`) — the N in "N of M". */
  readonly cited: number;
  /** ⑤ — the answer's total sentence count (M). Always ≥ `cited`. */
  readonly total: number;
  /** A human one-liner: "Grounded · 3 of 5 sentences" / "Not scored" / "Not grounded". */
  readonly label: string;
  /**
   * Tempdoc 836 S2S3-A.2 — zero cited sentences over an INCOMPLETE pass. "Not grounded" is an
   * EVIDENCE verdict; this says verification did not fully run, which is a different claim about a
   * different thing. Rendering the verdict over a pass that never ran is the same confident
   * wrongness the citation work exists to remove, pointed at the answer instead of at a sentence.
   */
  readonly notScored: boolean;
  /**
   * Tempdoc 836 S2S3-A.2 — the run reported that verification did not fully happen (either axis).
   * {@link label} carries the reason when this is true, and {@link notScored} is exactly this over
   * zero cited sentences. A render site shows the line only when there is something extra to say:
   * a permanent "…and everything was examined" line would be noise, and noise is what gets skipped.
   */
  readonly budgetIncomplete: boolean;
}

/**
 * Tempdoc 836 §4 — the producer whose scale the grounding thresholds are calibrated on.
 *
 * <p>Two producers write the response's `similarity`: the cross-encoder (bimodal — supporting text
 * 0.89–0.999, off-topic below 0.001) and the embedding-cosine fallback (compressed into 0.38–0.72,
 * with the supported and unsupported bands measurably INTERLEAVING at a 0.0049 margin, §9.7). One
 * 0.5 constant is applied to both. Only the first may set a verified score.
 */
export const VERIFIED_SCORER = 'CROSS_ENCODER';

/**
 * May a score from this producer be treated as verified? (Tempdoc 836 §4.)
 *
 * <p>ONE authority, read by every write site — the live citation-matches handler, the persisted
 * replay, and the claim→citation resolver — so the gate cannot be applied on one render path and
 * not the other (the 561 P-A divergence, which is what makes a reloaded conversation disagree with
 * the live one).
 *
 * <p>An ABSENT producer is admitted, and that is a deliberate, narrow allowance: the field is
 * emitted on every response since tempdoc 836 S1, so absence means a record persisted BEFORE the
 * field existed, not an unknown producer today. Treating those as unverified would silently strip
 * marks from every historical conversation; treating a KNOWN non-cross-encoder producer as
 * verified is the failure this gate exists to prevent, and it fails closed.
 */
export function isVerifiedProducer(scorer: string | null | undefined): boolean {
  return scorer === undefined || scorer === null || scorer === '' || scorer === VERIFIED_SCORER;
}

/**
 * Tempdoc 836 S2S3-A.2 — the two coverage axes an answer can be incomplete on, as FLAGS.
 *
 * <p>They are carried as a SET, not collapsed into a precedence chain at the producer, so the one
 * label function below is the only place they meet — a second classifier cannot appear by a caller
 * reading "the" incompleteness. The SENTENCE axis (`sentencesScored < sentencesTotal`) says how
 * much of the ANSWER was checked; the TEXT axis (`textIncomplete` / `unexaminedSources`) says how
 * much of the SOURCE TEXT was looked at. They are reported separately and never blended into one
 * ratio — a single number would let a budget shortfall read as an evidence verdict.
 */
export interface CoverageHonesty {
  readonly sentencesScored: number;
  readonly sentencesTotal: number;
  /** The deadline stopped the pass before every sentence was scored. */
  readonly sentencesIncomplete: boolean;
  /** At least one source was PARTIALLY examined (0 < scored < considered). */
  readonly textIncomplete: boolean;
  /** Sources whose text was never examined at all — starved by the budget, or absent. */
  readonly unexaminedSources: number;
  /**
   * Fraction of considered windows actually scored, or `null` when nothing was considered.
   * Available for a detail view, deliberately NOT in the primary line: "4% of your text" invites
   * the reader to treat coverage as a quality score, while a count of unexamined sources is
   * actionable (S2S3-A.2).
   */
  readonly textExaminedRatio: number | null;
}

/**
 * Read the coverage facts off a `rag.citation_matches` payload.
 *
 * <p>Returns `null` when the payload carries none — a producer that says nothing about coverage
 * must not be reported as having said "complete". Absence of the facts and a fact of zero are
 * different statements, and only the second is an answer.
 */
export function coverageHonesty(
  payload:
    | {
        readonly sentencesScored?: number;
        readonly sentencesTotal?: number;
        readonly sourceCoverage?: ReadonlyArray<SourceCoverage>;
      }
    | null
    | undefined,
): CoverageHonesty | null {
  if (!payload) return null;
  const coverage = Array.isArray(payload.sourceCoverage) ? payload.sourceCoverage : [];
  const scored = typeof payload.sentencesScored === 'number' ? payload.sentencesScored : -1;
  const totalSentences = typeof payload.sentencesTotal === 'number' ? payload.sentencesTotal : 0;
  if (scored < 0 && coverage.length === 0) return null;
  let considered = 0;
  let examined = 0;
  let textIncomplete = false;
  let unexaminedSources = 0;
  for (const c of coverage) {
    considered += c.windowsConsidered;
    examined += c.windowsScored;
    if (c.windowsScored === 0) unexaminedSources += 1;
    else if (c.windowsScored < c.windowsConsidered) textIncomplete = true;
  }
  const sentencesScored = scored < 0 ? totalSentences : scored;
  return {
    sentencesScored,
    sentencesTotal: totalSentences,
    sentencesIncomplete: sentencesScored < totalSentences,
    textIncomplete,
    unexaminedSources,
    textExaminedRatio: considered > 0 ? examined / considered : null,
  };
}

/**
 * Count an answer's sentences (the M in "N of M") — a best-effort prose split on terminating
 * punctuation followed by whitespace/end. Approximate by design (it is an honesty HINT, not an exact
 * metric); returns ≥ 1 for any non-empty text.
 *
 * <p>Tempdoc 836 §3.6 — this is the FALLBACK denominator, no longer an authority. The backend
 * segments with `BreakIterator` and now reports its count (`sentencesTotal`), so when the coverage
 * facts are present they decide M. Two counters that disagree, one of them the denominator of a
 * user-facing honesty claim, is the fork that section closed; this regex is what a producer without
 * the facts falls back to, never a second opinion about a producer that has them.
 */
export function countSentences(text: string): number {
  const t = (text ?? '').trim();
  if (t.length === 0) return 0;
  const terminators = t.match(/[.!?]+(?=\s|$)/g);
  return Math.max(terminators ? terminators.length : 0, 1);
}

/**
 * Project the answer's per-sentence cites + text into the ④/⑤ grounding-coverage read. Reads the ONE
 * §15.A grounding verdict ({@link groundingClass}) per cite — no second classifier.
 */
export function groundingCoverage(
  citations: ReadonlyArray<{
    readonly similarity: number;
    readonly sentenceText?: string;
    readonly sentenceIndex?: number;
  }>,
  answerText: string,
  // Tempdoc 836 S2S3-A.2 — the run's coverage facts, when the producer reported them. Absent for a
  // producer that reports none, which keeps that caller's line exactly as it was.
  honesty: CoverageHonesty | null = null,
): GroundingCoverage {
  // Tempdoc 847 §2.1e — the N in "N of M" counts SENTENCES, and since 847 a sentence supported by
  // two sources arrives as two citations (one mark per verified ref). Counting citations would read
  // "5 of 4 sentences" off a four-sentence answer, so a sentence's citations collapse to one entry,
  // classified by its STRONGEST support: the question the count answers is whether the sentence is
  // grounded, which one strong source settles.
  //
  // The identity is the producer's `sentenceIndex` where it exists, and the sentence TEXT only as a
  // fallback (847 S4 review F3). Keying on text alone merged two DIFFERENT sentences that happen to
  // read identically — a real shape in list answers ("It does not.") — and under-reported coverage
  // as "1 of 4" where the truth was 2 of 4. A citation shape carrying neither (a caller that passes
  // bare similarities) is counted individually, exactly as before 847.
  const bySentence = new Map<string | number, number>();
  let grounded = 0;
  let weak = 0;
  const tally = (similarity: number): void => {
    const verdict = groundingClass(similarity);
    if (verdict === 'grounded') grounded += 1;
    else if (verdict === 'weak') weak += 1;
  };
  for (const c of citations) {
    const key =
      typeof c.sentenceIndex === 'number'
        ? c.sentenceIndex
        : typeof c.sentenceText === 'string' && c.sentenceText.length > 0
          ? c.sentenceText
          : null;
    if (key === null) {
      tally(c.similarity);
      continue;
    }
    const best = bySentence.get(key);
    if (best === undefined || c.similarity > best) bySentence.set(key, c.similarity);
  }
  for (const similarity of bySentence.values()) tally(similarity);
  const cited = grounded + weak;
  // Tempdoc 836 §3.6 — the backend's BreakIterator count is the authority for M when it is
  // reported; the regex counter is the fallback for producers that report nothing.
  const counted =
    honesty !== null && honesty.sentencesTotal > 0
      ? honesty.sentencesTotal
      : countSentences(answerText);
  const total = Math.max(counted, cited);
  const ready = cited > 0;
  const budgetIncomplete = coverageIncomplete(honesty);
  return {
    ready,
    grounded,
    weak,
    cited,
    total,
    label: coverageLabel(cited, total, honesty),
    notScored: cited === 0 && budgetIncomplete,
    budgetIncomplete,
  };
}

/**
 * The coverage line to render BESIDE an answer, or `null` when there is nothing extra to say.
 *
 * <p>Tempdoc 836 S2S3-A.2 — a line that always renders ("…and all of it was examined") is noise,
 * and noise gets skipped, which is how the one case that matters would get missed. So the note
 * appears exactly when the run reported that verification did not fully happen — and it is the
 * SAME string {@link groundingCoverage} computed, never a second phrasing of the same fact.
 */
export function coverageNote(coverage: GroundingCoverage): string | null {
  return coverage.budgetIncomplete ? coverage.label : null;
}

/** True when EITHER axis reports the pass did not fully run. A set-read, not a precedence chain. */
function coverageIncomplete(honesty: CoverageHonesty | null): boolean {
  if (honesty === null) return false;
  return honesty.sentencesIncomplete || honesty.textIncomplete || honesty.unexaminedSources > 0;
}

/**
 * Tempdoc 836 S2S3-A.2 — THE coverage line. The one place the two axes meet.
 *
 * <p>The load-bearing split is `Not scored` vs `Not grounded`. "Not grounded" is a verdict about
 * EVIDENCE and is only honest when the pass actually ran; when the budget cut it short, the
 * truthful statement is that verification did not complete. This composes with F-049 (an
 * unresolvable claim mints nothing, so coverage degrades honestly "because coverage counts what
 * renders"): coverage now also says WHY it degraded — evidence, or budget.
 *
 * <p>Deliberately absent: a percentage of text examined. "4% of your text" invites reading coverage
 * as a quality score; the count of unexamined sources is actionable, a ratio is not. The ratio stays
 * on {@link CoverageHonesty} for a detail view.
 */
function coverageLabel(cited: number, total: number, honesty: CoverageHonesty | null): string {
  if (cited === 0) return coverageIncomplete(honesty) ? 'Not scored' : 'Not grounded';
  const base = `Grounded · ${cited} of ${total} sentences`;
  if (honesty === null) return base;
  if (honesty.unexaminedSources > 0) {
    const n = honesty.unexaminedSources;
    return `${base} · ${n} source${n === 1 ? '' : 's'} not examined`;
  }
  if (honesty.textIncomplete) return `${base} · part of the text examined`;
  if (honesty.sentencesIncomplete) {
    return `${base} · ${honesty.sentencesScored} of ${honesty.sentencesTotal} sentences scored`;
  }
  return base;
}

/** The metric label for a source's GROUNDING (faithfulness) — distinct from retrieval {@link RELEVANCE_METRIC}. */
export const GROUNDING_METRIC = 'Grounding';

/**
 * Tempdoc 603 C1 — a source's FAITHFULNESS: how much it actually grounded the answer, joined from the
 * per-sentence citation-matches (`rag.citation_matches`) — NOT the BM25 retrieval score. `cited:false` = the
 * source was retrieved but never grounded a sentence, so it MUST NOT read "high confidence" (the §1 mis-calibration).
 * The representative `similarity` is the strongest sentence match, fed to the ONE {@link evidenceTier} authority
 * for the trust tier (uncited → low → the "not cited" group).
 */
export interface SourceGrounding {
  readonly cited: boolean;
  readonly groundedSentences: number;
  /** Strongest matching-sentence similarity; 0 when uncited. */
  readonly similarity: number;
  /** The faithfulness tier (the ONE authority), so the SOURCES panel agrees with the inline citations. */
  readonly tier: GroundingTier;
  /**
   * Tempdoc 836 S2S3-A.3 — the third state the binary was missing. `examined-uncited` is today's
   * `cited: false` with its meaning intact (scored, supported nothing); `unexamined` is the source
   * the verification budget never looked at, which must NOT read as "retrieved but never grounded".
   *
   * <p>`unexamined` is a BUDGET fact, so it never feeds a grounding tier or count — the same
   * containment `lexicalScore` gets under F-048: present because it is true, never a tier input.
   */
  readonly state: SourceExamination;
}

/** Tempdoc 836 S2S3-A.3 — cited / examined-and-uncited / never-examined. */
export type SourceExamination = 'cited' | 'examined-uncited' | 'unexamined';

/**
 * Join a source to its grounding via the answer's citation-matches (603 C1, corrected per PART X.B).
 *
 * <p>The match's `sourceIndex` is the source's **array position in the `rag.citations` list** — the ONE
 * established convention across the citation system: the inline `[n]` marks + their label + `Claim.verifiedRefs`
 * (`citationResolve.claimsToCitations` does `sources[refIdx]`) all index sources by that position, and the worker
 * emits exactly that position (822 §3b renamed the field so the two facts cannot re-conflate). So a source is
 * grounded by the matches whose `sourceIndex` equals its POSITION in the
 * panel's sources list (NOT a document-ordinal compare — that was the §1 "everything uncited" bug). `parentDocId`
 * is a cheap correctness guard (a position's match shares the source's document).
 */
export function sourceGrounding(
  sourceIndex: number,
  matches: ReadonlyArray<CitationMatch>,
  parentDocId?: string,
  // Tempdoc 836 S2S3-A.3 — this source's examination facts, when the run reported them. Absent ⇒
  // the state stays the established binary, so a producer that says nothing about coverage does
  // not get "unexamined" assumed on its behalf.
  coverage?: SourceCoverage | null,
): SourceGrounding {
  let count = 0;
  let best = 0;
  for (const m of matches) {
    if (m.sourceIndex === sourceIndex && (parentDocId === undefined || m.parentDocId === parentDocId)) {
      count += 1;
      if (m.similarity > best) best = m.similarity;
    }
  }
  const cited = count > 0;
  // A source the budget never examined is uncitable for a BUDGET reason. It keeps the uncited tier
  // (a non-input, exactly as before) but says why, so the panel cannot describe it as "retrieved
  // but never grounded" — a verdict about evidence that was never read.
  const state: SourceExamination = cited
    ? 'cited'
    : coverage && coverage.windowsScored === 0
      ? 'unexamined'
      : 'examined-uncited';
  return {
    cited,
    groundedSentences: count,
    similarity: best,
    tier: evidenceTier(cited ? best : 0),
    state,
  };
}

/** The per-source trust badge text — "Grounds N sentence(s)" when cited, else the honest "Retrieved · not cited". */
export function sourceGroundingLabel(g: SourceGrounding): string {
  // Tempdoc 836 S2S3-A.3 — an unexamined source is not "not cited": nothing read it. Saying it was
  // retrieved and found wanting would assert a verdict over text no scorer ever saw.
  if (g.state === 'unexamined') return 'Retrieved · not examined';
  if (!g.cited) return 'Retrieved · not cited';
  return g.groundedSentences === 1 ? 'Grounds 1 sentence' : `Grounds ${g.groundedSentences} sentences`;
}

/** Filename tail of a doc id (path-separator agnostic). */
export function filenameOf(docId: string): string {
  const i = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf('\\'));
  return i >= 0 ? docId.substring(i + 1) : docId;
}

/** Project a raw score into a labeled, declared metric. */
export function evidenceScore(value: number, label: string = RELEVANCE_METRIC): EvidenceScore {
  const v = clamp01(value);
  return { value: v, pct: Math.round(v * 100), tier: evidenceTier(value), label };
}

/** Project a retrieval-evidence record into the citation view-model. */
export function toEvidenceItem(c: AnswerEvidenceSource): EvidenceItem {
  return {
    docId: c.parentDocId,
    filename: filenameOf(c.parentDocId),
    score: typeof c.score === 'number' ? evidenceScore(c.score) : null,
    excerpt: c.excerpt ?? '',
    headingText: c.headingText ?? '',
    location: {
      parentDocId: c.parentDocId,
      startLine: c.startLine,
      endLine: c.endLine,
      startChar: c.startChar,
      endChar: c.endChar,
    },
    inclusion: contextInclusionOf(c),
  };
}

/**
 * Tempdoc 849 §5.1 — read the producer's inclusion state off a citation, or `null`.
 *
 * <p>The whole point is what it refuses to do. An absent field yields `null`, never `'included'`:
 * a producer that said nothing has not told us the model saw the passage, and inventing that on its
 * behalf is the fabrication the record exists to remove. An unrecognised value also yields `null`
 * for the same reason — an unknown state is not a known one, and guessing which of the three it
 * meant is how a vocabulary drift becomes a false claim about evidence.
 */
export function contextInclusionOf(
  c: Pick<AnswerEvidenceSource, 'contextInclusion'> | null | undefined,
): ContextInclusion | null {
  const raw = c?.contextInclusion;
  return raw === 'included' || raw === 'partial' || raw === 'dropped' ? raw : null;
}

/**
 * Tempdoc 849 §7 — the retrieved-vs-received badge, as WORDS. One authority, because the sources
 * panel and the reading pane must not describe the same budget fact two different ways.
 *
 * <p>`detail` is the sentence, not a restatement of the label: the label answers "what happened",
 * the detail answers "so what". Neither ever quotes `contextIncludedChars` — 849 §9 Q6 kept that
 * record-only, because a character count invites precision about a cut the reader cannot see.
 */
export interface InclusionBadge {
  readonly state: ContextInclusion;
  readonly label: string;
  readonly detail: string;
}

/**
 * The badge for a resolved inclusion state — `null` for ABSENCE, which is the whole point.
 *
 * <p>The `dropped` wording is the flagship. "Retrieved" is the half the reader can already see (the
 * source is sitting in the panel); "never sent to the model" is the half nothing in the product has
 * ever said. It deliberately echoes {@link sourceGroundingLabel}'s `Retrieved · not cited` shape, so
 * the two budget facts read as siblings rather than as a verdict and an error — they are two
 * different cuts (§5.5) and neither is a fault.
 */
export function inclusionBadge(inclusion: ContextInclusion | null): InclusionBadge | null {
  switch (inclusion) {
    case 'included':
      return {
        state: 'included',
        label: 'Sent to the model',
        detail: 'The whole of this passage was in the prompt the model answered from.',
      };
    case 'partial':
      return {
        state: 'partial',
        label: 'Partly sent to the model',
        detail:
          'The prompt had room for only the start of this passage, so the model never saw the rest of it.',
      };
    case 'dropped':
      return {
        state: 'dropped',
        label: 'Retrieved · never sent to the model',
        detail:
          'The search found this passage, but the prompt had no room left for it — the model answered without ever seeing it.',
      };
    default:
      return null;
  }
}

/**
 * Tempdoc 849 §7 — the header's ONE score metric, named by what it MEASURES: how closely the
 * answer's sentence matched this passage. `RetrievalCitation.score` measures something else
 * entirely and is deliberately not rendered at all — see {@link claimMatch}.
 */
export const CLAIM_MATCH_METRIC = 'Claim match';

/** A score rendered as its metric plus a BAND — never a bare percentage (§7 rule 2). */
export interface ScoreBand {
  readonly metric: string;
  readonly band: 'strong' | 'moderate' | 'weak';
}

/**
 * The claim side, from the grounding join. `null` for an UNCITED source: there is no matched
 * sentence, so there is no similarity to band — and banding a `0` would print "weak claim match"
 * over a source no claim ever referenced.
 *
 * <p>THE ONLY banded score in the header, and the reason its retrieval sibling does not exist is
 * worth keeping next to it. `RetrievalCitation.score` is the RAW Lucene hit score
 * (`RagContextOps.java:395` — `setScore(hit.score())`; the chunk reranker reorders candidates and
 * never writes its cross-encoder scores back), while {@link evidenceTier}'s thresholds are anchored
 * to the cross-encoder cutoff. Banding one through the other is not merely imprecise, it is
 * CONSTANT: RRF-fused hybrid scores cap around 0.09 and would always read "weak", raw BM25 scores
 * are unbounded and would always clamp to "strong". A band that cannot vary with the evidence is
 * negative information — it looks like a measurement and carries none.
 */
export function claimMatch(g: SourceGrounding | null): ScoreBand | null {
  if (g === null || !g.cited) return null;
  return { metric: CLAIM_MATCH_METRIC, band: groundingLabel(g.similarity) };
}

/** How much of the reader's own question the header quotes back before eliding it. */
const TURN_LABEL_MAX_CHARS = 80;

/**
 * The turn a citation was followed from, as the reader's own words. Elided at a word boundary so a
 * long question does not become the header.
 */
export function citingTurnLabel(question: string | null | undefined): string | null {
  const q = (question ?? '').trim().replace(/\s+/g, ' ');
  if (q.length === 0) return null;
  if (q.length <= TURN_LABEL_MAX_CHARS) return q;
  const cut = q.slice(0, TURN_LABEL_MAX_CHARS);
  const lastSpace = cut.lastIndexOf(' ');
  return `${(lastSpace > TURN_LABEL_MAX_CHARS / 2 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}

/**
 * Tempdoc 849 §7 — the CITATION HEADER: what the reading pane can honestly say about the citation
 * it was opened by.
 *
 * <p>Named for what it is. `DocumentPane.provenance` is TEXT-EXTRACTION provenance (the OCR/text-layer
 * route) and keeps its name, its line and its behaviour; overloading "provenance" with this would
 * have merged two unrelated facts under one word (§7's opening instruction).
 *
 * <p>Every member is nullable and every null means the same thing: the producer said nothing, so the
 * header says nothing. There is no member whose absence is rendered as a default.
 */
export interface CitationHeader {
  /** The question whose answer cited this document, elided ({@link citingTurnLabel}). */
  readonly turnLabel: string | null;
  /** `Passage 4 of 9` — suppressed when the producer had no chunk ordinal ({@link DOC_LEVEL_CHUNK_SENTINEL}). */
  readonly passage: string | null;
  /** §5 retrieved-vs-received. Absent ⇒ nothing is rendered, never "included". */
  readonly inclusion: InclusionBadge | null;
  /**
   * {@link sourceGroundingLabel} verbatim — the panel's own words, not a second vocabulary.
   * Suppressed entirely on a `dropped` passage; see {@link suppressGroundingFor}.
   */
  readonly grounding: string | null;
  /** The claim similarity, labelled by what it measures. Suppressed with {@link grounding}. */
  readonly claim: ScoreBand | null;
  /**
   * Tempdoc 849 slice 2 S10 — the citation named a span the reader cannot use (`endChar <=
   * startChar`, or a non-finite offset). Distinct from "this pane was not opened by a citation",
   * which is the header being absent altogether.
   */
  readonly spanUnusable: boolean;
}

/**
 * Do two headers say the same thing? Value equality, because {@link citationHeader} mints a fresh
 * object on every call and a consumer that re-derives on each stream event would otherwise hand its
 * renderer a new identity per chunk for words that never moved.
 *
 * <p>Compares every member of {@link CitationHeader} — `evidenceProjection.test` pins the member
 * COUNT so a member added to the type without a line here fails loudly instead of silently
 * disappearing from the change detection.
 */
export function sameCitationHeader(
  a: CitationHeader | null,
  b: CitationHeader | null,
): boolean {
  if (a === null || b === null) return a === b;
  return (
    a.turnLabel === b.turnLabel &&
    a.passage === b.passage &&
    a.inclusion?.state === b.inclusion?.state &&
    a.grounding === b.grounding &&
    a.claim?.metric === b.claim?.metric &&
    a.claim?.band === b.claim?.band &&
    a.spanUnusable === b.spanUnusable
  );
}

/** What the pane says when the citation's own span was unusable (849 S10). */
export const CITATION_SPAN_UNUSABLE =
  'This citation did not record a usable position in the document, so nothing is highlighted.';

/**
 * Tempdoc 849 slice-3 review MEDIUM-3 — a `dropped` passage may not carry a grounding claim.
 *
 * <p>The pair is REACHABLE, not hypothetical: `RAGContext.java:429` stashes every kept citation for
 * the matcher regardless of what the cut did with it, and `StreamingCitationMatcher` scores answer
 * sentences against chunk text it RE-FETCHES by `(parentDocId, chunkIndex)` — not against what the
 * model was shown. So a passage the prompt had no room for can still be "matched" against the
 * answer, and the card would read "Retrieved · never sent to the model" beside "Grounds 1 sentence".
 *
 * <p>Those two statements cannot both be informative. The inclusion state has a producer that
 * observed the actual cut; the grounding label is a similarity between the answer and text the model
 * never saw. So the inclusion badge stands alone and the grounding claim is withheld — the honest
 * reduction, rather than printing a contradiction and leaving the reader to pick which half to
 * believe. The deeper fix (never showing the matcher a dropped citation) is a backend follow-up,
 * logged to the inbox; this is the presentation-side refusal to state the contradiction.
 */
export function suppressGroundingFor(inclusion: ContextInclusion | null): boolean {
  return inclusion === 'dropped';
}

/**
 * Project a followed citation into its header. `null` when there is nothing at all to say — a
 * header of five nulls is a row of empty space, not honesty.
 *
 * <p>One projector rather than five call sites, so §7's rules and the MEDIUM-3 suppression above
 * hold wherever a header is built: the pane and the sources panel cannot disagree about whether a
 * dropped passage is allowed to claim it grounded something.
 */
export function citationHeader(input: {
  readonly citation: AnswerEvidenceSource | null;
  readonly grounding: SourceGrounding | null;
  readonly question: string | null;
  readonly spanUnusable: boolean;
}): CitationHeader | null {
  const { citation, grounding, question, spanUnusable } = input;
  const inclusion = contextInclusionOf(citation);
  const claimable = suppressGroundingFor(inclusion) ? null : grounding;
  const header: CitationHeader = {
    turnLabel: citingTurnLabel(question),
    passage: passageLabel(citation),
    inclusion: inclusionBadge(inclusion),
    grounding: claimable === null ? null : sourceGroundingLabel(claimable),
    claim: claimMatch(claimable),
    spanUnusable,
  };
  const facts: ReadonlyArray<unknown> = [
    header.turnLabel,
    header.passage,
    header.inclusion,
    header.grounding,
    header.claim,
  ];
  return facts.some((fact) => fact !== null) || header.spanUnusable ? header : null;
}

/**
 * `Passage 4 of 9`, 1-based for the reader. Suppressed on the ABSENT sentinel and on a total that
 * cannot contain the index — a chunk ordinal the producer did not record is not passage zero.
 */
function passageLabel(citation: AnswerEvidenceSource | null): string | null {
  if (citation === null) return null;
  const { chunkIndex, chunkTotal } = citation;
  if (!Number.isInteger(chunkIndex) || chunkIndex === DOC_LEVEL_CHUNK_SENTINEL || chunkIndex < 0) {
    return null;
  }
  // Tempdoc 859 §5b — an ABSENT total (the delegate plane reports none) says nothing about how many
  // passages the document has, so there is no "of N" to write. Same answer the pre-existing
  // non-integer check already gave, now reachable by absence too.
  if (typeof chunkTotal !== 'number' || !Number.isInteger(chunkTotal) || chunkTotal <= chunkIndex) {
    return null;
  }
  return `Passage ${chunkIndex + 1} of ${chunkTotal}`;
}
