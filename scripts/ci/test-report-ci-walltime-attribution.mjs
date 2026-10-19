#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildWalltimeReport, mergePaginatedJobs, renderMarkdown } from './report-ci-walltime-attribution.mjs';

import * as walltime from './report-ci-walltime-attribution.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-ci-walltime-attribution.mjs');

// Compact fixture matching the real `gh api .../runs/<id>/jobs` shape
// (name/status/conclusion/started_at/completed_at/run_attempt + steps[]).
function job(name, startedAt, completedAt, steps = [], overrides = {}) {
  return {
    name,
    status: 'completed',
    conclusion: 'success',
    run_attempt: 1,
    started_at: startedAt,
    completed_at: completedAt,
    steps,
    ...overrides,
  };
}

function step(name, startedAt, completedAt) {
  return { name, status: 'completed', conclusion: 'success', number: 1, started_at: startedAt, completed_at: completedAt };
}

function sampleJobs() {
  return [
    // 600s wall: 15s checkout (fixed) + 585s work
    job('Unit tests (app-ui)', '2026-07-01T00:00:00Z', '2026-07-01T00:10:00Z', [
      step('Set up job', '2026-07-01T00:00:00Z', '2026-07-01T00:00:05Z'),
      step('Checkout', '2026-07-01T00:00:05Z', '2026-07-01T00:00:15Z'),
      step('Unit tests', '2026-07-01T00:00:15Z', '2026-07-01T00:10:00Z'),
    ]),
    // 300s wall
    job('License and notices', '2026-07-01T00:00:00Z', '2026-07-01T00:05:00Z', [
      step('Checkout', '2026-07-01T00:00:00Z', '2026-07-01T00:00:10Z'),
      step('Enforce the license allowlist', '2026-07-01T00:00:10Z', '2026-07-01T00:05:00Z'),
    ]),
    // in-progress (the attribution job itself) — no completed_at → excluded
    job('CI wall-clock attribution', '2026-07-01T00:10:00Z', null, [], { status: 'in_progress', conclusion: null }),
  ];
}

// mergePaginatedJobs: single object, bare array, concatenated --paginate pages,
// a }{ inside a string value (not a false boundary), and empty input.
{
  assert.deepEqual(
    mergePaginatedJobs('{"total_count":2,"jobs":[{"name":"a"},{"name":"b"}]}').jobs.map((j) => j.name),
    ['a', 'b'],
    'single {jobs} object',
  );
  assert.deepEqual(mergePaginatedJobs('[{"name":"x"}]').jobs.map((j) => j.name), ['x'], 'bare array');
  const twoPages =
    '{"total_count":3,"jobs":[{"name":"p1a"},{"name":"p1b"}]}\n{"total_count":3,"jobs":[{"name":"p2"}]}';
  assert.deepEqual(
    mergePaginatedJobs(twoPages).jobs.map((j) => j.name),
    ['p1a', 'p1b', 'p2'],
    'two concatenated pages merge',
  );
  assert.deepEqual(
    mergePaginatedJobs('{"total_count":1,"jobs":[{"name":"weird }{ name"}]}').jobs.map((j) => j.name),
    ['weird }{ name'],
    '}{ inside a string is not a document boundary',
  );
  assert.deepEqual(mergePaginatedJobs('').jobs, [], 'empty input yields no jobs');
}

// buildWalltimeReport: critical path, fixed-tax split, in-progress exclusion.
{
  const report = buildWalltimeReport({
    run: { runId: '123', runAttempt: 1, repository: 'o/r', workflow: 'CI' },
    jobs: sampleJobs(),
    selfJob: 'CI wall-clock attribution',
  });
  assert.equal(report.kind, 'justsearch-ci-walltime-attribution.v1');
  assert.equal(report.totals.jobs, 2, 'in-progress + self job excluded');
  assert.equal(report.criticalPath.job, 'Unit tests (app-ui)');
  assert.equal(report.criticalPath.wallSeconds, 600);
  assert.equal(report.totals.criticalPathSeconds, 600);
  const appui = report.jobs.find((j) => j.name === 'Unit tests (app-ui)');
  assert.equal(appui.wallSeconds, 600);
  assert.equal(appui.fixedTaxSeconds, 15, 'set up job + checkout are fixed tax');
  assert.equal(appui.workSeconds, 585);
  assert.equal(report.rerunCaveat, false);
  assert.match(renderMarkdown(report), /Critical path: \*\*Unit tests \(app-ui\)\*\*/);
  assert.match(
    renderMarkdown(report),
    /Next lever: to shrink the run, shrink \*\*Unit tests \(app-ui\)\*\* — 9m 45s addressable work vs 15s fixed runner tax\./,
    'directional next-lever line names the pacer with its work vs tax',
  );
}

// Rerun caveat surfaces when run_attempt > 1.
{
  const report = buildWalltimeReport({ run: { runId: '9', runAttempt: 2 }, jobs: sampleJobs(), selfJob: null });
  assert.equal(report.rerunCaveat, true);
  assert.match(renderMarkdown(report), /Rerun \(attempt > 1\)/);
}

// Empty / all-in-progress input yields a null critical path, no throw.
{
  const report = buildWalltimeReport({ run: {}, jobs: [job('x', '2026-07-01T00:00:00Z', null)] });
  assert.equal(report.criticalPath, null);
  assert.equal(report.totals.jobs, 0);
  assert.match(renderMarkdown(report), /Critical path: none/);
}

// suiteTimes splits a unit lane into test CPU vs framework overhead; non-unit jobs
// get no components. app-ui: 600s wall, 585s work step; suite CPU 165s => overhead 420s.
{
  const report = buildWalltimeReport({
    run: { runAttempt: 1 },
    jobs: sampleJobs(),
    selfJob: 'CI wall-clock attribution',
    suiteTimes: { 'app-ui': 165 },
  });
  const appui = report.jobs.find((j) => j.name === 'Unit tests (app-ui)');
  assert.deepEqual(appui.components, {
    testCpuSeconds: 165,
    frameworkOverheadSeconds: 420, // workSeconds 585 - 165
    fixedTaxSeconds: 15,
  });
  const license = report.jobs.find((j) => j.name === 'License and notices');
  assert.equal(license.components, undefined, 'non-unit job has no components');
  const md = renderMarkdown(report);
  assert.match(md, /Unit-lane cost breakdown/);
  assert.match(md, /conservative floor/);
}

// Overhead clamps at 0 when summed suite time exceeds the step wall-clock
// (possible when parallel forks overcount CPU).
{
  const report = buildWalltimeReport({
    run: {},
    jobs: sampleJobs(),
    selfJob: 'CI wall-clock attribution',
    suiteTimes: { 'app-ui': 9999 },
  });
  const appui = report.jobs.find((j) => j.name === 'Unit tests (app-ui)');
  assert.equal(appui.components.frameworkOverheadSeconds, 0, 'overhead never negative');
}

// No suiteTimes → no components, no breakdown section.
{
  const report = buildWalltimeReport({ run: {}, jobs: sampleJobs(), selfJob: 'CI wall-clock attribution' });
  assert.ok(report.jobs.every((j) => j.components === undefined));
  assert.doesNotMatch(renderMarkdown(report), /Unit-lane cost breakdown/);
}

// CLI path: reads --jobs-json {jobs:[...]}, writes JSON + MD.
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-ci-walltime-'));
  try {
    const jobsPath = path.join(root, 'run-jobs.json');
    const outJson = path.join(root, 'out.json');
    const outMd = path.join(root, 'out.md');
    fs.writeFileSync(jobsPath, JSON.stringify({ jobs: sampleJobs() }), 'utf8');
    // Downloaded-artifact layout: <dir>/<artifact-name>/build/ci/unit-test-attribution.json
    const unitDir = path.join(root, 'unit-artifacts');
    const attrPath = path.join(unitDir, 'unit-test-attribution-app-ui', 'build', 'ci', 'unit-test-attribution.json');
    fs.mkdirSync(path.dirname(attrPath), { recursive: true });
    fs.writeFileSync(attrPath, JSON.stringify({ lane: 'app-ui', totals: { timeSeconds: 165 } }), 'utf8');
    const res = spawnSync(process.execPath, [
      scriptPath,
      '--jobs-json', jobsPath,
      '--unit-attribution-dir', unitDir,
      '--run-id', '123',
      '--repository', 'o/r',
      '--workflow', 'CI',
      '--self-job', 'CI wall-clock attribution',
      '--out-json', outJson,
      '--out-md', outMd,
      '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(JSON.parse(res.stdout).criticalPath.job, 'Unit tests (app-ui)');
    const outObj = JSON.parse(fs.readFileSync(outJson, 'utf8'));
    assert.equal(outObj.totals.jobs, 2);
    const appui = outObj.jobs.find((j) => j.name === 'Unit tests (app-ui)');
    assert.equal(appui.components.testCpuSeconds, 165, 'CLI wired the suite-time correlation');
    assert.match(fs.readFileSync(outMd, 'utf8'), /Unit-lane cost breakdown/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

console.log('test-report-ci-walltime-attribution: PASS');

// Completion includes delayed aggregate scheduling; the longest job does not.
{
  const report = buildWalltimeReport({
    requiredChecks: ['Part', 'Aggregate'],
    jobs: [
      job('Part', '2026-07-01T00:00:00Z', '2026-07-01T00:10:00Z'),
      job('Aggregate', '2026-07-01T00:12:00Z', '2026-07-01T00:12:05Z'),
      job('Advisory', '2026-07-01T00:00:00Z', '2026-07-01T01:00:00Z'),
    ],
  });
  assert.equal(report.requiredCompletion.elapsedSeconds, 725);
  assert.equal(report.criticalPath.wallSeconds, 3600);
  assert.equal(report.requiredCompletion.complete, true);
  const missing = buildWalltimeReport({ requiredChecks: ['Missing'], jobs: sampleJobs() });
  assert.equal(missing.requiredCompletion.elapsedSeconds, null);
  assert.deepEqual(missing.requiredCompletion.missingChecks, ['Missing']);
}

// Multiple attempts of one part never overwrite the attempt selected by the job.
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cp-artifacts-'));
  try {
    for (const [attempt, seconds] of [[1, 100], [2, 200]]) {
      const dir = path.join(root, `unit-test-attribution-app-ui-ui-attempt-${attempt}`);
      fs.mkdirSync(dir);
      fs.writeFileSync(path.join(dir, 'unit-test-attribution.json'), JSON.stringify({ lane: 'app-ui-ui', totals: { timeSeconds: seconds } }));
    }
    assert.equal(walltime.readSuiteTimes(root, [{ name: 'Unit tests (app-ui-ui)', run_attempt: 2 }])['app-ui-ui'], 200);
    assert.equal(walltime.readSuiteTimes(root, [{ name: 'Unit tests (app-ui-ui)', run_attempt: 3 }])['app-ui-ui'], undefined);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
