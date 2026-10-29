import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./java-prepush.mjs', import.meta.url));
const root = fileURLToPath(new URL('../../', import.meta.url));
const command = 'js-gradle-slot ./gradlew compileTestJava compileIntegrationTestJava pmdAll --continue -PskipErrorProneTests=false -PskipWebBuild=true';
const args = [
  './gradlew', 'compileTestJava', 'compileIntegrationTestJava', 'pmdAll', '--continue',
  '-PskipErrorProneTests=false', '-PskipWebBuild=true',
];

function run(flags, { unreferenced = false, status = 0, spawnError = false } = {}) {
  // Replace only the child invocation: no Gradle process or shared slot is used.
  const stub = `
    import assert from 'node:assert/strict';
    import childProcess from 'node:child_process';
    import { syncBuiltinESMExports } from 'node:module';
    let calls = 0;
    childProcess.spawnSync = (command, args, options) => {
      assert.equal(++calls, 1, 'one slot and one Gradle invocation');
      assert.equal(command, 'js-gradle-slot');
      assert.deepEqual(args, ${JSON.stringify(unreferenced ? [...args, ':modules:app-launcher:test', '--tests', '*UnreferencedCodeTest'] : args)});
      assert.equal(options.cwd, ${JSON.stringify(root)});
      assert.equal(options.stdio, 'inherit');
      console.log('slot invoked');
      return ${spawnError ? "{ error: new Error('slot unavailable'), status: null }" : `{ status: ${status} }`};
    };
    syncBuiltinESMExports();
  `;
  const result = spawnSync(process.execPath, [
    '--import', `data:text/javascript,${encodeURIComponent(stub)}`, script, ...flags,
  ], { encoding: 'utf8' });
  assert.ifError(result.error);
  return result;
}

for (const flags of [['--dry-run'], ['--dry-run', '--dry-run']]) {
  const result = run(flags);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, `${command}\n`);
}
for (const flags of [['--dry-run', '--unreferenced'], ['--unreferenced', '--dry-run']]) {
  const result = run(flags);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, `${command} :modules:app-launcher:test --tests '*UnreferencedCodeTest'\n`);
}
for (const flags of [['--unknown'], ['--dry-run', '--unknown'], ['compileTestJava'], ['--unreferenced=false'], ['']]) {
  const result = run(flags);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Usage:/);
  assert.equal(result.stdout, '', 'invalid arguments never invoke the slot');
}
for (const unreferenced of [false, true]) {
  for (const status of [0, 17]) {
    const result = run(unreferenced ? ['--unreferenced'] : [], { unreferenced, status });
    assert.equal(result.status, status, result.stderr);
    assert.equal(result.stdout, 'slot invoked\n');
  }
}
const failed = run([], { spawnError: true });
assert.equal(failed.status, 1);
assert.match(failed.stderr, /java-prepush: slot unavailable/);
console.log('java-prepush: PASS');
