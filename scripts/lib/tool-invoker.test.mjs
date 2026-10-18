import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { test } from 'node:test';
const require = createRequire(import.meta.url);
const { toolInvocation, runToolSync, resolveNpmInvocation, quoteCmdArgument, windowsBatchInvocation } = require('./tool-invoker.cjs');

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
