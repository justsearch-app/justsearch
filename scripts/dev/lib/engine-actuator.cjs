'use strict';
/**
 * The dev-runner's Engine actuator (Spec F §5.2, step 9): everything the supervision loop does to or
 * learns from the Engine process, in one object, so the loop in engine-supervisor.cjs owns the
 * decisions and nothing else.
 *
 * What it owns:
 * - spawning an incarnation with `JUSTSEARCH_ENGINE_INCARNATION=<runId>/<n>` and capturing its OS
 *   identity (pid, creation FILETIME, executable) without blocking the loop;
 * - the ONE exit observation per incarnation (`pollExit`), so a death in any phase reaches the same
 *   handler (T4) instead of one branch per phase;
 * - the engine records of the exit ledger (`engine-start`, `engine-intent`, `engine-exit`,
 *   `engine-owner-died`) written as writer role `dev-runner`, in order: a record queue makes the
 *   start record (which waits for the identity) precede every intent and exit of its incarnation;
 * - the forced kill: `terminate-process.ps1` with JS_ENGINE_STOP, identity-checked on one handle,
 *   and a fallback on the same identity-checked helper with code 1 (`codeDistinctive: false`);
 * - reconciliation at supervision start (T1): an Engine start left open by an earlier supervisor is
 *   closed as `engine-owner-died` after an OS identity check, and never killed.
 *
 * What it does not own: any decision. `decide` stays the contract's (engine-supervisor.cjs).
 */

const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { execFile, spawnSync } = require('child_process');
const { terminateWithCodeSync, inspectRecordedTree, inspectProcessTrees } = require('./recorded-termination.cjs');

const ledgerLib = require('./exit-ledger.cjs');
const { engineRow } = require('./engine-supervisor.cjs');

const TERMINATE_SCRIPT = path.join(__dirname, 'terminate-process.ps1');
const INCARNATION_ENV = 'JUSTSEARCH_ENGINE_INCARNATION';
/**
 * Spec F §4.3: the quit deadline and the margin the supervisor keeps for its own forced kill, read
 * from the contract the Engine budgets its close against (`quitDeadlineMs`, `quitForceMarginMs`).
 */
const QUIT_DEADLINE_MS = engineRow().policy.quitDeadlineMs;
const SHELL_FORCE_MARGIN_MS = engineRow().policy.quitForceMarginMs;
/** Exit code of `taskkill /F` and libuv's kill: the same as an uncaught exception, so not distinctive. */
const NON_DISTINCTIVE_KILL_CODE = 1;

/** Contract request reason -> ledger stop reason and detail (Spec B §6.2). */
const LEDGER_REASON = Object.freeze({
  quit: { reason: 'QUIT', detail: null },
  upgrade: { reason: 'UPGRADE', detail: null },
  restart: { reason: 'ENGINE_RESTART', detail: null },
  hang: { reason: 'TIMEOUT', detail: 'hang' },
  start: { reason: 'TIMEOUT', detail: 'start' },
});

/** The exit code an intent's reason expects from a cooperative close (Spec B §6.2). */
function expectedCodeFor(ledgerReason, policy) {
  const named = (name) => (policy?.exitCodes ?? []).find((e) => e.name === name)?.code;
  switch (ledgerReason) {
    case 'ENGINE_RESTART':
    case 'DEVICE_LOST':
      return named('REQUESTED_RESTART') ?? 4;
    case 'CRASH_RECOVERY':
      return named('ESCALATED_RESTART') ?? 5;
    default:
      return named('OK') ?? 0;
  }
}

/**
 * The ledger exit class of one Engine exit (Spec B §6.2 and R1-R5).
 *
 * REQUESTED iff an intent precedes the exit and the code is the one that intent expects
 * (cooperative) or our own terminate code (forced). The dev fallback kill leaves code 1, so a code-1
 * exit after a TERMINATE that fell back is REQUESTED with `codeDistinctive: false` (Spec B C6).
 * Anything else with a readable code is UNEXPECTED; an unreadable code is UNKNOWN, never a crash.
 *
 * @param {{code: number|null, intents: object[], selfIntent?: object|null, terminate?: object|null,
 *          policy: object}} input
 */
function classifyEngineExit({ code, intents = [], selfIntent = null, terminate = null, policy }) {
  // Cooperative intents, oldest first; the Engine's own self-intent counts as one (requestedBy SELF).
  // It is placed before the owner's, so the matching below, latest first, prefers the owner's: an
  // Engine that closes because its supervisor asked (a hang request it can still read) records its
  // own ENGINE_RESTART/hang, but the exit is the supervisor's TIMEOUT/hang (Spec B §6.2).
  const cooperative = intents.filter((i) => i.channel !== 'TERMINATE');
  if (selfIntent) {
    cooperative.unshift({ reason: selfIntent.reason, detail: selfIntent.detail ?? null,
      expectedCode: selfIntent.expectedCode, self: true });
  }
  const latest = cooperative[cooperative.length - 1] ?? intents[intents.length - 1] ?? null;
  const base = { reason: latest?.reason ?? null, detail: latest?.detail ?? null };
  if (!Number.isInteger(code)) {
    return { ...base, exitClass: 'UNKNOWN', forced: Boolean(terminate?.calledAt), codeDistinctive: false };
  }
  if (terminate?.calledAt && latest) {
    if (code === ledgerLib.JS_ENGINE_STOP && terminate.distinctive) {
      return { ...base, exitClass: 'REQUESTED', forced: true, codeDistinctive: true };
    }
    if (!terminate.distinctive && terminate.fallback && code === NON_DISTINCTIVE_KILL_CODE) {
      return { ...base, exitClass: 'REQUESTED', forced: true, codeDistinctive: false };
    }
  }
  // A superseding intent (a quit over a restart request, T10b) does not un-request the earlier one:
  // the exit is REQUESTED by whichever outstanding intent expects this code, the owner's latest
  // first and the Engine's own last.
  for (let i = cooperative.length - 1; i >= 0; i -= 1) {
    const intent = cooperative[i];
    const expected = Number.isInteger(intent.expectedCode) ? intent.expectedCode : expectedCodeFor(intent.reason, policy);
    if (code === expected) {
      return { reason: intent.reason, detail: intent.detail ?? null, exitClass: 'REQUESTED', forced: false,
        codeDistinctive: true };
    }
  }
  return { ...base, exitClass: 'UNEXPECTED', forced: Boolean(terminate?.calledAt), codeDistinctive: true };
}

/** FILETIME ticks (decimal string) -> epoch ms, or null. */
function fileTimeToMs(fileTime) {
  if (typeof fileTime !== 'string' || !/^[1-9][0-9]{0,19}$/.test(fileTime)) return null;
  const ms = Number(BigInt(fileTime) / 10000n) - 11644473600000;
  return Number.isFinite(ms) && ms >= ledgerLib.EARLIEST_KNOWN_MS ? ms : null;
}

/** The two FILETIMEs name the same birth under the shared rule (exit-ledger.cjs `sameBirth`). */
const { sameBirth } = ledgerLib;

/**
 * Look up the OS identity of some pids in one bounded CIM query.
 *
 * @returns {Promise<Map<number, {present: true, creationFileTime: string|null, executable: string|null}
 *   | {present: false}> | null>} null when the query failed: an identity lookup failure is unknown,
 *   never dead (S17).
 */
function lookupProcessIdentities(pids, { exec = execFile, platform = process.platform, timeoutMs = 5000 } = {}) {
  const wanted = [...new Set(pids.map(Number).filter((p) => Number.isInteger(p) && p > 0))];
  if (wanted.length === 0) return Promise.resolve(new Map());
  if (platform !== 'win32') return Promise.resolve(null);
  const filter = wanted.map((p) => `ProcessId=${p}`).join(' OR ');
  const command = [
    '[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)',
    `$rows = @(Get-CimInstance Win32_Process -Filter '${filter}' -ErrorAction Stop | ForEach-Object {`,
    '  [pscustomobject]@{ pid = [int]$_.ProcessId;',
    '    creation = $(if ($_.CreationDate) { $_.CreationDate.ToFileTimeUtc().ToString() } else { $null });',
    '    executable = $_.ExecutablePath; name = $_.Name } })',
    'ConvertTo-Json -InputObject $rows -Compress -Depth 2',
  ].join('\n');
  return new Promise((resolve) => {
    exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command],
      { encoding: 'utf8', windowsHide: true, timeout: timeoutMs, maxBuffer: 1024 * 1024 },
      (error, stdout) => {
        if (error) { resolve(null); return; }
        let rows;
        try {
          const parsed = JSON.parse(String(stdout).trim() || '[]');
          rows = Array.isArray(parsed) ? parsed : [parsed];
        } catch {
          resolve(null);
          return;
        }
        const result = new Map();
        for (const pid of wanted) result.set(pid, { present: false });
        for (const row of rows) {
          const pid = Number(row?.pid);
          if (!result.has(pid)) continue;
          result.set(pid, {
            present: true,
            creationFileTime: typeof row.creation === 'string' && /^[1-9]\d{0,19}$/.test(row.creation) ? row.creation : null,
            executable: typeof row.executable === 'string' && row.executable ? row.executable : null,
            name: typeof row.name === 'string' ? row.name : null,
          });
        }
        resolve(result);
      });
  });
}

/** Capture before yielding: libuv still holds the spawned child's handle and reserves its PID. */
function captureSpawnedIdentity(child, { exec = spawnSync, platform = process.platform } = {}) {
  if (platform !== 'win32' || !child?.pid || child.exitCode !== null || child.signalCode !== null) return null;
  const result = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
    path.join(__dirname, 'read-process-birth.ps1'), '-Pid', String(child.pid)],
  { encoding: 'utf8', windowsHide: true, timeout: 5000 });
  const birth = String(result.stdout ?? '').trim();
  return result.status === 0 && /^[1-9]\d{0,19}$/.test(birth)
    ? { pid: child.pid, creationFileTimeUtc: birth } : null;
}

/**
 * Terminate one process with a chosen code, only if it is still the recorded birth: the birth is
 * re-checked on the handle the termination uses. `tree` also ends its verified descendants, each
 * through its own handle (`terminate-process.ps1 -Tree`); `birthTick` is the births' common tick
 * (the microsecond, or the millisecond for a birth recorded in milliseconds).
 *
 * @returns {Promise<{ok: boolean, outcome: string, status: number|null}>}
 */
function terminateWithCode(pid, creationFileTime, code, { exec = execFile, timeoutMs = 10000, tree = false,
  birthTick = ledgerLib.BIRTH_TICKS.MICROSECOND } = {}) {
  return new Promise((resolve) => {
    exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', TERMINATE_SCRIPT,
      '-Pid', String(pid), '-CreationFileTimeUtc', String(creationFileTime), '-Code', String(code >>> 0),
      '-BirthTick', String(birthTick), ...(tree ? ['-Tree'] : [])],
    { encoding: 'utf8', windowsHide: true, timeout: timeoutMs },
    (error, stdout) => {
      let detail = {};
      try { detail = JSON.parse(String(stdout).trim().split(/\r?\n/).pop()) ?? {}; } catch { /* keep failed */ }
      const outcome = detail.outcome ?? 'failed';
      const status = error ? (Number.isInteger(error.code) ? error.code : null) : 0;
      resolve({ ...detail, ok: status === 0 && outcome === 'terminated', outcome, status });
    });
  });
}

/** Non-distinctive fallback, retaining its historical name for callers and exit accounting. */
function taskkillPid(pid, { platform = process.platform, creationFileTime, exec = spawnSync } = {}) {
  if (platform !== 'win32') return false; // no same-handle identity primitive on this platform
  return terminateWithCodeSync(pid, creationFileTime, NON_DISTINCTIVE_KILL_CODE, { exec }).ok;
}

/**
 * POST /api/lifecycle/shutdown, the cooperative quit channel when the Engine is bound. The body
 * names the quit's deadline and the incarnation it addresses (Spec F §4.3), so the Engine budgets
 * its close to finish before this supervisor's force.
 */
function postShutdown(apiPort, reason, timeoutMs = 2000, { deadlineAtEpochMs, incarnation } = {}) {
  return new Promise((resolve) => {
    const body = Buffer.from(JSON.stringify(shutdownPostBody(reason, { deadlineAtEpochMs, incarnation })), 'utf8');
    const req = http.request({
      hostname: '127.0.0.1', port: apiPort, path: '/api/lifecycle/shutdown', method: 'POST', timeout: timeoutMs,
      headers: { 'Content-Type': 'application/json', 'Content-Length': body.length },
    }, (res) => {
      res.resume();
      resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode });
    });
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, status: null }); });
    req.on('error', () => resolve({ ok: false, status: null }));
    req.end(body);
  });
}

function writeJsonAtomicSync(target, value) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const tmp = `${target}.${process.pid}.${crypto.randomUUID().slice(0, 8)}.tmp`;
  fs.writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  fs.renameSync(tmp, target);
}

/** The lifecycle POST's body: the reason, plus the quit's deadline and addressee when known. */
function shutdownPostBody(reason, { deadlineAtEpochMs, incarnation } = {}) {
  return {
    reason,
    ...(Number.isInteger(deadlineAtEpochMs) ? { deadlineAtEpochMs } : {}),
    ...(incarnation ? { incarnation } : {}),
  };
}

/**
 * Item B2's request file (shutdown-request.v1.json). Spec F §4.3 adds the incarnation it addresses
 * and a fresh request nonce: the Engine honours a request naming an incarnation only when it is its
 * own, so a file left by an earlier incarnation can never stop a later one. `requestNonce` is not
 * `nonce`, which is the upgrade receipt's and which the Engine refuses on this host channel.
 */
function writeShutdownRequestFileSync(dataDir, {
  reason, deadlineEpochMs, issuedBy = 'dev-runner', nonce = null, incarnation = null, requestNonce = null,
}) {
  if (!incarnation || (requestNonce !== null && !requestNonce.trim())) {
    throw new Error('An addressed shutdown request requires incarnation and requestNonce');
  }
  const target = path.join(dataDir, 'runtime', 'shutdown-request.v1.json');
  writeJsonAtomicSync(target, {
    schemaVersion: 1, reason, deadlineEpochMs, issuedBy,
    ...(nonce ? { nonce } : {}),
    ...(incarnation ? { incarnation, requestNonce: requestNonce ?? crypto.randomUUID() } : {}),
  });
  return target;
}

/**
 * Engine starts an earlier supervisor left open: an `engine-start` with no `engine-exit` and no
 * `engine-owner-died` for the same incarnation.
 */
function openEngineStarts(records) {
  const key = (inc) => (inc && typeof inc.session === 'string' && Number.isInteger(inc.n) ? `${inc.session}/${inc.n}` : null);
  const open = new Map();
  for (const record of records) {
    const k = key(record.line?.incarnation);
    if (!k) continue;
    if (record.type === 'engine-start') open.set(k, { start: record.line, lastIntent: null });
    else if (record.type === 'engine-intent' && open.has(k)) open.get(k).lastIntent = record.line;
    else if (record.type === 'engine-exit' || record.type === 'engine-owner-died') open.delete(k);
  }
  return [...open.values()];
}

/**
 * @param {object} options
 * @param {string} options.dataDir
 * @param {string} options.runId the ledger session of this supervisor's incarnations
 * @param {object} options.policy the loaded supervision policy (exit table for codeName)
 * @param {(n: number, apiPort: number, env: object) => import('child_process').ChildProcess} options.spawnChild
 */
function createEngineActuator({
  dataDir,
  runId,
  policy,
  spawnChild,
  ledger = null,
  lookupIdentities = lookupProcessIdentities,
  terminate = terminateWithCode,
  fallbackKill = taskkillPid,
  post = postShutdown,
  writeRequest = writeShutdownRequestFileSync,
  describeExit = (code) => (Number.isInteger(code) ? `unknown(${code})` : null),
  clock = () => Date.now(),
  writerStartedAt = Date.now(),
  log = (line) => process.stderr.write(`[dev-runner] ${line}\n`),
}) {
  const ledgerDir = ledgerLib.ledgerDirectory(dataDir);
  const writer = ledger ?? ledgerLib.createLedgerWriter({
    ledgerDir, family: 'engine', rotates: true,
    writer: { role: 'dev-runner', pid: process.pid, startedAt: writerStartedAt },
  });

  let current = null;
  const incarnations = new Map();
  let wakeResolve = null;
  let recordChain = Promise.resolve();

  const enqueue = (work) => {
    const next = recordChain.then(work).catch((err) => {
      log(`exit ledger record failed: ${err?.message ?? err}`);
      return null;
    });
    recordChain = next;
    return next;
  };

  const append = (type, fields) => {
    let ok = false;
    try { ok = writer.append(type, fields); } catch (err) { log(`exit ledger ${type} rejected: ${err?.message ?? err}`); }
    if (!ok) log(`exit ledger ${type} was not written durably (a gap is pending)`);
    return ok;
  };

  const incarnationRef = (n) => ({ session: runId, n });

  const wake = () => {
    if (wakeResolve) {
      const resolve = wakeResolve;
      wakeResolve = null;
      resolve();
    }
  };

  const actuator = {
    ledgerDir,
    writer,

    /**
     * T1: close what an earlier supervisor left open, then start this writer session (rotation
     * happens only after reconciliation has read the old file). Never kills anything.
     */
    async reconcile() {
      let read;
      try {
        read = ledgerLib.readLedger(ledgerDir, 'engine');
      } catch (err) {
        log(`exit ledger unreadable at supervision start: ${err?.message ?? err}`);
        read = { records: [] };
      }
      const open = openEngineStarts(read.records);
      const written = [];
      if (open.length > 0) {
        const identities = await lookupIdentities(open.map((o) => o.start.pid));
        for (const { start, lastIntent } of open) {
          let osCheck;
          const seen = identities ? identities.get(Number(start.pid)) : null;
          if (!identities || !seen) osCheck = 'IDENTITY_UNKNOWN';
          else if (!seen.present) osCheck = 'GONE';
          else if (!start.creationFileTime || !seen.creationFileTime) osCheck = 'IDENTITY_UNKNOWN';
          else osCheck = sameBirth(start.creationFileTime, seen.creationFileTime) ? 'ALIVE_MATCH' : 'GONE';
          const record = {
            incarnation: start.incarnation,
            basis: lastIntent ? 'OPEN_INTENT' : 'OPEN_START',
            osCheck,
            exitClass: osCheck === 'GONE' ? 'OWNER_DIED' : 'UNKNOWN',
            pid: start.pid,
          };
          append('engine-owner-died', record);
          written.push(record);
          log(`exit ledger: Engine ${start.incarnation.session}/${start.incarnation.n} (pid ${start.pid}) was left `
            + `open by an earlier supervisor; recorded ${record.exitClass} (${osCheck})`);
        }
      }
      writer.startSession();
      return written;
    },

    /**
     * Spawn incarnation `n`. The exit listener is installed here, at spawn, so no exit can be missed;
     * it only stores the observation and wakes the loop. `pollExit` is its one consumer.
     */
    spawnEngine({ n, cause, apiPort }) {
      try { fs.unlinkSync(path.join(dataDir, "runtime", "shutdown-request.v1.json")); } catch (err) { if (err.code !== "ENOENT") log(`stale shutdown request cleanup failed: ${err.message}`); }
      const env = { [INCARNATION_ENV]: `${runId}/${n}` };
      const child = spawnChild(n, apiPort, env);
      const spawnedAt = clock();
      const state = {
        n, cause, child, pid: child.pid ?? null, spawnedAt,
        exit: null, exitConsumed: false,
        identity: null, intents: [], terminate: null, phase: 'STARTING',
      };
      child.once('exit', (code, signal) => {
        state.exit = { code: Number.isInteger(code) ? code : null, signal: signal ?? null, observedAt: clock() };
        wake();
      });
      child.once('error', (err) => {
        // A spawn that never ran: there is no exit code to read, which is UNKNOWN, not a crash.
        if (!state.exit) {
          state.exit = { code: null, signal: null, observedAt: clock(), error: err?.message ?? String(err) };
          wake();
        }
      });
      state.identityPromise = (state.pid
        ? lookupIdentities([state.pid]).then((map) => {
          const seen = map?.get(state.pid);
          state.identity = !state.exit && child.exitCode == null && child.signalCode == null && seen?.present
            ? { creationFileTime: seen.creationFileTime, executable: seen.executable }
            : { creationFileTime: null, executable: null, unknown: !map || Boolean(state.exit) };
          return state.identity;
        }).catch(() => (state.identity = { creationFileTime: null, executable: null, unknown: true }))
        : Promise.resolve(state.identity = { creationFileTime: null, executable: null, unknown: true }));
      incarnations.set(n, state);
      current = state;
      enqueue(async () => {
        const identity = await state.identityPromise;
        const startedMs = fileTimeToMs(identity.creationFileTime);
        append('engine-start', {
          incarnation: incarnationRef(n),
          pid: state.pid ?? 0,
          creationFileTime: identity.creationFileTime,
          processStartedAt: startedMs === null ? null : new Date(startedMs),
          executable: String(identity.executable ?? child.spawnfile ?? '').slice(0, 1024),
          cause,
          supervisorKind: 'dev-runner',
        });
      });
      return state;
    },

    current() {
      return current;
    },

    /** The one place an exit is consumed: returns it once, then null. */
    pollExit() {
      if (!current || !current.exit || current.exitConsumed) return null;
      current.exitConsumed = true;
      return { n: current.n, pid: current.pid, ...current.exit };
    },

    exitObserved() {
      return Boolean(current?.exit);
    },

    /** Sleep up to `ms`, returning early on an exit or a `wake()` (HostQuit, a probe result). */
    waitOrWake(ms) {
      return new Promise((resolve) => {
        const timer = setTimeout(() => { if (wakeResolve === done) wakeResolve = null; resolve(); }, ms);
        const done = () => { clearTimeout(timer); resolve(); };
        wakeResolve = done;
        if (current?.exit && !current.exitConsumed) wake();
      });
    },

    wake,

    /**
     * Record an intent for the current incarnation BEFORE acting on it.
     *
     * @param {{reasonKey: string, detail?: string|null, mode?: string, channel: string,
     *          deadlineAt?: number|null, reason?: string}} intent
     */
    recordIntent({ reasonKey, reason, detail, mode = 'COOPERATIVE', channel, deadlineAt = null }) {
      const state = current;
      if (!state) return null;
      const mapped = LEDGER_REASON[reasonKey] ?? { reason: reason ?? 'QUIT', detail: null };
      const intent = {
        intentId: crypto.randomUUID(),
        reason: reason ?? mapped.reason,
        detail: detail !== undefined ? detail : mapped.detail,
        mode,
        channel,
        requestedAt: clock(),
        deadlineAt,
      };
      state.intents.push(intent);
      enqueue(() => append('engine-intent', {
        incarnation: incarnationRef(state.n),
        intentId: intent.intentId,
        reason: intent.reason,
        detail: intent.detail,
        mode: intent.mode,
        channel: intent.channel,
        requestedAt: new Date(intent.requestedAt),
        deadlineAt: Number.isFinite(intent.deadlineAt) ? new Date(intent.deadlineAt) : null,
      }));
      return intent;
    },

    /**
     * The cooperative request file, addressed to the current incarnation; the Engine deletes it on
     * consumption.
     */
    writeShutdownRequest(reason, deadlineEpochMs) {
      if (!current) return null; // no Engine owns a request before spawn or after reap
      return writeRequest(dataDir, {
        reason, deadlineEpochMs,
        ...(current ? { incarnation: `${runId}/${current.n}`, requestNonce: crypto.randomUUID() } : {}),
      });
    },

    /** The cooperative HTTP quit, used when the Engine is bound (T10), with its deadline. */
    postShutdown(apiPort, reason, { deadlineAtEpochMs } = {}) {
      return post(apiPort, reason, undefined, {
        deadlineAtEpochMs,
        ...(current ? { incarnation: `${runId}/${current.n}` } : {}),
      });
    },

    /**
     * The forced kill (T7). TerminateProcess with JS_ENGINE_STOP, identity-checked on one handle; if
     * that cannot run, retry with the non-distinctive code on an identity-checked handle.
     */
    async forceKill(reasonKey, { reason, detail } = {}) {
      const state = current;
      if (!state || state.exit) return { attempted: false, reason: 'already-exited' };
      if (state.terminate) return { attempted: false, reason: 'already-forced' };
      const prior = state.intents.filter((i) => i.channel !== 'TERMINATE').pop();
      actuator.recordIntent({
        reasonKey,
        reason: reason ?? prior?.reason,
        detail: detail !== undefined ? detail : (prior?.detail ?? undefined),
        mode: 'NOW',
        channel: 'TERMINATE',
      });
      state.terminate = { calledAt: clock(), distinctive: false, fallback: false, outcome: null };
      const identity = state.identity ?? await Promise.race([
        state.identityPromise, new Promise((r) => setTimeout(() => r(null), 3000))]);
      if (state.exit) {
        state.terminate.outcome = 'already-exited';
        return { attempted: false, reason: 'already-exited' };
      }
      if (identity?.creationFileTime && state.pid) {
        const result = await terminate(state.pid, identity.creationFileTime, ledgerLib.JS_ENGINE_STOP);
        state.terminate.outcome = result.outcome;
        if (result.ok) {
          state.terminate.distinctive = true;
          return { attempted: true, distinctive: true };
        }
        if (result.outcome === 'mismatch' || result.outcome === 'gone') {
          // The recorded birth is gone; the exit is on its way (or was). Nothing else to kill.
          return { attempted: true, distinctive: false, outcome: result.outcome };
        }
      }
      if (state.exit || !state.pid) return { attempted: false, reason: 'already-exited' };
      state.terminate.fallback = true;
      const killed = fallbackKill(state.pid, { creationFileTime: identity?.creationFileTime });
      log(`forced kill used the identity-checked fallback for pid ${state.pid}: ${killed ? 'terminated' : 'incomplete'} (the exit code will not be distinctive)`);
      return { attempted: true, distinctive: false, fallback: true, killed };
    },

    /**
     * T4's record: one `engine-exit` per incarnation, with the ledger class beside the contract's.
     */
    async recordExit({ exit, phase, decision }) {
      const state = incarnations.get(exit.n);
      if (!state || state.exitRecorded) return null;
      state.exitRecorded = true;
      let selfIntent = null;
      try {
        const identity = state.identity ?? {};
        selfIntent = ledgerLib.readLedger(ledgerDir, 'engine').records
          .filter((r) => r.type === 'engine-self-intent' && Number(r.line?.engine?.pid) === state.pid
            && (!identity.creationFileTime || !r.line?.engine?.creationFileTime
              || sameBirth(String(r.line.engine.creationFileTime), identity.creationFileTime)))
          .map((r) => r.line).pop() ?? null;
      } catch { /* the owner's intents still classify */ }
      const classified = classifyEngineExit({
        code: exit.code, intents: state.intents, selfIntent, terminate: state.terminate, policy,
      });
      const fields = {
        incarnation: incarnationRef(state.n),
        exitClass: classified.exitClass,
        reason: classified.reason,
        detail: classified.detail,
        code: exit.code,
        codeName: Number.isInteger(exit.code) ? String(describeExit(exit.code)).slice(0, 64) : null,
        exitedAt: null,
        observedAt: new Date(exit.observedAt),
        terminateCalledAt: state.terminate?.calledAt ? new Date(state.terminate.calledAt) : null,
        forced: classified.forced,
        phase,
        codeDistinctive: classified.codeDistinctive,
        contract: {
          action: decision.action,
          exitClass: decision.exitClass ?? 'TRANSIENT',
          counted: decision.counted === true,
        },
        containment: 'LIBUV_JOB',
        jobEmpty: null,
      };
      await enqueue(() => append('engine-exit', fields));
      return { ...classified, fields };
    },

    /** Wait for every queued record to be appended. */
    flush() {
      return recordChain;
    },
  };
  return actuator;
}

/**
 * How a `stop` CLI's fallback can end the Engine, and the exit code each ending leaves:
 * - `cooperative`: it exited on the CLI's HTTP quit; the code is the quit's expected code, but the
 *   CLI is not the parent and cannot read it, so only the census knows it;
 * - `runner`: it ended with the runner the CLI killed, because it was in that runner's libuv job;
 *   a job closed with KILL_ON_JOB_CLOSE ends its processes with code 0;
 * - `terminate`: the CLI's identity-checked TerminateProcess with JS_ENGINE_STOP;
 * - `taskkill`: `taskkill /F`, code 1.
 */
const CLI_ENGINE_ENDINGS = Object.freeze({
  cooperative: { forced: false, code: null },
  runner: { forced: true, code: 0 },
  terminate: { forced: true, code: ledgerLib.JS_ENGINE_STOP },
  taskkill: { forced: true, code: NON_DISTINCTIVE_KILL_CODE },
  'identity-fallback': { forced: true, code: NON_DISTINCTIVE_KILL_CODE },
});

/**
 * Spec F §5.3: the Engine records a `stop` CLI writes, as writer role `dev-cli`, when the runner did
 * not answer and the CLI acts on the stack itself. Each intent is written BEFORE the action it
 * announces; the exit once the recorded Engine is gone, however it ended (`CLI_ENGINE_ENDINGS`).
 * Nothing is written for an incarnation that already has an `engine-exit`: one exit record per
 * incarnation, so a runner that recorded it first keeps it.
 *
 * The exit code is the stop census's identity-bound observation when it has one
 * (`observedBy: 'census'`), otherwise the code the ending leaves. A code other than the ending's is
 * UNEXPECTED: a crash during the stop stays a crash. A cooperative quit with no observed code is
 * UNKNOWN.
 *
 * @param {{dataDir: string, incarnation: {session: string, n: number}, policy?: object}} input
 */
function createCliEngineRecorder({
  dataDir, incarnation, policy = null, clock = () => Date.now(), describeExit = (code) => `unknown(${code})`,
}) {
  const ledgerDir = dataDir ? ledgerLib.ledgerDirectory(dataDir) : null;
  const validIncarnation = Boolean(incarnation && typeof incarnation.session === 'string'
    && Number.isInteger(incarnation.n));
  let writer = null;
  let exitWritten = false;

  const exitRecorded = () => {
    if (exitWritten) return true;
    let records = [];
    try { records = ledgerLib.readLedger(ledgerDir, 'engine').records; } catch { /* write anyway */ }
    return records.some((r) => r.type === 'engine-exit'
      && r.line?.incarnation?.session === incarnation.session && r.line?.incarnation?.n === incarnation.n);
  };
  const refusal = () => {
    if (!ledgerDir) return 'no data dir recorded for this run';
    if (!validIncarnation) return 'no incarnation recorded for this run';
    if (exitRecorded()) return 'the incarnation already has its exit record';
    return null;
  };
  const append = (type, fields) => {
    if (!writer) {
      writer = ledgerLib.createLedgerWriter({ ledgerDir, family: 'engine', clock,
        writer: { role: 'dev-cli', pid: process.pid, startedAt: clock() } });
      writer.startSession();
    }
    return writer.append(type, fields);
  };

  return {
    /** Before acting: `COOPERATIVE` over `HTTP_SHUTDOWN`, or `NOW` over `TERMINATE`. */
    intent({ reason = 'QUIT', detail = null, mode, channel, deadlineAt = null }) {
      const why = refusal();
      if (why) return { written: false, why };
      const durable = append('engine-intent', {
        incarnation, intentId: crypto.randomUUID(), reason, detail, mode, channel,
        requestedAt: new Date(clock()), deadlineAt: Number.isFinite(deadlineAt) ? new Date(deadlineAt) : null,
      });
      return { written: true, durable, type: 'engine-intent', mode, channel };
    },

    /**
     * @param {{endedBy: keyof CLI_ENGINE_ENDINGS, reason?: string, detail?: string|null,
     *          terminateCalledAt?: number|null, observed?: {exitCode?: number, atMs?: number}|null,
     *          phase?: string}} input
     */
    exit({ endedBy, reason = 'QUIT', detail = null, terminateCalledAt = null, observed = null, phase = 'STOPPING' }) {
      const ending = CLI_ENGINE_ENDINGS[endedBy];
      if (!ending) throw new TypeError(`unknown Engine ending: ${endedBy}`);
      const why = refusal();
      if (why) return { written: false, why };
      const observedCode = Number.isInteger(observed?.exitCode) ? observed.exitCode : null;
      const code = observedCode ?? ending.code;
      const expected = endedBy === 'cooperative' ? expectedCodeFor(reason, policy) : ending.code;
      const exitClass = !Number.isInteger(code) ? 'UNKNOWN' : code === expected ? 'REQUESTED' : 'UNEXPECTED';
      const codeDistinctive = exitClass === 'UNEXPECTED'
        || (exitClass === 'REQUESTED' && (endedBy === 'terminate' || endedBy === 'cooperative'));
      const fields = {
        incarnation,
        exitClass,
        reason,
        detail,
        code,
        codeName: Number.isInteger(code) ? String(describeExit(code, policy)).slice(0, 64) : null,
        exitedAt: observedCode !== null && Number.isFinite(observed.atMs) ? new Date(observed.atMs) : null,
        observedAt: new Date(clock()),
        terminateCalledAt: Number.isFinite(terminateCalledAt) ? new Date(terminateCalledAt) : null,
        forced: ending.forced,
        phase,
        codeDistinctive,
        contract: { action: 'stop', exitClass: 'REQUESTED', counted: false },
        observedBy: observedCode !== null ? 'census' : 'dev-cli',
      };
      const durable = append('engine-exit', fields);
      exitWritten = true;
      return { written: true, durable, type: 'engine-exit', endedBy, exitClass, code, observedBy: fields.observedBy };
    },
  };
}

module.exports = {
  INCARNATION_ENV,
  QUIT_DEADLINE_MS,
  SHELL_FORCE_MARGIN_MS,
  NON_DISTINCTIVE_KILL_CODE,
  LEDGER_REASON,
  TERMINATE_SCRIPT,
  expectedCodeFor,
  classifyEngineExit,
  fileTimeToMs,
  sameBirth,
  openEngineStarts,
  lookupProcessIdentities,
  captureSpawnedIdentity,
  inspectRecordedTree,
  inspectProcessTrees,
  terminateWithCode,
  taskkillPid,
  terminateWithCodeSync,
  postShutdown,
  shutdownPostBody,
  writeShutdownRequestFileSync,
  createEngineActuator,
  CLI_ENGINE_ENDINGS,
  createCliEngineRecorder,
};
