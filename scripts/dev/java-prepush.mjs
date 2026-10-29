#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const flags = process.argv.slice(2);
const unknown = flags.find((flag) => !['--unreferenced', '--dry-run'].includes(flag));
if (unknown !== undefined) {
  console.error('Usage: node scripts/dev/java-prepush.mjs [--unreferenced] [--dry-run]');
  process.exitCode = 1;
} else {
  const args = [
    './gradlew', 'compileTestJava', 'compileIntegrationTestJava', 'pmdAll', '--continue',
    '-PskipErrorProneTests=false', '-PskipWebBuild=true',
  ];
  if (flags.includes('--unreferenced')) {
    args.push(':modules:app-launcher:test', '--tests', '*UnreferencedCodeTest');
  }
  if (flags.includes('--dry-run')) {
    console.log(`js-gradle-slot ${args.map((arg) => arg.includes('*') ? `'${arg}'` : arg).join(' ')}`);
  } else {
    const result = spawnSync('js-gradle-slot', args, {
      cwd: fileURLToPath(new URL('../../', import.meta.url)),
      stdio: 'inherit',
    });
    if (result.error) console.error(`java-prepush: ${result.error.message}`);
    process.exitCode = result.status ?? 1;
  }
}
