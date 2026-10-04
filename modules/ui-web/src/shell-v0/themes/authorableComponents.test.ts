import { describe, it, expect } from 'vitest';
import { RESERVED_COMPONENTS, isAuthorableComponent } from './authorableComponents.js';
import { isComponentTag } from '../renderers/component-vocabulary.generated.js';

describe('authorableComponents — the reserved channel (569 Move 4 + tempdoc 571)', () => {
  it('keeps native recovery controls outside user-authored presentations', () => {
    expect(isComponentTag('jf-engine-recovery')).toBe(true);
    expect(isAuthorableComponent('jf-engine-recovery')).toBe(false);
  });

  it('reserves the trusted-channel + chrome authorities (569 Move 4)', () => {
    for (const tag of [
      'jf-authorization-host',
      'jf-provenance-badge',
      'jf-overlay-host',
      'jf-shell',
    ]) {
      expect(RESERVED_COMPONENTS.has(tag)).toBe(true);
      expect(isAuthorableComponent(tag)).toBe(false);
    }
  });

  it('tempdoc 571 — reserves the trust read-view jf-action-ledger (Activity Outcome face)', () => {
    // jf-action-ledger IS a real component in the closed vocabulary — so the reservation is
    // load-bearing: without it, a user-authored skin could mount the authorization audit and
    // forge/occlude what the agent did + which gates fired.
    expect(isComponentTag('jf-action-ledger')).toBe(true);
    // Reserving it makes it unauthorable — the component-tier completion of the surface-tier
    // TRUST ⟹ CORE foreclosure (same rule at two granularities).
    expect(RESERVED_COMPONENTS.has('jf-action-ledger')).toBe(true);
    expect(isAuthorableComponent('jf-action-ledger')).toBe(false);
  });
});
