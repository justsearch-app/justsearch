import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');

// This checks source constants against documentation, without starting the UI.
// Public claims runs it for docs-only PRs as well as changes to either Java source.
test('action-ledger documentation agrees with its kinds, filter and limits', () => {
  const doc = read('docs/reference/api-contract-map.md');
  const entry = doc.split(/\n(?=- )/).find(part => part.startsWith('- `GET /api/action-ledger` —'));
  assert.ok(entry, 'GET /api/action-ledger is documented');
  const event = read('modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ActionEvent.java');
  const kinds = /enum ActionEventKind\s*\{([^}]+)\}/.exec(event)?.[1].split(',').map(kind => kind.trim()).filter(Boolean);
  assert.ok(kinds?.length, 'ActionEventKind enum was read');
  for (const kind of kinds) assert.ok(entry.includes('`' + kind.toLowerCase() + '`'), kind);
  for (const text of ['?kind=', '?limit=', 'audit/action-ledger.jsonl']) assert.ok(entry.includes(text), text);
  const controller = read('modules/ui/src/main/java/io/justsearch/ui/api/ActionLedgerController.java');
  for (const constant of ['DEFAULT_LIMIT', 'MAX_LIMIT']) {
    const value = new RegExp(`\\b${constant} = (\\d+);`).exec(controller)?.[1];
    assert.ok(value && entry.includes(value), constant);
  }
});
