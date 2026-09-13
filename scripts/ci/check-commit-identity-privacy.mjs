#!/usr/bin/env node

/**
 * Guard the maintainer's commit metadata at local publication boundaries.
 *
 * This intentionally recognizes only the owner aliases declared by the
 * repository history policy. Other contributors and automation identities are
 * outside this rule's scope.
 */

import childProcess from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
const POLICY_PATH = path.join(SCRIPT_DIR, 'repo-history-policy.v1.json');
const HASH_PATTERN = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
const ZERO_HASH_PATTERN = /^(?:0{40}|0{64})$/;

function repoRootFromCwd() {
  for (let directory = process.cwd(); ; directory = path.dirname(directory)) {
    if (fs.existsSync(path.join(directory, 'settings.gradle.kts'))) return directory;
    const parent = path.dirname(directory);
    if (parent === directory) return REPO_ROOT;
  }
}

function runGit(args, cwd) {
  try {
    return childProcess.execFileSync('git', args, {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      windowsHide: true,
    });
  } catch {
    throw new Error('required Git metadata could not be read');
  }
}

function loadPolicy(policyPath = POLICY_PATH) {
  try {
    return JSON.parse(fs.readFileSync(policyPath, 'utf8'));
  } catch {
    throw new Error('commit identity privacy policy could not be read');
  }
}

function policyIdentity(policy) {
  const identity = policy?.ownerPrivacy;
  if (!identity || !Array.isArray(identity.ownerAliases) || identity.ownerAliases.length === 0) {
    throw new Error('commit identity privacy policy has no owner aliases');
  }
  if (typeof identity.approvedEmail !== 'string' || identity.approvedEmail.length === 0) {
    throw new Error('commit identity privacy policy has no approved address');
  }
  return {
    aliases: new Set(identity.ownerAliases.map((alias) => String(alias).trim().toLocaleLowerCase('en-US'))),
    approvedEmail: identity.approvedEmail.trim().toLocaleLowerCase('en-US'),
  };
}

function isOwnerName(name, identity) {
  return identity.aliases.has(String(name ?? '').trim().toLocaleLowerCase('en-US'));
}

function identityViolation(name, email, role, identity) {
  if (!isOwnerName(name, identity)) return null;
  if (String(email ?? '').trim().toLocaleLowerCase('en-US') === identity.approvedEmail) return null;
  return `owner ${role} identity must use the approved public no-reply address`;
}

function parseTrailerIdentities(message) {
  const identities = [];
  const trailerPattern = /^\s*Co-authored-by\s*:\s*(.*?)\s*<([^<>\r\n]+)>\s*$/gim;
  for (const match of String(message).matchAll(trailerPattern)) identities.push({ name: match[1], email: match[2] });
  return identities;
}

export function inspectCommitMetadata({ authorName, authorEmail, committerName, committerEmail, message }, policy) {
  const identity = policyIdentity(policy);
  const violations = [];
  const authorViolation = identityViolation(authorName, authorEmail, 'author', identity);
  if (authorViolation) violations.push(authorViolation);
  const committerViolation = identityViolation(committerName, committerEmail, 'committer', identity);
  if (committerViolation) violations.push(committerViolation);
  for (const trailer of parseTrailerIdentities(message ?? '')) {
    const trailerViolation = identityViolation(trailer.name, trailer.email, 'Co-authored-by trailer', identity);
    if (trailerViolation) violations.push(trailerViolation);
  }
  return violations;
}

function inspectCommit(commit, cwd, policy) {
  let raw;
  try {
    raw = runGit(
      ['show', '-s', '--format=%an%x00%ae%x00%cn%x00%ce%x00%B', '--end-of-options', commit],
      cwd
    );
  } catch {
    throw new Error('commit metadata could not be read');
  }
  const fields = raw.split('\0');
  if (fields.length < 5) throw new Error('commit metadata was incomplete');
  return inspectCommitMetadata(
    {
      authorName: fields[0],
      authorEmail: fields[1],
      committerName: fields[2],
      committerEmail: fields[3],
      message: fields.slice(4).join('\0'),
    },
    policy
  );
}

export function checkCommit({ commit, cwd = repoRootFromCwd(), policy = loadPolicy() }) {
  if (typeof commit !== 'string' || !HASH_PATTERN.test(commit)) throw new Error('commit reference is missing or invalid');
  return inspectCommit(commit, cwd, policy);
}

export function checkRange({ range, cwd = repoRootFromCwd(), policy = loadPolicy() }) {
  if (typeof range !== 'string' || !/^\S+\.\.\S+$/.test(range)) {
    throw new Error('range must use BASE..HEAD notation');
  }
  let commits;
  try {
    commits = runGit(['rev-list', '--reverse', '--end-of-options', range], cwd)
      .split(/\r?\n/)
      .filter(Boolean);
  } catch {
    throw new Error('range could not be resolved');
  }
  const violations = [];
  for (const commit of commits) violations.push(...inspectCommit(commit, cwd, policy));
  return [...new Set(violations)];
}

function parseGitIdentity(value, label) {
  const line = value.trim();
  const match = line.match(/^(.*?)\s*<([^<>]+)>\s+\d+\s+[+-]\d{4}$/);
  if (!match) throw new Error(`effective ${label} identity was incomplete`);
  return { name: match[1], email: match[2] };
}

export function checkPreCommit({ cwd = repoRootFromCwd(), policy = loadPolicy() } = {}) {
  let author;
  let committer;
  try {
    author = parseGitIdentity(runGit(['var', 'GIT_AUTHOR_IDENT'], cwd), 'author');
    committer = parseGitIdentity(runGit(['var', 'GIT_COMMITTER_IDENT'], cwd), 'committer');
  } catch (error) {
    throw error instanceof Error ? error : new Error('effective Git identity could not be read');
  }
  return inspectCommitMetadata(
    {
      authorName: author.name,
      authorEmail: author.email,
      committerName: committer.name,
      committerEmail: committer.email,
      message: '',
    },
    policy
  );
}

function isHash(value) {
  return HASH_PATTERN.test(value) || ZERO_HASH_PATTERN.test(value);
}

function advertisedRemoteCommits(remoteUrl, cwd) {
  if (typeof remoteUrl !== 'string' || remoteUrl.length === 0) throw new Error('pre-push remote URL was missing');
  let output;
  try {
    output = runGit(['ls-remote', remoteUrl], cwd);
  } catch {
    throw new Error('pre-push remote publication boundary could not be read');
  }
  const commits = output
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => line.trim().split(/\s+/)[0])
    .filter((hash) => HASH_PATTERN.test(hash));
  const localCommits = [];
  for (const commit of new Set(commits)) {
    try {
      runGit(['cat-file', '-e', `${commit}^{commit}`], cwd);
      localCommits.push(commit);
    } catch {
      // An advertised tip that is not fetched locally cannot be an exclusion.
    }
  }
  return localCommits;
}

function outgoingCommits(localHash, remoteHash, { cwd, remoteName, remoteUrl }) {
  if (!isHash(localHash) || !isHash(remoteHash)) throw new Error('pre-push metadata contained a missing or invalid ref');
  if (ZERO_HASH_PATTERN.test(localHash)) return [];
  try {
    runGit(['cat-file', '-e', `${localHash}^{commit}`], cwd);
    if (typeof remoteName !== 'string' || remoteName.length === 0) {
      throw new Error('pre-push remote name was missing');
    }
    const advertised = advertisedRemoteCommits(remoteUrl, cwd);
    const exclusions = ZERO_HASH_PATTERN.test(remoteHash) ? advertised : [remoteHash, ...advertised];
    if (!ZERO_HASH_PATTERN.test(remoteHash)) runGit(['cat-file', '-e', `${remoteHash}^{commit}`], cwd);
    if (exclusions.length === 0) return runGit(['rev-list', '--reverse', localHash], cwd)
      .split(/\r?\n/)
      .filter(Boolean);
    return runGit(['rev-list', '--reverse', localHash, '--not', ...exclusions], cwd)
      .split(/\r?\n/)
      .filter(Boolean);
  } catch {
    throw new Error('pre-push ref or publication boundary could not be resolved');
  }
}

export function checkPrePushInput({ input, cwd = repoRootFromCwd(), policy = loadPolicy(), remoteName, remoteUrl }) {
  if (typeof input !== 'string') throw new Error('pre-push ref input was missing');
  if (input.length === 0) return [];
  const lines = input.split(/\r?\n/);
  if (lines.at(-1) === '') lines.pop();
  const violations = [];
  const seen = new Set();
  for (const line of lines) {
    const fields = line.trim().split(/\s+/);
    if (fields.length !== 4 || fields.some((field) => field.length === 0)) {
      throw new Error('pre-push ref input contained a missing field');
    }
    const [, localHash, , remoteHash] = fields;
    for (const commit of outgoingCommits(localHash, remoteHash, { cwd, remoteName, remoteUrl })) {
      if (seen.has(commit)) continue;
      seen.add(commit);
      violations.push(...inspectCommit(commit, cwd, policy));
    }
  }
  return [...new Set(violations)];
}

function usage() {
  return [
    'Usage: node scripts/ci/check-commit-identity-privacy.mjs [--pre-commit | --pre-push-input FILE --remote-name NAME --remote-url URL | --range BASE..HEAD]',
    '',
    'Checks owner author, committer, and Co-authored-by metadata without printing email addresses.',
  ].join('\n');
}

function main(argv = process.argv.slice(2)) {
  try {
    let mode = 'pre-commit';
    let value = null;
    let remoteName = null;
    let remoteUrl = null;
    for (let index = 0; index < argv.length; index += 1) {
      const arg = argv[index];
      if (arg === '--pre-commit') mode = 'pre-commit';
      else if (arg === '--range' && argv[index + 1]) {
        mode = 'range';
        value = argv[++index];
      } else if (arg === '--pre-push-input' && argv[index + 1]) {
        mode = 'pre-push';
        value = argv[++index];
      } else if (arg === '--remote-name' && argv[index + 1]) remoteName = argv[++index];
      else if (arg === '--remote-url' && argv[index + 1]) remoteUrl = argv[++index];
      else if (arg === '--help' || arg === '-h') {
        console.log(usage());
        return 0;
      } else {
        throw new Error('unknown or incomplete command-line argument');
      }
    }
    const policy = loadPolicy();
    let violations;
    if (mode === 'range') violations = checkRange({ range: value, policy });
    else if (mode === 'pre-push') {
      let input;
      try {
        input = fs.readFileSync(value, 'utf8');
      } catch {
        throw new Error('pre-push ref input could not be read');
      }
      violations = checkPrePushInput({ input, policy, remoteName, remoteUrl });
    } else violations = checkPreCommit({ policy });
    if (violations.length === 0) {
      console.log('check-commit-identity-privacy: OK');
      return 0;
    }
    console.error('check-commit-identity-privacy: FAIL');
    for (const violation of violations) console.error(`- ${violation}`);
    return 1;
  } catch (error) {
    console.error(`check-commit-identity-privacy: FAIL\n- ${error.message}`);
    return 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  process.exitCode = main();
}
