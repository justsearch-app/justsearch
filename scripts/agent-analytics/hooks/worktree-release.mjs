#!/usr/bin/env node

/**
 * Release hook (tempdoc 952 §5.6, Amendment C) — the SessionEnd side of the lifecycle, for both
 * harnesses.
 *
 * When a session that is working inside a managed worktree ends, this hook runs
 * `worktree-lifecycle.cjs release <cwd> --own ...` so the resource is marked released without
 * any model involvement (952 §5.9: SessionEnd output is never read by a model, and the session is
 * over). The two harnesses differ in what the harness itself does next:
 *
 * - Claude Code removes the worktree AFTER SessionEnd hooks finish (hooks reference, SessionEnd),
 *   and what happens when a hook removes it first is undocumented (derisk D5). So the Claude
 *   path is `--record-only`: markers and receipt, no removal; Claude's own exit path removes a
 *   clean unnamed worktree, and anything it keeps is the reconciler's from then on.
 * - Codex has no worktree exit path for hand-rooted trees, so the Codex path is the full
 *   `--if-clean` release: archive, verify, remove, retire the branch when its receipt says landed.
 *   A dirty tree is left RELEASED for the reconciler.
 *
 * `end_reason` `clear` and `resume` mean the conversation continues under a new session id inside
 * the same worktree, so those are skipped; `other` is skipped because it is not documented to
 * mean the worktree is done. Only `logout` and `prompt_input_exit` (and the Codex adapter's
 * session end) release.
 *
 * Never runs from PreToolUse (861 [A4]); never removes anything under Claude.
 */

import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { mainRepoRoot, repoRoot, runHook, readJsonStdin, appendTelemetryEvent } from '../lib/hook-base.mjs';

const LIFECYCLE_CLI = path.join(repoRoot, 'scripts', 'dev', 'worktree-lifecycle.cjs');
const RELEASING_END_REASONS = new Set(['logout', 'prompt_input_exit']);

function normalize(p) {
  return path.resolve(p).replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
}

/**
 * Pure decision. `sanctionedRoot` is the absolute path of `.claude/worktrees` for this repository.
 * @returns {{action:'release', args:string[], harness:string}|{action:'skip', reason:string}}
 */
export function decideRelease(input, { sanctionedRoot, mainRoot }) {
  if (!input || typeof input !== 'object') return { action: 'skip', reason: 'no input' };
  if (input.hook_event_name !== 'SessionEnd') return { action: 'skip', reason: `event ${input.hook_event_name} is not SessionEnd` };
  const harness = input.harness === 'codex-cli' ? 'codex' : 'claude';
  const sessionId = typeof input.session_id === 'string' && input.session_id ? input.session_id : null;
  if (!sessionId) return { action: 'skip', reason: 'no session id' };
  const cwd = typeof input.cwd === 'string' && input.cwd ? input.cwd : null;
  if (!cwd) return { action: 'skip', reason: 'no cwd' };
  if (harness === 'claude') {
    const reason = input.end_reason;
    if (!RELEASING_END_REASONS.has(reason)) return { action: 'skip', reason: `end_reason ${reason ?? 'missing'} does not end the worktree's use` };
  }
  const cwdN = normalize(cwd);
  if (mainRoot && cwdN === normalize(mainRoot)) return { action: 'skip', reason: 'session ended in the main checkout' };
  if (!sanctionedRoot || !(cwdN === normalize(sanctionedRoot) || cwdN.startsWith(`${normalize(sanctionedRoot)}/`))) {
    return { action: 'skip', reason: 'cwd is not under the sanctioned worktree root' };
  }
  const args = ['release', cwd, '--own', '--session-id', sessionId, '--no-fetch'];
  args.push(harness === 'claude' ? '--record-only' : '--if-clean');
  return { action: 'release', args, harness };
}

async function main() {
  const input = await readJsonStdin();
  const sanctionedRoot = path.join(mainRepoRoot, '.claude', 'worktrees');
  const decision = decideRelease(input, { sanctionedRoot, mainRoot: mainRepoRoot });
  if (decision.action !== 'release') return;
  const result = spawnSync(process.execPath, [LIFECYCLE_CLI, ...decision.args], { cwd: mainRepoRoot, encoding: 'utf8', timeout: 55000 });
  appendTelemetryEvent({
    event: result.status === 0 ? 'worktree_release' : 'worktree_release_failed',
    harness: decision.harness,
    cwd: input.cwd,
    message: String(result.stderr || '').split('\n').filter(Boolean).slice(-2).join(' | ').slice(0, 300),
    ts: new Date().toISOString(),
  });
}

runHook(import.meta.url, main);
