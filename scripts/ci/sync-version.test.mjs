#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'js-version-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const rel of ['gradle.properties', 'THIRD_PARTY_NOTICES', 'modules/shell/package.json', 'modules/shell/package-lock.json',
    'modules/shell/src-tauri/tauri.conf.json', 'modules/shell/src-tauri/Cargo.toml', 'modules/shell/src-tauri/Cargo.lock',
    'scripts/ci/pack-mcpb.mjs', 'packaging/mcpb']) {
    const dest = path.join(root, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.cpSync(path.join(repo, rel), dest, { recursive: true });
  }
  return root;
}
function sync(root, ...args) {
  return spawnSync(process.execPath, [path.join(repo, 'scripts/ci/sync-version.mjs'),
    '--repo-root', root, ...args], { encoding: 'utf8' });
}
const read = (root, rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const json = (root, rel) => JSON.parse(read(root, rel));
function setVersion(root, version) {
  const p = path.join(root, 'gradle.properties');
  fs.writeFileSync(p, read(root, 'gradle.properties').replace(/^version=.*$/m, `version=${version}`));
}

test('Linux sync stamps app, Windows branding and both MCPB versions, URL and hash; second run is stable', (t) => {
  const root = fixture(t);
  setVersion(root, '1.2.3-rc.2');
  const result = sync(root, '--require-release-semver');
  assert.equal(result.status, 0, result.stderr);
  console.log(result.stdout.trim());
  assert.equal(json(root, 'modules/shell/package.json').version, '1.2.3-rc.2');
  const tauri = json(root, 'modules/shell/src-tauri/tauri.conf.json');
  assert.equal(tauri.version, '1.2.3-rc.2');
  assert.equal(tauri.bundle.copyright, 'JustSearch 1.2.3-rc.2 - Copyright (c) JustSearch');
  assert.match(read(root, 'modules/shell/src-tauri/Cargo.toml'), /^version = "1.2.3-rc.2"$/m);
  assert.equal(json(root, 'packaging/mcpb/manifest.json').version, '1.2.3-rc.2');
  const server = json(root, 'packaging/mcpb/server.json');
  assert.equal(server.version, '1.2.3-rc.2');
  assert.match(server.packages[0].identifier, /\/v1\.2\.3-rc\.2\/justsearch-mcp.mcpb$/);
  const check = spawnSync(process.execPath, [path.join(repo, 'scripts/ci/check-mcpb-consistency.mjs'),
    '--release-version', '1.2.3-rc.2'], { env: { ...process.env, CHECK_MCPB_ROOT: root }, encoding: 'utf8' });
  assert.equal(check.status, 0, check.stderr);
  const before = read(root, 'packaging/mcpb/server.json');
  assert.equal(sync(root).status, 0);
  assert.equal(read(root, 'packaging/mcpb/server.json'), before);
  // The temporary fixture is removed by t.after; the working tree is never bumped.
});

test('sync stamps only the shell crate notice; check rejects notice-only drift without writing', (t) => {
  const root = fixture(t);
  const notice = read(root, 'THIRD_PARTY_NOTICES');
  setVersion(root, '1.2.3-rc.2');
  const result = sync(root, '--require-release-semver');
  assert.equal(result.status, 0, result.stderr);
  const expected = notice.replace(/^  shell \S+(?= — )/m, '  shell 1.2.3-rc.2');
  const stamped = read(root, 'THIRD_PARTY_NOTICES');
  assert.equal(stamped.match(/^  shell .+$/m)?.[0], '  shell 1.2.3-rc.2 — Apache-2.0');
  assert.equal(stamped, expected, 'other notice content must stay byte-identical');
  assert.equal(sync(root, '--check').status, 0);

  fs.writeFileSync(path.join(root, 'THIRD_PARTY_NOTICES'), notice);
  const check = sync(root, '--check');
  assert.equal(check.status, 1, check.stderr);
  assert.match(check.stderr, /Version projections differ.*THIRD_PARTY_NOTICES/);
  assert.equal(read(root, 'THIRD_PARTY_NOTICES'), notice, 'check must not write');
  assert.equal(sync(root, '--what-if').status, 0);
  assert.equal(read(root, 'THIRD_PARTY_NOTICES'), notice, 'WhatIf must not write');
  assert.equal(sync(root).status, 0);
  assert.equal(read(root, 'THIRD_PARTY_NOTICES'), expected);
  assert.equal(sync(root, '--check').status, 0);
});

test('WhatIf leaves every projection unchanged; release mode rejects SNAPSHOT before writing', (t) => {
  const root = fixture(t);
  const before = read(root, 'modules/shell/src-tauri/tauri.conf.json');
  const manifest = read(root, 'packaging/mcpb/manifest.json');
  const server = read(root, 'packaging/mcpb/server.json');
  setVersion(root, '8.9.0');
  assert.equal(sync(root, '--what-if').status, 0);
  assert.equal(read(root, 'modules/shell/src-tauri/tauri.conf.json'), before);
  assert.equal(read(root, 'packaging/mcpb/manifest.json'), manifest);
  assert.equal(read(root, 'packaging/mcpb/server.json'), server);
  setVersion(root, '8.9.0-SNAPSHOT');
  const result = sync(root, '--require-release-semver');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /without SNAPSHOT/);
  assert.equal(read(root, 'modules/shell/src-tauri/tauri.conf.json'), before);
});

// Exercise the actual guard, including drift hidden by a correct MCPB hash.
test('check rejects every divergent projection without repairing it; sync restores agreement', (t) => {
  const root = fixture(t);
  setVersion(root, '0.3.0');
  assert.equal(sync(root).status, 0);
  assert.equal(sync(root, '--check').status, 0);
  const mutations = [
    ['THIRD_PARTY_NOTICES', value => value.replace(/^  shell \S+(?= — )/m, '  shell 9.9.9')],
    ['modules/shell/package.json', value => value.replace('"version": "0.3.0"', '"version": "9.9.9"')],
    ['modules/shell/package-lock.json', value => value.replace('"version": "0.3.0"', '"version": "9.9.9"')],
    ['modules/shell/package-lock.json', value => value.replace(/("": \{\s*"name": "shell",\s*"version": ")[^"]+/, '$19.9.9')],
    ['modules/shell/src-tauri/tauri.conf.json', value => value.replace('"version": "0.3.0"', '"version": "9.9.9"')],
    ['modules/shell/src-tauri/Cargo.toml', value => value.replace('version = "0.3.0"', 'version = "9.9.9"')],
    ['modules/shell/src-tauri/Cargo.lock', value => value.replace('name = "shell"\nversion = "0.3.0"', 'name = "shell"\nversion = "9.9.9"')],
    ['packaging/mcpb/manifest.json', value => value.replace('"version": "0.3.0"', '"version": "9.9.9"')],
    ['packaging/mcpb/server.json', value => value.replace('"version": "0.3.0"', '"version": "9.9.9"')],
    ['packaging/mcpb/server.json', value => value.replace('/v0.3.0/', '/v9.9.9/')],
  ];
  for (const [rel, mutate] of mutations) {
    const original = read(root, rel);
    const changed = mutate(original);
    assert.notEqual(changed, original, rel);
    fs.writeFileSync(path.join(root, rel), changed);
    const result = sync(root, '--check');
    assert.equal(result.status, 1, rel);
    assert.match(result.stderr, /Version projections differ/);
    assert.equal(read(root, rel), changed, 'check must not repair drift');
    assert.equal(sync(root).status, 0, rel);
    assert.equal(sync(root, '--check').status, 0, rel);
  }
});

for (const [name, rel, mutate] of [
  ['hash-only drift', 'packaging/mcpb/server.json', value => {
    const server = JSON.parse(value);
    server.packages[0].fileSha256 = 'f'.repeat(64);
    return JSON.stringify(server, null, 2) + '\n';
  }],
  ['source-content drift', 'packaging/mcpb/server/index.js', value => value + '\n// Changed bundle content.\n'],
]) {
  test(`sync repairs ${name} without a version bump; check and WhatIf write nothing`, (t) => {
    const root = fixture(t);
    assert.equal(sync(root).status, 0);
    assert.equal(sync(root, '--check').status, 0);
    const original = read(root, rel);
    const changed = mutate(original);
    assert.notEqual(changed, original);
    fs.writeFileSync(path.join(root, rel), changed);
    const snapshot = () => Object.fromEntries(fs.readdirSync(root, { recursive: true })
      .filter(file => fs.statSync(path.join(root, file)).isFile())
      .map(file => [file, read(root, file)]));
    const before = snapshot();
    const check = sync(root, '--check');
    assert.equal(check.status, 1, check.stderr);
    assert.match(check.stderr, /Version projections differ.*packaging\/mcpb\/server\.json/);
    assert.match(check.stderr, /Run node scripts\/ci\/sync-version\.mjs/);
    assert.deepEqual(snapshot(), before, 'check must not write any file');
    const whatIf = sync(root, '--what-if');
    assert.equal(whatIf.status, 0, whatIf.stderr);
    assert.deepEqual(snapshot(), before, 'WhatIf must not write any file');
    const result = sync(root);
    assert.equal(result.status, 0, result.stderr);
    const after = sync(root, '--check');
    assert.equal(after.status, 0, after.stderr);
    const expected = { ...before };
    const server = JSON.parse(expected['packaging/mcpb/server.json']);
    const repaired = json(root, 'packaging/mcpb/server.json');
    assert.notEqual(repaired.packages[0].fileSha256, server.packages[0].fileSha256);
    server.packages[0].fileSha256 = repaired.packages[0].fileSha256;
    expected['packaging/mcpb/server.json'] = JSON.stringify(server, null, 2) + '\n';
    assert.deepEqual(snapshot(), expected, 'sync must only repair the hash');
  });
}
