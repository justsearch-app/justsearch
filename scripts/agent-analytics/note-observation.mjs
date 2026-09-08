#!/usr/bin/env node

/**
 * note-observation — the retired inbox writer, kept as a ROUTER (tempdoc 872).
 *
 * Until 872 this appended an out-of-scope finding to a per-session shard under
 * `docs/observations.d/`, which `fold-observations.mjs` merged into the
 * `docs/observations.md` conditions store for a periodic triage pass. Tempdoc 680
 * pre-registered the failure condition for that design — "two consecutive months
 * of read-model output go unconsumed" — and it fired: 565 conditions, 517 with
 * their kind never confirmed, one probe, and the top "recurrence" turned out to be
 * 23 distinct findings sharing a file anchor. A pile nobody reads is not memory;
 * it is a place where a fix goes to not happen.
 *
 * The replacement is routing AT DISCOVERY. This command no longer writes anything:
 * invoked, it prints the destination table and exits non-zero, so an agent (or a
 * subagent still carrying the old brief) is redirected instead of silently fed a
 * dead file. `resolveSessionId` stays exported — record-merge.mjs and
 * preview-squash-message.mjs share it — which is why the file survives.
 *
 *   node scripts/agent-analytics/note-observation.mjs "<description>"   # -> routing table, exit 2
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { TELEMETRY_DIR, repoRoot } from './lib/telemetry-io.mjs';

/** Make a session id safe as a filename component. Feeds resolveSessionId, hence the ledger. */
function sanitizeId(id) {
  return String(id).trim().replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80) || 'unknown';
}

/**
 * Resolve the current session id. ENV-FIRST (tempdoc 684):
 *   1. $CLAUDE_CODE_SESSION_ID                 (harness-native — safest primary)
 *   2. $JUSTSEARCH_AGENT_SESSION_ID            (repo export)
 *   3. tmp/agent-telemetry/current-session-id  (export-session-env.mjs, cross-platform)
 *   4. short hash of the worktree toplevel     (stable per checkout, never empty)
 *
 * The pointer file (#3) records whatever session last STARTED in that checkout —
 * in the shared main checkout that is routinely a FOREIGN session's id, so it
 * must not win over env. Env vars are always the calling process's own identity,
 * including in a subagent-spawned shell (the child inherits the PARENT session's
 * env, and attributing to the parent is the desired behaviour there too).
 */
export function resolveSessionId({ root = repoRoot, env = process.env } = {}) {
  if (env.CLAUDE_CODE_SESSION_ID) return sanitizeId(env.CLAUDE_CODE_SESSION_ID);
  if (env.JUSTSEARCH_AGENT_SESSION_ID) return sanitizeId(env.JUSTSEARCH_AGENT_SESSION_ID);
  try {
    const fromFile = fs.readFileSync(path.join(root, TELEMETRY_DIR, 'current-session-id'), 'utf8').trim();
    if (fromFile) return sanitizeId(fromFile);
  } catch { /* fall through */ }
  try {
    const top = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: root, encoding: 'utf8' }).trim();
    return 'wt-' + createHash('sha1').update(top).digest('hex').slice(0, 12);
  } catch {
    return 'unknown';
  }
}

/** Today's date as YYYY-MM-DD (local). */
export function today(d = new Date()) {
  return d.toISOString().slice(0, 10);
}

/**
 * The routing table an agent sees instead of a "logged to …" line. Pure; the test
 * seam. Mirrors `rule:log-pre-existing-issues` in the canonical agent-workflow.md — that rule is the
 * authority; this is its delivery at the moment an agent reaches for the old habit.
 */
export function renderRouting(description) {
  return [
    'note-observation: the observations inbox is RETIRED (tempdoc 872) — nothing was written.',
    'Route the finding to where it is acted on, at discovery:',
    '  wrong doc/comment, verified one-line fix  -> fix it in place (ride-along in this PR)',
    '  red/flaky verification command on main    -> fix it, or quarantine the flaky test in its own runner',
    '                                               + the fix as a tracked item; main being red is a defect',
    '  platform/process lesson (must/never)      -> a hook; otherwise .claude/rules/agent-lessons.md',
    '  product defect you will not fix now       -> the owning tempdoc\'s open-items section / domain register',
    '  scheduled work                            -> the tempdoc, never a note',
    description ? `Your text: ${description}` : '',
  ].filter(Boolean).join('\n');
}

function main() {
  const description = process.argv.slice(2).join(' ').trim();
  console.error(renderRouting(description));
  process.exit(2);
}

// CLI entry only when run directly (not when imported).
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  main();
}
