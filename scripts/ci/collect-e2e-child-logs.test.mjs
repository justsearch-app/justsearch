import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { collectChildLogs } from './collect-e2e-child-logs.mjs';

test('failure collection retains Engine, supervisor and fixture output with bounded tails', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-logs-'));
  try {
    const write = (relative, data) => {
      const target = path.join(root, relative);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, data);
    };
    const fixture = 'tmp/lane-f-takeover/writer-junit-case';
    write(`${fixture}/data/logs/engine.log`, 'Engine and embedded worker output');
    write(`${fixture}/state/runs/run/logs/supervisor.log`, 'supervisor output');
    write(`${fixture}/state/runs/run/logs/backend.stderr.log`, 'child stderr');
    write(`${fixture}/fixture-output.txt`, 'fixture output');
    write(`${fixture}/data/jobs.db`, 'never upload a database');
    write(`${fixture}/content/source.log`, 'corpus content is not child output');
    write(`${fixture}/models/private.log`, 'never traverse models');
    write('modules/system-tests/build/reports/isolated-backend/case/backend.log', 'boot failure');
    write(`${fixture}/large-output.txt`, 'discard'.repeat(100_000) + 'LAST LINE');
    for (let i = 0; i < 210; i++) write(`${fixture}/zz-${i}.log`, Buffer.alloc(300_000, 'z'));
    // Links, including directory junctions on Windows, must not expose other trees.
    fs.symlinkSync(path.join(root, fixture, 'models'), path.join(root, fixture, 'linked'),
      process.platform === 'win32' ? 'junction' : 'dir');
    const destination = path.join(root, 'collected');
    const result = collectChildLogs(root, destination);
    assert.deepEqual(result.errors, []);
    assert.equal(result.scanLimitReached, false);
    assert.ok(result.omitted > 0);
    assert.ok(result.files.length <= result.maxFiles);
    assert.ok(result.files.reduce((n, f) => n + f.copiedBytes, 0) <= result.totalBytes);
    assert.ok(result.files.every((f) => f.copiedBytes <= result.fileBytes));
    for (const name of ['data/logs/engine.log', 'state/runs/run/logs/supervisor.log',
      'state/runs/run/logs/backend.stderr.log', 'fixture-output.txt']) {
      assert.ok(fs.existsSync(path.join(destination, fixture, name)), name);
    }
    const tail = fs.readFileSync(path.join(destination, fixture, 'large-output.txt'), 'utf8');
    assert.ok(tail.endsWith('LAST LINE'));
    assert.equal(tail.length, result.fileBytes);
    assert.ok(result.files.every((f) => !/models|linked|jobs.db|content/.test(f.path)));
    assert.ok(fs.existsSync(path.join(destination, 'manifest.json')));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('an early fixture failure still produces an explicit empty manifest', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-empty-'));
  try {
    assert.deepEqual(collectChildLogs(root, path.join(root, 'out')).files, []);
    assert.ok(fs.existsSync(path.join(root, 'out/manifest.json')));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
