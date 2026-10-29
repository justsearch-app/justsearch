/**
 * Git utilities for the discipline-gate kernel (tempdoc 530).
 *
 * Originally factored out of `scripts/contract-governance/lib/git-utils.mjs`
 * (slice 3a-1-8f §A.16 / §A.7) so multiple gate classes can share the same
 * baseline-resolution / shallow-detection / PR-scope-diff machinery.
 *
 * Pure infrastructure — no gate-class semantics live here.
 */

import { execFileSync } from 'node:child_process';

/**
 * @param {string} cwd
 * @returns {boolean}
 */
export function isShallowRepository(cwd) {
  try {
    const out = execFileSync('git', ['rev-parse', '--is-shallow-repository'], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return out === 'true';
  } catch {
    return false;
  }
}

/**
 * Resolve a baseline ref per the registry's baseline strategy.
 *
 * Strategies:
 *  - `tag`        — fixed tag (config-pinned via `ref`)
 *  - `tag-prefix` — most-recent tag matching `prefix`
 *  - `git-base`   — the PR base: the merge-base of `HEAD` and the branch this
 *                   work will land on, with `HEAD~1` as a genuine last resort.
 *                   See `resolveGitBase` for the full ladder.
 *  - `none`       — gates that don't need a git-historical baseline (e.g.,
 *                   ratchet gates that compare against a checked-in baseline
 *                   file). Returns `{ ref: null, strategy: 'none' }`.
 *
 * @param {{strategy: string, ref?: string, prefix?: string, fallback?: string, explicit?: string}} baseline
 * @param {string} cwd
 * @returns {{ref: string|null, strategy: string, fallback?: boolean, base?: string}}
 */
export function resolveBaselineRef(baseline, cwd) {
  const { strategy } = baseline;

  if (strategy === 'none') {
    return { ref: null, strategy };
  }

  if (strategy === 'tag') {
    if (!baseline.ref) {
      throw new Error("baseline.strategy='tag' requires baseline.ref");
    }
    if (!gitRefExists(baseline.ref, cwd)) {
      throw new Error(`baseline ref '${baseline.ref}' does not exist`);
    }
    return { ref: baseline.ref, strategy };
  }

  if (strategy === 'tag-prefix') {
    if (!baseline.prefix) {
      throw new Error("baseline.strategy='tag-prefix' requires baseline.prefix");
    }
    const tags = execFileSync('git', ['tag', '-l', `${baseline.prefix}*`, '--sort=-v:refname'], {
      cwd,
      encoding: 'utf8',
    })
      .split(/\r?\n/)
      .filter(Boolean);
    if (tags.length === 0) {
      if (baseline.fallback) {
        return { ref: baseline.fallback, strategy: 'tag-prefix-fallback', fallback: true };
      }
      throw new Error(
        `baseline.strategy='tag-prefix' but no tags match '${baseline.prefix}*' and no fallback configured`,
      );
    }
    return { ref: tags[0], strategy };
  }

  if (strategy === 'git-base') {
    return resolveGitBase(baseline, cwd);
  }

  throw new Error(`Unknown baseline.strategy: ${strategy}`);
}

/**
 * Candidate branch refs this HEAD could be landing on, most-authoritative first.
 *
 * @param {string} cwd
 * @returns {string[]}
 */
function defaultBranchCandidates(cwd) {
  const out = [];
  // A CI pull_request run states its target branch outright.
  const envBase = (process.env.GITHUB_BASE_REF ?? '').trim();
  if (envBase) out.push(`origin/${envBase}`, envBase);
  // `origin/HEAD` is the remote's own answer to "what is the default branch".
  try {
    const sym = execFileSync('git', ['symbolic-ref', '--short', 'refs/remotes/origin/HEAD'], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    if (sym) out.push(sym);
  } catch {
    /* no origin/HEAD — fall through to the conventional names */
  }
  out.push('origin/main', 'origin/master', 'main', 'master');
  return out.filter((ref, i) => out.indexOf(ref) === i);
}

/**
 * @param {string} a
 * @param {string} b
 * @param {string} cwd
 * @returns {string|null} the merge-base commit sha, or null if there is none
 */
function mergeBase(a, b, cwd) {
  try {
    const out = execFileSync('git', ['merge-base', a, b], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return out || null;
  } catch {
    return null;
  }
}

/**
 * @param {string} ref
 * @param {string} cwd
 * @returns {string|null}
 */
function revParse(ref, cwd) {
  try {
    return execFileSync('git', ['rev-parse', '--verify', `${ref}^{commit}`], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
  } catch {
    return null;
  }
}

/**
 * Resolve the `git-base` strategy to the ref a PR would actually be diffed against.
 *
 * The ladder, in order (tempdoc 884 §F row 11 — the docstring promised a PR base
 * and the implementation only ever returned the fallback, so every `git-base`
 * gate diffed a one-commit window and a changeset committed earlier in the branch
 * dropped out of scope the moment another commit landed):
 *
 *  1. `baseline.explicit` — an operator naming a ref (`--preflight <ref>`) is an
 *     instruction, not a guess; it wins outright.
 *  2. `merge-base(HEAD, <default branch>)` — the real PR base. Candidates come
 *     from `GITHUB_BASE_REF` (a CI `pull_request` run), then `origin/HEAD`, then
 *     the conventional names.
 *  3. `baseline.fallback ?? 'HEAD~1'` — the genuine last resort: no remote, or a
 *     HEAD that IS the default branch tip.
 *
 * Rung 3 is what keeps post-merge behaviour identical. After the merge queue
 * squashes, `HEAD` is the default branch tip, so `merge-base(HEAD, origin/main)`
 * is `HEAD` itself — an empty window that would silently disable every diff-scoped
 * check at the one moment it bites CI. A merge-base equal to `HEAD` is therefore
 * treated as "no divergence to measure" and falls through to `HEAD~1`.
 *
 * @param {{fallback?: string, explicit?: string}} baseline
 * @param {string} cwd
 * @returns {{ref: string, strategy: string, fallback?: boolean, base?: string}}
 */
export function resolveGitBase(baseline, cwd) {
  if (baseline.explicit) {
    if (!gitRefExists(baseline.explicit, cwd)) {
      throw new Error(
        `baseline ref '${baseline.explicit}' is unreachable. ` +
          `If running in CI, ensure 'actions/checkout' uses 'fetch-depth: 0'.`,
      );
    }
    return { ref: baseline.explicit, strategy: 'git-base-explicit' };
  }

  const head = revParse('HEAD', cwd);
  for (const candidate of defaultBranchCandidates(cwd)) {
    if (!gitRefExists(candidate, cwd)) continue;
    const base = mergeBase(candidate, 'HEAD', cwd);
    // base === head means HEAD is an ancestor of (or identical to) the default
    // branch — nothing has diverged, so there is no PR window. Fall through.
    if (!base || base === head) continue;
    return { ref: base, strategy: 'git-base-merge-base', base: candidate };
  }

  const lastResort = baseline.fallback ?? 'HEAD~1';
  if (!gitRefExists(lastResort, cwd)) {
    throw new Error(
      `baseline ref '${lastResort}' is unreachable. ` +
        `If running in CI, ensure 'actions/checkout' uses 'fetch-depth: 0'.`,
    );
  }
  return { ref: lastResort, strategy: 'git-base', fallback: true };
}

/**
 * @param {string} ref
 * @param {string} cwd
 * @returns {boolean}
 */
export function gitRefExists(ref, cwd) {
  try {
    execFileSync('git', ['rev-parse', '--verify', `${ref}^{commit}`], {
      cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Read a file at a given git ref.
 *
 * @param {string} ref
 * @param {string} filePath — repo-relative path
 * @param {string} cwd
 * @returns {string|null}   — null if file did not exist at ref
 */
export function readFileAtRef(ref, filePath, cwd) {
  try {
    const out = execFileSync('git', ['show', `${ref}:${filePath}`], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return out;
  } catch {
    return null;
  }
}

/**
 * Files added or modified between baseline ref and HEAD under a path filter.
 *
 * PR-scope semantics (slice 3a-1-8f §A.18): only files added/modified count.
 * Files merged from unrelated PRs (present at baseline) are ignored.
 *
 * @param {string} baselineRef
 * @param {string} pathFilter   — e.g., "contracts/wire/.changesets"
 * @param {string} cwd
 * @returns {string[]}          — repo-relative paths
 */
export function diffAddedModifiedFiles(baselineRef, pathFilter, cwd) {
  try {
    const out = execFileSync(
      'git',
      ['diff', '--diff-filter=AM', '--name-only', `${baselineRef}...HEAD`, '--', pathFilter],
      { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
    );
    return out
      .split(/\r?\n/)
      .map(s => s.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
}
