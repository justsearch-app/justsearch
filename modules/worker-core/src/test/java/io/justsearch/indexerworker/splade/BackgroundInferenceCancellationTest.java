/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import io.justsearch.indexerworker.inference.LocalSessionAcquisition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.*;
import ai.djl.modality.nlp.Vocabulary;
import ai.onnxruntime.*;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.embed.onnx.*;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.*;
import io.justsearch.ort.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BackgroundInferenceCancellationTest {
  private Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator> registry;
  private Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator> previous;

  @BeforeEach @SuppressWarnings("unchecked")
  void saveMetrics() throws Exception {
    var field = io.justsearch.indexerworker.metrics.OperationalMetrics.class.getDeclaredField("encoderAccumulators");
    field.setAccessible(true);
    registry = (Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator>)
        field.get(io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance());
    previous = new HashMap<>(registry);
  }

  @AfterEach void restoreMetrics() { registry.clear(); registry.putAll(previous); }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"ner-chunks", "ner-windows", "ner-overflow", "ner-batch", "ner-fallback",
      "splade-windows", "splade-long-batch", "splade-subbatches", "splade-fallback", "dense-slice", "bge-subbatches"})
  void hardStopPreventsTheNextNativeCallAndDiscardsTheOperation(String path) throws Exception {
    exercise(path, true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"ner-chunks", "ner-windows", "ner-overflow", "ner-batch", "ner-fallback",
      "splade-windows", "splade-long-batch", "splade-subbatches", "splade-fallback", "dense-slice", "bge-subbatches"})
  void noHardStopCompletesEveryMultiCallPath(String path) throws Exception {
    exercise(path, false);
  }

  private void exercise(String path, boolean cancel) throws Exception {
    var sessions = mock(SessionHandle.class);
    var nativeSession = mock(OrtSession.class);
    var calls = new AtomicInteger();
    var elapsed = new AtomicLong();
    var stopped = new AtomicBoolean();
    var recorder = new AtomicReference<OrtRunRecorder>(OrtRunRecorder.NOOP);
    doAnswer(call -> { recorder.set(call.getArgument(0)); return null; })
        .when(sessions).setOrtRunRecorder(any());
    when(sessions.isGpuAvailable()).thenReturn(path.startsWith("ner-") && !path.equals("ner-chunks"));
    when(sessions.status()).thenReturn(OrtCudaStatus.notConfigured());
    when(sessions.acquire(any())).thenAnswer(call -> new SessionHandle.Lease(nativeSession, null,
        () -> {}, !path.endsWith("fallback"), ns -> { elapsed.addAndGet(ns); recorder.get().recordOrtRunNs(ns); },
        call.getArgument(0)));
    when(sessions.acquireCpu(any())).thenAnswer(call -> new SessionHandle.Lease(nativeSession, null,
        () -> {}, true, ns -> { elapsed.addAndGet(ns); recorder.get().recordOrtRunNs(ns); }, call.getArgument(0)));
    boolean windowed = path.contains("windows") || path.contains("overflow") || path.equals("splade-long-batch");
    int tokenCount = path.equals("dense-slice") ? 17000 : windowed ? 37 : 3;
    var encoding = encoding(tokenCount);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    when(tokenizer.batchEncode(anyList())).thenAnswer(call -> Collections.nCopies(
        ((List<?>) call.getArgument(0)).size(), encoding).toArray(Encoding[]::new));
    when(nativeSession.run(anyMap())).thenAnswer(call -> {
      int number = calls.incrementAndGet();
      if (cancel && number == 1) stopped.set(true);
      if (number == 1 && path.endsWith("fallback")) throw new OrtException(path.startsWith("splade-")
          ? "AllocateRawInternal Available memory of 1 is smaller than requested bytes of 2" : "injected inference error");
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      long[][] ids = (long[][]) inputs.get("input_ids").getValue();
      int batch = ids.length, length = ids[0].length;
      var result = mock(OrtSession.Result.class);
      var value = mock(OnnxTensor.class);
      if (path.startsWith("ner-")) {
        float[][][] logits = new float[batch][length][9];
        for (var row : logits) for (var token : row) token[0] = 10;
        when(value.getValue()).thenReturn(logits); when(result.get(0)).thenReturn(value);
      } else if (path.startsWith("splade-")) {
        var weights = mock(OnnxTensor.class);
        when(value.getLongBuffer()).thenReturn(LongBuffer.wrap(new long[batch]));
        float[] data = new float[batch]; Arrays.fill(data, .5f);
        when(weights.getFloatBuffer()).thenReturn(FloatBuffer.wrap(data));
        when(result.get("output_idx")).thenReturn(Optional.of(value));
        when(result.get("output_weights")).thenReturn(Optional.of(weights));
      } else if (path.equals("dense-slice")) {
        float[][][] hidden = new float[batch][length][2];
        for (var row : hidden) for (var token : row) token[0] = 1;
        when(value.getValue()).thenReturn(hidden); when(result.get(0)).thenReturn(value);
      } else {
        var sparse = mock(OnnxTensor.class);
        when(value.getValue()).thenReturn(new float[batch][2]);
        when(sparse.getValue()).thenReturn(new float[batch][length][1]);
        when(result.get("dense_vecs")).thenReturn(Optional.of(value));
        when(result.get("sparse_vecs")).thenReturn(Optional.of(sparse));
      }
      return result;
    });
    try (var tensors = mockStatic(OnnxTensor.class)) {
      tensors.when(() -> OnnxTensor.createTensor(any(), any(LongBuffer.class), any(long[].class)))
          .thenAnswer(call -> {
            LongBuffer flat = call.getArgument(1); long[] shape = call.getArgument(2);
            long[][] data = new long[(int) shape[0]][(int) shape[1]];
            for (var row : data) flat.get(row);
            var tensor = mock(OnnxTensor.class);
            when(tensor.getValue()).thenReturn(data);
            when(tensor.getLongBuffer()).thenReturn(LongBuffer.wrap(Arrays.stream(data).flatMapToLong(Arrays::stream).toArray()));
            return tensor;
          });
      int capacity = windowed ? 8 : 512;
      if (path.startsWith("ner-")) {
        try (var service = new NerService(new NerAssembly(sessions, new NerShape(capacity, false), tokenizer,
            BioTagDecoder.LabelMapping.bertBaseNer(), null), new NerConfig(true, Path.of("fixture"), capacity, .5f, false, 0, 0))) {
          String text = path.equals("ner-chunks") ? "Ada in London. ".repeat(1000) : "text";
          List<String> texts = Collections.nCopies(path.equals("ner-batch") ? 129 : path.equals("ner-overflow") || path.equals("ner-fallback") ? 2 : 1, text);
          observe(cancel, stopped, () -> service.extractEntitiesBatch(texts));
        }
      } else if (path.startsWith("splade-")) {
        var vocab = mock(Vocabulary.class); when(vocab.size()).thenReturn(10L);
        when(vocab.getToken(anyLong())).thenAnswer(call -> "term" + call.getArgument(0));
        try (var encoder = new SpladeEncoder(sessions, new SpladeShape(capacity, false, SpladeEncoder.OutputFormat.PRESPARSE, null),
            tokenizer, vocab, null, new SpladeConfig(true, Path.of("fixture"), capacity, false, 0, 0, "onnx", "log1p"))) {
          observe(cancel, stopped, () -> encoder.encodeBatch(Collections.nCopies(path.equals("splade-subbatches") ? 70 : path.equals("splade-long-batch") ? 2 : 1, "text")));
        }
      } else if (path.equals("dense-slice")) {
        try (var encoder = new OnnxEmbeddingEncoder(sessions, new EmbeddingShape(512, false,
            OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 2), tokenizer)) {
          observe(cancel, stopped, () -> encoder.embedWindows("text", 0, 32));
        }
      } else {
        try (var encoder = new BgeM3Encoder(sessions, new BgeM3Shape(512, new String[256]), tokenizer,
            new BgeM3Config(true, Path.of("fixture"), 512, false, 0, 0))) {
          observe(cancel, stopped, () -> encoder.encodeBatch(Collections.nCopies(70, "text")));
        }
      }
    }
    if (cancel) assertEquals(1, calls.get(), "no second native invocation or fallback");
    else assertTrue(calls.get() >= 2, "fixture must exercise multiple native calls");
    if (!path.endsWith("fallback") || !cancel) assertTrue(elapsed.get() > 0, "successful native call time survives cancellation");
  }

  private static void observe(boolean cancel, AtomicBoolean stopped, java.util.concurrent.Callable<?> operation) {
    if (cancel) assertThrows(CancellationException.class,
        () -> LocalSessionAcquisition.withCancellation(stopped::get).call(operation::call));
    else assertDoesNotThrow(() -> LocalSessionAcquisition.withCancellation(stopped::get).call(operation::call));
    assertDoesNotThrow(LocalSessionAcquisition::checkCancellation, "scope must be released after failure");
  }

  private static Encoding encoding(int size) {
    var result = mock(Encoding.class);
    long[] ids = new long[size], mask = new long[size], words = new long[size]; String[] tokens = new String[size];
    for (int i = 0; i < size; i++) { ids[i] = i % 200; mask[i] = 1; words[i] = i; tokens[i] = "word"; }
    ids[0] = 101; ids[size - 1] = 102; words[0] = -1; words[size - 1] = -1;
    when(result.getIds()).thenReturn(ids); when(result.getAttentionMask()).thenReturn(mask);
    when(result.getTypeIds()).thenReturn(new long[size]); when(result.getWordIds()).thenReturn(words); when(result.getTokens()).thenReturn(tokens);
    return result;
  }
}
