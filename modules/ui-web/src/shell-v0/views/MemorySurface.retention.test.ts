// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import type { JfButton } from '../components/Button.js';
import type { Control } from '../components/Control.js';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function rememberControl(surface: MemorySurface): Promise<ShadowRoot> {
  const button = surface.shadowRoot!.querySelector<JfButton>('jf-button[label="Remember"]')!;
  await button.updateComplete;
  const control = button.shadowRoot!.querySelector<Control>('jf-control')!;
  await control.updateComplete;
  return control.shadowRoot!;
}

async function leaveAndReturn(surface: MemorySurface): Promise<void> {
  await settle(surface);
  const input = surface.shadowRoot!.querySelector<HTMLInputElement>('input.draft')!;
  input.value = 'unsaved fact';
  input.dispatchEvent(new Event('input'));
  surface.remove();
  document.body.appendChild(surface);
  await settle(surface);
}

function response(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

async function settle(surface: MemorySurface): Promise<void> {
  for (let i = 0; i < 6; i++) await Promise.resolve();
  await surface.updateComplete;
  for (let i = 0; i < 6; i++) await Promise.resolve();
  await surface.updateComplete;
}

function mount(): MemorySurface {
  const surface = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.appendChild(surface);
  return surface;
}

afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

describe('MemorySurface retained navigation', () => {
  it('keeps fresh memories and Remember enabled after a stale failure', async () => {
    const old = deferred<Response>();
    const fresh = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise));
    const surface = mount();
    await leaveAndReturn(surface);
    fresh.resolve(response(200, { locked: false, memories: [{ id: 'fresh', kind: 'fact', content: 'current memory' }] }));
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('current memory');

    old.resolve(response(503, {}));
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('current memory');
    expect(surface.shadowRoot?.textContent).not.toContain('Memories are unavailable');
    const control = await rememberControl(surface);
    expect(control.querySelector('button')?.disabled).toBe(false);
    expect(control.querySelector('button')?.getAttribute('aria-disabled')).not.toBe('true');
    expect(control.querySelector('[role="tooltip"]')).toBeNull();
  });

  it('keeps the spinner and Remember loading reason after a stale rejection during a fresh load', async () => {
    const old = deferred<Response>();
    const fresh = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise));
    const surface = mount();
    await leaveAndReturn(surface);

    old.reject(new TypeError('old connection failed'));
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('Loading memories');
    expect(surface.shadowRoot?.textContent).not.toContain('Memories are unavailable');
    const control = await rememberControl(surface);
    expect(control.querySelector('button')?.getAttribute('aria-disabled')).toBe('true');
    expect(control.querySelector('[role="tooltip"]')?.textContent).toContain('Loading memories');

    fresh.resolve(response(200, { locked: false, memories: [{ id: 'fresh', kind: 'fact', content: 'fresh result' }] }));
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('fresh result');
    expect(surface.shadowRoot?.textContent).not.toContain('Loading memories');
    expect((await rememberControl(surface)).querySelector('button')?.getAttribute('aria-disabled')).not.toBe('true');
  });

  it('keeps the fresh error and Remember refusal after a stale success finishes reading its body', async () => {
    const body = deferred<unknown>();
    const fresh = deferred<Response>();
    const old = response(200, {});
    vi.spyOn(old, 'json').mockReturnValueOnce(body.promise);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(old).mockReturnValueOnce(fresh.promise));
    const surface = mount();
    await leaveAndReturn(surface);
    expect(old.json).toHaveBeenCalledOnce();
    fresh.resolve(response(503, {}));
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('Memories are unavailable');

    body.resolve({ memories: [{ id: 'stale', kind: 'fact', content: 'outdated memory' }], locked: false });
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('Memories are unavailable');
    expect(surface.shadowRoot?.textContent).not.toContain('outdated memory');
    expect(surface.shadowRoot?.textContent).not.toContain('Loading memories');
    const control = await rememberControl(surface);
    expect(control.querySelector('button')?.getAttribute('aria-disabled')).toBe('true');
    expect(control.querySelector('[role="tooltip"]')?.textContent).toContain('Memories are unavailable');
  });

  it('keeps a visible load error until leaving, clears it on hide, and reloads on return', async () => {
    let finishReload!: (value: Response) => void;
    const reload = new Promise<Response>((resolve) => { finishReload = resolve; });
    const fetch = vi.fn()
      .mockResolvedValueOnce(response(503, { errorCode: 'MEMORIES_UNAVAILABLE' }))
      .mockReturnValueOnce(reload);
    vi.stubGlobal('fetch', fetch);
    const surface = mount();
    await settle(surface);
    expect(surface.shadowRoot?.textContent).toContain('Memories are unavailable');
    await surface.updateComplete;
    expect(surface.loadError).not.toBe('');
    surface.rememberDraft = 'keep my unsaved fact';

    surface.remove();
    expect(surface.loadError).toBe('');
    expect(surface.loading).toBe(false);
    expect(surface.rememberDraft).toBe('keep my unsaved fact');

    document.body.appendChild(surface);
    await settle(surface);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(surface.loading).toBe(true);
    expect(surface.shadowRoot?.textContent).toContain('Loading memories');
    expect(surface.loadError).toBe('');
    expect(surface.shadowRoot?.textContent).not.toContain('Memories are unavailable');
    finishReload(response(200, { locked: false, memories: [{ id: 'm1', kind: 'fact', content: 'recovered memory' }] }));
    await settle(surface);
    expect(surface.loading).toBe(false);
    expect(surface.shadowRoot?.textContent).toContain('recovered memory');
  });

  it('clears an in-flight spinner on hide and starts a fresh load on return', async () => {
    let finishReload!: (value: Response) => void;
    const reload = new Promise<Response>((resolve) => { finishReload = resolve; });
    const fetch = vi.fn()
      .mockReturnValueOnce(new Promise<Response>(() => {}))
      .mockReturnValueOnce(reload);
    vi.stubGlobal('fetch', fetch);
    const surface = mount();
    await settle(surface);
    expect(surface.loading).toBe(true);

    surface.remove();
    expect(surface.loading).toBe(false);
    expect(surface.loadError).toBe('');

    document.body.appendChild(surface);
    await settle(surface);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(surface.loading).toBe(true);
    finishReload(response(200, { locked: false, memories: [{ id: 'm2', kind: 'fact', content: 'fresh result' }] }));
    await settle(surface);
    expect(surface.loading).toBe(false);
    expect(surface.shadowRoot?.textContent).toContain('fresh result');
  });
});
