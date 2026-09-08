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
// can't break the assemble/head/worker JVMs. Injected into every JVM spawn's env below.
const { resolveJdkHome } = require(path.join(__dirname, 'lib', 'resolve-jdk.cjs'));
const { engineJavaLaunch } = require('./lib/engine-java-launch.cjs');

const repoRoot = path.resolve(__dirname, '..', '..');
const uiWebDir = path.resolve(repoRoot, 'modules', 'ui-web');
const gradleCmd = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
const gradlePath = path.resolve(repoRoot, gradleCmd);

/**
 * Resolve the main repo root, even when running inside a git worktree.
 * In worktrees, `.git` is a file containing `gitdir: <path>` where path
 * points to `<mainRepo>/.git/worktrees/<name>`. We walk up 3 levels to
 * find the main repo. Falls back to `repoRoot` if detection fails.
 */
function resolveMainRepoRoot() {
  const gitPath = path.join(repoRoot, '.git');
  try {
    const stat = fs.statSync(gitPath);
    if (stat.isFile()) {
      const content = fs.readFileSync(gitPath, 'utf8').trim();
      const match = content.match(/^gitdir:\s*(.+)$/);
      if (match) {
        const gitDir = path.resolve(repoRoot, match[1]);
        return path.resolve(gitDir, '..', '..', '..');
      }
    }
  } catch { /* not a worktree or no .git — fall through */ }
  return repoRoot;
}

const mainRepoRoot = resolveMainRepoRoot();
// State root defaults to the SHARED main-repo location so all worktrees coordinate on one
// lease. Overridable via JUSTSEARCH_DEV_RUNNER_STATE_ROOT for an ISOLATED dev-runner — used by
// integration tests and any throwaway stack that must not touch the shared lease (tempdoc 606
// validation seam). Normally unset in the runner's own env (the runner only EMITS it to the
// Head child below), so the default holds in production.
const stateRoot = process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT
  ? path.resolve(process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT)
  : path.resolve(mainRepoRoot, 'tmp', 'dev-runner');
const runsRoot = path.join(stateRoot, 'runs');
const activePath = path.join(stateRoot, 'active.json');
// Tempdoc 542 §B Layer 2: op-leases.json is Head's lease registry. Single Java writer
// (OperationLeaseServiceImpl); read here at admission time for criticality-aware dispatch.
const opLeasesPath = path.join(stateRoot, 'op-leases.json');
// Tempdoc 606: per-session activity stamps (general + dev-stack touch), written by the
// agent-analytics hooks under the SHARED state root so the supervisor (mainRepoRoot-scoped)
// can read them. Presence/idle grades + the presence-aware renewer join against these.
const sessionsDir = path.join(stateRoot, 'sessions');
const {
  computeOwnershipVerdict,
  classifyActivity,
  readSessionActivity,
  mergeSessionActivity,
  DEFAULT_THRESHOLDS,
} = require('./lib/ownership-verdict.cjs');
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

function isNoActiveRunError(err) {
  return err instanceof NoActiveRunError || err?.code === 'NO_ACTIVE_RUN' || err?.name === 'NoActiveRunError';
}

function nowIso() {
  return new Date().toISOString();
}

function toPosix(p) {
  return String(p).split(path.sep).join('/');
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
    await fsp.rename(tmp, filePath);
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
      '          [--dist-from <root>]  (record-only: the checkout the MCP caller asked to launch from)',
      '  status  [--run <runId>|--active] [--json]',
      '  stop    [--run <runId>|--active] [--force] [--json]',
      '  cleanup [--run <runId>|--active] [--force] [--clean soft|hard|none] [--json]',
      '  doctor  [--json]   report the onramp capability tier / what is missing / next remedy',
      '',
      'Notes:',
      '  - start is a long-running supervisor (foreground). Use Ctrl+C or run stop/cleanup to tear down the stack.',
      '  - When --json is used, stdout prints exactly one JSON object (no extra human logs).',
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
    const hardKeep = new Set(['ui']);
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
    'config', 'index', 'watched_roots.json', 'ui',
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
    await fsp.rm(path.join(dir, ent), { recursive: true, force: true }).catch(() => { });
  }
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
  const exeName = 'llama-server.exe';
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
function resolveCuda12ServerExe(worktreeRoot, sharedRoot, exeName) {
  const cuda12 = ['modules', 'ui', 'native-bin', 'llama-server', 'variants', 'cuda12', exeName];
  const candidates = [
    path.join(worktreeRoot, ...cuda12),   // worktree's own cuda12 (rare — a deliberate local install)
    path.join(sharedRoot, ...cuda12),     // the shared main-checkout cuda12 (the normal path)
  ];
  return candidates.find((p) => fs.existsSync(p)) || null;
}

function resolveAiDevEnv() {
  const env = {};
  // Tempdoc 656: provision the shared cuda12 GPU runtime (once, at the main checkout) so this and
  // every other worktree can reference it. Deliberately NO CPU baseline staging (GPU-only dev).
  ensureSharedCuda12Staged();
  if (!process.env.JUSTSEARCH_SERVER_EXE) {
    const exeName = process.platform === 'win32' ? 'llama-server.exe' : 'llama-server';
    const found = resolveCuda12ServerExe(repoRoot, mainRepoRoot, exeName);
    if (found) env.JUSTSEARCH_SERVER_EXE = found;
  }
  if (!process.env.JUSTSEARCH_MODELS_DIR) {
    // Tempdoc 618 §2: prefer the MAIN checkout's models (holds the LFS binaries) over a
    // worktree's models/ (tracked manifests only), so a worktree dev stack finds real models.
    const mainModels = path.join(mainRepoRoot, 'models');
    const localModels = path.join(repoRoot, 'models');
    if (fs.existsSync(mainModels)) env.JUSTSEARCH_MODELS_DIR = mainModels;
    else if (fs.existsSync(localModels)) env.JUSTSEARCH_MODELS_DIR = localModels;
  }
  // Auto-detect SPLADE model under the resolved models dir (models/splade/naver-splade-v3/)
  if (!process.env.JUSTSEARCH_SPLADE_MODEL_PATH) {
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

function resolveHolderSource() {
  if (process.env.JUSTSEARCH_AGENT_SESSION_ID) return 'claude';
  if (process.env.CI) return 'ci';
  return 'unknown';
}

function resolveAgentSessionId(cliSessionId) {
  if (cliSessionId) return cliSessionId;
  const fromEnv = (process.env.JUSTSEARCH_AGENT_SESSION_ID || '').trim();
  if (fromEnv) return fromEnv;
  try {
    const content = fs.readFileSync(
      path.join(repoRoot, 'tmp', 'agent-telemetry', 'current-session-id'),
      'utf8',
    );
    return content.trim() || null;
  } catch { return null; }
}

function resolveOwnerConfidence(agentSessionId, confirmedIbp) {
  if (!agentSessionId) return 'low';
  const fromEnv = (process.env.JUSTSEARCH_AGENT_SESSION_ID || '').trim();
  if (fromEnv && fromEnv === agentSessionId) return confirmedIbp ? 'high' : 'medium';
  return confirmedIbp ? 'medium' : 'medium';
}

function isPidAlive(pid) {
  const n = Number(pid);
  if (!Number.isFinite(n) || n <= 0) return false;
  try { process.kill(n, 0); return true; } catch { return false; }
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
    return { preserved: true, path: toPosix(path.relative(repoRoot, destEngineLog)) };
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
  taskkillExitCode = null,
  taskkillStderrTail = '',
  killedPids = [],
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
}) {
  return {
    schemaVersion: 2,
    runId,
    stoppedAt,
    disposition,
    actor,
    victim,
    taskkillExitCode,
    taskkillStderrTail,
    killedPids,
    // Tempdoc 730 B2: per-PID liveness probe taken BEFORE the kill attempt, so a
    // 'reaped_abandoned' report can distinguish "backend already dead, reaper cleaned up the
    // shell" from "backend live, reaper killed an abandoned-but-healthy stack".
    pidLiveness,
    // Tempdoc 730 B2: the backend JVM's own exit code when it self-exits (crash/OOM), captured
    // by the backend.on('exit') handler — absent for kill-driven stops (the exit code there is
    // taskkill's, already carried by taskkillExitCode).
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

// Tempdoc 730 B3: dump on OOM for the Head JVM. Dev-runner JVMs previously ran at JVM-default
// (unbounded) heap with no dump flags, so a runaway leak would thrash/consume RAM rather than
// OOM and leave evidence (see tempdoc 730 §THEORIZE B). The dump flags are always-on and cheap
// (inert if the cause is elsewhere). `-Xmx` is opt-in ONLY (JUSTSEARCH_HEAD_HEAP) — Increment-4
// review found a default cap can ITSELF induce an artifact OOM in the exact death scenario being
// diagnosed (tempdoc 730 Increment-4 review findings, 2026-07-14), so no default bound is emitted.
// Pure function (no process/env access beyond the passed-in values) so the generated flags are
// unit-testable without spawning a JVM.
function buildHeadJavaOpts({ existingJavaOpts, headAotOpts, headDistStamp, logsDir, headHeap, debugPort }) {
  const heapBound = headHeap && String(headHeap).trim() ? String(headHeap).trim() : null;
  return [
    existingJavaOpts,
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
    '-XX:+UseSerialGC -XX:MetaspaceSize=128m -XX:MaxDirectMemorySize=256m -XX:+UseCompactObjectHeaders -XX:-UsePerfData'
      + ' -Dfile.encoding=UTF-8',
    headAotOpts,
    // Tempdoc 606 Piece 2b: the Head echoes this on /api/runtime/manifest so a
    // stale old Head answering on a reused port is detectable (build mismatch).
    headDistStamp ? `-Djustsearch.head.stamp=${headDistStamp}` : null,
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
async function writeSelfExitStopReport({ runId, runPath, run, backendExitCode, interactive, incarnation = null }) {
  const engineLog = await preserveEngineLog(run, runPath);
  const stopReport = buildStopReport({
    runId,
    stoppedAt: nowIso(),
    disposition: interactive ? 'interactive_stop' : 'self_exited',
    backendExitCode,
    killedPids: [],
    pidLiveness: [],
    ports: null,
    portsClosed: null,
    errors: [],
    engineLog,
    incarnation,
  });
  const stopReportPath = path.join(path.dirname(runPath), 'stop-report.json');
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
}) {
  return {
    schemaVersion: 1,
    kind: 'engine-supervisor-state.v1',
    supervisor: 'dev-runner',
    state,
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
async function writeShutdownRequestFile(dataDir, { reason, deadlineEpochMs, issuedBy = 'dev-runner', nonce = null }) {
  const target = shutdownRequestPath(dataDir);
  await writeJsonAtomic(target, {
    schemaVersion: 1,
    reason,
    deadlineEpochMs,
    issuedBy,
    ...(nonce ? { nonce } : {}),
  });
  return target;
}

/**
 * The forced half of design 7.1's hang path: the deadline expired, so the request was not enough.
 *
 * Kill the Engine PID only. Its registered children intentionally survive recoverable death/hang
 * for identity-safe startup reconciliation and warm adoption.
 */
function forceKillEngineTree(pid) {
  if (!pid) return;
  try {
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/PID', String(pid), '/F'], { stdio: 'ignore', windowsHide: true });
    } else {
      process.kill(pid, 'SIGKILL');
    }
  } catch (err) {
    process.stderr.write(`[dev-runner] force kill of ${pid} failed: ${err?.message ?? err}\n`);
  }
}

/** Terminal-only cleanup of children whose three recorded OS identity axes still match. */
function cleanupRegisteredChildrenForTerminal(dataDir, inspect = inspectProcessIdentity, terminate = terminatePid) {
  let children;
  try {
    const manifest = JSON.parse(fs.readFileSync(path.join(dataDir, 'runtime', 'manifest.json'), 'utf8'));
    children = Array.isArray(manifest?.children) ? manifest.children : [];
  } catch {
    return [];
  }
  const outcomes = [];
  for (const child of children) {
    const identity = inspect(child?.pid);
    if (!identity || !identity.alive) {
      outcomes.push({ id: child?.id, outcome: 'dead' });
      continue;
    }
    const expectedStart = Date.parse(child?.startedAt);
    const actualStart = Date.parse(identity.startedAt);
    const expectedExe = normalizeExecutable(child?.executable);
    const actualExe = normalizeExecutable(identity.executable);
    if (!Number.isFinite(expectedStart) || !Number.isFinite(actualStart)
        || !expectedExe || !actualExe) {
      outcomes.push({ id: child?.id, outcome: 'unknown-identity' });
      continue;
    }
    if (Math.abs(expectedStart - actualStart) > 1000 || expectedExe !== actualExe) {
      outcomes.push({ id: child?.id, outcome: 'identity-mismatch' });
      continue;
    }
    outcomes.push({ id: child?.id, outcome: terminate(child.pid) ? 'terminated' : 'termination-failed' });
  }
  return outcomes;
}

function normalizeExecutable(value) {
  if (typeof value !== 'string' || !value.trim()) return null;
  const normalized = path.resolve(value).toLowerCase();
  return normalized;
}

function inspectProcessIdentity(pid) {
  if (!Number.isInteger(Number(pid)) || Number(pid) <= 0 || process.platform !== 'win32') return null;
  const script = [
    '$p=Get-Process -Id ([int]$args[0]) -ErrorAction Stop',
    '[pscustomobject]@{executable=$p.Path;startedAt=$p.StartTime.ToUniversalTime().ToString("o");alive=$true}|ConvertTo-Json -Compress',
  ].join(';');
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script, String(pid)], {
    encoding: 'utf8', windowsHide: true,
  });
  if (result.status !== 0) return { alive: false };
  try { return JSON.parse(result.stdout); } catch { return null; }
}

function terminatePid(pid) {
  if (process.platform !== 'win32') return false;
  const result = spawnSync('taskkill', ['/PID', String(pid), '/F'], {
    stdio: 'ignore', windowsHide: true,
  });
  return result.status === 0;
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

// Projection of existing status fields; indexServing can be DEGRADED for optional AI.
function essentialStatusReady(status) {
  return status?.components?.head?.state === 'LIFECYCLE_STATE_READY'
    && status?.indexAvailable === true
    && status?.worker?.core?.indexHealthy === true
    && status?.readiness?.components?.indexServing?.stale === false;
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

// Tempdoc 819 §D: attempt the graceful ordered shutdown before stopRun's unconditional
// `taskkill /T /F` fallback. Bounded (a 2s POST timeout + a 5s exit-wait, polled — never a blind
// sleep) so a graceful attempt can never make `stop` less reliable than the taskkill-only path:
// any failure (bad pid/port, connection refused, non-2xx, exit-wait timeout) falls through with
// `outcome` set to something other than 'exited', and the caller taskkills as before.
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

// This startup gate means the Engine answers valid HTTP on `/api/health`, including 503;
// index readiness is checked separately for the stability window. A11 deleted the Worker child, so
// nothing "connects" any more; what still lags is the in-process knowledge-server start, which
// HeadlessApp forks asynchronously (`CompletableFuture.supplyAsync(tryStartKnowledgeServer)`,
// HeadlessApp.java:981) so it runs in PARALLEL with API construction. Until that fork drives
// WorkerCapability to READY, `/api/knowledge/*` answers 503 ("Knowledge Server not ready").
// Consumers that hit those endpoints immediately after "stack up" must tolerate that transient
// 503 — see stage-reference-corpus.mjs stageAndVerify's ingest retry (tempdoc 656 §J/§K.5). (The
// MCP dev server exposes a separate worker-ready readiness level for callers that need it.)
async function waitForBackendReady(apiPort, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  const url = `http://127.0.0.1:${apiPort}/api/health`;
  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    const ok = await checkHttp200(url, 1200, true);
    if (ok) return true;
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

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

async function getPortOwnerWindows(port) {
  validatePort(port);
  const cmd = `Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue | Select-Object -First 1`;
  const res = await execPowerShell(cmd);
  if (!res.ok || !res.stdout.trim()) return null;

  // Parse via ConvertTo-Json to avoid formatting issues.
  const res2 = await execPowerShell(
    `Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue | ` +
    `Select-Object -First 1 LocalAddress,LocalPort,OwningProcess | ConvertTo-Json -Compress`,
  );
  if (!res2.ok || !res2.stdout.trim()) return null;
  try {
    const obj = JSON.parse(res2.stdout.trim());
    const pid = Number(obj?.OwningProcess);
    const addr = obj?.LocalAddress ? String(obj.LocalAddress) : null;
    return { pid: Number.isFinite(pid) && pid > 0 ? pid : null, address: addr };
  } catch {
    return null;
  }
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
    if (!loaded) throw new Error(`Run not found: ${opts.runId}`);
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

  const child = spawn(spawnCommand, spawnArgs, opts);

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

async function acquireAdmission({ takeover = 'deny', sessionId, confirmInterrupt = null } = {}) {
  // Step 1: Acquire sidecar lockfile with exclusive create (271 A3.3)
  const lockPayload = JSON.stringify({ pid: process.pid, acquiredAt: nowIso() });
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
      // Lock is only held for milliseconds during admission. If it's older than 2 minutes,
      // the holder crashed — treat as stale regardless of PID liveness (prevents PID-reuse deadlock).
      const lockAgeMs = existingLock.acquiredAt
        ? Date.now() - new Date(existingLock.acquiredAt).getTime()
        : Infinity;
      if (lockAgeMs < 120_000 && isPidAlive(existingLock.pid)) {
        return { action: 'conflict', reason: 'lock_held', holder: existingLock };
      }
      // Dead or stale holder — remove lock and re-acquire
      await fsp.rm(lockPath, { force: true });
      await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
    }
  }

  // We now hold the admission lock. Everything below must be in try/finally.
  try {
    const active = await readJsonIfExists(activePath);

    // Gather facts for the single ownership-verdict authority (tempdoc 606).
    const leaseExpired = active?.lease?.expiresAt
      ? new Date(active.lease.expiresAt) < new Date()
      : true; // No lease → treat as stale (pre-271 format)
    let runJson = null;
    if (active?.runPath) {
      try {
        runJson = JSON.parse(await fsp.readFile(
          path.join(mainRepoRoot, active.runPath), 'utf8'));
      } catch { /* run.json missing or unreadable */ }
    }
    const ownerPid = runJson?.pids?.runnerPid ?? null;
    const supervisorAlive = ownerPid ? isPidAlive(ownerPid) : false;
    // Tempdoc 542 §B Layer 4: op-lease registry (criticality-aware dispatch).
    const opLeases = await readActiveOpLeases();
    // Tempdoc 606 D1: owner-session activity (presence/idle grades).
    const ownerActivity = readSessionActivity(sessionsDir, active?.holder?.agentSessionId);

    // The ONE decision. selfCheck:false on the gate — the CLI applies takeover
    // policy regardless of caller (self-owner handling lives in the MCP layer);
    // this preserves pre-606 gate semantics.
    const decision = computeOwnershipVerdict({
      active,
      selfCheck: false,
      supervisorAlive,
      leaseExpired,
      ownerActivity,
      opLeases,
      takeover,
      confirmInterrupt,
      now: Date.now(),
    });

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
      await stopRun({
        runId: active.runId,
        disposition: decision.disposition,
        actor: { source: resolveHolderSource(), agentSessionId: resolveAgentSessionId(sessionId) },
        victim: decision.victim,
        ...(decision.criticalOpsInterrupted ? { criticalOpsInterrupted: decision.criticalOpsInterrupted } : {}),
        ...(decision.interruptibleWithLossInterrupted
          ? { interruptibleWithLossInterrupted: decision.interruptibleWithLossInterrupted } : {}),
      }).catch(() => {});
    }
    return {
      action: 'proceed',
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

async function cmdStart(opts) {
  // Tempdoc 606 3a: ownership epoch — monotonic, bumped on every custody episode (each
  // start replaces the prior holder). Read BEFORE admission (stopRun may clear active.json).
  const _priorActive = await readJsonIfExists(activePath);
  const ownershipEpoch = (Number(_priorActive?.ownershipEpoch) || 0) + 1;
  // Lease-aware admission: check ownership before starting (271, extended by 542 §B Layer 4)
  const admission = await acquireAdmission({
    takeover: opts.takeover,
    sessionId: opts.sessionId,
    confirmInterrupt: opts.confirmInterrupt,
  });
  if (admission.action === 'conflict') {
    // Tempdoc 542 §B Layer 4: criticality-aware error codes.
    //   handshake_required        — MUST_COMPLETE or UNSAFE_TO_INTERRUPT op-lease blocks `warn`
    //   requires_confirmation     — `force` against UNSAFE_TO_INTERRUPT without --confirm-interrupt
    //   fresh_owner / lock_held   — routine OWNER_CONFLICT (existing semantics)
    const code = admission.reason === 'handshake_required'
        ? 'HANDSHAKE_REQUIRED'
        : admission.reason === 'requires_confirmation'
            ? 'REQUIRES_CONFIRMATION'
            : 'OWNER_CONFLICT';
    let message;
    if (admission.reason === 'handshake_required') {
      message = admission.message
          ?? `Backend has ${admission.criticalOps?.length ?? 0} critical op-lease(s) active`;
    } else if (admission.reason === 'requires_confirmation') {
      message = admission.message ?? 'force-interrupt requires --confirm-interrupt=<opId>';
    } else {
      message = `Backend owned by ${admission.holder?.source ?? 'unknown'}` +
          (admission.reason === 'lock_held'
            ? ' (admission lock held by another process)'
            : ' (fresh lease, use --takeover=warn to override)');
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
      },
    };
    if (opts.json) {
      process.stdout.write(JSON.stringify(conflict) + '\n');
    } else {
      process.stderr.write(`[dev-runner] ${conflict.error.message}\n`);
    }
    return;
  }

  const activeAfterStop = await readJsonIfExists(activePath);
  await pruneHistoricRuns({
    preserveRunIds: [admission.victim?.runId, activeAfterStop?.runId].filter(Boolean),
  }).catch(() => { });

  const runId = crypto.randomUUID();
  const startedAt = nowIso();
  const uiPort = opts.uiPort;
  const apiPortRequested = opts.apiPort;
  const dataDir = resolveDataDir(opts.dataDir);

  const runDir = path.join(runsRoot, runId);
  const logsDir = path.join(runDir, 'logs');
  await mkdirp(logsDir);

  const backendStdoutPath = path.join(logsDir, 'backend.stdout.log');
  const backendStderrPath = path.join(logsDir, 'backend.stderr.log');
  const frontendStdoutPath = path.join(logsDir, 'frontend.stdout.log');
  const frontendStderrPath = path.join(logsDir, 'frontend.stderr.log');

  const backendStdout = fs.createWriteStream(backendStdoutPath, { flags: 'a' });
  const backendStderr = fs.createWriteStream(backendStderrPath, { flags: 'a' });
  const frontendStdout = fs.createWriteStream(frontendStdoutPath, { flags: 'a' });
  const frontendStderr = fs.createWriteStream(frontendStderrPath, { flags: 'a' });

  // Session-scoped clean gate: reject clean if another session owns the stack
  if (opts.clean !== 'none') {
    const active = await readJsonIfExists(activePath);
    if (active && active.holder?.agentSessionId) {
      const callerSession = resolveAgentSessionId(opts.sessionId);
      if (callerSession && callerSession !== active.holder.agentSessionId) {
        const err = new Error('OWNER_CONFLICT: clean rejected — another session owns the stack');
        err.code = 'OWNER_CONFLICT';
        err.holder = active.holder;
        err.lease = active.lease;
        throw err;
      }
    }
  }
  await cleanDataDir(dataDir, opts.clean);

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
      '[dev-runner] Ensuring distribution is up-to-date (assemble + installDist)...\n');
    const buildResult = spawnSync(
      gradlePath,
      ['assemble', ':modules:ui:installDist', '-PskipWebBuild=true'],
      // Tempdoc 696: pin a >= 24 JDK so a stale JDK-8 JAVA_HOME can't fail the assemble.
      {
        cwd: repoRoot,
        shell: process.platform === 'win32',
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
          ? `Gradle assemble + installDist could not be run: ${buildResult.error.message}`
            + `${buildResult.error.code ? ` (${buildResult.error.code})` : ''} — ${gradlePath}`
          : `Gradle assemble + installDist failed with exit code ${buildResult.status}`
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
    const gradleCmd = process.platform === 'win32' ? './gradlew.bat' : './gradlew';
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
    headHeap: process.env.JUSTSEARCH_HEAD_HEAP,
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
        javaOpts: engineJavaOpts, uiOpts: process.env.UI_OPTS }),
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
  const spawnEngineChild = (apiPortForThisIncarnation) => spawnLogged(
    spawnBackend.command,
    spawnBackend.args,
    {
      cwd: spawnBackend.cwd,
      env: {
        ...process.env,
        ...aiEnv,
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
        // Head startup flags: SerialGC (small heap, no throughput need), MetaspaceSize=128m,
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
      },
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
  );

  let backend = spawnEngineChild(apiPortRequested);

  const spawnFrontend = () => {
    if (harnessFrontendCommand) {
      const parsed = JSON.parse(harnessFrontendCommand);
      return { cwd: repoRoot, command: parsed[0], args: parsed.slice(1), shell: false };
    }
    return {
      cwd: uiWebDir,
      command: 'npm',
      args: ['run', 'dev', '--', '--host', '--port', String(uiPort), '--strictPort'],
      shell: process.platform === 'win32',
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
  // (the producer-published runtime manifest) and the legacy
  // <dataDir>/runtime/api-port.txt (thin mirror, to be removed in Phase 8).
  // Both serve as fallbacks when stdout piping is delayed; the manifest is
  // preferred because it carries instanceId (cross-linked into run.json so
  // restarts are detectable across orchestrator views).
  //
  // Preserve the predecessor manifest: schema v2 carries the managed-child ownership handoff.
  // Discovery below accepts only the current spawned Engine PID, so stale ports cannot bind.
  const runtimeDir = path.join(dataDir, 'runtime');
  const manifestPath = path.join(runtimeDir, 'manifest.json');
  // Pre-spawn cleanup. api-port.txt is the deprecated mirror (Phase 8) but
  // we still unlink it so a stale --clean=none restart doesn't leave a
  // misleading file around for any legacy consumer.
  //
  const clearStaleDiscoveryFiles = () => {
    try { fs.unlinkSync(path.join(runtimeDir, 'api-port.txt')); } catch { /* ok if absent */ }
  };
  clearStaleDiscoveryFiles();

  let manifestInstanceId = null;
  const tryReadManifest = (expectedPid, predecessor) => {
    try {
      const content = fs.readFileSync(manifestPath, 'utf8');
      const parsed = JSON.parse(content);
      const p = parsed?.head?.apiPort;
      if (Number.isInteger(p) && p > 0 && p <= 65535
          && parsed.pid === expectedPid && typeof parsed.instanceId === 'string'
          && parsed.instanceId.trim() && parsed.instanceId !== predecessor) {
        manifestInstanceId = parsed.instanceId;
        return p;
      }
    } catch { /* not yet written or malformed */ }
    return 0;
  };

  /**
   * Wait for ONE incarnation to publish a port and answer.
   *
   * Tempdoc 501 §3.1: the manifest is the sole discovery path. The legacy api-port.txt fallback the
   * wait-loop used to consult was dead code in the dev-runner context, and removing it tightened the
   * closure ("one mechanism per concern").
   *
   * Lane F stage B item B8 changed one thing here, deliberately: the loop no longer short-circuits
   * when `--api-port` named an explicit port. It used to, which meant an explicit-port start never
   * read the manifest at all and recorded `portSource: unresolved` with a null instanceId. A
   * supervisor needs the instanceId on every incarnation to tell one boot from the next, and the
   * the manifest must name the current child PID, so a retained predecessor ownership record can
   * never be mistaken for this incarnation's discovery state.
   */
  const awaitEngineIncarnation = async ({ portTimeoutMs, readyTimeoutMs }) => {
    let discovered = 0;
    const awaitedChild = backend;
    const predecessor = manifestInstanceId;
    const waitForPortDeadline = Date.now() + portTimeoutMs;
    while (discovered <= 0 && Date.now() < waitForPortDeadline) {
      // eslint-disable-next-line no-await-in-loop
      await new Promise((r) => setTimeout(r, 100));
      if (backend !== awaitedChild || awaitedChild.exitCode !== null) throw new Error("Engine exited during discovery");
      discovered = tryReadManifest(awaitedChild.pid, predecessor);
    }
    if (!Number.isFinite(discovered) || discovered <= 0) {
      const seconds = Math.max(1, Math.round(portTimeoutMs / 1000));
      throw new Error(
        `Backend did not emit JUSTSEARCH_API_PORT=<port> within ${seconds}s (requested=${apiPortRequested}).`,
      );
    }
    const ready = await waitForBackendReady(discovered, readyTimeoutMs);
    if (!ready || backend !== awaitedChild || awaitedChild.exitCode !== null) {
      const seconds = Math.max(1, Math.round(readyTimeoutMs / 1000));
      throw new Error(
        `Backend did not answer at http://127.0.0.1:${discovered}/api/health within ${seconds}s`);
    }
    return { apiPort: discovered, instanceId: manifestInstanceId };
  };

  const firstIncarnation = await awaitEngineIncarnation({
    portTimeoutMs: portEmitTimeoutMs,
    readyTimeoutMs: backendReadyTimeoutMs,
  });
  apiPortActual = firstIncarnation.apiPort;

  const apiBaseUrl = `http://127.0.0.1:${apiPortActual}`;
  const uiUrl = `http://localhost:${uiPort}`;

  // indexBasePath capture (271 stage 4)
  const expectedIbp = resolveExpectedIndexBasePath(dataDir);
  let confirmedIbp = null;
  try {
    confirmedIbp = await fetchConfirmedIndexBasePath(apiPortActual);
  } catch { /* best-effort */ }

  const spawnF = spawnFrontend();
  const frontend = spawnLogged(
    spawnF.command,
    spawnF.args,
    {
      cwd: spawnF.cwd,
      env: {
        ...process.env,
        VITE_JUSTSEARCH_API_PORT: String(apiPortActual),
        VITE_API_PORT: String(apiPortActual),
      },
      shell: spawnF.shell,
      windowsHide: spawnF.shell,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
    frontendStdout,
    frontendStderr,
    null,
  );

  const runJson = {
    schemaVersion: 1,
    runId,
    startedAt,
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
    owner: (() => {
      const sid = resolveAgentSessionId(opts.sessionId);
      return {
        source: resolveHolderSource(),
        agentSessionId: sid,
        confidence: resolveOwnerConfidence(sid, confirmedIbp),
      };
    })(),
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

  const runPath = path.join(runDir, 'run.json');
  await writeJsonAtomic(runPath, runJson);
  const leaseNow = nowIso();
  const holderSessionId = resolveAgentSessionId(opts.sessionId);
  await writeJsonAtomic(activePath, {
    kind: 'backend-shared-lease.v1',
    schemaVersion: 1,
    runId,
    runPath: toPosix(path.relative(repoRoot, runPath)),
    launcherFamily: 'dev-runner',
    mode: 'shared',
    holder: {
      source: resolveHolderSource(),
      agentSessionId: holderSessionId,
    },
    takeoverPolicy: 'warn',
    // Tempdoc 606 3a: ownership epoch (bumps on each custody transfer → notification basis).
    ownershipEpoch,
    // Tempdoc 606 Piece 2: provenance of the code this stack actually runs.
    provenance: devStackProvenance,
    lease: {
      // Tempdoc 735 G6: opts.leaseDurationSec is pre-clamped to [30, 7200]s at parse time
      // (clampLeaseDurationSec) and defaults to 30 — unchanged behavior when the starter
      // doesn't declare a longer campaign hold.
      durationSec: opts.leaseDurationSec,
      renewedAt: leaseNow,
      expiresAt: new Date(Date.now() + opts.leaseDurationSec * 1000).toISOString(),
      sequence: 1,
    },
    updatedAt: leaseNow,
  });
  // Tempdoc 606 3a: record the epoch this holder acquired, so if it is later displaced
  // it can detect that (pull-at-next-action notification via the ownership projection).
  if (holderSessionId) mergeSessionActivity(sessionsDir, holderSessionId, { ownedEpoch: ownershipEpoch });

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

  const onExit = () => {
    clearInterval(renewalInterval);
    try {
      backendStdout.end();
      backendStderr.end();
      frontendStdout.end();
      frontendStderr.end();
    } catch (_) { }
  };

  let shuttingDown = false;

  const shutdown = () => {
    // Guard against double-shutdown (e.g., SIGINT followed by SIGTERM)
    if (shuttingDown) return;
    shuttingDown = true;
    clearInterval(renewalInterval);

    // Close log streams first to flush buffered data
    try {
      backendStdout.destroy();
      backendStderr.destroy();
      frontendStdout.destroy();
      frontendStderr.destroy();
    } catch (_) { }

    // Best-effort: delegate cleanup to stop command; but ensure we don't strand children on Ctrl+C.
    // Note: This function is intentionally synchronous - signal handlers cannot await async functions.
    // spawn() returns immediately, so the cleanup is fire-and-forget.
    try {
      if (process.platform === 'win32') {
        // taskkill /T kills entire process tree
        spawn('taskkill', ['/PID', String(process.pid), '/T', '/F'], {
          stdio: 'ignore',
          windowsHide: true,
          detached: true,
        });
      } else {
        // Send SIGTERM to children - they should exit cleanly
        try { process.kill(backend.pid, 'SIGTERM'); } catch (_) { }
        try { process.kill(frontend.pid, 'SIGTERM'); } catch (_) { }
      }
    } catch (_) { }
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  let leaseSequence = 1;
  // Tempdoc 606 3b: set while the reaper deliberately tears the stack down, so the
  // backend/frontend exit handlers below don't process.exit() mid-cleanup (killing the
  // supervisor before stopRun clears active.json + writes the reaped_abandoned report).
  let reaping = false;
  renewalInterval = setInterval(async () => {
    try {
      leaseSequence += 1;
      const current = await readJsonIfExists(activePath);
      if (current?.runId !== runId) return;
      // Tempdoc 606 1d: presence-aware renewal. The lease is renewed by THIS detached
      // supervisor, decoupled from the owning agent session — so freshness is a false
      // liveness signal. Re-couple it: if the owner's general activity has gone stale
      // (session ended/crashed/silent), STOP renewing so the lease expires and the
      // existing stale-reclaim admission path frees the stack. Absence of any activity
      // stamp is treated as present (conservative — never reap on missing signal).
      const ownerActivity = readSessionActivity(sessionsDir, current?.holder?.agentSessionId);
      const { known, generalStale } = classifyActivity(ownerActivity, Date.now(), DEFAULT_THRESHOLDS);
      if (known && generalStale) {
        // Tempdoc 606 3b reaper: after a grace period of total silence, the supervisor
        // self-terminates to free VRAM/RAM/ports (a zombie stack from a long-gone session
        // is a real cost on a memory-pressured single-GPU host). Until grace, just pause
        // renewal so the lease lapses and a waiter polling the verdict can reclaim sooner.
        const lastT = ownerActivity?.lastActivityAt ? new Date(ownerActivity.lastActivityAt).getTime() : 0;
        const abandonedMs = Date.now() - lastT;
        const REAPER_GRACE_MS = Number(process.env.JUSTSEARCH_DEV_REAPER_GRACE_MS) > 0
          ? Number(process.env.JUSTSEARCH_DEV_REAPER_GRACE_MS)
          : 5 * 60_000;
        // Tempdoc 735 G6 completion: a declared campaign-length hold (--lease-duration-sec)
        // is INTENT — the owner said "I will be busy-but-quiet for this long". The reaper
        // honors it: the abandoned threshold is at least the declared lease duration. With
        // the default 30s lease this is a no-op (default thresholds dominate). Proven gap
        // 2026-07-14: a 1k-doc enrichment wait with a quiet owner session was reaped at
        // ~10 min (disposition reaped_abandoned) while the stack was doing exactly what the
        // owner started it for.
        const declaredHoldMs = (opts.leaseDurationSec ?? DEFAULT_LEASE_DURATION_SEC) * 1000;
        const abandonedThresholdMs = Math.max(
          DEFAULT_THRESHOLDS.abandonedAfterMs + REAPER_GRACE_MS,
          declaredHoldMs + REAPER_GRACE_MS,
        );
        if (abandonedMs > abandonedThresholdMs) {
          process.stderr.write(
            `[dev-runner] Reaping abandoned stack (owner silent ${Math.round(abandonedMs / 1000)}s, ` +
            `no successor) — freeing resources.\n`);
          reaping = true; // suppress the backend-exit auto-exit so stopRun finishes cleanup
          clearInterval(renewalInterval);
          await stopRun({
            runId,
            disposition: 'reaped_abandoned',
            actor: { source: 'dev-runner', agentSessionId: current?.holder?.agentSessionId ?? null },
            victim: { runId, holder: current?.holder ?? null },
          }).catch(() => {});
          process.exit(0); // cleanup done (active.json removed + stop-report written)
          return;
        }
        process.stderr.write(
          `[dev-runner] Owner ${current?.holder?.agentSessionId ?? '?'} is silent — ` +
          `pausing lease renewal so the stack can be reclaimed (lapses within its ` +
          `${opts.leaseDurationSec}s TTL).\n`);
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
          sequence: leaseSequence,
        },
        updatedAt: now,
      });
    } catch (_) { /* best-effort renewal */ }
  }, 10_000);

  // ============================================================================================
  // Lane F stage B item B8 — the supervisor state machine.
  //
  // What this replaces: "child died -> write a stop report -> process.exit(code)". That was not a
  // policy, it was the absence of one; a crashed Engine took the dev-runner with it and the stack
  // stayed down until a human noticed. The states are design 7.1's — starting / running / stopping
  // / restarting / exhausted — and every transition is published to <dataDir>/runtime/supervisor.v1.json.
  //
  // The DECISION is not here. `engineSupervisor.decide(observation, policy)` answers what to do, and
  // the Tauri shell answers the same question with the same table read from the same register. What
  // is here is the part a pure function cannot do: spawn, wait for the handle, sleep, kill.
  // ============================================================================================
  const supervisionPolicy = engineSupervisor.loadPolicy();
  const { ACTIONS, STATES } = engineSupervisor;

  let supervisorState = STATES.STARTING;
  let incarnation = 1;
  let restartCount = 0;
  let readyAt = null;
  let essentialReadySince = null;
  let hangTimer = null;
  let handoffWatchTimer = null;
  let requestDeadlineTimer = null;
  let consecutiveHealthMisses = 0;
  let observedRequestReason = null;
  let lastExitRecord = null;
  let supervising = true;

  const publishSupervisorState = async (state, extra = {}) => {
    supervisorState = state;
    await writeSupervisorState(dataDir, buildSupervisorState({
      state,
      runId,
      incarnation,
      pid: backend?.pid ?? null,
      apiPort: apiPortActual,
      instanceId: manifestInstanceId,
      restartCount,
      policy: supervisionPolicy,
      lastExit: lastExitRecord,
      requestedReason: observedRequestReason,
      readyAt,
      updatedAt: nowIso(),
      ...extra,
    }));
  };

  const clearSupervisorTimers = () => {
    for (const timer of [hangTimer, handoffWatchTimer, requestDeadlineTimer]) {
      if (!timer) continue;
      clearTimeout(timer);
      clearInterval(timer);
    }
    essentialReadySince = null;
    hangTimer = null;
    handoffWatchTimer = null;
    requestDeadlineTimer = null;
  };

  /**
   * Record one incarnation's death.
   *
   * Called on EVERY death, not only the last one — that is what item B8 means by "the stop report
   * becomes a per-incarnation record rather than the runner's last act". Before this, a run that
   * died three times produced one report, describing the third death, and the first two were
   * unrecoverable. `stop-report.json` still names the most recent death so every existing reader
   * keeps working; `incarnations/<n>/stop-report.json` is the per-death record.
   */
  const recordIncarnationDeath = async (exitCode) => writeSelfExitStopReport({
    runId,
    runPath,
    run: runJson,
    backendExitCode: exitCode,
    interactive: shuttingDown,
    incarnation,
  }).catch(() => { });

  /** Terminal: publish the final state and stop being a supervisor. */
  const finishSupervision = async (state, exitCode, reason) => {
    supervising = false;
    clearSupervisorTimers();
    await publishSupervisorState(state, { reason });
    onExit();
    process.exit(exitCode != null && exitCode !== 0 ? exitCode : 0);
  };

  /**
   * Ask the Engine to stop, out of band, and arm the deadline that covers it never reading the file.
   *
   * Re-entrancy is guarded, and the guard is load-bearing rather than tidy: the liveness probe has a
   * 1 s timeout and the poll interval can be shorter than that, so several probes are in flight at
   * once against a hung Engine and each can independently reach the miss threshold. Without the
   * guard, each would write the request and arm its OWN deadline; only the last would be tracked in
   * `requestDeadlineTimer`, and the untracked ones would survive the Engine's clean exit and fire a
   * `taskkill` at an incarnation that had done nothing wrong. Observed as an intermittent "hang-soft
   * was force-killed" in the conformance harness.
   */
  const requestEngineShutdown = async (reason) => {
    if (supervisorState === STATES.STOPPING && observedRequestReason === reason) return;
    observedRequestReason = reason;
    supervisorState = STATES.STOPPING;
    const deadlineEpochMs = Date.now() + supervisionPolicy.gracefulStopDeadlineMs;
    await writeShutdownRequestFile(dataDir, { reason, deadlineEpochMs });
    // Narrated because the file is transient by design — the Engine deletes it on consumption — so
    // without a line here the graceful arm leaves no trace at all when it works.
    process.stderr.write(
      `[dev-runner] wrote a shutdown request: reason=${reason}, deadline in `
      + `${supervisionPolicy.gracefulStopDeadlineMs}ms, then a forced kill.\n`);
    await publishSupervisorState(STATES.STOPPING, { reason });
    if (requestDeadlineTimer) clearTimeout(requestDeadlineTimer);
    requestDeadlineTimer = setTimeout(() => {
      if (!supervising || supervisorState !== STATES.STOPPING) return;
      const action = engineSupervisor.decide(
        { event: 'request-deadline-elapsed', requestedReason: reason, restartCount, state: supervisorState },
        supervisionPolicy,
      );
      if (action.action !== ACTIONS.FORCE_KILL) return;
      // The admission the request file makes: it is a request, never a guarantee. A JVM wedged at a
      // safepoint never reads it, and this is what ends that.
      process.stderr.write(
        `[dev-runner] FORCED KILL: the Engine ignored the ${reason} request for `
        + `${supervisionPolicy.gracefulStopDeadlineMs}ms.\n`);
      forceKillEngineTree(backend?.pid);
      if (reason === 'quit' || reason === 'upgrade') {
        cleanupRegisteredChildrenForTerminal(dataDir);
      }
    }, supervisionPolicy.gracefulStopDeadlineMs);
    requestDeadlineTimer.unref?.();
  };

  /**
   * Liveness polling. `running` only: a booting Engine answers nothing for seconds and one running
   * its ordered shutdown stops answering by design, so hang detection is suspended in `starting` and
   * `stopping` (design 7.1). The interval and the threshold are PLACEHOLDERS set with the collector
   * at stage E — this proves the path fires, never that it fires within a tuned budget.
   */
  let probeInFlight = false;
  const armHangDetection = () => {
    if (hangTimer) clearInterval(hangTimer);
    hangTimer = setInterval(async () => {
      if (!supervising || supervisorState !== STATES.RUNNING) return;
      if (probeInFlight) return;
      probeInFlight = true;
      const probedChild = backend;
      const probedInstance = manifestInstanceId;
      const probedPort = apiPortActual;
      const stillCurrent = () => supervising && supervisorState === STATES.RUNNING
        && backend === probedChild && manifestInstanceId === probedInstance && apiPortActual === probedPort;
      try {
        const alive = await checkHttp200(`http://127.0.0.1:${probedPort}/api/health`, 1000, true);
        if (!stillCurrent()) return;
        if (alive) {
          consecutiveHealthMisses = 0;
          const status = await fetchJsonHttp(`http://127.0.0.1:${probedPort}/api/status`, 1000);
          if (!stillCurrent()) return;
          if (!essentialStatusReady(status)) { essentialReadySince = null; return; }
          const observedAt = performance.now();
          essentialReadySince ??= observedAt;
          if (observedAt - essentialReadySince >= supervisionPolicy.stabilityWindowMs && restartCount > 0) {
            const action = engineSupervisor.decide(
              { event: 'stability-elapsed', restartCount, state: supervisorState }, supervisionPolicy);
            if (action.action === ACTIONS.RESET_BUDGET) {
              restartCount = 0;
              await publishSupervisorState(STATES.RUNNING);
            }
          }
          return;
        }
        essentialReadySince = null;
        consecutiveHealthMisses += 1;
        const action = engineSupervisor.decide(
          {
            event: 'health-miss',
            consecutiveMisses: consecutiveHealthMisses,
            restartCount,
            state: supervisorState,
          },
          supervisionPolicy,
        );
        if (action.action !== ACTIONS.REQUEST_SHUTDOWN) return;
        process.stderr.write(
          `[dev-runner] Engine missed ${consecutiveHealthMisses} liveness polls while alive — `
          + 'treating as a hang and requesting shutdown.\n');
        consecutiveHealthMisses = 0;
        await requestEngineShutdown('hang');
      } finally {
        probeInFlight = false;
      }
    }, supervisionPolicy.hangPollIntervalMs);
    hangTimer.unref?.();
  };

  /** Latch the admitted Engine's handoff once; later manifest writes cannot extend close. */
  const armHandoffWatch = () => {
    if (handoffWatchTimer) clearInterval(handoffWatchTimer);
    const startedAt = performance.now();
    handoffWatchTimer = setInterval(() => {
      if (!supervising || supervisorState !== STATES.RUNNING) return;
      // Harness-only host input exercises the production owner/writer after readiness. It does
      // not write the Engine's channel from the adapter or add a product command artifact.
      const hostRequest = harnessActive && incarnation === 1
        ? process.env.JUSTSEARCH_SUPERVISOR_HARNESS_REQUEST_REASON : null;
      if (hostRequest && performance.now() - startedAt >= 500
          && ['quit', 'restart', 'upgrade', 'hang'].includes(hostRequest)) {
        void requestEngineShutdown(hostRequest);
        return;
      }
      let manifest;
      try {
        manifest = JSON.parse(fs.readFileSync(path.join(dataDir, 'runtime', 'manifest.json'), 'utf8'));
      } catch { return; }
      const reason = engineSupervisor.shutdownHandoffReason(manifest, backend?.pid, manifestInstanceId);
      if (!reason) return;
      const closingChild = backend;
      const closingInstance = manifestInstanceId;
      supervisorState = STATES.STOPPING;
      void publishSupervisorState(STATES.STOPPING, { reason });
      requestDeadlineTimer = setTimeout(() => {
        if (!supervising || supervisorState !== STATES.STOPPING
            || backend !== closingChild || manifestInstanceId !== closingInstance) return;
        // A local close that exceeds the bound is a charged hang, including exit-code races.
        observedRequestReason = 'hang';
        process.stderr.write(`[dev-runner] FORCED KILL: Engine-local ${reason} close exceeded its deadline.\n`);
        forceKillEngineTree(closingChild.pid);
      }, supervisionPolicy.gracefulStopDeadlineMs);
      requestDeadlineTimer.unref?.();
    }, 50);
    handoffWatchTimer.unref?.();
  };

  const enterRunning = async () => {
    readyAt = nowIso();
    consecutiveHealthMisses = 0;
    await publishSupervisorState(STATES.RUNNING);
    essentialReadySince = null;
    armHangDetection();
    armHandoffWatch();
  };

  /** Start the next incarnation, keeping the run id, the lease and the four log streams. */
  const startNextIncarnation = async () => {
    incarnation += 1;
    observedRequestReason = null;
    clearStaleDiscoveryFiles();
    await publishSupervisorState(STATES.STARTING);
    // Request the port the dead incarnation actually bound rather than a fresh ephemeral one: the
    // Vite dev server was started with VITE_JUSTSEARCH_API_PORT baked in, and run.json's readers
    // resolve through it. Whatever is actually bound is written back below, so a port that could not
    // be reused produces a corrected record instead of a lie.
    backend = spawnEngineChild(apiPortActual);
    attachEngineExitHandler(backend);
    // The start deadline applies HERE and not to the first boot, and the distinction is real rather
    // than convenient: `start` is synchronous for its caller, so a first incarnation that never
    // publishes a port already fails the command with an error a human reads. A RESTART has no such
    // caller — nobody is waiting on it — so without a deadline the supervisor would sit in
    // `starting` forever, which is the one state design 7.1 suspends hang detection in.
    const next = await awaitEngineIncarnation({
      portTimeoutMs: Math.min(supervisionPolicy.startDeadlineMs, portEmitTimeoutMs),
      readyTimeoutMs: Math.min(supervisionPolicy.startDeadlineMs, backendReadyTimeoutMs),
    });
    apiPortActual = next.apiPort;
    manifestInstanceId = next.instanceId;
    runJson.apiPortActual = apiPortActual;
    runJson.apiBaseUrl = `http://127.0.0.1:${apiPortActual}`;
    runJson.pids.backendRootPid = backend.pid;
    runJson.incarnation = incarnation;
    runJson.resourceClaims.apiPort = apiPortActual;
    runJson.resourceClaims.runtimeManifestInstanceId = manifestInstanceId;
    await writeJsonAtomic(runPath, runJson);
    await enterRunning();
  };

  const onEngineExit = async (code) => {
    if (reaping || !supervising) return; // a deliberate reap owns teardown + exit
    // Tempdoc 819 §D: an external `stop` (a DIFFERENT OS process — the in-process `reaping` flag
    // above can't see it) may have just POSTed /api/lifecycle/shutdown and be waiting on this
    // very exit. It drops a marker file right before the POST so this handler can tell "I asked
    // for this" apart from "it crashed on its own" and skip writing a report that would race
    // stopRun's own (authoritative) one for the same run. stopRun deletes the marker once it is
    // done reacting to the exit, so a later, unrelated crash in this same run is never masked.
    if (fs.existsSync(path.join(path.dirname(runPath), 'graceful-shutdown.json'))) {
      supervising = false;
      clearSupervisorTimers();
      onExit();
      process.exit(code != null && code !== 0 ? code : 0);
      return;
    }
    if (requestDeadlineTimer) {
      clearTimeout(requestDeadlineTimer);
      requestDeadlineTimer = null;
    }

    const decision = engineSupervisor.decide(
      {
        event: 'exit',
        exitCode: code,
        requestedReason: observedRequestReason,
        restartCount,
        state: supervisorState,
      },
      supervisionPolicy,
    );
    // The record carries the class the BUDGET used, not the class the integer alone implies, and the
    // two differ exactly where it matters: an Engine that exits 0 because the supervisor asked it to
    // stop for a HANG exits with the code of a clean shutdown, and a state file that called that
    // REQUESTED would tell a reader the death was free when it was charged. `codeClass` keeps the
    // raw reading beside it so neither has to be inferred from the other.
    lastExitRecord = {
      code,
      reason: decision.reason ?? engineSupervisor.describeExit(code, supervisionPolicy),
      class: decision.exitClass ?? engineSupervisor.classifyExit(code, supervisionPolicy),
      codeReason: engineSupervisor.describeExit(code, supervisionPolicy),
      codeClass: engineSupervisor.classifyExit(code, supervisionPolicy),
      counted: decision.counted === true,
      requestedReason: observedRequestReason,
      incarnation,
      at: nowIso(),
    };

    // Tempdoc 730 B1/B2, re-cut per incarnation: the exit code and this incarnation's engine.log
    // land BEFORE the next incarnation can append to (or truncate) the shared file.
    const preserved = await preserveEngineLog(runJson, runPath, {
      destSubdir: path.join('incarnations', String(incarnation)),
    }).catch(() => null);
    await recordIncarnationDeath(code);

    process.stderr.write(
      `[dev-runner] Engine incarnation ${incarnation} exited ${code} (${lastExitRecord.reason}, `
      + `${lastExitRecord.class}); decision=${decision.action}`
      + `${decision.counted ? ` counted ${restartCount + 1}/${supervisionPolicy.maxRestartAttempts}` : ''}`
      + `${preserved?.preserved ? ` engineLog=${preserved.path}` : ''}\n`);

    if (decision.action === ACTIONS.STOP) {
      await finishSupervision(STATES.STOPPING, code, decision.reason);
      return;
    }
    if (decision.action === ACTIONS.EXHAUSTED) {
      // Design 7.1's terminal state. The file is what makes it observable: the Engine is down, so
      // no engine API can carry this, and the updater (item B13) reads exactly this record.
      process.stderr.write(
        `[dev-runner] ENGINE_RESTART_EXHAUSTED — ${decision.reason}. Supervisor state: `
        + `${toPosix(path.relative(repoRoot, supervisorStatePath(dataDir)))}\n`);
      await finishSupervision(STATES.EXHAUSTED, code, `ENGINE_RESTART_EXHAUSTED:${decision.reason}`);
      return;
    }

    if (decision.counted) restartCount += 1;
    await publishSupervisorState(STATES.RESTARTING, { reason: decision.reason });

    // The cooldown FLOOR is the handle closing, and only then the linear step (stage B §2).
    const release = await waitForEngineHandleRelease({ pid: backend?.pid, dataDir });
    if (!release.released) {
      process.stderr.write(
        `[dev-runner] proceeding with the restart after ${release.waitedMs}ms without a clean handle `
        + 'release — a boot that finds the data directory still locked will exit non-transient.\n');
    }
    if (decision.cooldownMs > 0) {
      await new Promise((r) => setTimeout(r, decision.cooldownMs));
    }
    if (!supervising) return;
    // The incarnation this call is about to start. A restart is asynchronous and the child's exit
    // handler is not: if THIS incarnation dies before it comes up, its own onEngineExit runs, decides
    // a restart of its own, and advances the counter — while the await below is still pending and
    // will reject on its timeout. Without this witness the stale rejection would request a shutdown
    // of an incarnation that had just started and done nothing wrong.
    const startedIncarnation = incarnation + 1;
    try {
      await startNextIncarnation();
    } catch (err) {
      if (!supervising) return; // the incarnation died while starting; its own exit owns the outcome
      if (incarnation !== startedIncarnation) return; // a later incarnation owns the outcome now
      // The start deadline elapsed: the incarnation is alive but has published nothing. Hang
      // detection is suspended in `starting`, so this edge is the only thing that ends the state —
      // and it ends it the same way a hang does, because the two are the same problem seen at
      // different times: a process that is up and not serving.
      const action = engineSupervisor.decide(
        { event: 'start-deadline-elapsed', restartCount, state: STATES.STARTING },
        supervisionPolicy,
      );
      process.stderr.write(
        `[dev-runner] incarnation ${incarnation} did not come up: ${err?.message ?? err}\n`);
      if (action.action === ACTIONS.REQUEST_SHUTDOWN) {
        await requestEngineShutdown(action.reason);
        return; // the forced kill and the resulting exit take the death path from here
      }
      await finishSupervision(STATES.EXHAUSTED, 1, `restart_failed:${err?.message ?? err}`);
    }
  };

  function attachEngineExitHandler(child) {
    child.on('exit', (code) => {
      onEngineExit(code).catch((err) => {
        process.stderr.write(`[dev-runner] supervisor failed: ${err?.stack ?? err}\n`);
        onExit();
        process.exit(1);
      });
    });
  }

  attachEngineExitHandler(backend);
  await enterRunning();

  frontend.on('exit', (code) => {
    if (reaping) return; // deliberate reap owns teardown + exit
    onExit();
    if (code != null && code !== 0) process.exit(code);
    process.exit(0);
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

async function stopRun(opts) {
  const { run, runPath } = await resolveRunTarget(opts);
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

  const killedPids = [];
  const errors = [];
  // Tempdoc 730 B2: per-PID liveness probe taken BEFORE each kill attempt — distinguishes
  // "backend already dead, reaper cleaned up the shell" from "backend live, reaper killed an
  // abandoned-but-healthy stack" (the ambiguity §THEORIZE B flagged in `reaped_abandoned`).
  const pidLiveness = [];

  let taskkillExitCode = null;
  let taskkillStderrTail = '';

  const taskkill = async (pid, role = null) => {
    if (!pid || !Number.isFinite(pid) || pid <= 0) {
      // Tempdoc 730 Increment-4 review: an invalid/missing pid for a named role is itself a
      // liveness fact ("this role had no PID to probe"), not silence — record the null-shape
      // entry so a stop-report reader can distinguish "role absent from the report" (report
      // predates B2) from "role present but had no PID" instead of the entry just not existing.
      if (role) pidLiveness.push({ role, pid: null, aliveBeforeKill: null });
      return;
    }
    if (role) pidLiveness.push({ role, pid, aliveBeforeKill: isPidAlive(pid) });
    if (process.platform !== 'win32') {
      try {
        process.kill(pid, 'SIGKILL');
        killedPids.push(pid);
      } catch (_) { }
      return;
    }
    await new Promise((resolve) => {
      const args = role === 'frontend'
        ? ['/PID', String(pid), '/T', '/F']
        : ['/PID', String(pid), '/F'];
      const p = spawn('taskkill', args, { windowsHide: true });
      let stderr = '';
      if (p.stderr) {
        p.stderr.on('data', (b) => {
          stderr += b.toString('utf8');
        });
      }
      p.on('close', (code) => {
        taskkillExitCode = code;
        taskkillStderrTail = stderr.split(/\r?\n/).slice(-10).join('\n');
        killedPids.push(pid);
        resolve();
      });
    });
  };

  // Tempdoc 819 §D: try the graceful ordered shutdown FIRST, before anything in the runner's
  // process tree is touched. The backend is a non-detached child of the runner (spawnLogged with
  // no `detached: true` — see `pids.backendRootPid: backend.pid` at run.json write time), so the
  // runner taskkill below uses `/T` (whole-tree) and would kill the backend JVM out from under a
  // graceful attempt made after it — with no JVM shutdown hook, exactly the outcome this change
  // exists to avoid. Confirmed against a captured stop-report from before this reordering:
  // backend `aliveBeforeKill: false` with `taskkillStderrTail: "process ... not found"`, i.e. the
  // runner's `/T` sweep had already taken it down.
  //
  // `gracefulShutdownMarkerPath` is a cross-process signal: this `stop` invocation and the
  // long-running supervisor holding the backend/frontend children are DIFFERENT OS processes (the
  // in-process `reaping` flag below only covers the same-process reaper path), so an in-memory
  // flag can't reach the supervisor's `backend.on('exit')` handler. The marker file is how it
  // learns "this exit was requested by an external stop" and skips writing its own racing
  // `writeSelfExitStopReport` (see the handler in cmdStart).
  const backendRootPid = Number(run?.pids?.backendRootPid);
  const gracefulShutdownMarkerPath = path.join(path.dirname(runPath), 'graceful-shutdown.json');
  const gracefulBackendShutdown =
    await maybeGracefulBackendShutdown(apiPort, backendRootPid, gracefulShutdownMarkerPath);

  // Prefer killing the runner (tree) if recorded; it owns backend/frontend stdin pipes.
  // Tempdoc 606 3b: but when stopRun is invoked IN-PROCESS by the supervisor's own reaper
  // (process.pid === runnerPid), taskkill /T on runnerPid would nuke this very process tree
  // before the cleanup below (stop-report + active.json removal) runs. Skip the self-kill in
  // that case — the frontend/backend taskkills still free all resources, cleanup completes,
  // and the reaper then process.exit(0)s itself. Cross-process callers (stop / takeover) have
  // a different pid and still kill the victim's runner tree.
  const stopRunnerPid = Number(run?.pids?.runnerPid);
  if (stopRunnerPid && stopRunnerPid !== process.pid) await taskkill(stopRunnerPid, 'runner');
  await taskkill(Number(run?.pids?.frontendRootPid), 'frontend');

  if (gracefulBackendShutdown.outcome === 'exited') {
    // Backend exited on request — it WAS alive when we asked it to stop (maybeGracefulBackend-
    // Shutdown only proceeds past its `isPidAlive` precondition when true), so the pre-kill
    // liveness fact is true, not false; `gracefulBackendShutdown.outcome` is what disambiguates
    // "we shut it down" from "found it already dead" for a death investigation, not this field.
    pidLiveness.push({ role: 'backend', pid: backendRootPid, aliveBeforeKill: true });
  } else {
    await taskkill(backendRootPid, 'backend');
  }
  if (dataDirAbs) cleanupRegisteredChildrenForTerminal(dataDirAbs);

  // Tempdoc 819 §D: best-effort cleanup — the marker's job (letting the supervisor's exit handler
  // know not to write a racing report) is done once we reach here regardless of outcome: either
  // the backend already exited (and the handler already made its call), or we're about to
  // taskkill it ourselves (forceful kill; no graceful exit event for the marker to matter to).
  try { await fsp.rm(gracefulShutdownMarkerPath, { force: true }); } catch (_) { /* best-effort */ }

  const portInfo = async (port) => {
    const listening = port > 0 ? await isTcpListening(port, 500) : false;
    if (!listening) return { port, closed: true };
    if (process.platform !== 'win32') return { port, closed: false };
    const owner = await getPortOwnerWindows(port);
    const ownerPid = owner?.pid ?? null;
    const cmd = ownerPid ? await getCommandLineWindows(ownerPid) : null;
    return {
      port,
      closed: false,
      ownerPid,
      ownerCommandLine: cmd,
      boundAddresses: owner?.address ? [owner.address] : [],
    };
  };

  // Verify ports closed (bounded).
  const deadline = Date.now() + 10_000;
  let apiInfo = await portInfo(apiPort);
  let uiInfo = await portInfo(uiPort);

  while (Date.now() < deadline && (!apiInfo.closed || !uiInfo.closed)) {
    // If a port is still open, try killing the owning PID (best-effort).
    for (const inf of [apiInfo, uiInfo]) {
      if (!inf || inf.closed) continue;
      if (process.platform === 'win32' && inf.ownerPid) {
        // eslint-disable-next-line no-await-in-loop
        await taskkill(inf.ownerPid, inf === apiInfo ? 'port_owner_api' : 'port_owner_ui');
      }
    }
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 250));
    // eslint-disable-next-line no-await-in-loop
    apiInfo = await portInfo(apiPort);
    // eslint-disable-next-line no-await-in-loop
    uiInfo = await portInfo(uiPort);
  }

  if (!apiInfo.closed) errors.push(`API port ${apiPort} still listening after stop timeout`);
  if (!uiInfo.closed) errors.push(`UI port ${uiPort} still listening after stop timeout`);

  // Tempdoc 730 B1, re-homed at item A16: snapshot the engine log into this run's own dir, so a
  // death run's evidence survives alongside its stop-report instead of only in the shared,
  // cross-run <dataDir>/logs/engine.log.
  const engineLog = await preserveEngineLog(run, runPath);

  const stopReport = buildStopReport({
    runId,
    stoppedAt: nowIso(),
    disposition,
    actor,
    victim,
    taskkillExitCode,
    taskkillStderrTail,
    killedPids,
    pidLiveness,
    ports: { api: apiInfo, ui: uiInfo },
    portsClosed: apiInfo.closed && uiInfo.closed,
    errors,
    engineLog,
    // Tempdoc 542 §B Layer 4 — make interrupted critical/loss op-leases part of the
    // permanent audit record. Tells the operator what was lost on a `force` takeover.
    criticalOpsInterrupted,
    interruptibleWithLossInterrupted,
    gracefulBackendShutdown,
  });

  const stopReportPath = path.join(path.dirname(runPath), 'stop-report.json');
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

  // Clear active pointer if it points to this run.
  const active = await readJsonIfExists(activePath);
  if (active?.runId === runId) {
    await fsp.rm(activePath, { force: true }).catch(() => { });
  }

  return {
    ok: true,
    runId,
    killedPids,
    portsClosed: stopReport.portsClosed,
    stopReportPath: toPosix(path.relative(repoRoot, stopReportPath)),
  };
}

async function cmdCleanup(opts) {
  let run = null;
  try {
    ({ run } = await resolveRunTarget(opts));
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(JSON.stringify({ ok: true, runId: null, portsClosed: true, note: 'no_active_run' }) + '\n');
      return;
    }
    throw err;
  }
  const stopRes = await stopRun({ ...opts, runId: run.runId, disposition: 'normal_stop' });
  if (opts.clean !== 'none') {
    const dir = run?.dataDir ? path.resolve(repoRoot, run.dataDir) : null;
    if (dir) await cleanDataDir(dir, opts.clean);
  }
  process.stdout.write(JSON.stringify({ ok: true, runId: run.runId, portsClosed: stopRes.portsClosed }) + '\n');
  if (!stopRes.portsClosed && !opts.force) process.exit(1);
}

async function cmdStop(opts) {
  // Session-scoped ownership gate: reject stop if another session owns the stack
  if (!opts.force) {
    const active = await readJsonIfExists(activePath);
    if (active && active.holder?.agentSessionId) {
      const callerSession = resolveAgentSessionId(opts.sessionId);
      if (callerSession && callerSession !== active.holder.agentSessionId) {
        const err = new Error('OWNER_CONFLICT: stop rejected — another session owns the stack');
        err.code = 'OWNER_CONFLICT';
        err.holder = active.holder;
        err.lease = active.lease;
        throw err;
      }
    }
  }
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
  process.stdout.write(JSON.stringify(res) + '\n');
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
            ...(err?.stack ? { stack: String(err.stack) } : {}),
          },
        }) + '\n',
      );
    } else {
      console.error(err?.stack || String(err));
    }
    process.exit(1);
  });
} else {
  module.exports = {
    __test: {
      pruneHistoricRuns,
      resolveDataDir,
      cleanDataDir,
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
      resolveMainRepoRoot,
      mainRepoRoot,
      repoRoot,
      resolveCuda12ServerExe,
      stageSharedCuda12,
      // Tempdoc 730 Increment 4 (B1/B2/B3)
      preserveEngineLog,
      buildStopReport,
      buildHeadJavaOpts,
      writeSelfExitStopReport,
      // Lane F stage B item B8: the supervisor's actuator helpers. The DECISION is not here —
      // scripts/dev/lib/engine-supervisor.cjs owns it and the Rust half reads the same register.
      checkHttp200,
      fetchJsonHttp,
      essentialStatusReady,
      buildSupervisorState,
      writeSupervisorState,
      supervisorStatePath,
      supervisorHistoryPath,
      shutdownRequestPath,
      writeShutdownRequestFile,
      waitForEngineHandleRelease,
      forceKillEngineTree,
      cleanupRegisteredChildrenForTerminal,
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
