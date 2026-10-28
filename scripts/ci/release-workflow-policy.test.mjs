import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { validateWorkflows } from './check-workflow-triggers.mjs';

const yaml = createRequire(import.meta.url)('js-yaml');
const read = name => readFileSync(new URL(`../../${name}`, import.meta.url), 'utf8');
const workflow = name => yaml.load(read(`.github/workflows/${name}.yml`));
const privateSecret = /secrets(?:\.JUSTSEARCH_(?:CODESIGN_\w+|TAURI_UPDATER_PRIVATE_KEY\w*|RELEASE_METADATA_PRIVATE_KEY_PEM)|\[['"]JUSTSEARCH_(?:CODESIGN_\w+|TAURI_UPDATER_PRIVATE_KEY\w*|RELEASE_METADATA_PRIVATE_KEY_PEM)['"]\])/;

test('production secrets belong only to release-signing jobs; unsigned builds have no secret references', () => {
  for (const name of ['build-installer', 'build-installer-windows', 'build-installer-linux', 'sign-vendored-mirrors']) {
    const w = workflow(name);
    assert(!privateSecret.test(JSON.stringify(w.env ?? {})), `${name}: workflow env exposes signing secrets`);
    for (const [id, job] of Object.entries(w.jobs)) {
      if (privateSecret.test(JSON.stringify(job))) assert.equal(job.environment, 'release-signing', `${name}/${id}`);
    }
  }
  for (const name of ['build-installer', 'build-installer-windows']) {
    const w = workflow(name);
    assert(!w.on.workflow_dispatch?.inputs?.sign && !w.on.workflow_call?.inputs?.sign, `${name}: manual signing input`);
  }
  for (const name of ['build-installer-windows', 'build-installer-linux']) {
    const jobs = Object.values(workflow(name).jobs);
    const unsigned = jobs.find(job => String(job.if).includes('!inputs.release'));
    assert(unsigned, `${name}: separate unsigned build`);
    assert(!privateSecret.test(JSON.stringify(unsigned)), `${name}: unsigned secrets`);
    const pins = unsigned.steps.find(step => step.run?.includes('packaging-smoke.mjs pins'));
    assert(pins, `${name}: generated test pins`);
    assert.doesNotMatch(pins.if, /sandboxTestMode/, `${name}: qualification builds also need test pins`);
  }
});

test('workflow policy refuses private signing secrets outside the protected environment', t => {
  const repoRoot = mkdtempSync(path.join(tmpdir(), 'release-policy-'));
  t.after(() => rmSync(repoRoot, { recursive: true, force: true }));
  mkdirSync(path.join(repoRoot, '.github/workflows'), { recursive: true });
  const file = '.github/workflows/test.yml';
  const policy = { workflows: [{ path: file, expectedTriggers: ['workflow_dispatch'] }] };
  for (const secret of ['JUSTSEARCH_CODESIGN_COMMAND', 'JUSTSEARCH_CODESIGN_PFX_B64',
    'JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY', 'JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD',
    'JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM']) {
    for (const ref of [`secrets.${secret}`, `secrets['${secret}']`]) {
      for (const environment of ['', '    environment: unsigned-build\n', '    environment:\n      name: unsigned-build\n']) {
        writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch\njobs:\n  build:\n${environment}    runs-on: ubuntu-latest\n    env:\n      KEY: \${{ ${ref} }}\n`);
        assert(validateWorkflows({ repoRoot, policy }).some(e => /release-signing/.test(e.message)), `${ref}: must reject unprotected secret`);
      }
      for (const environment of ['    environment: release-signing\n', '    environment:\n      name: release-signing\n']) {
        writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch\njobs:\n  build:\n${environment}    runs-on: ubuntu-latest\n    env:\n      KEY: \${{ ${ref} }}\n`);
        assert.deepEqual(validateWorkflows({ repoRoot, policy }), []);
      }
    }
  }
  writeFileSync(path.join(repoRoot, file), 'on: workflow_dispatch\nenv:\n  KEY: ${{ secrets.JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY }}\njobs: {}\n');
  assert(validateWorkflows({ repoRoot, policy }).some(e => /release-signing/.test(e.message)));
  writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch
jobs:
  signed:
    environment: release-signing
    steps: &shared-steps
      - run: echo signing
        env:
          KEY: \${{ secrets.JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY }}
  unsigned:
    steps: *shared-steps
`);
  assert(validateWorkflows({ repoRoot, policy }).some(e => /release-signing/.test(e.message)), 'aliased secret-bearing steps must also be protected');
});

test('branch/manual Windows builds cannot enter release concurrency groups', () => {
  const top = workflow('build-installer');
  const groupFor = ref => {
    const group = top.concurrency.group;
    if (!group.startsWith('${{')) return group;
    return Function('github', 'startsWith', 'format', `return (${group.slice(3, -2)})`)(
      { ref }, (value, prefix) => value.startsWith(prefix), (value, arg) => value.replace('{0}', arg));
  };
  const releaseGroup = workflow('publish-release').concurrency.group;
  assert.equal(groupFor('refs/tags/v1.2.3'), releaseGroup);
  for (const ref of ['refs/heads/main', 'refs/heads/qualification']) assert.notEqual(groupFor(ref), releaseGroup);
  const windows = workflow('build-installer-windows');
  assert(!windows.concurrency, 'unsigned reusable calls must not serialize release signing');
  for (const job of Object.values(windows.jobs)) {
    if (job.concurrency?.group === 'release-signing-spend') {
      assert.equal(job.environment, 'release-signing');
      assert.match(job.if, /inputs.release/);
      assert.match(job.if, /refs\/tags\/v/);
    }
  }
});

test('Linux signer receives private key and password only through environment', () => {
  const script = read('scripts/ci/package-installer-linux.sh');
  const signer = script.slice(script.indexOf('npm --prefix modules/shell run tauri -- signer sign'));
  assert(!/--private-key|--password|\$TAURI_SIGNING_PRIVATE_KEY/.test(signer), 'npm echoes signer arguments, which must contain no key or password');
});

test('download documentation covers both packages, checksums and saved provenance', () => {
  const doc = read('docs/how-to/verify-your-download.md');
  for (const expected of [/SHA256SUMS/, /\.deb/, /gh attestation verify/, /provenance\.sigstore\.json/, /--repo justsearch-app\/justsearch/]) assert.match(doc, expected);
  assert.doesNotMatch(doc, /requires owner approval before the build starts|generated by\s*\[`scripts\/ci\/build-release-assets.ps1/);
});
