'use strict';
const { createHash } = require('node:crypto');
// One bounded identity for record files, archive files and Git refs. The original folder name
// stays in the record. Existing safe IDs retain their spelling; the versioned namespace could
// not have been produced by the old grammar, so it cannot collide with a legacy safe ID.
function worktreeResourceId(resource) {
  if (typeof resource !== 'string' || !resource || resource === '.' || resource === '..'
      || /[/\0]/.test(resource) || (process.platform === 'win32' && resource.includes('\\'))) throw new Error(`unsafe recordId ${JSON.stringify(resource)}: expected a folder name`);
  if (/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(resource) && !resource.includes('..')
      && !resource.endsWith('.') && !resource.endsWith('.lock')
      && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(resource)) return resource;
  return 'w1%' + createHash('sha256').update(resource, 'utf8').digest('hex');
}
module.exports = { worktreeResourceId };
