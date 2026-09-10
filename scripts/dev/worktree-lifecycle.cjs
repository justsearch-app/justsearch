#!/usr/bin/env node
'use strict';

/**
 * Worktree and branch lifecycle command (tempdoc 952).
 *
 * Sessions own exclusive use of a worktree; this command, run by the session or by the
 * reconciler, owns the end of its life. Every creation registers a termination obligation
 * (per-branch git config markers, worktree-register.cjs); release, receipts (landing-receipt.cjs),
 * archive-before-delete (worktree-archive.cjs) and the existing Windows-safe remover
 * (remove-worktree.cjs) discharge it.
 *
 * Subcommands
 *   create <name> [--harness claude|codex] [--session-id <id>] [--prepare] [--no-dist]
 *   register [<path>] [--harness ..] [--session-id <id>] [--fork <sha>] [--anchor <sha>]
 *   release [<path>] [--own] [--if-clean] [--record-only] [--discard-ignored <glob>]...
 *           [--keep-branch --reason <r> --owner <o> --review-by <date>] [--session-id <id>] [--no-fetch]
 *   hold <path|branch> --reason <r> --owner <o> --review-by <date>
 *   status [--json] [--lifecycle]
 *   reconcile [--execute] [--occasion <name>] [--json] [--no-fetch]
 *
 * Phase 1 (governance/worktree-lifecycle.v1.json executeOnSchedule=false): `reconcile` reports and
 * quarantines; it removes only when `--execute` is given AND the policy allows it. `release` may
 * remove the caller's own resource. Nothing here runs from a PreToolUse hook (861 [A4]).
 * An UNKNOWN receipt never retires a branch. Archive failure means quarantine, not removal.
 */

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const register = require('./lib/worktree-register.cjs');
const archive = require('./lib/worktree-archive.cjs');
const receipts = require('./lib/landing-receipt.cjs');
const { resolveMainRepoRoot, resolveCallerSessionId } = require('./lib/agent-spawn-sweep.cjs');

const SCRIPT_DIR = __dirname;
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
const REMOVE_WORKTREE = path.join(SCRIPT_DIR, 'remove-worktree.cjs');
const PREPARE_WORKTREE = path.join(SCRIPT_DIR, 'prepare-worktree.cjs');
const TAG = '[worktree-lifecycle]';

function log(msg) { console.error(`${TAG} ${msg}`); }
function fail(msg, code = 1) { console.error(`${TAG} ERROR: ${msg}`); process.exit(code); }

function git(args, { cwd = REPO_ROOT, timeoutMs = 60000, env = null } = {}) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: timeoutMs, env: env ? { ...process.env, ...env } : process.env });
  return { ok: result.status === 0, status: result.status, stdout: (result.stdout || '').trim(), stderr: (result.stderr || '').trim() };
}

function requireGit(args, label, opts) {
  const r = git(args, opts);
  if (!r.ok) fail(`${label} failed: ${r.stderr || r.stdout || `exit ${r.status}`}`);
  return r.stdout;
}

// ---------------------------------------------------------------------------------------------
// Argument parsing (tiny, explicit; unknown flags are errors so a typo cannot silently widen scope)
// ---------------------------------------------------------------------------------------------

const FLAG_SPECS = {
  create: { valued: ['harness', 'session-id'], boolean: ['prepare', 'no-dist'] },
  register: { valued: ['harness', 'session-id', 'fork', 'anchor'], boolean: [] },
  release: { valued: ['session-id', 'reason', 'owner', 'review-by', 'discard-ignored'], boolean: ['own', 'if-clean', 'record-only', 'keep-branch', 'no-fetch', 'json'] },
  hold: { valued: ['reason', 'owner', 'review-by'], boolean: [] },
  status: { valued: [], boolean: ['json', 'lifecycle'] },
  reconcile: { valued: ['occasion', 'session-id'], boolean: ['execute', 'json', 'no-fetch'] },
};

function parseArgs(argv) {
  const cmd = argv[2];
  if (!cmd || !FLAG_SPECS[cmd]) {
    fail('usage: node scripts/dev/worktree-lifecycle.cjs <create|register|release|hold|status|reconcile> [args] (see the file header)', 2);
  }
  const spec = FLAG_SPECS[cmd];
  const flags = { _: [] };
  for (const key of spec.valued) flags[key] = key === 'discard-ignored' ? [] : null;
  for (const key of spec.boolean) flags[key] = false;
  for (let i = 3; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) { flags._.push(arg); continue; }
    const eq = arg.indexOf('=');
    const name = eq === -1 ? arg.slice(2) : arg.slice(2, eq);
    if (spec.boolean.includes(name)) { flags[name] = true; continue; }
    if (!spec.valued.includes(name)) fail(`unknown argument ${JSON.stringify(arg)} for ${cmd}`, 2);
    const value = eq === -1 ? argv[i + 1] : arg.slice(eq + 1);
    if (value === undefined || (eq === -1 && value.startsWith('--'))) fail(`--${name} requires a value`, 2);
    if (eq === -1) i += 1;
    if (name === 'discard-ignored') flags[name].push(value); else flags[name] = value;
  }
  return { cmd, flags };
}

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

function mainRoot() { return resolveMainRepoRoot(REPO_ROOT); }

// The policy travels with the code that interprets it: read it from this script's checkout, not
// from the main checkout (which may be on an older main without the file).
function policyOf() { return register.loadPolicy({ repoRoot: REPO_ROOT }); }

function callerSession(flags) {
  return resolveCallerSessionId({ explicit: flags['session-id'], env: process.env, repoRoot: REPO_ROOT });
}

function harnessOf(flags) {
  if (flags.harness) return flags.harness;
  if (process.env.CODEX_HOME || process.env.CODEX_SESSION_ID) return 'codex';
  return 'claude';
}

function resolveWorktreePath(main, given) {
  const candidate = given ? path.resolve(given) : process.cwd();
  const top = git(['rev-parse', '--show-toplevel'], { cwd: candidate });
  if (!top.ok) fail(`${candidate} is not inside a git worktree`);
  const abs = path.resolve(top.stdout);
  if (samePath(abs, main)) fail('the main checkout is never a lifecycle resource (branch-safety.md rules 3-4)');
  const entries = register.listWorktrees({ repoRoot: main });
  const entry = entries.find((e) => samePath(e.path, abs));
  if (!entry) fail(`${abs} is not a registered worktree of ${main}`);
  return entry;
}

function samePath(a, b) { return path.resolve(a).replace(/\\/g, '/').toLowerCase() === path.resolve(b).replace(/\\/g, '/').toLowerCase(); }

function fetchOrigin(flags) {
  if (flags['no-fetch']) return;
  const r = git(['fetch', '--prune', '--quiet', 'origin'], { timeoutMs: 120000 });
  if (!r.ok) log(`fetch failed (${r.stderr.split('\n')[0]}); receipts will use the cached origin/main`);
}

function isClean(worktreePath) {
  const status = git(['status', '--porcelain=v1', '-z', '--untracked-files=all', '--ignored=no'], { cwd: worktreePath });
  if (!status.ok) return null;
  return status.stdout.length === 0;
}

function nowIso() { return new Date().toISOString(); }

/** A receipt for the branch head: cached, else computed and appended. Never throws. */
function receiptFor({ main, policy, branch, head, markers }) {
  const file = path.join(main, policy.receipts?.file || receipts.DEFAULT_RECEIPTS_FILE);
  const target = policy.receipts?.target || receipts.DEFAULT_TARGET;
  const cached = receipts.findValidReceipt({ file, head, repoRoot: main, target });
  if (cached) return { ...cached, cached: true };
  const fresh = receipts.computeReceipt({ repoRoot: main, head, branch, fork: markers?.fork || null, anchor: markers?.anchor || null, target });
  try { receipts.appendReceipt(file, fresh); } catch (err) { log(`receipt not cached: ${err.message}`); }
  return { ...fresh, cached: false };
}

function receiptCovers(receipt) {
  return receipt && (receipt.verdict === receipts.RECEIPT_VERDICTS.LANDED || receipt.verdict === receipts.RECEIPT_VERDICTS.REDUNDANT_NOW);
}

function ownPidIdentity() {
  return { sessionId: null, pid: process.pid, creationFileTimeUtc: null };
}

// ---------------------------------------------------------------------------------------------
// create / register
// ---------------------------------------------------------------------------------------------

function cmdCreate(flags) {
  const name = flags._[0];
  if (!name || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(name)) fail('create needs a worktree name: letters, digits, dot, underscore, dash', 2);
  const main = mainRoot();
  const policy = policyOf(main);
  const dest = path.join(main, policy.sanctionedRoot, name);
  if (fs.existsSync(dest)) fail(`${dest} already exists`);
  const branch = `worktree-${name}`;
  if (git(['rev-parse', '--verify', '--quiet', `refs/heads/${branch}`]).ok) fail(`branch ${branch} already exists; pick another name or release it first`);
  fetchOrigin(flags);
  const anchor = requireGit(['rev-parse', 'origin/main'], 'resolving origin/main');
  requireGit(['worktree', 'add', '-b', branch, dest, anchor], 'git worktree add', { cwd: main });
  const sessionId = callerSession(flags) || 'unknown';
  const harness = harnessOf(flags);
  register.writeMarkers({ repoRoot: main, branch, markers: { resource: name, session: sessionId, harness, created: nowIso(), fork: anchor, anchor } });
  if (harness !== 'claude') {
    const reason = register.formatLockReason({ harness, session: sessionId, pid: process.ppid || process.pid });
    const lock = git(['worktree', 'lock', '--reason', reason, dest], { cwd: main });
    if (!lock.ok) log(`lock not set: ${lock.stderr}`);
  }
  if (flags.prepare) {
    const args = [PREPARE_WORKTREE, ...(flags['no-dist'] ? ['--no-dist'] : [])];
    const r = spawnSync(process.execPath, args, { cwd: dest, stdio: 'inherit' });
    if (r.status !== 0) log(`prepare-worktree exited ${r.status}; the worktree is registered anyway`);
  }
  log(`created ${dest} on ${branch} from origin/main ${anchor.slice(0, 9)} (resource ${name}, session ${sessionId})`);
  process.stdout.write(`${dest}\n`);
}

function cmdRegister(flags) {
  const main = mainRoot();
  const entry = resolveWorktreePath(main, flags._[0]);
  if (!entry.branch) fail('a detached worktree cannot be registered: it has no branch to carry markers; release it instead');
  const existing = register.readMarkers({ repoRoot: main, branch: entry.branch });
  const sessionId = flags['session-id'] || callerSession(flags) || existing?.session || 'unknown';
  const markers = {
    resource: existing?.resource || path.basename(entry.path),
    session: sessionId,
    harness: flags.harness || existing?.harness || harnessOf(flags),
    created: existing?.created || nowIso(),
    fork: flags.fork || existing?.fork || git(['merge-base', 'origin/main', entry.head]).stdout || entry.head,
    anchor: flags.anchor || existing?.anchor || git(['rev-parse', 'origin/main']).stdout || '',
  };
  register.writeMarkers({ repoRoot: main, branch: entry.branch, markers });
  if (existing?.released) register.clearMarkers({ repoRoot: main, branch: entry.branch, fields: ['released'] });
  // Silent on success: this runs from a hook and must add nothing to the session's context.
}

// ---------------------------------------------------------------------------------------------
// hold
// ---------------------------------------------------------------------------------------------

function requireHoldTriple(flags) {
  const missing = ['reason', 'owner', 'review-by'].filter((k) => !flags[k]);
  if (missing.length) fail(`keeping a resource needs --reason, --owner and --review-by (missing: ${missing.join(', ')})`, 2);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(flags['review-by'])) fail('--review-by must be YYYY-MM-DD', 2);
  return { reason: flags.reason, owner: flags.owner, reviewBy: flags['review-by'] };
}

function cmdHold(flags) {
  const main = mainRoot();
  const target = flags._[0];
  if (!target) fail('hold needs a worktree path or a branch name', 2);
  let branch = target;
  if (fs.existsSync(target)) branch = resolveWorktreePath(main, target).branch;
  if (!branch || !git(['rev-parse', '--verify', '--quiet', `refs/heads/${branch}`]).ok) fail(`no local branch ${branch}`);
  const hold = requireHoldTriple(flags);
  register.writeMarkers({ repoRoot: main, branch, markers: { hold: register.formatHold(hold) } });
  log(`hold recorded on ${branch}: ${hold.reason} (owner ${hold.owner}, review by ${hold.reviewBy})`);
}

// ---------------------------------------------------------------------------------------------
// Finalization (shared by release and reconcile --execute)
// ---------------------------------------------------------------------------------------------

/**
 * Archive, verify, remove, retire. Restartable: every phase is recorded before the next starts.
 * Returns { done, quarantined, reason, receipt }.
 */
async function finalize({ main, policy, entry, markers, sessionId, discardIgnored, keepBranch, holdTriple, dryRun = false }) {
  const resource = markers?.resource || path.basename(entry.path);
  const archivePolicy = archive.loadPolicy(REPO_ROOT);
  const existingRecords = await register.readFinalizations({ mainRepoRoot: main });
  const existing = existingRecords.find((r) => r.ok && r.record.resource === resource)?.record || null;
  const by = { sessionId: sessionId || 'unknown', ...ownPidIdentity() };
  const claim = register.claimFinalization({ existing, by, now: Date.now() });
  if (!claim.claim) return { done: false, quarantined: true, reason: `finalization already claimed: ${claim.reason}` };

  // Receipt first: it decides the branch's fate and costs nothing destructive.
  const receipt = entry.branch ? receiptFor({ main, policy, branch: entry.branch, head: entry.head, markers }) : null;
  const retireBranch = Boolean(entry.branch) && !keepBranch && receiptCovers(receipt);
  if (dryRun) {
    return { done: false, quarantined: false, dryRun: true, receipt, retireBranch, reason: 'dry-run' };
  }

  let record = existing && existing.phase !== 'done'
    ? existing
    : register.buildFinalizationRecord({ resource, worktreePath: entry.path, branch: entry.branch, head: entry.head, receipt: receipt ? { verdict: receipt.verdict, squash: receipt.squash, pr: receipt.pr } : null, by, leaseDurationSec: policy.thresholds.finalizationLeaseSec });
  await register.writeFinalization({ mainRepoRoot: main, record });

  // Phase: archived
  let manifest = null;
  if (record.phase === 'claimed') {
    const result = archive.archiveWorktree({ mainRepoRoot: main, worktreePath: entry.path, resource, policy: archivePolicy, discardIgnored });
    if (result.refused) {
      return { done: false, quarantined: true, receipt, reason: `${result.reason}: ${result.message || ''} ${result.oversized ? result.oversized.map((o) => `${o.path} (${o.size} B)`).join(', ') : ''}`.trim() };
    }
    manifest = result.manifest;
    record = { ...record, manifestPath: result.manifestPath };
    record = await register.advanceFinalization({ mainRepoRoot: main, record, phase: 'archived' });
    log(`archived ${resource}: ${result.tipRef} and ${result.stateRef} (${manifest.files.length} file(s))`);
  } else if (record.manifestPath && fs.existsSync(record.manifestPath)) {
    manifest = JSON.parse(fs.readFileSync(record.manifestPath, 'utf8'));
  }

  // Phase: verified
  if (record.phase === 'archived') {
    if (!manifest) return { done: false, quarantined: true, receipt, reason: 'archived phase recorded but no manifest found; re-run after inspecting the finalization record' };
    const verify = archive.verifyArchive({ mainRepoRoot: main, manifest });
    if (!verify.ok) {
      return { done: false, quarantined: true, receipt, reason: `archive verification failed: missing=${verify.missing.length} mismatched=${verify.mismatched.length} errors=${(verify.errors || []).length}; the tree changed after archiving or an object is missing` };
    }
    record = await register.advanceFinalization({ mainRepoRoot: main, record, phase: 'verified' });
  }

  // Phase: removed (the existing Windows-safe remover is the only deletion primitive)
  if (record.phase === 'verified') {
    if (fs.existsSync(entry.path) || entry.prunable === false) {
      const args = [REMOVE_WORKTREE, entry.path, '--allow-ignored', '--session-id', sessionId || 'unknown'];
      if (record.manifestPath) args.push('--archive-manifest', record.manifestPath);
      if (retireBranch) args.push('--delete-branch');
      const r = spawnSync(process.execPath, args, { cwd: main, encoding: 'utf8', timeout: 600000 });
      if (r.status !== 0) {
        return { done: false, quarantined: true, receipt, reason: `remove-worktree refused: ${(r.stderr || '').trim().split('\n').filter((l) => /BLOCKER|ERROR|refus/i.test(l)).slice(0, 4).join(' | ')}` };
      }
    }
    record = await register.advanceFinalization({ mainRepoRoot: main, record, phase: 'removed' });
  }

  // Phase: retired (branch)
  if (record.phase === 'removed') {
    if (entry.branch) {
      const still = git(['rev-parse', '--verify', '--quiet', `refs/heads/${entry.branch}`]);
      if (still.ok && !retireBranch) {
        const hold = holdTriple ? register.formatHold(holdTriple) : null;
        const kept = { released: nowIso() };
        if (hold) kept.hold = hold;
        register.writeMarkers({ repoRoot: main, branch: entry.branch, markers: kept });
        log(`branch ${entry.branch} kept: ${receipt ? `${receipt.verdict}${receipt.reason ? ` (${receipt.reason})` : ''}` : 'no receipt'}${hold ? `; hold ${holdTriple.reason}` : ''}`);
      } else if (still.ok && retireBranch) {
        // remove-worktree's --delete-branch already retired it when it ran; a leftover here means it
        // was skipped (directory already gone). Conditional delete with the expected value.
        const del = git(['update-ref', '-d', `refs/heads/${entry.branch}`, entry.head]);
        if (!del.ok) return { done: false, quarantined: true, receipt, reason: `branch retirement refused: ${del.stderr}` };
        log(`retired branch ${entry.branch} at ${entry.head.slice(0, 9)} (${receipt.verdict})`);
      }
    }
    record = await register.advanceFinalization({ mainRepoRoot: main, record, phase: 'retired' });
  }

  if (record.phase === 'retired') {
    record = await register.advanceFinalization({ mainRepoRoot: main, record, phase: 'done' });
    await register.removeFinalization({ mainRepoRoot: main, resource });
  }
  return { done: true, quarantined: false, receipt, retireBranch };
}

// ---------------------------------------------------------------------------------------------
// release
// ---------------------------------------------------------------------------------------------

async function cmdRelease(flags) {
  const main = mainRoot();
  const policy = policyOf(main);
  const entry = resolveWorktreePath(main, flags._[0]);
  const sessionId = callerSession(flags);
  const markers = entry.branch ? register.readMarkers({ repoRoot: main, branch: entry.branch }) : null;

  if (flags.own) {
    const ownerSession = markers?.session || register.parseLockReason(entry.locked || '')?.session || null;
    if (!sessionId || !ownerSession || ownerSession !== sessionId) {
      log(`not released: ${entry.path} is owned by session ${ownerSession || 'unknown'}, caller is ${sessionId || 'unknown'}`);
      return;
    }
  }
  const clean = isClean(entry.path);
  if (flags['if-clean'] && clean !== true) {
    if (entry.branch) register.writeMarkers({ repoRoot: main, branch: entry.branch, markers: { released: nowIso() } });
    log(`released (kept for the reconciler): ${entry.path} has ${clean === null ? 'an unreadable status' : 'uncommitted changes'}`);
    return;
  }
  if (entry.locked && !flags['record-only']) {
    const parsed = register.parseLockReason(entry.locked);
    if (parsed && parsed.harness !== 'claude') {
      const unlock = git(['worktree', 'unlock', entry.path], { cwd: main });
      if (!unlock.ok) fail(`could not unlock ${entry.path}: ${unlock.stderr}`);
    } else {
      log(`held by a harness lock (${entry.locked}); recording the release only`);
      flags['record-only'] = true;
    }
  }
  if (entry.branch) register.writeMarkers({ repoRoot: main, branch: entry.branch, markers: { released: nowIso() } });
  if (flags['record-only']) {
    log(`released ${entry.path} (record only); the reconciler or the harness completes removal`);
    return;
  }
  fetchOrigin(flags);
  const holdTriple = flags['keep-branch'] ? requireHoldTriple(flags) : null;
  const outcome = await finalize({ main, policy, entry, markers, sessionId, discardIgnored: flags['discard-ignored'], keepBranch: Boolean(holdTriple), holdTriple });
  if (flags.json) process.stdout.write(`${JSON.stringify(outcome)}\n`);
  if (outcome.quarantined) fail(`quarantined: ${outcome.reason}`, 3);
  log(`released and removed ${entry.path}${outcome.retireBranch ? ` and retired ${entry.branch}` : ''}`);
}

// ---------------------------------------------------------------------------------------------
// status / reconcile
// ---------------------------------------------------------------------------------------------

async function gatherStatus({ main, policy }) {
  const c = await register.census({ mainRepoRoot: main, policy });
  const archivePolicy = archive.loadPolicy(REPO_ROOT);
  const archives = archive.listArchives({ mainRepoRoot: main, policy: archivePolicy });
  return { census: c, archives };
}

function formatRow(w, root) {
  const hold = w.markers?.hold ? ` hold=${register.parseHold(w.markers.hold).reviewBy}` : '';
  const rel = w.path.replace(/\\/g, '/').replace(`${root.replace(/\\/g, '/')}/`, '');
  return `${w.state.padEnd(11)} ${(w.branch || 'detached').padEnd(44)} ${String(w.ageDays == null ? '?' : `${w.ageDays.toFixed(0)}d`).padStart(4)} ${rel}${hold}${w.reasons.length ? `  (${w.reasons[0]})` : ''}`;
}

async function cmdStatus(flags) {
  const root = mainRoot();
  const policy = policyOf(root);
  const data = await gatherStatus({ main: root, policy });
  if (flags.json) { process.stdout.write(`${JSON.stringify(data, null, 2)}\n`); return; }
  const { census, archives } = data;
  console.log(`worktrees: ${census.worktrees.length} managed, ${census.unmanaged.length} unmanaged, ${census.leftoverBranches.length} leftover branch(es), ${census.finalizing.length} finalizing`);
  for (const w of census.worktrees) console.log('  ' + formatRow(w, root));
  for (const w of census.unmanaged) console.log('  ' + formatRow(w, root));
  for (const b of census.leftoverBranches) console.log(`  ${b.state.padEnd(11)} ${b.branch.padEnd(44)} ${String(b.ageDays == null ? '?' : `${b.ageDays.toFixed(0)}d`).padStart(4)} (branch only)`);
  if (flags.lifecycle) {
    const bytes = archives.reduce((s, a) => s + (a.bytes || 0), 0);
    console.log(`archives: ${archives.length}, ${(bytes / 1048576).toFixed(1)} MB, oldest ${archives.length ? archives[archives.length - 1].createdAt : '-'}`);
    for (const f of census.finalizing) console.log(`  finalizing ${f.resource}: ${f.readable ? `${f.phase} since ${f.startedAt} (lease ${f.lease})` : `unreadable record: ${f.reason}`}`);
  }
}

async function cmdReconcile(flags) {
  const root = mainRoot();
  const policy = policyOf(root);
  const execute = flags.execute && policy.executeOnSchedule === true;
  if (flags.execute && !execute) log('executeOnSchedule is false in governance/worktree-lifecycle.v1.json (phase 1): reporting only');
  fetchOrigin(flags);
  const sessionId = callerSession(flags);
  const { census } = await gatherStatus({ main: root, policy });
  const obligations = [];

  for (const w of census.worktrees) {
    if (w.state === register.STATES.RELEASED || w.state === register.STATES.ORPHANED) {
      const markers = w.markers;
      const receipt = w.branch ? receiptFor({ main: root, policy, branch: w.branch, head: w.head, markers }) : null;
      const action = receiptCovers(receipt) ? 'remove and retire branch' : 'archive and remove; keep branch (unlanded)';
      obligations.push({ kind: 'worktree', ...w, receipt: receipt?.verdict || null, action });
      if (execute) {
        const outcome = await finalize({ main: root, policy, entry: { path: w.path, branch: w.branch, head: w.head, locked: w.lock, prunable: false }, markers, sessionId, discardIgnored: [], keepBranch: false, holdTriple: null });
        obligations[obligations.length - 1].outcome = outcome.done ? 'done' : `quarantined: ${outcome.reason}`;
      }
    } else if (w.state === register.STATES.SUSPECT || w.state === register.STATES.QUARANTINED) {
      obligations.push({ kind: 'worktree', ...w, receipt: null, action: w.state === register.STATES.SUSPECT ? `verify the owner; orphan after ${policy.thresholds.orphanGraceHours} h` : 'human decision' });
    }
  }
  for (const b of census.leftoverBranches) {
    if (b.state !== register.STATES.RELEASED) continue;
    const head = git(['rev-parse', `refs/heads/${b.branch}`]).stdout;
    const receipt = receiptFor({ main: root, policy, branch: b.branch, head, markers: b.markers });
    const covered = receiptCovers(receipt);
    obligations.push({ kind: 'branch', branch: b.branch, head, receipt: receipt.verdict, action: covered ? 'retire branch' : 'keep (unlanded): publish or hold' });
    if (execute && covered) {
      // A branch-only leftover has no tree to archive: keep its tip under refs/archive, then delete
      // the ref conditionally on the value the receipt was computed for.
      const refName = `${archive.loadPolicy(REPO_ROOT).refPrefix}/${b.markers.resource}/${head.slice(0, 9)}`;
      const keep = git(['update-ref', refName, head, '']);
      const del = keep.ok || git(['rev-parse', '--verify', '--quiet', refName]).stdout === head
        ? git(['update-ref', '-d', `refs/heads/${b.branch}`, head])
        : { ok: false, stderr: `tip ref ${refName} could not be written` };
      obligations[obligations.length - 1].outcome = del.ok ? 'retired' : `refused: ${del.stderr}`;
    }
  }

  if (flags.json) { process.stdout.write(`${JSON.stringify({ execute, obligations, finalizing: census.finalizing }, null, 2)}\n`); return; }
  if (obligations.length === 0 && census.finalizing.length === 0) return; // silent when nothing needs a decision (952 §5.9)
  console.log(`${TAG} ${obligations.length} obligation(s)${execute ? ' (execute mode)' : ' (advisory: nothing removed)'}`);
  for (const o of obligations) {
    console.log(`  ${o.state || 'branch'}${o.kind === 'branch' ? ' branch' : ''} ${o.branch || o.path}: ${o.action}${o.receipt ? ` [${o.receipt}]` : ''}${o.outcome ? ` -> ${o.outcome}` : ''}`);
  }
  for (const f of census.finalizing) console.log(`  finalizing ${f.resource}: ${f.readable ? `${f.phase} (lease ${f.lease})` : `unreadable: ${f.reason}`}`);
}

// ---------------------------------------------------------------------------------------------

async function run(argv = process.argv) {
  const { cmd, flags } = parseArgs(argv);
  switch (cmd) {
    case 'create': return cmdCreate(flags);
    case 'register': return cmdRegister(flags);
    case 'release': return cmdRelease(flags);
    case 'hold': return cmdHold(flags);
    case 'status': return cmdStatus(flags);
    case 'reconcile': return cmdReconcile(flags);
    default: return fail(`unknown command ${cmd}`, 2);
  }
}

if (require.main === module) {
  run().catch((err) => fail(err && err.stack ? err.stack : String(err)));
}

module.exports = { parseArgs, finalize, receiptFor, receiptCovers, run };
