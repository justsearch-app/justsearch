import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test, { after } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { pythonPath } = require('./python-path.cjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const checkout = mkdtempSync(path.join(tmpdir(), 'python-path-'));
after(() => rmSync(checkout, { recursive: true, force: true }));
for (const name of ['jseval', 'jsharness']) {
  const packageDir = path.join(checkout, 'scripts', name, name);
  mkdirSync(packageDir, { recursive: true });
  writeFileSync(path.join(packageDir, '__init__.py'), '');
}

test('names both packages of the given checkout, jseval first', () => {
  assert.deepEqual(pythonPath(checkout).split(path.delimiter),
    [path.join(checkout, 'scripts', 'jseval'), path.join(checkout, 'scripts', 'jsharness')]);
});

test('CLI and sourced shell function print the same value as the function', () => {
  assert.equal(execFileSync(process.execPath, [path.join(here, 'python-path.cjs'), checkout], { encoding: 'utf8' }).trim(),
    pythonPath(checkout));
  if (process.platform === 'win32') return;
  const shell = execFileSync('bash', ['-c', '. "$1/python-path.sh" && js_pythonpath "$2"', 'bash', here, checkout],
    { encoding: 'utf8' }).trim();
  assert.equal(shell, pythonPath(checkout));
});

test('the value resolves both packages to the given checkout', () => {
  const out = execFileSync('python3', ['-I', '-c', 'import sys; sys.path[:0] = sys.argv[1].split(__import__("os").pathsep); '
    + 'from importlib.util import find_spec as f; print(f("jseval").origin); print(f("jsharness").origin)', pythonPath(checkout)],
  { cwd: '/', encoding: 'utf8' }).trim().split('\n');
  assert.deepEqual(out, [path.join(checkout, 'scripts', 'jseval', 'jseval', '__init__.py'),
    path.join(checkout, 'scripts', 'jsharness', 'jsharness', '__init__.py')]);
});
