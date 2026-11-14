// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-top-bar>` — the frame's one bar (shell slice 2: FR1, FR2, FR7, FR8). Presentation and
 * interaction only; the Shell-level wiring (navigation, layouts, keyboard) is pinned in
 * Shell.frame.test.ts.
 */
import { afterEach, describe, expect, it } from 'vitest';
import './TopBar.js';
import type { TopBar, TopBarDestination, TopBarSelection } from './TopBar.js';

const AREAS: TopBarDestination[] = [
  { id: 'core.search-v3-surface', label: 'Search', icon: 'search', shortcut: 'Ctrl+1' },
  { id: 'core.library-surface', label: 'Library', icon: 'folder', shortcut: 'Ctrl+2' },
  { id: 'core.brain-surface', label: 'AI Brain', icon: 'cpu', shortcut: 'Ctrl+3' },
];

async function mount(setup: (bar: TopBar) => void = () => {}): Promise<TopBar> {
  const bar = document.createElement('jf-top-bar') as TopBar;
  bar.areas = AREAS;
  bar.activeId = 'core.library-surface';
  bar.system = { id: 'core.system-surface', label: 'System', icon: 'server', detail: 'Health, Activity, Logs' };
  bar.help = { id: 'core.help-surface', label: 'Help & shortcuts', icon: 'help-circle', shortcut: 'F1' };
  bar.about = { id: 'about', label: 'About JustSearch', icon: 'info' };
  setup(bar);
  document.body.append(bar);
  await bar.updateComplete;
  return bar;
}
function q<T extends Element = HTMLElement>(bar: TopBar, selector: string): T | null {
  return bar.shadowRoot!.querySelector<T>(selector as never) as T | null;
}
function all(bar: TopBar, selector: string): HTMLElement[] {
  return [...bar.shadowRoot!.querySelectorAll<HTMLElement>(selector)];
}
function selections(bar: TopBar): TopBarSelection[] {
  const seen: TopBarSelection[] = [];
  bar.addEventListener('top-bar-select', e => seen.push((e as CustomEvent<TopBarSelection>).detail));
  return seen;
}
async function openMore(bar: TopBar): Promise<void> {
  q<HTMLButtonElement>(bar, '.more-trigger')!.click();
  await bar.updateComplete;
}

afterEach(() => { document.querySelectorAll('jf-top-bar').forEach(el => el.remove()); });

describe('FR1 the top bar: brand, switcher, status slot and Menu', () => {
  it('renders the brand, three areas in order with the active one current, a status slot and Menu', async () => {
    const bar = await mount();
    expect(q(bar, '.brand')!.textContent!.trim()).toBe('JustSearch');
    expect(q(bar, 'nav')!.getAttribute('aria-label')).toBe('Areas');
    const segments = all(bar, 'nav .segment');
    expect(segments.map(s => s.getAttribute('aria-label'))).toEqual(['Search', 'Library', 'AI Brain']);
    expect(segments.map(s => s.getAttribute('aria-current'))).toEqual([null, 'page', null]);
    expect(q(bar, 'slot[name="status"]')).not.toBeNull();
    expect(q(bar, '.settings-trigger')).toBeNull();
    await openMore(bar);
    expect(q(bar, '[data-surface-id="core.settings-surface"]')!.getAttribute('aria-label')).toBe('Settings');
    expect(q(bar, '.more-trigger')!.getAttribute('aria-haspopup')).toBe('menu');
  });

  it('tooltips show the shortcuts, and assistive tech gets the same shortcut', async () => {
    const bar = await mount();
    const segments = all(bar, 'nav .segment');
    expect(segments.map(s => s.title)).toEqual(['Search · Ctrl+1', 'Library · Ctrl+2', 'AI Brain · Ctrl+3']);
    expect(segments.map(s => s.getAttribute('aria-keyshortcuts'))).toEqual(['Control+1', 'Control+2', 'Control+3']);
  });

  it('an area, Settings and a More row each report one selection', async () => {
    const bar = await mount();
    const seen = selections(bar);
    all(bar, 'nav .segment')[2]!.click();
    await openMore(bar);
    q<HTMLButtonElement>(bar, '[data-surface-id="core.settings-surface"]')!.click();
    await openMore(bar);
    q<HTMLButtonElement>(bar, '[role="menu"] [data-surface-id="core.system-surface"]')!.click();
    expect(seen).toEqual([{ kind: 'area', id: 'core.brain-surface' }, { kind: 'settings' },
      { kind: 'window', id: 'core.system-surface' }]);
    await bar.updateComplete;
    expect(q(bar, '[role="menu"]'), 'choosing a row closes the menu').toBeNull();
  });

  it('every area and window is addressable by exactly one data-surface-id while a menu is closed', async () => {
    const bar = await mount();
    for (const id of ['core.search-v3-surface', 'core.library-surface', 'core.brain-surface']) {
      expect(all(bar, `[data-surface-id="${id}"]`), id).toHaveLength(1);
    }
  });
});

describe('FR7 the More (⋯) menu', () => {
  it('holds System, Help & shortcuts (F1) and About; plugin windows under "More"; no Developer group by default', async () => {
    const bar = await mount(b => {
      b.windows = [{ id: 'vendor.acme.notes-surface', label: 'Acme notes', icon: 'hard-drive' }];
      b.developerWindows = [{ id: 'core.api-explorer-surface', label: 'API Explorer', icon: 'code' }];
    });
    await openMore(bar);
    const menu = q(bar, '[role="menu"]')!;
    expect(menu.getAttribute('aria-label')).toBe('Menu');
    const rows = all(bar, '[role="menu"] [role="menuitem"]');
    expect(rows.map(r => r.querySelector('.item-label')!.textContent)).toEqual(
      ['Settings', 'System', 'Help & shortcuts', 'About JustSearch', 'Acme notes']);
    expect(rows[1]!.querySelector('.item-detail')!.textContent).toBe('Health, Activity, Logs');
    expect(rows[2]!.querySelector('kbd')!.textContent).toBe('F1');
    expect(rows[2]!.getAttribute('aria-keyshortcuts')).toBe('F1');
    const groups = all(bar, '[role="menu"] [role="group"]');
    expect(groups.map(g => g.getAttribute('aria-label') ?? bar.shadowRoot!.getElementById(g.getAttribute('aria-labelledby')!)!.textContent))
      .toEqual(['App', 'More']);
    expect(rows[4]!.getAttribute('data-surface-id')).toBe('vendor.acme.notes-surface');
    expect(q(bar, '[data-menu-action="copy-link"]'), 'developer tools stay hidden').toBeNull();
  });

  it('SD1 shows the Developer group while tools are on: API explorer, Copy link, backend address and Logs', async () => {
    const bar = await mount(b => {
      b.developer = true;
      b.developerWindows = [{ id: 'core.api-explorer-surface', label: 'API Explorer', icon: 'code' }];
      b.backendAddress = 'http://127.0.0.1:7777';
    });
    const seen = selections(bar);
    await openMore(bar);
    expect(q(bar, '#developer-label')!.textContent).toBe('Developer');
    expect(q(bar, '[role="menu"] [data-surface-id="core.api-explorer-surface"]')).not.toBeNull();
    q<HTMLButtonElement>(bar, '[data-menu-action="backend-address"]')!.click();
    await bar.updateComplete;
    expect(q(bar, '[data-testid="backend-address"]')!.textContent).toBe('http://127.0.0.1:7777');
    q<HTMLButtonElement>(bar, '[data-menu-action="copy-link"]')!.click();
    expect(seen).toEqual([{ kind: 'copy-link' }]);
    await openMore(bar);
    expect(q(bar, '[data-menu-action="logs"]')).not.toBeNull();
    q<HTMLButtonElement>(bar, '[data-menu-action="logs"]')!.click();
    expect(seen.at(-1)).toEqual({ kind: 'logs' });
  });

  it('About reports its own selection without claiming a window id', async () => {
    const bar = await mount();
    const seen = selections(bar);
    await openMore(bar);
    const about = q<HTMLButtonElement>(bar, '[data-menu-action="about"]')!;
    expect(about.hasAttribute('data-surface-id')).toBe(false);
    about.click();
    expect(seen).toEqual([{ kind: 'window', id: 'about' }]);
  });

  it('opens with focus on the first row; arrows move, Escape closes and returns focus to ⋯', async () => {
    const bar = await mount();
    const trigger = q<HTMLButtonElement>(bar, '.more-trigger')!;
    trigger.focus();
    await openMore(bar);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const rows = all(bar, '[role="menu"] [role="menuitem"]');
    expect(bar.shadowRoot!.activeElement).toBe(rows[0]);
    const menu = q(bar, '[role="menu"]')!;
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, composed: true }));
    expect(bar.shadowRoot!.activeElement).toBe(rows[1]);
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true, composed: true }));
    expect(bar.shadowRoot!.activeElement).toBe(rows[rows.length - 1]);
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, composed: true }));
    expect(bar.shadowRoot!.activeElement, 'wraps').toBe(rows[0]);
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
    await bar.updateComplete;
    expect(q(bar, '[role="menu"]')).toBeNull();
    expect(bar.shadowRoot!.activeElement).toBe(trigger);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  it('closes on an outside click', async () => {
    const bar = await mount();
    await openMore(bar);
    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
    await bar.updateComplete;
    expect(q(bar, '[role="menu"]')).toBeNull();
  });
});

describe('FR2 / FR5 the switcher at the minimal size and under a layout that hides it', () => {
  it('the minimal bar\'s area button names the active area and opens the three areas as a radio menu', async () => {
    const bar = await mount();
    const seen = selections(bar);
    const trigger = q<HTMLButtonElement>(bar, '.area-trigger')!;
    expect(trigger.getAttribute('aria-label')).toBe('Library, choose area');
    trigger.click();
    await bar.updateComplete;
    const rows = all(bar, '[role="menu"][aria-label="Areas"] [role="menuitemradio"]');
    expect(rows.map(r => [r.querySelector('.item-label')!.textContent, r.getAttribute('aria-checked')]))
      .toEqual([['Search', 'false'], ['Library', 'true'], ['AI Brain', 'false']]);
    // The menu repeats destinations the switcher owns: they are addressed by data-area-id only.
    expect(rows.every(r => !r.hasAttribute('data-surface-id'))).toBe(true);
    rows[0]!.click();
    expect(seen).toEqual([{ kind: 'area', id: 'core.search-v3-surface' }]);
  });

  it('switcherVisible=false hides the switcher (and its navigation landmark); Settings and More stay', async () => {
    const bar = await mount(b => { b.switcherVisible = false; });
    expect(q(bar, 'nav')!.hidden).toBe(true);
    await openMore(bar);
    expect(q(bar, '[data-surface-id="core.settings-surface"]')).not.toBeNull();
    expect(q(bar, '.more-trigger')).not.toBeNull();
  });
});
