#!/usr/bin/env node
/**
 * Agent identity (scripts/dev/lib/agent-identity.cjs): which agent session is calling.
 *
 * Every case runs the real resolver over an injected process table (no real process walk, no
 * state), so the results do not depend on the harness that runs the tests. The table is
 * Win32_Process-shaped; pids that are not multiples of 4 name no real Windows process.
 *
 * Cases: Claude Bash, Claude via its node CLI, a Claude MCP server with a stale CLAUDE_PID, Codex
 * shell, Codex MCP, Codex in Claude, Claude in Codex, no agent, CI, override sanitising, the
 * MCP-to-dev-runner hand-off, the broken-chain CLAUDE_PID fallback (and every case where it must
 * NOT apply), the backend environment, the leftover pointer file, and the real CLI end to end.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const id = require(path.join(__dirname, 'lib', 'agent-identity.cjs'));

const CT = (n) => String(134_000_000_000_000_000n + BigInt(n));
const row = (pid, ppid, name, ct, cmd = name) => ({ ProcessId: pid, ParentProcessId: ppid, Name: name, CommandLine: cmd, CreationFileTimeUtc: CT(ct) });
const SYSTEM = row(4, 0, 'System', 0);

/** Resolve over `rows` starting at `selfPid`, with only `env` as the environment. */
function resolve(rows, selfPid, env = {}, extra = {}) {
  return id.resolveAgentIdentity({
    env,
    readTable: () => ({ ok: true, table: rows, readAt: Date.now() }),
    selfPid,
    ppid: extra.ppid ?? 1,
    noCache: true,
    ...(extra.explicit ? { explicit: extra.explicit } : {}),
  });
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/* -- The harness tables ----------------------------------------------------------------------- */

test('Claude Bash: the nearest claude.exe is the owner; its own variable is the label', () => {
  const rows = [SYSTEM, row(101, 4, 'claude.exe', 10), row(201, 101, 'bash.exe', 20), row(301, 201, 'node.exe', 30)];
  const r = resolve(rows, 301, { CLAUDE_CODE_SESSION_ID: 'claude-label-1' });
  assert.equal(r.harness, 'claude');
  assert.equal(r.owner.key, `claude-101-${CT(10)}`);
  assert.equal(r.sessionId, 'claude-label-1');
  assert.equal(r.source, 'process-ancestry');
});

test('Claude via its node CLI (@anthropic-ai/claude-code) is a Claude harness', () => {
  const rows = [SYSTEM, row(101, 4, 'node.exe', 10, 'node C:/npm/node_modules/@anthropic-ai/claude-code/cli.js'), row(301, 101, 'node.exe', 30)];
  const r = resolve(rows, 301, { CLAUDE_CODE_SESSION_ID: 'claude-label-2' });
  assert.equal(r.owner.key, `claude-101-${CT(10)}`);
});

test('Claude MCP server with a stale CLAUDE_PID: the walk decides, CLAUDE_PID is ignored', () => {
  // The MCP server's parent is the live session 101; CLAUDE_PID names an older, different session.
  const rows = [SYSTEM, row(55, 4, 'claude.exe', 5), row(101, 4, 'claude.exe', 10), row(301, 101, 'node.exe', 30, 'node server.mjs')];
  const r = resolve(rows, 301, { CLAUDE_CODE_SESSION_ID: 'claude-label-3', CLAUDE_PID: '55' });
  assert.equal(r.owner.key, `claude-101-${CT(10)}`);
  assert.equal(r.source, 'process-ancestry');
});

test('Codex shell: the nearest codex.exe, labelled by CODEX_THREAD_ID', () => {
  const rows = [SYSTEM, row(105, 4, 'codex.exe', 10), row(205, 105, 'pwsh.exe', 20), row(305, 205, 'node.exe', 30)];
  const r = resolve(rows, 305, { CODEX_THREAD_ID: 'codex-thread-1', CODEX_SESSION_ID: 'codex-session-1' });
  assert.equal(r.harness, 'codex');
  assert.equal(r.owner.key, `codex-105-${CT(10)}`);
  assert.equal(r.sessionId, 'codex-thread-1');
});

test('Codex MCP server: direct child of codex; CODEX_SESSION_ID labels it when there is no thread id', () => {
  const rows = [SYSTEM, row(105, 4, 'codex.exe', 10), row(305, 105, 'node.exe', 30, 'node server.mjs')];
  const r = resolve(rows, 305, { CODEX_SESSION_ID: 'codex-session-2' });
  assert.equal(r.owner.key, `codex-105-${CT(10)}`);
  assert.equal(r.sessionId, 'codex-session-2');
});

test('Codex started from a Claude shell: Codex is nearer; the inherited Claude label is ignored', () => {
  const rows = [SYSTEM, row(101, 4, 'claude.exe', 10), row(201, 101, 'bash.exe', 20), row(209, 201, 'codex.exe', 25), row(309, 209, 'node.exe', 30)];
  const r = resolve(rows, 309, { CLAUDE_CODE_SESSION_ID: 'claude-parent', CODEX_THREAD_ID: 'codex-child' });
  assert.equal(r.harness, 'codex');
  assert.equal(r.owner.key, `codex-209-${CT(25)}`);
  assert.equal(r.sessionId, 'codex-child');
});

test('Claude started from a Codex shell: Claude is nearer; the Codex variables are ignored', () => {
  const rows = [SYSTEM, row(105, 4, 'codex.exe', 10), row(213, 105, 'claude.exe', 25), row(313, 213, 'node.exe', 30)];
  const r = resolve(rows, 313, { CLAUDE_CODE_SESSION_ID: 'claude-child', CODEX_THREAD_ID: 'codex-parent' });
  assert.equal(r.harness, 'claude');
  assert.equal(r.owner.key, `claude-213-${CT(25)}`);
  assert.equal(r.sessionId, 'claude-child');
});

test('no agent: no owner and no label, even with an inherited label and the retired export', () => {
  const rows = [SYSTEM, row(117, 4, 'explorer.exe', 10), row(317, 117, 'node.exe', 30)];
  const r = resolve(rows, 317, { CLAUDE_CODE_SESSION_ID: 'inherited', JUSTSEARCH_AGENT_SESSION_ID: 'retired-export' });
  assert.equal(r.harness, 'unknown');
  assert.equal(r.owner, null);
  assert.equal(r.sessionId, null);
});

test('CI: harness ci, no owner', () => {
  const rows = [SYSTEM, row(117, 4, 'explorer.exe', 10), row(317, 117, 'node.exe', 30)];
  const r = resolve(rows, 317, { CI: 'true' });
  assert.equal(r.harness, 'ci');
  assert.equal(r.owner, null);
});

test('no process table at all: unknown, never a guess', () => {
  const r = id.resolveAgentIdentity({ env: { CLAUDE_CODE_SESSION_ID: 'x-label' }, readTable: () => ({ ok: false, reason: 'denied' }), noCache: true });
  assert.equal(r.harness, 'unknown');
  assert.equal(r.owner, null);
});

/* -- Overrides -------------------------------------------------------------------------------- */

test('override sanitising: path characters, dots, device names and the unknown sentinel', () => {
  const evil = id.resolveAgentIdentity({ explicit: 'claude:../../etc/passwd', env: {}, noCache: true });
  assert.equal(evil.harness, 'claude');
  assert.ok(id.isSafeKey(evil.owner.key), evil.owner.key);
  assert.doesNotMatch(evil.owner.key, /[\\/]/);
  assert.doesNotMatch(evil.sessionId, /^\./);
  const dev = id.resolveAgentIdentity({ explicit: 'con', env: {}, noCache: true });
  assert.notEqual(dev.sessionId.toLowerCase(), 'con', 'a Windows device name is never a file name');
  const win = id.resolveAgentIdentity({ explicit: 'C:\\Users\\x\\..\\y', env: {}, noCache: true });
  assert.doesNotMatch(win.owner.key, /[\\/:]/);
  const sentinel = id.resolveAgentIdentity({ explicit: 'unknown', env: { CI: '' }, readTable: () => ({ ok: false, reason: 'none' }), noCache: true });
  assert.equal(sentinel.owner, null, "'unknown' is the no-identity sentinel, not an override");
  assert.equal(id.parseOverride('...'), null, 'nothing usable left -> no override');
});

test('env override JUSTSEARCH_AGENT_IDENTITY names an override owner; a harness prefix names the harness', () => {
  const r = id.resolveAgentIdentity({ env: { JUSTSEARCH_AGENT_IDENTITY: 'codex:ci-run-42' }, noCache: true });
  assert.equal(r.harness, 'codex');
  assert.equal(r.owner.key, 'override-ci-run-42');
  assert.equal(r.source, 'override-env');
});

test('an env override and the same explicit --session-id name ONE owner (a helper and its closeout sweep)', () => {
  const helper = id.resolveAgentIdentity({ env: { JUSTSEARCH_AGENT_IDENTITY: 'lane-f-semantic-run-1' }, noCache: true });
  const sweep = id.resolveAgentIdentity({ explicit: 'lane-f-semantic-run-1', env: {}, noCache: true });
  assert.equal(helper.owner.key, 'override-lane-f-semantic-run-1');
  assert.equal(sweep.owner.key, helper.owner.key);
});

test('an explicit id equal to the detected session\'s own label is that session, not a second owner', () => {
  const rows = [SYSTEM, row(101, 4, 'claude.exe', 10), row(301, 101, 'node.exe', 30)];
  const same = resolve(rows, 301, { CLAUDE_CODE_SESSION_ID: 'claude-label-4' }, { explicit: 'claude-label-4' });
  assert.equal(same.owner.key, `claude-101-${CT(10)}`);
  const other = resolve(rows, 301, { CLAUDE_CODE_SESSION_ID: 'claude-label-4' }, { explicit: 'someone-else' });
  assert.equal(other.owner.key, 'override-someone-else');
});

/* -- The MCP -> dev-runner hand-off ----------------------------------------------------------- */

test('hand-off: honoured by the direct child it was made for, ignored by anyone else', () => {
  const caller = resolve([SYSTEM, row(105, 4, 'codex.exe', 10), row(305, 105, 'node.exe', 30)], 305, { CODEX_THREAD_ID: 'codex-thread-9' });
  const env = id.buildHandoffEnv(caller, 305);
  const child = id.resolveAgentIdentity({ env, ppid: 305, readTable: () => { throw new Error('no table needed'); }, noCache: true });
  assert.equal(child.owner.key, caller.owner.key);
  assert.equal(child.sessionId, 'codex-thread-9');
  assert.match(child.source, /^handoff:/);
  const stranger = id.resolveAgentIdentity({ env, ppid: 999, readTable: () => ({ ok: false, reason: 'none' }), noCache: true });
  assert.equal(stranger.owner, null, 'a grandchild (or anyone) inheriting the hand-off does not become the caller');
  const forged = id.resolveAgentIdentity({ env: { [id.ENV_HANDOFF]: JSON.stringify({ v: 1, parentPid: 305, identity: { harness: 'claude', owner: { key: '../x' } } }) }, ppid: 305, readTable: () => ({ ok: false, reason: 'none' }), noCache: true });
  assert.equal(forged.owner, null, 'an unsafe key in a hand-off is refused');
});

test('backend env: the owner\'s label replaces an inherited one; no owner removes it; the hand-off is not passed on', () => {
  const { __test } = require(path.join(__dirname, 'dev-runner.cjs'));
  const inherited = { PATH: 'p', JUSTSEARCH_AGENT_SESSION_ID: 'someone-elses', [id.ENV_HANDOFF]: '{"v":1}' };
  const owned = __test.applyAgentSessionEnv(inherited, { sessionId: 'the-owner-label' });
  assert.equal(owned.JUSTSEARCH_AGENT_SESSION_ID, 'the-owner-label');
  assert.equal(owned[id.ENV_HANDOFF], undefined);
  assert.equal(owned.PATH, 'p');
  const anonymous = __test.applyAgentSessionEnv(inherited, { harness: 'unknown', owner: null, sessionId: null });
  assert.equal('JUSTSEARCH_AGENT_SESSION_ID' in anonymous, false, 'an inherited label never names the wrong session');
});

/* -- The broken-chain CLAUDE_PID fallback ----------------------------------------------------- */

// Git Bash `timeout` / `xargs` fork then exec: the node process's recorded parent (251) is gone.
const BROKEN = [SYSTEM, row(101, 4, 'claude.exe', 10), row(301, 251, 'node.exe', 30)];

test('fallback: broken chain + CLAUDE_PID naming a live, older claude -> claude, source claude-pid-fallback', () => {
  const r = resolve(BROKEN, 301, { CLAUDE_PID: '101', CLAUDE_CODE_SESSION_ID: 'claude-label-5' });
  assert.equal(r.harness, 'claude');
  assert.equal(r.owner.key, `claude-101-${CT(10)}`);
  assert.equal(r.sessionId, 'claude-label-5');
  assert.equal(r.source, 'claude-pid-fallback');
});

test('fallback: a recycled parent pid (parent newer than child) counts as broken', () => {
  const rows = [SYSTEM, row(101, 4, 'claude.exe', 10), row(251, 4, 'svchost.exe', 40), row(301, 251, 'node.exe', 30)];
  const r = resolve(rows, 301, { CLAUDE_PID: '101' });
  assert.equal(r.source, 'claude-pid-fallback');
});

test('fallback refused: dead CLAUDE_PID -> unknown', () => {
  const r = resolve(BROKEN, 301, { CLAUDE_PID: '149' });
  assert.equal(r.harness, 'unknown');
  assert.equal(r.owner, null);
});

test('fallback refused: CLAUDE_PID names a process that is not Claude -> unknown', () => {
  const rows = [...BROKEN, row(121, 4, 'bash.exe', 5)];
  assert.equal(resolve(rows, 301, { CLAUDE_PID: '121' }).owner, null);
});

test('fallback refused: CLAUDE_PID names a claude NEWER than an ancestor the walk found (a reused pid) -> unknown', () => {
  const rows = [SYSTEM, row(133, 4, 'claude.exe', 50), row(301, 251, 'node.exe', 30)];
  assert.equal(resolve(rows, 301, { CLAUDE_PID: '133' }).owner, null);
});

test('fallback refused: a Codex variable is present (both harnesses with a broken chain is ambiguous) -> unknown', () => {
  assert.equal(resolve(BROKEN, 301, { CLAUDE_PID: '101', CODEX_THREAD_ID: 'codex-thread-x' }).owner, null);
  assert.equal(resolve(BROKEN, 301, { CLAUDE_PID: '101', CODEX_SESSION_ID: 'codex-session-x' }).owner, null);
});

test('fallback refused: a broken Codex chain stays unknown (Codex sets no pid variable; documented limitation)', () => {
  const rows = [SYSTEM, row(105, 4, 'codex.exe', 10), row(305, 251, 'node.exe', 30)];
  const r = resolve(rows, 305, { CODEX_THREAD_ID: 'codex-thread-y' });
  assert.equal(r.harness, 'unknown');
  assert.equal(r.owner, null);
});

test('fallback refused: an intact chain with no harness never consults CLAUDE_PID', () => {
  const rows = [SYSTEM, row(101, 4, 'claude.exe', 10), row(117, 4, 'explorer.exe', 12), row(301, 117, 'node.exe', 30)];
  assert.equal(resolve(rows, 301, { CLAUDE_PID: '101' }).owner, null);
});

/* -- Retired inputs and the real CLI ---------------------------------------------------------- */

test('the leftover pointer file tmp/agent-telemetry/current-session-id is never identity', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-identity-pointer-'));
  const cwd = process.cwd();
  try {
    fs.mkdirSync(path.join(tmp, 'tmp', 'agent-telemetry'), { recursive: true });
    fs.writeFileSync(path.join(tmp, 'tmp', 'agent-telemetry', 'current-session-id'), 'stale-pointer-session\n');
    process.chdir(tmp);
    const r = resolve([SYSTEM, row(117, 4, 'explorer.exe', 10), row(317, 117, 'node.exe', 30)], 317, {});
    assert.equal(r.sessionId, null);
    assert.equal(r.owner, null);
  } finally {
    process.chdir(cwd);
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('CLI end to end: node scripts/dev/agent-identity.mjs --json over a fixture table', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-identity-cli-'));
  try {
    const fixture = path.join(tmp, 'table.json');
    fs.writeFileSync(fixture, JSON.stringify({ selfPid: 309, rows: [SYSTEM, row(101, 4, 'claude.exe', 10), row(201, 101, 'bash.exe', 20), row(209, 201, 'codex.exe', 25), row(309, 209, 'node.exe', 30)] }));
    const env = { ...process.env, JUSTSEARCH_PROCESS_TABLE_FIXTURE: fixture, CLAUDE_CODE_SESSION_ID: 'claude-parent', CODEX_THREAD_ID: 'codex-cli-thread' };
    for (const k of ['JUSTSEARCH_AGENT_IDENTITY', 'JUSTSEARCH_AGENT_IDENTITY_HANDOFF', 'CODEX_SESSION_ID', 'CLAUDE_PID', 'CI']) delete env[k];
    const res = spawnSync(process.execPath, [path.join(__dirname, 'agent-identity.mjs'), '--json'], { env, encoding: 'utf8', timeout: 30_000 });
    assert.equal(res.status, 0, res.stderr);
    const out = JSON.parse(res.stdout.trim());
    assert.equal(out.harness, 'codex');
    assert.equal(out.owner.key, `codex-209-${CT(25)}`);
    assert.equal(out.sessionId, 'codex-cli-thread');
    assert.equal(typeof out.elapsedMs, 'number');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

let pass = 0;
let fail = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log(`  PASS  ${name}`); pass += 1; } catch (e) { console.error(`  FAIL  ${name}: ${e.message}`); fail += 1; }
}
console.log(`test-agent-identity: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
