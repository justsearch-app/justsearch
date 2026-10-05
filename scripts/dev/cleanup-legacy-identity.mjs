#!/usr/bin/env node
/**
 * Remove the leftovers of the retired session-identity mechanism. DRY-RUN BY DEFAULT.
 *
 * Agent sessions are now told apart by their harness process (scripts/dev/lib/agent-identity.cjs).
 * What the retired session hooks left behind is never identity and is read by nothing:
 *
 *   - `tmp/agent-telemetry/current-session-id` pointer files, in the main checkout and in every
 *     registered worktree. One file per checkout named whichever session last started there, so in
 *     a shared checkout it routinely named a FOREIGN session.
 *   - `<state root>/sessions/*.json` per-session activity stamps (the retired ledger) not modified
 *     in the last hour (so a pre-change writer still running in an older checkout is not raced).
 *
 * It also prunes the per-session data of ENDED owners: `<state root>/owners/<key>.json` dev-stack
 * touch records whose owner process has ended (pid gone or reused) and that are older than the
 * retention bound (JUSTSEARCH_DEV_OWNER_RETENTION_MS, default 3 days). A record whose owner is alive
 * or cannot be judged is kept.
 *
 * Usage:
 *   node scripts/dev/cleanup-legacy-identity.mjs [--json] [--execute] [--repo <path>]
 *
 * Without --execute it only lists what it would remove. The state root honours
 * JUSTSEARCH_DEV_RUNNER_STATE_ROOT; --repo picks the checkout (default: this script's main checkout).
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { resolveMainRepoRoot } = require('./lib/agent-spawn-sweep.cjs');
const ownerPresence = require('./lib/owner-presence.cjs');
const { readTableSync } = require('./lib/agent-identity.cjs');

const POINTER_REL = path.join('tmp', 'agent-telemetry', 'current-session-id');
const STAMP_MIN_AGE_MS = 60 * 60 * 1000;
const LIST_CAP = 20;

function argValue(argv, name) {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null;
}

/** Every checkout of the repository: the main one plus each registered worktree. */
export function listCheckouts(mainRoot) {
  const roots = [path.resolve(mainRoot)];
  const r = spawnSync('git', ['worktree', 'list', '--porcelain'], { cwd: mainRoot, encoding: 'utf8', windowsHide: true });
  if (r.status === 0) {
    for (const line of String(r.stdout).split(/\r?\n/)) {
      if (!line.startsWith('worktree ')) continue;
      const p = path.resolve(line.slice('worktree '.length).trim());
      if (!roots.some((x) => x.toLowerCase() === p.toLowerCase())) roots.push(p);
    }
  }
  return roots;
}

export function stateRootFor(mainRoot, env = process.env) {
  const override = env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
  return override && override.trim() ? path.resolve(override.trim()) : path.join(mainRoot, 'tmp', 'dev-runner');
}

/**
 * The plan: what would be removed. Reads only.
 * @returns {{ pointerFiles: string[], sessionStamps: string[], ownerRecords: string[] }}
 */
export function planCleanup({ mainRoot, env = process.env, table = undefined, now = Date.now() } = {}) {
  const pointerFiles = [];
  for (const root of listCheckouts(mainRoot)) {
    const f = path.join(root, POINTER_REL);
    try { if (fs.lstatSync(f).isFile()) pointerFiles.push(f); } catch { /* absent */ }
  }
  const stateRoot = stateRootFor(mainRoot, env);
  const sessionStamps = [];
  const sessionsDir = path.join(stateRoot, 'sessions');
  try {
    for (const name of fs.readdirSync(sessionsDir)) {
      const f = path.join(sessionsDir, name);
      const st = fs.lstatSync(f);
      if (name.endsWith('.json') && st.isFile() && now - st.mtimeMs > STAMP_MIN_AGE_MS) sessionStamps.push(f);
    }
  } catch { /* absent */ }
  const ownerRecords = [];
  const ownersDir = ownerPresence.ownersDir(stateRoot);
  let names = [];
  try { names = fs.readdirSync(ownersDir); } catch { /* absent */ }
  if (names.some((n) => n.endsWith('.json'))) {
    const evidence = table === undefined ? readTableSync({ env }) : table;
    const { touchRetentionMs } = ownerPresence.presenceThresholds(env);
    for (const name of names) {
      if (!name.endsWith('.json')) continue;
      const key = name.slice(0, -'.json'.length);
      const rec = ownerPresence.readOwnerRecord(stateRoot, key);
      if (!rec) continue;
      const touched = new Date(rec.lastDevStackTouchAt ?? 0).getTime();
      if (Number.isFinite(touched) && now - touched < touchRetentionMs) continue;
      const live = ownerPresence.ownerLiveness({ key, pid: rec.pid, creationTime: rec.creationTime }, { table: evidence, now });
      if (live.state === 'ended') ownerRecords.push(path.join(ownersDir, name));
    }
  }
  return { stateRoot, pointerFiles, sessionStamps, ownerRecords };
}

export function executeCleanup(plan) {
  const removed = [];
  const failed = [];
  for (const f of [...plan.pointerFiles, ...plan.sessionStamps, ...plan.ownerRecords]) {
    try { fs.rmSync(f, { force: true }); removed.push(f); } catch (err) { failed.push({ file: f, error: err.message }); }
  }
  return { removed, failed };
}

export function main(argv = process.argv.slice(2), env = process.env) {
  const execute = argv.includes('--execute');
  const json = argv.includes('--json');
  const repoArg = argValue(argv, '--repo');
  const scriptRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const mainRoot = resolveMainRepoRoot(repoArg ? path.resolve(repoArg) : scriptRoot);
  const plan = planCleanup({ mainRoot, env });
  const result = execute ? executeCleanup(plan) : null;
  const report = { mode: execute ? 'execute' : 'dry-run', mainRoot, ...plan, ...(result ? result : {}) };
  if (json) {
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  } else {
    const verb = execute ? 'removed' : 'would remove';
    const lines = [`[cleanup-legacy-identity] ${report.mode} (main checkout ${mainRoot}, state root ${plan.stateRoot})`];
    for (const [label, list] of [['pointer file', plan.pointerFiles], ['session stamp', plan.sessionStamps], ['ended-owner record', plan.ownerRecords]]) {
      lines.push(`  ${verb} ${list.length} ${label}(s)`);
      for (const f of list.slice(0, LIST_CAP)) lines.push(`    ${f}`);
      if (list.length > LIST_CAP) lines.push(`    ... and ${list.length - LIST_CAP} more (--json lists all)`);
    }
    if (!execute) lines.push('  (dry run: nothing changed; pass --execute to remove)');
    if (result?.failed.length) for (const x of result.failed) lines.push(`  FAILED ${x.file}: ${x.error}`);
    process.stdout.write(`${lines.join('\n')}\n`);
  }
  return result && result.failed.length ? 1 : 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main();
}
