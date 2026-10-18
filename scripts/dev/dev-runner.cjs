#!/usr/bin/env node
/**
 * Dev runner (Windows-first): start/stop/status/cleanup with durable run-state under tmp/dev-runner/.
 *
 * Contract:
 * - docs/tempdocs/13/02-dev-runner-cli.md
 */
/* eslint-disable no-console */

'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const os = require('os');
const net = require('net');
const http = require('http');
const crypto = require('crypto');
const { spawn, spawnSync, execFile } = require('child_process');
// Tempdoc 696: resolve a >= 24 JDK (target Temurin 25) so a stale JDK-8 JAVA_HOME
// can't break the build/Engine JVMs. Injected into every JVM spawn's env below.
const { resolveJdkHome } = require(path.join(__dirname, 'lib', 'resolve-jdk.cjs'));
const { engineJavaLaunch } = require('./lib/engine-java-launch.cjs');
const { engineIdentityAsync, startStopExitCensus, readRunLedger, ledgerView, ledgerResolution,
  carryFindings, stopProves, lookupObservation } = require('./lib/stop-exit-census.cjs');
const { TICK, compareIdentity, identityOf, identityKey, hasPid, birthOrder, bornAfter } = require('./lib/ledger-identity.cjs');
const engineActuator = require('./lib/engine-actuator.cjs');
const jobRun = require('./lib/job-run.cjs');
const devDataFolder = require('./lib/dev-data-folder.cjs');
const ledgerLib = require('./lib/exit-ledger.cjs');
const processIdentity = require('./lib/process-identity.cjs');

const repoRoot = path.resolve(__dirname, '..', '..');
const uiWebDir = path.resolve(repoRoot, 'modules', 'ui-web');
const { runToolSync, toolInvocation } = require('../lib/tool-invoker.cjs');
const gradlePath = toolInvocation('gradle', [], { repoRoot }).command;

const resolveMainRepoRoot = () => require('./lib/dev-runtime.cjs').resolveMainRepoRoot(repoRoot);

const mainRepoRoot = resolveMainRepoRoot();
// State root defaults to the SHARED main-repo location so all worktrees coordinate on one
// lease. Overridable via JUSTSEARCH_DEV_RUNNER_STATE_ROOT for an ISOLATED dev-runner — used by
// integration tests and any throwaway stack that must not touch the shared lease (tempdoc 606
// validation seam). Normally unset in the runner's own env (the runner only EMITS it to the
// Head child below), so the default holds in production.
const stateRoot = require('./lib/process-record.cjs').resolveRegisterDir(mainRepoRoot, '');
const runsRoot = path.join(stateRoot, 'runs');
const activePath = path.join(stateRoot, 'active.json');
// Tempdoc 542 §B Layer 2: op-leases.json is Head's lease registry. Single Java writer
// (OperationLeaseServiceImpl); read here at admission time for criticality-aware dispatch.
const opLeasesPath = path.join(stateRoot, 'op-leases.json');
const {
  computeOwnershipVerdict,
  classifyActivity,
  readSessionActivity,
} = require('./lib/ownership-verdict.cjs');
// Agent identity (who is calling) and owner presence (is the owner still running, has it used the
// stack lately). Identity comes from the caller's harness process, never from a shared file;
// presence from that process and from the touch records under <stateRoot>/owners/.
const agentIdentity = require('./lib/agent-identity.cjs');
const ownerPresence = require('./lib/owner-presence.cjs');
// Lane F stage B item B8: the supervisor's DECISION seam, shared with the Tauri half by way of
// governance/supervision-contract.v1.json. Everything that touches a process, a socket or the clock
// is the actuator's, and the actuator is this file — see the state machine below `cmdStart`.
const engineSupervisor = require('./lib/engine-supervisor.cjs');
const RUN_RETENTION_MS = 14 * 24 * 60 * 60 * 1000;
const RUN_RETENTION_COUNT = 200;
// Tempdoc 735 G6: campaign-length lease hold. Passive-expiry default stays 30s (unchanged
// behavior); a starter that declares intent can hold ownership up to 2h without depending on
// the presence-aware renewer, so a busy-but-CLI-silent measurement campaign (minutes of
// jseval/gradle activity with no Claude Code session touches) doesn't get reaped or taken over
// mid-run purely because the 10s renewal loop paused on a stale-activity read.
const DEFAULT_LEASE_DURATION_SEC = 30;
const MIN_LEASE_DURATION_SEC = 30;
const MAX_LEASE_DURATION_SEC = 7200;

function clampLeaseDurationSec(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return DEFAULT_LEASE_DURATION_SEC;
  const n = Math.round(Number(value));
  return Math.min(MAX_LEASE_DURATION_SEC, Math.max(MIN_LEASE_DURATION_SEC, n));
}

class NoActiveRunError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NoActiveRunError';
    this.code = 'NO_ACTIVE_RUN';
  }
}

/**
 * A caller input the runner refuses before doing any work. It carries its own documented `code`;
 * the top-level writer reports its one-line message without a stack, because nothing failed inside
 * the runner.
 */
class DevRunnerValidationError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'DevRunnerValidationError';
    this.code = code;
  }
}

function isNoActiveRunError(err) {
  return err instanceof NoActiveRunError || err?.code === 'NO_ACTIVE_RUN' || err?.name === 'NoActiveRunError';
}

function nowIso() {
  return new Date().toISOString();
}

function toPosix(p) {
  return String(p).split(path.sep).join('/');
}

/**
 * A run-state path as results and reports name it: relative to the MAIN checkout, the base
 * `active.runPath` is stored against, whichever checkout (main or a worktree) is running this
 * command. A path with no relative form from there (a state root on another drive) is named
 * absolute. Either way `path.resolve(<main checkout>, result)` is the file. Pure.
 */
function mainRelativePath(absPath, base = mainRepoRoot) {
  const rel = path.relative(base, absPath);
  return toPosix(rel && !path.isAbsolute(rel) ? rel : path.resolve(absPath));
}

async function mkdirp(p) {
  await fsp.mkdir(p, { recursive: true });
}

async function writeJsonAtomic(filePath, obj) {
  const tmp = `${filePath}.tmp`;
  const json = JSON.stringify(obj, null, 2) + '\n';
  await mkdirp(path.dirname(filePath));
  try {
    await fsp.writeFile(tmp, json, 'utf8');
    const renameDeadline = performance.now() + 1_000;
    let lastRefusal;
    for (;;) {
      // Windows readers may omit delete sharing. Keep the complete candidate until the
      // handle closes, but do not start another attempt after this publication's deadline.
      if (lastRefusal && performance.now() >= renameDeadline) throw lastRefusal;
      try {
        await fsp.rename(tmp, filePath);
        break;
      } catch (error) {
        if (process.platform !== 'win32' || !['EPERM', 'EBUSY'].includes(error?.code)) throw error;
        lastRefusal = error;
        await new Promise(resolve => setTimeout(resolve, 10));
      }
    }
  } catch (err) {
    // Clean up temp file on failure
    await fsp.rm(tmp, { force: true }).catch(() => { });
    throw err;
  }
}

async function readJsonIfExists(filePath) {
  try {
    const raw = await fsp.readFile(filePath, 'utf8');
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function parseArgs(argv) {
  const out = {
    cmd: null,
    json: false,
    uiPort: 5173,
    apiPort: 0,
    dataDir: null,
    clean: 'soft', // soft|hard|none
    runId: null,
    active: false,
    force: false,
    takeover: 'deny',
    confirmInterrupt: null,
    skipBuild: false,
    hotReload: false,
    sessionId: null,
    leaseDurationSec: DEFAULT_LEASE_DURATION_SEC,
    chatProfile: null,
    promptCacheMiB: null,
    distFromRoot: null,
  };

  const args = [...argv];
  out.cmd = args[0] || null;

  for (let i = 1; i < args.length; i += 1) {
    const token = args[i];
    if (!token.startsWith('--')) continue;
    const [key, inline] = token.split('=', 2);
    const takeValue = () => {
      if (inline != null) return inline;
      const next = args[i + 1];
      if (next == null || next.startsWith('--')) {
        throw new Error(`Missing value for ${key}`);
      }
      i += 1;
      return next;
    };

    switch (key) {
      case '--json':
        out.json = true;
        break;
      case '--ui-port':
        out.uiPort = Number(takeValue());
        break;
      case '--api-port':
        out.apiPort = Number(takeValue());
        break;
      case '--data-dir':
        out.dataDir = takeValue();
        break;
      case '--clean':
        out.clean = String(takeValue() || '').toLowerCase();
        break;
      case '--run':
        out.runId = takeValue();
        break;
      case '--active':
        out.active = true;
        break;
      case '--force':
        out.force = true;
        break;
      case '--forget-unresolved':
        out.forgetUnresolved = true;
        break;
      case '--takeover':
        out.takeover = takeValue();
        break;
      case '--confirm-interrupt':
        // Tempdoc 542 Layer 4: typed confirmation token matching the live opId; required
        // when `force` interrupts an `unsafe-to-interrupt` op-lease. Prevents typo'd reclaims.
        out.confirmInterrupt = takeValue();
        break;
      case '--skip-build':
        out.skipBuild = true;
        break;
      case '--hot-reload':
        out.hotReload = true;
        break;
      case '--session-id':
        out.sessionId = takeValue();
        break;
      case '--lease-duration-sec':
        // Tempdoc 735 G6: clamp here so every downstream reader (lease record write +
        // periodic renewal) sees one already-clamped value — no second clamp site to drift.
        out.leaseDurationSec = clampLeaseDurationSec(takeValue());
        break;
      case '--chat-profile':
        // Tempdoc 842 §2.4: chat model profile ("compact" | "standard"), forwarded to the
        // backend spawn env as JUSTSEARCH_CHAT_PROFILE. Validated below.
        out.chatProfile = String(takeValue() || '');
        break;
      case '--prompt-cache-mib':
        out.promptCacheMiB = parsePromptCacheMiB(takeValue());
        break;
      case '--dist-from':
        // Tempdoc 913 T1: the checkout the CALLER asked to launch from (already resolved by the
        // MCP server). Recorded verbatim into the run's provenance so a reader can tell "launched
        // where asked" from "running a checkout nobody asked for" — the two the mismatch check
        // used to conflate. Not a launch input: this process already runs in that tree.
        out.distFromRoot = String(takeValue() || '') || null;
        break;
      case '--help':
      case '-h':
        out.cmd = 'help';
        break;
      default:
        throw new Error(`Unknown flag: ${key}`);
    }
  }

  if (!['soft', 'hard', 'none'].includes(out.clean)) {
    throw new Error(`Invalid --clean: ${out.clean} (expected soft|hard|none)`);
  }
  if (out.forgetUnresolved && out.cmd !== 'stop') {
    throw new Error('--forget-unresolved is only valid for stop; it leaves processes running and a person decides. It does not clean data.');
  }
  if (!Number.isFinite(out.uiPort) || out.uiPort <= 0) throw new Error(`Invalid --ui-port: ${out.uiPort}`);
  if (!Number.isFinite(out.apiPort) || out.apiPort < 0) throw new Error(`Invalid --api-port: ${out.apiPort}`);
  if (out.chatProfile != null && !['compact', 'standard'].includes(out.chatProfile)) {
    throw new Error(`Invalid --chat-profile: ${out.chatProfile} (expected compact|standard)`);
  }
  return out;
}

function printUsage() {
  console.error(
    [
      'Usage: node scripts/dev/dev-runner.cjs <command> [options]',
      '',
      'Commands:',
      '  start   [--ui-port 5173] [--api-port 0|33221] [--data-dir <path>] [--clean soft|hard|none] [--json]',
      '          [--lease-duration-sec 30-7200]  (campaign-length ownership hold; default 30, clamped)',
      '          [--chat-profile compact|standard]  (backend JUSTSEARCH_CHAT_PROFILE; dev default compact)',
      '          [--prompt-cache-mib <MiB>]  (chat host cache; default 1024, eval and VDU always 0)',
      '          [--dist-from <root>]  (record-only: the checkout the MCP caller asked to launch from)',
      '  status  [--run <runId>|--active] [--json]',
      '  stop    [--run <runId>|--active] [--force] [--json]',
      '  stop    [--run <runId>|--active] --forget-unresolved [--json] (leaves processes running; a person decides)',
      '          Forgetting leaves processes running; a person decides. Only the owner may clear an incomplete stop.',
      '  cleanup [--run <runId>|--active] [--force] [--clean soft|hard|none] [--json]',
      '  doctor  [--json]   report the onramp capability tier / what is missing / next remedy',
      '',
      'Notes:',
      '  - start is a long-running supervisor (foreground). Use Ctrl+C or run stop/cleanup to tear down the stack.',
      '  - When --json is used, stdout prints exactly one JSON object (no extra human logs).',
      '  - --clean soft|hard cleans only a data folder a dev tool created and marked (.justsearch-dev-data.json): a new or',
      '    empty folder is marked; any other non-empty folder is refused unchanged (DATA_DIR_NOT_OURS).',
      '',
      'State:',
      `  ${toPosix(activePath)}`,
      `  ${toPosix(runsRoot)}/<runId>/run.json`,
    ].join('\n'),
  );
}

function resolveDataDir(dataDirArg) {
  if (dataDirArg) {
    const p = path.isAbsolute(dataDirArg) ? path.resolve(dataDirArg) : path.resolve(repoRoot, dataDirArg);
    // Validate path is under repoRoot to prevent path traversal
    const normalized = path.resolve(p);
    const repoNormalized = path.resolve(repoRoot);
    if (!normalized.startsWith(repoNormalized + path.sep) && normalized !== repoNormalized) {
      throw new Error(`--data-dir must be under repo root: ${dataDirArg}`);
    }
    return p;
  }
  return path.resolve(uiWebDir, '.dev-data');
}

/**
 * Top-level data-dir entry names owned by an AUTHORED durable store, read from
 * governance/store-recoverability.v1.json (the register `check-store-recoverability` enforces).
 *
 * Each row's `ownedPaths` are data-dir-relative globs ("audit/action-ledger.jsonl",
 * "memories/*.json", "watched-roots.json"); the first path segment is the entry a directory-level
 * clean would remove. Rows of every `root` are included: AI_HOME and PROGRAM_DATA_OR_DATA_DIR can
 * resolve inside the dev data dir, and the set is only ever used to PRESERVE, so a superfluous
 * name costs nothing while a missing one costs user-authored data. Fails soft — an unreadable or
 * malformed register yields an empty set, leaving the hand-maintained floor exactly as it was.
 */
function authoredStoreTopLevelNames(registerPath) {
  const file = registerPath || path.join(repoRoot, 'governance', 'store-recoverability.v1.json');
  const names = new Set();
  try {
    const register = JSON.parse(fs.readFileSync(file, 'utf8'));
    for (const store of register.durableStores || []) {
      if (store?.recoverability !== 'AUTHORED') continue;
      for (const owned of store.ownedPaths || []) {
        if (typeof owned !== 'string') continue;
        const head = owned.replace(/\\/g, '/').split('/')[0];
        // A glob in the FIRST segment names no single entry to keep — skip rather than guess.
        if (!head || head.includes('*')) continue;
        names.add(head);
      }
    }
  } catch (err) {
    console.warn(`[dev-runner] store-recoverability register unreadable (${err.message}); soft-clean keeps only the built-in list`);
  }
  return names;
}

async function cleanDataDir(dir, mode) {
  if (mode === 'none') return;
  if (mode === 'hard') {
    // Preserve ui/ so llmModelPath and other UI settings survive a hard reset.
    // Without this, AI activation fails with MODEL_PATH_REQUIRED until the user
    // reconfigures the model path manually.
    const hardKeep = new Set(['ui', devDataFolder.MARKER]);
    await mkdirp(dir);
    const entries = await fsp.readdir(dir).catch(() => []);
    for (const ent of entries) {
      if (hardKeep.has(ent)) continue;
      await fsp.rm(path.join(dir, ent), { recursive: true, force: true }).catch(() => { });
    }
    return;
  }
  // soft: preserve config/index/watched roots/ui settings + AI models/packs/policy + GPL data.
  const keep = new Set([
    devDataFolder.MARKER, 'config', 'index', 'watched_roots.json', 'ui',
    'models', 'installed-packs.v1.json', 'policy.v1.json',
    'gpl-training-triples.ndjson',     // GPL training data (hours of LLM work)
    'gpl-eval-snapshot.json',          // GPL eval snapshot (revalidation baseline)
  ]);
  // ...plus every AUTHORED durable store. The hand list above is the floor, not the authority:
  // governance/store-recoverability.v1.json is where "this store holds user-authored data that
  // nothing can regenerate" is declared, and a soft clean that deletes an AUTHORED store (audit/,
  // conversations/, memories/, feedback/, ...) destroys exactly what AUTHORED means. Deriving the
  // set here instead of restating it keeps this from forking off the register the next time a
  // store is added. Additive only — a register that cannot be read leaves the floor intact.
  for (const owned of authoredStoreTopLevelNames()) keep.add(owned);
  await mkdirp(dir);
  const entries = await fsp.readdir(dir).catch(() => []);
  for (const ent of entries) {
    if (keep.has(ent)) continue;
    if (ent === 'runtime' && fs.existsSync(path.join(dir, 'runtime', 'exit-ledger'))) {
      // Spec B step 2 / C7, Spec F §5.3: the exit ledger is history the next supervisor reconciles
      // (an Engine start left open by the run before a clean); everything else in runtime/ goes.
      const inner = await fsp.readdir(path.join(dir, 'runtime')).catch(() => []);
      for (const name of inner) {
        if (name === 'exit-ledger') continue;
        await fsp.rm(path.join(dir, 'runtime', name), { recursive: true, force: true }).catch(() => { });
      }
      continue;
    }
    await fsp.rm(path.join(dir, ent), { recursive: true, force: true }).catch(() => { });
  }
}

/**
 * A soft or hard clean deletes most of a data folder, so it runs only on a folder a dev tool
 * created and marked (lib/dev-data-folder.cjs): a missing folder is created and marked, an empty
 * one marked, the default dev folder adopted only when it holds nothing but dev-data contents.
 * Any other folder is refused unchanged (DATA_DIR_NOT_OURS); `--clean none` deletes nothing and
 * checks nothing.
 */
function dataFolderOptions(dir) {
  return {
    tool: 'dev-runner',
    isDefault: path.resolve(dir) === resolveDataDir(null),
    registerPath: path.join(repoRoot, 'governance', 'store-recoverability.v1.json'),
    defaultHint: toPosix(path.relative(repoRoot, resolveDataDir(null))),
  };
}

function dataFolderRefusal(dir, verdict) {
  const err = new Error(verdict.message);
  err.code = 'DATA_DIR_NOT_OURS';
  err.details = { dataDir: toPosix(path.resolve(dir)), reason: verdict.reason, marker: devDataFolder.MARKER,
    ...(verdict.unknown ? { unknown: verdict.unknown.slice(0, 20) } : {}) };
  return err;
}

/** Throws DATA_DIR_NOT_OURS when a clean of `dir` would not be allowed; changes nothing. */
function assertCleanableDataDir(dir, mode) {
  if (mode === 'none') return;
  const verdict = devDataFolder.inspectDevDataFolder(dir, dataFolderOptions(dir));
  if (!verdict.ok) throw dataFolderRefusal(dir, verdict);
}

/** The clean, on this tool's own folder only: it is claimed (marked) first, or refused unchanged. */
async function cleanOwnDataDir(dir, mode) {
  if (mode === 'none') return;
  const claim = devDataFolder.claimDevDataFolder(dir, dataFolderOptions(dir));
  if (!claim.ok) throw dataFolderRefusal(dir, claim);
  await cleanDataDir(dir, mode);
}

async function getRunDirectoryTimestamp(runDir) {
  const runJson = await readJsonIfExists(path.join(runDir, 'run.json'));
  const candidates = [
    runJson?.updatedAt,
    runJson?.stoppedAt,
    runJson?.startedAt,
  ].filter(Boolean);
  for (const candidate of candidates) {
    const parsed = Date.parse(candidate);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  try {
    const stat = await fsp.stat(runDir);
    return stat.mtimeMs;
  } catch {
    return 0;
  }
}

async function pruneHistoricRuns({
  preserveRunIds = [],
  retentionMs = RUN_RETENTION_MS,
  keepLatestCount = RUN_RETENTION_COUNT,
  runsDirectory = runsRoot,
} = {}) {
  await mkdirp(runsDirectory);
  const preserve = new Set((preserveRunIds || []).filter(Boolean));
  const cutoffMs = Date.now() - retentionMs;
  const entries = await fsp.readdir(runsDirectory, { withFileTypes: true }).catch(() => []);
  const runs = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const runDir = path.join(runsDirectory, entry.name);
    runs.push({
      runId: entry.name,
      runDir,
      timestampMs: await getRunDirectoryTimestamp(runDir),
    });
  }
  runs.sort((left, right) => right.timestampMs - left.timestampMs);

  const toDelete = [];
  for (let index = 0; index < runs.length; index += 1) {
    const run = runs[index];
    const keepBecauseRecent = run.timestampMs >= cutoffMs;
    const keepBecauseCount = index < keepLatestCount;
    const keepBecauseExplicit = preserve.has(run.runId);
    if (keepBecauseRecent || keepBecauseCount || keepBecauseExplicit) {
      continue;
    }
    toDelete.push(run);
  }

  const deletedRunIds = [];
  for (const run of toDelete) {
    await fsp.rm(run.runDir, { recursive: true, force: true }).catch(() => { });
    deletedRunIds.push(run.runId);
  }

  return {
    scanned: runs.length,
    kept: runs.length - deletedRunIds.length,
    deleted: deletedRunIds.length,
    deletedRunIds,
  };
}

/**
 * Tempdoc 656: pure one-time populate of the shared cuda12 GPU runtime. Guarded specifically on the
 * cuda12 exe (NOT "any llama-server runtime") — an existing cuda12 (Install-AI'd or previously staged)
 * is protected, but a stray flat CPU baseline in the same native-bin does NOT block provisioning
 * (that would silently break GPU dev after a stale CPU baseline was left behind). Copies the Gradle
 * cuda stage (exe + adjacent CUDA DLLs) into the shared native-bin. Pure (params + fs) → unit testable.
 *
 * @returns the staged cuda12 exe path if it copied, else null (already present, or no stage source).
 */
function stageSharedCuda12(sharedNativeBin, cudaStageCandidates, exeName) {
  const sharedCuda12 = path.join(sharedNativeBin, 'variants', 'cuda12');
  const sharedCuda12Exe = path.join(sharedCuda12, exeName);
  // Idempotent + don't-clobber, cuda12-SPECIFIC: skip only if a cuda12 runtime is already present.
  if (fs.existsSync(sharedCuda12Exe)) return null;
  const srcDir = cudaStageCandidates.find((d) => fs.existsSync(path.join(d, exeName)));
  if (!srcDir) return null; // no cuda12 built yet — the MCP readiness message reports the remedy
  fs.mkdirSync(sharedCuda12, { recursive: true });
  // Copy the full cuda12 dir (exe + adjacent CUDA DLLs — llama-server loads them from its own dir).
  for (const ent of fs.readdirSync(srcDir, { withFileTypes: true })) {
    if (ent.isDirectory()) continue;
    fs.copyFileSync(path.join(srcDir, ent.name), path.join(sharedCuda12, ent.name));
  }
  return sharedCuda12Exe;
}

/**
 * Tempdoc 656 (Move 1 + Move 2): provision the SHARED GPU (cuda12) llama-server runtime ONCE, at the
 * MAIN checkout, so every worktree references one copy with zero per-worktree download — the same
 * share-from-the-main-checkout property models already have via JUSTSEARCH_MODELS_DIR (see below).
 *
 * This deliberately NO LONGER stages a CPU llama-server baseline (that was tempdoc 618 §3). Per the
 * settled GPU-primary product direction (tempdoc 381: CPU GGUF chat is "not degraded — it's
 * unusable") and tempdoc 656, dev inference is GPU-only: a CPU baseline in dev is a silent fallback
 * that runs the 9B model on CPU (~10x slower + saturates every core → DOSes concurrent worktrees).
 * With no CPU baseline present, inference fails CLOSED (truthful "unavailable" via the runtime
 * manifest's reason codes) instead of silently degrading onto CPU.
 *
 * Populate source: the Gradle cuda stage (`stageLlamaCudaVariant` → build/llama-server/stage/
 * variants/cuda12), produced by a one-time `./gradlew :modules:ui:stageLlamaCudaVariant` at the main
 * checkout. Target: the main checkout's shared native-bin (gitignored). Idempotent; the cuda12-specific
 * guard protects an existing cuda12 while ignoring a stray flat CPU baseline (see stageSharedCuda12).
 */
function ensureSharedCuda12Staged() {
  if (process.platform !== 'win32') return; // prebuilt llama-server staging is Windows-only in dev
  const exeName = require('../lib/native-targets.cjs').executableName('llama-server');
  // The ONE shared runtime location every worktree references (main checkout, gitignored).
  const sharedNativeBin = path.join(mainRepoRoot, 'modules', 'ui', 'native-bin', 'llama-server');
  // Source: a Gradle-built cuda12 stage (main checkout preferred; worktree accepted as a fallback).
  const cudaStageCandidates = [
    path.join(mainRepoRoot, 'modules', 'ui', 'build', 'llama-server', 'stage', 'variants', 'cuda12'),
    path.join(repoRoot, 'modules', 'ui', 'build', 'llama-server', 'stage', 'variants', 'cuda12'),
  ];
  try {
    const staged = stageSharedCuda12(sharedNativeBin, cudaStageCandidates, exeName);
    if (staged) console.error(`[dev] 656: staged shared cuda12 GPU runtime into ${path.dirname(staged)}`);
  } catch (err) {
    console.error(`[dev] 656: warn — failed to stage shared cuda12 runtime: ${err.message}`);
  }
}

/**
 * Tempdoc 656: pure cuda12-only server-exe resolution — a worktree's own (deliberately Install-AI'd)
 * cuda12 first, else the SHARED main-checkout cuda12. Returns the resolved exe path, or null (→
 * JUSTSEARCH_SERVER_EXE stays unset → inference fails CLOSED). NEVER returns a CPU baseline: dev is
 * GPU-only (a CPU 9B fallback DOSes concurrent worktrees). Pure (params + fs only) so it is unit
 * testable; the anti-regression it guards is "a CPU llama-server never gets resolved in dev."
 */
const { resolveCuda12ServerExe } = require('./lib/dev-runtime.cjs');

function resolveAiDevEnv() {
  const env = {};
  // Tempdoc 656: provision the shared cuda12 GPU runtime (once, at the main checkout) so this and
  // every other worktree can reference it. Deliberately NO CPU baseline staging (GPU-only dev).
  ensureSharedCuda12Staged();
  const runtime = require('./lib/dev-runtime.cjs').resolveDevRuntime(repoRoot, mainRepoRoot);
  if (runtime.source === 'override' && !runtime.valid) throw new Error('JUSTSEARCH_SERVER_EXE must name a regular executable file');
  if (runtime.valid) env.JUSTSEARCH_SERVER_EXE = runtime.path;
  if (!process.env.JUSTSEARCH_MODELS_DIR) {
    // Tempdoc 618 §2: prefer the MAIN checkout's models (holds the LFS binaries) over a
    // worktree's models/ (tracked manifests only), so a worktree dev stack finds real models.
    const mainModels = path.join(mainRepoRoot, 'models');
    const localModels = path.join(repoRoot, 'models');
    if (fs.existsSync(mainModels)) env.JUSTSEARCH_MODELS_DIR = mainModels;
    else if (fs.existsSync(localModels)) env.JUSTSEARCH_MODELS_DIR = localModels;
  }
  // The supervisor fixture prepares its own candidate settings. Injecting a higher-priority
  // model path here would silently replace that candidate with the shared development model.
  if (!process.env.JUSTSEARCH_SPLADE_MODEL_PATH
      && process.env.JUSTSEARCH_SUPERVISOR_HARNESS !== '1') {
    const modelsBase = env.JUSTSEARCH_MODELS_DIR || path.join(repoRoot, 'models');
    const spladeDir = path.join(modelsBase, 'splade', 'naver-splade-v3');
    const required = ['model.onnx', 'tokenizer.json', 'vocab.txt'];
    if (required.every(f => fs.existsSync(path.join(spladeDir, f)))) {
      env.JUSTSEARCH_SPLADE_MODEL_PATH = spladeDir;
      env.JUSTSEARCH_SPLADE_ENABLED = 'true';
    }
  }
  return env;
}

/**
 * The calling agent session (see lib/agent-identity.cjs). `--session-id` is an explicit override;
 * otherwise the MCP server's hand-off, then the caller's harness ancestor, then ci/unknown.
 * Resolved once per command (the process table is read at most once for it).
 */
function resolveCallerIdentity(cliSessionId) {
  return agentIdentity.resolveAgentIdentity({ explicit: cliSessionId ?? null });
}

/** The holder block written to active.json: harness, readable label, and the owner key. */
function holderBlockFor(identity) {
  const owner = ownerPresence.ownerBlockFor(identity);
  return {
    source: identity?.harness ?? 'unknown',
    agentSessionId: identity?.sessionId ?? null,
    ...(owner ? { owner } : {}),
  };
}

/** run.json owner confidence: a detected harness is strong evidence, an override weaker. */
function resolveOwnerConfidence(identity, confirmedIbp) {
  if (!identity?.owner) return 'low';
  if (String(identity.source || '').endsWith('process-ancestry')) return confirmedIbp ? 'high' : 'medium';
  return 'medium';
}

/**
 * The backend's environment for operation-lease attribution: `JUSTSEARCH_AGENT_SESSION_ID` is the
 * owner's label when there is one, else the owner key (a Codex-started stack: Codex gives MCP
 * servers no thread id, so there is no label, but the backend's operation leases must still name
 * the owner), and is REMOVED when there is no owner at all, so a value inherited from whoever
 * launched this runner never names the wrong session. The key is used here only; the holder's
 * readable label (`holder.agentSessionId`, merge attribution) stays null rather than invented.
 * The identity hand-off is removed too: it was addressed to this runner, not to its children.
 */
function applyAgentSessionEnv(env, identity) {
  const out = { ...env };
  delete out[agentIdentity.ENV_HANDOFF];
  const name = identity?.sessionId || identity?.owner?.key || null;
  if (name) out.JUSTSEARCH_AGENT_SESSION_ID = name;
  else delete out.JUSTSEARCH_AGENT_SESSION_ID;
  return out;
}

/** A fresh process table (honours the test fixture seam). */
function readFreshProcessTable() {
  return agentIdentity.readTableSync();
}

/** Readable holder name for messages. */
function describeHolder(holder) {
  if (!holder) return 'unknown';
  const name = holder.agentSessionId || holder.owner?.key || null;
  return name ? `${holder.source ?? 'unknown'} session ${name}` : (holder.source ?? 'unknown');
}

/** Lease renewal period; env-tunable so integration tests can drive the supervisor in seconds. */
function leaseRenewIntervalMs(env = process.env) {
  const v = Number(env.JUSTSEARCH_DEV_LEASE_RENEW_MS);
  return Number.isFinite(v) && v >= 100 ? v : 10_000;
}

/** How many ticks between full (pid + creation time) checks of a holder whose pid is present. */
const FULL_PRESENCE_CHECK_EVERY = 30;

/**
 * The supervisor's per-tick presence evaluation. Each tick does a cheap pid-exists check; the full
 * pid + creation-time check (process table) runs when the pid looks gone, every
 * FULL_PRESENCE_CHECK_EVERY ticks (so a reused pid cannot keep an ended owner alive forever),
 * while an end is being timed, and before any reap. Missing evidence never reaps.
 */
function createPresenceMonitor({
  env = process.env,
  now = () => Date.now(),
  readTable = async () => agentIdentity.readTableAsync({ env }),
  pidPresence = (owner) => ownerPresence.ownerPidPresence(owner, { env }),
  readTouch = (key) => ownerPresence.readOwnerRecord(stateRoot, key),
} = {}) {
  let endedSinceMs = null;
  let ticks = 0;
  const thresholds = ownerPresence.presenceThresholds(env);
  const full = async (owner) => ownerPresence.ownerLiveness(owner, { table: await readTable(), now: now() }).state;
  return async function evaluate(current, declaredHoldMs) {
    const owner = current?.holder?.owner ?? null;
    if (!owner?.key || !owner.pid || !owner.creationTime) {
      return { action: 'renew', reason: 'owner unknown; never reaped on that basis' };
    }
    ticks += 1;
    const cheap = pidPresence(owner);
    let liveness;
    let confirmed = false;
    if (cheap === 'absent' || endedSinceMs !== null || ticks % FULL_PRESENCE_CHECK_EVERY === 0) {
      liveness = await full(owner);
      confirmed = true;
    } else {
      liveness = cheap === 'present' ? 'alive' : 'unknown';
    }
    if (liveness === 'ended') {
      if (endedSinceMs === null) endedSinceMs = now();
    } else {
      endedSinceMs = null;
    }
    const lastTouchAt = readTouch(owner.key)?.lastDevStackTouchAt ?? null;
    let decision = ownerPresence.decideSupervisorPresence({
      liveness, lastTouchAt, endedSinceMs, declaredHoldMs, now: now(), thresholds,
    });
    if (decision.action === 'reap' && !confirmed) {
      // A reap is a takeover-class decision: confirm with the full check first.
      liveness = await full(owner);
      if (liveness === 'ended' && endedSinceMs === null) endedSinceMs = now();
      decision = ownerPresence.decideSupervisorPresence({
        liveness, lastTouchAt, endedSinceMs, declaredHoldMs, now: now(), thresholds,
      });
    }
    return decision;
  };
}

/**
 * Ownership gate for commands that mutate someone's running stack without starting one (stop,
 * cleanup). The owner, and anyone facing a dead stack, an ended owner or an anonymous holder,
 * proceeds. Anyone else - including a caller with no agent identity facing an agent-owned stack -
 * gets OWNER_CONFLICT unless it passes --force (an explicit takeover).
 */
async function assertMayMutateRun(opts, verb) {
  if (opts.forgetUnresolved) {
    const { run } = await resolveRunTarget(opts);
    const caller = opts.callerIdentity ?? resolveCallerIdentity(opts.sessionId);
    if (!ownerPresence.isSelf({ holder: run.holder }, caller)) {
      const error = new Error(`Only the recorded stack owner may forget. ${forgetGuidance(run.runId)}`);
      error.code = 'OWNER_CONFLICT';
      throw error;
    }
    return;
  }
  if (opts.force) return;
  const active = await readJsonIfExists(activePath);
  if (!active?.runId || !active.holder) return;
  if (!active.holder.owner && !active.holder.agentSessionId) return; // anonymous holder (as before)
  const callerIdentity = opts.callerIdentity ?? resolveCallerIdentity(opts.sessionId);
  opts.callerIdentity = callerIdentity;
  touchCaller(callerIdentity);
  const runJson = readActiveRun(active);
  const leaseExpired = active.lease?.expiresAt ? new Date(active.lease.expiresAt) < new Date() : true;
  const { decision } = ownerVerdictFor({
    active,
    callerIdentity,
    selfCheck: true,
    supervisorAlive: ownerPresence.supervisorAliveFrom({ runJson, leaseExpired, pidAlive: isPidAlive }),
    leaseExpired,
    opLeases: { byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] }, entries: [] },
    takeover: 'deny',
  });
  // An explicit stop/cleanup of a dead or ended owner's stack is not a start's implicit takeover.
  if (decision.action !== 'conflict' || decision.reason === 'takeover_denied') return;
  const err = new Error(
    `OWNER_CONFLICT: ${verb} rejected - the stack is owned by ${describeHolder(active.holder)}, not by this caller `
    + `(${agentIdentity.describeIdentity(callerIdentity)}). Ask the user, then pass --force to take it over.`);
  err.code = 'OWNER_CONFLICT';
  err.details = { holder: active.holder, lease: active.lease ?? null, verdict: decision.verdict };
  err.holder = active.holder;
  err.lease = active.lease;
  throw err;
}

/** The run record active.json points at (any stored base), or null when it cannot be read. */
function readActiveRun(active) {
  return ownerPresence.readActiveRunRecord(active, { mainRepoRoot, runsRoot });
}

/** Record that the caller used the dev stack (its presence for idle/active grades). */
function touchCaller(identity) {
  try { ownerPresence.touchOwner(stateRoot, identity); } catch { /* best-effort */ }
}

/**
 * The single ownership decision for an owner-keyed record (gate, stop, cleanup). Gathers process
 * evidence only when the holder is someone else with a process identity.
 */
function ownerVerdictFor({ active, callerIdentity, selfCheck, supervisorAlive, leaseExpired, opLeases, takeover = 'deny', confirmInterrupt = null }) {
  const table = ownerPresence.holderNeedsTable(active, callerIdentity) ? readFreshProcessTable() : null;
  return ownerPresence.computeOwnerVerdict({
    active, callerIdentity, selfCheck, supervisorAlive, leaseExpired, stateRoot, table,
    opLeases, takeover, confirmInterrupt, now: Date.now(),
  });
}

/**
 * Spec F §5.3 (S17): a pid is dead only when the OS says it is absent (ESRCH). EPERM is present and
 * any other lookup failure is unknown, which counts as alive: no lock steal, no record removal and
 * no kill is ever based on a failed lookup. The run's own processes are real ones, so the agent
 * process-table fixture (which models harness processes) is not consulted here.
 */
function isPidAlive(pid) {
  const n = Number(pid);
  if (!Number.isFinite(n) || n <= 0) return false;
  return ownerPresence.ownerPidPresence({ pid: n }, { env: {} }) !== 'absent';
}

// Tempdoc 730 B1, re-homed by lane F stage A item A16.
//
// HISTORY. B1 preserved <dataDir>/logs/worker.log per run because that path was rotated by
// WorkerSpawner.java on the NEXT worker spawn — a fixed 2-generation RENAME rotation
// (worker.log -> .log.1 -> .log.2) that a death run's log could fall out of before anyone read
// it. Around that copy sat an ownership guard: a size+mtime stamp taken at readiness, a
// size-monotonicity check at stop time, and a fallback that looked for this run's content at
// worker.log.1 / worker.log.2. All of that existed to answer one question the rename-rotation
// posed: 'is the file at this path still the one THIS run wrote, or did a later spawn replace
// it?'
//
// WHAT A16 CHANGED. Item A11 deleted WorkerSpawner and the Worker child process; item A16
// renamed the surviving log to <dataDir>/logs/engine.log, written by the Engine JVM's own
// Logback FILE appender (modules/ui/src/main/resources/logback.xml). So:
//
//   * The SUBJECT survived and is re-homed: there is a real, growing engine.log to snapshot,
//     and a self-exit is still exactly the death-run scenario B1 exists for.
//   * The HAZARD did not survive, and neither did the machinery built for it. Logback appends
//     to engine.log and rolls by date/size into engine.%d{yyyy-MM-dd}.%i.log.gz — it never
//     renames the live file aside on the next boot. Nothing replaces the path under us, so the
//     stamp, the size-monotonicity check and the .log.1/.log.2 fallback could never fire again:
//     a guard that is structurally incapable of failing is a vacuous green, not a safety net.
//     Deleted with the hazard rather than left pointing at engine.log, where it would have
//     reported 'ownership: verified' unconditionally.
//
// HONEST LIMIT of what remains: because Logback APPENDS across runs, engine.log is cross-run,
// so the preserved copy is 'the engine log as it stood when this run stopped' — it can contain
// earlier runs' lines too. That is a widening, not a loss (B1's failure mode was a MISSING log,
// not an over-full one), and it is why the result no longer claims a per-run ownership verdict.
async function preserveEngineLog(run, runPath, { destSubdir = null } = {}) {
  const dataDirAbs = run?.dataDir ? path.resolve(repoRoot, run.dataDir) : null;
  if (!dataDirAbs) return { preserved: false, reason: 'no_data_dir' };
  const srcEngineLog = path.join(dataDirAbs, 'logs', 'engine.log');
  // Lane F stage B item B8: a supervised run has more than one incarnation, and engine.log is ONE
  // file keyed to the (cross-run, cross-incarnation) dataDir. Without a per-incarnation destination
  // the second death would overwrite the first death's evidence — which is the exact failure tempdoc
  // 730 B1 exists to prevent, arriving one level down. `destSubdir` is how a supervised incarnation
  // asks for its own copy; the default is unchanged, so every existing caller and its tests hold.
  const destLogsDir = destSubdir
    ? path.join(path.dirname(runPath), destSubdir, 'logs')
    : path.join(path.dirname(runPath), 'logs');
  const destEngineLog = path.join(destLogsDir, 'engine.log');

  if (!fs.existsSync(srcEngineLog)) return { preserved: false, reason: 'no_engine_log' };
  try {
    await mkdirp(destLogsDir);
    await fsp.copyFile(srcEngineLog, destEngineLog);
    return { preserved: true, path: mainRelativePath(destEngineLog) };
  } catch (err) {
    return { preserved: false, reason: 'copy_failed', error: err?.message || String(err) };
  }
}

// Tempdoc 730 B2: pure report shape shared by the explicit-stop/reap path (stopRun) and the
// in-process self-exit path (backend.on('exit') in cmdStart) so both produce the SAME
// stop-report.json contract. Kept side-effect-free (no fs/process access) so it is unit-testable
// with a fake exit code / fake PID-liveness list without a live stack.
function buildStopReport({
  runId,
  stoppedAt,
  disposition,
  actor = null,
  victim = null,
  killedPids = [],
  childCleanup = [],
  pidLiveness = [],
  backendExitCode = null,
  ports = null,
  portsClosed = null,
  errors = [],
  engineLog = null,
  criticalOpsInterrupted = null,
  interruptibleWithLossInterrupted = null,
  gracefulBackendShutdown = null,
  incarnation = null,
  processExits = null,
  exitAccountingComplete = null,
  exitAccountingGaps = null,
  exitCensusFile = null,
  exitAccountingScope = null,
  ledgerSummary = null,
  lateTargets = null,
  accountedProcesses = null,
}) {
  return {
    schemaVersion: 2,
    ...(processExits ? { processExits, exitAccountingComplete, exitAccountingGaps, exitCensusFile, exitAccountingScope } : {}),
    // Spec F §6.3 and Spec B §5.2: the run's exit ledger in one line (incarnations, children, their
    // exits by class) beside the census gaps; children the ledger started after the census was
    // armed. Additive, so schemaVersion stays 2.
    ...(ledgerSummary ? { ledgerSummary } : {}),
    ...(lateTargets ? { lateTargets } : {}),
    // Spec B §5.1: observed processes accounted without an exit observation of their own (console
    // hosts of closed ledger children, contained tier C helpers). Additive.
    ...(accountedProcesses ? { accountedProcesses } : {}),
    runId,
    stoppedAt,
    disposition,
    actor,
    victim,
    // Spec B step 9: the report no longer carries taskkill's own exit code and stderr; an exit is
    // read from the ledger and the census, and a failed taskkill is named in `errors`.
    killedPids,
    childCleanup,
    // Tempdoc 730 B2: per-PID liveness probe taken BEFORE the kill attempt, so a
    // 'reaped_abandoned' report can distinguish "backend already dead, reaper cleaned up the
    // shell" from "backend live, reaper killed an abandoned-but-healthy stack".
    pidLiveness,
    // Tempdoc 730 B2: the backend JVM's own exit code when it self-exits (crash/OOM), captured
    // by the backend.on('exit') handler — absent for kill-driven stops (their exit is the census's
    // and the ledger's).
    ...(backendExitCode !== null ? { backendExitCode } : {}),
    ports,
    portsClosed,
    errors,
    // Tempdoc 730 B1 (re-homed, item A16): where this run's engine.log ended up (or why it
    // didn't). Renamed from `workerLog` with the file it names; schemaVersion stays 2 because the
    // only reader of this key is scripts/dev/test-dev-runner-death-observability.mjs — no consumer
    // outside this repo's dev harness reads stop-report.json.
    ...(engineLog ? { engineLog } : {}),
    ...(criticalOpsInterrupted ? { criticalOpsInterrupted } : {}),
    ...(interruptibleWithLossInterrupted ? { interruptibleWithLossInterrupted } : {}),
    // Tempdoc 819 §D: outcome of the graceful POST /api/lifecycle/shutdown attempt made before
    // the backend taskkill fallback. Additive/optional like the two fields above it — no
    // existing reader depends on its absence, so no schemaVersion bump.
    ...(gracefulBackendShutdown ? { gracefulBackendShutdown } : {}),
    // Lane F stage B item B8: WHICH incarnation of a supervised run this report describes. Additive
    // and optional like the three fields above it — absent for every unsupervised path — so
    // schemaVersion stays 2 and the existing readers are untouched.
    ...(incarnation != null ? { incarnation } : {}),
  };
}

// The chat host prompt-cache override (--prompt-cache-mib), passed to the Engine as
// -Djustsearch.llm.prompt_cache_mib. An invalid value is the caller's input error, not a runner
// fault: it is refused before any start work with a typed code and one line, never UNHANDLED with
// a stack (documented in docs/reference/configuration/environment-variables.md).
function parsePromptCacheMiB(value) {
  if (!/^[0-9]+$/.test(String(value)) || Number(value) > 2147483647) {
    throw new DevRunnerValidationError(
      'INVALID_PROMPT_CACHE_MIB',
      `Invalid --prompt-cache-mib ${JSON.stringify(String(value))}: `
        + 'expected a non-negative integer MiB (0..2147483647)',
    );
  }
  return Number(value);
}

// Tempdoc 730 B3: dump on OOM for the Head JVM. Dev-runner JVMs previously ran at JVM-default
// (unbounded) heap with no dump flags, so a runaway leak would thrash/consume RAM rather than
// OOM and leave evidence (see tempdoc 730 §THEORIZE B). The dump flags are always-on and cheap
// (inert if the cause is elsewhere). `-Xmx` is opt-in ONLY (JUSTSEARCH_HEAD_HEAP) — Increment-4
// review found a default cap can ITSELF induce an artifact OOM in the exact death scenario being
// diagnosed (tempdoc 730 Increment-4 review findings, 2026-07-14), so no default bound is emitted.
// Pure function (no process/env access beyond the passed-in values) so the generated flags are
// unit-testable without spawning a JVM.
function buildHeadJavaOpts({ existingJavaOpts, headAotOpts, headDistStamp, logsDir, dataDir, headHeap, debugPort, promptCacheMiB }) {
  const heapBound = headHeap && String(headHeap).trim() ? String(headHeap).trim() : null;
  return [
    existingJavaOpts,
    promptCacheMiB != null
      ? `-Djustsearch.llm.prompt_cache_mib=${parsePromptCacheMiB(promptCacheMiB)}` : null,
    // Lane F PR 0 (design 17.2): one flag set with or without the AOT cache. TieredStopAtLevel=1
    // is gone (its 48 MiB C1-only code cache caused the CodeCache-threshold full GCs 917 Derisk 1
    // measured, and it conflicted with the AOT cache); MetaspaceSize=128m stops the
    // Metaspace-threshold full GCs at start.
    //
    // Lane F item A13 follow-up: UseCompactObjectHeaders and file.encoding join the set. This
    // process is BOTH halves now — Lucene, the job queue and the ONNX session cache share this
    // JVM — and -Dfile.encoding=UTF-8 was previously set by WorkerSpawner for the index half
    // (WorkerSpawner.java:457 before item A11 deleted it). Document extraction decodes untrusted
    // bytes, and the Windows platform default is not UTF-8, so losing it changes decoding
    // silently and only for non-ASCII content.
    //
    // modules/shell/src-tauri/src/lib.rs carries the same shared set for the PACKAGED spawn, and
    // scripts/dev/test-dev-runner-head-java-opts.mjs pins both sides: the list below exactly
    // (deepEqual, so an addition fails the test), and lib.rs by reading its source. The one
    // deliberate divergence is -Xmx: lib.rs pins 2g because a packaged JVM's default (1/4 of
    // physical RAM) is wrong in both directions, while the dev-runner keeps NO default heap
    // (tempdoc 730 Increment-4) and honours JUSTSEARCH_HEAD_HEAP when set.
    '-XX:+UseG1GC -XX:MetaspaceSize=128m -XX:MaxDirectMemorySize=256m -XX:+UseCompactObjectHeaders -XX:-UsePerfData'
      + ' -Dfile.encoding=UTF-8',
    headAotOpts,
    // Tempdoc 606 Piece 2b: the Head echoes this on /api/runtime/manifest so a
    // stale old Head answering on a reused port is detectable (build mismatch).
    headDistStamp ? `-Djustsearch.head.stamp=${headDistStamp}` : null,
    // Logback initializes before HeadlessApp mirrors the environment. Pass the owned directory
    // at JVM entry, as the packaged launcher does, so diagnostics cannot land in the cwd default.
    dataDir ? `-Djustsearch.data.dir=${/\s/.test(dataDir) ? `"${dataDir}"` : dataDir}` : null,
    heapBound ? `-Xmx${heapBound}` : null,
    '-XX:+HeapDumpOnOutOfMemoryError',
    logsDir ? `-XX:HeapDumpPath=${/\s/.test(logsDir) ? `"${logsDir}"` : logsDir}` : null,
    // Lane F stage B item B1. Without this the JVM lets an OutOfMemoryError reach the default
    // uncaught-exception handler, which exits 1 — the SAME code as a boot failure, so a
    // supervisor cannot tell a memory death from a bad config. With it the JVM exits 3.
    // (Measured on Temurin 25.0.2: 3 with the flag, 1 without.) Classified TRANSIENT by
    // app-engine's EngineExit table, i.e. retried under cooldown.
    '-XX:+ExitOnOutOfMemoryError',
    // Hot reload's JDWP listener. Lane F item A11: this flag was built by
    // WorkerSpawner.addDevHotReloadFlags for the Worker CHILD's command line. Deleting the
    // spawner deleted the listener, so from A11 until here HotSwapPush had nothing to connect
    // to — `reload` compiled, failed to attach, and the dev loop was a warm restart. There is
    // one JVM now, so the flag belongs on the Engine's own line.
    //
    // suspend=n, and bound to loopback: this is a local dev affordance, and a JDWP port is
    // remote code execution by design. The caller only passes a port once it has confirmed the
    // port is free (the DEBUG_PORT_UNAVAILABLE verdict), because a JDWP address already in use
    // does not degrade — the JVM refuses to start.
    debugPort
      ? `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${debugPort}`
      : null,
  ].filter(Boolean).join(' ');
}

// Tempdoc 730 B2: write a stop-report for a backend that exited WITHOUT going through
// stopRun() — i.e. the supervisor's backend.on('exit') fired on its own (crash/OOM) or in
// response to an interactive Ctrl+C, not a `stop`/reap taskkill. Before this, that path wrote
// NO stop-report at all (only onExit() closing the log streams), so a silent death left zero
// exit-code artifact — the exact gap §THEORIZE B names. Also preserves engine.log (B1, re-homed
// at item A16), since a self-exit is precisely the "death run" scenario B1 exists for.
async function writeSelfExitStopReport({ runId, runPath, run, backendExitCode, interactive, incarnation = null,
  killedPids = [], childCleanup = [], errors = [] }) {
  const engineLog = await preserveEngineLog(run, runPath);
  const stopReport = buildStopReport({
    runId,
    stoppedAt: nowIso(),
    disposition: interactive ? 'interactive_stop' : 'self_exited',
    backendExitCode,
    killedPids,
    childCleanup,
    pidLiveness: [],
    ports: null,
    portsClosed: null,
    errors,
    engineLog,
    incarnation,
  });
  // An earlier report of the run (a stop's, or an earlier incarnation's death) is kept, not overwritten.
  const kept = await keepEarlierReports(path.dirname(runPath));
  if (kept.earlierReports.length) Object.assign(stopReport, { earlierReports: kept.earlierReports,
    earlierReportFiles: kept.earlierReportFiles });
  // This report takes no census, but what an earlier stop of the run found is carried into it, as into
  // a stop's (carry by default): the latest report never reads as if it had not been found.
  const carried = carryFindings({ exitAccountingGaps: [] }, kept.earlierFindings);
  if (carried.exitAccountingComplete === false) {
    Object.assign(stopReport, { exitAccountingComplete: false, exitAccountingGaps: carried.exitAccountingGaps });
  }
  if (kept.earlierReportNotArchived) {
    stopReport.earlierReportNotArchived = kept.earlierReportNotArchived;
    stopReport.reportWrittenAs = kept.reportName;
    stopReport.errors = [...stopReport.errors, `earlier stop report not kept under a number: ${kept.earlierReportNotArchived.error}; `
      + `this report is ${kept.reportName}`];
  }
  const stopReportPath = path.join(path.dirname(runPath), kept.reportName);
  await writeJsonAtomic(stopReportPath, stopReport);
  // Lane F stage B item B8: the report becomes a per-incarnation record rather than the runner's
  // last act. `stop-report.json` still names the LAST one, so every existing reader keeps working;
  // the per-incarnation copy is what makes a run that died three times readable at all.
  if (incarnation != null) {
    await writeJsonAtomic(
      path.join(path.dirname(runPath), 'incarnations', String(incarnation), 'stop-report.json'),
      stopReport,
    );
  }
  return stopReport;
}

/** Discovery has no run.json yet, but its child cleanup belongs to the same death report. */
async function recordDiscoveryExit({ decision, dataDir, runId, runPath, backendExitCode, incarnation },
  { inspect, terminate, cleanupOptions } = {}) {
  const killedPids = [], errors = [];
  const terminal = [engineSupervisor.ACTIONS.EXHAUSTED, engineSupervisor.ACTIONS.STOP].includes(decision.action);
  const childCleanup = terminal
    ? await cleanupTerminalChildren(dataDir, killedPids, errors, inspect, terminate, cleanupOptions) : [];
  return writeSelfExitStopReport({ runId, runPath, run: { dataDir },
    backendExitCode, interactive: false, incarnation, killedPids, childCleanup, errors });
}

// =============================================================================================
// Lane F stage B item B8 — the supervisor's ACTUATOR half (design 7.1).
//
// The decision is `scripts/dev/lib/engine-supervisor.cjs`'s and is shared with the Tauri shell
// through the register. What lives here is everything a pure function cannot do honestly: spawn,
// wait for the process handle to close, sleep a cooldown, write the request file, force kill, and
// publish the state a reader outside this process can see.
//
// The state file is the point of the whole item. Before B8 an Engine that died took the dev-runner
// with it (`process.exit(code)` in the child's exit handler), so "why is the stack down" had exactly
// one answer available to anyone who was not watching the terminal: nothing. `supervisor.v1.json` is
// visible precisely when the Engine is not, which is why design 7.1 puts it beside the port manifest
// rather than behind an API.
// =============================================================================================

/** `<dataDir>/runtime/supervisor.v1.json` — the live state, rewritten on every transition. */
function supervisorStatePath(dataDir) {
  return path.join(dataDir, 'runtime', 'supervisor.v1.json');
}

/**
 * The terminal-state mirror (stage B checklist Q5's answer (c)).
 *
 * Q5 asks for the terminal record to survive the moment the Engine dies, and points at
 * `RuntimeManifestPublisher`'s append-only per-instance history as the precedent. That mirror is
 * written by Java, keyed by `instanceId`, into `runtime/instances/<instanceId>/` — and the case that
 * needs the record hardest is the one where NO instance ever published a manifest, so there is no
 * instanceId to key it by. The supervisor therefore writes a sibling JSONL inside the same
 * (already sanctioned) `instances` directory: same location convention, same append-only shape,
 * keyed by time instead of by an identity the failure mode may have prevented from existing.
 */
function supervisorHistoryPath(dataDir) {
  return path.join(dataDir, 'runtime', 'instances', 'supervisor-history.v1.jsonl');
}

/** `<dataDir>/runtime/shutdown-request.v1.json` — item B2's out-of-band channel, from this side. */
function shutdownRequestPath(dataDir) {
  return path.join(dataDir, 'runtime', 'shutdown-request.v1.json');
}

/**
 * The supervisor state record. Pure (no fs, no clock beyond what is passed in) so its shape can be
 * asserted without a live stack — the same reason `buildStopReport` is pure.
 */
function buildSupervisorState({
  state,
  runId,
  incarnation,
  pid = null,
  apiPort = null,
  instanceId = null,
  restartCount = 0,
  policy,
  lastExit = null,
  reason = null,
  requestedReason = null,
  readyAt = null,
  updatedAt,
  phase = null,
}) {
  return {
    schemaVersion: 1,
    kind: 'engine-supervisor-state.v1',
    supervisor: 'dev-runner',
    state,
    // Spec F §3.2: `starting` names its phase (spawned -> discovered); additive, absent otherwise.
    ...(phase ? { phase } : {}),
    runId,
    incarnation,
    pid,
    apiPort,
    instanceId,
    restartCount,
    maxRestartAttempts: policy?.maxRestartAttempts ?? null,
    // Recorded, not inferred: an `exhausted` reached under a 1.5 s stability window means something
    // different from one reached under 300 s, and only the file can tell a later reader which it was.
    policyProfile: policy?.harnessActive ? 'harness' : 'product',
    ...(policy?.overridden?.length ? { policyOverrides: policy.overridden } : {}),
    lastExit,
    reason,
    requestedReason,
    readyAt,
    updatedAt,
  };
}

/**
 * Publish a state transition, and mirror the terminal one.
 *
 * Best-effort by construction: a supervisor that cannot write its state file must still supervise.
 * The failure is reported on stderr rather than swallowed, because a silent write failure here
 * would make `exhausted` unobservable — the one state the file exists for.
 */
async function writeSupervisorState(dataDir, record) {
  try {
    await writeJsonAtomic(supervisorStatePath(dataDir), record);
  } catch (err) {
    process.stderr.write(`[dev-runner] supervisor state write failed: ${err?.message ?? err}\n`);
    return;
  }
  if (record.state !== 'exhausted') return;
  try {
    const historyPath = supervisorHistoryPath(dataDir);
    await mkdirp(path.dirname(historyPath));
    await fsp.appendFile(historyPath, `${JSON.stringify(record)}\n`, 'utf8');
  } catch (err) {
    process.stderr.write(`[dev-runner] supervisor history append failed: ${err?.message ?? err}\n`);
  }
}

function createSupervisorStateWriter(dataDir, write = writeSupervisorState) {
  // One run owns one transition stream. The handoff watcher and child-exit handler can publish
  // concurrently; unique temp names alone would still let an older state land after a newer one.
  let tail = Promise.resolve();
  return record => {
    const publication = tail.then(() => write(dataDir, record));
    // Keep later transitions publishable after a rejected writer. The submitting caller still
    // receives that rejection; production writeSupervisorState also reports filesystem failures.
    tail = publication.catch(() => {});
    return publication;
  };
}

/**
 * The cooldown FLOOR: wait until the dead incarnation has actually let go (design 7.1, stage B §2).
 *
 * Not a number, on purpose. Windows keeps file handles until the owning process is gone, and the
 * Engine holds two that decide whether the next incarnation can boot at all: `<dataDir>/app.lock`
 * (AppInstanceLock's exclusive channel — a second instance that finds it held exits DATA_DIR_LOCKED,
 * which the classifier calls NON_TRANSIENT) and `<dataDir>/logs/engine.log`. Restarting before both
 * are free would turn a recoverable crash into `exhausted` on the very next attempt, for a reason
 * that has nothing to do with the crash.
 *
 * Bounded and honest: after `timeoutMs` it returns `{ released: false }` and the caller restarts
 * anyway. A supervisor that blocks forever waiting for a handle is worse than one that tries and
 * records that it did not wait long enough.
 */
async function waitForEngineHandleRelease({ pid, dataDir, timeoutMs = 5000, intervalMs = 100 }) {
  const deadline = Date.now() + timeoutMs;
  const probes = [path.join(dataDir, 'app.lock'), path.join(dataDir, 'logs', 'engine.log')];
  const startedAt = Date.now();
  for (;;) {
    const pidGone = !pid || !isPidAlive(pid);
    let filesFree = true;
    for (const probe of probes) {
      if (!fs.existsSync(probe)) continue;
      try {
        const fd = fs.openSync(probe, 'a');
        fs.closeSync(fd);
      } catch {
        filesFree = false;
        break;
      }
    }
    if (pidGone && filesFree) {
      return { released: true, waitedMs: Date.now() - startedAt };
    }
    if (Date.now() >= deadline) {
      return { released: false, waitedMs: Date.now() - startedAt, pidGone, filesFree };
    }
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

/**
 * Write item B2's request file. The Engine's watcher reads it, runs the ordered shutdown with the
 * reason, and deletes it; the deadline is what covers a JVM that never reads it at all.
 */
async function writeShutdownRequestFile(dataDir, { reason, deadlineEpochMs, issuedBy = 'dev-runner', nonce = null,
  incarnation, requestNonce = crypto.randomUUID() }) {
  if (!incarnation || !requestNonce?.trim()) throw new Error('An addressed shutdown request requires incarnation and requestNonce');
  const target = shutdownRequestPath(dataDir);
  await writeJsonAtomic(target, {
    schemaVersion: 1,
    reason,
    deadlineEpochMs,
    issuedBy,
    incarnation,
    requestNonce,
    ...(nonce ? { nonce } : {}),
  });
  return target;
}

async function cleanupRegisteredChildrenForSupervisorState(state, dataDir, inspect, terminate) {
  return state === engineSupervisor.STATES.EXHAUSTED
    ? cleanupRegisteredChildrenForTerminal(dataDir, inspect, terminate) : [];
}

/** Terminal-only cleanup of children whose three recorded OS identity axes still match. */
async function cleanupRegisteredChildrenForTerminal(dataDir, inspect = inspectProcessIdentity, terminate = terminatePid,
  { timeoutMs = 5000, intervalMs = 100 } = {}) {
  let children;
  try {
    const manifest = JSON.parse(fs.readFileSync(path.join(dataDir, 'runtime', 'manifest.json'), 'utf8'));
    children = Array.isArray(manifest?.children) ? manifest.children : [];
  } catch {
    return [];
  }
  const outcomes = [];
  for (const child of children) {
    const record = { id: child?.id, pid: child?.pid, name: child?.executable ? path.win32.basename(child.executable) : child?.kind,
      startedAt: child?.startedAt ?? 'unknown', creationFileTimeUtc: child?.creationFileTimeUtc ?? null };
    const probe = () => { try { return inspect(child?.pid); } catch { return null; } };
    const identity = probe();
    if (!identity || typeof identity.alive !== 'boolean') {
      outcomes.push({ ...record, outcome: 'unknown-identity' });
      continue;
    }
    if (!identity.alive) {
      outcomes.push({ ...record, outcome: 'dead' });
      continue;
    }
    if (child.processIdentity) {
      if (!processIdentity.linuxIdentity(child)) { outcomes.push({ ...record, outcome: 'unknown-identity' }); continue; }
      if (!compareIdentity(child, { pid: child.pid, processIdentity: identity.processIdentity }, 'same')) {
        outcomes.push({ ...record, outcome: identity.processIdentity ? 'identity-mismatch' : 'unknown-identity' }); continue;
      }
      const killAccepted = await terminate(child.pid, { processIdentity: child.processIdentity });
      const deadline = Date.now() + timeoutMs;
      let after;
      do {
        after = probe();
        if (after?.alive === false || (after?.processIdentity && !compareIdentity(child,
          { pid: child.pid, processIdentity: after.processIdentity }, 'same'))) break;
        await new Promise(resolve => setTimeout(resolve, intervalMs));
      } while (Date.now() < deadline);
      const gone = after?.alive === false || (after?.processIdentity && !compareIdentity(child,
        { pid: child.pid, processIdentity: after.processIdentity }, 'same'));
      outcomes.push({ ...record, processIdentity: child.processIdentity, killAccepted,
        outcome: gone ? 'terminated' : after ? 'termination-failed' : 'unknown-exit' }); continue;
    }
    // ProcessHandle stores millisecond starts; Windows StartTime retains sub-ms ticks.
    // Compare their common represented millisecond, with no tolerance across births.
    const expectedStart = Date.parse(child?.startedAt);
    const actualStart = Date.parse(identity.startedAt);
    const expectedExe = normalizeExecutable(child?.executable);
    const actualExe = normalizeExecutable(identity.executable);
    if (!Number.isFinite(expectedStart) || !Number.isFinite(actualStart)
        || !expectedExe || !actualExe) {
      outcomes.push({ ...record, outcome: 'unknown-identity' });
      continue;
    }
    if (expectedStart !== actualStart || expectedExe !== actualExe) {
      outcomes.push({ ...record, outcome: 'identity-mismatch' });
      continue;
    }
    let killAccepted = false;
    try {
      killAccepted = await terminate(child.pid, { startedAtMs: actualStart });
    } catch { /* verification still owns the outcome */ }
    const deadline = Date.now() + timeoutMs;
    let after;
    for (;;) {
      after = probe();
      if (!after || after.alive !== true || Date.now() >= deadline) break;
      // A different birth means the registered process exited; never touch its replacement.
      if (Date.parse(after.startedAt) !== actualStart
          || normalizeExecutable(after.executable) !== actualExe) break;
      await new Promise(resolve => setTimeout(resolve, intervalMs));
    }
    const gone = after?.alive === false || (after?.alive === true
      && Number.isFinite(Date.parse(after.startedAt)) && normalizeExecutable(after.executable)
      && (Date.parse(after.startedAt) !== actualStart || normalizeExecutable(after.executable) !== actualExe));
    const unknown = !after || typeof after.alive !== 'boolean' || (after.alive
      && (!Number.isFinite(Date.parse(after.startedAt)) || !normalizeExecutable(after.executable)));
    outcomes.push({ ...record, killAccepted, outcome: gone ? 'terminated'
      : unknown ? 'unknown-exit' : 'termination-failed' });
  }
  return outcomes;
}

/** Keep explicit stop JSON and census accounting tied to verified child exits. */
async function cleanupTerminalChildren(dataDir, killedPids, errors, inspect, terminate, options) {
  const outcomes = await cleanupRegisteredChildrenForTerminal(dataDir, inspect, terminate, options);
  for (const child of outcomes) {
    if (child.outcome === 'terminated' && child.killAccepted && !killedPids.includes(child.pid)) {
      killedPids.push(child.pid);
    }
    if (!['dead', 'terminated', 'identity-mismatch'].includes(child.outcome)) {
      errors.push(`Registered child ${child.id} (PID ${child.pid}): ${child.outcome}`);
    }
  }
  return outcomes;
}

function normalizeExecutable(value) {
  if (typeof value !== 'string' || !value.trim()) return null;
  const absolute = path.resolve(value);
  const normalized = process.platform === 'win32' ? absolute.toLowerCase() : absolute;
  return normalized;
}

function inspectProcessIdentity(pid, { platform = process.platform, spawnSync: query = spawnSync } = {}) {
  if (!Number.isInteger(Number(pid)) || Number(pid) <= 0 || Number(pid) > 2147483647) return null;
  if (platform === 'linux') {
    const seen = processIdentity.readLinuxProcess(Number(pid));
    return !seen ? null : !seen.present ? { alive: false }
      : { alive: true, processIdentity: seen.processIdentity ?? null, executable: seen.executable ?? null };
  }
  if (platform !== 'win32') return null;
  const script = [
    '& { param([int]$targetPid)',
    'try { $p=Get-Process -Id $targetPid -ErrorAction Stop } catch { if ($_.FullyQualifiedErrorId -like "NoProcessFoundForGivenId*") { [pscustomobject]@{alive=$false}|ConvertTo-Json -Compress; exit 0 }; exit 1 }',
    '[pscustomobject]@{executable=$p.Path;startedAt=$p.StartTime.ToUniversalTime().ToString("o");alive=$true}|ConvertTo-Json -Compress',
    `} ${Number(pid)}`,
  ].join('\n');
  const result = query('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
    encoding: 'utf8', windowsHide: true, timeout: 5000,
  });
  if (result.error || result.status !== 0) return null;
  try { return JSON.parse(result.stdout); } catch { return null; }
}

/**
 * Ends a registered child only through a handle on which its recorded start (milliseconds, as the
 * manifest records it) is checked again (Spec F §5.3): the inspection before it never licenses a
 * kill by pid alone.
 */
async function terminatePid(pid, { startedAtMs, processIdentity: identity } = {}, { terminate = engineActuator.terminateWithCode } = {}) {
  if (process.platform === 'linux') return identity?.pid === pid && processIdentity.signalLinuxProcess(identity).ok;
  if (process.platform !== 'win32' || !Number.isFinite(startedAtMs)) return false;
  const creationFileTime = String((BigInt(Math.trunc(startedAtMs)) + 11_644_473_600_000n) * 10_000n);
  const result = await terminate(pid, creationFileTime, engineActuator.NON_DISTINCTIVE_KILL_CODE,
    { birthTick: ledgerLib.BIRTH_TICKS.MILLISECOND });
  return result?.ok === true;
}

// Tempdoc 606 Piece 2 (provenance): capture, at spawn, WHICH code the launched
// stack actually runs, so an arriving agent (or the owner after edits) can tell a
// stack built from its own worktree from one built elsewhere / a stale dist.
function resolveGitHead() {
  try {
    const r = spawnSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' });
    if (r.status === 0) return r.stdout.trim() || null;
  } catch { /* git unavailable */ }
  return null;
}

/**
 * Content stamp of the launched Engine dist: a short hash over the lib jars' name|size|mtime.
 * Detects both "wrong worktree" (paired with repoRoot) and "stale dist" (jar changed
 * but installDist reported UP-TO-DATE). Null when the dist dir is absent.
 *
 * Lane F stage A item A13: this used to be described as mirroring the Worker's
 * `generateBuildStamp` (indexer-worker/build.gradle.kts). That distribution is gone and the
 * ADR-0021 task moved to `:modules:ui` (writing modules/ui/build/install/ui/build-stamp.txt), so
 * both stamps now describe the same one distribution — but they are still DIFFERENT stamps, not
 * one mirrored: ADR-0021's is a Gradle content hash written to a file and consumed by jseval and
 * the MCP reload tool, while this one is an mtime-based provenance value computed here, injected
 * as `-Djustsearch.head.stamp` by buildHeadJavaOpts, and cross-checked against the running
 * Engine's self-reported stamp by the MCP server. Do not substitute one for the other.
 */
function computeHeadDistStamp() {
  try {
    const libDir = path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'lib');
    const files = fs.readdirSync(libDir).filter((f) => f.endsWith('.jar')).sort();
    if (files.length === 0) return null;
    const h = crypto.createHash('sha256');
    for (const f of files) {
      const st = fs.statSync(path.join(libDir, f));
      h.update(`${f}|${st.size}|${Math.round(st.mtimeMs)}\n`);
    }
    return h.digest('hex').slice(0, 16);
  } catch { return null; }
}

/**
 * The provenance block stamped on the lease/run at spawn.
 *
 * Tempdoc 913 T1: `distFromRoot` is the checkout the CALLER requested (`start { distFrom }`),
 * null when it just ran in the caller's own tree. `repoRoot` is where this process actually is,
 * so the two together answer "was it launched where it was asked to be" — which is what separates
 * a deliberate worktree launch from a stack running code nobody asked for.
 */
function resolveProvenance(distFromRoot = null) {
  return {
    repoRoot: toPosix(repoRoot),
    distFromRoot: distFromRoot ? toPosix(path.resolve(distFromRoot)) : null,
    gitHead: resolveGitHead(),
    headDistStamp: computeHeadDistStamp(),
  };
}

/**
 * Tempdoc 844 §4.2 R3 — the per-run hot-reload record.
 *
 * The JDWP port was a hardcoded 5005 in three independent places (this file, the MCP `reload`
 * handler's default, and WorkerSpawner's fallback, back when WorkerSpawner existed — item A11
 * later deleted it along with the Worker child process), so `reload` attached to "whatever listens
 * on 5005" with no way to tell whose VM that was. The port is chosen HERE, once, forwarded to the
 * Worker via JUSTSEARCH_DEV_DEBUG_PORT and written into run.json; `reload` reads it from there.
 *
 * `classesDir` is the identity token: WorkerSpawner used to put the same absolute path first on
 * the Worker's classpath (R4), so a pusher could confirm over JDI that the VM it attached to was
 * the one this run launched — instead of trusting a port number.
 *
 * An explicit JUSTSEARCH_DEV_DEBUG_PORT still wins (operator override); otherwise the first free
 * port from 5005 upward is taken, so a second stack cannot silently share the first one's port.
 */
/** The one module whose classes dir goes on the Engine classpath for hot reload (R4). */
const HOTRELOAD_MODULE = 'worker-services';

/** Filesystem timestamp slack, so ordinary granularity is not read as a rebuild. */
const HOTRELOAD_STAMP_SKEW_MS = 2000;

/**
 * `<root>/modules/<module>/build/classes/java/main` — the identity-token layout.
 *
 * Three sides used to agree on this shape: this file writes it into run.json, WorkerSpawner put
 * the same absolute path first on the Worker classpath (`devHotReloadClassesDir`), and the reload
 * tool parses the module back out of it (`reloadModuleFromClassesDir`). Item A11 deleted
 * WorkerSpawner along with the Worker child process, so that middle side no longer exists as
 * described here; what (if anything) re-establishes classpath identity on the current, merged
 * process has not been verified and is not asserted by this comment. A function rather than an
 * inline join so a test can pin it against the parser instead of restating it.
 */
function hotReloadClassesDir(root, module = HOTRELOAD_MODULE) {
  return toPosix(path.join(root, 'modules', module, 'build', 'classes', 'java', 'main'));
}

/** Newest `.class` mtime under `dir`, or null when there is no class file at all. */
function newestClassMtimeMs(dir) {
  let newest = null;
  const walk = (d) => {
    let entries;
    try {
      entries = fs.readdirSync(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) {
        walk(p);
      } else if (e.name.endsWith('.class')) {
        try {
          const { mtimeMs } = fs.statSync(p);
          if (newest === null || mtimeMs > newest) newest = mtimeMs;
        } catch { /* raced away — it cannot be the newest thing we rely on */ }
      }
    }
  };
  walk(dir);
  return newest;
}

/**
 * Tempdoc 844 M3 — may the hot-reload classes dir go FIRST on the Engine's classpath?
 *
 * R4 prefixes `modules/worker-services/build/classes/java/main` so the pushed bytecode and the
 * classes loaded later come from one tree. That is only true when the classes dir and the
 * installDist jars are the SAME build. With `--skip-build` (passed on 124 of 179 measured starts)
 * installDist does not run, so the two are independently aged: worker-services from build B,
 * everything else from build A's jars, with nothing comparing them — and `freshness.buildArtifact`
 * derives from the dist stamp alone, so it would still say FRESH.
 *
 * Lane F stage A item A13: the jars compared against are the ENGINE dist's
 * (`modules/ui/build/install/ui/lib`) — the one tree the process is launched from. There is no
 * second (worker) distribution to choose between any more; `worker-services-*.jar` is installed
 * into the Engine dist like every other module jar.
 *
 * The rule: prefix only when the pairing is established.
 *  - the build step ran → both artifacts came out of the one Gradle invocation → prefix;
 *  - `--skip-build` → compare the newest class file against the installed `worker-services-*.jar`.
 *    Classes no newer than the jar means the jar contains them (the jar is built FROM them), so
 *    the pair is consistent → prefix. Classes NEWER, or either side unreadable, is exactly the
 *    "I cannot verify this" case: hot reload is turned off for the run and run.json records why,
 *    which restores the pre-844 guarantee (a self-consistent, possibly stale, jar set) instead of
 *    a silent mixture.
 */
function assessHotReloadClasspath({ classesDir, buildRan, libDir }) {
  if (buildRan) {
    return { ok: true, verdict: 'BUILD_RAN', reason: null };
  }
  const newestClass = newestClassMtimeMs(classesDir);
  if (newestClass === null) {
    return {
      ok: false,
      verdict: 'CLASSES_DIR_EMPTY',
      reason: `${toPosix(classesDir)} holds no .class files, so there is nothing to put on the `
        + 'classpath and nothing for reload to push. Start without --skip-build.',
    };
  }
  let jarName = null;
  let jarMtime = null;
  try {
    jarName = fs.readdirSync(libDir)
      .find((f) => f.startsWith(`${HOTRELOAD_MODULE}-`) && f.endsWith('.jar')) || null;
    if (jarName) jarMtime = fs.statSync(path.join(libDir, jarName)).mtimeMs;
  } catch { /* the dist is not readable — handled as the unknown it is, just below */ }
  if (jarMtime === null) {
    return {
      ok: false,
      verdict: 'DIST_JAR_UNREADABLE',
      reason: `no ${HOTRELOAD_MODULE}-*.jar could be read under ${toPosix(libDir)}, so the classes `
        + 'dir cannot be shown to match the jars the Engine launches from. Start without '
        + '--skip-build.',
    };
  }
  if (newestClass > jarMtime + HOTRELOAD_STAMP_SKEW_MS) {
    return {
      ok: false,
      verdict: 'CLASSES_NEWER_THAN_DIST',
      reason: `${toPosix(classesDir)} was compiled after ${jarName} was installed `
        + `(${new Date(newestClass).toISOString()} vs ${new Date(jarMtime).toISOString()}), so `
        + `putting it first on the classpath would load ${HOTRELOAD_MODULE} from one build and `
        + 'every other module from another. Start without --skip-build to pair them.',
    };
  }
  return { ok: true, verdict: 'STAMPS_CONSISTENT', reason: null };
}

async function resolveDevHotReload(enabled, { buildRan = true } = {}) {
  if (!enabled) {
    return { enabled: false, requested: false, debugPort: null, classesDir: null };
  }
  const classesDir = hotReloadClassesDir(repoRoot);
  // Tempdoc 844 M3: no classpath prefix without an established pairing, and therefore no hot
  // reload — the prefix IS the mechanism, and offering it unpaired is the silent mixed classpath.
  const classpath = assessHotReloadClasspath({
    classesDir,
    buildRan,
    // Lane F stage A item A13: the Worker distribution is gone, so the jars to pair the classes
    // dir against are the Engine dist's — the same tree this file launches from a few hundred
    // lines below (modules/ui/build/install/ui/bin).
    libDir: path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'lib'),
  });
  if (!classpath.ok) {
    process.stderr.write(
      `[dev-runner] hot reload OFF (${classpath.verdict}): ${classpath.reason}\n`);
    return {
      enabled: false,
      requested: true,
      debugPort: null,
      classesDir: null,
      classpathVerdict: classpath.verdict,
      reason: classpath.reason,
    };
  }
  const override = Number(process.env.JUSTSEARCH_DEV_DEBUG_PORT);
  if (Number.isInteger(override) && override > 0) {
    return { enabled: true, requested: true, debugPort: override, classesDir, portSource: 'env-override' };
  }
  for (let port = 5005; port < 5025; port += 1) {
    // eslint-disable-next-line no-await-in-loop
    if (!(await isTcpListening(port))) {
      return { enabled: true, requested: true, debugPort: port, classesDir, portSource: 'scan' };
    }
  }
  // Tempdoc 844 S4: hot reload is default-ON, and this ran AFTER admission and installDist — with
  // the previous stack already stopped. Failing the whole start because an optional dev
  // convenience has no port is out of proportion: degrade, and say so loudly enough that nobody
  // reads the missing capability as a working one.
  const reason = 'no free JDWP port in 5005-5024 — stop whatever is listening there, or set '
    + 'JUSTSEARCH_DEV_DEBUG_PORT explicitly, and start again to get hot reload.';
  process.stderr.write(`[dev-runner] hot reload OFF (DEBUG_PORT_UNAVAILABLE): ${reason}\n`);
  return {
    enabled: false,
    requested: true,
    debugPort: null,
    classesDir: null,
    classpathVerdict: 'DEBUG_PORT_UNAVAILABLE',
    reason,
  };
}

function checkHttp200(url, timeoutMs, acceptAnyStatus = false) {
  return new Promise((resolve) => {
    const u = new URL(url);
    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port),
        path: u.pathname + u.search,
        method: 'GET',
        timeout: timeoutMs,
      },
      (res) => {
        clearTimeout(deadline);
        resolve(acceptAnyStatus || res.statusCode === 200);
        res.destroy();
      },
    );
    const deadline = setTimeout(() => req.destroy(new Error('deadline')), timeoutMs);
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) => {
      clearTimeout(deadline);
      if (process.env.JUSTSEARCH_DEV_RUNNER_DEBUG) {
        console.error(`[checkHttp200] ${url}: ${err.code || err.message}`);
      }
      resolve(false);
    });
    req.end();
  });
}

function fetchJsonHttp(url, timeoutMs) {
  return new Promise((resolve) => {
    const u = new URL(url);
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      resolve(value);
    };
    const req = http.request(
      { hostname: u.hostname, port: Number(u.port), path: u.pathname + u.search, method: 'GET' },
      (res) => {
        const chunks = [];
        let size = 0;
        res.on('data', (chunk) => {
          size += chunk.length;
          if (size > 1024 * 1024) { finish(null); req.destroy(); return; }
          chunks.push(chunk);
        });
        res.on('error', () => finish(null));
        res.on('aborted', () => finish(null));
        res.on('end', () => {
          if (res.statusCode !== 200) { finish(null); return; }
          try { finish(JSON.parse(Buffer.concat(chunks).toString())); }
          catch { finish(null); }
        });
      },
    );
    const deadline = setTimeout(() => { finish(null); req.destroy(); }, timeoutMs);
    req.on('error', () => finish(null));
    req.end();
  });
}

// The Engine owns essential readiness; the timestamp identifies a continuous READY epoch.
function essentialReadyEpoch(status) {
  const index = status?.readiness?.engineComponents?.index;
  const epoch = index?.stateSince;
  return index?.state === 'READY' && typeof epoch === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(epoch)
    && Number.isFinite(Date.parse(epoch))
    && new Date(epoch).toISOString().slice(0, 19) === epoch.slice(0, 19) ? epoch : null;
}

// Tempdoc 819 §D: mirrors the shell's kill_child() ordered-shutdown request
// (modules/shell/src-tauri/src/lib.rs:218-241) so a dev-runner `stop` gives the Head JVM a
// chance to run its normal shutdown hooks — in particular IndexingLoop's finalizeShutdownCommit
// backstop, which never runs under a bare `taskkill /F` because that kills with no JVM shutdown
// hook. No session-token header is attached: dev-runner-launched backends never set
// JUSTSEARCH_PROD / -Djustsearch.prod (grepped the spawn env block — absent), so
// ResolvedConfigBuilder's `resolveBoolean("justsearch.prod", false)` default leaves prodMode
// false and ApiSecurityFilters.setupSessionTokenEnforcement (ApiSecurityFilters.java:441-468)
// is a no-op — the endpoint is unauthenticated for dev-runner's own backends.
function postLifecycleShutdown(apiPort, timeoutMs) {
  return new Promise((resolve) => {
    const body = Buffer.from('{}', 'utf8');
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port: apiPort,
        path: '/api/lifecycle/shutdown',
        method: 'POST',
        timeout: timeoutMs,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': body.length,
        },
      },
      (res) => {
        res.resume();
        const ok = res.statusCode >= 200 && res.statusCode < 300;
        resolve({ ok, status: res.statusCode, error: ok ? null : `http_${res.statusCode}` });
      },
    );
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, status: null, error: 'timeout' });
    });
    req.on('error', (err) => {
      resolve({ ok: false, status: null, error: err.code || err.message });
    });
    req.write(body);
    req.end();
  });
}

// Tempdoc 819 §D: attempt the graceful ordered shutdown before stopRun's fallback, which ends each
// recorded process by its identity (pid plus birth, re-checked on the handle it terminates), never
// a tree, and then the Engine's job for what is left. Bounded (a 2s POST timeout + a 5s exit-wait,
// polled — never a blind sleep) so a graceful attempt can never make `stop` less reliable than the
// fallback alone: any failure (bad pid/port, connection refused, non-2xx, exit-wait timeout) falls
// through with `outcome` set to something other than 'exited', and the caller's fallback runs.
//
// `markerPath`, if given, is written right before the POST — the one moment a real shutdown
// request is in flight — and left in place for any outcome where the backend genuinely received
// it ('exited' or 'timeout': a 2xx ack means the request landed even if the exit itself is slow).
// The supervisor process's `backend.on('exit')` handler (a DIFFERENT OS process from this one)
// consults the marker's existence to tell "I asked for this" apart from "it crashed on its own",
// and skips writing a racing self-exit stop-report. Deleted immediately when the POST itself never
// reached the backend ('failed') — no request means nothing for the marker to explain. The caller
// (stopRun) removes it unconditionally once it has finished reacting to this function's outcome.
async function maybeGracefulBackendShutdown(apiPort, pid, markerPath = null) {
  if (!Number.isFinite(apiPort) || apiPort <= 0) {
    return { outcome: 'not_attempted', reason: 'no_api_port', requested: false, httpStatus: null, error: null, waitedMs: null };
  }
  if (!Number.isFinite(pid) || pid <= 0) {
    return { outcome: 'not_attempted', reason: 'no_backend_pid', requested: false, httpStatus: null, error: null, waitedMs: null };
  }
  if (!isPidAlive(pid)) {
    return { outcome: 'not_attempted', reason: 'already_dead', requested: false, httpStatus: null, error: null, waitedMs: null };
  }
  if (markerPath) {
    try { await writeJsonAtomic(markerPath, { requestedAt: nowIso(), apiPort, pid }); } catch (_) { /* best-effort */ }
  }
  const startedAt = Date.now();
  const post = await postLifecycleShutdown(apiPort, 2000);
  if (!post.ok) {
    // The request never reached (or was refused by) the backend — nothing for the marker to
    // suppress, and leaving it would wrongly blind a genuine future self-exit report for this run.
    if (markerPath) { try { await fsp.rm(markerPath, { force: true }); } catch (_) { /* best-effort */ } }
    return { outcome: 'failed', reason: null, requested: true, httpStatus: post.status, error: post.error, waitedMs: Date.now() - startedAt };
  }
  // Measured on this machine (tempdoc 819 §D live verification, taken while the Worker was still a
  // child process): Head acks the POST with 202 immediately, then runs its ordered close on a
  // daemon thread — manifest, API server, health monitor, HeadAssembly, then
  // knowledgeServer.closeForUpgrade(). Since lane F stage A item A11 that last step is an ordered
  // close of the IN-PROCESS worker host (`workerHost.close()`), not a child-process termination, so
  // GRACEFUL is the only outcome it can report. The "shutdown signal received" line landed ~5.5s
  // after the POST and the JVM exited shortly after, so a 5s budget reported `timeout` and
  // force-killed a JVM that was mid-clean-shutdown — destroying the
  // finalizeShutdownCommit() stamp this path exists to
  // preserve. The shell's 8s (lib.rs:149) would have cleared it with almost no margin; 15s keeps
  // the same bounded-poll contract with room for a slower machine. Cost is paid only when the
  // backend genuinely hangs, and the taskkill fallback is unchanged.
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (!isPidAlive(pid)) {
      return { outcome: 'exited', reason: null, requested: true, httpStatus: post.status, error: null, waitedMs: Date.now() - startedAt };
    }
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 200));
  }
  return { outcome: 'timeout', reason: null, requested: true, httpStatus: post.status, error: 'pid_still_alive_after_wait', waitedMs: Date.now() - startedAt };
}

function resolveExpectedIndexBasePath(dataDir) {
  const settingsPath = path.join(dataDir, 'ui', 'settings.json');
  try {
    const settings = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    if (settings.indexBasePath && typeof settings.indexBasePath === 'string') {
      return { path: path.resolve(settings.indexBasePath), evidence: 'settings_file' };
    }
  } catch { /* no settings file or unreadable */ }
  return { path: path.resolve(dataDir, 'index', 'default'), evidence: 'derived_default' };
}

async function fetchConfirmedIndexBasePath(apiPort) {
  const base = `http://127.0.0.1:${apiPort}`;
  const config = await fetchJsonHttp(`${base}/api/debug/effective-config`, 3000);
  if (config?.keys) {
    const entry = config.keys.find((k) => k.key === 'justsearch.index.base_path');
    if (entry?.value) return { path: entry.value, evidence: 'effective_config', source: entry.source ?? null };
  }
  const status = await fetchJsonHttp(`${base}/api/status`, 3000);
  if (status?.indexBasePath) return { path: status.indexBasePath, evidence: 'status_endpoint' };
  return null;
}

// The startup gate (the run loop's T3 probe, `io.probeHealth` in cmdStart: every 500 ms, 1.2 s
// per probe) means the Engine answers valid HTTP on `/api/health`, including 503;
// index readiness is checked separately for the stability window. A11 deleted the Worker child, so
// nothing "connects" any more; what still lags is the in-process knowledge-server start, which
// HeadlessApp forks asynchronously (`CompletableFuture.supplyAsync(tryStartKnowledgeServer)`,
// HeadlessApp.java:981) so it runs in PARALLEL with API construction. Until that fork drives
// WorkerCapability to READY, `/api/knowledge/*` answers 503 ("Knowledge Server not ready").
// Consumers that hit those endpoints immediately after "stack up" must tolerate that transient
// 503 — see stage-reference-corpus.mjs stageAndVerify's ingest retry (tempdoc 656 §J/§K.5). (The
// MCP dev server exposes a separate worker-ready readiness level for callers that need it.)

function isTcpListening(port, timeoutMs = 400) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let done = false;
    const finish = (v) => {
      if (done) return;
      done = true;
      try {
        sock.destroy();
      } catch (_) { }
      resolve(v);
    };
    sock.setTimeout(timeoutMs);
    sock.once('connect', () => finish(true));
    sock.once('timeout', () => finish(false));
    sock.once('error', () => finish(false));
    sock.connect(port, '127.0.0.1');
  });
}

function execPowerShell(command) {
  return new Promise((resolve) => {
    execFile(
      'powershell',
      ['-NoProfile', '-Command', command],
      { windowsHide: true, maxBuffer: 1024 * 1024 },
      (err, stdout, stderr) => {
        if (err) return resolve({ ok: false, stdout: stdout || '', stderr: stderr || String(err) });
        resolve({ ok: true, stdout: stdout || '', stderr: stderr || '' });
      },
    );
  });
}

function validatePort(port) {
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error(`Invalid port: ${port}`);
  }
  return port;
}

function validatePid(pid) {
  if (!Number.isInteger(pid) || pid <= 0) {
    throw new Error(`Invalid PID: ${pid}`);
  }
  return pid;
}


async function getCommandLineWindows(pid) {
  validatePid(pid);
  const cmd =
    `Get-CimInstance Win32_Process -Filter "ProcessId=${pid}" -ErrorAction SilentlyContinue | ` +
    `Select-Object -First 1 -ExpandProperty CommandLine`;
  const res = await execPowerShell(cmd);
  const line = res.ok ? res.stdout.trim() : '';
  return line || null;
}

async function loadRunById(runId) {
  const runPath = path.join(runsRoot, runId, 'run.json');
  const run = await readJsonIfExists(runPath);
  return run ? { run, runPath } : null;
}

async function resolveRunTarget(opts) {
  if (opts.runId) {
    const loaded = await loadRunById(opts.runId);
    if (!loaded) throw Object.assign(new Error(`Run not found: ${opts.runId}`), { noRunRecord: true });
    return loaded;
  }
  const active = await readJsonIfExists(activePath);
  if (!active || !active.runId) throw new NoActiveRunError('No active run (active.json missing).');
  const loaded = await loadRunById(active.runId);
  if (!loaded) throw new Error(`Active run not found: ${active.runId}`);
  return loaded;
}

function spawnLogged(command, args, opts, stdoutStream, stderrStream, splitter) {
  const shellEnabled = !!opts?.shell;
  const argsList = Array.isArray(args) ? args : [];

  const quoteForShell = (value) => {
    const s = String(value ?? '');
    if (process.platform === 'win32') {
      if (s.length === 0) return '""';
      return `"${s.replace(/(["^%&|<>])/g, '^$1')}"`;
    }
    if (s.length === 0) return "''";
    return `'${s.replace(/'/g, `'\\''`)}'`;
  };

  let spawnCommand = command;
  let spawnArgs = argsList;
  // Node DEP0190 warns when passing args with shell=true. Compose a quoted command line instead.
  if (shellEnabled && argsList.length > 0) {
    // DO NOT quote the command executable on Windows. Quoting it forces `cmd.exe` to execute it
    // such that `%~dp0` inside batch files (like npm.cmd) resolves to the current working directory
    // instead of the executable's real path.
    spawnCommand = [command, ...argsList.map(quoteForShell)].join(' ');
    spawnArgs = [];
  }

  const linuxRun = process.platform === 'linux' && !shellEnabled
    ? jobRun.startInJob({ command: spawnCommand, args: spawnArgs, cwd: opts.cwd, env: opts.env, stdio: opts.stdio,
      recordDir: opts.recordDir, label: 'dev-child' }) : null;
  const child = linuxRun ? linuxRun.child : spawn(spawnCommand, spawnArgs, opts);
  if (linuxRun) Object.defineProperty(child, 'jobRun', { value: linuxRun });

  const writeChunk = (stream, chunk) => {
    try {
      stream.write(chunk);
    } catch (_) {
      // ignore
    }
  };

  if (child.stdout) {
    child.stdout.on('data', (buf) => {
      if (splitter) splitter('stdout', buf);
      else writeChunk(stdoutStream, buf);
    });
  }
  if (child.stderr) {
    child.stderr.on('data', (buf) => {
      if (splitter) splitter('stderr', buf);
      else writeChunk(stderrStream, buf);
    });
  }
  return child;
}

/**
 * The Engine in a job this runner starts (job-run.cjs): the root is created suspended inside a
 * kill-on-close job with no breakaway, so every process the Engine starts is a member at its
 * creation, including a detached one that Node's own job would let go. The Engine's nested job
 * (WindowsEngineJob) sits inside this one. The returned child is the root as seen through its host
 * (rootAsChild): the root's PID, the host's streams, the root's exit and code. When the root exits
 * the host keeps what is left in the job (holdAfterRoot) until the runner's stop reaches its last
 * step (endEngineJobs), after the registered-helper step and the recorded identities, so nothing a
 * step before it would stop in order is ended first by the job. Off Windows, or for a shell
 * command, the plain spawn.
 */
function spawnEngineInJob(command, args, opts, stdoutStream, stderrStream, splitter,
  { recordDir, label, startupTimeoutMs = 20000, onStartupFailure = () => {} }) {
  if (process.platform !== 'win32' || opts?.shell) return spawnLogged(command, args, { ...opts, recordDir }, stdoutStream, stderrStream, splitter);
  if (!Number.isFinite(startupTimeoutMs) || startupTimeoutMs <= 0) throw new RangeError('the Engine startup budget must be positive and finite');
  const startedAt = performance.now();
  const remainingMs = () => Math.max(0, startupTimeoutMs - (performance.now() - startedAt));
  const run = jobRun.startInJob({ command, args, cwd: opts.cwd, env: opts.env, stdio: opts.stdio, label, recordDir,
    holdAfterRoot: true, capture: child => engineActuator.captureSpawnedIdentity(child,
      { timeoutMs: Math.floor(Math.min(5000, remainingMs())) }) });
  const record = jobRun.waitForRootSync(run, { timeoutMs: remainingMs() });
  // The host's identity as it recorded it on its own handle: what a stop's job step checks first.
  const latest = record ?? jobRun.readRunRecord(run);
  const sameHost = Number(latest?.pid) === run.child.pid && /^[1-9]\d{0,19}$/.test(String(latest.creationFileTimeUtc ?? ''))
    && (!run.host || (run.host.pid === Number(latest.pid) && run.host.creationFileTimeUtc === String(latest.creationFileTimeUtc)));
  if (sameHost) {
    run.host = { pid: Number(latest.pid), creationFileTimeUtc: String(latest.creationFileTimeUtc) };
  }
  if (!record || !sameHost || remainingMs() <= 0 || !Number.isSafeInteger(record.root?.pid) || record.root.pid <= 0
      || !/^[1-9]\d{0,19}$/.test(String(record.root.creationFileTimeUtc ?? ''))) {
    // No facade with an unknown PID: discovery and the exit ledger require this incarnation's
    // root. Only the host's captured/recorded birth grants cleanup authority. If it cannot be
    // verified, retain the job in the current run for an explicit later stop to account for.
    const cleanup = jobRun.stopJobRunSync(run);
    const err = new Error(`the Engine job host (pid ${run.child.pid}) did not publish its root within the ${startupTimeoutMs}ms startup budget`);
    err.code = 'ENGINE_ROOT_UNPUBLISHED';
    err.details = { recordPath: run.recordPath, hostPid: run.child.pid, host: run.host, startupTimeoutMs, cleanup };
    try { onStartupFailure(err.details); }
    catch (writeError) { err.details.failureRecordError = writeError?.message ?? String(writeError); }
    throw err;
  }
  const child = jobRun.rootAsChild(run, record, { spawnfile: command });
  child.stdout?.on('data', (buf) => splitter('stdout', buf));
  child.stderr?.on('data', (buf) => splitter('stderr', buf));
  return child;
}

/** A failed spawn still belongs to this run, including a host whose birth was not readable. */
function persistEngineStartupFailure(runPath, run, incarnation, details) {
  run.engineJob = { incarnation, recordPath: details.recordPath,
    host: details.host ?? { pid: details.hostPid, creationFileTimeUtc: null },
    startupFailure: { code: 'ENGINE_ROOT_UNPUBLISHED', startupTimeoutMs: details.startupTimeoutMs, cleanup: details.cleanup } };
  const pending = `${runPath}.engine-start-${process.pid}.tmp`;
  fs.writeFileSync(pending, JSON.stringify(run, null, 2) + '\n');
  fs.renameSync(pending, runPath);
}

const EARLIER_REPORT_RE = /^stop-report\.(\d+)\.json$/;

/** How long keeping an earlier report retries a rename another process's open handle refuses. */
const REPORT_ARCHIVE_RETRY_MS = 2000;
const REPORT_ARCHIVE_PAUSE_MS = 50;

/**
 * A retried stop never overwrites an earlier report of its run: the current stop-report.json is
 * kept, as it was, as stop-report.N.json under the next free number. A rename another process's
 * handle refuses for a moment (Windows EBUSY, EPERM, EACCES: a reader holding the file without
 * delete sharing) is tried again for up to REPORT_ARCHIVE_RETRY_MS. If the earlier report still
 * cannot be renamed it stays as stop-report.json, untouched, and this report is written under the
 * next free number instead (`reportName`, with `earlierReportNotArchived` saying why). Never throws.
 * `earlierReports` is every earlier report kept, archived or not (run-directory file names: the
 * numbered ones, oldest number first, then a stop-report.json that stayed where it was);
 * `earlierReportFiles` gives each with whether it was archived and can be read. `earlierFindings` is
 * what those reports found that no later stop clears, for exitCensus.carryFindings: each readable
 * report's accounting gaps, and an `earlier-report-unreadable` gap for each report that cannot be read.
 * @returns {Promise<{ earlierReports: string[], earlierReportFiles: Array<{ name: string, archived: boolean,
 *   readable: boolean }>, earlierFindings: Array<{ gap: object, from: string }>, reportName: string,
 *   earlierReportNotArchived?: { file: string, error: string } }>}
 */
async function keepEarlierReports(runDir, { pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  retryMs = REPORT_ARCHIVE_RETRY_MS, now = Date.now } = {}) {
  const list = () => { try { return fs.readdirSync(runDir); } catch { return []; } };
  const numbered = (names) => names.filter((n) => EARLIER_REPORT_RE.test(n))
    .sort((a, b) => Number(EARLIER_REPORT_RE.exec(a)[1]) - Number(EARLIER_REPORT_RE.exec(b)[1]));
  const nextName = (names) => `stop-report.${Math.max(0, ...numbered(names).map((n) => Number(EARLIER_REPORT_RE.exec(n)[1]))) + 1}.json`;
  let names = list();
  let notArchived = null;
  if (names.includes('stop-report.json')) {
    const by = now() + retryMs;
    for (;;) {
      try {
        fs.renameSync(path.join(runDir, 'stop-report.json'), path.join(runDir, nextName(names)));
        break;
      } catch (err) {
        if (err?.code === 'ENOENT') break; // nothing left to keep
        if (!['EBUSY', 'EPERM', 'EACCES'].includes(err?.code) || now() >= by) {
          notArchived = { file: 'stop-report.json', error: `${err?.code ?? 'error'}: ${err?.message ?? err}` };
          break;
        }
        await pause(REPORT_ARCHIVE_PAUSE_MS);
        names = list();
      }
    }
    names = list();
  }
  const kept = [...numbered(names).map((name) => ({ name, archived: true })),
    ...(notArchived && names.includes('stop-report.json') ? [{ name: 'stop-report.json', archived: false }] : [])];
  const earlierFindings = [];
  const earlierReportFiles = kept.map(({ name, archived }) => {
    let readable = true;
    try {
      const report = JSON.parse(fs.readFileSync(path.join(runDir, name), 'utf8').replace(/^\uFEFF/, ''));
      // A file that parses to anything but a report (null, a list, a number) says nothing of what that
      // stop found: it is read no better than one that does not parse.
      if (!report || typeof report !== 'object' || Array.isArray(report)) {
        throw Object.assign(new Error('not a stop report'), { code: 'NOT_A_REPORT' });
      }
      for (const gap of Array.isArray(report.exitAccountingGaps) ? report.exitAccountingGaps : []) {
        earlierFindings.push({ gap, from: name });
      }
    } catch (err) {
      readable = false;
      // What that stop found cannot be read, so it cannot be carried: the report itself is the gap.
      earlierFindings.push({ from: name, gap: { reason: 'earlier-report-unreadable', file: name, archived,
        detail: `${name} could not be read (${err?.code ?? err?.name ?? 'error'}); what that stop found is not accounted` } });
    }
    return { name, archived, readable };
  });
  const earlierReports = kept.map((k) => k.name);
  if (!notArchived) return { earlierReports, earlierReportFiles, earlierFindings, reportName: 'stop-report.json' };
  return { earlierReports, earlierReportFiles, earlierFindings, reportName: nextName(names), earlierReportNotArchived: notArchived };
}

/**
 * The identities (PID and birth) the earlier stops of a run name in what they found: each stop
 * report's accounting gaps and each census file's findings, as they were written, so a later stop
 * looks each up. Read only, before this stop's census keeps its earlier files; a file that cannot be
 * read names none (keepEarlierReports and the census make it a gap).
 */
const EARLIER_CENSUS_FILE_RE = /^process-exits(?:\.\d+)?\.json$/;
function earlierReportIdentities(runDir) {
  let names = [];
  try {
    names = fs.readdirSync(runDir).filter((n) => n === 'stop-report.json' || EARLIER_REPORT_RE.test(n) || EARLIER_CENSUS_FILE_RE.test(n));
  } catch { return []; }
  const identities = [];
  for (const name of names) {
    try {
      const report = JSON.parse(fs.readFileSync(path.join(runDir, name), 'utf8').replace(/^\uFEFF/, ''));
      const found = EARLIER_CENSUS_FILE_RE.test(name) ? report?.findings : report?.exitAccountingGaps;
      for (const g of Array.isArray(found) ? found : []) {
        if (!Number.isInteger(g?.pid) || g.pid <= 0 || (typeof g.creationFileTimeUtc !== 'string' && !processIdentity.linuxIdentity(g))) continue;
        identities.push({ pid: g.pid, creationFileTimeUtc: g.creationFileTimeUtc, ...(g.processIdentity ? { processIdentity: g.processIdentity } : {}), role: g.role ?? null,
          ...(g.birthTick != null ? { birthTick: String(g.birthTick) } : {}), ...(g.childId ? { childId: g.childId } : {}) });
      }
    } catch { /* named as unreadable by keepEarlierReports */ }
  }
  return identities;
}

/** A recorded identity the lookup could not read, resolved by its exit in the ledger. */
function describeResolved(p) {
  return { pid: Number(p.pid), creationFileTimeUtc: p.processIdentity ? null : String(p.creationFileTimeUtc), ...(p.processIdentity ? { processIdentity: p.processIdentity } : {}), role: p.role ?? null,
    observedCreationFileTimeUtc: p.observedCreationFileTimeUtc ?? null, basis: 'exit ledger' };
}

/** The job record's identities in the shape stop reports use. */
function jobIdentities(list) {
  return (Array.isArray(list) ? list : []).filter((p) => p?.pid)
    .map((p) => ({ pid: Number(p.pid), ...(p.processIdentity ? { processIdentity: p.processIdentity, creationFileTimeUtc: null }
      : { creationFileTimeUtc: String(p.creationFileTimeUtc) }) }));
}

const lockPath = path.join(stateRoot, 'active.lock.json');

/**
 * Tempdoc 542 §B Layer 2: read op-leases.json (Head's lease registry) and return ACTIVE entries
 * — i.e., entries whose expiresAt is in the future. The Head-side OperationLeaseService writes
 * this file; stale entries are also reaped Head-side on every write, but the admission gate
 * runs its own expiry filter as a belt-and-suspenders against time skew or crashed Head.
 *
 * Returns: { entries: [...], byCriticality: { mustComplete: [...], unsafeToInterrupt: [...] } }.
 */
async function readActiveOpLeases(overridePath = null) {
  const doc = await readJsonIfExists(overridePath ?? opLeasesPath);
  if (!doc || !Array.isArray(doc.opLeases)) {
    return { entries: [], byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] } };
  }
  const now = Date.now();
  const active = doc.opLeases.filter((e) => {
    if (!e?.expiresAt) return false;
    const t = new Date(e.expiresAt).getTime();
    return Number.isFinite(t) && t > now;
  });
  const byCriticality = {
    mustComplete: active.filter((e) => e.criticality === 'MUST_COMPLETE'),
    unsafeToInterrupt: active.filter((e) => e.criticality === 'UNSAFE_TO_INTERRUPT'),
    interruptibleWithLoss: active.filter((e) => e.criticality === 'INTERRUPTIBLE_WITH_LOSS'),
  };
  return { entries: active, byCriticality };
}

/** A lock held this long by a live process is reported as slow; it is never stolen for its age. */
const ADMISSION_LOCK_SLOW_MS = 120_000;

/**
 * Spec F §5.3 (X09-F2) item 4: the liveness of an admission lock's holder. `ended` only when the
 * holder's pid and creation time (from the lock payload) are gone from a fresh process table, or,
 * for a payload without a creation time, when its pid is absent. A lookup failure is `unknown`,
 * which never frees the lock.
 */
function admissionLockHolderLiveness(lock, {
  table = () => readFreshProcessTable(),
  pidPresence = (owner) => ownerPresence.ownerPidPresence(owner),
} = {}) {
  const pid = Number(lock?.pid);
  if (!Number.isInteger(pid) || pid <= 0) return 'unknown';
  if (!lock.creationFileTimeUtc && !lock.processIdentity) {
    return pidPresence({ pid }) === 'absent' ? 'ended' : 'unknown';
  }
  let rows;
  try { rows = table(); } catch { return 'unknown'; }
  return ownerPresence.ownerLiveness({ key: 'admission-lock', pid, creationTime: lock.creationFileTimeUtc, ...(lock.processIdentity ? { processIdentity: lock.processIdentity } : {}) },
    { table: rows }).state;
}

/**
 * The start admission. `publish`, when given, runs INSIDE the lock once the start may proceed: the
 * starting run.json and active.json are written there, so a second start meets this one's
 * ownership instead of racing it (Spec F §5.3, X09-F2). `runnerIdentity` is this process's
 * `{pid, creationFileTimeUtc}`, written into the lock payload.
 */
async function acquireAdmission({
  takeover = 'deny', sessionId, confirmInterrupt = null, callerIdentity = null,
  publish = null, runnerIdentity = null, stop = stopRun,
  lookupIdentities = engineActuator.lookupProcessIdentities,
} = {}) {
  const caller = callerIdentity ?? resolveCallerIdentity(sessionId);
  // Step 1: Acquire sidecar lockfile with exclusive create (271 A3.3)
  const lockPayload = JSON.stringify({
    pid: process.pid,
    creationFileTimeUtc: runnerIdentity?.creationFileTimeUtc ?? null,
    ...(runnerIdentity?.processIdentity ? { processIdentity: runnerIdentity.processIdentity } : {}),
    acquiredAt: nowIso(),
  });
  try {
    await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
  } catch (err) {
    if (err.code !== 'EEXIST') throw err;
    // Lock exists — check if holder is alive
    let existingLock;
    try {
      existingLock = JSON.parse(await fsp.readFile(lockPath, 'utf8'));
    } catch {
      // Unreadable lock — remove and retry once
      await fsp.rm(lockPath, { force: true });
      await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
      existingLock = null;
    }
    if (existingLock) {
      // The lock is held only for the admission itself. It is stale ONLY when its holder has
      // ended; an alive or unknown holder keeps it whatever its age (a slow one is reported).
      const holderState = admissionLockHolderLiveness(existingLock);
      if (holderState !== 'ended') {
        const lockAgeMs = existingLock.acquiredAt
          ? Date.now() - new Date(existingLock.acquiredAt).getTime()
          : Infinity;
        const slow = holderState === 'alive' && lockAgeMs >= ADMISSION_LOCK_SLOW_MS;
        return { action: 'conflict', reason: slow ? 'lock_holder_slow' : 'lock_held', holder: existingLock };
      }
      // Ended holder — remove lock and re-acquire
      await fsp.rm(lockPath, { force: true });
      await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
    }
  }

  // We now hold the admission lock. Everything below must be in try/finally.
  try {
    const forgottenRuns = [];
    for (const id of await fsp.readdir(runsRoot).catch(() => [])) {
      const marker = await readJsonIfExists(path.join(runsRoot, id, 'forgotten-unresolved.json'));
      if (!marker) continue;
      const record = await readJsonIfExists(path.join(runsRoot, id, 'run.json'));
      const entries = marker.leftBehind ?? recordedProcesses(record ?? {}, NaN).filter(p => p.pid);
      forgottenRuns.push({ runId: id, entries });
    }
    const ids = [...new Set(forgottenRuns.flatMap(r => r.entries.map(p => p.pid)))];
    const observed = ids.length ? await Promise.resolve().then(() => lookupIdentities(ids)).catch(() => null) : new Map();
    const unresolved = forgottenRuns.flatMap(r => r.entries.map(p => ({ ...p, runId: r.runId,
      state: checkRecordedProcess(p, observed?.get(p.pid) ?? (observed ? { present: false } : null)) })))
      .filter(p => p.state === 'match' || p.state === 'unknown');
    if (unresolved.length) {
      return { action: 'conflict', reason: 'forgotten_processes_remain',
        unresolvedProcesses: unresolved.map(p => describeUnresolved(p, p.state)),
        message: `Forgetting leaves processes running; a person decides. Forgotten processes must be ended or inspected first: ${unresolved.map(p =>
          `PID ${p.pid} (${p.name ?? p.role}), start ${p.startedAt ?? p.creationFileTimeUtc ?? 'unknown'}`).join('; ')}. No start or data cleanup was performed; there is no override.` };
    }
    let active = await readJsonIfExists(activePath);
    if (active?.runId && fs.existsSync(path.join(runsRoot, active.runId, 'forgotten-unresolved.json'))) {
      await fsp.rm(activePath, { force: true });
      active = null;
    }
    // Tempdoc 606 3a: the ownership epoch, read inside the lock (Spec F §5.3 item 1).
    const ownershipEpoch = (Number(active?.ownershipEpoch) || 0) + 1;

    // Gather facts for the single ownership-verdict authority (tempdoc 606).
    const leaseExpired = active?.lease?.expiresAt
      ? new Date(active.lease.expiresAt) < new Date()
      : true; // No lease → treat as stale (pre-271 format)
    // An unreadable run record behind an unexpired lease is an unknown supervisor: alive, not dead.
    const runJson = active?.runId ? readActiveRun(active) : null;
    const supervisorAlive = ownerPresence.supervisorAliveFrom({ runJson, leaseExpired, pidAlive: isPidAlive });
    // Tempdoc 542 §B Layer 4: op-lease registry (criticality-aware dispatch).
    const opLeases = await readActiveOpLeases();

    // The ONE decision, over owner presence (liveness of the holder's harness process + its
    // dev-stack touches). selfCheck:false on the gate: the gate applies takeover policy and the
    // critical-op protections regardless of caller, as before 606. The one exception is the
    // owner restarting its own stack (keys equal): that is not a takeover and is not asked as
    // one, unless a critical op or a dead stack decides otherwise.
    const verdictFacts = {
      active, callerIdentity: caller, selfCheck: false, supervisorAlive, leaseExpired,
      opLeases, takeover, confirmInterrupt,
    };
    let { decision } = ownerVerdictFor(verdictFacts);
    const ownRun = active?.runId && ownerPresence.isSelf(active, caller);
    // `deny` refuses to reclaim another owner's stale stack. Reclaiming one's own is not a
    // takeover, so it keeps the disposition an explicit policy would record.
    if (ownRun && decision.reason === 'takeover_denied') {
      ({ decision } = ownerVerdictFor({ ...verdictFacts, takeover: 'warn' }));
    }
    if (ownRun && !['WAIT_CRITICAL_OP', 'REQUIRES_CONFIRMATION', 'RECLAIM_DEAD'].includes(decision.verdict)) {
      decision = {
        action: 'proceed',
        verdict: 'USE',
        grade: 'self',
        disposition: 'owner_restart',
        victim: { runId: active.runId, holder: active.holder ?? null },
        recommendedAction: 'You own this stack - restarting it.',
      };
    }

    if (decision.action === 'conflict') {
      process.stderr.write(
        `[dev-runner] Admission conflict (${decision.verdict}/${decision.reason}) for run ${active?.runId}\n`);
      return {
        action: 'conflict',
        reason: decision.reason,
        verdict: decision.verdict,
        holder: active?.holder ?? null,
        lease: active?.lease ?? null,
        runId: active?.runId ?? null,
        ...(decision.criticalOps ? { criticalOps: decision.criticalOps } : {}),
        ...(decision.message ? { message: decision.message } : {}),
        resourceClaims: runJson?.resourceClaims ?? null,
        recommendedAction: decision.recommendedAction,
      };
    }

    // proceed
    if (decision.disposition) {
      process.stderr.write(
        `[dev-runner] Admission proceed (${decision.verdict}/${decision.disposition}) ` +
        `over run ${active?.runId} owned by ${active?.holder?.source ?? 'unknown'}\n`);
      // Spec F §5.3 (X09-F5): the predecessor's stop must have ended its stack. A stop that could
      // not verify that refuses the start; the new stack never runs beside a live old one.
      const predecessorStop = await stop({
        runId: active.runId,
        disposition: decision.disposition,
        actor: holderBlockFor(caller),
        victim: decision.victim,
        ...(decision.criticalOpsInterrupted ? { criticalOpsInterrupted: decision.criticalOpsInterrupted } : {}),
        ...(decision.interruptibleWithLossInterrupted
          ? { interruptibleWithLossInterrupted: decision.interruptibleWithLossInterrupted } : {}),
      }).catch((err) => (err?.noRunRecord
        // No run record: no process is recorded that a stop could act on or verify, and the gate
        // already found no sign of life (base behaviour: the start proceeds over it).
        ? { ok: true, outcome: 'NO_RUN_RECORD', errors: [String(err.message)] }
        : { ok: false, outcome: 'STOP_FAILED', errors: [String(err?.message ?? err)] }));
      if (predecessorStop?.outcome !== STOP_OUTCOME.STOPPED && predecessorStop?.outcome !== 'NO_RUN_RECORD') {
        process.stderr.write(
          `[dev-runner] Takeover refused: the stop of run ${active.runId} did not end it `
          + `(${predecessorStop?.outcome ?? 'no result'}).\n`);
        return {
          action: 'conflict',
          reason: 'takeover_stop_failed',
          verdict: decision.verdict,
          holder: active?.holder ?? null,
          lease: active?.lease ?? null,
          runId: active?.runId ?? null,
          stop: predecessorStop ?? null,
          resourceClaims: runJson?.resourceClaims ?? null,
          recommendedAction: 'The previous stack is still (partly) running; inspect the stop result before retrying.',
        };
      }
    }
    if (publish) await publish({ ownershipEpoch });
    return {
      action: 'proceed',
      ownershipEpoch,
      verdict: decision.verdict,
      ...(decision.disposition ? { disposition: decision.disposition } : {}),
      ...(decision.victim ? { victim: decision.victim } : {}),
      ...(decision.criticalOpsInterrupted ? { criticalOpsInterrupted: decision.criticalOpsInterrupted } : {}),
      ...(decision.interruptibleWithLossInterrupted
        ? { interruptibleWithLossInterrupted: decision.interruptibleWithLossInterrupted } : {}),
    };
  } finally {
    await fsp.rm(lockPath, { force: true }).catch(() => {});
  }
}

/**
 * This process's OS identity `{pid, creationFileTimeUtc, executable}` (Spec F §5.3): recorded in the
 * admission lock, run.json and active.json so a stop can verify the runner before acting on it. A
 * lookup failure leaves the creation time null (unknown), never invented.
 */
async function selfProcessIdentity({ lookup = engineActuator.lookupProcessIdentities } = {}) {
  const seen = (await lookup([process.pid]).catch(() => null))?.get(process.pid);
  return {
    pid: process.pid,
    creationFileTimeUtc: seen?.present ? seen.creationFileTime ?? null : null,
    ...(seen?.processIdentity ? { processIdentity: seen.processIdentity } : {}),
    executable: seen?.present ? seen.executable ?? process.execPath : process.execPath,
  };
}

/** The lease block every active.json write carries (tempdoc 735 G6: the same declared duration). */
function leaseBlock(durationSec, sequence, now = new Date()) {
  return {
    durationSec,
    renewedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + durationSec * 1000).toISOString(),
    sequence,
  };
}

/** Remove a failed start's own records: run.json, and active.json only while it still names this run. */
async function removeOwnStartRecords(runId, runPath) {
  await fsp.rm(runPath, { force: true }).catch(() => {});
  const active = await readJsonIfExists(activePath).catch(() => null);
  if (active?.runId === runId) await fsp.rm(activePath, { force: true }).catch(() => {});
}

async function cmdStart(opts) {
  const callerIdentity = opts.callerIdentity ?? resolveCallerIdentity(opts.sessionId);
  const runId = crypto.randomUUID();
  const startedAt = nowIso();
  const uiPort = opts.uiPort;
  const apiPortRequested = opts.apiPort;
  const dataDir = resolveDataDir(opts.dataDir);
  // Before admission: a start that would clean a folder that is not a dev tool's own is refused
  // before anything is stopped, started or changed.
  assertCleanableDataDir(dataDir, opts.clean);
  const runDir = path.join(runsRoot, runId);
  const logsDir = path.join(runDir, 'logs');
  const runPath = path.join(runDir, 'run.json');
  const runnerIdentity = await selfProcessIdentity();

  // Spec F §5.3 (X09-F2): the run is published while it starts — run.json naming the runner, then
  // active.json in phase `starting` — INSIDE the admission lock, so a second start meets this one's
  // ownership instead of racing it. The full record replaces both at the first ready.
  const startingRun = {
    schemaVersion: 1,
    runId,
    startedAt,
    phase: 'starting',
    apiPortRequested,
    apiPortActual: null,
    uiPortRequested: uiPort,
    uiPortActual: uiPort,
    dataDir: toPosix(dataDir),
    repoRoot: toPosix(repoRoot),
    // The runner answers `<runDir>/control/stop-request.json`; a stop asks it before killing anything.
    control: { stopRequest: 1 },
    pids: { runnerPid: process.pid, backendRootPid: null, frontendRootPid: null },
    identities: { runner: runnerIdentity, backend: null, frontend: null },
    logs: {
      backendStdout: toPosix(path.relative(repoRoot, path.join(logsDir, 'backend.stdout.log'))),
      backendStderr: toPosix(path.relative(repoRoot, path.join(logsDir, 'backend.stderr.log'))),
      frontendStdout: toPosix(path.relative(repoRoot, path.join(logsDir, 'frontend.stdout.log'))),
      frontendStderr: toPosix(path.relative(repoRoot, path.join(logsDir, 'frontend.stderr.log'))),
    },
    owner: holderBlockFor(callerIdentity),
    cleanupPolicy: 'shared-stack',
  };
  const lease = { sequence: 1 };

  // Lease-aware admission: check ownership before starting (271, extended by 542 section B Layer 4).
  // This is also the clean gate: a start that would clean another owner's data dir is refused
  // here, before anything is stopped or cleaned, unless the caller takes over explicitly.
  const admission = await acquireAdmission({
    takeover: opts.takeover,
    sessionId: opts.sessionId,
    confirmInterrupt: opts.confirmInterrupt,
    callerIdentity,
    runnerIdentity,
    publish: async ({ ownershipEpoch }) => {
      await mkdirp(logsDir);
      await writeJsonAtomic(runPath, startingRun);
      const now = new Date();
      await writeJsonAtomic(activePath, {
        kind: 'backend-shared-lease.v1',
        schemaVersion: 1,
        runId,
        runPath: toPosix(path.relative(mainRepoRoot, runPath)),
        launcherFamily: 'dev-runner',
        mode: 'shared',
        holder: holderBlockFor(callerIdentity),
        takeoverPolicy: 'warn',
        ownershipEpoch,
        phase: 'starting',
        runner: { pid: runnerIdentity.pid, creationFileTimeUtc: runnerIdentity.creationFileTimeUtc,
          ...(runnerIdentity.processIdentity ? { processIdentity: runnerIdentity.processIdentity } : {}) },
        lease: leaseBlock(opts.leaseDurationSec, lease.sequence, now),
        updatedAt: now.toISOString(),
      });
    },
  });
  if (admission.action === 'conflict') {
    // Tempdoc 542 §B Layer 4: criticality-aware error codes.
    //   handshake_required        — MUST_COMPLETE or UNSAFE_TO_INTERRUPT op-lease blocks `warn`
    //   requires_confirmation     — `force` against UNSAFE_TO_INTERRUPT without --confirm-interrupt
    //   takeover_stop_failed      — the predecessor's stop did not end it (Spec F §5.3, X09-F5)
    //   fresh_owner / lock_held   — routine OWNER_CONFLICT (existing semantics)
    const code = admission.reason === 'handshake_required'
        ? 'HANDSHAKE_REQUIRED'
        : admission.reason === 'requires_confirmation'
            ? 'REQUIRES_CONFIRMATION'
            : admission.reason === 'takeover_stop_failed'
                ? 'TAKEOVER_STOP_FAILED'
                : 'OWNER_CONFLICT';
    // The owner restarting its own stack is refused only by an operation in flight in it (the gate
    // excludes WAIT_CRITICAL_OP / REQUIRES_CONFIRMATION from owner_restart). Say so: the stack is
    // the caller's own, so "owned by <someone>; ask the user" would send it to the wrong remedy.
    const ownStack = ownerPresence.isSelf({ holder: admission.holder }, callerIdentity);
    let message;
    if (admission.reason === 'forgotten_processes_remain') {
      message = admission.message;
    } else if (admission.reason === 'takeover_stop_failed') {
      message = `Takeover refused: the stop of run ${admission.runId} did not end it; nothing was started. `
        + (admission.stop?.nextStep ?? forgetGuidance(admission.runId));
    } else if (ownStack) {
      message = ownerPresence.ownOpInFlightText(admission.criticalOps,
        admission.reason === 'handshake_required' ? 'warn'
          : admission.reason === 'requires_confirmation' ? 'confirm' : 'refused');
    } else if (admission.reason === 'handshake_required') {
      message = admission.message
          ?? `Backend has ${admission.criticalOps?.length ?? 0} critical op-lease(s) active`;
    } else if (admission.reason === 'requires_confirmation') {
      message = admission.message ?? 'force-interrupt requires --confirm-interrupt=<opId>';
    } else {
      message = `Backend owned by ${describeHolder(admission.holder)}` +
          (admission.reason === 'lock_held'
            ? ' (admission lock held by another process)'
            : admission.reason === 'lock_holder_slow'
              ? ' (admission lock held by a live process for over 2 minutes; it is not taken from it)'
            : admission.reason === 'idle_owner'
              ? ' (owner idle; --takeover=warn takes over without asking the user)'
            : admission.reason === 'takeover_denied'
              ? ' (stale or abandoned; --takeover=warn reclaims it without asking the user)'
              : ' (fresh lease; ask the user, then use --takeover=warn to override)');
    }
    const conflict = {
      ok: false,
      error: {
        code,
        message,
        holder: admission.holder ?? null,
        lease: admission.lease ?? null,
        runId: admission.runId ?? null,
        resourceClaims: admission.resourceClaims ?? null,
        ...(admission.criticalOps ? { criticalOps: admission.criticalOps } : {}),
        ...(admission.stop ? { stop: admission.stop } : {}),
      },
    };
    if (opts.json) {
      process.stdout.write(JSON.stringify(conflict) + '\n');
    } else {
      process.stderr.write(`[dev-runner] ${conflict.error.message}\n`);
    }
    return;
  }

  const ctx = {
    callerIdentity, admission, ownershipEpoch: admission.ownershipEpoch, runId, startedAt, uiPort,
    apiPortRequested, dataDir, runDir, logsDir, runPath, startingRun, runnerIdentity, lease,
    // Set once the ready-time records are written; from then on a failure is not a failed start.
    settled: false,
    // A stop request taken while starting: its handler ends the process.
    stopHandling: null,
    heartbeat: null,
  };
  // Spec F §5.3 item 3: while starting, the lease is renewed at the running cadence, so a slow
  // start never reads as an expired one.
  ctx.heartbeat = setInterval(() => {
    void (async () => {
      const current = await readJsonIfExists(activePath).catch(() => null);
      if (ctx.settled || current?.runId !== runId || current.phase !== 'starting') return;
      lease.sequence += 1;
      const now = new Date();
      await writeJsonAtomic(activePath, { ...current, lease: leaseBlock(opts.leaseDurationSec, lease.sequence, now),
        updatedAt: now.toISOString() });
    })().catch(() => {});
  }, leaseRenewIntervalMs());
  try {
    await runAdmittedStart(opts, ctx);
  } catch (err) {
    clearInterval(ctx.heartbeat);
    // A stop asked for while starting: answer it first, then report the start as stopped.
    if (ctx.stopHandling) {
      await ctx.stopHandling;
      err.code = 'START_STOPPED';
      err.message = `The start was ended by a stop request (${err.message}).`;
    }
    // The start failed: its run never existed for anyone else, so its own records go.
    if (!ctx.settled) await removeOwnStartRecords(runId, runPath);
    throw err;
  }
}

async function runAdmittedStart(opts, ctx) {
  const {
    callerIdentity, admission, ownershipEpoch, runId, startedAt, uiPort, apiPortRequested, dataDir,
    runDir, logsDir, runPath, startingRun, runnerIdentity, lease,
  } = ctx;
  await pruneHistoricRuns({
    preserveRunIds: [admission.victim?.runId, runId].filter(Boolean),
  }).catch(() => { });

  const backendStdoutPath = path.join(logsDir, 'backend.stdout.log');
  const backendStderrPath = path.join(logsDir, 'backend.stderr.log');
  const frontendStdoutPath = path.join(logsDir, 'frontend.stdout.log');
  const frontendStderrPath = path.join(logsDir, 'frontend.stderr.log');

  const backendStdout = fs.createWriteStream(backendStdoutPath, { flags: 'a' });
  const backendStderr = fs.createWriteStream(backendStderrPath, { flags: 'a' });
  const frontendStdout = fs.createWriteStream(frontendStdoutPath, { flags: 'a' });
  const frontendStderr = fs.createWriteStream(frontendStderrPath, { flags: 'a' });

  // The clean gate is the admission above: a non-owner reaches this line only by taking over.
  await cleanOwnDataDir(dataDir, opts.clean);

  const aiEnv = resolveAiDevEnv();

  // Tempdoc 842 §2.4: ambient operator env ALWAYS wins (same convention as resolveAiDevEnv above);
  // otherwise the CLI-supplied profile applies, and the dev default is "compact" even when the
  // caller omits --chat-profile entirely.
  const effectiveChatProfile = process.env.JUSTSEARCH_CHAT_PROFILE || opts.chatProfile || 'compact';

  // Tempdoc 842 §2.4: not fatal (the stack boots AI-offline anyway), but a missing compact model
  // file silently strands `ai_activate`/`agent_chat` auto-activation later — warn now, once, while
  // the remedy (fetch-compact-model.mjs) is one line away.
  if (effectiveChatProfile === 'compact') {
    const modelsDirForCheck = process.env.JUSTSEARCH_MODELS_DIR || aiEnv.JUSTSEARCH_MODELS_DIR;
    const compactModelPath = modelsDirForCheck
      ? path.join(modelsDirForCheck, 'compact', 'Qwen3.5-4B-Q4_K_M.gguf')
      : null;
    if (!compactModelPath || !fs.existsSync(compactModelPath)) {
      process.stderr.write(
        `[dev-runner] warn: compact chat model not found` +
        `${compactModelPath ? ` at ${compactModelPath}` : ' (no models dir resolved)'} — ` +
        `run: node scripts/dev/fetch-compact-model.mjs\n`,
      );
    }
  }

  // Ensure the distribution that is actually LAUNCHED is up-to-date (S7: bypass Gradle at runtime).
  // Tempdoc 844 F4: this step said "Ensuring distribution is up-to-date" and ran `assemble`, which
  // does NOT run installDist — so a Java edit rebuilt the jars and left
  // modules/ui/build/install/ui (the tree the Head is launched from, a few lines below) untouched.
  // Proven live 2026-08-19: after editing WorkerSpawner.java (deleted since — item A11 removed
  // WorkerSpawner along with the Worker child process it launched), a `start` without skipBuild
  // launched a Worker with the OLD classpath, and an explicit installDist then did real work.
  // The launched artifacts are now built by name. Warm cost measured in this worktree (config
  // cache reused):
  // assemble alone 891/957/923 ms, assemble + installDist 1055/1156 ms - about +0.15 s, once
  // per start, to make the message true.
  // Lane F stage A item A13: that measurement covered TWO installDist tasks, because the Worker
  // shipped its own distribution. There is one distribution now — the Engine's, built by
  // :modules:ui:installDist — so the list names it alone; the cost can only have gone down.
  if (!opts.skipBuild) {
    process.stderr.write(
      '[dev-runner] Ensuring distribution is up-to-date (installDist)...\n');
    const buildResult = runToolSync(
      'gradle',
      [':modules:ui:installDist', '-PskipWebBuild=true'],
      // Tempdoc 696: pin a >= 24 JDK so a stale JDK-8 JAVA_HOME can't fail the assemble.
      {
        cwd: repoRoot,
        stdio: ['ignore', 'pipe', 'inherit'],
        env: { ...process.env, JAVA_HOME: resolveJdkHome() },
      },
    );
    if (buildResult.status !== 0) {
      // Tempdoc 844 S5: a spawn that never ran gives status === null, and the real cause is in
      // `.error` (ENOENT on gradlew.bat, EACCES, EAGAIN). Reporting "exit code null" while
      // dropping it sent the reader looking for a Gradle failure that never happened.
      throw new Error(
        buildResult.error
          ? `Gradle installDist could not be run: ${buildResult.error.message}`
            + `${buildResult.error.code ? ` (${buildResult.error.code})` : ''} — ${gradlePath}`
          : `Gradle installDist failed with exit code ${buildResult.status}`
            + `${buildResult.signal ? ` (killed by ${buildResult.signal})` : ''}`);
    }
  }

  // Launch directly from installDist output instead of `gradlew runHeadless`.
  // The start script includes the correct classpath and JVM args; env vars are inherited.
  const headDistBin = path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'bin');
  const startScript = process.platform === 'win32'
    ? path.join(headDistBin, 'ui.bat')
    : path.join(headDistBin, 'ui');

  // S1: Dev-mode AOT cache — pass -XX:AOTCache= to the Head JVM if available.
  // The cache is generated by: ./gradlew generateDevHeadAotCache
  let headAotOpts = '';
  const headAotCache = path.resolve(repoRoot, 'modules', 'ui', 'build', 'aot-dev', 'head', 'head.aot');
  if (fs.existsSync(headAotCache)) {
    headAotOpts = `-XX:AOTCache=${headAotCache}`;
    process.stderr.write(`[dev-runner] Using dev AOT cache: ${headAotCache}\n`);
  }

  // Lane F stage B item B7/B8: the conformance harness substitutes its fake engine (and a stand-in
  // for the Vite dev server) here, which is the only way to drive a real crash, a real wedge and a
  // real handle-release cooldown through THIS supervisor rather than through a stand-in for it.
  //
  // Gated on the harness flag and not on the command variable alone: an inherited
  // JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND must not be able to replace a developer's Engine with
  // something else, so the escape hatch needs two keys, not one. `loadPolicy` reads the same flag
  // for the same reason, and the harness's own self-test asserts that one key is not enough.
  const harnessActive = process.env[engineSupervisor.HARNESS_FLAG] === '1';
  const harnessEngineCommand = harnessActive ? process.env.JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND : null;
  const harnessFrontendCommand = harnessActive ? process.env.JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND : null;

  // Fail fast if the Head dist doesn't exist (e.g. --skip-build without prior installDist).
  // Without this check, spawn() fails silently and the only feedback is a 60s timeout.
  if (!harnessEngineCommand && !fs.existsSync(startScript)) {
    const gradleCmd = `./${path.basename(gradlePath)}`;
    const remedy = `node scripts/dev/prepare-worktree.cjs (or: ${gradleCmd} :modules:ui:installDist)`;
    // Tempdoc 844 B2: a fully-understood, recoverable condition with a printed remedy is NOT an
    // unhandled exception. It surfaced as error code UNHANDLED on 16 of 20 observed `start` errors,
    // which mis-states the severity and puts it outside the documented admission code set.
    // Classified here — the layer that knows the condition — so the MCP wrapper needs no re-derivation.
    const err = new Error(
      `Head dist not found at ${startScript}. Make this checkout dev-ready (tempdoc 618 §3):\n` +
        `  node scripts/dev/prepare-worktree.cjs           # one command: npm ci + installDist\n` +
        `  or: ${gradleCmd} :modules:ui:installDist\n` +
        `Then retry start (or drop --skip-build to build automatically).`,
    );
    err.code = 'DIST_NOT_BUILT';
    err.details = { distPath: startScript, repoRoot, remedy };
    throw err;
  }

  // Tempdoc 606 Piece 2: capture provenance of the dist we are about to launch.
  // installDist has run by now, so the lib dir exists for the content stamp.
  const devStackProvenance = resolveProvenance(opts.distFromRoot);

  // Tempdoc 844 §4.2 R3: hot-reload facts are RECORDED per run instead of being a constant
  // three code sites agreed on by coincidence. The MCP `reload` tool reads the port and the
  // identity token from run.json; nothing re-derives 5005.
  const devHotReload = await resolveDevHotReload(opts.hotReload, { buildRan: !opts.skipBuild });
  process.stderr.write(
    `[dev-runner] Launching dist: repoRoot=${devStackProvenance.repoRoot} ` +
    `gitHead=${devStackProvenance.gitHead ?? '?'} headDistStamp=${devStackProvenance.headDistStamp ?? '?'}\n`);

  const engineJavaOpts = buildHeadJavaOpts({
    existingJavaOpts: process.env.JAVA_OPTS,
    headAotOpts,
    headDistStamp: devStackProvenance.headDistStamp,
    logsDir,
    dataDir,
    headHeap: process.env.JUSTSEARCH_HEAD_HEAP,
    promptCacheMiB: opts.promptCacheMiB,
    debugPort: devHotReload.enabled ? devHotReload.debugPort : null,
  });
  const spawnBackend = harnessEngineCommand
    ? (() => {
      const parsed = JSON.parse(harnessEngineCommand);
      return { cwd: repoRoot, command: parsed[0], args: parsed.slice(1), shell: false };
    })()
    : {
      cwd: repoRoot,
      ...engineJavaLaunch({ startScript, javaHome: resolveJdkHome(),
        javaOpts: engineJavaOpts, uiOpts: process.env.UI_OPTS, hotReload: devHotReload }),
    };
  if (harnessEngineCommand) {
    process.stderr.write(
      `[dev-runner] supervisor-conformance harness: engine command replaced by ${spawnBackend.command}\n`);
  }

  let apiPortActual = apiPortRequested;

  // Tempdoc 501 §3.1 closure-pass: stdout was previously parsed for
  // JUSTSEARCH_API_PORT=<n> as a fast-path discovery channel. That violates
  // the design's closure rule ("one mechanism per concern" — manifest is
  // the canonical discovery path). The stdout line still flows to the log
  // for human observation; only the consumer-side parse-to-state is gone.
  // Port comes exclusively from <dataDir>/runtime/manifest.json read in the
  // wait loop below.

  // Lane F stage B item B8: one incarnation of the Engine. A supervised restart calls this again
  // with the SAME log streams, the same run id and the same lease — 7.6's dev-runner sentence — so
  // what survives the child is everything except the child.
  const spawnEngineChild = (apiPortForThisIncarnation, incarnationEnv = {}, n = 1, startup = {}) => spawnEngineInJob(
    spawnBackend.command,
    spawnBackend.args,
    {
      cwd: spawnBackend.cwd,
      // The owner's label for operation-lease attribution (or none: an inherited value is removed).
      env: applyAgentSessionEnv({
        ...process.env,
        ...aiEnv,
        // Spec F §3.1: JUSTSEARCH_ENGINE_INCARNATION=<runId>/<n>, set by the actuator per spawn.
        ...incarnationEnv,
        // Tempdoc 696: pin a >= 24 JDK for the Engine JVM (ui.bat prefers JAVA_HOME). Since lane F
        // stage A item A11 deleted the Worker child, that is now the only JVM the pin has to cover;
        // the inference process the Engine still spawns inherits this env.
        JAVA_HOME: resolveJdkHome(),
        JUSTSEARCH_API_PORT: String(apiPortForThisIncarnation),
        JUSTSEARCH_DATA_DIR: dataDir,
        JUSTSEARCH_HOME: dataDir,
        // Tempdoc 842 §2.4: chat model profile ("compact" | "standard"). Ambient operator env
        // always wins (effectiveChatProfile already checked process.env first); the dev default
        // is "compact" even when --chat-profile is omitted entirely.
        JUSTSEARCH_CHAT_PROFILE: effectiveChatProfile,
        // The Engine's shipped default for the io.justsearch logger is INFO (modules/ui's
        // logback.xml:136 — the Worker's own logback went with the child process), so query text
        // (logged at DEBUG) stays out of diagnostics exports, which bundle logs/ with
        // path-only redaction. Dev has no such exposure and wants the verbose lines, so the
        // dev-runner opts back in — honour an explicit override if the caller set one.
        JUSTSEARCH_LOG_LEVEL: process.env.JUSTSEARCH_LOG_LEVEL || 'DEBUG',
        // Tempdoc 542 §B Layer 3: Head reads this to know where to write op-leases.json.
        // Absent → Head's OperationLeaseService is a no-op (production / non-dev-runner).
        JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
        // Hot-reload: DevReloadManager's gate (tempdoc 305). The JDWP listener is no longer an
        // env var the launched process reads back — it is a launch flag on JAVA_OPTS below, since
        // lane F item A11 deleted the Worker child whose command line used to carry it.
        // Tempdoc 844 M3: set EXPLICITLY in both directions. Spreading process.env above means an
        // ambient JUSTSEARCH_DEV_HOTRELOAD=true would otherwise survive a run where this decided
        // hot reload is off, and the Engine would report a reload capability the run record denies.
        JUSTSEARCH_DEV_HOTRELOAD: devHotReload.enabled ? 'true' : 'false',
        // Engine startup flags: G1 (design section 8 default; one heap serves search while it
        // indexes), MetaspaceSize=128m,
        // -XX:-UsePerfData (skip hsperfdata file); tiered compilation left at its default
        // (lane F PR 0), so the set no longer forks on AOT-cache presence.
        // S1: Pass dev AOT cache flag when available.
        // Tempdoc 730 B3: bounded -Xmx + HeapDumpOnOutOfMemoryError, dumping into THIS run's own
        // logs dir (JUSTSEARCH_HEAD_HEAP overrides the 2g default for constrained devices).
        JAVA_OPTS: engineJavaOpts,
        // NOTE: justsearch.repo.root is NOT set here. In Tauri production, lib.rs sets it to
        // headless_dir where sidecar ONNX models live. In dev mode, OnnxModelDiscovery's sidecar
        // step is a no-op, so reranker/citation-scorer are inactive. To enable them, set
        // JUSTSEARCH_RERANK_MODEL_PATH and JUSTSEARCH_CITATION_SCORER_MODEL_PATH explicitly.
      }, callerIdentity),
      shell: spawnBackend.shell,
      windowsHide: spawnBackend.windowsHide ?? true,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
    backendStdout,
    backendStderr,
    (streamKind, buf) => {
      // Tempdoc 501 §3.1: stdout is for human-readable logs only; discovery
      // happens through <dataDir>/runtime/manifest.json (read in wait loop).
      if (streamKind === 'stdout') {
        backendStdout.write(buf);
      } else {
        backendStderr.write(buf);
      }
    },
    // The incarnation's job record lives with the run, where a fallback stop reads it.
    { recordDir: path.join(runDir, 'engine-jobs'), label: `engine-${n}`, ...startup,
      onStartupFailure: details => persistEngineStartupFailure(runPath, runJson ?? startingRun, n, details) },
  );

  // Spec F §5.2 (step 9): the Engine is spawned, observed and stopped by the actuator
  // (lib/engine-actuator.cjs) under the run loop (lib/engine-supervisor.cjs `createSupervisionLoop`).
  // What stays here is the CLI, the frontend, the run record and the reports. `backend` is the
  // current incarnation's child; only the actuator spawns it.
  let backend = null;

  const spawnFrontend = () => {
    if (harnessFrontendCommand) {
      const parsed = JSON.parse(harnessFrontendCommand);
      return { cwd: repoRoot, command: parsed[0], args: parsed.slice(1), shell: false };
    }
    // Spec F §5.3 (frontend containment): vite's own node.exe is spawned directly, shell:false, so it
    // is a libuv child of this runner (in its job) rather than a grandchild of cmd.exe and npm that
    // breaks away and survives a runner kill. `npm run dev` ran exactly this (ui-web's `dev` = vite).
    return {
      cwd: uiWebDir,
      command: process.execPath,
      args: [path.join(uiWebDir, 'node_modules', 'vite', 'bin', 'vite.js'),
        '--host', '--port', String(uiPort), '--strictPort'],
      shell: false,
    };
  };

  const readTimeoutMs = (envKey, fallbackMs) => {
    const raw = process.env[envKey];
    if (raw == null || String(raw).trim() === '') return fallbackMs;
    const n = Number(String(raw).trim());
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallbackMs;
  };

  // Self-hosted runners can have cold-start builds (Gradle) that exceed 60s.
  // Use a longer default in CI, and allow explicit override via env vars.
  // Local: direct launch ~1.5s + app startup ~4.5s = ~6s to port emit,
  //        ~38s to worker ready (measured Mar 2026, tempdoc 275 S7).
  const defaultPortEmitTimeoutMs = process.env.CI ? 300_000 : 15_000;
  // GPU model initialization (ONNX CUDA session creation) routinely takes >60s.
  // Auto-detect GPU intent from env vars and use a longer default.
  const gpuRequested = !!(
    (process.env.JUSTSEARCH_EMBED_GPU_LAYERS && process.env.JUSTSEARCH_EMBED_GPU_LAYERS !== '0')
    || process.env.JUSTSEARCH_SPLADE_GPU_ENABLED === 'true'
    || (process.env.JUSTSEARCH_GPU_LAYERS && process.env.JUSTSEARCH_GPU_LAYERS !== '0')
  );
  const defaultBackendReadyTimeoutMs = process.env.CI ? 300_000
    : gpuRequested ? 180_000
    : 60_000;
  const portEmitTimeoutMs = readTimeoutMs('JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS', defaultPortEmitTimeoutMs);
  const backendReadyTimeoutMs = readTimeoutMs('JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS', defaultBackendReadyTimeoutMs);

  // Tempdoc 501 Phase 6: HeadlessApp writes <dataDir>/runtime/manifest.json
  // (the producer-published runtime manifest). The manifest is the sole discovery path
  // (tempdoc 501 §3.1): it carries instanceId, cross-linked into run.json so restarts are
  // detectable across orchestrator views.
  //
  // Preserve the predecessor manifest: schema v2 carries the managed-child ownership handoff.
  // Discovery accepts only the current spawned Engine PID, so stale ports cannot bind.
  const runtimeDir = path.join(dataDir, 'runtime');
  const manifestPath = path.join(runtimeDir, 'manifest.json');
  // Pre-spawn cleanup (the loop calls it before every spawn). api-port.txt is the deprecated
  // mirror (Phase 8); unlink it so a stale --clean=none restart doesn't leave a misleading file
  // around for any legacy consumer.
  const clearStaleDiscoveryFiles = () => {
    try { fs.unlinkSync(path.join(runtimeDir, 'api-port.txt')); } catch { /* ok if absent */ }
  };

  let manifestInstanceId = null;
  const readRawManifest = () => {
    try {
      return JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    } catch {
      return null; // not yet written or malformed
    }
  };
  /**
   * T2's observation: the manifest names THIS spawn's pid and a new instance. Lane F item B8 made
   * every start read it, including an explicit `--api-port` one: a supervisor needs the instanceId
   * on every incarnation to tell one boot from the next.
   */
  const readDiscoveredManifest = (expectedPid, predecessor) => {
    const parsed = readRawManifest();
    const p = parsed?.head?.apiPort;
    if (Number.isInteger(p) && p > 0 && p <= 65535
        && parsed.pid === expectedPid && typeof parsed.instanceId === 'string'
        && parsed.instanceId.trim() && parsed.instanceId !== predecessor) {
      return { apiPort: p, instanceId: parsed.instanceId };
    }
    return null;
  };

  const supervisionPolicy = engineSupervisor.loadPolicy();
  const publishSupervisorRecord = createSupervisorStateWriter(dataDir);
  const narrate = (line) => process.stderr.write(`[dev-runner] ${line}\n`);
  // Tempdoc 819 §D: an external `stop` drops this marker before it POSTs the Engine's shutdown, so
  // the exit it causes is a host quit and not a death to restart from.
  const gracefulShutdownMarkerPath = path.join(runDir, 'graceful-shutdown.json');
  let runJson = null;
  let stopFrontendTree = async () => true;
  // True from the first ready until run.json is on disk: a restart waits for it, so a replacement
  // is never reported running before the run it belongs to is recorded.
  let runRecordPending = false;
  let frontend = null;
  let shuttingDown = false;
  // Set while a caller in this process (Ctrl+C, the reaper, a frontend exit) has asked the loop to
  // quit and will exit the process itself once the loop's outcome is in.
  let quitCallerActive = false;
  // Tempdoc 606 3b: set while the reaper deliberately tears the stack down.
  let reaping = false;
  let markStartupSettled;
  const startupSettled = new Promise((resolve) => { markStartupSettled = resolve; });
  let onExit = () => {};

  /** Each incarnation's Engine job not yet ended (rootAsChild children), by incarnation. */
  const engineJobs = new Map();
  /** The registered-helper step's outcomes for the incarnation a host quit ended (the stop answer). */
  let quitHelperCleanup = null;
  /**
   * The stop's last step: ends every Engine job still held, after the ordered quit, the
   * registered-helper step and the recorded identities. A job that emptied by itself is only
   * retired; otherwise its recorded host is ended on a handle showing its recorded birth, which
   * closes the job, and each member is verified gone. Each process the job's termination ended is
   * listed by identity.
   */
  const endEngineJobs = () => {
    const summary = { state: 'ended', incarnations: [], endedByJob: [], survivors: [] };
    for (const [n, child] of [...engineJobs]) {
      engineJobs.delete(n);
      let result;
      try {
        result = jobRun.settleRunRecord(child.jobRun, { code: engineActuator.NON_DISTINCTIVE_KILL_CODE });
      } catch (err) {
        result = { ok: false, outcome: 'failed', reason: err?.message ?? String(err) };
      }
      const ended = jobIdentities(result?.endedByJob), survivors = jobIdentities(result?.survivors);
      summary.incarnations.push({ incarnation: n, host: child.jobRun.host ?? null, outcome: result?.outcome ?? 'failed',
        endedByJob: ended, survivors });
      summary.endedByJob.push(...ended);
      summary.survivors.push(...survivors);
      if (!result?.ok) summary.state = 'incomplete';
      if (ended.length) {
        narrate(`Engine incarnation ${n}: its job ended ${ended.length} process(es) the Engine left running: `
          + ended.map((p) => `pid ${p.pid} (born ${p.creationFileTimeUtc})`).join(', '));
      }
      if (!result?.ok) narrate(`Engine incarnation ${n}: its job stop was ${result?.outcome ?? 'failed'}`);
    }
    return summary;
  };

  /**
   * One incarnation's death record, written on EVERY death (lane F item B8): the per-incarnation
   * engine.log copy lands before the next incarnation can append to the shared file, then the stop
   * report. A death before the first ready has no run.json yet and goes through
   * `recordDiscoveryExit`; a terminal death also cleans up the Engine's registered children. A host
   * quit's report belongs to whoever asked for it (`stop`), except an interactive Ctrl+C.
   */
  const writeIncarnationReport = async ({ n, code, terminal, decision, hostQuit }) => {
    const preserved = await preserveEngineLog(runJson ?? { dataDir }, runPath, {
      destSubdir: path.join('incarnations', String(n)),
    }).catch(() => null);
    const engineLog = preserved?.preserved ? preserved.path : null;
    if (hostQuit && !shuttingDown) {
      const killedPids = [], errors = [];
      const childCleanup = terminal ? await cleanupTerminalChildren(dataDir, killedPids, errors) : [];
      quitHelperCleanup = { incarnation: n, childCleanup };
      return { engineLog, killedPids, childCleanup, errors };
    }
    if (!runJson) {
      await recordDiscoveryExit({ decision, dataDir, runId, runPath, backendExitCode: code, incarnation: n })
        .catch((err) => narrate(`discovery exit report failed: ${err?.message ?? err}`));
      if (terminal && !quitCallerActive) endEngineJobs();
      return { engineLog };
    }
    const killedPids = [], errors = [];
    const childCleanup = terminal ? await cleanupTerminalChildren(dataDir, killedPids, errors) : [];
    // A terminal death no stop caller owns: the Engine's jobs end after the registered-helper step.
    if (terminal && !quitCallerActive) endEngineJobs();
    await writeSelfExitStopReport({ runId, runPath, run: runJson, backendExitCode: code,
      interactive: shuttingDown, incarnation: n, killedPids, childCleanup, errors }).catch(() => { });
    return { engineLog, killedPids, childCleanup, errors };
  };

  // The starting record (published at admission) until the first ready, then the full one.
  const updateRunRecord = async () => {
    if (fs.existsSync(path.join(runDir, 'forgotten-unresolved.json'))) return;
    await writeJsonAtomic(runPath, runJson ?? startingRun)
      .catch((err) => narrate(`run.json update failed: ${err?.message ?? err}`));
  };

  const actuator = engineActuator.createEngineActuator({
    dataDir,
    runId,
    policy: supervisionPolicy,
    spawnChild: (n, port, incarnationEnv, startup) => {
      backend = spawnEngineChild(port, incarnationEnv, n, startup);
      // Held until endEngineJobs: a restart keeps what the dead incarnation left (as it keeps its
      // registered helpers), and a stop ends it only after its ordered steps.
      if (backend.jobRun) engineJobs.set(n, backend);
      return backend;
    },
    describeExit: (code) => engineSupervisor.describeExit(code, supervisionPolicy),
    log: narrate,
  });

  const supervision = engineSupervisor.createSupervisionLoop({
    actuator,
    policy: supervisionPolicy,
    requestedApiPort: apiPortRequested,
    timeouts: { initialPortMs: portEmitTimeoutMs, initialReadyMs: backendReadyTimeoutMs },
    // Harness-only host input exercises the production request path after readiness.
    harness: {
      requestReason: harnessActive ? (process.env.JUSTSEARCH_SUPERVISOR_HARNESS_REQUEST_REASON || null) : null,
    },
    log: narrate,
    io: {
      readManifest: readDiscoveredManifest,
      readRawManifest,
      probeHealth: (port, ms) => checkHttp200(`http://127.0.0.1:${port}/api/health`, ms, true),
      probeAlive: (port, ms) => checkHttp200(`http://127.0.0.1:${port}/api/health`, ms, true),
      fetchStatus: (port, ms) => fetchJsonHttp(`http://127.0.0.1:${port}/api/status`, ms),
      essentialReadyEpoch,
      waitForRelease: ({ pid }) => waitForEngineHandleRelease({ pid, dataDir }),
      clearStaleDiscoveryFiles,
      externalStopRequested: () => fs.existsSync(gracefulShutdownMarkerPath),
    },
    publish: async (snapshot) => {
      manifestInstanceId = snapshot.instanceId;
      await publishSupervisorRecord(buildSupervisorState({
        ...snapshot,
        runId,
        policy: supervisionPolicy,
        updatedAt: nowIso(),
      }));
    },
    hooks: {
      // X09-F4: a replacement's pid is in run.json before its discovery begins, so a stop during
      // that discovery finds it.
      onSpawned: async ({ n, pid }) => {
        const spawned = actuator.current();
        const record = runJson ?? startingRun;
        record.pids.backendRootPid = pid;
        record.incarnation = n;
        record.identities = { ...(record.identities ?? {}), backend: null };
        // The incarnation's job: a fallback stop ends what is left in it after the recorded identities.
        const job = spawned?.child?.jobRun;
        record.engineJob = job?.host ? { incarnation: n, recordPath: job.recordPath, host: job.host } : null;
        await updateRunRecord();
        // Spec F §5.3: the identity a fallback stop verifies before it kills this Engine. Looked up
        // without blocking discovery; written only while this incarnation is still the current one.
        void Promise.resolve(spawned?.identityPromise).then(async (identity) => {
          const latest = runJson ?? startingRun;
          if ((!identity?.creationFileTime && !identity?.processIdentity) || latest.pids.backendRootPid !== pid || latest.incarnation !== n
              || actuator.current() !== spawned || spawned?.n !== n || spawned.exit) return;
          latest.identities = { ...(latest.identities ?? {}),
            backend: { pid, creationFileTimeUtc: identity.creationFileTime, ...(identity.processIdentity ? { processIdentity: identity.processIdentity } : {}), executable: identity.executable ?? null } };
          await updateRunRecord();
        }).catch(() => {});
      },
      onReady: async ({ n, pid, apiPort, instanceId }) => {
        if (!runJson) return;
        const readyState = actuator.current();
        // Whatever the restart actually bound is written back, so a port that could not be reused
        // produces a corrected record instead of a lie.
        apiPortActual = apiPort;
        runJson.apiPortActual = apiPort;
        runJson.apiBaseUrl = `http://127.0.0.1:${apiPort}`;
        runJson.pids.backendRootPid = pid;
        runJson.incarnation = n;
        runJson.resourceClaims.apiPort = apiPort;
        runJson.resourceClaims.runtimeManifestInstanceId = instanceId;
        await updateRunRecord();
        const capturedIdentity = await engineIdentityAsync(pid, dataDir).catch(() => null);
        if (supervision.snapshot().incarnation !== n || actuator.current() !== readyState || readyState?.exit) return;
        runJson.exitCensusEngine = capturedIdentity;
        await updateRunRecord();
      },
      onIncarnationExit: (exit) => writeIncarnationReport(exit),
      restartReady: () => !runRecordPending,
      onTerminal: async (outcome) => {
        if (outcome.state === engineSupervisor.STATES.EXHAUSTED) {
          // Design 7.1's terminal state. The file is what makes it observable: the Engine is down,
          // so no engine API can carry this, and the updater (item B13) reads exactly this record.
          narrate(`ENGINE_RESTART_EXHAUSTED — ${String(outcome.reason).replace(/^ENGINE_RESTART_EXHAUSTED:/, '')}. `
            + `Supervisor state: ${toPosix(path.relative(repoRoot, supervisorStatePath(dataDir)))}`);
        }
        // Before the first ready the start command reports the outcome (firstReady rejects); a quit
        // asked for in this process is ended by its caller.
        if (!outcome.firstReadyDone || quitCallerActive || reaping) return;
        await startupSettled;
        endEngineJobs();
        onExit();
        process.exit(outcome.code != null && outcome.code !== 0 ? outcome.code : 0);
      },
    },
  });

  supervision.run().catch((err) => {
    narrate(`supervisor failed: ${err?.stack ?? err}`);
    if (err?.details) narrate(`supervisor failure details: ${JSON.stringify(err.details)}`);
    try { onExit(); } catch { /* streams not open yet */ }
    process.exit(1);
  });

  let frontendIdentity = null;
  let frontendStopIncomplete = true;
  // Keep the 5 s synchronous termination bound: the signal handler exits without awaiting us.
  // Only the recorded frontend is ended, never a tree through parent links: vite runs directly as
  // this runner's libuv child, so its own children are in the runner's job.
  stopFrontendTree = async (waitMs = 3000, { asynchronous = false } = {}) => {
    if (!frontend || !frontend.pid) return true;
    if (frontend.exitCode !== null || frontend.signalCode !== null) {
      if (process.platform === 'linux' && frontend.jobRun) return jobRun.settleRunRecord(frontend.jobRun).ok;
      return !frontendStopIncomplete;
    }
    let onFrontendExit;
    let timer;
    const exited = new Promise((resolve) => {
      onFrontendExit = () => resolve(true);
      frontend.once('exit', onFrontendExit);
    });
    try {
      if (process.platform === 'win32') {
        const result = frontendIdentity?.pid === frontend.pid
          ? await (asynchronous ? engineActuator.terminateWithCode : engineActuator.terminateWithCodeSync)(
            frontend.pid, frontendIdentity.creationFileTimeUtc,
            engineActuator.NON_DISTINCTIVE_KILL_CODE, { timeoutMs: 5000 }) : null;
        frontendStopIncomplete = !result?.ok;
      } else {
        frontendStopIncomplete = !processIdentity.signalLinuxProcess(frontendIdentity, 'SIGTERM').ok;
      }
    } catch (_) { frontendStopIncomplete = true; }
    try {
      const gone = await Promise.race([exited, new Promise((resolve) => {
        timer = setTimeout(() => resolve(false), waitMs);
      })]);
      if (process.platform === 'linux' && frontend.jobRun) return gone && jobRun.settleRunRecord(frontend.jobRun).ok;
      return gone && !frontendStopIncomplete;
    } finally {
      clearTimeout(timer);
      frontend.removeListener('exit', onFrontendExit);
    }
  };

  // Spec F §5.3: `stop` (and a takeover's stop) ASKS this runner, which is the only actor on its
  // Engine. The request becomes a host quit in the loop; the answer goes to stop-result.json.
  const controlDir = path.join(runDir, 'control');
  const stopRequestPath = path.join(controlDir, 'stop-request.json');
  const stopResultPath = path.join(controlDir, 'stop-result.json');
  const QUIT_DETAILS = new Set(['takeover', 'interrupted', 'session_end']);
  const takeStopRequest = async (request) => {
    quitCallerActive = true;
    clearInterval(stopPoll);
    const detail = request?.reason === 'takeover' ? 'takeover'
      : (QUIT_DETAILS.has(request?.detail) ? request.detail : null);
    narrate(`stop requested (${request?.reason ?? 'quit'}${detail ? `, ${detail}` : ''}); asking the Engine to quit.`);
    const frontendStop = stopFrontendTree(3000, { asynchronous: true });
    const outcome = await Promise.race([
      supervision.hostQuit({ detail }),
      new Promise((resolve) => setTimeout(() => resolve(null), engineActuator.QUIT_DEADLINE_MS + 1000)),
    ]);
    await actuator.flush();
    const frontendStopped = await frontendStop;
    // Last, the Engine's jobs: after the quit, the registered-helper step (run with the Engine's
    // exit report) and the frontend's recorded identity. Each process they end is listed.
    const heldJobs = engineJobs.size > 0;
    const ending = endEngineJobs();
    const engineJob = heldJobs ? {
      incarnation: outcome?.incarnation ?? null,
      host: backend?.jobRun?.host ?? null,
      state: ending.state,
      rootExitCode: backend?.jobFinal?.rootExitCode ?? null,
      endedByJob: ending.endedByJob,
      survivors: ending.survivors,
      incarnations: ending.incarnations,
    } : null;
    const stopped = Boolean(outcome) && frontendStopped && (!engineJob || engineJob.state === 'ended');
    await writeJsonAtomic(stopResultPath, {
      schemaVersion: 1,
      requestId: request?.requestId ?? null,
      runId,
      outcome: stopped ? 'stopped' : 'incomplete',
      engine: outcome ? { state: outcome.state, code: outcome.code ?? null, incarnation: outcome.incarnation ?? null } : null,
      frontendStopped,
      engineJob,
      // What the registered-helper step ended, before the recorded identities and the job.
      registeredHelpers: quitHelperCleanup?.childCleanup ?? [],
      at: nowIso(),
    }).catch((err) => narrate(`stop result write failed: ${err?.message ?? err}`));
    if (!ctx.settled) return; // a start in progress reports its own end (cmdStart)
    onExit();
    process.exit(0);
  };
  const stopPoll = setInterval(() => {
    if (ctx.stopHandling || !fs.existsSync(stopRequestPath)) return;
    let request;
    try { request = JSON.parse(fs.readFileSync(stopRequestPath, 'utf8')); } catch { return; }
    ctx.stopHandling = takeStopRequest(request).catch((err) => narrate(`stop request failed: ${err?.stack ?? err}`));
  }, 200);
  /**
   * A stop that arrived before the start settled owns the end of this start: nothing more is spawned
   * and the running record is never published over a stack being stopped (cmdStart reports it).
   */
  const endIfStopRequested = async () => {
    if (!ctx.stopHandling) return;
    await ctx.stopHandling;
    await stopFrontendTree();
    throw Object.assign(new Error('stop requested during startup'), { code: 'START_STOPPED' });
  };

  // Discovery precedes run.json and the frontend. A death or a start timeout before the first ready
  // is handled by the loop like any other (T4, T5), so an Engine is never abandoned alive; the start
  // fails only once the loop has reached a terminal outcome.
  try {
    const firstReady = await supervision.firstReady;
    runRecordPending = true;
    apiPortActual = firstReady.apiPort;
    manifestInstanceId = firstReady.instanceId;
  } catch (error) {
    // cmdStart removes this start's records (run.json, active.json) on the way out.
    if (error?.details) error.details = { runId, ...error.details };
    throw error;
  }
  await endIfStopRequested();

  const apiBaseUrl = `http://127.0.0.1:${apiPortActual}`;
  const uiUrl = `http://localhost:${uiPort}`;

  // indexBasePath capture (271 stage 4)
  const expectedIbp = resolveExpectedIndexBasePath(dataDir);
  let confirmedIbp = null;
  try {
    confirmedIbp = await fetchConfirmedIndexBasePath(apiPortActual);
  } catch { /* best-effort */ }

  const spawnF = spawnFrontend();
  frontend = spawnLogged(
    spawnF.command,
    spawnF.args,
    {
      cwd: spawnF.cwd,
      recordDir: path.join(runDir, 'frontend-jobs'),
      env: applyAgentSessionEnv({
        ...process.env,
        VITE_JUSTSEARCH_API_PORT: String(apiPortActual),
        VITE_API_PORT: String(apiPortActual),
      }, callerIdentity),
      shell: spawnF.shell,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
    frontendStdout,
    frontendStderr,
    null,
  );

  // Spec F §5.3: the frontend's identity, for a fallback stop that must verify it before a kill.
  frontendIdentity = frontend.pid
    ? await engineActuator.lookupProcessIdentities([frontend.pid]).then((map) => {
      const seen = map?.get(frontend.pid);
      return frontend.exitCode === null && frontend.signalCode === null && seen?.present && (seen.creationFileTime || seen.processIdentity)
        ? { pid: frontend.pid, creationFileTimeUtc: seen.creationFileTime ?? null, ...(seen.processIdentity ? { processIdentity: seen.processIdentity } : {}), executable: seen.executable ?? null } : null;
    }).catch(() => null)
    : null;

  const initialCensusState = actuator.current();
  const initialCensusIdentity = await engineIdentityAsync(initialCensusState?.pid, dataDir);
  runJson = {
    exitCensusEngine: actuator.current() === initialCensusState && !initialCensusState?.exit ? initialCensusIdentity : null,
    schemaVersion: 1,
    incarnation: startingRun.incarnation,
    runId,
    startedAt,
    phase: 'running',
    control: { stopRequest: 1 },
    identities: {
      runner: runnerIdentity,
      backend: startingRun.identities?.backend?.pid === backend.pid ? startingRun.identities.backend : null,
      frontend: frontendIdentity,
    },
    engineJob: startingRun.engineJob ?? null,
    ...(frontend.jobRun ? { frontendJob: { recordPath: frontend.jobRun.recordPath, host: frontend.jobRun.host } } : {}),
    apiPortRequested: apiPortRequested,
    apiPortActual,
    uiPortRequested: uiPort,
    uiPortActual: uiPort,
    apiBaseUrl,
    uiUrl,
    dataDir: toPosix(dataDir),
    repoRoot: toPosix(repoRoot),
    // Tempdoc 842 §2.4: the profile this stack's backend was spawned with, so MCP-side
    // auto-activation follows the stack's choice instead of assuming a default.
    chatProfile: effectiveChatProfile,
    // Tempdoc 844 §4.2 R3: what `reload` needs to push into THIS run — never re-derived from the
    // caller's cwd. `enabled:false` is a recorded fact ("this stack has no JDWP listener"), which
    // is why `reload` can refuse instead of attaching to a stranger's port.
    hotReload: devHotReload,
    spawn: {
      backend: { cwd: toPosix(spawnBackend.cwd), command: path.basename(spawnBackend.command), args: spawnBackend.args, shell: spawnBackend.shell },
      frontend: { cwd: toPosix(spawnF.cwd), command: spawnF.command, args: spawnF.args, shell: spawnF.shell },
    },
    pids: {
      runnerPid: process.pid,
      backendRootPid: backend.pid,
      frontendRootPid: frontend.pid,
    },
    logs: {
      backendStdout: toPosix(path.relative(repoRoot, backendStdoutPath)),
      backendStderr: toPosix(path.relative(repoRoot, backendStderrPath)),
      frontendStdout: toPosix(path.relative(repoRoot, frontendStdoutPath)),
      frontendStderr: toPosix(path.relative(repoRoot, frontendStderrPath)),
    },
    debug: {
      // Tempdoc 501 §3.1 closure: the historic `portLine` field carried the
      // raw JUSTSEARCH_API_PORT= stdout line that the dev-runner parsed for
      // discovery. Stdout-as-discovery is gone (manifest is the canonical
      // path); we record the manifest's instance identity here instead so
      // run.json still has the equivalent "what did we observe at startup"
      // breadcrumb for post-hoc audit.
      portSource: manifestInstanceId ? 'runtime-manifest' : 'unresolved',
      portSourceInstanceId: manifestInstanceId,
    },
    owner: {
      ...holderBlockFor(callerIdentity),
      confidence: resolveOwnerConfidence(callerIdentity, confirmedIbp),
    },
    resourceClaims: {
      apiPort: apiPortActual,
      uiPort,
      dataDir: toPosix(dataDir),
      justsearchHome: toPosix(dataDir),
      settingsStorePath: toPosix(path.join(dataDir, 'ui', 'settings.json')),
      runtimeDir: toPosix(path.join(dataDir, 'runtime')),
      // workerConfigSnapshotPath was claimed here until lane F stage A. The Engine no longer
      // writes <dataDir>/runtime/worker-config-snapshot.json (item A19 deleted the writer with
      // the ordinal-450 tier and the second JVM that read it), so the claim named a path that
      // never exists. runtimeDir already covers the directory for ownership purposes.
      // Tempdoc 501 §3.7: cross-link the orchestrator's run.json with the
      // producer-published manifest's instanceId. Restarts changing instanceId
      // are detectable from either view; stale orchestrator state becomes a
      // mechanical instanceId mismatch rather than a silent re-bind.
      runtimeManifestPath: toPosix(path.join(dataDir, 'runtime', 'manifest.json')),
      runtimeManifestInstanceId: (() => {
        // Re-read at write-time so we capture the manifest the producer wrote
        // (the in-loop read above may have missed it if stdout fired first).
        try {
          const content = fs.readFileSync(path.join(dataDir, 'runtime', 'manifest.json'), 'utf8');
          const parsed = JSON.parse(content);
          return parsed?.instanceId ?? manifestInstanceId;
        } catch {
          return manifestInstanceId;
        }
      })(),
      expectedIndexBasePath: toPosix(expectedIbp.path),
      expectedIndexBasePathEvidence: expectedIbp.evidence,
      confirmedIndexBasePath: confirmedIbp ? toPosix(confirmedIbp.path) : null,
      confirmedIndexBasePathEvidence: confirmedIbp?.evidence ?? null,
      confirmedIndexBasePathSource: confirmedIbp?.source ?? null,
    },
    cleanupPolicy: 'shared-stack',
  };

  await endIfStopRequested();
  // From here a stop request ends a settled stack (takeStopRequest exits the runner itself).
  ctx.settled = true;
  // A failed write leaves the start unsettled, so cmdStart removes its own records.
  if (!fs.existsSync(path.join(runDir, 'forgotten-unresolved.json'))) {
    await writeJsonAtomic(runPath, runJson).catch((err) => { ctx.settled = false; throw err; });
  }
  // The starting record becomes the running one (Spec F §5.3 item 2): same run, same epoch, the
  // lease carried on; the starting heartbeat hands over to the running renewal below.
  clearInterval(ctx.heartbeat);
  lease.sequence += 1;
  const leaseNow = nowIso();
  if (!fs.existsSync(path.join(runDir, 'forgotten-unresolved.json'))) await writeJsonAtomic(activePath, {
    kind: 'backend-shared-lease.v1',
    schemaVersion: 1,
    runId,
    // Relative to the MAIN checkout, the base every reader resolves it against (a worktree-relative
    // path climbs out of the main root). Readers also accept the older worktree-relative records.
    runPath: toPosix(path.relative(mainRepoRoot, runPath)),
    launcherFamily: 'dev-runner',
    mode: 'shared',
    // The owner: harness, readable label, and the owner key every self/other decision compares.
    holder: holderBlockFor(callerIdentity),
    takeoverPolicy: 'warn',
    // Tempdoc 606 3a: ownership epoch (bumps on each custody transfer → notification basis).
    ownershipEpoch,
    // Tempdoc 606 Piece 2: provenance of the code this stack actually runs.
    provenance: devStackProvenance,
    phase: 'running',
    runner: { pid: runnerIdentity.pid, creationFileTimeUtc: runnerIdentity.creationFileTimeUtc,
          ...(runnerIdentity.processIdentity ? { processIdentity: runnerIdentity.processIdentity } : {}) },
    lease: {
      // Tempdoc 735 G6: opts.leaseDurationSec is pre-clamped to [30, 7200]s at parse time
      // (clampLeaseDurationSec) and defaults to 30 — unchanged behavior when the starter
      // doesn't declare a longer campaign hold.
      durationSec: opts.leaseDurationSec,
      renewedAt: leaseNow,
      expiresAt: new Date(Date.now() + opts.leaseDurationSec * 1000).toISOString(),
      sequence: lease.sequence,
    },
    updatedAt: leaseNow,
  });
  runRecordPending = false;
  // Tempdoc 606 3a: record the epoch this holder acquired, so if it is later displaced
  // it can detect that (pull-at-next-action notification via the ownership projection).
  if (callerIdentity?.owner?.key) {
    ownerPresence.mergeOwnerRecord(stateRoot, callerIdentity.owner.key, {
      ownedEpoch: ownershipEpoch,
      harness: callerIdentity.harness,
      pid: callerIdentity.owner.pid ?? null,
      creationTime: callerIdentity.owner.creationTime ?? null,
      sessionId: callerIdentity.sessionId ?? null,
    });
  }
  // Per-session data of ENDED owners past the retention bound is pruned here, once per start;
  // a live or unjudgeable owner's record is never removed. Best-effort.
  try {
    ownerPresence.pruneOwnerRecords(stateRoot, { table: readFreshProcessTable });
  } catch { /* best-effort */ }

  const startResult = {
    ok: true,
    runId,
    apiPort: apiPortActual,
    uiPort,
    apiBaseUrl,
    uiUrl,
    dataDir: toPosix(dataDir),
    pids: runJson.pids,
    readiness: { ready_http: true },
  };

  if (opts.json) {
    process.stdout.write(JSON.stringify(startResult) + '\n');
  } else {
    console.error(`Started run ${runId}`);
    console.error(`Backend: ${apiBaseUrl}/api/status`);
    console.error(`Frontend: ${uiUrl}`);
    console.error(`Logs: ${toPosix(path.relative(repoRoot, logsDir))}/`);
  }

  let renewalInterval;

  onExit = () => {
    clearInterval(renewalInterval);
    try {
      backendStdout.end();
      backendStderr.end();
      frontendStdout.end();
      frontendStderr.end();
    } catch (_) { }
  };

  const shutdown = () => {
    // Guard against double-shutdown (e.g., SIGINT followed by SIGTERM)
    if (shuttingDown) return;
    shuttingDown = true;
    quitCallerActive = true;
    clearInterval(renewalInterval);
    // Spec F §5.3 (interruption): the loop is the only actor on the Engine. Ctrl+C posts a host quit
    // (detail `interrupted`) and waits for its outcome, bounded at 5 s; the Engine's exit is then
    // recorded like any other. Whatever is still running after that dies with the runner's job.
    const boundMs = 5000;
    Promise.race([
      supervision.hostQuit({ detail: 'interrupted', deadlineMs: boundMs }),
      new Promise((resolve) => setTimeout(resolve, boundMs)),
    ]).finally(async () => {
      await Promise.resolve(stopFrontendTree()).catch(() => false);
      endEngineJobs();
      onExit();
      process.exit(0);
    });
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  // Tempdoc 606 3b: `reaping` (declared above) is set while the reaper deliberately tears the
  // stack down, so the exit handlers don't process.exit() mid-cleanup (killing the supervisor
  // before stopRun clears active.json + writes the reaped_abandoned report).
  // Presence-aware renewal and reaper, keyed by the owner's harness process (see
  // lib/owner-presence.cjs). A declared campaign-length hold (--lease-duration-sec, tempdoc 735
  // G6) is intent and raises every threshold.
  const presenceMonitor = createPresenceMonitor();
  const declaredHoldMs = (opts.leaseDurationSec ?? DEFAULT_LEASE_DURATION_SEC) * 1000;
  let tickInFlight = false;
  renewalInterval = setInterval(async () => {
    if (tickInFlight) return;
    tickInFlight = true;
    try {
      lease.sequence += 1;
      const current = await readJsonIfExists(activePath);
      if (current?.runId !== runId) return;
      // Tempdoc 606 1d: the lease is renewed by THIS detached supervisor, decoupled from the
      // owning agent session, so freshness alone is a false liveness signal. Re-couple it to the
      // owner's process: an ended owner pauses renewal (the lease lapses and the stack is
      // reclaimable) and is reaped after the grace period; a running owner that has not used the
      // stack for an hour beyond its declared hold is reaped; an unknown owner never is.
      const presence = await presenceMonitor(current, declaredHoldMs);
      if (presence.action === 'reap') {
        process.stderr.write(`[dev-runner] Reaping stack (${presence.reason}) - freeing resources.\n`);
        reaping = true; // suppress the backend-exit auto-exit so stopRun finishes cleanup
        quitCallerActive = true;
        clearInterval(renewalInterval);
        // The loop quits the Engine first (its exit is recorded); stopRun then frees the rest.
        await Promise.race([
          supervision.hostQuit({ detail: 'session_end' }),
          new Promise((resolve) => setTimeout(resolve, engineActuator.QUIT_DEADLINE_MS + 2000)),
        ]);
        await stopRun({
          runId,
          disposition: presence.disposition,
          actor: { source: 'dev-runner', agentSessionId: current?.holder?.agentSessionId ?? null },
          victim: { runId, holder: current?.holder ?? null },
        }).catch(() => {});
        endEngineJobs();
        process.exit(0); // cleanup done (active.json removed + stop-report written)
        return;
      }
      if (presence.action === 'pause') {
        process.stderr.write(
          `[dev-runner] Owner ${describeHolder(current?.holder)}: ${presence.reason} ` +
          `(lease lapses within its ${opts.leaseDurationSec}s TTL).\n`);
        return; // skip this renewal; lease lapses within its declared TTL
      }
      const now = nowIso();
      await writeJsonAtomic(activePath, {
        ...current,
        // Tempdoc 735 G6: reassert the SAME declared duration on every renewal (not a fixed
        // 30s) — this is what gives a campaign-length hold its teeth: when the presence check
        // above later pauses renewal (owner busy for minutes with no CC-session activity), the
        // most recent expiresAt was already now+leaseDurationSec, not now+30s, so the passive
        // grace window is the full declared hold, not 30s.
        lease: {
          durationSec: opts.leaseDurationSec,
          renewedAt: now,
          expiresAt: new Date(Date.now() + opts.leaseDurationSec * 1000).toISOString(),
          sequence: lease.sequence,
        },
        updatedAt: now,
      });
    } catch (_) { /* best-effort renewal */ } finally { tickInFlight = false; }
  }, leaseRenewIntervalMs());

  // ============================================================================================
  // Lane F stage B item B8, Spec F step 9 — the supervisor state machine runs in
  // `engineSupervisor.createSupervisionLoop` (started above, before discovery). The DECISION is
  // `engineSupervisor.decide`'s, shared with the Tauri shell through the register; the loop owns the
  // order (one exit path, then a host quit, then the request deadline, then health) and the actuator
  // owns the process and the exit ledger. Every transition is published to
  // <dataDir>/runtime/supervisor.v1.json. What is left here is the frontend's exit.
  // ============================================================================================
  markStartupSettled();

  frontend.on('exit', (code) => {
    if (reaping || quitCallerActive || shuttingDown) return; // a deliberate stop owns teardown + exit
    // The stack is ending: the Engine is asked to quit through the loop, so its exit is recorded,
    // rather than left to die with the runner's job.
    quitCallerActive = true;
    narrate(`the frontend exited ${code}; stopping the Engine before exiting.`);
    Promise.race([
      supervision.hostQuit({}),
      new Promise((resolve) => setTimeout(resolve, engineActuator.QUIT_DEADLINE_MS + 2000)),
    ]).finally(() => {
      endEngineJobs();
      onExit();
      process.exit(code != null && code !== 0 ? code : 0);
    });
  });

  // Keep runner alive as a supervisor.
  // eslint-disable-next-line no-empty
  await new Promise(() => { });
}

async function cmdStatus(opts) {
  let run = null;
  try {
    ({ run } = await resolveRunTarget(opts));
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(
        JSON.stringify({ ok: false, runId: null, error: { code: 'NO_ACTIVE_RUN', message: 'No active run' } }) + '\n',
      );
      return;
    }
    throw err;
  }
  const apiPortRaw = Number(run?.apiPortActual);
  const uiPortRaw = Number(run?.uiPortActual);
  // Treat invalid ports as 0 (not listening) rather than letting NaN propagate
  const apiPort = Number.isFinite(apiPortRaw) && apiPortRaw > 0 ? apiPortRaw : 0;
  const uiPort = Number.isFinite(uiPortRaw) && uiPortRaw > 0 ? uiPortRaw : 0;

  const alive = {
    runner: isPidAlive(run?.pids?.runnerPid),
    backendRoot: isPidAlive(run?.pids?.backendRootPid),
    frontendRoot: isPidAlive(run?.pids?.frontendRootPid),
  };

  const ports = {
    api: { port: apiPort, listening: apiPort > 0 ? await isTcpListening(apiPort) : false },
    ui: { port: uiPort, listening: uiPort > 0 ? await isTcpListening(uiPort) : false },
  };

  const readiness = {
    ready_http: apiPort > 0 ? await checkHttp200(`http://127.0.0.1:${apiPort}/api/status`, 1200) : false,
  };

  const res = { ok: true, runId: run.runId, alive, ports, readiness };
  process.stdout.write(JSON.stringify(res) + '\n');
}

/** Spec F §5.3: a stop's result. STOPPED: the stack is verified gone and its ports are free. */
const STOP_OUTCOME = Object.freeze({ STOPPED: 'STOPPED', STOP_INCOMPLETE: 'STOP_INCOMPLETE' });

/** How long `stop` waits for the runner's answer: the Engine's quit deadline plus 2 s (Spec F §5.3). */
const STOP_RUNNER_WAIT_MS = engineActuator.QUIT_DEADLINE_MS + 2000;

/** A stop on behalf of another start (a takeover or reclaim) is a `takeover`; the owner's own is a `quit`. */
function stopRequestReasonFor(disposition) {
  if (!disposition || disposition === 'normal_stop' || disposition === 'owner_restart') return 'quit';
  if (String(disposition).startsWith('reaped')) return 'quit';
  return 'takeover';
}

/**
 * Spec F §5.3: ask the run's runner to stop its own stack. Writes `<runDir>/control/stop-request.json`
 * and waits for the runner's `stop-result.json` with the same request id, until `waitMs` or until the
 * runner is gone. The runner's loop is then the only actor on its Engine.
 *
 * @returns {Promise<{outcome: 'stopped'|'incomplete'|'no_answer'|'runner_gone', requestId: string,
 *   waitedMs: number}>}
 */
async function askRunnerToStop({
  runDir, runId, runnerPid, reason, detail = null, actor = null, waitMs = STOP_RUNNER_WAIT_MS,
  now = Date.now, sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)), alive = isPidAlive,
}) {
  const controlDir = path.join(runDir, 'control');
  const resultPath = path.join(controlDir, 'stop-result.json');
  const requestId = crypto.randomUUID();
  const startedAt = now();
  await writeJsonAtomic(path.join(controlDir, 'stop-request.json'), {
    schemaVersion: 1, requestId, runId, reason, detail, actor,
    deadlineEpochMs: startedAt + waitMs, requestedAt: new Date(startedAt).toISOString(),
  });
  for (;;) {
    // eslint-disable-next-line no-await-in-loop
    const result = await readJsonIfExists(resultPath).catch(() => null);
    if (result?.requestId === requestId) return { ...result, requestId, waitedMs: now() - startedAt };
    if (!alive(runnerPid)) return { outcome: 'runner_gone', requestId, waitedMs: now() - startedAt };
    if (now() - startedAt >= waitMs) return { outcome: 'no_answer', requestId, waitedMs: now() - startedAt };
    // eslint-disable-next-line no-await-in-loop
    await sleep(200);
  }
}

/**
 * The processes a run recorded, with the identity a stop verifies before acting on any of them
 * (Spec F §5.3, X09-F3): pid + creation FILETIME + executable, recorded at spawn. A record without
 * an identity (an older dev-runner's, or one written before the birth was read) only bounds its
 * processes: each was born before the record that names it was last written, so a later birth
 * under the same pid is a reuse; an earlier one is not proof of ownership (S17).
 */
function recordedProcesses(run, recordWrittenMs) {
  return [['runner', run?.pids?.runnerPid], ['backend', run?.pids?.backendRootPid],
    ['frontend', run?.pids?.frontendRootPid]].map(([role, raw]) => {
    const pid = Number(raw);
    if (!Number.isInteger(pid) || pid <= 0) return { role, pid: null };
    const rec = run?.identities?.[role];
    const recorded = Boolean(rec && Number(rec.pid) === pid && (rec.creationFileTimeUtc || processIdentity.linuxIdentity(rec)));
    return {
      role,
      pid,
      basis: recorded ? 'recorded' : 'record-time',
      creationFileTimeUtc: recorded && rec.creationFileTimeUtc ? String(rec.creationFileTimeUtc) : null,
      ...(recorded && rec.processIdentity ? { processIdentity: rec.processIdentity } : {}),
      executable: recorded ? rec.executable ?? null : null,
      bornNotAfterMs: recorded ? null : recordWrittenMs,
    };
  });
}

// <ledger-resolution> Every identity comparison in this region is compareIdentity's (ledger-identity.cjs);
// the structural check in test-dev-runner-stop-census.mjs holds it to that.
/**
 * The identities a stop tracks, as a list of distinct records (round 9): never a map keyed by part of
 * an identity. Two records of one PID and birth that name another childId, witness, owner, incarnation
 * or birth tick are both kept, and each is judged (ledger resolution, lookup) on its own, so one never
 * stands in for the other. Only a record repeated whole (compareIdentity 'exact', at the same tick:
 * an earlier stop writes its list to both run.json and stop-tracked-processes.json) is one record, its
 * other fields merged. `add(p)` adds one; `list` holds them in the order first added.
 */
function trackedIdentities() {
  const list = [];
  // By PID and birth: an index only; each candidate in a bucket is compared whole.
  const byKey = new Map();
  const sameRecord = (a, b) => compareIdentity(a, b, 'exact') && identityOf(a)?.tick === identityOf(b)?.tick;
  return {
    list,
    add(p) {
      if (!p?.pid) return;
      const key = identityKey(p);
      if (!byKey.has(key)) byKey.set(key, []);
      const bucket = byKey.get(key);
      const at = bucket.find((i) => sameRecord(list[i], p));
      if (at === undefined) {
        bucket.push(list.length);
        list.push({ ...p });
        return;
      }
      const prior = list[at];
      list[at] = { ...prior, ...p, owned: prior.owned === true || p.owned === true ? true : p.owned ?? prior.owned,
        bornNotAfterMs: prior.bornNotAfterMs ?? p.bornNotAfterMs };
    },
  };
}

/** A native helper's unreadable birth never becomes PID-only stop authority. */
function unidentifiedHelper(record) {
  return record?.role === 'native-helper' && !identityOf(record)?.birth;
}

/**
 * One recorded process against an OS lookup (`lookupProcessIdentities` row, or null when the lookup
 * failed): 'match' (alive, the recorded birth), 'gone', 'reused' (the pid now names another process)
 * or 'unknown'. Only 'match' may be killed; 'unknown' is never read as dead (S17).
 */
function checkRecordedProcess(recorded, seen) {
  if (!recorded?.pid) return 'gone';
  if (unidentifiedHelper(recorded)) return 'unknown';
  if (!seen) return 'unknown';
  if (!seen.present) return 'gone';
  if (recorded.processIdentity) {
    if (!processIdentity.linuxIdentity(recorded) || !processIdentity.linuxIdentity(seen)) return 'unknown';
    return compareIdentity(recorded, { pid: recorded.pid, processIdentity: seen.processIdentity }, 'same') ? 'match' : 'reused';
  }
  if (!seen.creationFileTime) return 'unknown';
  if (recorded.creationFileTimeUtc) {
    // The OS row read for the recorded PID: a CIM read (lookupProcessIdentities, the process table),
    // to the microsecond unless the lookup names its tick; compared at the coarser of the two.
    const row = { pid: recorded.pid, creationFileTime: String(seen.creationFileTime),
      birthTick: String(seen.birthTick ?? TICK.MICROSECOND) };
    if (!compareIdentity(recorded, row, 'same')) return 'reused';
    const want = normalizeExecutable(recorded.executable);
    const got = normalizeExecutable(seen.executable);
    return want && got && want !== got ? 'reused' : 'match';
  }
  // Without a recorded birth nothing proves the pid still names the run's process: a birth before
  // the record is consistent with a reuse that happened before it was written.
  const bornMs = engineActuator.fileTimeToMs(String(seen.creationFileTime));
  if (bornMs === null || !Number.isFinite(recorded.bornNotAfterMs)) return 'unknown';
  return bornMs <= recorded.bornNotAfterMs ? 'unknown' : 'reused';
}

function describeUnresolved(p, state) {
  const birth = p.creationFileTimeUtc ?? null;
  const ms = engineActuator.fileTimeToMs(birth ?? p.observedCreationFileTimeUtc);
  return { pid: p.pid, name: p.name ?? p.observedName ?? (p.executable ? path.win32.basename(p.executable) : 'unknown'), role: p.role,
    creationFileTimeUtc: birth, ...(p.processIdentity ? { processIdentity: p.processIdentity } : {}), bornNotAfterMs: p.bornNotAfterMs ?? null, owned: p.owned,
    startedAt: ms === null ? p.startedAt ?? 'unknown' : new Date(ms).toISOString(),
    observedCreationFileTimeUtc: p.observedCreationFileTimeUtc ?? null,
    reason: state === 'match' ? p.owned === false
      ? 'process is alive but ownership through its parent could not be proven' : 'recorded process is still alive'
      : birth ? 'process identity could not be read; ownership is unproven'
        : 'record has no start time; ownership is unproven' };
}

function forgetGuidance(runId) {
  return `Forgetting leaves processes running; a person decides. Only the recorded owner may clear tracking after an incomplete stop: dev-runner stop --run ${runId} --forget-unresolved. Processes left behind must be ended or inspected before another start.`;
}

/**
 * Whether `pid` is one of the run's recorded processes (same birth) or an identity-verified
 * descendant of one: every link up to the root is a parent born no later than its child.
 */
function isRunProcess(pid, processes, rows) {
  const byPid = new Map(rows.map((row) => [Number(row.ProcessId), row]));
  let row = byPid.get(Number(pid));
  const visited = new Set();
  while (row && !visited.has(Number(row.ProcessId))) {
    visited.add(Number(row.ProcessId));
    const current = row;
    const isRoot = processes.some((p) => hasPid(p, current.ProcessId) && checkRecordedProcess(p,
      { present: true, processIdentity: current.processIdentity, creationFileTime: current.CreationFileTimeUtc ?? null, executable: null }) === 'match');
    if (isRoot) return true;
    const parent = byPid.get(Number(row.ParentProcessId));
    // Each link up is a parent born no later than its child (both read from one process table: exact).
    if (!parent || birthOrder(parent, row, TICK.EXACT) === null || bornAfter(parent, row, TICK.EXACT)) return false;
    row = parent;
  }
  return false;
}
// </ledger-resolution>

/**
 * Spec F §5.3 (X09-F3): who holds a port. Never kills. `ownedByRun` is true only for a recorded
 * process of the run or a verified descendant; false is a foreign owner; null is unknown.
 */
async function portOwnerOf(port, processes, { table = () => processIdentity.readProcessTableAsync() } = {}) {
  const listening = port > 0 ? await isTcpListening(port, 500) : false;
  if (!listening) return { port, closed: true };
  const resolved = require('./lib/port-owner.cjs').resolveListenerPid(port);
  const owner = resolved.ok ? resolved : null;
  const ownerPid = owner?.pid ?? null;
  const commandLine = ownerPid ? process.platform === 'linux'
    ? processIdentity.readLinuxProcess(ownerPid)?.commandLine ?? null : await getCommandLineWindows(ownerPid) : null;
  let ownedByRun = null;
  if (ownerPid) {
    const snapshot = await table().catch(() => null);
    if (snapshot?.ok) ownedByRun = isRunProcess(ownerPid, processes, snapshot.table);
  }
  return {
    port,
    closed: false,
    ownerPid,
    ownerCommandLine: commandLine,
    boundAddresses: owner?.address ? [owner.address] : [],
    portOwner: { pid: ownerPid, commandLine, ownedByRun },
  };
}

/**
 * Stop a run (Spec F §5.3, S18). The runner is ASKED first; it quits its Engine through its loop
 * (the only actor on it), stops its frontend and answers. Only when the runner does not answer is
 * anything killed here, and every kill is identity-checked: the runner, then the Engine (with the
 * distinctive JS_ENGINE_STOP code), then the frontend. The Engine stop is recorded as `dev-cli`: the
 * intent before each action, the exit however the Engine ended. A port owner is never
 * killed. active.json is removed only when the stack is verified gone; otherwise it is kept with
 * `stopFailed` and the result is STOP_INCOMPLETE.
 */
async function stopRun(opts, {
  resolveTarget = resolveRunTarget,
  gracefulShutdown = maybeGracefulBackendShutdown,
  census = startStopExitCensus,
  inspectChild = inspectProcessIdentity,
  terminateChild = terminatePid,
  askRunner = askRunnerToStop,
  lookupIdentities = engineActuator.lookupProcessIdentities,
  terminateEngine = engineActuator.terminateWithCode,
  terminateProcess = engineActuator.terminateWithCode,
  portOwner = portOwnerOf,
  cliEngineRecorder = engineActuator.createCliEngineRecorder,
  stopEngineJob = (job, { ended = [] } = {}) => jobRun.stopJobRunSync({ recordPath: job.recordPath, host: job.host },
    { code: engineActuator.NON_DISTINCTIVE_KILL_CODE, endedIdentities: ended }),
  portWaitMs = 10_000,
} = {}) {
  const { run, runPath } = await resolveTarget(opts);
  const runDir = path.dirname(runPath);
  const trackedPath = path.join(runDir, 'stop-tracked-processes.json');
  const forgottenPath = path.join(runDir, 'forgotten-unresolved.json');
  const forgotten = await readJsonIfExists(forgottenPath);
  if (forgotten && !opts.forgetUnresolved) {
    const active = await readJsonIfExists(activePath);
    if (active?.runId === run.runId) await fsp.rm(activePath, { force: true });
    const message = `Stopped nothing because run ${run.runId} was forgotten. ${forgetGuidance(run.runId)}`;
    return { ok: false, outcome: 'FORGOTTEN_UNRESOLVED', runId: run.runId, killedPids: [], portsClosed: false,
      leftBehind: forgotten.leftBehind ?? [], note: message, error: { code: 'RUN_FORGOTTEN', message } };
  }
  if (opts.forgetUnresolved) {
    const caller = opts.callerIdentity ?? resolveCallerIdentity(opts.sessionId);
    if (!ownerPresence.isSelf({ holder: run.holder }, caller)) return { ok: false, outcome: 'FORGET_REFUSED',
      runId: run.runId, error: { code: 'OWNER_CONFLICT', message: forgetGuidance(run.runId) } };
    if (forgotten) return { ok: false, outcome: 'FORGET_REFUSED', runId: run.runId,
      error: { code: 'ALREADY_FORGOTTEN', message: `This run is already forgotten. ${forgetGuidance(run.runId)}` },
      leftBehind: forgotten.leftBehind ?? [] };
    const lastStop = await readJsonIfExists(path.join(runDir, 'stop-report.json'));
    if (lastStop?.outcome !== STOP_OUTCOME.STOP_INCOMPLETE) return { ok: false, outcome: 'FORGET_REFUSED',
      runId: run.runId, error: { code: 'FORGET_NOT_INCOMPLETE', message: `The last stop was not STOP_INCOMPLETE. ${forgetGuidance(run.runId)}` } };
  }
  const dataDirAbs = run?.dataDir ? path.resolve(repoRoot, run.dataDir) : null;
  const disposition = opts.disposition ?? null;
  const actor = opts.actor ?? null;
  const victim = opts.victim ?? null;
  // Tempdoc 542 §B Layer 4: when a `force` takeover interrupts MUST_COMPLETE or
  // UNSAFE_TO_INTERRUPT ops, the caller passes the list here so the stop-report names them.
  const criticalOpsInterrupted = opts.criticalOpsInterrupted ?? null;
  const interruptibleWithLossInterrupted = opts.interruptibleWithLossInterrupted ?? null;
  const runId = run.runId;
  const apiPort = Number(run?.apiPortActual);
  const uiPort = Number(run?.uiPortActual);
  const reason = stopRequestReasonFor(disposition);

  const killedPids = [];
  const errors = [];
  // Tempdoc 730 B2: per-PID liveness probe taken BEFORE each kill attempt — distinguishes
  // "backend already dead, reaper cleaned up the shell" from "backend live, reaper killed an
  // abandoned-but-healthy stack" (the ambiguity §THEORIZE B flagged in `reaped_abandoned`).
  // Each entry now also carries the identity verdict the kill decision was made on.
  const pidLiveness = [];

  // The exit ledger first: a recorded identity whose exit it records (same PID and birth) is gone,
  // and it is never passed to an OS lookup or probe in any phase. Its PID may name another process
  // by now, possibly one whose birth cannot be read, which never makes it ours. One reader serves
  // the stop and its census, so each ledger file is parsed once and later reads add what was appended.
  const ledgerReader = dataDirAbs ? ledgerLib.createIncrementalReader() : null;
  // This stop's own Engine writer, bound in memory to the Engine identity this stop checked before it
  // wrote that Engine's intent and exit (stopWriterBinding). Never read back from the ledger; a later
  // stop has none, so it looks the Engine up.
  const stopBindings = [];
  // <ledger-resolution> What the ledger says of the tracked identities (exitCensus.ledgerResolution):
  // `ledgerEnded` the identities it proves ended (for the job stop), `provenEnded(p)` whether the
  // tracked record `p` is one of them. The whole tracked record is compared (compareIdentity 'same'):
  // its PID and birth at the coarser of its own and the ledger's measured tick, and its childId, owner
  // and incarnation where it records them; and every start of the run it agrees with must be proven.
  let ledgerEnded = [];
  let ledgerResolved = null;
  let ledgerVersion = null;
  const readLedgerEnded = () => {
    if (!ledgerReader) return ledgerEnded;
    try {
      const ledger = readRunLedger(dataDirAbs, ledgerReader);
      // Judged again only when a ledger file changed since the last judgement.
      if (ledgerReader.version() !== ledgerVersion) {
        ledgerVersion = ledgerReader.version();
        ledgerResolved = ledgerResolution(ledgerView(ledger, runId, { stopBindings }));
        ledgerEnded = ledgerResolved.ended;
      }
    } catch (err) {
      errors.push(`exit ledger: ${err?.message ?? err}`);
    }
    return ledgerEnded;
  };
  const provenEnded = (p) => Boolean(p?.pid) && Boolean(p.creationFileTimeUtc || processIdentity.linuxIdentity(p)) && Boolean(ledgerResolved?.isEnded(p));
  // Each identity resolved by its ledger exit instead of a lookup, once.
  const resolvedByLedger = [];
  const noteResolved = (p) => {
    if (!resolvedByLedger.some((r) => compareIdentity(r, p, 'same'))) resolvedByLedger.push(describeResolved(p));
  };
  // </ledger-resolution>
  readLedgerEnded();

  /**
   * Ends one recorded process, never its tree, with the non-distinctive code `taskkill /F` gives,
   * through a handle on which its recorded birth is checked again: an inspection before it never
   * licenses a kill by pid alone (Spec F §5.3). Its children are covered by their creator's ordered
   * stop and by the Engine's manifest and exit ledger. `row` is its fallback.processes entry,
   * marked when the pid names another process by then.
   */
  const terminateRecorded = async (p, row) => {
    if (process.platform !== 'win32' && (process.platform !== 'linux' || !p.processIdentity)) {
      errors.push(`${p.role} pid ${p.pid}: identity-checked termination is unavailable on this platform`);
      return;
    }
    const result = await Promise.resolve(terminateProcess(p.pid, p.processIdentity ?? p.creationFileTimeUtc,
      engineActuator.NON_DISTINCTIVE_KILL_CODE))
      .catch(() => ({ ok: false, outcome: 'failed', status: null }));
    if (row) row.termination = result;
    if (result?.ok) killedPids.push(p.pid);
    if (result?.outcome === 'mismatch' && row) row.pidReused = true;
    if (!result?.ok && !['mismatch', 'gone'].includes(result?.outcome)) {
      errors.push(`${p.role} pid ${p.pid}: termination ${result?.outcome ?? 'failed'} (status ${result?.status ?? 'none'})`);
    }
  };

  const recordWrittenMs = await fsp.stat(runPath).then((s) => s.mtimeMs, () => NaN);
  const savedSnapshot = await readJsonIfExists(trackedPath);
  // Snapshot only the already-recorded Linux job members before orderly stop retires their files.
  // This is reporting evidence, never additional cleanup authority or a fresh descendant search.
  const recordedLinuxHelpers = process.platform === 'linux'
    ? [run.engineJob, run.frontendJob].flatMap(job => {
      const record = jobRun.readRunRecord(job);
      return record?.backend === 'linux-bound-child-v1' ? record.members ?? [] : [];
    }) : [];
  const processes = recordedProcesses(run, recordWrittenMs);
  const scope = Number(run.incarnation) || 1;
  for (const p of processes) {
    const bound = savedSnapshot?.legacyBounds?.find(b => b.role === p.role && b.pid === p.pid && b.scope === scope);
    if (p.basis === 'record-time' && bound) p.bornNotAfterMs = bound.bornNotAfterMs;
  }
  const legacyBounds = processes.filter(p => p.pid && p.basis === 'record-time')
    .map(p => ({ role: p.role, pid: p.pid, scope, bornNotAfterMs: p.bornNotAfterMs }));
  const byRole = Object.fromEntries(processes.map((p) => [p.role, p]));
  const runnerPid = byRole.runner.pid;
  // Tempdoc 606 3b: the supervisor's own reaper calls this in-process; it never acts on itself.
  const inProcess = runnerPid === process.pid;
  const gracefulShutdownMarkerPath = path.join(runDir, 'graceful-shutdown.json');
  let phaseRows = new Map();
  // When the latest lookup was asked: a process its rows say nothing of may have been born after it.
  let phaseAskedMs = null;
  // The PIDs the latest lookup was asked of: its rows say nothing of any other.
  let phaseAskedPids = new Set();
  // Adds each unproven start of the ledger the stop did not know yet, once (set with knownUnproven).
  let learnUnproven = () => [];
  const lookupPhase = async (entries) => {
    readLedgerEnded();
    // A start this read of the ledger shows for the first time is asked of too (learnUnproven).
    const learned = learnUnproven();
    const ids = [...new Set([...entries, ...learned].filter(p => p?.pid && !provenEnded(p)).map(p => p.pid))];
    phaseAskedMs = Date.now();
    phaseAskedPids = new Set(ids);
    phaseRows = ids.length ? await Promise.resolve().then(() => lookupIdentities(ids)).catch(() => null) : new Map();
  };
  // What the first identity-checked lookup of this stop observed of the recorded identities it found
  // ended, before the stop acted on anything (round 11): the recorded PID absent from a lookup that
  // answered, or naming a process whose readable birth is not the recorded one (for a record without
  // a birth, one born after the instant its process was seen alive, round 12). A lookup that failed, a
  // row whose birth cannot be read, or a record with neither a birth nor such an instant adds nothing
  // (S4 stays incomplete). The stop's own actions are never handed to the census (Spec B step 9).
  // The census judges each identity it accounts against these observations (lookupEndedIdentities).
  const lookupEnded = [];
  // The observations so far by PID (an index only: each candidate is then matched by compareIdentity).
  const lookupEndedByPid = new Map();
  const noteLookupEnded = (entries, observedAtMs) => {
    for (const p of entries) {
      if (!p?.pid || (!p.creationFileTimeUtc && !p.processIdentity && !Number.isFinite(p.bornNotAfterMs)) || provenEnded(p)
        || !phaseRows?.has(p.pid)) continue;
      const observation = lookupObservation(p, phaseRows.get(p.pid), observedAtMs);
      const bucket = lookupEndedByPid.get(p.pid) ?? [];
      if (!observation || bucket.some((o) => compareIdentity(o, p, 'same'))) continue;
      lookupEnded.push(observation);
      lookupEndedByPid.set(p.pid, [...bucket, observation]);
    }
  };
  const lookupOne = async (p) => {
    if (!p?.pid) return 'gone';
    if (provenEnded(p)) {
      noteResolved(p);
      return 'gone';
    }
    const row = phaseRows?.get(p.pid) ?? (phaseRows ? { present: false } : null);
    if (!unidentifiedHelper(p) && !p.creationFileTimeUtc && !p.processIdentity && row?.present && !Number.isFinite(p.bornNotAfterMs)) {
      p.bornNotAfterMs = Date.now();
      p.basis = 'first-observed-alive';
    }
    p.observedName = row?.name ?? (row?.executable ? path.win32.basename(row.executable) : null);
    p.observedCreationFileTimeUtc = row?.creationFileTime ?? null;
    return checkRecordedProcess(p, row);
  };

  // Identities left unresolved by an earlier attempt are re-checked, rather than treating a failed
  // helper or a self-exited root as permanent debt. They are reported, never killed: only a
  // process this run started, or a helper the Engine registered, is ever ended.
  // A list of distinct records, never merged by part of an identity (trackedIdentities).
  const trackedSet = trackedIdentities();
  const tracked = trackedSet.list;
  const remember = trackedSet.add;
  // A record from an earlier tree-era stop: its listed identities stay reported until resolved.
  for (const tree of run.stopUnresolvedTrees ?? []) {
    for (const p of tree.processes ?? []) remember({ ...p, role: `${tree.role} descendant` });
    for (const pid of tree.descendantsNotEnded ?? []) {
      if (!(tree.processes ?? []).some(p => p.pid === pid)) remember({ pid, role: `${tree.role} descendant`, creationFileTimeUtc: null });
    }
  }
  for (const p of run.stopTrackedProcesses ?? []) remember(p);
  for (const p of savedSnapshot?.processes ?? []) remember(p);
  const manifest = dataDirAbs ? await readJsonIfExists(path.join(dataDirAbs, 'runtime', 'manifest.json')) : null;
  const registered = Array.isArray(manifest?.children) ? manifest.children : [];
  const helperProcesses = registered.map(child => {
    const startMs = Date.parse(child.startedAt);
    return { pid: Number(child.pid), role: 'registered helper', name: child.executable ? path.win32.basename(child.executable) : child.kind,
      executable: child.executable, startedAt: child.startedAt,
      ...(child.processIdentity ? { processIdentity: child.processIdentity } : {}),
      creationFileTimeUtc: child.processIdentity ? null : child.creationFileTimeUtc ?? (Number.isFinite(startMs)
        ? String(BigInt(startMs) * 10000n + 116444736000000000n) : null),
      birthTick: child.processIdentity ? null : child.creationFileTimeUtc ? 10 : 10000, owned: false };
  });
  for (const p of helperProcesses) remember(p);
  // <ledger-resolution> Every identity the stop knows of and cannot prove ended is looked up, the
  // fallback the design rests on: the ledger's unproven starts (a predecessor's child another Engine
  // stopped, whose receipt proves nothing, its manifest entry removed) and the identities an earlier
  // stop report of the run names, never left out because no manifest entry or tracked record names
  // them any more. Each is reported, never ended, while it runs or cannot be read (STOP_INCOMPLETE);
  // one the lookup finds absent or reborn is accounted ended (noteLookupEnded). Its PID and birth are
  // compared only through compareIdentity, against the records already listed with the same PID.
  const knownByPid = new Map();
  const knownAlready = (p) => {
    const bucket = knownByPid.get(p.pid) ?? [];
    if (bucket.some((q) => compareIdentity(q, p, 'same'))) return true;
    knownByPid.set(p.pid, [...bucket, p]);
    return false;
  };
  for (const p of [...processes, ...tracked]) if (p?.pid && (p.creationFileTimeUtc || p.processIdentity)) knownAlready(p);
  const knownUnproven = [];
  const learnUnprovenFrom = (identities) => {
    const learned = [];
    for (const u of identities) {
      if (provenEnded(u) || knownAlready(u)) continue;
      const entry = { ...u, role: u.role ?? 'recorded identity', name: u.role ?? 'unknown' };
      knownUnproven.push(entry);
      learned.push(entry);
    }
    return learned;
  };
  // A start a later read of the ledger shows (one recorded while this stop runs) joins them, each
  // time the ledger is read again for a lookup and before the final check: it is looked up and checked
  // like the rest, and reported, never ended, while it runs or cannot be read.
  learnUnproven = () => (opts.forgetUnresolved ? [] : learnUnprovenFrom(ledgerResolved?.unproven ?? []));
  if (!opts.forgetUnresolved) learnUnprovenFrom([...(ledgerResolved?.unproven ?? []), ...earlierReportIdentities(runDir)]);
  // </ledger-resolution>
  const allEntries = () => [...processes, ...tracked, ...knownUnproven].filter(p => p.pid && p.pid !== process.pid);
  // The stop itself is never one of the identities it looks up.
  const isThisProcess = (p) => p?.pid === process.pid;
  // Arm OS exit handles while the initial identity lookup starts. Actions still wait for both.
  let censusError;
  const pendingCensus = !opts.forgetUnresolved
    ? Promise.resolve(census(run, dataDirAbs, runDir, process.env.JUSTSEARCH_EXIT_CENSUS_SCOPE, { ledgerReader, stopBindings }))
      .catch(error => { censusError = error; return null; }) : null;
  if (!opts.forgetUnresolved) {
    await lookupPhase(allEntries());
    noteLookupEnded(allEntries(), phaseAskedMs);
    for (const p of tracked) await lookupOne(p);
    await writeJsonAtomic(runPath, { ...run, stopTrackedProcesses: [...tracked] });
    await writeJsonAtomic(trackedPath, { processes: [...tracked], legacyBounds });
  } else {
    // Explicit escape hatch: inspection only. No shutdown request, runner request, or terminator.
    const leftBehind = [];
    // Admission can read this marker while inspection is pending: retain every recorded identity
    // until the lookup proves it resolved, rather than exposing an empty cleanup/start barrier.
    await writeJsonAtomic(forgottenPath, { at: nowIso(),
      leftBehind: allEntries().map(p => describeUnresolved(p, 'unknown')) });
    if (dataDirAbs) {
      const manifest = await readJsonIfExists(path.join(dataDirAbs, 'runtime', 'manifest.json'));
      for (const child of manifest?.children ?? []) remember({ pid: Number(child.pid), role: 'registered helper',
        name: child.executable ?? child.kind, creationFileTimeUtc: child.creationFileTimeUtc ?? null,
        startedAt: child.startedAt ?? 'unknown' });
    }
    await lookupPhase(allEntries());
    for (const p of [...processes, ...tracked].filter(p => p.pid)) {
      const state = await lookupOne(p);
      if (state === 'match' || state === 'unknown') leftBehind.push(describeUnresolved(p, state));
    }
    await writeJsonAtomic(runPath, { ...run, pids: {}, identities: {}, stopTrackedProcesses: [],
      stopUnresolvedTrees: undefined, forgottenUnresolved: { at: nowIso(), leftBehind } });
    await writeJsonAtomic(trackedPath, { processes: [] });
    await writeJsonAtomic(forgottenPath, { at: nowIso(), leftBehind });
    const active = await readJsonIfExists(activePath);
    if (active?.runId === runId) await fsp.rm(activePath, { force: true });
    return { ok: true, outcome: 'FORGOTTEN_UNRESOLVED', runId, killedPids: [], portsClosed: false,
      leftBehind, note: forgetGuidance(runId) };
  }
  const exitCensus = await pendingCensus;
  if (censusError) throw censusError;

  // 1. Ask the holder (only a runner that listens: older runners never read the control file).
  let runnerAnswer = null;
  if (!inProcess && runnerPid && run?.control?.stopRequest && isPidAlive(runnerPid)) {
    runnerAnswer = await askRunner({ runDir, runId, runnerPid, reason,
      detail: reason === 'takeover' ? 'takeover' : (opts.detail ?? null), actor });
  }
  const runnerStopped = runnerAnswer?.outcome === 'stopped';

  let gracefulBackendShutdown;
  let fallback = null;
  // The Engine exit the fallback caused, recorded once the census has observed it.
  let pendingEngineExit = null;
  // Whether the fallback already took the post-kill identity lookup the exit checks below read.
  let identitiesRefreshed = false;
  let childCleanup;
  // The registered-helper step: each helper the Engine registered, ended on a handle showing its
  // recorded start. It comes after the ordered quit and before the recorded identities and the
  // Engine's job, so nothing it would stop in order is ended first by a job closing.
  const runHelperStep = async () => {
    if (dataDirAbs && inspectChild === inspectProcessIdentity && terminateChild === terminatePid) {
      // One shared snapshot before helper actions and one shared verification after the batch.
      const childCleanup = [];
      const pending = [];
      for (let i = 0; i < registered.length; i++) {
        const child = registered[i], p = helperProcesses[i];
        const row = phaseRows?.get(p.pid) ?? (phaseRows ? { present: false } : null);
        const record = { id: child.id, ...p };
        const state = await lookupOne(p);
        if (state === 'gone' || state === 'reused') {
          childCleanup.push({ ...record, outcome: state === 'gone' ? 'dead' : 'identity-mismatch' }); continue;
        }
        if (p.processIdentity) {
          if (state !== 'match') {
            childCleanup.push({ ...record, outcome: state === 'unknown' ? 'unknown-identity' : 'identity-mismatch' }); continue;
          }
          let accepted = false;
          try { accepted = await terminateChild(p.pid, { processIdentity: p.processIdentity }); } catch {}
          pending.push({ ...record, killAccepted: accepted }); continue;
        }
        const expected = Date.parse(child.startedAt);
        const actual = engineActuator.fileTimeToMs(row?.creationFileTime);
        if (state !== 'match' || !Number.isFinite(expected) || actual === null
            || !normalizeExecutable(child.executable) || !normalizeExecutable(row?.executable)) {
          childCleanup.push({ ...record, outcome: 'unknown-identity' }); continue;
        }
        if (expected !== actual || normalizeExecutable(child.executable) !== normalizeExecutable(row.executable)) {
          childCleanup.push({ ...record, outcome: 'identity-mismatch' }); continue;
        }
        let accepted = false;
        try { accepted = await terminateChild(p.pid, { startedAtMs: actual }); } catch { /* verification below */ }
        pending.push({ ...record, killAccepted: accepted });
      }
      if (pending.length) await lookupPhase(allEntries());
      for (const child of pending) {
        const state = await lookupOne(child);
        const outcome = state === 'gone' || state === 'reused' ? 'terminated'
          : state === 'unknown' ? 'unknown-exit' : 'termination-failed';
        childCleanup.push({ ...child, outcome });
        if (outcome === 'terminated' && child.killAccepted && !killedPids.includes(child.pid)) killedPids.push(child.pid);
      }
      for (const child of childCleanup) if (!['dead', 'terminated', 'identity-mismatch'].includes(child.outcome)) {
        errors.push(`registered helper pid ${child.pid}: ${child.outcome}`);
      }
      return childCleanup;
    }
    return dataDirAbs ? cleanupTerminalChildren(dataDirAbs, killedPids, errors, inspectChild, terminateChild) : [];
  };
  if (runnerStopped) {
    gracefulBackendShutdown = { outcome: 'exited', reason: 'runner_quit', requested: true, httpStatus: null,
      error: null, waitedMs: runnerAnswer.waitedMs };
    // The runner exits right after it answers.
    const until = Date.now() + 5000;
    while (isPidAlive(runnerPid) && Date.now() < until) {
      // eslint-disable-next-line no-await-in-loop
      await new Promise((r) => setTimeout(r, 100));
    }
  } else {
    // Only fallback actions need a fresh identity batch here. A cooperative stop goes directly
    // to the final identity lookup; another lookup would repeat that stop phase.
    await lookupPhase(allEntries());
    fallback = { reason: runnerAnswer?.outcome ?? (inProcess ? 'in_process'
      : !runnerPid ? 'no_runner_recorded' : !run?.control?.stopRequest ? 'runner_does_not_listen' : 'runner_not_alive'),
    processes: [] };
    // Tempdoc 819 §D: the graceful ordered shutdown first, before the runner's job takes the Engine
    // with it — but never through a pid that now names another process.
    const backendBefore = await lookupOne(byRole.backend);

    // Spec F §5.3 (S15): for a runner that writes the ledger and an Engine seen running, every action
    // on the Engine is announced as `dev-cli` before it is taken, and its exit is recorded however
    // it ended: on the quit, with the runner whose job held it, or killed here.
    const stopDetail = reason === 'takeover' ? 'takeover' : (opts.detail ?? null);
    const engineIncarnation = { session: runId, n: Number.isInteger(run?.incarnation) && run.incarnation > 0 ? run.incarnation : null };
    const engineRecords = dataDirAbs && run?.control?.stopRequest && backendBefore === 'match'
      ? cliEngineRecorder({ dataDir: dataDirAbs, incarnation: engineIncarnation, ledgerReader })
      : null;
    // The identity this process checked ('match': the recorded PID and birth against the OS row) before
    // writing; with the writer it then wrote as, it binds this stop's records to that start (round 10).
    const engineChecked = backendBefore === 'match' && (byRole.backend.creationFileTimeUtc || byRole.backend.processIdentity)
      ? { pid: byRole.backend.pid, ...(byRole.backend.processIdentity ? { processIdentity: byRole.backend.processIdentity } : {}), creationFileTimeUtc: byRole.backend.creationFileTimeUtc, birthTick: byRole.backend.birthTick ?? null }
      : null;
    if (engineRecords) fallback.ledger = [];
    const recordEngine = (kind, fields) => {
      if (!engineRecords) return null;
      try {
        const written = engineRecords[kind]({ reason: 'QUIT', detail: stopDetail, ...fields });
        fallback.ledger.push(written);
        const writer = written?.written ? engineRecords.writerIdentity?.() : null;
        if (writer && engineChecked && !stopBindings.length) {
          stopBindings.push({ writer, incarnation: engineIncarnation, checked: engineChecked });
        }
        return written;
      } catch (err) {
        errors.push(`exit ledger: ${err?.message ?? err}`);
        return null;
      }
    };

    // The Engine currently logs an HTTP incarnation mismatch but still quits. A request body
    // cannot establish ownership: only a matched recorded birth licenses this fallback request.
    const canRequestShutdown = backendBefore === 'match';
    if (canRequestShutdown && Number.isFinite(apiPort) && apiPort > 0) {
      recordEngine('intent', { mode: 'COOPERATIVE', channel: 'HTTP_SHUTDOWN', deadlineAt: Date.now() + 17_000 });
    }
    gracefulBackendShutdown = !canRequestShutdown
      ? { outcome: 'not_attempted', reason: backendBefore === 'reused' ? 'pid_reused'
        : 'ownership_not_proven',
      requested: false, httpStatus: null, error: null, waitedMs: null }
      : await gracefulShutdown(apiPort, byRole.backend.pid ?? NaN, gracefulShutdownMarkerPath);
    const engineQuit = gracefulBackendShutdown.outcome === 'exited';
    // Killing the runner ends the Engine in its job, so the forced stop is announced before that kill.
    if (!engineQuit) recordEngine('intent', { mode: 'NOW', channel: 'TERMINATE' });
    let engineEnding = engineQuit ? { endedBy: 'cooperative', terminateCalledAt: null } : null;

    const killChecked = async (role) => {
      const p = byRole[role];
      if (!p.pid) {
        pidLiveness.push({ role, pid: null, aliveBeforeKill: null });
        return false;
      }
      const verdict = await lookupOne(p);
      pidLiveness.push({ role, pid: p.pid, aliveBeforeKill: verdict === 'match' ? true : verdict === 'unknown' ? null : false,
        identity: verdict });
      const row = { role, pid: p.pid, basis: p.basis, identity: verdict,
        ...(verdict === 'reused' ? { pidReused: true } : {}) };
      fallback.processes.push(row);
      if (verdict === 'unknown') errors.push(`${role} pid ${p.pid}: identity could not be verified; not killed`);
      if (verdict !== 'match') return false;
      await terminateRecorded(p, row);
      return killedPids.includes(p.pid);
    };

    // The registered-helper step before any identity is ended: killing the runner closes its job,
    // and with it the Engine's job host, which would end the helpers out of order. A quit that was
    // asked for is a new phase (the Engine may have ended its helpers), so it gets a fresh batch;
    // without one, the fallback's batch above still describes every helper.
    if (gracefulBackendShutdown?.requested) await lookupPhase(allEntries());
    childCleanup = await runHelperStep();

    // 2. The runner (never this process), only its recorded identity. A current runner's children
    //    are in its job; the Engine and the frontend are each ended by their own recorded identity
    //    in the next steps, never through the runner's parent links.
    const runnerKillAt = Date.now();
    const runnerKilled = inProcess ? false : await killChecked('runner');

    // 3. The Engine, unless it already exited on request.
    if (engineQuit) {
      pidLiveness.push({ role: 'backend', pid: byRole.backend.pid, aliveBeforeKill: true });
    } else if (byRole.backend.pid) {
      const p = byRole.backend;
      if (runnerKilled && !provenEnded(p)) {
        // The runner's job closes with it; give the Engine a moment to go the same way.
        const until = Date.now() + 2000;
        while (isPidAlive(p.pid) && Date.now() < until) {
          // eslint-disable-next-line no-await-in-loop
          await new Promise((r) => setTimeout(r, 100));
        }
      }
      const verdict = await lookupOne(p);
      pidLiveness.push({ role: 'backend', pid: p.pid, aliveBeforeKill: verdict === 'match' ? true
        : verdict === 'unknown' ? null : false, identity: verdict });
      const backendRow = { role: 'backend', pid: p.pid, basis: p.basis, identity: verdict,
        ...(verdict === 'reused' ? { pidReused: true } : {}) };
      fallback.processes.push(backendRow);
      if (verdict === 'unknown') errors.push(`backend pid ${p.pid}: identity could not be verified; not killed`);
      const endedWithRunner = runnerKilled ? { endedBy: 'runner', terminateCalledAt: runnerKillAt } : null;
      if (verdict === 'match') {
        // 'match' needs a recorded birth (checkRecordedProcess); both kills re-check it on their handle.
        const terminateCalledAt = Date.now();
        const result = await terminateEngine(p.pid, p.processIdentity ?? p.creationFileTimeUtc, ledgerLib.JS_ENGINE_STOP);
        if (result?.ok) {
          killedPids.push(p.pid);
          engineEnding = { endedBy: 'terminate', terminateCalledAt };
        } else if (!['mismatch', 'gone'].includes(result?.outcome)) {
          await terminateRecorded(p, backendRow);
          if (killedPids.includes(p.pid)) engineEnding = { endedBy: 'identity-fallback', terminateCalledAt };
        } else {
          engineEnding = endedWithRunner;
        }
      } else if (verdict === 'gone' || verdict === 'reused') {
        engineEnding = endedWithRunner;
      }
    } else {
      pidLiveness.push({ role: 'backend', pid: null, aliveBeforeKill: null });
    }

    // 4. The frontend, by its recorded identity only.
    await killChecked('frontend');
    await lookupPhase(allEntries());
    identitiesRefreshed = true;

    // The Engine's exit, once it is verifiably gone and nothing else can record it: the runner is
    // gone too (it would otherwise record its own child's exit), unless this CLI killed the Engine.
    if (engineRecords && engineEnding) {
      let engineAfter = await lookupOne(byRole.backend);
      const runnerAfter = inProcess ? 'match' : await lookupOne(byRole.runner);
      const killedHere = engineEnding.endedBy === 'terminate' || engineEnding.endedBy === 'identity-fallback';
      if ((engineAfter === 'gone' || engineAfter === 'reused')
          && (killedHere || runnerAfter === 'gone' || runnerAfter === 'reused')) {
        const ending = { ...engineEnding,
          runnerJobClose: engineEnding.endedBy === 'runner' && byRole.runner.creationFileTimeUtc
            && (runnerAfter === 'gone' || runnerAfter === 'reused') ? {
              pid: byRole.runner.pid, creationFileTimeUtc: byRole.runner.creationFileTimeUtc,
              osCheck: runnerAfter === 'gone' ? 'GONE' : 'REUSED', containment: 'LIBUV_JOB',
            } : null };
        // Spec B §4 step 1: a handle being stopped is STOPPING when it exits.
        pendingEngineExit = (observed) => recordEngine('exit', { ...ending, observed, phase: 'STOPPING' });
      }
    }
  }
  if (!identitiesRefreshed) await lookupPhase(allEntries());
  if (childCleanup === undefined) childCleanup = await runHelperStep();
  // A runner that answered the stop ran the registered-helper step itself, right after the
  // Engine's exit: a helper this step then finds gone is reported as that step ended it.
  if (runnerStopped && Array.isArray(runnerAnswer?.registeredHelpers)) {
    const byRunner = runnerAnswer.registeredHelpers.filter((c) => c?.pid);
    childCleanup = childCleanup.map((c) => {
      const earlier = c.outcome === 'dead' && byRunner.find((r) => Number(r.pid) === Number(c.pid)
        && (c.processIdentity ? compareIdentity(r, c, 'same') === true
          : (r.startedAt ?? null) === (c.startedAt ?? null)));
      return earlier ? { ...c, outcome: earlier.outcome, killAccepted: earlier.killAccepted ?? null, stoppedBy: 'runner' } : c;
    });
    for (const r of byRunner) {
      if (!childCleanup.some((c) => Number(c.pid) === Number(r.pid))) childCleanup.push({ ...r, stoppedBy: 'runner' });
    }
    for (const c of childCleanup) {
      if (c.stoppedBy === 'runner' && c.outcome === 'terminated' && c.killAccepted && !killedPids.includes(Number(c.pid))) {
        killedPids.push(Number(c.pid));
      }
    }
  }

  // 5. Last, the Engine's job: whatever the Engine left running after the ordered quit, the
  //    registered-helper step and the recorded identities. A runner that answered the stop ended
  //    its held jobs as its own last step and reports what they ended; otherwise the job's recorded host is ended here (on a handle showing its recorded birth),
  //    which ends every member, and each recorded member is verified gone. Either way each process
  //    the job's termination ended is listed by identity.
  //    The job stop skips each identity the ledger shows ended (read again now).
  let engineJob = null;
  const answeredJob = runnerAnswer?.engineJob ?? null;
  const recordedJob = run?.engineJob?.recordPath && run.engineJob.host?.pid ? run.engineJob : null;
  if (runnerStopped && answeredJob?.state === 'ended') {
    engineJob = { source: 'runner', incarnation: answeredJob.incarnation ?? null, outcome: 'ended',
      endedByJob: jobIdentities(answeredJob.endedByJob), survivors: [] };
  } else if (recordedJob && ['win32', 'linux'].includes(process.platform)) {
    if (!fs.existsSync(recordedJob.recordPath)
        && (!recordedJob.startupFailure || recordedJob.startupFailure.cleanup?.ok === true)) {
      // Retired by the runner once its host closed the job as `ended`.
      engineJob = { source: 'record', incarnation: recordedJob.incarnation ?? null, outcome: 'gone',
        endedByJob: jobIdentities(answeredJob?.endedByJob), survivors: [] };
    } else {
      const result = await Promise.resolve().then(() => stopEngineJob(recordedJob, { ended: readLedgerEnded() }))
        .catch(() => ({ ok: false, outcome: 'failed' }));
      engineJob = { source: 'stop', incarnation: recordedJob.incarnation ?? null, outcome: result?.outcome ?? 'failed',
        host: result?.host ?? null, endedByJob: jobIdentities(result?.endedByJob), survivors: jobIdentities(result?.survivors),
        ...(Array.isArray(result?.endedPerLedger) ? { endedPerLedger: jobIdentities(result.endedPerLedger) } : {}) };
      if (result?.host === 'terminated') {
        for (const p of engineJob.endedByJob) if (!killedPids.includes(p.pid)) killedPids.push(p.pid);
      }
      if (!result?.ok) errors.push(`Engine job: ${engineJob.outcome}${engineJob.survivors.length
        ? ` (${engineJob.survivors.map((p) => `pid ${p.pid}`).join(', ')} still running)` : ''}`);
    }
  }
  if (process.platform === 'linux' && run.frontendJob) {
    const result = jobRun.settleRunRecord(run.frontendJob);
    if (!result.ok) errors.push(`Frontend helpers: ${result.outcome}`);
    for (const member of result.survivors ?? []) remember({ ...member, role: 'frontend helper', owned: true });
  }
  const jobUnresolved = engineJob && !['ended', 'gone', 'terminated'].includes(engineJob.outcome)
    ? (engineJob.survivors.length ? engineJob.survivors : [{ pid: recordedJob?.host?.pid ?? null,
      creationFileTimeUtc: recordedJob?.host?.creationFileTimeUtc ?? null }])
      .map((p) => ({ ...p, role: 'Engine job member', state: 'unknown' })) : [];

  // <ledger-resolution> The outcome is decided by a lookup taken after the stop acted (round 12): every
  // other step that acts looks up again after it, the Engine job step does not, so when it ran the
  // identities are looked up once more, and one it just ended is seen gone, never judged on the lookup
  // from before it acted. The ledger as it is now: a start it shows that the latest lookup was not
  // asked of is looked up before the final check too, which otherwise would take it for absent.
  readLedgerEnded();
  learnUnproven();
  if (engineJob?.source === 'stop' || knownUnproven.some((p) => p.pid && !provenEnded(p) && !phaseAskedPids.has(p.pid))) {
    await lookupPhase(allEntries());
  }
  // </ledger-resolution>
  const unresolvedProcesses = [];
  for (const p of [...tracked, ...knownUnproven]) {
    const state = await lookupOne(p);
    if (state === 'match' || state === 'unknown') unresolvedProcesses.push({ ...p, state });
  }

  // Tempdoc 819 §D: the marker's job is done once we reach here, whatever the outcome.
  try { await fsp.rm(gracefulShutdownMarkerPath, { force: true }); } catch (_) { /* best-effort */ }

  // Is the stack gone? Every recorded process (but this one) must be gone or its pid reused.
  const remaining = processes.filter((p) => p.pid && p.pid !== process.pid).filter((p) => {
    if (!provenEnded(p)) return true;
    noteResolved(p);
    return false;
  });
  const after = phaseRows;
  const survivors = remaining
    .map((p) => ({ ...p, observedName: after?.get(p.pid)?.name ?? p.observedName,
      observedCreationFileTimeUtc: after?.get(p.pid)?.creationFileTime ?? p.observedCreationFileTimeUtc,
      state: checkRecordedProcess(p, after ? after.get(p.pid) ?? null : null) }))
    .filter((p) => p.state === 'match' || p.state === 'unknown');
  for (const s of survivors) errors.push(`${s.role} pid ${s.pid} is ${s.state === 'match' ? 'still running' : 'of unknown state'} after the stop`);

  // Ports: wait (bounded) for the run's own listeners to close. A port owner is never killed.
  const deadline = Date.now() + portWaitMs;
  // A port's owner is matched only with identities not proven ended.
  const portCandidates = processes.filter((p) => !provenEnded(p));
  let apiInfo = await portOwner(apiPort, portCandidates);
  let uiInfo = await portOwner(uiPort, portCandidates);
  const waitingOnRun = (info) => !info.closed && info.portOwner?.ownedByRun !== false;
  while (Date.now() < deadline && (waitingOnRun(apiInfo) || waitingOnRun(uiInfo))) {
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 250));
    // eslint-disable-next-line no-await-in-loop
    apiInfo = await portOwner(apiPort, portCandidates);
    // eslint-disable-next-line no-await-in-loop
    uiInfo = await portOwner(uiPort, portCandidates);
  }
  if (!apiInfo.closed) errors.push(`API port ${apiPort} still listening after stop timeout`);
  if (!uiInfo.closed) errors.push(`UI port ${uiPort} still listening after stop timeout`);
  const foreign = [apiInfo, uiInfo].find((info) => !info.closed && info.portOwner?.ownedByRun === false);
  const foreignPortOwner = foreign ? { port: foreign.port, ...foreign.portOwner } : null;
  const portsClosed = apiInfo.closed && uiInfo.closed;
  const portsFreeOfRun = [apiInfo, uiInfo].every((info) => info.closed || info.portOwner?.ownedByRun === false);
  // A registered helper left running, or whose exit could not be verified, is part of the stack.
  const helpersUnresolved = childCleanup.filter((c) => !['dead', 'terminated', 'identity-mismatch'].includes(c.outcome));
  let stackGone = survivors.length === 0 && portsFreeOfRun && helpersUnresolved.length === 0
    && unresolvedProcesses.length === 0 && jobUnresolved.length === 0;
  let outcome = stackGone && portsClosed ? STOP_OUTCOME.STOPPED : STOP_OUTCOME.STOP_INCOMPLETE;
  if (unresolvedProcesses.length) {
    errors.push('at least one tracked process identity is still running or of unknown state; the stop is incomplete');
  }
  const latest = await readJsonIfExists(runPath).catch(() => null);
  await writeJsonAtomic(runPath, { ...(latest ?? run), stopUnresolvedTrees: undefined,
    stopTrackedProcesses: unresolvedProcesses });
  await writeJsonAtomic(trackedPath, { processes: unresolvedProcesses, legacyBounds });
  const unresolved = [...survivors, ...unresolvedProcesses,
    ...helpersUnresolved.map(c => ({ ...c, role: 'registered helper', state: c.outcome.includes('unknown') ? 'unknown' : 'match' })),
    ...jobUnresolved]
    .map(p => describeUnresolved(p, p.state));
  const nextStep = `Check each listed PID and recorded birth identity; retry stop after it exits. `
    + forgetGuidance(runId);

  // Tempdoc 730 B1, re-homed at item A16: snapshot the engine log into this run's own dir, so a
  // death run's evidence survives alongside its stop-report instead of only in the shared,
  // cross-run <dataDir>/logs/engine.log.
  const engineLog = await preserveEngineLog(run, runPath);
  // The Engine exit this stop records is written from the census's OS observation and before the
  // census reads the ledger for its verdict, so the report reads every record of the stop.
  // <ledger-resolution> The census's final read of the ledger may show a start no lookup of this stop
  // was asked of (a successor repaired a crashed writer's torn record after the last one): each such
  // start it leaves unproven, not one the stop already knows (knownAlready), is looked up once, in one
  // bounded pass with no loop (round 13). What it finds ended is the census's evidence, as the first
  // lookup's is: the stop never acted on these. One still running or unreadable is reported, never
  // ended, and the stop is incomplete.
  const lateUnresolved = [];
  const lateLookupEnded = [];
  let lateLookedUp = false;
  const lookUpLate = async (unproven) => {
    if (lateLookedUp || opts.forgetUnresolved) return [];
    lateLookedUp = true;
    const late = (unproven ?? []).filter((u) => u?.pid && (!isThisProcess(u) || unidentifiedHelper(u))
      && (u.creationFileTimeUtc || u.processIdentity || Number.isFinite(u.bornNotAfterMs) || unidentifiedHelper(u)) && !provenEnded(u) && !knownAlready(u))
      .map((u) => ({ ...u, role: u.role ?? 'recorded identity', name: u.role ?? 'unknown' }));
    if (!late.length) return [];
    const askedAtMs = Date.now();
    const rows = await Promise.resolve().then(() => lookupIdentities([...new Set(late.map((p) => p.pid))])).catch(() => null);
    for (const p of late) {
      const row = rows?.get(p.pid) ?? (rows ? { present: false } : null);
      p.observedName = row?.name ?? (row?.executable ? path.win32.basename(row.executable) : null);
      p.observedCreationFileTimeUtc = row?.creationFileTime ?? null;
      const state = checkRecordedProcess(p, row);
      if (state === 'match' || state === 'unknown') {
        lateUnresolved.push({ ...p, state });
        continue;
      }
      const observation = rows?.has(p.pid) ? lookupObservation(p, row, askedAtMs) : null;
      if (observation) lateLookupEnded.push(observation);
    }
    return [...lateLookupEnded];
  };
  // </ledger-resolution>
  const censusAccounting = await exitCensus.finish({
    lookupEnded,
    lookUpLate,
    beforeVerdict: pendingEngineExit ? (osExits) => {
      // <ledger-resolution> The census's OS exit of the run's Engine: its whole recorded identity, or
      // its PID where the run recorded no birth.
      pendingEngineExit((osExits ?? []).find((e) => e.role === 'engine' && (byRole.backend.creationFileTimeUtc
        ? compareIdentity(e, byRole.backend, 'same') : hasPid(e, byRole.backend.pid))) ?? null);
      // </ledger-resolution>
    } : null,
  });
  // A retried stop keeps the earlier stop's report and names it (run-directory file names, oldest
  // first); stop-report.json is always this stop's. What an earlier stop found (a disagreement, lost
  // or unreadable evidence, or a report that cannot be read at all) is carried into this stop's
  // accounting: no later record or stop clears it (exitCensus.carryFindings). Only a gap that said an
  // identity's exit was not yet seen is cleared, when this stop proves that exact identity ended: by a
  // bound ledger record (read again, with this stop's own records, only when such a gap is asked
  // about) or its identity-checked lookup.
  for (const observation of lateLookupEnded) {
    const bucket = lookupEndedByPid.get(observation.pid) ?? [];
    if (bucket.some((o) => compareIdentity(o, observation, 'same'))) continue;
    lookupEnded.push(observation);
    lookupEndedByPid.set(observation.pid, [...bucket, observation]);
  }
  if (lateUnresolved.length) {
    unresolvedProcesses.push(...lateUnresolved);
    unresolved.push(...lateUnresolved.map((p) => describeUnresolved(p, p.state)));
    stackGone = false;
    outcome = STOP_OUTCOME.STOP_INCOMPLETE;
    const message = 'at least one tracked process identity is still running or of unknown state; the stop is incomplete';
    if (!errors.includes(message)) errors.push(message);
    const current = await readJsonIfExists(runPath).catch(() => null);
    await writeJsonAtomic(runPath, { ...(current ?? run), stopUnresolvedTrees: undefined,
      stopTrackedProcesses: unresolvedProcesses });
    await writeJsonAtomic(trackedPath, { processes: unresolvedProcesses, legacyBounds });
  }
  const kept = await keepEarlierReports(runDir);
  let ledgerReadAgain = false;
  const endedInLedger = (gap) => {
    if (!ledgerReadAgain) { ledgerReadAgain = true; readLedgerEnded(); }
    return provenEnded(gap);
  };
  const exitAccounting = carryFindings(censusAccounting, kept.earlierFindings,
    { proves: stopProves({ isEnded: endedInLedger, lookupEnded }) });

  const recordedHelpers = recordedLinuxHelpers.map(member => {
    const seen = processIdentity.readLinuxProcess(member.pid);
    const actual = seen?.processIdentity;
    const expected = processIdentity.linuxIdentity(member);
    const accounted = Boolean(expected && (seen?.present === false || (actual
      && (actual.bootId !== expected.bootId || actual.startTicks !== expected.startTicks))));
    return { ...member, accounted, endedObservedBy: accounted ? 'identity-checked lookup' : null };
  });
  const stopReport = buildStopReport({
    ...exitAccounting,
    runId,
    stoppedAt: nowIso(),
    disposition,
    actor,
    victim,
    killedPids,
    pidLiveness,
    ports: { api: apiInfo, ui: uiInfo },
    portsClosed,
    errors,
    engineLog,
    // Tempdoc 542 §B Layer 4 — make interrupted critical/loss op-leases part of the
    // permanent audit record. Tells the operator what was lost on a `force` takeover.
    criticalOpsInterrupted,
    interruptibleWithLossInterrupted,
    gracefulBackendShutdown,
    childCleanup,
  });
  Object.assign(stopReport, {
    outcome,
    unresolvedProcesses: unresolved,
    ...(outcome === STOP_OUTCOME.STOP_INCOMPLETE ? { nextStep } : {}),
    runner: runnerAnswer ? { outcome: runnerAnswer.outcome, waitedMs: runnerAnswer.waitedMs,
      engine: runnerAnswer.engine ?? null } : null,
    fallback,
    // Every process the Engine's job ended after the Engine itself, by identity.
    engineJob,
    ...(process.platform === 'linux' ? { recordedHelpers } : {}),
    // Recorded identities resolved by their exit in the ledger, never looked up or probed.
    ...(resolvedByLedger.length ? { resolvedByLedger } : {}),
    // Recorded identities the first identity-checked lookup found ended before the stop acted (round 11).
    ...(lookupEnded.length ? { endedByLookup: lookupEnded } : {}),
    ...(foreignPortOwner ? { foreignPortOwner } : {}),
  });

  // The earlier census and reports this stop kept, by name.
  const earlierReports = kept.earlierReports;
  if (exitAccounting?.earlierCensus?.length) Object.assign(stopReport, { earlierCensus: exitAccounting.earlierCensus,
    earlierCensusFiles: exitAccounting.earlierCensusFiles });
  if (earlierReports.length) Object.assign(stopReport, { earlierReports, earlierReportFiles: kept.earlierReportFiles });
  if (kept.earlierReportNotArchived) {
    // The earlier report stays as stop-report.json, as it was; this one is written beside it.
    stopReport.earlierReportNotArchived = kept.earlierReportNotArchived;
    stopReport.reportWrittenAs = kept.reportName;
    stopReport.errors.push(`earlier stop report not kept under a number: ${kept.earlierReportNotArchived.error}; `
      + `this report is ${kept.reportName}`);
  }
  const stopReportPath = path.join(runDir, kept.reportName);
  await writeJsonAtomic(stopReportPath, stopReport);

  // Append interference events to a shared NDJSON log for aggregate analysis.
  // Tempdoc 542 §B Layer 4: include forcibly_interrupted_critical_op so the disposition is
  // visible in the shared interference NDJSON log alongside warned_takeover/forced_reclaim.
  const INTERFERENCE_DISPOSITIONS = new Set([
    'stale_reclaim',
    'warned_takeover',
    'forced_reclaim',
    'forcibly_interrupted_critical_op',
  ]);
  if (disposition && INTERFERENCE_DISPOSITIONS.has(disposition)) {
    const event = {
      ts: stopReport.stoppedAt,
      event: 'interference_stop',
      disposition,
      runId,
      actor,
      victim,
      portsClosed: stopReport.portsClosed,
    };
    const logPath = path.join(stateRoot, 'interference-events.ndjson');
    try { fs.appendFileSync(logPath, JSON.stringify(event) + '\n'); } catch (_) {}
  }

  // Spec F §5.3 (X09-F5): the active pointer goes only with a stack verified gone; otherwise it
  // stays, marked, so the next caller sees a stack that may still be running.
  const active = await readJsonIfExists(activePath);
  if (active?.runId === runId) {
    if (stackGone) {
      await fsp.rm(activePath, { force: true }).catch(() => { });
    } else {
      await writeJsonAtomic(activePath, { ...active, stopFailed: { at: nowIso(), outcome,
        reason: survivors.length || helpersUnresolved.length
          ? `${[...survivors.map((s) => s.role), ...helpersUnresolved.map((c) => `helper ${c.id}`)].join(', ')} not verified gone`
          : 'ports still held by the run' } })
        .catch(() => { });
    }
  }

  return {
    ok: true,
    outcome,
    runId,
    killedPids,
    portsClosed,
    ...(foreignPortOwner ? { foreignPortOwner } : {}),
    // Main-checkout relative, like active.runPath (a worktree-relative path did not resolve
    // against the main checkout, where the run state lives).
    stopReportPath: mainRelativePath(stopReportPath),
    childCleanup,
    engineJob,
    ...(process.platform === 'linux' ? { recordedHelpers } : {}),
    errors,
    unresolvedProcesses: unresolved,
    ...(outcome === STOP_OUTCOME.STOP_INCOMPLETE ? { nextStep } : {}),
    ...exitAccounting,
    // Recorded identities the first identity-checked lookup found ended before the stop acted (round 11).
    ...(lookupEnded.length ? { endedByLookup: lookupEnded } : {}),
    ...(earlierReports.length ? { earlierReports, earlierReportFiles: kept.earlierReportFiles } : {}),
    ...(kept.earlierReportNotArchived ? { earlierReportNotArchived: kept.earlierReportNotArchived,
      reportWrittenAs: kept.reportName } : {}),
  };
}

async function cmdCleanup(opts) {
  // Cleanup stops the run and deletes its data: the same ownership gate as stop.
  await assertMayMutateRun(opts, 'cleanup');
  let run = null, runPath = null;
  try {
    ({ run, runPath } = await resolveRunTarget(opts));
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(JSON.stringify({ ok: true, runId: null, portsClosed: true, note: 'no_active_run' }) + '\n');
      return;
    }
    throw err;
  }
  const marker = await readJsonIfExists(path.join(path.dirname(runPath), 'forgotten-unresolved.json'));
  let stopRes;
  if (marker) {
    const entries = marker.leftBehind ?? [];
    const ids = [...new Set(entries.map(p => p.pid).filter(p => Number.isInteger(p) && p > 0))];
    const observed = await engineActuator.lookupProcessIdentities(ids).catch(() => null);
    const remaining = entries.map(p => ({ ...p, state: checkRecordedProcess(p,
      observed?.get(p.pid) ?? (observed ? { present: false } : null)) }))
      .filter(p => p.state === 'match' || p.state === 'unknown').map(p => describeUnresolved(p, p.state));
    stopRes = remaining.length ? { outcome: 'FORGOTTEN_UNRESOLVED', portsClosed: false, leftBehind: remaining,
      note: `Forgotten processes are still alive or unverifiable: ${remaining.map(p =>
        `PID ${p.pid} (${p.name}), start ${p.startedAt}: ${p.reason}`).join('; ')}. ${forgetGuidance(run.runId)}` }
      : { outcome: STOP_OUTCOME.STOPPED, portsClosed: true };
  } else {
    stopRes = await stopRun({ ...opts, runId: run.runId, disposition: 'normal_stop' });
  }
  if (stopRes.outcome !== STOP_OUTCOME.STOPPED) {
    // Spec F §5.3: a stack not verified gone keeps its data; nothing is deleted under it.
    process.stdout.write(JSON.stringify({
      ok: false, runId: run.runId, portsClosed: stopRes.portsClosed, cleaned: false,
      ...(stopRes.foreignPortOwner ? { foreignPortOwner: stopRes.foreignPortOwner } : {}),
      ...(stopRes.leftBehind ? { leftBehind: stopRes.leftBehind } : {}),
      error: { code: 'STOP_INCOMPLETE',
        message: `Run ${run.runId} could not be verified stopped; its data was not cleaned: ${stopRes.note ?? (stopRes.errors?.join('; ') || 'unknown')}.` },
    }) + '\n');
    process.exit(1);
  }
  if (opts.clean !== 'none') {
    const dir = run?.dataDir ? path.resolve(repoRoot, run.dataDir) : null;
    if (dir) await cleanOwnDataDir(dir, opts.clean);
  }
  process.stdout.write(JSON.stringify({ ok: true, runId: run.runId, portsClosed: stopRes.portsClosed }) + '\n');
  if (!stopRes.portsClosed && !opts.force) process.exit(1);
}

async function cmdStop(opts) {
  // Owner-keyed ownership gate: only the owner (or anyone facing a dead stack / ended owner)
  // stops without --force.
  await assertMayMutateRun(opts, 'stop');
  let res = null;
  try {
    res = await stopRun({ ...opts, disposition: 'normal_stop' });
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(
        JSON.stringify({
          ok: true,
          runId: null,
          killedPids: [],
          portsClosed: true,
          stopReportPath: null,
          note: 'no_active_run',
        }) + '\n',
      );
      return;
    }
    throw err;
  }
  if (res.outcome === STOP_OUTCOME.STOP_INCOMPLETE) {
    // Spec F §5.3 (X09-F5): the stack was not verified gone. active.json is kept (with stopFailed).
    process.stdout.write(JSON.stringify({
      ...res,
      ok: false,
      error: {
        code: 'STOP_INCOMPLETE',
        message: res.foreignPortOwner
          ? `Run ${res.runId} stopped, but port ${res.foreignPortOwner.port} is held by a process outside it `
            + `(pid ${res.foreignPortOwner.pid}); it was not killed.`
          : `Run ${res.runId} could not be verified stopped: `
            + (res.unresolvedProcesses?.length ? res.unresolvedProcesses.map(p =>
              `PID ${p.pid} (${p.name}), start ${p.startedAt}: ${p.reason}`).join('; ') : res.errors.join('; ') || 'unknown')
            + `. ${res.nextStep}`,
      },
    }) + '\n');
    process.exit(1);
  }
  process.stdout.write(JSON.stringify(res) + '\n');
  if (res.ok === false) process.exit(1);
  if (res.outcome === 'FORGOTTEN_UNRESOLVED') return;
  if (!res.portsClosed && !opts.force) process.exit(1);
}

/**
 * Tempdoc 656: `dev-runner doctor` — a discoverable entry point for the onramp doctor. Delegates to
 * scripts/dev/doctor.mjs (ESM), inheriting stdio and propagating its exit code, so `--json` and the
 * informational/exit-2-when-broken contract pass through unchanged. Kept a thin passthrough so the
 * tier/remedy logic has exactly one home.
 */
function cmdDoctor() {
  const doctorPath = path.join(__dirname, 'doctor.mjs');
  const passthrough = process.argv.slice(3); // args after `doctor` (e.g. --json)
  const res = spawnSync(process.execPath, [doctorPath, ...passthrough], { stdio: 'inherit' });
  process.exit(res.status == null ? 1 : res.status);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const cmd = opts.cmd;
  if (!cmd || cmd === 'help' || cmd === '--help' || cmd === '-h') {
    printUsage();
    process.exit(cmd ? 0 : 2);
  }

  await mkdirp(runsRoot);

  // The caller is resolved lazily, only where a decision or a write needs it (resolving may read
  // the process table). A start writes the caller as the holder: resolve it once here and record
  // the touch that keeps a working owner out of the idle grade. stop / cleanup resolve it inside
  // the ownership gate, and only when there is an owned holder to compare against. status is
  // read-only and never resolves it.
  if (cmd === 'start') {
    opts.callerIdentity = resolveCallerIdentity(opts.sessionId);
    touchCaller(opts.callerIdentity);
  }

  if (cmd === 'start') return cmdStart(opts);
  if (cmd === 'status') return cmdStatus(opts);
  if (cmd === 'stop') return cmdStop(opts);
  if (cmd === 'cleanup') return cmdCleanup(opts);
  if (cmd === 'doctor') return cmdDoctor();

  throw new Error(`Unknown command: ${cmd}`);
}

if (require.main === module) {
  main().catch((err) => {
    const wantsJson = process.argv.includes('--json');
    if (wantsJson) {
      process.stdout.write(
        JSON.stringify({
          ok: false,
          error: {
            code: err?.code || 'UNHANDLED',
            message: err?.message || String(err),
            // Tempdoc 844 B2: a classified error may carry structured context (offending path,
            // remedy) so a consumer does not have to scrape the message for it.
            ...(err?.details ? { details: err.details } : {}),
            // An ownership refusal names the holder and its lease, so the caller can ask the user.
            ...(err?.holder ? { holder: err.holder } : {}),
            ...(err?.lease ? { lease: err.lease } : {}),
            // A validation refusal is the caller's input error: its message is the whole report.
            ...(err?.stack && !(err instanceof DevRunnerValidationError) ? { stack: String(err.stack) } : {}),
          },
        }) + '\n',
      );
    } else if (err instanceof DevRunnerValidationError) {
      console.error(`[dev-runner] ${err.code}: ${err.message}`);
    } else {
      console.error(err?.stack || String(err));
    }
    process.exit(1);
  });
} else {
  module.exports = {
    __test: {
      trackedIdentities,
      persistEngineStartupFailure,
      pruneHistoricRuns,
      resolveDataDir,
      cleanDataDir,
      cleanOwnDataDir,
      assertCleanableDataDir,
      authoredStoreTopLevelNames,
      acquireAdmission,
      readActiveOpLeases,
      // Tempdoc 735 G6: lease-duration clamp + CLI arg parsing.
      clampLeaseDurationSec,
      parseArgs,
      DEFAULT_LEASE_DURATION_SEC,
      MIN_LEASE_DURATION_SEC,
      MAX_LEASE_DURATION_SEC,
      computeOwnershipVerdict,
      classifyActivity,
      readSessionActivity,
      isPidAlive,
      lockPath,
      activePath,
      resolveExpectedIndexBasePath,
      resolveOwnerConfidence,
      // Agent identity and owner presence.
      resolveCallerIdentity,
      holderBlockFor,
      applyAgentSessionEnv,
      createPresenceMonitor,
      assertMayMutateRun,
      ownerVerdictFor,
      leaseRenewIntervalMs,
      stateRoot,
      resolveMainRepoRoot,
      mainRepoRoot,
      repoRoot,
      resolveCuda12ServerExe,
      stageSharedCuda12,
      // Tempdoc 730 Increment 4 (B1/B2/B3)
      preserveEngineLog,
      mainRelativePath,
      buildStopReport,
      buildHeadJavaOpts,
      writeSelfExitStopReport,
      recordDiscoveryExit,
      // Lane F stage B item B8: the supervisor's actuator helpers. The DECISION is not here —
      // scripts/dev/lib/engine-supervisor.cjs owns it and the Rust half reads the same register.
      checkHttp200,
      fetchJsonHttp,
      essentialReadyEpoch,
      buildSupervisorState,
      writeJsonAtomic,
      writeSupervisorState,
      createSupervisorStateWriter,
      supervisorStatePath,
      supervisorHistoryPath,
      shutdownRequestPath,
      writeShutdownRequestFile,
      waitForEngineHandleRelease,
      cleanupRegisteredChildrenForTerminal,
      cleanupRegisteredChildrenForSupervisorState,
      inspectProcessIdentity,
      cleanupTerminalChildren,
      stopRun,
      // Spec F §5.3 (step 10): the stop that asks the holder, identity checks and port owners.
      STOP_OUTCOME,
      askRunnerToStop,
      recordedProcesses,
      checkRecordedProcess,
      isRunProcess,
      portOwnerOf,
      admissionLockHolderLiveness,
      removeOwnStartRecords,
      selfProcessIdentity,
      engineSupervisor,
      // Tempdoc 819 §D: graceful ordered-shutdown-before-taskkill helpers.
      postLifecycleShutdown,
      maybeGracefulBackendShutdown,
      // Tempdoc 844 M3: the hot-reload classpath pairing check.
      assessHotReloadClasspath,
      newestClassMtimeMs,
      hotReloadClassesDir,
      HOTRELOAD_MODULE,
    },
  };
}
