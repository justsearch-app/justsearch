/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that costly VDU mode transitions remain scoped to one captured batch. */
class VduBatchProcessorModeScopingTest {
  @TempDir Path tempDir;

  @Test
  void transitionsAreOncePerMultiDocumentPass() throws Exception {
    Fixture fixture = fixture(3);
    when(fixture.processor.process(any(Path.class), any(EngineContext.class)))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));

    OfflineProcessingOutcome outcome = fixture.run(() -> false);

    assertEquals(3, outcome.processed());
    verify(fixture.processor).enterVduMode();
    verify(fixture.processor).exitVduMode();
    verify(fixture.processor, times(3)).process(any(Path.class), any(EngineContext.class));
  }

  @Test
  void enterFailurePropagatesAndDoesNotAttemptDocumentsOrExit() throws Exception {
    Fixture fixture = fixture(2);
    VduProcessor.VduException enterFailure =
        new VduProcessor.VduException("enter failed", null);
    doThrow(enterFailure).when(fixture.processor).enterVduMode();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> fixture.run(() -> false));

    assertSame(enterFailure, thrown.getCause());
    verify(fixture.processor, never()).process(any(), any());
    verify(fixture.processor, never()).exitVduMode();
  }

  @Test
  void exitFailureIsVisibleWhenBatchBodySucceeds() throws Exception {
    Fixture fixture = fixture(1);
    when(fixture.processor.process(any(Path.class), any(EngineContext.class)))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));
    IllegalStateException exitFailure = new IllegalStateException("exit failed");
    doThrow(exitFailure).when(fixture.processor).exitVduMode();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> fixture.run(() -> false));

    assertSame(exitFailure, thrown);
  }

  @Test
  void cooperativeBlockLeavesRemainingDocumentsAndStillExitsMode() throws Exception {
    Fixture fixture = fixture(3);
    when(fixture.processor.process(any(Path.class), any(EngineContext.class)))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));
    AtomicInteger checks = new AtomicInteger();

    OfflineProcessingOutcome outcome = fixture.run(() -> checks.getAndIncrement() > 0);

    assertEquals(1, outcome.processed());
    assertEquals(2, outcome.remaining());
    assertEquals(BlockReason.ACTIVITY_OR_ENERGY, outcome.blockedReason());
    verify(fixture.processor).exitVduMode();
    verify(fixture.processor).process(any(Path.class), any(EngineContext.class));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void modeEntryCheckpointFailurePreservesCauseAndFatality(boolean fatal) throws Exception {
    Fixture fixture = fixture(2);
    var transition = new VduProcessor.VduException("enter failed", null);
    doThrow(transition).when(fixture.processor).enterVduMode();
    Throwable checkpoint = fatal ? new AssertionError("checkpoint fatal")
        : new IllegalStateException("checkpoint refused");
    var batch = new VduBatchProcessor(fixture.processor, fixture.gpu, () -> fixture.client,
        VduMetricCatalog.noop(), new VduCapabilityState());
    Class<? extends Throwable> expected = fatal ? AssertionError.class : IllegalStateException.class;
    Throwable thrown = assertThrows(expected,
        () -> batch.processPendingFiles(fixture.context, outcome -> {
          if (outcome.blockedReason() == BlockReason.AI_OFFLINE) {
            if (fatal) throw (AssertionError) checkpoint;
            throw (IllegalStateException) checkpoint;
          }
        }));
    assertEquals(1, thrown.getSuppressed().length);
    if (fatal) {
      assertSame(checkpoint, thrown);
      assertSame(transition, thrown.getSuppressed()[0].getCause());
    } else {
      assertSame(transition, thrown.getCause());
      assertSame(checkpoint, thrown.getSuppressed()[0]);
    }
    verify(fixture.client, never()).markVduProcessing(anyString(), anyInt(), any());
    verify(fixture.processor, never()).process(any(), any());
  }

  private Fixture fixture(int count) throws Exception {
    VduProcessor processor = mock(VduProcessor.class);
    when(processor.hasVisionCapability()).thenReturn(true);
    KnowledgeClient client = mock(KnowledgeClient.class);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Path file = tempDir.resolve("doc-" + i + ".png");
      Files.writeString(file, "image");
      ids.add(file.toString());
    }
    EngineContext context = TestEngineContexts.durableInternal();
    when(client.queryPendingVduDocIds(context)).thenReturn(ids);
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(anyString(), any(), any(VduUpdateOutcome.class), any(), anyInt(), any()))
        .thenReturn(true);
    GpuCapabilitiesService gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    return new Fixture(processor, client, gpu, context);
  }

  private record Fixture(VduProcessor processor, KnowledgeClient client,
      GpuCapabilitiesService gpu, EngineContext context) {
    OfflineProcessingOutcome run(java.util.function.BooleanSupplier interruption) {
      return new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
          new VduCapabilityState(), interruption)
          .processPendingFiles(context, ignored -> {});
    }
  }
}
