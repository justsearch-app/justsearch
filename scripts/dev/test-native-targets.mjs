import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { nativeTarget, executableName, libraryName } from '../lib/native-targets.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const spec = JSON.parse(fs.readFileSync(path.join(root, 'contracts/native-targets/spec.v1.json'), 'utf8'));
for (const fixture of spec.fixtures) {
  const [os, arch] = fixture.target.split('-');
  const { target, ...expected } = fixture;
  assert.deepEqual(nativeTarget(os, arch), expected);
}
assert.equal(executableName('curl'), process.platform === 'win32' ? 'curl.exe' : 'curl');
assert.equal(libraryName('cuda'), process.platform === 'win32' ? 'nvcuda.dll' : 'libcuda.so.1');
assert.throws(() => nativeTarget('linux', 'arm64'), /Unsupported native target/);
assert.throws(() => executableName('unknown'), /Unknown native executable/);
const gradleArgs = [':modules:configuration:test', '--tests', '*NativeTargetsContractTest',
  ':modules:app-inference:test', '--tests', '*InferenceLinuxResolutionTest',
  ':modules:app-services:test', '--tests', '*RuntimeNativeResolutionTest', '--tests', '*NativeExecutableModeTest', '--tests', '*DownloadLinuxCommandTest',
  '-PskipWebBuild=true'];
// Serialize against the shared campaign when running in that environment; ordinary checkouts
// need only their Gradle wrapper.
const campaignLock = '/tmp/js-linux-port/gradle.lock';
const sharedCampaign = fs.existsSync(path.dirname(campaignLock));
const result = process.platform === 'win32'
  ? spawnSync('cmd.exe', ['/d', '/s', '/c', 'gradlew.bat', ...gradleArgs], { cwd: root, stdio: 'inherit' })
  : sharedCampaign
    ? spawnSync('flock', [campaignLock, './gradlew', ...gradleArgs], { cwd: root, stdio: 'inherit' })
    : spawnSync('./gradlew', gradleArgs, { cwd: root, stdio: 'inherit' });
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
const java = JSON.parse(fs.readFileSync(path.join(root, 'modules/configuration/build/native-targets-fixtures.json'), 'utf8'));
for (const fixture of spec.fixtures) {
  const [os, arch] = fixture.target.split('-');
  assert.deepEqual(java[fixture.target], nativeTarget(os, arch), `Java/Node ${fixture.target}`);
}
console.log('PASS: Java/Node shared native fixtures; runtime override, invalid override, default, explicit variant, executable modes.');
