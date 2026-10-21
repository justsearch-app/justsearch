import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyDiff, classifyPaths, LANES } from './classify-changes.mjs';
const selected = (paths) => Object.entries(classifyPaths(paths)).filter(([, value]) => value).map(([lane]) => lane);
const all = (value) => assert.deepEqual(value, Object.fromEntries(LANES.map((lane) => [lane, true])));
test('O1 docs and agent files need no scoped lanes', () => {
  assert.deepEqual(selected(['docs/reference/testing/test-intent.md', 'AGENTS.md', '.claude/settings.json']), []);
});
for (const [path, lanes] of [
  ['modules/ui-web/src/api/statusPoll.ts', ['ui', 'services', 'lifecycle', 'build']],
  ['modules/ui-web/scripts/dev-all.cjs', ['devrunner', 'ui', 'services']],
  ['governance/supervision-contract.v1.json', ['services', 'rust']],
  ['governance/store-recoverability.v1.json', ['rust', 'devrunner']],
  ['governance/exit-ledger.v1.schema.json', ['rust', 'devrunner']],
  ['modules/configuration/src/main/resources/ai/model-registry.v2.json', ['license']],
  ['scripts/codegen/gen-notices.mjs', ['license']],
  ['scripts/codegen/license-overrides.json', ['license']],
  ['modules/app-services/src/test/resources/policy/app-update-policy-cases.json', ['rust', 'services', 'ui', 'platform', 'windows', 'search', 'lifecycle']],
  ['docs/reference/legal/ai-runtime-and-model-redistribution.md', ['license']],
  ['gates/config-surface/sysaccess-allowlist.txt', ['platform']],
  ['modules/core/src/testFixtures/java/Shared.java', ['ui', 'services', 'search', 'platform', 'windows', 'lifecycle']],
  ['modules/configuration/src/main/java/Config.java', ['ui', 'services', 'search', 'platform', 'windows', 'lifecycle']],
]) test(`O3 consumers of ${path}`, () => {
  for (const lane of lanes) assert.ok(classifyPaths([path])[lane], lane);
});
test('O4 Java closure excludes unrelated lanes', () => {
  assert.deepEqual(selected(['modules/core/src/main/java/Core.java']), ['build', 'ui', 'services', 'search', 'platform', 'windows', 'lifecycle', 'integration']);
  assert.equal(classifyPaths(['modules/ui/src/main/java/UI.java']).search, false);
});
test('O5 dev-runner lanes run for supervisor/native/launcher inputs only', () => {
  for (const path of ['scripts/dev/dev-runner.cjs', 'scripts/lib/engine-discovery.mjs', 'native/bound-exec/bound-exec.c', 'scripts/supervisor-conformance/run.mjs']) assert.ok(classifyPaths([path]).devrunner, path);
  assert.equal(classifyPaths(['modules/core/src/main/java/Core.java']).devrunner, false);
});
test('mixed changes take the union', () => {
  assert.deepEqual(selected(['scripts/jseval/jseval/main.py', 'modules/ui-web/src/api/statusPoll.ts']), [...new Set([...selected(['scripts/jseval/jseval/main.py']), ...selected(['modules/ui-web/src/api/statusPoll.ts'])])].sort((a,b) => LANES.indexOf(a)-LANES.indexOf(b)));
});
for (const paths of [[], ['unclaimed/file'], ['.github/workflows/ci.yml']]) test(`O2 fallback ${JSON.stringify(paths)}`, () => all(classifyPaths(paths)));
for (const event of ['push', 'workflow_dispatch', undefined]) test(`O2 event ${event}`, () => all(classifyDiff({ eventName: event })));
const event = { eventName: 'pull_request', base: 'a'.repeat(40), head: 'b'.repeat(40) };
test('renames and deletions use both paths from the no-renames diff', () => {
  const result = classifyDiff({ ...event, git: (args) => {
    assert.deepEqual(args, ['diff', '--name-status', '--no-renames', '-z', `${event.base}...${event.head}`]);
    return 'D\0modules/core/Old.java\0A\0modules/ui-web/New.ts\0';
  } });
  assert.ok(result.search && result.ui && result.lifecycle);
});
for (const output of ['', 'M\0docs/a.md', 'M\0', 'R100\0docs/a.md\0docs/b.md\0']) test(`incomplete/malformed diff ${JSON.stringify(output)}`, () => all(classifyDiff({ ...event, git: () => output })));
test('git failure selects every lane', () => all(classifyDiff({ ...event, git: () => { throw new Error('missing base'); } })));
