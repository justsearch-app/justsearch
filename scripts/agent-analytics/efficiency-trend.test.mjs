/**
 * efficiency-trend.test.mjs — unit tests for the trend reader (tempdoc 908 §5.1),
 * on synthetic Call fixtures built in-process (no real transcript content).
 *
 * Run with: `node scripts/agent-analytics/efficiency-trend.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import {
  percentile, isoWeekKey, dayKey, bucketKey, weekBounds, dayBounds, bucketBounds,
  bucketOverlapsWindow, buildLeadingIndicators, buildBucketedSpawnRows, buildSpawnTail,
  classifyPath, buildDeliveryRows, deliveryPowerWarning,
  classifyBucket, resolveGitRef, mergeLiveAndSnapshot, countBySource,
  resolveRowFlags, countUnknown,
} from './efficiency-trend.mjs';
import { makeCall } from './lib/ledger/record.mjs';

const SCRIPT_PATH = path.join(path.dirname(fileURLToPath(import.meta.url)), 'efficiency-trend.mjs');

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- percentile (shared with context-residency.mjs's convention) -----------

run('percentile: p50 of [1,2,3,4,5] is the middle element (floor-index method)', () => {
  assert.equal(percentile([1, 2, 3, 4, 5], 0.5), 3);
});

run('percentile: the two conventions named in 908 §5.1/§6.1 are the SAME value', () => {
  // sorted[floor(p*len)] (p=0.5) vs sorted[floor(len/2)] -- verified identical
  // for every integer len (0.5 is exact in IEEE-754 double), not merely assumed.
  for (const len of [1, 2, 3, 4, 5, 7, 10, 50, 131, 257]) {
    const sorted = Array.from({ length: len }, (_, i) => i);
    assert.equal(percentile(sorted, 0.5), sorted[Math.floor(len / 2)]);
  }
});

// --- ISO-week / day bucketing (boundary alignment against known dates) -----

run('isoWeekKey: Monday 2026-08-03 through Sunday 2026-08-09 all fall in 2026-W32', () => {
  assert.equal(isoWeekKey(Date.parse('2026-08-03T00:00:00.000Z')), '2026-W32');
  assert.equal(isoWeekKey(Date.parse('2026-08-09T23:59:59.999Z')), '2026-W32');
});

run('isoWeekKey: the Monday boundary itself rolls to the next week', () => {
  assert.equal(isoWeekKey(Date.parse('2026-08-10T00:00:00.000Z')), '2026-W33');
});

run('isoWeekKey: a late-December Monday can belong to the FOLLOWING ISO year (year-boundary correctness)', () => {
  assert.equal(isoWeekKey(Date.parse('2025-12-29T00:00:00.000Z')), '2026-W01');
});

run('dayKey: UTC calendar day, independent of time-of-day', () => {
  assert.equal(dayKey(Date.parse('2026-08-20T23:59:59.999Z')), '2026-08-20');
  assert.equal(dayKey(Date.parse('2026-08-20T00:00:00.000Z')), '2026-08-20');
});

run('bucketKey dispatches on `by`', () => {
  const ts = Date.parse('2026-08-20T12:00:00.000Z');
  assert.equal(bucketKey(ts, 'week'), isoWeekKey(ts));
  assert.equal(bucketKey(ts, 'day'), dayKey(ts));
});

run('weekBounds/dayBounds round-trip: bucketing a bound\'s own start ms returns the same key', () => {
  const wb = weekBounds('2026-W32');
  assert.equal(isoWeekKey(wb.startMs), '2026-W32');
  assert.equal(wb.endMs - wb.startMs, 7 * 24 * 60 * 60 * 1000);
  const db = dayBounds('2026-08-20');
  assert.equal(dayKey(db.startMs), '2026-08-20');
  assert.equal(db.endMs - db.startMs, 24 * 60 * 60 * 1000);
});

run('bucketBounds dispatches on `by` the same way bucketKey does', () => {
  assert.deepEqual(bucketBounds('2026-W32', 'week'), weekBounds('2026-W32'));
  assert.deepEqual(bucketBounds('2026-08-20', 'day'), dayBounds('2026-08-20'));
});

// --- buildLeadingIndicators ---------------------------------------------------

function call(overrides = {}) {
  return makeCall({
    harness: overrides.harness ?? 'claude-code',
    sessionId: overrides.sessionId ?? 's1',
    lineage: overrides.lineage ?? { kind: 'main' },
    model: overrides.model ?? 'claude-opus-5',
    contextTokens: overrides.contextTokens ?? 100000,
    ts: overrides.ts ?? '2026-08-03T00:00:00.000Z',
    tokens: overrides.tokens ?? { fresh: 0, output: 100 },
    synthetic: overrides.synthetic ?? false,
  });
}

run('buildLeadingIndicators: one row per bucket, calls/cost/ctxOut accumulate', () => {
  const calls = [
    call({ ts: '2026-08-03T00:00:00.000Z', contextTokens: 100000, tokens: { fresh: 0, output: 100 } }),
    call({ ts: '2026-08-04T00:00:00.000Z', contextTokens: 200000, tokens: { fresh: 0, output: 100 } }),
  ];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].bucket, '2026-W32');
  assert.equal(rows[0].calls, 2);
  assert.equal(rows[0].ctxOut, 1500); // (100000+200000)/200
});

run('buildLeadingIndicators: main and spawn/fork contextTokens percentiles are tracked separately', () => {
  const calls = [
    call({ lineage: { kind: 'main' }, contextTokens: 400000 }),
    call({ lineage: { kind: 'spawn' }, contextTokens: 100000 }),
    call({ lineage: { kind: 'fork' }, contextTokens: 200000 }),
  ];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows[0].mainP50Ctx, 400000);
  // spawn+fork collapse into one "sub" percentile group -- p50 of [100000,200000] floor(0.5*2)=idx1
  assert.equal(rows[0].subP50Ctx, 200000);
});

run('buildLeadingIndicators: unpriced calls are counted, never silently summed as $0', () => {
  const calls = [call({ model: 'gpt-5.5-unknown-model', tokens: { fresh: 0, output: 100 } })];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows[0].unpricedCalls, 1);
  assert.equal(rows[0].costUsd, 0);
});

run('buildLeadingIndicators: a call with no parsable ts is excluded, not folded into a bucket', () => {
  // makeCall's own `ts: partial.ts ?? null` means an EXPLICIT null stays null;
  // bypass the `call()` helper's default-ts fallback (which also treats null
  // as "use the default" via `??`) by constructing directly.
  const calls = [
    makeCall({ harness: 'claude-code', sessionId: 's1', model: 'claude-opus-5', contextTokens: 1000, ts: null, tokens: { fresh: 0, output: 1 } }),
    makeCall({ harness: 'claude-code', sessionId: 's1', model: 'claude-opus-5', contextTokens: 1000, ts: 'not-a-date', tokens: { fresh: 0, output: 1 } }),
  ];
  const { rows, excludedUnbucketable } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows.length, 0);
  assert.equal(excludedUnbucketable, 2);
});

run('buildLeadingIndicators: subCostSharePct is share of PRICED cost only', () => {
  const calls = [
    call({ lineage: { kind: 'main' }, model: 'claude-opus-5', tokens: { fresh: 0, output: 100 } }),
    call({ lineage: { kind: 'spawn' }, model: 'claude-opus-5', tokens: { fresh: 0, output: 100 } }),
  ];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows[0].subCostSharePct, 50);
});

// --- buildBucketedSpawnRows: spawn-bucket-by-first-call ------------------------

run('buildBucketedSpawnRows: a spawn crossing a bucket boundary is assigned ONE bucket -- the FIRST call\'s', () => {
  const calls = [
    // one spawn (sessionId s1) whose first call is in W32, second call drifts into W33
    call({ sessionId: 's1', lineage: { kind: 'spawn' }, ts: '2026-08-09T23:00:00.000Z', contextTokens: 50000 }),
    call({ sessionId: 's1', lineage: { kind: 'spawn' }, ts: '2026-08-10T01:00:00.000Z', contextTokens: 300000 }),
  ];
  const rows = buildBucketedSpawnRows(calls, 'week');
  assert.equal(rows.length, 1, 'one spawn == one row, not smeared across two buckets');
  assert.equal(rows[0].bucket, '2026-W32', 'bucketed by the FIRST call, even though the 2nd call is in W33');
  assert.equal(rows[0].calls, 2);
  assert.equal(rows[0].peakContextTokens, 300000, 'peak still looks at every call in the spawn');
});

run('buildBucketedSpawnRows: a spawn with no dateable first call is excluded (not bucketed as "now")', () => {
  const calls = [makeCall({
    harness: 'claude-code', sessionId: 's2', lineage: { kind: 'spawn' }, model: 'claude-opus-5',
    contextTokens: 1000, ts: null, tokens: { fresh: 0, output: 1 },
  })];
  const rows = buildBucketedSpawnRows(calls, 'week');
  assert.equal(rows.length, 0);
});

run('buildBucketedSpawnRows: unpriced calls inside a spawn are counted, not zero-priced silently', () => {
  const calls = [
    call({ sessionId: 's3', lineage: { kind: 'spawn' }, model: 'claude-opus-5', ts: '2026-08-03T00:00:00.000Z' }),
    call({ sessionId: 's3', lineage: { kind: 'spawn' }, model: 'unknown-xyz', ts: '2026-08-03T00:01:00.000Z' }),
  ];
  const rows = buildBucketedSpawnRows(calls, 'week');
  assert.equal(rows[0].unpricedCalls, 1);
});

// --- buildSpawnTail ------------------------------------------------------------

run('buildSpawnTail: long-spawn bucket threshold is >= (inclusive)', () => {
  const spawnRows = [
    { bucket: 'w', calls: 120, peakContextTokens: 100, costUsd: 10, unpricedCalls: 0 },
    { bucket: 'w', calls: 119, peakContextTokens: 100, costUsd: 5, unpricedCalls: 0 },
  ];
  const rows = buildSpawnTail(spawnRows, 120);
  assert.equal(rows[0].longSpawns, 1);
  assert.equal(rows[0].longCostSharePct, round1(10 / 15 * 100));
});
function round1(n) { return Math.round(n); }

run('buildSpawnTail: $/spawn is MEAN cost per spawn (total bucket cost / spawn count)', () => {
  const spawnRows = [
    { bucket: 'w', calls: 10, peakContextTokens: 100, costUsd: 10, unpricedCalls: 0 },
    { bucket: 'w', calls: 20, peakContextTokens: 200, costUsd: 20, unpricedCalls: 0 },
  ];
  const rows = buildSpawnTail(spawnRows, 120);
  assert.equal(rows[0].costPerSpawn, 15); // (10+20)/2
});

// --- classifyPath / buildDeliveryRows ------------------------------------------

run('classifyPath: code/docs/noise/other classification', () => {
  assert.equal(classifyPath('scripts/agent-analytics/foo.mjs'), 'code');
  assert.equal(classifyPath('modules/ui/src/main/java/Foo.java'), 'code');
  assert.equal(classifyPath('gates/foo/thing.json'), 'code');
  assert.equal(classifyPath('contracts/foo.proto'), 'code');
  assert.equal(classifyPath('docs/tempdocs/908-foo.md'), 'docs');
  assert.equal(classifyPath('package-lock.json'), 'noise');
  assert.equal(classifyPath('Cargo.lock'), 'noise');
  assert.equal(classifyPath('modules/ui-web/node_modules/foo/index.js'), 'noise');
  assert.equal(classifyPath('assets/logo.svg'), 'noise');
  assert.equal(classifyPath('README.md'), 'other');
});

run('buildDeliveryRows: median churn/PR and code-only churn split (908 §1.2b size control)', () => {
  const commits = [
    { hash: 'a', bucket: '2026-W32', files: [{ added: 100, deleted: 0, path: 'scripts/foo.mjs' }, { added: 5, deleted: 0, path: 'docs/bar.md' }] },
    { hash: 'b', bucket: '2026-W32', files: [{ added: 10, deleted: 10, path: 'package-lock.json' }] },
  ];
  const rows = buildDeliveryRows(commits, new Map([['2026-W32', 100]]));
  assert.equal(rows[0].prCount, 2);
  assert.equal(rows[0].codeChurn, 100, 'only the scripts/ file counts as code; docs and lockfile do not');
  assert.equal(rows[0].costPerLandedPR, 50); // 100/2
  assert.equal(rows[0].costPerKCodeLines, 1000); // 100*1000/100
});

run('buildDeliveryRows: a binary file\'s "-" numstat entry counts as 0, never NaN', () => {
  const commits = [{ hash: 'a', bucket: '2026-W32', files: [{ added: 0, deleted: 0, path: 'scripts/foo.bin' }] }];
  const rows = buildDeliveryRows(commits);
  assert.equal(rows[0].codeChurn, 0);
  assert.ok(!Number.isNaN(rows[0].medChurnPerPR));
});

run('buildDeliveryRows: a bucket absent from costByBucket reports null cost, never a fabricated $0', () => {
  const commits = [{ hash: 'a', bucket: '2026-W99', files: [{ added: 10, deleted: 0, path: 'scripts/foo.mjs' }] }];
  const rows = buildDeliveryRows(commits, new Map());
  assert.equal(rows[0].costUsd, null);
  assert.equal(rows[0].costPerLandedPR, null);
  assert.equal(rows[0].costPerKCodeLines, null);
});

run('deliveryPowerWarning: excludes TRUNCATED/PARTIAL buckets from the swing computation', () => {
  const rows = [
    { bucket: 'w1', costPerKCodeLines: 100 },
    { bucket: 'w2', costPerKCodeLines: 10 }, // huge swing, but excluded
    { bucket: 'w3', costPerKCodeLines: 90 },
  ];
  const msg = deliveryPowerWarning(rows, new Set(['w2']));
  assert.match(msg, /swings 1\.1x/); // 100/90, not 100/10 -- w2 excluded
});

run('deliveryPowerWarning: fewer than 2 eligible buckets reports "no swing computable", not a fabricated ratio', () => {
  const msg = deliveryPowerWarning([{ bucket: 'w1', costPerKCodeLines: 100 }], new Set());
  assert.match(msg, /no swing computable/);
});

// --- classifyBucket: truncated/partial marking ---------------------------------

run('classifyBucket: a bucket starting before floor+1day is TRUNCATED', () => {
  const bounds = { startMs: Date.parse('2026-08-03T00:00:00.000Z'), endMs: Date.parse('2026-08-10T00:00:00.000Z') };
  const floorMs = Date.parse('2026-08-04T12:00:00.000Z');
  const nowMs = Date.parse('2026-09-01T00:00:00.000Z');
  assert.deepEqual(classifyBucket(bounds, floorMs, nowMs), { truncated: true, partial: false });
});

run('classifyBucket: a bucket starting on-or-after floor+1day is NOT truncated', () => {
  const bounds = { startMs: Date.parse('2026-08-10T00:00:00.000Z'), endMs: Date.parse('2026-08-17T00:00:00.000Z') };
  const floorMs = Date.parse('2026-08-04T12:00:00.000Z');
  const nowMs = Date.parse('2026-09-01T00:00:00.000Z');
  assert.equal(classifyBucket(bounds, floorMs, nowMs).truncated, false);
});

run('classifyBucket: a bucket extending past now is PARTIAL', () => {
  const bounds = { startMs: Date.parse('2026-08-31T00:00:00.000Z'), endMs: Date.parse('2026-09-07T00:00:00.000Z') };
  const nowMs = Date.parse('2026-09-02T12:00:00.000Z');
  assert.equal(classifyBucket(bounds, null, nowMs).partial, true);
});

run('classifyBucket: a null floor (no transcripts discovered) never fabricates TRUNCATED', () => {
  const bounds = { startMs: Date.parse('2026-08-03T00:00:00.000Z'), endMs: Date.parse('2026-08-10T00:00:00.000Z') };
  assert.equal(classifyBucket(bounds, null, Date.parse('2026-09-01T00:00:00.000Z')).truncated, false);
});

// --- resolveGitRef: --no-git / git-absent behavior -----------------------------

run('resolveGitRef: an unresolvable ref AND an unresolvable "main" fallback returns null (never fabricates a ref)', () => {
  // a directory that is not inside a git repo at all -- both rev-parse calls fail
  const result = resolveGitRef({ cwd: os.tmpdir(), ref: 'origin/definitely-not-a-real-ref-xyz' });
  assert.equal(result, null);
});

run('resolveGitRef: the current repo\'s "main" resolves even when the requested ref does not (fallback: true)', () => {
  const result = resolveGitRef({ cwd: process.cwd(), ref: 'definitely-not-a-real-ref-xyz-908' });
  // main exists in this repo (branch-safety.md: main checkout stays on main)
  if (result) {
    assert.equal(result.ref, 'main');
    assert.equal(result.fallback, true);
  }
});

// --- mergeLiveAndSnapshot -------------------------------------------------------

run('mergeLiveAndSnapshot: live wins on a shared bucket key; snapshot fills gaps; both are labelled', () => {
  const live = [{ bucket: '2026-W35', calls: 100 }];
  const snapshot = [{ bucket: '2026-W35', calls: 999 }, { bucket: '2026-W30', calls: 50 }];
  const merged = mergeLiveAndSnapshot(live, snapshot);
  assert.equal(merged.length, 2);
  const w35 = merged.find((r) => r.bucket === '2026-W35');
  const w30 = merged.find((r) => r.bucket === '2026-W30');
  assert.equal(w35.calls, 100, 'live value wins, not the stale snapshot value');
  assert.equal(w35.source, 'live');
  assert.equal(w30.calls, 50, 'snapshot fills a bucket with no live data');
  assert.equal(w30.source, 'snapshot');
});

// --- bucketOverlapsWindow: regression for "snapshot merge ignores --since/--until" ---
// (independent-verification defect 1 -- the merge previously filtered a
// snapshot record only by harness/by, never by the requested window, so a
// 10-day query silently returned five weeks of stale snapshot data.)

run('bucketOverlapsWindow: a bucket entirely BEFORE sinceMs is excluded', () => {
  // 2026-W32 = 2026-08-03..2026-08-10; a window starting 2026-08-24 must not admit it
  assert.equal(bucketOverlapsWindow('2026-W32', 'week', Date.parse('2026-08-24T00:00:00.000Z'), null), false);
});

run('bucketOverlapsWindow: a bucket entirely AFTER untilMs is excluded', () => {
  assert.equal(bucketOverlapsWindow('2026-W40', 'week', null, Date.parse('2026-08-24T00:00:00.000Z')), false);
});

run('bucketOverlapsWindow: a bucket fully inside [sinceMs, untilMs] is admitted -- the other direction', () => {
  // a fix that dropped ALL snapshot rows would pass the two exclusion tests
  // above while destroying the feature; this is the test that catches that.
  assert.equal(bucketOverlapsWindow(
    '2026-W35', 'week', Date.parse('2026-08-24T00:00:00.000Z'), Date.parse('2026-09-02T00:00:00.000Z'),
  ), true);
});

run('bucketOverlapsWindow: a bucket straddling sinceMs (partial overlap) is admitted, not excluded', () => {
  // sinceMs lands mid-week -- the bucket still has hours of overlap with the window
  assert.equal(bucketOverlapsWindow('2026-W32', 'week', Date.parse('2026-08-05T00:00:00.000Z'), null), true);
});

run('bucketOverlapsWindow: null sinceMs/untilMs means unbounded on that side (matches lib/ledger/index.mjs\'s inTsWindow convention)', () => {
  assert.equal(bucketOverlapsWindow('2026-W32', 'week', null, null), true);
});

// --- countBySource -----------------------------------------------------------

run('countBySource: counts live vs snapshot, defaulting an absent `source` to live', () => {
  const rows = [{ source: 'live' }, { source: 'snapshot' }, {}, { source: 'snapshot' }];
  assert.deepEqual(countBySource(rows), { live: 2, snapshot: 2 });
});

// --- CLI-level regression: snapshot merge respects the window AND labels source ---
// (independent-verification defects 1+2, reproduced live against
// tmp/agent-telemetry/efficiency-trend.ndjson before this fix landed; these
// use a SCRATCH snapshot file via --snapshot-path, never the real store.)

function withScratchSnapshot(records, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'eff-trend-cli-'));
  const file = path.join(dir, 'snap.ndjson');
  fs.writeFileSync(file, records.map((r) => JSON.stringify(r)).join('\n') + '\n');
  try {
    return fn(file);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

run('CLI: a narrow --since/--until window admits an IN-window snapshot bucket and excludes an OUT-of-window one', () => {
  const records = [
    { bucket: '2020-W01', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 11, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
    { bucket: '2021-W01', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 22, costUsd: 2, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
  ];
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2020-12-28', '--until', '2021-01-11', '--harness', 'claude-code',
      '--no-git', '--snapshot-path', file,
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    assert.match(res.stdout, /2021-W01/, 'the in-window snapshot bucket must be admitted');
    assert.doesNotMatch(res.stdout, /2020-W01/, 'the out-of-window snapshot bucket must NOT leak in (defect 1)');
  });
});

run('CLI: a snapshot-sourced row is visibly labelled "snapshot" in the human output (defect 2)', () => {
  const records = [
    { bucket: '2020-W01', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 11, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: { spawns: 1, medCalls: 1, medPeakCtx: 1, costPerSpawn: 1, longSpawns: 0, longCostSharePct: 0, unpricedCalls: 0 } },
  ];
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2019-12-30', '--until', '2020-01-06', '--harness', 'claude-code',
      '--no-git', '--snapshot-path', file,
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    // the 2020-W01 row line itself must carry the "snapshot" label, not just
    // the word appearing SOMEWHERE in the output (e.g. a header or count line)
    const row = res.stdout.split('\n').find((l) => l.startsWith('2020-W01'));
    assert.ok(row, 'expected a 2020-W01 row in the leading-indicators table');
    assert.match(row, /snapshot/, 'a snapshot-sourced row must be visibly labelled, not indistinguishable from live');
    assert.match(res.stdout, /row sources: leading 0 live \/ 1 snapshot/, 'section (d) must state the live/snapshot row counts');
  });
});

run('CLI --json: a snapshot-sourced row carries source:"snapshot" and an in-window live-only bucket carries source:"live"', () => {
  const records = [
    { bucket: '2020-W01', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 11, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
  ];
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2019-12-30', '--until', '2020-01-06', '--harness', 'claude-code',
      '--no-git', '--snapshot-path', file, '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    const parsed = JSON.parse(res.stdout);
    assert.equal(parsed.leading.length, 1);
    assert.equal(parsed.leading[0].source, 'snapshot');
    assert.equal(parsed.corpusHonesty.sourceCounts.leading.snapshot, 1);
  });
});

// --- resolveRowFlags / countUnknown: regression for defect 3 -----------------
// (independent-verification defect 3 -- a snapshot row was re-judged against
// TODAY's rotation floor, so every bucket the snapshot rescues was flagged
// TRUNCATED forever, defeating trend-snapshot.mjs's whole purpose.)

const FAR_PAST_BOUNDS = { startMs: Date.parse('2020-01-06T00:00:00.000Z'), endMs: Date.parse('2020-01-13T00:00:00.000Z') };
const TODAY_FLOOR_MS = Date.parse('2026-08-04T00:00:00.000Z'); // long after FAR_PAST_BOUNDS
const NOW_MS = Date.parse('2026-09-02T00:00:00.000Z');

run('resolveRowFlags: a snapshot row captured COMPLETE is not flagged, even though it is before TODAY\'s floor', () => {
  const row = { bucket: '2020-W02', source: 'snapshot', truncatedAtCapture: false, partialAtCapture: false };
  const flags = resolveRowFlags(row, TODAY_FLOOR_MS, NOW_MS, 'week');
  assert.deepEqual(flags, { truncated: false, partial: false, unknown: false });
});

run('resolveRowFlags: a snapshot row captured TRUNCATED stays flagged (the writer\'s own honest capture-time state)', () => {
  const row = { bucket: '2020-W02', source: 'snapshot', truncatedAtCapture: true, partialAtCapture: false };
  const flags = resolveRowFlags(row, TODAY_FLOOR_MS, NOW_MS, 'week');
  assert.deepEqual(flags, { truncated: true, partial: false, unknown: false });
});

run('resolveRowFlags: a legacy snapshot record (neither field present) reads UNKNOWN, never a guessed false', () => {
  const row = { bucket: '2020-W02', source: 'snapshot' }; // no truncatedAtCapture/partialAtCapture at all
  const flags = resolveRowFlags(row, TODAY_FLOOR_MS, NOW_MS, 'week');
  assert.deepEqual(flags, { truncated: false, partial: false, unknown: true });
});

run('resolveRowFlags: a legacy record with only ONE of the two fields present is STILL unknown (both-or-unknown, no partial credit)', () => {
  const row = { bucket: '2020-W02', source: 'snapshot', truncatedAtCapture: false };
  assert.equal(resolveRowFlags(row, TODAY_FLOOR_MS, NOW_MS, 'week').unknown, true);
});

run('resolveRowFlags: a LIVE pre-floor row is STILL judged against today\'s floor (no regression on existing behaviour)', () => {
  const row = { bucket: '2020-W02', source: 'live' };
  const bounds = bucketBounds(row.bucket, 'week');
  assert.deepEqual(bounds, FAR_PAST_BOUNDS);
  const flags = resolveRowFlags(row, TODAY_FLOOR_MS, NOW_MS, 'week');
  assert.equal(flags.truncated, true, 'a live row this far before the floor must still read TRUNCATED');
  assert.equal(flags.unknown, false);
});

run('countUnknown: counts only rows with unknown:true', () => {
  const rows = [{ unknown: true }, { unknown: false }, {}, { unknown: true }];
  assert.equal(countUnknown(rows), 2, 'the bare {} row is not unknown:true and must not be counted');
});

run('CLI: a complete-at-capture snapshot row is unflagged next to a legacy record reading UNKNOWN', () => {
  const records = [
    // legacy shape -- no truncatedAtCapture/partialAtCapture at all
    { bucket: '2020-W01', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 11, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
    // new shape -- captured complete, even though it is long before today's rotation floor
    { bucket: '2020-W02', harness: 'claude-code', by: 'week', generatedAtMs: 1, truncatedAtCapture: false, partialAtCapture: false, leading: { calls: 22, costUsd: 2, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
  ];
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2019-12-30', '--until', '2020-01-20', '--harness', 'claude-code',
      '--no-git', '--snapshot-path', file, '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    const parsed = JSON.parse(res.stdout);
    const w01 = parsed.leading.find((r) => r.bucket === '2020-W01');
    const w02 = parsed.leading.find((r) => r.bucket === '2020-W02');
    assert.equal(w01.unknown, true, 'the legacy record must read UNKNOWN, not a guessed complete/truncated');
    assert.equal(w01.truncated, false, 'UNKNOWN must not also masquerade as TRUNCATED');
    assert.equal(w02.unknown, false);
    assert.equal(w02.truncated, false, 'a row captured complete must NOT be re-flagged TRUNCATED against a later floor -- the defect this fixes');
    assert.equal(parsed.corpusHonesty.unknownCounts.leading, 1);
  });

  // human-readable path: same fixture, assert the printed table itself
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2019-12-30', '--until', '2020-01-20', '--harness', 'claude-code', '--no-git', '--snapshot-path', file,
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    const w01Line = res.stdout.split('\n').find((l) => l.startsWith('2020-W01'));
    const w02Line = res.stdout.split('\n').find((l) => l.startsWith('2020-W02'));
    assert.match(w01Line, /UNKNOWN/, 'the legacy row must print UNKNOWN in the flags column');
    assert.doesNotMatch(w02Line, /TRUNCATED|UNKNOWN/, 'the complete-at-capture row must print with NO flag at all');
    assert.match(res.stdout, /UNKNOWN completeness .*leading 1, spawn tail 0/, 'section (d) must tally UNKNOWN rows separately from TRUNCATED/PARTIAL');
  });
});

// --- corpusHonesty.truncatedBuckets/partialBuckets: regression for defect 4 ---
// (independent-verification defect 4 -- the lists were derived from EVERY
// bucket key any section mentioned, so a bucket whose ONLY row was a
// source:'snapshot' UNKNOWN row still got named TRUNCATED -- contradicting
// its own row's flag, in the one section whose job is to not mislead.)

run('CLI: a bucket whose only row is an UNKNOWN snapshot row must NOT appear in truncatedBuckets, even though it predates the floor', () => {
  const records = [
    // legacy record, no captured flags -- reads UNKNOWN, and this bucket is
    // long before whatever floor the live scan discovers on this machine
    { bucket: '2020-W02', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 11, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
  ];
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2019-12-30', '--until', '2020-01-20', '--harness', 'claude-code',
      '--no-git', '--snapshot-path', file, '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    const parsed = JSON.parse(res.stdout);
    assert.equal(parsed.leading[0].bucket, '2020-W02');
    assert.equal(parsed.leading[0].unknown, true);
    assert.deepEqual(parsed.corpusHonesty.truncatedBuckets, [], 'a bucket with no live row must never be named TRUNCATED');
  });
});

run('CLI (consistency assertion): every bucket in truncatedBuckets has a LIVE row flagged truncated, and no UNKNOWN bucket is also in truncatedBuckets', () => {
  // Mix: one LIVE-shaped truncated bucket (far past, no snapshot involved --
  // this reader has no live ledger data that far back, so it is absent
  // entirely -- proving the OTHER direction) plus the UNKNOWN snapshot bucket
  // from the test above, in the SAME run.
  const records = [
    { bucket: '2020-W02', harness: 'claude-code', by: 'week', generatedAtMs: 1, leading: { calls: 11, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
    { bucket: '2020-W03', harness: 'claude-code', by: 'week', generatedAtMs: 1, truncatedAtCapture: true, partialAtCapture: false, leading: { calls: 5, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 }, spawnTail: null },
  ];
  withScratchSnapshot(records, (file) => {
    const res = spawnSync(process.execPath, [
      SCRIPT_PATH, '--since', '2019-12-30', '--until', '2020-01-27', '--harness', 'claude-code',
      '--no-git', '--snapshot-path', file, '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    const parsed = JSON.parse(res.stdout);
    const allLeadingRows = parsed.leading;
    const unknownBuckets = new Set(allLeadingRows.filter((r) => r.unknown).map((r) => r.bucket));

    for (const bucket of parsed.corpusHonesty.truncatedBuckets) {
      const hasLiveTruncatedRow = allLeadingRows.some((r) => r.bucket === bucket && r.source === 'live' && r.truncated === true);
      assert.ok(hasLiveTruncatedRow, `truncatedBuckets names ${bucket} but no LIVE row there is flagged truncated`);
      assert.ok(!unknownBuckets.has(bucket), `${bucket} is both UNKNOWN and TRUNCATED -- contradiction`);
    }
    // 2020-W03 is a SNAPSHOT row (not live), so even though truncatedAtCapture
    // is true it must not appear in truncatedBuckets (that list is LIVE-only
    // by construction; its own per-row `truncated:true` flag is enough).
    assert.ok(!parsed.corpusHonesty.truncatedBuckets.includes('2020-W03'));
    const w03 = allLeadingRows.find((r) => r.bucket === '2020-W03');
    assert.equal(w03.source, 'snapshot');
    assert.equal(w03.truncated, true, 'the row itself still honestly reports its captured truncation');
  });
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`efficiency-trend.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`efficiency-trend.test: ${passed} passed`);
