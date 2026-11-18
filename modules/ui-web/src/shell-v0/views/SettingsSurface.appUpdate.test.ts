// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const invoke = vi.fn();
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/plugin-autostart', () => ({
  isEnabled: vi.fn().mockResolvedValue(false),
  enable: vi.fn(),
  disable: vi.fn(),
}));
vi.mock('../plugin-api/capabilities/platform.js', async (importOriginal) => ({
  ...await importOriginal<typeof import('../plugin-api/capabilities/platform.js')>(),
  openExternalUrl: vi.fn().mockResolvedValue(undefined),
}));

import './SettingsSurface.js';
import type { JfButton } from '../components/Button.js';
import { openExternalUrl } from '../plugin-api/capabilities/platform.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { AppUpdateStatus } from '../state/appUpdateState.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';

describe('SettingsSurface app updates', () => {
  let status: AppUpdateStatus;

  async function renderAppUpdates(): Promise<Element> {
    const element = document.createElement('jf-settings-surface') as HTMLElement & {
      host_: unknown;
      activeCategory: string;
      updateComplete: Promise<unknown>;
    };
    element.host_ = createMockHostApi({
      platform: { capabilities: new Set(['native-notifications']) },
    });
    document.body.appendChild(element);
    await new Promise((resolve) => setTimeout(resolve, 0));
    // App updates is under General, so it is absent from the default category.
    element.activeCategory = 'general';
    await element.updateComplete;

    const section = element.shadowRoot?.querySelector('[data-testid="settings-app-updates"]');
    if (!section) throw new Error('App updates section did not render');
    return section;
  }

  beforeEach(() => {
    resetSurfaceCatalog();
    seedSurfaceCatalog({
      schemaVersion: '1',
      catalogVersion: 1,
      namespace: 'core',
      primitive: 'Surface',
      entries: [],
    });
    status = {
      state: 'available',
      currentVersion: '1.0.0',
      availableVersion: '1.1.0',
      releaseSequence: 4,
    };
    invoke.mockImplementation(async (command: string) =>
      command === 'app_update_status' || command === 'check_for_app_update' ? status : undefined,
    );
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ ui: {} }), { status: 200 }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    invoke.mockReset();
    vi.mocked(openExternalUrl).mockClear();
  });

  it('shows an authenticated available release and requires an explicit install action', async () => {
    const section = await renderAppUpdates();
    expect(section.textContent).toContain('Version 1.1.0 is ready to install.');
    expect(section.textContent).toContain('Install 1.1.0');
    expect(invoke).toHaveBeenCalledWith('app_update_status');
    expect(invoke).not.toHaveBeenCalledWith('install_app_update');
  });

  it('invokes installation only after the user activates the install control', async () => {
    const section = await renderAppUpdates();
    const install = section.querySelector<JfButton>('jf-button[label="Install update"]');
    expect(install).not.toBeNull();
    install?.onActivate?.();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(invoke).toHaveBeenCalledWith('install_app_update');
  });

  it.each(['0.3.7', '0.3.7-rc.1'])('opens the release notes for %s on activation', async (version) => {
    status = { ...status, availableVersion: version };
    const section = await renderAppUpdates();
    const name = `What's new in ${version}`;
    const install = section.querySelector('jf-button[label="Install update"]');
    const notes = Array.from(section.querySelectorAll<JfButton>('jf-button'))
      .find((button) => button.textContent?.trim() === name);
    expect(notes, name).toBeDefined();
    expect(notes?.parentElement).toBe(install?.parentElement);
    expect(['secondary', 'ghost']).toContain(notes?.variant);
    const button = notes?.shadowRoot?.querySelector('jf-control')
      ?.shadowRoot?.querySelector('button');
    expect(button).toBeTruthy();
    expect(button?.getAttribute('aria-label') ?? notes?.textContent?.trim()).toBe(name);
    expect(openExternalUrl).not.toHaveBeenCalled();

    button?.click();

    expect(openExternalUrl).toHaveBeenCalledExactlyOnceWith(
      `https://github.com/justsearch-app/justsearch/releases/tag/v${version}`,
    );
    expect(invoke).not.toHaveBeenCalledWith('install_app_update');
  });

  it.each([
    '../x', '0.3.7 evil', '0.3.7/../../x', '', 'v0.3.7', '0.3.7+build', '0.3.7\n',
    '00.3.7', '0.3.7-', '0.3.7-rc..1', '0.3.7-rc.01',
  ])('omits release notes for an invalid version %j', async (version) => {
    status = { ...status, availableVersion: version };
    const section = await renderAppUpdates();
    expect(section.textContent).not.toContain("What's new");
  });

  it.each(['up_to_date', 'checking', 'repair_required'])('omits release notes when %s', async (state) => {
    status = { ...status, state };
    const section = await renderAppUpdates();
    expect(section.textContent).not.toContain("What's new");
    expect(section.querySelector('jf-button[label="Install update"]')).toBeNull();
    const check = section.querySelector<JfButton>('jf-button[label="Check for updates"]');
    expect(check?.disabled).toBe(state === 'repair_required');
  });

  it('checks for updates when the user activates the check control', async () => {
    status = { ...status, state: 'up_to_date' };
    const section = await renderAppUpdates();
    expect(invoke).not.toHaveBeenCalledWith('check_for_app_update');

    section.querySelector<JfButton>('jf-button[label="Check for updates"]')?.onActivate?.();

    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('check_for_app_update'));
  });
});
