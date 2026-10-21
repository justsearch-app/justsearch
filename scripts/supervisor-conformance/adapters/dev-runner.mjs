import { runLifecycle } from '../lifecycle.mjs';
import { awaitFixtureExit, fixturesExited, removeFixtureDirectory } from '../fixture-cleanup.mjs';
/**
 * Conformance adapter: the development supervisor (`scripts/dev/dev-runner.cjs`), lane F item B8.
 *
 * This drives the REAL dev-runner — `node scripts/dev/dev-runner.cjs start` with an isolated state
 * root — against the fake engine, and reads the answers out of `<dataDir>/runtime/supervisor.v1.json`.
 * Nothing here stubs the supervisor: the crash is a real process exit, the cooldown really waits for
 * the handle, and the forced kill really runs `taskkill /T /F`. That is the point of an actuator
 * half — the decision table already proves the decisions, and what it cannot prove is that the code
 * around them does what the decision said.
 *
 * Two seams make it possible, both gated on JUSTSEARCH_SUPERVISOR_HARNESS=1 so neither can be
 * reached from a developer's environment by accident:
 *   JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND   — the fake engine replaces the Engine dist
 *   JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND — an idle process replaces the Vite dev server
 *
 * The fake engine's per-incarnation PLAN is what turns one spawned command into "crash, then behave"
 * — the supervisor spawns the same command line every time, exactly as it does in production.
 */

import fs from 'node:fs';
import assert from 'node:assert/strict';
import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';

import { enginePlanFor } from '../contract.mjs';
import { proveEssentialStability } from '../essential-stability.mjs';

const require = createRequire(import.meta.url);

export const name = 'dev-runner';

/** Spec F §7.2 lifecycle phases this adapter drives (`quit` arrives with the stop that asks the holder). */
export const phases = Object.freeze(['start', 'discovery', 'quit', 'kill']);

/** Spec F §0.1 / Spec B: the code a supervisor's forced kill leaves (0x4A530002). */
const JS_ENGINE_STOP = 0x4A530002;

/** An idle stand-in for the Vite dev server: the supervisor is not the frontend's supervisor. */
const IDLE_FRONTEND = [process.execPath, '-e', 'setInterval(() => {}, 60000)'];

export async function available({ io }) {
  const devRunner = path.join(io.repoRoot, 'scripts', 'dev', 'dev-runner.cjs');
  if (!fs.existsSync(devRunner)) return { ok: false, reason: `not found: ${devRunner}` };
  const exported = require(devRunner)?.__test ?? {};
  if (typeof exported.writeSupervisorState !== 'function') {
    return {
      ok: false,
      reason: 'dev-runner.cjs exports no supervisor actuator — item B8 has not landed in this tree',
    };
  }
  return { ok: true };
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** Poll `supervisor.v1.json` until `predicate` holds, recording every distinct state seen. */
export async function watchSupervisor({ statePath, predicate, timeoutMs, io, seen, faultGatePath }) {
  const end = io.timings?.wait('supervisor state: ' + predicate.toString(), timeoutMs);
  const deadline = Date.now() + timeoutMs;
  try {
    for (;;) {
      const state = io.readJsonIfPresent(statePath);
      if (state) {
        const key = `${state.state}:${state.incarnation}:${state.reason ?? ''}${state.phase ? `:${state.phase}` : ''}`;
        if (!seen.has(key)) seen.set(key, state);
        // Lifecycle cases may wait for a later incarnation. They still witness the first RUNNING
        // identity on the way there, so release its fixture fault just as the actuator cases do.
        if (faultGatePath && state.state === 'running' && state.incarnation === 1
            && !fs.existsSync(faultGatePath)) {
          fs.writeFileSync(faultGatePath, JSON.stringify({
            pid: state.pid, instanceId: state.instanceId, incarnation: state.incarnation,
          }));
        }
        if (predicate(state)) return state;
      }
      if (Date.now() >= deadline) return null;
      // eslint-disable-next-line no-await-in-loop
      await io.sleep(25);
    }
  } finally { end?.(); }
}

/** Settles with `promise`, or `false` after `ms`; the timer is cleared so it never holds the process open. */
function within(promise, ms) {
  let timer;
  const expired = new Promise(resolve => { timer = setTimeout(() => resolve(false), ms); });
  return Promise.race([promise, expired]).finally(() => clearTimeout(timer));
}

export async function driveCase({ testCase, policy, io, workDir = io.makeWorkDir(testCase.id), keepWorkDir = false }) {
  // One identity directory per launch: a case that drives two runs never stops one run's
  // fixtures on behalf of the other's teardown.
  fs.mkdirSync(workDir, { recursive: true });
  const identities = fs.mkdtempSync(path.join(workDir, 'fixture-identities-'));
  const identityPreload = path.join(io.repoRoot, 'scripts/supervisor-conformance/fixture-identity.cjs');
  const preload = process.platform === 'win32' ? ['--require', identityPreload] : [];
  const stateRoot = path.join(workDir, 'state');
  const dataDir = path.join(workDir, 'data');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  fs.mkdirSync(stateRoot, { recursive: true });

  const planPath = path.join(workDir, 'fake-engine.plan.json');
  if (!fs.existsSync(planPath)) io.writeEnginePlan(workDir, enginePlanFor(testCase));
  // The case's short budget belongs to incarnation 1, even if it never reaches readiness.
  const firstBootPreload = testCase.startTimeoutMs === undefined ? []
    : ['--require', path.join(io.repoRoot, 'scripts/supervisor-conformance/first-boot-budget.cjs')];
  const uiPort = await freePort();
  const statePath = path.join(dataDir, 'runtime', 'supervisor.v1.json');
  const faultGatePath = path.join(workDir, 'first-incarnation-running.json');
  const firstBootBudgetPath = path.join(workDir, 'first-boot-budget.json');
  const seen = new Map();

  io.timings?.launch(workDir);
  const child = spawn(
    process.execPath,
    [
      ...(io.timings ? ['--require', path.join(io.repoRoot, 'scripts/supervisor-conformance/timings-preload.cjs')] : []),
      ...preload,
      ...firstBootPreload,
      path.join(io.repoRoot, 'scripts', 'dev', 'dev-runner.cjs'),
      'start',
      '--json',
      '--skip-build',
      '--clean', 'none',
      '--api-port', '0',
      '--ui-port', String(uiPort),
      '--data-dir', dataDir,
      '--session-id', `supervisor-conformance-${testCase.id}`,
    ],
    {
      cwd: io.repoRoot,
      env: {
        ...process.env,
        ...io.harnessOverrides,
        JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
        JUSTSEARCH_CONFORMANCE_IDENTITIES: identities,
        ...(io.timings ? { JUSTSEARCH_CONFORMANCE_TIMINGS: path.join(workDir, 'spawn-timings.jsonl') } : {}),
        JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, ...preload, io.fakeEngine]),
        JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([IDLE_FRONTEND[0], ...preload, ...IDLE_FRONTEND.slice(1)]),
        JUSTSEARCH_FAKE_ENGINE_PLAN: planPath,
        JUSTSEARCH_FAKE_ENGINE_FAULT_GATE: faultGatePath,
        JUSTSEARCH_SUPERVISOR_HARNESS_REQUEST_REASON: testCase.request?.reason ?? '',
        JUSTSEARCH_CONFORMANCE_FIRST_BOOT_TIMEOUT_MS: String(testCase.startTimeoutMs ?? ''),
        JUSTSEARCH_CONFORMANCE_FIRST_BOOT_BUDGET_PATH: firstBootBudgetPath,
        // Keep the first incarnation's start bounded: the default is 15 s locally and 300 s in CI,
        // and a case that hangs for either is a case nobody will run.
        JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '10000',
        JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS: '10000',
        CI: '',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  const output = { stdout: '', stderr: '' };
  child.stdout.on('data', (b) => { output.stdout += b.toString(); });
  child.stderr.on('data', (b) => {
    output.stderr += b.toString();
    // A failing conformance case is a supervisor that did something unexpected, and the only witness
    // is the supervisor's own narration. Streaming it on demand is the difference between a failure
    // you can read and one you have to reproduce.
    // Timestamped on the way out: the supervisor's own lines are not, and every failure in this
    // adapter has been a question about the ORDER of two events (did the deadline fire before the
    // exit, did the request land before the poll) that untimestamped lines cannot answer.
    if (process.env.SUPERVISOR_CONFORMANCE_VERBOSE === '1') {
      for (const line of b.toString().split('\n').filter(Boolean)) {
        process.stderr.write(`${new Date().toISOString()} ${line}\n`);
      }
    }
  });
  // Without this, a spawn that never starts (ENOENT on the runner, a bad env) surfaces as an empty
  // stderr and a timeout — a failure whose message actively misleads.
  child.on('error', (err) => { output.stderr += `[spawn error] ${err.message}\n`; });
  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));

  const cleanup = async () => {
    const alive = () => child.exitCode === null && child.signalCode === null;
    const runnerEnded = ms => within(exited.then(() => true), ms);
    if (process.platform === 'win32' && alive()) {
      // The runner's own handle cannot name a reused PID. Ending the runner closes its libuv job
      // (KILL_ON_JOB_CLOSE), which ends the fixtures it started; their exits are confirmed below.
      try { child.kill(); } catch { /* already gone */ }
      await runnerEnded(2000);
    }
    // A runner still alive is asked to close its own Engine. Every fixture has recorded its PID and
    // a moment it held it before running application code; a failed orderly stop can only target
    // births resolved from those retained identities and recorded before the stop.
    if (alive()) {
      // The stop speaks for the session that started the run, with the same harness settings;
      // any other caller is refused as an owner conflict.
      const stop = spawn(process.execPath, [...preload,
        path.join(io.repoRoot, 'scripts/dev/dev-runner.cjs'), 'stop', '--json',
        '--session-id', `supervisor-conformance-${testCase.id}`], {
        cwd: io.repoRoot, windowsHide: true, stdio: 'ignore',
        env: { ...process.env, ...io.harnessOverrides, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
          JUSTSEARCH_CONFORMANCE_IDENTITIES: identities },
      });
      await within(new Promise((resolve, reject) => { stop.once('exit', resolve); stop.once('error', reject); }), 20000);
    }
    // A PID still taken may be a live fixture or a reuse: only the birth query tells them apart.
    if (process.platform === 'win32' && !(await fixturesExited(identities))) {
      await awaitFixtureExit(identities, { sleep: io.sleep });
    }
    let ended = await runnerEnded(5000);
    if (!ended && process.platform !== 'win32') {
      // Off Windows the runner is this process's own unreaped child while its exit is unseen, so
      // its handle cannot name a reused PID: a failed orderly stop falls back to that handle.
      try { child.kill('SIGTERM'); } catch { /* already gone */ }
      ended = await runnerEnded(3000);
      if (!ended) {
        try { child.kill('SIGKILL'); } catch { /* already gone */ }
        ended = await runnerEnded(2000);
      }
    }
    if (!ended) throw new Error('fixture runner exit unconfirmed; artifacts retained');
    if (keepWorkDir) return;
    if (io.deferCleanup) { io.deferCleanup(workDir, () => removeFixtureDirectory(workDir)); return; }
    if (process.env.SUPERVISOR_CONFORMANCE_KEEP === '1') {
      process.stderr.write(`[keep] ${workDir}\n`);
      return;
    }
    removeFixtureDirectory(workDir);
  };

  const run = { workDir, dataDir, stateRoot, statePath, faultGatePath, firstBootBudgetPath, child, output, exited, seen, cleanup, uiPort, identities };
  io.timings?.attach(run);
  return run;
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

export async function runCase({ testCase, policy, io }) {
  if (testCase.probe || (name === "tauri" && testCase.expect?.ledger)) return runLifecycle({ name, testCase, policy, io });
  if ((testCase.drives ?? []).some((d) => phases.includes(d)) && !(testCase.drives ?? []).includes('actuator')) {
    return runLifecycleCase({ testCase, policy, io });
  }
  const problems = [];
  const run = await driveCase({ testCase, policy, io });
  try {
    // Every case starts the same way: the supervisor must reach `running` before the fault, or the
    // case is testing the START path and not the supervisor.
    const running = await watchSupervisor({
      statePath: run.statePath,
      predicate: (s) => s.state === 'running' && s.incarnation === 1,
      timeoutMs: 20_000,
      io,
      seen: run.seen,
    });
    if (!running) {
      // stdout as well as stderr: the dev-runner reports a refused start as JSON on STDOUT, so an
      // adapter that only quoted stderr reported "never reached running" with an empty explanation.
      problems.push(
        'supervisor never reached running/incarnation 1.\n'
        + `        stdout: ${run.output.stdout.slice(-800)}\n`
        + `        stderr: ${run.output.stderr.slice(-1500)}`,
      );
      return { problems };
    }
    if (running.policyProfile !== 'harness') {
      problems.push(`the supervisor ran on the PRODUCT policy (${running.policyProfile}); the overrides did not reach it`);
    }
    // Arm the fixture only after witnessing the precondition. Socket/manifest publication precedes
    // Windows process census and lease setup; a timer from listen() can expire before RUNNING.
    // Keep the configured fault delays and all post-fault assertions unchanged. Later incarnations
    // retain their original timing, including deaths during discovery and partial boots.
    fs.writeFileSync(run.faultGatePath, JSON.stringify({
      pid: running.pid, instanceId: running.instanceId, incarnation: running.incarnation,
    }), 'utf8');

    if (testCase.id === 'live-503-and-continuous-essential-stability') {
      problems.push(...await proveEssentialStability({ dataDir: run.dataDir, statePath: run.statePath, policy, io }));
      return { problems };
    }

    if (RESTART_CASES.has(testCase.id)) {
      const restarted = await watchSupervisor({
        statePath: run.statePath,
        predicate: (s) => s.state === 'running' && s.incarnation === 2,
        timeoutMs: 25_000,
        io,
        seen: run.seen,
      });
      if (!restarted) {
        problems.push(
          `no second incarnation reached running. states seen: ${[...run.seen.keys()].join(' -> ')}\n`
          + run.output.stderr.slice(-1500),
        );
        return { problems };
      }
      const counted = testCase.expect.counted !== false;
      if (counted && restarted.restartCount !== 1) {
        problems.push(`restartCount ${restarted.restartCount}, expected 1 (this death is charged to the budget)`);
      }
      if (!counted && restarted.restartCount !== 0) {
        problems.push(
          `restartCount ${restarted.restartCount}, expected 0 — a REQUESTED restart must not spend the crash budget`,
        );
      }
      if (testCase.id === 'fileless-clean-local-restart-is-not-counted'
          || testCase.id === 'requested-restart-is-not-counted'
          || testCase.id === 'requested-restart-completion-failure-is-counted') {
        const expectedReason = counted ? 'fatal_or_uncaught'
          : testCase.request ? 'restart' : 'requested_restart';
        const expectedClass = counted ? 'TRANSIENT' : 'REQUESTED';
        if (restarted.lastExit?.code !== testCase.observation.exitCode
            || restarted.lastExit?.reason !== expectedReason
            || restarted.lastExit?.class !== expectedClass
            || restarted.lastExit?.counted !== counted) {
          problems.push(`incorrect restart exit record: ${JSON.stringify(restarted.lastExit)}`);
        }
        if (!testCase.request && fs.existsSync(path.join(run.dataDir, 'runtime', 'shutdown-request.v1.json'))) {
          problems.push('fileless restart left a shutdown request');
        }
      }
      if (['local-handoff-bounds-responsive-close-with-churn',
        'completed-local-handoff-still-bounds-native-exit'].includes(testCase.id)) {
        if (!(/FORCED KILL:/.test(run.output.stderr)) || restarted.lastExit?.reason !== 'hang'
            || restarted.lastExit?.class !== 'TRANSIENT' || !restarted.lastExit?.counted) {
          problems.push('responsive local close was not force-killed and charged as a hang');
        }
      }
      if (testCase.id === 'local-handoff-fatal-exit-remains-counted'
          && ((/FORCED KILL:/.test(run.output.stderr)) || restarted.lastExit?.reason !== 'fatal_or_uncaught')) {
        problems.push('local fatal exit was misclassified or force-killed before its own completion');
      }
      if (testCase.id === 'oom-3-restarts-under-budget' && restarted.lastExit?.reason !== 'out_of_memory') {
        problems.push(`lastExit.reason ${restarted.lastExit?.reason}, expected out_of_memory`);
      }
      if (testCase.id === 'escalated-restart'
          && (restarted.lastExit?.code !== 5
            || restarted.lastExit?.reason !== 'escalated_restart'
            || restarted.lastExit?.class !== 'TRANSIENT'
            || restarted.lastExit?.counted !== true)) {
        problems.push(`escalated restart did not spend the host budget: ${JSON.stringify(restarted.lastExit)}`);
      }
      // `FORCED KILL:` and not a looser match: the supervisor also NARRATES the deadline when it
      // writes the request ("...then a forced kill"), and a regex that matched both reported every
      // graceful recovery as a forced one. Caught by hang-soft failing while its own log showed the
      // graceful arm working.
      if (testCase.id === 'hang-hard-recovered-by-forced-kill' && !/FORCED KILL:/.test(run.output.stderr)) {
        problems.push(
          'the hang-hard incarnation was not force-killed — the request file alone ended it, so the'
          + ' forced branch was never exercised',
        );
      }
      if (testCase.id === 'hang-soft-recovered-through-the-request-file' && /FORCED KILL:/.test(run.output.stderr)) {
        problems.push('hang-soft was force-killed; the graceful arm did not recover it');
      }
      problems.push(...assertRunContinuity(run, restarted, io));
      return { problems };
    }

    if (testCase.expect.action === 'stop') {
      const code = await Promise.race([run.exited, io.sleep(20_000).then(() => 'TIMEOUT')]);
      if (code === 'TIMEOUT') {
        problems.push(`the dev-runner kept supervising after a \`${testCase.expect.action}\` decision`);
      } else if (code !== 0) {
        problems.push(`dev-runner exited ${code}, expected 0`);
      }
      const final = io.readJsonIfPresent(run.statePath);
      if (final?.state !== 'stopping') problems.push(`final state ${final?.state}, expected stopping`);
      if (final?.incarnation !== 1) problems.push(`incarnation ${final?.incarnation}, expected 1 (no restart)`);
      return { problems };
    }

    if (testCase.expect.action === 'exhausted') {
      const code = await Promise.race([run.exited, io.sleep(30_000).then(() => 'TIMEOUT')]);
      const final = io.readJsonIfPresent(run.statePath);
      if (code === 'TIMEOUT') problems.push('the dev-runner never gave up');
      if (final?.state !== 'exhausted') {
        problems.push(`final state ${final?.state}, expected exhausted. seen: ${[...run.seen.keys()].join(' -> ')}`);
      }
      if (!String(final?.reason ?? '').startsWith('ENGINE_RESTART_EXHAUSTED')) {
        problems.push(`terminal reason ${final?.reason}, expected ENGINE_RESTART_EXHAUSTED:<why>`);
      }
      if (testCase.id === 'non-transient-2-exhausts-at-once') {
        if (final?.lastExit?.class !== 'NON_TRANSIENT') {
          problems.push(`lastExit.class ${final?.lastExit?.class}, expected NON_TRANSIENT`);
        }
        if ((final?.restartCount ?? 99) >= policy.maxRestartAttempts) {
          problems.push(
            `restartCount ${final?.restartCount} reached the budget — a non-transient exit must give up`
            + ' AT ONCE, not after spending the remaining attempts',
          );
        }
      }
      if (testCase.id === 'budget-exhausted-after-max-attempts'
        && final?.restartCount !== policy.maxRestartAttempts) {
        problems.push(
          `restartCount ${final?.restartCount}, expected exactly ${policy.maxRestartAttempts}`
          + ' — the budget must be spent, not short-changed',
        );
      }
      // Q5's mirror: the terminal record has to outlive the live file, because the reader that needs
      // it (the updater's dead-Engine path) arrives after everything else is gone.
      const historyPath = path.join(run.dataDir, 'runtime', 'instances', 'supervisor-history.v1.jsonl');
      const history = fs.existsSync(historyPath)
        ? fs.readFileSync(historyPath, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l))
        : [];
      if (!history.some((r) => r.state === 'exhausted')) {
        problems.push(`the terminal state was not mirrored into ${historyPath}`);
      }
      return { problems };
    }

    if (testCase.expect.action === 'request-shutdown') {
      const stopping = await watchSupervisor({
        statePath: run.statePath,
        predicate: (s) => s.state === 'stopping' && s.reason === testCase.expect.reason,
        timeoutMs: 30_000,
        io,
        seen: run.seen,
      });
      if (!stopping) {
        problems.push(
          `never observed stopping/${testCase.expect.reason}. seen: ${[...run.seen.keys()].join(' -> ')}\n`
          + run.output.stderr.slice(-1500),
        );
      }
      // The file itself is transient — the Engine deletes it the moment it consumes it, which is the
      // success case — so the durable evidence is the supervisor's own narration of having written it.
      const requested = fs.existsSync(path.join(run.dataDir, 'runtime', 'shutdown-request.v1.json'))
        || /wrote a shutdown request: reason=/.test(run.output.stderr);
      if (!requested) {
        problems.push(`no shutdown request was written. stderr: ${run.output.stderr.slice(-1200)}`);
      }
      return { problems };
    }

    problems.push(`the adapter has no arm for expected action \`${testCase.expect.action}\``);
    return { problems };
  } finally {
    await run.cleanup();
  }
}

/**
 * The item-B8 acceptance assertion, checked on every restart case rather than once: a supervised
 * restart keeps the run id, the lease and the log streams (design 7.6's dev-runner sentence), and
 * therefore does NOT read as an abandoned stack to another session.
 *
 * TAKEOVER_ABANDONED is decided from the OWNER's activity, not from the child's, so the way a
 * restart could produce it is by disturbing the lease — resetting the sequence, shortening the
 * expiry, or rewriting the holder. Asserting the verdict alone would pass for the wrong reason
 * (a fresh stamp), so the lease's own continuity is asserted beside it.
 */
function assertRunContinuity(run, restarted, io) {
  const problems = [];
  const active = io.readJsonIfPresent(path.join(run.stateRoot, 'active.json'));
  if (!active) {
    problems.push('active.json vanished across the restart — the lease did not survive the child');
    return problems;
  }
  if (active.runId !== restarted.runId) {
    problems.push(`active.json runId ${active.runId} != supervisor runId ${restarted.runId}`);
  }
  if (!(active.lease?.sequence >= 1)) {
    problems.push(`lease.sequence ${active.lease?.sequence} — a restart must not reset the lease`);
  }
  if (!active.lease?.expiresAt || Date.parse(active.lease.expiresAt) <= Date.parse(active.lease.renewedAt)) {
    problems.push('lease expiry is not ahead of its renewal; the lease is not live across the restart');
  }

  const { computeOwnershipVerdict } = require(
    path.join(io.repoRoot, 'scripts', 'dev', 'lib', 'ownership-verdict.cjs'),
  );
  const verdict = computeOwnershipVerdict({
    active,
    now: Date.now(),
    takeover: 'deny',
    callerSessionId: 'a-different-session',
    selfCheck: false,
    supervisorAlive: true,
    leaseExpired: Date.parse(active.lease?.expiresAt ?? 0) <= Date.now(),
    // The owner is present — which is what makes the assertion sharp. TAKEOVER_ABANDONED is decided
    // from the owner's silence, so feeding a SILENT owner here would make the verdict a foregone
    // conclusion and the test would pass (or fail) for a reason that has nothing to do with the
    // restart. With a live owner, the only way to reach that verdict is a disturbed lease.
    ownerActivity: { lastActivityAt: new Date().toISOString(), lastDevStackTouchAt: new Date().toISOString() },
  });
  if (verdict.verdict === 'TAKEOVER_ABANDONED') {
    problems.push(
      'a supervised restart reads as TAKEOVER_ABANDONED to another session — the restart disturbed'
      + ' the lease, and the next agent would reclaim a stack that is recovering',
    );
  }
  // The four log streams outlive the child (7.6), and each incarnation's engine.log is preserved
  // before the next one can append to it (item B9's third assertion, proven here on real files).
  const runDir = path.join(run.stateRoot, 'runs', restarted.runId);
  for (const stream of ['backend.stdout.log', 'backend.stderr.log']) {
    if (!fs.existsSync(path.join(runDir, 'logs', stream))) {
      problems.push(`log stream ${stream} did not survive the restart`);
    }
  }
  return problems;
}

// =============================================================================================
// Spec F §7.2 lifecycle cases (start, discovery, kill): the exit ledger is the oracle.
//
// Each case declares the engine records its run must leave in `expect.ledger`, in order, with only
// the fields it makes a claim about; pids and times are never compared (S17-C1's normalisation).
// =============================================================================================

export function engineRecords(io, dataDir) {
  const ledgerLib = require(path.join(io.repoRoot, 'scripts', 'dev', 'lib', 'exit-ledger.cjs'));
  try {
    return ledgerLib.readLedger(ledgerLib.ledgerDirectory(dataDir), 'engine').records
      .filter((r) => r.type.startsWith('engine-'))
      .map((r) => r.line);
  } catch {
    return [];
  }
}

/** Whether `actual` carries every field `want` names, recursively for plain objects. */
export function subsetMismatch(want, actual, where) {
  const problems = [];
  for (const [key, value] of Object.entries(want)) {
    const got = actual?.[key];
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      problems.push(...subsetMismatch(value, got, `${where}.${key}`));
    } else if (got !== value) {
      problems.push(`${where}.${key}: expected ${JSON.stringify(value)}, got ${JSON.stringify(got)}`);
    }
  }
  return problems;
}

/**
 * Compare the run's engine records with the case's list. `session: "previous"` names the run before
 * this one; otherwise a record belongs to `runId`. A forced kill that fell back to taskkill (dev only,
 * recorded `codeDistinctive: false`) leaves code 1 instead of JS_ENGINE_STOP and is accepted as such.
 */
export function ledgerMismatch(records, expected, { runId, previousRunId, adapter = 'dev-runner' }) {
  // The Engine names itself by process, not incarnation (Spec B §6.1): a self-intent belongs to the
  // incarnation of this run whose engine-start, written before it, has the same pid.
  const byPid = new Map();
  const attributed = records.map((r) => {
    if (r.type === 'engine-start' && r.incarnation?.session === runId) byPid.set(Number(r.pid), r.incarnation);
    if (r.type !== 'engine-self-intent') return r;
    const incarnation = byPid.get(Number(r.engine?.pid));
    return incarnation ? { ...r, incarnation } : r;
  });
  const ours = attributed.filter((r) => r.incarnation?.session === runId
    || (previousRunId && r.type === 'engine-owner-died' && r.incarnation?.session === previousRunId));
  const problems = [];
  if (ours.length !== expected.length) {
    problems.push(`${ours.length} engine record(s), expected ${expected.length}: `
      + `${ours.map((r) => `${r.type}#${r.incarnation?.n}`).join(', ')}`);
  }
  expected.forEach((want, i) => {
    const got = ours[i];
    const where = `record ${i + 1} (${want.type} n=${want.n})`;
    if (!got) { problems.push(`${where}: missing`); return; }
    const { type, n, session, ...fields } = want;
    if (got.type !== type) problems.push(`${where}: type ${got.type}`);
    if (got.incarnation?.n !== n) problems.push(`${where}: incarnation ${got.incarnation?.n}`);
    if (session === 'previous' && got.incarnation?.session !== previousRunId) {
      problems.push(`${where}: session ${got.incarnation?.session}, expected the previous run ${previousRunId}`);
    }
    const compared = { ...fields };
    if (type === 'engine-exit' && compared.code === JS_ENGINE_STOP && process.platform === 'linux') {
      // POSIX reports the terminating signal, not a Windows DWORD exit code. Keep forced-stop
      // provenance and its deadline asserted. The shell also proves its prior owner intent;
      // the dev-runner retains its conservative UNKNOWN classification.
      compared.code = null;
      compared.exitClass = adapter === 'tauri' ? 'REQUESTED' : 'UNKNOWN';
      if (adapter === 'tauri') { compared.signal = 9; compared.ownerIntentBeforeSignal = true; }
      compared.codeDistinctive = false;
      compared.forced = true;
      if (!Number.isFinite(Date.parse(got.terminateCalledAt))) problems.push(`${where}: missing forced-stop timestamp`);
      if (got.exitedAt !== null) problems.push(`${where}: fabricated OS exit timestamp`);
    } else if (type === 'engine-exit' && compared.code === JS_ENGINE_STOP && got.codeDistinctive === false) {
      // The documented dev fallback (Spec F §5.2): taskkill /F, code 1, recorded as such.
      compared.code = 1;
    }
    problems.push(...subsetMismatch(compared, got, where));
    if (type === 'engine-exit' && got.codeDistinctive === false && got.code !== (process.platform === 'linux' ? null : 1)) {
      problems.push(`${where}: codeDistinctive false with code ${got.code}`);
    }
  });
  return problems;
}

async function waitForLedger(io, dataDir, expected, ids, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const problems = ledgerMismatch(engineRecords(io, dataDir), expected, ids);
    if (problems.length === 0 || Date.now() >= deadline) return problems;
    // eslint-disable-next-line no-await-in-loop
    await io.sleep(100);
  }
}

async function runLifecycleCase({ testCase, policy, io }) {
  const problems = [];
  const expect = testCase.expect ?? {};
  const workDir = io.makeWorkDir(testCase.id);
  let previousRunId = null;
  const runs = [];
  try {
    if (expect.killSupervisor) {
      // The supervisor is killed outright (TerminateProcess, no handler runs); its Engine dies with
      // the runner's job and nobody records the exit.
      const first = await driveCase({ testCase, policy, io, workDir, keepWorkDir: true });
      runs.push(first);
      const up = await watchSupervisor({ statePath: first.statePath, predicate: (s) => s.state === 'running',
        timeoutMs: 20_000, io, seen: first.seen, faultGatePath: first.faultGatePath });
      if (!up) {
        problems.push(`the first supervisor never reached running. stderr: ${first.output.stderr.slice(-1200)}`);
        return { problems };
      }
      previousRunId = up.runId;
      // The open start must be on disk before the kill, or the case proves nothing.
      const opened = await io.waitFor(() => engineRecords(io, first.dataDir)
        .some((r) => r.type === 'engine-start' && r.incarnation?.session === previousRunId), { timeoutMs: 10_000,
        what: 'the first run\'s engine-start' }).catch(() => false);
      if (!opened) problems.push('the first run never wrote its engine-start');
      first.child.kill();
      await Promise.race([first.exited, io.sleep(5000)]);
      const enginePid = up.pid;
      await io.waitFor(() => { try { process.kill(enginePid, 0); return false; } catch { return true; } },
        { timeoutMs: 10_000, what: 'the orphaned Engine to end with the runner' })
        .catch(() => problems.push(`the Engine (pid ${enginePid}) outlived its killed supervisor`));
    }

    const run = await driveCase({ testCase, policy, io, workDir, keepWorkDir: true });
    runs.push(run);
    const reach = expect.reach ?? { state: 'running', incarnation: 1 };
    const reached = await watchSupervisor({
      statePath: run.statePath,
      predicate: (s) => s.state === reach.state && s.incarnation === reach.incarnation
        && (!previousRunId || s.runId !== previousRunId),
      timeoutMs: 40_000,
      io,
      seen: run.seen,
      faultGatePath: run.faultGatePath,
    });
    if (!reached) {
      problems.push(
        `never reached ${reach.state}/${reach.incarnation}. seen: ${[...run.seen.keys()].join(' -> ')}\n`
        + `        stdout: ${run.output.stdout.slice(-600)}\n        stderr: ${run.output.stderr.slice(-1500)}`);
      return { problems };
    }
    if (testCase.startTimeoutMs !== undefined) {
      assert.deepEqual(io.readJsonIfPresent(run.firstBootBudgetPath), {
        initialPortMs: testCase.startTimeoutMs,
        initialReadyMs: testCase.startTimeoutMs,
      }, 'first incarnation captured the short startup budget before the spawn hook');
    }
    if (reach.restartCount !== undefined && reached.restartCount !== reach.restartCount) {
      problems.push(`restartCount ${reached.restartCount}, expected ${reach.restartCount}`);
    }
    if (expect.lastExit) problems.push(...subsetMismatch(expect.lastExit, reached.lastExit, 'lastExit'));
    if (expect.seenPhase) {
      const { incarnation, phase } = expect.seenPhase;
      const seenIt = [...run.seen.values()].some((s) => s.incarnation === incarnation && s.phase === phase);
      if (!seenIt) {
        problems.push(`never saw starting/${phase} on incarnation ${incarnation}: ${[...run.seen.keys()].join(' -> ')}`);
      }
    }
    if (expect.forcedKill && !/FORCED KILL:/.test(run.output.stderr)) {
      problems.push('the supervisor never narrated a FORCED KILL');
    }
    if (expect.ledger) {
      problems.push(...await waitForLedger(io, run.dataDir, expect.ledger, { runId: reached.runId, previousRunId }));
    }
    return { problems };
  } finally {
    for (const run of runs) {
      // eslint-disable-next-line no-await-in-loop
      await run.cleanup();
    }
    if (io.deferCleanup) {
      io.deferCleanup(workDir, () => removeFixtureDirectory(workDir));
    } else if (process.env.SUPERVISOR_CONFORMANCE_KEEP === '1') {
      process.stderr.write(`[keep] ${workDir}\n`);
    } else {
      removeFixtureDirectory(workDir);
    }
  }
}
