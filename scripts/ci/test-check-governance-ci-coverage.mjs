import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import { checkCoverage } from './check-governance-ci-coverage.mjs';

function check(command, extra = '') {
  return checkCoverage({
    workflowText: `jobs:\n  public-claims:\n    name: Public claims\n    runs-on: ubuntu-latest\n${extra}    steps:\n      - run: ${command}\n`,
    registry: { gates: [{ id: 'engine-port', selfTestFixturesDir: 'fixtures' }] },
    requiredChecks: ['Public claims'], consultRegister: {},
  });
}

test('fixture tests cannot substitute for production enforcement', () => {
  const r = check('node scripts/governance/run.mjs --self-test --mode gate');
  assert.equal(r.rows[0].fixtures, true);
  assert.equal(r.rows[0].production, false);
  assert.match(r.issues[0], /engine-port: no required hosted production invocation/);
});
test('only required hosted gate-mode production commands count', () => {
  const command = 'node scripts/governance/run.mjs --gate engine-port --mode gate';
  for (const productionCommand of [command, 'node scripts/governance/run.mjs --mode gate']) {
    const result = check(productionCommand);
    assert.equal(result.rows[0].production, true, productionCommand);
    assert.deepEqual(result.issues, []);
  }
  assert.equal(check(command, '    continue-on-error: true\n').issues.length, 1);
  assert.equal(check(command.replace('--mode gate', '--mode warn')).issues.length, 1);
  assert.equal(check(command + ' --fixture tmp/tree').issues.length, 1);
  assert.equal(check('node scripts/governance/gates/engine-port/enforcer.test.mjs').issues.length, 1);
});
for (const option of ['--preflight HEAD', '--explain engine-port', '--suggest-changeset', '--help', '-h']) {
  for (const selection of ['--gate engine-port --mode gate', '--mode gate']) {
    for (const position of ['before', 'after']) {
      const args = position === 'before' ? `${option} ${selection}` : `${selection} ${option}`;
      test(`${option} ${position} ${selection} receives no production credit`, () => {
        const result = check(`node scripts/governance/run.mjs ${args}`);
        assert.equal(result.rows[0].production, false, args);
        assert.equal(result.rows[0].fixtures, false, args);
        assert.equal(result.issues.length, 1, args);
        assert.match(result.issues[0], /engine-port: no required hosted production invocation/);
      });
    }
  }
}
test('short help and shell failure masking cannot receive production credit', () => {
  const command = 'node scripts/governance/run.mjs --gate engine-port --mode gate';
  for (const suffix of ['-h', '|| true', '| cat', '; true', '&', '&& echo done || true']) {
    const result = check(command + ' ' + suffix);
    assert.equal(result.rows[0].production, false, suffix);
    assert.equal(result.issues.length, 1, suffix);
  }
});
test('the final mode controls production enforcement', () => {
  const command = 'node scripts/governance/run.mjs --gate engine-port';
  assert.equal(check(command + ' --mode gate --mode warn').rows[0].production, false);
  assert.equal(check(command + ' --mode warn --mode gate').rows[0].production, true);
});
test('arguments must select known gates in the production registry', () => {
  const command = 'node scripts/governance/run.mjs --gate engine-port --mode gate';
  for (const suffix of ['--registry tmp/empty.json', '--gate missing', '--unknown', '--mode']) {
    assert.equal(check(command + ' ' + suffix).rows[0].production, false, suffix);
  }
  assert.equal(check('node scripts/governance/run.mjs --gate engine-port,missing --mode gate').rows[0].production, false);
});
test('quoted values and flag-shaped output paths are parsed as argument values', () => {
  const command = 'node scripts/governance/run.mjs --gate "engine-port" --mode "gate" --out "--help"';
  assert.equal(check(command).rows[0].production, true);
});
test('the checked-in workflow enforces engine-port on production', () => {
  const read = (p) => fs.readFileSync(new URL('../../' + p, import.meta.url), 'utf8');
  const r = checkCoverage({
    workflowText: read('.github/workflows/ci.yml'),
    registry: JSON.parse(read('governance/registry.v1.json')),
    requiredChecks: JSON.parse(read('scripts/ci/workflow-signal-policy.v1.json')).workflows.find((w) => w.name === 'CI').requiredStatusChecks,
    consultRegister: JSON.parse(read('governance/consult-register.v1.json')),
    exemptions: JSON.parse(read('scripts/ci/governance-ci-coverage-policy.v1.json')).exemptions,
  });
  assert.equal(r.rows.find((row) => row.id === 'engine-port').production, true);
  assert.deepEqual(r.issues, []);
});
