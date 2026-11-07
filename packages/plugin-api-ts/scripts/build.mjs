#!/usr/bin/env node
// Prepare both advertised runtime formats and declarations before replacing dist.
// Uses the repository's pinned, locally installed tools; never downloads tools.
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { replaceDirectory } from './replace-directory.mjs';

const pkg = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const staged = mkdtempSync(join(pkg, '.sdk-dist-'));
try {
  const result = spawnSync(process.execPath, [require.resolve('typescript/bin/tsc'), '-p', join(pkg, 'tsconfig.json'), '--emitDeclarationOnly', '--outDir', staged], { cwd: pkg, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Declaration build failed (${result.status})`);
  for (const [format, filename] of [['esm', 'index.mjs'], ['cjs', 'index.js']]) {
    await build({ entryPoints: [join(pkg, 'src/index.ts')], outfile: join(staged, filename), bundle: true, platform: 'node', target: 'node20', format, legalComments: 'none' });
  }
  replaceDirectory(staged, join(pkg, 'dist'));
  console.log('Built ESM, CommonJS and declarations');
} finally {
  rmSync(staged, { recursive: true, force: true });
}
