// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/plugin-autostart', () => ({
  isEnabled: vi.fn().mockResolvedValue(false), enable: vi.fn(), disable: vi.fn(),
}));
import './SettingsSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetForTest, __seedForTest } from '../../api/registry/SurfaceCatalogClient.js';

type Button = HTMLElement & { onActivate: () => void; disabled: boolean };
const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

async function mount(desktop = true): Promise<SettingsSurface> {
  const element = document.createElement('jf-settings-surface') as SettingsSurface;
  element.host_ = createMockHostApi({ platform: { capabilities: new Set(desktop ? ['native-notifications'] : []) } });
  document.body.append(element);
  await settle();
  element.activeCategory = 'data';
  await element.updateComplete;
  return element;
}
function button(element: SettingsSurface, label: string): Button {
  const found = element.shadowRoot!.querySelector<Button>(`jf-button[label="${label}"]`);
  expect(found, label).not.toBeNull();
  return found!;
}
async function request(element: SettingsSurface): Promise<void> {
  button(element, 'Delete all local data').onActivate();
  await element.updateComplete;
}

describe('WP5.1 Settings reset', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [] });
    invoke.mockReset().mockImplementation(async (command) => command === 'prepare_delete_data' ? 'single-use' : undefined);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ui: {} }), { status: 200 }));
  });
  afterEach(async () => {
    document.body.replaceChildren();
    await settle();
    vi.restoreAllMocks();
  });

  it('RS1 discloses authored stores, permanent loss, preserved assets, automatic timing and a copy path without encryption', async () => {
    const element = await mount();
    await request(element);
    const text = element.shadowRoot!.textContent!.toLowerCase();
    for (const word of ['conversations', 'memories', 'agent history', 'index', 'watched folders', 'settings', 'logs']) expect(text).toContain(word);
    expect(text).toMatch(/cannot be recovered.*(?:even|whether|with or without|regardless)/s);
    expect(text).toContain('downloaded ai models');
    expect(text).toContain('ai runtime');
    expect(text).toMatch(/restart.*automatically/s);
    expect(text).toMatch(/copy.*conversations.*before/s);
    expect(text).not.toContain('on next launch');
    expect(text).not.toContain('if you want to uninstall');
  });

  it('RS2 Cancel and an incomplete typed confirmation never invoke deletion', async () => {
    const element = await mount();
    await request(element);
    const confirm = element.shadowRoot!.querySelector<Button>('jf-button[variant="danger"]')!;
    expect(confirm.disabled).toBe(true);
    confirm.onActivate();
    await settle();
    button(element, 'Cancel').onActivate();
    await element.updateComplete;
    expect(element.shadowRoot!.querySelector('input[aria-label="Type DELETE to confirm"]')).toBeNull();
    expect(invoke.mock.calls.filter(([name]) => /delete_data/.test(name))).toEqual([]);
  });

  it('RS8 Escape cancels, restores focus, and the typed input has an associated description', async () => {
    const element = await mount();
    await request(element);
    const input = element.shadowRoot!.querySelector<HTMLInputElement>('input[aria-label="Type DELETE to confirm"]')!;
    expect(element.shadowRoot!.activeElement).toBe(input);
    const description = input.getAttribute('aria-describedby');
    expect(description).toBeTruthy();
    expect(element.shadowRoot!.getElementById(description!)).not.toBeNull();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    await element.updateComplete;
    await settle();
    expect(element.shadowRoot!.querySelector('input[aria-label="Type DELETE to confirm"]')).toBeNull();
    expect(element.shadowRoot!.activeElement).toBe(button(element, 'Delete all local data'));
    expect(invoke.mock.calls.filter(([name]) => /delete_data/.test(name))).toEqual([]);
  });

  it('RS8 browser explains the disabled control in visible text', async () => {
    const element = await mount(false);
    expect(button(element, 'Delete all local data').disabled).toBe(true);
    expect(element.shadowRoot!.textContent).toMatch(/desktop app.*browser/s);
  });

  it('RS3 typed confirmation sends the issued token once and describes automatic restart', async () => {
    const element = await mount();
    await request(element);
    const input = element.shadowRoot!.querySelector<HTMLInputElement>('input[aria-label="Type DELETE to confirm"]')!;
    input.value = 'DELETE';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await element.updateComplete;
    const confirm = element.shadowRoot!.querySelector<Button>('jf-button[variant="danger"]')!;
    expect(confirm.disabled).toBe(false);
    confirm.onActivate();
    confirm.onActivate();
    await settle();
    expect(invoke.mock.calls.filter(([name]) => /delete_data/.test(name))).toEqual([
      ['prepare_delete_data'], ['confirm_delete_data', { token: 'single-use' }],
    ]);
    expect(element.shadowRoot!.textContent).toContain('Restarting');
  });

  it('RN2 settings-to-defaults remains the separate core.reset-settings operation', async () => {
    const element = await mount();
    expect(element.shadowRoot!.querySelector('jf-operation[operation-id="core.reset-settings"]')).not.toBeNull();
    expect(invoke.mock.calls.filter(([name]) => /delete_data/.test(name))).toEqual([]);
  });
});
