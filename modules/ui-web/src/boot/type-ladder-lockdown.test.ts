// SPDX-License-Identifier: Apache-2.0
// Production typography under the desktop CSP; needs headless Chromium.
import { expect, it } from 'vitest';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';

const repoRoot = path.resolve(__dirname, '../../../..');

it('text, corners and bundled variable Inter follow the type ladder under the desktop policy', async () => {
  const evidence = await mkdtemp(path.join(os.tmpdir(), 'type-ladder-'));
  let passed = false;
  try {
    const { stdout } = await promisify(execFile)(process.execPath,
      [path.join(repoRoot, 'modules/ui-web/e2e/type-ladder-chromium.mjs'), evidence],
      { cwd: repoRoot, env: { ...process.env, JS_REPO_ROOT: repoRoot }, maxBuffer: 16 * 1024 * 1024 })
      .catch((error: { stdout?: string; stderr?: string }) => {
        throw new Error(`type-ladder-chromium failed:\n${error.stdout ?? ''}\n${error.stderr ?? ''}`);
      });
    const summary = JSON.parse(stdout.slice(stdout.lastIndexOf('\n{') + 1)) as { violations: number; surfaces: number };
    expect(summary.violations).toBe(0);
    expect(summary.surfaces).toBe(158);
    const inventory = JSON.parse(await readFile(path.join(evidence, 'results.json'), 'utf8')) as {
      checks: { id: string; result: string; textElements?: number }[];
    };
    for (const theme of ['dark', 'light']) {
      expect(inventory.checks.find(check => check.id === `${theme}/search-results-filters`)).toMatchObject({ result: 'pass' });
      expect(inventory.checks.find(check => check.id === `${theme}/search-results-filters`)!.textElements).toBeGreaterThan(0);
    }
    passed = true;
  } finally {
    if (passed) await rm(evidence, { recursive: true, force: true });
    else console.error(`Type ladder screenshots and diagnostics: ${evidence}`);
  }
}, 180_000);
