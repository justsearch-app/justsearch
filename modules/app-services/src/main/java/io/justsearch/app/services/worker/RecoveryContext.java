/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Tempdoc 627 (N2): forensic context computed at a recovery decision and carried onto the recovery
 * occurrence so RECENT EVENTS can say <em>which</em> attempt and <em>why</em>, not just
 * "restarting".
 *
 * <ul>
 *   <li>{@code attempt} — the 1-based restart attempt number
 *       (the component registry's cumulative recovery attempt).
 *   <li>{@code faultKind} — the recovered component name.
 *   <li>{@code backoffMs} — the cooldown slept before the retry.
 * </ul>
 *
 * <p>The monitor carries this value directly in {@link RecoveryOccurrence}. Its episode-local
 * context is cleared after physical recovery or terminal exhaustion; readiness projections never
 * retain event history.
 */
public record RecoveryContext(int attempt, String faultKind, long backoffMs) {}
