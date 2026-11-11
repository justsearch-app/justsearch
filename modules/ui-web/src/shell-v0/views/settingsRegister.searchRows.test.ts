// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { searchRegister, SETTINGS_REGISTER, type SettingsGroup, type SettingsViewer } from './settingsRegister.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import { __resetUserStateForTest, mutateDocument } from '../state/UserStateDocument.js';
import { setViewerAudience } from '../state/viewerAudienceState.js';
import { applyPresentationBodies, __resetPresentationForTest } from '../state/presentationRuntime.js';
import { SETTINGS_DECLARED } from '../themes/builtinPresentations.js';

const desktop: SettingsViewer = { capabilities: new Set(['native-notifications']), settingsRecovery: false, settingsReadOnly: false };
const keys = (query: string, viewer?: SettingsViewer) => searchRegister(query, SETTINGS_REGISTER, viewer).map(hit => hit.section?.key);

beforeEach(() => {
  __resetUserStateForTest(); __resetForTest(); __resetPresentationForTest();
  const bundle = readFileSync('../app-api/src/main/resources/messages/registry-surface.en.properties', 'utf8');
  __seedForTest(Object.fromEntries(bundle.split(/\r?\n/).filter(line => line.includes('='))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)])));
});
afterEach(() => { __resetUserStateForTest(); __resetForTest(); __resetPresentationForTest(); });

it('SR1 finds control labels in native rows and the declared Interface region', () => {
  expect(keys('DARK')).toContain('interface');
  expect(keys('reduce motion')).toContain('accessibility');
  applyPresentationBodies(SETTINGS_DECLARED);
  expect(keys('dark')).toContain('interface');
  expect(keys('vim keybindings')).toContain('interface');
  expect(keys('sound')).toContain('notifications');
  expect(keys('wide')).toContain('chat-width');
});

it('SR1 matches at word starts, all query words in any order, across labels and control words', () => {
  expect(keys('MOT red')).toContain('accessibility');
  expect(keys('access reduce')).toContain('accessibility');
  expect(keys('otion')).not.toContain('accessibility');
  expect(keys('reduce nonexistent')).toEqual([]);
  expect(keys('   ')).toEqual([]);
});

it('SR1 ranks label hits before keyword hits before control hits, with stable ties', () => {
  const register: SettingsGroup[] = [{ id: 'fixture', labelKey: 'Other', categories: [{
    id: 'fixture', kind: 'native', labelKey: 'Other', sections: [
      { key: 'control', labelKey: 'First', controlWords: () => ['Needle'] },
      { key: 'keyword', labelKey: 'Second', keywords: ['Needle'] },
      { key: 'label', labelKey: 'Needle' },
      { key: 'tie', labelKey: 'Needle too' },
    ],
  }] }];
  expect(searchRegister('need', register).map(hit => hit.section?.key)).toEqual(['label', 'tie', 'keyword', 'control']);
});

it('SR1 finds short keywords and exposes destinations without a Settings mount', () => {
  expect(keys('hotkey', desktop)).toContain('desktop');
  expect(keys('shortcut', desktop)).toContain('desktop');
  expect(keys('schedule')).toContain('indexing');
  expect(keys('battery')).toContain('indexing');
  expect(keys('tokens')).toContain('context-window');
  expect(keys('context window')).toContain('context-window');
  const hit = searchRegister('reduce motion')[0];
  expect(hit?.destination).toEqual({ kind: 'settings', section: 'appearance', anchor: 'accessibility' });
});

it('SR1 finds member controls from the same vocabulary as their renderers', () => {
  expect(keys('auto lock')).toContain('core.security-surface');
  expect(keys('import certify')).toContain('core.presentation-gallery-surface');
  expect(keys('generate prompt')).toContain('core.presentation-editor-surface');
  expect(keys('assist')).toContain('agent-autonomy');
});

it('SR3 names external destinations in results', () => {
  const indexing = searchRegister('schedule').find(hit => hit.section?.key === 'indexing');
  expect(indexing?.label).toBe('Library › When to index');
  expect(indexing?.destination).toEqual({ kind: 'indexing-hours' });
  const memory = searchRegister('tokens').find(hit => hit.section?.key === 'context-window');
  expect(memory?.label).toBe('AI Brain › Reading memory');
  expect(memory?.destination).toEqual({ kind: 'reading-memory' });
});

it('SR4 hides the conditional switcher Reset control until overrides exist', () => {
  expect(keys('reset')).not.toContain('rail');
  mutateDocument(doc => ({ ...doc, userConfig: { ...doc.userConfig, surfaceOrder: [] } }));
  expect(keys('reset')).toContain('rail');
});

it('SR4 hides platform control words when the desktop capability is absent', () => {
  expect(keys('launch on startup', desktop)).toContain('desktop');
  expect(keys('launch on startup')).not.toContain('desktop');
  expect(keys('hotkey')).not.toContain('desktop');
});

it('SR4 applies audience and developer-tool gates to control-word hits', () => {
  mutateDocument(doc => ({ ...doc, showDeveloperTools: true }));
  setViewerAudience('DEVELOPER');
  expect(keys('snapshot')).toContain('workspace-profiles');
  expect(keys('operator')).toContain('view-tier');
  setViewerAudience('USER');
  expect(keys('snapshot')).not.toContain('workspace-profiles');
  expect(keys('operator')).toContain('view-tier');
  mutateDocument(doc => ({ ...doc, showDeveloperTools: false }));
  expect(keys('operator')).not.toContain('view-tier');
});

it('SR4 applies saved-settings visibility before matching control words or keywords', () => {
  const register: SettingsGroup[] = [{ id: 'fixture', labelKey: 'Other', categories: [{
    id: 'fixture', kind: 'native', labelKey: 'Other', sections: [
      { key: 'saved', labelKey: 'Saved', savedSettings: true, controlWords: () => ['Retain choice'], keywords: ['restore'] },
    ],
  }] }];
  expect(searchRegister('retain', register, desktop).map(hit => hit.section?.key)).toEqual(['saved']);
  expect(searchRegister('retain', register, { ...desktop, settingsReadOnly: true })).toEqual([]);
  expect(searchRegister('restore', register, { ...desktop, settingsReadOnly: true })).toEqual([]);
});
