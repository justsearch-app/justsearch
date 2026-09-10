#!/usr/bin/env node

/**
 * PostToolUse hook for EnterWorktree.
 *
 * Mechanizes `verify-worktree-base` (.claude/rules/branch-safety.md, previously prose-only).
 * `worktree.baseRef: "fresh"` (.claude/settings.json, tempdoc 940) should make a new
 * worktree's HEAD equal `origin/main` by construction (the harness fetches the default
 * branch when it is >24h stale, 5s cap, and falls back to the cached ref), but a skipped or
 * timed-out fetch, a harness-version quirk, or a manual `git worktree add` can silently
 * violate that (tempdoc 618 §1 — an agent builds for hours on a stale base without
 * noticing). This hook compares the new worktree's HEAD with `origin/main` right after
 * creation and surfaces a mismatch immediately instead of relying on the agent to remember.
 *
 * Tempdoc 940: the setting used to be `"head"` (branch from the main checkout's HEAD), which
 * carried every commit anyone had ever made on local `main` into new worktrees. Local `main`
 * is PR-only (branch protection), so those commits can never be published from `main`; by
 * 2026-09-06 there were 297 of them and two PRs had silently grown to 134 and 189 files.
 * Worktrees no longer derive from local `main`, but a `main` that is AHEAD of `origin/main` is
 * still the signature of someone committing there, so this hook now reports it loudly.
 *
 * Tempdoc 727 F-3: the HEAD-equality check is silent in a *different* real failure mode — the
 * new worktree and main can share the same commit while main has UNCOMMITTED changes at the
 * moment of branching that a worktree created from a commit can never see (the triggering
 * incident: a worktree missed an uncommitted "Direction note" resolving the task's core
 * question, discovered only mid-session). So this hook also checks for uncommitted changes in
 * the main checkout, worded as a neutral FYI, not an alarm — branch-safety.md documents
 * shared-main WIP from other agents as a normal condition, so a blanket warning would mostly be
 * noise; naming what's uncommitted lets the agent judge relevance itself.
 *
 * - Synchronous (blocks until return, <5s via spawnSync); no network — it reads the cached
 *   `origin/main` ref, which the "fresh" base-ref logic has just refreshed
 * - Git subprocess calls: rev-parse in the worktree + rev-parse/rev-list/status in main
 * - Outputs hookSpecificOutput.additionalContext only when there is something to say
 */

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { hooksDisabled } from '../lib/hook-base.mjs';

// repoRoot resolution: the script's own location is
// always under the MAIN checkout's scripts/ dir regardless of the session's current
// worktree cwd, so this reliably resolves to main even when invoked from a worktree.
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..', '..');

function git(cwd, args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: 5000 });
  if (result.status !== 0) return null;
  return result.stdout.trim();
}

function headOf(cwd) {
  return git(cwd, ['rev-parse', 'HEAD']);
}

/** The cached remote default branch tip, or null if there is no such ref (no remote). */
function originMainOf(cwd) {
  return git(cwd, ['rev-parse', '--verify', '--quiet', 'origin/main']);
}

/** Tempdoc 940: how many commits local `main` has that `origin/main` lacks (null if unknown). */
function mainAheadCountOf(cwd) {
  const out = git(cwd, ['rev-list', '--count', 'origin/main..main']);
  if (out === null || out === '') return null;
  const n = Number(out);
  return Number.isFinite(n) ? n : null;
}

/** Tempdoc 727 F-3: short-status lines for uncommitted changes in `cwd` (empty array if clean). */
function uncommittedChangesIn(cwd) {
  const result = spawnSync('git', ['status', '--porcelain'], { cwd, encoding: 'utf8', timeout: 5000 });
  if (result.status !== 0) return [];
  return result.stdout.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
}

/**
 * Pure decision function: given the worktree HEAD, the cached `origin/main`, how far local
 * `main` is ahead of it, and main's uncommitted-change lines, build the additionalContext
 * string, or null if there's nothing worth surfacing. Exported for direct unit testing (no
 * subprocess/stdin involved).
 */
export function buildWorktreeBaseNotes({ worktreeHead, originMainHead, mainAheadCount, changes }) {
  const notes = [];

  // Tempdoc 940: the loud one. A local `main` ahead of origin is stranded work by construction.
  if (typeof mainAheadCount === 'number' && mainAheadCount > 0) {
    notes.push(
      `STOP: the main checkout's \`main\` is ${mainAheadCount} commit(s) AHEAD of origin/main. ` +
      `\`main\` is PR-only, so those commits can never be published from there and any branch ` +
      `built on them drags them into its PR (tempdoc 940: 297 stranded commits, two polluted PRs). ` +
      `This worktree was branched from origin/main, not from local main, so it does NOT carry them — ` +
      `keep it that way: never \`git merge main\` here. Tell the user local main needs realigning ` +
      `(.claude/rules/branch-safety.md, never-commit-on-local-main).`
    );
  }

  if (worktreeHead && originMainHead && worktreeHead !== originMainHead) {
    notes.push(
      `Worktree base mismatch: this worktree's HEAD (${worktreeHead.slice(0, 12)}) differs from ` +
      `origin/main (${originMainHead.slice(0, 12)}). worktree.baseRef:"fresh" should make these ` +
      `equal; the harness fetch may have been skipped or timed out. Run \`git fetch origin main && ` +
      `git merge origin/main\` and verify this worktree contains the work you expect before coding ` +
      `(.claude/rules/branch-safety.md, verify-worktree-base).`
    );
  }

  // Tempdoc 727 F-3: even when HEADs match, main may hold uncommitted work this
  // worktree (branched from a commit, not a working tree) cannot see.
  if (changes && changes.length > 0) {
    const shown = changes.slice(0, 8).join('; ');
    const more = changes.length > 8 ? ` (+${changes.length - 8} more)` : '';
    notes.push(
      `FYI: the main checkout has ${changes.length} uncommitted change(s) not visible in this ` +
      `worktree (it was branched from a commit, not from main's working tree): ${shown}${more}. ` +
      `If any of these are relevant to your task, check main directly before assuming this ` +
      `worktree has everything.`
    );
  }

  return notes.length > 0 ? notes.join('\n\n') : null;
}

async function main() {
  // Tempdoc 727 review Finding C: this hook shells out to `git` several times per
  // EnterWorktree — honor the kill switch like the other hooks that follow this convention.
  if (hooksDisabled()) return;

  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    if (input.tool_name !== 'EnterWorktree') return;

    const worktreePath = input.tool_response?.worktreePath;
    if (!worktreePath) return; // creation failed or switched into an existing worktree via `path`

    const additionalContext = buildWorktreeBaseNotes({
      worktreeHead: headOf(worktreePath),
      originMainHead: originMainOf(repoRoot),
      mainAheadCount: mainAheadCountOf(repoRoot),
      changes: uncommittedChangesIn(repoRoot),
    });
    if (!additionalContext) return;

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext,
      },
    }));
  } catch {
    // Parse failure — no output, don't block
  }
}

main().catch(() => process.exit(0));
