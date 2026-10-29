import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./java-prepush.mjs', import.meta.url));
const root = fileURLToPath(new URL('../../', import.meta.url));
const gradleArgs = [
  'compileTestJava', 'compileIntegrationTestJava', 'pmdAll', '--continue',
  '-PskipErrorProneTests=false', '-PskipWebBuild=true',
];
const fallbackMessage = 'java-prepush: js-gradle-slot was not found on PATH; running the Gradle wrapper directly.\n';
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-java-prepush-'));

function run(flags, { slot = true, platform = process.platform, status = 0, spawnError = false } = {}) {
  const bin = path.join(temporary, `${platform}-${slot}`);
  fs.mkdirSync(bin, { recursive: true });
  if (slot) {
    const wrapper = path.join(bin, platform === 'win32' ? 'js-gradle-slot.CMD' : 'js-gradle-slot');
    fs.writeFileSync(wrapper, '#!/bin/sh\nexit 99\n');
    fs.chmodSync(wrapper, 0o755);
  }
  const gradlew = platform === 'win32' ? 'gradlew.bat' : './gradlew';
  const expectedCommand = slot ? 'js-gradle-slot' : gradlew;
  const expectedArgs = [
    ...(slot ? [gradlew] : []), ...gradleArgs,
    ...(flags.includes('--unreferenced') ? [':modules:app-launcher:test', '--tests', '*UnreferencedCodeTest'] : []),
  ];
  const valid = flags.every((flag) => ['--dry-run', '--unreferenced'].includes(flag));
  // Real PATH lookup; replace only execution so neither Gradle nor a shared slot runs.
  const stub = `
    import assert from 'node:assert/strict';
    import childProcess from 'node:child_process';
    import { syncBuiltinESMExports } from 'node:module';
    Object.defineProperty(process, 'platform', { value: ${JSON.stringify(platform)} });
    let calls = 0;
    childProcess.spawnSync = (command, args, options) => {
      assert.equal(++calls, 1, 'one Gradle invocation');
      assert.equal(command, ${JSON.stringify(expectedCommand)});
      assert.deepEqual(args, ${JSON.stringify(expectedArgs)});
      assert.equal(options.cwd, ${JSON.stringify(root)});
      assert.equal(options.stdio, 'inherit');
      assert.equal(Boolean(options.shell), ${platform === 'win32'});
      console.log('Gradle invoked');
      return ${spawnError ? "{ error: new Error('execution unavailable'), status: null }" : `{ status: ${status} }`};
    };
    process.on('exit', () => assert.equal(calls, ${valid && !flags.includes('--dry-run') ? 1 : 0}));
    syncBuiltinESMExports();
  `;
  const result = spawnSync(process.execPath, [
    '--import', `data:text/javascript,${encodeURIComponent(stub)}`, script, ...flags,
  ], { encoding: 'utf8', env: { ...process.env, PATH: bin, PATHEXT: '.COM;.EXE;.BAT;.CMD' } });
  assert.ifError(result.error);
  return { ...result, expectedCommand, expectedArgs };
}

try {
  for (const platform of new Set([process.platform, 'win32'])) {
    for (const slot of [true, false]) {
      for (const flags of [['--dry-run'], ['--dry-run', '--dry-run'], ['--dry-run', '--unreferenced'], ['--unreferenced', '--dry-run']]) {
        const result = run(flags, { slot, platform });
        assert.equal(result.status, 0, result.stderr);
        const quote = platform === 'win32' ? '"' : "'";
        const args = result.expectedArgs.map((arg) => arg.includes('*') ? `${quote}${arg}${quote}` : arg);
        assert.equal(result.stdout, `${[result.expectedCommand, ...args].join(' ')}\n`);
        assert.equal(result.stderr, slot ? '' : fallbackMessage);
      }
      for (const flags of [['--unknown'], ['--dry-run', '--unknown'], ['compileTestJava'], ['--unreferenced=false'], ['']]) {
        const result = run(flags, { slot, platform });
        assert.equal(result.status, 1);
        assert.match(result.stderr, /Usage:/);
        assert.equal(result.stdout, '', 'invalid arguments never invoke Gradle');
      }
      for (const unreferenced of [false, true]) {
        for (const status of [0, 17]) {
          const result = run(unreferenced ? ['--unreferenced'] : [], { slot, platform, status });
          assert.equal(result.status, status, result.stderr);
          assert.equal(result.stdout, 'Gradle invoked\n');
          assert.equal(result.stderr, slot ? '' : fallbackMessage);
        }
      }
      const failed = run([], { slot, platform, spawnError: true });
      assert.equal(failed.status, 1);
      assert.equal(failed.stderr, `${slot ? '' : fallbackMessage}java-prepush: execution unavailable\n`);
    }
  }
  console.log('java-prepush: PATH-present/PATH-absent execution and dry-run, POSIX/Windows selection: PASS');
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
