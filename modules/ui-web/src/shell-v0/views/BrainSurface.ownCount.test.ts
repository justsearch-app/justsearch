// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// FA4a — AI Brain's "Your files" counts the person's own files (the Engine's `ownSearchableDocuments`,
// bundled Help excluded), like the composer and Library: none in words, unknown in words, never a number.
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import './BrainSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
const HELP = 250;
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
const known = (value: number) => ({ known: true as const, value });

/** A fresh install's AI state: nothing installed, observed live; `own` of the person's files beside the Help. */
function state(own: number | undefined): AiState {
  return {
    aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null, notRequested: true },
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    runtime: { mode: 'offline', installed: { known: true, value: false } },
    capabilities: { chat: false }, status: { inference: { chatEnabledSpec: false } },
    installStatus: { state: 'idle', phase: 'idle', installedFully: false, resumableBytes: 0 },
    runtimeStatus: { onnxFeatures: [] },
    index: { documentCount: known((own ?? 0) + HELP), searchableDocumentCount: known((own ?? 0) + HELP),
      ownDocumentCount: own === undefined ? { known: false } : known(own) },
  } as unknown as AiState;
}
async function filesLine(own: number | undefined): Promise<string> {
  store.current = state(own);
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi();
  document.body.appendChild(el);
  for (let i = 0; i < 10; i++) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
  return el.shadowRoot!.querySelector('.files-step .step-meta')!.textContent!.trim();
}
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: false }, llm: {} });
    if (path.endsWith('/plan-preview')) return json({ totalDownloadBytes: 0, components: [] });
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: [] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

it('FA4a counts the person\'s own files, not the bundled Help', async () => {
  expect(await filesLine(3)).toBe('3 files');
});

it('FA4a one own file reads in the singular', async () => {
  expect(await filesLine(1)).toBe('1 file');
});

it('FA4a with no own files it says so in words, whatever Help holds', async () => {
  expect(await filesLine(0)).toBe('None yet');
});

it('FA4a an Engine that does not report the own count: unknown in words, never a number', async () => {
  expect(await filesLine(undefined)).toBe('File count unknown');
});
