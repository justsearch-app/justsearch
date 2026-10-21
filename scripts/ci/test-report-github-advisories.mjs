import assert from 'node:assert/strict';

import {
  buildAffectsBatches,
  packageSpecsFromLockfileText,
  queryGitHubAdvisories,
  REQUIRED_ADVISORY_TARGETS,
  unavailableAdvisoryTargetReason,
} from './lib/github-advisory-report.mjs';
import { collectAdvisoryTarget } from './report-github-advisories.mjs';

const lockfile = JSON.stringify({
  lockfileVersion: 3,
  packages: {
    '': { name: 'fixture', version: '1.0.0' },
    'node_modules/lodash': { version: '4.17.20' },
    'node_modules/outer/node_modules/@scope/inner': { version: '2.0.0' },
  },
});
assert.deepEqual(packageSpecsFromLockfileText(lockfile), ['@scope/inner@2.0.0', 'lodash@4.17.20']);
assert.throws(() => packageSpecsFromLockfileText('{}'), /package-lock schema/);
assert.deepEqual(
  REQUIRED_ADVISORY_TARGETS.map(({ targetId, lockfile: path }) => [targetId, path]),
  [
    ['root', 'package-lock.json'],
    ['ui-web', 'modules/ui-web/package-lock.json'],
    ['shell', 'modules/shell/package-lock.json'],
    ['runtime-client', 'packages/runtime-client/package-lock.json'],
    ['wire-contract', 'scripts/wire-contract/package-lock.json'],
  ],
  'every production npm lockfile must have one explicit advisory target',
);

const batches = buildAffectsBatches(['a@1.0.0', 'b@1.0.0', 'c@1.0.0'], { maxSpecs: 2 });
assert.deepEqual(batches, [['a@1.0.0', 'b@1.0.0'], ['c@1.0.0']]);
for (const specs of [
  ['a@1.0.0', 'a@2.0.0', 'b@1.0.0', 'b@2.0.0'],
  ['@scope/a@1.0.0', 'b@1.0.0', '@scope/a@2.0.0', 'b@2.0.0'],
]) {
  const batches = buildAffectsBatches(specs);
  assert.deepEqual(batches.flat(), specs);
  for (const batch of batches) {
    const names = batch.map(spec => spec.slice(0, spec.lastIndexOf('@')));
    assert.equal(new Set(names).size, batch.length, 'different versions of a package must be in separate requests');
  }
}

const advisory = (id, severity = 'high') => ({
  ghsa_id: id,
  severity,
  html_url: `https://github.com/advisories/${id}`,
  vulnerabilities: [{ package: { ecosystem: 'npm', name: 'lodash' }, vulnerable_version_range: '< 4.17.21' }],
  affected: [{ package: 'lodash', version: '4.17.20' }],
});
let calls = 0;
const fetched = await queryGitHubAdvisories(['lodash@4.17.20'], {
  fetchImpl: async (url, options) => {
    calls += 1;
    assert.equal(options.method, 'GET');
    assert.match(String(url), /ecosystem=npm/);
    assert.equal(options.headers.Authorization, 'Bearer test-token');
    return {
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => calls === 1
        ? Array.from({ length: 100 }, () => advisory('GHSA-35JH-R3H4-6JHM'))
        : [advisory('GHSA-35JH-R3H4-6JHM'), advisory('GHSA-29MW-WPGM-HMR9', 'moderate')],
    };
  },
  token: 'test-token',
  retries: 0,
  timeoutSignalFactory: () => undefined,
});
assert.equal(calls, 2, 'a full page must trigger pagination');
assert.deepEqual(fetched.map((row) => row.ghsa_id), ['GHSA-29MW-WPGM-HMR9', 'GHSA-35JH-R3H4-6JHM']);

assert.deepEqual(fetched[0].affected, [{ package: 'lodash', version: '4.17.20' }]);
const attributed = await queryGitHubAdvisories(['lodash@4.17.20', 'lodash@4.17.19', 'unrelated@1.0.0'], {
  fetchImpl: async () => ({ ok: true, json: async () => [advisory('GHSA-35JH-R3H4-6JHM')] }),
  retries: 0,
  timeoutSignalFactory: () => undefined,
});
assert.deepEqual(attributed[0].affected, [
  { package: 'lodash', version: '4.17.19' }, { package: 'lodash', version: '4.17.20' },
], 'retain each queried version of the matching package, never attribute unrelated packages');
const exact = await queryGitHubAdvisories(['lodash@4.17.20', 'lodash@4.17.21', 'unrelated@1.0.0'], {
  fetchImpl: async (url) => ({ ok: true, json: async () =>
    url.searchParams.get('affects').split(',').includes('lodash@4.17.20')
      ? [advisory('GHSA-35JH-R3H4-6JHM')] : [] }),
  retries: 0, timeoutSignalFactory: () => undefined,
});
assert.deepEqual(exact[0].affected, [{ package: 'lodash', version: '4.17.20' }],
  'a safe version beside a vulnerable version is never attributed its advisory');
// A multi-package advisory can be returned through just one affected package.
// Each package's OWN range decides attribution, including multiple entries for a name.
const multiPackage = {
  ...advisory('GHSA-35JH-R3H4-6JHM'),
  vulnerabilities: [
    { package: { ecosystem: 'npm', name: 'pkg-a' }, vulnerable_version_range: '>= 1.0.0, < 2.0.0' },
    { package: { ecosystem: 'npm', name: 'pkg-b' }, vulnerable_version_range: '< 2.0.0' },
    { package: { ecosystem: 'npm', name: 'pkg-b' }, vulnerable_version_range: '>= 4.0.0, < 5.0.0' },
  ],
};
const multiple = await queryGitHubAdvisories(['pkg-a@1.0.0', 'pkg-b@3.0.0', 'pkg-b@4.1.0'], {
  fetchImpl: async () => ({ ok: true, json: async () => [multiPackage] }),
  retries: 0, timeoutSignalFactory: () => undefined,
});
assert.deepEqual(multiple[0].affected, [
  { package: 'pkg-a', version: '1.0.0' }, { package: 'pkg-b', version: '4.1.0' },
], 'a clean version of the second named package must not inherit the first package match');
for (const specs of [['pkg-b@1.0.0-next.29'], ['pkg-a@1.0.0', 'pkg-b@1.0.0-next.29']]) {
  const prerelease = await queryGitHubAdvisories(specs, {
    fetchImpl: async () => ({ ok: true, json: async () => [{ ...multiPackage,
      vulnerabilities: [multiPackage.vulnerabilities[0],
        { package: { ecosystem: 'npm', name: 'pkg-b' }, vulnerable_version_range: '< 1.0.0' }],
    }] }), retries: 0, timeoutSignalFactory: () => undefined,
  });
  assert.ok(prerelease[0].affected.some(entry => entry.package === 'pkg-b' && entry.version === '1.0.0-next.29'),
    'a vulnerable prerelease must be attributed even beside another matching package');
}
for (const [version, range, reason] of [
  ['not-a-version', '< 1.0.0', 'package version'],
  ['1.0.0-next.29', 'not a range', 'vulnerable range'],
]) {
  await assert.rejects(queryGitHubAdvisories(['pkg-a@1.0.0', `pkg-b@${version}`], {
    fetchImpl: async () => ({ ok: true, json: async () => [{ ...multiPackage,
      vulnerabilities: [multiPackage.vulnerabilities[0],
        { package: { ecosystem: 'npm', name: 'pkg-b' }, vulnerable_version_range: range }],
    }] }), retries: 0, timeoutSignalFactory: () => undefined,
  }), new RegExp(`unreadable ${reason} for pkg-b@${version.replaceAll('.', '\\.')}`),
  'an undecidable second package must fail the report even when the first package matches');
}
for (const range of [undefined, '', 'not a range']) {
  await assert.rejects(queryGitHubAdvisories(['pkg-a@1.0.0'], {
    fetchImpl: async () => ({ ok: true, json: async () => [{ ...multiPackage,
      vulnerabilities: [{ package: { ecosystem: 'npm', name: 'pkg-a' }, vulnerable_version_range: range }],
    }] }), retries: 0, timeoutSignalFactory: () => undefined,
  }), /unreadable vulnerable range for pkg-a@1.0.0/);
}
await assert.rejects(queryGitHubAdvisories(['unrelated@1.0.0'], {
  fetchImpl: async () => ({ ok: true, json: async () => [advisory('GHSA-35JH-R3H4-6JHM')] }),
  retries: 0, timeoutSignalFactory: () => undefined,
}), /matches no queried package/);
assert.match(unavailableAdvisoryTargetReason({
  available: true, lockfile_sha256: 'a'.repeat(64), package_versions: 1,
  advisories: [{ ...advisory('GHSA-35JH-R3H4-6JHM'), affected: undefined }],
}), /no package\/version attribution/);

let retryCalls = 0;
const retried = await queryGitHubAdvisories(['lodash@4.17.20'], {
  fetchImpl: async () => {
    retryCalls += 1;
    if (retryCalls === 1) throw new Error('socket reset');
    return { ok: true, status: 200, headers: { get: () => null }, json: async () => [] };
  },
  retries: 1,
  sleep: async () => {},
  timeoutSignalFactory: () => undefined,
});
assert.deepEqual(retried, []);
assert.equal(retryCalls, 2);

let forbiddenCalls = 0;
await assert.rejects(
  queryGitHubAdvisories(['lodash@4.17.20'], {
    fetchImpl: async () => {
      forbiddenCalls += 1;
      return { ok: false, status: 403, headers: { get: () => null } };
    },
    retries: 2,
    sleep: async () => {},
    timeoutSignalFactory: () => undefined,
  }),
  /HTTP 403/,
);
assert.equal(forbiddenCalls, 1, 'non-transient HTTP failures must not be retried');

const timeoutSignals = [];
await assert.rejects(
  queryGitHubAdvisories(['lodash@4.17.20'], {
    fetchImpl: async (_url, options) => {
      timeoutSignals.push(options.signal);
      throw new DOMException('deadline exceeded', 'TimeoutError');
    },
    timeoutMs: 1234,
    retries: 1,
    sleep: async () => {},
    timeoutSignalFactory: (ms) => ({ deadline_ms: ms }),
  }),
  /deadline exceeded/,
);
assert.deepEqual(timeoutSignals, [{ deadline_ms: 1234 }, { deadline_ms: 1234 }]);

await assert.rejects(
  queryGitHubAdvisories(['lodash@4.17.20'], {
    fetchImpl: async () => ({ ok: true, status: 200, headers: { get: () => null }, json: async () => ({}) }),
    retries: 0,
    timeoutSignalFactory: () => undefined,
  }),
  /JSON array/,
);

const goodTarget = {
  available: true,
  lockfile_sha256: 'a'.repeat(64),
  package_versions: 2,
  advisories: [advisory('GHSA-35JH-R3H4-6JHM')],
};
assert.equal(unavailableAdvisoryTargetReason(goodTarget), null);
assert.match(unavailableAdvisoryTargetReason({ ...goodTarget, available: false, error: 'timeout' }), /timeout/);
assert.match(unavailableAdvisoryTargetReason({ ...goodTarget, advisories: [advisory('bad')] }), /invalid ghsa_id/);

const unavailable = await collectAdvisoryTarget({
  repoRoot: process.cwd(),
  target: { targetId: 'missing', lockfile: 'not-present.lock' },
  query: async () => { throw new Error('must not query'); },
});
assert.equal(unavailable.available, false);
assert.match(unavailable.error, /ENOENT/);

console.log('test-report-github-advisories: PASS');
