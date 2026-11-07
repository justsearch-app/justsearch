#!/usr/bin/env node

/**
 * Context Attribution Analyzer — measures what fills agent context windows.
 *
 * Parses Claude Code transcript JSONL files and classifies every content block
 * by category (tool output by tool name, assistant text, thinking, user text,
 * system). Chars/4 ≈ tokens — sufficient for proportional analysis.
 *
 * Usage:
 *   node context-attribution.mjs --session-id <id>   # Analyze one session
 *   node context-attribution.mjs --all                # Analyze all sessions
 *   node context-attribution.mjs --all --json         # JSON output
 *   node context-attribution.mjs --all --top 5        # Show top N tools
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  loadEvents, groupBySession, round,
} from './lib/telemetry-io.mjs';
import { callsFromClaudeTranscript } from './lib/ledger/claude-adapter.mjs';

const DEFAULT_TOP = 10;

// --- Sufficiency floors (tempdoc 858 §6) -----------------------------------
// An `insufficient` boolean computed from the window, surfaced prominently, and
// a CONCLUSION that requires !insufficient while the underlying counts stay
// visible. Declared as constants rather than in a data file because these are
// fixed properties of the estimator and do not move as the population grows —
// unlike a recalibratable baseline with tolerances, which is what the retired
// `check-agent-quality-trend.mjs` carried its own floor in (tempdoc 858 §4.5).
// The in-lane precedent for a declared floor was `MIN_TOOL_CALLS` in
// analyze-trends.mjs (deleted, tempdoc 930 — no invoker since 2026-07).

/**
 * Aggregate mode summarises each category as a median with a [P25–P75] band.
 * A distribution-free interval for a median is the order-statistic (sign-test)
 * interval, whose widest form [x(1), x(n)] attains coverage 1 - 2·2^-n. That
 * reaches 95% only from n = 6 (2^-5 = 0.031 > 0.025; 2^-6 = 0.016 ≤ 0.025).
 * Below 6, even the widest interval the sample can produce fails to cover the
 * population median at 95%, so the median is a description of these sessions
 * and not an estimate of the population's.
 */
const MIN_ATTRIBUTION_SESSIONS = 6;

/**
 * The aggregate is computed over the sessions whose transcripts still exist,
 * which is a subset selected by transcript retention, not a random sample —
 * the observed starved run was N=7 usable of 20. Under arbitrary missingness
 * the population median is only bounded by the observed sample when more than
 * half the population is observed; at or below half, the unobserved sessions
 * could sit entirely on one side and carry the true median outside the observed
 * range. So this is the identification boundary for a median, not a taste
 * threshold. Strict `>`: exactly half is the unidentified case.
 */
const MIN_ATTRIBUTION_COVERAGE = 0.5;

// --- Transcript path discovery (adapted from cost-session.mjs) ---

function findTranscriptPaths(sessionEvents) {
  const startEvent = sessionEvents.find(e => e.event === 'session_start' && e.transcript_path);
  const mainPath = startEvent?.transcript_path ?? null;

  const subagentPaths = [];
  const seen = new Set();
  for (const e of sessionEvents) {
    if (e.event === 'subagent_stop' && e.agent_transcript_path) {
      if (!seen.has(e.agent_transcript_path)) {
        seen.add(e.agent_transcript_path);
        subagentPaths.push(e.agent_transcript_path);
      }
    }
  }

  return { mainPath, subagentPaths };
}

// --- Transcript parser ---

/**
 * Per-tool NAME resolution (886 §12 PR 5a) is sourced from the ledger's
 * `ToolEvent`s (`callsFromClaudeTranscript`), replacing this module's own
 * private `tool_use_id -> name` join — the same join claude-adapter.mjs's
 * `toolUseById` already performs. CHAR COUNTS deliberately stay a LOCAL
 * computation (`localToolResultChars` below, unchanged since before this
 * migration), NOT `ToolEvent.outputChars`, on measured evidence from a
 * parity run against this repo's real corpus (886 §12 PR 5a):
 *
 *   outputChars is built on the ledger's shared `extractToolResultText`,
 *   which — correctly, for its OTHER consumers — extracts only `text`-typed
 *   content blocks and drops any other block type (an `image` block, i.e. a
 *   screenshot). This instrument's whole purpose is "what fills agent
 *   context windows" (module docstring) and an image block consumes real
 *   context regardless of whether its bytes are human-readable, so a
 *   text-only count systematically UNDERSTATES exactly the tools this repo
 *   leans on hardest for visual verification (ui-shot / claude-in-chrome).
 *   Substituting outputChars measured a 22.5% drop in aggregate corpus chars
 *   (46,913K -> 36,375K) with `mcp__claude-in-chrome__computer` losing 99%
 *   (4,157K -> 47K), `browser_batch` losing 99% (1,688K -> 9K), and `Read`
 *   losing 52% (9,570K -> 4,577K, since Read is routinely pointed at
 *   screenshot PNGs here) — not a rounding difference, a different metric.
 *   See docs/tempdocs/886-agent-token-efficiency-review.md's PR 5a outcome
 *   paragraph for the full measurement.
 *
 * The two joins are POSITIONALLY zippable: both walk the same file's
 * `tool_result` blocks in the same document order, and cannot disagree BY
 * CONSTRUCTION — both predicates are structurally identical (`streamLines`
 * over the file, filtering `entry.type === 'user'` +
 * `Array.isArray(content)` + `b.type === 'tool_result'`; see
 * `lib/ledger/claude-adapter.mjs`'s `processClaudeTranscript` vs this file's
 * own `localToolResultChars`, which re-implements the identical line-scan by
 * hand rather than calling `streamLines` itself). This is a structural
 * guarantee, not an empirically-observed one (886 §12 PR 5b review nit —
 * the prior wording here overstated a corpus scan as proof of an invariant
 * the code shape already establishes). A `<task-notification>` line surfaces
 * on the ledger as its own synthetic `ToolEvent` (role `wait`, name
 * `task-notification`) rather than a `tool_result` and is excluded from the
 * zip — it is not a Claude tool call; its text is still counted once, as
 * ordinary user text, by the text/thinking/user/system walk in
 * `analyzeTranscript`. The `'(zip-mismatch)'` fallback below is therefore
 * DEFENSIVE — it exists for a future transcript shape neither walk was
 * written against, not a case expected to fire today — refusing to guess
 * rather than silently mis-pairing by position, with a warning printed once
 * per file.
 *
 * Orphan/forward-referenced tool_results (a `tool_use_id` with no matching
 * `tool_use` block in this file, e.g. a subagent transcript whose spawning
 * call lives in the parent's own file) are labelled `'(unknown)'` by the
 * ledger adapter (`claude-adapter.mjs`'s `use?.name ?? '(unknown)'`) — NOT
 * the bare `'unknown'` this module's own pre-PR-5a private join used. This
 * module inherited the adapter's label as a side effect of the PR 5a
 * migration; kept here deliberately (prefer the adapter's label as the one
 * source of truth) rather than reintroducing a second, divergent spelling.
 */
function localToolResultChars(filePath) {
  let content;
  try {
    content = fs.readFileSync(filePath, 'utf8');
  } catch {
    return [];
  }
  const chars = [];
  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    let obj;
    try { obj = JSON.parse(line); } catch { continue; }
    if (obj.type !== 'user' || !Array.isArray(obj.message?.content)) continue;
    for (const b of obj.message.content) {
      if (b.type !== 'tool_result') continue;
      const c = typeof b.content === 'string' ? b.content : JSON.stringify(b.content || '');
      chars.push(c.length);
    }
  }
  return chars;
}

/**
 * Pure zip/aggregation step, split out from `attributeToolEvents` (886 §12
 * PR 5b) so the zip-mismatch fallback is unit-testable without a real
 * transcript file on disk — same "pure, entries-in" pattern
 * `lib/ledger/codex-adapter.mjs`'s `processCodexEntries` uses.
 */
function attributeFromArrays(names, chars, { filePath = '(in-memory)' } = {}) {
  const byTool = new Map();
  const addTo = (name, c) => {
    if (!byTool.has(name)) byTool.set(name, { count: 0, chars: 0 });
    const entry = byTool.get(name);
    entry.count += 1;
    entry.chars += c;
  };

  if (names.length !== chars.length) {
    console.error(`context-attribution: tool_result count mismatch in ${filePath} (ledger=${names.length}, local=${chars.length}) — attributing to '(zip-mismatch)'`);
    // One addTo() call PER folded tool_result (886 §12 PR 5b fix), not one
    // call carrying the summed total — the prior version left `count` stuck
    // at 1 while `chars` already held the full sum, understating the
    // mismatch's own reported call count.
    for (const c of chars) addTo('(zip-mismatch)', c);
    return byTool;
  }

  for (let i = 0; i < names.length; i += 1) addTo(names[i], chars[i]);
  return byTool;
}

function attributeToolEvents(filePath) {
  const { toolEvents } = callsFromClaudeTranscript(filePath);
  const names = toolEvents.filter((ev) => ev.name !== 'task-notification').map((ev) => ev.name);
  const chars = localToolResultChars(filePath);
  return attributeFromArrays(names, chars, { filePath });
}

function analyzeTranscript(filePath) {
  let content;
  try {
    content = fs.readFileSync(filePath, 'utf8');
  } catch {
    return { error: `Cannot read transcript: ${filePath}` };
  }

  const lines = content.split('\n').filter(l => l.trim());
  let assistantTextChars = 0;
  let thinkingChars = 0;
  let userTextChars = 0;
  let systemChars = 0;

  for (const line of lines) {
    let obj;
    try { obj = JSON.parse(line); } catch { continue; }

    if (obj.type === 'assistant' && Array.isArray(obj.message?.content)) {
      for (const b of obj.message.content) {
        if (b.type === 'text') {
          assistantTextChars += (b.text || '').length;
        } else if (b.type === 'thinking') {
          thinkingChars += (b.thinking || '').length;
        }
      }
    }

    if (obj.type === 'user') {
      if (typeof obj.message?.content === 'string') {
        userTextChars += obj.message.content.length;
      } else if (Array.isArray(obj.message?.content)) {
        for (const b of obj.message.content) {
          if (b.type === 'text') {
            userTextChars += (b.text || '').length;
          }
        }
      }
    }

    if (obj.type === 'system') {
      const c = typeof obj.message?.content === 'string'
        ? obj.message.content
        : JSON.stringify(obj.message?.content || '');
      systemChars += c.length;
    }
  }

  const byTool = attributeToolEvents(filePath);

  // Convert byTool Map to sorted array
  const toolTotal = [...byTool.values()].reduce((s, v) => s + v.chars, 0);
  const topTools = [...byTool.entries()]
    .map(([tool, { count, chars }]) => ({ tool, count, chars }))
    .sort((a, b) => b.chars - a.chars);

  return {
    toolTotal,
    assistantTextChars,
    thinkingChars,
    userTextChars,
    systemChars,
    topTools,
  };
}

// --- Per-session attribution ---

function attributeSession(sessionId, sessionEvents) {
  const { mainPath, subagentPaths } = findTranscriptPaths(sessionEvents);

  if (!mainPath) {
    return { session_id: sessionId, error: 'no_transcript' };
  }

  // Analyze main transcript
  const main = analyzeTranscript(mainPath);
  if (main.error) {
    return { session_id: sessionId, error: main.error };
  }

  // Merge subagent transcripts into a combined view
  for (const sp of subagentPaths) {
    const sub = analyzeTranscript(sp);
    if (sub.error) continue;
    main.toolTotal += sub.toolTotal;
    main.assistantTextChars += sub.assistantTextChars;
    main.thinkingChars += sub.thinkingChars;
    main.userTextChars += sub.userTextChars;
    main.systemChars += sub.systemChars;
    // Merge per-tool stats
    for (const st of sub.topTools) {
      const existing = main.topTools.find(t => t.tool === st.tool);
      if (existing) {
        existing.count += st.count;
        existing.chars += st.chars;
      } else {
        main.topTools.push({ ...st });
      }
    }
  }

  // Re-sort after merge
  main.topTools.sort((a, b) => b.chars - a.chars);

  const totalChars = main.toolTotal + main.assistantTextChars
    + main.thinkingChars + main.userTextChars + main.systemChars;

  if (totalChars === 0) {
    return { session_id: sessionId, error: 'empty_transcript' };
  }

  const pct = (n) => round(n / totalChars, 3);

  return {
    session_id: sessionId,
    total_chars: totalChars,
    estimated_tokens: Math.round(totalChars / 4),
    categories: {
      tool_outputs:    { chars: main.toolTotal,          pct: pct(main.toolTotal) },
      assistant_text:  { chars: main.assistantTextChars,  pct: pct(main.assistantTextChars) },
      thinking:        { chars: main.thinkingChars,       pct: pct(main.thinkingChars) },
      user_messages:   { chars: main.userTextChars,       pct: pct(main.userTextChars) },
      system:          { chars: main.systemChars,         pct: pct(main.systemChars) },
    },
    top_tools: main.topTools.map(t => ({
      tool: t.tool,
      calls: t.count,
      chars: t.chars,
      pct: pct(t.chars),
    })),
    subagent_transcripts: subagentPaths.length,
  };
}

// --- Aggregate mode ---

function aggregateResults(results) {
  const valid = results.filter(r => !r.error);
  if (valid.length === 0) return { count: 0, error: 'no_valid_sessions' };

  const categories = ['tool_outputs', 'assistant_text', 'thinking', 'user_messages', 'system'];
  const catPcts = {};
  for (const cat of categories) {
    catPcts[cat] = valid.map(r => r.categories[cat].pct).sort((a, b) => a - b);
  }

  // Aggregate per-tool across all sessions
  const toolAgg = new Map();
  for (const r of valid) {
    for (const t of r.top_tools) {
      if (!toolAgg.has(t.tool)) toolAgg.set(t.tool, { totalChars: 0, totalCalls: 0, sessions: 0 });
      const a = toolAgg.get(t.tool);
      a.totalChars += t.chars;
      a.totalCalls += t.calls;
      a.sessions++;
    }
  }

  const grandChars = valid.reduce((s, r) => s + r.total_chars, 0);
  const toolsSorted = [...toolAgg.entries()]
    .map(([tool, a]) => ({
      tool,
      total_chars: a.totalChars,
      total_calls: a.totalCalls,
      sessions: a.sessions,
      pct: round(a.totalChars / grandChars, 3),
    }))
    .sort((a, b) => b.total_chars - a.total_chars);

  const categoryMedians = Object.fromEntries(
    categories.map(cat => [cat, round(median(catPcts[cat]), 3)])
  );

  const coverage = results.length > 0 ? valid.length / results.length : 0;
  const insufficient = valid.length < MIN_ATTRIBUTION_SESSIONS
    || coverage <= MIN_ATTRIBUTION_COVERAGE;

  // The question this instrument exists to answer ("what fills agent context
  // windows") — a conclusion, so it requires !insufficient. The medians,
  // quartiles and tool ranking it is read off are emitted either way.
  const dominant = categories
    .map(cat => ({ cat, med: categoryMedians[cat] }))
    .sort((a, b) => b.med - a.med)[0];

  return {
    count: valid.length,
    skipped: results.length - valid.length,
    coverage: round(coverage, 3),
    min_sessions: MIN_ATTRIBUTION_SESSIONS,
    min_coverage: MIN_ATTRIBUTION_COVERAGE,
    insufficient,
    dominant_category: insufficient ? null : dominant.cat,
    total_chars: grandChars,
    estimated_tokens: Math.round(grandChars / 4),
    category_medians: categoryMedians,
    category_p25: Object.fromEntries(
      categories.map(cat => [cat, round(percentile(catPcts[cat], 25), 3)])
    ),
    category_p75: Object.fromEntries(
      categories.map(cat => [cat, round(percentile(catPcts[cat], 75), 3)])
    ),
    top_tools: toolsSorted,
  };
}

function median(sorted) {
  if (sorted.length === 0) return 0;
  return percentile(sorted, 50);
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = (p / 100) * (sorted.length - 1);
  const lo = Math.floor(idx);
  const hi = Math.ceil(idx);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
}

// --- Human-readable formatting ---

function formatSingle(result, topN) {
  if (result.error) return `Session ${result.session_id}: ${result.error}`;

  const lines = [];
  const line = (s = '') => lines.push(s);
  const pct = (n) => `${(n * 100).toFixed(1)}%`;
  const kChars = (n) => `${(n / 1000).toFixed(0)}K`;

  line(`## Context Attribution: ${result.session_id}`);
  line();
  line(`Total: ${kChars(result.total_chars)} chars (~${kChars(result.estimated_tokens)} tokens est.)`);
  line(`Subagent transcripts merged: ${result.subagent_transcripts}`);
  line();
  line(`### Category Breakdown`);
  line();
  line(`| Category | Chars | % |`);
  line(`|----------|------:|--:|`);
  for (const [cat, data] of Object.entries(result.categories)) {
    line(`| ${cat.replace(/_/g, ' ')} | ${kChars(data.chars)} | ${pct(data.pct)} |`);
  }
  line();
  line(`### Top Tools`);
  line();
  line(`| Tool | Calls | Chars | % |`);
  line(`|------|------:|------:|--:|`);
  for (const t of result.top_tools.slice(0, topN)) {
    line(`| ${t.tool} | ${t.calls} | ${kChars(t.chars)} | ${pct(t.pct)} |`);
  }

  return lines.join('\n');
}

function formatAggregate(agg, topN) {
  if (agg.error) return `No valid sessions: ${agg.error}`;

  const lines = [];
  const line = (s = '') => lines.push(s);
  const pct = (n) => `${(n * 100).toFixed(1)}%`;
  const kChars = (n) => `${(n / 1000).toFixed(0)}K`;

  line(`## Context Attribution — Aggregate (N=${agg.count})`);
  if (agg.skipped > 0) line(`Skipped: ${agg.skipped} sessions (no/missing transcript)`);
  if (agg.insufficient) {
    line(`> ⚠️ Insufficient sample: ${agg.count} usable of ${agg.count + agg.skipped}`
      + ` (need N ≥ ${agg.min_sessions} and > ${pct(agg.min_coverage)} coverage).`
      + ` Distribution below describes these sessions only; no dominant category is claimed.`);
  } else {
    line(`Dominant context consumer: **${agg.dominant_category.replace(/_/g, ' ')}**`
      + ` (median ${pct(agg.category_medians[agg.dominant_category])}).`);
  }
  line();
  line(`Total across all sessions: ${kChars(agg.total_chars)} chars (~${kChars(agg.estimated_tokens)} tokens est.)`);
  line();
  line(`### Category Distribution (median [P25–P75])`);
  line();
  line(`| Category | Median | P25 | P75 |`);
  line(`|----------|-------:|----:|----:|`);
  for (const cat of Object.keys(agg.category_medians)) {
    line(`| ${cat.replace(/_/g, ' ')} | ${pct(agg.category_medians[cat])} | ${pct(agg.category_p25[cat])} | ${pct(agg.category_p75[cat])} |`);
  }
  line();
  line(`### Top Tools (aggregate across all sessions)`);
  line();
  line(`| Tool | Sessions | Calls | Chars | % |`);
  line(`|------|:--------:|------:|------:|--:|`);
  for (const t of agg.top_tools.slice(0, topN)) {
    line(`| ${t.tool} | ${t.sessions} | ${t.total_calls} | ${kChars(t.total_chars)} | ${pct(t.pct)} |`);
  }

  return lines.join('\n');
}

// --- Main ---

function main() {
  const args = process.argv.slice(2);
  const jsonOnly = args.includes('--json');
  const all = args.includes('--all');
  const sessionIdx = args.indexOf('--session-id');
  const sessionId = sessionIdx !== -1 ? args[sessionIdx + 1] : null;
  const topIdx = args.indexOf('--top');
  const topN = topIdx !== -1 ? parseInt(args[topIdx + 1], 10) || DEFAULT_TOP : DEFAULT_TOP;

  if (!all && !sessionId) {
    console.error('Usage: node context-attribution.mjs --session-id <id> | --all [--json] [--top N]');
    process.exit(1);
  }

  const events = loadEvents();
  const sessions = groupBySession(events);

  if (sessionId) {
    const sessionEvents = sessions.get(sessionId);
    if (!sessionEvents) {
      console.error(`Session not found in events: ${sessionId}`);
      process.exit(1);
    }
    const result = attributeSession(sessionId, sessionEvents);

    if (jsonOnly) {
      process.stdout.write(JSON.stringify(result, null, 2) + '\n');
    } else {
      console.log(formatSingle(result, topN));
    }
    return;
  }

  // --all mode
  const results = [];
  for (const [sid, sessionEvents] of sessions) {
    results.push(attributeSession(sid, sessionEvents));
  }

  if (jsonOnly) {
    const agg = aggregateResults(results);
    process.stdout.write(JSON.stringify(agg, null, 2) + '\n');
  } else {
    const agg = aggregateResults(results);
    console.log(formatAggregate(agg, topN));
  }
}

const __filename = fileURLToPath(import.meta.url);
if (process.argv[1] && path.resolve(process.argv[1]) === __filename) main();

export {
  aggregateResults, attributeSession, formatAggregate,
  MIN_ATTRIBUTION_SESSIONS, MIN_ATTRIBUTION_COVERAGE,
  attributeFromArrays,
};
