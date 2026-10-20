// Execution-policy adapter for run-evidence.mjs; no test results or assertions are changed.
// Use with node --require <this file> scripts/governance/gates/test-intent/run-evidence.mjs.
const cp = require('node:child_process');
const fs = require('node:fs');
const { syncBuiltinESMExports } = require('node:module');
const original = cp.spawnSync;
const clones = new Set();
cp.spawnSync = function (command, args, options) {
  if (typeof command === 'string' && command.startsWith('./gradlew ')) {
    const actual = `js-gradle-slot ${command}`;
    process.stderr.write(`[execution-policy] ${actual}\n`);
    const env = { ...args.env };
    env.JAVA_TOOL_OPTIONS = `${env.JAVA_TOOL_OPTIONS ?? ''} -Djava.io.tmpdir=${env.TMPDIR}`.trim();
    process.stderr.write(`[execution-policy] JAVA_TOOL_OPTIONS=${env.JAVA_TOOL_OPTIONS}\n`);
    const result = original(actual, { ...args, env });
    if (result.status === 75) {
      process.stderr.write(`${result.stdout ?? ''}${result.stderr ?? ''}\nGradle slot exited 75; stopping without retry.\n`);
      process.exit(75);
    }
    return result;
  }
  if (typeof command === 'string' && command.startsWith('npx vitest run ')) {
    // Copying node_modules dereferences .bin symlinks; invoke the same installed CLI
    // at its package location so its relative imports remain valid in isolated clones.
    const actual = `${command.replace(/^npx vitest run /, "node node_modules/vitest/vitest.mjs run ")} --maxWorkers=4`;
    process.stderr.write(`[execution-policy] ${actual}\n`);
    const result = original(actual, args);
    const report = /--outputFile=("[^"\n]+")/.exec(command);
    if (process.env.TOKEN_EVIDENCE_RAW && report) {
      const file = JSON.parse(report[1]);
      if (fs.existsSync(file)) fs.copyFileSync(file, `${process.env.TOKEN_EVIDENCE_RAW}-${clones.has(args.cwd.replace(/\/modules\/ui-web$/, "")) ? "before" : "candidate"}.json`);
    }
    return result;
  }
  if (command === 'git' && Array.isArray(args) && args[2] === 'worktree') {
    if (args[3] === 'add') {
      const [, , , , , destination, revision] = args;
      process.stderr.write(`[execution-policy] shared clone ${destination}; detached checkout ${revision}\n`);
      const clone = original('git', ['clone', '--quiet', '--shared', '--no-checkout', options.cwd, destination], options);
      if (clone.status !== 0) return clone;
      clones.add(destination);
      return original('git', ['-C', destination, 'checkout', '--quiet', '--detach', revision], options);
    }
    if (args[3] === 'remove') {
      const destination = args[5];
      if (!clones.delete(destination)) throw new Error(`refusing to remove an unowned clone: ${destination}`);
      fs.rmSync(destination, { recursive: true });
      return { status: 0, signal: null, stdout: '', stderr: '' };
    }
  }
  return original(command, args, options);
};
syncBuiltinESMExports();
