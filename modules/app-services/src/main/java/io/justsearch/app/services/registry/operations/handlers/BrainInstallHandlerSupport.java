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

  /** The string items of array {@code key}; empty when absent or malformed. */
  static java.util.Set<String> parseStrings(String argumentsJson, String key) {
    try {
      JsonNode v = HandlerJson.MAPPER.readTree(
          argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson).get(key);
      if (v == null || !v.isArray()) return java.util.Set.of();
      var items = new java.util.LinkedHashSet<String>();
      for (JsonNode item : v) {
        if (!item.isString() || item.asString().isBlank()) return java.util.Set.of();
        items.add(item.asString());
      }
      return java.util.Set.copyOf(items);
    } catch (RuntimeException malformed) {
      return java.util.Set.of();
    }
  }

  /** The string value of {@code key}; {@code null} when absent or not a string. */
  static String parseString(String argumentsJson, String key) {
    try {
      JsonNode v = HandlerJson.MAPPER.readTree(
          argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson).get(key);
      return v != null && v.isString() ? v.asString() : null;
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  static boolean parseAcceptTerms(String argumentsJson) {
    return parseBoolean(argumentsJson, "acceptTerms");
  }

  static boolean parseBoolean(String argumentsJson, String key) {
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode v = root.get(key);
      return v != null && v.isBoolean() && v.asBoolean();
    } catch (Exception e) {
      // tolerate parse errors — default false (matches HTTP handler semantics).
      return false;
    }
  }
}
