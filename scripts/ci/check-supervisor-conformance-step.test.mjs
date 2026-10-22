import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const yaml = require('js-yaml');

test('Windows Tauri conformance propagates either native command failure', () => {
  const workflow = yaml.load(fs.readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8'));
  const steps = Object.values(workflow.jobs).flatMap(job => job.steps ?? []);
  for (const name of ['Supervisor conformance (Tauri adapter)', 'Supervisor conformance repeated restart (tauri)']) {
  const step = steps.find(candidate => candidate.name === name);
  const lines = step.run.trim().split('\n').map(line => line.trim());
  const commands = lines.filter(line => line.startsWith('node '));
  assert.equal(commands.length, 1, 'each native command has its own checked step');
  for (const command of commands) {
    assert.equal(lines[lines.indexOf(command) + 1],
      'if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }',
      `native failure must stop the step immediately: ${command}`);
  }
  assert.equal(step.shell, 'pwsh');
  }
});
