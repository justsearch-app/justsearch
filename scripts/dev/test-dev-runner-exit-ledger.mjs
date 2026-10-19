#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
/**
 * The Node exit ledger (scripts/dev/lib/exit-ledger.cjs; Spec F step 5, Spec B T11, T12, T15).
 *
 * T15: the shared cases under governance/fixtures/exit-ledger/cases/ summarise exactly as their
 *      expected-summary.json, which the Java reader is held to by ExitLedgerSummaryParityTest.
 * T11: the reader tolerates a torn last line, CRLF, corrupt lines, a newer schema, an unknown type and
 *      the unknown timestamps 0, null, FILETIME 0 (−11644473600000) and 1999; nothing throws.
 * T12: a rotating writer rotates over 1 MiB at session start and before passing the 2 MiB cap, the
 *      older generation is replaced, and a failed rotation keeps appending and records a gap.
 * Writer: one line per append, seq spent on every attempt, a failed write returns false and the gap
 * is written before the next record, a torn tail is closed, caller bugs throw.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const ledger = require(path.join(__dirname, 'lib', 'exit-ledger.cjs'));
const repoRoot = path.resolve(__dirname, '..', '..');

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-exit-ledger-'));
let n = 0;
const freshDir = () => {
  n += 1;
  const dir = path.join(tmp, `case-${n}`, 'runtime', 'exit-ledger');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
};
const WRITER = { role: 'dev-runner', pid: 4242, startedAt: Date.UTC(2026, 9, 6, 9, 0, 0) };
const fixedClock = (ms = Date.UTC(2026, 9, 6, 9, 30, 0)) => () => ms;
const lines = (file) => fs.readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
const envelope = (o) => JSON.stringify({ schemaVersion: 1, seq: 1, at: '2026-10-06T09:00:00Z',
  writer: { role: 'engine', pid: 7, startedAt: '2026-10-06T08:00:00Z' }, ...o });

// --------------------------------------------------------------------------------------------- T15
test('T15: shared writer cases reproduce the canonical bytes', () => {
  const dir = path.join(repoRoot, 'governance/fixtures/exit-ledger/writer-parity');
  const samples = JSON.parse(fs.readFileSync(path.join(dir, 'cases.json'), 'utf8'));
  const actual = [];
  for (const sample of samples) {
    const target = freshDir();
    const w = ledger.createLedgerWriter({ ledgerDir: target, family: sample.family,
      writer: sample.writer, clock: () => sample.atMs });
    assert.equal(w.append(sample.type, sample.fields), true);
    actual.push(fs.readFileSync(ledger.currentPath(target, sample.family)));
  }
  assert.deepEqual(Buffer.concat(actual), fs.readFileSync(path.join(dir, 'expected.jsonl')));
});

test('T15: every shared case summarises exactly as its expected-summary.json', () => {
  const cases = path.join(repoRoot, 'governance', 'fixtures', 'exit-ledger', 'cases');
  const dirs = fs.readdirSync(cases, { withFileTypes: true }).filter((e) => e.isDirectory());
  assert.ok(dirs.length >= 2, 'the shared cases exist');
  for (const d of dirs) {
    const dir = path.join(cases, d.name);
    const expected = JSON.parse(fs.readFileSync(path.join(dir, 'expected-summary.json'), 'utf8'));
    const actual = {
      children: ledger.summarizeLedger(dir, 'children'),
      engine: ledger.summarizeLedger(dir, 'engine'),
    };
    assert.deepEqual(actual, expected, `${d.name} summary`);
  }
});

test('T15: the fixture bytes keep their CRLF line and their torn tail through checkout', () => {
  const file = path.join(repoRoot, 'governance', 'fixtures', 'exit-ledger', 'cases', 'reader-edge', 'engine.v1.jsonl');
  const bytes = fs.readFileSync(file);
  assert.ok(bytes.includes(Buffer.from('}\r\n')), 'a CRLF-terminated record is part of the case');
  assert.notEqual(bytes[bytes.length - 1], 0x0a, 'the case ends in a torn line');
});

test('the top-level shared fixture reads with nothing skipped and the step-V details', () => {
  const dir = path.join(repoRoot, 'governance', 'fixtures', 'exit-ledger');
  const read = ledger.readLedger(dir, 'engine');
  assert.equal(read.skippedLines, 0);
  assert.equal(read.tornTail, false);
  const details = read.records.map((r) => ledger.stopDetailOf(r));
  for (const t of ['session_end', 'takeover', 'interrupted']) assert.ok(details.includes(t), t);
});

// --------------------------------------------------------------------------------------------- T11
test('T11: the 2001 rule — only ISO-8601 UTC Z strings at or after 2001 are known', () => {
  const known = ledger.knownTimestamp;
  assert.equal(known(null), null);
  assert.equal(known(undefined), null);
  assert.equal(known(0), null);
  assert.equal(known(-11644473600000), null, 'FILETIME 0 as epoch ms');
  assert.equal(known(Date.UTC(2026, 0, 1)), null, 'a number is never a timestamp');
  assert.equal(known('1601-01-01T00:00:00Z'), null);
  assert.equal(known('1999-12-31T23:59:59Z'), null);
  assert.equal(known('2000-12-31T23:59:59.999Z'), null);
  assert.equal(known('2001-01-01T00:00:00Z'), Date.UTC(2001, 0, 1));
  assert.equal(known('2026-10-06T09:00:00+00:00'), null, 'an offset is not Z');
  assert.equal(known('2026-10-06 09:00:00Z'), null);
  assert.equal(known('2026-02-30T00:00:00Z'), null);
  assert.equal(known('2026-10-06T09:00Z'), null, 'seconds are required');
  assert.equal(known('2026-10-06T09:00:00.123456789Z'), Date.UTC(2026, 9, 6, 9, 0, 0, 123));
  assert.equal(ledger.formatTimestamp(Date.UTC(2026, 9, 6, 9, 0, 0)), '2026-10-06T09:00:00Z');
  assert.equal(ledger.formatTimestamp(Date.UTC(2026, 9, 6, 9, 0, 0, 5)), '2026-10-06T09:00:00.005Z');
});

test('T11: reader tolerance — torn tail, CRLF, corrupt, newer schema, unknown type, unknown times', () => {
  const dir = freshDir();
  const file = ledger.currentPath(dir, 'engine');
  fs.writeFileSync(file, [
    `${envelope({ type: 'engine-start', at: 0 })}\r\n`,
    `${envelope({ type: 'engine-start', seq: 2, at: null, exitedAt: -11644473600000 })}\n`,
    `${envelope({ type: 'engine-start', seq: 3, at: '1999-01-01T00:00:00Z' })}\n`,
    'garbage\n',
    `${envelope({ schemaVersion: 2, type: 'engine-start' })}\n`,
    `${envelope({ type: 'stop-intent' })}\n`,
    '\n',
    envelope({ type: 'engine-exit', seq: 9 }).slice(0, 40),
  ].join(''));
  let read;
  assert.doesNotThrow(() => { read = ledger.readLedger(dir, 'engine'); });
  assert.equal(read.records.length, 3);
  assert.deepEqual(read.records.map((r) => r.at), [null, null, null]);
  assert.equal(read.corruptLines, 1);
  assert.equal(read.unknownSchemaLines, 1);
  assert.equal(read.unknownTypeLines, 1);
  assert.equal(read.tornTail, true);
  assert.equal(read.skippedLines, 3);
});

test('T11: missing files are an empty ledger; the older generation is read first', () => {
  const dir = freshDir();
  assert.deepEqual(ledger.readLedger(dir, 'children').records, []);
  assert.deepEqual(ledger.readLedger(path.join(dir, 'absent'), 'engine').records, []);
  fs.writeFileSync(ledger.rotatedPath(dir, 'engine'), `${envelope({ type: 'engine-start', seq: 1 })}\n`);
  fs.writeFileSync(ledger.currentPath(dir, 'engine'), `${envelope({ type: 'engine-exit', seq: 2 })}\n`);
  assert.deepEqual(ledger.readLedger(dir, 'engine').records.map((r) => r.type), ['engine-start', 'engine-exit']);
});

test('T11: a line over 8 KiB is corrupt; a non-integral seq or schemaVersion literal is corrupt', () => {
  const dir = freshDir();
  fs.writeFileSync(ledger.currentPath(dir, 'engine'), [
    `${envelope({ type: 'engine-start', pad: 'x'.repeat(ledger.MAX_READ_LINE_BYTES) })}\n`,
    `${envelope({ type: 'engine-start' }).replace('"seq":1', '"seq":1.0')}\n`,
    `${envelope({ type: 'engine-start' }).replace('"schemaVersion":1', '"schemaVersion":1e0')}\n`,
    `${envelope({ type: 'engine-start', seq: 4 })}\n`,
  ].join(''));
  const read = ledger.readLedger(dir, 'engine');
  assert.equal(read.corruptLines, 3);
  assert.deepEqual(read.records.map((r) => r.seq), [4]);
});

test('an unknown detail token reads as unknown with the raw token kept', () => {
  const dir = freshDir();
  fs.writeFileSync(ledger.currentPath(dir, 'engine'),
    `${envelope({ type: 'engine-intent', detail: 'from_the_future' })}\n${envelope({ type: 'engine-intent', seq: 2, detail: 'takeover' })}\n`);
  const [a, b] = ledger.readLedger(dir, 'engine').records;
  assert.equal(ledger.stopDetailOf(a), 'unknown');
  assert.equal(ledger.textField(a, 'detail'), 'from_the_future');
  assert.equal(ledger.stopDetailOf(b), 'takeover');
});

// ------------------------------------------------------------------------------------------ writer
test('writer: one fsynced line per record, seq from 1, the envelope from the writer', () => {
  const dir = freshDir();
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, clock: fixedClock() });
  w.startSession();
  assert.equal(w.append('engine-intent', { incarnation: { session: 'r', n: 1 }, detail: 'interrupted',
    requestedAt: new Date(Date.UTC(2026, 9, 6, 9, 30, 0)), deadlineAt: null, missing: undefined }), true);
  assert.equal(w.append('engine-exit', { code: ledger.JS_ENGINE_STOP }), true);
  const [first, second] = lines(ledger.currentPath(dir, 'engine'));
  assert.deepEqual(Object.keys(first).slice(0, 5), ['schemaVersion', 'type', 'seq', 'at', 'writer']);
  assert.equal(first.seq, 1);
  assert.equal(second.seq, 2);
  assert.equal(first.at, '2026-10-06T09:30:00Z');
  assert.deepEqual(first.writer, { role: 'dev-runner', pid: 4242, startedAt: '2026-10-06T09:00:00Z' });
  assert.equal(first.requestedAt, '2026-10-06T09:30:00Z');
  assert.equal(first.missing, null);
  assert.equal(second.code, 1246953474);
  assert.equal(w.healthy(), true);
  const read = ledger.readLedger(dir, 'engine');
  assert.equal(read.skippedLines, 0);
  assert.equal(read.records.length, 2);
});

test('writer: caller bugs throw — another family, an envelope key, a record over 4 KiB', () => {
  const dir = freshDir();
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER });
  assert.throws(() => w.append('child-exit', {}), TypeError);
  assert.throws(() => w.append('engine-exit', { seq: 5 }), TypeError);
  assert.throws(() => w.append('engine-exit', { pad: 'x'.repeat(ledger.MAX_RECORD_BYTES) }), RangeError);
  assert.equal(fs.existsSync(ledger.currentPath(dir, 'engine')), false, 'nothing was written');
  assert.equal(w.append('ledger-gap', { reason: 'CORRUPT_LINES', count: 1, since: '2026-10-06T09:00:00Z' }), true,
    'ledger-gap belongs to every family');
});

test('writer: a failed write returns false, spends its seq, and the gap precedes the next record', () => {
  const dir = freshDir();
  let failNext = false;
  const fsImpl = { ...fs, openSync: (file, flags) => {
    if (failNext && flags === 'a') { failNext = false; const e = new Error('disk full'); e.code = 'ENOSPC'; throw e; }
    return fs.openSync(file, flags);
  } };
  const clockValues = [Date.UTC(2026, 9, 6, 9, 0, 1), Date.UTC(2026, 9, 6, 9, 0, 2), Date.UTC(2026, 9, 6, 9, 0, 3)];
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, fsImpl,
    clock: () => clockValues.shift() });
  assert.equal(w.append('engine-start', { n: 1 }), true);
  failNext = true;
  assert.doesNotThrow(() => assert.equal(w.append('engine-intent', { n: 2 }), false));
  assert.equal(w.healthy(), false);
  assert.equal(w.append('engine-exit', { n: 3 }), true);
  assert.equal(w.healthy(), true);
  const recs = lines(ledger.currentPath(dir, 'engine'));
  assert.deepEqual(recs.map((r) => [r.type, r.seq]), [['engine-start', 1], ['ledger-gap', 3], ['engine-exit', 4]],
    'seq 2 is the hole the lost record left');
  assert.deepEqual([recs[1].reason, recs[1].count, recs[1].since], ['WRITE_FAILED', 1, '2026-10-06T09:00:02Z']);
});

test('writer: a torn tail is closed at session start and by the next append', () => {
  const dir = freshDir();
  const file = ledger.currentPath(dir, 'engine');
  fs.writeFileSync(file, `${envelope({ type: 'engine-start' })}\n{"schemaVersion":1,"ty`);
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER });
  w.startSession();
  assert.equal(fs.readFileSync(file, 'utf8').endsWith('\n'), true);
  fs.appendFileSync(file, '{"torn by another writer"');
  assert.equal(w.append('engine-exit', {}), true);
  const read = ledger.readLedger(dir, 'engine');
  assert.equal(read.tornTail, false);
  assert.deepEqual(read.records.map((r) => r.type), ['engine-start', 'engine-exit']);
  assert.equal(read.corruptLines, 2, 'the two torn fragments are corrupt lines, the records are intact');
});

// --------------------------------------------------------------------------------------------- T12
test('T12: a rotating writer rotates a file over the session size at session start', () => {
  const dir = freshDir();
  const current = ledger.currentPath(dir, 'engine');
  const rotated = ledger.rotatedPath(dir, 'engine');
  fs.writeFileSync(rotated, `${envelope({ type: 'engine-start', seq: 99 })}\n`);
  fs.writeFileSync(current, `${envelope({ type: 'engine-start' })}\n`.repeat(4));
  const size = fs.statSync(current).size;
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, rotates: true,
    sessionRotateBytes: size - 1, hardCapBytes: size * 10 });
  w.startSession();
  assert.equal(fs.existsSync(current), false);
  assert.equal(fs.statSync(rotated).size, size, 'the older generation was replaced, not appended to');
  // the default bounds are the format's
  const d = ledger.createLedgerWriter({ ledgerDir: freshDir(), family: 'engine', writer: WRITER, rotates: true });
  assert.ok(d);
  assert.equal(ledger.SESSION_ROTATE_BYTES, 1024 * 1024);
  assert.equal(ledger.HARD_CAP_BYTES, 2 * 1024 * 1024);
});

test('T12: an append-only writer never rotates; a small file is not rotated', () => {
  const dir = freshDir();
  const current = ledger.currentPath(dir, 'engine');
  fs.writeFileSync(current, `${envelope({ type: 'engine-start' })}\n`.repeat(4));
  const size = fs.statSync(current).size;
  ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, rotates: false,
    sessionRotateBytes: 1, hardCapBytes: 2 }).startSession();
  assert.equal(fs.statSync(current).size, size);
  ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, rotates: true,
    sessionRotateBytes: size, hardCapBytes: size * 2 }).startSession();
  assert.equal(fs.statSync(current).size, size, 'at the session size exactly it is not over it');
});

test('T12: the hard cap rotates before an append that would pass it', () => {
  const dir = freshDir();
  const current = ledger.currentPath(dir, 'engine');
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, rotates: true,
    clock: fixedClock(), sessionRotateBytes: 100, hardCapBytes: 600 });
  w.startSession();
  let appended = 0;
  while (!fs.existsSync(ledger.rotatedPath(dir, 'engine'))) {
    assert.equal(w.append('engine-start', { n: appended }), true);
    appended += 1;
    assert.ok(fs.statSync(current).size <= 600, 'the current file never passes the cap');
    assert.ok(appended < 50);
  }
  const all = ledger.readLedger(dir, 'engine');
  assert.equal(all.records.length, appended, 'every record is in a readable generation');
  assert.deepEqual(all.records.map((r) => r.seq), Array.from({ length: appended }, (_, i) => i + 1));
});

test('T12: a failed rotation keeps appending to the current file and records ROTATE_FAILED', () => {
  const dir = freshDir();
  const current = ledger.currentPath(dir, 'engine');
  fs.writeFileSync(current, `${envelope({ type: 'engine-start' })}\n`.repeat(4));
  const size = fs.statSync(current).size;
  const w = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, rotates: true,
    clock: fixedClock(), sessionRotateBytes: size - 1, hardCapBytes: size * 10,
    rotation: () => { const e = new Error('sharing violation'); e.code = 'EBUSY'; throw e; } });
  w.startSession();
  assert.equal(w.append('engine-exit', {}), true);
  const recs = lines(current);
  assert.equal(recs.length, 6);
  assert.deepEqual([recs[4].type, recs[4].reason, recs[4].count, recs[4].seq], ['ledger-gap', 'ROTATE_FAILED', 1, 1]);
  assert.deepEqual([recs[5].type, recs[5].seq], ['engine-exit', 2]);
});

// Interleaved in one process, not truly concurrent: what it pins is that an append-only writer's
// per-append open follows the rotator's renames, so every surviving line is whole and readable.
test('T12: an append-only writer interleaved with rotations always lands in a readable generation', () => {
  const dir = freshDir();
  const current = ledger.currentPath(dir, 'engine');
  const rotator = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', writer: WRITER, rotates: true,
    sessionRotateBytes: 100, hardCapBytes: 400 });
  const appender = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine',
    writer: { role: 'engine', pid: 99, startedAt: Date.UTC(2026, 9, 6) } });
  let total = 0;
  for (let i = 0; i < 40; i += 1) {
    assert.equal(rotator.append('engine-start', { i }), true);
    assert.equal(appender.append('engine-self-intent', { i }), true);
    total += 2;
  }
  const read = ledger.readLedger(dir, 'engine');
  assert.equal(read.skippedLines, 0);
  assert.equal(read.tornTail, false);
  assert.ok(read.records.length > 0 && read.records.length <= total);
  assert.ok(fs.statSync(current).size <= 400 + 256);
});

test('constants: the distinctive terminate codes', () => {
  assert.equal(ledger.JS_CHILD_STOP, 1246953473);
  assert.equal(ledger.JS_ENGINE_STOP, 1246953474);
});

let failed = 0;
for (const t of tests) {
  try {
    // eslint-disable-next-line no-await-in-loop
    await t.fn();
    console.log(`ok - ${t.name}`);
  } catch (err) {
    failed += 1;
    console.log(`not ok - ${t.name}\n${err?.stack ?? err}`);
  }
}
fs.rmSync(tmp, { recursive: true, force: true });
console.log(`\nexit-ledger: ${tests.length - failed}/${tests.length} passed`);
if (failed) process.exit(1);
