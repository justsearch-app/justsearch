#!/usr/bin/env node
// One immutable candidate. gh is an adapter; the producer and verifier also run offline.
import { createHash, createPrivateKey, createPublicKey } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir, copyFile, lstat, mkdtemp, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import os from 'node:os';
import { createReadStream } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { buildReleaseAssets, verifyReleaseAssets } from './app-release-assets.mjs';
import { extractVersion } from './release-changelog.mjs';

export const DESCRIPTOR_URL = 'https://github.com/justsearch-app/justsearch/releases/latest/download/release.v2.json';
const TARGETS = ['linux-x86_64', 'windows-x86_64'];
async function fileHash(file) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest('hex');
}
function required(name, value) {
  if (typeof value !== 'string' || !value.trim()) throw Error(`${name} is required`);
  return value;
}
function safeName(name) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._+-]*$/.test(name)) throw Error(`unsafe asset name: ${name}`);
  return name;
}
function identity(o) {
  if (!/^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(o.tag)
    || o.tag !== `v${o.version}` || !/^[0-9a-f]{40}$/.test(o.commit)
    || !/^\d+$/.test(o.runId) || !/^[\w.-]+\/[\w.-]+$/.test(o.repo)) throw Error('invalid candidate identity');
}
async function validatePublicKeys(o) {
  if (o.qualificationMode || process.env.JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE) throw Error('qualification mode cannot ship');
  for (const name of ['artifactPublicKey', 'artifactKeyId', 'metadataKeyId', 'metadataRootPublicKey']) required(name, o[name]);
  required('metadata public key', o.metadataPublicKeyPath);
  const publicKey = createPublicKey(await readFile(o.metadataPublicKeyPath));
  if (publicKey.asymmetricKeyType !== 'ed25519') throw Error('metadata public key must be ed25519');
  if (publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64') !== o.metadataRootPublicKey) {
    throw Error('metadata root public key does not match signing key');
  }
}
export async function validateKeys(o) {
  await validatePublicKeys(o);
  const pem = o.metadataPrivateKeyPem ?? await readFile(required('metadata private key', o.metadataPrivateKeyPath));
  const privateKey = createPrivateKey(pem);
  const publicKey = createPublicKey(await readFile(o.metadataPublicKeyPath));
  if (privateKey.asymmetricKeyType !== 'ed25519'
    || !createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).equals(publicKey.export({ type: 'spki', format: 'der' }))) {
    throw Error('metadata signing key pair mismatch');
  }
}
export async function buildCandidate(o) {
  identity(o);
  try { await lstat(o.outDir); throw Error('refusing to reuse an existing candidate directory'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await validateKeys(o);
  if (Object.keys(o.targets).sort().join() !== TARGETS.join()) throw Error('candidate requires both targets');
  // Admission happens before creating output. Never clean or merge an existing candidate.
  const targets = {};
  for (const target of TARGETS) {
    const input = o.targets[target];
    const name = safeName(path.basename(input.installerPath));
    if (!name.endsWith(target === 'linux-x86_64' ? '.deb' : '.exe')) throw Error('wrong artifact extension');
    targets[target] = { ...input, artifactKeyId: o.artifactKeyId, artifactPublicKey: o.artifactPublicKey,
      installerUrl: `https://github.com/${o.repo}/releases/download/${o.tag}/${name}` };
  }
  for (const platform of ['windows', 'linux']) {
    const check = JSON.parse(await readFile(o.evidence[platform]));
    if (check.passed !== true || check.commit !== o.commit || check.runId !== o.runId || check.platform !== platform) {
      throw Error(`${platform} packaged smoke failed or belongs to another candidate`);
    }
  }
  await mkdir(o.outDir); // exclusive: EEXIST is intentional, including for a partial failed build
  for (const input of Object.values(targets)) {
    const name = path.basename(input.installerPath);
    await copyFile(input.installerPath, path.join(o.outDir, name));
    await copyFile(input.artifactSignaturePath, path.join(o.outDir, `${name}.sig`));
  }
  await copyFile(o.mcpb, path.join(o.outDir, 'justsearch-mcp.mcpb'));
  await copyFile(o.compatibilityRegisterPath, path.join(o.outDir, 'store-recoverability.v1.json'));
  await copyFile(o.compatibilityBaselinePath, path.join(o.outDir, 'compatibility-baseline.json'));
  for (const platform of ['windows', 'linux']) await copyFile(o.evidence[platform], path.join(o.outDir, `smoke-${platform}.json`));
  await buildReleaseAssets({ ...o, targets });
  await verifyReleaseAssets({ ...o, targets, releaseDir: o.outDir,
    expectedMetadataKeyId: o.metadataKeyId, expectedMetadataPublicKeyBase64: o.metadataRootPublicKey });
  const assets = (await readdir(o.outDir)).sort();
  const candidate = { schemaVersion: 1, repo: o.repo, tag: o.tag, version: o.version,
    commit: o.commit, runId: o.runId, qualificationMode: false,
    installers: Object.fromEntries(Object.entries(targets).map(([target, input]) => [target, path.basename(input.installerPath)])),
    assets };
  await writeFile(path.join(o.outDir, 'candidate.json'), `${JSON.stringify(candidate, null, 2)}\n`, { flag: 'wx' });
  return candidate;
}
export async function writeChecksums(directory) {
  const names = (await readdir(directory)).filter(n => n !== 'SHA256SUMS').sort();
  const lines = [];
  for (const name of names) {
    safeName(name);
    if (!(await lstat(path.join(directory, name))).isFile()) throw Error('release assets must be regular files');
    lines.push(`${await fileHash(path.join(directory, name))}  ${name}`);
  }
  await writeFile(path.join(directory, 'SHA256SUMS'), `${lines.join('\n')}\n`);
}
export async function verifyChecksums(directory) {
  const names = (await readdir(directory)).filter(n => n !== 'SHA256SUMS').sort();
  const lines = (await readFile(path.join(directory, 'SHA256SUMS'), 'utf8')).trim().split('\n');
  const listed = [];
  for (const line of lines) {
    const match = /^([a-f0-9]{64})  (.+)$/.exec(line);
    if (!match) throw Error('malformed checksum manifest');
    const name = safeName(match[2]); listed.push(name);
    if (!(await lstat(path.join(directory, name))).isFile()
      || await fileHash(path.join(directory, name)) !== match[1]) throw Error(`checksum mismatch: ${name}`);
  }
  if (listed.sort().join() !== names.join() || new Set(listed).size !== listed.length) throw Error('checksum asset set mismatch');
  return names;
}
export function verifyAttestation(file, c, directory) {
  execFileSync('gh', ['attestation', 'verify', file, '--bundle', path.join(directory, 'provenance.sigstore.json'),
    '--repo', c.repo, '--signer-workflow', `${c.repo}/.github/workflows/build-installer.yml`,
    '--source-digest', c.commit, '--source-ref', `refs/tags/${c.tag}`, '--deny-self-hosted-runners'], { stdio: 'pipe' });
}
export async function verifyCandidate(o) {
  const dir = o.releaseDir;
  for (const name of ['repo', 'metadataKeyId', 'metadataRootPublicKey', 'artifactKeyId', 'artifactPublicKey', 'metadataPublicKeyPath']) required(name, o[name]);
  const names = await verifyChecksums(dir);
  const c = JSON.parse(await readFile(path.join(dir, 'candidate.json')));
  identity(c);
  if (c.qualificationMode !== false || c.schemaVersion !== 1 || c.repo !== o.repo
    || c.tag !== o.expectedTag || c.commit !== o.expectedCommit) throw Error('candidate identity mismatch');
  const expected = [...c.assets, 'candidate.json', 'provenance.sigstore.json'].sort();
  if (expected.join() !== names.join() || new Set(expected).size !== expected.length) throw Error('candidate asset set mismatch');
  const checkAttestation = o.verifyAttestation ?? verifyAttestation;
  // The bundle cannot attest itself. SHA256SUMS covers the bundle; every payload,
  // including the inventory, is independently covered by the saved Sigstore bundle.
  for (const name of names.filter(n => n !== 'provenance.sigstore.json')) await checkAttestation(path.join(dir, name), c, dir);
  if (Object.keys(c.installers).sort().join() !== TARGETS.join()) throw Error('candidate must contain both targets');
  const targets = {};
  for (const target of TARGETS) {
    const name = safeName(c.installers[target]);
    targets[target] = { installerPath: path.join(dir, name), artifactSignaturePath: path.join(dir, `${name}.sig`),
      artifactKeyId: o.artifactKeyId, artifactPublicKey: o.artifactPublicKey };
  }
  const result = await verifyReleaseAssets({ targets, releaseDir: dir, metadataPublicKeyPath: o.metadataPublicKeyPath,
    expectedMetadataKeyId: o.metadataKeyId, expectedMetadataPublicKeyBase64: o.metadataRootPublicKey, minimumSequence: o.minimumSequence,
    compatibilityRegisterPath: path.join(dir, 'store-recoverability.v1.json'), compatibilityBaselinePath: path.join(dir, 'compatibility-baseline.json') });
  if (result.descriptor.version !== c.version) throw Error('candidate descriptor version mismatch');
  for (const target of TARGETS) {
    if (result.descriptor.targets[target].artifact.url !== `https://github.com/${c.repo}/releases/download/${c.tag}/${c.installers[target]}`) throw Error('candidate artifact URL mismatch');
  }
  return { ...result, candidate: c };
}
export const candidateMarker = c => `<!-- justsearch-candidate:${c.commit}:${c.runId} -->`;
export async function uploadDraft(o, remote) {
  const { candidate: c } = await verifyCandidate(o);
  const marker = candidateMarker(c);
  const names = (await readdir(o.releaseDir)).sort();
  let release = await remote.get();
  if (release && (!release.draft || release.tag_name !== c.tag || !release.body?.includes(marker))) throw Error('release belongs to another candidate or is already public');
  if (!release) release = await remote.create({ tag_name: c.tag, target_commitish: c.commit, body: `${marker}\n${o.notes ?? ''}`, name: c.tag, draft: true });
  if (release.assets.some(a => !names.includes(a.name)) || new Set(release.assets.map(a => a.name)).size !== release.assets.length) throw Error('draft contains another candidate asset set');
  for (const name of names) {
    const digest = await fileHash(path.join(o.releaseDir, name));
    if (release.assets.some(a => a.name === name)) {
      if (digest !== await remote.hash(name)) throw Error(`draft has different bytes: ${name}`);
    } else await remote.upload(name);
  }
  // A successful upload is not enough: download and compare the complete remote set.
  for (const name of names) if (await fileHash(path.join(o.releaseDir, name)) !== await remote.hash(name)) throw Error(`round-trip has different bytes: ${name}`);
}
export async function publishDraft(o, remote) {
  const { candidate: c } = await verifyCandidate(o);
  const release = await remote.get();
  if (!release || release.tag_name !== c.tag || !release.body?.includes(candidateMarker(c))) throw Error('draft candidate identity mismatch');
  if (/\b(?:Not tested|Known issues)\s*:\s*\[agent fills before final validation\]/i.test(release.body)) throw Error('unfilled release notes placeholder');
  const names = (await readdir(o.releaseDir)).sort();
  if (release.assets.map(a => a.name).sort().join() !== names.join()) throw Error('draft asset set mismatch');
  for (const name of names) {
    if (await fileHash(path.join(o.releaseDir, name)) !== await remote.hash(name)) throw Error('draft bytes changed before publication');
  }
  // After a lost promotion response, verification is repeated and no bytes are rewritten.
  if (!release.draft) return;
  await remote.promote();
}
function gh(args, encoding = 'utf8') { return execFileSync('gh', args, { encoding, stdio: ['ignore', 'pipe', 'pipe'] }); }
function remoteFor(o) {
  let release;
  const api = `/repos/${o.repo}/releases`;
  return {
    get: async () => {
      // Listing avoids interpreting an authentication/network error as "no draft".
      const releases = JSON.parse(gh(['api', '--paginate', '--slurp', `${api}?per_page=100`])).flat();
      const matches = releases.filter(r => r.tag_name === o.expectedTag);
      if (matches.length > 1) throw Error('multiple releases for candidate tag');
      release = matches[0]; return release;
    },
    create: async body => {
      release = JSON.parse(execFileSync('gh', ['api', api, '--method', 'POST', '--input', '-'], { input: JSON.stringify(body), encoding: 'utf8' }));
      return release;
    },
    hash: async name => {
      // A fresh directory prevents stale downloads from hiding a missing asset.
      // gh writes payloads to disk; installer bytes never enter a child-process buffer.
      safeName(name);
      const directory = await mkdtemp(path.join(os.tmpdir(), 'release-download-'));
      try {
        gh(['release', 'download', o.expectedTag, '--repo', o.repo, '--pattern', name, '--dir', directory]);
        return await fileHash(path.join(directory, name));
      } finally {
        await rm(directory, { recursive: true, force: true });
      }
    },
    promote: async () => { gh(['release', 'edit', o.expectedTag, '--repo', o.repo, '--draft=false', '--prerelease=false', '--latest']); },
    upload: async name => { gh(['release', 'upload', o.expectedTag, path.join(o.releaseDir, name), '--repo', o.repo]); },
  };
}
function envOptions() {
  return { repo: process.env.GITHUB_REPOSITORY, tag: process.env.GITHUB_REF_NAME,
    commit: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID,
    expectedTag: process.env.JUSTSEARCH_RELEASE_EXPECTED_TAG,
    expectedCommit: process.env.JUSTSEARCH_RELEASE_EXPECTED_COMMIT,
    artifactKeyId: process.env.JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID,
    artifactPublicKey: process.env.JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY,
    metadataKeyId: process.env.JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID,
    metadataRootPublicKey: process.env.JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY,
    metadataPrivateKeyPath: process.env.JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH,
    metadataPublicKeyPath: process.env.JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PATH };
}
async function main() {
  const [command, ...args] = process.argv.slice(2);
  const o = { ...envOptions(), ...(args[0]?.endsWith('.json') ? JSON.parse(await readFile(args[0])) : {}) };
  if (command === 'build') {
    const metadataPrivateKeyPem = required('METADATA_PRIVATE_KEY_PEM', process.env.METADATA_PRIVATE_KEY_PEM);
    return buildCandidate({ ...o, metadataPrivateKeyPem });
  }
  if (command === 'checksums') return writeChecksums(args[0]);
  if (command === 'verify' || command === 'upload' || command === 'publish') {
    o.releaseDir ??= args[0];
    o.expectedTag ??= o.tag;
    o.expectedCommit ??= o.commit;
    await verifyCandidate(o);
    if (command === 'upload') {
      const changelog = extractVersion(await readFile('CHANGELOG.md', 'utf8'), o.expectedTag.slice(1));
      const sums = await readFile(path.join(o.releaseDir, 'SHA256SUMS'), 'utf8');
      o.notes = `${changelog}\n\nWindows x86_64: NSIS installer. Linux x86_64: Ubuntu 24.04+ Debian package.\n\nPackaged checks: smoke-windows.json, smoke-linux.json. Provenance: provenance.sigstore.json.\n\nNot tested: [agent fills before final validation]\n\nKnown issues: [agent fills before final validation]\n\nVerify downloads with SHA256SUMS and the saved GitHub provenance bundle.\n\n\`\`\`text\n${sums}\`\`\`\n`;
      return uploadDraft(o, remoteFor(o));
    }
    if (command === 'publish') {
      // Another draft may have been qualified in parallel with this one. Only
      // published descriptors advance sequence, and stale drafts cannot ship.
      o.minimumSequence = Number(execFileSync(process.execPath,
        ['scripts/ci/derive-release-sequence.mjs', '--repo', o.repo, '--exclude-tag', o.expectedTag],
        { encoding: 'utf8', env: process.env }).trim());
      if (!Number.isSafeInteger(o.minimumSequence) || o.minimumSequence <= 0) throw Error('invalid publication sequence floor');
      return publishDraft(o, remoteFor(o));
    }
    return;
  }
  if (command === 'admit') {
    for (const name of ['HAS_CODESIGN_COMMAND', 'HAS_TAURI_KEY', 'HAS_METADATA_KEY']) {
      if (process.env[name] !== 'true') throw Error(`${name} must be true`);
    }
    o.version = (await readFile('gradle.properties', 'utf8')).match(/^version=(.+)$/m)?.[1].trim();
    identity(o); await validatePublicKeys(o);
    extractVersion(await readFile('CHANGELOG.md', 'utf8'), o.version);
    if (process.env.JUSTSEARCH_RELEASE_DESCRIPTOR_URL !== DESCRIPTOR_URL) throw Error('production descriptor URL is required');
    const prior = await remoteFor({ ...o, expectedTag: o.tag }).get();
    if (prior && (!prior.draft || !prior.body?.includes(candidateMarker(o)))) throw Error('tag already belongs to another candidate; use the next patch version');
    const runs = JSON.parse(gh(['api', '--paginate', '--slurp',
      `/repos/${o.repo}/actions/workflows/build-installer.yml/runs?event=push&branch=${encodeURIComponent(o.tag)}&per_page=100`])).flatMap(p => p.workflow_runs);
    if (runs.some(run => String(run.id) !== o.runId)) throw Error('tag already attempted by another run; use the next patch version');
    execFileSync('git', ['merge-base', '--is-ancestor', o.commit, 'origin/main']);
    console.log('Release admission passed'); return;
  }
  throw Error('usage: release-candidate.mjs admit|build CONFIG.json|checksums DIR|verify DIR|upload DIR|publish DIR');
}
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) main().catch(error => { console.error(error.message); process.exitCode = 1; });
