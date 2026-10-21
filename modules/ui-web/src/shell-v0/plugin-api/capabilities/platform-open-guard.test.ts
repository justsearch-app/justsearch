// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const native = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: native.invoke }));

import { createPlatformApi, openLocalFile } from './platform.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../../components/advisory/ephemeralToast.js';
import { AdvisoryStore } from '../../components/advisory/AdvisoryStore.js';
import '../../components/advisory/AdvisoryToastHost.js';
import '../../views/BrowseSurface.js';
import '../../components/chat/CitationsPanel.js';
import { AdvisoryToastHost } from '../../components/advisory/AdvisoryToastHost.js';
import { BrowseSurface } from '../../views/BrowseSurface.js';
import { CitationsPanel } from '../../components/chat/CitationsPanel.js';
import { createMockHostApi } from '../testHostApi.js';
import { registerSearchResultActions, __resetSearchResultActionsForTest, SEARCH_RESULT_CONTEXT } from '../../commands/searchResultActions.js';
import { listContextActions, __resetForTest } from '../../commands/ContextActionRegistry.js';

const path = '/docs/crafted.desktop';
const refusal = 'Opening .desktop files is not allowed. Use Show in folder instead.';
let messages: EphemeralToastSpec[];
let store: AdvisoryStore;
let toast: AdvisoryToastHost;
const onToast = (event: Event) => { messages.push((event as CustomEvent<EphemeralToastSpec>).detail); };

async function settled(): Promise<void> {
  await vi.waitFor(() => expect(native.invoke).toHaveBeenCalled());
  // Dynamic import + rejected invoke + Lit's update are separate microtask turns.
  await new Promise((resolve) => setTimeout(resolve, 20));
  await toast.updateComplete;
}

beforeEach(() => {
  vi.stubGlobal('__TAURI_INTERNALS__', {});
  vi.stubGlobal('open', vi.fn());
  native.invoke.mockReset().mockRejectedValue(refusal);
  messages = [];
  document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
  store = new AdvisoryStore();
  store.start();
  toast = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  toast.store = store;
  document.body.appendChild(toast);
});

afterEach(() => {
  document.body.replaceChildren();
  store.stop();
  document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
  __resetForTest();
  __resetSearchResultActionsForTest();
  vi.unstubAllGlobals();
});

async function expectLocalRefusal(): Promise<void> {
  await settled();
  expect(native.invoke.mock.calls).toEqual([['open_file', { path }]]);
  expect(globalThis.open).not.toHaveBeenCalled();
  expect(messages).toHaveLength(1);
  expect(messages[0]).toMatchObject({ message: refusal, severity: 'error', actionLabel: 'Show in folder' });
  expect(toast.shadowRoot?.textContent).toContain(refusal);
  expect(toast.shadowRoot?.textContent).toContain('Show in folder');
}

describe('desktop opening guard', () => {
  it('local refusal rejects once, renders feedback, and offers Show in folder', async () => {
    const result = await openLocalFile(path).then(() => 'resolved', (error: unknown) => error);
    expect(result).toBe(refusal);
    await expectLocalRefusal();
    native.invoke.mockResolvedValueOnce(undefined);
    messages[0]!.onAction?.();
    await vi.waitFor(() => expect(native.invoke).toHaveBeenLastCalledWith('reveal_in_explorer', { path }));
    expect(native.invoke).toHaveBeenCalledTimes(2);
  });

  it('external refusal rejects once and renders feedback without a browser fallback', async () => {
    const api = createPlatformApi('CORE');
    const url = 'tel:123';
    const result = await api.openExternal(url).then(() => 'resolved', (error: unknown) => error);
    expect(result).toBe(refusal);
    await settled();
    expect(native.invoke.mock.calls).toEqual([['open_external_url', { url }]]);
    expect(globalThis.open).not.toHaveBeenCalled();
    expect(messages).toHaveLength(1);
    expect(toast.shadowRoot?.textContent).toContain(refusal);
  });

  it('routes BrowseSurface file activation through the local command with visible refusal', async () => {
    const browse = document.createElement('jf-browse-surface') as BrowseSurface;
    browse.host_ = createMockHostApi({
      platform: createPlatformApi('CORE'),
      data: { fetch: async () => new Response(JSON.stringify({ roots: [{ path: '/docs' }] })) },
    });
    document.body.appendChild(browse);
    await browse.updateComplete;
    await new Promise((resolve) => setTimeout(resolve, 0));
    browse.expanded = { __BROWSE_ROOTS__: true, '/docs': true };
    browse.folderChildren = { '/docs': [] };
    browse.fileChildren = { '/docs': [{ path, name: 'crafted.desktop' }] };
    await browse.updateComplete;
    const row = browse.shadowRoot?.querySelector<HTMLElement>('.row.file');
    expect(row).toBeTruthy();
    row!.click();
    await expectLocalRefusal();
  });

  it('routes search-result activation through the local command with visible refusal', async () => {
    registerSearchResultActions();
    const action = listContextActions(SEARCH_RESULT_CONTEXT, { path }).find((entry) => entry.id === 'search-result.open');
    expect(action).toBeDefined();
    await action!.handler({ path });
    await expectLocalRefusal();
  });

  it('routes citation activation through the local command with visible refusal', async () => {
    const panel = document.createElement('jf-citations-panel') as CitationsPanel;
    panel.sources = [{ parentDocId: path, chunkIndex: 0, chunkTotal: 1, startChar: 0, endChar: 10, score: 0.9, excerpt: 'source', startLine: 0, endLine: 1, headingText: '' }];
    document.body.appendChild(panel);
    panel.sourcesExpanded = true;
    await panel.updateComplete;
    const button = panel.shadowRoot?.querySelector<HTMLButtonElement>('[data-open-file]');
    expect(button).toBeTruthy();
    button!.click();
    await expectLocalRefusal();
  });

  it('keeps successful local and external calls on their respective native routes', async () => {
    native.invoke.mockResolvedValue(undefined);
    await openLocalFile('/docs/report.pdf');
    await createPlatformApi('CORE').openExternal('https://example.com/?q=1#f');
    expect(native.invoke.mock.calls).toEqual([
      ['open_file', { path: '/docs/report.pdf' }],
      ['open_external_url', { url: 'https://example.com/?q=1#f' }],
    ]);
    expect(globalThis.open).not.toHaveBeenCalled();
    expect(messages).toHaveLength(0);
  });

  it('keeps browser opening outside Tauri', async () => {
    vi.stubGlobal('__TAURI_INTERNALS__', undefined);
    await openLocalFile('/docs/report.pdf');
    await createPlatformApi('CORE').openExternal('https://example.com/');
    expect(native.invoke).not.toHaveBeenCalled();
    expect(globalThis.open).toHaveBeenCalledTimes(2);
  });
});
