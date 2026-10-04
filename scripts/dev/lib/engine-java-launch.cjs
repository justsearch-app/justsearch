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

function engineJavaLaunch({ startScript, javaHome, javaOpts = '', uiOpts = '', platform = process.platform }) {
  // POSIX launchers exec the JVM, preserving PID and their ulimit setup.
  if (platform !== 'win32') return { command: startScript, args: [], shell: false, windowsHide: true };
  const bin = path.dirname(startScript);
  const script = fs.readFileSync(path.join(bin, 'ui.bat'), 'utf8');
  const defaults = script.match(/^set DEFAULT_JVM_OPTS=(.*)$/m);
  const entry = script.match(/-classpath "%CLASSPATH%" ([\w.$]+) %\*/);
  if (!defaults || !entry) throw new Error('Installed Engine launch script has an unsupported shape');
  return {
    command: path.join(javaHome, 'bin', platform === 'win32' ? 'java.exe' : 'java'),
    args: [...splitJvmOptions(defaults[1]), ...splitJvmOptions(javaOpts),
      ...splitJvmOptions(uiOpts), '-classpath', path.resolve(bin, '..', 'lib', '*'), entry[1]],
    shell: false,
    windowsHide: true,
  };
}
module.exports = { engineJavaLaunch, splitJvmOptions };
