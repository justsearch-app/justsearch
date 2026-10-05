/**
 * Tempdoc 861 W5 — the shared assembly (`scripts/dev/lib/agent-spawn-sweep.cjs`) that wires
 * each of §6.4's six reap occasions onto the Phase 4 reaper (`agent-spawn-reaper.cjs`).
 *
 * What this file proves, occasion by occasion, against injected records/table (861 W5 brief):
 *
 *   - `session-start` (via `runAgentSpawnSweep`) reaches a REAL kill for a lapsed-and-stale
 *     other-session record — not just an eligibility list nobody spends. A real disposable
 *     child process is spawned, killed through the full occasion pipeline, and its death is
 *     independently confirmed (never the running dev stack's own process).
 *   - `session-end` (`ownSessionOnly: true`) reaps only the calling session's OWN records and
 *     leaves an equally-stale OTHER session's record alone — the narrower scope 861 §6.4
 *     describes ("this session's own spawns"), proven by a same-fixture-set A/B.
 *   - `before-a-build` (`findBuildHolders`) NEVER mints a `reap` disposition, even against a
 *     fixture that is reap-eligible on every other axis (same-session, matching identity) — the
 *     build-hook-never-kills proof [A4] requires. Also proves the path pre-filter: a record that
 *     does not hold the target path is invisible to it.
 *   - `orientation` (`gatherAgentSpawnOrientation`) likewise mints no `reap`, and folds in the
 *     observed-tier derivation for an unregistered known-fingerprint process.
 *   - `worktree-teardown` (`consultAgentSpawnsForTeardown`) sets `blocksProceed` for a
 *     contention/refuse holder and clears it once that holder is gone — remove-worktree.cjs's
 *     actual decision input.
 *
 * Real-process cleanup: every spawned child is force-killed in a `finally` regardless of
 * whether the sweep already reaped it, so a failing assertion never leaks a process.
 *
 * Run with: `node scripts/agent-analytics/861-w5-agent-spawn-sweep.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { writeHarnessFixture, writeNoHarnessFixture, identityEnv } from './lib/agent-identity-fixture.mjs';

// Owner identity (agent-identity.cjs): a session is its harness process and "own" is its owner KEY.
// OWN has no process identity (liveness unknown, the old "no activity stamp"); ENDED_OWN names a pid
// no Windows process can have (not a multiple of 4), so every process table reads its owner as gone
// (the old "stale activity stamp").
const OWN = (key) => ({ harness: 'claude', pid: null, creationTime: null, key });
const ENDED_OWN = (key) => ({ harness: 'claude', pid: 999_997, creationTime: '134000000000000000', key });
const ID = (key) => ({ harness: 'claude', owner: OWN(key), sessionId: key, source: 'test' });

const require = createRequire(import.meta.url);
const {
  runAgentSpawnSweep,
  consultAgentSpawnsForTeardown,
  inspectAgentSpawnsForTeardown,
  gatherAgentSpawnOrientation,
  findBuildHolders,
  deriveObservedRows,
  KNOWN_AGENT_SPAWN_FINGERPRINTS,
  describeEntry,
  holdsWithin,
  recordHoldsTree,
  strictRecordHoldsTree,
  resolveCallerIdentity,
  resolveMainRepoRoot,
} = require('../dev/lib/agent-spawn-sweep.cjs');
const { buildAgentSpawnRecord, writeAgentSpawnRecord, OWNERSHIP_MODES, recordHoldsPath } = require('../dev/lib/agent-spawn-record.cjs');
const { readProcessTable, normalizeCreationTime } = require('../dev/lib/process-identity.cjs');

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.join(HERE, '..', 'dev', 'agent-spawn-sweep.cjs');

let passed = 0;
const failures = [];
const spawnedPids = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

function killIfAlive(pid) {
  try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
}

/** Spawn a real, disposable, long-lived child this test owns — never the dev stack's own Vite. */
function spawnDisposableChild() {
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    stdio: 'ignore',
    windowsHide: true,
  });
  spawnedPids.push(child.pid);
  return child;
}

/** Poll the real process table until our own child appears (mirrors 861-w4-reaper-kill.test.mjs
 *  — it is not there the instant it spawns). */
async function findOwnChildRow(pid) {
  for (let i = 0; i < 15; i += 1) {
    const table = readProcessTable();
    if (table.ok) {
      const row = table.table.find((r) => Number(r?.ProcessId) === pid);
      if (row) return row;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  return null;
}

/** Resolve the identity triple for a real child from a fresh process-table read. */
async function identityFor(pid) {
  const row = await findOwnChildRow(pid);
  assert.ok(row, `spawned child pid ${pid} never appeared in the process table`);
  const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
  assert.ok(creationFileTimeUtc, `child pid ${pid} has no readable creation time`);
  return { creationFileTimeUtc, cmdlineFingerprint: '-e', cmdline: row.CommandLine };
}

async function withTmpRegister(fn) {
  const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-sweep-'));
  const env = { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: tmp };
  try {
    await fn({ tmp, env, mainRepoRoot: tmp });
  } finally {
    await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});
  }
}

/** A fresh, injectable process table asserting the given rows are alive right now — matches
 *  `readProcessTable()`'s tri-state result shape without spawning PowerShell. Identity
 *  verification (pid AND creation time AND fingerprint) runs BEFORE the same-session/lease
 *  matrix rules in `classifySpawnRecord` — a fixture record must therefore verify as MATCH
 *  before its ownership/lease shape can be exercised at all; a non-existent pid reads as
 *  MISMATCH ("the process is gone"), not as evidence the matrix skips past. */
function fakeTable(now, rows) {
  return { ok: true, readAt: now, table: rows };
}

const NOW = () => Date.now();
const LAPSED_LEASE = (now) => ({
  durationSec: 1,
  renewedAt: new Date(now - 5000).toISOString(),
  expiresAt: new Date(now - 4000).toISOString(), // lapsed 4s ago
});

const skipped = [];

async function main() {
  // ── session-start: a real kill through the full occasion pipeline ──────────────────────────
  // Win32-only: `readProcessTable`/`taskkill` are implemented for win32 only
  // (`process-identity.cjs`), so this is skipped rather than silently passing on CI's Linux
  // runner — the other checks below inject a fake table and are platform-independent.
  if (process.platform !== 'win32') {
    skipped.push('session-start reaps a real, lapsed-and-stale OTHER-session record (real kill): win32-only (readProcessTable/taskkill)');
  } else {
  await check('session-start reaps a real, lapsed-and-stale OTHER-session record (real kill)', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const child = spawnDisposableChild();
      try {
        const identity = await identityFor(child.pid);
        const now = NOW();
        const record = await buildAgentSpawnRecord({
          recordId: 'w5-session-start-real-kill',
          producer: 'test',
          pid: child.pid,
          creationFileTimeUtc: identity.creationFileTimeUtc,
          cmdlineFingerprint: identity.cmdlineFingerprint,
          port: 39999,
          leaseDurationSec: 1,
          sessionId: 'other-session-aaaa',
          owner: ENDED_OWN('other-session-aaaa'),
          now,
        });
        // buildAgentSpawnRecord always mints a FUTURE lease — overwrite with a lapsed one after.
        const dir = path.join(tmp, 'agent-spawns');
        await writeAgentSpawnRecord({ dir, record: { ...record, lease: LAPSED_LEASE(now) } });

        // An owner with no verifiable process would be UNKNOWN -> CONTENTION (leave), not reap.
        // The record's owner has ENDED instead, so the matrix's only reaping cell
        // (LAPSED_OWNER_STALE) is the one actually exercised. (The ledger stamp below is retired
        // and read by nothing; it stays to show it no longer licenses anything.)
        const sessionsDir = path.join(tmp, 'sessions');
        await fsp.mkdir(sessionsDir, { recursive: true });
        await fsp.writeFile(
          path.join(sessionsDir, 'other-session-aaaa.json'),
          JSON.stringify({ lastActivityAt: new Date(now - 20 * 60_000).toISOString() }),
        );

        const result = await runAgentSpawnSweep({
          occasion: 'session-start',
          mainRepoRoot,
          callerIdentity: ID('this-session-bbbb'),
          env,
          now,
          // Small thresholds so a 20-minute-old activity stamp reads as STALE without touching
          // the module-load-time DEFAULT_THRESHOLDS (env vars read there are captured once at
          // require-time, so passing an explicit thresholds object is the only reliable seam).
          thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
        });

        assert.equal(result.buckets.reap.length, 1, 'expected exactly one reap-bucket entry');
        assert.equal(result.buckets.reap[0].cell, 'other-session/lease-lapsed/owner-stale');
        assert.equal(result.kills.length, 1, 'executeReap should have been invoked once');
        assert.equal(result.kills[0].killed, true, `kill did not report success: ${JSON.stringify(result.kills[0])}`);
        assert.equal(result.kills[0].confirmed, true, 'kill was not confirmed against a fresh table');

        // Independently confirm the real process is actually gone (not just self-reported).
        await new Promise((r) => setTimeout(r, 200));
        const after = readProcessTable();
        assert.ok(after.ok);
        assert.ok(!after.table.some((r) => Number(r?.ProcessId) === child.pid), 'child pid still present in a fresh table after the reap');

        // The record file itself is removed on a confirmed kill (861 §6.3's disposal contract).
        const stillThere = await fsp.stat(path.join(dir, 'w5-session-start-real-kill.json')).then(() => true, () => false);
        assert.equal(stillThere, false, 'record file should be removed after a confirmed kill');
      } finally {
        killIfAlive(child.pid);
      }
    });
  });
  }

  // -- a live other owner is never reaped; its ended twin is -------------------------------------
  await check('session-start never reaps a RUNNING other owner\'s lapsed spawn; the same spawn of an ENDED owner is reaped', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const LIVE_OWNER = { harness: 'codex', pid: 900_117, creationTime: '134000000000900117', key: 'codex-900117-134000000000900117' };
      const live = await buildAgentSpawnRecord({
        recordId: 'w5-live-owner', producer: 'test', pid: 999911,
        creationFileTimeUtc: '134320479841300361', cmdlineFingerprint: 'vite --port 11',
        port: 40011, leaseDurationSec: 1, sessionId: 'other-live', owner: LIVE_OWNER, now,
      });
      const ended = await buildAgentSpawnRecord({
        recordId: 'w5-ended-owner', producer: 'test', pid: 999914,
        creationFileTimeUtc: '134320479841300362', cmdlineFingerprint: 'vite --port 12',
        port: 40012, leaseDurationSec: 1, sessionId: 'other-ended', owner: ENDED_OWN('other-ended'), now,
      });
      await writeAgentSpawnRecord({ dir, record: { ...live, lease: LAPSED_LEASE(now) } });
      await writeAgentSpawnRecord({ dir, record: { ...ended, lease: LAPSED_LEASE(now) } });
      // A same-label legacy twin: the caller's label, no owner block. Never "own", never reaped.
      const legacy = await buildAgentSpawnRecord({
        recordId: 'w5-legacy-same-label', producer: 'test', pid: 999913,
        creationFileTimeUtc: '134320479841300363', cmdlineFingerprint: 'vite --port 13',
        port: 40013, leaseDurationSec: 1, sessionId: 'caller-session', now,
      });
      await writeAgentSpawnRecord({ dir, record: { ...legacy, lease: LAPSED_LEASE(now) } });

      const result = await runAgentSpawnSweep({
        occasion: 'session-start',
        mainRepoRoot,
        callerIdentity: ID('caller-session'),
        env,
        now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999911, CreationFileTimeUtc: '134320479841300361', CommandLine: 'vite --port 11' },
          { ProcessId: 999914, CreationFileTimeUtc: '134320479841300362', CommandLine: 'vite --port 12' },
          { ProcessId: 999913, CreationFileTimeUtc: '134320479841300363', CommandLine: 'vite --port 13' },
          { ProcessId: LIVE_OWNER.pid, CreationFileTimeUtc: LIVE_OWNER.creationTime, CommandLine: 'codex.exe', Name: 'codex.exe' },
        ]),
        thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
      });
      const byId = new Map(result.buckets.all.map((e) => [e.recordId, e]));
      assert.equal(byId.get('w5-live-owner').cell, 'other-session/lease-lapsed/owner-active', 'a running owner is active');
      assert.notEqual(byId.get('w5-live-owner').disposition, 'reap', 'a running other owner\'s spawn is never reaped');
      assert.equal(byId.get('w5-ended-owner').cell, 'other-session/lease-lapsed/owner-stale');
      assert.equal(byId.get('w5-legacy-same-label').cell, 'other-session/lease-lapsed/owner-unknown', 'a legacy record is not own by its label');
      assert.notEqual(byId.get('w5-legacy-same-label').disposition, 'reap');
      assert.deepEqual(result.buckets.reap.map((e) => e.recordId), ['w5-ended-owner']);
    });
  });

  // ── session-end: scoped to the caller's OWN records only ────────────────────────────────────
  await check('session-end reaps the caller\'s own stale record but leaves an equally-stale OTHER session\'s alone', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const sessionsDir = path.join(tmp, 'sessions');
      await fsp.mkdir(sessionsDir, { recursive: true });

      // Two fixture records, identical shape, different owning session. Identity verification
      // runs before the same-session/lease matrix, so a fake pid needs a matching fake table row
      // to verify MATCH — this test asserts on BUCKET MEMBERSHIP, not on a real kill (the
      // previous case already proved that end-to-end against a real process).
      const mine = await buildAgentSpawnRecord({
        recordId: 'w5-mine', producer: 'test', pid: 999901,
        creationFileTimeUtc: '134320479841300350', cmdlineFingerprint: 'vite --port 1',
        port: 40001, leaseDurationSec: 1, sessionId: 'caller-session', owner: OWN('caller-session'), now,
      });
      const theirs = await buildAgentSpawnRecord({
        recordId: 'w5-theirs', producer: 'test', pid: 999902,
        creationFileTimeUtc: '134320479841300351', cmdlineFingerprint: 'vite --port 2',
        port: 40002, leaseDurationSec: 1, sessionId: 'other-session', owner: ENDED_OWN('other-session'), now,
      });
      await writeAgentSpawnRecord({ dir, record: { ...mine, lease: LAPSED_LEASE(now) } });
      await writeAgentSpawnRecord({ dir, record: { ...theirs, lease: LAPSED_LEASE(now) } });
      await fsp.writeFile(path.join(sessionsDir, 'other-session.json'), JSON.stringify({ lastActivityAt: new Date(now - 20 * 60_000).toISOString() }));

      const result = await runAgentSpawnSweep({
        occasion: 'session-end',
        mainRepoRoot,
        callerIdentity: ID('caller-session'),
        ownSessionOnly: true,
        env,
        now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999901, CreationFileTimeUtc: '134320479841300350', CommandLine: 'vite --port 1' },
          { ProcessId: 999902, CreationFileTimeUtc: '134320479841300351', CommandLine: 'vite --port 2' },
        ]),
        thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
      });

      const evaluatedRecordIds = result.buckets.all.map((e) => e.recordId);
      assert.deepEqual(evaluatedRecordIds, ['w5-mine'], 'session-end must only evaluate the caller\'s own records, not the other session\'s');
      assert.equal(result.buckets.reap[0].cell, 'same-session');
    });
  });

  // ── before-a-build: structurally cannot mint a reap, even against a reap-eligible fixture ──
  await check('before-a-build (findBuildHolders) never returns a reap disposition, even for a same-session identity-matched holder', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'worktree-under-build');
      await fsp.mkdir(worktreeRoot, { recursive: true });

      const record = await buildAgentSpawnRecord({
        recordId: 'w5-build-holder', producer: 'test', pid: 999903,
        creationFileTimeUtc: '134320479841300352', cmdlineFingerprint: 'vite --port 3',
        port: 40003, leaseDurationSec: 3600, sessionId: 'caller-session', owner: OWN('caller-session'),
        resourceRoots: { worktreeRoot }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      const result = await findBuildHolders({
        mainRepoRoot,
        targetPath: worktreeRoot,
        callerIdentity: ID('caller-session'), // SAME session + live lease -> would be `reap` on any EXECUTE occasion
        env,
        now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999903, CreationFileTimeUtc: '134320479841300352', CommandLine: 'vite --port 3' },
        ]),
      });

      assert.equal(result.holders.length, 1, 'the path pre-filter should have found the one holder');
      assert.equal(result.holders[0].cell, 'same-session', 'sanity: this fixture IS reap-eligible on the matrix');
      // THE PROOF: [A4] structural downgrade. `capability:'advisory'` means no entry here may
      // ever carry disposition 'reap' — this is not a policy this hook applies, it is a
      // pairing the reaper's frozen OCCASIONS map makes unreachable.
      for (const e of result.holders) {
        assert.notEqual(e.disposition, 'reap', 'before-a-build must NEVER produce a spendable reap disposition');
      }
      assert.equal(result.holders[0].disposition, 'report');
      assert.equal(result.holders[0].ceiling, 'reap');
      assert.equal(result.holders[0].downgraded, true);
    });

    // Path pre-filter: a record that does NOT hold the target path is invisible to this occasion.
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-unrelated', producer: 'test', pid: 999904,
        creationFileTimeUtc: '134320479841300353', cmdlineFingerprint: 'vite --port 4',
        port: 40004, leaseDurationSec: 3600, sessionId: 'caller-session', owner: OWN('caller-session'),
        resourceRoots: { worktreeRoot: path.join(tmp, 'somewhere-else') }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      const result = await findBuildHolders({
        mainRepoRoot,
        targetPath: path.join(tmp, 'the-build-target'),
        callerIdentity: ID('caller-session'),
        env,
        now,
      });
      assert.deepEqual(result.holders, [], 'a record holding an unrelated path must not surface as a build holder');
    });
  });

  // ── F-5: recordFilter (the per-session dedup) applies BEFORE the process-table read ─────────
  await check('F-5: findBuildHolders never reads the process table when recordFilter excludes every path-matched candidate', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'worktree-under-build-f5');
      await fsp.mkdir(worktreeRoot, { recursive: true });
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-f5-already-nudged', producer: 'test', pid: 999990,
        creationFileTimeUtc: '134320479841300390', cmdlineFingerprint: 'vite --port 90',
        port: 40090, leaseDurationSec: 3600, sessionId: 'caller-session', owner: OWN('caller-session'),
        resourceRoots: { worktreeRoot }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      let tableReads = 0;
      const spyReadTable = () => { tableReads += 1; return fakeTable(now, []); };

      const excluded = await findBuildHolders({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now,
        readTable: spyReadTable,
        recordFilter: () => false, // simulates "every path-matched holder is already nudged"
      });
      assert.deepEqual(excluded.holders, []);
      assert.equal(tableReads, 0, 'recordFilter must exclude the candidate BEFORE the process-table read, not after — that IS the fix (861 W5 review F-5)');

      const included = await findBuildHolders({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now,
        readTable: spyReadTable,
        recordFilter: () => true,
      });
      assert.equal(included.holders.length, 1, 'sanity: the SAME candidate is found when recordFilter admits it');
      assert.equal(tableReads, 1, 'a non-excluded candidate still reaches the process-table read exactly once');
    });
  });

  // ── orientation: never mints a reap either, and folds in the observed tier ──────────────────
  await check('orientation (gatherAgentSpawnOrientation) never returns a reap disposition', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-orientation', producer: 'test', pid: 999905,
        creationFileTimeUtc: '134320479841300354', cmdlineFingerprint: 'vite --port 5',
        port: 40005, leaseDurationSec: 1, sessionId: 'caller-session', owner: ENDED_OWN('caller-session'), now,
      });
      await writeAgentSpawnRecord({ dir, record: { ...record, lease: LAPSED_LEASE(now) } });
      const sessionsDir = path.join(tmp, 'sessions');
      await fsp.mkdir(sessionsDir, { recursive: true });
      await fsp.writeFile(path.join(sessionsDir, 'caller-session.json'), JSON.stringify({ lastActivityAt: new Date(now - 20 * 60_000).toISOString() }));

      const result = await gatherAgentSpawnOrientation({
        mainRepoRoot, env, now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999905, CreationFileTimeUtc: '134320479841300354', CommandLine: 'vite --port 5' },
        ]),
        thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
      });
      assert.equal(result.available, true);
      const own = result.buckets.all.find((e) => e.recordId === 'w5-orientation');
      assert.ok(own, 'the registered record should be evaluated');
      // This call passes no callerSessionId (a caller that could not resolve its own session id) —
      // the lapsed lease + stale owner activity is what makes this fixture reap-eligible on the
      // matrix regardless of attribution.
      assert.equal(own.cell, 'other-session/lease-lapsed/owner-stale', 'sanity: this fixture IS reap-eligible on the matrix');
      // The matrix would reap this on an EXECUTE occasion; orientation must downgrade it instead.
      for (const e of result.buckets.all) assert.notEqual(e.disposition, 'reap');
      assert.equal(own.disposition, 'report');
      assert.equal(own.ceiling, 'reap');
      assert.equal(own.downgraded, true);
    });
  });

  // ── D2 (closing-window findings): orientation must attribute the CALLING session's own live
  // spawn as same-session, not other-session/lease-live. gatherAgentSpawnOrientation previously
  // never accepted/forwarded callerSessionId to reapEligible, so a session's own record fell
  // through to the CONTENTION branch (agent-spawn-reaper.cjs's `lease === 'live'` check) instead
  // of the SAME_SESSION check just above it. Proves both the misattribution (red, no
  // callerSessionId — the old call shape world-state.mjs used) and the fix (green, callerSessionId
  // supplied) against the identical fixture.
  await check('D2: orientation attributes the calling session\'s own live spawn as same-session, not other-session/lease-live', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-orientation-own-session', producer: 'test', pid: 999907,
        creationFileTimeUtc: '134320479841300356', cmdlineFingerprint: 'vite --port 7',
        port: 40007, leaseDurationSec: 3600, sessionId: 'caller-session', owner: OWN('caller-session'), now,
      });
      await writeAgentSpawnRecord({ dir, record }); // live lease — no LAPSED_LEASE override

      const readFakeTable = () => fakeTable(now, [
        { ProcessId: 999907, CreationFileTimeUtc: '134320479841300356', CommandLine: 'vite --port 7' },
      ]);
      const thresholds = { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 };

      // RED: the pre-fix call shape — no callerSessionId — misattributes this session's own record.
      const before = await gatherAgentSpawnOrientation({ mainRepoRoot, env, now, readTable: readFakeTable, thresholds });
      const ownBefore = before.buckets.all.find((e) => e.recordId === 'w5-orientation-own-session');
      assert.ok(ownBefore, 'the registered record should be evaluated');
      assert.equal(ownBefore.cell, 'other-session/lease-live', 'sanity: this reproduces the reported misattribution when callerSessionId is withheld');

      // GREEN: passing callerSessionId lets reapEligible recognize the record as this session's own.
      const after = await gatherAgentSpawnOrientation({
        mainRepoRoot, env, now, readTable: readFakeTable, thresholds, callerIdentity: ID('caller-session'),
      });
      const ownAfter = after.buckets.all.find((e) => e.recordId === 'w5-orientation-own-session');
      assert.ok(ownAfter, 'the registered record should be evaluated');
      assert.equal(ownAfter.cell, 'same-session', 'D2 fix: an own-session record must read same-session in orientation output');
      assert.notEqual(ownAfter.cell, 'other-session/lease-live');
      // Orientation stays advisory even for same-session: it still never mints a live reap.
      assert.equal(ownAfter.disposition, 'report');
      assert.equal(ownAfter.ceiling, 'reap');
      assert.equal(ownAfter.downgraded, true);
    });
  });

  await check('deriveObservedRows: a known-fingerprint process with no covering record is observed; a registered pid is not', () => {
    const table = [
      { ProcessId: 111, CommandLine: 'node vite --port 9999' },
      { ProcessId: 222, CommandLine: 'python scripts/agent-analytics/otlp-sink.py --port 4318' },
      { ProcessId: 333, CommandLine: 'notepad.exe' },
    ];
    const observed = deriveObservedRows(table, new Set([111]));
    const pids = observed.map((r) => r.ProcessId).sort();
    assert.deepEqual(pids, [222], 'pid 111 is registered (excluded); pid 222 matches a known fingerprint and is unregistered; pid 333 matches no fingerprint');
    assert.ok(KNOWN_AGENT_SPAWN_FINGERPRINTS.includes('vite'));
  });

  // ── worktree-teardown: blocksProceed set/cleared based on the holder's fate ─────────────────
  await check('worktree-teardown (consultAgentSpawnsForTeardown) sets blocksProceed for a contention holder and clears it once resolved', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'worktree-under-teardown');
      await fsp.mkdir(worktreeRoot, { recursive: true });

      // Other session, LIVE lease -> CONTENTION -> blocksProceed (this occasion is EXECUTE,
      // so a live-leased other-session holder is a real refusal-worthy contention, not garbage).
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-teardown-holder', producer: 'test', pid: 999906,
        creationFileTimeUtc: '134320479841300355', cmdlineFingerprint: 'vite --port 6',
        port: 40006, leaseDurationSec: 3600, sessionId: 'other-session', owner: OWN('other-session'),
        resourceRoots: { worktreeRoot }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      const readFakeTable = () => fakeTable(now, [
        { ProcessId: 999906, CreationFileTimeUtc: '134320479841300355', CommandLine: 'vite --port 6' },
      ]);
      const blocked = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now, readTable: readFakeTable,
      });
      assert.equal(blocked.buckets.blocksProceed, true, 'a live-leased other-session holder must block teardown');
      assert.equal(blocked.buckets.contention.length, 1);
      assert.equal(blocked.buckets.reap.length, 0);

      // Now the holder is gone (its record removed, e.g. the producer cleaned up) — nothing left
      // under the path, so the fast path returns immediately and blocksProceed clears.
      await fsp.rm(path.join(dir, 'w5-teardown-holder.json'), { force: true });
      const clear = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now, readTable: readFakeTable,
      });
      assert.equal(clear.buckets.blocksProceed, false, 'no remaining holder must not block teardown');
      assert.deepEqual(clear.buckets.all, []);
    });
  });

  await check('worktree-teardown: a path pre-filter miss produces the empty fast-path shape (no PowerShell spawn needed)', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const result = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: path.join(tmp, 'nonexistent-worktree'), callerIdentity: ID('x'), env,
      });
      assert.equal(result.buckets.blocksProceed, false);
      assert.deepEqual(result.buckets.all, []);
      assert.deepEqual(result.kills, []);
    });
  });

  await check('worktree-teardown blocks when execution-time identity re-verification refuses an otherwise reapable holder', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'execution-refuse-target');
      await fsp.mkdir(worktreeRoot, { recursive: true });
      const record = await buildAgentSpawnRecord({
        recordId: 'execution-refuse', producer: 'test', pid: 999916,
        creationFileTimeUtc: '134320479841300916', cmdlineFingerprint: 'vite --port 16',
        port: 40016, leaseDurationSec: 3600, sessionId: 'caller-session', owner: OWN('caller-session'),
        resourceRoots: { worktreeRoot }, now,
      });
      await writeAgentSpawnRecord({ dir, record });
      const readTable = () => fakeTable(now, [{
        ProcessId: 999916,
        CreationFileTimeUtc: '134320479841300916',
        CommandLine: 'vite --port 16',
      }]);
      const result = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now, readTable,
        executeReadTable: () => ({ ok: false, reason: 'injected execution-time process-table failure' }),
      });
      assert.equal(result.kills.length, 1);
      assert.equal(result.kills[0].refused, true);
      assert.equal(result.kills[0].confirmed, false);
      assert.equal(result.buckets.blocksProceed, true, 'an unconfirmed execution must block directory deletion');
      assert.equal(result.executionBlockers.length, 1);
    });
  });

  await check('worktree-teardown preview treats a pending atomic register write as unknown without modifying it', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'pending-target');
      await fsp.mkdir(worktreeRoot, { recursive: true });
      await fsp.mkdir(dir, { recursive: true });
      const pending = path.join(dir, 'serve-123.json.999.tmp');
      await fsp.writeFile(pending, '{in progress');
      const before = await fsp.stat(pending);
      const result = await inspectAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env,
      });
      const after = await fsp.stat(pending);
      assert.equal(result.buckets.blocksProceed, true);
      assert.match(result.buckets.all[0].reason, /pending atomic-write/i);
      assert.equal(after.size, before.size);
      assert.equal(after.mtimeMs, before.mtimeMs);
      assert.equal(await fsp.readFile(pending, 'utf8'), '{in progress');
    });
  });

  await check('strict teardown path relation propagates non-absence realpath failures', async () => {
    const error = Object.assign(new Error('injected access refusal'), { code: 'EACCES' });
    await assert.rejects(
      strictRecordHoldsTree(
        { resourceRoots: { worktreeRoot: path.resolve('held-root') } },
        path.resolve('target-root'),
        { realpath: async () => { throw error; } },
      ),
      /injected access refusal/,
    );
  });

  // ── F-2a/F-3: the shared session-id resolution chain ────────────────────────────────────────
  await check('F-2a/F-3: resolveCallerIdentity - explicit > harness process (its own label) > none; never the export or the pointer file', async () => {
    const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-session-id-'));
    try {
      const harness = writeHarnessFixture(path.join(tmp, 'identity'));
      const none = writeNoHarnessFixture(path.join(tmp, 'identity'));
      const explicit = resolveCallerIdentity({ explicit: 'explicit-wins', env: identityEnv(harness.file, { CLAUDE_CODE_SESSION_ID: 'xxxx-label' }) });
      assert.equal(explicit.sessionId, 'explicit-wins');
      assert.equal(explicit.owner.key, 'override-explicit-wins');
      const detected = resolveCallerIdentity({ env: identityEnv(harness.file, { CLAUDE_CODE_SESSION_ID: 'from-claude-code' }) });
      assert.equal(detected.sessionId, 'from-claude-code');
      assert.equal(detected.owner.key, harness.owner.key, 'the owner is the harness process, not the label');
      const exportOnly = resolveCallerIdentity({ env: identityEnv(none.file, { JUSTSEARCH_AGENT_SESSION_ID: 'from-export-hook' }) });
      assert.equal(exportOnly.owner, null, 'JUSTSEARCH_AGENT_SESSION_ID is not identity');
      assert.equal(exportOnly.sessionId, null);

      const telemetryDir = path.join(tmp, 'tmp', 'agent-telemetry');
      await fsp.mkdir(telemetryDir, { recursive: true });
      await fsp.writeFile(path.join(telemetryDir, 'current-session-id'), 'from-pointer-file\n');
      const cwd = process.cwd();
      process.chdir(tmp);
      try {
        const withFile = resolveCallerIdentity({ env: identityEnv(none.file) });
        assert.equal(withFile.sessionId, null, 'the leftover pointer file is never identity');
        assert.equal(withFile.harness, 'unknown');
      } finally {
        process.chdir(cwd);
      }
    } finally {
      await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});
    }
  });

  await check('resolveMainRepoRoot is re-exported (used by remove-worktree.cjs — F-8)', () => {
    assert.equal(typeof resolveMainRepoRoot, 'function');
  });

  // ── F-1: pruning is actually wired, not merely documented ───────────────────────────────────
  await check('F-1 RED/GREEN: a transient IDENTITY_REFUSE record blocks teardown forever without pruning (red); the session-start sweep\'s now-wired prune clears it (green)', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'worktree-under-teardown-f1');
      await fsp.mkdir(worktreeRoot, { recursive: true });

      const EIGHT_DAYS_AGO = now - 8 * 24 * 60 * 60 * 1000; // > DEFAULT_MAX_RECORD_AGE_MS (7d)
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-f1-aged-refuse', producer: 'test', pid: 999960,
        creationFileTimeUtc: '134320479841300360', cmdlineFingerprint: 'vite --port 60',
        port: 40060, leaseDurationSec: 60, sessionId: 'other-session', owner: OWN('other-session'),
        resourceRoots: { worktreeRoot }, now: EIGHT_DAYS_AGO,
      });
      // Lease lapsed long ago (non-live -> prunable by age); startedAt is baked in as 8 days old.
      await writeAgentSpawnRecord({
        dir,
        record: {
          ...record,
          lease: { durationSec: 60, renewedAt: new Date(EIGHT_DAYS_AGO).toISOString(), expiresAt: new Date(EIGHT_DAYS_AGO + 60_000).toISOString() },
        },
      });

      // A row matching pid + creationTime but with an UNREADABLE CommandLine -> genuine
      // IDENTITY_REFUSE (transient evidence-unavailable), never MISMATCH. MISMATCH ("positively
      // gone") is the case F3's carve-out already unblocks; REFUSE is the case F-1 fixes.
      const readFakeTable = () => fakeTable(now, [
        { ProcessId: 999960, CreationFileTimeUtc: '134320479841300360', CommandLine: null },
      ]);

      // RED: query teardown directly, no sweep/prune has ever run — reproduces the pre-fix state.
      const red = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now, readTable: readFakeTable,
      });
      assert.equal(red.buckets.all[0]?.cell, 'identity-refuse', 'sanity: this is a transient REFUSE, not a positively-gone MISMATCH');
      assert.equal(red.buckets.blocksProceed, true, 'RED: a transient identity-refuse record blocks teardown');

      // GREEN: the session-start sweep (prune defaults to true for this occasion) removes the
      // aged record BEFORE reapEligible ever evaluates it again.
      const sweepResult = await runAgentSpawnSweep({
        occasion: 'session-start', mainRepoRoot, env, now, readTable: readFakeTable,
      });
      assert.ok(sweepResult.pruned, 'session-start must invoke pruneAgentSpawnRecords (F-1 wiring)');
      assert.deepEqual(sweepResult.pruned.deletedIds, ['w5-f1-aged-refuse']);

      const green = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerIdentity: ID('caller-session'), env, now, readTable: readFakeTable,
      });
      assert.equal(green.buckets.blocksProceed, false, 'GREEN: pruning cleared the aged record — teardown no longer blocks forever');
      assert.deepEqual(green.buckets.all, []);
    });
  });

  await check('F-1 control: prune:false reproduces the pre-fix behaviour on the same fixture shape (the aged record survives)', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const EIGHT_DAYS_AGO = now - 8 * 24 * 60 * 60 * 1000;
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-f1-control', producer: 'test', pid: 999961,
        creationFileTimeUtc: '134320479841300361', cmdlineFingerprint: 'vite --port 61',
        port: 40061, leaseDurationSec: 60, sessionId: 'other-session', owner: OWN('other-session'), now: EIGHT_DAYS_AGO,
      });
      await writeAgentSpawnRecord({
        dir,
        record: {
          ...record,
          lease: { durationSec: 60, renewedAt: new Date(EIGHT_DAYS_AGO).toISOString(), expiresAt: new Date(EIGHT_DAYS_AGO + 60_000).toISOString() },
        },
      });

      const result = await runAgentSpawnSweep({
        occasion: 'session-start', mainRepoRoot, env, now, prune: false,
        readTable: () => fakeTable(now, []),
      });
      assert.equal(result.pruned, null, 'prune:false must skip pruning entirely');
      const stillThere = await fsp.stat(path.join(dir, 'w5-f1-control.json')).then(() => true, () => false);
      assert.equal(stillThere, true, 'without pruning wired, the aged record survives indefinitely — this is the bug F-1 fixes');
    });
  });

  // ── F-4: root containment, both directions ──────────────────────────────────────────────────
  await check('F-4: recordHoldsTree finds a Vite holding a path INSIDE the queried (wider) tree — the §6.2 headline case recordHoldsPath alone misses', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const mainLikeRoot = path.join(tmp, 'main-like-checkout');
      const nodeModules = path.join(mainLikeRoot, 'node_modules');
      await fsp.mkdir(nodeModules, { recursive: true });

      const record = await buildAgentSpawnRecord({
        recordId: 'w5-f4-cross-tree', producer: 'test', pid: 999970,
        creationFileTimeUtc: '134320479841300370', cmdlineFingerprint: 'vite --port 70',
        port: 40070, leaseDurationSec: 3600, sessionId: 'other-session', owner: OWN('other-session'),
        // The record's held root is a WORKTREE's node_modules resolved to the MAIN checkout's
        // real node_modules (the junction target) — narrower than, and nested INSIDE, the tree
        // a build/teardown would query with (mainLikeRoot).
        resourceRoots: { nodeModulesRealPath: nodeModules },
      });

      // THE BUG, demonstrated directly: recordHoldsPath alone asks the wrong direction.
      assert.equal(await recordHoldsPath(record, mainLikeRoot), false, 'sanity: recordHoldsPath alone misses the headline case');
      // THE FIX: recordHoldsTree (both directions) finds it.
      assert.equal(await holdsWithin(record, mainLikeRoot), true);
      assert.equal(await recordHoldsTree(record, mainLikeRoot), true, 'recordHoldsTree must find a record whose held root is nested INSIDE the queried tree');

      // And through the actual occasion wiring, not just the predicate in isolation:
      const dir = path.join(tmp, 'agent-spawns');
      await writeAgentSpawnRecord({ dir, record });

      const built = await findBuildHolders({ mainRepoRoot, targetPath: mainLikeRoot, callerIdentity: ID('caller-session'), env });
      assert.equal(built.holders.length, 1, 'findBuildHolders must surface the cross-tree holder when queried with the WIDER tree root');
      assert.equal(built.holders[0].recordId, 'w5-f4-cross-tree');

      const consulted = await consultAgentSpawnsForTeardown({ mainRepoRoot, targetPath: mainLikeRoot, callerIdentity: ID('caller-session'), env });
      assert.equal(consulted.buckets.all.length, 1, 'consultAgentSpawnsForTeardown must surface the same cross-tree holder');
      assert.equal(consulted.buckets.all[0].recordId, 'w5-f4-cross-tree');
    });
  });

  // ── F-2b: the printed remedy leads with the safe sweep, not a bare kill ─────────────────────
  await check('F-2b: describeEntry leads with the sweep-CLI remedy for a registered entry; an observed-tier entry (no register row) keeps only the kill line', () => {
    const registered = { disposition: 'contention', cell: 'other-session/lease-live', reason: 'x', recordId: 'some-id', pid: 123, killLine: 'taskkill /PID 123 /F', record: { producer: 'ui-shot' } };
    const observed = { disposition: 'report', cell: 'observed-only', reason: 'y', recordId: null, pid: 456, killLine: 'taskkill /PID 456 /F', record: null };

    const registeredLine = describeEntry(registered);
    assert.match(registeredLine, /resolve via sweep: node scripts\/dev\/agent-spawn-sweep\.cjs/, 'a registered entry must lead with the safe sweep remedy');
    assert.match(registeredLine, /taskkill/, 'the raw kill line stays present as an explicit, last-resort fallback');
    assert.ok(
      registeredLine.indexOf('agent-spawn-sweep.cjs') < registeredLine.indexOf('taskkill'),
      'the sweep remedy must come BEFORE the bare taskkill line, not merely exist somewhere in the output',
    );

    const observedLine = describeEntry(observed);
    assert.doesNotMatch(observedLine, /agent-spawn-sweep\.cjs/, 'an observed-tier entry (no recordId) has no register row for a sweep to act on');
    assert.match(observedLine, /taskkill/);
  });

  // ── F-1 + F-2a/F-3, end to end through the real CLI subprocess (the documented invocation) ──
  if (process.platform !== 'win32') {
    skipped.push('CLI end-to-end (F-1/F-2a/F-3): win32-only (readProcessTable/taskkill)');
  } else {
    await check('CLI end-to-end: no --session-id flag (the documented invocation) resolves the caller from its harness process and reaps its own live spawn', async () => {
      const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-cli-e2e-'));
      const child = spawnDisposableChild();
      try {
        const identity = await identityFor(child.pid);
        const now = NOW();
        const sessionId = `cli-e2e-session-${process.pid}`;
        const harness = writeHarnessFixture(path.join(tmp, 'identity'));
        const dir = path.join(tmp, 'agent-spawns');
        const record = await buildAgentSpawnRecord({
          recordId: 'w5-cli-e2e',
          producer: 'cli-e2e-test',
          pid: child.pid,
          creationFileTimeUtc: identity.creationFileTimeUtc,
          cmdlineFingerprint: identity.cmdlineFingerprint,
          port: 40200,
          leaseDurationSec: 1,
          sessionId,
          owner: harness.owner,
          now,
        });
        // Lapsed lease, NO activity stamp at all for this session. On a lapsed-lease-alone
        // basis that would read as CONTENTION (unknown activity -> leave) — so a SAME-SESSION
        // reap here is only reachable if the CLI actually resolved `sessionId` as the caller's
        // own, which is exactly F-2a/F-3's fix. Get session resolution wrong (null, or some
        // other id) and this fixture falls through to contention instead of reaping.
        await writeAgentSpawnRecord({ dir, record: { ...record, lease: LAPSED_LEASE(now) } });

        const res = spawnSync('node', [CLI, '--occasion', 'session-start'], {
          encoding: 'utf8',
          timeout: 15000,
          env: identityEnv(harness.file, { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: tmp, CLAUDE_CODE_SESSION_ID: sessionId }),
        });
        assert.equal(res.status, 0, `CLI exited ${res.status}. stderr:\n${res.stderr}`);
        assert.match(res.stdout, /same-session/, 'the record must classify as same-session - proof the CLI resolved its harness identity as the owner');
        assert.match(res.stdout, /pruned:/, 'the CLI must always run the prune step (F-1), regardless of occasion');

        await new Promise((r) => setTimeout(r, 300));
        const after = readProcessTable();
        assert.ok(after.ok);
        assert.ok(!after.table.some((r) => Number(r?.ProcessId) === child.pid), 'the child must actually be reaped end-to-end through the real CLI subprocess');
      } finally {
        killIfAlive(child.pid);
        await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});
      }
    });
  }

  for (const s of skipped) console.log(`  (skipped) ${s}`);

  if (failures.length) {
    console.error(`861-w5-agent-spawn-sweep.test: ${failures.length} FAILED / ${passed} passed / ${skipped.length} skipped`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exitCode = 1;
    return;
  }
  console.log(`861-w5-agent-spawn-sweep.test: ${passed} passed / ${skipped.length} skipped`);
}

main().finally(() => {
  for (const pid of spawnedPids) killIfAlive(pid);
});
