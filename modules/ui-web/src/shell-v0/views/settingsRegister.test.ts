// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * `searchRegister()` — tempdoc 855 §6 Phase 4: the register's second projection.
 *
 * Covers: category-label matches, section-label matches, group-label matches (fan out to every
 * category in the group), audience gating (a USER must not get a DEVELOPER-only section hit), and
 * the empty-query contract (`<jf-settings-nav>`'s signal to show the grouped view instead).
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { searchRegister, categoryLabel, SETTINGS_REGISTER, type SettingsGroup } from './settingsRegister.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import { getViewerAudience, setViewerAudience } from '../state/viewerAudienceState.js';
import { __resetUserStateForTest, mutateDocument } from '../state/UserStateDocument.js';
import {
  __seedForTest as seedSurfaceCatalog,
  __resetForTest as resetSurfaceCatalog,
} from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

const FIXTURE_REGISTER: readonly SettingsGroup[] = [
  {
    id: 'general',
    labelKey: 'settings.group.general',
    categories: [
      {
        id: 'appearance',
        kind: 'native',
        labelKey: 'settings.category.appearance',
        sections: [
          { key: 'interface', labelKey: 'settings.section.interface' },
          { key: 'theme', labelKey: 'settings.section.theme' },
        ],
      },
      {
        id: 'core.presentation-gallery-surface',
        kind: 'member',
        memberSurfaceId: 'core.presentation-gallery-surface',
      },
    ],
  },
  {
    id: 'app',
    labelKey: 'settings.group.app',
    categories: [
      {
        id: 'developer',
        kind: 'native',
        labelKey: 'settings.category.developer',
        sections: [
          { key: 'view-tier', labelKey: 'settings.section.view-tier' },
          {
            key: 'workspace-profiles',
            labelKey: 'settings.section.workspace-profiles',
            gate: () => getViewerAudience() === 'DEVELOPER',
          },
        ],
      },
    ],
  },
];

function surfaceOf(id: string, labelKey: string): Surface {
  return {
    id,
    presentation: { labelKey, descriptionKey: `${labelKey}.desc`, iconHint: null, category: null },
    audience: 'USER',
    placement: 'DEEPLINK',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag: 'jf-test-member-surface',
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  };
}

function catalogOf(...entries: Surface[]): SurfaceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries,
  };
}

describe('searchRegister', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest({
      'settings.group.general': 'General',
      'settings.group.app': 'App',
      'settings.category.appearance': 'Appearance',
      'settings.category.developer': 'Developer',
      'settings.section.interface': 'Interface',
      'settings.section.theme': 'Theme',
      'settings.section.view-tier': 'View tier',
      'settings.section.workspace-profiles': 'Workspace Profiles (developer)',
      'registry-surface.core.presentation-gallery-surface.label': 'Skins',
    });
    resetSurfaceCatalog();
    seedSurfaceCatalog(catalogOf(surfaceOf('core.presentation-gallery-surface', 'registry-surface.core.presentation-gallery-surface.label')));
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetForTest();
    resetSurfaceCatalog();
    __resetUserStateForTest();
  });

  it('an empty (or whitespace-only) query returns no results', () => {
    expect(searchRegister('', FIXTURE_REGISTER)).toEqual([]);
    expect(searchRegister('   ', FIXTURE_REGISTER)).toEqual([]);
  });

  it('matches a native category label, case-insensitively', () => {
    const results = searchRegister('APPEAR', FIXTURE_REGISTER);
    expect(results).toHaveLength(1);
    expect(results[0]!.category.id).toBe('appearance');
    expect(results[0]!.section).toBeUndefined();
  });

  it('matches a member category label via present()', () => {
    const results = searchRegister('skins', FIXTURE_REGISTER);
    expect(results).toHaveLength(1);
    expect(results[0]!.category.id).toBe('core.presentation-gallery-surface');
  });

  it('matches a sub-anchor section label and carries its category', () => {
    const results = searchRegister('theme', FIXTURE_REGISTER);
    expect(results).toHaveLength(1);
    expect(results[0]!.category.id).toBe('appearance');
    expect(results[0]!.section?.key).toBe('theme');
  });

  it('a group-label match fans out to every category in that group', () => {
    const results = searchRegister('general', FIXTURE_REGISTER);
    expect(results.map((r) => r.category.id).sort()).toEqual(
      ['appearance', 'core.presentation-gallery-surface'].sort(),
    );
    expect(results.every((r) => r.section === undefined)).toBe(true);
  });

  it('respects audience gating: a USER querying a DEVELOPER-only section gets no hit', () => {
    setViewerAudience('USER');
    const results = searchRegister('workspace', FIXTURE_REGISTER);
    expect(results).toEqual([]);
  });

  it('a DEVELOPER querying the same term DOES get the hit', () => {
    setViewerAudience('DEVELOPER');
    const results = searchRegister('workspace', FIXTURE_REGISTER);
    expect(results).toHaveLength(1);
    expect(results[0]!.section?.key).toBe('workspace-profiles');
  });

  it('SD1 View tier search follows developer tools independently of audience', () => {
    setViewerAudience('USER');
    expect(searchRegister('view tier', SETTINGS_REGISTER)).toEqual([]);
    mutateDocument(doc => ({ ...doc, showDeveloperTools: true }));
    const results = searchRegister('view tier', SETTINGS_REGISTER);
    expect(results).toHaveLength(1);
    expect(results[0]!.section?.key).toBe('view-tier');
    setViewerAudience('DEVELOPER');
    mutateDocument(doc => ({ ...doc, showDeveloperTools: false }));
    expect(searchRegister('view tier', SETTINGS_REGISTER)).toEqual([]);
  });

  it('no match returns an empty array', () => {
    expect(searchRegister('zzz-nonexistent', FIXTURE_REGISTER)).toEqual([]);
  });
});

describe('categoryLabel', () => {
  beforeEach(() => {
    __resetForTest();
    __seedForTest({ 'settings.category.appearance': 'Appearance' });
  });

  afterEach(() => {
    __resetForTest();
  });

  it('resolves a native category through localizeResourceKey', () => {
    expect(
      categoryLabel({ id: 'appearance', kind: 'native', labelKey: 'settings.category.appearance' }),
    ).toBe('Appearance');
  });
});

describe('the Layout sub-anchor in the shipped words (shell slice 2)', () => {
  afterEach(() => __resetForTest());

  it('the Settings search indexes the switcher list by the nav\'s own label: "switcher" finds it, "rail" does not', () => {
    // The shipped bundle, not a test translation: the nav and the search read the same message.
    const bundle = readFileSync(resolve('../app-api/src/main/resources/messages/registry-surface.en.properties'), 'utf8');
    __seedForTest(Object.fromEntries(bundle.split(/\r?\n/).filter((line) => line.startsWith('settings.'))
      .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)])));
    const hits = (query: string) => searchRegister(query, SETTINGS_REGISTER)
      .filter((r) => r.category.id === 'appearance' && r.section?.key === 'rail');
    expect(hits('switcher')).toHaveLength(1);
    expect(hits('rail')).toEqual([]);
  });
});
