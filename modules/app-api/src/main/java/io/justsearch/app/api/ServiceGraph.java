/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * The full set of head-side services, partitioned by the lifecycle stage at which they become
 * usable. Produced by {@code ServicePhase} (tempdoc 519 §4), consumed by controllers, operation
 * handlers, SSE channels — anything that today goes through the {@code AppFacade} locator.
 *
 * <p>The three sub-records reflect the three distinct availability profiles:
 * <ul>
 *   <li>{@link CoreServices} — always available after head-side bootstrap.</li>
 *   <li>{@link WorkerServices} — fully resolved only after the index component is reachable.</li>
 *   <li>{@link InferenceServices} — usable even when the inference engine is OFFLINE; callers
 *       test the {@code isAvailable()} of the individual service before invoking.</li>
 * </ul>
 *
 * <p>Tempdoc 519 §5 / Block C.1: replaces the {@code AppFacade} locator pattern. Consumers
 * receive the specific record they need at construction time, not a god-interface lookup.
 *
 * <p>Stability: stable (API contract).
 */
public record ServiceGraph(
    CoreServices core,
    WorkerServices worker,
    InferenceServices inference) {}
