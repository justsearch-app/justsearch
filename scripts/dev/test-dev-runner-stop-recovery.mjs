import './lib/test-fixture-processes.cjs';
// Recovery across the real CLI and injected identity boundaries. Only fake harness children run.
// Identity-only stops, as on the integration side since 58a51a608: identities left by an earlier
// (tree-era) stop are re-checked and reported, never killed through parent links. A dev tool's run
// is started in its own job (scripts/dev/lib/job-run.cjs), so what it starts ends with its stop.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { EventEmitter } from 'node:events';
const require = createRequire(import.meta.url);
const repo = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stop-recovery-'));
fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
const dataRoot = fs.mkdtempSync(path.join(repo, 'tmp', 'stop-recovery-data-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = root;
const actuator = require('./lib/engine-actuator.cjs');
const { stopRun, acquireAdmission, holderBlockFor, resolveCallerIdentity } = require('./dev-runner.cjs').__test;
const tests = [], children = [];
const extraOwned = [];
const test = (name, fn, windowsOnly = false) => tests.push([name, fn, windowsOnly]);
const windowsNative = (name, fn) => test(name, fn, true);
const pid = 987654, birth = '134000000000000000';
function fixture(name, extra = {}) {
  const run = { runId: name, holder: holderBlockFor(resolveCallerIdentity('recovery-owner')), pids: { frontendRootPid: pid },
    identities: { frontend: { pid, creationFileTimeUtc: birth } }, ...extra };
  const dir = path.join(root, 'runs', name); fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json'); fs.writeFileSync(runPath, JSON.stringify(run));
  fs.writeFileSync(path.join(root, 'active.json'), JSON.stringify({ runId: name }));
  return { run, runPath };
}
const baseIo = target => ({ resolveTarget: async () => target,
  census: async () => ({ finish: async () => ({}) }),
  portOwner: async () => ({ closed: true }), portWaitMs: 0 });
for (const present of [false, true]) test(`a legacy unresolved-tree record resolves once its listed identity is ${present ? 'reused' : 'gone'}`, async () => {
  const target = fixture(`resolved-${present}`, { stopUnresolvedTrees: [{ role: 'frontend',
    outcome: 'partial', processes: [{ pid: pid + 1, creationFileTimeUtc: birth, owned: true }] }] });
  const result = await stopRun({}, { ...baseIo(target), lookupIdentities: async pids => new Map(pids.map(p => [p,
    { present: p === pid + 1 && present, creationFileTime: '134000000000200000' }])) });
  assert.equal(result.outcome, 'STOPPED', 'gone/reused recorded descendants must resolve the record');
  assert.equal(fs.existsSync(path.join(root, 'active.json')), false);
  assert.equal(JSON.parse(fs.readFileSync(target.runPath)).stopUnresolvedTrees, undefined, 'the tree-era record is retired');
  assert.deepEqual(JSON.parse(fs.readFileSync(target.runPath)).stopTrackedProcesses, []);
});
test('anonymous frontend failure clears when its recorded root is gone', async () => {
  const target = fixture('anonymous', { stopUnresolvedTrees: [{ role: 'frontend', outcome: 'runner-tree-incomplete' }] });
  const result = await stopRun({}, { ...baseIo(target), lookupIdentities: async pids => new Map(pids.map(p => [p, { present: false }])) });
  assert.equal(result.outcome, 'STOPPED');
});
test('descendants survive a runner rewrite through the separate stop snapshot', async () => {
  const target = fixture('separate-snapshot');
  const descendant = { pid: pid + 1, creationFileTimeUtc: birth, role: 'frontend descendant', owned: false };
  fs.writeFileSync(path.join(path.dirname(target.runPath), 'stop-tracked-processes.json'), JSON.stringify({ processes: [descendant] }));
  let alive = true;
  const io = { ...baseIo(target), lookupIdentities: async pids => new Map(pids.map(p => [p,
    { present: p === pid + 1 && alive, creationFileTime: birth }])) };
  assert.equal((await stopRun({}, io)).outcome, 'STOP_INCOMPLETE', 'a runner rewrite cannot forget a recorded surviving descendant');
  alive = false;
  assert.equal((await stopRun({}, io)).outcome, 'STOPPED', 'the separate snapshot also clears after identity recheck');
});
test('a live identity left by an earlier stop is reported, never killed, and keeps the stop incomplete', async () => {
  const target = fixture('nested-partial', { stopUnresolvedTrees: [{ role: 'frontend',
    processes: [{ pid: pid + 1, creationFileTimeUtc: birth, owned: true }] }] });
  const actions = [];
  const result = await stopRun({}, { ...baseIo(target),
    lookupIdentities: async pids => new Map(pids.map(p => [p, { present: p === pid + 1, creationFileTime: birth }])),
    terminateProcess: (...args) => { actions.push(args); return { ok: true, outcome: 'terminated' }; } });
  assert.equal(result.outcome, 'STOP_INCOMPLETE');
  assert.ok(result.unresolvedProcesses?.some(p => p.pid === pid + 1), 'the surviving identity stays reported');
  assert.deepEqual(actions.filter(a => a[0] === pid + 1), [], 'an identity found through parent links is never killed');
  assert.ok(read(target.runPath).stopTrackedProcesses.some(p => p.pid === pid + 1), 'a later stop re-checks it');
});
test('rewriting an unresolved legacy record never moves its PID reuse boundary', async () => {
  const target = fixture('legacy-bound', { pids: { backendRootPid: pid, frontendRootPid: pid + 1 }, identities: {},
    stopUnresolvedTrees: [{ role: 'frontend', outcome: 'runner-tree-incomplete' }] });
  fs.utimesSync(target.runPath, new Date('2020-01-01'), new Date('2020-01-01'));
  let frontendAlive = true;
  const io = { ...baseIo(target), resolveTarget: async () => ({ ...target, run: read(target.runPath) }),
    lookupIdentities: async pids => new Map(pids.map(p => [p, p === pid
      ? { present: true, creationFileTime: birth } : { present: frontendAlive, creationFileTime: null }])) };
  assert.equal((await stopRun({}, io)).outcome, 'STOP_INCOMPLETE');
  frontendAlive = false;
  assert.equal((await stopRun({}, io)).outcome, 'STOPPED', 'the previously proven reused PID cannot become unknown after a save');
});
test('a forgotten run never spends identities restored by a stale writer', async () => {
  const target = fixture('forgotten-stale');
  fs.writeFileSync(path.join(path.dirname(target.runPath), 'forgotten-unresolved.json'), JSON.stringify({ leftBehind: [] }));
  let actions = 0;
  const result = await stopRun({}, { ...baseIo(target),
    lookupIdentities: async () => new Map([[pid, { present: true, creationFileTime: birth }]]),
    terminateProcess: () => { actions++; return { ok: true }; } });
  assert.equal(result.outcome, 'FORGOTTEN_UNRESOLVED');
  assert.equal(actions, 0);
});
test('late runner record publication cannot restore forgotten PIDs', async () => {
  const target = fixture('forgotten-writer');
  fs.writeFileSync(target.runPath, JSON.stringify({ pids: {} }));
  fs.writeFileSync(path.join(path.dirname(target.runPath), 'forgotten-unresolved.json'), '{}');
  const source = fs.readFileSync(path.join(repo, 'scripts/dev/dev-runner.cjs'), 'utf8');
  const fn = source.match(/  const updateRunRecord = async \(\) => \{[\s\S]*?\n  \};/)?.[0];
  assert.ok(fn);
  const context = { fs, path, runDir: path.dirname(target.runPath), runPath: target.runPath,
    runJson: target.run, startingRun: target.run, narrate: () => {},
    writeJsonAtomic: async (file, data) => fs.writeFileSync(file, JSON.stringify(data)) };
  vm.runInNewContext(`${fn}\nthis.update = updateRunRecord;`, context); await context.update();
  assert.deepEqual(read(target.runPath).pids, {}, 'forget cannot be undone by a delayed birth or ready callback');
});
test('start ignores an active pointer for an explicitly forgotten run', async () => {
  const target = fixture('forgotten-start');
  fs.writeFileSync(path.join(path.dirname(target.runPath), 'forgotten-unresolved.json'), '{}');
  let stops = 0;
  const result = await acquireAdmission({ sessionId: 'forgotten-start', stop: async () => { stops++; return { outcome: 'STOP_INCOMPLETE' }; } });
  assert.equal(result.action, 'proceed', 'a retired tracking pointer cannot block the next start');
  assert.equal(stops, 0, 'start does not stop any explicitly forgotten process');
});
test('forget unknown legacy Engine reports it and sends no shutdown or termination', async () => {
  const target = fixture('legacy', { pids: { backendRootPid: pid }, identities: {} });
  let actions = 0;
  fs.writeFileSync(path.join(path.dirname(target.runPath), 'stop-report.json'), JSON.stringify({ outcome: 'STOP_INCOMPLETE' }));
  const result = await stopRun({ forgetUnresolved: true, sessionId: 'recovery-owner' }, { ...baseIo(target),
    lookupIdentities: async () => new Map([[pid, { present: true, creationFileTime: birth }]]),
    gracefulShutdown: async () => { actions++; return {}; }, terminateEngine: () => { actions++; return {}; },
    terminateProcess: () => { actions++; return {}; }, askRunner: () => { actions++; return {}; } });
  assert.equal(result.outcome, 'FORGOTTEN_UNRESOLVED');
  assert.equal(actions, 0);
  assert.equal(result.leftBehind[0].pid, pid);
  assert.match(result.leftBehind[0].reason, /no start time/);
  assert.equal(result.leftBehind[0].creationFileTimeUtc, null, 'observed birth never establishes ownership');
  assert.equal(result.leftBehind[0].observedCreationFileTimeUtc, birth);
  assert.equal(fs.existsSync(path.join(root, 'active.json')), false);
});
test('unknown Engine has an actionable recovery command and process details', async () => {
  const target = fixture('unknown-engine', { pids: { backendRootPid: pid }, identities: {} });
  const result = await stopRun({}, { ...baseIo(target),
    lookupIdentities: async () => new Map([[pid, { present: true, creationFileTime: birth, name: 'fixture-engine.exe' }]]) });
  assert.equal(result.outcome, 'STOP_INCOMPLETE');
  assert.match(result.nextStep ?? '', /--forget-unresolved/);
  assert.equal(result.unresolvedProcesses?.[0]?.pid, pid);
  assert.equal(result.unresolvedProcesses[0].name, 'fixture-engine.exe');
  assert.match(result.unresolvedProcesses[0].reason, /ownership is unproven/);
});
test('dev-all birth capture finishes in the spawn turn before stop', () => {
  // dev-all starts each child through the job starter, which reads the host's identity before it
  // returns; a stop right after the spawn must already carry that birth.
  const file = path.join(repo, 'modules/ui-web/scripts/dev-all.cjs');
  let text = fs.readFileSync(file, 'utf8'); text = text.slice(0, text.indexOf('process.on("SIGINT"'));
  const calls = [], child = Object.assign(new EventEmitter(), { pid, exitCode: null, signalCode: null });
  const jobRun = require('./lib/job-run.cjs');
  const context = { require: id => id === 'process' ? { platform: 'win32', env: {} }
    : id.includes('job-run') ? { ...jobRun,
      startInJob: options => jobRun.startInJob({ ...options, platform: 'win32', env: { JUSTSEARCH_JOB_RECORD_DIR: path.join(root, 'dev-all-runs') },
        spawnImpl: () => child, capture: () => ({ pid, creationFileTimeUtc: birth }) }),
      stopJobRunSync: (...args) => { calls.push(args); return { ok: true }; } }
      : createRequire(file)(id), __dirname: path.dirname(file), console, setTimeout };
  vm.runInNewContext(`${text}\nthis.start=startOwnedChild; this.stop=terminateChild;`, context);
  context.stop(context.start(process.execPath, ['fixture'], { stdio: 'ignore', label: 'dev-all-check' }));
  assert.equal(calls[0]?.[0]?.host?.creationFileTimeUtc, birth, 'stop immediately after spawn must carry the birth');
});
let exitedWrapper;
windowsNative('spawn capture retains the exited wrapper identity, and its identity stop ends nothing else', () => {
  const proof = path.join(root, 'exited-wrapper.json');
  const identityExe = process.platform === 'win32' ? require('./lib/test-fixture-processes.cjs').identityExecutable() : null;
  const code = `const cp=require('child_process'),fs=require('fs'); const child=cp.spawn(process.execPath,['-e','setTimeout(()=>{},120000)'],{stdio:'ignore',detached:true,windowsHide:true}); const r=cp.spawnSync(${JSON.stringify(identityExe)},[String(child.pid)],{encoding:'utf8',windowsHide:true}); fs.writeFileSync(${JSON.stringify(proof)},JSON.stringify({pid:child.pid,creationFileTimeUtc:r.stdout.trim()})); process.exit(0);`;
  const wrapper = spawn(process.execPath, ['-e', code], { stdio: 'ignore', windowsHide: true });
  const recorded = spawnSync(identityExe, [String(wrapper.pid)], { encoding: 'utf8', windowsHide: true }).stdout.trim();
  extraOwned.push({ pid: wrapper.pid, creationFileTimeUtc: recorded });
  // Block the parent's event loop: its original child handle stays open after the wrapper exits.
  const waited = spawnSync('powershell.exe', ['-NoProfile','-Command',
    `$end=[DateTime]::UtcNow.AddSeconds(15); while(-not(Test-Path -LiteralPath '${proof.replaceAll("'", "''")}')){if([DateTime]::UtcNow -gt $end){exit 9};Start-Sleep -Milliseconds 10}; Start-Sleep -Milliseconds 200`],
    { encoding: 'utf8', windowsHide: true, timeout: 20000 });
  assert.equal(waited.status, 0, 'wrapper recorded its leaf before exiting');
  const leaf = read(proof); extraOwned.push(leaf);
  const captured = actuator.captureSpawnedIdentity?.(wrapper);
  assert.ok(captured?.creationFileTimeUtc, 'an exited but pinned root still has a readable birth');
  assert.equal(captured.creationFileTimeUtc, recorded);
  const stopped = actuator.terminateWithCodeSync(wrapper.pid, captured.creationFileTimeUtc, 1, { timeoutMs: 10000 });
  assert.equal(stopped.outcome, 'gone', JSON.stringify(stopped));
  exitedWrapper = { wrapper, captured, leaf };
});
windowsNative('a detached leaf of an exited wrapper does not survive its run\'s stop', async () => {
  // A dev tool's run: started through the tools' job starter when the tree has one, so the kernel adds
  // the leaf to the run's job at its creation and the run's stop ends it, without parent links. A
  // tree without it starts and stops the run as the check above does, by the wrapper's identity alone.
  const starter = path.join(repo, 'scripts/dev/lib/job-run.cjs');
  let leaf;
  if (fs.existsSync(starter)) {
    const jobRun = require(starter);
    const identityExe = require('./lib/test-fixture-processes.cjs').identityExecutable();
    const proof = path.join(root, 'job-wrapper-leaf.json');
    const code = `const cp=require('child_process'),fs=require('fs'); const child=cp.spawn(process.execPath,['-e','setTimeout(()=>{},120000)'],{stdio:'ignore',detached:true,windowsHide:true}); const r=cp.spawnSync(${JSON.stringify(identityExe)},[String(child.pid)],{encoding:'utf8',windowsHide:true}); fs.writeFileSync(${JSON.stringify(proof)},JSON.stringify({pid:child.pid,creationFileTimeUtc:r.stdout.trim()})); process.exit(0);`;
    const records = path.join(root, 'job-runs');
    const run = jobRun.startInJob({ command: process.execPath, args: ['-e', code], stdio: 'ignore',
      env: { ...process.env, JUSTSEARCH_JOB_RECORD_DIR: records }, label: 'exited-wrapper' });
    assert.ok(run.host?.creationFileTimeUtc, 'the run\'s host identity is read in the spawn turn');
    leaf = await until(() => read(proof));
    extraOwned.push(leaf);
    const stopped = jobRun.stopJobRunSync(run);
    assert.equal(stopped.ok, true, JSON.stringify(stopped));
    const recorded = new Set((stopped.survivors ?? []).map(p => p.pid));
    assert.equal(recorded.has(leaf.pid), false, 'the stop reports no surviving member');
  } else {
    assert.ok(exitedWrapper, 'run after the spawn-capture test');
    ({ leaf } = exitedWrapper);
  }
  const after = actuator.terminateWithCodeSync(leaf.pid, leaf.creationFileTimeUtc, 0, { inspectOnly: true });
  assert.notEqual(after.outcome, 'inspected', 'the detached leaf must not survive its run\'s stop');
});
const read = file => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function until(fn, ms = 60000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const value = fn(); if (value) return value; await sleep(50); }
  assert.fail('harness deadline expired');
}
function cli(args, env) {
  const result = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), ...args, '--json'],
    { cwd: repo, env, encoding: 'utf8', timeout: 90000 });
  const json = readJsonOutput(result.stdout);
  return { status: result.status, ...json };
}
function readJsonOutput(output) { try { return JSON.parse(String(output).trim().split(/\r?\n/).at(-1)); } catch { return { output }; } }
let cliRecovery;
windowsNative('real CLI first stop resolves a frontend exiting during the stop request', async () => {
  const state = path.join(root, 'cli'); fs.mkdirSync(state);
  const plan = path.join(state, 'plan.json'); fs.writeFileSync(plan, JSON.stringify({ incarnations: [{ mode: 'honour' }] }));
  const delayExit = path.join(state, 'delay-exit.cjs');
  fs.writeFileSync(delayExit, 'const exit=process.exit.bind(process); process.exit=code=>setTimeout(()=>exit(code),800);');
  // The frontend exits by itself once the runner has taken the stop request (its quit intent is in
  // the Engine ledger), while the runner's identity-bound frontend stop is still on its way. A tree
  // stop's enumeration used to leave a fixed 400 ms margin; an identity-only stop needs this signal.
  const frontend = `const fs=require('fs'),p=require('path');let ending=false;setInterval(()=>{if(ending)return;const root=process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT,r=p.join(root,'runs');if(!fs.existsSync(r))return;for(const id of fs.readdirSync(r)){if(!fs.existsSync(p.join(r,id,'control','stop-request.json')))continue;let run;try{run=JSON.parse(fs.readFileSync(p.join(r,id,'run.json'),'utf8'))}catch{continue}let text='';try{text=fs.readFileSync(p.join(run.dataDir,'runtime','exit-ledger','engine.v1.jsonl'),'utf8')}catch{}const taken=text.split(/\\r?\\n/).some(l=>{try{const x=JSON.parse(l);return x.type==='engine-intent'&&x.writer&&x.writer.role==='dev-runner'}catch{return false}});if(taken){ending=true;fs.writeFileSync(p.join(root,'frontend-self-exit.json'),JSON.stringify({pid:process.pid}));process.exit(0)}}},20)`;
  const env = { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_SUPERVISOR_HARNESS: '1',
    JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0', JUSTSEARCH_FAKE_ENGINE_PLAN: plan, CI: '',
    JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, '--require', delayExit, path.join(repo, 'scripts/supervisor-conformance/fake-engine.mjs')]),
    JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', frontend]) };
  async function start(session, takeover = false) {
    // Old stop files are in older runs; only this frontend reproduction needs self-exit.
    const e = session === 'first' ? env : { ...env, JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(()=>{},60000)']) };
    const child = spawn(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), 'start', '--json',
      '--skip-build', '--clean', 'none', '--api-port', '0', '--ui-port', '5599', '--data-dir', path.join(dataRoot, session),
      '--session-id', session, ...(takeover ? ['--takeover', 'force'] : [])], { cwd: repo, env: e, stdio: ['ignore','pipe','pipe'] });
    const captured = spawnSync('powershell.exe', ['-NoProfile', '-Command', `(Get-Process -Id ${child.pid}).StartTime.ToFileTimeUtc().ToString()`], { encoding: 'utf8', windowsHide: true });
    const identity = { pid: child.pid, creationFileTimeUtc: captured.stdout.trim() }; children.push({ child, identity });
    let output = ''; child.stdout.on('data', b => { output += b; }); child.stderr.on('data', b => { output += b; });
    return until(() => { const active = read(path.join(state, 'active.json')); const run = active?.runId ? read(path.join(state, 'runs', active.runId, 'run.json')) : null;
      if (child.exitCode !== null) return { failed: true, output };
      return active?.phase !== 'starting' && run?.pids?.runnerPid === child.pid && run?.pids?.frontendRootPid && run?.holder?.sessionId !== 'impossible' ? { child, run, active, output } : null; });
  }
  const first = await start('first'); assert.ok(!first.failed, first.output);
  const stopped = cli(['stop', '--session-id', 'first'], env);
  assert.equal(read(path.join(state, 'frontend-self-exit.json'))?.pid, first.run.pids.frontendRootPid,
    'the frontend must actually exit by itself once the runner took the stop, before the delayed Engine exit');
  const second = cli(['stop', '--session-id', 'first'], env);
  const takeover = await start('second', true);
  const takeoverStopped = takeover.failed ? null : cli(['stop', '--session-id', 'second'], env);
  const next = await start('third');
  if (!next.failed) cli(['stop', '--session-id', 'third'], env);
  cliRecovery = { stopped, second, takeover, takeoverStopped, next };
  assert.equal(stopped.outcome, 'STOPPED', JSON.stringify(stopped));
});
windowsNative('real CLI second stop finds no active run after frontend self-exit', () => {
  assert.equal(cliRecovery?.second.note, 'no_active_run', JSON.stringify(cliRecovery?.second));
});
windowsNative('real CLI another-session takeover proceeds after frontend self-exit', () => {
  assert.ok(cliRecovery && !cliRecovery.takeover.failed, cliRecovery?.takeover.output);
  assert.equal(cliRecovery.takeoverStopped.outcome, 'STOPPED');
});
windowsNative('real CLI next start proceeds after frontend self-exit', () => {
  assert.ok(cliRecovery && !cliRecovery.next.failed, cliRecovery?.next.output);
});
test('real CLI forget prints the unknown Engine and leaves it alive', async () => {
  const state = path.join(root, 'forget-cli');
  const runId = 'legacy-live'; const dir = path.join(state, 'runs', runId); fs.mkdirSync(dir, { recursive: true });
  // The deliberately unowned fixture exits only on this private channel after every assertion.
  // Forget is portable: it must retain an unknown process without needing a native stop primitive.
  const exitFile = path.join(state, 'fixture-quit');
  const child = spawn(process.execPath, ['-e', `const fs=require('fs');setInterval(()=>{if(fs.existsSync(${JSON.stringify(exitFile)}))process.exit(0)},20)`], { stdio: 'ignore' });
  const exited = new Promise(resolve => child.once('exit', resolve));
  const capture = process.platform === 'win32'
    ? spawnSync('powershell.exe', ['-NoProfile', '-Command', `(Get-Process -Id ${child.pid}).StartTime.ToFileTimeUtc().ToString()`], { encoding: 'utf8', windowsHide: true })
    : null;
  children.push({ child, identity: capture ? { pid: child.pid, creationFileTimeUtc: capture.stdout.trim() } : null, exitFile, exited });
  const holder = holderBlockFor(resolveCallerIdentity('forget-owner'));
  fs.writeFileSync(path.join(dir, 'run.json'), JSON.stringify({ runId, holder, pids: { backendRootPid: child.pid } }));
  fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify({ runId, holder, runPath: path.join(dir, 'run.json') }));
  const env = { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state };
  assert.equal(cli(['stop', '--run', runId, '--session-id', 'forget-owner'], env).outcome, 'STOP_INCOMPLETE');
  const result = cli(['stop', '--run', runId, '--session-id', 'forget-owner', '--forget-unresolved'], env);
  assert.equal(result.outcome, 'FORGOTTEN_UNRESOLVED', JSON.stringify(result));
  assert.equal(result.leftBehind[0].pid, child.pid);
  assert.equal(result.status, 0, 'explicit forget succeeds without requiring a force flag');
  assert.equal(child.exitCode, null, 'forget never terminates the unknown Engine');
  assert.equal(fs.existsSync(path.join(state, 'active.json')), false);
});
let failed = 0, skipped = 0;
try {
  for (const [name, fn, windowsOnly] of tests) {
    if (windowsOnly && process.platform !== 'win32') {
      console.log(`SKIP ${name}: Windows native process identities and job-object stops`);
      skipped++;
      continue;
    }
    try { await fn(); console.log(`PASS ${name}`); } catch (e) { failed++; console.error(`FAIL ${name}: ${e.stack}`); }
  }
}
finally {
  // A portable fixture uses its private self-exit channel. Terminations target only our
  // spawn-recorded identities, each by its own recorded birth; a runner's children end with its
  // job, never through a sweep of parent links.
  for (const { child, identity, exitFile, exited } of children) {
    if (exitFile) {
      fs.writeFileSync(exitFile, 'quit');
      await until(() => child.exitCode !== null, 5000);
      await exited;
    } else if (identity && child.exitCode === null) actuator.terminateWithCodeSync(child.pid, identity.creationFileTimeUtc, 1);
  }
  for (const p of extraOwned) actuator.terminateWithCodeSync(p.pid, p.creationFileTimeUtc, 1);
  // JSON/log-only fixture; no symlinks or junctions are created here.
  fs.rmSync(root, { recursive: true, force: true });
  fs.rmSync(dataRoot, { recursive: true, force: true });
}
console.log(`stop-recovery: ${tests.length - failed - skipped}/${tests.length - skipped} passed, ${skipped} skipped`);
process.exitCode = failed ? 1 : 0;
