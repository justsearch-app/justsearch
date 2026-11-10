// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup-2 Track CC — SettingsSurface viewer-audience
 * toggle wire-up test.
 *
 * Verifies the three buttons in the "Viewer mode" / "View tier"
 * section actually update the viewerAudienceState store on click.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetThemeStateForTest } from '../state/themeState.js';
import {
  __seedForTest as seedSurfaceCatalog,
  __resetForTest as resetSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { getViewerAudience } from '../state/viewerAudienceState.js';
import type { SurfaceCatalog } from '../../api/types/surface.js';

const EMPTY_RAIL: SurfaceCatalog = {
  schemaVersion: '1',
  catalogVersion: 1,
  namespace: 'core',
  primitive: 'Surface',
  entries: [],
};

async function mountSurface(): Promise<HTMLElement> {
  const el = document.createElement('jf-settings-surface') as HTMLElement & {
    host_?: unknown;
    activeCategory: string;
    updateComplete: Promise<unknown>;
  };
  // Surface now reads tauri-runtime via host_.platform.capabilities; inject a
  // mock host before connectedCallback runs.
  el.host_ = createMockHostApi();
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ ui: {} }), { status: 200 }),
  );
  document.body.appendChild(el);
  await new Promise((resolve) => setTimeout(resolve, 0));
  // Tempdoc 855 P1 — View tier is a sub-anchor of the "developer" category (register-driven
  // paging); the default active category ("appearance") would not render it.
  el.activeCategory = 'advanced';
  await el.updateComplete;
  return el;
}

// Tempdoc 855 §15.2/§17 R2 — the View tier picker is now `<jf-option-button-group>` (the shared
// radiogroup renderer, plain-props path); its `.option-btn` buttons render inside THAT element's
// own shadow root, a separate tree from SettingsSurface's (shadow DOM does not compose across
// nested custom elements for querySelector/querySelectorAll — verified empirically, not assumed).
// The class contract (`button.option-btn` / `.option-label` / `.selected`) is unchanged; only the
// traversal needed a second hop through the nested component's shadow root.
function audienceButtonByLabel(
  root: ShadowRoot,
  label: 'User' | 'Operator' | 'Developer',
): HTMLButtonElement | undefined {
  const group = root.querySelector('jf-option-button-group');
  const buttons = Array.from(group?.shadowRoot?.querySelectorAll('.option-btn') ?? []);
  return buttons.find(
    (b) => b.querySelector('.option-label')?.textContent?.trim() === label,
  ) as HTMLButtonElement | undefined;
}

describe('SettingsSurface — viewer-audience toggle', () => {
  beforeEach(() => {
    __resetUserConfigForTest();
    __resetThemeStateForTest();
    __resetUserStateForTest();
    resetSurfaceCatalog();
    seedSurfaceCatalog(EMPTY_RAIL);
    __resetSessionRegistryForTest();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('renders the three audience-tier buttons', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    expect(audienceButtonByLabel(root, 'User')).toBeTruthy();
    expect(audienceButtonByLabel(root, 'Operator')).toBeTruthy();
    expect(audienceButtonByLabel(root, 'Developer')).toBeTruthy();
  });

  it('User button selected by default', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    const userBtn = audienceButtonByLabel(root, 'User');
    expect(userBtn?.classList.contains('selected')).toBe(true);
  });

  it('clicking Operator updates the store', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    audienceButtonByLabel(root, 'Operator')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(getViewerAudience()).toBe('OPERATOR');
  });

  it('clicking Developer updates the store', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    audienceButtonByLabel(root, 'Developer')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(getViewerAudience()).toBe('DEVELOPER');
  });

  it('clicking User reverts the store', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    audienceButtonByLabel(root, 'Developer')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    audienceButtonByLabel(root, 'User')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(getViewerAudience()).toBe('USER');
  });

  it('selected class follows the active audience', async () => {
    const el = await mountSurface();
    const root = el.shadowRoot!;
    audienceButtonByLabel(root, 'Operator')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(
      audienceButtonByLabel(root, 'Operator')?.classList.contains('selected'),
    ).toBe(true);
    expect(
      audienceButtonByLabel(root, 'User')?.classList.contains('selected'),
    ).toBe(false);
  });
});
