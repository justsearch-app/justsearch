import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { stripComments } from './strip-comments.mjs';

export const CORE_SURFACE_METADATA_FILE = 'modules/ui-web/src/api/generated/core-surfaces.generated.ts';

/** Resolve only the entries explicitly contributed by CorePlugin, never every Java definition. */
export function expandCoreSurfaceMetadata(pluginSource, generatedSource) {
  const source = stripComments(pluginSource, { withHtml: false });
  if (!source.includes('CORE_SURFACE_METADATA')) return source;
  if (!source.includes('...CORE_SURFACE_METADATA[')) {
    throw new Error('CorePlugin metadata references moved; update the declaration reader');
  }
  const json = generatedSource?.match(/export const CORE_SURFACE_METADATA = ([\s\S]*?) as const;/)?.[1];
  if (!json) throw new Error(`${CORE_SURFACE_METADATA_FILE}: missing/unreadable metadata declaration`);
  const metadata = JSON.parse(json);
  const expanded = source.replace(/\.\.\.CORE_SURFACE_METADATA\[['"]([^'"]+)['"]\]/g, (_, id) => {
    const fields = metadata[id];
    if (!fields || fields.id !== id) throw new Error(`Missing generated surface metadata for ${id}`);
    return Object.entries(fields).map(([key, value]) => `${key}: ${JSON.stringify(value)}`).join(',\n    ');
  });
  if (/\bCORE_SURFACE_METADATA\s*\[/.test(expanded)) {
    throw new Error('Unresolved CorePlugin metadata reference; update the declaration reader');
  }
  // Callers may pass already-expanded declarations through their pure parser again.
  return expanded.replace(/import\s*\{\s*CORE_SURFACE_METADATA\s*\}\s*from\s*['"][^'"]+['"];?/g, '');
}

/** Existing static gates read the effective declarations through the same projection seam. */
export function readCorePluginDeclarations(pluginFile, repoRoot = '.') {
  const source = readFileSync(join(repoRoot, pluginFile), 'utf8');
  const generated = source.includes('CORE_SURFACE_METADATA[')
    ? readFileSync(join(repoRoot, CORE_SURFACE_METADATA_FILE), 'utf8') : undefined;
  return expandCoreSurfaceMetadata(source, generated);
}
