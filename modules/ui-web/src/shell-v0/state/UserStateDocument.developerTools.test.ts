// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it } from 'vitest';
import { __resetUserStateForTest, __resetInMemoryStateForTest, getDocument, mutateDocument, createProfile, setActiveProfileId } from './UserStateDocument.js';

const KEY = 'justsearch.userState.v2';
const profile = (extra: Record<string, unknown> = {}) => ({ id: 'default', label: 'Default', userConfig: { version: 1 }, activeThemeId: null, pinnedSearches: [], ...extra });
const seed = (extra: Record<string, unknown> = {}) => localStorage.setItem(KEY, JSON.stringify({ version: 2, activeProfileId: 'default', profiles: { default: profile(extra) } }));
beforeEach(() => __resetUserStateForTest());
afterEach(() => __resetUserStateForTest());

it.each([true, false])('SD5 round-trip and user-state export/import retain explicit %s', value => {
  seed({ showDeveloperTools: value });
  expect(getDocument().showDeveloperTools).toBe(value);
  mutateDocument(doc => ({ ...doc, activeThemeId: 'theme.changed' }));
  const exported = localStorage.getItem(KEY)!;
  expect(JSON.parse(exported).profiles.default.showDeveloperTools).toBe(value);
  __resetUserStateForTest();
  localStorage.setItem(KEY, exported);
  expect(getDocument().showDeveloperTools).toBe(value);
});

it.each([true, false])('SD5 implicit and explicit profile bases copy %s without touching the basis', value => {
  seed({ showDeveloperTools: value });
  getDocument();
  createProfile('implicit', 'Implicit');
  createProfile('explicit', 'Explicit', 'default');
  const profiles = JSON.parse(localStorage.getItem(KEY)!).profiles;
  expect(profiles.default.showDeveloperTools).toBe(value);
  expect(profiles.implicit.showDeveloperTools).toBe(value);
  expect(profiles.explicit.showDeveloperTools).toBe(value);
  setActiveProfileId('implicit');
  expect(getDocument().showDeveloperTools).toBe(value);
  __resetInMemoryStateForTest();
  expect(getDocument().showDeveloperTools).toBe(value);
});

it('SD5 schema-2 boot and read-only projection leave the absent field and original bytes alone', () => {
  seed({ viewerAudience: 'DEVELOPER' });
  const original = localStorage.getItem(KEY);
  expect(getDocument().showDeveloperTools).toBeUndefined();
  expect(getDocument().profiles.default).not.toHaveProperty('showDeveloperTools');
  expect(localStorage.getItem(KEY)).toBe(original);
  __resetInMemoryStateForTest();
  getDocument();
  expect(localStorage.getItem(KEY)).toBe(original);
});

it('SD5 newer-schema boot is not overwritten', () => {
  const raw = JSON.stringify({ version: 999, future: 'kept' });
  localStorage.setItem(KEY, raw);
  getDocument();
  expect(localStorage.getItem(KEY)).toBe(raw);
});

it('SD5 accepts the new boolean, rejects invalid values and keeps the unknown-field contract', () => {
  seed({ showDeveloperTools: false, futureProfileField: 'ignored' });
  expect(getDocument().profiles.default).toMatchObject({ showDeveloperTools: false });
  expect(getDocument().profiles.default).not.toHaveProperty('futureProfileField');
  mutateDocument(doc => ({ ...doc, activeThemeId: 'theme.changed' }));
  expect(JSON.parse(localStorage.getItem(KEY)!).profiles.default).not.toHaveProperty('futureProfileField');
  __resetUserStateForTest();
  seed({ showDeveloperTools: 'true' });
  expect(getDocument().showDeveloperTools).toBeUndefined();
  mutateDocument(doc => ({ ...doc, activeThemeId: 'theme.changed' }));
  expect(JSON.parse(localStorage.getItem(KEY)!).profiles.default).not.toHaveProperty('showDeveloperTools');
});
