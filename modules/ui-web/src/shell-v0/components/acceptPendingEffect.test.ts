// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { acceptPendingWithConsent } from './acceptPendingEffect.js';
import { proposeEffect, __resetPendingForTest } from '../substrates/pending-effects/index.js';
import { __resetJournalForTest } from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __seedForTest, __resetForTest } from '../../api/registry/OperationCatalogClient.js';
import type { OperationCatalog } from '../../api/types/registry.js';
afterEach(() => { __resetForTest(); __resetPendingForTest(); __resetJournalForTest(); });
it('C12 typed agent MEDIUM acceptance preserves consent without minting a token', () => {
  __seedForTest({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries: [{
    id: 'core.reindex', presentation: { labelKey: 'core.reindex', descriptionKey: 'core.reindex', iconHint: null, category: null },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} }, policy: { risk: 'MEDIUM', confirm: { kind: 'NONE' }, audit: 'METADATA_ONLY', undoSupported: false },
    availability: {}, lineage: { affects: [], supersedes: [] }, provenance: CORE_PROVENANCE, executors: ['UI'], audience: 'USER', consumers: [],
  }] } as OperationCatalog);
  const received = vi.fn(); document.addEventListener('jf-invoke-operation', received);
  try {
    const id = proposeEffect({ kind: 'invoke-operation', operationId: 'core.reindex', args: { scope: 'exact' } }, CORE_PROVENANCE, 'agent');
    acceptPendingWithConsent(id); acceptPendingWithConsent(id);
    expect(received).toHaveBeenCalledTimes(1);
    expect((received.mock.calls[0]![0] as CustomEvent).detail).toMatchObject({ operationId: 'core.reindex', args: { scope: 'exact' }, consented: true });
    expect((received.mock.calls[0]![0] as CustomEvent).detail.confirmationToken).toBeUndefined();
  } finally { document.removeEventListener('jf-invoke-operation', received); }
});
