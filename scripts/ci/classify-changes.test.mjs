import assert from 'node:assert/strict';
import test from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { classifyDiff, classifyPaths, LANES } from './classify-changes.mjs';
const selected = (paths) => Object.entries(classifyPaths(paths)).filter(([, value]) => value).map(([lane]) => lane);
const all = (value) => assert.deepEqual(value, Object.fromEntries(LANES.map((lane) => [lane, true])));
test('O1 docs and agent files need no scoped lanes', () => {
  assert.deepEqual(selected(['docs/reference/testing/test-intent.md', 'docs/reference/contributing/mcp-dev-tools.md', 'AGENTS.md', '.claude/settings.json']), []);
});
test('the generated legal document selects only its notice-producing lane', () => {
  assert.deepEqual(selected(['docs/reference/legal/ai-runtime-and-model-redistribution.md']), ['license']);
});
for (const file of ['scripts/ci/pr-flakes.tsv', 'scripts/ci/pr-flakes.mjs']) {
  test(`shared PR filter selects every lane: ${file}`, () => all(classifyPaths([file])));
}
for (const [path, lanes] of [
  ['docs/reference/legal/ai-runtime-and-model-redistribution.md', ['license']],
  ['scripts/jseval/jseval/ui_a11y_gate.py', ['frontend']],
  ['governance/ui-a11y-baseline.v1.json', ['frontend']],
  ['governance/ui-proportion-baseline.v1.json', ['frontend']],
  ['governance/status-facts.v1.json', ['frontend']],
  ['modules/configuration/src/main/resources/ai/model-registry.v2.json', ['frontend']],
  ['modules/ui-web/src/api/statusPoll.ts', ['frontend']],
  ['.gitignore', ['devrunner', 'windows', 'jseval']],
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
  ['modules/app-services/src/test/resources/io/justsearch/app/services/diagnostics/hotspot-fatal-headers/native-oom.txt', ['rust', 'devrunner', 'services']],
  ['gates/config-surface/sysaccess-allowlist.txt', ['platform']],
  ['modules/core/src/testFixtures/java/Shared.java', ['ui', 'services', 'search', 'platform', 'windows', 'lifecycle']],
  ['modules/configuration/src/main/java/Config.java', ['ui', 'services', 'search', 'platform', 'windows', 'lifecycle']],
]) test(`O3 consumers of ${path}`, () => {
  for (const lane of lanes) assert.ok(classifyPaths([path])[lane], lane);
});
test('O4 Java closure excludes unrelated lanes', () => {
  assert.deepEqual(selected(['modules/core/src/main/java/Core.java']), ['build', 'ui', 'services', 'search', 'platform', 'windows', 'lifecycle', 'integration', 'jseval']);
  assert.equal(classifyPaths(['modules/ui/src/main/java/UI.java']).search, false);
});
test('O5 dev-runner lanes follow their inputs rather than the Java closure', () => {
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
for (const output of ['', 'M\0docs/a.md', 'M\0', 'R100\0docs/a.md\0docs/b.md\0', 'R\0docs/a.md\0', 'C\0docs/a.md\0', 'U\0docs/a.md\0']) test(`incomplete/malformed diff ${JSON.stringify(output)}`, () => all(classifyDiff({ ...event, git: () => output })));
test('git failure selects every lane', () => all(classifyDiff({ ...event, git: () => { throw new Error('missing base'); } })));

for (const path of [
  'governance/config-apply.v1.json', 'governance/worktree-lifecycle.v1.json',
  'governance/fixtures/exit-ledger/cases/children-basic/children.v1.jsonl',
]) test(`governance data keeps its non-dev-runner consumers: ${path}`, () => {
  const result = classifyPaths([path]);
  for (const lane of LANES.filter(lane => lane !== 'devrunner')) assert.ok(result[lane], lane);
});
for (const path of [
  'modules/ui/build.gradle.kts',
  'modules/shell/src-tauri/src/engine_host.rs',
  'modules/shell/src-tauri/src/shell_actuator.rs',
  'scripts/jseval/lane-f/e-crash-evidence.mjs',
]) test(`spread-out dev-runner input: ${path}`, () => {
  assert.ok(classifyPaths([path]).devrunner);
  assert.ok(classifyPaths([path]).windows);
});

// Read-only drift check: a literal path/folder in a lane's tests is a conservative
// consumer edge. Expand folders over tracked files so adding a new reader or file
// cannot quietly weaken the classifier. Runtime opens are audited separately.
test('tracked repository paths named by Java, Node, Rust and Gradle readers select their readers', () => {
  const tracked = [...new Set(execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).split('\0').filter(Boolean))];
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
  add('scripts/codegen/gen-notices.mjs', 'license');
  for (const source of tracked) {
    // Build scripts and shared convention plugins can read test inputs at
    // configuration time, including only when JUSTSEARCH_PR_CI is enabled.
    if (source.endsWith('.gradle.kts') || /^build-logic\/.*\.kt$/.test(source)) {
      for (const [lane, selected] of Object.entries(classifyPaths([source]))) if (selected) add(source, lane);
    }
    if (/^scripts\/jseval\/jseval\/ui.*\.py$/.test(source)) add(source, 'frontend');
    if (/^modules\/shell\/src-tauri\/.*\.rs$/.test(source)) add(source, 'rust');
    if (/^scripts\/dev\/test-dev-runner-.*\.mjs$/.test(source)) add(source, 'devrunner');
    if (/^scripts\/lib\/.*\.test\.mjs$/.test(source)) { add(source, 'devrunner'); add(source, 'windows'); }
    if (/^scripts\/supervisor-conformance\/.*\.mjs$/.test(source)) { add(source, 'devrunner'); add(source, 'rust'); }
  }
  const classification = new Map();
  const gaps = new Set();
  const checked = new Map();
  // Check each (literal, lane) once. Folder expansion and classification are
  // indexed/cached, including joined paths: never literals x all tracked files.
  const checkLiteral = (literal, source, lanes) => {
    if (!literal.includes('/')) return; // checkout root markers are not reads
    const files = present.has(literal) ? [literal] : folders.get(literal);
    if (!files) return;
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
  };
  for (const [source, lanes] of readers) {
    // Git still lists deleted/moved sources until staged; the destination runs in Public claims.
    let text;
    try { text = readFileSync(source, 'utf8'); } catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    if (source.endsWith('.gradle.kts') || source.endsWith('.kt')) {
      // A direct configuration-time read applies to every job configuring this
      // script. inputs.file declarations instead belong to their owning tasks.
      for (const match of text.matchAll(/\brootProject\.file\(\s*["']([^"'\r\n]+)["']\s*\)\s*\.\s*(?:readLines|readText|readBytes|useLines|forEachLine|inputStream|reader|bufferedReader)\(/g)) {
        checkLiteral(match[1], source, lanes);
      }
      continue;
    }
    // Bound each path expression to a line. Exclude calls nested in the
    // expression rather than scanning arbitrary source prefixes repeatedly.
    const expressions = [...text.matchAll(/\b(?:join|resolve|Path\.of|Paths\.get)\(([^;{}()\r\n]{1,1000})\)/g)];
    const bindings = new Map();
    for (const expression of expressions) {
      const strings = [...expression[1].matchAll(/["']([^"'\r\n]+)["']/g)].map(entry => entry[1]);
      if (!strings.length) continue;
      const first = expression[1].split(',')[0].trim();
      const base = bindings.get(first);
      // A scratch variable (work, data, modelsRoot, ...) is not the checkout.
      if (!base && !/^["']/.test(first) && !/^(?:REPO_ROOT|ROOT|repoRoot|root|HERE|__dirname|parent)$/.test(first)) continue;
      let literal = path.posix.join(...(base ? [base, ...strings] : strings));
      if (literal.startsWith('../')) literal = path.posix.normalize(path.posix.join(path.posix.dirname(source), literal));
      const declaration = text.slice(Math.max(0, expression.index - 80), expression.index).match(/(?:const|let|var) (\w+) = (?:path\.)?$/);
      if (declaration && folders.has(literal)) {
        bindings.set(declaration[1], literal);
        // A folder assigned as the base of later joins is a locator, not a
        // recursive read. The child expressions below supply the actual inputs.
        const uses = text.slice(expression.index + expression[0].length);
        if (uses.includes(`join(${declaration[1]},`) && !new RegExp(`(?:readdir|copy|fingerprint)\\w*\\([^;\\n]*\\b${declaration[1]}\\b`).test(uses)) continue;
      }
      checkLiteral(literal, source, lanes);
    }
    for (const expression of text.matchAll(/(?:["'][^"'\r\n]+["']\s*\/\s*)+["'][^"'\r\n]+["']/g)) {
      const strings = [...expression[0].matchAll(/["']([^"'\r\n]+)["']/g)].map(entry => entry[1]);
      checkLiteral(path.posix.join(...strings), source, lanes);
    }
    for (const match of text.matchAll(/"((?:\\.|[^"\\\r\n])*)"|'((?:\\.|[^'\\\r\n])*)'|`((?:\\.|[^`\\])*)`/g)) {
      // Fixture strings and assertions do not read repository data. Inspect path
      // construction, file reads, and named path constants; bare root markers
      // locate the checkout rather than consuming every file below that marker.
      const prefix = text.slice(Math.max(0, match.index - 180), match.index);
      const pathUse = /(?:\b(?:resolve|join|readFileSync|readFile|readAllLines|readString|read_to_string|copyFileSync|URL|Path.of|Paths.get|include_str!|include_bytes!)\([^;{}\n]*|\b[A-Z][A-Z_0-9]*\s*=\s*)$/.test(prefix)
        // Across lines, stop at a closed call so an earlier assertion argument
        // cannot make a fixture string in a following argument look like a read.
        || /\b(?:resolve|join|readFileSync|readFile|readAllLines|readString|read_to_string|copyFileSync|URL|Path.of|Paths.get|include_str!|include_bytes!)\([^;{})]*$/.test(prefix);
      if (!pathUse) continue;
      let literal = (match[1] ?? match[2] ?? match[3]).replaceAll('\\', '/').replace(/\/$/, '');
      if (/^\.\.?\//.test(literal)) {
        if (/\b(?:URL|include_str!|include_bytes!)\($/.test(prefix)) {
          literal = path.posix.normalize(path.posix.join(path.posix.dirname(source), literal));
        } else if (source.endsWith('.rs') && prefix.includes('CARGO_MANIFEST_DIR')) {
          literal = path.posix.normalize(path.posix.join('modules/shell/src-tauri', literal));
        }
      }
      if (!literal.includes('/') && folders.has(literal)) continue;
      if (!literal.includes('/') && /assert(?:True|False|Equals)/.test(prefix)) continue;
      if ([...bindings.values()].includes(literal) && folders.has(literal)) continue;
      checkLiteral(literal, source, lanes);
    }
  }
  // ui_fixtures resolves this relative to the discovered frontend fixture dir.
  const fixtureSource = 'scripts/jseval/jseval/ui_fixtures.py';
  assert.match(readFileSync(fixtureSource, 'utf8'), /configuration\/src\/main\/resources\/ai\/model-registry\.v2\.json/);
  checkLiteral('modules/configuration/src/main/resources/ai/model-registry.v2.json', fixtureSource, ['frontend']);
  for (const lane of ['ui', 'services', 'search', 'platform', 'lifecycle', 'integration']) {
    assert.ok(checked.get('scripts/ci/pr-flakes.tsv')?.has(lane), `Gradle rootProject.file read audited for ${lane}`);
  }
  console.log(`classifier read audit: ${readers.size} readers, ${checked.size} repository inputs, ${gaps.size} gaps`);
  assert.deepEqual([...gaps].sort(), []);
});

test('Java-only and gate changes do not select dev-runner or its PR conformance', () => {
  for (const path of ['modules/core/src/main/java/Core.java', 'modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java', 'modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java', 'gates/config-surface/sysaccess-allowlist.txt', 'gates/test-efficacy/strength-baseline.v1.json', 'scripts/release/app-release-assets.mjs', 'scripts/jseval/jseval/backend.py', 'governance/config-apply.v1.json']) {
    const result = classifyPaths([path]);
    assert.equal(result.devrunner, false, path);
    assert.equal(result.rust, path.startsWith('governance/'), path);
  }
});
