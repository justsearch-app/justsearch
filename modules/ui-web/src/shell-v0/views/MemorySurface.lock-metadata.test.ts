// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// S53.3 amendment / S53.4: agent choice, adopted under the user's delegation.
import { afterEach, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import type { JfButton } from '../components/Button.js';
vi.mock('../components/ConfirmDialog.js', () => ({ confirmAsync: vi.fn(async () => true) }));
const fact = { id: 'lock-metadata', kind: 'fact', content: 'Retain this fact until absence is confirmed' };
const readable = () => new Response(JSON.stringify({ memories: [fact], locked: false }));
const malformed = (locked: unknown, memories: unknown = []) => new Response(JSON.stringify({ memories, locked }));
const invalidLocks = ['true', 'false', null, 0, 1, [], {}, undefined];
async function settle(el: MemorySurface) {
  for (let i = 0; i < 30; ++i) await Promise.resolve();
  await el.updateComplete;
  for (const button of el.shadowRoot!.querySelectorAll<JfButton>('jf-button')) {
    await button.updateComplete;
    const control = button.shadowRoot!.querySelector('jf-control');
    if (control) await (control as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete;
  }
}
async function mount() {
  const el = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.append(el); await settle(el); return el;
}
const forget = (el: MemorySurface) => el.shadowRoot!.querySelectorAll<JfButton>('.list jf-button')[0];
const reload = (el: MemorySurface) => el.shadowRoot!.querySelector<JfButton>('jf-button[label="Reload memories"]')!;
const text = (el: MemorySurface) => el.shadowRoot!.textContent ?? '';
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });
it.each(invalidLocks.map(locked => ({ locked })))('does not confirm lost-response absence with invalid locked=$locked', async ({ locked }) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(readable).mockImplementationOnce(readable)
    .mockRejectedValueOnce(new TypeError('lost response')).mockImplementation(() => malformed(locked)));
  const el = await mount(); await forget(el)!.onActivate?.(); await settle(el);
  expect(text(el)).toContain('outcome is unknown');
  expect(el.announcement).toBe('');
  expect(el.loadError).toContain('Could not read');
  expect(reload(el)).not.toBeNull();
  expect(el.shadowRoot!.activeElement).toBe(reload(el));
});
it.each(invalidLocks.map(locked => ({ locked })))('does not trust initial presence with invalid locked=$locked', async ({ locked }) => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => malformed(locked, [fact])));
  const el = await mount();
  expect(el.loadError).toContain('Could not read');
  expect(forget(el)).toBeUndefined();
  expect(text(el)).not.toContain(fact.content);
  expect(el.shadowRoot!.querySelector<HTMLInputElement>('input.draft')!.disabled).toBe(true);
});
it.each(invalidLocks.map(locked => ({ locked })))('does not send DELETE after a consent read with invalid locked=$locked', async ({ locked }) => {
  const fetch = vi.fn().mockImplementationOnce(readable).mockImplementation(() => malformed(locked, [fact]));
  vi.stubGlobal('fetch', fetch);
  const el = await mount(); await forget(el)!.onActivate?.(); await settle(el);
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0);
  expect(el.loadError).toContain('Could not read');
  expect(el.announcement).toBe('');
});
it.each([[], [fact]].map(memories => ({ memories })))('keeps manual reconciliation unknown for malformed lock metadata and memories=$memories', async ({ memories }) => {
  const fetch = vi.fn().mockImplementationOnce(readable).mockImplementationOnce(readable)
    .mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce(new Response('{}', { status: 503 }))
    .mockImplementationOnce(() => malformed('true', memories)).mockImplementation(readable);
  vi.stubGlobal('fetch', fetch);
  const el = await mount(); await forget(el)!.onActivate?.(); await settle(el);
  expect(reload(el)).not.toBeNull();
  await reload(el).onActivate?.(); await settle(el);
  expect(text(el)).toContain('outcome is unknown');
  expect(el.announcement).toBe('');
  expect(el.loadError).toContain('Could not read');
  expect(el.shadowRoot!.activeElement).toBe(reload(el));
  await reload(el).onActivate?.(); await settle(el);
  expect(el.announcement).toContain('still in memory');
  expect(forget(el)).toBeDefined();
  expect(el.shadowRoot!.activeElement).toBe(forget(el));
});
