/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import io.justsearch.telemetry.catalog.EmptyTags;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Out-of-process extraction sandbox backed by a pool of <b>persistent</b> child JVMs (tempdoc 885
 * item 14, design decision 1).
 *
 * <p>Replaces the one-child-JVM-per-file {@code ProcessExtractionSandbox} shipped by tempdoc 410,
 * which was never enabled because a JVM start plus Tika class-loading per file costs hundreds of
 * milliseconds. Here each child is spawned lazily, handles one request at a time, and is reused
 * until it crashes, misses a deadline, or exhausts its request budget.
 *
 * <p><b>The deadline is enforced by killing the child, not by cancelling a thread.</b> That is the
 * property the in-process extractor cannot have: {@code Future.cancel(true)} only sets an interrupt
 * flag, and a wedged PDFBox/POI parse ignores it. Terminating the child closes its pipes, which
 * unblocks this sandbox's reader task, so the calling thread returns at the deadline and the next
 * file gets a fresh child.
 *
 * <p><b>Every child is started and stopped through {@link ChildControl}</b> (Spec B §2.7), which
 * records each stop's reason before it acts and each exit as requested or unexpected. The pool
 * keeps its own retry and permanence decisions; a child that dies on its own is not stopped, its
 * recorded exit is read instead.
 *
 * <p><b>Failure classification.</b> A child that dies with {@code OutOfMemoryError} on its stderr
 * is reported as a plain {@link ContentExtractor.ExtractionException}, which
 * {@code JobBatchExtractor} already maps to {@code PARSER_FAILED} with
 * {@code IngestionRetryPolicy.NONE} — a permanent failure, because retrying a file that does not
 * fit in the child heap will exhaust it again. Any other non-zero exit is a
 * {@link SandboxExtractionException} (retryable), and a missed deadline is an
 * {@link TimeboxedContentExtractor.ExtractionTimeoutException} (PARSER_TIMEOUT), exactly as before.
 */
public final class PersistentExtractionSandbox implements ExtractionSandbox {
  private static final Logger log = LoggerFactory.getLogger(PersistentExtractionSandbox.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final long JSON_BYTES_PER_UTF16_CODE_UNIT = 6L;
  private static final long JSON_STRUCTURAL_ALLOWANCE_BYTES = 64L * 1024L;
  static final int DEFAULT_MAX_STDERR_BYTES = 64 * 1024;
  /** Requests one child handles before it is recycled — the leak guard (design decision 1). */
  static final int DEFAULT_MAX_REQUESTS_PER_CHILD = 500;
  /** How long pool close waits for children to exit on EOF before terminating them. */
  static final Duration GRACEFUL_CLOSE_WAIT = Duration.ofSeconds(2);
  /** The {@code requestedBy} of every stop this pool asks for. */
  static final String REQUESTED_BY = "extraction-pool";
  /** How long a child that broke the protocol on its own gets to exit before it is stopped. */
  private static final Duration SELF_EXIT_WAIT = Duration.ofSeconds(2);
  /** Bounds a wait on a stop; the control itself gives up after two terminate attempts. */
  private static final Duration STOP_WAIT = Duration.ofSeconds(15);

  static final String REASON_TIMEOUT = "timeout";
  static final String REASON_CRASH = "crash";
  static final String REASON_OOM = "oom";
  static final String REASON_REQUEST_BUDGET = "request_budget";
  static final String REASON_PROTOCOL = "protocol";
  static final String REASON_INTERRUPTED = "interrupted";
  /** Recorded by the wiring, not the pool: the child failed its startup probe. */
  public static final String REASON_PROBE_FAILED = "probe_failed";

  private final List<String> command;
  private final TikaExtractionPolicy policy;
  private final OcrRoutingConfig ocrConfig;
  private final Duration timeout;
  private final int maxResponseBytes;
  private final int maxStderrBytes;
  private final int maxRequestsPerChild;
  private final ExtractionMetricCatalog catalog;
  private final ChildControl control;

  private final Slot[] allSlots;
  private final BlockingQueue<Slot> freeSlots;
  private final ExecutorService readers;
  private final AtomicLong spawnCount = new AtomicLong();
  private final AtomicLong restartCount = new AtomicLong();
  private final Thread shutdownHook;
  private volatile boolean closed;

  public PersistentExtractionSandbox(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog) {
    this(readerRegistration, command, policy, ocrConfig, timeout, poolSize, maxRequestsPerChild, catalog,
        ManagedChildRegistry.noop());
  }

  /** A pool with its own unrecorded control; the Engine passes its {@link ChildControl}. */
  public PersistentExtractionSandbox(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog,
      ManagedChildRegistry childRegistry) {
    this(readerRegistration, command, policy, ocrConfig, timeout, poolSize, maxRequestsPerChild,
        catalog, DefaultChildControl.unrecorded(childRegistry));
  }

  public PersistentExtractionSandbox(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog,
      ChildControl control) {
    this(
        readerRegistration,
        command,
        policy,
        ocrConfig,
        timeout,
        poolSize,
        maxRequestsPerChild,
        catalog,
        responseByteCeiling(policy),
        DEFAULT_MAX_STDERR_BYTES,
        control);
  }

  PersistentExtractionSandbox(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog,
      int maxResponseBytes,
      int maxStderrBytes) {
    this(readerRegistration, command, policy, ocrConfig, timeout, poolSize, maxRequestsPerChild, catalog,
        maxResponseBytes, maxStderrBytes, DefaultChildControl.unrecorded(ManagedChildRegistry.noop()));
  }

  PersistentExtractionSandbox(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog,
      int maxResponseBytes,
      int maxStderrBytes,
      ChildControl control) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Sandbox command must not be empty");
    }
    this.command = List.copyOf(command);
    this.policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    this.ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    this.timeout = timeout == null ? TimeboxedContentExtractor.DEFAULT_TIMEOUT : timeout;
    this.maxResponseBytes = Math.max(1024, maxResponseBytes);
    this.maxStderrBytes = Math.max(1024, maxStderrBytes);
    this.maxRequestsPerChild =
        maxRequestsPerChild > 0 ? maxRequestsPerChild : DEFAULT_MAX_REQUESTS_PER_CHILD;
    this.catalog = catalog;
    this.control = Objects.requireNonNull(control, "control");

    int size = Math.max(1, poolSize);
    this.allSlots = new Slot[size];
    this.freeSlots = new ArrayBlockingQueue<>(size);
    for (int i = 0; i < size; i++) {
      allSlots[i] = new Slot();
      freeSlots.add(allSlots[i]);
    }
    this.readers =
        Objects.requireNonNull(readerRegistration, "readerRegistration").open(
            r -> {
              Thread t = new Thread(r, "extraction-sandbox-reader");
              t.setDaemon(true);
              return t;
            });
    // Belt to the child's PID-gate braces: a clean JVM exit that skips close() must not leave a
    // child behind either. Removed in close() so a per-test sandbox does not accumulate hooks.
    this.shutdownHook = new Thread(this::stopOnShutdown, "extraction-sandbox-shutdown");
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } catch (RuntimeException | Error failure) {
      shutdownAndCancelQueued(readers);
      throw failure;
    }
  }

  @Override
  public TikaExtractionPolicy policy() {
    return policy;
  }

  /**
   * Maximum encoded response admitted for a policy under the one-frame protocol.
   *
   * <p>JSON can require six ASCII bytes for one UTF-16 code unit ({@code \\uXXXX}). Content and
   * policy-controlled metadata are therefore charged at that worst case, as are every other
   * bounded string in the response schema. The structural allowance covers field names,
   * punctuation, booleans, integers, arrays, and maps.
   */
  static int responseByteCeiling(TikaExtractionPolicy configuredPolicy) {
    TikaExtractionPolicy effectivePolicy =
        configuredPolicy == null ? TikaExtractionPolicy.defaults() : configuredPolicy;
    if (effectivePolicy.policyId().length() > SandboxExtractionResponse.MAX_IDENTIFIER_CHARS) {
      throw new IllegalArgumentException(
          "Extraction policy id exceeds sandbox response-schema limit of "
              + SandboxExtractionResponse.MAX_IDENTIFIER_CHARS
              + " UTF-16 code units");
    }
    try {
      long metadataChars =
          Math.multiplyExact(
              (long) effectivePolicy.maxMetadataEntries(),
              Math.addExact(
                  (long) effectivePolicy.maxMetadataKeyChars(),
                  (long) effectivePolicy.maxMetadataValueChars()));
      long boundedChars = effectivePolicy.maxExtractedChars();
      boundedChars =
          Math.addExact(
              boundedChars,
              Math.multiplyExact(3L, ExtractionArtifact.MAX_SCALAR_METADATA_CHARS));
      boundedChars = Math.addExact(boundedChars, metadataChars);
      boundedChars = Math.addExact(boundedChars, SandboxExtractionRequest.MAX_REQUEST_ID_CHARS);
      boundedChars =
          Math.addExact(
              boundedChars,
              Math.multiplyExact(2L, SandboxExtractionResponse.MAX_IDENTIFIER_CHARS));
      boundedChars =
          Math.addExact(
              boundedChars,
              Math.multiplyExact(
                  (long) ExtractionArtifact.MAX_WARNING_COUNT,
                  ExtractionArtifact.MAX_WARNING_CHARS));
      boundedChars =
          Math.addExact(boundedChars, ExtractionArtifact.MAX_VISUAL_EXTRACTION_EVIDENCE_CHARS);
      boundedChars =
          Math.addExact(
              boundedChars,
              Math.addExact(
                  SandboxExtractionResponse.MAX_ERROR_MESSAGE_CHARS,
                  SandboxExtractionResponse.MAX_REASON_CODE_CHARS));
      long bytes =
          Math.addExact(
              Math.multiplyExact(boundedChars, JSON_BYTES_PER_UTF16_CODE_UNIT),
              JSON_STRUCTURAL_ALLOWANCE_BYTES);
      if (bytes > SandboxFrames.MAX_FRAME_BYTES) {
        throw new IllegalArgumentException(
            "Extraction policy requires a sandbox response ceiling of "
                + bytes
                + " bytes, above the protocol maximum of "
                + SandboxFrames.MAX_FRAME_BYTES);
      }
      return Math.toIntExact(bytes);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "Extraction policy is too large for the sandbox response protocol", e);
    }
  }

  @Override
  public ExtractionArtifact extract(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    synchronized (this) {
      if (closed) {
        throw new IngestionServiceUnavailableException(
            "Extraction sandbox is closed");
      }
    }
    Slot slot;
    try {
      slot = freeSlots.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted waiting for a sandbox child");
    }
    try {
      return extractOnSlot(slot, file);
    } catch (IOException | ContentExtractor.ExtractionException | RuntimeException failure) {
      synchronized (this) {
        if (closed) {
          throw new SandboxServiceUnavailableException("Extraction sandbox is closing", failure);
        }
      }
      throw failure;
    } finally {
      freeSlots.add(slot);
    }
  }

  private ExtractionArtifact extractOnSlot(Slot slot, Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Child child;
    try {
      child = acquireChild(slot);
    } catch (IOException e) {
      // Failure to open the child is sandbox infrastructure failure, not failure to read the
      // source file. Keep source IO and child/parser failures document-specific below.
      throw new SandboxServiceUnavailableException("Failed to start sandbox child", e);
    }
    child.requests++;
    // The tail is reported per FILE, so it must not carry the previous request's chatter.
    child.stderr.reset();

    String requestId = java.util.UUID.randomUUID().toString();
    byte[] request =
        MAPPER.writeValueAsBytes(
            new SandboxExtractionRequest(
                SandboxExtractionRequest.CURRENT_SCHEMA_VERSION,
                requestId,
                file.toAbsolutePath().toString(),
                policy,
                ocrConfig));
    try {
      SandboxFrames.write(child.stdin, request);
    } catch (IOException e) {
      // The child died between the liveness check and the write (broken pipe).
      throw discardAndClassify(slot, child, REASON_CRASH, e);
    }

    Future<byte[]> pending;
    try {
      pending = readers.submit(() -> SandboxFrames.read(child.stdout, maxResponseBytes));
    } catch (java.util.concurrent.RejectedExecutionException refused) {
      // A request is already on the pipe. Retire it before reporting shared reader unavailability.
      discardChild(slot, child, REASON_PROTOCOL);
      throw new SandboxServiceUnavailableException("Sandbox reader cannot accept work", refused);
    } catch (RuntimeException refused) {
      // Unknown child failures retain the per-document retry policy.
      throw discardAndClassify(slot, child, REASON_PROTOCOL, refused);
    } catch (Error fatal) {
      try {
        discardChild(slot, child, REASON_PROTOCOL);
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != fatal) fatal.addSuppressed(cleanup);
      }
      throw fatal;
    }
    byte[] responseBytes;
    try {
      responseBytes = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Kill FIRST: destroying the child closes its stdout, which is what releases the reader
      // task. cancel() alone would only interrupt a read that is not interruptible on Windows.
      discardChild(slot, child, REASON_TIMEOUT);
      pending.cancel(true);
      throw new TimeboxedContentExtractor.ExtractionTimeoutException(
          "Sandbox extraction timed out after " + timeout.toMillis() + "ms for: "
              + file.getFileName(),
          e);
    } catch (InterruptedException e) {
      // NOT a crash: the child is healthy and is killed only because this thread was interrupted
      // (loop shutdown, or an outer timebox replacing its executor). Tagging it `crash` made a
      // deadline kill indistinguishable from a child that died on its own — the chaos tier caught
      // exactly that (tempdoc 885 §SC-chaos).
      Thread.currentThread().interrupt();
      discardChild(slot, child, REASON_INTERRUPTED);
      pending.cancel(true);
      throw new InterruptedIOException("Interrupted awaiting sandbox response");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof SandboxFrames.SandboxProtocolException) {
        throw discardAndClassify(slot, child, REASON_PROTOCOL, (Exception) cause);
      }
      throw discardAndClassify(
          slot, child, REASON_CRASH, cause instanceof Exception ex ? ex : new IOException(cause));
    }

    if (responseBytes == null) {
      // Clean EOF instead of a frame: the child exited while we were waiting for its answer.
      throw discardAndClassify(slot, child, REASON_CRASH, null);
    }

    String stderrTail = child.stderr.tail();
    if (!stderrTail.isEmpty() && log.isDebugEnabled()) {
      log.debug("Sandbox child stderr (success path): {}", stderrTail);
    }
    try {
      return decode(responseBytes, requestId);
    } catch (SandboxServiceUnavailableException unavailable) {
      // A valid response about shared storage does not corrupt the protocol or retire its owner.
      throw unavailable;
    } catch (SandboxExtractionException malformed) {
      throw discardAndClassify(slot, child, REASON_PROTOCOL, malformed);
    }
  }

  private ExtractionArtifact decode(byte[] responseBytes, String requestId)
      throws ContentExtractor.ExtractionException {
    try {
      SandboxExtractionResponse response =
          MAPPER.readValue(responseBytes, SandboxExtractionResponse.class);
      if (response == null
          || response.schemaVersion() != SandboxExtractionResponse.CURRENT_SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported sandbox response schema");
      }
      if (!requestId.equals(response.requestId())) {
        throw new IllegalArgumentException("Sandbox response requestId does not match request");
      }
      ExtractionArtifact artifact = response.toArtifact();
      if (response.status() == ExtractionStatus.BUDGET_EXCEEDED) {
        throw new ContentExtractor.BudgetExceededException(
            "Sandbox extraction budget exceeded",
            response.reasonCode() == null ? "EXTRACTED_TEXT_TOO_LARGE" : response.reasonCode());
      }
      if (response.status() == ExtractionStatus.TIMED_OUT) {
        throw new TimeboxedContentExtractor.ExtractionTimeoutException(
            "Sandbox parser timed out", null);
      }
      if (response.status() == ExtractionStatus.FAILED) {
        if (SandboxExtractionResponse.SERVICE_UNAVAILABLE.equals(response.reasonCode())) {
          throw new SandboxServiceUnavailableException("Sandbox storage or service is unavailable", null);
        }
        throw new ContentExtractor.ExtractionException("Sandbox parser failed");
      }
      return artifact.validateContentBoundsOnly(policy.maxExtractedChars());
    } catch (ContentExtractor.ExtractionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SandboxExtractionException("Malformed sandbox response", e);
    }
  }

  /**
   * Retires the child and turns its exit into the right typed failure. A crash is read from the
   * child's own recorded exit; any other reason stops it first.
   */
  private ContentExtractor.ExtractionException discardAndClassify(
      Slot slot, Child child, String reason, Exception cause) {
    ExitOutcome exit;
    try {
      exit = REASON_CRASH.equals(reason) ? selfExit(child) : retire(child, reason);
    } catch (RuntimeException | Error cleanup) {
      if (cause != null && cause != cleanup) cleanup.addSuppressed(cause);
      throw cleanup;
    }
    // The OOM signature only reaches the tail once the drain thread has seen EOF on the dead
    // child's stderr; reading it before that classifies a heap exhaustion as an ordinary crash.
    child.stderr.awaitDrain(2000L);
    String tail = child.stderr.tail();
    boolean oom = tail.contains("OutOfMemoryError");
    if (oom) noteQuietly(child, REASON_OOM);
    finishDiscard(slot, child, oom ? REASON_OOM : reason);
    String code = describeCode(exit);
    if (oom) {
      // Permanent: the file does not fit in the child heap, so a retry exhausts it again.
      return new ContentExtractor.ExtractionException(
          "Sandbox child exhausted its heap (exit=" + code + "): " + tail, cause);
    }
    return new SandboxExtractionException(
        "Sandbox child exited with code " + code + ": " + tail, cause);
  }

  private void discardChild(Slot slot, Child child, String reason) {
    retire(child, reason);
    finishDiscard(slot, child, reason);
  }

  private void finishDiscard(Slot slot, Child child, String reason) {
    child.close();
    if (slot.child == child) {
      slot.child = null;
    }
    restartCount.incrementAndGet();
    if (catalog != null) {
      catalog.sandboxRestartTotal.increment(ExtractionSandboxRestartTags.of(reason));
    }
    log.warn("Extraction sandbox child recycled (reason={}, pid={})", reason, child.pid);
  }

  /**
   * Stops the child for {@code reason} and waits for its recorded exit. A child that survives both
   * terminate attempts keeps its slot (and {@code retirementReason}), so the next request on the
   * slot retries the stop under the first intent instead of spawning beside a live child.
   */
  private ExitOutcome retire(Child child, String reason) {
    child.retirementReason = reason;
    if (child.handle.state() == ChildHandle.State.EXITED) return recordedExit(child);
    return awaitStop(child, control.stop(child.handle, stopRequest(reason)));
  }

  /**
   * A child that broke the protocol by itself (EOF, broken pipe) normally has exited or is about
   * to; its exit is recorded as UNEXPECTED by the control. One still running after a short wait is
   * stopped, since it no longer answers.
   */
  private ExitOutcome selfExit(Child child) {
    child.retirementReason = REASON_CRASH;
    CompletableFuture<ExitOutcome> exit = child.handle.exit().toCompletableFuture();
    boolean interrupted = Thread.interrupted();
    try {
      long deadline = System.nanoTime() + SELF_EXIT_WAIT.toNanos();
      while (true) {
        try {
          return exit.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (TimeoutException stillRunning) {
          break;
        } catch (ExecutionException impossible) {
          break;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    return awaitStop(child, control.stop(child.handle,
        StopRequest.of(StopReason.RECYCLE, StopMode.NOW, REQUESTED_BY)
            .withDetail(StopDetail.PROTOCOL)));
  }

  /** The pool's reasons as stop requests (Spec B §2.7). */
  private static StopRequest stopRequest(String reason) {
    return switch (reason) {
      case REASON_TIMEOUT -> StopRequest.of(StopReason.TIMEOUT, StopMode.NOW, REQUESTED_BY);
      case REASON_INTERRUPTED -> StopRequest.of(StopReason.CANCELLED, StopMode.NOW, REQUESTED_BY);
      case REASON_REQUEST_BUDGET -> StopRequest.of(StopReason.RECYCLE, StopMode.NOW, REQUESTED_BY)
          .withDetail(StopDetail.REQUEST_BUDGET);
      default -> StopRequest.of(StopReason.RECYCLE, StopMode.NOW, REQUESTED_BY)
          .withDetail(StopDetail.PROTOCOL);
    };
  }

  /**
   * Waits for a stop, keeping the caller's interrupt for after it: an interrupted caller must not
   * turn an in-flight teardown into a false survival verdict.
   */
  private ExitOutcome awaitStop(Child child, CompletionStage<ExitOutcome> stop) {
    CompletableFuture<ExitOutcome> future = stop.toCompletableFuture();
    boolean interrupted = Thread.interrupted();
    Throwable failure = null;
    try {
      long deadline = System.nanoTime() + STOP_WAIT.toNanos();
      while (true) {
        try {
          return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException e) {
          failure = e.getCause() != null ? e.getCause() : e;
          break;
        } catch (TimeoutException e) {
          failure = e;
          break;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    throw new IllegalStateException("Extraction child " + child.pid
        + " survived retirement; retaining its slot for cleanup retry; state="
        + child.handle.state(), failure);
  }

  private static ExitOutcome recordedExit(Child child) {
    return child.handle.exit().toCompletableFuture().join();
  }

  private void noteQuietly(Child child, String token) {
    try {
      control.note(child.handle, token);
    } catch (RuntimeException unrecorded) {
      log.debug("Could not record extraction child note {}", token, unrecorded);
    }
  }

  private static String describeCode(ExitOutcome exit) {
    OptionalInt code = exit.code();
    return code.isPresent() ? Integer.toString(code.getAsInt()) : "unknown";
  }

  private Child acquireChild(Slot slot) throws IOException {
    Child current = slot.child;
    if (current != null && current.retirementReason != null) {
      discardChild(slot, current, current.retirementReason);
      current = null;
    } else if (current != null && current.requests >= maxRequestsPerChild) {
      discardChild(slot, current, REASON_REQUEST_BUDGET);
      current = null;
    } else if (current != null && current.handle.state() == ChildHandle.State.EXITED) {
      // It ended on its own; its exit is already recorded (UNEXPECTED), so nothing is stopped.
      current.retirementReason = REASON_CRASH;
      finishDiscard(slot, current, REASON_CRASH);
      current = null;
    }
    if (current == null) {
      // Serialize the final admission and publication with close's flag. A slot waiter must not
      // spawn a new child after close has already enumerated the children it owns.
      synchronized (this) {
        if (closed) {
          throw new IngestionServiceUnavailableException(
              "Extraction sandbox is closed");
        }
        current = spawn();
        slot.child = current;
      }
    }
    return current;
  }

  private Child spawn() throws IOException {
    List<String> argv = new ArrayList<>(command);
    argv.addAll(io.justsearch.app.api.runtime.child.OwnedHelper.childArguments());
    argv.add(ExtractionSandboxChild.PARENT_PID_FLAG + ProcessHandle.current().pid());
    ChildSpec spec = new ChildSpec(ChildKind.EXTRACTION, argv, null, Map.of(),
        new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(),
        new ChildSpec.CooperativeStop(
            ChildSpec.CooperativeStop.Action.CLOSE_STDIN, GRACEFUL_CLOSE_WAIT, Set.of(0)),
        "extraction");
    ChildHandle handle;
    try {
      // A child whose manifest registration fails is stopped (ROLLBACK) by the control and never
      // returned; one that survives that stays held by the control for its close to retry.
      handle = control.start(spec, new ChildControl.ManifestEntry("stdio", null, hashArgv(command)));
    } catch (IllegalStateException closedControl) {
      throw new IOException("child control is unavailable", closedControl);
    }
    spawnCount.incrementAndGet();
    if (catalog != null) {
      catalog.sandboxSpawnTotal.increment(EmptyTags.INSTANCE);
    }
    log.info("Extraction sandbox child spawned (pid={})", handle.pid());
    return new Child(handle, maxStderrBytes);
  }

  @Override
  public void close() {
    synchronized (this) { closed = true; }
    List<String> survivors = stopAllGracefully();
    shutdownAndCancelQueued(readers);
    try {
      readers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (!survivors.isEmpty()) {
      // Keep the JVM callback and exact handles reachable for the final cleanup attempt.
      throw new IllegalStateException(
          "Extraction children survived terminal cleanup: " + survivors);
    }
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException e) {
      // Already shutting down — the hook is running or has run.
    }
  }

  private static void shutdownAndCancelQueued(ExecutorService executor) {
    for (Runnable queued : executor.shutdownNow()) {
      if (queued instanceof Future<?> future) {
        future.cancel(false);
      }
    }
  }

  /**
   * Design 7.3 step 4 closes after extraction drain: one cooperative QUIT for every serving child,
   * so EOF ends each serve loop normally and one pool-wide grace window (not one per child) bounds
   * it before the control terminates the rest. A child already being retired is stopped at once,
   * which retries a survivor under its first intent. The host deadline is 15 seconds
   * (supervision-contract.v1.json).
   */
  private List<String> stopAllGracefully() {
    List<Child> serving = new ArrayList<>();
    List<Child> retiring = new ArrayList<>();
    for (Slot slot : allSlots) {
      Child child = slot.child;
      if (child == null) continue;
      (child.retirementReason == null ? serving : retiring).add(child);
    }
    return stopAndCollect(serving, retiring,
        StopRequest.of(StopReason.QUIT, StopMode.COOPERATIVE, REQUESTED_BY));
  }

  /** The JVM is exiting without {@link #close()}: stop every child now. */
  private void stopOnShutdown() {
    List<Child> all = new ArrayList<>();
    for (Slot slot : allSlots) {
      Child child = slot.child;
      if (child != null) all.add(child);
    }
    List<String> survivors = stopAndCollect(all, List.of(),
        StopRequest.of(StopReason.QUIT, StopMode.NOW, REQUESTED_BY));
    if (!survivors.isEmpty()) {
      log.error("Extraction children survived shutdown: {}", survivors);
    }
  }

  private List<String> stopAndCollect(List<Child> serving, List<Child> retiring,
      StopRequest request) {
    List<CompletableFuture<?>> stops = new ArrayList<>();
    boolean interrupted = Thread.interrupted();
    try {
      if (!serving.isEmpty()) {
        List<ChildHandle> handles = serving.stream().map(child -> child.handle).toList();
        stops.add(control.stopAll(handles, request).toCompletableFuture());
      }
      for (Child child : retiring) {
        stops.add(control.stop(child.handle,
            StopRequest.of(request.reason(), StopMode.NOW, REQUESTED_BY)).toCompletableFuture());
      }
      CompletableFuture<Void> all = CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new));
      long deadline = System.nanoTime() + STOP_WAIT.toNanos();
      while (!all.isDone()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) break;
        try {
          all.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException | TimeoutException survivorOrDeadline) {
          break; // each child's own state says which
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    List<String> survivors = new ArrayList<>();
    for (Slot slot : allSlots) {
      Child child = slot.child;
      if (child == null || !(serving.contains(child) || retiring.contains(child))) continue;
      ChildHandle.State state = child.handle.state();
      if (state == ChildHandle.State.EXITED) {
        slot.child = null;
        child.close();
      } else {
        survivors.add("pid=" + child.pid + " state=" + state);
      }
    }
    return survivors;
  }

  private static String hashArgv(List<String> argv) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      for (String arg : argv) {
        digest.update(arg.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Children spawned since construction (test seam for the pool's reuse property). */
  long spawnCount() {
    return spawnCount.get();
  }

  /** Children discarded since construction (crash, timeout, OOM or request budget). */
  long restartCount() {
    return restartCount.get();
  }

  /** Live PID of the child currently bound to slot 0, or -1. Test seam for "same child reused". */
  long firstChildPid() {
    Child child = allSlots[0].child;
    return child == null ? -1L : child.pid;
  }

  /** One concurrency unit of the pool: at most one request in flight, at most one child. */
  private static final class Slot {
    private volatile Child child;
  }

  private static final class Child {
    private final ChildHandle handle;
    private final long pid;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final StderrTail stderr;
    private final AtomicBoolean inputCloseStarted = new AtomicBoolean();
    private int requests;
    private volatile String retirementReason;

    Child(ChildHandle handle, int maxStderrBytes) {
      this.handle = handle;
      this.pid = handle.pid();
      this.stdin = handle.stdin();
      this.stdout = handle.stdout();
      this.stderr = new StderrTail(handle.stderr(), maxStderrBytes);
    }

    /** After the recorded exit: the pipes are released. */
    void close() {
      stderr.stop();
      if (inputCloseStarted.compareAndSet(false, true)) closeInput();
    }

    void closeInput() {
      try {
        stdin.close();
      } catch (IOException e) {
        // EOF may race child exit; the bounded wait/force path confirms termination.
      }
    }
  }

  /**
   * Drains the child's stderr on a daemon thread, keeping the LAST {@code maxBytes}.
   *
   * <p>Draining is mandatory, not diagnostic: an undrained stderr pipe fills its OS buffer and
   * wedges the child mid-parse, which would look exactly like the hang this sandbox exists to
   * prevent.
   *
   * <p><b>It must keep the tail, not the head.</b> A bounded buffer that stops accepting once full
   * discards the newest bytes — and the newest bytes are the ones that say how the child died. A
   * parser chatty enough to fill 64 KB would push its own {@code OutOfMemoryError} trace out of the
   * capture, {@code tail().contains("OutOfMemoryError")} would be false, and the OOM would be
   * reported as a retryable crash instead of a permanent parse failure. Hence a ring.
   */
  private static final class StderrTail {
    private final byte[] ring;
    private final Thread thread;
    private int size;
    private int end;

    StderrTail(InputStream stream, int maxBytes) {
      this.ring = new byte[Math.max(1, maxBytes)];
      this.thread =
          new Thread(
              () -> {
                byte[] chunk = new byte[4096];
                try (InputStream in = stream) {
                  int n;
                  while ((n = in.read(chunk)) >= 0) {
                    append(chunk, n);
                  }
                } catch (IOException e) {
                  // Child gone; whatever was captured is the tail.
                }
              },
              "extraction-sandbox-stderr");
      thread.setDaemon(true);
      thread.start();
    }

    private synchronized void append(byte[] chunk, int n) {
      for (int i = n > ring.length ? n - ring.length : 0; i < n; i++) {
        ring[end] = chunk[i];
        end = (end + 1) % ring.length;
        if (size < ring.length) {
          size++;
        }
      }
    }

    /**
     * Forgets everything captured so far. Called before each request so the reported tail belongs
     * to the file that actually failed, not to whatever the child logged handling earlier ones.
     */
    synchronized void reset() {
      size = 0;
      end = 0;
    }

    synchronized String tail() {
      byte[] out = new byte[size];
      int begin = (end - size + ring.length) % ring.length;
      for (int i = 0; i < size; i++) {
        out[i] = ring[(begin + i) % ring.length];
      }
      String value =
          new String(out, StandardCharsets.UTF_8).replaceAll("[\\r\\n\\t]+", " ").trim();
      return value.length() <= 512 ? value : value.substring(value.length() - 512);
    }

    void awaitDrain(long millis) {
      try {
        thread.join(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    void stop() {
      thread.interrupt();
    }
  }
}
