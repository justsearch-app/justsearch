// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
// Component-only: two conversations may share the exact inherited turn and source objects.
import { afterEach, expect, it, vi } from 'vitest';
import { SV3_CITATION_OPEN, type Sv3CitationOpen } from './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { Sv3Turn } from './sv3-sessions.js';
import type { CitationsPanel } from '../../components/chat/CitationsPanel.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
vi.mock('../../api/authorizedFetch.js', () => ({ authorizedFetch: vi.fn() }));
const source = { parentDocId: '/docs/shared.txt', chunkIndex: 0, startLine: 1, endLine: 1, excerpt: 'Recorded evidence', headingText: '' };
const turn: Sv3Turn = {
  id: 'shared-prefix', recordId: 'question', assistantRecordId: 'answer', recordOpenedByUser: false,
  kind: 'ask', question: 'Earlier question', answer: 'Earlier answer', status: 'complete',
  evidence: { sources: [source], matches: [], marks: [], retrievalMode: '' },
  detail: '', toolCalls: 0, activity: [], askedAt: 1, standaloneQuestion: '', reasoning: [],
  durationMs: null, modelLabel: null, disposition: null,
};
afterEach(() => { document.body.replaceChildren(); vi.resetAllMocks(); });
async function settle(el: Sv3Main) {
  await el.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  const panel = el.renderRoot.querySelector<CitationsPanel>('jf-citations-panel');
  await panel?.updateComplete;
}
it('rechecks a new conversation even when it inherits the identical turn and source objects', async () => {
  const replies: Array<(response: Response) => void> = [];
  vi.mocked(authorizedFetch).mockImplementation(() => new Promise(resolve => { replies.push(resolve); }));
  const el = document.createElement('jf-sv3-main') as Sv3Main;
  el.conversationId = 'original';
  el.turns = [turn];
  document.body.append(el);
  await settle(el);
  el.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!.click();
  await settle(el);
  expect(replies).toHaveLength(1);
  el.conversationId = 'fork';
  // No new turn/source reference accompanies this conversation change.
  await settle(el);
  expect(replies).toHaveLength(2);
  replies[1]!(new Response(JSON.stringify({ states: { '/docs/shared.txt': 'present' } })));
  await settle(el);
  replies[0]!(new Response(JSON.stringify({ states: { '/docs/shared.txt': 'absent' } })));
  await settle(el);
  const panel = el.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
  expect(panel.renderRoot.textContent).not.toContain('No longer available');
});

it('passes confirmed panel absence to the pane owner for both source-card and inline citation opens', async () => {
  vi.mocked(authorizedFetch).mockResolvedValue(new Response(JSON.stringify({ states: { '/docs/shared.txt': 'absent' } })));
  const el = document.createElement('jf-sv3-main') as Sv3Main;
  el.conversationId = 'saved';
  el.apiBase = 'http://127.0.0.1:43123';
  el.turns = [turn];
  document.body.append(el);
  await settle(el);
  el.renderRoot.querySelector<HTMLButtonElement>('[data-testid="sv3-turn-sources"]')!.click();
  await settle(el);
  expect(vi.mocked(authorizedFetch).mock.calls[0]?.[0]).toBe('http://127.0.0.1:43123/api/documents/presence');
  const opened: Sv3CitationOpen[] = [];
  el.addEventListener(SV3_CITATION_OPEN, event => opened.push((event as CustomEvent<Sv3CitationOpen>).detail));
  const panel = el.renderRoot.querySelector<CitationsPanel>('jf-citations-panel')!;
  panel.renderRoot.querySelector<HTMLButtonElement>('.source')!.click();
  expect(opened[0]?.sourceUnavailable).toBe(true);
  el.renderRoot.querySelector('jf-markdown-block')!.dispatchEvent(new CustomEvent('citation-select', {
    bubbles: true, composed: true, detail: source,
  }));
  expect(opened).toHaveLength(2);
  expect(opened[1]?.sourceUnavailable).toBe(true);
});
