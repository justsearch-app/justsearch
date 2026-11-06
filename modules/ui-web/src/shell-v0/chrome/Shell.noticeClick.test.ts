// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE5 — a clicked notification with Settings open, at the frame: the real Shell, router, Settings
 * window and status pill. Closing Settings navigates back to the area under it, which must not take
 * down the panel the click opens.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './Shell.js';
import type { Shell } from './Shell.js';
import type { SettingsWindow } from './SettingsWindow.js';
import type { StatusPill } from '../components/StatusPill.js';
import type { StatusPanel } from '../components/StatusPanel.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { deactivateProjection } from '../router/URLProjector.js';
import { __resetJournalForTest } from '../state/NavigationJournal.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetInMemoryStateForTest } from '../state/UserStateDocument.js';
import { __resetModalityForTest } from '../primitives/modality.js';
import { requestAuthorization, withdrawAuthorization } from '../operations/authorizationBroker.js';
import { installNoticeActivationBridge } from '../router/tauriBridge.js';
import type { Surface } from '../../api/types/surface.js';

vi.mock('../router/tauriBridge.js', async importOriginal => ({
  ...await importOriginal<typeof import('../router/tauriBridge.js')>(),
  installNoticeActivationBridge: vi.fn(() => () => {}),
}));

const LIBRARY = 'core.library-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';

function surface(id: string, mountTag: string, extra: Partial<Surface> = {}): Surface {
  return { id, mountTag, presentation: { labelKey: id, descriptionKey: id }, audience: 'USER', placement: 'RAIL',
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra };
}
for (const tag of ['jf-click-library-probe', 'jf-click-brain-probe', 'jf-click-settings-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {});
}

async function drain(): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
}
async function mount(): Promise<Shell> {
  const shell = document.createElement('jf-shell') as Shell;
  document.body.append(shell);
  await shell.updateComplete; await drain(); await shell.updateComplete;
  return shell;
}
async function settle(shell: Shell): Promise<void> { await drain(); await shell.updateComplete; await drain(); }
const pillOf = (shell: Shell) => shell.shadowRoot!.querySelector<StatusPill>('jf-status-pill')!;
const panelOf = (shell: Shell) => pillOf(shell).shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
const settingsOf = (shell: Shell) => shell.shadowRoot!.querySelector<SettingsWindow>('jf-settings-window')!;
const clickNotification = (id: string) => vi.mocked(installNoticeActivationBridge).mock.calls.at(-1)![0](id);
function agent(id: string) {
  void requestAuthorization({ pendingId: id, operationId: 'core.ingest-files', gateBehavior: 'INLINE_CONFIRM', riskTier: 'MEDIUM',
    argsSummary: 'x', requestedBy: 'Claude Desktop', arrival: 'needs-you', live: true,
    askedAt: new Date(Date.now() - 60_000).toISOString(), expiresAt: new Date(Date.now() + 4 * 60_000).toISOString(),
    reject: async () => {}, refresh: async () => ({ operationId: 'core.ingest-files', argsSummary: 'x', requestedBy: 'Claude Desktop' }) });
}
async function openSettings(shell: Shell): Promise<void> {
  shell.shadowRoot!.querySelector('jf-top-bar')!.shadowRoot!.querySelector<HTMLButtonElement>(`[data-surface-id="${SETTINGS}"]`)!.click();
  await settle(shell);
  expect(settingsOf(shell).open).toBe(true);
}
const closeSettings = async (shell: Shell) => {
  settingsOf(shell).shadowRoot!.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
  await settle(shell);
  expect(settingsOf(shell).open).toBe(false);
};
const reviewing = (shell: Shell) => panelOf(shell).shadowRoot!.querySelector('[data-testid="status-review"]')?.getAttribute('data-fact');

beforeEach(async () => {
  vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ entries: [] }), { status: 200 })));
  window.history.replaceState(null, '', '#');
  seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(LIBRARY, 'jf-click-library-probe'),
    surface(BRAIN, 'jf-click-brain-probe'),
    surface(SETTINGS, 'jf-click-settings-probe', { placement: 'MODAL' }),
  ] });
});
afterEach(() => {
  document.body.replaceChildren(); deactivateProjection(); resetCatalog(); __resetBootstrapForTest(); __resetJournalForTest();
  __resetAiStateForTest(); __resetUserConfigForTest(); __resetInMemoryStateForTest(); __resetModalityForTest(); vi.unstubAllGlobals();
});

describe('DE5 a notification clicked with Settings open', () => {
  it('leaves Settings where it is, and opens the review once Settings closes', async () => {
    const shell = await mount();
    await openSettings(shell);
    agent('s1'); await settle(shell);
    clickNotification('authorization:s1'); await settle(shell);
    expect(settingsOf(shell).open).toBe(true);
    expect(pillOf(shell).open).toBe(false);
    await closeSettings(shell);
    expect(pillOf(shell).open).toBe(true);
    expect(reviewing(shell)).toBe('authorization:s1');
    withdrawAuthorization('s1');
  });
  it('a request answered before Settings closes opens no review and says so in the panel', async () => {
    const shell = await mount();
    await openSettings(shell);
    agent('s2'); await settle(shell);
    clickNotification('authorization:s2'); await settle(shell);
    withdrawAuthorization('s2'); await settle(shell);
    await closeSettings(shell);
    expect(pillOf(shell).open).toBe(true);
    expect(reviewing(shell)).toBeUndefined();
    expect(panelOf(shell).shadowRoot!.querySelector('[data-testid="status-notice-gone"]')?.textContent).toBe('What that notification was about is no longer here.');
  });
  it('the return to the area under Settings landing after the panel opened leaves it open', async () => {
    const shell = await mount();
    shell.activateSurface(LIBRARY, {}, 'RAIL'); await settle(shell);
    await openSettings(shell);
    agent('s3'); await settle(shell);
    clickNotification('authorization:s3'); await settle(shell);
    await closeSettings(shell);
    expect(reviewing(shell)).toBe('authorization:s3');
    // A real browser delivers Back's popstate later than this test environment does.
    shell.activateSurface(LIBRARY, {}, 'RAIL'); await settle(shell);
    expect(pillOf(shell).open).toBe(true);
    expect(reviewing(shell)).toBe('authorization:s3');
    withdrawAuthorization('s3');
  });
  it('moving to another area still closes the panel', async () => {
    const shell = await mount();
    shell.activateSurface(LIBRARY, {}, 'RAIL'); await settle(shell);
    pillOf(shell).toggle(); await settle(shell);
    expect(pillOf(shell).open).toBe(true);
    shell.activateSurface(BRAIN, {}, 'RAIL'); await settle(shell);
    expect(pillOf(shell).open).toBe(false);
  });
});
