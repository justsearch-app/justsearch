import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { test } from 'node:test';
const require = createRequire(import.meta.url);
const { toolInvocation, runToolSync, resolveNpmInvocation } = require('./tool-invoker.cjs');

test('Gradle invocation preserves argv, cwd, spaces, Unicode, metacharacters and exit status', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'invoker space ü & '));
  try {
    const args = ['-Pdata=a b;$(literal)&`literal`', 'x%PATH%!^', 'double"quote', '', 'tail\\'];
    const recorder = path.join(root, 'record.cjs');
    fs.writeFileSync(recorder, 'console.log(JSON.stringify({args:process.argv.slice(2),cwd:process.cwd()}));process.exit(17);');
    if (process.platform === 'win32') {
      fs.writeFileSync(path.join(root, 'gradlew.bat'), `@echo off\r\n"${process.execPath}" "${recorder}" %*\r\nexit /b %ERRORLEVEL%\r\n`);
    } else {
      fs.writeFileSync(path.join(root, 'gradlew'), `#!/bin/sh\nexec '${process.execPath.replaceAll("'", "'\\''")}' '${recorder.replaceAll("'", "'\\''")}' "$@"\n`, { mode: 0o755 });
    }
    const result = runToolSync('gradle', args, { repoRoot: root, cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 17, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), { args, cwd: root });
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
