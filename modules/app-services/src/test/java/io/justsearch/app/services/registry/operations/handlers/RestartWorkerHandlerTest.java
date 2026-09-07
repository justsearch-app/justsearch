package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.WorkerService;
import io.justsearch.app.services.worker.RestartRequiredException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RestartWorkerHandler}'s real implementation (slice 3a-1-2 closure).
 *
 * <p>Replaces the prior stub-behavior tests with delegation tests against a
 * lambda-implemented {@link WorkerService}.
 *
 * <p><b>Lane F review, major 5.</b> The production {@code WorkerService} cannot restart anything
 * any more — item A11 deleted the spawner, so {@code WorkerServiceImpl.restart()} throws
 * {@link RestartRequiredException} unconditionally and the operation's ANSWER is "the user must
 * restart the Engine". The handler was letting that land in its generic {@code catch (Exception)},
 * which logged a stack trace at ERROR and returned "Worker restart failed: ..." — so the one path
 * production actually takes was reported as a crash. The case below pins the real answer. The
 * success case is kept because the handler is generic over {@code WorkerService} and its
 * delegation contract still holds; it is simply not the shape the live implementation produces.
 */
final class RestartWorkerHandlerTest {

  @Test
  void restartRequiredIsAnAnswerNotACrash() {
    RestartWorkerHandler handler =
        new RestartWorkerHandler(
            () ->
                new WorkerService() {
                  @Override
                  public boolean available() {
                    return true;
                  }

                  @Override
                  public long workerPid() {
                    return 0L;
                  }

                  @Override
                  public int restart() {
                    // Exactly what WorkerServiceImpl does on the live path.
                    throw new RestartRequiredException("Restarting the index half");
                  }
                });

    OperationResult result = handler.execute("{}");

    assertFalse(result.success(), "it did not restart, so it is not a success");
    assertEquals(
        RestartRequiredException.CODE,
        result.errorCode().orElse(null),
        "the caller must be able to tell 'impossible, do something else' from 'broke, retry' — and"
            + " this is the same code the HTTP path already answers 409 with, so the two surfaces"
            + " agree about one event");
    assertEquals(
        Boolean.FALSE,
        result.retryable().orElse(null),
        "retrying cannot help; restarting can. An agent that retries this forever is the failure"
            + " this field exists to prevent");
    assertFalse(
        result.message().contains("Worker restart failed"),
        "the generic crash wording would tell an agent the operation broke: " + result.message());
  }

  @Test
  void executeReturnsFailureWhenWorkerUnavailable() {
    // Default constructor uses WorkerService.unavailable() → available()
    // returns false → handler returns failure.
    RestartWorkerHandler handler = new RestartWorkerHandler();
    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("Worker service unavailable"));
  }

  @Test
  void executeReturnsSuccessWithPortWhenWorkerRestarts() {
    RestartWorkerHandler handler =
        new RestartWorkerHandler(
            () ->
                new WorkerService() {
                  @Override
                  public boolean available() {
                    return true;
                  }

                  @Override
                  public long workerPid() {
                    return 12345L;
                  }

                  @Override
                  public int restart() {
                    return 9001;
                  }
                });
    OperationResult result = handler.execute("{}");
    assertTrue(result.success());
    assertTrue(result.message().contains("9001"));
    assertEquals(9001, result.structuredData().get("port"));
  }

  @Test
  void executeReturnsFailureWhenRestartThrows() {
    RestartWorkerHandler handler =
        new RestartWorkerHandler(
            () ->
                new WorkerService() {
                  @Override
                  public boolean available() {
                    return true;
                  }

                  @Override
                  public long workerPid() {
                    return 0L;
                  }

                  @Override
                  public int restart() throws Exception {
                    throw new java.io.IOException("port already in use");
                  }
                });
    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("port already in use"));
  }

  @Test
  void executeIgnoresArguments() {
    // Restart-worker takes no arguments per CoreOperationCatalog.intf
    RestartWorkerHandler handler = new RestartWorkerHandler();
    OperationResult one = handler.execute("{}");
    OperationResult two = handler.execute("{\"foo\":\"bar\"}");
    assertEquals(one.success(), two.success());
  }

  @Test
  void executeHasNoExecutionId() {
    // Restart is not undoable.
    RestartWorkerHandler handler = new RestartWorkerHandler();
    OperationResult result = handler.execute("{}");
    assertTrue(result.executionId().isEmpty());
  }
}
