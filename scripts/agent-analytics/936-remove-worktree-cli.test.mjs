/**
 * Real-Git production-CLI regressions for registered worktree removal safety.
 * Every destructive operation is confined to a newly-created OS temp directory.
 * Run with: node scripts/agent-analytics/936-remove-worktree-cli.test.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(HERE, '..', '..');
const require = createRequire(import.meta.url);
const { recordMergeLink } = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'remove-worktree.cjs'));
const { readProcessTable } = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'lib', 'process-identity.cjs'));
const FIXTURE_PROCESS_TABLE_ENV = 'JUSTSEARCH_936_FIXTURE_PROCESS_TABLE_PIDS';
const PROCESS_IDENTITY_REL = 'scripts/dev/lib/process-identity.cjs';
const CLI_FILES = [
  'scripts/dev/remove-worktree.cjs',
  'scripts/dev/lib/process-identity.cjs',
  'scripts/dev/lib/process-record.cjs',
  'scripts/dev/lib/agent-spawn-record.cjs',
  'scripts/dev/lib/agent-spawn-reaper.cjs',
  'scripts/dev/lib/agent-spawn-sweep.cjs',
  'scripts/dev/lib/ownership-verdict.cjs',
  'scripts/dev/lib/worktree-archive.cjs',
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
  } catch (err) {
    failures.push(`${label}: ${err.stack || err}`);
  }
}

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

async function installProductionCli(repo) {
  for (const rel of CLI_FILES) {
    const destination = path.join(repo, ...rel.split('/'));
    await fsp.mkdir(path.dirname(destination), { recursive: true });
    await fsp.copyFile(path.join(SOURCE_ROOT, ...rel.split('/')), destination);
  }
  const processIdentity = path.join(repo, ...PROCESS_IDENTITY_REL.split('/'));
  const productionProcessIdentity = path.join(path.dirname(processIdentity), 'process-identity.production.cjs');
  await fsp.rename(processIdentity, productionProcessIdentity);
  await fsp.writeFile(processIdentity, `'use strict';
// Test-only contract injection inside this disposable copied CLI; production stays the fallback.
const production = require('./process-identity.production.cjs');
const encodedPids = process.env.${FIXTURE_PROCESS_TABLE_ENV};
if (encodedPids === undefined) {
  module.exports = production;
} else {
  const pids = JSON.parse(encodedPids);
  if (!Array.isArray(pids) || pids.length === 0 || pids.some((pid) => !Number.isInteger(pid) || pid <= 0)) {
    throw new Error('${FIXTURE_PROCESS_TABLE_ENV} must encode a non-empty array of positive integer pids');
  }
  module.exports = {
    ...production,
    readProcessTable() {
      return { ok: true, table: pids.map((pid) => ({ ProcessId: pid })), readAt: Date.now() };
    },
  };
}
`, 'utf8');
  return path.join(repo, 'scripts', 'dev', 'remove-worktree.cjs');
}

async function makeRepo(label) {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), `remove-wt-936-${label}-`));
  const repo = path.join(root, 'owner repo');
  await fsp.mkdir(repo, { recursive: true });
  git(repo, 'init', '-b', 'main');
  git(repo, 'config', 'user.email', 'fixture@example.invalid');
  git(repo, 'config', 'user.name', 'Fixture');
  await fsp.writeFile(path.join(repo, '.gitignore'), 'ignored/\nnode_modules\n', 'utf8');
  await fsp.writeFile(path.join(repo, 'tracked.txt'), 'base\n', 'utf8');
  git(repo, 'add', '.gitignore', 'tracked.txt');
  git(repo, 'commit', '-m', 'fixture');
  return { root, repo, cli: await installProductionCli(repo) };
}

async function mutateInstalledCli(cli, mutate) {
  const before = await fsp.readFile(cli, 'utf8');
  const after = mutate(before);
  assert.notEqual(after, before, 'mutant must alter the disposable CLI copy');
  await fsp.writeFile(cli, after, 'utf8');
}

function addWorktree(fixture, relativeName, branch, { detached = false, parent = fixture.root } = {}) {
  const target = path.join(parent, relativeName);
  if (detached) git(fixture.repo, 'worktree', 'add', '--detach', target, 'HEAD');
  else git(fixture.repo, 'worktree', 'add', '-b', branch, target, 'HEAD');
  return target;
}

function runCli(fixture, target, args = [], {
  stateRoot = path.join(fixture.root, 'isolated-state'),
  env = {},
  processTablePids,
} = {}) {
  const childEnv = {
    ...process.env,
    JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
    CLAUDE_CODE_SESSION_ID: '936-cli-fixture',
    ...env,
  };
  delete childEnv[FIXTURE_PROCESS_TABLE_ENV];
  if (processTablePids !== undefined) {
    childEnv[FIXTURE_PROCESS_TABLE_ENV] = JSON.stringify(processTablePids);
  }
  const result = spawnSync(process.execPath, [fixture.cli, target, ...args], {
    cwd: fixture.repo,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: childEnv,
  });
  return { status: result.status, output: `${result.stdout || ''}${result.stderr || ''}` };
}

function registeredPaths(repo) {
  const raw = git(repo, 'worktree', 'list', '--porcelain', '-z');
  return raw.split('\0').filter((field) => field.startsWith('worktree ')).map((field) => path.resolve(field.slice(9)));
}

function refExists(repo, ref) {
  return spawnSync('git', ['show-ref', '--verify', '--quiet', ref], { cwd: repo }).status === 0;
}

function captureErrors(action) {
  const original = console.error;
  const lines = [];
  console.error = (...args) => lines.push(args.join(' '));
  try {
    action();
  } finally {
    console.error = original;
  }
  return lines.join('\n');
}

async function waitForFile(file, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { return (await fsp.readFile(file, 'utf8')).trim(); } catch { /* keep waiting */ }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`timed out waiting for ${file}`);
}

async function snapshot(fixture, target, stateRoot) {
  const fingerprint = async (file) => {
    const stat = await fsp.lstat(file);
    const content = stat.isSymbolicLink() ? await fsp.readlink(file) : await fsp.readFile(file);
    return {
      size: Number(stat.size),
      mtimeMs: stat.mtimeMs,
      sha256: crypto.createHash('sha256').update(content).digest('hex'),
    };
  };
  const walk = async (root) => {
    if (!fs.existsSync(root)) return null;
    const out = [];
    async function visit(current) {
      const entries = await fsp.readdir(current, { withFileTypes: true });
      for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
        const absolute = path.join(current, entry.name);
        const rel = path.relative(root, absolute).replace(/\\/g, '/');
        const stat = await fsp.lstat(absolute);
        const kind = stat.isSymbolicLink() ? 'link' : stat.isDirectory() ? 'dir' : 'file';
        const content = stat.isDirectory() && !stat.isSymbolicLink() ? null : await fingerprint(absolute);
        out.push({ rel, kind, size: Number(stat.size), mtimeMs: stat.mtimeMs, content });
        if (stat.isDirectory() && !stat.isSymbolicLink()) await visit(absolute);
      }
    }
    await visit(root);
    return out;
  };
  const ownerIndex = path.resolve(fixture.repo, git(fixture.repo, 'rev-parse', '--git-path', 'index').trim());
  const targetIndex = path.resolve(target, git(target, 'rev-parse', '--git-path', 'index').trim());
  return {
    worktrees: git(fixture.repo, 'worktree', 'list', '--porcelain', '-z'),
    refs: git(fixture.repo, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads'),
    target: await walk(target),
    ownerIndex: await fingerprint(ownerIndex),
    targetIndex: await fingerprint(targetIndex),
    worktreeAdmin: await walk(path.join(fixture.repo, '.git', 'worktrees')),
    state: await walk(stateRoot),
    telemetry: await walk(path.join(fixture.repo, 'tmp', 'agent-telemetry')),
  };
}

const fixtures = [];
try {
  await check('native process-table evidence stays fail-closed off Windows and observes this test on Windows', () => {
    const nonWindows = readProcessTable({ platform: 'linux' });
    assert.equal(nonWindows.ok, false);
    assert.match(nonWindows.reason, /implemented for win32 only \(platform=linux\)/i);

    if (process.platform === 'win32') {
      const native = readProcessTable();
      assert.equal(native.ok, true, native.reason);
      assert.equal(native.table.some((row) => Number(row?.ProcessId) === process.pid), true,
        `native process table did not contain test pid ${process.pid}`);
    } else {
      const native = readProcessTable();
      assert.equal(native.ok, false);
      assert.match(native.reason, new RegExp(`implemented for win32 only \\(platform=${process.platform}\\)`, 'i'));
    }
  });

  const primary = await makeRepo('primary');
  fixtures.push(primary);
  const target = addWorktree(primary, 'external target with spaces', 'codex/936-safe');
  const guessedBranch = `refs/heads/worktree-${path.basename(target).replace(/\s+/g, '-')}`;
  git(primary.repo, 'branch', guessedBranch.slice('refs/heads/'.length), 'HEAD');
  await fsp.mkdir(path.join(target, 'ignored'), { recursive: true });
  await fsp.writeFile(path.join(target, 'ignored', 'cache.txt'), 'ignored evidence', 'utf8');
  const junctionTarget = path.join(primary.root, 'junction target');
  await fsp.mkdir(junctionTarget);
  await fsp.writeFile(path.join(junctionTarget, 'must-survive.txt'), 'survive', 'utf8');
  await fsp.symlink(junctionTarget, path.join(target, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
  const stateRoot = path.join(primary.root, 'preview-state');

  // Tempdoc 952 Amendment A: ignored files leave only through a verified archive, so
  // `--allow-ignored` needs the manifest worktree-archive.cjs writes. The archive is taken BEFORE
  // the effect-free snapshot so the dry-run below is still proven to change nothing.
  const { archiveWorktree, DEFAULT_ARCHIVE_POLICY } = require('../dev/lib/worktree-archive.cjs');
  const archived = archiveWorktree({
    mainRepoRoot: primary.repo,
    worktreePath: target,
    resource: 'external-target-with-spaces',
    policy: DEFAULT_ARCHIVE_POLICY,
  });
  assert.equal(archived.refused, undefined, `archive refused: ${JSON.stringify(archived)}`);
  const archiveManifest = archived.manifestPath;

  await check('dry-run inventories ignored paths and has zero filesystem, ref, registry, state, or telemetry effects', async () => {
    const before = await snapshot(primary, target, stateRoot);
    const result = runCli(primary, target, ['--dry-run', '--allow-ignored', '--archive-manifest', archiveManifest], { stateRoot });
    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /preview:/i);
    assert.match(result.output, /codex\/936-safe/);
    assert.match(result.output, /ignored paths \(explicitly allowed\)/i);
    assert.match(result.output, /ignored[\\/]?$/im);
    assert.match(result.output, /node_modules[\\/]?$/im);
    assert.match(result.output, /no changes were made/i);
    assert.deepEqual(await snapshot(primary, target, stateRoot), before);
  });

  await check('ignored paths block removal unless --allow-ignored is explicit', () => {
    const result = runCli(primary, target);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /ignored paths are present/i);
    assert.equal(fs.existsSync(target), true);
  });

  await check('--allow-ignored without a verified archive manifest is refused intact (952 Amendment A)', async () => {
    const bare = runCli(primary, target, ['--allow-ignored']);
    assert.equal(bare.status, 1, bare.output);
    assert.match(bare.output, /verified archive/i);
    assert.equal(fs.existsSync(target), true);
    // A manifest that no longer matches the tree (the archived ignored file changed) is refused too.
    await fsp.writeFile(path.join(target, 'ignored', 'cache.txt'), 'changed after archive', 'utf8');
    const stale = runCli(primary, target, ['--allow-ignored', '--archive-manifest', archiveManifest]);
    assert.equal(stale.status, 1, stale.output);
    assert.match(stale.output, /does not verify/i);
    assert.equal(fs.existsSync(target), true);
    await fsp.writeFile(path.join(target, 'ignored', 'cache.txt'), 'ignored evidence', 'utf8');
  });

  await check('an exact external path with spaces removes its captured codex branch, preserves guessed branch and junction target', () => {
    const result = runCli(primary, target, ['--allow-ignored', '--archive-manifest', archiveManifest, '--delete-branch']);
    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /merge attribution requires an explicit known --session-id/i);
    assert.equal(fs.existsSync(target), false);
    assert.equal(refExists(primary.repo, 'refs/heads/codex/936-safe'), false);
    assert.equal(refExists(primary.repo, guessedBranch), true, 'similarly named guessed branch must survive');
    assert.equal(fs.readFileSync(path.join(junctionTarget, 'must-survive.txt'), 'utf8'), 'survive');
    assert.equal(registeredPaths(primary.repo).some((item) => path.resolve(item) === path.resolve(target)), false);
  });

  await check('detached HEAD removal skips branch deletion without guessing', () => {
    const detached = addWorktree(primary, 'detached target', null, { detached: true });
    const result = runCli(primary, detached, ['--delete-branch']);
    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /detached HEAD/i);
    assert.equal(fs.existsSync(detached), false);
  });

  await check('main and an arbitrary misleading pathname are refused intact', async () => {
    const mainResult = runCli(primary, primary.repo, ['--dry-run']);
    assert.equal(mainResult.status, 1, mainResult.output);
    assert.match(mainResult.output, /main worktree|owns this running script/i);
    const arbitrary = path.join(primary.root, '.claude', 'worktrees', 'misleading');
    await fsp.mkdir(arbitrary, { recursive: true });
    await fsp.writeFile(path.join(arbitrary, 'keep.txt'), 'keep');
    const arbitraryResult = runCli(primary, arbitrary, ['--allow-ignored']);
    assert.equal(arbitraryResult.status, 1, arbitraryResult.output);
    assert.match(arbitraryResult.output, /not one exact registered worktree/i);
    assert.equal(fs.readFileSync(path.join(arbitrary, 'keep.txt'), 'utf8'), 'keep');
  });

  await check('a worktree registered to a different repository is refused intact', async () => {
    const other = await makeRepo('other');
    fixtures.push(other);
    const foreignTarget = addWorktree(other, 'other repository target', 'codex/other');
    const result = runCli(primary, foreignTarget);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /not one exact registered worktree/i);
    assert.equal(fs.existsSync(foreignTarget), true);
  });

  await check('a separate-git-dir named .git refuses its exact registered main metadata entry on the destructive path', async () => {
    const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'remove-wt-936-separate-git-'));
    const main = path.join(root, 'separate main');
    const admin = path.join(root, 'metadata', '.git');
    await fsp.mkdir(path.dirname(admin), { recursive: true });
    git(root, 'init', '-b', 'main', '--separate-git-dir', admin, main);
    git(main, 'config', 'user.email', 'fixture@example.invalid');
    git(main, 'config', 'user.name', 'Fixture');
    await fsp.writeFile(path.join(main, 'tracked.txt'), 'separate\n');
    git(main, 'add', 'tracked.txt');
    git(main, 'commit', '-m', 'fixture');
    const linked = path.join(root, 'linked owner');
    git(main, 'worktree', 'add', '-b', 'codex/separate-owner', linked, 'HEAD');
    const fixture = { root, repo: linked, cli: await installProductionCli(linked) };
    fixtures.push(fixture);
    const workingTreeResult = runCli(fixture, main);
    assert.equal(workingTreeResult.status, 1, workingTreeResult.output);
    assert.match(workingTreeResult.output, /not one exact registered worktree/i);
    const registeredMain = path.dirname(admin);
    assert.ok(registeredPaths(linked).some((item) => path.resolve(item) === path.resolve(registeredMain)));
    const result = runCli(fixture, registeredMain);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /repository's main worktree/i);
    assert.equal(fs.existsSync(admin), true, 'the separate Git database must remain intact');
    assert.equal(fs.readFileSync(path.join(main, 'tracked.txt'), 'utf8'), 'separate\n');
  });

  await check('a junction alias to a registered target is refused while the real target remains intact', async () => {
    const realTarget = addWorktree(primary, 'alias real target', 'codex/alias-real');
    const alias = path.join(primary.root, 'alias path');
    await fsp.symlink(realTarget, alias, process.platform === 'win32' ? 'junction' : 'dir');
    const result = runCli(primary, alias);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /not one exact registered worktree|alias/i);
    assert.equal(fs.existsSync(path.join(realTarget, 'tracked.txt')), true);
  });

  await check('nested registered worktree protects its parent from recursive deletion', () => {
    const outer = addWorktree(primary, 'outer target', 'codex/outer');
    const nested = addWorktree(primary, 'nested registered', 'codex/nested', { parent: outer });
    const result = runCli(primary, outer, ['--allow-ignored']);
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /contains registered worktree/i);
    assert.equal(fs.existsSync(outer), true);
    assert.equal(fs.existsSync(nested), true);
  });

  await check('locked and dirty worktrees are refused intact for their actual reasons', async () => {
    const locked = addWorktree(primary, 'locked target', 'codex/locked');
    git(primary.repo, 'worktree', 'lock', '--reason', 'fixture lock', locked);
    const lockResult = runCli(primary, locked);
    assert.equal(lockResult.status, 1, lockResult.output);
    assert.match(lockResult.output, /locked.*fixture lock/i);
    assert.equal(fs.existsSync(locked), true);

    const dirty = addWorktree(primary, 'dirty target', 'codex/dirty');
    await fsp.writeFile(path.join(dirty, 'untracked.txt'), 'do not delete');
    const dirtyResult = runCli(primary, dirty);
    assert.equal(dirtyResult.status, 1, dirtyResult.output);
    assert.match(dirtyResult.output, /tracked, staged, or untracked changes/i);
    assert.equal(fs.readFileSync(path.join(dirty, 'untracked.txt'), 'utf8'), 'do not delete');
  });

  await check('targeted registration cleanup preserves an unrelated stale registration and branch', async () => {
    const staleA = addWorktree(primary, 'stale selected', 'codex/stale-selected');
    const staleB = addWorktree(primary, 'stale unrelated', 'codex/stale-unrelated');
    await fsp.rm(staleA, { recursive: true, force: true });
    await fsp.rm(staleB, { recursive: true, force: true });
    const before = registeredPaths(primary.repo);
    assert.ok(before.some((item) => path.resolve(item) === path.resolve(staleA)));
    assert.ok(before.some((item) => path.resolve(item) === path.resolve(staleB)));
    const result = runCli(primary, staleA);
    assert.equal(result.status, 0, result.output);
    const after = registeredPaths(primary.repo);
    assert.equal(after.some((item) => path.resolve(item) === path.resolve(staleA)), false);
    assert.equal(after.some((item) => path.resolve(item) === path.resolve(staleB)), true);
    assert.equal(refExists(primary.repo, 'refs/heads/codex/stale-unrelated'), true);
  });

  await check('merge attribution requires an explicit known session before lookup or writing', () => {
    const mergeCommit = git(primary.repo, 'rev-parse', 'HEAD').trim();
    let lookups = 0;
    const writes = [];
    const apparentlyMerged = () => {
      lookups += 1;
      return mergeCommit;
    };
    const telemetryWriter = (command, args, options) => {
      writes.push({ command, args, options });
      return { status: 0, stdout: 'fixture telemetry writer accepted merge link', stderr: '' };
    };

    const missingOutput = captureErrors(() => recordMergeLink({
      repoRoot: primary.repo,
      branch: 'codex/apparently-merged',
      mergeCommitArg: null,
      sessionIdArg: null,
      mergeCommitLookup: apparentlyMerged,
      spawnProcess: telemetryWriter,
    }));
    assert.equal(lookups, 0, 'missing --session-id must skip even an apparently merged PR lookup');
    assert.equal(writes.length, 0, 'missing --session-id must not invoke the telemetry writer');
    assert.match(missingOutput, /requires an explicit known --session-id/i);

    const unknownOutput = captureErrors(() => recordMergeLink({
      repoRoot: primary.repo,
      branch: 'codex/apparently-merged',
      mergeCommitArg: null,
      sessionIdArg: 'unknown',
      mergeCommitLookup: apparentlyMerged,
      spawnProcess: telemetryWriter,
    }));
    assert.equal(lookups, 0, '--session-id unknown must skip the merged PR lookup');
    assert.equal(writes.length, 0, '--session-id unknown must not invoke the telemetry writer');
    assert.match(unknownOutput, /requests unattributed teardown/i);

    const lookupOutput = captureErrors(() => recordMergeLink({
      repoRoot: primary.repo,
      branch: 'codex/apparently-merged',
      mergeCommitArg: null,
      sessionIdArg: 'session-936-lookup',
      mergeCommitLookup: apparentlyMerged,
      spawnProcess: telemetryWriter,
    }));
    assert.equal(lookups, 1, 'known explicit session retains merged PR lookup');
    assert.equal(writes.length, 1, 'known session with a merged PR invokes the writer once');
    assert.deepEqual(writes[0].args.slice(1), [
      mergeCommit,
      '--session-id',
      'session-936-lookup',
    ]);
    assert.match(lookupOutput, /fixture telemetry writer accepted merge link/i);

    const recordedOutput = captureErrors(() => recordMergeLink({
      repoRoot: primary.repo,
      branch: 'codex/known-session',
      mergeCommitArg: mergeCommit,
      sessionIdArg: 'session-936-known',
      mergeCommitLookup: () => { throw new Error('provided merge SHA must bypass lookup'); },
      spawnProcess: telemetryWriter,
    }));
    assert.equal(writes.length, 2, 'known session with a provided merge SHA invokes the writer once');
    assert.equal(writes[1].command, 'node');
    assert.deepEqual(writes[1].args, [
      path.join(primary.repo, 'scripts', 'agent-analytics', 'record-merge.mjs'),
      mergeCommit,
      '--session-id',
      'session-936-known',
    ]);
    assert.equal(writes[1].options.cwd, primary.repo);
    assert.match(recordedOutput, /fixture telemetry writer accepted merge link/i);
  });

  const liveChild = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });
  try {
    await new Promise((resolve) => setTimeout(resolve, 500));
    await check('the copied CLI retains native platform process-table behavior without fixture evidence', async () => {
      const nativeTarget = addWorktree(primary, 'native runtime target', 'codex/native-runtime');
      const nativeState = path.join(primary.root, 'native-runtime-state');
      const runId = 'native-live';
      await fsp.mkdir(path.join(nativeState, 'runs', runId), { recursive: true });
      await fsp.writeFile(path.join(nativeState, 'active.json'), JSON.stringify({
        kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
        provenance: { repoRoot: nativeTarget, distFromRoot: nativeTarget },
      }));
      await fsp.writeFile(path.join(nativeState, 'runs', runId, 'run.json'), JSON.stringify({
        schemaVersion: 1, runId, repoRoot: nativeTarget, dataDir: nativeTarget, apiPortActual: 65534,
        pids: { runnerPid: liveChild.pid, backendRootPid: liveChild.pid, frontendRootPid: liveChild.pid },
      }));
      const result = runCli(primary, nativeTarget, [], { stateRoot: nativeState });
      assert.equal(result.status, 1, result.output);
      if (process.platform === 'win32') {
        assert.match(result.output, /shared runtime.*active.*references/i);
      } else {
        assert.match(result.output, /process liveness is unknown.*implemented for win32 only/i);
      }
      assert.equal(fs.existsSync(nativeTarget), true);
    });

    await check('stale ownership lease cannot excuse a live target-referencing shared runtime', async () => {
      const runtimeTarget = addWorktree(primary, 'shared runtime target', 'codex/shared-runtime');
      const runtimeState = path.join(primary.root, 'shared-runtime-state');
      const runId = 'fixture-live';
      await fsp.mkdir(path.join(runtimeState, 'runs', runId), { recursive: true });
      await fsp.writeFile(path.join(runtimeState, 'active.json'), JSON.stringify({
        kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
        provenance: { repoRoot: runtimeTarget, distFromRoot: runtimeTarget },
        lease: { expiresAt: '2000-01-01T00:00:00Z' },
      }));
      await fsp.writeFile(path.join(runtimeState, 'runs', runId, 'run.json'), JSON.stringify({
        schemaVersion: 1, runId, repoRoot: runtimeTarget, dataDir: runtimeTarget, apiPortActual: 65534,
        pids: { runnerPid: liveChild.pid, backendRootPid: liveChild.pid, frontendRootPid: liveChild.pid },
      }));
      const result = runCli(primary, runtimeTarget, [], {
        stateRoot: runtimeState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /shared runtime.*active.*references/i);
      assert.equal(fs.existsSync(runtimeTarget), true);

      const dataTarget = addWorktree(primary, 'shared owned data target', 'codex/shared-owned-data');
      const dataState = path.join(primary.root, 'shared-owned-data-state');
      const dataRunId = 'fixture-owned-data';
      const ownedData = path.join(dataTarget, 'ignored', 'live-index');
      await fsp.mkdir(path.join(dataState, 'runs', dataRunId), { recursive: true });
      await fsp.writeFile(path.join(dataState, 'active.json'), JSON.stringify({
        kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: dataRunId,
        provenance: { repoRoot: primary.repo, distFromRoot: primary.repo },
      }));
      await fsp.writeFile(path.join(dataState, 'runs', dataRunId, 'run.json'), JSON.stringify({
        schemaVersion: 1, runId: dataRunId, repoRoot: primary.repo, dataDir: ownedData,
        resourceClaims: { dataDir: ownedData, expectedIndexBasePath: path.join(ownedData, 'index', 'default') },
        apiPortActual: 65534,
        pids: { runnerPid: liveChild.pid, backendRootPid: liveChild.pid, frontendRootPid: liveChild.pid },
      }));
      const dataResult = runCli(primary, dataTarget, ['--allow-ignored'], {
        stateRoot: dataState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(dataResult.status, 1, dataResult.output);
      assert.match(dataResult.output, /shared runtime.*active.*references/i);
      assert.equal(fs.existsSync(dataTarget), true);

      const sharedOwnedRoot = path.join(primary.root, 'shared owned root');
      await fsp.mkdir(sharedOwnedRoot, { recursive: true });
      const nestedDataTarget = addWorktree(primary, 'index', 'codex/shared-nested-data', { parent: sharedOwnedRoot });
      const nestedDataState = path.join(primary.root, 'shared-nested-data-state');
      const nestedDataRunId = 'fixture-nested-data';
      await fsp.mkdir(path.join(nestedDataState, 'runs', nestedDataRunId), { recursive: true });
      await fsp.writeFile(path.join(nestedDataState, 'active.json'), JSON.stringify({
        kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: nestedDataRunId,
        provenance: { repoRoot: primary.repo, distFromRoot: primary.repo },
      }));
      await fsp.writeFile(path.join(nestedDataState, 'runs', nestedDataRunId, 'run.json'), JSON.stringify({
        schemaVersion: 1, runId: nestedDataRunId, repoRoot: primary.repo, dataDir: sharedOwnedRoot,
        resourceClaims: { dataDir: sharedOwnedRoot }, apiPortActual: 65534,
        pids: { runnerPid: liveChild.pid, backendRootPid: liveChild.pid, frontendRootPid: liveChild.pid },
      }));
      const nestedDataResult = runCli(primary, nestedDataTarget, [], {
        stateRoot: nestedDataState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(nestedDataResult.status, 1, nestedDataResult.output);
      assert.match(nestedDataResult.output, /shared runtime.*active.*references/i);
      assert.equal(fs.existsSync(nestedDataTarget), true);

      const unknownTarget = addWorktree(primary, 'shared unknown data target', 'codex/shared-unknown-data');
      const unknownState = path.join(primary.root, 'shared-unknown-data-state');
      const unknownRunId = 'fixture-unknown-data';
      await fsp.mkdir(path.join(unknownState, 'runs', unknownRunId), { recursive: true });
      await fsp.writeFile(path.join(unknownState, 'active.json'), JSON.stringify({
        kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: unknownRunId,
        provenance: { repoRoot: primary.repo, distFromRoot: primary.repo },
      }));
      await fsp.writeFile(path.join(unknownState, 'runs', unknownRunId, 'run.json'), JSON.stringify({
        schemaVersion: 1, runId: unknownRunId, repoRoot: primary.repo, apiPortActual: 65534,
        pids: { runnerPid: liveChild.pid, backendRootPid: liveChild.pid, frontendRootPid: liveChild.pid },
      }));
      const unknownResult = runCli(primary, unknownTarget, [], {
        stateRoot: unknownState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(unknownResult.status, 1, unknownResult.output);
      assert.match(unknownResult.output, /unknown owned-path relation.*run\.dataDir is missing/i);
      assert.equal(fs.existsSync(unknownTarget), true);
    });

    await check('a live target-referencing foreign runtime blocks, while proven unrelated live state does not', async () => {
      const blocked = addWorktree(primary, 'foreign runtime blocked', 'codex/foreign-blocked');
      const allowed = addWorktree(primary, 'foreign runtime unrelated', 'codex/foreign-unrelated');
      const unknown = addWorktree(primary, 'foreign runtime unknown', 'codex/foreign-unknown');
      const foreignState = path.join(primary.root, 'foreign-runtime-state');
      await fsp.mkdir(path.join(foreignState, 'foreign'), { recursive: true });
      const recordFile = path.join(foreignState, 'foreign', 'jseval-live.json');
      const record = {
        schemaVersion: 1, producer: 'jseval', recordId: 'jseval-live', pid: liveChild.pid,
        ports: { api: 65534 }, repoRoot: blocked, dataDir: blocked,
      };
      await fsp.writeFile(recordFile, JSON.stringify(record));
      const blockedResult = runCli(primary, blocked, [], {
        stateRoot: foreignState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(blockedResult.status, 1, blockedResult.output);
      assert.match(blockedResult.output, /foreign runtime.*owns a path/i);
      assert.equal(fs.existsSync(blocked), true);

      record.repoRoot = primary.repo;
      record.dataDir = path.join(blocked, 'ignored', 'live-index');
      await fsp.writeFile(recordFile, JSON.stringify(record));
      const ownedDataResult = runCli(primary, blocked, ['--allow-ignored'], {
        stateRoot: foreignState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(ownedDataResult.status, 1, ownedDataResult.output);
      assert.match(ownedDataResult.output, /foreign runtime.*owns a path/i);

      const foreignOwnedRoot = path.join(primary.root, 'foreign owned root');
      await fsp.mkdir(foreignOwnedRoot, { recursive: true });
      const nestedOwnedTarget = addWorktree(primary, 'index', 'codex/foreign-nested-data', { parent: foreignOwnedRoot });
      record.dataDir = foreignOwnedRoot;
      await fsp.writeFile(recordFile, JSON.stringify(record));
      const nestedOwnedResult = runCli(primary, nestedOwnedTarget, [], {
        stateRoot: foreignState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(nestedOwnedResult.status, 1, nestedOwnedResult.output);
      assert.match(nestedOwnedResult.output, /foreign runtime.*owns a path/i);
      assert.equal(fs.existsSync(nestedOwnedTarget), true);

      delete record.dataDir;
      await fsp.writeFile(recordFile, JSON.stringify(record));
      const unknownResult = runCli(primary, unknown, [], {
        stateRoot: foreignState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(unknownResult.status, 1, unknownResult.output);
      assert.match(unknownResult.output, /unknown owned-path relation.*dataDir is missing/i);
      assert.equal(fs.existsSync(unknown), true);

      record.dataDir = primary.repo;
      await fsp.writeFile(recordFile, JSON.stringify(record));
      const allowedResult = runCli(primary, allowed, [], {
        stateRoot: foreignState,
        processTablePids: [liveChild.pid],
      });
      assert.equal(allowedResult.status, 0, allowedResult.output);
      assert.match(allowedResult.output, /proven unrelated provenance/i);
      assert.equal(fs.existsSync(allowed), false);
    });
  } finally {
    try { process.kill(liveChild.pid, 'SIGKILL'); } catch { /* already exited */ }
  }

  await check('malformed runtime state and obstructed register paths fail closed', async () => {
    const malformedTarget = addWorktree(primary, 'malformed runtime target', 'codex/runtime-unknown');
    const malformedState = path.join(primary.root, 'malformed-state');
    await fsp.mkdir(malformedState, { recursive: true });
    await fsp.writeFile(path.join(malformedState, 'active.json'), '{ broken');
    const malformedResult = runCli(primary, malformedTarget, ['--dry-run'], { stateRoot: malformedState });
    assert.equal(malformedResult.status, 1, malformedResult.output);
    assert.match(malformedResult.output, /shared runtime state is unknown/i);
    assert.equal(fs.existsSync(malformedTarget), true);

    const obstructedTarget = addWorktree(primary, 'obstructed register target', 'codex/register-obstructed');
    const obstructedState = path.join(primary.root, 'obstructed-state');
    await fsp.mkdir(obstructedState, { recursive: true });
    await fsp.writeFile(path.join(obstructedState, 'agent-spawns'), 'not a directory');
    const obstructedResult = runCli(primary, obstructedTarget, ['--dry-run'], { stateRoot: obstructedState });
    assert.equal(obstructedResult.status, 1, obstructedResult.output);
    assert.match(obstructedResult.output, /agent-spawns safety inspection failed.*not a directory/i);
    assert.equal(fs.existsSync(obstructedTarget), true);
  });

  await check('visible pending shared and foreign atomic writes are unknown blockers', async () => {
    const activeTarget = addWorktree(primary, 'pending active target', 'codex/pending-active');
    const activeState = path.join(primary.root, 'pending-active-state');
    await fsp.mkdir(activeState, { recursive: true });
    await fsp.writeFile(path.join(activeState, 'active.json.tmp'), '{}');
    const activeResult = runCli(primary, activeTarget, ['--dry-run'], { stateRoot: activeState });
    assert.equal(activeResult.status, 1, activeResult.output);
    assert.match(activeResult.output, /pending atomic-write.*active\.json\.tmp/i);
    assert.equal(fs.existsSync(activeTarget), true);

    const runTarget = addWorktree(primary, 'pending run target', 'codex/pending-run');
    const runState = path.join(primary.root, 'pending-run-state');
    const runId = 'pending-run';
    const runDir = path.join(runState, 'runs', runId);
    await fsp.mkdir(runDir, { recursive: true });
    await fsp.writeFile(path.join(runState, 'active.json'), JSON.stringify({
      kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
      provenance: { repoRoot: runTarget, distFromRoot: runTarget },
    }));
    const run = {
      schemaVersion: 1, runId, repoRoot: runTarget, dataDir: runTarget, apiPortActual: 65534,
      pids: { runnerPid: 2147483647, backendRootPid: 2147483646, frontendRootPid: 2147483645 },
    };
    await fsp.writeFile(path.join(runDir, 'run.json'), JSON.stringify(run));
    await fsp.writeFile(path.join(runDir, 'run.json.tmp'), JSON.stringify(run));
    const runResult = runCli(primary, runTarget, ['--dry-run'], { stateRoot: runState });
    assert.equal(runResult.status, 1, runResult.output);
    assert.match(runResult.output, /pending atomic-write.*run\.json\.tmp/i);
    assert.equal(fs.existsSync(runTarget), true);

    const foreignTarget = addWorktree(primary, 'pending foreign target', 'codex/pending-foreign');
    const foreignState = path.join(primary.root, 'pending-foreign-state');
    await fsp.mkdir(path.join(foreignState, 'foreign'), { recursive: true });
    await fsp.writeFile(path.join(foreignState, 'foreign', '.jseval-pending.tmp'), '{}');
    const foreignResult = runCli(primary, foreignTarget, ['--dry-run'], { stateRoot: foreignState });
    assert.equal(foreignResult.status, 1, foreignResult.output);
    assert.match(foreignResult.output, /foreign runtime register has pending atomic-write entry/i);
    assert.equal(fs.existsSync(foreignTarget), true);
  });

  await check('unsafe run ids, mismatched run records, non-absolute provenance, and missing pids remain unknown blockers', async () => {
    const badRunIdTarget = addWorktree(primary, 'bad run id target', 'codex/bad-run-id');
    const badRunIdState = path.join(primary.root, 'bad-run-id-state');
    await fsp.mkdir(badRunIdState, { recursive: true });
    await fsp.writeFile(path.join(badRunIdState, 'active.json'), JSON.stringify({
      kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: '..',
      provenance: { repoRoot: badRunIdTarget, distFromRoot: badRunIdTarget },
    }));
    const badRunId = runCli(primary, badRunIdTarget, ['--dry-run'], { stateRoot: badRunIdState });
    assert.equal(badRunId.status, 1, badRunId.output);
    assert.match(badRunId.output, /unsupported or incomplete shape/i);

    const mismatchTarget = addWorktree(primary, 'mismatched run target', 'codex/mismatch-run');
    const mismatchState = path.join(primary.root, 'mismatch-state');
    await fsp.mkdir(path.join(mismatchState, 'runs', 'expected'), { recursive: true });
    await fsp.writeFile(path.join(mismatchState, 'active.json'), JSON.stringify({
      kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: 'expected',
      provenance: { repoRoot: mismatchTarget, distFromRoot: mismatchTarget },
    }));
    await fsp.writeFile(path.join(mismatchState, 'runs', 'expected', 'run.json'), JSON.stringify({
      schemaVersion: 99, runId: 'different', repoRoot: mismatchTarget,
    }));
    const mismatch = runCli(primary, mismatchTarget, ['--dry-run'], { stateRoot: mismatchState });
    assert.equal(mismatch.status, 1, mismatch.output);
    assert.match(mismatch.output, /unsupported shape or mismatched runId/i);

    const mixedPidTarget = addWorktree(primary, 'mixed pid target', 'codex/mixed-pid');
    const mixedPidState = path.join(primary.root, 'mixed-pid-state');
    const mixedRunId = 'mixed-pids';
    await fsp.mkdir(path.join(mixedPidState, 'runs', mixedRunId), { recursive: true });
    await fsp.writeFile(path.join(mixedPidState, 'active.json'), JSON.stringify({
      kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: mixedRunId,
      provenance: { repoRoot: mixedPidTarget, distFromRoot: mixedPidTarget },
    }));
    await fsp.writeFile(path.join(mixedPidState, 'runs', mixedRunId, 'run.json'), JSON.stringify({
      schemaVersion: 1, runId: mixedRunId, repoRoot: mixedPidTarget, dataDir: mixedPidTarget,
      apiPortActual: 65534,
      pids: { runnerPid: 2147483647, backendRootPid: 'unverified-live-child', frontendRootPid: 2147483646 },
    }));
    const mixedPid = runCli(primary, mixedPidTarget, ['--dry-run'], { stateRoot: mixedPidState });
    assert.equal(mixedPid.status, 1, mixedPid.output);
    assert.match(mixedPid.output, /incomplete process identity.*expected pid fields/i);

    const foreignTarget = addWorktree(primary, 'bad foreign target', 'codex/bad-foreign');
    const foreignState = path.join(primary.root, 'bad-foreign-state');
    await fsp.mkdir(path.join(foreignState, 'foreign'), { recursive: true });
    await fsp.writeFile(path.join(foreignState, 'foreign', 'bad.json'), JSON.stringify({
      schemaVersion: 1, producer: 'fixture', recordId: 'bad', pid: null,
      ports: { api: 65534 }, repoRoot: 'relative/worktree',
    }));
    const badForeign = runCli(primary, foreignTarget, ['--dry-run'], { stateRoot: foreignState });
    assert.equal(badForeign.status, 1, badForeign.output);
    assert.match(badForeign.output, /declares no usable pid/i);
  });

  await check('a timed-out runtime probe is unknown and cannot prove stale', async () => {
    const timeoutTarget = addWorktree(primary, 'timeout runtime target', 'codex/timeout-runtime');
    const timeoutState = path.join(primary.root, 'timeout-state');
    const portFile = path.join(primary.root, 'timeout-port.txt');
    const server = spawn(process.execPath, [
      '-e',
      "const fs=require('fs'),net=require('net');const s=net.createServer(()=>{});s.listen(0,'127.0.0.1',()=>fs.writeFileSync(process.argv[1],String(s.address().port)));setInterval(()=>{},1000)",
      portFile,
    ], { stdio: 'ignore', windowsHide: true });
    try {
      const port = Number(await waitForFile(portFile));
      assert.ok(Number.isInteger(port) && port > 0);
      await fsp.mkdir(path.join(timeoutState, 'foreign'), { recursive: true });
      await fsp.writeFile(path.join(timeoutState, 'foreign', 'timeout.json'), JSON.stringify({
        schemaVersion: 1, producer: 'fixture', recordId: 'timeout', pid: 2147483647,
        ports: { api: port }, repoRoot: timeoutTarget, dataDir: timeoutTarget,
      }));
      const result = runCli(primary, timeoutTarget, ['--dry-run'], {
        stateRoot: timeoutState,
        processTablePids: [server.pid],
      });
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /API probe is TIMED_OUT/i);
      assert.equal(fs.existsSync(timeoutTarget), true);
    } finally {
      try { process.kill(server.pid, 'SIGKILL'); } catch { /* already exited */ }
    }
  });

  await check('membership/main guards are causal: disabling them lets only a disposable main tree be erased', async () => {
    const mutant = await makeRepo('mutant-membership');
    fixtures.push(mutant);
    await fsp.rm(path.join(mutant.repo, 'scripts'), { recursive: true, force: true });
    const linked = addWorktree(mutant, 'mutant owner', 'codex/mutant-owner');
    const cli = await installProductionCli(linked);
    const linkedFixture = { root: mutant.root, repo: linked, cli };
    await mutateInstalledCli(cli, (source) => source
      .replace(/  if \(samePath\(abs, facts\.mainWorktree\)\) fail\([^\n]+\);\r?\n/, '')
      .replace(/  if \(samePath\(targetGitDir, facts\.commonDir\)\) \{\r?\n    fail\([^\n]+\);\r?\n  \}\r?\n/, ''));
    const result = runCli(linkedFixture, mutant.repo);
    assert.equal(result.status, 1, result.output);
    assert.equal(fs.existsSync(path.join(mutant.repo, 'tracked.txt')), false,
      'without both main guards, deletion reaches the disposable main tree before Git rejects registration cleanup');
  });

  await check('runtime guard is causal: disabling its live-shared blocker erases only a disposable held tree', async () => {
    const mutant = await makeRepo('mutant-runtime');
    fixtures.push(mutant);
    const target = addWorktree(mutant, 'mutant-runtime-target', 'codex/mutant-runtime');
    await mutateInstalledCli(mutant.cli, (source) => source.replace(
      'blockers.push(`shared runtime run ${runId} is active and ${relationText}`);',
      'notes.push(`MUTANT ignored live shared runtime ${runId} ${relationText}`);',
    ));
    const stateRoot = path.join(mutant.root, 'mutant-runtime-state');
    const runId = 'mutant-live';
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });
    try {
      await new Promise((resolve) => setTimeout(resolve, 300));
      await fsp.mkdir(path.join(stateRoot, 'runs', runId), { recursive: true });
      await fsp.writeFile(path.join(stateRoot, 'active.json'), JSON.stringify({
        kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
        provenance: { repoRoot: target, distFromRoot: target },
      }));
      await fsp.writeFile(path.join(stateRoot, 'runs', runId, 'run.json'), JSON.stringify({
        schemaVersion: 1, runId, repoRoot: target, dataDir: target, apiPortActual: 65534,
        pids: { runnerPid: child.pid, backendRootPid: child.pid, frontendRootPid: child.pid },
      }));
      const result = runCli(mutant, target, [], { stateRoot, processTablePids: [child.pid] });
      assert.equal(result.status, 0, result.output);
      assert.equal(fs.existsSync(target), false, 'mutant proves the live-runtime blocker prevents deletion');
    } finally {
      try { process.kill(child.pid, 'SIGKILL'); } catch { /* already exited */ }
    }
  });

  await check('captured-branch selection is causal: restoring the guessed name deletes only that disposable wrong branch', async () => {
    const mutant = await makeRepo('mutant-branch');
    fixtures.push(mutant);
    const target = addWorktree(mutant, 'mutant-branch-target', 'codex/mutant-actual');
    const guessed = `worktree-${path.basename(target)}`;
    git(mutant.repo, 'branch', guessed, 'HEAD');
    await mutateInstalledCli(mutant.cli, (source) => source.replace(
      "const short = captured.branchRef.slice('refs/heads/'.length);",
      "const short = 'worktree-' + path.basename(captured.path);",
    ));
    const result = runCli(mutant, target, ['--delete-branch']);
    assert.equal(result.status, 0, result.output);
    assert.equal(refExists(mutant.repo, 'refs/heads/codex/mutant-actual'), true,
      'mutant leaves the actual captured branch behind');
    assert.equal(refExists(mutant.repo, `refs/heads/${guessed}`), false,
      'mutant deletes the guessed branch, proving the original survival assertion is causal');
  });
} finally {
  for (const fixture of fixtures.reverse()) {
    await fsp.rm(fixture.root, { recursive: true, force: true }).catch(() => {});
  }
}

if (failures.length) {
  console.error(`936-remove-worktree-cli.test: ${failures.length} FAILED / ${passed} passed`);
  for (const failure of failures) console.error(`  ✗ ${failure}`);
  process.exit(1);
}
console.log(`936-remove-worktree-cli.test: ${passed} passed`);
