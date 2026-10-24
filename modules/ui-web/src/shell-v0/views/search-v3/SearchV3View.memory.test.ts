// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// WP5.3a S53.3 / DM1: agent-adopted under delegated choice.
import { afterEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Palette } from './Sv3Palette.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../../controllers/navigateRequest.js';

afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  localStorage.clear();
});

it('opens the existing Memory surface from a keyboard palette command (S53.3, DM1)', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')));
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view);
  await view.updateComplete;
  const palette = view.shadowRoot!.querySelector<Sv3Palette>('jf-sv3-palette')!;
  await palette.show();
  const input = palette.shadowRoot!.querySelector<HTMLInputElement>('input')!;
  input.value = 'memory';
  input.dispatchEvent(new Event('input'));
  await palette.updateComplete;
  const command = palette.shadowRoot!.querySelector('[role="option"]');
  const navigation: unknown[] = [];
  const listener = (event: Event) => navigation.push((event as CustomEvent).detail);
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listener);
  try {
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await palette.updateComplete;
    expect(command?.textContent ?? '').toContain('Memory');
    expect(navigation).toEqual([{ surfaceId: 'core.memory-surface' }]);
    expect(palette.open).toBe(false);
  } finally {
    document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listener);
  }
});
