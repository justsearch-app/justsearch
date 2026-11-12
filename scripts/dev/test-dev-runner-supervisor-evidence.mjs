import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { check, finish, root, seed, caller } from './owner-liveness-check-support.mjs';

const require = createRequire(import.meta.url);
const identity = require('./lib/agent-identity.cjs');
const originalReadTable = identity.readTableSync;
const originalReadTableAsync = identity.readTableAsync;
const state = root();
let tableReads = 0;
// Install before importing the server so the supervisor's default reader is also covered.
identity.readTableSync = () => {
  tableReads++;
  return { ok: true, readAt: Date.now(), table: [
    { ProcessId: process.pid, CreationFileTimeUtc: '134320479841300351' },
  ] };
};
identity.readTableAsync = async () => identity.readTableSync();
// The server's process-level error handler logs errors; an aborted test must still fail.
process.exitCode = 1;
try {
  const { buildOwnershipProjection } = await import('./justsearch-dev-mcp/server.mjs');
  const presence = require('./lib/owner-presence.cjs');
  const project = (active, run, evidence) => buildOwnershipProjection({ mainRepoRoot: state, callerRepoRoot: state,
      callerIdentity: caller, active, runJson: run, evidence, stateRoot: state });
  for (const recorded of [undefined, { pid: process.pid, creationFileTimeUtc: '134320479841300350' }]) {
    await check(`supervisor ${recorded ? 'Windows birth' : 'without birth'} uses presence without a table`, async () => {
      tableReads = 0;
      const { active, run } = seed(state, { runner: process.pid,
        identities: recorded ? { runner: recorded } : {} });
      for (const evidence of ['cheap', 'full']) {
        const result = await project(active, run, evidence);
        assert.equal(tableReads, 0, `${evidence} evidence must not read a process table`);
        assert.notEqual(result.decision.verdict, 'RECLAIM_DEAD', `${evidence} evidence falls back to pid presence`);
      }
    });
  }
  if (process.platform === 'linux') {
    const actual = require('./lib/process-identity.cjs').readLinuxProcess(process.pid).processIdentity;
    for (const change of [{}, { startTicks: String(BigInt(actual.startTicks) + 1n) },
      { bootId: actual.bootId === '444fd9cf-f547-4117-a537-fc8be3c44466'
        ? '333fd9cf-f547-4117-a537-fc8be3c44466' : '444fd9cf-f547-4117-a537-fc8be3c44466' }]) {
      const reused = Object.keys(change).length > 0;
      await check(`supervisor Linux ${reused ? `reused ${Object.keys(change)[0]}` : 'matching birth'}`, async () => {
        tableReads = 0;
        const { active, run } = seed(state, { runner: process.pid,
          identities: { runner: { pid: process.pid, processIdentity: { ...actual, ...change } } } });
        for (const evidence of ['cheap', 'full']) {
          const result = await project(active, run, evidence);
          assert.equal(result.decision.verdict === 'RECLAIM_DEAD', reused, `${evidence} checks Linux birth`);
          assert.equal(tableReads, 0, `${evidence} Linux evidence must not read a process table`);
        }
        assert.equal(presence.supervisorAliveFrom({ runJson: run, leaseExpired: true,
          pidAlive: () => true, readProcess: () => null }), true, 'unreadable /proc falls back to presence');
      });
    }
  }
  await check('supervisor missing or invalid pid uses lease; absent pid is dead', () => {
    for (const runner of [undefined, null, 0, -1, 'invalid']) {
      for (const leaseExpired of [false, true]) {
        assert.equal(presence.supervisorAliveFrom({ runJson: { pids: { runnerPid: runner } }, leaseExpired }), !leaseExpired);
      }
    }
    for (const leaseExpired of [false, true]) {
      assert.equal(presence.supervisorAliveFrom({ runJson: null, leaseExpired }), !leaseExpired);
      assert.equal(presence.supervisorAliveFrom({ runJson: { pids: { runnerPid: process.pid } },
        leaseExpired, pidAlive: () => false }), false);
    }
  });
} finally {
  identity.readTableSync = originalReadTable;
  identity.readTableAsync = originalReadTableAsync;
  fs.rmSync(state, { recursive: true, force: true });
}
finish();
