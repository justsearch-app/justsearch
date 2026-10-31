#!/usr/bin/env node
/**
 * Check that the thread client imports the generated event-kind tuple and uses it for strict
 * parsing and known-kind rejection. Required by the ui-web-gates recipe. Generated tuple freshness
 * is checked separately by RegistryEnumsTsGenerationTest.
 */
import { readFileSync } from 'node:fs';
import { stripComments } from '../lib/strip-comments.mjs';

const FE_CLIENT_FILE = 'modules/ui-web/src/shell-v0/views/unifiedThreadClient.ts';

/** Require the generated tuple at both client validation boundaries. */
export function checkClientConsumption(clientSource) {
  const failures = [];
  const client = stripComments(clientSource, { withHtml: false });
  if (!/import\s*\{\s*INTERACTION_EVENT_KINDS\s+as\s+KNOWN_EVENT_KINDS\s*\}\s*from\s*['"]\.\.\/\.\.\/api\/generated\/registry-enums\.generated\.js['"]/.test(client)
      || !client.includes('z.enum(KNOWN_EVENT_KINDS)')
      || !client.includes('new Set(KNOWN_EVENT_KINDS)')) {
    failures.push('The thread client must use the generated tuple for strict parsing and known-kind rejection.');
  }

  return failures;
}

function main() {
  const failures = checkClientConsumption(readFileSync(FE_CLIENT_FILE, 'utf8'));

  if (failures.length > 0) {
    console.error(
      '✗ thread-event-kinds check FAILED (tempdoc S4a):\n' + failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }

  console.log(
    '✓ thread-event-kinds check OK — the client uses the generated tuple for strict parsing and known-kind rejection.',
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-thread-event-kinds.mjs')) {
  main();
}
