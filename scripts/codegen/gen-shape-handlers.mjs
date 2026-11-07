#!/usr/bin/env node
/**
 * Slice 491 §9.D Phase E C1 — ConversationShape typed-handler codegen.
 *
 * Generates `modules/ui-web/src/api/generated/shape-handlers/<shape-id>.ts`
 * for each ConversationShape in the catalog. Each output file declares a
 * typed interface with one optional `onXxx` method per event in the shape's
 * eventSchema.
 *
 * Per §9.E A5 finding: `eventSchema` is `List<String>` (names only); payload
 * types are not declared structurally today. V1 codegen emits handlers with
 * `payload: unknown` — typed payloads land via a future eventSchema model
 * extension per Q6 (no proto-contract impact since ConversationShape is off-
 * wire).
 *
 * Per §9.E A5 + Q4: standalone node script, NOT `:wireGenerate`. Reads from
 * a bundled fixture (default) or `/api/registry/shapes` live (`--live` flag).
 *
 * Usage:
 *   node scripts/codegen/gen-shape-handlers.mjs           # bundled fixture
 *   node scripts/codegen/gen-shape-handlers.mjs --live    # fetch /api/registry/shapes
 *   node scripts/codegen/gen-shape-handlers.mjs --check   # exit non-zero on diff
 *
 * The CI gate (`scripts/ci/regen-all.mjs --check`) wraps `--check`.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const OUTPUT_DIR = join(
  REPO_ROOT,
  'modules',
  'ui-web',
  'src',
  'api',
  'generated',
  'shape-handlers',
);

/**
 * Bundled fixture — the 6 shapes shipped in CoreConversationShapeCatalog as
 * of 2026-05-14. Single source of truth for handler-name codegen until a
 * shape's eventSchema or projection changes (then update this fixture +
 * re-run codegen + check in the diff).
 *
 * When `--live` is passed, this fixture is ignored; the script fetches
 * `/api/registry/shapes` and uses the live entries instead.
 */
// Verified 2026-05-14 against the live /api/registry/shapes catalog from this
// worktree's backend (corrected from initial-draft IDs that didn't match the
// shipped Java constants).
// Tempdoc 564 Phase 0: BUNDLED_SHAPES is the GENERATED projection of the catalog
// (scripts/codegen/shapes.fixture.json, captured by ConversationShapeFixtureGenTest from
// CoreConversationShapeCatalog) — not a hand array. It auto-includes every shape the catalog
// declares (incl. tempdoc 560's core.workflow-run) with typed event descriptors.
const BUNDLED_SHAPES = JSON.parse(readFileSync(join(REPO_ROOT, "scripts", "codegen", "shapes.fixture.json"), "utf8"));

function parseArgs(argv) {
  const args = { live: false, check: false, port: null };
  for (const a of argv.slice(2)) {
    if (a === '--live') args.live = true;
    else if (a === '--check') args.check = true;
    else if (a.startsWith('--port=')) args.port = Number(a.slice(7));
  }
  return args;
}

async function fetchLiveCatalog(port) {
  const url = `http://127.0.0.1:${port}/api/registry/shapes`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Live fetch failed: ${res.status} ${res.statusText} (${url})`);
  }
  const body = await res.json();
  if (!body || !Array.isArray(body.entries)) {
    throw new Error(`Live response missing 'entries' array: ${JSON.stringify(body).slice(0, 200)}`);
  }
  return body.entries;
}

function eventNameToHandlerMethod(eventName) {
  // chunk → onChunk
  // tool_call_proposed → onToolCallProposed
  // navigate.url_extracted → onNavigateUrlExtracted
  return (
    'on' +
    eventName
      .split(/[._]/)
      .filter((s) => s.length > 0)
      .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
      .join('')
  );
}

function shapeIdToTypeName(shapeId) {
  // core.navigate-chat → CoreNavigateChatHandlers
  return (
    shapeId
      .split(/[.-]/)
      .filter((s) => s.length > 0)
      .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
      .join('') + 'Handlers'
  );
}

function shapeIdToFileName(shapeId) {
  // core.navigate-chat → core-navigate-chat
  return shapeId.replace(/\./g, '-');
}

// tempdoc 564: map a typed EventField (from shapes.fixture.json descriptors) to a TS type.
function tsTypeForField(field) {
  switch (field.type) {
    case 'STRING':
      return 'string';
    case 'NUMBER':
      return 'number';
    case 'BOOLEAN':
      return 'boolean';
    case 'ENUM':
      return field.enumValues && field.enumValues.length
        ? field.enumValues.map((v) => "'" + v + "'").join(' | ')
        : 'string';
    case 'OBJECT':
      return field.objectType || 'Record<string, unknown>';
    case 'ARRAY':
      return (
        (field.elementType === 'OBJECT'
          ? field.objectType || 'Record<string, unknown>'
          : tsTypeForField({ type: field.elementType, enumValues: [] })) + '[]'
      );
    default:
      return 'unknown';
  }
}

// tool_call_pending → ToolCallPending (the PascalCase part after 'on').
function pascalEvent(eventName) {
  return eventNameToHandlerMethod(eventName).slice(2);
}

function renderHandlers(shape) {
  const typeName = shapeIdToTypeName(shape.id);
  const baseName = typeName.replace(/Handlers$/, '');
  const sharedRefs = new Set();
  const interfaces = [];
  const methods = [];
  for (const descriptor of shape.eventSchema) {
    const method = eventNameToHandlerMethod(descriptor.name);
    const fields = descriptor.fields || [];
    if (fields.length === 0) {
      // No declared payload fields yet — keep the permissive signature.
      methods.push('  /** SSE event: ' + descriptor.name + ' */');
      methods.push('  ' + method + '?(payload: unknown): void;');
      continue;
    }
    const payloadType = baseName + pascalEvent(descriptor.name) + 'Payload';
    const fieldLines = fields.map((f) => {
      if (f.objectType) sharedRefs.add(f.objectType);
      return '  ' + f.name + (f.optional ? '?' : '') + ': ' + tsTypeForField(f) + ';';
    });
    interfaces.push(
      '/** Payload of the `' + descriptor.name + '` event. */\n' +
        'export interface ' + payloadType + ' {\n' + fieldLines.join('\n') + '\n}',
    );
    methods.push('  /** SSE event: ' + descriptor.name + ' */');
    methods.push('  ' + method + '?(payload: ' + payloadType + '): void;');
  }
  const importLine =
    sharedRefs.size > 0
      ? ["import type { " + [...sharedRefs].sort().join(', ') + " } from './shared.js';", '']
      : [];
  const out = [
    '/**',
    ' * AUTO-GENERATED by scripts/codegen/gen-shape-handlers.mjs (tempdoc 564: typed payloads).',
    ' * Source shape: ' + shape.id,
    ' * Events: ' + shape.eventSchema.length,
    ' *',
    ' * Do NOT edit by hand. Re-generate with:',
    ' *   node scripts/codegen/gen-shape-handlers.mjs',
    ' */',
    '',
    ...importLine,
    ...interfaces.flatMap((i) => [i, '']),
    '/** Typed SSE handler interface for shape ' + shape.id + '. */',
    'export interface ' + typeName + ' {',
    ...methods,
    '}',
    '',
  ];
  return out.join('\n');
}

function ensureOutputDir() {
  if (!existsSync(OUTPUT_DIR)) {
    mkdirSync(OUTPUT_DIR, { recursive: true });
  }
}

function writeIndexFile(shapes) {
  const lines = [
    '/**',
    ' * AUTO-GENERATED by scripts/codegen/gen-shape-handlers.mjs',
    ' * Re-exports every shape\'s typed handler interface for convenient import.',
    ' */',
    '',
  ];
  for (const shape of shapes) {
    const fname = shapeIdToFileName(shape.id);
    lines.push("export type { " + shapeIdToTypeName(shape.id) + " } from './" + fname + ".js';");
  }
  lines.push('');
  return lines.join('\n');
}

async function main() {
  const args = parseArgs(process.argv);
  let shapes;
  if (args.live) {
    const port = args.port ?? 33221; // dev default
    console.log(`[gen-shape-handlers] fetching live catalog from port ${port}…`);
    shapes = await fetchLiveCatalog(port);
  } else {
    console.log(`[gen-shape-handlers] using bundled fixture (${BUNDLED_SHAPES.length} shapes)`);
    shapes = BUNDLED_SHAPES;
  }

  ensureOutputDir();

  // In check mode, snapshot the existing files first so we can diff after.
  const checkMode = args.check;
  let before = {};
  if (checkMode) {
    for (const shape of shapes) {
      const fname = shapeIdToFileName(shape.id) + '.ts';
      const p = join(OUTPUT_DIR, fname);
      if (existsSync(p)) before[fname] = readFileSync(p, 'utf8');
    }
    const indexPath = join(OUTPUT_DIR, 'index.ts');
    if (existsSync(indexPath)) before['index.ts'] = readFileSync(indexPath, 'utf8');
  }

  const written = [];
  for (const shape of shapes) {
    if (!Array.isArray(shape.eventSchema)) {
      console.error(
        `[gen-shape-handlers] WARN: shape ${shape.id} has no eventSchema array; skipped`,
      );
      continue;
    }
    const fname = shapeIdToFileName(shape.id) + '.ts';
    const p = join(OUTPUT_DIR, fname);
    const content = renderHandlers(shape);
    writeFileSync(p, content, 'utf8');
    written.push(p);
  }

  const indexContent = writeIndexFile(shapes);
  const indexPath = join(OUTPUT_DIR, 'index.ts');
  writeFileSync(indexPath, indexContent, 'utf8');
  written.push(indexPath);

  if (checkMode) {
    const after = {};
    for (const shape of shapes) {
      const fname = shapeIdToFileName(shape.id) + '.ts';
      const p = join(OUTPUT_DIR, fname);
      after[fname] = readFileSync(p, 'utf8');
    }
    after['index.ts'] = readFileSync(indexPath, 'utf8');
    const drift = [];
    for (const k of Object.keys(after)) {
      if (before[k] !== after[k]) drift.push(k);
    }
    for (const k of Object.keys(before)) {
      if (!after[k]) drift.push(k + ' (deleted)');
    }
    if (drift.length > 0) {
      console.error(
        '[gen-shape-handlers] CHECK FAILED: codegen output drifted from committed files:',
      );
      for (const d of drift) console.error('  - ' + d);
      console.error('Re-run without --check, inspect the diff, commit the regen.');
      process.exit(1);
    }
    console.log('[gen-shape-handlers] check passed — output matches committed files');
    return;
  }

  console.log(
    `[gen-shape-handlers] wrote ${written.length} files:\n  ${written
      .map((p) => relative(REPO_ROOT, p))
      .join('\n  ')}`,
  );
}

// Slice 491 F7 — main-guard so importing this module (e.g., from
// check-shape-view-coverage.mjs) doesn't trigger the top-level codegen
// side-effect. Without the guard, importers wrote handler files just by
// loading BUNDLED_SHAPES.
const isMain =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  main().catch((err) => {
    console.error('[gen-shape-handlers] failed:', err);
    process.exit(1);
  });
}

// Slice 491 F7 — export the bundled fixture so other CI scripts can consume it
// via a clean import (no source-parsing fallback needed).
export { BUNDLED_SHAPES };
