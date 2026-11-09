// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it } from 'vitest';
import { __resetUserStateForTest, __resetInMemoryStateForTest, getAllWalkthroughProgress,
  getWalkthroughProgress, mutateDocument, createProfile, setActiveProfileId } from './UserStateDocument.js';

// The baseline fails assertions about the absent feature, rather than failing to load the suite.
const modules = import.meta.glob('./firstUse.ts', { eager: true });
const api = Object.values(modules)[0] as typeof import('./firstUse.js') | undefined;
const facts = { roots: 0, searchable: 0, waiting: 0, reading: false, aiActive: false };
const project = (...args: Parameters<NonNullable<typeof api>['projectFirstUse']>) => api?.projectFirstUse(...args) ?? {};
beforeEach(() => __resetUserStateForTest());

describe('Getting started facts (SB1–SB11)', () => {
  it('unknown → zero → positive never presents unknown as empty', () => {
    expect(project({ ...facts, roots: null, searchable: null })).toMatchObject({ empty: false, visible: false });
    expect(project({ ...facts, searchable: null })).toMatchObject({ empty: false, visible: false });
    expect(project(facts)).toMatchObject({ empty: true, visible: true, done: [false, false, false] });
    expect(project({ ...facts, roots: 1, searchable: 2 })).toMatchObject({ empty: false, done: [true, false, false] });
  });
  it('live progress names observed searchable and waiting counts, with no invented total or time', () => {
    expect(project({ ...facts, roots: 1, searchable: 12, waiting: 8, reading: true })).toMatchObject({ progress: '12 files searchable so far · 8 waiting to be read' });
    expect(project({ ...facts, roots: 1, searchable: 1, waiting: null, reading: true })).toMatchObject({ progress: '1 file searchable so far · still reading' });
    expect(project({ ...facts, roots: 1, searchable: null, waiting: null })).toMatchObject({ progress: null, empty: false });
  });
  it('a watched folder with zero searchable documents points to Library and does not finish', () => {
    expect(project({ ...facts, roots: 1 })).toMatchObject({ visible: true, done: [true, false, false], folderNeedsAttention: true });
  });
  it('ad-hoc documents need no watched folder, and unsupported hardware needs no AI', () => {
    expect(project({ ...facts, searchable: 4 })).toMatchObject({ empty: false, done: [true, false, false] });
    expect(project({ ...facts, searchable: 4 }, { activeStepIndex: 0, completedStepIds: ['try-search'], dismissed: false })).toMatchObject({ visible: false, done: [true, true, false] });
  });
  for (const [name, old, suppress] of [
    ['absent', undefined, false],
    ['partial', { activeStepIndex: 2, completedStepIds: ['open-palette'], dismissed: false }, false],
    ['finished', { activeStepIndex: 4, completedStepIds: [], dismissed: false }, true],
    ['dismissed', { activeStepIndex: 0, completedStepIds: [], dismissed: true }, true],
    ['malformed', { activeStepIndex: 'four', completedStepIds: null, dismissed: 'yes' }, false],
  ] as const) {
    for (const roots of [0, 1]) it(`old welcome ${name}, ${roots ? 'populated' : 'empty'} engine`, () => {
      expect(project({ ...facts, roots, searchable: roots ? 10 : 0 }, undefined, old)).toMatchObject({ visible: !(roots && suppress), done: [!!roots, false, false] });
    });
  }
  it('Hide and Help reopen survive reload, keep old/plugin records and co-resident values', () => {
    mutateDocument(d => ({ ...d, chatWidth: 'wide', walkthroughState: {
      welcome: { activeStepIndex: 4, completedStepIds: ['old'], dismissed: true },
      'plugin.tour': { activeStepIndex: 1, completedStepIds: ['plugin-step'], dismissed: false },
    } }));
    api?.hideGettingStarted();
    __resetInMemoryStateForTest();
    expect(project(facts, getWalkthroughProgress('core.getting-started'))).toMatchObject({ visible: false });
    api?.reopenGettingStarted();
    __resetInMemoryStateForTest();
    expect(project({ ...facts, roots: 1, searchable: 1 }, getWalkthroughProgress('core.getting-started'), getWalkthroughProgress('welcome'))).toMatchObject({ visible: true, reopened: true });
    expect(getAllWalkthroughProgress()).toMatchObject({ welcome: { completedStepIds: ['old'], dismissed: true }, 'plugin.tour': { completedStepIds: ['plugin-step'] } });
  });
  it('ordinary profile switch keeps progress, completed factory reset starts fresh', () => {
    api?.hideGettingStarted();
    createProfile('work', 'Work'); setActiveProfileId('work');
    expect(project(facts, getWalkthroughProgress('core.getting-started'))).toMatchObject({ visible: false });
    __resetUserStateForTest();
    expect(project(facts, getWalkthroughProgress('core.getting-started'))).toMatchObject({ visible: true, done: [false, false, false] });
  });
});
