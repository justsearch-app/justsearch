// Recovery controls drive the production actor and a real fixture Engine in both hosts.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { driveCase } from './adapters/dev-runner.mjs';
import { binaryPath, runBinary } from './adapters/tauri.mjs';

export async function runRecoveryCase({ name, testCase, io }) {
  if (testCase.id === 'recovery-never-healthy-delayed-retry') return runNeverHealthyCase({ name, testCase, io });
  const env = { ...io.harnessOverrides,
    JUSTSEARCH_SUPERVISOR_EXHAUSTED_RETRY_DELAY_MS: testCase.id.includes('manual') && !testCase.id.includes('race') ? '5000' : '1000',
    JUSTSEARCH_SUPERVISOR_START_DEADLINE_MS: '600',
    JUSTSEARCH_SUPERVISOR_HARNESS_FAIL_SPAWN_AT: String(testCase.failSpawnAt ?? 0) };
  const workDir = io.makeWorkDir(`${name}-${testCase.id}`);
  const dataDir = path.join(workDir, 'data');
  fs.mkdirSync(dataDir, { recursive: true });
  const localIo = { ...io, harnessOverrides: env };
  let run;
  if (name === 'dev-runner') run = await driveCase({ testCase, io: localIo, workDir });
  else {
    const planPath = io.writeEnginePlan(workDir, testCase.enginePlan);
    const faultGatePath = path.join(workDir, 'first-incarnation-running.json');
    const process = runBinary({ binary: binaryPath(io.repoRoot), workDir, dataDir, planPath, io,
      env: { ...globalThis.process.env, ...env, JUSTSEARCH_FAKE_ENGINE_FAULT_GATE: faultGatePath }, stopAfterIncarnation: 0, stopOnExhausted: false, failSpawnAt: testCase.failSpawnAt });
    run = { ...process, dataDir, faultGatePath, statePath: path.join(dataDir, 'runtime/supervisor.v1.json'),
      cleanup: async () => {
        if (process.child.exitCode === null) {
          try { await command('quit'); await Promise.race([process.exited, io.sleep(5000)]); } catch { /* diagnostic below */ }
          if (process.child.exitCode === null) process.child.kill();
          await process.exited;
        }
      } };
  }
  let nonce = 0;
  const current = () => io.readJsonIfPresent(run.statePath);
  const waitState = predicate => io.waitFor(() => { const state = current(); return state && predicate(state) ? state : null; }, { timeoutMs: 15000, what: testCase.id });
  const command = async action => {
    const request = { nonce: ++nonce, action };
    const target = path.join(run.dataDir, 'shell-control.json');
    fs.writeFileSync(`${target}.tmp`, JSON.stringify(request)); fs.renameSync(`${target}.tmp`, target);
    return io.waitFor(() => {
      const result = io.readJsonIfPresent(path.join(run.dataDir, 'shell-control-result.json'));
      return result?.nonce === request.nonce ? result.result : null;
    }, { timeoutMs: 5000, what: `${action} reply` });
  };
  try {
    if (testCase.failSpawnAt === 1) {
      const failed = await waitState(state => state.state === 'exhausted');
      assert.match(failed.reason, /missing-java/);
      assert.ok(!failed.retryAt);
      return { problems: [] };
    }
    const first = await waitState(state => state.state === 'running');
    const firstManifest = io.readJsonIfPresent(path.join(run.dataDir, 'runtime/manifest.json'));
    if (run.faultGatePath) fs.writeFileSync(run.faultGatePath, JSON.stringify({ pid: first.pid, instanceId: first.instanceId, incarnation: first.incarnation }));
    if (testCase.failSpawnAt === 2) {
      const failed = await waitState(state => state.state === 'exhausted');
      assert.match(failed.reason, /missing-java/);
      assert.ok(!failed.retryAt);
      return { problems: [] };
    }
    if (testCase.id === 'recovery-index-never-ready') {
      const pending = await waitState(state => !!state.notReady);
      assert.equal(pending.state, 'running');
      assert.equal(pending.incarnation, 1);
      assert.match(pending.notReady, /UNAVAILABLE/);
      assert.equal(pending.restartCount, 0);
      return { problems: [] };
    }
    if (testCase.id === 'recovery-status-failure-and-resume') {
      const second = await waitState(state => state.state === 'running' && state.incarnation === 2);
      const control = value => fs.writeFileSync(path.join(run.dataDir, 'fake-essential-ready.json'), JSON.stringify(value));
      const witnessed = async (name, after) => io.waitFor(() => {
        const report = io.readJsonIfPresent(path.join(run.dataDir, name));
        return report?.pid === second.pid && report.at >= after ? report : null;
      }, { timeoutMs: 5000, what: name });
      let after = Date.now();
      control({ ready: true });
      await witnessed('fake-status-observed.json', after);
      await io.sleep(800);
      assert.equal(current().restartCount, 1);
      after = Date.now();
      control({ ready: true, statusFail: true });
      await witnessed('fake-status-failure.json', after);
      await io.sleep(300);
      after = Date.now();
      control({ ready: true });
      await witnessed('fake-status-observed.json', after);
      await io.sleep(1000);
      assert.equal(current().restartCount, 1, 'a failed status probe interrupts sustained readiness');
      await command('suspend');
      await io.sleep(1000);
      await command('resume');
      await io.sleep(1000);
      assert.equal(current().restartCount, 1, 'resume starts a fresh readiness window');
      await waitState(state => state.state === 'running' && state.restartCount === 0);
      assert.equal(current().incarnation, second.incarnation);
      return { problems: [] };
    }
    if (testCase.id === 'recovery-long-loop-remains-visible') {
      await io.sleep(2000);
      fs.writeFileSync(path.join(run.dataDir, 'fake-exit.json'), JSON.stringify({ incarnation: 1 }));
      await waitState(state => state.state === 'running' && state.incarnation === 2);
      await waitState(state => state.state === 'running' && state.incarnation === 2 && state.restartCount === 0);
      fs.writeFileSync(path.join(run.dataDir, 'fake-exit.json'), JSON.stringify({ incarnation: 2 }));
      const third = await waitState(state => state.state === 'running' && state.incarnation >= 3);
      assert.equal(third.recentExits?.count, 2, 'the 24-hour stop count must survive stable windows');
      assert.equal(third.restartCount, 1, 'each earlier sustained-ready window replenishes the budget');
      return { problems: [] };
    }
    const exhausted = await waitState(state => state.state === 'exhausted');
    assert.equal(exhausted.restartCount, 3);
    assert.ok(Date.parse(exhausted.retryAt) > Date.now(), 'exhaustion publishes the delayed retry');
    assert.equal(exhausted.recentExits?.count, 4, 'exhaustion must retain the 24-hour stop count');
    if (testCase.id === 'recovery-manual-retry-races-deadline-and-quit') {
      await io.sleep(Math.max(0, Date.parse(exhausted.retryAt) - Date.now() - 100));
      const raced = await command('restart-quit-race');
      assert.equal(raced.ok, true);
      assert.equal(raced.quitRefusedRestart, true);
      await io.sleep(1400);
      assert.equal(current().state, 'stopped');
      assert(current().incarnation <= 5, 'the race must admit at most one replacement');
      const pid = current().pid;
      assert(!pid || !io.isAlive(pid), 'quit must leave no Engine alive');
    } else if (testCase.id === 'recovery-manual-restart-and-update-hold') {
      assert.equal((await command('hold')).ok, true);
      const denied = await command('restart');
      assert.equal(denied.ok, false); assert.match(denied.error, /update|held/i);
      await io.sleep(1200);
      assert.equal(current().incarnation, 4);
      assert.equal((await command('release')).ok, true);
      const replies = [];
      for (let i = 0; i < 5; i++) replies.push(await command('restart'));
      assert.equal(replies[0].ok, true);
      const restarted = await waitState(state => state.state === 'running' && state.incarnation === 5);
      assert.equal(restarted.restartCount, 0);
      assert.notEqual(restarted.instanceId, first.instanceId);
      const manifest = io.readJsonIfPresent(path.join(run.dataDir, 'runtime/manifest.json'));
      assert.equal(restarted.apiPort, manifest.head.apiPort);
      assert.equal(restarted.instanceId, manifest.instanceId);
      assert.notEqual(manifest.head.sessionToken, firstManifest.head.sessionToken);
      const status = await (await fetch(`http://127.0.0.1:${restarted.apiPort}/api/status`)).json();
      assert.equal(status.readiness.engineComponents.index.state, 'READY');
      await io.sleep(1200);
      assert.equal(current().incarnation, 5, 'repeated clicks must not leave a deferred restart');
    } else if (testCase.id === 'recovery-quit-during-delayed-retry') {
      await command('quit');
      await io.sleep(1400);
      assert.equal(current().state, 'stopped');
      assert.equal(current().incarnation, 4);
    } else if (testCase.id === 'recovery-update-hold-blocks-delayed-retry') {
      assert.equal((await command('hold')).ok, true);
      await io.sleep(1400);
      assert.equal(current().state, 'exhausted');
      assert.equal(current().incarnation, 4);
      assert.equal((await command('release')).ok, true);
      await waitState(state => state.state === 'running' && state.incarnation === 5);
    } else {
      const restarted = await waitState(state => state.state === 'running' && state.incarnation === 5);
      assert.equal(restarted.restartCount, 0);
      assert.equal(restarted.recentExits.count, 4);
      assert.notEqual(restarted.instanceId, first.instanceId);
    }
    return { problems: [] };
  } catch (error) { return { problems: [`${error.stack}\n${run.output.stderr.slice(-1800)}`] }; }
  finally {
    await run.cleanup();
    fs.rmSync(workDir, { recursive: true, force: true });
  }
}

// This checks the shared supervisor contract, independently of the dev CLI's admission policy.
// Both implementations own a real fixture Engine; no adapter disables the CLI policy.
async function runNeverHealthyCase({ name, testCase, io }) {
  const require = createRequire(import.meta.url);
  const { createSupervisionLoop, loadPolicy } = require('../dev/lib/engine-supervisor.cjs');
  const { createEngineActuator } = require('../dev/lib/engine-actuator.cjs');
  const host = require('../dev/dev-runner.cjs').__test;
  const workDir = io.makeWorkDir(`${name}-${testCase.id}`);
  const dataDir = path.join(workDir, 'data');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  const planPath = io.writeEnginePlan(workDir, testCase.enginePlan);
  const env = { ...process.env, ...io.harnessOverrides, JUSTSEARCH_SUPERVISOR_EXHAUSTED_RETRY_DELAY_MS: '1000' };
  const statePath = path.join(dataDir, 'runtime/supervisor.v1.json');
  const seen = [];
  let run;
  if (name === 'dev-runner') {
    const policy = loadPolicy({ env });
    const actuator = createEngineActuator({ dataDir, runId: 'never-healthy', policy,
      spawnChild: (_n, apiPort, incarnationEnv) => spawn(process.execPath, [io.fakeEngine], {
        env: { ...env, ...incarnationEnv, JUSTSEARCH_DATA_DIR: dataDir,
          JUSTSEARCH_API_PORT: String(apiPort), JUSTSEARCH_FAKE_ENGINE_PLAN: planPath,
          JUSTSEARCH_FAKE_ENGINE_FAULT_GATE: '' },
        stdio: 'ignore',
      }),
    });
    const loop = createSupervisionLoop({ actuator, policy, timeouts: { initialPortMs: 4000, initialReadyMs: 4000 },
      io: {
        readManifest: () => io.readJsonIfPresent(path.join(dataDir, 'runtime/manifest.json')),
        readRawManifest: () => io.readJsonIfPresent(path.join(dataDir, 'runtime/manifest.json')),
        probeHealth: (port, ms) => host.checkHttp200(`http://127.0.0.1:${port}/api/health`, ms, true),
        probeAlive: (port, ms) => host.checkHttp200(`http://127.0.0.1:${port}/api/health`, ms, true),
        fetchStatus: (port, ms) => host.fetchJsonHttp(`http://127.0.0.1:${port}/api/status`, ms),
        essentialReadyEpoch: host.essentialReadyEpoch,
        waitForRelease: ({ pid }) => host.waitForEngineHandleRelease({ pid, dataDir }),
        clearStaleDiscoveryFiles() {},
      },
      publish: async state => { seen.push(state); await host.writeSupervisorState(dataDir, state); },
    });
    const exited = loop.run();
    run = { cleanup: async () => { await loop.hostQuit(); await exited; }, output: { stderr: '' } };
  } else {
    run = runBinary({ binary: binaryPath(io.repoRoot), workDir, dataDir, planPath, io, env,
      stopAfterIncarnation: 0, stopOnExhausted: false });
    run.cleanup = async () => {
      if (run.child.exitCode === null) {
        fs.writeFileSync(path.join(dataDir, 'shell-control.json'), JSON.stringify({ action: 'quit', nonce: 1 }));
      }
      await run.exited;
    };
  }
  const waitState = predicate => io.waitFor(() => {
    const state = io.readJsonIfPresent(statePath);
    if (state) seen.push(state);
    return state && predicate(state) ? state : null;
  }, { timeoutMs: 15000, intervalMs: 10, what: `${name}: never-healthy delayed retry` });
  try {
    const exhausted = await waitState(state => state.state === 'exhausted');
    assert.equal(exhausted.incarnation, 4);
    assert.equal(exhausted.restartCount, 3);
    assert.ok(exhausted.retryAt, 'default supervision must retry even without a healthy incarnation');
    const successor = await waitState(state => state.state === 'starting' && state.incarnation === 5);
    assert.equal(successor.restartCount, 0, 'the delayed retry must refill the budget');
    await waitState(state => state.state === 'exhausted' && state.incarnation === 8);
    assert(seen.every(state => state.state !== 'running'), 'no incarnation became healthy');
    assert.equal(fs.existsSync(path.join(dataDir, 'runtime/manifest.json')), false);
    assert.equal(io.readJsonIfPresent(`${planPath}.state.json`).next, 8);
    return { problems: [] };
  } catch (error) {
    return { problems: [`${error.stack}\n${run.output.stderr.slice(-1800)}`] };
  } finally {
    await run.cleanup();
    fs.rmSync(workDir, { recursive: true, force: true });
  }
}
