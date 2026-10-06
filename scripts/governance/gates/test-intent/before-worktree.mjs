/**
 * The fail-before worktree's node_modules link and its safe teardown (run-evidence.mjs `--before`).
 *
 * The before-worktree's `modules/ui-web/node_modules` is a link (a junction on Windows) to the
 * candidate's. `git worktree remove --force` deletes THROUGH such a link, emptying the candidate's
 * node_modules, so the link itself is removed first and the forced removal only runs once the link
 * is verifiably gone.
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const rel = ['modules', 'ui-web', 'node_modules'];

export function linkNodeModules(repoRoot, worktree) {
  const src = path.join(repoRoot, ...rel);
  const dst = path.join(worktree, ...rel);
  if (!fs.existsSync(src) || fs.existsSync(dst)) return;
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.symlinkSync(src, dst, process.platform === 'win32' ? 'junction' : 'dir');
}

function lstatOrNull(p) {
  try {
    return fs.lstatSync(p);
  } catch {
    return null;
  }
}

/** Remove the link itself (never its target). Returns an error message, or null when no link remains. */
export function unlinkNodeModules(worktree) {
  const dst = path.join(worktree, ...rel);
  const st = lstatOrNull(dst);
  if (!st) return null;
  if (!st.isSymbolicLink()) return null; // a real directory belongs to the worktree; git removes it
  try {
    fs.unlinkSync(dst);
  } catch {
    try {
      fs.rmdirSync(dst); // a junction is removed as an empty directory entry, target untouched
    } catch (e) {
      return `cannot remove link ${dst}: ${e.message}`;
    }
  }
  return lstatOrNull(dst) ? `link ${dst} still present after removal` : null;
}

/** Tear down the before-worktree. Returns `{ removed, detail }`; the forced removal never runs with a link left. */
export function removeBeforeWorktree(repoRoot, worktree) {
  const err = unlinkNodeModules(worktree);
  if (err) return { removed: false, detail: `${err}; worktree left at ${worktree}` };
  const r = spawnSync('git', ['worktree', 'remove', '--force', worktree], { cwd: repoRoot, encoding: 'utf8' });
  if (r.status !== 0) return { removed: false, detail: `git worktree remove failed (${(r.stderr ?? '').trim()}); worktree left at ${worktree}` };
  return { removed: true, detail: '' };
}
