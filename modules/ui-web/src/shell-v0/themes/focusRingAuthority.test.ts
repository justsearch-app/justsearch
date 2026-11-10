import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, it } from 'vitest';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../..');
const SEARCH_V3 = `${join(SRC, 'shell-v0/views/search-v3')}${sep}`;

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.(?:[jt]sx?|css)$/.test(entry.name) && !/\.(?:test|spec)\./.test(entry.name) ? [path] : [];
  });
}

it('focus outlines and shadows use the focus-ring token, preserving forced-colours system colours', () => {
  const bypasses: string[] = [];
  for (const file of sourceFiles(SRC)) {
    const source = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    // Leaf CSS blocks work for stylesheets and Lit templates, including rules inside media queries.
    for (const rule of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const selector = rule[1] as string;
      if (!/:focus(?:-visible|-within)?\b/.test(selector)) continue;
      for (const declaration of (rule[2] as string).matchAll(/(?:^|;)\s*(outline(?:-color)?|box-shadow)\s*:\s*([^;]+)/g)) {
        const value = (declaration[2] as string).trim();
        // Resets do not paint a ring; transparent outlines are repainted by forced colours only.
        if (/^(?:none|0|2px solid transparent)$/.test(value)) continue;
        if (/var\(\s*--focus-ring-color\s*[,)]/.test(value)) continue;
        // Search owns a window-scoped --ring, pinned by sv3-tokens.test.ts contrast checks.
        if (file.startsWith(SEARCH_V3) && /var\(\s*--ring\s*[,)]/.test(value)) continue;
        if (/(?:^|\s)(?:Highlight|HighlightText|CanvasText)$/.test(value)) continue;
        bypasses.push(`${relative(SRC, file)}: ${selector.trim()} { ${declaration[1]}: ${value} }`);
      }
    }
  }
  expect(bypasses, bypasses.join('\n')).toEqual([]);
});
