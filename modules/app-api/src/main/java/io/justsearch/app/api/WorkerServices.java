/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Worker-process-dependent services: indexing, document fetch, exclude-pattern enforcement,
 * and search (which gRPC-dials the Worker via {@code SearchPort}).
 * All four require a reachable Worker to function — they may be unavailable (or have
 * supplier-backed indirection) until {@code ConnectPhase} resolves the live Worker reference.
 *
 * <p>Part of the typed-service-graph that replaces the {@code AppFacade} locator interface
 * (tempdoc 519 §5 / Block C.1).
 *
 * <p>Stability: stable (API contract).
 */
public record WorkerServices(
    IndexingService indexing,
    DocumentService documents,
    ExcludesService excludes,
    SearchService search) {}
