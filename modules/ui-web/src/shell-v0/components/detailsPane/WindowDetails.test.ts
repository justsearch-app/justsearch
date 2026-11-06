// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// A window's own details pane: what `host.ui.showInspector` opens outside Search (DP3, DP5), and each
// window keeping to its own selection (C4).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './WindowDetails.js';
import type { WindowDetails } from './WindowDetails.js';
import type { DetailsPane } from './DetailsPane.js';
import {
  getInspectorState,
  resetInspectorState,
  setInspectorOwnerResolver,
  setOpen,
  setSelected,
} from '../../state/inspectorState.js';
import { readDetailsPaneWidth } from './detailsPaneSizing.js';

const LIBRARY = 'core.library-surface';
const BRAIN = 'core.brain-surface';

async function mount(windowId: string, width = 1280): Promise<WindowDetails> {
  const box = document.createElement('div');
  box.getBoundingClientRect = () => ({ width }) as DOMRect;
  const el = document.createElement('jf-window-details');
  el.windowId = windowId;
  box.append(el);
  document.body.append(box);
  await settle(el);
  return el;
}

async function settle(el: WindowDetails): Promise<void> {
  for (let i = 0; i < 4; i += 1) { await Promise.resolve(); await el.updateComplete; }
  await el.shadowRoot?.querySelector('jf-details-pane')?.updateComplete;
}

const select = (owner: string, path = '/read.txt', extra: Record<string, unknown> = {}): void => {
  setInspectorOwnerResolver(() => owner);
  setSelected({ id: path, title: path.slice(1), path, ...extra });
};

const pane = (el: WindowDetails): DetailsPane | null => el.shadowRoot!.querySelector('jf-details-pane');
const reader = (el: WindowDetails): (HTMLElement & { docPath: string; highlightRange: unknown }) | null =>
  el.shadowRoot!.querySelector('jf-document-pane');

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')));
});

afterEach(() => {
  resetInspectorState();
  setInspectorOwnerResolver(() => null);
  document.body.replaceChildren();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('a window’s details pane', () => {
  it('opens what was selected in this window, with its passage', async () => {
    const el = await mount(LIBRARY);
    expect(pane(el)).toBeNull();
    select(LIBRARY, '/read.txt', { highlightStartLine: 6, highlightEndLine: 8 });
    await settle(el);
    expect(reader(el)?.docPath).toBe('/read.txt');
    expect(reader(el)?.highlightRange).toEqual({ startLine: 6, endLine: 8 });
  });

  it('is a sheet below 980 px of window width, inline from 980, and only inline has a grip', async () => {
    const wide = await mount(LIBRARY, 980);
    select(LIBRARY);
    await settle(wide);
    expect(wide.hasAttribute('overlay')).toBe(false);
    expect(wide.shadowRoot!.querySelector('[data-testid="details-pane-grip"]')).not.toBeNull();
    document.body.replaceChildren();
    const narrow = await mount(LIBRARY, 979);
    select(LIBRARY, '/other.txt');
    await settle(narrow);
    expect(narrow.hasAttribute('overlay')).toBe(true);
    expect(narrow.shadowRoot!.querySelector('[data-testid="details-pane-grip"]')).toBeNull();
  });

  it('closes from its control, closes the shared signal, and returns focus to what had it', async () => {
    const opener = document.createElement('button');
    document.body.append(opener);
    opener.focus();
    const el = await mount(LIBRARY);
    select(LIBRARY);
    await settle(el);
    pane(el)!.dispatchEvent(new CustomEvent('details-close'));
    await settle(el);
    expect(pane(el)).toBeNull();
    expect(getInspectorState().isOpen).toBe(false);
    expect(document.activeElement).toBe(opener);
  });

  it('Escape in the pane closes it; Escape during a composition does not', async () => {
    const el = await mount(LIBRARY);
    select(LIBRARY);
    await settle(el);
    pane(el)!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true }));
    await settle(el);
    expect(pane(el)).not.toBeNull();
    pane(el)!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle(el);
    expect(pane(el)).toBeNull();
  });

  it('resizes within limits from the keyboard, and Home forgets the choice', async () => {
    const el = await mount(LIBRARY);
    select(LIBRARY);
    await settle(el);
    const grip = el.shadowRoot!.querySelector('[data-testid="details-pane-grip"]')!;
    for (let i = 0; i < 40; i += 1) grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    await settle(el);
    expect(el.style.getPropertyValue('--details-width')).toBe('360px');
    expect(readDetailsPaneWidth()).toBe(360);
    for (let i = 0; i < 40; i += 1) grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }));
    await settle(el);
    expect(el.style.getPropertyValue('--details-width')).toBe('560px');
    grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true, cancelable: true }));
    await settle(el);
    expect(readDetailsPaneWidth()).toBeNull();
  });
});

describe('C4 each window shows its own selection', () => {
  it('a split layout: two windows keep their own documents', async () => {
    const left = await mount(LIBRARY);
    const right = await mount(BRAIN);
    select(LIBRARY, '/left.txt');
    await settle(left);
    select(BRAIN, '/right.txt');
    await settle(left); await settle(right);
    expect(reader(left)?.docPath).toBe('/left.txt');
    expect(reader(right)?.docPath).toBe('/right.txt');
  });

  it('one window closing leaves the other open', async () => {
    const left = await mount(LIBRARY);
    const right = await mount(BRAIN);
    select(LIBRARY, '/left.txt');
    select(BRAIN, '/right.txt');
    await settle(left); await settle(right);
    pane(left)!.dispatchEvent(new CustomEvent('details-close'));
    await settle(left); await settle(right);
    expect(pane(left)).toBeNull();
    expect(reader(right)?.docPath).toBe('/right.txt');
    expect(getInspectorState().isOpen).toBe(true);
  });

  it('rapid selection in one window lands on the last, and the other never sees any of it', async () => {
    const left = await mount(LIBRARY);
    const right = await mount(BRAIN);
    for (const path of ['/1.txt', '/2.txt', '/3.txt']) select(LIBRARY, path);
    await settle(left); await settle(right);
    expect(reader(left)?.docPath).toBe('/3.txt');
    expect(pane(right)).toBeNull();
  });

  it('a window reused for another surface does not bring the old document along', async () => {
    const el = await mount(LIBRARY);
    select(LIBRARY, '/library.txt');
    await settle(el);
    el.windowId = BRAIN;
    await settle(el);
    expect(pane(el)).toBeNull();
  });

  it('a profile change clears every window', async () => {
    const left = await mount(LIBRARY);
    const right = await mount(BRAIN);
    select(LIBRARY, '/left.txt');
    select(BRAIN, '/right.txt');
    await settle(left); await settle(right);
    resetInspectorState();
    await settle(left); await settle(right);
    expect(pane(left)).toBeNull();
    expect(pane(right)).toBeNull();
  });

  it('closing and reopening shows the selection again in the same window only', async () => {
    const left = await mount(LIBRARY);
    const right = await mount(BRAIN);
    select(LIBRARY, '/left.txt');
    await settle(left);
    setOpen(false);
    await settle(left);
    expect(pane(left)).toBeNull();
    setOpen(true);
    await settle(left); await settle(right);
    expect(reader(left)?.docPath).toBe('/left.txt');
    expect(pane(right)).toBeNull();
  });
});
