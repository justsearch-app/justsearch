'use strict';
const path = require('node:path');
function pathKey(value, platform = process.platform) {
  const api = platform === 'win32' ? path.win32 : path.posix;
  let resolved = api.resolve(value);
  if (platform === 'win32') resolved = resolved.replace(/\\/g, '/').toLowerCase();
  return resolved.replace(/\/+$/, '') || '/';
}
const samePath = (a, b, platform = process.platform) => pathKey(a, platform) === pathKey(b, platform);
module.exports = { pathKey, samePath };
