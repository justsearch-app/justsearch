#!/usr/bin/env node
/**
 * Lane F stage E paired driver. Root/operator owns the shared stack lease.
 * No build, install, Gradle, or automatic collector changes. Node built-ins only.
 * E7 signed dead-Engine upgrade is operator-driven and externally blocked on signing.
 * Missing instruments/clauses are UNMEASURABLE, never a successful measurement.
 * Raw evidence is retained in tmp/lane-f-e; index.json points to every invocation.
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { gcLogOption, hangPolicy, splitGap } from './e456-instruments.mjs';
import { startOwned, activateChat, verifySharedModels as checkSharedModels } from './e-start-ready.mjs';
import { terminalComplete, isAdmitted, agentMetrics } from './e-agent-metrics.cjs';
import { measurementIdentity } from './e-pair-identity.mjs';
import { LiveCollector, crashExperiment, hangExperiment, childPathExperiment, projectChildPolicies } from './e456-live.mjs';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
export const ARMS = Object.freeze({
  branch: 'F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify',
  main: 'F:/justsearch-public/.claude/worktrees/lane-f-e-main',
});
const GROUPS = ['e1-quality', 'e2-e3-load', 'e4-memory-soak', 'e5-crash', 'e6-hang'];
const EVIDENCE = 'docs/design/lane-f-engine-jvm/evidence/E';
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const write = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
};
const finite = x => typeof x === 'number' && Number.isFinite(x) && x >= 0;
export const MAIN_REVISION = 'ac1c93bf32c2bba3e4a22462295acbc618f850bc';

/** E0.2: compare content, never ancestry, and retain the exact pin-check evidence. */
export function checkFixturePins(root = ROOT, revision = MAIN_REVISION, git = args => {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) throw new Error(`Fixture pin check failed: ${result.stderr || result.error}`);
  return result.stdout.trim();
}, stampFile = path.join(ARMS.main, 'modules/indexer-worker/build/install/indexer-worker/build-stamp.txt')) {
  const spec = read(path.join(root, 'scripts/jseval/lane-f-workflow-fixture.v1.json'));
  const baseline = read(path.join(root, 'docs/design/lane-f-engine-jvm/evidence/baseline/fixture-pr0b/pins.json'));
  if (!baseline?.recordedRevision || !baseline.pinnedSurfaces?.length) throw new Error('Missing PR 0b pinned surfaces');
  const directory = path.join(root, baseline.directory);
  const captures = [1, 2, 3].map(n => {
    const file = path.join(directory, `capture-${n}.json`);
    return { file, sha256: hash(fs.readFileSync(file)), provenance: read(file).provenance };
  });
  const first = captures[0].provenance;
  const stamp = first?.['worker.buildStamp'];
  const pins = first?.pins;
  if (!stamp || !pins || !Object.keys(pins).length) throw new Error('PR 0b lacks recorded build or pins');
  const sameBuildAndPins = captures.every(c => c.provenance?.['worker.buildStamp'] === stamp
    && JSON.stringify(c.provenance?.pins) === JSON.stringify(pins)
    && c.provenance?.chatProfile === first.chatProfile
    && Object.keys(c.provenance?.sampling ?? {}).length === Object.keys(spec.sampling).length
    && Object.entries(spec.sampling).every(([key, value]) => c.provenance?.sampling?.[key] === value));
  const args = ['diff', '--name-only', baseline.recordedRevision, revision, '--', ...baseline.pinnedSurfaces];
  const changedFiles = git(args).split(/\r?\n/).filter(Boolean);
  const currentBuildStamp = fs.existsSync(stampFile) ? fs.readFileSync(stampFile, 'utf8').trim() : null;
  const decision = sameBuildAndPins && changedFiles.length === 0 && currentBuildStamp === stamp ? 'reuse' : 'recapture';
  return { rule: 'E0.2', decision, directory, recordedRevision: baseline.recordedRevision,
    mainRevision: revision, revisionSource: baseline.revisionSource, gitDiffArgs: args, changedFiles,
    reasons: [...(!sameBuildAndPins ? ['capture-build-or-pins-mismatch'] : []),
      ...(changedFiles.length ? ['pinned-surfaces-changed'] : []),
      ...(currentBuildStamp !== stamp ? ['installed-build-mismatch-or-missing'] : [])],
    recordedBuildStamp: stamp, currentBuildStamp, stampFile, sameBuildAndPins, pins,
    recordedChatProfile: first.chatProfile, captures: captures.map(({ file, sha256 }) => ({ file, sha256 })) };
}
export const verifySharedModels = config => checkSharedModels(config, path.resolve(ARMS.main, '../../../models'));
export function parseArgs(argv) {
  const [command, ...rest] = argv;
  if (![...GROUPS, 'e0-values', 'e4-hang-values', 'table', 'reproject'].includes(command)) throw new Error('Unknown subcommand');
  const result = { command, dryRun: false };
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    if (flag === '--dry-run') {
      if (result.dryRun) throw new Error('Duplicate --dry-run');
      result.dryRun = true;
    } else {
      const key = { '--arm': 'arm', '--window': 'window', '--repo-root': 'repoRoot', '--arm-tree': 'armTree',
        '--fault': 'fault', '--debug-port': 'debugPort', '--workload': 'workload', '--record': 'record' }[flag];
      const value = rest[++i];
      if (!key || !value || value.startsWith('--') || result[key]) throw new Error(`Invalid option ${flag}`);
      result[key] = value;
    }
  }
  if (GROUPS.includes(command) && !Object.hasOwn(ARMS, result.arm)) throw new Error('--arm branch|main required');
  if (result.arm && !Object.hasOwn(ARMS, result.arm)) throw new Error('Invalid arm');
  if (command === 'e0-values' && result.arm && result.arm !== 'main') throw new Error('E0 uses MAIN only');
  if (command === 'table' && result.arm) throw new Error('table reads both arms; omit --arm');
  if (command === 'e4-hang-values' && result.arm) throw new Error('Hang values use both arms; omit --arm');
  if (result.armTree && !GROUPS.includes(command)) throw new Error('--arm-tree requires a run group');
  if (result.fault && (command !== 'e6-hang' || !['soft', 'hard'].includes(result.fault))) throw new Error('--fault soft|hard is E6 only');
  if (result.debugPort && (command !== 'e6-hang' || !/^\d+$/.test(result.debugPort)
    || Number(result.debugPort) < 1024 || Number(result.debugPort) > 65535)) throw new Error('--debug-port 1024..65535 is E6 only');
  if (command === 'e4-memory-soak' ? !['1', '2', '3'].includes(result.window) : result.window !== undefined) {
    throw new Error('--window 1|2|3 required only for E4');
  }
  if (command === 'e2-e3-load' ? !['agent-idle', 'scripted-agent'].includes(result.workload) : result.workload !== undefined) {
    throw new Error('--workload agent-idle|scripted-agent required only for E2/E3');
  }
  if (command === 'reproject' ? !result.record || result.arm : result.record !== undefined) throw new Error('--record <record.json> required only for reproject; omit --arm');
  return result;
}

/** A reviewable process plan; placeholders are resolved only after the owned start receipt. */
export function buildPlan(options, values, root = ROOT, fixtureDecision) {
  const arm = options.arm ?? 'main';
  const tree = options.armTree ? path.resolve(options.armTree) : ARMS[arm];
  const group = options.command;
  const raw = path.join(root, 'tmp/lane-f-e', group, arm, '${invocation}');
  const tools = path.join(ROOT, 'scripts/jseval/lane-f');
  const runner = path.join(tree, 'scripts/dev/dev-runner.cjs');
  const pythonCwd = path.join(root, 'scripts/jseval');
  const env = {
    PYTHONPATH: pythonCwd, PYTHONUTF8: '1',
    // Trust only the selected owner-assigned arm in child processes; no global Git config edits.
    GIT_CONFIG_COUNT: '1', GIT_CONFIG_KEY_0: 'safe.directory', GIT_CONFIG_VALUE_0: tree,
    JUSTSEARCH_HEAD_HEAP: values.heap.packaged.replace('-Xmx', ''),
    UI_OPTS: '-XX:-UseSerialGC -XX:-UseZGC -XX:+UseG1GC',
    JAVA_OPTS: '-Djustsearch.eval.mode=true',
    JUSTSEARCH_CHAT_PROFILE: 'standard',
  };
  if (['e4-memory-soak', 'e5-crash', 'e6-hang'].includes(group)) {
    env.JAVA_TOOL_OPTIONS = gcLogOption(path.join(raw, 'gc')) + ' -XX:MaxDirectMemorySize=256m -XX:MetaspaceSize=128m';
    if (arm === 'main') {
      env.JUSTSEARCH_WORKER_HEAP = values.heap.packaged.replace('-Xmx', '');
      env.JUSTSEARCH_JVM_OPTS = '-XX:+UseG1GC';
    }
  }
  const worstPauseMs = values.hangParameters?.worstPauseMs;
  const debugPort = options.debugPort ?? '33225';
  if (group === 'e6-hang') {
    // Dry runs remain reviewable before E4 freezes the values; execution validates them first.
    env.JUSTSEARCH_SUPERVISOR_HARNESS = '1';
    env.JUSTSEARCH_SUPERVISOR_HANG_POLL_INTERVAL_MS = String(values.hangParameters?.intervalMs ?? '${hangIntervalMs}');
    env.JUSTSEARCH_SUPERVISOR_HANG_THRESHOLD = String(values.hangParameters?.missCount ?? '${hangMissCount}');
    const debug = `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${debugPort}`;
    if (arm === 'main') env.JUSTSEARCH_JVM_OPTS += ` ${debug}`;
    else env.JAVA_OPTS += ` ${debug}`;
  }
  const cmd = (label, executable, args, cwd = tree, extra = {}) => ({ label, executable, args, cwd, env,
    ...(group === 'e2-e3-load' ? { budgetSeconds: 60 } : {}), ...extra });
  const start = label => cmd(`start-${label}`, 'node', [runner, 'start', '--json', '--skip-build',
    '--clean', 'none', '--data-dir', path.join(tree, 'tmp/lane-f-e', '${invocation}', label),
    '--api-port', '33221', '--session-id', '${session}', '--lease-duration-sec', '3600',
    '--chat-profile', 'standard'], tree, { mode: 'start',
      requestsAfterReceipt: ['/api/mcp/token', '/api/health', '/api/status', '/api/debug/state', '/api/runtime/manifest', '/api/debug/effective-config'] });
  const stop = label => cmd(`stop-${label}`, 'node', [runner, 'stop', '--json', '--run', '${runId}',
    '--session-id', '${session}'], tree, { mode: 'stop' });
  const evalRun = (label, load = false, mode = 'hybrid') => cmd(label, 'python', ['-m', 'jseval', 'run',
    '--dataset', 'scifact', '--modes', 'lexical,hybrid', '--embedding', '--splade', '--ce',
    '--pipeline', '--base-url', 'http://127.0.0.1:33221',
    '--corpus-dir', path.join(root, 'tmp/lane-f-e/corpus/scifact'),
    '--output-dir', path.join(raw, label, 'eval-results'),
    '--timeline', path.join(raw, label, 'timeline.tsv'),
    ...(load ? ['--search-load', 'continuous', '--search-load-search-mode', mode, '--max-queries', '1'] : [])], pythonCwd);
  const samplers = label => [
    cmd(`rss-${label}`, 'powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
      path.join(tools, 'head-rss-sampler.ps1'), '-Out', path.join(raw, label, 'head-rss.csv'),
      '-Stop', path.join(raw, label, 'rss.stop'), '-IncludeSplitWorker',
      ...(group === 'e4-memory-soak' ? ['-Scope', path.join(raw, label, 'process-scope.json'), '-Arm', arm] : [])], tree, { mode: 'background' }),
    cmd(`status-${label}`, 'bash', [path.join(tools, 'status-sampler.sh'), path.join(raw, label),
      '33221', path.join(raw, label, 'status.stop')], tree, { mode: 'background' }),
  ];
  const admission = label => cmd(`admission-${label}`, 'node', [path.join(tools, 'admission-loop.mjs'),
    '--capture-workload', path.join(raw, label), '--base-url', 'http://127.0.0.1:33221',
    '--stop-file', path.join(raw, label, 'admission.stop')], tree, { mode: 'background' });
  const probe = label => cmd(`encoder-${label}`, 'bash', [path.join(tools, 'encoder-latency-probe.sh'),
    path.join(raw, label, 'encoder'), '3', '33221'], root);
  const analyze = label => cmd(`analyze-${label}`, 'node', [path.join(tools, 'analyze-head-run.cjs'), path.join(raw, label), '--paired-split'], root, { identityRole: 'scoring' });
  const endSamples = label => ({ label: `end-samples-${label}`, mode: 'end-samples', directory: path.join(raw, label) });
  const instruments = label => ({ label: `instruments-${label}`, mode: 'instruments-start', directory: path.join(raw, label),
    arm, target: arm === 'main' ? 'Head + Worker under Head WorkerSpawner' : 'Engine under dev-runner supervisor',
    gcEveryMinutes: 5, warmupMinutes: 5, reconfigureEveryMinutes: 15,
    settingsRoute: '/api/settings/v2', liveSetting: 'ui.excludePatterns', consumer: '/api/indexing/excludes/apply?dryRun=true' });
  const endInstruments = label => ({ label: `end-instruments-${label}`, mode: 'instruments-stop' });
  const preamble = [cmd('revision', 'git', ['rev-parse', 'HEAD']),
    cmd('dirty', 'git', ['status', '--porcelain']),
    cmd('runner-status', 'node', [runner, 'status', '--active', '--json']),
    cmd('machine', 'node', ['-e',
      'const os=require("node:os");console.log(JSON.stringify({platform:os.platform(),release:os.release(),ramBytes:os.totalmem(),cpu:os.cpus().map(c=>c.model)}))']),
    cmd('gpu', 'nvidia-smi', ['--query-gpu=name,uuid,memory.total,driver_version', '--format=csv'])];
  if (group === 'e0-values') return [{ mode: group, label: group }];
  if (group === 'e4-hang-values') return [{ mode: group, label: group,
    rule: 'Freeze interval >= 10 seconds, interval * misses >= 3 * worst observed E4 safepoint pause across both arms' }];
  if (group === 'table') return [cmd('fixture-gate', 'bash', [path.join(tools, 'fixture-gate.sh'),
    '${mainFixture}', '${branchFixture}', '${fixtureReport}'], root), { mode: group, label: group }];
  let commands;
  if (group === 'e1-quality') {
    fixtureDecision ??= checkFixturePins(root);
    commands = [start('quality'), evalRun('quality'), stop('quality'),
      cmd('relevance', 'python', ['-m', 'jseval', 'relevance-gate', '--dataset', 'beir/scifact',
        '--data-dir', path.join(raw, 'quality'), '--report-out', path.join(raw, 'relevance-gate.json')], pythonCwd, { identityRole: 'scoring' })];
    const fixtureProfile = fixtureDecision.decision === 'reuse' ? fixtureDecision.recordedChatProfile : 'standard';
    const fixtureEnv = { ...env, JUSTSEARCH_CHAT_PROFILE: fixtureProfile,
      JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH: 'true', JUSTSEARCH_LLM_SLOTS: '1',
      JUSTSEARCH_RERANK_DEADLINE_MS: '60000', JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS: '60000',
      JUSTSEARCH_RERANK_TOP_K: '40', JUSTSEARCH_RERANK_GPU_MEM_MB: '4096',
      JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX: '5000',
      JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER: '50',
      JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED: 'false',
      JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE: 'false',
      JUSTSEARCH_FIXTURE_CORPUS_ROOT: ARMS.main,
    };
    if (arm === 'main' && fixtureDecision.decision === 'reuse') {
      commands.push({ label: 'fixture-reuse', mode: 'fixture-reuse', ...fixtureDecision });
    } else for (let n = 1; n <= values.noisePairGate.capturesPerSide; n++) {
      const fixtureStart = start(`fixture-${n}`);
      fixtureStart.args[fixtureStart.args.indexOf('--chat-profile') + 1] = fixtureProfile;
      commands.push({ ...fixtureStart, env: fixtureEnv },
        cmd(`fixture-${n}`, 'bash', [path.join(tools, 'fixture-cycle.sh'), path.join(raw, 'fixture', `capture-${n}.json`),
          fixtureProfile, '33221'], root, { env: fixtureEnv }), stop(`fixture-${n}`));
    }
  } else if (group === 'e2-e3-load') {
    const label = options.workload;
    commands = [start(label), ...samplers(label).filter(c => c.label.startsWith('rss-')).map(c => ({ ...c, budgetSeconds: 3000 })),
      cmd(`bulk-${label}`, 'python', ['-m', 'jseval.bulk_load',
        '--base-url', 'http://127.0.0.1:33221',
        '--corpus-dir', path.join(root, 'tmp/lane-f-e/corpus/scifact'),
        '--output-dir', path.join(raw, label), '--workload', label,
        '--admission-script', path.join(tools, 'admission-loop.mjs'), '--block-seconds', '600'],
        path.join(ROOT, 'scripts/jseval'), { budgetSeconds: 1500,
          window: { modes: ['hybrid', 'lexical'], blockSeconds: 600, concurrency: 1,
            statusIntervalSeconds: 5, enrichmentWait: false } }),
      endSamples(label), { ...probe(label), budgetSeconds: 120 }, { ...analyze(label), budgetSeconds: 30 }, stop(label)];
  } else if (group === 'e4-memory-soak') {
    commands = [start('soak'), instruments('soak'), ...samplers('soak'), admission('soak'),
      { ...evalRun('soak', true), mode: 'soak', minutes: values.soak.windows[Number(options.window) - 1].minutes },
      endSamples('soak'), endInstruments('soak'), analyze('soak'), stop('soak')];
  } else if (group === 'e5-crash') {
    commands = [start('crash'), instruments('crash'), { label: 'durable-crash', mode: 'crash-experiment',
      killTarget: arm === 'branch' ? 'owned Engine JVM' : 'owned Worker JVM (Head survives)',
      mechanics: 'scripts/supervisor-conformance/verified-crash.mjs (shared with real-writer recovery)',
      corpusDocuments: 100, requireProcessing: true, requireNonzeroCheckpoint: arm === 'branch',
      splitDisposition: arm === 'main' ? splitGap('MAIN exposes durable jobs, no Lane F operations checkpoint') : undefined },
    endInstruments('crash'), stop('crash')];
    for (const reason of ['restart', 'quit', 'upgrade']) commands.push(start(`children-${reason}`),
      instruments(`children-${reason}`), { label: `children-${reason}`, mode: 'child-path', reason,
        mechanism: reason === 'restart' ? arm === 'branch' ? 'existing shutdown request channel' : 'core.restart-worker'
          : reason === 'quit' ? '/api/lifecycle/shutdown' : '/api/upgrade/prepare + /api/upgrade/commit-shutdown' },
      endInstruments(`children-${reason}`), stop(`children-${reason}`));
  } else {
    commands = [];
    for (const kind of options.fault ? [options.fault] : ['soft', 'hard']) commands.push(start(`hang-${kind}`),
      instruments(`hang-${kind}`), { label: `hang-${kind}`, mode: 'hang-experiment', kind, port: debugPort, worstPauseMs,
        loopback: '127.0.0.1', target: arm === 'branch' ? 'Engine' : 'Worker',
        policy: values.hangParameters,
        splitDisposition: arm === 'main' ? splitGap('MAIN Worker gRPC supervision exists; MAIN Head HTTP hangs have no autonomous dev-arm recovery') : undefined },
      endInstruments(`hang-${kind}`), stop(`hang-${kind}`));
  }
  return [...preamble, ...commands].flatMap(c => c.mode === 'start' && group !== 'e1-quality' ? [c,
    { label: `activate-${c.label}`, mode: 'ai-activate', args: [], baseUrl: 'http://127.0.0.1:33221',
      method: 'POST', endpoint: '/api/ai/runtime/activate', body: { variantId: 'cuda12', chatProfile: 'standard' },
      profile: 'standard', readyEndpoint: '/api/ai/runtime/status', timeoutSeconds: 300, pollIntervalMs: 2000 }] : [c]);
}

const CLAUSES = {
  E1: ['baseline-quality', 'SearchTrace-shape', 'workflow-evidence-citations-cancellation', 'allowed-differences'],
  E2: ['indexing-window-valid', 'foreground-p95', 'agent-api-p95', 'idle-rejections', 'scripted-rejections', 'no-timeout-or-5xx'],
  E3: ['indexing-window-valid', 'stage-completion-rates-under-foreground-load'],
  E4: ['component-commit-budget', 'machine-wide-commit-vs-main', 'working-set', 'live-after-GC-trend',
    'zero-crashes', 'owner-duration', 'index-agent-reconfigure-workload'],
  E5: ['actual-death-durable-operation', 'crash-to-api', 'crash-to-index', 'checkpoint-resume',
    'visible-restarting', 'no-orphaned-child', 'restart-quit-upgrade-child-policy'],
  E6: ['runnable-watcher-api-pool-wedge', 'whole-JVM-wedge', 'graceful-deadline', 'forced-deadline', 'E4-derived-hang-settings'],
  E7: ['signed-dead-Engine-upgrade'],
};
/** Missing/nonfinite facts are unmeasurable; known violations always dominate missing facts. */
export function verdict(checks) {
  return checks.includes(false) ? 'fail' : checks.length && checks.every(x => x === true) ? 'pass' : 'unmeasurable';
}
export function qualityGateVerdict(report) {
  if (report?.exit_code !== 0) return false;
  if (!finite(report.current) || !finite(report.baseline) || !finite(report.floor)) return undefined;
  return report.checks?.some(c => c.name === 'ndcg10-no-regression' && c.status === 'ok') ? true : undefined;
}
export function windowValidity(load) {
  if (!load || load.durationSeconds !== 1200 || load.blockSeconds !== 600
    || JSON.stringify(load.modes) !== JSON.stringify(['hybrid', 'lexical']) || load.concurrency !== 1) return undefined;
  const samples = load.samples ?? [];
  if (samples.some(s => s.active === false)) return false;
  if (samples.length < 2 || samples.some(s => s.error || s.active !== true
    || !finite(s.chunkEmbeddingCompletedCount) || !finite(s.indexedDocuments))) return undefined;
  if (samples[0].offsetSeconds !== 0 || samples.at(-1).offsetSeconds !== 1200) return undefined;
  if (!finite(samples[0].observedAtMs) || !finite(samples.at(-1).observedAtMs)
    || Math.abs(samples[0].observedAtMs - load.startedAtMs) > 6000
    || Math.abs(samples.at(-1).observedAtMs - load.endedAtMs) > 6000) return undefined;
  if (!finite(load.startedAtMs) || !finite(load.endedAtMs)
    || Math.abs(load.endedAtMs - load.startedAtMs - 1200000) > 6000) return undefined;
  for (let i = 1; i < samples.length; i++) {
    if (samples[i].offsetSeconds <= samples[i - 1].offsetSeconds
      || samples[i].offsetSeconds - samples[i - 1].offsetSeconds > 12
      || samples[i].chunkEmbeddingCompletedCount < samples[i - 1].chunkEmbeddingCompletedCount
      || samples[i].indexedDocuments < samples[i - 1].indexedDocuments) return undefined;
  }
  return true;
}
export const INDEX_STAGES = ['primary', 'embed', 'splade', 'chunk_embed', 'ner'];
const E3_RATE_CLAUSE = 'stage-completion-rates-under-foreground-load';
/** Raw wire counters are the authority; completed fields take precedence over projections. */
export function stageCompletionRates(load) {
  const samples = load.samples ?? [];
  const docPopulations = samples.flatMap(s => {
    const w = s.raw?.worker;
    return [w?.core?.indexedDocuments, w?.enrichment?.embeddingDocCount, w?.enrichment?.spladeDocCount,
      ...(w?.enrichment?.completeness ?? []).filter(c => ['embed', 'splade', 'ner'].includes(c.stageId)).map(c => c.expected)];
  }).filter(finite);
  const expectedDocuments = finite(load.expectedDocuments) ? load.expectedDocuments
    : docPopulations.length ? Math.max(...docPopulations) : undefined;
  const expectedSource = finite(load.expectedDocuments) ? 'load.expectedDocuments'
    : 'maximum observed raw primary/document-enrichment population (legacy capture)';
  const fields = {
    embed: ['embeddingCompletedCount', 'embeddingPendingCount', 'embeddingDocCount'],
    splade: ['spladeCompletedCount', 'spladePendingCount', 'spladeDocCount'],
    chunk_embed: ['chunkEmbeddingCompletedCount', 'chunkEmbeddingPendingCount', 'chunkDocCount'],
    ner: ['completedNerCount', 'pendingNerCount', undefined],
  };
  return Object.fromEntries(INDEX_STAGES.map(stage => {
    const sources = new Set();
    const rows = samples.map(s => {
      const w = s.raw?.worker, e = w?.enrichment;
      let completed, pending, expected;
      if (stage === 'primary') {
        completed = w?.core?.indexedDocuments; expected = expectedDocuments;
        sources.add('raw.worker.core.indexedDocuments');
      } else {
        const [doneKey, pendingKey, expectedKey] = fields[stage];
        const owner = stage === 'chunk_embed' ? e?.chunk : e;
        pending = owner?.[pendingKey];
        expected = expectedKey ? owner?.[expectedKey] : e?.completeness?.find(c => c.stageId === stage)?.expected;
        if (!finite(expected) && stage !== 'chunk_embed') expected = expectedDocuments;
        if (owner && Object.hasOwn(owner, doneKey)) {
          completed = owner[doneKey]; sources.add(`raw.worker.enrichment.${stage === 'chunk_embed' ? 'chunk.' : ''}${doneKey}`);
        } else {
          completed = finite(expected) && finite(pending) && expected >= pending ? expected - pending : undefined;
          sources.add(`expected - ${pendingKey} (completed field absent)`);
        }
      }
      const active = stage === 'primary' ? finite(completed) && finite(expected) ? completed < expected : undefined
        : finite(pending) ? pending > 0 : undefined;
      return { time: s.observedAtMs, completed, active };
    });
    const result = { completedSources: [...sources], expectedDocuments, expectedSource };
    if (!rows.length || rows.some(r => !finite(r.time) || !finite(r.completed) || r.active === undefined))
      return [stage, { ...result, reason: 'missing-stage-counter-or-time' }];
    if (rows.some((r, i) => i && r.time <= rows[i - 1].time))
      return [stage, { ...result, reason: 'time-regression' }];
    result.counterRegressions = rows.flatMap((r, i) => i && r.completed < rows[i - 1].completed
      ? [{ observedAtMs: r.time, from: rows[i - 1].completed, to: r.completed }] : []);
    const start = rows.findIndex(r => r.active);
    if (start < 0) return [stage, { ...result, reason: 'no-active-interval' }];
    const drained = rows.findIndex((r, i) => i > start && !r.active);
    const last = drained < 0 ? rows.length - 1 : drained;
    const seconds = (rows[last].time - rows[start].time) / 1000;
    const delta = rows[last].completed - rows[start].completed;
    return [stage, { ...result, activeSeconds: seconds, completedDelta: delta,
      startObservedAtMs: rows[start].time, endObservedAtMs: rows[last].time,
      drainedInWindow: drained >= 0, rate: seconds >= 60 && delta >= 0 ? delta / seconds : undefined,
      reason: seconds < 60 ? 'active-interval-under-60-seconds' : delta < 0 ? 'negative-completion-delta'
        : delta === 0 ? 'no-completions-in-window' : undefined }];
  }));
}
export function compareStageRates(stages, splitStages) {
  const comparisons = {}, checks = [];
  for (const stage of INDEX_STAGES) {
    const main = splitStages?.[stage]?.rate;
    if (!finite(main) || main <= 0) {
      comparisons[stage] = { status: 'not-compared', reason: finite(main) ? 'split-did-not-progress' : 'split-rate-unmeasurable' };
      continue;
    }
    const branch = stages?.[stage]?.rate, minimum = main * .9;
    const check = finite(branch) ? branch >= minimum && branch > 0 : undefined;
    comparisons[stage] = { status: check === true ? 'pass' : check === false ? 'fail' : 'unmeasurable', splitRate: main, branchRate: branch, minimum };
    checks.push(check);
  }
  return { comparisons, check: checks.includes(false) ? false : checks.length && checks.every(c => c === true) ? true : undefined };
}
export function projectLoad(record, load, calls, values) {
  const valid = windowValidity(load);
  record.clauses['indexing-window-valid'] = valid;
  if (valid !== true) record.gaps['indexing-window-valid'] = valid === false
    ? 'INVALID: indexing completed before the fixed load window ended'
    : 'Missing, stale, discontinuous, or incomplete fixed-window samples';
  record.metrics.searchP95 = load.searchP95 ?? {};
  record.metrics.loadWindow = { startedAtMs: load.startedAtMs, endedAtMs: load.endedAtMs,
    durationSeconds: load.durationSeconds, queryPoolHash: load.queryPoolHash };
  const samples = load.samples ?? [], first = samples[0], last = samples.at(-1);
  record.metrics.stageRates = valid === true ? stageCompletionRates(load) : {};
  if (valid === true) {
    record.metrics.chunkActiveSeconds = record.metrics.stageRates.chunk_embed.activeSeconds;
    record.metrics.chunkWorkDrainedInWindow = record.metrics.stageRates.chunk_embed.drainedInWindow;
    record.metrics.chunksPerSec = record.metrics.stageRates.chunk_embed.rate;
    record.metrics.docsPerSec = (last.indexedDocuments - first.indexedDocuments) / 1200;
    record.metrics.chunksByWorkload = { [record.workload]: record.metrics.chunksPerSec };
  }
  const wire = [...(load.requests ?? []), ...calls];
  record.metrics.windowBoundaryRequests = wire.filter(c => c.windowBoundary).length;
  const rejectionCodes = new Set(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT']);
  const legalRejection = c => rejectionCodes.has(c.code) && c.retrySafe === true && /^[1-9]\d*$/.test(c.retryAfter);
  record.metrics.wireRejections = wire.filter(c => c.status === 429).length;
  record.metrics.wireOffered = wire.length;
  record.clauses['no-timeout-or-5xx'] = wire.length ? wire.every(c => terminalComplete(c)
    && (c.windowBoundary && ['TRANSPORT_FAILURE', 'WINDOW_BOUNDARY_CANCELLED'].includes(c.error) && !(c.status >= 500)
      || !c.error && ((c.status >= 200 && c.status < 300) || c.status === 429 && legalRejection(c))))
    && !(load.samples ?? []).some(s => s.error)
    && (record.workload !== 'scripted-agent' || load.admissionExitCode === 0 && calls.length > 0) : undefined;
  record.clauses['foreground-p95'] = ['hybrid', 'lexical'].every(m => finite(load.searchP95?.[m])) ? true : undefined;
  const admitted = calls.filter(isAdmitted);
  Object.assign(record.metrics, agentMetrics(calls));
  delete record.gaps.agentTerminalErrors;
  if (Object.keys(record.metrics.agentTerminalErrors).length) record.gaps.agentTerminalErrors = JSON.stringify(record.metrics.agentTerminalErrors);
  if (record.workload === 'agent-idle') record.clauses['idle-rejections'] = wire.length
    ? record.metrics.wireRejections <= values.admissionRejectionCeiling.agentIdle : undefined;
  else {
    record.clauses['agent-api-p95'] = admitted.length ? true : undefined;
    record.clauses['scripted-rejections'] = wire.length ? wire.filter(c => c.status === 429).every(legalRejection)
      && record.metrics.wireRejections / wire.length <= values.admissionRejectionCeiling.scriptedAgentFraction : undefined;
  }
  delete record.clauses['chunks-per-second-under-foreground-load'];
  delete record.gaps['chunks-per-second-under-foreground-load'];
  const split = values.indexingProgressFraction.stagesByWorkload?.[record.workload];
  const compared = compareStageRates(record.metrics.stageRates, record.arm === 'main' ? record.metrics.stageRates : split);
  record.metrics.stageComparisons = compared.comparisons;
  record.clauses[E3_RATE_CLAUSE] = valid === false ? false : valid === true ? compared.check : undefined;
  if (record.clauses[E3_RATE_CLAUSE] !== true) record.gaps[E3_RATE_CLAUSE] = JSON.stringify(compared.comparisons);
}
export function mergeLoadRecords(workloads) {
  const list = ['agent-idle', 'scripted-agent'].map(w => workloads[w]);
  const present = list.filter(Boolean);
  const merged = structuredClone(present.at(-1));
  merged.id = present.map(r => r.id).join(', ');
  merged.workloadRecords = present.map(r => r.recordFile);
  merged.pairIdentity = hash(JSON.stringify(list.map(r => r?.pairIdentity)));
  merged.metrics = { searchP95: {}, searchP95ByWorkload: {}, chunksByWorkload: {}, queryPoolByWorkload: {}, stagesByWorkload: {} };
  for (const r of present) {
    merged.metrics.stagesByWorkload[r.workload] = r.metrics.stageRates;
    merged.metrics.queryPoolByWorkload[r.workload] = r.metrics.loadWindow?.queryPoolHash;
    merged.metrics.searchP95ByWorkload[r.workload] = r.metrics.searchP95;
    merged.metrics.chunksByWorkload[r.workload] = r.metrics.chunksPerSec;
    for (const mode of ['hybrid', 'lexical']) if (finite(r.metrics.searchP95?.[mode]))
      merged.metrics.searchP95[mode] = Math.max(merged.metrics.searchP95[mode] ?? 0, r.metrics.searchP95[mode]);
  }
  merged.metrics.agentP95 = workloads['scripted-agent']?.metrics.agentP95;
  merged.metrics.agentTerminalErrors = workloads['scripted-agent']?.metrics.agentTerminalErrors;
  merged.metrics.agentAdmitted = workloads['scripted-agent']?.metrics.agentAdmitted;
  merged.metrics.agentOffered = workloads['scripted-agent']?.metrics.agentOffered;
  merged.metrics.chunksPerSec = list.every(r => finite(r?.metrics.chunksPerSec))
    ? Math.min(...list.map(r => r.metrics.chunksPerSec)) : undefined;
  merged.clauses = {};
  merged.gaps = {};
  merged.gaps.agentTerminalErrors = workloads['scripted-agent']?.gaps?.agentTerminalErrors;
  for (const clause of new Set([...CLAUSES.E2, ...CLAUSES.E3])) {
    const applicable = clause === 'idle-rejections' ? [list[0]]
      : ['scripted-rejections', 'agent-api-p95'].includes(clause) ? [list[1]] : list;
    merged.gaps[clause] = applicable.map(r => r?.gaps?.[clause]).filter(Boolean).join('; ') || undefined;
    const checks = applicable.map(r => r?.clauses[clause]);
    merged.clauses[clause] = checks.includes(false) ? false : checks.every(x => x === true) ? true : undefined;
  }
  if (present.some(r => r.failure)) merged.failure = present.filter(r => r.failure).map(r => r.failure).join('; ');
  if (present.some(r => r.valuesHash !== merged.valuesHash || r.revision !== merged.revision)) merged.failure = 'Workload provenance mismatch';
  return merged;
}
export function tableVerdicts(records, values) {
  const output = [];
  const pair = (group, clause, evaluate) => {
    const arms = ['main', 'branch'].map(arm => records[`${group}/${arm}`]);
    const checks = arms.map(r => r?.clauses?.[clause]);
    if (evaluate && arms.every(Boolean)) checks.push(evaluate(arms[1], arms[0]));
    if (arms.some(r => r?.failure)) checks.push(false);
    if (arms.every(Boolean)) {
      if (group === 'E2' || group === 'E3') {
        for (const w of ['agent-idle', 'scripted-agent']) {
          const m = arms[0].metrics?.queryPoolByWorkload?.[w], b = arms[1].metrics?.queryPoolByWorkload?.[w];
          if (arms[0].workloadRecords || arms[1].workloadRecords) checks.push(m && b ? m === b : undefined);
        }
      }
      checks.push(arms[0].pairIdentity === arms[1].pairIdentity, arms[0].valuesHash === arms[1].valuesHash);
    }
    const disposition = arms[0]?.dispositions?.[clause];
    const result = verdict(disposition?.status === 'unmeasurable-on-split' ? [...checks, undefined] : checks);
    output.push({ group, clause, verdict: result === 'unmeasurable' && disposition ? 'unmeasurable-on-split' : result,
      disposition,
      sources: arms.map(r => r?.id ?? 'missing').join(' / '),
      sourceRecords: arms.flatMap(r => r?.workloadRecords ?? [r?.recordFile]),
      reason: arms.map(r => r?.gaps?.[clause]).filter(Boolean).join('; ') || 'paired clause and frozen values' });
  };
  for (const [group, clauses] of Object.entries(CLAUSES)) for (const clause of clauses) {
    let evaluate;
    if (group === 'E2' && clause === 'foreground-p95') evaluate = b => {
      if (values.foregroundSearchP95Ceiling.ceilingByWorkload) {
        const checks = ['agent-idle', 'scripted-agent'].flatMap(w => ['hybrid', 'lexical'].map(m => {
          const observed = b.metrics?.searchP95ByWorkload?.[w]?.[m];
          const bound = values.foregroundSearchP95Ceiling.ceilingByWorkload[w]?.[m];
          return finite(observed) && finite(bound) ? observed <= bound : undefined;
        }));
        return checks.includes(false) ? false : checks.every(c => c === true) ? true : undefined;
      }
      const bounds = values.foregroundSearchP95Ceiling.ceilingMs;
      return verdict(['hybrid', 'lexical'].map(mode => finite(b.metrics?.searchP95?.[mode]) && finite(bounds?.[mode])
        ? b.metrics.searchP95[mode] <= bounds[mode] : undefined)) === 'pass' ? true
        : ['hybrid', 'lexical'].some(mode => finite(b.metrics?.searchP95?.[mode]) && finite(bounds?.[mode])
          && b.metrics.searchP95[mode] > bounds[mode]) ? false : undefined;
    };
    if (group === 'E2' && clause === 'agent-api-p95') evaluate = b => finite(b.metrics?.agentP95)
      && finite(values.agentLoopApiP95Ceiling.ceilingMs) ? b.metrics.agentP95 <= values.agentLoopApiP95Ceiling.ceilingMs : undefined;
    if (group === 'E3' && clause === E3_RATE_CLAUSE) evaluate = b => {
      const checks = ['agent-idle', 'scripted-agent'].map(w => compareStageRates(
        b.metrics?.stagesByWorkload?.[w], values.indexingProgressFraction.stagesByWorkload?.[w]).check);
      return checks.includes(false) ? false : checks.every(c => c === true) ? true : undefined;
    };
    if (group === 'E4' && clause === 'machine-wide-commit-vs-main') evaluate = (b, m) =>
      finite(b.metrics?.peakCommitMB) && finite(m.metrics?.peakCommitMB) ? b.metrics.peakCommitMB <= m.metrics.peakCommitMB : undefined;
    if (group === 'E4' && clause === 'component-commit-budget') evaluate = (b, m) =>
      finite(b.metrics?.peakCommitMB) && finite(m.metrics?.peakCommitMB) ? b.metrics.peakCommitMB <= m.metrics.peakCommitMB : undefined;
    if (group === 'E5' && clause === 'crash-to-api') evaluate = b =>
      finite(b.metrics?.[clause]) ? b.metrics[clause] <= values.crashToApiRestoredMs : undefined;
    pair(group, clause, evaluate);
  }
  return output;
}

function filesUnder(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
    ? filesUnder(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}
export function sourceDirt(porcelain) {
  return porcelain.split(/\r?\n/).filter(Boolean).filter(line => {
    const name = line.slice(3).replaceAll('\\', '/');
    return !name.startsWith(`${EVIDENCE}/`);
  });
}
export function latestRecords(root) {
  const records = {};
  const windows = { main: {}, branch: {} };
  const workloads = { main: {}, branch: {} };
  for (const file of filesUnder(path.join(root, EVIDENCE)).filter(f => path.basename(f) === 'index.json')) {
    for (const entry of read(file).runs ?? []) {
      const record = read(entry.record);
      if (record.groups.includes('E4') && (!windows[record.arm][record.window]
        || windows[record.arm][record.window].startedAt < record.startedAt)) windows[record.arm][record.window] = record;
      if (record.groups.includes('E2') && record.workload && (!workloads[record.arm][record.workload]
        || workloads[record.arm][record.workload].startedAt < record.startedAt)) workloads[record.arm][record.workload] = record;
      for (const group of record.groups) if (!records[`${group}/${record.arm}`]
        || records[`${group}/${record.arm}`].startedAt < record.startedAt) records[`${group}/${record.arm}`] = record;
    }
  }
  for (const arm of ['main', 'branch']) {
    if (Object.keys(workloads[arm]).length) {
      const merged = mergeLoadRecords(workloads[arm]);
      records[`E2/${arm}`] = records[`E3/${arm}`] = merged;
    }
    const list = Object.values(windows[arm]);
    if (!list.length) continue;
    const merged = structuredClone(list.at(-1));
    merged.id = list.map(r => r.id).join(', ');
    merged.windowRecords = list.map(r => r.recordFile);
    // Each slot has its own acquisition plan (55/55/10 minutes). Compare the same
    // slots across arms, rather than requiring unlike durations to share an identity.
    merged.pairIdentityInputs = { kind: 'lane-f-e4-window-identities.v1', windows: Object.fromEntries(
      ['1', '2', '3'].map(w => [w, windows[arm][w]?.pairIdentity ?? null])) };
    merged.pairIdentity = hash(JSON.stringify(merged.pairIdentityInputs));
    merged.metrics.peakCommitMB = list.every(r => finite(r.metrics.peakCommitMB))
      ? Math.max(...list.map(r => r.metrics.peakCommitMB)) : undefined;
    for (const clause of CLAUSES.E4) {
      const applicable = clause === 'live-after-GC-trend' ? list.filter(r => r.window !== '3') : list;
      merged.clauses[clause] = applicable.length && verdict(applicable.map(r => r.clauses[clause])) === 'pass'
        ? true : applicable.some(r => r.clauses[clause] === false) ? false : undefined;
    }
    merged.metrics.worstPauseMs = list.every(r => finite(r.metrics.worstPauseMs)) ? Math.max(...list.map(r => r.metrics.worstPauseMs)) : undefined;
    const durationChecks = ['1', '2', '3'].map(w => windows[arm][w]?.clauses['window-duration']);
    durationChecks.push(list.every(r => r.valuesHash === merged.valuesHash
      && r.revision === merged.revision));
    merged.clauses['owner-duration'] = durationChecks.includes(false) ? false
      : durationChecks.every(c => c === true) ? true : undefined;
    if (list.some(r => r.failure)) merged.failure = list.filter(r => r.failure).map(r => r.failure).join('; ');
    records[`E4/${arm}`] = merged;
  }
  return records;
}
export function fillValues(document, records) {
  if (Object.values(records).some(r => r.arm === 'branch')) throw new Error('E0 is forbidden after any branch run');
  const quality = records['E1/main'];
  const load = records['E2/main'];
  if (!quality || !load || quality.failure || load.failure) throw new Error('Successful MAIN E1-E3 records required');
  if (load.clauses?.['indexing-window-valid'] !== true || load.workloadRecords?.length !== 2
    || [...CLAUSES.E2, ...CLAUSES.E3].some(c => load.clauses[c] !== true)) throw new Error('Both valid MAIN workload windows required');
  const metrics = load.metrics;
  if (!['agent-idle', 'scripted-agent'].every(w => INDEX_STAGES.some(stage => finite(metrics.stagesByWorkload?.[w]?.[stage]?.rate) && metrics.stagesByWorkload[w][stage].rate > 0)
    && ['hybrid', 'lexical'].every(m => finite(metrics.searchP95ByWorkload?.[w]?.[m]) && metrics.searchP95ByWorkload[w][m] > 0))
    || !['hybrid', 'lexical'].every(mode => finite(metrics.searchP95?.[mode]) && metrics.searchP95[mode] > 0)
    || !finite(metrics.agentP95) || metrics.agentP95 <= 0) {
    throw new Error('MAIN lacks measured E0 search/API p95 or positive stage rates; cannot invent bounds');
  }
  const next = structuredClone(document);
  const v = next.values;
  for (const key of ['foregroundSearchP95Ceiling', 'agentLoopApiP95Ceiling', 'indexingProgressFraction']) {
    v[key].measuredAtE0 = false;
    v[key].sourceRun = load.id;
  }
  v.foregroundSearchP95Ceiling.measuredMs = metrics.searchP95;
  v.foregroundSearchP95Ceiling.ceilingMs = Object.fromEntries(Object.entries(metrics.searchP95).map(([k, x]) => [k, x * 1.1]));
  v.foregroundSearchP95Ceiling.ceilingByWorkload = Object.fromEntries(Object.entries(metrics.searchP95ByWorkload ?? {}).map(([w, modes]) =>
    [w, Object.fromEntries(Object.entries(modes).map(([m, x]) => [m, x * 1.1]))]));
  v.agentLoopApiP95Ceiling.measuredMs = metrics.agentP95;
  v.agentLoopApiP95Ceiling.ceilingMs = metrics.agentP95 * 1.1;
  for (const obsolete of ['measuredChunksPerSec', 'minimumChunksPerSec', 'minimumByWorkload']) delete v.indexingProgressFraction[obsolete];
  v.indexingProgressFraction.stagesByWorkload = Object.fromEntries(['agent-idle', 'scripted-agent'].map(w =>
    [w, Object.fromEntries(INDEX_STAGES.map(stage => {
      const measured = metrics.stagesByWorkload[w][stage];
      return [stage, { ...measured, minimumRate: finite(measured?.rate) && measured.rate > 0 ? measured.rate * .9 : undefined,
        comparison: finite(measured?.rate) && measured.rate > 0 ? 'compared' : 'not-compared' }];
    }))]));
  v.indexingProgressFraction.sourceRecords = load.workloadRecords;
  next.e0 = { qualityRun: quality.id, loadRun: load.id, fixedAt: new Date().toISOString() };
  return next;
}

function resolveCommand(command, bindings) {
  const replace = s => s.replace(/\$\{(\w+)\}/g, (_, key) => {
    if (bindings[key] === undefined) throw new Error(`Unresolved ${key}`);
    return bindings[key];
  });
  return { ...command, args: command.args?.map(replace), directory: command.directory && replace(command.directory),
    env: command.env && Object.fromEntries(Object.entries(command.env).map(([k, v]) => [k, replace(v)])) };
}
function execute(command, context) {
  const log = path.join(context.raw, `${context.sequence++}-${command.label}`);
  const out = fs.openSync(`${log}.stdout`, 'w');
  const err = fs.openSync(`${log}.stderr`, 'w');
  const child = spawn(command.executable, command.args, { cwd: command.cwd,
    env: { ...process.env, ...command.env, JUSTSEARCH_SESSION_TOKEN: context.token ?? '' },
    stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  let stdout = '';
  child.stdout.on('data', b => { fs.writeSync(out, b); stdout += b; });
  child.stderr.on('data', b => fs.writeSync(err, b));
  const receipt = { ...command, startedAt: new Date().toISOString(), stdoutFile: `${log}.stdout`, stderrFile: `${log}.stderr` };
  context.record.commands.push(receipt);
  const complete = new Promise(resolve => {
    child.once('error', error => { receipt.error = error.message; });
    child.once('close', (code, signal) => {
      fs.closeSync(out); fs.closeSync(err);
      Object.assign(receipt, { code, signal, endedAt: new Date().toISOString() });
      resolve({ code, stdout, receipt });
    });
  });
  let expired = false;
  const timer = setTimeout(() => {
    expired = true;
    // The registered supervisor is stopped by its owned lifecycle, never by a PID kill.
    if (command.mode !== 'start') child.kill();
  }, Math.max(1, Math.min(context.deadline - Date.now(), (command.budgetSeconds ?? 3540) * 1000)));
  complete.finally(() => clearTimeout(timer));
  return { child, complete, stdout: () => stdout, expired: () => expired };
}

export function collect(context) {
  const r = context.record;
  for (const command of r.commands) {
    if (!command.stdoutFile || !fs.existsSync(command.stdoutFile)) continue;
    const output = fs.readFileSync(command.stdoutFile, 'utf8');
    for (const match of output.matchAll(/"runId"\s*:\s*"([a-zA-Z0-9-]+)"/g)) {
      if (!r.runIds.includes(match[1])) r.runIds.push(match[1]);
    }
  }
  const summaries = filesUnder(context.raw).filter(f => path.basename(f) === 'summary.json').map(read);
  const loads = summaries.filter(s => s.search_load);
  r.metrics.searchP95 = {};
    for (const s of loads) {
    const load = s.search_load;
    if (finite(load.latency_ms?.p95)) r.metrics.searchP95[load.search_mode] = Math.max(r.metrics.searchP95[load.search_mode] ?? 0, load.latency_ms.p95);
  }
  const captures = filesUnder(context.raw).filter(f => path.basename(f) === 'workload.json').map(read);
  const calls = captures.flatMap(c => c.requests ?? []);
  const admitted = calls.filter(isAdmitted);
  Object.assign(r.metrics, agentMetrics(calls));
  if (Object.keys(r.metrics.agentTerminalErrors).length) r.gaps.agentTerminalErrors = JSON.stringify(r.metrics.agentTerminalErrors);
  if (calls.length) r.clauses['no-timeout-or-5xx'] = calls.every(c => !c.error && terminalComplete(c)
    && (c.status >= 200 && c.status < 300 || c.status === 429));
  r.metrics.chunksByWorkload = {};
  for (const file of filesUnder(context.raw).filter(f => path.basename(f) === 'status-series.csv')) {
    const [header, ...lines] = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
    const columns = header.split(',');
    const samples = lines.map(line => line.split(','));
    if (samples.length > 1) {
      const first = samples[0], last = samples.at(-1), ci = columns.indexOf('chunkDocCount');
      const seconds = (Date.parse(last[0]) - Date.parse(first[0])) / 1000;
      const delta = Number(last[ci]) - Number(first[ci]);
      if (ci >= 0 && seconds > 0 && delta >= 0) {
        r.metrics.chunksByWorkload[path.basename(path.dirname(file))] = delta / seconds;
        r.metrics.chunksPerSec = Math.min(r.metrics.chunksPerSec ?? Infinity, delta / seconds);
      }
    }
  }
  if (r.groups.includes('E1')) {
    const gate = path.join(context.raw, 'relevance-gate.json');
    if (fs.existsSync(gate)) r.clauses['baseline-quality'] = qualityGateVerdict(read(gate));
    const fixtures = filesUnder(path.join(context.raw, 'fixture')).filter(f => /^capture-\d+\.json$/.test(path.basename(f))).map(read);
    r.clauses['SearchTrace-shape'] = fixtures.length === context.values.noisePairGate.capturesPerSide
      ? fixtures.every(c => Object.values(c.queries ?? {}).length > 0 && Object.values(c.queries).every(q =>
        q.httpStatus === 200 && typeof q['trace.effectiveMode'] === 'string'
        && typeof q['trace.decisionKind'] === 'string' && q['trace.stageIds']?.length > 0)) : undefined;
  }
  if (r.groups.includes('E2') && r.workload) {
    const file = path.join(context.raw, r.workload, 'bulk-load.json');
    if (fs.existsSync(file)) projectLoad(r, read(file), calls, context.values);
  } else if (r.groups.includes('E2')) {
    r.clauses['no-timeout-or-5xx'] = calls.length && loads.length
      ? calls.every(c => !c.error && ((c.status >= 200 && c.status < 300) || c.status === 429)
        && terminalComplete(c))
        && loads.every(s => s.search_load.errors === 0) : undefined;
    r.clauses['foreground-p95'] = Object.keys(r.metrics.searchP95).length === 2 ? true : undefined;
    r.clauses['agent-api-p95'] = admitted.length ? true : undefined;
    const rejectionCodes = new Set(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT']);
    r.clauses['scripted-rejections'] = calls.length ? calls.filter(c => c.status === 429).every(c =>
      rejectionCodes.has(c.code) && c.retrySafe === true && /^[1-9]\d*$/.test(c.retryAfter))
      && calls.filter(c => c.status === 429).length / calls.length <= context.values.admissionRejectionCeiling.scriptedAgentFraction : undefined;
    r.clauses['idle-rejections'] = loads.length ? loads.filter(s => s.search_load && !s.search_load.errors).length === loads.length : undefined;
  }
  // Legacy unbounded load records have no validated per-stage population.
  if (r.groups.includes('E4')) {
    const completedCycles = r.commands.filter(c => /^soak-cycle-/.test(c.label) && c.code === 0);
    const workload = completedCycles.length && loads.length && calls.length
      ? loads.every(s => s.search_load.errors === 0) && calls.every(c => !c.error && terminalComplete(c)
        && (c.status >= 200 && c.status < 300 || c.status === 429)) : undefined;
    r.metrics.completedSoakCycles = completedCycles.length;
    r.clauses['index-agent-reconfigure-workload'] = verdict([
      r.clauses['index-agent-reconfigure-workload'], workload]) === 'pass' ? true : workload === false ? false : undefined;
  }

}

/** Re-score only retained E2/E3 wire evidence. Never refit E0 or alter launch provenance. */
export function reprojectRecord(file, values, dryRun = false) {
  const original = read(file);
  if (!original.endedAt || !original.groups?.includes('E2') || !original.groups?.includes('E3')
    || !['agent-idle', 'scripted-agent'].includes(original.workload)) throw new Error('Complete E2/E3 workload record required');
  const directory = path.join(original.raw, original.workload);
  const bulk = path.join(directory, 'bulk-load.json');
  const workload = path.join(directory, 'workload.json');
  const required = [...(original.rawFiles ?? []), bulk, ...(original.workload === 'scripted-agent' ? [workload] : [])];
  const missing = required.filter(f => !fs.existsSync(f) || !fs.statSync(f).isFile());
  if (!required.length || missing.length) throw new Error(`Missing retained raw files: ${missing.join(', ')}`);
  const projected = structuredClone(original);
  projected.measuredProjection ??= { metrics: original.metrics, clauses: original.clauses, gaps: original.gaps };
  projected.projectionHistory = [...(original.projectionHistory ?? []),
    { at: original.reprojectedAt ?? original.endedAt, driverHash: original.reprojectionDriverHash,
      metrics: original.metrics, clauses: original.clauses, gaps: original.gaps }];
  projected.metrics = {}; projected.clauses = {}; projected.gaps = {};
  const calls = original.workload === 'scripted-agent' ? read(workload).requests ?? [] : [];
  projectLoad(projected, read(bulk), calls, values);
  projected.reprojectedAt = new Date().toISOString();
  projected.reprojectionDriverHash = hash(fs.readFileSync(fileURLToPath(import.meta.url)));
  projected.reprojectionValuesHash = hash(JSON.stringify(values));
  projected.reprojectionRawHashes = Object.fromEntries([bulk, ...(original.workload === 'scripted-agent' ? [workload] : [])]
    .map(f => [f, hash(fs.readFileSync(f))]));
  if (!dryRun) write(file, projected);
  return projected;
}

export async function main(argv = process.argv.slice(2), root = ROOT) {
  const options = parseArgs(argv);
  root = path.resolve(options.repoRoot ?? root);
  const valuesFile = options.command === 'reproject' && !options.repoRoot
    ? path.resolve(path.dirname(options.record), '../../values.json') : path.join(root, EVIDENCE, 'values.json');
  const document = read(valuesFile), values = document.values;
  if (options.command === 'reproject') {
    const result = reprojectRecord(path.resolve(options.record), values, options.dryRun);
    console.log(JSON.stringify({ recordFile: result.recordFile, reprojectedAt: result.reprojectedAt, metrics: result.metrics, clauses: result.clauses }, null, 2));
    return;
  }
  const fixtureDecision = options.command === 'e1-quality' ? checkFixturePins(root) : undefined;
  const plan = buildPlan(options, values, root, fixtureDecision);
  if (options.dryRun) {
    console.log(JSON.stringify({ options, fixtureDecision, limitSeconds: 3540, loadBudgetSeconds: options.command === 'e2-e3-load' ? 3000 : undefined, leaseDurationSec: 3600, commands: plan }, null, 2));
    return;
  }
  const records = latestRecords(root);
  if (options.command === 'e4-hang-values') {
    const arms = ['main', 'branch'].map(arm => records[`E4/${arm}`]);
    if (arms.some(r => !r || r.failure || r.clauses['owner-duration'] !== true || !finite(r.metrics.worstPauseMs))) {
      throw new Error('Both complete E4 arm records with observed safepoint pauses required');
    }
    const worstPauseMs = Math.max(...arms.map(r => r.metrics.worstPauseMs));
    values.hangParameters = { ...values.hangParameters, intervalMs: 10000,
      missCount: Math.max(3, Math.ceil(3 * worstPauseMs / 10000)), worstPauseMs,
      measuredAtE4: false, sourceRuns: arms.map(r => r.id), frozenAt: new Date().toISOString() };
    hangPolicy(values, worstPauseMs); write(valuesFile, document); return;
  }
  if (options.command === 'e0-values') {
    write(valuesFile, fillValues(document, records));
    // MAIN established these bounds: bind its existing reference records to the frozen file.
    const valuesHash = hash(fs.readFileSync(valuesFile));
    for (const r of new Set(Object.values(records))) if (r.arm === 'main' && ['E1', 'E2', 'E3'].some(g => r.groups.includes(g))) {
      for (const file of r.workloadRecords ?? []) {
        const source = read(file); source.executedValuesHash ??= source.valuesHash;
        source.valuesHash = valuesHash; write(file, source);
      }
      if (r.workloadRecords) continue;
      r.executedValuesHash ??= r.valuesHash;
      r.valuesHash = valuesHash; write(r.recordFile, r);
    }
    return;
  }
  if (options.command === 'table') {
    const mainArm = records['E1/main'], branchArm = records['E1/branch'];
    if (mainArm && branchArm) {
      const raw = path.join(root, 'tmp/lane-f-e/table', randomUUID());
      fs.mkdirSync(raw, { recursive: true });
      const context = { raw, record: { commands: [] }, sequence: 0, deadline: Date.now() + 60000 };
      const gate = resolveCommand(plan[0], { mainFixture: path.join(mainArm.raw, 'fixture'),
        branchFixture: path.join(branchArm.raw, 'fixture'), fixtureReport: path.join(raw, 'fixture-gate.json') });
      const outcome = await execute(gate, context).complete;
      write(path.join(raw, 'commands.json'), context.record.commands);
      for (const record of [mainArm, branchArm]) for (const clause of ['workflow-evidence-citations-cancellation', 'allowed-differences']) {
        record.clauses[clause] = outcome.code === 0;
        record.gaps[clause] = `fixture-gate raw report: ${raw}`;
      }
      for (const record of [mainArm, branchArm]) write(record.recordFile, record);
    }
    const rows = tableVerdicts(records, values);
    const text = ['# Stage E paired verdicts', '', '| Group | Clause | Verdict | MAIN / BRANCH run | Reason |',
      '|---|---|---|---|---|', ...rows.map(r => `| ${r.group} | ${r.clause} | ${r.verdict} | ${r.sourceRecords.map((file, i) =>
        file ? `[${path.basename(path.dirname(file)).toUpperCase()}](${path.relative(path.join(root, EVIDENCE), file).replaceAll('\\', '/')})` : 'missing').join(' / ')} | ${r.reason} |`), '',
      ...Object.keys(CLAUSES).map(group => `${group}: **${verdict(rows.filter(r => r.group === group).map(r => r.verdict === 'pass' ? true : r.verdict === 'fail' ? false : undefined))}**`), '',
      'E7 is operator-driven and externally blocked on signing. Unmeasurable is not a waiver.',
      'One-sided feature acceptance: [D1](../../stages/D1.md) and [D2](../../stages/D2.md).', ''].join('\n');
    fs.writeFileSync(path.join(root, EVIDENCE, 'table.md'), text);
    return;
  }
  if (options.arm === 'branch' && Object.values(values).some(v => v?.measuredAtE0 === true)) throw new Error('Run MAIN E1-E3 and e0-values before any branch run');
  if (options.command === 'e6-hang') hangPolicy(values, values.hangParameters?.worstPauseMs);
  const id = `${new Date().toISOString().replaceAll(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`;
  const raw = path.join(root, 'tmp/lane-f-e', options.command, options.arm, id);
  fs.mkdirSync(raw, { recursive: true });
  const groups = { 'e1-quality': ['E1'], 'e2-e3-load': ['E2', 'E3'], 'e4-memory-soak': ['E4'], 'e5-crash': ['E5'], 'e6-hang': ['E6'] }[options.command];
  const machine = { hostname: os.hostname(), platform: os.platform(), release: os.release(), arch: os.arch(),
    cpu: os.cpus().map(c => c.model), ramBytes: os.totalmem(), node: process.version };
  const corpusFiles = ['docs/explanation', 'docs/reference'].flatMap(dir => filesUnder(path.join(ARMS.main, dir))).sort();
  const { pairIdentity, pairIdentityInputs } = measurementIdentity(plan, {
    sourceRoot: ROOT, outputRoot: root, armTree: options.armTree ? path.resolve(options.armTree) : ARMS[options.arm],
    arm: options.arm, group: options.command, raw, invocation: id, session: `lane-f-e-${id}`,
    machine, corpus: corpusFiles.map(f => [path.relative(ARMS.main, f), hash(fs.readFileSync(f))]),
    workload: options.workload ?? options.command, heap: values.heap, collector: values.collector,
  });
  const destination = path.join(root, EVIDENCE, options.command, options.arm);
  const recordFile = path.join(destination, `${id}.json`);
  const record = { kind: 'lane-f-e-run.v1', id, groups, arm: options.arm, window: options.window, workload: options.workload,
    startedAt: new Date().toISOString(), machine, pairIdentity, pairIdentityInputs, valuesHash: hash(fs.readFileSync(valuesFile)),
    recordFile, raw, runIds: [], commands: [], metrics: {}, clauses: {}, gaps: {},
    fixtureDecision,
    additionalArtifacts: [] };
  const context = { raw, record, values, deadline: Date.now() + (options.command === 'e2-e3-load' ? 3000000 : 3540000), sequence: 0, background: [], root };
  const bindings = { invocation: id, session: `lane-f-e-${id}` };
  const index = path.join(destination, 'index.json');
  write(recordFile, record); // Even failed/aborted branch starts prevent a later E0 refit.
  write(index, { runs: [...(fs.existsSync(index) ? read(index).runs : []), { id, record: recordFile, raw }] });
  try {
    fs.mkdirSync(path.join(raw, 'gc'), { recursive: true });
    for (const template of plan) {
      if (Date.now() >= context.deadline) throw new Error('Invocation deadline');
      const command = resolveCommand(template, bindings);
      if (command.mode === 'ai-activate') { await activateChat(command, context); continue; }
      if (command.mode === 'fixture-reuse') {
        for (const capture of command.captures) {
          if (hash(fs.readFileSync(capture.file)) !== capture.sha256) throw new Error('PR 0b capture changed after pin check');
          const target = path.join(raw, 'fixture', path.basename(capture.file));
          fs.mkdirSync(path.dirname(target), { recursive: true });
          fs.copyFileSync(capture.file, target);
        }
        record.commands.push({ ...command, copiedAt: new Date().toISOString() });
        continue;
      }
      if (command.mode === 'instruments-start') {
        context.collector = await new LiveCollector(context, command).start(); continue;
      }
      if (command.mode === 'instruments-stop') {
        await context.collector.stop();
        if (groups.includes('E4')) { context.collector.projectE4(); context.instrumentedE4 = true; }
        context.collector = null; continue;
      }
      if (command.mode === 'crash-experiment') { await crashExperiment(context); continue; }
      if (command.mode === 'hang-experiment') { await hangExperiment(context, command); continue; }
      if (command.mode === 'child-path') { await childPathExperiment(context, command.reason); continue; }
      if (command.mode === 'end-samples') {
        fs.writeFileSync(path.join(command.directory, 'rss.stop'), 'stop');
        fs.writeFileSync(path.join(command.directory, 'status.stop'), 'stop');
        fs.writeFileSync(path.join(command.directory, 'admission.stop'), 'stop');
        for (const proc of context.background.splice(0)) {
          const result = await proc.complete;
          if (result.code !== 0) throw new Error(`Instrument failed: ${result.receipt.label}`);
        }
        continue;
      }
      for (const arg of command.args.filter(a => /\.(csv|tsv|json)$/.test(a) && a.startsWith(raw))) fs.mkdirSync(path.dirname(arg), { recursive: true });
      if (command.mode === 'start') {
        await startOwned(command, context, bindings, execute, path.resolve(ARMS.main, '../../../models'));
        continue;
      }
      if (command.mode === 'background') {
        context.background.push(execute(command, context)); continue;
      }
      if (command.mode === 'soak') {
        const end = Date.now() + command.minutes * 60000;
        if (end + 60000 > context.deadline) throw new Error('Insufficient one-hour budget for complete soak window');
        let cycle = 0;
        while (Date.now() < end) {
          const iteration = { ...command, label: `soak-cycle-${++cycle}`, args: [...command.args, '--reset'] };
          const cycleContext = { ...context, deadline: Math.min(context.deadline, end) };
          const execution = execute(iteration, cycleContext);
          context.sequence = cycleContext.sequence;
          const result = await execution.complete;
          if (execution.expired() && Date.now() >= end) break;
          if (result.code !== 0) throw new Error(`Soak cycle exited ${result.code}`);
        }
        record.metrics.measuredMinutes = command.minutes;
        continue;
      }
      const result = await execute(command, context).complete;
      if (result.code !== 0) throw new Error(`${command.label} exited ${result.code}; see ${result.receipt.stderrFile}`);
      if (command.label === 'runner-status') {
        const status = JSON.parse(result.stdout);
        if (status.runId) throw new Error(`Shared stack occupied by ${status.runId}; no takeover`);
      } else if (command.label === 'dirty') {
        if (sourceDirt(result.stdout).length) throw new Error('Arm source tree is dirty; built revision is ambiguous');
      } else if (command.label === 'revision') {
        record.revision = result.stdout.trim();
        if (options.arm === 'main' && !record.revision.startsWith('ac1c93bf3')) throw new Error('MAIN revision differs from owner pin');
      }
      if (command.mode === 'stop') { context.owned = null; delete bindings.runId; context.token = null; }
    }
    collect(context);
    if (groups.includes('E5')) projectChildPolicies(record);
  } catch (error) {
    record.failure = error.message;
    process.exitCode = 1;
  } finally {
    if (context.collector) {
      try {
        await context.collector.stop();
        if (groups.includes('E4')) { context.collector.projectE4(); context.instrumentedE4 = true; }
      } catch (error) { record.failure = `${record.failure ?? ''}; collector cleanup: ${error.message}`; }
    }
    for (const proc of context.background) proc.child.kill();
    if (context.owned) {
      const command = resolveCommand(plan.find(c => c.mode === 'stop'), bindings);
      context.deadline = Math.min(context.deadline + 30000, Date.parse(record.startedAt) + 3590000);
      const stopped = await execute(command, context).complete;
      if (stopped.code !== 0) record.failure = `${record.failure ?? ''}; owned stop failed`;
    }
    // Retain diagnostics even when an analyzer/start/load step failed before normal projection.
    if (record.failure) {
      try { collect(context); } catch (error) { record.failure += `; evidence projection: ${error.message}`; }
    }
    record.endedAt = new Date().toISOString();
    record.rawFiles = [raw, ...record.additionalArtifacts].flatMap(filesUnder);
    for (const group of groups) for (const clause of CLAUSES[group]) {
      if (record.clauses[clause] === undefined && !record.gaps[clause]) record.gaps[clause] =
        'Named instrument did not produce a validated measurement for this clause';
    }
    write(recordFile, record);
    console.log(JSON.stringify({ id, recordFile, failure: record.failure, gaps: record.gaps }, null, 2));
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
