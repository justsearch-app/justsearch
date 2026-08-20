/**
 * Tempdoc 859 §3 — the ONE delegate-plane evidence projection.
 *
 * Each case here pins a claim the rev-1 design got wrong and rev 2 corrected, so a regression would
 * be a return to a specific, named mistake rather than a vague drift:
 *
 *  - **T8 / §3a** — the matches are PROJECTED. An empty match list is not honest silence: it makes
 *    every source `cited: false`, which the panel renders as the VERDICT "Retrieved · not cited"
 *    about sources the matcher demonstrably cited.
 *  - **§5b** — the five retrieval-only fields stay ABSENT. Zero-filling them fabricates retrieval
 *    facts into a panel that groups and grades by them.
 *  - **T3 / T4 / §4** — the producer gate fires on the agent plane, and the pre-stamp allowance
 *    still admits a record written before the stamp existed.
 */
import { describe, it, expect } from 'vitest';
import { agentAnswerEvidence } from './agentEvidence.js';
import { sourceGrounding, sourceGroundingLabel } from './evidenceProjection.js';
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';

const SOURCES: AgentSource[] = [
  {
    parentDocId: 'docs/a.md',
    chunkIndex: 4,
    path: 'f:/docs/a.md',
    title: 'Doc A',
    excerpt: 'passage A',
    startLine: 3,
    endLine: 9,
    headingText: 'Intro',
  },
  {
    parentDocId: 'docs/b.md',
    chunkIndex: 1,
    path: 'f:/docs/b.md',
    title: 'Doc B',
    excerpt: 'passage B',
    startLine: 12,
    endLine: 20,
    headingText: '',
  },
];

const CITES: AgentSentenceCite[] = [
  { sentenceText: 'The lock held.', sourceIndex: 0, similarity: 0.91 },
  // The SAME sentence, a second source — the multi-source shape the ordinal derivation exists for.
  { sentenceText: 'The lock held.', sourceIndex: 1, similarity: 0.77 },
  { sentenceText: 'The retry then succeeded.', sourceIndex: 1, similarity: 0.83 },
];

describe('agentAnswerEvidence — sources', () => {
  it('carries what the delegate producer reports and leaves the retrieval-only fields ABSENT', () => {
    const { sources } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(sources).toHaveLength(2);
    const first = sources[0]!;
    expect(first.parentDocId).toBe('docs/a.md');
    expect(first.chunkIndex).toBe(4);
    expect(first.startLine).toBe(3);
    expect(first.endLine).toBe(9);
    expect(first.excerpt).toBe('passage A');
    expect(first.headingText).toBe('Intro');
    // §5b — the forbidden repair, asserted as absence rather than as a value. `startChar: 0` on
    // every source of a document makes a followed citation resolve to that document's FIRST source
    // (the wrong-target deep link 822 §3b removed), and `score: 0` is a "low relevance" grade over a
    // number this producer never emits.
    expect(first.startChar).toBeUndefined();
    expect(first.endChar).toBeUndefined();
    expect(first.score).toBeUndefined();
    expect(first.chunkTotal).toBeUndefined();
    expect(first.headingLevel).toBeUndefined();
    // ...and absence is stated by the KEY being missing, not by a sentinel value sitting in it.
    expect(Object.keys(first).sort()).toEqual(
      ['chunkIndex', 'endLine', 'excerpt', 'headingText', 'parentDocId', 'startLine'].sort(),
    );
  });

  it('reports the SOURCE count, not the cite count — the fabricated number 859 §1(3b) found', () => {
    // The observed defect read `attributes.citations` as if it were the retrieval set, so a run with
    // 28 sources and 12 sentence-cites told the reader it had 12 sources. Two sources, three cites.
    const { sources, matches } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(sources).toHaveLength(2);
    expect(matches).toHaveLength(3);
  });
});

describe('agentAnswerEvidence — matches (T8, §3a)', () => {
  it('projects one match per cite, with the sentence ordinal shared with the marks', () => {
    const { matches, marks } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(matches.map((m) => m.sentenceIndex)).toEqual([0, 0, 1]);
    // The marks ran the SAME ordinal walk, so a mark and its panel card describe one sentence.
    expect(marks.map((m) => m.sentenceIndex)).toEqual([0, 0, 1]);
    expect(matches.map((m) => m.sourceIndex)).toEqual([0, 1, 1]);
    expect(matches.map((m) => m.similarity)).toEqual([0.91, 0.77, 0.83]);
    expect(matches.map((m) => m.parentDocId)).toEqual(['docs/a.md', 'docs/b.md', 'docs/b.md']);
  });

  it('states textSource CHUNK_LOOKUP rather than leaving it absent (§2)', () => {
    // The agent path scores against chunk text RE-FETCHED by (parentDocId, chunkIndex), never the
    // literal excerpt the model saw. Absent would mean "a record older than the field" — a different
    // claim, and the one that hides this asymmetry.
    const { matches } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(matches.every((m) => m.textSource === 'CHUNK_LOOKUP')).toBe(true);
  });

  it('T8 — a cited source reads "Grounds N sentences", never "Retrieved · not cited"', () => {
    const { sources, matches } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    // Source 1 is cited by two sentences; source 0 by one. This is the exact join the panel performs.
    const g0 = sourceGrounding(0, matches, sources[0]!.parentDocId);
    const g1 = sourceGrounding(1, matches, sources[1]!.parentDocId);
    expect(sourceGroundingLabel(g0)).toBe('Grounds 1 sentence');
    expect(sourceGroundingLabel(g1)).toBe('Grounds 2 sentences');
    expect(g0.cited).toBe(true);
    expect(g1.cited).toBe(true);
  });

  it('T8 counter-case — with matches thrown away the panel asserts the opposite', () => {
    // Pinning WHY `matches: []` was rejected: the empty list is not neutral, it produces a verdict.
    const { sources } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    const g = sourceGrounding(0, [], sources[0]!.parentDocId);
    expect(sourceGroundingLabel(g)).toBe('Retrieved · not cited');
  });

  it('mints no match for a cite whose sourceIndex addresses no source — same fail-closed rule as the mark', () => {
    const dangling: AgentSentenceCite[] = [
      { sentenceText: 'Dangling.', sourceIndex: 9, similarity: 0.5 },
      { sentenceText: 'Valid.', sourceIndex: 0, similarity: 0.6 },
    ];
    const { matches, marks } = agentAnswerEvidence(SOURCES, dangling, 'CROSS_ENCODER');
    expect(matches).toHaveLength(1);
    expect(matches[0]!.parentDocId).toBe('docs/a.md');
    expect(marks).toHaveLength(1);
  });
});

describe('agentAnswerEvidence — the producer gate (§4)', () => {
  it('T3 — a cosine-scored run mints NO marks, and its sources still stand', () => {
    const { sources, matches, marks } = agentAnswerEvidence(SOURCES, CITES, 'EMBEDDING_COSINE');
    expect(marks).toEqual([]);
    // Sources-without-marks is `AgentCitationResolver`'s documented degradation (565 §10) — the
    // evidence is still shown, only the per-sentence grading is withheld.
    expect(sources).toHaveLength(2);
    expect(matches).toHaveLength(3);
  });

  it('T4 — an ABSENT stamp still marks: the pre-stamp allowance, not an empty payload', () => {
    // The discriminator for T3: if T3 passed because the payload was empty rather than because the
    // gate fired, this case would pass too and prove nothing. Same sources, same cites, no stamp.
    expect(agentAnswerEvidence(SOURCES, CITES, null).marks).toHaveLength(3);
    expect(agentAnswerEvidence(SOURCES, CITES, undefined).marks).toHaveLength(3);
    expect(agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER').marks).toHaveLength(3);
  });

  it('an unrecognised producer fails CLOSED — an unknown scorer is not a verified one', () => {
    expect(agentAnswerEvidence(SOURCES, CITES, 'SOMETHING_NEW').marks).toEqual([]);
    expect(agentAnswerEvidence(SOURCES, CITES, 'NONE').marks).toEqual([]);
  });
});
