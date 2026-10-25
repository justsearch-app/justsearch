/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelCapabilityResolver;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Factory failures use the same retained surface and retry as ordinary native retirement. */
class InferenceCompositionRootPartialAssemblyTest {
  @ParameterizedTest
  @CsvSource({"EMBEDDING,false", "EMBEDDING,true", "NER,false", "NER,true",
      "BGE_M3,false", "BGE_M3,true", "SPLADE,false", "SPLADE,true",
      "RERANKER,false", "RERANKER,true", "CITATION,false", "CITATION,true"})
  void postTokenizerFailureRetiresSessionsBeforeTokenizerAndRefusalKeepsBoth(EncoderRole role, boolean refuse,
      @TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("model.onnx"), "model-witness");
    Files.writeString(dir.resolve("tokenizer.json"), "tokenizer-witness");
    var entries = new HashMap<String, String>();
    for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.NER_ENABLED, EnvRegistry.SPLADE_ENABLED,
        EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED, EnvRegistry.CITATION_SCORER_ENABLED}) {
      entries.put(key.configKey(), "false");
    }
    EnvRegistry enabled;
    EnvRegistry path;
    switch (role) {
      case EMBEDDING -> { enabled = EnvRegistry.AI_EMBED_ENABLED; path = EnvRegistry.EMBED_ONNX_MODEL_PATH; }
      case NER -> { enabled = EnvRegistry.NER_ENABLED; path = EnvRegistry.NER_MODEL_PATH; }
      case BGE_M3 -> {
        enabled = EnvRegistry.BGE_M3_ENABLED; path = EnvRegistry.BGE_M3_MODEL_PATH;
        entries.put(EnvRegistry.SPARSE_MODEL.configKey(), "bge-m3");
      }
      case SPLADE -> { enabled = EnvRegistry.SPLADE_ENABLED; path = EnvRegistry.SPLADE_MODEL_PATH; }
      case RERANKER -> { enabled = EnvRegistry.RERANK_ENABLED; path = EnvRegistry.RERANK_MODEL_PATH; }
      case CITATION -> { enabled = EnvRegistry.CITATION_SCORER_ENABLED; path = EnvRegistry.CITATION_SCORER_MODEL_PATH; }
      default -> throw new AssertionError(role);
    }
    entries.put(enabled.configKey(), "true");
    entries.put(path.configKey(), dir.toString());
    var configuration = TestResolvedConfigHelper.fromEntries(entries);
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    var refusal = new AtomicBoolean(refuse);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      status.set(refusal.get() ? SessionHandle.RetirementStatus.REFUSED
          : SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var releases = new AtomicInteger();
    doAnswer(ignored -> {
      assertEquals(SessionHandle.RetirementStatus.RETIRED, status.get());
      assertEquals(1, releases.incrementAndGet(), "exactly one native release");
      return null;
    }).when(tokenizer).close();
    var cause = new IllegalStateException("capability resolution failed after tokenizer allocation");
    try (var assembler = mockStatic(OrtSessionAssembler.class);
        var tokenizers = mockStatic(HuggingFaceTokenizer.class);
        var capabilities = mockStatic(ModelCapabilityResolver.class, CALLS_REAL_METHODS);
        var vocabulary = mockStatic(ai.djl.modality.nlp.DefaultVocabulary.class)) {
      vocabulary.when(ai.djl.modality.nlp.DefaultVocabulary::builder).thenThrow(cause);
      assembler.when(() -> OrtSessionAssembler.buildManager(any(), any(), any(), any()))
          .thenReturn(sessions);
      if (role == EncoderRole.RERANKER || role == EncoderRole.CITATION) {
        assembler.when(() -> OrtSessionAssembler.probeModelNames(any(), any()))
            .thenThrow(new ai.onnxruntime.OrtException("probe failed after tokenizer allocation"));
      } else {
        assembler.when(() -> OrtSessionAssembler.probeModelNames(any(), any()))
            .thenReturn(new OrtSessionAssembler.ProbedNames(Set.of("input_ids"), Set.of("logits")));
      }
      tokenizers.when(() -> HuggingFaceTokenizer.newInstance(any(Path.class), any(Map.class)))
          .thenReturn(tokenizer);
      tokenizers.when(() -> HuggingFaceTokenizer.newInstance(any(Path.class))).thenReturn(tokenizer);
      capabilities.when(() -> ModelCapabilityResolver.resolve(any(), any(), any(), any(),
          anyBoolean(), any())).thenThrow(cause);
      var surface = InferenceCompositionRoot.compose(configuration, HardwareProfile.cpuOnly(),
          null, null, () -> false);
      assertTrue(surface.embedding().isEmpty());
      assertTrue(surface.ner().isEmpty() && surface.splade().isEmpty() && surface.bgeM3().isEmpty()
          && surface.reranker().isEmpty() && surface.citation().isEmpty());
      if (role == EncoderRole.EMBEDDING) assertSame(cause, surface.embeddingFailure());
      assertEquals(refuse ? 0 : 1, releases.get());
      if (refuse) {
        assertThrows(IllegalStateException.class, surface::close);
        assertEquals(0, releases.get());
        refusal.set(false);
      }
      // A metadata copy and partition share the failed role's retry ownership too.
      var projection = EncoderConfigurationProjection.from(configuration);
      var partition = surface.withUnavailableQueryRoles(Set.of(EncoderRole.CITATION))
          .partitionQueryRoles(projection);
      partition.query().close();
      partition.index().close();
      surface.close();
      assertEquals(1, releases.get());
      verify(tokenizer).close();
    }
  }
}
