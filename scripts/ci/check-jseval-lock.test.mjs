import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const check = fileURLToPath(new URL('./check-jseval-lock.mjs', import.meta.url));

function pyproject(base, ui = []) {
  const list = (deps) => deps.map((d) => `"${d}"`).join(', ');
  return `[project]\ndependencies = [${list(base)}]\n\n[project.optional-dependencies]\nui = [${list(ui)}]\n`;
}

/** A checkout holding both packages; jseval's lock pins click and httpx only. */
function checkout(jsharnessBase) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'check-jseval-lock-'));
  fs.writeFileSync(path.join(root, 'settings.gradle.kts'), '');
  const write = (pkg, file, text) => {
    fs.mkdirSync(path.join(root, 'scripts', pkg), { recursive: true });
    fs.writeFileSync(path.join(root, 'scripts', pkg, file), text);
  };
  write('jseval', 'pyproject.toml', pyproject(['click>=8.1.0', 'httpx>=0.25.0']));
  write('jseval', 'requirements.lock.txt', 'click==8.1.7\nhttpx==0.27.0\n');
  write('jsharness', 'pyproject.toml', pyproject(jsharnessBase, ['playwright>=1.40']));
  write('jsharness', 'requirements.lock.txt', 'click==8.1.7\nhttpx==0.27.0\nrich==13.7.0\nplaywright==1.45.0\n');
  return root;
}

function run(root, ...args) {
  return spawnSync(process.execPath, [check, ...args], { cwd: root, encoding: 'utf8' });
}

test('jseval mode passes when jsharness base dependencies are pinned by the jseval lock', () => {
  const result = run(checkout(['click>=8.1.0', 'httpx>=0.25.0']));
  assert.equal(result.status, 0, result.stderr);
});

test('jseval mode fails a jsharness base dependency the jseval lock does not pin', () => {
  const result = run(checkout(['click>=8.1.0', 'rich>=13.0']));
  assert.equal(result.status, 1, result.stdout);
  assert.match(result.stderr, /"rich>=13\.0" is a direct dependency in scripts\/jsharness\/pyproject\.toml/);
});

test('jseval mode fails a jsharness floor above the jseval lock pin', () => {
  const result = run(checkout(['httpx>=0.28.0']));
  assert.equal(result.status, 1, result.stdout);
  assert.match(result.stderr, /"httpx".*0\.28\.0.*0\.27\.0/);
});

test('jsharness mode checks base and ui dependencies against the jsharness lock', () => {
  assert.equal(run(checkout(['click>=8.1.0', 'rich>=13.0']), 'jsharness').status, 0);
});
