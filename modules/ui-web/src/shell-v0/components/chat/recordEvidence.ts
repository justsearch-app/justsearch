// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 847 S1 — the ONE `claimMatches` envelope → evidence authority.
 *
 * A grounded answer's per-sentence evidence arrives as ONE map: `StreamingCitationMatcher`
 * (`modules/app-services/.../conversation/spi/StreamingCitationMatcher.java:150,155-159`) hands the
 * SAME payload object to the live `rag.citation_matches` SSE event and to the done-payload entry
 * `ConversationEngine` persists on the assistant record. So the live envelope and the persisted one
 * are not two shapes to read two ways — they are one shape, and this module is the one reader.
 *
 * It exists because the producer gate (tempdoc 836 §4) was applied by a private method on ONE view.
 * A second render path needing the same conversion (search v3, 847 §2.3/§2.4) would have forked the
 * gate across two paths — precisely the 561 P-A divergence `isVerifiedProducer`'s own doc comment
 * says must not happen: the same payload marking differently in two windows, or live versus after a
 * reload. This is a PROJECTION of the envelope, not a second authority over it.
 *
 * The gate is applied to BOTH halves of the conversion, because both reach a grounding tier:
 *  - {@link claimsFromRecord} → `Claim.verifiedScore` → `claimsToCitations` → the inline `[n]` marks.
 *  - {@link matchesFromRecord} → `CitationMatch.similarity` → `sourceGrounding` → the SOURCES panel's
 *    per-source tier and "Grounds N sentences" line (`evidenceProjection.ts:611-644` reads
 *    `m.similarity` straight). Gating only the first left the marks honest and the panel not: the
 *    same cosine number that mints no mark still painted a verification tier beside the source.
 */
import type { CitationMatch, Claim, SourceCoverage } from './citationTypes.js';
import { isVerifiedProducer } from './evidenceProjection.js';

/**
 * Tempdoc 836 §4 — read the producer off a live payload or a persisted `claimMatches` record.
 * `null` = the envelope predates the field, which {@link isVerifiedProducer} admits deliberately (it
 * is an absent fact about an old record, not an unknown producer today).
 */
export function readScorer(payload: unknown): string | null {
  if (payload !== null && typeof payload === 'object') {
    const s = (payload as { scorer?: unknown }).scorer;
    if (typeof s === 'string' && s !== '') return s;
  }
  return null;
}

/**
 * Tempdoc 822 §3b — read a persisted match's source position. Records written before the rename
 * carry the key `chunkIndex`, and their stored VALUES were already the correct positional numbers:
 * `claimMatches` is persisted from `StreamingCitationMatcher.onDone`'s authoritative
 * `documents.matchCitations` call, never from the streaming deltas whose values were wrong. So this
 * is a reader for user data under its old key, not a compatibility shim for wrong values — and no
 * migration is needed. `-1` = absent.
 */
export function readSourceIndex(m: Record<string, unknown>): number {
  if (typeof m.sourceIndex === 'number') return m.sourceIndex;
  if (typeof m.chunkIndex === 'number') return m.chunkIndex;
  return -1;
}

/** The envelope's `matches` array, or `[]` when it carries none. */
function matchArrayOf(claimMatches: unknown): Array<Record<string, unknown>> {
  return claimMatches !== null &&
    typeof claimMatches === 'object' &&
    Array.isArray((claimMatches as { matches?: unknown }).matches)
    ? (claimMatches as { matches: Array<Record<string, unknown>> }).matches
    : [];
}

/**
 * Tempdoc 847 S1 — the producer gate over a MATCH LIST, for the sources panel's sake.
 *
 * A match from a non-admitted producer is DROPPED whole rather than handed over with its number
 * intact, exactly as `claimsToCitations` drops a claim it may not verify: `sourceGrounding` reads
 * `m.similarity` directly into a tier, so there is no field a cosine score could be written into
 * that the panel would not read as a cross-encoder probability. The source still appears in the
 * panel — it was retrieved, which is true — it simply carries no verification tier, which is the
 * same thing the answer's markless text says. One producer verdict, two surfaces, one outcome.
 *
 * Absence is admitted (see {@link isVerifiedProducer}): a legacy record that predates the field
 * keeps the grounding it always rendered.
 */
export function admittedMatches(
  matches: readonly CitationMatch[],
  scorer: string | null | undefined,
): readonly CitationMatch[] {
  return isVerifiedProducer(scorer) ? matches : [];
}

/**
 * Tempdoc 561 P-A (evidence non-divergence): reconstruct the FE `Claim[]` from a `claimMatches`
 * envelope's per-claim grounding, so a RELOADED conversation renders the same inline per-claim marks
 * the live render shows.
 *
 * Tempdoc 836 §4 — the PRODUCER gate reads the same authority the live handler does, because a gate
 * applied to only one of the two render paths recreates the 561 P-A divergence.
 */
export function claimsFromRecord(claimMatches: unknown): Claim[] {
  const matches = matchArrayOf(claimMatches);
  const scorer = readScorer(claimMatches);
  const verified = isVerifiedProducer(scorer);
  const bySentence = new Map<number, Claim>();
  for (const m of matches) {
    const idx = typeof m.sentenceIndex === 'number' ? m.sentenceIndex : 0;
    const text = typeof m.sentenceText === 'string' ? m.sentenceText : '';
    const sim = typeof m.similarity === 'number' && verified ? m.similarity : null;
    const ref = readSourceIndex(m);
    const existing = bySentence.get(idx);
    if (existing) {
      existing.verifiedScore =
        sim === null ? existing.verifiedScore : Math.max(existing.verifiedScore ?? 0, sim);
      if (verified && ref >= 0 && !existing.verifiedRefs.includes(ref)) {
        existing.verifiedRefs.push(ref);
      }
    } else {
      bySentence.set(idx, {
        sentenceIndex: idx,
        sentenceText: text,
        ...(scorer !== null ? { scorer } : {}),
        // Tempdoc 822 §3d/§3b — the persisted `claimMatches` come from the AUTHORITATIVE post-hoc
        // `documents.matchCitations` call (`StreamingCitationMatcher.onDone`), never from the
        // streaming lexical deltas, so a reloaded conversation's scores AND refs are verified by
        // provenance: both land on the verified side.
        verifiedScore: sim,
        lexicalScore: 0,
        verifiedRefs: verified && ref >= 0 ? [ref] : [],
        lexicalRefs: [],
      });
    }
  }
  return [...bySentence.values()];
}

/**
 * Tempdoc 603 PART X.B — the SOURCES-panel grounding sibling of {@link claimsFromRecord}: the
 * `CitationMatch[]` behind a reloaded conversation's per-source grounding. The match `sourceIndex`
 * is the source's POSITION in the persisted `citations` list — persisted in order by
 * `RAGDoneEnricher`, the same order `sourceGrounding` joins on.
 *
 * Gated by {@link admittedMatches}: the panel's tiers and the answer's marks answer to one producer
 * verdict (847 §2.3).
 */
export function matchesFromRecord(claimMatches: unknown): CitationMatch[] {
  const parsed = matchArrayOf(claimMatches).map((m) => ({
    sentenceIndex: typeof m.sentenceIndex === 'number' ? m.sentenceIndex : 0,
    sentenceText: typeof m.sentenceText === 'string' ? m.sentenceText : '',
    sourceIndex: readSourceIndex(m),
    similarity: typeof m.similarity === 'number' ? m.similarity : 0,
    parentDocId: typeof m.parentDocId === 'string' ? m.parentDocId : '',
  }));
  return [...admittedMatches(parsed, readScorer(claimMatches))];
}


/** Tempdoc 836 S2S3-A.1 — read the per-source examination facts off a payload or a record. */
export function readSourceCoverage(payload: unknown): SourceCoverage[] {
  if (!payload || typeof payload !== 'object') return [];
  const raw = (payload as { sourceCoverage?: unknown }).sourceCoverage;
  if (!Array.isArray(raw)) return [];
  const out: SourceCoverage[] = [];
  for (const c of raw) {
    if (!c || typeof c !== 'object') continue;
    const r = c as Record<string, unknown>;
    if (
      typeof r.sourceIndex === 'number' &&
      typeof r.windowsConsidered === 'number' &&
      typeof r.windowsScored === 'number'
    ) {
      out.push({
        sourceIndex: r.sourceIndex,
        windowsConsidered: r.windowsConsidered,
        windowsScored: r.windowsScored,
      });
    }
  }
  return out;
}

/** Coverage facts shared by the live Ask envelope and its saved claimMatches. Absence stays absent. */
export function coverageFactsFromRecord(payload: unknown): {
  readonly groundingIncomplete?: boolean;
  readonly sentencesTotal?: number;
  readonly sentencesScored?: number;
  readonly sourceCoverage?: readonly SourceCoverage[];
} {
  if (!payload || typeof payload !== 'object') return {};
  const p = payload as Record<string, unknown>;
  return {
    ...(p.groundingIncomplete === true ? { groundingIncomplete: true } : {}),
    ...(typeof p.sentencesTotal === 'number' ? { sentencesTotal: p.sentencesTotal } : {}),
    ...(typeof p.sentencesScored === 'number' ? { sentencesScored: p.sentencesScored } : {}),
    ...(Array.isArray(p.sourceCoverage) ? { sourceCoverage: readSourceCoverage(p) } : {}),
  };
}
