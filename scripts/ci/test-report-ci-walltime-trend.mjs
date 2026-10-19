#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildTrendReport, renderMarkdown } from './report-ci-walltime-trend.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-ci-walltime-trend.mjs');

function policy() {
  return {
    kind: 'justsearch-ci-walltime-policy.v1',
    lanes: [
      { job: 'Unit tests (app-ui)', advisoryBudgets: { maxWallSeconds: 750 } },
      { job: 'Build (no model blobs)', advisoryBudgets: { maxWallSeconds: 595 } },
    ],
  };
}

function run(runId, appui, build) {
  return { runId, createdAt: '2026-07-01T00:00:00Z', jobs: [
    { name: 'Unit tests (app-ui)', wallSeconds: appui },
    { name: 'Build (no model blobs)', wallSeconds: build },
  ] };
}

// Median within ceilings → no sustained drift.
{
  const runs = [run(1, 600, 470), run(2, 610, 480), run(3, 590, 475)];
  const report = buildTrendReport({ runs, policy: policy() });
  assert.equal(report.kind, 'justsearch-ci-walltime-trend.v1');
  assert.equal(report.runCount, 3);
  assert.equal(report.sustainedDrift, false);
  const appui = report.lanes.find((l) => l.job === 'Unit tests (app-ui)');
  assert.equal(appui.medianSeconds, 600);
  assert.equal(appui.overBudget, false);
  assert.match(renderMarkdown(report), /within advisory ceilings/);
}

// Median above ceiling → sustained drift flagged; one low outlier does not rescue it.
{
  const runs = [run(1, 800, 470), run(2, 820, 480), run(3, 600, 475), run(4, 810, 472), run(5, 830, 478)];
  const report = buildTrendReport({ runs, policy: policy() });
  assert.equal(report.sustainedDrift, true);
  assert.deepEqual(report.driftedLanes.map((l) => l.job), ['Unit tests (app-ui)']);
  assert.equal(report.driftedLanes[0].medianSeconds, 810);
  assert.match(renderMarkdown(report), /sustained drift in 1 lane/);
}

// A single slow run does NOT trip drift (median stays under ceiling).
{
  const runs = [run(1, 600, 470), run(2, 900, 480), run(3, 590, 475)];
  const report = buildTrendReport({ runs, policy: policy() });
  assert.equal(report.sustainedDrift, false, 'one slow run must not notify');
}

// CLI path with --runs-json.
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-ci-walltime-trend-'));
  try {
    fs.writeFileSync(path.join(root, 'settings.gradle.kts'), 'rootProject.name = "test"\n', 'utf8');
    const policyPath = path.join(root, 'scripts/ci/ci-walltime-policy.v1.json');
    const runsPath = path.join(root, 'runs.json');
    fs.mkdirSync(path.dirname(policyPath), { recursive: true });
    fs.writeFileSync(policyPath, `${JSON.stringify(policy(), null, 2)}\n`, 'utf8');
    fs.writeFileSync(runsPath, JSON.stringify([run(1, 800, 470), run(2, 810, 480), run(3, 820, 475)]), 'utf8');
    const res = spawnSync(process.execPath, [scriptPath, '--runs-json', runsPath, '--policy', policyPath, '--json'], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(JSON.parse(res.stdout).sustainedDrift, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

console.log('test-report-ci-walltime-trend: PASS');

// Historical work and the new aggregate share a required check name but have
// different meanings. Do not report legacy work as an over-budget aggregate.
{
  const policy = { lanes: [
    { job: 'Unit tests (app-ui)', requiredCheck: 'Unit tests (app-ui)', advisoryBudgets: { maxWallSeconds: 30 } },
    { job: 'Unit tests (app-ui-ui)', requiredCheck: 'Unit tests (app-ui)', advisoryBudgets: { maxWallSeconds: 1200 } },
    { job: 'Unit tests (app-ui-services)', requiredCheck: 'Unit tests (app-ui)', advisoryBudgets: { maxWallSeconds: 1440 } },
  ] };
  const report = buildTrendReport({ policy, runs: [
    { jobs: [{ name: 'Unit tests (app-ui)', wallSeconds: 2200 }] },
    { jobs: [{ name: 'Unit tests (app-ui)', wallSeconds: 2 }, { name: 'Unit tests (app-ui-ui)', wallSeconds: 1000 }, { name: 'Unit tests (app-ui-services)', wallSeconds: 1300 }] },
  ] });
  const aggregate = report.lanes.find((lane) => lane.job === 'Unit tests (app-ui)');
  assert.equal(aggregate.runs, 1);
  assert.equal(aggregate.medianSeconds, 2);
  assert.equal(report.sustainedDrift, false);
}
