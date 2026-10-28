import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, readdir, rm, cp, unlink } from 'node:fs/promises';
import { generateKeyPairSync, createPublicKey } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { generateFixture } from './updater-v2-fixture.mjs';
const subject = await import('./release-candidate.mjs').catch(() => ({}));
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
    download: async name => uploads.get(name),
    upload: async (name, bytes) => { assert(!uploads.has(name)); uploads.set(name, bytes); release.assets.push({ name }); },
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
    download: async name => readFile(path.join(f.outDir, name)),
    promote: async () => { promoted++; draft = false; },
  };
  await assert.rejects(subject.publishDraft({ ...policy(f), minimumSequence: 43 }, remote), /older than accepted/);
  assert.equal(promoted, 0);
  await assert.rejects(subject.publishDraft({ ...policy(f), verifyAttestation: async () => { throw Error('wrong attestation'); } }, remote), /wrong attestation/);
  assert.equal(promoted, 0);
  await subject.publishDraft(policy(f), remote);
  await subject.publishDraft(policy(f), remote);
  assert.equal(promoted, 1);
  await assert.rejects(subject.publishDraft(policy(f), { ...remote, download: async () => Buffer.from('tampered') }), /changed before publication/);
});
test('verification requires every production trust pin', async t => {
  const f = await build(t); await attest(f);
  for (const name of ['metadataPublicKeyPath', 'metadataRootPublicKey', 'metadataKeyId', 'artifactKeyId', 'artifactPublicKey']) {
    await assert.rejects(subject.verifyCandidate({ ...policy(f), [name]: '' }), /required/);
  }
});
