// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// C4 — "a delayed response for folder A never fills folder B (repairs today's failed-files
// folder-switch defect)". Written against the list's public properties and DOM alone.
import { afterEach, expect, it, vi } from 'vitest';
import './FailedFilesList.js';
import type { FailedFilesList } from './FailedFilesList.js';

const job = (pathHash: string, errorMessage: string): Record<string, unknown> => ({ pathHash, state: 'FAILED', attempts: 3,
  lastUpdatedMs: 1_700_000_000_000, errorMessage, retryAfterMs: 0, collection: 'default', scanId: '' });
const body = (jobs: Array<Record<string, unknown>>): Response => new Response(JSON.stringify({ jobs, count: jobs.length }));

async function pump(el: FailedFilesList): Promise<void> {
  for (let i = 0; i < 30; i += 1) { await Promise.resolve(); await el.updateComplete; }
}

const answers = new Map<string, (response: Response) => void>();

function holdEveryListing(): void {
  answers.clear();
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
    const folder = new URL(String(url), 'http://test').searchParams.get('pathHash');
    if (folder === null) return new Response('{}');
    return new Promise<Response>(resolve => { answers.set(folder, resolve); });
  }));
}

async function listFor(folder: string): Promise<FailedFilesList> {
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = folder;
  document.body.append(el);
  await pump(el);
  return el;
}

afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

it('the render after a folder switch already shows none of the previous folder', async () => {
  holdEveryListing();
  const el = await listFor('A');
  answers.get('A')!(body([job('A-file', 'A reason')]));
  await pump(el);
  expect(el.shadowRoot!.textContent).toContain('A reason');

  el.folderPathHash = 'B';
  await el.updateComplete; // The very next frame, before anything else settles.

  expect(el.shadowRoot!.textContent).not.toContain('A reason');
  expect(el.shadowRoot!.querySelector('jf-row-actions[row-key="A-file"]')).toBeNull();
});

it('an error for the previous folder does not stay under the new one', async () => {
  holdEveryListing();
  const el = await listFor('A');
  answers.get('A')!(new Response('nope', { status: 503 }));
  await pump(el);
  expect(el.shadowRoot!.textContent).toContain('HTTP 503');

  el.folderPathHash = 'B';
  await el.updateComplete;

  expect(el.shadowRoot!.textContent).not.toContain('HTTP 503');
});

it('a delayed response for folder A never fills folder B', async () => {
  holdEveryListing();
  const el = await listFor('A');
  el.folderPathHash = 'B';
  await pump(el);
  answers.get('B')!(body([job('B-file', 'B reason')]));
  await pump(el);
  answers.get('A')!(body([job('A-file', 'late A')]));
  await pump(el);
  expect(el.rows.map(row => row.pathHash)).toEqual(['B-file']);
  expect(el.shadowRoot!.textContent).not.toContain('late A');
});

it('a delayed failure for folder A says nothing under folder B', async () => {
  holdEveryListing();
  const el = await listFor('A');
  el.folderPathHash = 'B';
  await pump(el);
  answers.get('A')!(new Response('nope', { status: 503 }));
  await pump(el);
  expect(el.shadowRoot!.textContent).not.toContain('HTTP 503');
});

it('rapid switching settles on the last folder, whichever reply lands first', async () => {
  holdEveryListing();
  const el = await listFor('A');
  for (const folder of ['B', 'C', 'A', 'C']) {
    el.folderPathHash = folder;
    await pump(el);
  }
  answers.get('C')!(body([job('C-file', 'C reason')]));
  await pump(el);
  answers.get('A')!(body([job('A-file', 'late A')]));
  answers.get('B')!(body([job('B-file', 'late B')]));
  await pump(el);
  expect(el.rows.map(row => row.pathHash)).toEqual(['C-file']);
});
