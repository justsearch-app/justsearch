/**
 * Tempdoc 952 P3 — real-Git regressions for `scripts/dev/lib/worktree-archive.cjs`.
 *
 * Every scenario builds its own throwaway repository (plus a linked worktree, so the common-dir
 * resolution and the "archive a worktree, not the main checkout" path are the ones under test)
 * under `os.tmpdir()` and deletes it afterwards. Nothing touches this repository.
 *
 * The load-bearing assertions, in the order the design cares about them:
 *   - the source worktree is BYTE-IDENTICAL after an archive (porcelain status incl.
 *     `--ignored=matching` before vs after) — the property that lets an archive run before the
 *     removal decision is final;
 *   - Amendment A's refusal names the oversized file, and `discardIgnored` converts the refusal
 *     into a success whose manifest still NAMES what was dropped;
 *   - `verifyArchive` reports a post-archive edit as `mismatched` (the "re-archive before you
 *     delete" signal), not as success;
 *   - the temporary index is unlinked even when the archive fails mid-way (injected failing git),
 *     because that is exactly when a stray file would be left inside a shared `.git/`.
 *
 * Run with: node scripts/agent-analytics/952-worktree-archive.test.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(HERE, '..', '..');
const require = createRequire(import.meta.url);
const {
  archiveWorktree,
  classifyIgnored,
  defaultGit,
  listArchives,
  loadPolicy,
  matchesDeclaredCache,
  matchesGlob,
  removeRestored,
  restoreArchive,
  verifyArchive,
} = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'lib', 'worktree-archive.cjs'));

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

function git(cwd, ...args) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(`git ${args.join(' ')} exited ${result.status}:\n${result.stdout || ''}${result.stderr || ''}`);
  }
  return (result.stdout || '').trim();
}

const CAP = 64;
const POLICY = {
  refPrefix: 'refs/archive',
  localDir: 'tmp/archive/worktrees',
  ignoredPerFileCapBytes: CAP,
  declaredCaches: ['node_modules/', 'build/', 'tmp/dev-runner/'],
  valuableIgnored: ['tmp/*.patch', '.mcp.json'],
  disposableIgnored: ['tmp/**/eval-results/**'],
};

const fixtures = [];

function write(root, rel, content) {
  const abs = path.join(root, ...rel.split('/'));
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, content, 'utf8');
  return abs;
}

/** A repository plus one linked worktree carrying the standard dirty state of the scenarios. */
async function makeFixture(name) {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), `952-archive-${name}-`));
  const repo = path.join(root, 'repo');
  fs.mkdirSync(repo, { recursive: true });
  git(repo, 'init', '-q', '--initial-branch=main', '.');
  git(repo, 'config', 'user.email', 'agent@justsearch.test');
  git(repo, 'config', 'user.name', '952 test');
  // Deterministic bytes: CRLF translation would make `hash-object` comparisons platform-dependent.
  git(repo, 'config', 'core.autocrlf', 'false');
  write(repo, 'a.txt', 'original tracked content\n');
  write(repo, 'keep-me.txt', 'untouched\n');
  write(repo, '.gitignore', 'tmp/\nnode_modules/\nbuild/\n.mcp.json\n');
  git(repo, 'add', 'a.txt', 'keep-me.txt', '.gitignore');
  git(repo, 'commit', '-qm', 'init');
  const worktree = path.join(root, 'wt');
  git(repo, 'worktree', 'add', '-q', '-b', `worktree-${name}`, worktree, 'HEAD');
  const fixture = { root, repo, worktree, resource: `worktree-${name}` };
  fixtures.push(fixture);
  return fixture;
}

/** The dirty state Amendment A classifies: tracked edit, untracked, valuable/plain/oversized
 *  ignored, a declared cache, and a declared-disposable eval-results tree. */
function dirty(worktree) {
  write(worktree, 'a.txt', 'original tracked content\nmodified by the session\n');
  write(worktree, 'new-note.md', 'untracked working note\n');
  write(worktree, 'tmp/keep.patch', 'valuable ignored patch\n');
  write(worktree, 'tmp/small.txt', 'small ignored\n');
  write(worktree, 'tmp/huge.bin', 'X'.repeat(CAP * 4));
  write(worktree, 'tmp/run/eval-results/r.json', '{"disposable":true}\n');
  write(worktree, 'node_modules/pkg/index.js', 'module.exports = 1;\n');
  // Nested declared caches: a real worktree holds modules/ui-web/node_modules and modules/*/build.
  write(worktree, 'modules/ui-web/node_modules/pkg/index.js', 'module.exports = 2;\n');
  write(worktree, 'modules/core/build/out.class', 'compiled\n');
}

const statusOf = (worktree) => git(worktree, 'status', '--porcelain', '--ignored=matching');

/**
 * Porcelain status proves WHICH paths exist in which state; it says nothing about their bytes.
 * "Byte-identical before and after" is the property §5.5 actually claims, so hash the whole tree
 * (path + content, `.git` excluded) as well.
 */
function digestTree(root) {
  const hash = crypto.createHash('sha256');
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
      if (entry.name === '.git') continue;
      const abs = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(abs);
      else hash.update(path.relative(root, abs).replace(/\\/g, '/')).update('\0')
        .update(entry.isSymbolicLink() ? fs.readlinkSync(abs, { encoding: 'buffer' }) : fs.readFileSync(abs)).update('\0');
    }
  };
  walk(root);
  return hash.digest('hex');
}

const treeOf = (repo, commit) => git(repo, 'ls-tree', '-r', '--name-only', commit).split('\n').filter(Boolean);
const archiveRefs = (repo, resource) => git(repo, 'for-each-ref', '--format=%(refname)', `refs/archive/${resource}/`).split('\n').filter(Boolean);
const strayIndexFiles = (repo) => fs.readdirSync(path.join(repo, '.git')).filter((n) => n.startsWith('952-archive-'));

try {
  /* ── 1. classification, refusal, discard, exclusion, byte-identical source ──────────────── */

  await check('oversized ignored file refuses the archive and names the path', async () => {
    const f = await makeFixture('refuse');
    dirty(f.worktree);

    const classified = classifyIgnored({ worktreePath: f.worktree, policy: POLICY });
    assert.deepEqual(classified.oversized.map((e) => e.path), ['tmp/huge.bin']);
    assert.deepEqual(classified.disposable.map((e) => e.path), ['tmp/run/eval-results/r.json']);
    assert.deepEqual(classified.caches.map((e) => e.path).sort(), [
      'modules/core/build/out.class', 'modules/ui-web/node_modules/pkg/index.js', 'node_modules/pkg/index.js',
    ]);
    assert.deepEqual(classified.archive.map((e) => e.path).sort(), ['tmp/keep.patch', 'tmp/small.txt']);

    const refusal = archiveWorktree({
      mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY,
    });
    assert.equal(refusal.refused, true);
    assert.equal(refusal.reason, 'oversized-ignored');
    assert.deepEqual(refusal.oversized.map((e) => e.path), ['tmp/huge.bin']);
    assert.equal(refusal.oversized[0].size, CAP * 4);
    assert.equal(fs.existsSync(path.join(f.repo, 'tmp', 'archive')), false, 'a refusal writes no manifest');

    // `discardIgnored` accepts a glob as well as an exact path — the CLI passes whatever the
    // operator typed.
    const byGlob = archiveWorktree({
      mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY,
      discardIgnored: ['tmp/*.bin'],
    });
    assert.equal(byGlob.refused, undefined, JSON.stringify(byGlob));
    assert.deepEqual(byGlob.manifest.skipped.filter((e) => e.reason === 'oversized-discarded').map((e) => e.path), ['tmp/huge.bin']);
  });

  await check('discardIgnored archives everything else, names the discard, excludes caches, leaves the tree untouched', async () => {
    const f = await makeFixture('archive');
    dirty(f.worktree);
    const before = statusOf(f.worktree);
    const beforeDigest = digestTree(f.worktree);

    const result = archiveWorktree({
      mainRepoRoot: f.repo,
      worktreePath: f.worktree,
      resource: f.resource,
      policy: POLICY,
      discardIgnored: ['tmp/huge.bin'],
      now: Date.parse('2026-09-10T12:00:00.000Z'),
    });
    assert.equal(result.refused, undefined, JSON.stringify(result));
    f.archive = result;

    // Tip ref points at the committed head.
    assert.equal(result.tipRef, `refs/archive/${f.resource}/${git(f.worktree, 'rev-parse', '--short', 'HEAD')}`);
    assert.equal(git(f.repo, 'rev-parse', result.tipRef), result.head);
    assert.equal(git(f.repo, 'rev-parse', result.stateRef), result.stateCommit);
    assert.equal(git(f.repo, 'rev-parse', `${result.stateCommit}^`), result.head, 'state commit is parented on HEAD');

    // What the state commit does and does not contain.
    const tree = treeOf(f.repo, result.stateCommit);
    assert.ok(tree.includes('new-note.md'), 'untracked file archived');
    assert.ok(tree.includes('tmp/keep.patch'), 'valuable ignored file archived');
    assert.ok(tree.includes('tmp/small.txt'), 'small ignored file archived');
    assert.ok(!tree.some((p) => p.startsWith('node_modules/')), 'declared cache excluded');
    assert.ok(!tree.some((p) => p.includes('/node_modules/')), 'nested declared cache excluded');
    assert.ok(!tree.some((p) => p.includes('/build/')), 'nested build output excluded');
    assert.ok(!tree.includes('tmp/huge.bin'), 'discarded oversized file excluded');
    assert.ok(!tree.some((p) => p.includes('eval-results/')), 'declared-disposable path excluded');
    assert.equal(
      git(f.repo, 'show', `${result.stateCommit}:a.txt`),
      'original tracked content\nmodified by the session',
      'tracked modification captured',
    );

    // Manifest: classes, sizes, skips.
    const manifest = JSON.parse(fs.readFileSync(result.manifestPath, 'utf8'));
    assert.equal(result.manifestPath, path.join(f.repo, 'tmp', 'archive', 'worktrees', f.resource, '20260910T120000Z.json'));
    assert.equal(manifest.createdAt, '2026-09-10T12:00:00.000Z');
    const classOf = Object.fromEntries(manifest.files.map((e) => [e.path, e.class]));
    assert.equal(classOf['a.txt'], 'tracked-modified');
    assert.equal(classOf['new-note.md'], 'untracked');
    assert.equal(classOf['tmp/keep.patch'], 'ignored-valuable');
    assert.equal(classOf['tmp/small.txt'], 'ignored');
    assert.ok(manifest.files.every((e) => /^[0-9a-f]{40,64}$/.test(e.blob)), 'every archived file carries a blob id');
    assert.equal(manifest.files.find((e) => e.path === 'tmp/keep.patch').size, 'valuable ignored patch\n'.length);
    const skipped = Object.fromEntries(manifest.skipped.map((e) => [e.path, e.reason]));
    assert.equal(skipped['tmp/huge.bin'], 'oversized-discarded');
    assert.equal(skipped['tmp/run/eval-results/r.json'], 'declared-disposable');
    assert.equal(manifest.caches.files, 3, 'caches summarized, not listed per file');

    // The source worktree is byte-identical, and no temp index survives.
    assert.equal(statusOf(f.worktree), before, 'archiving does not modify the source worktree');
    assert.equal(digestTree(f.worktree), beforeDigest, 'archiving leaves every working file byte-identical');
    assert.deepEqual(strayIndexFiles(f.repo), [], 'temporary index removed');
    assert.equal(fs.readFileSync(path.join(f.worktree, 'tmp', 'huge.bin'), 'utf8').length, CAP * 4,
      'a discarded file is only excluded from the archive, never deleted from the worktree');
  });

  /* ── 2. verification ───────────────────────────────────────────────────────────────────── */

  await check('verifyArchive is ok after archiving and reports a post-archive edit as mismatched', async () => {
    const f = await makeFixture('verify');
    dirty(f.worktree);
    const result = archiveWorktree({
      mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY,
      discardIgnored: ['tmp/huge.bin'],
    });
    assert.equal(result.refused, undefined, JSON.stringify(result));

    const clean = verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest });
    assert.deepEqual(clean, { ok: true, missing: [], mismatched: [], errors: [] });

    write(f.worktree, 'new-note.md', 'untracked working note\nchanged after the archive\n');
    const stale = verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest });
    assert.equal(stale.ok, false);
    assert.deepEqual(stale.mismatched, ['new-note.md']);
    assert.deepEqual(stale.missing, []);

    // A file deleted after archiving is NOT a mismatch — the archive still holds it.
    fs.rmSync(path.join(f.worktree, 'tmp', 'small.txt'));
    write(f.worktree, 'new-note.md', 'untracked working note\n');
    const afterDelete = verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest });
    assert.deepEqual(afterDelete, { ok: true, missing: [], mismatched: [], errors: [] });

    // A manifest naming a ref that no longer resolves is not ok either.
    git(f.repo, 'update-ref', '-d', result.stateRef);
    const dropped = verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest });
    assert.equal(dropped.ok, false);
    assert.equal(dropped.errors.length, 1, JSON.stringify(dropped.errors));
  });

  await check('an ignored file named exactly like a declared cache is classified as a cache and not archived', async () => {
    const f = await makeFixture('cache-leaf');
    write(f.worktree, 'tmp/build', 'a file, not a directory\n');
    write(f.worktree, 'tmp/sub/node_modules', 'also a file\n');
    const classified = classifyIgnored({ worktreePath: f.worktree, policy: POLICY });
    assert.deepEqual(classified.caches.map((e) => e.path).sort(), ['tmp/build', 'tmp/sub/node_modules']);
    const result = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(result.refused, undefined, JSON.stringify(result));
    const tree = git(f.repo, 'ls-tree', '-r', '--name-only', result.stateCommit).split('\n');
    assert.ok(!tree.includes('tmp/build'), `cache leaf archived: ${tree.join(', ')}`);
    assert.ok(!tree.includes('tmp/sub/node_modules'), `cache leaf archived: ${tree.join(', ')}`);
    assert.equal(result.manifest.caches.files, 2);
  });

  await check('tracked and unignored files on cache-named paths keep their state; ignored caches stay out', async () => {
    const f = await makeFixture('cache-tracked');
    write(f.worktree, 'tools/build', 'v1\n');
    write(f.worktree, 'docs/build/page.md', 'v1\n');
    write(f.worktree, 'docs/build/gone.md', 'v1\n');
    git(f.worktree, 'add', '-f', 'tools/build', 'docs/build/page.md', 'docs/build/gone.md');
    git(f.worktree, 'commit', '-qm', 'tracked files on cache-named paths');
    write(f.worktree, 'tools/build', 'v2\n');
    write(f.worktree, 'docs/build/page.md', 'v2\n');
    fs.rmSync(path.join(f.worktree, 'docs', 'build', 'gone.md'));
    write(f.worktree, 'src/build', 'unignored new file\n');
    write(f.worktree, 'docs/build/staged.md', 'force-added, not committed\n');
    git(f.worktree, 'add', '-f', 'docs/build/staged.md');
    write(f.worktree, 'modules/x/build/out.class', 'ignored cache\n');
    const result = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(result.refused, undefined, JSON.stringify(result));
    const show = (p) => git(f.repo, 'show', `${result.stateCommit}:${p}`);
    assert.equal(show('tools/build'), 'v2', 'tracked edit of a cache-named file');
    assert.equal(show('docs/build/page.md'), 'v2', 'tracked edit under a cache-named directory');
    const tree = git(f.repo, 'ls-tree', '-r', '--name-only', result.stateCommit).split('\n');
    assert.ok(!tree.includes('docs/build/gone.md'), 'tracked deletion under a cache-named directory');
    assert.ok(tree.includes('src/build'), 'unignored new file with a cache name');
    assert.equal(show('docs/build/staged.md'), 'force-added, not committed', 'staged addition under a cache-named directory');
    assert.ok(!tree.includes('modules/x/build/out.class'), 'ignored cache excluded');
    assert.deepEqual(result.manifest.files.map((e) => e.path).sort(),
      ['docs/build/gone.md', 'docs/build/page.md', 'docs/build/staged.md', 'src/build', 'tools/build']);
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest }),
      { ok: true, missing: [], mismatched: [], errors: [] });
  });

  await check('verifyArchive spends a fixed number of git processes, not two per archived file', async () => {
    const f = await makeFixture('verify-batch');
    for (let i = 0; i < 40; i += 1) write(f.worktree, `notes/n${i}.md`, `note ${i}\n`);
    const result = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(result.refused, undefined, JSON.stringify(result));
    assert.ok(result.manifest.files.length >= 40, `manifest lists ${result.manifest.files.length} files`);

    let calls = 0;
    const counting = (args, options) => { calls += 1; return defaultGit(args, options); };
    const clean = verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest, git: counting });
    assert.deepEqual(clean, { ok: true, missing: [], mismatched: [], errors: [] });
    assert.ok(calls <= 3, `verifyArchive ran ${calls} git processes for ${result.manifest.files.length} files`);

    // A batch failure still names the file: per-file hashing takes over.
    write(f.worktree, 'notes/n7.md', 'note 7, edited after the archive\n');
    const failingBatch = (args, options) => {
      if (args[0] === 'hash-object' && args[1] === '--stdin-paths') throw new Error('simulated unreadable file');
      return defaultGit(args, options);
    };
    const fallback = verifyArchive({ mainRepoRoot: f.repo, manifest: result.manifest, git: failingBatch });
    assert.deepEqual(fallback.mismatched, ['notes/n7.md']);
    assert.deepEqual(fallback.errors, []);
  });

  // Durable link checks inspect the saved Git representation and restore, never target contents.
  const linkProbe = await makeFixture('native-link-probe');
  let nativeSymlinks = true;
  try { fs.symlinkSync('missing', path.join(linkProbe.worktree, 'probe')); }
  catch (err) {
    if (process.platform !== 'win32' || !['EPERM', 'EACCES', 'ENOSYS'].includes(err.code)) throw err;
    nativeSymlinks = false;
    console.log(`UNVERIFIED native Windows symlinks: ${err.code}`);
  }
  if (nativeSymlinks) {
    await check('unchanged dangling links and genuinely removed source links remain verifiable and restorable', async () => {
      const f = await makeFixture('removed-link');
      git(f.repo, 'config', 'core.symlinks', 'true');
      const payload = 'missing destination \t\n';
      fs.symlinkSync(payload, path.join(f.worktree, 'saved-link'));
      const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
      fs.unlinkSync(path.join(f.worktree, 'saved-link'));
      assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
      const destination = path.join(f.root, 'restore-removed-link');
      assert.equal(restoreArchive({ mainRepoRoot: f.repo, stateCommit: saved.stateCommit, destination }).refused, undefined);
      assert.equal(fs.readlinkSync(path.join(destination, 'saved-link')), payload);
      assert.equal(removeRestored({ mainRepoRoot: f.repo, path: destination }).removed, true);
    });

    await check('tracked, untracked, ignored, directory and dangling links retain exact Git payloads and restore', async () => {
      const f = await makeFixture('links');
      git(f.repo, 'config', 'core.symlinks', 'true');
      const external = write(f.root, 'outside/file', 'external target contents\n');
      fs.symlinkSync(external, path.join(f.worktree, 'tracked-link'));
      fs.symlinkSync(external, path.join(f.worktree, 'unchanged-link'));
      git(f.worktree, 'add', 'tracked-link', 'unchanged-link');
      git(f.worktree, 'commit', '-qm', 'committed links');
      fs.unlinkSync(path.join(f.worktree, 'tracked-link'));
      const payloads = new Map([
        ['tracked-link', '../outside/file'],
        ['directory-link', path.dirname(external)],
        ['missing link ü', 'missing destination \t\n'],
        ['tmp/keep.patch', external],
        ['tmp/dangling', 'missing ignored destination'],
      ]);
      if (process.platform !== 'win32') {
        payloads.set('line\nbreak', 'nonexistent\ntrailing \t');
        payloads.set('raw-link', Buffer.from([0x6d, 0x69, 0x73, 0x73, 0xff, 0x20, 0x09]));
      }
      for (const [name, target] of payloads) {
        fs.mkdirSync(path.dirname(path.join(f.worktree, name)), { recursive: true });
        fs.symlinkSync(target, path.join(f.worktree, name), name === 'directory-link' ? 'dir' : 'file');
      }
      const indexPath = git(f.worktree, 'rev-parse', '--path-format=absolute', '--git-path', 'index');
      const beforeIndex = fs.readFileSync(indexPath);
      const before = digestTree(f.worktree);
      const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
      assert.equal(saved.refused, undefined, JSON.stringify(saved));
      assert.equal(digestTree(f.worktree), before);
      assert.deepEqual(fs.readFileSync(indexPath), beforeIndex);
      assert.ok(!saved.manifest.files.some((e) => e.path === 'unchanged-link'), 'unchanged committed link needs no changed-file entry');
      for (const [name, target] of payloads) {
        const entry = saved.manifest.files.find((e) => e.path === name);
        assert.ok(entry, `manifest retains ${name}`);
        assert.equal(entry.size, Buffer.byteLength(target), `size is link text for ${name}`);
        assert.match(git(f.repo, 'ls-tree', saved.stateCommit, '--', name), /^120000 blob /);
        const stored = spawnSync('git', ['cat-file', 'blob', entry.blob], { cwd: f.repo });
        assert.equal(stored.status, 0);
        assert.deepEqual(stored.stdout, Buffer.from(target));
      }
      assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
      fs.writeFileSync(external, 'destination changed independently\n');
      fs.unlinkSync(external);
      assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
      const destination = path.join(f.root, 'restored');
      assert.equal(restoreArchive({ mainRepoRoot: f.repo, stateCommit: saved.stateCommit, destination }).refused, undefined);
      for (const [name, target] of payloads) {
        assert.ok(fs.lstatSync(path.join(destination, name)).isSymbolicLink());
        assert.deepEqual(fs.readlinkSync(path.join(destination, name), { encoding: 'buffer' }), Buffer.from(target));
      }
      assert.equal(removeRestored({ mainRepoRoot: f.repo, path: destination }).removed, true);
      assert.equal(digestTree(f.worktree), before);
      assert.deepEqual(fs.readFileSync(indexPath), beforeIndex);
      assert.equal(fs.existsSync(external), false, 'restore/removal never recreates an external target');
    });

    await check('ignored symlink caps and cache/disposable/valuable policy use the link entry including dangling links', async () => {
      const f = await makeFixture('link-policy');
      write(f.worktree, '.gitignore', 'tmp/\nnode_modules\n');
      const target = write(f.root, 'large-target', 'X'.repeat(CAP * 10));
      const links = new Map([
        ['tmp/small', '../large-target'], ['tmp/dangling', 'missing'],
        ['tmp/over', 'x'.repeat(CAP + 1)], ['tmp/valuable.patch', 'x'.repeat(CAP + 2)],
        ['tmp/run/eval-results/link', 'missing'], ['node_modules', target],
      ]);
      for (const [name, payload] of links) {
        fs.mkdirSync(path.dirname(path.join(f.worktree, name)), { recursive: true });
        fs.symlinkSync(payload, path.join(f.worktree, name));
      }
      const classified = classifyIgnored({ worktreePath: f.worktree, policy: POLICY });
      assert.deepEqual(classified.oversized.map((e) => e.path), ['tmp/over']);
      assert.deepEqual(classified.caches.map((e) => e.path), ['node_modules']);
      assert.deepEqual(classified.disposable.map((e) => e.path), ['tmp/run/eval-results/link']);
      assert.deepEqual(classified.archive.map((e) => e.path).sort(), ['tmp/dangling', 'tmp/small', 'tmp/valuable.patch']);
      for (const entry of Object.values(classified).flat()) assert.equal(entry.size, Buffer.byteLength(links.get(entry.path)));
      assert.equal(archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY }).reason, 'oversized-ignored');
    });

    for (const mutation of ['dangling-retarget', 'relative-retarget', 'absolute-retarget', 'link-to-file', 'file-to-link']) {
      await check(`verifyArchive refuses ${mutation} even when dereferenced or blob bytes are equal`, async () => {
        const f = await makeFixture(mutation);
        git(f.repo, 'config', 'core.symlinks', 'true');
        const absA = path.join(f.root, 'target-a');
        const absB = path.join(f.root, 'target-b');
        const name = 'entry';
        const original = mutation === 'absolute-retarget' ? absA : mutation === 'dangling-retarget' ? 'missing-a' : '../target-a';
        fs.writeFileSync(absA, original);
        fs.writeFileSync(absB, original);
        if (mutation === 'file-to-link') write(f.worktree, name, original);
        else fs.symlinkSync(original, path.join(f.worktree, name));
        const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
        assert.equal(saved.refused, undefined, JSON.stringify(saved));
        fs.unlinkSync(path.join(f.worktree, name));
        if (mutation === 'link-to-file') write(f.worktree, name, original);
        else fs.symlinkSync(mutation === 'absolute-retarget' ? absB : mutation === 'relative-retarget' ? '../target-b'
          : mutation === 'dangling-retarget' ? 'missing-b' : original, path.join(f.worktree, name));
        const result = verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest });
        assert.equal(result.ok, false, JSON.stringify(result));
        assert.deepEqual(result.mismatched, [name]);
        assert.equal(fs.readFileSync(absA, 'utf8'), original);
        assert.equal(fs.readFileSync(absB, 'utf8'), original);
      });
    }
  }

  await check('core.symlinks=false preserves canonical 120000 materialized Git files with Git filters', async () => {
    const f = await makeFixture('materialized');
    git(f.repo, 'config', 'core.symlinks', 'false');
    write(f.worktree, 'materialized.link', 'old target');
    const blob = git(f.worktree, 'hash-object', '-w', '--no-filters', 'materialized.link');
    git(f.worktree, 'update-index', '--add', '--cacheinfo', `120000,${blob},materialized.link`);
    git(f.worktree, 'commit', '-qm', 'materialized Git symlink');
    const payload = 'missing destination \t\r\n';
    write(f.worktree, 'materialized.link', payload);
    write(f.worktree, '.gitattributes', '*.link text eol=lf\n');
    const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    assert.match(git(f.repo, 'ls-tree', saved.stateCommit, '--', 'materialized.link'), /^120000 blob /);
    assert.equal(defaultGit(['show', `${saved.stateCommit}:materialized.link`], { cwd: f.repo }), payload.replace('\r\n', '\n'));
    assert.equal(fs.readFileSync(path.join(f.worktree, 'materialized.link'), 'utf8'), payload, 'archive leaves source bytes intact');
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
    const destination = path.join(f.root, 'restore-materialized');
    assert.equal(restoreArchive({ mainRepoRoot: f.repo, stateCommit: saved.stateCommit, destination }).refused, undefined);
    assert.equal(fs.lstatSync(path.join(destination, 'materialized.link')).isSymbolicLink(), false);
    assert.equal(fs.readFileSync(path.join(destination, 'materialized.link'), 'utf8'), payload.replace('\r\n', '\n'));
    assert.equal(removeRestored({ mainRepoRoot: f.repo, path: destination }).removed, true);
  });

  await check('ordinary filtered CRLF files, unusual names and named hash-error fallback remain supported', async () => {
    const f = await makeFixture('filtered');
    write(f.worktree, '.gitattributes', '*.txt text eol=lf\n');
    write(f.worktree, 'spaces ü.txt', 'line one\r\nline two\r\n');
    if (process.platform !== 'win32') write(f.worktree, 'line\nbreak.txt', 'newline name\r\n');
    const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
    assert.equal(defaultGit(['show', `${saved.stateCommit}:spaces ü.txt`], { cwd: f.repo }), 'line one\nline two\n');
    const failingGit = (args, options) => {
      if (args[0] === 'hash-object' && (args[1] === '--stdin-paths' || args.at(-1) === 'spaces ü.txt')) throw new Error('fixture read failure');
      return defaultGit(args, options);
    };
    const failed = verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest, git: failingGit });
    assert.equal(failed.ok, false);
    assert.equal(failed.errors.length, 1, JSON.stringify(failed));
    assert.match(failed.errors[0], /hash-object failed for spaces ü\.txt: fixture read failure/);
  });

  await check('real inspection failures refuse while genuinely missing entries remain valid', async () => {
    const f = await makeFixture('inspection');
    write(f.worktree, 'nested/draft.txt', 'retained draft\n');
    const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    fs.renameSync(path.join(f.worktree, 'nested'), path.join(f.root, 'held-nested'));
    write(f.worktree, 'nested', 'directory replaced by a file');
    const blocked = verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest });
    assert.equal(blocked.ok, false, JSON.stringify(blocked));
    assert.match(blocked.errors.join('\n'), /Cannot inspect nested\/draft.txt/);
    fs.unlinkSync(path.join(f.worktree, 'nested'));
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
    git(f.repo, 'worktree', 'remove', '--force', f.worktree);
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] }, 'object-only verification survives removal');
  });

  await check('genuinely removed regular entries and object-only retained archives remain verifiable and restorable', async () => {
    const f = await makeFixture('removed-regular');
    write(f.worktree, 'draft.txt', 'retained, then removed\n');
    const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    fs.unlinkSync(path.join(f.worktree, 'draft.txt'));
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
    git(f.repo, 'worktree', 'remove', '--force', f.worktree);
    assert.deepEqual(verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest }), { ok: true, missing: [], mismatched: [], errors: [] });
    const destination = path.join(f.root, 'restore-removed-file');
    assert.equal(restoreArchive({ mainRepoRoot: f.repo, stateCommit: saved.stateCommit, destination }).refused, undefined);
    assert.equal(fs.readFileSync(path.join(destination, 'draft.txt'), 'utf8'), 'retained, then removed\n');
    assert.equal(removeRestored({ mainRepoRoot: f.repo, path: destination }).removed, true);
  });

  if (process.platform !== 'win32' && process.getuid() !== 0) await check('EACCES is an inspection error, never evidence that an archived entry disappeared', async () => {
    const f = await makeFixture('eacces');
    write(f.worktree, 'private/draft.txt', 'retained private draft\n');
    const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    fs.chmodSync(path.join(f.worktree, 'private'), 0);
    try {
      assert.throws(() => fs.lstatSync(path.join(f.worktree, 'private/draft.txt')), { code: 'EACCES' });
      const blocked = verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest });
      assert.equal(blocked.ok, false, JSON.stringify(blocked));
      assert.match(blocked.errors.join('\n'), /Cannot inspect private\/draft.txt:.*EACCES/);
    } finally { fs.chmodSync(path.join(f.worktree, 'private'), 0o755); }
  });

  await check('a missing retained blob refuses even when the worktree still has its bytes', async () => {
    const f = await makeFixture('missing-blob');
    write(f.worktree, 'draft.txt', 'unique retained blob for removal\n');
    const saved = archiveWorktree({ mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY });
    assert.equal(saved.refused, undefined, JSON.stringify(saved));
    const blob = saved.manifest.files.find((e) => e.path === 'draft.txt').blob;
    fs.unlinkSync(path.join(f.repo, '.git', 'objects', blob.slice(0, 2), blob.slice(2)));
    const blocked = verifyArchive({ mainRepoRoot: f.repo, manifest: saved.manifest });
    assert.equal(blocked.ok, false, JSON.stringify(blocked));
    assert.deepEqual(blocked.missing, ['draft.txt']);
  });

  /* ── 3. restore ────────────────────────────────────────────────────────────────────────── */

  await check('restoreArchive materializes the archived state, including the ignored-valuable file', async () => {
    const f = await makeFixture('restore');
    dirty(f.worktree);
    const result = archiveWorktree({
      mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY,
      discardIgnored: ['tmp/huge.bin'],
    });
    assert.equal(result.refused, undefined, JSON.stringify(result));

    const destination = path.join(f.root, 'restored');
    const restored = restoreArchive({ mainRepoRoot: f.repo, stateCommit: result.stateCommit, destination });
    assert.equal(restored.refused, undefined, JSON.stringify(restored));
    assert.equal(restored.path, destination);
    assert.equal(fs.readFileSync(path.join(destination, 'tmp', 'keep.patch'), 'utf8'), 'valuable ignored patch\n');
    assert.equal(fs.readFileSync(path.join(destination, 'new-note.md'), 'utf8'), 'untracked working note\n');
    assert.equal(fs.readFileSync(path.join(destination, 'a.txt'), 'utf8'), 'original tracked content\nmodified by the session\n');
    assert.equal(fs.existsSync(path.join(destination, 'node_modules')), false);
    assert.equal(git(destination, 'rev-parse', '--abbrev-ref', 'HEAD'), 'HEAD', 'restored worktree is detached');

    const removal = removeRestored({ mainRepoRoot: f.repo, path: destination });
    assert.equal(removal.removed, true, JSON.stringify(removal));
    assert.equal(fs.existsSync(destination), false);
    assert.ok(!git(f.repo, 'worktree', 'list').includes('restored'), 'restored worktree deregistered');
  });

  /* ── 4. tip-ref conditional create + inventory ─────────────────────────────────────────── */

  await check('tip ref is idempotent for an unchanged head and a moved head adds a second ref', async () => {
    const f = await makeFixture('tipref');
    dirty(f.worktree);
    const opts = {
      mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY,
      discardIgnored: ['tmp/huge.bin'],
    };
    const first = archiveWorktree({ ...opts, now: Date.parse('2026-09-10T12:00:00.000Z') });
    assert.equal(first.refused, undefined, JSON.stringify(first));
    assert.equal(first.tipRefCreated, true);

    const second = archiveWorktree({ ...opts, now: Date.parse('2026-09-10T12:05:00.000Z') });
    assert.equal(second.refused, undefined, JSON.stringify(second));
    assert.equal(second.tipRefCreated, false, 'the same head re-uses its tip ref instead of failing');
    assert.equal(second.tipRef, first.tipRef);
    assert.notEqual(second.stateRef, first.stateRef, 'each archive keeps its own state ref');

    git(f.worktree, 'commit', '-qam', 'session commit');
    const third = archiveWorktree({ ...opts, now: Date.parse('2026-09-10T12:10:00.000Z') });
    assert.equal(third.refused, undefined, JSON.stringify(third));
    assert.equal(third.tipRefCreated, true);
    assert.notEqual(third.tipRef, first.tipRef);
    const tips = archiveRefs(f.repo, f.resource).filter((r) => !r.includes('/state-'));
    assert.equal(tips.length, 2, `expected two tip refs, got ${JSON.stringify(tips)}`);

    const archives = listArchives({ mainRepoRoot: f.repo, policy: POLICY });
    assert.equal(archives.length, 3);
    assert.deepEqual(archives.map((a) => a.createdAt), [
      '2026-09-10T12:10:00.000Z', '2026-09-10T12:05:00.000Z', '2026-09-10T12:00:00.000Z',
    ], 'newest first');
    assert.ok(archives.every((a) => a.resource === f.resource && a.bytes > 0));
  });

  /* ── 5. glob matcher ───────────────────────────────────────────────────────────────────── */

  await check('a timestamp collision preserves the first manifest and retained state', async () => {
    const f = await makeFixture('collision');
    write(f.worktree, 'draft.txt', 'first retained version\n');
    const opts = { mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY, now: Date.parse('2026-09-10T12:00:00Z') };
    const first = archiveWorktree(opts);
    assert.equal(first.refused, undefined, JSON.stringify(first));
    const manifestBytes = fs.readFileSync(first.manifestPath);
    write(f.worktree, 'draft.txt', 'second version must not replace evidence\n');
    const before = digestTree(f.worktree);
    const second = archiveWorktree(opts);
    assert.equal(second.refused, true, JSON.stringify(second));
    assert.deepEqual(fs.readFileSync(first.manifestPath), manifestBytes);
    assert.equal(git(f.repo, 'rev-parse', first.stateRef), first.stateCommit);
    assert.equal(git(f.repo, 'show', `${first.stateRef}:draft.txt`), 'first retained version');
    assert.equal(digestTree(f.worktree), before);

    // A leftover manifest without its ref is still evidence, never a writable slot.
    git(f.repo, 'update-ref', '-d', first.stateRef);
    const manifestOnly = archiveWorktree(opts);
    assert.equal(manifestOnly.refused, true, JSON.stringify(manifestOnly));
    assert.deepEqual(fs.readFileSync(first.manifestPath), manifestBytes);
  });

  await check('an existing state ref cannot be overwritten when its manifest is missing', async () => {
    const f = await makeFixture('state-ref-collision');
    write(f.worktree, 'draft.txt', 'first retained version\n');
    const opts = { mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY, now: Date.parse('2026-09-10T12:00:00Z') };
    const first = archiveWorktree(opts);
    assert.equal(first.refused, undefined, JSON.stringify(first));
    fs.unlinkSync(first.manifestPath);
    write(f.worktree, 'draft.txt', 'later version\n');
    const second = archiveWorktree(opts);
    assert.equal(second.refused, true, JSON.stringify(second));
    assert.equal(git(f.repo, 'rev-parse', first.stateRef), first.stateCommit);
    assert.equal(git(f.repo, 'show', `${first.stateRef}:draft.txt`), 'first retained version');
    assert.equal(fs.existsSync(first.manifestPath), false, 'refusal creates no replacement manifest');
  });

  await check('glob matcher: ** spans segments, * stays inside one, no wildcard is exact', async () => {
    // `**` in the middle may match zero segments — `tmp/**/eval-results/**` covers both shapes the
    // repository's eval output actually takes.
    assert.equal(matchesGlob('tmp/**/eval-results/**', 'tmp/run/eval-results/r.json'), true);
    assert.equal(matchesGlob('tmp/**/eval-results/**', 'tmp/a/b/c/eval-results/deep/r.json'), true);
    assert.equal(matchesGlob('tmp/**/eval-results/**', 'tmp/eval-results/r.json'), true);
    // The directory itself matches its own `/**` — excluding it is the same intent as excluding
    // its contents.
    assert.equal(matchesGlob('tmp/**/eval-results/**', 'tmp/eval-results'), true);
    assert.equal(matchesGlob('tmp/**/eval-results/**', 'tmp/eval-results-notes.md'), false);
    assert.equal(matchesGlob('tmp/**/eval-results/**', 'other/eval-results/r.json'), false);

    assert.equal(matchesGlob('tmp/*.patch', 'tmp/a.patch'), true);
    assert.equal(matchesGlob('tmp/*.patch', 'tmp/nested/a.patch'), false, '* does not cross a separator');
    assert.equal(matchesGlob('tmp/*.patch', 'tmpa.patch'), false);
    assert.equal(matchesGlob('tmp/*.patch', 'tmp/a.patch.bak'), false);
    assert.equal(matchesGlob('tmp/*.patch', 'tmp\\a.patch'), true, 'Windows separators are normalized');

    assert.equal(matchesGlob('.mcp.json', '.mcp.json'), true);
    assert.equal(matchesGlob('.mcp.json', 'sub/.mcp.json'), false);
    assert.equal(matchesGlob('.mcp.json', 'Xmcp.json'), false, 'the dot is literal, not a wildcard');
    assert.equal(matchesGlob('scripts/jseval/tmp/**', 'scripts/jseval/tmp/a/b.json'), true);

    assert.equal(matchesDeclaredCache(['node_modules/'], 'node_modules/pkg/x.js'), true);
    assert.equal(matchesDeclaredCache(['node_modules/'], 'modules/ui-web/node_modules/pkg/x.js'), true);
    assert.equal(matchesDeclaredCache(['node_modules/'], 'docs/my-node_modules-notes.md'), false);
    assert.equal(matchesDeclaredCache(['tmp/dev-runner/'], 'tmp/dev-runner/foreign/a.json'), true);
    assert.equal(matchesDeclaredCache(['tmp/dev-runner/'], 'tmp/dev-runner-notes.md'), false);
  });

  /* ── 6. failure cleanup + policy loading ───────────────────────────────────────────────── */

  await check('the temporary index is unlinked even when commit-tree fails', async () => {
    const f = await makeFixture('failure');
    dirty(f.worktree);
    const before = statusOf(f.worktree);
    const beforeDigest = digestTree(f.worktree);
    let indexPath = null;
    let indexExistedDuringBuild = false;
    const failingGit = (args, options) => {
      if (options?.env?.GIT_INDEX_FILE) indexPath = options.env.GIT_INDEX_FILE;
      if (args[0] === 'write-tree') indexExistedDuringBuild = fs.existsSync(indexPath);
      if (args[0] === 'commit-tree') throw new Error('injected commit-tree failure');
      return defaultGit(args, options);
    };

    const result = archiveWorktree({
      mainRepoRoot: f.repo, worktreePath: f.worktree, resource: f.resource, policy: POLICY,
      discardIgnored: ['tmp/huge.bin'], git: failingGit,
    });
    assert.equal(result.refused, true);
    assert.equal(result.reason, 'git-failed');
    assert.equal(result.step, 'state-commit');
    assert.match(result.message, /injected commit-tree failure/);
    assert.ok(indexExistedDuringBuild, 'the temp index really was created (otherwise the cleanup assertion is vacuous)');
    assert.equal(fs.existsSync(indexPath), false, 'temp index unlinked on the failure path');
    assert.deepEqual(strayIndexFiles(f.repo), [], 'no pathspec or index leftovers in the common dir');
    assert.equal(statusOf(f.worktree), before, 'source worktree untouched by the failed archive');
    assert.equal(digestTree(f.worktree), beforeDigest, 'a failed archive leaves every working file byte-identical');
    assert.equal(fs.existsSync(path.join(f.repo, 'tmp', 'archive')), false, 'a failed archive writes no manifest');
  });

  await check('loadPolicy reads the governance archive block and falls back to defaults', async () => {
    const shipped = loadPolicy(SOURCE_ROOT);
    assert.equal(shipped.refPrefix, 'refs/archive');
    assert.equal(shipped.localDir, 'tmp/archive/worktrees');
    assert.equal(shipped.ignoredPerFileCapBytes, 8388608);
    assert.ok(shipped.declaredCaches.includes('node_modules/'));
    assert.ok(shipped.valuableIgnored.includes('tmp/*.patch'));
    assert.ok(shipped.disposableIgnored.includes('tmp/**/eval-results/**'));
    assert.ok(shipped.source.endsWith('worktree-lifecycle.v1.json'));

    const empty = await fsp.mkdtemp(path.join(os.tmpdir(), '952-archive-policy-'));
    fixtures.push({ root: empty });
    const fallback = loadPolicy(empty);
    assert.equal(fallback.source, 'default');
    assert.equal(fallback.ignoredPerFileCapBytes, 8388608);
    assert.deepEqual(fallback.valuableIgnored, []);
    assert.equal(listArchives({ mainRepoRoot: empty }).length, 0, 'no archive directory is an empty list, not a throw');
  });
} finally {
  for (const fixture of fixtures.reverse()) {
    await fsp.rm(fixture.root, { recursive: true, force: true }).catch(() => {});
  }
}

if (failures.length) {
  console.error(`952-worktree-archive.test: ${failures.length} FAILED / ${passed} passed`);
  for (const failure of failures) console.error(`  x ${failure}`);
  process.exit(1);
}
console.log(`952-worktree-archive.test: ${passed} passed`);
