#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { parseArgs, buildHeadJavaOpts } = require('./dev-runner.cjs').__test;
const { splitJvmOptions } = require('./lib/engine-java-launch.cjs');

for (const value of ['0', '1024', '256', '2147483647']) {
  let opts;
  assert.doesNotThrow(() => { opts = parseArgs(['start', '--prompt-cache-mib', value]); },
    'the dev runner must accept the explicit cache override');
  const flags = splitJvmOptions(buildHeadJavaOpts({ promptCacheMiB: opts.promptCacheMiB }));
  assert.ok(flags.includes(`-Djustsearch.llm.prompt_cache_mib=${value}`));
}
assert.equal(parseArgs(['start', '--prompt-cache-mib=128']).promptCacheMiB, 128);
assert.ok(!buildHeadJavaOpts({}).includes('prompt_cache_mib'), 'Java owns the mode defaults');
const flags = splitJvmOptions(buildHeadJavaOpts({
  existingJavaOpts: '-Djustsearch.llm.prompt_cache_mib=4096 -Djustsearch.eval.mode=true',
  promptCacheMiB: 256,
}));
assert.equal(flags.filter(f => f.startsWith('-Djustsearch.llm.prompt_cache_mib=')).at(-1),
  '-Djustsearch.llm.prompt_cache_mib=256', 'explicit CLI override wins over JAVA_OPTS');
assert.ok(flags.includes('-Djustsearch.eval.mode=true'), 'evaluation mode reaches Java');
for (const value of ['-1', '', ' ', '+1', '1.5', 'NaN', 'Infinity', '2147483648']) {
  assert.throws(() => parseArgs(['start', '--prompt-cache-mib', value]),
    /non-negative integer/);
  assert.throws(() => buildHeadJavaOpts({ promptCacheMiB: value }), /non-negative integer/);
}
assert.throws(() => parseArgs(['start', '--prompt-cache-mib']), /Missing value/);
console.log('test-dev-runner-prompt-cache: OK');
