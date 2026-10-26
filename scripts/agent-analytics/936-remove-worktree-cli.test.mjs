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
// Preserve the measured runner spelling before ordinary fixture roots are canonicalized.
const NATIVE_ALIAS_TEMP = process.env.ARCHIVE_ALIAS_TEMP ?? os.tmpdir();
const { recordMergeLink } = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'remove-worktree.cjs'));
const { readProcessTable } = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'lib', 'process-identity.cjs'));
const FIXTURE_PROCESS_TABLE_ENV = 'JUSTSEARCH_936_FIXTURE_PROCESS_TABLE_PIDS';
const PROCESS_IDENTITY_REL = 'scripts/dev/lib/process-identity.cjs';
const CLI_FILES = [
  'scripts/lib/native-targets.cjs',
  'contracts/native-targets/spec.v1.json',
  'scripts/lib/path-identity.cjs',
  'scripts/dev/remove-worktree.cjs',
  'scripts/dev/lib/process-identity.cjs',
  'scripts/dev/lib/process-record.cjs',
  'scripts/dev/lib/agent-spawn-record.cjs',
  'scripts/dev/lib/agent-spawn-reaper.cjs',
  'scripts/dev/lib/recorded-termination.cjs',
  'scripts/dev/lib/terminate-process.ps1',
  'scripts/dev/lib/agent-spawn-sweep.cjs',
  'scripts/dev/lib/ownership-verdict.cjs',
  'scripts/dev/lib/agent-identity.cjs',
  'scripts/dev/lib/owner-presence.cjs',
  'scripts/dev/lib/worktree-archive.cjs',
  'scripts/dev/lib/worktree-resource-id.cjs',
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
  const createdRoot = await fsp.mkdtemp(path.join(os.tmpdir(), `remove-wt-936-${label}-`));
  const root = process.platform === 'win32' ? fs.realpathSync.native(createdRoot) : createdRoot;
  const repo = path.join(root, 'owner repo');
  await fsp.mkdir(repo, { recursive: true });
  git(repo, 'init', '-b', 'main');
  git(repo, 'config', 'user.email', 'fixture@example.invalid');
  git(repo, 'config', 'user.name', 'Fixture');
  await fsp.writeFile(path.join(repo, '.gitignore'), 'ignored/\nnode_modules\n', 'utf8');
  await fsp.writeFile(path.join(repo, 'tracked.txt'), 'base\n', 'utf8');
  git(repo, 'add', '.gitignore', 'tracked.txt');
  let nativeSymlinks = true;
  try {
    // Every admission/runtime/lock/registration regression also carries a committed native link.
    await fsp.writeFile(path.join(root, 'gate-sentinel'), 'external gate sentinel\n');
    await fsp.symlink('../gate-sentinel', path.join(repo, 'gate-link'));
    git(repo, 'config', 'core.symlinks', 'true');
    git(repo, 'add', 'gate-link');
  } catch (err) {
    if (process.platform !== 'win32' || !['EPERM', 'EACCES', 'ENOSYS'].includes(err.code)) throw err;
    nativeSymlinks = false;
    console.log(`UNVERIFIED native Windows link removal scenarios: ${err.code}`);
  }
  git(repo, 'commit', '-m', 'fixture');
  return { root, repo, cli: await installProductionCli(repo), nativeSymlinks };
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
  const members = registeredPaths(fixture.repo).filter((entry) => process.platform === 'win32'
    ? entry.toLowerCase() === path.resolve(target).toLowerCase() : entry === path.resolve(target));
  assert.equal(members.length, 1, 'capture canonical Git membership before any removal');
  return members[0];
}

function runCli(fixture, target, args = [], {
  stateRoot = path.join(fixture.root, 'isolated-state'),
  env = {},
  processTablePids,
  cwd = fixture.repo,
  cli = fixture.cli,
  nodeArgs = [],
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
  const result = spawnSync(process.execPath, [...nodeArgs, cli, target, ...args], {
    cwd,
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

function shortSpelling(directory) {
  const key = (value) => path.resolve(value).toLowerCase();
  const inspect = (spelling) => {
    assert.ok(typeof spelling === 'string' && path.isAbsolute(spelling), 'Native alias spelling must be absolute');
    const root = path.parse(spelling).root;
    let current = root;
    let stat;
    for (const part of ['', ...spelling.slice(root.length).split(/[\\/]+/).filter(Boolean)]) {
      assert.ok(part !== '.' && part !== '..', 'Native alias spelling must not contain dot components');
      current = part ? path.join(current, part) : current;
      stat = fs.lstatSync(current, { bigint: true });
      assert.ok(stat.isDirectory() && !stat.isSymbolicLink(), `Native alias must traverse plain directories: ${current}`);
    }
    assert.equal(typeof stat.dev, 'bigint');
    assert.equal(typeof stat.ino, 'bigint');
    assert.ok(stat.ino > 0n, 'Native directory identity must be unambiguous');
    return { real: fs.realpathSync.native(spelling), dev: stat.dev, ino: stat.ino };
  };
  const native = inspect(NATIVE_ALIAS_TEMP);
  const canonical = inspect(native.real);
  assert.equal(key(native.real), key(canonical.real));
  assert.equal(native.dev, canonical.dev);
  assert.equal(native.ino, canonical.ino);
  if (key(NATIVE_ALIAS_TEMP) === key(canonical.real)) return null; // No genuine native alias capability.

  const target = inspect(directory);
  const relative = path.relative(canonical.real, target.real);
  assert.ok(!path.isAbsolute(relative) && relative !== '..' && !relative.startsWith(`..${path.sep}`),
    'Owned fixture is outside the measured native temp alias; fixture geometry needs repair');
  const short = path.join(NATIVE_ALIAS_TEMP, relative);
  assert.notEqual(key(short), key(directory), 'Native alias must be a distinct spelling');
  const alias = inspect(short);
  assert.equal(key(alias.real), key(target.real));
  assert.equal(alias.dev, target.dev);
  assert.equal(alias.ino, target.ino);
  return short;
}

// Deterministic mutations in an owned copied CLI/library; no timing sleeps or product hook.
async function installLateMutation(fixture, { target, manifestPath, point, kind, rewrite = false }) {
  const probe = path.join(fixture.root, 'late-mutation.json');
  const action = `(() => {
    const fs = require('node:fs');
    const target = ${JSON.stringify(target)};
    const before = fs.lstatSync(target, { bigint: true }).ino.toString();
    if (${JSON.stringify(kind)} === 'root') {
      fs.renameSync(target, target + '-previous');
      fs.cpSync(target + '-previous', target, { recursive: true, dereference: false, verbatimSymlinks: true });
      if (${rewrite}) {
        const file = ${JSON.stringify(manifestPath)};
        const manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
        manifest.worktreePath = target;
        fs.writeFileSync(file, JSON.stringify(manifest));
      }
    } else if (${JSON.stringify(kind)} === 'head') {
      require('node:child_process').execFileSync('git', ['commit', '--allow-empty', '-m', 'late head'], { cwd: target, stdio: 'pipe' });
    } else if (${JSON.stringify(kind)} === 'branch') {
      require('node:child_process').execFileSync('git', ['branch', '-m', 'late-renamed-branch'], { cwd: target, stdio: 'pipe' });
    } else if (${JSON.stringify(kind)} === 'ignored') {
      fs.mkdirSync(require('node:path').join(target, 'ignored'), { recursive: true });
      fs.writeFileSync(require('node:path').join(target, 'ignored', 'late.txt'), 'never retained');
    } else fs.writeFileSync(require('node:path').join(target, 'draft.txt'), 'late unretained bytes');
    fs.writeFileSync(${JSON.stringify(probe)}, JSON.stringify({ before, after: fs.lstatSync(target, { bigint: true }).ino.toString() }));
  })()`;
  if (point === 'helper') {
    const needle = 'consult = await consultAgentSpawnsForTeardown({ mainRepoRoot, targetPath: admission.abs, callerIdentity });';
    await mutateInstalledCli(fixture.cli, (source) => source.replace(needle,
      `consult = await (async () => { const pending = consultAgentSpawnsForTeardown({ mainRepoRoot, targetPath: admission.abs, callerIdentity }); ${action}; return await pending; })();`));
  } else {
    const file = path.join(fixture.repo, 'scripts/dev/lib/worktree-archive.cjs');
    fs.renameSync(file, path.join(path.dirname(file), 'worktree-archive.production.cjs'));
    fs.writeFileSync(file, `const p = require('./worktree-archive.production.cjs');
let verification = 0, classification = 0, performed = false;
function mutate() { if (performed) return; performed = true; ${action}; }
module.exports = { ...p,
  verifyArchive(args) {
    const pass = ++verification;
    return p.verifyArchive({ ...args, git(argv, options) {
      const out = p.defaultGit(argv, options);
      if (${JSON.stringify(point)} === 'hash' && pass === 2 && argv[0] === 'hash-object') mutate();
      return out;
    }});
  },
  classifyIgnored(args) {
    const out = p.classifyIgnored(args);
    if (${JSON.stringify(point)} === 'ignored' && ++classification === 2) mutate();
    return out;
  }
};\n`);
  }
  return probe;
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
  await check('native process-table evidence observes this test on supported OSes and fails closed elsewhere', () => {
    if (process.platform !== 'linux') {
      const nonWindows = readProcessTable({ platform: 'linux' });
      assert.equal(nonWindows.ok, false);
      assert.match(nonWindows.reason, /implemented for win32 only \(platform=linux\)/i);
    }

    if (process.platform === 'win32') {
      const native = readProcessTable();
      assert.equal(native.ok, true, native.reason);
      assert.equal(native.table.some((row) => Number(row?.ProcessId) === process.pid), true,
        `native process table did not contain test pid ${process.pid}`);
    } else if (process.platform === 'linux') {
      const native = readProcessTable();
      assert.equal(native.ok, true, native.reason);
      const self = native.table.find((row) => Number(row?.ProcessId) === process.pid);
      assert.equal(self?.processIdentity?.kind, 'linux-proc-v1');
      assert.equal(self.processIdentity.pid, process.pid);
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

  for (const kind of ['head', 'branch']) await check(`a ${kind} move during final archive hashing refuses before deletion`, async () => {
    const fx = await makeRepo(`late-${kind}`);
    fixtures.push(fx);
    const dest = addWorktree(fx, `late-${kind}`, `codex/936-late-${kind}`);
    fs.writeFileSync(path.join(dest, 'draft.txt'), 'retained draft\n');
    const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: dest, resource: `late-${kind}`, policy: DEFAULT_ARCHIVE_POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    const probe = await installLateMutation(fx, { target: dest, manifestPath: saved.manifestPath, point: 'hash', kind });
    const result = runCli(fx, dest, ['--archive-manifest', saved.manifestPath]);
    assert.ok(fs.existsSync(probe), 'the final hashing seam really executed');
    assert.equal(result.status, 1, result.output);
    assert.match(result.output, /branch or HEAD changed during archive verification/);
    assert.equal(fs.readFileSync(path.join(dest, 'draft.txt'), 'utf8'), 'retained draft\n');
    assert.equal(git(fx.repo, 'rev-parse', saved.stateRef).trim(), saved.stateCommit);
    assert.ok(registeredPaths(fx.repo).includes(dest));
  });

  if (process.platform === 'win32') {
    async function aliasedTarget(label, { scriptOwned = false } = {}) {
      const fx = await makeRepo(`alias-${label}`);
      fixtures.push(fx);
      const dest = addWorktree(fx, `long target ${label}`, `codex/936-${label}`);
      const short = shortSpelling(dest);
      if (!short) { console.log(`UNVERIFIED S17 ${label}: native 8.3 spelling unavailable`); return null; }
      if (scriptOwned) await installProductionCli(dest);
      fs.writeFileSync(path.join(dest, 'draft.txt'), 'retained draft\n');
      return { fx, dest, short };
    }
    const valid = await aliasedTarget('valid-legacy');
    if (valid) await check('actual removal accepts a genuine short manifest only for the admitted canonical target', () => {
      const { fx, dest, short } = valid;
      const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: short, resource: 'valid-legacy', policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      const bytes = fs.readFileSync(saved.manifestPath);
      assert.ok(registeredPaths(fx.repo).includes(dest));
      const directAlias = runCli(fx, short, ['--dry-run', '--archive-manifest', saved.manifestPath]);
      assert.equal(directAlias.status, 1, directAlias.output);
      assert.match(directAlias.output, /not one exact registered worktree|resolves through an alias/);
      const result = runCli(fx, dest, ['--archive-manifest', saved.manifestPath]);
      assert.equal(result.status, 0, result.output);
      assert.equal(fs.existsSync(dest), false);
      assert.deepEqual(fs.readFileSync(saved.manifestPath), bytes);
      assert.equal(git(fx.repo, 'show', `${saved.stateRef}:draft.txt`), 'retained draft\n');
    });

    const wrong = await aliasedTarget('different-identity');
    if (wrong) await check('equal basename, HEAD and bytes never substitute for physical manifest identity', async () => {
      const { fx, dest, short } = wrong;
      const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: short, resource: 'different-identity', policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      const parent = path.join(fx.root, 'other-parent');
      fs.mkdirSync(parent);
      const other = addWorktree(fx, path.basename(dest), 'codex/936-other-identity', { parent });
      fs.writeFileSync(path.join(other, 'draft.txt'), 'retained draft\n');
      assert.equal(git(other, 'rev-parse', 'HEAD'), saved.head + '\n');
      const before = await snapshot(fx, other, path.join(fx.root, 'isolated-state'));
      const result = runCli(fx, other, ['--archive-manifest', saved.manifestPath]);
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /archive manifest/);
      assert.deepEqual(await snapshot(fx, other, path.join(fx.root, 'isolated-state')), before);
    });

    for (const kind of ['root', 'ancestor']) {
      const f = await aliasedTarget(`junction-${kind}`);
      if (!f) continue;
      await check(`manifest proof refuses ${kind} junction traversal into an admitted target`, async () => {
        const { fx, dest } = f;
        const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: dest, resource: `junction-${kind}`, policy: DEFAULT_ARCHIVE_POLICY });
        assert.equal(saved.refused, undefined, JSON.stringify(saved));
        const alias = path.join(fx.root, 'junction-spelling');
        fs.symlinkSync(kind === 'root' ? dest : path.dirname(dest), alias, 'junction');
        saved.manifest.worktreePath = kind === 'root' ? alias : path.join(alias, path.basename(dest));
        fs.writeFileSync(saved.manifestPath, JSON.stringify(saved.manifest));
        const before = await snapshot(fx, dest, path.join(fx.root, 'isolated-state'));
        const result = runCli(fx, dest, ['--archive-manifest', saved.manifestPath]);
        assert.equal(result.status, 1, result.output);
        assert.match(result.output, /target proof failed|archive manifest.*not /);
        assert.deepEqual(await snapshot(fx, dest, path.join(fx.root, 'isolated-state')), before);
      });
    }

    const nested = await aliasedTarget('nested-alias');
    if (nested) await check('a Git nested entry created through the opposite alias still protects its parent', async () => {
      const { fx, dest, short } = nested;
      const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: short, resource: 'nested-alias', policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      git(fx.repo, 'worktree', 'add', '-b', 'codex/936-nested-alias-child', path.join(short, 'nested'));
      const entry = registeredPaths(fx.repo).find((item) => path.basename(item) === 'nested');
      assert.ok(entry);
      console.log(`S17 actual nested Git spelling: ${entry}; admitted parent: ${dest}`);
      const before = await snapshot(fx, dest, path.join(fx.root, 'isolated-state'));
      const result = runCli(fx, dest, ['--archive-manifest', saved.manifestPath]);
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /contains registered worktree/);
      assert.deepEqual(await snapshot(fx, dest, path.join(fx.root, 'isolated-state')), before);
    });

    const mainFx = await makeRepo('legacy-main-markers');
    fixtures.push(mainFx);
    const shortMain = shortSpelling(mainFx.repo);
    if (!shortMain) console.log('UNVERIFIED S17 main removal aliases: native 8.3 spelling unavailable');
    else await check('legacy wrong main markers and a retained archive never override main Git identity', async () => {
      git(mainFx.repo, 'config', 'branch.main.justsearch-resource', 'legacy-wrong-main');
      const owner = addWorktree(mainFx, 'linked-script-owner', 'codex/936-main-proof-owner');
      const ownerCli = await installProductionCli(owner);
      const fx = { ...mainFx, repo: owner, cli: ownerCli };
      const saved = archiveWorktree({ mainRepoRoot: mainFx.repo, worktreePath: shortMain, resource: 'legacy-main', policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      const before = await snapshot(mainFx, mainFx.repo, path.join(mainFx.root, 'isolated-state'));
      const config = fs.readFileSync(path.join(mainFx.repo, '.git/config'));
      for (const target of [mainFx.repo, shortMain]) {
        const result = runCli(fx, target, ['--archive-manifest', saved.manifestPath]);
        assert.equal(result.status, 1, result.output);
        assert.match(result.output, target === mainFx.repo ? /main worktree|main-worktree|main Git-directory/
          : /main worktree|main-worktree|main Git-directory|not one exact registered worktree/);
        assert.deepEqual(await snapshot(mainFx, mainFx.repo, path.join(mainFx.root, 'isolated-state')), before);
        assert.deepEqual(fs.readFileSync(path.join(mainFx.repo, '.git/config')), config);
        assert.equal(fs.readFileSync(path.join(mainFx.root, 'gate-sentinel'), 'utf8'), 'external gate sentinel\n');
      }
    });

    for (const spelling of ['ordinary', 'legacy']) for (const protectedKind of ['script', 'cwd-root', 'cwd-subdir']) {
      const label = `${spelling}-${protectedKind}`;
      const f = await aliasedTarget(label, { scriptOwned: protectedKind === 'script' });
      if (!f) continue;
      await check(`${spelling} manifest cannot remove an opposite-alias protected ${protectedKind}`, async () => {
        const { fx, dest, short } = f;
        fs.mkdirSync(path.join(dest, 'cwd child'));
        const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: spelling === 'legacy' ? short : dest, resource: label, policy: DEFAULT_ARCHIVE_POLICY });
        assert.equal(saved.refused, undefined, JSON.stringify(saved));
        const before = await snapshot(fx, dest, path.join(fx.root, 'isolated-state'));
        const options = protectedKind === 'script'
          ? { cli: path.join(short, 'scripts/dev/remove-worktree.cjs'), nodeArgs: ['--preserve-symlinks-main'] }
          : { cwd: protectedKind === 'cwd-root' ? short : path.join(short, 'cwd child') };
        const result = runCli(fx, dest, ['--archive-manifest', saved.manifestPath], options);
        assert.equal(result.status, 1, result.output);
        assert.match(result.output, protectedKind === 'script' ? /owns this running script/ : /current process directory/);
        assert.deepEqual(await snapshot(fx, dest, path.join(fx.root, 'isolated-state')), before);
      });
    }

    for (const point of ['helper', 'hash', 'ignored']) for (const rewrite of [false, true]) {
      const label = `root-${point}-${rewrite}`;
      const f = await aliasedTarget(label);
      if (!f) continue;
      await check(`sticky first exception refuses root replacement at ${point}, lexical rewrite=${rewrite}`, () => {
        const { fx, dest, short } = f;
        const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: short, resource: label, policy: DEFAULT_ARCHIVE_POLICY });
        assert.equal(saved.refused, undefined, JSON.stringify(saved));
        return installLateMutation(fx, { target: dest, manifestPath: saved.manifestPath, point, kind: 'root', rewrite }).then((probe) => {
          const result = runCli(fx, dest, ['--archive-manifest', saved.manifestPath]);
          assert.ok(fs.existsSync(probe), 'root replacement seam executed after first archive admission');
          const ids = JSON.parse(fs.readFileSync(probe, 'utf8'));
          assert.notEqual(ids.before, ids.after, 'the owned root was physically replaced');
          assert.equal(result.status, 1, result.output);
          assert.equal(fs.readFileSync(path.join(dest, 'draft.txt'), 'utf8'), 'retained draft\n');
          assert.equal(fs.readFileSync(path.join(`${dest}-previous`, 'draft.txt'), 'utf8'), 'retained draft\n');
          assert.ok(fs.lstatSync(path.join(dest, 'gate-link')).isSymbolicLink(), 'first mutation never unlinked native pointers');
          assert.equal(git(fx.repo, 'rev-parse', saved.stateRef).trim(), saved.stateCommit);
        });
      });
    }
    for (const kind of ['ignored', 'file']) {
      const f = await aliasedTarget(`late-${kind}`);
      if (!f) continue;
      await check(`legacy exception still refuses new ${kind} state across helper consult`, async () => {
        const { fx, dest, short } = f;
        const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: short, resource: `late-${kind}`, policy: DEFAULT_ARCHIVE_POLICY });
        assert.equal(saved.refused, undefined, JSON.stringify(saved));
        const probe = await installLateMutation(fx, { target: dest, manifestPath: saved.manifestPath, point: 'helper', kind });
        const result = runCli(fx, dest, ['--allow-ignored', '--archive-manifest', saved.manifestPath]);
        assert.ok(fs.existsSync(probe));
        assert.equal(result.status, 1, result.output);
        assert.ok(fs.existsSync(path.join(dest, '.git')));
      });
    }
  } else console.log('UNVERIFIED S17 actual Windows 8.3 manifest/protected-root/replacement cases: Linux host');

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

  await check('a verified archive manifest lifts the dirty-state blocker only for the paths it covers (952 §5.5)', async () => {
    const covered = addWorktree(primary, 'dirty archived', 'codex/936-dirty-archived');
    await fsp.writeFile(path.join(covered, 'draft.txt'), 'uncommitted draft', 'utf8');
    const archivedDirty = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: covered, resource: 'dirty-archived', policy: DEFAULT_ARCHIVE_POLICY });
    assert.equal(archivedDirty.refused, undefined, JSON.stringify(archivedDirty));
    const ok = runCli(primary, covered, ['--archive-manifest', archivedDirty.manifestPath]);
    assert.equal(ok.status, 0, ok.output);
    assert.equal(fs.existsSync(covered), false);
    assert.equal(git(primary.repo, 'cat-file', '-t', archivedDirty.stateCommit).trim(), 'commit', 'the state commit outlives the tree');

    const partial = addWorktree(primary, 'dirty partial', 'codex/936-dirty-partial');
    await fsp.writeFile(path.join(partial, 'draft.txt'), 'archived draft', 'utf8');
    const archivedPartial = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: partial, resource: 'dirty-partial', policy: DEFAULT_ARCHIVE_POLICY });
    assert.equal(archivedPartial.refused, undefined, JSON.stringify(archivedPartial));
    await fsp.writeFile(path.join(partial, 'after-archive.txt'), 'never archived', 'utf8');
    const refused = runCli(primary, partial, ['--archive-manifest', archivedPartial.manifestPath]);
    assert.equal(refused.status, 1, refused.output);
    assert.match(refused.output, /not covered by the archive manifest: after-archive\.txt/);
    assert.equal(fs.existsSync(partial), true);
  });

  await check('an archive for another worktree cannot authorize actual removal', async () => {
    const dest = addWorktree(primary, 'wrong-archive-target', 'codex/936-wrong-archive-target');
    const before = await snapshot(primary, dest, path.join(primary.root, 'isolated-state'));
    const refused = runCli(primary, dest, ['--archive-manifest', archiveManifest]);
    assert.equal(refused.status, 1, refused.output);
    assert.match(refused.output, /archive manifest .* (?:is for .*, not |target proof failed: archive manifest is for a different physical worktree: )/);
    assert.deepEqual(await snapshot(primary, dest, path.join(primary.root, 'isolated-state')), before);
  });

  // Durable boundary checks: each refusal runs the actual destructive CLI in an owned fixture.
  for (const mutation of ['new-ignored', 'recreated-deletion', 'committed-after-archive', 'forged-blob']) {
    await check(`retained archive refuses ${mutation} at actual removal`, async () => {
      const dest = addWorktree(primary, mutation, `codex/936-${mutation}`);
      if (mutation === 'recreated-deletion') fs.unlinkSync(path.join(dest, 'tracked.txt'));
      else fs.writeFileSync(path.join(dest, 'draft.txt'), 'retained draft\n');
      const saved = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: dest, resource: mutation, policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      if (mutation === 'new-ignored') {
        fs.mkdirSync(path.join(dest, 'ignored'));
        fs.writeFileSync(path.join(dest, 'ignored', 'new.txt'), 'never retained\n');
      } else if (mutation === 'recreated-deletion') {
        fs.writeFileSync(path.join(dest, 'tracked.txt'), 'recreated, never retained\n');
      } else if (mutation === 'committed-after-archive') {
        fs.writeFileSync(path.join(dest, 'new-commit.txt'), 'newly committed, never retained\n');
        git(dest, 'add', 'new-commit.txt');
        git(dest, 'commit', '-m', 'new state after archive');
      } else {
        fs.writeFileSync(path.join(dest, 'draft.txt'), 'forged mapping, unretained\n');
        saved.manifest.files.find((e) => e.path === 'draft.txt').blob = git(dest, 'hash-object', '-w', 'draft.txt').trim();
        fs.writeFileSync(saved.manifestPath, JSON.stringify(saved.manifest));
      }
      const before = await snapshot(primary, dest, path.join(primary.root, 'isolated-state'));
      const refused = runCli(primary, dest, ['--allow-ignored', '--archive-manifest', saved.manifestPath]);
      assert.equal(refused.status, 1, refused.output);
      assert.match(refused.output, /archive|retained/i);
      assert.deepEqual(await snapshot(primary, dest, path.join(primary.root, 'isolated-state')), before);
      assert.equal(git(primary.repo, 'rev-parse', saved.stateRef).trim(), saved.stateCommit);
    });
  }

  if (primary.nativeSymlinks) {
    if (process.platform === 'win32') await check('junction removal follows Git representation and preserves its external witness', async () => {
      const dest = addWorktree(primary, 'native-junction', 'codex/936-native-junction');
      const outside = path.join(primary.root, 'native-junction-target');
      fs.mkdirSync(outside);
      fs.writeFileSync(path.join(outside, 'sentinel'), 'external junction witness\n');
      fs.symlinkSync(outside, path.join(dest, 'junction'), 'junction');
      const staged = spawnSync('git', ['add', '--force', '--', 'junction'], { cwd: dest, encoding: 'utf8' });
      const representation = staged.status === 0 ? git(dest, 'ls-files', '--stage', '--', 'junction') : '';
      const nativeGitLink = /^120000 [0-9a-f]{40,64} 0\tjunction\s*$/.test(representation);
      const before = await snapshot(primary, dest, path.join(primary.root, 'isolated-state'));
      const saved = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: dest, resource: 'native-junction', policy: DEFAULT_ARCHIVE_POLICY });
      assert.deepEqual(await snapshot(primary, dest, path.join(primary.root, 'isolated-state')), before);
      const args = saved.refused ? [] : ['--archive-manifest', saved.manifestPath];
      const removed = runCli(primary, dest, args);
      assert.equal(removed.status, nativeGitLink && !saved.refused ? 0 : 1, removed.output);
      if (!nativeGitLink || saved.refused) assert.deepEqual(await snapshot(primary, dest, path.join(primary.root, 'isolated-state')), before);
      assert.equal(fs.readFileSync(path.join(outside, 'sentinel'), 'utf8'), 'external junction witness\n');
    });

    if (process.platform !== 'win32' && process.getuid() !== 0) await check('real permission-denied inspection cannot authorize actual removal', async () => {
      const dest = addWorktree(primary, 'permission-denied', 'codex/936-permission-denied');
      fs.mkdirSync(path.join(dest, 'private'));
      fs.writeFileSync(path.join(dest, 'private/draft.txt'), 'retained private draft\n');
      const saved = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: dest, resource: 'permission-denied', policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      const before = await snapshot(primary, dest, path.join(primary.root, 'isolated-state'));
      let refused;
      fs.chmodSync(path.join(dest, 'private'), 0);
      try { refused = runCli(primary, dest, ['--archive-manifest', saved.manifestPath]); }
      finally { fs.chmodSync(path.join(dest, 'private'), 0o755); }
      assert.equal(refused.status, 1, refused.output);
      assert.deepEqual(await snapshot(primary, dest, path.join(primary.root, 'isolated-state')), before);
    });

    await check('new valuable dangling links block removal while declared disposal and recorded discards remain allowed', async () => {
      const fx = await makeRepo('ignored-policy');
      fixtures.push(fx);
      const policy = { ...DEFAULT_ARCHIVE_POLICY, ignoredPerFileCapBytes: 16,
        valuableIgnored: ['ignored/*.patch'], disposableIgnored: ['ignored/disposable/**'] };
      fs.mkdirSync(path.join(fx.repo, 'governance'));
      fs.writeFileSync(path.join(fx.repo, 'governance/worktree-lifecycle.v1.json'), JSON.stringify({ archive: policy }));
      const dest = addWorktree(fx, 'ignored-policy', 'codex/936-ignored-policy');
      fs.mkdirSync(path.join(dest, 'ignored'));
      fs.writeFileSync(path.join(dest, 'ignored/discard.bin'), 'X'.repeat(32));
      const saved = archiveWorktree({ mainRepoRoot: fx.repo, worktreePath: dest, resource: 'ignored-policy', policy, discardIgnored: ['ignored/discard.bin'] });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      fs.writeFileSync(path.join(dest, 'ignored/discard.bin'), 'smaller');
      fs.mkdirSync(path.join(dest, 'ignored/disposable'));
      fs.writeFileSync(path.join(dest, 'ignored/disposable/new.bin'), 'X'.repeat(32));
      const outside = path.join(fx.root, 'outside-cache');
      fs.mkdirSync(outside);
      fs.writeFileSync(path.join(outside, 'sentinel'), 'must survive\n');
      fs.symlinkSync(outside, path.join(dest, 'node_modules'), 'dir');
      fs.symlinkSync('missing valuable destination'.repeat(2), path.join(dest, 'ignored/new.patch'));
      const refused = runCli(fx, dest, ['--allow-ignored', '--archive-manifest', saved.manifestPath]);
      assert.equal(refused.status, 1, refused.output);
      assert.match(refused.output, /ignored paths not covered.*ignored\/new.patch/);
      assert.ok(fs.lstatSync(path.join(dest, 'ignored/new.patch')).isSymbolicLink());
      fs.unlinkSync(path.join(dest, 'ignored/new.patch'));
      const removed = runCli(fx, dest, ['--allow-ignored', '--archive-manifest', saved.manifestPath]);
      assert.equal(removed.status, 0, removed.output);
      assert.equal(fs.existsSync(dest), false);
      assert.equal(fs.readFileSync(path.join(outside, 'sentinel'), 'utf8'), 'must survive\n');
    });

    for (const mutation of ['dangling-retarget', 'relative-retarget', 'absolute-retarget', 'link-to-file', 'file-to-link', 'recreated-deletion-link']) {
      await check(`actual removal refuses ${mutation} and preserves source/index/external targets`, async () => {
        const dest = addWorktree(primary, mutation, `codex/936-${mutation}`);
        git(dest, 'config', 'core.symlinks', 'true');
        const targetA = path.join(primary.root, `${mutation}-a`);
        const targetB = path.join(primary.root, `${mutation}-b`);
        const payload = mutation === 'absolute-retarget' ? targetA : mutation === 'dangling-retarget' ? 'missing-a' : `../${mutation}-a`;
        fs.writeFileSync(targetA, payload);
        fs.writeFileSync(targetB, payload);
        const entry = mutation === 'recreated-deletion-link' ? 'tracked.txt' : 'entry';
        if (mutation === 'recreated-deletion-link') fs.unlinkSync(path.join(dest, entry));
        else if (mutation === 'file-to-link') fs.writeFileSync(path.join(dest, entry), payload);
        else fs.symlinkSync(payload, path.join(dest, entry));
        const saved = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: dest, resource: mutation, policy: DEFAULT_ARCHIVE_POLICY });
        assert.equal(saved.refused, undefined, JSON.stringify(saved));
        if (mutation !== 'recreated-deletion-link') fs.unlinkSync(path.join(dest, entry));
        if (mutation === 'link-to-file') fs.writeFileSync(path.join(dest, entry), payload);
        else fs.symlinkSync(mutation === 'dangling-retarget' ? 'missing-b' : mutation === 'absolute-retarget' ? targetB
          : mutation === 'relative-retarget' ? `../${mutation}-b` : payload, path.join(dest, entry));
        const before = await snapshot(primary, dest, path.join(primary.root, 'isolated-state'));
        const refused = runCli(primary, dest, ['--allow-ignored', '--archive-manifest', saved.manifestPath]);
        assert.equal(refused.status, 1, refused.output);
        assert.match(refused.output, /does not verify/);
        assert.deepEqual(await snapshot(primary, dest, path.join(primary.root, 'isolated-state')), before);
        assert.equal(fs.readFileSync(targetA, 'utf8'), payload);
        assert.equal(fs.readFileSync(targetB, 'utf8'), payload);
      });
    }

    await check('unchanged retained link text permits external destination edits/deletion and actual removal', async () => {
      const dest = addWorktree(primary, 'link-release', 'codex/936-link-release');
      const outside = path.join(primary.root, 'release-external');
      fs.writeFileSync(outside, 'destination before archive\n');
      fs.symlinkSync(outside, path.join(dest, 'saved-link'));
      let canonicalLink;
      if (process.platform === 'win32') {
        git(dest, 'add', '--', 'saved-link');
        canonicalLink = git(dest, 'rev-parse', ':saved-link').trim();
      }
      fs.mkdirSync(path.join(dest, 'ignored'));
      fs.symlinkSync('missing destination', path.join(dest, 'ignored', 'dangling'));
      const saved = archiveWorktree({ mainRepoRoot: primary.repo, worktreePath: dest, resource: 'link-release', policy: DEFAULT_ARCHIVE_POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      fs.writeFileSync(outside, 'destination after archive\n');
      assert.equal(runCli(primary, dest, ['--dry-run', '--allow-ignored', '--archive-manifest', saved.manifestPath]).status, 0);
      assert.equal(fs.readFileSync(outside, 'utf8'), 'destination after archive\n');
      fs.unlinkSync(outside);
      const removed = runCli(primary, dest, ['--allow-ignored', '--archive-manifest', saved.manifestPath]);
      assert.equal(removed.status, 0, removed.output);
      assert.equal(fs.existsSync(dest), false);
      assert.equal(fs.existsSync(outside), false);
      if (process.platform === 'win32') {
        assert.match(git(primary.repo, 'ls-tree', saved.stateCommit, '--', 'saved-link'), /^120000 blob /);
        assert.equal(git(primary.repo, 'rev-parse', `${saved.stateCommit}:saved-link`).trim(), canonicalLink);
      } else assert.equal(git(primary.repo, 'show', `${saved.stateCommit}:saved-link`), outside);
    });
  }

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
    const createdRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'remove-wt-936-separate-git-'));
    const root = process.platform === 'win32' ? fs.realpathSync.native(createdRoot) : createdRoot;
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
      } else if (process.platform === 'linux') {
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
    try { liveChild.kill('SIGKILL'); } catch { /* already exited */ }
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
      try { server.kill('SIGKILL'); } catch { /* already exited */ }
    }
  });

  await check('membership/main guards are causal: disabling them lets only a disposable main tree be erased', async () => {
    const mutant = await makeRepo('mutant-membership');
    fixtures.push(mutant);
    await fsp.rm(path.join(mutant.repo, 'scripts'), { recursive: true, force: true });
    // The copied native descriptor is fixture scaffolding too; it must not make this
    // deliberately unguarded main checkout dirty before the destructive canary runs.
    await fsp.rm(path.join(mutant.repo, 'contracts'), { recursive: true, force: true });
    const linked = addWorktree(mutant, 'mutant owner', 'codex/mutant-owner');
    const cli = await installProductionCli(linked);
    const linkedFixture = { root: mutant.root, repo: linked, cli };
    await mutateInstalledCli(cli, (source) => source
      .replace(/  if \(samePath\(abs, facts\.mainWorktree\)\) fail\([^\n]+\);\r?\n/, '')
      .replace(/  if \(isSameDirectory\(facts\.mainWorktree\)\) fail\([^\n]+\);\r?\n/, '')
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
      try { child.kill('SIGKILL'); } catch { /* already exited */ }
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
