#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
// Exercise the real Windows termination handles. Only sleepers spawned and recorded here are
// targets. Inject process-table snapshots to make otherwise nondeterministic PID reuse repeatable.
// Identity-only stops, as on the integration side since 58a51a608: the handle ends only the
// recorded process; a parent link in the process table never licenses another termination.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';

const require = createRequire(import.meta.url);
const actuator = require('./lib/engine-actuator.cjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const script = path.join(here, 'lib/terminate-process.ps1');
const quote = s => `'${String(s).replaceAll("'", "''")}'`;
const owned = [];
const alive = pid => { if (process.platform === 'linux') return require('./lib/process-identity.cjs').readLinuxProcess(pid)?.present !== false; try { process.kill(pid, 0); return true; } catch { return false; } };
async function sleeper() {
  const child = spawn(process.execPath, ['-e', 'setTimeout(()=>{},60000)'], { stdio: 'ignore' });
  const row = (await actuator.lookupProcessIdentities([child.pid]))?.get(child.pid);
  assert.ok(row?.creationFileTime || row?.processIdentity, 'a spawned target must have its birth recorded before testing');
  const identity = { pid: child.pid, birth: row.processIdentity ?? row.creationFileTime };
  owned.push(identity);
  return identity;
}
function tree(root, rows, snapshots = null, extra = '') {
  if (process.platform === 'linux') return actuator.terminateWithCodeSync(root.pid, root.birth, 1);
  const json = Buffer.from(JSON.stringify(snapshots ?? [rows])).toString('base64');
  const command = `$global:scan=0; function Get-CimInstance { $tables = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${json}')) | ConvertFrom-Json; $rows=$tables[[Math]::Min($global:scan,$tables.Count-1)]; $global:scan++; foreach ($r in $rows) { [pscustomobject]@{ ProcessId=$r.pid; ParentProcessId=$r.parent; CreationDate=$(if($r.birth){[DateTime]::FromFileTimeUtc([long]$r.birth)}else{$null}) } } }; & ${quote(script)} -Pid ${root.pid} -CreationFileTimeUtc ${root.birth} -Code 1${extra}; exit $LASTEXITCODE`;
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command],
    { encoding: 'utf8', windowsHide: true });
  assert.ok(result.stdout.trim(), `helper returned an outcome: ${result.stderr}`);
  return { status: result.status, ...JSON.parse(result.stdout.trim().split(/\r?\n/).at(-1)) };
}
const row = (child, parent) => ({ pid: child.pid, parent: parent.pid, birth: child.birth });
const tests = [
  ['real handle refuses a frontend PID whose birth no longer matches', async () => {
    const root = await sleeper();
    const result = await actuator.terminateWithCode(root.pid, (process.platform === 'linux' ? { ...root.birth, startTicks: String(BigInt(root.birth.startTicks) - 1n) } : String(BigInt(root.birth) - 100000000n)), 1);
    assert.equal(result.outcome, 'mismatch');
    assert.equal(alive(root.pid), true, 'the replacement survives');
  }],
  ['real handle ends only the recorded process, never a descendant the process table links to it', async () => {
    const root = await sleeper(), child = await sleeper(), unknown = await sleeper();
    const result = tree(root, [row(child, root), { ...row(unknown, root), birth: null }]);
    assert.equal(result.status, 0, JSON.stringify(result));
    assert.equal(result.outcome, 'terminated');
    assert.equal(result.descendantsEnded, undefined, 'no descendant discovery');
    assert.equal(alive(root.pid), false, 'the recorded process ends');
    assert.equal(alive(child.pid), true, 'a linked, birth-proven child is not reached through its parent link');
    assert.equal(alive(unknown.pid), true, 'a linked child without a birth is untouched');
  }],
  ['the real handle has no tree mode', async () => {
    const root = await sleeper(), child = await sleeper();
    if (process.platform === 'linux') {
      const helper = require('./lib/process-identity.cjs').boundExecPath();
      const result = spawnSync(helper, ['signal', String(root.pid), root.birth.bootId, root.birth.startTicks, 'SIGKILL', '-Tree'], { encoding: 'utf8' });
      assert.notEqual(result.status, 0, 'a tree request is refused, not silently honoured');
      assert.match(result.stderr, /invalid signal arguments/);
      assert.equal(alive(root.pid), true); assert.equal(alive(child.pid), true); return;
    }
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script,
      '-Pid', String(root.pid), '-CreationFileTimeUtc', root.birth, '-Code', '1', '-Tree'], { encoding: 'utf8', windowsHide: true });
    assert.notEqual(result.status, 0, 'a tree request is refused, not silently honoured');
    assert.match(result.stdout + result.stderr, /Tree/, 'the refusal names the unknown tree parameter');
    assert.equal(alive(root.pid), true, 'a refused request ends nothing');
    assert.equal(alive(child.pid), true);
  }],
  ['frontend identity stop on real native handles ends the recorded frontend and nothing it did not record', async () => {
    const parent = await sleeper(), unrecorded = await sleeper();
    const state = fs.mkdtempSync(path.join(os.tmpdir(), 'identity-stop-'));
    process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = state;
    const { stopRun } = require('./dev-runner.cjs').__test;
    const runId = 'run-identity-real', runPath = path.join(state, 'run.json'), activePath = path.join(state, 'active.json');
    const run = { runId, pids: { frontendRootPid: parent.pid },
      identities: { frontend: process.platform === 'linux' ? { pid: parent.pid, processIdentity: parent.birth } : { pid: parent.pid, creationFileTimeUtc: parent.birth } } };
    fs.writeFileSync(runPath, JSON.stringify(run));
    fs.writeFileSync(activePath, JSON.stringify({ runId }));
    const requests = [];
    try {
      const result = await stopRun({ runId }, {
        resolveTarget: async () => ({ run, runPath }),
        census: async () => ({ finish: async () => ({}) }),
        gracefulShutdown: async () => ({ outcome: 'not_attempted' }),
        terminateProcess: (...args) => { requests.push(args); return actuator.terminateWithCode(...args); },
        portOwner: async () => ({ closed: true }), portWaitMs: 0,
      });
      assert.equal(alive(parent.pid), false, 'the real recorded frontend exited');
      assert.equal(alive(unrecorded.pid), true, 'a process the run did not record is never ended');
      assert.deepEqual(requests.map(r => r[0]), [parent.pid], 'only the recorded identity is requested');
      assert.ok(requests.every(r => r.length <= 3), 'no tree option reaches the termination handle');
      assert.deepEqual(result.killedPids, [parent.pid], JSON.stringify(result));
      assert.equal(result.outcome, 'STOPPED', JSON.stringify(result));
    } finally {
      // Own JSON-only fixture; sleeper cleanup below still uses its recorded spawn identity.
      fs.rmSync(state, { recursive: true, force: true });
    }
  }],
  ['legacy cleanup uses the selected birth at termination and reports a reused PID incomplete', async () => {
    const child = await sleeper();
    const oldBirth = String(BigInt(child.birth) - 100000000n);
    // Both before and after scan APIs see the recorded process. The OS handle sees its replacement.
    const command = `$global:LASTEXITCODE=0; function Get-Process { [pscustomobject]@{Id=${child.pid}; WorkingSet64=0} }; function Get-CimInstance { [pscustomobject]@{ProcessId=${child.pid}; CommandLine='io.justsearch.HeadlessApp'; WorkingSetSize=0; CreationDate=[DateTime]::FromFileTimeUtc(${oldBirth}L)} }; function Stop-Process { param($Id,[switch]$Force) Write-Output 'UNSAFE_PID_KILL' }; & ${quote(path.join(here, 'cleanup.ps1'))} -Force`;
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command],
      { encoding: 'utf8', windowsHide: true });
    assert.doesNotMatch(result.stdout, /UNSAFE_PID_KILL/, 'cleanup must not issue an unverified PID-only kill');
    assert.equal(result.status, 1, 'a reused identity is reported incomplete');
    assert.equal(alive(child.pid), true);
  }],
  ...['ai/token-probe.ps1', 'bench/run-B1.ps1'].map(relative => [
    `${relative} cleanup refuses PID reuse through the termination handle`, async () => {
      const target = await sleeper(), oldBirth = String(BigInt(target.birth) - 100000000n);
      const filename = path.resolve(here, '..', relative);
      const source = fs.readFileSync(filename, 'utf8');
      // Execute the production cleanup block only. Never run a GPU/server/benchmark workload.
      const block = relative.startsWith('ai/')
        ? source.slice(source.lastIndexOf('} finally {') + '} finally {'.length, source.lastIndexOf('}'))
        : source.slice(source.indexOf('  Stop-Process -Id $dmon.Id') >= 0 ? source.indexOf('  Stop-Process -Id $dmon.Id')
          : source.indexOf('  foreach ($telemetry in'), source.indexOf('  Stop-Job -Job $smartJob'));
      assert.ok(block.trim());
      const command = `$startedPid=${target.pid}; $startedBirth='${oldBirth}'; $dmon=[pscustomobject]@{Id=${target.pid}}; $lhmProc=$dmon; $dmonBirth='${oldBirth}'; $lhmBirth='${oldBirth}'; function Stop-Process { param($Id,[switch]$Force,$ErrorAction) Write-Output 'UNSAFE_PID_KILL' }; & { $PSScriptRoot=${quote(path.dirname(filename))}; ${block} }`;
      const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command],
        { encoding: 'utf8', windowsHide: true });
      assert.doesNotMatch(result.stdout, /UNSAFE_PID_KILL/, 'a stale telemetry/probe PID cannot authorize termination');
      assert.match(result.stdout, /mismatch/, `the recorded birth reaches the real handle: ${result.stdout}${result.stderr}`);
      assert.equal(alive(target.pid), true);
    },
  ]),
];

if (!['win32', 'linux'].includes(process.platform)) {
  console.log('tree-handles: Windows-only (not executed)');
} else {
  let failed = 0, skipped = 0;
  try {
    for (const [name, run] of tests) {
      if (process.platform === 'linux' && (name.startsWith('legacy cleanup') || name.startsWith('ai/') || name.startsWith('bench/'))) {
        console.log(`SKIP ${name}: Windows PowerShell cleanup entry point; Linux: test-dev-runner-stop-ownership and stop-round4`); skipped++; continue;
      }
      try { await run(); console.log(`PASS ${name}`); }
      catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
    }
  } finally {
    // Cleanup uses the spawn-time identity through the same real OS handle, never a PID alone.
    for (const child of owned) await actuator.terminateWithCode(child.pid, child.birth, 1);
  }
  console.log(`tree-handles: ${tests.length - failed - skipped}/${tests.length - skipped} passed, ${skipped} skipped`);
  process.exitCode = failed ? 1 : 0;
}
