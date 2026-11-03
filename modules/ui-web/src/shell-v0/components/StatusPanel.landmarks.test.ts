// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import axe from 'axe-core';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

let pill: StatusPill;
beforeEach(async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest();
  // The page frame owns the top-level landmarks, as the shell does.
  const banner = document.createElement('header'); banner.textContent = 'JustSearch';
  const main = document.createElement('main'); main.textContent = 'Window';
  const contentinfo = document.createElement('footer'); contentinfo.textContent = 'Frame footer';
  pill = new StatusPill(); banner.append(pill);
  document.body.append(banner, main, contentinfo);
  pill.open = true; await pill.updateComplete;
  await pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!.updateComplete;
});
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.unstubAllGlobals(); });

it('B5 X2 the open panel adds no page-level banner or contentinfo landmark', async () => {
  const panel = pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
  expect(panel.shadowRoot!.querySelectorAll('header, footer, main, nav, aside, [role="banner"], [role="contentinfo"]')).toHaveLength(0);
  const result = await axe.run(document, { runOnly: ['landmark-banner-is-top-level', 'landmark-contentinfo-is-top-level',
    'landmark-no-duplicate-banner', 'landmark-no-duplicate-contentinfo', 'landmark-unique'] });
  expect(result.violations.map(v => v.id)).toEqual([]);
});
