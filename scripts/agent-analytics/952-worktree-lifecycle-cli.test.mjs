/**
 * Real-Git regressions for the tempdoc 952 lifecycle CLI (`scripts/dev/worktree-lifecycle.cjs`).
 *
 * Every case builds a throwaway checkout under `os.tmpdir()`: a bare `origin`, a `git init`
 * checkout carrying a STAGED COPY of the production CLI and its libraries (the CLI reads its
 * policy from its OWN checkout root, `path.resolve(__dirname, '..', '..')`, so the copy is what
 * makes a fixture policy authoritative), and an isolated `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` for
 * finalization records and session ledgers. Nothing here writes to the JustSearch repository and
 * no fixture outlives the `finally` block.
 *
 * The `gh` stub is `JUSTSEARCH_GH_BIN=<node>` plus a CommonJS file named `pr` in the directory
 * `landing-receipt.cjs` runs gh from: `spawnSync(node, ['pr', 'list', ...], { cwd: repoRoot })`
 * executes `<repoRoot>/pr`, which answers from `JUSTSEARCH_TEST_GH_PRS` (branch -> PR row) and
 * prints `[]` for every other branch. A `.cmd` shim cannot serve as the stub: Node 24 refuses to
 * spawn `.cmd`/`.bat` without `shell: true` (EINVAL), which `lookupMergedPr` swallows into "no
 * merged PR" — every receipt would silently degrade to UNKNOWN.
 *
 * Run with: node scripts/agent-analytics/952-worktree-lifecycle-cli.test.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(HERE, '..', '..');
const require = createRequire(import.meta.url);

/** Staged into every fixture checkout: the CLI, its deletion primitive, and their libraries. */
const CLI_FILES = [
  'scripts/dev/worktree-lifecycle.cjs',
  'scripts/dev/remove-worktree.cjs',
  'scripts/dev/prepare-worktree.cjs',
  'scripts/dev/lib/process-identity.cjs',
  'scripts/dev/lib/process-record.cjs',
  'scripts/dev/lib/agent-spawn-record.cjs',
  'scripts/dev/lib/agent-spawn-reaper.cjs',
  'scripts/dev/lib/agent-spawn-sweep.cjs',
  'scripts/dev/lib/ownership-verdict.cjs',
  'scripts/dev/lib/worktree-archive.cjs',
  'scripts/dev/lib/worktree-register.cjs',
  'scripts/dev/lib/landing-receipt.cjs',
  'scripts/dev/justsearch-dev-mcp/observations.mjs',
  'scripts/dev/justsearch-dev-mcp/files.mjs',
  'scripts/dev/justsearch-dev-mcp/paths.mjs',
  'governance/worktree-lifecycle.v1.json',
];

const POLICY_REL = 'governance/worktree-lifecycle.v1.json';

/** The gh stub: a CommonJS file named `pr`, executed as `node pr list --head <branch> …`. */
const GH_STUB = `'use strict';
const argv = process.argv.slice(2);
const at = argv.indexOf('--head');
const branch = at === -1 ? null : argv[at + 1];
let rows = {};
try { rows = JSON.parse(process.env.JUSTSEARCH_TEST_GH_PRS || '{}'); } catch { rows = {}; }
const row = branch && Object.prototype.hasOwnProperty.call(rows, branch) ? rows[branch] : null;
process.stdout.write(JSON.stringify(row ? [row] : []));
`;

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (err) {
    failures.push(`${label}: ${err.stack || err}`);
  }
}

/* ── plumbing ─────────────────────────────────────────────────────────────────────────────── */

function run(command, args, { cwd, env } = {}) {
  const result = spawnSync(command, args, {
    cwd,
    env: { ...process.env, ...env },
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} exited ${result.status}:\n${result.stdout || ''}${result.stderr || ''}`);
  }
  return result.stdout || '';
}

function git(repo, ...args) {
  return run('git', args, { cwd: repo, env: { GIT_OPTIONAL_LOCKS: '0' } });
}

function gitTry(repo, ...args) {
  const r = spawnSync('git', args, { cwd: repo, encoding: 'utf8', env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' } });
  return { status: r.status, stdout: (r.stdout || '').trim(), stderr: (r.stderr || '').trim() };
}

function cfg(repo, key) {
  const r = gitTry(repo, 'config', '--get', key);
  return r.status === 0 ? r.stdout : null;
}

function branchConfigDump(repo) {
  return gitTry(repo, 'config', '--get-regexp', '^branch\\.').stdout;
}

function refSha(repo, ref) {
  const r = gitTry(repo, 'rev-parse', '--verify', '--quiet', ref);
  return r.status === 0 ? r.stdout : null;
}

function listRefs(repo, pattern) {
  return git(repo, 'for-each-ref', '--format=%(refname)', pattern)
    .split('\n').map((line) => line.trim()).filter(Boolean);
}

/** `git worktree list --porcelain -z` as `[{ path, branch, locked }]`. */
function worktreeEntries(repo) {
  const raw = git(repo, 'worktree', 'list', '--porcelain', '-z');
  const entries = [];
  let current = null;
  for (const field of raw.split('\0')) {
    if (field === '') {
      if (current) entries.push(current);
      current = null;
      continue;
    }
    const split = field.indexOf(' ');
    const key = split === -1 ? field : field.slice(0, split);
    const value = split === -1 ? null : field.slice(split + 1);
    if (key === 'worktree') {
      if (current) entries.push(current);
      current = { path: value, branch: null, locked: null };
    } else if (key === 'branch' && current) current.branch = String(value).replace(/^refs\/heads\//, '');
    else if (key === 'locked' && current) current.locked = value === null ? '(no reason supplied)' : value;
  }
  if (current) entries.push(current);
  return entries;
}

function entryFor(repo, worktreePath) {
  const wanted = path.resolve(worktreePath).replace(/\\/g, '/').toLowerCase();
  return worktreeEntries(repo).find((e) => path.resolve(e.path).replace(/\\/g, '/').toLowerCase() === wanted) || null;
}

/** A pid that is provably not running: spawn a process, wait for it, verify it is gone. */
function deadPid() {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const r = spawnSync(process.execPath, ['-e', 'process.exit(0)'], { encoding: 'utf8' });
    const pid = r.pid;
    if (!Number.isInteger(pid) || pid <= 0) continue;
    try {
      process.kill(pid, 0);
    } catch {
      return pid;
    }
  }
  throw new Error('could not obtain a pid that is reliably not running');
}

/* ── fixtures ─────────────────────────────────────────────────────────────────────────────── */

const fixtures = [];

async function stageCli(checkout, patchPolicy) {
  for (const rel of CLI_FILES) {
    const destination = path.join(checkout, ...rel.split('/'));
    await fsp.mkdir(path.dirname(destination), { recursive: true });
    await fsp.copyFile(path.join(SOURCE_ROOT, ...rel.split('/')), destination);
  }
  if (patchPolicy) {
    const file = path.join(checkout, ...POLICY_REL.split('/'));
    const policy = JSON.parse(await fsp.readFile(file, 'utf8'));
    await fsp.writeFile(file, `${JSON.stringify(patchPolicy(policy), null, 2)}\n`, 'utf8');
  }
}

async function makeFixture(label, { patchPolicy = null } = {}) {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), `wt-lifecycle-952-${label}-`));
  const origin = path.join(root, 'origin.git');
  const checkout = path.join(root, 'checkout');
  const stateRoot = path.join(root, 'state');
  const fixture = {
    root,
    origin,
    checkout,
    stateRoot,
    cli: path.join(checkout, 'scripts', 'dev', 'worktree-lifecycle.cjs'),
    sanctioned: path.join(checkout, '.claude', 'worktrees'),
  };
  fixtures.push(fixture);

  run('git', ['init', '--bare', '-b', 'main', origin], { cwd: root });
  await fsp.mkdir(checkout, { recursive: true });
  git(checkout, 'init', '-b', 'main');
  for (const [key, value] of [
    ['user.email', 'fixture@example.invalid'],
    ['user.name', 'Fixture'],
    ['core.autocrlf', 'false'],
    ['commit.gpgsign', 'false'],
    ['gc.auto', '0'],
    ['advice.detachedHead', 'false'],
  ]) git(checkout, 'config', key, value);

  await stageCli(checkout, patchPolicy);
  await fsp.writeFile(path.join(checkout, '.gitignore'), 'ignored/\nnode_modules/\ntmp/\n.claude/worktrees/\n', 'utf8');
  await fsp.writeFile(path.join(checkout, 'README.md'), 'fixture checkout\n', 'utf8');
  git(checkout, 'add', '.gitignore', 'README.md', 'scripts', 'governance');
  git(checkout, 'commit', '-m', 'fixture base');
  git(checkout, 'remote', 'add', 'origin', origin);
  git(checkout, 'push', 'origin', 'main');
  git(checkout, 'fetch', 'origin');

  // The gh stub lives where landing-receipt.cjs invokes gh from (the main checkout).
  await fsp.writeFile(path.join(checkout, 'pr'), GH_STUB, 'utf8');
  fixture.originMain = refSha(checkout, 'origin/main');
  return fixture;
}

function runCli(fixture, args, { env = {}, cwd = fixture.checkout } = {}) {
  const childEnv = { ...process.env };
  for (const key of ['CLAUDE_CODE_SESSION_ID', 'JUSTSEARCH_AGENT_SESSION_ID', 'CODEX_HOME', 'CODEX_SESSION_ID', 'JUSTSEARCH_TEST_GH_PRS']) {
    delete childEnv[key];
  }
  Object.assign(childEnv, {
    JUSTSEARCH_DEV_RUNNER_STATE_ROOT: fixture.stateRoot,
    JUSTSEARCH_GH_BIN: process.execPath,
    GIT_OPTIONAL_LOCKS: '0',
  }, env);
  const result = spawnSync(process.execPath, [fixture.cli, ...args], {
    cwd,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: childEnv,
  });
  const stdout = result.stdout || '';
  const stderr = result.stderr || '';
  return { status: result.status, stdout, stderr, out: `${stdout}${stderr}` };
}

function createWorktree(fixture, name, sessionId, extra = []) {
  const result = runCli(fixture, ['create', name, '--session-id', sessionId, ...extra]);
  assert.equal(result.status, 0, result.out);
  const dest = path.join(fixture.sanctioned, name);
  assert.equal(result.stdout.trim(), dest, result.out);
  return dest;
}

function commitIn(dir, file, content, message) {
  fs.mkdirSync(path.dirname(path.join(dir, file)), { recursive: true });
  fs.writeFileSync(path.join(dir, file), content, 'utf8');
  git(dir, 'add', '--', file);
  git(dir, 'commit', '-m', message);
  return refSha(dir, 'HEAD');
}

function writeLedger(fixture, sessionId, lastActivityAt) {
  const dir = path.join(fixture.stateRoot, 'sessions');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${sessionId}.json`), JSON.stringify({ lastActivityAt }), 'utf8');
}

function finalizationFile(fixture, resource) {
  return path.join(fixture.stateRoot, 'worktrees', `${resource}.json`);
}

function readFinalization(fixture, resource) {
  try {
    return JSON.parse(fs.readFileSync(finalizationFile(fixture, resource), 'utf8'));
  } catch {
    return null;
  }
}

function manifestFiles(fixture, resource) {
  const dir = path.join(fixture.checkout, 'tmp', 'archive', 'worktrees', resource);
  try {
    return fs.readdirSync(dir).filter((name) => name.endsWith('.json')).map((name) => path.join(dir, name));
  } catch {
    return [];
  }
}

function receiptLines(fixture) {
  try {
    return fs.readFileSync(path.join(fixture.checkout, 'tmp', 'agent-telemetry', 'landing-receipts.ndjson'), 'utf8')
      .split('\n').map((line) => line.trim()).filter(Boolean).map((line) => JSON.parse(line));
  } catch {
    return [];
  }
}

/** A landed worktree: a branch commit plus the identical change squashed onto origin/main. */
function landWorktree(fixture, name, sessionId, prNumber) {
  const dest = createWorktree(fixture, name, sessionId);
  const head = commitIn(dest, `${name}.txt`, `feature ${name}\n`, `feat: ${name}`);
  fs.writeFileSync(path.join(fixture.checkout, `${name}.txt`), `feature ${name}\n`, 'utf8');
  git(fixture.checkout, 'add', '--', `${name}.txt`);
  git(fixture.checkout, 'commit', '-m', `feat(${name}): squashed (#${prNumber})`);
  git(fixture.checkout, 'push', 'origin', 'main');
  git(fixture.checkout, 'fetch', 'origin');
  const squash = refSha(fixture.checkout, 'HEAD');
  const prs = { [`worktree-${name}`]: { number: prNumber, headRefOid: head, mergeCommit: { oid: squash } } };
  return { dest, head, squash, env: { JUSTSEARCH_TEST_GH_PRS: JSON.stringify(prs) } };
}

/* ── cases ────────────────────────────────────────────────────────────────────────────────── */

try {
  // ── 1-4: create, register, hold, status (no destructive step in this fixture) ──────────────
  const base = await makeFixture('base');
  const stagedRegister = require(path.join(base.checkout, 'scripts', 'dev', 'lib', 'worktree-register.cjs'));

  await check('create registers a worktree at origin/main with markers and prints its path', () => {
    const dest = createWorktree(base, 'c1', 'session-c1');
    assert.equal(fs.existsSync(dest), true);
    assert.equal(refSha(base.checkout, 'refs/heads/worktree-c1'), base.originMain);
    assert.equal(gitTry(dest, 'rev-parse', '--abbrev-ref', 'HEAD').stdout, 'worktree-c1');
    assert.equal(cfg(base.checkout, 'branch.worktree-c1.justsearch-resource'), 'c1');
    assert.equal(cfg(base.checkout, 'branch.worktree-c1.justsearch-session'), 'session-c1');
    assert.equal(cfg(base.checkout, 'branch.worktree-c1.justsearch-harness'), 'claude');
    assert.equal(cfg(base.checkout, 'branch.worktree-c1.justsearch-fork'), base.originMain);
    assert.equal(cfg(base.checkout, 'branch.worktree-c1.justsearch-anchor'), base.originMain);
    const created = cfg(base.checkout, 'branch.worktree-c1.justsearch-created');
    assert.equal(Number.isFinite(new Date(created).getTime()), true, `created marker is not a timestamp: ${created}`);
    assert.equal(cfg(base.checkout, 'branch.worktree-c1.justsearch-released'), null);
    assert.equal(entryFor(base.checkout, dest).locked, null, 'a claude worktree is not locked');
  });

  await check('a second create with the same name is refused and changes nothing', () => {
    const beforeWorktrees = git(base.checkout, 'worktree', 'list', '--porcelain');
    const beforeRefs = listRefs(base.checkout, 'refs/').join('\n');
    const beforeConfig = branchConfigDump(base.checkout);
    const result = runCli(base, ['create', 'c1', '--session-id', 'session-other']);
    assert.equal(result.status, 1, result.out);
    assert.match(result.stderr, /ERROR: .*c1 already exists/);
    assert.equal(git(base.checkout, 'worktree', 'list', '--porcelain'), beforeWorktrees);
    assert.equal(listRefs(base.checkout, 'refs/').join('\n'), beforeRefs);
    assert.equal(branchConfigDump(base.checkout), beforeConfig);
  });

  await check('create --harness codex locks the worktree with a parseable reason', () => {
    const dest = createWorktree(base, 'c2', 'session-c2', ['--harness', 'codex']);
    assert.equal(cfg(base.checkout, 'branch.worktree-c2.justsearch-harness'), 'codex');
    const locked = entryFor(base.checkout, dest).locked;
    assert.notEqual(locked, null, 'a codex worktree must be locked');
    const parsed = stagedRegister.parseLockReason(locked);
    assert.notEqual(parsed, null, `lock reason is not parseable: ${locked}`);
    assert.equal(parsed.harness, 'codex');
    assert.equal(parsed.session, 'session-c2');
    assert.equal(Number.isInteger(parsed.pid) && parsed.pid > 0, true, `lock reason carries no pid: ${locked}`);
  });

  await check('register writes markers on a hand-made worktree and prints nothing on success', () => {
    const dest = path.join(base.sanctioned, 'r1');
    git(base.checkout, 'worktree', 'add', '-b', 'worktree-r1', dest, 'origin/main');
    const result = runCli(base, ['register', dest, '--session-id', 'session-r1']);
    assert.equal(result.status, 0, result.out);
    assert.equal(result.stdout, '', `register wrote to stdout: ${result.stdout}`);
    assert.equal(result.stderr, '', `register wrote to stderr: ${result.stderr}`);
    assert.equal(cfg(base.checkout, 'branch.worktree-r1.justsearch-resource'), 'r1');
    assert.equal(cfg(base.checkout, 'branch.worktree-r1.justsearch-session'), 'session-r1');
    assert.equal(cfg(base.checkout, 'branch.worktree-r1.justsearch-harness'), 'claude');
    assert.equal(cfg(base.checkout, 'branch.worktree-r1.justsearch-fork'), base.originMain);
    assert.equal(cfg(base.checkout, 'branch.worktree-r1.justsearch-anchor'), base.originMain);
  });

  await check('register refuses the main checkout', () => {
    const beforeConfig = branchConfigDump(base.checkout);
    const result = runCli(base, ['register', base.checkout, '--session-id', 'session-r1']);
    assert.equal(result.status, 1, result.out);
    assert.match(result.stderr, /the main checkout is never a lifecycle resource/);
    assert.equal(cfg(base.checkout, 'branch.main.justsearch-resource'), null);
    assert.equal(branchConfigDump(base.checkout), beforeConfig);
  });

  await check('status reports ACTIVE from a fresh ledger, SUSPECT when it goes stale, and UNMANAGED off the sanctioned root', () => {
    const stray = path.join(base.root, 'stray-worktree');
    git(base.checkout, 'worktree', 'add', '-b', 'stray-branch', stray, 'origin/main');
    const c1 = path.join(base.sanctioned, 'c1');
    git(base.checkout, 'worktree', 'lock', '--reason', `claude session session-c1 (pid ${deadPid()})`, c1);
    writeLedger(base, 'session-c1', new Date().toISOString());

    const text = runCli(base, ['status']);
    assert.equal(text.status, 0, text.out);
    assert.match(text.stdout, /^worktrees: \d+ managed, \d+ unmanaged, \d+ leftover branch\(es\), \d+ finalizing$/m);
    assert.match(text.stdout, /ACTIVE +worktree-c1/);
    assert.match(text.stdout, /UNMANAGED +stray-branch/);

    const json = runCli(base, ['status', '--json']);
    assert.equal(json.status, 0, json.out);
    const data = JSON.parse(json.stdout);
    const active = data.census.worktrees.find((w) => w.branch === 'worktree-c1');
    assert.equal(active.state, 'ACTIVE');
    assert.match(active.reasons.join(' | '), /session ledger last activity \d+ min ago/);
    const unmanaged = data.census.unmanaged.find((w) => w.branch === 'stray-branch');
    assert.equal(unmanaged.state, 'UNMANAGED');
    assert.match(unmanaged.reasons.join(' | '), /outside the sanctioned worktree root/);
    assert.equal(Array.isArray(data.archives), true);

    // Causality: ACTIVE came from the ledger, not from the lock's presence.
    writeLedger(base, 'session-c1', new Date(Date.now() - 3 * 3600 * 1000).toISOString());
    const stale = JSON.parse(runCli(base, ['status', '--json']).stdout);
    assert.equal(stale.census.worktrees.find((w) => w.branch === 'worktree-c1').state, 'SUSPECT');
  });

  await check('hold sets the marker only with a complete, well-formed triple', () => {
    createWorktree(base, 'h1', 'session-h1');
    const ok = runCli(base, ['hold', 'worktree-h1', '--reason', 'awaiting review', '--owner', 'elias', '--review-by', '2026-10-01']);
    assert.equal(ok.status, 0, ok.out);
    assert.match(ok.stderr, /hold recorded on worktree-h1: awaiting review \(owner elias, review by 2026-10-01\)/);
    assert.equal(cfg(base.checkout, 'branch.worktree-h1.justsearch-hold'), 'awaiting review|elias|2026-10-01');
    const held = JSON.parse(runCli(base, ['status', '--json']).stdout)
      .census.worktrees.find((w) => w.branch === 'worktree-h1');
    assert.equal(held.state, 'HELD');
    assert.equal(held.markers.hold.reviewBy, '2026-10-01');

    const missing = runCli(base, ['hold', 'worktree-h1', '--reason', 'r', '--owner', 'o']);
    assert.equal(missing.status, 2, missing.out);
    assert.match(missing.stderr, /missing: review-by/);
    const badDate = runCli(base, ['hold', 'worktree-h1', '--reason', 'r', '--owner', 'o', '--review-by', 'october']);
    assert.equal(badDate.status, 2, badDate.out);
    assert.match(badDate.stderr, /--review-by must be YYYY-MM-DD/);
    assert.equal(cfg(base.checkout, 'branch.worktree-h1.justsearch-hold'), 'awaiting review|elias|2026-10-01',
      'a rejected hold must not overwrite the recorded one');
  });

  // KNOWN-RED against ffbd60acd: `formatRow` calls
  // `register.parseHold(w.markers.hold)`, but `census` already delivers `hold` PARSED
  // (`markersFromConfigRecords` stores `parseHold(value)`), so the second parse gets an object,
  // returns null, and `.reviewBy` throws. One held resource makes the whole text-mode `status`
  // exit 1 with a TypeError — the HELD row is the one an operator most needs to see, and the
  // failure takes the ACTIVE/SUSPECT/UNMANAGED rows down with it. Read `w.markers.hold` directly.
  await check('KNOWN-RED: text-mode status must render a HELD row instead of crashing', () => {
    const text = runCli(base, ['status']);
    assert.equal(text.status, 0, text.out);
    assert.match(text.stdout, /HELD +worktree-h1/);
    assert.match(text.stdout, /hold=2026-10-01/);
  });

  // ── 5: release of a clean, unlanded worktree ───────────────────────────────────────────────
  const releaseFx = await makeFixture('release');
  await check('release archives, removes and keeps the branch when the receipt is UNKNOWN', () => {
    const keep = createWorktree(releaseFx, 'keepme', 'session-keep');
    const dest = createWorktree(releaseFx, 'r5', 'session-r5');
    const head = commitIn(dest, 'work.txt', 'unlanded work\n', 'feat: unlanded');

    const result = runCli(releaseFx, ['release', dest, '--no-fetch', '--session-id', 'session-r5']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /archived r5: refs\/archive\/r5\//);
    assert.match(result.stderr, /branch worktree-r5 kept: UNKNOWN/);
    // The CLI reports the registered path as git prints it (forward slashes on Windows).
    assert.equal(result.stderr.replace(/\\/g, '/').includes(`released and removed ${dest.replace(/\\/g, '/')}`), true, result.out);

    const archiveRefs = listRefs(releaseFx.checkout, 'refs/archive/r5');
    assert.equal(archiveRefs.includes(`refs/archive/r5/${head.slice(0, 7)}`)
      || archiveRefs.some((ref) => head.startsWith(ref.slice('refs/archive/r5/'.length))), true,
      `no tip ref for ${head}: ${archiveRefs.join(', ')}`);
    assert.equal(archiveRefs.some((ref) => /\/state-\d{8}T\d{6}Z$/.test(ref)), true, archiveRefs.join(', '));
    assert.equal(manifestFiles(releaseFx, 'r5').length, 1);

    assert.equal(fs.existsSync(dest), false, 'the released directory must be gone');
    assert.equal(entryFor(releaseFx.checkout, dest), null, 'its Git registration must be gone');
    assert.notEqual(refSha(releaseFx.checkout, 'refs/heads/worktree-r5'), null, 'an UNKNOWN receipt never retires a branch');
    assert.equal(refSha(releaseFx.checkout, 'refs/heads/worktree-r5'), head);
    const released = cfg(releaseFx.checkout, 'branch.worktree-r5.justsearch-released');
    assert.equal(Number.isFinite(new Date(released).getTime()), true, `released marker is not a timestamp: ${released}`);
    assert.equal(receiptLines(releaseFx).at(-1).verdict, 'UNKNOWN');
    assert.equal(fs.existsSync(finalizationFile(releaseFx, 'r5')), false, 'a completed finalization leaves no record');

    // What must survive: the sibling worktree, its branch and markers, and the main checkout.
    assert.equal(fs.existsSync(keep), true);
    assert.equal(refSha(releaseFx.checkout, 'refs/heads/worktree-keepme'), releaseFx.originMain);
    assert.equal(cfg(releaseFx.checkout, 'branch.worktree-keepme.justsearch-resource'), 'keepme');
    assert.equal(refSha(releaseFx.checkout, 'refs/heads/main'), releaseFx.originMain);
    assert.equal(listRefs(releaseFx.checkout, 'refs/archive/keepme').length, 0);
  });

  // ── 6: release of a landed worktree retires the branch ─────────────────────────────────────
  const landedFx = await makeFixture('landed');
  await check('release retires the branch when a merged PR proves the head landed', () => {
    const landed = landWorktree(landedFx, 'w6', 'session-w6', 606);
    const result = runCli(landedFx, ['release', landed.dest, '--no-fetch', '--session-id', 'session-w6'], { env: landed.env });
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /released and removed .* and retired worktree-w6/);
    assert.equal(refSha(landedFx.checkout, 'refs/heads/worktree-w6'), null, 'a LANDED receipt retires the branch');
    assert.equal(fs.existsSync(landed.dest), false);
    const tipRefs = listRefs(landedFx.checkout, 'refs/archive/w6');
    assert.equal(tipRefs.length >= 2, true, tipRefs.join(', '));
    assert.equal(tipRefs.some((ref) => refSha(landedFx.checkout, ref) === landed.head), true,
      `the archived tip must still hold ${landed.head}: ${tipRefs.join(', ')}`);
    const receipt = receiptLines(landedFx).at(-1);
    assert.equal(receipt.verdict, 'LANDED');
    assert.equal(receipt.pr, 606);
    assert.equal(receipt.squash, landed.squash);
    assert.equal(fs.existsSync(finalizationFile(landedFx, 'w6')), false);
    // What must survive: the squash on main and the branch's content in the archive.
    assert.equal(refSha(landedFx.checkout, 'origin/main'), landed.squash);
    assert.equal(fs.existsSync(path.join(landedFx.checkout, 'w6.txt')), true);
  });

  // ── 7: the three non-removing release modes ────────────────────────────────────────────────
  const modesFx = await makeFixture('modes');
  await check('release --own refuses another session\'s resource and changes nothing', () => {
    const dest = createWorktree(modesFx, 'own1', 'session-own1');
    const result = runCli(modesFx, ['release', dest, '--own', '--no-fetch', '--session-id', 'intruder-session']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /not released: .* is owned by session session-own1, caller is intruder-session/);
    assert.equal(fs.existsSync(dest), true);
    assert.equal(refSha(modesFx.checkout, 'refs/heads/worktree-own1'), modesFx.originMain);
    assert.equal(cfg(modesFx.checkout, 'branch.worktree-own1.justsearch-released'), null);
    assert.equal(listRefs(modesFx.checkout, 'refs/archive/own1').length, 0);
    assert.equal(fs.existsSync(finalizationFile(modesFx, 'own1')), false);
  });

  await check('release --if-clean keeps a dirty worktree and records the release for the reconciler', () => {
    const dest = createWorktree(modesFx, 'dirty1', 'session-dirty1');
    fs.writeFileSync(path.join(dest, 'in-progress.txt'), 'uncommitted\n', 'utf8');
    const result = runCli(modesFx, ['release', dest, '--if-clean', '--no-fetch', '--session-id', 'session-dirty1']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /released \(kept for the reconciler\): .* has uncommitted changes/);
    assert.equal(fs.existsSync(dest), true);
    assert.equal(fs.readFileSync(path.join(dest, 'in-progress.txt'), 'utf8'), 'uncommitted\n');
    assert.notEqual(cfg(modesFx.checkout, 'branch.worktree-dirty1.justsearch-released'), null);
    assert.equal(listRefs(modesFx.checkout, 'refs/archive/dirty1').length, 0);
  });

  await check('release --record-only marks the resource and removes nothing', () => {
    const dest = createWorktree(modesFx, 'rec1', 'session-rec1');
    const result = runCli(modesFx, ['release', dest, '--record-only', '--no-fetch', '--session-id', 'session-rec1']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /released .* \(record only\); the reconciler or the harness completes removal/);
    assert.equal(fs.existsSync(dest), true);
    assert.notEqual(cfg(modesFx.checkout, 'branch.worktree-rec1.justsearch-released'), null);
    assert.equal(listRefs(modesFx.checkout, 'refs/archive/rec1').length, 0);
    assert.equal(fs.existsSync(finalizationFile(modesFx, 'rec1')), false);
  });

  // ── 8: an oversized ignored file quarantines the release ───────────────────────────────────
  const capFx = await makeFixture('cap', {
    patchPolicy: (policy) => ({ ...policy, archive: { ...policy.archive, ignoredPerFileCapBytes: 16 } }),
  });
  const capDest = createWorktree(capFx, 'r8', 'session-r8');
  // Unlanded, so the branch is one of the things the destructive case must leave behind.
  const capHead = commitIn(capDest, 'work.txt', 'unlanded work\n', 'feat: unlanded');
  fs.mkdirSync(path.join(capDest, 'ignored'), { recursive: true });
  fs.writeFileSync(path.join(capDest, 'ignored', 'big.bin'), 'x'.repeat(64), 'utf8');

  await check('an ignored file over the policy cap quarantines the release intact', () => {
    const result = runCli(capFx, ['release', capDest, '--no-fetch', '--session-id', 'session-r8']);
    assert.equal(result.status, 3, result.out);
    assert.match(result.stderr, /ERROR: quarantined: oversized-ignored/);
    assert.match(result.stderr, /ignored\/big\.bin \(64 B\)/);
    assert.equal(fs.existsSync(capDest), true, 'a quarantined resource is never removed');
    assert.equal(fs.readFileSync(path.join(capDest, 'ignored', 'big.bin'), 'utf8').length, 64);
    assert.equal(refSha(capFx.checkout, 'refs/heads/worktree-r8'), capHead);
    const record = readFinalization(capFx, 'r8');
    assert.notEqual(record, null, 'the interrupted finalization must leave a record');
    assert.equal(record.phase, 'claimed');
    assert.equal(record.resource, 'r8');

    const status = runCli(capFx, ['status', '--lifecycle']);
    assert.equal(status.status, 0, status.out);
    assert.match(status.stdout, /finalizing r8: claimed since /);
    assert.match(status.stdout, /FINALIZING +worktree-r8/);
  });

  await check('release --discard-ignored names the oversized file and completes the release', () => {
    const result = runCli(capFx, ['release', capDest, '--no-fetch', '--discard-ignored', 'ignored/*', '--session-id', 'session-r8']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /archived r8: /);
    assert.match(result.stderr, /released and removed /);
    assert.equal(fs.existsSync(capDest), false);
    assert.equal(fs.existsSync(finalizationFile(capFx, 'r8')), false);
    const manifest = JSON.parse(fs.readFileSync(manifestFiles(capFx, 'r8')[0], 'utf8'));
    assert.equal(manifest.skipped.some((s) => s.path === 'ignored/big.bin' && s.reason === 'oversized-discarded'), true,
      `a discarded file must be named in the manifest: ${JSON.stringify(manifest.skipped)}`);
    assert.equal(manifest.files.some((f) => f.path === 'ignored/big.bin'), false);
    // What must survive: the unlanded branch and the archive of what was kept.
    assert.equal(refSha(capFx.checkout, 'refs/heads/worktree-r8'), capHead);
    assert.equal(listRefs(capFx.checkout, 'refs/archive/r8').length >= 2, true);
  });

  // ── 9: --keep-branch overrides a retiring receipt, and needs the hold triple ────────────────
  const keepFx = await makeFixture('keep');
  const kept = landWorktree(keepFx, 'w9', 'session-w9', 909);

  await check('release --keep-branch without the hold triple exits 2 and removes nothing', () => {
    const result = runCli(keepFx, ['release', kept.dest, '--keep-branch', '--no-fetch', '--session-id', 'session-w9'], { env: kept.env });
    assert.equal(result.status, 2, result.out);
    assert.match(result.stderr, /keeping a resource needs --reason, --owner and --review-by \(missing: reason, owner, review-by\)/);
    assert.equal(fs.existsSync(kept.dest), true);
    assert.equal(refSha(keepFx.checkout, 'refs/heads/worktree-w9'), kept.head);
    assert.equal(listRefs(keepFx.checkout, 'refs/archive/w9').length, 0);
    assert.equal(fs.existsSync(finalizationFile(keepFx, 'w9')), false);
  });

  // KNOWN-RED against ffbd60acd: `cmdRelease` stamps `justsearch-released` BEFORE it validates the
  // `--keep-branch` triple, so an exit-2 argument error leaves the resource RELEASED — and a
  // RELEASED worktree whose receipt is LANDED is exactly what `reconcile` proposes to "remove and
  // retire branch". A rejected request to KEEP a branch must not register an obligation to retire
  // it; argument validation belongs before the state mutation.
  await check('KNOWN-RED: a rejected --keep-branch must not leave the resource RELEASED', () => {
    assert.equal(cfg(keepFx.checkout, 'branch.worktree-w9.justsearch-released'), null,
      'exit-2 argument validation ran after the released marker was written');
  });

  await check('release --keep-branch with the triple removes the tree and holds the landed branch', () => {
    const result = runCli(keepFx, [
      'release', kept.dest, '--keep-branch', '--reason', 'evidence pending', '--owner', 'elias',
      '--review-by', '2026-10-01', '--no-fetch', '--session-id', 'session-w9',
    ], { env: kept.env });
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /branch worktree-w9 kept: LANDED .*; hold evidence pending/);
    assert.doesNotMatch(result.stderr, /and retired worktree-w9/);
    assert.equal(fs.existsSync(kept.dest), false);
    assert.equal(refSha(keepFx.checkout, 'refs/heads/worktree-w9'), kept.head, 'the held branch must survive');
    assert.equal(cfg(keepFx.checkout, 'branch.worktree-w9.justsearch-hold'), 'evidence pending|elias|2026-10-01');
    assert.notEqual(cfg(keepFx.checkout, 'branch.worktree-w9.justsearch-released'), null);
    assert.equal(listRefs(keepFx.checkout, 'refs/archive/w9').length >= 2, true);
    assert.equal(fs.existsSync(finalizationFile(keepFx, 'w9')), false);
  });

  // ── 10: the advisory reconciler ────────────────────────────────────────────────────────────
  const reconcileFx = await makeFixture('reconcile');
  await check('reconcile prints nothing when nothing needs a decision (952 §5.9)', () => {
    const result = runCli(reconcileFx, ['reconcile', '--no-fetch', '--session-id', 'session-rc']);
    assert.equal(result.status, 0, result.out);
    assert.equal(result.stdout, '', `reconcile wrote to stdout: ${result.stdout}`);
    assert.equal(result.stderr, '', `reconcile wrote to stderr: ${result.stderr}`);
  });

  await check('reconcile reports a released-but-present worktree as one advisory obligation', () => {
    const dest = createWorktree(reconcileFx, 'rc1', 'session-rc');
    commitIn(dest, 'unlanded.txt', 'unlanded\n', 'feat: unlanded');
    const recorded = runCli(reconcileFx, ['release', dest, '--record-only', '--no-fetch', '--session-id', 'session-rc']);
    assert.equal(recorded.status, 0, recorded.out);

    const result = runCli(reconcileFx, ['reconcile', '--no-fetch', '--session-id', 'session-rc']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stdout, /\[worktree-lifecycle\] 1 obligation\(s\) \(advisory: nothing removed\)/);
    assert.match(result.stdout, /RELEASED .*rc1: archive and remove; keep branch \(unlanded\) \[UNKNOWN\]/);
    assert.equal(fs.existsSync(dest), true, 'advisory phase 1 removes nothing');
    assert.equal(listRefs(reconcileFx.checkout, 'refs/archive/rc1').length, 0);
  });

  await check('reconcile --execute refuses to execute while executeOnSchedule is false', () => {
    const dest = path.join(reconcileFx.sanctioned, 'rc1');
    const result = runCli(reconcileFx, ['reconcile', '--execute', '--no-fetch', '--session-id', 'session-rc']);
    assert.equal(result.status, 0, result.out);
    assert.match(result.stderr, /executeOnSchedule is false in governance\/worktree-lifecycle\.v1\.json \(phase 1\): reporting only/);
    assert.match(result.stdout, /1 obligation\(s\) \(advisory: nothing removed\)/);
    assert.equal(fs.existsSync(dest), true, 'phase 1 must not remove on --execute');
    assert.notEqual(refSha(reconcileFx.checkout, 'refs/heads/worktree-rc1'), null);
    assert.equal(listRefs(reconcileFx.checkout, 'refs/archive/rc1').length, 0);
    assert.equal(fs.existsSync(finalizationFile(reconcileFx, 'rc1')), false);
  });

  // ── 11: restart from a torn finalization record ────────────────────────────────────────────
  const resumeFx = await makeFixture('resume');
  await check('release resumes an archived-phase finalization left by a dead claimant', async () => {
    const dest = createWorktree(resumeFx, 'r11', 'session-r11');
    const head = commitIn(dest, 'unlanded.txt', 'unlanded\n', 'feat: unlanded');
    fs.mkdirSync(path.join(dest, 'ignored'), { recursive: true });
    fs.writeFileSync(path.join(dest, 'ignored', 'keep.txt'), 'small ignored payload\n', 'utf8');

    const stagedArchive = require(path.join(resumeFx.checkout, 'scripts', 'dev', 'lib', 'worktree-archive.cjs'));
    const register = require(path.join(resumeFx.checkout, 'scripts', 'dev', 'lib', 'worktree-register.cjs'));
    const archived = stagedArchive.archiveWorktree({
      mainRepoRoot: resumeFx.checkout,
      worktreePath: dest,
      resource: 'r11',
      policy: stagedArchive.loadPolicy(resumeFx.checkout),
    });
    assert.equal(archived.refused, undefined, `archive refused: ${JSON.stringify(archived)}`);
    assert.equal(archived.manifest.files.some((f) => f.path === 'ignored/keep.txt'), true);

    const crashedAt = Date.now() - 3600 * 1000;
    const record = {
      ...register.buildFinalizationRecord({
        resource: 'r11',
        worktreePath: dest,
        branch: 'worktree-r11',
        head,
        phase: 'archived',
        by: { sessionId: 'crashed-session', pid: deadPid(), creationFileTimeUtc: null },
        leaseDurationSec: 900,
        now: crashedAt,
      }),
      manifestPath: archived.manifestPath,
    };
    await register.writeFinalization({
      mainRepoRoot: resumeFx.checkout,
      record,
      env: { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: resumeFx.stateRoot },
    });
    assert.equal(readFinalization(resumeFx, 'r11').phase, 'archived');
    const statusBefore = runCli(resumeFx, ['status', '--json']);
    assert.equal(JSON.parse(statusBefore.stdout).census.worktrees.find((w) => w.branch === 'worktree-r11').state, 'FINALIZING');

    const result = runCli(resumeFx, ['release', dest, '--no-fetch', '--session-id', 'session-r11']);
    assert.equal(result.status, 0, result.out);
    assert.doesNotMatch(result.stderr, /archived r11:/, 'a resumed finalization must not re-archive');
    assert.equal(manifestFiles(resumeFx, 'r11').length, 1, 'no second manifest was written');
    assert.equal(listRefs(resumeFx.checkout, 'refs/archive/r11').filter((ref) => /\/state-/.test(ref)).length, 1,
      'no second state ref was written');
    assert.equal(fs.existsSync(dest), false);
    assert.equal(fs.existsSync(finalizationFile(resumeFx, 'r11')), false, 'the resumed finalization ends with no record');
    assert.equal(refSha(resumeFx.checkout, 'refs/heads/worktree-r11'), head, 'an UNKNOWN receipt keeps the branch');
    assert.notEqual(cfg(resumeFx.checkout, 'branch.worktree-r11.justsearch-released'), null);
  });
} finally {
  for (const fixture of fixtures.reverse()) {
    await fsp.rm(fixture.root, { recursive: true, force: true }).catch(() => {});
  }
}

if (failures.length) {
  console.error(`952-worktree-lifecycle-cli.test: ${failures.length} FAILED / ${passed} passed`);
  for (const failure of failures) console.error(`  x ${failure}`);
  process.exit(1);
}
console.log(`952-worktree-lifecycle-cli.test: ${passed} passed`);
