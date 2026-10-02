/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** Operation entry point for one monitor-owned component recovery attempt. */
public final class RecoverComponentHandler implements OperationHandler {

  private final Supplier<ComponentRecoveryAuthority> authoritySupplier;

  public RecoverComponentHandler(Supplier<ComponentRecoveryAuthority> authoritySupplier) {
    this.authoritySupplier = Objects.requireNonNull(authoritySupplier, "authoritySupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String name = componentName(argumentsJson);
    if (name == null) {
      return failure(ApiErrorCode.INVALID_STATE, "Component name is required");
    }

    ComponentRecoveryAuthority authority = authoritySupplier.get();
    if (authority == null) {
      return failure(ApiErrorCode.SERVICE_UNAVAILABLE, "Component recovery is still initializing");
    }

    ComponentRecoveryAuthority.Outcome outcome = authority.requestComponentRecovery(name);
    return switch (outcome) {
      case ACCEPTED -> OperationResult.success(
          "Component recovery accepted",
          Map.of("component", name, "recovery", outcome.name()));
      case ALREADY_RUNNING -> failure(ApiErrorCode.ADMISSION_ENGINE_LIMIT,
          "A component recovery is already running");
      case EXHAUSTED -> failure(ApiErrorCode.WORKER_RECOVERY_EXHAUSTED,
          "Component recovery budget is spent; restart the application to retry");
      case NOT_APPLICABLE -> failure(ApiErrorCode.INVALID_STATE,
          "Component does not need recovery");
      case UNKNOWN_COMPONENT -> failure(ApiErrorCode.NOT_FOUND,
          "Unknown Engine component: " + name);
      case OWNER_UNAVAILABLE -> failure(ApiErrorCode.SERVICE_UNAVAILABLE,
          "Component has no available local recovery owner");
    };
  }

  private static String componentName(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) return null;
    try {
      JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
      JsonNode name = root == null ? null : root.get("name");
      if (name == null || !name.isString() || name.asString().isBlank()) return null;
      return name.asString().trim();
    } catch (RuntimeException invalidJson) {
      return null;
    }
  }

  private static OperationResult failure(ApiErrorCode code, String message) {
    return OperationResult.failure(message, code.name(), Map.of(), code.isRetryable());
  }
}
