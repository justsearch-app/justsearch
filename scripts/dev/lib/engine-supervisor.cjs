/**
 * The Engine supervisor's decision seam, JS half (design 7.1, lane F stage B item B7).
 *
 * Two implementations of one contract: this file and `modules/shell/src-tauri/src/supervisor.rs`.
 * Both are pure `decide(observation, policy) -> action` functions over the SAME case list, which
 * lives in `governance/supervision-contract.v1.json`'s `engine` row — read here, embedded there
 * with `include_str!`. Neither copies the numbers: "one contract, two implementations" is a claim
 * about one file, and the conformance harness (`scripts/supervisor-conformance/`) is what makes it
 * checkable rather than asserted.
 *
 * WHAT IS DELIBERATELY NOT HERE: anything that touches a process, a socket or the clock. The
 * actuator half — spawn, wait for handle release, sleep the cooldown, write the request file, kill —
 * lives in the caller (`scripts/dev/dev-runner.cjs`), because that half is the part a unit test
 * cannot honestly stand in for and the part the harness has to drive for real. Keeping the decision
 * pure is what lets the same table run in Node and in Rust without a fake process in either.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const REGISTER_RELATIVE = path.join('governance', 'supervision-contract.v1.json');

/** Walk up from this file to the repo root (the directory that has the register). */
function resolveRepoRoot(start = __dirname) {
  let dir = path.resolve(start);
  for (let i = 0; i < 8; i++) {
    if (fs.existsSync(path.join(dir, REGISTER_RELATIVE))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(`supervision register not found above ${start} (looked for ${REGISTER_RELATIVE})`);
}

let cachedRegister = null;

function loadRegister({ repoRoot = resolveRepoRoot(), reload = false } = {}) {
  if (cachedRegister && !reload) return cachedRegister;
  const raw = fs.readFileSync(path.join(repoRoot, REGISTER_RELATIVE), 'utf8');
  cachedRegister = JSON.parse(raw);
  return cachedRegister;
}

function engineRow(options = {}) {
  const register = loadRegister(options);
  const row = (register.processes || []).find((p) => p.id === 'engine');
  if (!row) {
    throw new Error('supervision register has no `engine` process row (lane F stage B item B7)');
  }
  return row;
}

/**
 * The env var that turns the per-run overrides on. Setting an override WITHOUT this is inert, by
 * construction rather than by convention: a dev-runner started from a shell that happens to carry a
 * leftover JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS=1500 must not quietly ship a 1.5 s stability
 * window to a user's machine, and `harnessOverrides.note` in the register says so on the other side.
 */
const HARNESS_FLAG = 'JUSTSEARCH_SUPERVISOR_HARNESS';

/** Overridable parameter -> its env var. Only these; the restart BUDGET is never overridable. */
const OVERRIDE_ENV = Object.freeze({
  stabilityWindowMs: 'JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS',
  maxCooldownMs: 'JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS',
  cooldownIncrementMs: 'JUSTSEARCH_SUPERVISOR_COOLDOWN_INCREMENT_MS',
  startDeadlineMs: 'JUSTSEARCH_SUPERVISOR_START_DEADLINE_MS',
  gracefulStopDeadlineMs: 'JUSTSEARCH_SUPERVISOR_GRACEFUL_STOP_DEADLINE_MS',
  hangPollIntervalMs: 'JUSTSEARCH_SUPERVISOR_HANG_POLL_INTERVAL_MS',
  hangUnhealthyThreshold: 'JUSTSEARCH_SUPERVISOR_HANG_THRESHOLD',
});

/**
 * The policy the supervisor runs on: the register's numbers, plus per-run overrides IF AND ONLY IF
 * the harness flag is set in the same environment.
 *
 * `harnessActive` is returned rather than inferred by the caller so a state file can record which
 * numbers were in force — a supervisor state that says `exhausted` after a 1.5 s stability window
 * means something different from one that says it after 300 s.
 */
function loadPolicy({ env = process.env, repoRoot, reload } = {}) {
  const row = engineRow({ repoRoot, reload });
  const declared = row.policy;
  const policy = {
    maxRestartAttempts: declared.maxRestartAttempts,
    cooldownIncrementMs: declared.cooldownIncrementMs,
    maxCooldownMs: declared.maxCooldownMs,
    stabilityWindowMs: declared.stabilityWindowMs,
    stabilityWindowCountedFrom: declared.stabilityWindowCountedFrom,
    startDeadlineMs: declared.startDeadlineMs,
    gracefulStopDeadlineMs: declared.gracefulStopDeadlineMs,
    hangPollIntervalMs: declared.hangPollIntervalMs,
    hangUnhealthyThreshold: declared.hangUnhealthyThreshold,
    exitCodes: row.exitCodes,
    unknownExitClass: row.unknownExitClass,
    harnessActive: false,
    overridden: [],
  };
  if (env[HARNESS_FLAG] !== '1') return policy;
  policy.harnessActive = true;
  for (const [key, varName] of Object.entries(OVERRIDE_ENV)) {
    const raw = env[varName];
    if (raw == null || String(raw).trim() === '') continue;
    const value = Number(String(raw).trim());
    if (!Number.isFinite(value) || value < 0) continue;
    policy[key] = value;
    policy.overridden.push(key);
  }
  return policy;
}

/** The exit class the register declares for `code`, falling back to `unknownExitClass`. */
function classifyExit(code, policy) {
  const row = (policy.exitCodes || []).find((e) => e.code === code);
  return row ? row.class : policy.unknownExitClass;
}

/**
 * The short reason label for an exit code — the same rendering `EngineExit.describe` produces, so a
 * supervisor state file and an Engine log line name a death the same way. Unknown codes keep their
 * number: `unknown(-1073741819)` sends a reader to the Windows exception status, `crash` does not.
 */
function describeExit(code, policy) {
  const row = (policy.exitCodes || []).find((e) => e.code === code);
  return row ? row.name.toLowerCase() : `unknown(${code})`;
}

const ACTIONS = Object.freeze({
  NONE: 'none',
  STOP: 'stop',
  RESTART: 'restart',
  EXHAUSTED: 'exhausted',
  REQUEST_SHUTDOWN: 'request-shutdown',
  FORCE_KILL: 'force-kill',
  ENTER_RUNNING: 'enter-running',
  RESET_BUDGET: 'reset-budget',
});

const STATES = Object.freeze({
  STARTING: 'starting',
  RUNNING: 'running',
  STOPPING: 'stopping',
  RESTARTING: 'restarting',
  EXHAUSTED: 'exhausted',
});

function decideOnExit(observation, policy) {
  const requested = observation.requestedReason ?? null;
  const code = observation.exitCode;

  // Quit and upgrade retain host ownership. Restart is free only when the Engine
  // certifies a clean ordered close with its registered requested-restart exit.
  if (requested === 'restart' && describeExit(code, policy) === 'requested_restart') {
    // No cooldown ramp: nothing crashed. The actuator still waits for the process handle to close,
    // which is the floor under EVERY restart and is not a number this function can express.
    return { action: ACTIONS.RESTART, reason: 'restart', exitClass: 'REQUESTED', cooldownMs: 0, counted: false };
  }
  if (requested === 'upgrade') {
    return { action: ACTIONS.STOP, reason: 'upgrade', exitClass: 'REQUESTED', counted: false };
  }
  if (requested === 'quit') {
    return { action: ACTIONS.STOP, reason: 'quit', exitClass: 'REQUESTED', counted: false };
  }

  // `hang` is always counted: the request was the recovery, and the thing
  // being recovered from was a fault. Treating it as requested-and-free would give an Engine that
  // hangs every 30 seconds an unbounded number of restarts.
  const exitClass = requested === 'hang' ? 'TRANSIENT' : classifyExit(code, policy);
  const reason = requested === 'hang' ? 'hang' : describeExit(code, policy);

  if (exitClass === 'REQUESTED' && reason === 'requested_restart') {
    return { action: ACTIONS.RESTART, reason, exitClass, cooldownMs: 0, counted: false };
  }
  if (exitClass === 'REQUESTED') {
    // Exit 0 with nothing outstanding: the ordered shutdown ran because something else asked for it
    // (the HTTP trigger, a signal). Restarting here would fight the user.
    return { action: ACTIONS.STOP, reason, exitClass, counted: false };
  }
  if (exitClass === 'NON_TRANSIENT') {
    return { action: ACTIONS.EXHAUSTED, reason, exitClass, counted: false };
  }

  const attempt = (observation.restartCount ?? 0) + 1;
  if (attempt > policy.maxRestartAttempts) {
    return { action: ACTIONS.EXHAUSTED, reason, exitClass, counted: false };
  }
  return {
    action: ACTIONS.RESTART,
    reason,
    exitClass,
    cooldownMs: Math.min(policy.cooldownIncrementMs * attempt, policy.maxCooldownMs),
    counted: true,
  };
}

/**
 * The whole decision. Pure: same inputs, same answer, no clock and no filesystem.
 *
 * @param {{event: string, exitCode?: number, requestedReason?: string|null,
 *          consecutiveMisses?: number, restartCount?: number, state?: string}} observation
 * @param {object} policy from {@link loadPolicy}
 * @returns {{action: string, reason?: string, cooldownMs?: number, counted: boolean}}
 */
function decide(observation, policy) {
  const state = observation.state ?? STATES.RUNNING;
  switch (observation.event) {
    case 'exit':
      return decideOnExit(observation, policy);

    case 'ready':
      // The `starting` -> `running` edge. It is also what arms hang detection and what starts the
      // stability window, which is why it is a decision and not a bookkeeping detail: design 7.1
      // counts the window from `ready` precisely because a window from spawn is eaten by the boot.
      return state === STATES.STARTING
        ? { action: ACTIONS.ENTER_RUNNING, counted: false }
        : { action: ACTIONS.NONE, counted: false };

    case 'stability-elapsed':
      return { action: ACTIONS.RESET_BUDGET, counted: false };

    case 'health-miss':
      // Suspended outside `running`: a booting Engine answers nothing for seconds and one running
      // its ordered shutdown stops answering by design. Reading either as a hang would restart a
      // healthy Engine or race a shutdown with a kill.
      if (state !== STATES.RUNNING) return { action: ACTIONS.NONE, counted: false };
      if ((observation.consecutiveMisses ?? 0) < policy.hangUnhealthyThreshold) {
        return { action: ACTIONS.NONE, counted: false };
      }
      return { action: ACTIONS.REQUEST_SHUTDOWN, reason: 'hang', counted: false };

    case 'start-deadline-elapsed':
      if (state !== STATES.STARTING) return { action: ACTIONS.NONE, counted: false };
      return { action: ACTIONS.REQUEST_SHUTDOWN, reason: 'hang', counted: false };

    case 'request-deadline-elapsed':
      // The admission the request file makes: it is a request, never a guarantee. A JVM wedged at a
      // safepoint never reads it, and only this ends that.
      return {
        action: ACTIONS.FORCE_KILL,
        reason: observation.requestedReason ?? 'hang',
        counted: false,
      };

    default:
      throw new Error(`engine-supervisor: unknown observation event \`${observation.event}\``);
  }
}

/** A current-incarnation handoff bounds close; the exit code alone certifies a clean restart. */
function shutdownHandoffReason(manifest, pid, instanceId) {
  if (!pid || !instanceId || manifest?.schemaVersion !== 2
      || manifest.pid !== pid || manifest.instanceId !== instanceId) return null;
  const handoff = manifest.shutdownHandoff;
  if (!['pending', 'ready', 'incomplete'].includes(handoff?.state)) return null;
  return ['quit', 'restart', 'upgrade', 'hang'].includes(handoff.reason) ? handoff.reason : null;
}

module.exports = {
  shutdownHandoffReason,
  ACTIONS,
  STATES,
  HARNESS_FLAG,
  OVERRIDE_ENV,
  SUPERVISOR_STATE_FILENAME: 'supervisor.v1.json',
  SUPERVISOR_HISTORY_FILENAME: 'supervisor-history.v1.jsonl',
  resolveRepoRoot,
  loadRegister,
  engineRow,
  loadPolicy,
  classifyExit,
  describeExit,
  decide,
};
