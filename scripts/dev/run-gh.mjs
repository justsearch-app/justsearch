#!/usr/bin/env node
/**
 * `gh` CLI runner (tempdoc 743 second wave, P-K exec substrate).
 *
 * Two jobs:
 *
 *  1. **Resolved-path invocation.** Scoop shim junctions have been observed unreachable
 *     from an agent Bash session (symptom: `Shim: Could not create process …`), which is
 *     the birthplace of the hand-typed `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe"` quoting
 *     class. This wrapper resolves the scoop-installed binary itself —
 *     via the `current` symlink so it survives a `gh` version bump — and falls back to
 *     plain `gh` on PATH for portability to a machine without this scoop layout. Args are
 *     passed as a vector (`spawnSync(bin, argv, ...)`), never shell-interpolated, so no
 *     quoting trap exists on either path.
 *
 *  2. **`checks-wait` mode** — mechanizes the "registration race" prose guidance tempdoc 746
 *     shipped as a runnable command instead of a hand-rolled poll loop:
 *
 *     `node scripts/dev/run-gh.mjs checks-wait <pr-number> [--timeout-sec N] [--required-only]`
 *
 *     `gh pr checks <pr>` exit-code contract (cli/cli#7866, verified live on gh 2.90.0 —
 *     tempdoc 743 R3 derisk): the exit code is a BITWISE combination —
 *       - bit 0 (1) set  → at least one check is FAILING
 *       - bit 3 (8) set  → at least one check is PENDING (no failures yet)
 *       - 0              → every check reported and none are failing/pending (PASS)
 *     A fail bit is terminal (a failed check does not un-fail while polling), so any code
 *     with bit 0 set is treated as FAIL even if bit 3 is also set.
 *
 *     The overload that causes cli/cli#7401's spurious failure: right after a push, before
 *     CI has registered any checks, `gh pr checks <pr>` ALSO exits 1 with a distinct
 *     "no checks reported" message — indistinguishable from a real failure by exit code
 *     alone. This wrapper pre-polls (every 15s) until the output stops looking like the
 *     no-checks-yet case before it starts trusting the bitwise contract, which is the fix
 *     #7401 itself never shipped.
 *
 *     `--required-only` (tempdoc 829 R1): passes `--required` through to every underlying
 *     `gh pr checks` call, so the bitwise verdict above is computed over ONLY the contexts
 *     registered in the branch protection rule's `required_status_checks.contexts` — verified
 *     working on gh 2.90.0, filters to exactly the required contexts. Advisory (non-required)
 *     lanes cannot change mergeability, so without this flag a flaky advisory lane (e.g.
 *     continue-on-error integration tests) reads as FAIL and triggers a rerun that cannot
 *     possibly matter: 829 F1 found 12/12 lane reruns on 2026-08-13 were unnecessary because
 *     the one failing lane was never in the required set — every attempt-1 run was already
 *     mergeable. The no-checks-yet pre-poll heuristic (`isUnregistered`) covers both invocation
 *     shapes, but `gh` does NOT emit identical text across them (cli/cli pkg/cmd/pr/checks/checks.go,
 *     gh 2.90.0): without `--required` it emits "no checks reported on the '%s' branch" when
 *     `statusCheckRollup.Nodes` is empty; with `--required` it can instead emit "no required checks
 *     reported on the '%s' branch" — checks ARE registered, but zero of them are in the required
 *     set yet (e.g. one required context, like cla-assistant, reporting from a different workflow
 *     run than the rest — a real staggered-registration window, not a hypothetical). Both variants
 *     are exit 1 and both are treated as not-yet-registered by the same regex. The bitwise exit
 *     contract itself is unchanged by the flag — only which checks feed into it. Omitting the flag
 *     is byte-identical to the pre-829 behavior (all reported checks, required and advisory alike).
 *
 *     The same bounded exit contract is used by `merge-wait` and `run-wait-sha`:
 *     0 = success, 1 = terminal failure, 3 = TIMEOUT, 2 = an unexpected `gh`/JSON error.
 *     Both emit only state transitions, so one long-lived process replaces conversational polls.
 */

import path from 'node:path';
import fs from 'node:fs';
import { buildPublicSquashRecord } from '../ci/lib/squash-message-projection.mjs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const POLL_INTERVAL_MS = 15_000;
const MERGE_POLL_INTERVAL_MS = 60_000;
const DEFAULT_TIMEOUT_SEC = 1800;
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PREVIEW_SCRIPT = path.resolve(SCRIPT_DIR, '..', 'ci', 'preview-squash-message.mjs');
const REVIEW_RECORD_SCRIPT = path.resolve(SCRIPT_DIR, '..', 'ci', 'pr-review-record.mjs');

/** Resolve the `gh` binary: scoop's `current` symlink if present, else plain `gh` on PATH. */
export function resolveGhBin(env = process.env) {
  if (env.JUSTSEARCH_GH_BIN) return env.JUSTSEARCH_GH_BIN;
  if (process.platform === 'win32') {
    const scoopRoot = env.SCOOP || 'F:\\scoop';
    const candidate = path.join(scoopRoot, 'apps', 'gh', 'current', 'bin', 'gh.exe');
    try {
      if (fs.existsSync(candidate)) return candidate;
    } catch {
      /* fall through to PATH */
    }
  }
  return 'gh';
}

/** Run `gh` with an argument vector (no shell interpolation), stdio inherited. */
function runGh(bin, args) {
  return spawnSync(bin, args, { stdio: 'inherit' });
}

/** Run `gh` capturing output (for checks-wait's internal polling, not user-facing stdio). */
function runGhCaptured(bin, args) {
  return spawnSync(bin, args, { encoding: 'utf8' });
}

/**
 * True when a `gh pr checks` result looks like "no checks have registered yet" rather than
 * a real failure or a real pass/pending state — the cli/cli#7401 ambiguity. Pure; unit-tested.
 */
export function isUnregistered(result) {
  const text = `${result.stdout || ''}${result.stderr || ''}`;
  if (result.status === 1 && /no (required )?checks reported/i.test(text)) return true;
  // A completely empty result with a non-zero/non-standard status also reads as "not up yet"
  // rather than a decodable bitwise verdict.
  if (!text.trim() && result.status !== 0) return true;
  return false;
}

/**
 * Decode the cli/cli#7866 bitwise exit contract into a verdict. Pure; unit-tested.
 * Returns one of: 'pass' | 'fail' | 'pending' | 'unknown'.
 */
export function decodeChecksExit(status) {
  if (status === 0) return 'pass';
  if (typeof status !== 'number') return 'unknown';
  if ((status & 1) !== 0) return 'fail'; // fail bit wins even if pending bit also set
  if ((status & 8) !== 0) return 'pending';
  return 'unknown';
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Build the `gh pr checks` argument vector. Pure; unit-tested. With `requiredOnly`, appends
 * `--required` so the bitwise verdict only reflects `required_status_checks.contexts` (829 R1).
 */
export function buildChecksArgs(prNumber, requiredOnly) {
  const args = ['pr', 'checks', String(prNumber)];
  if (requiredOnly) args.push('--required');
  return args;
}

export function classifyMergeSnapshot(snapshot) {
  const state = String(snapshot?.state || '').toUpperCase();
  const mergeState = String(snapshot?.mergeStateStatus || 'UNKNOWN').toUpperCase();
  if (state === 'MERGED' || snapshot?.mergedAt) {
    const sha = snapshot?.mergeCommit?.oid || snapshot?.mergeCommit?.sha || 'unknown';
    return { verdict: 'pass', key: `MERGED:${sha}`, message: `merged at ${sha}` };
  }
  if (state === 'CLOSED') return { verdict: 'fail', key: 'CLOSED', message: 'closed without merging' };
  return { verdict: 'pending', key: `${state || 'OPEN'}:${mergeState}`, message: `${state || 'OPEN'} / ${mergeState}` };
}

export function selectWorkflowRun(rows, sha, event = null) {
  return (Array.isArray(rows) ? rows : [])
    .filter((row) => row?.headSha === sha && (!event || row?.event === event))
    .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')))[0] || null;
}

export function classifyWorkflowRun(run) {
  if (!run) return { verdict: 'pending', key: 'UNREGISTERED', message: 'waiting for run registration' };
  const status = String(run.status || '').toLowerCase();
  const conclusion = String(run.conclusion || '').toLowerCase();
  if (status !== 'completed') {
    return { verdict: 'pending', key: `${run.databaseId}:${status}`, message: `run ${run.databaseId} ${status || 'pending'}` };
  }
  if (conclusion === 'success') {
    return { verdict: 'pass', key: `${run.databaseId}:success`, message: `run ${run.databaseId} succeeded` };
  }
  return { verdict: 'fail', key: `${run.databaseId}:${conclusion || 'unknown'}`, message: `run ${run.databaseId} concluded ${conclusion || 'unknown'}` };
}

/** gh versions emit either one merged array or consecutive JSON arrays for --paginate. */
function parsePaginatedArrays(text) {
  const rows = [];
  let start = 0; let depth = 0; let inString = false; let escaped = false; let pages = 0;
  for (let i = 0; i < text.length; i += 1) {
    const char = text[i];
    if (depth === 0) {
      if (/\s/.test(char)) continue;
      if (char !== '[') throw new Error('expected branch rules array');
      start = i;
    }
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
    } else if (char === '"') inString = true;
    else if (char === '[' || char === '{') depth += 1;
    else if (char === ']' || char === '}') {
      depth -= 1;
      if (depth === 0) {
        rows.push(...JSON.parse(text.slice(start, i + 1)));
        pages += 1;
      }
    }
  }
  if (depth || !pages) throw new Error('incomplete branch rules JSON');
  return rows;
}

function capturedJson(bin, args) {
  const result = runGhCaptured(bin, args);
  if (args[0] === 'pr' && args[1] === 'checks') return parseRequiredChecksResult(result);
  if (result.error) return { error: result.error };
  if (result.status !== 0) return { error: new Error(`${result.stderr || result.stdout || `gh exited ${result.status}`}`.trim()), stderr: result.stderr };
  try {
    return { value: args.includes('--paginate') ? parsePaginatedArrays(result.stdout) : JSON.parse(result.stdout) };
  } catch (error) {
    return { error: new Error(`invalid gh JSON: ${error.message}`) };
  }
}

function emitTransition(prefix, classified, previousKey) {
  if (classified.key !== previousKey) process.stderr.write(`${prefix}: ${classified.message}\n`);
  return classified.key;
}

/** Decode required checks by their current JSON rows, never by gh's checks exit bits. */
export function parseRequiredChecksResult(result) {
  if (result.error) return { error: result.error };
  if (typeof result.status !== 'number' || result.signal) {
    return { error: new Error(`gh checks terminated without an exit status${result.signal ? ` (${result.signal})` : ''}`) };
  }
  // isUnregistered also accepts empty failures for checks-wait; merge-wait requires the message.
  const text = `${result.stdout || ''}${result.stderr || ''}`;
  if (text.trim() && isUnregistered(result)) return { value: [] };
  try {
    const value = JSON.parse(result.stdout);
    if (!Array.isArray(value) || value.some((row) => !row ||
        ['name', 'state', 'bucket', 'link'].some((key) => typeof row[key] !== 'string'))) {
      throw new Error('incomplete required-check rows');
    }
    return { value };
  } catch (error) {
    return { error: new Error(`${text.trim() || `gh exited ${result.status}`}: invalid gh checks JSON: ${error.message}`) };
  }
}

export function classifyRequiredChecks(rows) {
  const failures = [];
  let passed = 0;
  for (const row of rows) {
    const state = row.state.toUpperCase();
    if (['fail', 'cancel'].includes(row.bucket) ||
        ['STARTUP_FAILURE', 'ERROR', 'FAILURE', 'TIMED_OUT', 'ACTION_REQUIRED'].includes(state)) {
      failures.push(row);
    } else if (state !== 'STALE' && (row.bucket === 'pass' || ['SUCCESS', 'SKIPPED', 'NEUTRAL'].includes(state))) {
      passed += 1;
    }
  }
  return { reported: rows.length, passed, pending: rows.length - passed - failures.length, failures };
}

function requiredChecksSummary(checks) {
  return checks.reported ? `${checks.reported} reported, ${checks.passed} passed, ${checks.pending} pending` : 'none reported yet';
}

export function classifyMergePoll(before, after, rows, watchedHead, passingForMs = 0, requiredContexts = []) {
  for (const snapshot of [before, after]) {
    const classified = classifyMergeSnapshot(snapshot);
    if (classified.verdict === 'pass') return classified;
  }
  if (before.headRefOid !== after.headRefOid || before.state !== after.state) {
    return { verdict: 'pending', key: 'CHANGED', message: 'PR head or state changed during poll; waiting for a stable snapshot' };
  }
  const classified = classifyMergeSnapshot(after);
  if (classified.verdict === 'fail') return classified;
  if (after.headRefOid !== watchedHead) {
    return { verdict: 'fail', key: 'NEW_HEAD', message: `new head ${after.headRefOid}; enqueue again` };
  }
  if (after.isDraft) return { verdict: 'fail', key: 'DRAFT', message: 'PR is a draft' };
  if (after.mergeStateStatus === 'DIRTY') return { verdict: 'fail', key: 'DIRTY', message: 'PR has merge conflicts (DIRTY)' };
  const checks = classifyRequiredChecks(rows);
  if (checks.failures.length) {
    return { verdict: 'fail', key: 'CHECKS_FAILED', message: `required checks failed: ${checks.failures.map((row) => `${row.name} (${row.state}, ${row.bucket}) ${row.link}`).join('; ')}` };
  }
  if (!after.autoMergeRequest) {
    return { verdict: 'fail', key: 'AUTO_MERGE_OFF', message: 'auto-merge is off; enqueue again after a fix' };
  }
  const allPassed = requiredContexts.length > 0 && checks.passed === checks.reported &&
    requiredContexts.every((context) => rows.some((row) => row.name === context && classifyRequiredChecks([row]).passed === 1));
  if (allPassed && passingForMs >= 600_000) {
    return { verdict: 'fail', key: 'STALLED', message: `merge stalled for 10 minutes with required checks passing; mergeStateStatus ${after.mergeStateStatus}` };
  }
  const checkKey = rows.map((row) => `${row.name}:${row.state}:${row.bucket}`).sort().join(',');
  return { verdict: 'pending', key: `${classified.key}:${checkKey}`, message: `${classified.message}; required checks: ${requiredChecksSummary(checks)}`, allPassed };
}

export async function mergeWait(bin, prNumber, timeoutSec, {
  loadJson = capturedJson,
  now = () => Date.now(),
  pause = sleep,
  emit = emitTransition,
  repo = null,
  head = null,
} = {}) {
  const deadline = now() + timeoutSec * 1000;
  const viewArgs = ['pr', 'view', String(prNumber), '--json',
    'state,mergedAt,mergeCommit,headRefOid,baseRefName,isDraft,mergeStateStatus,autoMergeRequest'];
  const checksArgs = ['pr', 'checks', String(prNumber), '--required', '--json', 'name,state,bucket,link'];
  if (repo) { viewArgs.push('--repo', repo); checksArgs.push('--repo', repo); }
  const readSnapshot = () => {
    const loaded = loadJson(bin, viewArgs);
    if (loaded.error) return loaded;
    const snapshot = loaded.value;
    if (!snapshot || !['OPEN', 'CLOSED', 'MERGED'].includes(snapshot.state) ||
        typeof snapshot.headRefOid !== 'string' || !snapshot.headRefOid ||
        typeof snapshot.baseRefName !== 'string' || !snapshot.baseRefName ||
        typeof snapshot.isDraft !== 'boolean' || typeof snapshot.mergeStateStatus !== 'string' ||
        !Object.hasOwn(snapshot, 'autoMergeRequest')) {
      return { error: new Error('invalid gh JSON: incomplete PR snapshot') };
    }
    return loaded;
  };
  let previousKey = null;
  let watchedHead = head?.toLowerCase() ?? null;
  let requiredContexts = null;
  let passingSince = null;
  for (;;) {
    const before = readSnapshot();
    if (before.error) {
      process.stderr.write(`run-gh merge-wait: ${before.error.message}\n`);
      return 2;
    }
    if (watchedHead === null) watchedHead = before.value.headRefOid;
    if (requiredContexts === null) {
      const base = encodeURIComponent(before.value.baseRefName);
      const repository = `repos/${repo || '{owner}/{repo}'}`;
      const classic = loadJson(bin, ['api', `${repository}/branches/${base}/protection/required_status_checks`]);
      const rules = loadJson(bin, ['api', `${repository}/rules/branches/${base}`, '--paginate']);
      // gh reports API failures as "gh: <message> (HTTP <status>)" on stderr.
      const classicStatus = classic.error ? /\bHTTP (403|404)\b/.exec(classic.stderr || '')?.[1] : null;
      const unprotected = classicStatus === '404' && /Branch not protected/i.test(classic.stderr);
      const unknown = classicStatus && !unprotected && !/rate limit|abuse detection/i.test(classic.stderr);
      try {
        if (classic.error && !unprotected && !unknown) throw classic.error;
        if (rules.error) throw rules.error;
        const contexts = [];
        if (!classic.error) {
          const protection = classic.value;
          if (!Array.isArray(protection?.contexts) || !Array.isArray(protection?.checks) ||
              protection.contexts.some((context) => typeof context !== 'string' || !context) ||
              protection.checks.some((check) => typeof check?.context !== 'string' || !check.context)) {
            throw new Error('invalid gh JSON: incomplete classic required status checks');
          }
          contexts.push(...protection.contexts, ...protection.checks.map((check) => check.context));
        }
        if (!Array.isArray(rules.value) || rules.value.some((rule) => typeof rule?.type !== 'string' || !rule.type)) {
          throw new Error('invalid gh JSON: incomplete branch rules');
        }
        for (const rule of rules.value.filter((rule) => rule.type === 'required_status_checks')) {
          const checks = rule.parameters?.required_status_checks;
          if (!Array.isArray(checks) || checks.some((check) => typeof check?.context !== 'string' || !check.context)) {
            throw new Error('invalid gh JSON: incomplete required status check contexts');
          }
          contexts.push(...checks.map((check) => check.context));
        }
        requiredContexts = unknown ? [] : [...new Set(contexts)];
        const reason = unknown ? 'classic required checks unknown: protection cannot be read' :
          !requiredContexts.length ? 'branch protection and rulesets returned no required contexts' : null;
        if (reason) process.stderr.write(`run-gh merge-wait: stall detection disabled: ${reason}; timeout still applies\n`);
      } catch (error) {
        process.stderr.write(`run-gh merge-wait: ${error.message}\n`);
        return 2;
      }
    }
    const checks = loadJson(bin, checksArgs);
    const after = readSnapshot();
    let classified = classifyMergeSnapshot(before.value);
    if (classified.verdict !== 'pass') {
      if (after.error) {
        process.stderr.write(`run-gh merge-wait: ${after.error.message}\n`);
        return 2;
      }
      classified = classifyMergeSnapshot(after.value);
      if (classified.verdict !== 'pass') {
        if (checks.error) {
          process.stderr.write(`run-gh merge-wait: ${checks.error.message}\n`);
          return 2;
        }
        classified = classifyMergePoll(before.value, after.value, checks.value, watchedHead, 0, requiredContexts);
        if (classified.allPassed) {
          if (passingSince === null) passingSince = now();
          classified = classifyMergePoll(before.value, after.value, checks.value, watchedHead, now() - passingSince, requiredContexts);
        } else {
          passingSince = null;
        }
      }
    }
    previousKey = emit(`run-gh merge-wait PR #${prNumber}`, classified, previousKey);
    if (classified.verdict === 'pass') return 0;
    if (classified.verdict === 'fail') return 1;
    if (now() >= deadline) {
      const missing = requiredContexts.filter((context) => !checks.value.some((row) => row.name === context));
      process.stderr.write(`run-gh merge-wait: TIMEOUT after ${timeoutSec}s; required checks: ${requiredChecksSummary(classifyRequiredChecks(checks.value))}${missing.length ? `; not reported yet: ${missing.join(', ')}` : ''}\n`);
      return 3;
    }
    await pause(MERGE_POLL_INTERVAL_MS);
  }
}

export async function runWaitSha(bin, sha, timeoutSec, { workflow = 'CI', branch = 'main', event = 'push' } = {}, {
  loadJson = capturedJson,
  now = () => Date.now(),
  pause = sleep,
  emit = emitTransition,
} = {}) {
  const deadline = now() + timeoutSec * 1000;
  let previousKey = null;
  for (;;) {
    const args = ['run', 'list', '--workflow', workflow, '--limit', '100', '--json',
      'databaseId,status,conclusion,headSha,event,createdAt,url'];
    if (branch) args.push('--branch', branch);
    if (event) args.push('--event', event);
    const loaded = loadJson(bin, args);
    if (loaded.error) {
      process.stderr.write(`run-gh run-wait-sha: ${loaded.error.message}\n`);
      return 2;
    }
    let selected = selectWorkflowRun(loaded.value, sha, event);
    let covering = false;
    if ((!selected || selected.conclusion === 'cancelled') && branch === 'main' && event === 'push') {
      const candidates = (Array.isArray(loaded.value) ? loaded.value : [])
        .filter((row) => row.event === 'push' && row.headSha !== sha && row.conclusion !== 'cancelled'
          && (!selected || String(row.createdAt) >= String(selected.createdAt)))
        .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
      for (const candidate of candidates) {
        const comparison = loadJson(bin, ['api', `repos/{owner}/{repo}/compare/${sha}...${candidate.headSha}`]);
        if (comparison.error) {
          process.stderr.write(`run-gh run-wait-sha: ${comparison.error.message}\n`);
          return 2;
        }
        if (['ahead', 'identical'].includes(comparison.value?.status)) {
          selected = candidate;
          covering = true;
          break;
        }
      }
    }
    const classified = classifyWorkflowRun(selected);
    if (covering) classified.message = `following covering run ${selected.databaseId} (${selected.headSha}) for ${sha}: ${classified.message}`;
    previousKey = emit(`run-gh run-wait-sha ${sha.slice(0, 12)}`, classified, previousKey);
    if (classified.verdict === 'pass') return 0;
    if (classified.verdict === 'fail') return 1;
    if (now() >= deadline) {
      process.stderr.write(`run-gh run-wait-sha: TIMEOUT after ${timeoutSec}s\n`);
      return 3;
    }
    await pause(POLL_INTERVAL_MS);
  }
}

async function checksWait(bin, prNumber, timeoutSec, requiredOnly) {
  const deadline = Date.now() + timeoutSec * 1000;
  const checksArgs = buildChecksArgs(prNumber, requiredOnly);

  // Phase 1: pre-poll until checks register (cli/cli#7401 mitigation).
  let last = runGhCaptured(bin, checksArgs);
  if (last.error) {
    // Spawn failure (ENOENT etc.) must fail fast and legibly — never poll a binary that
    // isn't there until timeout and then report a wrong-cause TIMEOUT (refute-first review
    // finding 2a, 743 second wave).
    process.stderr.write(`run-gh checks-wait: failed to spawn \`${bin}\`: ${last.error.message}\n`);
    return 2;
  }
  while (isUnregistered(last)) {
    if (Date.now() >= deadline) {
      process.stderr.write(
        `run-gh checks-wait: TIMEOUT waiting for PR #${prNumber} checks to register after ${timeoutSec}s\n`,
      );
      return 3;
    }
    await sleep(POLL_INTERVAL_MS);
    last = runGhCaptured(bin, checksArgs);
    if (last.error) {
      process.stderr.write(`run-gh checks-wait: failed to spawn \`${bin}\`: ${last.error.message}\n`);
      return 2;
    }
  }

  // Phase 2: trust the bitwise contract until it resolves to pass/fail.
  for (;;) {
    const verdict = decodeChecksExit(last.status);
    if (verdict === 'pass') {
      process.stderr.write(`run-gh checks-wait: PASS — PR #${prNumber} all checks green\n`);
      return 0;
    }
    if (verdict === 'fail') {
      process.stderr.write(`run-gh checks-wait: FAIL — PR #${prNumber} has a failing check\n`);
      return 1;
    }
    if (verdict === 'pending') {
      if (Date.now() >= deadline) {
        process.stderr.write(
          `run-gh checks-wait: TIMEOUT — PR #${prNumber} still pending after ${timeoutSec}s\n`,
        );
        return 3;
      }
      await sleep(POLL_INTERVAL_MS);
      last = runGhCaptured(bin, checksArgs);
      continue;
    }
    // 'unknown': an unexpected gh error (auth, network, ...) — surface verbatim, don't loop forever.
    process.stderr.write(
      `run-gh checks-wait: unexpected \`gh pr checks\` result (exit ${last.status}) — surfacing verbatim:\n${last.stdout || ''}${last.stderr || ''}\n`,
    );
    return 2;
  }
}

/** Extract the `--timeout-sec N` flag. Pure; unit-tested. */
export function parseTimeoutSec(args) {
  const i = args.indexOf('--timeout-sec');
  if (i === -1) return { timeoutSec: DEFAULT_TIMEOUT_SEC, rest: args };
  const value = Number(args[i + 1]);
  const hasValidValue = Number.isFinite(value) && value > 0;
  // Only consume the next token if it actually parses as a positive number — otherwise it's
  // a different flag (or nothing), and eating it would silently drop that argument.
  const rest = hasValidValue
    ? [...args.slice(0, i), ...args.slice(i + 2)]
    : [...args.slice(0, i), ...args.slice(i + 1)];
  return { timeoutSec: hasValidValue ? value : DEFAULT_TIMEOUT_SEC, rest };
}

/** Extract the boolean `--required-only` flag (829 R1). Pure; unit-tested. */
export function parseRequiredOnly(args) {
  const i = args.indexOf('--required-only');
  if (i === -1) return { requiredOnly: false, rest: args };
  const rest = [...args.slice(0, i), ...args.slice(i + 1)];
  return { requiredOnly: true, rest };
}

export function parseValueFlag(args, name, fallback = null) {
  const index = args.indexOf(name);
  if (index === -1) return { value: fallback, rest: args };
  const candidate = args[index + 1];
  if (!candidate || candidate.startsWith('--')) return { value: fallback, rest: [...args.slice(0, index), ...args.slice(index + 1)] };
  return { value: candidate, rest: [...args.slice(0, index), ...args.slice(index + 2)] };
}

export function parseEnqueueArgs(args) {
  const prToken = args[0];
  if (typeof prToken !== 'string' || !/^[1-9]\d*$/.test(prToken)) {
    throw new Error('enqueue requires a positive <pr-number>.');
  }
  const prNumber = Number(prToken);
  if (!Number.isSafeInteger(prNumber)) throw new Error('enqueue <pr-number> exceeds JavaScript safe-integer range.');
  let repo = null;
  for (let index = 1; index < args.length; index += 1) {
    if (args[index] === '--repo' && args[index + 1] && !args[index + 1].startsWith('--') && repo == null) {
      repo = args[++index];
      continue;
    }
    throw new Error(`enqueue accepts only <pr-number> and optional --repo owner/repo; received ${JSON.stringify(args[index])}.`);
  }
  if (repo != null && !/^[^/\s]+\/[^/\s]+$/.test(repo)) throw new Error('enqueue --repo must be owner/repo.');
  return { prNumber, repo };
}

function childFailure(label, result, writeError) {
  if (result?.error) {
    writeError(`run-gh enqueue: ${label} failed to spawn: ${result.error.message}\n`);
    return 2;
  }
  if (result?.signal) {
    writeError(`run-gh enqueue: ${label} terminated by ${result.signal}.\n`);
    return 2;
  }
  if (!Number.isInteger(result?.status)) {
    writeError(`run-gh enqueue: ${label} returned no exit status.\n`);
    return 2;
  }
  if (result.status !== 0) {
    writeError(`run-gh enqueue: ${label} refused publication (exit ${result.status}).\n`);
    return result.status;
  }
  return 0;
}

export function enqueuePullRequest(bin, prNumber, repo = null, {
  run = spawnSync,
  nodeBin = process.execPath,
  previewScript = PREVIEW_SCRIPT,
  reviewRecordScript = REVIEW_RECORD_SCRIPT,
  writeError = (message) => process.stderr.write(message),
} = {}) {
  const readSnapshot = () => {
    const args = ['pr', 'view', String(prNumber), '--json', 'headRefOid,title,body,isDraft,mergeable'];
    if (repo) args.push('--repo', repo);
    const response = run(bin, args, { encoding: 'utf8', windowsHide: true });
    const failure = childFailure('PR snapshot', response, writeError);
    if (failure) return { failure };
    try {
      const value = JSON.parse(response.stdout);
      if (!/^[a-f0-9]{40}$/.test(value.headRefOid) || typeof value.title !== 'string'
          || typeof value.body !== 'string' || typeof value.isDraft !== 'boolean'
          || !['MERGEABLE', 'CONFLICTING', 'UNKNOWN'].includes(value.mergeable)) throw new Error('incomplete PR snapshot');
      if (value.isDraft || value.mergeable !== 'MERGEABLE') throw new Error(value.isDraft ? 'PR is a draft' : `PR mergeability is ${value.mergeable}`);
      return { value };
    } catch (error) {
      writeError(`run-gh enqueue: ${error.message}\n`);
      return { failure: 2 };
    }
  };
  const before = readSnapshot();
  if (before.failure) return before.failure;
  const commonArgs = ['--pr', String(prNumber)];
  if (repo) commonArgs.push('--repo', repo);
  const steps = [
    ['squash preview', nodeBin, [previewScript, ...commonArgs]],
    ['managed review-record check', nodeBin, [reviewRecordScript, 'check', ...commonArgs]],
  ];
  for (const [label, command, args] of steps) {
    const result = run(command, args, { stdio: 'inherit', windowsHide: true });
    const failure = childFailure(label, result, writeError);
    if (failure !== 0) return failure;
  }

  const after = readSnapshot();
  if (after.failure) return after.failure;
  if (['headRefOid', 'title', 'body', 'isDraft', 'mergeable'].some((key) => before.value[key] !== after.value[key])) {
    writeError('run-gh enqueue: PR changed during validation; enqueue again.\n');
    return 2;
  }
  // Use the preview's projection, including GitHub's PR-number suffix and LF body.
  const preview = buildPublicSquashRecord({ pr: { ...after.value, number: prNumber } });
  const mergeArgs = ['pr', 'merge', String(prNumber), '--squash', '--auto',
    '--match-head-commit', after.value.headRefOid, '--subject', preview.expectedLandedSubject, '--body', preview.body];
  if (repo) mergeArgs.push('--repo', repo);
  const result = run(bin, mergeArgs, { stdio: 'inherit', windowsHide: true });
  const failure = childFailure('squash auto-merge request', result, writeError);
  if (failure === 0) {
    writeError(`run-gh enqueue: run node scripts/dev/run-gh.mjs merge-wait ${prNumber}${repo ? ` --repo ${repo}` : ''} --head ${after.value.headRefOid} --timeout-sec 5400 in the background.\n`);
  }
  return failure;
}

async function main() {
  const argv = process.argv.slice(2);
  const bin = resolveGhBin();

  if (argv[0] === 'checks-wait') {
    const { timeoutSec, rest: afterTimeout } = parseTimeoutSec(argv.slice(1));
    const { requiredOnly, rest } = parseRequiredOnly(afterTimeout);
    const prNumber = rest[0];
    if (!prNumber) {
      process.stderr.write('run-gh checks-wait: missing <pr-number>\n');
      process.exit(2);
    }
    const code = await checksWait(bin, prNumber, timeoutSec, requiredOnly);
    process.exit(code);
  }

  if (argv[0] === 'merge-wait') {
    const { timeoutSec, rest } = parseTimeoutSec(argv.slice(1));
    const repoFlag = parseValueFlag(rest, '--repo');
    const headFlag = parseValueFlag(repoFlag.rest, '--head');
    if (rest.includes('--head') && (!/^[a-f0-9]{40}$/i.test(headFlag.value ?? '') || headFlag.rest.includes('--head'))) {
      process.stderr.write('run-gh merge-wait: --head must be a 40-hex SHA\n');
      process.exit(2);
    }
    const prNumber = headFlag.rest[0];
    if (!prNumber) {
      process.stderr.write('run-gh merge-wait: missing <pr-number>\n');
      process.exit(2);
    }
    process.exit(await mergeWait(bin, prNumber, timeoutSec, { repo: repoFlag.value, head: headFlag.value }));
  }

  if (argv[0] === 'run-wait-sha') {
    const { timeoutSec, rest: afterTimeout } = parseTimeoutSec(argv.slice(1));
    const workflowFlag = parseValueFlag(afterTimeout, '--workflow', 'CI');
    const branchFlag = parseValueFlag(workflowFlag.rest, '--branch', 'main');
    const eventFlag = parseValueFlag(branchFlag.rest, '--event', 'push');
    const sha = eventFlag.rest[0];
    if (!sha) {
      process.stderr.write('run-gh run-wait-sha: missing <sha>\n');
      process.exit(2);
    }
    process.exit(await runWaitSha(bin, sha, timeoutSec, {
      workflow: workflowFlag.value,
      branch: branchFlag.value,
      event: eventFlag.value,
    }));
  }

  if (argv[0] === 'enqueue') {
    try {
      const { prNumber, repo } = parseEnqueueArgs(argv.slice(1));
      process.exit(enqueuePullRequest(bin, prNumber, repo));
    } catch (error) {
      process.stderr.write(`run-gh enqueue: ${error.message}\n`);
      process.exit(2);
    }
  }

  const result = runGh(bin, argv);
  if (result.error) {
    // Mirror run-py.mjs's spawn-error guard: with stdio 'inherit', ENOENT is otherwise a
    // silent exit 1 with zero output (refute-first review finding 2b, 743 second wave).
    process.stderr.write(`run-gh: failed to spawn \`${bin}\`: ${result.error.message}\n`);
    process.exit(127);
  }
  process.exit(result.status ?? 1);
}

function isDirectRun() {
  return !!process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
}

if (isDirectRun()) {
  main();
}
