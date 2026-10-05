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
 * dead file. `resolveSessionId` / `resolveAgentLabel` stay exported - record-merge.mjs
 * uses them - which is why the file survives.
 *
 *   node scripts/agent-analytics/note-observation.mjs "<description>"   # -> routing table, exit 2
 */

import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { repoRoot } from './lib/telemetry-io.mjs';

const require = createRequire(import.meta.url);
const { resolveAgentIdentity, sanitizeSessionId } = require('../dev/lib/agent-identity.cjs');

/**
 * The calling agent session's own label, or null. One identity rule for the whole repository
 * (`scripts/dev/lib/agent-identity.cjs`): an explicit override, else the caller's nearest harness
 * process (Claude Code or Codex), whose OWN variable is the label - `CLAUDE_CODE_SESSION_ID` for
 * Claude, `CODEX_THREAD_ID` for Codex. A Codex session started from a Claude shell therefore names
 * itself, not the Claude session whose variable it inherited. Never the retired shared pointer
 * file (it handed every session the same stale id) and never `JUSTSEARCH_AGENT_SESSION_ID`.
 * A subagent's shell runs under its parent session's harness, so it is attributed to the parent -
 * the desired behaviour. Sanitised by the shared rule (`^[A-Za-z0-9._-]{4,80}$`).
 * `opts` are the resolver's test seams (`env`, `readTable`, `selfPid`).
 */
export function resolveAgentLabel({ env = process.env, explicit = null, ...opts } = {}) {
  return resolveAgentIdentity({ env, explicit, ...opts }).sessionId ?? null;
}

/**
 * Resolve the current session id: the agent label (above), else a short hash of the worktree
 * toplevel (stable per checkout, never empty), else 'unknown'. Merge attribution
 * (record-merge.mjs) uses `resolveAgentLabel` and skips when there is no label.
 */
export function resolveSessionId({ root = repoRoot, env = process.env, ...opts } = {}) {
  const label = resolveAgentLabel({ env, ...opts });
  if (label) return label;
  try {
    const top = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: root, encoding: 'utf8' }).trim();
    return 'wt-' + createHash('sha1').update(top).digest('hex').slice(0, 12);
  } catch {
    return 'unknown';
  }
}

export { sanitizeSessionId };

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
    '  platform/process lesson (must/never)      -> a check; otherwise agent-system project knowledge',
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
