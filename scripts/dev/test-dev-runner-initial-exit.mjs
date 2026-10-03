import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const temporary = path.join(repo, 'tmp');
fs.mkdirSync(temporary, { recursive: true });
const root = fs.mkdtempSync(path.join(temporary, 'justsearch-initial-exit-'));
const state = path.join(root, 'state');
const frontendMarker = path.join(root, 'frontend-started');
const uiPort = await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port;
    server.close(() => resolve(port));
  });
});
try {
  const result = spawnSync(process.execPath, [
    path.join(repo, 'scripts/dev/dev-runner.cjs'), 'start', '--json', '--skip-build',
    '--clean', 'none', '--api-port', '0', '--ui-port', String(uiPort), '--data-dir', path.join(root, 'data'),
    '--session-id', 'initial-exit-test', '--lease-duration-sec', '60',
  ], {
    cwd: repo, encoding: 'utf8', timeout: 20000,
    env: { ...process.env, JUSTSEARCH_SUPERVISOR_HARNESS: '1',
      JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state,
      JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, '-e', 'process.exit(23)']),
      JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e',
        `require('node:fs').writeFileSync(${JSON.stringify(frontendMarker)}, 'started')`]),
    },
  });
  assert.equal(result.status, 1, `${result.stdout} ${result.stderr} ${result.error ?? ''}`);
  const envelope = result.stdout.split(/\r?\n/).filter(line => line.startsWith('{'))
    .map(line => JSON.parse(line)).find(value => value.ok === false);
  assert.equal(envelope?.error?.code, 'ENGINE_EXITED_DURING_DISCOVERY', result.stdout);
  const details = envelope.error.details;
  assert.equal(details.exitCode, 23);
  assert.equal(details.signalCode, null);
  assert.ok(Number.isInteger(details.pid) && details.pid > 0);
  assert.equal(details.childReplaced, false);
  assert.equal(details.initialDiscovery, true);
  assert.ok(typeof details.runId === 'string' && details.runId.length > 0);
  assert.equal(fs.existsSync(path.join(state, 'runs', details.runId, 'run.json')), false);
  assert.equal(fs.existsSync(frontendMarker), false, 'frontend cannot start before initial Engine discovery');
} finally {
  const absolute = path.resolve(root);
  assert.equal(path.dirname(absolute), path.resolve(temporary));
  assert.ok(path.basename(absolute).startsWith('justsearch-initial-exit-'));
  fs.rmSync(absolute, { recursive: true, force: true });
}
console.log('PASS initial Engine exit: actual child status retained before run registration');
