// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const invoke = vi.fn();
const statusListeners = new Set<(event: { payload: unknown }) => void>();
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async (name: string, handler: (event: { payload: unknown }) => void) => {
  if (name === 'justsearch://search-shortcut-status') statusListeners.add(handler);
  return () => { statusListeners.delete(handler); };
}) }));
vi.mock('@tauri-apps/plugin-autostart', () => ({
  isEnabled: vi.fn().mockRejectedValue(new Error('autostart unavailable')),
}));
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetForTest, __seedForTest } from '../../api/registry/SurfaceCatalogClient.js';

interface SettingsElement extends HTMLElement {
  host_: unknown;
  activeCategory: string;
  updateComplete: Promise<unknown>;
}
async function mount(native = true): Promise<SettingsElement> {
  const el = document.createElement('jf-settings-surface') as SettingsElement;
  el.host_ = createMockHostApi({ platform: { capabilities: new Set(native ? ['native-notifications'] : []) } });
  document.body.append(el);
  el.activeCategory = 'desktop';
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}

describe('desktop search shortcut', () => {
  beforeEach(() => {
    statusListeners.clear();
    __resetForTest();
    __seedForTest({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [] });
    invoke.mockImplementation(async (command: string) => command === 'search_shortcut_status'
      ? { enabled: true, active: false, reason: 'Not available in this desktop session.' }
      : undefined);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ui: {} }), { status: 200 }));
  });
  afterEach(() => {
    document.body.replaceChildren();
    vi.restoreAllMocks();
    invoke.mockReset();
  });

  it('SC13 shows shortcut status independently of failed autostart and announces it politely', async () => {
    const el = await mount();
    const row = el.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]');
    expect(row?.textContent ?? '').toContain('Alt + Shift + Space');
    expect(row?.textContent).toContain('Not available in this desktop session.');
    expect(row?.textContent).toContain('--focus-search');
    expect(row?.querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite');
    expect(row?.querySelector('jf-switch')?.getAttribute('label')).toBe('Global search shortcut');
  });

  it('SC13 exposes the unavailable reason on the keyboard switch itself', async () => {
    const el = await mount();
    invoke.mockResolvedValue({ enabled: false, active: false, reason: null });
    const control = el.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"] jf-switch') as HTMLElement & { updateComplete: Promise<unknown> };
    expect(control).toBeTruthy();
    await control.updateComplete;
    const toggle = control.shadowRoot?.querySelector('[role="switch"]');
    expect(toggle?.getAttribute('aria-description')).toContain('Not available in this desktop session.');
    expect(toggle?.getAttribute('aria-checked')).toBe('true');
    toggle?.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('set_search_shortcut_enabled', { enabled: false }));
    await el.updateComplete;
  });

  it('SC7 distinguishes off, active and enabled but unavailable', async () => {
    invoke.mockResolvedValue({ enabled: false, active: false, reason: null });
    const off = await mount();
    expect(off.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]')?.textContent ?? '').toContain('Turned off');
    off.remove();
    invoke.mockResolvedValue({ enabled: true, active: true, reason: null });
    const active = await mount();
    expect(active.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]')?.textContent).toContain('Active');
  });

  it('SC8 keeps the shell state after a failed save and prevents overlapping toggles', async () => {
    invoke.mockImplementation(async (command: string) => command === 'search_shortcut_status'
      ? { enabled: true, active: true, reason: null } : undefined);
    const el = await mount();
    const row = el.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]');
    const control = row?.querySelector('jf-switch') as HTMLElement & { checked: boolean; disabled: boolean };
    expect(control).toBeTruthy();
    let rejectSave!: (error: Error) => void;
    invoke.mockImplementation((command: string) => command === 'set_search_shortcut_enabled'
      ? new Promise((_resolve, reject) => { rejectSave = reject; })
      : Promise.resolve({ enabled: true, active: true, reason: null }));
    control.dispatchEvent(new Event('change'));
    control.dispatchEvent(new Event('change'));
    await el.updateComplete;
    await vi.waitFor(() => expect(invoke.mock.calls.filter(([command]) => command === 'set_search_shortcut_enabled')).toHaveLength(1));
    expect(control.disabled).toBe(true);
    rejectSave(new Error('Disk is read-only'));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;
    expect(control.checked).toBe(true);
    expect(control.disabled).toBe(false);
    expect(row?.textContent).toContain('Disk is read-only');
    expect(row?.textContent).toContain('Active');
  });

  it('SC8 keeps newer shell state when an older toggle reply arrives late', async () => {
    invoke.mockResolvedValue({ enabled: true, active: true, reason: null, revision: 1 });
    const el = await mount();
    const row = el.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]');
    const control = row?.querySelector('jf-switch') as HTMLElement & { checked: boolean; disabled: boolean };
    expect(control).toBeTruthy();
    let finish!: (value: unknown) => void;
    invoke.mockImplementation((command: string) => command === 'set_search_shortcut_enabled'
      ? new Promise((resolve) => { finish = resolve; })
      : Promise.resolve({ enabled: true, active: true, reason: null, revision: 1 }));
    control.dispatchEvent(new Event('change'));
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('set_search_shortcut_enabled', { enabled: false }));
    try {
      for (const handler of statusListeners) handler({ payload: { enabled: true, active: false, reason: 'Another app holds the chord.', revision: 3 } });
      await el.updateComplete;
      expect(row?.textContent).toContain('Another app holds the chord.');
      finish({ enabled: false, active: false, reason: null, revision: 2 });
      await vi.waitFor(() => expect(control.disabled).toBe(false));
      expect(control.checked).toBe(true);
      expect(row?.textContent).toContain('Another app holds the chord.');
    } finally {
      finish({ enabled: false, active: false, reason: null, revision: 2 });
      await vi.waitFor(() => expect(control.disabled).toBe(false));
    }
  });

  it('SN6 hides native shortcut controls and makes no native calls in a browser', async () => {
    const el = await mount(false);
    expect(el.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]')).toBeNull();
    expect(invoke).not.toHaveBeenCalled();
  });

  it('SC7 reports an unavailable status when the native reply is malformed', async () => {
    invoke.mockResolvedValue(undefined);
    const el = await mount();
    expect(el.shadowRoot?.querySelector('[data-testid="settings-search-shortcut"]')?.textContent).toContain('Shortcut status is unavailable');
  });
});
