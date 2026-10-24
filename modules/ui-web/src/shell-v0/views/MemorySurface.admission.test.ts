// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import type { JfButton } from '../components/Button.js';
import { confirmAsync } from '../components/ConfirmDialog.js';
vi.mock('../components/ConfirmDialog.js', () => ({ confirmAsync: vi.fn() }));
let surface: MemorySurface;
const original = { id: 'replace', content: 'Content A', kind: 'fact', actor: 'primary', createdAt: '2026-10-23T00:00:00Z' };
async function settle() { for (let i = 0; i < 30; i++) await Promise.resolve(); await surface.updateComplete; }
const forget = () => surface.shadowRoot!.querySelector<JfButton>('.list jf-button')!;
beforeEach(() => { vi.useFakeTimers(); vi.mocked(confirmAsync).mockReset().mockResolvedValue(true); });
afterEach(async () => {
  document.body.replaceChildren();
  await vi.runOnlyPendingTimersAsync();
  vi.useRealTimers(); vi.unstubAllGlobals();
});
it('R1 never replays a consented DELETE after a retry-safe refusal replaced the record', async () => {
  let memories = [original]; let attempts = 0;
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
    if (init?.method === 'DELETE') {
      if (++attempts === 1) {
        memories = [{ ...original, content: 'Content B' }];
        return new Response(JSON.stringify({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true }), { status: 429, headers: { 'Retry-After': '1' } });
      }
      memories = []; return new Response('{}');
    }
    return new Response(JSON.stringify({ memories, locked: false }));
  }));
  surface = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.append(surface); await settle();
  const attempt = forget().onActivate?.(); await settle();
  await vi.advanceTimersByTimeAsync(1200); await attempt; await settle();
  expect(attempts).toBe(1);
  expect(memories[0]?.content).toBe('Content B');
  expect(forget().label).toBe('Forget: Content B');
  expect(surface.mutationError).toContain('429');
  expect(surface.announcement).not.toContain('Forgotten');
  // The refusal is retryable through fresh user consent and its pre-delete identity read.
  await forget().onActivate?.(); await settle();
  expect(attempts).toBe(2);
  expect(memories).toHaveLength(0);
});
