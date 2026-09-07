/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.app.services.worker.RestartRequiredException;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.WorkerService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.restart-worker}.
 *
 * <p>Slice 3a-1-2 closure: real implementation replaces the prior synthetic-success
 * stub. Delegates to {@link WorkerService#restart()} via a lazy supplier (the
 * {@code WorkerService} is late-bound in AppFacade after async Worker startup, so
 * eager capture would freeze an unavailable() instance).
 *
 * <p>Returns {@code structuredData.port} on success so callers (FE, agent loops)
 * can observe the new port without a separate health probe. Reconnection of the
 * gRPC client + circuit-breaker reset happen inside
 * {@code WorkerServiceImpl.restart()} per its docstring contract.
 */
public final class RestartWorkerHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(RestartWorkerHandler.class);

  private final Supplier<WorkerService> workerSupplier;

  /**
   * Default constructor — supplier returning unavailable(). Used by test paths
   * that don't need real worker dispatch (e.g., ValidatorRunnerTest).
   */
  public RestartWorkerHandler() {
    this(WorkerService::unavailable);
  }

  public RestartWorkerHandler(Supplier<WorkerService> workerSupplier) {
    this.workerSupplier = Objects.requireNonNull(workerSupplier, "workerSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    WorkerService worker;
    try {
      worker = workerSupplier.get();
    } catch (RuntimeException e) {
      log.warn("RestartWorkerHandler: worker supplier threw", e);
      return OperationResult.failure("Worker service unavailable: " + e.getMessage());
    }
    if (worker == null || !worker.available()) {
      return OperationResult.failure("Worker service unavailable");
    }
    try {
      int port = worker.restart();
      return OperationResult.success(
          "Worker restarted on port " + port, Map.of("port", port));
    } catch (RestartRequiredException e) {
      // Not a failure of this operation — it is the operation's ANSWER since item A11 deleted the
      // spawner. Restarting the index half means restarting the Engine, which only the user can do.
      //
      // It was landing in the generic catch below, which logged at ERROR with a stack trace and
      // returned "Worker restart failed: Restarting the index half". Three things wrong with that,
      // none of them cosmetic: an agent reading the result cannot tell "this is impossible, do
      // something else" from "this broke, retry"; every invocation wrote a stack trace for an
      // expected, correct outcome; and the HTTP path already answers 409 with the restart_required
      // code for the same condition, so the two surfaces disagreed about one event. Carrying the
      // code makes them agree, and retryable=false says the thing the caller needs to know —
      // retrying will not help, restarting will.
      return OperationResult.failure(
          e.getMessage(), RestartRequiredException.CODE, Map.of(), Boolean.FALSE);
    } catch (Exception e) {
      log.error("RestartWorkerHandler: worker restart threw", e);
      return OperationResult.failure(
          "Worker restart failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }
}
