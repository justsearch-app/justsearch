import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { Worker } from 'node:worker_threads';
const require = createRequire(import.meta.url);
const job = require('./lib/job-run.cjs');
const identity = require('./lib/process-identity.cjs');
const linux = process.platform === 'linux';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const gone = id => {
  const seen = identity.readLinuxProcess(id.pid);
  return seen?.present === false || (seen?.processIdentity &&
    (seen.processIdentity.bootId !== id.bootId || seen.processIdentity.startTicks !== id.startTicks));
};
async function waitFor(predicate, timeout = 2000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = predicate(); if (value) return value; await pause(5); }
  assert.fail('process/fixture deadline exceeded');
}

test('Linux bound child dies with its owner; unrelated child survives; wrong parent executes nothing', { skip: !linux }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'linux-node-lifetime-'));
  let owner, decoy, owned, ownerBirth, decoyBirth;
  try {
    const program = `const job=require(${JSON.stringify(path.join(import.meta.dirname, 'lib/job-run.cjs'))});
const child=job.startInJob({command:process.execPath,args:['-e','process.on("SIGTERM",()=>{});console.log("ready");setInterval(()=>{},1000)'],
stdio:['ignore','pipe','pipe'],recordDir:${JSON.stringify(dir)}});
child.child.stdout.once('data',()=>console.log(JSON.stringify(child.host)));setInterval(()=>{},1000);`;
    owner = job.spawnBoundChild(process.execPath, ['-e', program], { stdio: ['ignore', 'pipe', 'pipe'] });
    ownerBirth = identity.readLinuxProcess(owner.pid).processIdentity;
    let output = ''; owner.stdout.on('data', data => { output += data; });
    owned = await waitFor(() => { try { return JSON.parse(output.trim()); } catch { return null; } }, 5000);
    assert.ok(owned.processIdentity);
    decoy = job.spawnBoundChild(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
    decoyBirth = identity.readLinuxProcess(decoy.pid).processIdentity;
    const killedAt = performance.now();
    assert.equal(identity.signalLinuxProcess(ownerBirth).ok, true);
    await once(owner, 'exit');
    await waitFor(() => gone(owned.processIdentity));
    assert.ok(performance.now() - killedAt < 2000);
    assert.equal(gone(decoyBirth), false, 'the unrelated process is never a cleanup target');
    const marker = path.join(dir, 'must-not-run');
    const refused = spawnSync(identity.boundExecPath(), ['exec', String(process.pid + 1), '--', process.execPath, '-e',
      `require('node:fs').writeFileSync(${JSON.stringify(marker)},'executed')`], { encoding: 'utf8' });
    assert.equal(refused.status, 125);
    assert.ok(refused.stderr.trim());
    assert.equal(fs.existsSync(marker), false);
  } finally {
    for (const id of [owned?.processIdentity, ownerBirth, decoyBirth]) if (id && !gone(id)) identity.signalLinuxProcess(id);
    if (decoy && decoy.exitCode === null && decoy.signalCode === null) await once(decoy, 'exit');
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Linux pidfd rejects stale identity, and same-process cleanup retires its record after the exit callback', { skip: !linux }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'linux-node-record-'));
  let run;
  try {
    run = job.startInJob({ command: process.execPath, args: ['-e', 'setInterval(()=>{},1000)'], stdio: 'ignore', recordDir: dir });
    assert.equal(run.contained, false, 'Linux does not claim Windows job containment');
    const id = run.host.processIdentity;
    const worker = new Worker('0', { eval: true });
    await once(worker, 'exit');
    assert.equal(gone(id), false, 'an unrelated worker thread exit must not end the main-thread child');
    const refusal = new Worker(`const {parentPort}=require('node:worker_threads');
      try { require(${JSON.stringify(path.join(import.meta.dirname, 'lib/job-run.cjs'))}).spawnBoundChild(process.execPath, ['-e','process.exit(0)']); }
      catch(error) { parentPort.postMessage(error.message); }`, { eval: true });
    const refusalExit = once(refusal, 'exit');
    const [message] = await once(refusal, 'message');
    assert.match(message, /process-lifetime main thread/);
    await refusalExit;
    assert.equal(identity.signalLinuxProcess({ ...id, startTicks: String(BigInt(id.startTicks) + 1n) }).outcome, 'mismatch');
    assert.equal(gone(id), false);
    const stopped = job.stopJobRunSync(run);
    assert.equal(stopped.ok, true, JSON.stringify(stopped));
    await once(run.child, 'exit');
    assert.equal(fs.existsSync(run.recordPath), false, 'late exit callbacks cannot recreate retired records');
    assert.deepEqual(job.settleRunRecord(run), { ok: true, outcome: 'gone' });
  } finally {
    if (run?.host && !gone(run.host.processIdentity)) job.stopJobRunSync(run);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
