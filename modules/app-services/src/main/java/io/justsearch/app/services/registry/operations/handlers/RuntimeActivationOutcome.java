/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.RuntimeVariantService;
import java.util.Map;

/** Projects the runtime owner's terminal outcome; an inconclusive self-test did not activate. */
final class RuntimeActivationOutcome {
  private RuntimeActivationOutcome() {}

  static OperationExecution execution(String message, RuntimeVariantService.Attempt attempt) {
    return new OperationExecution(OperationResult.success(message, attempt.status()),
        attempt.completion().thenApply(status -> {
          if ("completed".equals(status.state)
              && !"failed".equals(status.result) && !"inconclusive".equals(status.result)) {
            return OperationResult.success(status.message);
          }
          String code = status.errorCode == null || status.errorCode.isBlank()
              ? "inconclusive".equals(status.result) ? "SELF_TEST_INCONCLUSIVE"
                  : "failed".equals(status.result) ? "SELF_TEST_FAILED" : "RUNTIME_ACTIVATION_INCOMPLETE"
              : status.errorCode;
          return OperationResult.failure(status.message, code, Map.of(), false);
        }));
  }
}
