// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * L2 (shell slice 2) — the panel's plugin row. One item always shows directly, wrapping inside the
 * row with its provenance; "… N more" collects only items that do not fit after it. happy-dom has no
 * layout, so the widths the overflow controller measures (`offsetWidth` of each item,
 * `clientWidth` of the row) are stubbed to a row narrower than its first item.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { registerStatusBarItem, __resetForTest as resetRegistry } from '../commands/StatusBarRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

let pill: StatusPill;
const ITEM_WIDTH = 520;
const ROW_WIDTH = 380;

function panel(): StatusPanel { return pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!; }
async function frames(): Promise<void> {
  for (let i = 0; i < 20; i++) {
    await new Promise<void>(r => setTimeout(r, 20));
    await panel().updateComplete;
  }
}
function item(id: string): void {
  registerStatusBarItem({ id, source: 'plugin', provenance: CORE_PROVENANCE, priority: 1, position: 'right',
    render: () => `${id}: a status line that is wider than the panel's plugin row by itself` });
}

beforeEach(async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); resetRegistry();
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('plugin') ? ITEM_WIDTH : 0;
  });
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('plugins') ? ROW_WIDTH : 0;
  });
  pill = new StatusPill();
  document.body.append(pill);
});
afterEach(() => { document.body.replaceChildren(); resetRegistry(); __resetAiStateForTest(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('L2 a single item wider than the row shows directly, with its provenance, and no "… 1 more"', async () => {
  item('plugin.wide');
  pill.open = true;
  await frames();
  const row = panel().shadowRoot!.querySelector('[aria-label="Plugin status"]')!;
  expect([...row.querySelectorAll('[data-plugin-item]')].map(el => el.getAttribute('data-plugin-item'))).toEqual(['plugin.wide']);
  expect(row.querySelector('[data-plugin-item] jf-provenance-chip')).not.toBeNull();
  expect(row.querySelector('button')).toBeNull();
});

it('L2 with more items than fit, the first shows and "… N more" counts only the rest', async () => {
  item('plugin.one'); item('plugin.two'); item('plugin.three');
  pill.open = true;
  await frames();
  const row = panel().shadowRoot!.querySelector('[aria-label="Plugin status"]')!;
  expect([...row.querySelectorAll('[data-plugin-item]')].map(el => el.getAttribute('data-plugin-item'))).toEqual(['plugin.one']);
  const more = row.querySelector('button')!;
  expect(more.textContent!.trim()).toBe('… 2 more');
  more.click();
  await panel().updateComplete;
  expect(row.querySelectorAll('[data-plugin-item]')).toHaveLength(3);
});
