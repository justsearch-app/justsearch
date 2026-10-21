// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 ask path's PRODUCER GATE (tempdoc 847 S2 / §1.5).
 *
 * The window used to merge every `rag.citation_matches` entry as verified provenance without ever
 * reading the payload's `scorer`, so on a scorer-less or scorer-failing install it painted
 * embedding-cosine numbers — whose supported and unsupported bands interleave at a 0.0049 margin
 * (836 §9.7) — with cross-encoder-calibrated grounding tiers.
 *
 * Every gate case runs the SAME fixture through two producers, because the assertion that matters is
 * a DIFFERENCE: a "zero marks" expectation over a fixture that could never mint one passes for the
 * wrong reason (`claimsToCitations` returns `[]` for an empty source list, `citationResolve.ts:32`),
 * so each gated case is paired with the cross-encoder twin that must mark.
 *
 * Nothing here reaches the network and nothing here mocks the shared stream consumer: the global
 * fetch is stubbed with a Response-shaped stub whose body is a real SSE stream, exactly as
 * `SearchV3View.ask.test.ts` does it, so the frames travel the REAL parser and the REAL event-name →
 * handler dispatch on their way in.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { RetrievalCitation } from '../../components/chat/citationTypes.js';
import { sourceGrounding } from '../../components/chat/evidenceProjection.js';
import { claimsFromRecord, matchesFromRecord } from '../../components/chat/recordEvidence.js';
import { claimsToCitations } from '../../components/chat/citationResolve.js';
import { sv3Ask } from './sv3-ask.js';
import type { Sv3TurnEvidence } from './sv3-sessions.js';

const DOC = 'docs/lease.md';

const source = (): RetrievalCitation => ({
  parentDocId: DOC,
  chunkIndex: 0,
  chunkTotal: 1,
  startChar: 0,
  endChar: 40,
  score: 0.9,
  excerpt: 'The lock held past the renewal date.',
  startLine: 1,
  endLine: 2,
  headingText: 'Renewal',
  headingLevel: 2,
});

/** One matched sentence, at a score the cross-encoder tiers would call grounded. */
const matchPayload = (scorer?: string): Record<string, unknown> => ({
  ...(scorer === undefined ? {} : { scorer }),
  sentencesTotal: 1,
  sentencesScored: 1,
  matches: [
    {
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      sourceIndex: 0,
      similarity: 0.94,
      parentDocId: DOC,
    },
  ],
});

let fetchMock: ReturnType<typeof vi.fn>;
let realFetch: typeof globalThis.fetch;

beforeEach(() => {
  realFetch = globalThis.fetch;
  fetchMock = vi.fn();
  globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

/** A finished SSE body carrying the two citation events, in the order the backend emits them. */
function stubFrames(frames: ReadonlyArray<{ event: string; data: unknown }>): void {
  const encoder = new TextEncoder();
  const queued = frames.map((f) => ({
    done: false,
    value: encoder.encode(`event: ${f.event}\ndata: ${JSON.stringify(f.data)}\n\n`),
  }));
  fetchMock.mockImplementation(async () => ({
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async () => queued.shift() ?? { done: true, value: undefined },
        cancel: () => {},
        releaseLock: () => {},
      }),
    },
  }));
}

/**
 * Drive one ask through the two citation events and return the LAST evidence snapshot the sink was
 * handed. The sources are always non-empty: an empty `rag.citations` would make every "no marks"
 * assertion below vacuous.
 */
async function askWith(payload: Record<string, unknown>, meta?: Record<string, unknown>): Promise<Sv3TurnEvidence> {
  stubFrames([
    ...(meta ? [{ event: 'rag.meta', data: meta }] : []),
    { event: 'rag.citations', data: { citations: [source()] } },
    { event: 'rag.citation_matches', data: payload },
    // The consumer requires a TERMINAL event; a body that just stops is a lost connection, not a
    // finished answer (`streams.ts:725-729`).
    { event: 'done', data: {} },
  ]);
  const seen: Sv3TurnEvidence[] = [];
  await sv3Ask(
    { apiBase: 'http://127.0.0.1:0', question: 'why did the renewal fail?', conversationId: 'uc-a' },
    {
      onDelta: () => {},
      onEvidence: (evidence) => seen.push(evidence),
      onReasoning: () => {},
      onRewrite: () => {},
      onDone: () => {},
      onRefused: () => {},
      onHalted: () => {},
      onLeft: () => {},
      onFailed: (message) => {
        throw new Error(`the ask failed: ${message}`);
      },
    },
  );
  return seen.at(-1) as Sv3TurnEvidence;
}

describe('the search v3 ask path reads the producer off the payload (847 §1.5)', () => {
  it('mints NO mark from an embedding-cosine payload, while the cross-encoder twin marks', async () => {
    const cosine = await askWith(matchPayload('EMBEDDING_COSINE'));
    const crossEncoder = await askWith(matchPayload('CROSS_ENCODER'));
    // The twin proves the fixture CAN mint a mark, so the zero above is the gate and not an empty
    // source list quietly short-circuiting the resolver.
    expect(crossEncoder.marks).toHaveLength(1);
    expect(crossEncoder.marks[0]?.similarity).toBe(0.94);
    expect(cosine.marks).toEqual([]);
    // The sources still arrived — the answer stood on retrieval either way; only the VERIFICATION
    // is withheld.
    expect(cosine.sources).toHaveLength(1);
  });

  it('agrees with the sources panel: no marks AND no verification tier, from one verdict', async () => {
    const cosine = await askWith(matchPayload('EMBEDDING_COSINE'));
    const crossEncoder = await askWith(matchPayload('CROSS_ENCODER'));
    // `sourceGrounding` is what the panel paints each source's tier from, and it reads `similarity`
    // straight. An ungated match list would leave the panel claiming a grounded source beside an
    // answer with no marks at all (847 §2.3).
    const admitted = sourceGrounding(0, [...crossEncoder.matches], DOC);
    const gated = sourceGrounding(0, [...cosine.matches], DOC);
    expect(admitted.cited).toBe(true);
    expect(admitted.groundedSentences).toBe(1);
    expect(gated.cited).toBe(false);
    expect(gated.groundedSentences).toBe(0);
    expect(cosine.matches).toEqual([]);
  });

  it('lets the VERIFIED text win when a draft delta already claimed the same sentence index', async () => {
    // 847 S5 — the two sides segment differently: the mid-stream delta cuts an incomplete markdown
    // buffer as prose (a whole bullet list can arrive as one draft "sentence"), the final matches
    // cut parsed block nodes. At the same `sentenceIndex` they are usually different sentences, so
    // a merge that kept the draft's text would hand the renderer a key that does not name the
    // sentence that earned the score — a mark placed by another sentence's evidence.
    stubFrames([
      { event: 'rag.citations', data: { citations: [source()] } },
      {
        event: 'rag.citation_delta',
        data: {
          sentenceIndex: 0,
          sentenceText: 'The lock held.\n- And the renewal date passed.',
          citations: [{ sourceIndex: 0, score: 0.4 }],
        },
      },
      { event: 'rag.citation_matches', data: matchPayload('CROSS_ENCODER') },
      { event: 'done', data: {} },
    ]);
    const seen: Sv3TurnEvidence[] = [];
    await sv3Ask(
      { apiBase: 'http://127.0.0.1:0', question: 'why did the renewal fail?', conversationId: 'uc-b' },
      {
        onDelta: () => {},
        onEvidence: (evidence) => seen.push(evidence),
        onReasoning: () => {},
        onRewrite: () => {},
        onDone: () => {},
        onRefused: () => {},
        onHalted: () => {},
        onLeft: () => {},
        onFailed: (message) => {
          throw new Error(`the ask failed: ${message}`);
        },
      },
    );
    const evidence = seen.at(-1) as Sv3TurnEvidence;
    expect(evidence.marks).toHaveLength(1);
    expect(evidence.marks[0]?.sentenceText).toBe('The lock held.');
  });

  it('ADMITS a payload that names no producer at all — absence is not a verdict', async () => {
    // The deliberate legacy allowance (`isVerifiedProducer`, 836 §4): an envelope with no `scorer`
    // predates the field. Pinned here so a stamping regression in the handler — writing an empty or
    // a wrong producer onto the claim — cannot quietly turn this path back into a blanket bypass for
    // producers that DO name themselves.
    const absent = await askWith(matchPayload());
    const crossEncoder = await askWith(matchPayload('CROSS_ENCODER'));
    expect(absent.marks).toHaveLength(1);
    expect(absent.marks[0]?.similarity).toBe(crossEncoder.marks[0]?.similarity);
    expect(absent.matches).toHaveLength(1);
  });
});

/**
 * Tempdoc 847 F-12 — the live arm and the reload arm are ONE reader of one envelope.
 *
 * S2 shared the producer GATE between the two paths and left every other field to be read twice:
 * `recordEvidence.ts` normalized a match's position, similarity and `parentDocId` for the reloaded
 * conversation, while this window read the raw payload object for the live one. Two readers of one
 * shape is the 561 P-A divergence with a smaller blast radius, not a different thing — so what is
 * asserted below is EQUALITY of the two arms, not a fixed expectation on either.
 *
 * The legacy `chunkIndex` key below is LIVE-REACHABLE, and that was measured rather than assumed
 * (847 §S6-live). `toCitationMatchPayload:326-355` writes `sourceIndex` on today's source, but the
 * key travels on the wire from whatever build is actually serving: a backend one rename behind
 * (822 §3b) emits `chunkIndex`, and a stack running such a build was observed emitting exactly that
 * — `{sentenceText, sentenceIndex, parentDocId, similarity, chunkIndex}`, with no `scorer` at all.
 * Against that frame the pre-fix live arm read `typeof m.sourceIndex === 'number'`, got `false`,
 * resolved no ref and minted NO mark, while the reload arm's `readSourceIndex:48-52` fell back to
 * `chunkIndex` and marked. That is the F-12 defect exactly: zero marks live, marks after a reload.
 *
 * So this is not a hypothetical shape. It is the one the shared reader has always handled and the
 * live arm silently dropped, and pinning the two arms as EQUAL is what stops a third field from
 * being normalized on one path and not the other.
 */
describe('the live arm reads the envelope through the SAME reader the reload does (847 F-12)', () => {
  /** The same match, keyed by the pre-rename positional name a persisted record can still carry. */
  const legacyPayload = (): Record<string, unknown> => ({
    scorer: 'CROSS_ENCODER',
    sentencesTotal: 1,
    sentencesScored: 1,
    matches: [
      {
        sentenceIndex: 0,
        sentenceText: 'The lock held.',
        chunkIndex: 0,
        similarity: 0.94,
        parentDocId: DOC,
      },
    ],
  });

  /** What the RELOAD arm makes of an envelope — the shared readers, over the same source list. */
  const reloadArm = (
    payload: Record<string, unknown>,
  ): Pick<Sv3TurnEvidence, 'marks' | 'matches'> => ({
    marks: claimsToCitations(claimsFromRecord(payload), [source()]),
    matches: matchesFromRecord(payload),
  });

  it('resolves a legacy positional key live, exactly as the reloaded conversation does', async () => {
    const live = await askWith(legacyPayload());
    const reloaded = reloadArm(legacyPayload());
    // The reload arm CAN mark this envelope — so an equality that held at zero would be the two arms
    // agreeing on the defect, and this rules that reading out.
    expect(reloaded.marks).toHaveLength(1);
    expect(live.marks).toEqual(reloaded.marks);
    expect(live.matches).toEqual(reloaded.matches);
    // And the panel agrees with the marks, from the one normalized position (847 §2.3).
    expect(sourceGrounding(0, [...live.matches], DOC).cited).toBe(true);
  });

  it('stays identical on the post-rename payload — the control for the case above', async () => {
    const live = await askWith(matchPayload('CROSS_ENCODER'));
    const reloaded = reloadArm(matchPayload('CROSS_ENCODER'));
    expect(reloaded.marks).toHaveLength(1);
    expect(live.marks).toEqual(reloaded.marks);
    expect(live.matches).toEqual(reloaded.matches);
  });
});

it('keeps the retrieval fallback reason through citations and completion', async () => {
  const evidence = await askWith(matchPayload('cross-encoder'), {
    retrieval_mode: 'BM25', retrieval_mode_reason: 'SEMANTIC_BUDGET_EXCEEDED',
    retrieval_mode_cause: 'loading',
  });
  expect(evidence).toMatchObject({ retrievalMode: 'BM25',
    retrievalModeReason: 'SEMANTIC_BUDGET_EXCEEDED', retrievalModeCause: 'loading' });
});
