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

// Tempdoc 730 B1: worker.log lives under the (persistent, cross-run) dataDir and is rotated by
// WorkerSpawner.java on the NEXT worker spawn — a fixed 2-generation rotation that a death run's
// log can fall out of before anyone reads it (the reproduced incident: the death run's log was
// already gone by the time it was inspected). Copy THIS run's current worker.log into the run's
// OWN directory at stop time, while stopRun still knows unambiguously which run it belongs to —
// this converts every future death from "inconclusive" (log overwritten) to "diagnosable".
//
// Tempdoc 730 Increment-4 review findings (2026-07-14): the naive guard "current file's mtime >=
// the readiness-time stamp's mtime => it's still ours" is WRONG — a worker.log legitimately grows
// during a run (mtime keeps advancing), but so does a LATER run's overwrite of the same shared
// path, and that later mtime is *also* >= the earlier run's stamp. That guard would silently file
// run B's content as run A's "verified" log (the reap-after-restart mislabel case).
//
// The reviewed plan's first choice of guard was file-identity via birthtime (WorkerSpawner
// rotates by RENAMING worker.log -> worker.log.1 -> worker.log.2 on the next spawn, and a rename
// preserves birthtime while a fresh spawn's newly-created file gets a new one) — but a live probe
// on this Windows/NTFS checkout disproved that assumption: NTFS file-system tunneling (the OS
// caching a short-lived deleted/renamed-away file's metadata, incl. creation time, and handing it
// back to a file recreated at the SAME path within ~15s) makes a brand-new worker.log inherit the
// OLD file's birthtimeMs — exactly the case this guard needed to tell apart. `git status` isn't
// relevant here; this was reproduced directly: create -> rename-away -> recreate-at-same-path ->
// the recreated file's birthtimeMs matched the original's, indistinguishable from true identity.
// So this substitutes the plan's named fallback: SIZE-MONOTONICITY (a worker.log is append-only —
// it only grows while a run owns it; a value smaller than what was stamped at readiness proves
// the path was rotated/replaced under us) combined with rotation-NAME matching (worker.log.1/.2
// are exactly where WorkerSpawner puts what it rotated away).
async function preserveWorkerLog(run, runPath) {
  const dataDirAbs = run?.dataDir ? path.resolve(repoRoot, run.dataDir) : null;
  if (!dataDirAbs) return { preserved: false, reason: 'no_data_dir' };
  const logsDirAbs = path.join(dataDirAbs, 'logs');
  const srcWorkerLog = path.join(logsDirAbs, 'worker.log');
  const destLogsDir = path.join(path.dirname(runPath), 'logs');
  const destWorkerLog = path.join(destLogsDir, 'worker.log');
  const stamp = run?.workerLogStamp || null;

  const copyFrom = async (sourcePath, extra) => {
    try {
      await mkdirp(destLogsDir);
      await fsp.copyFile(sourcePath, destWorkerLog);
      return { preserved: true, path: toPosix(path.relative(repoRoot, destWorkerLog)), ...extra };
    } catch (err) {
      return { preserved: false, reason: 'copy_failed', error: err?.message || String(err) };
    }
  };

  if (!stamp) {
    // Older run.json predates the ownership stamp (B1's original behavior) — best-effort copy,
    // but the result says so explicitly rather than silently claiming verified ownership.
    if (!fs.existsSync(srcWorkerLog)) return { preserved: false, reason: 'no_worker_log' };
    return copyFrom(srcWorkerLog, { ownership: 'unstamped' });
  }

  const statOrNull = (filePath) => {
    try { return fs.statSync(filePath); } catch { return null; }
  };
  // "Still consistent with THIS run's file": never shrunk below what was stamped at readiness,
  // and never moved backward in time. A rotated-away path is replaced by a fresh, small file, so
  // a size drop below the stamp is the tell that the path under us stopped being ours.
  const isMonotonicWith = (st) => !!st && st.size >= stamp.size && st.mtimeMs >= stamp.mtimeMs;

  const currentStat = statOrNull(srcWorkerLog);
  if (isMonotonicWith(currentStat)) {
    return copyFrom(srcWorkerLog, { ownership: 'verified' });
  }

  for (const rotatedName of ['worker.log.1', 'worker.log.2']) {
    const rotatedPath = path.join(logsDirAbs, rotatedName);
    const rotatedStat = statOrNull(rotatedPath);
    if (isMonotonicWith(rotatedStat)) {
      return copyFrom(rotatedPath, { ownership: 'heuristic', source: 'rotated' });
    }
  }

  return { preserved: false, reason: 'ownership_unverified' };
}

// Tempdoc 730 Increment-4 review: capture the ownership-stamp identity of THIS run's worker.log
// at the point cmdStart confirms backend HTTP-readiness — i.e. after WorkerSpawner's own startup
// (and any rotation it performs on spawn) has settled, so the stamp names the log file this run
// actually owns rather than one still mid-rotation. Null when the log doesn't exist yet (e.g. the
// worker hasn't logged anything by the time HTTP readiness is confirmed).
function captureWorkerLogStamp(dataDirAbs) {
  try {
    const st = fs.statSync(path.join(dataDirAbs, 'logs', 'worker.log'));
    return { size: st.size, mtimeMs: st.mtimeMs };
  } catch {
    return null;
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
  workerLog = null,
  criticalOpsInterrupted = null,
  interruptibleWithLossInterrupted = null,
  gracefulBackendShutdown = null,
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
    // Tempdoc 730 B1: where this run's worker.log ended up (or why it didn't).
    ...(workerLog ? { workerLog } : {}),
    ...(criticalOpsInterrupted ? { criticalOpsInterrupted } : {}),
    ...(interruptibleWithLossInterrupted ? { interruptibleWithLossInterrupted } : {}),
    // Tempdoc 819 §D: outcome of the graceful POST /api/lifecycle/shutdown attempt made before
    // the backend taskkill fallback. Additive/optional like the two fields above it — no
    // existing reader depends on its absence, so no schemaVersion bump.
    ...(gracefulBackendShutdown ? { gracefulBackendShutdown } : {}),
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
function buildHeadJavaOpts({ existingJavaOpts, headAotOpts, headDistStamp, logsDir, headHeap }) {
  const heapBound = headHeap && String(headHeap).trim() ? String(headHeap).trim() : null;
  return [
    existingJavaOpts,
    // Lane F PR 0 (design 17.2): one flag set with or without the AOT cache. TieredStopAtLevel=1
    // is gone (its 48 MiB C1-only code cache caused the CodeCache-threshold full GCs 917 Derisk 1
    // measured, and it conflicted with the AOT cache); MetaspaceSize=128m stops the
    // Metaspace-threshold full GCs at start. lib.rs carries the same set; the pairing is pinned by
    // scripts/dev/test-dev-runner-head-java-opts.mjs.
    '-XX:+UseSerialGC -XX:MetaspaceSize=128m -XX:-UsePerfData',
    headAotOpts,
    // Tempdoc 606 Piece 2b: the Head echoes this on /api/runtime/manifest so a
    // stale old Head answering on a reused port is detectable (build mismatch).
    headDistStamp ? `-Djustsearch.head.stamp=${headDistStamp}` : null,
    heapBound ? `-Xmx${heapBound}` : null,
    '-XX:+HeapDumpOnOutOfMemoryError',
    logsDir ? `-XX:HeapDumpPath=${logsDir}` : null,
  ].filter(Boolean).join(' ');
}

// Tempdoc 730 B2: write a stop-report for a backend that exited WITHOUT going through
// stopRun() — i.e. the supervisor's backend.on('exit') fired on its own (crash/OOM) or in
// response to an interactive Ctrl+C, not a `stop`/reap taskkill. Before this, that path wrote
// NO stop-report at all (only onExit() closing the log streams), so a silent death left zero
// exit-code artifact — the exact gap §THEORIZE B names. Also preserves worker.log (B1), since a
// self-exit is precisely the "death run" scenario B1 exists for.
async function writeSelfExitStopReport({ runId, runPath, run, backendExitCode, interactive }) {
  const workerLog = await preserveWorkerLog(run, runPath);
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
    workerLog,
  });
  const stopReportPath = path.join(path.dirname(runPath), 'stop-report.json');
  await writeJsonAtomic(stopReportPath, stopReport);
  return stopReport;
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
 * Content stamp of the launched Head dist (mirrors the Worker's generateBuildStamp,
 * indexer-worker/build.gradle.kts): a short hash over the lib jars' name|size|mtime.
 * Detects both "wrong worktree" (paired with repoRoot) and "stale dist" (jar changed
 * but installDist reported UP-TO-DATE). Null when the dist dir is absent.
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
 * handler's default, and WorkerSpawner's fallback), so `reload` attached to "whatever listens on
 * 5005" with no way to tell whose VM that was. The port is chosen HERE, once, forwarded to the
 * Worker via JUSTSEARCH_DEV_DEBUG_PORT and written into run.json; `reload` reads it from there.
 *
 * `classesDir` is the identity token: WorkerSpawner puts the same absolute path first on the
 * Worker's classpath (R4), so a pusher can confirm over JDI that the VM it attached to is the one
 * this run launched — instead of trusting a port number.
 *
 * An explicit JUSTSEARCH_DEV_DEBUG_PORT still wins (operator override); otherwise the first free
 * port from 5005 upward is taken, so a second stack cannot silently share the first one's port.
 */
/** The one module whose classes dir goes on the Worker classpath for hot reload (R4). */
const HOTRELOAD_MODULE = 'worker-services';

/** Filesystem timestamp slack, so ordinary granularity is not read as a rebuild. */
const HOTRELOAD_STAMP_SKEW_MS = 2000;

/**
 * `<root>/modules/<module>/build/classes/java/main` — the identity-token layout.
 *
 * Three sides agree on this shape: this file writes it into run.json, WorkerSpawner puts the same
 * absolute path first on the Worker classpath (`devHotReloadClassesDir`), and the reload tool
 * parses the module back out of it (`reloadModuleFromClassesDir`). A function rather than an inline
 * join so a test can pin it against the parser instead of restating it.
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
 * Tempdoc 844 M3 — may the hot-reload classes dir go FIRST on the Worker's classpath?
 *
 * R4 prefixes `modules/worker-services/build/classes/java/main` so the pushed bytecode and the
 * classes loaded later come from one tree. That is only true when the classes dir and the
 * installDist jars are the SAME build. With `--skip-build` (passed on 124 of 179 measured starts)
 * installDist does not run, so the two are independently aged: worker-services from build B,
 * everything else from build A's jars, with nothing comparing them — and `freshness.buildArtifact`
 * derives from the dist stamp alone, so it would still say FRESH.
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
        + 'dir cannot be shown to match the jars the Worker launches from. Start without '
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
    libDir: path.join(
      repoRoot, 'modules', 'indexer-worker', 'build', 'install', 'indexer-worker', 'lib'),
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

function checkHttp200(url, timeoutMs) {
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
        res.resume();
        resolve(res.statusCode === 200);
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) => {
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
    const req = http.request(
      { hostname: u.hostname, port: Number(u.port), path: u.pathname + u.search, method: 'GET', timeout: timeoutMs },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          if (res.statusCode !== 200) { resolve(null); return; }
          try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
          catch { resolve(null); }
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', () => resolve(null));
    req.end();
  });
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
  // Measured on this machine (tempdoc 819 §D live verification): Head acks the POST with 202
  // immediately, then runs its ordered close on a daemon thread — manifest, API server, health
  // monitor, HeadAssembly, then knowledgeServer.closeForUpgrade(), which gracefully stops the
  // Worker subprocess. The Worker's "shutdown signal received" landed ~5.5s after the POST and the
  // JVM exited shortly after, so a 5s budget reported `timeout` and force-killed a JVM that was
  // mid-clean-shutdown — destroying the finalizeShutdownCommit() stamp this path exists to
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

// "Ready" here means the HEAD is up (`/api/status` returns 200) — NOT that the Worker is ready. The
// Worker connects/warms up a beat later, and until it is available the WorkerCapability before-handler
// returns 503 ("Knowledge Server not ready") on `/api/knowledge/*`. Consumers that hit worker endpoints
// immediately after "stack up" must tolerate that transient 503 — see stage-reference-corpus.mjs
// stageAndVerify's ingest retry (tempdoc 656 §J/§K.5). (The MCP dev server exposes a separate
// worker-ready readiness level for callers that need it.)
async function waitForBackendReady(apiPort, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  const url = `http://127.0.0.1:${apiPort}/api/status`;
  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    const ok = await checkHttp200(url, 1200);
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
  // Proven live 2026-08-19: after editing WorkerSpawner.java, a `start` without skipBuild launched a
  // Worker with the OLD classpath, and an explicit installDist then did real work. The launched
  // artifacts are now built by name. Warm cost measured in this worktree (config cache reused):
  // assemble alone 891/957/923 ms, assemble + both installDist 1055/1156 ms - about +0.15 s, once
  // per start, to make the message true.
  if (!opts.skipBuild) {
    process.stderr.write(
      '[dev-runner] Ensuring distribution is up-to-date (assemble + installDist)...\n');
    const buildResult = spawnSync(
      gradlePath,
      ['assemble', ':modules:ui:installDist', ':modules:indexer-worker:installDist', '-PskipWebBuild=true'],
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

  // Fail fast if the Head dist doesn't exist (e.g. --skip-build without prior installDist).
  // Without this check, spawn() fails silently and the only feedback is a 60s timeout.
  if (!fs.existsSync(startScript)) {
    const gradleCmd = process.platform === 'win32' ? './gradlew.bat' : './gradlew';
    const remedy = `node scripts/dev/prepare-worktree.cjs (or: ${gradleCmd} :modules:ui:installDist :modules:indexer-worker:installDist)`;
    // Tempdoc 844 B2: a fully-understood, recoverable condition with a printed remedy is NOT an
    // unhandled exception. It surfaced as error code UNHANDLED on 16 of 20 observed `start` errors,
    // which mis-states the severity and puts it outside the documented admission code set.
    // Classified here — the layer that knows the condition — so the MCP wrapper needs no re-derivation.
    const err = new Error(
      `Head dist not found at ${startScript}. Make this checkout dev-ready (tempdoc 618 §3):\n` +
        `  node scripts/dev/prepare-worktree.cjs           # one command: npm ci + both installDists\n` +
        `  or: ${gradleCmd} :modules:ui:installDist :modules:indexer-worker:installDist\n` +
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

  const spawnBackend = {
    cwd: repoRoot,
    command: startScript,
    args: [],
    shell: process.platform === 'win32',
  };

  let apiPortActual = apiPortRequested;
  let portEmitted = false;

  // Tempdoc 501 §3.1 closure-pass: stdout was previously parsed for
  // JUSTSEARCH_API_PORT=<n> as a fast-path discovery channel. That violates
  // the design's closure rule ("one mechanism per concern" — manifest is
  // the canonical discovery path). The stdout line still flows to the log
  // for human observation; only the consumer-side parse-to-state is gone.
  // Port comes exclusively from <dataDir>/runtime/manifest.json read in the
  // wait loop below.

  const backend = spawnLogged(
    spawnBackend.command,
    spawnBackend.args,
    {
      cwd: spawnBackend.cwd,
      env: {
        ...process.env,
        ...aiEnv,
        // Tempdoc 696: pin a >= 24 JDK for the Head JVM (ui.bat prefers JAVA_HOME); the
        // Worker and inference processes the Head spawns inherit this env.
        JAVA_HOME: resolveJdkHome(),
        JUSTSEARCH_API_PORT: String(apiPortRequested),
        JUSTSEARCH_DATA_DIR: dataDir,
        JUSTSEARCH_HOME: dataDir,
        // Tempdoc 842 §2.4: chat model profile ("compact" | "standard"). Ambient operator env
        // always wins (effectiveChatProfile already checked process.env first); the dev default
        // is "compact" even when --chat-profile is omitted entirely.
        JUSTSEARCH_CHAT_PROFILE: effectiveChatProfile,
        // The Worker's shipped default for the io.justsearch logger is INFO, so query text
        // (logged at DEBUG) stays out of diagnostics exports, which bundle logs/ with
        // path-only redaction. Dev has no such exposure and wants the verbose lines, so the
        // dev-runner opts back in — honour an explicit override if the caller set one.
        JUSTSEARCH_LOG_LEVEL: process.env.JUSTSEARCH_LOG_LEVEL || 'DEBUG',
        // Tempdoc 542 §B Layer 3: Head reads this to know where to write op-leases.json.
        // Absent → Head's OperationLeaseService is a no-op (production / non-dev-runner).
        JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
        // Hot-reload: enable JDWP + DevReloadManager on Worker (tempdoc 305).
        // Tempdoc 844 R3: the port comes from the per-run record written into run.json below,
        // so the pusher reads it instead of assuming 5005.
        // Tempdoc 844 M3: set EXPLICITLY in both directions. Spreading process.env above means an
        // ambient JUSTSEARCH_DEV_HOTRELOAD=true would otherwise survive a run where this decided
        // hot reload is off, and the Worker would prefix its classpath with a classes dir the run
        // record says is not there — a mixed classpath that run.json denies.
        JUSTSEARCH_DEV_HOTRELOAD: devHotReload.enabled ? 'true' : 'false',
        ...(devHotReload.enabled ? {
          JUSTSEARCH_DEV_DEBUG_PORT: String(devHotReload.debugPort),
        } : {}),
        // Head startup flags: SerialGC (small heap, no throughput need), MetaspaceSize=128m,
        // -XX:-UsePerfData (skip hsperfdata file); tiered compilation left at its default
        // (lane F PR 0), so the set no longer forks on AOT-cache presence.
        // S1: Pass dev AOT cache flag when available.
        // Tempdoc 730 B3: bounded -Xmx + HeapDumpOnOutOfMemoryError, dumping into THIS run's own
        // logs dir (JUSTSEARCH_HEAD_HEAP overrides the 2g default for constrained devices).
        JAVA_OPTS: buildHeadJavaOpts({
          existingJavaOpts: process.env.JAVA_OPTS,
          headAotOpts,
          headDistStamp: devStackProvenance.headDistStamp,
          logsDir,
          headHeap: process.env.JUSTSEARCH_HEAD_HEAP,
        }),
        // NOTE: justsearch.repo.root is NOT set here. In Tauri production, lib.rs sets it to
        // headless_dir where sidecar ONNX models live. In dev mode, OnnxModelDiscovery's sidecar
        // step is a no-op, so reranker/citation-scorer are inactive. To enable them, set
        // JUSTSEARCH_RERANK_MODEL_PATH and JUSTSEARCH_CITATION_SCORER_MODEL_PATH explicitly.
      },
      shell: spawnBackend.shell,
      windowsHide: spawnBackend.shell,
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

  const spawnFrontend = () => ({
    cwd: uiWebDir,
    command: 'npm',
    args: ['run', 'dev', '--', '--host', '--port', String(uiPort), '--strictPort'],
    shell: process.platform === 'win32',
  });

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
  // Delete any stale files from a previous run to prevent reading the wrong
  // port when using --clean=none (the previous backend may have bound a
  // different ephemeral port).
  const runtimeDir = path.join(dataDir, 'runtime');
  const manifestPath = path.join(runtimeDir, 'manifest.json');
  // Pre-spawn cleanup. api-port.txt is the deprecated mirror (Phase 8) but
  // we still unlink it so a stale --clean=none restart doesn't leave a
  // misleading file around for any legacy consumer.
  try { fs.unlinkSync(path.join(runtimeDir, 'api-port.txt')); } catch { /* ok if absent */ }
  try { fs.unlinkSync(manifestPath); } catch { /* ok if absent */ }

  let manifestInstanceId = null;
  const tryReadManifest = () => {
    try {
      const content = fs.readFileSync(manifestPath, 'utf8');
      const parsed = JSON.parse(content);
      const p = parsed?.head?.apiPort;
      if (Number.isFinite(p) && p > 0) {
        manifestInstanceId = parsed.instanceId ?? null;
        return p;
      }
    } catch { /* not yet written or malformed */ }
    return 0;
  };

  // Tempdoc 501 §3.1: manifest is the sole discovery path. The legacy
  // api-port.txt fallback the wait-loop used to consult was dead code in
  // the dev-runner context (the worktree always builds the current
  // HeadlessApp, which writes both files); removing it tightens the
  // closure ("one mechanism per concern").
  const waitForPortDeadline = Date.now() + portEmitTimeoutMs;
  // Exit the moment the ACTUAL bound port is known. The old guard also OR'd in
  // `apiPortRequested <= 0`, which is permanently true for an ephemeral request (`--api-port 0`,
  // the default) — so the loop discovered the port (below) but ignored it and spun the FULL
  // `portEmitTimeoutMs` regardless (15s local / 300s CI). On CI that 300s exceeded the onramp
  // smoke's 240s startStack budget → deterministic "stack start timed out" (tempdoc 656 §I).
  while (apiPortActual <= 0 && Date.now() < waitForPortDeadline) {
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 100));
    if (!portEmitted && apiPortActual <= 0) {
      const p = tryReadManifest();
      if (p > 0) {
        apiPortActual = p;
        portEmitted = true;
      }
    }
  }

  if (!Number.isFinite(apiPortActual) || apiPortActual <= 0) {
    const seconds = Math.max(1, Math.round(portEmitTimeoutMs / 1000));
    throw new Error(
      `Backend did not emit JUSTSEARCH_API_PORT=<port> within ${seconds}s (requested=${apiPortRequested}).`,
    );
  }

  const apiBaseUrl = `http://127.0.0.1:${apiPortActual}`;
  const uiUrl = `http://localhost:${uiPort}`;

  const readyHttp = await waitForBackendReady(apiPortActual, backendReadyTimeoutMs);
  if (!readyHttp) {
    const seconds = Math.max(1, Math.round(backendReadyTimeoutMs / 1000));
    throw new Error(`Backend did not become ready at ${apiBaseUrl}/api/status within ${seconds}s`);
  }

  // Tempdoc 730 Increment-4 review: stamp worker.log's identity now that HTTP-readiness confirms
  // WorkerSpawner's own startup (and any rotation-on-spawn) has settled — see preserveWorkerLog /
  // captureWorkerLogStamp above for why this feeds the stop-time ownership guard.
  const workerLogStamp = captureWorkerLogStamp(dataDir);

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
    // Tempdoc 730 Increment-4 review: identity stamp of THIS run's worker.log at readiness, used
    // by preserveWorkerLog's stop-time ownership guard (null if the file didn't exist yet).
    workerLogStamp,
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
      workerConfigSnapshotPath: toPosix(path.join(dataDir, 'runtime', 'worker-config-snapshot.json')),
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

  backend.on('exit', (code) => {
    if (reaping) return; // deliberate reap owns teardown + exit (avoids racing stopRun cleanup)
    // Tempdoc 819 §D: an external `stop` (a DIFFERENT OS process — the in-process `reaping` flag
    // above can't see it) may have just POSTed /api/lifecycle/shutdown and be waiting on this
    // very exit. It drops a marker file right before the POST so this handler can tell "I asked
    // for this" apart from "it crashed on its own" and skip writing a report that would race
    // stopRun's own (authoritative) one for the same run. stopRun deletes the marker once it is
    // done reacting to the exit, so a later, unrelated crash in this same run is never masked.
    if (fs.existsSync(path.join(path.dirname(runPath), 'graceful-shutdown.json'))) {
      onExit();
      if (code != null && code !== 0) process.exit(code);
      process.exit(0);
      return;
    }
    // Tempdoc 730 B2: capture the exit code + preserve worker.log (B1) even though no
    // stopRun() ran for this exit. Best-effort/fire-and-forget: a signal-driven exit can't
    // await, so the write races the process.exit() below but is fast (fs-local, ms-scale).
    writeSelfExitStopReport({
      runId,
      runPath,
      run: runJson,
      backendExitCode: code,
      interactive: shuttingDown,
    }).catch(() => { }).finally(() => {
      onExit();
      if (code != null && code !== 0) process.exit(code);
      process.exit(0);
    });
  });
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
      const p = spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true });
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

  // Tempdoc 730 B1: snapshot this run's worker.log into its own run dir before the next
  // start's WorkerSpawner rotation can carry it away.
  const workerLog = await preserveWorkerLog(run, runPath);

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
    workerLog,
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
      preserveWorkerLog,
      buildStopReport,
      buildHeadJavaOpts,
      writeSelfExitStopReport,
      captureWorkerLogStamp,
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
