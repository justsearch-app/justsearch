/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Tempdoc 627 (N2): forensic context computed at a recovery decision and carried onto the recovery
 * occurrence so RECENT EVENTS can say <em>which</em> attempt and <em>why</em>, not just
 * "restarting".
 *
 * <ul>
 *   <li>{@code attempt} — the 1-based restart attempt number
 *       ({@code BootRecoveryDecision.Decision.nextAttempt}).
 *   <li>{@code faultKind} — {@code "boot"}, the only value any live producer emits: the boot-recovery
 *       arm of {@code KnowledgeServerHealthMonitor} is the sole writer. The field is a string rather
 *       than an enum because it was the supervisor's vocabulary — {@code "hang"}
 *       (alive-but-unresponsive) and {@code "death"} (process gone) — and lane F stage A item A11
 *       deleted that supervisor along with the process it supervised. Stage B's supervisor is
 *       expected to widen it again.
 *   <li>{@code backoffMs} — the cooldown slept before the retry.
 * </ul>
 *
 * <p>The monitor carries this value directly in {@link RecoveryOccurrence}. Its episode-local
 * context is cleared after physical recovery or terminal give-up; readiness projections never
 * retain event history.
 */
public record RecoveryContext(int attempt, String faultKind, long backoffMs) {}
