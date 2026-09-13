#!/usr/bin/env node
/**
 * Tempdoc 618 §2: make a git worktree dev-ready in one command.
 *
 * A worktree is a fresh checkout: it needs its own `node_modules` (for FE typecheck/test/Vite) and
 * its own installDist before the dev stack will start. What it does NOT need a setup step for:
 *   - models: the dev-runner resolves JUSTSEARCH_MODELS_DIR from the MAIN checkout (618 §2);
 *   - GPU runtime: the dev-runner resolves the shared cuda12 llama-server from the MAIN checkout
 *     (tempdoc 656 Move 2), so every worktree references one copy — no per-worktree download.
 *
 * GPU runtime is GPU-only by design (tempdoc 656): dev never provisions a CPU llama-server (a CPU
 * fallback runs the 9B model ~10x slower and saturates every core, DOSing concurrent worktrees). To
 * make the shared GPU runtime available, run ONCE at the MAIN checkout:
 *   ./gradlew :modules:ui:stageLlamaCudaVariant     # ~600 MB one-time download of the cuda12 build
 * After that the dev-runner auto-populates the shared native-bin and every worktree references it.
 * If it is absent, inference is cleanly unavailable (fails closed) — search still works.
 *
 * What it DOES need (post-cutover): .mcp.json and .claude/settings.local.json are gitignored
 * (maintainer-local, not tracked) — whether a fresh worktree starts with them depends on whether
 * your base checkout already had them, so don't rely on it. This script seeds both from their
 * committed `.example` files if missing (never overwrites an existing one) — fill in per-machine
 * values (github PAT, permissions/env) in the copies afterward. See MAINTAINING.md.
 *
 * Usage (run from inside the worktree):
 *   node scripts/dev/prepare-worktree.cjs            # npm ci + installDist
 *   node scripts/dev/prepare-worktree.cjs --no-dist  # FE-only (skip the Java dist)
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
// Tempdoc 696: resolve a >= 24 JDK (Temurin 25) for the installDist gradle step so a
// stale JDK-8 JAVA_HOME can't break worktree prep. Resolved lazily (only for gradle).
const { resolveJdkHome } = require(path.join(__dirname, 'lib', 'resolve-jdk.cjs'));

const repoRoot = path.resolve(__dirname, '..', '..');
const noDist = process.argv.includes('--no-dist');
const isWin = process.platform === 'win32';
const npm = isWin ? 'npm.cmd' : 'npm';
// Absolute, cwd-qualified path: a bare 'gradlew.bat' is not reliably resolved
// from the child's cwd by every spawn environment ("'gradlew.bat' is not
// recognized" — tempdoc 684), even with cwd set correctly.
const gradle = isWin ? path.join(repoRoot, 'gradlew.bat') : path.join(repoRoot, 'gradlew');

function run(cmd, args, cwd, extraEnv) {
  console.error(`[prepare-worktree] $ ${cmd} ${args.join(' ')}  (cwd: ${cwd})`);
  const env = extraEnv ? { ...process.env, ...extraEnv } : process.env;
  const r = spawnSync(cmd, args, { cwd, stdio: 'inherit', shell: true, env });
  if (r.status !== 0) {
    console.error(`[prepare-worktree] FAILED (${r.status}): ${cmd} ${args.join(' ')}`);
    process.exit(r.status || 1);
  }
}

// Tempdoc 746 item 6: root `.worktreeinclude` now lists these same two paths, so Claude Code
// copies the REAL (gitignored) files from the parent checkout into a new worktree at creation
// time, before this script ever runs. The existsSync check below is what makes that composition
// safe — this function is a fallback (seeds a blank `.example` template) only when no real file
// was already copied in, and never clobbers one that was.
function seedFromExample(exampleRelPath, destRelPath) {
  const dest = path.join(repoRoot, destRelPath);
  const example = path.join(repoRoot, exampleRelPath);
  if (fs.existsSync(dest)) {
    console.error(`[prepare-worktree] ${destRelPath} already exists — leaving it as-is`);
    return;
  }
  if (!fs.existsSync(example)) {
    console.error(`[prepare-worktree] WARNING: ${exampleRelPath} not found — cannot seed ${destRelPath}`);
    return;
  }
  fs.copyFileSync(example, dest);
  console.error(`[prepare-worktree] seeded ${destRelPath} from ${exampleRelPath}`);
}

// 0. Seed maintainer-local config (gitignored; a fresh worktree may or may not start with it).
seedFromExample('.mcp.json.example', '.mcp.json');
seedFromExample(path.join('.claude', 'settings.local.json.example'), path.join('.claude', 'settings.local.json'));
console.error('[prepare-worktree] if .mcp.json was just created: the justsearch-dev server needs no secret and works immediately.');

// 0b. Hook wiring drift (tempdoc 872). `.worktreeinclude` copies the parent's REAL
// settings.local.json in, so a new worktree inherits whatever hooks block the parent had —
// which can name a hook the manifest has since removed (the Stop hook then exits 1 visibly
// every turn). The generator only rewrites the hooks block, so regenerating is safe.
{
  const settingsLocal = path.join(repoRoot, '.claude', 'settings.local.json');
  const gen = path.join(repoRoot, 'scripts', 'codegen', 'gen-agent-hooks-wiring.mjs');
  if (fs.existsSync(settingsLocal) && fs.existsSync(gen)) {
    const check = spawnSync(process.execPath, [gen, '--check'], { cwd: repoRoot, stdio: 'pipe', encoding: 'utf8' });
    if (check.status !== 0) {
      console.error('[prepare-worktree] hooks block drifted from governance/agent-hooks.v1.json — regenerating');
      run(process.execPath, [gen], repoRoot);
    }
  }
}

// 1. FE deps — npm ci (clean, lockfile-pinned; same command the build task now uses).
run(npm, ['ci'], path.join(repoRoot, 'modules', 'ui-web'));

// 2. Dists the dev stack launches from (skippable for FE-only work).
if (!noDist) {
  let devJdkHome;
  try {
    devJdkHome = resolveJdkHome();
  } catch (e) {
    // Report via this file's convention (a clean one-liner), not a raw Node stack trace.
    console.error(`[prepare-worktree] ${e.message}`);
    process.exit(1);
  }
  // Lane F stage A item A13: one distribution. `:modules:indexer-worker:installDist` used to be
  // built alongside this one for the Worker child process; that process and its `application`
  // distribution are gone, and the Engine dist carries the worker jars.
  run(gradle, [':modules:ui:installDist'], repoRoot, {
    JAVA_HOME: devJdkHome,
  });
}

console.error('[prepare-worktree] done — models + the shared cuda12 GPU runtime resolve automatically via the dev-runner (run `./gradlew :modules:ui:stageLlamaCudaVariant` once at the main checkout to provision the GPU runtime; GPU-only by design — tempdoc 656).');
