/**
 * lib/ledger/codex-adapter.mjs — OpenAI Codex CLI rollout transcripts
 * projected onto the neutral `Call`/`ToolEvent` shape (tempdoc 886 §12 PR 1,
 * independent-review fix-up).
 *
 * Neutral-ledger source: `${codexHome}/sessions/**\/rollout-*.jsonl`
 * (recursing the `YYYY/MM/DD` layout). `archived_sessions` is skipped wherever
 * it appears in that walk. The raw attribution reader intentionally has a
 * wider source contract: it reads both active and archived fragments and
 * performs event-time snapshotting/deduplication itself.
 *
 * Every rule below is from tempdoc 886 §11's derisk pass (A1/A2/A7), verified
 * against the real corpus (51,740 `token_count` events, 289 sessions) before
 * this adapter was written — not re-derived here:
 *
 *   A1  `last_token_usage.input_tokens` INCLUDES `cached_input_tokens` (the
 *       OpenAI convention, confirmed: 0 of 51,740 events have cached > input).
 *       So `contextTokens = input_tokens`, `fresh = input_tokens - cached_input_tokens`.
 *   A2  `last_token_usage` is a per-call DELTA, not a running total — but a
 *       `token_count` event is a REPEAT (not a new call) when its
 *       `total_token_usage.total_tokens` exactly equals the previous kept
 *       event's; 1,482 such repeats exist corpus-wide and must be dropped, or
 *       every reader downstream double-counts a call that never happened.
 *       NEVER read `total_token_usage` as if it were per-call — a resumed
 *       thread's cumulative counter carries prior history forward, so it
 *       diverges from the sum of deltas by construction, not by bug (see
 *       `selfCheck` below).
 *   A7  `function_call`/`custom_tool_call` (+ their `_output` siblings) and
 *       `inter_agent_communication_metadata` are present and richer than
 *       assumed pre-derisk — tool outputs run up to 751k chars, hence the
 *       64k-char cap here (`OUTPUT_CHAR_CAP`).
 *
 * A `rate_limits`-only `token_count` event (`info: null`) carries no usage at
 * all and is skipped outright, not counted as a repeat.
 *
 *   A8  NATIVE PER-RESPONSE USAGE (tempdoc 951). Current Codex rollouts write a
 *       `type: "token_usage_record"` line per model response, whose
 *       `payload.usage` is that response's own usage and whose
 *       `payload.response_id` is its identity. That stream is the better
 *       source: `token_count` is a UI notification, so a response the CLI never
 *       notified on is simply absent from it. Verified on a real explorer
 *       rollout (38 native records vs 40 `token_count` events, 1 `compacted`):
 *       the compaction-summarizer response (input 234,478 / cached 232,192)
 *       has a native record and NO `token_count`, so the legacy input total
 *       (5,235,254) understates the native total (5,469,732) by exactly that
 *       one record.
 *
 *       Therefore: if a rollout has ANY usable native record, this adapter
 *       builds every `Call` from the native stream and builds NONE from
 *       `token_count`; a rollout with none falls back to the legacy A1/A2 path
 *       unchanged. The two streams are NEVER summed: that would double-count
 *       every response present in both. `token_count` is still read on the
 *       native path, but only to keep the legacy `selfCheck` diagnostics
 *       (`deltaInputSum`/`maxCumulativeInput`/`resets`/`repeatsDropped`)
 *       comparable across both paths; `selfCheck.usageSource` names which
 *       stream produced the Calls. `usage.input_tokens` follows the same A1
 *       convention (it already includes `cached_input_tokens`).
 *       `response_id` is deduplicated first-wins (the corpus has no duplicate
 *       within a file; the drop count is reported rather than assumed zero),
 *       and a record missing `usage` or `response_id` is ignored outright and
 *       does not by itself select the native path.
 *
 *       The `compacted` semantics are unchanged and now ride the native
 *       stream: the first call AFTER a `compacted` line carries
 *       `compactionBoundary: true`, and the synthetic zero-token boundary Call
 *       below is emitted only when nothing at all follows it.
 *
 * LINEAGE: current Codex child rollouts carry an explicit parent edge in
 * `session_meta.payload.source.subagent.thread_spawn`, including
 * `parent_thread_id`, `agent_role`, and `agent_path`. Those sessions produce
 * `lineage.kind = 'spawn'`; sessions without that evidence remain `main`.
 * `inter_agent_communication_metadata` still names no parent and remains only
 * the session-level `multiAgent` fact. `turn_context.payload.effort` supplies
 * the actual reasoning effort used for each call.
 *
 * TOOL EVENTS (independent review fix): `agent_message` payloads are plain
 * assistant reply text, not tool activity, so this adapter no longer creates
 * a `ToolEvent` for them (`lib/ledger/tool-roles.mjs` still maps the NAME to
 * a role, for table completeness, independent of whether this adapter emits
 * it).
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { makeCall, makeToolEvent } from './record.mjs';
import { roleFor } from './tool-roles.mjs';

export const DEFAULT_CODEX_HOME = path.join(os.homedir(), '.codex');
const OUTPUT_CHAR_CAP = 65536;
const ARCHIVED_DIR_NAME = 'archived_sessions';

function walkRolloutFiles(dir, out, errors = null) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (error) {
    if (errors) errors.push({ path: dir, code: error.code ?? 'READ_ERROR' });
    return false;
  }
  for (const e of entries) {
    if (e.name === ARCHIVED_DIR_NAME) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      walkRolloutFiles(p, out, errors);
    } else if (e.isFile() && e.name.startsWith('rollout-') && e.name.endsWith('.jsonl')) {
      out.push(p);
    }
  }
  return true;
}

function parseLinesWithDiagnostics(file) {
  let content;
  try {
    content = fs.readFileSync(file, 'utf8');
  } catch (error) {
    return { entries: [], malformedLines: 0, readError: error };
  }
  const entries = [];
  let malformedLines = 0;
  for (const raw of content.split('\n')) {
    if (!raw.trim()) continue;
    try {
      entries.push(JSON.parse(raw));
    } catch {
      // truncated/corrupt line — skip it, matching every other reader's per-line tolerance
      malformedLines += 1;
    }
  }
  return { entries, malformedLines, readError: null };
}

function parseLines(file) {
  return parseLinesWithDiagnostics(file).entries;
}

function outputStringOf(payload) {
  const out = payload.output;
  if (typeof out === 'string') return out;
  try {
    return JSON.stringify(out ?? '');
  } catch {
    return '';
  }
}

/**
 * Flatten the text-bearing portions of a Codex tool result without retaining
 * its wrapper shape. Desktop custom tools commonly return an object whose
 * numeric keys contain `input_text` blocks, while CLI tools more often return
 * a plain string. Attribution readers need the text itself; the neutral
 * ToolEvent above deliberately keeps only its capped size.
 */
export function codexToolOutputText(output) {
  const pieces = [];
  const seen = new Set();

  function visit(value) {
    if (typeof value === 'string') {
      pieces.push(value);
      return;
    }
    if (value == null || typeof value !== 'object' || seen.has(value)) return;
    seen.add(value);

    if (Array.isArray(value)) {
      for (const item of value) visit(item);
      return;
    }

    if (typeof value.text === 'string') {
      pieces.push(value.text);
      return;
    }
    if (Object.hasOwn(value, 'output')) {
      visit(value.output);
      return;
    }
    if (Object.hasOwn(value, 'content')) {
      visit(value.content);
      return;
    }

    const numericKeys = Object.keys(value)
      .filter((key) => /^\d+$/.test(key))
      .sort((a, b) => Number(a) - Number(b));
    if (numericKeys.length > 0) {
      for (const key of numericKeys) visit(value[key]);
      return;
    }

    // Unknown structured outputs are uncommon, but JSON text is a more useful
    // fail-closed representation than silently discarding them.
    try {
      pieces.push(JSON.stringify(value));
    } catch {
      // Cyclic/host objects are not valid rollout JSON; ignore only this leaf.
    }
  }

  visit(output);
  return pieces.join('\n');
}

function inputStringOf(payload) {
  if (payload.type === 'function_call') return payload.arguments ?? '';
  const input = payload.input;
  return typeof input === 'string' ? input : JSON.stringify(input ?? '');
}

/**
 * The ONE documented, deliberately-handled failure mode: a rollout file with
 * no usable `sessionId`. `makeCall`/`makeToolEvent` both require one, so a
 * file with no `session_meta` line (or a `session_meta` whose `payload.id`
 * is missing/empty) cannot produce a valid record — this is detected UP
 * FRONT, before any `Call` is attempted, and returned as an explicit skip
 * signal rather than caught after the fact. This is the only skip path;
 * `listCodexCalls` does not wrap the rest of parsing in a try/catch, so any
 * OTHER exception (a genuine bug) propagates instead of being silently
 * swallowed (independent review, 886 §12 PR 1 fix-up — the previous version
 * caught everything here, which also hid real bugs).
 */
function findSessionMetadata(entries) {
  return entries.find((e) => e.type === 'session_meta')?.payload ?? null;
}

function findSessionId(entries) {
  return findSessionMetadata(entries)?.id || null;
}

function lineageFromSessionMetadata(metadata) {
  const spawn = metadata?.source?.subagent?.thread_spawn;
  const parentSessionId = spawn?.parent_thread_id ?? metadata?.parent_thread_id ?? null;
  if (!spawn || !parentSessionId) {
    return { parentSessionId: null, kind: 'main' };
  }
  return {
    parentSessionId,
    kind: 'spawn',
    agentType: spawn.agent_role ?? metadata.agent_role ?? null,
    requestedModel: null,
    description: spawn.agent_path ?? metadata.agent_path ?? null,
  };
}

/**
 * Pair raw Codex tool inputs with their full text outputs. This is the narrow
 * escape hatch for attribution readers whose question cannot be answered from
 * the privacy-safer, size-only ToolEvent projection. Callers must aggregate in
 * memory and must not persist `input` or `outputText`.
 */
export function processCodexToolExchanges(entries, { file } = {}) {
  const sessionId = findSessionId(entries);
  if (!sessionId) {
    return { exchanges: [], session: null, skip: { file, reason: 'no usable sessionId (missing or empty session_meta.payload.id)' } };
  }

  const metaEntry = entries.find((entry) => entry.type === 'session_meta');
  const project = metaEntry?.payload?.cwd ?? null;
  const pendingByCallId = new Map();
  const exchanges = [];

  for (const entry of entries) {
    if (entry.type !== 'response_item' || !entry.payload) continue;
    const p = entry.payload;

    if (p.type === 'function_call' || p.type === 'custom_tool_call') {
      pendingByCallId.set(p.call_id, {
        sessionId,
        project,
        file,
        callId: p.call_id,
        name: p.name,
        input: inputStringOf(p),
        outputText: null,
        rawOutputChars: null,
        missingOutput: true,
        outputTimestampUnknown: false,
        startedTs: entry.timestamp ?? null,
        completedTs: null,
      });
      continue;
    }

    if (p.type === 'function_call_output' || p.type === 'custom_tool_call_output') {
      const pending = pendingByCallId.get(p.call_id);
      if (!pending) continue;
      exchanges.push({
        ...pending,
        outputText: codexToolOutputText(p.output),
        rawOutputChars: outputStringOf(p).length,
        missingOutput: false,
        outputTimestampUnknown: timestampMs(entry.timestamp) == null,
        completedTs: entry.timestamp ?? null,
      });
      pendingByCallId.delete(p.call_id);
    }
  }

  // A read-like call at EOF with no result is evidence too. Preserve it so an
  // attribution reader can distinguish "never returned" from "returned no
  // matching bytes" instead of silently dropping the attempt.
  exchanges.push(...pendingByCallId.values());
  return {
    exchanges,
    session: { harness: 'codex-cli', sessionId, project },
    skip: null,
  };
}

/**
 * Build `{calls, toolEvents, session}` from an already-parsed entries array.
 * Exported (not just `processCodexFile`) so a test can feed it a crafted
 * entries array directly — including one designed to throw — without going
 * through `JSON.parse`, which can never produce the shapes needed to
 * exercise a genuine propagation path (no getters/proxies survive a JSON
 * round-trip). `file` is carried through only for the skip-reason record.
 */
export function processCodexEntries(entries, { file } = {}) {
  const sessionMetadata = findSessionMetadata(entries);
  const sessionId = sessionMetadata?.id || null;
  if (!sessionId) {
    return { calls: [], toolEvents: [], session: null, skip: { file, reason: 'no usable sessionId (missing or empty session_meta.payload.id)' } };
  }

  // `inter_agent_communication_metadata` marks the SESSION, not a lineage
  // edge for any one call (see module doc) — pre-scanned so a line appearing
  // late in the file still sets the session-level flag correctly regardless
  // of where in a single sequential pass it is encountered.
  const multiAgent = entries.some((e) => e.type === 'inter_agent_communication_metadata');

  // A8: one pre-scan decides the usage stream for the WHOLE file, so a rollout
  // whose first native record appears late still never mixes the two streams.
  const hasNative = entries.some((e) => e.type === 'token_usage_record'
    && e.payload?.usage && e.payload?.response_id);

  const calls = [];
  const toolEvents = [];
  const pendingByCallId = new Map();

  let project = null;
  let provider = 'openai';
  let currentModel = null;
  let currentReasoningEffort = null;
  let compactionPending = false;
  let compactedTs = null;
  let prevCumulativeTotal = null;
  let prevCumulativeInput = null;
  let index = 0;
  let firstTs = null;
  let lastTs = null;
  let deltaInputSum = 0;
  let maxCumulativeInput = 0;
  let resets = 0;
  let repeatsDropped = 0;
  let nativeRecords = 0;
  let nativeDuplicatesDropped = 0;
  const seenResponseIds = new Set();

  const lineage = lineageFromSessionMetadata(sessionMetadata);

  for (const entry of entries) {
    const ts = entry.timestamp ?? null;
    if (ts) {
      if (!firstTs) firstTs = ts;
      lastTs = ts;
    }

    if (entry.type === 'session_meta') {
      project = entry.payload?.cwd ?? project;
      provider = entry.payload?.model_provider ?? provider;
      continue;
    }

    if (entry.type === 'turn_context') {
      currentModel = entry.payload?.model ?? currentModel;
      currentReasoningEffort = entry.payload?.effort ?? currentReasoningEffort;
      continue;
    }

    if (entry.type === 'inter_agent_communication_metadata') {
      continue; // pre-scanned above; nothing to do per-line
    }

    if (entry.type === 'compacted') {
      compactionPending = true;
      compactedTs = ts;
      continue;
    }

    if (entry.type === 'token_usage_record') {
      if (!hasNative) continue; // no usable record in this file at all
      const usage = entry.payload?.usage;
      const responseId = entry.payload?.response_id;
      if (!usage || !responseId) continue; // A8: unusable record, ignored outright
      if (seenResponseIds.has(responseId)) {
        nativeDuplicatesDropped += 1;
        continue; // A8: first record for a response_id wins
      }
      seenResponseIds.add(responseId);
      nativeRecords += 1;

      const boundary = compactionPending;
      if (boundary) compactionPending = false;

      calls.push(makeCall({
        harness: 'codex-cli',
        provider,
        project,
        sessionId,
        callId: `${sessionId}:${index}`,
        lineage,
        ts,
        model: currentModel,
        reasoningEffort: currentReasoningEffort,
        tokens: {
          fresh: Math.max(0, (usage.input_tokens ?? 0) - (usage.cached_input_tokens ?? 0)),
          cacheRead: usage.cached_input_tokens ?? 0,
          // Codex still has no BILLABLE cache write, so this axis stays null
          // even though the native record carries `cache_write_input_tokens`
          // (record.mjs: an absent axis is null, never 0).
          cacheWrite5m: null,
          cacheWrite1h: null,
          output: usage.output_tokens ?? 0,
          reasoning: usage.reasoning_output_tokens ?? 0,
        },
        contextTokens: usage.input_tokens ?? 0, // A1: input_tokens already includes cached
        compactionBoundary: boundary,
      }));
      index += 1;
      continue;
    }

    if (entry.type === 'event_msg') {
      const p = entry.payload;

      if (p?.type === 'token_count') {
        if (!p.info) continue; // rate_limits-only event — no usage at all
        const L = p.info.last_token_usage;
        const T = p.info.total_token_usage;
        if (!L || !T) continue;

        const curInput = T.input_tokens ?? 0;
        if (curInput > maxCumulativeInput) maxCumulativeInput = curInput;
        if (prevCumulativeInput != null && curInput < prevCumulativeInput) resets += 1;
        prevCumulativeInput = curInput;

        if (prevCumulativeTotal != null && T.total_tokens === prevCumulativeTotal) {
          repeatsDropped += 1;
          prevCumulativeTotal = T.total_tokens;
          continue; // A2: exact repeat of the previous cumulative — not a new call
        }
        prevCumulativeTotal = T.total_tokens;
        deltaInputSum += L.input_tokens ?? 0;

        // A8: on the native path the diagnostics above still run (so both
        // paths report comparable selfCheck numbers), but the native stream
        // owns Call creation and `compactionPending`; never sum both.
        if (hasNative) continue;

        const fresh = Math.max(0, (L.input_tokens ?? 0) - (L.cached_input_tokens ?? 0));
        const boundary = compactionPending;
        if (boundary) compactionPending = false;

        calls.push(makeCall({
          harness: 'codex-cli',
          provider,
          project,
          sessionId,
          callId: `${sessionId}:${index}`,
          lineage,
          ts,
          model: currentModel,
          reasoningEffort: currentReasoningEffort,
          tokens: {
            fresh,
            cacheRead: L.cached_input_tokens ?? 0,
            cacheWrite5m: null,
            cacheWrite1h: null,
            output: L.output_tokens ?? 0,
            reasoning: L.reasoning_output_tokens ?? 0,
          },
          contextTokens: L.input_tokens ?? 0, // A1: input_tokens already includes cached
          compactionBoundary: boundary,
        }));
        index += 1;
        continue;
      }

      // agent_message: deliberately NOT turned into a ToolEvent (see module doc).
      continue;
    }

    if (entry.type === 'response_item') {
      const p = entry.payload;
      if (!p) continue;

      if (p.type === 'function_call' || p.type === 'custom_tool_call') {
        const inputStr = inputStringOf(p);
        pendingByCallId.set(p.call_id, {
          name: p.name,
          role: roleFor('codex-cli', p.name),
          inputChars: inputStr.length,
        });
        continue;
      }

      if (p.type === 'function_call_output' || p.type === 'custom_tool_call_output') {
        const outStr = outputStringOf(p);
        const truncated = outStr.length > OUTPUT_CHAR_CAP;
        const pending = pendingByCallId.get(p.call_id);
        toolEvents.push(makeToolEvent({
          harness: 'codex-cli',
          sessionId,
          callRef: null,
          role: pending?.role ?? 'other',
          name: pending?.name ?? '(unknown)',
          inputChars: pending?.inputChars ?? 0,
          outputChars: Math.min(outStr.length, OUTPUT_CHAR_CAP),
          isError: false,
          ts,
          truncated: truncated || undefined,
        }));
        if (pending) pendingByCallId.delete(p.call_id);
        continue;
      }
    }
  }

  // A `compacted` line with no following call on the ACTIVE usage stream (a
  // `token_usage_record` under A8, else a `token_count` event) still marks a
  // real boundary. Rather than drop it silently, emit a synthetic zero-token Call
  // carrying `compactionBoundary: true` AND `synthetic: true` — the
  // documented choice from the brief's either/or (886 §12 PR 1): a boundary
  // the ledger never saw a call for is still a boundary a reader
  // (context-residency.mjs, PR 2) needs to see, and a synthetic zero-cost
  // Call, clearly flagged as such, is cheaper for readers to handle than a
  // second "orphan boundary" concept.
  if (compactionPending) {
    calls.push(makeCall({
      harness: 'codex-cli',
      provider,
      project,
      sessionId,
      callId: `${sessionId}:${index}`,
      lineage,
      ts: compactedTs,
      model: currentModel,
      reasoningEffort: currentReasoningEffort,
      tokens: { fresh: 0, cacheRead: 0, cacheWrite5m: null, cacheWrite1h: null, output: 0, reasoning: 0 },
      contextTokens: 0,
      compactionBoundary: true,
      synthetic: true,
    }));
    index += 1;
  }

  return {
    calls,
    toolEvents,
    session: {
      harness: 'codex-cli',
      sessionId,
      project,
      firstTs,
      lastTs,
      calls: calls.length,
      multiAgent,
      lineage,
      selfCheck: {
        deltaInputSum,
        maxCumulativeInput,
        resets,
        repeatsDropped,
        usageSource: hasNative ? 'native' : 'legacy',
        nativeRecords,
        nativeDuplicatesDropped,
      },
    },
    skip: null,
  };
}

/** Parse one rollout file and build its `{calls, toolEvents, session, skip}`. */
function processCodexFile(file) {
  const entries = parseLines(file);
  return processCodexEntries(entries, { file });
}

function rolloutFilesInWindow({ codexHome, sinceMs, untilMs }) {
  const home = codexHome ?? DEFAULT_CODEX_HOME;
  const sessionsRoot = path.join(home, 'sessions');
  const discovered = [];
  walkRolloutFiles(sessionsRoot, discovered);

  const files = [];
  for (const file of discovered) {
    let stat;
    try {
      stat = fs.statSync(file);
    } catch {
      continue;
    }
    if (sinceMs != null && stat.mtimeMs < sinceMs) continue;
    if (untilMs != null && stat.mtimeMs > untilMs) continue;
    files.push(file);
  }
  return files;
}

function rawRolloutFiles(codexHome) {
  const home = codexHome ?? DEFAULT_CODEX_HOME;
  const discovered = [];
  const sourceRootErrors = [];
  let sourceRootsAvailable = 0;
  let sourceRootsMissing = 0;
  for (const root of [path.join(home, 'sessions'), path.join(home, ARCHIVED_DIR_NAME)]) {
    const walkErrors = [];
    if (walkRolloutFiles(root, discovered, walkErrors)) {
      sourceRootsAvailable += 1;
      sourceRootErrors.push(...walkErrors);
      continue;
    }
    const missingRoot = walkErrors.some((error) => error.path === root && error.code === 'ENOENT');
    if (missingRoot) sourceRootsMissing += 1;
    sourceRootErrors.push(...walkErrors.filter((error) => !(error.path === root && error.code === 'ENOENT')));
  }
  return {
    files: [...new Set(discovered)].sort(),
    sourceRootsAvailable,
    sourceRootsMissing,
    sourceRootErrors,
  };
}

function timestampMs(value) {
  const millis = Date.parse(value ?? '');
  return Number.isFinite(millis) ? millis : null;
}

function entriesAsOf(entries, untilMs) {
  if (untilMs == null) return entries;
  return entries.filter((entry) => {
    const millis = timestampMs(entry?.timestamp);
    if (entry?.type === 'session_meta') return millis == null || millis <= untilMs;
    if (millis == null && entry?.type === 'response_item'
      && (entry.payload?.type === 'function_call_output' || entry.payload?.type === 'custom_tool_call_output')) {
      return true;
    }
    return millis != null && millis <= untilMs;
  });
}

function exchangeStartInWindow(exchange, sinceMs, untilMs) {
  const millis = timestampMs(exchange.startedTs);
  if (millis == null) return false;
  if (sinceMs != null && millis < sinceMs) return false;
  if (untilMs != null && millis > untilMs) return false;
  return true;
}

function exchangeIdentity(exchange) {
  return `${exchange.sessionId}\u0000${exchange.callId ?? ''}\u0000${timestampMs(exchange.startedTs) ?? 'invalid'}`;
}

function exchangeContentSignature(exchange) {
  return JSON.stringify([
    exchange.name ?? null,
    exchange.input ?? null,
    exchange.outputText ?? null,
    exchange.missingOutput,
    exchange.outputTimestampUnknown,
    timestampMs(exchange.completedTs),
  ]);
}

/**
 * Every Codex CLI `Call`/`ToolEvent` this machine holds, across every
 * session under `${codexHome}/sessions`, in the `sinceMs`/`untilMs` mtime
 * window (file mtime — same posture as the Claude adapter/transcript-store,
 * not a per-line timestamp scan). Never throws on a missing `~/.codex`
 * root, and never throws on the ONE documented per-file skip condition
 * (no usable sessionId) — those are collected into `skipped`. Any OTHER
 * exception during parsing (a genuine bug, not a documented degrade case)
 * PROPAGATES — this function does not swallow it.
 */
export function listCodexCalls({ codexHome, sinceMs = null, untilMs = null, projectFilter = null } = {}) {
  const files = rolloutFilesInWindow({ codexHome, sinceMs, untilMs });

  const calls = [];
  const toolEvents = [];
  const sessions = [];
  const skipped = [];

  for (const file of files) {
    const result = processCodexFile(file);
    if (result.skip) {
      skipped.push(result.skip);
      continue;
    }
    if (projectFilter && !(result.session.project && projectFilter.test(result.session.project))) continue;

    calls.push(...result.calls);
    toolEvents.push(...result.toolEvents);
    sessions.push(result.session);
  }

  return { calls, toolEvents, sessions, skipped };
}

/**
 * Full raw tool exchanges for Codex attribution readers. Unlike the neutral
 * ledger, this includes both active and archived rollout fragments and uses
 * exchange-start event time rather than file mtime. `untilMs` is an as-of
 * boundary: entries after it are removed before calls and outputs are paired,
 * so later appends cannot rewrite a fixed-window missing-output observation.
 * Duplicate fragment copies are unioned by session/call/start identity.
 * Outputs are not capped. Nothing is written to disk here.
 */
export function listCodexToolExchanges({ codexHome, sinceMs = null, untilMs = null, projectFilter = null } = {}) {
  const discovery = rawRolloutFiles(codexHome);
  const exchangeStates = new Map();
  const sessionsById = new Map();
  const skipped = [];
  let fragmentsDiscovered = 0;
  let fragmentsContributing = 0;
  let unreadableFragments = 0;
  let malformedLines = 0;
  let untimestampedExchanges = 0;
  let untimestampedOutputs = 0;
  let duplicateExchangeCopies = 0;
  let conflictingExchangeCopies = 0;

  for (const file of discovery.files) {
    const parsed = parseLinesWithDiagnostics(file);
    malformedLines += parsed.malformedLines;
    if (parsed.readError) {
      unreadableFragments += 1;
      skipped.push({ file, reason: `could not read rollout: ${parsed.readError.code ?? parsed.readError.message}` });
      continue;
    }
    const allEntries = parsed.entries;
    const entries = entriesAsOf(allEntries, untilMs);
    if (entries.length === 0) continue;
    fragmentsDiscovered += 1;
    const result = processCodexToolExchanges(entries, { file });
    if (result.skip) {
      const hasInWindowEvent = entries.some((entry) => {
        const millis = timestampMs(entry?.timestamp);
        return millis != null
          && (sinceMs == null || millis >= sinceMs)
          && (untilMs == null || millis <= untilMs);
      });
      if (hasInWindowEvent) skipped.push(result.skip);
      continue;
    }
    if (projectFilter && !(result.session.project && projectFilter.test(result.session.project))) continue;

    untimestampedExchanges += allEntries.filter((entry) => (
      entry?.type === 'response_item'
      && (entry.payload?.type === 'function_call' || entry.payload?.type === 'custom_tool_call')
      && timestampMs(entry.timestamp) == null
    )).length;
    untimestampedOutputs += allEntries.filter((entry) => (
      entry?.type === 'response_item'
      && (entry.payload?.type === 'function_call_output' || entry.payload?.type === 'custom_tool_call_output')
      && timestampMs(entry.timestamp) == null
    )).length;

    const inWindow = [];
    for (const exchange of result.exchanges) {
      if (timestampMs(exchange.startedTs) == null) continue;
      if (exchangeStartInWindow(exchange, sinceMs, untilMs)) inWindow.push(exchange);
    }
    if (inWindow.length === 0) continue;
    fragmentsContributing += 1;
    if (!sessionsById.has(result.session.sessionId)) sessionsById.set(result.session.sessionId, result.session);

    for (const exchange of inWindow) {
      const key = exchangeIdentity(exchange);
      const signature = exchangeContentSignature(exchange);
      const state = exchangeStates.get(key);
      if (!state) {
        exchangeStates.set(key, { exchange, signature, conflicted: false });
        continue;
      }
      duplicateExchangeCopies += 1;
      if (state.signature !== signature) {
        conflictingExchangeCopies += 1;
        state.conflicted = true;
      }
    }
  }

  return {
    exchanges: [...exchangeStates.values()].filter((state) => !state.conflicted).map((state) => state.exchange),
    sessions: [...sessionsById.values()],
    skipped,
    sourceRootsAvailable: discovery.sourceRootsAvailable,
    sourceRootsMissing: discovery.sourceRootsMissing,
    sourceRootErrors: discovery.sourceRootErrors,
    fragmentsDiscovered,
    fragmentsContributing,
    unreadableFragments,
    malformedLines,
    untimestampedExchanges,
    untimestampedOutputs,
    duplicateExchangeCopies,
    conflictingExchangeCopies,
  };
}
