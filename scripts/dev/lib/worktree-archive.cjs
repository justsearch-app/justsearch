#!/usr/bin/env node
/**
 * Tempdoc 952 P3 — preservation before removal (§5.5, amended by derisk §10 D3 / Amendment A).
 *
 * A worktree is only safe to delete once everything a session could still want out of it lives
 * somewhere that survives the directory. This module is that "somewhere": Git's own object
 * database for content, plus one JSON manifest per archive for the human/tool-readable index.
 *
 * Three mechanisms, all proven on this repository's Windows filesystem by `tmp/archive-probe.mjs`
 * (derisk D2):
 *   1. TIP REF — the committed head goes to `<refPrefix>/<resource>/<short-sha>` through a
 *      conditional `update-ref … ''` (create-only). Committed work then survives branch deletion.
 *   2. STATE COMMIT — dirty working state (tracked modifications, untracked files, and ignored
 *      files that policy says are valuable) becomes ONE commit built through a TEMPORARY index
 *      (`GIT_INDEX_FILE` in the common dir): `read-tree HEAD` → `add -A --force` with an exclusion
 *      pathspec → `write-tree` → `commit-tree … -p HEAD`. The source worktree is never written to,
 *      so it is byte-identical before and after — that property is what lets an archive run BEFORE
 *      the removal decision is final, and it is asserted by the tests, not assumed.
 *   3. MANIFEST — `<mainRepoRoot>/<localDir>/<resource>/<ts>.json` records head, both refs, and
 *      every archived file with its blob id, size and class, plus what was deliberately SKIPPED.
 *      A skipped file is named, never silently dropped: "we did not keep this" is a fact the
 *      operator must be able to read afterwards.
 *
 * Amendment A (D3) is why classification exists at all. 15 of 24 measured worktrees held 0-4 MB of
 * ignored non-cache content, but `lane-F-A` held 4.3 GB of eval run data. So ignored files are
 * archived up to a per-file cap OR when they match a declared-valuable glob; anything larger is
 * REFUSED (typed refusal, no partial archive) until the caller names it in `discardIgnored` or a
 * declared-disposable glob covers it. Declared caches (`node_modules/`, `build/`, …) are never
 * archived and never refuse anything.
 *
 * Nothing here throws for an expected condition: an oversized ignored file, a moved tip, a failing
 * git invocation all come back as `{ refused: true, reason, … }`. Callers (P5's CLI, the
 * reconciler) branch on `reason`; an exception would mean a bug in this module, not a state of the
 * repository.
 *
 * The `git` runner is injectable on every export for the same reason `process-record.cjs` injects
 * its readers: the failure branches (a `commit-tree` that dies mid-archive, leaving a temp index
 * behind) are not reproducible against a real git, and an untested cleanup path is how a temp file
 * ends up permanently in someone's `.git/`.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const { resolveMainRepoRoot } = require('./agent-spawn-sweep.cjs');

const POLICY_REL = ['governance', 'worktree-lifecycle.v1.json'];

/**
 * Defaults for the `archive` block. Present so a caller can archive against a checkout whose
 * governance file is missing or unreadable (a half-removed worktree is exactly the situation this
 * module runs in) instead of refusing to preserve anything. They mirror
 * `governance/worktree-lifecycle.v1.json`; that file, when readable, always wins.
 */
const DEFAULT_ARCHIVE_POLICY = Object.freeze({
  refPrefix: 'refs/archive',
  localDir: 'tmp/archive/worktrees',
  ignoredPerFileCapBytes: 8 * 1024 * 1024,
  declaredCaches: Object.freeze([
    'node_modules/', 'build/', '.gradle/', 'dist/', 'target/', 'tmp/dev-runner/',
    '.mypy_cache/', '__pycache__/', '.pytest_cache/',
  ]),
  valuableIgnored: Object.freeze([]),
  disposableIgnored: Object.freeze([]),
});

/* ── git runner ─────────────────────────────────────────────────────────────────────────────── */

/**
 * The production git runner. Returns stdout as a string and throws on a non-zero exit (the callers
 * below convert that into a typed refusal — this stays the thin, honest layer).
 *
 * @param {string[]} args
 * @param {{cwd?: string, env?: Record<string,string>}} [options]
 * @returns {string}
 */
function defaultGit(args, { cwd = process.cwd(), env = null } = {}) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: env ? { ...process.env, ...env } : process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: 128 * 1024 * 1024,
  });
}

/** Trimmed single-value git output. */
function gitText(git, args, options) {
  const out = git(args, options);
  return typeof out === 'string' ? out.trim() : String(out ?? '').trim();
}

/** NUL-separated git output as an array, with the trailing empty element dropped. */
function gitZ(git, args, options) {
  const out = git(args, options);
  const text = typeof out === 'string' ? out : String(out ?? '');
  return text.split('\0').filter((entry) => entry.length > 0);
}

/** A typed refusal for a git invocation that failed. Never leaks the raw exception. */
function gitRefusal(step, err) {
  const stderr = err && err.stderr ? String(err.stderr).trim() : '';
  const message = stderr || (err && err.message ? String(err.message).trim() : String(err));
  return { refused: true, reason: 'git-failed', step, message, oversized: [] };
}

/* ── policy ─────────────────────────────────────────────────────────────────────────────────── */

/**
 * Read the `archive` block of `governance/worktree-lifecycle.v1.json` with defaults filled.
 * Unreadable or malformed policy yields the defaults plus `source: 'default'` — this module's job
 * is to preserve work, so a missing policy file must not become a reason to preserve nothing.
 *
 * @param {string} mainRepoRoot
 * @returns {{refPrefix:string, localDir:string, ignoredPerFileCapBytes:number,
 *   declaredCaches:string[], valuableIgnored:string[], disposableIgnored:string[], source:string}}
 */
function loadPolicy(mainRepoRoot) {
  const file = path.join(mainRepoRoot, ...POLICY_REL);
  let block = null;
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (parsed && typeof parsed === 'object' && parsed.archive && typeof parsed.archive === 'object') {
      block = parsed.archive;
    }
  } catch { /* fall through to defaults */ }
  const str = (value, fallback) => (typeof value === 'string' && value.trim() ? value.trim() : fallback);
  const list = (value, fallback) => (Array.isArray(value) ? value.filter((v) => typeof v === 'string' && v.trim()) : [...fallback]);
  const num = (value, fallback) => (Number.isFinite(value) && value >= 0 ? value : fallback);
  return {
    refPrefix: str(block?.refPrefix, DEFAULT_ARCHIVE_POLICY.refPrefix).replace(/\/+$/, ''),
    localDir: str(block?.localDir, DEFAULT_ARCHIVE_POLICY.localDir),
    ignoredPerFileCapBytes: num(block?.ignoredPerFileCapBytes, DEFAULT_ARCHIVE_POLICY.ignoredPerFileCapBytes),
    declaredCaches: list(block?.declaredCaches, DEFAULT_ARCHIVE_POLICY.declaredCaches),
    valuableIgnored: list(block?.valuableIgnored, DEFAULT_ARCHIVE_POLICY.valuableIgnored),
    disposableIgnored: list(block?.disposableIgnored, DEFAULT_ARCHIVE_POLICY.disposableIgnored),
    source: block ? file : 'default',
  };
}

/* ── glob matching ──────────────────────────────────────────────────────────────────────────── */

const GLOB_CACHE = new Map();

/** Escape a literal run for use inside a RegExp. */
function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Translate a policy glob into a RegExp. Deliberately small (no dependency, no brace expansion,
 * no character classes) because the policy vocabulary is small and a surprising match here would
 * silently discard someone's work:
 *   - `**` spans path segments (`tmp/**\/eval-results/**` matches `tmp/eval-results/x` too — a
 *     middle `**` may match zero segments, which is what gitignore/minimatch do);
 *   - `*` and `?` stay inside one segment;
 *   - anything else is literal, and a pattern with no wildcard is an exact path match.
 */
function globToRegExp(pattern) {
  const cached = GLOB_CACHE.get(pattern);
  if (cached) return cached;
  const segments = pattern.split('/');
  let source = '^';
  segments.forEach((segment, index) => {
    const last = index === segments.length - 1;
    if (segment === '**') {
      if (last) source += index === 0 ? '.*' : '(?:/.*)?';
      else source += index === 0 ? '(?:[^/]+/)*' : '(?:/[^/]+)*/';
      return;
    }
    if (index > 0 && segments[index - 1] !== '**') source += '/';
    source += segment.replace(/\*\*|[*?]|[^*?]+/g, (token) => {
      if (token === '**' || token === '*') return '[^/]*';
      if (token === '?') return '[^/]';
      return escapeRegExp(token);
    });
  });
  source += '$';
  const regex = new RegExp(source);
  GLOB_CACHE.set(pattern, regex);
  return regex;
}

/**
 * Does `filePath` (repo-relative, forward slashes) match `pattern`?
 * @param {string} pattern
 * @param {string} filePath
 * @returns {boolean}
 */
function matchesGlob(pattern, filePath) {
  if (typeof pattern !== 'string' || typeof filePath !== 'string') return false;
  const p = normalizeRelPath(pattern);
  const f = normalizeRelPath(filePath);
  if (!p) return false;
  if (p === f) return true;
  if (!/[*?]/.test(p)) return false;
  return globToRegExp(p).test(f);
}

/** Any-of form used for `discardIgnored` (exact path or glob) and the policy glob lists. */
function matchesAnyGlob(patterns, filePath) {
  return (patterns || []).some((pattern) => matchesGlob(pattern, filePath));
}

/** Normalize to repo-relative forward slashes with no leading/trailing separator. */
function normalizeRelPath(value) {
  return String(value ?? '').replace(/\\/g, '/').replace(/^\.\//, '').replace(/^\/+/, '').replace(/\/+$/, '');
}

/**
 * Declared-cache match: prefix OR any-segment-boundary containment, so both `node_modules/x` and
 * `modules/ui-web/node_modules/x` are caches, while `my-node_modules-notes.md` is not.
 */
function matchesDeclaredCache(caches, filePath) {
  const f = normalizeRelPath(filePath);
  return (caches || []).some((raw) => {
    const c = normalizeRelPath(raw);
    if (!c) return false;
    return f === c || f.startsWith(`${c}/`) || f.includes(`/${c}/`) || f.endsWith(`/${c}`);
  });
}

/* ── classification ─────────────────────────────────────────────────────────────────────────── */

/**
 * Split the worktree's ignored files into the four policy buckets (Amendment A).
 *
 * Order is load-bearing and matches the amendment: declared cache → disposable → valuable →
 * over-cap → archive. Valuable BEFORE the cap check is the point of the valuable list: a 40 MB
 * `tmp/session.log` that policy calls valuable is archived, not refused.
 *
 * @param {{worktreePath:string, policy:object, git?:Function}} options
 * @returns {{archive:Array, oversized:Array, disposable:Array, caches:Array}|{refused:true}}
 */
function classifyIgnored({ worktreePath, policy, git = defaultGit }) {
  const active = policy || DEFAULT_ARCHIVE_POLICY;
  let entries;
  try {
    entries = gitZ(git, ['ls-files', '--others', '--ignored', '--exclude-standard', '-z'], { cwd: worktreePath });
  } catch (err) {
    return gitRefusal('ls-files-ignored', err);
  }
  const buckets = { archive: [], oversized: [], disposable: [], caches: [] };
  for (const raw of entries) {
    const rel = normalizeRelPath(raw);
    if (!rel) continue;
    let size = 0;
    try {
      size = fs.statSync(path.join(worktreePath, rel)).size;
    } catch {
      // Vanished between `ls-files` and here (a live build writing into `build/`). Nothing to
      // archive, nothing to refuse over.
      continue;
    }
    const entry = { path: rel, size };
    if (matchesDeclaredCache(active.declaredCaches, rel)) buckets.caches.push(entry);
    else if (matchesAnyGlob(active.disposableIgnored, rel)) buckets.disposable.push(entry);
    else if (matchesAnyGlob(active.valuableIgnored, rel)) buckets.archive.push(entry);
    else if (size > active.ignoredPerFileCapBytes) buckets.oversized.push(entry);
    else buckets.archive.push(entry);
  }
  return buckets;
}

/* ── archive ────────────────────────────────────────────────────────────────────────────────── */

/** `20260910T142530Z` — filesystem- and ref-safe, sorts chronologically. */
function compactTimestamp(now) {
  const date = new Date(typeof now === 'number' || now instanceof Date ? now : Date.now());
  return date.toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
}

/**
 * The common (shared) git directory for a path that may itself be a linked worktree — where the
 * temporary index has to live so a per-worktree gitdir teardown cannot strand it.
 */
function resolveCommonDir({ worktreePath, git = defaultGit }) {
  return gitText(git, ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: worktreePath });
}

/**
 * Preserve a worktree: tip ref, state commit, manifest. See the module header for the mechanism.
 *
 * Refusals (never exceptions): `oversized-ignored` (with the offending entries), `tip-ref-conflict`
 * (the archive ref name already points somewhere else), `git-failed` (with the failing step).
 *
 * @param {{mainRepoRoot:string, worktreePath:string, resource:string, policy?:object,
 *   discardIgnored?:string[], git?:Function, now?:number|Date}} options
 */
function archiveWorktree({
  mainRepoRoot,
  worktreePath,
  resource,
  policy = null,
  discardIgnored = [],
  git = defaultGit,
  now = Date.now(),
}) {
  const active = policy || loadPolicy(mainRepoRoot);
  const classified = classifyIgnored({ worktreePath, policy: active, git });
  if (classified.refused) return classified;

  // Amendment A: refuse as a whole rather than archive a partial tree. A caller that meant to drop
  // the 4.3 GB of eval output says so explicitly.
  const discarded = [];
  const blocking = [];
  for (const entry of classified.oversized) {
    if (matchesAnyGlob(discardIgnored, entry.path)) discarded.push(entry);
    else blocking.push(entry);
  }
  if (blocking.length > 0) {
    return {
      refused: true,
      reason: 'oversized-ignored',
      oversized: blocking,
      message: `${blocking.length} ignored file(s) exceed the ${active.ignoredPerFileCapBytes}-byte cap; name them in discardIgnored or add a declared-disposable glob`,
    };
  }

  let head;
  let shortSha;
  let commonDir;
  try {
    head = gitText(git, ['rev-parse', 'HEAD'], { cwd: worktreePath });
    shortSha = gitText(git, ['rev-parse', '--short', 'HEAD'], { cwd: worktreePath });
    commonDir = resolveCommonDir({ worktreePath, git });
  } catch (err) {
    return gitRefusal('resolve-head', err);
  }

  // (1) tip ref — conditional create. An existing ref with the same value is success, not a
  // conflict: archiving twice must be idempotent, because the reconciler retries.
  const tipRef = `${active.refPrefix}/${resource}/${shortSha}`;
  let tipRefCreated = false;
  try {
    git(['update-ref', tipRef, head, ''], { cwd: mainRepoRoot });
    tipRefCreated = true;
  } catch (createErr) {
    let existing = '';
    try {
      existing = gitText(git, ['rev-parse', '--verify', '--quiet', `${tipRef}^{commit}`], { cwd: mainRepoRoot });
    } catch { existing = ''; }
    if (existing !== head) {
      return existing
        ? { refused: true, reason: 'tip-ref-conflict', tipRef, expected: head, actual: existing, oversized: [] }
        : gitRefusal('update-ref-tip', createErr);
    }
  }

  // (2) state commit through a temporary index in the common dir. Unique per process AND per call:
  // two archives running in one process must not share an index file.
  const stamp = compactTimestamp(now);
  const unique = `${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
  const indexFile = path.join(commonDir, `952-archive-index-${unique}`);
  const pathspecFile = path.join(commonDir, `952-archive-pathspec-${unique}`);
  const excluded = [
    ...active.declaredCaches.map((c) => `:!${normalizeRelPath(c)}`),
    // `literal` so a path containing `*` or `[` is excluded as itself, not as a pattern.
    ...[...classified.disposable, ...discarded].map((e) => `:(exclude,literal)${e.path}`),
  ];
  // A pathspec FILE rather than argv: a disposable eval-results tree can hold thousands of paths,
  // and Windows' ~32k command line would truncate the exclusion — silently archiving what policy
  // said to drop.
  const pathspecs = ['.', ...excluded];

  let stateCommit;
  let treeFiles;
  try {
    fs.writeFileSync(pathspecFile, pathspecs.join('\0'));
    const env = { GIT_INDEX_FILE: indexFile };
    git(['read-tree', 'HEAD'], { cwd: worktreePath, env });
    git(['add', '-A', '--force', `--pathspec-from-file=${pathspecFile}`, '--pathspec-file-nul'], { cwd: worktreePath, env });
    const tree = gitText(git, ['write-tree'], { cwd: worktreePath, env });
    stateCommit = gitText(git, ['commit-tree', tree, '-p', head, '-m', `archive: working state of ${resource}`], { cwd: worktreePath, env });
    // `-z` on both listings below: without it git C-quotes any path with a non-ASCII or special
    // character, and this repository has such files — a quoted path would silently never match its
    // manifest entry.
    treeFiles = gitZ(git, ['ls-tree', '-r', '-z', stateCommit], { cwd: mainRepoRoot });
  } catch (err) {
    return gitRefusal('state-commit', err);
  } finally {
    // Always — a temp index left in `.git/` is a foreign file in a shared directory, and the
    // failure path is exactly when it would be left behind.
    try { fs.unlinkSync(indexFile); } catch { /* never existed, or already gone */ }
    try { fs.unlinkSync(pathspecFile); } catch { /* idem */ }
  }

  const stateRef = `${active.refPrefix}/${resource}/state-${stamp}`;
  let changed;
  let untracked;
  try {
    git(['update-ref', stateRef, stateCommit], { cwd: mainRepoRoot });
    changed = gitZ(git, ['diff-tree', '--no-commit-id', '-r', '--name-only', '-z', head, stateCommit], { cwd: mainRepoRoot });
    untracked = new Set(gitZ(git, ['ls-files', '--others', '--exclude-standard', '-z'], { cwd: worktreePath }).map(normalizeRelPath));
  } catch (err) {
    return gitRefusal('state-ref', err);
  }

  // (3) manifest.
  const blobs = new Map();
  for (const line of treeFiles) {
    // `<mode> blob <sha>\t<path>`
    const match = /^\d+ (\w+) ([0-9a-f]{40,64})\t([\s\S]*)$/.exec(line);
    if (match && match[1] === 'blob') blobs.set(normalizeRelPath(match[3]), match[2]);
  }
  const ignoredSizes = new Map();
  for (const entry of classified.archive) ignoredSizes.set(entry.path, entry.size);
  const valuableSet = new Set(classified.archive.filter((e) => matchesAnyGlob(active.valuableIgnored, e.path)).map((e) => e.path));

  const files = changed.map((raw) => {
    const rel = normalizeRelPath(raw);
    const blob = blobs.get(rel) || null;
    let size = ignoredSizes.has(rel) ? ignoredSizes.get(rel) : null;
    if (size === null) {
      try { size = fs.statSync(path.join(worktreePath, rel)).size; } catch { size = 0; }
    }
    let klass;
    if (ignoredSizes.has(rel)) klass = valuableSet.has(rel) ? 'ignored-valuable' : 'ignored';
    else if (untracked.has(rel)) klass = 'untracked';
    else klass = 'tracked-modified';
    return { path: rel, blob, size, class: klass };
  });

  const skipped = [
    ...classified.disposable.map((e) => ({ path: e.path, size: e.size, reason: 'declared-disposable' })),
    ...discarded.map((e) => ({ path: e.path, size: e.size, reason: 'oversized-discarded' })),
  ];

  const manifest = {
    resource,
    worktreePath: String(worktreePath).replace(/\\/g, '/'),
    head,
    tipRef,
    stateRef,
    stateCommit,
    createdAt: new Date(typeof now === 'number' || now instanceof Date ? now : Date.now()).toISOString(),
    files,
    skipped,
    // Summary only: a per-file list of `node_modules` would be tens of thousands of entries and
    // says nothing an operator needs. The count is still evidence that caches were seen and
    // deliberately not archived.
    caches: { files: classified.caches.length, bytes: classified.caches.reduce((sum, e) => sum + e.size, 0) },
  };

  const manifestPath = path.join(mainRepoRoot, ...active.localDir.split('/'), resource, `${stamp}.json`);
  try {
    fs.mkdirSync(path.dirname(manifestPath), { recursive: true });
    fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  } catch (err) {
    return { refused: true, reason: 'manifest-write-failed', message: String(err && err.message ? err.message : err), oversized: [] };
  }

  return { head, tipRef, tipRefCreated, stateRef, stateCommit, manifestPath, manifest };
}

/* ── verification ───────────────────────────────────────────────────────────────────────────── */

/**
 * Prove an archive is still both COMPLETE (every blob is in the object store, the state ref still
 * resolves to the state commit) and CURRENT (each still-present working file hashes to the blob
 * that was archived).
 *
 * A `mismatched` entry does not mean corruption — it means the worktree changed after archiving,
 * so the archive no longer represents it and the caller must re-archive before removing anything.
 * That distinction is the whole reason this is separate from `archiveWorktree`.
 *
 * @param {{mainRepoRoot:string, manifest:object, git?:Function}} options
 * @returns {{ok:boolean, missing:string[], mismatched:string[], errors:string[]}}
 */
function verifyArchive({ mainRepoRoot, manifest, git = defaultGit }) {
  const missing = [];
  const mismatched = [];
  const errors = [];
  if (!manifest || typeof manifest !== 'object') {
    return { ok: false, missing, mismatched, errors: ['manifest is not an object'] };
  }
  const worktreePath = manifest.worktreePath;

  try {
    const resolved = gitText(git, ['rev-parse', '--verify', '--quiet', `${manifest.stateRef}^{commit}`], { cwd: mainRepoRoot });
    if (resolved !== manifest.stateCommit) {
      errors.push(`stateRef ${manifest.stateRef} resolves to ${resolved || '<missing>'}, expected ${manifest.stateCommit}`);
    }
  } catch (err) {
    errors.push(`stateRef ${manifest.stateRef} does not resolve: ${err && err.message ? err.message : err}`);
  }

  for (const entry of manifest.files || []) {
    if (!entry || !entry.blob) continue;
    try {
      git(['cat-file', '-e', `${entry.blob}^{blob}`], { cwd: mainRepoRoot });
    } catch {
      missing.push(entry.path);
      continue;
    }
    if (!worktreePath) continue;
    const absolute = path.join(worktreePath, entry.path);
    if (!fs.existsSync(absolute)) continue; // Deleted since archiving — the archive still holds it.
    try {
      // Filters ARE applied (no `--no-filters`) so this hashes the same way `git add` did when the
      // blob was written; otherwise every CRLF-normalized file would report a false mismatch.
      const current = gitText(git, ['hash-object', '--', entry.path], { cwd: worktreePath });
      if (current !== entry.blob) mismatched.push(entry.path);
    } catch (err) {
      errors.push(`hash-object failed for ${entry.path}: ${err && err.message ? err.message : err}`);
    }
  }

  return { ok: missing.length === 0 && mismatched.length === 0 && errors.length === 0, missing, mismatched, errors };
}

/* ── restore ────────────────────────────────────────────────────────────────────────────────── */

/**
 * Materialize a state commit into a THROWAWAY detached worktree so a human (or `reconcile`'s
 * sampling probe) can look at what was preserved. Detached on purpose: an archive is not a branch
 * and restoring one must not create a name that then needs its own lifecycle.
 *
 * @param {{mainRepoRoot:string, stateCommit:string, destination:string, git?:Function}} options
 * @returns {{path:string}|{refused:true}}
 */
function restoreArchive({ mainRepoRoot, stateCommit, destination, git = defaultGit }) {
  try {
    git(['worktree', 'add', '--detach', destination, stateCommit], { cwd: mainRepoRoot });
  } catch (err) {
    return gitRefusal('worktree-add-restore', err);
  }
  return { path: destination };
}

/**
 * Remove a worktree created by `restoreArchive`. `--force` is correct here and only here: the
 * caller owns this directory, it was created seconds ago from an archived commit, and its contents
 * are by construction reproducible from `stateCommit`.
 *
 * @param {{mainRepoRoot:string, path:string, git?:Function}} options
 */
function removeRestored({ mainRepoRoot, path: worktreePath, git = defaultGit }) {
  try {
    git(['worktree', 'remove', '--force', worktreePath], { cwd: mainRepoRoot });
  } catch (err) {
    return gitRefusal('worktree-remove-restore', err);
  }
  return { removed: true, path: worktreePath };
}

/* ── inventory ──────────────────────────────────────────────────────────────────────────────── */

/**
 * Every archive manifest on this machine, newest first — the input to world-state's "archives:
 * count, bytes, age" line (§5.5). `bytes` is the archived content total from the manifest's own
 * file list, which is what an operator deciding whether to prune is actually asking about; the
 * manifest JSON's own few kB is noise.
 *
 * Never throws and never hides an unreadable manifest: a torn file comes back with
 * `unreadable: true` rather than being skipped, because "there is an archive here I cannot read"
 * is a different claim from "there is no archive".
 *
 * @param {{mainRepoRoot:string, policy?:object}} options
 * @returns {Array<{resource:string, manifestPath:string, createdAt:string|null, bytes:number}>}
 */
function listArchives({ mainRepoRoot, policy = null }) {
  const active = policy || loadPolicy(mainRepoRoot);
  const root = path.join(mainRepoRoot, ...active.localDir.split('/'));
  const rows = [];
  let resources;
  try {
    resources = fs.readdirSync(root, { withFileTypes: true }).filter((e) => e.isDirectory());
  } catch {
    return rows;
  }
  for (const dir of resources) {
    const resourceDir = path.join(root, dir.name);
    let names;
    try {
      names = fs.readdirSync(resourceDir).filter((n) => n.endsWith('.json'));
    } catch {
      continue;
    }
    for (const name of names) {
      const manifestPath = path.join(resourceDir, name);
      try {
        const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
        rows.push({
          resource: typeof manifest.resource === 'string' ? manifest.resource : dir.name,
          manifestPath,
          createdAt: typeof manifest.createdAt === 'string' ? manifest.createdAt : null,
          bytes: (Array.isArray(manifest.files) ? manifest.files : []).reduce((sum, f) => sum + (Number.isFinite(f?.size) ? f.size : 0), 0),
        });
      } catch {
        rows.push({ resource: dir.name, manifestPath, createdAt: null, bytes: 0, unreadable: true });
      }
    }
  }
  rows.sort((a, b) => {
    const at = a.createdAt || '';
    const bt = b.createdAt || '';
    if (at === bt) return a.manifestPath < b.manifestPath ? 1 : -1;
    return at < bt ? 1 : -1;
  });
  return rows;
}

module.exports = {
  DEFAULT_ARCHIVE_POLICY,
  defaultGit,
  loadPolicy,
  matchesGlob,
  matchesAnyGlob,
  matchesDeclaredCache,
  normalizeRelPath,
  resolveCommonDir,
  resolveMainRepoRoot,
  classifyIgnored,
  archiveWorktree,
  verifyArchive,
  restoreArchive,
  removeRestored,
  listArchives,
};
