/**
 * lib/input-summarizer.test.mjs — parity test for the tool-name switch ->
 * lib/ledger/tool-roles.mjs `roleFor` migration (tempdoc 886 §12 PR 5a).
 *
 * `EXPECTED_INPUT_RESULTS`/`EXPECTED_RESPONSE_RESULTS` are a SNAPSHOT of what
 * the pre-migration hand-rolled `switch (toolName)` produced for these exact
 * inputs, captured from the CURRENT (unmigrated) implementation before any
 * refactor landed — not re-derived from the new implementation, or this test
 * would just restate whatever the migration happens to do. Every caller of
 * `summarizeInput`/`summarizeResponse` (`hooks/dispatch.mjs`) must see
 * byte-identical output after the migration, which is what this test proves.
 *
 * Deliberately includes the cases where the OLD switch's behaviour is a
 * quirk worth pinning down explicitly, so a "cleaner" role-keyed rewrite
 * cannot silently widen coverage:
 *   - `NotebookEdit`/`MultiEdit` share the 'edit' role with `Edit`/`Write`
 *     but were NEVER given bespoke fields — they fall to the generic
 *     `{tool: name}` default, same as an unrecognized name.
 *   - `PowerShell` shares the 'shell' role with `Bash` but was never given
 *     Bash's field set either — same generic default.
 *   - `Agent` shares the 'spawn' role with `Task` but was never given
 *     Task's field set — same generic default.
 *   - `TaskCreate`/`TaskUpdate`/`TaskGet`/`TaskList`/`TaskOutput`/
 *     `EnterPlanMode`/`ExitPlanMode`/`AskUserQuestion`/`Skill` had explicit
 *     `case` labels in the old switch, but every one of them returned
 *     exactly what the `default` branch already returns (`{tool: name}`) —
 *     i.e. those cases were dead weight, not real formatting.
 *
 * Run with: `node scripts/agent-analytics/lib/input-summarizer.test.mjs`
 */

import assert from 'node:assert/strict';
import { summarizeInput, summarizeResponse } from './input-summarizer.mjs';

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

// --- snapshot: summarizeInput -------------------------------------------
// [toolName, toolInput, expectedOutput], captured from the pre-migration
// implementation (see module doc above).

const INPUT_CASES = [
  ['Read', { file_path: '/a/b.txt', offset: 10 }, { file_path: '/a/b.txt', has_offset: true, has_limit: false }],
  ['Read', { file_path: '/a/b.txt' }, { file_path: '/a/b.txt', has_offset: false, has_limit: false }],
  ['Edit', { file_path: '/a/b.txt', old_string: 'abc', new_string: 'defgh', replace_all: true },
    { file_path: '/a/b.txt', old_string_length: 3, new_string_length: 5, replace_all: true }],
  ['Write', { file_path: '/a/b.txt', content: 'hello world' }, { file_path: '/a/b.txt', content_length: 11 }],
  ['NotebookEdit', { notebook_path: '/a/b.ipynb', new_source: 'x' }, { tool: 'NotebookEdit' }],
  ['MultiEdit', { file_path: '/a/b.txt', edits: [] }, { tool: 'MultiEdit' }],
  ['Bash', { command: 'ls -la /some/very/long/path'.repeat(10), description: 'list', timeout: 5000, run_in_background: false },
    { command: 'ls -la /some/very/long/path'.repeat(10).substring(0, 200), description: 'list', timeout: 5000, run_in_background: false }],
  ['PowerShell', { command: 'Get-ChildItem', description: 'list' }, { tool: 'PowerShell' }],
  ['Grep', { pattern: 'foo', path: '.', output_mode: 'content', type: 'js', glob: '*.js' },
    { pattern: 'foo', path: '.', output_mode: 'content', type: 'js', glob: '*.js' }],
  ['Glob', { pattern: '**/*.ts', path: 'src' }, { pattern: '**/*.ts', path: 'src' }],
  ['Task', { subagent_type: 'general-purpose', description: 'do x', model: 'sonnet', prompt: 'p'.repeat(50), run_in_background: false },
    { subagent_type: 'general-purpose', description: 'do x', model: 'sonnet', prompt_length: 50, run_in_background: false }],
  ['Agent', { prompt: 'do y', subagent_type: 'fork' }, { tool: 'Agent' }],
  ['WebSearch', { query: 'hello world' }, { query: 'hello world' }],
  ['WebFetch', { url: 'https://example.com/page?x=1' }, { domain: 'example.com' }],
  ['WebFetch', { url: 'not a url ' + 'x'.repeat(100) }, { domain: ('not a url ' + 'x'.repeat(100)).substring(0, 60) }],
  ['TaskCreate', { title: 'x' }, { tool: 'TaskCreate' }],
  ['TaskUpdate', { id: '1' }, { tool: 'TaskUpdate' }],
  ['TaskGet', { id: '1' }, { tool: 'TaskGet' }],
  ['TaskList', {}, { tool: 'TaskList' }],
  ['TaskOutput', { id: '1' }, { tool: 'TaskOutput' }],
  ['EnterPlanMode', {}, { tool: 'EnterPlanMode' }],
  ['ExitPlanMode', { plan: 'p' }, { tool: 'ExitPlanMode' }],
  ['AskUserQuestion', { question: 'q' }, { tool: 'AskUserQuestion' }],
  ['Skill', { skill: 'x' }, { tool: 'Skill' }],
  ['mcp__justsearch-dev__foo', { a: 1 }, { mcp_tool: 'mcp__justsearch-dev__foo' }],
  ['mcp__docs__resolve-library-id', { libraryName: 'react' }, { mcp_tool: 'mcp__docs__resolve-library-id' }],
  ['SomeFutureTool', { a: 1, b: 2 }, { tool: 'SomeFutureTool' }],
  ['Read', null, {}],
  ['Read', undefined, {}],
  ['Read', [], {}],
  ['Read', 'not-an-object', {}],
];

for (const [name, input, expected] of INPUT_CASES) {
  run(`summarizeInput(${name}, ${JSON.stringify(input)}) matches pre-migration snapshot`, () => {
    assert.deepEqual(summarizeInput(name, input), expected);
  });
}

// --- snapshot: summarizeResponse ----------------------------------------
// summarizeResponse never switched on toolName (shape-driven only) and is
// untouched by this migration; included for completeness/regression cover.

const RESPONSE_CASES = [
  ['Bash', { exitCode: 0, success: true }, { exit_code: 0, success: true }],
  ['Bash', 'plain string output', { response_length: 19 }],
  ['Read', { filePath: '/a/b.txt', otherKey: 1, anotherKey: 2 }, { file_path: '/a/b.txt' }],
  ['Read', { unrelatedKey: 1 }, { response_keys: 1 }],
  ['Read', null, {}],
  ['Read', 42, {}],
];

for (const [name, resp, expected] of RESPONSE_CASES) {
  run(`summarizeResponse(${name}, ...) matches pre-migration snapshot`, () => {
    assert.deepEqual(summarizeResponse(name, resp), expected);
  });
}

// --- report ----------------------------------------------------------------

if (failures.length) {
  console.error(`input-summarizer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`input-summarizer.test: ${passed} passed`);
