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
      assert(!privateSecret.test(JSON.stringify(job.env ?? {})), `${name}/${id}: job env exposes signing secrets`);
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
        assert(validateWorkflows({ repoRoot, policy }).some(e => /step/.test(e.message)), `${ref}: job-level secret must fail even in release-signing`);
        writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch\njobs:\n  build:\n${environment}    runs-on: ubuntu-latest\n    steps:\n      - run: node scripts/release/release-candidate.mjs build options.json\n        env:\n          KEY: \${{ ${ref} }}\n`);
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
      - run: node scripts/release/release-candidate.mjs build options.json
        env:
          KEY: \${{ secrets.JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY }}
  unsigned:
    steps: *shared-steps
`);
  assert(validateWorkflows({ repoRoot, policy }).some(e => /release-signing/.test(e.message)), 'aliased secret-bearing steps must also be protected');
  writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch
jobs:
  signed:
    environment: release-signing
    steps:
      - &signing-step
        run: node scripts/release/release-candidate.mjs build options.json
        env:
          KEY: \${{ secrets.JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY }}
  unsigned:
    steps:
      - *signing-step
`);
  assert(validateWorkflows({ repoRoot, policy }).some(e => /release-signing/.test(e.message)), 'aliased secret-bearing step must also be protected');
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

test('only tag pushes enter the release graph; mirror dispatch uses protected main', () => {
  const top = workflow('build-installer');
  const eligible = (job, event_name, ref) => Function('github', 'startsWith', `return (${job.if})`)(
    { event_name, ref }, (value, prefix) => value.startsWith(prefix));
  assert.equal(eligible(top.jobs.prepare, 'push', 'refs/tags/v1.2.3'), true);
  for (const ref of ['refs/heads/main', 'refs/heads/other', 'refs/tags/v1.2.3']) {
    assert.equal(eligible(top.jobs.prepare, 'workflow_dispatch', ref), false, `manual release entry: ${ref}`);
  }
  assert.deepEqual(top.jobs.windows.needs, 'prepare');
  assert.deepEqual(top.jobs.linux.needs, 'prepare');
  assert.deepEqual(top.jobs.assemble.needs, ['windows', 'linux']);
  assert.deepEqual(top.jobs.draft.needs, 'assemble');
  const mirrors = workflow('sign-vendored-mirrors').jobs['sign-mirrors'];
  assert.equal(mirrors.environment, 'release-signing');
  assert.equal(eligible(mirrors, 'workflow_dispatch', 'refs/heads/main'), true);
  assert.equal(eligible(mirrors, 'workflow_dispatch', 'refs/tags/v1.2.3'), true);
  assert.equal(eligible(mirrors, 'push', 'refs/heads/main'), false);
  assert.equal(eligible(mirrors, 'workflow_dispatch', 'refs/heads/other'), false);
});
test('release-signing deployment instructions admit v tags and protected main', () => {
  const doc = read('docs/how-to/cut-a-release.md');
  assert.match(doc, /\*\*Tag\*\* rule matching \*\*`v\*`\*\*/);
  assert.match(doc, /\*\*Branch\*\* rule matching \*\*`main`\*\*/);
});
test('release instructions describe real-tag rehearsals and main mirror dispatch', () => {
  const doc = read('docs/how-to/cut-a-release.md');
  assert.doesNotMatch(doc, /workflow summary reports both counts|throwaway self-signed certificate|explicitly self-signed|add \*\*no branch/);
  assert.match(doc, /throwaway patch version/);
  assert.match(doc, /never publish/i);
  assert.match(doc, /gh workflow run sign-vendored-mirrors.yml --ref main/);
  assert.match(doc, /\*\*Branch\*\* rule matching \*\*`main`\*\*/);
});

function policyFixture(t, name = 'build-installer') {
  const repoRoot = mkdtempSync(path.join(tmpdir(), 'release-policy-'));
  t.after(() => rmSync(repoRoot, { recursive: true, force: true }));
  mkdirSync(path.join(repoRoot, '.github/workflows'), { recursive: true });
  mkdirSync(path.join(repoRoot, 'scripts/release'), { recursive: true });
  const file = `.github/workflows/${name}.yml`;
  const policy = { workflows: [{ path: file, expectedTriggers: ['workflow_dispatch'] }] };
  return { repoRoot, check(run, env = "KEY: ${{ secrets.JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM }}") {
    writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch
jobs:
  sign:
    environment: release-signing
    steps:
      - ${run}
        env:
          ${env}
`);
    return validateWorkflows({ repoRoot, policy });
  } };
}
for (const name of ['build-installer', 'build-installer-windows', 'build-installer-linux', 'publish-release', 'packaging-smoke']) {
  test(`workflow policy rejects a secret-bearing non-signing step in ${name}`, t => {
    const { check } = policyFixture(t, name);
    assert(check('run: echo check-only').some(e => /step that signs/.test(e.message)));
  });
  for (const expression of ['toJSON(secrets)', "secrets[format('JUSTSEARCH_{0}', 'CODESIGN_COMMAND')]", 'secrets[env.X]']) {
    test(`workflow policy rejects ${expression} even in the signing step in ${name}`, t => {
      const { check } = policyFixture(t, name);
      assert(check('run: node scripts/release/release-candidate.mjs build options.json', `KEY: \${{ ${expression} }}`)
        .some(e => /literal secret names/.test(e.message)));
    });
  }
  test(`workflow policy matches signing secret names case-insensitively in ${name}`, t => {
    const { check } = policyFixture(t, name);
    for (const secret of ['justsearch_codesign_command', 'JustSearch_Tauri_Updater_Private_Key', 'justsearch_release_metadata_private_key_pem']) {
      for (const ref of [`secrets.${secret}`, `secrets['${secret}']`]) {
        assert(check('run: echo check-only', `KEY: \${{ ${ref} }}`).some(e => /step that signs/.test(e.message)), ref);
        assert.deepEqual(check('run: node scripts/release/release-candidate.mjs build options.json', `KEY: \${{ ${ref} }}`), []);
      }
    }
  });
  test(`workflow policy restricts inherited secrets to per-release calls in ${name}`, t => {
    const { repoRoot } = policyFixture(t, name);
    const file = `.github/workflows/${name}.yml`;
    const policy = { workflows: [{ path: file, expectedTriggers: ['workflow_dispatch'] }] };
    for (const destination of ['./.github/workflows/build-installer-windows.yml', './.github/workflows/packaging-smoke.yml',
      './.github/workflows/sign-vendored-mirrors.yml', 'other/repo/.github/workflows/build.yml@main']) {
      writeFileSync(path.join(repoRoot, file), `on: workflow_dispatch\njobs:\n  call:\n    uses: ${destination}\n    secrets: inherit\n`);
      const errors = validateWorkflows({ repoRoot, policy });
      if (['./.github/workflows/build-installer-windows.yml', './.github/workflows/packaging-smoke.yml'].includes(destination)) assert.deepEqual(errors, []);
      else assert(errors.some(e => /inherited only/.test(e.message)), destination);
    }
  });
}
test('step-only secret policy excludes the separately tracked mirror workflow', t => {
  const { check } = policyFixture(t, 'sign-vendored-mirrors');
  assert.deepEqual(check('run: echo check-only'), []);
});
for (const run of ['run: echo check-only', 'run: node scripts/release/release-candidate.mjs admit',
  'run: node scripts/release/release-candidate.mjs stage-keys', 'uses: actions/upload-artifact@v7']) {
  test(`workflow policy rejects signing secrets in a non-signing step: ${run}`, t => {
    const { check } = policyFixture(t);
    assert(check(run).some(e => /step that signs/.test(e.message)));
  });
}
test('workflow policy permits exact presence flags in non-signing steps', t => {
  const { check } = policyFixture(t);
  assert.deepEqual(check('run: echo check-only', "HAS_KEY: ${{ secrets.JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM != '' }}"), []);
});
for (const destination of ['GITHUB_ENV', 'GITHUB_OUTPUT']) {
  test(`workflow policy rejects a secret-derived path exported to ${destination}`, t => {
    const { check } = policyFixture(t);
    const run = `run: |
          node scripts/release/release-candidate.mjs build options.json
          echo KEY_PATH=/tmp/private.pem >> "$${destination}"`;
    assert(check(run).some(e => /secret-derived/.test(e.message)));
  });
  test(`workflow policy rejects a script exporting a secret-derived path to ${destination}`, t => {
    const { repoRoot, check } = policyFixture(t);
    writeFileSync(path.join(repoRoot, 'scripts/release/release-candidate.mjs'), `writeFile(keyPath, process.env.KEY); appendFile(process.env.${destination}, keyPath);`);
    assert(check('run: node scripts/release/release-candidate.mjs build options.json').some(e => /secret-derived/.test(e.message)));
  });
}

test('release admission has only presence flags, and only descriptor assembly receives the metadata secret', () => {
  const { prepare, assemble } = workflow('build-installer').jobs;
  const admission = prepare.steps.find(s => s.run?.includes('release-candidate.mjs admit'));
  for (const flag of ['HAS_CODESIGN_COMMAND', 'HAS_TAURI_KEY', 'HAS_METADATA_KEY']) assert.match(admission.env[flag], /!= ''/);
  assert.equal(Object.keys(admission.env).filter(k => k !== 'GH_TOKEN').length, 3);
  for (const job of [prepare, assemble]) {
    for (const step of job.steps) {
      if (!privateSecret.test(JSON.stringify(step))) continue;
      if (step === admission) continue;
      assert.match(step.run, /release-candidate\.mjs build/);
      assert.equal(step.env.METADATA_PRIVATE_KEY_PEM, '${{ secrets.JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM }}');
    }
  }
});
