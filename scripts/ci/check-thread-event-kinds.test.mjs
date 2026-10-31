/** Self-test for the generated event-kind tuple's client consumption guard. */
import assert from 'node:assert/strict';
import { checkClientConsumption } from './check-thread-event-kinds.mjs';

const client = `
import { INTERACTION_EVENT_KINDS as KNOWN_EVENT_KINDS } from '../../api/generated/registry-enums.generated.js';
const strictKind = z.enum(KNOWN_EVENT_KINDS);
const knownKinds = new Set(KNOWN_EVENT_KINDS);
`;

assert.deepEqual(checkClientConsumption(client), []);
for (const [label, source] of [
  ['generated import removed', client.replace(/^import .*;$/m, '')],
  ['strict parser uses a local list', client.replace('z.enum(KNOWN_EVENT_KINDS)', "z.enum(['MESSAGE'])")],
  ['known-kind rejection uses a local list', client.replace('new Set(KNOWN_EVENT_KINDS)', "new Set(['MESSAGE'])")],
  ['consumption exists only in comments', `/* ${client} */`],
]) {
  assert.equal(checkClientConsumption(source).length, 1, label);
}
console.log('check-thread-event-kinds.test: OK (5 assertions)');
