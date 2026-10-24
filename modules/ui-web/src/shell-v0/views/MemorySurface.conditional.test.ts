// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import type { JfButton } from '../components/Button.js';
vi.mock('../components/ConfirmDialog.js', () => ({ confirmAsync: vi.fn(async () => true) }));
const first = { id: 'same', content: 'original', revision: '"' + 'a'.repeat(64) + '"' };
const replacement = { ...first, content: 'replacement', revision: '"' + 'b'.repeat(64) + '"' };
const list = (memories: unknown[] = [first], locked = false) => new Response(JSON.stringify({ memories, locked }));
async function settle(el: MemorySurface) {
  for (let i = 0; i < 30; ++i) await Promise.resolve();
  await el.updateComplete;
}
async function mount() {
  const el = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.append(el); await settle(el); return el;
}
const forget = (el: MemorySurface) => el.shadowRoot!.querySelector<JfButton>('.list jf-button')!;
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });

it.each([undefined, '', '*', 'abc', 'W/"' + 'a'.repeat(64) + '"'])('rejects a row with invalid revision %s without deleting', async revision => {
  const fetch = vi.fn().mockImplementation(() => list([{ ...first, revision }]));
  vi.stubGlobal('fetch', fetch);
  const el = await mount();
  expect(el.loadError).toContain('Could not read');
  expect(forget(el)).toBeNull();
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false);
});

it.each([true, false])('sends the listed revision once and announces the actual removal result (%s)', async removed => {
  const methods: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
    methods.push(init?.method ?? 'GET');
    if (init?.method === 'DELETE') {
      expect(new Headers(init.headers).get('If-Match')).toBe(first.revision);
      return new Response(JSON.stringify({ removed }));
    }
    return list(methods.length === 1 ? [first] : []);
  }));
  const el = await mount();
  await forget(el).onActivate?.(); await settle(el);
  expect(methods).toEqual(['GET', 'DELETE', 'GET']);
  expect(el.announcement).toContain(removed ? 'Forgotten.' : 'already gone');
  if (!removed) expect(el.announcement).not.toContain('Forgotten');
});

it.each(['readable', 'failed', 'locked', 'second replacement'])('keeps the 412 message with a %s re-read', async outcome => {
  let reads = 0;
  const fetch = vi.fn(async (_url, init) => {
    if (init?.method === 'DELETE') return new Response('{"errorCode":"INVALID_STATE"}', { status: 412 });
    if (++reads === 1) return list();
    if (outcome === 'failed') throw new TypeError('offline');
    if (outcome === 'locked') return list([], true);
    return list([{ ...replacement, content: outcome === 'second replacement' ? 'second replacement' : 'replacement' }]);
  });
  vi.stubGlobal('fetch', fetch);
  const el = await mount(); await forget(el).onActivate?.(); await settle(el);
  expect(reads).toBe(2);
  expect(el.mutationError).toBe('The current item was kept; the earlier attempt may have completed.');
  expect(el.announcement).not.toContain('Forgotten');
  const text = el.shadowRoot!.textContent!;
  if (outcome === 'failed') {
    expect(text).toContain('Memories are unavailable');
    expect(text).not.toContain('original');
  } else if (outcome === 'locked') {
    expect(text).toContain('Unlock');
    expect(text).not.toContain('original');
  } else expect(text).toContain(outcome === 'second replacement' ? 'second replacement' : 'replacement');
});

it('does not announce a delayed 412 after navigation', async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>(resolve => { finish = resolve; });
  let deletes = 0;
  vi.stubGlobal('fetch', vi.fn((_url, init) => {
    if (init?.method === 'DELETE') { ++deletes; return pending; }
    return Promise.resolve(list());
  }));
  const el = await mount(); const button = forget(el);
  const attempt = button.onActivate?.(); await settle(el);
  void button.onActivate?.();
  expect(deletes).toBe(1);
  el.remove(); document.body.append(el); await settle(el);
  finish(new Response('{}', { status: 412 }));
  await attempt; await settle(el);
  expect(el.mutationError).toBe('');
  expect(el.announcement).toBe('');
});
