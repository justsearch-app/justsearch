/**
 * Tests for the release-sequence derivation (scripts/ci/derive-release-sequence.mjs).
 *
 * Fully offline: the core `deriveReleaseSequence` takes an injected `fetchAsset`, so the
 * GitHub API is never touched. The CLI's argument handling is exercised by spawning the
 * script (no network needed for the failure paths).
 *
 * Run: `node scripts/ci/derive-release-sequence.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

import {
  DESCRIPTOR_ASSET_NAME,
  RELEASE_SEQUENCE_FLOOR,
  deriveReleaseSequence,
} from './derive-release-sequence.mjs';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'derive-release-sequence.mjs');

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

/** A release carrying a descriptor asset whose bytes are keyed by asset url. */
function release(tag, { sequence, draft = false, prerelease = false, withAsset = true } = {}) {
  return {
    tag_name: tag,
    draft,
    prerelease,
    assets: withAsset
      ? [
          { name: 'JustSearch_x64-setup.exe', url: `https://api.example/assets/${tag}-exe` },
          { name: DESCRIPTOR_ASSET_NAME, url: `https://api.example/assets/${tag}`, _sequence: sequence },
        ]
      : [{ name: 'JustSearch_x64-setup.exe', url: `https://api.example/assets/${tag}-exe` }],
  };
}

/** Serves the descriptor body encoded in the fixture asset, or a caller-supplied override. */
function fetcher(override) {
  return async (asset, rel) => {
    if (override) return override(asset, rel);
    return JSON.stringify({ schemaVersion: 1, version: '0.0.0', sequence: asset._sequence });
  };
}

async function expectThrow(label, promise, pattern) {
  try {
    await promise;
    failures.push(`${label}: expected a throw, got success`);
  } catch (error) {
    ok(label, pattern.test(error.message));
  }
}

// 1. Max across releases wins, not the first or the newest-listed.
{
  const result = await deriveReleaseSequence({
    releases: [release('v0.4.0', { sequence: 57 }), release('v0.9.0', { sequence: 112 }), release('v0.5.0', { sequence: 88 })],
    fetchAsset: fetcher(),
  });
  ok('max across releases + 1', result.sequence === 113);
  ok('provenance names the winning release', /v0\.9\.0/.test(result.provenance) && /112/.test(result.provenance));
  ok('observed lists every descriptor', result.observed.length === 3);
}

// 2. A release with no descriptor asset is skipped with a warning, not an error.
{
  const result = await deriveReleaseSequence({
    releases: [release('v0.1.0', { withAsset: false }), release('v0.2.0', { sequence: 40 })],
    fetchAsset: fetcher(),
  });
  ok('missing-asset release skipped', result.sequence === 41);
  ok('missing-asset warns', result.warnings.some((w) => /v0\.1\.0/.test(w) && /no release\.v1\.json/.test(w)));
}

// 3. A descriptor that exists but cannot be downloaded is a HARD ERROR (never guess past it).
await expectThrow(
  'undownloadable descriptor throws',
  deriveReleaseSequence({
    releases: [release('v0.2.0', { sequence: 40 })],
    fetchAsset: fetcher(() => {
      throw new Error('HTTP 403 Forbidden');
    }),
  }),
  /Failed to download release\.v1\.json from published release v0\.2\.0.*403/s,
);

// 4. A descriptor that downloads but is not JSON is a HARD ERROR.
await expectThrow(
  'unparseable descriptor throws',
  deriveReleaseSequence({
    releases: [release('v0.2.0', { sequence: 40 })],
    fetchAsset: fetcher(async () => '<html>rate limited</html>'),
  }),
  /unparseable release\.v1\.json/,
);

// 5. A descriptor with a non-integer / non-positive sequence is a HARD ERROR.
for (const [label, sequence] of [
  ['missing', undefined],
  ['string', '40'],
  ['zero', 0],
  ['negative', -1],
  ['fractional', 4.5],
  ['unsafe', Number.MAX_SAFE_INTEGER + 1],
  ['no safe successor', Number.MAX_SAFE_INTEGER],
]) {
  await expectThrow(
    `invalid sequence (${label}) throws`,
    deriveReleaseSequence({ releases: [release('v0.2.0', { sequence })], fetchAsset: fetcher() }),
    /invalid sequence/,
  );
}

// 6. No published descriptor anywhere -> the checked-in floor, which must exceed v0.2.0's 40.
{
  const result = await deriveReleaseSequence({
    releases: [release('v0.1.0', { withAsset: false })],
    fetchAsset: fetcher(),
  });
  ok('floor constant is 41', RELEASE_SEQUENCE_FLOOR === 41);
  ok('zero-descriptor falls back to the floor', result.sequence === RELEASE_SEQUENCE_FLOOR);
  ok('floor provenance is explicit', /floor 41/.test(result.provenance));
}

// 7. An empty release list also lands on the floor rather than 1.
{
  const result = await deriveReleaseSequence({ releases: [], fetchAsset: fetcher() });
  ok('empty release list -> floor', result.sequence === RELEASE_SEQUENCE_FLOOR);
}

// 8. The floor is a hard lower bound, not just a zero-descriptor fallback: a stale/low
//    published max must never produce a value a shipped client would refuse.
{
  const result = await deriveReleaseSequence({
    releases: [release('v0.0.9', { sequence: 3 })],
    fetchAsset: fetcher(),
  });
  ok('low published max raised to the floor', result.sequence === RELEASE_SEQUENCE_FLOOR);
  ok('raise is stated in provenance', /raised to checked-in floor 41/.test(result.provenance));
}

// 9. Drafts never count: an unpublished draft cannot have been accepted by any client.
{
  const result = await deriveReleaseSequence({
    releases: [release('v9.9.9', { sequence: 900, draft: true }), release('v0.2.0', { sequence: 40 })],
    fetchAsset: fetcher(),
  });
  ok('draft ignored', result.sequence === 41);
  ok('draft warns', result.warnings.some((w) => /draft release v9\.9\.9/.test(w)));
}

// 10. Prereleases DO count. /releases/latest never serves them, but they are published
//     artifacts; counting them keeps the max a conservative superset.
{
  const result = await deriveReleaseSequence({
    releases: [release('v0.3.0-rc.1', { sequence: 55, prerelease: true }), release('v0.2.0', { sequence: 40 })],
    fetchAsset: fetcher(),
  });
  ok('prerelease descriptor counts toward the max', result.sequence === 56);
}

// 11. --exclude-tag keeps a re-run of an already-published tag idempotent.
{
  const releases = [release('v0.3.0', { sequence: 41 }), release('v0.2.0', { sequence: 40 })];
  const fresh = await deriveReleaseSequence({ releases, fetchAsset: fetcher() });
  ok('without exclusion the re-run would drift upward', fresh.sequence === 42);
  const rerun = await deriveReleaseSequence({ releases, fetchAsset: fetcher(), excludeTag: 'v0.3.0' });
  ok('excluding the built tag re-derives the same value', rerun.sequence === 41);
  ok('exclusion warns', rerun.warnings.some((w) => /tag being built/.test(w)));
}

// 12. CLI: no repo and no GITHUB_REPOSITORY -> exit 1 with a legible message, no network.
{
  const env = { ...process.env };
  delete env.GITHUB_REPOSITORY;
  const r = spawnSync(process.execPath, [SCRIPT], { env, encoding: 'utf8' });
  ok('missing repo exits 1', r.status === 1);
  ok('missing repo explains itself', /No repository/.test(r.stderr));
  ok('missing repo prints nothing on stdout', r.stdout.trim() === '');
}

// 13. CLI: unknown argument -> exit 1.
{
  const r = spawnSync(process.execPath, [SCRIPT, '--nope'], { encoding: 'utf8' });
  ok('unknown argument exits 1', r.status === 1);
  ok('unknown argument named in the error', /--nope/.test(r.stderr));
}

// 14. CLI: an unreachable API is a clean exit 1 with NOTHING on stdout. Regression guard for
//     two distinct ways this could ship a sequence anyway: printing a fallback on failure, and
//     process.exit() racing fetch's open socket (which aborts Node with a libuv assertion and a
//     garbage exit code — observed as 0xC0000409 before the fix).
{
  const r = spawnSync(process.execPath, [SCRIPT, '--repo', 'owner/repo'], {
    env: { ...process.env, DERIVE_RELEASE_SEQUENCE_API_ROOT: 'http://127.0.0.1:1' },
    encoding: 'utf8',
    timeout: 30_000,
  });
  ok('unreachable API exits exactly 1', r.status === 1);
  ok('unreachable API prints nothing on stdout', r.stdout.trim() === '');
  ok('unreachable API says it FAILED', /FAILED/.test(r.stderr));
  ok('unreachable API does not abort the runtime', !/Assertion failed/.test(r.stderr + r.stdout));
}

// 15. CLI: --help prints usage and exits 0 without touching the API.
{
  const r = spawnSync(process.execPath, [SCRIPT, '--help'], { encoding: 'utf8' });
  ok('--help exits 0', r.status === 0);
  ok('--help names the floor', /41/.test(r.stdout));
}

// The installed tag owns the complete baseline; its descriptor may be only an inherited subset.
const frozenStrategy = 'READ_V0_OR_V1_AND_WRITE_V1';
const predecessorRegister = {
  durableStores: [{ id: 'ui-settings', owner: 'HEAD', recoverability: 'AUTHORED',
    currentVersion: 4, reconciliation: frozenStrategy }],
};
const predecessorDescriptor = (sequence, tag = sequence === 57 ? 'v0.4.0' : 'v0.9.0') => ({
  schemaVersion: 1, channel: 'stable', target: 'windows-x86_64', version: tag.slice(1),
  sequence, compatibility: [{ ownerId: 'ui-settings',
  owner: 'HEAD', role: 'AUTHORED', formatVersion: 4, reconciliationStrategy: frozenStrategy }] });

const retiredSnapshot = {
  id: 'worker-config-snapshot',
  owner: 'HEAD',
  recoverability: 'DERIVED',
  currentVersion: 0,
  reconciliation: 'UNCONDITIONALLY_REGENERATE_BEFORE_WORKER_START',
  byteDisposition: 'PRESERVE_INERT',
  retiredAt: '2026-09-07',
  decision: 'lane F stage A item A19 (tempdoc 936)',
  note: 'Retired compatibility projection; existing bytes remain untouched and are never consumed.',
};
const inheritedRetiredRegister = {
  durableStores: [predecessorRegister.durableStores[0]],
  retiredDurableStores: [retiredSnapshot],
};
const inheritedRetiredDescriptor = (sequence, tag = 'v0.9.0') => ({
  schemaVersion: 1,
  channel: 'stable',
  target: 'windows-x86_64',
  version: tag.slice(1),
  sequence,
  compatibility: [
    { ownerId: 'ui-settings', owner: 'HEAD', role: 'AUTHORED', formatVersion: 4,
      reconciliationStrategy: frozenStrategy },
    { ownerId: retiredSnapshot.id, owner: retiredSnapshot.owner, role: retiredSnapshot.recoverability,
      formatVersion: retiredSnapshot.currentVersion, reconciliationStrategy: retiredSnapshot.reconciliation },
  ],
});

{
  const exactRawRegister = `${JSON.stringify(inheritedRetiredRegister)}\n`;
  const result = await deriveReleaseSequence({
    releases: [release('v0.9.0', { sequence: 112 })],
    fetchAsset: async () => JSON.stringify(inheritedRetiredDescriptor(112)),
    fetchCompatibilityBaseline: async () => exactRawRegister,
  });
  ok('predecessor descriptor validates active plus retired owners', result.sequence === 113);
  ok('predecessor baseline preserves exact raw register bytes',
    result.compatibilityBaseline.text === exactRawRegister);
}

await expectThrow('predecessor rejects an unknown retired owner', deriveReleaseSequence({
  releases: [release('v0.9.0', { sequence: 112 })],
  fetchAsset: async () => JSON.stringify({
    ...inheritedRetiredDescriptor(112),
    compatibility: [...inheritedRetiredDescriptor(112).compatibility, {
      ownerId: 'unknown-retired-owner', owner: 'HEAD', role: 'DERIVED', formatVersion: 0,
      reconciliationStrategy: 'REBUILD',
    }],
  }),
  fetchCompatibilityBaseline: async () => JSON.stringify(inheritedRetiredRegister),
}), /identity or format mismatch/);

await expectThrow('predecessor rejects a retired version mismatch', deriveReleaseSequence({
  releases: [release('v0.9.0', { sequence: 112 })],
  fetchAsset: async () => JSON.stringify({
    ...inheritedRetiredDescriptor(112),
    compatibility: inheritedRetiredDescriptor(112).compatibility.map(row => row.ownerId === retiredSnapshot.id
      ? { ...row, formatVersion: 1 } : row),
  }),
  fetchCompatibilityBaseline: async () => JSON.stringify(inheritedRetiredRegister),
}), /identity or format mismatch/);
{
  const fetched = [];
  const text = JSON.stringify({ durableStores: [...predecessorRegister.durableStores,
    { ...predecessorRegister.durableStores[0], id: 'operations-db' }] }, null, 2) + '\n';
  const result = await deriveReleaseSequence({
    releases: [release('v0.4.0', { sequence: 57 }), release('v0.9.0', { sequence: 112 }),
      release('v9.9.9', { sequence: 900 })],
    fetchAsset: async (asset, rel) => JSON.stringify(predecessorDescriptor(asset._sequence, rel.tag_name)),
    excludeTag: 'v9.9.9',
    fetchCompatibilityBaseline: async rel => { fetched.push(rel.tag_name); return text; },
  });
  ok('baseline uses the same highest-sequence predecessor', result.sequence === 113
    && result.compatibilityBaseline.tag === 'v0.9.0' && fetched.join() === 'v0.9.0');
  ok('baseline retains exact tag bytes and full installed owner set',
    result.compatibilityBaseline.text === text);
}
for (const [label, register, pattern] of [
  ['renamed frozen strategy', { durableStores: [{ ...predecessorRegister.durableStores[0],
    reconciliation: 'READ_V0_THROUGH_V5' }] }, /identity or format mismatch/],
  ['missing descriptor owner', { durableStores: [{ ...predecessorRegister.durableStores[0],
    id: 'different-owner' }] }, /identity or format mismatch/],
  ['duplicate store', { durableStores: [...predecessorRegister.durableStores,
    ...predecessorRegister.durableStores] }, /invalid or duplicate/],
  ['empty store set', { durableStores: [] }, /no installed durable stores/],
  ['wrong installed format', { durableStores: [{ ...predecessorRegister.durableStores[0],
    currentVersion: 5 }] }, /identity or format mismatch/],
]) {
  await expectThrow(`baseline rejects ${label}`, deriveReleaseSequence({
    releases: [release('v0.9.0', { sequence: 112 })],
    fetchAsset: async () => JSON.stringify(predecessorDescriptor(112)),
    fetchCompatibilityBaseline: async () => JSON.stringify(register),
  }), pattern);
}
await expectThrow('baseline refuses a guessed zero-descriptor predecessor', deriveReleaseSequence({
  releases: [], fetchAsset: fetcher(), fetchCompatibilityBaseline: async () => '{}',
}), /No published predecessor/);
await expectThrow('baseline refuses tied highest tags', deriveReleaseSequence({
  releases: [release('v0.8.0', { sequence: 112 }), release('v0.9.0', { sequence: 112 })],
  fetchAsset: async (asset, rel) => JSON.stringify(predecessorDescriptor(asset._sequence, rel.tag_name)),
  fetchCompatibilityBaseline: async () => JSON.stringify(predecessorRegister),
}), /ambiguous predecessor/);
for (const [field, value] of [['version', '0.8.0'], ['schemaVersion', 2],
  ['channel', 'preview'], ['target', 'linux-x86_64']]) {
  await expectThrow(`baseline binds predecessor ${field}`, deriveReleaseSequence({
    releases: [release('v0.9.0', { sequence: 112 })],
    fetchAsset: async () => JSON.stringify({ ...predecessorDescriptor(112), [field]: value }),
    fetchCompatibilityBaseline: async () => { throw new Error('must not fetch an inconsistent predecessor'); },
  }), /descriptor\/tag identity mismatch/);
}

// Actual CLI/API/write chain: failure must emit neither a sequence nor a baseline artifact.
{
  const directory = await mkdtemp(path.join(os.tmpdir(), 'release-predecessor-test-'));
  let origin;
  let mismatch = false;
  const requests = [];
  const server = createServer((request, response) => {
    requests.push(request.url);
    response.setHeader('Content-Type', 'application/json');
    if (request.url.startsWith('/repos/owner/repo/releases?')) {
      response.end(JSON.stringify([57, 112].map((sequence, index) => ({
        tag_name: index ? 'v0.9.0' : 'v0.4.0',
        assets: [{ name: DESCRIPTOR_ASSET_NAME, url: `${origin}/assets/${sequence}` }],
      }))));
    } else if (request.url.startsWith('/assets/')) {
      response.end(JSON.stringify(predecessorDescriptor(Number(request.url.split('/').at(-1)))));
    } else if (request.url === '/repos/owner/repo/contents/governance/store-recoverability.v1.json?ref=v0.9.0') {
      response.end(JSON.stringify(mismatch ? { durableStores: [{
        ...predecessorRegister.durableStores[0], reconciliation: 'READ_V0_THROUGH_V5',
      }] } : predecessorRegister) + '\n');
    } else { response.writeHead(404); response.end('{}'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
  const run = destination => new Promise((resolve, reject) => {
    const env = { ...process.env, DERIVE_RELEASE_SEQUENCE_API_ROOT: origin };
    delete env.GITHUB_TOKEN;
    delete env.GH_TOKEN;
    const child = spawn(process.execPath, [SCRIPT, '--repo', 'owner/repo',
      '--compat-baseline-out', destination], { env, timeout: 15_000 });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', status => resolve({ status, stdout, stderr }));
  });
  try {
    const baseline = path.join(directory, 'baseline.json');
    const success = await run(baseline);
    ok('CLI succeeds with exact predecessor sequence', success.status === 0 && success.stdout === '113\n');
    ok('CLI emits exact validated tag bytes', await readFile(baseline, 'utf8')
      === JSON.stringify(predecessorRegister) + '\n');
    ok('CLI reads tag ref rather than default branch', requests.some(url => url.endsWith('?ref=v0.9.0')));
    mismatch = true;
    const failed = await run(path.join(directory, 'refused.json'));
    ok('CLI renamed strategy fails before sequence output', failed.status === 1 && failed.stdout === ''
      && /identity or format mismatch/.test(failed.stderr));
    ok('CLI failed acquisition leaves no baseline or temporary artifact',
      (await readdir(directory)).join() === 'baseline.json');
    const stale = path.join(directory, 'stale.json');
    await writeFile(stale, JSON.stringify(predecessorRegister));
    const staleFailure = await run(stale);
    ok('CLI invalidates a pre-existing baseline before failed acquisition', staleFailure.status === 1
      && staleFailure.stdout === '' && (await readdir(directory)).join() === 'baseline.json');
    await writeFile(stale, JSON.stringify(predecessorRegister));
    const noRepositoryEnv = { ...process.env };
    delete noRepositoryEnv.GITHUB_REPOSITORY;
    const noRepository = spawnSync(process.execPath, [SCRIPT, '--compat-baseline-out', stale], {
      env: noRepositoryEnv, encoding: 'utf8', timeout: 15_000,
    });
    ok('CLI invalidates a named baseline even when repository is missing', noRepository.status === 1
      && noRepository.stdout === '' && /No repository/.test(noRepository.stderr)
      && (await readdir(directory)).join() === 'baseline.json');
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    await rm(directory, { recursive: true, force: true });
  }
}

if (failures.length > 0) {
  console.error(`derive-release-sequence.test: FAIL (${failures.length}/${passed + failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`derive-release-sequence.test: OK (${passed} assertions)`);
