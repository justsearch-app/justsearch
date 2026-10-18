#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Gate for tempdoc 617 D2: "app update never touches models".
 *
 * D2 chose monolithic full-installer updates on the condition that models are reused in place and
 * never re-downloaded, and says this "must be an explicit invariant". Tempdoc 617 §7.1 then asserts
 * it as fact. Nothing enforced it, so the property was true only by accident of how the installer
 * happened to be assembled.
 *
 * It matters because a monolithic update runs uninstall-then-install over an existing install. The
 * two ways to break it are (a) the packaging surface starts carrying model weights, so every update
 * ships or overwrites ~9 GB, and (b) an uninstall hook recursively deletes a user-data root, so the
 * upgrade half of the cycle destroys the models it was supposed to preserve. Both are one careless
 * line, and neither shows up until a real user upgrades.
 *
 * This is a static gate on the DECLARED surface. It cannot prove a built installer is clean. The
 * empirical half is `.github/workflows/update-preserves-models.yml`, which installs a published
 * release N, seeds model files, installs N+1 over it, then uninstalls, asserting the seeded bytes
 * survive both — see `docs/how-to/verify-update-preserves-models.md`. That lane supersedes tempdoc
 * 617 §9 item 3 (the published installer-over-release round) and the model-survival half of item 5,
 * which had never been executed. §9 item 4 (the in-app updater path) still needs a GUI Sandbox.
 * What this gate prevents is the declaration silently changing underneath that lane.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { readTauriConfig } from './lib/tauri-config.mjs';

/** File shapes that mean "model weights", not config/tokenizer JSON. */
const MODEL_WEIGHT = /\.onnx\b|\.gguf\b|\.safetensors\b|\bmodel-weights\b/i;

/** User-data roots an installer/uninstaller must never remove. Models live under these. */
const USER_DATA_ROOT = /\$LOCALAPPDATA|\$APPDATA|\$PROFILE|\$DOCUMENTS/;

export function checkBundleResources(tauriConfJson) {
  const failures = [];
  let conf;
  try {
    conf = JSON.parse(tauriConfJson);
  } catch (error) {
    return [`tauri.conf.json does not parse: ${error.message}`];
  }
  for (const entry of conf?.bundle?.resources ?? []) {
    if (MODEL_WEIGHT.test(entry) || /(^|\/)models(\/|$)/i.test(entry)) {
      failures.push(
        `bundle.resources ships model weights: "${entry}". D2 requires models be reused in ` +
          `place, never carried by the installer.`,
      );
    }
  }
  return failures;
}

export function checkSidecarStaging(gradleKts) {
  const start = gradleKts.indexOf('val bundleSidecarResources');
  if (start === -1) {
    return [
      'bundleSidecarResources task not found in modules/ui/build.gradle.kts. It stages the Tauri ' +
        'payload, so this gate cannot verify the installer excludes model weights — re-point the ' +
        'gate rather than deleting it.',
    ];
  }
  // The task body ends where the next top-level declaration begins.
  const rest = gradleKts.slice(start + 1);
  const nextDecl = rest.search(/\n(?:val|tasks|abstract|open|private|internal|fun|plugins)\b/);
  const body = nextDecl === -1 ? rest : rest.slice(0, nextDecl);

  const failures = [];
  for (const [index, line] of body.split('\n').entries()) {
    const code = line.replace(/\/\/.*$/, '');
    if (!/\bfrom\s*\(/.test(code)) continue;
    if (MODEL_WEIGHT.test(code) || /\bmodelsDir\b|"models"|'models'/.test(code)) {
      failures.push(
        `bundleSidecarResources stages model weights (body line ${index + 1}): ${line.trim()}. ` +
          `The sidecar payload must carry runtime and config only.`,
      );
    }
  }
  return failures;
}

export function checkNsisHooks(nsh) {
  const failures = [];
  for (const [index, line] of nsh.split('\n').entries()) {
    const code = line.replace(/^\s*;.*$/, '');
    if (/\bRMDir\s+\/r\b/i.test(code)) {
      failures.push(
        `installer-hooks.nsh line ${index + 1} uses recursive RMDir /r: ${line.trim()}. ` +
          `A monolithic update runs uninstall-then-install, so a recursive delete here removes ` +
          `user state mid-upgrade. Non-recursive RMDir (empty-only) is the safe form.`,
      );
    }
    if (/\b(RMDir|Delete)\b/i.test(code) && USER_DATA_ROOT.test(code)) {
      failures.push(
        `installer-hooks.nsh line ${index + 1} deletes under a user-data root: ${line.trim()}. ` +
          `Models and authored state live there; D2 requires they survive an update.`,
      );
    }
  }
  return failures;
}

export function runCheck(root, read = (p) => readFileSync(resolve(root, p), 'utf8')) {
  let conf;
  try { conf = JSON.stringify(readTauriConfig(root, 'windows', read)); }
  catch (error) { return [error.message]; }
  return [
    ...checkBundleResources(conf),
    ...checkSidecarStaging(read('modules/ui/build.gradle.kts')),
    ...checkNsisHooks(read('modules/shell/src-tauri/nsis/installer-hooks.nsh')),
  ];
}

const invokedDirectly = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop());
if (invokedDirectly) {
  const failures = runCheck(process.cwd());
  if (failures.length > 0) {
    console.error(`update-preserves-models gate FAILED (${failures.length}):`);
    for (const failure of failures) console.error(`  - ${failure}`);
    process.exit(1);
  }
  console.log(
    'update-preserves-models gate OK - installer payload declares no model weights and no ' +
      'uninstall hook removes a user-data root (tempdoc 617 D2).',
  );
}
