#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  NPM_LOCK_ROOTS,
  applySetup,
  buildInstallPlan,
  parseArgs,
  parseNodeMajor,
  parsePythonVersion,
  parseRustVersion,
  probeSupportedPython,
  resolveNpmInvocation,
  runProcess,
  validateGradleJavaHome,
  validatePrerequisites,
} from './bootstrap.mjs';

test('parses supported tool version formats', () => {
  assert.equal(parseNodeMajor('v24.14.0'), 24);
  assert.equal(parseNodeMajor('20.0.0'), 20);
  assert.deepEqual(parsePythonVersion('Python 3.13.7'), { major: 3, minor: 13 });
  assert.deepEqual(parseRustVersion('rustc 1.90.0 (abc 2026-01-01)'), { major: 1, minor: 90 });
  assert.equal(parseNodeMajor('v24-preview'), null);
  assert.equal(parsePythonVersion('not python'), null);
});

test('enforces native floors while Rust remains advisory', () => {
  const supported = validatePrerequisites({
    nodeVersion: 'v24.14.0', javaMajor: 24, pythonVersion: 'Python 3.13.1', rustVersion: null,
  });
  assert.equal(supported.ok, true);
  assert.equal(supported.warnings.length, 1);

  const unsupported = validatePrerequisites({
    nodeVersion: 'v18.20.0', javaMajor: 21, pythonVersion: 'Python 3.12.9', rustVersion: 'rustc 1.90.0',
  });
  assert.equal(unsupported.ok, false);
  assert.equal(unsupported.failures.length, 3);
});

test('rejects Node 23 at the contributor Node 24 boundary', () => {
  const result = validatePrerequisites({
    nodeVersion: 'v23.11.1', javaMajor: 24, pythonVersion: 'Python 3.13.1', rustVersion: null,
  });
  assert.equal(result.ok, false);
  assert.deepEqual(result.failures, ['Node.js 24+ is required (found v23.11.1).']);
});

test('Python probing continues past an older interpreter to a supported spelling', () => {
  const outputs = new Map([
    ['python3', 'Python 3.12.10'],
    ['python', 'Python 3.13.7'],
  ]);
  const result = probeSupportedPython(
    [['python3', ['--version']], ['python', ['--version']]],
    (command) => outputs.get(command),
  );
  assert.deepEqual(result, { command: 'python', output: 'Python 3.13.7' });
});

test('stale JAVA_HOME fails before bootstrap advertises a Gradle-ready environment', () => {
  assert.throws(() => validateGradleJavaHome({
    ambientJavaHome: '/old-jdk',
    resolvedJdkHome: '/jdk-25',
    inspect: () => 'openjdk version "17.0.12"',
  }), /Gradle will prefer it.*Point JAVA_HOME/s);
  assert.doesNotThrow(() => validateGradleJavaHome({
    ambientJavaHome: '/jdk-24',
    resolvedJdkHome: '/jdk-24',
    inspect: () => 'openjdk version "24.0.2"',
  }));
});

test('install plan is explicit and requires a lockfile at every reviewed root', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-bootstrap-plan-'));
  try {
    for (const relativeRoot of NPM_LOCK_ROOTS) {
      const dir = path.join(root, relativeRoot);
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, 'package.json'), '{}');
      fs.writeFileSync(path.join(dir, 'package-lock.json'), '{}');
    }
    const plan = buildInstallPlan(root);
    assert.deepEqual(plan.map((entry) => entry.relativeRoot), [...NPM_LOCK_ROOTS]);
    assert.deepEqual(plan.find((entry) => entry.relativeRoot === 'packages/runtime-client').args,
      ['ci', '--ignore-scripts']);
    fs.unlinkSync(path.join(root, 'scripts/wire-contract/package-lock.json'));
    assert.throws(() => buildInstallPlan(root), /refusing a non-lockfile install/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('--check performs no install or chmod', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-bootstrap-check-'));
  const gradlePath = path.join(root, 'gradlew');
  fs.writeFileSync(gradlePath, '#!/bin/sh\n');
  fs.chmodSync(gradlePath, 0o755);
  let installs = 0;
  let chmods = 0;
  try {
    applySetup({
      check: true,
      platform: 'linux',
      gradlePath,
      installPlan: [{ relativeRoot: '.', cwd: root, args: ['ci'] }],
      install: () => { installs += 1; },
      chmod: () => { chmods += 1; },
      log: () => {},
    });
    assert.equal(installs, 0);
    assert.equal(chmods, 0);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('mutating setup repairs Unix wrapper mode and installs every planned root', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-bootstrap-apply-'));
  const gradlePath = path.join(root, 'gradlew');
  fs.writeFileSync(gradlePath, '#!/bin/sh\n');
  let chmodMode = null;
  const installed = [];
  try {
    const installPlan = NPM_LOCK_ROOTS.map((relativeRoot) => ({
      relativeRoot,
      cwd: path.join(root, relativeRoot),
      args: ['ci'],
    }));
    applySetup({
      check: false,
      platform: 'linux',
      gradlePath,
      installPlan,
      install: (entry) => installed.push(entry.relativeRoot),
      chmod: (_file, mode) => { chmodMode = mode; },
      log: () => {},
    });
    assert.equal(chmodMode, 0o755);
    assert.deepEqual(installed, [...NPM_LOCK_ROOTS]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('command failures surface the command and exit status', () => {
  assert.throws(
    () => runProcess(process.execPath, ['-e', 'process.exit(7)'], { shell: false }),
    /exited 7/,
  );
});

test('npm invocation uses the active CLI through Node on Windows', () => {
  const npmCli = process.env.npm_execpath;
  assert.ok(npmCli, 'test is expected to run through npm');
  assert.deepEqual(resolveNpmInvocation({
    platform: 'win32', execPath: process.execPath, env: { npm_execpath: npmCli },
  }), { command: process.execPath, prefixArgs: [npmCli] });
});

test('CLI rejects unknown options', () => {
  assert.deepEqual(parseArgs(['--check']), { check: true, help: false });
  assert.throws(() => parseArgs(['--install-everything']), /Unknown option/);
});
