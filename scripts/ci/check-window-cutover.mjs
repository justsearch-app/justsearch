#!/usr/bin/env node
/**
 * The window-cutover forcing function (tempdoc 851; owner decision 2026-08-19; deadline moved by
 * owner decision 2026-09-30, see DEADLINE).
 *
 * Three search/chat windows existed at once. Two of them were "temporary until the cutover":
 * search-v2 (tempdoc 818) sat DEVELOPER/DEEPLINK for months with an ACTIVE cutover tempdoc whose
 * parity rows were never checked, and was ultimately deleted un-promoted. This gate exists so the
 * SAME shape cannot repeat silently with Search v3 — a window parked "until the cutover" either
 * cuts over or is retired, and after a dated deadline the build says so.
 *
 * Two independent conditions:
 *
 *  (a) REAPPEARANCE — `modules/ui-web/src/shell-v0/views/search-v2/` must stay deleted. Restoring
 *      it always FAILS, at any date. The window was retired by owner decision, not shelved.
 *
 *  (b) CUTOVER DEADLINE — the Search v3 promotion must be COMPLETE by {@link DEADLINE}. Before the
 *      deadline an incomplete promotion WARNS (exit 0); on or after it, the check FAILS.
 *
 * "Complete" is detected structurally, from two facts that only the promotion can produce:
 *
 *   1. `core.search-v3-surface` is registered with `audience: 'USER'` in CorePlugin.ts — i.e. the
 *      window is actually reachable by a user, not just by a developer deeplink. Read grep-style
 *      from the registration block with comments stripped first, so a commented-out or merely
 *      discussed registration cannot satisfy it.
 *   2. `governance/window-cutover.done` exists — the promotion program's explicit marker. A file
 *      the promoting agent creates deliberately, so "the audience flipped as a side effect" is not
 *      enough to close this gate.
 *
 * Both must hold. Either one alone leaves the promotion incomplete.
 *
 * Run: `node scripts/ci/check-window-cutover.mjs [--now YYYY-MM-DD]`
 * `--now` (or `JUSTSEARCH_CHECK_NOW`) overrides today's date — it exists so the deadline branch is
 * testable before the deadline arrives, and is honoured in CI too (CI passes nothing, so CI always
 * evaluates against the real clock).
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/** The retired window's directory. Its reappearance is always a failure. */
export const RETIRED_WINDOW_DIR = 'modules/ui-web/src/shell-v0/views/search-v2';

/** Where surfaces are registered (the audience fact). */
export const CORE_PLUGIN_FILE = 'modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts';

/** The promotion program's explicit completion marker. */
export const CUTOVER_MARKER_FILE = 'governance/window-cutover.done';

/** The successor window's surface id. */
export const SUCCESSOR_SURFACE_ID = 'core.search-v3-surface';

/**
 * On or after this date an incomplete Search v3 promotion is a build failure, not a warning.
 * Owner decision 2026-09-30 moved it from 2026-09-30: main was frozen for lane F from 2026-09-08,
 * so tempdoc 852 could not progress. The new date follows the expected lane F merge.
 */
export const DEADLINE = '2026-11-30';

/** The owner decision this gate carries, named in every failure message. */
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
 * The audience declared for `surfaceId`, or null when the surface is not registered at all.
 * Reads the object literal that carries the id, bounded by that entry's own terminator, so a
 * neighbouring surface's audience cannot be mistaken for this one's.
 */
export function registeredAudience(pluginSource, surfaceId) {
  const source = stripComments(pluginSource);
  const idIndex = source.indexOf(`'${surfaceId}'`);
  if (idIndex === -1) return null;
  const rest = source.slice(idIndex);
  const end = /\n\s*\},/.exec(rest);
  const block = end === null ? rest : rest.slice(0, end.index);
  const audience = /audience:\s*'([A-Z]+)'/.exec(block);
  return audience === null ? null : audience[1];
}

/** Today, as YYYY-MM-DD, honouring the `--now` / env override. */
export function resolveNow(argv = process.argv.slice(2), env = process.env) {
  const flagIndex = argv.indexOf('--now');
  const fromFlag = flagIndex === -1 ? null : argv[flagIndex + 1];
  const raw = fromFlag ?? env.JUSTSEARCH_CHECK_NOW ?? null;
  if (raw === null) return new Date().toISOString().slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    throw new Error(`--now must be YYYY-MM-DD, got "${raw}"`);
  }
  return raw;
}

/**
 * Evaluate both conditions. Returns `{ errors, warnings }` — `errors` fails the build, `warnings`
 * do not. Date comparison is lexicographic, which is exact for zero-padded ISO dates.
 */
export function run(repoRoot, now) {
  const errors = [];
  const warnings = [];

  if (fs.existsSync(path.join(repoRoot, RETIRED_WINDOW_DIR))) {
    errors.push(
      `${RETIRED_WINDOW_DIR}/ exists again. The search-v2 window was RETIRED by owner decision`
        + ` ${OWNER_DECISION_DATE} (tempdoc 851) — deleted un-promoted, not shelved. Rebuilding it`
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

  const audience = registeredAudience(fs.readFileSync(pluginPath, 'utf8'), SUCCESSOR_SURFACE_ID);
  const markerPresent = fs.existsSync(path.join(repoRoot, CUTOVER_MARKER_FILE));
  const missing = [];
  if (audience !== 'USER') {
    missing.push(
      `${SUCCESSOR_SURFACE_ID} is registered with audience ${audience ?? '(not registered)'} in`
        + `${' '}${CORE_PLUGIN_FILE}, not USER — the successor window is still not user-reachable`,
    );
  }
  if (!markerPresent) {
    missing.push(
      `${CUTOVER_MARKER_FILE} does not exist — the promotion program creates this marker when the`
        + ' cutover is done',
    );
  }

  if (missing.length === 0) return { errors, warnings };

  const detail = missing.map((m) => `      - ${m}`).join('\n');
  if (now < DEADLINE) {
    warnings.push(
      `The Search v3 promotion is incomplete (deadline ${DEADLINE}; today ${now}):\n${detail}\n`
        + `      This WARNS until ${DEADLINE} and FAILS on and after it. Owner decision`
        + ` ${OWNER_DECISION_DATE} (tempdoc 851) retired search-v2 rather than let a third window`
        + ' sit un-promoted indefinitely; this deadline is what keeps v3 from repeating that.',
    );
  } else {
    errors.push(
      `The Search v3 promotion passed its ${DEADLINE} deadline while incomplete (today ${now}):\n`
        + `${detail}\n      Either finish the promotion (make the surface USER-reachable and land`
        + ` ${CUTOVER_MARKER_FILE}) or retire the window the way owner decision`
        + ` ${OWNER_DECISION_DATE} retired search-v2 (tempdoc 851 records that sweep). Moving the`
        + ' deadline is an owner decision, not a fix.',
    );
  }
  return { errors, warnings };
}

function main() {
  const { errors, warnings } = run(repoRootFromCwd(), resolveNow());
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
