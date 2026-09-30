#!/usr/bin/env node
//
// Tempdoc 844 §12.2 — unit tests for the honesty contract on the dev MCP surface:
// "a dev tool must not report state it did not verify, and must not report success it did not
// confirm; where it cannot verify something, it says so."
//
// Covered, in the order of §12.3:
//   B1  resolveDistRoot     — preflight/start resolve the SAME tree; bare worktree names resolve;
//                             a bad name is refused with the names that DO exist.
//   B3  probeForeignRuns    — null (did not look) is distinct from [] (looked, found nothing);
//                             owned vs observed-but-unowned never merge; unknown stays unknown.
//   D3  the foreign-run register — a producer's self-declared record supplies identity; a record
//                             its producer never retired reads as `stale`, never as live; an
//                             unregistered port still surfaces via the probe; a torn record is an
//                             explicit `unreadable` and does not crash or suppress its neighbours.
//   M1  readForeignRegister — only ENOENT/ENOTDIR yields []; any other readdir failure propagates
//                             so the caller reports null ("did not look").
//   M4  normalizeApiCallPath — the allowlist is applied to the path that will actually be SENT:
//                             dot segments, encoded dot segments and `//host` cannot slip past it.
//   S3  resolveForeignRegisterDir — the reader follows JUSTSEARCH_DEV_RUNNER_STATE_ROOT, as the
//                             producer that writes the records does.
//   B4a projectJsonPath     — a miss returns the available keys, never the body; array indexing.
//   B4b truncationNotice    — a maxBytes overrun is a declared truncation, not a failed call.
//
// Pure unit tests: no dev stack, no subprocess, no network (the port probe is injected).
//
// Run: node scripts/dev/test-dev-mcp-surface-honesty.mjs
//

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  API_CALL_ALLOWLIST,
  FOREIGN_BACKEND_PORTS,
  FOREIGN_REGISTER_RELPOSIX,
  normalizeApiCallPath,
  parseJsonPathExpr,
  probeForeignRuns,
  readForeignRegister,
  projectJsonPath,
  resolveDistRoot,
  resolveForeignRegisterDir,
  truncationNotice,
} from './justsearch-dev-mcp/server.mjs';
import { buildDevRunnerArgsStart } from './justsearch-dev-mcp/cli.mjs';
import { createRequire } from 'node:module';

// Fail closed: non-zero until the runner at the bottom clears it, so a top-level abort cannot leave
// a green result that ran only part of the suite.
process.exitCode = 1;

const HERE = import.meta.dirname;
// dev-runner.cjs exports its __test seam only when it is not the entry module, so requiring it
// here runs no supervisor (same access path as test-dev-runner-lease-duration.mjs).
const devRunnerModule = createRequire(import.meta.url)(path.join(HERE, 'dev-runner.cjs'));

/* ── B1: the tree preflight checks is the tree start launches from ─────────────────────────── */

/** A throwaway main-repo shape: `<tmp>/main` with two worktrees, one of them not a checkout. */
async function makeFixture() {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-844-'));
  const main = path.join(root, 'main');
  await fsp.mkdir(path.join(main, 'scripts', 'dev'), { recursive: true });
  await fsp.writeFile(path.join(main, 'scripts', 'dev', 'dev-runner.cjs'), '// fixture\n', 'utf8');
  for (const name of ['round14', 'validate-main']) {
    const wt = path.join(main, '.claude', 'worktrees', name);
    await fsp.mkdir(path.join(wt, 'scripts', 'dev'), { recursive: true });
    await fsp.writeFile(path.join(wt, 'scripts', 'dev', 'dev-runner.cjs'), '// fixture\n', 'utf8');
  }
  // A worktree directory that is NOT a usable checkout (no dev-runner.cjs).
  await fsp.mkdir(path.join(main, '.claude', 'worktrees', 'half-made'), { recursive: true });
  return { root, main };
}

const fx = await makeFixture();
const CALLER = path.join(fx.main, '.claude', 'worktrees', 'round14');

const distTests = [
  ['distFrom omitted → the caller checkout, unchanged behaviour', async () => {
    const r = await resolveDistRoot({ distFrom: undefined, mainRepoRoot: fx.main, fallbackRepoRoot: CALLER, fallbackDevRunnerPath: 'X' });
    assert.equal(r.ok, true);
    assert.equal(r.repoRoot, CALLER);
    assert.equal(r.devRunnerPath, 'X');
    assert.equal(r.distFrom, null);
    assert.equal(r.resolvedVia, 'caller-checkout');
  }],
  ['a bare worktree NAME resolves against .claude/worktrees (was INVALID_DIST_FROM)', async () => {
    const r = await resolveDistRoot({ distFrom: 'validate-main', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, true);
    assert.equal(r.resolvedVia, 'worktree-name');
    assert.equal(r.repoRoot, path.join(fx.main, '.claude', 'worktrees', 'validate-main'));
  }],
  ['preflight and start resolve the SAME root for the same distFrom', async () => {
    const asStart = await resolveDistRoot({ distFrom: 'round14', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main, fallbackDevRunnerPath: 'a' });
    const asPreflight = await resolveDistRoot({ distFrom: 'round14', mainRepoRoot: fx.main, fallbackRepoRoot: CALLER, fallbackDevRunnerPath: 'b' });
    assert.equal(asStart.repoRoot, asPreflight.repoRoot);
    // …and it is NOT the invoking checkout — the false green in §5.1 was checking that one.
    assert.notEqual(asPreflight.repoRoot, fx.main);
  }],
  ['a relative path to a worktree still works', async () => {
    const r = await resolveDistRoot({ distFrom: path.join('.claude', 'worktrees', 'round14'), mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, true);
    assert.equal(r.resolvedVia, 'path');
    assert.equal(r.repoRoot, CALLER);
  }],
  ['the main repo itself is accepted', async () => {
    const r = await resolveDistRoot({ distFrom: fx.main, mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, true);
    assert.equal(r.repoRoot, fx.main);
  }],
  ['an unknown bare name is refused, and the refusal lists the names that DO exist', async () => {
    const r = await resolveDistRoot({ distFrom: 'no-such-tree', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'INVALID_DIST_FROM');
    assert.match(r.error.message, /round14/);
    assert.match(r.error.message, /validate-main/);
  }],
  ['a directory outside the repo is still refused', async () => {
    const r = await resolveDistRoot({ distFrom: path.join(fx.root, 'elsewhere'), mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'INVALID_DIST_FROM');
  }],
  ['a worktree directory without dev-runner.cjs is refused, naming the missing file', async () => {
    const r = await resolveDistRoot({ distFrom: 'half-made', mainRepoRoot: fx.main, fallbackRepoRoot: fx.main });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'INVALID_DIST_FROM');
    assert.match(r.error.message, /dev-runner\.cjs not found/);
  }],
];

/* ── B3: "free" must not be claimed about a machine that was not looked at ─────────────────── */

/** An injectable probe: 200 for the listed ports, null (nothing listening) otherwise. */
const listening = (...ports) => async (url) => {
  const port = Number(new URL(url).port);
  return ports.includes(port) ? 200 : null;
};

const foreignTests = [
  ['probing off → null, NOT [] — "I did not look" is not "I found nothing"', async () => {
    const r = await probeForeignRuns({ enabled: false, hasActiveRun: false, probe: listening(33221) });
    assert.equal(r, null);
  }],
  ['probed, nothing listening → [] (looked, found nothing)', async () => {
    const r = await probeForeignRuns({ enabled: true, hasActiveRun: false, probe: listening() });
    assert.deepEqual(r, []);
  }],
  ['an UNREGISTERED backend on 33221 is reported as observed-but-unowned', async () => {
    const r = await probeForeignRuns({ enabled: true, hasActiveRun: false, probe: listening(33221) });
    assert.equal(r.length, 1);
    // `source` (tempdoc 844 D3) distinguishes this from a self-declared run; the assertion stays
    // exhaustive so a silently-added field still fails here.
    assert.deepEqual(r[0], { port: 33221, kind: 'backend', probePath: '/api/status', attribution: 'unowned', source: 'observed' });
  }],
  ['an UNREGISTERED backend returning 503 is still reported as a live listener', async () => {
    const r = await probeForeignRuns({
      enabled: true,
      hasActiveRun: false,
      ports: [33221],
      inferencePort: 8080,
      probe: async (url) => Number(new URL(url).port) === 33221 ? 503 : null,
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].port, 33221);
    assert.equal(r[0].source, 'observed');
  }],
  ['UNKNOWN owned-run state never guesses that an observed backend is unowned', async () => {
    const r = await probeForeignRuns({
      enabled: true,
      ownedRunState: 'UNKNOWN',
      ports: [33221],
      inferencePort: 8080,
      probe: listening(33221),
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].source, 'observed');
    assert.equal(r[0].attribution, 'unknown');
  }],
  ['33221 is the DEFAULT probed port (jseval binds it hardcoded)', () => {
    assert.ok(FOREIGN_BACKEND_PORTS.includes(33221));
  }],
  ['the OWNED run\'s own api port is never reported as foreign', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, ownedApiPort: 33221, ports: [33221], probe: listening(33221),
    });
    assert.deepEqual(r, []);
  }],
  ['the owned run\'s own llama-server is not reported as foreign when aiActive is true', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, aiActive: true, inferencePort: 8080, probe: listening(8080),
    });
    assert.deepEqual(r, []);
  }],
  ['an inference listener the run disclaims (aiActive false) is unowned', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, aiActive: false, inferencePort: 8080, probe: listening(8080),
    });
    assert.equal(r[0].attribution, 'unowned');
    assert.equal(r[0].kind, 'inference');
  }],
  ['an inference listener that cannot be attributed says "unknown", not "unowned"', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, aiActive: null, inferencePort: 8080, probe: listening(8080),
    });
    assert.equal(r[0].attribution, 'unknown');
  }],
  ['with no active run at all, an inference listener is definitely unowned', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, aiActive: null, inferencePort: 8080, probe: listening(8080),
    });
    assert.equal(r[0].attribution, 'unowned');
  }],
  ['a probe that throws yields null (did not look), not [] (found nothing)', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, probe: async () => { throw new Error('probe exploded'); },
    });
    assert.equal(r, null);
  }],
  ['no subprocess: probeForeignRuns only calls the injected probe', async () => {
    const seen = [];
    await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [33221], inferencePort: 8080,
      probe: async (url) => { seen.push(url); return null; },
    });
    assert.deepEqual(seen.sort(), ['http://127.0.0.1:33221/api/status', 'http://127.0.0.1:8080/health']);
  }],
];

/* ── D3: a producer's own record, and the staleness a crash leaves behind ──────────────────── */
//
// The records are written to a fixture directory the same way `run_register.py` writes them, so
// this exercises the REAL reader end-to-end (parse, version check, liveness merge) rather than a
// mock of it. No backend is started; every state is produced by writing a file.

const REC = (over = {}) => ({
  schemaVersion: 1,
  producer: 'jseval',
  recordId: 'jseval-4242',
  pid: 4242,
  ports: { api: 33221 },
  repoRoot: 'F:\\justsearch-public\\.claude\\worktrees\\round14',
  dataDir: 'F:\\justsearch-public\\tmp\\headless-eval-data',
  workload: 'eval-backend',
  inferenceRequested: false,
  gpuBound: 'unverified',
  sessionId: 'b73007cd-907a-48da-8d74-2379da83be8f',
  startedAt: '2026-08-19T09:00:00Z',
  ...over,
});

/** Write `files` (name → string | object) into a fresh register directory; returns its path. */
async function makeRegister(files) {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-844-reg-'));
  for (const [name, body] of Object.entries(files)) {
    await fsp.writeFile(
      path.join(dir, name),
      typeof body === 'string' ? body : JSON.stringify(body, null, 2),
      'utf8',
    );
  }
  registerDirsToClean.push(dir);
  return dir;
}
const registerDirsToClean = [];

const deadPid = () => false;
const alivePid = () => true;

const registerTests = [
  ['a live registered record is reported with IDENTITY, not just a port', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r.length, 1);
    const f = r[0];
    assert.equal(f.source, 'registered');
    assert.equal(f.state, 'live');
    assert.equal(f.port, 33221);
    assert.equal(f.producer, 'jseval');
    assert.equal(f.pid, 4242);
    assert.match(f.repoRoot, /round14/);
    assert.equal(f.attribution, 'unowned');
    assert.equal(f.recordFile, 'tmp/dev-runner/foreign/jseval-4242.json');
    assert.deepEqual(f.liveness, { portAnswered: true, pidAlive: true });
  }],
  ['a registered backend returning 503 remains live and keeps its identity', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true,
      hasActiveRun: false,
      registerDir,
      isPidAlive: alivePid,
      probe: async (url) => Number(new URL(url).port) === 33221 ? 503 : null,
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].state, 'live');
    assert.equal(r[0].source, 'registered');
    assert.equal(r[0].producer, 'jseval');
    assert.deepEqual(r[0].liveness, { portAnswered: true, pidAlive: true });
  }],
  ['UNKNOWN owned-run state never guesses that a registered backend is unowned', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true,
      ownedRunState: 'UNKNOWN',
      registerDir,
      isPidAlive: alivePid,
      probe: listening(33221),
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].source, 'registered');
    assert.equal(r[0].attribution, 'unknown');
  }],
  ['a live record has no identityStale flag when its pid is alive', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r[0].identityStale, undefined);
  }],
  ['a port that answers behind a DEAD recorded pid is flagged: the listener is verified, the identity is not', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: deadPid, probe: listening(33221),
    });
    assert.equal(r[0].state, 'live');
    assert.equal(r[0].identityStale, true);
    assert.deepEqual(r[0].liveness, { portAnswered: true, pidAlive: false });
  }],
  ['gpuBound is passed through VERBATIM — the reader never upgrades "unverified" to a claim', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r[0].gpuBound, 'unverified');
    assert.equal(r[0].inferenceRequested, false);
  }],
  ['a record whose pid is DEAD and whose port is silent is `stale`, never `live`', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: deadPid, probe: listening(),
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].state, 'stale');
    assert.notEqual(r[0].state, 'live');
    assert.deepEqual(r[0].liveness, { portAnswered: false, pidAlive: false });
  }],
  ['…and the stale record is REPORTED, not silently deleted', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: deadPid, probe: listening(),
    });
    const still = await fsp.readdir(registerDir);
    assert.deepEqual(still, ['jseval-4242.json'], 'reading must not mutate another lifecycle\'s state');
  }],
  ['a record whose pid is ALIVE but whose port is silent is `unreachable` — booting or wedged, not live', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(),
    });
    assert.equal(r[0].state, 'unreachable');
    assert.deepEqual(r[0].liveness, { portAnswered: false, pidAlive: true });
  }],
  ['a registered port is reported ONCE, with identity — never also as a bare observation', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r.filter((f) => f.port === 33221).length, 1);
    assert.equal(r.filter((f) => f.source === 'observed').length, 0);
  }],
  ['an UNREGISTERED reachable port still appears via the probe (the register only covers what registered)', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC({ pid: 7, recordId: 'jseval-7', ports: { api: 39999 } }) });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [33221], registerDir, isPidAlive: deadPid, probe: listening(33221),
    });
    const observed = r.filter((f) => f.source === 'observed');
    assert.equal(observed.length, 1);
    assert.deepEqual(observed[0], { port: 33221, kind: 'backend', probePath: '/api/status', attribution: 'unowned', source: 'observed' });
  }],
  ['a torn / corrupt record does not crash the reader — it is an explicit `unreadable`', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': '{"schemaVersion": 1, "pid": 42' });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: deadPid, probe: listening(),
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].state, 'unreadable');
    assert.equal(r[0].source, 'registered');
    assert.equal(r[0].port, null);
    assert.equal(r[0].attribution, 'unknown');
    assert.ok(r[0].reason, 'an unreadable record must say WHY');
  }],
  ['a corrupt record does not suppress the healthy ones beside it', async () => {
    const registerDir = await makeRegister({
      'jseval-1.json': 'not json at all',
      'jseval-4242.json': REC(),
    });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r.length, 2);
    assert.equal(r.filter((f) => f.state === 'live').length, 1);
    assert.equal(r.filter((f) => f.state === 'unreadable').length, 1);
  }],
  ['a record from a FUTURE schema version is unreadable, not guessed at', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC({ schemaVersion: 99 }) });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r[0].state, 'unreadable');
    assert.match(r[0].reason, /schemaVersion/);
  }],
  ['a record with no usable ports.api is unreadable rather than probed on a guess', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC({ ports: {} }) });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [], registerDir, isPidAlive: alivePid, probe: listening(),
    });
    assert.equal(r[0].state, 'unreadable');
    assert.match(r[0].reason, /ports\.api/);
  }],
  ['probing OFF still yields null even with records present — the tri-state survives D3', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: false, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r, null);
  }],
  ['no register directory at all → today\'s probe behaviour, unchanged', async () => {
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: false, registerDir: path.join(os.tmpdir(), 'jsdev-844-does-not-exist'),
      isPidAlive: deadPid, probe: listening(33221),
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].source, 'observed');
  }],
  ['a registered port is probed even when it is NOT in the fixed port list', async () => {
    const registerDir = await makeRegister({ 'jseval-9.json': REC({ pid: 9, recordId: 'jseval-9', ports: { api: 40404 } }) });
    const seen = [];
    await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [33221], inferencePort: 8080, registerDir,
      isPidAlive: deadPid, probe: async (url) => { seen.push(url); return null; },
    });
    assert.ok(seen.includes('http://127.0.0.1:40404/api/status'), 'a declared port must be verified, not assumed');
  }],
  ['a record claiming the OWNED api port cannot be attributed — unknown, not unowned', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const r = await probeForeignRuns({
      enabled: true, hasActiveRun: true, ownedApiPort: 33221, ports: [33221], registerDir,
      isPidAlive: alivePid, probe: listening(33221),
    });
    assert.equal(r.length, 1);
    assert.equal(r[0].attribution, 'unknown');
  }],
  ['the register read spawns nothing and only touches the injected probe', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': REC() });
    const seen = [];
    await probeForeignRuns({
      enabled: true, hasActiveRun: false, ports: [33221], inferencePort: 8080, registerDir,
      isPidAlive: alivePid, probe: async (url) => { seen.push(url); return null; },
    });
    assert.deepEqual(seen.sort(), ['http://127.0.0.1:33221/api/status', 'http://127.0.0.1:8080/health']);
  }],
  ['readForeignRegister returns [] for a missing directory rather than throwing', async () => {
    assert.deepEqual(await readForeignRegister({ dir: path.join(os.tmpdir(), 'jsdev-844-nope') }), []);
    assert.deepEqual(await readForeignRegister({ dir: null }), []);
  }],
  ['readForeignRegister declares that it stopped early rather than truncating in silence', async () => {
    const files = {};
    for (let i = 0; i < 5; i += 1) files[`jseval-${i}.json`] = REC({ pid: i, recordId: `jseval-${i}` });
    const registerDir = await makeRegister(files);
    const entries = await readForeignRegister({ dir: registerDir, maxRecords: 2 });
    assert.equal(entries.filter((e) => e.ok).length, 2);
    const notice = entries.find((e) => !e.ok && e.recordId === '(register)');
    assert.match(notice.reason, /5 records present/);
  }],
  ['an oversized record is refused by size, not read into memory', async () => {
    const registerDir = await makeRegister({ 'jseval-4242.json': JSON.stringify({ schemaVersion: 1, pad: 'x'.repeat(2000) }) });
    const entries = await readForeignRegister({ dir: registerDir, maxBytes: 100 });
    assert.equal(entries[0].ok, false);
    assert.match(entries[0].reason, /too large/);
  }],
  ['the register directory is a SIBLING of the dev-runner\'s own state, never inside runs/', () => {
    assert.equal(FOREIGN_REGISTER_RELPOSIX, 'tmp/dev-runner/foreign');
    assert.ok(!FOREIGN_REGISTER_RELPOSIX.includes('/runs'), 'runs/ is the one directory dev-runner globs AND prunes');
  }],
  ['every enumeration site in dev-runner is a known one, so foreign/ cannot become one of its runs', async () => {
    // Tempdoc 844 M6: this was `!/readdir(Sync)?\(\s*stateRoot/` — a negative source regex that
    // could not catch the thing it named. Every real enumeration site passes a variable or a
    // path.join, so `readdir(path.join(stateRoot, …))` and `readdir(someVar)` both slipped past it
    // while the test kept reporting the invariant as enforced.
    //
    // An INVENTORY can be checked, where a negative pattern cannot: this pins the argument
    // expression of every readdir call site in the file. The invariant itself ("none of these is
    // the state root") is a human reading of that list — but a NEW enumeration cannot land without
    // failing here and forcing the reading to happen again, which is the enforceable half.
    const src = await fsp.readFile(path.join(HERE, 'dev-runner.cjs'), 'utf8');
    const sites = [...src.matchAll(/\breaddir(?:Sync)?\(\s*([^,)]+)/g)].map((m) => m[1].trim()).sort();
    assert.deepEqual(
      sites,
      [
        'd',                // newestClassMtimeMs — recursive walk of a build/classes dir (M3)
        'dir',              // cleanDataDir — a data dir
        'dir',              // authoredStoreTopLevelNames — a data dir
        'libDir',           // computeHeadDistStamp — modules/ui/build/install/ui/lib
        // Lane F stage A item A13: this was the indexer-worker dist lib dir; that distribution is
        // gone, so both sites now read the one Engine dist lib.
        'libDir',           // assessHotReloadClasspath — modules/ui/build/install/ui/lib (M3)
        'runsDirectory',    // pruneHistoricRuns — <stateRoot>/runs, the one dir it globs
        'srcDir',           // stageSharedCuda12 — the shared cuda12 staging source
      ].sort(),
      'a readdir call site in dev-runner.cjs changed. None may enumerate the state root itself: '
        + 'foreign/ is a SIBLING of runs/, and this lane\'s location argument depends on the '
        + 'dev-runner reading its own state files by exact name. Re-check the new site, then '
        + 'update this list.',
    );
    assert.ok(!/stateRoot,\s*['"]foreign['"]/.test(src), 'dev-runner must not read the foreign register');
  }],
  ['the reader honours JUSTSEARCH_DEV_RUNNER_STATE_ROOT, like the producer that writes the records', () => {
    // Tempdoc 844 S3: run_register.py:66-73 redirects the register under the override; this reader
    // hardcoded <main>/tmp/dev-runner/foreign, so with the override set it reported `[]` — "looked,
    // found nothing" — about a directory nothing ever writes to.
    const isolated = path.join(os.tmpdir(), 'jsdev-844-stateroot');
    assert.equal(
      resolveForeignRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: isolated }),
      path.join(path.resolve(isolated), 'foreign'));
    assert.equal(
      resolveForeignRegisterDir('F:/main', {}),
      path.join('F:/main', ...FOREIGN_REGISTER_RELPOSIX.split('/')));
    assert.equal(
      resolveForeignRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: '   ' }),
      path.join('F:/main', ...FOREIGN_REGISTER_RELPOSIX.split('/')));
  }],
  ['a readdir failure that is NOT "no such directory" propagates, so the caller reports null', async () => {
    // Tempdoc 844 M1: `catch { return []; }` asserted ENOENT and swallowed EACCES/EPERM/EMFILE too,
    // turning "I could not read the register" into "I read it and it was empty" — the tri-state
    // collapse this reader exists to prevent, in its own code.
    const throwing = (code) => () => { const e = new Error(`${code}: refused`); e.code = code; throw e; };
    for (const code of ['EACCES', 'EPERM', 'EMFILE', 'EIO']) {
      // eslint-disable-next-line no-await-in-loop
      await assert.rejects(
        readForeignRegister({ dir: path.join(os.tmpdir(), 'jsdev-844-nope'), readdir: throwing(code) }),
        new RegExp(code));
    }
    // ENOENT/ENOTDIR still mean "nothing has ever registered here", which IS a verified claim.
    for (const code of ['ENOENT', 'ENOTDIR']) {
      // eslint-disable-next-line no-await-in-loop
      assert.deepEqual(
        await readForeignRegister({ dir: path.join(os.tmpdir(), 'jsdev-844-nope'), readdir: throwing(code) }),
        []);
    }
    // …and probeForeignRuns turns that into `null` (did not look), never `[]`.
    const runs = await probeForeignRuns({
      enabled: true,
      hasActiveRun: false,
      registerDir: path.join(os.tmpdir(), 'jsdev-844-nope'),
      readRegister: async () => { const e = new Error('EPERM'); e.code = 'EPERM'; throw e; },
      probe: async () => 0,
    });
    assert.equal(runs, null, 'an unreadable register must be "did not look", not "found nothing"');
  }],
];

/* ── B4a: a typo must cost a hint, not the whole payload ───────────────────────────────────── */

const DOC = {
  llm: { model_path: 'C:/models/x.gguf', gpuLayers: 99 },
  results: [{ fields: { path: 'a.txt' } }, { fields: { path: 'b.txt' } }],
  empty: [],
  nil: null,
  count: 3,
};

const jsonPathTests = [
  ['a hit returns the projected subtree', () => {
    assert.deepEqual(projectJsonPath(DOC, 'llm'), { ok: true, value: DOC.llm });
    assert.deepEqual(projectJsonPath(DOC, 'llm.model_path'), { ok: true, value: 'C:/models/x.gguf' });
  }],
  ['array indexing works (a.b[0].c)', () => {
    assert.deepEqual(projectJsonPath(DOC, 'results[0].fields.path'), { ok: true, value: 'a.txt' });
    assert.deepEqual(projectJsonPath(DOC, 'results[1].fields'), { ok: true, value: { path: 'b.txt' } });
  }],
  ['parseJsonPathExpr splits keys and indices', () => {
    assert.deepEqual(parseJsonPathExpr('a.b[0].c'), ['a', 'b', 0, 'c']);
    assert.deepEqual(parseJsonPathExpr('a[0][1]'), ['a', 0, 1]);
    assert.equal(parseJsonPathExpr('a..b'), null);
    assert.equal(parseJsonPathExpr(''), null);
  }],
  ['a top-level miss names the available keys and does NOT carry the body', () => {
    const r = projectJsonPath(DOC, 'lmm');
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'JSON_PATH_MISS');
    assert.deepEqual(r.available.keys, ['llm', 'results', 'empty', 'nil', 'count']);
    for (const key of Object.keys(DOC)) assert.match(r.error.message, new RegExp(key));
    // The point of the fix: the payload itself is not in the answer.
    assert.ok(!JSON.stringify(r).includes('C:/models/x.gguf'), 'the response body must not be returned on a miss');
  }],
  ['a nested miss names the DEEPEST level that resolved', () => {
    const r = projectJsonPath(DOC, 'llm.modelPath');
    assert.equal(r.ok, false);
    assert.equal(r.resolvedPrefix, 'llm');
    assert.deepEqual(r.available.keys, ['model_path', 'gpuLayers']);
    assert.match(r.error.message, /model_path/);
  }],
  ['an out-of-range index reports the array length instead of keys', () => {
    const r = projectJsonPath(DOC, 'results[7].fields');
    assert.equal(r.ok, false);
    assert.equal(r.available.kind, 'array');
    assert.equal(r.available.length, 2);
    assert.match(r.error.message, /\[0\]\.\.\[1\]/);
  }],
  ['an empty array says so rather than offering an index', () => {
    const r = projectJsonPath(DOC, 'empty[0]');
    assert.equal(r.ok, false);
    assert.match(r.error.message, /empty/);
  }],
  ['descending into a scalar or null says what it is', () => {
    assert.match(projectJsonPath(DOC, 'count.deeper').error.message, /is number/);
    assert.match(projectJsonPath(DOC, 'nil.deeper').error.message, /is null/);
  }],
  ['a malformed expression is INVALID, not a silent miss', () => {
    const r = projectJsonPath(DOC, 'llm..model_path');
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'JSON_PATH_INVALID');
  }],
  ['many keys are capped but the total is declared', () => {
    const wide = Object.fromEntries(Array.from({ length: 80 }, (_, i) => [`k${i}`, i]));
    const r = projectJsonPath(wide, 'nope');
    assert.equal(r.available.keys.length, 50);
    assert.equal(r.available.keysTotal, 80);
  }],
];

/* ── B4b: exceeding maxBytes is a declared truncation, not a failed call ───────────────────── */

const truncationTests = [
  ['the notice names the bytes read, the limit, and that it was truncated', () => {
    const n = truncationNotice({ bytesRead: 2000, maxBytes: 2000 });
    assert.equal(n.code, 'RESPONSE_TRUNCATED');
    assert.match(n.message, /TRUNCATED/);
    assert.match(n.message, /2000 bytes/);
    assert.match(n.message, /maxBytes=2000/);
  }],
  ['the notice corrects the misconception that maxBytes shrinks output', () => {
    const n = truncationNotice({ bytesRead: 2000, maxBytes: 2000 });
    assert.match(n.message, /does NOT parse as JSON/);
    assert.match(n.message, /jsonPath/);
  }],
];

/* ── B2: a fully-understood, recoverable condition is not an unhandled exception ───────────── */
//
// Honest limit: exercising this end-to-end means running `dev-runner start`, i.e. launching a
// stack, which these tests deliberately do not do. What IS asserted here is the classification and
// the transport contract that carries it — the throw site attaches a code, and the `--json`
// writer forwards `err.code`/`err.details` instead of flattening everything to UNHANDLED. The
// writer's default branch is separately observable with `node scripts/dev/dev-runner.cjs bogus --json`.

const devRunnerSrc = await fsp.readFile(path.join(HERE, 'dev-runner.cjs'), 'utf8');

const distCodeTests = [
  ['the missing-Head-dist throw carries code DIST_NOT_BUILT', () => {
    const site = devRunnerSrc.slice(devRunnerSrc.indexOf('Head dist not found at'));
    assert.match(site.slice(0, 900), /err\.code = 'DIST_NOT_BUILT'/);
  }],
  ['…and structured context: the offending path and the remedy', () => {
    const site = devRunnerSrc.slice(devRunnerSrc.indexOf('Head dist not found at'));
    assert.match(site.slice(0, 900), /err\.details = \{ distPath: startScript, repoRoot, remedy \}/);
  }],
  ['the --json writer forwards a classified code rather than flattening to UNHANDLED', () => {
    assert.match(devRunnerSrc, /code: err\?\.code \|\| 'UNHANDLED'/);
  }],
  ['…and forwards err.details so a consumer need not scrape the message', () => {
    assert.match(devRunnerSrc, /\.\.\.\(err\?\.details \? \{ details: err\.details \} : \{\}\)/);
  }],
  ['the documented error-code table lists DIST_NOT_BUILT and INVALID_DIST_FROM', async () => {
    const doc = await fsp.readFile(
      path.join(HERE, '..', '..', 'docs', 'reference', 'contributing', 'mcp-dev-tools.md'), 'utf8');
    const section = doc.slice(doc.indexOf('## Start-Tool Error Codes'));
    assert.match(section, /`DIST_NOT_BUILT`/);
    assert.match(section, /`INVALID_DIST_FROM`/);
    assert.match(section, /`START_TIMED_OUT`/);
  }],
  ['M2: a start timeout cannot synthesize ok:true from whatever runId is in active.json', async () => {
    // The removed block read active.json and, if ANY runId was there, returned
    // `{ ok: true, runId, apiPort: runJson?.apiPortActual ?? 0, … }` — verifying neither that the
    // run belonged to this call nor that anything answered. Source-structural (the handler is a
    // closure inside registerTool), so it asserts on both the absence of the old shape and the
    // presence of the new one.
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    const site = src.slice(src.indexOf('START_TIMED_OUT') - 3000, src.indexOf('START_TIMED_OUT') + 2500);
    assert.doesNotMatch(src, /ok: true, recovered: true/, 'the recovered-as-success NDJSON event must be gone');
    assert.doesNotMatch(src, /apiPort: runJson\?\.apiPortActual \?\? 0/, 'the synthesized success payload must be gone');
    assert.match(site, /readinessConfirmed: false/);
    assert.match(site, /observed/);
    assert.match(site, /Readiness was NOT confirmed/);
    assert.match(site, /timedOut: true/);
  }],
];

/* ── M4: the allowlist is api_call's only boundary, so it must see the path that is sent ───── */

/** What the handler does: shape check, then allowlist, then the URL-stability assertion. */
function admitApiCallPath(rawPath, base = 'http://127.0.0.1:33301') {
  const shape = normalizeApiCallPath(rawPath);
  if (!shape.ok) return { admitted: false, why: `shape: ${shape.reason}` };
  const entry = API_CALL_ALLOWLIST.find(
    (e) => e.path === rawPath || (e.pattern instanceof RegExp && e.pattern.test(rawPath)));
  if (!entry) return { admitted: false, why: 'not allowlisted' };
  const sent = new URL(rawPath, base).pathname;
  if (sent !== rawPath) return { admitted: false, why: `resolves to ${sent}` };
  return { admitted: true, sent };
}

/* ── 913 T1: the distFrom→provenance wiring the mismatch verdict reads ─────────────────────── */
//
// computeProvenanceMismatch is unit-tested in test-ownership-verdict.mjs. These pin the WIRING
// that puts `distFromRoot` in front of it: without the forward, that function is correct and the
// verdict is still wrong, which is the shape `audit-without-test` names.

const provenanceWiringTests = [
  ['start forwards the resolved distFrom root to the dev-runner', () => {
    const args = buildDevRunnerArgsStart({
      apiPort: 0, uiPort: 5173, clean: 'soft', distFromRoot: '/tmp/main/.claude/worktrees/lane-x',
    });
    assert.ok(args.includes('--dist-from=/tmp/main/.claude/worktrees/lane-x'),
      `--dist-from missing from ${JSON.stringify(args)}`);
  }],
  ['…and omits the flag entirely when no distFrom was asked for', () => {
    const args = buildDevRunnerArgsStart({ apiPort: 0, uiPort: 5173, clean: 'soft', distFromRoot: null });
    assert.ok(!args.some((a) => a.startsWith('--dist-from')),
      'a caller-checkout launch must not claim a requested root it never requested');
  }],
  ['the start handler only forwards it when distFrom was actually supplied', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /distFromRoot:\s*distRoot\.distFrom\s*\?\s*effRepoRoot\s*:\s*null/);
  }],
  ['dev-runner parses --dist-from into the option the provenance block reads', () => {
    const out = devRunnerModule.__test.parseArgs(
      ['start', '--api-port=0', '--dist-from=/tmp/main/.claude/worktrees/lane-x']);
    assert.equal(out.distFromRoot, '/tmp/main/.claude/worktrees/lane-x');
    assert.equal(devRunnerModule.__test.parseArgs(['start', '--api-port=0']).distFromRoot, null,
      'absent flag must stay null, not become the empty string');
  }],
  ['…and the provenance block stamped on the lease carries it', () => {
    assert.match(devRunnerSrc, /function resolveProvenance\(distFromRoot = null\)/);
    assert.match(devRunnerSrc, /distFromRoot:\s*distFromRoot\s*\?\s*toPosix\(path\.resolve\(distFromRoot\)\)\s*:\s*null/);
    assert.match(devRunnerSrc, /resolveProvenance\(opts\.distFromRoot\)/,
      'the spawn-time call must pass the parsed option — an unparameterised call would stamp null forever');
  }],
];

const allowlistTests = [
  // 913 T2: the two endpoints the allowlist was missing. GET-only, and the schemas route matches
  // by pattern because the schema name is a path segment.
  ['913 T2: the index-wide failed-jobs route is reachable, like its by-prefix sibling', () => {
    assert.equal(admitApiCallPath('/api/indexing-jobs/failed').admitted, true);
    assert.equal(admitApiCallPath('/api/indexing-jobs/failed/by-prefix').admitted, true);
  }],
  ['913 T2: /api/schemas/<name> is admitted, and is GET-only', () => {
    const r = admitApiCallPath('/api/schemas/failed-indexing-jobs-response.v1.json');
    assert.equal(r.admitted, true, r.why);
    assert.equal(r.sent, '/api/schemas/failed-indexing-jobs-response.v1.json');
    const entry = API_CALL_ALLOWLIST.find((e) => e.path === '/api/schemas/{name}');
    assert.deepEqual(entry.methods, ['GET'], 'a schema read must not be a mutation surface');
  }],
  ['913 T2: the schemas pattern does not admit a deeper path than the route serves', () => {
    // LocalApiServer serves /api/schemas/{name} — one segment. A two-segment path is not that
    // route, and admitting it would make the allowlist describe a surface that does not exist.
    assert.equal(admitApiCallPath('/api/schemas/nested/thing.json').admitted, false);
  }],
  ['a legitimate package id is still admitted, and sent verbatim', () => {
    const r = admitApiCallPath('/api/ai/install/packages/chat-compact_v1.2/decline');
    assert.equal(r.admitted, true, r.why);
    assert.equal(r.sent, '/api/ai/install/packages/chat-compact_v1.2/decline');
    assert.equal(admitApiCallPath('/api/settings/v2').admitted, true);
  }],
  ['".." in a path parameter no longer escapes the allowlist', () => {
    // The live bug: `[A-Za-z0-9._-]+` matches "..", and the match happened BEFORE
    // `new URL(path, base)` normalized it — so this passed the boundary and was SENT as
    // /api/ai/install/decline, a different endpoint than the one that was checked.
    const r = admitApiCallPath('/api/ai/install/packages/../decline');
    assert.equal(r.admitted, false);
    assert.match(r.why, /"\.\." segment/);
  }],
  ['a "." segment is refused too', () => {
    assert.equal(admitApiCallPath('/api/ai/install/packages/./decline').admitted, false);
    assert.equal(admitApiCallPath('/api/./settings/v2').admitted, false);
  }],
  ['percent-encoded dot segments are refused (the decode happens before the check)', () => {
    for (const p of [
      '/api/ai/install/packages/%2e%2e/decline',
      '/api/ai/install/packages/%2E%2E/decline',
      '/api/ai/install/packages/%2e/decline',
      '/api/ai/install/packages/a%2fb/decline',
    ]) {
      assert.equal(admitApiCallPath(p).admitted, false, `${p} must not be admitted`);
    }
  }],
  ['a protocol-relative path cannot redirect the call off loopback', () => {
    const r = admitApiCallPath('//example.com/api/settings/v2');
    assert.equal(r.admitted, false);
    assert.match(r.why, /empty segment/);
  }],
  ['a relative path, a backslash and a trailing slash are all refused', () => {
    assert.match(normalizeApiCallPath('api/settings/v2').reason, /must start with "\/"/);
    assert.match(normalizeApiCallPath('/api\\settings').reason, /backslash/);
    assert.match(normalizeApiCallPath('/api/settings/v2/').reason, /empty segment/);
    assert.match(normalizeApiCallPath('/api/%zz/v2').reason, /percent-encoding/);
    assert.match(normalizeApiCallPath('').reason, /non-empty/);
  }],
  ['the handler applies the same three steps in the same order this test mirrors', async () => {
    // admitApiCallPath above mirrors the handler's SEQUENCING (the handler is a closure inside
    // registerTool and cannot be imported); normalizeApiCallPath and API_CALL_ALLOWLIST are the
    // real ones. Pin the sequencing so the mirror cannot quietly stop describing the handler.
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    const shapeAt = src.indexOf('const shape = normalizeApiCallPath(input.path);');
    const allowAt = src.indexOf('const entry = API_CALL_ALLOWLIST.find(');
    const urlAt = src.indexOf('const resolvedUrl = new URL(input.path, base);');
    assert.ok(shapeAt > 0 && allowAt > 0 && urlAt > 0, 'all three steps must be present');
    assert.ok(shapeAt < allowAt, 'the shape check must run BEFORE the allowlist match');
    assert.ok(allowAt < urlAt, 'the URL-stability assertion must run after the allowlist match');
    assert.match(src, /if \(resolvedUrl\.pathname !== input\.path\)/);
  }],
  ['every allowlisted literal path passes the shape check it is now filtered through', () => {
    for (const e of API_CALL_ALLOWLIST) {
      if (e.pattern) continue; // `{packageId}` is a template, not a real path
      const shape = normalizeApiCallPath(e.path);
      assert.equal(shape.ok, true, `${e.path}: ${shape.reason}`);
      assert.equal(new URL(e.path, 'http://127.0.0.1:33301').pathname, e.path);
    }
  }],
];

/* ── run ───────────────────────────────────────────────────────────────────────────────────── */

let pass = 0;
let fail = 0;
for (const [name, fn] of [...distTests, ...distCodeTests, ...provenanceWiringTests, ...foreignTests, ...registerTests, ...allowlistTests, ...jsonPathTests, ...truncationTests]) {
  try {
    await fn();
    console.log(`  PASS  ${name}`);
    pass += 1;
  } catch (e) {
    console.error(`  FAIL  ${name}: ${e.message}`);
    fail += 1;
  }
}
await fsp.rm(fx.root, { recursive: true, force: true });
for (const dir of registerDirsToClean) await fsp.rm(dir, { recursive: true, force: true });
console.log(`test-dev-mcp-surface-honesty: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
