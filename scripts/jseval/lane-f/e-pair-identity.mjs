/** Measurement protocol identity. No scoring code, directory sweeps, or execution of instruments. */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SOURCE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const posix = value => value.replaceAll('\\', '/');
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])])) : value;

export function normalizePlan(plan, options) {
  const { sourceRoot = SOURCE, outputRoot = sourceRoot, armTree, arm, invocation, session, runId } = options;
  const replacements = [
    [options.raw, '<RAW>'], [path.join(outputRoot, 'tmp/lane-f-e', options.group ?? '', arm ?? '', '${invocation}'), '<RAW>'],
    // One placeholder for every checkout: in real runs the branch arm IS the tooling root, so a
    // path cannot say which role it plays, and the bytes it names are hashed separately anyway.
    [armTree, '<TREE>'], [sourceRoot, '<TREE>'], [outputRoot, '<TREE>'],
    [invocation, '<INVOCATION>'], [session, '<SESSION>'], [runId, '<RUN>'], [options.revision, '<ARM_REVISION>'],
  ].filter(([v]) => v).sort((a, b) => String(b[0]).length - String(a[0]).length);
  const text = value => {
    let s = posix(value);
    for (const [from, to] of replacements) s = s.replaceAll(posix(from), to);
    return s.replace(/\$\{(invocation|session|runId)\}/g, (_, k) => ({ invocation: '<INVOCATION>', session: '<SESSION>', runId: '<RUN>' })[k]);
  };
  const normalize = value => typeof value === 'string' ? text(value) : Array.isArray(value) ? value.map(normalize)
    : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, normalize(v)])) : value;
  return plan.map(command => {
    const env = { ...(command.env ?? {}) };
    // These configure the intentionally split subject, not an extra foreground workload.
    delete env.JUSTSEARCH_WORKER_HEAP; delete env.JUSTSEARCH_JVM_OPTS;
    if (env.JUSTSEARCH_FIXTURE_CORPUS_ROOT) env.JUSTSEARCH_FIXTURE_CORPUS_ROOT = '<CORPUS_TREE>';
    for (const key of Object.keys(env)) if (/^GIT_CONFIG_VALUE_\d+$/.test(key)
      && env[key.replace('VALUE', 'KEY')] === 'safe.directory') env[key] = '<TREE>';
    // The debugger attaches to the subject JVM on either arm, in different launch slots.
    if (options.group === 'e6-hang' && env.JAVA_OPTS) env.JAVA_OPTS = env.JAVA_OPTS.replace(/\s+-agentlib:jdwp=[^\s]+/, '');
    const result = normalize({ ...command, env });
    // Preserve parameters that control acquisition; remove receipts/results and arm descriptions.
    for (const key of ['arm', 'target', 'killTarget', 'splitDisposition', 'requestsAfterReceipt', 'identityRole', 'requireNonzeroCheckpoint']) delete result[key];
    if (command.mode === 'child-path' && command.reason === 'restart') result.mechanism = '<RESTART_SUBJECT>';
    result.label = text(command.label); result.mode = command.mode ?? 'command';
    result.executable = command.executable ? text(command.executable) : null;
    result.args = normalize(command.args ?? []);
    for (let i = 1; i < result.args.length; i++) if (['-Arm', '--arm'].includes(result.args[i - 1])) result.args[i] = '<ARM>';
    return stable(result);
  });
}

/** Import closure for local JS/TS; Python uses AST to include relative and function-local imports. */
export function instrumentFiles(plan, sourceRoot = SOURCE, executionRoot = sourceRoot) {
  const files = new Set(), pending = [], python = new Map(), pythonOwners = new Map();
  const root = (...parts) => path.join(sourceRoot, ...parts);
  const pythonRoot = (command, script = false) => {
    const search = [script ? undefined : command.cwd, ...(command.env?.PYTHONPATH ?? '').split(path.delimiter)]
      .filter(Boolean).map(p => path.resolve(p));
    return search.find(p => fs.existsSync(path.join(p, 'jseval/__init__.py'))) ?? path.join(executionRoot, 'scripts/jseval');
  };
  const add = (file, pyRoot = path.join(executionRoot, 'scripts/jseval')) => {
    const resolved = path.resolve(file);
    if (/(?:^|[\\/])(?:test|tests|__pycache__)(?:[\\/]|$)|(?:\.test\.|test-)/.test(resolved)) throw new Error(`Test is not a measurement instrument: ${file}`);
    if (!fs.existsSync(resolved)) throw new Error(`Missing measurement dependency: ${file}`);
    if (resolved.endsWith('.py')) {
      const owners = pythonOwners.get(resolved) ?? new Set(); owners.add(pyRoot); pythonOwners.set(resolved, owners);
    }
    if (!files.has(resolved)) { files.add(resolved); pending.push(resolved); }
  };
  const pythonModule = (module, pyRoot = path.join(executionRoot, 'scripts/jseval')) => {
    const base = path.join(pyRoot, ...module.split('.'));
    const file = fs.existsSync(`${base}.py`) ? `${base}.py` : path.join(base, '__main__.py');
    add(file, pyRoot);
  };
  for (const c of plan) {
    // Common stop-observation policy: candidate uses handle receipts, frozen MAIN uses reports.
    for (const file of c.acquisitionDependencies ?? []) add(file);
    if (c.mode === 'start') add(root('scripts/jseval/lane-f/e-acquire.mjs'));
    if (c.mode === 'start') { add(root('scripts/jseval/lane-f/e-start-ready.mjs')); add(root('scripts/jseval/lane-f/capability-ready.py'), pythonRoot(c, true)); }
    if (c.mode === 'ai-activate') add(root('scripts/jseval/lane-f/e-start-ready.mjs'));
    if (['instruments-start', 'crash-experiment', 'hang-experiment', 'child-path'].includes(c.mode)) add(root('scripts/jseval/lane-f/e456-live.mjs'));
    if (c.identityRole === 'scoring') continue;
    for (const arg of c.args ?? []) {
      // dev-runner is the subject's arm-specific launcher, like its application binaries.
      if (posix(arg).endsWith('/scripts/dev/dev-runner.cjs')) continue;
      if (/\.(?:mjs|cjs|js|ts|py|sh|ps1)$/.test(arg) && !arg.includes('${')) add(path.isAbsolute(arg) ? arg : path.resolve(c.cwd ?? sourceRoot, arg), pythonRoot(c, true));
    }
    const module = (c.args ?? []).indexOf('-m');
    if (module >= 0 && c.executable === 'python') pythonModule(c.args[module + 1], pythonRoot(c));
  }
  while (pending.length) {
    const file = pending.pop(), code = fs.readFileSync(file, 'utf8');
    if (file.endsWith('.py')) {
      for (const owner of pythonOwners.get(file)) {
        const seeds = python.get(owner) ?? new Set(); seeds.add(file); python.set(owner, seeds);
      }
      continue;
    }
    if (/\.(?:mjs|cjs|js|ts)$/.test(file)) {
      for (const m of code.matchAll(/(?:\bfrom\s*|\brequire\s*\(\s*|\bimport\s*\(\s*|\bimport\s*)['"](\.[^'"]+)['"]/g)) add(path.resolve(path.dirname(file), m[1]));
    }
    if (file.endsWith('.sh')) {
      const executableLines = code.split('\n').filter(l => !l.trimStart().startsWith('#')).join('\n');
      for (const m of executableLines.matchAll(/\b(?:node|source|bash)\s+(scripts\/[\w./-]+\.(?:mjs|cjs|sh))/g)) add(path.join(executionRoot, m[1]));
      for (const m of executableLines.matchAll(/\bfrom\s+(jseval[\w.]*)\s+import/g)) {
        const base = path.join(executionRoot, 'scripts/jseval', ...m[1].split('.')); add(`${base}.py`);
      }
      if (/python\s+-m\s+jseval\b/.test(executableLines)) pythonModule('jseval');
      // The request body/query pool is loaded by the supplemental encoder probe.
      if (['encoder-latency-probe.sh', 'fixture-cycle.sh'].includes(path.basename(file))) add(path.join(executionRoot, 'scripts/jseval/lane-f-workflow-fixture.v1.json'));
    }
  }
  if (python.size) {
    for (const [packageRoot, seeds] of python) {
      const result = spawnSync('python', [root('scripts/jseval/lane-f/e-pair-python.py'), packageRoot, ...seeds],
        { encoding: 'utf8', windowsHide: true, timeout: 30000 });
      if (result.status !== 0) throw new Error(`Python instrument inventory failed: ${result.stderr || result.error}`);
      for (const file of JSON.parse(result.stdout)) files.add(path.resolve(file));
    }
  }
  return [...files].sort();
}

export function measurementIdentity(plan, options, readBytes = fs.readFileSync) {
  const sourceRoot = options.sourceRoot ?? SOURCE;
  const executionRoot = options.outputRoot ?? sourceRoot;
  const instruments = {};
  for (const file of instrumentFiles(plan, sourceRoot, executionRoot)) {
    const owner = file.startsWith(`${executionRoot}${path.sep}`) ? executionRoot : sourceRoot;
    const name = posix(path.relative(owner, file));
    const hashes = instruments[name] ?? new Set(); hashes.add(digest(readBytes(file))); instruments[name] = hashes;
  }
  const inputs = {
    kind: 'lane-f-measurement-identity.v3', plan: normalizePlan(plan, options),
    instruments: Object.fromEntries(Object.entries(instruments).map(([name, hashes]) => [name, [...hashes].sort()])),
    corpus: options.corpus, scifact: options.scifact, models: options.models, machine: options.machine, workload: options.workload,
    heap: options.heap, collector: options.collector,
  };
  return { pairIdentity: digest(JSON.stringify(stable(inputs))), pairIdentityInputs: stable(inputs) };
}
