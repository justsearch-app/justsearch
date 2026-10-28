#!/usr/bin/env node
import { appendFileSync, readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { generateKeyPairSync } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { signArtifact } from '../release/updater-v2-fixture.mjs';
export function packagingChanged(files) {
  return files.some(file => /^(modules\/|SSOT\/|packaging\/|gradle\/|build-logic\/|scripts\/(ci|release|lib)\/|\.github\/workflows\/|(?:build|settings)\.gradle\.kts$|gradle.properties$|gradlew|package(?:-lock)?\.json$)/.test(file));
}
function api(endpoint) { return JSON.parse(execFileSync('gh', ['api', '--paginate', '--slurp', endpoint], { encoding: 'utf8' })); }
function env(name, value) { appendFileSync(process.env.GITHUB_ENV, `${name}=${value}\n`); }
function output(name, value) { appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`); }
function unique(dir, suffix) {
  const matches = readdirSync(dir).filter(n => n.endsWith(suffix));
  if (matches.length !== 1) throw Error(`expected exactly one ${suffix} in ${dir}`);
  return path.resolve(dir, matches[0]);
}
function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'pins') {
    const key = generateKeyPairSync('ed25519').publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64');
    env('JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY', key);
    env('JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID', 'packaging-smoke-test-only');
    env('JUSTSEARCH_RELEASE_DESCRIPTOR_URL', 'https://example.invalid/releases/latest/download/release.v2.json');
    env('JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY', signArtifact(Buffer.from('test only')).artifactPublicKey);
    env('JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID', 'packaging-smoke-test-only');
    return;
  }
  if (command === 'cached') {
    const artifacts = api(`/repos/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}/artifacts?per_page=100`).flatMap(p => p.artifacts);
    output('exists', artifacts.some(a => a.name === args[0] && !a.expired) ? 'true' : 'false'); return;
  }
  if (command === 'changed') {
    const runs = api(`/repos/${process.env.GITHUB_REPOSITORY}/actions/workflows/packaging-smoke.yml/runs?branch=main&status=success&per_page=100`).flatMap(p => p.workflow_runs);
    const previous = runs.find(r => String(r.id) !== process.env.GITHUB_RUN_ID);
    let changed = true;
    if (previous) {
      const ancestor = execFileSync('git', ['merge-base', previous.head_sha, 'HEAD'], { encoding: 'utf8' }).trim();
      // A rewritten main cannot be assumed equivalent to the last smoke.
      if (ancestor === previous.head_sha) {
        const files = execFileSync('git', ['diff', '--name-only', previous.head_sha, 'HEAD'], { encoding: 'utf8' }).trim().split('\n');
        changed = packagingChanged(files);
      }
    }
    output('changed', String(changed)); return;
  }
  if (command === 'evidence') {
    const [platform, destination] = args;
    mkdirSync(path.dirname(destination), { recursive: true });
    writeFileSync(destination, JSON.stringify({ platform, passed: true, commit: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID,
      checks: platform === 'windows' ? ['installer-execution-level', 'installer_verify (fresh, restart, upgrade-arrival)'] : ['dpkg install', 'installed Engine ready', 'bundled help search'] }, null, 2)+'\n'); return;
  }
  if (command === 'plan') {
    const root = args[0];
    const targets = {};
    for (const [platform, suffix] of [['windows', '.exe'], ['linux', '.deb']]) {
      const installerPath = unique(path.join(root, platform), suffix);
      targets[`${platform}-x86_64`] = { installerPath, artifactSignaturePath: `${installerPath}.sig` };
    }
    const options = { version: process.env.GITHUB_REF_NAME.slice(1), sequence: Number(readFileSync(path.join(root, 'context/sequence'), 'utf8')),
      targets, outDir: path.resolve('dist/candidate'), compatibilityRegisterPath: path.resolve('governance/store-recoverability.v1.json'),
      compatibilityBaselinePath: path.resolve(root, 'context/compatibility-baseline.json'), mcpb: unique(path.join(root, 'windows'), '.mcpb'),
      evidence: { windows: path.resolve(root, 'checks/smoke-windows.json'), linux: path.resolve(root, 'linux/smoke-linux.json') } };
    writeFileSync(args[1], JSON.stringify(options)); return;
  }
  throw Error('usage: packaging-smoke.mjs pins|cached NAME|changed|evidence PLATFORM PATH|plan INPUTS CONFIG');
}
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) main();
