/**
 * Tempdoc 861 W2 [A2] — process identity: the adverse tests, plus the happy path.
 *
 * Phase 2's acceptance is not "identity verification works". It is that identity verification
 * REFUSES in every state where the evidence does not support a kill (`green-masked-destructive`:
 * a happy-path-only suite does not close this phase). The required branches:
 *
 *   (i)   recycled pid          — pid alive, creation time differs -> MISMATCH (not MATCH)
 *   (ii)  unreadable creation   — field absent or unparseable      -> REFUSE   (not MISMATCH)
 *   (iii) table unavailable     — enumeration failed, returns []   -> REFUSE   (an empty table is
 *                                                                   NO evidence, not exculpatory
 *                                                                   evidence — `getProcessTable`
 *                                                                   fails silently to `[]` by
 *                                                                   design, remove-worktree.cjs:125-131)
 *   (iv)  STALE table           — a snapshot past the freshness bound -> REFUSE. Added by review:
 *                                 all three conjuncts match a stale row even after the pid has been
 *                                 recycled, so no mutation to the conjunction can catch this. A
 *                                 caller that hoists one `readProcessTable` out of a sweep loop is
 *                                 the realistic way in.
 *
 * Each branch was proven DISCRIMINATING by mutation: with the corresponding guard removed from
 * `process-identity.cjs`, the test fails; restored, it passes.
 *
 * These tests are sited under `scripts/agent-analytics/` deliberately: that directory is
 * auto-discovered by `run-all-tests.mjs:31-40` and run as a CI job step, while `scripts/dev/*.test.mjs`
 * runs in CI nowhere (861 §7.6). The MODULE lives in `scripts/dev/lib/`; test location and module
 * location need not match, and for the safety-critical branch of a kill path, CI coverage wins.
 *
 * Fixtures only — no process is signalled, and the live probes are Windows-guarded and read-only.
 *
 * Run with: `node scripts/agent-analytics/861-w2-process-identity.test.mjs`
 */

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  PROCESS_TABLE_PS_COMMAND,
  IDENTITY,
  DEFAULT_MAX_TABLE_AGE_MS,
  normalizeCreationTime,
  describeJsonParseFailure,
  readProcessTable,
  coerceProcessTable,
  verifyProcessIdentity,
  isVerifiedMatch,
} = require('../dev/lib/process-identity.cjs');
const { getProcessTable } = require('../dev/remove-worktree.cjs');
const { spawn, spawnSync } = require('node:child_process');

let passed = 0;
let skipped = 0;
const failures = [];
function check(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

async function checkAsync(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

/** Spawn a disposable, detached child whose argv carries `marker`, killed by the caller. */
function spawnMarked(marker) {
  const child = spawn(process.execPath, ['-e', 'setTimeout(()=>{}, 20000)', marker], {
    detached: true,
    stdio: 'ignore',
    windowsHide: true,
  });
  child.unref();
  return child;
}

function killMarked(child) {
  try {
    if (child.exitCode === null && child.signalCode === null) child.kill(); // original held ChildProcess
  } catch {
    /* best-effort cleanup only */
  }
}

/** Condition-poll (not a blind sleep) for a pid to show up in a live `readProcessTable()` read. */
async function waitForPidInTable(pid, { timeoutMs = 8000, intervalMs = 250 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    const result = readProcessTable();
    last = result;
    if (result.ok) {
      const row = result.table.find((r) => Number(r.ProcessId) === pid);
      if (row) return { result, row };
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return { result: last, row: null };
}

/* ── Fixtures ─────────────────────────────────────────────────────────────────────────────── */

// 18-digit FILETIMEs, the real shape measured from `Win32_Process.CreationDate.ToFileTimeUtc()`
// on this host (2026-08-25): e.g. `134320479841300350`.
const T_ORIGINAL = '134320479841300350';
const T_RECYCLED = '134320999999999990';

const RECORD = () => ({
  pid: 4242,
  creationFileTimeUtc: T_ORIGINAL,
  cmdlineFingerprint: 'vite --port 5173',
});

const ROW = (over = {}) => ({
  ProcessId: 4242,
  ParentProcessId: 100,
  Name: 'node.exe',
  CommandLine: 'node C:\\wt\\node_modules\\vite\\bin\\vite.js vite --port 5173',
  CreationFileTimeUtc: T_ORIGINAL,
  ...over,
});

const NOISE = { ProcessId: 100, ParentProcessId: 1, Name: 'cmd.exe', CommandLine: 'cmd.exe /d /s /c npx.cmd vite', CreationFileTimeUtc: T_ORIGINAL };

/** A just-read snapshot, the shape `readProcessTable` returns. Bare arrays now REFUSE by design. */
const fresh = (rows) => ({ ok: true, table: rows, readAt: Date.now() });

/* ── Happy path ───────────────────────────────────────────────────────────────────────────── */

check('happy path: pid AND creation time AND fingerprint all agree, on a fresh table -> MATCH', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([NOISE, ROW()]) });
  assert.equal(r.verdict, IDENTITY.MATCH);
  assert.deepEqual(r.matched, { pid: true, creationTime: true, fingerprint: true });
  assert.equal(isVerifiedMatch(r), true);
});

/* ── (i) recycled pid ─────────────────────────────────────────────────────────────────────── */

check('(i) recycled pid: same pid, DIFFERENT creation time -> MISMATCH, never MATCH', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([ROW({ CreationFileTimeUtc: T_RECYCLED })]) });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
  assert.equal(isVerifiedMatch(r), false);
  assert.equal(r.matched.creationTime, false);
  // Precision: the pid itself DID match. If this assertion inverted, the test would be passing for
  // the wrong reason (e.g. because the fixture pid was wrong rather than because the time differed).
  assert.equal(r.matched.pid, true);
  assert.match(r.reason, /recycled/);
});

check('(i) the recycled process shares the fingerprint too: the conjunction still refuses it', () => {
  // The realistic recycle: a NEW vite on the same reused pid, whose command line contains the very
  // same substring. Fingerprint alone would say yes; only the creation-time term catches it — which
  // is why 861 §6.2 calls the substring fingerprint safe ONLY inside the conjunction.
  const row = ROW({ CreationFileTimeUtc: T_RECYCLED, CommandLine: 'node vite.js vite --port 5173' });
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([row]) });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
});

check('(i) exact equality, no tolerance window: a 100ns difference is still a MISMATCH', () => {
  // Measured, and the reason the creation time is carried as a decimal STRING: as JSON numbers,
  // Number('134320479841300350') === Number('134320479841300351') is TRUE in Node, so these two
  // instants would collapse and the recycled-pid branch would silently stop discriminating.
  assert.equal(Number(T_ORIGINAL) === Number('134320479841300351'), true);
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([ROW({ CreationFileTimeUtc: '134320479841300351' })]) });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
});

check('a pid absent from a successfully-read table is a read NEGATIVE, not an absent one', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([NOISE]) });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
  assert.equal(r.matched.pid, false);
});

/* ── (ii) unreadable creation time ────────────────────────────────────────────────────────── */

check('(ii) record creation time ABSENT -> REFUSE (not MISMATCH, not MATCH)', () => {
  const record = { ...RECORD(), creationFileTimeUtc: undefined };
  const r = verifyProcessIdentity({ record, table: fresh([ROW()]) });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.notEqual(r.verdict, IDENTITY.MISMATCH);
  assert.equal(isVerifiedMatch(r), false);
});

check('(ii) record creation time UNPARSEABLE -> REFUSE', () => {
  for (const bad of ['', '  ', 'not-a-time', '20260825T101112Z', '-1', '0', {}, [], true]) {
    const r = verifyProcessIdentity({ record: { ...RECORD(), creationFileTimeUtc: bad }, table: fresh([ROW()]) });
    assert.equal(r.verdict, IDENTITY.REFUSE, `expected REFUSE for ${JSON.stringify(bad)}, got ${r.verdict}`);
  }
});

check('(ii) LIVE row creation time absent/unparseable -> REFUSE (pid reuse cannot be ruled out)', () => {
  for (const bad of [null, undefined, 'garbage', '']) {
    const r = verifyProcessIdentity({ record: RECORD(), table: fresh([ROW({ CreationFileTimeUtc: bad })]) });
    assert.equal(r.verdict, IDENTITY.REFUSE, `expected REFUSE for live-row ${JSON.stringify(bad)}, got ${r.verdict}`);
    assert.equal(r.matched.pid, true);
    assert.equal(r.matched.creationTime, null); // not evaluated — not "false"
  }
});

check('(ii) a JSON NUMBER creation time is unreadable evidence, never a rounded compare', () => {
  // Every number is refused, not merely the unsafe ones: a FILETIME small enough to be a safe
  // integer would date the process before 1629, so the safe-integer branch could only ever admit
  // a value that is not a creation time at all.
  assert.equal(Number.isSafeInteger(Number(T_ORIGINAL)), false);
  assert.equal(normalizeCreationTime(Number(T_ORIGINAL)), null);
  assert.equal(normalizeCreationTime(12345), null);
  assert.equal(normalizeCreationTime(0), null);
  const r = verifyProcessIdentity({ record: { ...RECORD(), creationFileTimeUtc: Number(T_ORIGINAL) }, table: fresh([ROW()]) });
  assert.equal(r.verdict, IDENTITY.REFUSE);
});

check('normalizeCreationTime canonicalizes without loss, and refuses non-evidence', () => {
  assert.equal(normalizeCreationTime(T_ORIGINAL), T_ORIGINAL);
  assert.equal(normalizeCreationTime(` ${T_ORIGINAL} `), T_ORIGINAL);
  assert.equal(normalizeCreationTime('0134320479841300350'), T_ORIGINAL); // leading zeros canonicalize
  assert.equal(normalizeCreationTime(null), null);
  assert.equal(normalizeCreationTime(NaN), null);
  assert.equal(normalizeCreationTime('99999999999999999999999'), null); // past the digit bound
});

/* ── (iii) process table unavailable ──────────────────────────────────────────────────────── */

check('(iii) EMPTY table -> REFUSE: no evidence is not exculpatory evidence', () => {
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: [] }).verdict, IDENTITY.REFUSE);
  const r = verifyProcessIdentity({ record: RECORD(), table: { ok: true, table: [], readAt: Date.now() } });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.match(r.reason, /NO evidence/);
});

check('(iii) missing / null / failed-tri-state table -> REFUSE in every form', () => {
  for (const table of [undefined, null, { ok: false, reason: 'powershell exited 1' }, { ok: true, table: [], readAt: Date.now() }, 'nonsense', 42, { ok: true, readAt: Date.now() }]) {
    const r = verifyProcessIdentity({ record: RECORD(), table });
    assert.equal(r.verdict, IDENTITY.REFUSE, `expected REFUSE for table=${JSON.stringify(table)}, got ${r.verdict}`);
  }
});

check('(iii) readProcessTable never degrades to [] — it reports the failure, and stamps success', () => {
  const failing = () => ({ status: 1, stdout: '', stderr: 'boom' });
  assert.equal(readProcessTable({ platform: 'win32', exec: failing }).ok, false);
  assert.equal(readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: '[]' }) }).ok, false, 'a zero-row enumeration on a running host is a failed query');
  assert.equal(readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: 'not json' }) }).ok, false);
  assert.equal(readProcessTable({ platform: 'linux' }).ok, false);
  assert.equal(readProcessTable({ platform: 'win32', exec: () => { throw new Error('spawn EPERM'); } }).ok, false);
  const single = readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: JSON.stringify(ROW()) }), now: () => 1234 });
  assert.equal(single.ok, true, 'a single-object CIM result is wrapped into a one-row table');
  assert.equal(single.table.length, 1);
  assert.equal(single.readAt, 1234, 'a successful read must carry its own timestamp');
});

/* ── (iv) STALE table — the branch the conjunction cannot see ─────────────────────────────── */

check('(iv) a STALE snapshot -> REFUSE even though all three conjuncts match', () => {
  const now = 1_000_000;
  const stale = { ok: true, table: [ROW()], readAt: now - (DEFAULT_MAX_TABLE_AGE_MS + 1) };
  const r = verifyProcessIdentity({ record: RECORD(), table: stale, now });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.match(r.reason, /freshness bound/);
  // Precision: the SAME rows inside the bound produce a MATCH, so the refusal is caused by the age
  // and nothing else.
  const bounded = { ok: true, table: [ROW()], readAt: now - (DEFAULT_MAX_TABLE_AGE_MS - 1) };
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: bounded, now }).verdict, IDENTITY.MATCH);
});

check('(iv) the bound is caller-tunable, and a future-dated stamp is refused too', () => {
  const now = 1_000_000;
  const snapshot = { ok: true, table: [ROW()], readAt: now - 30_000 };
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: snapshot, now }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: snapshot, now, maxTableAgeMs: 60_000 }).verdict, IDENTITY.MATCH);
  const future = { ok: true, table: [ROW()], readAt: now + 60_000 };
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: future, now }).verdict, IDENTITY.REFUSE);
});

check('(iv) a bare unstamped array REFUSES unless the caller names the waiver', () => {
  const bare = [ROW()];
  const refused = verifyProcessIdentity({ record: RECORD(), table: bare });
  assert.equal(refused.verdict, IDENTITY.REFUSE, 'an unstamped array must not be trusted by default');
  assert.match(refused.reason, /readAt/);
  // The waiver has to be spelled out, and only then does the normal verdict follow.
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: bare, acceptUnstampedTable: true }).verdict, IDENTITY.MATCH);
  // A stamp-less tri-state result is the same situation and gets the same treatment.
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: { ok: true, table: bare } }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: { ok: true, table: bare }, acceptUnstampedTable: true }).verdict, IDENTITY.MATCH);
  // The waiver does NOT reach the other refusals — it waives the age bound only.
  assert.equal(verifyProcessIdentity({ record: RECORD(), table: [], acceptUnstampedTable: true }).verdict, IDENTITY.REFUSE);
});

check('coerceProcessTable maps every unusable shape onto ok:false', () => {
  const now = 1_000_000;
  assert.equal(coerceProcessTable([]).ok, false);
  assert.equal(coerceProcessTable(null).ok, false);
  assert.equal(coerceProcessTable([ROW()]).ok, false, 'bare array without the waiver');
  assert.equal(coerceProcessTable([ROW()], { acceptUnstampedTable: true }).ok, true);
  assert.equal(coerceProcessTable({ ok: true, table: [], readAt: now }, { now }).ok, false);
  const good = coerceProcessTable({ ok: true, table: [ROW()], readAt: now - 500 }, { now });
  assert.equal(good.ok, true);
  assert.equal(good.ageMs, 500);
});

/* ── The conjunction, and the shape of the verdict ────────────────────────────────────────── */

check('fingerprint is the third conjunct: pid + creation time alone do NOT license a match', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([ROW({ CommandLine: 'node some-other-server.js' })]) });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
  assert.deepEqual(r.matched, { pid: true, creationTime: true, fingerprint: false });
});

check('an unavailable CommandLine cannot be read as a passing fingerprint -> REFUSE', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: fresh([ROW({ CommandLine: null })]) });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.equal(r.matched.fingerprint, null);
});

check('a record missing pid or fingerprint refuses rather than falling through', () => {
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), pid: 0 }, table: fresh([ROW()]) }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), pid: '4242' }, table: fresh([ROW()]) }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), cmdlineFingerprint: '  ' }, table: fresh([ROW()]) }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), cmdlineFingerprint: '' }, table: fresh([ROW()]) }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({}).verdict, IDENTITY.REFUSE);
});

check('REFUSE is a THIRD value, not false — the classifyActivity precedent', () => {
  // `classifyActivity` returns `{ known:false, generalStale:false }` so an absent signal cannot
  // masquerade as a permissive one (ownership-verdict.cjs:83-85). Same discipline here: a caller
  // spelling the check `verdict !== MISMATCH` must not thereby get permission.
  assert.equal(IDENTITY.REFUSE === false, false);
  assert.equal(IDENTITY.REFUSE === IDENTITY.MISMATCH, false);
  const refused = verifyProcessIdentity({ record: RECORD(), table: fresh([]) });
  assert.equal(typeof refused.verdict, 'string');
  assert.notEqual(refused.verdict, IDENTITY.MISMATCH);
  assert.equal(isVerifiedMatch(refused), false);
  for (const v of [IDENTITY.REFUSE, IDENTITY.MISMATCH]) assert.equal(isVerifiedMatch({ verdict: v }), false);
  assert.equal(isVerifiedMatch({ verdict: IDENTITY.MATCH }), true);
});

/* ── The projection [A2] ──────────────────────────────────────────────────────────────────── */

check('[A2] the CIM projection adds the creation time and keeps every column its consumers use', () => {
  for (const col of ['ProcessId', 'ParentProcessId', 'Name', 'CommandLine']) {
    assert.ok(PROCESS_TABLE_PS_COMMAND.includes(col), `projection lost ${col}, which remove-worktree's holder scan needs`);
  }
  assert.ok(PROCESS_TABLE_PS_COMMAND.includes('CreationDate'), 'projection must not project CreationDate away (861 [A2])');
  assert.ok(PROCESS_TABLE_PS_COMMAND.includes('ToFileTimeUtc'), 'creation time must be normalized in PowerShell, not compared as a locale-dependent CIM datetime string');
});

/* ── 861 production sweep (2026-08-25): control characters survive the process-table round trip ──
 *
 * Root cause, reproduced live (not merely hypothesized): Windows PowerShell 5.1's
 * `[Console]::OutputEncoding` is the OEM codepage (IBM437 on the repro host) even when
 * `-NoProfile -NonInteractive` with stdout piped — verified directly against `spawnSync`'s own
 * invocation shape. CP437 double-books low byte values 0x01-0x1F as GLYPHS for common symbols
 * (U+2022 '•' -> 0x07, U+263A '☺' -> 0x01, U+2192 '→' -> 0x1A, etc. — a brute-force scan of every
 * BMP code point found 34 such collisions). `ConvertTo-Json` emits those symbols correctly
 * (unescaped, since they are far above the C0 range JSON requires escaping); the corruption is the
 * OS console-encoding step that follows, converting the printable symbol to a raw CP437 byte that
 * happens to sit in the C0 control range, which `spawnSync({encoding:'utf8'})` then decodes as a
 * genuine control character inside what was, one hop earlier, valid JSON. Forcing the console's
 * actual output encoding to UTF-8 (`PROCESS_TABLE_PS_COMMAND`'s `[Console]::OutputEncoding` line)
 * closes this class. A SEPARATE regex-based sanitizer strips any control byte the source string
 * already carries verbatim (e.g. a corrupted WMI cross-process read) before `ConvertTo-Json` ever
 * sees it — neither layer alone covers the other's case. */

check('[A2->861] the projection sets console output encoding to UTF-8, closing the codepage-glyph class', () => {
  assert.match(PROCESS_TABLE_PS_COMMAND, /\[Console\]::OutputEncoding\s*=\s*\[System\.Text\.UTF8Encoding\]/, 'the OEM-codepage encoding mismatch (861 production sweep) is not fixed at its source');
});

check('[A2->861] the projection sanitizes CommandLine and Name against raw C0 control bytes, preserving tab/LF/CR', () => {
  const sanitizeRegex = /\[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\]/;
  const commandLineBlock = /CommandLine[\s\S]*?Expression=\{([\s\S]*?)\}\}/.exec(PROCESS_TABLE_PS_COMMAND);
  const nameBlock = /Name'\s*;\s*Expression=\{([\s\S]*?)\}\}/.exec(PROCESS_TABLE_PS_COMMAND);
  assert.ok(commandLineBlock, 'CommandLine must be a calculated property, not a bare passthrough, to be sanitizable');
  assert.match(commandLineBlock[1], sanitizeRegex, 'CommandLine calculated property must strip C0 controls');
  assert.ok(nameBlock, 'Name must be a calculated property');
  assert.match(nameBlock[1], sanitizeRegex, 'Name calculated property must strip C0 controls');
  // Tab (\x09), LF (\x0A), CR (\x0D) are the three gaps in the stripped ranges [00-08][0B-0C][0E-1F] —
  // pin the gap explicitly so a future edit cannot silently widen the stripped range over them.
  for (const preserved of ['\\x09', '\\x0A', '\\x0D']) {
    assert.ok(!sanitizeRegex.source.includes(preserved), `sanitizer must not strip ${preserved} (ConvertTo-Json already escapes it correctly)`);
  }
});

/* ── describeJsonParseFailure: the REFUSE diagnostic ──────────────────────────────────────── */

check('describeJsonParseFailure locates the failure and flags any control byte still present', () => {
  // A raw, UNESCAPED control byte sitting inside a string literal — built via fromCharCode, never
  // as a literal source byte, so this file cannot itself become an instance of the bug it tests.
  // 0x07 is exactly the shape CP437 produces for U+2022 once it round-trips through the
  // console-encoding bug this fix closes (see the [A2->861] comment above).
  const rawControlByte = String.fromCharCode(0x07);
  const text = `{"rows":["${'x'.repeat(30)}${rawControlByte}bad${'y'.repeat(30)}"]}`;
  let caught;
  try {
    JSON.parse(text);
    assert.fail('fixture must actually fail JSON.parse — otherwise this test proves nothing');
  } catch (err) {
    caught = err;
  }
  const described = describeJsonParseFailure(text, caught);
  assert.match(described, /position \d+/i, 'must preserve the original JSON.parse position');
  assert.match(described, /near offset \d+/, 'must add a located window, not just repeat the raw message');
  assert.match(described, /0x07/, 'must name the actual offending control byte, not just say "a control character"');
});

check('describeJsonParseFailure degrades gracefully when it cannot locate a position', () => {
  const err = new Error('totally different shape of error, no position mentioned');
  assert.equal(describeJsonParseFailure('{"a":1}', err), err.message);
  assert.equal(describeJsonParseFailure(null, err), err.message);
  assert.equal(describeJsonParseFailure(undefined, { message: 'position 5 but no text to slice' }), 'position 5 but no text to slice');
});

check('a genuinely unparseable table still REFUSES — the diagnostic enriches the reason, never weakens the verdict', () => {
  const rawControlByte = String.fromCharCode(0x07);
  const badJson = `[{"ProcessId":1,"CommandLine":"${'a'.repeat(20)}${rawControlByte}bad"}]`;
  const r = readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: badJson }) });
  assert.equal(r.ok, false, 'malformed JSON must still be REFUSE, not a best-effort parse');
  assert.match(r.reason, /not JSON/);
  assert.match(r.reason, /0x07/, 'the REFUSE reason must carry the diagnostic, not just the bare JSON.parse message');
});

/* ── Windows-guarded live probes (read-only) ──────────────────────────────────────────────── */

if (process.platform === 'win32') {
  check('[win32] readProcessTable really enumerates, and this process verifies against its own row', () => {
    const result = readProcessTable();
    assert.equal(result.ok, true, `live enumeration failed: ${result.reason}`);
    assert.ok(Number.isFinite(result.readAt), 'a live read must carry a readAt stamp');
    const self = result.table.find((r) => Number(r.ProcessId) === process.pid);
    assert.ok(self, 'this process is missing from its own process table');
    const t = normalizeCreationTime(self.CreationFileTimeUtc);
    assert.ok(t !== null, `live creation time unreadable: ${JSON.stringify(self.CreationFileTimeUtc)}`);
    assert.ok(t.length >= 17, `expected an 18-digit FILETIME, got ${t}`);
    assert.equal(Number.isSafeInteger(Number(t)), false, 'a live FILETIME must exceed MAX_SAFE_INTEGER — the reason it travels as a string');

    const record = { pid: process.pid, creationFileTimeUtc: t, cmdlineFingerprint: '861-w2-process-identity' };
    assert.equal(verifyProcessIdentity({ record, table: result }).verdict, IDENTITY.MATCH);
    // Same live row, one tick earlier: the recycled-pid branch against real evidence.
    const shifted = (BigInt(t) - 1n).toString();
    assert.equal(verifyProcessIdentity({ record: { ...record, creationFileTimeUtc: shifted }, table: result }).verdict, IDENTITY.MISMATCH);
    // And the same real snapshot, aged past the bound: REFUSE.
    const aged = { ...result, readAt: result.readAt - 60_000 };
    assert.equal(verifyProcessIdentity({ record, table: aged }).verdict, IDENTITY.REFUSE);
  });

  check('[win32] remove-worktree getProcessTable now carries the creation time', () => {
    const table = getProcessTable();
    assert.ok(table.length > 0, 'live holder-scan table was empty');
    const self = table.find((r) => Number(r.ProcessId) === process.pid);
    assert.ok(self, 'this process is missing from getProcessTable()');
    assert.ok(normalizeCreationTime(self.CreationFileTimeUtc) !== null, 'getProcessTable still projects the creation time away (861 [A2])');
    assert.equal(typeof self.CommandLine, 'string', 'the holder scan lost CommandLine');
  });
} else {
  skipped += 2;
}

/* \u2500\u2500 861 production sweep: REAL PowerShell round trips through the live REGISTRY-fixture process,
 * not a mocked shape. This is the fixture the tempdoc asked for: "a fixture CommandLine carrying a
 * raw control character through the REAL PowerShell round trip". Every disposable child is killed
 * in a `finally`, and nothing here signals or reads any OTHER process on the host. \u2500\u2500 */

if (process.platform === 'win32') {
  await checkAsync('[win32] a raw C0 control byte in a real CommandLine survives the round trip (sanitizer)', async () => {
    // Built via fromCharCode: this spawns a REAL child process whose OS-level CommandLine (as WMI
    // will report it) contains an actual, literal 0x01 byte \u2014 verified directly against a raw CIM
    // query before this fix existed (not merely asserted).
    const marker = 'W2861_RAWCTRL_' + String.fromCharCode(0x01) + '_END';
    const child = spawnMarked(marker);
    try {
      const { result, row } = await waitForPidInTable(child.pid);
      assert.ok(result && result.ok, `live process-table read failed: ${result && result.reason}`);
      assert.ok(row, `spawned pid ${child.pid} never appeared in the process table`);
      assert.equal(typeof row.CommandLine, 'string', 'CommandLine must survive as a string, not disappear');
      assert.ok(row.CommandLine.includes('W2861_RAWCTRL_'), 'sanitizer must not eat the surrounding text, only the control byte');
      for (const ch of row.CommandLine) {
        assert.ok(ch.codePointAt(0) >= 0x20 || ch === '\t' || ch === '\n' || ch === '\r', `a raw control byte 0x${ch.codePointAt(0).toString(16)} survived the sanitizer into a live CommandLine`);
      }
    } finally {
      killMarked(child);
    }
  });

  await checkAsync('[win32] a printable symbol that CP437 double-books as a control glyph survives the round trip (encoding fix)', async () => {
    // The ACTUAL reproduced production mechanism (see the [A2->861] comment): U+2022 '\u2022' encodes to
    // CP437 byte 0x07, which upstream Node re-decodes as a literal control character under the OLD
    // (buggy) command. Under the fix, `[Console]::OutputEncoding` is UTF-8, so this round-trips
    // intact as the actual symbol.
    const bullet = String.fromCharCode(0x2022);
    const smiley = String.fromCharCode(0x263a);
    const marker = 'W2861_SYMBOL_' + bullet + '_' + smiley + '_END';
    const child = spawnMarked(marker);
    try {
      const { result, row } = await waitForPidInTable(child.pid);
      assert.ok(result && result.ok, `live process-table read failed (this IS the 861 production symptom if it fails): ${result && result.reason}`);
      assert.ok(row, `spawned pid ${child.pid} never appeared in the process table`);
      assert.ok(row.CommandLine.includes(bullet), `U+2022 must round-trip as itself, not corrupt to a control byte; got ${JSON.stringify(row.CommandLine)}`);
      assert.ok(row.CommandLine.includes(smiley), `U+263A must round-trip as itself, not corrupt to a control byte; got ${JSON.stringify(row.CommandLine)}`);
    } finally {
      killMarked(child);
    }
  });
} else {
  skipped += 2;
}

if (failures.length > 0) {
  console.error(`861-w2-process-identity.test: ${failures.length} FAILED, ${passed} passed, ${skipped} skipped`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w2-process-identity.test: all ${passed} checks passed${skipped ? ` (${skipped} skipped: not win32)` : ''}`);
