/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.*;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import io.justsearch.configuration.resolved.ConfigStore;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.util.Base64;

/** M3a/M8: session acquisition failures must reach the document owner's retry policy. */
final class NerLazyAcquisitionFailureTest {
  @TempDir Path temp;
  private ConfigStore previousStore;
  private MockedStatic<OperationalMetrics> metrics;

  @BeforeEach
  void ownOptimizedCache() {
    previousStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", temp.resolve("cache").toString(),
        "justsearch.ort.optimized_cache_max_mb", "16"))));
    metrics = mockStatic(OperationalMetrics.class);
    metrics.when(OperationalMetrics::getInstance).thenReturn(mock(OperationalMetrics.class));
  }

  @AfterEach
  void restoreConfig() {
    try {
      if (metrics != null) metrics.close();
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previousStore);
    }
  }

  @Test
  void firstAndRepeatedCpuFallbacksReturnTheSameEntitiesAsEagerCpuOnColdAndWarmCaches() throws Exception {
    // An actual ONNX graph: two int64 inputs [1,3], constant logits [1,3,9] with
    // B-PER/O/B-LOC winners. Only tokenization is mocked, so ORT loading/run and BIO decoding run.
    Path model = Files.write(temp.resolve("model.onnx"), Base64.getDecoder().decode(
        "CApCAhAROucBEghuZXItdGVzdCp+CAEIAwgJEAFCBmxvZ2l0c0psAAAAAAAAAAAAAAAAAAAgQQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgQQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIEEAAAAAWhsKCWlucHV0X2lkcxIOCgwIBxIICgIIAQoCCANaIAoOYXR0ZW50aW9uX21hc2sSDgoMCAcSCAoCCAEKAggDYhwKBmxvZ2l0cxISChAIARIMCgIIAQoCCAMKAggJ"));
    NerResult expected = new NerResult(List.of("Ada"), List.of(), List.of("London"));
    for (int cacheState = 0; cacheState < 2; cacheState++) {
      for (ExecutionProvider provider : List.of(ExecutionProvider.CUDA, ExecutionProvider.CPU)) {
        var variant = VariantSelection.optimal(model, ModelPrecision.FP32, provider);
        var policy = ModelSessionPolicyResolver.resolve(EncoderRole.NER,
            TestResolvedConfigHelper.withDefaults(), HardwareProfile.gpuFull(12_000_000_000L), variant);
        SessionHandle handle = OrtSessionAssembler.buildManager("ner-entities",
            new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)), () -> false);
        try (NerService service = service(handle)) {
          for (int cycle = 0; cycle < 3; cycle++) {
            assertEquals(expected, service.extractEntities("Ada in London"));
            assertEquals(List.of(expected), service.extractEntitiesBatch(List.of("Ada in London")));
            handle.releaseGpu();
          }
        }
      }
    }
  }

  @Test
  void singleDocumentDoesNotTurnAcquisitionFailureIntoSuccessfulEmptyEntities() {
    SessionHandle sessions = failingSessions();
    try (NerService service = service(sessions)) {
      assertThrows(SessionTemporarilyUnavailableException.class,
          () -> service.extractEntities("Ada Lovelace worked in London"));
      verify(sessions).acquire(any());
    }
  }

  @Test
  void batchedGpuPathDoesNotTurnAcquisitionFailureIntoSuccessfulEmptyEntities() {
    SessionHandle sessions = failingSessions();
    when(sessions.isGpuAvailable()).thenReturn(true);
    try (NerService service = service(sessions)) {
      assertThrows(SessionTemporarilyUnavailableException.class,
          () -> service.extractEntitiesBatch(List.of("Ada in London", "Alan in Cambridge")));
      verify(sessions).acquire(any());
    }
  }

  @Test
  void batchCpuPathAlsoPropagatesAcquisitionFailure() {
    SessionHandle sessions = failingSessions();
    try (NerService service = service(sessions)) {
      assertThrows(SessionTemporarilyUnavailableException.class,
          () -> service.extractEntitiesBatch(List.of("Ada in London", "Alan in Cambridge")));
      verify(sessions).acquire(any());
    }
  }

  private static SessionHandle failingSessions() {
    SessionHandle sessions = mock(SessionHandle.class);
    var retirement = new java.util.concurrent.atomic.AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(sessions.retirementStatus()).thenAnswer(ignored -> retirement.get());
    doAnswer(ignored -> {
      retirement.set(SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    when(sessions.acquire(any())).thenThrow(
        new SessionTemporarilyUnavailableException("ner: CPU session creation failed"));
    return sessions;
  }

  private static NerService service(SessionHandle sessions) {
    Encoding encoding = mock(Encoding.class);
    when(encoding.getIds()).thenReturn(new long[] {1, 2, 3});
    when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1, 1});
    when(encoding.getTokens()).thenReturn(new String[] {"Ada", "in", "London"});
    when(encoding.getWordIds()).thenReturn(new long[] {0, 1, 2});
    HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    when(tokenizer.batchEncode(anyList())).thenReturn(new Encoding[] {encoding, encoding});
    var assembly = new NerAssembly(sessions, new NerShape(512, false), tokenizer,
        BioTagDecoder.LabelMapping.bertBaseNer(), null);
    return new NerService(assembly, new NerConfig(true, Path.of("missing/ner"), 512, .5f, true, 0, 2048));
  }
}
