/**
 * lib/ledger/tool-roles.test.mjs — unit tests for the per-harness tool-name
 * -> role map (tempdoc 886 §12 PR 1).
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/tool-roles.test.mjs`
 */

import assert from 'node:assert/strict';
import { roleFor } from './tool-roles.mjs';

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

// --- claude-code -------------------------------------------------------------

run('Claude: Read -> read', () => assert.equal(roleFor('claude-code', 'Read'), 'read'));
run('Claude: Grep/Glob -> search', () => {
  assert.equal(roleFor('claude-code', 'Grep'), 'search');
  assert.equal(roleFor('claude-code', 'Glob'), 'search');
});
run('Claude: Edit/Write/NotebookEdit/MultiEdit -> edit', () => {
  assert.equal(roleFor('claude-code', 'Edit'), 'edit');
  assert.equal(roleFor('claude-code', 'Write'), 'edit');
  assert.equal(roleFor('claude-code', 'NotebookEdit'), 'edit');
  assert.equal(roleFor('claude-code', 'MultiEdit'), 'edit');
});
run('Claude: Bash/PowerShell -> shell', () => {
  assert.equal(roleFor('claude-code', 'Bash'), 'shell');
  assert.equal(roleFor('claude-code', 'PowerShell'), 'shell');
});
run('Claude: Agent/Task -> spawn', () => {
  assert.equal(roleFor('claude-code', 'Agent'), 'spawn');
  assert.equal(roleFor('claude-code', 'Task'), 'spawn');
});
run('Claude: WebFetch/WebSearch -> web', () => {
  assert.equal(roleFor('claude-code', 'WebFetch'), 'web');
  assert.equal(roleFor('claude-code', 'WebSearch'), 'web');
});
run('Claude: any mcp__* tool -> other', () => {
  assert.equal(roleFor('claude-code', 'mcp__justsearch-dev__justsearch_dev_start'), 'other');
  assert.equal(roleFor('claude-code', 'mcp__docs__resolve-library-id'), 'other');
});
run('Claude: an unknown tool name -> other', () => {
  assert.equal(roleFor('claude-code', 'SomeFutureTool'), 'other');
});

// --- codex-cli -----------------------------------------------------------

run('Codex: exec/shell_command/run/js -> shell', () => {
  assert.equal(roleFor('codex-cli', 'exec'), 'shell');
  assert.equal(roleFor('codex-cli', 'shell_command'), 'shell');
  assert.equal(roleFor('codex-cli', 'run'), 'shell');
  assert.equal(roleFor('codex-cli', 'js'), 'shell');
});
run('Codex: apply_patch -> edit', () => assert.equal(roleFor('codex-cli', 'apply_patch'), 'edit'));
run('Codex: read_file/view_image -> read', () => {
  assert.equal(roleFor('codex-cli', 'read_file'), 'read');
  assert.equal(roleFor('codex-cli', 'view_image'), 'read');
});
run('Codex: web_search -> web', () => assert.equal(roleFor('codex-cli', 'web_search'), 'web'));
run('Codex: wait/wait_agent -> wait', () => {
  assert.equal(roleFor('codex-cli', 'wait'), 'wait');
  assert.equal(roleFor('codex-cli', 'wait_agent'), 'wait');
});
run('Codex: spawn_agent/send_message/list_agents/followup_task -> spawn', () => {
  assert.equal(roleFor('codex-cli', 'spawn_agent'), 'spawn');
  assert.equal(roleFor('codex-cli', 'send_message'), 'spawn');
  assert.equal(roleFor('codex-cli', 'list_agents'), 'spawn');
  assert.equal(roleFor('codex-cli', 'followup_task'), 'spawn');
});
run('Codex: agent_message is NOT a tool (assistant reply text) and is unmapped', () => {
  assert.equal(roleFor('codex-cli', 'agent_message'), 'other');
});
run('Codex: update_plan/tool_search -> other', () => {
  assert.equal(roleFor('codex-cli', 'update_plan'), 'other');
  assert.equal(roleFor('codex-cli', 'tool_search'), 'other');
});
run('Codex: an unknown tool name -> other', () => {
  assert.equal(roleFor('codex-cli', 'some_future_tool'), 'other');
});

/**
 * Corpus vocabulary snapshot (independent review, 2026-09-02, 50,259 real
 * Codex calls / tool events on this machine): the top-14 observed names by
 * frequency. Every one of them must resolve to a non-'other' role EXCEPT
 * `update_plan` and `tool_search`, which are real, frequently-seen names
 * that are genuinely not read/edit/shell/search/spawn/wait/web activity.
 */
run('every name in the real corpus vocabulary snapshot resolves to a non-other role, except update_plan/tool_search', () => {
  const CORPUS_VOCAB_2026_09_02 = [
    // agent_message (9,203 in the snapshot) is omitted on purpose: it is assistant
    // reply text, not a tool, and the adapter never emits a ToolEvent for it.
    'exec', 'shell_command', 'apply_patch', 'wait', 'wait_agent',
    'update_plan', 'send_message', 'js', 'spawn_agent', 'list_agents', 'run',
    'followup_task', 'view_image',
  ];
  const OTHER_EXPECTED = new Set(['update_plan', 'tool_search']);
  for (const name of CORPUS_VOCAB_2026_09_02) {
    const role = roleFor('codex-cli', name);
    if (OTHER_EXPECTED.has(name)) {
      assert.equal(role, 'other', `${name} is expected to resolve to 'other'`);
    } else {
      assert.notEqual(role, 'other', `${name} resolved to 'other' but should not have`);
    }
  }
});

// --- edge cases ----------------------------------------------------------

run('a missing/null tool name -> other for any harness', () => {
  assert.equal(roleFor('claude-code', null), 'other');
  assert.equal(roleFor('claude-code', undefined), 'other');
  assert.equal(roleFor('codex-cli', ''), 'other');
});
run('an unknown harness -> other regardless of tool name', () => {
  assert.equal(roleFor('gemini-cli', 'Read'), 'other');
});
run('"wait" is never produced by roleFor for Claude (adapter-level only there); IS a real Codex role', () => {
  const claudeNames = ['Read', 'Grep', 'Glob', 'Edit', 'Write', 'NotebookEdit', 'MultiEdit', 'Bash', 'PowerShell', 'Agent', 'Task', 'WebFetch', 'WebSearch'];
  for (const n of claudeNames) assert.notEqual(roleFor('claude-code', n), 'wait');
  assert.equal(roleFor('codex-cli', 'wait'), 'wait');
  assert.equal(roleFor('codex-cli', 'wait_agent'), 'wait');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`tool-roles.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`tool-roles.test: ${passed} passed`);
