// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { createHostApi } from './HostApiImpl.js';
import { getInspectorState, resetInspectorState } from '../state/inspectorState.js';
import { resetSearchState } from '../state/searchState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetAgentSessionStore } from '../state/agentSessionStore.js';
import { __resetConversationListForTest } from '../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../controllers/draftKeptHint.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { takePendingSelection, } from '../utils/compose.js';
import '../views/search-v3/SearchV3View.js';
import type { SearchV3View } from '../views/search-v3/SearchV3View.js';

type ElementView = HTMLElement & { updateComplete: Promise<unknown> };
const deps = { apiBase: '', registerSurfacePort: () => {} };
let fetchMock: ReturnType<typeof vi.fn>;
const q = <T extends Element = HTMLElement>(host: ElementView, id: string): T | null =>
  host.shadowRoot?.querySelector<T>(`[data-testid="${id}"]`) ?? null;
async function drain(view: ElementView): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}
beforeEach(() => {
  resetInspectorState();
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  deactivateProjection();
  takePendingSelection(); document.body.replaceChildren();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetInspectorState(); resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

describe('C9 A3 public inspector passage validation', () => {
  it.each(['CORE', 'TRUSTED_PLUGIN', 'UNTRUSTED_PLUGIN'] as const)('%s keeps a valid 0-based inclusive passage', tier => {
    const host = createHostApi('passage-proof', tier, deps);
    host.ui.showInspector({ id: 'read', title: 'Read', path: '/read.txt', kind: 'browse-node', highlightStartLine: 6, highlightEndLine: 8 });
    expect(getInspectorState().selected).toEqual({ id: 'read', title: 'Read', path: '/read.txt', kind: 'browse-node', highlightStartLine: 6, highlightEndLine: 8 });
    expect(getInspectorState().isOpen).toBe(true);
    host.ui.showInspector({ id: 'single', title: 'Single', highlightStartLine: 1, highlightEndLine: 1 });
    expect(getInspectorState().selected).toMatchObject({ path: '', highlightStartLine: 1, highlightEndLine: 1 });
    host.ui.showInspector({ id: 'first', title: 'First', highlightStartLine: 0, highlightEndLine: 0 });
    expect(getInspectorState().selected).toMatchObject({ path: '', highlightStartLine: 0, highlightEndLine: 0 });
  });
  it.each([
    [undefined, undefined], [6, undefined], [undefined, 8], [-1, 8], [8, 6],
    [1.5, 8], [6, 8.5], [NaN, 8], [6, NaN], [Infinity, 8], [6, Infinity], ['6', 8], [6, '8'], [null, 8],
  ])('ignores the whole invalid range %s to %s without error or a stale highlight', (start, end) => {
    const host = createHostApi('passage-proof', 'CORE', deps);
    host.ui.showInspector({ id: 'old', title: 'Old', highlightStartLine: 6, highlightEndLine: 8 });
    expect(() => host.ui.showInspector({ id: 'next', title: 'Next', path: '/next.txt', highlightStartLine: start, highlightEndLine: end } as never)).not.toThrow();
    expect(getInspectorState().selected).toEqual({ id: 'next', title: 'Next', path: '/next.txt', kind: undefined });
    expect(getInspectorState().isOpen).toBe(true);
  });
});

it.each<[number, number, number, string[]]>([
  [900, 6, 8, ['Selected six', 'Selected seven', 'Selected eight']],
  [1568, 6, 8, ['Selected six', 'Selected seven', 'Selected eight']],
  [900, 0, 1, ['zero', 'one']],
  [1568, 0, 1, ['zero', 'one']],
  [900, -1, 1, []],
  [1568, -1, 1, []],
])('C9 A3 public inspector at width %i paints valid range %i to %i only', async (width, start, end, highlighted) => {
  fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: 'zero\none\ntwo\nthree\nfour\nfive\nSelected six\nSelected seven\nSelected eight\nnine' }) });
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  vi.spyOn(view, 'getBoundingClientRect').mockReturnValue({ width } as DOMRect);
  vi.stubGlobal('ResizeObserver', class {
    constructor(private callback: ResizeObserverCallback) {}
    observe(target: Element): void { if (target === view) this.callback([], this as unknown as ResizeObserver); }
    unobserve(): void {}
    disconnect(): void {}
  });
  document.body.append(view); await drain(view);
  const host = createHostApi('passage-proof', 'CORE', { apiBase: '', registerSurfacePort: () => {} });
  host.ui.showInspector({ id: '/read.txt', title: 'Read', path: '/read.txt', highlightStartLine: start, highlightEndLine: end });
  await drain(view);
  const pane = q<ElementView>(view, 'sv3-pane')!; expect(pane).not.toBeNull(); await pane.updateComplete;
  expect(pane.hasAttribute('overlay')).toBe(width < 980);
  const reader = pane.shadowRoot!.querySelector('jf-document-pane') as ElementView & { docPath: string; highlightRange: unknown };
  await drain(reader);
  expect(reader.docPath).toBe('/read.txt');
  expect(reader.highlightRange).toEqual(start < 0 ? null : { startLine: start, endLine: end });
  expect(view.shadowRoot!.querySelectorAll('jf-sv3-pane')).toHaveLength(1);
  expect([...reader.shadowRoot!.querySelectorAll('.hl-strong')].map(line => line.textContent?.trim())).toEqual(highlighted);
});
