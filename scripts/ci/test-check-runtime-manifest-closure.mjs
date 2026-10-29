#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'runtime-manifest-closure-'));
try {
  const checker = 'scripts/ci/check-runtime-manifest-closure.mjs';
  fs.mkdirSync(path.join(fixture, 'scripts/ci'), { recursive: true });
  fs.copyFileSync(new URL('./check-runtime-manifest-closure.mjs', import.meta.url),
    path.join(fixture, checker));
  function git(...args) {
    const result = spawnSync('git', args, { cwd: fixture, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  }
  function run() {
    const result = spawnSync(process.execPath, [checker], { cwd: fixture, encoding: 'utf8' });
    assert.ifError(result.error);
    return result;
  }
  git('init', '--quiet');
  fs.writeFileSync(path.join(fixture, 'scripts/ci/allowed.cjs'),
    'const file = path.join(dataDir, "runtime", "manifest.json");\n');
  fs.writeFileSync(path.join(fixture, 'scripts/ci/harness.cjs'),
    'const file = path.join(dataDir, "runtime", "index-start-initial-reached.json");\n');
  git('add', '.');
  const allowed = run();
  assert.equal(allowed.status, 0, allowed.stderr);
  assert.match(allowed.stdout, /2 files scanned, 0 violations/);

  fs.writeFileSync(path.join(fixture, 'scripts/ci/violating.cjs'),
    'const file = path.join(dataDir, "runtime", "unsanctioned.json");\n');
  git('add', '.');
  const violating = run();
  assert.equal(violating.status, 1, violating.stdout + violating.stderr);
  assert.match(violating.stderr, /FAILED — 1 closure-rule violations/);
  assert.match(violating.stderr,
    /scripts\/ci\/violating\.cjs:1 \[sibling-file\] runtime\/unsanctioned\.json/);

  fs.unlinkSync(path.join(fixture, 'scripts/ci/violating.cjs'));
  git('add', '-u');
  const restored = run();
  assert.equal(restored.status, 0, restored.stderr);
  assert.match(restored.stdout, /2 files scanned, 0 violations/);
  console.log('test-check-runtime-manifest-closure: PASS (allowed, violating, restored)');
} finally {
  fs.rmSync(fixture, { recursive: true, force: true });
}
