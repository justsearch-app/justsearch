// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';

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
    finishReload(response(200, { memories: [{ id: 'm1', kind: 'fact', content: 'recovered memory' }] }));
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
    finishReload(response(200, { memories: [{ id: 'm2', kind: 'fact', content: 'fresh result' }] }));
    await settle(surface);
    expect(surface.loading).toBe(false);
    expect(surface.shadowRoot?.textContent).toContain('fresh result');
  });
});
