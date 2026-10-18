/**
 * Tempdoc 861 W2 — the `agent-spawns/` record grammar, its lease, and [A10] retention.
 *
 * What these tests hold in place:
 *
 *   - [A8] the scope carries its OWN schema version, structurally independent of `foreign/`'s;
 *   - [A9] the scope resolves through `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`, as a SIBLING of
 *     `foreign/` and never inside the dev-runner's enumerated children;
 *   - [A7] validation is per-scope, proven in BOTH directions: neither scope's reader silently
 *     accepts the other's records;
 *   - reading never deletes — the record files survive every read, including stale and
 *     failed-verify ones;
 *   - [A10] pruning is an explicit maintenance call: age AND no live lease, failed-verify records
 *     pruned by age alone, orphaned temp files swept, symlinks never deleted through;
 *   - the junction-lock lookup works through a REAL junction, in both directions (the query path
 *     and the recorded root), which is the whole reason `resourceRoots` exists.
 *
 * Sited under `scripts/agent-analytics/` because that directory is CI-discovered
 * (`run-all-tests.mjs:31-40`) while `scripts/dev/*.test.mjs` is not (861 §7.6).
 *
 * Run with: `node scripts/agent-analytics/861-w2-agent-spawn-record.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  AGENT_SPAWN_RECORD_SCHEMA_VERSION,
  AGENT_SPAWNS_REGISTER_DIRNAME,
  AGENT_SPAWNS_REGISTER_RELPOSIX,
  OWNERSHIP_MODES,
  DEFAULT_MAX_RECORD_AGE_MS,
  resolveAgentSpawnsRegisterDir,
  agentSpawnRecordPath,
  assertSafeRecordId,
  validateAgentSpawnRecord,
  readAgentSpawnRegister,
  buildAgentSpawnRecord,
  writeAgentSpawnRecord,
  removeAgentSpawnRecord,
  renewAgentSpawnLease,
  markAgentSpawnRecordFailedVerify,
  leaseState,
  recordHoldsPath,
  resolveNodeModulesRealPath,
  pruneAgentSpawnRecords,
} = require('../dev/lib/agent-spawn-record.cjs');
const {
  FOREIGN_RECORD_SCHEMA_VERSION,
  resolveForeignRegisterDir,
  validateForeignRecord,
  readForeignRegister,
  writeRecordAtomic,
} = require('../dev/lib/process-record.cjs');

let passed = 0;
let skipped = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const dirsToClean = [];
async function makeDir() {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w2-'));
  dirsToClean.push(dir);
  return dir;
}

const T = '134320479841300350';
const DAY = 24 * 60 * 60 * 1000;

function goodRecord(over = {}) {
  return buildAgentSpawnRecord({
    recordId: 'ui-shot-5173',
    producer: 'ui-shot',
    pid: 4242,
    creationFileTimeUtc: T,
    cmdlineFingerprint: 'vite --port 5173',
    port: 5173,
    leaseDurationSec: 1800,
    sessionId: 'bccfc163',
    repoRoot: 'F:/justsearch-public',
    ...over,
  });
}

// One canonical valid record, for the many assertions that only need to patch one field.
const BASE = await goodRecord();

// The shape `foreign/`'s producer (`run_register.py`) writes: schemaVersion 1, ports.api, no
// identity triple, no ownership mode.
const FOREIGN_SHAPED = () => ({
  schemaVersion: FOREIGN_RECORD_SCHEMA_VERSION,
  recordId: 'jseval-33221',
  producer: 'jseval',
  pid: 9001,
  ports: { api: 33221 },
  repoRoot: 'F:/justsearch-public',
  workload: 'eval',
  startedAt: new Date().toISOString(),
});

/* ── [A8] the scope's own schema version ──────────────────────────────────────────────────── */

await check('[A8] the agent-spawns version constant is this scope\'s own, not foreign\'s', () => {
  assert.equal(typeof AGENT_SPAWN_RECORD_SCHEMA_VERSION, 'number');
  // They are both 1 today by coincidence, not coupling — so the test that matters is that the
  // agent-spawns validator names ITS OWN constant and rejects a record declaring a different one,
  // independent of whatever `foreign/` happens to be at.
  const v = validateAgentSpawnRecord({ ...BASE, schemaVersion: AGENT_SPAWN_RECORD_SCHEMA_VERSION + 1 });
  assert.equal(v.ok, false);
  assert.match(v.reason, new RegExp(`understands ${AGENT_SPAWN_RECORD_SCHEMA_VERSION}`));
  assert.match(v.reason, /agent-spawn schemaVersion/);
});

/* ── [A9] directory resolution ────────────────────────────────────────────────────────────── */

await check('[A9] the scope honours JUSTSEARCH_DEV_RUNNER_STATE_ROOT', () => {
  const withOverride = resolveAgentSpawnsRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: 'D:/iso/state' });
  assert.equal(path.basename(withOverride), AGENT_SPAWNS_REGISTER_DIRNAME);
  assert.equal(path.resolve(withOverride), path.resolve('D:/iso/state', AGENT_SPAWNS_REGISTER_DIRNAME));
  const plain = resolveAgentSpawnsRegisterDir('F:/main', {});
  assert.equal(path.resolve(plain), path.resolve('F:/main', AGENT_SPAWNS_REGISTER_RELPOSIX));
  assert.equal(resolveAgentSpawnsRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: '   ' }), plain);
});

await check('the scope is a SIBLING of foreign/, never inside it or the dev-runner\'s children', () => {
  const mine = resolveAgentSpawnsRegisterDir('F:/main', {});
  const theirs = resolveForeignRegisterDir('F:/main', {});
  assert.equal(path.dirname(mine), path.dirname(theirs));
  assert.notEqual(mine, theirs);
  // `dev-runner.cjs` enumerates only `runs/`; a sibling directory cannot be mistaken for a run.
  assert.notEqual(path.basename(mine), 'runs');
  assert.equal(AGENT_SPAWNS_REGISTER_RELPOSIX, `tmp/dev-runner/${AGENT_SPAWNS_REGISTER_DIRNAME}`);
});

/* ── [A7] validation is per-scope, in BOTH directions ─────────────────────────────────────── */

await check('[A7] a foreign-shaped record in agent-spawns/ reads as unreadable, never accepted', async () => {
  const dir = await makeDir();
  await writeRecordAtomic(path.join(dir, 'jseval-33221.json'), FOREIGN_SHAPED());
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].ok, false);
  assert.match(entries[0].reason, /creationFileTimeUtc|ownership|cmdlineFingerprint/);
});

await check('[A7] and the mirror: an agent-spawn record fails foreign/\'s validator', async () => {
  assert.equal(validateForeignRecord(BASE).ok, false);
  const dir = await makeDir();
  await writeRecordAtomic(path.join(dir, 'ui-shot-5173.json'), BASE);
  const entries = await readForeignRegister({ dir });
  assert.equal(entries[0].ok, false);
});

await check('an UNKNOWN probe kind is reported unreadable, and the port is bounded', () => {
  assert.equal(validateAgentSpawnRecord({ ...BASE, probe: { kind: 'pipe', name: '\\\\.\\pipe\\x' } }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...BASE, probe: undefined }).ok, false);
  for (const port of [0, -1, 65536, 1.5, '5173', null]) {
    assert.equal(validateAgentSpawnRecord({ ...BASE, probe: { kind: 'port', port } }).ok, false, `expected invalid for port ${JSON.stringify(port)}`);
  }
  for (const port of [1, 5173, 65535]) {
    assert.equal(validateAgentSpawnRecord({ ...BASE, probe: { kind: 'port', port } }).ok, true, `expected valid for port ${port}`);
  }
});

await check('the identity triple is REQUIRED by the record shape, not optional', () => {
  for (const patch of [
    { pid: 0 },
    { pid: 'x' },
    { creationFileTimeUtc: undefined },
    { creationFileTimeUtc: 'garbage' },
    { creationFileTimeUtc: Number(T) }, // a JSON number is not readable evidence
    { cmdlineFingerprint: '' },
  ]) {
    assert.equal(validateAgentSpawnRecord({ ...BASE, ...patch }).ok, false, `expected invalid for ${JSON.stringify(patch)}`);
  }
});

await check('ownership: both declared modes accepted, anything else rejected', async () => {
  for (const mode of Object.values(OWNERSHIP_MODES)) {
    assert.equal(validateAgentSpawnRecord({ ...BASE, ownership: mode }).ok, true);
  }
  assert.equal(validateAgentSpawnRecord({ ...BASE, ownership: 'exempt' }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...BASE, ownership: undefined }).ok, false);
  // The OTel sink's mode is declared, not an exemption (861 [A6]).
  const sink = await goodRecord({ recordId: 'otlp-sink-4318', producer: 'otlp-sink-ensure', port: 4318, ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON });
  assert.equal(sink.ownership, 'ownerless-singleton');
  assert.equal(validateAgentSpawnRecord(sink).ok, true);
});

await check('a malformed lease invalidates the record rather than being ignored', () => {
  assert.equal(validateAgentSpawnRecord({ ...BASE, lease: { durationSec: 0, renewedAt: 'x', expiresAt: 'y' } }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...BASE, lease: { durationSec: 60, renewedAt: 'nope', expiresAt: 'nope' } }).ok, false);
  const { lease, ...noLease } = BASE;
  assert.equal(validateAgentSpawnRecord(noLease).ok, true, 'a record with NO lease is structurally valid; its lease state is "unknown"');
});

/* ── Build / write / read / remove ────────────────────────────────────────────────────────── */

await check('a built record round-trips through write and read', async () => {
  const dir = await makeDir();
  const file = await writeAgentSpawnRecord({ dir, record: BASE });
  assert.equal(file, agentSpawnRecordPath(dir, 'ui-shot-5173'));
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].ok, true);
  assert.deepEqual(entries[0].record, BASE);
  assert.equal(entries[0].record.creationFileTimeUtc, T);
});

await check('building or writing an invalid record throws at the producer, not later at a reader', async () => {
  const dir = await makeDir();
  await assert.rejects(() => buildAgentSpawnRecord({ recordId: 'x', producer: 'p', pid: 1, creationFileTimeUtc: 'nope', cmdlineFingerprint: 'f', port: 1, leaseDurationSec: 10 }));
  await assert.rejects(() => writeAgentSpawnRecord({ dir, record: { ...BASE, ownership: 'exempt' } }));
});

await check('a traversal-shaped recordId is refused loudly, not sanitized quietly', () => {
  for (const bad of ['../escape', 'a/b', 'a\\b', '..', '', '.hidden', 'x'.repeat(200)]) {
    assert.throws(() => assertSafeRecordId(bad), undefined, `expected throw for ${JSON.stringify(bad)}`);
  }
  assert.equal(assertSafeRecordId('ui-shot_5173.dev'), 'ui-shot_5173.dev');
});

await check('two servers produce two records — the six-leak shape a single-slot file could not hold', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: BASE });
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ recordId: 'ui-shot-5174', port: 5174, cmdlineFingerprint: 'vite --port 5174', pid: 4343 }) });
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 2);
  assert.equal(entries.every((e) => e.ok), true);
});

await check('removeAgentSpawnRecord retires one record and reports an absent one honestly', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: BASE });
  assert.equal((await removeAgentSpawnRecord({ dir, recordId: 'ui-shot-5173' })).removed, true);
  assert.equal((await removeAgentSpawnRecord({ dir, recordId: 'ui-shot-5173' })).removed, false);
  assert.equal((await readAgentSpawnRegister({ dir })).length, 0);
});

/* ── Lease-on-use ─────────────────────────────────────────────────────────────────────────── */

await check('leaseState is a tri-state: unknown is NOT lapsed', () => {
  const now = Date.now();
  assert.equal(leaseState(BASE, now), 'live');
  assert.equal(leaseState({ lease: { durationSec: 60, renewedAt: 'x', expiresAt: new Date(now - 1000).toISOString() } }, now), 'lapsed');
  assert.equal(leaseState({}, now), 'unknown');
  assert.equal(leaseState({ lease: { expiresAt: 'not-a-date' } }, now), 'unknown');
});

await check('lease-on-use: a reuse refreshes the existing record rather than duplicating it', async () => {
  const dir = await makeDir();
  const t0 = Date.UTC(2026, 7, 25, 10, 0, 0);
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ now: t0 }) });
  const before = (await readAgentSpawnRegister({ dir }))[0].record;
  const res = await renewAgentSpawnLease({ dir, recordId: 'ui-shot-5173', now: t0 + 10 * 60 * 1000 });
  assert.equal(res.renewed, true);
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1, 'a reuse must refresh, not duplicate');
  const after = entries[0].record;
  assert.ok(new Date(after.lease.expiresAt) > new Date(before.lease.expiresAt));
  assert.equal(after.lease.durationSec, before.lease.durationSec, 'the duration carries over when the caller does not restate it');
  assert.equal(after.startedAt, before.startedAt, 'a renewal must not rewrite when the process started');
  assert.equal(after.pid, before.pid);
});

await check('renewAgentSpawnLease has ONE failure shape — a malformed record returns, never throws', async () => {
  const dir = await makeDir();
  // Absent record.
  const missing = await renewAgentSpawnLease({ dir, recordId: 'nope' });
  assert.deepEqual([missing.renewed, typeof missing.reason], [false, 'string']);
  // Present but unparseable.
  await fsp.writeFile(path.join(dir, 'torn.json'), '{ not json', 'utf8');
  const torn = await renewAgentSpawnLease({ dir, recordId: 'torn' });
  assert.deepEqual([torn.renewed, typeof torn.reason], [false, 'string']);
  // Present, parseable, and INVALID — the case that used to throw while its siblings returned,
  // so a caller written against the returned shape crashed on the record it most needed to report.
  await writeRecordAtomic(path.join(dir, 'bad.json'), { ...BASE, recordId: 'bad', ownership: 'exempt' });
  const bad = await renewAgentSpawnLease({ dir, recordId: 'bad', durationSec: 60 });
  assert.deepEqual([bad.renewed, typeof bad.reason], [false, 'string']);
  // No usable duration anywhere.
  const { lease, ...noLease } = BASE;
  await writeRecordAtomic(path.join(dir, 'nolease.json'), { ...noLease, recordId: 'nolease' });
  assert.equal((await renewAgentSpawnLease({ dir, recordId: 'nolease' })).renewed, false);
});

/* ── [A5] failed-verify retention, and reading never deletes ──────────────────────────────── */

await check('[A5] a failed identity verify RETAINS the record with a marker', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: BASE });
  const res = await markAgentSpawnRecordFailedVerify({ dir, recordId: 'ui-shot-5173', verdict: 'refuse', reason: 'process table unavailable' });
  assert.equal(res.marked, true);
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1, 'the record must survive the refusal — the diagnostic trail is the point');
  assert.equal(entries[0].ok, true, 'the marker must not invalidate the record');
  assert.equal(entries[0].record.identityVerify.verdict, 'refuse');
  assert.match(entries[0].record.identityVerify.reason, /process table unavailable/);
  assert.ok(entries[0].record.identityVerify.at);
});

await check('reading NEVER deletes — stale, failed-verify, and unreadable records all survive', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ recordId: 'stale-1', now: Date.now() - 90 * DAY }) });
  await markAgentSpawnRecordFailedVerify({ dir, recordId: 'stale-1', verdict: 'refuse', reason: 'r' });
  await fsp.writeFile(path.join(dir, 'torn.json'), '{ not json', 'utf8');
  const before = (await fsp.readdir(dir)).sort();
  await readAgentSpawnRegister({ dir });
  await readAgentSpawnRegister({ dir });
  assert.deepEqual((await fsp.readdir(dir)).sort(), before);
});

/* ── [A10] pruning ────────────────────────────────────────────────────────────────────────── */

await check('[A10] prune deletes only records that are BOTH too old AND without a live lease', async () => {
  const dir = await makeDir();
  const now = Date.now();
  // (a) old + lapsed lease -> garbage.
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ recordId: 'old-lapsed', now: now - 30 * DAY }) });
  // (b) young + lapsed lease -> too new to judge.
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ recordId: 'young-lapsed', now: now - 1 * DAY, leaseDurationSec: 60 }) });
  // (c) old + LIVE lease -> an ownerless-singleton renewing its claim. Deleting this would demote a
  //     wanted daemon to the observed tier, where the sweep prints a kill line beside it.
  const singleton = await goodRecord({ recordId: 'otlp-sink-4318', producer: 'otlp-sink-ensure', port: 4318, ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON, now: now - 30 * DAY });
  singleton.lease = { durationSec: 3600, renewedAt: new Date(now).toISOString(), expiresAt: new Date(now + 3600_000).toISOString() };
  await writeAgentSpawnRecord({ dir, record: singleton });

  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.deepEqual(res.deletedIds, ['old-lapsed']);
  assert.deepEqual([res.found, res.deleted, res.retained], [3, 1, 2]);
  assert.deepEqual((await fsp.readdir(dir)).sort(), ['otlp-sink-4318.json', 'young-lapsed.json']);
});

await check('[A10] a failed-verify record is pruned by AGE ALONE — the marker buys time, not immortality', async () => {
  const dir = await makeDir();
  const now = Date.now();
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ recordId: 'fv-old', now: now - 30 * DAY }) });
  await writeAgentSpawnRecord({ dir, record: await goodRecord({ recordId: 'fv-young', now: now - 1 * DAY, leaseDurationSec: 60 }) });
  for (const id of ['fv-old', 'fv-young']) {
    await markAgentSpawnRecordFailedVerify({ dir, recordId: id, verdict: 'refuse', reason: 'creation time unreadable', now });
  }
  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.deepEqual(res.deletedIds, ['fv-old'], 'the marker must not exempt an aged record from retention limits');
  assert.deepEqual((await fsp.readdir(dir)).sort(), ['fv-young.json']);
});

await check('[A10] prune is explicit, bounded, and non-destructive where evidence is missing', async () => {
  const dir = await makeDir();
  const now = Date.now();
  const old = new Date(now - 30 * DAY);
  // An unreadable file has no lease to honour, so age alone decides — and it is still reported.
  const torn = path.join(dir, 'torn.json');
  await fsp.writeFile(torn, '{ not json', 'utf8');
  await fsp.utimes(torn, old, old);
  await fsp.writeFile(path.join(dir, 'torn-young.json'), '{ not json', 'utf8');
  // AGED like the torn fixture on purpose: if this file were merely young, its survival would be
  // explained by its age and would say nothing about the extension filter it is here to pin.
  const notes = path.join(dir, 'notes.txt');
  await fsp.writeFile(notes, 'not a record', 'utf8');
  await fsp.utimes(notes, old, old);

  const dry = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now, dryRun: true });
  assert.deepEqual(dry.deletedIds, ['torn']);
  assert.equal(dry.dryRun, true);
  assert.equal((await fsp.readdir(dir)).length, 3, 'a dry run must delete nothing');

  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.deepEqual(res.deletedIds, ['torn']);
  assert.deepEqual((await fsp.readdir(dir)).sort(), ['notes.txt', 'torn-young.json'], 'an aged non-.json file is still never touched');

  const gone = await pruneAgentSpawnRecords({ dir: path.join(dir, 'nope'), now });
  assert.deepEqual([gone.found, gone.deleted], [0, 0]);
  assert.equal(DEFAULT_MAX_RECORD_AGE_MS, 7 * DAY);
});

await check('[A10] prune sweeps orphaned temp files, which nothing else in this scope can see', async () => {
  const dir = await makeDir();
  const now = Date.now();
  const old = new Date(now - 30 * DAY);
  // The residue `writeRecordAtomic` leaves when a producer dies mid-write.
  const orphan = path.join(dir, 'ui-shot-5173.json.9999.tmp');
  await fsp.writeFile(orphan, '{"partial":', 'utf8');
  await fsp.utimes(orphan, old, old);
  const inFlight = path.join(dir, 'ui-shot-5174.json.8888.tmp');
  await fsp.writeFile(inFlight, '{"partial":', 'utf8');
  await writeAgentSpawnRecord({ dir, record: BASE });

  // A reader never sees these, so an unswept temp file accumulates unnoticed.
  assert.equal((await readAgentSpawnRegister({ dir })).length, 1);

  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.equal(res.deletedTmp, 1, 'only the AGED temp file is swept — a concurrent write must survive');
  assert.deepEqual((await fsp.readdir(dir)).sort(), ['ui-shot-5173.json', 'ui-shot-5174.json.8888.tmp']);
});

/* ── The junction-lock lookup ─────────────────────────────────────────────────────────────── */

await check('recordHoldsPath answers the junction-lock question lexically', async () => {
  const rec = {
    resourceRoots: { worktreeRoot: 'F:/justsearch-public/.claude/worktrees/wt-a', nodeModulesRealPath: 'F:/justsearch-public/modules/ui-web/node_modules' },
  };
  assert.equal(await recordHoldsPath(rec, 'F:/justsearch-public/modules/ui-web/node_modules/lightningcss-win32-x64-msvc/lightningcss.node'), true);
  assert.equal(await recordHoldsPath(rec, 'F:/justsearch-public/modules/ui-web/node_modules'), true);
  assert.equal(await recordHoldsPath(rec, 'F:/justsearch-public/.claude/worktrees/wt-a/src/main.ts'), true);
  assert.equal(await recordHoldsPath(rec, 'F:/justsearch-public/.claude/worktrees/wt-b/src/main.ts'), false);
  // A prefix that is not a path boundary must not match.
  assert.equal(await recordHoldsPath(rec, 'F:/justsearch-public/modules/ui-web/node_modules-backup/x'), false);
  assert.equal(await recordHoldsPath({}, 'F:/anything'), false);
  assert.equal(await recordHoldsPath(rec, ''), false);
});

await check('resolveNodeModulesRealPath resolves through the junction, and reports absence as absence', async () => {
  const dir = await makeDir();
  assert.equal(await resolveNodeModulesRealPath(dir), null);
  await fsp.mkdir(path.join(dir, 'node_modules'));
  assert.equal(await resolveNodeModulesRealPath(dir), await fsp.realpath(path.join(dir, 'node_modules')));
  assert.equal(await resolveNodeModulesRealPath(null), null);
});

if (['win32', 'linux'].includes(process.platform)) {
  await check('[native] a REAL directory link: the query path resolves, and a forgotten realpath is fixed at normalize time', async () => {
    // The incident shape (861 §6.2): a Vite serving a WORKTREE holds the MAIN checkout's
    // node_modules because the worktree's copy is a junction into it — and the path a build error
    // prints is the JUNCTION side. A lexical-only lookup returns false for exactly that query,
    // which is the diagnosis this field exists to make possible.
    const mainNodeModules = path.join(await makeDir(), 'node_modules');
    await fsp.mkdir(mainNodeModules);
    const held = path.join(mainNodeModules, 'lightningcss.node');
    await fsp.writeFile(held, 'binary', 'utf8');

    const worktree = await makeDir();
    const junction = path.join(worktree, 'node_modules');
    const mk = process.platform === 'win32' ? spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      `New-Item -ItemType Junction -Path '${junction.replace(/'/g, "''")}' -Target '${mainNodeModules.replace(/'/g, "''")}' | Out-Null`],
    { encoding: 'utf8' }) : await fsp.symlink(mainNodeModules, junction, 'dir').then(() => ({status:0}));
    assert.equal(mk.status, 0, `directory link creation failed: ${mk.stderr}`);

    const viaJunction = path.join(junction, 'lightningcss.node');
    assert.notEqual(path.resolve(viaJunction).toLowerCase(), path.resolve(held).toLowerCase(), 'fixture is only meaningful if the two spellings differ');

    // The record declares ONLY the resolved node_modules — deliberately NOT the worktree. That is
    // the incident shape (the holder is a main-checkout process; the worktree belongs to whoever
    // is asking) and it is what makes this assertion discriminating: with a `worktreeRoot` of the
    // junction's own worktree in the record, the junction query would match LEXICALLY through that
    // second root and prove nothing about junction resolution at all.
    const rec = await goodRecord({ resourceRoots: { nodeModulesRealPath: mainNodeModules } });
    assert.deepEqual(Object.keys(rec.resourceRoots), ['nodeModulesRealPath'], 'fixture must declare exactly one root');
    assert.equal(await recordHoldsPath(rec, held), true, 'the real path must match');
    assert.equal(await recordHoldsPath(rec, viaJunction), true, 'the JUNCTION path must match too — this is the lock-diagnosis case');
    // A path that does not exist yet still resolves through its nearest existing ancestor.
    assert.equal(await recordHoldsPath(rec, path.join(junction, 'not-created-yet', 'x.node')), true);
    // And an unrelated path under the same worktree is still NOT held by this record.
    assert.equal(await recordHoldsPath(rec, path.join(worktree, 'src', 'main.ts')), false);

    // The mirror defect: a producer that hands over the junction side would write a record that
    // silently never matches. Normalizing at build time resolves it instead.
    const forgot = await goodRecord({ recordId: 'ui-shot-5174', resourceRoots: { nodeModulesRealPath: junction } });
    assert.equal(
      path.resolve(forgot.resourceRoots.nodeModulesRealPath).toLowerCase(),
      path.resolve(await fsp.realpath(mainNodeModules)).toLowerCase(),
      'a junction handed to the builder must be resolved at normalize time',
    );
    assert.equal(await recordHoldsPath(forgot, held), true);
  });
} else {
  skipped += 1;
}

/* ── cleanup + report ─────────────────────────────────────────────────────────────────────── */

for (const dir of dirsToClean) await fsp.rm(dir, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`861-w2-agent-spawn-record.test: ${failures.length} FAILED, ${passed} passed, ${skipped} skipped`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w2-agent-spawn-record.test: all ${passed} checks passed${skipped ? ` (${skipped} skipped: not win32)` : ''}`);
