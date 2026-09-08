#!/usr/bin/env node
/** Project the complete shared AGENTS.md contract into Claude's adapter. */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const CONTRACT_START = '<!-- generated:agent-contract:start — source: AGENTS.md; run: node scripts/docs/agent-instructions-sync.mjs -->';
export const CONTRACT_END = '<!-- generated:agent-contract:end -->';

export function projectContract(current, source) {
  const claude = current.replace(/\r\n/g, '\n');
  const agents = source.replace(/\r\n/g, '\n');
  if (!/^# JustSearch agent instructions$/m.test(agents)
      || !/^## Hard invariants$/m.test(agents) || !/^## Verification$/m.test(agents)) {
    throw new Error('AGENTS.md shared contract is missing required sections');
  }
  const starts = claude.split(CONTRACT_START);
  const ends = claude.split(CONTRACT_END);
  if (starts.length !== 2 || ends.length !== 2
      || claude.indexOf(CONTRACT_START) >= claude.indexOf(CONTRACT_END)) {
    throw new Error('CLAUDE.md requires exactly one ordered shared-contract marker pair');
  }
  if (claude.includes('generated:agent-invariants:')) {
    throw new Error('obsolete partial invariant projection must be removed');
  }
  // The first line is a maintainer budget comment, not part of the policy.
  const body = agents.slice(agents.indexOf('# JustSearch agent instructions'))
    .replace(/^# JustSearch agent instructions/, '## Shared project contract').trimEnd();
  return starts[0] + CONTRACT_START + '\n' + body + '\n' + CONTRACT_END + ends[1];
}

export function expectedClaude() {
  return projectContract(fs.readFileSync(path.join(ROOT, 'CLAUDE.md'), 'utf8'),
    fs.readFileSync(path.join(ROOT, 'AGENTS.md'), 'utf8'));
}

function main() {
  const expected = expectedClaude();
  const file = path.join(ROOT, 'CLAUDE.md');
  if (process.argv.includes('--check')) {
    if (fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') !== expected) {
      throw new Error('CLAUDE.md shared contract drifted; run node scripts/docs/agent-instructions-sync.mjs');
    }
    console.log('agent-instructions-sync --check: OK (complete shared contract)');
  } else {
    fs.writeFileSync(file, expected, 'utf8');
    console.log('agent-instructions-sync: projected complete shared contract');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
