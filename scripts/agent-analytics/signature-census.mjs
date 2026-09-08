#!/usr/bin/env node
/**
 * Tempdoc 743 second wave, Slice 3 — P-L: mechanical error-signature census.
 *
 * Extension of the alive 727 friction-mining pass (mine-friction.mjs —
 * untouched by this file): that miner judges WHOLE SESSIONS via an LLM;
 * this is the cheap, mechanical complement that scans
 * every session in a window for a small SEEDED table of known recurring
 * error signatures (743 Finding A: "the same error signatures recur in
 * every session with no feedback loop") and counts them — no LLM call.
 *
 * Semi-automatic by design (743 P-L): this script only PROPOSES counts. A
 * human/agent session DISPOSES each signature whose count clears the
 * ratchet threshold as exactly one of root-fix / fire-time hint / explicit
 * wontfix — see scripts/agent-analytics/README.md "Signature census (743
 * P-L)". Census output must NEVER land in always-loaded prose (Lane C,
 * self-poisoning risk; the always-loaded-budget ratchet is the guard).
 *
 * Reporting tool: always exits 0, even on a partial/failed scan — a broken
 * census must never block a mining pass or CI. Errors are reported inline
 * in the output, not via exit code.
 *
 * Usage:
 *   node signature-census.mjs [--since 2026-07-13] [--until <date>] [--json]
 *
 * Output: writes tmp/agent-telemetry/signature-census.json and
 * tmp/agent-telemetry/signature-census.md, and prints the markdown table to
 * stdout (or the raw JSON, with --json).
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  listSessions, listSubagentPaths, iterateTurns, dedupeAdjacentUserTurns,
  DEFAULT_PROJECTS_ROOT,
} from './lib/transcript-store.mjs';
import { TELEMETRY_DIR, repoRoot } from './lib/telemetry-io.mjs';

const DEFAULT_WINDOW_DAYS = 28;
const SAMPLE_MAX_CHARS = 200;
const SAMPLES_PER_SIGNATURE = 2;
const UNMATCHED_PREFIX_LEN = 40;
const UNMATCHED_TOP_N = 15;

// --- Seeded signature table (743 Finding A + Design P-K/P-L, 2026-07-17) --
//
// Ordered list: the FIRST regex that matches wins (a text matching two
// signatures is intentionally not double-counted — e.g. a quoting-EOF error
// inside a `gh`-adjacent command still counts once). `pavedPath` names the
// P-K/P-L remedy this signature is evidence for, or null when none exists
// yet (candidate for a NEW disposition at the next census-review session).
export const SIGNATURES = [
  {
    id: 'ps-call-operator-in-bash',
    description: 'PowerShell call-operator syntax (`& "path\\to\\exe"`) pasted into a POSIX bash tool call — the birthplace of the `&`/quoting class (743 P-K).',
    regex: /(^|[\s])&\s*"[^"\n]+"|unexpected token `&'/,
    pavedPath: 'P-K gh/exec-substrate runner owns resolved-path invocation so agents never hand-type quoted scoop paths (tempdoc 743 P-K).',
  },
  {
    id: 'cp1252-encode',
    description: "Windows cp1252 console/file encoding crashing on non-ASCII from inline Python (UnicodeEncodeError / 'charmap' codec).",
    regex: /UnicodeEncodeError|charmap['\s]{0,4}codec/i,
    pavedPath: 'P-K interpreter runner: scoped PYTHONIOENCODING=utf-8 (tempdoc 743 P-K; R4 CONFIRMED LIVE on this box).',
  },
  {
    id: 'quoting-eof',
    description: 'Unbalanced quoting in a hand-typed shell command (heredoc/quote mismatch) surfacing as "unexpected EOF while looking for matching".',
    regex: /unexpected EOF while looking for matching/i,
    pavedPath: 'P-K interpreter/exec runner: argument-vector passing eliminates inline-quoting traps (tempdoc 743 P-K).',
  },
  {
    id: 'gh-pending-exit',
    description: '`gh` CLI exit-code semantics misread as failure (e.g. `gh pr checks` exits 1/8 while checks are still pending or have in fact passed).',
    regex: /gh (pr|run) checks\b|exit status 8\b|Some checks (were|are) not (yet )?(complete|successful)|no checks reported/i,
    pavedPath: 'P-K gh runner encodes the documented 0/1/8 bitwise exit contract + post-push check-registration pre-poll (tempdoc 743 P-K; R3 partially live-confirmed).',
  },
  {
    id: 'schema-not-loaded',
    description: 'A deferred MCP/tool schema called before ToolSearch loaded it — InputValidationError naming a tool "not in the discovered-tool set".',
    regex: /InputValidationError[\s\S]{0,300}not in the discovered-tool set|not in the discovered-tool set/i,
    pavedPath: null,
  },
  {
    id: 'path-not-found-dialect',
    description: 'Path-dialect mismatch: `/tmp` used on Windows (vs. the scratchpad convention), or a `cd` into a path that does not exist on this shell.',
    regex: /\/tmp\/\S*:?\s*(No such file or directory|cannot access)|cd:\s*.*:\s*No such file or directory/i,
    pavedPath: 'Scratchpad-dir convention (per-session, injected in the system prompt) already exists; this signature measures hand-rolled `/tmp` usage against it.',
  },
  {
    id: 'edit-not-read',
    description: 'Edit tool called on a file that was not Read first, or was modified since the last Read (cross-root worktree/main-checkout copy confusion is one cause).',
    regex: /has not been read yet|has (not been read|been modified) since/i,
    pavedPath: 'The `edit-reread-cross-root` case in docs/reference/contributing/agent-postmortems.md §31 is the remedy: re-read the exact worktree-qualified path before editing, not "a" copy of the same basename.',
  },
];

// --- CLI args --------------------------------------------------------------

function parseArgs(argv) {
  const opts = { since: null, until: null, json: false, projectsRoot: DEFAULT_PROJECTS_ROOT };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--json') opts.json = true;
    else if (a === '--projects-root') opts.projectsRoot = argv[++i];
  }
  if (!opts.since) {
    opts.since = new Date(Date.now() - DEFAULT_WINDOW_DAYS * 24 * 3600 * 1000).toISOString().slice(0, 10);
  }
  return opts;
}

// --- Text normalization ----------------------------------------------------

function normalize(text) {
  return String(text || '').replace(/\s+/g, ' ').trim();
}

function sample(text) {
  const n = normalize(text);
  return n.length > SAMPLE_MAX_CHARS ? n.slice(0, SAMPLE_MAX_CHARS) + '…' : n;
}

export function classify(text) {
  for (const sig of SIGNATURES) {
    if (sig.regex.test(text)) return sig.id;
  }
  return null;
}

// --- Aggregation state -------------------------------------------------

function emptySignatureBucket(sig) {
  return {
    id: sig.id,
    description: sig.description,
    pavedPath: sig.pavedPath,
    count: 0,
    sessions: new Set(),
    firstSeen: null,
    lastSeen: null,
    samples: [],
  };
}

function record(bucket, { sessionId, timestamp, text }) {
  bucket.count += 1;
  bucket.sessions.add(sessionId);
  if (timestamp) {
    if (!bucket.firstSeen || timestamp < bucket.firstSeen) bucket.firstSeen = timestamp;
    if (!bucket.lastSeen || timestamp > bucket.lastSeen) bucket.lastSeen = timestamp;
  }
  if (bucket.samples.length < SAMPLES_PER_SIGNATURE) {
    const s = sample(text);
    if (s && !bucket.samples.includes(s)) bucket.samples.push(s);
  }
}

/**
 * Scan every error-shaped text surface in one transcript file: tool_result
 * blocks flagged `is_error`, PLUS a few assistant-visible error shapes (the
 * agent's own text sometimes quotes/describes an error inline without a
 * formal tool_result, e.g. summarizing a build failure) — both count toward
 * the same signature table, since either is evidence the signature fired in
 * this session.
 */
function scanFile(filePath, sessionId, buckets, unmatchedClusters, { skipSidechain = false } = {}) {
  for (const turn of iterateTurns(filePath)) {
    // When the session's subagents/agent-*.jsonl files are scanned separately, the same
    // subagent errors also appear inlined as sidechain lines in the parent transcript —
    // skip them here so each error counts once (refute-first review finding 4, 743).
    if (skipSidechain && turn.isSidechain) continue;
    const candidates = [];
    for (const r of turn.toolResults) {
      if (r.isError && r.text) candidates.push(r.text);
    }
    if (turn.type === 'assistant' && turn.assistantText) candidates.push(turn.assistantText);

    for (const text of candidates) {
      const id = classify(text);
      if (id) {
        record(buckets[id], { sessionId, timestamp: turn.timestamp, text });
      } else if (turn.toolResults.some((r) => r.isError && r.text === text)) {
        // UNMATCHED bucket clusters TOOL ERRORS only — assistant prose that
        // merely mentions a failure (no signature match) is not itself an
        // error signature and would flood the unmatched cluster with noise.
        const key = normalize(text).slice(0, UNMATCHED_PREFIX_LEN);
        if (!key) continue;
        if (!unmatchedClusters[key]) {
          unmatchedClusters[key] = { prefix: key, count: 0, sessions: new Set(), firstSeen: null, lastSeen: null, samples: [] };
        }
        record(unmatchedClusters[key], { sessionId, timestamp: turn.timestamp, text });
      }
    }
  }
}

/** Real (non-storage-artifact) user message count over an already-materialized turn array, per 743 F-1. */
export function countRealUserMessagesInTurns(turns) {
  return dedupeAdjacentUserTurns(turns).filter((t) => t.type === 'user' && t.userText).length;
}

/** Real (non-storage-artifact) user message count for one transcript file, per 743 F-1. */
function countRealUserMessages(filePath) {
  return countRealUserMessagesInTurns([...iterateTurns(filePath)]);
}

// --- Report shaping ----------------------------------------------------

function finalizeBucket(b) {
  return {
    id: b.id,
    description: b.description,
    pavedPath: b.pavedPath ?? null,
    count: b.count,
    distinctSessions: b.sessions.size,
    firstSeen: b.firstSeen,
    lastSeen: b.lastSeen,
    samples: b.samples,
  };
}

function finalizeCluster(c) {
  return {
    prefix: c.prefix,
    count: c.count,
    distinctSessions: c.sessions.size,
    firstSeen: c.firstSeen,
    lastSeen: c.lastSeen,
    samples: c.samples,
  };
}

function toMarkdown(report) {
  const lines = [];
  lines.push(`# Signature census (743 P-L) — ${report.window.since} .. ${report.window.until}`);
  lines.push('');
  lines.push(`Sessions scanned: ${report.sessions_scanned}. Generated: ${report.generated_at}.`);
  lines.push('');
  lines.push('| Signature | Count | Sessions | First seen | Last seen | Paved path |');
  lines.push('|---|---|---|---|---|---|');
  for (const s of report.signatures) {
    const pp = s.pavedPath ? s.pavedPath.split('(')[0].trim() : '(none yet — candidate for a new disposition)';
    lines.push(`| ${s.id} | ${s.count} | ${s.distinctSessions} | ${s.firstSeen ?? '—'} | ${s.lastSeen ?? '—'} | ${pp} |`);
  }
  lines.push('');
  lines.push('## Samples');
  for (const s of report.signatures) {
    if (s.count === 0) continue;
    lines.push(`### ${s.id} (${s.count} occurrences, ${s.distinctSessions} sessions)`);
    lines.push(s.description);
    for (const sm of s.samples) lines.push(`- \`${sm}\``);
    lines.push('');
  }
  if (report.unmatched.length) {
    lines.push('## UNMATCHED (clustered by first 40 chars)');
    lines.push('');
    lines.push('| Prefix | Count | Sessions | Last seen |');
    lines.push('|---|---|---|---|');
    for (const c of report.unmatched) {
      lines.push(`| \`${c.prefix}\` | ${c.count} | ${c.distinctSessions} | ${c.lastSeen ?? '—'} |`);
    }
    lines.push('');
  }
  return lines.join('\n');
}

// --- Main ----------------------------------------------------------------

export function main() {
  const opts = parseArgs(process.argv.slice(2));
  const sinceMs = Date.parse(opts.since);
  const untilMs = opts.until ? Date.parse(opts.until) : null;

  const sessions = listSessions({ projectsRoot: opts.projectsRoot, sinceMs, untilMs });

  const buckets = {};
  for (const sig of SIGNATURES) buckets[sig.id] = emptySignatureBucket(sig);
  const unmatchedClusters = {};

  let realUserMessages = 0;
  let filesScanned = 0;
  let scanErrors = 0;

  for (const s of sessions) {
    try {
      const projectDirPath = path.join(opts.projectsRoot, s.projectDir);
      const subPaths = listSubagentPaths(projectDirPath, s.sessionId);
      // Skip sidechain lines in the parent only when the subagent files exist to be
      // scanned in their own right — otherwise sidechain lines are the only record.
      scanFile(s.path, s.sessionId, buckets, unmatchedClusters, {
        skipSidechain: subPaths.length > 0,
      });
      realUserMessages += countRealUserMessages(s.path);
      filesScanned += 1;

      for (const subPath of subPaths) {
        scanFile(subPath, s.sessionId, buckets, unmatchedClusters);
        filesScanned += 1;
      }
    } catch (err) {
      scanErrors += 1;
      process.stderr.write(`signature-census: skipping ${s.sessionId} after error: ${err.message}\n`);
    }
  }

  const signatures = Object.values(buckets).map(finalizeBucket).sort((a, b) => b.count - a.count);
  const unmatched = Object.values(unmatchedClusters)
    .map(finalizeCluster)
    .sort((a, b) => b.count - a.count)
    .slice(0, UNMATCHED_TOP_N);

  const report = {
    window: { since: opts.since, until: opts.until ?? new Date().toISOString().slice(0, 10) },
    generated_at: new Date().toISOString(),
    sessions_scanned: sessions.length,
    files_scanned: filesScanned,
    scan_errors: scanErrors,
    real_user_messages_seen: realUserMessages,
    signatures,
    unmatched,
    disposition_note: 'A signature at count >= 5 gets a disposition (root-fix / fire-time hint / wontfix) at the next mining-pass review — see scripts/agent-analytics/README.md "Signature census (743 P-L)". This report is a proposal, not a verdict.',
  };

  const outDir = path.join(repoRoot, TELEMETRY_DIR);
  try {
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(path.join(outDir, 'signature-census.json'), JSON.stringify(report, null, 2), 'utf8');
    fs.writeFileSync(path.join(outDir, 'signature-census.md'), toMarkdown(report), 'utf8');
  } catch (err) {
    process.stderr.write(`signature-census: could not write output files: ${err.message}\n`);
  }

  if (opts.json) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  } else {
    process.stdout.write(toMarkdown(report) + '\n');
  }
}

// Reporting tool: exit 0 always, even on a fatal error — a broken census must
// never block the mining pass or CI (see file header). Only runs main() when
// invoked directly (`node signature-census.mjs`), not when imported for its
// exports (SIGNATURES/classify/countRealUserMessagesInTurns/main) by tests.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (err) {
    process.stderr.write(`signature-census: fatal error (reported, not thrown): ${err.stack || err.message}\n`);
  }
  process.exit(0);
}
