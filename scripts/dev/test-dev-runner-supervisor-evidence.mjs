import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { check, finish, root, seed, caller } from './owner-liveness-check-support.mjs';

const require = createRequire(import.meta.url);
const identity = require('./lib/agent-identity.cjs');
const originalReadTable = identity.readTableSync;
const state = root();
let tableReads = 0;
// Install before importing the server so the supervisor's default reader is also covered.
identity.readTableSync = () => {
  tableReads++;
  return { ok: true, readAt: Date.now(), table: [
    { ProcessId: process.pid, CreationFileTimeUtc: '134320479841300351' },
  ] };
};
// The server's process-level error handler logs errors; an aborted test must still fail.
process.exitCode = 1;
try {
  const { buildOwnershipProjection } = await import('./justsearch-dev-mcp/server.mjs');
  await check('supervisor table confirmation is reserved for full evidence', async () => {
    const { active, run } = seed(state, { runner: process.pid,
      identities: { runner: { pid: process.pid, creationFileTimeUtc: '134320479841300350' } } });
    const project = evidence => buildOwnershipProjection({ mainRepoRoot: state, callerRepoRoot: state,
      callerIdentity: caller, active, runJson: run, evidence, stateRoot: state });

    const cheap = await project('cheap');
    assert.equal(tableReads, 0, 'cheap evidence must not read the process table');
    assert.notEqual(cheap.decision.verdict, 'RECLAIM_DEAD', 'cheap evidence falls back to pid presence');

    const full = await project('full');
    assert.equal(tableReads, 1, 'full evidence must read the process table');
    assert.equal(full.decision.verdict, 'RECLAIM_DEAD', 'full evidence confirms a reused runner pid');
  });
} finally {
  identity.readTableSync = originalReadTable;
  fs.rmSync(state, { recursive: true, force: true });
}
finish();
