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
  exhaustedRetryDelayMs: 'JUSTSEARCH_SUPERVISOR_EXHAUSTED_RETRY_DELAY_MS',
  quitDeadlineMs: 'JUSTSEARCH_SUPERVISOR_QUIT_DEADLINE_MS',
  dataDirLockedWaitMs: 'JUSTSEARCH_SUPERVISOR_DATA_DIR_LOCKED_WAIT_MS',
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
    exhaustedRetryDelayMs: declared.exhaustedRetryDelayMs,
    cooldownIncrementMs: declared.cooldownIncrementMs,
    maxCooldownMs: declared.maxCooldownMs,
    stabilityWindowMs: declared.stabilityWindowMs,
    stabilityWindowCountedFrom: declared.stabilityWindowCountedFrom,
    startDeadlineMs: declared.startDeadlineMs,
    gracefulStopDeadlineMs: declared.gracefulStopDeadlineMs,
    hangPollIntervalMs: declared.hangPollIntervalMs,
    hangUnhealthyThreshold: declared.hangUnhealthyThreshold,
    // Spec F §4.3: the host quit's deadline and the margin kept for the supervisor's own force. Not
    // overridable: the Engine budgets its close against the same numbers.
    quitDeadlineMs: declared.quitDeadlineMs,
    quitForceMarginMs: declared.quitForceMarginMs,
    dataDirLockedWaitMs: declared.dataDirLockedWaitMs,
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
  // Spec F §3.2: terminal after a host quit (stop, takeover, interruption). A STOP decided by the
  // contract on its own (exit 0, a harness quit or upgrade request) still publishes `stopping`.
  STOPPED: 'stopped',
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

/**
 * The handoff reason the supervisor may still attribute to an exit it did not see requested.
 * Only terminal operator intents qualify: a quick failed quit or upgrade close must not be
 * restarted. A restart or hang handoff from an Engine that then died on its own (a terminal
 * writer fault marks one) must not relabel that exit; the exit-code classifier owns it.
 */
function exitHandoffReason(manifest, pid, instanceId) {
  const reason = shutdownHandoffReason(manifest, pid, instanceId);
  return reason === 'quit' || reason === 'upgrade' ? reason : null;
}

// =============================================================================================
// The run loop (Spec F §3.3 and §5.2, step 9): T1-T12 over an injected actuator.
//
// The loop decides nothing the contract does not: every edge that the contract owns goes through
// `decide`. What the loop owns is ORDER. It is the only consumer of an Engine exit (`pollExit`), so a
// death in any phase — before the manifest, between the manifest and HTTP, during readiness, while
// running or while stopping — takes one path (T4) and leaves one `engine-exit`. Each tick runs, in
// order: the exit, a host quit, the request deadline, then host requests and health.
//
// It touches no process, file or socket itself: the actuator (engine-actuator.cjs) and the `io`
// functions the caller passes do. That keeps the module's promise above — the decision half is pure —
// and lets the dev-runner keep its CLI, frontend and reports.
// =============================================================================================

const TICK_MS = 50;
const READY_PROBE_GAP_MS = 500;
const READY_PROBE_TIMEOUT_MS = 1200;
const HANG_PROBE_TIMEOUT_MS = 1000;
/** Spec F §3.5: a loop wait that overran by more than this was a suspend, not a slow tick. */
const SUSPEND_GAP_MS = 2000;
/** Spec F §3.5: after a resume, health misses are not counted for this long. */
const RESUME_GRACE_MS = 30000;

/**
 * The supervisor's clock with suspended time taken out (Spec F §3.5, dev half). Node has no power
 * API, so the only source is the tick-gap detector: a loop wait of `tickMs` that returns after more
 * than `suspendGapMs` was a suspend of the observed gap minus one tick. `now()` is the monotonic
 * clock minus every suspend seen so far, so a deadline armed on it is extended by the suspend; with
 * no suspend it is the monotonic clock. `inResumeGrace()` is true for `resumeGraceMs` of monotonic
 * time after the last resume.
 *
 * Only the wait is measured, never the loop's own awaits (a POST may take its 2 s timeout): a slow
 * step must not read as a suspend, which would push the quit's force past the contract deadline.
 */
function createSuspendAwareClock({
  monotonic = () => performance.now(), tickMs = TICK_MS, suspendGapMs = SUSPEND_GAP_MS,
  resumeGraceMs = RESUME_GRACE_MS,
} = {}) {
  let suspendedMs = 0;
  let resumedAt = null;
  let openSuspend = null;
  let resumed = false;
  return {
    takeResumed() { const value = resumed; resumed = false; return value; },
    now: () => (openSuspend ?? monotonic()) - suspendedMs,
    notePower(kind, at = monotonic()) {
      if (kind === 'suspend' && openSuspend === null) openSuspend = at;
      if (kind === 'resume' && openSuspend !== null) {
        suspendedMs += Math.max(0, at - openSuspend); openSuspend = null; resumedAt = at; resumed = true;
      }
    },
    /** Times one wait: `await clock.measureWait(() => waitOrWake(tickMs))`; returns the suspend seen. */
    async measureWait(wait) {
      const before = monotonic();
      await wait();
      const after = monotonic();
      const gap = after - before;
      if (openSuspend !== null || (resumedAt !== null && resumedAt >= before) || gap <= suspendGapMs) return 0;
      const suspended = gap - tickMs;
      suspendedMs += suspended;
      resumedAt = after;
      return suspended;
    },
    inResumeGrace: () => resumedAt !== null && monotonic() - resumedAt < resumeGraceMs,
    suspendedMs: () => suspendedMs,
  };
}
const HARNESS_REQUEST_DELAY_MS = 500;
const HARNESS_REQUEST_REASONS = Object.freeze(['quit', 'restart', 'upgrade', 'hang']);

/**
 * @param {object} options
 * @param {object} options.actuator see engine-actuator.cjs
 * @param {object} options.policy from {@link loadPolicy}
 * @param {object} options.io {readManifest(pid, predecessorInstanceId) -> {apiPort, instanceId}|null,
 *   readRawManifest() -> object|null, probeHealth(port, ms) -> Promise<bool>, probeAlive(port, ms),
 *   fetchStatus(port, ms) -> Promise<object|null>, essentialReadyEpoch(status) -> string|null,
 *   waitForRelease({pid}) -> Promise<{released, waitedMs}>, clearStaleDiscoveryFiles()}
 * @param {(fields: object) => Promise<void>} options.publish writes the supervisor state record
 * @param {{initialPortMs: number, initialReadyMs: number}} options.timeouts the first boot's limits
 * @param {number} options.requestedApiPort the port the first incarnation is asked to bind
 * @param {{requestReason?: string|null}} [options.harness]
 * @param {object} [options.hooks] onSpawned, onReady, onIncarnationExit (awaited), onTerminal
 */
function createSupervisionLoop({
  actuator,
  policy,
  io,
  publish,
  timeouts,
  requestedApiPort = 0,
  harness = {},
  hooks = {},
  log = () => {},
  monotonic = () => performance.now(),
  wallClock = () => Date.now(),
  quitDeadlineMs = policy.quitDeadlineMs ?? 8000,
  forceMarginMs = policy.quitForceMarginMs ?? 300,
  tickMs = TICK_MS,
}) {
  // Every deadline and interval below runs on this clock, so suspended time extends them (§3.5).
  const harnessActive = process.env[HARNESS_FLAG] === '1';
  const grace = harnessActive ? Number(process.env.JUSTSEARCH_SUPERVISOR_RESUME_GRACE_MS) || RESUME_GRACE_MS : RESUME_GRACE_MS;
  const suspendClock = createSuspendAwareClock({ monotonic, tickMs, resumeGraceMs: grace });
  const origin = monotonic(); let injected = [];
  if (harnessActive && process.env.JUSTSEARCH_SUPERVISOR_HARNESS_SUSPEND_FILE) {
    try { injected = JSON.parse(fs.readFileSync(process.env.JUSTSEARCH_SUPERVISOR_HARNESS_SUSPEND_FILE, 'utf8')); } catch {}
  }
  const applied = new Set();
  const clockNow = () => {
    injected.forEach((interval, index) => {
      for (const [kind, at] of [['suspend', interval.fromMs], ['resume', interval.toMs]]) {
        const key = `${index}/${kind}`;
        if (!applied.has(key) && monotonic() - origin >= at) { applied.add(key); suspendClock.notePower(kind, origin + at); }
      }
    });
    return suspendClock.now();
  };
  let predecessorExitAt = null; let lockedRetried = false;
  const s = {
    state: STATES.STARTING,
    phase: 'spawned',
    incarnation: 0,
    pid: null,
    restartCount: 0,
    apiPort: requestedApiPort,
    instanceId: null,
    lastExit: null,
    requestedReason: null,
    readyAt: null,
    firstReadyDone: false,
    // starting
    portDeadlineAt: null,
    readyDeadlineAt: null,
    startTimeout: null,
    healthInFlight: false,
    healthResult: null,
    nextHealthAt: 0,
    // running
    runningSince: null,
    consecutiveMisses: 0,
    hangInFlight: false,
    hangResult: null,
    nextHangAt: 0,
    essentialReadySince: null,
    essentialEpoch: null,
    harnessRequestFired: false,
    // stopping
    stopKind: null,
    stopReasonLabel: null,
    stopLedgerKey: null,
    stopDetail: null,
    requestDeadlineAt: null,
    requestDeadlineMs: null,
    forced: false,
    // restarting
    release: null,
    releasePending: false,
    cooldownMs: 0,
    cooldownUntil: null,
    // host quit
    pendingHostQuit: null,
    hostQuitActive: false,
    hostQuitDetail: null,
    outcome: null,
    retryAt: null,
    exhaustedUntil: null,
    manualRestart: false,
    replacementHeld: false,
    notReady: null,
    notReadySince: null,
    probeGeneration: 0,
  };

  let resolveFirstReady;
  let rejectFirstReady;
  const firstReady = new Promise((resolve, reject) => { resolveFirstReady = resolve; rejectFirstReady = reject; });
  // The caller awaits this; the guard only keeps an early terminal outcome from being reported as an
  // unhandled rejection before the caller has attached its handler.
  firstReady.catch(() => {});
  const quitWaiters = [];

  const snapshot = () => ({
    state: s.state,
    phase: s.state === STATES.STARTING ? s.phase : null,
    incarnation: s.incarnation,
    pid: s.pid,
    apiPort: s.apiPort,
    instanceId: s.instanceId,
    restartCount: s.restartCount,
    lastExit: s.lastExit,
    requestedReason: s.requestedReason,
    readyAt: s.readyAt,
    retryAt: s.retryAt,
    notReady: s.notReady,
  });

  const emit = (state, extra = {}) => {
    s.state = state;
    return publish({ ...snapshot(), ...extra }).catch((err) => log(`supervisor state write failed: ${err?.message ?? err}`));
  };

  const callHook = (name, payload) => {
    const hook = hooks[name];
    if (typeof hook !== 'function') return Promise.resolve(null);
    return Promise.resolve().then(() => hook(payload)).catch((err) => {
      log(`supervisor hook ${name} failed: ${err?.stack ?? err}`);
      return null;
    });
  };

  const startDeadlines = () => {
    const now = clockNow();
    const portMs = s.firstReadyDone ? Math.min(policy.startDeadlineMs, timeouts.initialPortMs) : timeouts.initialPortMs;
    s.portDeadlineAt = now + portMs;
    s.portTimeoutMs = portMs;
    s.readyTimeoutMs = s.firstReadyDone
      ? Math.min(policy.startDeadlineMs, timeouts.initialReadyMs) : timeouts.initialReadyMs;
    s.readyDeadlineAt = null;
  };

  /** T1 and T9: spawn the next incarnation and publish its pid before discovery begins (X09-F4). */
  const spawnIncarnation = async (cause) => {
    s.incarnation += 1;
    s.requestedReason = null;
    s.manualRestart = false;
    // A manifest left by any earlier incarnation can never be taken for this one's.
    s.predecessorInstanceId = s.instanceId ?? s.predecessorInstanceId;
    s.phase = 'spawned';
    s.startTimeout = null;
    s.healthInFlight = false;
    s.healthResult = null;
    s.probeGeneration += 1;
    s.notReady = null;
    s.notReadySince = null;
    s.essentialReadySince = null;
    s.essentialEpoch = null;
    s.nextHealthAt = 0;
    s.stopKind = null;
    s.stopReasonLabel = null;
    s.stopLedgerKey = null;
    s.stopDetail = null;
    s.requestDeadlineAt = null;
    s.forced = false;
    s.harnessRequestFired = s.incarnation !== 1;
    io.clearStaleDiscoveryFiles();
    const port = s.firstReadyDone ? (s.apiPort ?? requestedApiPort) : requestedApiPort;
    // Creating the Windows job and publishing its root are part of this same startup budget.
    // Arm before the synchronous spawn so that a slow host cannot reset the discovery deadline.
    startDeadlines();
    const spawned = actuator.spawnEngine({ n: s.incarnation, cause, apiPort: port,
      startupTimeoutMs: Math.max(0, s.portDeadlineAt - clockNow()) });
    s.pid = spawned.pid ?? null;
    await emit(STATES.STARTING);
    await callHook('onSpawned', { n: s.incarnation, pid: s.pid, cause });
  };

  /** Ask the Engine to stop through the request file and arm the deadline that covers it not reading it. */
  const requestShutdown = async (reason, { ledgerKey = reason, detail } = {}) => {
    if (s.state === STATES.STOPPING && s.requestedReason === reason) return;
    s.requestedReason = reason;
    s.state = STATES.STOPPING;
    const deadlineMs = policy.gracefulStopDeadlineMs;
    const deadlineEpochMs = wallClock() + deadlineMs;
    await actuator.recordIntent({ reasonKey: ledgerKey, ...(detail !== undefined ? { detail } : {}),
      channel: 'REQUEST_FILE', deadlineAt: deadlineEpochMs });
    actuator.writeShutdownRequest(reason, deadlineEpochMs);
    // Narrated because the file is transient by design — the Engine deletes it on consumption — so
    // without a line here the graceful arm leaves no trace at all when it works.
    log(`wrote a shutdown request: reason=${reason}, deadline in ${deadlineMs}ms, then a forced kill.`);
    s.stopKind = 'request';
    s.stopReasonLabel = reason;
    s.stopLedgerKey = ledgerKey;
    s.stopDetail = detail;
    s.requestDeadlineAt = clockNow() + deadlineMs;
    s.requestDeadlineMs = deadlineMs;
    await emit(STATES.STOPPING, { reason });
  };

  /** T5: the start deadline elapsed with the Engine alive and not ready (W09-F2). */
  const startDeadlineElapsed = async (kind) => {
    const seconds = Math.max(1, Math.round((kind === 'port' ? s.portTimeoutMs : s.readyTimeoutMs) / 1000));
    const message = kind === 'port'
      ? `Backend did not emit JUSTSEARCH_API_PORT=<port> within ${seconds}s (requested=${requestedApiPort}).`
      : `Backend did not answer at http://127.0.0.1:${s.apiPort}/api/health within ${seconds}s`;
    s.startTimeout = { kind, message, n: s.incarnation };
    log(`incarnation ${s.incarnation} did not come up: ${message}`);
    const action = decide({ event: 'start-deadline-elapsed', restartCount: s.restartCount, state: STATES.STARTING }, policy);
    if (action.action === ACTIONS.REQUEST_SHUTDOWN) {
      await requestShutdown(action.reason, { ledgerKey: 'start' });
    }
  };

  /** T3. */
  const enterRunning = async () => {
    const action = decide({ event: 'ready', state: STATES.STARTING }, policy);
    if (action.action !== ACTIONS.ENTER_RUNNING) return;
    s.readyAt = new Date(wallClock()).toISOString();
    s.runningSince = clockNow();
    s.notReadySince = s.runningSince;
    s.consecutiveMisses = 0;
    s.essentialReadySince = null;
    s.essentialEpoch = null;
    s.hangInFlight = false;
    s.hangResult = null;
    s.nextHangAt = clockNow() + policy.hangPollIntervalMs;
    // Published before any caller work (frontend, run record): a reader that waits for `running`
    // sees it as soon as the Engine answers, whatever the caller does next.
    await emit(STATES.RUNNING);
    const first = !s.firstReadyDone;
    s.firstReadyDone = true;
    const ready = { n: s.incarnation, pid: s.pid, apiPort: s.apiPort, instanceId: s.instanceId, first };
    if (first) resolveFirstReady(ready);
    else void callHook('onReady', ready);
  };

  const tickStarting = async () => {
    if (s.phase === 'spawned') {
      const found = io.readManifest(s.pid, s.predecessorInstanceId);
      if (found) {
        // T2: the manifest names this spawn's pid.
        s.apiPort = found.apiPort;
        s.instanceId = found.instanceId;
        s.phase = 'discovered';
        s.readyDeadlineAt = clockNow() + s.readyTimeoutMs;
        s.nextHealthAt = 0;
        await emit(STATES.STARTING);
      } else if (clockNow() >= s.portDeadlineAt) {
        await startDeadlineElapsed('port');
        return;
      }
    }
    if (s.phase !== 'discovered') return;
    if (s.healthResult && s.healthResult.n === s.incarnation) {
      const { ok } = s.healthResult;
      s.healthResult = null;
      if (ok) {
        await enterRunning();
        return;
      }
      s.nextHealthAt = clockNow() + READY_PROBE_GAP_MS;
    }
    if (clockNow() >= s.readyDeadlineAt) {
      await startDeadlineElapsed('ready');
      return;
    }
    if (!s.healthInFlight && clockNow() >= s.nextHealthAt) {
      s.healthInFlight = true;
      const n = s.incarnation;
      const generation = s.probeGeneration;
      io.probeHealth(s.apiPort, READY_PROBE_TIMEOUT_MS).then((ok) => ok, () => false).then((ok) => {
        if (n === s.incarnation && generation === s.probeGeneration) {
          s.healthInFlight = false;
          s.healthResult = { n, ok };
          actuator.wake();
        }
      });
    }
  };

  const tickRunning = async () => {
    // Harness-only host input exercises the production request path after readiness.
    const hostRequest = harness.requestReason;
    if (hostRequest && !s.harnessRequestFired && s.incarnation === 1
        && clockNow() - s.runningSince >= HARNESS_REQUEST_DELAY_MS && HARNESS_REQUEST_REASONS.includes(hostRequest)) {
      s.harnessRequestFired = true;
      await requestShutdown(hostRequest);
      return;
    }
    // Latch the admitted Engine's own close once; later manifest writes cannot extend it.
    const manifest = io.readRawManifest();
    const handoff = shutdownHandoffReason(manifest, s.pid, s.instanceId);
    if (handoff) {
      // Only terminal operator intents label the coming exit (a failed quit must not be restarted).
      s.requestedReason = exitHandoffReason(manifest, s.pid, s.instanceId);
      s.state = STATES.STOPPING;
      s.stopKind = 'handoff';
      s.stopReasonLabel = handoff;
      s.requestDeadlineAt = clockNow() + policy.gracefulStopDeadlineMs;
      s.requestDeadlineMs = policy.gracefulStopDeadlineMs;
      await emit(STATES.STOPPING, { reason: handoff });
      return;
    }
    if (s.hangResult && s.hangResult.n === s.incarnation && s.hangResult.instanceId === s.instanceId) {
      const result = s.hangResult;
      s.hangResult = null;
      if (result.alive) {
        s.consecutiveMisses = 0;
        const epoch = result.status ? io.essentialReadyEpoch(result.status) : null;
        if (epoch === null) {
          s.essentialReadySince = null;
          s.essentialEpoch = null;
          s.notReadySince ??= result.at;
          if (result.at - s.notReadySince >= policy.startDeadlineMs) {
            const index = result.status?.readiness?.engineComponents?.index;
            const safe = value => String(value ?? '').replace(/[^a-zA-Z0-9._ -]/g, '').slice(0, 256);
            const reason = index ? `Index: ${safe(index.state)} (${safe(index.reasonCode ?? index.reason ?? 'readiness not reported')})`
              : 'The Engine answers, but index status is unavailable';
            if (reason !== s.notReady) { s.notReady = reason; await emit(STATES.RUNNING); }
          }
        } else {
          s.notReadySince = null;
          if (s.notReady !== null) { s.notReady = null; await emit(STATES.RUNNING); }
          if (epoch !== s.essentialEpoch) {
            s.essentialEpoch = epoch;
            s.essentialReadySince = result.at;
          }
          if (result.at - s.essentialReadySince >= policy.stabilityWindowMs && s.restartCount > 0) {
            const action = decide({ event: 'stability-elapsed', restartCount: s.restartCount, state: s.state }, policy);
            if (action.action === ACTIONS.RESET_BUDGET) {
              s.restartCount = 0;
              await emit(STATES.RUNNING);
            }
          }
        }
      } else if (suspendClock.inResumeGrace()) {
        // A machine waking up answers late; a miss in the grace after a resume is not a hang (§3.5).
        s.essentialReadySince = null;
        s.essentialEpoch = null;
        s.consecutiveMisses = 0;
      } else {
        s.essentialReadySince = null;
        s.essentialEpoch = null;
        s.consecutiveMisses += 1;
        const action = decide({ event: 'health-miss', consecutiveMisses: s.consecutiveMisses,
          restartCount: s.restartCount, state: s.state }, policy);
        if (action.action === ACTIONS.REQUEST_SHUTDOWN) {
          log(`Engine missed ${s.consecutiveMisses} liveness polls while alive — treating as a hang and requesting shutdown.`);
          s.consecutiveMisses = 0;
          await requestShutdown('hang');
          return;
        }
      }
    }
    if (!s.hangInFlight && clockNow() >= s.nextHangAt) {
      s.hangInFlight = true;
      s.nextHangAt = clockNow() + policy.hangPollIntervalMs;
      const tag = { n: s.incarnation, instanceId: s.instanceId, port: s.apiPort, generation: s.probeGeneration };
      (async () => {
        const alive = await io.probeAlive(tag.port, HANG_PROBE_TIMEOUT_MS).catch(() => false);
        const status = alive ? await io.fetchStatus(tag.port, HANG_PROBE_TIMEOUT_MS).catch(() => null) : null;
        return { ...tag, alive, status, at: clockNow() };
      })().then((result) => {
        if (result.generation === s.probeGeneration && result.n === s.incarnation && result.instanceId === s.instanceId) {
          s.hangInFlight = false;
          s.hangResult = result;
          actuator.wake();
        }
      });
    }
  };

  /** T7 / T12b: the request deadline elapsed. */
  const requestDeadlineElapsed = async () => {
    s.requestDeadlineAt = null;
    if (s.forced) return;
    let ledgerKey;
    let detail;
    if (s.stopKind === 'handoff') {
      // Operator terminal intent remains terminal at the deadline; recovery closes become hangs.
      s.requestedReason = ['quit', 'upgrade'].includes(s.stopReasonLabel) ? s.stopReasonLabel : 'hang';
      log(`FORCED KILL: Engine-local ${s.stopReasonLabel} close exceeded its deadline.`);
      ledgerKey = s.requestedReason;
    } else if (s.stopKind === 'hostquit') {
      log(`FORCED KILL: the Engine ignored the quit request for ${s.requestDeadlineMs}ms.`);
      ledgerKey = 'quit';
      detail = s.hostQuitDetail;
    } else {
      const action = decide({ event: 'request-deadline-elapsed', requestedReason: s.requestedReason,
        restartCount: s.restartCount, state: s.state }, policy);
      if (action.action !== ACTIONS.FORCE_KILL) return;
      // The admission the request file makes: it is a request, never a guarantee.
      log(`FORCED KILL: the Engine ignored the ${s.stopReasonLabel} request for ${s.requestDeadlineMs}ms.`);
      ledgerKey = s.stopLedgerKey ?? s.requestedReason;
      detail = s.stopDetail;
    }
    s.forced = true;
    await actuator.forceKill(ledgerKey, detail !== undefined ? { detail } : {});
  };

  const finish = async (state, code, reason) => {
    await emit(state, { reason });
    s.outcome = { state, code, reason, lastExit: s.lastExit, firstReadyDone: s.firstReadyDone,
      startTimeout: s.startTimeout, hostQuit: s.hostQuitActive, incarnation: s.incarnation, pid: s.pid };
    if (!s.firstReadyDone) {
      const error = s.startTimeout && s.lastExit?.incarnation === s.startTimeout.n
        ? new Error(s.startTimeout.message)
        : new Error('Engine exited during discovery');
      if (!(s.startTimeout && s.lastExit?.incarnation === s.startTimeout.n)) {
        error.code = 'ENGINE_EXITED_DURING_DISCOVERY';
        error.details = { pid: s.pid, exitCode: s.lastExit?.code ?? null, signalCode: s.lastSignal ?? null,
          childReplaced: false, initialDiscovery: true };
      }
      error.outcome = s.outcome;
      rejectFirstReady(error);
    }
    for (const waiter of quitWaiters.splice(0)) waiter(s.outcome);
    await callHook('onTerminal', s.outcome);
    return s.outcome;
  };

  /** T4: the one exit path, in every phase. */
  const onExit = async (exit) => {
    const phase = s.state === STATES.STARTING ? 'STARTING' : s.state === STATES.RUNNING ? 'RUNNING' : 'STOPPING';
    s.lastSignal = exit.signal ?? null;
    // An external stop that asked the Engine itself (the `stop` CLI's graceful POST, tempdoc 819 §D)
    // leaves a marker: that exit was a host quit, so the loop stops instead of restarting.
    if (!s.hostQuitActive && typeof io.externalStopRequested === 'function' && io.externalStopRequested()) {
      s.hostQuitActive = true;
      s.requestedReason = 'quit';
    }
    // A quick failed operator close can exit before the handoff watcher sees it.
    if (!s.requestedReason) {
      s.requestedReason = exitHandoffReason(io.readRawManifest(), s.pid, s.instanceId);
    }
    const code = exit.code;
    if (exit.error) {
      const decision = { action: ACTIONS.EXHAUSTED, reason: `spawn_failed:${exit.error}`, exitClass: 'NON_TRANSIENT', counted: false };
      await actuator.recordExit({ exit, phase, decision });
      return finish(STATES.EXHAUSTED, null, `ENGINE_RESTART_EXHAUSTED:${decision.reason}`);
    }
    let decision = decide({ event: 'exit', exitCode: code, requestedReason: s.requestedReason,
      restartCount: s.restartCount, state: s.state }, policy);
    if (code === 2 && phase === 'STARTING' && predecessorExitAt !== null &&
        clockNow() - predecessorExitAt < policy.dataDirLockedWaitMs && !lockedRetried) {
      lockedRetried = true;
      decision = { action: ACTIONS.RESTART, reason: 'data_dir_locked', exitClass: 'NON_TRANSIENT',
        counted: false, cooldownMs: policy.dataDirLockedWaitMs };
    }
    predecessorExitAt = clockNow();

    // The record carries the class the BUDGET used, not the class the integer alone implies; `codeClass`
    // keeps the raw reading beside it.
    s.lastExit = {
      code,
      reason: decision.reason ?? describeExit(code, policy),
      class: decision.exitClass ?? classifyExit(code, policy),
      codeReason: describeExit(code, policy),
      codeClass: classifyExit(code, policy),
      counted: decision.counted === true,
      requestedReason: s.requestedReason,
      incarnation: s.incarnation,
      at: new Date(wallClock()).toISOString(),
    };
    const ledger = await actuator.recordExit({ exit, phase, decision });
    const terminal = decision.action === ACTIONS.STOP || decision.action === ACTIONS.EXHAUSTED;
    const reported = await callHook('onIncarnationExit', {
      n: s.incarnation, pid: s.pid, code, signal: exit.signal ?? null, phase, decision, lastExit: s.lastExit,
      ledger, terminal, firstReadyDone: s.firstReadyDone, hostQuit: s.hostQuitActive,
    });
    log(`Engine incarnation ${s.incarnation} exited ${code} (${s.lastExit.reason}, ${s.lastExit.class}); `
      + `decision=${decision.action}`
      + `${decision.counted ? ` counted ${s.restartCount + 1}/${policy.maxRestartAttempts}` : ''}`
      + `${ledger ? ` ledger=${ledger.exitClass}${ledger.forced ? '/forced' : ''}` : ''}`
      + `${reported?.engineLog ? ` engineLog=${reported.engineLog}` : ''}`);
    if (decision.action === ACTIONS.STOP) {
      return finish(STATES.STOPPED, code, decision.reason);
    }
    if (decision.action === ACTIONS.EXHAUSTED) {
      // Startup admission must settle: a runner that never became healthy exits with failure.
      // Only an admitted Engine receives unattended recovery after exhausting its budget.
      if (!s.firstReadyDone || decision.exitClass !== 'TRANSIENT') {
        return finish(STATES.EXHAUSTED, code, `ENGINE_RESTART_EXHAUSTED:${decision.reason}`);
      }
      await io.waitForRelease({ pid: exit.pid });
      s.pid = null;
      s.apiPort = null;
      s.predecessorInstanceId = s.instanceId;
      s.instanceId = null;
      s.exhaustedUntil = clockNow() + policy.exhaustedRetryDelayMs;
      s.retryAt = new Date(wallClock() + policy.exhaustedRetryDelayMs).toISOString();
      s.notReady = null;
      await emit(STATES.EXHAUSTED, { reason: `ENGINE_RESTART_EXHAUSTED:${decision.reason}` });
      return null;
    }
    if (s.hostQuitActive) {
      // Unreachable through `decide` (a quit request always stops), kept so a quit can never restart.
      return finish(STATES.STOPPED, code, 'quit');
    }
    if (decision.counted) s.restartCount += 1;
    s.cooldownMs = decision.cooldownMs ?? 0;
    s.release = null;
    s.releasePending = true;
    s.cooldownUntil = null;
    const deadPid = exit.pid;
    io.waitForRelease({ pid: deadPid }).then((release) => release, () => ({ released: false, waitedMs: 0 }))
      .then((release) => {
        s.releasePending = false;
        s.release = release;
        actuator.wake();
      });
    await emit(STATES.RESTARTING, { reason: decision.reason });
    return null;
  };

  /** T8-T9: the floor (handle release), then the cancellable cooldown, then the next spawn. */
  const tickRestarting = async () => {
    if (s.releasePending) return;
    if (s.cooldownUntil === null) {
      if (s.release && !s.release.released) {
        log(`proceeding with the restart after ${s.release.waitedMs}ms without a clean handle `
          + 'release — a boot that finds the data directory still locked will exit non-transient.');
      }
      s.cooldownUntil = clockNow() + s.cooldownMs;
    }
    if (clockNow() < s.cooldownUntil) return;
    // The caller may hold a restart back while it is still publishing the run that the last ready
    // started (its run record must exist before a replacement can be reported running).
    if (typeof hooks.restartReady === 'function' && !hooks.restartReady()) return;
    if (s.replacementHeld || s.hostQuitActive || s.pendingHostQuit) return;
    try { await spawnIncarnation('RESTART'); }
    catch (error) { return finish(STATES.EXHAUSTED, 1, `ENGINE_RESTART_EXHAUSTED:restart_failed:${error.message ?? error}`); }
  };

  /** T10, T10b, T11. */
  const takeHostQuit = async () => {
    const quit = s.pendingHostQuit;
    s.pendingHostQuit = null;
    if (s.outcome) return s.outcome;
    const already = s.hostQuitActive;
    s.hostQuitActive = true;
    if (!already) s.hostQuitDetail = quit.detail ?? null;
    const deadlineMs = Math.max(0, (quit.deadlineMs ?? quitDeadlineMs) - forceMarginMs);
    const deadlineEpochMs = wallClock() + (quit.deadlineMs ?? quitDeadlineMs);
    if ([STATES.RESTARTING, STATES.EXHAUSTED].includes(s.state)) {
      s.retryAt = null;
      // T11: no incarnation is alive; cancel the cooldown and spawn nothing.
      return finish(STATES.STOPPED, s.lastExit?.code ?? null, 'quit');
    }
    if (s.state === STATES.STOPPING) {
      if (s.stopKind === 'hostquit') return null; // one quit, one intent
      // T10b: the quit supersedes a restart or hang request; no second request is sent.
      s.requestedReason = 'quit';
      s.stopKind = 'hostquit';
      const quitAt = clockNow() + deadlineMs;
      s.requestDeadlineAt = s.requestDeadlineAt === null ? quitAt : Math.min(s.requestDeadlineAt, quitAt);
      s.requestDeadlineMs = deadlineMs;
      await actuator.recordIntent({ reasonKey: 'quit', detail: s.hostQuitDetail, channel: 'REQUEST_FILE',
        deadlineAt: deadlineEpochMs });
      await emit(STATES.STOPPING, { reason: 'quit' });
      return null;
    }
    // T10: starting or running.
    s.requestedReason = 'quit';
    s.stopKind = 'hostquit';
    s.stopReasonLabel = 'quit';
    s.state = STATES.STOPPING;
    s.requestDeadlineAt = clockNow() + deadlineMs;
    s.requestDeadlineMs = deadlineMs;
    const bound = s.firstReadyDone || s.phase === 'discovered';
    let sent = false;
    if (bound && Number.isInteger(s.apiPort) && s.apiPort > 0) {
      await actuator.recordIntent({ reasonKey: 'quit', detail: s.hostQuitDetail, channel: 'HTTP_SHUTDOWN',
        deadlineAt: deadlineEpochMs });
      const posted = await actuator.postShutdown(s.apiPort, 'quit', { deadlineAtEpochMs: deadlineEpochMs });
      sent = posted.ok;
    }
    if (!sent) {
      await actuator.recordIntent({ reasonKey: 'quit', detail: s.hostQuitDetail, channel: 'REQUEST_FILE',
        deadlineAt: deadlineEpochMs });
      actuator.writeShutdownRequest('quit', deadlineEpochMs);
    }
    log(`asked the Engine to quit${s.hostQuitDetail ? ` (${s.hostQuitDetail})` : ''} through `
      + `${sent ? 'POST /api/lifecycle/shutdown' : 'the request file'}; forced kill in ${deadlineMs}ms.`);
    await emit(STATES.STOPPING, { reason: 'quit' });
    return null;
  };

  const run = async () => {
    await actuator.reconcile();
    while (s.replacementHeld && !s.pendingHostQuit && !s.hostQuitActive) await actuator.waitOrWake(tickMs);
    if (s.pendingHostQuit || s.hostQuitActive) {
      s.hostQuitActive = true;
      return finish(STATES.STOPPED, null, 'quit');
    }
    try { await spawnIncarnation(s.incarnation === 0 ? 'INITIAL' : 'RESTART'); }
    catch (error) { return finish(STATES.EXHAUSTED, 1, `ENGINE_RESTART_EXHAUSTED:spawn_failed:${error.message ?? error}`); }
    for (;;) {
      const exit = actuator.pollExit();
      if (exit) {
        // eslint-disable-next-line no-await-in-loop
        const done = await onExit(exit);
        if (done) return done;
        continue;
      }
      if (harnessActive && typeof io.takeRecoveryCommand === 'function') {
        const command = io.takeRecoveryCommand();
        if (command) {
          let result;
          try {
            if (command.action === 'restart-quit-race') {
              let accepted = 0;
              for (let i = 0; i < 5; i++) { try { controller.restartEngine(); accepted++; } catch { /* automatic retry may have won */ } }
              void controller.hostQuit();
              let refused = false;
              try { controller.restartEngine(); } catch { refused = true; }
              io.recoveryCommandResult(command, { ok: true, accepted, quitRefusedRestart: refused });
              continue;
            }
            if (command.action === 'restart') controller.restartEngine();
            else if (command.action === 'hold') controller.setReplacementHold(true);
            else if (command.action === 'release') controller.setReplacementHold(false);
            else if (command.action === 'quit') void controller.hostQuit();
            else if (command.action === 'suspend' || command.action === 'resume') suspendClock.notePower(command.action);
            else throw new Error('Unsupported recovery command');
            result = { ok: true };
          } catch (error) { result = { ok: false, error: String(error.message ?? error) }; }
          io.recoveryCommandResult(command, result);
        }
      }
      if (s.pendingHostQuit) {
        // eslint-disable-next-line no-await-in-loop
        const done = await takeHostQuit();
        if (done) return done;
      }
      clockNow();
      if (suspendClock.takeResumed()) {
        s.essentialReadySince = null; s.essentialEpoch = null; s.consecutiveMisses = 0;
        s.probeGeneration += 1; s.hangResult = null; s.hangInFlight = false;
      }
      if (s.state === STATES.STOPPING && s.requestDeadlineAt !== null && clockNow() >= s.requestDeadlineAt) {
        // eslint-disable-next-line no-await-in-loop
        await requestDeadlineElapsed();
      }
      if (!actuator.exitObserved()) {
        // eslint-disable-next-line no-await-in-loop
        if (s.state === STATES.STARTING) await tickStarting();
        // eslint-disable-next-line no-await-in-loop
        else if (s.state === STATES.RUNNING) await tickRunning();
      }
      if (s.state === STATES.EXHAUSTED && !s.replacementHeld && !s.hostQuitActive && !s.pendingHostQuit
          && (s.manualRestart || (s.exhaustedUntil !== null && clockNow() >= s.exhaustedUntil))) {
        s.restartCount = 0;
        s.manualRestart = false;
        s.exhaustedUntil = null;
        s.retryAt = null;
        s.requestedReason = null;
        s.releasePending = false;
        s.cooldownUntil = clockNow();
        lockedRetried = false;
        await emit(STATES.RESTARTING);
      }
      if (s.state === STATES.RESTARTING && !s.pendingHostQuit) {
        // eslint-disable-next-line no-await-in-loop
        const done = await tickRestarting();
        if (done) return done;
      }
      // eslint-disable-next-line no-await-in-loop
      const suspended = await suspendClock.measureWait(() => actuator.waitOrWake(tickMs));
      if (suspended > 0) {
        s.consecutiveMisses = 0;
        s.essentialReadySince = null;
        s.essentialEpoch = null;
        s.probeGeneration += 1;
        s.hangInFlight = false;
        s.hangResult = null;
        log(`the machine was suspended for about ${Math.round(suspended)}ms; deadlines are extended by it and `
          + `health misses are not counted for ${RESUME_GRACE_MS}ms.`);
      }
    }
  };

  let running = null;
  const controller = {
    /** Starts the loop once; resolves with the terminal outcome. */
    run() {
      if (!running) running = run();
      return running;
    },
    firstReady,
    restartEngine() {
      if (s.hostQuitActive || s.pendingHostQuit || ['quit', 'upgrade'].includes(s.requestedReason)) {
        throw new Error('Quit or upgrade is in progress; restart refused');
      }
      if (s.replacementHeld) throw new Error('An update holds Engine replacement; restart refused');
      if (![STATES.EXHAUSTED, STATES.STOPPED].includes(s.state)) {
        throw new Error('The Engine is already starting or running; restart refused');
      }
      if (s.outcome) {
        s.outcome = null;
        s.state = STATES.STARTING;
        s.restartCount = 0;
        s.retryAt = null;
        s.exhaustedUntil = null;
        running = run();
      } else { s.manualRestart = true; }
      actuator.wake();
    },
    setReplacementHold(held) {
      if (s.hostQuitActive || s.pendingHostQuit) throw new Error('Quit is in progress');
      s.replacementHeld = held;
      actuator.wake();
    },
    /**
     * Post a host quit (stop, takeover, interruption, reap). Resolves with the loop's terminal
     * outcome; every caller of a quit waits on the same outcome, and only the loop acts.
     */
    hostQuit({ detail = null, deadlineMs = quitDeadlineMs } = {}) {
      if (s.outcome) { s.hostQuitActive = true; s.requestedReason = 'quit'; return Promise.resolve(s.outcome); }
      const waiter = new Promise((resolve) => quitWaiters.push(resolve));
      if (!s.pendingHostQuit && !s.hostQuitActive) s.pendingHostQuit = { detail, deadlineMs };
      actuator.wake();
      return waiter;
    },
    snapshot,
    outcome: () => s.outcome,
  };
  return controller;
}

module.exports = {
  createSupervisionLoop,
  createSuspendAwareClock,
  SUSPEND_GAP_MS,
  RESUME_GRACE_MS,
  TICK_MS,
  shutdownHandoffReason,
  exitHandoffReason,
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
