// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE1 in Settings: deleting a custom theme and revoking a plugin ask in place (a band under the
 * item), not in a window-blocking dialog. DE2: deleting all local data stays a typed confirmation,
 * in a modal dialog (ref-16).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/plugin-autostart', () => ({
  isEnabled: vi.fn().mockResolvedValue(false), enable: vi.fn(), disable: vi.fn(),
}));
import './SettingsSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetForTest as resetSurfaceCatalog, __seedForTest as seedSurfaceCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { setCustomThemeEntries, __resetCatalogForTest } from '../themes/themesCatalog.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';
import type { PluginManifest } from '../plugin-api/plugin-types.js';

type Answer = HTMLElement & { onActivate: () => unknown };
type Band = HTMLElement & { heading: string; consequence: string; actionLabel: string; updateComplete: Promise<unknown>; focusKeep(): Promise<void> };
const receipts: EphemeralToastSpec[] = [];
const receive = (event: Event) => receipts.push((event as CustomEvent<EphemeralToastSpec>).detail);
const settle = () => new Promise<void>(resolve => setTimeout(resolve, 0));

async function mount(category: string, extra: Parameters<typeof createMockHostApi>[0] = {}) {
  const confirm = vi.fn().mockResolvedValue(true);
  const deleteTheme = vi.fn();
  const el = document.createElement('jf-settings-surface') as SettingsSurface;
  el.host_ = createMockHostApi({ ui: { showConfirmDialog: confirm }, theme: { deleteTheme }, ...extra });
  document.body.append(el);
  await settle();
  el.activeCategory = category;
  await el.updateComplete; await settle();
  return { el, confirm, deleteTheme };
}
const band = (el: SettingsSurface, selector: string) => el.shadowRoot!.querySelector<Band>(selector);
async function answer(found: Band, which: 'confirm' | 'keep') {
  await found.updateComplete;
  found.shadowRoot!.querySelector<Answer>(`jf-button.${which}`)!.onActivate();
}

beforeEach(() => {
  receipts.length = 0; document.addEventListener(EPHEMERAL_TOAST_EVENT, receive);
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ui: {} }), { status: 200 })));
  __resetThemeStateForTest(); __resetCatalogForTest(); resetSurfaceCatalog();
  seedSurfaceCatalog({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [] });
  __resetSessionRegistryForTest();
});
afterEach(() => { document.removeEventListener(EPHEMERAL_TOAST_EVENT, receive); document.body.replaceChildren(); __resetCatalogForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('Settings own risky actions ask in place (DE1)', () => {
  it('deleting a custom theme asks in a band naming the theme; Keep it deletes nothing, the verb deletes once', async () => {
    setCustomThemeEntries([{ id: 'custom.x', displayName: 'My Custom', description: 'A user theme',
      tokens: { schemaVersion: 1, id: 'custom.x', displayName: 'My Custom', tokens: { 'h-teal': '120' } } }]);
    const { el, confirm, deleteTheme } = await mount('appearance');
    const del = el.shadowRoot!.querySelector<Answer>('.custom-theme-del')!;
    del.onActivate(); await el.updateComplete;
    let found = band(el, '.theme-delete-confirm')!;
    expect(found.heading).toBe('Delete the theme My Custom?');
    expect(found.consequence).toContain("can't be undone");
    expect(found.actionLabel).toBe('Delete My Custom');
    await answer(found, 'keep'); await el.updateComplete;
    expect(band(el, '.theme-delete-confirm')).toBeNull();
    expect(deleteTheme).not.toHaveBeenCalled();
    expect(receipts).toEqual([]);
    del.onActivate(); await el.updateComplete;
    found = band(el, '.theme-delete-confirm')!;
    await answer(found, 'confirm'); await answer(found, 'confirm'); await el.updateComplete;
    expect(deleteTheme).toHaveBeenCalledTimes(1);
    expect(deleteTheme).toHaveBeenCalledWith('custom.x');
    expect(receipts).toEqual([expect.objectContaining({ classId: 'core.own-action', severity: 'success', message: 'My Custom deleted' })]);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('revoking a plugin asks in a band under its row; the verb revokes it', async () => {
    const registry = __resetSessionRegistryForTest();
    registry.install({ id: 'alpha', version: '1.0.0', displayName: 'Plugin alpha', contractVersion: '1.1', tagNamespace: 'alpha',
      capabilities: { surfaces: [] }, register: () => {} } as PluginManifest);
    const { el, confirm } = await mount('plugins');
    const revoke = [...el.shadowRoot!.querySelectorAll<Answer>('jf-button')].find(b => b.getAttribute('label') === 'Revoke')!;
    revoke.onActivate(); await el.updateComplete;
    const found = band(el, '.plugin-revoke-confirm')!;
    expect(found.closest('.plugin-row')).not.toBeNull();
    expect(found.heading).toBe('Revoke the plugin alpha?');
    expect(found.consequence).toContain('comes back only when it is loaded again');
    await answer(found, 'confirm'); await el.updateComplete;
    expect(registry.list().map(p => p.manifest.id)).not.toContain('alpha');
    expect(receipts).toEqual([expect.objectContaining({ classId: 'core.own-action', severity: 'success', message: 'Plugin alpha revoked' })]);
    expect(confirm).not.toHaveBeenCalled();
  });
});

describe('Delete all local data stays a typed dialog (DE2)', () => {
  it('opens a modal dialog that names who asked, what is deleted, what stays, that it cannot be undone, and the phrase', async () => {
    invoke.mockReset().mockImplementation(async (command: string) => command === 'prepare_delete_data' ? 'single-use' : undefined);
    const { el } = await mount('data-reset', { platform: { capabilities: new Set(['native-notifications']) } });
    el.shadowRoot!.querySelector<Answer>('jf-button[label="Delete all local data"]')!.onActivate();
    await el.updateComplete; await settle();
    const dialog = el.shadowRoot!.querySelector<HTMLDialogElement>('dialog.delete-dialog')!;
    expect(dialog.open).toBe(true);
    const text = dialog.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('You asked for this');
    expect(dialog.querySelector('#delete-data-title')!.textContent).toBe('Delete all JustSearch data?');
    expect(text).toContain('Your original files and folders are untouched');
    expect(text).toContain('cannot be recovered');
    expect(text).toContain('Type DELETE to confirm');
    expect(dialog.querySelector('.remaining')!.textContent).toBe('6 more letters');
    const answers = [...dialog.querySelectorAll('.dialog-actions jf-button')].map(b => b.getAttribute('label'));
    expect(answers).toEqual(['Cancel', 'Delete & Restart']);
    el.shadowRoot!.querySelector<Answer>('dialog.delete-dialog jf-button[label="Cancel"]')!.onActivate();
    await el.updateComplete; await settle();
    expect(el.shadowRoot!.querySelector('dialog.delete-dialog')).toBeNull();
    expect(invoke.mock.calls.filter(([name]) => /delete_data/.test(String(name)))).toEqual([]);
  });
});
