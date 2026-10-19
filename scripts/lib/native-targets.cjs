'use strict';
const fs = require('node:fs');
const path = require('node:path');

const spec = JSON.parse(fs.readFileSync(path.join(__dirname, '../../contracts/native-targets/spec.v1.json'), 'utf8'));
if (spec.schemaVersion !== 1) throw new Error('Unsupported native targets contract');

/** The current target's native names; overrides allow the shared fixtures to run on either OS. */
function nativeTarget(platform = process.platform, arch = process.arch) {
  const os = platform === 'win32' || platform.toLowerCase().startsWith('windows') ? 'windows' : platform;
  const cpu = ['x64', 'amd64'].includes(arch) ? 'x86_64' : arch;
  const target = spec.targets[`${os}-${cpu}`];
  if (!target) throw new Error(`Unsupported native target: ${os}-${cpu}`);
  return target;
}

function executableName(id) {
  const name = nativeTarget().executables[id];
  if (!name) throw new Error(`Unknown native executable: ${id}`);
  return name;
}

function libraryName(id) {
  const name = nativeTarget().libraries[id];
  if (!name) throw new Error(`Unknown native library: ${id}`);
  return name;
}

module.exports = { nativeTarget, executableName, libraryName };
