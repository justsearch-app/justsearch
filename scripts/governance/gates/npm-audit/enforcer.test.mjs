import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { enforceNpmAudit } from './enforcer.mjs';
import { REQUIRED_ADVISORY_TARGETS, sha256, GITHUB_ADVISORY_REPORT_SCHEMA } from '../../../ci/lib/github-advisory-report.mjs';

const baselinePath = 'scripts/ci/github-advisory-baseline.v1.json';
const reportPath = 'tmp/github-advisory-report.json';
const gate = {
  id: 'npm-audit',
  baseline: { path: baselinePath },
  changesetsDir: 'gates/npm-audit/.changesets',
  config: { reportPath, trackedSeverities: ['high', 'critical'] },
};
const rootLockfile = '{"lockfileVersion":3,"packages":{"node_modules/example":{"version":"1.0.0"}}}\n';
const uiLockfile = '{"lockfileVersion":3,"packages":{"node_modules/ui-example":{"version":"1.0.0"}}}\n';
const lockfiles = Object.fromEntries(REQUIRED_ADVISORY_TARGETS.map((target) => [
  target.lockfile,
  target.targetId === 'root' ? rootLockfile : target.targetId === 'ui-web' ? uiLockfile : '{"lockfileVersion":3,"packages":{"node_modules/example":{"version":"1.0.0"}}}\n',
]));
const advisory = (severity = 'high') => ({
  ghsa_id: 'GHSA-35JH-R3H4-6JHM', severity,
  affected: [{ package: 'example', version: '1.0.0' }],
  html_url: 'https://github.com/advisories/GHSA-35jh-r3h4-6jhm',
});
const report = (rootAdvisories = [], overrides = {}) => ({
  schema: GITHUB_ADVISORY_REPORT_SCHEMA,
  source: { provider: 'github-global-security-advisories', api_version: '2026-03-10' },
  targets: REQUIRED_ADVISORY_TARGETS.map((target) => ({
    target_id: target.targetId,
    lockfile: target.lockfile,
    available: true,
    lockfile_sha256: sha256(lockfiles[target.lockfile]),
    package_versions: 1,
    advisories: target.targetId === 'root' ? rootAdvisories : [],
    ...(target.targetId === 'root' ? overrides : {}),
  })),
});
const baseline = (rootAdvisories = []) => ({
  schema: 'github-advisory-baseline.v1',
  targets: Object.fromEntries(REQUIRED_ADVISORY_TARGETS.map((target) => [
    target.targetId,
    { advisories: target.targetId === 'root' ? rootAdvisories : [] },
  ])),
});

function put(root, relative, value) {
  const file = path.resolve(root, relative);
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`);
}

async function runFixture({ currentReport, liveBaseline, priorBaseline = liveBaseline, changeset, rebalance = false }) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'justsearch-advisory-gate-'));
  try {
    put(root, reportPath, currentReport);
    put(root, baselinePath, liveBaseline);
    put(root, `_baseline/${baselinePath}`, priorBaseline);
    for (const [lockfile, text] of Object.entries(lockfiles)) put(root, lockfile, text);
    if (changeset) put(root, 'gates/npm-audit/.changesets/fixture.md', changeset);
    const result = await enforceNpmAudit({
      repoRoot: root, gate, fixtureMode: true, fixtureRoot: root, baselineRef: 'fixture', rebalance,
    });
    result.writtenBaseline = JSON.parse(readFileSync(path.resolve(root, baselinePath), 'utf8'));
    return result;
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const declaration = '---\nclassification: declared-regression\ntempdoc: 921\n---\nReviewed fixture advisory.\n';

const within = await runFixture({ currentReport: report([advisory()]), liveBaseline: baseline([advisory()]) });
assert.equal(within.verdict, 'pass');

const silent = await runFixture({ currentReport: report([advisory()]), liveBaseline: baseline([]) });
assert.equal(silent.verdict, 'fail');
assert.ok(silent.findings.some((finding) => finding.ruleId === 'npm-audit/silent-regression'));

const declaredUnpinned = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([]), changeset: declaration,
});
assert.equal(declaredUnpinned.verdict, 'fail');
assert.ok(declaredUnpinned.findings.some((finding) => finding.ruleId === 'npm-audit/declared-regression-without-repin'));

const declaredPinned = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([advisory()]),
  priorBaseline: baseline([]), changeset: declaration,
});
assert.equal(declaredPinned.verdict, 'pass');
assert.ok(declaredPinned.findings.some((finding) => finding.ruleId === 'npm-audit/declared-baseline-shift'));

const silentPin = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([advisory()]), priorBaseline: baseline([]),
});
assert.equal(silentPin.verdict, 'fail');
assert.ok(silentPin.findings.some((finding) => finding.ruleId === 'npm-audit/silent-baseline-shift'));

const escalated = await runFixture({
  currentReport: report([advisory('critical')]), liveBaseline: baseline([advisory('high')]),
});
assert.equal(escalated.verdict, 'fail');
assert.match(escalated.findings[0].message, /severity increased high → critical/);

const unavailable = await runFixture({
  currentReport: report([], { available: false, error: 'deadline exceeded' }), liveBaseline: baseline([]),
});
assert.equal(unavailable.verdict, 'fail');
assert.ok(unavailable.findings.some((finding) => finding.ruleId === 'npm-audit/report-unavailable'));

const missingTargetReport = report([]);
missingTargetReport.targets = missingTargetReport.targets.filter((target) => target.target_id !== 'wire-contract');
const missingTarget = await runFixture({ currentReport: missingTargetReport, liveBaseline: baseline([]) });
assert.equal(missingTarget.verdict, 'fail');
assert.ok(missingTarget.findings.some((finding) => /wire-contract advisory evidence is unavailable/.test(finding.message)));

const stale = await runFixture({
  currentReport: report([], { lockfile_sha256: 'c'.repeat(64) }), liveBaseline: baseline([]),
});
assert.equal(stale.verdict, 'fail');
assert.ok(stale.findings.some((finding) => /digest does not match/.test(finding.message)));

const rebalanceCannotAccept = await runFixture({
  currentReport: report([advisory()]), liveBaseline: baseline([]), rebalance: true,
});
assert.equal(rebalanceCannotAccept.verdict, 'fail');
assert.deepEqual(rebalanceCannotAccept.writtenBaseline.targets.root.advisories, []);

const rebalanceRemovesResolved = await runFixture({
  currentReport: report([]), liveBaseline: baseline([advisory()]), rebalance: true,
});
assert.equal(rebalanceRemovesResolved.verdict, 'pass');
assert.deepEqual(rebalanceRemovesResolved.writtenBaseline.targets.root.advisories, []);

console.log('npm-audit identity enforcer tests: PASS');

// Real git base reads, including all five lockfiles and nested/transitive versions.
async function prFixture({ added = false, newPackage = false, secondVersion = false, accepted = false, unreadable = false, unavailable = false, missingAttribution = false } = {}, target = REQUIRED_ADVISORY_TARGETS[0]) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'justsearch-advisory-pr-'));
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  try {
    for (const [file, text] of Object.entries(lockfiles)) put(root, file, text);
    git('init', '--quiet'); git('add', '.');
    git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit', '--quiet', '-m', 'base lockfiles');
    const base = git('rev-parse', 'HEAD');
    const packageName = newPackage ? 'introduced' : target.targetId === 'ui-web' ? 'ui-example' : 'example';
    const finding = { ...advisory(), affected: [{ package: packageName, version: secondVersion || added ? '2.0.0' : '1.0.0' }] };
    const currentReport = report();
    const row = currentReport.targets.find((row) => row.target_id === target.targetId);
    const packages = JSON.parse(lockfiles[target.lockfile]).packages;
    if (added || newPackage) packages[`node_modules/${packageName}`] = { version: finding.affected[0].version };
    if (secondVersion) packages[`node_modules/outer/node_modules/${packageName}`] = { version: '2.0.0' };
    const text = JSON.stringify({ lockfileVersion: 3, packages });
    put(root, target.lockfile, text);
    row.lockfile_sha256 = sha256(text);
    row.advisories = [finding];
    if (unavailable) { row.available = false; row.error = 'provider HTTP 503'; }
    if (missingAttribution) delete finding.affected;
    put(root, reportPath, currentReport);
    const pin = baseline();
    if (accepted) pin.targets[target.targetId].advisories = [advisory()];
    put(root, baselinePath, pin);
    return await enforceNpmAudit({ repoRoot: root, gate, eventName: 'pull_request', prBaseSha: unreadable ? 'f'.repeat(40) : base, baselineRef: base });
  } finally { rmSync(root, { recursive: true, force: true }); }
}
for (const target of REQUIRED_ADVISORY_TARGETS) {
  const baseVersion = await prFixture({}, target);
  assert.equal(baseVersion.verdict, 'pass', `${target.lockfile}: ${JSON.stringify(baseVersion.findings)}`);
  const introduced = await prFixture({ newPackage: true }, target);
  assert.equal(introduced.verdict, 'fail', `${target.lockfile}: a newly introduced vulnerable package must block`);
  assert.ok(introduced.findings.some((row) => row.ruleId === 'npm-audit/silent-regression'));
  const added = await prFixture({ added: true }, target);
  assert.equal(added.verdict, 'fail', target.lockfile);
  assert.ok(added.findings.some((row) => row.ruleId === 'npm-audit/silent-regression'));
  const second = await prFixture({ secondVersion: true, accepted: true }, target);
  assert.equal(second.verdict, 'fail', `${target.lockfile}: a nested second vulnerable version of an accepted advisory must block`);
}
const noBase = await prFixture({ unreadable: true });
assert.equal(noBase.verdict, 'fail');
assert.match(noBase.findings[0].message, /PR base .*cannot be read/);
const providerFailure = await prFixture({ unavailable: true });
assert.equal(providerFailure.verdict, 'fail');
assert.match(providerFailure.findings[0].message, /provider HTTP 503/);
const noAttribution = await prFixture({ missingAttribution: true });
assert.equal(noAttribution.verdict, 'fail');
assert.match(noAttribution.findings[0].message, /attribution/);
console.log('npm-audit PR base/package/version tests: PASS');
