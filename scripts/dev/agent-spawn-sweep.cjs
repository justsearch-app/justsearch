#!/usr/bin/env node
/**
 * Tempdoc 861 W5 — the runnable sweep the session-closeout skill's checklist step names.
 *
 * The `session-closeout` occasion (861 §6.4: "the skill gains a step that runs the sweep and
 * reports") is EXECUTE-capability, so it is meant to be run under direct human/agent
 * supervision and reported, not fired silently from a hook. This is that runnable command —
 * the same `runAgentSpawnSweep` assembly the SessionStart and SessionEnd hooks use, exposed as
 * a CLI so the skill's step is "run this and report" rather than a second implementation.
 *
 * Usage:
 *   node scripts/dev/agent-spawn-sweep.cjs [--occasion session-closeout] [--session-id <id>]
 *
 * `--occasion` accepts any EXECUTE-capability occasion (`session-start`, `session-end`,
 * `session-closeout`); it defaults to `session-closeout` since that is this CLI's primary
 * caller. Passing `--own-session-only` narrows the register read to the caller's own records
 * before evaluation, mirroring the SessionEnd hook's scope (861 §6.4: "this session's own
 * spawns") — omitted by default, so a manual run performs the full abandonment sweep.
 */
'use strict';

const path = require('node:path');
const {
  resolveMainRepoRoot,
  resolveCallerIdentity,
  runAgentSpawnSweep,
  describeEntry,
} = require('./lib/agent-spawn-sweep.cjs');

function flagValue(argv, name) {
  const i = argv.indexOf(`--${name}`);
  if (i !== -1 && argv[i + 1] && !argv[i + 1].startsWith('--')) return argv[i + 1].trim();
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(`--${name}=`.length).trim() : null;
}

function printBucket(label, entries) {
  if (!entries.length) return;
  console.log(`\n${label} (${entries.length}):`);
  for (const e of entries) console.log(`  ${describeEntry(e).replace(/\n/g, '\n  ')}`);
}

async function main() {
  const argv = process.argv.slice(2);
  const occasion = flagValue(argv, 'occasion') || 'session-closeout';
  const ownSessionOnly = argv.includes('--own-session-only');

  const repoRoot = path.resolve(__dirname, '..', '..');
  const mainRepoRoot = resolveMainRepoRoot(repoRoot);
  // [F-3] One identity rule (`resolveCallerIdentity` -> agent-identity.cjs), shared with
  // `remove-worktree.cjs` so both consumers recognise the calling session the same way.
  const callerIdentity = resolveCallerIdentity({ explicit: flagValue(argv, 'session-id'), env: process.env });

  const result = await runAgentSpawnSweep({
    occasion,
    mainRepoRoot,
    callerIdentity,
    ownSessionOnly,
    actorSource: 'agent-spawn-sweep-cli',
    // [F-1] The CLI always prunes, regardless of occasion: "run this and it cleans up" is this
    // command's entire purpose, and `session-closeout` (the default occasion here) does not
    // itself default to pruning the way `session-start` does.
    prune: true,
  });

  console.log(`[agent-spawn-sweep] occasion=${occasion} register=${result.dir}`);
  if (result.pruned) {
    console.log(`[agent-spawn-sweep] pruned: ${result.pruned.deleted} deleted (of ${result.pruned.found} found), ${result.pruned.retained} retained, ${result.pruned.deletedTmp} stale .tmp file(s) swept`);
  }
  printBucket('REAPED (attempted)', result.buckets.reap);
  printBucket('CONTENTION (left alone)', result.buckets.contention);
  printBucket('REFUSED (retained, marked)', result.buckets.refuse);
  printBucket('REPORTED (never reaped)', result.buckets.report);

  if (result.kills.length) {
    console.log(`\nKill outcomes:`);
    for (const k of result.kills) {
      console.log(`  recordId=${k.recordId} pid=${k.pid ?? '?'} killed=${k.killed} confirmed=${k.confirmed} refused=${k.refused}${k.reason ? ` reason=${k.reason}` : ''}`);
    }
  }
  if (result.marked.marked.length || result.marked.failed.length) {
    console.log(`\nMarked failed-verify: ${result.marked.marked.length} (failed to mark: ${result.marked.failed.length})`);
  }
  if (!result.buckets.all.length) {
    console.log('\nnothing registered — register is empty or unreadable.');
  }
}

if (require.main === module) {
  main().catch((err) => {
    console.error(`[agent-spawn-sweep] ERROR: ${err && err.message ? err.message : err}`);
    process.exit(0); // best-effort reporting tool — never block the session on a sweep failure
  });
}

module.exports = { main };
