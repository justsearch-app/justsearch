/**
 * trend-snapshot.test.mjs — unit tests for the trend snapshot writer (tempdoc
 * 908 §5.2), on synthetic records built in-process. No test touches the real
 * `tmp/agent-telemetry/efficiency-trend.ndjson`.
 *
 * Run with: `node scripts/agent-analytics/trend-snapshot.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  recordKey, buildSnapshotRecord, findContentLeaks, upsertRecords,
  readSnapshotFile, writeSnapshotFile, buildRunLengthHistogram,
} from './trend-snapshot.mjs';

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

// --- buildRunLengthHistogram -----------------------------------------------

run('buildRunLengthHistogram: buckets by calls, matching the [0-10,...,500+] bands', () => {
  const rows = [{ calls: 5 }, { calls: 15 }, { calls: 125 }, { calls: 600 }];
  const hist = buildRunLengthHistogram(rows);
  assert.equal(hist['0-10'], 1);
  assert.equal(hist['10-30'], 1);
  assert.equal(hist['120-250'], 1);
  assert.equal(hist['500+'], 1);
  assert.equal(hist['30-60'], 0);
});

// --- buildSnapshotRecord: aggregates only, null when absent ------------------

run('buildSnapshotRecord: leading/spawnTail are null when the row is absent, never a fabricated zero-row', () => {
  const r = buildSnapshotRecord({ bucket: '2026-W33', harness: 'claude-code', by: 'week', generatedAtMs: 1, leadingRow: undefined, spawnTailRow: undefined });
  assert.equal(r.leading, null);
  assert.equal(r.spawnTail, null);
});

run('buildSnapshotRecord: carries only aggregate fields off a leadingRow/spawnTailRow', () => {
  const r = buildSnapshotRecord({
    bucket: '2026-W33', harness: 'claude-code', by: 'week', generatedAtMs: 123,
    leadingRow: { calls: 10, costUsd: 5, unpricedCalls: 0, ctxOut: 100, costPerMOut: 50, mainP50Ctx: 1000, subP50Ctx: 500, subCostSharePct: 10 },
    spawnTailRow: { spawns: 2, medCalls: 5, medPeakCtx: 100, costPerSpawn: 2.5, longSpawns: 0, longCostSharePct: 0, unpricedCalls: 0 },
    runLengthHistogram: { '0-10': 2 },
  });
  assert.equal(r.leading.calls, 10);
  assert.equal(r.spawnTail.spawns, 2);
  assert.deepEqual(r.spawnTail.runLengthHistogram, { '0-10': 2 });
});

// --- truncatedAtCapture/partialAtCapture: regression for defect 3 ------------
// (independent-verification defect 3 -- completeness must be recorded AT
// CAPTURE TIME, not re-derived later against a floor that has since moved.)

run('buildSnapshotRecord: carries truncatedAtCapture/partialAtCapture at the TOP LEVEL, sibling to leading/spawnTail', () => {
  const r = buildSnapshotRecord({
    bucket: '2026-W33', harness: 'claude-code', by: 'week', generatedAtMs: 1,
    leadingRow: { calls: 1, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 },
    truncatedAtCapture: false, partialAtCapture: true,
  });
  assert.equal(r.truncatedAtCapture, false);
  assert.equal(r.partialAtCapture, true);
});

run('findContentLeaks: booleans truncatedAtCapture/partialAtCapture never trip the string-shape checker', () => {
  const r = buildSnapshotRecord({
    bucket: '2026-W33', harness: 'claude-code', by: 'week', generatedAtMs: 1,
    leadingRow: { calls: 1, costUsd: 1, unpricedCalls: 0, ctxOut: 1, costPerMOut: 1, mainP50Ctx: 1, subP50Ctx: 1, subCostSharePct: 0 },
    truncatedAtCapture: true, partialAtCapture: false,
  });
  assert.deepEqual(findContentLeaks(r), []);
});

// --- findContentLeaks: the aggregates-only rule enforced by test -------------

run('findContentLeaks: a real snapshot record produces zero leaks', () => {
  const r = buildSnapshotRecord({
    bucket: '2026-W33', harness: 'claude-code', by: 'week', generatedAtMs: 123,
    leadingRow: { calls: 10, costUsd: 5, unpricedCalls: 0, ctxOut: 100, costPerMOut: 50, mainP50Ctx: 1000, subP50Ctx: 500, subCostSharePct: 10 },
  });
  assert.deepEqual(findContentLeaks(r), []);
});

run('findContentLeaks: a path-like string (contains "/") is flagged', () => {
  const violation = { bucket: '2026-W33', evilField: 'F:/justsearch-public/some/leaked/path.jsonl' };
  const leaks = findContentLeaks(violation);
  assert.ok(leaks.some((l) => l.path === '$.evilField' && /path-like/.test(l.reason)));
});

run('findContentLeaks: a string longer than maxLen is flagged even without a slash', () => {
  const violation = { evilField: 'x'.repeat(200) };
  const leaks = findContentLeaks(violation, 64);
  assert.ok(leaks.some((l) => l.path === '$.evilField' && /longer than 64/.test(l.reason)));
});

run('findContentLeaks: walks nested arrays/objects, not just top-level keys', () => {
  const violation = { nested: { list: ['ok', 'a/b/c'] } };
  const leaks = findContentLeaks(violation);
  assert.ok(leaks.some((l) => l.path === '$.nested.list[1]'));
});

run('findContentLeaks: a bare bucket key ("2026-W33") and harness name are never flagged', () => {
  assert.deepEqual(findContentLeaks({ bucket: '2026-W33', harness: 'claude-code', by: 'week' }), []);
});

// --- upsertRecords: idempotent replace, not append ---------------------------

run('upsertRecords: a matching (bucket, harness) key REPLACES, never duplicates', () => {
  const existing = [{ bucket: '2026-W32', harness: 'claude-code', v: 1 }, { bucket: '2026-W33', harness: 'claude-code', v: 1 }];
  const merged = upsertRecords(existing, [{ bucket: '2026-W32', harness: 'claude-code', v: 2 }]);
  assert.equal(merged.length, 2);
  assert.equal(merged.find((r) => r.bucket === '2026-W32').v, 2);
});

run('upsertRecords: different harnesses for the SAME bucket are distinct keys', () => {
  const existing = [{ bucket: '2026-W32', harness: 'claude-code', v: 1 }];
  const merged = upsertRecords(existing, [{ bucket: '2026-W32', harness: 'codex-cli', v: 1 }]);
  assert.equal(merged.length, 2);
});

run('recordKey: joins bucket and harness', () => {
  assert.equal(recordKey({ bucket: '2026-W32', harness: 'claude-code' }), '2026-W32|claude-code');
});

// --- file round-trip: idempotent write -------------------------------------

run('writeSnapshotFile + readSnapshotFile: round-trips, and re-running the same upsert keeps the line count stable', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'trend-snap-'));
  const file = path.join(dir, 'snap.ndjson');
  const recordsA = [{ bucket: '2026-W32', harness: 'claude-code', v: 1 }, { bucket: '2026-W33', harness: 'claude-code', v: 1 }];
  writeSnapshotFile(file, recordsA);
  const readBack = readSnapshotFile(file);
  assert.equal(readBack.length, 2);

  // idempotent re-run: same records upserted again -> same total line count
  const merged = upsertRecords(readBack, recordsA);
  writeSnapshotFile(file, merged);
  assert.equal(readSnapshotFile(file).length, 2, 'line count must not grow on a repeat run with identical input');

  fs.rmSync(dir, { recursive: true, force: true });
});

run('readSnapshotFile: a missing file returns an empty array, not a throw', () => {
  assert.deepEqual(readSnapshotFile('/definitely/does/not/exist/snap.ndjson'), []);
});

run('readSnapshotFile: a malformed line is skipped, not a throw', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'trend-snap-'));
  const file = path.join(dir, 'snap.ndjson');
  fs.writeFileSync(file, '{"bucket":"2026-W32","harness":"claude-code"}\nnot json\n');
  const rows = readSnapshotFile(file);
  assert.equal(rows.length, 1);
  fs.rmSync(dir, { recursive: true, force: true });
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`trend-snapshot.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`trend-snapshot.test: ${passed} passed`);
