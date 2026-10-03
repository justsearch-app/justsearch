import { describe, it, expect } from 'vitest';
import { LIFECYCLE } from './lifecycleState';
import { statusResponseSchema } from './generated/schema-types/status-response';

/**
 * 548 S5 (§4.1): pins the FE lifecycle-state constants to the exact wire strings the backend
 * emits via `LifecycleState.wireName()`. If the wire vocabulary ever changes (or the enum is
 * renamed), these break here rather than silently mis-comparing `/api/status` states in
 * StatusDeck / HealthSurface.
 *
 * Tempdoc 683 (FE proto teardown): the authority cross-check is now the generated
 * record→JSON-Schema→Zod enum (`statusResponseSchema`) — the same projection the
 * runtime `lifecycleState.ts` derives its types from — not the retired `status_pb` proto enum.
 */
describe('LIFECYCLE constants (derived from the generated wire-enum authority)', () => {
  it('equal the short wire strings the backend serializes', () => {
    expect(LIFECYCLE.STARTING).toBe('LIFECYCLE_STATE_STARTING');
    expect(LIFECYCLE.READY).toBe('LIFECYCLE_STATE_READY');
    expect(LIFECYCLE.DEGRADED).toBe('LIFECYCLE_STATE_DEGRADED');
    expect(LIFECYCLE.ERROR).toBe('LIFECYCLE_STATE_ERROR');
    expect(LIFECYCLE.STOPPING).toBe('LIFECYCLE_STATE_STOPPING');
    expect(LIFECYCLE.STOPPED).toBe('LIFECYCLE_STATE_STOPPED');
  });

  it('are members of the generated wire-enum runtime authority', () => {
    const wireEnumValues = statusResponseSchema.shape.lifecycle.unwrap().shape.state.unwrap().unwrap().options;
    for (const constant of Object.values(LIFECYCLE)) {
      expect(wireEnumValues).toContain(constant);
    }
  });
});
