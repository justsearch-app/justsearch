import './lib/test-fixture-processes.cjs';
// Fixed staging lists must be sufficient to load the production reaper they copy.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
const repo = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'reaper-staging-'));
let failed = 0;
try {
  for (const file of ['952-worktree-lifecycle-cli.test.mjs', '936-remove-worktree-cli.test.mjs', '861-w5-remove-worktree-teardown.test.mjs']) {
    try {
      const source = fs.readFileSync(path.join(repo, 'scripts/agent-analytics', file), 'utf8');
      const literal = source.match(/const CLI_FILES = (\[[\s\S]*?\]);/)?.[1];
      assert.ok(literal, 'fixed staging list must be found');
      const files = vm.runInNewContext(literal);
      const fixture = path.join(root, file);
      for (const rel of files) {
        fs.mkdirSync(path.dirname(path.join(fixture, rel)), { recursive: true });
        fs.copyFileSync(path.join(repo, rel), path.join(fixture, rel));
      }
      const staged = path.join(fixture, 'scripts/dev/lib/agent-spawn-reaper.cjs');
      assert.doesNotThrow(() => createRequire(staged)(staged), 'the copied production reaper must load all its dependencies');
      assert.ok(files.includes('scripts/dev/lib/terminate-process.ps1'), 'the staged terminator must exist when called');
      assert.ok(files.includes('scripts/dev/lib/inspect-process-trees.ps1'), 'the batch census helper must be staged with its library');
      console.log(`PASS ${file} fixed dependency list`);
    } catch (error) { failed++; console.error(`FAIL ${file}: ${error.stack}`); }
  }
} finally {
  // Plain copied source files only; this fixture never creates links or starts processes.
  fs.rmSync(root, { recursive: true, force: true });
}
process.exitCode = failed ? 1 : 0;
