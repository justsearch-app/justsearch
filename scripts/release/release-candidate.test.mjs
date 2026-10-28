import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, readdir, rm, cp, unlink, mkdir, chmod, symlink } from 'node:fs/promises';
import { generateKeyPairSync, createPublicKey, createHash } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { generateFixture, signArtifact } from './updater-v2-fixture.mjs';
const subject = await import('./release-candidate.mjs').catch(() => ({}));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const commit = 'a'.repeat(40);
async function fixture(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'release-candidate-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const f = await generateFixture(path.join(dir, 'inputs'));
  const mcpb = path.join(dir, 'bundle.mcpb');
  await writeFile(mcpb, 'fake mcpb');
  const evidence = {};
  for (const platform of ['windows', 'linux']) {
    evidence[platform] = path.join(dir, `${platform}.json`);
    await writeFile(evidence[platform], JSON.stringify({ platform, commit, runId: '123', passed: true }));
  }
  return { ...f, commit, runId: '123', tag: 'v1.2.3', repo: 'justsearch-app/justsearch',
    artifactKeyId: 'test-artifact', artifactPublicKey: f.targets['linux-x86_64'].artifactPublicKey,
    metadataRootPublicKey: createPublicKey(await readFile(f.metadataPublicKeyPath)).export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64'),
    mcpb, evidence, outDir: path.join(dir, 'candidate') };
}
async function build(t) {
  assert.equal(typeof subject.buildCandidate, 'function', 'complete two-platform candidate producer is required');
  const f = await fixture(t);
  await subject.buildCandidate(f);
  return f;
}
async function attest(f) {
  await writeFile(path.join(f.outDir, 'provenance.sigstore.json'), 'fake bundle (verifier injected in tests)');
  await subject.writeChecksums(f.outDir);
}
function policy(f) {
  return { ...f, releaseDir: f.outDir, expectedCommit: commit, expectedTag: f.tag,
    verifyAttestation: async () => {} };
}
test('complete candidate contains both targets, matching latest, checksums over every asset and durable evidence', async t => {
  const f = await build(t);
  await attest(f);
  const result = await subject.verifyCandidate(policy(f));
  assert.deepEqual(Object.keys(result.descriptor.targets).sort(), ['linux-x86_64', 'windows-x86_64']);
  assert.deepEqual(Object.keys(result.latest.platforms).sort(), Object.keys(result.descriptor.targets).sort());
  const names = await readdir(f.outDir);
  const sums = await readFile(path.join(f.outDir, 'SHA256SUMS'), 'utf8');
  assert.deepEqual(sums.trim().split('\n').map(line => line.slice(66)).sort(), names.filter(n => n !== 'SHA256SUMS').sort());
  assert(names.includes('candidate.json') && names.includes('smoke-windows.json') && names.includes('smoke-linux.json'));
});
for (const [name, mutate, message] of [
  ['missing metadata private key', f => { f.metadataPrivateKeyPath = ''; }, /metadata private key/],
  ['missing artifact signing input', f => { f.artifactPublicKey = ''; }, /artifactPublicKey/],
  ['wrong metadata root', f => { f.metadataRootPublicKey = Buffer.alloc(32).toString('base64'); }, /root public key/],
  ['wrong metadata key pair', async f => { await writeFile(f.metadataPublicKeyPath, generateKeyPairSync('ed25519').publicKey.export({ type: 'spki', format: 'pem' })); }, /metadata.*key/],
  ['wrong artifact key', f => { f.artifactPublicKey = 'wrong-key'; }, /artifact/],
  ['qualification mode', f => { f.qualificationMode = true; }, /qualification/],
  ['one platform missing', f => { delete f.targets['linux-x86_64']; }, /both targets/],
  ['failed required check', async f => { await writeFile(f.evidence.linux, JSON.stringify({ passed: false })); }, /smoke/],
  ['mixed check commit', async f => { await writeFile(f.evidence.linux, JSON.stringify({ passed: true, commit: 'b'.repeat(40), runId: '123', platform: 'linux' })); }, /smoke/],
  ['mismatched artifact signature', async f => { await cp(f.targets['windows-x86_64'].artifactSignaturePath, f.targets['linux-x86_64'].artifactSignaturePath); }, /artifact signature/],
]) test(`candidate refuses ${name}`, async t => {
  assert.equal(typeof subject.buildCandidate, 'function', `candidate guard for ${name} is required`);
  const f = await fixture(t);
  await mutate(f);
  await assert.rejects(subject.buildCandidate(f), message);
});
test('publish refuses changed bytes, missing assets and extra unmanifested assets', async t => {
  const f = await build(t); await attest(f);
  const p = policy(f);
  const installer = path.join(f.outDir, path.basename(f.targets['linux-x86_64'].installerPath));
  const original = await readFile(installer);
  await writeFile(installer, 'changed');
  await assert.rejects(subject.verifyCandidate(p), /checksum/);
  await writeFile(installer, original);
  await writeFile(path.join(f.outDir, 'extra.exe'), 'unlisted');
  await assert.rejects(subject.verifyCandidate(p), /asset set|checksum/);
  await unlink(path.join(f.outDir, 'extra.exe'));
  await unlink(installer);
  await assert.rejects(subject.verifyCandidate(p), /ENOENT/);
});
for (const name of ['release.v2.json.sig', 'update.deb.sig']) {
  test(`publish refuses mismatched ${name} even with recomputed checksums`, async t => {
    const f = await build(t); await attest(f);
    await writeFile(path.join(f.outDir, name), 'wrong signature');
    await subject.writeChecksums(f.outDir);
    await assert.rejects(subject.verifyCandidate(policy(f)), /signature/);
  });
}
test('publish requires cryptographic provenance for each payload bound to the original tag and commit', async t => {
  const f = await build(t); await attest(f);
  const calls = [];
  await subject.verifyCandidate({ ...policy(f), verifyAttestation: async (file, identity) => {
    calls.push(path.basename(file)); assert.equal(identity.commit, commit); assert.equal(identity.tag, 'v1.2.3');
  } });
  assert(calls.includes('candidate.json') && calls.includes('release.v2.json') && calls.includes('update.deb') && calls.includes('setup.exe'));
  await assert.rejects(subject.verifyCandidate({ ...policy(f), verifyAttestation: async () => { throw Error('attestation mismatch'); } }), /attestation mismatch/);
  await assert.rejects(subject.verifyCandidate({ ...policy(f), expectedCommit: 'b'.repeat(40) }), /identity/);
});
test('assembly refuses an existing directory rather than mixing or replacing candidate bytes', async t => {
  const f = await build(t);
  const before = await readFile(path.join(f.outDir, 'candidate.json'));
  await assert.rejects(subject.buildCandidate({ ...f, commit: 'b'.repeat(40) }), /exist|reuse/);
  assert.deepEqual(await readFile(path.join(f.outDir, 'candidate.json')), before);
});
test('draft retries accept only the same run and identical uploaded bytes; never clobber', async t => {
  const f = await build(t); await attest(f);
  const options = policy(f);
  const uploads = new Map();
  let release;
  const remote = {
    get: async () => release,
    create: async body => { release = { ...body, id: 1, draft: true, assets: [] }; return release; },
    hash: async name => hash(uploads.get(name)),
    upload: async name => { const bytes = await readFile(path.join(f.outDir, name)); assert(!uploads.has(name)); uploads.set(name, bytes); release.assets.push({ name }); },
  };
  await subject.uploadDraft(options, remote);
  const count = uploads.size;
  await subject.uploadDraft(options, remote);
  assert.equal(uploads.size, count);
  release.body = '<!-- justsearch-candidate:other-run -->';
  await assert.rejects(subject.uploadDraft(options, remote), /another candidate/);
  release.body = subject.candidateMarker(f);
  uploads.set('latest.json', Buffer.from('wrong bytes'));
  await assert.rejects(subject.uploadDraft(options, remote), /different bytes/);
});
test('publication promotes only verified draft bytes; retry never rebuilds, and stale sequence refuses promotion', async t => {
  const f = await build(t); await attest(f);
  let promoted = 0;
  let draft = true;
  const remote = {
    get: async () => ({ tag_name: f.tag, body: subject.candidateMarker(f), draft,
      assets: (await readdir(f.outDir)).map(name => ({ name })) }),
    hash: async name => hash(await readFile(path.join(f.outDir, name))),
    promote: async () => { promoted++; draft = false; },
  };
  await assert.rejects(subject.publishDraft({ ...policy(f), minimumSequence: 43 }, remote), /older than accepted/);
  assert.equal(promoted, 0);
  await assert.rejects(subject.publishDraft({ ...policy(f), verifyAttestation: async () => { throw Error('wrong attestation'); } }, remote), /wrong attestation/);
  assert.equal(promoted, 0);
  await subject.publishDraft(policy(f), remote);
  await subject.publishDraft(policy(f), remote);
  assert.equal(promoted, 1);
  await assert.rejects(subject.publishDraft(policy(f), { ...remote, hash: async () => hash(Buffer.from('tampered')) }), /changed before publication/);
});
test('verification requires every production trust pin', async t => {
  const f = await build(t); await attest(f);
  for (const name of ['metadataPublicKeyPath', 'metadataRootPublicKey', 'metadataKeyId', 'artifactKeyId', 'artifactPublicKey']) {
    await assert.rejects(subject.verifyCandidate({ ...policy(f), [name]: '' }), /required/);
  }
});

for (const section of ['Not tested', 'Known issues']) {
  test(`publication refuses the unfilled ${section} placeholder`, async t => {
    const f = await build(t); await attest(f);
    let promoted = false;
    let notes = `${section}: [agent fills before final validation]`;
    const remote = {
      get: async () => ({ tag_name: f.tag, body: `${subject.candidateMarker(f)}\n${notes}`, draft: true,
        assets: (await readdir(f.outDir)).map(name => ({ name })) }),
      hash: async name => hash(await readFile(path.join(f.outDir, name))),
      promote: async () => { promoted = true; },
    };
    await assert.rejects(subject.publishDraft(policy(f), remote), /unfilled.*placeholder/);
    assert.equal(promoted, false);
    notes = 'Not tested: GPU hardware.\nKnown issues: None found.';
    await subject.publishDraft(policy(f), remote);
    assert.equal(promoted, true);
  });
}

test('real gh adapter streams large installers through upload, resume, round-trip and publication', async t => {
  const f = await fixture(t);
  const dir = path.dirname(f.outDir);
  const installer = f.targets['windows-x86_64'];
  const bytes = Buffer.concat([Buffer.from('MZ'), Buffer.alloc(2 * 1024 * 1024, 7)]);
  await writeFile(installer.installerPath, bytes);
  await writeFile(installer.artifactSignaturePath, signArtifact(bytes).signature);
  await subject.buildCandidate(f); await attest(f);
  await mkdir(path.join(dir, 'bin'));
  await mkdir(path.join(dir, 'remote'));
  await mkdir(path.join(dir, 'scripts'));
  await mkdir(path.join(dir, 'scripts/ci'));
  await cp(new URL('../ci/derive-release-sequence.mjs', import.meta.url), path.join(dir, 'scripts/ci/derive-release-sequence.mjs'));
  await symlink(fileURLToPath(new URL('../ci/check-store-recoverability.mjs', import.meta.url)), path.join(dir, 'scripts/ci/check-store-recoverability.mjs'));
  const api = createServer((_request, response) => { response.setHeader('content-type', 'application/json'); response.end('[]'); });
  await new Promise(resolve => api.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => api.close(resolve)));
  await writeFile(path.join(dir, 'CHANGELOG.md'), '# Changelog\n\n## [Unreleased]\n\n## [1.2.3] - 2026-10-27\n\n### Fixed\n\n- Test candidate.\n');
  const gh = path.join(dir, 'bin/gh');
  await writeFile(gh, `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const a = process.argv.slice(2), root = process.env.FAKE_GH_ROOT;
const stateFile = path.join(root, 'state.json');
const state = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile)) : null;
const save = value => fs.writeFileSync(stateFile, JSON.stringify(value));
const assets = () => fs.readdirSync(root).filter(n => n !== 'state.json').map(name => ({ name, id: name }));
if (a[0] === 'attestation') process.exit(0); // Test-only provenance adapter.
if (a[0] === 'api') {
  if (a.includes('POST')) { const body = JSON.parse(fs.readFileSync(0)); save({...body, id: 1}); console.log(JSON.stringify({...body, id: 1, assets: []})); }
  else if (a.some(x => x.includes('/releases/assets/'))) process.stdout.write(fs.readFileSync(path.join(root, a[1].split('/').pop())));
  else if (a.some(x => x.includes('/assets?'))) console.log(JSON.stringify(assets()));
  else console.log(JSON.stringify([state ? [{...state, assets: assets()}] : []]));
} else if (a[0] === 'release' && a[1] === 'upload') {
  const name = path.basename(a[3]);
  if (fs.existsSync(path.join(root, name))) throw Error('no clobber');
  fs.copyFileSync(a[3], path.join(root, name));
} else if (a[0] === 'release' && a[1] === 'download') {
  const name = a[a.indexOf('--pattern') + 1], dir = a[a.indexOf('--dir') + 1];
  fs.copyFileSync(path.join(root, name), path.join(dir, name));
} else if (a[0] === 'release' && a[1] === 'edit') save({...state, draft: false});
else throw Error('unexpected gh arguments: ' + JSON.stringify(a));
`);
  await chmod(gh, 0o755);
  const config = path.join(dir, 'options.json');
  const options = { ...f, releaseDir: f.outDir, expectedTag: f.tag, expectedCommit: commit };
  await writeFile(config, JSON.stringify(options));
  const script = fileURLToPath(new URL('./release-candidate.mjs', import.meta.url));
  const env = { ...process.env, PATH: `${path.join(dir, 'bin')}${path.delimiter}${process.env.PATH}`, FAKE_GH_ROOT: path.join(dir, 'remote'),
    DERIVE_RELEASE_SEQUENCE_API_ROOT: `http://127.0.0.1:${api.address().port}` };
  const run = command => promisify(execFile)(process.execPath, [script, command, config], { cwd: dir, env, encoding: 'utf8' });
  await run('upload'); await run('upload');
  const stateFile = path.join(dir, 'remote/state.json');
  const state = JSON.parse(await readFile(stateFile));
  state.body = state.body.replaceAll('[agent fills before final validation]', 'None.');
  await writeFile(stateFile, JSON.stringify(state));
  await run('publish'); await run('publish');
  assert.equal(JSON.parse(await readFile(stateFile)).draft, false);
  // Remote corruption must still fail at the real adapter boundary.
  await writeFile(path.join(dir, 'remote', path.basename(installer.installerPath)), 'tampered');
  await assert.rejects(run('publish'), /draft bytes changed before publication/);
});

for (const fails of [false, true]) test(`descriptor signing command removes its temporary private key on ${fails ? 'a thrown error' : 'success'}`, async t => {
  const f = await fixture(t);
  const dir = path.dirname(f.outDir);
  const runnerTemp = path.join(dir, 'runner-temp');
  await mkdir(runnerTemp);
  const githubEnv = path.join(dir, 'github-env');
  await writeFile(githubEnv, '');
  const env = { ...process.env, RUNNER_TEMP: runnerTemp, GITHUB_ENV: githubEnv,
    METADATA_PRIVATE_KEY_PEM: await readFile(f.metadataPrivateKeyPath, 'utf8'),
    METADATA_PUBLIC_KEY_PEM: await readFile(f.metadataPublicKeyPath, 'utf8') };
  delete env.JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH;
  delete f.metadataPrivateKeyPath;
  // Execute the workflow's key-staging commands as well: a key exported by an
  // earlier step must not survive the signing step, even when assembly throws.
  const yaml = createRequire(import.meta.url)('js-yaml');
  const workflow = yaml.load(await readFile(new URL('../../.github/workflows/build-installer.yml', import.meta.url), 'utf8'));
  const script = fileURLToPath(new URL('./release-candidate.mjs', import.meta.url));
  for (const step of workflow.jobs.assemble.steps) {
    if (step.run?.includes('release-candidate.mjs build')) break;
    if (!step.env?.METADATA_PUBLIC_KEY_PEM) continue;
    await promisify(execFile)('bash', ['-e', '-c', step.run], { env });
    for (const line of (await readFile(githubEnv, 'utf8')).trim().split('\n')) {
      const split = line.indexOf('=');
      if (split > 0) env[line.slice(0, split)] = line.slice(split + 1);
    }
  }
  if (fails) delete f.targets['linux-x86_64'];
  const config = path.join(dir, 'signing-options.json');
  await writeFile(config, JSON.stringify(f));
  const run = promisify(execFile)(process.execPath, [script, 'build', config], { env });
  if (fails) await assert.rejects(run, /candidate requires both targets/);
  else { await run; assert((await readdir(f.outDir)).includes('release.v2.json.sig')); }
  assert.deepEqual((await readdir(runnerTemp)).filter(name => name !== 'metadata-public.pem'), [], 'signing step left private key material on disk');
  assert.doesNotMatch(await readFile(githubEnv, 'utf8'), /PRIVATE_KEY_PATH/, 'private key path escaped into GITHUB_ENV');
});

test('admit requires boolean presence flags and accepts no signing secret values', async t => {
  const f = await fixture(t);
  const dir = path.dirname(f.outDir);
  const bin = path.join(dir, 'bin');
  await mkdir(bin);
  for (const [name, source] of [['gh', '#!/bin/sh\necho "[]"\n'], ['git', '#!/bin/sh\ntest "$1" = merge-base\n']]) {
    const file = path.join(bin, name); await writeFile(file, source); await chmod(file, 0o755);
  }
  await writeFile(path.join(dir, 'gradle.properties'), 'version=1.2.3\n');
  await writeFile(path.join(dir, 'CHANGELOG.md'), '# Changelog\n\n## [Unreleased]\n\n## [1.2.3] - 2026-10-27\n\n### Fixed\n\n- Test candidate.\n');
  const env = { ...process.env, PATH: `${bin}${path.delimiter}${process.env.PATH}`,
    GITHUB_REPOSITORY: f.repo, GITHUB_REF_NAME: f.tag, GITHUB_SHA: f.commit, GITHUB_RUN_ID: f.runId,
    JUSTSEARCH_RELEASE_DESCRIPTOR_URL: subject.DESCRIPTOR_URL,
    JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY: f.metadataRootPublicKey,
    JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID: f.metadataKeyId,
    JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PATH: f.metadataPublicKeyPath,
    JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY: f.artifactPublicKey, JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID: f.artifactKeyId,
    HAS_CODESIGN_COMMAND: 'true', HAS_TAURI_KEY: 'true', HAS_METADATA_KEY: 'true' };
  for (const name of ['JUSTSEARCH_CODESIGN_MODE', 'JUSTSEARCH_CODESIGN_COMMAND', 'TAURI_SIGNING_PRIVATE_KEY',
    'METADATA_PRIVATE_KEY_PEM', 'JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH']) delete env[name];
  const script = fileURLToPath(new URL('./release-candidate.mjs', import.meta.url));
  const run = values => promisify(execFile)(process.execPath, [script, 'admit'], { cwd: dir, env: values });
  assert.match((await run(env)).stdout, /Release admission passed/);
  for (const flag of ['HAS_CODESIGN_COMMAND', 'HAS_TAURI_KEY', 'HAS_METADATA_KEY']) {
    for (const value of ['', 'false', 'not-a-boolean']) {
      await assert.rejects(run({ ...env, [flag]: value }), new RegExp(`${flag} must be true`));
    }
  }
});
