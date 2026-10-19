/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.inference.LlamaServerExitClassifier.Classification;
import io.justsearch.app.inference.LlamaServerExitClassifier.CrashClass;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Spec A §9.2 U8: the §7.1 classifier table, read from llama-server log fixtures. Where the repo has
 * a real llama-server line it is used verbatim: the b8571 lost-bind output (S-2 live bind marker,
 * exit 1 after 148 ms), the tempdoc 883 out-of-memory abort (exit 127 standalone, 0xC0000409 under
 * the Engine) and the tempdoc 346 unknown-architecture refusal.
 */
final class LlamaServerExitClassifierTest {

  private static final int EXIT_STACK_BUFFER_OVERRUN = -1073740791; // 0xC0000409

  /** The real b8571 output when the fresh port was taken (verify-s2 live-bind-marker.log). */
  private static final String REAL_BIND_FAILURE = String.join("\n",
      "init: using 19 threads for HTTP server",
      "start: binding port with default address family",
      "start: couldn't bind HTTP server socket, hostname: 127.0.0.1, port: 60728",
      "main: exiting due to HTTP server error");

  /** The real b8571 abort when a 1M-token window did not fit (tempdoc 883). */
  private static final String REAL_CUDA_OOM = String.join("\n",
      "llama_context: n_ctx_seq     = 1000192",
      "llama_context: n_ctx_seq (1000192) > n_ctx_train (262144) -- possible training context"
          + " overflow",
      "llama_kv_cache:      CUDA0 KV buffer size = 16604.75 MiB",
      "CUDA error: out of memory",
      "D:\\a\\llama.cpp\\llama.cpp\\ggml\\src\\ggml-cuda\\ggml-cuda.cu:98: CUDA error");

  /** The real refusal of a model newer than the bundled build (tempdoc 346). */
  private static final String REAL_UNKNOWN_ARCHITECTURE =
      "error loading model architecture: unknown model architecture: 'qwen35'";

  private record Row(String name, int exitCode, String log, CrashClass expected,
      FailedCode code, String evidence) {}

  private static final List<Row> TABLE = List.of(
      // CH3: a lost bind is a port race, retried once at the same rung.
      new Row("real lost bind", 1, REAL_BIND_FAILURE, CrashClass.PORT_LOST,
          FailedCode.START_FAILED,
          "start: couldn't bind HTTP server socket, hostname: 127.0.0.1, port: 60728"),
      // RESOURCE: the window or the model did not fit (F17: CUDA OOM is not device loss).
      new Row("real CUDA OOM under the Engine", EXIT_STACK_BUFFER_OVERRUN, REAL_CUDA_OOM,
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED, "CUDA error: out of memory"),
      new Row("real CUDA OOM standalone", 127, REAL_CUDA_OOM, CrashClass.RESOURCE,
          FailedCode.RESOURCE_EXHAUSTED, "CUDA error: out of memory"),
      new Row("F1 KV allocation", 1,
          "ggml_backend_cuda_buffer_type_alloc_buffer: allocating 16604.75 MiB on device 0:"
              + " cudaMalloc failed: out of memory",
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED,
          "ggml_backend_cuda_buffer_type_alloc_buffer: allocating 16604.75 MiB on device 0:"
              + " cudaMalloc failed: out of memory"),
      new Row("compute buffer", 1,
          "ggml_gallocr_reserve_n: failed to allocate CUDA0 buffer of size 2147483648",
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED,
          "ggml_gallocr_reserve_n: failed to allocate CUDA0 buffer of size 2147483648"),
      new Row("alloc_buffer and failed on one line", 1,
          "ggml_backend_cpu_buffer_type_alloc_buffer: allocation of 34359738368 bytes failed",
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED,
          "ggml_backend_cpu_buffer_type_alloc_buffer: allocation of 34359738368 bytes failed"),
      new Row("context creation", 1,
          "llama_init_from_model: failed to create context with model 'model.gguf'",
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED,
          "llama_init_from_model: failed to create context with model 'model.gguf'"),
      new Row("weights that do not fit are memory, not a bad file", 1, String.join("\n",
              "ggml_backend_cuda_buffer_type_alloc_buffer: allocating 4096.00 MiB on device 0:"
                  + " cudaMalloc failed: out of memory",
              "llama_model_load: error loading model: unable to allocate CUDA0 buffer",
              "llama_model_load_from_file_impl: failed to load model"),
          CrashClass.RESOURCE, FailedCode.RESOURCE_EXHAUSTED,
          "ggml_backend_cuda_buffer_type_alloc_buffer: allocating 4096.00 MiB on device 0:"
              + " cudaMalloc failed: out of memory"),
      new Row("STATUS_NO_MEMORY without text", -1073741801, "", CrashClass.RESOURCE,
          FailedCode.RESOURCE_EXHAUSTED, "exit -1073741801"),
      // DEVICE_SUSPECT: never retried in place.
      new Row("launch failure", EXIT_STACK_BUFFER_OVERRUN,
          "CUDA error: unspecified launch failure", CrashClass.DEVICE_SUSPECT,
          FailedCode.START_FAILED, "CUDA error: unspecified launch failure"),
      new Row("watchdog", 1, "CUDA error: the launch timed out and was terminated",
          CrashClass.DEVICE_SUSPECT, FailedCode.START_FAILED,
          "CUDA error: the launch timed out and was terminated"),
      new Row("device lost", 1, "ggml_vulkan: device lost", CrashClass.DEVICE_SUSPECT,
          FailedCode.START_FAILED, "ggml_vulkan: device lost"),
      new Row("no device", 1,
          "ggml_cuda_init: failed to initialize CUDA: no CUDA-capable device is detected",
          CrashClass.DEVICE_SUSPECT, FailedCode.START_FAILED,
          "ggml_cuda_init: failed to initialize CUDA: no CUDA-capable device is detected"),
      new Row("devices unavailable", 1, "CUDA error 46 (cudaErrorDevicesUnavailable)",
          CrashClass.DEVICE_SUSPECT, FailedCode.START_FAILED,
          "CUDA error 46 (cudaErrorDevicesUnavailable)"),
      // CONFIG: fails at every rung, so no ladder.
      new Row("real unknown architecture", 1, REAL_UNKNOWN_ARCHITECTURE, CrashClass.CONFIG,
          FailedCode.MODEL_LOAD_FAILED, REAL_UNKNOWN_ARCHITECTURE),
      new Row("missing DLL", LlamaServerExitClassifier.EXIT_DLL_NOT_FOUND, "", CrashClass.CONFIG,
          FailedCode.MISSING_DLL, "exit -1073741515"),
      new Row("corrupt model", 1,
          "llama_model_load: error loading model: tensor 'blk.0.attn_q.weight' data is not"
              + " within the file bounds",
          CrashClass.CONFIG, FailedCode.MODEL_LOAD_FAILED,
          "llama_model_load: error loading model: tensor 'blk.0.attn_q.weight' data is not"
              + " within the file bounds"),
      new Row("model file", 1, "srv    load_model: failed to load model, 'model.gguf'",
          CrashClass.CONFIG, FailedCode.MODEL_LOAD_FAILED,
          "srv    load_model: failed to load model, 'model.gguf'"),
      new Row("address in use", 1, "bind: Address already in use", CrashClass.CONFIG,
          FailedCode.START_FAILED, "bind: Address already in use"),
      // OTHER: everything else.
      new Row("bare exit 1", 1, "", CrashClass.OTHER, FailedCode.START_FAILED, null),
      new Row("0xC0000409 without memory text", EXIT_STACK_BUFFER_OVERRUN,
          "D:\\a\\llama.cpp\\llama.cpp\\ggml\\src\\ggml-cuda\\ggml-cuda.cu:98: CUDA error",
          CrashClass.OTHER, FailedCode.START_FAILED, null),
      new Row("reasoning flag rejection is T8's, not a class", 1,
          "error while handling argument \"--reasoning-budget\": invalid value",
          CrashClass.OTHER, FailedCode.START_FAILED, null),
      new Row("unknown exit and no log", -1, null, CrashClass.OTHER, FailedCode.START_FAILED,
          null));

  @Test
  void everyFixtureLandsInItsSpecClassWithItsEvidenceLine() {
    for (Row row : TABLE) {
      Classification actual = LlamaServerExitClassifier.classify(row.exitCode(), row.log());
      assertEquals(row.expected(), actual.crashClass(), row.name());
      assertEquals(row.code(), actual.failedCode(), row.name());
      assertEquals(row.evidence(), actual.evidence(), row.name());
    }
  }

  @Test
  void theTableCoversEveryClass() {
    Set<CrashClass> covered = EnumSet.noneOf(CrashClass.class);
    TABLE.forEach(row -> covered.add(row.expected()));
    assertEquals(EnumSet.allOf(CrashClass.class), covered);
  }

  @Test
  void aWindowsLogWithCarriageReturnsGivesTheSameEvidence() {
    Classification actual = LlamaServerExitClassifier.classify(
        EXIT_STACK_BUFFER_OVERRUN, REAL_CUDA_OOM.replace("\n", "\r\n"));
    assertEquals(CrashClass.RESOURCE, actual.crashClass());
    assertEquals("CUDA error: out of memory", actual.evidence());
    assertTrue(LlamaServerExitClassifier.classify(1, REAL_BIND_FAILURE.replace("\n", "\r\n"))
        .evidence().endsWith("port: 60728"));
  }
}
