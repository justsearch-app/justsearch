// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { CitationsPanel } from '../../components/chat/CitationsPanel.js';
import { loadHighlighter } from '../../components/markdown/markdownHighlight.js';
import { agentAnswerEvidence } from '../../components/chat/agentEvidence.js';
import type { RetrievalCitation, SourceCoverage } from '../../components/chat/citationTypes.js';
import { sv3Ask } from './sv3-ask.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';

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
async function live(payload: ReturnType<typeof envelope>, retrievalMode = ''): Promise<Sv3TurnEvidence> {
  const encoder = new TextEncoder();
  const frames = [['rag.citations', { citations: sources }], ['rag.meta', { retrieval_mode: retrievalMode }], ['chunk', { text: answer }],
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
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });

describe('WP4.2a source panel repairs', () => {
  it.each([true, false])('HC5 shows unexamined FULLTEXT_FALLBACK sources with matches present=%s live and cold', async (hasMatches) => {
    const payload = envelope({ ...full, sourceCoverage: [
      { sourceIndex: 0, windowsConsidered: 4, windowsScored: 1 },
      { sourceIndex: 1, windowsConsidered: 4, windowsScored: 0 },
    ] }, hasMatches ? matches : []);
    const cold = projectSv3RecordTurns([
      { id: 'u1', occurredAt: new Date(1000).toISOString(), kind: 'USER_MESSAGE', originator: 'user',
        content: 'Why?', attributes: {} },
      { id: 'a1', occurredAt: new Date(2000).toISOString(), kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: answer, attributes: { citations: sources, claimMatches: payload, retrievalMode: 'FULLTEXT_FALLBACK' } },
    ])[0]!;
    for (const turn of [{ ...cold, evidence: await live(payload, 'FULLTEXT_FALLBACK') }, cold]) {
      expect(turn.evidence?.retrievalMode).toBe('FULLTEXT_FALLBACK');
      const el = await mount(turn);
      el.renderRoot.querySelector<HTMLElement>('[data-testid="sv3-turn-sources"]')!.click();
      await el.updateComplete;
      const panel = el.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
      await panel.updateComplete;
      expect([...panel.renderRoot.querySelectorAll('.grounding')].map((badge) => badge.textContent))
        .toContain('Retrieved · not examined');
      if (hasMatches) {
        expect(panel.renderRoot.textContent).toContain('Grounds 2 sentences');
        expect(panel.renderRoot.textContent).not.toContain('not cited');
      }
    }
  });

  it('HN3 keeps the mixed-source Delegate panel neutral live and cold', async () => {
    // A document-level source cannot be scored. Cosine provenance supplies no admitted matches;
    // these facts must not opt Delegate into Ask's new no-match examination groups (D1).
    const agentSources = sources.map((s, i) => ({
      ...s, path: s.parentDocId, title: `Source ${i}`, chunkIndex: i === 0 ? -1 : 0,
    }));
    const cold = projectSv3RecordTurns([
      { id: 'u1', occurredAt: new Date(1000).toISOString(), kind: 'USER_MESSAGE', originator: 'user',
        content: 'Why?', attributes: { shapeId: 'core.agent-run' } },
      { id: 'a1', occurredAt: new Date(2000).toISOString(), kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: answer, attributes: { sources: agentSources, citations: [], citationScorer: 'EMBEDDING_COSINE' } },
    ])[0]!;
    expect(cold.kind).toBe('agent');
    expect(cold.evidence?.sourceCoverage).toEqual([{ sourceIndex: 0, windowsConsidered: 0, windowsScored: 0 }]);
    // concludeRun consumes the same shared projector as the saved-record path.
    const streamed = { ...cold, evidence: { ...agentAnswerEvidence(agentSources, [], 'EMBEDDING_COSINE'), retrievalMode: '' } };
    for (const turn of [streamed, cold]) {
      const el = await mount(turn);
      el.renderRoot.querySelector<HTMLElement>('[data-testid="sv3-turn-sources"]')!.click();
      await el.updateComplete;
      const panel = el.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
      await panel.updateComplete;
      expect(panel.renderRoot.querySelectorAll('.source')).toHaveLength(2);
      expect(panel.renderRoot.querySelectorAll('.tier-header, .weak-toggle, .grounding')).toHaveLength(0);
    }
  });
});
