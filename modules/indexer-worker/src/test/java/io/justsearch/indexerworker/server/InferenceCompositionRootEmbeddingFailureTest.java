/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.BertNerInference;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;

final class InferenceCompositionRootEmbeddingFailureTest {
  @org.junit.jupiter.api.Test
  void embeddingFailureRetainsOriginalCause(@TempDir Path dir) throws Exception {
    assertFailedAssemblyOwnership(EncoderRole.EMBEDDING, dir, true);
  }

  @org.junit.jupiter.api.Test
  void inputCaptureFailureRetainsOriginalCause(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("model.onnx"), "model-witness");
    Files.writeString(dir.resolve("tokenizer.json"), "unused-tokenizer");
    Files.writeString(dir.resolve("model_manifest.json"), "{not-json");
    var cfg = TestResolvedConfigHelper.fromEntries(java.util.Map.of(
        EnvRegistry.AI_EMBED_ENABLED.configKey(), "true",
        EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), dir.toString(),
        EnvRegistry.NER_ENABLED.configKey(), "false",
        EnvRegistry.SPLADE_ENABLED.configKey(), "false",
        EnvRegistry.BGE_M3_ENABLED.configKey(), "false",
        EnvRegistry.RERANK_ENABLED.configKey(), "false",
        EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false"));
    var plan = new AtomicReference<InferenceCompositionRoot.CapturedCompositionPlan>();
    var surface = InferenceCompositionRoot.compose(
        EncoderConfigurationProjection.from(cfg), HardwareProfile.cpuOnly(), null, null,
        () -> false, io.justsearch.ort.telemetry.OrtSessionTelemetryEvents.NOOP,
        null, null, ignored -> {}, plan::set);
    Object cause = plan.get().indexPlan().role(EncoderRole.EMBEDDING).captureFailure();
    org.junit.jupiter.api.Assertions.assertInstanceOf(Throwable.class, cause,
        "Capture must retain the actual failure rather than only its text");
    assertSame(cause, org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> surface.getClass().getMethod("embeddingFailure").invoke(surface)));
    assertTrue(surface.embedding().isEmpty());
  }

  private static void assertFailedAssemblyOwnership(EncoderRole role, Path dir, boolean refuse)
      throws Exception {
    Files.writeString(dir.resolve("model.onnx"), "model-witness");
    Files.writeString(dir.resolve("tokenizer.json"), "malformed-tokenizer");
    Files.writeString(dir.resolve("vocab.txt"), "[UNK]");
    var entries = new HashMap<String, String>();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.NER_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.RERANK_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "false");
    switch (role) {
      case EMBEDDING -> {
        entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
        entries.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), dir.toString());
      }
      case NER -> {
        entries.put(EnvRegistry.NER_ENABLED.configKey(), "true");
        entries.put(EnvRegistry.NER_MODEL_PATH.configKey(), dir.toString());
      }
      case BGE_M3 -> {
        entries.put(EnvRegistry.SPARSE_MODEL.configKey(), "bge-m3");
        entries.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "true");
        entries.put(EnvRegistry.BGE_M3_MODEL_PATH.configKey(), dir.toString());
      }
      case SPLADE -> {
        entries.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
        entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "true");
        entries.put(EnvRegistry.SPLADE_MODEL_PATH.configKey(), dir.toString());
      }
      default -> throw new AssertionError(role);
    }
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    var closes = new AtomicInteger();
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      if (closes.incrementAndGet() == 1 && refuse) {
        status.set(SessionHandle.RetirementStatus.REFUSED);
        throw new IllegalStateException("native close refused");
      }
      status.set(SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    var assemblyFailure = new IllegalArgumentException("malformed tokenizer");
    try (var assembler = mockStatic(OrtSessionAssembler.class);
        var embedding = mockStatic(OnnxEmbeddingEncoder.class);
        var ner = mockStatic(BertNerInference.class);
        var bge = mockStatic(BgeM3Encoder.class);
        var splade = mockStatic(SpladeEncoder.class)) {
      assembler.when(() -> OrtSessionAssembler.buildManager(any(), any(), any(), any()))
          .thenReturn(sessions);
      embedding.when(() -> OnnxEmbeddingEncoder.buildAssembly(any(), any(), anyInt(), anyInt(),
          anyBoolean(), any())).thenThrow(assemblyFailure);
      ner.when(() -> BertNerInference.buildAssembly(any(), any(), anyInt(), anyBoolean(), any()))
          .thenThrow(assemblyFailure);
      bge.when(() -> BgeM3Encoder.buildAssembly(any(), any())).thenThrow(assemblyFailure);
      splade.when(() -> SpladeEncoder.buildAssembly(any(), any(), any()))
          .thenThrow(assemblyFailure);

      var surface = InferenceCompositionRoot.compose(
          TestResolvedConfigHelper.fromEntries(entries), HardwareProfile.cpuOnly(), null, null,
          () -> false);

      Object recorded = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> surface.getClass().getMethod("embeddingFailure").invoke(surface),
          "A failed embedding assembly must retain its actual load cause");
      assertSame(assemblyFailure, recorded);
      var marked = surface.withUnavailableQueryRoles(java.util.Set.of(EncoderRole.CITATION));
      assertSame(assemblyFailure, org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> marked.getClass().getMethod("embeddingFailure").invoke(marked)));
      var partition = surface.partitionQueryRoles(EncoderConfigurationProjection.from(
          TestResolvedConfigHelper.fromEntries(entries)));
      assertSame(assemblyFailure, org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> partition.index().getClass().getMethod("embeddingFailure").invoke(partition.index())));
      org.junit.jupiter.api.Assertions.assertNull(org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> partition.query().getClass().getMethod("embeddingFailure").invoke(partition.query())));
      verify(sessions).close();
      assembler.verify(() -> OrtSessionAssembler.buildManager(any(), any(), any(), any()));
      assertEquals(List.of(sessions), surface.handles());
      assertTrue(surface.policies().models().containsKey(role));
      assertTrue(surface.embedding().isEmpty() && surface.ner().isEmpty()
          && surface.bgeM3().isEmpty() && surface.splade().isEmpty());
      if (refuse) {
        assertEquals(SessionHandle.RetirementStatus.REFUSED, surface.retirementStatus());
        assertSame(sessions, surface.handles().get(0));
        assertEquals(1, assemblyFailure.getSuppressed().length);
        surface.close();
        verify(sessions, times(2)).close();
      }
      assertEquals(SessionHandle.RetirementStatus.RETIRED, surface.retirementStatus());
    }
  }
}
