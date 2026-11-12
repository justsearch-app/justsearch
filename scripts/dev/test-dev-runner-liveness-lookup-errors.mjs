import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { check, finish, root, seed, mcp, pid, caller, emptyOps } from './owner-liveness-check-support.mjs';
// No process-wide stubs escape this dedicated child.
if (!process.argv.includes('--worker')) {
  const r = spawnSync(process.execPath, [import.meta.filename, '--worker'], { encoding: 'utf8', timeout: 180000 });
  process.stdout.write(r.stdout || ''); process.stderr.write(r.stderr || '');
  if (r.error) console.error(r.error.message);
  process.exitCode = r.status === 0 ? 0 : 1;
} else {
  const state = root(); process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = state;
  const require = createRequire(import.meta.url), presence = require('./lib/owner-presence.cjs');
  const records = require('./lib/process-record.cjs'), jobs = require('./lib/job-run.cjs');
  const locks = require('./lib/worktree-register.cjs'), cli = require('./dev-runner.cjs').__test;
  const { buildOwnershipProjection } = await import('./justsearch-dev-mcp/server.mjs');
  const project = (active, run) => buildOwnershipProjection({ mainRepoRoot: state, callerRepoRoot: state,
    callerIdentity: caller, active, runJson: run, evidence: 'full', stateRoot: state });
  async function lookup(code, fn) {
    const original = process.kill;
    process.kill = function(p, signal) {
      if (p === pid && signal === 0) { if (code) throw Object.assign(new Error('injected lookup'), { code }); return true; }
      return original.call(process, p, signal);
    };
    try { await fn(); } finally { process.kill = original; }
  }
  try {
    if (process.platform === 'linux') {
      const actual = require('./lib/process-identity.cjs').readLinuxProcess(process.pid).processIdentity;
      for (const change of [{ startTicks: String(BigInt(actual.startTicks) + 1n) },
        { bootId: '444fd9cf-f547-4117-a537-fc8be3c44466' }]) {
        await check(`S7 reused runner ${Object.keys(change)[0]} MCP and CLI`, async () => {
          const { active, run } = seed(state, { expired: true, runner: process.pid,
            identities: { runner: { pid: process.pid, processIdentity: { ...actual, ...change } } } });
          assert.equal(presence.supervisorAliveFrom({ runJson: run, leaseExpired: true, pidAlive: () => true }), false, 'reused runner must count as dead');
          const a = await project(active, run);
          const b = await cli.acquireAdmission({ callerIdentity: caller, takeover: 'warn', stop: async () => ({ outcome: 'STOPPED' }) });
          assert.equal(a.decision.verdict, 'RECLAIM_DEAD', `MCP: ${a.decision.verdict}`);
          assert.equal(b.verdict, 'RECLAIM_DEAD', `CLI: ${b.verdict}`);
          assert.equal(b.action, 'proceed');
        });
      }
    }
    for (const code of ['', 'EPERM', 'EIO']) {
      for (const runner of [pid, null, 0, -1]) await check(`S8 unconfirmed runner=${runner} ${code || 'present'}`, async () => {
        const { active, run } = seed(state, { runner });
        await lookup(code, async () => {
          const result = await project(active, run);
          assert.notEqual(result.decision.verdict, 'RECLAIM_DEAD');
          assert.equal(result.decision.action, 'conflict');
        });
      });
    }
    for (const code of ['EPERM', 'EIO']) {
      await check(`S9 ${code} CLI and MCP protect owner and critical op`, async () => {
        const { active, run } = seed(state);
        const op = { opId: 'critical-check', opClass: 'indexing.migration', criticality: 'MUST_COMPLETE', startedAt: new Date().toISOString(), originProcess: 'fixture',
          expiresAt: new Date(Date.now() + 60000).toISOString() };
        fs.writeFileSync(path.join(state, 'op-leases.json'), JSON.stringify({ opLeases: [op] }));
        await lookup(code, async () => {
          const a = await project(active, run);
          const b = await cli.acquireAdmission({ callerIdentity: caller, takeover: 'deny' });
          assert.notEqual(a.decision.verdict, 'RECLAIM_DEAD', `MCP verdict ${a.decision.verdict}, CLI ${b.verdict}`);
          assert.equal(a.decision.action, 'conflict'); assert.equal(b.action, 'conflict');
          assert.equal(a.decision.verdict, b.verdict);
        });
        fs.rmSync(path.join(state, 'op-leases.json'), { force: true });
      });
      for (const tool of ['preflight', 'quick_health']) await check(`S10 ${tool} ${code}`, async () => {
        seed(state); fs.rmSync(path.join(state, 'op-leases.json'), { force: true }); const client = await mcp(state, { code });
        try {
          const r = await client.call(tool, tool === 'quick_health' ? { probe: false } : {});
          if (tool === 'preflight') {
            assert.equal(r.checks.noStaleRun, false); assert.match(r.details.noStaleRun, /live processes/i);
          } else assert.equal(r.running, true, 'a failed pid lookup must keep running=true');
        } finally { await client.close(); }
      });
      await check(`S11 ${code} shared pid probe, lock retention and foreign run`, async () => {
        await lookup(code, async () => {
          const alive = records.pidAlive(pid);
          const lock = locks.deriveOwnerAlive({ lock: { pid, start: null } });
          const foreign = await records.probeForeignRuns({ enabled: true, ownedRunState: 'ABSENT', ports: [],
            inferencePorts: [], registerDir: state, probe: async () => null,
            readRegister: async () => [{ ok: true, recordId: 'foreign', record: { pid, ports: { api: 32101 } } }] });
          assert.ok(alive === true && lock.alive === true && foreign[0].state === 'unreachable',
            `alive=${alive}, lock=${lock.alive}, foreign=${foreign[0].state}`);
        });
      });
      await check(`S12 ${code} waits for matching root publication`, async () => {
        const recordPath = path.join(state, 'job.json'); fs.rmSync(recordPath, { force: true });
        let clock = 0, waits = 0;
        await lookup(code, async () => {
          const r = jobs.waitForRootSync({ recordPath, child: { pid } }, { timeoutMs: 50, intervalMs: 10,
            monotonic: () => clock, wait: ms => { clock += ms; if (++waits === 2) fs.writeFileSync(recordPath, JSON.stringify({ pid, root: { pid: 42 } })); } });
          assert.equal(r?.root?.pid, 42, `waits=${waits}, root=${r?.root?.pid}`);
        });
      });
      await check(`S12 ${code} mismatched host waits until deadline`, async () => {
        const recordPath = path.join(state, 'job.json'); fs.writeFileSync(recordPath, JSON.stringify({ pid: pid + 1, root: { pid: 42 } }));
        let clock = 0;
        await lookup(code, async () => {
          assert.equal(jobs.waitForRootSync({ recordPath, child: { pid } }, { timeoutMs: 50,
            monotonic: () => clock, wait: ms => { clock += ms; } }), null);
          assert.equal(clock, 50, 'unknown host must wait to deadline; wrong host cannot publish root');
        });
      });
    }
    await check('S14 ESRCH shared probe lock foreign run root wait and ownership', async () => {
      const { active, run } = seed(state, { expired: true });
      const recordPath = path.join(state, 'gone-job.json'); let waits = 0;
      await lookup('ESRCH', async () => {
        assert.equal(records.pidAlive(pid), false); assert.equal(cli.isPidAlive(pid), false);
        assert.equal(locks.deriveOwnerAlive({ lock: { pid, start: null } }).alive, false);
        const foreign = await records.probeForeignRuns({ enabled: true, ownedRunState: 'ABSENT', ports: [], inferencePorts: [],
          registerDir: state, probe: async () => null,
          readRegister: async () => [{ ok: true, recordId: 'foreign', record: { pid, ports: { api: 32101 } } }] });
        assert.equal(foreign[0].state, 'stale');
        assert.equal(jobs.waitForRootSync({ recordPath, child: { pid } }, { wait: () => { waits++; } }), null);
        assert.equal(waits, 0); assert.equal((await project(active, run)).decision.verdict, 'RECLAIM_DEAD');
        const admitted = await cli.acquireAdmission({ callerIdentity: caller, takeover: 'warn', stop: async () => ({ outcome: 'STOPPED' }) });
        assert.equal(admitted.action, 'proceed'); assert.equal(admitted.verdict, 'RECLAIM_DEAD');
      });
    });
    await check('S14 owner restart active refusal takeover critical protection', () => {
      const { active } = seed(state), facts = { active, callerIdentity: caller, selfCheck: false,
        supervisorAlive: true, leaseExpired: false, stateRoot: state, opLeases: emptyOps };
      assert.equal(presence.computeOwnerVerdict(facts).decision.action, 'conflict');
      assert.equal(presence.computeOwnerVerdict({ ...facts, takeover: 'warn' }).decision.action, 'proceed');
      assert.equal(presence.computeOwnerVerdict({ ...facts, selfCheck: true, callerIdentity: { owner: active.holder.owner } }).decision.action, 'proceed');
      const op = { opId: 'critical', opClass: 'indexing.migration', criticality: 'MUST_COMPLETE' };
      assert.equal(presence.computeOwnerVerdict({ ...facts, takeover: 'warn', opLeases: {
        byCriticality: { ...emptyOps.byCriticality, mustComplete: [op] }, entries: [op] } }).decision.action, 'conflict');
    });
  } finally { fs.rmSync(state, { recursive: true, force: true }); }
  finish();
}
