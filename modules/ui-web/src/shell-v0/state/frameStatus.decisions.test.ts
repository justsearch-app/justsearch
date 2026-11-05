// SPDX-License-Identifier: Apache-2.0
// L16 — one Earlier row per decision, from the ledger rows one Allow really writes.
import { expect, it } from 'vitest';
import { deriveFrameStatus } from './frameStatus.js';
import { unifiedActivity, type BackendLedgerEntry } from '../operations/ActionLedgerClient.js';

const now = Date.parse('2026-11-05T12:00:00Z');
const at = (ms: number) => new Date(now + ms).toISOString();
const gate = (pendingId: string, disposition: string, ms: number): BackendLedgerEntry => ({ id: `gate:${pendingId}:${disposition}`,
  kind: 'gate', occurredAt: at(ms), originator: 'agent', transport: 'MCP', operationId: 'core.reindex', disposition,
  gateBehavior: 'INLINE_CONFIRM', pendingId } as BackendLedgerEntry);
/** ConsentCapsuleService.emitGrant: the one-time permission an Allow mints and its run uses. */
const grant = (grantId: string, action: string, ms: number, transport = 'APPROVAL'): BackendLedgerEntry => ({ id: `grant:${action}:${grantId}`,
  kind: 'grant', occurredAt: at(ms), originator: 'user', transport, grantId, action, subject: 'core.reindex' } as BackendLedgerEntry);
const operation = (id: string, outcome: string, ms: number): BackendLedgerEntry => ({ id: `operation:${id}`, kind: 'operation',
  occurredAt: at(ms), originator: 'agent', transport: 'MCP', operationId: 'core.reindex', outcome } as BackendLedgerEntry);
const earlier = (rows: BackendLedgerEntry[]) => deriveFrameStatus({ ai: null, now, contactLostAt: null,
  activity: unifiedActivity(rows, []) }).sections.earlier.map(row => ({ title: row.title, detail: row.detail, tone: row.tone }));

it('L16 one Allow is one Earlier row showing its latest state', () => {
  const allow = [gate('p1', 'GATED', -9000), gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990)];
  expect(earlier(allow)).toEqual([{ title: 'Allowed: Reindex', detail: undefined, tone: 'success' }]);
  const used = [...allow, grant('g1', 'CONSUMED', -7000)];
  expect(earlier(used)).toEqual([{ title: 'Allowed: Reindex', detail: 'Allowed · started', tone: 'success' }]);
  expect(earlier([...used, operation('o1', 'SUCCESS', -5000)])).toEqual([{ title: 'Allowed: Reindex', detail: 'Allowed · completed', tone: 'success' }]);
  expect(earlier([...used, operation('o1', 'FAILURE', -5000)])).toEqual([{ title: 'Allowed: Reindex', detail: 'Allowed · failed', tone: 'failure' }]);
});

it('L16 each of two Allows keeps its own row and its own outcome', () => {
  const rows = [gate('p1', 'GATED', -9000), gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990), grant('g1', 'CONSUMED', -7900),
    operation('o1', 'SUCCESS', -7000),
    gate('p2', 'GATED', -6000), gate('p2', 'APPROVED', -5000), grant('g2', 'ISSUED', -4990), grant('g2', 'CONSUMED', -4900),
    operation('o2', 'FAILURE', -4000)];
  expect(earlier(rows)).toEqual([
    { title: 'Allowed: Reindex', detail: 'Allowed · failed', tone: 'failure' },
    { title: 'Allowed: Reindex', detail: 'Allowed · completed', tone: 'success' },
  ]);
});

it('L16 rows that are not this decision\'s steps stay their own rows', () => {
  const rows = [gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990),
    // "Always allow" is a lasting permission of its own; a later run of the same operation was never allowed here.
    grant('always', 'ISSUED', -7980, 'ALLOW_ALWAYS'), operation('manual', 'SUCCESS', -1000)];
  expect(earlier(rows).map(row => `${row.title} · ${row.detail ?? ''}`)).toEqual([
    'Reindex · Completed', 'Permission for Reindex · Allowed', 'Allowed: Reindex · ']);
});
