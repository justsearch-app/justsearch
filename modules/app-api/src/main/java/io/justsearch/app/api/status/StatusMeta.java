/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Metadata object appended to the /api/status response to describe freshness and data provenance.
 *
 * <p>{@code workerRpcAtMs} is the epoch-millisecond timestamp captured immediately before the
 * Worker in-process port call. {@code workerRpcStale} is {@code true} when the call failed and the response
 * was assembled from fallback data (i.e., the Worker was unreachable for this request).
 *
 * <p>Consumers that do not care about freshness can ignore this object entirely.
 *
 * <p>333 §5: Status freshness meta object.
 */
public record StatusMeta(long workerRpcAtMs, boolean workerRpcStale) {}
