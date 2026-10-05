/**
 * Base resolution and the full-status diff for the test-intent gate (tempdoc 966 D1 "Mechanics").
 *
 * The kernel's `diffAddedModifiedFiles` is added/modified only, by design for its callers; a test
 * that LEAVES the suite is a deletion, so this gate keeps its own diff. It compares the base commit
 * with the WORKING TREE (tracked changes plus untracked, non-ignored files), which in CI equals HEAD
 * and locally includes work not yet committed — the script runs the same way in both places.
 *
 * Renames are not taken from git's similarity heuristic. A deleted path and an added path are paired
 * as a move only when their blobs are byte-identical; everything else stays a deletion plus an
 * addition, so a renamed-and-edited test flags both sides.
 */

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { gitRefExists, isShallowRepository } from '../../lib/git-utils.mjs';

export class BaseResolutionError extends Error {}

export function git(args, cwd, { input, env } = {}) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    input,
    env: env ?? process.env,
    maxBuffer: 256 * 1024 * 1024,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
}

function tryGit(args, cwd) {
  try {
    return git(args, cwd).trim();
  } catch {
    return null;
  }
}

function readEventPayload(env) {
  const p = env.GITHUB_EVENT_PATH;
  if (!p) return null;
  try {
    return JSON.parse(readFileSync(p, 'utf8'));
  } catch {
    return null;
  }
}

/**
 * Resolve the commit the PR is measured against.
 *
 *  - pull_request : merge-base(origin/$GITHUB_BASE_REF, HEAD)
 *  - merge_group  : merge_group.base_sha from the event payload
 *  - push         : HEAD^1 (the squash commit's first parent)
 *  - otherwise    : explicit --base, else merge-base(origin/main | main, HEAD) — the local run
 *
 * A shallow clone fails closed: the base and the branch's changesets may not be present.
 *
 * @returns {{ref: string, how: string, event: string, prNumber: number|null, fork: boolean}}
 */
export function resolveBase({ repoRoot, env = process.env, explicit = null }) {
  if (isShallowRepository(repoRoot)) {
    throw new BaseResolutionError(
      'shallow clone: the test-intent gate needs full history to find the base and every changeset ' +
        "in the branch. Re-run with 'fetch-depth: 0' (CI) or 'git fetch --unshallow' (local).",
    );
  }
  const event = env.GITHUB_EVENT_NAME || 'local';
  const payload = readEventPayload(env);
  const info = { event, prNumber: prNumberFrom(event, payload, repoRoot), fork: isForkPr(event, payload) };

  if (explicit) {
    const sha = tryGit(['rev-parse', '--verify', `${explicit}^{commit}`], repoRoot);
    if (!sha) throw new BaseResolutionError(`base ref '${explicit}' does not resolve`);
    return { ref: sha, how: `explicit ${explicit}`, ...info };
  }

  if (event === 'pull_request' || event === 'pull_request_target') {
    const baseRef = (env.GITHUB_BASE_REF || payload?.pull_request?.base?.ref || '').trim();
    if (!baseRef) throw new BaseResolutionError('pull_request event without GITHUB_BASE_REF');
    for (const cand of [`origin/${baseRef}`, baseRef]) {
      if (!gitRefExists(cand, repoRoot)) continue;
      const mb = tryGit(['merge-base', cand, 'HEAD'], repoRoot);
      if (mb) return { ref: mb, how: `merge-base(${cand}, HEAD)`, ...info };
    }
    throw new BaseResolutionError(`no merge-base between HEAD and '${baseRef}' (fetch it with full history)`);
  }

  if (event === 'merge_group') {
    const baseSha = payload?.merge_group?.base_sha;
    if (!baseSha) throw new BaseResolutionError('merge_group event payload has no merge_group.base_sha');
    if (!gitRefExists(baseSha, repoRoot)) {
      throw new BaseResolutionError(`merge-group base ${baseSha} is not in the clone (fetch-depth: 0)`);
    }
    return { ref: tryGit(['rev-parse', `${baseSha}^{commit}`], repoRoot), how: 'merge_group.base_sha', ...info };
  }

  if (event === 'push') {
    const parent = tryGit(['rev-parse', '--verify', 'HEAD^1^{commit}'], repoRoot);
    if (!parent) throw new BaseResolutionError('push event: HEAD has no first parent to compare against');
    return { ref: parent, how: 'HEAD^1 (first parent of the pushed commit)', ...info };
  }

  for (const cand of ['origin/main', 'main']) {
    if (!gitRefExists(cand, repoRoot)) continue;
    const mb = tryGit(['merge-base', cand, 'HEAD'], repoRoot);
    if (mb) return { ref: mb, how: `merge-base(${cand}, HEAD)`, ...info };
  }
  throw new BaseResolutionError('no base: pass --base <ref>, or fetch origin/main');
}

function prNumberFrom(event, payload, repoRoot) {
  if (payload?.pull_request?.number) return Number(payload.pull_request.number);
  if (event === 'merge_group') {
    const m = /\/pr-(\d+)-/.exec(payload?.merge_group?.head_ref ?? '');
    if (m) return Number(m[1]);
  }
  if (event === 'push') {
    const subject = tryGit(['log', '-1', '--format=%s'], repoRoot) ?? '';
    const all = [...subject.matchAll(/\(#(\d+)\)/g)];
    if (all.length) return Number(all[all.length - 1][1]);
  }
  return null;
}

function isForkPr(event, payload) {
  if (!event.startsWith('pull_request')) return false;
  const pr = payload?.pull_request;
  if (!pr) return false;
  if (pr.head?.repo?.fork === true) return true;
  const head = pr.head?.repo?.full_name;
  const base = pr.base?.repo?.full_name;
  return Boolean(head && base && head !== base);
}

/** path -> blob id for every file in `ref`. */
export function treeBlobs(ref, repoRoot) {
  const out = new Map();
  const text = git(['ls-tree', '-r', '-z', '--full-tree', ref], repoRoot);
  for (const rec of text.split('\0')) {
    if (!rec) continue;
    const tab = rec.indexOf('\t');
    const [, type, blob] = rec.slice(0, tab).split(' ');
    if (type === 'blob') out.set(rec.slice(tab + 1), blob);
  }
  return out;
}

/** Blob ids of working-tree files, with the repo's clean filters applied (CRLF-safe). */
export function workingBlobs(paths, repoRoot) {
  const out = new Map();
  if (paths.length === 0) return out;
  const text = git(['hash-object', '--stdin-paths'], repoRoot, { input: paths.join('\n') + '\n' });
  const ids = text.split(/\r?\n/).filter(Boolean);
  paths.forEach((p, i) => out.set(p, ids[i]));
  return out;
}

/**
 * Every path that differs between `base` and the working tree.
 *
 * Move pairing (byte-identical only) is the caller's job; this returns raw additions and deletions.
 *
 * @returns {{changes: Array<{path: string, status: 'A'|'D'|'M', before: string|null, after: string|null}>,
 *            baseBlobs: Map<string,string>}}
 */
export function diffWorkingTree(base, repoRoot) {
  const raw = git(['diff', '--no-renames', '--no-ext-diff', '--name-status', '-z', base, '--'], repoRoot);
  const tokens = raw.split('\0');
  const entries = new Map();
  for (let i = 0; i + 1 < tokens.length; i += 2) {
    const status = tokens[i];
    const p = tokens[i + 1];
    if (!status || !p) continue;
    const s = status[0] === 'T' ? 'M' : status[0];
    entries.set(p, s);
  }
  const untracked = git(['ls-files', '--others', '--exclude-standard', '-z'], repoRoot)
    .split('\0')
    .filter(Boolean);
  for (const p of untracked) if (!entries.has(p)) entries.set(p, 'A');

  const baseBlobs = treeBlobs(base, repoRoot);
  const present = [...entries].filter(([, s]) => s !== 'D').map(([p]) => p);
  const after = workingBlobs(present, repoRoot);

  const changes = [];
  for (const [p, s] of entries) {
    const before = baseBlobs.get(p) ?? null;
    const aft = s === 'D' ? null : after.get(p) ?? null;
    // `git diff` against the working tree can list a stat-dirty file whose content is unchanged.
    if (s === 'M' && before && before === aft) continue;
    changes.push({ path: p, status: before === null ? 'A' : aft === null ? 'D' : s === 'A' ? 'M' : s, before, after: aft });
  }
  changes.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { changes, baseBlobs };
}

/** File content (utf8) at a ref, or null when absent. */
export function readAtRef(ref, rel, repoRoot) {
  try {
    return git(['show', `${ref}:${rel}`], repoRoot);
  } catch {
    return null;
  }
}

export function readWorking(rel, repoRoot) {
  try {
    return readFileSync(resolve(repoRoot, rel), 'utf8');
  } catch {
    return null;
  }
}
