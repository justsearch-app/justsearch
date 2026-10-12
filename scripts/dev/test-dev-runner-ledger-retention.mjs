import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import ledger from './lib/exit-ledger.cjs';
import census from './lib/stop-exit-census.cjs';

const stamp = '2026-10-08T10:00:00Z';
const birth = census.fileTimeFromInstant(stamp);
test('five incarnations retain every witness across the per-file size bound', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'retained-ledger-'));
  try {
    for (let n = 1; n <= 5; n++) {
      const writer = ledger.createLedgerWriter({ ledgerDir: dir, family: 'children',
        writer: { role: 'engine', pid: 100 + n, startedAt: Date.parse(stamp) }, rotates: true,
        sessionRotateBytes: 400, hardCapBytes: 900 });
      writer.startSession();
      for (let i = 0; i < 8; i++) assert.equal(writer.append('job-process-start', { witnessId: `j-${n}-${i}`,
        pid: n * 1000 + i, creationFileTime: birth, incarnation: { session: 'retention', n },
        executable: 'C:\\test\\tesseract.exe', contained: true }), true);
    }
    const records = ledger.readLedger(dir, 'children').records;
    assert.equal(records.filter(r => r.type === 'job-process-start').length, 40,
      'earlier incarnations must survive rotation until the run report');
    assert.ok(fs.statSync(ledger.currentPath(dir, 'children')).size <= 900);
  } finally { fs.rmSync(dir, { recursive: true }); }
});
test('at the retention bound dropped identities have durable evidence-lost records and a distinct census class', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lost-ledger-'));
  try {
    const writer = ledger.createLedgerWriter({ ledgerDir: dir, family: 'children',
      writer: { role: 'engine', pid: 100, startedAt: Date.parse(stamp) }, rotates: true,
      sessionRotateBytes: 400, hardCapBytes: 900, retentionBytes: 1800 });
    for (let i = 0; i < 30; i++) assert.equal(writer.append('job-process-start', {
      witnessId: `j-${i}`, pid: 200 + i, creationFileTime: birth, executable: 'C:\\test\\tesseract.exe',
      contained: true, incarnation: { session: 'retention', n: 1 } }), true);
    const records = ledger.readLedger(dir, 'children').records;
    assert.ok(records.some(r => r.type === 'ledger-gap' && r.line.reason === 'EVIDENCE_LOST' && r.line.pid === 200));
    const input = census.exitTargets({ runId: 'retention', pids: {} }, null,
      [{ pid: 200, creationFileTimeUtc: birth, executable: null }], {
        engine: { present: true, records: [] }, children: { present: true, records } });
    assert.equal(input.evidenceLost?.[0]?.evidenceClass, 'evidence-lost');
    assert.ok(input.gaps.some(g => g.pid === 200 && g.reason === 'evidence_lost'));
    assert.equal(input.accountedProcesses.length, 0, 'lost evidence never credits ownership');
    const archiveBytes = fs.readdirSync(dir).filter(f => /^children.v1.\d+.jsonl$/.test(f))
      .reduce((n,f) => n + fs.statSync(path.join(dir, f)).size, 0);
    assert.ok(archiveBytes <= 1800);
  } finally { fs.rmSync(dir, { recursive: true }); }
});
