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
/**
 * ConsentCapsuleService.emitGrant through ActionLedgerProjection.toWireRow: the one-time permission an
 * Allow mints and its run uses, naming the request (`pendingId`) when the Engine knew it.
 */
const grant = (grantId: string, action: string, ms: number, transport = 'APPROVAL', pendingId?: string): BackendLedgerEntry => ({
  id: `grant:${action}:${grantId}`, kind: 'grant', occurredAt: at(ms), originator: 'user', transport, grantId, action,
  outcome: action, subject: 'core.reindex', ...(pendingId ? { pendingId } : {}) } as BackendLedgerEntry);
/** A committed operation row (`operation:<key>`), naming the request whose Allow admitted the run when known. */
const operation = (id: string, outcome: string, ms: number, pendingId?: string): BackendLedgerEntry => ({ id: `operation:${id}`,
  kind: 'operation', occurredAt: at(ms), originator: 'agent', transport: 'MCP', operationId: 'core.reindex', outcome,
  ...(pendingId ? { pendingId } : {}) } as BackendLedgerEntry);
const earlier = (rows: BackendLedgerEntry[]) => deriveFrameStatus({ ai: null, now, contactLostAt: null,
  activity: unifiedActivity(rows, []) }).sections.earlier.map(row => ({ title: row.title, detail: row.detail, tone: row.tone }));

it('L16 one Allow is one Earlier row showing its latest state', () => {
  const allow = [gate('p1', 'GATED', -9000), gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990, 'APPROVAL', 'p1')];
  expect(earlier(allow)).toEqual([{ title: 'Allowed: Reindex', detail: undefined, tone: 'success' }]);
  const used = [...allow, grant('g1', 'CONSUMED', -7000, 'APPROVAL', 'p1')];
  expect(earlier(used)).toEqual([{ title: 'Allowed: Reindex', detail: 'Allowed · started', tone: 'success' }]);
  expect(earlier([...used, operation('o1', 'SUCCESS', -5000, 'p1')])).toEqual([{ title: 'Allowed: Reindex', detail: 'Allowed · completed', tone: 'success' }]);
  expect(earlier([...used, operation('o1', 'FAILURE', -5000, 'p1')])).toEqual([{ title: 'Allowed: Reindex', detail: 'Allowed · failed', tone: 'failure' }]);
});

it('L16 each of two Allows keeps its own row and its own outcome', () => {
  const rows = [gate('p1', 'GATED', -9000), gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990, 'APPROVAL', 'p1'),
    grant('g1', 'CONSUMED', -7900, 'APPROVAL', 'p1'), operation('o1', 'SUCCESS', -7000, 'p1'),
    gate('p2', 'GATED', -6000), gate('p2', 'APPROVED', -5000), grant('g2', 'ISSUED', -4990, 'APPROVAL', 'p2'),
    grant('g2', 'CONSUMED', -4900, 'APPROVAL', 'p2'), operation('o2', 'FAILURE', -4000, 'p2')];
  expect(earlier(rows)).toEqual([
    { title: 'Allowed: Reindex', detail: 'Allowed · failed', tone: 'failure' },
    { title: 'Allowed: Reindex', detail: 'Allowed · completed', tone: 'success' },
  ]);
});

it('L16 rows that are not this decision\'s steps stay their own rows', () => {
  const rows = [gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990, 'APPROVAL', 'p1'),
    // "Always allow" is a lasting permission of its own; a later run of the same operation was never allowed here.
    grant('always', 'ISSUED', -7980, 'ALLOW_ALWAYS'), operation('manual', 'SUCCESS', -1000)];
  expect(earlier(rows).map(row => `${row.title} · ${row.detail ?? ''}`)).toEqual([
    'Reindex · Completed', 'Permission for Reindex · Allowed', 'Allowed: Reindex · ']);
});

it('L16 two Allows of one operation 1.5 s apart keep their own outcomes when the first run succeeds and the second fails', () => {
  const rows = [gate('p1', 'GATED', -4000), gate('p1', 'APPROVED', -3000), grant('g1', 'ISSUED', -2990, 'APPROVAL', 'p1'),
    grant('g1', 'CONSUMED', -2980, 'APPROVAL', 'p1'), operation('o1', 'SUCCESS', -2500, 'p1'),
    gate('p2', 'GATED', -2000), gate('p2', 'APPROVED', -1500), grant('g2', 'ISSUED', -1490, 'APPROVAL', 'p2'),
    grant('g2', 'CONSUMED', -1480, 'APPROVAL', 'p2'), operation('o2', 'FAILURE', -1000, 'p2')];
  expect(earlier(rows)).toEqual([
    { title: 'Allowed: Reindex', detail: 'Allowed · failed', tone: 'failure' },
    { title: 'Allowed: Reindex', detail: 'Allowed · completed', tone: 'success' },
  ]);
});

it('L16 a long first run that fails after a later Allow was used keeps its failure on its own decision', () => {
  const rows = [gate('p1', 'APPROVED', -20_000), grant('g1', 'ISSUED', -19_990, 'APPROVAL', 'p1'),
    grant('g1', 'CONSUMED', -19_980, 'APPROVAL', 'p1'),
    gate('p2', 'APPROVED', -10_000), grant('g2', 'ISSUED', -9990, 'APPROVAL', 'p2'), grant('g2', 'CONSUMED', -9980, 'APPROVAL', 'p2'),
    operation('o1', 'FAILURE', -5000, 'p1'), operation('o2', 'SUCCESS', -3000, 'p2')];
  expect(earlier(rows)).toEqual([
    { title: 'Allowed: Reindex', detail: 'Allowed · completed', tone: 'success' },
    { title: 'Allowed: Reindex', detail: 'Allowed · failed', tone: 'failure' },
  ]);
});

it('L16 rows without a request id are never joined to a decision; each keeps its own words', () => {
  // Rows recorded before the id existed, or by a path that cannot know the request.
  const rows = [gate('p1', 'GATED', -9000), gate('p1', 'APPROVED', -8000), grant('g1', 'ISSUED', -7990),
    grant('g1', 'CONSUMED', -7900), operation('o1', 'SUCCESS', -7000)];
  expect(earlier(rows).map(row => `${row.title} · ${row.detail ?? ''} · ${row.tone}`)).toEqual([
    'Reindex · Completed · success', 'Permission for Reindex · Used · neutral', 'Permission for Reindex · Allowed · success',
    'Allowed: Reindex ·  · success']);
  // A row naming a request whose decision is not listed here stays a row of its own too.
  expect(earlier([gate('p1', 'APPROVED', -8000), operation('o9', 'FAILURE', -7000, 'p9')])
    .map(row => `${row.title} · ${row.detail ?? ''}`)).toEqual(['Reindex · Failed', 'Allowed: Reindex · ']);
});
