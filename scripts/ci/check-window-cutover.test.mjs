/**
 * Tests for the permanent window guards (scripts/ci/check-window-cutover.mjs).
 *
 * Retirement and unreadable registrations fail; non-USER audiences warn without failing.
 * Neither comments nor a neighbouring registration can satisfy the audience check.
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
  RETIRED_WINDOW_DIR,
  CORE_PLUGIN_FILE,
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

/** A fixture repo: CorePlugin source, plus an optional revived window directory. */
function makeFixture({ pluginSource = plugin('DEVELOPER'), revived = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'window-cutover-gate-'));
  // Keep CLI repository discovery inside the fixture even when TMPDIR is inside a checkout.
  fs.mkdirSync(path.join(root, '.git'));
  if (pluginSource !== null) {
    const pluginPath = path.join(root, CORE_PLUGIN_FILE);
    fs.mkdirSync(path.dirname(pluginPath), { recursive: true });
    fs.writeFileSync(pluginPath, pluginSource);
  }
  if (revived) fs.mkdirSync(path.join(root, RETIRED_WINDOW_DIR), { recursive: true });
  return root;
}

const checker = fileURLToPath(new URL('./check-window-cutover.mjs', import.meta.url));

// --- audience parsing ------------------------------------------------------
ok(
  "the successor's own audience is read, not a neighbour's",
  registeredAudience(plugin('DEVELOPER'), 'core.search-v3-surface') === 'DEVELOPER',
);
ok(
  'a USER registration reads as USER',
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

ok(
  'split pairing before the real registration is not mistaken for the surface id',
  registeredAudience(`const library = { id: 'core.library-surface', audience: 'USER',
    splitPairing: { secondary: 'core.search-v3-surface' },
  };\n${plugin('DEVELOPER')}`, 'core.search-v3-surface') === 'DEVELOPER',
);
ok(
  'a bare string mention without any registration remains unreadable',
  registeredAudience(`const target = 'core.search-v3-surface';\n${plugin('USER').replace('core.search-v3-surface', 'core.other-surface')}`,
    'core.search-v3-surface') === null,
);

// --- permanent guards and audience reporting, including the actual CLI ------
const cases = [
  { label: 'non-USER audience', options: {}, warnings: 1,
    detail: ['core.search-v3-surface', 'DEVELOPER', CORE_PLUGIN_FILE, 'not USER'] },
  { label: 'USER audience', options: { pluginSource: plugin('USER') } },
  { label: 'restored search-v2 with USER audience',
    options: { pluginSource: plugin('USER'), revived: true }, errors: 1,
    detail: [RETIRED_WINDOW_DIR] },
  { label: 'restored search-v2 with non-USER audience',
    options: { revived: true }, errors: 1, warnings: 1,
    detail: [RETIRED_WINDOW_DIR, 'not USER'] },
  { label: 'missing CorePlugin', options: { pluginSource: null },
    errors: 1, detail: [CORE_PLUGIN_FILE] },
  { label: 'missing successor registration despite USER neighbour',
    options: { pluginSource: plugin('USER').replace('core.search-v3-surface', 'core.other-surface') },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
  { label: 'unparseable successor audience despite USER neighbour',
    options: { pluginSource: plugin('DYNAMIC').replace("audience: 'DYNAMIC',", 'audience: getAudience(),') },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
  { label: 'line-commented successor despite USER neighbour',
    options: { pluginSource: "// id: 'core.search-v3-surface', audience: 'USER'\n"
      + plugin('USER').replace('core.search-v3-surface', 'core.other-surface') },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
  { label: 'block-commented successor despite USER neighbour',
    options: { pluginSource: "/* id: 'core.search-v3-surface', audience: 'USER' */\n"
      + plugin('USER').replace('core.search-v3-surface', 'core.other-surface') },
    errors: 1, detail: [CORE_PLUGIN_FILE, 'registration'] },
];
for (const scenario of cases) {
  const root = makeFixture(scenario.options);
  try {
    const label = scenario.label;
    const result = run(root);
    ok(`${label}: errors`, result.errors.length === (scenario.errors ?? 0));
    ok(`${label}: warnings`, result.warnings.length === (scenario.warnings ?? 0));
    ok(`${label}: diagnostic names the relevant condition`,
      (scenario.detail ?? []).every((part) => [...result.errors, ...result.warnings].join('\n').includes(part)));
    const cli = spawnSync(process.execPath, [checker], { cwd: root, encoding: 'utf8' });
    ok(`${label}: CLI exit status`, cli.status === (scenario.errors ? 1 : 0));
    ok(`${label}: CLI warning visibility`, cli.stderr.includes('check-window-cutover: WARN') === Boolean(scenario.warnings));
    ok(`${label}: CLI diagnostic names the relevant condition`,
      (scenario.detail ?? []).every((part) => cli.stderr.includes(part)));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

if (failures.length > 0) {
  console.error(`check-window-cutover.test: FAIL (${failures.length} of ${passed + failures.length})`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exitCode = 1;
} else {
  console.log(`check-window-cutover.test: OK (${passed} assertions)`);
}
