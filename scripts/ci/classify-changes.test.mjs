import assert from 'node:assert/strict';
import test from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { classifyDiff, classifyPaths, LANES } from './classify-changes.mjs';
const selected = (paths) => Object.entries(classifyPaths(paths)).filter(([, value]) => value).map(([lane]) => lane);
const all = (value) => assert.deepEqual(value, Object.fromEntries(LANES.map((lane) => [lane, true])));
test('O1 docs and agent files need no scoped lanes', () => {
  assert.deepEqual(selected(['docs/reference/testing/test-intent.md', 'docs/reference/legal/ai-runtime-and-model-redistribution.md', 'AGENTS.md', '.claude/settings.json']), []);
});
for (const [path, lanes] of [
  ['modules/ui-web/src/api/statusPoll.ts', ['ui', 'services', 'lifecycle', 'build']],
  ['modules/ui-web/scripts/dev-all.cjs', ['devrunner', 'ui', 'services']],
  ['governance/supervision-contract.v1.json', ['services', 'rust']],
  ['governance/store-recoverability.v1.json', ['rust', 'devrunner']],
  ['governance/exit-ledger.v1.schema.json', ['rust', 'devrunner']],
  ['modules/configuration/src/main/resources/ai/model-registry.v2.json', ['license']],
  ['scripts/codegen/gen-notices.mjs', ['license']],
  ['build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt', ['license']],
  ['gradle.properties', ['license']],
  ['scripts/codegen/license-overrides.json', ['license']],
  ['modules/app-services/src/test/resources/policy/app-update-policy-cases.json', ['rust', 'services', 'ui', 'platform', 'windows', 'search', 'lifecycle']],
  ['gates/config-surface/sysaccess-allowlist.txt', ['platform']],
  ['modules/core/src/testFixtures/java/Shared.java', ['ui', 'services', 'search', 'platform', 'windows', 'lifecycle']],
  ['modules/configuration/src/main/java/Config.java', ['ui', 'services', 'search', 'platform', 'windows', 'lifecycle']],
]) test(`O3 consumers of ${path}`, () => {
  for (const lane of lanes) assert.ok(classifyPaths([path])[lane], lane);
});
test('O4 Java closure excludes unrelated lanes', () => {
  assert.deepEqual(selected(['modules/core/src/main/java/Core.java']), ['build', 'ui', 'services', 'search', 'platform', 'windows', 'devrunner', 'lifecycle', 'integration', 'jseval']);
  assert.equal(classifyPaths(['modules/ui/src/main/java/UI.java']).search, false);
});
test('O5 dev-runner lanes conservatively include Java and spread-out runtime inputs', () => {
  for (const path of ['scripts/dev/dev-runner.cjs', 'scripts/lib/engine-discovery.mjs', 'native/bound-exec/bound-exec.c', 'scripts/supervisor-conformance/run.mjs']) assert.ok(classifyPaths([path]).devrunner, path);
  assert.equal(classifyPaths(['modules/core/src/main/java/Core.java']).devrunner, true);
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
for (const output of ['', 'M\0docs/a.md', 'M\0', 'R100\0docs/a.md\0docs/b.md\0', 'R\0docs/a.md\0', 'C\0docs/a.md\0', 'U\0docs/a.md\0']) test(`incomplete/malformed diff ${JSON.stringify(output)}`, () => all(classifyDiff({ ...event, git: () => output })));
test('git failure selects every lane', () => all(classifyDiff({ ...event, git: () => { throw new Error('missing base'); } })));

for (const path of [
  'governance/config-apply.v1.json', 'governance/worktree-lifecycle.v1.json',
  'governance/fixtures/exit-ledger/cases/children-basic/children.v1.jsonl',
]) test(`governance data is a shared input: ${path}`, () => all(classifyPaths([path])));
for (const path of [
  'modules/ui/build.gradle.kts',
  'modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java',
  'modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java',
  'scripts/jseval/jseval/backend.py',
]) test(`spread-out dev-runner input: ${path}`, () => {
  assert.ok(classifyPaths([path]).devrunner);
  assert.ok(classifyPaths([path]).windows);
});

// Read-only drift check: a literal path/folder in a lane's tests is a conservative
// consumer edge. Expand folders over tracked files so adding a new reader or file
// cannot quietly weaken the classifier. Runtime opens are audited separately.
test('tracked repository paths named by Java and Node tests select their readers', () => {
  const tracked = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).split('\0').filter(Boolean);
  const present = new Set(tracked);
  const folders = new Map();
  for (const file of tracked) {
    for (let slash = file.indexOf('/'); slash !== -1; slash = file.indexOf('/', slash + 1)) {
      const folder = file.slice(0, slash);
      if (!folders.has(folder)) folders.set(folder, []);
      folders.get(folder).push(file);
    }
  }
  const policy = JSON.parse(readFileSync(new URL('./unit-test-shard-policy.v1.json', import.meta.url), 'utf8'));
  const readers = new Map();
  const add = (source, lane) => {
    if (!readers.has(source)) readers.set(source, new Set());
    readers.get(source).add(lane);
  };
  for (const shard of policy.lanes) {
    const lane = shard.classifierLane ?? ({ 'app-ui-ui': 'ui', 'app-ui-services': 'services', 'search-worker': 'search', 'platform-contracts': 'platform' })[shard.lane];
    assert.ok(LANES.includes(lane), `missing classifier lane for ${shard.lane}`);
    for (const task of shard.gradleTasks) {
      const module = task.split(':')[2];
      for (const source of tracked.filter(file => file.startsWith(`modules/${module}/src/test`) && file.endsWith('.java'))) add(source, lane);
    }
  }
  for (const source of tracked) {
    if (/^scripts\/dev\/test-dev-runner-.*\.mjs$/.test(source)) add(source, 'devrunner');
    if (/^scripts\/lib\/.*\.test\.mjs$/.test(source)) { add(source, 'devrunner'); add(source, 'windows'); }
    if (/^scripts\/supervisor-conformance\/.*\.mjs$/.test(source)) { add(source, 'devrunner'); add(source, 'rust'); }
  }
  const classification = new Map();
  const gaps = new Set();
  const checked = new Map();
  for (const [source, lanes] of readers) {
    // Git still lists deleted/moved sources until staged; the destination runs in Public claims.
    let text;
    try { text = readFileSync(source, 'utf8'); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    for (const match of text.matchAll(/"((?:\\.|[^"\\\r\n])*)"|'((?:\\.|[^'\\\r\n])*)'|`((?:\\.|[^`\\])*)`/g)) {
      // Fixture strings and assertions do not read repository data. Inspect path
      // construction, file reads, and named path constants; bare root markers
      // locate the checkout rather than consuming every file below that marker.
      const prefix = text.slice(Math.max(0, match.index - 180), match.index);
      const pathUse = /(?:\b(?:resolve|join|readFileSync|readFile|readAllLines|readString|URL|Path.of|Paths.get)\([^;{}\n]*|\b[A-Z][A-Z_0-9]*\s*=\s*)$/.test(prefix);
      if (!pathUse) continue;
      const literal = (match[1] ?? match[2] ?? match[3]).replaceAll('\\', '/').replace(/\/$/, '');
      if (!literal.includes('/') && folders.has(literal)) continue;
      if (!literal.includes('/') && /assert(?:True|False|Equals)/.test(prefix)) continue;
      const files = present.has(literal) ? [literal] : folders.get(literal);
      if (!files) continue;
      if (!checked.has(literal)) checked.set(literal, new Set());
      for (const lane of lanes) {
        if (checked.get(literal).has(lane)) continue;
        checked.get(literal).add(lane);
        for (const file of files) {
          if (!classification.has(file)) classification.set(file, classifyPaths([file]));
          if (!classification.get(file)[lane]) {
            gaps.add(`${lane}: ${source} names ${literal} -> ${file}`);
            break;
          }
        }
      }
    }
  }
  assert.deepEqual([...gaps].sort(), []);
});

test('shared non-module inputs selecting a Java lane also select both Windows lanes', () => {
  for (const path of ['gates/config-surface/sysaccess-allowlist.txt', 'gates/test-efficacy/strength-baseline.v1.json', 'scripts/release/app-release-assets.mjs']) {
    const result = classifyPaths([path]);
    assert.ok(result.devrunner && result.windows, path);
  }
});
