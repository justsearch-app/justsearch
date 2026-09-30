import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  buildReleaseAssets,
  verifyTauriArtifactSignature,
  verifyReleaseAssets,
} from './app-release-assets.mjs';

const MINISIGN_PUBLIC_KEY = `untrusted comment: minisign public key E7620F1842B4E81F
RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3`;
const MINISIGN_SIGNATURE = `untrusted comment: signature from minisign secret key
RWQf6LRCGA9i59SLOFxz6NxvASXDJeRtuZykwQepbDEGt87ig1BNpWaVWuNrm73YiIiJbq71Wi+dP9eKL8OC351vwIasSSbXxwA=
trusted comment: timestamp:1555779966\tfile:test
QtKMXWyYcwdpZAlPF7tE2ENJkRd1ujvKjlj1m9RtHTBnZPa5WKU5uWRs5GoP5M/VqE81QFuMKI5k/SfNQUaOAA==`;
const TAURI_PUBLIC_KEY = Buffer.from(MINISIGN_PUBLIC_KEY).toString('base64');
const TAURI_SIGNATURE = Buffer.from(MINISIGN_SIGNATURE).toString('base64');

async function fixture() {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'justsearch-release-'));
  const installerPath = path.join(dir, 'JustSearch-setup.exe');
  const artifactSignaturePath = `${installerPath}.sig`;
  const metadataPrivateKeyPath = path.join(dir, 'metadata-private.pem');
  const metadataPublicKeyPath = path.join(dir, 'metadata-public.pem');
  const compatibilityRegisterPath = path.join(dir, 'stores.json');
  const outDir = path.join(dir, 'out');
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  await Promise.all([
    writeFile(installerPath, 'test'),
    writeFile(artifactSignaturePath, TAURI_SIGNATURE),
    writeFile(
      metadataPrivateKeyPath,
      privateKey.export({ type: 'pkcs8', format: 'pem' }),
    ),
    writeFile(
      metadataPublicKeyPath,
      publicKey.export({ type: 'spki', format: 'pem' }),
    ),
    writeFile(
      compatibilityRegisterPath,
      JSON.stringify({
        knownCompatibilityGaps: [],
        durableStores: [
          {
            id: 'preferences',
            owner: 'HEAD',
            recoverability: 'AUTHORED',
            status: 'READY',
            currentVersion: 1,
            readableLegacyVersions: [0],
            reconciliation: 'READ_V0_OR_V1',
          },
        ],
      }),
    ),
  ]);
  return {
    installerPath,
    artifactSignaturePath,
    metadataPrivateKeyPath,
    metadataPublicKeyPath,
    compatibilityRegisterPath,
    outDir,
  };
}

test('build and verify a closed descriptor/latest/artifact set', async () => {
  const f = await fixture();
  await buildReleaseAssets({
    ...f,
    version: '1.2.3',
    sequence: 42,
    installerUrl: 'https://example.invalid/JustSearch-setup.exe',
    artifactKeyId: 'artifact-2026-01',
    artifactPublicKey: TAURI_PUBLIC_KEY,
    metadataKeyId: 'metadata-2026-01',
    publishedAt: '2026-07-30T00:00:00.000Z',
  });
  const verified = await verifyReleaseAssets({
    ...f,
    releaseDir: f.outDir,
  });
  assert.equal(verified.descriptor.sequence, 42);
  assert.deepEqual(
    verified.descriptor.compatibility[0].readableSourceVersions,
    [0, 1],
  );
  assert.equal(verified.descriptor.compatibility[0].role, 'AUTHORED');
  assert.equal(verified.descriptor.compatibility[0].formatVersion, 1);
  assert.equal(verified.descriptor.metadataKeyId, 'metadata-2026-01');
  assert.equal(verified.descriptor.metadataRootPolicy, 'OFFLINE_LONG_LIVED_V1');
  assert.equal(verified.descriptor.artifact.size, 4);
  assert.equal(verified.descriptor.artifact.keyId, 'artifact-2026-01');
});

test('compatibility baseline keeps the installed row set and uses current readable versions', async () => {
  const f = await fixture();
  const register = JSON.parse(await readFile(f.compatibilityRegisterPath, 'utf8'));
  const compatibilityBaselinePath = path.join(path.dirname(f.outDir), 'baseline.json');
  await writeFile(compatibilityBaselinePath, JSON.stringify(register));
  register.durableStores[0].currentVersion = 2;
  register.durableStores[0].readableLegacyVersions = [0, 1];
  register.durableStores.push({ ...register.durableStores[0], id: 'operations-db' });
  await writeFile(f.compatibilityRegisterPath, JSON.stringify(register));
  const { descriptor } = await buildReleaseAssets({
    ...f,
    compatibilityBaselinePath,
    version: '1.2.3',
    sequence: 42,
    installerUrl: 'https://example.invalid/JustSearch-setup.exe',
    artifactKeyId: 'artifact-2026-01',
    artifactPublicKey: TAURI_PUBLIC_KEY,
    metadataKeyId: 'metadata-2026-01',
  });
  assert.deepEqual(descriptor.compatibility.map((row) => row.ownerId), ['preferences']);
  assert.equal(descriptor.compatibility[0].formatVersion, 2);
  assert.deepEqual(descriptor.compatibility[0].readableSourceVersions, [0, 1, 2]);
  await verifyReleaseAssets({ ...f, releaseDir: f.outDir });
});

for (const field of ['owner', 'recoverability', 'reconciliation']) {
  test(`compatibility baseline refuses changed ${field}`, async () => {
    const f = await fixture();
    const register = JSON.parse(await readFile(f.compatibilityRegisterPath, 'utf8'));
    const compatibilityBaselinePath = path.join(path.dirname(f.outDir), 'baseline.json');
    await writeFile(compatibilityBaselinePath, JSON.stringify(register));
    register.durableStores[0][field] = 'CHANGED';
    await writeFile(f.compatibilityRegisterPath, JSON.stringify(register));
    await assert.rejects(buildReleaseAssets({
      ...f,
      compatibilityBaselinePath,
      version: '1.2.3',
      sequence: 42,
      installerUrl: 'https://example.invalid/JustSearch-setup.exe',
      artifactKeyId: 'artifact-2026-01',
      artifactPublicKey: TAURI_PUBLIC_KEY,
      metadataKeyId: 'metadata-2026-01',
    }), /baseline.*preferences.*identity/);
  });
}

test('tampered descriptor fails metadata verification', async () => {
  const f = await fixture();
  await buildReleaseAssets({
    ...f,
    version: '1.2.3',
    sequence: 42,
    installerUrl: 'https://example.invalid/JustSearch-setup.exe',
    artifactKeyId: 'artifact-2026-01',
    artifactPublicKey: TAURI_PUBLIC_KEY,
    metadataKeyId: 'metadata-2026-01',
  });
  const descriptorPath = path.join(f.outDir, 'release.v1.json');
  const descriptor = JSON.parse(await readFile(descriptorPath, 'utf8'));
  descriptor.sequence = 41;
  await writeFile(descriptorPath, `${JSON.stringify(descriptor)}\n`);
  await assert.rejects(
    verifyReleaseAssets({ ...f, releaseDir: f.outDir }),
    /metadata signature verification failed/,
  );
});

test('non-ready compatibility register blocks publication', async () => {
  const f = await fixture();
  await writeFile(
    f.compatibilityRegisterPath,
    JSON.stringify({
      knownCompatibilityGaps: ['conversations'],
      durableStores: [],
    }),
  );
  await assert.rejects(
    buildReleaseAssets({
      ...f,
      version: '1.2.3',
      sequence: 42,
      installerUrl: 'https://example.invalid/JustSearch-setup.exe',
      artifactKeyId: 'artifact-2026-01',
      artifactPublicKey: TAURI_PUBLIC_KEY,
      metadataKeyId: 'metadata-2026-01',
    }),
    /not release-ready/,
  );
});

test('missing compatibility strategy blocks descriptor assembly', async () => {
  const f = await fixture();
  await writeFile(
    f.compatibilityRegisterPath,
    JSON.stringify({
      knownCompatibilityGaps: [],
      durableStores: [{
        id: 'preferences',
        owner: 'HEAD',
        status: 'READY',
        currentVersion: 1,
        readableLegacyVersions: [0],
        reconciliation: 'READ_V0_OR_V1',
      }],
    }),
  );
  await assert.rejects(
    buildReleaseAssets({
      ...f,
      version: '1.2.3',
      sequence: 42,
      installerUrl: 'https://example.invalid/JustSearch-setup.exe',
      artifactKeyId: 'artifact-2026-01',
      artifactPublicKey: TAURI_PUBLIC_KEY,
      metadataKeyId: 'metadata-2026-01',
    }),
    /recoverability must be non-blank/,
  );
});

test('downloaded-set verification rejects latest feed drift', async () => {
  const f = await fixture();
  await buildReleaseAssets({
    ...f,
    version: '1.2.3',
    sequence: 42,
    installerUrl: 'https://example.invalid/JustSearch-setup.exe',
    artifactKeyId: 'artifact-2026-01',
    artifactPublicKey: TAURI_PUBLIC_KEY,
    metadataKeyId: 'metadata-2026-01',
  });
  const latestPath = path.join(f.outDir, 'latest.json');
  const latest = JSON.parse(await readFile(latestPath, 'utf8'));
  latest.platforms['windows-x86_64'].url = 'https://example.invalid/other.exe';
  await writeFile(latestPath, `${JSON.stringify(latest)}\n`);
  await assert.rejects(
    verifyReleaseAssets({ ...f, releaseDir: f.outDir }),
    /not a closed set/,
  );
});

test('downloaded-set verification rejects artifact byte drift', async () => {
  const f = await fixture();
  await buildReleaseAssets({
    ...f,
    version: '1.2.3',
    sequence: 42,
    installerUrl: 'https://example.invalid/JustSearch-setup.exe',
    artifactKeyId: 'artifact-2026-01',
    artifactPublicKey: TAURI_PUBLIC_KEY,
    metadataKeyId: 'metadata-2026-01',
  });
  await writeFile(f.installerPath, 'tampered');
  await assert.rejects(
    verifyReleaseAssets({ ...f, releaseDir: f.outDir }),
    /digest or size/,
  );
});

test('compiled metadata root identity must match release verification key', async () => {
  const f = await fixture();
  await buildReleaseAssets({
    ...f,
    version: '1.2.3',
    sequence: 42,
    installerUrl: 'https://example.invalid/JustSearch-setup.exe',
    artifactKeyId: 'artifact-2026-01',
    artifactPublicKey: TAURI_PUBLIC_KEY,
    metadataKeyId: 'metadata-2026-01',
  });
  await assert.rejects(
    verifyReleaseAssets({
      ...f,
      releaseDir: f.outDir,
      expectedMetadataKeyId: 'metadata-emergency-rotation',
    }),
    /compiled root key id/,
  );
  await assert.rejects(
    verifyReleaseAssets({
      ...f,
      releaseDir: f.outDir,
      expectedMetadataPublicKeyBase64: Buffer.alloc(32, 7).toString('base64'),
    }),
    /compiled raw root public key/,
  );
});

test('artifact verification rejects a forged signature string and tampered bytes', () => {
  assert.throws(
    () => verifyTauriArtifactSignature(
      Buffer.from('test'),
      Buffer.from('not a minisign signature').toString('base64'),
      TAURI_PUBLIC_KEY,
    ),
    /invalid Minisign structure/,
  );
  assert.throws(
    () => verifyTauriArtifactSignature(
      Buffer.from('tampered'),
      TAURI_SIGNATURE,
      TAURI_PUBLIC_KEY,
    ),
    /signature verification failed/,
  );
});
