#!/usr/bin/env node
/**
 * ensure-ci-on-sha — guarantee a CI run exists for the EXACT commit you are about to rely on.
 *
 * GitHub's `synchronize` event is not a promise. Repeatedly across this repo's history a push has
 * landed and no workflow run appeared for the new head — leaving a PR whose newest green check
 * belongs to an OLDER commit. That is the dangerous shape, because the PR page still reads green:
 * the evidence is real, it is just evidence about a different tree. `gh pr checks` cannot see the
 * difference, since it reports on whatever runs exist.
 *
 * So this asks the only question that matters — "is there a run whose headSha equals THIS sha?" —
 * and, if there is not, dispatches one and confirms the dispatched run actually carries that sha.
 *
 * Usage:
 *   node scripts/ci/ensure-ci-on-sha.mjs [<branch>] [options]
 *
 *   <branch>              branch to check (default: the current branch; required when HEAD is
 *                         detached). Its LOCAL head sha is the subject; pass --remote to use the
 *                         pushed head instead. The branch is ALWAYS needed — `gh run list` and
 *                         `gh workflow run` are both branch-scoped — so --sha narrows which sha
 *                         must be found, it does not replace the branch.
 *   --sha <sha>           the sha a run must carry, instead of resolving one from the branch head.
 *   --workflow <file>     workflow to look for / dispatch (default: ci.yml).
 *   --wait-sec <n>        how long to wait for an existing run to appear before dispatching
 *                         (default: 90). A run usually registers within ~30s of a push; this window
 *                         is what keeps the tool from dispatching a duplicate of a run that was
 *                         merely slow.
 *   --confirm-sec <n>     how long to wait for a DISPATCHED run to appear (default: 120).
 *   --no-dispatch         report only; never dispatch. Exit 1 if no run exists.
 *   --remote              resolve the branch head from `origin` instead of the local ref.
 *   --json                machine-readable result on stdout.
 *
 * Idempotent: if a run already exists for the sha, it dispatches nothing and exits 0. Running it
 * twice in a row is a no-op the second time.
 *
 * Exit codes: 0 = a run exists for the sha (pre-existing or newly dispatched and confirmed);
 *             1 = no run and none could be created/confirmed; 2 = usage or `gh` error.
 *
 * To wait for a PR's CI run to finish, use `node scripts/dev/run-gh.mjs run-wait-sha <head-sha>
 * --workflow CI --branch <pr-branch> --event pull_request`. To merge, use `shepherd <n>`
 * (or `enqueue` then `merge-wait`) through the same runner. Waiting is only meaningful once
 * checks exist for the right commit; a dispatched run needs `--event workflow_dispatch` instead.
 */
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { resolveGhBin } from '../dev/run-gh.mjs';

const DEFAULT_WORKFLOW = 'ci.yml';
const DEFAULT_WAIT_SEC = 90;
const DEFAULT_CONFIRM_SEC = 120;
const POLL_INTERVAL_MS = 10_000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Parse argv into options. Pure; unit-testable. */
export function parseArgs(argv) {
  const opts = {
    branch: null,
    sha: null,
    workflow: DEFAULT_WORKFLOW,
    waitSec: DEFAULT_WAIT_SEC,
    confirmSec: DEFAULT_CONFIRM_SEC,
    dispatch: true,
    remote: false,
    json: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--sha') opts.sha = argv[++i] ?? null;
    else if (a === '--workflow') opts.workflow = argv[++i] ?? DEFAULT_WORKFLOW;
    else if (a === '--wait-sec') opts.waitSec = Number(argv[++i]);
    else if (a === '--confirm-sec') opts.confirmSec = Number(argv[++i]);
    else if (a === '--no-dispatch') opts.dispatch = false;
    else if (a === '--remote') opts.remote = true;
    else if (a === '--json') opts.json = true;
    else if (a.startsWith('--')) throw new Error(`unknown option: ${a}`);
    else if (opts.branch === null) opts.branch = a;
    else throw new Error(`unexpected argument: ${a}`);
  }
  for (const [k, v] of [['waitSec', opts.waitSec], ['confirmSec', opts.confirmSec]]) {
    if (!Number.isFinite(v) || v < 0) throw new Error(`--${k === 'waitSec' ? 'wait-sec' : 'confirm-sec'} must be a non-negative number`);
  }
  return opts;
}

/**
 * Pick the run for `sha` out of `gh run list --json` output, newest first.
 * Pure; unit-tested — this is the whole judgment the tool makes.
 */
export function findRunForSha(runs, sha) {
  if (!sha) return null;
  return (runs || []).find((r) => r.headSha === sha) ?? null;
}

/** Short sha for logging, without pretending it is the identity. */
const short = (sha) => (sha ? sha.slice(0, 8) : '?');

function git(args) {
  const r = spawnSync('git', args, { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

function gh(bin, args) {
  const r = spawnSync(bin, args, { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error(`gh ${args.join(' ')} failed (exit ${r.status}): ${(r.stderr || '').trim()}`);
  }
  return (r.stdout || '').trim();
}

function listRuns(bin, workflow, branch) {
  const out = gh(bin, [
    'run', 'list',
    '--workflow', workflow,
    '--branch', branch,
    '--limit', '30',
    '--json', 'databaseId,headSha,status,conclusion,event,url,createdAt',
  ]);
  try {
    return JSON.parse(out || '[]');
  } catch {
    throw new Error(`could not parse \`gh run list\` output as JSON: ${out.slice(0, 200)}`);
  }
}

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`ensure-ci-on-sha: ${e.message}`);
    process.exit(2);
  }

  const log = (msg) => {
    if (!opts.json) console.log(msg);
  };
  const finish = (result) => {
    if (opts.json) console.log(JSON.stringify(result, null, 2));
    process.exit(result.exitCode);
  };

  const bin = resolveGhBin();
  let branch = opts.branch;
  let sha = opts.sha;

  try {
    if (!branch) {
      branch = git(['rev-parse', '--abbrev-ref', 'HEAD']);
      // Detached HEAD makes `rev-parse --abbrev-ref` answer the literal string "HEAD", which would
      // go to `gh run list --branch HEAD`, match nothing, and read as "no CI ran" — a false alarm
      // that this tool exists to be trusted against. Name the real problem instead.
      if (branch === 'HEAD') {
        throw new Error(
          'HEAD is detached, so there is no branch for gh to list runs on or dispatch against. ' +
            'Pass a branch explicitly: ensure-ci-on-sha.mjs <branch> [--sha <sha>]',
        );
      }
    }
    if (!sha) {
      // The REMOTE head is what CI actually builds, so --remote is the honest default for a pushed
      // branch; the local head is kept as the default because it is what the caller just built and
      // is about to push, and a mismatch between the two is itself worth surfacing.
      sha = opts.remote ? git(['rev-parse', `origin/${branch}`]) : git(['rev-parse', 'HEAD']);
      if (!opts.remote) {
        let remoteSha = null;
        try {
          remoteSha = git(['rev-parse', `origin/${branch}`]);
        } catch {
          /* no remote tracking ref yet — the push has not happened; the warning below covers it */
        }
        if (remoteSha && remoteSha !== sha) {
          log(
            `ensure-ci-on-sha: WARNING local HEAD ${short(sha)} != origin/${branch} ${short(remoteSha)} — ` +
              `CI can only run what is pushed. Push first, or pass --remote.`,
          );
        }
      }
    }
  } catch (e) {
    console.error(`ensure-ci-on-sha: ${e.message}`);
    process.exit(2);
  }

  log(`ensure-ci-on-sha: workflow=${opts.workflow} branch=${branch} sha=${short(sha)}`);

  const lookFor = async (budgetSec, what) => {
    const deadline = Date.now() + budgetSec * 1000;
    for (;;) {
      const runs = listRuns(bin, opts.workflow, branch);
      const hit = findRunForSha(runs, sha);
      if (hit) return hit;
      if (Date.now() >= deadline) return null;
      log(`  no ${what} for ${short(sha)} yet — polling (${Math.round((deadline - Date.now()) / 1000)}s left)`);
      await sleep(POLL_INTERVAL_MS);
    }
  };

  let run;
  try {
    run = await lookFor(opts.waitSec, 'run');
  } catch (e) {
    console.error(`ensure-ci-on-sha: ${e.message}`);
    process.exit(2);
  }

  if (run) {
    log(`ensure-ci-on-sha: OK — run ${run.databaseId} (${run.event}, ${run.status}${run.conclusion ? `/${run.conclusion}` : ''}) is on ${short(sha)}`);
    log(`  ${run.url}`);
    finish({ ok: true, dispatched: false, sha, branch, workflow: opts.workflow, run, exitCode: 0 });
  }

  if (!opts.dispatch) {
    console.error(`ensure-ci-on-sha: no ${opts.workflow} run for ${short(sha)} on ${branch} (--no-dispatch)`);
    finish({ ok: false, dispatched: false, sha, branch, workflow: opts.workflow, run: null, exitCode: 1 });
  }

  log(`ensure-ci-on-sha: no run for ${short(sha)} — dispatching ${opts.workflow} on ${branch}`);
  try {
    gh(bin, ['workflow', 'run', opts.workflow, '--ref', branch]);
  } catch (e) {
    console.error(`ensure-ci-on-sha: dispatch failed — ${e.message}`);
    finish({ ok: false, dispatched: false, sha, branch, workflow: opts.workflow, run: null, exitCode: 2 });
  }

  // Confirm rather than assume: `gh workflow run` returns before the run object exists, and a
  // workflow_dispatch against a moving branch ref can resolve to a DIFFERENT sha than the one asked
  // about. An unconfirmed dispatch would put this tool in the same class as the bug it exists for.
  let confirmed;
  try {
    confirmed = await lookFor(opts.confirmSec, 'dispatched run');
  } catch (e) {
    console.error(`ensure-ci-on-sha: ${e.message}`);
    finish({ ok: false, dispatched: true, sha, branch, workflow: opts.workflow, run: null, exitCode: 2 });
  }

  if (!confirmed) {
    console.error(
      `ensure-ci-on-sha: dispatched ${opts.workflow} on ${branch} but no run carrying ${short(sha)} ` +
        `appeared within ${opts.confirmSec}s. The branch head may have moved, or the workflow may not ` +
        `accept workflow_dispatch. Check: gh run list --workflow ${opts.workflow} --branch ${branch}`,
    );
    finish({ ok: false, dispatched: true, sha, branch, workflow: opts.workflow, run: null, exitCode: 1 });
  }

  log(`ensure-ci-on-sha: OK — dispatched run ${confirmed.databaseId} (${confirmed.event}) confirmed on ${short(sha)}`);
  log(`  ${confirmed.url}`);
  finish({ ok: true, dispatched: true, sha, branch, workflow: opts.workflow, run: confirmed, exitCode: 0 });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(`ensure-ci-on-sha: ${e?.stack || e}`);
    process.exit(2);
  });
}
