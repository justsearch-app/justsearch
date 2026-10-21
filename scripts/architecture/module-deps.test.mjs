import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

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
