#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';

import {
  checkBundleResources,
  checkNsisHooks,
  checkSidecarStaging,
  runCheck,
} from './check-update-preserves-models.mjs';

const failures = [];
let passed = 0;

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

// -------------------- bundle.resources --------------------

const CLEAN_CONF = JSON.stringify({
  bundle: { resources: ['resources/headless/**/*', 'resources/vc_redist.x64.exe'] },
});

test('the shipped bundle.resources shape passes', () => {
  assert.deepEqual(checkBundleResources(CLEAN_CONF), []);
});

test('bundle.resources carrying a models tree fails', () => {
  const conf = JSON.stringify({ bundle: { resources: ['resources/models/**/*'] } });
  assert.equal(checkBundleResources(conf).length, 1);
});

test('bundle.resources carrying weight files fails', () => {
  for (const glob of ['resources/**/*.onnx', 'payload/*.gguf', 'x/*.safetensors']) {
    const conf = JSON.stringify({ bundle: { resources: [glob] } });
    assert.equal(checkBundleResources(conf).length, 1, `expected ${glob} to be rejected`);
  }
});

test('unparseable tauri.conf.json is a failure, not a silent pass', () => {
  assert.equal(checkBundleResources('{not json').length, 1);
});

// -------------------- sidecar staging --------------------

const CLEAN_GRADLE = `
val bundleSidecarResources by tasks.registering(Sync::class) {
  from(rootProject.layout.projectDirectory.dir("SSOT")) { into("SSOT") }
  from(headlessRuntimeImageDir) { into("runtime") }
  from(llamaStageDir) { into("native-bin/llama-server") }
}

val somethingElse by tasks.registering {
  from(modelsDir) { into("models") }
}
`;

test('the shipped sidecar staging shape passes', () => {
  assert.deepEqual(checkSidecarStaging(CLEAN_GRADLE), []);
});

test('staging model weights into the sidecar fails', () => {
  const gradle = CLEAN_GRADLE.replace(
    'from(llamaStageDir) { into("native-bin/llama-server") }',
    'from(modelsDir) { into("models") }',
  );
  assert.equal(checkSidecarStaging(gradle).length, 1);
});

test('a renamed or deleted sidecar task fails loudly rather than passing vacuously', () => {
  // Otherwise the gate would report OK forever after a refactor moved the payload elsewhere.
  assert.equal(checkSidecarStaging('val other by tasks.registering {}').length, 1);
});

test('the scan is scoped to the sidecar task, not the whole build file', () => {
  // `somethingElse` above stages models and must NOT be attributed to the sidecar payload.
  assert.deepEqual(checkSidecarStaging(CLEAN_GRADLE), []);
});

// -------------------- NSIS hooks --------------------

const CLEAN_NSH = `
!macro NSIS_HOOK_PREUNINSTALL
  ; Clean up machine policy directory if empty (best-effort, non-recursive).
  RMDir "$COMMONAPPDATA\\JustSearch"
!macroend
`;

test('the shipped uninstall hook shape passes', () => {
  assert.deepEqual(checkNsisHooks(CLEAN_NSH), []);
});

test('a recursive RMDir /r anywhere in the hooks fails', () => {
  const nsh = CLEAN_NSH.replace('RMDir "$COMMONAPPDATA', 'RMDir /r "$COMMONAPPDATA');
  assert.equal(nsh.includes('/r'), true);
  assert.ok(checkNsisHooks(nsh).length >= 1);
});

test('deleting under a user-data root fails even when non-recursive', () => {
  const nsh = CLEAN_NSH.replace('$COMMONAPPDATA', '$LOCALAPPDATA');
  assert.equal(checkNsisHooks(nsh).length, 1);
});

test('a commented-out recursive delete is not a violation', () => {
  const nsh = '  ; RMDir /r "$LOCALAPPDATA\\JustSearch"\n';
  assert.deepEqual(checkNsisHooks(nsh), []);
});

test('the Windows resource overlay cannot ship model weights past a clean base', () => {
  const files = {
    'modules/shell/src-tauri/tauri.conf.json': CLEAN_CONF,
    'modules/shell/src-tauri/tauri.windows.conf.json': JSON.stringify({ bundle: { resources: ['models/chat.gguf'] } }),
    'modules/ui/build.gradle.kts': CLEAN_GRADLE,
    'modules/shell/src-tauri/nsis/installer-hooks.nsh': CLEAN_NSH,
  };
  assert.equal(runCheck('', file => files[file]).length, 1);
});

if (failures.length > 0) {
  console.error(`check-update-preserves-models.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-update-preserves-models.test OK - ${passed} assertions passed.`);
