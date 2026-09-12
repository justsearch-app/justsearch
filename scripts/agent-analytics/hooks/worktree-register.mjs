#!/usr/bin/env node

/**
 * Registration hook (tempdoc 952 §5.2, Amendment C) — the creation side of the lifecycle.
 *
 * Fires on `WorktreeCreate` (the harness is about to create a worktree: the input carries
 * `worktree_path`) and on PostToolUse `EnterWorktree` (a session entered an existing worktree:
 * the tool response carries `worktreePath`). Either way it runs
 * `worktree-lifecycle.cjs register <path>`, which writes the per-branch ownership markers.
 *
 * Silent by design (952 §5.9 / A10): success prints nothing, so the hook adds zero bytes to the
 * session's context. Failures go to telemetry only. It never removes anything and never blocks:
 * on `WorktreeCreate` exit code 0 is required for the creation to proceed, so `main()` always
 * returns normally (runHook exits 0 on a thrown error too).
 *
 * Registration, not creation, is the invariant: a worktree created by the harness or by hand
 * under the sanctioned root becomes a managed resource the moment a session enters it.
 */

import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { mainRepoRoot, repoRoot, runHook, readJsonStdin, appendTelemetryEvent } from '../lib/hook-base.mjs';

const LIFECYCLE_CLI = path.join(repoRoot, 'scripts', 'dev', 'worktree-lifecycle.cjs');

/**
 * Pure decision: which path to register, if any.
 * @param {object} input hook JSON
 * @returns {{action:'register', worktreePath:string, sessionId:string|null, harness:string}
 *          | {action:'skip', reason:string}}
 */
export function decideRegistration(input) {
  if (!input || typeof input !== 'object') return { action: 'skip', reason: 'no input' };
  const harness = input.harness === 'codex-cli' ? 'codex' : 'claude';
  const sessionId = typeof input.session_id === 'string' && input.session_id ? input.session_id : null;
  const event = input.hook_event_name;
  let worktreePath = null;
  if (event === 'WorktreeCreate') {
    worktreePath = typeof input.worktree_path === 'string' ? input.worktree_path : null;
    if (!worktreePath) return { action: 'skip', reason: 'WorktreeCreate without worktree_path' };
  } else if (event === 'PostToolUse') {
    if (input.tool_name !== 'EnterWorktree') return { action: 'skip', reason: `tool ${input.tool_name} is not EnterWorktree` };
    worktreePath = typeof input.tool_response?.worktreePath === 'string' ? input.tool_response.worktreePath : null;
    if (!worktreePath) return { action: 'skip', reason: 'EnterWorktree response without worktreePath' };
  } else {
    return { action: 'skip', reason: `event ${event} is not a registration point` };
  }
  return { action: 'register', worktreePath, sessionId, harness };
}

async function main() {
  const input = await readJsonStdin();
  const decision = decideRegistration(input);
  if (decision.action !== 'register') return;
  const args = [LIFECYCLE_CLI, 'register', decision.worktreePath, '--harness', decision.harness];
  if (decision.sessionId) args.push('--session-id', decision.sessionId);
  const result = spawnSync(process.execPath, args, { cwd: mainRepoRoot, encoding: 'utf8', timeout: 8000 });
  if (result.status !== 0) {
    appendTelemetryEvent({
      event: 'worktree_register_failed',
      worktreePath: decision.worktreePath,
      message: String(result.stderr || result.stdout || result.error?.message || `exit ${result.status}`).slice(0, 300),
      ts: new Date().toISOString(),
    });
  }
  // Nothing printed on either path: registration must not cost the session a single token.
}

runHook(import.meta.url, main);
