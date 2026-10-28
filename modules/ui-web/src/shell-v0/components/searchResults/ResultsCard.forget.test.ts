// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it } from 'vitest';
import './ResultsCard.js';
import type { ResultsCard, CardSnapshot } from './ResultsCard.js';
import type { ContextMenu } from '../ContextMenu.js';
import { registerSearchResultActions, __resetSearchResultActionsForTest } from '../../commands/searchResultActions.js';
import { __resetForTest } from '../../commands/ContextActionRegistry.js';

const path = '/docs/report\\[1].txt ';
const snapshot: CardSnapshot = { query: 'report', results: [{ id: path, path, title: 'Report' }],
  matchCount: 1, totalHits: 1, facetsTruncated: false, isSearching: false, processingTimeMs: 1, error: null };

async function mount(supported: boolean) {
  const card = document.createElement('jf-results-card') as ResultsCard & { forgetAvailable: boolean };
  card.snapshot = snapshot;
  card.forgetAvailable = supported;
  document.body.appendChild(card);
  await card.updateComplete;
  const row = card.shadowRoot?.querySelector<HTMLElement>('[data-testid="search-result-row"]');
  expect(row).toBeTruthy();
  return { card, row: row! };
}

beforeEach(() => {
  __resetForTest(); __resetSearchResultActionsForTest(); registerSearchResultActions();
});
afterEach(() => {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  document.body.innerHTML = '';
  __resetForTest(); __resetSearchResultActionsForTest();
});

it.each(['pointer', 'ContextMenu', 'Shift+F10'])('S56.1 opens the real forget menu by %s and emits the exact selected target', async (activation) => {
  const { card, row } = await mount(true);
  const observed: unknown[] = [];
  card.addEventListener('card-forget', (event) => observed.push((event as CustomEvent).detail));
  expect(row.tabIndex).toBe(0);
  row.focus();
  if (activation === 'pointer') row.dispatchEvent(new MouseEvent('contextmenu', { clientX: 10, clientY: 12, bubbles: true, cancelable: true }));
  else row.dispatchEvent(new KeyboardEvent('keydown', { key: activation === 'ContextMenu' ? 'ContextMenu' : 'F10',
    shiftKey: activation === 'Shift+F10', bubbles: true, cancelable: true }));
  const menu = document.querySelector('jf-context-menu') as ContextMenu;
  expect(menu, 'the keyboard gesture must reach the existing menu').toBeTruthy();
  await menu.updateComplete;
  const choices = Array.from(menu.shadowRoot?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? []);
  expect(choices.map((choice) => choice.textContent?.trim())).toContain('Forget this file');
  choices.find((choice) => choice.textContent?.trim() === 'Forget this file')?.click();
  await Promise.resolve();
  expect(observed).toEqual([{ id: path, path, scope: 'FILE', invoker: row }]);
});

it('S56.6 an unsupported host retains its ordinary pointer actions without offering forget', async () => {
  const { row } = await mount(false);
  row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  const menu = document.querySelector('jf-context-menu') as ContextMenu;
  await menu.updateComplete;
  const labels = Array.from(menu.shadowRoot?.querySelectorAll('[role="menuitem"]') ?? [], (item) => item.textContent?.trim());
  expect(labels).toContain('Open document');
  expect(labels).toContain('Copy path');
  expect(labels).not.toContain('Forget this file');
  expect(labels).not.toContain('Forget its folder');
});
