#!/usr/bin/env node
//
// Tempdoc 606 — hermetic integration test for the admission GATE (acquireAdmission).
// Drives the REAL acquireAdmission end-to-end against an ISOLATED temp state root
// (JUSTSEARCH_DEV_RUNNER_STATE_ROOT) with fast thresholds — exercising the full
// fact-gathering (lease + run.json supervisor liveness + owner presence: the holder's harness
// process in an injected process table, and its dev-stack touch record under owners/),
// the verdict mapping, and the stopRun side effect — WITHOUT a backend and WITHOUT
// touching the shared lease. Complements the pure verdict unit tests by proving the
// gate wiring (audit-driven fixes need a runnable test, slice-execution discipline).
//
// Env MUST be set before requiring dev-runner.cjs (its state paths are module-load consts).

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Temp state MUST be on the same drive as the repo: acquireAdmission resolves run.json via
// path.join(mainRepoRoot, runPath), and a cross-drive path.relative yields an absolute path
// that path.join garbles. Use the worktree's gitignored tmp/ (same F: drive as mainRepoRoot).
const TMPBASE = path.join(__dirname, '..', '..', 'tmp');
fs.mkdirSync(TMPBASE, { recursive: true });
const STATE = fs.mkdtempSync(path.join(TMPBASE, 'devrunner-gate-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = STATE;
process.env.JUSTSEARCH_DEV_ABANDONED_MS = '2000';
process.env.JUSTSEARCH_DEV_IDLE_MS = '3000';
// Identity is hermetic: the caller is an explicit override, and every process-table read goes to
// a fixture this test writes (agent-identity.cjs JUSTSEARCH_PROCESS_TABLE_FIXTURE), so the result
// is the same under Claude, Codex, a plain shell or CI.
for (const k of ['JUSTSEARCH_AGENT_IDENTITY_HANDOFF', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_PID', 'CODEX_THREAD_ID', 'CODEX_SESSION_ID', 'JUSTSEARCH_AGENT_SESSION_ID', 'CI']) delete process.env[k];
const CALLER_ID = 'gate-test-caller';
process.env.JUSTSEARCH_AGENT_IDENTITY = CALLER_ID;
const TABLE = path.join(STATE, 'process-table.json');
process.env.JUSTSEARCH_PROCESS_TABLE_FIXTURE = TABLE;

const require = createRequire(import.meta.url);
const { __test } = require(path.join(__dirname, 'dev-runner.cjs'));
const { acquireAdmission, assertMayMutateRun, mainRepoRoot } = __test;
// Loaded defensively so that, run against a checkout without the identity module, every case
// still reports its own result instead of the whole file dying at load time.
let agentIdentity = null;
try { agentIdentity = require(path.join(__dirname, 'lib', 'agent-identity.cjs')); } catch { /* the cases that need it fail */ }
const DEV_RUNNER = path.join(__dirname, 'dev-runner.cjs');

// A second agent session, as the dev MCP server hands it to the dev-runner it spawns
// (JUSTSEARCH_AGENT_IDENTITY_HANDOFF, honoured only by that direct child).
const INTRUDER = Object.freeze({
  harness: 'codex',
  owner: { harness: 'codex', pid: 900_113, creationTime: '134000000000900113', key: 'codex-900113-134000000000900113' },
  sessionId: 'mcp-intruder-thread',
  source: 'process-ancestry',
});

/** Run a dev-runner command as the MCP server does for a caller: a direct child with the hand-off. */
function runAsMcpCaller(args, identity) {
  const env = { ...process.env, ...agentIdentity.buildHandoffEnv(identity, process.pid) };
  delete env.JUSTSEARCH_AGENT_IDENTITY;
  const res = spawnSync(process.execPath, [DEV_RUNNER, ...args, '--json'], { env, encoding: 'utf8', timeout: 60_000 });
  const line = String(res.stdout || '').trim().split(/\r?\n/).filter(Boolean).pop() || '{}';
  let json = null;
  try { json = JSON.parse(line); } catch { json = { unparsed: res.stdout, stderr: res.stderr }; }
  return { status: res.status, json, stderr: res.stderr };
}

async function refusalOf(fn) {
  try { await fn(); return null; } catch (err) { return err; }
}

const activePath = path.join(STATE, 'active.json');
const ownersDir = path.join(STATE, 'owners');
const runsDir = path.join(STATE, 'runs');
const HOLDER = 'sess-OTHER';
// The holder's harness process (another agent session). Its pid is not a multiple of 4, so no real
// Windows process can be it; liveness comes only from the fixture table.
const HOLDER_OWNER = { harness: 'claude', pid: 900_105, creationTime: '134000000000900105', key: 'claude-900105-134000000000900105' };
const SELF_OWNER = { harness: 'unknown', pid: null, creationTime: null, key: `override-${CALLER_ID}` };
const sleepers = [];

function spawnAliveProc() {
  // A harmless long-lived child whose pid is "alive" for supervisor-liveness, and which
  // stopRun may safely kill on a proceed (it is NOT this test process).
  const p = spawn(process.execPath, ['-e', 'setTimeout(()=>{}, 600000)'], { stdio: 'ignore' });
  sleepers.push(p);
  return p.pid;
}

function writeTable({ holderAlive }) {
  const rows = [{ ProcessId: 4, ParentProcessId: 0, Name: 'System', CommandLine: '', CreationFileTimeUtc: '133000000000000000' }];
  if (holderAlive) rows.push({ ProcessId: HOLDER_OWNER.pid, ParentProcessId: 4, Name: 'claude.exe', CommandLine: 'claude.exe', CreationFileTimeUtc: HOLDER_OWNER.creationTime });
  fs.writeFileSync(TABLE, JSON.stringify({ selfPid: process.pid, rows }));
}

/**
 * @param {object} p
 * @param {'alive'|'ended'} [p.owner]   the holder's harness process state in the table
 * @param {number|null} [p.touchAgoMs]  age of the holder's last dev-stack touch; null = no record
 * @param {object|null} [p.holderOwner] the holder's owner block; null = a pre-change (legacy) holder
 * @param {number} [p.durationSec]      the lease's declared hold
 */
function craft({ runnerPid, leaseMsAhead = 30_000, owner = 'alive', touchAgoMs = null, holderOwner = HOLDER_OWNER, agentSessionId = HOLDER, durationSec = 1 }) {
  fs.mkdirSync(ownersDir, { recursive: true });
  writeTable({ holderAlive: owner === 'alive' });
  const runId = 'run-TEST';
  const runDir = path.join(runsDir, runId);
  fs.mkdirSync(runDir, { recursive: true });
  const runFile = path.join(runDir, 'run.json');
  fs.rmSync(path.join(runDir, 'stop-report.json'), { force: true }); // no report left over from an earlier case
  fs.writeFileSync(runFile, JSON.stringify({ runId, pids: { runnerPid, backend: 999999, frontend: 999998 } }));
  fs.writeFileSync(activePath, JSON.stringify({
    kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
    // runPath is resolved by acquireAdmission via path.join(mainRepoRoot, runPath).
    runPath: path.relative(mainRepoRoot, runFile).split(path.sep).join('/'),
    holder: { source: holderOwner?.harness ?? 'claude', agentSessionId, ...(holderOwner ? { owner: holderOwner } : {}) },
    takeoverPolicy: 'warn', ownershipEpoch: 3,
    lease: { durationSec, renewedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + leaseMsAhead).toISOString(), sequence: 9 },
  }));
  for (const name of fs.readdirSync(ownersDir)) fs.rmSync(path.join(ownersDir, name), { force: true });
  if (holderOwner && touchAgoMs !== null) {
    fs.writeFileSync(path.join(ownersDir, `${holderOwner.key}.json`), JSON.stringify({
      schema: 'owner-touch.v1', key: holderOwner.key, harness: holderOwner.harness,
      pid: holderOwner.pid, creationTime: holderOwner.creationTime, lastDevStackTouchAt: ago(touchAgoMs),
    }));
  }
}

function clearActive() { try { fs.unlinkSync(activePath); } catch {} }
function ago(ms) { return new Date(Date.now() - ms).toISOString(); }

const tests = [
  ['NO_OWNER → proceed (no disposition)', async () => {
    clearActive();
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, undefined);
  }],

  ['dead supervisor → proceed stale_reclaim', async () => {
    craft({ runnerPid: 999999, owner: 'alive', touchAgoMs: 500 }); // dead supervisor pid
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'stale_reclaim');
    assert.ok(!fs.existsSync(activePath), 'active.json cleared on reclaim');
  }],

  ['ABANDONED (holder harness process ended) + deny -> proceed abandoned_reclaim (KEY: deny proceeds)', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'ended', touchAgoMs: 300 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'abandoned_reclaim');
    assert.ok(!fs.existsSync(activePath), 'active.json cleared on takeover');
  }],

  ['IDLE (harness running, dev-stack touch stale) + deny -> conflict idle_owner', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 10_000 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.reason, 'idle_owner');
    assert.equal(r.verdict, 'IDLE_HOLD');
  }],

  ['IDLE + warn → proceed idle_takeover', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 10_000 });
    const r = await acquireAdmission({ takeover: 'warn' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'idle_takeover');
  }],

  ['ACTIVE (harness running, fresh touch) + deny -> conflict fresh_owner CONTENTION (two owners on start)', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 300 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.reason, 'fresh_owner');
    assert.equal(r.verdict, 'CONTENTION');
  }],

  ['ACTIVE + warn → proceed warned_takeover', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 300 });
    const r = await acquireAdmission({ takeover: 'warn' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'warned_takeover');
  }],

  ['UNKNOWN use (harness running, no touch record) + deny -> conflict CONTENTION (conservative, NOT abandoned)', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: null });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.verdict, 'CONTENTION');
    assert.equal(r.reason, 'fresh_owner');
  }],

  ['declared hold: a stale touch inside the lease\'s declared duration is not idle -> conflict CONTENTION', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 10_000, durationSec: 60 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.verdict, 'CONTENTION');
  }],

  ['self restart: the holder\'s owner key is the caller\'s -> proceed owner_restart even under deny', async () => {
    craft({ runnerPid: spawnAliveProc(), holderOwner: SELF_OWNER, agentSessionId: CALLER_ID, touchAgoMs: 300 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'owner_restart');
  }],

  ['legacy holder (no owner block) carrying the caller\'s own label is an unknown owner, never self -> conflict', async () => {
    craft({ runnerPid: spawnAliveProc(), holderOwner: null, agentSessionId: CALLER_ID });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.verdict, 'CONTENTION');
  }],

  ['two owners on stop: another session\'s stop through the MCP hand-off is OWNER_CONFLICT; the stack is untouched', async () => {
    const runnerPid = spawnAliveProc();
    craft({ runnerPid, owner: 'alive', touchAgoMs: 300 });
    const before = fs.readFileSync(activePath, 'utf8');
    const r = runAsMcpCaller(['stop'], INTRUDER);
    assert.equal(r.status, 1, JSON.stringify(r.json));
    assert.equal(r.json?.error?.code, 'OWNER_CONFLICT', JSON.stringify(r.json));
    assert.equal(r.json.error.holder?.owner?.key, HOLDER_OWNER.key, 'the refusal names the holder by its owner key');
    assert.equal(fs.readFileSync(activePath, 'utf8'), before, 'the lease record is untouched');
    assert.doesNotThrow(() => process.kill(runnerPid, 0), 'the holder\'s supervisor was not stopped');
  }],

  ['two owners on clean: another session\'s cleanup through the MCP hand-off is OWNER_CONFLICT', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 300 });
    const r = runAsMcpCaller(['cleanup', '--clean', 'soft'], INTRUDER);
    assert.equal(r.status, 1, JSON.stringify(r.json));
    assert.equal(r.json?.error?.code, 'OWNER_CONFLICT', JSON.stringify(r.json));
    assert.ok(fs.existsSync(activePath), 'the lease record survives a refused cleanup');
  }],

  ['the hand-off is honoured only by its direct child: a forwarded copy does not make the intruder the owner', async () => {
    const forwarded = agentIdentity.buildHandoffEnv({ ...INTRUDER, owner: HOLDER_OWNER, harness: 'claude' }, process.pid + 4);
    const env = { ...forwarded };
    const id = agentIdentity.resolveAgentIdentity({ env, ppid: process.pid, readTable: () => ({ ok: false, reason: 'none' }), noCache: true });
    assert.equal(id.owner, null, 'a hand-off addressed to another parent is ignored');
  }],

  ['stop/clean gate: the owner itself passes, as does --force, and an ended owner\'s stack', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 300 });
    const self = { harness: 'claude', owner: HOLDER_OWNER, sessionId: HOLDER, source: 'test' };
    assert.equal(await refusalOf(() => assertMayMutateRun({ callerIdentity: self }, 'stop')), null, 'the owner stops its own stack');
    assert.equal(await refusalOf(() => assertMayMutateRun({ callerIdentity: INTRUDER, force: true }, 'stop')), null, '--force is an explicit takeover');
    const refused = await refusalOf(() => assertMayMutateRun({ callerIdentity: INTRUDER }, 'cleanup'));
    assert.equal(refused?.code, 'OWNER_CONFLICT');
    craft({ runnerPid: spawnAliveProc(), owner: 'ended', touchAgoMs: 300 });
    assert.equal(await refusalOf(() => assertMayMutateRun({ callerIdentity: INTRUDER }, 'stop')), null, 'an ended owner\'s stack is reclaimable');
  }],

  ['two owners: a running holder is not treated as self by another caller with the same readable label', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 300, agentSessionId: CALLER_ID });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.reason, 'fresh_owner');
  }],

  // --- Late abandoned reclaims. The owner ended; its supervisor still runs and stopped renewing,
  // so the lease lapsed after its declared hold. That is an abandoned reclaim, not a stale one,
  // however long the hold was, and (like an early abandoned reclaim) it is not interference.
  ['late abandoned, default 30 s lease: ended owner + lapsed lease + live supervisor -> abandoned_reclaim, not logged as interference', async () => {
    const before = interferenceRows().length;
    craft({ runnerPid: spawnAliveProc(), owner: 'ended', touchAgoMs: 300, durationSec: 30, leaseMsAhead: -5_000 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed', JSON.stringify(r));
    assert.equal(r.verdict, 'TAKEOVER_ABANDONED');
    assert.equal(r.disposition, 'abandoned_reclaim');
    assert.equal(stopReportDisposition(), 'abandoned_reclaim', 'the stop report records abandoned_reclaim');
    assert.equal(interferenceRows().length, before, 'no interference row for an abandoned reclaim');
  }],
  ['late abandoned, 600 s declared hold lapsed: abandoned_reclaim, not logged as interference', async () => {
    const before = interferenceRows().length;
    craft({ runnerPid: spawnAliveProc(), owner: 'ended', touchAgoMs: 650_000, durationSec: 600, leaseMsAhead: -1_000 });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed', JSON.stringify(r));
    assert.equal(r.disposition, 'abandoned_reclaim');
    assert.equal(stopReportDisposition(), 'abandoned_reclaim');
    assert.equal(interferenceRows().length, before);
  }],
  ['late abandoned + MUST_COMPLETE op in flight still proceeds under deny; only the recorded reason changes', async () => {
    craft({ runnerPid: spawnAliveProc(), owner: 'ended', touchAgoMs: 300, durationSec: 30, leaseMsAhead: -5_000 });
    writeOpLeases([{ opId: 'mc-1', opClass: 'indexing.migration', criticality: 'MUST_COMPLETE' }]);
    try {
      const r = await acquireAdmission({ takeover: 'deny' });
      assert.equal(r.action, 'proceed', JSON.stringify(r));
      assert.equal(r.disposition, 'abandoned_reclaim');
      assert.equal(r.criticalOpsInterrupted, undefined, 'same fields as the stale reclaim it replaces');
      assert.ok(!fs.existsSync(activePath), 'the stack was reclaimed');
    } finally { writeOpLeases(null); }
  }],
  ['S2 at the gate: dead supervisor (owner ended too), legacy holder, live owner with a lapsed lease -> stale_reclaim, logged', async () => {
    const cases = [
      ['dead supervisor, owner ended', () => craft({ runnerPid: 999_997, owner: 'ended', touchAgoMs: 300, leaseMsAhead: -5_000 })],
      ['legacy holder (no owner block), lapsed lease', () => craft({ runnerPid: spawnAliveProc(), holderOwner: null, leaseMsAhead: -5_000 })],
      ['override owner (no process identity: unknown), lapsed lease', () => craft({ runnerPid: spawnAliveProc(), holderOwner: { harness: 'unknown', pid: null, creationTime: null, key: 'override-some-other' }, leaseMsAhead: -5_000 })],
      ['live owner, lapsed lease', () => craft({ runnerPid: spawnAliveProc(), owner: 'alive', touchAgoMs: 300, leaseMsAhead: -5_000 })],
    ];
    for (const [label, setup] of cases) {
      const before = interferenceRows().length;
      setup();
      const r = await acquireAdmission({ takeover: 'deny' });
      assert.equal(r.action, 'proceed', `${label}: ${JSON.stringify(r)}`);
      assert.equal(r.disposition, 'stale_reclaim', label);
      assert.equal(stopReportDisposition(), 'stale_reclaim', label);
      const rows = interferenceRows();
      assert.equal(rows.length, before + 1, `${label}: a stale reclaim is still logged`);
      assert.equal(rows.at(-1).disposition, 'stale_reclaim', label);
    }
  }],
  ['S3 parity: quick_health (cheap), acquire_when_free (full) and the gate give the same late-abandoned verdict', async () => {
    const { buildOwnershipProjection } = await import(pathToFileURL(path.join(__dirname, 'justsearch-dev-mcp', 'server.mjs')).href);
    const recommendedTakeoverFor = require(path.join(__dirname, 'lib', 'ownership-verdict.cjs')).recommendedTakeoverFor;
    // A fixture main root, so the projection reads no real repo state (op-leases, run records).
    const fakeMain = path.join(STATE, 'main');
    fs.mkdirSync(fakeMain, { recursive: true });
    craft({ runnerPid: spawnAliveProc(), owner: 'ended', touchAgoMs: 300, durationSec: 30, leaseMsAhead: -5_000 });
    const active = JSON.parse(fs.readFileSync(activePath, 'utf8'));
    for (const evidence of ['cheap', 'full']) {
      const { decision, ownership } = await buildOwnershipProjection({
        mainRepoRoot: fakeMain, callerRepoRoot: fakeMain, callerIdentity: INTRUDER, takeover: 'deny', active, evidence, stateRoot: STATE,
      });
      assert.equal(decision.verdict, 'TAKEOVER_ABANDONED', `${evidence}: ${JSON.stringify(decision)}`);
      assert.equal(ownership.verdict, 'TAKEOVER_ABANDONED', evidence);
      assert.equal(ownership.grade, 'abandoned', evidence);
      assert.equal(recommendedTakeoverFor(decision), 'deny', `${evidence}: acquirable without a takeover flag`);
    }
    const gate = await acquireAdmission({ takeover: 'deny', sessionId: INTRUDER.sessionId, callerIdentity: INTRUDER });
    assert.equal(gate.verdict, 'TAKEOVER_ABANDONED');
    assert.equal(gate.disposition, 'abandoned_reclaim');
  }],
];

function stopReportDisposition() {
  try { return JSON.parse(fs.readFileSync(path.join(runsDir, 'run-TEST', 'stop-report.json'), 'utf8')).disposition ?? null; } catch { return null; }
}

function interferenceRows() {
  try {
    return fs.readFileSync(path.join(STATE, 'interference-events.ndjson'), 'utf8').split(/\r?\n/).filter(Boolean).map((l) => JSON.parse(l));
  } catch { return []; }
}

/** The backend's op-lease registry under the isolated state root; null removes it. */
function writeOpLeases(entries) {
  const file = path.join(STATE, 'op-leases.json');
  if (!entries) { fs.rmSync(file, { force: true }); return; }
  const expiresAt = new Date(Date.now() + 600_000).toISOString();
  fs.writeFileSync(file, JSON.stringify({ schema: 'op-leases.v1', opLeases: entries.map((e) => ({ expiresAt, ...e })) }));
}

let pass = 0, fail = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log(`  PASS  ${name}`); pass++; }
  catch (e) { console.error(`  FAIL  ${name}: ${e.message}`); fail++; }
}
for (const p of sleepers) { try { process.kill(p.pid); } catch {} }
try { fs.rmSync(STATE, { recursive: true, force: true }); } catch {}
console.log(`test-dev-runner-gate-integration: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
