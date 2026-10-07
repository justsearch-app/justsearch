/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.DefaultVocabulary;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.ort.Composition;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, check E-MEM1 (b), invariant 1: when a GPU SPLADE batch or a GPU embedding
 * sub-batch fails with a BFC arena failure and the encoder steps down to its CPU session, the CPU
 * step runs outside the device seat. While that CPU step runs, a waiting query on another encoder
 * of the same device is seated.
 *
 * <p>Before E7a the CPU step ran inside the GPU lease (SpladeEncoder.java:619;
 * OnnxEmbeddingEncoder.java:475), so the query waited for it.
 *
 * <p>Real encoders and real tokenizers over real {@code NativeSessionHandle}s built through the
 * production assembler, on their device's real gate. The native sessions are mocks: the GPU
 * session fails with ONNX Runtime's BFC arena message and the CPU session blocks until the test
 * lets it return a real tensor of the shape the model would.
 */
final class DeviceSeatDuringCpuStepTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");
  private static final String BFC =
      "[E:onnxruntime:, sequential_executor.cc:516 ExecuteKernel] Non-zero status code returned"
          + " while running MatMul node. Status Message: BFCArena::AllocateRawInternal Available"
          + " memory of 0 is smaller than requested bytes of 25165824";

  private final List<AutoCloseable> closeAfter = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeAll() throws Exception {
    for (AutoCloseable closeable : closeAfter.reversed()) closeable.close();
  }

  @Test
  void aSpladeBatchsCpuStepRunsOutsideTheDeviceSeat() throws Exception {
    int device = 171;
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap(), anySet(), anyMap(), any())).thenThrow(new OrtException(BFC));
    when(gpu.run(anyMap())).thenThrow(new OrtException(BFC));
    when(gpu.run(anyMap(), any(OrtSession.RunOptions.class))).thenThrow(new OrtException(BFC));
    BlockingCpu cpu = new BlockingCpu(shape -> new float[(int) shape[0]][(int) shape[1]][VOCAB]);
    NativeSessionHandle sessions = handle("splade", device, gpu, cpu.session());
    SpladeEncoder encoder =
        new SpladeEncoder(
            sessions,
            new SpladeShape(512, true, SpladeEncoder.OutputFormat.MLM_LOGITS, "logits"),
            tokenizer("models/splade/naver-splade-v3/tokenizer.json"),
            DefaultVocabulary.builder().add(vocabulary()).optUnknownToken("[UNK]").build(),
            null,
            new SpladeConfig(true, MODEL.getParent(), 512, true, device, 0, "onnx", "log1p"));
    closeAfter.add(encoder);

    List<Map<String, Float>> encoded =
        queryIsSeatedDuringTheCpuStep(
            device, cpu,
            () -> encoder.encodeBatch(List.of("sparse retrieval", "token weighting at scale")));
    assertEquals(2, encoded.size());
  }

  @Test
  void anEmbeddingSubBatchsCpuStepRunsOutsideTheDeviceSeat() throws Exception {
    int device = 172;
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenThrow(new OrtException(BFC));
    when(gpu.run(anyMap(), any(OrtSession.RunOptions.class))).thenThrow(new OrtException(BFC));
    BlockingCpu cpu =
        new BlockingCpu(shape -> filled(new float[(int) shape[0]][(int) shape[1]][8]));
    NativeSessionHandle sessions = handle("embed", device, gpu, cpu.session());
    OnnxEmbeddingEncoder encoder =
        new OnnxEmbeddingEncoder(
            sessions,
            new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 0),
            tokenizer("models/onnx/gte-multilingual-base/tokenizer.json"));

    List<float[]> embedded =
        queryIsSeatedDuringTheCpuStep(
            device, cpu, () -> encoder.embedBatch(List.of("dense retrieval", "a second chunk")));
    assertEquals(2, embedded.size());
  }

  @Test
  void anExpiredRequestDeadlineCannotCutShortTheBulkEmbeddingLadder() throws Exception {
    Object result = assertDoesNotThrow(() -> embeddingLadderWithExpiry(false),
        "indexing must finish both documents after the admitting request expires");
    assertEquals(2, ((List<?>) result).size());
  }

  @Test
  void aQueryEmbeddingLadderStillStopsAtItsRequestDeadline() throws Exception {
    var failure = org.junit.jupiter.api.Assertions.assertThrows(
        java.lang.reflect.InvocationTargetException.class, () -> embeddingLadderWithExpiry(true));
    org.junit.jupiter.api.Assertions.assertInstanceOf(
        io.justsearch.ort.SessionAcquireDeadlineExceededException.class, failure.getCause());
  }

  private Object embeddingLadderWithExpiry(boolean query) throws Exception {
    var deadline = new java.util.concurrent.atomic.AtomicLong(
        System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenThrow(new OrtException(BFC));
    when(gpu.run(anyMap(), any(OrtSession.RunOptions.class))).thenThrow(new OrtException(BFC));
    OrtSession cpu = mock(OrtSession.class);
    when(cpu.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      long[] shape = inputs.get("input_ids").getInfo().getShape();
      var tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
          filled(new float[(int) shape[0]][(int) shape[1]][8]));
      closeAfter.add(tensor);
      var result = mock(OrtSession.Result.class);
      when(result.get(0)).thenReturn(tensor);
      deadline.set(System.nanoTime() - 1);
      return result;
    });
    var sessions = handle("embed", query ? 174 : 173, gpu, cpu);
    var encoder = new OnnxEmbeddingEncoder(sessions,
        new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 0),
        null);
    var method = OnnxEmbeddingEncoder.class.getDeclaredMethod("embedPreTokenizedBatch",
        List.class, java.util.function.Supplier.class);
    method.setAccessible(true);
    java.util.function.Supplier<SessionAcquisitionRequest> authority = () ->
        new SessionAcquisitionRequest(query ? SessionAcquisitionRequest.Urgency.FOREGROUND
            : SessionAcquisitionRequest.Urgency.BACKGROUND, deadline.get(), () -> false, true, query);
    return method.invoke(encoder,
        List.of(new long[][] {{1, 2}, {1, 1}, {0, 0}},
            new long[][] {{3, 4}, {1, 1}, {0, 0}}), authority);
  }

  /**
   * Runs {@code bulk} on its own thread until its CPU step is running, then asks for the device
   * seat for a foreground query on another encoder of the same device.
   */
  private <T> T queryIsSeatedDuringTheCpuStep(int device, BlockingCpu cpu, Callable<T> bulk)
      throws Exception {
    NativeSessionHandle query = handle("ner", device, answering(), answering());
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker = new Thread(() -> {
      try {
        result.set(bulk.call());
      } catch (Throwable thrown) {
        failure.set(thrown);
      }
    }, "e-mem1b-bulk");
    worker.start();
    try {
      assertTrue(cpu.started.await(30, TimeUnit.SECONDS), "the encoder took its CPU step");
      SessionHandle.Lease seated =
          assertDoesNotThrow(
              () ->
                  query.acquire(
                      SessionAcquisitionRequest.within(
                          SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(2))),
              "the query waited for the device seat while the bulk encoder ran on its CPU");
      try (seated) {
        assertFalse(seated.isCpu(), "the query is seated on the device");
      }
    } finally {
      cpu.mayReturn.countDown();
      worker.join(30_000);
    }
    assertFalse(worker.isAlive());
    assertNull(failure.get());
    return result.get();
  }

  private static final int VOCAB = 8;

  private static List<String> vocabulary() {
    List<String> tokens = new ArrayList<>();
    tokens.add("[PAD]");
    tokens.add("[UNK]");
    for (int i = 2; i < VOCAB; i++) tokens.add("token" + i);
    return tokens;
  }

  private static float[][][] filled(float[][][] hidden) {
    for (float[][] row : hidden) for (float[] token : row) java.util.Arrays.fill(token, 1f);
    return hidden;
  }

  /** A CPU session whose first run blocks until the test lets it return. */
  private final class BlockingCpu {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch mayReturn = new CountDownLatch(1);
    private final Function<long[], float[][][]> output;

    BlockingCpu(Function<long[], float[][][]> output) {
      this.output = output;
    }

    OrtSession session() throws OrtException {
      OrtSession session = mock(OrtSession.class);
      when(session.run(anyMap())).thenAnswer(invocation -> {
        Map<String, OnnxTensor> inputs = invocation.getArgument(0);
        long[] shape = inputs.get("input_ids").getInfo().getShape();
        started.countDown();
        assertTrue(mayReturn.await(30, TimeUnit.SECONDS));
        OnnxTensor tensor =
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), output.apply(shape));
        closeAfter.add(tensor);
        OrtSession.Result result = mock(OrtSession.Result.class);
        when(result.get(0)).thenReturn(tensor);
        return result;
      });
      return session;
    }
  }

  private static OrtSession answering() throws OrtException {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> mock(OrtSession.Result.class));
    return session;
  }

  private NativeSessionHandle handle(String name, int device, OrtSession gpu, OrtSession cpu)
      throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(device, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            /* gpuRetryIntervalMs= */ 60_000L);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                name,
                new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
                () -> true);
    set(handle, "gpuSession", gpu);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    set(handle, "cpuSession", cpu);
    closeAfter.add(handle);
    return handle;
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }

  private HuggingFaceTokenizer tokenizer(String relative) throws Exception {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.getParent();
    assertTrue(dir != null, "the repository root is above the test's working directory");
    HuggingFaceTokenizer tokenizer =
        HuggingFaceTokenizer.newInstance(
            dir.resolve(relative), Map.of("truncation", "false", "padding", "false"));
    closeAfter.add(tokenizer);
    return tokenizer;
  }
}
