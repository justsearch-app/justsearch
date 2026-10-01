/** Runtime input receipts. File metadata avoids multi-GB model reads per invocation. */
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const stable = v => Array.isArray(v) ? v.map(stable) : v && typeof v === 'object'
  ? Object.fromEntries(Object.keys(v).sort().map(k => [k, stable(v[k])])) : v;
const files = dir => fs.existsSync(dir) ? fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory()
  ? files(path.join(dir, e.name)) : [path.join(dir, e.name)]).sort() : [];
export function corpusManifest(dir) {
  const list = files(dir);
  if (!list.length) throw new Error(`Materialized corpus missing: ${dir}`);
  return list.map(file => [path.relative(dir, file).replaceAll('\\', '/'), digest(fs.readFileSync(file))]);
}
// Projection of configuration.OnnxModelDiscovery and the module-local discoverers.
// Stage E deliberately confines automatic discovery to its verified shared model root.
const encoders = [
  ['embed', 'justsearch.embed.onnx.model_path', ['onnx/gte-multilingual-base']],
  ['splade', 'justsearch.splade.model_path', ['onnx/splade', 'splade/naver-splade-v3']],
  ['ner', 'justsearch.ner.model_path', ['onnx/ner', 'ner/distilbert-multilingual-ner-hrl']],
  ['reranker', 'justsearch.rerank.model_path', ['onnx/reranker', 'reranker/ms-marco-MiniLM-L6-v2']],
  ['citation_scorer', 'justsearch.citation.scorer.model_path', ['onnx/citation-scorer']],
];
const normalize = p => path.resolve(p).replaceAll('\\', '/');
const canonical = p => {
  const value = normalize(fs.realpathSync(p));
  return process.platform === 'win32' ? value.toLowerCase() : value;
};
const valueOf = (config, key) => config.keys.find(k => k.key === key)?.value;
const resolve = (root, p) => path.resolve(root, p);
const chatReferences = manifest => [manifest?.ai?.modelPath,
  ...(manifest?.children ?? []).filter(c => c.kind === 'LLAMA_SERVER').map(c => c.modelPath)].filter(Boolean);
function weights(dir) {
  const file = path.join(dir, 'model_manifest.json');
  const names = fs.existsSync(file) ? Object.entries(JSON.parse(fs.readFileSync(file)))
    .filter(([key, value]) => ['cpu', 'gpu'].includes(key) && typeof value === 'string').map(([, value]) => value)
    : ['model.onnx', 'model_fp16.onnx'];
  return names.map(name => path.resolve(dir, name)).filter(f => fs.existsSync(f) && fs.statSync(f).isFile());
}
function complete(dir, id) {
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return false;
  return weights(dir).length > 0 && fs.existsSync(path.join(dir, 'tokenizer.json'))
    && (id !== 'splade' || fs.existsSync(path.join(dir, 'vocab.txt')) || fs.existsSync(path.join(dir, 'model_manifest.json')));
}
export function modelIdentity(config, manifests = []) {
  const root = valueOf(config ?? { keys: [] }, 'justsearch.models.dir');
  if (!root || !fs.existsSync(root) || !fs.statSync(root).isDirectory())
    throw new Error('Configured identity unavailable: effective config lacks an accessible model store');
  const selections = encoders.map(([id, key, defaults]) => {
    const explicit = valueOf(config, key);
    const selected = explicit?.trim() ? resolve(root, explicit)
      : defaults.map(p => resolve(root, p)).find(p => complete(p, id));
    if (!selected || !fs.existsSync(selected)) throw new Error(`Configured model missing: ${id} (${key}; ${root})`);
    const directory = fs.statSync(selected).isDirectory();
    if (directory && !complete(selected, id)) throw new Error(`Configured model incomplete: ${id} (${selected})`);
    return { id, path: normalize(selected), weights: directory ? weights(selected).map(normalize).sort() : [normalize(selected)] };
  });
  const chat = valueOf(config, 'justsearch.llm.model_path') || valueOf(config, 'justsearch.vlm.model')
    || manifests.flatMap(chatReferences)[0];
  if (!chat) throw new Error('Configured model missing: chat model path in config/manifest');
  const chatPath = resolve(root, chat);
  if (!fs.existsSync(chatPath) || !fs.statSync(chatPath).isFile()) throw new Error(`Configured model missing: chat (${chatPath})`);
  selections.push({ id: 'chat', path: normalize(chatPath), weights: [normalize(chatPath)] });
  // Include every explicitly configured external file, including projector/chunk reranker.
  const configured = config.keys.filter(k => typeof k.value === 'string' && k.value.trim()
    && /\.(?:model_path|model)$/.test(k.key));
  const refs = configured.map(k => resolve(root, k.value));
  const namedKeys = new Set([...encoders.map(([, key]) => key), 'justsearch.llm.model_path', 'justsearch.vlm.model']);
  for (const [i, file] of refs.entries()) {
    if (!fs.existsSync(file)) throw new Error(`Configured model missing: ${file}`);
    if (!namedKeys.has(configured[i].key)) selections.push({ id: configured[i].key, path: normalize(file),
      weights: fs.statSync(file).isDirectory() ? weights(file).map(normalize).sort() : [normalize(file)] });
  }
  const inventory = [...new Set([...files(root), ...selections.flatMap(s => fs.statSync(s.path).isDirectory() ? files(s.path) : [s.path]),
    ...refs.flatMap(p => fs.statSync(p).isDirectory() ? files(p) : [p])].map(normalize))].sort();
  const models = inventory.map(file => {
    const stat = fs.statSync(file);
    return { path: file, size: stat.size, mtimeMs: stat.mtimeMs };
  });
  return { configuredSelections: selections.sort((a, b) => a.id.localeCompare(b.id)),
    method: 'configured-store-superset-size-mtime', root: normalize(root), chatModels: [normalize(chatPath)], models };
}
export function validateRuntimeIdentity(identity, statuses = [], manifests = []) {
  const matches = (id, reported) => {
    const selection = identity.configuredSelections.find(s => s.id === id);
    const reportedPath = resolve(identity.root, reported);
    if (!selection || !fs.existsSync(reportedPath) || ![selection.path, ...selection.weights]
      .some(p => canonical(p) === canonical(reportedPath)))
      throw new Error(`Runtime model identity mismatch: ${id} reported ${reported}, configured ${selection?.path ?? 'no model'}`);
  };
  for (const s of statuses.filter(Boolean)) {
    if (s.active?.modelPath) matches('chat', s.active.modelPath);
    for (const feature of s.onnxFeatures ?? []) if (feature.modelPath) matches(feature.id, feature.modelPath);
  }
  for (const manifest of manifests) for (const reported of chatReferences(manifest)) matches('chat', reported);
}
const sessions = ai => [{ id: 'chat', modelPath: ai?.active?.modelPath ?? null,
  activeVariantId: ai?.active?.activeVariantId ?? null }, ...(ai?.onnxFeatures ?? []).map(f => ({
  id: f.id, modelPath: f.modelPath, modelActive: f.modelActive, status: f.status,
  runtimeIdentityAvailable: Boolean(f.modelPath), executionProvider: f.executionProvider,
  gpuFallback: f.gpuFallback, fallbackReason: f.fallbackReason,
}))];
export function finalizeInputs(context) {
  const { record: r, root } = context;
  const captures = files(context.raw).filter(f => /effective-config\.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(f)));
  if (!captures.length) throw new Error('Measured model identity unavailable: effective-config capture missing');
  const loadFiles = files(context.raw).filter(f => /bulk-load.json$/.test(f));
  const loads = loadFiles.map(f => JSON.parse(fs.readFileSync(f)));
  const sessionReceipts = [...files(context.raw).filter(f => /encoder-sessions.*\.json$/.test(f))
    .map(f => ({ ...JSON.parse(fs.readFileSync(f)), sourceFile: f })),
    ...loads.flatMap((l, i) => Object.entries(l.encoderSessions ?? {}).map(([boundary, s]) => ({ ...s,
      label: `bulk-${boundary}`, sourceFile: loadFiles[i] })))];
  const statuses = sessionReceipts.map(s => s.ai).filter(Boolean);
  const manifests = sessionReceipts.map(s => s.manifest).filter(Boolean);
  if (context.manifest) manifests.push(context.manifest);
  r.executedModels = statuses.flatMap(sessions);
  r.runtimeModelObservations = sessionReceipts.map(s => ({ label: s.label, observedAt: s.observedAt, sourceFile: s.sourceFile,
    sessions: sessions(s.ai), aiError: s.aiError, manifestError: s.manifestError }));
  r.pairIdentityInputs.models = modelIdentity(captures[0], manifests);
  validateRuntimeIdentity(r.pairIdentityInputs.models, statuses, manifests);
  if (sessionReceipts.some(s => s.modelIdentityError)) throw new Error('Configured/runtime model identity capture failed');
  const capturedModels = sessionReceipts.map(s => s.models).filter(Boolean);
  if (capturedModels.some(m => JSON.stringify(m.models) !== JSON.stringify(r.pairIdentityInputs.models.models)))
    throw new Error('Model file size/mtime changed during acquisition');
  if (r.groups.some(g => ['E1', 'E2', 'E4'].includes(g))) {
    const dir = path.join(root, 'tmp/lane-f-e/corpus/scifact');
    r.pairIdentityInputs.scifact = loads[0]?.corpusManifest ?? corpusManifest(dir);
    if (!r.pairIdentityInputs.scifact.length) throw new Error('Materialized SciFact manifest empty');
  }
  r.pairIdentity = digest(JSON.stringify(stable(r.pairIdentityInputs)));
}
export async function captureEncoderSessions(context, label, request = fetch) {
  const result = { label, observedAt: new Date().toISOString() };
  for (const [key, endpoint] of [['ai', '/api/ai/runtime/status'], ['manifest', '/api/runtime/manifest']]) {
    try {
      const response = await request(`http://127.0.0.1:33221${endpoint}`, {
        headers: { Host: '127.0.0.1:33221' }, signal: AbortSignal.timeout(10000) });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      result[key] = await response.json();
    } catch (e) { result[`${key}Error`] = e.message; }
  }
  const file = path.join(context.raw, `encoder-sessions-${label}.json`);
  result.sessions = sessions(result.ai);
  if (context.effectiveConfig) {
    try {
      result.models = modelIdentity(context.effectiveConfig, [context.manifest, result.manifest].filter(Boolean));
      validateRuntimeIdentity(result.models, result.ai ? [result.ai] : [], [result.manifest].filter(Boolean));
    }
    catch (e) { result.modelIdentityError = e.message; }
  }
  fs.writeFileSync(file, JSON.stringify(result, null, 2));
  context.record.encoderSessions ??= {};
  context.record.encoderSessions[label] = result;
  if (result.modelIdentityError || !result.models) throw new Error(`Configured/runtime model identity invalid: ${result.modelIdentityError ?? 'effective config missing'}; see ${file}`);
}
