'use strict';
// The one place that knows which directories make a checkout's Python packages importable.
// Every caller that sets PYTHONPATH for jseval or jsharness goes through this (shell callers
// through python-path.sh, which runs the CLI form below), so the list cannot drift per caller.
const path = require('node:path');

/** PYTHONPATH value naming the jseval and jsharness packages of the checkout at `root`. */
function pythonPath(root) {
  const resolved = path.resolve(root);
  return [path.join(resolved, 'scripts', 'jseval'), path.join(resolved, 'scripts', 'jsharness')]
    .join(path.delimiter);
}

module.exports = { pythonPath };

// `node scripts/lib/python-path.cjs <checkout-root>` prints the value, for python-path.sh.
if (require.main === module) {
  if (process.argv.length !== 3) {
    process.stderr.write('usage: node scripts/lib/python-path.cjs <checkout-root>\n');
    process.exit(2);
  }
  process.stdout.write(`${pythonPath(process.argv[2])}\n`);
}
