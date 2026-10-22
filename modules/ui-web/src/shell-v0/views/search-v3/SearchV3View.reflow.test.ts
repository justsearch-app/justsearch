// @vitest-environment happy-dom
// HC8: the host's measured box must yield chrome before it loses its reading column.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { sv3BoundaryStorageKeys } from './sv3-boundaries.js';

let notify: (() => void) | undefined;
let width = 360;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')));
  vi.stubGlobal('ResizeObserver', class {
    constructor(private callback: () => void) {}
    observe(target: Element): void {
      if (target.localName === 'jf-sv3-window') notify = this.callback;
    }
    disconnect(): void {}
    unobserve(): void {}
  });
});
afterEach(async () => {
  document.querySelectorAll('jf-sv3-window').forEach(el => el.remove());
  await new Promise(resolve => setTimeout(resolve, 0));
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
  notify = undefined;
});
async function mount(): Promise<SearchV3View> {
  const el = document.createElement('jf-sv3-window');
  el.getBoundingClientRect = () => ({ width }) as DOMRect;
  document.body.append(el);
  await el.updateComplete;
  return el;
}

it.each([320, 360, 480])('HC8 yields the sidebar at %i px without overwriting the saved expanded choice', async size => {
  width = size;
  localStorage.setItem(sv3BoundaryStorageKeys.sidebarCollapsed, '0');
  localStorage.setItem(sv3BoundaryStorageKeys.sidebarWidth, '300');
  const el = await mount();
  expect(el.sidebarCollapsed).toBe(true);
  expect(el.hasAttribute('sidebar-narrow')).toBe(true);
  expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarCollapsed)).toBe('0');
  expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBe('300');
  width = 1400;
  notify?.();
  await el.updateComplete;
  expect(el.sidebarCollapsed).toBe(false);
  expect(el.hasAttribute('sidebar-narrow')).toBe(false);
});

it('HC8 keeps a saved collapsed choice through reflow and remount', async () => {
  width = 1400;
  localStorage.setItem(sv3BoundaryStorageKeys.sidebarCollapsed, '1');
  const el = await mount();
  width = 360;
  notify?.();
  await el.updateComplete;
  width = 1400;
  notify?.();
  await el.updateComplete;
  expect(el.sidebarCollapsed).toBe(true);
  el.remove();
  const reopened = await mount();
  expect(reopened.sidebarCollapsed).toBe(true);
  expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarCollapsed)).toBe('1');
});

it('HC8 lets the reader reopen and dismiss the narrow sidebar and keeps that choice on a wide remount', async () => {
  width = 360;
  const el = await mount();
  expect(el.hasAttribute('sidebar-narrow')).toBe(true);
  const bar = el.shadowRoot!.querySelector('jf-sv3-sidebar')!;
  await bar.updateComplete;
  const toggle = bar.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-collapse"]')!;
  toggle.click();
  await el.updateComplete;
  expect(el.sidebarCollapsed).toBe(false);
  expect(el.hasAttribute('sidebar-narrow')).toBe(true);
  expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarCollapsed)).toBe('0');
  toggle.click();
  await el.updateComplete;
  expect(el.sidebarCollapsed).toBe(true);
  expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarCollapsed)).toBe('1');
  el.remove();
  width = 1400;
  const reopened = await mount();
  expect(reopened.sidebarCollapsed).toBe(true);
});
