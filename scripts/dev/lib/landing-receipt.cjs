#!/usr/bin/env node
/**
 * Tempdoc 952 §5.3 — landed-ness receipts.
 *
 * ADR-0045 squash-merges every PR, so a branch's own commits never appear in `main` and
 * ancestry cannot answer "did this work land?" (`rule:squash-merge-verify-content-not-ancestry`).
 * This module answers it by CONTENT, with a controlled three-way simulation, and records the
 * answer as a receipt so the reconciler does not have to re-derive it (and cannot silently
 * upgrade "I could not tell" into "safe to delete").
 *
 * Three verdicts, and only two of them authorize retirement:
 *
 *   LANDED         the branch head is fully contained in the squash commit its merged PR
 *                  produced. Historical and stable: it stays true no matter what `main` does
 *                  afterwards, which is why the receipt names the squash it was proven against.
 *   REDUNDANT_NOW  no accepted squash, but merging the head into the CURRENT target changes
 *                  nothing. Weaker and time-dependent: it is a statement about today's target.
 *   UNKNOWN        anything else — no merged PR, a moved head, a conflict, a differing tree, a
 *                  git failure, or a repository whose merge configuration makes the simulation
 *                  untrustworthy. UNKNOWN never authorizes retirement.
 *
 * WHY THE SIMULATION IS RUN WITH FORCED CONFIG (`-c merge.default= -c merge.renormalize=false`):
 * a `merge.default` custom driver that keeps its own side and exits 0 makes ANY branch look
 * landed — the merge returns the target tree while the branch's unique content is silently
 * dropped. Measured on git 2.53.0.windows.3 (probe, 2026-09-10):
 *
 *   - `merge.default=<driver>` in config: uncontrolled → false "equal to target tree";
 *     with `-c merge.default=` → conflict, i.e. the honest driver-free outcome. Controlled.
 *   - the SAME driver reached through a `.gitattributes` `merge=<driver>` line: `-c merge.default=`
 *     does NOT neutralize it (the attribute path does not consult `merge.default`), and the
 *     simulation still returns the target tree. `-c merge.<driver>.driver=` only turns the clean
 *     result into a flagged conflict, it does not restore built-in merging.
 *
 * So config forcing alone is not sufficient, and the safe answer for a repository that configures
 * ANY custom merge driver is to refuse: `computeReceipt` returns UNKNOWN with the
 * `custom-merge-driver` reason before running a simulation it cannot trust. A `.gitattributes`
 * `merge=<name>` whose driver is NOT configured falls back to git's built-in three-way merge and
 * is therefore harmless — which is exactly what the config probe distinguishes.
 *
 * NO NETWORK HERE. Reachability is judged against whatever `target` currently resolves to in the
 * local repository; the CALLER fetches first (`git fetch origin main`) if it wants "reachable from
 * today's origin/main". A library that fetched would make every read of a receipt a network call.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

/** The only three verdicts. `LANDED` and `REDUNDANT_NOW` authorize retirement; `UNKNOWN` never does. */
const RECEIPT_VERDICTS = Object.freeze({
  LANDED: 'LANDED',
  REDUNDANT_NOW: 'REDUNDANT_NOW',
  UNKNOWN: 'UNKNOWN',
});

/** NDJSON cache path, relative to the repository root (`telemetry-io`'s `tmp/agent-telemetry`). */
const DEFAULT_RECEIPTS_FILE = 'tmp/agent-telemetry/landing-receipts.ndjson';

/** Default comparison target. Local ref name — resolution never fetches (see the header). */
const DEFAULT_TARGET = 'origin/main';

/**
 * Stable reason codes. A reason string is `<code>: <detail>`, so a caller (or a test) matches on
 * the code and still gets the detail for a human.
 */
const RECEIPT_REASONS = Object.freeze({
  LANDED: 'landed',
  REDUNDANT_NOW: 'redundant-now',
  NO_MERGED_PR: 'no-merged-pr',
  PR_HEAD_MISMATCH: 'pr-head-mismatch',
  HEAD_UNRESOLVABLE: 'head-unresolvable',
  TARGET_UNRESOLVABLE: 'target-unresolvable',
  NO_BASE: 'no-base',
  CUSTOM_MERGE_DRIVER: 'custom-merge-driver',
  GIT_FAILED: 'git-failed',
  CONFLICT: 'conflict',
  TREE_DIFFERS: 'tree-differs',
});

/** Config forced onto every simulation. See the header for what each one defends against. */
const MERGE_CONTROL_ARGS = Object.freeze(['-c', 'merge.default=', '-c', 'merge.renormalize=false']);

/** How many CONFLICT lines a reason string carries (enough to identify the path, bounded). */
const MAX_CONFLICT_LINES = 3;

/* ── Injectable runners ─────────────────────────────────────────────────────────────────────── */

/**
 * Default `git` runner. Injectable so tests can drive failure paths without a repository.
 *
 * @param {string[]} args
 * @param {{cwd: string}} options
 * @returns {{status: number|null, stdout: string, stderr: string}}
 */
function defaultGit(args, { cwd }) {
  const r = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
  });
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
}

/**
 * Default `gh` runner — the same lookup `remove-worktree.cjs` already performs
 * (`mergeCommitFromPr`), extended with `headRefOid`. `run-gh.mjs`'s scoop-path resolution cannot
 * be required from CommonJS (it is ESM), so the binary is `gh` on PATH with the same
 * `JUSTSEARCH_GH_BIN` override that wrapper honours first.
 *
 * @param {string[]} args
 * @param {{cwd: string}} options
 * @returns {{status: number|null, stdout: string, stderr: string}}
 */
function defaultGh(args, { cwd }) {
  const bin = process.env.JUSTSEARCH_GH_BIN || 'gh';
  const r = spawnSync(bin, args, { cwd, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
}

/* ── Small git helpers (each returns null rather than throwing) ─────────────────────────────── */

/** Run git, return trimmed stdout on exit 0, else null. */
function gitOut(git, repoRoot, args) {
  try {
    const r = git(args, { cwd: repoRoot });
    if (!r || r.status !== 0) return null;
    return (r.stdout || '').trim();
  } catch {
    return null;
  }
}

/** Resolve a revision to a full SHA, or null. */
function revParse(git, repoRoot, rev) {
  if (!rev) return null;
  const out = gitOut(git, repoRoot, ['rev-parse', '--verify', '--quiet', `${rev}^{commit}`]);
  return out || null;
}

/** Resolve a commit's tree SHA, or null. */
function treeOf(git, repoRoot, commit) {
  if (!commit) return null;
  return gitOut(git, repoRoot, ['rev-parse', '--verify', '--quiet', `${commit}^{tree}`]) || null;
}

/** True/false when git answered, null when it could not (unknown revision, broken repo). */
function isAncestor(git, repoRoot, ancestor, descendant) {
  if (!ancestor || !descendant) return null;
  let r;
  try {
    r = git(['merge-base', '--is-ancestor', ancestor, descendant], { cwd: repoRoot });
  } catch {
    return null;
  }
  if (!r) return null;
  if (r.status === 0) return true;
  if (r.status === 1) return false;
  return null;
}

/** Merge base of two revisions, or null. */
function mergeBase(git, repoRoot, a, b) {
  if (!a || !b) return null;
  return gitOut(git, repoRoot, ['merge-base', a, b]) || null;
}

/**
 * Names of custom merge drivers configured for this repository (config, not attributes).
 * A non-empty list means a `.gitattributes` `merge=<name>` line can bypass the simulation's
 * built-in three-way merge, so the simulation's answer is not trustworthy (header).
 */
function customMergeDrivers({ repoRoot, git = defaultGit }) {
  const out = gitOut(git, repoRoot, ['config', '--get-regexp', '^merge\\..*\\.driver$']);
  if (!out) return [];
  return out
    .split(/\r?\n/)
    .map((line) => line.trim().split(/\s+/)[0])
    .filter(Boolean)
    .map((key) => key.replace(/^merge\./, '').replace(/\.driver$/, ''));
}

/* ── PR lookup ─────────────────────────────────────────────────────────────────────────────── */

/**
 * The merged PR for `branch`, if GitHub knows one.
 *
 * `headRefOid` is what makes the receipt honest: it is the commit the PR actually merged, so a
 * head that moved after the merge (one more local commit) is detected instead of inheriting the
 * PR's verdict. Returns null on ANY failure — no gh, not authenticated, no PR, unparseable JSON —
 * because "I could not ask" and "there is no PR" must both degrade to UNKNOWN, never to a throw
 * in a teardown path.
 *
 * @returns {{number: number, headRefOid: string, squash: string|null}|null}
 */
function lookupMergedPr({ repoRoot, branch, gh = defaultGh }) {
  if (!branch) return null;
  let r;
  try {
    r = gh(
      ['pr', 'list', '--head', branch, '--state', 'merged', '--json', 'number,headRefOid,mergeCommit', '--limit', '1'],
      { cwd: repoRoot },
    );
  } catch {
    return null;
  }
  if (!r || r.status !== 0) return null;
  let rows;
  try {
    rows = JSON.parse(r.stdout || '[]');
  } catch {
    return null;
  }
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const row = rows[0];
  const headRefOid = typeof row?.headRefOid === 'string' ? row.headRefOid : null;
  const number = Number.isInteger(row?.number) ? row.number : null;
  if (!headRefOid || number === null) return null;
  const squash = typeof row?.mergeCommit?.oid === 'string' ? row.mergeCommit.oid : null;
  return { number, headRefOid, squash };
}

/* ── The controlled simulation ─────────────────────────────────────────────────────────────── */

/**
 * Merge `head` into `target` from `base` WITHOUT touching a worktree or the index, and report
 * whether the result is exactly `target`'s tree — i.e. whether `head` adds nothing `target` does
 * not already have.
 *
 * `--write-tree`'s first output line is the resulting tree on success AND the conflicted tree on
 * failure, so equality alone is not a verdict: exit status is part of the contract (git 2.53
 * probe: a conflicted run printed a tree OID that happened to equal the target's).
 *
 * @returns {{ok: boolean, status: number|null, tree: string|null, targetTree: string|null,
 *   equal: boolean, conflicts: string[], stderr: string}}
 */
function simulateCoverage({ repoRoot, base, target, head, git = defaultGit }) {
  const targetTree = treeOf(git, repoRoot, target);
  let r;
  try {
    r = git(
      [...MERGE_CONTROL_ARGS, 'merge-tree', '--write-tree', `--merge-base=${base}`, target, head],
      { cwd: repoRoot },
    );
  } catch (err) {
    return { ok: false, status: null, tree: null, targetTree, equal: false, conflicts: [], stderr: String(err) };
  }
  const stdout = (r?.stdout || '').replace(/\r\n/g, '\n');
  const lines = stdout.split('\n');
  const tree = (lines[0] || '').trim() || null;
  const conflicts = lines.filter((line) => line.includes('CONFLICT')).map((line) => line.trim());
  const ok = r?.status === 0;
  return {
    ok,
    status: r ? r.status : null,
    tree,
    targetTree,
    equal: Boolean(ok && tree && targetTree && tree === targetTree),
    conflicts,
    stderr: (r?.stderr || '').trim(),
  };
}

/** Reason detail for a simulation that did not prove coverage. */
function simulationReason(sim, label) {
  if (sim.conflicts.length > 0) {
    return `${RECEIPT_REASONS.CONFLICT}: ${label} ${sim.conflicts.slice(0, MAX_CONFLICT_LINES).join(' | ')}`;
  }
  if (!sim.ok) {
    const detail = sim.stderr || `merge-tree exited ${sim.status}`;
    return `${RECEIPT_REASONS.GIT_FAILED}: ${label} ${detail}`.trim();
  }
  return `${RECEIPT_REASONS.TREE_DIFFERS}: ${label} result ${sim.tree || 'none'} != ${sim.targetTree || 'none'}`;
}

/* ── The receipt ───────────────────────────────────────────────────────────────────────────── */

/**
 * Compute a landed-ness receipt for one branch head.
 *
 * @param {object} options
 * @param {string} options.repoRoot     repository (or worktree) to ask
 * @param {string} options.head         the branch head (any revision git accepts; resolved here)
 * @param {string} [options.branch]     branch name, for the PR lookup; without it there is no PR
 * @param {string} [options.fork]       recorded `branch.<name>.justsearch-fork` SHA
 * @param {string} [options.anchor]     recorded `branch.<name>.justsearch-anchor` SHA
 * @param {string} [options.target]     comparison target ref (default `origin/main`)
 * @param {Function} [options.git]      injectable git runner
 * @param {Function} [options.gh]       injectable gh runner
 * @param {Function} [options.now]      injectable clock
 * @returns {{head: string, branch: string|null, base: string|null, squash: string|null,
 *   pr: number|null, target: string|null, verdict: string, reason: string, ts: string}}
 */
function computeReceipt({
  repoRoot,
  head,
  branch = null,
  fork = null,
  anchor = null,
  target = DEFAULT_TARGET,
  git = defaultGit,
  gh = defaultGh,
  now = () => new Date(),
}) {
  const ts = now().toISOString();
  const base0 = {
    head: String(head || ''),
    branch: branch || null,
    base: null,
    squash: null,
    pr: null,
    target: null,
    verdict: RECEIPT_VERDICTS.UNKNOWN,
    reason: '',
    ts,
  };

  const headSha = revParse(git, repoRoot, head);
  if (!headSha) {
    return { ...base0, reason: `${RECEIPT_REASONS.HEAD_UNRESOLVABLE}: ${head}` };
  }
  const receipt = { ...base0, head: headSha };
  receipt.target = revParse(git, repoRoot, target);

  // Refuse before simulating in a repository whose merge configuration can fake a clean merge.
  const drivers = customMergeDrivers({ repoRoot, git });
  if (drivers.length > 0) {
    return { ...receipt, reason: `${RECEIPT_REASONS.CUSTOM_MERGE_DRIVER}: ${drivers.join(', ')}` };
  }

  // (1) The PR is accepted only when it merged THIS head.
  const pr = lookupMergedPr({ repoRoot, branch, gh });
  let squash = null;
  let prNote = '';
  if (pr) {
    receipt.pr = pr.number;
    if (pr.headRefOid === headSha) {
      squash = revParse(git, repoRoot, pr.squash);
      receipt.squash = squash;
      if (!squash) prNote = `${RECEIPT_REASONS.NO_MERGED_PR}: PR #${pr.number} has no resolvable merge commit`;
    } else {
      prNote = `${RECEIPT_REASONS.PR_HEAD_MISMATCH}: PR #${pr.number} merged ${pr.headRefOid.slice(0, 12)}, head is ${headSha.slice(0, 12)}`;
    }
  } else {
    prNote = `${RECEIPT_REASONS.NO_MERGED_PR}: ${branch ? `no merged PR for ${branch}` : 'no branch given'}`;
  }

  // (2) Base: the recorded fork when it really is behind the squash, else the merge base with the
  // anchor (or the target). A fork that is NOT an ancestor of the squash describes a different
  // line of history — using it would ask the simulation the wrong question.
  let base = null;
  if (squash && fork && isAncestor(git, repoRoot, fork, squash) === true) {
    base = revParse(git, repoRoot, fork);
  }
  if (!base) base = mergeBase(git, repoRoot, anchor || target, headSha);
  if (!base) {
    return {
      ...receipt,
      reason: `${RECEIPT_REASONS.NO_BASE}: no merge base between ${anchor || target} and ${headSha.slice(0, 12)}`,
    };
  }
  receipt.base = base;

  // (3) With an accepted squash the receipt is HISTORICAL: it is proven against that squash and
  // stays true however the target moves afterwards. No fall-through to the target test — a proven
  // squash that fails its own simulation is a fact about this head, not an invitation to retry.
  if (squash) {
    const sim = simulateCoverage({ repoRoot, base, target: squash, head: headSha, git });
    if (sim.equal) {
      return {
        ...receipt,
        verdict: RECEIPT_VERDICTS.LANDED,
        reason: `${RECEIPT_REASONS.LANDED}: covered by squash ${squash.slice(0, 12)}${pr ? ` (PR #${pr.number})` : ''}`,
      };
    }
    return { ...receipt, reason: simulationReason(sim, `vs squash ${squash.slice(0, 12)}:`) };
  }

  // (4) No accepted squash: is the head redundant against the target as it stands NOW?
  if (!receipt.target) {
    return { ...receipt, reason: `${RECEIPT_REASONS.TARGET_UNRESOLVABLE}: ${target}` };
  }
  const sim = simulateCoverage({ repoRoot, base, target: receipt.target, head: headSha, git });
  if (sim.equal) {
    return {
      ...receipt,
      verdict: RECEIPT_VERDICTS.REDUNDANT_NOW,
      reason: `${RECEIPT_REASONS.REDUNDANT_NOW}: ${target} at ${receipt.target.slice(0, 12)} already contains this head${prNote ? ` (${prNote})` : ''}`,
    };
  }
  const reason = simulationReason(sim, `vs ${target} ${receipt.target.slice(0, 12)}:`);
  return { ...receipt, reason: prNote ? `${reason} (${prNote})` : reason };
}

/* ── NDJSON cache ──────────────────────────────────────────────────────────────────────────── */

/**
 * Read every receipt in an NDJSON file. A missing file is an empty history, and a torn line is
 * skipped rather than fatal: this file is a cache, and a half-written last line must not break a
 * teardown.
 *
 * @param {string} file absolute path (callers resolve `DEFAULT_RECEIPTS_FILE` against repoRoot)
 */
function loadReceipts(file) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch {
    return [];
  }
  const out = [];
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object') out.push(parsed);
    } catch {
      /* torn or foreign line — a cache, not a ledger */
    }
  }
  return out;
}

/** Append one receipt as a single NDJSON line, creating the directory. Returns the receipt. */
function appendReceipt(file, receipt) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.appendFileSync(file, `${JSON.stringify(receipt)}\n`, 'utf8');
  return receipt;
}

/**
 * The newest still-valid receipt for `head`, or null.
 *
 * Validity is reachability, because a verdict can expire: a LANDED receipt is worthless if its
 * squash is no longer reachable from the target (force-push, reverted history, a target that was
 * reset), and a REDUNDANT_NOW receipt is worthless once the target it was measured against is no
 * longer an ancestor of the target today. UNKNOWN is never valid.
 *
 * The caller fetches first if it wants "today's origin/main" (header) — this never talks to the
 * network.
 */
function findValidReceipt({ file, head, repoRoot, target = DEFAULT_TARGET, git = defaultGit }) {
  const candidates = loadReceipts(file)
    .map((receipt, index) => ({ receipt, index }))
    .filter(({ receipt }) => receipt.head === head && receipt.verdict && receipt.verdict !== RECEIPT_VERDICTS.UNKNOWN)
    .sort((a, b) => {
      const ta = Date.parse(a.receipt.ts || '') || 0;
      const tb = Date.parse(b.receipt.ts || '') || 0;
      return tb - ta || b.index - a.index;
    });
  if (candidates.length === 0) return null;
  const targetSha = revParse(git, repoRoot, target);
  if (!targetSha) return null;
  for (const { receipt } of candidates) {
    const anchorSha = receipt.verdict === RECEIPT_VERDICTS.LANDED ? receipt.squash : receipt.target;
    if (!anchorSha) continue;
    if (isAncestor(git, repoRoot, anchorSha, targetSha) === true) return receipt;
  }
  return null;
}

module.exports = {
  RECEIPT_VERDICTS,
  RECEIPT_REASONS,
  DEFAULT_RECEIPTS_FILE,
  DEFAULT_TARGET,
  MERGE_CONTROL_ARGS,
  defaultGit,
  defaultGh,
  customMergeDrivers,
  lookupMergedPr,
  simulateCoverage,
  computeReceipt,
  loadReceipts,
  appendReceipt,
  findValidReceipt,
};
