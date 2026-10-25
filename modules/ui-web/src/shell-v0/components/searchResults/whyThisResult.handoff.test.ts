// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { render } from 'lit';
import { renderWhyDisclosure, type WhyHit } from './whyThisResult.js';
import { takePendingForceShape, takePendingSelection } from '../../utils/compose.js';
vi.mock('../../state/aiStateStore.js', () => ({ getAiState: () => ({ capabilities: { chat: true } }) }));
afterEach(() => { document.body.replaceChildren(); takePendingForceShape(); takePendingSelection(); });
const trace: WhyHit['trace'] = [{ id: 'sparse-retrieval', rank: 2, score: 3.5 }, { id: 'cross-encoder', rank: 1, score: 0.9 }];
function explain(id?: string, path?: string): { query: string } {
  const hit = { id, path, title: 'orchard.md', trace };
  const host = document.createElement('div'); document.body.append(host);
  let state: { query: string } | undefined;
  document.addEventListener('navigate-with-context', event => { state = (event as CustomEvent).detail.state; }, { once: true });
  render(renderWhyDisclosure(hit), host);
  host.querySelector<HTMLButtonElement>('[data-testid="hit-why-explain"]')!.click();
  return state!;
}
it('HO5 Explain carries the hit document as trace provenance', () => {
  explain('docs/orchard.md');
  expect(takePendingSelection()).toEqual({ kind: 'search-trace', scope: 'hit', summary: 'sparse-retrieval #2 3.50 · cross-encoder #1 0.90', docId: 'docs/orchard.md' });
});
it('HO5 Explain keeps the exact model trace in the prepared text', () => {
  const state = explain('docs/orchard.md');
  expect(state.query).toBe('Explain why this result ("orchard.md") ranked where it did.\n\nRanking trace:\nsparse-retrieval #2 3.50 · cross-encoder #1 0.90');
});
it('HO5 Explain without a document identity visibly keeps a trace-only record', () => {
  const state = explain();
  expect(state.query).toContain('No document link is available for this result; the ranking trace is shown below.');
  expect(state.query).toContain('Ranking trace:\nsparse-retrieval #2 3.50 · cross-encoder #1 0.90');
  expect(takePendingSelection()).toEqual({ kind: 'search-trace', scope: 'hit', summary: 'sparse-retrieval #2 3.50 · cross-encoder #1 0.90' });
});
it('HO5 Explain links the document path when the result identity is a chunk', () => {
  explain('chunk:unmerged', 'docs/orchard.md');
  expect(takePendingSelection()).toMatchObject({ docId: 'docs/orchard.md' });
});
it('HO5 an unmerged chunk without a document path stays visibly trace-only', () => {
  const state = explain('chunk:unmerged');
  expect(state.query).toContain('No document link is available for this result; the ranking trace is shown below.');
  expect(takePendingSelection()).not.toHaveProperty('docId');
});
