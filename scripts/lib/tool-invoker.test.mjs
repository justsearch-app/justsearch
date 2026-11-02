import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { test } from 'node:test';
const require = createRequire(import.meta.url);
const { toolInvocation, runToolSync, resolveNpmInvocation, quoteCmdArgument, windowsBatchInvocation, acquireHeavy } = require('./tool-invoker.cjs');
const { spawnSync } = require('node:child_process');
// Gradle runs through machine admission on Linux: never the machine-wide directory in tests.
const privateRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'invoker-admission-'));
process.env.JS_ADMISSION_DIR = path.join(privateRoot, 'admission');
process.on('exit', () => fs.rmSync(privateRoot, { recursive: true, force: true }));
const ADMISSION = path.resolve(import.meta.dirname, '../dev/admission.py');
const admissionStatus = () => JSON.parse(spawnSync('python3', ['-I', ADMISSION, 'status'], { encoding: 'utf8' }).stdout);
const linux = { skip: process.platform !== 'linux' && 'Linux machine admission' };

function fakeGradle(body) {
  const root = fs.mkdtempSync(path.join(privateRoot, 'gradle-'));
  fs.writeFileSync(path.join(root, 'gradlew'), `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  return root;
}

test('Gradle invocation preserves argv, cwd, spaces, Unicode, metacharacters and exit status', () => {
  const root = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'invoker space ü & ')));
  try {
    const args = ['-Pdata=a b;$(touch PWNED) ;&`literal`', 'x%PATH%!^', 'double"quote', '', 'tail\\'];
    const recorder = path.join(root, 'record.cjs');
    fs.writeFileSync(recorder, 'console.log(JSON.stringify({args:process.argv.slice(2),cwd:process.cwd()}));process.exit(17);');
    if (process.platform === 'win32') {
      fs.writeFileSync(path.join(root, 'gradlew.bat'), `@echo off\r\n"%JUSTSEARCH_TEST_NODE%" "%~dp0record.cjs" %*\r\nexit /b %ERRORLEVEL%\r\n`);
    } else {
      fs.writeFileSync(path.join(root, 'gradlew'), `#!/bin/sh\nexec '${process.execPath.replaceAll("'", "'\\''")}' '${recorder.replaceAll("'", "'\\''")}' "$@"\n`, { mode: 0o755 });
    }
    const result = runToolSync('gradle', args, { repoRoot: root, cwd: root, encoding: 'utf8', env: { ...process.env, JUSTSEARCH_TEST_NODE: process.execPath } });
    assert.equal(result.status, 17, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), { args, cwd: root });
    assert.equal(fs.existsSync(path.join(root, 'PWNED')), false, 'metacharacters stay arguments');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('npm resolves to Node and its CLI, without batch interpretation', () => {
  const cli = import.meta.filename;
  const npm = resolveNpmInvocation({ platform: 'win32', execPath: process.execPath, env: { npm_execpath: cli } });
  assert.deepEqual(npm, { command: process.execPath, prefixArgs: [cli] });
  assert.deepEqual(toolInvocation('npm', ['ci', 'literal&'], { platform: 'win32', env: { npm_execpath: cli } }).args,
    [cli, 'ci', 'literal&']);
});

test('missing command fails instead of succeeding with a null exit', () => {
  const result = runToolSync('gradle', [], { repoRoot: path.join(os.tmpdir(), 'absent-invoker-root'), encoding: 'utf8' });
  assert.ok(result.error || result.status !== 0);
});

test('a missing Gradle wrapper keeps its own ENOENT error', linux, () => {
  const result = runToolSync('gradle', [], { repoRoot: path.join(os.tmpdir(), 'absent-invoker-root'), encoding: 'utf8' });
  assert.equal(result.error?.code, 'ENOENT', `status ${result.status}: ${result.stderr}`);
});

test('Linux Gradle runs as heavy machine admission, shown in status', linux, () => {
  const root = fakeGradle(`exec python3 -I '${ADMISSION}' status`);
  const result = runToolSync('gradle', ['help'], { repoRoot: root, cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const rows = JSON.parse(result.stdout);
  assert.deepEqual(rows.map(row => [row.mode, row.purpose]), [['heavy', 'Gradle']]);
  assert.deepEqual(admissionStatus(), [], 'released after Gradle exits');
});

test('without python3 Gradle runs without admission and one warning', linux, () => {
  const root = fakeGradle('echo ran; exit 17');
  const emptyPath = fs.mkdtempSync(path.join(privateRoot, 'no-python-'));
  const code = `const r=require(${JSON.stringify(path.join(import.meta.dirname, 'tool-invoker.cjs'))}).runToolSync('gradle',[],`
    + `{repoRoot:${JSON.stringify(root)},encoding:'utf8',env:{...process.env,PATH:${JSON.stringify(emptyPath)}}});`
    + `process.stdout.write(JSON.stringify({status:r.status,error:r.error?.code??null,stdout:r.stdout}))`;
  const result = spawnSync(process.execPath, ['-e', code], { encoding: 'utf8' });
  assert.deepEqual(JSON.parse(result.stdout), { status: 17, error: null, stdout: 'ran\n' }, result.stderr);
  assert.equal(result.stderr.trim().split('\n').length, 1, result.stderr);
  assert.match(result.stderr, /python3 not found.*without admission/);
});

test('an unusable admission directory runs Gradle without admission and one warning', linux, () => {
  if (process.getuid() === 0) return;
  const root = fakeGradle('echo ran; exit 17');
  const blocked = fs.mkdtempSync(path.join(privateRoot, 'blocked-'));
  fs.chmodSync(blocked, 0o555);
  try {
    const result = runToolSync('gradle', [], { repoRoot: root, encoding: 'utf8',
      env: { ...process.env, JS_ADMISSION_DIR: path.join(blocked, 'admission') } });
    assert.equal(result.status, 17, result.stderr);
    assert.equal(result.stdout, 'ran\n');
    assert.equal(result.stderr.trim().split('\n').length, 1, result.stderr);
    assert.match(result.stderr, /without admission/);
  } finally { fs.chmodSync(blocked, 0o755); }
});

test('releasing heavy admission never throws, even after its holder died', linux, async () => {
  const release = await acquireHeavy('fixture start');
  const [row] = admissionStatus();
  assert.equal(row?.purpose, 'fixture start');
  assert.ok(process.env.JS_ADMISSION_TOKEN);
  // The holder is this process's own child (spawned by acquireHeavy).
  const parent = Number(fs.readFileSync(`/proc/${row.pid}/stat`, 'utf8').split(') ')[1].split(' ')[1]);
  assert.equal(parent, process.pid);
  process.kill(row.pid, 'SIGTERM');
  await release();
  assert.equal(process.env.JS_ADMISSION_TOKEN, undefined);
  assert.deepEqual(admissionStatus(), []);
});


test('bootstrap invoker loads with only Node built-ins, before npm ci', () => {
  const source = fs.readFileSync(new URL('./tool-invoker.cjs', import.meta.url), 'utf8');
  for (const [, dependency] of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
    assert.ok(dependency.startsWith('node:'), dependency);
  }
});

test('cmd.exe quoting protects hostile arguments and CRT quote/backslash boundaries', () => {
  const pairs = [
    ['', '^"^"'], ['hello world', '^"hello^ world^"'],
    ['$(touch PWNED) ;&', '^"$^(touch^ PWNED^)^ ^;^&^"'],
    ['%PATH%!^|<>', '^"^%PATH^%^!^^^|^<^>^"'],
    ['double"quote', '^"double\\^"quote^"'],
    ['tail\\', '^"tail\\\\^"'],
  ];
  for (const [raw, expected] of pairs) assert.equal(quoteCmdArgument(raw), expected, JSON.stringify(raw));
  const invocation = windowsBatchInvocation('C:\\space &\\gradlew.bat', pairs.map(([raw]) => raw), { ComSpec: 'C:\\Windows\\cmd.exe' });
  assert.equal(invocation.command, 'C:\\Windows\\cmd.exe');
  assert.deepEqual(invocation.args.slice(0, 4), ['/d', '/v:off', '/s', '/c']);
  assert.equal(invocation.args[4], '"C:\\space^ ^&\\gradlew.bat ' + pairs.map(([, expected]) => expected).join(' ') + '"');
  assert.equal(invocation.shell, false);
  assert.equal(invocation.windowsVerbatimArguments, true);
  for (const raw of ['a\nb', 'a\rb', 'a\0b']) assert.throws(() => quoteCmdArgument(raw), /NUL or line breaks/);
});
