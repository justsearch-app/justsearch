import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { pythonPath } = require('./python-path.cjs');
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');

test('names both packages of the given checkout, jseval first', () => {
  const other = path.resolve('/elsewhere/checkout');
  assert.deepEqual(pythonPath(other).split(path.delimiter),
    [path.join(other, 'scripts/jseval'), path.join(other, 'scripts/jsharness')]);
});

test('CLI and sourced shell function print the same value as the function', () => {
  assert.equal(execFileSync(process.execPath, [path.join(here, 'python-path.cjs'), root], { encoding: 'utf8' }).trim(),
    pythonPath(root));
  if (process.platform === 'win32') return;
  const shell = execFileSync('bash', ['-c', '. "$1/scripts/lib/python-path.sh" && js_pythonpath "$1"', 'bash', root],
    { encoding: 'utf8' }).trim();
  assert.equal(shell, pythonPath(root));
});

test('the value resolves both packages to this checkout', () => {
  const out = execFileSync('python3', ['-I', '-c', 'import sys; sys.path[:0] = sys.argv[1].split(__import__("os").pathsep); '
    + 'from importlib.util import find_spec as f; print(f("jseval").origin); print(f("jsharness").origin)', pythonPath(root)],
  { cwd: '/', encoding: 'utf8' }).trim().split('\n');
  assert.deepEqual(out, [path.join(root, 'scripts/jseval/jseval/__init__.py'),
    path.join(root, 'scripts/jsharness/jsharness/__init__.py')]);
});
