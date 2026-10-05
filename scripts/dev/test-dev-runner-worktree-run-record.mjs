#!/usr/bin/env node
//
// The run record of a stack started from a worktree. The dev-runner keeps its state under the
// MAIN checkout, but a stack launched from a worktree used to store `active.runPath` relative to
// that worktree (`../../../tmp/dev-runner/runs/...`). Every reader joined it onto the main root,
// found nothing, judged the supervisor dead (RECLAIM_DEAD) and let anyone stop, clean or start
// over a live, actively used stack.
//
// These cases build that exact record - the run directory under an isolated state root, the
// runPath relative to a worktree-like launch directory that is not the main root - and drive the
// real gates: the start admission (acquireAdmission), the stop/clean gate through the dev-runner
// CLI as the MCP server spawns it, and the MCP ownership projection. They also pin the fail-safe:
// a run record that cannot be read behind an unexpired lease is an unknown supervisor, never a
// dead one.
//
// Hermetic: the state root is a temp directory (JUSTSEARCH_DEV_RUNNER_STATE_ROOT), every process
// table read goes to a fixture (JUSTSEARCH_PROCESS_TABLE_FIXTURE), the "supervisor" is a sleeper
// child this test spawns, and no case starts a stack (start is exercised through its admission
// gate only). Env MUST be set before requiring dev-runner.cjs (its state paths are load-time).

import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TMPBASE = path.join(__dirname, '..', '..', 'tmp');
fs.mkdirSync(TMPBASE, { recursive: true });
const STATE = fs.mkdtempSync(path.join(TMPBASE, 'devrunner-wt-record-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = STATE;
process.env.JUSTSEARCH_DEV_ABANDONED_MS = '2000';
process.env.JUSTSEARCH_DEV_IDLE_MS = '3000';
process.env.JUSTSEARCH_DEV_MCP_LOG_NDJSON = '0';
const IDENTITY_KEYS = ['JUSTSEARCH_AGENT_IDENTITY', 'JUSTSEARCH_AGENT_IDENTITY_HANDOFF', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_PID',
  'CODEX_THREAD_ID', 'CODEX_SESSION_ID', 'JUSTSEARCH_AGENT_SESSION_ID', 'CI'];
for (const k of IDENTITY_KEYS) delete process.env[k];
const TABLE = path.join(STATE, 'process-table.json');
process.env.JUSTSEARCH_PROCESS_TABLE_FIXTURE = TABLE;

const require = createRequire(import.meta.url);
const { __test } = require(path.join(__dirname, 'dev-runner.cjs'));
const { acquireAdmission, assertMayMutateRun, mainRepoRoot } = __test;
let agentIdentity = null;
try { agentIdentity = require(path.join(__dirname, 'lib', 'agent-identity.cjs')); } catch { /* the cases that need it fail */ }
const DEV_RUNNER = path.join(__dirname, 'dev-runner.cjs');

// The checkout the stack was launched from: a worktree of the main checkout, as cmdStart's
// repoRoot is when an agent starts the stack from its worktree. Never created; only a path base.
const LAUNCH_ROOT = path.join(mainRepoRoot, '.claude', 'worktrees', `wt-record-launch-${process.pid}`);
assert.notEqual(path.resolve(LAUNCH_ROOT), path.resolve(mainRepoRoot));

const activePath = path.join(STATE, 'active.json');
const ownersDir = path.join(STATE, 'owners');
const RUN_ID = 'run-WT';
const runDir = path.join(STATE, 'runs', RUN_ID);
const runFile = path.join(runDir, 'run.json');
const stopReportFile = path.join(runDir, 'stop-report.json');

// The holder: another agent session's harness process (pid not a multiple of 4, so no real
// Windows process can be it; its liveness comes only from the fixture table).
const HOLDER_OWNER = { harness: 'claude', pid: 900_121, creationTime: '134000000000900121', key: 'claude-900121-134000000000900121' };
const HOLDER = { harness: 'claude', owner: HOLDER_OWNER, sessionId: 'sess-WT-OWNER', source: 'process-ancestry' };
const INTRUDER = Object.freeze({
  harness: 'codex',
  owner: { harness: 'codex', pid: 900_125, creationTime: '134000000000900125', key: 'codex-900125-134000000000900125' },
  sessionId: 'thread-WT-INTRUDER',
  source: 'process-ancestry',
});

const sleepers = [];
function spawnAliveProc() {
  // The holder's "supervisor": a harmless child a proceed may kill (never this test process).
  const p = spawn(process.execPath, ['-e', 'setTimeout(()=>{}, 600000)'], { stdio: 'ignore' });
  sleepers.push(p);
  return p.pid;
}
function alive(pid) { try { process.kill(pid, 0); return true; } catch { return false; } }

function writeTable({ holderAlive }) {
  const rows = [{ ProcessId: 4, ParentProcessId: 0, Name: 'System', CommandLine: '', CreationFileTimeUtc: '133000000000000000' }];
  if (holderAlive) rows.push({ ProcessId: HOLDER_OWNER.pid, ParentProcessId: 4, Name: 'claude.exe', CommandLine: 'claude.exe', CreationFileTimeUtc: HOLDER_OWNER.creationTime });
  fs.writeFileSync(TABLE, JSON.stringify({ selfPid: process.pid, rows }));
}

function ago(ms) { return new Date(Date.now() - ms).toISOString(); }

/**
 * The record a stack started from LAUNCH_ROOT leaves behind (base cmdStart format).
 * @param {object} p
 * @param {'alive'|'ended'} [p.owner]  the holder's harness process in the table
 * @param {number|null} [p.touchAgoMs] age of the holder's last dev-stack touch
 * @param {boolean} [p.runRecord]      false = no run record on disk at all
 */
function craftWorktreeStack({ runnerPid, owner = 'alive', touchAgoMs = 300, leaseMsAhead = 30_000, runRecord = true, holder = HOLDER }) {
  writeTable({ holderAlive: owner === 'alive' });
  fs.rmSync(runDir, { recursive: true, force: true });
  if (runRecord) {
    fs.mkdirSync(runDir, { recursive: true });
    fs.writeFileSync(runFile, JSON.stringify({ schemaVersion: 1, runId: RUN_ID, pids: { runnerPid, backend: 999_999, frontend: 999_998 } }));
  }
  fs.writeFileSync(activePath, JSON.stringify({
    kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: RUN_ID,
    runPath: path.relative(LAUNCH_ROOT, runFile).split(path.sep).join('/'),
    launcherFamily: 'dev-runner', mode: 'shared',
    holder: { source: holder.harness, agentSessionId: holder.sessionId, owner: holder.owner },
    takeoverPolicy: 'warn', ownershipEpoch: 4,
    lease: { durationSec: 1, renewedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + leaseMsAhead).toISOString(), sequence: 2 },
  }));
  fs.rmSync(ownersDir, { recursive: true, force: true });
  fs.mkdirSync(ownersDir, { recursive: true });
  if (touchAgoMs !== null) {
    fs.writeFileSync(path.join(ownersDir, `${holder.owner.key}.json`), JSON.stringify({
      schema: 'owner-touch.v1', key: holder.owner.key, harness: holder.owner.harness,
      pid: holder.owner.pid, creationTime: holder.owner.creationTime, lastDevStackTouchAt: ago(touchAgoMs),
    }));
  }
}

/** A dev-runner command as the MCP server spawns it for `identity` (direct child, hand-off env). */
function runCli(args, identity) {
  const env = { ...process.env };
  for (const k of IDENTITY_KEYS) delete env[k];
  if (identity) Object.assign(env, agentIdentity.buildHandoffEnv(identity, process.pid));
  const res = spawnSync(process.execPath, [DEV_RUNNER, ...args, '--json'], { env, encoding: 'utf8', timeout: 60_000 });
  const line = String(res.stdout || '').trim().split(/\r?\n/).filter(Boolean).pop() || '{}';
  let json;
  try { json = JSON.parse(line); } catch { json = { unparsed: res.stdout, stderr: res.stderr }; }
  return { status: res.status, json };
}

async function refusalOf(fn) {
  try { await fn(); return null; } catch (err) { return err; }
}

function stopDisposition() {
  try { return JSON.parse(fs.readFileSync(stopReportFile, 'utf8')).disposition ?? null; } catch { return null; }
}

const tests = [
  ['the stored run path really is worktree-relative and misses under the main root (the layout under test)', async () => {
    craftWorktreeStack({ runnerPid: spawnAliveProc() });
    const stored = JSON.parse(fs.readFileSync(activePath, 'utf8')).runPath;
    assert.ok(!fs.existsSync(path.join(mainRepoRoot, stored)), `joined onto the main root it must not exist: ${stored}`);
    assert.ok(fs.existsSync(runFile));
  }],

  ['another owner\'s stop (MCP hand-off) over a worktree-started live stack is OWNER_CONFLICT; nothing is stopped', async () => {
    const runnerPid = spawnAliveProc();
    craftWorktreeStack({ runnerPid });
    const before = fs.readFileSync(activePath, 'utf8');
    const r = runCli(['stop'], INTRUDER);
    assert.equal(r.json?.error?.code, 'OWNER_CONFLICT', JSON.stringify(r.json));
    assert.equal(r.status, 1);
    assert.equal(fs.readFileSync(activePath, 'utf8'), before, 'the lease record is untouched');
    assert.ok(alive(runnerPid), 'the holder\'s supervisor was not stopped');
    assert.equal(stopDisposition(), null, 'no stop report was written');
  }],

  ['another owner\'s clean over a worktree-started live stack is OWNER_CONFLICT', async () => {
    const runnerPid = spawnAliveProc();
    craftWorktreeStack({ runnerPid });
    const r = runCli(['cleanup', '--clean', 'soft'], INTRUDER);
    assert.equal(r.json?.error?.code, 'OWNER_CONFLICT', JSON.stringify(r.json));
    assert.ok(fs.existsSync(activePath), 'the lease record survives');
    assert.ok(alive(runnerPid), 'the holder\'s supervisor was not stopped');
  }],

  ['another owner\'s start over a worktree-started live stack is refused at admission (OWNER_CONFLICT, not RECLAIM_DEAD)', async () => {
    const runnerPid = spawnAliveProc();
    craftWorktreeStack({ runnerPid });
    const r = await acquireAdmission({ takeover: 'deny', sessionId: INTRUDER.sessionId, callerIdentity: INTRUDER });
    assert.equal(r.action, 'conflict', JSON.stringify(r));
    assert.equal(r.reason, 'fresh_owner');
    assert.equal(r.verdict, 'CONTENTION');
    assert.ok(fs.existsSync(activePath) && alive(runnerPid), 'the stack is untouched');
  }],

  ['an unidentified caller\'s stop over a worktree-started live stack is OWNER_CONFLICT', async () => {
    const runnerPid = spawnAliveProc();
    craftWorktreeStack({ runnerPid });
    const r = runCli(['stop'], null);
    assert.equal(r.json?.error?.code, 'OWNER_CONFLICT', JSON.stringify(r.json));
    assert.ok(alive(runnerPid), 'the holder\'s supervisor was not stopped');
  }],

  ['an unreadable run record behind an unexpired lease is not a dead supervisor (start, stop gate)', async () => {
    craftWorktreeStack({ runnerPid: null, runRecord: false });
    const start = await acquireAdmission({ takeover: 'deny', sessionId: INTRUDER.sessionId, callerIdentity: INTRUDER });
    assert.notEqual(start.verdict, 'RECLAIM_DEAD', JSON.stringify(start));
    assert.equal(start.action, 'conflict', JSON.stringify(start));
    assert.ok(fs.existsSync(activePath), 'the lease record survives');
    const stop = await refusalOf(() => assertMayMutateRun({ callerIdentity: INTRUDER, sessionId: INTRUDER.sessionId }, 'stop'));
    assert.equal(stop?.code, 'OWNER_CONFLICT');
  }],

  ['an unreadable run record behind an EXPIRED lease is still reclaimable (no evidence of life left)', async () => {
    craftWorktreeStack({ runnerPid: null, runRecord: false, leaseMsAhead: -60_000 });
    const r = await acquireAdmission({ takeover: 'deny', sessionId: INTRUDER.sessionId, callerIdentity: INTRUDER });
    assert.equal(r.action, 'proceed');
    assert.equal(r.verdict, 'RECLAIM_DEAD');
  }],

  ['the owner restarting its worktree-started stack goes through owner_restart (S20), not the dead path', async () => {
    craftWorktreeStack({ runnerPid: spawnAliveProc() });
    const r = await acquireAdmission({ takeover: 'deny', sessionId: HOLDER.sessionId, callerIdentity: HOLDER });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'owner_restart', JSON.stringify(r));
    assert.equal(stopDisposition(), 'owner_restart', 'the stop report records owner_restart');
  }],

  ['stop reports over worktree-started stacks record idle_takeover / abandoned_reclaim, never stale_reclaim', async () => {
    craftWorktreeStack({ runnerPid: spawnAliveProc(), touchAgoMs: 10_000 });
    const idle = await acquireAdmission({ takeover: 'warn', sessionId: INTRUDER.sessionId, callerIdentity: INTRUDER });
    assert.equal(idle.disposition, 'idle_takeover', JSON.stringify(idle));
    assert.equal(stopDisposition(), 'idle_takeover');

    craftWorktreeStack({ runnerPid: spawnAliveProc(), owner: 'ended' });
    const abandoned = await acquireAdmission({ takeover: 'deny', sessionId: INTRUDER.sessionId, callerIdentity: INTRUDER });
    assert.equal(abandoned.disposition, 'abandoned_reclaim', JSON.stringify(abandoned));
    assert.equal(stopDisposition(), 'abandoned_reclaim');
  }],

  ['the MCP ownership projection reads a worktree-started run record and fails safe on an unreadable one', async () => {
    const { buildOwnershipProjection } = await import(pathToFileURL(path.join(__dirname, 'justsearch-dev-mcp', 'server.mjs')).href);
    // A fixture main root, so the projection reads no real repo state (op-leases, run records).
    const fakeMain = path.join(STATE, 'main');
    const launch = path.join(fakeMain, '.claude', 'worktrees', 'wt-a');
    fs.mkdirSync(fakeMain, { recursive: true });
    const project = () => {
      const active = JSON.parse(fs.readFileSync(activePath, 'utf8'));
      active.runPath = path.relative(launch, runFile).split(path.sep).join('/');
      return buildOwnershipProjection({ mainRepoRoot: fakeMain, callerRepoRoot: launch, callerIdentity: INTRUDER, takeover: 'deny', active, evidence: 'full', stateRoot: STATE });
    };
    craftWorktreeStack({ runnerPid: spawnAliveProc() });
    const live = await project();
    assert.equal(live.decision.verdict, 'CONTENTION', JSON.stringify(live.decision));
    assert.equal(live.ownership.leaseFresh, true);
    craftWorktreeStack({ runnerPid: null, runRecord: false });
    const unknown = await project();
    assert.notEqual(unknown.decision.verdict, 'RECLAIM_DEAD');
    assert.equal(unknown.decision.action, 'conflict');
  }],
];

let pass = 0, fail = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log(`  PASS  ${name}`); pass++; }
  catch (e) { console.error(`  FAIL  ${name}: ${e.message}`); fail++; }
}
for (const p of sleepers) { try { process.kill(p.pid); } catch {} }
try { fs.rmSync(STATE, { recursive: true, force: true }); } catch {}
console.log(`test-dev-runner-worktree-run-record: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
