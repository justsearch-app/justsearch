/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.DeviceMemoryLine;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CandidateIndexTargetCapture;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionRetiredException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** Worker publication proof with the physical device-memory supplier replaced at its boundary. */
final class KnowledgeServerDeviceMemoryLineTest {
  private static final long FOOTPRINT = 1024L;

  @Test
  void fittingCandidateRetainsNativeAViewDuringBCompose(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, new DeviceMemoryLine(4096L, 2048L));
        var composition = mockedComposition()) {
      var sawA = new AtomicBoolean();
      composition.when(() -> InferenceCompositionRoot.compose(any(), any(), any(), any(),
          any(), any(), any())).thenAnswer(ignored -> {
            try (var serving = fixture.server.captureServingView()) {
              sawA.set(serving.services() == fixture.producer
                  && serving.encoderSet() == fixture.sourceOwner);
            }
            return emptySurface();
          });
      fixture.composeCandidate();
      assertTrue(sawA.get(), "A must remain the native serving view throughout beside compose");
      assertEquals(ComposeEvidence.Mode.BESIDE, fixture.composeEvidence().mode());
      try (var serving = fixture.server.captureServingView()) {
        assertSame(fixture.producer, serving.services());
        assertSame(fixture.sourceOwner, serving.encoderSet());
      }
    }
  }

  @Test
  void insufficientDeviceMemoryPublishesLexicalAAndReloadingBeforeCompose(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir, new DeviceMemoryLine(4096L, 512L));
        var composition = mockedComposition()) {
      var sawLexicalA = new AtomicBoolean();
      composition.when(() -> InferenceCompositionRoot.compose(any(), any(), any(), any(),
          any(), any(), any())).thenAnswer(ignored -> {
            try (var serving = fixture.server.captureServingView()) {
              sawLexicalA.set(serving.services() == fixture.lexical
                  && serving.encoderSet() == null);
            }
            verify(fixture.component).transition(ComponentState.RELOADING, null,
                "A serves text while candidate native models compose in place");
            return emptySurface();
          });
      fixture.composeCandidate();
      assertTrue(sawLexicalA.get(), "text-only A must be published before B native compose");
      assertEquals(ComposeEvidence.Mode.IN_PLACE, fixture.composeEvidence().mode());
      assertTrue(fixture.sourceOwner.isClosed(), "native A must retire before B composition");
    }
  }

  @Test
  void rejectedInPlaceCandidateRecomposesTheSource(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir, new DeviceMemoryLine(4096L, 512L));
        var composition = mockedComposition()) {
      composition.when(() -> InferenceCompositionRoot.compose(any(), any(), any(), any(),
          any(), any(), any()))
          .thenThrow(new IllegalStateException("B rejected"))
          .thenReturn(emptySurface());
      var refusal = assertThrows(InvocationTargetException.class, fixture::composeCandidate)
          .getCause();
      assertTrue(refusal instanceof IllegalStateException);
      assertEquals(ComposeEvidence.Mode.IN_PLACE, fixture.composeEvidence().mode());
      fixture.recomposeAfterRefusal((Exception) refusal);
      try (var serving = fixture.server.captureServingView()) {
        assertSame(fixture.producer, serving.services());
        assertTrue(serving.encoderSet() != fixture.sourceOwner,
            "the restored A must own a newly composed native set");
      }
      assertTrue(fixture.sourceOwner.isClosed());
    }
  }

  @Test
  void restoredSourceNativeCallDrainsBeforeAnotherInPlaceBuild(@TempDir Path dir)
      throws Exception {
    var discovery = io.justsearch.ort.testing.ModelDirTestResolver.discover(
        "models/onnx/gte-multilingual-base", null, "model.onnx");
    Assumptions.assumeTrue(discovery.modelDir() != null,
        "standard embedding model is unavailable for native lifetime proof");
    var handle = io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
        "restored-A-held-native", discovery.modelDir());
    var request = SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, java.time.Duration.ofSeconds(2));
    try (var fixture = new Fixture(dir, new DeviceMemoryLine(4096L, 512L));
        var composition = mockedComposition()) {
      composition.when(() -> InferenceCompositionRoot.compose(any(), any(), any(), any(),
          any(), any(), any()))
          .thenThrow(new IllegalStateException("B rejected"))
          .thenReturn(new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
              Optional.empty(), Optional.empty(), Optional.empty(),
              mock(io.justsearch.ort.PolicySnapshot.class), List.of(handle)));
      var refusal = assertThrows(InvocationTargetException.class, fixture::composeCandidate)
          .getCause();
      fixture.recomposeAfterRefusal((Exception) refusal);
      try (var restored = fixture.server.captureServingView()) {
        assertTrue(restored.encoderSet() != fixture.sourceOwner,
            "A's restored native owner must be a new generation");
      }

      var issuedNative = handle.acquireCpu(request);
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        try {
          var nextBuild = executor.submit(() -> {
            fixture.beginInPlaceBuild();
            return null;
          });
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
          while (handle.retirementStatus() == io.justsearch.ort.SessionHandle.RetirementStatus.ACTIVE
              && System.nanoTime() < deadline) Thread.onSpinWait();
          assertEquals(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRING,
              handle.retirementStatus(), "the next build must retire restored A first");
          assertTrue(!nextBuild.isDone(), "B cannot compose while restored A's native call runs");
          assertTrue(!issuedNative.session().getInputNames().isEmpty(),
              "restored A's issued native session stays readable during retirement");
          assertThrows(SessionRetiredException.class, () -> handle.acquireCpu(request));
          issuedNative.close();
          nextBuild.get(5, TimeUnit.SECONDS);
          assertEquals(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED,
              handle.retirementStatus());
        } finally {
          issuedNative.close();
        }
      }
    }
  }

  @Test
  void rejectedCandidateAndFailedSourceRecomposeRetainBothReasons(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir, new DeviceMemoryLine(4096L, 512L));
        var composition = mockedComposition()) {
      composition.when(() -> InferenceCompositionRoot.compose(any(), any(), any(), any(),
          any(), any(), any()))
          .thenThrow(new IllegalStateException("B rejected"))
          .thenThrow(new IllegalStateException("A unavailable"));
      var refusal = assertThrows(InvocationTargetException.class, fixture::composeCandidate)
          .getCause();
      assertEquals(ComposeEvidence.Mode.IN_PLACE, fixture.composeEvidence().mode());
      fixture.recomposeAfterRefusal((Exception) refusal);
      var evidence = org.mockito.ArgumentCaptor.forClass(String.class);
      verify(fixture.component).recordRecoveryAttempt(evidence.capture());
      assertTrue(evidence.getValue().contains("B refused: B rejected"));
      assertTrue(evidence.getValue().contains("A recompose refused: A unavailable"));
      verify(fixture.component).transition(ComponentState.UNAVAILABLE, null, evidence.getValue());
      try (var serving = fixture.server.captureServingView()) {
        assertSame(fixture.lexical, serving.services(),
            "A text remains published when both native compositions fail");
      }
    }
  }

  private static MockedStatic<InferenceCompositionRoot> mockedComposition() {
    var composition = mockStatic(InferenceCompositionRoot.class);
    composition.when(() -> InferenceCompositionRoot.estimateCandidateFootprintBytes(
        any(), any(), any(), any(), any())).thenReturn(FOOTPRINT);
    return composition;
  }

  private static InferenceSurface emptySurface() {
    return new InferenceSurface(Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(),
        mock(io.justsearch.ort.PolicySnapshot.class), List.of());
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    Field field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static final class Fixture implements AutoCloseable {
    private final TestEngineExecutors executors = new TestEngineExecutors();
    private final ComponentHandle component = mock(ComponentHandle.class);
    private final DefaultWorkerAppServices producer = mock(DefaultWorkerAppServices.class);
    private final WorkerAppServices lexical = mock(WorkerAppServices.class);
    private final EncoderSet sourceOwner;
    private final KnowledgeServer server;
    private Object candidate;

    private Fixture(Path dir, DeviceMemoryLine line) throws Exception {
      ResolvedConfig configuration = ResolvedConfig.builder().contributeEnvRegistry().build();
      server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dir.resolve("data")),
          null, ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null,
          component, configuration, () -> configuration, new ReentrantReadWriteLock(), () -> line);
      var absent = IndexFingerprint.ModelFingerprint.notConfigured();
      sourceOwner = new EncoderSet(emptySurface(),
          new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));
      sourceOwner.releaseModelReady();
      var active = mock(RunningRuntime.class);
      var green = mock(RunningRuntime.class);
      when(producer.prepareTextOnlyCandidateView(active)).thenReturn(lexical);
      server.appServices = producer;
      set(server, "searchLifecycle", active);
      set(server, "ingestLifecycle", green);
      server.publishServingView(producer);
      Field servingField = KnowledgeServer.class.getDeclaredField("servingView");
      servingField.setAccessible(true);
      Object view = servingField.get(server);
      Method attach = view.getClass().getDeclaredMethod("attachEncoderSet", EncoderSet.class);
      attach.setAccessible(true);
      attach.invoke(view, sourceOwner);
      set(server, "initialEncoderSet", sourceOwner);

      var model = new IndexGenerationManager.ModelArtifact(
          dir.resolve("unused.onnx").toAbsolutePath().normalize().toString(), "a".repeat(64));
      Map<String, IndexGenerationManager.ModelArtifact> models = Map.of("unused", model);
      var inputs = new SsotCommitMetadataSource.RuntimeFingerprintInputs(
          768, absent, absent, absent);
      byte[] canonical = "{}".getBytes(StandardCharsets.UTF_8);
      var target = new IndexTargetSnapshot(HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical)), "{}");
      set(server, "recordedCandidate", new RecordedIngestionLifecycle.RecordedCandidate(
          configuration, target, models));
      set(server, "recordedCandidateFingerprint",
          new CandidateIndexTargetCapture.CaptureResult(target, inputs, models));
    }

    private void composeCandidate() throws Exception {
      Method compose = KnowledgeServer.class.getDeclaredMethod("composeRecordedCandidateModels");
      compose.setAccessible(true);
      candidate = compose.invoke(server);
    }

    private void beginInPlaceBuild() throws Exception {
      Method begin = KnowledgeServer.class.getDeclaredMethod("beginInPlaceCandidateBuild");
      begin.setAccessible(true);
      begin.invoke(server);
    }

    private ComposeEvidence composeEvidence() {
      var captured = org.mockito.ArgumentCaptor.forClass(ComposeEvidence.class);
      verify(component).setLastCompose(captured.capture());
      assertEquals(FOOTPRINT, captured.getValue().footprintBytes());
      return captured.getValue();
    }

    private void recomposeAfterRefusal(Exception refusal) throws Exception {
      set(server, "inPlaceSourceHadModels", true);
      Method restore = KnowledgeServer.class.getDeclaredMethod(
          "recomposeSourceAfterCandidateRefusal", Exception.class);
      restore.setAccessible(true);
      restore.invoke(server, refusal);
    }

    @Override public void close() throws Exception {
      if (candidate != null) {
        Method close = candidate.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(candidate);
      }
      server.close();
      executors.close();
    }
  }
}
