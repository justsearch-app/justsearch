#!/usr/bin/env node
/**
 * Keep the retired search-v2 directory deleted and the Search v3 audience observable.
 * A missing CorePlugin file or unreadable registration fails; a non-USER audience warns.
 * Comments and neighbouring registrations cannot satisfy the audience check.
 *
 * Run: `node scripts/ci/check-window-cutover.mjs`
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { readCorePluginDeclarations, expandCoreSurfaceMetadata } from '../lib/core-surface-declarations.mjs';

/** The retired window's directory. Its reappearance is always a failure. */
export const RETIRED_WINDOW_DIR = 'modules/ui-web/src/shell-v0/views/search-v2';

/** Where surfaces are registered (the audience fact). */
export const CORE_PLUGIN_FILE = 'modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts';

/** The successor window's surface id. */
export const SUCCESSOR_SURFACE_ID = 'core.search-v3-surface';

/** The owner decision carried by the retirement guard. */
export const OWNER_DECISION_DATE = '2026-08-19';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

/** Strip `//` and block comments so a commented-out registration cannot satisfy the audience check. */
export function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

/**
 * The audience declared for `surfaceId`, or null when its registration is absent or unparseable.
 * Reads the object literal that carries the id, bounded by that entry's own terminator, so a
 * neighbouring surface's audience cannot be mistaken for this one's.
 */
export function registeredAudience(pluginSource, surfaceId, generatedSource) {
  const source = expandCoreSurfaceMetadata(pluginSource, generatedSource);
  const escapedId = surfaceId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const idIndex = source.search(new RegExp(`\\bid:\\s*['"]${escapedId}['"]`));
  if (idIndex === -1) return null;
  const rest = source.slice(idIndex);
  const end = /\n\s*\},/.exec(rest);
  const block = end === null ? rest : rest.slice(0, end.index);
  const audience = [...block.matchAll(/audience:\s*['"]([A-Z]+)['"]/g)].at(-1);
  return audience?.[1] ?? null;
}

/**
 * Check retirement and registration readability, and report a non-USER audience.
 * Returns `{ errors, warnings }` — `errors` fails the build, `warnings` do not.
 */
export function run(repoRoot) {
  const errors = [];
  const warnings = [];

  if (fs.existsSync(path.join(repoRoot, RETIRED_WINDOW_DIR))) {
    errors.push(
      `${RETIRED_WINDOW_DIR}/ exists again. The search-v2 window was RETIRED by owner decision`
        + ` ${OWNER_DECISION_DATE} (tempdoc 851) — deleted, not shelved. Rebuilding it`
        + ' recreates the three-window state that decision ended; if the window is genuinely wanted'
        + ' back, that is a new owner decision and a new tempdoc, not a restored directory.',
    );
  }

  const pluginPath = path.join(repoRoot, CORE_PLUGIN_FILE);
  if (!fs.existsSync(pluginPath)) {
    // Not a pass: the check can no longer see the fact it exists to check.
    errors.push(
      `${CORE_PLUGIN_FILE} is missing — this gate can no longer read which audience`
        + ` ${SUCCESSOR_SURFACE_ID} is registered for. Point it at the new registration site.`,
    );
    return { errors, warnings };
  }

  const audience = registeredAudience(readCorePluginDeclarations(CORE_PLUGIN_FILE, repoRoot), SUCCESSOR_SURFACE_ID);
  if (audience === null) {
    errors.push(
      `${CORE_PLUGIN_FILE} has a missing or unparseable registration for ${SUCCESSOR_SURFACE_ID}`
        + ' — this gate can no longer read its audience. Point it at a readable registration.',
    );
    return { errors, warnings };
  }
  if (audience !== 'USER') {
    warnings.push(
      `${SUCCESSOR_SURFACE_ID} is registered with audience ${audience} in`
        + `${' '}${CORE_PLUGIN_FILE}, not USER — the successor window is still not user-reachable`,
    );
  }
  return { errors, warnings };
}

function main() {
  const { errors, warnings } = run(repoRootFromCwd());
  for (const w of warnings) console.warn(`check-window-cutover: WARN\n  - ${w}`);
  if (errors.length === 0) {
    console.log('check-window-cutover: OK');
    return;
  }
  console.error('check-window-cutover: FAIL');
  for (const e of errors) console.error(`  - ${e}`);
  process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href) {
  main();
}
