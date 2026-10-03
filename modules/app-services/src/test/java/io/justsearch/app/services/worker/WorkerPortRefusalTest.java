package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.ipc.StatusResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class WorkerPortRefusalTest {
  static Stream<Arguments> refusals() {
    return Stream.of("start", "cutover", "rollback", "pause", "resume", "gc", "settle",
            "sync", "prune", "operational", "debug", "health", "version", "policies", "ort")
        .flatMap(path -> Stream.of(false, true).flatMap(admission ->
            Stream.of(false, true).map(wrapped -> Arguments.of(path, admission, wrapped))));
  }

  @ParameterizedTest
  @MethodSource("refusals")
  void optionalPortFailureTranslationPreservesRefusals(String path, boolean admission, boolean wrapped) {
    RuntimeException refused = admission
        ? new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3)
        : new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "engine.calls", 3);
    RuntimeException failure = wrapped
        ? new CompletionException(new ExecutionException(refused)) : refused;
    var rpc = mock(IngestRpcExecutor.class, invocation -> { throw failure; });
    var migration = new MigrationOps(rpc);
    var registry = mock(EngineExecutorRegistry.class);
    when(registry.limits(EngineExecutorSpec.Kind.BACKGROUND))
        .thenReturn(new EngineExecutorRegistry.Limits(1, 1));
    var sync = new SyncOps(registry, rpc, Map.of(), null);
    var client = mock(KnowledgeClient.class, CALLS_REAL_METHODS);
    doReturn(StatusResponse.getDefaultInstance()).when(client).getStatus(any());
    doThrow(failure).when(client).getHealthCheck(any());
    doThrow(failure).when(client).executeHealthRpc(anyString(), anyLong(), any(), any());
    doThrow(failure).when(client).executeIngestRpc(anyString(), any(), any(), any());
    if (path.equals("ort")) doThrow(failure).when(client).getStatus(any());
    var context = TestEngineContexts.internal();

    var thrown = assertThrows(RuntimeException.class, () -> {
      switch (path) {
        case "start" -> migration.startMigration("test", List.of(), context);
        case "cutover" -> migration.requestCutover(false, context);
        case "rollback" -> migration.rollbackMigration(context);
        case "pause" -> migration.pauseMigration("test", context);
        case "resume" -> migration.resumeMigration(context);
        case "gc" -> migration.runIndexGc(0, true, context);
        case "settle" -> migration.settleIndex(true, 0, context);
        case "sync" -> sync.syncDirectory("root", true, context);
        case "prune" -> sync.pruneMissing("root", context);
        case "operational" -> client.getWorkerOperationalView(context);
        case "debug" -> client.getDebugWorkerState(context);
        case "health" -> client.isHealthy(context);
        case "version" -> client.getVersion(context);
        case "policies" -> client.getSessionPolicies(context);
        case "ort" -> client.getEncoderOrtCudaViews(context);
        default -> throw new AssertionError(path);
      }
    });
    assertSame(refused, thrown);
  }
}
