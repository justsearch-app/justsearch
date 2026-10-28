// @vitest-environment happy-dom

/**
 * Tempdoc 577 Goal 1 Phase 8 (570 Move C) — the search result's core verb-space
 * contributes through the ONE ContextActionRegistry seam.
 *
 * Pins: boot registration lists the verbs for the `search-result-row` context
 * (so `openContextMenu` appends them automatically); `enabled` gates on a
 * payload path; handlers ride the platform capability / clipboard seams.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('../plugin-api/capabilities/platform.js', () => ({
  openLocalFile: vi.fn(() => Promise.resolve()),
  revealLocalPath: vi.fn(() => Promise.resolve()),
}));
vi.mock('../utils/clipboardCopy.js', () => ({
  copyToClipboard: vi.fn(() => Promise.resolve()),
}));

import {
  registerSearchResultActions,
  __resetSearchResultActionsForTest,
  SEARCH_RESULT_CONTEXT,
} from './searchResultActions.js';
import { listContextActions, __resetForTest } from './ContextActionRegistry.js';
import { openLocalFile, revealLocalPath } from '../plugin-api/capabilities/platform.js';
import { copyToClipboard } from '../utils/clipboardCopy.js';

const HIT = { id: 'a', title: 'a.md', path: 'f:\\docs\\a.md' };

describe('searchResultActions (577 Phase 8)', () => {
  beforeEach(() => {
    __resetForTest();
    __resetSearchResultActionsForTest();
    vi.clearAllMocks();
    registerSearchResultActions();
  });

  it('registers the core verbs for the search-result-row context, in priority order', () => {
    const actions = listContextActions(SEARCH_RESULT_CONTEXT, HIT);
    expect(actions.map((a) => a.id)).toEqual([
      'search-result.open',
      'search-result.reveal',
      'search-result.copy-path',
    ]);
  });

  it('is idempotent — a second boot call does not duplicate the verbs', () => {
    registerSearchResultActions();
    expect(listContextActions(SEARCH_RESULT_CONTEXT, HIT)).toHaveLength(3);
  });

  it('verbs are disabled for a payload without a path', () => {
    expect(listContextActions(SEARCH_RESULT_CONTEXT, { id: 'x' })).toHaveLength(0);
  });

  it('handlers ride the one platform/clipboard seams', async () => {
    const actions = listContextActions(SEARCH_RESULT_CONTEXT, HIT);
    await actions.find((a) => a.id === 'search-result.open')!.handler(HIT);
    expect(openLocalFile).toHaveBeenCalledWith(HIT.path);
    await actions.find((a) => a.id === 'search-result.reveal')!.handler(HIT);
    expect(revealLocalPath).toHaveBeenCalledWith(HIT.path);
    await actions.find((a) => a.id === 'search-result.copy-path')!.handler(HIT);
    expect(copyToClipboard).toHaveBeenCalledWith(HIT.path);
  });

  it('S56.6 offers forget only to the host callback and leaves all ordinary verbs available', async () => {
    const forget = vi.fn();
    const supported = { ...HIT, forget };
    const actions = listContextActions(SEARCH_RESULT_CONTEXT, supported);
    expect(actions.map((action) => action.id)).toEqual([
      'search-result.open', 'search-result.reveal', 'search-result.copy-path',
      'search-result.forget-file', 'search-result.forget-folder',
    ]);
    await actions.find((action) => action.id === 'search-result.forget-file')!.handler(supported);
    await actions.find((action) => action.id === 'search-result.forget-folder')!.handler(supported);
    expect(forget.mock.calls).toEqual([['FILE'], ['FOLDER']]);
    expect(listContextActions(SEARCH_RESULT_CONTEXT, HIT)).toHaveLength(3);
  });

  it('S56.9 keeps help and agent-history verbs absent even in a capable host', () => {
    for (const collection of ['justsearch-help', 'agent-history']) {
      const actions = listContextActions(SEARCH_RESULT_CONTEXT, { ...HIT, collection, forget: vi.fn() });
      expect(actions.map((action) => action.id)).toEqual([
        'search-result.open', 'search-result.reveal', 'search-result.copy-path',
      ]);
    }
  });
});
