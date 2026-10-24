// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  recordNavigation,
  getJournalState,
  subscribeJournal,
  canGoBack,
  canGoForward,
  peekBack,
  peekForward,
  navigateBack,
  navigateForward,
  isNavigatingHistoryNow,
  __resetJournalForTest,
} from './NavigationJournal.js';

describe('NavigationJournal', () => {
  beforeEach(() => {
    __resetJournalForTest();
    // Reset hash to prevent cross-test contamination from snapshotCurrentEntry.
    window.location.hash = '';
  });

  describe('recording', () => {
    it('starts empty', () => {
      const state = getJournalState();
      expect(state.entries).toEqual([]);
      expect(state.cursor).toBe(-1);
    });

    it('records a navigation entry', () => {
      recordNavigation('core.search-surface', 'justsearch://surface/core.search-surface', 'Search', 'RAIL');
      const state = getJournalState();
      expect(state.entries).toHaveLength(1);
      expect(state.cursor).toBe(0);
      expect(state.entries[0]!.surfaceId).toBe('core.search-surface');
      expect(state.entries[0]!.label).toBe('Search');
      expect(state.entries[0]!.transport).toBe('RAIL');
    });

    it('advances cursor on subsequent navigations', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      recordNavigation('core.health-surface', 'url3', 'Health', 'RAIL');
      const state = getJournalState();
      expect(state.entries).toHaveLength(3);
      expect(state.cursor).toBe(2);
    });

    it('truncates forward entries on branch', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      recordNavigation('core.health-surface', 'url3', 'Health', 'RAIL');

      const mockDispatch = vi.fn().mockResolvedValue(undefined);
      await navigateBack(mockDispatch);
      // cursor now at 1 (Library)
      expect(getJournalState().cursor).toBe(1);

      // New navigation from this point truncates Health
      recordNavigation('core.settings-surface', 'url4', 'Settings', 'RAIL');
      const state = getJournalState();
      expect(state.entries).toHaveLength(3); // Search, Library, Settings
      expect(state.cursor).toBe(2);
      expect(state.entries[2]!.surfaceId).toBe('core.settings-surface');
    });

    it('snapshots departing surface state on new navigation', () => {
      recordNavigation('core.search-surface', 'justsearch://surface/core.search-surface', 'Search', 'RAIL');
      // Simulate URLProjector replaceState updating the hash (user typed a query)
      window.location.hash = '#justsearch://surface/core.search-surface?query=rust';
      // Navigate away — the snapshot should update the Search entry
      recordNavigation('core.library-surface', 'justsearch://surface/core.library-surface', 'Library', 'RAIL');
      const state = getJournalState();
      expect(state.entries[0]!.url).toBe('justsearch://surface/core.search-surface?query=rust');
    });

    it('R2 keeps an incoming recovery URL out of the departing surface journal entry', () => {
      const stage = 'justsearch://surface/core.unified-chat-surface';
      recordNavigation('core.unified-chat-surface', stage, 'Search', 'RAIL');
      window.location.hash = '#justsearch://surface/core.settings-surface?focusSettingsRecovery=true';
      recordNavigation('core.settings-surface', 'justsearch://surface/core.settings-surface', 'Settings', 'URL_BAR');
      const state = getJournalState();
      expect(state.entries[0]!.url).toBe(stage);
      expect(state.entries.every(entry => !entry.url.includes('focusSettingsRecovery'))).toBe(true);
    });

    it('de-duplicates consecutive same-surface navigations', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.search-surface', 'url2', 'Search', 'RAIL');
      recordNavigation('core.search-surface', 'url3', 'Search', 'BUTTON');
      const state = getJournalState();
      expect(state.entries).toHaveLength(1);
      expect(state.cursor).toBe(0);
      // Last URL/transport wins
      expect(state.entries[0]!.url).toBe('url3');
      expect(state.entries[0]!.transport).toBe('BUTTON');
    });

    it('does not de-duplicate different surfaces', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      recordNavigation('core.search-surface', 'url3', 'Search', 'RAIL');
      expect(getJournalState().entries).toHaveLength(3);
    });

    it('evicts oldest entries at capacity', () => {
      for (let i = 0; i < 55; i++) {
        recordNavigation(`core.surface-${i}`, `url-${i}`, `Surface ${i}`, 'RAIL');
      }
      const state = getJournalState();
      expect(state.entries).toHaveLength(50);
      expect(state.entries[0]!.surfaceId).toBe('core.surface-5');
      expect(state.cursor).toBe(49);
    });
  });

  describe('back/forward state', () => {
    it('canGoBack is false when empty', () => {
      expect(canGoBack()).toBe(false);
    });

    it('canGoBack is false with one entry', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      expect(canGoBack()).toBe(false);
    });

    it('canGoBack is true with two entries', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      expect(canGoBack()).toBe(true);
    });

    it('canGoForward is false at end', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      expect(canGoForward()).toBe(false);
    });

    it('canGoForward is true after navigateBack', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      const mockDispatch = vi.fn().mockResolvedValue(undefined);
      await navigateBack(mockDispatch);
      expect(canGoForward()).toBe(true);
    });
  });

  describe('peek', () => {
    it('peekBack returns null when at start', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      expect(peekBack()).toBeNull();
    });

    it('peekBack returns previous entry', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      expect(peekBack()!.surfaceId).toBe('core.search-surface');
    });

    it('peekForward returns null at end', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      expect(peekForward()).toBeNull();
    });

    it('peekForward returns next entry after navigateBack', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      const mockDispatch = vi.fn().mockResolvedValue(undefined);
      await navigateBack(mockDispatch);
      expect(peekForward()!.surfaceId).toBe('core.library-surface');
    });
  });

  describe('navigateBack/Forward', () => {
    it('navigateBack dispatches previous entry', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      const mockDispatch = vi.fn().mockResolvedValue(undefined);

      await navigateBack(mockDispatch);

      expect(mockDispatch).toHaveBeenCalledWith(
        expect.objectContaining({
          address: { kind: 'navigate', target: 'core.search-surface', state: {} },
          transport: 'BUTTON',
        }),
        { pushHistory: false },
      );
      expect(getJournalState().cursor).toBe(0);
    });

    it('navigateForward dispatches next entry', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');
      const mockDispatch = vi.fn().mockResolvedValue(undefined);

      await navigateBack(mockDispatch);
      await navigateForward(mockDispatch);

      expect(mockDispatch).toHaveBeenLastCalledWith(
        expect.objectContaining({
          address: { kind: 'navigate', target: 'core.library-surface', state: {} },
          transport: 'BUTTON',
        }),
        { pushHistory: false },
      );
      expect(getJournalState().cursor).toBe(1);
    });

    it('navigateBack restores state from recorded URL', async () => {
      recordNavigation(
        'core.search-surface',
        'justsearch://surface/core.search-surface?query=rust',
        'Search',
        'RAIL',
      );
      recordNavigation('core.library-surface', 'justsearch://surface/core.library-surface', 'Library', 'RAIL');
      const mockDispatch = vi.fn().mockResolvedValue(undefined);

      await navigateBack(mockDispatch);

      expect(mockDispatch).toHaveBeenCalledWith(
        expect.objectContaining({
          address: {
            kind: 'navigate',
            target: 'core.search-surface',
            state: { query: 'rust' },
          },
        }),
        { pushHistory: false },
      );
    });

    it('navigateBack is no-op when cannot go back', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      const mockDispatch = vi.fn().mockResolvedValue(undefined);
      await navigateBack(mockDispatch);
      expect(mockDispatch).not.toHaveBeenCalled();
    });

    it('isNavigatingHistory suppresses recording during back/forward', async () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');

      let capturedFlag = false;
      const mockDispatch = vi.fn().mockImplementation(async () => {
        capturedFlag = isNavigatingHistoryNow();
        // Simulate what would happen if something tried to record
        recordNavigation('core.should-not-appear', 'urlX', 'Ghost', 'BUTTON');
      });

      await navigateBack(mockDispatch);
      expect(capturedFlag).toBe(true);
      // The recording during navigateBack was suppressed
      const state = getJournalState();
      expect(state.entries.find((e) => e.surfaceId === 'core.should-not-appear')).toBeUndefined();
    });
  });

  describe('subscribe', () => {
    it('fires listener immediately with current state', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      const listener = vi.fn();
      subscribeJournal(listener);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith(
        expect.objectContaining({ cursor: 0, entries: expect.any(Array) }),
      );
    });

    it('fires listener on record', () => {
      const listener = vi.fn();
      subscribeJournal(listener);
      listener.mockClear();
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it('unsubscribe stops firing', () => {
      const listener = vi.fn();
      const unsub = subscribeJournal(listener);
      listener.mockClear();
      unsub();
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe('persistence', () => {
    it('round-trips through localStorage', () => {
      recordNavigation('core.search-surface', 'url1', 'Search', 'RAIL');
      recordNavigation('core.library-surface', 'url2', 'Library', 'RAIL');

      // Simulate fresh module load by resetting in-memory state only
      // (keep localStorage intact)
      const raw = localStorage.getItem('justsearch.navigationJournal.v1');
      expect(raw).not.toBeNull();

      // Reset and re-initialize
      __resetJournalForTest();
      localStorage.setItem('justsearch.navigationJournal.v1', raw!);

      const state = getJournalState();
      expect(state.entries).toHaveLength(2);
      expect(state.cursor).toBe(1);
      expect(state.entries[0]!.surfaceId).toBe('core.search-surface');
    });
  });
});
