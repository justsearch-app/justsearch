#!/usr/bin/env node
/**
 * Slice 491 §9.D Phase E C5 — Q8 enforcement: every USER-audience
 * `ConversationShape` shipped in `CoreConversationShapeCatalog` must have a
 * registered FE view factory in `modules/ui-web/src/shell-v0/router/`
 * `viewFactoryRegistry`. Prevents the orphaned-FE-wrapper defect class
 * (§9.E A5 — `streams.ts:summarizeDocumentStream`-with-zero-callers was the
 * original instance of this problem; this gate keeps the substrate honest).
 *
 * Reads the shape list from the bundled fixture in
 * `scripts/codegen/gen-shape-handlers.mjs` (`BUNDLED_SHAPES`) so the gate
 * runs without a live backend. Pass `--live --port=N` to fetch
 * `/api/registry/shapes` from a running backend instead.
 *
 * For each shape, string-greps every `*.ts` under
 * `modules/ui-web/src/shell-v0/` for a `registerViewFactory(<id>, ...)`
 * call. Hard-fail if any shape has no registration.
 *
 * Usage:
 *   node scripts/ci/check-shape-view-coverage.mjs              # bundled fixture
 *   node scripts/ci/check-shape-view-coverage.mjs --live       # live fetch (default port 33221)
 *   node scripts/ci/check-shape-view-coverage.mjs --live --port=5180
 *
 * Exit code: 0 on full coverage; 1 on any missing-factory finding.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const FE_SRC_DIR = join(REPO_ROOT, 'modules', 'ui-web', 'src', 'shell-v0');

async function fetchLiveShapeIds(port) {
  const url = `http://127.0.0.1:${port}/api/registry/shapes`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(
      `Live fetch failed: ${res.status} ${res.statusText} (${url})`,
    );
  }
  const body = await res.json();
  if (!body || !Array.isArray(body.entries)) {
    throw new Error('Live response missing entries[]');
  }
  return body.entries
    .filter((e) => e.audience === 'USER')
    .map((e) => e.id);
}

function walkTsFiles(dir, accum = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      // Skip generated + node_modules-style dirs (none under shell-v0 today,
      // but guard for the future).
      if (entry === 'generated' || entry === 'node_modules') continue;
      walkTsFiles(full, accum);
    } else if (entry.endsWith('.ts') && !entry.endsWith('.test.ts')) {
      accum.push(full);
    }
  }
  return accum;
}

function findRegistrationsForShape(shapeId, tsFiles) {
  // Match registerViewFactory('core.x', ...) — single OR double quotes.
  const re = new RegExp(
    `registerViewFactory\\(\\s*['"]${shapeId.replace(/[.\-]/g, '\\$&')}['"]`,
    'g',
  );
  const hits = [];
  for (const file of tsFiles) {
    const content = readFileSync(file, 'utf8');
    if (re.test(content)) {
      hits.push(file);
    }
    re.lastIndex = 0;
  }
  return hits;
}

function parseArgs(argv) {
  const args = { live: false, port: 33221 };
  for (const a of argv.slice(2)) {
    if (a === '--live') args.live = true;
    else if (a.startsWith('--port=')) args.port = Number(a.slice(7));
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv);
  let shapeIds;
  if (args.live) {
    shapeIds = await fetchLiveShapeIds(args.port);
  } else {
    // F7: gen-shape-handlers.mjs is now import-safe (main-guard + exported
    // BUNDLED_SHAPES). Import directly rather than source-parsing.
    const mod = await import(
      pathToFileURL(
        join(REPO_ROOT, 'scripts', 'codegen', 'gen-shape-handlers.mjs'),
      ).href,
    );
    shapeIds = mod.BUNDLED_SHAPES.map((s) => s.id);
  }

  if (!shapeIds || shapeIds.length === 0) {
    console.error('No shapes loaded — cannot run coverage check.');
    process.exit(1);
  }

  // NavigateChatShape is the canonical exception: its audience is the
  // agent-loop's URL-emission consumer (i.e., AgentRunShape's runner via
  // URLExtractor), NOT a top-level user. The shape has no surface entry
  // (no FE rail mount) and is invoked via /api/chat/url-emit for probes +
  // future consumers without agent tools. Documented in Q7 / §9.F.
  // This is the only architectural exemption. F5 lifted the other three:
  //   - core.agent-run: AgentSurface.ts now registerViewFactory's it.
  //   - core.batch-summarize + core.hierarchical-summarize: SummarizeView
  //     branches send() by shape-id; one view covers all three Summarize
  //     shapes (single-doc {docId} + multi-doc {docIds[]}).
  const EXEMPT = new Set(['core.navigate-chat']);

  const tsFiles = walkTsFiles(FE_SRC_DIR);

  const missing = [];
  const ok = [];
  let exemptCount = 0;
  for (const id of shapeIds) {
    if (EXEMPT.has(id)) {
      ok.push({ id, status: 'EXEMPT' });
      exemptCount++;
      continue;
    }
    // Tempdoc 560 §28.G — `vendor.*` shapes are plugin-contributed and register their view factory at
    // RUNTIME (PluginRegistry.applyContribution → registerViewFactory), trust-validated at install
    // time, not via a source-level callsite this gate can grep. The gate's charter is `core.*`
    // coverage; plugin trust is PluginRegistry's responsibility. (Only reachable in --live mode, where
    // /api/registry/shapes surfaces composed plugin shapes.)
    if (id.startsWith('vendor.')) {
      ok.push({ id, status: 'EXEMPT (vendor/plugin runtime-registered)' });
      exemptCount++;
      continue;
    }
    const hits = findRegistrationsForShape(id, tsFiles);
    if (hits.length === 0) {
      missing.push(id);
    } else {
      ok.push({ id, status: `REGISTERED (${hits.length} site${hits.length === 1 ? '' : 's'})` });
    }
  }

  console.log('Shape view coverage report (slice 491 §9.D C5 / Q8):');
  for (const entry of ok) {
    console.log(`  OK    ${entry.id} — ${entry.status}`);
  }
  for (const id of missing) {
    console.log(`  FAIL  ${id} — no registerViewFactory call found`);
  }

  if (missing.length > 0) {
    console.error(
      `\n${missing.length} shape(s) missing a registered FE view factory:`,
    );
    for (const id of missing) {
      console.error(`  - ${id}`);
    }
    console.error(
      '\nFix: import the typed view module into a shell-loaded path so its ' +
        "side-effect `registerViewFactory('<shape-id>', '<jf-tag>')` runs at " +
        'module-load time. See AskView.ts / SummarizeView.ts for the pattern.',
    );
    process.exit(1);
  }

  console.log(`\nAll ${shapeIds.length - exemptCount} non-exempt shape(s) have a registered view factory.`);
  process.exit(0);
}

main().catch((err) => {
  console.error('Coverage check failed:', err);
  process.exit(1);
});
