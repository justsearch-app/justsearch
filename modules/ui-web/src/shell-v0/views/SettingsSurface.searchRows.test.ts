// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import { takeIndexingRuleFocus } from '../router/indexingRuleIntent.js';
import { takeReadingMemoryFocus } from '../router/readingMemoryIntent.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';

type Surface = HTMLElement & { host_: unknown; activeCategory: string; updateComplete: Promise<unknown> };
type Nav = HTMLElement & { updateComplete: Promise<unknown> };
let scroll: HTMLElement[];
beforeEach(() => {
  __resetUserStateForTest(); __resetUserConfigForTest(); __resetThemeStateForTest(); __resetSessionRegistryForTest(); __resetForTest();
  __seedForTest({ 'settings.section.accessibility': 'Accessibility', 'settings.section.theme': 'Theme',
    'settings.section.indexing': 'Indexing', 'settings.section.context-window': 'Reading memory' });
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ui: {} }), { status: 200 })));
  scroll = [];
  vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(function (this: HTMLElement) { scroll.push(this); });
  takeIndexingRuleFocus(); takeReadingMemoryFocus();
});
afterEach(() => {
  document.body.innerHTML = ''; takeIndexingRuleFocus(); takeReadingMemoryFocus();
  __resetUserStateForTest(); __resetForTest(); vi.restoreAllMocks(); vi.unstubAllGlobals();
});
async function mount(): Promise<Surface> {
  const el = document.createElement('jf-settings-surface') as Surface;
  el.host_ = createMockHostApi();
  document.body.append(el);
  await vi.waitFor(() => expect(el.shadowRoot?.querySelector('jf-settings-nav')?.shadowRoot?.querySelector('input')).toBeTruthy());
  await el.updateComplete;
  return el;
}
async function choose(el: Surface, query: string, key: string): Promise<void> {
  const nav = el.shadowRoot!.querySelector('jf-settings-nav') as Nav;
  const input = nav.shadowRoot!.querySelector('input')!;
  input.value = query;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await nav.updateComplete;
  const row = nav.shadowRoot!.querySelector<HTMLButtonElement>(`button.search-result-row[data-settings-anchor="${key}"]`);
  expect(row).toBeTruthy();
  row!.focus(); row!.click();
  await el.updateComplete; await nav.updateComplete;
}

it('SR2 a clicked control-word result selects the section, focuses and marks the scrolled row', async () => {
  const el = await mount();
  await choose(el, 'reduce motion', 'accessibility');
  const row = el.shadowRoot!.querySelector<HTMLElement>('.settings-content-inner [data-settings-anchor="accessibility"]')!;
  expect(el.activeCategory).toBe('appearance');
  expect(scroll).toContain(row);
  expect(el.shadowRoot!.activeElement).toBe(row);
  expect(row.classList.contains('arrival-highlight')).toBe(true);
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
  expect(row.classList.contains('arrival-highlight')).toBe(false);
});

it('SR2 opens containing details before scrolling, and the next pointer clears the mark', async () => {
  const el = await mount();
  el.activeCategory = 'appearance'; await el.updateComplete;
  const row = el.shadowRoot!.querySelector<HTMLElement>('.settings-content-inner [data-settings-anchor="theme"]')!;
  const outer = document.createElement('details');
  const inner = document.createElement('details');
  row.replaceWith(outer); outer.append(inner); inner.append(row);
  const nav = el.shadowRoot!.querySelector('jf-settings-nav')!;
  nav.dispatchEvent(new CustomEvent('search-select', { detail: { categoryId: 'appearance', sectionKey: 'theme' }, bubbles: true, composed: true }));
  await el.updateComplete;
  expect(outer.open).toBe(true); expect(inner.open).toBe(true);
  expect(scroll).toContain(row);
  expect(el.shadowRoot!.activeElement).toBe(row);
  expect(row.classList.contains('arrival-highlight')).toBe(true);
  document.dispatchEvent(new Event('pointerdown', { bubbles: true }));
  expect(row.classList.contains('arrival-highlight')).toBe(false);
});

it.each([
  ['when to index', 'indexing', 'core.library-surface', undefined],
  ['context window', 'context-window', 'core.brain-surface', { tab: 'runtime' }],
] as const)('SR3 %s opens its real destination with an arrival request', async (query, key, surfaceId, state) => {
  const el = await mount();
  const navigate = vi.fn();
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate);
  try {
    await choose(el, query, key);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect((navigate.mock.calls[0]![0] as CustomEvent).detail).toEqual({ surfaceId, ...(state ? { state } : {}) });
    if (key === 'indexing') expect(takeIndexingRuleFocus()).toBe('hours');
    else expect(takeReadingMemoryFocus()).toBe(true);
  } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, navigate); }
});

it('SR2 removing Settings clears the previous arrival mark', async () => {
  const el = await mount();
  await choose(el, 'theme', 'theme');
  const marked = el.shadowRoot!.querySelector('.arrival-highlight');
  expect(marked).toBeTruthy();
  el.remove();
  expect(marked!.classList.contains('arrival-highlight')).toBe(false);
});
