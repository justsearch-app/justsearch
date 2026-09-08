#!/usr/bin/env node

import assert from 'node:assert/strict';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  buildResponse,
  extractExitCode,
  eventMatcherMatches,
  forcePushRefusal,
  matcherMatches,
  patchTargets,
  toolAliases,
} from './codex-hook-adapter.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..', '..');
const ADAPTER = path.join(HERE, 'codex-hook-adapter.mjs');
let passed = 0;
const failures = [];

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`ok   ${name}`);
  } catch (error) {
    failures.push(`${name}: ${error.stack ?? error.message}`);
    console.error(`FAIL ${name}: ${error.message}`);
  }
}

test('extracts Add/Update/Delete targets and maps Add to Write', () => {
  assert.deepEqual(
    patchTargets('*** Begin Patch\n*** Add File: a.txt\n+x\n*** Update File: b.md\n*** Move to: moved/b.md\n@@\n*** Delete File: c.json\n*** End Patch'),
    [
      { filePath: 'a.txt', toolName: 'Write' },
      { filePath: 'b.md', toolName: 'Edit' },
      { filePath: 'moved/b.md', toolName: 'Edit' },
      { filePath: 'c.json', toolName: 'Edit' },
    ],
  );
});

test('Codex tool aliases match Claude manifest matchers', () => {
  assert.deepEqual(toolAliases('apply_patch'), ['apply_patch', 'Edit', 'Write']);
  assert.equal(matcherMatches('Edit|Write', 'apply_patch'), true);
  assert.equal(matcherMatches('Agent', 'spawn_agent'), true);
  assert.equal(matcherMatches('Bash', 'exec_command'), true);
  assert.equal(matcherMatches('Read', 'apply_patch'), false);
});

test('normalizes structured and model-facing exit codes', () => {
  assert.equal(extractExitCode({ exit_code: 7 }), 7);
  assert.equal(extractExitCode({ metadata: { exitCode: 3 } }), 3);
  assert.equal(extractExitCode('Process exited with code 2'), 2);
  assert.equal(extractExitCode('no exit information'), null);
});

test('lifecycle matchers use event fields without tool aliases or tool-name fallback', () => {
  for (const [event, field, value] of [
    ['SubagentStart', 'agent_type', 'explorer'], ['SubagentStop', 'agent_type', 'explorer'],
    ['SessionStart', 'source', 'resume'], ['PreCompact', 'trigger', 'auto'],
    ['PostCompact', 'trigger', 'auto'],
  ]) {
    assert.equal(eventMatcherMatches(value, { hook_event_name: event, [field]: value }), true);
    assert.equal(eventMatcherMatches(value, { hook_event_name: event, tool_name: value }), false);
  }
  assert.equal(eventMatcherMatches('^Agent$', { hook_event_name: 'SubagentStart', agent_type: 'spawn_agent' }), false);
  assert.equal(eventMatcherMatches('Bash', { hook_event_name: 'PreToolUse', tool_name: 'exec_command' }), true);
});

test('combines context and rewrite fields into one Codex response', () => {
  const response = buildResponse('PreToolUse', {
    blocks: [],
    context: ['one', 'one', 'two'],
    systemMessages: [],
    permissionDecision: 'allow',
    permissionDecisionReason: null,
    updatedInput: { sessionId: 's1' },
    continue: true,
  });
  assert.equal(response.hookSpecificOutput.additionalContext, 'one\n\ntwo');
  assert.deepEqual(response.hookSpecificOutput.updatedInput, { sessionId: 's1' });
});

// The Codex-side force-push refusal (930 E1 follow-up). Claude gets this from native
// `permissions.deny`; Codex has no such mechanism, so the adapter carries it.
test('forcePushRefusal: token-exact, quote-stripped, per-segment', () => {
  const blocked = [
    'git push --force origin main',
    'git push -f',
    'cd modules/ui-web && git push -f',
    'git push origin +HEAD:main',
    'git push --force-with-lease origin main',
    'git push --force-with-lease=refs/heads/main origin main',
    'npm test; git push --force',
  ];
  for (const cmd of blocked) {
    assert.ok(forcePushRefusal(cmd), `expected a refusal for: ${cmd}`);
  }
  const allowed = [
    'git push -u origin feature && gh workflow run ci.yml -f sign=true',
    'echo "git push --force"',
    "echo 'git push --force'",
    'git push origin main',
    'git commit -m "do not force push" && git push',
    'gh workflow run build-installer.yml -f tag=v1',
    'git log --oneline -f',
    '',
  ];
  for (const cmd of allowed) {
    assert.equal(forcePushRefusal(cmd), null, `expected no refusal for: ${cmd}`);
  }
});

/** Drive the adapter end-to-end on one PreToolUse shell command. */
function adapterOnCommand(command, label) {
  return spawnSync(process.execPath, [ADAPTER], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    input: JSON.stringify({
      hook_event_name: 'PreToolUse',
      session_id: `codex-adapter-${label}-${process.pid}`,
      tool_name: 'Bash',
      tool_input: { command },
    }),
  });
}

test('end-to-end adapter refuses a plain force push', () => {
  const result = adapterOnCommand('git push --force origin main', 'force');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /Force push is blocked/);
});

test('end-to-end adapter allows a -f flag belonging to a later segment', () => {
  const result = adapterOnCommand('git push -u origin feature && gh workflow run ci.yml -f sign=true', 'gh-f');
  assert.equal(result.status, 0);
  assert.doesNotMatch(result.stderr, /Force push is blocked/);
});

test('end-to-end adapter allows a force-push spelling inside a quoted string', () => {
  const result = adapterOnCommand('echo "git push --force"', 'quoted');
  assert.equal(result.status, 0);
  assert.doesNotMatch(result.stderr, /Force push is blocked/);
});

test('end-to-end adapter refuses the + refspec spelling', () => {
  const result = adapterOnCommand('git push origin +HEAD:main', 'refspec');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /Force push is blocked/);
});

test('end-to-end adapter refuses a force push in a later compound segment', () => {
  const result = adapterOnCommand('cd modules/ui-web && git push -f', 'compound');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /Force push is blocked/);
});

test('end-to-end adapter refuses a direct publication merge request', () => {
  const result = adapterOnCommand('gh pr merge 933', 'publication-merge');
  assert.equal(result.status, 2);
  assert.match(result.stderr, /run-gh\.mjs enqueue/);
});

test('end-to-end adapter allows the validated publication enqueue gateway', () => {
  const result = adapterOnCommand('node scripts/dev/run-gh.mjs enqueue 933', 'publication-enqueue');
  assert.equal(result.status, 0);
  assert.doesNotMatch(result.stderr, /Direct PR merge requests/);
});

test('end-to-end adapter injects Codex session id into justsearch-dev calls', () => {
  const sessionId = `codex-adapter-mcp-test-${process.pid}`;
  const result = spawnSync(process.execPath, [ADAPTER], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    input: JSON.stringify({
      hook_event_name: 'PreToolUse',
      session_id: sessionId,
      tool_name: 'mcp__justsearch-dev__quick_health',
      tool_input: {},
    }),
  });
  assert.equal(result.status, 0);
  const output = JSON.parse(result.stdout);
  assert.equal(output.hookSpecificOutput.permissionDecision, 'allow');
  assert.equal(output.hookSpecificOutput.updatedInput.sessionId, sessionId);
});

for (const agentType of ['explorer', 'Plan', 'worker']) {
  test(`SubagentStart routes by agent_type: ${agentType}`, () => {
    const result = spawnSync(process.execPath, [ADAPTER], {
      cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000, windowsHide: true,
      env: { ...process.env, JUSTSEARCH_DISABLE_HOOKS: '0' },
      input: JSON.stringify({
        hook_event_name: 'SubagentStart', session_id: `codex-start-${process.pid}`,
        agent_id: `fixture-${agentType}`, agent_type: agentType,
        cwd: REPO_ROOT, permission_mode: 'default',
      }),
    });
    assert.equal(result.status, 0, result.stderr);
    const output = result.stdout.trim() ? JSON.parse(result.stdout) : {};
    const context = output.hookSpecificOutput?.additionalContext ?? '';
    if (agentType === 'worker') {
      assert.equal(context, '', 'a role outside the manifest matcher must not get the baseline');
    } else {
      assert.equal(output.hookSpecificOutput?.hookEventName, 'SubagentStart');
      assert.match(context, /Codex subagent baseline brief/);
      assert.match(context, /AGENTS\.md/);
      assert.match(context, /tested revision.*missing proof/);
      assert.match(context, /locale-invariant/);
    }
  });
}

if (failures.length > 0) {
  console.error(`\n${failures.length} failure(s):\n${failures.join('\n\n')}`);
  process.exit(1);
}
console.log(`\ncodex-hook-adapter.test: ${passed} passed`);
