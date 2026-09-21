#!/usr/bin/env node
/**
 * Tempdoc 656 O2 — the onramp doctor.
 *
 * One command that answers, at any moment: WHAT TIER is this environment at, WHAT'S MISSING to reach
 * the next, and the SINGLE NEXT REMEDY. It composes existing sources — it is a projection, not a new
 * authority (tempdoc 501): the model registry (`model-registry.v2.json`) for what should exist, the
 * `AiPreflightService` endpoint / on-disk presence for what does, and (when a stack is running)
 * `/api/status` + `/api/runtime/manifest` for live readiness + the specific "why AI isn't ready".
 *
 * Tiers (each a COMPLETE first-success, not a degraded fraction):
 *   Tier 0  keyword search      — works with ZERO models (live-proven); the floor.
 *   Tier 1  semantic / hybrid   — needs the ONNX embedding model.
 *   Tier 2  cited AI answers    — needs the chat GGUF + the GPU (cuda12) llama-server runtime.
 *
 * Informational: exits 0 by default (a doctor, not a gate). Exits 2 only if a RUNNING stack's worker
 * is not READY (the environment is actually broken). `--json` prints the structured report.
 */
'use strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const asJson = process.argv.includes('--json');

/** Resolve the MAIN checkout root (worktrees carry a `.git` FILE pointing at the real gitdir). */
function resolveMainRepoRoot() {
  try {
    const dotGit = path.join(repoRoot, '.git');
    const st = fs.statSync(dotGit);
    if (st.isFile()) {
      const m = fs.readFileSync(dotGit, 'utf8').trim().match(/^gitdir:\s*(.+)$/);
      if (m) return path.resolve(repoRoot, m[1], '..', '..', '..');
    }
  } catch { /* not a worktree */ }
  return repoRoot;
}
const mainRepoRoot = resolveMainRepoRoot();

/** The models dir the runtime resolves (env override, else main checkout, else this checkout). */
function resolveModelsDir() {
  const env = process.env.JUSTSEARCH_MODELS_DIR;
  if (env && fs.existsSync(env)) return env;
  const main = path.join(mainRepoRoot, 'models');
  if (fs.existsSync(main)) return main;
  return path.join(repoRoot, 'models');
}

function readRegistry() {
  const p = path.join(repoRoot, 'modules', 'configuration', 'src', 'main', 'resources', 'ai', 'model-registry.v2.json');
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

/** cuda12 GPU llama-server runtime resolvable (worktree-own first, else shared main checkout)? */
function cuda12Resolvable() {
  const exe = process.platform === 'win32' ? 'llama-server.exe' : 'llama-server';
  const tail = ['modules', 'ui', 'native-bin', 'llama-server', 'variants', 'cuda12', exe];
  return [path.join(repoRoot, ...tail), path.join(mainRepoRoot, ...tail)].some((p) => fs.existsSync(p));
}

/**
 * Cheap NVIDIA GPU presence probe: `nvidia-smi --query-gpu=...`. Returns `true` on a successful,
 * non-empty query, `null` ("unknown") on ANY failure (missing binary, no permissions, timeout, non-NVIDIA
 * GPU) — nvidia-smi's absence does not prove a GPU is absent, so failure must not be reported as `false`.
 * This is a presence check, not the full VRAM-sizing probe `gpu-bridge` does at activation time; it only
 * answers "was an NVIDIA GPU positively confirmed", to stop the doctor from claiming Tier 2 on artifacts
 * alone (CONTRIBUTING.md: Tier 2 needs an NVIDIA GPU, 8 GB+ VRAM).
 */
function gpuPresenceCheck() {
  try {
    const out = execFileSync('nvidia-smi', ['--query-gpu=memory.total', '--format=csv,noheader'], {
      timeout: 3000,
      stdio: ['ignore', 'pipe', 'ignore'],
    }).toString().trim();
    return out.length > 0 ? true : null;
  } catch {
    return null; // absent / errored / timed out — unknown, NOT "no GPU"
  }
}

/**
 * Is a package's PRIMARY artifact present on disk? Tier detection keys on the primary artifact (a
 * variant file), NOT full package completeness — mirrors the canActivateDefault fix: e.g. the chat
 * package's `mmproj` supporting file is vision-only and must not gate "can this env answer" (text
 * cited answers need only the GGUF variant). Zero-variant packages fall back to supporting-file
 * presence. A projection of the registry (mirrors AiPreflightService's variant check).
 */
function packagePresent(pkg, modelsDir, aiHome) {
  const base = pkg.installRoot ? path.join(aiHome, pkg.installRoot) : modelsDir;
  const dir = path.join(base, pkg.targetDir || '');
  const variants = pkg.variants || [];
  if (variants.length > 0) return variants.some((v) => fs.existsSync(path.join(dir, v.filename)));
  return (pkg.supportingFiles || []).length > 0
    && (pkg.supportingFiles || []).every((s) => fs.existsSync(path.join(dir, s.filename)));
}

/** Offline tier detection: read the registry + check disk. */
function detectOffline() {
  const reg = readRegistry();
  const modelsDir = resolveModelsDir();
  const aiHome = mainRepoRoot; // dev: native-bin lives under the (shared) checkout, not a data dir
  const present = {};
  for (const pkg of reg.packages || []) present[pkg.id] = packagePresent(pkg, modelsDir, aiHome);
  const runtime = cuda12Resolvable();
  const gpuVerified = gpuPresenceCheck();
  return { present, runtime, gpuVerified, modelsDir };
}

/** Map presence → a tier + what each higher tier needs + the single next remedy. */
function deriveTier({ present, runtime, gpuVerified }) {
  const embedding = !!present.embedding;
  const chat = !!present.chat;
  const missing = [];
  // Tiers are CUMULATIVE (Tier 2 ⊃ Tier 1 ⊃ Tier 0): a higher tier is only "reached" once every
  // lower tier's requirement is met. Reporting Tier 2 while embedding is absent would contradict the
  // `missing` list, so gate each rung on the ones below it.
  let tier = 0; // Tier 0 (keyword search) needs no models — always reachable.
  if (embedding) tier = 1; else missing.push('embedding model (semantic/hybrid search)');
  if (embedding && chat && runtime) tier = 2;
  // missing/nextRemedy both walk the ladder in the same order: embedding → runtime → chat.
  if (!runtime) missing.push('GPU llama-server runtime (cited AI answers)');
  if (!chat) missing.push('chat GGUF model (cited AI answers)');
  let nextRemedy;
  if (!embedding) {
    nextRemedy = 'Reach Tier 1 (semantic search): install the ONNX embedding model (Install AI, or place it under the models dir).';
  } else if (!runtime) {
    nextRemedy = 'Reach Tier 2 (cited answers): provision the GPU runtime once — `./gradlew :modules:ui:stageLlamaCudaVariant` at the main checkout (then the dev-runner shares it to every worktree).';
  } else if (!chat) {
    nextRemedy = 'Reach Tier 2 (cited answers): install the chat GGUF model (Install AI).';
  } else if (tier === 2 && gpuVerified !== true) {
    nextRemedy = 'Tier 2 artifacts are present but an NVIDIA GPU was not positively verified — cited answers require an NVIDIA GPU with 8 GB+ VRAM.';
  } else {
    nextRemedy = null; // Tier 2 — nothing missing for the onramp.
  }
  return { tier, missing, nextRemedy };
}

/** Detect a running stack: active.json → runId → the run's run.json carries apiBaseUrl. */
function detectStack() {
  try {
    const stateRoot = path.join(mainRepoRoot, 'tmp', 'dev-runner');
    const active = JSON.parse(fs.readFileSync(path.join(stateRoot, 'active.json'), 'utf8'));
    if (!active.runId) return null;
    const run = JSON.parse(fs.readFileSync(path.join(stateRoot, 'runs', active.runId, 'run.json'), 'utf8'));
    const base = run.apiBaseUrl || (run.apiPortActual ? `http://127.0.0.1:${run.apiPortActual}` : null);
    return base ? { live: true, apiBaseUrl: base } : null;
  } catch { return null; }
}

async function getJson(base, route) {
  try {
    const res = await fetch(base + route, { signal: AbortSignal.timeout(3000) });
    if (!res.ok) return null;
    return await res.json();
  } catch { return null; }
}

async function main() {
  const offline = detectOffline();
  const derived = deriveTier(offline);
  const tierNames = ['keyword search', 'semantic/hybrid search', 'cited AI answers'];
  // Tier 2 is keyed on artifact presence only (packagePresent/cuda12Resolvable check disk, not
  // hardware) — label it honestly when an NVIDIA GPU wasn't positively confirmed, so "Tier 2" doesn't
  // read as a guarantee that activation will actually succeed (CONTRIBUTING.md: Tier 2 needs an
  // NVIDIA GPU, 8 GB+ VRAM).
  const tierName = derived.tier === 2 && offline.gpuVerified !== true
    ? 'cited AI answers (artifacts present; GPU not verified — requires an NVIDIA GPU, 8 GB+ VRAM)'
    : tierNames[derived.tier];
  const report = {
    tier: derived.tier,
    tierName,
    modelsPresent: offline.present,
    gpuRuntimeResolvable: offline.runtime,
    gpuVerified: offline.gpuVerified,
    missingForHigherTiers: derived.missing,
    nextRemedy: derived.nextRemedy,
    live: null,
  };

  const stack = detectStack();
  let workerBroken = false;
  if (stack) {
    const [status, manifest] = await Promise.all([
      getJson(stack.apiBaseUrl, '/api/status'),
      getJson(stack.apiBaseUrl, '/api/runtime/manifest'),
    ]);
    if (status) {
      const worker = status.components?.worker;
      // LifecycleSnapshotV2's six real states (LifecycleState proto; UNSPECIFIED/UNRECOGNIZED are
      // rejected sentinels, never actually sent — see LifecycleSnapshotV2.Lifecycle). STARTING
      // is a normal transient state during stack boot, not an environment defect — exclude it from
      // "broken" so a doctor run during startup doesn't exit 2 for a stack that just hasn't finished
      // coming up yet. Only DEGRADED/ERROR/STOPPING/STOPPED (i.e. anything but READY or STARTING)
      // count as "actually broken".
      const workerStarting = worker?.state === 'LIFECYCLE_STATE_STARTING';
      workerBroken = worker && worker.state && worker.state !== 'LIFECYCLE_STATE_READY' && !workerStarting;
      report.live = {
        apiBaseUrl: stack.apiBaseUrl,
        worker: worker?.state,
        workerStarting,
        indexedDocuments: status.worker?.core?.indexedDocuments,
        aiReady: status.aiReady,
        embeddingReady: status.embeddingReady,
        aiReason: manifest?.ai?.pendingReason ?? status.components?.inference?.reason_code ?? null,
      };
    }
  }

  if (asJson) {
    console.log(JSON.stringify(report, null, 2));
  } else {
    console.log(`\nJustSearch onramp doctor\n${'='.repeat(24)}`);
    console.log(`Tier reached: ${report.tier} — ${report.tierName}` + (report.tier === 0 ? '  (works with zero models downloaded)' : ''));
    console.log(`Models on disk: ${Object.entries(offline.present).filter(([, v]) => v).map(([k]) => k).join(', ') || '(none)'}`);
    console.log(`GPU runtime resolvable: ${offline.runtime ? 'yes' : 'no'}` +
      (offline.runtime ? ` (GPU verified: ${offline.gpuVerified === true ? 'yes' : 'not verified'})` : ''));
    if (report.live) {
      console.log(`\nLive stack (${report.live.apiBaseUrl}): worker=${report.live.worker}, indexed=${report.live.indexedDocuments}, aiReady=${report.live.aiReady}` +
        (report.live.aiReason ? `, ai=${report.live.aiReason}` : ''));
      if (report.live.workerStarting) console.log(`(stack is starting — worker not yet READY; this is informational, not an error)`);
    } else {
      console.log(`\nNo running stack detected (offline check only). Start one: node scripts/dev/dev-runner.cjs start`);
    }
    if (report.nextRemedy) console.log(`\nNext step → ${report.nextRemedy}`);
    else console.log(`\nAll onramp tiers reachable. Try the demo: ingest examples/onramp-corpus and search.`);
    console.log('');
  }

  process.exitCode = workerBroken ? 2 : 0;
}

main();
