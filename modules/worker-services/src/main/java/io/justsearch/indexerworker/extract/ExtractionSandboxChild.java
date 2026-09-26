/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Child-process entry point for out-of-process extraction (tempdoc 885 item 14).
 *
 * <p>Serves a length-prefixed request/response loop on stdin/stdout ({@link SandboxFrames}) until
 * the parent closes the pipe. The one-shot "read stdin to EOF, answer once, exit" mode was retired
 * with {@code ProcessExtractionSandbox}: a JVM start plus Tika class-loading per file is why that
 * mode was never shipped, and a persistent child amortises both across every file it handles.
 * There is therefore no mode flag — serving is the only mode.
 *
 * <p>Two invariants this class owns:
 *
 * <ul>
 *   <li><b>Protocol channel isolation.</b> The real {@code System.out} is captured before any
 *       parser runs and {@code System.out} is redirected to stderr, so a parser that prints cannot
 *       corrupt a frame. (Shipped before this tempdoc; the serve loop preserves it.)
 *   <li><b>Process lifetime.</b> Windows bootstrap first installs a kill-on-close Job Object
 *       around this JVM and all its native descendants. The parent-PID watchdog halts this JVM
 *       when the Engine dies; Windows then kills the entire job, also on forced parser recycling.
 *       Other platforms retain only the parent watchdog, without native-descendant containment.
 * </ul>
 */
public final class ExtractionSandboxChild {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  static final String PARENT_PID_FLAG = "--parent-pid=";

  /** Parent-liveness poll interval. Short enough that an orphan dies well inside a test. */
  private static final long PARENT_POLL_MS = 500L;

  private ExtractionSandboxChild() {}

  public static void main(String[] args) throws Exception {
    PrintStream protocolOut = System.out;
    System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    initializeProcessBoundary(args);
    serve(System.in, protocolOut);
  }

  static void serve(InputStream in, OutputStream protocolOut) throws IOException {
    try (ExtractorCache cache = new ExtractorCache()) {
    byte[] frame;
    while ((frame = SandboxFrames.read(in, SandboxFrames.MAX_FRAME_BYTES)) != null) {
      SandboxExtractionRequest request =
          MAPPER.readValue(new String(frame, StandardCharsets.UTF_8), SandboxExtractionRequest.class);
      SandboxFrames.write(protocolOut, MAPPER.writeValueAsBytes(handle(request, cache)));
    }
    }
  }

  private static SandboxExtractionResponse handle(
      SandboxExtractionRequest request, ExtractorCache cache) {
    TikaExtractionPolicy policy =
        request.policy() == null ? TikaExtractionPolicy.defaults() : request.policy();
    OcrRoutingConfig ocrConfig =
        request.ocrConfig() == null ? OcrRoutingConfig.disabled() : request.ocrConfig();
    try {
      ExtractionArtifact artifact =
          cache
              .extractor(policy, ocrConfig)
              .extractArtifact(Path.of(request.path()))
              .validateContentBoundsOnly(policy.maxExtractedChars());
      return SandboxExtractionResponse.fromArtifact(request.requestId(), artifact);
    } catch (ContentExtractor.BudgetExceededException e) {
      return SandboxExtractionResponse.failed(
          request.requestId(), ExtractionStatus.BUDGET_EXCEEDED, policy, "sandbox-child", "Budget exceeded", e.reasonCode());
    } catch (Exception e) {
      return SandboxExtractionResponse.failed(
          request.requestId(), ExtractionStatus.FAILED, policy, "sandbox-child", "Sandbox parser failed", "PARSER_FAILED");
    }
  }

  /**
   * Reuses one {@link PolicyDrivenTikaExtractor} across requests. The parent sends the same policy
   * on every frame, so this constructs exactly one extractor per child in practice — which is the
   * whole point of a persistent child. An {@link OutOfMemoryError} is deliberately NOT caught: the
   * parent classifies a heap-exhausted child as a permanent parse failure from the exit code plus
   * the JVM's own stderr signature, and answering from a poisoned heap is not reliable.
   */
  static final class ExtractorCache implements AutoCloseable {
    private TikaExtractionPolicy policy;
    private OcrRoutingConfig ocrConfig;
    private PolicyDrivenTikaExtractor extractor;

    PolicyDrivenTikaExtractor extractor(TikaExtractionPolicy p, OcrRoutingConfig o) {
      if (extractor == null || !Objects.equals(policy, p) || !Objects.equals(ocrConfig, o)) {
        close();
        extractor = new PolicyDrivenTikaExtractor(ExtractionSandboxChild::openOcrPool, p, o);
        policy = p;
        ocrConfig = o;
      }
      return extractor;
    }

    @Override public void close() {
      if (extractor != null) {
        extractor.close();
        extractor = null;
      }
    }
  }

  /** External-process-only OCR factory; the parent projects its resolved worker limit in each frame. */
  public static java.util.concurrent.ExecutorService openOcrPool(int workers) {
    if (workers < 1) throw new IllegalArgumentException("OCR workers must be resolved and positive");
    return new java.util.concurrent.ThreadPoolExecutor(
        workers, workers, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(workers),
        Thread.ofPlatform().daemon().name("parser-child-ocr-", 0).factory(),
        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
  }

  static long parentPid(String[] args) {
    if (args == null) {
      return -1L;
    }
    for (String arg : args) {
      if (arg != null && arg.startsWith(PARENT_PID_FLAG)) {
        try {
          return Long.parseLong(arg.substring(PARENT_PID_FLAG.length()).trim());
        } catch (NumberFormatException e) {
          return -1L;
        }
      }
    }
    return -1L;
  }

  /**
   * Installs Windows descendant containment, then the parent-liveness gate for a child launched with {@code --parent-pid=<pid>}.
   *
   * <p>Public because the chaos harness's stub parser is a real out-of-tree child of this pool and
   * must run <b>this</b> orphan-prevention code, not a copy of it — a copied watchdog would make
   * the system-level "Worker shutdown leaves no orphan" assertion prove nothing about production.
   */
  // Halting IS the contract here: a parser child whose parent died must not outlive it, and no
  // orderly shutdown is available from a watchdog thread in a doomed process.
  @SuppressWarnings("PMD.DoNotTerminateVM")
  public static void initializeProcessBoundary(String[] args) {
    WindowsParserContainment.install();
    long parentPid = parentPid(args);
    if (parentPid <= 0) {
      return;
    }
    Thread watchdog =
        new Thread(
            () -> {
              while (true) {
                Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
                if (parent.isEmpty() || !parent.get().isAlive()) {
                  Runtime.getRuntime().halt(0);
                }
                try {
                  Thread.sleep(PARENT_POLL_MS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            "extraction-sandbox-parent-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }
}
