// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import '../renderers/registry.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { applyAppearance, __resetThemeStateForTest } from '../state/themeState.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetSessionRegistryForTest } from '../plugin-api/sessionRegistry.js';
import { restoreActivePresentationOnBoot } from '../state/presentationState.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';
import { __resetUiModeForTest } from '../state/uiModeState.js';

type Element = HTMLElement & { updateComplete: Promise<unknown>; ui: Record<string, unknown>; saving: boolean };
const initialUi = { theme: 'system', mode: 'simple', highContrast: false, vimMode: true, defaultAction: 'open' };
const appearance = (event: Event): void => {
  void applyAppearance((event as CustomEvent).detail as Parameters<typeof applyAppearance>[0]);
};

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('No incidental backend')));
  __resetUserConfigForTest();
  __resetThemeStateForTest();
  __resetSessionRegistryForTest();
  __resetPresentationForTest();
  __resetUiModeForTest();
  restoreActivePresentationOnBoot();
  document.addEventListener('jf-set-appearance', appearance);
});

afterEach(async () => {
  // Settle the surface's lazy imports and persistence before removing its environment.
  await Promise.all([
    import('../substrates/profiles/index.js'),
    import('../substrates/consent/index.js'),
  ]);
  await new Promise((resolve) => setTimeout(resolve, 0));
  document.body.replaceChildren();
  document.removeEventListener('jf-set-appearance', appearance);
  document.documentElement.removeAttribute('data-theme');
  document.documentElement.classList.remove('high-contrast');
  __resetPresentationForTest();
  __resetThemeStateForTest();
  __resetUiModeForTest();
  vi.unstubAllGlobals();
});

async function mount(conflict = false, delayInitialLoad = false, overlap = false): Promise<{
  el: Element; posts: Record<string, unknown>[]; releaseInitialLoad: () => void; releaseSecondSave: () => void;
}> {
  const posts: Record<string, unknown>[] = [];
  let ui = { ...initialUi };
  let witness = { acceptedRevision: 0, lastCommittedOperationKey: null as string | null };
  let releaseInitialLoad = (): void => {};
  let firstRead = true;
  let releaseSecondSave = (): void => {};
  const el = document.createElement('jf-settings-surface') as Element;
  Object.assign(el, { host_: createMockHostApi({ data: { fetch: async (path, init) => {
    if (path !== '/api/settings/v2') return new Response('{}');
    if (init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      posts.push(body);
      if (overlap && posts.length === 2) await new Promise<void>((resolve) => { releaseSecondSave = resolve; });
      if (conflict && (!overlap || posts.length === 1)) return new Response(JSON.stringify({
        error: 'Settings changed elsewhere', errorCode: 'VERSION_CONFLICT', retryable: false,
      }), { status: 409 });
      ui = { ...ui, ...body.ui as typeof ui };
      witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: body.operationKey as string };
      return new Response(JSON.stringify({ ui, witness, state: 'COMPLETE', operationKey: body.operationKey }));
    }
    if (firstRead && delayInitialLoad) {
      firstRead = false;
      const original = JSON.stringify({ ui, witness });
      return new Promise<Response>((resolve) => { releaseInitialLoad = () => resolve(new Response(original)); });
    }
    firstRead = false;
    return new Response(JSON.stringify({ ui, witness }));
  } } }) });
  document.body.appendChild(el);
  await settle(el);
  return { el, posts, releaseInitialLoad: () => releaseInitialLoad(), releaseSecondSave: () => releaseSecondSave() };
}

async function settle(el: Element): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

async function chooseDark(el: Element): Promise<void> {
  const declared = el.shadowRoot!.querySelector('jf-declared-surface') as Element;
  await declared.updateComplete;
  const layout = declared.shadowRoot!.querySelector('jf-vertical-layout') as Element;
  await layout.updateComplete;
  const control = [...layout.shadowRoot!.querySelectorAll('jf-x-ui-renderer-control')]
    .find((candidate) => (candidate as HTMLElement & { path: string }).path === 'theme') as Element;
  await control.updateComplete;
  const group = control.shadowRoot!.querySelector('jf-option-button-group') as Element;
  await group.updateComplete;
  (group.shadowRoot!.querySelectorAll('button')[1] as HTMLButtonElement).click();
  await settle(el);
  await vi.waitFor(() => expect(el.saving).toBe(false));
}

it('S36.9: opening Settings and choosing Dark writes only the edited field once', async () => {
  const { el, posts } = await mount();
  expect(posts).toHaveLength(0);
  await chooseDark(el);
  expect(posts).toHaveLength(1);
  expect(posts[0]!.ui).toEqual({ theme: 'dark' });
  expect(el.ui).toMatchObject({ theme: 'dark', vimMode: true, defaultAction: 'open' });
  expect(document.documentElement.dataset.theme).toBe('dark');
});

it('S36.9/N36.2: a rejected Dark save restores the stored theme in the control and document', async () => {
  const { el } = await mount(true);
  await chooseDark(el);
  await vi.waitFor(() => expect(el.ui.theme).toBe('system'));
  expect(document.documentElement.dataset.theme).toBe('light');
  expect(el.ui.vimMode).toBe(true);
});

it('S36.9: a delayed mount observation cannot overwrite an accepted Dark choice', async () => {
  const { el, releaseInitialLoad } = await mount(false, true);
  try {
    await chooseDark(el);
    expect(el.ui.theme).toBe('dark');
  } finally { releaseInitialLoad(); }
  await settle(el);
  expect(el.ui.theme).toBe('dark');
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(el.ui.vimMode).toBe(true);
  expect(el.ui.defaultAction).toBe('open');
});

it('S36.9: a conflict is reconciled after a later overlapping save finishes', async () => {
  const { el, posts, releaseSecondSave } = await mount(true, false, true);
  el.ui = { ...el.ui, theme: 'light' };
  await applyAppearance({ theme: 'light' });
  for (const theme of ['dark', 'light']) document.dispatchEvent(new CustomEvent('jf-save-settings', {
    detail: { settings: { ui: { theme } } },
  }));
  try {
    await vi.waitFor(() => expect(posts).toHaveLength(2));
    await settle(el);
  } finally { releaseSecondSave(); }
  await settle(el);
  await vi.waitFor(() => expect(el.saving).toBe(false));
  expect(el.ui.theme).toBe('light');
  expect(document.documentElement.dataset.theme).toBe('light');
  expect(el.ui.vimMode).toBe(true);
});
