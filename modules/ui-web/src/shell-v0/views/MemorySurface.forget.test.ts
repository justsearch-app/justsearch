// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// WP5.3a, S53.3 (pass-2 amendment), S53.7: agent-adopted under delegated choice.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import type { JfButton } from '../components/Button.js';
import { confirmAsync } from '../components/ConfirmDialog.js';

vi.mock('../components/ConfirmDialog.js', () => ({ confirmAsync: vi.fn() }));

const fact = { id: 'm/1', kind: 'fact', content: 'My preferred language is German' };
const other = { id: 'm2', kind: 'fact', content: 'A second memory' };
const response = (memories = [fact, other], locked = false) =>
  new Response(JSON.stringify({ memories, locked }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

async function settle(el: MemorySurface): Promise<void> {
  for (let i = 0; i < 20; ++i) await Promise.resolve();
  await el.updateComplete;
  for (const button of el.shadowRoot!.querySelectorAll<JfButton>('jf-button')) {
    await button.updateComplete;
    const control = button.shadowRoot!.querySelector('jf-control');
    if (control) await (control as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete;
  }
}

async function mount(): Promise<MemorySurface> {
  const el = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.append(el);
  await settle(el);
  return el;
}

function forget(el: MemorySurface, index = 0): JfButton {
  return el.shadowRoot!.querySelectorAll<JfButton>('.list jf-button')[index]!;
}

const text = (el: MemorySurface) => el.shadowRoot!.textContent ?? '';

beforeEach(() => {
  vi.mocked(confirmAsync).mockReset().mockResolvedValue(true);
});

afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

it('names each Forget control with its memory (S53.3, S53.7)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response()));
  const el = await mount();
  expect(forget(el).label).toBe(`Forget: ${fact.content}`);
  expect(forget(el, 1).label).toBe(`Forget: ${other.content}`);
});

it('confirms the named memory and cancellation sends no delete (S53.3)', async () => {
  const fetch = vi.fn().mockResolvedValue(response());
  vi.stubGlobal('fetch', fetch);
  vi.mocked(confirmAsync).mockResolvedValue(false);
  const el = await mount();
  await forget(el).onActivate?.();
  await settle(el);
  expect(confirmAsync).toHaveBeenCalledWith(expect.objectContaining({
    message: expect.stringContaining(fact.content), confirmLabel: 'Forget', cancelLabel: 'Cancel',
  }));
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(text(el)).toContain(fact.content);
});

it('guards repeated activation during confirmation and deletion (S53.3)', async () => {
  const consent = deferred<boolean>();
  const deletion = deferred<Response>();
  vi.mocked(confirmAsync).mockReturnValue(consent.promise);
  const fetch = vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockReturnValueOnce(deletion.promise)
    .mockResolvedValue(response([other]));
  vi.stubGlobal('fetch', fetch);
  const el = await mount();
  const button = forget(el);
  const first = button.onActivate?.();
  void button.onActivate?.();
  await settle(el);
  const confirmations = vi.mocked(confirmAsync).mock.calls.length;
  const beforeConsent = fetch.mock.calls.length;
  consent.resolve(true);
  await settle(el);
  void button.onActivate?.();
  await settle(el);
  const deletes = fetch.mock.calls.filter(([, init]) => init?.method === 'DELETE').length;
  deletion.resolve(new Response('{}'));
  await first;
  await settle(el);
  expect(confirmations).toBe(1);
  expect(beforeConsent).toBe(1);
  expect(deletes).toBe(1);
  expect(text(el)).not.toContain(fact.content);
});

it('announces confirmed removal and focuses the next useful control (S53.3, S53.7)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response())
    .mockResolvedValueOnce(new Response('{}')).mockResolvedValue(response([other])));
  const el = await mount();
  forget(el).focus();
  await forget(el).onActivate?.();
  await settle(el);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('Forgotten');
  expect(el.shadowRoot!.activeElement).toBe(forget(el));
});

it('focuses the teaching input after forgetting the last memory (S53.3)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response([fact])).mockResolvedValueOnce(response([fact]))
    .mockResolvedValueOnce(new Response('{}')).mockResolvedValue(response([])));
  const el = await mount();
  forget(el).focus();
  await forget(el).onActivate?.();
  await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(el.shadowRoot!.querySelector('input.draft'));
});

it('reconciles a lost delete response with a confirmed absence (S53.3 amendment)', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockRejectedValueOnce(new TypeError('lost response'))
    .mockResolvedValue(response([other]));
  vi.stubGlobal('fetch', fetch);
  const el = await mount();
  await forget(el).onActivate?.();
  await settle(el);
  expect(text(el)).not.toContain(fact.content);
  expect(text(el)).toContain('response was lost');
  expect(text(el)).toContain('confirmed');
  expect(text(el)).not.toContain('nothing was forgotten');
});

it('reconciles a lost delete response with a still-present memory (S53.3 amendment)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockRejectedValueOnce(new TypeError('lost response'))
    .mockResolvedValue(response()));
  const el = await mount();
  await forget(el).onActivate?.();
  await settle(el);
  expect(text(el)).toContain(fact.content);
  expect(text(el)).toContain('still in memory');
  expect(text(el)).not.toContain('nothing was forgotten');
});

it('leaves an unknown outcome when reconciliation fails and offers a fresh read (S53.3 amendment, S53.4)', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockRejectedValueOnce(new TypeError('lost response'))
    .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(response([other]));
  vi.stubGlobal('fetch', fetch);
  const el = await mount();
  await forget(el).onActivate?.();
  await settle(el);
  const message = text(el);
  const retry = [...el.shadowRoot!.querySelectorAll<JfButton>('jf-button')].find(b => b.label === 'Reload memories');
  expect(message).toContain('unknown');
  expect(message).not.toContain('nothing was forgotten');
  expect(message).not.toContain(fact.content);
  expect(retry).toBeDefined();
  await retry!.onActivate?.();
  await settle(el);
  expect(text(el)).toContain(other.content);
  expect(text(el)).not.toContain(fact.content);
});

it('cannot infer absence from a locked reconciliation (S53.3 amendment, S53.4)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockRejectedValueOnce(new TypeError('lost response'))
    .mockResolvedValue(response([], true)));
  const el = await mount();
  await forget(el).onActivate?.();
  await settle(el);
  expect(text(el)).toContain('unknown');
  expect(text(el)).toContain('Unlock');
  expect(text(el)).not.toContain('nothing was forgotten');
});

it('cannot infer absence from a malformed reconciliation (S53.3 amendment, S53.4)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockRejectedValueOnce(new TypeError('lost response'))
    .mockResolvedValue(new Response('{"locked":false}')));
  const el = await mount();
  await forget(el).onActivate?.();
  await settle(el);
  expect(text(el)).toContain('unknown');
  expect(text(el)).toContain('Memories are unavailable');
  expect(text(el)).not.toContain('No learned memory yet');
});

it('keeps a refused deletion retryable by keyboard after refreshing the list (S53.3, S53.7)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response())
    .mockResolvedValueOnce(new Response('{}', { status: 500 })).mockResolvedValue(response()));
  const el = await mount();
  forget(el).focus();
  await forget(el).onActivate?.();
  await settle(el);
  expect(text(el)).toContain(fact.content);
  expect(text(el)).toContain('server said 500');
  expect(el.shadowRoot!.activeElement).toBe(forget(el));
});

it('does not send a delete when confirmation belongs to a previous visit (S53.3)', async () => {
  const consent = deferred<boolean>();
  vi.mocked(confirmAsync).mockReturnValue(consent.promise);
  const fetch = vi.fn().mockResolvedValue(response());
  vi.stubGlobal('fetch', fetch);
  const el = await mount();
  const attempt = forget(el).onActivate?.();
  el.remove();
  document.body.append(el);
  await settle(el);
  consent.resolve(true);
  await attempt;
  await settle(el);
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0);
});

it('does not let a delayed deletion change a newer visit (S53.3 amendment, S53.4)', async () => {
  const deletion = deferred<Response>();
  const fetch = vi.fn().mockResolvedValueOnce(response()).mockResolvedValueOnce(response()).mockReturnValueOnce(deletion.promise)
    .mockResolvedValue(response([other]));
  vi.stubGlobal('fetch', fetch);
  const el = await mount();
  const attempt = forget(el).onActivate?.();
  await settle(el);
  el.remove();
  document.body.append(el);
  await settle(el);
  const calls = fetch.mock.calls.length;
  deletion.resolve(new Response('{}', { status: 500 }));
  await attempt;
  await settle(el);
  expect(fetch).toHaveBeenCalledTimes(calls);
  expect(text(el)).toContain(other.content);
  expect(el.mutationError).toBe('');
});

it('keeps Forget guarded when an earlier visit finishes Remember (S53.3, N53.2)', async () => {
  const save = deferred<Response>();
  const consent = deferred<boolean>();
  vi.mocked(confirmAsync).mockReturnValue(consent.promise);
  vi.stubGlobal('fetch', vi.fn((_url, init) =>
    init?.method === 'POST' ? save.promise : Promise.resolve(response())));
  const el = await mount();
  el.rememberDraft = 'earlier visit draft';
  await settle(el);
  const remember = el.shadowRoot!.querySelector<JfButton>('jf-button[label="Remember"]')!;
  void remember.onActivate?.();
  await settle(el);
  el.remove(); document.body.append(el);
  await settle(el);
  el.rememberDraft = 'newer visit draft';
  const first = forget(el).onActivate?.();
  save.resolve(new Response('{}'));
  await settle(el);
  const second = forget(el).onActivate?.();
  const confirmations = vi.mocked(confirmAsync).mock.calls.length;
  consent.resolve(false);
  await Promise.all([first, second]);
  await settle(el);
  expect(confirmations).toBe(1);
  expect(el.rememberDraft).toBe('newer visit draft');
});
