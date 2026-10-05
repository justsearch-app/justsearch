#!/usr/bin/env node

/**
 * Record a session -> merge-commit link (tempdoc 622 Layer B keying prerequisite,
 * §11 U2). Appends one line to tmp/agent-telemetry/session-merges.ndjson so the
 * outcome join can attribute a merge commit to the session that produced it —
 * the weak link that git history itself does not carry (merge messages cite
 * tempdoc numbers, not session ids).
 *
 * Run at merge time (scripts/dev/remove-worktree.cjs runs it at worktree teardown):
 *   node scripts/agent-analytics/record-merge.mjs                       # links HEAD merge
 *   node scripts/agent-analytics/record-merge.mjs <commit>              # links a specific commit
 *   node scripts/agent-analytics/record-merge.mjs <commit> --session-id <id>  # escape hatch
 *   node scripts/agent-analytics/record-merge.mjs <commit> --source publish    # provenance
 *
 * Tempdoc 856 §3.1: every row carries `source` (which writer observed the link)
 * and `kind` ('fact' for anything observed, 'inference' for shard-derived
 * recovery). `--source` defaults to 'teardown', so remove-worktree.cjs's
 * existing invocation is unchanged. Rows written before 856 have neither field
 * and mean exactly that default — readers backfill via normalizeMergeLinkRow;
 * the ledger is never rewritten.
 *
 * The session id is the CALLING session's own label, by the repository's one
 * identity rule (note-observation.mjs's resolveAgentLabel -> scripts/dev/lib/
 * agent-identity.cjs): the nearest harness process (Claude Code or Codex) names
 * the session and its own variable is the label. With no label the link is
 * skipped - never a borrowed, stale or worktree-hash id. `--session-id` is an
 * escape hatch for headless/cron contexts; it is sanitised by the same rule, and
 * every recorded id matches ^[A-Za-z0-9._-]{4,80}$.
 *
 * Read-only w.r.t. git (rev-parse + log); append-only telemetry. Non-destructive.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  TELEMETRY_DIR, COSTS_FILE, SESSION_MERGES_FILE, repoRoot,
  MERGE_LINK_SOURCES, DEFAULT_MERGE_LINK_SOURCE, buildMergeLinkRow,
} from './lib/telemetry-io.mjs';
import { atomicWriteFileSync } from './lib/hook-base.mjs';
import { resolveAgentLabel, sanitizeSessionId } from './note-observation.mjs';
import { findSessionTranscript, computeSessionCost, DEFAULT_PROJECTS_ROOT } from './baseline-economics.mjs';

const MERGES_FILE = SESSION_MERGES_FILE;
const VALID_SOURCES = new Set(Object.values(MERGE_LINK_SOURCES));

/**
 * Adapt a computeSessionCost() record (baseline-economics.mjs) to the
 * existing costs.ndjson row shape (cost-session.mjs's costSession() output),
 * so both writers append rows a downstream reader can treat uniformly. Adds a
 * few extra fields (namespaced, non-colliding) that only the transcript-first
 * path can supply: the orchestrator/worker split and unknown-model tokens.
 */
export function costRecordFromSessionCost(rec) {
  return {
    ts: new Date().toISOString(),
    session_id: rec.session_id,
    total_cost_usd: rec.total_cost_usd,
    tokens: rec.total_tokens,
    model: rec.main.model,
    turns: rec.main.turns + rec.subagents.turns,
    subagent_transcripts_found: rec.subagents.found,
    subagent_transcripts_missing: rec.subagents.missing,
    reason: null,
    source: 'record-merge-baseline-economics',
    orchestrator_tokens_total: rec.orchestrator_tokens_total,
    worker_tokens_total: rec.worker_tokens_total,
    unknown_model_tokens: rec.unknown_model_tokens,
  };
}

/**
 * Upsert one record into costs.ndjson by session_id (create-or-replace, same
 * read-modify-write pattern as cost-session.mjs's upsertCost, but using
 * atomicWriteFileSync for the write half). record-merge runs at every merge —
 * concurrent teardowns across parallel worktrees can call this for different
 * sessions at close to the same instant, and a plain writeFileSync mid-write
 * crash would truncate the whole file. atomicWriteFileSync's write-temp+rename
 * makes a torn/truncated costs.ndjson impossible: a reader always sees either
 * the complete pre-write file or the complete post-write file.
 *
 * Residual (accepted, tempdoc 743 Finding 3): the read-modify-write is still
 * not a single atomic transaction — two truly simultaneous upserts can both
 * read the same pre-write snapshot, and the later rename wins, silently
 * dropping the earlier writer's row. This is last-writer-wins on a snapshot,
 * not corruption: costs.ndjson stays well-formed NDJSON either way, and a
 * dropped row self-heals the next time that session's own merge re-runs this
 * upsert (or a manual cost-session.mjs re-cost). True read-modify-write
 * atomicity (e.g. a lockfile) is not worth it for best-effort telemetry.
 */
export function upsertCostRecord(record, { root = repoRoot } = {}) {
  const costsPath = path.join(root, TELEMETRY_DIR, COSTS_FILE);
  fs.mkdirSync(path.dirname(costsPath), { recursive: true });
  let existing = [];
  try {
    existing = fs.readFileSync(costsPath, 'utf8')
      .split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
  } catch { /* start fresh */ }
  const idx = existing.findIndex((r) => r.session_id === record.session_id);
  if (idx !== -1) existing[idx] = record;
  else existing.push(record);
  const content = existing.map((r) => JSON.stringify(r)).join('\n') + '\n';
  atomicWriteFileSync(costsPath, content);
}

/**
 * Best-effort: cost the session that just produced a merge (transcript-first,
 * via baseline-economics.mjs — NOT the events store, tempdoc 743's survival
 * requirement: telemetry only survives if a workflow moment re-runs it) and
 * upsert into costs.ndjson. Never throws — a missing/unparseable transcript
 * must not block merge recording.
 */
export function bestEffortUpsertCost(sessionId, { projectsRoot = DEFAULT_PROJECTS_ROOT, root = repoRoot } = {}) {
  try {
    const found = findSessionTranscript(sessionId, projectsRoot);
    if (!found) return { ok: false, reason: 'transcript_not_found' };
    const rec = computeSessionCost({
      sessionId: found.sessionId, projectDir: found.projectDir,
      mainPath: found.mainPath, subagentPaths: found.subagentPaths, startTs: null,
    });
    const costRecord = costRecordFromSessionCost(rec);
    upsertCostRecord(costRecord, { root });
    return { ok: true, costRecord };
  } catch (e) {
    return { ok: false, reason: e.message };
  }
}

function git(args) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
}

export function parseArgs(argv) {
  let commitArg = null;
  let sessionIdArg = null;
  let sourceArg = null;
  // A flag whose value is missing (or is the next flag) must ERROR rather than
  // fall through to the default: `--source` with a typo'd value silently
  // recording 'teardown' is a mislabelled provenance row, which is worse than
  // a failed command.
  const valueFor = (flag, raw) => {
    if (raw === undefined || String(raw).startsWith('--')) throw new Error(`${flag} needs a value`);
    return raw;
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--session-id') {
      sessionIdArg = valueFor('--session-id', argv[i + 1]);
      i += 1;
    } else if (arg.startsWith('--session-id=')) {
      sessionIdArg = valueFor('--session-id', arg.slice('--session-id='.length) || undefined);
    } else if (arg === '--source') {
      sourceArg = valueFor('--source', argv[i + 1]);
      i += 1;
    } else if (arg.startsWith('--source=')) {
      sourceArg = valueFor('--source', arg.slice('--source='.length) || undefined);
    } else if (commitArg === null) {
      commitArg = arg;
    }
  }
  return {
    commitArg: commitArg || 'HEAD',
    sessionIdArg,
    source: sourceArg ? sourceArg.trim() : DEFAULT_MERGE_LINK_SOURCE,
  };
}

function main() {
  let parsed;
  try {
    parsed = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`record-merge: ${e.message}`);
    process.exit(2);
  }
  const { commitArg, sessionIdArg, source } = parsed;
  if (!VALID_SOURCES.has(source)) {
    console.error(`record-merge: unknown --source '${source}' (expected one of ${[...VALID_SOURCES].join(', ')})`);
    process.exit(2);
  }
  let hash, subject;
  try {
    hash = git(['rev-parse', commitArg]);
    subject = git(['log', '-1', '--format=%s', hash]);
  } catch (e) {
    console.error(`record-merge: cannot resolve commit ${commitArg}: ${e.message}`);
    process.exit(1);
  }

  const sessionId = sessionIdArg ? sanitizeSessionId(sessionIdArg) : resolveAgentLabel();
  if (!sessionId || sessionId === 'unknown') {
    console.error('record-merge: no session id resolvable; link skipped (merge not attributed).');
    process.exit(0); // non-fatal: never block a merge over telemetry
  }

  const record = buildMergeLinkRow({ sessionId, mergeCommit: hash, subject, source });
  const file = path.join(repoRoot, TELEMETRY_DIR, MERGES_FILE);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.appendFileSync(file, JSON.stringify(record) + '\n', 'utf8');
  console.log(`record-merge: linked session ${sessionId.slice(0, 8)} -> ${hash.slice(0, 8)} [${record.source}/${record.kind}] (${subject.slice(0, 60)})`);

  // Best-effort cost upsert (tempdoc 743 survival requirement: re-run at a workflow
  // moment, not a one-off audit). Never allowed to fail the merge recording above.
  try {
    const costResult = bestEffortUpsertCost(sessionId);
    if (costResult.ok) {
      console.log(`record-merge: costed session ${sessionId.slice(0, 8)} -> $${costResult.costRecord.total_cost_usd?.toFixed(4) ?? 'n/a'}`);
    } else {
      console.error(`record-merge: cost upsert skipped (${costResult.reason})`);
    }
  } catch (e) {
    console.error(`record-merge: cost upsert failed non-fatally: ${e.message}`);
  }
}

// CLI entry only when run directly (not when imported by the test — tempdoc 743
// requires bestEffortUpsertCost/upsertCostRecord to be unit-testable against a
// temp dir without triggering a real git rev-parse + telemetry append on import).
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
