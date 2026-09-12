/**
 * Tempdoc 952 §5.3 / P2 — landed-ness receipt regressions on real Git.
 *
 * Every case builds its own throwaway repository under `os.tmpdir()` with `git init` and removes
 * it afterwards; nothing here touches the JustSearch repository, and no case shells out to the
 * real `gh` (PR lookups are injected stubs computed from the fixture).
 *
 * The scenarios are the researcher's experiments (952-evidence/researcher-recommendation-2026-09-10.md
 * §7) turned into assertions, because each one is a way the cheap answer ("is this branch merged?")
 * is WRONG under ADR-0045 squash merging.
 *
 * Run with: node scripts/agent-analytics/952-landing-receipt.test.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(HERE, '..', '..');
const require = createRequire(import.meta.url);
const {
  RECEIPT_VERDICTS,
  RECEIPT_REASONS,
  DEFAULT_RECEIPTS_FILE,
  DEFAULT_TARGET,
  computeReceipt,
  lookupMergedPr,
  simulateCoverage,
  loadReceipts,
  appendReceipt,
  findValidReceipt,
} = require(path.join(SOURCE_ROOT, 'scripts', 'dev', 'lib', 'landing-receipt.cjs'));

let passed = 0;
const failures = [];
const fixtures = [];

async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (err) {
    failures.push(`${label}: ${err.stack || err}`);
  }
}

/* ── Throwaway repository plumbing ─────────────────────────────────────────────────────────── */

function gitRun(repo, args) {
  const r = spawnSync('git', args, {
    cwd: repo,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
  });
  return { status: r.status, stdout: r.stdout || '', stderr: r.stderr || '' };
}

function git(repo, ...args) {
  const r = gitRun(repo, args);
  if (r.status !== 0) {
    throw new Error(`git ${args.join(' ')} exited ${r.status}:\n${r.stdout}${r.stderr}`);
  }
  return r.stdout.trim();
}

async function makeRepo(label) {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), `landing-receipt-952-${label}-`));
  const repo = path.join(root, 'repo');
  await fsp.mkdir(repo, { recursive: true });
  git(repo, 'init', '-q', '-b', 'main', '.');
  git(repo, 'config', 'user.email', 'fixture@example.invalid');
  git(repo, 'config', 'user.name', 'Fixture');
  git(repo, 'config', 'commit.gpgsign', 'false');
  const fixture = { root, repo };
  fixtures.push(fixture);
  return fixture;
}

function write(repo, rel, content) {
  const target = path.join(repo, ...rel.split('/'));
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, 'utf8');
}

function commitAll(repo, message) {
  git(repo, 'add', '-A');
  git(repo, 'commit', '-q', '-m', message);
  return git(repo, 'rev-parse', 'HEAD');
}

/** A `gh pr list` stub: one merged PR row, exactly the shape the real lookup asks for. */
function ghMergedPr({ number = 7, headRefOid, squash }) {
  return () => ({
    status: 0,
    stdout: JSON.stringify([{ number, headRefOid, mergeCommit: squash ? { oid: squash } : null }]),
    stderr: '',
  });
}

/** A `gh pr list` stub for "no merged PR". */
const ghNoPr = () => ({ status: 0, stdout: '[]', stderr: '' });

/** Injectable git runner bound to a fixture repository (production default shape). */
function gitRunner(repo) {
  return (args) => gitRun(repo, args);
}

function reasonCode(receipt) {
  return String(receipt.reason || '').split(':')[0];
}

/* ── Cases ─────────────────────────────────────────────────────────────────────────────────── */

try {
  await check('exp1: squash-merged branch is LANDED, and an unrelated later edit to the same file leaves it REDUNDANT_NOW too', async () => {
    const { repo } = await makeRepo('exp1');
    write(repo, 'a.txt', 'l1\nl2\nl3\nl4\nl5\n');
    const fork = commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'a.txt', 'l1\nl2\nl3\nl4\nl5\nfeat-a\n');
    commitAll(repo, 'feat c1');
    write(repo, 'a.txt', 'l1\nl2\nl3\nl4\nl5\nfeat-a\nfeat-b\n');
    const head = commitAll(repo, 'feat c2');
    git(repo, 'checkout', '-q', 'main');
    git(repo, 'merge', '--squash', '-q', 'feat');
    const squash = commitAll(repo, 'squash of feat');
    // The later edit touches the SAME file, above the landed lines: `git cherry` reports both
    // branch commits unmatched here (researcher §7 row 1), which is why patch identity is not
    // the mechanism.
    write(repo, 'a.txt', 'l0-new\nl1\nl2\nl3\nl4\nl5\nfeat-a\nfeat-b\n');
    const mainNow = commitAll(repo, 'unrelated later edit to the same file');

    const landed = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork,
      target: 'main',
      gh: ghMergedPr({ number: 717, headRefOid: head, squash }),
      git: gitRunner(repo),
    });
    assert.equal(landed.verdict, RECEIPT_VERDICTS.LANDED, landed.reason);
    assert.equal(reasonCode(landed), RECEIPT_REASONS.LANDED);
    assert.equal(landed.squash, squash);
    assert.equal(landed.pr, 717);
    assert.equal(landed.base, fork, 'recorded fork is an ancestor of the squash, so it is the base');
    assert.equal(landed.target, mainNow);
    assert.match(landed.ts, /^\d{4}-\d{2}-\d{2}T/);

    // OBSERVED, and asserted so a regression is legible: because the later edit did not touch the
    // branch's own lines, the weaker current-target test ALSO passes. REDUNDANT_NOW is therefore
    // not a fallback that only fires for unlanded work — it fires whenever today's target already
    // contains the head, which for landed work is the normal case.
    const redundant = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      anchor: fork,
      target: 'main',
      gh: ghNoPr,
      git: gitRunner(repo),
    });
    assert.equal(redundant.verdict, RECEIPT_VERDICTS.REDUNDANT_NOW, redundant.reason);
    assert.equal(reasonCode(redundant), RECEIPT_REASONS.REDUNDANT_NOW);
    assert.equal(redundant.squash, null, 'REDUNDANT_NOW proves nothing about a squash');
    assert.equal(redundant.target, mainNow);
  });

  await check('exp2: a later overlapping edit conflicts against current main, but the receipt against the squash stays LANDED', async () => {
    const { repo } = await makeRepo('exp2');
    write(repo, 'a.txt', 'l1\nl2\nl3\n');
    const fork = commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'a.txt', 'l1\nl2\nl3\nfeat-a\n');
    commitAll(repo, 'feat c1');
    write(repo, 'a.txt', 'l1\nl2\nl3\nfeat-a\nfeat-b\n');
    const head = commitAll(repo, 'feat c2');
    git(repo, 'checkout', '-q', 'main');
    git(repo, 'merge', '--squash', '-q', 'feat');
    const squash = commitAll(repo, 'squash of feat');
    write(repo, 'a.txt', 'l1\nl2\nl3\nMAIN-REWRITE-a\nMAIN-REWRITE-b\n');
    commitAll(repo, 'later edit overlapping the landed lines');

    const landed = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork,
      target: 'main',
      gh: ghMergedPr({ number: 708, headRefOid: head, squash }),
      git: gitRunner(repo),
    });
    assert.equal(landed.verdict, RECEIPT_VERDICTS.LANDED, landed.reason);
    assert.equal(landed.squash, squash);

    // The same head against today's main: a genuine conflict. This is what makes the historical
    // form necessary — a receipt computed only against the moving target would refuse work that
    // provably landed.
    const conflicted = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      anchor: fork,
      target: 'main',
      gh: ghNoPr,
      git: gitRunner(repo),
    });
    assert.equal(conflicted.verdict, RECEIPT_VERDICTS.UNKNOWN, conflicted.reason);
    assert.equal(reasonCode(conflicted), RECEIPT_REASONS.CONFLICT);
    assert.match(conflicted.reason, /CONFLICT \(content\): Merge conflict in a\.txt/);
    assert.ok(conflicted.reason.split('CONFLICT').length - 1 <= 4, 'reason carries at most the first 3 CONFLICT lines');
  });

  await check('exp3: a commit added after the merged head is not covered — the PR is rejected and the head is UNKNOWN', async () => {
    const { repo } = await makeRepo('exp3');
    write(repo, 'a.txt', 'base\n');
    const fork = commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'a.txt', 'base\nfeat\n');
    const mergedHead = commitAll(repo, 'feat c1');
    write(repo, 'b.txt', 'local work after the merge\n');
    const head = commitAll(repo, 'feat c2, after the PR merged');
    git(repo, 'checkout', '-q', 'main');
    git(repo, 'merge', '--squash', '-q', mergedHead);
    const squash = commitAll(repo, 'squash of the merged head only');

    const receipt = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork,
      target: 'main',
      gh: ghMergedPr({ number: 42, headRefOid: mergedHead, squash }),
      git: gitRunner(repo),
    });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.UNKNOWN, receipt.reason);
    assert.equal(reasonCode(receipt), RECEIPT_REASONS.TREE_DIFFERS);
    assert.match(receipt.reason, new RegExp(RECEIPT_REASONS.PR_HEAD_MISMATCH));
    // The head mismatch must send the computation down the current-target test, not the squash
    // test: with the mismatch check removed the reason reads `vs squash …` instead (mutation
    // probe, 2026-09-10), so this is what makes the case discriminating rather than incidental.
    assert.match(receipt.reason, /vs main /);
    assert.equal(receipt.squash, null, 'a PR that merged a different commit contributes no verified squash');
    assert.equal(receipt.pr, 42, 'the rejected PR is still recorded, so the reason is checkable');
  });

  await check('exp4: a custom merge driver that keeps its own side cannot produce a false LANDED', async () => {
    const { repo } = await makeRepo('exp4');
    write(repo, 'a.txt', 'l1\nl2\nl3\n');
    write(repo, '.gitattributes', 'a.txt merge=keepours\n');
    const fork = commitAll(repo, 'base');
    git(repo, 'config', 'merge.keepours.name', 'keep our side');
    // `true` exits 0 leaving %A (the target side) untouched: the driver claims a clean merge and
    // silently drops the branch's content.
    git(repo, 'config', 'merge.keepours.driver', 'true');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'a.txt', 'l1\nl2\nl3\nUNIQUE-BRANCH-CONTENT-NEVER-LANDED\n');
    const head = commitAll(repo, 'feat c1');
    git(repo, 'checkout', '-q', 'main');
    write(repo, 'a.txt', 'l1\nl2\nl3\nMAIN-ONLY\n');
    const mainNow = commitAll(repo, 'main edit');

    // The falsifier: an UNCONTROLLED merge-tree in this repository reports the target tree, i.e.
    // "the branch adds nothing" — a false positive on content that never landed.
    const uncontrolled = gitRun(repo, ['merge-tree', '--write-tree', `--merge-base=${fork}`, mainNow, head]);
    const mainTree = git(repo, 'rev-parse', `${mainNow}^{tree}`);
    assert.equal(uncontrolled.status, 0, 'the driver makes the uncontrolled merge succeed');
    assert.equal(uncontrolled.stdout.split('\n')[0].trim(), mainTree,
      'without driver control the simulation would falsely equal the target tree');

    // OBSERVED on git 2.53.0.windows.3: `-c merge.default=` alone does NOT neutralize a driver
    // reached through a `.gitattributes` `merge=` line (it only neutralizes `merge.default`), so
    // the forced config is not sufficient on its own and the receipt refuses instead.
    const forced = simulateCoverage({ repoRoot: repo, base: fork, target: mainNow, head, git: gitRunner(repo) });
    assert.equal(forced.equal, true, 'documented: the forced merge config does not disarm an attribute-selected driver');

    const receipt = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork,
      target: 'main',
      gh: ghMergedPr({ number: 9, headRefOid: head, squash: mainNow }),
      git: gitRunner(repo),
    });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.UNKNOWN, receipt.reason);
    assert.equal(reasonCode(receipt), RECEIPT_REASONS.CUSTOM_MERGE_DRIVER);
    assert.match(receipt.reason, /keepours/);

    // And the safe verdict equals the driver-free outcome: with the driver removed the same
    // repository answers UNKNOWN as well (the branch's unique content genuinely conflicts).
    git(repo, 'config', '--unset', 'merge.keepours.driver');
    const driverFree = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork,
      target: 'main',
      gh: ghMergedPr({ number: 9, headRefOid: head, squash: mainNow }),
      git: gitRunner(repo),
    });
    assert.equal(driverFree.verdict, RECEIPT_VERDICTS.UNKNOWN, driverFree.reason);
    assert.notEqual(reasonCode(driverFree), RECEIPT_REASONS.CUSTOM_MERGE_DRIVER);
  });

  await check('exp5: content inherited from a parent branch and never landed keeps the head UNKNOWN', async () => {
    const { repo } = await makeRepo('exp5');
    write(repo, 'a.txt', 'base\n');
    commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'parent');
    write(repo, 'inherited.txt', 'parent work that never landed\n');
    const fork = commitAll(repo, 'parent work');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'own.txt', 'the session own change\n');
    const head = commitAll(repo, 'own work');
    git(repo, 'checkout', '-q', 'main');
    write(repo, 'own.txt', 'the session own change\n');
    const squash = commitAll(repo, 'squash of the own change only');

    const receipt = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork,
      target: 'main',
      gh: ghMergedPr({ number: 11, headRefOid: head, squash }),
      git: gitRunner(repo),
    });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.UNKNOWN, receipt.reason);
    assert.equal(reasonCode(receipt), RECEIPT_REASONS.TREE_DIFFERS);
    // The recorded fork is NOT an ancestor of the squash (it carries the unlanded parent commit),
    // so the base falls back to the merge base with the target — which is exactly what exposes the
    // inherited file. Using the fork as the base would have hidden it.
    assert.notEqual(receipt.base, fork);
    assert.equal(receipt.base, git(repo, 'merge-base', 'main', head));
    assert.equal(receipt.squash, squash, 'the accepted squash is recorded even though it did not cover the head');
  });

  await check('exp6: an unlanded branch with no merged PR is UNKNOWN, never REDUNDANT_NOW', async () => {
    const { repo } = await makeRepo('exp6');
    write(repo, 'a.txt', 'base\n');
    commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'unlanded.txt', 'work that no PR ever merged\n');
    const head = commitAll(repo, 'unlanded work');
    git(repo, 'checkout', '-q', 'main');

    const receipt = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      target: 'main',
      gh: ghNoPr,
      git: gitRunner(repo),
    });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.UNKNOWN, receipt.reason);
    assert.equal(reasonCode(receipt), RECEIPT_REASONS.TREE_DIFFERS);
    assert.match(receipt.reason, new RegExp(RECEIPT_REASONS.NO_MERGED_PR));
    assert.equal(receipt.pr, null);
    assert.equal(receipt.squash, null);
  });

  await check('a conflict reason names the first three conflicting paths and stops there', async () => {
    const { repo } = await makeRepo('conflictcap');
    for (let i = 0; i < 5; i += 1) write(repo, `f${i}.txt`, 'l1\nl2\nl3\n');
    const fork = commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    for (let i = 0; i < 5; i += 1) write(repo, `f${i}.txt`, 'l1\nl2\nBRANCH\n');
    const head = commitAll(repo, 'branch edits');
    git(repo, 'checkout', '-q', 'main');
    for (let i = 0; i < 5; i += 1) write(repo, `f${i}.txt`, 'l1\nl2\nMAIN\n');
    commitAll(repo, 'main edits');

    const sim = simulateCoverage({ repoRoot: repo, base: fork, target: 'main', head, git: gitRunner(repo) });
    assert.equal(sim.ok, false);
    assert.equal(sim.equal, false);
    assert.equal(sim.conflicts.length, 5, 'the simulation itself reports every conflict');

    const receipt = computeReceipt({ repoRoot: repo, head, branch: 'feat', anchor: fork, target: 'main', gh: ghNoPr, git: gitRunner(repo) });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.UNKNOWN, receipt.reason);
    assert.equal(reasonCode(receipt), RECEIPT_REASONS.CONFLICT);
    assert.equal(receipt.reason.split('CONFLICT').length - 1, 3, 'the reason is bounded to the first three');
    assert.match(receipt.reason, /f0\.txt/);
    assert.match(receipt.reason, /f2\.txt/);
    assert.ok(!receipt.reason.includes('f3.txt'), 'the fourth conflict is not carried into the reason');
  });

  await check('any git failure degrades to UNKNOWN instead of throwing', async () => {
    const { repo } = await makeRepo('gitfail');
    write(repo, 'a.txt', 'base\n');
    commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'a.txt', 'base\nfeat\n');
    const head = commitAll(repo, 'feat');
    git(repo, 'checkout', '-q', 'main');

    const broken = (args) => {
      if (args.includes('merge-tree')) return { status: 128, stdout: '', stderr: 'fatal: simulated merge-tree failure' };
      return gitRun(repo, args);
    };
    const receipt = computeReceipt({ repoRoot: repo, head, branch: 'feat', target: 'main', gh: ghNoPr, git: broken });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.UNKNOWN, receipt.reason);
    assert.equal(reasonCode(receipt), RECEIPT_REASONS.GIT_FAILED);
    assert.match(receipt.reason, /simulated merge-tree failure/);

    const throwing = () => { throw new Error('git is unavailable'); };
    const unresolved = computeReceipt({ repoRoot: repo, head, branch: 'feat', target: 'main', gh: ghNoPr, git: throwing });
    assert.equal(unresolved.verdict, RECEIPT_VERDICTS.UNKNOWN, unresolved.reason);
    assert.equal(reasonCode(unresolved), RECEIPT_REASONS.HEAD_UNRESOLVABLE);

    const noTarget = computeReceipt({ repoRoot: repo, head, branch: 'feat', target: 'origin/does-not-exist', gh: ghNoPr, git: gitRunner(repo) });
    assert.equal(noTarget.verdict, RECEIPT_VERDICTS.UNKNOWN, noTarget.reason);
    assert.equal(reasonCode(noTarget), RECEIPT_REASONS.NO_BASE);
  });

  await check('lookupMergedPr returns null for malformed JSON, a failing gh, a throwing gh, and an empty list', async () => {
    const { repo } = await makeRepo('ghstub');
    write(repo, 'a.txt', 'base\n');
    commitAll(repo, 'base');
    const malformed = () => ({ status: 0, stdout: '[{"number": 1, "headRefOid"', stderr: '' });
    assert.equal(lookupMergedPr({ repoRoot: repo, branch: 'feat', gh: malformed }), null);
    assert.equal(lookupMergedPr({ repoRoot: repo, branch: 'feat', gh: () => ({ status: 1, stdout: '', stderr: 'no auth' }) }), null);
    assert.equal(lookupMergedPr({ repoRoot: repo, branch: 'feat', gh: () => { throw new Error('gh missing'); } }), null);
    assert.equal(lookupMergedPr({ repoRoot: repo, branch: 'feat', gh: ghNoPr }), null);
    assert.equal(lookupMergedPr({ repoRoot: repo, branch: '', gh: () => { throw new Error('must not be called'); } }), null);
    // A row without headRefOid is not usable evidence either — it cannot be matched to a head.
    const headless = () => ({ status: 0, stdout: JSON.stringify([{ number: 5, mergeCommit: { oid: 'abc' } }]), stderr: '' });
    assert.equal(lookupMergedPr({ repoRoot: repo, branch: 'feat', gh: headless }), null);
    const good = () => ({ status: 0, stdout: JSON.stringify([{ number: 5, headRefOid: 'a'.repeat(40), mergeCommit: { oid: 'b'.repeat(40) } }]), stderr: '' });
    assert.deepEqual(lookupMergedPr({ repoRoot: repo, branch: 'feat', gh: good }), {
      number: 5,
      headRefOid: 'a'.repeat(40),
      squash: 'b'.repeat(40),
    });
  });

  await check('receipts round-trip as NDJSON, skip torn lines, and expose the documented constants', async () => {
    const { root } = await makeRepo('ndjson');
    const file = path.join(root, 'nested', 'landing-receipts.ndjson');
    assert.deepEqual(loadReceipts(file), [], 'a missing cache is an empty history');
    const one = appendReceipt(file, { head: 'a'.repeat(40), verdict: RECEIPT_VERDICTS.LANDED, ts: '2026-09-10T00:00:00.000Z' });
    appendReceipt(file, { head: 'b'.repeat(40), verdict: RECEIPT_VERDICTS.UNKNOWN, ts: '2026-09-10T01:00:00.000Z' });
    fs.appendFileSync(file, '{"head": "torn\n', 'utf8');
    const loaded = loadReceipts(file);
    assert.equal(loaded.length, 2, 'the torn last line is skipped, not fatal');
    assert.deepEqual(loaded[0], one);
    assert.equal(fs.readFileSync(file, 'utf8').split('\n')[0], JSON.stringify(one), 'one receipt per line');
    assert.equal(DEFAULT_RECEIPTS_FILE, 'tmp/agent-telemetry/landing-receipts.ndjson');
    assert.equal(DEFAULT_TARGET, 'origin/main');
    assert.deepEqual(Object.keys(RECEIPT_VERDICTS).sort(), ['LANDED', 'REDUNDANT_NOW', 'UNKNOWN']);
  });

  await check('findValidReceipt honours reachability: valid while the squash is in the target, null once it is not', async () => {
    const { root, repo } = await makeRepo('validity');
    write(repo, 'a.txt', 'base\n');
    const base = commitAll(repo, 'base');
    git(repo, 'checkout', '-q', '-b', 'feat');
    write(repo, 'a.txt', 'base\nfeat\n');
    const head = commitAll(repo, 'feat');
    git(repo, 'checkout', '-q', 'main');
    git(repo, 'merge', '--squash', '-q', 'feat');
    const squash = commitAll(repo, 'squash of feat');

    const file = path.join(root, 'landing-receipts.ndjson');
    const receipt = computeReceipt({
      repoRoot: repo,
      head,
      branch: 'feat',
      fork: base,
      target: 'main',
      gh: ghMergedPr({ number: 3, headRefOid: head, squash }),
      git: gitRunner(repo),
    });
    assert.equal(receipt.verdict, RECEIPT_VERDICTS.LANDED, receipt.reason);
    appendReceipt(file, receipt);
    appendReceipt(file, { ...receipt, verdict: RECEIPT_VERDICTS.UNKNOWN, ts: '2099-01-01T00:00:00.000Z' });

    const found = findValidReceipt({ file, head, repoRoot: repo, target: 'main', git: gitRunner(repo) });
    assert.ok(found, 'the LANDED receipt is valid while its squash is reachable from the target');
    assert.equal(found.verdict, RECEIPT_VERDICTS.LANDED, 'a newer UNKNOWN never wins');
    assert.equal(found.squash, squash);
    assert.equal(findValidReceipt({ file, head: 'c'.repeat(40), repoRoot: repo, target: 'main', git: gitRunner(repo) }), null);

    // The target loses the squash (force-push, revert-by-reset, a re-cut branch): the receipt is
    // no longer evidence about the target, so it must stop being returned.
    git(repo, 'reset', '-q', '--hard', base);
    assert.equal(findValidReceipt({ file, head, repoRoot: repo, target: 'main', git: gitRunner(repo) }), null);
    assert.equal(findValidReceipt({ file, head, repoRoot: repo, target: 'origin/main', git: gitRunner(repo) }), null,
      'an unresolvable target proves nothing either');

    // A REDUNDANT_NOW receipt is validated against the target SHA it was measured at.
    const redundantFile = path.join(root, 'redundant.ndjson');
    appendReceipt(redundantFile, {
      head,
      branch: 'feat',
      base,
      squash: null,
      pr: null,
      target: squash,
      verdict: RECEIPT_VERDICTS.REDUNDANT_NOW,
      reason: 'redundant-now: fixture',
      ts: '2026-09-10T00:00:00.000Z',
    });
    assert.equal(findValidReceipt({ file: redundantFile, head, repoRoot: repo, target: 'main', git: gitRunner(repo) }), null,
      'the target it was measured against is no longer an ancestor of the target');
  });
} finally {
  for (const fixture of fixtures.reverse()) {
    await fsp.rm(fixture.root, { recursive: true, force: true }).catch(() => {});
  }
}

if (failures.length) {
  console.error(`952-landing-receipt.test: ${failures.length} FAILED / ${passed} passed`);
  for (const failure of failures) console.error(`  x ${failure}`);
  process.exit(1);
}
console.log(`952-landing-receipt.test: ${passed} passed`);
