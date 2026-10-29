/** Real CLI/Git/filesystem checks; the Gradle wrapper is a deterministic result producer. */
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { evidenceSelfDigest } from './evidence.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CHECK = 'modules/core/src/test/java/io/x/ProbeTest.java#probe';
const CLI = 'scripts/governance/gates/test-intent/run-evidence.mjs';
const WINDOWS = process.platform === 'win32';

function detachLinks(entry) {
  const stat = fs.lstatSync(entry, { throwIfNoEntry: false });
  if (stat?.isSymbolicLink()) fs.unlinkSync(entry);
  else if (stat?.isDirectory()) {
    for (const name of fs.readdirSync(entry)) detachLinks(path.join(entry, name));
  }
}

async function fixture(options, check) {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'evidence-cli-'));
  const root = path.join(scratch, 'repo');
  const beforeRoot = path.join(scratch, 'before');
  const out = path.join(scratch, 'evidence.json');
  const env = { ...process.env, LIGHTGBM_NATIVE_LIB_PATH: '', PROBE_NODE: process.execPath,
    PROBE_ROOT: root, PROBE_CONTROL: scratch, PROBE_MODE: options.mode ?? 'assertion',
    PROBE_LOCK: options.lock ? '1' : '' };
  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'core.longpaths=true', ...args], { cwd: root, env, encoding: 'utf8' });
    assert.equal(result.status, 0, `fixture git ${args.join(' ')}: ${result.stderr}`);
    return result.stdout.trim();
  };
  const write = (rel, text) => {
    const file = path.join(root, rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text, 'utf8');
  };
  let locker;
  let lockerClosed;
  let lockerOutput = '';
  try {
    fs.mkdirSync(root);
    fs.mkdirSync(beforeRoot);
    if (options.profile) {
      const inherited = path.join(scratch, 'developer-profile');
      for (const key of ['HOME', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME',
        'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_STATE_HOME', 'JUSTSEARCH_HOME', 'JUSTSEARCH_DATA_DIR']) env[key] = inherited;
      env.GRADLE_USER_HOME = path.join(scratch, 'gradle-cache');
      env.PROBE_PROFILE = '1';
    }
    git('init', '-q');
    // Override even a machine-wide true setting: the runner must supply long-path support.
    git('config', 'core.longpaths', 'false');
    git('config', 'core.autocrlf', 'false');
    for (const name of ['run-evidence', 'changeset', 'evidence', 'evidence-runners', 'rust-tests', 'classifications']) {
      write(`scripts/governance/gates/test-intent/${name}.mjs`, fs.readFileSync(path.join(HERE, `${name}.mjs`), 'utf8'));
    }
    write('scripts/governance/lib/frontmatter.mjs', fs.readFileSync(path.join(HERE, '../../lib/frontmatter.mjs'), 'utf8'));
    write(CHECK.split('#')[0], '// deterministic CLI boundary fixture\n');
    write('.gitignore', 'modules/core/build/\n');
    if (options.lfs) {
      git('lfs', 'install', '--local', '--skip-smudge');
      write('.gitattributes', '*.lfs-test filter=lfs diff=lfs merge=lfs -text\n');
      write('modified.lfs-test', 'original LFS content\n');
    }
    write('probe.cjs', `
const fs = require('node:fs'), path = require('node:path');
const before = process.cwd() !== process.env.PROBE_ROOT;
const control = process.env.PROBE_CONTROL;
if (process.env.PROBE_PROFILE) {
  fs.writeFileSync(path.join(control, before ? 'before-profile.json' : 'candidate-profile.json'),
    JSON.stringify({ cwd: process.cwd(), env: process.env,
      init: fs.existsSync('build/test-evidence-profile.init.gradle')
        ? fs.readFileSync('build/test-evidence-profile.init.gradle', 'utf8') : null }));
}
if (before && process.env.PROBE_LOCK) {
  const locked = path.join(process.cwd(), 'held-file');
  fs.writeFileSync(locked, 'held without delete sharing');
  fs.writeFileSync(path.join(control, 'lock-request'), locked);
  const deadline = Date.now() + 30000;
  while (!fs.existsSync(path.join(control, 'lock-ready'))) {
    if (Date.now() > deadline) throw new Error('lock holder did not become ready');
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 20);
  }
}
if (before && process.env.PROBE_MODE === 'interrupted') {
  const result = require('node:child_process').spawnSync(path.join(control, 'interrupt.exe'), [],
    { encoding: 'utf8', windowsHide: true });
  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  fs.writeFileSync(path.join(control, 'interrupt-result'), JSON.stringify({ status: result.status, output: result.stderr }));
  if ((result.status >>> 0) !== 0xC000013A) throw new Error('isolated CTRL_BREAK did not interrupt the child: ' + result.status);
  process.exit(result.status);
}
const failure = !before ? '' : process.env.PROBE_MODE === 'exception'
  ? '<failure type="java.lang.IllegalStateException" message="before probe exploded"/>'
  : '<failure type="org.opentest4j.AssertionFailedError" message="before assertion"/>';
fs.mkdirSync('modules/core/build/test-results/test', { recursive: true });
fs.writeFileSync('modules/core/build/test-results/test/TEST-io.x.ProbeTest.xml',
  '<testsuite><testcase classname="io.x.ProbeTest" name="probe">' + failure + '</testcase><testcase classname="io.x.ProbeTest" name="skipped"><skipped/></testcase></testsuite>');
if (before && process.env.PROBE_MODE === 'exception') console.error('java.lang.IllegalStateException: before probe exploded');
process.exitCode = before ? 1 : 0;
`);
    if (WINDOWS) write('gradlew.bat', '@echo off\n"%PROBE_NODE%" "%~dp0probe.cjs"\nexit /b %errorlevel%\n');
    else {
      write('gradlew', '#!/bin/sh\nexec "$PROBE_NODE" ./probe.cjs\n');
      fs.chmodSync(path.join(root, 'gradlew'), 0o755);
    }
    if (options.longPath) {
      const rel = `${Array.from({ length: 5 }, (_, i) => `segment-${i}-${'x'.repeat(48)}`).join('/')}/marker.txt`;
      assert.ok(path.join(beforeRoot, 'test-intent-before-abcdef', rel).length > 360);
      write(rel, 'long path must survive checkout and hashing\n');
    }
    // This is an isolated fixture repository, never the user's checkout/history.
    git('add', '-A');
    git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
      '-c', 'commit.gpgsign=false', 'commit', '-q', '-m', 'CLI fixture');
    if (options.mode === 'interrupted') {
      const cs = path.join(scratch, 'interrupt.cs');
      fs.writeFileSync(cs, `
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
class Interrupt {
  [DllImport("kernel32.dll")] static extern bool AllocConsole();
  [DllImport("kernel32.dll")] static extern uint GetConsoleProcessList(uint[] list, uint size);
  [DllImport("kernel32.dll")] static extern bool GenerateConsoleCtrlEvent(uint kind, uint group);
  [DllImport("kernel32.dll")] static extern IntPtr GetConsoleWindow();
  [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr window, int command);
  static int Main() {
    // A GUI-subsystem executable starts without a console. Allocate a private one, and fail
    // closed if any other process shares it; never send an event into the test runner's console.
    using (Process own = Process.GetCurrentProcess()) {
      File.WriteAllText(Path.Combine(Environment.GetEnvironmentVariable("PROBE_CONTROL"), "interrupt-identity"),
        own.Id + "/" + own.StartTime.ToUniversalTime().ToFileTimeUtc());
      if (!AllocConsole()) return 90;
      ShowWindow(GetConsoleWindow(), 0);
      uint[] members = new uint[2];
      if (GetConsoleProcessList(members, 2) != 1 || members[0] != (uint)own.Id) return 91;
      Console.Error.WriteLine("before probe interrupted: CTRL_BREAK_EVENT");
      Console.Error.Flush();
      if (!GenerateConsoleCtrlEvent(1, 0)) return 92;
      Thread.Sleep(5000);
      return 93;
    }
  }
}
`, 'utf8');
      const compile = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        'Add-Type -Path (Join-Path $env:PROBE_CONTROL interrupt.cs) -OutputAssembly (Join-Path $env:PROBE_CONTROL interrupt.exe) -OutputType WindowsApplication'],
      { env, windowsHide: true, encoding: 'utf8' });
      assert.equal(compile.status, 0, `interrupt fixture compilation: ${compile.stderr}`);
    }
    if (options.lock) {
      const lockScript = path.join(scratch, 'hold.ps1');
      fs.writeFileSync(lockScript, `
$ErrorActionPreference = 'Stop'
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$request = Join-Path $env:PROBE_CONTROL 'lock-request'
$release = Join-Path $env:PROBE_CONTROL 'lock-release'
while (-not (Test-Path -LiteralPath $request)) {
  if ((Test-Path -LiteralPath $release) -or [DateTime]::UtcNow -gt $deadline) { exit 0 }
  Start-Sleep -Milliseconds 20
}
$held = [IO.File]::Open([IO.File]::ReadAllText($request), [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
try {
  [IO.File]::WriteAllText((Join-Path $env:PROBE_CONTROL 'lock-ready'), 'held')
  while (-not (Test-Path -LiteralPath $release)) {
    if ([DateTime]::UtcNow -gt $deadline) { throw 'lock release timed out' }
    Start-Sleep -Milliseconds 20
  }
} finally { $held.Dispose() }
`, 'utf8');
      // Cooperative release plus a bounded self-exit; the fixture never kills a process.
      locker = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', lockScript],
        { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      locker.stdout.on('data', data => { lockerOutput += data; });
      locker.stderr.on('data', data => { lockerOutput += data; });
      lockerClosed = once(locker, 'close');
    }
    if (options.candidate) write(CHECK.split('#')[0], '// changed candidate check\n');
    if (options.lfs) {
      write('modified.lfs-test', 'changed LFS content\n');
      write('added.lfs-test', 'new LFS content\n');
    }
    const gitBefore = options.lfs ? gitSnapshot(path.join(root, '.git')) : null;
    const result = spawnSync(process.execPath, [path.join(root, CLI), '--out', out, '--check', CHECK,
      ...(options.mixed ? ['--check', CHECK.replace('#probe', '#noSuchMethod'),
        '--check', CHECK.replace('#probe', '#skipped')] : []),
      ...(options.candidate ? [] : ['--before', options.invalidRef ? 'NO-SUCH-REVISION' : 'HEAD', '--before-root', beforeRoot])],
    { cwd: root, env, encoding: 'utf8' });
    const output = `${result.stdout}\n${result.stderr}`;
    if (options.lock) {
      assert.equal(fs.readFileSync(path.join(scratch, 'lock-ready'), 'utf8'), 'held');
      assert.match(output, /cleanup failed/, 'real Git removal must encounter the held Windows file');
    }
    if (options.mode === 'interrupted') {
      const interrupted = JSON.parse(fs.readFileSync(path.join(scratch, 'interrupt-result'), 'utf8'));
      assert.equal(interrupted.status >>> 0, 0xC000013A, 'the private console child must really be interrupted');
      assert.match(interrupted.output, /interrupted: CTRL_BREAK_EVENT/);
      assert.match(fs.readFileSync(path.join(scratch, 'interrupt-identity'), 'utf8'), /^\d+\/\d+$/);
    }
    await check({ result, output, out, beforeRoot, git, scratch, root, env, gitBefore });
  } finally {
    fs.writeFileSync(path.join(scratch, 'lock-release'), 'release');
    if (locker) {
      const [code] = await lockerClosed;
      assert.equal(code, 0, lockerOutput);
    }
    // Also clean failed baseline runs: detach all links before giving anything to Git/removal.
    detachLinks(scratch);
    for (const name of fs.readdirSync(beforeRoot)) {
      const wt = path.join(beforeRoot, name);
      if (fs.existsSync(path.join(wt, '.git'))) git('worktree', 'remove', '--force', wt);
    }
    assert.equal(path.dirname(scratch), path.resolve(os.tmpdir()));
    fs.rmSync(scratch, { recursive: true, force: true });
  }
}

function readEvidence(out) {
  assert.ok(fs.existsSync(out), 'completed run results must be written even when cleanup fails');
  const evidence = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.equal(evidence.digest, evidenceSelfDigest(evidence));
  return evidence;
}

function gitSnapshot(dir) {
  return Object.fromEntries(fs.readdirSync(dir, { recursive: true }).sort().map(rel => {
    const file = path.join(dir, rel);
    return [rel, fs.statSync(file).isFile() ? fs.readFileSync(file).toString('base64') : null];
  }));
}

test('CLI: candidate-only LFS hashing leaves Git untouched and preserves the filtered tree', async t => {
  const lfs = spawnSync('git-lfs', ['version'], { encoding: 'utf8' });
  if (lfs.error?.code === 'ENOENT') return t.skip('git-lfs is not installed');
  assert.equal(lfs.status, 0, lfs.stderr);
  await fixture({ candidate: true, lfs: true }, ({ result, output, out, scratch, root, env, gitBefore }) => {
    assert.equal(result.status, 0, output);
    const gitAfter = gitSnapshot(path.join(root, '.git'));
    assert.deepEqual([...new Set([...Object.keys(gitBefore), ...Object.keys(gitAfter)])]
      .filter(rel => gitBefore[rel] !== gitAfter[rel]), [],
      'candidate-only hashing must not write Git objects, LFS objects or LFS temporary files');
    const copy = path.join(scratch, 'writable-copy');
    const copyGit = (...args) => {
      const result = spawnSync('git', args, { cwd: copy, env, encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
      return result.stdout.trim();
    };
    fs.mkdirSync(copy);
    copyGit('clone', '--quiet', '--shared', '--no-checkout', root, '.');
    fs.cpSync(root, copy, { recursive: true, filter: source => source !== path.join(root, '.git') });
    copyGit('lfs', 'install', '--local', '--skip-smudge');
    copyGit('add', '-A');
    const expected = copyGit('write-tree');
    for (const file of ['added.lfs-test', 'modified.lfs-test']) {
      assert.match(copyGit('show', `${expected}:${file}`), /^version https:\/\/git-lfs.github.com\/spec\/v1\n/,
        'the reference tree must contain cleaned LFS pointers');
    }
    assert.equal(readEvidence(out).tree, expected);
  });
});

test('CLI: invalid before revision removes its empty reservation without a second error', async () => {
  await fixture({ invalidRef: true }, ({ result, output, beforeRoot, git }) => {
    assert.equal(result.status, 2);
    assert.match(output, /invalid reference: NO-SUCH-REVISION/);
    assert.deepEqual(fs.readdirSync(beforeRoot), [], 'invalid reference must not leak a reserved directory');
    assert.doesNotMatch(output, /cleanup failed|is not a working tree/);
    assert.equal((git('worktree', 'list', '--porcelain').match(/^worktree /gm) ?? []).length, 1);
  });
});

for (const mode of ['exception', 'interrupted']) {
  test(`CLI: ${mode} results and diagnostics survive a real Windows cleanup lock`, { skip: !WINDOWS }, async () => {
    await fixture({ mode, lock: true }, ({ result, output, out }) => {
      assert.equal(result.status, 2, 'cleanup failure remains an infrastructure error');
      const evidence = readEvidence(out);
      assert.equal(evidence.failBefore.checks[0].classification, mode === 'exception' ? 'exception' : 'environment');
      assert.notEqual(evidence.failBefore.runs[0].exitCode, 0);
      const diagnostic = mode === 'exception' ? /IllegalStateException: before probe exploded/ : /interrupted: CTRL_BREAK_EVENT/;
      assert.match(output, diagnostic, 'the CLI must name the run failure as well as cleanup failure');
      assert.match(evidence.failBefore.runs[0].output, diagnostic, 'the evidence must retain the child diagnostic');
      assert.match(output, /fail-before .*: (exception|environment)/);
      assert.match(output, /evidence written:/);
    });
  });
}

test('CLI: long paths work with core.longpaths disabled in the fixture repository', { skip: !WINDOWS }, async () => {
  await fixture({ longPath: true }, ({ result, output, out, beforeRoot, git }) => {
    assert.equal(result.status, 0, `long-path checkout, hashing and removal must succeed: ${output}`);
    assert.equal(readEvidence(out).failBefore.checks[0].classification, 'assertion');
    assert.deepEqual(fs.readdirSync(beforeRoot), []);
    assert.equal((git('worktree', 'list', '--porcelain').match(/^worktree /gm) ?? []).length, 1);
    assert.equal(git('config', '--local', 'core.longpaths'), 'false', 'the runner must not mutate repository configuration');
  });
});


test('CLI: candidate and historical runners isolate profiles while retaining Gradle caches', async () => {
  await fixture({ profile: true }, ({ result, output, scratch }) => {
    assert.equal(result.status, 0, output);
    for (const label of ['candidate', 'before']) {
      const probe = JSON.parse(fs.readFileSync(path.join(scratch, `${label}-profile.json`), 'utf8'));
      const profile = path.join(probe.cwd, 'build', 'test-evidence-profile');
      for (const key of ['HOME', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME',
        'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_STATE_HOME']) {
        assert.ok(probe.env[key].startsWith(profile + path.sep), `${label} ${key} must use its checkout profile`);
      }
      for (const key of ['JUSTSEARCH_HOME', 'JUSTSEARCH_DATA_DIR']) {
        assert.equal(Object.hasOwn(probe.env, key), false, `${label} must remove inherited ${key}`);
        assert.ok(probe.init.includes(`environment.remove("${key}")`));
      }
      assert.equal(probe.env.GRADLE_USER_HOME, path.join(scratch, 'gradle-cache'));
      assert.match(probe.init, /tasks.withType\(org.gradle.api.tasks.testing.Test\).configureEach/);
      assert.match(probe.init, /systemProperty "user.home", home.absolutePath/);
      for (const key of ['justsearch.data.dir', 'justsearch.home']) {
        assert.ok(probe.init.includes(`systemProperties.remove("${key}")`));
      }
      assert.doesNotMatch(probe.init, /systemProperty\s+["']justsearch\.(?:data\.dir|home)["']/);
    }
  });
});

for (const mixed of [false, true]) {
  test(`CLI: candidate-only ${mixed ? 'mixed results exit 1' : 'passing checks exit 0'} with valid JSON`, async () => {
    await fixture({ candidate: true, mixed }, ({ result, output, out }) => {
      assert.equal(result.status, mixed ? 1 : 0, output);
      const evidence = readEvidence(out);
      assert.equal(evidence.failBefore, null);
      assert.deepEqual(evidence.checks.map(c => c.outcome), mixed ? ['passed', 'not-found', 'skipped'] : ['passed']);
      assert.deepEqual(evidence.checks.map(c => c.executed), mixed ? [true, false, false] : [true]);
    });
  });
}
