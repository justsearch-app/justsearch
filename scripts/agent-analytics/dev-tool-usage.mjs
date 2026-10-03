#!/usr/bin/env node
/**
 * Dev MCP tool usage reader (tempdoc 844 P7).
 *
 * WHY THIS EXISTS. Tempdoc 844's §3 numbers (379 `justsearch-dev` invocations,
 * per-tool error rates, the "31% of schema payload for 0% of traffic" finding)
 * were produced by an ad-hoc, one-off script that no longer exists — the
 * audit was reproducible in principle (its §10 documents the method) but not
 * in practice. This reader is that method turned into standing substrate, on
 * the model of `cache-efficiency.mjs` (841): discovery comes from
 * `lib/transcript-store.mjs`, this file adds only the dev-tool-specific
 * extraction and classification on top.
 *
 * 844 §12.6 names this reader's headline number — first-call success rate —
 * as the falsifier for the whole "fix the dev surface" lane: if it does not
 * move after the honesty fixes land, the tools were never the problem and the
 * surface should shrink to arbitration-only. That is why this script exists
 * as a REPEATABLE instrument, not a one-shot report.
 *
 * METHOD (844 §10, verbatim): parse every transcript line as JSON, skipping
 * unparseable lines. Per file, index every `message.content[]` entry with
 * `type === "tool_use"` by its `id` (name only). Join every
 * `message.content[]` entry with `type === "tool_result"` back to that map by
 * `tool_use_id`. Invocation counts come from `tool_use` blocks ONLY — a naive
 * grep over tool names double-counts (the call and the result both mention
 * the name) and also matches the deferred-tool listing injected into every
 * session's system reminders. A result is an error when `is_error === true`,
 * OR its text body matches `"ok"\s*:\s*false`, OR it matches `"error"\s*:\s*\{`.
 * An error code is pulled from a `"code":"UPPER_SNAKE"` match when present.
 *
 * FIRST-CALL SUCCESS RATE — the definition, spelled out because it is a
 * judgment call the falsifier depends on. Invocations of one tool, within one
 * session, are walked in chronological order (transcript timestamp, falling
 * back to file-processing order when timestamps tie or are missing). An
 * invocation is a RETRY iff the immediately preceding invocation of the SAME
 * tool in the SAME session was an error. First-call success rate is:
 *
 *   (non-retry invocations that succeeded) / (non-retry invocations)
 *
 * Retries are excluded from BOTH numerator and denominator, not folded into
 * "failed". Rationale: counting a retry as a second failure double-penalizes
 * one incident, and counting a successful retry as an ordinary success hides
 * that it took two attempts — neither is what "first-call success" should
 * mean. This only tracks retries of the SAME tool; a session that abandons a
 * failed `api_call` for `fetch_api_json` is not counted as a retry of either
 * (the tools are meant to be interchangeable for some jobs, and conflating
 * them would understate both tools' real retry burden).
 *
 * SESSION KEY. A dev tool call from a subagent transcript
 * (`<sessionId>/subagents/agent-*.jsonl`) is attributed to its PARENT
 * session's key, not treated as its own session — the ownership/arbitration
 * tools this surface exists for are a property of the conversation the human
 * is driving, and splitting subagent calls into separate "sessions" would
 * inflate the session-count denominator without changing what a retry means.
 *
 * REGISTERED-SET DISCOVERY. The zero-use finding (844 §3: five tools never
 * invoked) only means something if the reader can also see what exists to be
 * called. This spawns `scripts/dev/justsearch-dev-mcp.mjs` as a real MCP
 * stdio server and does `initialize` + `tools/list` — the same handshake
 * Claude Code performs. Another agent may be actively editing that server
 * concurrently (844 is explicit about this), so a spawn/handshake failure is
 * NON-FATAL: the reader falls back to observed-only tool names and prints a
 * warning. It is only attempted for the default `justsearch-dev` server —
 * other servers (github, claude-in-chrome, ...) are not locally spawnable the
 * same way, so their zero-use tools cannot be discovered this way and are
 * simply not claimed.
 *
 * HONEST LIMITS (844 §1, carried into the output footer):
 *   - Byte totals are BYTES, not tokens. A screenshot result is a base64
 *     image; it tokenizes as an image (~1-2k tokens) regardless of its byte
 *     size. Byte ORDERING across tools holds; do not convert a byte figure to
 *     a token claim without re-measuring.
 *   - The ok-false / error-object regexes have a small false-positive rate:
 *     a result whose body TEXT happens to contain the literal marker (e.g. a
 *     `tail_log` result quoting engine-log JSON) is classified as an error
 *     even though the tool call itself succeeded. Error counts are therefore
 *     an upper bound, roughly +/-1 per tool on a corpus this size.
 *   - Zero usage of an off-by-default or undocumented feature (e.g.
 *     `hotReload`) measures VISIBILITY, not value — it is not evidence the
 *     feature is unneeded.
 *   - The corpus rolls. These numbers are only re-derivable while the
 *     underlying transcripts survive on this machine.
 *
 * Usage:
 *   node scripts/agent-analytics/dev-tool-usage.mjs [--json] [--since <ISO>] [--server <name>]
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { listSessions, listSubagentPaths, DEFAULT_PROJECTS_ROOT } from './lib/transcript-store.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const DEFAULT_SERVER = 'justsearch-dev';

// --- pure extraction / classification (exported for test) --------------------

/** `mcp__<server>__<tool>` -> `{ server, tool }`, or `null` for a non-mcp name. */
export function splitServerAndTool(fullName) {
  if (typeof fullName !== 'string' || !fullName.startsWith('mcp__')) return null;
  const rest = fullName.slice('mcp__'.length);
  const idx = rest.indexOf('__');
  if (idx === -1) return { server: rest, tool: '' };
  return { server: rest.slice(0, idx), tool: rest.slice(idx + 2) };
}

/** Every `tool_use` block on one transcript entry, `{id, name}` only. */
export function extractToolUseBlocks(entry) {
  const content = entry?.message?.content;
  if (!Array.isArray(content)) return [];
  const out = [];
  for (const b of content) {
    if (b && b.type === 'tool_use' && typeof b.id === 'string' && typeof b.name === 'string') {
      out.push({ id: b.id, name: b.name });
    }
  }
  return out;
}

/** Every `tool_result` block on one transcript entry, raw (unclassified). */
export function extractToolResultBlocks(entry) {
  const content = entry?.message?.content;
  if (!Array.isArray(content)) return [];
  const out = [];
  for (const b of content) {
    if (b && b.type === 'tool_result' && typeof b.tool_use_id === 'string') out.push(b);
  }
  return out;
}

/** Visible text of a tool_result's `content`, string or content-block array. */
export function resultBodyText(block) {
  if (!block) return '';
  if (typeof block.content === 'string') return block.content;
  if (Array.isArray(block.content)) {
    return block.content
      .filter((c) => c && typeof c.text === 'string')
      .map((c) => c.text)
      .join('\n');
  }
  return '';
}

/** Result byte size: string length, or sum of text-block + image source.data lengths. */
export function resultByteSize(block) {
  if (!block) return 0;
  if (typeof block.content === 'string') return block.content.length;
  if (Array.isArray(block.content)) {
    let sum = 0;
    for (const c of block.content) {
      if (!c) continue;
      if (typeof c.text === 'string') sum += c.text.length;
      if (c.type === 'image' && c.source && typeof c.source.data === 'string') sum += c.source.data.length;
    }
    return sum;
  }
  return 0;
}

const OK_FALSE_RE = /"ok"\s*:\s*false/;
const ERROR_OBJ_RE = /"error"\s*:\s*\{/;
const CODE_RE = /"code"\s*:\s*"([A-Z_0-9]+)"/;

/**
 * Classify one joined tool_result. `is_error` wins outright; otherwise the
 * body text is scanned for the ok-false / error-object markers (844 §1's
 * documented false-positive source — pinned in the test file, not "fixed",
 * because 844's own numbers assume this exact behaviour).
 */
export function classifyResultBlock(block) {
  const text = resultBodyText(block);
  const isError = Boolean(block?.is_error) || OK_FALSE_RE.test(text) || ERROR_OBJ_RE.test(text);
  const codeMatch = text.match(CODE_RE);
  return { isError, code: codeMatch ? codeMatch[1] : null, bytes: resultByteSize(block) };
}

/**
 * Mark each invocation (chronologically ordered, ONE (session, tool) pair) as
 * a retry iff the immediately preceding invocation errored. Pure and total —
 * does not look at anything but `isError` / order, so it is testable without
 * a transcript.
 */
export function markRetries(chronological) {
  let prevError = false;
  return chronological.map((inv) => {
    const isRetry = prevError;
    prevError = inv.isError;
    return { ...inv, isRetry };
  });
}

// --- per-file extraction -------------------------------------------------

/**
 * Parse one transcript file and push one record per `mcp__*` tool_use into
 * `records` (shared across every file in the corpus). `seqState` is a shared
 * `{n: 0}` counter used only as an ordering tie-breaker when timestamps are
 * missing or identical — it increases monotonically across the whole scan,
 * not just within one file, so cross-file (main + subagent) ties still
 * resolve to a stable, if imprecise, order. That imprecision is inherent
 * (subagent and main transcripts are physically separate files) and is
 * covered by the printed honest-limits footer.
 */
export function analyzeFile(file, sessionKey, records, seqState) {
  let content;
  try {
    content = fs.readFileSync(file, 'utf8');
  } catch {
    return;
  }
  const entries = [];
  for (const raw of content.split('\n')) {
    if (!raw.trim()) continue;
    let entry;
    try {
      entry = JSON.parse(raw);
    } catch {
      continue;
    }
    entries.push(entry);
  }

  const toolUseById = new Map();
  for (const entry of entries) {
    for (const use of extractToolUseBlocks(entry)) {
      if (!toolUseById.has(use.id)) {
        toolUseById.set(use.id, { name: use.name, ts: entry.timestamp ? Date.parse(entry.timestamp) : null });
      }
    }
  }

  const resultByToolUseId = new Map();
  for (const entry of entries) {
    for (const res of extractToolResultBlocks(entry)) {
      resultByToolUseId.set(res.tool_use_id, res);
    }
  }

  for (const [id, use] of toolUseById) {
    if (!use.name.startsWith('mcp__')) continue;
    const resultBlock = resultByToolUseId.get(id) || null;
    const classified = resultBlock
      ? classifyResultBlock(resultBlock)
      : { isError: false, code: null, bytes: 0 };
    records.push({
      toolFullName: use.name,
      sessionKey,
      ts: use.ts,
      seq: seqState.n++,
      isError: classified.isError,
      code: classified.code,
      bytes: classified.bytes,
      hasResult: resultBlock !== null,
    });
  }
}

// --- corpus discovery ------------------------------------------------------

/**
 * Every main + subagent transcript file across every `*justsearch-public*`
 * project dir, paired with the SESSION KEY it should be attributed to (see
 * the header comment's "SESSION KEY" note). Reuses
 * `lib/transcript-store.mjs` discovery rather than re-walking the filesystem
 * (743/841 precedent — one discovery module, not a fourth hand-rolled one).
 */
export function gatherFiles({ sinceMs = null, projectsRoot = DEFAULT_PROJECTS_ROOT } = {}) {
  const files = [];
  for (const s of listSessions({ projectsRoot, sinceMs })) {
    const sessionKey = `${s.projectDir}:${s.sessionId}`;
    files.push({ path: s.path, sessionKey });
    const projectDirPath = path.join(projectsRoot, s.projectDir);
    for (const sub of listSubagentPaths(projectDirPath, s.sessionId)) {
      files.push({ path: sub, sessionKey });
    }
  }
  return files;
}

// --- report assembly --------------------------------------------------------

/**
 * Build the full report from raw invocation `records` (as produced by
 * `analyzeFile`). `registeredFull` is an optional array of fully-qualified
 * `mcp__<server>__<tool>` names (from a live `tools/list`, or `null` when
 * discovery was skipped/failed) — tools registered but never invoked still
 * get a zeroed row, per 844 §3's "5 tools, 0 calls" finding.
 */
export function buildReport(records, { server = DEFAULT_SERVER, registeredFull = null } = {}) {
  const perServer = new Map();
  const serverGroups = new Map();

  for (const r of records) {
    const split = splitServerAndTool(r.toolFullName);
    if (!split) continue;
    const slot = perServer.get(split.server) || { calls: 0, bytes: 0 };
    slot.calls += 1;
    slot.bytes += r.bytes;
    perServer.set(split.server, slot);
    if (split.server === server) {
      const arr = serverGroups.get(r.toolFullName) || [];
      arr.push(r);
      serverGroups.set(r.toolFullName, arr);
    }
  }

  const allToolNames = new Set(serverGroups.keys());
  if (registeredFull) for (const t of registeredFull) allToolNames.add(t);

  const prefix = `${server.replace(/-/g, '_')}_`;

  const rows = [];
  for (const fullName of allToolNames) {
    const toolSuffix = splitServerAndTool(fullName)?.tool ?? fullName;
    const shortName = toolSuffix.startsWith(prefix) ? toolSuffix.slice(prefix.length) : toolSuffix;
    const recs = serverGroups.get(fullName) || [];
    const calls = recs.length;
    const sessions = new Set(recs.map((r) => r.sessionKey));
    const errors = recs.filter((r) => r.isError).length;

    const codeTally = new Map();
    for (const r of recs) if (r.code) codeTally.set(r.code, (codeTally.get(r.code) || 0) + 1);

    const withResult = recs.filter((r) => r.hasResult);
    const bytesTotal = withResult.reduce((a, r) => a + r.bytes, 0);
    const avgBytes = withResult.length ? bytesTotal / withResult.length : 0;

    const bySession = new Map();
    for (const r of recs) {
      const arr = bySession.get(r.sessionKey) || [];
      arr.push(r);
      bySession.set(r.sessionKey, arr);
    }
    let nonRetry = 0;
    let nonRetrySuccess = 0;
    for (const sessRecs of bySession.values()) {
      sessRecs.sort((a, b) => {
        const at = a.ts ?? Infinity;
        const bt = b.ts ?? Infinity;
        if (at !== bt) return at - bt;
        return a.seq - b.seq;
      });
      for (const m of markRetries(sessRecs)) {
        if (!m.isRetry) {
          nonRetry += 1;
          if (!m.isError) nonRetrySuccess += 1;
        }
      }
    }

    rows.push({
      fullName,
      shortName,
      calls,
      sessions: sessions.size,
      errors,
      errorRate: calls ? errors / calls : 0,
      nonRetry,
      nonRetrySuccess,
      firstCallSuccessRate: nonRetry ? nonRetrySuccess / nonRetry : null,
      avgBytes,
      topCodes: [...codeTally.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3),
    });
  }

  rows.sort((a, b) => b.calls - a.calls || a.shortName.localeCompare(b.shortName));

  const totalCalls = rows.reduce((a, r) => a + r.calls, 0);
  const totalErrors = rows.reduce((a, r) => a + r.errors, 0);
  const totalNonRetry = rows.reduce((a, r) => a + r.nonRetry, 0);
  const totalNonRetrySuccess = rows.reduce((a, r) => a + r.nonRetrySuccess, 0);

  const perServerRows = [...perServer.entries()]
    .map(([name, v]) => ({ server: name, calls: v.calls, bytes: v.bytes }))
    .sort((a, b) => b.calls - a.calls);

  const sessionsSeen = new Set(records.map((r) => r.sessionKey)).size;

  return {
    server,
    sessionsSeen,
    perTool: rows,
    totals: {
      calls: totalCalls,
      errors: totalErrors,
      errorRate: totalCalls ? totalErrors / totalCalls : 0,
      firstCallSuccessRate: totalNonRetry ? totalNonRetrySuccess / totalNonRetry : null,
    },
    perServer: perServerRows,
  };
}

// --- registered-tool discovery (live MCP handshake) -------------------------

/**
 * Spawn the real `justsearch-dev` MCP stdio server and ask it `tools/list`,
 * the same handshake Claude Code performs. Returns `{ tools, warning }`
 * where `tools` is an array of fully-qualified `mcp__justsearch-dev__<tool>`
 * names, or `null` when discovery could not complete (never throws — a
 * concurrently-edited server is an expected, non-fatal condition here).
 */
export function discoverRegisteredTools(server, { timeoutMs = 8000 } = {}) {
  if (server !== DEFAULT_SERVER) {
    return Promise.resolve({
      tools: null,
      warning: `registered-set discovery is only implemented for "${DEFAULT_SERVER}" (requested "${server}") — zero-use tools for this server cannot be shown.`,
    });
  }
  const serverPath = path.join(REPO_ROOT, 'scripts', 'dev', 'justsearch-dev-mcp.mjs');
  if (!fs.existsSync(serverPath)) {
    return Promise.resolve({ tools: null, warning: `${serverPath} not found — skipping registered-set discovery.` });
  }

  return new Promise((resolve) => {
    let settled = false;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      resolve(result);
    };

    let child;
    try {
      child = spawn(process.execPath, [serverPath], {
        cwd: REPO_ROOT,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
      });
    } catch (e) {
      finish({ tools: null, warning: `could not spawn justsearch-dev-mcp: ${e.message}` });
      return;
    }

    const timer = setTimeout(() => {
      try { child.kill(); } catch { /* already gone */ }
      finish({ tools: null, warning: 'timed out waiting for justsearch-dev-mcp tools/list (another agent may be mid-edit on the server) — falling back to observed-only tool names.' });
    }, timeoutMs);

    child.on('error', (e) => {
      clearTimeout(timer);
      finish({ tools: null, warning: `justsearch-dev-mcp spawn error: ${e.message}` });
    });

    let buf = '';
    child.stdout.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let idx;
      while ((idx = buf.indexOf('\n')) !== -1) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        let msg;
        try {
          msg = JSON.parse(line);
        } catch {
          continue;
        }
        if (msg.id === 1) {
          if (msg.error) {
            clearTimeout(timer);
            try { child.kill(); } catch { /* already gone */ }
            finish({ tools: null, warning: `initialize failed: ${JSON.stringify(msg.error)}` });
            return;
          }
          child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
          child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list' })}\n`);
        } else if (msg.id === 2) {
          clearTimeout(timer);
          try { child.kill(); } catch { /* already gone */ }
          if (msg.error) {
            finish({ tools: null, warning: `tools/list failed: ${JSON.stringify(msg.error)}` });
            return;
          }
          const raw = (msg.result?.tools || []).map((t) => t.name);
          const tools = raw.map((n) => `mcp__${server}__${n.replace(/\./g, '_')}`);
          finish({ tools, warning: null });
        }
      }
    });

    child.stdin.write(`${JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'dev-tool-usage-reader', version: '0.1.0' },
      },
    })}\n`);
  });
}

// --- CLI ---------------------------------------------------------------------

function parseArgs(argv) {
  const asJson = argv.includes('--json');
  const sinceIdx = argv.indexOf('--since');
  const sinceArg = sinceIdx !== -1 ? argv[sinceIdx + 1] : null;
  const sinceMs = sinceArg ? Date.parse(sinceArg) : null;
  const serverIdx = argv.indexOf('--server');
  const server = serverIdx !== -1 && argv[serverIdx + 1] ? argv[serverIdx + 1] : DEFAULT_SERVER;
  return { asJson, sinceMs, server };
}

function fmtKB(n) { return `${(n / 1024).toFixed(1)}KB`; }
function pct(a, b) { return b ? `${(100 * a / b).toFixed(1)}%` : 'n/a'; }
function pctOrNA(rate) { return rate == null ? 'n/a' : `${(100 * rate).toFixed(1)}%`; }

function printHuman(report) {
  console.log(`dev-tool-usage — server="${report.server}", ${report.sessionsSeen} sessions scanned`);

  console.log(`\nper-tool (${report.server}):`);
  console.log(
    `  ${'tool'.padEnd(20)}${'calls'.padStart(7)}${'sess'.padStart(6)}${'err%'.padStart(8)}`
    + `${'1st-call%'.padStart(11)}${'avgKB'.padStart(9)}  top error codes`,
  );
  for (const r of report.perTool) {
    const codes = r.topCodes.length
      ? r.topCodes.map(([c, n]) => `${c}x${n}`).join(', ')
      : (r.calls === 0 ? '(never invoked)' : '');
    console.log(
      `  ${r.shortName.padEnd(20)}${String(r.calls).padStart(7)}${String(r.sessions).padStart(6)}`
      + `${pct(r.errors, r.calls).padStart(8)}${pctOrNA(r.firstCallSuccessRate).padStart(11)}`
      + `${fmtKB(r.avgBytes).padStart(9)}  ${codes}`,
    );
  }

  const t = report.totals;
  console.log(`\ntotals: calls=${t.calls}  error-rate=${pct(t.errors, t.calls)}  first-call-success=${pctOrNA(t.firstCallSuccessRate)}`);

  console.log(`\nall MCP servers (dev surface in proportion):`);
  console.log(`  ${'server'.padEnd(24)}${'calls'.padStart(7)}${'bytes'.padStart(12)}`);
  const totalServerCalls = report.perServer.reduce((a, s) => a + s.calls, 0);
  for (const s of report.perServer) {
    console.log(`  ${s.server.padEnd(24)}${String(s.calls).padStart(7)}${fmtKB(s.bytes).padStart(12)}  (${pct(s.calls, totalServerCalls)} of calls)`);
  }

  if (report.registeredWarning) {
    console.log(`\n!! ${report.registeredWarning}`);
  }

  console.log(`\nhonest limits:`);
  console.log(`  - byte totals are BYTES, not tokens; image results tokenize differently (do not convert).`);
  console.log(`  - the ok-false / error-object regexes have a small false-positive rate (a result whose`);
  console.log(`    TEXT BODY happens to contain the literal marker is counted as an error).`);
  console.log(`  - zero usage of an off-by-default or undocumented feature measures visibility, not value.`);
}

async function main() {
  const { asJson, sinceMs, server } = parseArgs(process.argv.slice(2));

  const files = gatherFiles({ sinceMs });
  const records = [];
  const seqState = { n: 0 };
  for (const f of files) analyzeFile(f.path, f.sessionKey, records, seqState);

  const { tools: registeredFull, warning: registeredWarning } = await discoverRegisteredTools(server);

  const report = buildReport(records, { server, registeredFull });
  report.registeredWarning = registeredWarning;

  if (asJson) {
    console.log(JSON.stringify(report, null, 2));
    return;
  }
  printHuman(report);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main();
}
