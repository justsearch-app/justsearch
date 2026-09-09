/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.function.Function;

/**
 * Abstraction for executing search RPCs with circuit breaker and deadline support.
 *
 * <p>Companion classes use this instead of depending on {@link KnowledgeClient} directly.
 */
@FunctionalInterface
public interface SearchRpcExecutor {
    <T> T execute(
            String operation,
            KnowledgeClient.RpcDeadlineCategory category,
            Function<SearchServiceCalls, T> rpc, io.justsearch.core.context.EngineContext engineContext);
}
