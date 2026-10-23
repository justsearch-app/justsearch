#!/usr/bin/env node
/**
 * Quick verification of AI prerequisites implementation.
 * Run: node scripts/verify-prerequisites.mjs
 */

import { execSync } from 'child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'fs';
import { join } from 'path';
import { executableName } from './lib/native-targets.cjs';

const llamaServer = executableName('llama-server');
const llamaLibrary = process.platform === 'win32' ? 'llama.dll' : 'libllama.so';

const state = {
  criticalFailures: 0,
  warnings: 0,
};

function pass(message) {
  console.log(`[OK] ${message}`);
}

function fail(message) {
  state.criticalFailures += 1;
  console.log(`[FAIL] ${message}`);
}

function warn(message) {
  state.warnings += 1;
  console.log(`[WARN] ${message}`);
}

function info(message) {
  console.log(`  ${message}`);
}

function readText(path) {
  return readFileSync(path, 'utf8');
}

function isDirectory(path) {
  if (!existsSync(path)) {
    return false;
  }
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function verifyLlamaRuntime() {
  const candidates = [];
  const home = process.env.JUSTSEARCH_HOME;
  const appData = process.env.APPDATA;
  const localAppData = process.env.LOCALAPPDATA;

  if (home) {
    candidates.push(join(home, 'native-bin', 'llama-server'));
  }
  if (appData) {
    candidates.push(join(appData, 'io.justsearch.shell', 'native-bin', 'llama-server'));
  }
  if (localAppData) {
    candidates.push(join(localAppData, 'JustSearch', 'native-bin', 'llama-server'));
  }
  candidates.push('modules/ui/native-bin/llama-server');
  candidates.push('native-bin/llama-server');

  const seen = new Set();
  const uniqueCandidates = candidates.filter((p) => {
    if (seen.has(p)) {
      return false;
    }
    seen.add(p);
    return true;
  });

  const existingRoots = uniqueCandidates.filter((p) => isDirectory(p));
  for (const root of existingRoots) {
    const directExe = join(root, llamaServer);
    if (existsSync(directExe)) {
      pass(`${llamaServer} found at ${directExe}`);
      return { ok: true, resolvedRoot: root };
    }

    const variantsDir = join(root, 'variants');
    if (isDirectory(variantsDir)) {
      const variantDirs = readdirSync(variantsDir).sort();
      for (const variant of variantDirs) {
        const variantExe = join(variantsDir, variant, llamaServer);
        if (existsSync(variantExe)) {
          pass(`${llamaServer} found at ${variantExe}`);
          return { ok: true, resolvedRoot: root };
        }
      }
    }
  }

  if (existingRoots.length > 0) {
    fail(
        `llama-server runtime roots found but no executable discovered. Checked: ${existingRoots.join(', ')}`);
  } else {
    fail(
        `No llama-server runtime root found. Checked: ${uniqueCandidates.join(', ')}`);
  }
  return { ok: false, resolvedRoot: null };
}

console.log('\n=== AI Prerequisites Verification ===\n');

// 1. Verify SchemaFields.EMBEDDING_STATUS
console.log('1. SchemaFields.EMBEDDING_STATUS');
try {
  const schemaFile = 'modules/indexing/src/main/java/io/justsearch/indexing/SchemaFields.java';
  if (!existsSync(schemaFile)) {
    fail(`Missing file: ${schemaFile}`);
  } else {
    const content = readText(schemaFile);
    if (content.includes('EMBEDDING_STATUS')) {
      pass('EMBEDDING_STATUS constant exists');
    } else {
      fail('EMBEDDING_STATUS constant missing');
    }
    if (content.includes('EMBEDDING_STATUS_PENDING')) {
      pass('Embedding status values include EMBEDDING_STATUS_PENDING');
    } else {
      fail('Embedding status values missing EMBEDDING_STATUS_PENDING');
    }
  }
} catch (error) {
  fail(`Error while verifying schema fields: ${error.message}`);
}

// 2. Verify DocumentService.fetchBatch
console.log('\n2. DocumentService.fetchBatch()');
try {
  const serviceFile = 'modules/app-api/src/main/java/io/justsearch/app/api/DocumentService.java';
  if (!existsSync(serviceFile)) {
    fail(`Missing file: ${serviceFile}`);
  } else {
    const content = readText(serviceFile);
    if (content.includes('fetchBatch')) {
      pass('fetchBatch method exists in DocumentService');
    } else {
      fail('fetchBatch method missing in DocumentService');
    }
  }
} catch (error) {
  fail(`Error while verifying DocumentService: ${error.message}`);
}

// 3. Verify VramDetector
console.log('\n3. VramDetector');
try {
  const vramFile = 'modules/gpu-bridge/src/main/java/io/justsearch/gpu/VramDetector.java';
  if (!existsSync(vramFile)) {
    fail(`Missing file: ${vramFile}`);
  } else {
    const content = readText(vramFile);
    pass('VramDetector.java exists');
    if (content.includes('nvidia-smi')) {
      pass('VramDetector uses nvidia-smi');
    } else {
      fail('VramDetector missing nvidia-smi usage');
    }
    if (content.includes('meetsVduRequirements')) {
      pass('VramDetector includes meetsVduRequirements()');
    } else {
      fail('VramDetector missing meetsVduRequirements()');
    }
    if (content.includes('getRecommendedLlamaServerFlags')) {
      pass('VramDetector includes getRecommendedLlamaServerFlags()');
    } else {
      fail('VramDetector missing getRecommendedLlamaServerFlags()');
    }
  }
} catch (error) {
  fail(`Error while verifying VramDetector: ${error.message}`);
}

// 4. Verify TempFileManager path and methods
console.log('\n4. TempFileManager');
try {
  const tempFile = 'modules/app-util/src/main/java/io/justsearch/app/util/TempFileManager.java';
  if (!existsSync(tempFile)) {
    fail(`Missing file: ${tempFile}`);
  } else {
    const content = readText(tempFile);
    pass(`TempFileManager exists at ${tempFile}`);
    if (content.includes('createTempFile')) {
      pass('TempFileManager includes createTempFile()');
    } else {
      fail('TempFileManager missing createTempFile()');
    }
    if (content.includes('createTempDirectory')) {
      pass('TempFileManager includes createTempDirectory()');
    } else {
      fail('TempFileManager missing createTempDirectory()');
    }
    if (content.includes('registerShutdownHook')) {
      pass('TempFileManager includes registerShutdownHook()');
    } else {
      fail('TempFileManager missing registerShutdownHook()');
    }
  }
} catch (error) {
  fail(`Error while verifying TempFileManager: ${error.message}`);
}

// 5. Verify InferenceLifecycleManager package and methods
console.log('\n5. InferenceLifecycleManager');
try {
  const inferenceDir = 'modules/app-inference/src/main/java/io/justsearch/app/inference';
  const managerFile = join(inferenceDir, 'InferenceLifecycleManager.java');
  if (!isDirectory(inferenceDir)) {
    fail(`Missing directory: ${inferenceDir}`);
  } else if (!existsSync(managerFile)) {
    fail(`Missing file: ${managerFile}`);
  } else {
    pass(`Inference package exists at ${inferenceDir}`);

    const files = readdirSync(inferenceDir);
    if (files.includes('InferenceLifecycleManager.java')) {
      pass('InferenceLifecycleManager.java exists');
    } else {
      fail('InferenceLifecycleManager.java missing');
    }
    if (files.includes('InferenceConfig.java')) {
      pass('InferenceConfig.java exists');
    } else {
      fail('InferenceConfig.java missing');
    }
    const apiDir = 'modules/app-api/src/main/java/io/justsearch/app/api';
    if (existsSync(join(apiDir, 'ModeTransitionException.java'))) {
      pass('ModeTransitionException.java exists in app-api');
    } else {
      fail(`ModeTransitionException.java missing at ${apiDir}`);
    }

    const modeFile = join(apiDir, 'Mode.java');
    if (existsSync(modeFile) && readText(modeFile).includes('enum Mode')) {
      pass('Mode enum exists in app-api');
    } else {
      fail(`Mode enum missing at ${modeFile}`);
    }

    const content = readText(managerFile);
    if (content.includes('switchToOnlineMode')) {
      pass('InferenceLifecycleManager includes switchToOnlineMode()');
    } else {
      fail('InferenceLifecycleManager missing switchToOnlineMode()');
    }
    if (content.includes('switchToIndexingMode')) {
      pass('InferenceLifecycleManager includes switchToIndexingMode()');
    } else {
      fail('InferenceLifecycleManager missing switchToIndexingMode()');
    }
    if (content.includes('chatCompletion')) {
      pass('InferenceLifecycleManager includes chatCompletion()');
    } else {
      fail('InferenceLifecycleManager missing chatCompletion()');
    }
    if (content.includes('visionCompletion')) {
      pass('InferenceLifecycleManager includes visionCompletion()');
    } else {
      fail('InferenceLifecycleManager missing visionCompletion()');
    }
  }
} catch (error) {
  fail(`Error while verifying InferenceLifecycleManager: ${error.message}`);
}

// 6. Runtime GPU visibility (warning only)
console.log('\n6. Runtime: nvidia-smi detection');
try {
  const result = execSync(
      'nvidia-smi --query-gpu=memory.total,memory.free --format=csv,noheader,nounits',
      { encoding: 'utf8' });
  const [total, free] = result.trim().split(',').map((item) => parseInt(item.trim(), 10));
  pass(`GPU detected: ${total} MB total, ${free} MB free`);

  if (total >= 8192) {
    pass(`Meets 8GB VDU guidance (${(total / 1024).toFixed(1)} GB)`);
  } else {
    warn(`Below 8GB VDU guidance (${(total / 1024).toFixed(1)} GB)`);
  }

  if (total >= 12288) {
    pass('12GB+ detected');
  } else {
    info('Lower VRAM tier detected; KV quantization may be required');
  }
} catch (error) {
  warn(`nvidia-smi unavailable or failed: ${error.message}`);
}

// 7. Core and optional model files
console.log('\n7. Model files');
// Chat/VLM model filenames are read from the registry SSOT
// (modules/configuration/src/main/resources/ai/model-registry.v2.json, package id=chat) so this
// check never drifts when the packaged model changes — cf. tempdoc 579 model-id drift.
// Tempdoc 840: relocated from modules/ui to modules/configuration (the module that owns
// ModelRegistryLoader), so app-services no longer reaches the resource by classpath coincidence.
function readChatModelNames() {
  try {
    const reg = JSON.parse(readText('modules/configuration/src/main/resources/ai/model-registry.v2.json'));
    const chat = (reg.packages || []).find((p) => p.id === 'chat');
    return {
      variant: chat?.variants?.[0]?.filename ?? null,
      mmproj: chat?.supportingFiles?.[0]?.filename ?? null,
    };
  } catch {
    return { variant: null, mmproj: null };
  }
}
const { variant: chatModelFile, mmproj: mmprojFile } = readChatModelNames();

const modelChecks = [
  {
    path: `models/${chatModelFile ?? 'Qwen_Qwen3.5-9B-Q4_K_M.gguf'}`,
    label: 'Chat / VLM model (registry id=chat)',
    required: true,
  },
  {
    // Legacy GGUF embedding fallback — NOT in model-registry.v2.json and not on the
    // search hot path (the live embedding is ONNX gte-multilingual-base). Optional. (579)
    path: 'models/nomic-embed-text-v1.5.Q4_K_M.gguf',
    label: 'Embedding model (legacy GGUF fallback)',
    required: false,
  },
  {
    path: `models/${mmprojFile ?? 'mmproj-F16.gguf'}`,
    label: 'Vision projector model (registry id=chat supportingFile)',
    required: false,
  },
];

for (const check of modelChecks) {
  if (existsSync(check.path)) {
    pass(`${check.label} found at ${check.path}`);
  } else if (check.required) {
    fail(`${check.label} missing at ${check.path}`);
  } else {
    warn(`${check.label} not found at ${check.path} (optional)`);
  }
}

// 8. Optional citation scorer model files
console.log('\n8. Citation scorer model files (optional)');
// Tempdoc 656: the registry targetDir is onnx/citation-scorer (the model is ms-marco-MiniLM-L-6-v2,
// not the old L2-v2 path) — the previous hardcoded path was a standing false-positive.
const citationModelDir = 'models/onnx/citation-scorer';
const citationModel = join(citationModelDir, 'model.onnx');
const citationTokenizer = join(citationModelDir, 'tokenizer.json');
if (existsSync(citationModel) && existsSync(citationTokenizer)) {
  pass(`Citation scorer assets found at ${citationModelDir}`);
} else {
  warn(
      `Citation scorer assets missing at ${citationModelDir} (optional for preflight)`);
}

// 9. Native llama library for embedding path checks
console.log('\n9. Native llama library');
const nativeCandidates = [];
if (process.env.JUSTSEARCH_NATIVE_PATH) {
  nativeCandidates.push(join(process.env.JUSTSEARCH_NATIVE_PATH, llamaLibrary));
}
nativeCandidates.push(llamaLibrary);
nativeCandidates.push(join('native-bin', llamaLibrary));

const foundNative = nativeCandidates.find((candidate) => existsSync(candidate));
if (foundNative) {
  pass(`Native llama library found at ${foundNative}`);
} else {
  fail(`Native llama library not found. Checked: ${nativeCandidates.join(', ')}`);
}

// 10. llama-server runtime discovery (critical)
console.log('\n10. llama-server runtime layout');
verifyLlamaRuntime();

console.log('\n=== Verification Complete ===');
console.log(`Critical failures: ${state.criticalFailures}`);
console.log(`Warnings: ${state.warnings}\n`);

if (state.criticalFailures > 0) {
  process.exitCode = 1;
}
