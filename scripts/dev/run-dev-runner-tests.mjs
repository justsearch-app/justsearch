#!/usr/bin/env node
/**
 * Runs every `scripts/dev/test-dev-runner-*.mjs` and aggregates the results.
 *
 * WHY THIS EXISTS (lane F stage B item B9, and it is the same failure mode tempdoc 745 D6 named for
 * `scripts/agent-analytics/run-all-tests.mjs`): these files are standalone `node <file>` scripts that
 * exit non-zero on failure, with no test framework and so no `node --test` entry point. Before this
 * runner, `package.json`'s `test:dev-runner` named TWO of them by hand and was invoked from exactly
 * one workflow — `onramp-smoke.yml`, whose trigger is `workflow_dispatch` only. So the death
 * observability suite, the admission suite, the pruning suite and the rest ran in CI NOWHERE:
 * manual-only, i.e. only when someone remembered. A layer nothing invokes is dead regardless of its
 * quality, and the supervisor item B8 landed is exactly the kind of code that rots quietly.
 *
 * DISCOVERY IS DELIBERATE, NOT A CONVENIENCE. A hardcoded list rots the same way the two-file script
 * did: the next agent adds `test-dev-runner-foo.mjs`, forgets the list, and the test silently never
 * runs. Globbing means a new test file is in CI the moment it lands, with nothing to remember.
 *
 * Usage: node scripts/dev/run-dev-runner-tests.mjs [--verbose]
 * Exit code: 0 iff every discovered test file exited 0.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const VERBOSE = process.argv.includes('--verbose');

/**
 * A floor, not a count. Pinning the exact number would make every new test file a two-file change
 * and tempt the next agent to lower it; a floor only ever fails when discovery BREAKS, which is the
 * failure this runner would otherwise report as a green.
 */
const MIN_EXPECTED_FILES = 8;

const files = fs
  .readdirSync(HERE, { withFileTypes: true })
  .filter((entry) => entry.isFile() && /^test-dev-runner-.*\.mjs$/.test(entry.name))
  .map((entry) => path.join(HERE, entry.name))
  .sort();

if (files.length < MIN_EXPECTED_FILES) {
  console.error(
    `run-dev-runner-tests: discovered ${files.length} test files (< ${MIN_EXPECTED_FILES}) — `
    + 'that is a broken glob, not a pass.',
  );
  process.exit(1);
}

let passed = 0;
const failures = [];

for (const file of files) {
  const rel = path.relative(process.cwd(), file);
  const started = Date.now();
  const res = spawnSync(process.execPath, [file], { encoding: 'utf8' });
  const tookMs = Date.now() - started;
  if (res.status === 0) {
    passed++;
    if (VERBOSE) console.log(`PASS  ${rel} (${tookMs}ms)`);
  } else {
    failures.push({ rel, res });
    console.log(`FAIL  ${rel} (${tookMs}ms)`);
    const output = `${res.stdout || ''}${res.stderr || ''}`.trimEnd();
    if (output) console.log(output.split('\n').map((l) => `      ${l}`).join('\n'));
    if (res.status === null) console.log(`      (killed by signal ${res.signal})`);
  }
}

console.log(`\ndev-runner: ${passed}/${files.length} test files passed`);
if (failures.length) {
  console.log(`failed: ${failures.map((f) => f.rel).join(', ')}`);
  process.exit(1);
}
