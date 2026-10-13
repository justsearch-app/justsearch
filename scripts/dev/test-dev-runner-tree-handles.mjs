#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
// Exercise the real Windows termination handles. Only sleepers spawned and recorded here are
// targets. Inject process-table snapshots to make otherwise nondeterministic PID reuse repeatable.
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
const alive = pid => { try { process.kill(pid, 0); return true; } catch { return false; } };
async function sleeper() {
  const child = spawn(process.execPath, ['-e', 'setTimeout(()=>{},60000)'], { stdio: 'ignore' });
  const row = (await actuator.lookupProcessIdentities([child.pid]))?.get(child.pid);
  assert.ok(row?.creationFileTime, 'a spawned target must have its birth recorded before testing');
  const identity = { pid: child.pid, birth: row.creationFileTime };
  owned.push(identity);
  return identity;
}
function tree(root, rows, snapshots = null) {
  const json = Buffer.from(JSON.stringify(snapshots ?? [rows])).toString('base64');
  const command = `$global:scan=0; function Get-CimInstance { $tables = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${json}')) | ConvertFrom-Json; $rows=$tables[[Math]::Min($global:scan,$tables.Count-1)]; $global:scan++; foreach ($r in $rows) { [pscustomobject]@{ ProcessId=$r.pid; ParentProcessId=$r.parent; CreationDate=$(if($r.birth){[DateTime]::FromFileTimeUtc([long]$r.birth)}else{$null}) } } }; & ${quote(script)} -Pid ${root.pid} -CreationFileTimeUtc ${root.birth} -Code 1 -Tree; exit $LASTEXITCODE`;
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command],
    { encoding: 'utf8', windowsHide: true });
  assert.ok(result.stdout.trim(), `helper returned an outcome: ${result.stderr}`);
  return { status: result.status, ...JSON.parse(result.stdout.trim().split(/\r?\n/).at(-1)) };
}
const row = (child, parent) => ({ pid: child.pid, parent: parent.pid, birth: child.birth });
const tests = [
  ['real handle refuses a frontend PID whose birth no longer matches', async () => {
    const root = await sleeper();
    const result = await actuator.terminateWithCode(root.pid, String(BigInt(root.birth) - 100000000n), 1, { tree: true });
    assert.equal(result.outcome, 'mismatch');
    assert.equal(alive(root.pid), true, 'the replacement survives');
  }],
  ['real tree handle reports incomplete for a linked descendant whose birth is unavailable', async () => {
    const root = await sleeper(), child = await sleeper();
    const result = tree(root, [{ pid: child.pid, parent: root.pid, birth: null }]);
    assert.equal(alive(child.pid), true, 'unproven descendants are untouched');
    assert.equal(result.outcome, 'partial', 'missing identity is an incomplete tree, never success');
    assert.equal(result.status, 4);
    assert.deepEqual(result.descendantsNotEnded, [child.pid]);
  }],
  ['missing birth retains every unresolved link below it', async () => {
    const root = await sleeper(), unknown = await sleeper(), leaf = await sleeper();
    const result = tree(root, [{ ...row(unknown, root), birth: null }, row(leaf, unknown)]);
    assert.equal(result.outcome, 'partial');
    assert.deepEqual([...result.descendantsNotEnded].sort(), [unknown.pid, leaf.pid].sort(),
      'an unknown ancestor does not make its descendants disappear from the result');
    assert.equal(alive(unknown.pid), true);
    assert.equal(alive(leaf.pid), true, 'a parent link through an unknown identity never licenses termination');
  }],
  ['partialFrontendTreeKeepsStopIncomplete on real Windows handles and the persisted run', async () => {
    const parent = await sleeper(), descendant = await sleeper();
    const state = fs.mkdtempSync(path.join(os.tmpdir(), 'partial-tree-stop-'));
    process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = state;
    const { stopRun } = require('./dev-runner.cjs').__test;
    const runId = 'run-partial-real', runPath = path.join(state, 'run.json'), activePath = path.join(state, 'active.json');
    const run = { runId, pids: { frontendRootPid: parent.pid },
      identities: { frontend: { pid: parent.pid, creationFileTimeUtc: parent.birth } } };
    fs.writeFileSync(runPath, JSON.stringify(run));
    fs.writeFileSync(activePath, JSON.stringify({ runId }));
    try {
      const result = await stopRun({ runId }, {
        resolveTarget: async () => ({ run, runPath }),
        census: async () => ({ finish: async () => ({}) }),
        gracefulShutdown: async () => ({ outcome: 'not_attempted' }),
        terminateProcess: (target, recordedBirth, code, options) => actuator.terminateWithCode(target, recordedBirth, code, {
          ...options, exec: (_cmd, _args, _opts, done) => {
            const outcome = tree(parent, [{ ...row(descendant, parent), birth: null }]);
            done({ code: outcome.status }, JSON.stringify(outcome));
          },
        }),
        portOwner: async () => ({ closed: true }), portWaitMs: 0,
      });
      assert.equal(alive(parent.pid), false, 'the real root exited');
      assert.equal(alive(descendant.pid), true, 'the unresolved real process survives');
      assert.equal(result.outcome, 'STOP_INCOMPLETE', 'root exit cannot conceal the unregistered survivor');
      assert.equal(JSON.parse(fs.readFileSync(activePath, 'utf8')).runId, runId);
    } finally {
      // Own JSON-only fixture; descendant cleanup below still uses its recorded spawn identity.
      fs.rmSync(state, { recursive: true, force: true });
    }
  }],
  ['late child is found by a bounded re-enumeration while parent handles remain pinned', async () => {
    const root = await sleeper(), child = await sleeper();
    const result = tree(root, [], [[], [row(child, root)], []]);
    assert.equal(alive(child.pid), false, 'a child missed by the initial table is still ended');
    assert.equal(result.outcome, 'terminated');
    assert.deepEqual(result.descendantsEnded, [child.pid]);
  }],
  ['continuously expanding snapshots report the enumeration bound, never a complete tree', async () => {
    const root = await sleeper(), a = await sleeper(), b = await sleeper(), c = await sleeper();
    const result = tree(root, [], [[row(a, root)], [row(b, a)], [row(c, b)]]);
    assert.equal(result.outcome, 'partial', 'the last bounded scan cannot prove that no later child was missed');
    assert.equal(result.enumerationLimitReached, true);
  }],
  ['real tree handle reports incomplete when a descendant PID changes birth after the snapshot', async () => {
    const root = await sleeper(), child = await sleeper();
    const snapshot = { ...row(child, root), birth: String(BigInt(child.birth) - 10000n) };
    assert.ok(BigInt(snapshot.birth) > BigInt(root.birth), 'snapshot child must be newer than root');
    const result = tree(root, [snapshot]);
    assert.equal(alive(child.pid), true, 'a replacement descendant survives');
    assert.equal(result.outcome, 'partial', 'identity mismatch cannot be hidden by root termination');
    assert.equal(result.status, 4);
  }],
  ['real tree rejects a reused parent link and everything below it, but ends proven descendants', async () => {
    const root = await sleeper(), oldChild = await sleeper(), reusedParent = await sleeper(), leaf = await sleeper();
    const result = tree(root, [row(reusedParent, root), row(oldChild, reusedParent), row(leaf, oldChild)]);
    assert.equal(result.outcome, 'terminated');
    assert.deepEqual(result.descendantsEnded, [reusedParent.pid]);
    assert.equal(alive(oldChild.pid), true, 'an older child cannot belong to the new parent identity');
    assert.equal(alive(leaf.pid), true, 'a later descendant cannot bypass the broken ancestry link');
    assert.equal((result.processes ?? []).some(p => p.pid === oldChild.pid || p.pid === leaf.pid), false,
      'proven foreign ancestry cannot be carried forward as unresolved ownership');
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

if (process.platform !== 'win32') {
  console.log('tree-handles: Windows-only (not executed)');
} else {
  let failed = 0;
  try {
    for (const [name, run] of tests) {
      try { await run(); console.log(`PASS ${name}`); }
      catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
    }
  } finally {
    // Cleanup uses the spawn-time identity through the same real OS handle, never a PID alone.
    for (const child of owned) await actuator.terminateWithCode(child.pid, child.birth, 1);
  }
  console.log(`tree-handles: ${tests.length - failed}/${tests.length} passed`);
  process.exitCode = failed ? 1 : 0;
}
