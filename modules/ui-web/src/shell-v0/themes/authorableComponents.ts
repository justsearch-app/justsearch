// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 4/6 — the AUTHORABLE component subset.
 *
 * The closed component vocabulary (component-vocabulary.generated.ts) lists every host
 * `jf-*` element — including the trusted channel + chrome authorities. A user-authored
 * declaration must NOT be able to mount those: mounting `jf-authorization-host` would let
 * a skin forge a trusted approval dialog; mounting `jf-overlay-host` / `jf-shell` would
 * fork the chrome. So the authorable surface is the vocabulary MINUS a reserved denylist,
 * and mounting a reserved component is a validation error (rung-2 unrepresentable), not
 * merely a gate catch.
 *
 * <p>Tempdoc 571 extends this denylist from the trusted-channel components UP to the trust
 * READ-VIEW: `jf-action-ledger` (the Activity surface's Outcome face) renders the authorization
 * audit — what the agent did and which gates fired (GATED / DENIED / APPROVED). Reserving it is
 * the component-tier completion of the surface-tier `TRUST ⟹ CORE` foreclosure: a skin cannot
 * forge or occlude the trust audit any more than it can forge the live approval dialog. Same rule
 * (trust-role ⟹ host-owned, unauthorable) at two granularities.
 */

import { isComponentTag } from '../renderers/component-vocabulary.generated.js';

/**
 * Host components a user declaration may NOT mount — the trusted channel + chrome
 * authorities (anti-spoof / truth-of-chrome). Keep this minimal and explicit.
 */
export const RESERVED_COMPONENTS: ReadonlySet<string> = new Set([
  'jf-authorization-host', // the trusted approval ceremony (Move 4) — never skin-mountable
  'jf-provenance-badge', // the "non-core element" trust indicator
  'jf-overlay-host', // the reserved overlay/slot authority
  'jf-shell', // the chrome shell (kernel-owned)
  'jf-engine-recovery', // native update/recovery controls belong to desktop boot, not user layouts
  'jf-action-ledger', // tempdoc 571 — the trust read-view (Activity's Outcome face): the authorization
  // audit (gate firings GATED/DENIED/APPROVED). A skin must not forge or occlude it — the
  // component-tier completion of the surface-tier TRUST ⟹ CORE foreclosure.
]);

/**
 * Is `tag` a host component a Presentation Declaration may mount? It must be in the closed
 * vocabulary AND not reserved.
 */
export function isAuthorableComponent(tag: string): boolean {
  return isComponentTag(tag) && !RESERVED_COMPONENTS.has(tag);
}
