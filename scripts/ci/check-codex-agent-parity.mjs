#!/usr/bin/env node
/**
 * Fail-closed parity gate for the repository's Codex CLI/Desktop projection.
 *
 * AGENTS.md, the manually maintained Codex skills, and
 * governance/agent-hooks.v1.json are human-edited authorities. This check
 * proves that Codex's generated and native surfaces remain committed and that
 * hooks, MCP configuration, and bounded subagent roles preserve their
 * contracts without embedding credentials.
 */

import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

function read(rel) {
  return readFileSync(resolve(ROOT, rel), 'utf8');
}

function run(rel, args = []) {
  const result = spawnSync(process.execPath, [resolve(ROOT, rel), ...args], {
    cwd: ROOT,
    encoding: 'utf8',
    windowsHide: true,
  });
  if (result.status !== 0) {
    process.stderr.write(result.stdout ?? '');
    process.stderr.write(result.stderr ?? '');
    throw new Error(`${rel} ${args.join(' ')} failed with exit ${result.status}`);
  }
}

const checks = [
  ['AGENTS → CLAUDE complete shared-contract projection is current', () =>
    run('scripts/docs/agent-instructions-sync.mjs', ['--check'])],
  ['shared hook manifest → Codex hooks projection is current', () =>
    run('scripts/codegen/gen-codex-hooks.mjs', ['--check'])],
  ['Codex hook adapter contract tests pass', () =>
    run('scripts/agent-analytics/hooks/codex-hook-adapter.test.mjs')],
  ['hard-invariant parser and projection tests pass', () =>
    run('scripts/agent-analytics/lib/hard-invariants.test.mjs')],
  ['project MCP config is present, bounded, and credential-free', () => {
    const config = read('.codex/config.toml');
    assert.match(config, /\[mcp_servers\.justsearch-dev\]/);
    assert.match(config, /command\s*=\s*"node"/);
    assert.match(config, /p\.join\(r,'scripts','dev','justsearch-dev-mcp\.mjs'\)/);
    assert.match(config, /git.*rev-parse.*--show-toplevel/);
    assert.match(config, /required\s*=\s*true/);
    assert.match(config, /startup_timeout_sec\s*=\s*\d+/);
    assert.match(config, /tool_timeout_sec\s*=\s*\d+/);
    assert.match(config, /^default_subagent_model\s*=\s*"gpt-5\.6-luna"/m);
    assert.match(config, /^default_subagent_reasoning_effort\s*=\s*"high"/m);
    assert.doesNotMatch(config, /^cwd\s*=/m, 'project MCP must inherit Codex repository cwd; cwd=".." starts outside worktrees');
    assert.doesNotMatch(config, /(token|password|secret|pat)\s*=/i);
    // tempdoc 951: the thread cap is an anomaly guard, not a workflow instrument.
    // The value and the comment that says so are both part of the contract.
    assert.match(config, /^max_concurrent_threads_per_session\s*=\s*10\s*$/m, 'thread cap must be the 951 anomaly-guard value');
    assert.match(config, /Anomaly guard only/, 'thread cap must carry its anomaly-guard rationale comment');
  }],
  // "declared sandbox intent", not "sandboxed": since Codex #39299 a role file
  // cannot change the child's sandbox or approval policy (children inherit the
  // parent turn's). The keys stay as declared intent; docs say they are not
  // enforced (tempdoc 951 R2).
  ['native Codex agent roles are complete with declared sandbox intent', () => {
    const dir = resolve(ROOT, '.codex', 'agents');
    const names = readdirSync(dir).filter((name) => name.endsWith('.toml')).sort();
    assert.deepEqual(names, ['archivist.toml', 'companion.toml', 'complex_worker.toml', 'explorer.toml', 'reviewer.toml', 'worker.toml']);
    const expectedRouting = {
      'archivist.toml': { model: 'gpt-5.6-luna', effort: 'high', sandbox: 'workspace-write' },
      'companion.toml': { model: 'gpt-5.6-luna', effort: 'xhigh', sandbox: 'read-only' },
      'complex_worker.toml': { model: 'gpt-5.6-sol', effort: 'medium', sandbox: 'workspace-write' },
      'explorer.toml': { model: 'gpt-5.6-luna', effort: 'high', sandbox: 'read-only' },
      'reviewer.toml': { model: 'gpt-5.6-sol', effort: 'high', sandbox: 'read-only' },
      'worker.toml': { model: 'gpt-5.6-luna', effort: 'high', sandbox: 'workspace-write' },
    };
    for (const name of names) {
      const role = read(`.codex/agents/${name}`);
      assert.match(role, /^name\s*=\s*"[^"]+"/m);
      assert.match(role, /^description\s*=\s*"[^"]+"/m);
      assert.match(role, new RegExp(`^model\\s*=\\s*"${expectedRouting[name].model.replaceAll('.', '\\.')}"`, 'm'));
      assert.match(role, new RegExp(`^model_reasoning_effort\\s*=\\s*"${expectedRouting[name].effort}"`, 'm'));
      assert.match(role, new RegExp(`^sandbox_mode\\s*=\\s*"${expectedRouting[name].sandbox}"`, 'm'));
      assert.match(role, /^developer_instructions\s*=\s*"""/m);
    }
    const complexWorker = read('.codex/agents/complex_worker.toml');
    for (const escalationSignal of ['ambiguous', 'cross-module', 'concurrency', 'lifecycle', 'security', 'migration', 'fails verification']) {
      assert.match(complexWorker, new RegExp(escalationSignal), `complex_worker must advertise the ${escalationSignal} escalation signal`);
    }
    // tempdoc 951: Companion is a persistent read-only helper; its contract is
    // carried by developer_instructions because the client ignores sandbox_mode.
    const companion = read('.codex/agents/companion.toml');
    for (const marker of ['Task ID', 'rev-parse', 'stable facts', 'mutable state', 'grep', 'never edit files', 'Not yours']) {
      assert.match(companion, new RegExp(marker, 'i'), `companion must state the "${marker}" contract`);
    }
    // tempdoc 951: Archivist may commit its own docs-only diff, narrowly.
    const archivist = read('.codex/agents/archivist.toml');
    for (const marker of ['Write surface', 'unverified assumptions', 'cost-session.mjs', 'explicit paths', 'never `git add -A`', 'never push', 'never open or merge a PR', 'Not yours']) {
      // \s+ so a wrapped phrase in the TOML string still matches.
      const pattern = marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/ /g, '\\s+');
      assert.match(archivist, new RegExp(pattern, 'i'), `archivist must state the "${marker}" contract`);
    }
    const sharedInstructions = read('AGENTS.md');
    assert.match(sharedInstructions, /set `fork_turns` to\s+`"none"` or a positive integer/i);
    assert.match(sharedInstructions, /omitted\/`"all"`\s+inherits the parent model and\s+effort and bypasses role pins/);
    assert.match(sharedInstructions, /`companion` \(Luna\/xhigh/, 'AGENTS.md must name the companion role and pin');
    assert.match(sharedInstructions, /`archivist` \(Luna\/high/, 'AGENTS.md must name the archivist role and pin');
    assert.match(sharedInstructions, /thread cap is an anomaly\s+guard, not a concurrency budget/, 'AGENTS.md must state the cap semantics');
    assert.match(sharedInstructions, /role-file `sandbox_mode` is declared intent only/, 'AGENTS.md must state that role sandbox keys are not enforced');
  }],
  ['Codex hooks contain only events supported by the current hook API', () => {
    const hookConfig = JSON.parse(read('.codex/hooks.json'));
    const supported = new Set([
      'SessionStart', 'SessionEnd', 'PreToolUse', 'PostToolUse', 'PreCompact',
      'SubagentStart', 'SubagentStop', 'UserPromptSubmit', 'Stop', 'Interrupt',
      'PermissionRequest', 'PostCompact',
    ]);
    for (const event of Object.keys(hookConfig.hooks ?? {})) {
      assert.ok(supported.has(event), `unsupported Codex hook event: ${event}`);
    }
    for (const unsupported of ['PostToolUseFailure', 'CwdChanged', 'InstructionsLoaded']) {
      assert.equal(hookConfig.hooks?.[unsupported], undefined, `${unsupported} must not be projected`);
    }
  }],
  ['manually maintained Codex skills are committed rather than ignored', () => {
    const ignore = read('.gitignore');
    assert.doesNotMatch(ignore, /^\s*\.agents\/?\s*$/m);
    const probe = spawnSync('git', ['check-ignore', '.agents/skills/dev-stack/SKILL.md'], {
      cwd: ROOT, encoding: 'utf8', windowsHide: true,
    });
    assert.equal(probe.status, 1, `.agents skills are ignored by: ${(probe.stdout ?? '').trim()}`);
  }],
];

let failed = 0;
for (const [label, check] of checks) {
  try {
    check();
    console.log(`  PASS  ${label}`);
  } catch (error) {
    failed += 1;
    console.error(`  FAIL  ${label}: ${error.message}`);
  }
}

if (failed > 0) {
  console.error(`check-codex-agent-parity: FAIL (${failed}/${checks.length})`);
  process.exit(1);
}
console.log(`check-codex-agent-parity: OK (${checks.length} checks)`);
