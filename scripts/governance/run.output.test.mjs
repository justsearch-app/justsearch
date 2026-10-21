import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// The test-intent enforcer prints its report through this runner. Reproduce its large-output
// failure at the shared runner boundary, without depending on the branch's test-intent findings.
for (const mode of ['gate', 'warn']) {
  test(`${mode} mode drains a large failing report through a pipe before exiting`, async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'governance-output-'));
    try {
      const count = 639;
      const gates = Array.from({ length: count }, (_, i) => ({
        id: `output-${i}`,
        title: 'Output drainage regression',
        enforcer: 'scripts/governance/gates/test-intent/enforcer.mjs',
        config: { inputs: [{
          path: path.join(dir, `absent-${i}.json`),
          class: 'required',
          producer: `produce-${i}-` + 'x'.repeat(200),
        }] },
      }));
      const registry = path.join(dir, 'registry.json');
      writeFileSync(registry, JSON.stringify({ kind: 'discipline-gate-registry.v1', version: 1, gates }));
      const child = spawn(process.execPath, [
        fileURLToPath(new URL('./run.mjs', import.meta.url)),
        '--registry', registry, '--mode', mode, '--format', 'compact',
        '--out', path.join(dir, 'out.sarif'),
      ], { stdio: ['ignore', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
      // Leave stdout unread briefly so the OS pipe fills even on a fast test host.
      child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
      child.stdout.pause();
      const resume = setTimeout(() => child.stdout.resume(), 200);
      const status = await new Promise((resolve, reject) => {
        child.on('error', reject);
        child.on('close', resolve);
      });
      clearTimeout(resume);
      assert.equal(status, mode === 'gate' ? 1 : 0, stderr);
      assert.match(stdout, /639 gates evaluated, 639 fail, 639 findings/);
      assert.equal((stdout.match(/kernel\/input-missing:/g) ?? []).length, count,
        `received ${Buffer.byteLength(stdout)} bytes; the complete cause list must reach the pipe`);
      assert.match(stdout, /\[output-638\] kernel\/input-missing:.*\n$/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
}
