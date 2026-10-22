import '../dev/lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../../', import.meta.url));

for (const id of ['recovery-delayed-retry', 'recovery-manual-restart-and-update-hold']) {
  test(`${id}: a slow third root publication reaches recovery instead of failing spawn`, () => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'recovery-startup-budget-'));
    const receipt = path.join(work, 'root-publication.json');
    const preload = path.join(work, 'slow-root.cjs');
    // Model the Windows job host's startup cost at the real actuator boundary. This is
    // deliberately longer than the former 600ms override, within the shared fixture budget.
    fs.writeFileSync(preload, `
const fs = require('node:fs');
const supervisor = require(${JSON.stringify(path.join(repo, 'scripts/dev/lib/engine-supervisor.cjs'))});
const create = supervisor.createSupervisionLoop;
supervisor.createSupervisionLoop = options => {
  const spawn = options.actuator.spawnEngine.bind(options.actuator);
  options.actuator.spawnEngine = request => {
    if (request.n === 3) {
      const started = performance.now();
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 900);
      const elapsed = performance.now() - started;
      fs.writeFileSync(process.env.JUSTSEARCH_RECOVERY_STARTUP_RECEIPT,
        JSON.stringify({ n: request.n, budget: request.startupTimeoutMs, elapsed }));
      if (elapsed >= request.startupTimeoutMs) throw new Error('controlled root publication exceeded startup budget');
    }
    return spawn(request);
  };
  return create(options);
};
`);
    try {
      const result = spawnSync(process.execPath, [
        path.join(repo, 'scripts/supervisor-conformance/run.mjs'), '--adapter', 'dev-runner', '--case', id,
      ], {
        cwd: repo, encoding: 'utf8', timeout: 60000,
        env: { ...process.env, JUSTSEARCH_PR_CI: 'false', JUSTSEARCH_HOME: work, JUSTSEARCH_DATA_DIR: work,
          JUSTSEARCH_RECOVERY_STARTUP_RECEIPT: receipt,
          NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${JSON.stringify(preload)}` },
      });
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}\n${result.error ?? ''}`);
      const observed = JSON.parse(fs.readFileSync(receipt, 'utf8'));
      assert.equal(observed.n, 3);
      assert(observed.elapsed >= 900, 'the controlled startup delay was exercised');
      assert(observed.budget > observed.elapsed, 'replacement startup has room for fixture root publication');
      assert.match(result.stdout, /1\/1 checks passed/);
    } finally {
      fs.rmSync(work, { recursive: true, force: true });
    }
  });
}

for (const delay of [0, 16000]) {
  test(delay ? 'recovery race waits for slow first fixture boot before exercising E5'
    : 'recovery race retains its timing witness until fixture cleanup', () => {
    const id = 'recovery-manual-retry-races-deadline-and-quit';
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'recovery-first-boot-'));
    const receipt = path.join(work, 'first-spawn.json');
    const preload = path.join(work, 'slow-first-spawn.cjs');
    // The parent still polls normally while its child models synchronous Windows job-host setup.
    // 16s exceeds the former recovery observer's 15s, within the adapter's 60s initial spawn limit.
    fs.writeFileSync(preload, `
const fs = require('node:fs');
const supervisor = require(${JSON.stringify(path.join(repo, 'scripts/dev/lib/engine-supervisor.cjs'))});
const create = supervisor.createSupervisionLoop;
supervisor.createSupervisionLoop = options => {
  const spawn = options.actuator.spawnEngine.bind(options.actuator);
  options.actuator.spawnEngine = request => {
    if (request.n === 1) {
      const started = performance.now();
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ${delay});
      fs.writeFileSync(process.env.JUSTSEARCH_RECOVERY_STARTUP_RECEIPT,
        JSON.stringify({ n: request.n, budget: request.startupTimeoutMs, elapsed: performance.now() - started }));
    }
    return spawn(request);
  };
  return create(options);
};
`);
    const started = Date.now();
    try {
      const result = spawnSync(process.execPath, [path.join(repo, 'scripts/supervisor-conformance/run.mjs'),
        '--adapter', 'dev-runner', '--case', id], {
        cwd: repo, encoding: 'utf8', timeout: 90000,
        env: { ...process.env, JUSTSEARCH_PR_CI: 'false', JUSTSEARCH_HOME: work, JUSTSEARCH_DATA_DIR: work,
          JUSTSEARCH_RECOVERY_STARTUP_RECEIPT: receipt,
          NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${JSON.stringify(preload)}` },
      });
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}\n${result.error ?? ''}`);
      const observed = JSON.parse(fs.readFileSync(receipt, 'utf8'));
      assert.equal(observed.n, 1);
      assert(observed.elapsed >= delay, 'the controlled first-spawn delay was exercised');
      assert(observed.budget > observed.elapsed, 'first spawn remains within its existing fixture budget');
      const records = path.join(repo, 'tmp/supervisor-conformance/records');
      const matching = fs.readdirSync(records).filter(name => name.startsWith(`dev-runner-${id}-`))
        .map(name => JSON.parse(fs.readFileSync(path.join(records, name), 'utf8')))
        .filter(record => record.startedAtMs >= started);
      assert.equal(matching.length, 1, 'the recovery case must publish its timing witness before cleanup');
      const record = matching[0];
      assert.deepEqual(record.problems, []);
      assert(record.events.some(event => event.phase === 'ready'), 'first readiness is witnessed');
      assert(record.events.some(event => event.phase === 'stoppedPublished'), 'quit completion is witnessed');
      assert(record.waits[0].budgetMs > 16000, 'initial observation includes the existing fixture startup allowance');
      assert(record.waits.slice(1).every(wait => wait.budgetMs <= 15000), 'recovery and control deadlines stay bounded');
      assert.doesNotMatch(result.stderr, /timing warning/);
    } finally {
      fs.rmSync(work, { recursive: true, force: true });
    }
  });
}
