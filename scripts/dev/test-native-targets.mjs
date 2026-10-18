import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { nativeTarget, executableName, libraryName } from '../lib/native-targets.cjs';
import { toolInvocation, runToolSync } from '../lib/tool-invoker.cjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const spec = JSON.parse(fs.readFileSync(path.join(root, 'contracts/native-targets/spec.v1.json'), 'utf8'));
const golden = JSON.parse(fs.readFileSync(path.join(root, 'contracts/native-targets/windows.pre-port.golden.json'), 'utf8'));
assert.deepEqual(nativeTarget('win32', 'x64'), golden, 'Windows literals pinned independently to 37e0957c9');
assert.equal(executableName('curl'), process.platform === 'win32' ? 'curl.exe' : 'curl');
assert.equal(libraryName('cuda'), process.platform === 'win32' ? 'nvcuda.dll' : 'libcuda.so.1');
assert.throws(() => nativeTarget('linux', 'arm64'), /Unsupported native target/);
assert.throws(() => executableName('unknown'), /Unknown native executable/);
const gradleArgs = [':modules:configuration:test', '--tests', '*NativeTargetsContractTest',
  ':modules:app-inference:test', '--tests', '*InferenceLinuxResolutionTest',
  ':modules:app-services:test', '--tests', '*RuntimeNativeResolutionTest', '--tests', '*NativeExecutableModeTest', '--tests', '*DownloadLinuxCommandTest',
  '-PskipWebBuild=true', '--no-daemon'];
const result = runToolSync('gradle', gradleArgs, { repoRoot: root, cwd: root, stdio: 'inherit' });
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
const java = JSON.parse(fs.readFileSync(path.join(root, 'modules/configuration/build/native-targets-fixtures.json'), 'utf8'));
for (const [target, expected] of Object.entries(spec.targets)) {
  assert.deepEqual(java[target], expected, `Java/Node ${target}`);
}
const rust = spawnSync('cargo', ['test', '--locked', '--lib', 'native_targets::tests::shared_fixtures_agree'], { cwd: path.join(root, 'modules/shell/src-tauri'), stdio: 'inherit' });
if (rust.error) throw rust.error;
if (rust.status !== 0) process.exit(rust.status ?? 1);
console.log('PASS: Java/Node/Rust shared native fixtures; runtime override, invalid override, default, explicit variant, executable modes.');
