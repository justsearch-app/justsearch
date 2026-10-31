#!/usr/bin/env node
// Cross-platform implementation of sync-version.ps1; gradle.properties is canonical.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { packMcpb } from './pack-mcpb.mjs';

const args = process.argv.slice(2);
let root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
let dryRun = false;
let check = false;
let requireRelease = false;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--repo-root' && args[i + 1]) root = path.resolve(args[++i]);
  else if (args[i] === '--what-if') dryRun = true;
  else if (args[i] === '--check') { check = true; dryRun = true; }
  else if (args[i] === '--require-release-semver') requireRelease = true;
  else throw new Error(`Unknown or incomplete argument: ${args[i]}`);
}

const props = fs.readFileSync(path.join(root, 'gradle.properties'), 'utf8');
const version = props.match(/^\s*version\s*=\s*(.+)$/m)?.[1].trim();
if (!version || !/^\d+\.\d+\.\d+/.test(version)) throw new Error(`Gradle version '${version}' does not look like SemVer (expected x.y.z...).`);
if (requireRelease && (!/^\d+\.\d+\.\d+(-[A-Za-z0-9]+(\.[A-Za-z0-9]+)*)?$/.test(version) || /SNAPSHOT/i.test(version))) {
  throw new Error(`Release builds require SemVer x.y.z or x.y.z-PRERELEASE without SNAPSHOT. Got: '${version}'.`);
}
console.log(`Gradle canonical version: ${version}`);

const tauri = 'modules/shell/src-tauri/tauri.conf.json';
const cargo = 'modules/shell/src-tauri/Cargo.toml';
const updates = new Map();
function update(rel, pattern, replacement) {
  const before = updates.get(rel)?.next ?? fs.readFileSync(path.join(root, rel), 'utf8');
  if (!pattern.test(before)) throw new Error(`${rel}: required version/branding field is missing`);
  pattern.lastIndex = 0;
  const next = before.replace(pattern, replacement);
  const original = updates.get(rel)?.original ?? before;
  updates.set(rel, { original, next });
}
// Keep the existing PowerShell projections and branding text, including prereleases.
update(tauri, /"version"\s*:\s*"[^"]+"/g, `"version": "${version}"`);
update('modules/shell/package.json', /"version"\s*:\s*"[^"]+"/g, `"version": "${version}"`);
update(tauri, /"copyright"\s*:\s*"[^"]*"/g, `"copyright": "JustSearch ${version} - Copyright (c) JustSearch"`);
update(cargo, /^(\s*version\s*=\s*")[^"]+("\s*)$/gm, `$1${version}$2`);

update('modules/shell/src-tauri/Cargo.lock', /(name = "shell"\r?\nversion = ")[^"]+(")/, `$1${version}$2`);
update('THIRD_PARTY_NOTICES', /^(  shell )\S+( — )/m, `$1${version}$2`);
const lockPath = 'modules/shell/package-lock.json';
const lockOriginal = fs.readFileSync(path.join(root, lockPath), 'utf8');
const lock = JSON.parse(lockOriginal);
lock.version = version;
lock.packages[''].version = version;
updates.set(lockPath, { original: lockOriginal, next: JSON.stringify(lock, null, 2) + '\n' });

// The existing JSON-aware packer stamps both MCPB versions and the release URL,
// then syncs the hash even when only the bundle's source changed. Preflight before writing files.
function stamp(checkOnly) {
  const commands = [['--set-version', version, ...(checkOnly ? ['--dry-run'] : [])]];
  if (!checkOnly) commands.push(['--sync']);
  const output = [];
  for (const command of commands) {
    const result = spawnSync(process.execPath, [path.join(root, 'scripts/ci/pack-mcpb.mjs'), ...command], {
      env: { ...process.env, CHECK_MCPB_ROOT: root }, encoding: 'utf8',
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`pack-mcpb.mjs ${command[0]} failed (exit=${result.status}): ${result.stderr || result.stdout}`);
    output.push(result.stdout.trim());
  }
  return output.join('\n');
}
stamp(true);
if (check) {
  const drift = [...updates].filter(([, { original, next }]) => original !== next).map(([rel]) => rel);
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'packaging/mcpb/manifest.json'), 'utf8'));
  const server = JSON.parse(fs.readFileSync(path.join(root, 'packaging/mcpb/server.json'), 'utf8'));
  if (manifest.version !== version) drift.push('packaging/mcpb/manifest.json');
  if (server.version !== version ||
      server.packages?.[0]?.identifier !== `https://github.com/justsearch-app/justsearch/releases/download/v${version}/justsearch-mcp.mcpb` ||
      server.packages?.[0]?.fileSha256 !== packMcpb(root).sha256) drift.push('packaging/mcpb/server.json');
  if (drift.length) throw new Error(`Version projections differ from gradle.properties: ${drift.join(', ')}. Run node scripts/ci/sync-version.mjs.`);
  console.log('Version projections agree with gradle.properties.');
  process.exit(0);
}
for (const [rel, { original, next }] of updates) {
  console.log(`${rel}: ${original === next ? 'already up-to-date' : dryRun ? 'would update' : 'updating'}`);
  if (!dryRun && original !== next) fs.writeFileSync(path.join(root, rel), next, 'utf8');
}
console.log(stamp(dryRun));
console.log(dryRun ? 'WhatIf: no files written.' : 'Version sync applied successfully.');
