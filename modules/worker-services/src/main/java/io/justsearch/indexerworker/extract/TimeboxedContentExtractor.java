/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A content extractor wrapper that enforces a timeout on extraction operations.
 *
 * <p>This prevents a single pathological file from hanging the entire indexing loop.
 * When extraction times out:
 * <ul>
 *   <li>The extraction task is cancelled (best-effort; native parsers may not respond)</li>
 *   <li>An {@link ExtractionTimeoutException} is thrown</li>
 *   <li>A timeout counter is incremented for observability</li>
 * </ul>
 *
 * <p>Thread safety: This class is thread-safe. The underlying executor is a single-thread
 * executor to ensure isolation between extraction tasks.
 *
 * <p><b>Tempdoc 885 item 14 — a timed-out extraction no longer poisons the executor.</b> A wedged
 * native parser ignores {@code cancel(true)}, so the single worker thread used to stay occupied
 * forever and every subsequent file queued behind it: one bad file stopped <em>all</em> extraction
 * until the Worker restarted. On a timeout the executor is now replaced, so the next file gets a
 * fresh thread. <b>Honest residual for the in-process families:</b> the wedged thread itself is not
 * killable from the JVM — it is a daemon thread that leaks (holding its parser's memory) until the
 * process exits. Only the {@link PersistentExtractionSandbox} path genuinely reclaims the work, by
 * killing the child. That is the reason the wedge-prone families default to {@code process}
 * routing ({@link RoutingExtractionSandbox}).
 *
 * @see ContentExtractor
 */
public final class TimeboxedContentExtractor implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(TimeboxedContentExtractor.class);

  /** Default extraction timeout (60 seconds). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /** Minimum allowed timeout (5 seconds). */
  public static final Duration MIN_TIMEOUT = Duration.ofSeconds(5);

  private final ContentExtractorProvider delegate;
  private final ExtractionSandbox sandbox;
  private final Duration timeout;
  private final AtomicReference<ExecutorService> executor = new AtomicReference<>();
  private final AtomicLong executorGeneration = new AtomicLong();

  // Observability counters
  private final AtomicLong timeoutCount = new AtomicLong(0);
  private final ExtractionMetricCatalog catalog;
  private final EngineExecutorRegistry.Registration executorRegistration;

  // Rate-limited logging for timeouts
  private volatile long lastTimeoutLogMs = 0;
  private static final long TIMEOUT_LOG_INTERVAL_MS = 10_000; // 10 seconds

  /**
   * Creates a timeboxed content extractor with default timeout and no telemetry.
   *
   * @param delegate the underlying content extractor provider
   */
  public TimeboxedContentExtractor(
      EngineExecutorRegistry.Registration executorRegistration,
      ContentExtractorProvider delegate) {
    this(executorRegistration, delegate, DEFAULT_TIMEOUT, null);
  }

  /** Creates a timeboxed extractor around an explicit sandbox implementation. */
  public TimeboxedContentExtractor(
      EngineExecutorRegistry.Registration executorRegistration,
      ExtractionSandbox sandbox,
      Duration timeout,
      ExtractionMetricCatalog catalog) {
    this(
        executorRegistration,
        null,
        Objects.requireNonNull(sandbox, "sandbox"),
        timeout,
        catalog,
        true);
  }

  /**
   * Creates a timeboxed content extractor with specified timeout and optional telemetry.
   *
   * @param delegate the underlying content extractor provider
   * @param timeout extraction timeout (minimum 5 seconds)
   * @param catalog extraction metric catalog (may be null for tests)
   */
  public TimeboxedContentExtractor(
      EngineExecutorRegistry.Registration executorRegistration,
      ContentExtractorProvider delegate,
      Duration timeout,
      ExtractionMetricCatalog catalog) {
    this(
        executorRegistration,
        Objects.requireNonNull(delegate, "delegate"),
        new InProcessExtractionSandbox(delegate),
        timeout,
        catalog,
        true);
  }

  /**
   * Internal constructor that allows tests to bypass {@link #MIN_TIMEOUT}.
   */
  TimeboxedContentExtractor(
      EngineExecutorRegistry.Registration executorRegistration,
      ContentExtractorProvider delegate,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      boolean enforceMinTimeout) {
    this(
        executorRegistration,
        Objects.requireNonNull(delegate, "delegate"),
        new InProcessExtractionSandbox(delegate),
        timeout,
        catalog,
        enforceMinTimeout);
  }

  TimeboxedContentExtractor(
      EngineExecutorRegistry.Registration executorRegistration,
      ContentExtractorProvider delegate,
      ExtractionSandbox sandbox,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      boolean enforceMinTimeout) {
    this.executorRegistration =
        Objects.requireNonNull(executorRegistration, "executorRegistration");
    this.delegate = delegate;
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    if (enforceMinTimeout) {
      this.timeout = timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : timeout;
    } else {
      this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }
    // Use a single-thread executor to isolate extraction work
    this.executor.set(newExtractionExecutor(executorGeneration.get()));
    this.catalog = catalog;
  }

  private ExecutorService newExtractionExecutor(long generation) {
    return executorRegistration.open(
        r -> {
          Thread t = new Thread(r, "ContentExtractor-Timebox-" + generation);
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * Extracts text content from a file with timeout enforcement.
   *
   * @param file the file to extract content from
   * @return the extraction result
   * @throws IOException if the file cannot be read
   * @throws ExtractionException if Tika parsing fails
   * @throws ExtractionTimeoutException if extraction exceeds the configured timeout
   */
  public ExtractionResult extract(Path file) throws IOException, ExtractionException {
    return extractArtifact(file).result();
  }

  /** Extracts a bounded artifact with timeout enforcement. */
  public ExtractionArtifact extractArtifact(Path file) throws IOException, ExtractionException {
    Objects.requireNonNull(file, "file");

    // The task reports its own completion: Future.isDone() is TRUE the moment cancel() succeeds,
    // even while the task thread runs on, so it cannot answer "did the thread come back?".
    AtomicBoolean taskReturned = new AtomicBoolean();
    Callable<ExtractionArtifact> task =
        () -> {
          try {
            return sandbox.extract(file);
          } finally {
            taskReturned.set(true);
          }
        };
    ExecutorService current = executor.get();
    Future<ExtractionArtifact> future = current.submit(task);

    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Best-effort cancellation
      future.cancel(true);
      replaceIfWedged(current, taskReturned);
      recordTimeout(file);
      throw new ExtractionTimeoutException(
          "Extraction timed out after " + timeout.toSeconds() + "s for: " + file.getFileName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new ExtractionException("Extraction interrupted for: " + file.getFileName(), e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException ioe) {
        throw ioe;
      } else if (cause instanceof ExtractionException ee) {
        throw ee;
      } else {
        throw new ExtractionException("Extraction failed for: " + file.getFileName(), cause);
      }
    } catch (CancellationException e) {
      throw new ExtractionException("Extraction was cancelled for: " + file.getFileName(), e);
    }
  }

  /**
   * Attempts to extract content, returning an empty result on failure (including timeout).
   *
   * @param file the file to extract content from
   * @return the extraction result, or empty result if extraction fails
   */
  public ExtractionResult extractSafe(Path file) {
    try {
      return extract(file);
    } catch (IOException | ExtractionException e) {
      log.debug("Safe extraction failed for {}: {}", file, e.getMessage());
      return new ExtractionResult("", null, detectMimeType(file));
    }
  }

  /**
   * Detects the MIME type of a file without timeout (fast operation).
   *
   * @param file the file to detect
   * @return the detected MIME type
   */
  public String detectMimeType(Path file) {
    return delegate != null ? delegate.detectMimeType(file) : "application/octet-stream";
  }

  public TikaExtractionPolicy extractionPolicy() {
    return sandbox.policy();
  }

  /**
   * Returns the total number of extraction timeouts since this extractor was created.
   */
  public long getTimeoutCount() {
    return timeoutCount.get();
  }

  /**
   * Replaces the executor when a timed-out task did not actually stop.
   *
   * <p>{@code cancel(true)} interrupts, and a native parser mid-{@code read} ignores it. Without
   * this, the single worker thread stays occupied by the wedged task and every later file blocks
   * on {@code submit} forever — the "one bad file stops all extraction" defect. Called only on the
   * timeout path, so a healthy run keeps exactly one executor for the extractor's whole life.
   */
  private void replaceIfWedged(ExecutorService current, AtomicBoolean taskReturned) {
    if (taskReturned.get()) {
      return;
    }
    ExecutorService replacement;
    try {
      replacement = newExtractionExecutor(executorGeneration.incrementAndGet());
    } catch (EngineExecutorRejectedException refused) {
      // The extraction that discovered the wedge still owns its truthful timeout outcome. Retire
      // this unusable executor so every later submission receives a typed refusal instead of
      // queueing forever behind the wedged task.
      shutdownAndCancelQueued(current);
      log.warn("Extraction executor replacement refused: {}", refused.getMessage());
      return;
    }
    if (executor.compareAndSet(current, replacement)) {
      // shutdownNow() re-interrupts and prevents new work reaching the wedged thread; it does NOT
      // stop the running task (nothing can), so the thread leaks by design until the JVM exits.
      shutdownAndCancelQueued(current);
      log.warn(
          "Extraction executor thread wedged past the timeout; replaced with generation {}",
          executorGeneration.get());
    } else {
      shutdownAndCancelQueued(replacement);
    }
  }

  private void recordTimeout(Path file) {
    long count = timeoutCount.incrementAndGet();
    if (catalog != null) {
      catalog.timeoutTotal.increment(ExtractionTimeoutTags.of());
    }

    // Rate-limited warning log
    long now = System.currentTimeMillis();
    if (now - lastTimeoutLogMs > TIMEOUT_LOG_INTERVAL_MS) {
      lastTimeoutLogMs = now;
      log.warn("Extraction timeout for {} (total timeouts: {})", file.getFileName(), count);
    } else {
      log.debug("Extraction timeout for {} (total timeouts: {})", file.getFileName(), count);
    }
  }

  @Override
  public void close() {
    ExecutorService current = executor.get();
    shutdownAndCancelQueued(current);
    try {
      if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Extraction executor did not terminate cleanly");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while shutting down extraction executor");
    }
    // Closes parser children and the in-process extractor's component-owned OCR pool.
    sandbox.close();
  }

  private static void shutdownAndCancelQueued(ExecutorService executor) {
    for (Runnable queued : executor.shutdownNow()) {
      if (queued instanceof Future<?> future) {
        future.cancel(false);
      }
    }
  }

  /**
   * Exception thrown when content extraction exceeds the configured timeout.
   */
  public static class ExtractionTimeoutException extends ExtractionException {
    public ExtractionTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
