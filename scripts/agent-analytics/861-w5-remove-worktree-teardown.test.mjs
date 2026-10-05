/**
 * Tempdoc 861 W5 — `remove-worktree.cjs`'s `worktree-teardown` occasion, driven end-to-end as a
 * real subprocess against an ISOLATED fixture `agent-spawns/` register
 * (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT` override — never the real repo's register).
 *
 * Three things this file proves (861 W5 brief: "teardown blocked by a blocksProceed holder;
 * observed-tier fallback preserved"):
 *
 *   1. A registered, other-session, LIVE-leased holder (real disposable process, matching
 *      identity) makes `remove-worktree.cjs` REFUSE — non-zero exit, a named holder + remedy
 *      line on stderr, and the target directory LEFT INTACT. This is the §2-bis (c) fix: a
 *      proceed-anyway here is what leaves a half-deleted, `.git`-less worktree shell.
 *   2. Once that holder's record is gone, the SAME target directory is torn down successfully —
 *      proving the refusal above was about the holder, not a permanent block.
 *   3. With NO registered holder at all (a register with zero records for the path), teardown
 *      proceeds exactly as before this tempdoc — the observed-tier command-line fallback
 *      (`filterHolders`/`reportHolders`, unit-tested separately in `remove-worktree.test.mjs`)
 *      is untouched, and normal deletion still works.
 *
 * The fixture installs the production CLI and its local dependencies into a disposable Git
 * repository, then creates real linked worktrees. No shared JustSearch registration is touched.
 *
 * Run with: `node scripts/agent-analytics/861-w5-remove-worktree-teardown.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { writeHarnessFixture, identityEnv } from './lib/agent-identity-fixture.mjs';

const require = createRequire(import.meta.url);
const { buildAgentSpawnRecord, writeAgentSpawnRecord } = require('../dev/lib/agent-spawn-record.cjs');
const { readProcessTable, normalizeCreationTime } = require('../dev/lib/process-identity.cjs');

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
let removeWorktreeCli = null;
const CLI_FILES = [
  'scripts/dev/remove-worktree.cjs',
  'scripts/dev/lib/process-identity.cjs',
  'scripts/dev/lib/process-record.cjs',
  'scripts/dev/lib/agent-spawn-record.cjs',
  'scripts/dev/lib/agent-spawn-reaper.cjs',
  'scripts/dev/lib/agent-spawn-sweep.cjs',
  'scripts/dev/lib/ownership-verdict.cjs',
  'scripts/dev/lib/agent-identity.cjs',
  'scripts/dev/lib/owner-presence.cjs',
  'scripts/dev/justsearch-dev-mcp/observations.mjs',
  'scripts/dev/justsearch-dev-mcp/files.mjs',
  'scripts/dev/justsearch-dev-mcp/paths.mjs',
];

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

function killIfAlive(pid) {
  try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
}

function git(repo, ...args) {
  const result = spawnSync('git', args, {
    cwd: repo,
    encoding: 'utf8',
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
  });
  assert.equal(result.status, 0, `git ${args.join(' ')} failed:\n${result.stdout || ''}${result.stderr || ''}`);
  return result.stdout || '';
}

async function installFixtureRepo(scratch) {
  const repo = path.join(scratch, 'owner repo');
  await fsp.mkdir(repo, { recursive: true });
  git(repo, 'init', '-b', 'main');
  git(repo, 'config', 'user.email', 'fixture@example.invalid');
  git(repo, 'config', 'user.name', 'Fixture');
  await fsp.writeFile(path.join(repo, 'tracked.txt'), 'fixture');
  git(repo, 'add', 'tracked.txt');
  git(repo, 'commit', '-m', 'fixture');
  for (const rel of CLI_FILES) {
    const destination = path.join(repo, ...rel.split('/'));
    await fsp.mkdir(path.dirname(destination), { recursive: true });
    await fsp.copyFile(path.join(REPO_ROOT, ...rel.split('/')), destination);
  }
  return repo;
}

function addFixtureWorktree(repo, target, branch) {
  git(repo, 'worktree', 'add', '-b', branch, target, 'HEAD');
  return target;
}

async function findOwnChildRow(pid) {
  for (let i = 0; i < 15; i += 1) {
    const table = readProcessTable();
    if (table.ok) {
      const row = table.table.find((r) => Number(r?.ProcessId) === pid);
      if (row) return row;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  return null;
}

/** remove-worktree.cjs writes ALL of its own reporting to stderr (console.error) even on
 *  success, so capture both streams explicitly (spawnSync, not execFileSync — which returns
 *  only stdout, discarding stderr on the non-throwing path).
 *
 *  `sessionIdFlag` defaults to an explicit `--session-id caller-session` (most tests don't care
 *  about F-2a's env-chain resolution and want a stable, known caller id); pass `null` to omit
 *  the flag entirely - the documented invocation - so `resolveCallerIdentity` resolves the caller
 *  from its harness process, simulated through `env` (F-2a/F-3's actual fix). */
function runRemoveWorktree(targetDir, { env, sessionIdFlag = 'caller-session' } = {}) {
  const args = [removeWorktreeCli, targetDir];
  if (sessionIdFlag) args.push('--session-id', sessionIdFlag);
  const res = spawnSync('node', args, {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
  return { status: res.status, output: `${res.stdout || ''}${res.stderr || ''}` };
}

async function main() {
  if (process.platform !== 'win32') {
    console.log('861-w5-remove-worktree-teardown.test: skipped (win32-only surfaces).');
    console.log('861-w5-remove-worktree-teardown.test: 0 passed');
    return;
  }

  const scratch = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-teardown-'));
  const repo = await installFixtureRepo(scratch);
  removeWorktreeCli = path.join(repo, 'scripts', 'dev', 'remove-worktree.cjs');
  const stateRoot = path.join(scratch, 'state');
  const fixtureWorktree = path.join(scratch, '.claude', 'worktrees', 'w5-teardown-fixture');
  addFixtureWorktree(repo, fixtureWorktree, 'fixture/w5-holder');

  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });

  try {
    const row = await findOwnChildRow(child.pid);
    assert.ok(row, `spawned child pid ${child.pid} never appeared in the process table`);
    const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
    assert.ok(creationFileTimeUtc, 'child has no readable creation time');

    const dir = path.join(stateRoot, 'agent-spawns');
    const record = await buildAgentSpawnRecord({
      recordId: 'w5-teardown-holder',
      producer: 'w5-teardown-test-fixture',
      pid: child.pid,
      creationFileTimeUtc,
      cmdlineFingerprint: '-e',
      port: 40200,
      leaseDurationSec: 3600, // LIVE — other session, live lease -> CONTENTION -> blocksProceed
      sessionId: 'other-session',
      resourceRoots: { worktreeRoot: fixtureWorktree },
    });
    await writeAgentSpawnRecord({ dir, record });

    const env = { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot };

    await check('a registered, other-session, live-leased holder REFUSES the teardown (non-zero exit)', () => {
      const { status, output } = runRemoveWorktree(fixtureWorktree, { env });
      assert.equal(status, 1, `expected exit 1, got ${status}. Output:\n${output}`);
      assert.match(output, /refusing to remove/i);
      assert.match(output, /w5-teardown-test-fixture/, 'the holder\'s producer should be named');
      assert.match(output, /taskkill/i, 'a ready-to-run remedy line should be printed');
      // [F-2b] The PRIMARY remedy is the safe sweep CLI, not the bare taskkill — printing
      // taskkill as THE fix would contradict branch-safety.md's own "never hand-taskkill one"
      // rule this same tempdoc added.
      assert.match(output, /agent-spawn-sweep\.cjs/i, 'the safe sweep-CLI remedy should be printed alongside the last-resort kill line');
      assert.ok(output.indexOf('agent-spawn-sweep.cjs') < output.indexOf('taskkill'), 'the sweep remedy must appear before the bare taskkill line');
    });

    await check('the target directory is LEFT INTACT after a refusal (the §2-bis (c) fix)', async () => {
      const stillThere = await fsp.stat(fixtureWorktree).then((s) => s.isDirectory(), () => false);
      assert.equal(stillThere, true, 'a refused teardown must not leave a half-deleted directory');
      const markerStillThere = await fsp.stat(path.join(fixtureWorktree, 'tracked.txt')).then(() => true, () => false);
      assert.equal(markerStillThere, true);
    });

    await check('once the holder\'s record is gone, the SAME directory tears down successfully', async () => {
      await fsp.rm(path.join(dir, 'w5-teardown-holder.json'), { force: true });
      const { status, output } = runRemoveWorktree(fixtureWorktree, { env });
      assert.equal(status, 0, `expected exit 0, got ${status}. Output:\n${output}`);
      assert.match(output, /deleted/i);
      const gone = await fsp.stat(fixtureWorktree).then(() => false, () => true);
      assert.equal(gone, true, 'the directory should be deleted once no holder remains');
    });
  } finally {
    killIfAlive(child.pid);
  }

  // ── Observed-tier fallback: with NO agent-spawns register at all, teardown proceeds exactly
  // as before this tempdoc — the new consult step must be a no-op when nothing is registered. ──
  await check('with an empty (no-record) register, teardown proceeds normally (no regression)', async () => {
    const emptyStateRoot = path.join(scratch, 'empty-state');
    const target = path.join(scratch, '.claude', 'worktrees', 'w5-teardown-no-register');
    addFixtureWorktree(repo, target, 'fixture/w5-empty');

    const { status, output } = runRemoveWorktree(target, { env: { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: emptyStateRoot } });
    assert.equal(status, 0, `expected exit 0, got ${status}. Output:\n${output}`);
    assert.match(output, /deleted/i);
    assert.doesNotMatch(output, /agent-spawns register/i, 'no register consult output when nothing is registered');
    const gone = await fsp.stat(target).then(() => false, () => true);
    assert.equal(gone, true);
  });

  // -- F-2a: with no --session-id flag the caller's OWN session is resolved from its harness
  // process (agent-identity.cjs), so its own live spawn reaps instead of falling through to
  // CONTENTION. The harness is simulated through the process-table fixture seam. -------------
  await check('F-2a: the documented invocation (no --session-id) still reaps the caller\'s own live spawn via its harness identity', async () => {
    const sameSessionRoot = path.join(scratch, 'same-session');
    const sameSessionState = path.join(sameSessionRoot, 'state');
    const sameSessionWorktree = path.join(sameSessionRoot, '.claude', 'worktrees', 'w5-f2a-fixture');
    addFixtureWorktree(repo, sameSessionWorktree, 'fixture/w5-same-session');

    const ownChild = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });
    try {
      const row = await findOwnChildRow(ownChild.pid);
      assert.ok(row, `spawned child pid ${ownChild.pid} never appeared in the process table`);
      const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
      assert.ok(creationFileTimeUtc);

      const sessionId = `f2a-same-session-${process.pid}`;
      const harness = writeHarnessFixture(path.join(sameSessionRoot, 'identity'));
      const dir2 = path.join(sameSessionState, 'agent-spawns');
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-f2a-own-holder',
        producer: 'w5-f2a-test-fixture',
        pid: ownChild.pid,
        creationFileTimeUtc,
        cmdlineFingerprint: '-e',
        port: 40210,
        leaseDurationSec: 3600, // live — but SAME session reaps regardless of lease state (§6.3)
        sessionId,
        owner: harness.owner,
        resourceRoots: { worktreeRoot: sameSessionWorktree },
      });
      await writeAgentSpawnRecord({ dir: dir2, record });

      // No --session-id flag: the ONLY way this resolves to `sessionId` is the env chain.
      const { status, output } = runRemoveWorktree(sameSessionWorktree, {
        env: identityEnv(harness.file, { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: sameSessionState, CLAUDE_CODE_SESSION_ID: sessionId }),
        sessionIdFlag: null,
      });
      assert.equal(status, 0, `expected exit 0 (own-session reap, not a refusal), got ${status}. Output:\n${output}`);
      assert.match(output, /same-session/, 'the record must classify as same-session - proof the harness identity reached the teardown consult');
      assert.match(output, /deleted/i);
      const gone = await fsp.stat(sameSessionWorktree).then(() => false, () => true);
      assert.equal(gone, true, 'a correctly-attributed own-session holder must not block its own worktree\'s teardown');
    } finally {
      killIfAlive(ownChild.pid);
    }
  });

  await fsp.rm(scratch, { recursive: true, force: true }).catch(() => {});

  if (failures.length) {
    console.error(`861-w5-remove-worktree-teardown.test: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exitCode = 1;
    return;
  }
  console.log(`861-w5-remove-worktree-teardown.test: ${passed} passed`);
}

main();
