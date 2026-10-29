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

  // Exercise selection through the real git-backed scanner, not a copy of its glob translator.
  const selectionCases = [
    ['modules/example/src/main/java/io/justsearch/Example.java', true],
    ['modules/example/src/main/kotlin/io/justsearch/Example.kt', true],
    ['modules/ui-web/src/api/example.ts', true],
    ['modules/ui-web/src/components/example.tsx', true],
    ['modules/ui-web/src/api/example.js', true],
    ['modules/ui-web/src/api/example.mjs', true],
    ['modules/shell/src-tauri/src/example.rs', true],
    ['modules/shell/src-tauri/src/bin/example.rs', true],
    ['scripts/dev/example.mjs', true],
    ['scripts/dev/example.cjs', true],
    ['scripts/dev/example.js', true],
    ['docs/example.mjs', false],
    ['docs/example.cjs', false],
    ['docs/example.js', false],
    ['docs/example.ts', false],
    ['docs/tempdocs/example.mjs', false],
    ['modules/example/src/test/java/io/justsearch/ExampleTest.java', false],
    ['modules/example/src/test/kotlin/io/justsearch/ExampleTest.kt', false],
    ['modules/ui-web/tests/example.ts', false],
    ['scripts/dev/example.ts', false],
    ['modules/ui-web/src/api/example.ts.bak', false],
    ['scripts/dev/example.mjs.bak', false],
    ['scripts/lib/platform-paths.mjs', false],
    ['modules/ui/src/main/java/io/justsearch/ui/runtime/RuntimeManifestPublisher.java', false],
  ];
  const selectionFailures = [];
  for (const [file, selected] of selectionCases) {
    const absolute = path.join(fixture, file);
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    fs.writeFileSync(absolute,
      'const file = path.join(dataDir, "runtime", "unsanctioned.json");\n');
    git('add', '.');
    const result = run();
    try {
      assert.equal(result.status, selected ? 1 : 0, `${file}: ${result.stdout}${result.stderr}`);
      if (selected) {
        assert.match(result.stderr, /FAILED — 1 closure-rule violations/);
        assert.ok(result.stderr.includes(`${file}:1 [sibling-file] runtime/unsanctioned.json`));
      } else {
        assert.match(result.stdout, /2 files scanned, 0 violations/);
      }
    } catch (error) {
      selectionFailures.push(error.message);
    }
    fs.unlinkSync(absolute);
    git('add', '-u');
  }
  assert.deepEqual(selectionFailures, [], 'source selection must follow the stated globs and skips');

  // Known false positives: these must produce findings today, not clean scans.
  // A later narrowing of the heuristic must deliberately update these expectations.
  const knownFalsePositives = [
    ['comment', '// Example: path.join(dataDir, "runtime", "retired.json")\n', 'retired.json'],
    ['non-dataDir base', 'path.join(outputDir, "runtime", "report.json");\n', 'report.json'],
  ];
  for (const [reason, content, artifact] of knownFalsePositives) {
    const file = 'scripts/ci/known-false-positive.mjs';
    fs.writeFileSync(path.join(fixture, file), content);
    git('add', '.');
    const result = run();
    assert.equal(result.status, 1, `${reason}: ${result.stdout}${result.stderr}`);
    assert.match(result.stderr, /FAILED — 1 closure-rule violations/);
    assert.ok(result.stderr.includes(`${file}:1 [sibling-file] runtime/${artifact}`));
    console.log(`Known false positive observed: ${reason} (runtime/${artifact})`);
    fs.unlinkSync(path.join(fixture, file));
    git('add', '-u');
  }
  console.log('test-check-runtime-manifest-closure: PASS (allowed, violating, restored, selection; 2 known false positives observed)');
} finally {
  fs.rmSync(fixture, { recursive: true, force: true });
}
