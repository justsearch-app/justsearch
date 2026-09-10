#!/usr/bin/env node
/**
 * Tempdoc 618 §2: junction-safe, long-path-safe worktree teardown.
 *
 * `git worktree remove` fails on Windows with "Filename too long" on deep node_modules paths and
 * leaves an orphan directory behind (reproduced across sessions; e.g. a stale
 * `.claude/worktrees/587-…` that is no longer a registered worktree). Worse, `rm -rf` / some tools
 * can delete *through* a node_modules **junction** into the main checkout's real node_modules
 * (silent data loss — §9). This script removes a worktree safely:
 *   1. unlinks directory junctions (reparse points) link-only, so each junction's target survives;
 *   2. deletes the remaining tree with long-path support (Node fs, then a `\\?\` .NET fallback —
 *      both verified in the 618 de-risk pass: .NET Directory.Delete is junction-safe and handles
 *      >260-char paths);
 *   3. removes only that worktree's Git registration.
 *
 * Usage:
 *   node scripts/dev/remove-worktree.cjs <worktree-path> [--dry-run] [--allow-ignored]
 *     [--delete-branch] [--merge-commit <sha>] [--session-id <id|unknown>]
 *
 * Safety: admission comes from the owning repository's exact registered-worktree list, not a
 * pathname convention. Main, aliases, nested registrations, locks, changes, and relevant live or
 * unknown runtime provenance are refused before filesystem mutation.
 *
 * Tempdoc 746 item 4 — junction-unlink KEPT (not superseded by Claude Code >=2.1.205's native
 * junction handling), for two independent reasons:
 *   1. Native handling covers harness-DRIVEN worktree removal only. This script also runs
 *      standalone from a shell (its documented usage above), a path native handling never sees.
 *   2. Deletion here never goes through `git worktree remove` (junction-safety of which would be
 *      moot anyway) — `main()` deletes the tree itself via `deleteTree` (fs.rmSync, falling back
 *      to a `\\?\`-prefixed .NET `Directory.Delete`) and removes the selected registration afterward,
 *      without a repository-wide prune. Empirically probed both of `deleteTree`'s own
 *      methods against a scratch junction (temp-dir fixture, Node v24.12.0 / Windows 11):
 *      fs.rmSync(recursive) turned out to already be junction-safe on its own (unlinks the
 *      reparse point, leaves the target untouched) — but the `.NET Directory.Delete` FALLBACK is
 *      NOT: it throws `UnauthorizedAccessException: Access to the path 'node_modules' is denied`
 *      on a tree containing an un-unlinked junction, and does not complete the delete at all. That
 *      fallback exists precisely for the long-path/held-handle cases this script was written to
 *      solve (§ above), so `removeJunctions` running first is what keeps the fallback able to
 *      finish, not merely "safe" in the no-data-loss sense.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const {
  PROCESS_TABLE_PS_COMMAND,
  describeJsonParseFailure,
  readProcessTable,
} = require('./lib/process-identity.cjs');
const {
  consultAgentSpawnsForTeardown,
  inspectAgentSpawnsForTeardown,
  describeEntry,
  resolveMainRepoRoot,
  resolveCallerSessionId,
  resolveDevRunnerStateRoot,
} = require('./lib/agent-spawn-sweep.cjs');
const {
  resolveForeignRegisterDir,
  readForeignRegister,
} = require('./lib/process-record.cjs');

let runtimeProbeModule = null;
async function probeRuntimePort(url) {
  if (!runtimeProbeModule) runtimeProbeModule = await import('./justsearch-dev-mcp/observations.mjs');
  return runtimeProbeModule.probeLoopbackHttpStatus(url, { timeoutMs: 800 });
}

function fail(msg) {
  throw new Error(msg);
}

// 1. Unlink directory junctions link-only so we never recurse through them into shared targets.
function removeJunctions(dir) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    let st;
    try {
      st = fs.lstatSync(p);
    } catch {
      continue;
    }
    if (st.isSymbolicLink()) {
      // Directory junction / symlink: remove the LINK only. rmdir does not follow the reparse point.
      try {
        fs.rmdirSync(p);
        console.error(`[remove-worktree] unlinked junction: ${p}`);
      } catch (err) {
        try {
          fs.unlinkSync(p);
          console.error(`[remove-worktree] unlinked: ${p}`);
        } catch {
          console.error(`[remove-worktree] WARN could not unlink ${p}: ${err.message}`);
        }
      }
    } else if (st.isDirectory()) {
      removeJunctions(p);
    }
  }
}

/** Synchronous sleep without shelling out to `sleep`. */
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/**
 * Long-path delete via .NET Directory.Delete, junction-safe and >260-char-safe.
 * Builds the `\\?\` extended-length path from an ABSOLUTE, backslash-normalized
 * path — a raw `p` (possibly forward-slashed / relative) makes .NET throw
 * "The filename, directory name, or volume label syntax is incorrect."
 */
function longPathDelete(p) {
  const extended = '\\\\?\\' + path.resolve(p).replace(/\//g, '\\');
  // PowerShell single-quoted strings are literal (no backslash-escaping) —
  // only a literal single quote needs doubling. JSON.stringify would be wrong
  // here: it escapes `\` for JS/JSON semantics, which PowerShell's
  // double-quoted string then reads back as DOUBLED literal backslashes,
  // producing an illegal path (".NET Directory.Delete: Illegal characters in
  // path" — reproduced while writing this fix).
  const psLiteral = "'" + extended.replace(/'/g, "''") + "'";
  const psCmd = `[System.IO.Directory]::Delete(${psLiteral}, $true)`;
  return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psCmd], {
    encoding: 'utf8',
  });
}

/**
 * Fetch the full local process table via WMI/CIM (ProcessId, ParentProcessId, Name, CommandLine,
 * CreationFileTimeUtc).
 * Returns [] on any failure so `reportHolders` degrades to "no holder found" instead of throwing
 * mid-teardown. Deliberately unfiltered (not a `Where-Object -like` query): the ancestor walk in
 * `ancestorPids` needs every process's ParentProcessId to climb the chain, including ancestors
 * whose own command line does not name the worktree path (e.g. an intermediate console-host).
 *
 * Tempdoc 861 [A2]: the projection previously dropped `CreationDate`, so the process table this
 * scan already collects could NOT answer "is this still the same process, or a recycled pid?".
 * The projection now comes from ONE shared constant (`process-identity.cjs`), which adds the
 * creation time normalized via `.ToFileTimeUtc()`. This function's own `[]`-on-failure contract is
 * unchanged and correct for a best-effort holder report — but it is exactly why an identity check
 * must NOT read this function's result: `[]` here means "I could not look", and
 * `readProcessTable` in `process-identity.cjs` is the tri-state an identity check uses instead.
 */
function getProcessTable() {
  if (process.platform !== 'win32') return [];
  const psCmd = PROCESS_TABLE_PS_COMMAND;
  const res = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psCmd], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (res.status !== 0 || !res.stdout) return [];
  try {
    const parsed = JSON.parse(res.stdout);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch (err) {
    // `[]`-on-failure is unchanged by design (a best-effort holder report, not an identity check —
    // see the docstring above), but a silent `[]` left the next operator blind to WHY the scan came
    // up empty (861 production sweep, 2026-08-25). Log the diagnostic; do not change the return.
    console.error(`[remove-worktree] process-table query returned unparseable JSON: ${describeJsonParseFailure(res.stdout, err)}`);
    return [];
  }
}

/**
 * Walk `table`'s ParentProcessId chain upward from `pid`, returning every ancestor PID (NOT
 * including `pid` itself). A depth cap + cycle guard protects against a corrupt/partial table
 * (real WMI snapshots can have stale ParentProcessId rows pointing at a reused PID).
 */
function ancestorPids(table, pid, maxDepth = 64) {
  const byPid = new Map();
  for (const proc of table) {
    const id = Number(proc && proc.ProcessId);
    if (Number.isFinite(id)) byPid.set(id, proc);
  }
  const ancestors = new Set();
  let currentPid = Number(pid);
  for (let i = 0; i < maxDepth; i += 1) {
    const proc = byPid.get(currentPid);
    if (!proc) break;
    const parentPid = Number(proc.ParentProcessId);
    if (!Number.isFinite(parentPid) || parentPid === currentPid || ancestors.has(parentPid)) break;
    ancestors.add(parentPid);
    currentPid = parentPid;
  }
  return ancestors;
}

/**
 * Belt-and-braces: true when `entry`'s command line names both this script and the target path —
 * i.e. it looks like remove-worktree.cjs's own invocation, independent of whether the PID/ancestor
 * walk resolved it. Catches an intermediate wrapper the CIM parent chain didn't capture (e.g. a
 * PID the WMI snapshot missed a generation of).
 */
function looksLikeOwnInvocation(entry, targetBase) {
  const cmd = String((entry && entry.CommandLine) || '');
  return cmd.includes('remove-worktree.cjs') && cmd.includes(targetBase);
}

/**
 * Self-match fix (tempdoc 746 item 5): the previous holder-scan excluded only its OWN PowerShell
 * query process (`-ne $PID`) — it still named the invoking Node process and its shell/bash
 * ancestor as "holders", because BOTH have the worktree path in argv (that's how they invoked this
 * very script). An agent that ran the printed `taskkill` suggestion against that self-match killed
 * its own invoking process chain mid-run, leaving a half-deleted worktree — reproduced twice
 * (docs/observations.md, 2026-07-16).
 *
 * Excludes from the holder set: this script's own PID, every ancestor PID up the parent chain (the
 * shell/node chain that launched it), and any entry that looks like this script's own invocation by
 * command line (belt-and-braces for an ancestor the CIM walk didn't resolve).
 */
function filterHolders(table, targetPath, ownPid) {
  const base = path.basename(targetPath);
  const excludePids = new Set([Number(ownPid), ...ancestorPids(table, ownPid)]);
  return table.filter((entry) => {
    const cmd = String((entry && entry.CommandLine) || '');
    if (!cmd.includes(base)) return false;
    if (excludePids.has(Number(entry && entry.ProcessId))) return false;
    if (looksLikeOwnInvocation(entry, base)) return false;
    return true;
  });
}

function formatHolderLines(entry) {
  return [
    `PID ${entry.ProcessId}: ${entry.Name} — ${entry.CommandLine}`,
    `  kill: taskkill /F /PID ${entry.ProcessId}`,
  ];
}

/** Best-effort report of processes whose command line names the held path, to help the operator kill it. */
function reportHolders(p) {
  if (process.platform !== 'win32') return;
  const table = getProcessTable();
  const holders = filterHolders(table, p, process.pid);
  if (holders.length) {
    // Tempdoc 727 F-2: alongside the existing description, print a ready-to-run kill
    // command per holder — turns manual recovery into "copy this line if you're sure
    // it's safe" instead of hand-constructing the right taskkill invocation. Never
    // executed automatically: an unconditional auto-kill risks a legitimate process
    // (an open editor, another agent's session) that merely happens to name this path.
    // `filterHolders` (tempdoc 746 item 5) has already excluded this script's own PID,
    // its ancestor chain, and anything matching its own invocation signature, so this
    // list can no longer suggest killing the very process performing the teardown.
    console.error(`[remove-worktree] possible holder(s) of ${p}:`);
    for (const h of holders) {
      for (const line of formatHolderLines(h)) console.error(`[remove-worktree]   ${line}`);
    }
  } else {
    console.error(
      `[remove-worktree] no holder found by command line for ${p}; a process whose CWD is inside ` +
        `it (but doesn't name it on the command line) will not show up here.`,
    );
  }
}

// 2. Delete the remaining tree, long-path aware, with a bounded retry for held handles.
function deleteTree(p, { attempts = 5, retryDelayMs = 300 } = {}) {
  if (!fs.existsSync(p)) return true;
  let lastErr = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      fs.rmSync(p, { recursive: true, force: true, maxRetries: 3 });
      if (!fs.existsSync(p)) return true;
      lastErr = new Error('path still exists after rmSync');
    } catch (err) {
      lastErr = err;
    }

    const retryable = /EPERM|EBUSY|ENOTEMPTY/.test(String(lastErr && lastErr.code));
    if (attempt < attempts && (retryable || lastErr)) {
      sleepSync(retryDelayMs);
      if (!fs.existsSync(p)) return true;
    }
  }

  console.error(`[remove-worktree] node delete failed (${lastErr && lastErr.message}); trying \\\\?\\ long-path delete`);
  if (process.platform === 'win32') {
    const ps = longPathDelete(p);
    if (ps.status !== 0) {
      console.error(ps.stderr || ps.stdout || '(no output)');
      if (!fs.existsSync(p)) return true;
      reportHolders(p);
      return false;
    }
  } else {
    return false;
  }
  return !fs.existsSync(p);
}

/** Read a `--flag <value>` / `--flag=<value>` pair out of argv. */
function flagValue(argv, name) {
  const i = argv.indexOf(`--${name}`);
  if (i !== -1 && argv[i + 1] && !argv[i + 1].startsWith('--')) return argv[i + 1].trim();
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(`--${name}=`.length).trim() : null;
}

const QUERY_ENV = Object.freeze({ GIT_OPTIONAL_LOCKS: '0' });

function pathKey(p) {
  const resolved = path.resolve(p).replace(/\\/g, '/').replace(/\/+$/, '');
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

function samePath(a, b) {
  return pathKey(a) === pathKey(b);
}

function isWithin(candidate, root) {
  const child = pathKey(candidate);
  const parent = pathKey(root);
  return child === parent || child.startsWith(`${parent}/`);
}

function realpathNearestSync(p) {
  const abs = path.resolve(p);
  let head = abs;
  const tail = [];
  for (;;) {
    try {
      const real = fs.realpathSync.native(head);
      return tail.length ? path.join(real, ...tail) : real;
    } catch (err) {
      if (err?.code !== 'ENOENT' && err?.code !== 'ENOTDIR') throw err;
      const parent = path.dirname(head);
      if (parent === head) return abs;
      tail.unshift(path.basename(head));
      head = parent;
    }
  }
}

function gitQuery(repoRoot, args, { cwd = repoRoot } = {}) {
  return spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: { ...process.env, ...QUERY_ENV },
  });
}

function requireGit(repoRoot, args, label, options) {
  const result = gitQuery(repoRoot, args, options);
  if (result.status !== 0) {
    fail(`${label} failed: ${String(result.stderr || result.stdout || `git exited ${result.status}`).trim()}`);
  }
  return result.stdout || '';
}

/** Lossless parser for `git worktree list --porcelain -z`. */
function parseWorktreePorcelainZ(raw) {
  const entries = [];
  let entry = null;
  for (const field of String(raw || '').split('\0')) {
    if (field === '') {
      if (entry) entries.push(entry);
      entry = null;
      continue;
    }
    const split = field.indexOf(' ');
    const key = split === -1 ? field : field.slice(0, split);
    const value = split === -1 ? null : field.slice(split + 1);
    if (key === 'worktree') {
      if (entry) entries.push(entry);
      entry = {
        path: value,
        head: null,
        branchRef: null,
        detached: false,
        bare: false,
        locked: null,
        prunable: null,
      };
      continue;
    }
    if (!entry) fail(`git worktree list returned ${JSON.stringify(key)} before a worktree field`);
    if (key === 'HEAD') entry.head = value;
    else if (key === 'branch') entry.branchRef = value;
    else if (key === 'detached') entry.detached = true;
    else if (key === 'bare') entry.bare = true;
    else if (key === 'locked') entry.locked = value || '(no reason supplied)';
    else if (key === 'prunable') entry.prunable = value || '(no reason supplied)';
    else {
      if (!entry.extra) entry.extra = [];
      entry.extra.push({ key, value });
    }
  }
  if (entry) entries.push(entry);
  return entries;
}

function listRegisteredWorktrees(repoRoot) {
  const raw = requireGit(repoRoot, ['worktree', 'list', '--porcelain', '-z'], 'git worktree membership query');
  const entries = parseWorktreePorcelainZ(raw);
  if (entries.length === 0) fail('git worktree membership query returned no entries');
  return entries;
}

function repositoryFacts(repoRoot, entries) {
  const commonDir = path.resolve(
    repoRoot,
    requireGit(repoRoot, ['rev-parse', '--path-format=absolute', '--git-common-dir'], 'git common-directory query').trim(),
  );
  const isBare = requireGit(repoRoot, ['rev-parse', '--is-bare-repository'], 'git bare-repository query').trim() === 'true';
  const configuredWorktree = gitQuery(repoRoot, ['config', '--path', '--get', 'core.worktree']);
  if (configuredWorktree.status !== 0 && configuredWorktree.status !== 1) {
    fail(`core.worktree query failed: ${String(configuredWorktree.stderr || configuredWorktree.stdout || `git exited ${configuredWorktree.status}`).trim()}`);
  }
  if (!isBare && configuredWorktree.status === 0 && configuredWorktree.stdout.trim()) {
    fail(`refusing: owning repository declares core.worktree=${configuredWorktree.stdout.trim()}; separate Git-directory layout is unsupported`);
  }
  if (!isBare && path.basename(commonDir).toLowerCase() !== '.git') {
    fail(`refusing: owning repository uses unsupported separate Git directory ${commonDir}; main-worktree identity cannot be proven safely`);
  }
  const mainWorktree = isBare ? null : path.dirname(commonDir);
  if (!isBare && entries.filter((entry) => entry.path && samePath(entry.path, mainWorktree)).length !== 1) {
    fail(`refusing: ${commonDir} does not identify one exact registered main worktree; separate Git-directory layout is unsupported`);
  }
  if (!isBare) {
    const mainTop = requireGit(
      repoRoot,
      ['rev-parse', '--show-toplevel'],
      'main worktree-root proof',
      { cwd: mainWorktree },
    ).trim();
    const mainGitDir = requireGit(
      repoRoot,
      ['rev-parse', '--path-format=absolute', '--git-dir'],
      'main Git-directory proof',
      { cwd: mainWorktree },
    ).trim();
    if (!samePath(mainTop, mainWorktree) || !samePath(mainGitDir, commonDir)) {
      fail(`refusing: ${commonDir} does not prove the standard registered main-worktree layout; separate Git-directory layout is unsupported`);
    }
  }
  return { commonDir, isBare, mainWorktree };
}

function parseNulList(raw) {
  return String(raw || '').split('\0').filter(Boolean);
}

function inspectGitAdmission({ repoRoot, target, allowIgnored }) {
  const abs = path.resolve(target);
  const entries = listRegisteredWorktrees(repoRoot);
  const facts = repositoryFacts(repoRoot, entries);
  if (facts.isBare) fail('refusing: the owning repository is bare and has no removable linked worktree');

  const matches = entries.filter((candidate) => candidate.path && samePath(candidate.path, abs));
  if (matches.length !== 1) {
    fail(`refusing: ${abs} is not one exact registered worktree of ${facts.commonDir}`);
  }
  const entry = matches[0];
  if (samePath(abs, facts.mainWorktree)) fail(`refusing: ${abs} is the repository's main worktree`);
  if (samePath(abs, repoRoot)) fail(`refusing: ${abs} is the checkout that owns this running script`);
  if (isWithin(process.cwd(), abs)) fail(`refusing: the current process directory is at or inside ${abs}`);
  if (entry.bare) fail(`refusing: ${abs} is a bare worktree entry`);
  if (entry.locked !== null) fail(`refusing: ${abs} is locked (${entry.locked})`);

  const nested = entries.filter((candidate) => candidate.path && !samePath(candidate.path, abs) && isWithin(candidate.path, abs));
  if (nested.length) {
    fail(`refusing: ${abs} contains registered worktree(s): ${nested.map((candidate) => candidate.path).join(', ')}`);
  }

  if (!fs.existsSync(abs)) {
    if (entry.prunable === null) fail(`refusing: registered target ${abs} is missing but Git has not classified it as prunable`);
    return { abs, entry, entries, facts, exists: false, changes: [], ignored: [], blockers: [] };
  }
  const rootStat = fs.lstatSync(abs);
  if (rootStat.isSymbolicLink()) fail(`refusing: ${abs} is a symlink or junction alias`);
  if (!rootStat.isDirectory()) fail(`refusing: ${abs} is not a directory`);
  const real = fs.realpathSync.native(abs);
  if (!samePath(real, abs)) fail(`refusing: ${abs} resolves through an alias to ${real}`);

  const targetGitDir = requireGit(
    repoRoot,
    ['rev-parse', '--path-format=absolute', '--git-dir'],
    'target Git-directory query',
    { cwd: abs },
  ).trim();
  if (samePath(targetGitDir, facts.commonDir)) {
    fail(`refusing: ${abs} is the repository's main worktree`);
  }
  const targetTop = requireGit(repoRoot, ['rev-parse', '--show-toplevel'], 'target repository-root query', { cwd: abs }).trim();
  if (!samePath(targetTop, abs)) fail(`refusing: target Git root ${targetTop} does not equal registered path ${abs}`);
  const targetCommon = path.resolve(
    abs,
    requireGit(repoRoot, ['rev-parse', '--path-format=absolute', '--git-common-dir'], 'target common-directory query', { cwd: abs }).trim(),
  );
  if (!samePath(targetCommon, facts.commonDir)) {
    fail(`refusing: ${abs} belongs to a different repository (${targetCommon})`);
  }
  const targetHead = requireGit(repoRoot, ['rev-parse', '--verify', 'HEAD'], 'target HEAD query', { cwd: abs }).trim();
  if (!entry.head || targetHead !== entry.head) {
    fail(`refusing: target HEAD ${targetHead || '(missing)'} does not match registered HEAD ${entry.head || '(missing)'}`);
  }
  const targetBranchResult = gitQuery(repoRoot, ['symbolic-ref', '--quiet', 'HEAD'], { cwd: abs });
  if (targetBranchResult.status !== 0 && targetBranchResult.status !== 1) {
    fail(`target branch query failed: ${String(targetBranchResult.stderr || targetBranchResult.stdout || `git exited ${targetBranchResult.status}`).trim()}`);
  }
  const targetBranchRef = targetBranchResult.status === 0 ? targetBranchResult.stdout.trim() : null;
  if ((entry.detached && targetBranchRef !== null)
      || (!entry.detached && (!entry.branchRef || targetBranchRef !== entry.branchRef))) {
    fail(`refusing: target branch ${targetBranchRef || '(detached)'} does not match registered branch ${entry.branchRef || '(detached)'}`);
  }

  const changes = parseNulList(requireGit(
    repoRoot,
    ['status', '--porcelain=v1', '-z', '--untracked-files=all', '--ignored=no'],
    'target dirty-state query',
    { cwd: abs },
  ));
  const ignored = parseNulList(requireGit(
    repoRoot,
    ['ls-files', '--others', '--ignored', '--exclude-standard', '--directory', '-z'],
    'target ignored-file query',
    { cwd: abs },
  ));
  const blockers = [];
  if (changes.length) blockers.push(`tracked, staged, or untracked changes: ${changes.join(', ')}`);
  if (ignored.length && !allowIgnored) blockers.push('ignored paths are present; pass --allow-ignored to remove them');
  return { abs, entry, entries, facts, exists: true, changes, ignored, blockers };
}

function referenceRelation(raw, target) {
  if (typeof raw !== 'string' || !raw.trim() || !path.isAbsolute(raw.trim())) return null;
  try {
    const resolved = realpathNearestSync(raw.trim());
    return isWithin(resolved, target) || isWithin(target, resolved);
  } catch {
    return null;
  }
}

async function readOptionalJsonStrict(file, { maxBytes = 200_000 } = {}) {
  let stat;
  try {
    stat = await fs.promises.lstat(file);
  } catch (err) {
    if (err && (err.code === 'ENOENT' || err.code === 'ENOTDIR')) return { state: 'absent' };
    return { state: 'unknown', reason: `cannot inspect ${file}: ${String(err?.message || err)}` };
  }
  if (stat.isSymbolicLink()) return { state: 'unknown', reason: `${file} is a symlink` };
  if (!stat.isFile()) return { state: 'unknown', reason: `${file} is not a regular file` };
  if (stat.size > maxBytes) return { state: 'unknown', reason: `${file} exceeds ${maxBytes} bytes` };
  try {
    const value = JSON.parse(await fs.promises.readFile(file, 'utf8'));
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { state: 'unknown', reason: `${file} does not contain a JSON object` };
    }
    return { state: 'present', value };
  } catch (err) {
    return { state: 'unknown', reason: `cannot parse ${file}: ${String(err?.message || err)}` };
  }
}

async function inspectPendingAtomicWrite(file) {
  const pending = `${file}.tmp`;
  try {
    const stat = await fs.promises.lstat(pending);
    const shape = stat.isSymbolicLink() ? 'symlink' : stat.isFile() ? 'file' : 'non-file entry';
    return `pending atomic-write ${shape} ${pending} makes runtime state unknown`;
  } catch (err) {
    if (err?.code === 'ENOENT' || err?.code === 'ENOTDIR') return null;
    return `cannot inspect pending atomic write ${pending}: ${String(err?.message || err)}`;
  }
}

function runtimePathRelations(claims, target) {
  let related = false;
  const unknown = [];
  for (const claim of claims) {
    if (claim.value === null || claim.value === undefined || claim.value === '') {
      if (claim.required) unknown.push(`${claim.name} is missing`);
      continue;
    }
    const relation = referenceRelation(claim.value, target);
    if (relation === true) related = true;
    else if (relation === null) unknown.push(`${claim.name} is not a resolvable absolute path`);
  }
  return { related, unknown };
}

function livePidsFromTable(pids, tableResult) {
  if (!tableResult.ok) return { state: 'unknown', reason: tableResult.reason };
  const present = new Set(tableResult.table.map((row) => Number(row?.ProcessId)));
  return { state: pids.some((pid) => present.has(pid)) ? 'live' : 'stale' };
}

async function inspectRuntimeProvenance({ mainRepoRoot, target }) {
  const stateRoot = resolveDevRunnerStateRoot(mainRepoRoot, process.env);
  const blockers = [];
  const notes = [];
  let processTable = null;
  const getTable = () => {
    if (!processTable) processTable = readProcessTable();
    return processTable;
  };

  const activeFile = path.join(stateRoot, 'active.json');
  const activePending = await inspectPendingAtomicWrite(activeFile);
  if (activePending) blockers.push(`shared runtime state is unknown: ${activePending}`);
  const activeRead = await readOptionalJsonStrict(activeFile);
  if (activeRead.state === 'unknown') {
    blockers.push(`shared runtime state is unknown: ${activeRead.reason}`);
  } else if (activeRead.state === 'present') {
    const active = activeRead.value;
    const runId = typeof active.runId === 'string'
      && active.runId !== '.'
      && active.runId !== '..'
      && /^[A-Za-z0-9._-]+$/.test(active.runId)
      ? active.runId
      : null;
    if (active.kind !== 'backend-shared-lease.v1' || active.schemaVersion !== 1 || !runId) {
      blockers.push('shared runtime active.json has an unsupported or incomplete shape');
    } else {
      const runFile = path.join(stateRoot, 'runs', runId, 'run.json');
      const runPending = await inspectPendingAtomicWrite(runFile);
      if (runPending) blockers.push(`shared runtime run ${runId} is unknown: ${runPending}`);
      const runRead = await readOptionalJsonStrict(runFile);
      if (runRead.state !== 'present') {
        blockers.push(`shared runtime run ${runId} is ${runRead.state}: ${runRead.reason || runFile}`);
      } else {
        const run = runRead.value;
        if (run.schemaVersion !== 1 || run.runId !== runId) {
          blockers.push(`shared runtime run ${runId} has an unsupported shape or mismatched runId`);
        } else {
          const claims = [
            { name: 'active.provenance.repoRoot', value: active.provenance?.repoRoot, required: true },
            { name: 'active.provenance.distFromRoot', value: active.provenance?.distFromRoot, required: false },
            { name: 'run.repoRoot', value: run.repoRoot, required: true },
            { name: 'run.dataDir', value: run.dataDir, required: true },
            { name: 'run.spawn.backend.cwd', value: run.spawn?.backend?.cwd, required: false },
            { name: 'run.spawn.frontend.cwd', value: run.spawn?.frontend?.cwd, required: false },
            ...[
              'dataDir', 'justsearchHome', 'settingsStorePath', 'runtimeDir',
              'workerConfigSnapshotPath', 'runtimeManifestPath', 'expectedIndexBasePath',
              'confirmedIndexBasePath',
            ].map((name) => ({ name: `run.resourceClaims.${name}`, value: run.resourceClaims?.[name], required: false })),
          ];
          const relation = runtimePathRelations(claims, target);
          if (!relation.related && relation.unknown.length === 0) {
            notes.push(`shared runtime run ${runId} has proven unrelated provenance`);
          } else {
            const expectedPidFields = ['runnerPid', 'backendRootPid', 'frontendRootPid'];
            const pidsValid = run.pids && typeof run.pids === 'object' && !Array.isArray(run.pids)
              && expectedPidFields.every((name) => Number.isInteger(run.pids[name]) && run.pids[name] > 0);
            if (!pidsValid) {
              blockers.push(`shared runtime run ${runId} has incomplete process identity; all expected pid fields are required for stopped-state proof`);
            } else {
              const pids = expectedPidFields.map((name) => run.pids[name]);
              const liveness = livePidsFromTable(pids, getTable());
              const port = Number.isInteger(run.apiPortActual) && run.apiPortActual > 0 ? run.apiPortActual : null;
              const relationText = relation.related
                ? `references ${target}`
                : `has unknown owned-path relation to ${target} (${relation.unknown.join(', ')})`;
              if (liveness.state === 'unknown') {
                blockers.push(`shared runtime run ${runId} ${relationText}, but process liveness is unknown: ${liveness.reason}`);
              } else if (liveness.state === 'live') {
                blockers.push(`shared runtime run ${runId} is active and ${relationText}`);
              } else if (!port) {
                blockers.push(`shared runtime run ${runId} ${relationText} but declares no usable API port for stale-state proof`);
              } else {
                const probe = await probeRuntimePort(`http://127.0.0.1:${port}/api/status`);
                if (probe.state === 'REACHABLE') {
                  blockers.push(`shared runtime run ${runId} has a reachable listener and ${relationText}`);
                } else if (probe.state === 'REFUSED') {
                  notes.push(`shared runtime run ${runId} is proven stale (all recorded pids absent; connection refused)`);
                } else {
                  blockers.push(`shared runtime run ${runId} ${relationText}, but its API probe is ${probe.state}`);
                }
              }
            }
          }
        }
      }
    }
  } else {
    notes.push('shared runtime register is absent');
  }

  const foreignDir = resolveForeignRegisterDir(mainRepoRoot, process.env);
  let foreign;
  try {
    const foreignStat = await fs.promises.lstat(foreignDir).catch((err) => {
      if (err?.code === 'ENOENT') return null;
      throw err;
    });
    if (foreignStat?.isSymbolicLink()) throw new Error(`${foreignDir} is a symlink`);
    if (foreignStat && !foreignStat.isDirectory()) throw new Error(`${foreignDir} is not a directory`);
    if (foreignStat) {
      const pending = (await fs.promises.readdir(foreignDir, { withFileTypes: true }))
        .filter((entry) => entry.name.endsWith('.tmp'));
      for (const entry of pending) {
        blockers.push(`foreign runtime register has pending atomic-write entry ${entry.name}; state is unknown`);
      }
    }
    foreign = await readForeignRegister({ dir: foreignDir });
  } catch (err) {
    blockers.push(`foreign runtime register is unreadable: ${String(err?.message || err)}`);
    foreign = [];
  }
  for (const entry of foreign) {
    if (!entry.ok) {
      blockers.push(`foreign runtime record ${entry.recordId} is unreadable: ${entry.reason}`);
      continue;
    }
    const record = entry.record;
    const relation = runtimePathRelations([
      { name: 'repoRoot', value: record.repoRoot, required: true },
      { name: 'dataDir', value: record.dataDir, required: true },
    ], target);
    if (!relation.related && relation.unknown.length === 0) {
      notes.push(`foreign runtime ${entry.recordId} has proven unrelated provenance`);
      continue;
    }
    if (!Number.isInteger(record.pid) || record.pid <= 0) {
      blockers.push(`foreign runtime ${entry.recordId} may reference ${target}, but declares no usable pid`);
      continue;
    }
    const table = getTable();
    if (!table.ok) {
      blockers.push(`foreign runtime ${entry.recordId} may reference ${target}, but process liveness is unknown: ${table.reason}`);
      continue;
    }
    const pidPresent = table.table.some((row) => Number(row?.ProcessId) === record.pid);
    const probe = pidPresent ? null : await probeRuntimePort(`http://127.0.0.1:${record.ports.api}/api/status`);
    if (!pidPresent && probe.state === 'REFUSED') {
      notes.push(`foreign runtime ${entry.recordId} is proven stale (pid absent; connection refused)`);
      continue;
    }
    if (!pidPresent && probe.state !== 'REACHABLE') {
      blockers.push(`foreign runtime ${entry.recordId} may reference ${target}, but its API probe is ${probe.state}`);
      continue;
    }
    if (!relation.related) {
      blockers.push(`live or unreachable foreign runtime ${entry.recordId} has unknown owned-path relation to ${target}: ${relation.unknown.join(', ')}`);
    } else {
      blockers.push(`live or unreachable foreign runtime ${entry.recordId} owns a path under ${target}`);
    }
  }
  return { blockers, notes };
}

/**
 * Ask GitHub for the squash commit its merged PR produced. This is the only
 * cheap source of truth: ADR-0045 squash-merges every PR, so the branch's own
 * commits never appear in main and ancestry cannot answer this
 * (`squash-merge-verify-content-not-ancestry`).
 */
function mergeCommitFromPr(repoRoot, branch) {
  const r = spawnSync(
    'gh',
    ['pr', 'list', '--head', branch, '--state', 'merged', '--json', 'mergeCommit', '--limit', '1'],
    { cwd: repoRoot, encoding: 'utf8' },
  );
  if (r.status !== 0) return null;
  try {
    const rows = JSON.parse(r.stdout || '[]');
    return rows[0]?.mergeCommit?.oid || null;
  } catch {
    return null;
  }
}

function recordMergeLink({
  repoRoot,
  branch,
  mergeCommitArg,
  sessionIdArg,
  mergeCommitLookup = mergeCommitFromPr,
  spawnProcess = spawnSync,
}) {
  const sessionId = String(sessionIdArg || '').trim();
  if (!sessionId || sessionId === 'unknown') {
    console.error(
      '[remove-worktree] record-merge SKIPPED: ' +
        (sessionId === 'unknown'
          ? '--session-id unknown requests unattributed teardown.'
          : 'merge attribution requires an explicit known --session-id.'),
    );
    return;
  }

  const commit = mergeCommitArg || (branch ? mergeCommitLookup(repoRoot, branch) : null);

  if (!commit) {
    // Deliberately NOT falling back to repoRoot HEAD — that is the bug.
    console.error(
      '[remove-worktree] record-merge SKIPPED: could not establish this branch\'s merge commit' +
        (branch ? ` (branch ${branch}: no merged PR found via gh)` : ' (worktree has no branch)') +
        '.\n[remove-worktree]   The session -> merge link is a FACT-tier row; guessing it would' +
        ' write a wrong one that cannot be retracted.\n[remove-worktree]   Backfill once you know' +
        ' the commit:\n[remove-worktree]     node scripts/agent-analytics/record-merge.mjs <merge-commit>' +
        ' --session-id <id>\n[remove-worktree]   Or re-run with --merge-commit <sha>.',
    );
    return;
  }

  const args = [
    path.join(repoRoot, 'scripts', 'agent-analytics', 'record-merge.mjs'),
    commit,
    '--session-id',
    sessionId,
  ];
  try {
    const rec = spawnProcess('node', args, { cwd: repoRoot, encoding: 'utf8' });
    const out = (rec.stdout || rec.stderr || '').trim();
    if (out) console.error(`[remove-worktree] ${out}`);
  } catch (err) {
    console.error(`[remove-worktree] WARN record-merge: ${err.message}`);
  }
}

function parseArgs(argv) {
  const target = argv[2];
  if (!target || target.startsWith('--')) {
    fail(
      'usage: node scripts/dev/remove-worktree.cjs <worktree-path> [--dry-run] [--allow-ignored --archive-manifest <manifest.json>] [--delete-branch]' +
        ' [--merge-commit <sha>] [--session-id <id>]' +
        ' (merge attribution requires an explicit known --session-id; unknown skips it.' +
        ' Helper caller identity still falls back to CLAUDE_CODE_SESSION_ID /' +
        ' JUSTSEARCH_AGENT_SESSION_ID / tmp/agent-telemetry/current-session-id)',
    );
  }
  const knownBooleans = new Set(['--dry-run', '--allow-ignored', '--delete-branch']);
  const knownValued = new Set(['--merge-commit', '--session-id', '--archive-manifest']);
  for (let i = 3; i < argv.length; i += 1) {
    const arg = argv[i];
    if (knownBooleans.has(arg)) continue;
    if (knownValued.has(arg)) {
      if (!argv[i + 1] || argv[i + 1].startsWith('--')) fail(`${arg} requires a value`);
      i += 1;
      continue;
    }
    if ([...knownValued].some((name) => arg.startsWith(`${name}=`))) continue;
    fail(`unknown argument ${JSON.stringify(arg)}`);
  }
  return {
    target,
    dryRun: argv.includes('--dry-run'),
    allowIgnored: argv.includes('--allow-ignored'),
    deleteBranch: argv.includes('--delete-branch'),
    mergeCommitArg: flagValue(argv, 'merge-commit'),
    sessionIdArg: flagValue(argv, 'session-id'),
    // Tempdoc 952: ignored files are never discarded by inference. `--allow-ignored` now means
    // "they are archived": the manifest written by worktree-lifecycle.cjs release must verify.
    archiveManifestArg: flagValue(argv, 'archive-manifest'),
  };
}

function printAdmission(admission, { dryRun, allowIgnored }) {
  const branch = admission.entry.detached || !admission.entry.branchRef
    ? `detached HEAD at ${admission.entry.head}`
    : `${admission.entry.branchRef.replace(/^refs\/heads\//, '')} at ${admission.entry.head}`;
  console.error(`[remove-worktree] ${dryRun ? 'preview' : 'target'}: ${admission.abs}`);
  console.error(`[remove-worktree] revision: ${branch}`);
  if (admission.ignored.length) {
    console.error(`[remove-worktree] ignored paths (${allowIgnored ? 'explicitly allowed' : 'BLOCKER'}):`);
    for (const item of admission.ignored) console.error(`[remove-worktree]   ${item}`);
  } else {
    console.error('[remove-worktree] ignored paths: none');
  }
  for (const blocker of admission.blockers) console.error(`[remove-worktree] BLOCKER: ${blocker}`);
}

/**
 * Tempdoc 952: `--allow-ignored` is admissible only with a manifest from worktree-archive.cjs
 * that names this target and still verifies against the live tree. Returns a blocker string or
 * null. Verification failure is a blocker, not a warning: the tree moved after archiving.
 */
function inspectArchiveManifest({ mainRepoRoot, manifestArg, target, needed }) {
  if (!manifestArg) {
    return {
      blocker: `${needed}; it leaves only through a verified archive — run `
        + '`node scripts/dev/worktree-lifecycle.cjs release <path>` or pass --archive-manifest <manifest.json>',
      covered: null,
    };
  }
  const manifestPath = path.resolve(manifestArg);
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (err) {
    return { blocker: `archive manifest unreadable: ${manifestPath} (${String(err && err.message ? err.message : err)})`, covered: null };
  }
  if (!manifest || typeof manifest !== 'object' || typeof manifest.worktreePath !== 'string') {
    return { blocker: `archive manifest ${manifestPath} has no worktreePath`, covered: null };
  }
  if (!samePath(manifest.worktreePath, target)) {
    return { blocker: `archive manifest ${manifestPath} is for ${manifest.worktreePath}, not ${target}`, covered: null };
  }
  // Loaded lazily: the archive library requires the governance policy file, which a dry-run on a
  // repository without it must not need.
  const { verifyArchive } = require('./lib/worktree-archive.cjs');
  const verify = verifyArchive({ mainRepoRoot, manifest });
  if (!verify.ok) {
    const detail = [
      verify.missing.length ? `missing objects: ${verify.missing.slice(0, 3).join(', ')}` : null,
      verify.mismatched.length ? `changed since archive: ${verify.mismatched.slice(0, 3).join(', ')}` : null,
      (verify.errors || []).length ? `errors: ${verify.errors.slice(0, 2).map((e) => (typeof e === 'string' ? e : e.reason || JSON.stringify(e))).join('; ')}` : null,
    ].filter(Boolean).join('; ');
    return { blocker: `archive manifest does not verify (${detail}); re-run release so the archive matches the tree`, covered: null };
  }
  const covered = new Set((manifest.files || []).map((f) => String(f.path || '').replace(/\\/g, '/')));
  return { blocker: null, covered };
}

/** The path of one `git status --porcelain=v1 -z` entry (`XY path`; a rename's old path is bare). */
function statusEntryPath(entry) {
  return (/^.. /.test(entry) ? entry.slice(3) : entry).replace(/\\/g, '/');
}

const CHANGES_BLOCKER_PREFIX = 'tracked, staged, or untracked changes: ';

/**
 * Tempdoc 952 §5.5: with a verified archive, tracked/untracked changes are no longer a blocker
 * PROVIDED every changed path is in the archive (its blob hash was just re-verified against the
 * tree). Anything not covered stays a blocker. Ignored paths are covered by the same manifest.
 */
function applyArchiveCoverage(admission, gate) {
  if (gate.blocker) {
    admission.blockers.push(gate.blocker);
    return;
  }
  if (!admission.changes.length) return;
  const uncovered = admission.changes.map(statusEntryPath).filter((p) => !gate.covered.has(p));
  if (uncovered.length) {
    admission.blockers.push(`changes not covered by the archive manifest: ${uncovered.join(', ')}`);
    return;
  }
  admission.blockers = admission.blockers.filter((b) => !b.startsWith(CHANGES_BLOCKER_PREFIX));
  admission.archiveCoveredChanges = admission.changes.length;
}

function assertStableAdmission(before, after) {
  if (!samePath(before.abs, after.abs)
      || before.entry.head !== after.entry.head
      || before.entry.branchRef !== after.entry.branchRef
      || before.entry.detached !== after.entry.detached) {
    fail('refusing: worktree membership, branch, or HEAD changed during safety checks');
  }
}

function removeExactRegistration(repoRoot, abs) {
  const result = spawnSync('git', ['worktree', 'remove', '--force', '--', abs], {
    cwd: repoRoot,
    encoding: 'utf8',
    env: { ...process.env, ...QUERY_ENV },
  });
  if (result.status !== 0) {
    fail(`target directory was deleted, but its Git registration could not be removed: ${String(result.stderr || result.stdout).trim()}`);
  }
  console.error(`[remove-worktree] removed Git registration for ${abs}`);
}

function deleteCapturedBranch(repoRoot, captured) {
  if (captured.detached || !captured.branchRef) {
    console.error('[remove-worktree] detached HEAD: no branch to delete.');
    return;
  }
  if (!captured.branchRef.startsWith('refs/heads/')) fail(`refusing to delete non-local ref ${captured.branchRef}`);
  const current = gitQuery(repoRoot, ['rev-parse', '--verify', `${captured.branchRef}^{commit}`]);
  if (current.status !== 0 || current.stdout.trim() !== captured.head) {
    fail(`refusing to delete ${captured.branchRef}: it moved from captured HEAD ${captured.head}`);
  }
  const users = listRegisteredWorktrees(repoRoot).filter((entry) => entry.branchRef === captured.branchRef);
  if (users.length) fail(`refusing to delete ${captured.branchRef}: it is checked out at ${users.map((entry) => entry.path).join(', ')}`);
  const short = captured.branchRef.slice('refs/heads/'.length);
  const result = spawnSync('git', ['branch', '-D', '--', short], { cwd: repoRoot, encoding: 'utf8' });
  if (result.status !== 0) fail(`failed to delete captured branch ${short}: ${String(result.stderr || result.stdout).trim()}`);
  console.error(`[remove-worktree] deleted branch ${short}`);
}

async function main({ argv = process.argv, repoRoot = path.resolve(__dirname, '..', '..') } = {}) {
  const options = parseArgs(argv);
  const mainRepoRoot = resolveMainRepoRoot(repoRoot);
  const admission = inspectGitAdmission({ repoRoot, target: options.target, allowIgnored: options.allowIgnored });
  // Tempdoc 952 Amendment A: ignored files leave only through a verified archive. A bare
  // `--allow-ignored` used to delete them with the tree; it now needs the manifest that
  // worktree-lifecycle.cjs release wrote, and that manifest must still match the tree.
  const archiveGate = (adm) => {
    const wantsIgnored = adm.ignored.length && options.allowIgnored;
    if (!wantsIgnored && !options.archiveManifestArg) return; // classic path: dirty state blocks
    const needed = wantsIgnored ? 'ignored paths are present' : 'an archive manifest was given';
    applyArchiveCoverage(adm, inspectArchiveManifest({ mainRepoRoot, manifestArg: options.archiveManifestArg, target: adm.abs, needed }));
  };
  archiveGate(admission);
  printAdmission(admission, options);

  const runtime = await inspectRuntimeProvenance({ mainRepoRoot, target: admission.abs });
  for (const note of runtime.notes) console.error(`[remove-worktree] runtime: ${note}`);
  for (const blocker of runtime.blockers) console.error(`[remove-worktree] BLOCKER: ${blocker}`);

  const callerSessionId = resolveCallerSessionId({ explicit: options.sessionIdArg, env: process.env, repoRoot });
  let helperInspection;
  try {
    helperInspection = await inspectAgentSpawnsForTeardown({
      mainRepoRoot,
      targetPath: admission.abs,
      callerSessionId,
    });
  } catch (err) {
    fail(`agent-spawns safety inspection failed: ${String(err?.message || err)}`);
  }
  for (const entry of helperInspection.buckets.all) {
    console.error(`[remove-worktree] helper: ${describeEntry(entry).replace(/\n/g, '\n[remove-worktree]   ')}`);
  }

  const previewBlockers = [
    ...admission.blockers,
    ...runtime.blockers,
    ...(helperInspection.buckets.blocksProceed ? ['a registered helper cannot be safely cleared'] : []),
  ];
  if (options.dryRun) {
    if (previewBlockers.length) fail(`dry-run found ${previewBlockers.length} blocker(s); no changes were made`);
    console.error('[remove-worktree] dry-run: removal is admissible; no changes were made.');
    return;
  }
  if (admission.blockers.length || runtime.blockers.length) {
    fail(`refusing to remove ${admission.abs}: ${admission.blockers.length + runtime.blockers.length} safety blocker(s)`);
  }

  // Re-probe runtime provenance before the final helper consult. The helper consult is the last
  // asynchronous action before Git/filesystem admission is synchronously revalidated.
  const finalRuntime = await inspectRuntimeProvenance({ mainRepoRoot, target: admission.abs });
  if (finalRuntime.blockers.length) fail(`refusing after runtime revalidation: ${finalRuntime.blockers.join('; ')}`);

  // Keep the established effectful helper-reaper path for actual teardown. It re-verifies process
  // identity immediately before any authorized kill and blocks on every unreadable holder row.
  let consult;
  try {
    consult = await consultAgentSpawnsForTeardown({ mainRepoRoot, targetPath: admission.abs, callerSessionId });
  } catch (err) {
    fail(`agent-spawns register consult failed: ${String(err?.message || err)}`);
  }
  if (consult.buckets.all.length > 0) {
    console.error(`[remove-worktree] agent-spawns register: ${consult.buckets.all.length} relevant record(s):`);
    for (const entry of consult.buckets.all) {
      console.error(`[remove-worktree]   ${describeEntry(entry).replace(/\n/g, '\n[remove-worktree]   ')}`);
    }
  }
  if (consult.buckets.blocksProceed) {
    fail(`refusing to remove ${admission.abs}: a registered agent-spawn holder could not be reaped`);
  }

  // No asynchronous work occurs between this final membership check and the first mutation.
  const finalAdmission = inspectGitAdmission({
    repoRoot,
    target: admission.abs,
    allowIgnored: options.allowIgnored,
  });
  assertStableAdmission(admission, finalAdmission);
  archiveGate(finalAdmission); // re-verifies the archive against the tree as it is NOW
  if (finalAdmission.blockers.length) fail(`refusing after final admission: ${finalAdmission.blockers.join('; ')}`);

  const capturedBranch = admission.entry.branchRef?.replace(/^refs\/heads\//, '') || null;
  if (fs.existsSync(admission.abs)) {
    removeJunctions(admission.abs);
    if (!deleteTree(admission.abs)) fail(`failed to delete ${admission.abs}`);
    console.error(`[remove-worktree] deleted ${admission.abs}`);
  } else {
    console.error(`[remove-worktree] ${admission.abs} already gone; removing only its registration.`);
  }

  removeExactRegistration(repoRoot, admission.abs);
  if (options.deleteBranch) deleteCapturedBranch(repoRoot, admission.entry);

  // Record the identity-attributed merge link only after removal and only when the caller opted in
  // with a known `--session-id`. Missing/unknown identity returns before the GitHub query or writer.
  // `--merge-commit` wins; otherwise a merged PR lookup may establish the squash commit.
  recordMergeLink({
    repoRoot,
    branch: capturedBranch,
    mergeCommitArg: options.mergeCommitArg,
    sessionIdArg: options.sessionIdArg,
  });

  console.error('[remove-worktree] done.');
}

if (require.main === module) {
  main().catch((err) => {
    console.error(`[remove-worktree] ERROR: ${err && err.message ? err.message : err}`);
    process.exit(1);
  });
}

module.exports = {
  deleteTree,
  longPathDelete,
  sleepSync,
  reportHolders,
  removeJunctions,
  main,
  getProcessTable,
  ancestorPids,
  looksLikeOwnInvocation,
  filterHolders,
  recordMergeLink,
};
