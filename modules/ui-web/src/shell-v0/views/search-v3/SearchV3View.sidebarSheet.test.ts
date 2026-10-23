// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Sidebar } from './Sv3Sidebar.js';
import { sv3BoundaryStorageKeys } from './sv3-boundaries.js';

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')));
  localStorage.setItem(sv3BoundaryStorageKeys.sidebarCollapsed, '0');
  localStorage.setItem(sv3BoundaryStorageKeys.sidebarWidth, '300');
});

afterEach(async () => {
  document.querySelectorAll('jf-sv3-window').forEach(el => el.remove());
  await new Promise(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
});

function savedSidebar(): (string | null)[] {
  return [sv3BoundaryStorageKeys.sidebarCollapsed, sv3BoundaryStorageKeys.sidebarWidth]
    .map(key => localStorage.getItem(key));
}

async function mount(width = 640): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window');
  el.getBoundingClientRect = () => ({ width }) as DOMRect;
  el.composerState = 'docked';
  document.body.append(el);
  await el.updateComplete;
  return el;
}

async function controls(el: SearchV3View): Promise<{ bar: Sv3Sidebar; toggle: HTMLButtonElement; other: HTMLButtonElement }> {
  const bar = el.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  await bar.updateComplete;
  return {
    bar,
    toggle: bar.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-collapse"]')!,
    other: bar.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-new"]')!,
  };
}

it('HC8 Escape dismisses the sidebar sheet and restores its opener without saving the dismissal', async () => {
  const el = await mount();
  const { bar, toggle, other } = await controls(el);
  toggle.focus();
  toggle.click();
  await el.updateComplete;
  await bar.updateComplete;
  expect(el.sidebarCollapsed).toBe(false);
  const saved = savedSidebar();
  other.focus();
  const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true });
  other.dispatchEvent(escape);
  await el.updateComplete;
  await bar.updateComplete;
  expect(el.sidebarCollapsed).toBe(true);
  expect(escape.defaultPrevented).toBe(true);
  expect(el.composerState).toBe('docked');
  expect(bar.shadowRoot!.activeElement).toBe(toggle);
  expect(savedSidebar()).toEqual(saved);
});

it('HC8 the sidebar backdrop dismisses the sheet and restores its opener without saving the dismissal', async () => {
  const el = await mount();
  const { bar, toggle } = await controls(el);
  toggle.focus();
  toggle.click();
  await el.updateComplete;
  await bar.updateComplete;
  const saved = savedSidebar();
  const backdrop = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-backdrop"]');
  expect(backdrop, 'the reopened sheet needs an outside-click dismissal surface').not.toBeNull();
  backdrop!.focus();
  backdrop!.click();
  await el.updateComplete;
  await bar.updateComplete;
  expect(el.sidebarCollapsed).toBe(true);
  expect(bar.shadowRoot!.activeElement).toBe(toggle);
  expect(savedSidebar()).toEqual(saved);
  expect(el.shadowRoot!.querySelector('[data-testid="sv3-sidebar-backdrop"]')).toBeNull();
});

it('HC8 the expanded inline sidebar keeps its Escape and has no backdrop', async () => {
  const el = await mount(1400);
  const { other } = await controls(el);
  const saved = savedSidebar();
  other.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
  await el.updateComplete;
  expect(el.sidebarCollapsed).toBe(false);
  expect(el.shadowRoot!.querySelector('[data-testid="sv3-sidebar-backdrop"]')).toBeNull();
  expect(savedSidebar()).toEqual(saved);
});

it('HC8 an IME Escape leaves the sidebar sheet open', async () => {
  const el = await mount();
  const { toggle, other } = await controls(el);
  toggle.click();
  await el.updateComplete;
  other.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true, composed: true }));
  await el.updateComplete;
  expect(el.sidebarCollapsed).toBe(false);
});
