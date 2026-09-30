/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import tools.jackson.databind.JsonNode;

/**
 * Shared helpers for {@link StartAiInstallHandler} + {@link RepairAiInstallHandler}.
 * Both parse the same {@code {"acceptTerms"?: boolean}} arg shape; this avoids
 * duplicating the Jackson tree-read across the two handlers.
 */
final class BrainInstallHandlerSupport {

  private BrainInstallHandlerSupport() {}

  @SuppressWarnings("unchecked")
  static io.justsearch.agent.api.registry.OperationExecution execution(
      String message, io.justsearch.app.api.AiInstallService.Attempt attempt) {
    var completion = attempt.completion().thenApply(status -> {
      if ("cancelled".equals(status.state)) throw new java.util.concurrent.CancellationException();
      if ("completed".equals(status.state)) {
        return io.justsearch.agent.api.registry.OperationResult.success(status.message);
      }
      String code = status.errorCode == null || status.errorCode.isBlank() ? "INSTALL_INCOMPLETE" : status.errorCode;
      return io.justsearch.agent.api.registry.OperationResult.failure(status.message, code, java.util.Map.of(), false);
    });
    java.util.Map<String, Object> snapshot = HandlerJson.MAPPER.convertValue(attempt.started(), java.util.Map.class);
    return new io.justsearch.agent.api.registry.OperationExecution(
        io.justsearch.agent.api.registry.OperationResult.success(message, snapshot), completion);
  }

  static boolean parseAcceptTerms(String argumentsJson) {
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode v = root.get("acceptTerms");
      return v != null && v.isBoolean() && v.asBoolean();
    } catch (Exception e) {
      // tolerate parse errors — default false (matches HTTP handler semantics).
      return false;
    }
  }
}
