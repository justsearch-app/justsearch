import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const script = readFileSync(new URL('./verify-installer-nsis-win.ps1', import.meta.url), 'utf8');
// The same unmodified, installed-Engine response used by the Linux smoke test.
const health = JSON.parse(readFileSync(new URL('./fixtures/installed-engine-health.json', import.meta.url), 'utf8'));
const readiness = script.split('function Wait-ForBackendReady {')[1].split('function Start-HeadlessBackend {')[0];

// Evaluate the PowerShell rule's property reads and accepted values on Linux,
// where pwsh is unavailable. Do not duplicate the intended path or state here.
const reads = [...readiness.matchAll(/\$(\w+) = \[string\]\$lastHealthJson\.([\w.]+)/g)];
assert(reads.length, 'readiness must read a health component');
function ready(response, indexAvailable = true) {
  const [variable] = reads[0].slice(1);
  assert(reads.every(match => match[1] === variable), 'health rule uses one readiness variable');
  const value = reads.map(match => match[2].split('.').reduce((obj, key) => obj?.[key], response))
    .find(state => state !== undefined) ?? '';
  const accepted = [...readiness.matchAll(new RegExp(`\\$${variable} -eq "([^"]+)"`, 'g'))]
    .map(match => match[1]);
  assert(accepted.length, 'readiness must compare the health state');
  assert.match(readiness, /if \(\$lastStatusJson\.indexAvailable -ne \$true\) \{\s+Add-BackoffSleep[^\n]+\s+continue\s+\}/);
  return indexAvailable === true && accepted.includes(value);
}

test('Windows smoke accepts recorded current health without a worker component', () => {
  assert(!('worker' in health.components));
  assert(ready(health), 'recorded Engine has a ready index; waiting for absent worker health would time out');
});

test('Windows smoke requires an available, ready index', () => {
  for (const state of ['STARTING', 'ERROR', 'UP', 'LIFECYCLE_STATE_READY', undefined]) {
    const response = structuredClone(health);
    if (state === undefined) delete response.components.index;
    else response.components.index.state = state;
    assert.equal(ready(response), false, `index state ${state}`);
  }
  assert.equal(ready({}), false);
  assert.equal(ready(health, false), false);
});

test('Windows smoke requires all legal files beside the installed Engine jar', () => {
  const installed = script.split('$headlessDir = Split-Path -Parent $uiJar.FullName')[1];
  assert(installed, 'legal checks must use the installed jar directory');
  const legal = installed.match(/foreach \(\$legalFile in @\(([^)]+)\)\) \{([^}]+)\}/);
  assert(legal, 'installed payload must check its legal files');
  assert.deepEqual([...legal[1].matchAll(/"([^"]+)"/g)].map(match => match[1]),
    ['LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES', 'T3-CODE-NOTICE.md', 'LUCIDE-LICENSE.txt']);
  assert.match(legal[2], /Join-Path -Path \$headlessDir -ChildPath "legal\/\$legalFile"/);
  assert.match(legal[2], /Assert \(Test-Path -LiteralPath \$legalPath -PathType Leaf\) "Installed payload missing legal\/\$legalFile at \$legalPath"/);
});
