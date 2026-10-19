import './lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { engineJavaLaunch, splitJvmOptions } = require('./lib/engine-java-launch.cjs');
assert.deepEqual(splitJvmOptions('-Da="a b" -Db=C:\\data\\index -Dc=\'c d\''),
  ['-Da=a b', '-Db=C:\\data\\index', '-Dc=c d']);
assert.deepEqual(splitJvmOptions('-Dliteral=$(echo nope);`x`'), ['-Dliteral=$(echo', 'nope);`x`']);
assert.throws(() => splitJvmOptions('-Da="unfinished'), /Unclosed quote/);
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'engine launch '));
try {
  const bin = path.join(directory, 'bin');
  fs.mkdirSync(bin);
  const script = path.join(bin, 'ui.bat');
  fs.writeFileSync(script, 'set DEFAULT_JVM_OPTS="--enable-native-access=ALL-UNNAMED"\n'
    + 'endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %UI_OPTS% '
    + '-classpath "%CLASSPATH%" io.justsearch.ui.HeadlessApp %*\n');
  const launch = engineJavaLaunch({ startScript: script, javaHome: 'C:/Java Home',
    javaOpts: '-Dpath="C:\\data space\\file" -Xmx2g', uiOpts: '-Dlast=true', platform: 'win32' });
  assert.equal(launch.shell, false);
  assert.equal(launch.windowsHide, true);
  assert.equal(launch.command, path.join('C:/Java Home', 'bin', 'java.exe'));
  assert.deepEqual(launch.args, ['--enable-native-access=ALL-UNNAMED',
    '-Dpath=C:\\data space\\file', '-Xmx2g', '-Dlast=true', '-classpath',
    path.join(directory, 'lib', '*'), 'io.justsearch.ui.HeadlessApp']);
  const unixScript = path.join(bin, 'ui');
  const setup = '#!/bin/sh\nAPP_HOME=$(cd "${0%/*}/.." && pwd)\n'
    + 'JAVACMD="$JAVA_HOME/bin/java"\nulimit -n 512\n'
    + `DEFAULT_JVM_OPTS='"--enable-native-access=ALL-UNNAMED"'\n`;
  fs.writeFileSync(unixScript, setup + 'set -- \\\n -classpath "$CLASSPATH" io.justsearch.ui.HeadlessApp "$@"\nexec "$JAVACMD" "$@"\n');
  const classesDir = path.join(directory, 'modules/worker-services/build/classes/java/main');
  fs.mkdirSync(classesDir, { recursive: true });
  fs.mkdirSync(path.join(directory, 'lib'), { recursive: true });
  const classFile = path.join(classesDir, 'Reload.class');
  const jar = path.join(directory, 'lib/worker-services-test.jar');
  fs.writeFileSync(classFile, 'new class'); fs.writeFileSync(jar, 'older installed jar');
  fs.utimesSync(jar, new Date(100000), new Date(100000));
  fs.utimesSync(classFile, new Date(200000), new Date(200000));
  const { assessHotReloadClasspath } = require('./dev-runner.cjs').__test;
  const stale = assessHotReloadClasspath({ classesDir, libDir: path.join(directory, 'lib'), buildRan: false });
  assert.equal(stale.ok, false);
  assert.equal(stale.verdict, 'CLASSES_NEWER_THAN_DIST');
  for (const platform of ['linux', 'win32']) {
    const options = { startScript: platform === 'linux' ? unixScript : script, javaHome: 'C:/Java Home',
      javaOpts: '-Dpath="data space/file" -Xmx2g', uiOpts: '-Dlast=true', platform,
      hotReload: { enabled: true, classesDir } };
    const composed = engineJavaLaunch(options);
    const argv = platform === 'linux' ? composed.args.slice(3) : composed.args;
    assert.deepEqual(argv, ['--enable-native-access=ALL-UNNAMED', '-Dpath=data space/file', '-Xmx2g',
      '-Dlast=true', '-classpath', `${classesDir}${platform === 'win32' ? ';' : ':'}${path.join(directory, 'lib', '*')}`,
      'io.justsearch.ui.HeadlessApp']);
    const refused = engineJavaLaunch({ ...options, hotReload: { enabled: false, classesDir: null,
      classpathVerdict: stale.verdict } });
    assert.equal(refused.args[refused.args.indexOf('-classpath') + 1], path.join(directory, 'lib', '*'));
  }
  if (process.platform !== 'win32') {
    const javaHome = path.join(directory, 'jdk');
    fs.mkdirSync(path.join(javaHome, 'bin'), { recursive: true });
    fs.writeFileSync(path.join(javaHome, 'bin/java'), '#!/bin/sh\nulimit -n\nprintf "%s\\n" "$@"\n', { mode: 0o755 });
    const linux = engineJavaLaunch({ startScript: unixScript, javaHome, javaOpts: '-Dliteral="$(touch forbidden);&"', platform: 'linux' });
    assert.ok(linux.args[1].startsWith(setup), 'installed setup and ulimits are retained verbatim');
    const result = spawnSync(linux.command, linux.args, { env: { ...process.env, JAVA_HOME: javaHome }, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.stdout.trim().split('\n'), ['512', '--enable-native-access=ALL-UNNAMED',
      '-Dliteral=$(touch forbidden);&', '-classpath', path.join(directory, 'lib', '*'), 'io.justsearch.ui.HeadlessApp']);
  }
  fs.writeFileSync(script, 'unknown launch format');
  assert.throws(() => engineJavaLaunch({ startScript: script, javaHome: '.', platform: 'win32' }), /unsupported shape/);
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
console.log('PASS engine Java launch: direct child, installed defaults, quoted options, fail closed');
