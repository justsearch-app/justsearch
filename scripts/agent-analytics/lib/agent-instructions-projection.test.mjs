import assert from 'node:assert/strict';
import fs from 'node:fs';
import { CONTRACT_START, CONTRACT_END, projectContract, expectedClaude }
  from '../../docs/agent-instructions-sync.mjs';

const source = fs.readFileSync(new URL('../../../AGENTS.md', import.meta.url), 'utf8');
const adapter = `adapter prefix\n${CONTRACT_START}\nold\n${CONTRACT_END}\nadapter suffix\n`;
const projected = projectContract(adapter, source);
assert.ok(projected.startsWith('adapter prefix\n'));
assert.ok(projected.endsWith('\nadapter suffix\n'));
assert.equal(projectContract(projected, source), projected, 'projection must be idempotent');
assert.equal(projectContract(adapter.replaceAll('\n', '\r\n'), source), projected);

// Exercise a policy change outside the six invariants: this is the original drift gap.
const changed = source.replace('## Verification', 'An additional acceptance obligation.\n\n## Verification');
assert.notEqual(projectContract(adapter, changed), projected);
assert.ok(projectContract(adapter, changed).includes('An additional acceptance obligation.'));
for (const malformed of [
  adapter.replace(CONTRACT_START, ''), adapter.replace(CONTRACT_END, ''),
  adapter + CONTRACT_START, adapter + CONTRACT_END,
  `${CONTRACT_END}\n${CONTRACT_START}`,
]) assert.throws(() => projectContract(malformed, source), /marker pair/);
assert.throws(() => projectContract(adapter, ''), /required sections/);
assert.throws(() => projectContract(adapter + '<!-- generated:agent-invariants:end -->', source), /obsolete/);
const committed = fs.readFileSync(new URL('../../../CLAUDE.md', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
assert.equal(expectedClaude(), committed, 'committed delivery must match the entire source');
console.log('agent-instructions-projection: complete delivery, drift, boundary, and idempotence checks passed');
