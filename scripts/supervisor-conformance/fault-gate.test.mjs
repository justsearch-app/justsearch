import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import test from 'node:test';

const fakeEngine = fileURLToPath(new URL('./fake-engine.mjs', import.meta.url));

async function waitFor(read, what) {
  const deadline = performance.now() + 5000;
  while (performance.now() < deadline) {
    const value = read();
    if (value) return value;
    await sleep(10);
  }
  throw new Error(`timed out waiting for ${what}`);
}

async function fixture({ mode, code, gated = true, incarnation = 1 }, check) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'fault-gate-'));
  const gate = path.join(work, 'running.json');
  const plan = path.join(work, 'plan.json');
  fs.writeFileSync(plan, JSON.stringify({ incarnations: [{ mode, exitCode: code, exitAfterMs: 200 }] }));
  if (incarnation > 1) fs.writeFileSync(`${plan}.state.json`, JSON.stringify({ next: incarnation - 1 }));
  const child = spawn(process.execPath, [fakeEngine], {
    env: { ...process.env, JUSTSEARCH_DATA_DIR: work, JUSTSEARCH_API_PORT: '0',
      JUSTSEARCH_FAKE_ENGINE_PLAN: plan, JUSTSEARCH_FAKE_ENGINE_FAULT_GATE: gated ? gate : '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (b) => { output += b; });
  child.stderr.on('data', (b) => { output += b; });
  const exited = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (exitCode) => resolve(exitCode));
  });
  try {
    const manifest = gated && incarnation === 1 ? await waitFor(() => {
      try { return JSON.parse(fs.readFileSync(path.join(work, 'runtime', 'manifest.json'), 'utf8')); }
      catch { return null; }
    }, 'manifest') : null;
    await check({ child, gate, manifest, exited, output: () => output });
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill();
    await exited;
    fs.rmSync(work, { recursive: true, force: true, maxRetries: 5 });
  }
}

for (const [mode, code] of [['crash', 1], ['oom', 3], ['clean-exit', 4]]) {
  test(`${mode}: first fault waits for an observed running identity`, async () => {
    await fixture({ mode, code }, async ({ child, gate, manifest, exited, output }) => {
      const identity = { pid: manifest.pid, instanceId: manifest.instanceId, incarnation: 1 };
      // Simulate slow Windows startup, then stale acknowledgments. Each wait exceeds exitAfterMs.
      await sleep(350);
      assert.equal(child.exitCode, null, `fault fired before the adapter observed running: ${output()}`);
      for (const wrong of [{ pid: manifest.pid + 1 }, { instanceId: 'stale' }, { incarnation: 2 }]) {
        fs.writeFileSync(gate, JSON.stringify({ ...identity, ...wrong }));
        await sleep(250);
        assert.equal(child.exitCode, null, 'an unrelated acknowledgment released the fault');
      }
      fs.writeFileSync(gate, JSON.stringify(identity));
      await waitFor(() => child.exitCode !== null, 'the released fault');
      assert.equal(await exited, code);
      assert.match(output(), /fault gate released after adapter observed running/);
      assert.equal(fs.existsSync(path.join(path.dirname(gate), 'runtime', 'shutdown-request.v1.json')), false);
    });
  });
}

for (const options of [{ gated: false, incarnation: 1 }, { gated: true, incarnation: 2 }]) {
  test(`original timing remains for gated=${options.gated}, incarnation=${options.incarnation}`, async () => {
    await fixture({ mode: 'crash', code: 1, ...options }, async ({ child, exited, output }) => {
      await waitFor(() => child.exitCode !== null, 'an ungated exit');
      assert.equal(await exited, 1);
      assert.doesNotMatch(output(), /fault gate released/);
    });
  });
}
