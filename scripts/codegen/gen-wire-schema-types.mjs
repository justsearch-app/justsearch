#!/usr/bin/env node
/**
 * Tempdoc 564 — JSON-Schema → {TS type, Zod schema} codegen.
 *
 * The de-risked 564 design makes the victools-emitted JSON Schema
 * (`SSOT/schemas/<name>.v1.json`) the single canonical projection of a wire
 * record. This script generates BOTH the TypeScript type AND a runtime Zod
 * schema FROM that one source, into
 * `modules/ui-web/src/api/generated/schema-types/<name>.ts`.
 *
 * This is the tier the parallel typescript-generator path (`wire-types.ts`)
 * cannot provide: a runtime validator (Zod) generated from the same source as
 * the type, so the FE validates the wire against the contract instead of
 * fail-open `.loose()` hand-Zod that drifts.
 *
 * Supported JSON-Schema features (the bounded set victools emits for wire
 * records): `type` (scalar or `[T,"null"]` nullable), `properties` + optional
 * `required`, `items`, `additionalProperties` (typed maps), `enum`, `$ref` to
 * `#/$defs/<Name>`. Unsupported keywords throw — extend deliberately, never
 * silently widen to `unknown`.
 *
 * Usage:
 *   node scripts/codegen/gen-wire-schema-types.mjs           # write
 *   node scripts/codegen/gen-wire-schema-types.mjs --check   # exit non-zero on drift
 *
 * The CI gate `scripts/ci/regen-all.mjs --check` wraps `--check`.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const OUTPUT_DIR = join(REPO_ROOT, 'modules', 'ui-web', 'src', 'api', 'generated', 'schema-types');

/**
 * The wire records projected to {TS, Zod}. Phase 1 pilots SEARCH; Phase 2 adds
 * the rest of the surfaces here (each entry browser-gated when it lands).
 */
const TARGETS = [
  {
    schema: 'SSOT/schemas/knowledge-search-response.v1.json',
    outFile: 'knowledge-search-response.ts',
    rootName: 'KnowledgeSearchResponse',
  },
  {
    // The /api/status surface — the second hard case: @JsonUnwrapped flattening,
    // nullable-ref defs, and an anyOf nullable enum (LifecycleState).
    schema: 'modules/app-api/src/main/resources/schemas/status-response.schema.json',
    outFile: 'status-response.ts',
    rootName: 'StatusResponse',
  },
  // Tempdoc 564 Phase B (4b breadth) — the FE drift surfaces (inference, packs, browse).
  {
    schema: 'SSOT/schemas/ai-runtime-status-response.v1.json',
    outFile: 'ai-runtime-status-response.ts',
    rootName: 'AiRuntimeStatusResponse',
  },
  // Tempdoc 663 §L/Stage 4 — /api/inference/status, moved off a hand-built Map onto a typed record.
  {
    schema: 'SSOT/schemas/inference-status-response.v1.json',
    outFile: 'inference-status-response.ts',
    rootName: 'InferenceStatusResponse',
  },
  {
    schema: 'SSOT/schemas/effective-policy.v1.json',
    outFile: 'effective-policy.ts',
    rootName: 'EffectivePolicy',
  },
  {
    schema: 'SSOT/schemas/ai-pack-import-status.v1.json',
    outFile: 'ai-pack-import-status.ts',
    rootName: 'AiPackImportStatus',
  },
  // Tempdoc 840 Phase 4 — GET /api/ai/install/status. The wire shape had THREE hand-maintained
  // authorities (the Java DTO plus two independent TS interfaces, one still modelling the retired
  // v1 `assets[]`), and the staged install widened the drift with every field it added. Generating
  // the TS from the same schema the Java DTO emits leaves exactly one authority, with
  // regen-all --check as the guard.
  {
    schema: 'SSOT/schemas/ai-install-status.v1.json',
    outFile: 'ai-install-status.ts',
    rootName: 'AiInstallStatus',
  },
  {
    schema: 'SSOT/schemas/folder-browse-response.v1.json',
    outFile: 'folder-browse-response.ts',
    rootName: 'FolderBrowseResponse',
  },
  {
    schema: 'SSOT/schemas/folder-files-response.v1.json',
    outFile: 'folder-files-response.ts',
    rootName: 'FolderFilesResponse',
  },
  {
    // The indexed-roots library surface (/api/indexing-roots/substrate → {items: IndexedRootView[]}).
    schema: 'SSOT/schemas/indexed-root.v1.json',
    outFile: 'indexed-root-view.ts',
    rootName: 'IndexedRootView',
  },
  // Tempdoc 564 Phase 3: the search-trace types — the FE's last proto consumers move to Zod.
  { schema: 'SSOT/schemas/search-trace.v1.json', outFile: 'search-trace.ts', rootName: 'SearchTrace' },
  { schema: 'SSOT/schemas/trace-stage.v1.json', outFile: 'trace-stage.ts', rootName: 'TraceStage' },
  { schema: 'SSOT/schemas/hit-stage.v1.json', outFile: 'hit-stage.ts', rootName: 'HitStage' },
  // Tempdoc 564 Phase 1 (health): the HealthEvent body is the discriminated `anyOf`
  // (lifecycle/condition/threshold) with bare-object `attributes`/`magnitudes` — the exact
  // shape proto3 cannot type without `google.protobuf.Value`. JSON Schema models it faithfully.
  { schema: 'SSOT/schemas/health-event.v1.json', outFile: 'health-event.ts', rootName: 'HealthEvent' },
  // Tempdoc 564 Phase 3: the agent sessions/history surface — record-backed (projected at the
  // AgentController boundary), retiring the FE's fail-open `.loose()` hand-Zod.
  {
    schema: 'SSOT/schemas/agent-sessions-response.v1.json',
    outFile: 'agent-sessions-response.ts',
    rootName: 'AgentSessionsResponse',
  },
  {
    schema: 'SSOT/schemas/agent-history-response.v1.json',
    outFile: 'agent-history-response.ts',
    rootName: 'AgentHistoryResponse',
  },
  // Tempdoc 834 §5.1 (S4): the live-run enumeration — the FE's run-discovery authority, replacing
  // the localStorage pointer. Typed end-to-end for the same reason the agent surface above is: this
  // is the endpoint that hands out the runIds every other run read is keyed by.
  {
    schema: 'SSOT/schemas/live-runs-response.v1.json',
    outFile: 'live-runs-response.ts',
    rootName: 'LiveRunsResponse',
  },
  // Tempdoc 564 Phase 4: the last barrel consumer of the parallel `wire-types.ts` path. The
  // timeseries-snapshot schema is a clean, precise, hand-curated artifact (required fields), so the
  // FE type+Zod generate from it; once the barrel re-points here, wire-types.ts has no consumers and
  // is retired. (The schema is not yet record-generated — see observations: unify via shared config.)
  {
    schema: 'SSOT/schemas/timeseries-snapshot.v1.json',
    outFile: 'timeseries-snapshot.ts',
    rootName: 'TimeseriesSnapshot',
  },
  // Tempdoc 564 Phase 5: the failed-jobs surface (Health view) — record-backed, retiring the FE's
  // unchecked raw cast for a generated parse-boundary Zod.
  {
    schema: 'SSOT/schemas/failed-jobs-response.v1.json',
    outFile: 'failed-jobs-response.ts',
    rootName: 'FailedJobsResponse',
  },
  // Tempdoc 911 (885 UL.9): the SUBSTRATE-shaped failed-jobs surfaces — /api/indexing-jobs/failed
  // and the folder-scoped .../by-prefix the FailedJobsDrawer reads. Both hand-built a
  // Map<String,Object> that was almost an IndexingJobView (scanId dropped), so nothing described
  // the wire and the drawer read `state` — the RETRY_EXHAUSTED discriminator — off untyped JSON.
  // PreciseWire on both records, so this projection is required + non-null end to end.
  {
    schema: 'SSOT/schemas/failed-indexing-jobs-response.v1.json',
    outFile: 'failed-indexing-jobs-response.ts',
    rootName: 'FailedIndexingJobsResponse',
  },
  // Tempdoc 560 §4c (Phase A): the registry "declaration" shape as a generated single-authority
  // projection, retiring the hand-mirrored api/types/registry.ts. The `…Wire` rootName frees the
  // FE-facing names (Resource/Presentation/Provenance) for the tightened derived aliases in the
  // re-export barrel (the generated Zod stays the runtime authority). Resource's wire ==
  // resource.v1.json exactly (18 keys, verified live); Operation is Phase B (its wire is the
  // divergent UIOperationEmitter projection — §13.2).
  { schema: 'SSOT/schemas/resource.v1.json', outFile: 'resource.ts', rootName: 'ResourceWire' },
  { schema: 'SSOT/schemas/presentation.v1.json', outFile: 'presentation.ts', rootName: 'PresentationWire' },
  { schema: 'SSOT/schemas/provenance.v1.json', outFile: 'provenance.ts', rootName: 'ProvenanceWire' },
  // Phase B: the Operation wire is the UIOperationView record (the emitter builds + serializes it),
  // so this schema == the live /api/registry/operations entry shape (was the divergent emitter, §13.2).
  { schema: 'SSOT/schemas/operation-wire.v1.json', outFile: 'operation.ts', rootName: 'OperationWire' },
  // Recorded migration gaps use the same outcome record served by operation-history.
  { schema: 'SSOT/schemas/operation-outcome-view.v1.json', outFile: 'operation-outcome-view.ts', rootName: 'OperationOutcomeView' },
  // DiagnosticChannel slice (tempdoc 560 §4c): the Logs surface's registry primitive — the
  // UIDiagnosticChannelView record's projection, retiring the hand-mirrored types/diagnostic.ts.
  { schema: 'SSOT/schemas/diagnostic-channel.v1.json', outFile: 'diagnostic-channel.ts', rootName: 'DiagnosticChannelWire' },
  // Tempdoc 683 — the /api/settings/v2 surface: retires the fail-open `.loose()` hand-Zod
  // (SettingsV2Schema in api/schemas.ts) for the generated record→JSON-Schema→Zod projection.
  {
    schema: 'SSOT/schemas/settings-v2.v1.json',
    outFile: 'settings-v2.ts',
    rootName: 'SettingsV2',
  },
  // Tempdoc 884 (ADR-0038 amendment): the Surface Manifest wire, projected from the Surface
  // record. Retires the hand-written mirror at api/types/surface.ts, which was a declared
  // exception on ADR-0038's premise probe. `SurfaceWire` (not `Surface`) because the FE keeps a
  // composed `Surface` carrying two fields the backend never sends (`factory`, `splitPairing`),
  // and check-wire-type-single-authority forbids a hand-declared type sharing a rootName.
  {
    schema: 'SSOT/schemas/surface.v1.json',
    outFile: 'surface.ts',
    rootName: 'SurfaceWire',
  },
];

function parseArgs(argv) {
  const args = { check: false };
  for (const a of argv.slice(2)) {
    if (a === '--check') args.check = true;
  }
  return args;
}

function lowerCamel(name) {
  return name.charAt(0).toLowerCase() + name.slice(1);
}

/**
 * Sanitize a `$defs` key into a valid PascalCase TS identifier. victools emits
 * names like `Component-nullable` / `LifecycleState-nullable` (nullable-ref
 * variants); the hyphen is illegal in a TS identifier, so collapse non-alnum
 * separators and PascalCase the segments.
 */
function sanitizeTypeName(rawName) {
  return rawName
    .split(/[^A-Za-z0-9]+/)
    .filter((s) => s.length > 0)
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join('');
}

function zodConstName(typeName) {
  return lowerCamel(typeName) + 'Schema';
}

function refTypeName(ref) {
  const m = /^#\/\$defs\/(.+)$/.exec(ref);
  if (!m) throw new Error('Unsupported $ref (only #/$defs/<Name> supported): ' + ref);
  return sanitizeTypeName(m[1]);
}

/** Split an anyOf into {members: non-null branches, nullable: bool}. */
function splitAnyOf(members) {
  const nullable = members.some((m) => m && m.type === 'null');
  const branches = members.filter((m) => !(m && m.type === 'null'));
  return { branches, nullable };
}

/** Collect the raw `$defs` names a schema fragment references via `$ref`. */
function collectRefs(node, acc) {
  if (Array.isArray(node)) {
    node.forEach((n) => collectRefs(n, acc));
  } else if (node && typeof node === 'object') {
    if (typeof node.$ref === 'string') {
      const m = /^#\/\$defs\/(.+)$/.exec(node.$ref);
      if (m) acc.add(m[1]);
    }
    for (const v of Object.values(node)) collectRefs(v, acc);
  }
  return acc;
}

/**
 * Order `$defs` so every def precedes the defs that reference it — required
 * because the emitted Zod consts reference each other (a const used before its
 * declaration is a temporal-dead-zone error). Throws on a cycle (recursive wire
 * types would need `z.lazy()`; none exist today).
 */
function orderDefs(defs) {
  const order = [];
  const done = new Set();
  const stack = new Set();
  function visit(name) {
    if (done.has(name)) return;
    if (stack.has(name)) throw new Error('Cyclic $defs not supported (needs z.lazy): ' + name);
    stack.add(name);
    for (const ref of collectRefs(defs[name], new Set())) {
      if (ref in defs && ref !== name) visit(ref);
    }
    stack.delete(name);
    done.add(name);
    order.push(name);
  }
  for (const name of Object.keys(defs)) visit(name);
  return order;
}

/** Split a possibly-array `type` into the non-null scalar + a nullable flag. */
function splitType(schema) {
  let t = schema.type;
  let nullable = false;
  if (Array.isArray(t)) {
    nullable = t.includes('null');
    const rest = t.filter((x) => x !== 'null');
    if (rest.length !== 1) {
      throw new Error('Unsupported multi-type (only [T,"null"] supported): ' + JSON.stringify(t));
    }
    t = rest[0];
  }
  return { t, nullable };
}

const INDENT = '  ';

/**
 * An "opaque" schema carries no type/ref/structure — victools emits `{}` for an untyped Java
 * `Object` field (arbitrary JSON the FE forwards verbatim). Rendered as `unknown` / `z.unknown()`.
 */
function isOpaqueSchema(schema) {
  return (
    !schema.$ref &&
    !schema.type &&
    !schema.anyOf &&
    !schema.enum &&
    !('const' in schema) &&
    !schema.properties &&
    !schema.items &&
    !schema.additionalProperties
  );
}

// ---- TypeScript type rendering ----

function tsType(schema, depth) {
  if (schema.$ref) return refTypeName(schema.$ref);
  if ('const' in schema) return JSON.stringify(schema.const);
  if (schema.anyOf) {
    const { branches, nullable } = splitAnyOf(schema.anyOf);
    const union = branches.map((b) => tsType(b, depth)).join(' | ');
    const base = branches.length > 1 ? '(' + union + ')' : union;
    return nullable ? base + ' | null' : base;
  }
  if (schema.enum) {
    return schema.enum.map((v) => JSON.stringify(v)).join(' | ');
  }
  // An empty schema (`{}`) — victools emits this for an untyped `Object` field: opaque JSON the FE
  // only forwards (an Operation's interface inputs/result, the availability AST, a uiHint value).
  if (isOpaqueSchema(schema)) return 'unknown';
  const { t, nullable } = splitType(schema);
  let base;
  switch (t) {
    case 'string':
      base = 'string';
      break;
    case 'integer':
    case 'number':
      base = 'number';
      break;
    case 'boolean':
      base = 'boolean';
      break;
    case 'array':
      if (!schema.items) throw new Error('array without items');
      base = tsArrayElement(schema.items, depth);
      break;
    case 'object':
      if (schema.properties) base = tsObjectLiteral(schema, depth);
      else if (schema.additionalProperties)
        base = 'Record<string, ' + tsType(schema.additionalProperties, depth) + '>';
      else base = 'Record<string, unknown>';
      break;
    default:
      throw new Error('Unsupported type: ' + JSON.stringify(t));
  }
  return nullable ? base + ' | null' : base;
}

function tsArrayElement(items, depth) {
  const el = tsType(items, depth);
  // Wrap union / object-literal element types so `[]` binds correctly.
  const needsParens = /[|}]/.test(el) || el.includes(' | ');
  return (needsParens ? '(' + el + ')' : el) + '[]';
}

function tsObjectLiteral(schema, depth) {
  const required = new Set(schema.required || []);
  const pad = INDENT.repeat(depth + 1);
  const closePad = INDENT.repeat(depth);
  const lines = Object.entries(schema.properties).map(([k, v]) => {
    const opt = required.has(k) ? '' : '?';
    return pad + k + opt + ': ' + tsType(v, depth + 1) + ';';
  });
  return '{\n' + lines.join('\n') + '\n' + closePad + '}';
}

function renderTsDeclaration(typeName, schema) {
  const { t, nullable } = splitType(schema);
  // A plain (non-nullable) object with declared properties → an interface for
  // readability. Nullable objects / anyOf / enums / aliases use `export type`
  // so the `| null` (and unions) are preserved on the named symbol itself.
  if (t === 'object' && schema.properties && !nullable && !schema.anyOf) {
    return 'export interface ' + typeName + ' ' + tsObjectLiteral(schema, 0);
  }
  return 'export type ' + typeName + ' = ' + tsType(schema, 0) + ';';
}

// ---- Zod schema rendering ----

function zodExpr(schema, depth) {
  if (schema.$ref) return zodConstName(refTypeName(schema.$ref));
  if ('const' in schema) return 'z.literal(' + JSON.stringify(schema.const) + ')';
  if (schema.anyOf) {
    const { branches, nullable } = splitAnyOf(schema.anyOf);
    let base;
    if (branches.length === 1) base = zodExpr(branches[0], depth);
    else base = 'z.union([' + branches.map((b) => zodExpr(b, depth)).join(', ') + '])';
    return nullable ? base + '.nullable()' : base;
  }
  let base;
  if (schema.enum) {
    base = 'z.enum([' + schema.enum.map((v) => JSON.stringify(v)).join(', ') + '])';
    return base; // plain enums here are never nullable
  }
  if (isOpaqueSchema(schema)) return 'z.unknown()';
  const { t, nullable } = splitType(schema);
  switch (t) {
    case 'string':
      base = 'z.string()';
      break;
    case 'integer':
      base = 'z.number().int()';
      break;
    case 'number':
      base = 'z.number()';
      break;
    case 'boolean':
      base = 'z.boolean()';
      break;
    case 'array':
      if (!schema.items) throw new Error('array without items');
      base = 'z.array(' + zodExpr(schema.items, depth) + ')';
      break;
    case 'object':
      if (schema.properties) base = zodObject(schema, depth);
      else if (schema.additionalProperties)
        base = 'z.record(z.string(), ' + zodExpr(schema.additionalProperties, depth) + ')';
      else base = 'z.record(z.string(), z.unknown())';
      break;
    default:
      throw new Error('Unsupported type: ' + JSON.stringify(t));
  }
  return nullable ? base + '.nullable()' : base;
}

function zodObject(schema, depth) {
  const required = new Set(schema.required || []);
  const pad = INDENT.repeat(depth + 1);
  const closePad = INDENT.repeat(depth);
  const lines = Object.entries(schema.properties).map(([k, v]) => {
    let e = zodExpr(v, depth + 1);
    if (!required.has(k)) e += '.optional()';
    return pad + JSON.stringify(k) + ': ' + e + ',';
  });
  return 'z.strictObject({\n' + lines.join('\n') + '\n' + closePad + '})';
}

function renderZodDeclaration(typeName, schema) {
  return 'export const ' + zodConstName(typeName) + ' = ' + zodExpr(schema, 0) + ';';
}

// ---- File assembly ----

function renderModule(target, schemaJson) {
  const defs = schemaJson.$defs || {};
  const rootSchema = { type: schemaJson.type, properties: schemaJson.properties, required: schemaJson.required };

  const blocks = [];
  blocks.push('/**');
  blocks.push(' * AUTO-GENERATED by scripts/codegen/gen-wire-schema-types.mjs (tempdoc 564).');
  blocks.push(' * Source schema: ' + target.schema);
  blocks.push(' *');
  blocks.push(' * The single canonical projection of the Java wire record: this file is the TS type');
  blocks.push(' * AND the runtime Zod validator, both generated from the one JSON Schema.');
  blocks.push(' * Do NOT edit by hand. Re-generate with:');
  blocks.push(' *   node scripts/codegen/gen-wire-schema-types.mjs');
  blocks.push(' */');
  blocks.push('');
  blocks.push("import { z } from 'zod';");
  blocks.push('');

  // $defs first, in dependency order (the root + later defs reference earlier ones).
  for (const defName of orderDefs(defs)) {
    const typeName = sanitizeTypeName(defName);
    blocks.push(renderTsDeclaration(typeName, defs[defName]));
    blocks.push(renderZodDeclaration(typeName, defs[defName]));
    blocks.push('');
  }

  blocks.push(renderTsDeclaration(target.rootName, rootSchema));
  blocks.push(renderZodDeclaration(target.rootName, rootSchema));
  blocks.push('');

  return blocks.join('\n');
}

function renderIndex(targets) {
  const lines = [
    '/**',
    ' * AUTO-GENERATED by scripts/codegen/gen-wire-schema-types.mjs (tempdoc 564).',
    ' * Barrel for the JSON-Schema-derived wire types + Zod schemas.',
    ' */',
    '',
  ];
  for (const t of targets) {
    const mod = './' + t.outFile.replace(/\.ts$/, '.js');
    // `isolatedModules` requires `export type` for type-only re-exports.
    lines.push("export type { " + t.rootName + " } from '" + mod + "';");
    lines.push("export { " + zodConstName(t.rootName) + " } from '" + mod + "';");
  }
  lines.push('');
  return lines.join('\n');
}

function ensureOutputDir() {
  if (!existsSync(OUTPUT_DIR)) mkdirSync(OUTPUT_DIR, { recursive: true });
}

function main() {
  const args = parseArgs(process.argv);
  ensureOutputDir();

  const outputs = {};
  for (const target of TARGETS) {
    const schemaPath = join(REPO_ROOT, target.schema);
    const schemaJson = JSON.parse(readFileSync(schemaPath, 'utf8'));
    outputs[target.outFile] = renderModule(target, schemaJson);
  }
  outputs['index.ts'] = renderIndex(TARGETS);

  if (args.check) {
    const drift = [];
    for (const [fname, content] of Object.entries(outputs)) {
      const p = join(OUTPUT_DIR, fname);
      const before = existsSync(p) ? readFileSync(p, 'utf8') : null;
      if (before !== content) drift.push(fname);
    }
    if (drift.length > 0) {
      console.error('[gen-wire-schema-types] CHECK FAILED: output drifted from committed files:');
      for (const d of drift) console.error('  - ' + d);
      console.error('Re-run `node scripts/codegen/gen-wire-schema-types.mjs`, inspect, commit the regen.');
      process.exit(1);
    }
    console.log('[gen-wire-schema-types] check passed — output matches committed files');
    return;
  }

  const written = [];
  for (const [fname, content] of Object.entries(outputs)) {
    const p = join(OUTPUT_DIR, fname);
    writeFileSync(p, content, 'utf8');
    written.push(p);
  }
  console.log(
    '[gen-wire-schema-types] wrote ' +
      written.length +
      ' files:\n  ' +
      written.map((p) => relative(REPO_ROOT, p)).join('\n  '),
  );
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  try {
    main();
  } catch (err) {
    console.error('[gen-wire-schema-types] failed:', err);
    process.exit(1);
  }
}

// The single registry of migrated wire records — consumed by the mandate gate
// (check-wire-type-single-authority.mjs) so it auto-extends as TARGETS grows.
export { TARGETS, OUTPUT_DIR };
