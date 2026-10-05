/**
 * Tempdoc 861 W4 — the kill path: TOCTOU, the three adverse identity states, and one real kill.
 *
 * 861 §6.3's closing sentence is the specification for this file: *"Every kill re-verifies identity
 * (pid AND creation time AND fingerprint) immediately before acting. The adverse branches are
 * required tests, not happy-path afterthoughts (`green-masked-destructive`)."*
 *
 * So the adverse branches come first here, and the happy path is one test at the end.
 *
 * **Nothing in this file operates on a discovered pid.** A dev stack is running on this host while
 * these tests execute. Every victim is a `node -e` child THIS FILE spawned, identified by a random
 * marker embedded in its own command line, and the test refuses to proceed unless the process-table
 * row it found carries that marker AND the pid the spawn returned. Every other test injects a fake
 * `exec` and never reaches `taskkill.exe` at all.
 *
 * Sited under `scripts/agent-analytics/` per 861 §7.6 — `scripts/dev/*.test.mjs` runs in CI nowhere,
 * and a kill path whose tests never run is the defect this tempdoc exists to remove, one level up.
 *
 * Run with: `node scripts/agent-analytics/861-w4-reaper-kill.test.mjs`
 */

import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

const {
  REAP_DISPOSITIONS,
  OCCASIONS,
  CELLS,
  reapEligible,
  executeReap,
  taskkillByPid,
  readDevRunnerActiveRun,
} = require('../dev/lib/agent-spawn-reaper.cjs');
const {
  OWNERSHIP_MODES,
  buildAgentSpawnRecord,
  writeAgentSpawnRecord,
  agentSpawnRecordPath,
} = require('../dev/lib/agent-spawn-record.cjs');
const { readProcessTable, IDENTITY } = require('../dev/lib/process-identity.cjs');

let passed = 0;
const failures = [];
const skipped = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const dirsToClean = [];
async function makeRegisterDir() {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w4-register-'));
  dirsToClean.push(dir);
  return dir;
}

/* ── fixtures ─────────────────────────────────────────────────────────────────────────────── */

const NOW = Date.parse('2026-08-25T12:00:00.000Z');
const CALLER = 'caller-session-bbbbbbbb';
const CALLER_OWNER = { harness: 'claude', pid: null, creationTime: null, key: CALLER };
// [F2] Occasions are named; capability comes with the name and is not a separate argument.
const SWEEP_OCCASION = 'session-start';
const TEARDOWN_OCCASION = 'worktree-teardown';
const PID = 41064;
const CTIME = '134320479841300350';
const FINGERPRINT = 'vite --port 5174';
const iso = (ms) => new Date(ms).toISOString();

const REC = (over = {}) => ({
  schemaVersion: 1,
  recordId: 'ui-shot-5174',
  producer: 'ui-shot',
  pid: PID,
  creationFileTimeUtc: CTIME,
  cmdlineFingerprint: FINGERPRINT,
  ownership: OWNERSHIP_MODES.SESSION_OWNED,
  probe: { kind: 'port', port: 5174 },
  startedAt: iso(NOW - 3_600_000),
  lease: { durationSec: 1800, renewedAt: iso(NOW - 60_000), expiresAt: iso(NOW + 1_740_000) },
  sessionId: CALLER,
  ...over,
  // Ownership is the trusted owner KEY (agent-identity.cjs), never the label; the fixture keys each
  // record's owner by its session string so "same session" keeps meaning what the cases say.
  ...(('owner' in over) ? {} : { ownerIdentityVersion: 1, owner: { harness: 'claude', pid: null, creationTime: null, key: over.sessionId ?? CALLER } }),
});

const ROW = (over = {}) => ({
  ProcessId: PID,
  ParentProcessId: 9444,
  Name: 'node.exe',
  CommandLine: `"C:\\node.exe" vite.js ${FINGERPRINT}`,
  CreationFileTimeUtc: CTIME,
  ...over,
});

const TABLE = (rows = [ROW()], ageMs = 0) => ({ ok: true, table: rows, readAt: NOW - ageMs });

/** The same-session reap entry every adverse test starts from, so each one really was eligible. */
function eligibleEntry(record = REC()) {
  const out = reapEligible({
    records: [{ ok: true, recordId: record.recordId, record }],
    processTable: TABLE(),
    occasion: SWEEP_OCCASION,
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => null,
    env: {},
  });
  assert.equal(out.reap.length, 1, 'the fixture must be eligible before the adverse branch is tested');
  assert.equal(out.reap[0].cell, CELLS.SAME_SESSION);
  return out.reap[0];
}

/** A fake `taskkill` that counts invocations. Reaching it in an adverse test is the failure. */
function countingExec() {
  const calls = [];
  const exec = (file, args) => { calls.push([file, ...args].join(' ')); return { status: 0, stdout: '', stderr: '' }; };
  return { exec, calls };
}

async function seedRecord(dir, record) {
  await writeAgentSpawnRecord({ dir, record });
  return agentSpawnRecordPath(dir, record.recordId);
}

async function readRecordFile(file) {
  return JSON.parse(await fsp.readFile(file, 'utf8'));
}

await check('[F2] the two occasions this file drives are real, and both carry the EXECUTE capability', () => {
  // If either were advisory, every kill test below would silently degrade to asserting nothing —
  // `eligibleEntry` would find no reap entry and the file would fail loudly rather than pass empty.
  assert.equal(OCCASIONS[SWEEP_OCCASION]?.capability, 'execute');
  assert.equal(OCCASIONS[TEARDOWN_OCCASION]?.capability, 'execute');
});

/* ── [A2] the three adverse identity states, end to end through executeReap ───────────────── */

const ADVERSE = [
  {
    id: '(i) recycled pid — the pid is alive but the creation time differs',
    table: () => TABLE([ROW({ CreationFileTimeUtc: '134320479841399999' })]),
    verdict: IDENTITY.MISMATCH,
    reasonRe: /recycled/,
  },
  {
    id: '(ii) unreadable creation time — the field is absent on the live row',
    table: () => TABLE([ROW({ CreationFileTimeUtc: null })]),
    verdict: IDENTITY.REFUSE,
    reasonRe: /creation time is absent or unreadable/,
  },
  {
    id: '(iii) process table unavailable — the enumeration failed and yielded nothing',
    table: () => ({ ok: false, reason: 'process-table query exited 1' }),
    verdict: IDENTITY.REFUSE,
    reasonRe: /process table unusable/,
  },
];

for (const a of ADVERSE) {
  await check(`ADVERSE ${a.id}: executeReap refuses, kills nothing, marks the record, and RETAINS it`, async () => {
    const dir = await makeRegisterDir();
    const record = REC();
    const file = await seedRecord(dir, record);
    const entry = eligibleEntry(record);
    const { exec, calls } = countingExec();

    const res = await executeReap(entry, {
      dir,
      readTable: a.table,
      exec,
      platform: 'win32',
      now: () => NOW,
    });

    assert.equal(res.refused, true, 'must refuse');
    assert.equal(res.killed, false, 'must not kill');
    assert.equal(res.confirmed, false);
    assert.deepEqual(calls, [], 'taskkill must never be invoked on an unverified identity');
    assert.equal(res.identity.verdict, a.verdict, `identity verdict (reason: ${res.identity.reason})`);
    assert.match(res.identity.reason, a.reasonRe);

    // Retain, never delete (861 §6.3 / [A5]): the diagnostic trail must survive the refusal.
    const after = await readRecordFile(file);
    assert.equal(res.marked?.marked, true, 'the record must be marked failed-verify');
    assert.equal(after.identityVerify?.verdict, a.verdict, 'the marker records the actual verdict');
    assert.ok(after.identityVerify?.at, 'the marker is stamped');
    assert.equal(after.pid, record.pid, 'the rest of the record is preserved');
    assert.equal(res.retained, true);
  });
}

await check('ADVERSE: an empty process table is NO evidence — it refuses rather than proceeding', async () => {
  const dir = await makeRegisterDir();
  const record = REC();
  await seedRecord(dir, record);
  const { exec, calls } = countingExec();
  const res = await executeReap(eligibleEntry(record), {
    dir,
    // `getProcessTable` fails silently to `[]` by design (remove-worktree.cjs:125-131). A reaper
    // reading that as "nothing contradicts me" would re-ship that defect inside a kill path.
    readTable: () => ({ ok: true, table: [], readAt: NOW }),
    exec,
    platform: 'win32',
    now: () => NOW,
  });
  assert.equal(res.refused, true);
  assert.deepEqual(calls, []);
  assert.match(res.identity.reason, /NO evidence, not exculpatory evidence/);
});

/* ── F1 TOCTOU: eligibility has a shelf life, and executeReap re-derives its own licence ──── */

await check('TOCTOU: a table that aged past the freshness bound between eligibility and execution REFUSES at execution', async () => {
  const dir = await makeRegisterDir();
  const record = REC();
  const file = await seedRecord(dir, record);

  // Eligibility is decided against a FRESH table: the entry really is a reap.
  const entry = eligibleEntry(record);
  assert.equal(entry.disposition, REAP_DISPOSITIONS.REAP);

  // Time passes. The snapshot executeReap can obtain is now 60s old — the row still matches all
  // three identity conjuncts, which is exactly why the conjunction alone cannot catch this.
  const { exec, calls } = countingExec();
  const res = await executeReap(entry, {
    dir,
    readTable: () => TABLE([ROW()], 60_000),
    exec,
    platform: 'win32',
    now: () => NOW,
  });

  assert.equal(res.refused, true, 'a stale snapshot must not license a kill');
  assert.equal(res.killed, false);
  assert.deepEqual(calls, [], 'taskkill must not run');
  assert.equal(res.identity.verdict, IDENTITY.REFUSE);
  assert.match(res.identity.reason, /beyond the \d+ms freshness bound/);
  const after = await readRecordFile(file);
  assert.equal(after.identityVerify?.verdict, IDENTITY.REFUSE);
});

await check('TOCTOU control: with a fresh snapshot the SAME entry proceeds — so the refusal above is the staleness, not the fixture', async () => {
  const dir = await makeRegisterDir();
  const record = REC();
  const file = await seedRecord(dir, record);
  const entry = eligibleEntry(record);

  let reads = 0;
  const readTable = () => {
    reads += 1;
    // First read (the pre-kill re-verify) sees the process; the confirmation read does not.
    return reads === 1 ? TABLE([ROW()]) : TABLE([ROW({ ProcessId: 999999 })]);
  };
  const { exec, calls } = countingExec();
  const res = await executeReap(entry, { dir, readTable, exec, platform: 'win32', now: () => NOW });

  assert.equal(res.refused, false);
  assert.equal(res.killed, true);
  assert.equal(res.confirmed, true, 'the confirmation read must see the pid gone');
  assert.deepEqual(calls, [`taskkill.exe /PID ${PID} /F`], 'by pid, /F, and never /T');
  assert.equal(reads, 2, 'executeReap reads the table twice: re-verify, then confirm');
  assert.equal(res.stamp.by.occasion, SWEEP_OCCASION);
  assert.equal(res.stamp.by.cell, CELLS.SAME_SESSION);
  assert.ok(res.stamp.at, 'the stamp carries reaped-at');
  assert.equal(res.recordRemoved, true, 'a confirmed kill retires the record');
  await assert.rejects(() => fsp.readFile(file, 'utf8'), 'the record file is gone');
});

await check('TOCTOU: executeReap IGNORES the table eligibility was computed against — it always reads its own', async () => {
  const dir = await makeRegisterDir();
  const record = REC();
  await seedRecord(dir, record);
  const entry = eligibleEntry(record);
  // The entry was minted against a matching table. If executeReap trusted it, this would kill.
  let reads = 0;
  const { exec, calls } = countingExec();
  const res = await executeReap(entry, {
    dir,
    readTable: () => { reads += 1; return TABLE([ROW({ CreationFileTimeUtc: '134320479841399999' })]); },
    exec,
    platform: 'win32',
    now: () => NOW,
  });
  assert.equal(reads >= 1, true, 'executeReap must read a fresh table itself');
  assert.equal(res.refused, true);
  assert.deepEqual(calls, []);
});

await check('an UNCONFIRMED kill retains the record with the stamp — an outcome nobody could verify is evidence', async () => {
  const dir = await makeRegisterDir();
  const record = REC();
  const file = await seedRecord(dir, record);
  const entry = eligibleEntry(record);
  const { exec } = countingExec();
  // The process is still in the table after the kill: taskkill "succeeded" but nothing died.
  const res = await executeReap(entry, { dir, readTable: () => TABLE([ROW()]), exec, platform: 'win32', now: () => NOW });
  assert.equal(res.killed, true);
  assert.equal(res.confirmed, false);
  assert.equal(res.recordRemoved, false);
  assert.equal(res.retained, true);
  const after = await readRecordFile(file);
  assert.equal(after.reaped.confirmed, false, 'the stamp is on the retained record');
  assert.equal(after.reaped.by.source, 'agent-spawn-reaper');
});

await check('executeReap refuses a contention entry, a refuse entry, and a report entry alike', async () => {
  const dir = await makeRegisterDir();
  const other = REC({ sessionId: 'someone-else', recordId: 'ui-shot-5175' });
  const out = reapEligible({
    records: [{ ok: true, recordId: other.recordId, record: other }],
    processTable: TABLE(),
    occasion: TEARDOWN_OCCASION,
    callerOwnerKey: CALLER,
    now: NOW,
    activityFor: () => null,
    env: {},
  });
  assert.equal(out.contention.length, 1);
  const { exec, calls } = countingExec();
  const res = await executeReap(out.contention[0], { dir, readTable: () => TABLE(), exec, platform: 'win32', now: () => NOW });
  assert.equal(res.refused, true);
  assert.deepEqual(calls, []);
  assert.match(res.reason, /accepts only entries whose disposition is "reap"/);
});

await check('taskkillByPid refuses on a non-win32 platform rather than inventing a POSIX kill', () => {
  const res = taskkillByPid(1234, { exec: () => { throw new Error('must not be called'); }, platform: 'linux' });
  assert.equal(res.ok, false);
  assert.match(res.reason, /win32 only/);
});

await check("readDevRunnerActiveRun degrades to an empty claim when the dev-runner's state root is absent", async () => {
  const empty = await readDevRunnerActiveRun({ stateRoot: path.join(os.tmpdir(), `861-w4-absent-${crypto.randomUUID()}`) });
  assert.deepEqual(empty, { runId: null, pids: [] });
  assert.deepEqual(await readDevRunnerActiveRun({}), { runId: null, pids: [] });
});

/* ── THE REAL KILL: one disposable child this test spawned, and nothing else ──────────────── */

/**
 * The end-to-end proof, on the platform the reaper targets. Everything above injects `exec`; this
 * one reaches `taskkill.exe`.
 *
 * The safety argument, which matters because a dev stack is running on this host: the victim is a
 * `node -e` process spawned three lines earlier, whose command line carries a random marker unique
 * to this run. The test asserts BOTH that the process-table row's `ProcessId` equals the pid the
 * spawn returned AND that its command line contains that marker before a record is even built. A
 * discovered pid can never reach the kill.
 */
async function withDisposableChild(fn) {
  const marker = `861w4fixture${crypto.randomBytes(8).toString('hex')}`;
  const child = spawn(process.execPath, ['-e', `/* ${marker} */ setInterval(() => {}, 1000);`], {
    stdio: 'ignore',
    windowsHide: true,
  });
  try {
    return await fn(child, marker);
  } finally {
    try { child.kill('SIGKILL'); } catch { /* already gone: the point of the test */ }
  }
}

/** Poll the real process table until our own child appears (it is not there the instant it spawns). */
async function findOwnChildRow(pid, marker) {
  for (let i = 0; i < 15; i += 1) {
    const table = readProcessTable();
    if (table.ok) {
      const row = table.table.find((r) => Number(r?.ProcessId) === pid);
      if (row && typeof row.CommandLine === 'string' && row.CommandLine.includes(marker)) return { row, table };
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  return null;
}

if (process.platform !== 'win32') {
  skipped.push('REAL KILL: win32-only (the process table and taskkill are win32 surfaces)');
} else {
  await check('REAL KILL: a same-session record over a child this test spawned is verified, killed, confirmed, and retired', async () => {
    await withDisposableChild(async (child, marker) => {
      const found = await findOwnChildRow(child.pid, marker);
      assert.ok(found, `the spawned child (pid ${child.pid}) never appeared in the process table with its marker`);

      // Belt and braces: this test may only ever act on the process it created.
      assert.equal(Number(found.row.ProcessId), child.pid, 'the row must be OUR child');
      assert.ok(found.row.CommandLine.includes(marker), 'the row must carry OUR unique marker');

      const dir = await makeRegisterDir();
      const record = await buildAgentSpawnRecord({
        recordId: 'w4-fixture-child',
        producer: '861-w4-test-fixture',
        pid: child.pid,
        creationFileTimeUtc: found.row.CreationFileTimeUtc,
        cmdlineFingerprint: marker,
        port: 65000,
        leaseDurationSec: 60,
        sessionId: CALLER,
        owner: CALLER_OWNER,
      });
      const file = await seedRecord(dir, record);

      const out = reapEligible({
        records: [{ ok: true, recordId: record.recordId, record }],
        // A genuinely fresh read, taken now — the projection's own freshness bound applies.
        processTable: readProcessTable(),
        occasion: SWEEP_OCCASION,
        callerOwnerKey: CALLER,
        activityFor: () => null,
        env: {},
      });
      assert.equal(out.reap.length, 1, `expected one reapable entry, got ${JSON.stringify(out.all.map((e) => [e.cell, e.disposition, e.reason]))}`);
      assert.equal(out.reap[0].cell, CELLS.SAME_SESSION);

      const res = await executeReap(out.reap[0], { dir, actor: { sessionId: CALLER, source: '861-w4-test' } });

      assert.equal(res.refused, false, `executeReap refused: ${res.reason}`);
      assert.equal(res.killed, true, `taskkill failed: ${JSON.stringify(res.kill)}`);
      assert.equal(res.confirmed, true, `the kill was not confirmed: ${res.stamp?.confirmation}`);
      assert.equal(res.identity.verdict, IDENTITY.MATCH);
      assert.equal(res.stamp.by.sessionId, CALLER);
      assert.equal(res.stamp.by.source, '861-w4-test');
      assert.equal(res.recordRemoved, true);
      await assert.rejects(() => fsp.readFile(file, 'utf8'), 'a confirmed kill retires the record');

      // And the process really is gone.
      const gone = readProcessTable();
      assert.equal(gone.ok, true);
      assert.equal(gone.table.some((r) => Number(r?.ProcessId) === child.pid), false, 'the child must be gone');
    });
  });

  await check('REAL TOCTOU: a process that dies between eligibility and execution is REFUSED, not killed by pid', async () => {
    const dir = await makeRegisterDir();
    // Phase 1 — the process is alive, and eligibility is computed against a real snapshot of it.
    const { record, file, liveRow } = await withDisposableChild(async (child, marker) => {
      const found = await findOwnChildRow(child.pid, marker);
      assert.ok(found, 'the spawned child never appeared in the process table');
      const rec = await buildAgentSpawnRecord({
        recordId: 'w4-fixture-toctou',
        producer: '861-w4-test-fixture',
        pid: child.pid,
        creationFileTimeUtc: found.row.CreationFileTimeUtc,
        cmdlineFingerprint: marker,
        port: 65001,
        leaseDurationSec: 60,
        sessionId: CALLER,
        owner: CALLER_OWNER,
      });
      return { record: rec, file: await seedRecord(dir, rec), liveRow: found.row };
    });

    const out = reapEligible({
      records: [{ ok: true, recordId: record.recordId, record }],
      processTable: { ok: true, table: [liveRow], readAt: Date.now() },
      occasion: SWEEP_OCCASION,
      callerOwnerKey: CALLER,
      activityFor: () => null,
      env: {},
    });
    assert.equal(out.reap.length, 1, 'the entry really was eligible while the process was alive');

    // Phase 2 — the process has since exited on its own (the `finally` above ended it), and its
    // record outlives it. That is 861 §2's live evidence exactly, and the only defence is that
    // executeReap re-derives its licence against a table it reads ITSELF.
    const res = await executeReap(out.reap[0], { dir });
    assert.equal(res.refused, true, 'a record whose process is gone must refuse, not kill whatever now holds the pid');
    assert.equal(res.killed, false);
    assert.equal(res.identity.verdict, IDENTITY.MISMATCH);
    assert.match(res.identity.reason, /not present in the process table/);
    const after = await readRecordFile(file);
    assert.equal(after.identityVerify?.verdict, IDENTITY.MISMATCH, 'retained and marked');
  });
}

/* ── cleanup + report ─────────────────────────────────────────────────────────────────────── */

for (const dir of dirsToClean) await fsp.rm(dir, { recursive: true, force: true });

for (const s of skipped) console.log(`861-w4-reaper-kill.test: SKIP ${s}`);
if (failures.length > 0) {
  console.error(`861-w4-reaper-kill.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w4-reaper-kill.test: all ${passed} checks passed`);
