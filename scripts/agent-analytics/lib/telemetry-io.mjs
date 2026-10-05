/**
 * Shared I/O utilities for the agent-analytics pipeline.
 *
 * Deduplicates event loading, NDJSON parsing, and session grouping used by
 * cost-session, context-attribution, the merge-link scripts, and the friction miners.
 */

import fs from 'node:fs';
import path from 'node:path';

export const TELEMETRY_DIR = 'tmp/agent-telemetry';
export const EVENTS_FILE = 'events.ndjson';
export const SESSIONS_DIR = 'sessions';
export const COSTS_FILE = 'costs.ndjson';
export const SESSION_MERGES_FILE = 'session-merges.ndjson';

// --- friction scope filter (tempdoc 858 §7) --------------------------------
// One matcher and one renderer for friction-excluded-sessions.json, because
// there were four consumers, three forked copies, and TWO different match rules
// (baseline-economics/overhead-taxonomy matched one way, aggregate-friction/
// friction-timeline the other). The rules are unified here on the bidirectional
// form, which is the superset: it is what the friction pair already did, and it
// is provably identical for the transcript-driven pair, whose session ids come
// from filenames and so are never a strict prefix of a >=8-char key.

/** The listed ids. Exposed separately because the COUNT is itself reportable. */
export function loadExclusionKeys(filePath) {
  try {
    const data = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    return Object.keys(data.excluded || {});
  } catch {
    return [];
  }
}

/**
 * Match a session id against the listed keys. Keys are full UUIDs or truncated
 * 8-char prefixes, and callers pass ids of both shapes, so the comparison runs
 * both directions rather than assuming which side is truncated.
 */
export function makeExclusionMatcher(keys) {
  return (sessionId) => keys.some((k) => sessionId.startsWith(k) || k.startsWith(sessionId));
}

export function loadExclusionMatcher(filePath) {
  return makeExclusionMatcher(loadExclusionKeys(filePath));
}

/**
 * Render the scope-filter outcome so a zero cannot be misread as an observation.
 *
 * The list is a dated CAPTURE whose ids rotate out of ~/.claude/projects, so
 * "excluded: 0" states something no consumer checked — that nothing needed
 * excluding — when the truth is that the filter could not act. Every consumer of
 * friction-excluded-sessions.json renders through this, which is what makes the
 * file's `_consumers` MUST-NOT enforceable rather than aspirational.
 */
export function fmtScopeExclusion({ excluded, listed, mergesExcluded = 0, disabled = false }) {
  if (disabled) return 'scope filter disabled (--include-excluded)';
  if (excluded > 0) return `excluded by scope filter: ${excluded}`;
  if (mergesExcluded > 0) {
    return `scope filter excluded no session here, but ${mergesExcluded} merge row(s) below belong to scope-excluded sessions`;
  }
  if (listed === 0) return 'no scope filter configured';
  return `scope filter matched no session here — 0 of ${listed} listed ids`;
}

// --- session→merge link provenance (tempdoc 856 §3.1) ---------------------
// The link row gains `source` + `kind` so a recovered row is distinguishable
// from an observed one. Rows written before 856 carry NEITHER field; they are
// all teardown-written observations, so normalizeMergeLinkRow() backfills them
// as {source:'teardown', kind:'fact'} on READ. The ledger file itself is never
// rewritten — legacy rows stay byte-identical on disk.
export const MERGE_LINK_SOURCES = Object.freeze({
  TEARDOWN: 'teardown',           // remove-worktree.cjs at worktree teardown
  PUBLISH: 'publish',             // the publishing session, at merge time
  COMMIT_MESSAGE: 'commit-message', // Session-Id: line in the squash commit message
  SHARD_INFERENCE: 'shard-inference', // derived from an observation-shard add
});

export const DEFAULT_MERGE_LINK_SOURCE = MERGE_LINK_SOURCES.TEARDOWN;

/**
 * The evidence tier a source earns. Three-valued on purpose (856 §3.2 —
 * "absent evidence is not negative evidence", applied to provenance):
 *
 *   fact      — a known writer observed the link.
 *   inference — derived, with a measured error rate (856 §4).
 *   unknown   — an UNRECOGNISED source. Not fact.
 *
 * The third case is the one that matters. A ledger row carrying a foreign,
 * misspelled, or future `source` must not be laundered into the fact tier just
 * because it is not literally 'shard-inference'; that would let an unvetted
 * writer mint facts. `unknown` makes the gap legible instead.
 */
export function mergeLinkKind(source) {
  if (source === MERGE_LINK_SOURCES.SHARD_INFERENCE) return 'inference';
  if (Object.values(MERGE_LINK_SOURCES).includes(source)) return 'fact';
  return 'unknown';
}

/** Evidence tiers, weakest to strongest. Used to resolve a row that disagrees with itself. */
const TIER_RANK = Object.freeze({ unknown: 0, inference: 1, fact: 2 });

/**
 * Read-side normalizer: returns a copy of a ledger row with `source`/`kind`
 * guaranteed present. Absent `source` → the legacy meaning (every row written
 * before 856 is a teardown-written observation).
 *
 * WHEN `source` AND `kind` DISAGREE, THE WEAKER TIER WINS. Neither field can be
 * trusted to win outright:
 *
 * - Believing `kind` lets a row declare `source:'shard-inference', kind:'fact'`
 *   and be read as observed — the fact tier absorbing an inference at a
 *   measured ~8.9% error rate (856 §4), which is the whole failure §3.1 exists
 *   to prevent.
 * - Believing `source` lets a row declare `source:'teardown', kind:'inference'`
 *   and be UPGRADED to fact — normalization manufacturing confidence the writer
 *   explicitly disclaimed.
 *
 * Both are upgrades, and an upgrade is the only direction that can invent
 * evidence. Taking the minimum can only ever weaken a claim, so a disagreement
 * degrades to caution instead of resolving in favour of whichever field the
 * reader happened to trust. All three in-repo writers go through
 * buildMergeLinkRow, where the two agree by construction, so a disagreement
 * means a foreign, hand-edited, or corrupt row — exactly the case to be
 * conservative about.
 *
 * The discarded claim is surfaced as `kind_conflict` rather than silently
 * overwritten: a corrected claim is still a finding about the ledger (856 §7,
 * rejects are reported, not dropped).
 */
export function normalizeMergeLinkRow(row) {
  const source = row?.source ?? DEFAULT_MERGE_LINK_SOURCE;
  const fromSource = mergeLinkKind(source);
  const declared = row?.kind;
  const declaredIsWeaker = declared != null
    && TIER_RANK[declared] != null
    && TIER_RANK[declared] < TIER_RANK[fromSource];
  const kind = declaredIsWeaker ? declared : fromSource;
  const out = { ...row, source, kind };
  if (declared != null && declared !== kind) out.kind_conflict = declared;
  return out;
}

/**
 * Build one session→merge ledger row. Lives here rather than in any one
 * writer because THREE writers emit it (record-merge.mjs, merge-links.mjs,
 * recover-merge-links.mjs) and a second row shape would be exactly the fork
 * tempdoc 856 exists to remove.
 */
export function buildMergeLinkRow({
  sessionId, mergeCommit, subject,
  source = DEFAULT_MERGE_LINK_SOURCE, ts = new Date().toISOString(),
}) {
  if (!Object.values(MERGE_LINK_SOURCES).includes(source)) {
    throw new Error(`buildMergeLinkRow: unknown source '${source}' (expected one of ${Object.values(MERGE_LINK_SOURCES).join(', ')})`);
  }
  return {
    session_id: sessionId,
    merge_commit: mergeCommit,
    subject,
    ts,
    source,
    kind: mergeLinkKind(source),
  };
}

// Resolve repo root from this lib's location (lib/ → agent-analytics/ → scripts/ → repo)
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
export const repoRoot = path.resolve(scriptDir, '..', '..', '..');

/**
 * Load all events from events.ndjson (and the rotated .prev file).
 */
export function loadEvents() {
  const events = [];
  const dir = path.join(repoRoot, TELEMETRY_DIR);

  // Load both current and rotated file
  for (const filename of [EVENTS_FILE + '.prev', EVENTS_FILE]) {
    const filePath = path.join(dir, filename);
    if (!fs.existsSync(filePath)) continue;

    const content = fs.readFileSync(filePath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      try {
        events.push(JSON.parse(line));
      } catch {
        // Skip malformed lines
      }
    }
  }

  return events;
}

// --- OTLP ingest seam (tempdoc 622 Layer A) -------------------------------
// The native Claude Code OpenTelemetry stream is the canonical capture authority
// (projection-not-fork). `otlp-sink.py` decodes it to NDJSON under
// tmp/agent-telemetry/otlp/. loadEventsFromOtlp() normalizes those records into
// the SAME event shape dispatch.mjs produced, so the scorers need no changes.

export const OTLP_DIR = 'otlp';

// Matches otlp-sink.py's `_archive_regex(base)`: `<base>.<UTC-compact-
// timestamp>[_NN].ndjson`, e.g. `logs.2026-07-16T133648Z.ndjson` or
// `logs.2026-07-16T133648Z_01.ndjson`. Anchored on the literal base so
// base='logs' cannot match `logs-something-else.ndjson` or a different
// stream's archives (e.g. `metrics.*`).
function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function archiveRegex(base) {
  return new RegExp(`^${escapeRegExp(base)}\\.\\d{4}-\\d{2}-\\d{2}T\\d{6}Z(_\\d+)?\\.ndjson$`);
}

/**
 * Read a rotated OTLP stream — the legacy `<base>.prev.ndjson` (if still on
 * disk from before tempdoc 745), every `<base>.<timestamp>[_NN].ndjson`
 * archive sorted chronologically, then the current `<base>.ndjson` — oldest
 * first, mirroring loadEvents' dual-read. otlp-sink.py archives (never
 * deletes) each stream past 20 MB and keeps every archive unless its
 * RETENTION says otherwise, so a reader that only checked `.prev` would
 * silently drop every archive but the newest one (and desync logs vs
 * metrics). Exported for direct unit testing — the write path
 * (loadEventsFromOtlp/loadCostsFromOtlp) is otherwise the only caller.
 */
export function loadOtlpStream(dir, base) {
  const pattern = archiveRegex(base);
  let archiveNames = [];
  try {
    archiveNames = fs.readdirSync(dir).filter((name) => pattern.test(name));
  } catch {
    archiveNames = [];
  }
  archiveNames.sort();
  const files = [
    path.join(dir, `${base}.prev.ndjson`),
    ...archiveNames.map((name) => path.join(dir, name)),
    path.join(dir, `${base}.ndjson`),
  ];
  return files.flatMap((f) => loadNdjsonArray(f));
}

/**
 * Read the compact `ledger.ndjson` stream — otlp-sink.py's body-free
 * projection of the log stream (tempdoc 930 F2) — archives first, current
 * last, exactly like every other rotated stream.
 *
 * It exists as its own reader (rather than callers passing the literal
 * 'ledger' to `loadOtlpStream`) because the base name is the contract between
 * the sink and this module: logs.ndjson keeps 2 archives (~1 GB/active day of
 * embedded request bodies) while ledger.ndjson keeps 90 (~1 MB/day), so a
 * reader that wants tokens/cost/tool outcomes older than the last hour must
 * read THIS stream, and naming it here is what makes that discoverable.
 */
export function readOtlpLedger(dir) {
  return loadOtlpStream(dir, 'ledger');
}

/**
 * Reconstruct the dispatch.mjs-style `input_summary` from an OTLP `tool_input`
 * JSON string, so OTLP-sourced events match the legacy event shape the scorers
 * read (file_path / command / has_offset / *_string_length / …).
 */
function reconstructInputSummary(toolName, toolInputJson) {
  let ti = {};
  try { ti = JSON.parse(toolInputJson || '{}'); } catch { return { tool: toolName }; }
  switch (toolName) {
    case 'Bash':
      return { command: ti.command, description: ti.description ?? null,
               timeout: ti.timeout ?? null, run_in_background: ti.run_in_background ?? false };
    case 'Read':
      return { file_path: ti.file_path, has_offset: ti.offset != null, has_limit: ti.limit != null };
    case 'Edit':
      return { file_path: ti.file_path, old_string_length: (ti.old_string || '').length,
               new_string_length: (ti.new_string || '').length, replace_all: ti.replace_all ?? false };
    case 'Write':
      return { file_path: ti.file_path, content_length: (ti.content || '').length };
    case 'Grep':
      return { pattern: ti.pattern, path: ti.path ?? null, output_mode: ti.output_mode ?? null,
               type: ti.type ?? null, glob: ti.glob ?? null };
    case 'Glob':
      return { pattern: ti.pattern, path: ti.path ?? null };
    default:
      // MCP / Task / other: keep a minimal, non-content summary.
      return ti.subagent_type ? { tool: toolName, subagent_type: ti.subagent_type } : { tool: toolName };
  }
}

/**
 * Load events from the decoded OTLP log stream (and span durations) and
 * normalize them to the legacy analytics event schema.
 */
export function loadEventsFromOtlp() {
  const dir = path.join(repoRoot, TELEMETRY_DIR, OTLP_DIR);
  const logs = loadOtlpStream(dir, 'logs');

  // The rich tool input lives on the `tool_result` (post) log, but a reader of the
  // hook event stream expects `input_summary` on the `pre_tool_use` event. Pre-pass:
  // map tool_use_id -> reconstructed input_summary so we can attach it to BOTH the
  // pre_tool_use (tool_decision) and post_tool_use (tool_result) events.
  const inputByUseId = new Map();
  // model is carried by metrics, not logs; thread it onto a synthetic session_start.
  const modelBySession = (() => {
    const m = new Map();
    for (const rec of loadOtlpStream(dir, 'metrics')) {
      for (const p of rec.points || []) {
        const a = p.attributes || {};
        if (a['session.id'] && a.model && !m.has(a['session.id'])) m.set(a['session.id'], a.model);
      }
    }
    return m;
  })();
  for (const r of logs) {
    const a = r.attributes || {};
    if (a['event.name'] === 'tool_result' && a.tool_use_id) {
      inputByUseId.set(a.tool_use_id, reconstructInputSummary(a.tool_name, a.tool_input));
    }
  }

  const events = [];
  const seenSessionStart = new Set();
  for (const r of logs) {
    const a = r.attributes || {};
    const sid = a['session.id'];
    const ts = a['event.timestamp'];
    if (!sid) continue;
    // synthesize one session_start carrying the model, so reports populate `model`
    if (!seenSessionStart.has(sid)) {
      seenSessionStart.add(sid);
      events.push({ event: 'session_start', session_id: sid, ts,
        model: modelBySession.get(sid) ?? null, schema_version: 1, source: 'otlp' });
    }
    switch (a['event.name']) {
      case 'user_prompt':
        events.push({ event: 'user_prompt_submit', session_id: sid, ts,
          prompt_length: a.prompt_length != null ? Number(a.prompt_length) : null,
          prompt_excerpt: a.prompt ? String(a.prompt).slice(0, 200) : null,
          schema_version: 1, source: 'otlp' });
        break;
      case 'tool_result': {
        const ok = String(a.success) === 'true';
        events.push({ event: ok ? 'post_tool_use' : 'post_tool_use_failure',
          session_id: sid, ts, tool_name: a.tool_name,
          duration_ms: a.duration_ms != null ? Number(a.duration_ms) : undefined,
          tool_use_id: a.tool_use_id,
          input_summary: inputByUseId.get(a.tool_use_id) ?? reconstructInputSummary(a.tool_name, a.tool_input),
          ...(ok ? {} : { error_summary: a.error || '(tool failed)' }),
          schema_version: 1, source: 'otlp' });
        break;
      }
      case 'tool_decision':
        // attach the joined input_summary so pre_tool_use readers
        // (file_reads / unbounded / hot-file / edits) see the file_path + flags.
        events.push({ event: 'pre_tool_use', session_id: sid, ts, tool_name: a.tool_name,
          tool_use_id: a.tool_use_id, input_summary: inputByUseId.get(a.tool_use_id) ?? null,
          schema_version: 1, source: 'otlp' });
        break;
      case 'subagent_completed':
        events.push({ event: 'subagent_stop', session_id: sid, ts, schema_version: 1, source: 'otlp' });
        break;
      default:
        break; // hook_*, mcp_server_connection, api_request, plugin_loaded — not scored
    }
  }
  return events;
}

/**
 * Aggregate per-session cost + token usage from the decoded OTLP metric stream.
 * Returns Map<session_id, {cost_usd, input_tokens, output_tokens,
 * cache_read_tokens, cache_write_tokens, model, harness, by_source}>. This is
 * the canonical cost authority (the harness computes `claude_code.cost.usage`
 * directly) — it replaces transcript re-derivation (tempdoc 622 §9.3 fork).
 *
 * Token source preference (tempdoc 886 §12 PR 3): `otlp-sink.py` now writes a
 * `gen_ai.usage` normalised twin ADDITIVELY alongside every
 * `claude_code.token.usage` / `codex.turn.token_usage` point it decodes (the
 * originals are untouched). Summing both would double-count, so this reads
 * `gen_ai.usage` FIRST and remembers exactly which origin points it already
 * covered (keyed on session + time_unix_nano + the origin point's own raw
 * `type`/`token_type` attribute value, which `otlp-sink.py`'s
 * `_genai_normalize` passes through verbatim onto the normalised twin) —
 * the second pass over `claude_code.token.usage` skips any point whose key
 * is already consumed. Archives written before this change carry no
 * `gen_ai.usage` records at all, so the consumed-key set stays empty for
 * them and the fallback runs exactly as before (no behaviour change for old
 * data). `codex.turn.token_usage` is still not read directly here — its
 * tokens flow ONLY through `gen_ai.usage` (Codex has no dedicated
 * cost-authority metric to preserve field-parity with, unlike Claude's
 * `claude_code.cost.usage`).
 *
 * Codex `input`-includes-`cache_read` correction (independent review
 * SHOULD-FIX 1): a Codex `gen_ai.usage` point whose `gen_ai.token.kind` is
 * `input` and whose `gen_ai.input_includes_cache_read` flag is set carries
 * the RAW input count (input + cached), not a fresh one — summing it
 * straight into `input_tokens` alongside `cache_read_tokens` would
 * double-count the cached portion in any downstream `input + output +
 * cache_write + cache_read` total (e.g. `baseline-economics.mjs`,
 * `overhead-taxonomy.mjs`). This function resolves FRESH input (`input -
 * cache_read`) by pairing the flagged `input` point with its `cache_read`
 * sibling for the same `session.id` + `time_unix_nano` — buffered in either
 * arrival order, since stream order between the two is not guaranteed. If
 * the `cache_read` sibling never shows up (e.g. malformed/partial export),
 * the raw `input` value is kept AS-IS — never silently subtracted from
 * nothing — and `rec.input_includes_cache_read` is set so a caller can tell
 * that session's `input_tokens` is not already fresh.
 *
 * `dir` defaults to the live sink's real output directory; tests pass a
 * temp directory (same pattern `loadOtlpStream` already exposes for direct
 * unit testing) so no test ever reads or writes `tmp/agent-telemetry/otlp/`.
 */
export function loadCostsFromOtlp(dir = path.join(repoRoot, TELEMETRY_DIR, OTLP_DIR)) {
  const metrics = loadOtlpStream(dir, 'metrics');
  const TOKEN_FIELD = { input: 'input_tokens', output: 'output_tokens',
    cacheRead: 'cache_read_tokens', cacheCreation: 'cache_write_tokens' };
  // gen_ai.token.kind vocabulary (886 §12 PR 3) mapped onto this function's
  // pre-existing Claude-shaped four-axis field set. 'reasoning' (Codex-only,
  // no Claude counterpart) has no field to fold into and is intentionally
  // left unmapped here — a reader wanting the reasoning axis reads the
  // neutral `lib/ledger/` record instead, not this legacy cost shape.
  const GENAI_FIELD = { input: 'input_tokens', output: 'output_tokens',
    cache_read: 'cache_read_tokens', cache_creation: 'cache_write_tokens' };
  const map = new Map();
  const ensure = (sid) => {
    if (!map.has(sid)) map.set(sid, { session_id: sid, cost_usd: 0,
      input_tokens: 0, output_tokens: 0, cache_read_tokens: 0, cache_write_tokens: 0,
      model: null, harness: null, hasCostMetric: false, input_includes_cache_read: false,
      by_source: {} });
    return map.get(sid);
  };

  // Pass 1: gen_ai.usage normalised records are the preferred source.
  const consumedKeys = new Set();
  // Buffers for the input/cache_read pairing described above, keyed on
  // `${sid}|${time_unix_nano}` (independent of which side arrives first).
  const pendingRawInput = new Map(); // key -> { rec, value }
  const pendingCacheRead = new Map(); // key -> value
  for (const m of metrics) {
    if (m.name !== 'gen_ai.usage') continue;
    const a = m.attributes || {};
    const sid = a['session.id'];
    if (!sid) continue;
    const rec = ensure(sid);
    if (a['gen_ai.request.model']) rec.model = a['gen_ai.request.model'];
    if (a['gen_ai.system']) rec.harness = a['gen_ai.system'];
    const src = a.query_source || 'main';
    rec.by_source[src] ??= { cost_usd: 0, output_tokens: 0 };
    const kind = a['gen_ai.token.kind'];
    const rawType = a.type ?? a.token_type;
    const value = m.value || 0;
    const pairKey = `${sid}|${m.time_unix_nano}`;
    consumedKeys.add(`${pairKey}|${rawType}`);

    if (kind === 'input' && a['gen_ai.input_includes_cache_read']) {
      const cached = pendingCacheRead.get(pairKey);
      if (cached != null) {
        rec.input_tokens += value - cached;
        pendingCacheRead.delete(pairKey);
      } else {
        pendingRawInput.set(pairKey, { rec, value });
      }
      continue;
    }
    if (kind === 'cache_read') {
      const pending = pendingRawInput.get(pairKey);
      if (pending != null) {
        pending.rec.input_tokens += pending.value - value;
        pendingRawInput.delete(pairKey);
      }
      rec.cache_read_tokens += value;
      continue;
    }

    const field = GENAI_FIELD[kind];
    if (field) rec[field] += value;
    if (kind === 'output') rec.by_source[src].output_tokens += value;
  }
  // A raw `input` point whose `cache_read` sibling never arrived: keep it
  // RAW (do not fabricate a subtraction) and flag the session.
  for (const { rec, value } of pendingRawInput.values()) {
    rec.input_tokens += value;
    rec.input_includes_cache_read = true;
  }

  // Pass 2: claude_code.cost.usage (never normalised — gen_ai.usage covers
  // only token metrics, not cost) and claude_code.token.usage points not
  // already consumed by a pass-1 normalised twin.
  for (const m of metrics) {
    for (const p of m.points || []) {
      const a = p.attributes || {};
      const sid = a['session.id'];
      if (!sid) continue;
      const rec = ensure(sid);
      if (a.model) rec.model = a.model;
      const src = a.query_source || 'main';
      rec.by_source[src] ??= { cost_usd: 0, output_tokens: 0 };
      if (m.name === 'claude_code.cost.usage') {
        rec.cost_usd += p.value || 0;
        rec.by_source[src].cost_usd += p.value || 0;
        rec.hasCostMetric = true;
      } else if (m.name === 'claude_code.token.usage') {
        const key = `${sid}|${p.time_unix_nano}|${a.type}`;
        if (consumedKeys.has(key)) continue;
        const field = TOKEN_FIELD[a.type];
        if (field) rec[field] += p.value || 0;
        if (a.type === 'output') rec.by_source[src].output_tokens += p.value || 0;
      }
    }
  }
  for (const rec of map.values()) rec.cost_usd = round(rec.cost_usd, 4);
  return map;
}

/**
 * Pluggable event ingest. `'ndjson'` (default) reads the legacy dispatch.mjs
 * stream; `'otlp'` reads the native-OTel-derived stream (tempdoc 622 Layer A).
 * During the parallel-run transition both sources exist; callers pick one.
 */
export function loadEventsFromSource(source = 'ndjson') {
  if (source === 'otlp') return loadEventsFromOtlp();
  return loadEvents();
}

/**
 * Group events by session_id into a Map<string, Event[]>.
 */
export function groupBySession(events) {
  const sessions = new Map();
  for (const event of events) {
    const sid = event.session_id;
    if (!sid) continue;
    if (!sessions.has(sid)) sessions.set(sid, []);
    sessions.get(sid).push(event);
  }
  return sessions;
}

/**
 * Load an NDJSON file into an array of parsed records.
 */
export function loadNdjsonArray(filePath) {
  const records = [];
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      try { records.push(JSON.parse(line)); } catch { /* skip */ }
    }
  } catch { /* file doesn't exist */ }
  return records;
}

/**
 * Load an NDJSON file into a Map keyed by session_id.
 */
export function loadNdjsonMap(filePath) {
  const map = new Map();
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      try {
        const record = JSON.parse(line);
        if (record.session_id) map.set(record.session_id, record);
      } catch { /* skip */ }
    }
  } catch { /* file doesn't exist */ }
  return map;
}

/**
 * Round a number to the given number of decimal places.
 */
export function round(n, decimals = 3) {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}
