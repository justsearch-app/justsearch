import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

test('all repository logger identifiers catch bare messages and preserve contextual calls', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'slf4j-bare-'));
  try {
    const loggers = ['log', 'LOG', 'logger', 'LOGGER', 'DEFAULT_LOG', 'root', 'schedulerLogger', 'jetty', 'javalin', 'ioJustsearch', 'lucene', 'sqlite', 'captured', 'wiringLogger', 'ringLogger', 'gplLogger'];
    writeFileSync(path.join(root, 'Example.java'), loggers.map(name => `${name}.error(\n e.getMessage()\n);`).join('\n') + `
log.error("message", e);
LOG.warn(e.getMessage(), e);
logger.info("context: {}", e.getMessage());
LOGGER.debug("context " + e.getMessage());
// LOG.error(e.getMessage());
String text = "logger.warn(e.getMessage())";
/* LOGGER.info(e.getMessage()); */
`);
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./check-slf4j-bare-message-logging.mjs', import.meta.url)), '--root', root, '--json'], { encoding: 'utf8' });
    assert.equal(result.status, 1, result.stderr);
    assert.ok(result.stdout, JSON.stringify(result));
    const report = JSON.parse(result.stdout);
    assert.equal(report.violations.length, loggers.length);
    assert.deepEqual(report.violations.map(v => v.snippet.split('.')[0]), loggers);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
