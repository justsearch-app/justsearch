// SPDX-License-Identifier: Apache-2.0
// The desktop window's content policy (Tauri adds a nonce to style-src, which switches off
// 'unsafe-inline') refuses style attributes set from script and <style> elements made at runtime.
// The browser UI has no such policy, so this runs e2e/csp-chromium.mjs: a production build served
// under that policy in headless Chromium (needs: npx playwright-core install chromium).
import { expect, it } from 'vitest';
import { execFile } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';

const repoRoot = path.resolve(__dirname, '../../../..');

it('per-element styles and runtime sheets apply under the desktop window policy', async () => {
  const evidence = await mkdtemp(path.join(os.tmpdir(), 'csp-styles-'));
  try {
    const { stdout } = await promisify(execFile)(process.execPath,
      [path.join(repoRoot, 'modules/ui-web/e2e/csp-chromium.mjs'), evidence],
      { cwd: repoRoot, env: { ...process.env, JS_REPO_ROOT: repoRoot }, maxBuffer: 16 * 1024 * 1024 })
      .catch((error: { stdout?: string; stderr?: string }) => {
        throw new Error(`csp-chromium failed:\n${error.stdout ?? ''}\n${error.stderr ?? ''}`);
      });
    const failed = JSON.parse(stdout.slice(stdout.indexOf('{'))).checks.filter((c: { result: string }) => c.result !== 'pass');
    expect(failed).toEqual([]);
  } finally {
    await rm(evidence, { recursive: true, force: true });
  }
}, 180_000);
