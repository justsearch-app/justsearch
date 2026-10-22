import './lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { engineJavaLaunch } = require('./lib/engine-java-launch.cjs');
const flag = '-XX:TrimNativeHeapInterval=30000';
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'engine-trim-'));
try {
  const bin = path.join(temp, 'bin');
  fs.mkdirSync(bin);
  const unix = path.join(bin, 'ui');
  fs.writeFileSync(unix, '#!/bin/sh\nJAVACMD="$JAVA_HOME/bin/java"\nDEFAULT_JVM_OPTS=\'"-Dtest=true"\'\nset -- "$@"\n');
  fs.writeFileSync(path.join(bin, 'ui.bat'), 'set DEFAULT_JVM_OPTS="-Dtest=true"\n-classpath "%CLASSPATH%" io.justsearch.ui.HeadlessApp %*\n');
  for (const [platform, glibc, expected] of [['linux', false, false], ['win32', true, false], ['darwin', true, false], ['linux', true, true]]) {
    const launch = engineJavaLaunch({ startScript: platform === 'win32' ? path.join(bin, 'ui.bat') : unix,
      javaHome: '/jdk', platform, glibc });
    assert.equal(launch.args.includes(flag), expected, `${platform} glibc=${glibc}`);
    assert.equal(launch.env, undefined, 'the launcher does not inject an option environment');
    console.log(`PASS dev ${platform}/glibc=${glibc}: trim ${expected ? 'present' : 'absent'}`);
  }
  const rust = fs.readFileSync('modules/shell/src-tauri/src/lib.rs', 'utf8');
  assert.match(rust, /#\[cfg\(all\(target_os = "linux", target_env = "gnu"\)\)\]\s*cmd\.arg\("-XX:TrimNativeHeapInterval=30000"\)/);
  const gradle = fs.readFileSync('modules/ui/build.gradle.kts', 'utf8');
  assert.match(gradle, /OperatingSystem\.current\(\)\.isLinux[\s\S]*getconf GNU_LIBC_VERSION/);
  assert.match(gradle, /"run", "runHeadless", "runHeadlessEval", "runHeadlessWithProfiling"[\s\S]*jvmArgs\(engineNativeHeapJvmArgs\)/);
  assert.match(gradle, /javaBin\.absolutePath,\s*\*engineNativeHeapJvmArgs\.toTypedArray\(\)/);
  assert.match(gradle, /uname -s[\s\S]*getconf GNU_LIBC_VERSION[\s\S]*set --/);
  assert.doesNotMatch(gradle, /applicationDefaultJvmArgs = listOf\([^)]*TrimNativeHeapInterval/);
  if (process.argv.includes('--generated')) {
    const generated = path.resolve('modules/ui/build/scripts/ui');
    assert.ok(fs.existsSync(generated), 'generate startScripts before this check');
    assert.ok(!fs.readFileSync(generated + '.bat', 'utf8').includes(flag), 'Windows generated script must omit trim');
    console.log('PASS generated Windows target: trim absent');
    const jdk = path.join(temp, 'jdk');
    const tools = path.join(temp, 'tools');
    fs.mkdirSync(path.join(jdk, 'bin'), { recursive: true });
    fs.mkdirSync(tools);
    fs.writeFileSync(path.join(jdk, 'bin/java'), '#!/bin/sh\nprintf "%s\\n" "$@"\nenv\n', { mode: 0o755 });
    for (const [system, libc, expected] of [['Linux', 'glibc', true], ['Linux', 'musl', false], ['Darwin', 'glibc', false]]) {
      fs.writeFileSync(path.join(tools, 'uname'), `#!/bin/sh\necho '${system}'\n`, { mode: 0o755 });
      fs.writeFileSync(path.join(tools, 'getconf'), `#!/bin/sh\nexit ${libc === 'glibc' ? 0 : 1}\n`, { mode: 0o755 });
      const result = spawnSync('/bin/sh', [generated], { encoding: 'utf8', env: { ...process.env,
        JAVA_HOME: jdk, PATH: tools + path.delimiter + process.env.PATH, DEFAULT_JVM_OPTS: 'inherited-marker' } });
      assert.equal(result.status, 0, result.stderr);
      const lines = result.stdout.trim().split('\n');
      assert.equal(lines.includes(flag), expected, `generated ${system}/${libc}: ${result.stdout}`);
      console.log(`PASS executed generated ${system}/${libc}: trim ${expected ? 'present' : 'absent'}`);
      assert.ok(!lines.some(line => /^[A-Z_]+=.*TrimNativeHeapInterval/.test(line)), 'no environment variable carries trim');
    }
  }
  // Run a real JVM and its real Java child: the option belongs to the parent argv only.
  const source = path.join(temp, 'TrimChild.java');
  fs.writeFileSync(source, `import java.lang.management.ManagementFactory;
class TrimChild {
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      if (!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("${flag}")) throw new AssertionError("missing parent argv");
      var p = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "${source}", "child").inheritIO().start();
      if (p.waitFor() != 0) throw new AssertionError("child failed");
      String llama = System.getenv("JUSTSEARCH_TEST_LLAMA_SERVER");
      if (llama != null) {
        var command = new ProcessBuilder(llama, "--version").redirectErrorStream(true);
        if (command.environment().values().stream().anyMatch(v -> v.contains("TrimNativeHeapInterval"))) throw new AssertionError("llama environment carries trim");
        var nativeChild = command.start();
        String output = new String(nativeChild.getInputStream().readAllBytes());
        if (nativeChild.waitFor() != 0) throw new AssertionError("llama-server --version failed: " + output);
        System.out.println("PASS actual llama-server --version: no trim in child launch environment");
      }
    } else {
      if (ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(s -> s.contains("TrimNativeHeapInterval"))) throw new AssertionError("child inherited trim");
      for (String key : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"})
        if (System.getenv().getOrDefault(key, "").contains("TrimNativeHeapInterval")) throw new AssertionError(key);
      System.out.println("PASS real Java child: no inherited trim option");
    }
  }
}`);
  if (process.platform === 'linux' && process.report.getReport().header.glibcVersionRuntime) {
    const llamaIndex = process.argv.indexOf('--llama-server');
    const env = { ...process.env };
    if (llamaIndex >= 0) {
      assert.ok(process.argv[llamaIndex + 1], '--llama-server needs an executable path');
      env.JUSTSEARCH_TEST_LLAMA_SERVER = path.resolve(process.argv[llamaIndex + 1]);
    }
    const result = spawnSync('java', [flag, source], { encoding: 'utf8', env });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    process.stdout.write(result.stdout);
  }
} finally { fs.rmSync(temp, { recursive: true, force: true }); }
console.log('PASS Engine native heap trim: platform argv and child environment');
