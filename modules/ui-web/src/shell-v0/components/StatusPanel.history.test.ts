// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import { StatusPanel } from './StatusPanel.js';
import type { UnifiedActionEntry } from '../operations/ActionLedgerClient.js';
import { AdvisoryStore, type AdvisoryRecord, type AdvisorySnapshot } from './advisory/AdvisoryStore.js';
import { deriveFrameStatus } from '../state/frameStatus.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
let pill: StatusPill;
let current: AdvisorySnapshot;
const listeners = new Set<(s: AdvisorySnapshot) => void>();
const acknowledge = vi.fn(); const acknowledgeAll = vi.fn();
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
async function flush() { await Promise.resolve(); await pill.updateComplete; await panel().updateComplete; }
function record(key: string, options: Partial<AdvisoryRecord> = {}): AdvisoryRecord {
  return { key, acknowledged: false, origin: 'stream', sourceRenderHint: 'PERSISTED', event: {
    id: key, classId: 'health.recoverable', occurredAt: '2026-11-03T10:00:00Z', renderHint: 'PERSISTED',
    classExtras: { conditionId: 'folder.unavailable', subject: 'Notes' }, primaryAction: null,
    bodyI18nKey: null, diagnosticsLink: null, provenance: null,
  }, ...options };
}
async function publish(records: AdvisoryRecord[]) { current = { ...current, advisories: records }; listeners.forEach(cb => cb(current)); await flush(); }
beforeEach(async () => {
  __resetAiStateForTest(); vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  current = { advisories: [], unreadCount: 0, isConnected: true, lastFrameKind: 'snapshot' };
  const store = { subscribe: (cb: (s: AdvisorySnapshot) => void) => { listeners.add(cb); cb(current); return () => listeners.delete(cb); }, acknowledge, acknowledgeAll };
  pill = new StatusPill(); pill.store = store as unknown as AdvisoryStore; document.body.append(pill); await flush();
});
afterEach(() => { pill.remove(); listeners.clear(); __resetAiStateForTest(); vi.unstubAllGlobals(); });
function button(label: string) { return [...panel().shadowRoot!.querySelectorAll('button')].find(b => b.textContent!.trim() === label)!; }
describe('Earlier (moved from the inbox drawer)', () => {
  it('omits empty sections; calm has one line and the two history destinations', async () => {
    panel().model = { ...deriveFrameStatus({ ai: null, now: 0, contactLostAt: null }), pill: { kind: 'calm', label: 'Up to date', stale: false } };
    await panel().updateComplete;
    expect(panel().shadowRoot!.querySelectorAll('section')).toHaveLength(0);
    expect(panel().shadowRoot!.querySelector('.empty')!.textContent).toBe('Search is up to date. Nothing needs you.');
    expect(button('All activity')).toBeDefined(); expect(button('Health')).toBeDefined();
  });
  it('newest comes first regardless of read state; unread keeps its marker and history does not reactivate a resolved condition', async () => {
    const newest = record('newest', { acknowledged: true });
    const older = record('older', { event: { ...newest.event, id: 'older', occurredAt: '2026-11-02T10:00:00Z' } });
    await publish([newest, older]);
    const rows = [...panel().shadowRoot!.querySelectorAll('article')];
    expect(rows.map(n => n.getAttribute('data-fact'))).toEqual(['advisory:newest', 'advisory:older']);
    expect(panel().shadowRoot!.querySelectorAll('.unread')).toHaveLength(1);
    expect(rows[0]!.querySelector('.unread')).toBeNull();
    expect(rows[1]!.querySelector('.unread')).not.toBeNull();
    expect(panel().model!.sections.problems).toEqual([]);
    rows[1]!.querySelector<HTMLButtonElement>('button')!.click(); await flush(); expect(acknowledge).toHaveBeenCalledExactlyOnceWith('older');
    await publish([{ ...older, acknowledged: true }, newest]);
    button('Details').click(); await flush(); expect(acknowledge).toHaveBeenCalledTimes(1);
  });
  it('Mark all read uses the existing acknowledgement and stays focusable with a reason when already read', async () => {
    await publish([record('one')]); button('Mark all read').click(); expect(acknowledgeAll).toHaveBeenCalledTimes(1);
    await publish([record('one', { acknowledged: true })]);
    const mark = button('Mark all read'); mark.focus();
    expect(mark.disabled).toBe(false); expect(mark.getAttribute('aria-disabled')).toBe('true'); expect(mark.title).toBe('All items are already read');
    mark.click(); expect(acknowledgeAll).toHaveBeenCalledTimes(1);
  });
  it.each(['https://example.test/diagnostics', 'diagnostics.i18n.key', 'javascript:alert(1)'])('diagnostics preserve safe navigation or plain text for %s', async diagnosticsLink => {
    const r = record('one'); await publish([{ ...r, event: { ...r.event, diagnosticsLink } }]);
    button('Details').click(); await flush();
    const link = panel().shadowRoot!.querySelector('a');
    if (diagnosticsLink.startsWith('https:')) { expect(link!.getAttribute('href')).toBe(diagnosticsLink); expect(link!.rel).toBe('noopener noreferrer'); }
    else { expect(link).toBeNull(); expect(panel().shadowRoot!.textContent).toContain(diagnosticsLink); }
  });
  it('retains local receipts in Earlier without making them unread', async () => {
    await publish([record('local', { origin: 'local', sourceRenderHint: 'EPHEMERAL', toast: { message: 'Copied' } })]);
    expect(panel().model!.sections.earlier[0]!.title).toBe('Copied'); expect(panel().model!.sections.earlier[0]!.unread).toBe(false);
  });
  it('unknown advisory classes use default chrome, without exposing their raw class id as the title', async () => {
    const r = record('one'); await publish([{ ...r, event: { ...r.event, classId: 'future.unknown' } }]);
    expect(panel().model!.sections.earlier[0]!.title).not.toBe('future.unknown');
  });
  it('humanizes recoverable conditions, authored reasons and operation actions', async () => {
    const r = record('one');
    await publish([{ ...r, event: { ...r.event, classExtras: { conditionId: 'index.rebuild_required', subject: 'Notes' },
      primaryAction: { target: 'core.rebuild-index', defaultArgsJson: '{}' } } }]);
    const row = panel().model!.sections.earlier[0]!;
    expect(row.title).not.toContain('index.rebuild_required'); expect(row.title).toContain('Notes');
    expect(row.actions![0]!.label).not.toContain('core.rebuild-index');
  });
  it('forget offers reindexing while backend supported reversal alone is called Undo', async () => {
    const r = record('one');
    await publish([{ ...r, event: { ...r.event, classExtras: { operationId: 'core.forget-root' }, primaryAction: { target: 'core.scan-root', defaultArgsJson: '{}' } } }]);
    expect(button('Add back to search')).toBeDefined();
    await publish([{ ...r, event: { ...r.event, primaryActionKind: 'undo', primaryAction: { target: 'core.some-operation', defaultArgsJson: '{"executionId":"exact"}' } } }]);
    expect(button('Undo')).toBeDefined();
  });
  it('a failed operation reads as a failure in Earlier, and ledger codes read as plain words', async () => {
    const activity = (id: string, extra: Partial<UnifiedActionEntry>): UnifiedActionEntry => ({ id, source: 'backend', kind: 'operation',
      occurredAt: '2026-11-03T10:00:00Z', originator: 'user', label: 'Reindex everything', ...extra });
    panel().model = deriveFrameStatus({ ai: null, now: 0, contactLostAt: null, advisories: [record('failed', { event: { ...record('failed').event,
      classId: 'operation.completed', classExtras: { operationId: 'core.bulk-reindex', outcome: 'FAILURE' } } })],
    activity: [activity('a1', { outcome: 'FAILURE' }), activity('a2', { outcome: 'SUCCESS', label: 'Search index' }),
      activity('g1', { kind: 'gate', label: 'GATED Reindex everything (TYPED_CONFIRM)', gate: { disposition: 'GATED', operation: 'Reindex everything' } })] });
    await panel().updateComplete;
    const row = (id: string) => panel().shadowRoot!.querySelector(`article[data-fact="${id}"]`)!;
    for (const id of ['advisory:failed', 'activity:a1']) {
      expect(row(id).querySelector('.state-icon')!.textContent, id).toBe('✕');
      expect(row(id).textContent, id).toMatch(/failed/i);
    }
    expect(row('activity:a2').querySelector('.state-icon')!.textContent).toBe('✓');
    const text = panel().shadowRoot!.querySelector('section[aria-label="Earlier"]')!.textContent!;
    for (const code of ['GATED', 'SUCCESS', 'FAILURE', 'TYPED_CONFIRM']) expect(text, code).not.toContain(code);
    expect(row('activity:g1').textContent).toContain('Approval requested: Reindex everything');
  });
  it('row titles share one icon column that never stretches with the title', async () => {
    const css = (panel().constructor as typeof StatusPanel).styles.cssText;
    // The icon is not a flexible title span: a fixed inline size, no grow.
    expect(css).toMatch(/\.state-icon\s*\{[^}]*flex:\s*none[^}]*inline-size:\s*var\(--jf-space-4\)/);
    expect(css).not.toMatch(/\.title\s*>\s*span\s*\{/);
    await publish([record('one')]);
    expect(panel().shadowRoot!.querySelector('.title > .state-icon + .text')).not.toBeNull();
  });
});
