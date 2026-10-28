import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, access } from 'node:fs/promises';
import { createPrivateKey, generateKeyPairSync, sign } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { generateFixture } from './updater-v2-fixture.mjs';
import { buildReleaseAssets, verifyReleaseAssets } from './app-release-assets.mjs';
import { deriveReleaseSequence } from '../ci/derive-release-sequence.mjs';

async function fixture() {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'justsearch-v2-'));
  let f;
  await assert.doesNotReject(async () => { f = await generateFixture(dir); },
    'producer must accept both explicitly supplied target artifacts');
  return f;
}
async function resign(f, mutate) {
  const name = path.join(f.outDir, 'release.v2.json');
  const descriptor = JSON.parse(await readFile(name));
  mutate(descriptor);
  const bytes = Buffer.from(JSON.stringify(descriptor));
  await writeFile(name, bytes);
  await writeFile(`${name}.sig`, sign(null, bytes,
    createPrivateKey(await readFile(f.metadataPrivateKeyPath))).toString('base64'));
}

test('v2 producer and consumer agree on both signed targets and the complete predecessor register', async () => {
  const f = await fixture();
  const { descriptor, latest } = await verifyReleaseAssets(f);
  assert.equal(descriptor.schemaVersion, 2);
  assert.deepEqual(Object.keys(descriptor.targets).sort(), ['linux-x86_64', 'windows-x86_64']);
  const register = JSON.parse(await readFile(f.compatibilityBaselinePath));
  for (const target of Object.keys(descriptor.targets)) {
    assert.deepEqual(descriptor.targets[target].compatibility.map(row => row.ownerId), register.durableStores.map(row => row.id));
    assert.equal(latest.platforms[target].signature, descriptor.targets[target].artifact.signature);
  }
  await assert.rejects(access(path.join(f.outDir, 'release.v1.json')), { code: 'ENOENT' });
  register.durableStores.pop();
  await writeFile(f.compatibilityBaselinePath, JSON.stringify(register));
  await assert.rejects(buildReleaseAssets(f), /complete named predecessor register/);
});

test('v2 rejects tampered bytes and the wrong metadata root', async () => {
  const f = await fixture();
  await assert.rejects(verifyReleaseAssets({ ...f, expectedMetadataPublicKeyBase64: Buffer.alloc(32).toString('base64') }), /compiled raw root/);
  await assert.rejects(verifyReleaseAssets({ ...f, expectedMetadataKeyId: 'wrong-key' }), /compiled root key id/);
  const wrongKeyPath = path.join(f.outDir, 'wrong-metadata-public.pem');
  await writeFile(wrongKeyPath, generateKeyPairSync('ed25519').publicKey.export({ type: 'spki', format: 'pem' }));
  await assert.rejects(verifyReleaseAssets({ ...f, metadataPublicKeyPath: wrongKeyPath }), /metadata signature verification failed/);
  await writeFile(path.join(f.outDir, 'release.v2.json'), Buffer.concat([await readFile(path.join(f.outDir, 'release.v2.json')), Buffer.from(' ')]));
  await assert.rejects(verifyReleaseAssets(f), /metadata signature verification failed/);
});

for (const [field, value] of [['artifactKeyId', 'wrong-artifact-id'], ['artifactPublicKey', 'wrong-artifact-key']]) {
  test(`v2 verification rejects a descriptor using a different compiled ${field}`, async () => {
    const f = await fixture();
    f.targets['linux-x86_64'][field] = value;
    await assert.rejects(verifyReleaseAssets(f), /artifact signing key does not match the compiled key/);
  });
}

test('v2 producer refuses a mismatched artifact signature, missing signing input and qualification mode', async () => {
  const f = await fixture();
  await writeFile(f.targets['linux-x86_64'].artifactSignaturePath,
    await readFile(f.targets['windows-x86_64'].artifactSignaturePath));
  await assert.rejects(buildReleaseAssets(f), /artifact signature verification failed/);
  await assert.rejects(buildReleaseAssets({ ...f, qualificationMode: true }), /qualification-mode/);
  await assert.rejects(buildReleaseAssets({ ...f, targets: { 'windows-x86_64': { ...f.targets['windows-x86_64'], artifactPublicKey: '' } } }), /must be non-blank/);
});

test('v2 rejects rollback, a missing target and a missing predecessor store', async () => {
  const f = await fixture();
  await assert.rejects(verifyReleaseAssets({ ...f, minimumSequence: 43 }), /older than accepted/);
  await resign(f, descriptor => { delete descriptor.targets['linux-x86_64']; });
  await assert.rejects(verifyReleaseAssets(f), /target set/);
  await buildReleaseAssets(f);
  await resign(f, descriptor => { descriptor.targets['linux-x86_64'].compatibility.pop(); });
  await assert.rejects(verifyReleaseAssets(f), /complete predecessor register/);
});

test('sequence derivation shares v1/v2 history, skips drafts, and rejects conflicting descriptors', async () => {
  const f = await fixture();
  const v2 = JSON.parse(await readFile(path.join(f.outDir, 'release.v2.json')));
  const releases = [
    { tag_name: 'v0.3.0', assets: [{ name: 'release.v1.json', descriptor: { schemaVersion: 1, sequence: 41 } }] },
    { tag_name: 'v1.2.3', assets: [{ name: 'release.v2.json', descriptor: v2 }] },
    { tag_name: 'v9.9.9', draft: true, assets: [{ name: 'release.v2.json', descriptor: { ...v2, sequence: 999 } }] },
  ];
  const fetchAsset = async asset => JSON.stringify(asset.descriptor);
  assert.equal((await deriveReleaseSequence({ releases, fetchAsset })).sequence, 43);
  const predecessor = await deriveReleaseSequence({ releases: [releases[1]], fetchAsset,
    fetchCompatibilityBaseline: () => readFile(f.compatibilityBaselinePath, 'utf8') });
  assert.equal(predecessor.sequence, 43);
  assert.equal(JSON.parse(predecessor.compatibilityBaseline.text).durableStores.length,
    v2.targets['linux-x86_64'].compatibility.length);
  await assert.rejects(deriveReleaseSequence({ releases: [releases[1], {
    tag_name: 'v1.2.4', assets: [{ name: 'release.v2.json', descriptor: v2 }],
  }], fetchAsset }), /Conflicting published sequence/);
  const old = { ...v2, schemaVersion: 1, target: 'windows-x86_64', ...v2.targets['windows-x86_64'] };
  delete old.targets;
  const dual = { ...releases[1], assets: [...releases[1].assets, { name: 'release.v1.json', descriptor: old }] };
  assert.equal((await deriveReleaseSequence({ releases: [dual], fetchAsset })).sequence, 43);
  old.sequence = 50;
  await assert.rejects(deriveReleaseSequence({ releases: [dual], fetchAsset }), /Conflicting v1\/v2/);
});
