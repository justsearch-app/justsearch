#!/usr/bin/env node
/**
 * Tempdoc 861 W5 — the shared plumbing behind all six §6.4 reap occasions.
 *
 * `agent-spawn-reaper.cjs` is a pure projection (`reapEligible`) plus one effectful arm
 * (`executeReap`); it deliberately knows nothing about directories, session ids, or the
 * dev-runner's state layout. Six call sites (a SessionStart hook, a SessionEnd hook, a
 * PreToolUse/Bash hint, `remove-worktree.cjs`, `world-state.mjs`, and the session-closeout
 * skill's CLI) all need the SAME assembly: resolve the register + state root, read the
 * register, read the process table, resolve the dev-runner's own active run, call
 * `reapEligible`, and — for the three EXECUTE occasions — run `executeReap` on the `reap`
 * bucket and discharge the `markRefusals` obligation ([F7]).
 *
 * Writing that assembly six times would be the exact "creating a new utility function when
 * an identical one exists two packages over" failure mode this repo's agent rules name
 * first. This module is the one assembly, parameterized by occasion.
 *
 * Nothing in this module invents a new capability the reaper does not already gate: every
 * function here is a thin composition of `reapEligible` / `executeReap` / `markRefusals`
 * plus the register/process-table plumbing. `capability` is never a caller argument (861
 * [A4] / review F2) — callers pass an occasion NAME, exactly as the reaper requires.
 */
'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');

const {
  resolveAgentSpawnsRegisterDir,
  readAgentSpawnRegister,
  recordHoldsPath,
  realpathNearest,
  normalizePathForCompare,
  pruneAgentSpawnRecords,
  DEFAULT_MAX_RECORD_AGE_MS,
  hasTrustedOwner,
} = require('./agent-spawn-record.cjs');
const { readProcessTable } = require('./process-identity.cjs');
const {
  reapEligible,
  executeReap,
  markRefusals,
  readDevRunnerActiveRun,
} = require('./agent-spawn-reaper.cjs');
const { DEFAULT_THRESHOLDS } = require('./ownership-verdict.cjs');
const { resolveAgentIdentity } = require('./agent-identity.cjs');

/**
 * Resolve the main repo root, even from inside a git worktree. Duplicated (deliberately, not
 * extracted) from the same small helper already independently present in `dev-runner.cjs`,
 * `justsearch-dev-mcp/paths.mjs`, and `agent-analytics/lib/hook-base.mjs` — a fourth copy of
 * an 8-line, already-established repo convention is lower risk than refactoring three
 * unrelated call sites this tempdoc's phase does not otherwise touch.
 */
function resolveMainRepoRoot(fromDir) {
  try {
    const gitPath = path.join(fromDir, '.git');
    const stat = fs.statSync(gitPath);
    if (stat.isFile()) {
      const content = fs.readFileSync(gitPath, 'utf8').trim();
      const m = content.match(/^gitdir:\s*(.+)$/);
      if (m) {
        const gitDir = path.resolve(fromDir, m[1]);
        return path.resolve(gitDir, '..', '..', '..');
      }
    }
  } catch { /* not a worktree or no .git — fall through */ }
  return fromDir;
}

/**
 * Resolve the calling agent session (861 W5 review F-2a/F-3) through the one identity rule
 * (`agent-identity.cjs`): an explicit override wins, then the caller's nearest harness process
 * (its pid + creation time is the owner key; its label is that harness's own session variable),
 * then ci/unknown. Never the retired shared pointer file, never `JUSTSEARCH_AGENT_SESSION_ID`.
 *
 * Without it, a caller with no explicit `--session-id` (the documented invocation) could never
 * be recognised as the owner of its own spawns, and the SAME-SESSION reap rule could never fire
 * for that caller's own live spawn - it would fall through to CONTENTION instead (F-2a).
 */
function resolveCallerIdentity({ explicit = null, env = process.env } = {}) {
  return resolveAgentIdentity({ explicit, env });
}

/**
 * The dev-runner's own state root (`<mainRepoRoot>/tmp/dev-runner`, or the
 * `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` override — 861 [A9]). Derived from
 * `resolveAgentSpawnsRegisterDir` rather than re-deriving the override rule a third time:
 * `agent-spawns` is a single path segment under the state root, so its parent IS the root.
 */
function resolveDevRunnerStateRoot(mainRepoRoot, env = process.env) {
  return path.dirname(resolveAgentSpawnsRegisterDir(mainRepoRoot, env));
}

/**
 * Known agent-spawn producer command-line fingerprints, for the orientation occasion's
 * "observed" tier (861 §6.3's observed row: a process matching no record, reported with a
 * ready-to-run kill line, never auto-killed). Deliberately a SHORT fixed list, mirroring
 * `FOREIGN_BACKEND_PORTS`'s own "a scan, not a registry" rationale (844 §12.4): both current
 * `agent-spawns` producers are Vite-based (ui-shot, `serve-worktree-fe`), and the sink's own
 * script basename is the third. A portless, non-Vite producer is not exercised by this list
 * (861 §6.5 names the same honest limit for the probe-kind story) — extending it is additive
 * when one arrives, not built ahead of it.
 */
const KNOWN_AGENT_SPAWN_FINGERPRINTS = Object.freeze(['vite', 'otlp-sink.py']);

/**
 * Rows of `table` that look like a known agent-spawn producer but whose pid is not already
 * covered by a registered record. Pure; used only by the orientation gatherer.
 */
function deriveObservedRows(table, registeredPids) {
  if (!Array.isArray(table)) return [];
  return table.filter((row) => {
    const pid = Number(row?.ProcessId);
    if (!Number.isInteger(pid) || registeredPids.has(pid)) return false;
    const cmd = String(row?.CommandLine || '');
    return KNOWN_AGENT_SPAWN_FINGERPRINTS.some((fp) => cmd.includes(fp));
  });
}

/**
 * The reverse containment direction from `recordHoldsPath` (861 W5 review F-4).
 *
 * `recordHoldsPath(record, p)` answers "does `p` sit AT OR BENEATH one of this record's held
 * roots" — correct for "does this record hold the worktree being torn down", where the queried
 * path and the held root are the SAME tree. It answers nothing for §6.2's headline case: a
 * WORKTREE's Vite holds the MAIN checkout's `node_modules` (a root NARROWER than, and nested
 * INSIDE, the tree a build/teardown names) — both `findBuildHolders` and
 * `consultAgentSpawnsForTeardown` query with a TREE ROOT (`repoRoot` / the worktree path), never
 * the specific deep file a build would actually touch, so the record's `nodeModulesRealPath`
 * (`<mainRoot>/node_modules`) never equals-or-is-beneath the wider `treeRoot` being asked about —
 * `recordHoldsPath` returns `false` for exactly the case the field was recorded for.
 *
 * This answers the other direction: does `treeRoot` CONTAIN one of the record's held roots.
 */
async function holdsWithin(record, treeRoot) {
  const target = normalizePathForCompare(await realpathNearest(treeRoot));
  if (!target) return false;
  const roots = record?.resourceRoots || {};
  for (const key of ['worktreeRoot', 'nodeModulesRealPath']) {
    const raw = roots[key];
    if (typeof raw !== 'string' || !raw.trim()) continue;
    // eslint-disable-next-line no-await-in-loop -- two roots at most per record
    const root = normalizePathForCompare(await realpathNearest(raw));
    if (!root) continue;
    if (root === target || root.startsWith(`${target}/`)) return true;
  }
  return false;
}

/**
 * Both directions of root containment (861 W5 review F-4) — the predicate `findBuildHolders`
 * and `consultAgentSpawnsForTeardown`'s pre-filter actually need: does the record hold (at/under)
 * the queried tree, OR does the queried tree contain one of the record's held roots.
 */
async function recordHoldsTree(record, treeRoot) {
  if (await recordHoldsPath(record, treeRoot)) return true;
  return holdsWithin(record, treeRoot);
}

/** Teardown-only path resolver: tolerate absence while propagating I/O/permission failures. */
async function strictRealpathNearest(p, { realpath = fsp.realpath } = {}) {
  if (typeof p !== 'string' || !p.trim() || !path.isAbsolute(p)) {
    throw new Error(`held resource path is not absolute: ${JSON.stringify(p)}`);
  }
  const abs = path.resolve(p);
  let head = abs;
  const tail = [];
  for (;;) {
    try {
      const real = await realpath(head);
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

/** Strict, tri-state-by-throw relation used only at the destructive teardown boundary. */
async function strictRecordHoldsTree(record, treeRoot, options) {
  const target = normalizePathForCompare(await strictRealpathNearest(treeRoot, options));
  const roots = record?.resourceRoots || {};
  for (const key of ['worktreeRoot', 'nodeModulesRealPath']) {
    const raw = roots[key];
    if (raw === undefined || raw === null) continue;
    // eslint-disable-next-line no-await-in-loop -- two roots at most per record
    const root = normalizePathForCompare(await strictRealpathNearest(raw, options));
    if (target === root || target.startsWith(`${root}/`) || root.startsWith(`${target}/`)) return true;
  }
  return false;
}

async function readTeardownEntries({ dir, targetPath }) {
  await assertOptionalRegisterDirectory(dir);
  let dirEntries;
  try {
    dirEntries = await fsp.readdir(dir, { withFileTypes: true });
  } catch (err) {
    if (err?.code === 'ENOENT') return [];
    throw err;
  }
  const pending = dirEntries
    .filter((entry) => entry.name.endsWith('.tmp'))
    .map((entry) => ({
      ok: false,
      recordId: `(pending:${entry.name})`,
      reason: `pending atomic-write entry ${entry.name} makes helper registration state unknown; wait for the producer, or if it is stale run node scripts/dev/agent-spawn-sweep.cjs --occasion session-start`,
    }));
  const rawEntries = await readAgentSpawnRegister({ dir });
  const relevant = [...pending];
  for (const entry of rawEntries) {
    if (!entry.ok) {
      relevant.push(entry);
      continue;
    }
    try {
      // eslint-disable-next-line no-await-in-loop -- bounded by AGENT_SPAWNS_MAX_RECORDS (64)
      if (await strictRecordHoldsTree(entry.record, targetPath)) relevant.push(entry);
    } catch (err) {
      relevant.push({
        ok: false,
        recordId: entry.recordId,
        reason: `could not establish held-resource relation: ${String(err?.message || err).slice(0, 200)}`,
      });
    }
  }
  return relevant;
}

/**
 * The EXECUTE-capability assembly shared by the three occasions that may actually kill:
 * `session-start`, `session-end`, and `session-closeout` (861 §6.4 / OCCASIONS map).
 *
 * `ownSessionOnly` scopes the register read to records this session owns BEFORE evaluation —
 * used by the SessionEnd occasion, whose tempdoc description is "best-effort reap of this
 * session's OWN spawns" (861 §6.4), narrower than the full abandonment sweep session-start and
 * session-closeout perform. Records with no attributable session (unreadable, or a foreign
 * sessionId) are excluded from an own-session-only run rather than folded in — the broader
 * sweep at session-start is what covers those.
 *
 * Never mints a kill outside `executeReap`'s own re-verification: this function does not
 * shortcut anything the reaper already gates, it only wires the plumbing around it.
 *
 * `filterEntry` is the general pre-filter hook (async-capable): `worktree-teardown` uses it to
 * scope the register read to records that `recordHoldsTree` says hold the worktree being torn
 * down, exactly the pre-filter `findBuildHolders` applies for `before-a-build`. `ownSessionOnly`
 * is sugar for the SessionEnd occasion's own narrower filter and is applied in ADDITION to a
 * supplied `filterEntry`, never instead of it.
 *
 * **[F-1] Pruning is wired here, not merely documented.** `pruneAgentSpawnRecords` had ZERO
 * production callers before this fix — the "record retained until the 7-day prune" bound the
 * reaper's own comments lean on (`agent-spawn-reaper.cjs`'s F3 `blocksProceed` carve-out
 * explicitly) did not exist, so a single transient `IDENTITY_REFUSE` (one bad process-table read)
 * marked a record failed-verify and blocked `remove-worktree` on that tree FOREVER — nothing ever
 * aged it out. `prune` defaults to true exactly on `session-start` (the occasion whose whole job
 * is a periodic, unattended sweep) and is force-enabled by the CLI regardless of occasion, since
 * "run this and it cleans up" is that command's entire purpose. Runs BEFORE the register read so
 * a just-pruned record never even reaches `reapEligible`.
 *
 * @returns {Promise<{occasion: string, dir: string, buckets: object, kills: Array, marked: object, pruned: object|null}>}
 */
async function runAgentSpawnSweep({
  occasion,
  mainRepoRoot,
  callerIdentity = null,
  ownSessionOnly = false,
  filterEntry = null,
  now = Date.now(),
  env = process.env,
  readTable = readProcessTable,
  executeReadTable = readProcessTable,
  actorSource = null,
  thresholds = DEFAULT_THRESHOLDS,
  prune = occasion === 'session-start',
  pruneMaxAgeMs = DEFAULT_MAX_RECORD_AGE_MS,
} = {}) {
  const stateRoot = resolveDevRunnerStateRoot(mainRepoRoot, env);
  const dir = resolveAgentSpawnsRegisterDir(mainRepoRoot, env);

  const pruned = prune ? await pruneAgentSpawnRecords({ dir, maxAgeMs: pruneMaxAgeMs, now }) : null;

  let rawEntries = await readAgentSpawnRegister({ dir });
  if (ownSessionOnly) {
    // "Own" is the owner key: a record without an owner block is nobody's own.
    const ownKey = callerIdentity?.owner?.key ?? null;
    rawEntries = rawEntries.filter((e) => e.ok && ownKey && hasTrustedOwner(e.record) && e.record.owner.key === ownKey);
  }
  const entries = [];
  for (const e of rawEntries) {
    // eslint-disable-next-line no-await-in-loop -- bounded by AGENT_SPAWNS_MAX_RECORDS (64)
    if (!filterEntry || (await filterEntry(e))) entries.push(e);
  }

  // Fast path: a pre-filtered caller (worktree-teardown by path) with nothing matching has no
  // process table to read and nothing for the reaper to evaluate — skip the PowerShell spawn.
  if (filterEntry && entries.length === 0) {
    return {
      occasion, dir, kills: [], marked: { marked: [], failed: [], skipped: 0 }, pruned,
      buckets: { reap: [], contention: [], refuse: [], report: [], all: [], blocksProceed: false, markPending: [] },
    };
  }

  const processTable = readTable();
  const devRunnerActive = await readDevRunnerActiveRun({ stateRoot });

  const buckets = reapEligible({
    records: entries,
    processTable,
    occasion,
    callerOwnerKey: callerIdentity?.owner?.key ?? null,
    now,
    thresholds,
    devRunnerActive,
    env,
  });

  const kills = [];
  for (const entry of buckets.reap) {
    kills.push(await executeReap(entry, {
      dir,
      readTable: executeReadTable,
      actor: { sessionId: callerIdentity?.sessionId ?? null, ownerKey: callerIdentity?.owner?.key ?? null, source: actorSource || occasion },
    }));
  }
  const marked = await markRefusals(buckets.markPending, { dir, now });

  return { occasion, dir, buckets, kills, marked, pruned };
}

/**
 * The EXECUTE assembly for `worktree-teardown` (861 §6.4 / §6.3's Conflict column at its
 * ceiling): `remove-worktree.cjs` "consults the register before unlinking junctions, reaps
 * what it is authorized to, and refuses to proceed while an unreapable holder remains" (861
 * §6.4). Pre-filters the register with `recordHoldsTree` against `targetPath` — the worktree
 * about to be torn down — exactly as `findBuildHolders` does for `before-a-build`; a record
 * that does not hold anything under `targetPath` is not this teardown's concern. [F-4]: checks
 * BOTH containment directions, so a worktree's Vite holding the MAIN checkout's `node_modules`
 * is caught when `targetPath` is the MAIN tree, not only when it is the worktree's own tree.
 *
 * The caller reads `result.buckets.blocksProceed` to decide whether to proceed: `makeEntry` in
 * the reaper (861 review F3/[A5]) already restricts `blocksProceed` to a REGISTERED holder this
 * occasion could not clear (contention, an identity refusal, or a never-reap policy row) —
 * never the observed tier, which stays this caller's own judgement per the existing
 * `filterHolders`/`reportHolders` fallback.
 */
async function consultAgentSpawnsForTeardown({
  mainRepoRoot,
  targetPath,
  callerIdentity = null,
  now = Date.now(),
  env = process.env,
  readTable = readProcessTable,
  executeReadTable = readProcessTable,
  thresholds = DEFAULT_THRESHOLDS,
} = {}) {
  const registerDir = resolveAgentSpawnsRegisterDir(mainRepoRoot, env);
  const entries = await readTeardownEntries({ dir: registerDir, targetPath });
  const result = await runAgentSpawnSweep({
    occasion: 'worktree-teardown',
    mainRepoRoot,
    callerIdentity,
    now,
    env,
    readTable,
    executeReadTable,
    thresholds,
    actorSource: 'remove-worktree',
    // An unreadable row cannot prove that it is unrelated to this target. Include it so the
    // conflict matrix refuses teardown instead of turning unknown state into permission.
    filterEntry: (entry) => entries.some((candidate) => candidate.recordId === entry.recordId && candidate.ok === entry.ok),
  });
  // Include pending/strict-relation unknowns that are not part of the ordinary *.json reader.
  const synthetic = entries.filter((entry) => !result.buckets.all.some((row) => row.recordId === entry.recordId));
  if (synthetic.length) {
    const table = readTable();
    const stateRoot = resolveDevRunnerStateRoot(mainRepoRoot, env);
    const extra = reapEligible({
      records: synthetic,
      processTable: table,
      occasion: 'worktree-teardown',
      callerOwnerKey: callerIdentity?.owner?.key ?? null,
      now,
      thresholds,
      devRunnerActive: await readDevRunnerActiveRun({ stateRoot }),
      env,
    });
    result.buckets.all.push(...extra.all);
    result.buckets.refuse.push(...extra.refuse);
    result.buckets.blocksProceed = result.buckets.blocksProceed || extra.blocksProceed;
  }
  const uncleared = result.kills.filter((kill) => !kill.confirmed && kill.identity?.verdict !== 'mismatch');
  if (uncleared.length) {
    result.buckets.blocksProceed = true;
    result.executionBlockers = uncleared;
  }
  return result;
}

async function assertOptionalRegisterDirectory(dir) {
  try {
    const stat = await fsp.lstat(dir);
    if (stat.isSymbolicLink()) throw new Error(`${dir} is a symlink`);
    if (!stat.isDirectory()) throw new Error(`${dir} is not a directory`);
  } catch (err) {
    if (err?.code === 'ENOENT') return;
    throw err;
  }
}

/**
 * Read-only counterpart to consultAgentSpawnsForTeardown for `remove-worktree --dry-run`.
 * It deliberately stops after the pure reapEligible projection: no executeReap, refusal mark,
 * prune, lease renewal, or register write occurs. Unreadable rows are retained because their
 * relationship to the target is unknown and therefore blocks the preview verdict.
 */
async function inspectAgentSpawnsForTeardown({
  mainRepoRoot,
  targetPath,
  callerIdentity = null,
  now = Date.now(),
  env = process.env,
  readTable = readProcessTable,
  thresholds = DEFAULT_THRESHOLDS,
} = {}) {
  const stateRoot = resolveDevRunnerStateRoot(mainRepoRoot, env);
  const dir = resolveAgentSpawnsRegisterDir(mainRepoRoot, env);
  const entries = await readTeardownEntries({ dir, targetPath });
  if (entries.length === 0) {
    return {
      occasion: 'worktree-teardown', dir,
      buckets: { reap: [], contention: [], refuse: [], report: [], all: [], blocksProceed: false, markPending: [] },
    };
  }
  const processTable = readTable();
  const devRunnerActive = await readDevRunnerActiveRun({ stateRoot });
  const buckets = reapEligible({
    records: entries,
    processTable,
    occasion: 'worktree-teardown',
    callerOwnerKey: callerIdentity?.owner?.key ?? null,
    now,
    thresholds,
    devRunnerActive,
    env,
  });
  return { occasion: 'worktree-teardown', dir, buckets };
}

/**
 * The ADVISORY assembly for `orientation` (861 §6.4: `world-state.mjs`'s read-only section —
 * "It never kills"). Since `occasion: 'orientation'` binds to `capability: 'advisory'` in the
 * reaper's frozen `OCCASIONS` map, `reapEligible` mints no `reap` entries here regardless of
 * what this function passes in — the advisory downgrade is enforced in the reaper, not here.
 *
 * Degrades to `{ available: false, reason }` on any failure, matching `world-state.mjs`'s own
 * per-section "degrade to unavailable, never crash" contract (never throws).
 *
 * `callerIdentity` (D2, closing-window findings): without it, `reapEligible`'s SAME-SESSION
 * check (the record's owner key equals the caller's, `agent-spawn-reaper.cjs`) can never fire,
 * so the calling session's OWN live spawn falls through to the CONTENTION branch and reads
 * `other-session/lease-live` in the orientation report - a session misattributing its own record
 * to "another session". Callers resolve it via `resolveCallerIdentity` (the one identity rule,
 * shared with `remove-worktree.cjs`) and pass it through, exactly as `findBuildHolders`/
 * `consultAgentSpawnsForTeardown` do.
 */
async function gatherAgentSpawnOrientation({
  mainRepoRoot,
  callerIdentity = null,
  now = Date.now(),
  env = process.env,
  readTable = readProcessTable,
  thresholds = DEFAULT_THRESHOLDS,
} = {}) {
  try {
    const stateRoot = resolveDevRunnerStateRoot(mainRepoRoot, env);
    const dir = resolveAgentSpawnsRegisterDir(mainRepoRoot, env);
  
    const rawEntries = await readAgentSpawnRegister({ dir });
    const tableResult = readTable();
    const devRunnerActive = await readDevRunnerActiveRun({ stateRoot });

    const registeredPids = new Set(
      rawEntries.filter((e) => e.ok && Number.isInteger(e.record?.pid)).map((e) => e.record.pid),
    );
    const observed = tableResult.ok ? deriveObservedRows(tableResult.table, registeredPids) : [];

    const buckets = reapEligible({
      records: rawEntries,
      processTable: tableResult,
      occasion: 'orientation',
      callerOwnerKey: callerIdentity?.owner?.key ?? null,
      now,
      thresholds,
      devRunnerActive,
        observed,
      env,
    });

    // [F7] Orientation never calls `executeReap`, so a refusal it evaluates would otherwise
    // never be marked — discharge it here, the same one-line call the sweep occasions use.
    const marked = await markRefusals(buckets.markPending, { dir, now });

    return { available: true, dir, buckets, marked };
  } catch (err) {
    return { available: false, reason: String(err?.message || err).slice(0, 200) };
  }
}

/**
 * The ADVISORY assembly for `before-a-build` (861 §6.4 / [A4]: "advisory only; it never
 * kills"). Pre-filters the register to records that `recordHoldsTree` says hold `targetPath`
 * — the same pre-filter `remove-worktree.cjs`'s teardown occasion uses — so a build about to
 * write under `targetPath` is warned about a REAL holder, not every registered spawn in the
 * register regardless of what it touches. [F-4]: checks both containment directions (a worktree
 * Vite holding MAIN's `node_modules` is caught when `targetPath` is the MAIN tree).
 *
 * `occasion: 'before-a-build'` binds to `capability: 'advisory'` in the reaper's frozen map,
 * so — exactly as `gatherAgentSpawnOrientation` above — no `reap` entry can ever be minted
 * here; this function could not obtain a kill list even if it tried to.
 *
 * `recordFilter` (861 W5 review F-5) applies BEFORE the process-table read, not after: the
 * build hint's per-session marker de-dup passes `(e) => !alreadyNudged(sessionId, e.recordId)`
 * here so an already-nudged holder never causes a PowerShell spawn on a subsequent gradlew/npm
 * call — check before work, not after.
 *
 * Returns `{ holders: [] }` fast (no process-table read) when the pre-filter finds nothing —
 * the common case, and the reason this hint stays cheap enough for a PreToolUse hook budget.
 */
async function findBuildHolders({
  mainRepoRoot,
  targetPath,
  callerIdentity = null,
  now = Date.now(),
  env = process.env,
  readTable = readProcessTable,
  thresholds = DEFAULT_THRESHOLDS,
  recordFilter = null,
} = {}) {
  const stateRoot = resolveDevRunnerStateRoot(mainRepoRoot, env);
  const dir = resolveAgentSpawnsRegisterDir(mainRepoRoot, env);

  const rawEntries = await readAgentSpawnRegister({ dir });
  const holderCandidates = [];
  for (const e of rawEntries) {
    if (!e.ok || !e.record) continue;
    // eslint-disable-next-line no-await-in-loop -- bounded by AGENT_SPAWNS_MAX_RECORDS (64)
    if (!(await recordHoldsTree(e.record, targetPath))) continue;
    if (recordFilter && !recordFilter(e)) continue;
    holderCandidates.push(e);
  }
  if (holderCandidates.length === 0) return { holders: [], marked: null };

  const processTable = readTable();
  const devRunnerActive = await readDevRunnerActiveRun({ stateRoot });
  const buckets = reapEligible({
    records: holderCandidates,
    processTable,
    occasion: 'before-a-build',
    callerOwnerKey: callerIdentity?.owner?.key ?? null,
    now,
    thresholds,
    devRunnerActive,
    env,
  });
  const marked = await markRefusals(buckets.markPending, { dir, now });
  return { holders: buckets.all, marked };
}

/**
 * One human-readable line for a `reapEligible` entry — shared by the CLI, the hint hooks,
 * `remove-worktree.cjs`, and `world-state.mjs`'s markdown rendering, so the four don't each
 * invent their own phrasing.
 *
 * [F-2b] The PRIMARY remedy is the sweep CLI, not a bare `taskkill` line: branch-safety.md's own
 * new rule says "never hand-`taskkill` one; run `node scripts/dev/agent-spawn-sweep.cjs` first"
 * — printing the forbidden command as if it were THE fix would contradict the rule this same
 * tempdoc just added. The sweep goes through the full matrix (identity re-verification, the
 * activity join, marking/pruning); a bare kill bypasses all of it. Only registered entries
 * (`recordId` present) get the sweep line — an observed-tier entry has no register row for a
 * sweep to act on, so `killLine` (still last-resort, still explicit about the risk) is what's left.
 */
function describeEntry(entry) {
  const who = entry.record?.producer ? `${entry.record.producer} (pid ${entry.pid ?? '?'})` : `pid ${entry.pid ?? '?'}`;
  const head = `[${entry.disposition}${entry.downgraded ? `, ceiling=${entry.ceiling}` : ''}] ${entry.cell}: ${who}`;
  const reason = entry.reason ? ` — ${entry.reason}` : '';
  const sweep = entry.recordId
    ? '\n    resolve via sweep: node scripts/dev/agent-spawn-sweep.cjs --occasion session-start'
    : '';
  const kill = entry.killLine
    ? `\n    force, only if you are certain it is safe (bypasses identity re-verification): ${entry.killLine}`
    : '';
  return `${head}${reason}${sweep}${kill}`;
}

module.exports = {
  resolveMainRepoRoot,
  resolveCallerIdentity,
  resolveDevRunnerStateRoot,
  KNOWN_AGENT_SPAWN_FINGERPRINTS,
  deriveObservedRows,
  holdsWithin,
  recordHoldsTree,
  strictRealpathNearest,
  strictRecordHoldsTree,
  runAgentSpawnSweep,
  consultAgentSpawnsForTeardown,
  inspectAgentSpawnsForTeardown,
  gatherAgentSpawnOrientation,
  findBuildHolders,
  describeEntry,
};
