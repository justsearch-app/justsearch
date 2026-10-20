// Harness-only observation at Node's process-spawn boundary, including the Windows job host.
// No launch arguments, environment values or child behavior are changed.
const fs = require('node:fs');
const { ChildProcess } = require('node:child_process');
const original = ChildProcess.prototype.spawn;
ChildProcess.prototype.spawn = function (options) {
  const incarnation = options.envPairs?.find(value => value.startsWith('JUSTSEARCH_ENGINE_INCARNATION='))?.split('=').slice(1).join('=');
  const target = process.env.JUSTSEARCH_CONFORMANCE_TIMINGS;
  if (target && incarnation) {
    try {
      fs.appendFileSync(target, JSON.stringify({ phase: 'spawnRequested', atMs: Date.now(), incarnation,
        source: 'Node ChildProcess.spawn (Engine or its job host)' }) + '\n');
    } catch (err) { process.stderr.write(`[timing warning] ${err.message}\n`); }
  }
  return original.call(this, options);
};
