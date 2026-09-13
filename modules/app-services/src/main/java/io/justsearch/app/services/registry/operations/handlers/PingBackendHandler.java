/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.Map;

/**
 * Handler for {@code core.ping-backend} per tempdoc 429 §"Initial entries".
 *
 * <p>Adapts the existing {@code /api/health} endpoint. Returns a synthetic success
 * payload with timestamp; the actual production health check is delegated to the
 * existing {@code DiagnosticsController}.
 */
public final class PingBackendHandler implements OperationHandler {

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    return OperationResult.success(
        "Backend reachable",
        Map.of("timestamp", System.currentTimeMillis(), "status", "ok"));
  }
}
