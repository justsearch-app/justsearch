/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.ner.NerConfig;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.ner.NerShape;
import io.justsearch.ort.SessionHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** B-S7: failed downstream cleanup must remain reachable even after admission is closed. */
class WrapperCloseRetryTest {
  @ParameterizedTest
  @ValueSource(strings = {"splade", "embedding", "ner"})
  void failedCloseCanBeRetriedWithoutLeakingTheTokenizer(String role) throws Exception {
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    var calls = new AtomicInteger();
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      if (calls.incrementAndGet() == 1) {
        status.set(SessionHandle.RetirementStatus.REFUSED);
        throw new IllegalStateException("injected native close failure");
      }
      status.set(SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    AutoCloseable wrapper;
    switch (role) {
      case "splade" -> wrapper = new SpladeEncoder(sessions,
          new SpladeShape(512, false, SpladeEncoder.OutputFormat.MLM_LOGITS, "logits"),
          tokenizer, mock(Vocabulary.class), null, SpladeConfig.DISABLED);
      case "embedding" -> {
        var encoder = new OnnxEmbeddingEncoder(sessions,
            new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 512, 0),
            tokenizer);
        wrapper = EmbeddingService.createWithBackend(new OnnxEmbeddingBackend(encoder, 0, 512),
            embeddingConfig());
      }
      case "ner" -> wrapper = new NerService(new NerAssembly(sessions, new NerShape(512, false),
          tokenizer, null, null), NerConfig.DISABLED);
      default -> throw new AssertionError(role);
    }
    // NER used to swallow the failure and mark itself closed; the retry still must release.
    if (role.equals("ner")) {
      try { wrapper.close(); } catch (IllegalStateException expected) { /* retained for retry */ }
    } else {
      assertThrows(IllegalStateException.class, wrapper::close);
    }
    assertFalse(status.get() == SessionHandle.RetirementStatus.RETIRED);
    assertDoesNotThrow(wrapper::close);
    assertDoesNotThrow(wrapper::close);
    verify(tokenizer).close();
  }
  @org.junit.jupiter.api.Test
  void checkedBackendFailureRetainsBackendForRetry() throws Exception {
    var backend = mock(io.justsearch.aibackend.backend.AiBackend.class);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    org.mockito.Mockito.doThrow(new io.justsearch.aibackend.backend.BackendException("close refused"))
        .doAnswer(ignored -> { tokenizer.close(); return null; }).when(backend).close();
    var service = EmbeddingService.createWithBackend(backend, embeddingConfig());
    assertThrows(IllegalStateException.class, service::close);
    assertFalse(service.isAvailable());
    assertDoesNotThrow(service::close);
    assertDoesNotThrow(service::close);
    verify(tokenizer).close();
  }

  @org.junit.jupiter.api.Test
  void retryKeepsSuccessfullyFlushedEvidenceUnchanged(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
      throws Exception {
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    var calls = new AtomicInteger();
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      if (calls.incrementAndGet() == 1) {
        status.set(SessionHandle.RetirementStatus.REFUSED);
        throw new IllegalStateException("native close refused");
      }
      status.set(SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var evidencePath = dir.resolve("truncation.json");
    var config = new SpladeConfig(true, dir, 512, false, 0, 0, "onnx", "log1p");
    var encoder = new SpladeEncoder(sessions,
        new SpladeShape(512, false, SpladeEncoder.OutputFormat.MLM_LOGITS, "logits"),
        tokenizer, mock(Vocabulary.class), evidencePath, config);
    var field = SpladeEncoder.class.getDeclaredField("truncationEvidence");
    field.setAccessible(true);
    ((SpladeTruncationEvidence) field.get(encoder)).record(600);
    assertThrows(IllegalStateException.class, encoder::close);
    byte[] firstFlush = java.nio.file.Files.readAllBytes(evidencePath);
    encoder.close();
    encoder.close();
    org.junit.jupiter.api.Assertions.assertArrayEquals(firstFlush, java.nio.file.Files.readAllBytes(evidencePath));
    verify(tokenizer).close();
  }

  /** B-P3/8/10: a GPU yield retains the standalone owner until full unload. */
  @org.junit.jupiter.api.Test
  void repeatedGpuHandoffsKeepEmbeddingTokenizerUntilFullUnload() {
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> { status.set(SessionHandle.RetirementStatus.RETIRED); return null; })
        .when(sessions).close();
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var encoder = new OnnxEmbeddingEncoder(sessions,
        new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 512, 0),
        tokenizer);
    var service = EmbeddingService.createWithBackend(new OnnxEmbeddingBackend(encoder, 0, 512),
        embeddingConfig());
    for (int handoff = 0; handoff < 4; handoff++) {
      service.releaseGpuSession();
      verify(tokenizer, org.mockito.Mockito.never()).close();
    }
    verify(sessions, org.mockito.Mockito.times(4)).releaseGpu();
    service.close();
    service.close();
    verify(tokenizer).close();
  }

  private static EmbeddingConfig embeddingConfig() {
    return new EmbeddingConfig(true, java.nio.file.Path.of("fixture"), "onnx", false, 0, 0,
        512, false, 512);
  }

}
