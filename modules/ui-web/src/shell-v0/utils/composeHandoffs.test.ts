// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { compose, takePendingForceShape, takePendingSelection } from './compose.js';
import { buildRequestBody, type ShapeId } from '../views/unifiedChatRequest.js';
vi.mock('../state/aiStateStore.js', () => ({ getAiState: () => ({ capabilities: { chat: true } }) }));
afterEach(() => { document.body.replaceChildren(); takePendingForceShape(); takePendingSelection(); });
it.each(['core.ask', 'core.summarize'] as const)('HO6 Browse %s carries the clicked document without an affordance', operation => {
  let state: { query: string; forceShapeId: ShapeId; docIds: string[] } | undefined;
  const listener = (e: Event): void => { state = (e as CustomEvent).detail.state; };
  document.addEventListener('navigate-with-context', listener, { once: true });
  compose({ operation, source: 'CONTEXT_MENU', docIds: ['clicked.md'], docName: 'clicked.md' });
  expect(state?.forceShapeId).toBe(operation === 'core.ask' ? 'core.rag-ask' : 'core.summarize');
  const body = buildRequestBody(state!.forceShapeId, state!.query || 'question', 'session', '', state!.docIds, takePendingSelection());
  if (operation === 'core.ask') expect(body.docIds).toEqual(['clicked.md']);
  else expect(body.selection).toEqual({ kind: 'result-set', items: [{ id: 'clicked.md', kind: 'doc' }] });
});
it('HO9 a second compose without a selection replaces the first one', () => {
  compose({ operation: 'core.summarize', source: 'BUTTON', selection: { kind: 'search-trace', scope: 'hit', summary: 'older trace' } });
  compose({ operation: 'core.ask', source: 'BUTTON', userPrompt: 'unrelated' });
  expect(takePendingSelection()).toBeNull();
});
