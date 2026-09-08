/**
 * Conformance adapter: the production supervisor (the Tauri shell), lane F stage B item B10.
 *
 * It drives `modules/shell/src-tauri`'s `supervisor-conformance` binary, which runs the SAME
 * `run_supervision` loop `lib.rs` runs, over the same `decide` the register's decision table pins —
 * against a real child (the fake engine). That is the whole answer to `stages/B.md` Q2: `cargo test
 * --lib` proves the decisions, this proves the actuator, and "one contract, two implementations" is
 * false while either half of either implementation is unrun.
 *
 * The binary is built by the CI step that precedes this one (`cargo build --bin
 * supervisor-conformance --locked`); locally, run that first. If it is missing this adapter reports
 * itself UNAVAILABLE with the command — it does not build on demand, because a harness that
 * silently compiles a several-minute dependency graph in the middle of a case list is a harness
 * whose timings mean nothing.
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

import { enginePlanFor } from '../contract.mjs';

export const name = 'tauri';

const CRATE = ['modules', 'shell', 'src-tauri'];

function binaryPath(repoRoot) {
  for (const profile of ['debug', 'release']) {
    for (const suffix of ['.exe', '']) {
      const candidate = path.join(repoRoot, ...CRATE, 'target', profile, `supervisor-conformance${suffix}`);
      if (fs.existsSync(candidate)) return candidate;
    }
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
  'crash-1-restarts-under-budget',
  'oom-3-restarts-under-budget',
  'hang-soft-recovered-through-the-request-file',
  'hang-hard-recovered-by-forced-kill',
  'requested-restart-is-not-counted',
]);

function runBinary({ binary, workDir, dataDir, planPath, io, env, stopAfterIncarnation }) {
  const child = spawn(
    binary,
    [
      '--data-dir', dataDir,
      '--fake-engine', io.fakeEngine,
      '--plan', planPath,
      '--node', process.execPath,
      '--run-ms', '25000',
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
  });

  try {
    // A case that declares a `request` is driven by that request rather than by a fault: this is the
    // requested path, written by someone who is not the supervisor (item B15's escalation, item B6's
    // commit-shutdown), which is the shape the supervisor must not charge to the crash budget.
    if (testCase.request) {
      await io.waitFor(
        () => io.readJsonIfPresent(path.join(dataDir, 'runtime', 'supervisor.v1.json'))?.state === 'running',
        { timeoutMs: 20_000, what: 'the tauri supervisor to reach running' },
      ).catch(() => null);
      io.writeShutdownRequest(dataDir, {
        reason: testCase.request.reason,
        deadlineEpochMs: Date.now() + policy.gracefulStopDeadlineMs,
      });
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
      problems.push('no supervisor.v1.json was written — the state a dead Engine cannot report is missing');
    } else {
      if (state.kind !== 'engine-supervisor-state.v1') problems.push(`state kind ${state.kind}`);
      if (state.supervisor !== 'tauri') problems.push(`state supervisor ${state.supervisor}`);
      if (state.schemaVersion !== 1) problems.push(`state schemaVersion ${state.schemaVersion}`);
    }

    if (RESTART_CASES.has(testCase.id)) {
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
          `restartCount ${summary.restartCount}, expected 0 — a REQUESTED restart must not spend the crash budget`,
        );
      }
      if (testCase.id === 'oom-3-restarts-under-budget' && summary.lastExit?.reason !== 'out_of_memory') {
        problems.push(`lastExit.reason ${summary.lastExit?.reason}, expected out_of_memory`);
      }
      if (testCase.id === 'hang-hard-recovered-by-forced-kill' && summary.forcedKill !== true) {
        problems.push(
          'the hang-hard incarnation was not force-killed — the request file alone ended it, so the'
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
            `restartCount ${summary.restartCount} reached the budget — a non-transient exit must give`
            + ' up AT ONCE, not after spending the remaining attempts',
          );
        }
      }
      if (testCase.id === 'budget-exhausted-after-max-attempts'
        && summary.restartCount !== policy.maxRestartAttempts) {
        problems.push(
          `restartCount ${summary.restartCount}, expected exactly ${policy.maxRestartAttempts}`
          + ' — the budget must be spent, not short-changed',
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
