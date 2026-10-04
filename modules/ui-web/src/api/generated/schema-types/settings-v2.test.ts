/**
 * Tempdoc 683 (X3) — FE-side faithfulness gate for the /api/settings/v2 surface.
 *
 * The generated `settingsV2Schema` (record → JSON Schema → Zod) must validate the
 * real captured settings wire fixture. Strict (no `.loose()` fail-open): contract
 * drift fails here instead of passing silently.
 */
import { describe, it, expect } from 'vitest';

import { settingsV2Schema } from './settings-v2';
import settingsFixture from '../../__fixtures__/settings-v2-live.json';

describe('generated settingsV2Schema (683 faithfulness)', () => {
  it('validates the real captured settings wire fixture with no contract drift', () => {
    const result = settingsV2Schema.safeParse(settingsFixture);
    if (!result.success) {
      throw new Error(
        'generated settings-v2 schema rejected the real wire fixture: ' +
          JSON.stringify(result.error.issues, null, 2)
      );
    }
    expect(result.success).toBe(true);
  });
  it('accepts a receipt-only replay without inventing a current settings document', () => {
    const operationKey = '0199324a-0000-7000-8000-000000000001';
    const receipt = settingsV2Schema.parse({
      operationKey,
      state: 'COMPLETE',
      witness: { acceptedRevision: 3, lastCommittedOperationKey: operationKey },
    });
    expect(receipt.witness?.acceptedRevision).toBe(3);
    expect(receipt.witness?.lastCommittedOperationKey).toBe(operationKey);
    expect(receipt.ui).toBeUndefined();
    expect(receipt.llm).toBeUndefined();
    expect(receipt.indexPaths).toBeUndefined();
  });

  it('rejects a witness with a nonnumeric revision', () => {
    expect(settingsV2Schema.safeParse({
      witness: { acceptedRevision: '3', lastCommittedOperationKey: null },
    }).success).toBe(false);
  });

  it('requires both members of a nonnull witness while allowing an explicit initial pair', () => {
    for (const witness of [{}, { acceptedRevision: 0 }, { lastCommittedOperationKey: null }]) {
      expect(settingsV2Schema.safeParse({ witness }).success).toBe(false);
    }
    expect(settingsV2Schema.safeParse({
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null },
    }).success).toBe(true);
  });

});
