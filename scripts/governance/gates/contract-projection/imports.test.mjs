/**
 * Unit tests for the contract-projection import detector (tempdoc 884 review S5).
 *
 * BOTH directions are the point. A false POSITIVE (a doc comment counted as an import) is what
 * put a file that imports nothing into governance/contract-surfaces.v1.json. A false NEGATIVE
 * (a real import the parser misses) silently disables the gate's actual job — catching an
 * UNdeclared consumer — so every import form the codebase uses gets a positive case here.
 *
 * Run with: `node scripts/governance/gates/contract-projection/imports.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { importSpecifiers, importsGeneratedModule, importsGeneratedRecord,
  matchesGeneratedModule } from './imports.mjs';

let passed = 0;
const failures = [];

function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const BASE = 'agent-sessions-response';

// ------------------------------------------------------ MUST match: real import forms

run('import type { X } from a .js specifier resolving to the .ts module', () => {
  const src = "import type { AgentSessionsResponse } from '../../api/generated/schema-types/agent-sessions-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('plain value import with NO extension (the api/schemas.ts:18 shape)', () => {
  const src = "import { agentSessionsResponseSchema } from './generated/schema-types/agent-sessions-response';\n";
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('double-quoted specifier', () => {
  const src = 'import { X } from "./generated/schema-types/agent-sessions-response.js";\n';
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('multi-line import block (the api/types/registry.ts:44-48 shape)', () => {
  const src = [
    'import type {',
    '  ResourceWire,',
    '  ResourceRefWire,',
    "} from '../generated/schema-types/agent-sessions-response';",
    '',
  ].join('\n');
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('export … from re-export (the api/generated/index.ts:14 shape)', () => {
  const src = "export type { SearchTrace } from './schema-types/agent-sessions-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('export * as ns from re-export', () => {
  const src = "export * as wire from './schema-types/agent-sessions-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('side-effect import', () => {
  const src = "import '../generated/schema-types/agent-sessions-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('dynamic import()', () => {
  const src = "const m = await import('../generated/schema-types/agent-sessions-response.js');\n";
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('a real import AFTER a doc comment that also names the module still matches', () => {
  // The false-negative trap: comment-stripping must not eat the import that follows it.
  const src = [
    '/**',
    ' * Shaped like `generated/schema-types/agent-sessions-response.ts`.',
    ' */',
    "import { agentSessionsResponseSchema } from './generated/schema-types/agent-sessions-response.js';",
    '',
  ].join('\n');
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('a URL string literal on an earlier line does not swallow a later import', () => {
  const src = [
    "const docs = 'https://example.invalid/schema-types/notes';",
    "import { X } from './generated/schema-types/agent-sessions-response.js';",
    '',
  ].join('\n');
  assert.equal(importsGeneratedModule(src, BASE), true);
});

run('a regex literal containing quotes does not swallow a later import', () => {
  const src = [
    "const q = /['\"]/g;",
    "import { X } from './generated/schema-types/agent-sessions-response.js';",
    '',
  ].join('\n');
  assert.equal(importsGeneratedModule(src, BASE), true);
});

// --------------------------------------------------- MUST NOT match: mentions, not imports

run('a JSDoc naming the generated path is NOT a consumer (the AgentSessionController:217 case)', () => {
  const src = [
    '/**',
    ' * Rows arrive shaped like `AgentSessionSummary` (generated wire type',
    ' * `generated/schema-types/agent-sessions-response.ts`): an ISO-8601 `startedAt` string.',
    ' */',
    'export class C {}',
    '',
  ].join('\n');
  assert.equal(importsGeneratedModule(src, BASE), false);
});

run('a line comment naming the path is NOT a consumer (the api/schemas.ts:22 case)', () => {
  const src = "// the projection (`./generated/schema-types/agent-sessions-response`) is parsed elsewhere.\n";
  assert.equal(importsGeneratedModule(src, BASE), false);
});

run('a commented-out import is NOT a consumer', () => {
  const src = "// import { X } from './generated/schema-types/agent-sessions-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), false);
});

run('a plain string constant holding the path is NOT an import', () => {
  const src = "const p = './generated/schema-types/agent-sessions-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), false);
});

run('importing a DIFFERENT generated module is not this one', () => {
  const src = "import { X } from '../generated/schema-types/agent-history-response.js';\n";
  assert.equal(importsGeneratedModule(src, BASE), false);
});

// ----------------------------------------------------------- specifier matching precision

run('prefix collision: schema-types/resource does not match schema-types/resource-usage', () => {
  assert.equal(matchesGeneratedModule('../generated/schema-types/resource-usage.js', 'resource'), false);
  assert.equal(matchesGeneratedModule('../generated/schema-types/resource.js', 'resource'), true);
});

run('suffix collision: schema-types/status-response does not match ai-runtime-status-response', () => {
  assert.equal(matchesGeneratedModule('../generated/schema-types/ai-runtime-status-response.js', 'status-response'), false);
  assert.equal(matchesGeneratedModule('./schema-types/status-response.js', 'status-response'), true);
});

run('the schema-types segment must be a path segment, not a name fragment', () => {
  assert.equal(matchesGeneratedModule('../generated/my-schema-types/surface.js', 'surface'), false);
});

run('the barrel `schema-types/index.js` is not a match for any record', () => {
  assert.equal(matchesGeneratedModule('./schema-types/index.js', 'status-response'), false);
});

run('named generated barrel import proves this record and detects an undeclared consumer', () => {
  const barrel = "export type { OperationOutcomeView } from './operation-outcome-view.js';\n"
    + "export { operationOutcomeViewSchema } from './operation-outcome-view.js';\n";
  const consumer = "import { operationOutcomeViewSchema, type OperationOutcomeView }"
    + " from '../../api/generated/schema-types/index.js';\n";
  assert.equal(importsGeneratedRecord(consumer, 'operation-outcome-view', barrel), true);
  assert.equal(importsGeneratedRecord(consumer, 'other-response', barrel), false);
  assert.equal(importsGeneratedRecord("// " + consumer, 'operation-outcome-view', barrel), false);
  assert.equal(importsGeneratedRecord(
    "import { unrelated } from '../../api/generated/schema-types/index.js';",
    'operation-outcome-view', barrel), false);
  assert.equal(importsGeneratedRecord(consumer, 'operation-outcome-view', ''), false);
});

// ------------------------------------------------------------------- specifier collection

run('importSpecifiers returns every distinct specifier and no comment text', () => {
  const src = [
    "import { z } from 'zod';",
    "// import { fake } from './nope.js';",
    "import type { A } from './generated/schema-types/surface.js';",
    "export { b } from './b.js';",
    'export const c = 1;',
    '',
  ].join('\n');
  assert.deepEqual(importSpecifiers(src).sort(), ['./b.js', './generated/schema-types/surface.js', 'zod']);
});

// ------------------------------------------------------------- against the shipped sources

run('the shipped declared consumers of AiInstallStatus really import it', () => {
  // These three were registered by PR 2 from the old substring matcher; they are genuine.
  for (const rel of [
    'modules/ui-web/src/shell-v0/state/installComponents.ts',
    'modules/ui-web/src/shell-v0/substrates/ai/aiInstallBridge.ts',
    'modules/ui-web/src/shell-v0/substrates/tasks/aiInstallTasksBridge.ts',
  ]) {
    const src = fs.readFileSync(path.resolve(rel), 'utf8');
    assert.equal(importsGeneratedModule(src, 'ai-install-status'), true, `${rel} must parse as a real import`);
  }
});

run('AgentSessionController.ts mentions the module only in prose — not a consumer', () => {
  const rel = 'modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts';
  const src = fs.readFileSync(path.resolve(rel), 'utf8');
  assert.ok(src.includes('schema-types/agent-sessions-response'), 'the prose mention must still be there');
  assert.equal(importsGeneratedModule(src, 'agent-sessions-response'), false);
});

if (failures.length > 0) {
  console.error(`contract-projection imports.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`contract-projection imports.test: all ${passed} checks passed`);
