/**
 * Tests for the window-cutover forcing function (scripts/ci/check-window-cutover.mjs).
 *
 * Incomplete promotion stays report-only before, on and after the former deadline. Retirement
 * and unreadable-registration guards still fail at every date, and neither comments nor a
 * neighbouring registration can satisfy the audience check.
 *
 * Run: `node scripts/ci/check-window-cutover.test.mjs` (exits non-zero on failure)
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  run,
  registeredAudience,
  resolveNow,
  RETIRED_WINDOW_DIR,
  CORE_PLUGIN_FILE,
  CUTOVER_MARKER_FILE,
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
  // Keep CLI repository discovery inside the fixture even when TMPDIR is inside a checkout.
  fs.mkdirSync(path.join(root, '.git'));
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

const DATES = ['2026-11-29', '2026-11-30', '2026-12-01'];
const checker = fileURLToPath(new URL('./check-window-cutover.mjs', import.meta.url));

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

// --- promotion and hard guards, including the actual CLI at every date ------
const cases = [
  { label: 'neither completion condition', options: {}, warnings: 1,
    detail: ['not USER', CUTOVER_MARKER_FILE, '2026-08-19'] },
  { label: 'USER audience only', options: { pluginSource: plugin('USER') }, warnings: 1,
    detail: [CUTOVER_MARKER_FILE] },
  { label: 'marker only', options: { marker: true }, warnings: 1, detail: ['not USER'] },
  { label: 'both completion conditions', options: { pluginSource: plugin('USER'), marker: true } },
  { label: 'restored search-v2 despite complete promotion',
    options: { pluginSource: plugin('USER'), marker: true, revived: true }, errors: 1,
    detail: [RETIRED_WINDOW_DIR] },
  { label: 'missing CorePlugin despite marker', options: { pluginSource: null, marker: true },
    errors: 1, detail: [CORE_PLUGIN_FILE] },
  { label: 'missing successor registration despite USER neighbour and marker',
    options: { pluginSource: plugin('USER').replace('core.search-v3-surface', 'core.other-surface'), marker: true },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
  { label: 'unparseable successor audience despite USER neighbour and marker',
    options: { pluginSource: plugin('DYNAMIC').replace("audience: 'DYNAMIC',", 'audience: getAudience(),'), marker: true },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
  { label: 'line-commented successor despite USER neighbour and marker',
    options: { pluginSource: "// id: 'core.search-v3-surface', audience: 'USER'\n"
      + plugin('USER').replace('core.search-v3-surface', 'core.other-surface'), marker: true },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
  { label: 'block-commented successor despite USER neighbour and marker',
    options: { pluginSource: "/* id: 'core.search-v3-surface', audience: 'USER' */\n"
      + plugin('USER').replace('core.search-v3-surface', 'core.other-surface'), marker: true },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
];
for (const scenario of cases) {
  const root = makeFixture(scenario.options);
  try {
    for (const now of DATES) {
      const label = `${scenario.label} on ${now}`;
      const result = run(root, now);
      ok(`${label}: errors`, result.errors.length === (scenario.errors ?? 0));
      ok(`${label}: warnings`, result.warnings.length === (scenario.warnings ?? 0));
      ok(`${label}: diagnostic names the relevant condition`,
        (scenario.detail ?? []).every((part) => [...result.errors, ...result.warnings].join('\n').includes(part)));
      const cli = spawnSync(process.execPath, [checker, '--now', now], { cwd: root, encoding: 'utf8' });
      ok(`${label}: CLI exit status`, cli.status === (scenario.errors ? 1 : 0));
      ok(`${label}: CLI warning visibility`, cli.stderr.includes('check-window-cutover: WARN') === Boolean(scenario.warnings));
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
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
