// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';
import type { SettingsNav } from '../components/SettingsNav.js';
import * as register from './settingsRegister.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { requestMemberTab } from '../router/memberTabIntent.js';
import { __resetForTest as resetCatalog, __seedForTest as seedCatalog } from '../../api/registry/SurfaceCatalogClient.js';
import { __resetUserStateForTest, getDocument } from '../state/UserStateDocument.js';
import { setViewerAudience } from '../state/viewerAudienceState.js';
import type { Surface } from '../../api/types/surface.js';
import * as lazySurfaces from './lazySurfaceRegistry.js';

const homes: Record<string, string[]> = {
  general: ['desktop', 'notifications', 'app-updates', 'keyboard', 'indexing', 'context-window'],
  appearance: ['interface', 'theme', 'core.presentation-gallery-surface', 'core.presentation-editor-surface', 'token-editor', 'layout', 'rail', 'chat-width', 'accessibility'],
  'security-privacy': ['core.security-surface', 'durable-grants', 'plugin-permissions', 'agent-autonomy', 'feedback-capture'],
  'data-reset': ['reset-settings', 'data'],
  plugins: ['plugins', 'delivered-contributions'],
  advanced: ['view-tier', 'workspace-profiles'],
  about: ['version', 'update-information'],
};
const settle = async (el: SettingsSurface) => { await el.updateComplete; await new Promise(r => setTimeout(r, 0)); await el.updateComplete; };
async function mount(): Promise<SettingsSurface> {
  const el = document.createElement('jf-settings-surface') as SettingsSurface;
  el.host_ = createMockHostApi({ data: { fetch: async () => new Response('{"ui":{}}') } });
  document.body.append(el);
  await settle(el);
  return el;
}
function member(id: string, mountTag: string): Surface {
  return { id, mountTag, audience: 'USER', placement: 'DEEPLINK',
    presentation: { labelKey: id, descriptionKey: id },
    consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1' } };
}
for (const tag of ['jf-section-gallery-probe', 'jf-section-editor-probe']) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {
    connectedCallback() { if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<input aria-label="Draft">'; }
  });
}
beforeEach(() => {
  __resetUserStateForTest(); resetCatalog();
  seedCatalog({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    member('core.presentation-gallery-surface', 'jf-section-gallery-probe'),
    member('core.presentation-editor-surface', 'jf-section-editor-probe'),
  ] });
});
afterEach(() => { document.body.replaceChildren(); resetCatalog(); __resetUserStateForTest(); vi.restoreAllMocks(); });

it('SG2 the seven sections contain every native and member entry once in its settled order', async () => {
  const el = await mount();
  expect(el.activeCategory).toBe('general');
  expect(register.allCategories().map(c => c.id)).toEqual(Object.keys(homes));
  for (const [section, entries] of Object.entries(homes)) {
    expect(register.findCategory(section)?.sections?.map(e => e.key)).toEqual(entries);
  }
  const all = register.allCategories().flatMap(c => c.sections?.map(e => e.key) ?? []);
  expect(new Set(all).size).toBe(all.length);
  expect(el.shadowRoot!.querySelector('.header #settings-reset')).toBeNull();
});

it('SG4 every legacy category and entry resolves centrally, with explicit entries winning', () => {
  expect(register).toHaveProperty('resolveSettingsCoordinate');
  const resolve = register.resolveSettingsCoordinate;
  const aliases: Record<string, [string, string]> = {
    appearance: ['appearance', 'interface'], layout: ['appearance', 'layout'], notifications: ['general', 'notifications'],
    agent: ['security-privacy', 'agent-autonomy'], desktop: ['general', 'desktop'], indexing: ['general', 'indexing'],
    developer: ['advanced', 'view-tier'], data: ['data-reset', 'data'], plugins: ['plugins', 'plugins'],
    'privacy-trust': ['security-privacy', 'core.security-surface'],
  };
  for (const [id, [section, anchor]] of Object.entries(aliases)) expect(resolve(id)).toEqual({ section, anchor });
  for (const [section, entries] of Object.entries(homes)) for (const anchor of entries) {
    expect(resolve('old-category', anchor)).toEqual({ section, anchor });
  }
  expect(resolve('layout', 'keyboard')).toEqual({ section: 'general', anchor: 'keyboard' });
  expect(resolve('plugins', 'durable-grants')).toEqual({ section: 'security-privacy', anchor: 'durable-grants' });
  expect(resolve('unknown')).toBeUndefined();
});

it('SG3 SG4 latest pending and live arrivals select section and anchor without saved-state writes', async () => {
  const documentBefore = JSON.stringify(getDocument());
  requestMemberTab('core.settings-surface', 'core.presentation-gallery-surface');
  requestMemberTab('core.settings-surface', 'layout', 'keyboard');
  const el = await mount();
  expect(el.activeCategory).toBe('general');
  expect(el.shadowRoot!.activeElement?.getAttribute('data-settings-anchor')).toBe('keyboard');
  requestMemberTab('core.settings-surface', 'appearance', 'theme');
  requestMemberTab('core.settings-surface', 'plugins', 'durable-grants');
  await settle(el);
  expect(el.activeCategory).toBe('security-privacy');
  expect(el.shadowRoot!.activeElement?.getAttribute('data-settings-anchor')).toBe('durable-grants');
  expect(JSON.stringify(getDocument())).toBe(documentBefore);
});

it.each(['pending', 'live'])('SG4 superseding %s arrivals focus and scroll only the latest anchor', async mode => {
  let el: SettingsSurface | undefined;
  if (mode === 'live') el = await mount();
  const scrolled: string[] = [];
  vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockImplementation(function (this: HTMLElement) {
    if (this.dataset.settingsAnchor) scrolled.push(this.dataset.settingsAnchor);
  });
  requestMemberTab('core.settings-surface', 'appearance', 'theme');
  requestMemberTab('core.settings-surface', 'appearance', 'accessibility');
  el ??= await mount();
  await settle(el);
  expect(el.shadowRoot!.activeElement?.getAttribute('data-settings-anchor')).toBe('accessibility');
  expect(scrolled).toEqual(['accessibility']);
});

it('SG6 audience changes update unchanged search and navigation and rescue a disappearing focused row', async () => {
  setViewerAudience('DEVELOPER');
  const el = await mount();
  requestMemberTab('core.settings-surface', 'developer'); await settle(el);
  const nav = el.shadowRoot!.querySelector<SettingsNav>('jf-settings-nav')!;
  nav.query = 'workspace-profiles'; await nav.updateComplete;
  const row = nav.shadowRoot!.querySelector<HTMLButtonElement>('.search-result-row')!;
  expect(row).not.toBeNull(); row.focus();
  setViewerAudience('USER'); await settle(el); await nav.updateComplete;
  expect(nav.query).toBe('workspace-profiles');
  expect(nav.shadowRoot!.querySelector('.search-result-row')).toBeNull();
  expect(nav.shadowRoot!.activeElement?.classList.contains('search-input')).toBe(true);
  expect(el.shadowRoot!.querySelector('[data-settings-anchor="workspace-profiles"]')).toBeNull();
  nav.query = ''; await nav.updateComplete;
  expect(nav.shadowRoot!.querySelector('[data-settings-anchor="workspace-profiles"]')).toBeNull();
  requestMemberTab('core.settings-surface', 'developer', 'workspace-profiles'); await settle(el);
  expect(el.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('hidden by your view tier');
});

it.each(['navigation', 'content'])('SG6 audience changes rescue a disappearing focused %s entry without changing section', async mode => {
  setViewerAudience('DEVELOPER');
  const el = await mount();
  requestMemberTab('core.settings-surface', 'developer'); await settle(el);
  const nav = el.shadowRoot!.querySelector<SettingsNav>('jf-settings-nav')!;
  await nav.updateComplete;
  const root = mode === 'navigation' ? nav.shadowRoot! : el.shadowRoot!;
  const row = root.querySelector<HTMLElement>('[data-settings-anchor="workspace-profiles"]');
  expect(row).not.toBeNull(); row!.focus();
  setViewerAudience('USER'); await settle(el); await nav.updateComplete;
  expect(root.querySelector('[data-settings-anchor="workspace-profiles"]')).toBeNull();
  expect(nav.shadowRoot!.activeElement?.classList.contains('category-row')).toBe(true);
  expect(el.activeCategory).toBe('advanced');
});

it('SG5 recovery selects Data & reset and the existing Reset anchor without invoking reset', async () => {
  const el = await mount();
  void el.focusSettingsRecovery(); await settle(el);
  expect(el.activeCategory).toBe('data-reset');
  expect(el.shadowRoot!.querySelector('[data-settings-anchor="reset-settings"] #settings-reset jf-operation')?.getAttribute('operation-id')).toBe('core.reset-settings');
  expect(el.shadowRoot!.querySelector('.header jf-operation')).toBeNull();
});

it('SG7 About names the version sources and observed states and keeps actions in General', async () => {
  const el = await mount();
  const meta = document.createElement('meta'); meta.name = 'justsearch-version'; meta.content = '2.4.0'; document.head.append(meta);
  try {
    requestMemberTab('core.settings-surface', 'about'); await settle(el);
    expect(el.shadowRoot!.querySelector('[data-testid="settings-version"]')?.textContent ?? '').toContain('Web bundle version: 2.4.0');
    expect(el.shadowRoot!.textContent).toContain('source: application version metadata');
    expect(el.shadowRoot!.textContent).toContain('Application updates are unavailable in the browser');
    el.host_ = createMockHostApi({ platform: { capabilities: new Set(['native-notifications']) } });
    el.requestUpdate(); await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Update information is unavailable from the desktop updater');
    for (const state of ['idle', 'up_to_date', 'error', 'repair_required', 'future-state']) {
      el.updateStatus = { state, currentVersion: '2.3.0', availableVersion: '2.5.0', error: state === 'error' ? 'Observation failed' : undefined };
      await settle(el);
      const version = el.shadowRoot!.querySelector('[data-testid="settings-version"]')?.textContent;
      expect(version).toContain('Web bundle version: 2.4.0'); expect(version).toContain('Desktop version: 2.3.0');
      const text = el.shadowRoot!.querySelector('[data-testid="settings-update-information"]')?.textContent;
      expect(text).toContain('2.5.0');
      if (state === 'idle') expect(text).toContain('No update check has been observed');
      else if (state === 'up_to_date') expect(text).toContain('last reported this version up to date');
      else expect(text).toContain(state);
      if (state === 'error') expect(text).toContain('Observation failed');
      if (state === 'repair_required') expect(text).toContain('signed installer');
    }
    expect(el.shadowRoot!.querySelector('jf-button[label="Check for updates"]')).toBeNull();
    (el.shadowRoot!.querySelector('jf-button[label="Open Updates"]') as HTMLElement & { onActivate(): void }).onActivate(); await settle(el);
    expect(el.activeCategory).toBe('general');
    expect(el.shadowRoot!.querySelector('jf-button[label="Check for updates"]')).not.toBeNull();
  } finally { meta.remove(); }
});

it('SG7 About says the bundle version is unknown when its metadata is absent', async () => {
  const el = await mount();
  requestMemberTab('core.settings-surface', 'about'); await settle(el);
  expect(el.shadowRoot!.querySelector('[data-testid="settings-version"]')?.textContent ?? '').toContain('Web bundle version: unknown');
});

it('SG8 Escape consumes theme JSON import and rename before the enclosing window can close', async () => {
  const el = await mount(); requestMemberTab('core.settings-surface', 'appearance'); await settle(el);
  let escapes = 0; el.addEventListener('keydown', e => { if ((e as KeyboardEvent).key === 'Escape' && !e.defaultPrevented) escapes++; });
  el.themeImporting = true; el.themeImportDraft = 'draft'; await settle(el);
  el.shadowRoot!.querySelector('textarea.theme-import-json')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
  await settle(el); expect(el.themeImporting).toBe(false); expect(escapes).toBe(0);
  el.renamingThemeId = 'draft-theme'; el.renameDraft = 'draft'; await settle(el);
  el.shadowRoot!.querySelector('.settings-root')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
  await settle(el); expect(el.renamingThemeId).toBeNull(); expect(escapes).toBe(0);
});

it('SG9 Gallery and Editor coexist with stable independent instances and sibling controls during rerenders', async () => {
  const el = await mount(); requestMemberTab('core.settings-surface', 'core.presentation-editor-surface'); await settle(el);
  const gallery = el.shadowRoot!.querySelector('jf-section-gallery-probe');
  const editor = el.shadowRoot!.querySelector('jf-section-editor-probe');
  expect(gallery).not.toBeNull(); expect(editor).not.toBeNull();
  const draft = editor!.shadowRoot!.querySelector('input')!; draft.value = 'Work in progress';
  el.saving = true; el.requestUpdate(); await settle(el);
  expect(el.shadowRoot!.querySelector('jf-section-gallery-probe')).toBe(gallery);
  expect(el.shadowRoot!.querySelector('jf-section-editor-probe')).toBe(editor);
  expect(draft.value).toBe('Work in progress');
  expect(el.shadowRoot!.querySelector('[data-settings-anchor="interface"]')).not.toBeNull();
  requestMemberTab('core.settings-surface', 'general'); await settle(el);
  expect(editor!.isConnected).toBe(false); expect(gallery!.isConnected).toBe(false);
  requestMemberTab('core.settings-surface', 'appearance'); await settle(el);
  expect(el.shadowRoot!.querySelector('jf-section-editor-probe')).toBe(editor);
  expect(editor!.shadowRoot!.querySelector('input')!.value).toBe('Work in progress');
});

it.each(['missing', 'loading', 'unregistered'])('SG9 a %s Gallery leaves the Editor and native sibling controls usable', async state => {
  if (state === 'loading') {
    vi.spyOn(lazySurfaces, 'isLazySurface').mockImplementation(tag => tag === 'jf-section-never-loaded');
    vi.spyOn(lazySurfaces, 'ensureSurfaceLoaded').mockResolvedValue(undefined);
  }
  seedCatalog({ schemaVersion: '1', catalogVersion: 2, namespace: 'core', primitive: 'Surface', entries: [
    ...(state !== 'missing' ? [member('core.presentation-gallery-surface', 'jf-section-never-loaded')] : []),
    member('core.presentation-editor-surface', 'jf-section-editor-probe'),
  ] });
  const el = await mount();
  requestMemberTab('core.settings-surface', 'appearance'); await settle(el);
  const editor = el.shadowRoot!.querySelector('jf-section-editor-probe');
  expect(editor).not.toBeNull();
  const draft = editor!.shadowRoot!.querySelector('input')!; draft.value = 'Still usable';
  const trigger = el.shadowRoot!.querySelector<HTMLElement & { onActivate(): void }>('jf-button[label="Import a theme from JSON"]');
  expect(trigger).not.toBeNull(); trigger!.onActivate(); await settle(el);
  expect(el.shadowRoot!.querySelector('textarea.theme-import-json')).not.toBeNull();
  expect(draft.value).toBe('Still usable');
});
