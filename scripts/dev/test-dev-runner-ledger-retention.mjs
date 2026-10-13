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

// One retained engine row, a loss journal and a 100-byte bound: rotation must evict the archive.
function journalEviction(journalLines) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loss-journal-'));
  const current = ledger.currentPath(dir, 'engine');
  const rotated = path.join(dir, 'engine.v1.1.jsonl'), archive = path.join(dir, 'engine.v1.2.jsonl');
  const lossFile = path.join(dir, 'engine.v1.lost.jsonl');
  const row = JSON.stringify({ schemaVersion: 1, type: 'engine-start', seq: 1, at: stamp,
    writer: { role: 'shell', pid: 100, startedAt: stamp }, incarnation: { session: 'old', n: 1 },
    pid: 200, creationFileTime: birth });
  fs.writeFileSync(archive, `${row}\n`);
  fs.writeFileSync(current, `${row}\n`);
  fs.writeFileSync(lossFile, journalLines.map(line => `${line}\n`).join(''));
  let failure = null;
  try { ledger.retainAndRename(current, rotated, 100); } catch (error) { failure = error; }
  const lines = fs.readFileSync(lossFile, 'utf8').split('\n').filter(Boolean);
  return { dir, archive, failure, lines };
}
const earlierLoss = JSON.stringify({ schemaVersion: 1, type: 'ledger-gap', seq: 1, at: '2026-10-07T10:00:00Z',
  writer: { role: 'engine', pid: 90, startedAt: '2026-10-07T10:00:00Z' }, reason: 'EVIDENCE_LOST',
  count: 1, since: '2026-10-07T10:00:00Z', droppedFile: 'engine.v1.3.jsonl', droppedBytes: 10 });
const cutOff = '{"schemaVersion":1,"type":"ledger-gap","detail":"cut';
const summaryOf = lines => lines.map(line => { try { return JSON.parse(line); } catch { return null; } })
  .filter(r => r?.detail === 'archive dropped at retention bound');

test('a cut-off loss-journal line is counted and kept verbatim while eviction proceeds', () => {
  const { dir, archive, failure, lines } = journalEviction([earlierLoss, cutOff]);
  try {
    assert.equal(fs.existsSync(archive), false, `the archive above the bound is evicted (${failure?.message})`);
    assert.equal(failure, null);
    assert.deepEqual(lines.filter(line => line === cutOff), [cutOff], 'the unreadable journal line is kept as it was');
    assert.ok(lines.includes(earlierLoss), 'earlier loss records survive the rewrite');
    const [summary] = summaryOf(lines);
    assert.equal(summary?.corruptLines, 1, 'the unreadable journal line is counted as corrupt');
  } finally { fs.rmSync(dir, { recursive: true }); }
});

test('a clean loss journal keeps its records and gains the eviction summary', () => {
  const { dir, archive, failure, lines } = journalEviction([earlierLoss]);
  try {
    assert.equal(failure, null);
    assert.equal(fs.existsSync(archive), false, 'the archive above the bound is evicted');
    assert.equal(lines[0], earlierLoss, 'the earlier loss record stays first and unchanged');
    assert.equal(summaryOf(lines).length, 1, 'one summary for the eviction');
    assert.ok(lines.some(line => JSON.parse(line).pid === 200), 'the evicted identity has a loss receipt');
  } finally { fs.rmSync(dir, { recursive: true }); }
});

test('loss-journal compaction keeps an unreadable line verbatim', () => {
  const padded = JSON.stringify({ ...JSON.parse(earlierLoss), detail: 'x'.repeat(300) });
  const { dir, archive, failure, lines } = journalEviction([...Array(2000).fill(padded), cutOff]);
  try {
    assert.equal(fs.existsSync(archive), false, `the archive above the bound is evicted (${failure?.message})`);
    assert.equal(lines.filter(line => line !== cutOff).length, 1, 'the readable journal compacted to one row');
    assert.deepEqual(lines.filter(line => line === cutOff), [cutOff], 'compaction keeps the unreadable line');
  } finally { fs.rmSync(dir, { recursive: true }); }
});
