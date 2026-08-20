/**
 * Tempdoc 859 §5c (T9) — the reader the compiler cannot catch.
 *
 * `sv3SourceIndex` used to join a followed citation to its source on the CHARACTER SPAN, a fact only
 * the retrieval plane reports. A delegate turn's sources carry none, so `undefined === 0` was false
 * for every agent mark: the mark rendered, the reader clicked, and the source pane resolved nothing.
 * TypeScript reports nothing about comparing `number | undefined` to `number`, so this file is the
 * only static oracle the defect has.
 *
 * The second case is the TRAP, and is why the fix is a different join rather than a zero-fill:
 * making every delegate source `(0, 0)` would make `findIndex` return the document's FIRST source,
 * which is exactly the wrong-target deep link 822 §3b removed. A test that only checked "an agent
 * mark resolves" would pass under the trap; this one puts two passages of ONE document in the set.
 */
import { describe, it, expect } from 'vitest';
import { SV3_SOURCE_INDEX_ABSENT, sv3SourceIndex, sv3CitationAnchor } from './sv3-citation-anchor.js';
import { agentAnswerEvidence } from '../../components/chat/agentEvidence.js';
import type { Sv3Turn } from './sv3-sessions.js';
import type { AgentSource } from '../../../api/generated/shape-handlers/shared.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';

/** TWO passages of the SAME document — the fixture the zero-fill trap cannot survive. */
const TWO_PASSAGES_OF_ONE_DOC: AgentSource[] = [
  {
    parentDocId: 'docs/runbook.md',
    chunkIndex: 7,
    path: 'f:/docs/runbook.md',
    title: 'Runbook',
    excerpt: 'the first passage',
    startLine: 3,
    endLine: 9,
    headingText: 'Setup',
  },
  {
    parentDocId: 'docs/runbook.md',
    chunkIndex: 3,
    path: 'f:/docs/runbook.md',
    title: 'Runbook',
    excerpt: 'the second passage',
    startLine: 40,
    endLine: 52,
    headingText: 'Recovery',
  },
];

function agentTurn(sources: AgentSource[]): Sv3Turn {
  const evidence = agentAnswerEvidence(
    sources,
    [{ sentenceText: 'The retry succeeded.', sourceIndex: 1, similarity: 0.9 }],
    'CROSS_ENCODER',
  );
  return {
    id: 't1',
    kind: 'agent',
    status: 'complete',
    question: 'what happened?',
    answer: 'The retry succeeded.',
    evidence: { ...evidence, retrievalMode: '' },
    detail: '',
    toolCalls: 0,
    activity: [],
    askedAt: 0,
    standaloneQuestion: '',
    reasoning: [],
  } as unknown as Sv3Turn;
}

const detailOf = (turn: Sv3Turn, markIndex: number): CitationSelectDetail =>
  turn.evidence!.marks[markIndex]!.detail;

describe('sv3SourceIndex — the followed-citation join (859 §5c)', () => {
  it('T9 — an agent mark resolves to its source, which the char-span join could never do', () => {
    const turn = agentTurn(TWO_PASSAGES_OF_ONE_DOC);
    const detail = detailOf(turn, 0);
    // The precondition that made the old join dead: the SOURCE reports no span at all.
    expect(turn.evidence!.sources[1]!.startChar).toBeUndefined();
    expect(sv3SourceIndex(turn, detail)).toBe(1);
    expect(sv3SourceIndex(turn, detail)).not.toBe(SV3_SOURCE_INDEX_ABSENT);
  });

  it('T9 — a SECOND passage of the same document resolves to ITSELF, not to the first', () => {
    // The zero-fill trap, made unpassable. Both sources share `parentDocId`; only `startLine`
    // distinguishes them, which is exactly what the `sourceKey` identity keys on.
    const turn = agentTurn(TWO_PASSAGES_OF_ONE_DOC);
    const second: CitationSelectDetail = {
      parentDocId: 'docs/runbook.md',
      startLine: 40,
      endLine: 52,
      excerpt: 'the second passage',
    };
    const first: CitationSelectDetail = {
      parentDocId: 'docs/runbook.md',
      startLine: 3,
      endLine: 9,
      excerpt: 'the first passage',
    };
    expect(sv3SourceIndex(turn, second)).toBe(1);
    expect(sv3SourceIndex(turn, first)).toBe(0);
  });

  it('a citation naming no source in the turn is ABSENT, not source 0', () => {
    const turn = agentTurn(TWO_PASSAGES_OF_ONE_DOC);
    expect(
      sv3SourceIndex(turn, {
        parentDocId: 'docs/other.md',
        startLine: 3,
        endLine: 9,
        excerpt: '',
      }),
    ).toBe(SV3_SOURCE_INDEX_ABSENT);
    // Same document, a line no passage of it starts at — the near-miss the old join could not tell
    // apart from a hit, because both compared as `undefined === undefined` once a span was absent.
    expect(
      sv3SourceIndex(turn, {
        parentDocId: 'docs/runbook.md',
        startLine: 41,
        endLine: 52,
        excerpt: '',
      }),
    ).toBe(SV3_SOURCE_INDEX_ABSENT);
    expect(sv3SourceIndex(null, first())).toBe(SV3_SOURCE_INDEX_ABSENT);
  });

  it('the ANCHOR stays absent for a delegate citation — the document opens, nothing is highlighted', () => {
    // Char spans are for HIGHLIGHTING, a different question from identity (§5c). A delegate mark
    // reports none, and the honest answer is no highlight rather than a fabricated span.
    const turn = agentTurn(TWO_PASSAGES_OF_ONE_DOC);
    expect(sv3CitationAnchor(detailOf(turn, 0), null)).toBeNull();
    expect(
      sv3CitationAnchor({ parentDocId: 'd', startLine: 1, endLine: 2, excerpt: '' }, null),
    ).toBeNull();
    // A retrieval-plane citation with a real span still anchors.
    expect(
      sv3CitationAnchor(
        { parentDocId: 'd', startLine: 1, endLine: 2, startChar: 10, endChar: 40, excerpt: 'x' },
        'a sentence',
      ),
    ).toEqual({ startChar: 10, endChar: 40, excerpt: 'x', sentenceText: 'a sentence' });
  });
});

function first(): CitationSelectDetail {
  return { parentDocId: 'docs/runbook.md', startLine: 3, endLine: 9, excerpt: '' };
}
