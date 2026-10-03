/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Immediate wire response plus actual work completion; a started response is not completion. */
public record OperationExecution(OperationResult response, CompletionStage<OperationResult> completion) {
  public OperationExecution {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(completion, "completion");
  }
  public static OperationExecution finished(OperationResult result) {
    return new OperationExecution(result, CompletableFuture.completedFuture(result));
  }
}
