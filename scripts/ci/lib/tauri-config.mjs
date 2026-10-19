import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Tauri platform overlays use JSON Merge Patch: objects merge, arrays replace, null removes.
function merge(base, patch) {
  if (patch === null || typeof patch !== 'object' || Array.isArray(patch)) return patch;
  const result = { ...(base && typeof base === 'object' && !Array.isArray(base) ? base : {}) };
  for (const [key, value] of Object.entries(patch)) {
    if (value === null) delete result[key];
    else Object.defineProperty(result, key, {
      value: merge(Object.hasOwn(result, key) ? result[key] : undefined, value),
      enumerable: true, configurable: true, writable: true,
    });
  }
  return result;
}

export function readTauriConfig(root, platform, read = file => readFileSync(resolve(root, file), 'utf8')) {
  const decode = file => {
    const text = read(file);
    try { return JSON.parse(text); }
    catch (error) { throw new Error(`${file} is not valid JSON: ${error.message}`); }
  };
  const base = decode('modules/shell/src-tauri/tauri.conf.json');
  let overlay;
  try { overlay = decode(`modules/shell/src-tauri/tauri.${platform}.conf.json`); }
  catch (error) {
    if (error.code === 'ENOENT') return base;
    throw error;
  }
  return merge(base, overlay);
}
