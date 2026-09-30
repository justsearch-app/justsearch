/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Wire-level search service: translates between {@link SearchRequest} / {@link SearchResponse}
 * (app-api DTOs) and the core search port. Replaces the {@code AppFacade.search(SearchRequest)}
 * method that was the only non-locator member of the deprecated AppFacade interface.
 *
 * <p>Tempdoc 519 §5: extracted because {@code AppFacade}'s six methods were five locator
 * accessors plus this one business method. Moving it to a dedicated service interface lets the
 * three typed service records ({@code CoreServices}, {@code WorkerServices},
 * {@code InferenceServices}) replace the locator entirely. {@code SearchService} lives in
 * {@code WorkerServices} because it requires a reachable Worker (the implementation wraps a
 * core {@code SearchPort} which gRPC-dials the Worker).
 *
 * <p>Production implementation: {@code io.justsearch.app.services.search.SearchServiceImpl}.
 *
 * <p>Stability: stable (API contract).
 */
public interface SearchService {
  SearchResponse search(SearchRequest request, io.justsearch.core.context.EngineContext context);
}
