import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const repoRoot = fileURLToPath(new URL('../../../../', import.meta.url));
const script = fileURLToPath(new URL('../../../ci/check-atom-fork-ratchet.mjs', import.meta.url));
const baselinePath = 'scripts/ci/atom-fork-ratchet-baseline.v1.json';
const file = 'shell-v0/standalone-regression.ts';

function fixture(fn) {
  const scratch = resolve(repoRoot, 'tmp');
  mkdirSync(scratch, { recursive: true });
  const root = mkdtempSync(join(scratch, 'atom-cli-'));
  const write = (path, body) => {
    const target = join(root, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, body);
  };
  // Use the real repository only for read-only history/changeset discovery. The
  // executable scans this scratch work tree; no git mutations or commits occur.
  try {
    const gitDir = execFileSync('git', ['rev-parse', '--absolute-git-dir'], {
      cwd: repoRoot, encoding: 'utf8',
    }).trim();
    const run = (...args) => spawnSync(process.execPath, [script, ...args], {
      cwd: root, encoding: 'utf8',
      env: { ...process.env, GIT_DIR: gitDir, GIT_WORK_TREE: root },
    });
    mkdirSync(join(root, 'modules/ui-web/src/shell-v0'), { recursive: true });
    return fn({ root, write, run });
  } finally {
    assert.ok(root.startsWith(scratch + sep), 'cleanup must stay under workspace tmp');
    rmSync(root, { recursive: true, force: true });
  }
}

test('standalone CLI rejects simultaneous source growth and a newly added pin', () => fixture(({ write, run }) => {
  write('modules/ui-web/src/' + file, '.badge { color: red; }');
  write(baselinePath, JSON.stringify({ [file]: 1 }));
  const result = run();
  assert.equal(result.error, undefined);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /baseline pin increased from 0 to 1 without a declared growth changeset/);
}));

test('standalone CLI accepts declared and repinned growth', () => fixture(({ write, run }) => {
  write('modules/ui-web/src/' + file, '.badge { color: red; }');
  write(baselinePath, JSON.stringify({ [file]: 1 }));
  write('gates/atom-fork-ratchet/.changesets/standalone-growth.md',
    '---\nclassification: declared-growth\ntempdoc: 936\n---\n');
  const result = run();
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stdout + result.stderr);
}));

test('standalone CLI still rejects declared overflow without repinning', () => fixture(({ write, run }) => {
  write('modules/ui-web/src/' + file, '.badge { color: red; }');
  write(baselinePath, '{}');
  write('gates/atom-fork-ratchet/.changesets/standalone-growth.md',
    '---\nclassification: declared-growth\ntempdoc: 936\n---\n');
  const result = run();
  assert.equal(result.error, undefined);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /declared-growth-without-repin/);
}));

test('standalone CLI accepts a clean shrinking baseline', () => fixture(({ write, run }) => {
  write(baselinePath, '{}');
  const result = run();
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stdout + result.stderr);
}));
