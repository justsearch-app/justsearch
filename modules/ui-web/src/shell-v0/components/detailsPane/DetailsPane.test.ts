// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// The shared frame of every window's details pane (design system §2, §7, §10).
import { afterEach, describe, expect, it, vi } from 'vitest';
import './DetailsPane.js';
import './DetailsPluginTab.js';
import { DETAILS_CLOSE, DETAILS_TAB_SELECT, type DetailsPane, type DetailsTab } from './DetailsPane.js';
import { pluginContributionFor, pluginDetailsTabs, type DetailsPluginTab } from './DetailsPluginTab.js';
import {
  __resetForTest as resetTabs,
  registerInspectorTab,
  type InspectorTabContribution,
} from '../../commands/InspectorTabRegistry.js';
import { CORE_PROVENANCE } from '../../primitives/provenance.js';
import {
  DETAILS_PANE_MAX_PX,
  DETAILS_PANE_MIN_PX,
  clampDetailsPaneWidth,
  detailsPaneIsInline,
  forgetDetailsPaneWidth,
  readDetailsPaneWidth,
  storeDetailsPaneWidth,
} from './detailsPaneSizing.js';

const TABS: DetailsTab[] = [
  { id: 'source:0', label: 'Tenancy agreement.pdf', number: '1', title: 'Documents/Tenancy agreement.pdf' },
  { id: 'source:1', label: 'flat issues.md', number: '2' },
  { id: 'context', label: 'Context' },
];

async function frame(overlay = false): Promise<DetailsPane> {
  const el = document.createElement('jf-details-pane');
  el.tabs = TABS;
  el.activeTab = 'source:0';
  el.overlay = overlay;
  document.body.append(el);
  await el.updateComplete;
  return el;
}

const tab = (el: DetailsPane, id: string): HTMLButtonElement => el.shadowRoot!.querySelector<HTMLButtonElement>(`[data-tab="${id}"]`)!;

afterEach(() => {
  document.body.replaceChildren();
  resetTabs();
  localStorage.clear();
});

describe('jf-details-pane', () => {
  it('names the region and draws a tab list with one selected, roving tab', async () => {
    const el = await frame();
    expect(el.hasAttribute('data-details-pane')).toBe(true);
    expect(el.shadowRoot!.querySelector('aside')!.getAttribute('aria-label')).toBe('Details');
    expect(el.shadowRoot!.querySelector('[role="tablist"]')).not.toBeNull();
    expect(tab(el, 'source:0').getAttribute('aria-selected')).toBe('true');
    expect(tab(el, 'source:0').tabIndex).toBe(0);
    expect(tab(el, 'context').tabIndex).toBe(-1);
    expect(tab(el, 'source:0').title).toBe('Documents/Tenancy agreement.pdf');
    expect(el.shadowRoot!.querySelector('[role="tabpanel"]')!.getAttribute('aria-labelledby')).toBe('details-tab-source:0');
  });

  it('a click chooses a tab; the window decides, so the frame does not change on its own', async () => {
    const el = await frame();
    const chosen: string[] = [];
    el.addEventListener(DETAILS_TAB_SELECT, event => chosen.push((event as CustomEvent<{ id: string }>).detail.id));
    tab(el, 'context').click();
    expect(chosen).toEqual(['context']);
    expect(el.activeTab).toBe('source:0');
  });

  it.each([
    ['ArrowRight', 'source:1'],
    ['ArrowLeft', 'context'],
    ['Home', 'source:0'],
    ['End', 'context'],
  ])('%s moves to the expected tab', async (key, expected) => {
    const el = await frame();
    const chosen: string[] = [];
    el.addEventListener(DETAILS_TAB_SELECT, event => chosen.push((event as CustomEvent<{ id: string }>).detail.id));
    tab(el, 'source:0').dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    expect(chosen).toEqual([expected]);
  });

  it('close raises one event, and it does not leave the window', async () => {
    const el = await frame();
    const seen: Event[] = [];
    const escaped: Event[] = [];
    el.addEventListener(DETAILS_CLOSE, event => seen.push(event));
    document.body.addEventListener(DETAILS_CLOSE, event => escaped.push(event));
    (el.shadowRoot!.querySelector('[data-testid="details-pane-close"]') as HTMLElement & { onActivate(): void }).onActivate();
    expect(seen).toHaveLength(1);
    expect(escaped).toHaveLength(0);
  });

  it('a sheet has a dim that closes it; inline has none', async () => {
    const inline = await frame();
    expect(inline.shadowRoot!.querySelector('[data-testid="details-pane-backdrop"]')).toBeNull();
    const sheet = await frame(true);
    const seen: Event[] = [];
    sheet.addEventListener(DETAILS_CLOSE, event => seen.push(event));
    sheet.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="details-pane-backdrop"]')!.click();
    expect(seen).toHaveLength(1);
  });

  it('focusActiveTab puts the keyboard on the active tab', async () => {
    const el = await frame();
    el.focusActiveTab();
    expect(el.shadowRoot!.activeElement).toBe(tab(el, 'source:0'));
  });
});

describe('details pane sizes (design system §2)', () => {
  it('holds 360-560 px', () => {
    expect(DETAILS_PANE_MIN_PX).toBe(360);
    expect(DETAILS_PANE_MAX_PX).toBe(560);
    expect(clampDetailsPaneWidth(100)).toBe(360);
    expect(clampDetailsPaneWidth(450)).toBe(450);
    expect(clampDetailsPaneWidth(900)).toBe(560);
  });

  it('is inline from 980 px of window width, a sheet below, and an unknown box is not a narrow one', () => {
    expect(detailsPaneIsInline(979)).toBe(false);
    expect(detailsPaneIsInline(980)).toBe(true);
    expect(detailsPaneIsInline(Number.POSITIVE_INFINITY)).toBe(true);
  });

  it('remembers a width, forgets it, and reads a stored non-number as no memory', () => {
    expect(readDetailsPaneWidth()).toBeNull();
    storeDetailsPaneWidth(480.4);
    expect(readDetailsPaneWidth()).toBe(480);
    forgetDetailsPaneWidth();
    expect(readDetailsPaneWidth()).toBeNull();
    localStorage.setItem('justsearch.searchV3.pane.width.v1', 'wide');
    expect(readDetailsPaneWidth()).toBeNull();
  });
});

describe('plugin inspector tabs (DP4)', () => {
  const contribution = (over: Partial<InspectorTabContribution> = {}): InspectorTabContribution => ({
    id: 'vendor.a', label: 'A', priority: 1, source: 'plugin', provenance: CORE_PROVENANCE, render: () => 'text', ...over,
  });

  async function mounted(tabContribution: InspectorTabContribution, selectedItem: unknown = null): Promise<DetailsPluginTab> {
    const el = document.createElement('jf-details-plugin-tab');
    el.contribution = tabContribution;
    el.selectedItem = selectedItem;
    document.body.append(el);
    await el.updateComplete;
    await el.updateComplete;
    return el;
  }

  it('offers registered tabs in priority order, and honours a `when` that is false', () => {
    registerInspectorTab(contribution({ id: 'vendor.b', label: 'B', priority: 2 }));
    registerInspectorTab(contribution({ id: 'vendor.a', label: 'A', priority: 1 }));
    registerInspectorTab(contribution({ id: 'vendor.hidden', label: 'Hidden', priority: 3, when: 'false' }));
    expect(pluginDetailsTabs()).toEqual([{ id: 'plugin:vendor.a', label: 'A' }, { id: 'plugin:vendor.b', label: 'B' }]);
    expect(pluginContributionFor('plugin:vendor.b')?.label).toBe('B');
    expect(pluginContributionFor('context')).toBeNull();
    expect(pluginContributionFor('plugin:vendor.gone')).toBeNull();
  });

  it('a string stays text: markup in it is never parsed', async () => {
    const el = await mounted(contribution({ render: () => '<img src=x onerror=alert(1)>' }));
    expect(el.shadowRoot!.querySelector('img')).toBeNull();
    expect(el.shadowRoot!.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('mounts an element, and renders again when the selection changes', async () => {
    const render = vi.fn(({ selectedItem }: { selectedItem: unknown }) => {
      const node = document.createElement('p');
      node.textContent = String((selectedItem as { path: string }).path);
      return node;
    });
    const el = await mounted(contribution({ render }), { path: '/a.md' });
    expect(el.shadowRoot!.querySelector('p')!.textContent).toBe('/a.md');
    el.selectedItem = { path: '/b.md' };
    await el.updateComplete;
    expect(el.shadowRoot!.querySelectorAll('p')).toHaveLength(1);
    expect(el.shadowRoot!.querySelector('p')!.textContent).toBe('/b.md');
  });

  it('a plugin that throws costs its tab alone, and says so', async () => {
    const el = await mounted(contribution({ render: () => { throw new Error('boom'); } }));
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('[data-testid="details-plugin-failed"]')).not.toBeNull();
  });
});
