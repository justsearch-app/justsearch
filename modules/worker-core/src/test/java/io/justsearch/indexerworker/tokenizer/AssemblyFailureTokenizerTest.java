/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.tokenizer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtException;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.BertNerInference;
import io.justsearch.ort.ModelCapabilityResolver;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** B-S2/6: the real factories clean up failures after native tokenizer creation. */
class AssemblyFailureTokenizerTest {
  @ParameterizedTest
  @ValueSource(strings = {"embedding-capability", "ner-capability", "ner-probe"})
  void factoryFailureReleasesItsTokenizer(String path, @TempDir Path dir) throws Exception {
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var sessions = mock(SessionHandle.class);
    var status = new java.util.concurrent.atomic.AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> { status.set(SessionHandle.RetirementStatus.RETIRED); return null; })
        .when(sessions).close();
    var failure = new IllegalStateException("injected capability failure");
    try (var tokenizers = mockStatic(HuggingFaceTokenizer.class);
        var probes = mockStatic(OrtSessionAssembler.class);
        var capabilities = mockStatic(ModelCapabilityResolver.class)) {
      tokenizers.when(() -> HuggingFaceTokenizer.newInstance(eq(dir.resolve("tokenizer.json")),
          any(Map.class))).thenReturn(tokenizer);
      if (path.equals("ner-probe")) {
        probes.when(() -> OrtSessionAssembler.probeModelNames(any(), any()))
            .thenThrow(new OrtException("injected NER probe failure"));
        var thrown = assertThrows(Exception.class,
            () -> BertNerInference.buildAssembly(sessions, dir, 512, true));
        retireAfterFailure(thrown, sessions, tokenizer);
      } else {
        probes.when(() -> OrtSessionAssembler.probeModelNames(any(), any()))
            .thenReturn(new OrtSessionAssembler.ProbedNames(Set.of("input_ids"), Set.of("logits")));
        capabilities.when(() -> ModelCapabilityResolver.resolve(any(), any(), any(), any(),
            anyBoolean(), any())).thenThrow(failure);
        var thrown = assertThrows(Exception.class, () -> {
          if (path.startsWith("embedding")) {
            OnnxEmbeddingEncoder.buildAssembly(sessions, dir, 512, 512, true);
          } else {
            BertNerInference.buildAssembly(sessions, dir, 512, true);
          }
        });
        assertSame(failure, thrown.getCause() == null ? thrown : thrown.getCause());
        retireAfterFailure(thrown, sessions, tokenizer);
      }
      verify(tokenizer).close();
    }
  }
  private static void retireAfterFailure(Exception failure, SessionHandle sessions,
      HuggingFaceTokenizer tokenizer) throws Exception {
    verify(tokenizer, never()).close();
    sessions.close();
    if (failure instanceof AutoCloseable owner) {
      owner.close();
      owner.close();
    }
  }

  @org.junit.jupiter.api.Test
  void failureBeforeAnySessionExistsClosesTheTokenizerDirectly(@TempDir Path dir) throws Exception {
    var tokenizer = mock(HuggingFaceTokenizer.class);
    try (var tokenizers = mockStatic(HuggingFaceTokenizer.class)) {
      tokenizers.when(() -> HuggingFaceTokenizer.newInstance(eq(dir.resolve("tokenizer.json")),
          any(Map.class))).thenReturn(tokenizer);
      assertThrows(NullPointerException.class,
          () -> BertNerInference.buildAssembly(null, dir, 512, true));
      verify(tokenizer).close();
    }
  }

  @org.junit.jupiter.api.Test
  void failedTokenizerCloseBeforeSessionsRetainsItsOwnerForRetry(@TempDir Path dir) throws Exception {
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var releases = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doThrow(new IllegalStateException("tokenizer close refused"))
        .doAnswer(ignored -> { releases.incrementAndGet(); return null; }).when(tokenizer).close();
    try (var tokenizers = mockStatic(HuggingFaceTokenizer.class)) {
      tokenizers.when(() -> HuggingFaceTokenizer.newInstance(eq(dir.resolve("tokenizer.json")),
          any(Map.class))).thenReturn(tokenizer);
      var failure = assertThrows(Exception.class,
          () -> BertNerInference.buildAssembly(null, dir, 512, true));
      if (failure instanceof AutoCloseable owner) {
        owner.close();
        owner.close();
      }
      org.junit.jupiter.api.Assertions.assertEquals(1, releases.get());
    }
  }

}
