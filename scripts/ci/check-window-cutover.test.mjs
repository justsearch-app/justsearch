/**
 * Tests for the window-cutover forcing function (scripts/ci/check-window-cutover.mjs).
 *
 * A dated gate is the easy thing to ship inert: it passes on the day it lands and nobody sees the
 * branch that matters until the deadline arrives. These exercise the BITE — the reappearance fail,
 * both sides of the deadline, and each half of "complete" alone failing to close it — plus the
 * degenerate cases (a commented-out registration; a missing CorePlugin) that must not pass silently.
 *
 * Run: `node scripts/ci/check-window-cutover.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

import {
  run,
  registeredAudience,
  resolveNow,
  RETIRED_WINDOW_DIR,
  CORE_PLUGIN_FILE,
  CUTOVER_MARKER_FILE,
  DEADLINE,
} from './check-window-cutover.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

const plugin = (audience) => `
export const CORE_SURFACES = [
  {
    id: 'core.api-explorer-surface',
    mountTag: 'jf-api-explorer-view',
    audience: 'DEVELOPER',
    placement: 'DEEPLINK',
  },
  {
    id: 'core.search-v3-surface',
    mountTag: 'jf-sv3-window',
    audience: '${audience}',
    placement: 'DEEPLINK',
  },
  {
    id: 'core.brain-surface',
    mountTag: 'jf-brain-surface',
    audience: 'USER',
    placement: 'RAIL',
  },
];
`;

/** A fixture repo: CorePlugin source, plus optional marker file and revived window directory. */
function makeFixture({ pluginSource = plugin('DEVELOPER'), marker = false, revived = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'window-cutover-gate-'));
  if (pluginSource !== null) {
    const pluginPath = path.join(root, CORE_PLUGIN_FILE);
    fs.mkdirSync(path.dirname(pluginPath), { recursive: true });
    fs.writeFileSync(pluginPath, pluginSource);
  }
  if (marker) {
    const markerPath = path.join(root, CUTOVER_MARKER_FILE);
    fs.mkdirSync(path.dirname(markerPath), { recursive: true });
    fs.writeFileSync(markerPath, 'cutover complete\n');
  }
  if (revived) fs.mkdirSync(path.join(root, RETIRED_WINDOW_DIR), { recursive: true });
  return root;
}

// Derived from DEADLINE so a moved deadline keeps testing its own boundary: the day before WARNS,
// the deadline day itself FAILS ("on or after").
const BEFORE = new Date(Date.parse(`${DEADLINE}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);
const AFTER = DEADLINE;

// --- audience parsing ------------------------------------------------------
ok(
  "the successor's own audience is read, not a neighbour's",
  registeredAudience(plugin('DEVELOPER'), 'core.search-v3-surface') === 'DEVELOPER',
);
ok(
  'a promoted registration reads as USER',
  registeredAudience(plugin('USER'), 'core.search-v3-surface') === 'USER',
);
ok(
  'an unregistered surface reads as null, not as some default',
  registeredAudience(plugin('USER'), 'core.search-v9-surface') === null,
);
ok(
  'a commented-out registration does not satisfy the audience check',
  registeredAudience(
    `// { id: 'core.search-v3-surface', audience: 'USER' },\n${plugin('DEVELOPER')}`,
    'core.search-v3-surface',
  ) === 'DEVELOPER',
);
ok(
  'a block-commented registration does not satisfy it either',
  registeredAudience(
    `/* id: 'core.search-v3-surface', audience: 'USER' */\n${plugin('DEVELOPER')}`,
    'core.search-v3-surface',
  ) === 'DEVELOPER',
);

// --- (a) reappearance ------------------------------------------------------
{
  const root = makeFixture({ pluginSource: plugin('USER'), marker: true, revived: true });
  const { errors } = run(root, BEFORE);
  ok(
    'a revived search-v2 directory FAILS even before the deadline, and even when v3 is promoted',
    errors.length === 1 && errors[0].includes(RETIRED_WINDOW_DIR),
  );
}

// --- (b) deadline ----------------------------------------------------------
{
  const root = makeFixture();
  const before = run(root, BEFORE);
  const after = run(root, AFTER);
  ok('an incomplete promotion WARNS before the deadline', before.errors.length === 0 && before.warnings.length === 1);
  ok('the warning names the deadline', before.warnings[0].includes(DEADLINE));
  ok('the same state FAILS on/after the deadline', after.errors.length === 1 && after.warnings.length === 0);
  ok('the failure names the owner decision date', after.errors[0].includes('2026-08-19'));
}
{
  const root = makeFixture({ pluginSource: plugin('USER'), marker: true });
  ok(
    'a complete promotion passes on both sides of the deadline',
    run(root, BEFORE).errors.length === 0
      && run(root, BEFORE).warnings.length === 0
      && run(root, AFTER).errors.length === 0,
  );
}
{
  const usrOnly = makeFixture({ pluginSource: plugin('USER'), marker: false });
  const markerOnly = makeFixture({ pluginSource: plugin('DEVELOPER'), marker: true });
  ok('USER audience alone does not close the gate', run(usrOnly, AFTER).errors.length === 1);
  ok('the marker alone does not close the gate', run(markerOnly, AFTER).errors.length === 1);
  ok(
    'each single-condition failure names the condition still missing',
    run(usrOnly, AFTER).errors[0].includes(CUTOVER_MARKER_FILE)
      && run(markerOnly, AFTER).errors[0].includes('not USER'),
  );
}

// --- degenerate ------------------------------------------------------------
{
  const root = makeFixture({ pluginSource: null, marker: true });
  const { errors } = run(root, BEFORE);
  ok(
    'a missing CorePlugin FAILS rather than passing silently',
    errors.length === 1 && errors[0].includes(CORE_PLUGIN_FILE),
  );
}

// --- date resolution -------------------------------------------------------
ok('--now is honoured', resolveNow(['--now', '2026-10-01'], {}) === '2026-10-01');
ok('the env override is honoured', resolveNow([], { JUSTSEARCH_CHECK_NOW: '2026-10-02' }) === '2026-10-02');
ok('the flag wins over the env', resolveNow(['--now', '2026-10-03'], { JUSTSEARCH_CHECK_NOW: '2026-10-02' }) === '2026-10-03');
ok('a real date is returned with no override', /^\d{4}-\d{2}-\d{2}$/.test(resolveNow([], {})));
{
  let threw = false;
  try {
    resolveNow(['--now', 'yesterday'], {});
  } catch {
    threw = true;
  }
  ok('a malformed --now is rejected rather than silently ignored', threw);
}

if (failures.length > 0) {
  console.error(`check-window-cutover.test: FAIL (${failures.length} of ${passed + failures.length})`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exitCode = 1;
} else {
  console.log(`check-window-cutover.test: OK (${passed} assertions)`);
}
