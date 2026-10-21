// @vitest-environment happy-dom

/**
 * Tempdoc 874 — the chat-width section is REACHABLE, end to end.
 *
 * The setting's identity is one string repeated in three files that never reference each other:
 * `chat-width` as the register's section key (`settingsRegister.ts`), as the `sectionRenderers()`
 * dispatch key (`SettingsSurface.ts`), and as `settings.section.chat-width` in the label catalog.
 * A typo in any one of them yields a silently blank sub-anchor — the register still declares a
 * section, the nav still lists it, and nothing renders. This mounts the real surface and asserts the
 * section is actually there, with the control wired to the real store.
 *
 * The harness is the one `SettingsSurface.highContrast.test.ts` established; `appearance` is the
 * default active category (`firstCategoryId()`), which is where this section lives.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
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
import { restoreActivePresentationOnBoot } from '../state/presentationState.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import { getChatWidth, setChatWidth } from '../state/chatWidthState.js';

async function mountSettings(): Promise<HTMLElement> {
  const el = document.createElement('jf-settings-surface') as HTMLElement & {
    updateComplete: Promise<unknown>;
  };
  (el as unknown as Record<string, unknown>).host_ = createMockHostApi({
    data: {
      fetch: () => Promise.resolve(new Response(JSON.stringify({ ui: {} }), { status: 200 })),
    },
  });
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  return el;
}

beforeEach(() => {
  __resetUserConfigForTest();
  __resetUserStateForTest();
  __resetThemeStateForTest();
  __resetSessionRegistryForTest();
  __resetPresentationForTest();
  __resetForTest();
  __seedForTest({
    'settings.related.label': 'Related settings',
    'settings.section.accessibility': 'Accessibility',
    'settings.section.chat-width': 'Chat width',
  });
  restoreActivePresentationOnBoot();
});

afterEach(() => {
  document.body.innerHTML = '';
  __resetPresentationForTest();
  __resetUserStateForTest();
  __resetForTest();
  vi.restoreAllMocks();
});

describe('SettingsSurface — the chat-width section', () => {
  it('renders under the register key, with the three presets and the stored value selected', async () => {
    setChatWidth('wide');
    const el = await mountSettings();

    // The sub-anchor the register declares — the join between the three files.
    const anchor = el.shadowRoot!.querySelector('[data-settings-anchor="chat-width"]');
    expect(anchor, 'the register key must dispatch to a renderer').toBeTruthy();
    const section = anchor!.querySelector('[data-testid="settings-chat-width"]');
    expect(section, 'the renderer must produce the titled section').toBeTruthy();

    const slider = section!.querySelector('jf-discrete-slider') as HTMLElement & {
      steps: ReadonlyArray<{ value: string; label: string }>;
      value: string;
    };
    expect(slider).toBeTruthy();
    expect(slider.steps.map((s) => s.value)).toEqual(['narrow', 'default', 'wide']);
    expect(slider.steps.map((s) => s.label)).toEqual(['Narrow', 'Default', 'Wide']);
    // The control shows what is stored, not a hard-coded default.
    expect(slider.value).toBe('wide');
  });

  it('moving the control writes through to the store', async () => {
    const el = await mountSettings();
    const slider = el.shadowRoot!.querySelector(
      '[data-testid="settings-chat-width"] jf-discrete-slider',
    ) as HTMLElement & { updateComplete: Promise<unknown> };
    await slider.updateComplete;

    // Drive the real atom: its range input at index 0 is 'narrow'.
    const input = slider.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    input.value = '0';
    input.dispatchEvent(new Event('input'));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;

    expect(getChatWidth()).toBe('narrow');
  });
});
