import { expect, it } from 'vitest';
import { folderStatus } from './folderStatus';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view';

it('S35.1 incomplete removal remains visible during rebuild and with a missing folder', () => {
  const reason = 'Removal incomplete. Live watching is not confirmed; retry Remove.';
  for (const provisional of [false, true]) {
    for (const status of ['indexed', 'unavailable'] as const) {
      const row: IndexedRootView = {
        pathHash: 'removing', collection: 'notes', fileCount: 2,
        lastIndexedIsoTime: '2026-10-19T00:00:00Z', status, walkError: reason,
        walkCompleted: true, inFlightCount: 1, failedCount: 0,
      };
      const derived = folderStatus(row, {
        relativeTime: '', verifiedRelativeTime: '', provisional, enrichmentPending: false,
      });
      expect(derived.state).toBe('failed');
      expect(derived.metaText).toContain(reason);
      expect(derived.glyph).toBe('error');
    }
  }
});
