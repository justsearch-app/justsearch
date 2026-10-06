#!/usr/bin/env node
/**
 * Execution evidence for new checks — tempdoc 966 D4. The script, not the agent, writes the file.
 *
 *   node scripts/governance/gates/test-intent/run-evidence.mjs \
 *     --out gates/test-intent/evidence/<name>.json \
 *     --check <check id> [--check <check id> ...] \
 *     [--before <ref> [--overlay <path> ...]]
 *
 * It runs the named checks itself (Gradle → JUnit XML, vitest → JSON report, cargo → libtest
 * output), validates its own output (a fresh result file per run; a check that matched nothing is
 * `not-found`, a skipped one is `skipped` — neither is executed), and records: revision, working-tree
 * hash, the exact commands, the environment, a content digest of every named check's file, and
 * executed versus skipped per check.
 *
 * `--before <ref>`: the fail-before run for repaired behaviour. A detached worktree of <ref> gets
 * the candidate's check files (for a Rust production file, only its #[cfg(test)] items are
 * transplanted) plus any `--overlay` paths, the same commands run there, and each check's failure is
 * classified: `assertion` (the only one that shows the old behaviour is wrong), `exception`,
 * `compile`, `environment`, `passed`, `skipped`.
 *
 * Exit: 0 when every check executed and passed on the candidate (and, with --before, failed with an
 * assertion before); 1 otherwise (the file is still written, so the failure is on record); 2 usage.
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { linkNodeModules, removeBeforeWorktree } from './before-worktree.mjs';
import { normalizeText } from './changeset.mjs';
import { EVIDENCE_PRODUCER, EVIDENCE_SCHEMA, checkFile, checkName, contentDigest, evidenceSelfDigest } from './evidence.mjs';
import {
  classifyFailBefore,
  gradleTarget,
  parseJUnitXml,
  parseLibtest,
  parseVitestJson,
  runnerFor,
  selectJUnitCases,
  selectVitestCases,
  summarize,
} from './evidence-runners.mjs';
import { extractTestItems, testModuleFiles } from './rust-tests.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..', '..', '..');
const IS_WIN = process.platform === 'win32';

function sh(command, cwd) {
  const started = Date.now();
  const res = spawnSync(command, { cwd, shell: true, encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
  return {
    command,
    cwd: path.relative(REPO_ROOT, cwd).replaceAll('\\', '/') || '.',
    exitCode: res.status ?? -1,
    durationMs: Date.now() - started,
    output: `${res.stdout ?? ''}\n${res.stderr ?? ''}`,
  };
}

function gitOut(args, cwd, env) {
  const res = spawnSync('git', args, { cwd, encoding: 'utf8', env: env ?? process.env });
  if (res.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${res.stderr}`);
  return res.stdout.trim();
}

/** Tree hash of the working tree (tracked + untracked, ignored excluded), via a throwaway index. */
function workingTreeHash(root) {
  const idx = path.join(os.tmpdir(), `test-intent-index-${process.pid}-${Date.now()}`);
  const env = { ...process.env, GIT_INDEX_FILE: idx };
  try {
    gitOut(['read-tree', 'HEAD'], root, env);
    gitOut(['add', '-A'], root, env);
    return gitOut(['write-tree'], root, env);
  } finally {
    fs.rmSync(idx, { force: true });
  }
}

function toolVersion(cmd) {
  const r = spawnSync(cmd, { shell: true, encoding: 'utf8' });
  return r.status === 0 ? `${r.stdout}${r.stderr}`.trim().split(/\r?\n/)[0] : null;
}

/** Run every check in `root`; returns {runs, results: Map<checkId, {cases, buildOutput, fileStatus, fileMessage}>}. */
function runChecks(checks, root) {
  const runs = [];
  const results = new Map();
  const gradleGroups = new Map();
  const vitestFiles = new Map();
  for (const id of checks) {
    const r = runnerFor(id);
    if (r === 'gradle') {
      const t = gradleTarget(id);
      if (t.error) throw new Error(t.error);
      const key = `${t.project}:${t.task}`;
      if (!gradleGroups.has(key)) gradleGroups.set(key, []);
      gradleGroups.get(key).push({ id, t });
    } else if (r === 'vitest') {
      const lockdown = /-lockdown\.test\./.test(checkFile(id));
      const key = lockdown ? 'lockdown' : 'default';
      if (!vitestFiles.has(key)) vitestFiles.set(key, []);
      vitestFiles.get(key).push(id);
    } else if (r === 'cargo') {
      if (!checkName(id)) throw new Error(`${id}: a Rust check needs '#<libtest name>' (e.g. updater::tests::rejects_bad_sig)`);
      const name = checkName(id);
      const run = sh(`cargo test --manifest-path modules/shell/src-tauri/Cargo.toml -- --exact ${JSON.stringify(name)}`, root);
      runs.push(run);
      results.set(id, { runner: 'cargo', cases: parseLibtest(run.output).filter((c) => c.name === name), buildOutput: run.output });
    } else {
      throw new Error(`${id}: no runner for this path (JVM test source set, ui-web test file, or shell crate .rs)`);
    }
  }
  for (const group of gradleGroups.values()) {
    for (const { t } of group) fs.rmSync(path.join(root, t.resultFile), { force: true });
    const filters = [...new Set(group.map(({ t }) => (t.method ? `${t.fqcn}.${t.method}` : t.fqcn)))];
    const wrapper = IS_WIN ? '.\\gradlew.bat' : './gradlew';
    const { project, task } = group[0].t;
    const run = sh(`${wrapper} ${project}:${task} ${filters.map((f) => `--tests ${JSON.stringify(f)}`).join(' ')} --rerun -PskipWebBuild=true --console=plain`, root);
    runs.push(run);
    for (const { id, t } of group) {
      const xmlPath = path.join(root, t.resultFile);
      const cases = fs.existsSync(xmlPath) ? selectJUnitCases(parseJUnitXml(fs.readFileSync(xmlPath, 'utf8')), t) : [];
      results.set(id, { runner: 'gradle', cases, buildOutput: run.output });
    }
  }
  for (const [kind, ids] of vitestFiles) {
    const out = path.join(os.tmpdir(), `test-intent-vitest-${process.pid}-${Date.now()}.json`);
    const files = [...new Set(ids.map((id) => checkFile(id).replace(/^modules\/ui-web\//, '')))];
    const config = kind === 'lockdown' ? ' --config vitest.config.lockdown.ts' : '';
    const run = sh(`npx vitest run ${files.map((f) => JSON.stringify(f)).join(' ')}${config} --reporter=json --outputFile=${JSON.stringify(out)}`, path.join(root, 'modules', 'ui-web'));
    runs.push(run);
    const parsed = fs.existsSync(out) ? parseVitestJson(fs.readFileSync(out, 'utf8')) : [];
    fs.rmSync(out, { force: true });
    for (const id of ids) {
      const sel = selectVitestCases(parsed, id);
      results.set(id, { runner: 'vitest', ...sel, buildOutput: run.output });
    }
  }
  return { runs, results };
}

/** Put the candidate's checks into the before-worktree. */
function overlayInto(worktree, checks, overlays) {
  const files = [...new Set([...checks.map(checkFile), ...overlays])];
  for (const rel of files) {
    const src = path.join(REPO_ROOT, rel);
    const dst = path.join(worktree, rel);
    if (!fs.existsSync(src)) continue;
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    const isRustProd = rel.endsWith('.rs') && fs.existsSync(dst) && !overlays.includes(rel) && !rustTestFiles().has(rel);
    if (!isRustProd) {
      fs.copyFileSync(src, dst);
      continue;
    }
    // Transplant only the #[cfg(test)] items: the production half must stay the before-state.
    const cand = extractTestItems(normalizeText(fs.readFileSync(src, 'utf8')));
    const before = normalizeText(fs.readFileSync(dst, 'utf8'));
    if (cand.wholeFile) {
      fs.copyFileSync(src, dst);
      continue;
    }
    const old = extractTestItems(before);
    let text = before;
    for (const it of [...old.items].reverse()) text = text.slice(0, it.start) + text.slice(it.end);
    fs.writeFileSync(dst, `${text.replace(/\s+$/, '')}\n\n${cand.text}\n`, 'utf8');
  }
}

/** Files the candidate crate loads through a test-only `mod` declaration (whole-file test code). */
let rustTestFileCache = null;
function rustTestFiles() {
  if (rustTestFileCache) return rustTestFileCache;
  rustTestFileCache = new Set();
  const crate = 'modules/shell/src-tauri';
  const list = spawnSync('git', ['ls-files', '-co', '--exclude-standard', '--', crate], { cwd: REPO_ROOT, encoding: 'utf8' });
  for (const rel of (list.stdout ?? '').split(/\s+/).filter((p) => p.endsWith('.rs'))) {
    const abs = path.join(REPO_ROOT, rel);
    if (!fs.existsSync(abs)) continue;
    for (const f of testModuleFiles(rel, normalizeText(fs.readFileSync(abs, 'utf8')))) rustTestFileCache.add(f);
  }
  return rustTestFileCache;
}

function parseArgs(argv) {
  const a = { checks: [], overlays: [] };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    const v = () => {
      if (argv[i + 1] === undefined) throw new Error(`${k} needs a value`);
      return argv[++i];
    };
    if (k === '--check') a.checks.push(v().replaceAll('\\', '/'));
    else if (k === '--out') a.out = v();
    else if (k === '--before') a.before = v();
    else if (k === '--overlay') a.overlays.push(v().replaceAll('\\', '/'));
    else throw new Error(`unknown argument ${k}`);
  }
  if (!a.out || a.checks.length === 0) throw new Error('usage: --out <file> --check <id> [--check <id>...] [--before <ref> [--overlay <path>...]]');
  return a;
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(e.message);
    return 2;
  }
  for (const id of args.checks) {
    if (!fs.existsSync(path.join(REPO_ROOT, checkFile(id)))) {
      console.error(`${id}: ${checkFile(id)} does not exist`);
      return 2;
    }
  }
  const digestsBefore = new Map(args.checks.map((id) => [id, contentDigest(fs.readFileSync(path.join(REPO_ROOT, checkFile(id)), 'utf8'))]));
  const scriptText = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8');

  const { runs, results } = runChecks(args.checks, REPO_ROOT);
  const checks = args.checks.map((id) => {
    const r = results.get(id);
    const now = contentDigest(fs.readFileSync(path.join(REPO_ROOT, checkFile(id)), 'utf8'));
    if (now !== digestsBefore.get(id)) throw new Error(`${checkFile(id)} changed while the checks ran; re-run`);
    return {
      id,
      file: checkFile(id),
      runner: r.runner,
      contentDigest: now,
      ...summarize(r.cases),
      cases: r.cases.map((c) => ({ name: c.name, status: c.status })),
    };
  });

  let failBefore = null;
  if (args.before) {
    const wt = fs.mkdtempSync(path.join(os.tmpdir(), 'test-intent-before-'));
    fs.rmSync(wt, { recursive: true, force: true });
    gitOut(['worktree', 'add', '--detach', wt, args.before], REPO_ROOT);
    try {
      overlayInto(wt, args.checks, args.overlays);
      linkNodeModules(REPO_ROOT, wt);
      const fb = runChecks(args.checks, wt);
      failBefore = {
        ref: args.before,
        revision: gitOut(['rev-parse', `${args.before}^{commit}`], REPO_ROOT),
        overlays: args.overlays,
        runs: fb.runs.map(({ output: _o, ...r }) => ({ ...r, cwd: '<before-worktree>' })),
        checks: args.checks.map((id) => ({ id, ...classifyFailBefore(fb.results.get(id)) })),
      };
    } finally {
      const gone = removeBeforeWorktree(REPO_ROOT, wt);
      if (!gone.removed) console.error(`run-evidence: before-worktree not removed: ${gone.detail}`);
    }
  }

  const evidence = {
    schema: EVIDENCE_SCHEMA,
    producer: { script: EVIDENCE_PRODUCER, scriptDigest: contentDigest(scriptText) },
    createdAt: new Date().toISOString(),
    revision: gitOut(['rev-parse', 'HEAD'], REPO_ROOT),
    tree: workingTreeHash(REPO_ROOT),
    environment: {
      platform: process.platform,
      arch: process.arch,
      osRelease: os.release(),
      node: process.version,
      java: runs.some((r) => /gradlew/.test(r.command)) ? toolVersion('java -version') : null,
      cargo: runs.some((r) => /^cargo /.test(r.command)) ? toolVersion('cargo --version') : null,
      ci: Boolean(process.env.CI),
    },
    runs: runs.map(({ output: _o, ...r }) => r),
    checks,
    failBefore,
  };
  evidence.digest = evidenceSelfDigest(evidence);
  const outAbs = path.resolve(REPO_ROOT, args.out);
  fs.mkdirSync(path.dirname(outAbs), { recursive: true });
  fs.writeFileSync(outAbs, JSON.stringify(evidence, null, 2) + '\n', 'utf8');

  let ok = true;
  for (const c of checks) {
    const line = `${c.id}: ${c.outcome}${c.executed ? '' : ' (NOT executed)'}`;
    if (!c.executed || c.outcome !== 'passed') ok = false;
    console.log(line);
  }
  for (const c of failBefore?.checks ?? []) {
    console.log(`fail-before ${c.id}: ${c.classification} — ${c.detail}`);
    if (c.classification !== 'assertion') ok = false;
  }
  console.log(`evidence written: ${path.relative(REPO_ROOT, outAbs).replaceAll('\\', '/')}`);
  return ok ? 0 : 1;
}

try {
  process.exitCode = main();
} catch (e) {
  console.error(`run-evidence: ${e.message}`);
  process.exitCode = 2;
}
