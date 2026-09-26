package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor.ExtractionTimeoutException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TimeboxedContentExtractor}.
 */
@DisplayName("TimeboxedContentExtractor")
class TimeboxedContentExtractorTest {

  @TempDir
  Path tempDir;

  private ContentExtractor delegate;
  private TimeboxedContentExtractor extractor;

  @BeforeEach
  void setUp() {
    delegate = new ContentExtractor();
  }

  @AfterEach
  void tearDown() {
    if (extractor != null) {
      extractor.close();
    }
  }

  @Test
  @DisplayName("successful extraction returns result")
  @Timeout(10)
  void successfulExtraction() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Hello World");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Hello World", result.content().trim());
    assertTrue(result.mimeType().startsWith("text/"));
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("IOException is propagated for missing file")
  @Timeout(5)
  void ioExceptionPropagated() {
    Path nonExistent = tempDir.resolve("missing.txt");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(5), null);

    assertThrows(IOException.class, () -> {
      extractor.extract(nonExistent);
    });
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("extractSafe returns empty result on IOException")
  @Timeout(5)
  void extractSafeOnIoException() {
    Path nonExistent = tempDir.resolve("missing.txt");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extractSafe(nonExistent);

    assertEquals("", result.content());
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("minimum timeout is enforced")
  @Timeout(10)
  void minimumTimeoutEnforced() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Content");

    // Try to set a timeout below minimum (100ms < 5s minimum)
    // The extractor should use the minimum timeout instead
    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofMillis(100), null);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Content", result.content().trim());
    // Fast operations should still work even with minimum timeout
  }

  @Test
  @DisplayName("null timeout uses minimum")
  @Timeout(10)
  void nullTimeoutUsesMinimum() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Content");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, null, null);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Content", result.content().trim());
  }

  @Test
  @DisplayName("detectMimeType delegates correctly")
  @Timeout(5)
  void detectMimeTypeDelegates() throws Exception {
    Path textFile = tempDir.resolve("document.txt");
    Files.writeString(textFile, "plain text content");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate);
    String mime = extractor.detectMimeType(textFile);

    assertTrue(mime.startsWith("text/"));
  }

  @Test
  @DisplayName("close shuts down executor cleanly")
  @Timeout(5)
  void closeShutdownsExecutor() {
    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate);
    extractor.close();
    // Should not throw
  }

  @Test
  @DisplayName("default constructor uses default timeout")
  @Timeout(10)
  void defaultConstructor() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Content");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate);
    ExtractionResult result = extractor.extract(textFile);

    assertEquals("Content", result.content().trim());
  }

  @Test
  @DisplayName("markdown file extraction works")
  @Timeout(10)
  void markdownExtraction() throws Exception {
    Path mdFile = tempDir.resolve("readme.md");
    Files.writeString(mdFile, "# Title\n\nSome **bold** text.");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(10), null);
    ExtractionResult result = extractor.extract(mdFile);

    assertTrue(result.content().contains("Title"));
    assertTrue(result.content().contains("bold"));
  }

  @Test
  @DisplayName("empty file returns empty content")
  @Timeout(10)
  void emptyFileReturnsEmptyContent() throws Exception {
    Path emptyFile = tempDir.resolve("empty.txt");
    Files.writeString(emptyFile, "");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extract(emptyFile);

    assertEquals("", result.content());
  }

  @Test
  @DisplayName("html extraction strips tags")
  @Timeout(10)
  void htmlExtraction() throws Exception {
    Path htmlFile = tempDir.resolve("page.html");
    Files.writeString(htmlFile, "<html><body><p>Hello World</p></body></html>");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(10), null);
    ExtractionResult result = extractor.extract(htmlFile);

    assertTrue(result.content().contains("Hello World"));
    // HTML tags should be stripped
    assertFalse(result.content().contains("<p>"));
  }

  @Test
  @DisplayName("timeout count starts at zero")
  @Timeout(5)
  void timeoutCountStartsAtZero() {
    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate);
    assertEquals(0, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("extraction result type checking works")
  @Timeout(10)
  void extractionResultTypes() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    Files.writeString(textFile, "Test content");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate);
    ExtractionResult result = extractor.extract(textFile);

    assertTrue(result.isTextBased());
    assertFalse(result.isPdf());
    assertFalse(result.isOffice());
  }

  @Test
  @DisplayName("extractSafe returns result on success")
  @Timeout(10)
  void extractSafeOnSuccess() throws Exception {
    Path textFile = tempDir.resolve("safe-test.txt");
    Files.writeString(textFile, "Safe content");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), delegate, Duration.ofSeconds(5), null);
    ExtractionResult result = extractor.extractSafe(textFile);

    assertEquals("Safe content", result.content().trim());
  }

  @Test
  @DisplayName("custom provider implementation works through happy path")
  @Timeout(10)
  void customProviderHappyPath() throws Exception {
    ContentExtractorProvider stubProvider =
        new ContentExtractorProvider() {
          @Override
          public ExtractionResult extract(Path file) {
            return new ExtractionResult("stub content", "stub title", "text/plain");
          }

          @Override
          public String detectMimeType(Path file) {
            return "text/plain";
          }
        };

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), stubProvider, Duration.ofSeconds(5), null);
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "ignored");

    ExtractionResult result = extractor.extract(file);
    assertEquals("stub content", result.content());
    assertEquals("stub title", result.title());
    assertEquals("text/plain", result.mimeType());
    assertEquals("text/plain", extractor.detectMimeType(file));
  }

  @Test
  @DisplayName("explicit sandbox can be injected")
  @Timeout(5)
  void explicitSandboxCanBeInjected() throws Exception {
    Path file = tempDir.resolve("sandbox.txt");
    Files.writeString(file, "ignored");

    ExtractionSandbox sandbox =
        path ->
            ExtractionArtifact.full(
                new ExtractionResult("sandbox content", "sandbox title", "text/plain"),
                "InjectedSandbox");

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), sandbox, Duration.ofSeconds(5), null);

    ExtractionArtifact artifact = extractor.extractArtifact(file);
    assertEquals("sandbox content", artifact.result().content());
    assertEquals("InjectedSandbox", artifact.parserId());
    assertEquals("application/octet-stream", extractor.detectMimeType(file));
  }

  @Test
  @DisplayName("artifact validation rejects failed sandbox responses")
  void artifactValidationRejectsFailedStatus() {
    ExtractionArtifact artifact =
        new ExtractionArtifact(
            ExtractionStatus.FAILED,
            new ExtractionResult("bad", null, "text/plain"),
            "policy",
            "parser",
            false,
            java.util.List.of());

    assertThrows(
        ContentExtractor.ExtractionException.class,
        () -> artifact.validateContentBoundsOnly(1024));
  }

  @Test
  @DisplayName("extract times out deterministically with injected delegate")
  @Timeout(5)
  void extractTimesOutDeterministically() throws Exception {
    Path file = tempDir.resolve("slow.bin");
    Files.writeString(file, "x");

    // Provider that blocks until interrupted (so timeout path is deterministic).
    ContentExtractorProvider slowDelegate =
        new ContentExtractorProvider() {
          @Override
          public ExtractionResult extract(Path f) throws IOException, ContentExtractor.ExtractionException {
            CountDownLatch never = new CountDownLatch(1);
            try {
              // Wait "forever" unless interrupted by cancellation.
              never.await(60, TimeUnit.SECONDS);
              return new ExtractionResult("unexpected", null, "application/octet-stream");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              // If we were interrupted, just simulate a fast termination.
              return new ExtractionResult("", null, "application/octet-stream");
            }
          }

          @Override
          public String detectMimeType(Path f) {
            return "application/octet-stream";
          }
        };

    extractor = new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), slowDelegate, Duration.ofMillis(50), null, false);

    assertThrows(ExtractionTimeoutException.class, () -> extractor.extract(file));
    assertEquals(1, extractor.getTimeoutCount());
  }

  @Test
  @DisplayName("after a wedged extraction times out, the next file extracts normally")
  @Timeout(30)
  void wedgedExtractionDoesNotPoisonTheExecutor() throws Exception {
    // Tempdoc 885 item 14 [R7]: the executor was a single-thread executor and cancel(true) only
    // interrupts. A parser that ignores the interrupt held that one thread forever, so the NEXT
    // file — and every file after it — timed out too: one bad file stopped ALL extraction until
    // the Worker restarted. This sandbox reproduces exactly that: it ignores interruption.
    Path file = tempDir.resolve("wedge.bin");
    Files.writeString(file, "x");

    AtomicBoolean release = new AtomicBoolean(false);
    AtomicInteger calls = new AtomicInteger();
    ExtractionSandbox wedgingSandbox =
        path -> {
          if (calls.incrementAndGet() == 1) {
            while (!release.get()) {
              // parkNanos returns on interrupt WITHOUT throwing, so this loop survives cancel().
              LockSupport.parkNanos(1_000_000L);
            }
            return ExtractionArtifact.full(
                new ExtractionResult("late", null, "text/plain"), "wedged");
          }
          return ExtractionArtifact.full(
              new ExtractionResult("second file", null, "text/plain"), "recovered");
        };

    extractor =
        new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, wedgingSandbox, Duration.ofMillis(200), null, false);
    try {
      assertThrows(ExtractionTimeoutException.class, () -> extractor.extractArtifact(file));
      assertEquals(1, extractor.getTimeoutCount());

      ExtractionArtifact next = extractor.extractArtifact(file);
      assertEquals("second file", next.result().content());
      assertEquals(1, extractor.getTimeoutCount(), "the second extraction must not have timed out");
    } finally {
      release.set(true);
    }
  }

  @Test
  @DisplayName("a second wedge exhausts the two-generation bound without hiding its timeout")
  @Timeout(10)
  void thirdGenerationIsRefusedAndLaterExtractionGetsTypedClosedRefusal() throws Exception {
    Path file = Files.writeString(tempDir.resolve("two-wedges.bin"), "x");
    AtomicBoolean release = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(2);
    ExtractionSandbox sandbox =
        path -> {
          entered.countDown();
          while (!release.get()) {
            LockSupport.parkNanos(1_000_000L);
          }
          return ExtractionArtifact.full(
              new ExtractionResult("late", null, "text/plain"), "wedged");
        };
    CappedTimeboxRegistration registration = new CappedTimeboxRegistration();
    extractor =
        new TimeboxedContentExtractor(
            registration, null, sandbox, Duration.ofMillis(100), null, false);

    try {
      assertThrows(ExtractionTimeoutException.class, () -> extractor.extractArtifact(file));
      assertThrows(
          ExtractionTimeoutException.class,
          () -> extractor.extractArtifact(file),
          "the extraction which discovers the instance limit retains its timeout outcome");
      assertEquals(0, entered.getCount(), "both admitted generations must reach the parser");

      EngineExecutorRejectedException refusal =
          assertThrows(
              EngineExecutorRejectedException.class, () -> extractor.extractArtifact(file));
      assertEquals(Reason.CLOSED, refusal.reason());
      assertEquals(2, registration.openedCount());
    } finally {
      release.set(true);
    }
  }

  private static final class CappedTimeboxRegistration
      implements EngineExecutorRegistry.Registration {
    private static final EngineExecutorSpec SPEC =
        new EngineExecutorSpec("test-timebox-cap", Kind.BACKGROUND, Mode.PLATFORM, 1, 4, 2);

    private final java.util.List<ExecutorService> instances = new java.util.ArrayList<>();

    @Override
    public EngineExecutorSpec spec() {
      return SPEC;
    }

    @Override
    public synchronized ExecutorService open(ThreadFactory threadFactory) {
      instances.removeIf(ExecutorService::isTerminated);
      if (instances.size() >= SPEC.maxInstances()) {
        throw new EngineExecutorRejectedException(Reason.INSTANCE_LIMIT, SPEC.name(), 1);
      }
      ThreadPoolExecutor executor =
          new ThreadPoolExecutor(
              1,
              1,
              0,
              TimeUnit.MILLISECONDS,
              new java.util.concurrent.ArrayBlockingQueue<>(SPEC.queueCapacity()),
              threadFactory,
              (task, owner) -> {
                Reason reason = owner.isShutdown() ? Reason.CLOSED : Reason.QUEUE_LIMIT;
                throw new EngineExecutorRejectedException(reason, SPEC.name(), 1);
              });
      instances.add(executor);
      return executor;
    }

    int openedCount() {
      return instances.size();
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory threadFactory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutorService openVirtual() {
      throw new UnsupportedOperationException();
    }

    @Override
    public synchronized void close() {
      instances.forEach(ExecutorService::shutdownNow);
    }
  }
}
