import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { runBinary } from './adapters/tauri.mjs';
import { engineRecords, watchSupervisor } from './adapters/dev-runner.mjs';
import { enginePlanFor } from './contract.mjs';

const alive = (pid) => {
  try { process.kill(pid, 0); return true; } catch (err) {
    if (err.code === 'ESRCH') return false;
    throw err;
  }
};

export async function runUpdaterHold({ testCase, io }) {
  const workDir = io.makeWorkDir(`tauri-${testCase.id}`);
  const dataDir = path.join(workDir, 'data');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  const run = runBinary({
    binary: path.join(io.repoRoot, 'modules/shell/src-tauri/target/debug/supervisor-conformance.exe'),
    workDir, dataDir, planPath: io.writeEnginePlan(workDir, enginePlanFor(testCase)), io,
    env: { ...process.env, ...io.harnessOverrides }, stopAfterIncarnation: 0,
  });
  const statePath = path.join(dataDir, 'runtime/supervisor.v1.json');
  const records = () => engineRecords(io, dataDir);
  let enginePid;
  const waitState = (predicate, timeoutMs = 5000) => watchSupervisor({
    statePath, predicate, io, seen: new Map(), timeoutMs,
  });
  async function control(action) {
    const nonce = crypto.randomUUID();
    const request = { action, nonce, reason: 'quit' };
    const tmp = path.join(dataDir, 'shell-control.tmp');
    fs.writeFileSync(tmp, JSON.stringify(request));
    fs.renameSync(tmp, path.join(dataDir, 'shell-control.json'));
    let result;
    // Missing controls are an unavailable probe, never an assertion witness.
    try {
      result = await io.waitFor(() => {
        const receipt = io.readJsonIfPresent(path.join(dataDir, 'shell-control-result.json'));
        return receipt?.nonce === nonce && receipt.result;
      }, { what: `${action} conformance control receipt`, timeoutMs: 4000, intervalMs: 10 });
    } catch (err) {
      throw new Error(`UNVERIFIED: binary has no usable ${action} control; ${err.message}`);
    }
    return result;
  }
  try {
    const first = await waitState(s => s.state === 'running');
    assert(first, 'positive control: Engine reaches running');
    enginePid = first.pid;
    assert(alive(enginePid), 'positive control: Engine is alive');
    const probe = testCase.probe;
    if (probe === 'updater-nonempty') {
      const result = await control(probe);
      assert(result.helperAlive, 'positive control: injected nonempty job still contains a live process');
      assert.equal(result.launches, 0, 'installer launched while the job still held a process');
      assert.equal(result.emptyChecks, 2, 'wait for job empty before and after terminate confirmation');
      assert.equal(result.terminates, 1, 'terminate after the first job-empty timeout');
      assert.equal(result.resumes, 1, 'attempt supervision resume after failed confirmation');
      assert.equal(result.intent?.phase, 'REPAIR_REQUIRED');
      assert.match(result.error, /helpers did not exit: 1 processes/);
      assert(result.stagedExists, 'failed containment keeps the update staged');
      return { problems: [] };
    }
    let actorThread;
    if (probe !== 'updater-quit') {
      const held = await control('hold');
      assert(held.ok && held.held, 'replacement hold is acquired');
      actorThread = held.actorThread;
      if (probe === 'updater-hold') {
        assert(held.elapsedMs < testCase.expect.holdWithinMs, 'hold returns at once');
        assert(held.actorRunning && held.continues, 'hold keeps the same actor supervising');
        assert.equal(held.pid, enginePid, 'hold leaves the live Engine in place');
      }
    }
    if (probe === 'updater-hold') {
      const stopped = await control('quit-upgrade');
      assert(stopped.quitAccepted, 'held Engine still answers the actor quit');
      assert(await waitState(s => s.state === 'stopped'), 'held actor publishes terminal quit');
    } else if (probe === 'updater-upgrade') {
      const result = await control('upgrade');
      assert(result.ok, 'upgrade stop goes through the actor');
      const rows = records();
      const intentIndex = rows.findIndex(r => r.type === 'engine-intent' && r.reason === 'UPGRADE');
      const exitIndex = rows.findIndex(r => r.type === 'engine-exit');
      assert(intentIndex >= 0 && exitIndex > intentIndex, 'actor records UPGRADE intent before exit and handoff');
      const exit = rows[exitIndex];
      assert.equal(exit.reason, 'UPGRADE');
      assert.equal(exit.exitClass, 'REQUESTED');
      assert.equal(exit.contract.action, 'stop');
      assert.equal(rows.filter(r => r.type === 'engine-exit').length, 1, 'one actor exit record');
      assert(result.jobEmpty && result.pid === null, 'job is empty before upgrade handoff returns');
      assert(!alive(enginePid), 'Engine is gone before handoff');
      assert(await waitState(s => s.state === 'stopped'), 'upgrade publishes stopped');
      const code = await run.exited;
      assert.equal(code, 0);
      const summary = run.output.stdout.trim().split('\n').map(line => {
        try { return JSON.parse(line); } catch { return null; }
      }).filter(Boolean).at(-1);
      assert.equal(summary.outcome.reason, 'upgrade', 'terminal outcome is stopped(upgrade)');
    } else if (probe === 'updater-held-exit' || probe === 'updater-release') {
      process.kill(enginePid);
      await io.sleep(700); // More than the harness's normal cooldown.
      const exit = records().find(r => r.type === 'engine-exit');
      assert(exit, 'held exit must be recorded');
      assert.equal(exit.exitClass, 'UNEXPECTED', 'a crash while held stays unexpected');
      assert.equal(records().filter(r => r.type === 'engine-start').length, 1, 'hold admits no successor');
      if (probe === 'updater-release') {
        // This drives finish_staged_update's real failed-prepare/release branch with a live Engine.
        await control('release');
        const second = await waitState(s => s.state === 'running' && s.incarnation === 2);
        assert(second, 'release resumes normal restart through the same actor');
        const failed = await control('updater-prepare-failure');
        assert.match(failed.error, /injected prepare failure/);
        assert.equal(failed.resumes, 1, 'failed prepare releases once');
        assert(failed.actorRunning && !failed.held, 'failed prepare keeps the original actor supervising');
        assert(actorThread, 'the original supervisor thread is observed');
        assert.equal(failed.actorThread, actorThread, 'release and failed prepare keep the same actor thread');
        assert.equal(io.readJsonIfPresent(statePath).incarnation, 2, 'failed prepare does not replace the Engine');
      }
      const quit = await control('quit-upgrade');
      assert(quit.quitAccepted, 'the held actor remains available');
      assert(await waitState(s => s.state === 'stopped'), 'actor finishes quit');
    } else if (probe === 'updater-quit') {
      const result = await control(probe);
      assert.equal(result.launches, 0, 'quit while held refuses installer launch');
      assert(result.upgradeRefused, 'quit wins and refuses upgrade');
      assert(result.commitRefused, 'quit refuses an updater commit ticket');
      assert.equal(result.state, 'staged', 'quit leaves update staged');
      assert(result.stagedExists, 'staged artifact survives quit');
      assert(await waitState(s => s.state === 'stopped'), 'quit while held publishes stopped');
      const exit = records().find(r => r.type === 'engine-exit');
      assert.equal(exit?.reason, 'QUIT');
      assert.equal(exit?.exitClass, 'REQUESTED');
      assert(!alive(enginePid), 'nothing remains running');
    } else {
      throw new Error(`unknown updater probe ${probe}`);
    }
    return { problems: [] };
  } finally {
    if (run.child.exitCode === null && run.child.signalCode === null) {
      fs.writeFileSync(path.join(dataDir, 'shell-control.json'), JSON.stringify({ reason: 'quit' }));
      await Promise.race([run.exited, io.sleep(2500)]);
      if (run.child.exitCode === null && run.child.signalCode === null) run.child.kill();
      await run.exited;
    }
    // Exact fixture pids only; older binaries have no supervisor kill-on-close job.
    const pids = new Set([enginePid, io.readJsonIfPresent(statePath)?.pid]);
    for (const pid of pids) if (Number.isInteger(pid) && alive(pid)) process.kill(pid);
    if (process.env.SUPERVISOR_CONFORMANCE_KEEP !== '1') {
      // A cleanup handle race must not replace the scenario's original failure.
      try { fs.rmSync(workDir, { recursive: true, force: true, maxRetries: 5 }); }
      catch (err) { process.stderr.write(`fixture cleanup retained ${workDir}: ${err.code}\n`); }
    }
  }
}
