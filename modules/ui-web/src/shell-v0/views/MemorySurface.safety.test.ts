// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import type { JfButton } from '../components/Button.js';
import { confirmAsync } from '../components/ConfirmDialog.js';

vi.mock('../components/ConfirmDialog.js', () => ({ confirmAsync: vi.fn() }));
const initial = { id: 'one', content: 'Original memory', kind: 'fact', actor: 'primary', createdAt: '2026-10-23T00:00:00Z' };
let memories = [initial];
let deletes: string[];
let loseDelete = false;
let failRead = false;
let retainOnLoss = false;
let surface: MemorySurface;

async function settle() {
  for (let i = 0; i < 30; ++i) await Promise.resolve();
  await surface.updateComplete;
  for (const atom of surface.shadowRoot!.querySelectorAll<JfButton>('jf-button')) {
    await atom.updateComplete;
    const control = atom.shadowRoot!.querySelector('jf-control');
    if (control) await (control as MemorySurface).updateComplete;
  }
}
const forget = () => surface.shadowRoot!.querySelector<JfButton>('.list jf-button')!;
const reload = () => surface.shadowRoot!.querySelector<JfButton>('jf-button[label="Reload memories"]')!;

beforeEach(async () => {
  memories = [initial]; deletes = []; loseDelete = failRead = retainOnLoss = false;
  vi.mocked(confirmAsync).mockReset().mockResolvedValue(true);
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
    if (init?.method === 'DELETE') {
      deletes.push(String(_url));
      if (!retainOnLoss) memories = [];
      if (loseDelete) { failRead = true; throw new TypeError('response lost after request'); }
      return new Response('{}');
    }
    return new Response(JSON.stringify({ memories, locked: false }), { status: failRead ? 503 : 200 });
  }));
  surface = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.append(surface);
  await settle();
});
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); });

it('F1 refuses repeated keydown activation of a Forget trigger', async () => {
  const atom = forget();
  const control = atom.shadowRoot!.querySelector('jf-control')!;
  const button = control.shadowRoot!.querySelector('button')!;
  const repeated = new KeyboardEvent('keydown', { key: 'Enter', repeat: true, bubbles: true, composed: true, cancelable: true });
  button.dispatchEvent(repeated);
  if (!repeated.defaultPrevented) button.click();
  await settle();
  expect(repeated.defaultPrevented).toBe(true);
  expect(confirmAsync).not.toHaveBeenCalled();
});

it.each(['content', 'createdAt'])('F2 refuses consent after the memory changes its %s', async field => {
  let consent!: (value: boolean) => void;
  vi.mocked(confirmAsync).mockReturnValueOnce(new Promise(resolve => { consent = resolve; }));
  const attempt = forget().onActivate?.();
  memories = [{ ...initial, [field]: field === 'content' ? 'Replacement memory' : '2026-10-23T01:00:00Z' }];
  consent(true); await attempt; await settle();
  expect(deletes).toHaveLength(0);
  expect(surface.shadowRoot!.textContent).toContain('changed');
  expect(forget().label).toContain(memories[0]!.content);
  // Only fresh consent to the newly listed record can now delete it.
  await forget().onActivate?.(); await settle();
  expect(deletes).toHaveLength(1);
});

it('F2 does not delete an item that vanished during confirmation', async () => {
  let consent!: (value: boolean) => void;
  vi.mocked(confirmAsync).mockReturnValueOnce(new Promise(resolve => { consent = resolve; }));
  const attempt = forget().onActivate?.();
  memories = [];
  consent(true); await attempt; await settle();
  expect(deletes).toHaveLength(0);
  expect(surface.announcement).toContain('no longer');
});

it.each([false, true])('F3 manual reconciliation announces and focuses its resolved outcome (present=%s)', async present => {
  loseDelete = true; retainOnLoss = present;
  await forget().onActivate?.(); await settle();
  expect(surface.mutationError).toContain('unknown');
  failRead = false;
  reload().focus();
  await reload().onActivate?.(); await settle();
  expect(surface.announcement).toContain(present ? 'still in memory' : 'confirmed this item is absent');
  expect(surface.shadowRoot!.activeElement).toBe(present ? forget() : surface.shadowRoot!.querySelector('input.draft'));
});
