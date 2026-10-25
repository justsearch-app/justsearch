/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtException;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class EmbeddingServiceSpanTest {
  private static final EmbeddingConfig CONFIG =
      new EmbeddingConfig(false, Path.of("unused-span-test-model"), "onnx", false, 0, 0, 512, false, 0);
  private static EmbeddingService service(OnnxEmbeddingEncoder encoder, String prefix) {
    return EmbeddingService.createWithBackend(new OnnxEmbeddingBackend(encoder, 0, 512),
        CONFIG, NoopEmbeddingTelemetryEvents.INSTANCE, prefix, "");
  }

  @Test
  void invalidOriginalBoundsNeverBecomeValidByPrefixing() throws Exception {
    var encoder = mock(OnnxEmbeddingEncoder.class);
    try (var service = service(encoder, "search_document: ")) {
      int[][][] invalid = {null, {null}, {{}}, {{0}}, {{0, 1, 2}}, {{-1, 1}}, {{3, 1}},
          {{0, 5}}, {{Integer.MIN_VALUE, 0}}, {{0, Integer.MAX_VALUE}}, {{2, 3}}, {{1, 2}}, {{2, 2}}};
      assertNull(service.embedWithSpans(null, new int[0][]));
      for (int[][] spans : invalid) {
        assertNull(assertDoesNotThrow(() -> service.embedWithSpans("a😀z", spans)));
      }
      verify(encoder, never()).embedWithSpans(any(), any());
    }
  }

  @Test
  void prefixesShiftUtf16BoundsOnceWithoutMutatingRows() throws Exception {
    String content = "red 😀 blue";
    for (String prefix : new String[] {"", "search_document: ", "😀𠀀 "}) {
      var encoder = mock(OnnxEmbeddingEncoder.class);
      var result = new OnnxEmbeddingEncoder.EmbedResult(new float[] {1},
          List.of(new float[] {1}, new float[] {2}, new float[] {3}), 3);
      when(encoder.embedWithSpans(anyString(), any())).thenReturn(result);
      try (var service = service(encoder, prefix)) {
        int[][] original = {{7, 11}, {7, 11}, {0, 0}};
        var actual = service.embedWithSpans(content, original);
        assertNotNull(actual);
        assertSame(result.chunkVectors(), actual.chunkVectors());
        assertEquals(3, actual.chunkCount());
        var spans = ArgumentCaptor.forClass(int[][].class);
        verify(encoder).embedWithSpans(eq(prefix + content), spans.capture());
        assertArrayEquals(new int[] {7 + prefix.length(), 11 + prefix.length()}, spans.getValue()[0]);
        assertArrayEquals(spans.getValue()[0], spans.getValue()[1]);
        assertArrayEquals(new int[] {prefix.length(), prefix.length()}, spans.getValue()[2]);
        assertArrayEquals(new int[] {7, 11}, original[0]);
        assertArrayEquals(new int[] {7, 11}, original[1]);
        assertArrayEquals(new int[] {0, 0}, original[2]);
        assertNotNull(service.embedWithSpans(content, new int[0][]));
      }
    }
  }

  @Test
  void malformedOriginalIsRefusedEvenIfPrefixJoinFormsAValidPair() throws Exception {
    var encoder = mock(OnnxEmbeddingEncoder.class);
    when(encoder.embedWithSpans(anyString(), any())).thenReturn(
        new OnnxEmbeddingEncoder.EmbedResult(new float[] {1}, List.of(new float[] {1}), 1));
    try (var service = service(encoder, String.valueOf((char) 0xd83d))) {
      String content = String.valueOf((char) 0xde00) + " blue";
      assertNull(service.embedWithSpans(content, new int[][] {{0, content.length()}}));
      verify(encoder, never()).embedWithSpans(any(), any());
      assertNull(service.embedWithSpans(content, new int[][] {{2, content.length()}}));
      verify(encoder, never()).embedWithSpans(anyString(), any());
      assertNotNull(service.embedWithSpans(content, new int[0][]));
      verify(encoder).embedWithSpans(eq("😀 blue"), argThat(rows -> rows.length == 0));
    }
  }

  @Test
  void malformedOriginalAndPrefixAreRefusedOnlyWhenCoordinatesAreRequested() throws Exception {
    for (char surrogate : new char[] {(char) 0xd83d, (char) 0xde00}) {
      var encoder = mock(OnnxEmbeddingEncoder.class);
      when(encoder.embedWithSpans(anyString(), any())).thenReturn(
          new OnnxEmbeddingEncoder.EmbedResult(new float[] {1}, List.of(), 0));
      try (var service = service(encoder, "")) {
        String text = surrogate + " blue";
        assertNull(service.embedWithSpans(text, new int[][] {{2, text.length()}}));
        assertNull(service.embedWithSpans(text, new int[][] {{0, 0}}));
        verify(encoder, never()).embedWithSpans(anyString(), any());
        assertNotNull(service.embedWithSpans(text, new int[0][]));
        verify(encoder).embedWithSpans(eq(text), argThat(rows -> rows.length == 0));
      }
      var prefixedEncoder = mock(OnnxEmbeddingEncoder.class);
      when(prefixedEncoder.embedWithSpans(anyString(), any())).thenReturn(
          new OnnxEmbeddingEncoder.EmbedResult(new float[] {1}, List.of(), 0));
      try (var service = service(prefixedEncoder, surrogate + " ")) {
        assertNull(service.embedWithSpans("blue", new int[][] {{0, 4}}));
        verify(prefixedEncoder, never()).embedWithSpans(anyString(), any());
        assertNotNull(service.embedWithSpans("blue", new int[0][]));
      }
    }
  }

  @Test
  void unavailableClosedUnsupportedAndNativeFailuresKeepTheirBehavior() throws Exception {
    var encoder = mock(OnnxEmbeddingEncoder.class);
    var failure = new OrtException("span inference failure");
    when(encoder.embedWithSpans(anyString(), any())).thenThrow(failure);
    try (var service = service(encoder, "")) {
      var thrown = assertThrows(RuntimeException.class,
          () -> service.embedWithSpans("blue", new int[0][]));
      assertSame(failure, thrown.getCause());
      service.close();
      assertNull(service.embedWithSpans("blue", new int[0][]));
    }
    try (var service = EmbeddingService.createWithBackend(mock(AiBackend.class), CONFIG)) {
      assertNull(service.embedWithSpans("blue", new int[0][]));
    }
    try (var service = new EmbeddingService(CONFIG.modelPath(), CONFIG)) {
      assertNull(service.embedWithSpans("blue", new int[0][]));
    }
  }
}
