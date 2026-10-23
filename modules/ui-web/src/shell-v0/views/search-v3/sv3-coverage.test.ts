// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { CitationsPanel } from '../../components/chat/CitationsPanel.js';
import { loadHighlighter } from '../../components/markdown/markdownHighlight.js';
import { coverageHonesty, coverageNote, groundingCoverage } from '../../components/chat/evidenceProjection.js';
import { claimsFromRecord } from '../../components/chat/recordEvidence.js';
import { claimsToCitations } from '../../components/chat/citationResolve.js';
import type { RetrievalCitation, SourceCoverage } from '../../components/chat/citationTypes.js';
import { sv3Ask } from './sv3-ask.js';
import { sv3AnswerFrame } from './sv3-honesty.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import { applySv3Record, latestTurnRef, setTurnEvidence, settleTurn, submitInSession,
  SV3_SESSIONS_EMPTY, type Sv3Turn, type Sv3TurnEvidence } from './sv3-sessions.js';

const sources: RetrievalCitation[] = [0, 1].map((i) => ({
  parentDocId: `docs/source-${i}.md`, chunkIndex: 0, chunkTotal: 1,
  startChar: 0, endChar: 80, score: 0.9, excerpt: 'The lock held. The retry succeeded.',
  startLine: 1, endLine: 2, headingText: '', headingLevel: 0,
}));
const answer = '1. The lock held.\n2. The retry succeeded.';
const matches = ['The lock held.', 'The retry succeeded.'].map((sentenceText, sentenceIndex) => ({
  sentenceText, sentenceIndex, sourceIndex: 0, similarity: 0.94, parentDocId: sources[0]!.parentDocId,
}));
interface Facts { sentencesTotal?: number; sentencesScored?: number; sourceCoverage?: readonly SourceCoverage[] }
const full: Facts = { sentencesTotal: 2, sentencesScored: 2,
  sourceCoverage: sources.map((_, sourceIndex) => ({ sourceIndex, windowsConsidered: 4, windowsScored: 4 })) };
const envelope = (facts: Facts, scoredMatches = matches, scorer: string | null = 'CROSS_ENCODER') =>
  ({ ...facts, matches: scoredMatches, scorer });
function saved(payload?: ReturnType<typeof envelope>, text = answer): Sv3Turn {
  return projectSv3RecordTurns([
    { id: 'u1', occurredAt: new Date(1000).toISOString(), kind: 'USER_MESSAGE', originator: 'user',
      content: 'Why?', attributes: {} },
    { id: 'a1', occurredAt: new Date(2000).toISOString(), kind: 'ASSISTANT_MESSAGE', originator: 'agent',
      content: text, attributes: { citations: sources, ...(payload ? { claimMatches: payload } : {}) } },
  ])[0]!;
}
async function live(payload: ReturnType<typeof envelope>): Promise<Sv3TurnEvidence> {
  const encoder = new TextEncoder();
  const frames = [['rag.citations', { citations: sources }], ['chunk', { text: answer }],
    ['rag.citation_matches', payload], ['done', {}]];
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200,
    body: new ReadableStream({ start(controller) {
      for (const [event, data] of frames) controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
      controller.close();
    } }),
  })));
  const seen: Sv3TurnEvidence[] = [];
  await sv3Ask({ apiBase: 'http://localhost:0', question: 'Why?', conversationId: 'coverage' }, {
    onEvidence: (evidence) => seen.push(evidence), onDelta() {}, onReasoning() {}, onRewrite() {},
    onDone() {}, onRefused() {}, onHalted() {}, onLeft() {},
    onFailed(message) { throw new Error(message); },
  });
  expect(seen.length).toBeGreaterThan(0);
  return seen.at(-1)!;
}
async function mount(turn: Sv3Turn): Promise<Sv3Main> {
  await loadHighlighter();
  const el = document.createElement('jf-sv3-main') as Sv3Main;
  el.turns = [turn]; document.body.append(el); await el.updateComplete;
  await Promise.all([...el.renderRoot.querySelectorAll('jf-markdown-block')].map((b) =>
    (b as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete));
  return el;
}
const note = (el: Sv3Main) => el.renderRoot.querySelector('[data-testid="sv3-turn-coverage"]');
function refreshed(evidence: Sv3TurnEvidence, record: Sv3Turn, streaming = false): Sv3Turn {
  let list = submitInSession(SV3_SESSIONS_EMPTY, 'Why?', 1000, 'ask', 'coverage');
  const ref = latestTurnRef(list)!; list = setTurnEvidence(list, ref, evidence);
  if (!streaming) list = settleTurn(list, ref, 'complete', 2000);
  return applySv3Record(list, 'coverage', [record]).sessions[0]!.turns[0]!;
}
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });

describe('WP4.2a coverage honesty', () => {
  it('HC1 uses the check count for a fully verified list live and cold', async () => {
    const payload = envelope(full); const cold = saved(payload);
    const streamed = { ...cold, evidence: await live(payload) };
    expect(sv3AnswerFrame(streamed)).toBe('grounded'); expect(sv3AnswerFrame(cold)).toBe('grounded');
    expect(streamed.evidence?.marks).toEqual(cold.evidence?.marks);
    expect(note(await mount(cold))).toBeNull();
  });
  it('HC1 honors a count even when other coverage facts are absent', async () => {
    const cold = saved(envelope({ sentencesTotal: 2 }));
    expect(sv3AnswerFrame(cold)).toBe('grounded'); expect(note(await mount(cold))).toBeNull();
  });
  it.each([
    ['sources', { ...full, sourceCoverage: [full.sourceCoverage![0]!, { sourceIndex: 1, windowsConsidered: 4, windowsScored: 0 }] }, matches,
      'Grounded · 2 of 2 sentences · 1 source not examined'],
    ['text', { ...full, sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 4, windowsScored: 1 }] }, matches,
      'Grounded · 2 of 2 sentences · part of the text examined'],
    ['sentences', { ...full, sentencesScored: 1 }, [matches[0]!], 'Grounded · 1 of 2 sentences · 1 of 2 sentences scored'],
    ['not scored', { ...full, sentencesScored: 0 }, [], 'Not scored'],
  ] as const)('HC2 shares the %s note live refreshed and cold', async (_, facts, scoredMatches, expected) => {
    const payload = envelope(facts, [...scoredMatches]); const cold = saved(payload); const evidence = await live(payload);
    const oldNote = coverageNote(groundingCoverage(claimsToCitations(claimsFromRecord(payload), sources), answer, coverageHonesty(payload)));
    expect(oldNote).toBe(expected);
    for (const turn of [{ ...cold, evidence }, refreshed(evidence, cold), cold, saved(payload)])
      expect(note(await mount(turn))?.textContent).toBe(expected);
  });
  it.each([true, false])('HC5 supplies examination facts with matches present=%s', async (hasMatches) => {
    const payload = envelope({ ...full, sourceCoverage: [
      { sourceIndex: 0, windowsConsidered: 4, windowsScored: 1 },
      { sourceIndex: 1, windowsConsidered: 4, windowsScored: 0 },
    ] }, hasMatches ? matches : []); const cold = saved(payload);
    for (const turn of [{ ...cold, evidence: await live(payload) }, cold]) {
      const el = await mount(turn);
      const trigger = el.renderRoot.querySelector<HTMLElement>('[data-testid="sv3-turn-sources"]');
      expect(trigger).not.toBeNull(); trigger!.click(); await el.updateComplete;
      const panel = el.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!; await panel.updateComplete;
      expect(panel.sourceCoverage).toEqual(payload.sourceCoverage);
      expect(panel.renderRoot.textContent).toContain('not examined');
      if (hasMatches) {
        expect(panel.renderRoot.textContent).not.toContain('not cited');
        expect(panel.renderRoot.textContent).toContain('Grounds 2 sentences');
      }
    }
  });
  it('HC6 preserves each live fact when a settled refresh carries only some facts', async () => {
    const evidence = await live(envelope({ ...full, sentencesScored: 1 }));
    const partial = saved(envelope({ sentencesTotal: 2 })); const turn = refreshed(evidence, partial);
    expect(turn.evidence).toMatchObject({ ...full, sentencesScored: 1 });
    expect(note(await mount(turn))?.textContent).toBe('Grounded · 2 of 2 sentences · 1 of 2 sentences scored');
    expect(partial.evidence).not.toHaveProperty('sentencesScored'); expect(note(await mount(saved()))).toBeNull();
  });
  it('HC6 fills absent live facts from the record without inventing source facts', async () => {
    const turn = refreshed(await live(envelope({ sentencesScored: 1 })), saved(envelope({ sentencesTotal: 2 })));
    expect(turn.evidence).toMatchObject({ sentencesScored: 1, sentencesTotal: 2 });
    expect(turn.evidence?.sourceCoverage).toBeUndefined();
    expect(note(await mount(turn))?.textContent).toBe('Grounded · 2 of 2 sentences · 1 of 2 sentences scored');
  });
  it('HC6 retains live facts during streaming or checking refresh', async () => {
    const evidence = await live(envelope({ ...full, sentencesScored: 0 }, []));
    const turn = refreshed(evidence, saved(), true);
    expect(turn.evidence).toMatchObject({ ...full, sentencesScored: 0 }); expect(turn.status).toBe('streaming');
    expect(note(await mount(turn))).toBeNull();
  });
  it('HC8 keeps the note as ordinary stable text without live announcements', async () => {
    const cold = saved(envelope({ ...full, sentencesScored: 1 })); const el = await mount(cold); const first = note(el);
    expect(first?.textContent).toBe('Grounded · 2 of 2 sentences · 1 of 2 sentences scored');
    expect(first?.closest('[aria-live], [role="status"], [role="alert"]')).toBeNull();
    for (let i = 0; i < 3; i++) { el.turns = [{ ...cold }]; await el.updateComplete; expect(note(el)).toBe(first); }
  });
  it('HC3 HC4 HN1 preserve complete and legacy prose frames and marks without extra notes', async () => {
    for (const facts of [full, {}]) {
      const payload = envelope(facts); const cold = saved(payload, 'The lock held. The retry succeeded.');
      expect(sv3AnswerFrame(cold)).toBe('grounded'); expect((await live(payload)).marks).toEqual(cold.evidence?.marks);
      expect(note(await mount(cold))).toBeNull();
    }
    const legacy = saved(); expect(sv3AnswerFrame(legacy)).toBe('sourced'); expect(note(await mount(legacy))).toBeNull();
  });
  it.each(['EMBEDDING_COSINE', 'NONE', 'UNKNOWN', null, ''])('HN2 keeps the producer gate for %s live and cold', async (scorer) => {
    const payload = envelope(full, matches, scorer); const cold = saved(payload); const evidence = await live(payload);
    expect(evidence.marks).toEqual(cold.evidence?.marks); expect(evidence.matches).toEqual(cold.evidence?.matches);
    expect(evidence.marks).toHaveLength(scorer === null || scorer === '' ? 2 : 0);
  });
  it.each(['agent', 'halted', 'refused', 'failed'] as const)('HN3 HN5 keep %s turns without Ask coverage notes', async (kindOrStatus) => {
    const cold = saved(envelope({ ...full, sentencesScored: 0 }, []));
    const turn: Sv3Turn = kindOrStatus === 'agent' ? { ...cold, kind: 'agent' } : { ...cold, status: kindOrStatus };
    expect(note(await mount(turn))).toBeNull();
  });
});
