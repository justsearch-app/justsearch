// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const invoke = vi.fn();
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/plugin-autostart', () => ({
  isEnabled: vi.fn().mockResolvedValue(false),
  enable: vi.fn(),
  disable: vi.fn(),
}));

import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';

describe('SettingsSurface app updates', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
    seedSurfaceCatalog({
      schemaVersion: '1',
      catalogVersion: 1,
      namespace: 'core',
      primitive: 'Surface',
      entries: [],
    });
    invoke.mockImplementation(async (command: string) => {
      if (command === 'app_update_status') {
        return {
          state: 'available',
          currentVersion: '1.0.0',
          availableVersion: '1.1.0',
          releaseSequence: 4,
        };
      }
      return undefined;
    });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ ui: {} }), { status: 200 }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    invoke.mockReset();
  });

  it('shows an authenticated available release and requires an explicit install action', async () => {
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
    // Tempdoc 855 P1 — App updates is a sub-anchor of the "desktop" category (register-driven
    // paging); the default active category ("appearance") would not render it.
    element.activeCategory = 'general';
    await element.updateComplete;

    const section = element.shadowRoot?.querySelector('[data-testid="settings-app-updates"]');
    expect(section?.textContent).toContain('Version 1.1.0 is ready to install.');
    expect(section?.textContent).toContain('Install 1.1.0');
    expect(invoke).toHaveBeenCalledWith('app_update_status');
    expect(invoke).not.toHaveBeenCalledWith('install_app_update');
  });

  it('invokes installation only after the user activates the install control', async () => {
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
    element.activeCategory = 'general';
    await element.updateComplete;

    const install = element.shadowRoot?.querySelector(
      'jf-button[label="Install update"]',
    ) as (HTMLElement & { onActivate: () => void }) | null;
    expect(install).not.toBeNull();
    install?.onActivate();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(invoke).toHaveBeenCalledWith('install_app_update');
  });
});
