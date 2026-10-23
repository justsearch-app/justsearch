// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import '../chrome/SettingsWindow.js';
import type { SettingsWindow } from '../chrome/SettingsWindow.js';
import type { Operation } from '../../api/types/registry.js';
import { __resetForTest as resetOperations, __seedForTest as seedOperations } from '../../api/registry/OperationCatalogClient.js';
import { __resetForTest as resetSurfaces, __seedForTest as seedSurfaces } from '../../api/registry/SurfaceCatalogClient.js';
import { registerOperationButtonStrategy } from '../aggregate-substrate/strategies/operationButton.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __feedForTest, __feedContactForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { getViewerAudience, setViewerAudience } from '../state/viewerAudienceState.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';

const reset: Operation = {
  id: 'core.reset-settings', audience: 'OPERATOR',
  presentation: { labelKey: 'ops.reset-settings.label', descriptionKey: 'ops.reset-settings.description' },
  intf: { inputs: {}, result: {}, errors: [], uiHints: {} },
  policy: { risk: 'MEDIUM', confirm: { kind: 'INLINE' }, audit: 'METADATA_ONLY', undoSupported: false },
  availability: {}, lineage: { affects: [], supersedes: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' }, executors: ['UI'], consumers: [],
};
type Rendered = HTMLElement & { updateComplete: Promise<unknown> };
const tick = () => new Promise<void>(resolve => setTimeout(resolve, 0));
let unregister: () => void;
let originalTier: ReturnType<typeof getViewerAudience>;
let fetchMock: ReturnType<typeof vi.fn>;

function observe(reason: string | null): StatusSnapshot {
  const status = {
    operating: { indexing: { state: reason ? 'PAUSED' : 'RUNNING', reasons: reason ? [reason] : [],
      untilEpochMs: null, waitingForResume: false } },
    worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexedDocuments: 0, searchableDocuments: 0, indexHealthy: true } },
  } as unknown as StatusSnapshot;
  __feedForTest({ status });
  __feedContactForTest();
  return status;
}
async function settle(surface: SettingsSurface): Promise<void> {
  await surface.updateComplete;
  await tick();
  const operation = surface.shadowRoot!.querySelector<Rendered>('jf-operation');
  if (operation) await operation.updateComplete;
  const opButton = operation?.querySelector<Rendered>('jf-op-button');
  if (opButton) await opButton.updateComplete;
  const action = opButton?.shadowRoot?.querySelector<Rendered>('jf-action-button');
  if (action) await action.updateComplete;
}
async function mount(): Promise<SettingsSurface> {
  const surface = document.createElement('jf-settings-surface') as SettingsSurface;
  surface.host_ = createMockHostApi();
  document.body.append(surface);
  await settle(surface);
  return surface;
}
async function mountRecoveryWindow(): Promise<{ window: SettingsWindow; surface: SettingsSurface; close: HTMLButtonElement }> {
  seedSurfaces({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [{
    id: 'core.settings-surface', audience: 'USER', placement: 'MODAL', mountTag: 'jf-settings-surface',
    presentation: { labelKey: 'registry-surface.settings-surface.label', descriptionKey: 'registry-surface.settings-surface.description' },
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  }] });
  const window = document.createElement('jf-settings-window') as SettingsWindow;
  window.host_ = createMockHostApi();
  document.body.append(window);
  window.open = true;
  await window.updateComplete;
  await tick();
  const surface = window.shadowRoot!.querySelector('jf-settings-surface') as SettingsSurface;
  await settle(surface);
  const close = window.shadowRoot!.querySelector<HTMLButtonElement>('button.close')!;
  // Happy DOM does not perform the native showModal initial-focus step.
  close.focus();
  window.focusSettingsRecovery();
  await window.updateComplete;
  await settle(surface);
  expect(invokeButton(surface)).toBeNull();
  return { window, surface, close };
}
function action(surface: SettingsSurface): Rendered | null {
  return surface.shadowRoot!.querySelector('jf-op-button')?.shadowRoot?.querySelector('jf-action-button') ?? null;
}
function invokeButton(surface: SettingsSurface): HTMLButtonElement | null {
  return action(surface)?.shadowRoot?.querySelector('button') ?? null;
}

beforeEach(() => {
  originalTier = getViewerAudience();
  setViewerAudience('USER');
  __resetAiStateForTest();
  resetOperations();
  seedOperations({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries: [reset] });
  resetSurfaces();
  seedSurfaces({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [] });
  unregister = registerOperationButtonStrategy();
  fetchMock = vi.fn().mockRejectedValue(new TypeError('Test backend unavailable'));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(async () => {
  document.body.replaceChildren();
  unregister();
  resetOperations();
  resetSurfaces();
  __resetAiStateForTest();
  setViewerAudience(originalTier);
  await tick();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('CR37c settings recovery', () => {
  it('R2 retains recovery route focus until the first status reveals the hold', async () => {
    const { surface, close } = await mountRecoveryWindow();
    expect(deepActiveElement()).toBe(close);
    observe('settings_recovery'); await settle(surface);
    expect(invokeButton(surface)).not.toBeNull();
    expect(deepActiveElement()).toBe(invokeButton(surface));
  });

  it('R2 drops pending recovery focus when the user moves focus, even if they return', async () => {
    const { surface, close } = await mountRecoveryWindow();
    const nav = surface.shadowRoot!.querySelector('jf-settings-nav')!;
    await (nav as Rendered).updateComplete;
    nav.shadowRoot!.querySelector<HTMLInputElement>('input')!.focus();
    close.focus();
    observe('settings_recovery'); await settle(surface);
    expect(invokeButton(surface)).not.toBeNull();
    expect(deepActiveElement()).toBe(close);
  });

  it('R2 drops pending recovery focus when Settings closes and is reopened normally', async () => {
    const { window, surface, close } = await mountRecoveryWindow();
    window.dismiss(); await window.updateComplete;
    window.open = true; await window.updateComplete;
    close.focus();
    observe('settings_recovery'); await settle(surface);
    expect(invokeButton(surface)).not.toBeNull();
    expect(deepActiveElement()).toBe(close);
    window.focusSettingsRecovery(); await window.updateComplete; await settle(surface);
    expect(deepActiveElement()).toBe(invokeButton(surface));
  });

  it('R2 drops pending recovery focus when the first status reports no recovery hold', async () => {
    const surface = await mount();
    const other = document.createElement('button');
    document.body.append(other); other.focus();
    await surface.focusSettingsRecovery();
    observe(null); await settle(surface);
    observe('settings_recovery'); await settle(surface);
    expect(invokeButton(surface)).not.toBeNull();
    expect(deepActiveElement()).toBe(other);
  });

  it('R2 retains recovery intent through an unconfirmed recovery observation until Reset appears', async () => {
    const surface = await mount();
    const other = document.createElement('button');
    document.body.append(other); other.focus();
    await surface.focusSettingsRecovery();
    const status = observe('settings_recovery');
    __feedForTest({ status: { ...status, meta: { ...status.meta, workerRpcStale: true } } as StatusSnapshot });
    await settle(surface);
    expect(invokeButton(surface)).toBeNull();
    observe('settings_recovery'); await settle(surface);
    expect(invokeButton(surface)).not.toBeNull();
    expect(deepActiveElement()).toBe(invokeButton(surface));
  });

  it('R2 never steals focus moved during the existing asynchronous focus walk', async () => {
    const { window, surface } = await mountRecoveryWindow();
    observe('settings_recovery'); await settle(surface);
    const nav = surface.shadowRoot!.querySelector('jf-settings-nav') as Rendered;
    await nav.updateComplete;
    const search = nav.shadowRoot!.querySelector<HTMLInputElement>('input')!;
    window.focusSettingsRecovery(); await window.updateComplete;
    search.focus(); await settle(surface);
    expect(deepActiveElement()).toBe(search);
  });

  it('R2 cancels a repeated recovery route when the user moves focus before the window updates', async () => {
    const { window, surface } = await mountRecoveryWindow();
    observe('settings_recovery'); await settle(surface);
    const nav = surface.shadowRoot!.querySelector('jf-settings-nav') as Rendered;
    await nav.updateComplete;
    const search = nav.shadowRoot!.querySelector<HTMLInputElement>('input')!;
    window.focusSettingsRecovery();
    search.focus();
    await window.updateComplete; await settle(surface);
    expect(deepActiveElement()).toBe(search);
  });

  it('R1 exposes the existing reset in every tier only for the observed recovery hold', async () => {
    const surface = await mount();
    expect(invokeButton(surface)).toBeNull();
    observe('settings_recovery');
    await settle(surface);
    for (const tier of ['USER', 'OPERATOR', 'DEVELOPER'] as const) {
      setViewerAudience(tier);
      await settle(surface);
      expect(invokeButton(surface), `${tier} can reset during recovery`).not.toBeNull();
      expect(action(surface)?.getAttribute('confirm-kind')).toBe('INLINE');
      expect(getViewerAudience()).toBe(tier);
    }
  });

  it('R1 preserves confirmation, cancellation, errors and successful reset handling in User tier', async () => {
    observe('settings_recovery');
    const surface = await mount();
    expect(invokeButton(surface)).not.toBeNull();
    invokeButton(surface)!.click();
    await settle(surface);
    expect(action(surface)!.shadowRoot!.textContent).toContain('Are you sure?');
    expect(fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.reset-settings/invoke'))).toHaveLength(0);
    const named = (label: string) => [...action(surface)!.shadowRoot!.querySelectorAll('button')].find(b => b.textContent!.trim() === label)!;
    named('Cancel').click();
    await settle(surface);
    expect(fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.reset-settings/invoke'))).toHaveLength(0);
    fetchMock.mockResolvedValue(Response.json({ success: false, errorClass: 'VALIDATION', message: 'Recovery reset refused' }, { status: 400 }));
    invokeButton(surface)!.click(); await settle(surface);
    named('Yes').click(); await settle(surface);
    expect(surface.shadowRoot!.textContent).toContain('Recovery reset refused');
    expect(fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.reset-settings/invoke'))).toHaveLength(1);
    fetchMock.mockResolvedValue(Response.json({ success: true, message: 'Settings reset' }));
    invokeButton(surface)!.click(); await settle(surface);
    named('Yes').click(); await settle(surface);
    expect(surface.shadowRoot!.textContent).not.toContain('Recovery reset refused');
    expect(fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.reset-settings/invoke'))).toHaveLength(2);
    observe(null); await settle(surface);
    expect(invokeButton(surface)).toBeNull();
    expect(getViewerAudience()).toBe('USER');
  });

  it('R2 focuses the actual reset control in every tier without a View-tier detour', async () => {
    observe('settings_recovery');
    const surface = await mount();
    for (const tier of ['USER', 'OPERATOR', 'DEVELOPER'] as const) {
      setViewerAudience(tier); await settle(surface);
      const category = surface.activeCategory;
      await surface.focusSettingsRecovery();
      await settle(surface);
      expect(surface.activeCategory, 'recovery does not detour to developer settings').toBe(category);
      expect(invokeButton(surface)).not.toBeNull();
      expect(deepActiveElement(), `${tier} focuses the actionable button`).toBe(invokeButton(surface));
      expect(getViewerAudience()).toBe(tier);
      expect(fetchMock.mock.calls.filter(c => String(c[0]).endsWith('/core.reset-settings/invoke'))).toHaveLength(0);
    }
  });

  for (const reason of [null, 'user_paused', 'game_mode']) {
    it(`R3 preserves normal audience visibility outside recovery (${reason})`, async () => {
      observe(reason);
      const surface = await mount();
      expect(invokeButton(surface)).toBeNull();
      setViewerAudience('OPERATOR'); await settle(surface);
      expect(invokeButton(surface)).not.toBeNull();
      setViewerAudience('USER'); await settle(surface);
      expect(invokeButton(surface)).toBeNull();
    });
  }

  it('R1 R3 removes the exception on a remote clear and restores focus only when Reset had focus', async () => {
    observe('settings_recovery');
    const surface = await mount();
    expect(invokeButton(surface)).not.toBeNull();
    invokeButton(surface)!.focus();
    observe(null); await settle(surface);
    expect(invokeButton(surface)).toBeNull();
    expect(deepActiveElement()).toBe(surface.shadowRoot!.querySelector('h2'));
    observe('settings_recovery'); await settle(surface);
    const other = document.createElement('button'); document.body.append(other); other.focus();
    observe(null); await settle(surface);
    expect(invokeButton(surface)).toBeNull();
    expect(deepActiveElement()).toBe(other);
  });

  it('R1 R3 restores focus when pending confirmation blurs before the recovery hold clears', async () => {
    observe('settings_recovery');
    const surface = await mount();
    expect(invokeButton(surface)).not.toBeNull();
    invokeButton(surface)!.click(); await settle(surface);
    const yes = [...action(surface)!.shadowRoot!.querySelectorAll('button')].find(b => b.textContent!.trim() === 'Yes')!;
    yes.focus();
    let finish!: (response: Response) => void;
    fetchMock.mockImplementation(() => new Promise<Response>(resolve => { finish = resolve; }));
    yes.click(); await settle(surface);
    expect(yes.isConnected).toBe(false);
    expect(invokeButton(surface)!.disabled).toBe(true);
    // The existing ceremony removes Yes and disables Reset during the request.
    // Model the native blur as well when running in Happy DOM.
    yes.blur();
    finish(Response.json({ success: true, message: 'Settings committed' }));
    await settle(surface);
    observe(null); await settle(surface);
    expect(invokeButton(surface)).toBeNull();
    expect(deepActiveElement()).toBe(surface.shadowRoot!.querySelector('h2'));
  });
});
