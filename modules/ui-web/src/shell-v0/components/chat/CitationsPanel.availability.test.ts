// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
// Component-only checks of the shared source panel used by Search v3 Ask and Delegate.
import { afterEach, describe, expect, it, vi } from 'vitest';
import './CitationsPanel.js';
import type { CitationsPanel } from './CitationsPanel.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
vi.mock('../../api/authorizedFetch.js', () => ({ authorizedFetch: vi.fn() }));
const fetchPresence = vi.mocked(authorizedFetch);
const source = (id: string) => ({ parentDocId: id, chunkIndex: 0, startLine: 0, endLine: 1, excerpt: 'Recorded evidence', headingText: '' });
async function settle(el: CitationsPanel) {
  await el.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
}
async function mount(ids = ['/docs/held.md', '/docs/gone.md'], observe = true) {
  const el = document.createElement('jf-citations-panel') as CitationsPanel;
  el.sources = ids.map(source);
  el.sourcesExpanded = true;
  el.observeAvailability = observe;
  document.body.append(el);
  await settle(el);
  return el;
}
const cards = (el: CitationsPanel) => [...el.renderRoot.querySelectorAll('.source-card')];
const response = (states: Record<string, string>) => new Response(JSON.stringify({ states }));
afterEach(() => { document.body.replaceChildren(); vi.resetAllMocks(); });
describe('WP5.8 source availability', () => {
  it.each(['present', 'unknown', 'absent'])('includes only confirmed absence in the source button description (%s)', async (state) => {
    let release!: (value: Response) => void;
    fetchPresence.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    const el = await mount(['/docs/source.md']);
    const button = cards(el)[0]!.querySelector('button.source')!;
    const originalName = button.getAttribute('aria-label');
    const originalDescription = button.getAttribute('aria-description');
    const originalDescribedBy = button.getAttribute('aria-describedby');
    const status = button.querySelector('[role="status"]')!;
    release(response({ '/docs/source.md': state }));
    await settle(el);
    expect(button.getAttribute('aria-label')).toBe(originalName);
    expect(button.getAttribute('aria-describedby')).toBe(originalDescribedBy);
    expect(button.getAttribute('aria-description')).toBe(state === 'absent' ? 'No longer available' : originalDescription);
    expect(button.querySelectorAll('[role="status"]')).toHaveLength(1);
    expect(button.querySelector('[role="status"]')).toBe(status);
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.textContent?.trim()).toBe(state === 'absent' ? 'No longer available' : '');
  });
  it('announces only confirmed absence without changing recorded evidence', async () => {
    fetchPresence.mockResolvedValue(response({ '/docs/held.md': 'present', '/docs/gone.md': 'absent' }));
    const el = await mount();
    expect(cards(el)[0]!.textContent).not.toContain('No longer available');
    expect(cards(el)[1]!.textContent).toContain('No longer available');
    expect(cards(el)[1]!.querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite');
    expect(el.sources).toEqual([source('/docs/held.md'), source('/docs/gone.md')]);
    expect(fetchPresence.mock.calls[0]?.[2]).toEqual({ retry: false });
  });
  it.each(['unknown', 'failed', 'malformed', 'unrecognized'])('keeps %s checks neutral', async (kind) => {
    if (kind === 'failed') fetchPresence.mockRejectedValue(new Error('Engine down'));
    else if (kind === 'malformed') fetchPresence.mockResolvedValue(new Response('not JSON'));
    else fetchPresence.mockResolvedValue(response({ '/docs/gone.md': kind }));
    const el = await mount();
    expect(el.renderRoot.textContent).not.toContain('No longer available');
  });
  it('does not treat an array response as membership for an opaque numeric document ID', async () => {
    fetchPresence.mockResolvedValue(new Response(JSON.stringify({ states: ['absent'] })));
    const el = await mount(['0']);
    expect(el.renderRoot.textContent).not.toContain('No longer available');
  });
  it('paints sources before a slow check and pages many sources in bounded batches', async () => {
    let release!: (value: Response) => void;
    fetchPresence.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    fetchPresence.mockImplementation(async (_url, init) => {
      const ids = (JSON.parse(init!.body as string) as { docIds: string[] }).docIds;
      return response(Object.fromEntries(ids.map(id => [id, 'absent'])));
    });
    const el = await mount(Array.from({ length: 131 }, (_, i) => `/docs/${i}.md`));
    expect(cards(el)).toHaveLength(131);
    expect(fetchPresence).toHaveBeenCalledTimes(1);
    release(response({ '/docs/0.md': 'absent' }));
    await settle(el);
    expect(fetchPresence.mock.calls.map(call => (JSON.parse(call[1]!.body as string) as { docIds: string[] }).docIds.length)).toEqual([64, 64, 3]);
    expect(cards(el)[0]!.textContent).toContain('No longer available');
    expect(cards(el)[130]!.textContent).toContain('No longer available');
  });
  it('ignores a late prior conversation and observes returned sources on the next showing', async () => {
    let late!: (value: Response) => void;
    fetchPresence.mockImplementationOnce(() => new Promise(resolve => { late = resolve; }));
    const el = await mount(['/docs/same.md']);
    el.presenceScope = 'new-conversation';
    el.sources = [source('/docs/same.md')];
    fetchPresence.mockResolvedValue(response({ '/docs/same.md': 'present' }));
    await settle(el);
    expect(late).toBeTypeOf('function');
    late(response({ '/docs/same.md': 'absent' }));
    await settle(el);
    expect(el.renderRoot.textContent).not.toContain('No longer available');
    el.remove();
    fetchPresence.mockResolvedValue(response({ '/docs/same.md': 'absent' }));
    document.body.append(el);
    await settle(el);
    expect(el.renderRoot.textContent).toContain('No longer available');
    el.remove();
    fetchPresence.mockResolvedValue(response({ '/docs/same.md': 'present' }));
    document.body.append(el);
    await settle(el);
    expect(el.renderRoot.textContent).not.toContain('No longer available');
  });
  it('uses the resolved Engine address and ignores replies from its previous address', async () => {
    let late!: (value: Response) => void;
    fetchPresence.mockImplementationOnce(() => new Promise(resolve => { late = resolve; }));
    const el = await mount(['/docs/same.md']);
    fetchPresence.mockResolvedValue(response({ '/docs/same.md': 'present' }));
    el.apiBase = 'http://127.0.0.1:43123';
    await settle(el);
    expect(fetchPresence.mock.calls[1]?.[0]).toBe('http://127.0.0.1:43123/api/documents/presence');
    late(response({ '/docs/same.md': 'absent' }));
    await settle(el);
    expect(el.renderRoot.textContent).not.toContain('No longer available');
  });
  it('does not observe old-window consumers', async () => {
    await mount(undefined, false);
    expect(fetchPresence).not.toHaveBeenCalled();
  });
});
