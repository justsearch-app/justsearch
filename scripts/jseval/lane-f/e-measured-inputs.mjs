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
export function modelIdentity(config, statuses = []) {
  const root = config?.keys?.find(k => k.key === 'justsearch.models.dir')?.value;
  if (!root || !fs.existsSync(root)) throw new Error('Captured effective config lacks an accessible model store');
  const configured = config.keys.filter(k => typeof k.value === 'string' && k.value.trim()
    && (/\.model_path$/.test(k.key) || /\.model$/.test(k.key) && path.isAbsolute(k.value))).map(k => k.value);
  const refs = [...configured, ...statuses.flatMap(s => [s.active?.modelPath, ...(s.onnxFeatures ?? []).map(f => f.modelPath)].filter(Boolean))];
  const inventory = [...new Set([...files(root), ...refs.flatMap(p => fs.existsSync(p) && fs.statSync(p).isDirectory() ? files(p) : [p])].map(p => path.resolve(p)))].sort();
  const models = inventory.map(file => {
    const stat = fs.statSync(file);
    return { path: file.replaceAll('\\', '/'), size: stat.size, mtimeMs: stat.mtimeMs };
  });
  if (!models.length) throw new Error('Captured model store is empty');
  const chatModels = [...new Set([config.keys.find(k => k.key === 'justsearch.llm.model_path')?.value,
    ...statuses.map(s => s.active?.modelPath)].filter(Boolean))].map(p => p.replaceAll('\\', '/')).sort();
  const configuredSelections = [...new Set(config.keys.filter(k => /(?:\.model(?:_path)?|\.variantId|\.sparse_model)$/.test(k.key)).map(k => JSON.stringify([k.key, k.value])))].sort().map(v => JSON.parse(v));
  const executedSelections = [...new Set(statuses.flatMap(s => [
    ...(s.active?.modelPath ? [['chat', s.active.modelPath.replaceAll('\\', '/'), s.active.activeVariantId ?? null]] : []),
    ...(s.onnxFeatures ?? []).filter(f => f.modelPath).map(f => [f.id, f.modelPath.replaceAll('\\', '/')]),
  ]).map(v => JSON.stringify(v)))].sort().map(v => JSON.parse(v));
  return { configuredSelections, executedSelections, method: 'configured-store-superset-size-mtime', root: root.replaceAll('\\', '/'), chatModels, models };
}
export function establishExecutedModels(config, statuses, requireChat, allowDormant = false) {
  if (!statuses.length || statuses.some(s => !s || !Array.isArray(s.onnxFeatures)))
    throw new Error('Executed model identity unavailable: required runtime AI status receipt missing or malformed');
  if (requireChat && !statuses.some(s => typeof s.active?.modelPath === 'string' && s.active.modelPath))
    throw new Error('Executed chat model identity unavailable: runtime model path missing');
  const identity = modelIdentity(config, statuses);
  if (!identity.executedSelections.length && !(allowDormant && !requireChat && statuses.every(s =>
    s.active?.modelPath == null && s.onnxFeatures.every(f => f.id && f.modelActive === false && f.status !== 'unknown'))))
    throw new Error('Executed model identity unavailable: no runtime model IDs/paths');
  for (const s of statuses) for (const feature of s.onnxFeatures) {
    if (!feature.id || feature.modelActive === true && !feature.modelPath || feature.status === 'unknown')
      throw new Error(`Executed encoder identity unavailable: ${feature.id ?? 'missing ID'} has unknown model identity`);
    if (feature.modelPath && (!feature.id || !identity.models.some(m => m.path === path.resolve(feature.modelPath).replaceAll('\\', '/')
      || m.path.startsWith(path.resolve(feature.modelPath).replaceAll('\\', '/') + '/'))))
      throw new Error(`Executed encoder identity unavailable: ${feature.id ?? 'missing ID'}`);
  }
  return identity;
}
export function finalizeInputs(context) {
  const { record: r, root } = context;
  const captures = files(context.raw).filter(f => /effective-config\.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(f)));
  if (!captures.length) throw new Error('Measured model identity unavailable: effective-config capture missing');
  const loads = files(context.raw).filter(f => /bulk-load.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(f)));
  const statuses = [...files(context.raw).filter(f => /encoder-sessions.*\.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(f)).ai),
    ...loads.flatMap(l => Object.values(l.encoderSessions ?? {}).map(s => s.ai))].filter(Boolean);
  const sessionReceipts = [...files(context.raw).filter(f => /encoder-sessions.*\.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(f))),
    ...loads.flatMap(l => Object.values(l.encoderSessions ?? {}))];
  if (sessionReceipts.some(s => !s.ai || s.modelIdentityError)) throw new Error('Required runtime model identity capture failed');
  r.executedModels = statuses.flatMap(s => [{ id: 'chat', modelPath: s?.active?.modelPath }, ...(s?.onnxFeatures ?? []).map(f => ({ id: f.id, modelPath: f.modelPath }))]);
  r.pairIdentityInputs.models = establishExecutedModels(captures[0], statuses, !r.groups.includes('E1'));
  const capturedModels = files(context.raw).filter(f => /encoder-sessions.*\.json$/.test(f)).map(f => JSON.parse(fs.readFileSync(f)).models).filter(Boolean);
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
  if (context.effectiveConfig) {
    try { result.models = establishExecutedModels(context.effectiveConfig, result.ai ? [result.ai] : [], !context.record.groups.includes('E1'), true); }
    catch (e) { result.modelIdentityError = e.message; }
  }
  fs.writeFileSync(file, JSON.stringify(result, null, 2));
  context.record.encoderSessions ??= {};
  context.record.encoderSessions[label] = result;
  if (!result.ai || result.modelIdentityError || !result.models) throw new Error(`Required model identity unavailable before measurement: ${result.aiError ?? result.modelIdentityError ?? 'effective config missing'}; see ${file}`);
}
