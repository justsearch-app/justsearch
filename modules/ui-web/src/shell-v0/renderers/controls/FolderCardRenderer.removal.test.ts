// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './FolderCardRenderer.js';
import type { FolderCardRenderer } from './FolderCardRenderer.js';
afterEach(() => document.body.replaceChildren());
it('exposes removal progress and blocks all actions only on the affected card', async () => {
  const el = document.createElement('jf-folder-card') as FolderCardRenderer;
  el.visible = true; el.enabled = true; el.uischema = { type: 'Control' }; el.onChange = () => {};
  el.data = [{ pathHash: 'a', displayPath: '/docs', metaText: 'Removing folder…', removalBusy: true, failed: 2 },
    { pathHash: 'b', displayPath: '/other', metaText: 'No indexable files' }];
  const remove = vi.fn(); el.addEventListener('jf-folder-card-remove', remove);
  document.body.append(el); await el.updateComplete;
  for (const toggle of el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.folder-toggle')) toggle.click();
  await el.updateComplete;
  const cards = el.shadowRoot!.querySelectorAll('.card');
  expect(cards[0]!.getAttribute('aria-busy')).toBe('true');
  const controls = cards[0]!.querySelectorAll<HTMLElement & { disabled: boolean; onActivate: () => void }>('jf-button');
  expect([...controls].every(control => control.disabled)).toBe(true);
  controls[controls.length - 1]!.onActivate(); expect(remove).not.toHaveBeenCalled();
  const other = cards[1]!.querySelector<HTMLElement & { disabled: boolean; onActivate: () => void }>('jf-button')!;
  expect(other.disabled).toBe(false); other.onActivate(); expect(remove).toHaveBeenCalledTimes(1);
});
