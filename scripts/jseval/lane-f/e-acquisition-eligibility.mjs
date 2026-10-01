/** Acquisition eligibility and immutable machine/corpus receipts. Hashed with the instruments. */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { hangPolicy } from './e456-instruments.mjs';
import { verifySharedModels as checkSharedModels } from './e-start-ready.mjs';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const EVIDENCE = 'docs/design/lane-f-engine-jvm/evidence/E';
const filesUnder = dir => !fs.existsSync(dir) ? [] : fs.readdirSync(dir, { withFileTypes: true })
  .flatMap(e => e.isDirectory() ? filesUnder(path.join(dir, e.name)) : [path.join(dir, e.name)]);
export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
export const ARMS = Object.freeze({
  branch: 'F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify',
  main: 'F:/justsearch-public/.claude/worktrees/lane-f-e-main',
});
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
export function sourceDirt(porcelain) {
  return porcelain.split(/\r?\n/).filter(Boolean).filter(line => {
    const name = line.slice(3).replaceAll('\\', '/');
    return !name.startsWith(`${EVIDENCE}/`);
  });
}

export const sharedModelStore = () => path.resolve(ARMS.main, '../../../models');
export function runPrerequisites(options, values) {
  if (options.arm === 'branch' && Object.values(values).some(v => v?.measuredAtE0 === true)) throw new Error('Run MAIN E1-E3 and e0-values before any branch run');
  if (options.command === 'e6-hang') hangPolicy(values, values.hangParameters?.worstPauseMs);
}
export function acquisitionReceipts(options) {
  const machine = { hostname: os.hostname(), platform: os.platform(), release: os.release(), arch: os.arch(),
    cpu: os.cpus().map(c => c.model), ramBytes: os.totalmem(), node: process.version };
  const corpusFiles = options.command === 'e1-quality'
    ? ['docs/explanation', 'docs/reference'].flatMap(dir => filesUnder(path.join(ARMS.main, dir))).sort() : [];
  return { machine, corpus: corpusFiles.map(f => [path.relative(ARMS.main, f), hash(fs.readFileSync(f))]) };
}
