#!/usr/bin/env node

import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import {
  checkCommit,
  checkPreCommit,
  checkPrePushInput,
  checkRange,
} from './check-commit-identity-privacy.mjs';

const POLICY = {
  ownerPrivacy: {
    ownerAliases: ['eliasjustus', 'Elias Justus'],
    approvedEmail: 'owner-approved@example.invalid',
  },
};
const PRIVATE_EMAIL = 'owner-private@example.invalid';
const CONTRIBUTOR_EMAIL = 'contributor@example.invalid';
const ZERO_SHA = '0'.repeat(40);
const CHECK_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-commit-identity-privacy.mjs');
const HOOK_SOURCE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../.githooks/pre-push');
const HOOK_SHELL = process.platform === 'win32' && process.env.ProgramFiles
  ? path.join(process.env.ProgramFiles, 'Git', 'bin', 'sh.exe')
  : 'sh';

function shellPath(filePath) {
  return process.platform === 'win32'
    ? filePath.replace(/^([A-Za-z]):[\\/]/, (_, drive) => `/${drive.toLowerCase()}/`).replaceAll('\\', '/')
    : filePath;
}

function git(repo, args, { env = {}, input } = {}) {
  return childProcess.execFileSync('git', args, {
    cwd: repo,
    env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1', ...env },
    encoding: 'utf8',
    input,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  }).trim();
}

function makeRepo() {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-identity-'));
  fs.writeFileSync(path.join(repo, 'settings.gradle.kts'), "rootProject.name = \"identity-test\"\n");
  git(repo, ['init', '-q']);
  return repo;
}

function makeBareRepo() {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-identity-remote-'));
  git(repo, ['init', '--bare', '-q']);
  return repo;
}

function installHookFixture(repo) {
  const ciDir = path.join(repo, 'scripts', 'ci');
  fs.mkdirSync(ciDir, { recursive: true });
  fs.copyFileSync(CHECK_SCRIPT, path.join(ciDir, 'check-commit-identity-privacy.mjs'));
  fs.writeFileSync(path.join(ciDir, 'repo-history-policy.v1.json'), `${JSON.stringify(POLICY, null, 2)}\n`);
  const governanceDir = path.join(repo, 'scripts', 'governance');
  fs.mkdirSync(governanceDir, { recursive: true });
  for (const [script, name, args] of [
    [path.join(governanceDir, 'run.mjs'), 'test-intent', ['--gate', 'test-intent', '--mode', 'gate']],
    [path.join(ciDir, 'check-store-recoverability.mjs'), 'store-recoverability', []],
  ]) {
    fs.writeFileSync(script, `import assert from 'node:assert/strict';
import fs from 'node:fs';
assert.deepEqual(process.argv.slice(2), ${JSON.stringify(args)});
fs.appendFileSync('gate-calls.txt', '${name}\\n');
if (process.env.FAIL_PUSH_GATE === '${name}') {
  console.log('${name} failure stdout');
  console.error('${name} failure stderr');
  process.exit(1);
}
`);
  }
  const hooksDir = path.join(repo, '.githooks');
  fs.mkdirSync(hooksDir, { recursive: true });
  fs.copyFileSync(HOOK_SOURCE, path.join(hooksDir, 'pre-push'));
  return path.join(hooksDir, 'pre-push');
}

function runHook(repo, hook, remoteUrl, input, captureFile, env = {}) {
  const fakeBin = path.join(repo, 'fake-bin');
  fs.mkdirSync(fakeBin, { recursive: true });
  const fakeGitLfs = path.join(fakeBin, 'git-lfs');
  fs.writeFileSync(fakeGitLfs, '#!/bin/sh\ncat >"$CAPTURE_FILE"\n');
  fs.chmodSync(fakeGitLfs, 0o755);
  if (process.platform === 'win32') {
    fs.writeFileSync(
      path.join(fakeBin, 'git-lfs.cmd'),
      '@echo off\r\npowershell -NoProfile -Command "$input=[Console]::OpenStandardInput();$output=[IO.File]::Create($env:CAPTURE_FILE);$input.CopyTo($output);$output.Dispose()"\r\nexit /b 0\r\n'
    );
  }
  const pathValue = process.platform === 'win32'
    ? `${shellPath(fakeBin)}:/usr/bin:/c/Program Files/Git/cmd:${shellPath(path.dirname(process.execPath))}`
    : `${fakeBin}:${process.env.PATH ?? ''}`;
  const command = `PATH='${pathValue}' CAPTURE_FILE='${shellPath(captureFile)}' '${shellPath(hook)}' 'origin' '${remoteUrl}'`;
  return childProcess.spawnSync(HOOK_SHELL, ['-c', command], {
    cwd: repo,
    encoding: 'utf8',
    env: { ...process.env, JUSTSEARCH_SKIP_PUSH_GATES: '', FAIL_PUSH_GATE: '', ...env },
    input,
    windowsHide: true,
  });
}

function commit(repo, { authorName, authorEmail, committerName = authorName, committerEmail = authorEmail, message }) {
  return git(
    repo,
    ['commit', '--allow-empty', '--cleanup=verbatim', '-F', '-'],
    {
      env: {
        GIT_AUTHOR_NAME: authorName,
        GIT_AUTHOR_EMAIL: authorEmail,
        GIT_COMMITTER_NAME: committerName,
        GIT_COMMITTER_EMAIL: committerEmail,
      },
      input: `${message}\n`,
    }
  ) && git(repo, ['rev-parse', 'HEAD']);
}

function rangeFrom(repo, base, head) {
  return checkRange({ range: `${base}..${head}`, cwd: repo, policy: POLICY });
}

function assertPrivacyFailure(repo, metadata, expectedMessage) {
  const base = commit(repo, {
    authorName: 'Contributor',
    authorEmail: CONTRIBUTOR_EMAIL,
    message: 'base',
  });
  const head = commit(repo, metadata);
  const violations = rangeFrom(repo, base, head);
  assert.deepEqual(violations, [expectedMessage]);
  return { base, head };
}

const temporaryRepos = [];
try {
  {
    const repo = makeRepo();
    temporaryRepos.push(repo);
    assertPrivacyFailure(
      repo,
      {
        authorName: 'eliasjustus',
        authorEmail: PRIVATE_EMAIL,
        committerName: 'Build Bot',
        committerEmail: 'bot@example.invalid',
        message: 'author metadata',
      },
      'owner author identity must use the approved public no-reply address'
    );
  }

  {
    const repo = makeRepo();
    const remote = makeBareRepo();
    temporaryRepos.push(repo, remote);
    const base = commit(repo, {
      authorName: 'Contributor',
      authorEmail: CONTRIBUTOR_EMAIL,
      message: 'base',
    });
    const historical = commit(repo, {
      authorName: 'eliasjustus',
      authorEmail: PRIVATE_EMAIL,
      message: 'already published historical metadata',
    });
    git(repo, ['remote', 'add', 'origin', remote]);
    git(repo, ['push', '-q', 'origin', `${historical}:refs/heads/main`]);
    const unfetchedRepo = makeRepo();
    temporaryRepos.push(unfetchedRepo);
    const unfetched = commit(unfetchedRepo, {
      authorName: 'Another Contributor',
      authorEmail: 'another@example.invalid',
      message: 'advertised but unfetched',
    });
    git(unfetchedRepo, ['remote', 'add', 'origin', remote]);
    git(unfetchedRepo, ['push', '-q', 'origin', `${unfetched}:refs/heads/unfetched`]);
    const approved = commit(repo, {
      authorName: 'Elias Justus',
      authorEmail: POLICY.ownerPrivacy.approvedEmail,
      message: 'new approved topic commit',
    });
    assert.deepEqual(
      checkPrePushInput({
        input: `refs/heads/topic ${approved} refs/heads/topic ${ZERO_SHA}\n`,
        cwd: repo,
        policy: POLICY,
        remoteName: 'origin',
        remoteUrl: remote,
      }),
      []
    );
    assert.deepEqual(
      checkPrePushInput({
        input: `refs/heads/existing ${approved} refs/heads/existing ${base}\n`,
        cwd: repo,
        policy: POLICY,
        remoteName: 'origin',
        remoteUrl: remote,
      }),
      []
    );
    const privateHead = commit(repo, {
      authorName: 'eliasjustus',
      authorEmail: PRIVATE_EMAIL,
      committerName: 'Build Bot',
      committerEmail: 'bot@example.invalid',
      message: 'new private topic commit',
    });
    assert.deepEqual(
      checkPrePushInput({
        input: `refs/heads/topic ${privateHead} refs/heads/topic ${ZERO_SHA}\n`,
        cwd: repo,
        policy: POLICY,
        remoteName: 'origin',
        remoteUrl: remote,
      }),
      ['owner author identity must use the approved public no-reply address']
    );
    assert.notEqual(base, historical);
  }

  {
    const repo = makeRepo();
    const remote = makeBareRepo();
    temporaryRepos.push(repo, remote);
    git(repo, ['remote', 'add', 'origin', remote]);
    const approved = commit(repo, {
      authorName: 'Elias Justus',
      authorEmail: POLICY.ownerPrivacy.approvedEmail,
      message: 'approved first push',
    });
    assert.deepEqual(
      checkPrePushInput({
        input: `refs/heads/main ${approved} refs/heads/main ${ZERO_SHA}\n`,
        cwd: repo,
        policy: POLICY,
        remoteName: 'origin',
        remoteUrl: remote,
      }),
      []
    );
    const privateHead = commit(repo, {
      authorName: 'eliasjustus',
      authorEmail: PRIVATE_EMAIL,
      committerName: 'Build Bot',
      committerEmail: 'bot@example.invalid',
      message: 'private first push',
    });
    assert.deepEqual(
      checkPrePushInput({
        input: `refs/heads/main ${privateHead} refs/heads/main ${ZERO_SHA}\n`,
        cwd: repo,
        policy: POLICY,
        remoteName: 'origin',
        remoteUrl: remote,
      }),
      ['owner author identity must use the approved public no-reply address']
    );
  }

  {
    const repo = makeRepo();
    const remote = makeBareRepo();
    temporaryRepos.push(repo, remote);
    const base = commit(repo, {
      authorName: 'Contributor',
      authorEmail: CONTRIBUTOR_EMAIL,
      message: 'base',
    });
    git(repo, ['remote', 'add', 'origin', remote]);
    git(repo, ['push', '-q', 'origin', `${base}:refs/heads/main`]);
    const hook = installHookFixture(repo);
    const capture = path.join(repo, 'git-lfs-stdin.txt');
    const remoteUrl = pathToFileURL(remote).href;
    let result = runHook(repo, hook, remoteUrl, '', capture);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(capture, 'utf8'), '');
    const privateHead = commit(repo, {
      authorName: 'eliasjustus',
      authorEmail: PRIVATE_EMAIL,
      committerName: 'Build Bot',
      committerEmail: 'bot@example.invalid',
      message: 'private hook rejection',
    });
    fs.rmSync(capture, { force: true });
    result = runHook(repo, hook, remoteUrl, `refs/heads/topic ${privateHead} refs/heads/topic ${ZERO_SHA}\n`, capture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /owner author identity/);
    assert.ok(!result.stderr.includes(PRIVATE_EMAIL));
    assert.ok(!fs.existsSync(capture));
    const deletion = `refs/heads/topic ${ZERO_SHA} refs/heads/topic ${base}\n`;
    result = runHook(repo, hook, remoteUrl, deletion, capture);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(capture, 'utf8'), deletion);
  }

  {
    const repo = makeRepo();
    const remote = makeBareRepo();
    temporaryRepos.push(repo, remote);
    const base = commit(repo, {
      authorName: 'Contributor', authorEmail: CONTRIBUTOR_EMAIL, message: 'gate base',
    });
    git(repo, ['remote', 'add', 'origin', remote]);
    git(repo, ['push', '-q', 'origin', `${base}:refs/heads/main`]);
    const hook = installHookFixture(repo);
    const capture = path.join(repo, 'git-lfs-stdin.txt');
    const calls = path.join(repo, 'gate-calls.txt');
    const remoteUrl = pathToFileURL(remote).href;
    const topic = `refs/heads/main ${base} refs/heads/topic ${ZERO_SHA}\n`;
    const main = `refs/heads/topic ${base} refs/heads/main ${ZERO_SHA}\n`;
    const deletion = `(delete) ${ZERO_SHA} refs/heads/topic ${base}\n`;
    const tag = `refs/tags/v1 ${base} refs/tags/v1 ${ZERO_SHA}\n`;
    function run(input, env = {}) {
      fs.rmSync(capture, { force: true });
      fs.rmSync(calls, { force: true });
      return runHook(repo, hook, remoteUrl, input, capture, env);
    }
    for (const input of [topic, main + deletion + tag + topic]) {
      const result = run(input);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(fs.readFileSync(calls, 'utf8'), 'test-intent\nstore-recoverability\n');
      assert.equal(fs.readFileSync(capture, 'utf8'), input);
    }
    for (const failed of ['test-intent', 'store-recoverability']) {
      const result = run(topic, { FAIL_PUSH_GATE: failed });
      assert.equal(result.status, 1, result.stderr);
      assert.match(result.stderr, new RegExp(`pre-push: .*${failed}.*failed`));
      assert.match(result.stdout, new RegExp(`${failed} failure stdout`));
      assert.match(result.stderr, new RegExp(`${failed} failure stderr`));
      assert.ok(!fs.existsSync(capture), 'a failed gate blocks LFS');
    }
    for (const input of ['', main, deletion, tag, main + deletion + tag]) {
      const result = run(input, { FAIL_PUSH_GATE: 'test-intent' });
      assert.equal(result.status, 0, result.stderr);
      assert.ok(!fs.existsSync(calls), 'non-topic updates do not run gates');
      assert.equal(fs.readFileSync(capture, 'utf8'), input);
    }
    let result = run(topic, { JUSTSEARCH_SKIP_PUSH_GATES: '1', FAIL_PUSH_GATE: 'test-intent' });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /pre-push: .*skipped.*JUSTSEARCH_SKIP_PUSH_GATES=1/);
    assert.ok(!fs.existsSync(calls));
    assert.equal(fs.readFileSync(capture, 'utf8'), topic);
    result = run(topic, { JUSTSEARCH_SKIP_PUSH_GATES: 'true', FAIL_PUSH_GATE: 'test-intent' });
    assert.equal(result.status, 1, 'only the exact value 1 bypasses gates');
    const privateHead = commit(repo, {
      authorName: 'eliasjustus', authorEmail: PRIVATE_EMAIL,
      committerName: 'Build Bot', committerEmail: 'bot@example.invalid',
      message: 'privacy still applies when gates are skipped',
    });
    result = run(`refs/heads/topic ${privateHead} refs/heads/topic ${ZERO_SHA}\n`, {
      JUSTSEARCH_SKIP_PUSH_GATES: '1',
    });
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /owner author identity/);
    assert.ok(!fs.existsSync(calls));
    assert.ok(!fs.existsSync(capture));
  }

  {
    const repo = makeRepo();
    const remote = makeBareRepo();
    temporaryRepos.push(repo, remote);
    assertPrivacyFailure(
      repo,
      {
        authorName: 'Contributor',
        authorEmail: CONTRIBUTOR_EMAIL,
        committerName: 'Elias Justus',
        committerEmail: PRIVATE_EMAIL,
        message: 'committer metadata',
      },
      'owner committer identity must use the approved public no-reply address'
    );
  }

  {
    const repo = makeRepo();
    temporaryRepos.push(repo);
    assertPrivacyFailure(
      repo,
      {
        authorName: 'Contributor',
        authorEmail: CONTRIBUTOR_EMAIL,
        message: 'subject\n\nCo-authored-by: Elias Justus <owner-private@example.invalid>',
      },
      'owner Co-authored-by trailer identity must use the approved public no-reply address'
    );
  }

  {
    const repo = makeRepo();
    temporaryRepos.push(repo);
    const base = commit(repo, {
      authorName: 'Elias Justus',
      authorEmail: POLICY.ownerPrivacy.approvedEmail,
      message: 'approved owner',
    });
    const head = commit(repo, {
      authorName: 'Contributor',
      authorEmail: CONTRIBUTOR_EMAIL,
      committerName: 'Build Bot',
      committerEmail: 'bot@example.invalid',
      message: 'unrelated contributor',
    });
    assert.deepEqual(rangeFrom(repo, base, head), []);
    assert.deepEqual(
      checkCommit({ commit: head, cwd: repo, policy: POLICY }),
      []
    );
  }

  {
    const repo = makeRepo();
    temporaryRepos.push(repo);
    const original = {
      GIT_AUTHOR_NAME: process.env.GIT_AUTHOR_NAME,
      GIT_AUTHOR_EMAIL: process.env.GIT_AUTHOR_EMAIL,
      GIT_COMMITTER_NAME: process.env.GIT_COMMITTER_NAME,
      GIT_COMMITTER_EMAIL: process.env.GIT_COMMITTER_EMAIL,
    };
    try {
      Object.assign(process.env, {
        GIT_AUTHOR_NAME: 'eliasjustus',
        GIT_AUTHOR_EMAIL: PRIVATE_EMAIL,
        GIT_COMMITTER_NAME: 'Contributor',
        GIT_COMMITTER_EMAIL: CONTRIBUTOR_EMAIL,
      });
      assert.deepEqual(checkPreCommit({ cwd: repo, policy: POLICY }), [
        'owner author identity must use the approved public no-reply address',
      ]);
      process.env.GIT_AUTHOR_EMAIL = POLICY.ownerPrivacy.approvedEmail;
      assert.deepEqual(checkPreCommit({ cwd: repo, policy: POLICY }), []);
      process.env.GIT_COMMITTER_NAME = 'Elias Justus';
      process.env.GIT_COMMITTER_EMAIL = PRIVATE_EMAIL;
      assert.deepEqual(checkPreCommit({ cwd: repo, policy: POLICY }), [
        'owner committer identity must use the approved public no-reply address',
      ]);
    } finally {
      for (const [key, value] of Object.entries(original)) {
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      }
    }
  }

  {
    const repo = makeRepo();
    temporaryRepos.push(repo);
    const base = commit(repo, {
      authorName: 'Contributor',
      authorEmail: CONTRIBUTOR_EMAIL,
      message: 'base',
    });
    const head = commit(repo, {
      authorName: 'Elias Justus',
      authorEmail: POLICY.ownerPrivacy.approvedEmail,
      message: 'approved owner',
    });
    const remote = makeBareRepo();
    temporaryRepos.push(remote);
    git(repo, ['remote', 'add', 'origin', remote]);
    git(repo, ['push', '-q', 'origin', `${base}:refs/heads/main`]);
    const input = [
      `refs/heads/main ${head} refs/remotes/origin/main ${base}`,
      `refs/heads/topic ${head} refs/remotes/origin/topic ${ZERO_SHA}`,
      `refs/heads/deleted ${ZERO_SHA} refs/remotes/origin/deleted ${base}`,
    ].join('\n');
    assert.deepEqual(checkPrePushInput({ input, cwd: repo, policy: POLICY, remoteName: 'origin', remoteUrl: remote }), []);
    assert.throws(
      () => checkPrePushInput({ input: `refs/heads/main ${head} refs/remotes/origin/main`, cwd: repo, policy: POLICY }),
      /missing field/
    );
  }

  {
    const repo = makeRepo();
    temporaryRepos.push(repo);
    const base = commit(repo, {
      authorName: 'Contributor',
      authorEmail: CONTRIBUTOR_EMAIL,
      message: 'base',
    });
    const head = commit(repo, {
      authorName: 'eliasjustus',
      authorEmail: PRIVATE_EMAIL,
      message: 'private owner metadata',
    });
    const result = childProcess.spawnSync(process.execPath, [CHECK_SCRIPT, '--range', `${base}..${head}`], {
      cwd: repo,
      encoding: 'utf8',
      env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1' },
      windowsHide: true,
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /owner author identity/);
    assert.ok(!result.stdout.includes(PRIVATE_EMAIL));
    assert.ok(!result.stderr.includes(PRIVATE_EMAIL));
    const badArgument = childProcess.spawnSync(process.execPath, [CHECK_SCRIPT, '--unknown', PRIVATE_EMAIL], {
      cwd: repo,
      encoding: 'utf8',
      env: { ...process.env, GIT_CONFIG_NOSYSTEM: '1' },
      windowsHide: true,
    });
    assert.equal(badArgument.status, 1);
    assert.ok(!badArgument.stdout.includes(PRIVATE_EMAIL));
    assert.ok(!badArgument.stderr.includes(PRIVATE_EMAIL));
  }

  {
    const repo = makeRepo();
    const remote = makeBareRepo();
    temporaryRepos.push(repo, remote);
    const base = commit(repo, {
      authorName: 'Contributor', authorEmail: CONTRIBUTOR_EMAIL, message: 'push base',
    });
    git(repo, ['remote', 'add', 'origin', remote]);
    const hook = installHookFixture(repo);
    const capture = path.join(repo, 'git-lfs-stdin.txt');
    const calls = path.join(repo, 'gate-calls.txt');
    // Install the same LFS stub used by the protocol tests, then let Git invoke the hook.
    assert.equal(runHook(repo, hook, remote, '', capture).status, 0);
    git(repo, ['config', 'core.hooksPath', '.githooks']);
    function push(args, env = {}) {
      fs.rmSync(capture, { force: true });
      fs.rmSync(calls, { force: true });
      return childProcess.spawnSync('git', ['push', ...args], {
        cwd: repo,
        encoding: 'utf8',
        env: {
          ...process.env,
          PATH: `${path.join(repo, 'fake-bin')}${path.delimiter}${process.env.PATH ?? ''}`,
          CAPTURE_FILE: capture,
          JUSTSEARCH_SKIP_PUSH_GATES: '1',
          FAIL_PUSH_GATE: '',
          ...env,
        },
        windowsHide: true,
      });
    }
    function allowed(args) {
      const result = push(args);
      assert.equal(result.status, 0, result.stderr);
      assert.ok(fs.existsSync(capture), 'allowed pushes reach Git LFS');
    }
    function refused(args, oldSha, newSha, ref, env = {}, message = /history replacement is not allowed/) {
      const result = push(args, env);
      assert.equal(result.status, 1, `expected push refusal: ${args.join(' ')}\n${result.stderr}`);
      assert.match(result.stderr, message);
      for (const value of [ref, oldSha, newSha]) assert.ok(result.stderr.includes(value), result.stderr);
      assert.ok(!fs.existsSync(calls), 'ancestry refusal precedes expensive gates');
      assert.ok(!fs.existsSync(capture), 'ancestry refusal precedes Git LFS');
      assert.equal(git(remote, ['rev-parse', ref]), oldSha, 'refused pushes preserve the remote ref');
    }
    allowed(['origin', 'HEAD:refs/heads/topic']);
    const head = commit(repo, {
      authorName: 'Contributor', authorEmail: CONTRIBUTOR_EMAIL, message: 'fast-forward',
    });
    allowed(['origin', 'HEAD:refs/heads/topic']);
    assert.equal(git(remote, ['rev-parse', 'refs/heads/topic']), head);
    for (const args of [
      ['--force', 'origin', `${base}:refs/heads/topic`],
      ['-f', 'origin', `${base}:refs/heads/topic`],
      ['origin', `+${base}:refs/heads/topic`],
      [`--force-with-lease=refs/heads/topic:${head}`, 'origin', `${base}:refs/heads/topic`],
      ['--force-if-includes', 'origin', '--force', `${base}:refs/heads/topic`],
      ['--quiet', 'origin', `${base}:refs/heads/topic`, '--force'],
    ]) refused(args, head, base, 'refs/heads/topic');
    refused(['--force', 'origin', `${base}:refs/heads/topic`], head, base, 'refs/heads/topic', {
      JUSTSEARCH_SKIP_PUSH_GATES: '', FAIL_PUSH_GATE: 'test-intent',
    });

    // A later rejected update must block the entire batch, even after a new branch.
    const batch = `refs/heads/new ${head} refs/heads/new ${ZERO_SHA}\nrefs/heads/topic ${base} refs/heads/topic ${head}\n`;
    let result = runHook(repo, hook, remote, batch, capture, { JUSTSEARCH_SKIP_PUSH_GATES: '1' });
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /history replacement is not allowed/);
    assert.ok(!fs.existsSync(capture));

    for (const annotated of [false, true]) {
      const tag = annotated ? 'annotated' : 'lightweight';
      const tagArgs = annotated
        ? ['-c', 'user.name=Contributor', '-c', `user.email=${CONTRIBUTOR_EMAIL}`, 'tag', '-a', '-m', 'tag']
        : ['tag'];
      git(repo, [...tagArgs, tag, base]);
      allowed(['origin', `refs/tags/${tag}`]);
      git(repo, [...tagArgs, '-f', tag, head]);
      allowed(['--force', 'origin', `refs/tags/${tag}`]);
      const oldTag = git(repo, ['rev-parse', `refs/tags/${tag}`]);
      git(repo, [...tagArgs, '-f', tag, base]);
      const newTag = git(repo, ['rev-parse', `refs/tags/${tag}`]);
      refused(['--force', 'origin', `refs/tags/${tag}`], oldTag, newTag, `refs/tags/${tag}`);
    }
    allowed(['origin', '--delete', 'topic']);
    assert.equal(git(remote, ['for-each-ref', '--format=%(refname)', 'refs/heads/topic']), '');

    const unfetchedRepo = makeRepo();
    temporaryRepos.push(unfetchedRepo);
    const unknown = commit(unfetchedRepo, {
      authorName: 'Contributor', authorEmail: CONTRIBUTOR_EMAIL, message: 'unfetched remote commit',
    });
    git(unfetchedRepo, ['push', '-q', remote, 'HEAD:refs/heads/unfetched']);
    assert.notEqual(childProcess.spawnSync('git', ['cat-file', '-e', unknown], { cwd: repo }).status, 0);
    refused(['--force', 'origin', 'HEAD:refs/heads/unfetched'], unknown, head, 'refs/heads/unfetched', {}, /fetch first/i);
    allowed(['origin', '--delete', 'unfetched']);
    console.log('pre-push ancestry: new branch, fast-forward, force spellings, batch, tags, deletion, missing object: PASS');
  }

  console.log('test-check-commit-identity-privacy: PASS');
} finally {
  for (const repo of temporaryRepos) fs.rmSync(repo, { recursive: true, force: true });
}
