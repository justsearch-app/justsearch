import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { changedPathsSinceMain, selectIntegrationTasks } from './java-prepush.mjs';

const script = fileURLToPath(new URL('./java-prepush.mjs', import.meta.url));
const root = fileURLToPath(new URL('../../', import.meta.url));
const gradleArgs = [
  'compileTestJava', 'compileIntegrationTestJava', 'pmdAll', '--continue',
  '-PskipErrorProneTests=false', '-PskipWebBuild=true',
];
const fallbackMessage = 'java-prepush: js-gradle-slot was not found on PATH; running the Gradle wrapper directly.\n';
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-java-prepush-'));

function run(flags, { slot = true, platform = process.platform, status = 0, spawnError = false,
  changedPaths = ['modules/ui/src/main/java/Example.java'], gitError = false } = {}) {
  const bin = path.join(temporary, `${platform}-${slot}`);
  fs.mkdirSync(bin, { recursive: true });
  if (slot) {
    const wrapper = path.join(bin, platform === 'win32' ? 'js-gradle-slot.CMD' : 'js-gradle-slot');
    fs.writeFileSync(wrapper, '#!/bin/sh\nexit 99\n');
    fs.chmodSync(wrapper, 0o755);
  }
  const gradlew = platform === 'win32' ? 'gradlew.bat' : './gradlew';
  const expectedCommand = slot ? 'js-gradle-slot' : gradlew;
  const expectedArgs = [
    ...(slot ? [gradlew] : []), ...gradleArgs,
    ...(changedPaths.some((file) => file.startsWith('modules/ui/'))
      ? [':modules:app-services:integrationTest', ':modules:ui:integrationTest'] : []),
    ...(flags.includes('--unreferenced') ? [':modules:app-launcher:test', '--tests', '*UnreferencedCodeTest'] : []),
  ];
  const valid = flags.every((flag) => ['--dry-run', '--unreferenced'].includes(flag));
  // Real PATH lookup; replace only execution so neither Gradle nor a shared slot runs.
  const stub = `
    import assert from 'node:assert/strict';
    import childProcess from 'node:child_process';
    import { syncBuiltinESMExports } from 'node:module';
    Object.defineProperty(process, 'platform', { value: ${JSON.stringify(platform)} });
    let calls = 0;
    let gitCalls = 0;
    childProcess.execFileSync = (command, args, options) => {
      assert.equal(command, 'git');
      assert.equal(options.cwd, ${JSON.stringify(root)});
      if (${gitError}) throw new Error('origin/main unavailable');
      if (++gitCalls === 1) {
        assert.deepEqual(args, ['merge-base', 'HEAD', 'origin/main']);
        return 'baseline\\n';
      }
      assert.deepEqual(args, ['diff', '--name-only', '--no-renames', '-z', 'baseline', '--']);
      return ${JSON.stringify(changedPaths.join('\0') + '\0')};
    };
    childProcess.spawnSync = (command, args, options) => {
      assert.equal(++calls, 1, 'one Gradle invocation');
      assert.equal(command, ${JSON.stringify(expectedCommand)});
      assert.deepEqual(args, ${JSON.stringify(expectedArgs)});
      assert.equal(options.cwd, ${JSON.stringify(root)});
      assert.equal(options.stdio, 'inherit');
      assert.equal(Boolean(options.shell), ${platform === 'win32'});
      assert.equal(options.env.JUSTSEARCH_PREPUSH, 'true');
      assert.equal(options.env.PATH, process.env.PATH, 'preserve inherited environment');
      console.log('Gradle invoked');
      return ${spawnError ? "{ error: new Error('execution unavailable'), status: null }" : `{ status: ${status} }`};
    };
    process.on('exit', () => assert.equal(calls, ${valid && !gitError && !flags.includes('--dry-run') ? 1 : 0}));
    syncBuiltinESMExports();
  `;
  const result = spawnSync(process.execPath, [
    '--import', `data:text/javascript,${encodeURIComponent(stub)}`, script, ...flags,
  ], { encoding: 'utf8', env: { ...process.env, PATH: bin, PATHEXT: '.COM;.EXE;.BAT;.CMD' } });
  assert.ifError(result.error);
  return { ...result, expectedCommand, expectedArgs };
}

try {
  for (const platform of new Set([process.platform, 'win32'])) {
    for (const slot of [true, false]) {
      for (const flags of [['--dry-run'], ['--dry-run', '--dry-run'], ['--dry-run', '--unreferenced'], ['--unreferenced', '--dry-run']]) {
        const result = run(flags, { slot, platform });
        assert.equal(result.status, 0, result.stderr);
        const quote = platform === 'win32' ? '"' : "'";
        const args = result.expectedArgs.map((arg) => arg.includes('*') ? `${quote}${arg}${quote}` : arg);
        const environment = platform === 'win32' ? 'set "JUSTSEARCH_PREPUSH=true" &&' : 'JUSTSEARCH_PREPUSH=true';
        assert.equal(result.stdout, `${[environment, result.expectedCommand, ...args].join(' ')}\n`);
        assert.equal(result.stderr, slot ? '' : fallbackMessage);
      }
      for (const flags of [['--unknown'], ['--dry-run', '--unknown'], ['compileTestJava'], ['--unreferenced=false'], ['']]) {
        const result = run(flags, { slot, platform });
        assert.equal(result.status, 1);
        assert.match(result.stderr, /Usage:/);
        assert.equal(result.stdout, '', 'invalid arguments never invoke Gradle');
      }
      for (const unreferenced of [false, true]) {
        for (const status of [0, 17]) {
          const result = run(unreferenced ? ['--unreferenced'] : [], { slot, platform, status });
          assert.equal(result.status, status, result.stderr);
          assert.equal(result.stdout, 'Gradle invoked\n');
          assert.equal(result.stderr, slot ? '' : fallbackMessage);
        }
      }
      const failed = run([], { slot, platform, spawnError: true });
      assert.equal(failed.status, 1);
      assert.equal(failed.stderr, `${slot ? '' : fallbackMessage}java-prepush: execution unavailable\n`);
      const noJava = run([], { slot, platform, changedPaths: ['docs/example.md'] });
      assert.equal(noJava.status, 0, noJava.stderr);
      const missingBase = run([], { slot, platform, gitError: true });
      assert.equal(missingBase.status, 1);
      assert.equal(missingBase.stderr, 'java-prepush: origin/main unavailable\n');
    }
  }
  console.log('java-prepush: PATH-present/PATH-absent execution and dry-run, POSIX/Windows selection: PASS');

  const fixtureModules = {
    'core': '',
    'runtime': '',
    'fixtures': '',
    'bridge': 'dependencies { implementation(project(":modules:core")) }',
    'app-api': 'dependencies { api(project(":modules:bridge")) }',
    'indexing': 'dependencies { runtimeOnly(project(":modules:runtime")) }',
    'app-services': 'testing { suites { val integrationTest by registering(JvmTestSuite::class) { dependencies { implementation(testFixtures(project(":modules:indexing"))) } } } }',
    'reranker': 'dependencies { add("integrationTestRuntimeOnly", project(":modules:fixtures")) }',
    'ui': 'dependencies { testFixturesImplementation(testFixtures(project(":modules:reranker"))) }',
    'system-tests': 'dependencies { add("integrationTestRuntimeOnly", project(":modules:ui")) }',
    'cycle': 'dependencies { testImplementation(project(":modules:app-api")) }',
  };
  fixtureModules['app-api'] += '\ndependencies { testRuntimeOnly(project(":modules:cycle")) }';
  const settings = Object.keys(fixtureModules).map((module) => `include(":modules:${module}")`).join('\n');
  const buildFiles = Object.fromEntries(Object.entries(fixtureModules)
    .map(([module, text]) => [`modules/${module}/build.gradle.kts`, text]));
  const select = (...changedPaths) => selectIntegrationTasks({ changedPaths, settings, buildFiles });
  assert.deepEqual(select('modules/core/src/main/java/Core.java'), [':modules:app-api:integrationTest']);
  assert.deepEqual(select('modules/runtime/src/main/java/Runtime.java'), [
    ':modules:app-services:integrationTest', ':modules:indexing:integrationTest',
  ]);
  assert.deepEqual(select('modules/fixtures/src/testFixtures/java/Fixture.java'), [
    ':modules:reranker:integrationTest', ':modules:ui:integrationTest',
  ]);
  assert.deepEqual(select('modules/ui/src/test/java/UiTest.java'), [':modules:ui:integrationTest']);
  assert.deepEqual(select('modules/system-tests/src/test/java/Test.java'), []);
  assert.deepEqual(select('modules/ui-web/src/index.ts', 'modules/shell/src/main.rs', 'docs/guide.md'), []);
  assert.deepEqual(select(), []);
  assert.deepEqual(select('modules/ui/README.md', 'modules/core/docs/notes.md'), []);
  assert.deepEqual(select('modules/core/src/main/java/Old.java', 'modules/runtime/src/main/java/New.java'), [
    ':modules:app-api:integrationTest', ':modules:app-services:integrationTest', ':modules:indexing:integrationTest',
  ]);
  const allTasks = ['app-api', 'app-services', 'indexing', 'reranker', 'ui'].map((module) => `:modules:${module}:integrationTest`);
  for (const file of ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradle.lockfile', 'gradlew', 'gradlew.bat', 'build-logic/src/main/kotlin/Plugin.kt', 'gradle/libs.versions.toml']) {
    assert.deepEqual(select(file), allTasks, file);
  }
  assert.throws(() => selectIntegrationTasks({ settings, changedPaths: ['modules/core/src/main/java/Core.java'], buildFiles: {} }), /Missing module build file/);

  // Exercise merge-base and rename handling through real Git in an isolated repository.
  const repository = path.join(temporary, 'git-fixture');
  fs.mkdirSync(repository);
  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: repository, encoding: 'utf8', env: {
      ...process.env, GIT_AUTHOR_NAME: 'Fixture', GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
      GIT_COMMITTER_NAME: 'Fixture', GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
    } });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '--quiet');
  fs.mkdirSync(path.join(repository, 'modules/core'), { recursive: true });
  fs.mkdirSync(path.join(repository, 'modules/runtime'), { recursive: true });
  fs.writeFileSync(path.join(repository, 'modules/core/Old.java'), 'class Example {}\n');
  git('add', '.');
  git('commit', '--quiet', '-m', 'base');
  const base = git('rev-parse', 'HEAD');
  git('update-ref', 'refs/remotes/origin/main', base);
  git('mv', 'modules/core/Old.java', 'modules/runtime/New.java');
  git('commit', '--quiet', '-m', 'move');
  const candidate = git('rev-parse', 'HEAD');
  git('checkout', '--quiet', '--detach', base);
  fs.writeFileSync(path.join(repository, 'upstream.md'), 'main advanced\n');
  git('add', '.');
  git('commit', '--quiet', '-m', 'upstream');
  git('update-ref', 'refs/remotes/origin/main', 'HEAD');
  git('checkout', '--quiet', '--detach', candidate);
  assert.deepEqual(changedPathsSinceMain(repository), ['modules/core/Old.java', 'modules/runtime/New.java']);

  // #1011 changed these module paths between c542733154 and dc296275ac.
  const regressionPaths = [
    "SSOT/schemas/knowledge-search-response.v1.json",
    "contracts/wire/.changesets/wp2.3a-filters.md",
    "contracts/wire/CHANGELOG.md",
    "contracts/wire/VERSION",
    "contracts/wire/knowledge.proto",
    "docs/reference/api-contract-map.md",
    "gates/test-intent/.changesets/wp2.3a-filters.md",
    "gates/test-intent/evidence/wp2.3a-domain-validation.json",
    "gates/test-intent/evidence/wp2.3a-entry-validation.json",
    "gates/test-intent/evidence/wp2.3a-filters.json",
    "gates/test-intent/evidence/wp2.3a-filters/README.md",
    "gates/test-intent/evidence/wp2.3a-filters/before-entry-validation.xml",
    "gates/test-intent/evidence/wp2.3a-filters/runner-machine-rules.patch",
    "gates/test-intent/evidence/wp2.3a-preservation.json",
    "modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ChunkSearchOps.java",
    "modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneRuntimeTypes.java",
    "modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ParentFilterOverflowException.java",
    "modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ParentMetadataQuery.java",
    "modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilder.java",
    "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java",
    "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilderTest.java",
    "modules/app-api/src/main/java/io/justsearch/app/api/DocumentService.java",
    "modules/app-api/src/main/java/io/justsearch/app/api/RetrieveContextParams.java",
    "modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchRequest.java",
    "modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchResponse.java",
    "modules/app-api/src/main/java/io/justsearch/app/api/knowledge/SearchRestrictions.java",
    "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/SearchRestrictionsTest.java",
    "modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java",
    "modules/app-services/src/main/java/io/justsearch/app/services/worker/FilterNormalizationService.java",
    "modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java",
    "modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchRequestMapper.java",
    "modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchRpcOps.java",
    "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java",
    "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteRestrictionFailureTest.java",
    "modules/ipc-common/src/main/proto/indexing.proto",
    "modules/ui-web/src/api/domains/search.ts",
    "modules/ui-web/src/api/domains/searchRestrictions.test.ts",
    "modules/ui-web/src/api/generated/schema-types/knowledge-search-response.ts",
    "modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java",
    "modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java",
    "modules/ui/src/main/resources/SSOT/schemas/knowledge-search-response.v1.json",
    "modules/ui/src/test/java/io/justsearch/ui/api/RestrictionValidationEntryTest.java",
    "modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java",
    "modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java",
    "modules/worker-services/src/main/java/io/justsearch/indexerworker/services/input/SearchInputCapture.java",
    "modules/worker-services/src/main/java/io/justsearch/indexerworker/util/ProtoConverters.java",
    "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java",
    "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/input/EntityExpansionRestrictionTest.java"
  ];
  const realSettings = fs.readFileSync(path.join(root, 'settings.gradle.kts'), 'utf8');
  const { parseSettings } = await import('../architecture/module-deps.mjs');
  const realBuildFiles = Object.fromEntries(parseSettings(realSettings).map((module) => {
    const file = `${module.slice(1).replaceAll(':', '/')}/build.gradle.kts`;
    return [file, fs.readFileSync(path.join(root, file), 'utf8')];
  }));
  assert.ok(selectIntegrationTasks({ changedPaths: regressionPaths, settings: realSettings, buildFiles: realBuildFiles })
    .includes(':modules:ui:integrationTest'), '#1011 selects UI integration tests');
  console.log('java-prepush: dependency closure, shared build inputs, merge-base renames and #1011 selection: PASS');
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
