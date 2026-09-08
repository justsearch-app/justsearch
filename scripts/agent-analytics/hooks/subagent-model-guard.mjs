#!/usr/bin/env node

/**
 * Synchronous PreToolUse hook (matcher: "Agent") — subagent model guard.
 *
 * Closes the premium-model inheritance leak (claude-code#74788): an Agent spawn
 * with no explicit `model` resolves to the MAIN session's model, so when the main
 * thread runs Fable 5 (credits-billed), every unpinned subagent silently bills
 * fable-tier for worker-grade work. Live-reproduced 2026-07-12 in this repo: an
 * unpinned probe spawn reported `claude-fable-5` (~52K tokens for a one-line reply).
 *
 * Policy (owner decision 2026-07-12, extends CLAUDE.md "Model routing"):
 *   - `model` explicitly set to sonnet/opus/haiku (any form) → allowed.
 *   - `model` missing            → BLOCK: re-call with an explicit model.
 *   - `model` fable-family       → BLOCK: fable is main-thread-only.
 *
 * Coverage (live-verified 2026-07-12):
 *   - Top-level Agent spawns: guarded (all three paths tested — unpinned blocked,
 *     fable blocked, haiku passed).
 *   - Nested spawns: ALSO guarded — verified live, a sonnet subagent's unpinned
 *     child was blocked by this hook (historically described as an exception to "parent hooks
 *     don't fire in subagents" lesson; see agent-lessons.md).
 *   - NOT verified: fork-type subagents (ignore `model` entirely per #74788) and
 *     the background-agent dispatch-picker path (#64493).
 *
 * Kill switch: JUSTSEARCH_DISABLE_HOOKS=1 (hook-base).
 */

import { hooksDisabled } from '../lib/hook-base.mjs';

const FABLE_PATTERN = /fable/i;

/** Pure decision core: takes the tool input, returns a decision — no I/O. */
export function evaluateAgentSpawn(toolInput) {
  const model = toolInput?.model;
  if (typeof model === 'string' && model.trim() !== '') {
    if (FABLE_PATTERN.test(model)) {
      return {
        block: true,
        reason:
          `Subagent model "${model}" is blocked: fable is reserved for the interactive main thread ` +
          '(credits-billed; claude-code#74788 inheritance leak). Re-call with model: "sonnet" ' +
          '(implementation floor), "haiku" (only where wrong output is self-evident), or "opus".',
      };
    }
    return { block: false };
  }
  return {
    block: true,
    reason:
      'Agent spawn has no explicit `model`. An unpinned spawn inherits the MAIN session model ' +
      '(currently fable-tier — credits-billed; verified live, claude-code#74788). Re-call with an ' +
      'explicit model: "sonnet" (implementation floor), "haiku" (cheap/self-evident), or "opus". ' +
      'Nested spawns must pin their models too; session-wide hook coverage still depends ' +
      'on the active client configuration and matching tool path.',
  };
}

async function main() {
  if (hooksDisabled()) return;
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  try {
    const input = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (input.tool_name !== 'Agent') return;
    const verdict = evaluateAgentSpawn(input.tool_input);
    if (verdict.block) {
      process.stderr.write(verdict.reason);
      process.exit(2);
    }
  } catch {
    // Parse failure — fail open, never block on our own bug.
  }
}

main().catch(() => process.exit(0));
