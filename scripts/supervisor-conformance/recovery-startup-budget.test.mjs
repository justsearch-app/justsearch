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
        env: { ...process.env, JUSTSEARCH_HOME: work, JUSTSEARCH_DATA_DIR: work,
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
