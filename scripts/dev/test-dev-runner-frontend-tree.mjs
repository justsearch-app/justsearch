#!/usr/bin/env node
// Deterministic PID-reuse interleavings at the runner's actual frontend-stop closure and other
// dev-tool kill boundaries. No process is started or stopped by these simulated interleavings.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const birth = '134000000000000000';
const pid = 12345;
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

function moduleWithIo(file, io, overrides = {}) {
  const filename = path.join(here, file);
  const mod = { exports: {} };
  const local = createRequire(filename);
  const wrappedRequire = (id) => overrides[id]
    ?? (id === './recorded-termination.cjs' ? moduleWithIo('lib/recorded-termination.cjs', io) : undefined)
    ?? (['child_process', 'node:child_process'].includes(id) ? io : local(id));
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    require: wrappedRequire, module: mod, exports: mod.exports, __dirname: path.dirname(filename),
    __filename: filename, process: { ...process, platform: 'win32', kill: (...args) => io.kill?.(...args) }, console, Buffer,
    setTimeout, clearTimeout, setInterval, clearInterval, URL,
  }, { filename });
  return mod.exports;
}

function frontendStop({ recorded = { pid, creationFileTimeUtc: birth }, outcome = 'terminated',
  currentBirth = birth, exits = true } = {}) {
  const frontend = Object.assign(new EventEmitter(), { pid, exitCode: null, signalCode: null });
  const ended = [];
  const calls = [];
  const end = () => {
    ended.push(pid);
    if (exits) queueMicrotask(() => { frontend.exitCode = 1; frontend.emit('exit', 1); });
  };
  const source = fs.readFileSync(path.join(here, 'dev-runner.cjs'), 'utf8');
  const closure = source.match(/  stopFrontendTree = async \(waitMs = 3000\) => \{[\s\S]*?\n  \};/)?.[0];
  assert.ok(closure, 'exercise the production frontend stop, including the starting revision');
  const context = {
    frontend, frontendIdentity: recorded,
    frontendStopIncomplete: source.includes('let frontendStopIncomplete = true;'),
    process: { platform: 'win32' }, setTimeout, clearTimeout,
    // Model the old OS call: a PID-only kill ends whatever now has that PID.
    spawnSync: () => { end(); return { status: 0 }; },
    engineActuator: { NON_DISTINCTIVE_KILL_CODE: 1,
      terminateWithCodeSync: (target, recordedBirth, code, opts) => {
        calls.push({ target, recordedBirth, code, ...opts });
        if (recordedBirth !== currentBirth) return { ok: false, outcome: 'mismatch' };
        if (outcome === 'terminated' || outcome === 'partial') end();
        return { ok: outcome === 'terminated', outcome };
      } },
  };
  vm.runInNewContext(closure, context);
  return { stop: context.stopFrontendTree, frontend, ended, calls };
}

test('frontend PID reuse between record and stop never ends the replacement, and reports incomplete', async () => {
  const run = frontendStop({ currentBirth: '134000000000100000' });
  const stopped = await run.stop(10);
  assert.deepEqual(run.ended, [], 'the unrelated replacement must survive');
  assert.equal(stopped, false);
});

test('frontend without a recorded birth never kills and reports incomplete', async () => {
  const run = frontendStop({ recorded: null });
  assert.equal(await run.stop(10), false);
  assert.deepEqual(run.ended, []);
});

test('frontend partial tree stop stays incomplete after the root exits and on a repeated call', async () => {
  const run = frontendStop({ outcome: 'partial' });
  assert.equal(await run.stop(10), false, 'root exit cannot conceal an unproven/surviving descendant');
  assert.equal(await run.stop(10), false);
});

test('frontend root already exited without a verified tree stop does not prove its descendants ended', async () => {
  const run = frontendStop();
  run.frontend.exitCode = 1;
  assert.equal(await run.stop(10), false);
  assert.deepEqual(run.ended, []);
});

test('frontend verified stop keeps the exit result, tree scope, code and five-second actuator bound', async () => {
  const run = frontendStop();
  assert.equal(await run.stop(10), true);
  assert.deepEqual(run.calls, [{ target: pid, recordedBirth: birth, code: 1, tree: true, timeoutMs: 5000 }]);
  assert.equal(run.frontend.listenerCount('exit'), 0);
});

test('frontend still waits for exit and times out when termination did not produce an exit', async () => {
  const run = frontendStop({ exits: false });
  assert.equal(await run.stop(10), false);
});

test('actuator fallback rechecks the recorded birth through the termination handle', () => {
  const ended = [];
  const calls = [];
  const io = { spawnSync: (command, args) => {
    calls.push({ command, args });
    if (/taskkill/i.test(command)) { ended.push(pid); return { status: 0 }; }
    return { status: 3, stdout: '{"outcome":"mismatch"}' };
  } };
  const actuator = moduleWithIo('lib/engine-actuator.cjs', io);
  const stopped = actuator.taskkillPid(pid, { creationFileTime: birth });
  assert.deepEqual(ended, [], 'a fallback cannot spend a stale inspection on a PID-only kill');
  assert.equal(stopped, false);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].args[calls[0].args.indexOf('-CreationFileTimeUtc') + 1], birth);
});

test('actuator fallback with no birth refuses before calling the OS', () => {
  const calls = [];
  const actuator = moduleWithIo('lib/engine-actuator.cjs', {
    spawnSync: (...args) => { calls.push(args); return { status: 0 }; },
  });
  assert.equal(actuator.taskkillPid(pid), false);
  assert.equal(calls.length, 0);
});

test('unsupported fallback refuses a PID-only stop instead of claiming a verified kill', () => {
  const ended = [];
  const actuator = moduleWithIo('lib/engine-actuator.cjs', { kill: (target) => ended.push(target) });
  assert.equal(actuator.taskkillPid(pid, { platform: 'linux', creationFileTime: birth }), false);
  assert.deepEqual(ended, []);
});

test('unsupported recorded stop records incomplete without issuing a PID-only kill', async () => {
  const source = fs.readFileSync(path.join(here, 'dev-runner.cjs'), 'utf8');
  const closure = source.match(/  const terminateRecorded = async \(p, row, \{ tree = false \} = \{\}\) => \{[\s\S]*?\n  \};/)?.[0];
  assert.ok(closure);
  const ended = [], errors = [];
  const context = { process: { platform: 'linux', kill: target => ended.push(target) }, errors, killedPids: [] };
  vm.runInNewContext(`${closure}\nthis.stop = terminateRecorded;`, context);
  await context.stop({ pid, role: 'frontend', creationFileTimeUtc: birth });
  assert.deepEqual(ended, []);
  assert.equal(errors.length, 1);
});

test('manual reaper and worktree remedies carry birth or withhold an unsafe command', () => {
  for (const [file, name, args] of [
    ['lib/agent-spawn-reaper.cjs', 'killLineFor', [pid, birth]],
    ['remove-worktree.cjs', 'formatHolderLines', [{ ProcessId: pid, CreationFileTimeUtc: birth }]],
  ]) {
    const source = fs.readFileSync(path.join(here, file), 'utf8');
    const declaration = source.match(new RegExp(`function ${name}\\([^]*?\\n\\}`))?.[0];
    assert.ok(declaration);
    const fn = vm.runInNewContext(`(${declaration})`);
    const text = String(fn(...args));
    assert.doesNotMatch(text, /taskkill/i, `${name} must not suggest PID-only termination`);
    assert.ok(text.includes(`-CreationFileTimeUtc ${birth}`));
    const unknown = String(name === 'killLineFor' ? fn(pid) : fn({ ProcessId: pid }));
    assert.match(unknown, /no safe termination command/);
  }
});

test('forced Engine stop carries the spawn-time birth into its non-distinctive fallback', async () => {
  const child = Object.assign(new EventEmitter(), { pid });
  const calls = [];
  const engine = require('./lib/engine-actuator.cjs').createEngineActuator({
    dataDir: 'unused-in-memory-ledger', runId: 'frontend-tree-test', policy: {},
    spawnChild: () => child, ledger: { append: () => true }, log: () => {},
    lookupIdentities: async () => new Map([[pid, { present: true, creationFileTime: birth }]]),
    terminate: async () => ({ ok: false, outcome: 'failed' }),
    fallbackKill: (...args) => { calls.push(args); return false; },
  });
  engine.spawnEngine({ n: 1 });
  await engine.flush();
  const result = await engine.forceKill('quit');
  assert.equal(result.killed, false);
  assert.deepEqual(calls, [[pid, { creationFileTime: birth }]]);
});

test('spawn reaper passes the record birth through its last kill boundary, without tree scope', () => {
  const calls = [];
  const res = require('./lib/agent-spawn-reaper.cjs').taskkillByPid(pid, {
    platform: 'win32', creationFileTimeUtc: birth,
    exec: (command, args) => { calls.push({ command, args }); return { status: 3, stdout: '{"outcome":"mismatch"}' }; },
  });
  assert.equal(res.ok, false);
  assert.equal(calls.length, 1);
  assert.ok(calls[0].args.includes(birth), 'the termination call must carry the recorded birth');
  assert.ok(!calls[0].args.includes('-Tree'), 'the reaper owns only the registered process');
});

test('worktree frontend kill uses its spawn-time identity and rejects a reused PID', () => {
  const calls = [];
  const ended = [];
  const serve = moduleWithIo('serve-worktree-fe.cjs', {
    spawnSync: () => { ended.push(pid); return { status: 0 }; },
  }, { './lib/engine-actuator.cjs': { NON_DISTINCTIVE_KILL_CODE: 1,
    terminateWithCodeSync: (...args) => { calls.push(args); return { ok: false, outcome: 'mismatch' }; } } });
  const result = serve.killSpawnTree({ pid }, { pid, creationFileTimeUtc: birth });
  assert.deepEqual(ended, [], 'the frontend shim PID is not sufficient kill authority');
  assert.equal(result.ok, false);
  assert.equal(calls[0][1], birth);
  assert.equal(calls[0][3].tree, true);
  calls.length = 0;
  assert.equal(serve.killSpawnTree({ pid }).ok, false);
  assert.equal(calls.length, 0);
});

let failed = 0;
for (const [name, run] of tests) {
  try { await run(); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
}
console.log(`frontend-tree: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
