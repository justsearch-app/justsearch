import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { after, test } from 'node:test';
import { buildOwnershipProjection, checkRunMutationOwnership } from './justsearch-dev-mcp/server.mjs';

// Isolated owner records and process evidence; never touch the shared stack or signal a process.
const stateRoot = fs.mkdtempSync(path.join(os.tmpdir(), 's6-owner-presence-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = stateRoot;
const fixture = path.join(stateRoot, 'process-table.json');
process.env.JUSTSEARCH_PROCESS_TABLE_FIXTURE = fixture;
const require = createRequire(import.meta.url);
const presence = require('./lib/owner-presence.cjs');
const { recommendedTakeoverFor } = require('./lib/ownership-verdict.cjs');
const { ownerVerdictFor, createPresenceMonitor } = require('./dev-runner.cjs').__test;
after(() => fs.rmSync(stateRoot, { recursive: true, force: true }));

const callerIdentity = { owner: { key: 'override-s6-caller' } };
const processIdentity = {
  kind: 'linux-proc-v1', pid: 900105,
  bootId: '333fd9cf-f547-4117-a537-fc8be3c44466', startTicks: '9007199254740993',
};
const linux = { harness: 'claude', key: 'claude-s6-linux', pid: processIdentity.pid, creationTime: null, processIdentity };
const windows = { harness: 'claude', key: 'claude-s6-windows', pid: 900109, creationTime: '134000000000900109' };
const linuxRow = { ProcessId: linux.pid, processIdentity };
const windowsRow = { ProcessId: windows.pid, CreationFileTimeUtc: windows.creationTime };
const cases = [
  { name: 'O1 Linux exited', owner: linux, state: 'ended', rows: [] },
  { name: 'O2 Linux pid reused', owner: linux, state: 'ended', rows: [
    { ...linuxRow, processIdentity: { ...processIdentity, startTicks: '9007199254740994' } },
  ] },
  { name: 'O2 Linux previous boot', owner: linux, state: 'ended', rows: [
    { ...linuxRow, processIdentity: { ...processIdentity, bootId: '444fd9cf-f547-4117-a537-fc8be3c44466' } },
  ] },
  { name: 'O3 Linux alive', owner: linux, state: 'alive', rows: [linuxRow] },
  { name: 'O4 Linux unreadable birth', owner: linux, state: 'unknown', rows: [{ ProcessId: linux.pid }] },
  { name: 'O4 Linux unavailable table', owner: linux, state: 'unknown', unavailable: true },
  { name: 'O4 Linux malformed identity', owner: { ...linux, processIdentity: { ...processIdentity, startTicks: 123 } },
    state: 'unknown', rows: [] },
  { name: 'O5 Windows exited', owner: windows, state: 'ended', rows: [] },
  { name: 'O5 Windows pid reused', owner: windows, state: 'ended', rows: [
    { ...windowsRow, CreationFileTimeUtc: '134000000000900110' },
  ], cheapState: 'alive' },
  { name: 'O5 Windows alive', owner: windows, state: 'alive', rows: [windowsRow] },
  { name: 'O5 Windows unreadable birth', owner: windows, state: 'unknown', rows: [{ ProcessId: windows.pid }] },
  { name: 'O5 Windows unavailable table', owner: windows, state: 'unknown', unavailable: true },
];

function evidence(c, now = Date.now()) {
  return c.unavailable ? { ok: false, reason: '/proc unreadable' } : {
    ok: true, readAt: now,
    // The fixture reader refuses an empty table: retain an unrelated process even after owner exit.
    table: [{ ProcessId: 1, CreationFileTimeUtc: '133000000000000000' }, ...c.rows],
  };
}

function prepare(c) {
  const table = evidence(c);
  fs.writeFileSync(fixture, JSON.stringify(table.ok ? { rows: table.table } : table));
  presence.touchOwner(stateRoot, { harness: c.owner.harness, owner: c.owner });
  return {
    runId: 's6-running-stack', holder: { source: 'claude', owner: c.owner },
    lease: { durationSec: 1, expiresAt: new Date(Date.now() + 60_000).toISOString() },
  };
}

function assertDecision(decision, state) {
  const ended = state === 'ended';
  assert.equal(decision.verdict, ended ? 'TAKEOVER_ABANDONED' : 'CONTENTION');
  assert.equal(decision.action, 'conflict', 'plain deny never silently takes a running stack');
  assert.equal(recommendedTakeoverFor(decision), ended ? 'warn' : null,
    'only an ended owner permits self-authorized takeover');
}

for (const c of cases) {
  test(`${c.name}: owner-presence collects evidence and decides`, () => {
    const active = prepare(c);
    const table = presence.holderNeedsTable(active, callerIdentity) ? evidence(c) : null;
    const facts = { active, callerIdentity, supervisorAlive: true, leaseExpired: false, stateRoot, table };
    const result = presence.computeOwnerVerdict(facts);
    assert.equal(presence.ownerLiveness(c.owner, { table: evidence(c) }).state, c.state);
    if (c.state !== 'alive') assert.equal(result.liveness.state, c.state);
    assertDecision(result.decision, c.state);
    if (c.state === 'ended') {
      assert.equal(presence.computeOwnerVerdict({ ...facts, takeover: 'warn' }).decision.action, 'proceed');
    }
  });

  test(`${c.name}: dev-runner ownership gate`, () => {
    const active = prepare(c);
    const facts = { active, callerIdentity, selfCheck: true, supervisorAlive: true, leaseExpired: false };
    const result = ownerVerdictFor(facts);
    if (c.state !== 'alive') assert.equal(result.liveness.state, c.state);
    assertDecision(result.decision, c.state);
    if (c.state === 'ended') {
      assert.equal(ownerVerdictFor({ ...facts, takeover: 'warn' }).decision.action, 'proceed');
    }
  });

  for (const mode of ['full', 'cheap']) {
    test(`${c.name}: MCP ${mode} ownership projection`, async () => {
      const active = prepare(c);
      const { decision } = await buildOwnershipProjection({
        mainRepoRoot: stateRoot, callerRepoRoot: stateRoot, callerIdentity, active,
        // A real live supervisor, with a different owner: this must not pass via RECLAIM_DEAD.
        runJson: { pids: { runnerPid: process.pid } }, evidence: mode, stateRoot,
      });
      assertDecision(decision, mode === 'cheap' ? c.cheapState ?? c.state : c.state);
    });
  }

  test(`${c.name}: MCP mutation authorization`, async () => {
    const active = prepare(c);
    const result = await checkRunMutationOwnership({
      mainRepoRoot: stateRoot, callerRepoRoot: stateRoot, callerIdentity, active,
      runJson: { pids: { runnerPid: process.pid } }, tool: 'reload', stateRoot,
    });
    assert.equal(result.allowed, c.state === 'ended');
    if (c.state !== 'ended') assert.equal(result.refusal.error.code, 'OWNER_CONFLICT');
  });

  test(`${c.name}: dev-runner supervisor presence monitor`, async () => {
    let now = Date.now();
    const current = { holder: { owner: c.owner } };
    const tick = createPresenceMonitor({
      env: { JUSTSEARCH_DEV_REAPER_GRACE_MS: '2000' }, now: () => now,
      pidPresence: () => c.unavailable ? 'unknown' : c.rows.length ? 'present' : 'absent',
      readTable: async () => evidence(c, now),
      readTouch: () => ({ lastDevStackTouchAt: new Date(now).toISOString() }),
    });
    let result;
    // A reused pid is detected by the existing periodic full check (30 ticks).
    for (let i = 0; i < 30; i++) result = await tick(current, 0);
    assert.equal(result.action, c.state === 'ended' ? 'pause' : 'renew');
    now += 2001;
    result = await tick(current, 0);
    assert.equal(result.action, c.state === 'ended' ? 'reap' : 'renew');
    if (c.state === 'ended') assert.equal(result.disposition, 'reaped_abandoned');
  });
}

test('O3 self owner and legacy/override owners do not request process evidence', () => {
  const active = { holder: { owner: linux } };
  assert.equal(presence.holderNeedsTable(active, { owner: linux }), false);
  for (const owner of [null, { key: 'override-owner' }, { ...linux, processIdentity: null }]) {
    assert.equal(presence.holderNeedsTable({ holder: { owner } }, callerIdentity), false);
  }
});
