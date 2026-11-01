/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ort.SessionHandle;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class NerFailureAndCoverageTest {
  @Test
  void conflictingOverlapPredictionsSelectContextAndStitchCompleteBioEntity() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    var encoding = ownerEncoding(23, "tail", 221);
    String[] tokens = encoding.getTokens();
    tokens[10] = "Mia"; tokens[11] = "Chen"; tokens[12] = "Jr";
    var tokenizer = mock(HuggingFaceTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    var calls = new AtomicInteger();
    when(lease.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      LongBuffer ids = inputs.get("input_ids").getLongBuffer();
      boolean first = calls.getAndIncrement() == 0;
      float[][][] logits = new float[1][ids.limit()][9];
      for (int token = 0; token < ids.limit(); token++) {
        long id = ids.get(token);
        // At the shared middle token, equal context keeps the first window's I-PER.
        // The outer overlap tokens choose opposite windows by their deeper context.
        int label = id == 210 ? (first ? 3 : 5)
            : id == 211 ? (first ? 4 : 6) : id == 212 ? (first ? 7 : 4) : 0;
        logits[0][token][label] = 10;
      }
      var result = mock(OrtSession.Result.class); var value = mock(OnnxTensor.class);
      when(value.getValue()).thenReturn(logits); when(result.get(0)).thenReturn(value);
      return result;
    });
    try (var tensors = mockStatic(OnnxTensor.class);
        var service = new NerService(new NerAssembly(sessions, new NerShape(14, false), tokenizer,
            BioTagDecoder.LabelMapping.bertBaseNer(), null),
            new NerConfig(true, Path.of("fixture"), 14, .5f, false, 0, 0))) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenAnswer(call -> {
            var tensor = mock(OnnxTensor.class);
            when(tensor.getLongBuffer()).thenReturn(call.getArgument(1));
            return tensor;
          });
      var result = service.extractEntities("Mia Chen Jr");
      assertEquals(List.of("Mia Chen Jr"), result.persons());
      assertTrue(result.organizations().isEmpty());
      assertTrue(result.locations().isEmpty());
      assertEquals(2, calls.get(), "both overlapping windows must participate");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"first", "middle", "last"})
  void anyFailedChunkRejectsTheWholeDocument(String position) throws Exception {
    String text = "Ada worked in London. ".repeat(800);
    int chunks = ChunkSplitter.split(text, 400, 50).size();
    int failAt = switch (position) { case "first" -> 1; case "last" -> chunks; default -> chunks / 2; };
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    var calls = new AtomicInteger();
    when(lease.run(anyMap())).thenAnswer(call -> {
      if (calls.incrementAndGet() == failAt) throw new OrtException("injected NER run failure");
      return output(3);
    });
    try (var tensors = mockStatic(OnnxTensor.class); var service = service(sessions, 3, 512)) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenReturn(mock(OnnxTensor.class));
      assertThrows(IllegalStateException.class, () -> service.extractEntities(text));
      assertEquals(failAt, calls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gpuBatchFallbackPropagatesFailureAndPreservesSuccessfulFallback(boolean fallbackFails) throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.isGpuAvailable()).thenReturn(true);
    when(sessions.acquire(any())).thenReturn(lease);
    var calls = new AtomicInteger();
    when(lease.run(anyMap())).thenAnswer(call -> {
      if (calls.incrementAndGet() == 1 || fallbackFails) throw new OrtException("injected run failure");
      return output(3);
    });
    try (var tensors = mockStatic(OnnxTensor.class); var service = service(sessions, 3, 512)) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenReturn(mock(OnnxTensor.class));
      if (fallbackFails) {
        assertThrows(IllegalStateException.class, () -> service.extractEntitiesBatch(List.of("one", "two")));
      } else {
        assertEquals(List.of(NerResult.EMPTY, NerResult.EMPTY), service.extractEntitiesBatch(List.of("one", "two")));
      }
    }
  }

  @Test
  void denseTokenStreamReachesInferenceThroughFramedOverlappingWindows() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    var observed = new HashSet<Long>();
    when(lease.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      LongBuffer ids = inputs.get("input_ids").getLongBuffer();
      assertEquals(101, ids.get(0));
      assertEquals(102, ids.get(ids.limit() - 1));
      assertTrue(ids.limit() <= 8);
      for (int i = 1; i < ids.limit() - 1; i++) observed.add(ids.get(i));
      return output(ids.limit());
    });
    try (var tensors = mockStatic(OnnxTensor.class); var service = service(sessions, 37, 8)) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenAnswer(call -> {
            var tensor = mock(OnnxTensor.class);
            LongBuffer value = call.getArgument(1);
            when(tensor.getLongBuffer()).thenReturn(value);
            return tensor;
          });
      assertEquals(NerResult.EMPTY, service.extractEntities("punctuation!中😀"));
      assertEquals(35, observed.size());
      assertTrue(observed.contains(35L), "the token-dense tail must be inferred");
    }
  }

  @Test
  void laterWindowEntitiesStayWithTheirDocumentInMixedSortedBatches() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    when(sessions.isGpuAvailable()).thenReturn(true);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    Encoding shortText = ownerEncoding(3, "Ada", 901);
    Encoding dense = ownerEncoding(37, "Lin", 235);
    when(tokenizer.encode(anyString())).thenAnswer(call -> "short".equals(call.getArgument(0)) ? shortText : dense);
    when(tokenizer.batchEncode(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      return texts.stream().map(text -> "short".equals(text) ? shortText : dense).toArray(Encoding[]::new);
    });
    when(lease.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      long[][] ids = (long[][]) inputs.get("input_ids").getValue();
      float[][][] logits = new float[ids.length][ids[0].length][9];
      for (int row = 0; row < ids.length; row++) {
        for (int token = 0; token < ids[row].length; token++) {
          long id = ids[row][token];
          logits[row][token][id == 212 ? 4 : id == 211 || id == 901 || id == 235 ? 3 : 0] = 10;
        }
      }
      var result = mock(OrtSession.Result.class); var value = mock(OnnxTensor.class);
      when(value.getValue()).thenReturn(logits); when(result.get(0)).thenReturn(value);
      return result;
    });
    try (var tensors = mockStatic(OnnxTensor.class);
        var service = new NerService(new NerAssembly(sessions, new NerShape(8, false), tokenizer,
            BioTagDecoder.LabelMapping.bertBaseNer(), null),
            new NerConfig(true, Path.of("fixture"), 8, .5f, false, 0, 0))) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenAnswer(call -> {
            LongBuffer flat = call.getArgument(1); long[] shape = call.getArgument(2);
            long[][] data = new long[(int) shape[0]][(int) shape[1]];
            for (long[] row : data) flat.get(row);
            var tensor = mock(OnnxTensor.class); when(tensor.getValue()).thenReturn(data); return tensor;
          });
      var results = service.extractEntitiesBatch(List.of("short", "dense", "short"));
      assertEquals(List.of("Ada"), results.get(0).persons());
      assertEquals(List.of("Mia Chen", "Lin"), results.get(1).persons());
      assertEquals(results.get(0), results.get(2));
    }
  }

  private static Encoding ownerEncoding(int count, String owner, long marker) {
    var encoding = mock(Encoding.class);
    long[] ids = new long[count]; long[] mask = new long[count]; long[] words = new long[count];
    String[] tokens = new String[count];
    for (int i = 0; i < count; i++) { ids[i] = 200 + i; mask[i] = 1; words[i] = i; tokens[i] = "word"; }
    ids[0] = 101; ids[count - 1] = 102; ids[count - 2] = marker;
    tokens[count - 2] = owner; words[0] = -1; words[count - 1] = -1;
    if (count > 12) { tokens[11] = "Mia"; tokens[12] = "Chen"; }
    when(encoding.getIds()).thenReturn(ids); when(encoding.getAttentionMask()).thenReturn(mask);
    when(encoding.getWordIds()).thenReturn(words); when(encoding.getTokens()).thenReturn(tokens);
    return encoding;
  }

  private static OrtSession.Result output(int length) throws OrtException {
    var output = mock(OrtSession.Result.class);
    var value = mock(OnnxTensor.class);
    float[][][] logits = new float[1][length][9];
    for (float[] token : logits[0]) token[0] = 10;
    when(value.getValue()).thenReturn(logits);
    when(output.get(0)).thenReturn(value);
    return output;
  }

  private static NerService service(SessionHandle sessions, int tokenCount, int capacity) {
    var encoding = mock(Encoding.class);
    long[] ids = new long[tokenCount];
    long[] masks = new long[tokenCount];
    long[] words = new long[tokenCount];
    String[] tokens = new String[tokenCount];
    for (int i = 0; i < tokenCount; i++) { ids[i] = i; masks[i] = 1; words[i] = i; tokens[i] = "word"; }
    ids[0] = 101; ids[tokenCount - 1] = 102;
    words[0] = -1; words[tokenCount - 1] = -1;
    when(encoding.getIds()).thenReturn(ids);
    when(encoding.getAttentionMask()).thenReturn(masks);
    when(encoding.getTokens()).thenReturn(tokens);
    when(encoding.getWordIds()).thenReturn(words);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    when(tokenizer.batchEncode(anyList())).thenAnswer(call -> {
      List<?> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), encoding).toArray(Encoding[]::new);
    });
    return new NerService(new NerAssembly(sessions, new NerShape(capacity, false), tokenizer,
        BioTagDecoder.LabelMapping.bertBaseNer(), null),
        new NerConfig(true, Path.of("fixture"), capacity, .5f, false, 0, 0));
  }
}
