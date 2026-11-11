// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// WP5.3a S53.3 / DM1: agent-adopted under delegated choice. S6 SP7: the command lives in the app's one palette.
import { afterEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import '../../commands/CommandPalette.js';
import type { SearchV3View } from './SearchV3View.js';
import type { CommandPalette } from '../../commands/CommandPalette.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../../controllers/navigateRequest.js';

afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  localStorage.clear();
});

const flush = async (): Promise<void> => { for (let i = 0; i < 40; i++) await Promise.resolve(); };

it('SP7 opens the existing Memory surface from the app palette’s "Open AI Brain › Memory" (S53.3, DM1)', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}')));
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  const palette = document.createElement('jf-command-palette') as CommandPalette;
  document.body.append(view, palette);
  await view.updateComplete;
  palette.show();
  await palette.updateComplete;
  const input = palette.shadowRoot!.querySelector<HTMLInputElement>('input')!;
  input.value = 'memory';
  input.dispatchEvent(new Event('input'));
  await palette.updateComplete;
  const command = palette.shadowRoot!.querySelector('[role="option"][aria-selected="true"]');
  const navigation: unknown[] = [];
  const listener = (event: Event) => navigation.push((event as CustomEvent).detail);
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listener);
  try {
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flush();
    expect(command?.textContent ?? '').toContain('Open AI Brain › Memory');
    expect(navigation).toEqual([{ surfaceId: 'core.memory-surface' }]);
    expect(palette.open).toBe(false);
  } finally {
    document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listener);
  }
});
