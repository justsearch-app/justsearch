/**
 * lib/ledger/codex-adapter.test.mjs — unit tests for the Codex CLI ledger
 * adapter (tempdoc 886 §12 PR 1, independent-review fix-up), run against the
 * synthetic fixture rollout at `scripts/agent-analytics/fixtures/codex/` (no
 * real prompts/paths). `fixtures/codex/native/` is a SECOND, separate
 * `codexHome` holding the A8 `token_usage_record` rollouts (tempdoc 951); it
 * sits outside `fixtures/codex/sessions/` so the legacy-only expectations
 * above keep discovering exactly one session.
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/codex-adapter.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  codexToolOutputText, listCodexCalls, listCodexToolExchanges,
  processCodexEntries, processCodexToolExchanges,
} from './codex-adapter.mjs';
import { isCall, isToolEvent } from './record.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE_CODEX_HOME = path.join(HERE, '..', '..', 'fixtures', 'codex');

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const { calls, toolEvents, sessions, skipped } = listCodexCalls({ codexHome: FIXTURE_CODEX_HOME });

// --- discovery + shape -------------------------------------------------------

run('discovers exactly one fixture session, nothing skipped', () => {
  assert.equal(sessions.length, 1);
  assert.equal(sessions[0].sessionId, 'fixture-codex-session-1');
  assert.equal(sessions[0].project, 'F:\\FixtureProject');
  assert.deepEqual(skipped, []);
});

run('every returned Call/ToolEvent passes its own shape check', () => {
  for (const c of calls) assert.ok(isCall(c), `not a valid Call: ${JSON.stringify(c)}`);
  for (const e of toolEvents) assert.ok(isToolEvent(e), `not a valid ToolEvent: ${JSON.stringify(e)}`);
});

// --- A2: exact-repeat token_count events are dropped ------------------------

run('an event_msg token_count with info:null (rate-limits only) produces no Call', () => {
  // 3 real token_count events with usage info in the fixture (one is an exact
  // repeat and gets dropped too) + 1 rate_limits-only -> exactly 3 Calls total
  assert.equal(calls.length, 3);
});

run('the exact-repeat token_count event is dropped and counted in selfCheck', () => {
  assert.equal(sessions[0].selfCheck.repeatsDropped, 1);
});

run('deltaInputSum sums last_token_usage.input_tokens across kept events only', () => {
  // 5000 (call 0) + 8000 (call 1) + 200 (call 2, post-compaction) = 13200
  assert.equal(sessions[0].selfCheck.deltaInputSum, 13200);
});

run('maxCumulativeInput is the MAX total_token_usage.input_tokens seen, not the last', () => {
  // seen cumulative input values: 5000, 5000 (repeat), 13000, 200 (post-compaction reset)
  // -- the max is 13000, even though the LAST value (200) is smaller
  assert.equal(sessions[0].selfCheck.maxCumulativeInput, 13000);
});

run('resets counts cumulative DECREASES (the post-compaction drop from 13000 to 200)', () => {
  assert.equal(sessions[0].selfCheck.resets, 1);
});

// --- A1: fresh = input - cached; contextTokens = input (already includes cached) --

run('call 0: fresh = input_tokens - cached_input_tokens, contextTokens = input_tokens', () => {
  const c = calls[0];
  assert.equal(c.tokens.fresh, 5000 - 1000);
  assert.equal(c.tokens.cacheRead, 1000);
  assert.equal(c.contextTokens, 5000);
  assert.equal(c.tokens.output, 50);
  assert.equal(c.tokens.reasoning, 5);
  assert.equal(c.tokens.cacheWrite5m, null);
  assert.equal(c.tokens.cacheWrite1h, null);
  assert.equal(c.model, 'gpt-5.5');
  assert.equal(c.callId, 'fixture-codex-session-1:0');
  assert.equal(c.synthetic, false);
});

run('call 1 reflects the second (non-repeat) token_count delta', () => {
  const c = calls[1];
  assert.equal(c.tokens.fresh, 8000 - 2000);
  assert.equal(c.contextTokens, 8000);
  assert.equal(c.compactionBoundary, false);
});

// --- compacted line -> next call gets compactionBoundary = true ------------

run('the call following a "compacted" line carries compactionBoundary = true, synthetic = false', () => {
  const c = calls[2];
  assert.equal(c.compactionBoundary, true);
  assert.equal(c.tokens.fresh, 200);
  assert.equal(c.synthetic, false, 'a REAL call attached to the boundary is not synthetic');
});

run('calls NOT adjacent to a compacted line stay compactionBoundary = false', () => {
  assert.equal(calls[0].compactionBoundary, false);
  assert.equal(calls[1].compactionBoundary, false);
});

// --- BLOCKER 1: inter_agent_communication_metadata is a SESSION flag, never a lineage kind --

run('every Codex Call has lineage.kind = main, regardless of inter_agent_communication_metadata', () => {
  // the fixture DOES contain an inter_agent_communication_metadata line -- it
  // must NOT change any call's lineage.kind (886 independent review: a
  // {trigger_turn:false} payload names no parent, so no per-call edge exists)
  for (const c of calls) {
    assert.equal(c.lineage.kind, 'main');
    assert.equal(c.lineage.parentSessionId, null);
  }
});

run('the session-level multiAgent flag is true when inter_agent_communication_metadata is present', () => {
  assert.equal(sessions[0].multiAgent, true);
});

run('a session with NO inter_agent_communication_metadata gets multiAgent = false', () => {
  const entries = [
    { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'no-iacm-session', cwd: 'F:\\NoIacm', model_provider: 'openai' } },
  ];
  const result = processCodexEntries(entries, { file: 'no-iacm.jsonl' });
  assert.equal(result.session.multiAgent, false);
  assert.deepEqual(result.calls.every((c) => c.lineage.kind === 'main'), true);
});

run('current thread_spawn metadata produces real Codex spawn lineage and effort', () => {
  const entries = [
    {
      timestamp: '2026-09-06T00:00:00.000Z',
      type: 'session_meta',
      payload: {
        id: 'codex-child-1',
        cwd: 'F:\\FixtureProject',
        model_provider: 'openai',
        parent_thread_id: 'codex-parent-1',
        source: { subagent: { thread_spawn: {
          parent_thread_id: 'codex-parent-1', depth: 1,
          agent_path: '/root/bounded_worker', agent_role: 'worker',
        } } },
      },
    },
    { timestamp: '2026-09-06T00:00:01.000Z', type: 'turn_context', payload: { model: 'gpt-5.6-luna', effort: 'high' } },
    {
      timestamp: '2026-09-06T00:00:02.000Z', type: 'event_msg',
      payload: { type: 'token_count', info: {
        last_token_usage: { input_tokens: 100, cached_input_tokens: 20, output_tokens: 10, reasoning_output_tokens: 4 },
        total_token_usage: { input_tokens: 100, total_tokens: 110 },
      } },
    },
  ];
  const result = processCodexEntries(entries, { file: 'codex-child.jsonl' });
  assert.equal(result.calls.length, 1);
  assert.deepEqual(result.calls[0].lineage, {
    parentSessionId: 'codex-parent-1', kind: 'spawn', agentType: 'worker',
    requestedModel: null, description: '/root/bounded_worker',
  });
  assert.equal(result.calls[0].model, 'gpt-5.6-luna');
  assert.equal(result.calls[0].reasoningEffort, 'high');
  assert.deepEqual(result.session.lineage, result.calls[0].lineage);
});

run('a subagent label without a source parent edge does not fabricate lineage', () => {
  const entries = [{
    timestamp: '2026-09-06T00:00:00.000Z', type: 'session_meta',
    payload: { id: 'unlinked-child', thread_source: 'subagent', agent_role: 'worker' },
  }];
  const result = processCodexEntries(entries, { file: 'unlinked-child.jsonl' });
  assert.deepEqual(result.session.lineage, { parentSessionId: null, kind: 'main' });
});

// --- BLOCKER 2: tool events, real vocabulary, no agent_message ToolEvent ---

run('function_call + function_call_output join into one shell ToolEvent (name: shell_command)', () => {
  const e = toolEvents.find((t) => t.name === 'shell_command');
  assert.ok(e);
  assert.equal(e.role, 'shell');
  const expectedInput = JSON.stringify({ command: 'ls', workdir: 'F:\\FixtureProject', timeout_ms: 30000 });
  assert.equal(e.inputChars, expectedInput.length);
  assert.equal(e.outputChars, 'Exit code: 0\nWall time: 0.1 seconds\nOutput:\n---\nfile1\nfile2\n'.length);
  assert.equal(e.truncated, undefined);
});

run('custom_tool_call (apply_patch) + output over 65536 chars is capped and flagged truncated', () => {
  const e = toolEvents.find((t) => t.name === 'apply_patch');
  assert.ok(e);
  assert.equal(e.role, 'edit');
  assert.equal(e.outputChars, 65536);
  assert.equal(e.truncated, true);
});

run('agent_message produces NO ToolEvent (plain assistant text, not tool activity)', () => {
  const e = toolEvents.find((t) => t.name === 'agent_message');
  assert.equal(e, undefined);
});

run('exactly 2 tool events total (shell_command, apply_patch) -- agent_message excluded', () => {
  assert.equal(toolEvents.length, 2);
});

// --- raw tool exchanges for attribution readers ----------------------------

run('codexToolOutputText flattens desktop numeric-key text blocks in order', () => {
  const output = {
    0: { type: 'input_text', text: 'header' },
    1: { type: 'input_text', text: 'body' },
  };
  assert.equal(codexToolOutputText(output), 'header\nbody');
});

run('processCodexToolExchanges pairs full input/output and retains a missing output', () => {
  const entries = [
    { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'exchange-session', cwd: 'F:\\JustSearch', model_provider: 'openai' } },
    { timestamp: '2026-08-03T00:00:01.000Z', type: 'response_item', payload: { type: 'custom_tool_call', call_id: 'done', name: 'exec', input: 'Get-Content .agents/skills/example/SKILL.md' } },
    { timestamp: '2026-08-03T00:00:02.000Z', type: 'response_item', payload: { type: 'custom_tool_call_output', call_id: 'done', output: { 0: { type: 'input_text', text: 'first' }, 1: { type: 'input_text', text: 'second' } } } },
    { timestamp: '2026-08-03T00:00:03.000Z', type: 'response_item', payload: { type: 'function_call', call_id: 'pending', name: 'shell_command', arguments: '{"command":"cat .agents/skills/pending/SKILL.md"}' } },
  ];
  const result = processCodexToolExchanges(entries, { file: 'fixture.jsonl' });
  assert.equal(result.exchanges.length, 2);
  assert.equal(result.exchanges[0].outputText, 'first\nsecond');
  assert.equal(result.exchanges[0].missingOutput, false);
  assert.equal(result.exchanges[0].project, 'F:\\JustSearch');
  assert.equal(result.exchanges[1].callId, 'pending');
  assert.equal(result.exchanges[1].missingOutput, true);
  assert.equal(result.exchanges[1].outputText, null);
});

run('listCodexToolExchanges uses the same fixture discovery and project filter', () => {
  const raw = listCodexToolExchanges({ codexHome: FIXTURE_CODEX_HOME, projectFilter: /fixtureproject/i });
  assert.equal(raw.sessions.length, 1);
  assert.equal(raw.fragmentsDiscovered, 1);
  assert.equal(raw.fragmentsContributing, 1);
  assert.equal(raw.exchanges.length, 2);
  assert.equal(raw.unreadableFragments, 0);
  assert.equal(raw.malformedLines, 0);
  assert.equal(raw.untimestampedExchanges, 0);
  assert.equal(raw.untimestampedOutputs, 0);
  assert.equal(raw.sourceRootsAvailable, 1);
  assert.equal(raw.sourceRootsMissing, 1);
  assert.equal(raw.sourceRootErrors.length, 0);
  assert.equal(raw.duplicateExchangeCopies, 0);
  assert.equal(raw.conflictingExchangeCopies, 0);
  assert.ok(raw.exchanges.some((exchange) => exchange.name === 'shell_command'));
  const applyExchange = raw.exchanges.find((exchange) => exchange.name === 'apply_patch');
  assert.ok(applyExchange.outputText.length > 65536, 'raw attribution output must not inherit the neutral ToolEvent cap');
});

run('fixed until is an as-of boundary that later output cannot rewrite', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-exchange-asof-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '03');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'rollout-asof.jsonl');
  const entries = [
    { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'asof-session', cwd: 'F:\\JustSearch' } },
    { timestamp: '2026-08-03T00:00:01.000Z', type: 'custom', payload: {} },
    { timestamp: '2026-08-03T00:00:02.000Z', type: 'response_item', payload: { type: 'function_call', call_id: 'read', name: 'shell_command', arguments: '{"command":"cat .agents/skills/example/SKILL.md"}' } },
  ];
  fs.writeFileSync(file, entries.map((entry) => JSON.stringify(entry)).join('\n') + '\n', 'utf8');

  const options = {
    codexHome: tmp,
    sinceMs: Date.parse('2026-08-03T00:00:00.000Z'),
    untilMs: Date.parse('2026-08-03T00:00:03.000Z'),
  };
  const before = listCodexToolExchanges(options);
  assert.equal(before.exchanges.length, 1);
  assert.equal(before.exchanges[0].missingOutput, true);

  fs.appendFileSync(file, JSON.stringify({
    timestamp: '2026-08-03T00:00:04.000Z',
    type: 'response_item',
    payload: { type: 'function_call_output', call_id: 'read', output: 'late result' },
  }) + '\n', 'utf8');
  const after = listCodexToolExchanges(options);
  assert.deepEqual(after.exchanges, before.exchanges);
});

run('active and archived fragments are unioned without double-counting copied exchanges', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-exchange-union-test-'));
  const activeDir = path.join(tmp, 'sessions', '2026', '08', '03');
  const archivedDir = path.join(tmp, 'archived_sessions');
  fs.mkdirSync(activeDir, { recursive: true });
  fs.mkdirSync(archivedDir, { recursive: true });
  const meta = { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'union-session', cwd: 'F:\\JustSearch' } };
  const copied = [
    { timestamp: '2026-08-03T00:00:01.000Z', type: 'response_item', payload: { type: 'function_call', call_id: 'copied', name: 'shell_command', arguments: '{}' } },
    { timestamp: '2026-08-03T00:00:02.000Z', type: 'response_item', payload: { type: 'function_call_output', call_id: 'copied', output: 'same' } },
  ];
  fs.writeFileSync(
    path.join(activeDir, 'rollout-active.jsonl'),
    [meta, ...copied].map((entry) => JSON.stringify(entry)).join('\n') + '\n',
    'utf8',
  );
  const archivedOnly = [
    { timestamp: '2026-08-03T00:00:03.000Z', type: 'response_item', payload: { type: 'function_call', call_id: 'archived-only', name: 'shell_command', arguments: '{}' } },
    { timestamp: '2026-08-03T00:00:04.000Z', type: 'response_item', payload: { type: 'function_call_output', call_id: 'archived-only', output: 'archived' } },
  ];
  const copiedWithEquivalentOffsets = copied.map((entry) => ({
    ...entry,
    timestamp: entry.timestamp.replace('.000Z', '.000+00:00'),
  }));
  fs.writeFileSync(
    path.join(archivedDir, 'rollout-archived.jsonl'),
    [meta, ...copiedWithEquivalentOffsets, ...archivedOnly].map((entry) => JSON.stringify(entry)).join('\n') + '\n',
    'utf8',
  );

  const raw = listCodexToolExchanges({
    codexHome: tmp,
    untilMs: Date.parse('2026-08-03T00:00:05.000Z'),
  });
  assert.equal(raw.fragmentsDiscovered, 2);
  assert.equal(raw.fragmentsContributing, 2);
  assert.equal(raw.sessions.length, 1);
  assert.equal(raw.exchanges.length, 2);
  assert.equal(raw.duplicateExchangeCopies, 1);
  assert.equal(raw.conflictingExchangeCopies, 0);
});

run('conflicting copies are counted and quarantined', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-exchange-conflict-test-'));
  for (const relative of [path.join('sessions', '2026', '08', '03'), 'archived_sessions']) {
    fs.mkdirSync(path.join(tmp, relative), { recursive: true });
  }
  const makeEntries = (output) => [
    { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'conflict-session', cwd: 'F:\\JustSearch' } },
    { timestamp: '2026-08-03T00:00:01.000Z', type: 'response_item', payload: { type: 'function_call', call_id: 'same-id', name: 'shell_command', arguments: '{}' } },
    { timestamp: '2026-08-03T00:00:02.000Z', type: 'response_item', payload: { type: 'function_call_output', call_id: 'same-id', output } },
  ];
  fs.writeFileSync(path.join(tmp, 'sessions', '2026', '08', '03', 'rollout-a.jsonl'), makeEntries('short').map(JSON.stringify).join('\n') + '\n', 'utf8');
  fs.writeFileSync(path.join(tmp, 'archived_sessions', 'rollout-b.jsonl'), makeEntries('longer output').map(JSON.stringify).join('\n') + '\n', 'utf8');

  const raw = listCodexToolExchanges({ codexHome: tmp });
  assert.equal(raw.exchanges.length, 0);
  assert.equal(raw.duplicateExchangeCopies, 1);
  assert.equal(raw.conflictingExchangeCopies, 1);
});

run('untimestamped exchanges are omitted and counted', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-exchange-untimestamped-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '03');
  fs.mkdirSync(dir, { recursive: true });
  const entries = [
    { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'untimestamped-session', cwd: 'F:\\JustSearch' } },
    { type: 'response_item', payload: { type: 'function_call', call_id: 'no-time', name: 'shell_command', arguments: '{}' } },
  ];
  fs.writeFileSync(path.join(dir, 'rollout-no-time.jsonl'), entries.map(JSON.stringify).join('\n') + '\n', 'utf8');
  const raw = listCodexToolExchanges({
    codexHome: tmp,
    untilMs: Date.parse('2026-08-03T00:00:05.000Z'),
  });
  assert.equal(raw.exchanges.length, 0);
  assert.equal(raw.untimestampedExchanges, 1);
});

run('untimestamped outputs are retained but marked as as-of indeterminate', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-exchange-output-time-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '03');
  fs.mkdirSync(dir, { recursive: true });
  const entries = [
    { timestamp: '2026-08-03T00:00:00.000Z', type: 'session_meta', payload: { id: 'output-time-session', cwd: 'F:\\JustSearch' } },
    { timestamp: '2026-08-03T00:00:01.000Z', type: 'response_item', payload: { type: 'function_call', call_id: 'read', name: 'shell_command', arguments: '{}' } },
    { type: 'response_item', payload: { type: 'function_call_output', call_id: 'read', output: 'result without event time' } },
  ];
  fs.writeFileSync(path.join(dir, 'rollout-output-no-time.jsonl'), entries.map(JSON.stringify).join('\n') + '\n', 'utf8');
  const raw = listCodexToolExchanges({
    codexHome: tmp,
    untilMs: Date.parse('2026-08-03T00:00:05.000Z'),
  });
  assert.equal(raw.exchanges.length, 1);
  assert.equal(raw.exchanges[0].missingOutput, false);
  assert.equal(raw.exchanges[0].outputTimestampUnknown, true);
  assert.equal(raw.untimestampedOutputs, 1);
});

// --- resilience --------------------------------------------------------------

run('listCodexCalls returns an empty result for a nonexistent codexHome, never throws', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-adapter-test-'));
  const missing = path.join(tmp, 'does-not-exist');
  const r = listCodexCalls({ codexHome: missing });
  assert.deepEqual(r, { calls: [], toolEvents: [], sessions: [], skipped: [] });
});

run('listCodexToolExchanges returns an empty result for a nonexistent codexHome', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-exchange-test-'));
  const r = listCodexToolExchanges({ codexHome: path.join(tmp, 'does-not-exist') });
  assert.deepEqual(r, {
    exchanges: [],
    sessions: [],
    skipped: [],
    sourceRootsAvailable: 0,
    sourceRootsMissing: 2,
    sourceRootErrors: [],
    fragmentsDiscovered: 0,
    fragmentsContributing: 0,
    unreadableFragments: 0,
    malformedLines: 0,
    untimestampedExchanges: 0,
    untimestampedOutputs: 0,
    duplicateExchangeCopies: 0,
    conflictingExchangeCopies: 0,
  });
});

run('a rollout file under an "archived_sessions" directory is skipped (walk-level, not skip-list)', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-adapter-test-'));
  const archivedDir = path.join(tmp, 'sessions', 'archived_sessions');
  fs.mkdirSync(archivedDir, { recursive: true });
  fs.writeFileSync(
    path.join(archivedDir, 'rollout-archived.jsonl'),
    JSON.stringify({ timestamp: '2026-01-01T00:00:00.000Z', type: 'session_meta', payload: { id: 'archived-1', cwd: 'X', model_provider: 'openai' } }) + '\n',
    'utf8',
  );
  const r = listCodexCalls({ codexHome: tmp });
  assert.equal(r.sessions.length, 0, 'a file under archived_sessions must not be discovered');
  assert.deepEqual(r.skipped, [], 'never even reached -- not a "skipped" case, just never walked');
});

// --- compacted line with NO following token_count -> synthetic boundary call --

run('a "compacted" line with no subsequent token_count emits a synthetic zero-token boundary call', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-adapter-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '02');
  fs.mkdirSync(dir, { recursive: true });
  const lines = [
    { timestamp: '2026-08-02T00:00:00.000Z', type: 'session_meta', payload: { id: 'orphan-boundary-session', cwd: 'F:\\Orphan', model_provider: 'openai' } },
    { timestamp: '2026-08-02T00:00:01.000Z', type: 'turn_context', payload: { turn_id: 't1', model: 'gpt-5.5' } },
    { timestamp: '2026-08-02T00:00:02.000Z', type: 'event_msg', payload: { type: 'token_count', info: { last_token_usage: { input_tokens: 100, cached_input_tokens: 10, output_tokens: 5, reasoning_output_tokens: 0, total_tokens: 105 }, total_token_usage: { input_tokens: 100, cached_input_tokens: 10, output_tokens: 5, reasoning_output_tokens: 0, total_tokens: 105 } } } },
    { timestamp: '2026-08-02T00:00:03.000Z', type: 'compacted', payload: { message: '', replacement_history: [] } },
  ];
  fs.writeFileSync(
    path.join(dir, 'rollout-2026-08-02T00-00-00-orphan.jsonl'),
    lines.map((l) => JSON.stringify(l)).join('\n') + '\n',
    'utf8',
  );
  const r = listCodexCalls({ codexHome: tmp });
  assert.equal(r.calls.length, 2, 'the real call plus one synthetic boundary call');
  const synthetic = r.calls[1];
  assert.equal(synthetic.compactionBoundary, true);
  assert.equal(synthetic.contextTokens, 0);
  assert.equal(synthetic.tokens.fresh, 0);
  assert.equal(synthetic.tokens.output, 0);
  assert.equal(synthetic.synthetic, true, 'NIT 6: the fabricated call must be flagged synthetic');
  assert.equal(r.calls[0].synthetic, false, 'the real call must NOT be flagged synthetic');
});

// --- BLOCKER 3: narrowed skip handling ---------------------------------------

run('a session_meta with no payload.id is SKIPPED with a reason, not thrown', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-adapter-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '04');
  fs.mkdirSync(dir, { recursive: true });
  const lines = [
    { timestamp: '2026-08-04T00:00:00.000Z', type: 'session_meta', payload: { cwd: 'F:\\NoId', model_provider: 'openai' } },
    { timestamp: '2026-08-04T00:00:01.000Z', type: 'event_msg', payload: { type: 'token_count', info: { last_token_usage: { input_tokens: 10, cached_input_tokens: 0, output_tokens: 1, reasoning_output_tokens: 0, total_tokens: 11 }, total_token_usage: { input_tokens: 10, cached_input_tokens: 0, output_tokens: 1, reasoning_output_tokens: 0, total_tokens: 11 } } } },
  ];
  const file = path.join(dir, 'rollout-2026-08-04T00-00-00-noid.jsonl');
  fs.writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');

  const r = listCodexCalls({ codexHome: tmp });
  assert.equal(r.calls.length, 0);
  assert.equal(r.sessions.length, 0);
  assert.equal(r.skipped.length, 1);
  assert.equal(r.skipped[0].file, file);
  assert.match(r.skipped[0].reason, /sessionId/);
});

run('a session with NO session_meta line at all is SKIPPED with a reason', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-adapter-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '05');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'rollout-2026-08-05T00-00-00-nometa.jsonl');
  fs.writeFileSync(
    file,
    JSON.stringify({ timestamp: '2026-08-05T00:00:00.000Z', type: 'turn_context', payload: { turn_id: 't1', model: 'gpt-5.5' } }) + '\n',
    'utf8',
  );
  const r = listCodexCalls({ codexHome: tmp });
  assert.equal(r.skipped.length, 1);
  assert.equal(r.skipped[0].file, file);
});

run('a malformed JSON line on a NON-token-count line is tolerated, NOT skipped -- calls still parse', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-adapter-test-'));
  const dir = path.join(tmp, 'sessions', '2026', '08', '06');
  fs.mkdirSync(dir, { recursive: true });
  const goodLines = [
    JSON.stringify({ timestamp: '2026-08-06T00:00:00.000Z', type: 'session_meta', payload: { id: 'malformed-line-session', cwd: 'F:\\Malformed', model_provider: 'openai' } }),
    JSON.stringify({ timestamp: '2026-08-06T00:00:01.000Z', type: 'turn_context', payload: { turn_id: 't1', model: 'gpt-5.5' } }),
    'this is not valid JSON {{{ garbage garbage', // malformed line, NOT a token_count line
    JSON.stringify({ timestamp: '2026-08-06T00:00:02.000Z', type: 'event_msg', payload: { type: 'token_count', info: { last_token_usage: { input_tokens: 50, cached_input_tokens: 5, output_tokens: 3, reasoning_output_tokens: 0, total_tokens: 53 }, total_token_usage: { input_tokens: 50, cached_input_tokens: 5, output_tokens: 3, reasoning_output_tokens: 0, total_tokens: 53 } } } }),
  ];
  const file = path.join(dir, 'rollout-2026-08-06T00-00-00-malformed.jsonl');
  fs.writeFileSync(file, goodLines.join('\n') + '\n', 'utf8');

  const r = listCodexCalls({ codexHome: tmp });
  assert.equal(r.skipped.length, 0, 'a malformed non-token line must not cause a skip');
  assert.equal(r.calls.length, 1, 'the real token_count line after the malformed one still parses');
  assert.equal(r.calls[0].sessionId, 'malformed-line-session');
  const raw = listCodexToolExchanges({ codexHome: tmp });
  assert.equal(raw.malformedLines, 1, 'the attribution reader must expose tolerated parse loss');
});

run('a genuine thrown error during entry processing PROPAGATES, is not swallowed', () => {
  const throwingEntries = [
    { timestamp: '2026-08-07T00:00:00.000Z', type: 'session_meta', payload: { id: 'throwing-session', cwd: 'F:\\Throw', model_provider: 'openai' } },
    // a JSON.parse round-trip can never produce a getter -- this is a
    // deliberately-constructed object (not read from a file) so the test
    // exercises a REAL uncaught-exception path through processCodexEntries,
    // proving BLOCKER 3's fix: the per-file try/catch this used to be
    // wrapped in is gone, so this throw is no longer silently swallowed.
    {
      timestamp: '2026-08-07T00:00:01.000Z',
      get type() { throw new Error('boom - simulated parse-time exception'); },
    },
  ];
  assert.throws(() => processCodexEntries(throwingEntries, { file: 'throwing.jsonl' }), /boom - simulated parse-time exception/);
});

// --- A8: native token_usage_record stream -----------------------------------

const NATIVE_FIXTURE_HOME = path.join(FIXTURE_CODEX_HOME, 'native');
const native = listCodexCalls({ codexHome: NATIVE_FIXTURE_HOME });
const gapSession = native.sessions.find((s) => s.sessionId === 'fixture-codex-native-gap');
const gapCalls = native.calls.filter((c) => c.sessionId === 'fixture-codex-native-gap');
const dupeSession = native.sessions.find((s) => s.sessionId === 'fixture-codex-native-dupe');
const dupeCalls = native.calls.filter((c) => c.sessionId === 'fixture-codex-native-dupe');

run('the native fixture home yields two sessions, both on the native usage stream', () => {
  assert.equal(native.sessions.length, 2);
  assert.deepEqual(native.skipped, []);
  assert.equal(gapSession.selfCheck.usageSource, 'native');
  assert.equal(dupeSession.selfCheck.usageSource, 'native');
});

run('every native Call passes the neutral Call shape check', () => {
  for (const c of native.calls) assert.ok(isCall(c), `not a valid Call: ${JSON.stringify(c)}`);
});

run('one Call per distinct token_usage_record, none from token_count', () => {
  // 3 native records, but only 2 token_count events -- the legacy path would
  // have produced 2 Calls here, so a count of 3 proves the native stream owns
  // Call creation and that the streams are not summed (which would give 5).
  assert.equal(gapCalls.length, 3);
  assert.equal(gapSession.selfCheck.nativeRecords, 3);
  assert.equal(gapSession.selfCheck.nativeDuplicatesDropped, 0);
});

run('native context total equals the sum of the distinct records input_tokens', () => {
  const nativeInput = gapCalls.reduce((sum, c) => sum + c.contextTokens, 0);
  assert.equal(nativeInput, 1000 + 2500 + 500);
  // and fresh + cacheRead reconstructs it, per A1
  const reconstructed = gapCalls.reduce((sum, c) => sum + c.tokens.fresh + c.tokens.cacheRead, 0);
  assert.equal(reconstructed, nativeInput);
});

run('the compaction-gap record is exactly the native-vs-legacy input difference', () => {
  // selfCheck.deltaInputSum still reports what the legacy token_count stream
  // would have summed; the ONE response with no token_count notification (the
  // compaction summarizer, input 2500) is the whole difference.
  const nativeInput = gapCalls.reduce((sum, c) => sum + c.contextTokens, 0);
  assert.equal(gapSession.selfCheck.deltaInputSum, 1000 + 500);
  assert.equal(nativeInput - gapSession.selfCheck.deltaInputSum, 2500);
});

run('compactionBoundary lands on the first native record AFTER the compacted line', () => {
  assert.equal(gapCalls[0].compactionBoundary, false);
  // the summarizer response PRECEDES the compacted line, so it is not the boundary
  assert.equal(gapCalls[1].compactionBoundary, false);
  assert.equal(gapCalls[1].contextTokens, 2500);
  assert.equal(gapCalls[2].compactionBoundary, true);
  assert.equal(gapCalls[2].contextTokens, 500);
  for (const c of gapCalls) assert.equal(c.synthetic, false, 'every native Call is real, not synthetic');
});

run('native Calls carry turn_context model/effort, sequential callIds, and a null cache-write axis', () => {
  assert.equal(gapCalls[0].tokens.fresh, 1000 - 400);
  assert.equal(gapCalls[0].tokens.cacheRead, 400);
  assert.equal(gapCalls[0].tokens.output, 60);
  assert.equal(gapCalls[0].tokens.reasoning, 10);
  assert.equal(gapCalls[0].tokens.cacheWrite5m, null);
  assert.equal(gapCalls[0].tokens.cacheWrite1h, null, 'cache_write_input_tokens is not a billable Codex axis');
  assert.equal(gapCalls[0].model, 'gpt-5.5');
  assert.equal(gapCalls[0].reasoningEffort, 'high');
  assert.equal(gapCalls[0].provider, 'openai');
  assert.equal(gapCalls[0].project, 'F:\\FixtureProject');
  assert.deepEqual(gapCalls.map((c) => c.callId), [
    'fixture-codex-native-gap:0', 'fixture-codex-native-gap:1', 'fixture-codex-native-gap:2',
  ]);
});

run('a duplicate token_count notification does not double count on the native path', () => {
  // two identical token_count events describe the SAME single native response
  assert.equal(dupeCalls.length, 1);
  assert.equal(dupeCalls[0].contextTokens, 700);
  assert.equal(dupeCalls[0].tokens.fresh, 700 - 300);
  assert.equal(dupeSession.selfCheck.nativeRecords, 1);
  // the legacy A2 diagnostics still run on the native path
  assert.equal(dupeSession.selfCheck.repeatsDropped, 1);
  assert.equal(dupeSession.selfCheck.deltaInputSum, 700);
});

run('selfCheck keeps every legacy key and adds the A8 keys', () => {
  assert.deepEqual(Object.keys(gapSession.selfCheck).sort(), [
    'deltaInputSum', 'maxCumulativeInput', 'nativeDuplicatesDropped',
    'nativeRecords', 'repeatsDropped', 'resets', 'usageSource',
  ]);
  assert.equal(gapSession.selfCheck.maxCumulativeInput, 1000);
  assert.equal(gapSession.selfCheck.resets, 1, 'the post-compaction cumulative drop is still counted');
});

run('the legacy-only fixture stays on the legacy path with no native records', () => {
  assert.equal(sessions[0].selfCheck.usageSource, 'legacy');
  assert.equal(sessions[0].selfCheck.nativeRecords, 0);
  assert.equal(sessions[0].selfCheck.nativeDuplicatesDropped, 0);
  assert.equal(calls.length, 3, 'legacy Call construction is unchanged');
});

run('a "compacted" line last on the native path still emits the synthetic boundary call', () => {
  const entries = [
    { timestamp: '2026-09-09T01:00:00.000Z', type: 'session_meta', payload: { id: 'native-orphan-boundary', cwd: 'F:\\Orphan', model_provider: 'openai' } },
    { timestamp: '2026-09-09T01:00:01.000Z', type: 'turn_context', payload: { model: 'gpt-5.5', effort: 'high' } },
    {
      timestamp: '2026-09-09T01:00:02.000Z', type: 'token_usage_record',
      payload: { response_id: 'resp-orphan-1', usage: { input_tokens: 300, cached_input_tokens: 50, output_tokens: 20, reasoning_output_tokens: 0, total_tokens: 320 } },
    },
    { timestamp: '2026-09-09T01:00:03.000Z', type: 'compacted', payload: { message: '', replacement_history: [] } },
  ];
  const result = processCodexEntries(entries, { file: 'native-orphan.jsonl' });
  assert.equal(result.calls.length, 2, 'the real native call plus one synthetic boundary call');
  assert.equal(result.calls[0].synthetic, false);
  assert.equal(result.calls[1].synthetic, true);
  assert.equal(result.calls[1].compactionBoundary, true);
  assert.equal(result.calls[1].contextTokens, 0);
  assert.equal(result.calls[1].callId, 'native-orphan-boundary:1');
  assert.equal(result.session.selfCheck.usageSource, 'native');
});

run('a duplicate response_id is dropped first-wins and counted', () => {
  const record = (responseId, inputTokens) => ({
    timestamp: '2026-09-09T02:00:00.000Z', type: 'token_usage_record',
    payload: { response_id: responseId, usage: { input_tokens: inputTokens, cached_input_tokens: 0, output_tokens: 1, reasoning_output_tokens: 0, total_tokens: inputTokens + 1 } },
  });
  const entries = [
    { timestamp: '2026-09-09T02:00:00.000Z', type: 'session_meta', payload: { id: 'native-dupe-id', cwd: 'F:\\Dupe', model_provider: 'openai' } },
    record('resp-same', 100),
    record('resp-same', 999),
    record('resp-other', 40),
  ];
  const result = processCodexEntries(entries, { file: 'native-dupe-id.jsonl' });
  assert.equal(result.calls.length, 2);
  assert.equal(result.calls[0].contextTokens, 100, 'first record for a response_id wins');
  assert.equal(result.calls[1].contextTokens, 40);
  assert.equal(result.session.selfCheck.nativeRecords, 2);
  assert.equal(result.session.selfCheck.nativeDuplicatesDropped, 1);
});

run('a token_usage_record missing usage or response_id is ignored and does not switch the path', () => {
  const entries = [
    { timestamp: '2026-09-09T03:00:00.000Z', type: 'session_meta', payload: { id: 'native-unusable', cwd: 'F:\\Unusable', model_provider: 'openai' } },
    { timestamp: '2026-09-09T03:00:01.000Z', type: 'turn_context', payload: { model: 'gpt-5.5' } },
    { timestamp: '2026-09-09T03:00:02.000Z', type: 'token_usage_record', payload: { response_id: 'resp-no-usage' } },
    { timestamp: '2026-09-09T03:00:03.000Z', type: 'token_usage_record', payload: { usage: { input_tokens: 5000, cached_input_tokens: 0, output_tokens: 1, reasoning_output_tokens: 0, total_tokens: 5001 } } },
    {
      timestamp: '2026-09-09T03:00:04.000Z', type: 'event_msg',
      payload: { type: 'token_count', info: {
        last_token_usage: { input_tokens: 90, cached_input_tokens: 9, output_tokens: 4, reasoning_output_tokens: 1, total_tokens: 94 },
        total_token_usage: { input_tokens: 90, cached_input_tokens: 9, output_tokens: 4, reasoning_output_tokens: 1, total_tokens: 94 },
      } },
    },
  ];
  const result = processCodexEntries(entries, { file: 'native-unusable.jsonl' });
  assert.equal(result.session.selfCheck.usageSource, 'legacy');
  assert.equal(result.session.selfCheck.nativeRecords, 0);
  assert.equal(result.calls.length, 1, 'the legacy token_count still produces its Call');
  assert.equal(result.calls[0].contextTokens, 90, 'the unusable record contributes nothing');
});

run('a native record with no token_count anywhere still produces Calls', () => {
  const entries = [
    { timestamp: '2026-09-09T04:00:00.000Z', type: 'session_meta', payload: { id: 'native-only', cwd: 'F:\\NativeOnly', model_provider: 'openai' } },
    {
      timestamp: '2026-09-09T04:00:01.000Z', type: 'token_usage_record',
      payload: { response_id: 'resp-only-1', usage: { input_tokens: 120, cached_input_tokens: 20, output_tokens: 7, reasoning_output_tokens: 2, total_tokens: 129 } },
    },
  ];
  const result = processCodexEntries(entries, { file: 'native-only.jsonl' });
  assert.equal(result.calls.length, 1);
  assert.equal(result.calls[0].tokens.fresh, 100);
  assert.equal(result.session.selfCheck.deltaInputSum, 0, 'no token_count events to diagnose');
  assert.equal(result.session.selfCheck.usageSource, 'native');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`codex-adapter.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`codex-adapter.test: ${passed} passed`);
