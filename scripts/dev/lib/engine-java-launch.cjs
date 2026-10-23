/* The installed Gradle script remains the authority for defaults and entry point. */
'use strict';
const fs = require('node:fs');
const path = require('node:path');

// JAVA_OPTS/UI_OPTS are option lists, not shell programs. Preserve Windows backslashes and
// quoted whitespace; never perform shell expansion or execute metacharacters.
function splitJvmOptions(text = '') {
  const args = [];
  let value = '';
  let quote = null;
  let started = false;
  for (const character of text) {
    if (quote) {
      if (character === quote) quote = null;
      else value += character;
    } else if (character === '"' || character === "'") {
      quote = character;
      started = true;
    } else if (/\s/.test(character)) {
      if (started) { args.push(value); value = ''; started = false; }
    } else { value += character; started = true; }
  }
  if (quote) throw new Error('Unclosed quote in Engine JVM options');
  if (started) args.push(value);
  return args;
}

function engineJavaLaunch({ startScript, javaHome, javaOpts = '', uiOpts = '', hotReload = null, platform = process.platform,
  glibc = platform === 'linux' && Boolean(process.report.getReport().header.glibcVersionRuntime) }) {
  const bin = path.dirname(startScript);
  // Gradle generates both launchers on both OSes; the batch defaults are already argv quoted.
  const script = fs.readFileSync(path.join(bin, 'ui.bat'), 'utf8');
  const defaults = script.match(/^set DEFAULT_JVM_OPTS=(.*)$/m);
  const entry = script.match(/-classpath "%CLASSPATH%" ([\w.$]+) %\*/);
  if (!defaults || !entry) throw new Error('Installed Engine launch script has an unsupported shape');
  const jars = path.resolve(bin, '..', 'lib', '*');
  const classpath = hotReload?.enabled && hotReload.classesDir
    ? `${hotReload.classesDir}${platform === 'win32' ? ';' : ':'}${jars}` : jars;
  const unix = platform === 'win32' ? null : fs.readFileSync(startScript, 'utf8');
  const unixDefaults = unix?.match(/^DEFAULT_JVM_OPTS=(.*)$/m);
  if (unix && !unixDefaults) throw new Error('Installed Engine launch script has an unsupported shape');
  const defaultArgs = unix ? splitJvmOptions(splitJvmOptions(unixDefaults[1])[0] ?? '') : splitJvmOptions(defaults[1]);
  const nativeHeapArgs = platform === 'linux' && glibc ? ['-XX:TrimNativeHeapInterval=30000'] : [];
  const args = [...defaultArgs, ...nativeHeapArgs, ...splitJvmOptions(javaOpts),
    ...splitJvmOptions(uiOpts), '-classpath', classpath, entry[1]];
  if (platform === 'win32') return {
    command: path.join(javaHome, 'bin', 'java.exe'), args, shell: false, windowsHide: true,
  };
  // Run the installed launcher's unchanged setup, including its ulimit policy, with $0 set to
  // its real path. Replace only Gradle's argv assembly: shell expansion never sees our JVM argv.
  const assembly = unix.search(/^set -- /m);
  if (assembly < 0 || !unix.slice(0, assembly).includes('JAVACMD=')) {
    throw new Error('Installed Engine launch script has an unsupported shape');
  }
  return { command: '/bin/sh', args: ['-c', `${unix.slice(0, assembly)}\nexec "$JAVACMD" "$@"\n`, startScript, ...args],
    shell: false, windowsHide: true };
}
module.exports = { engineJavaLaunch, splitJvmOptions };
