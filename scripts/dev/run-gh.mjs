#!/usr/bin/env node
/**
 * `gh` CLI runner (tempdoc 743 second wave, P-K exec substrate).
 *
 * Two jobs:
 *
 *  1. **Resolved-path invocation.** Scoop shim junctions have been observed unreachable
 *     from an agent Bash session (symptom: `Shim: Could not create process …`), which is
 *     the birthplace of the hand-typed `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe"` quoting
 *     class (agent-lessons.md). This wrapper resolves the scoop-installed binary itself —
 *     via the `current` symlink so it survives a `gh` version bump — and falls back to
 *     plain `gh` on PATH for portability to a machine without this scoop layout. Args are
 *     passed as a vector (`spawnSync(bin, argv, ...)`), never shell-interpolated, so no
 *     quoting trap exists on either path.
 *
 *  2. **`checks-wait` mode** — mechanizes the prose guidance tempdoc 746 shipped
 *     (`.claude/skills/publish/SKILL.md` "Registration race" bullet) as a runnable command
 *     instead of a hand-rolled poll loop:
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
 *     A second, narrower registration gap (tempdoc 872 §6, observed twice on PR #571): the
 *     rollup can be non-empty and fully green while the `CI` workflow (`.github/workflows/ci.yml`
 *     `name: CI`) itself has not started — e.g. only `cla-assistant` (workflow `CLA Assistant`,
 *     a separate GitHub App check) has registered so far. `isUnregistered` correctly calls that
 *     "registered" (it is a real, non-empty rollup), so the bitwise contract resolves to PASS
 *     over a rollup that just happens to contain nothing but a passing CLA check — reported as
 *     "all checks green" while `gh run list` still shows the `CI` run `pending`. The pre-poll
 *     therefore ALSO requires a `gh pr checks <pr> --json name,workflow,state` row whose
 *     `workflow` field equals `CI` (verified live on gh 2.90.0, PR #571: CI job rows carry
 *     `"workflow":"CI"`, cla-assistant carries `"workflow":"CLA Assistant"` — the JSON `workflow`
 *     field, not `name`, is what distinguishes them) before it will leave phase 1, regardless of
 *     how the plain-text bitwise exit reads. Once a `CI` row exists, phase 2's bitwise contract
 *     is trusted as before — a real `pending` CI run flips the pending bit, so no separate
 *     "is CI still running" check is needed past this point.
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
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const POLL_INTERVAL_MS = 15_000;
const DEFAULT_TIMEOUT_SEC = 1800;
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PREVIEW_SCRIPT = path.resolve(SCRIPT_DIR, '..', 'ci', 'preview-squash-message.mjs');
const REVIEW_RECORD_SCRIPT = path.resolve(SCRIPT_DIR, '..', 'ci', 'pr-review-record.mjs');
// Matches the `name:` field in .github/workflows/ci.yml — verified live (gh pr checks --json
// workflow, PR #571) that individual CI job rows carry exactly this value, distinct from other
// rollup entries such as cla-assistant's `"workflow":"CLA Assistant"` (tempdoc 872 §6).
const CI_WORKFLOW_NAME = 'CI';

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

/**
 * Build the `gh pr checks <pr> --json name,workflow,state` argument vector used to confirm
 * the CI workflow ITSELF (not just any check) has a row in the rollup (tempdoc 872 §6). Pure;
 * unit-tested. Mirrors `buildChecksArgs`'s `--required` gating so the registration check is
 * computed over the same filtered/unfiltered rollup the caller asked for.
 */
export function buildChecksJsonArgs(prNumber, requiredOnly) {
  const args = ['pr', 'checks', String(prNumber), '--json', 'name,workflow,state'];
  if (requiredOnly) args.push('--required');
  return args;
}

/**
 * True when at least one row of a `gh pr checks --json` rollup belongs to the given workflow
 * (default: the `CI` workflow). Pure; unit-tested. This is the fix for tempdoc 872 §6: a
 * non-empty, fully-passing rollup containing only a non-CI check (e.g. cla-assistant) is NOT
 * "CI is green" — it means CI hasn't registered yet.
 */
export function hasWorkflowRegistered(rows, workflowName = CI_WORKFLOW_NAME) {
  return Array.isArray(rows) && rows.some((row) => String(row?.workflow || '') === workflowName);
}

/**
 * Parse a `gh pr checks --json` capture into rows, or null if the rollup was empty/unparseable.
 * Verified live (gh 2.90.0): an unregistered rollup exits 1 with the SAME plain-text
 * "no checks reported" error as the non-JSON invocation, even with `--json` set — there is no
 * JSON error envelope to parse, so a `JSON.parse` failure here is the expected not-yet-registered
 * case, not a surprise.
 */
function parseChecksJson(result) {
  try {
    const rows = JSON.parse(result.stdout);
    return Array.isArray(rows) ? rows : null;
  } catch {
    return null;
  }
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

function capturedJson(bin, args) {
  const result = runGhCaptured(bin, args);
  if (result.error) return { error: result.error };
  if (result.status !== 0) return { error: new Error(`${result.stderr || result.stdout || `gh exited ${result.status}`}`.trim()) };
  try {
    return { value: JSON.parse(result.stdout) };
  } catch (error) {
    return { error: new Error(`invalid gh JSON: ${error.message}`) };
  }
}

function emitTransition(prefix, classified, previousKey) {
  if (classified.key !== previousKey) process.stderr.write(`${prefix}: ${classified.message}\n`);
  return classified.key;
}

export async function mergeWait(bin, prNumber, timeoutSec, {
  loadJson = capturedJson,
  now = () => Date.now(),
  pause = sleep,
  emit = emitTransition,
} = {}) {
  const deadline = now() + timeoutSec * 1000;
  let previousKey = null;
  for (;;) {
    const loaded = loadJson(bin, ['pr', 'view', String(prNumber), '--json', 'state,mergedAt,mergeCommit,mergeStateStatus,url']);
    if (loaded.error) {
      process.stderr.write(`run-gh merge-wait: ${loaded.error.message}\n`);
      return 2;
    }
    const classified = classifyMergeSnapshot(loaded.value);
    previousKey = emit(`run-gh merge-wait PR #${prNumber}`, classified, previousKey);
    if (classified.verdict === 'pass') return 0;
    if (classified.verdict === 'fail') return 1;
    if (now() >= deadline) {
      process.stderr.write(`run-gh merge-wait: TIMEOUT after ${timeoutSec}s\n`);
      return 3;
    }
    await pause(POLL_INTERVAL_MS);
  }
}

export async function runWaitSha(bin, sha, timeoutSec, { workflow = 'CI', branch = null, event = null } = {}, {
  loadJson = capturedJson,
  now = () => Date.now(),
  pause = sleep,
  emit = emitTransition,
} = {}) {
  const deadline = now() + timeoutSec * 1000;
  let previousKey = null;
  for (;;) {
    const args = ['run', 'list', '--workflow', workflow, '--commit', sha, '--limit', '20', '--json',
      'databaseId,status,conclusion,headSha,event,createdAt,url'];
    if (branch) args.push('--branch', branch);
    if (event) args.push('--event', event);
    const loaded = loadJson(bin, args);
    if (loaded.error) {
      process.stderr.write(`run-gh run-wait-sha: ${loaded.error.message}\n`);
      return 2;
    }
    const classified = classifyWorkflowRun(selectWorkflowRun(loaded.value, sha, event));
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

/**
 * `checks-wait`'s core loop. Pure enough to test: `runCmd`/`now`/`pause` are injectable
 * (default to the real `gh` spawn / wall clock / real sleep), matching the DI pattern already
 * used by `mergeWait`/`runWaitSha` above. Exported so a fixture-driven test can exercise the
 * exact control flow the tempdoc 872 §6 bug lived in, not just the pure helper it calls.
 */
export async function checksWait(bin, prNumber, timeoutSec, requiredOnly, {
  runCmd = runGhCaptured,
  now = () => Date.now(),
  pause = sleep,
} = {}) {
  const deadline = now() + timeoutSec * 1000;
  const checksArgs = buildChecksArgs(prNumber, requiredOnly);
  const checksJsonArgs = buildChecksJsonArgs(prNumber, requiredOnly);

  // True while phase 1 should keep pre-polling: either the plain-text rollup still looks
  // unregistered (cli/cli#7401), OR it's non-empty but the `CI` workflow itself has no row in
  // it yet (tempdoc 872 §6 — a lone non-CI check, e.g. cla-assistant, is not "CI is green").
  const needsMoreRegistration = (result) => {
    if (isUnregistered(result)) return true;
    return !hasWorkflowRegistered(parseChecksJson(runCmd(bin, checksJsonArgs)));
  };

  // Phase 1: pre-poll until checks register AND the CI workflow itself has registered.
  let last = runCmd(bin, checksArgs);
  if (last.error) {
    // Spawn failure (ENOENT etc.) must fail fast and legibly — never poll a binary that
    // isn't there until timeout and then report a wrong-cause TIMEOUT (refute-first review
    // finding 2a, 743 second wave).
    process.stderr.write(`run-gh checks-wait: failed to spawn \`${bin}\`: ${last.error.message}\n`);
    return 2;
  }
  while (needsMoreRegistration(last)) {
    if (now() >= deadline) {
      process.stderr.write(
        `run-gh checks-wait: TIMEOUT waiting for PR #${prNumber}'s ${CI_WORKFLOW_NAME} checks to register after ${timeoutSec}s\n`,
      );
      return 3;
    }
    await pause(POLL_INTERVAL_MS);
    last = runCmd(bin, checksArgs);
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
      if (now() >= deadline) {
        process.stderr.write(
          `run-gh checks-wait: TIMEOUT — PR #${prNumber} still pending after ${timeoutSec}s\n`,
        );
        return 3;
      }
      await pause(POLL_INTERVAL_MS);
      last = runCmd(bin, checksArgs);
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

  const mergeArgs = ['pr', 'merge', String(prNumber)];
  if (repo) mergeArgs.push('--repo', repo);
  const result = run(bin, mergeArgs, { stdio: 'inherit', windowsHide: true });
  return childFailure('merge-queue request', result, writeError);
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
    const prNumber = rest[0];
    if (!prNumber) {
      process.stderr.write('run-gh merge-wait: missing <pr-number>\n');
      process.exit(2);
    }
    process.exit(await mergeWait(bin, prNumber, timeoutSec));
  }

  if (argv[0] === 'run-wait-sha') {
    const { timeoutSec, rest: afterTimeout } = parseTimeoutSec(argv.slice(1));
    const workflowFlag = parseValueFlag(afterTimeout, '--workflow', 'CI');
    const branchFlag = parseValueFlag(workflowFlag.rest, '--branch');
    const eventFlag = parseValueFlag(branchFlag.rest, '--event');
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
