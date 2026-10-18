#!/usr/bin/env node
/**
 * Tempdoc 618 §7: serve THIS worktree's frontend against the already-running dev backend.
 *
 * The MCP dev-runner serves Vite from the MAIN checkout, so a worktree's changed FE is never what
 * the running stack serves — to see it in a real browser you otherwise hand-roll a second Vite,
 * fight port collisions, and risk pointing at the wrong code (§7 cost ~15 turns). This helper:
 *   - picks a free port (from 5174; --strictPort so Vite fails fast instead of drifting silently);
 *   - pins the API proxy at the running backend (VITE_JUSTSEARCH_API_PORT; auto-detected from the
 *     shared dev-runner lease, or pass --api-port). Read-only: it BORROWS the running backend, it
 *     does NOT start one — so it works even when the stack is owned by another session;
 *   - serves from THIS worktree's modules/ui-web (Vite's cwd), so the served code IS the worktree's
 *     code by construction. It prints the branch + path so that correspondence is unmistakable
 *     (the 606 running↔worktree provenance check, applied to the FE).
 *
 * Usage (run from inside the worktree):
 *   node scripts/dev/serve-worktree-fe.cjs [--api-port <p>] [--port <p>]
 *
 * ONE serve contract, two consumer paths (tempdoc 615 §27/§29). This is the HUMAN path
 * (foreground, eyes-are-the-mount-check). The AUTOMATED path is the screenshot harness's
 * `scripts/jseval/jseval/ui_shot.py` (`_start_vite_server`), which adds detached spawn +
 * captured stderr + an app-mounted readiness gate (`ui_check._await_app_ready`) on top of
 * the same contract: FREE-PORT scan · `--strictPort` · NEUTRAL vite (no `--mode mock`) ·
 * provenance. They are two native paths bound by this contract (NOT one cross-language
 * process — that would add the very fragility §27 removes); drift is guarded by
 * `scripts/jseval/tests/test_ui_serve.py`.
 *
 * Tempdoc 861 W3 [A3] — registers a spawn record in the `agent-spawns/` scope so a leaked Vite is
 * reapable and a build-conflict hint can name it. Vite's own `node vite.js` is started directly
 * (no `npx`/`cmd.exe` shim); on win32 it runs inside its own job (scripts/dev/lib/job-run.cjs), so
 * `child.pid` is the job's host and the stop ends Vite and everything Vite starts. The record is
 * still built from the port's actual LISTENER, resolved AFTER the readiness gate, never from
 * `child.pid`. Registration is best-effort and never blocks or fails the serve: a bookkeeping
 * failure must not stop a human from seeing their FE.
 */
'use strict';
const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawnSync } = require('child_process');
const engineActuator = require('./lib/engine-actuator.cjs');
const jobRun = require('./lib/job-run.cjs');

const { resolveListenerPid } = require('./lib/port-owner.cjs');
const { readProcessTable, normalizeCreationTime } = require('./lib/process-identity.cjs');
const { resolveAgentIdentity } = require('./lib/agent-identity.cjs');
const { ownerBlockFor } = require('./lib/owner-presence.cjs');
const {
  resolveAgentSpawnsRegisterDir,
  buildAgentSpawnRecord,
  writeAgentSpawnRecord,
  removeAgentSpawnRecord,
  resolveNodeModulesRealPath,
} = require('./lib/agent-spawn-record.cjs');

const repoRoot = path.resolve(__dirname, '..', '..');
const uiWebDir = path.join(repoRoot, 'modules', 'ui-web');

// A long-lived FOREGROUND session ceiling (861 §6.2's lease), not a renewed lease-on-use loop:
// unlike ui-shot (invoked repeatedly across many captures), this is one process that runs until
// the human Ctrl-C's it, so there is no natural "reuse" event to renew against. Four hours covers
// an ordinary dev session; the §6.3 matrix already treats "own session, lease lapsed" as always
// reapable by that SAME session and "other session, lapsed but owner activity fresh" as
// contention, not garbage — so a generous-but-finite duration here costs nothing.
const AGENT_SPAWN_LEASE_DURATION_SEC = 4 * 60 * 60;

function argVal(flag, argv = process.argv) {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : null;
}

// Resolve the main repo root (state lives there even when we run inside a worktree).
function mainRepoRoot() {
  try {
    const gitPath = path.join(repoRoot, '.git');
    const st = fs.statSync(gitPath);
    if (st.isFile()) {
      const m = fs.readFileSync(gitPath, 'utf8').trim().match(/^gitdir:\s*(.+)$/);
      if (m) return path.resolve(repoRoot, m[1], '..', '..', '..');
    }
  } catch { /* not a worktree */ }
  return repoRoot;
}

function detectBackendPort(argv = process.argv) {
  const explicit = argVal('--api-port', argv);
  if (explicit) return Number(explicit);
  try {
    const stateRoot = path.join(mainRepoRoot(), 'tmp', 'dev-runner');
    const active = JSON.parse(fs.readFileSync(path.join(stateRoot, 'active.json'), 'utf8'));
    if (active?.runId) {
      const run = JSON.parse(fs.readFileSync(path.join(stateRoot, 'runs', active.runId, 'run.json'), 'utf8'));
      // run.json exposes the bound port as apiPortActual (+ apiBaseUrl); older shapes used apiPort/ports.
      const port =
        run?.apiPortActual ??
        run?.apiPort ??
        run?.ports?.apiPort ??
        (run?.apiBaseUrl ? Number(new URL(run.apiBaseUrl).port) : null);
      if (port) return Number(port);
    }
  } catch { /* fall through — Vite auto-discovers the manifest if no env pin */ }
  return null;
}

// A connect-probe is more reliable than a bind-probe on Windows: a wildcard bind can coexist with
// a listener already on [::1], so a bind-probe falsely reports "free" for a port Vite (which binds
// `localhost`) will then fail on. Probe BOTH loopback stacks — free only if neither answers.
function portInUse(port, host) {
  return new Promise((resolve) => {
    const sock = net.connect({ port, host });
    sock.setTimeout(400);
    sock.once('connect', () => { sock.destroy(); resolve(true); });
    sock.once('timeout', () => { sock.destroy(); resolve(false); });
    sock.once('error', () => resolve(false)); // ECONNREFUSED / unreachable → nothing listening here
  });
}

async function isFree(port) {
  const [v4, v6] = await Promise.all([portInUse(port, '127.0.0.1'), portInUse(port, '::1')]);
  return !v4 && !v6;
}

async function pickPort(start) {
  for (let p = start; p < start + 50; p++) {
    if (await isFree(p)) return p;
  }
  throw new Error(`no free port in ${start}..${start + 50}`);
}

/**
 * Poll until something answers on `port`, or give up at `timeoutMs`. Resolves `true`/`false`.
 *
 * Probes BOTH loopback stacks, for the same reason `isFree` above does: Vite binds `localhost`,
 * which on Windows resolves to `::1` FIRST, so the listener is IPv6-only and a `127.0.0.1`-only
 * probe reports "never started accepting connections" against a server that is already serving.
 * That false negative skipped registration entirely, leaving a Vite that `agent-spawn-sweep.cjs`
 * could not reap and two reviewers had to `taskkill` by hand.
 */
async function waitForPortListening(port, { timeoutMs = 30_000, intervalMs = 250, probe = portInUse } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const [v4, v6] = await Promise.all([probe(port, '127.0.0.1'), probe(port, '::1')]);
    if (v4 || v6) return true;
    if (Date.now() >= deadline) return false;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

/**
 * Kill the spawned server and everything it started. On win32 `identity` is the job run from
 * job-run.cjs (`child.pid` is its host): the stop ends the host on the handle that shows its
 * recorded birth, so Windows ends every member of its job, and then verifies each recorded member
 * has ended. A bare `{ pid, creationFileTimeUtc }` ends only that process. Never parent links.
 * Returns `{ ok }` or `{ ok: false, reason }`; never throws.
 */
function killSpawnTree(child, identity) {
  if (!child || !child.pid) return { ok: false, reason: 'no child process to kill' };
  try {
    if (identity?.recordPath) {
      if (identity.host?.pid !== child.pid) return { ok: false, reason: 'no recorded child identity' };
      const res = jobRun.stopJobRunSync(identity, { code: jobRun.NON_DISTINCTIVE_KILL_CODE });
      return { ...res, ...(!res.ok ? { reason: `recorded run stop ${res.outcome}` } : {}) };
    }
    if (process.platform === 'win32') {
      if (identity?.pid !== child.pid) return { ok: false, reason: 'no recorded child identity' };
      const res = engineActuator.terminateWithCodeSync(child.pid, identity.creationFileTimeUtc,
        engineActuator.NON_DISTINCTIVE_KILL_CODE);
      return { ...res, ...(!res.ok ? { reason: `identity-checked termination ${res.outcome}` } : {}) };
    }
    child.kill('SIGTERM');
    return { ok: true };
  } catch (err) {
    return { ok: false, reason: err.message };
  }
}

/**
 * 861 [A3] — the record's identity triple, resolved from the OS rather than trusted from the
 * spawn call. `pid` is the port's actual listener (never `child.pid`); `creationFileTimeUtc` and
 * `cmdlineFingerprint` come from the SAME process-table row, so the three conjuncts describe one
 * observation rather than being stitched from two.
 *
 * Returns `{ ok: true, pid, creationFileTimeUtc, cmdlineFingerprint } | { ok: false, reason }`.
 */
function resolveListenerIdentity(port, { resolvePid = resolveListenerPid, table = readProcessTable } = {}) {
  const owner = resolvePid(port);
  if (!owner.ok) return { ok: false, reason: `could not resolve the listener on port ${port}: ${owner.reason}` };
  const snapshot = table();
  if (!snapshot.ok) return { ok: false, reason: `could not read the process table: ${snapshot.reason}` };
  const row = snapshot.table.find((r) => Number(r?.ProcessId) === owner.pid);
  if (!row) return { ok: false, reason: `listener pid ${owner.pid} vanished before its identity could be read` };
  const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
  const processIdentity = row.processIdentity ?? null;
  if (creationFileTimeUtc === null && !processIdentity) {
    return { ok: false, reason: `listener pid ${owner.pid} has no readable creation time` };
  }
  const cmdline = typeof row.CommandLine === 'string' ? row.CommandLine : '';
  // The Vite server for THIS port is uniquely identified by its own --port flag in its command
  // line (mirrors ui_shot.py's `_pid_is_our_vite`); safe as the third identity conjunct because
  // the second conjunct (exact creation-time equality) is what makes a substring match sound.
  const cmdlineFingerprint = `--port ${port}`;
  if (!cmdline.includes(cmdlineFingerprint)) {
    return { ok: false, reason: `listener pid ${owner.pid}'s command line does not contain ${JSON.stringify(cmdlineFingerprint)}; refusing to record an unverified identity` };
  }
  return { ok: true, pid: owner.pid, creationFileTimeUtc, ...(processIdentity ? { processIdentity } : {}), cmdlineFingerprint };
}

/**
 * 861 W3 F5 — Windows FILETIME (100ns ticks since 1601-01-01) to Unix epoch milliseconds.
 *
 * Used ONLY for the one-port-two-servers corroboration below: "did this listener start AFTER we
 * called spawn()", a coarse ordering check, never an identity claim — §6.2's exact-equality rule
 * for the identity triple is untouched.
 */
function fileTimeToEpochMs(fileTimeStr) {
  return Number(BigInt(fileTimeStr) / 10000n - 11644473600000n);
}

/**
 * Register this server in the `agent-spawns/` scope, once the port is confirmed listening.
 * Best-effort and NEVER throws: a bookkeeping failure must not stop a human from seeing their FE.
 * Returns `{ dir, recordId }` on success, `null` otherwise (logged to stderr either way).
 *
 * 861 W3 F5 — the one-port-two-servers gap: when `port` came from `pickPort` (the free-port scan),
 * whatever answers afterward can only be OUR Vite — nothing was listening a moment before we
 * picked it. But an EXPLICIT `--port` carries no such guarantee: `waitForPortListening` only
 * proves "something is listening", not "our spawn is what came up" — a pre-existing stranger on
 * that port (our own `--strictPort` spawn then fails to bind, invisibly, behind the stranger's
 * success) would otherwise be corroborated as if it were ours. So an explicit port additionally
 * requires the listener to have started AFTER this call began — a listener that PREDATES the
 * spawn is, by construction, not the process we just started.
 *
 * REGISTER-OR-KILL: readiness is what makes a spawn reapable — the record is built from the port's
 * listener, so no readiness means no record, and an unrecorded child is invisible to
 * `agent-spawn-sweep.cjs` forever. Leaving it running is therefore the one outcome this function
 * must not produce: if readiness is never observed, the child's tree is killed and the kill is
 * reported. (Only THIS branch kills. When readiness IS observed but the identity or the F5
 * corroboration fails, something else may own the port — killing there could take out a stranger.)
 */
async function registerServedVite({
  port,
  explicitPort = false,
  spawnStartTime = Date.now(),
  callerIdentity = null,
  sessionId = undefined,
  child = null,
  childIdentity = null,
  waitForPort = waitForPortListening,
  resolveIdentity = resolveListenerIdentity,
  killChild = killSpawnTree,
} = {}) {
  try {
    const ready = await waitForPort(port);
    if (!ready) {
      const killed = killChild(child, await childIdentity);
      console.error(
        `[serve-worktree-fe] port ${port} never started accepting connections; ` +
        (killed.ok
          ? 'killed the unregistered child so it cannot leak past this session'
          : `could NOT kill the unregistered child (${killed.reason}) — check for a stray vite on port ${port}`),
      );
      return null;
    }
    const identity = resolveIdentity(port);
    if (!identity.ok) {
      console.error(`[serve-worktree-fe] could not establish the listener's identity, not registering: ${identity.reason}`);
      return null;
    }
    if (explicitPort && identity.processIdentity) {
      const own = (await childIdentity)?.host?.processIdentity;
      // Linux's direct Vite child must itself hold this listener; a stranger never becomes ours
      // through a wall-clock approximation of its monotonic birth ticks.
      if (!own || identity.pid !== own.pid || identity.processIdentity.bootId !== own.bootId
          || identity.processIdentity.startTicks !== own.startTicks) {
        console.error('[serve-worktree-fe] listener does not match the spawned Vite identity; not registering');
        return null;
      }
    } else if (explicitPort) {
      const listenerStartedMs = fileTimeToEpochMs(identity.creationFileTimeUtc);
      if (listenerStartedMs < spawnStartTime) {
        console.error(
          `[serve-worktree-fe] port ${port} was already held by pid ${identity.pid} before this spawn ` +
          `(--port pointed at a pre-existing listener) — not registering a stranger's process`,
        );
        return null;
      }
    }
    // The true owner: the calling agent session's harness process (agent-identity.cjs).
    const caller = callerIdentity ?? resolveAgentIdentity();
    const owner = ownerBlockFor(caller);
    const dir = resolveAgentSpawnsRegisterDir(mainRepoRoot());
    // 861 W3 F5 — the pid rides in the record id itself: a clean-exit delete keyed on `port` alone
    // could remove a DIFFERENT process's record if a stranger later took the same port under a
    // different pid than the one this session actually registered.
    const recordId = `serve-worktree-fe-${port}-${identity.pid}`;
    const record = await buildAgentSpawnRecord({
      recordId,
      producer: 'serve-worktree-fe',
      pid: identity.pid,
      creationFileTimeUtc: identity.creationFileTimeUtc,
      ...(identity.processIdentity ? { processIdentity: identity.processIdentity } : {}),
      cmdlineFingerprint: identity.cmdlineFingerprint,
      port,
      leaseDurationSec: AGENT_SPAWN_LEASE_DURATION_SEC,
      sessionId: sessionId === undefined ? caller.sessionId : sessionId,
      owner,
      repoRoot,
      resourceRoots: {
        worktreeRoot: repoRoot,
        nodeModulesRealPath: await resolveNodeModulesRealPath(uiWebDir),
      },
    });
    await writeAgentSpawnRecord({ dir, record });
    return { dir, recordId: record.recordId };
  } catch (err) {
    console.error(`[serve-worktree-fe] agent-spawn registration failed (non-fatal): ${err.message}`);
    return null;
  }
}

async function unregisterServedVite(registered) {
  if (!registered) return;
  try {
    await removeAgentSpawnRecord({ dir: registered.dir, recordId: registered.recordId });
  } catch { /* clean-exit retirement is best-effort */ }
}

/**
 * The calling agent session's readable label, by the one identity rule (agent-identity.cjs): the
 * nearest harness process names the session and its own variable is the label. Never the retired
 * shared pointer file, never `JUSTSEARCH_AGENT_SESSION_ID`. `opts` is the resolver's test seam.
 */
function resolveSessionId(env = process.env, opts = {}) {
  return resolveAgentIdentity({ env, ...opts }).sessionId ?? null;
}

async function main(argv = process.argv) {
  const branch = (spawnSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).stdout || '').trim();
  const explicitPortArg = argVal('--port', argv);
  const port = Number(explicitPortArg) || (await pickPort(5174));
  const apiPort = detectBackendPort(argv);

  const env = { ...process.env };
  if (apiPort) env.VITE_JUSTSEARCH_API_PORT = String(apiPort);

  console.error('[serve-worktree-fe] serving worktree FE:');
  console.error(`  branch:  ${branch || '(unknown)'}`);
  console.error(`  source:  ${uiWebDir}`);
  console.error(`  url:     http://localhost:${port}`);
  console.error(`  backend: ${apiPort ? `port ${apiPort} (borrowed, read-only)` : 'auto-discover (no running lease found)'}`);

  const isWin = process.platform === 'win32';
  const spawnStartTime = Date.now(); // F5: the corroboration clock starts HERE, before spawn()
  // Vite's own node entry, directly (no npx/cmd.exe shim): the process that owns the port is the
  // one started here. On win32 it runs in its own job, whose host identity is read at spawn.
  const viteEntry = path.join(uiWebDir, 'node_modules', 'vite', 'bin', 'vite.js');
  const run = jobRun.startInJob({ command: process.execPath, args: [viteEntry, '--port', String(port), '--strictPort'],
    cwd: uiWebDir, env, stdio: 'inherit', label: 'serve-worktree-fe' });
  const { child } = run;
  const childIdentity = Promise.resolve(isWin && !run.contained ? null : run);

  // Fire-and-track, never block the serve on registration (861 [A3]/W3).
  const registered = registerServedVite({ port, explicitPort: !!explicitPortArg, spawnStartTime, child, childIdentity });

  // Bounded wait for a registration already in flight (e.g. a quick Vite boot) so the clean-exit
  // retirement actually runs — but a human hitting Ctrl-C must never be held hostage by the
  // (up to 30s) port-readiness poll: if registration has not settled yet, abandon it. A leaked
  // record is not a leaked process — its lease lapses and the reaper (861 Phase 4) collects it.
  const retireRegistration = async () => {
    const settled = await Promise.race([
      registered.then((r) => ({ timedOut: false, r })).catch(() => ({ timedOut: false, r: null })),
      new Promise((resolve) => setTimeout(() => resolve({ timedOut: true }), 1500)),
    ]);
    if (!settled.timedOut) await unregisterServedVite(settled.r).catch(() => {});
  };
  // The job run's record goes once its processes have verifiably ended: a host that closed its job
  // as `ended` leaves nothing to verify; any other host (e.g. one Ctrl+C reached first) is settled
  // by the stop, which verifies every recorded identity before removing the record.
  const settleRun = () => (run.recordPath ? jobRun.settleRunRecord(run, { code: jobRun.NON_DISTINCTIVE_KILL_CODE }) : { ok: true });
  let stopping = false;
  const stopOnSignal = async () => {
    if (stopping) return;
    stopping = true;
    const result = child.exitCode === null && child.signalCode === null ? killSpawnTree(child, run) : settleRun();
    await retireRegistration();
    if (!result.ok) console.error(`[serve-worktree-fe] incomplete stop: ${result.reason ?? result.outcome}`);
    process.exit(result.ok ? 0 : 1);
  };
  process.on('SIGINT', stopOnSignal);
  process.on('SIGTERM', stopOnSignal);

  child.on('exit', async (code) => {
    if (stopping) return; // the signal's stop exits once it has settled the run
    await retireRegistration();
    settleRun();
    process.exit(code ?? 0);
  });
}

function isDirectRun() {
  return require.main === module;
}

if (isDirectRun()) {
  main().catch((err) => {
    console.error(`[serve-worktree-fe] ERROR: ${err.message}`);
    process.exit(1);
  });
}

module.exports = {
  argVal,
  mainRepoRoot,
  detectBackendPort,
  portInUse,
  isFree,
  pickPort,
  waitForPortListening,
  killSpawnTree,
  resolveListenerIdentity,
  fileTimeToEpochMs,
  registerServedVite,
  unregisterServedVite,
  resolveSessionId,
  AGENT_SPAWN_LEASE_DURATION_SEC,
  repoRoot,
  uiWebDir,
};
