#!/usr/bin/env node
//
// The owner's own stack, and the paths a stop names, driven through throwaway CHECKOUTS.
//
// Two follow-ups of the process-based agent identity, tested where an agent meets them:
//   - an owner restarting its own stack while an operation is in flight in it is refused (that is
//     unchanged), and the refusal says the stack is the caller's own and names the operation,
//     instead of "owned by <you>; ask the user". quick_health and acquire_when_free say the same.
//     Through `dev-runner start --json` and through the dev MCP server's `start` tool over stdio;
//   - a stop names its stop report (and the report its engine-log copy) relative to the MAIN
//     checkout, the base active.runPath uses, from a worktree as from the main checkout, and
//     absolute when the state root is on another drive. Through `dev-runner stop --json` and the
//     dev MCP server's `stop` tool.
//
// Hermetic by construction. The fixture copies this checkout's dev-runner, its lib/ and the dev
// MCP server into a temp directory: a "main checkout" (no .git) and a "worktree" under it (a .git
// file naming the main checkout's git dir, which is all the root resolution reads). Every state
// root is inside the fixture (or, for the cross-drive case, a temp directory under this
// checkout's own tmp/). Identity is an override (JUSTSEARCH_AGENT_IDENTITY) and every process-
// table read goes to a fixture table. The "supervisor" is a sleeper child this test spawns. The
// fixture has no gradlew and no dist, so even a start that wrongly got past admission could not
// launch anything; every start here is expected to be refused at admission.
//
// Run: node scripts/dev/test-dev-runner-own-stack-checkout.mjs

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');

/**
 * A case that cannot run here (no drive letters off Windows, or none free to map). It is reported
 * as SKIPPED and counted apart: never a pass, never a failure.
 */
class Skip extends Error {}
// Test seam: JUSTSEARCH_TEST_SIMULATE_PLATFORM stands in for process.platform in the drive-letter
// check only, so the non-Windows skip can be exercised on Windows.
const PLATFORM = process.env.JUSTSEARCH_TEST_SIMULATE_PLATFORM || process.platform;

/* -- fixture checkouts ------------------------------------------------------------------------ */

// Canonical (long, real) form: os.tmpdir() can be an 8.3 short path on Windows (CI runners), and
// the dev MCP server's no-symlink check compares a file's real path against its repo root.
const ROOT = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'devrunner-own-stack-')));
const MAIN = path.join(ROOT, 'main');
const WT = path.join(MAIN, '.claude', 'worktrees', 'wt-own');
const STATE = path.join(MAIN, 'tmp', 'dev-runner');
const TABLE = path.join(ROOT, 'process-table.json');
const DATA = path.join(ROOT, 'data');

function copyCheckout(dest) {
  const items = [
    'scripts/dev/dev-runner.cjs',
    'scripts/dev/justsearch-dev-mcp.mjs',
    'scripts/dev/justsearch-dev-mcp',
    'scripts/dev/lib',
    'governance/supervision-contract.v1.json',
  ];
  for (const rel of items) {
    const src = path.join(REPO_ROOT, ...rel.split('/'));
    const dst = path.join(dest, ...rel.split('/'));
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.cpSync(src, dst, { recursive: true });
  }
}
copyCheckout(MAIN);
copyCheckout(WT);
// A worktree's .git is a file naming <main>/.git/worktrees/<name>; the root resolution reads only that.
fs.writeFileSync(path.join(WT, '.git'), `gitdir: ${path.join(MAIN, '.git', 'worktrees', 'wt-own').split(path.sep).join('/')}\n`);
fs.mkdirSync(STATE, { recursive: true });
fs.mkdirSync(path.join(DATA, 'logs'), { recursive: true });
fs.writeFileSync(path.join(DATA, 'logs', 'engine.log'), 'engine log line\n');
fs.writeFileSync(TABLE, JSON.stringify({
  selfPid: process.pid,
  rows: [{ ProcessId: 4, ParentProcessId: 0, Name: 'System', CommandLine: '', CreationFileTimeUtc: '133000000000000000' }],
}));

const OWNER_LABEL = 'own-stack-owner';
const OWNER_BLOCK = { harness: 'unknown', pid: null, creationTime: null, key: `override-${OWNER_LABEL}` };
const IDENTITY_KEYS = ['JUSTSEARCH_AGENT_IDENTITY', 'JUSTSEARCH_AGENT_IDENTITY_HANDOFF', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_PID',
  'CODEX_THREAD_ID', 'CODEX_SESSION_ID', 'JUSTSEARCH_AGENT_SESSION_ID', 'CI', 'JUSTSEARCH_DEV_MCP_REPO_ROOT', 'JUSTSEARCH_REPO_ROOT'];

function childEnv(stateRoot = STATE, label = OWNER_LABEL) {
  const env = { ...process.env };
  for (const k of IDENTITY_KEYS) delete env[k];
  return {
    ...env,
    JUSTSEARCH_AGENT_IDENTITY: label,
    JUSTSEARCH_PROCESS_TABLE_FIXTURE: TABLE,
    JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
    JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0',
  };
}

const sleepers = [];
function spawnSleeper() {
  const p = spawn(process.execPath, ['-e', 'setTimeout(()=>{}, 600000)'], { stdio: 'ignore' });
  sleepers.push(p);
  return p.pid;
}
function alive(pid) { try { process.kill(pid, 0); return true; } catch { return false; } }
const posix = (p) => String(p).split(path.sep).join('/');

/**
 * The owner's live stack under `stateRoot`: a run record, an active.json held by OWNER_BLOCK with
 * a fresh lease, and the given operation leases (null = no registry).
 */
function craftOwnStack({ stateRoot = STATE, runId = 'run-OWN', ops = null } = {}) {
  const runnerPid = spawnSleeper();
  const runDir = path.join(stateRoot, 'runs', runId);
  fs.rmSync(runDir, { recursive: true, force: true });
  fs.mkdirSync(runDir, { recursive: true });
  const runFile = path.join(runDir, 'run.json');
  fs.writeFileSync(runFile, JSON.stringify({ schemaVersion: 1, runId, dataDir: posix(DATA), pids: { runnerPid } }));
  const rel = path.relative(MAIN, runFile);
  fs.writeFileSync(path.join(stateRoot, 'active.json'), JSON.stringify({
    kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
    runPath: posix(rel && !path.isAbsolute(rel) ? rel : runFile),
    launcherFamily: 'dev-runner', mode: 'shared',
    holder: { source: 'unknown', agentSessionId: OWNER_LABEL, owner: OWNER_BLOCK },
    takeoverPolicy: 'warn', ownershipEpoch: 2,
    lease: { durationSec: 60, renewedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 60_000).toISOString(), sequence: 1 },
  }));
  const leases = path.join(stateRoot, 'op-leases.json');
  if (ops) {
    // The registry shape the backend writes (OperationLease.java), labelled with the stack's owner.
    const now = Date.now();
    fs.writeFileSync(leases, JSON.stringify({
      schema: 'op-leases.v1',
      opLeases: ops.map((o) => ({
        startedAt: new Date(now - 5_000).toISOString(),
        expectedDurationSec: 600,
        expiresAt: new Date(now + 600_000).toISOString(),
        heartbeatAt: null,
        originProcess: 'head',
        holder: { source: 'unknown', agentSessionId: OWNER_LABEL },
        metadata: {},
        ...o,
      })),
    }));
  } else {
    fs.rmSync(leases, { force: true });
  }
  return { runnerPid, runDir, runFile };
}

const MUST = [{ opId: 'op-mc-1', opClass: 'indexing.migration', criticality: 'MUST_COMPLETE' }];
const UNSAFE = [{ opId: 'op-ui-1', opClass: 'corruption.recovery', criticality: 'UNSAFE_TO_INTERRUPT' }];

/** A dev-runner command run from `checkout`, as a shell or the MCP server runs it. */
function runCli(checkout, args, stateRoot = STATE, label = OWNER_LABEL) {
  const res = spawnSync(process.execPath, [path.join(checkout, 'scripts', 'dev', 'dev-runner.cjs'), ...args, '--json'], {
    cwd: checkout, env: childEnv(stateRoot, label), encoding: 'utf8', timeout: 90_000,
  });
  const line = String(res.stdout || '').trim().split(/\r?\n/).filter(Boolean).pop() || '{}';
  let json;
  try { json = JSON.parse(line); } catch { json = { unparsed: res.stdout, stderr: res.stderr }; }
  return { status: res.status, json, stderr: res.stderr };
}

/* -- a minimal MCP stdio client against a fixture checkout's dev MCP server ------------------- */

function startMcp(checkout) {
  const child = spawn(process.execPath, [path.join(checkout, 'scripts', 'dev', 'justsearch-dev-mcp.mjs')], {
    cwd: checkout, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true, env: childEnv(),
  });
  const pending = new Map();
  let nextId = 1;
  let buf = '';
  let stderrTail = '';
  child.stderr.on('data', (c) => { stderrTail = (stderrTail + c.toString('utf8')).slice(-4000); });
  child.stdout.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch { continue; }
      const waiter = pending.get(msg.id);
      if (waiter) { pending.delete(msg.id); waiter(msg); }
    }
  });
  const call = (method, params, timeoutMs = 90_000) => new Promise((resolve, reject) => {
    const id = nextId++;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out. stderr=${stderrTail}`)); }, timeoutMs);
    pending.set(id, (msg) => { clearTimeout(timer); resolve(msg); });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
  });
  return {
    async init() {
      const r = await call('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test-own-stack', version: '1' } });
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} })}\n`);
      return r;
    },
    call,
    async tool(name, args) {
      const msg = await call('tools/call', { name, arguments: args });
      if (msg.error) throw new Error(`${name} failed: ${JSON.stringify(msg.error)}`);
      return msg.result?.structuredContent ?? JSON.parse(msg.result?.content?.[0]?.text ?? '{}');
    },
    close() { try { child.kill(); } catch { /* gone */ } },
  };
}

/** One dev MCP server from the fixture main checkout, shared by the MCP cases. */
let mainMcp = null;
let worktreeStopReport = null;
async function sharedMcp() {
  if (!mainMcp) {
    mainMcp = startMcp(MAIN);
    mainMcp.initResult = await mainMcp.init();
  }
  return mainMcp;
}

/* -- shared assertions ------------------------------------------------------------------------ */

const FOREIGN_WORDING = /another (agent|owner|session)|ask the user|owned by /i;
const STARTED_CLAIM = /you started|started by you|your operation/i;

function assertOwnStackRefusal(json, { code, op, unsafe }) {
  assert.equal(json?.ok, false, JSON.stringify(json));
  assert.equal(json.error?.code, code, JSON.stringify(json));
  const m = String(json.error.message || '');
  assert.match(m, /your own/, `says the stack is the caller's own: ${m}`);
  assert.match(m, /operation is running in it/, m);
  assert.ok(m.includes(op.opClass) && m.includes(op.opId), `names the operation: ${m}`);
  assert.match(m, /wait/i, `says to wait: ${m}`);
  if (code !== 'REQUIRES_CONFIRMATION') assert.match(m, /force/, `says to force: ${m}`);
  if (unsafe) assert.match(m, /--confirm-interrupt=<opId>/, `confirm-interrupt for an unsafe op: ${m}`);
  assert.doesNotMatch(m, FOREIGN_WORDING, m);
  assert.doesNotMatch(m, STARTED_CLAIM, m);
}

function assertUntouched(before, runnerPid) {
  assert.equal(fs.readFileSync(path.join(STATE, 'active.json'), 'utf8'), before, 'the lease record is untouched');
  assert.ok(alive(runnerPid), 'the owner\'s supervisor was not stopped');
}

/* -- cases ------------------------------------------------------------------------------------ */

const tests = [
  ['S4 CLI: the owner\'s deny restart over a MUST_COMPLETE op is refused in own-stack wording', async () => {
    const { runnerPid } = craftOwnStack({ ops: MUST });
    const before = fs.readFileSync(path.join(STATE, 'active.json'), 'utf8');
    const r = runCli(MAIN, ['start', '--clean=none']);
    assertOwnStackRefusal(r.json, { code: 'OWNER_CONFLICT', op: MUST[0], unsafe: false });
    assert.equal(r.json.error.holder?.owner?.key, OWNER_BLOCK.key, 'the refusal still carries the holder');
    assert.equal(r.json.error.criticalOps?.[0]?.opId, MUST[0].opId, 'and the blocking operation');
    assertUntouched(before, runnerPid);
  }],
  ['S4 CLI: the owner\'s deny restart over an UNSAFE_TO_INTERRUPT op names --confirm-interrupt', async () => {
    const { runnerPid } = craftOwnStack({ ops: UNSAFE });
    const before = fs.readFileSync(path.join(STATE, 'active.json'), 'utf8');
    const r = runCli(MAIN, ['start', '--clean=none']);
    assertOwnStackRefusal(r.json, { code: 'OWNER_CONFLICT', op: UNSAFE[0], unsafe: true });
    assertUntouched(before, runnerPid);
  }],
  ['S4 CLI: the owner\'s warn restart over a MUST_COMPLETE op is HANDSHAKE_REQUIRED in own-stack wording', async () => {
    const { runnerPid } = craftOwnStack({ ops: MUST });
    const before = fs.readFileSync(path.join(STATE, 'active.json'), 'utf8');
    const r = runCli(MAIN, ['start', '--clean=none', '--takeover=warn']);
    assertOwnStackRefusal(r.json, { code: 'HANDSHAKE_REQUIRED', op: MUST[0], unsafe: false });
    assertUntouched(before, runnerPid);
  }],
  ['S4 CLI: the owner\'s force restart over an UNSAFE op without a confirm is REQUIRES_CONFIRMATION in own-stack wording', async () => {
    const { runnerPid } = craftOwnStack({ ops: UNSAFE });
    const before = fs.readFileSync(path.join(STATE, 'active.json'), 'utf8');
    const r = runCli(MAIN, ['start', '--clean=none', '--takeover=force']);
    assertOwnStackRefusal(r.json, { code: 'REQUIRES_CONFIRMATION', op: UNSAFE[0], unsafe: true });
    assertUntouched(before, runnerPid);
  }],
  ['P2 preserved: a DIFFERENT session facing the op is still refused and told someone else holds the stack', async () => {
    const { runnerPid } = craftOwnStack({ ops: MUST });
    const before = fs.readFileSync(path.join(STATE, 'active.json'), 'utf8');
    const r = runCli(MAIN, ['start', '--clean=none'], STATE, 'some-other-session');
    assert.equal(r.json?.error?.code, 'OWNER_CONFLICT', JSON.stringify(r.json));
    assert.match(r.json.error.message, new RegExp(`^Backend owned by .*${OWNER_LABEL}`), r.json.error.message);
    assert.match(r.json.error.message, /ask the user/);
    assert.doesNotMatch(r.json.error.message, /your own/);
    assertUntouched(before, runnerPid);
  }],
  ['S4 MCP guidance: the instructions and the start description cover the own-stack refusal', async () => {
    const mcp = await sharedMcp();
    const instructions = String(mcp.initResult?.result?.instructions || '');
    assert.match(instructions, /OWNER_CONFLICT: another agent owns the stack/, 'the foreign-owner guidance stays');
    assert.match(instructions, /says the stack is your own, an operation is running in it/, 'the own-stack guidance is there');
    const listed = await mcp.call('tools/list', {});
    const startTool = (listed.result?.tools ?? []).find((t) => t.name === 'justsearch.dev.start');
    assert.match(String(startTool?.description), /operation is running in your own stack/);
  }],
  ...[[MUST, false], [UNSAFE, true]].flatMap(([ops, unsafe]) => [
    [`S5 MCP quick_health: the owner with a ${ops[0].criticality} op in flight is told about it (verdict USE unchanged)`, async () => {
      const mcp = await sharedMcp();
      craftOwnStack({ ops });
      const qh = await mcp.tool('justsearch.dev.quick_health', { probe: false });
      assert.equal(qh.ownership?.verdict, 'USE', JSON.stringify(qh));
      assert.equal(qh.ownership.callerIsOwner, true);
      assert.equal(qh.ownership.opLeases?.[0]?.opId, ops[0].opId, 'quick_health lists the op');
      assert.match(qh.ownership.recommendedAction, /You own this stack/);
      assert.ok(qh.ownership.recommendedAction.includes(`${ops[0].opClass} (`), `quick_health names the op: ${qh.ownership.recommendedAction}`);
      assert.doesNotMatch(qh.ownership.recommendedAction, FOREIGN_WORDING);
    }],
    [`S5 MCP acquire_when_free: the owner with a ${ops[0].criticality} op in flight is told about it (acquirable, deny: unchanged)`, async () => {
      const mcp = await sharedMcp();
      craftOwnStack({ ops });
      const aw = await mcp.tool('justsearch.dev.acquire_when_free', { timeoutSec: 1, pollMs: 500 });
      assert.equal(aw.acquirable, true, 'acquirability is unchanged');
      assert.equal(aw.verdict, 'USE');
      assert.equal(aw.recommendedTakeover, 'deny');
      assert.equal(aw.ownership?.opLeases?.[0]?.opId, ops[0].opId);
      assert.ok(aw.recommendedAction.includes(`${ops[0].opClass} (`), `acquire_when_free names the op: ${aw.recommendedAction}`);
      assert.doesNotMatch(aw.recommendedAction, FOREIGN_WORDING);
    }],
    [`S4 MCP start: the owner's restart over a ${ops[0].criticality} op is refused in own-stack wording`, async () => {
      const mcp = await sharedMcp();
      const { runnerPid } = craftOwnStack({ ops });
      const before = fs.readFileSync(path.join(STATE, 'active.json'), 'utf8');
      const st = await mcp.tool('justsearch.dev.start', { clean: 'none' });
      assertOwnStackRefusal(st, { code: 'OWNER_CONFLICT', op: ops[0], unsafe });
      assertUntouched(before, runnerPid);
    }],
  ]),

  ['S6 shell: a stop from a worktree names the stop report on the run record\'s base (main-relative); the owner\'s stop with an op in flight proceeds', async () => {
    const { runDir } = craftOwnStack({ ops: MUST });
    const storedRunPath = JSON.parse(fs.readFileSync(path.join(STATE, 'active.json'), 'utf8')).runPath;
    const r = runCli(WT, ['stop']);
    assert.equal(r.json?.ok, true, JSON.stringify(r.json));
    assert.ok(!fs.existsSync(path.join(STATE, 'active.json')), 'the owner\'s stop went through (as before)');
    worktreeStopReport = path.join(runDir, 'stop-report.json');
    assert.equal(r.json.stopReportPath, 'tmp/dev-runner/runs/run-OWN/stop-report.json');
    assert.equal(path.resolve(MAIN, r.json.stopReportPath), worktreeStopReport);
    assert.equal(path.dirname(path.resolve(MAIN, r.json.stopReportPath)), path.dirname(path.resolve(MAIN, storedRunPath)), 'same base as active.runPath');
  }],
  ['S6 shell: that stop report names its engine-log copy on the same base', async () => {
    assert.ok(worktreeStopReport, 'the worktree stop above wrote a report');
    const report = JSON.parse(fs.readFileSync(worktreeStopReport, 'utf8'));
    assert.equal(report.disposition, 'normal_stop');
    assert.equal(report.engineLog?.preserved, true, JSON.stringify(report.engineLog));
    assert.equal(report.engineLog.path, 'tmp/dev-runner/runs/run-OWN/logs/engine.log');
    assert.ok(fs.existsSync(path.resolve(MAIN, report.engineLog.path)), 'the engine-log path resolves against the main checkout');
  }],
  ['S6 dev tools: the MCP stop from a worktree returns the main-relative stop-report path', async () => {
    const { runDir } = craftOwnStack();
    const mcp = startMcp(WT);
    try {
      await mcp.init();
      const out = await mcp.tool('justsearch.dev.stop', {});
      assert.equal(out.ok, true, JSON.stringify(out));
      assert.equal(out.stopReportPath, 'tmp/dev-runner/runs/run-OWN/stop-report.json');
      assert.ok(fs.existsSync(path.resolve(MAIN, out.stopReportPath)));
      assert.equal(path.resolve(MAIN, out.stopReportPath), path.join(runDir, 'stop-report.json'));
    } finally { mcp.close(); }
  }],
  ['S6 preserved: a stop from the main checkout names the same main-relative path', async () => {
    const { runDir } = craftOwnStack();
    const r = runCli(MAIN, ['stop']);
    assert.equal(r.json?.ok, true, JSON.stringify(r.json));
    assert.equal(r.json.stopReportPath, 'tmp/dev-runner/runs/run-OWN/stop-report.json');
    const report = JSON.parse(fs.readFileSync(path.join(runDir, 'stop-report.json'), 'utf8'));
    assert.equal(report.engineLog?.path, 'tmp/dev-runner/runs/run-OWN/logs/engine.log');
  }],
  ['S6 preserved: a stop with no run returns a null stop-report path', async () => {
    fs.rmSync(path.join(STATE, 'active.json'), { force: true });
    const r = runCli(WT, ['stop']);
    assert.equal(r.json?.ok, true, JSON.stringify(r.json));
    assert.equal(r.json.runId, null);
    assert.equal(r.json.stopReportPath, null);
  }],
  ['S6: a state root on another drive gives absolute, readable stop-report and engine-log paths', async () => {
    // The other drive is a `subst` letter mapped onto a folder inside this test's own temp root,
    // so nothing is written outside ROOT (never into a checkout's tmp/). Windows only.
    if (PLATFORM !== 'win32') {
      throw new Skip(`drive letters are Windows-only (platform ${PLATFORM})`);
    }
    const target = path.join(ROOT, 'xdrive');
    fs.mkdirSync(target, { recursive: true });
    const used = new Set(['A', 'B', path.parse(path.resolve(ROOT)).root[0].toUpperCase()]);
    let letter = null;
    for (const c of 'ZYXWVUTSRQPONMLKJIHGFED') {
      if (used.has(c) || fs.existsSync(`${c}:\\`)) continue;
      const res = spawnSync('subst', [`${c}:`, target], { encoding: 'utf8', windowsHide: true });
      if (res.status === 0 && fs.existsSync(`${c}:\\`)) { letter = c; break; }
    }
    if (!letter) throw new Skip('no free drive letter could be mapped with subst');
    const xstate = `${letter}:\\devrunner-state`;
    try {
      fs.mkdirSync(xstate, { recursive: true });
      assert.notEqual(path.parse(xstate).root.toLowerCase(), path.parse(path.resolve(MAIN)).root.toLowerCase(), 'a different drive');
      const { runDir } = craftOwnStack({ stateRoot: xstate, runId: 'run-X' });
      const r = runCli(WT, ['stop'], xstate);
      assert.equal(r.json?.ok, true, JSON.stringify(r.json));
      const expected = path.join(runDir, 'stop-report.json');
      assert.ok(path.isAbsolute(r.json.stopReportPath), r.json.stopReportPath);
      assert.equal(path.resolve(MAIN, r.json.stopReportPath), expected);
      assert.ok(fs.existsSync(path.resolve(MAIN, r.json.stopReportPath)));
      const report = JSON.parse(fs.readFileSync(expected, 'utf8'));
      assert.ok(path.isAbsolute(report.engineLog?.path), JSON.stringify(report.engineLog));
      assert.ok(fs.existsSync(path.resolve(MAIN, report.engineLog.path)));
    } finally {
      fs.rmSync(xstate, { recursive: true, force: true });
      spawnSync('subst', [`${letter}:`, '/d'], { windowsHide: true });
    }
  }],
];

let pass = 0, fail = 0, skipped = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log(`  PASS  ${name}`); pass++; }
  catch (e) {
    if (e instanceof Skip) { console.log(`  SKIPPED (not a pass)  ${name}: ${e.message}`); skipped++; }
    else { console.error(`  FAIL  ${name}: ${e.message}`); fail++; }
  }
}
if (mainMcp) { mainMcp.close(); await new Promise((r) => setTimeout(r, 500)); }
for (const p of sleepers) { try { process.kill(p.pid); } catch { /* gone */ } }
try { fs.rmSync(ROOT, { recursive: true, force: true }); } catch { /* best effort */ }
console.log(`test-dev-runner-own-stack-checkout: ${pass} passed, ${fail} failed, ${skipped} skipped`);
process.exit(fail === 0 ? 0 : 1);
