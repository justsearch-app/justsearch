// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * First run (ADR-0024; docs/how-to/cut-a-release.md section 4): a machine that never installed AI
 * installs through Install AI. `repairNeeded` means "a required file is missing", which is true on
 * that machine too, so it alone must not turn AI Brain into a repair page. Only evidence that AI
 * was installed or an install began does (F2/F3, unchanged).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AiState, InstallStatus } from '../state/aiStateStore.js';
import type { InstallPlanPreview } from '../utils/aiInstallPoll.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
const store = vi.hoisted(() => ({ listeners: new Set<(state: AiState) => void>(), current: {} as AiState }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (state: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import './BrainSurface.js';

interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
interface Button extends HTMLElement { updateComplete: Promise<boolean>; label: string }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const ids = ['embedding', 'reranker', 'chat'];
let preview: InstallPlanPreview;
let presence: { packages: Array<{ id: string; complete: boolean; presentVariantFiles?: string[] }> };
let operations: Array<{ id: string; args: unknown }>;

async function settle(el: Brain) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
function buttons(el: Brain) { return [...el.shadowRoot!.querySelectorAll<Button>('jf-button')]; }
async function press(host: Button, el: Brain) {
  await host.updateComplete;
  const control = host.shadowRoot!.querySelector<Button>('jf-control')!; await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click(); await settle(el);
}
async function mount(installStatus: InstallStatus) {
  store.current = { ...store.current, installStatus };
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = '';
  el.host_ = createMockHostApi({
    data: { invokeOperation: async (id: string, args: unknown) => { operations.push({ id, args }); return { success: true, structuredData: null }; } },
    ui: { showConfirmDialog: async () => true },
  });
  document.body.append(el); await settle(el); await settle(el); return el;
}
const headline = (el: Brain) => el.shadowRoot!.querySelector('.brain-headline')!.textContent;

beforeEach(() => {
  operations = [];
  preview = { totalDownloadBytes: 3072, components: ids.map(id => ({ id, label: id, state: 'to-download', declinable: id !== 'embedding', totalBytes: 1024, downloadBytes: 1024 })) };
  presence = { packages: ids.map(id => ({ id, complete: false, presentVariantFiles: [] })) };
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'offline', installed: { known: true, value: false } },
    runtimeStatus: {}, capabilities: { chat: false }, status: { inference: { chatEnabledSpec: true } },
  } as unknown as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2')) return json({ ui: { chatEnabled: null }, llm: {} });
    if (path.includes('/plan-preview')) return json(preview);
    if (path.endsWith('/models/status')) return json(presence);
    if (path.endsWith('/manifest')) return json({ schemaVersion: 2, packages: ids.map(id => ({ id, label: id, license: 'Apache-2.0', termsUrl: 'https://example.invalid/terms', variants: [], supportingFiles: [] })) });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

/** What the Engine reports on a machine where AI was never installed: every required file is missing. */
const fresh: InstallStatus = { state: 'idle', phase: 'idle', installedFully: false, repairNeeded: true, resumableBytes: 0, startedAtEpochMs: 0, packages: [] };

describe('AI Brain on first run', () => {
  it('F1 a fresh install says AI is not installed and Install AI reaches Accept and download', async () => {
    const el = await mount(fresh);
    expect(headline(el)).toBe("AI isn't installed yet");
    expect(el.shadowRoot!.querySelector('[data-testid="install-repair-needed"]')).toBeNull();
    const install = buttons(el).find(b => b.label.startsWith('Install AI'));
    expect(install, `rendered: ${buttons(el).map(b => b.label).join(', ')}`).toBeTruthy();
    await install!.updateComplete;
    const control = install!.shadowRoot!.querySelector<Button>('jf-control')!; await control.updateComplete;
    const native = control.shadowRoot!.querySelector<HTMLButtonElement>('button')!;
    expect((native.getAttribute('aria-label') ?? native.textContent ?? '').trim()).toMatch(/^Install AI/);
    await press(install!, el); await settle(el);
    const dialog = el.shadowRoot!.querySelector<HTMLDialogElement>('[data-testid="install-consent-dialog"]');
    expect(dialog?.open).toBe(true);
    expect(dialog!.querySelector('[label="Accept and download"]')).not.toBeNull();
  });

  it('F2 a completed install with a required file deleted offers Repair, which runs the default plan', async () => {
    preview.components = preview.components!.map(c => c.id === 'chat' ? c : { ...c, state: 'installed', downloadBytes: 0 });
    presence.packages = presence.packages.map(p => p.id === 'chat' ? p : { ...p, complete: true });
    const el = await mount(fresh);
    expect(headline(el)).toBe('Installed — repair available');
    expect(buttons(el).some(b => b.label.startsWith('Install AI'))).toBe(false);
    const repair = el.shadowRoot!.querySelector<Button>('[role="alert"] [label="Repair"]')!;
    expect(repair).not.toBeNull();
    await press(repair, el);
    expect(operations).toEqual([{ id: 'core.repair-ai-install', args: { acceptTerms: true } }]);
  });

  it.each([
    ['partial bytes from an interrupted download', { resumableBytes: 4096 }],
    ['a run record from this session', { state: 'cancelled', startedAtEpochMs: 100, packages: [{ packageId: 'embedding', state: 'failed' }] }],
  ] as const)('F3 an interrupted install with files missing offers Repair: %s', async (_name, patch) => {
    const el = await mount({ ...fresh, ...patch } as InstallStatus);
    expect(headline(el)).toBe('Installed — repair available');
    expect(el.shadowRoot!.querySelector('[role="alert"] [label="Repair"]')).not.toBeNull();
  });
});
