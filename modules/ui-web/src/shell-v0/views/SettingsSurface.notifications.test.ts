// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE5 / ref-13 — the Notifications choice is reachable end to end: the register's General group
 * declares it, its key dispatches to a renderer, and the control writes the real store.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './SettingsSurface.js';
import '../renderers/registry.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import { getNotifications, setNotifications } from '../state/notificationsState.js';
import { SETTINGS_REGISTER, searchRegister } from './settingsRegister.js';

type Surface = HTMLElement & { updateComplete: Promise<unknown>; activeCategory: string };

async function mountNotifications(): Promise<Surface> {
  const el = document.createElement('jf-settings-surface') as Surface;
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: { fetch: () => Promise.resolve(new Response(JSON.stringify({ ui: {} }), { status: 200 })) },
  });
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  el.activeCategory = 'notifications';
  await el.updateComplete;
  return el;
}
const group = (el: Surface) => el.shadowRoot!.querySelector('[data-testid="settings-notifications"] jf-option-button-group') as HTMLElement & {
  options: ReadonlyArray<{ value: string; label: string }>; value: string; groupLabel: string; updateComplete: Promise<unknown>;
};

beforeEach(() => {
  __resetUserConfigForTest(); __resetUserStateForTest(); __resetThemeStateForTest();
  __resetSessionRegistryForTest(); __resetPresentationForTest(); __resetForTest();
  __seedForTest({ 'settings.group.general': 'General', 'settings.category.notifications': 'Notifications', 'settings.section.notifications': 'Notifications' });
});
afterEach(() => {
  document.body.innerHTML = '';
  __resetPresentationForTest(); __resetUserStateForTest(); __resetForTest();
  vi.restoreAllMocks();
});

describe('DE5 Settings › General › Notifications', () => {
  it('lives in the General group and settings search finds it', () => {
    const general = SETTINGS_REGISTER.find((g) => g.id === 'general')!;
    expect(general.categories.map((c) => c.id)).toContain('notifications');
    expect(searchRegister('notifications').map((hit) => hit.category.id)).toContain('notifications');
  });
  it('offers Off, On and On with sound with the stored value selected, named for assistive technology', async () => {
    setNotifications('sound');
    const control = group(await mountNotifications());
    expect(control.options.map((o) => o.label)).toEqual(['Off', 'On', 'On with sound']);
    expect(control.value).toBe('sound');
    expect(control.groupLabel).toBe('Desktop notifications');
  });
  it('says when it applies, in the words of ref-13', async () => {
    const el = await mountNotifications();
    expect(el.shadowRoot!.querySelector('[data-testid="settings-notifications"]')!.textContent)
      .toContain('Only while JustSearch is in the background: an agent request is waiting, or long work finished or failed.');
  });
  it('choosing writes the store and shows the choice', async () => {
    const el = await mountNotifications();
    group(el).dispatchEvent(new CustomEvent('change', { detail: { value: 'off' }, bubbles: true, composed: true }));
    await el.updateComplete;
    expect(getNotifications()).toBe('off');
    expect(group(el).value).toBe('off');
  });
  it('a retained surface shows the value the document holds now', async () => {
    const el = await mountNotifications();
    el.remove();
    setNotifications('off');
    document.body.appendChild(el);
    await el.updateComplete;
    expect(group(el).value).toBe('off');
  });
});
