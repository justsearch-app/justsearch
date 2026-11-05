// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { deriveFrameStatus } from './frameStatus.js';
import { unifiedActivity, type BackendLedgerEntry } from '../operations/ActionLedgerClient.js';
import type { AdvisoryRecord } from '../components/advisory/AdvisoryStore.js';
const now = Date.parse('2026-11-04T18:00:00Z');
const gate = (id: string, pendingId: string | undefined, disposition: string, time: number): BackendLedgerEntry => ({
  id, kind: 'gate', occurredAt: new Date(now + time).toISOString(), originator: 'agent',
  operationId: 'core.apply-excludes', disposition, gateBehavior: 'TYPED_CONFIRM', ...(pendingId ? { pendingId } : {}),
});
it('L5 pairs by exact pending id when an older request waits and a newer request was answered', () => {
  const model = deriveFrameStatus({ ai: null, now, contactLostAt: null,
    authorizations: [{ pendingId: 'older-waiting', operationId: 'core.apply-excludes' }],
    activity: unifiedActivity([gate('older', 'older-waiting', 'GATED', -3000),
      gate('newer', 'newer-denied', 'GATED', -2000), gate('denied', 'newer-denied', 'DENIED', -1000)], []),
  });
  expect(model.sections.needs.map(row => row.id)).toEqual(['authorization:older-waiting']);
  expect(model.sections.earlier).toHaveLength(1);
  expect(model.sections.earlier[0]!.title).toContain('Denied by you');
});
it('legacy gate rows without a pending id never hide an answered row for the same operation', () => {
  const model = deriveFrameStatus({ ai: null, now, contactLostAt: null,
    authorizations: [{ pendingId: 'older-waiting', operationId: 'core.apply-excludes' }],
    activity: unifiedActivity([gate('legacy', undefined, 'DENIED', -1000)], []),
  });
  expect(model.sections.needs).toHaveLength(1);
  expect(model.sections.earlier.map(row => row.id)).toContain('activity:legacy');
});
it('Earlier names an expired request instead of saying approval requested', () => {
  const advisory = { key: 'expired', acknowledged: false, origin: 'stream', sourceRenderHint: 'REQUIRES_ACK', event: {
    id: 'expired', classId: 'authorization.pending', occurredAt: new Date(now - 600_000).toISOString(),
    renderHint: 'REQUIRES_ACK', classExtras: { pendingId: 'expired', operationId: 'core.apply-excludes',
      expiresAt: new Date(now - 1000).toISOString() },
  } } as AdvisoryRecord;
  const model = deriveFrameStatus({ ai: null, now, contactLostAt: null, advisories: [advisory] });
  expect(model.sections.needs).toHaveLength(0);
  expect(model.sections.earlier[0]!.title).toBe('Denied automatically');
});
it('Earlier names an allowed request and retires its keyed GATED row', () => {
  const model = deriveFrameStatus({ ai: null, now, contactLostAt: null,
    activity: unifiedActivity([gate('request', 'allowed', 'GATED', -2000),
      gate('allowed', 'allowed', 'APPROVED', -1000)], []),
  });
  expect(model.sections.earlier).toHaveLength(1);
  expect(model.sections.earlier[0]!.title).toContain('Allowed');
});

it('Earlier names automatic denial for both terminal ledger expiry and a stale keyed waiting row', () => {
  for (const disposition of ['EXPIRED', 'GATED']) {
    const advisory = { key: 'expired', acknowledged: false, origin: 'stream', sourceRenderHint: 'REQUIRES_ACK', event: {
      id: 'expired', classId: 'authorization.pending', occurredAt: new Date(now - 600_000).toISOString(),
      renderHint: 'REQUIRES_ACK', classExtras: { pendingId: 'expired', operationId: 'core.apply-excludes',
        expiresAt: new Date(now - 1000).toISOString() },
    } } as AdvisoryRecord;
    const model = deriveFrameStatus({ ai: null, now, contactLostAt: null, advisories: [advisory],
      activity: unifiedActivity([gate('expiry', 'expired', disposition, -1000)], []),
    });
    expect(model.sections.needs).toHaveLength(0);
    expect(model.sections.earlier.find(row => row.id === 'activity:expiry')!.title).toContain('Denied automatically');
  }
});

it('terminal ledger outcomes remain in Earlier before the waiting snapshot is withdrawn', () => {
  for (const disposition of ['DENIED', 'APPROVED', 'EXPIRED']) {
    const activity = unifiedActivity([gate('request', 'answered', 'GATED', -2000),
      gate('answer', 'answered', disposition, -1000)], []);
    for (const authorizations of [[{ pendingId: 'answered', operationId: 'core.apply-excludes' }], []]) {
      const model = deriveFrameStatus({ ai: null, now, contactLostAt: null, activity, authorizations });
      expect(model.sections.needs).toHaveLength(0);
      expect(model.sections.earlier).toHaveLength(1);
      expect(model.sections.earlier[0]!.title).toContain(disposition === 'DENIED' ? 'Denied by you'
        : disposition === 'APPROVED' ? 'Allowed' : 'Denied automatically');
    }
  }
});

it('an expired advisory and its keyed gate produce exactly one Earlier row', () => {
  const advisory = { key: 'expired', acknowledged: false, origin: 'stream', sourceRenderHint: 'REQUIRES_ACK', event: {
    id: 'expired', classId: 'authorization.pending', occurredAt: new Date(now - 600_000).toISOString(),
    renderHint: 'REQUIRES_ACK', classExtras: { pendingId: 'expired', operationId: 'core.apply-excludes',
      expiresAt: new Date(now - 1000).toISOString() },
  } } as AdvisoryRecord;
  for (const disposition of ['GATED', 'EXPIRED']) {
    const model = deriveFrameStatus({ ai: null, now, contactLostAt: null, advisories: [advisory],
      authorizations: [{ pendingId: 'expired', operationId: 'core.apply-excludes',
        expiresAt: new Date(now - 1000).toISOString() }],
      activity: unifiedActivity([gate('request', 'expired', 'GATED', -2000),
        ...(disposition === 'EXPIRED' ? [gate('expiry', 'expired', 'EXPIRED', -1000)] : [])], []),
    });
    expect(model.sections.needs).toHaveLength(0);
    expect(model.sections.earlier).toHaveLength(1);
    expect(model.sections.earlier[0]!.title).toContain('Denied automatically');
  }
});
