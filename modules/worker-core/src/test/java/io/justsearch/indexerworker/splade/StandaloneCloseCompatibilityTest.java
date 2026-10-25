/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import io.justsearch.indexerworker.bgem3.BgeM3Config;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Shape;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.BertNerInference;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.ner.NerConfig;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.ner.NerShape;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StandaloneCloseCompatibilityTest {
  static Stream<Arguments> unconfirmedRetirements() {
    return Stream.of("embedding", "embedding-service", "splade", "bge-m3", "ner", "ner-service")
        .flatMap(role -> Stream.of(null, SessionHandle.RetirementStatus.ACTIVE,
            SessionHandle.RetirementStatus.RETIRING, SessionHandle.RetirementStatus.REFUSED)
            .map(status -> Arguments.of(role, status)));
  }

  @ParameterizedTest
  @MethodSource("unconfirmedRetirements")
  void unconfirmedRetirementIsQuietAndRetainsTokenizerForRetry(
      String role, SessionHandle.RetirementStatus initial) throws Exception {
    var sessions = mock(SessionHandle.class);
    when(sessions.status()).thenReturn(io.justsearch.ort.OrtCudaStatus.notConfigured());
    var status = new AtomicReference<>(initial);
    var allowRetirement = new AtomicBoolean();
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      if (allowRetirement.get()) status.set(SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    doAnswer(ignored -> {
      assertEquals(SessionHandle.RetirementStatus.RETIRED, status.get());
      return null;
    }).when(tokenizer).close();
    var wrapper = wrapper(role, sessions, tokenizer);

    assertDoesNotThrow(wrapper::close);
    verify(tokenizer, never()).close();
    allowRetirement.set(true);
    assertDoesNotThrow(wrapper::close);
    assertDoesNotThrow(wrapper::close);
    verify(tokenizer).close();
  }

  @Test
  void nerServiceKeepsItsNonFatalCloseAndRetriesAnException() {
    var sessions = mock(SessionHandle.class);
    when(sessions.status()).thenReturn(io.justsearch.ort.OrtCudaStatus.notConfigured());
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doThrow(new IllegalStateException("native close refused"))
        .doAnswer(ignored -> {
          status.set(SessionHandle.RetirementStatus.RETIRED);
          return null;
        }).when(sessions).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var service = wrapper("ner-service", sessions, tokenizer);
    assertDoesNotThrow(service::close);
    verify(tokenizer, never()).close();
    assertDoesNotThrow(service::close);
    verify(tokenizer).close();
  }

  private static AutoCloseable wrapper(String role, SessionHandle sessions,
      HuggingFaceTokenizer tokenizer) {
    var shape = new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL,
        512, 0);
    return switch (role) {
      case "embedding" -> new OnnxEmbeddingEncoder(sessions, shape, tokenizer);
      case "embedding-service" -> EmbeddingService.createWithBackend(new OnnxEmbeddingBackend(
          new OnnxEmbeddingEncoder(sessions, shape, tokenizer), 0, 512),
          new EmbeddingConfig(true, Path.of("fixture"), "onnx", false, 0, 0, 512, false, 512));
      case "splade" -> new SpladeEncoder(sessions, new SpladeShape(512, false,
          SpladeEncoder.OutputFormat.MLM_LOGITS, "logits"), tokenizer, mock(Vocabulary.class),
          null, SpladeConfig.DISABLED);
      case "bge-m3" -> new BgeM3Encoder(sessions, new BgeM3Shape(512, new String[0]), tokenizer,
          BgeM3Config.DISABLED);
      case "ner" -> new BertNerInference(sessions, new NerShape(512, false), tokenizer);
      case "ner-service" -> new NerService(new NerAssembly(sessions, new NerShape(512, false),
          tokenizer, null, null), NerConfig.DISABLED);
      default -> throw new AssertionError(role);
    };
  }
}
