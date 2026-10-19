/** Filesystem boundary checks. Git and test commands are intercepted; no product process runs. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import vm from 'node:vm';
import { fileURLToPath, pathToFileURL } from 'node:url';

import * as changesets from './changeset.mjs';
import * as evidence from './evidence.mjs';
import * as runners from './evidence-runners.mjs';
import * as rust from './rust-tests.mjs';

const script = fileURLToPath(new URL('./run-evidence.mjs', import.meta.url));
const source = fs.readFileSync(script, 'utf8');
// Run the CLI's actual functions with isolated boundaries, without starting its top-level entry.
const functions = source.slice(source.indexOf('function sh('), source.lastIndexOf('\ntry {'))
  .replaceAll('import.meta.url', 'scriptUrl');

function linksIn(dir) {
  const stat = fs.lstatSync(dir, { throwIfNoEntry: false });
  if (!stat) return [];
  if (stat.isSymbolicLink()) return [dir];
  return stat.isDirectory() ? fs.readdirSync(dir).flatMap(name => linksIn(path.join(dir, name))) : [];
}

test('--copy-node-modules creates independent ordinary files including linked dependencies', () => {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'evidence-dependencies-'));
  const root = path.join(scratch, 'candidate');
  const worktree = path.join(scratch, 'before');
  const modules = path.join('modules', 'ui-web', 'node_modules');
  const dependency = path.join(scratch, 'dependency');
  const sourceDependency = path.join(root, modules, 'dependency');
  const copiedDependency = path.join(worktree, modules, 'dependency');
  try {
    fs.mkdirSync(path.join(root, modules), { recursive: true });
    fs.mkdirSync(dependency);
    fs.writeFileSync(path.join(dependency, 'marker'), 'original');
    fs.symlinkSync(dependency, sourceDependency, process.platform === 'win32' ? 'junction' : 'dir');
    vm.runInNewContext(`${functions}
      const args = parseArgs(['--out', 'evidence.json', '--check', 'probe', '--copy-node-modules']);
      prepareNodeModules(worktree, args.copyNodeModules);`, {
      fs, path, REPO_ROOT: root, IS_WIN: process.platform === 'win32', worktree,
    });
    assert.deepEqual(linksIn(worktree), [], 'copied dependencies must contain no junctions or symlinks');
    assert.equal(fs.readFileSync(path.join(copiedDependency, 'marker'), 'utf8'), 'original');
    fs.writeFileSync(path.join(copiedDependency, 'marker'), 'changed in before-worktree');
    assert.equal(fs.readFileSync(path.join(dependency, 'marker'), 'utf8'), 'original',
      'changes in the before-worktree must not modify the candidate dependency');
    assert.ok(fs.lstatSync(sourceDependency).isSymbolicLink(), 'the candidate dependency link stays intact');
  } finally {
    for (const entry of linksIn(scratch)) fs.unlinkSync(entry);
    assert.equal(path.dirname(scratch), path.resolve(os.tmpdir()));
    fs.rmSync(scratch, { recursive: true, force: true });
  }
});

function fixture(options, check) {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'evidence-cleanup-'));
  const root = path.join(scratch, 'repo');
  const outside = path.join(scratch, 'outside');
  const beforeRoot = path.join(scratch, 'before');
  const checkFile = 'modules/core/src/test/java/io/x/ProbeTest.java';
  const observed = { removals: [], links: [], messages: [] };
  const link = (dest, target = outside, kind = 'junction') => {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.symlinkSync(target, dest, process.platform === 'win32' ? kind : 'dir');
    return dest;
  };
  fs.mkdirSync(path.dirname(path.join(root, checkFile)), { recursive: true });
  fs.writeFileSync(path.join(root, checkFile), '// synthetic check\n');
  fs.mkdirSync(outside);
  fs.mkdirSync(beforeRoot);
  fs.writeFileSync(path.join(outside, 'marker'), 'keep this installation');
  let blockedLink;
  const filesystem = { ...fs,
    unlinkSync(file) {
      if (file === blockedLink) throw new Error('unlink denied');
      return fs.unlinkSync(file);
    },
    readdirSync(dir, ...args) {
      if (options.readFailure && dir === observed.wt) throw new Error('directory unreadable');
      return fs.readdirSync(dir, ...args);
    },
  };
  const spawnSync = (command, args, opts) => {
    const ok = { status: 0, stdout: '', stderr: '' };
    if (command === 'java -version') return { ...ok, stdout: 'synthetic Java\n' };
    if (command === 'git') {
      if (args[0] === '-c' && args[1] === 'core.longpaths=true') args = args.slice(2);
      if (args[0] === 'worktree' && args[1] === 'add') {
        observed.wt = args[3];
        fs.mkdirSync(observed.wt, { recursive: true });
        options.populate?.({ wt: observed.wt, outside, link });
        if (options.unlinkFailure) blockedLink = link(path.join(observed.wt, 'nested', 'blocked'));
        if (options.addFailure) return { ...ok, status: 1, stderr: 'checkout interrupted' };
      } else if (args[0] === 'worktree' && args[1] === 'remove') {
        const wt = args.at(-1);
        assert.equal(path.dirname(wt), beforeRoot, 'only the reserved before tree may be removed');
        const links = linksIn(wt);
        observed.removals.push(wt);
        observed.links.push(...links);
        // Never hand a still-linked tree to Git or recursive deletion, even on the broken version.
        if (links.length) return { ...ok, status: 1, stderr: 'fixture refused linked recursive removal' };
        if (options.removeFailure) return { ...ok, status: 1, stderr: 'worktree locked' };
        fs.rmSync(wt, { recursive: true, force: true });
      }
      return { ...ok, stdout: '0123456789abcdef\n' };
    }
    assert.match(command, /gradlew/, 'only synthetic Gradle commands are accepted');
    const cwd = args.cwd;
    if (cwd !== root && options.runnerFailure) throw new Error('before runner exploded');
    if (cwd !== root && options.interrupted) return { status: null, signal: 'SIGINT', stdout: '', stderr: '' };
    const result = path.join(cwd, 'modules/core/build/test-results/test/TEST-io.x.ProbeTest.xml');
    fs.mkdirSync(path.dirname(result), { recursive: true });
    fs.writeFileSync(result, `<testsuite><testcase classname="io.x.ProbeTest" name="probe">${cwd === root ? ''
      : '<failure type="org.opentest4j.AssertionFailedError">expected safe cleanup</failure>'}</testcase></testsuite>`);
    return { ...ok, status: cwd === root ? 0 : 1 };
  };
  try {
    const context = vm.createContext({
      ...changesets, ...evidence, ...runners, ...rust,
      fs: filesystem, path, os, spawnSync, fileURLToPath, scriptUrl: pathToFileURL(script).href,
      REPO_ROOT: root, IS_WIN: process.platform === 'win32',
      process: { ...process, env: { ...process.env, LIGHTGBM_NATIVE_LIB_PATH: '' },
        argv: ['node', script, '--before', 'HEAD', '--before-root', beforeRoot,
          '--out', 'evidence.json', '--check', `${checkFile}#probe`] },
      console: { log: value => observed.messages.push(value), error: value => observed.messages.push(value) },
    });
    let error;
    try { observed.exitCode = vm.runInContext(`${functions}\nmain();`, context); }
    catch (e) { error = e; }
    check({ ...observed, error, outside });
    assert.equal(fs.readFileSync(path.join(outside, 'marker'), 'utf8'), 'keep this installation');
  } finally {
    // The test also cleans up failed candidates safely: lstat sees dangling links, never their targets.
    for (const entry of linksIn(scratch)) fs.unlinkSync(entry);
    assert.equal(path.dirname(scratch), path.resolve(os.tmpdir()));
    fs.rmSync(scratch, { recursive: true, force: true });
  }
}

test('nested junctions are unlinked before recursive worktree removal', () => {
  fixture({ populate: ({ wt, link }) => link(path.join(wt, 'modules/ui-web/node_modules/package/dependencies')) }, result => {
    assert.deepEqual(result.links, [], 'Git must never receive a tree containing a junction');
    assert.ifError(result.error);
    assert.equal(result.exitCode, 0);
    assert.equal(result.removals.length, 1);
  });
});

for (const kind of ['junction', 'dir']) {
  test(`top-level node_modules ${kind} is unlinked without touching its target`, t => {
    fixture({ populate: ({ wt, outside, link }) => {
      try { link(path.join(wt, 'modules/ui-web/node_modules'), outside, kind); }
      catch (e) {
        if (kind === 'dir' && process.platform === 'win32' && e.code === 'EPERM') t.skip('directory symlink privilege is unavailable');
        else throw e;
      }
    } }, result => {
      assert.ifError(result.error);
      assert.deepEqual(result.links, []);
      assert.equal(result.exitCode, 0);
    });
  });
}

test('dangling node_modules links are removed too', () => {
  fixture({ populate: ({ wt, outside, link }) => link(path.join(wt, 'modules/ui-web/node_modules'), path.join(outside, 'missing')) }, result => {
    assert.deepEqual(result.links, [], 'existsSync must not hide a dangling link');
    assert.ifError(result.error);
  });
});

test('a linked ancestor is detached without traversing or modifying its target', () => {
  fixture({ populate: ({ wt, outside, link }) => {
    fs.mkdirSync(path.join(outside, 'installation'));
    link(path.join(outside, 'node_modules'), path.join(outside, 'installation'));
    link(path.join(wt, 'modules/ui-web'));
  } }, result => {
    assert.ok(fs.lstatSync(path.join(result.outside, 'node_modules'), { throwIfNoEntry: false })?.isSymbolicLink(),
      'cleanup must not unlink anything inside the external target');
    assert.deepEqual(result.links, [], 'the ancestor link must be detached first');
    assert.ifError(result.error);
  });
});

test('runner errors still detach nested links and retain the original diagnostic', () => {
  fixture({ runnerFailure: true, populate: ({ wt, link }) => link(path.join(wt, 'nested', 'install')) }, result => {
    assert.deepEqual(result.links, []);
    assert.match(result.error?.message ?? '', /before runner exploded/);
    assert.equal(result.removals.length, 1);
  });
});

test('an interrupted child result still detaches links and reports failed evidence', () => {
  fixture({ interrupted: true, populate: ({ wt, link }) => link(path.join(wt, 'nested', 'install')) }, result => {
    assert.deepEqual(result.links, []);
    assert.ifError(result.error);
    assert.equal(result.exitCode, 1);
  });
});

test('unlink errors prevent recursive removal', () => {
  fixture({ unlinkFailure: true }, result => {
    assert.equal(result.removals.length, 0, 'Git removal must not run after a failed unlink');
    assert.match(result.error?.message ?? '', /unlink denied/);
  });
});

test('unreadable directories prevent recursive removal', () => {
  fixture({ readFailure: true }, result => {
    assert.equal(result.removals.length, 0, 'Git removal must not run when links cannot be inspected');
    assert.match(result.error?.message ?? '', /directory unreadable/);
  });
});

test('cleanup failure is reported even when the checks succeeded', () => {
  fixture({ removeFailure: true }, result => assert.match(result.error?.message ?? '', /worktree locked/));
});

test('cleanup failure preserves the earlier runner failure as well', () => {
  fixture({ runnerFailure: true, removeFailure: true }, result => {
    assert.match(result.error?.message ?? '', /before runner exploded/);
    assert.match(result.error?.message ?? '', /worktree locked/);
  });
});

test('cleanup failure preserves a signal-only interruption diagnostic', () => {
  fixture({ interrupted: true, removeFailure: true }, result => {
    assert.match(result.error?.message ?? '', /worktree locked/);
    assert.match(result.messages.join('\n'), /command interrupted: SIGINT/);
    assert.match(result.messages.join('\n'), /fail-before .*environment/);
    assert.match(result.messages.join('\n'), /evidence written:/);
  });
});

test('partial worktree creation still detaches links and attempts removal after checkout failure', () => {
  fixture({ addFailure: true, populate: ({ wt, link }) => link(path.join(wt, 'nested', 'install')) }, result => {
    assert.equal(result.removals.length, 1, 'creation must be inside the cleanup scope');
    assert.deepEqual(result.links, []);
    assert.match(result.error?.message ?? '', /checkout interrupted/);
  });
});
