// Execution-policy adapter for run-evidence.mjs; no test results or assertions are changed.
// Use with node --require <this file> scripts/governance/gates/test-intent/run-evidence.mjs.
const cp = require('node:child_process');
const fs = require('node:fs');
const { syncBuiltinESMExports } = require('node:module');
const original = cp.spawnSync;
const clones = new Set();
// Generated only in the caller's temporary directory; never loaded by a product build.
const javaPolicy = String.raw`// Test-only execution policy: isolate temporary files and Engine homes without JAVA_TOOL_OPTIONS.
def requestedRoot = System.getenv('TOKEN_TEST_TEMP_ROOT')
if (!requestedRoot) throw new GradleException('TOKEN_TEST_TEMP_ROOT must name the evidence sandbox')
allprojects { p ->
  def sandbox = new File(requestedRoot,
      Integer.toHexString(p.rootDir.absolutePath.hashCode()) + '/' + p.path.replace(':', '_'))
  tasks.withType(Test).configureEach {
    def taskRoot = new File(sandbox, name)
    systemProperty('java.io.tmpdir', new File(taskRoot, 'temp').absolutePath)
    environment('JUSTSEARCH_HOME', new File(taskRoot, 'home').absolutePath)
    environment('JUSTSEARCH_DATA_DIR', new File(taskRoot, 'data').absolutePath)
    doFirst {
      ['temp', 'home', 'data'].each { new File(taskRoot, it).mkdirs() }
    }
  }
}
`;

cp.spawnSync = function (command, args, options) {
  if (typeof command === 'string' && command.startsWith('./gradlew ')) {
    // The complete producer (candidate, report extraction and before run) holds one slot.
    const heldLock = fs.readlinkSync(`/proc/${process.ppid}/fd/9`);
    if (!heldLock.startsWith('/tmp/js-linux-port/gradle.lock')) {
      throw new Error('Run the complete evidence producer under js-gradle-slot');
    }
    const init = require('node:path').join(process.env.TMPDIR, 'token-bootstrap-java-policy.init.gradle');
    fs.writeFileSync(init, javaPolicy);
    const quotedInit = "'" + init.replaceAll("'", "'\\''") + "'";
    const actual = `${command} --init-script ${quotedInit} --max-workers=6`;
    const env = { ...args.env };
    delete env.JAVA_TOOL_OPTIONS;
    env.JAVA_OPTS = `${env.JAVA_OPTS ?? ''} -Djava.io.tmpdir=${env.TMPDIR}`.trim();
    process.stderr.write(`[execution-policy] existing whole-producer slot: ${actual}\n`);
    return original(actual, { ...args, env });
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
