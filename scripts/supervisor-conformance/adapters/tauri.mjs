import { runLifecycle } from '../lifecycle.mjs';
import { engineRecords } from './dev-runner.mjs';
import { signalLinuxProcess } from '../../dev/lib/process-identity.cjs';
import { runUpdaterHold } from '../updater-hold.mjs';
/**
 * Conformance adapter: the production supervisor (the Tauri shell), lane F stage B item B10.
 *
 * It drives `modules/shell/src-tauri`'s `supervisor-conformance` binary, which runs the SAME
 * `run_supervision` loop `lib.rs` runs, over the same `decide` the register's decision table pins â€”
 * against a real child with a controlled fake workload. B17 binds the driver to the production
 * EngineHost child ownership, manifest admission, exit, state publication and handoff methods.
 * This proves those lifecycle bindings; AppHandle setup and graphical events remain outside
 * this executable proof.
 *
 * The binary is built by the CI step that precedes this one (`cargo build --bin
 * supervisor-conformance --locked`); locally, run that first. If it is missing this adapter reports
 * itself UNAVAILABLE with the command â€” it does not build on demand, because a harness that
 * silently compiles a several-minute dependency graph in the middle of a case list is a harness
 * whose timings mean nothing.
 */

import fs from 'node:fs';
import assert from 'node:assert/strict';
import path from 'node:path';
import { spawn } from 'node:child_process';

import { enginePlanFor, engineRow } from '../contract.mjs';
import { proveEssentialStability } from '../essential-stability.mjs';

export const name = 'tauri';
export const phases = Object.freeze(['start','discovery','quit','kill']);

const CRATE = ['modules', 'shell', 'src-tauri'];

export function binaryPath(repoRoot) {
  const suffix = process.platform === 'win32' ? '.exe' : '';
  for (const profile of ['debug', 'release']) {
    const candidate = path.join(repoRoot, ...CRATE, 'target', profile, `supervisor-conformance${suffix}`);
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

export async function available({ io }) {
  const binary = binaryPath(io.repoRoot);
  if (binary) return { ok: true };
  return {
    ok: false,
    reason:
      'the supervisor-conformance binary is not built. Run, in modules/shell/src-tauri:\n'
      + '          cargo build --bin supervisor-conformance --locked',
  };
}

const RESTART_CASES = new Set([
  'local-handoff-bounds-responsive-close-with-churn',
  'completed-local-handoff-still-bounds-native-exit',
  'local-handoff-fatal-exit-remains-counted',
  'fileless-clean-local-restart-is-not-counted',
  'crash-1-restarts-under-budget',
  'oom-3-restarts-under-budget',
  'escalated-restart',
  'hang-soft-recovered-through-the-request-file',
  'hang-hard-recovered-by-forced-kill',
  'requested-restart-is-not-counted',
  'requested-restart-completion-failure-is-counted',
]);

export function runBinary({ binary, workDir, dataDir, planPath, io, env, stopAfterIncarnation, requestReason }) {
  const child = spawn(
    binary,
    [
      '--data-dir', dataDir,
      '--fake-engine', io.fakeEngine,
      '--plan', planPath,
      '--node', process.execPath,
      '--run-ms', '25000',
      '--request-reason', requestReason ?? '',
      // A restart case is answered the moment a second incarnation is running; a terminal case has
      // to be allowed to REACH its terminal state, so it gets no early stop at all. Passing this in
      // rather than baking it into the binary is what stops the budget-exhaustion case being cut
      // short at incarnation 2 and reported as `cancelled`.
      '--stop-after-incarnation', String(stopAfterIncarnation),
    ],
    { cwd: workDir, env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const output = { stdout: '', stderr: '' };
  child.stdout.on('data', (b) => { output.stdout += b.toString(); });
  child.stderr.on('data', (b) => { output.stderr += b.toString(); });
  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  child.on('error', (err) => { output.stderr += `[spawn error] ${err.message}\n`; });
  return { child, output, exited };
}

export async function runCase({ testCase, policy, io }) {
  if (String(testCase.probe).startsWith('updater-')) return runUpdaterHold({ testCase, policy, io });
  if (testCase.probe || (name === "tauri" && testCase.expect?.ledger)) return runLifecycle({ name, testCase, policy, io });
  const problems = [];
  const binary = binaryPath(io.repoRoot);
  if (!binary) return { problems: ['the supervisor-conformance binary vanished between checks'] };

  const workDir = io.makeWorkDir(`tauri-${testCase.id}`);
  const dataDir = path.join(workDir, 'data');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  const planPath = io.writeEnginePlan(workDir, enginePlanFor(testCase));

  const run = runBinary({
    binary,
    workDir,
    dataDir,
    planPath,
    io,
    env: { ...process.env, ...io.harnessOverrides },
    stopAfterIncarnation: RESTART_CASES.has(testCase.id) ? 2 : 0,
    requestReason: testCase.request?.reason,
  });

  try {
    if (testCase.id === 'engine-exit-ends-its-helpers'
        || testCase.id === 'supervisor-killed-ends-engine-and-helpers') {
      const helper = await io.waitFor(() => io.readJsonIfPresent(path.join(workDir, 'grandchild-1.json')),
        { what: 'detached helper pid', timeoutMs: 10000 });
      const alive = io.isAlive;
      try {
        assert.equal(alive(helper.pid), true, 'positive control: helper ran before the fault');
        assert.equal(alive(helper.parentPid), true, 'positive control: Engine ran before the fault');
        if (testCase.id === 'supervisor-killed-ends-engine-and-helpers') {
          run.child.kill(); // TerminateProcess on Windows: no cleanup code gets to run.
          await run.exited;
        } else {
          await io.waitFor(() => !alive(helper.parentPid),
            { what: 'Engine exit', timeoutMs: 10000, intervalMs: 10 });
        }
        const started = Date.now();
        while ((alive(helper.pid) || alive(helper.parentPid))
            && Date.now() - started < testCase.expect.helperGoneWithinMs) {
          await io.sleep(10);
        }
        assert.equal(alive(helper.pid), false,
          `helper survives ${testCase.expect.helperGoneWithinMs} ms after the fault`);
        assert.equal(alive(helper.parentPid), false, 'Engine survives supervisor death');
        return { problems };
      } finally {
        // Stop the supervisor before cleanup so the negative control cannot spawn another Engine.
        // The before-state has no job: its restarted successor must be cleaned up explicitly too.
        try { run.child.kill(); } catch { /* already gone */ }
        await Promise.race([run.exited, io.sleep(2000)]);
        const manifest = io.readJsonIfPresent(path.join(dataDir, 'runtime', 'manifest.json'));
        const state = io.readJsonIfPresent(path.join(dataDir, 'runtime', 'supervisor.v1.json'));
        // These pids came only from this run's fixture/state; no tree or port sweep.
        if (process.platform === 'linux') {
          const owned = [helper, ...engineRecords(io, dataDir).filter(row => row.type === 'engine-start')];
          for (const record of owned) {
            if (!alive(record.pid)) continue;
            const result = signalLinuxProcess(record.processIdentity, 'SIGKILL');
            assert(result.ok || result.outcome === 'mismatch',
              `fixture identity-bound stop failed: ${result.reason}`);
          }
        } else {
          const owned = new Set([helper.pid, helper.parentPid, manifest?.pid, manifest?.head?.pid, state?.pid]);
          for (const pid of owned) {
            if (Number.isInteger(pid) && pid > 0 && alive(pid)) {
              try { process.kill(pid); } catch { /* exited concurrently */ }
            }
          }
        }
      }
    }
    if (testCase.id === 'live-503-and-continuous-essential-stability') {
      problems.push(...await proveEssentialStability({
        dataDir, statePath: path.join(dataDir, 'runtime', 'supervisor.v1.json'), policy, io,
      }));
      // Finish the real loop through its ordinary quit request so its owned child is reaped.
      const manifest = io.readJsonIfPresent(path.join(dataDir, 'runtime', 'manifest.json'));
      await fetch(`http://127.0.0.1:${manifest.head.apiPort}/api/lifecycle/shutdown`, { method: 'POST', headers: manifest.head.sessionToken ? { 'X-JustSearch-Session': manifest.head.sessionToken } : {} });
      await Promise.race([run.exited, io.sleep(5000)]);
      return { problems };
    }
    const code = await Promise.race([run.exited, io.sleep(45_000).then(() => 'TIMEOUT')]);
    if (code === 'TIMEOUT') {
      return { problems: [`the conformance binary never finished.\n        stderr: ${run.output.stderr.slice(-1200)}`] };
    }
    if (code !== 0) {
      problems.push(`the conformance binary exited ${code}: ${run.output.stderr.slice(-1200)}`);
    }

    let summary = null;
    for (const line of run.output.stdout.trim().split('\n')) {
      try {
        summary = JSON.parse(line);
      } catch { /* not the summary line */ }
    }
    if (!summary?.ok) {
      return {
        problems: [
          `no summary from the conformance binary.\n        stdout: ${run.output.stdout.slice(-800)}`
          + `\n        stderr: ${run.output.stderr.slice(-800)}`,
        ],
      };
    }
    if (summary.policyProfile !== 'harness') {
      problems.push(`the binary ran on the PRODUCT policy (${summary.policyProfile}); the overrides did not reach it`);
    }

    // The state file is the contract's other half: one shape, two writers. Asserting on it here and
    // on the same fields in the dev-runner adapter is what makes "one file" a checked claim.
    const state = io.readJsonIfPresent(path.join(dataDir, 'runtime', 'supervisor.v1.json'));
    if (!state) {
      problems.push('no supervisor.v1.json was written â€” the state a dead Engine cannot report is missing');
    } else {
      if (state.kind !== 'engine-supervisor-state.v1') problems.push(`state kind ${state.kind}`);
      if (state.supervisor !== 'tauri') problems.push(`state supervisor ${state.supervisor}`);
      if (state.schemaVersion !== 1) problems.push(`state schemaVersion ${state.schemaVersion}`);
    }

    if (summary.forcedKill) {
      const code = engineRow().exitCodes.find((row) => row.name === 'SUPERVISOR_FORCED').code;
      if (process.platform === 'linux') {
        // SIGKILL has no Windows program exit code. The owner ledger records the
        // actual signal and the successful force following a cooperative intent.
        assert.equal(summary.lastExit?.code, -9, 'forced shell stop observes SIGKILL');
        assert.equal((summary.checkpoint ?? state)?.lastExit?.code, -9,
          'the disk state must retain the forced signal observation');
        const exit = engineRecords(io, dataDir).find(row => row.type === 'engine-exit' && row.forced);
        assert(exit, 'forced shell stop must have a durable exit receipt');
        assert.equal(exit.code, null);
        assert.equal(exit.signal, 9);
        assert.equal(exit.codeDistinctive, false);
        assert.equal(exit.ownerIntentBeforeSignal, true);
        assert.equal(exit.exitClass, 'REQUESTED');
      } else {
        assert.equal(summary.lastExit?.code, code, 'forced shell stop must record SUPERVISOR_FORCED');
        assert.equal((summary.checkpoint ?? state)?.lastExit?.code, code, 'the disk state must retain the distinctive forced code');
      }
    }

    if (RESTART_CASES.has(testCase.id)) {
      const checkpoint = summary.checkpoint;
      if (checkpoint) { summary.state = checkpoint.state; summary.restartCount = checkpoint.restartCount; }
      if (summary.incarnation < 2) {
        problems.push(`no second incarnation (incarnation ${summary.incarnation}, state ${summary.state})`);
      } else if (summary.state !== 'running') {
        problems.push(`second incarnation reached ${summary.state}, not running`);
      }
      const counted = testCase.expect.counted !== false;
      if (counted && summary.restartCount !== 1) {
        problems.push(`restartCount ${summary.restartCount}, expected 1 (this death is charged)`);
      }
      if (!counted && summary.restartCount !== 0) {
        problems.push(
          `restartCount ${summary.restartCount}, expected 0 â€” a REQUESTED restart must not spend the crash budget`,
        );
      }
      if (testCase.id === 'fileless-clean-local-restart-is-not-counted'
          || testCase.id === 'requested-restart-is-not-counted'
          || testCase.id === 'requested-restart-completion-failure-is-counted') {
        const expectedReason = counted ? 'fatal_or_uncaught'
          : testCase.request ? 'restart' : 'requested_restart';
        const expectedClass = counted ? 'TRANSIENT' : 'REQUESTED';
        if (summary.lastExit?.code !== testCase.observation.exitCode
            || summary.lastExit?.reason !== expectedReason
            || summary.lastExit?.class !== expectedClass
            || summary.lastExit?.counted !== counted) {
          problems.push(`incorrect restart exit record: ${JSON.stringify(summary.lastExit)}`);
        }
        if (!testCase.request && fs.existsSync(path.join(dataDir, 'runtime', 'shutdown-request.v1.json'))) {
          problems.push('fileless restart left a shutdown request');
        }
      }
      if (['local-handoff-bounds-responsive-close-with-churn',
        'completed-local-handoff-still-bounds-native-exit'].includes(testCase.id)) {
        if (!(summary.forcedKill === true) || summary.lastExit?.reason !== 'hang'
            || summary.lastExit?.class !== 'TRANSIENT' || !summary.lastExit?.counted) {
          problems.push('responsive local close was not force-killed and charged as a hang');
        }
      }
      if (testCase.id === 'local-handoff-fatal-exit-remains-counted'
          && ((summary.forcedKill === true) || summary.lastExit?.reason !== 'fatal_or_uncaught')) {
        problems.push('local fatal exit was misclassified or force-killed before its own completion');
      }
      if (testCase.id === 'oom-3-restarts-under-budget' && summary.lastExit?.reason !== 'out_of_memory') {
        problems.push(`lastExit.reason ${summary.lastExit?.reason}, expected out_of_memory`);
      }
      if (testCase.id === 'escalated-restart'
          && (summary.lastExit?.code !== 5
            || summary.lastExit?.reason !== 'escalated_restart'
            || summary.lastExit?.class !== 'TRANSIENT'
            || summary.lastExit?.counted !== true)) {
        problems.push(`escalated restart did not spend the host budget: ${JSON.stringify(summary.lastExit)}`);
      }
      if (testCase.id === 'hang-hard-recovered-by-forced-kill' && summary.forcedKill !== true) {
        problems.push(
          'the hang-hard incarnation was not force-killed â€” the request file alone ended it, so the'
          + ' forced branch was never exercised',
        );
      }
      if (testCase.id === 'hang-soft-recovered-through-the-request-file' && summary.forcedKill === true) {
        problems.push('hang-soft was force-killed; the graceful arm did not recover it');
      }
      return { problems };
    }

    if (testCase.expect.action === 'stop') {
      if (summary.outcome?.kind !== 'stopped') {
        problems.push(`outcome ${JSON.stringify(summary.outcome)}, expected stopped`);
      }
      if (summary.incarnation !== 1) problems.push(`incarnation ${summary.incarnation}, expected 1 (no restart)`);
      return { problems };
    }

    if (testCase.expect.action === 'exhausted') {
      if (summary.outcome?.kind !== 'exhausted') {
        problems.push(`outcome ${JSON.stringify(summary.outcome)}, expected exhausted`);
      }
      if (!String(summary.outcome?.reason ?? '').startsWith('ENGINE_RESTART_EXHAUSTED')) {
        problems.push(`terminal reason ${summary.outcome?.reason}, expected ENGINE_RESTART_EXHAUSTED:<why>`);
      }
      if (testCase.id === 'non-transient-2-exhausts-at-once') {
        if (summary.lastExit?.class !== 'NON_TRANSIENT') {
          problems.push(`lastExit.class ${summary.lastExit?.class}, expected NON_TRANSIENT`);
        }
        if ((summary.restartCount ?? 99) >= policy.maxRestartAttempts) {
          problems.push(
            `restartCount ${summary.restartCount} reached the budget â€” a non-transient exit must give`
            + ' up AT ONCE, not after spending the remaining attempts',
          );
        }
      }
      if (testCase.id === 'budget-exhausted-after-max-attempts'
        && summary.restartCount !== policy.maxRestartAttempts) {
        problems.push(
          `restartCount ${summary.restartCount}, expected exactly ${policy.maxRestartAttempts}`
          + ' â€” the budget must be spent, not short-changed',
        );
      }
      return { problems };
    }

    if (testCase.expect.action === 'request-shutdown') {
      if (!Array.isArray(summary.requestsWritten) || !summary.requestsWritten.includes(testCase.expect.reason)) {
        problems.push(
          `no \`${testCase.expect.reason}\` shutdown request was written (wrote: `
          + `${JSON.stringify(summary.requestsWritten)})`,
        );
      }
      return { problems };
    }

    problems.push(`the adapter has no arm for expected action \`${testCase.expect.action}\``);
    return { problems };
  } finally {
    try { run.child.kill(); } catch { /* already gone */ }
    await io.sleep(150);
    if (process.env.SUPERVISOR_CONFORMANCE_KEEP === '1') {
      process.stderr.write(`[keep] ${workDir}\n`);
    } else {
      try { fs.rmSync(workDir, { recursive: true, force: true, maxRetries: 5 }); } catch { /* windows handles */ }
    }
  }
}
