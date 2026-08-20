/**
 * Tempdoc 559 Authority IV — evidence-projection conformance.
 *
 * Proves the citation UI's view-model is a TOTAL projection of the retrieval-
 * evidence record (`RetrievalCitation` ← Java `ContextCitation`): every record
 * field is either represented in `EvidenceItem` or DELIBERATELY dropped — the
 * same lossy-downward discipline the Java `SearchTraceProjectionConformanceTest`
 * applies to the ranking sibling. Also pins the score-meaning fix: the score is
 * a labeled metric, not a bare scalar.
 */
import { describe, it, expect } from 'vitest';
import type { RetrievalCitation, CitationMatch } from './CitationsPanel.js';
import {
  toEvidenceItem,
  evidenceScore,
  evidenceTier,
  groundingClass,
  groundingLabel,
  groundingCoverage,
  countSentences,
  filenameOf,
  RELEVANCE_METRIC,
  declaredGroundingClass,
  answerFrame,
  answerFrameLabel,
  groundingDegraded,
  sourcesAreChunkPrecise,
  sourceGrounding,
  sourceGroundingLabel,
  // Tempdoc 849 slice 3 §7 — the citation header's label authority.
  inclusionBadge,
  claimMatch,
  citationHeader,
  citingTurnLabel,
  sameCitationHeader,
  suppressGroundingFor,
  DOC_LEVEL_CHUNK_SENTINEL,
  CLAIM_MATCH_METRIC,
  CITATION_SPAN_UNUSABLE,
  type SourceGrounding,
} from './evidenceProjection.js';

function match(overrides: Partial<CitationMatch> = {}): CitationMatch {
  return {
    sentenceIndex: 0,
    sentenceText: 's',
    sourceIndex: 0,
    similarity: 0.8,
    parentDocId: 'a.md',
    ...overrides,
  };
}

const FULL: RetrievalCitation = {
  parentDocId: 'C:/docs/handbook/onboarding.md',
  chunkIndex: 3,
  chunkTotal: 9,
  startChar: 120,
  endChar: 240,
  score: 0.83,
  excerpt: 'usually configured via the admin panel',
  startLine: 10,
  endLine: 14,
  headingText: 'Configuration',
  headingLevel: 2,
  contextInclusion: 'partial',
  contextIncludedChars: 96,
};

describe('evidenceProjection — total projection of the evidence record', () => {
  it('represents every field that the citation card needs', () => {
    const item = toEvidenceItem(FULL);
    // parentDocId → docId + location.parentDocId
    expect(item.docId).toBe(FULL.parentDocId);
    expect(item.location.parentDocId).toBe(FULL.parentDocId);
    // startChar/endChar/startLine/endLine → location (navigate-to-source)
    expect(item.location.startChar).toBe(FULL.startChar);
    expect(item.location.endChar).toBe(FULL.endChar);
    expect(item.location.startLine).toBe(FULL.startLine);
    expect(item.location.endLine).toBe(FULL.endLine);
    // score → labeled metric; excerpt + headingText carried verbatim
    expect(item.score?.value).toBeCloseTo(0.83);
    expect(item.excerpt).toBe(FULL.excerpt);
    expect(item.headingText).toBe(FULL.headingText);
    // parentDocId tail → filename
    expect(item.filename).toBe('onboarding.md');
  });

  it('classifies every RetrievalCitation field as represented or deliberately dropped', () => {
    // The exhaustive field set — a new record field forces a decision here.
    const REPRESENTED = new Set([
      'parentDocId',
      'startChar',
      'endChar',
      'score',
      'excerpt',
      'startLine',
      'endLine',
      'headingText',
      // Tempdoc 849 §5.1 — retrieved-vs-received, carried as EvidenceItem.inclusion.
      'contextInclusion',
    ]);
    // Deliberately dropped — not needed by the citation card (chunk indexing +
    // heading depth are navigation-internal, not user-facing evidence).
    // `contextIncludedChars` is record-only by decision (849 §9 Q6): "partially
    // included" is the honest fact, and a char count invites false precision
    // about a boundary the reader cannot see.
    const DROPPED = new Set([
      'chunkIndex',
      'chunkTotal',
      'headingLevel',
      'contextIncludedChars',
    ]);
    const allFields = Object.keys(FULL);
    for (const f of allFields) {
      expect(REPRESENTED.has(f) || DROPPED.has(f)).toBe(true);
    }
    // No phantom classification (every classified field really exists).
    for (const f of [...REPRESENTED, ...DROPPED]) {
      expect(allFields).toContain(f);
    }
  });

  // --- Tempdoc 849 §5.1: retrieved is not received, and absence is not "included".

  it('849: a resolved inclusion state reaches the projection verbatim', () => {
    expect(toEvidenceItem(FULL).inclusion).toBe('partial');
    expect(toEvidenceItem({ ...FULL, contextInclusion: 'dropped' }).inclusion).toBe('dropped');
  });

  it('849: a citation that says nothing about inclusion projects null, never "included"', () => {
    const silent = { ...FULL };
    delete silent.contextInclusion;
    // The load-bearing assertion: `null`, not `'included'`. Every conversation persisted before
    // 849 is silent, and defaulting them to "the model saw it" is the fabrication the field exists
    // to remove — a defaulting projection would pass a `toBeDefined()` check just as happily.
    expect(toEvidenceItem(silent).inclusion).toBeNull();
  });

  it('849: an unrecognised inclusion value is absence, not a guess', () => {
    // A vocabulary drift must not be coerced into one of the three known states — guessing which
    // one it meant is how drift becomes a false claim about evidence.
    expect(toEvidenceItem({ ...FULL, contextInclusion: 'sort-of' } as never).inclusion).toBeNull();
  });

  it('849: inclusion never feeds the score or its tier (budget-fact containment)', () => {
    const dropped = toEvidenceItem({ ...FULL, contextInclusion: 'dropped' });
    const included = toEvidenceItem({ ...FULL, contextInclusion: 'included' });
    expect(dropped.score).toEqual(included.score);
  });

  it('the score carries a declared metric label — not a bare scalar', () => {
    const item = toEvidenceItem(FULL);
    expect(item.score?.label).toBe(RELEVANCE_METRIC);
    expect(item.score?.label).toBe('Relevance');
  });

  it('a source whose producer reported NO score projects a null score, not a zero one', () => {
    // Tempdoc 859 §5b — the delegate plane's `AgentSource` carries no ranking score (uncalibrated,
    // 559). `evidenceScore(0)` would project that silence as a real `low` tier: a grade over a
    // number nobody produced, which is exactly the fabrication the optional field exists to remove.
    const { score: _dropped, ...noScore } = FULL;
    const item = toEvidenceItem(noScore);
    expect(item.score).toBeNull();
    // and the rest of the projection is unaffected — absence is contained to the field.
    expect(item.excerpt).toBe(FULL.excerpt);
  });

  it('score projects to clamped value / percent / tier', () => {
    expect(evidenceScore(1).pct).toBe(100);
    expect(evidenceScore(1).tier).toBe('high');
    expect(evidenceScore(0.5).pct).toBe(50);
    expect(evidenceScore(0.5).tier).toBe('medium');
    expect(evidenceScore(0.1).tier).toBe('low');
    // out-of-range clamps
    expect(evidenceScore(1.7).value).toBe(1);
    expect(evidenceScore(-0.2).value).toBe(0);
  });

  it('tier thresholds are the single authority, anchored to the matcher cutoff (0.6/0.5)', () => {
    expect(evidenceTier(0.6)).toBe('high');
    expect(evidenceTier(0.59)).toBe('medium');
    expect(evidenceTier(0.5)).toBe('medium'); // = the matcher cutoff: a cited sentence is ≥ this
    expect(evidenceTier(0.49)).toBe('low'); // below the cutoff → only UNcited prose lands here
  });

  // Tempdoc 565 §15.A — the grounding presentations (inline-mark class, hover label) derive from the
  // ONE tier authority. Before §15 these were forked (MarkdownBlock/StreamingTextBlock 0.5/0.2;
  // CitationHoverCard 0.5/0.2) so a single similarity classified DIFFERENTLY across surfaces; these
  // pin that one similarity now maps to one tier everywhere (and the boundaries track `evidenceTier`).
  it('groundingClass derives the inline-mark stem from the one tier authority', () => {
    expect(groundingClass(0.6)).toBe('grounded'); // high
    expect(groundingClass(0.59)).toBe('weak'); // medium
    expect(groundingClass(0.5)).toBe('weak'); // medium (a cited sentence at the cutoff reads weak, not ungrounded)
    expect(groundingClass(0.49)).toBe('ungrounded'); // low (below cutoff = uncited prose)
  });

  it('groundingLabel derives the hover word from the SAME tier authority', () => {
    expect(groundingLabel(0.6)).toBe('strong'); // high
    expect(groundingLabel(0.59)).toBe('moderate'); // medium
    expect(groundingLabel(0.49)).toBe('weak'); // low
  });

  it('one similarity → one tier across every grounding surface (the §15.A fix)', () => {
    // A mid-band value (above the 0.5 cutoff, below the 0.6 grounded bar) reads consistently 'weak'.
    const s = 0.55;
    const tier = evidenceTier(s); // 'medium'
    expect(tier).toBe('medium');
    // class + label + score.tier now all agree on that one tier — no surface disagrees.
    expect(groundingClass(s)).toBe('weak');
    expect(groundingLabel(s)).toBe('moderate');
    expect(evidenceScore(s).tier).toBe('medium');
  });

  it('filenameOf handles both path separators and bare ids', () => {
    expect(filenameOf('a/b/c.md')).toBe('c.md');
    expect(filenameOf('a\\b\\c.md')).toBe('c.md');
    expect(filenameOf('bare')).toBe('bare');
  });
});

// Tempdoc 565 §14 ④/⑤ — the grounding-honesty read (readiness + coverage) over the one §15.A verdict.
describe('groundingCoverage — the ④ readiness + ⑤ coverage read', () => {
  it('countSentences splits prose best-effort and is ≥1 for non-empty text', () => {
    expect(countSentences('')).toBe(0);
    expect(countSentences('One sentence.')).toBe(1);
    expect(countSentences('First. Second! Third?')).toBe(3);
    expect(countSentences('No terminator here')).toBe(1); // text but no punctuation ⇒ one sentence
  });

  it('④ ready=false + "Not grounded" when no sentence is cited', () => {
    const c = groundingCoverage([], 'An answer with three sentences. Two. Three.');
    expect(c.ready).toBe(false);
    expect(c.cited).toBe(0);
    expect(c.label).toBe('Not grounded');
  });

  it('④ ready + ⑤ "N of M" counts grounded/weak cites via the §15.A verdict', () => {
    // similarities: 0.8 grounded, 0.55 weak (≥0.5 cutoff, <0.6 high), 0.49 below-cutoff (not cited in practice).
    const cites = [{ similarity: 0.8 }, { similarity: 0.55 }];
    const c = groundingCoverage(cites, 'Alpha statement. Beta statement. Gamma. Delta. Epsilon.');
    expect(c.ready).toBe(true);
    expect(c.grounded).toBe(1); // 0.8 ⇒ grounded
    expect(c.weak).toBe(1); // 0.55 ⇒ weak
    expect(c.cited).toBe(2); // N
    expect(c.total).toBe(5); // M
    expect(c.label).toBe('Grounded · 2 of 5 sentences');
  });

  it('total (M) is never less than cited (N)', () => {
    // more cites than the heuristic finds sentences ⇒ total clamps up to cited.
    const c = groundingCoverage([{ similarity: 0.9 }, { similarity: 0.9 }, { similarity: 0.9 }], 'Short');
    expect(c.cited).toBe(3);
    expect(c.total).toBeGreaterThanOrEqual(3);
  });
});

// Tempdoc 577 §2.12 Move 3 — the epistemic answer-frame authority.
describe('answerFrame — the epistemic frame (declared class × actual outcome)', () => {
  it('declares each shape a grounding class (exhaustive over the interaction shapes)', () => {
    expect(declaredGroundingClass('core.agent-run')).toBe('grounded-index');
    expect(declaredGroundingClass('core.rag-ask')).toBe('grounded-index');
    expect(declaredGroundingClass('core.workflow-run')).toBe('grounded-index');
    expect(declaredGroundingClass('core.free-chat')).toBe('ungrounded-llm');
    expect(declaredGroundingClass('core.extract')).toBe('transform');
  });

  it('an ungrounded-LLM shape is always framed ungrounded — regardless of (absent) evidence', () => {
    expect(answerFrame('core.free-chat', 0, groundingCoverage([], 'Some answer.'))).toBe('ungrounded');
    // even if the model fabricated markers and we had a stray cite, the shape declares ungrounded:
    expect(answerFrame('core.free-chat', 3, groundingCoverage([{ similarity: 0.9 }], 'A. B.'))).toBe(
      'ungrounded',
    );
  });

  it('a transform shape (extract) is framed transform', () => {
    expect(answerFrame('core.extract', 0, groundingCoverage([], '{json}'))).toBe('transform');
  });

  it('an index-grounded shape with ZERO sources is honestly ungrounded (the §2.9 V1 case)', () => {
    expect(answerFrame('core.agent-run', 0, groundingCoverage([], 'Based on [1] and [2].'))).toBe(
      'ungrounded',
    );
  });

  it('index-grounded with sources but NO per-sentence cites is grounded (marks pending, not partial)', () => {
    expect(answerFrame('core.agent-run', 4, groundingCoverage([], 'A. B. C.'))).toBe('grounded');
  });

  it('603 D-4: index-grounded, sources but no cites AND document-level (not chunk-precise) is SOURCED', () => {
    // Marks can NEVER arrive for document-level sources (no chunk identity → the matcher cannot run), so
    // cited===0 is provenance, not "marks pending" — the `sourced` frame, never "grounded".
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), false)).toBe('sourced');
    // The SAME zero-cite coverage with chunk-precise sources (default) stays grounded (marks pending).
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), true)).toBe('grounded');
    // Partial coverage outranks the doc-level check (some sentences DID cite) — still partially-grounded.
    const cov = groundingCoverage([{ similarity: 0.9 }], 'Alpha. Beta. Gamma.');
    expect(answerFrame('core.agent-run', 2, cov, false)).toBe('partially-grounded');
  });

  it('720: chunk-precise sources, zero cites — grounded while streaming, SOURCED once settled', () => {
    // The regression the settled path never had a test for: a chunk-precise answer whose matcher tied NO
    // sentence to a passage. Mid-stream (settled=false / omitted) marks may still arrive ⇒ grounded.
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), true)).toBe('grounded');
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), true, false)).toBe('grounded');
    // Once SETTLED the matcher has finished and matched nothing ⇒ provenance, NOT "Grounded · 0 of N".
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), true, true)).toBe('sourced');
    // Document-level stays `sourced` regardless of settle state (the matcher can never run there).
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), false, true)).toBe('sourced');
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), false, false)).toBe('sourced');
    // Settled does NOT override partial coverage (some sentences DID cite).
    const cov = groundingCoverage([{ similarity: 0.9 }], 'Alpha. Beta. Gamma.');
    expect(answerFrame('core.agent-run', 2, cov, true, true)).toBe('partially-grounded');
  });

  it('index-grounded where some sentences cite and others do not is partially-grounded', () => {
    // 1 cited of 3 sentences ⇒ partial.
    const cov = groundingCoverage([{ similarity: 0.9 }], 'Alpha. Beta. Gamma.');
    expect(cov.cited).toBe(1);
    expect(cov.total).toBe(3);
    expect(answerFrame('core.agent-run', 2, cov)).toBe('partially-grounded');
  });

  it('frame labels: grounded is silent; transform (603 C3) + partial + ungrounded carry an honest header', () => {
    expect(answerFrameLabel('grounded')).toBeNull();
    // 603 C3 — an extraction (transform) is no longer silent: it is the model's own structuring, not
    // retrieved data, so it carries an unmissable "not retrieved from your documents" marker.
    expect(answerFrameLabel('transform')).toMatch(/not retrieved from your documents/i);
    expect(answerFrameLabel('partially-grounded')).toMatch(/not backed/i);
    // Tempdoc 577 §2.16 — the default (non-degraded) ungrounded label names the no-search mode.
    expect(answerFrameLabel('ungrounded')).toMatch(/does not search/i);
    // The degraded variant is honest that the run SEARCHED but found nothing to cite.
    expect(answerFrameLabel('ungrounded', true)).toMatch(/found nothing to cite/i);
    // 603 D-4 — the SOURCED label states provenance without claiming per-sentence grounding.
    expect(answerFrameLabel('sourced')).toMatch(/per-sentence grounding not verified/i);
  });

  describe('sourcesAreChunkPrecise — 603 D-4 doc-level vs chunk-precise discriminator', () => {
    it('all document-level (chunkIndex === -1 sentinel) ⇒ not chunk-precise', () => {
      expect(sourcesAreChunkPrecise([{ chunkIndex: -1 }, { chunkIndex: -1 }])).toBe(false);
    });
    it('any real chunk ordinal (including 0) ⇒ chunk-precise', () => {
      expect(sourcesAreChunkPrecise([{ chunkIndex: -1 }, { chunkIndex: 0 }])).toBe(true);
      expect(sourcesAreChunkPrecise([{ chunkIndex: 3 }])).toBe(true);
    });
    it('a source type without chunkIndex (RAG RetrievalCitation) ⇒ chunk-precise (no sentinel)', () => {
      expect(sourcesAreChunkPrecise([{}])).toBe(true);
    });
    it('empty list ⇒ not chunk-precise (vacuous)', () => {
      expect(sourcesAreChunkPrecise([])).toBe(false);
    });
  });

  it('577 §2.16 — groundingDegraded distinguishes searched-but-uncitable from no-search', () => {
    // A grounded-index shape (agent/RAG) with ZERO sources searched but couldn't cite ⇒ degraded.
    expect(groundingDegraded('core.agent-run', 0)).toBe(true);
    expect(groundingDegraded('core.rag-ask', 0)).toBe(true);
    // With sources, it is not degraded (it cited something).
    expect(groundingDegraded('core.agent-run', 3)).toBe(false);
    // A free-chat shape never searches ⇒ NOT degraded (it is ungrounded by design, not by failure).
    expect(groundingDegraded('core.free-chat', 0)).toBe(false);
    // A transform shape is not a grounding case at all.
    expect(groundingDegraded('core.extract', 0)).toBe(false);
  });
});

describe('sourceGrounding — faithfulness join by ARRAY POSITION (603 C1 / PART X.B)', () => {
  it('joins a source to its grounded sentences by its array position (match.sourceIndex === sourceIndex)', () => {
    // The match `sourceIndex` is the source's POSITION in the rag.citations list (the established convention
    // the inline marks use), NOT a document ordinal. Source at index 2 is grounded by matches at position 2.
    const g = sourceGrounding(2, [
      match({ sourceIndex: 2, parentDocId: 'a.md', similarity: 0.7 }),
      match({ sourceIndex: 2, parentDocId: 'a.md', similarity: 0.9 }),
      match({ sourceIndex: 5, parentDocId: 'a.md', similarity: 0.99 }), // different position — excluded
    ], 'a.md');
    expect(g.cited).toBe(true);
    expect(g.groundedSentences).toBe(2);
    expect(g.similarity).toBe(0.9); // strongest match
    expect(g.tier).toBe('high'); // 0.9 ≥ 0.6
  });

  it('DECISIVE — position-join, not doc-ordinal: a match at position 1 grounds index 1 even when ordinals differ', () => {
    // This is the §1 "everything uncited" bug guard. Sources have NON-sequential doc-ordinals; the join must
    // key on the array POSITION (what the matcher emits), so an ordinal compare would mis-assign here.
    const matches = [match({ sourceIndex: 1, parentDocId: 'sys.md', similarity: 0.95 })];
    const grounded = sourceGrounding(1, matches, 'sys.md'); // the source at array-index 1
    expect(grounded.cited).toBe(true);
    expect(grounded.groundedSentences).toBe(1);
    const other = sourceGrounding(0, matches, 'other.md'); // a different source at index 0
    expect(other.cited).toBe(false);
  });

  it('parentDocId guards a position whose match is from a different document', () => {
    const g = sourceGrounding(0, [match({ sourceIndex: 0, parentDocId: 'b.md' })], 'a.md');
    expect(g.cited).toBe(false);
  });

  it('a retrieved-but-unmatched source is uncited (low tier, similarity 0)', () => {
    const g = sourceGrounding(0, [match({ sourceIndex: 3, parentDocId: 'a.md' })], 'a.md');
    expect(g.cited).toBe(false);
    expect(g.groundedSentences).toBe(0);
    expect(g.similarity).toBe(0);
    expect(g.tier).toBe('low'); // never "high confidence"
  });

  it('sourceGroundingLabel: count when cited, honest "not cited" otherwise', () => {
    expect(sourceGroundingLabel({ cited: true, groundedSentences: 1, similarity: 0.8, tier: 'high' as never, state: 'cited' })).toBe('Grounds 1 sentence');
    expect(sourceGroundingLabel({ cited: true, groundedSentences: 3, similarity: 0.8, tier: 'high' as never, state: 'cited' })).toBe('Grounds 3 sentences');
    expect(sourceGroundingLabel({ cited: false, groundedSentences: 0, similarity: 0, tier: 'low' as never, state: 'examined-uncited' })).toBe('Retrieved · not cited');
  });
});

/**
 * Tempdoc 849 slice 3 §7 — the citation header's LABELS.
 *
 * The design's one structural rule for this slice is that the wording lives here and not in a view,
 * so these are the tests that make the rule enforceable: every assertion below is about a string a
 * renderer is forbidden to mint for itself.
 */
describe('849 §7 — the citation header labels', () => {
  const cited: SourceGrounding = {
    cited: true,
    groundedSentences: 2,
    similarity: 0.82,
    tier: 'high' as never,
    state: 'cited',
  };
  const uncited: SourceGrounding = {
    cited: false,
    groundedSentences: 0,
    similarity: 0,
    tier: 'low' as never,
    state: 'examined-uncited',
  };

  it('badges each resolved inclusion state, and says NOTHING for absence', () => {
    expect(inclusionBadge('included')?.label).toBe('Sent to the model');
    expect(inclusionBadge('partial')?.label).toBe('Partly sent to the model');
    // The flagship. Both halves are load-bearing: "retrieved" is what the reader can already see,
    // "never sent to the model" is what nothing in the product has ever said.
    expect(inclusionBadge('dropped')?.label).toBe('Retrieved · never sent to the model');
    // ABSENCE. Not a badge, not a placeholder, not "unknown" — nothing at all.
    expect(inclusionBadge(null)).toBeNull();
  });

  it('no badge quotes a character count — 849 Q6 kept contextIncludedChars record-only', () => {
    for (const state of ['included', 'partial', 'dropped'] as const) {
      const badge = inclusionBadge(state);
      expect(badge).not.toBeNull();
      expect(`${badge?.label} ${badge?.detail}`).not.toMatch(/\d/);
    }
  });

  it('bands the claim score only for a source a claim actually matched', () => {
    expect(claimMatch(cited)).toEqual({ metric: CLAIM_MATCH_METRIC, band: 'strong' });
    // An uncited source has similarity 0. Banding it would print "weak claim match" over a source
    // no claim ever referenced — a verdict where there is no measurement.
    expect(claimMatch(uncited)).toBeNull();
    expect(claimMatch(null)).toBeNull();
  });

  it('renders NO retrieval band — the raw hit score is not on the tier scale', () => {
    // Slice-3 review HIGH-1. `RetrievalCitation.score` is the raw Lucene hit score
    // (`RagContextOps.java:395` — `setScore(hit.score())`; the chunk reranker reorders and never
    // writes cross-encoder scores back), while the tier thresholds are anchored to the CE cutoff.
    // Banding one through the other is not merely imprecise, it is CONSTANT: RRF-fused hybrid
    // scores cap near 0.09 → always "weak"; raw BM25 scores are unbounded → always "strong".
    //
    // So the header must carry no retrieval band AT ALL, under any mode. The two fixtures below are
    // the two ends of that reproduction, and neither may produce a second band.
    for (const score of [0.09, 12.4]) {
      const header = citationHeader({
        citation: { ...FULL, score },
        grounding: cited,
        question: 'q',
        spanUnusable: false,
      });
      expect(header).not.toBeNull();
      // Exhaustive, not a spot check: the ONLY band the header may carry is the claim one.
      const bands = Object.values(header as object).filter(
        (v) => v !== null && typeof v === 'object' && 'band' in (v as object),
      );
      expect(bands).toEqual([{ metric: CLAIM_MATCH_METRIC, band: 'strong' }]);
    }
  });

  it('the one score names what it MEASURES, and is a band rather than a number (§7 rule 2)', () => {
    const header = citationHeader({
      citation: { ...FULL, score: 0.9 },
      grounding: { ...cited, similarity: 0.51 },
      question: 'How does indexing reach the head?',
      spanUnusable: false,
    });
    // Deliberately asymmetric (retrieval 0.9 against claim similarity 0.51): the band must come
    // from the CLAIM similarity, so a projector that reached for `citation.score` would read
    // "strong" here instead of "moderate".
    expect(header?.claim).toEqual({ metric: CLAIM_MATCH_METRIC, band: 'moderate' });
    expect(`${header?.claim?.band}`).not.toMatch(/[\d%]/);
  });

  it('carries the citing turn, the passage position and the grounding label verbatim', () => {
    const header = citationHeader({
      citation: FULL, // chunkIndex 3 of 9 → 1-based for the reader
      grounding: cited,
      question: '  How   does indexing reach the head?  ',
      spanUnusable: false,
    });
    expect(header?.turnLabel).toBe('How does indexing reach the head?');
    expect(header?.passage).toBe('Passage 4 of 9');
    // The panel's own words, not a second vocabulary for the same fact.
    expect(header?.grounding).toBe(sourceGroundingLabel(cited));
    expect(header?.inclusion?.state).toBe('partial');
  });

  // --- Slice-3 review MEDIUM-3: a dropped passage may not also claim it grounded the answer.

  it('withholds the grounding claim from a DROPPED passage', () => {
    // The pair is reachable, not hypothetical: `RAGContext.java:429` hands the matcher every kept
    // citation regardless of the cut, and the matcher scores against chunk text it RE-FETCHES — not
    // against what the model saw. So "never sent to the model" and "Grounds 2 sentences" can both
    // be produced for one source, and they cannot both be informative.
    const header = citationHeader({
      citation: { ...FULL, contextInclusion: 'dropped' },
      grounding: cited,
      question: 'q',
      spanUnusable: false,
    });
    expect(header?.inclusion?.state).toBe('dropped');
    // The inclusion badge stands ALONE. Its producer observed the actual cut; the grounding label
    // is a similarity against text the model never saw.
    expect(header?.grounding).toBeNull();
    expect(header?.claim).toBeNull();
  });

  it('withholds it ONLY for dropped — partial and included keep their grounding', () => {
    // The discriminator. Without it the test above would pass against a projector that had simply
    // stopped emitting grounding altogether.
    for (const state of ['included', 'partial'] as const) {
      const header = citationHeader({
        citation: { ...FULL, contextInclusion: state },
        grounding: cited,
        question: 'q',
        spanUnusable: false,
      });
      expect(header?.grounding).toBe(sourceGroundingLabel(cited));
      expect(header?.claim).not.toBeNull();
    }
    // …and an ABSENT inclusion state is not "dropped": a pre-849 citation keeps its grounding.
    const silent = { ...FULL };
    delete silent.contextInclusion;
    expect(
      citationHeader({ citation: silent, grounding: cited, question: 'q', spanUnusable: false })
        ?.grounding,
    ).toBe(sourceGroundingLabel(cited));
  });

  it('suppressGroundingFor is the one predicate both surfaces read', () => {
    expect(suppressGroundingFor('dropped')).toBe(true);
    expect(suppressGroundingFor('partial')).toBe(false);
    expect(suppressGroundingFor('included')).toBe(false);
    expect(suppressGroundingFor(null)).toBe(false);
  });

  it('suppresses the passage position when the producer recorded no chunk ordinal', () => {
    const absent = { ...FULL, chunkIndex: DOC_LEVEL_CHUNK_SENTINEL };
    const of = (citation: RetrievalCitation): string | null =>
      citationHeader({
        citation,
        grounding: null,
        question: null,
        spanUnusable: false,
      })?.passage ?? null;
    expect(of(absent)).toBeNull();
    // …and when the total cannot contain the index: a "Passage 10 of 9" would be a number the record
    // does not support, which is worse than saying nothing.
    expect(of({ ...FULL, chunkIndex: 9, chunkTotal: 9 })).toBeNull();
    // Non-vacuity: the same projector DOES speak for a well-formed ordinal, so the two nulls above
    // are the suppression and not a projector that never emits a passage at all.
    expect(of(FULL)).toBe('Passage 4 of 9');
  });

  it('elides a long question at a word boundary, and refuses an empty one', () => {
    const long = citingTurnLabel(`${'a'.repeat(30)} ${'b'.repeat(30)} ${'c'.repeat(40)}`);
    expect(long?.endsWith('…')).toBe(true);
    expect(long?.length).toBeLessThanOrEqual(82);
    expect(long).not.toContain('cccc');
    expect(citingTurnLabel('   ')).toBeNull();
    expect(citingTurnLabel(null)).toBeNull();
  });

  it('is NULL when there is nothing to say — a header of five absences is empty space', () => {
    expect(
      citationHeader({
        citation: null,
        grounding: null,
        question: null,
        spanUnusable: false,
      }),
    ).toBeNull();
  });

  it('849 S10 — an unusable span makes the header speak even when nothing else does', () => {
    const header = citationHeader({
      citation: null,
      grounding: null,
      question: null,
      spanUnusable: true,
    });
    // The whole point of S10: "opened by a citation with an unusable span" must be distinguishable
    // from "not opened by a citation at all", which is the header being null.
    expect(header).not.toBeNull();
    expect(header?.spanUnusable).toBe(true);
    expect(CITATION_SPAN_UNUSABLE).toContain('nothing is highlighted');
  });

  it('a pre-849 citation carries every OTHER header fact and no inclusion claim', () => {
    const silent = { ...FULL };
    delete silent.contextInclusion;
    delete silent.contextIncludedChars;
    const header = citationHeader({
      citation: silent,
      grounding: cited,
      question: 'q',
      spanUnusable: false,
    });
    // The absence is CONTAINED: it silences one member, not the header.
    expect(header?.inclusion).toBeNull();
    expect(header?.passage).toBe('Passage 4 of 9');
    expect(header?.grounding).not.toBeNull();
  });

  // --- Slice-3 review LOW-5: the header is re-derived per stream event, so it needs value equality.

  it('sameCitationHeader detects a change in EVERY member it must watch', () => {
    const base = citationHeader({
      citation: FULL,
      grounding: cited,
      question: 'q',
      spanUnusable: false,
    });
    expect(base).not.toBeNull();
    const full = base as NonNullable<typeof base>;
    // The drift guard. `sameCitationHeader` compares member by member, so a member added to
    // `CitationHeader` without a line there would silently stop being watched — and the consumer
    // that re-derives on every streamed chunk would never re-render for it. If this count changes,
    // add the new member to `sameCitationHeader` (and to the mutations below).
    // Five nullable facts + `spanUnusable`.
    expect(Object.keys(full)).toHaveLength(6);

    expect(sameCitationHeader(full, { ...full })).toBe(true);
    expect(sameCitationHeader(null, null)).toBe(true);
    expect(sameCitationHeader(full, null)).toBe(false);

    expect(sameCitationHeader(full, { ...full, turnLabel: 'other' })).toBe(false);
    expect(sameCitationHeader(full, { ...full, passage: 'Passage 1 of 9' })).toBe(false);
    expect(sameCitationHeader(full, { ...full, inclusion: inclusionBadge('dropped') })).toBe(false);
    expect(sameCitationHeader(full, { ...full, grounding: 'Retrieved · not cited' })).toBe(false);
    expect(sameCitationHeader(full, { ...full, claim: null })).toBe(false);
    expect(
      sameCitationHeader(full, { ...full, claim: { metric: CLAIM_MATCH_METRIC, band: 'weak' } }),
    ).toBe(false);
    expect(sameCitationHeader(full, { ...full, spanUnusable: true })).toBe(false);
  });
});
