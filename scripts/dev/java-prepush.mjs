#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { accessSync, constants, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const flags = process.argv.slice(2);
const unknown = flags.find((flag) => !['--unreferenced', '--dry-run'].includes(flag));
if (unknown !== undefined) {
  console.error('Usage: node scripts/dev/java-prepush.mjs [--unreferenced] [--dry-run]');
  process.exitCode = 1;
} else {
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const windows = process.platform === 'win32';
  const extensions = windows ? (process.env.PATHEXT || '.COM;.EXE;.BAT;.CMD').split(';') : [''];
  const slotAvailable = (process.env.PATH || '').split(path.delimiter).some((directory) =>
    extensions.some((extension) => {
      const candidate = path.resolve(root, directory, `js-gradle-slot${extension}`);
      try {
        accessSync(candidate, windows ? constants.F_OK : constants.X_OK);
        return statSync(candidate).isFile();
      } catch { return false; }
    }));
  const gradlew = windows ? 'gradlew.bat' : './gradlew';
  const command = slotAvailable ? 'js-gradle-slot' : gradlew;
  const args = [
    ...(slotAvailable ? [gradlew] : []), 'compileTestJava', 'compileIntegrationTestJava', 'pmdAll', '--continue',
    '-PskipErrorProneTests=false', '-PskipWebBuild=true',
  ];
  if (flags.includes('--unreferenced')) {
    args.push(':modules:app-launcher:test', '--tests', '*UnreferencedCodeTest');
  }
  if (!slotAvailable) console.error('java-prepush: js-gradle-slot was not found on PATH; running the Gradle wrapper directly.');
  if (flags.includes('--dry-run')) {
    const quote = windows ? '"' : "'";
    console.log(`${command} ${args.map((arg) => arg.includes('*') ? `${quote}${arg}${quote}` : arg).join(' ')}`);
  } else {
    const result = spawnSync(command, args, {
      cwd: root,
      stdio: 'inherit',
      shell: windows,
    });
    if (result.error) console.error(`java-prepush: ${result.error.message}`);
    process.exitCode = result.status ?? 1;
  }
}
