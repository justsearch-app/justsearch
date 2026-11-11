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
import type { Operation } from '../../api/types/registry.js';
import { __resetForTest as resetOperations, __seedForTest as seedOperations } from '../../api/registry/OperationCatalogClient.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';

const homes: Record<string, string[]> = {
  general: ['desktop', 'notifications', 'app-updates', 'keyboard', 'indexing', 'context-window'],
  appearance: ['interface', 'theme', 'core.presentation-gallery-surface', 'core.presentation-editor-surface', 'token-editor', 'layout', 'rail', 'chat-width', 'accessibility'],
  'security-privacy': ['core.security-surface', 'durable-grants', 'plugin-permissions', 'agent-autonomy', 'feedback-capture'],
  'data-reset': ['reset-settings', 'data'],
  plugins: ['plugins', 'delivered-contributions'],
  advanced: ['show-developer-tools', 'view-tier', 'workspace-profiles'],
  about: ['version', 'update-information'],
};
const settle = async (el: SettingsSurface) => { await el.updateComplete; await new Promise(r => setTimeout(r, 0)); await el.updateComplete; };
async function mount(capabilities: string[] = [], settings = '{"ui":{}}'): Promise<SettingsSurface> {
  const el = document.createElement('jf-settings-surface') as SettingsSurface;
  el.host_ = createMockHostApi({ data: { fetch: async () => new Response(settings) }, platform: { capabilities: new Set(capabilities) } });
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
afterEach(() => {
  document.body.replaceChildren(); resetCatalog(); resetOperations(); __resetAiStateForTest(); __resetUserStateForTest(); vi.restoreAllMocks();
});
/** The Engine's Reset to defaults operation: offered to OPERATOR and DEVELOPER by its audience policy. */
function seedReset(): void {
  const reset: Operation = {
    id: 'core.reset-settings', audience: 'OPERATOR',
    presentation: { labelKey: 'ops.reset-settings.label', descriptionKey: 'ops.reset-settings.description' },
    intf: { inputs: {}, result: {}, errors: [], uiHints: {} },
    policy: { risk: 'MEDIUM', confirm: { kind: 'INLINE' }, audit: 'METADATA_ONLY', undoSupported: false },
    availability: {}, lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' }, executors: ['UI'], consumers: [],
  };
  seedOperations({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries: [reset] });
}
/** Entry keys the navigation lists for the open section, and the entry keys its search offers for `query`. */
async function offered(el: SettingsSurface, query: string): Promise<{ content: string[]; navigation: string[]; search: string[] }> {
  const nav = el.shadowRoot!.querySelector<SettingsNav>('jf-settings-nav')!;
  nav.query = ''; await nav.updateComplete;
  const keys = (root: ParentNode, selector: string) => [...root.querySelectorAll<HTMLElement>(selector)].map(e => e.dataset.settingsAnchor ?? '');
  const content = keys(el.shadowRoot!, '[data-settings-anchor]');
  const navigation = keys(nav.shadowRoot!, '.anchor-list [data-settings-anchor]');
  nav.query = query; await nav.updateComplete;
  const search = keys(nav.shadowRoot!, '.search-result-row[data-settings-anchor]');
  nav.query = ''; await nav.updateComplete;
  return { content, navigation, search };
}

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
    developer: ['advanced', 'show-developer-tools'], data: ['data-reset', 'data'], plugins: ['plugins', 'plugins'],
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
  expect(el.shadowRoot!.activeElement?.id).toBe('show-developer-tools');
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
  expect(el.shadowRoot!.activeElement?.id).toBe('show-developer-tools');
  expect(el.activeCategory).toBe('advanced');
});

it('SG5 recovery selects Data & reset and the existing Reset anchor without invoking reset', async () => {
  seedReset(); setViewerAudience('OPERATOR');
  const el = await mount();
  void el.focusSettingsRecovery(); await settle(el);
  expect(el.activeCategory).toBe('data-reset');
  expect(el.shadowRoot!.querySelector('[data-settings-anchor="reset-settings"] #settings-reset jf-operation')?.getAttribute('operation-id')).toBe('core.reset-settings');
  expect(el.shadowRoot!.querySelector('.header jf-operation')).toBeNull();
});

it.each(['USER', 'OPERATOR', 'DEVELOPER'] as const)('SG5 SG6 Reset to defaults is listed, searched and rendered only where its operation policy offers it (%s)', async audience => {
  seedReset(); setViewerAudience(audience);
  const el = await mount();
  requestMemberTab('core.settings-surface', 'data-reset'); await settle(el);
  const found = await offered(el, 'reset');
  const offeredHere = audience !== 'USER';
  expect(found.content.includes('reset-settings'), 'content').toBe(offeredHere);
  expect(found.navigation.includes('reset-settings'), 'navigation').toBe(offeredHere);
  expect(found.search.includes('reset-settings'), 'search').toBe(offeredHere);
  expect(el.shadowRoot!.querySelector('[data-settings-anchor="reset-settings"] h3')?.textContent ?? null)
    .toBe(offeredHere ? 'Reset settings' : null);
  if (offeredHere) expect(el.shadowRoot!.querySelector('#settings-reset jf-operation')?.getAttribute('operation-id')).toBe('core.reset-settings');
  expect(found.content).toContain('data');
});

it('SG5 SG6 settings recovery offers Reset to a USER in content, navigation and search; ending it removes Reset everywhere', async () => {
  seedReset(); setViewerAudience('USER');
  const indexing = (reasons: string[]) => ({ operating: { indexing: { state: reasons.length ? 'PAUSED' : 'RUNNING', reasons,
    untilEpochMs: null, waitingForResume: false } } }) as unknown as StatusSnapshot;
  __feedForTest({ status: indexing(['settings_recovery']) });
  const el = await mount();
  requestMemberTab('core.settings-surface', 'data-reset'); await settle(el);
  expect(await offered(el, 'reset')).toMatchObject({ content: ['reset-settings', 'data'], navigation: ['reset-settings', 'data'], search: ['reset-settings'] });
  __feedForTest({ status: indexing([]) }); await settle(el);
  expect(await offered(el, 'reset')).toMatchObject({ content: ['data'], navigation: ['data'], search: [] });
});

it.each(['OPERATOR', 'DEVELOPER', 'USER in settings recovery'] as const)('SG5 SG6 in Session-only mode Reset to defaults is neither rendered empty, listed, searched nor arrived at (%s)', async viewer => {
  seedReset(); setViewerAudience(viewer === 'USER in settings recovery' ? 'USER' : viewer);
  if (viewer === 'USER in settings recovery') {
    __feedForTest({ status: { operating: { indexing: { state: 'PAUSED', reasons: ['settings_recovery'],
      untilEpochMs: null, waitingForResume: false } } } as unknown as StatusSnapshot });
  }
  const el = await mount([], '{"ui":{},"settingsMode":"in_memory"}');
  expect(el.shadowRoot!.querySelector('.header jf-status-badge')?.textContent, 'Session-only mode').toContain('Session-only');
  requestMemberTab('core.settings-surface', 'data-reset'); await settle(el);
  const found = await offered(el, 'reset');
  expect(found).toMatchObject({ content: ['data'], navigation: ['data'], search: [] });
  expect([...el.shadowRoot!.querySelectorAll('h3')].map(h => h.textContent?.trim()), 'no empty Reset heading').not.toContain('Reset settings');
  // An old Reset link and recovery open Data & reset without explaining or focusing a missing row.
  el.activeCategory = 'general'; await settle(el);
  requestMemberTab('core.settings-surface', 'data', 'reset-settings'); await settle(el);
  expect(el.activeCategory).toBe('data-reset');
  expect(el.shadowRoot!.querySelector('.settings-content-inner > [role="status"]'), 'no hidden-destination explanation').toBeNull();
  await el.focusSettingsRecovery(); await settle(el);
  expect(el.activeCategory).toBe('data-reset');
  expect(el.shadowRoot!.querySelector('[data-settings-anchor="reset-settings"]')).toBeNull();
});

it.each([['browser', []], ['desktop', ['native-notifications']]] as const)('SG6 SG7 in the %s, rows this platform renders are the rows navigation, search and About offer', async (platform, capabilities) => {
  const el = await mount([...capabilities]);
  const desktop = platform === 'desktop';
  const general = await offered(el, 'updates');
  const nativeRows = ['desktop', 'app-updates'];
  for (const key of nativeRows) {
    expect(general.content.includes(key), `content ${key}`).toBe(desktop);
    expect(general.navigation.includes(key), `navigation ${key}`).toBe(desktop);
  }
  expect(general.search.includes('app-updates'), 'search updates').toBe(desktop);
  expect((await offered(el, 'desktop')).search.includes('desktop'), 'search desktop').toBe(desktop);
  for (const key of general.content) {
    expect(el.shadowRoot!.querySelector(`[data-settings-anchor="${key}"]`)?.textContent?.trim(), `${key} renders something`).not.toBe('');
  }
  requestMemberTab('core.settings-surface', 'about'); await settle(el);
  const open = el.shadowRoot!.querySelector<HTMLElement & { onActivate(): void }>('jf-button[label="Open Updates"]');
  expect(Boolean(open), 'About links to Updates').toBe(desktop);
  if (open) {
    open.onActivate(); await settle(el);
    expect(el.activeCategory).toBe('general');
    expect(el.shadowRoot!.querySelector('jf-button[label="Check for updates"]')).not.toBeNull();
  }
  // An old update link opens General; a row this platform lacks is neither explained nor focused.
  requestMemberTab('core.settings-surface', 'desktop', 'app-updates'); await settle(el);
  expect(el.activeCategory).toBe('general');
  expect(el.shadowRoot!.querySelector('.settings-content-inner > [role="status"]'), 'no hidden-destination explanation').toBeNull();
  expect(el.shadowRoot!.activeElement?.getAttribute('data-settings-anchor') ?? null).toBe(desktop ? 'app-updates' : null);
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
