import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
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
  assert.deepEqual(engineJavaLaunch({ startScript: '/installed/bin/ui', platform: 'linux' }),
    { command: '/installed/bin/ui', args: [], shell: false, windowsHide: true });
  fs.writeFileSync(script, 'unknown launch format');
  assert.throws(() => engineJavaLaunch({ startScript: script, javaHome: '.', platform: 'win32' }), /unsupported shape/);
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
console.log('PASS engine Java launch: direct child, installed defaults, quoted options, fail closed');
