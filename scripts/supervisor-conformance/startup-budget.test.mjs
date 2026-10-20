import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { driveCase, watchSupervisor } from './adapters/dev-runner.mjs';
import { setTimeout as sleep } from 'node:timers/promises';
import { FAKE_ENGINE, HARNESS_FLAG, OVERRIDE_ENV, repoRoot, loadPolicy } from './contract.mjs';

const harnessOverrides = {
  [HARNESS_FLAG]: '1',
  [OVERRIDE_ENV.startDeadlineMs]: '4000',
  [OVERRIDE_ENV.gracefulStopDeadlineMs]: '1500',
};
const io = {
  repoRoot, fakeEngine: FAKE_ENGINE, harnessOverrides, sleep,
  readJsonIfPresent(file) {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
  },
  makeWorkDir(label) {
    const root = path.join(repoRoot, 'tmp', 'startup-budget-tests');
    fs.mkdirSync(root, { recursive: true });
    return fs.mkdtempSync(path.join(root, `${label}-`));
  },
  writeEnginePlan(work, incarnations) {
    fs.writeFileSync(path.join(work, 'fake-engine.plan.json'), JSON.stringify({ incarnations }));
  },
};
const policy = loadPolicy({ env: harnessOverrides });

test('delayed fixture reaches its first running incarnation without spending the restart budget', { timeout: 160000 }, async () => {
  const testCase = { id: 'delayed-fixture-start', enginePlan: [
    { mode: 'slow-start', slowStartMs: 11000 }, { mode: 'honour' },
  ] };
  const run = await driveCase({ testCase, policy, io });
  try {
    const state = await watchSupervisor({ statePath: run.statePath,
      predicate: s => s.state === 'running' || s.state === 'exhausted',
      timeoutMs: 140000, io, seen: run.seen });
    assert.ok(state, `first incarnation missed: ${run.output.stderr}`);
    assert.equal(state.incarnation, 1, `startup spent an incarnation: ${run.output.stderr}`);
    assert.equal(state.restartCount, 0);
    assert.doesNotMatch(run.output.stderr, /did not come up|FORCED KILL:/);
  } finally { await run.cleanup(); }
});

test('fixture without a port still reaches forced startup escalation', { timeout: 180000 }, async () => {
  const testCase = { id: 'never-publishes-port',
    enginePlan: [{ mode: 'die-before-manifest', exitAfterMs: 300000 }] };
  const run = await driveCase({ testCase, policy, io });
  try {
    const deadline = performance.now() + 140000;
    while (!/FORCED KILL:/.test(run.output.stderr) && performance.now() < deadline) await sleep(25);
    assert.match(run.output.stderr, /Backend did not emit JUSTSEARCH_API_PORT/);
    assert.match(run.output.stderr, /wrote a shutdown request: reason=hang/);
    assert.match(run.output.stderr, /FORCED KILL:/);
    assert.equal(fs.existsSync(path.join(run.dataDir, 'runtime/manifest.json')), false);
  } finally { await run.cleanup(); }
});
