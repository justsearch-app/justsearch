// Test-only keys and signed bytes, shared by the Rust and Node conformance checks.
import { createPrivateKey, createPublicKey, createHash, sign } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { buildReleaseAssets } from './app-release-assets.mjs';

function testKey(label) {
  return createPrivateKey({ key: Buffer.concat([
    Buffer.from('302e020100300506032b657004220420', 'hex'),
    createHash('sha256').update(`justsearch-public-test-only:${label}`).digest(),
  ]), type: 'pkcs8', format: 'der' });
}
export function signArtifact(bytes) {
  const privateKey = testKey('artifact');
  const raw = createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).subarray(-32);
  const keyId = Buffer.alloc(8, 4);
  const rawSignature = sign(null, bytes, privateKey);
  const comment = 'test fixture only';
  return {
    artifactPublicKey: Buffer.from(`untrusted comment: test only\n${Buffer.concat([Buffer.from('Ed'), keyId, raw]).toString('base64')}\n`).toString('base64'),
    signature: Buffer.from(`untrusted comment: test only\n${Buffer.concat([Buffer.from('Ed'), keyId, rawSignature]).toString('base64')}\ntrusted comment: ${comment}\n${sign(null, Buffer.concat([rawSignature, Buffer.from(comment)]), privateKey).toString('base64')}\n`).toString('base64'),
  };
}
export async function generateFixture(directory) {
  await mkdir(directory, { recursive: true });
  const privateKey = testKey('metadata');
  const metadataPrivateKeyPath = path.join(directory, 'metadata-private.pem');
  const metadataPublicKeyPath = path.join(directory, 'metadata-public.pem');
  const metadataPublicKey = createPublicKey(privateKey);
  await writeFile(metadataPrivateKeyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }));
  await writeFile(metadataPublicKeyPath, metadataPublicKey.export({ type: 'spki', format: 'pem' }));
  await writeFile(path.join(directory, 'metadata-public.base64'), metadataPublicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64'));
  const compatibilityRegisterPath = fileURLToPath(new URL('../../governance/store-recoverability.v1.json', import.meta.url));
  const compatibilityBaselinePath = path.join(directory, 'predecessor.json');
  await writeFile(compatibilityBaselinePath, await readFile(compatibilityRegisterPath));
  const debHeader = 'debian-binary'.padEnd(16) + '0'.padEnd(12) + '0'.padEnd(6) + '0'.padEnd(6)
    + '100644'.padEnd(8) + '4'.padEnd(10) + '`\n';
  const targets = {};
  for (const [target, bytes] of [
    ['windows-x86_64', Buffer.from('MZfixture')],
    ['linux-x86_64', Buffer.from(`!<arch>\n${debHeader}2.0\n`)],
  ]) {
    const installerPath = path.join(directory, target === 'linux-x86_64' ? 'update.deb' : 'setup.exe');
    const artifactSignaturePath = `${installerPath}.sig`;
    const signed = signArtifact(bytes);
    await writeFile(installerPath, bytes);
    await writeFile(artifactSignaturePath, signed.signature);
    targets[target] = { installerPath, artifactSignaturePath,
      installerUrl: `https://example.invalid/${path.basename(installerPath)}`,
      artifactKeyId: 'test-artifact', artifactPublicKey: signed.artifactPublicKey };
  }
  const options = { targets, metadataPrivateKeyPath, metadataPublicKeyPath,
    compatibilityRegisterPath, compatibilityBaselinePath, compatibilityPredecessor: 'HEAD',
    outDir: directory, releaseDir: directory, version: '1.2.3', sequence: 42,
    metadataKeyId: 'test-metadata', expectedMetadataKeyId: 'test-metadata',
    publishedAt: '2026-10-27T00:00:00Z' };
  await buildReleaseAssets(options);
  return options;
}
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  await generateFixture(process.argv[2]);
}
