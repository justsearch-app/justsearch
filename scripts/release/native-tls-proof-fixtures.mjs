// Ephemeral build carrier helper. Uses the existing deterministic PUBLIC TEST keys.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const descriptorUrl = 'https://github.com/justsearch-app/justsearch/releases/latest/download/release.v2.json';
const keyId = 'native-tls-public-test-only';

export async function initialize(source, privateDirectory, publicDirectory) {
  const { generateFixture, signArtifact } = await import(pathToFileURL(path.join(source, 'scripts/release/updater-v2-fixture.mjs')));
  // Keep the generated signing ability in runner temp; never export this directory.
  await generateFixture(privateDirectory);
  await mkdir(publicDirectory, { recursive: true });
  const pins = {
    classification: 'PUBLIC TEST ONLY — never publish as a release',
    JUSTSEARCH_RELEASE_DESCRIPTOR_URL: descriptorUrl,
    JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY: (await readFile(path.join(privateDirectory, 'metadata-public.base64'), 'utf8')).trim(),
    JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID: keyId,
    JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY: signArtifact(Buffer.from('public test pin')).artifactPublicKey,
    JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID: keyId,
  };
  await writeFile(path.join(publicDirectory, 'trust-pins.json'), `${JSON.stringify(pins, null, 2)}\n`);
  await copyFile(path.join(privateDirectory, 'metadata-public.pem'), path.join(publicDirectory, 'metadata-public.pem'));
  return pins;
}

export async function assemble(source, privateDirectory, publicDirectory, packageDirectory, version, platform, baseline) {
  assert.match(version, /^0\.3\.(80|81|82)$/);
  assert.ok(['linux', 'windows'].includes(platform));
  const { signArtifact } = await import(pathToFileURL(path.join(source, 'scripts/release/updater-v2-fixture.mjs')));
  const { buildReleaseAssets, verifyReleaseAssets } = await import(pathToFileURL(path.join(source, 'scripts/release/app-release-assets.mjs')));
  const pins = JSON.parse(await readFile(path.join(publicDirectory, 'trust-pins.json'), 'utf8'));
  const receipt = JSON.parse(await readFile(path.join(packageDirectory, 'package.json'), 'utf8'));
  assert.equal(receipt.version, version);
  assert.equal(receipt.platform, platform);
  assert.equal(path.basename(receipt.installer), receipt.installer, 'installer must be a safe basename');
  assert.ok(!receipt.installer.includes('\\') && !['.', '..'].includes(receipt.installer));
  assert.ok(receipt.installer.endsWith(platform === 'linux' ? '.deb' : '-setup.exe'));
  const installerPath = path.join(packageDirectory, receipt.installer);
  const bytes = await readFile(installerPath);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), receipt.sha256);
  assert.equal(bytes.length, receipt.size);
  const signed = signArtifact(bytes);
  assert.equal(signed.artifactPublicKey, pins.JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY);
  const artifactSignaturePath = `${installerPath}.sig`;
  await writeFile(artifactSignaturePath, `${signed.signature}\n`);
  const options = {
    outDir: packageDirectory, releaseDir: packageDirectory,
    version, sequence: Number(version.split('.').at(-1)),
    publishedAt: '2026-11-06T00:00:00Z',
    notes: 'PUBLIC TEST ONLY: native TLS/update proof; not a release.',
    metadataPrivateKeyPath: path.join(privateDirectory, 'metadata-private.pem'),
    metadataPublicKeyPath: path.join(publicDirectory, 'metadata-public.pem'),
    metadataKeyId: keyId, expectedMetadataKeyId: keyId,
    expectedMetadataPublicKeyBase64: pins.JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY,
    compatibilityRegisterPath: path.join(source, 'governance/store-recoverability.v1.json'),
    compatibilityBaselinePath: baseline,
    targets: { [`${platform}-x86_64`]: {
      installerPath, artifactSignaturePath,
      installerUrl: `https://github.com/justsearch-app/justsearch/releases/download/v${version}/${encodeURIComponent(receipt.installer)}`,
      artifactKeyId: keyId, artifactPublicKey: signed.artifactPublicKey,
    } },
  };
  await buildReleaseAssets(options);
  await verifyReleaseAssets(options);
  await writeFile(path.join(packageDirectory, 'fixture-layout.json'), `${JSON.stringify({
    descriptorUrl,
    feedUrl: descriptorUrl.replace('release.v2.json', 'latest.json'),
    descriptorSignatureUrl: `${descriptorUrl}.sig`,
    installerUrl: options.targets[`${platform}-x86_64`].installerUrl,
    placement: 'Place release.v2.json, release.v2.json.sig and latest.json at the latest/download paths; place the exact installer at installerUrl. Runtime mirror and TLS certificates are separate.',
    checks: 'Existing app-release-assets verifies descriptor signature, exact installer digest/size, artifact signature and closed descriptor/feed/installer set.',
  }, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'initialize' && args.length === 3) await initialize(...args);
  else if (command === 'assemble' && args.length === 7) await assemble(...args);
  else throw new Error('Expected initialize SOURCE PRIVATE PUBLIC or assemble SOURCE PRIVATE PUBLIC PACKAGE VERSION PLATFORM BASELINE');
}
