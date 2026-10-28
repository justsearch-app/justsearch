#!/usr/bin/env node
// Cross-platform implementation of sync-version.ps1; gradle.properties is canonical.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
let root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
let dryRun = false;
let requireRelease = false;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--repo-root' && args[i + 1]) root = path.resolve(args[++i]);
  else if (args[i] === '--what-if') dryRun = true;
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

// The existing JSON-aware packer stamps both MCPB versions and the release URL,
// rehashing the bundle when its manifest changes. Preflight before writing files.
function stamp(checkOnly) {
  const result = spawnSync(process.execPath, [path.join(root, 'scripts/ci/pack-mcpb.mjs'),
    '--set-version', version, ...(checkOnly ? ['--dry-run'] : [])], {
    env: { ...process.env, CHECK_MCPB_ROOT: root }, encoding: 'utf8',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`pack-mcpb.mjs --set-version failed (exit=${result.status}): ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}
stamp(true);
for (const [rel, { original, next }] of updates) {
  console.log(`${rel}: ${original === next ? 'already up-to-date' : dryRun ? 'would update' : 'updating'}`);
  if (!dryRun && original !== next) fs.writeFileSync(path.join(root, rel), next, 'utf8');
}
console.log(stamp(dryRun));
console.log(dryRun ? 'WhatIf: no files written.' : 'Version sync applied successfully.');
