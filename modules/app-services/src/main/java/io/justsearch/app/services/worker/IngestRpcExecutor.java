/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import java.util.function.Function;

/**
 * Abstraction for executing ingest RPCs with circuit breaker and deadline support.
 *
 * <p>Companion classes use this instead of depending on {@link KnowledgeClient} directly.
 */
@FunctionalInterface
public interface IngestRpcExecutor {
    <T> T execute(
            String operation,
            KnowledgeClient.RpcDeadlineCategory category,
            Function<IngestServiceCalls, T> rpc, EngineContext engineContext);
}
