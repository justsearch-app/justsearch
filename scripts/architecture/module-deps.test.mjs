import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { parseProjectDependencies, parseSettings } from './module-deps.mjs';

test('pure parsers include runtime, test fixtures and add configuration forms', () => {
  assert.deepEqual(parseSettings('// include(":modules:ignored")\nval example = """include(":modules:string")"""\ninclude(":modules:one")\ninclude(":modules:two", ":reports")'),
    [':modules:one', ':modules:two', ':reports']);
  const deps = parseProjectDependencies(`
dependencies {
  api(project(":modules:core"))
  runtimeOnly(project(":modules:runtime"))
  testImplementation(testFixtures(project(":modules:fixture")))
  testFixturesApi(project(":modules:fixture-api"))
  add("integrationTestRuntimeOnly", project(":modules:ui"))
  add("integrationTestImplementation", testFixtures(project(":modules:core")))
  add("runtimeOnly", /* comment */ project(":modules:added-runtime"))
  add(customRuntime.name, project(":modules:custom-runtime"))
  // add("runtimeOnly", project(":modules:comment"))
  val example = """implementation(project(":modules:string"))"""
}
buildscript { dependencies { add("runtimeOnly", project(":modules:build-tool")) } }
testing { suites { val integrationTest by registering(JvmTestSuite::class) { dependencies {
  implementation(testFixtures(project(":modules:suite-fixture")))
  runtimeOnly(project(":modules:suite-runtime"))
} } } }
`);
  assert.deepEqual([...deps.production].sort(), ['added-runtime', 'core', 'runtime']);
  assert.deepEqual([...deps.test].sort(), ['core', 'fixture', 'fixture-api', 'suite-fixture', 'suite-runtime', 'ui']);
  assert.deepEqual([...deps.all].sort(), ['added-runtime', 'core', 'custom-runtime', 'fixture', 'fixture-api', 'runtime', 'suite-fixture', 'suite-runtime', 'ui']);
});

test('only main dependencies are production; suite dependencies stay test-only', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'module-deps-'));
  try {
    mkdirSync(path.join(root, 'scripts/architecture'), { recursive: true });
    mkdirSync(path.join(root, 'modules/example'), { recursive: true });
    copyFileSync(new URL('./module-deps.mjs', import.meta.url), path.join(root, 'scripts/architecture/module-deps.mjs'));
    writeFileSync(path.join(root, 'settings.gradle.kts'), 'include(":modules:example")');
    writeFileSync(path.join(root, 'modules/example/build.gradle.kts'), `
// dependencies { implementation(project(":modules:comment")) }
val text = """dependencies { implementation(project(":modules:string")) }"""
/* nested { block /* dependencies { } */ comment } */
dependencies {
  implementation(project(":modules:core"))
  compileOnly(project(":modules:annotations"))
  compileOnlyApi(project(":modules:public-annotations"))
  runtimeOnly(project(":modules:runtime"))
  testImplementation(project(":modules:unit"))
  for (m in auditedModules) testImplementation(project(":modules:$m"))
}
buildscript { dependencies { implementation(project(":modules:build-tool")) } }
testing { suites {
  val integrationTest by registering(JvmTestSuite::class) { dependencies {
    implementation(project(":modules:suite"))
    runtimeOnly(project(":modules:suite-runtime"))
  } }
} }
`);
    const result = spawnSync(process.execPath, ['scripts/architecture/module-deps.mjs'], { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const graph = JSON.parse(readFileSync(path.join(root, 'tmp/arch-preflight/module-deps.json'), 'utf8'));
    assert.deepEqual(graph.modules[0].productionDeps, ['annotations', 'core', 'public-annotations', 'runtime']);
    assert.deepEqual(graph.modules[0].testDeps, ['suite', 'suite-runtime', 'unit']);
    const doc = readFileSync(path.join(root, 'tmp/arch-preflight/module-deps-tempdoc.md'), 'utf8');
    assert.match(doc, /annotations` \(compile-only\)/);
    assert.doesNotMatch(doc, /:modules:\$m/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
