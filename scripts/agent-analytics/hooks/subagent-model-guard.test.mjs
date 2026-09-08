/**
 * Unit tests for subagent-model-guard's decision core (claude-code#74788 leak).
 * Run with: `node scripts/agent-analytics/hooks/subagent-model-guard.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { evaluateAgentSpawn } from './subagent-model-guard.mjs';

let passed = 0;
const failures = [];

function check(label, toolInput, expectedBlock) {
  try {
    assert.equal(evaluateAgentSpawn(toolInput).block, expectedBlock, label);
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// Explicit non-fable models pass.
check('sonnet allowed', { model: 'sonnet', prompt: 'x' }, false);
check('haiku allowed', { model: 'haiku', prompt: 'x' }, false);
check('opus allowed', { model: 'opus', prompt: 'x' }, false);
check('full opus id allowed', { model: 'claude-opus-4-8' }, false);

// Fable in any form blocks.
check('fable alias blocked', { model: 'fable' }, true);
check('full fable id blocked', { model: 'claude-fable-5' }, true);
check('fable mixed case blocked', { model: 'Fable' }, true);

// The inheritance leak: missing/empty model blocks.
check('missing model blocked', { prompt: 'x' }, true);
check('empty model blocked', { model: '', prompt: 'x' }, true);
check('whitespace model blocked', { model: '  ' }, true);
check('null tool_input blocked', null, true);
check('non-string model blocked', { model: 42 }, true);

const missingModelReason = evaluateAgentSpawn({}).reason;
assert.match(missingModelReason, /Nested spawns must pin their models/);
assert.doesNotMatch(missingModelReason, /cannot see nested|No hooks fire/);

if (failures.length > 0) {
  console.error(`subagent-model-guard.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`subagent-model-guard.test: all ${passed} checks passed`);
