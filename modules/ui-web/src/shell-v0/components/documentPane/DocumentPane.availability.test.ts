// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
// Component-only checks; live Engine/browser checks supply the real boundary evidence.
import { afterEach, describe, expect, it, vi } from 'vitest';
import './DocumentPane.js';
import type { DocumentPane } from './DocumentPane.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { revealLocalPath } from '../../plugin-api/capabilities/platform.js';
vi.mock('../../api/authorizedFetch.js', () => ({ authorizedFetch: vi.fn() }));
vi.mock('../../utils/clipboardCopy.js', () => ({ copyToClipboard: vi.fn(async () => true) }));
vi.mock('../../plugin-api/capabilities/platform.js', () => ({ revealLocalPath: vi.fn(async () => {}) }));
async function pane(status: number, body: unknown) {
  vi.mocked(authorizedFetch).mockResolvedValue(new Response(JSON.stringify(body), { status }));
  const el = document.createElement('jf-document-pane') as DocumentPane;
  el.docPath = '/last-known/a-very-long-folder-name/remembered.txt';
  document.body.append(el);
  await el.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}
afterEach(() => { document.body.replaceChildren(); vi.clearAllMocks(); });
describe('WP5.8 opened source availability', () => {
  it('shows confirmed absence with last-known path, copy and reveal', async () => {
    const el = await pane(404, { sourceState: 'absent' });
    expect(el.renderRoot.textContent).toContain('no longer available');
    expect(el.renderRoot.querySelector('.last-known-path')?.textContent).toBe(el.docPath);
    const buttons = [...el.renderRoot.querySelectorAll('.missing-path jf-button')]
      .map(button => button.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!);
    buttons[0]!.click();
    buttons[1]!.click();
    await el.updateComplete;
    expect(copyToClipboard).toHaveBeenCalledWith(el.docPath);
    expect(revealLocalPath).toHaveBeenCalledWith(el.docPath);
  });
  it('keeps an already completed retained preview without another read', async () => {
    const el = await pane(200, { content: 'Already loaded text.' });
    el.remove();
    document.body.append(el);
    await el.updateComplete;
    expect(authorizedFetch).toHaveBeenCalledTimes(1);
    expect(el.content).toBe('Already loaded text.');
  });
  it('rechecks a retained pane on return and ignores its earlier missing-source reply', async () => {
    const replies: Array<(response: Response) => void> = [];
    vi.mocked(authorizedFetch).mockImplementation(() => new Promise(resolve => { replies.push(resolve); }));
    const el = document.createElement('jf-document-pane') as DocumentPane;
    el.docPath = '/docs/returned.txt';
    document.body.append(el);
    await el.updateComplete;
    el.remove();
    document.body.append(el);
    await el.updateComplete;
    expect(replies).toHaveLength(2);
    replies[1]!(new Response(JSON.stringify({ content: 'The source returned.' })));
    await new Promise(resolve => setTimeout(resolve, 0));
    replies[0]!(new Response(JSON.stringify({ sourceState: 'absent' }), { status: 404 }));
    await new Promise(resolve => setTimeout(resolve, 0));
    await el.updateComplete;
    expect(el.content).toBe('The source returned.');
    expect(el.error).toBeNull();
  });
  it.each([404, 500, 503, 504])('shows a friendly retryable failure for unconfirmed HTTP %s', async status => {
    const el = await pane(status, {});
    expect(el.renderRoot.textContent).toContain('could not be read');
    expect(el.renderRoot.textContent).not.toContain('HTTP');
    expect(el.renderRoot.textContent).not.toContain('no longer available');
  });
});
