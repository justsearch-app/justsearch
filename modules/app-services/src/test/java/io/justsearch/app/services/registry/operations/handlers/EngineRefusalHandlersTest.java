package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class EngineRefusalHandlersTest {
  @TempDir Path directory;

  static Stream<Arguments> refusals() {
    return Stream.of("clear", "retry", "cancel", "gc", "settle", "add", "remove", "reconcile",
            "resolve", "resolveFallback", "preview", "apply", "recover", "offline")
        .flatMap(path -> Stream.of(false, true).flatMap(admission ->
            Stream.of(false, true).map(wrapped -> Arguments.of(path, admission, wrapped))));
  }

  @ParameterizedTest
  @MethodSource("refusals")
  void handlerPreservesTypedRefusal(String path, boolean admission, boolean wrapped) {
    RuntimeException refused = admission
        ? new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3)
        : new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "engine.calls", 3);
    RuntimeException failure = wrapped
        ? new CompletionException(new ExecutionException(refused)) : refused;
    var indexing = mock(IndexingService.class, invocation -> {
      if (path.equals("resolveFallback") && invocation.getMethod().getName().equals("resolvePathHash")) {
        return Map.of("found", false);
      }
      throw failure;
    });
    var excludes = mock(ExcludesService.class, invocation -> { throw failure; });
    var leases = mock(OperationLeaseService.class);
    var lease = mock(OperationLeaseHandle.class);
    when(leases.register(anyString(), any(), anyLong(), any())).thenReturn(lease);
    OperationHandler handler = switch (path) {
      case "clear" -> new ClearFailedJobsHandler(() -> indexing);
      case "retry" -> new RetryIndexingJobHandler(() -> indexing);
      case "cancel" -> new CancelIndexingJobHandler(() -> indexing);
      case "gc" -> new IndexGcHandler(() -> indexing, leases);
      case "settle" -> new SettleIndexHandler(() -> indexing, leases);
      case "add" -> new AddWatchedRootHandler(() -> indexing);
      case "remove" -> new RemoveWatchedRootHandler(() -> indexing);
      case "reconcile" -> new ReconcileRootHandler(() -> indexing);
      case "resolve", "resolveFallback" -> new ResolvePathHashHandler(() -> indexing);
      case "preview" -> new PreviewExcludesHandler(() -> excludes);
      case "apply" -> new ApplyExcludesHandler(() -> excludes);
      case "recover" -> new RecoverComponentHandler(
          () -> mock(ComponentRecoveryAuthority.class, invocation -> { throw failure; }));
      case "offline" -> new TriggerOfflineProcessingHandler(
          () -> mock(BrainRuntimeService.class, invocation -> { throw failure; }));
      default -> throw new AssertionError(path);
    };
    String arguments = HandlerJson.MAPPER.writeValueAsString(Map.of(
        "path", directory.toString(), "pathHash", "hash", "name", "index"));

    var thrown = assertThrows(RuntimeException.class,
        () -> handler.execute(arguments, TestEngineContexts.internal()));
    // Recovery has no local error translation; asynchronous wrappers may reach the common writer.
    if (path.equals("recover") && wrapped) {
      assertSame(failure, thrown);
    } else {
      assertSame(refused, thrown);
    }
    if (path.equals("gc") || path.equals("settle")) verify(lease).release(OpLeaseOutcome.FAILURE);
  }
}
