/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.PolicyService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.create-user-policy}.
 *
 * <p>Slice 3a-2-c continuation: BrainPackImportSection Create User Policy
 * button. Delegates to {@link PolicyService#createUserPolicy(String)} via
 * lazy supplier.
 *
 * <p>Args shape: {@code {"manifestSha256": string}}. Returns the created
 * file path in {@code structuredData.path}.
 */
public final class CreateUserPolicyHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(CreateUserPolicyHandler.class);

  private final Supplier<PolicyService> supplier;

  public CreateUserPolicyHandler(Supplier<PolicyService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String manifestSha256;
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode v = root.get("manifestSha256");
      if (v == null || !v.isTextual() || v.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: manifestSha256");
      }
      manifestSha256 = v.asString();
    } catch (Exception e) {
      return HandlerJson.invalidArgs(e);
    }

    PolicyService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("CreateUserPolicyHandler: supplier threw", e);
      return OperationResult.failure("Policy service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Policy service unavailable");
    }

    try {
      Map<String, Object> result = svc.createUserPolicy(manifestSha256);
      return OperationResult.success("User policy created", result);
    } catch (IllegalArgumentException e) {
      return OperationResult.failure(e.getMessage(), "INVALID_REQUEST", Map.of(), false);
    } catch (Exception e) {
      // NOTE: UserPolicyWriteException (modules/ui) carries a typed
      // ApiErrorCode + httpStatus (e.g., MACHINE_POLICY_PRESENT,
      // USER_POLICY_ALREADY_EXISTS). Lifted to app-api in Phase D; until
      // then the substrate surface uses the generic code.
      log.error("CreateUserPolicyHandler: createUserPolicy threw", e);
      return OperationResult.failure(
          "User policy create failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "USER_POLICY_CREATE_FAILED",
          Map.of(),
          false);
    }
  }
}
