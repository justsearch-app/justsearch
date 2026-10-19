/**
 * The test-intent detection corpus (tempdoc 966 D1, S14), run end to end: every case builds a real
 * scratch git repo and runs the real analysis. Discovered by scripts/governance/run-all-tests.mjs.
 *
 * Cases run in parallel child processes (each is a handful of git commands; serially the corpus is
 * slow on Windows). `node corpus.test.mjs --case <index>` runs one case in-process.
 *
 * Run with: `node scripts/governance/gates/test-intent/corpus.test.mjs`
 */

import { spawn } from 'node:child_process';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

import { CASES, assertCase } from './corpus.mjs';
import { runScenario } from './scenario.mjs';

const SELF = fileURLToPath(import.meta.url);

if (process.argv[2] === '--case') {
  const c = CASES[Number(process.argv[3])];
  const { result, root, cleanup } = runScenario(c.scenario);
  try {
    assertCase(c, result, root);
  } catch (e) {
    console.error(e.message);
    process.exitCode = 1;
  } finally {
    cleanup();
  }
} else {
  if (CASES.length < 13) throw new Error('corpus shrank below the D1 list');
  const width = Math.max(2, Math.min(8, os.cpus().length));
  const failures = [];
  let next = 0;
  const runOne = (i) => new Promise((resolveRun) => {
    const child = spawn(process.execPath, [SELF, '--case', String(i)], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('close', (code) => {
      if (code !== 0) failures.push(`${CASES[i].name}\n    ${out.trim().split('\n').join('\n    ')}`);
      resolveRun();
    });
  });
  const lane = async () => {
    while (next < CASES.length) await runOne(next++);
  };
  await Promise.all(Array.from({ length: width }, lane));
  if (failures.length > 0) {
    console.error(`test-intent corpus: ${failures.length} FAILED, ${CASES.length - failures.length} passed`);
    for (const f of failures) console.error(`  x ${f}`);
    process.exit(1);
  }
  console.log(`test-intent corpus: all ${CASES.length} cases behave as stated`);
}
