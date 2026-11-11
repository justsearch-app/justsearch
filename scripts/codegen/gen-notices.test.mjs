/**
 * Tempdoc 632 — unit tests for the NOTICE/THIRD_PARTY_NOTICES projection (gen-notices.mjs).
 *
 * Guards the behavioral guarantees: dual-licensed JVM deps are claimed under their permissive
 * override (not the jk1-collapsed copyleft string); the model list projects from the registry
 * `license` field (the regression that the old heredoc silently dropped Qwen); and the model render
 * names every package. The exported functions take injectable data, so no real report is needed.
 *
 * Run with: `node scripts/codegen/gen-notices.test.mjs`  (exits non-zero on any failure)
 */

import assert from 'node:assert/strict';
import { loadJvm, loadModels, loadCargo, renderModelsNotice, renderThirdParty, renderModelLicenseTable, injectLegalDocTable, isStrongCopyleft, nativeDispositionCheck, loadNative } from './gen-notices.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const OVERRIDES = {
  jvmModuleLicense: {
    'net.java.dev.jna:jna': 'Apache-2.0',
    'ch.qos.logback:logback-classic': 'EPL-1.0',
  },
  nativeRuntimes: [{ name: 'NVIDIA cuDNN runtime', license: 'LicenseRef-NVIDIA-cuDNN-SLA', ref: 'cuDNN SLA — separate from the CUDA EULA' }],
};

run('dual-licensed JVM dep is claimed under its permissive override, not jk1 LGPL', () => {
  const report = {
    dependencies: [
      { moduleName: 'net.java.dev.jna:jna', moduleVersion: '5.18.1', moduleLicense: 'GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1' },
      { moduleName: 'ch.qos.logback:logback-classic', moduleVersion: '1.5.32', moduleLicense: 'GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1' },
      { moduleName: 'org.apache.lucene:lucene-core', moduleVersion: '10.4.0', moduleLicense: 'Apache License, Version 2.0' },
    ],
  };
  const jvm = loadJvm(report, OVERRIDES);
  assert.equal(jvm.find((d) => d.name === 'net.java.dev.jna:jna').license, 'Apache-2.0');
  assert.equal(jvm.find((d) => d.name === 'ch.qos.logback:logback-classic').license, 'EPL-1.0');
  // non-overridden deps keep their declared license
  assert.equal(jvm.find((d) => d.name === 'org.apache.lucene:lucene-core').license, 'Apache License, Version 2.0');
});

run('models project from the registry license field (incl. the chat model the old heredoc omitted)', () => {
  const registry = {
    packages: [
      { id: 'chat', label: 'Chat model', license: 'Apache-2.0', termsUrl: 'https://huggingface.co/Qwen/Qwen3.5-9B', targetDir: '' },
      { id: 'ner', label: 'NER', license: 'AFL-3.0', termsUrl: 'x', targetDir: 'onnx/ner' },
    ],
  };
  const models = loadModels(registry);
  const chat = models.find((m) => m.id === 'chat');
  assert.equal(chat.license, 'Apache-2.0');
  const notice = renderModelsNotice(models);
  assert.match(notice, /Chat model/);
  assert.match(notice, /Qwen3\.5-9B/);
  assert.match(notice, /AFL-3\.0/);
});

run('THIRD_PARTY_NOTICES surfaces the separate-cuDNN-SLA note', () => {
  const tpn = renderThirdParty([], [], [], { runtimes: OVERRIDES.nativeRuntimes, tessCore: { version: '5.5.0', license: 'Apache-2.0' }, tessDlls: { libraries: [{ dll: 'libtesseract-5.dll', library: 'Tesseract', license: 'Apache-2.0' }] } });
  assert.match(tpn, /cuDNN SLA/);
  assert.match(tpn, /SEPARATE|separate/);
});

run('Tesseract notice attributes German, English and orientation data from tessdata_fast', () => {
  const tpn = renderThirdParty([], [], [], loadNative());
  assert.match(tpn, /German, English and orientation \(OSD\) tessdata_fast — Apache-2\.0/);
});

run('Rust/Cargo deps are sorted and rendered into THIRD_PARTY_NOTICES', () => {
  const cargo = loadCargo([
    { name: 'tauri', version: '2.0.0', license: 'Apache-2.0 OR MIT' },
    { name: 'adler2', version: '2.0.1', license: '0BSD OR MIT OR Apache-2.0' },
  ]);
  assert.equal(cargo[0].name, 'adler2'); // sorted
  const tpn = renderThirdParty([], [], [], { runtimes: [], tessCore: { version: '5', license: 'Apache-2.0' }, tessDlls: { libraries: [] } }, cargo);
  assert.match(tpn, /Rust \/ Cargo \(desktop shell\) dependencies/);
  assert.match(tpn, /tauri 2\.0\.0 — Apache-2\.0 OR MIT/);
});

run('isStrongCopyleft flags GPL-only but not LGPL/MPL/exception/dual-with-permissive', () => {
  assert.equal(isStrongCopyleft('GPL-3.0-or-later'), true);
  assert.equal(isStrongCopyleft('AGPL-3.0'), true);
  assert.equal(isStrongCopyleft('SSPL-1.0'), true);
  assert.equal(isStrongCopyleft('LGPL-2.1-or-later'), false); // weak copyleft, fine as separate component
  assert.equal(isStrongCopyleft('MPL-2.0'), false);
  assert.equal(isStrongCopyleft('GPL-3.0-or-later WITH GCC-exception-3.1'), false); // linking exception
  assert.equal(isStrongCopyleft('BSD-3-Clause OR GPL-2.0-only'), false); // dual offers permissive
  assert.equal(isStrongCopyleft('MIT OR Apache-2.0 OR LGPL-2.1-or-later'), false);
  assert.equal(isStrongCopyleft('MIT'), false);
});

run('renderModelLicenseTable projects all models except cuda-runtime, with license + source', () => {
  const models = loadModels({
    packages: [
      { id: 'chat', label: 'Chat', license: 'Apache-2.0', termsUrl: 'https://huggingface.co/Qwen/Qwen3.5-9B', targetDir: '' },
      { id: 'ner', label: 'NER', license: 'AFL-3.0', termsUrl: 'https://x/ner', targetDir: 'onnx/ner' },
      { id: 'cuda-runtime', label: 'CUDA', license: 'LicenseRef-NVIDIA-CUDA-EULA', termsUrl: 'https://nvidia', targetDir: 'cuda12' },
    ],
  });
  const table = renderModelLicenseTable(models);
  assert.match(table, /\| Model \| License \| Source \|/);
  assert.match(table, /Chat \(`chat`\) \| Apache-2\.0 .*Qwen3\.5-9B/);
  assert.match(table, /NER \(`ner`\) \| AFL-3\.0/);
  assert.doesNotMatch(table, /cuda-runtime/); // GPU runtime excluded from the model table
});

run('injectLegalDocTable splices between markers and throws if markers are missing', () => {
  const models = loadModels({ packages: [{ id: 'ner', label: 'NER', license: 'AFL-3.0', termsUrl: 'https://x', targetDir: 'd' }] });
  const START = '<!-- GENERATED:MODEL_LICENSES:BEGIN — do not edit; run: node scripts/codegen/gen-notices.mjs -->';
  const END = '<!-- GENERATED:MODEL_LICENSES:END -->';
  const doc = `# legal\nintro\n${START}\nOLD\n${END}\noutro\n`;
  const out = injectLegalDocTable(models, doc);
  assert.match(out, /# legal\nintro/);
  assert.match(out, /outro/);
  assert.match(out, /NER \(`ner`\) \| AFL-3\.0/);
  assert.doesNotMatch(out, /OLD/); // old region replaced
  assert.throws(() => injectLegalDocTable(models, '# legal\nno markers'), /missing the MODEL_LICENSES markers/);
});

// --- native-binary disposition gate (tempdoc 632) ---
const firingDll = { tessDlls: { libraries: [{ dll: 'libjbig-0.dll', library: 'JBIG-KIT', license: 'GPL-2.0-or-later', disposition: 'strong-copyleft' }] }, runtimes: [], tessCore: {} };
const restrictedRuntime = { tessDlls: { libraries: [] }, runtimes: [{ name: 'NVIDIA cuDNN runtime', license: 'LicenseRef-NVIDIA-cuDNN-SLA', disposition: 'restricted' }], tessCore: {} };

run('disposition gate: a FIRING artifact with NO acceptance fails', () => {
  const e = nativeDispositionCheck(firingDll, { acceptances: [] });
  assert.equal(e.length, 1);
  assert.match(e[0], /JBIG-KIT.*FIRING.*NO dated acceptance/);
});

run('disposition gate: a FIRING artifact WITH a dated acceptance passes', () => {
  const e = nativeDispositionCheck(firingDll, { acceptances: [{ artifact: 'JBIG-KIT', disposition: 'strong-copyleft', date: '2026-06-23' }] });
  assert.equal(e.length, 0);
});

run('disposition gate: restricted runtime also fires + is suppressible', () => {
  assert.equal(nativeDispositionCheck(restrictedRuntime, { acceptances: [] }).length, 1);
  assert.equal(nativeDispositionCheck(restrictedRuntime, { acceptances: [{ artifact: 'NVIDIA cuDNN runtime', disposition: 'restricted', date: 'x' }] }).length, 0);
});

run('disposition gate: a missing/invalid disposition fails (no silent default)', () => {
  const missing = { tessDlls: { libraries: [{ dll: 'x.dll', library: 'Mystery', license: 'MIT' }] }, runtimes: [], tessCore: {} };
  const e = nativeDispositionCheck(missing, { acceptances: [] });
  assert.equal(e.length, 1);
  assert.match(e[0], /Mystery.*missing\/invalid disposition/);
});

run('disposition gate: a stale acceptance (no matching firing artifact) fails', () => {
  const permissiveOnly = { tessDlls: { libraries: [{ dll: 'x.dll', library: 'zlib', license: 'Zlib', disposition: 'permissive' }] }, runtimes: [], tessCore: {} };
  const e = nativeDispositionCheck(permissiveOnly, { acceptances: [{ artifact: 'ghost', disposition: 'strong-copyleft', date: 'x' }] });
  assert.equal(e.length, 1);
  assert.match(e[0], /ghost.*no matching firing native artifact/);
});

run('disposition gate: the REAL manifests classify the firing set as exactly {JBIG-KIT, NVIDIA CUDA, NVIDIA cuDNN}', () => {
  const native = loadNative();
  const firing = [
    ...native.tessDlls.libraries.filter((l) => l.disposition === 'strong-copyleft' || l.disposition === 'restricted').map((l) => l.library),
    ...native.runtimes.filter((r) => r.disposition === 'strong-copyleft' || r.disposition === 'restricted').map((r) => r.name),
  ].sort();
  assert.deepEqual(firing, ['JBIG-KIT', 'NVIDIA CUDA runtime (cudart, cuBLAS, cuBLAS Lt)', 'NVIDIA cuDNN runtime'].sort());
  // and the real ledger suppresses them all (no errors against real data)
  assert.equal(nativeDispositionCheck().length, 0);
});

if (failures.length) {
  console.error(`gen-notices.test: ${failures.length} FAILED`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`gen-notices.test: ${passed} passed`);
