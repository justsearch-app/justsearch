// SPDX-License-Identifier: Apache-2.0
/**
 * memberTabIntent — tempdoc 571 §11 / 578: the "open this member's tab" signal (pub/sub + one-shot).
 *
 * When a member deep-link (e.g. core.browse-surface) is redirected to its host (core.library-surface),
 * the host's `<jf-surface-tabs>` should open the requested member's tab rather than the default first
 * tab. The mounted host element does not receive the redirect's `originalId` directly, so this module
 * is the narrow hand-off. It serves BOTH mount timings:
 *
 *   - **host not yet mounted** (navigating from elsewhere): the request is stored as a one-shot
 *     `pending[hostId]` that the host drains in `connectedCallback` (`takeMemberTabIntent`).
 *   - **host already mounted** (you're on the host and follow a member deep-link → the redirect lands
 *     on the already-active host, so no re-mount fires): subscribers are notified synchronously, and
 *     the live host switches its tab (`subscribeMemberTab`). This is the post-review fix for the
 *     "already-active host doesn't switch tabs" bug.
 *
 * The publish (`requestMemberTab`) is called from the navigation-apply step (the intentRouter redirect
 * hook wired in Shell), NOT from the surface resolver — keeping `resolveSurface` pure (no stale intent
 * from a speculative resolution).
 */
/** A listener returns `true` if it HANDLED the request (its host id matched and it switched tab). */
type MemberTabListener = (hostId: string, memberId: string, anchor?: string) => boolean | void;

const pending = new Map<string, { memberId: string; anchor?: string }>();
const listeners = new Set<MemberTabListener>();

/**
 * Request that `memberId` become the active tab of `hostId`. Notifies live subscribers first; only if
 * NO live listener handled it (i.e. the host is not currently mounted) is a one-shot `pending` intent
 * stored for the host to drain on its next mount. This prevents the stale-pending leak: an
 * already-mounted host switches its tab via the listener and leaves no `pending` that a later
 * navigate-away-and-back would wrongly drain.
 */
export function requestMemberTab(hostId: string, memberId: string, anchor?: string): void {
  let handled = false;
  for (const l of listeners) {
    try {
      if (l(hostId, memberId, anchor) === true) handled = true;
    } catch {
      // a listener throwing must not break navigation
    }
  }
  if (handled) pending.delete(hostId);
  else pending.set(hostId, { memberId, ...(anchor ? { anchor } : {}) });
}

/** Consume (and clear) the pending member-tab intent for `hostId`, if any. Drained on host mount. */
export function takeMemberTabIntent(hostId: string): string | undefined {
  return takeMemberTabRequest(hostId)?.memberId;
}

/** Settings also addresses individual entries within a page. Latest request wins before mount. */
export function takeMemberTabRequest(hostId: string): { memberId: string; anchor?: string } | undefined {
  const request = pending.get(hostId);
  pending.delete(hostId);
  return request;
}

/**
 * Subscribe to member-tab requests. A mounted host subscribes so it switches tabs even when it is
 * already the active surface (no re-mount). Returns an unsubscribe fn (call in disconnectedCallback).
 */
export function subscribeMemberTab(listener: MemberTabListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
