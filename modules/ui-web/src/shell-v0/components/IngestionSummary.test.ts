// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './IngestionSummary.js';
import type { IngestionSummary } from './IngestionSummary.js';
import type { JfButton } from './Button.js';
import type { StatusBadge } from './StatusBadge.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __resetAiStateForTest, __tickClockForTest } from '../state/aiStateStore.js';
import { INGESTION_REASON_LABELS, type IngestionRollup } from './ingestionSummaryPresentation.js';

const row = (over: Partial<IngestionRollup> = {}): IngestionRollup => ({
  outcomeClass: 'SUCCESS_FULL', reasonCode: 'SUCCESS', retryPolicy: 'NONE', count: 1,
  lastObservedAtMs: 1700000000000, ...over,
});
const response = (rows: IngestionRollup[]) => new Response(JSON.stringify({ rollups: rows, count: rows.length }));
type HostFetch = PluginHostApi['data']['fetch'];
const host = (fetch: HostFetch) => ({ data: { fetch } }) as unknown as PluginHostApi;
async function pump(el: IngestionSummary): Promise<void> {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function mount(fetch: HostFetch): Promise<IngestionSummary> {
  const el = document.createElement('jf-ingestion-summary') as IngestionSummary;
  el.host_ = host(fetch);
  document.body.append(el);
  await pump(el);
  return el;
}
function text(el: IngestionSummary): string { return el.shadowRoot!.textContent!; }
function button(el: IngestionSummary, copy: string): JfButton {
  const match = [...el.shadowRoot!.querySelectorAll<JfButton>('jf-button')]
    .find((b) => b.textContent!.includes(copy));
  expect(match).toBeDefined();
  return match!;
}
async function activate(el: IngestionSummary, copy: string): Promise<void> {
  const atom = button(el, copy);
  await atom.updateComplete;
  const control = atom.shadowRoot!.querySelector('jf-control') as HTMLElement & { updateComplete: Promise<unknown> };
  await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
  await pump(el);
}

beforeEach(() => { __resetAiStateForTest(); vi.useFakeTimers(); vi.setSystemTime(1700000000000); });
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.useRealTimers(); });

describe('IngestionSummary: real component transport and presentation', () => {
  it('requests the retained summary once and counts events, not the envelope group count', async () => {
    const fetch = vi.fn(async () => response([
      row({ count: 7 }),
      row({ outcomeClass: 'SKIPPED_POLICY', reasonCode: 'UNCHANGED', count: 3 }),
      row({ outcomeClass: 'PARSER_FAILED', reasonCode: 'PARSER_FAILED', count: 2 }),
      row({ outcomeClass: 'DEFERRED_POLICY', reasonCode: 'CLOUD_PLACEHOLDER', count: 4 }),
      row({ outcomeClass: 'SUCCESS_EMPTY', reasonCode: 'SUCCESS_EMPTY' }),
    ]));
    const el = await mount(fetch);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0]).toEqual(['/api/diagnostics/ingestion/summary?since=0', { signal: expect.any(AbortSignal) }]);
    expect(text(el)).toContain('17 recorded indexing outcomes');
    expect(text(el)).toContain('A file can appear more than once');
    expect(text(el)).toContain('File unchanged since its last index');
    expect(text(el)).toContain('Content extraction failed');
    const badges = [...el.shadowRoot!.querySelectorAll<StatusBadge>('jf-status-badge')];
    expect(badges.map((b) => [b.textContent, b.status])).toEqual([
      ['Indexed', 'success'], ['Deferred', 'neutral'], ['Skipped', 'neutral'],
      ['Extraction failed', 'failed'], ['Indexed without content', 'neutral'],
    ]);
  });

  it('renders readable wording for every actual reason constant, including multiline values', async () => {
    const source = readFileSync(resolve('../worker-core/src/main/java/io/justsearch/indexerworker/ingest/IngestionReasonCodes.java'), 'utf8');
    const codes = [...source.matchAll(/public static final String\s+\w+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1]!);
    expect(codes.length).toBeGreaterThan(24); // catches the old single-line census's blind spot
    expect(Object.keys(INGESTION_REASON_LABELS).sort()).toEqual(codes.sort());
    const el = await mount(async () => response(codes.map((code) => row({ reasonCode: code }))));
    expect(el.shadowRoot!.querySelectorAll('li')).toHaveLength(20);
    await activate(el, 'Show all');
    expect(el.shadowRoot!.querySelectorAll('li')).toHaveLength(codes.length);
    for (const code of codes) {
      expect(text(el)).toContain(INGESTION_REASON_LABELS[code]);
      expect(text(el)).not.toContain(code);
    }
    await activate(el, 'Show fewer');
    expect(el.shadowRoot!.querySelectorAll('li')).toHaveLength(20);
  });

  it('distinguishes decoding loss from truncation in the rendered summary', async () => {
    const el = await mount(async () => response([
      row({ outcomeClass: 'SUCCESS_PARTIAL', reasonCode: 'TEXT_LOST_WHILE_DECODING', count: 3 }),
      row({ outcomeClass: 'SUCCESS_PARTIAL', reasonCode: 'TEXT_LOST_WHILE_DECODING_AND_TRUNCATED', count: 2 }),
      row({ outcomeClass: 'SUCCESS_PARTIAL', reasonCode: 'SUCCESS_PARTIAL', count: 1 }),
    ]));
    const rows = [...el.shadowRoot!.querySelectorAll('li')].map((li) => li.textContent!.replace(/\s+/g, ' ').trim());
    expect(rows).toHaveLength(3);
    expect(rows[0]).toContain('Content indexed; some characters could not be decoded');
    expect(rows[0]).not.toContain('extraction limit');
    expect(rows[1]).toContain('Content indexed up to the extraction limit; some characters could not be decoded');
    expect(rows[2]).toContain('Content indexed up to the extraction limit');
    expect(rows[2]).not.toContain('could not be decoded');
  });

  it('keeps unknown classifications neutral, never displays opaque codes or prototype properties', async () => {
    const el = await mount(async () => response([
      row({ outcomeClass: 'NEW_SUCCESS', reasonCode: 'NEW_SECRET_PATH_REASON' }),
      row({ outcomeClass: 'constructor', reasonCode: '__proto__' }),
    ]));
    expect(text(el)).toContain('Outcome unknown');
    expect(text(el)).toContain('Reason not recognized by this version');
    expect(text(el)).not.toContain('NEW_SECRET_PATH_REASON');
    expect(text(el)).not.toContain('__proto__');
    expect([...el.shadowRoot!.querySelectorAll<StatusBadge>('jf-status-badge')].every((b) => b.status === 'neutral')).toBe(true);
  });

  it('combines retry-policy subgroups without treating them as new reasons or promising retries', async () => {
    const el = await mount(async () => response([
      row({ outcomeClass: 'IO_FAILED', reasonCode: 'IO_ERROR', count: 2 }),
      row({ outcomeClass: 'IO_FAILED', reasonCode: 'IO_ERROR', retryPolicy: 'RETRY_WITH_BACKOFF', count: 5 }),
    ]));
    expect(el.shadowRoot!.querySelectorAll('li')).toHaveLength(1);
    expect(el.shadowRoot!.querySelector('.count')!.textContent).toBe('7');
    expect(text(el)).not.toContain('RETRY_WITH_BACKOFF');
  });

  it('distinguishes initial loading from a genuinely empty ledger', async () => {
    let resolve!: (response: Response) => void;
    const el = await mount(() => new Promise((r) => { resolve = r; }));
    expect(text(el)).toContain('Loading indexing activity');
    expect(text(el)).not.toContain('No indexing outcomes');
    expect(button(el, 'Refresh activity').disabled).toBe(true);
    resolve(response([])); await pump(el);
    expect(text(el)).toContain('No indexing outcomes in retained history');
    expect(el.shadowRoot!.querySelector('[role="status"]')).not.toBeNull();
  });

  it.each([
    ['503', () => Promise.resolve(new Response('private stack trace', { status: 503 }))],
    ['non-JSON', () => Promise.resolve(new Response('<html>private path</html>'))],
    ['wrong shape', () => Promise.resolve(new Response(JSON.stringify({ rollups: [{}], count: 1 })))],
    ['missing rows', () => Promise.resolve(new Response(JSON.stringify({ rollups: [], count: 1 })))],
    ['network', () => Promise.reject(new Error('private path'))],
  ])('shows honest %s failure and the actual refresh button recovers', async (_, fail) => {
    const fetch = vi.fn().mockImplementationOnce(fail).mockImplementation(async () => response([row()]));
    const el = await mount(fetch);
    expect(text(el)).toContain('Could not refresh indexing activity');
    expect(text(el)).not.toContain('No indexing outcomes');
    expect(text(el)).not.toContain('private');
    expect(el.shadowRoot!.querySelector('jf-error-alert')).not.toBeNull();
    await activate(el, 'Retry activity refresh');
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(text(el)).toContain('1 recorded indexing outcome.');
    expect(el.shadowRoot!.querySelector('jf-error-alert')).toBeNull();
  });

  it('bounds hung requests, retains a visibly stale summary, and never overlaps tick requests', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response([row({ count: 3 })]))
      .mockImplementation(() => new Promise<Response>(() => {}));
    const el = await mount(fetch);
    __tickClockForTest(); await pump(el);
    expect(fetch).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(4000); __tickClockForTest(); await pump(el);
    expect(fetch).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(4000); __tickClockForTest(); await pump(el);
    expect(fetch).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(11000); await pump(el);
    expect(fetch.mock.calls[1]![1].signal.aborted).toBe(true);
    expect(text(el)).toContain('Showing the last loaded summary');
    expect(text(el)).toContain('3 recorded indexing outcomes in the last loaded summary');
    expect(button(el, 'Retry activity refresh').disabled).toBe(false);
  });

  it('keeps the live region stable during slow and unchanged background requests, but announces changes and failures', async () => {
    let resolve!: (response: Response) => void;
    const fetch = vi.fn().mockResolvedValueOnce(response([row({ count: 3 })]))
      .mockImplementationOnce(() => new Promise<Response>((r) => { resolve = r; }))
      .mockResolvedValueOnce(response([row({ count: 5 })]))
      .mockResolvedValueOnce(new Response('', { status: 503 }));
    const el = await mount(fetch);
    const status = el.shadowRoot!.querySelector('[role="status"]')!;
    const initial = status.textContent;
    const announcements: string[] = [];
    const observer = new MutationObserver(() => { announcements.push(status.textContent!); });
    observer.observe(status, { childList: true, characterData: true, subtree: true });
    try {
      vi.advanceTimersByTime(4000); __tickClockForTest(); await pump(el);
      expect(fetch).toHaveBeenCalledTimes(2);
      expect(status.textContent).toBe(initial);
      vi.advanceTimersByTime(3000); await pump(el);
      expect(status.textContent).toBe(initial);
      resolve(response([row({ count: 3 })])); await pump(el);
      expect(status.textContent).toBe(initial);
      expect(announcements).toEqual([]);

      vi.advanceTimersByTime(4000); __tickClockForTest(); await pump(el);
      expect(status.textContent).toContain('5 recorded indexing outcomes');
      expect(announcements).toEqual(['5 recorded indexing outcomes.']);
      vi.advanceTimersByTime(4000); __tickClockForTest(); await pump(el);
      expect(status.textContent).toContain('in the last loaded summary');
      expect(el.shadowRoot!.querySelector('jf-error-alert')).not.toBeNull();
      expect(announcements).toHaveLength(2);
    } finally {
      observer.disconnect();
    }
  });

  it('announces loading for an explicit refresh and restores the result after an unchanged response', async () => {
    let resolve!: (response: Response) => void;
    const fetch = vi.fn().mockResolvedValueOnce(response([row({ count: 3 })]))
      .mockImplementationOnce(() => new Promise<Response>((r) => { resolve = r; }));
    const el = await mount(fetch);
    const status = el.shadowRoot!.querySelector('[role="status"]')!;
    await activate(el, 'Refresh activity');
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(status.textContent).toBe('Refreshing indexing activity…');
    resolve(response([row({ count: 3 })])); await pump(el);
    expect(status.textContent).toBe('3 recorded indexing outcomes.');
  });

  it('aborts on disconnect, unsubscribes ticks, clears timers and rejects late data after reconnect', async () => {
    let resolve!: (response: Response) => void;
    const fetch = vi.fn().mockImplementationOnce(() => new Promise<Response>((r) => { resolve = r; }))
      .mockImplementation(async () => response([row({ count: 9 })]));
    const el = await mount(fetch);
    const signal = fetch.mock.calls[0]![1].signal as AbortSignal;
    el.remove();
    expect(signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(20000); __tickClockForTest();
    expect(fetch).toHaveBeenCalledTimes(1);
    document.body.append(el); await pump(el);
    expect(text(el)).toContain('9 recorded indexing outcomes');
    resolve(response([row({ count: 100 })])); await pump(el);
    expect(text(el)).toContain('9 recorded indexing outcomes');
    expect(text(el)).not.toContain('100 recorded');
  });

  it('supersedes an old host even when its transport ignores cancellation', async () => {
    let resolve!: (response: Response) => void;
    const oldFetch = vi.fn<HostFetch>(() => new Promise<Response>((r) => { resolve = r; }));
    const el = await mount(oldFetch);
    const nextFetch = vi.fn(async () => response([row({ count: 5 })]));
    el.host_ = host(nextFetch); await pump(el);
    expect(oldFetch.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    resolve(response([row({ count: 100 })])); await pump(el);
    expect(text(el)).toContain('5 recorded indexing outcomes');
    expect(text(el)).not.toContain('100 recorded');
  });
});
