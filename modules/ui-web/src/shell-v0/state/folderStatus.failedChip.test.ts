// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 914 D3 — the "N failed" chip must stay REACHABLE while the retry ladder re-queues a
 * permanently failing file.
 *
 * Measured on the live stack (2 files failing with `ExtractionException: Sandbox parser failed`,
 * nothing else pending), 60 samples of `/api/indexing-roots/substrate` at 300ms:
 *
 *   inFlight=0 failed=2  x56      the settled truth
 *   inFlight=2 failed=0  x4       the retry window — the re-queued jobs count as IN-FLIGHT
 *
 * A re-queued FAILED job is PENDING while it waits, so the worker's per-root `GROUP BY` reports it
 * as in-flight and not as failed; the wire carries nothing that distinguishes it from a first
 * attempt. In those windows the seam took its `inFlight > 0` branch, `failed` fell to 0, the chip
 * disappeared and the drill-down was unreachable — on a folder whose files will NEVER succeed, so
 * the flicker is permanent, not transitional.
 *
 * The three sampled states are pinned below, plus the two directions the rule must NOT go: it may
 * never invent a count the substrate never reported, and a settled zero must discharge the memory.
 *
 * Review finding S2-1 added the third describe block: the carry has to live ABOVE the branch chain,
 * because `provisional` / `unavailable` / `walkError` all outrank the in-flight branch this file
 * originally covered on its own.
 */

import { describe, it, expect } from 'vitest';
import { folderStatus, rememberFailedCounts, failedChipCopy } from './folderStatus';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view';

const row = (over: Partial<IndexedRootView> = {}): IndexedRootView => ({
  pathHash: 'h',
  collection: 'default',
  fileCount: 10,
  lastIndexedIsoTime: '2026-06-17T00:00:00Z',
  status: 'indexed',
  walkError: '',
  inFlightCount: 0,
  failedCount: 0,
  walkCompleted: true,
  ...over,
});

const ctx = (lastKnownFailed?: number, over: { provisional?: boolean } = {}) => ({
  relativeTime: 'just now',
  verifiedRelativeTime: '',
  provisional: false,
  enrichmentPending: false,
  enrichmentBlocked: false,
  ...over,
  ...(lastKnownFailed === undefined ? {} : { lastKnownFailed }),
});

describe('folderStatus — the failed chip survives a retry re-queue (914 D3)', () => {
  it('sample A (56/60): inFlight=0 failed=2 — the settled truth, chip from this poll', () => {
    const fs = folderStatus(row({ inFlightCount: 0, failedCount: 2 }), ctx(2));
    expect(fs.state).toBe('failed');
    expect(fs.failed).toBe(2);
    // Nothing is being carried: this poll reported the number itself.
    expect(fs.failedIsLastKnown).toBeFalsy();
  });

  it('sample B (4/60): inFlight=2 failed=0 — the chip stays, flagged as last-known', () => {
    const fs = folderStatus(row({ inFlightCount: 2, failedCount: 0 }), ctx(2));
    // The state line stays truthful: the queue IS working on two jobs right now.
    expect(fs.state).toBe('indexing');
    expect(fs.metaText).toBe('default · Reading · 2 remaining');
    // ...and the drill-down stays reachable, which is the defect this closes.
    expect(fs.failed).toBe(2);
    expect(fs.failedIsLastKnown).toBe(true);
    // The chip says which kind of number it is rather than asserting it as this tick's count.
    // The qualifier is VISIBLE text (review S2-2), not an aria-only label.
    const copy = failedChipCopy(fs.failed, fs.failedIsLastKnown === true);
    expect(copy.text).toBe('2 failed · last known');
    expect(copy.title).toContain('failed as of the last settled check');
    // WCAG 2.5.3 "label in name" — the accessible name must CONTAIN the visible text.
    expect(copy.label.startsWith(copy.text)).toBe(true);
  });

  it('sample C (1/60 in the validator run): inFlight=1 failed=1 — THIS poll wins over the memory', () => {
    const fs = folderStatus(row({ inFlightCount: 1, failedCount: 1 }), ctx(2));
    expect(fs.state).toBe('indexing');
    // 1, not the remembered 2: a sample that reports a count is never overridden by an older one.
    expect(fs.failed).toBe(1);
    expect(fs.failedIsLastKnown).toBeFalsy();
    const copy = failedChipCopy(fs.failed, fs.failedIsLastKnown === true);
    expect(copy.text).toBe('1 failed');
    expect(copy.title).toBe('');
    expect(copy.label.startsWith(copy.text)).toBe(true);
    // Singular, both places.
    expect(copy.label).toBe('1 failed — show the failed file');
  });

  it('never invents a chip: in-flight with no failure ever observed shows no count', () => {
    const fs = folderStatus(row({ inFlightCount: 2, failedCount: 0 }), ctx());
    expect(fs.state).toBe('indexing');
    expect(fs.failed).toBe(0);
    expect(fs.failedIsLastKnown).toBeFalsy();
  });

  it('a SETTLED branch ignores the memory — a drained root reports its own truth', () => {
    // Same memory as sample B, but the queue is settled: the substrate has answered, so the answer
    // is 0 failures and the row is ready. A sticky chip here would be the lie 813/906 forbid.
    const fs = folderStatus(row({ inFlightCount: 0, failedCount: 0 }), ctx(2));
    expect(fs.state).toBe('ready');
    expect(fs.failed).toBe(0);
    expect(fs.failedIsLastKnown).toBeFalsy();
  });
});

describe('rememberFailedCounts — the bound on the memory (914 D3)', () => {
  it('remembers a reported failure count', () => {
    expect(rememberFailedCounts({}, [row({ pathHash: 'a', failedCount: 2 })])).toEqual({ a: 2 });
  });

  it('refreshes rather than ages: a new number replaces the old one', () => {
    expect(rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 5 })])).toEqual({ a: 5 });
  });

  it('carries the previous number across an UNSETTLED zero (the retry window)', () => {
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 0, inFlightCount: 2 })]),
    ).toEqual({ a: 2 });
  });

  it('DISCHARGES on a settled zero — one drained observation ends the memory', () => {
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 0, inFlightCount: 0 })]),
    ).toEqual({});
  });

  it('drops a root that left the listing, so the map cannot outgrow the current poll', () => {
    expect(rememberFailedCounts({ a: 2, gone: 7 }, [row({ pathHash: 'a', failedCount: 2 })])).toEqual({
      a: 2,
    });
  });

  it('ignores a row with no pathHash rather than keying the memory on an empty string', () => {
    expect(rememberFailedCounts({}, [row({ pathHash: '', failedCount: 3 })])).toEqual({});
  });

  it('while PROVISIONAL a transitional zero cannot discharge the memory (S2-1)', () => {
    // During a global rebuild the seam refuses to treat any per-root number as terminal, so a
    // drained-looking sample is not the settled answer that ends the memory.
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 0, inFlightCount: 0 })], {
        provisional: true,
      }),
    ).toEqual({ a: 2 });
    // ...and the SAME sample outside a rebuild still discharges it.
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 0, inFlightCount: 0 })]),
    ).toEqual({});
  });

  it('a reported count still refreshes the memory while provisional', () => {
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 5 })], { provisional: true }),
    ).toEqual({ a: 5 });
  });
});

/**
 * Review finding S2-1 — the branches that OUTRANK the in-flight one.
 *
 * The first cut carried the count inside `inFlight > 0` only. `provisional`, `unavailable` and
 * `walkError` all return earlier and all returned the RAW `failed`, so a retry window coinciding
 * with any of them still dropped the chip. Measured on the served FE: the shell was provisional for
 * 168 of 250 renders, so the majority path was the unfixed one and the fix engaged 0 times in 250.
 *
 * Each case below is the same wire sample — `failed=0, inFlight=2`, the retry window — differing
 * only in which branch claims it.
 */
describe('folderStatus — the carry applies in every unsettled branch (914 D3 / S2-1)', () => {
  const retryWindow = { inFlightCount: 2, failedCount: 0 };

  it('PROVISIONAL: "Rebuilding…" keeps the chip, flagged last-known', () => {
    const fs = folderStatus(row(retryWindow), ctx(2, { provisional: true }));
    expect(fs.state).toBe('unknown');
    expect(fs.metaText).toContain('Rebuilding…');
    expect(fs.failed).toBe(2);
    expect(fs.failedIsLastKnown).toBe(true);
  });

  it('UNAVAILABLE: a disconnected folder keeps the chip, flagged last-known', () => {
    const fs = folderStatus(row({ ...retryWindow, status: 'unavailable' }), ctx(2));
    expect(fs.state).toBe('unavailable');
    expect(fs.failed).toBe(2);
    expect(fs.failedIsLastKnown).toBe(true);
  });

  it('WALK ERROR: a failed walk keeps the chip, flagged last-known', () => {
    const fs = folderStatus(row({ ...retryWindow, walkError: 'access denied' }), ctx(2));
    expect(fs.state).toBe('failed');
    expect(fs.metaText).toContain('access denied');
    expect(fs.failed).toBe(2);
    expect(fs.failedIsLastKnown).toBe(true);
  });

  it('none of the three invents a chip with nothing carried', () => {
    expect(folderStatus(row(retryWindow), ctx(undefined, { provisional: true })).failed).toBe(0);
    expect(folderStatus(row({ ...retryWindow, status: 'unavailable' }), ctx()).failed).toBe(0);
    expect(folderStatus(row({ ...retryWindow, walkError: 'access denied' }), ctx()).failed).toBe(0);
  });

  it('a SETTLED branch never carries — the raw count stands where the queue has answered', () => {
    // `ready` and `empty` are reached only with inFlight === 0 && failed === 0; a chip resurrected
    // from memory over a drained folder would be the lie 813/906 forbid, so these stay at 0 even
    // when a stale carry is handed in.
    expect(folderStatus(row(), ctx(2)).state).toBe('ready');
    expect(folderStatus(row(), ctx(2)).failed).toBe(0);
    const empty = folderStatus(
      row({ status: 'scanned', lastIndexedIsoTime: '', fileCount: 0 }),
      ctx(2),
    );
    expect(empty.state).toBe('empty');
    expect(empty.failed).toBe(0);
  });
});
