import {
  createHash,
  createPrivateKey,
  createPublicKey,
  sign,
  verify,
} from 'node:crypto';
import {
  mkdir,
  readFile,
  writeFile,
  rm,
} from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { checkRetiredDurableStores } from '../ci/check-store-recoverability.mjs';

const MINISIGN_ED25519_SPKI_PREFIX = Buffer.from(
  '302a300506032b6570032100',
  'hex',
);
const TRUSTED_COMMENT_PREFIX = 'trusted comment: ';

export function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`;
  }
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

export async function loadReleaseCompatibility(compatibilityRegisterPath, compatibilityBaselinePath, predecessorRef) {
  const register = JSON.parse(await readFile(compatibilityRegisterPath, 'utf8'));
  if ((register.knownCompatibilityGaps ?? []).length > 0) {
    throw new Error(
      `compatibility register is not release-ready: ${register.knownCompatibilityGaps.join(', ')}`,
    );
  }
  let compatibility = (register.durableStores ?? []).map((store) => {
    if (store.status !== 'READY') {
      throw new Error(`durable store ${store.id} is not READY`);
    }
    for (const [field, value] of Object.entries({
      id: store.id,
      owner: store.owner,
      recoverability: store.recoverability,
      reconciliation: store.reconciliation,
    })) {
      requireNonBlank(`durable store ${store.id ?? '<missing>'} ${field}`, value);
    }
    if (!Number.isSafeInteger(store.currentVersion) || store.currentVersion < 0) {
      throw new Error(`durable store ${store.id} has an invalid currentVersion`);
    }
    return {
      ownerId: store.id,
      owner: store.owner,
      role: store.recoverability,
      formatVersion: store.currentVersion,
      readableSourceVersions: [...new Set([
        ...(store.readableLegacyVersions ?? []),
        store.currentVersion,
      ])].sort((a, b) => a - b),
      reconciliationStrategy: store.reconciliation,
    };
  });

  const currentById = new Map(compatibility.map((row) => [row.ownerId, row]));
  if (currentById.size !== compatibility.length) {
    throw new Error('compatibility register contains duplicate durable store ids');
  }
  const retirementFailures = checkRetiredDurableStores(register);
  if (retirementFailures.length) throw new Error(retirementFailures.join(' '));
  const retiredById = new Map((register.retiredDurableStores ?? []).map(store => [store.id, store]));
  let baseline;
  if (predecessorRef) {
    requireNonBlank('compatibility predecessor ref', predecessorRef);
    const root = fileURLToPath(new URL('../../', import.meta.url));
    baseline = JSON.parse(execFileSync('git', ['show', '--end-of-options',
      `${predecessorRef}:governance/store-recoverability.v1.json`], { cwd: root, encoding: 'utf8' }));
  }
  if (compatibilityBaselinePath) {
    const supplied = JSON.parse(await readFile(compatibilityBaselinePath, 'utf8'));
    if (baseline && canonicalJson(baseline) !== canonicalJson(supplied)) {
      throw new Error('compatibility baseline is not the complete named predecessor register');
    }
    baseline = supplied;
  }
  if (baseline) {
    if (!Array.isArray(baseline.durableStores) || baseline.durableStores.length === 0) {
      throw new Error('compatibility baseline must contain its installed durable stores');
    }
    const seen = new Set();
    compatibility = baseline.durableStores.map((store) => {
      requireNonBlank('compatibility baseline store id', store.id);
      if (seen.has(store.id)) {
        throw new Error(`compatibility baseline repeats durable store ${store.id}`);
      }
      seen.add(store.id);
      let current = currentById.get(store.id);
      if (!current) {
        const retired = retiredById.get(store.id);
        if (!retired) {
          throw new Error(`compatibility baseline store ${store.id} is missing from current register`);
        }
        if (store.currentVersion !== retired.currentVersion) {
          throw new Error(`compatibility baseline store ${store.id} changes retired version`);
        }
        current = {
          ownerId: retired.id, owner: retired.owner, role: retired.recoverability,
          formatVersion: retired.currentVersion, readableSourceVersions: [retired.currentVersion],
          reconciliationStrategy: retired.reconciliation,
        };
      }
      if (store.owner !== current.owner
          || store.recoverability !== current.role
          || store.reconciliation !== current.reconciliationStrategy) {
        throw new Error(`compatibility baseline store ${store.id} changes identity`);
      }
      if (!Number.isSafeInteger(store.currentVersion) || store.currentVersion < 0
          || !current.readableSourceVersions.includes(store.currentVersion)) {
        throw new Error(`compatibility baseline store ${store.id} has an unreadable source version`);
      }
      return current;
    });
  }

  return compatibility;
}

const TARGETS = ['windows-x86_64', 'linux-x86_64'];

function targetInputs(options) {
  // Keep the existing Windows CLI usable while the release workflow is combined.
  return options.targets ?? {
    'windows-x86_64': {
      installerPath: options.installerPath,
      artifactSignaturePath: options.artifactSignaturePath,
      installerUrl: options.installerUrl,
      artifactKeyId: options.artifactKeyId,
      artifactPublicKey: options.artifactPublicKey,
    },
  };
}

export async function buildReleaseAssets(options) {
  const { metadataPrivateKeyPath, compatibilityRegisterPath, compatibilityBaselinePath,
    outDir, version, sequence, metadataKeyId, publishedAt = new Date().toISOString(),
    notes = '' } = options;
  if (options.qualificationMode || process.env.JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE === '1') {
    throw new Error('qualification-mode builds must not produce release metadata');
  }
  requireNonBlank('version', version);
  requireNonBlank('metadataKeyId', metadataKeyId);
  if (!Number.isSafeInteger(sequence) || sequence <= 0) {
    throw new Error('sequence must be a positive safe integer');
  }
  const targets = {};
  const platforms = {};
  for (const [target, input] of Object.entries(targetInputs(options))) {
    if (!TARGETS.includes(target)) throw new Error(`unsupported release target: ${target}`);
    for (const field of ['installerUrl', 'artifactKeyId', 'artifactPublicKey']) {
      requireNonBlank(`${target} ${field}`, input[field]);
    }
    if (new URL(input.installerUrl).protocol !== 'https:') throw new Error('artifact URL must use HTTPS');
    const installer = await readFile(input.installerPath);
    const signature = (await readFile(input.artifactSignaturePath, 'utf8')).trim();
    const publicKey = input.artifactPublicKey.trim();
    verifyTauriArtifactSignature(installer, signature, publicKey);
    targets[target] = {
      artifact: { url: input.installerUrl,
        sha256: createHash('sha256').update(installer).digest('hex'), size: installer.length,
        signature, keyId: input.artifactKeyId, publicKey },
      compatibility: await loadReleaseCompatibility(compatibilityRegisterPath,
        input.compatibilityBaselinePath ?? compatibilityBaselinePath,
        input.compatibilityPredecessor ?? options.compatibilityPredecessor),
    };
    platforms[target] = { signature, url: input.installerUrl };
  }
  if (!Object.keys(targets).length) throw new Error('release must have at least one target');
  const descriptor = { schemaVersion: 2, sequence, version, channel: 'stable', metadataKeyId,
    metadataRootPolicy: 'OFFLINE_LONG_LIVED_V1', publishedAt, targets };
  const descriptorBytes = Buffer.from(`${canonicalJson(descriptor)}\n`);
  const metadataSignature = sign(null, descriptorBytes,
    createPrivateKey(await readFile(metadataPrivateKeyPath, 'utf8'))).toString('base64');
  const latest = { version, notes, pub_date: publishedAt, platforms };
  await mkdir(outDir, { recursive: true });
  // A reused output directory must not accidentally offer v2 to an installed v0.3.0 client.
  await rm(path.join(outDir, 'release.v1.json'), { force: true });
  await rm(path.join(outDir, 'release.v1.json.sig'), { force: true });
  await Promise.all([
    writeFile(path.join(outDir, 'release.v2.json'), descriptorBytes),
    writeFile(path.join(outDir, 'release.v2.json.sig'), `${metadataSignature}\n`),
    writeFile(path.join(outDir, 'latest.json'), `${JSON.stringify(latest, null, 2)}\n`),
  ]);
  return { descriptor, latest, metadataSignature };
}

export async function verifyReleaseAssets(options) {
  const { metadataPublicKeyPath, releaseDir, expectedMetadataKeyId,
    expectedMetadataPublicKeyBase64 } = options;
  const publicKeyPem = await readFile(metadataPublicKeyPath, 'utf8');
  const descriptorBytes = await readFile(path.join(releaseDir, 'release.v2.json'));
  const signatureRaw = await readFile(path.join(releaseDir, 'release.v2.json.sig'), 'utf8');
  const latest = JSON.parse(await readFile(path.join(releaseDir, 'latest.json'), 'utf8'));
  if (!verify(null, descriptorBytes, createPublicKey(publicKeyPem),
    Buffer.from(signatureRaw.trim(), 'base64'))) {
    throw new Error('release metadata signature verification failed');
  }
  const descriptor = JSON.parse(descriptorBytes);
  if (expectedMetadataKeyId != null && descriptor.metadataKeyId !== expectedMetadataKeyId) {
    throw new Error('release metadata key id does not match the compiled root key id');
  }
  if (expectedMetadataPublicKeyBase64 != null) {
    const raw = createPublicKey(publicKeyPem).export({ type: 'spki', format: 'der' }).subarray(-32);
    if (raw.toString('base64') !== expectedMetadataPublicKeyBase64.trim()) {
      throw new Error('metadata PEM does not match the compiled raw root public key');
    }
  }
  if (descriptor.schemaVersion !== 2 || descriptor.channel !== 'stable'
    || !descriptor.metadataKeyId?.trim() || descriptor.metadataRootPolicy !== 'OFFLINE_LONG_LIVED_V1'
    || !descriptor.targets || !Object.keys(descriptor.targets).length
    || Object.keys(descriptor.targets).some(target => !TARGETS.includes(target))) {
    throw new Error('release descriptor schema/channel/target/key mismatch');
  }
  if (!Number.isSafeInteger(descriptor.sequence) || descriptor.sequence <= 0
      || descriptor.sequence < (options.minimumSequence ?? 0)) {
    throw new Error('release descriptor sequence is invalid or older than accepted sequence');
  }
  const inputs = targetInputs(options);
  if (Object.keys(inputs).sort().join() !== Object.keys(descriptor.targets).sort().join()
      || Object.keys(latest.platforms ?? {}).sort().join() !== Object.keys(inputs).sort().join()) {
    throw new Error('release target set does not match supplied artifacts/latest.json');
  }
  for (const [target, input] of Object.entries(inputs)) {
    const { artifact, compatibility } = descriptor.targets[target];
    const installer = await readFile(input.installerPath);
    const artifactSignature = (await readFile(input.artifactSignaturePath, 'utf8')).trim();
    if (artifact?.sha256 !== createHash('sha256').update(installer).digest('hex')
      || artifact?.size !== installer.length) {
      throw new Error('installer digest or size does not match release descriptor');
    }
    if ((input.artifactKeyId != null && artifact.keyId !== input.artifactKeyId)
      || (input.artifactPublicKey != null && artifact.publicKey !== input.artifactPublicKey.trim())) {
      throw new Error('artifact signing key does not match the compiled key');
    }
    verifyTauriArtifactSignature(installer, artifactSignature, artifact.publicKey);
    const platform = latest.platforms[target];
    if (descriptor.version !== latest.version || artifact.url !== platform.url
      || artifact.signature !== artifactSignature || platform.signature !== artifactSignature) {
      throw new Error('latest.json, descriptor, and artifact signature are not a closed set');
    }
    const owners = new Set();
    for (const owner of compatibility ?? []) {
      if (!owner.ownerId || owners.has(owner.ownerId)) {
        throw new Error(`duplicate or missing compatibility owner: ${owner.ownerId}`);
      }
      owners.add(owner.ownerId);
      if (!owner.owner || !owner.role || !owner.reconciliationStrategy
        || !owner.readableSourceVersions?.includes(owner.formatVersion)) {
        throw new Error(`compatibility owner ${owner.ownerId} has incomplete strategy data`);
      }
    }
    if (options.compatibilityRegisterPath) {
      const expected = await loadReleaseCompatibility(options.compatibilityRegisterPath,
        input.compatibilityBaselinePath ?? options.compatibilityBaselinePath,
        input.compatibilityPredecessor ?? options.compatibilityPredecessor);
      if (canonicalJson(compatibility) !== canonicalJson(expected)) {
        throw new Error('descriptor compatibility does not cover the complete predecessor register');
      }
    }
  }
  return { descriptor, latest };
}

/**
 * Mirror Tauri updater 2.x verification: both inputs are base64 wrappers around
 * Minisign text files, and legacy Ed plus prehashed ED signatures are accepted.
 */
export function verifyTauriArtifactSignature(
  artifactBytes,
  encodedSignature,
  encodedPublicKey,
) {
  requireNonBlank('artifactSignature', encodedSignature);
  requireNonBlank('artifactPublicKey', encodedPublicKey);

  const publicKeyText = decodeUtf8Base64('artifact public key', encodedPublicKey);
  const signatureText = decodeUtf8Base64('artifact signature', encodedSignature);
  const publicLines = publicKeyText.trimEnd().split(/\r?\n/);
  const signatureLines = signatureText.trimEnd().split(/\r?\n/);
  if (publicLines.length !== 2 || signatureLines.length !== 4) {
    throw new Error('Tauri artifact key or signature has invalid Minisign structure');
  }

  const publicPacket = decodePacket('Minisign public key', publicLines[1], 42);
  const signaturePacket = decodePacket('Minisign signature', signatureLines[1], 74);
  const globalSignature = decodePacket(
    'Minisign global signature',
    signatureLines[3],
    64,
  );
  const publicAlgorithm = publicPacket.subarray(0, 2).toString('ascii');
  const signatureAlgorithm = signaturePacket.subarray(0, 2).toString('ascii');
  if (
    !['Ed', 'ED'].includes(publicAlgorithm)
    || !['Ed', 'ED'].includes(signatureAlgorithm)
  ) {
    throw new Error('Tauri artifact signature uses an unsupported Minisign algorithm');
  }
  if (!publicPacket.subarray(2, 10).equals(signaturePacket.subarray(2, 10))) {
    throw new Error('Tauri artifact signature key id does not match its public key');
  }
  if (!signatureLines[2].startsWith(TRUSTED_COMMENT_PREFIX)) {
    throw new Error('Tauri artifact signature is missing its trusted comment');
  }

  const publicKey = createPublicKey({
    key: Buffer.concat([
      MINISIGN_ED25519_SPKI_PREFIX,
      publicPacket.subarray(10, 42),
    ]),
    format: 'der',
    type: 'spki',
  });
  const message =
    signatureAlgorithm === 'ED'
      ? createHash('blake2b512').update(artifactBytes).digest()
      : artifactBytes;
  const rawSignature = signaturePacket.subarray(10, 74);
  if (!verify(null, message, publicKey, rawSignature)) {
    throw new Error('Tauri artifact signature verification failed');
  }
  const globalMessage = Buffer.concat([
    rawSignature,
    Buffer.from(signatureLines[2].slice(TRUSTED_COMMENT_PREFIX.length)),
  ]);
  if (!verify(null, globalMessage, publicKey, globalSignature)) {
    throw new Error('Tauri artifact trusted-comment signature verification failed');
  }
}

function decodeUtf8Base64(name, value) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(
      Buffer.from(value, 'base64'),
    );
  } catch (error) {
    throw new Error(`${name} is not base64-encoded UTF-8: ${error.message}`);
  }
}

function decodePacket(name, value, expectedLength) {
  const packet = Buffer.from(value, 'base64');
  if (packet.length !== expectedLength) {
    throw new Error(`${name} has invalid length`);
  }
  return packet;
}

function requireNonBlank(name, value) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} must be non-blank`);
  }
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) throw new Error(`unexpected argument: ${arg}`);
    const key = arg.slice(2);
    const value = argv[i + 1];
    if (value == null || value.startsWith('--')) throw new Error(`missing value for --${key}`);
    out[key] = value;
    i += 1;
  }
  return out;
}

async function main() {
  const [command, ...rest] = process.argv.slice(2);
  const args = parseArgs(rest);
  const targets = args.targets ? JSON.parse(await readFile(args.targets, 'utf8')) : undefined;
  if (command === 'build') {
    await buildReleaseAssets({
      targets,
      installerPath: args.installer,
      artifactSignaturePath: args['artifact-signature'],
      metadataPrivateKeyPath: args['metadata-private-key'],
      compatibilityRegisterPath: args.compatibility,
      compatibilityBaselinePath: args['compat-baseline'],
      compatibilityPredecessor: args['compat-predecessor'],
      outDir: args['out-dir'],
      version: args.version,
      sequence: Number(args.sequence),
      installerUrl: args.url,
      artifactKeyId: args['artifact-key-id'],
      artifactPublicKey: args['artifact-public-key'],
      metadataKeyId: args['metadata-key-id'],
      publishedAt: args['published-at'],
      notes: args.notes ?? '',
    });
  } else if (command === 'check-compatibility') {
    await loadReleaseCompatibility(args.compatibility, args['compat-baseline'], args['compat-predecessor']);
  } else if (command === 'verify') {
    await verifyReleaseAssets({
      targets,
      compatibilityRegisterPath: args.compatibility,
      compatibilityBaselinePath: args['compat-baseline'],
      compatibilityPredecessor: args['compat-predecessor'],
      installerPath: args.installer,
      artifactSignaturePath: args['artifact-signature'],
      metadataPublicKeyPath: args['metadata-public-key'],
      releaseDir: args['release-dir'],
      expectedMetadataKeyId: args['metadata-key-id'],
      expectedMetadataPublicKeyBase64: args['metadata-root-public-key'],
    });
  } else {
    throw new Error('usage: app-release-assets.mjs <build|verify|check-compatibility> [options]');
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((error) => {
    console.error(`app-release-assets: ${error.message}`);
    process.exitCode = 1;
  });
}
