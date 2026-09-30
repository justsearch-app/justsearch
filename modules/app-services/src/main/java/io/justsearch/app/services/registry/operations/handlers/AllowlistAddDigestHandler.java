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
 * Handler for {@code core.allowlist-add-digest}.
 *
 * <p>Slice 3a-2-c continuation: BrainPackImportSection Add Digest to
 * Allowlist button. Delegates to
 * {@link PolicyService#addDigestToAllowlist(String)} via lazy supplier.
 *
 * <p>Args shape: {@code {"manifestSha256": string}}. Returns
 * {@code structuredData} with {@code path}, {@code changed},
 * {@code allowlistedCount}.
 */
public final class AllowlistAddDigestHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(AllowlistAddDigestHandler.class);

  private final Supplier<PolicyService> supplier;

  public AllowlistAddDigestHandler(Supplier<PolicyService> supplier) {
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
      log.warn("AllowlistAddDigestHandler: supplier threw", e);
      return OperationResult.failure("Policy service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Policy service unavailable");
    }

    try {
      Map<String, Object> result = svc.addDigestToAllowlist(manifestSha256);
      return OperationResult.success("Digest added to allowlist", result);
    } catch (IllegalArgumentException e) {
      return OperationResult.failure(e.getMessage(), "INVALID_REQUEST", Map.of(), false);
    } catch (Exception e) {
      log.error("AllowlistAddDigestHandler: addDigestToAllowlist threw", e);
      return OperationResult.failure(
          "Allowlist update failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "USER_POLICY_UPDATE_FAILED",
          Map.of(),
          false);
    }
  }
}
