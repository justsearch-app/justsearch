// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it } from 'vitest';
import { getShowDeveloperTools, setShowDeveloperTools, subscribeShowDeveloperTools } from './developerToolsState.js';
import { __resetUserStateForTest, createProfile, setActiveProfileId, getDocument } from './UserStateDocument.js';
import { setViewerAudience } from './viewerAudienceState.js';
beforeEach(() => __resetUserStateForTest());
afterEach(() => __resetUserStateForTest());

it('SD2 SD3 notifies only effective changes, including audience and equal-value profile switches', () => {
  const seen: boolean[] = [];
  const unsubscribe = subscribeShowDeveloperTools(value => seen.push(value));
  setViewerAudience('OPERATOR');
  setViewerAudience('AGENT');
  expect(seen).toEqual([false]);
  setViewerAudience('DEVELOPER');
  createProfile('other', 'Other');
  setActiveProfileId('other');
  expect(getShowDeveloperTools()).toBe(false);
  setShowDeveloperTools(true);
  setActiveProfileId('default');
  expect(seen).toEqual([false, true, false, true]);
  expect(getDocument().showDeveloperTools).toBeUndefined();
  setShowDeveloperTools(false);
  setViewerAudience('USER');
  expect(seen).toEqual([false, true, false, true, false]);
  unsubscribe();
  setShowDeveloperTools(true);
  expect(seen).toHaveLength(5);
});
