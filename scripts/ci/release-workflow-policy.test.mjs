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

test('hosted Linux provisions the commands used by the X11 desktop journeys', () => {
  const provision = workflow('qualify-release-linux').jobs.journeys.steps
    .find(s => s.name === 'Provision qualification tools').run;
  const install = provision.replaceAll('\\\n', ' ').match(/sudo apt-get install -y ([^\n]+)/);
  assert(install, 'hosted Linux must provision the desktop tools');
  const packages = new Set(install[1].trim().split(/\s+/));
  for (const [command, pkg] of Object.entries({
    'gtk-launch': 'libgtk-3-bin',
    'xdg-mime / xdg-open': 'xdg-utils',
    'xvfb-run': 'xvfb',
    xauth: 'xauth',
    'dbus-run-session / dbus-update-activation-environment': 'dbus-x11',
    openbox: 'openbox',
    xdotool: 'xdotool',
    scrot: 'scrot',
    mousepad: 'mousepad',
    tesseract: 'tesseract-ocr',
    WebKitWebDriver: 'webkit2gtk-driver',
    coredumpctl: 'systemd-coredump',
  })) {
    assert(packages.has(pkg), `${command} needs ${pkg} on the hosted runner`);
  }
});

test('hosted Linux enables user namespaces before the full harness self-test', () => {
  const steps = workflow('qualify-release-linux').jobs.journeys.steps;
  const provision = steps.find(s => s.name === 'Provision qualification tools').run;
  const namespaces = provision.indexOf('sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0');
  const selfTest = provision.indexOf("python3 -m unittest discover -s scripts/release/qual/journeys -p 'test_*.py' -v");
  assert(namespaces >= 0 && selfTest > namespaces, 'the GPU sandbox fixtures need user namespaces before discovery');
});

test('Windows uploads native viewer setup diagnostics on every outcome', () => {
  const upload = workflow('qualify-release-windows').jobs.journeys.steps
    .find(s => s.name === 'Upload viewer setup diagnostics on every outcome');
  assert.equal(upload.if, 'always()');
  assert.equal(upload.with.path, 'C:/Qual/viewer-setup/');
});

// Keep the write-enabled scripts closed to additional commands, including in the
// runner guard and embedded Python. Only line endings and outer whitespace vary.
const normalizeScript = script => script.replaceAll('\r\n', '\n').trim();
const allowedRunnerGuard = {
  linux: '[[ $RUNNER_ENVIRONMENT == github-hosted ]]',
  windows: "if ($env:RUNNER_ENVIRONMENT -cne 'github-hosted') { throw 'Refusing a non-hosted runner' }",
};
const allowedDraftFetch = {
  linux: String.raw`
set -euo pipefail
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]]
mkdir -p "$RUNNER_TEMP/release-assets"
gh release view "$RELEASE_TAG" --json isDraft,tagName,targetCommitish > "$RUNNER_TEMP/draft.json"
jq -e '.isDraft == true' "$RUNNER_TEMP/draft.json"
gh release download "$RELEASE_TAG" --dir "$RUNNER_TEMP/release-assets" \
  --pattern '*.deb' --pattern justsearch-mcp.mcpb --pattern SHA256SUMS
gh api "repos/$GITHUB_REPOSITORY/contents/packaging/mcpb/server.json?ref=$RELEASE_TAG" \
  --jq .content | base64 --decode > "$RUNNER_TEMP/server.json"`,
  windows: String.raw`
if ($env:RELEASE_TAG -cnotmatch '^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$') { throw 'Invalid release tag' }
$assets=Join-Path $env:RUNNER_TEMP release-assets
New-Item -ItemType Directory -Path $assets | Out-Null
$draft=& gh release view $env:RELEASE_TAG --json isDraft,tagName,targetCommitish
if ($LASTEXITCODE) { throw 'Draft lookup failed' }
$draft | Set-Content (Join-Path $env:RUNNER_TEMP draft.json)
if (($draft | ConvertFrom-Json).isDraft -ne $true) { throw 'An existing draft release is required' }
& gh release download $env:RELEASE_TAG --dir $assets --pattern '*-setup.exe' --pattern justsearch-mcp.mcpb --pattern SHA256SUMS
if ($LASTEXITCODE) { throw 'Draft download failed' }
$content=& gh api "repos/$env:GITHUB_REPOSITORY/contents/packaging/mcpb/server.json?ref=$env:RELEASE_TAG" --jq .content
if ($LASTEXITCODE) { throw 'Tagged registry fetch failed' }
[IO.File]::WriteAllBytes((Join-Path $env:RUNNER_TEMP server.json),[Convert]::FromBase64String(($content -join '')))`,
};

function assertRequiredBeforeProvision(job, index) {
  const step = job.steps[index];
  assert(!Object.hasOwn(step, 'if'), `${step.name} must have no if`);
  assert(!Object.hasOwn(step, 'continue-on-error'), `${step.name} must have no continue-on-error`);
  const provisionIndex = job.steps.findIndex(s => s.name === 'Provision qualification tools');
  assert(index < provisionIndex, `${step.name} must precede provisioning`);
}

for (const platform of ['linux', 'windows']) {
  const name = `qualify-release-${platform}`;
  const fetchJob = w => Object.entries(w.jobs).find(([, job]) => job.steps.some(s => /gh release (view|download)/.test(s.run)));

  const assertFetchPolicy = w => {
    const [id, job] = fetchJob(w);
    assert.deepEqual(job.permissions, { contents: 'write' }, 'draft lookup needs push access');
    assert.notEqual(id, 'journeys');
    assert.deepEqual(w.permissions, {});
    for (const [otherId, other] of Object.entries(w.jobs)) {
      if (otherId !== id) assert.deepEqual(other.permissions, { contents: 'read' });
    }
    // No checkout: repository code from the dispatched ref never runs with the write token.
    assert.deepEqual(job.steps.map(s => s.uses ?? s.name), [
      'Require a disposable GitHub-hosted runner',
      'Fetch draft bytes and tagged registry metadata',
      'actions/upload-artifact@v7',
    ], 'the write-enabled job may contain only the guard, fetch and upload');
    const runs = job.steps.filter(s => s.run);
    assert.equal(runs.length, 2, 'only the hosted guard and fetch may run with write access');
    assert.equal(normalizeScript(runs[0].run), allowedRunnerGuard[platform], 'runner guard may run only the allowed command');
    const fetch = runs[1];
    assert.equal(normalizeScript(fetch.run), normalizeScript(allowedDraftFetch[platform]), 'draft fetch may run only the allowed commands');
    assert.equal(fetch.env.GH_TOKEN, '${{ github.token }}');
    assert.equal(fetch.env.GH_REPO, '${{ github.repository }}', 'gh needs the repository without a checkout');
  };
  test(`${name}: only the draft fetch job has write access and cannot run candidate code`, () => assertFetchPolicy(workflow(name)));

  const assertJourneysVerificationPolicy = w => {
    const [id, fetch] = fetchJob(w);
    const job = w.jobs.journeys;
    assert.deepEqual(job.permissions, { contents: 'read' });
    assert.doesNotMatch(JSON.stringify(job.permissions), /write/);
    assert.equal(job.needs, id);
    const upload = fetch.steps.find(s => s.uses?.startsWith('actions/upload-artifact@'));
    const downloadIndex = job.steps.findIndex(s => s.uses === 'actions/download-artifact@v8');
    assert(downloadIndex >= 0, 'journeys must download the verified artifact');
    const download = job.steps[downloadIndex];
    assert.equal(download.with.name, upload.with.name);
    assert.equal(download.with.path, '${{ runner.temp }}');
    assert.deepEqual(upload.with.path.trim().split('\n'), [
      '${{ runner.temp }}/release-assets/', '${{ runner.temp }}/server.json', '${{ runner.temp }}/draft.json',
    ]);
    assert.equal(upload.with['if-no-files-found'], 'error');
    assert.equal(upload.with['retention-days'], 1);
    const verifyIndex = job.steps.findIndex(s => /verify_assets\(root/.test(s.run));
    const provisionIndex = job.steps.findIndex(s => s.name === 'Provision qualification tools');
    assert(verifyIndex > downloadIndex && verifyIndex < provisionIndex, 'downloaded bytes must be verified before provisioning');
    assertRequiredBeforeProvision(job, verifyIndex);
    const packagePattern = platform === 'linux' ? '*.deb' : '*-setup.exe';
    assert(job.steps[verifyIndex].run.includes(packagePattern));
    assert.match(job.steps[verifyIndex].run, /justsearch-mcp\.mcpb/);
    assert(!job.steps.some(s => /gh release (view|download)/.test(s.run)), 'journeys cannot fetch private drafts');
  };
  test(`${name}: journeys reverify transferred bytes before provisioning with a read-only token`, () => assertJourneysVerificationPolicy(workflow(name)));

  const assertFetchFailurePolicy = w => {
    const [id] = fetchJob(w);
    const job = w.jobs.journeys;
    assert.equal(job.if, 'always()');
    const guardIndex = job.steps.findIndex(s => s.env?.FETCH_RESULT === `\${{ needs.${id}.result }}`);
    const downloadIndex = job.steps.findIndex(s => s.uses?.startsWith('actions/download-artifact@'));
    assert(guardIndex >= 0 && guardIndex < downloadIndex, 'fetch failure must stop candidate preparation');
    assertRequiredBeforeProvision(job, guardIndex);
    assert.match(job.steps[guardIndex].run, /FETCH_RESULT/);
    assert.match(job.steps[guardIndex].run, /success/);
    const record = job.steps.find(s => s.run?.includes('preparation_failure('));
    assert.equal(record.if, 'always()');
    const upload = job.steps.find(s => s.name === 'Upload observations on every outcome');
    assert.equal(upload.if, 'always()');
    assert.equal(upload.with.name, `${platform}-release-journeys-\${{ inputs.tag }}`);
    assert.equal(upload.with['retention-days'], 30);
    assert.match(upload.with.path, /journey-evidence/);
    assert.match(upload.with.path, /draft\.json/);
  };
  test(`${name}: failed or skipped draft fetch still records unavailable journeys`, () => assertFetchFailurePolicy(workflow(name)));

  for (const [index, label] of [[0, 'runner guard'], [1, 'draft fetch']]) {
    test(`${name}: policy rejects a candidate command appended to the ${label}`, () => {
      const w = workflow(name);
      w.jobs.fetch.steps[index].run += '\npython candidate.py\n';
      assert.throws(() => assertFetchPolicy(w), { code: 'ERR_ASSERTION', message: /may run only the allowed command/ });
    });
  }
  for (const step of [{ run: 'python candidate.py' }, { uses: 'actions/checkout@v4' }, { uses: 'actions/setup-python@v5' }, { name: 'Unexpected step' }]) {
    test(`${name}: policy rejects an extra fetch step ${JSON.stringify(step)}`, () => {
      const w = workflow(name);
      w.jobs.fetch.steps.push(step);
      assert.throws(() => assertFetchPolicy(w), { code: 'ERR_ASSERTION' });
    });
  }
  for (const [stepName, check] of [
    ['Verify downloaded draft bytes', assertJourneysVerificationPolicy],
    ['Require successful draft fetch', assertFetchFailurePolicy],
  ]) {
    for (const [key, value] of [['if', false], ['continue-on-error', true]]) {
      test(`${name}: policy rejects ${key}: ${value} on ${stepName}`, () => {
        const w = workflow(name);
        w.jobs.journeys.steps.find(s => s.name === stepName)[key] = value;
        assert.throws(() => check(w), { code: 'ERR_ASSERTION', message: `${stepName} must have no ${key}` });
      });
    }
    test(`${name}: policy rejects provisioning before ${stepName}`, () => {
      const w = workflow(name);
      const steps = w.jobs.journeys.steps;
      const provisionIndex = steps.findIndex(s => s.name === 'Provision qualification tools');
      const [provision] = steps.splice(provisionIndex, 1);
      steps.splice(steps.findIndex(s => s.name === stepName), 0, provision);
      assert.throws(() => check(w), { code: 'ERR_ASSERTION', message: /before provisioning|must precede provisioning/ });
    });
  }
}

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
  test(`workflow policy sees a secret after a # that YAML keeps as text in ${name}`, t => {
    const { check } = policyFixture(t, name);
    assert(check('run: echo check-only', 'KEY: a#${{ secrets.JUSTSEARCH_CODESIGN_COMMAND }}').some(e => /step that signs/.test(e.message)));
  });
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
