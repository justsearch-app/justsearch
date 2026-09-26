/**
 * Tempdoc 564 Phase 2 — FE-side faithfulness gate for the /api/status surface.
 *
 * StatusResponse is the second hard case (@JsonUnwrapped flattening, nullable-ref defs,
 * an anyOf nullable enum). The generated `statusResponseSchema` (record → JSON Schema → Zod)
 * must validate the real captured status wire fixture — proving the generality of the
 * pipeline beyond search.
 */
import { describe, it, expect } from 'vitest';

import { componentStateSchema, statusResponseSchema } from './status-response';
import statusFixture from '../../__fixtures__/status-response-live.json';

describe('generated statusResponseSchema (564 faithfulness)', () => {
  it('validates the real captured status wire fixture with no contract drift', () => {
    const result = statusResponseSchema.safeParse(statusFixture);
    if (!result.success) {
      throw new Error(
        'generated status schema rejected the real wire fixture: ' +
          JSON.stringify(result.error.issues, null, 2)
      );
    }
    expect(result.success).toBe(true);
  });

  it('exposes the six engine-component states as a distinct generated vocabulary', () => {
    const expected = ['ABSENT', 'STARTING', 'READY', 'RELOADING', 'FAILED', 'UNAVAILABLE'];

    expect(componentStateSchema.options).toEqual(expected);
    for (const state of expected) {
      expect(componentStateSchema.safeParse(state).success).toBe(true);
    }
  });

  it('rejects the retired aggregate spelling and unknown component states', () => {
    expect(componentStateSchema.safeParse('LIFECYCLE_STATE_READY').success).toBe(false);
    expect(componentStateSchema.safeParse('FUTURE_COMPONENT_STATE').success).toBe(false);
  });
});
