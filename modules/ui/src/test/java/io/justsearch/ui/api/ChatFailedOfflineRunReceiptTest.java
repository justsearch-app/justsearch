/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.TriggerOfflineProcessingHandler;
import io.justsearch.app.services.vdu.ImagePreparer;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.vdu.VduBatchProcessor;
import io.justsearch.app.services.vdu.VduCapabilityState;
import io.justsearch.app.services.vdu.VduMetricCatalog;
import io.justsearch.app.services.vdu.VduProcessor;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * S4: a person's explicit picture-reading run while chat is FAILED. The real document processor,
 * batch, coordinator, trigger handler, attempt runner and SQLite operation store; the chat
 * controller answers the VDU hold the way it does while a failure is latched ({@link
 * RefusalReason#FAILED}, nothing launched).
 */
@Timeout(20)
final class ChatFailedOfflineRunReceiptTest {
  @TempDir Path directory;

  @Test
  void explicitRunWhileChatIsFailedRecordsATypedRefusalThatSurvivesReopeningTheStore()
      throws Exception {
    Path database = directory.resolve("chat-failed.db");
    Path picture = Files.writeString(directory.resolve("picture.png"), "image");
    var executors = new DefaultEngineExecutorRegistry();
    var admission = new EngineAdmissionController(1, 1, 1);
    var client = mock(KnowledgeClient.class);
    var holds = mock(ChatHolds.class);
    var ai = mock(OnlineAiService.class);
    var introspection = mock(OnlineAiRuntimeIntrospection.class);
    when(introspection.hasVisionCapability()).thenReturn(true);
    when(holds.serverLaunches()).thenReturn(4L);
    when(holds.holdVdu(anyString()))
        .thenThrow(new ChatRefusal(RefusalReason.FAILED, Optional.empty(), false));
    when(client.countPendingVdu(any())).thenReturn(1);
    when(client.queryPendingVduDocIds(any())).thenReturn(List.of(picture.toString()));
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(gpuSnapshot());
    var processor = new VduProcessor(introspection, holds, ai,
        new TempFileManager(directory.resolve("temp")), new ImagePreparer(), VduMetricCatalog.noop());
    var batch = new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
        new VduCapabilityState());
    var coordinator = new OfflineCoordinator(
        executors, admission, batch, () -> client, new VduCapabilityState());
    var brain = new BrainRuntimeServiceImpl(mock(OnlineAiService.class),
        (context, progress) -> coordinator.startExplicitProcessing(context, progress));
    var handler = new TriggerOfflineProcessingHandler(() -> brain);
    EngineContext caller = TestRequestContexts.browser();
    String key;
    String code;
    try (var store = new SqliteOperationStore(database)) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var descriptor = OperationDescriptor.invocation(OperationKind.OPERATION,
          CoreOperationCatalog.TRIGGER_OFFLINE_PROCESSING.value(), "{}", false);
      OperationAttemptRunner.Result running;
      try (var dispatch = admission.admit(caller, false)) {
        EngineContext context = dispatch.context();
        var prepared = runner.accept(new OperationAttemptRunner.Request(null, descriptor, context, null));
        key = prepared.accepted().key();
        running = runner.start(prepared, record -> handler.executeRecorded("{}", null, context, record));
      }
      OperationRecord terminal = running.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertEquals(OperationState.FAILED, terminal.state());
      code = terminal.receipt().code();
      assertNotEquals("UNCAUGHT_EXCEPTION", code,
          "a refusal because chat is FAILED is a known outcome, not an uncaught exception");
      assertTrue(code.toLowerCase(java.util.Locale.ROOT).matches(".*chat.?failed.*"),
          "the receipt names the chat failure: " + code);
    } finally {
      coordinator.close();
      executors.close();
    }
    try (var reopened = new SqliteOperationStore(database)) {
      assertEquals(code, reopened.find(key).orElseThrow().receipt().code(),
          "the typed code is what the store keeps");
    }
    verify(holds, times(1)).holdVdu(anyString());
    verify(client, never()).markVduProcessing(anyString(), anyInt(), anyString(), any());
    verifyNoInteractions(ai);
    assertEquals(4L, holds.serverLaunches(), "no server launch");
  }

  private static GpuCapabilities gpuSnapshot() {
    var effective = new GpuCapabilities.Effective(true, "test", GpuCapabilities.Confidence.HIGH,
        "1.0", 1, 0, 1, 24_000_000_000L, 24_000_000_000L, 0L,
        GpuCapabilities.Cuda.unknown());
    return new GpuCapabilities(null, null, effective);
  }
}
