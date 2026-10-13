#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const devRunnerModule = require(path.join(__dirname, 'dev-runner.cjs'));
const { pruneHistoricRuns } = devRunnerModule.__test;

function writeRun(runDir, startedAt, mtimeMs) {
  fs.mkdirSync(runDir, { recursive: true });
  fs.writeFileSync(path.join(runDir, 'run.json'), `${JSON.stringify({
    startedAt,
    updatedAt: startedAt,
  }, null, 2)}\n`, 'utf8');
  const atime = new Date(mtimeMs);
  const mtime = new Date(mtimeMs);
  fs.utimesSync(runDir, atime, mtime);
  fs.utimesSync(path.join(runDir, 'run.json'), atime, mtime);
}

async function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-prune-'));
  try {
    const runsRoot = path.join(tempRoot, 'tmp', 'dev-runner', 'runs');
    fs.mkdirSync(runsRoot, { recursive: true });
    const now = Date.now();

    for (let i = 0; i < 230; i += 1) {
      const runId = `run-${String(i).padStart(3, '0')}`;
      const ageDays = i < 5 ? 2 : 40 + i;
      const tsMs = now - (ageDays * 24 * 60 * 60 * 1000);
      writeRun(path.join(runsRoot, runId), new Date(tsMs).toISOString(), tsMs);
    }

    const result = await pruneHistoricRuns({
      preserveRunIds: ['run-229'],
      retentionMs: 14 * 24 * 60 * 60 * 1000,
      keepLatestCount: 200,
      runsDirectory: runsRoot,
    });

    const remaining = fs.readdirSync(runsRoot).sort();
    assert.equal(remaining.length, 201);
    assert.ok(remaining.includes('run-229'), 'explicitly preserved run should remain');
    assert.ok(!remaining.includes('run-205'), 'old run outside newest-200 set should be pruned');
    assert.ok(remaining.includes('run-000'), 'recent run should remain');
    assert.ok(result.deleted > 0);
    console.log('test-dev-runner-pruning: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

await main();
