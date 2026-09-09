package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Engine behaviour is exercised entirely through the process-factory seam — no real tesseract
 * binary is needed, so these run green on CI. Rendering is real (PDFBox on tiny fixture PDFs); the
 * stub child "OCRs" by writing the {@code .txt}/{@code .tsv} files tesseract would have written.
 */
final class PdfOcrEngineTest {
  @TempDir Path tempDir;

  private static final OcrRoutingConfig CONFIG =
      new OcrRoutingConfig(true, List.of("eng"), 30_000, null, null, null, null, null);

  @Test
  @Timeout(30)
  void joinsPagesInPageOrderRegardlessOfCompletionOrder() throws Exception {
    Path pdf = blankPdf(3);
    // Later pages complete first; page-order join must still order the markers 1,2,3.
    long[] blockMs = {200L, 100L, 20L};
    PdfOcrEngine engine =
        engine(CONFIG, 4, cmd -> succeedStub(cmd, blockMs));

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    String text = result.text();
    assertTrue(text.contains("--- OCR page 1 ---"), text);
    assertTrue(text.contains("PAGEWORD0") && text.contains("PAGEWORD1") && text.contains("PAGEWORD2"), text);
    assertTrue(
        text.indexOf("--- OCR page 1 ---") < text.indexOf("--- OCR page 2 ---")
            && text.indexOf("--- OCR page 2 ---") < text.indexOf("--- OCR page 3 ---"),
        "markers must be page-ordered: " + text);
    assertEquals(3, result.pagesProcessed());
    assertFalse(result.truncated());
    assertNull(result.failureReason());
    assertTrue(result.confidence().present(), "confidence should aggregate from per-page TSVs");
  }

  @Test
  @Timeout(30)
  void inLoopPageCapMarksTruncated() throws Exception {
    Path pdf = blankPdf(3);
    OcrRoutingConfig capped =
        new OcrRoutingConfig(true, List.of("eng"), 30_000, 2, null, null, null, null);
    PdfOcrEngine engine = engine(capped, 4, cmd -> succeedStub(cmd, new long[] {0L, 0L, 0L}));

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertEquals(2, result.pagesProcessed());
    assertTrue(result.truncated(), "processing fewer than all pages must flag truncated");
    assertFalse(result.text().contains("--- OCR page 3 ---"), result.text());
  }

  @Test
  @Timeout(30)
  void aggregateBudgetExpiryReturnsPartialOrderedTextTruncated() throws Exception {
    Path pdf = blankPdf(3);
    // Budget 1s; page 0 instant, later pages block well past the budget.
    OcrRoutingConfig budgeted = new OcrRoutingConfig(true, List.of("eng"), 1_000, null, null, null, null, null);
    long[] blockMs = {0L, 5_000L, 5_000L};
    PdfOcrEngine engine = engine(budgeted, 4, cmd -> succeedStub(cmd, blockMs));

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertTrue(result.text().contains("PAGEWORD0"), "page 0 completed before the budget: " + result.text());
    assertFalse(result.text().contains("PAGEWORD1"), "budget must stop before slow pages: " + result.text());
    assertTrue(result.truncated(), "budget expiry must flag truncated");
    assertTrue(result.pagesProcessed() >= 1 && result.pagesProcessed() < 3);
  }

  @Test
  @Timeout(30)
  void timeoutWithZeroCompletedPagesReportsTimeout() throws Exception {
    Path pdf = blankPdf(2);
    OcrRoutingConfig budgeted = new OcrRoutingConfig(true, List.of("eng"), 1_000, null, null, null, null, null);
    PdfOcrEngine engine = engine(budgeted, 4, cmd -> succeedStub(cmd, new long[] {9_000L, 9_000L}));

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertEquals(0, result.pagesProcessed());
    assertTrue(result.text().isBlank());
    assertEquals(OcrSkipReason.TIMEOUT, result.failureReason());
  }

  @Test
  @Timeout(30)
  void spawnFailureReportsUnknown() throws Exception {
    Path pdf = blankPdf(1);
    PdfOcrEngine engine =
        engine(
            CONFIG,
            4,
            cmd -> {
              throw new IOException("cannot start tesseract");
            });

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertEquals(0, result.pagesProcessed());
    assertEquals(OcrSkipReason.UNKNOWN, result.failureReason());
  }

  @Test
  @Timeout(30)
  void interruptDestroysAllRegisteredChildren() throws Exception {
    Path pdf = blankPdf(3);
    List<StubProcess> spawned = Collections.synchronizedList(new java.util.ArrayList<>());
    CountDownLatch allStarted = new CountDownLatch(3);
    PdfOcrEngine engine =
        engine(
            CONFIG,
            4,
            cmd -> {
              StubProcess stub = new StubProcess(0, 60_000L); // blocks far longer than the test
              spawned.add(stub);
              allStarted.countDown();
              return stub;
            });

    AtomicReference<PdfOcrEngine.OcrEngineResult> out = new AtomicReference<>();
    Thread worker = new Thread(() -> out.set(engine.ocrPdf(pdf, Integer.MAX_VALUE)), "engine-under-test");
    worker.start();
    assertTrue(allStarted.await(10, TimeUnit.SECONDS), "all page children should have spawned");
    worker.interrupt();
    worker.join(TimeUnit.SECONDS.toMillis(15));

    assertFalse(worker.isAlive(), "engine must unwind promptly on interrupt");
    assertEquals(3, spawned.size());
    for (StubProcess stub : spawned) {
      assertTrue(
          stub.wasForceDestroyed(),
          "every registered child must be destroyForcibly'd (not gracefully destroy'd) on interrupt");
    }
  }

  /**
   * Regression for the CI-only flake (run 29159503718): a worker interrupted between {@code
   * starter.start()} returning and its own {@code live.add(process)} — i.e., not yet
   * registered — must still end up force-destroyed. The 3rd starter call to run deliberately
   * pauses for the full window via {@link #uninterruptibleSleep} (immune to the engine's cancel
   * signal, modeling a scheduler pause) so its {@code live.add(process)} and {@code
   * process.waitFor(...)} both run strictly *after* the engine's cancel/interrupt signal and after
   * a naive, non-waiting {@code shutdownNow()} would already have returned to the caller. Without
   * {@code shutdownAndAwaitTermination} blocking for the pool to actually quiesce before the final
   * {@code killAll} sweep, this reproduces the miss deterministically; with it, that worker thread
   * still finishes unwinding (its pre-set interrupted status makes the next {@code
   * Process.waitFor} throw immediately) before the sweep runs.
   */
  @Test
  @Timeout(30)
  void interruptDestroysChildStillRegisteringWhenCancelFires() throws Exception {
    Path pdf = blankPdf(3);
    List<StubProcess> spawned = Collections.synchronizedList(new java.util.ArrayList<>());
    CountDownLatch allStarted = new CountDownLatch(3);
    AtomicInteger callIndex = new AtomicInteger();
    PdfOcrEngine engine =
        engine(
            CONFIG,
            4,
            cmd -> {
              StubProcess stub = new StubProcess(0, 60_000L); // blocks far longer than the test
              spawned.add(stub);
              int thisCall = callIndex.getAndIncrement();
              allStarted.countDown();
              if (thisCall == 2) {
                // Simulate a scheduler pause landing between "spawned" (visible to the latch) and
                // this starter returning control to runTesseract's live.add(process) — the
                // registration gap. Uninterruptible-for-the-full-window: an interrupt landing
                // mid-pause must NOT shorten it (a plain try/sleep/catch lets the FIRST interrupt
                // truncate the wait early, making the race non-deterministic — observed ~20%
                // failure rate — because it then races a SECOND, independent interrupt delivery
                // from shutdownNow() against killAll() instead of reliably outlasting both).
                uninterruptibleSleep(300L);
              }
              return stub;
            });

    AtomicReference<PdfOcrEngine.OcrEngineResult> out = new AtomicReference<>();
    Thread worker = new Thread(() -> out.set(engine.ocrPdf(pdf, Integer.MAX_VALUE)), "engine-under-test");
    worker.start();
    assertTrue(allStarted.await(10, TimeUnit.SECONDS), "all page children should have spawned");
    worker.interrupt();
    worker.join(TimeUnit.SECONDS.toMillis(15));

    assertFalse(worker.isAlive(), "engine must unwind promptly on interrupt");
    assertEquals(3, spawned.size());
    for (StubProcess stub : spawned) {
      assertTrue(
          stub.wasForceDestroyed(),
          "every registered child must be destroyForcibly'd, including one still registering when"
              + " cancellation fired");
    }
  }

  @Test
  @Timeout(30)
  void oversizePageIsSkippedBeforeRenderWithoutSpawn() throws Exception {
    // F1 regression: the guard fires PRE-render (predicted dims from the media box at the
    // engine's DPI), so the oversized page buffer is never allocated and no child is spawned.
    // Test engine renders at 72 DPI (scale 1.0): Letter = 612x792 px passes maxImageDimension
    // 1000; the 5000x5000pt page predicts 5000x5000 px and trips it.
    Path pdf = pdfWithPages(PDRectangle.LETTER, new PDRectangle(5000, 5000));
    OcrRoutingConfig guarded = new OcrRoutingConfig(true, List.of("eng"), 30_000, null, 1000, null, null, null);
    AtomicInteger spawns = new AtomicInteger();
    PdfOcrEngine engine =
        engine(
            guarded,
            4,
            cmd -> {
              spawns.incrementAndGet();
              return succeedStub(cmd, new long[] {0L, 0L});
            });

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertEquals(1, spawns.get(), "the oversized page must never reach a tesseract spawn");
    assertEquals(1, result.pagesProcessed());
    assertTrue(result.text().contains("PAGEWORD0"), result.text());
    assertFalse(result.text().contains("--- OCR page 2 ---"), result.text());
    assertTrue(result.truncated(), "skipping an oversized page leaves content honestly incomplete");
    assertNull(result.failureReason());
  }

  @Test
  @Timeout(30)
  void allPagesOversizeReportsSizeWithZeroSpawns() throws Exception {
    Path pdf = pdfWithPages(new PDRectangle(5000, 5000), new PDRectangle(6000, 4000));
    OcrRoutingConfig guarded = new OcrRoutingConfig(true, List.of("eng"), 30_000, null, 1000, null, null, null);
    AtomicInteger spawns = new AtomicInteger();
    PdfOcrEngine engine =
        engine(
            guarded,
            4,
            cmd -> {
              spawns.incrementAndGet();
              return succeedStub(cmd, new long[] {0L, 0L});
            });

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertEquals(0, spawns.get(), "no page may render or spawn when all pages are oversize");
    assertEquals(0, result.pagesProcessed());
    assertTrue(result.text().isBlank());
    assertTrue(result.truncated());
    assertEquals(OcrSkipReason.SIZE, result.failureReason());
  }

  @Test
  @Timeout(30)
  void productionPathReResolvesRuntimePerEngineCall() throws Exception {
    // Regression: the "Install AI" flow can restore the app-owned tesseract runtime while the
    // worker is running; a construction-time resolution snapshot would keep spawning a stale
    // executable forever (standalone-capability-stays-stuck). Two calls, mutated resolution
    // between them — the second call must use the newly resolved runtime.
    Path image = tempDir.resolve("input.png");
    Files.writeString(image, "fixture");
    Path alphaExe = writeFakeTesseract(tempDir.resolve("runtime-alpha"), "RUNTIME ALPHA TEXT");
    Path bravoExe = writeFakeTesseract(tempDir.resolve("runtime-bravo"), "RUNTIME BRAVO TEXT");
    AtomicReference<TikaOcrRuntime.RuntimePaths> resolved =
        new AtomicReference<>(runtimePaths(alphaExe));
    PdfOcrEngine engine = new PdfOcrEngine(testPoolFactory(), CONFIG, resolved::get, 72, 2, null);

    String first = engine.ocrImage(image, Integer.MAX_VALUE).text();
    resolved.set(runtimePaths(bravoExe));
    String second = engine.ocrImage(image, Integer.MAX_VALUE).text();

    assertTrue(first.contains("ALPHA"), "first call must use the initially resolved runtime: " + first);
    assertTrue(second.contains("BRAVO"), "second call must pick up the newly restored runtime: " + second);
  }

  @Test
  @Timeout(30)
  void pdfPoolIsClosedAndTerminatedBeforeOcrReturns() throws Exception {
    Path pdf = blankPdf(1);
    AtomicReference<ExecutorService> opened = new AtomicReference<>();
    IntFunction<ExecutorService> factory =
        size -> {
          ExecutorService pool = Executors.newFixedThreadPool(size);
          opened.set(pool);
          return pool;
        };
    PdfOcrEngine engine =
        new PdfOcrEngine(factory, CONFIG, cmd -> succeedStub(cmd, new long[] {0L}), "stub", 72, 2, null);

    PdfOcrEngine.OcrEngineResult result = engine.ocrPdf(pdf, Integer.MAX_VALUE);

    assertEquals(1, result.pagesProcessed());
    assertTrue(opened.get().isShutdown());
    assertTrue(opened.get().isTerminated(), "pool must terminate before temp/process cleanup");
  }

  @Test
  @Timeout(30)
  void typedPoolRefusalEscapesAfterPoolCleanup() throws Exception {
    Path pdf = blankPdf(1);
    EngineExecutorRejectedException refusal =
        new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "ocr", 1);
    AtomicReference<ExecutorService> opened = new AtomicReference<>();
    IntFunction<ExecutorService> factory =
        size -> {
          ExecutorService pool =
              new ThreadPoolExecutor(
                  1,
                  1,
                  0L,
                  TimeUnit.MILLISECONDS,
                  new SynchronousQueue<>()) {
                @Override
                public void execute(Runnable command) {
                  throw refusal;
                }
              };
          opened.set(pool);
          return pool;
        };
    PdfOcrEngine engine =
        new PdfOcrEngine(factory, CONFIG, cmd -> succeedStub(cmd, new long[] {0L}), "stub", 72, 2, null);

    EngineExecutorRejectedException failure =
        assertThrows(
            EngineExecutorRejectedException.class,
            () -> engine.ocrPdf(pdf, Integer.MAX_VALUE));

    assertSame(refusal, failure);
    assertTrue(opened.get().isTerminated(), "refusing pool must close before cleanup completes");
  }

  @Test
  @Timeout(30)
  void fatalChildFailureEscapesAfterActualPoolAndTempCleanup() throws Exception {
    Path pdf = blankPdf(1);
    AtomicReference<ExecutorService> opened = new AtomicReference<>();
    AtomicReference<Path> actualTemp = new AtomicReference<>();
    AssertionError fatal = new AssertionError("fatal OCR child");
    PdfOcrEngine engine = new PdfOcrEngine(size -> {
      ExecutorService pool = Executors.newFixedThreadPool(size);
      opened.set(pool);
      return pool;
    }, CONFIG, cmd -> {
      actualTemp.set(Path.of(cmd.get(1)).getParent());
      throw fatal;
    }, "stub", 72, 1, null);

    assertSame(fatal, assertThrows(AssertionError.class,
        () -> engine.ocrPdf(pdf, Integer.MAX_VALUE)));
    assertTrue(opened.get().isTerminated());
    assertNotNull(actualTemp.get(), "the real OCR child must have entered");
    assertFalse(Files.exists(actualTemp.get()), "actual OCR temp directory must be deleted");
  }

  @Test
  @Timeout(30)
  void callerInterruptionWaitsForActualChildExitBeforeDeletingTemp() throws Exception {
    Path pdf = blankPdf(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Path> actualTemp = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean restored = new java.util.concurrent.atomic.AtomicBoolean();
    PdfOcrEngine engine = engine(CONFIG, 1, cmd -> {
      actualTemp.set(Path.of(cmd.get(1)).getParent());
      entered.countDown();
      while (release.getCount() != 0) {
        try { release.await(); }
        catch (InterruptedException expected) { interrupted.countDown(); }
      }
      return succeedStub(cmd, new long[] {0L});
    });
    Thread caller = Thread.ofPlatform().start(() -> {
      try { engine.ocrPdf(pdf, Integer.MAX_VALUE); }
      catch (Throwable thrown) { failure.set(thrown); }
      finally { restored.set(Thread.currentThread().isInterrupted()); }
    });
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      caller.interrupt();
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      caller.join(150);
      assertTrue(caller.isAlive(), "cancelled Future is not actual child exit");
      assertTrue(Files.exists(actualTemp.get()), "live child still owns its files");
      release.countDown();
      caller.join(2000);
      assertFalse(caller.isAlive());
      assertNull(failure.get());
      assertTrue(restored.get());
      assertFalse(Files.exists(actualTemp.get()));
    } finally {
      release.countDown();
      caller.interrupt();
      caller.join(2000);
    }
  }

  @Test
  @Timeout(30)
  void asynchronousProcessKillMustExitBeforeTempDeletion() throws Exception {
    Path pdf = blankPdf(1);
    var process = org.mockito.Mockito.mock(Process.class);
    var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
    var killRequested = new CountDownLatch(1);
    var allowExit = new CountDownLatch(1);
    var actualTemp = new AtomicReference<Path>();
    var failure = new AtomicReference<Throwable>();
    org.mockito.Mockito.when(process.isAlive()).thenAnswer(call -> alive.get());
    org.mockito.Mockito.when(process.destroyForcibly()).thenAnswer(call -> {
      killRequested.countDown();
      return process;
    });
    org.mockito.Mockito.when(process.waitFor()).thenAnswer(call -> {
      allowExit.await();
      alive.set(false);
      return 0;
    });
    // Timed wait defaults to false: OCR times out and requests an asynchronous kill.
    PdfOcrEngine engine = engine(CONFIG, 1, cmd -> {
      actualTemp.set(Path.of(cmd.get(1)).getParent());
      return process;
    });
    Thread caller = Thread.ofPlatform().start(() -> {
      try { engine.ocrPdf(pdf, Integer.MAX_VALUE); }
      catch (Throwable thrown) { failure.set(thrown); }
    });
    try {
      assertTrue(killRequested.await(2, TimeUnit.SECONDS));
      caller.join(150);
      assertTrue(caller.isAlive(), "destroyForcibly returning is not process exit");
      assertTrue(Files.exists(actualTemp.get()));
      allowExit.countDown();
      caller.join(2000);
      assertFalse(caller.isAlive());
      assertNull(failure.get());
      assertFalse(Files.exists(actualTemp.get()));
      assertFalse(process.isAlive());
    } finally {
      allowExit.countDown();
      caller.interrupt();
      caller.join(2000);
    }
  }

  // --- helpers -------------------------------------------------------------

  private PdfOcrEngine engine(OcrRoutingConfig config, int poolSize, PdfOcrEngine.ProcessStarter starter) {
    return new PdfOcrEngine(testPoolFactory(), config, starter, "stub-tesseract", 72, poolSize, null);
  }

  private static IntFunction<ExecutorService> testPoolFactory() {
    return size -> Executors.newFixedThreadPool(size);
  }

  private static TikaOcrRuntime.RuntimePaths runtimePaths(Path executable) {
    return new TikaOcrRuntime.RuntimePaths(executable, executable.getParent(), null);
  }

  /** A fake tesseract script that writes {@code <outputBase>.txt} the way the real binary would. */
  private static Path writeFakeTesseract(Path directory, String text) throws IOException {
    Files.createDirectories(directory);
    boolean windows =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    Path executable = directory.resolve(windows ? "tesseract.cmd" : "tesseract");
    String script =
        windows
            ? "@echo off\r\necho " + text + "> \"%~2.txt\"\r\nexit /b 0\r\n"
            : "#!/usr/bin/env sh\nprintf '" + text + "' > \"$2.txt\"\nexit 0\n";
    Files.writeString(executable, script);
    executable.toFile().setExecutable(true, false);
    return executable;
  }

  /** Writes the .txt/.tsv a real tesseract would emit, then returns a fast exit-0 stub. */
  private static StubProcess succeedStub(List<String> command, long[] blockMsByPage) throws IOException {
    int pageIndex = pageIndexOf(command);
    Path base = Path.of(command.get(2));
    Files.writeString(Path.of(base + ".txt"), "PAGEWORD" + pageIndex + " content", StandardCharsets.UTF_8);
    Files.writeString(
        Path.of(base + ".tsv"),
        "level\tpage\tblock\tpar\tline\tword\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t90\tPAGEWORD"
            + pageIndex
            + "\n",
        StandardCharsets.UTF_8);
    long block = pageIndex >= 0 && pageIndex < blockMsByPage.length ? blockMsByPage[pageIndex] : 0L;
    return new StubProcess(0, block);
  }

  /**
   * Sleeps the full {@code millis} window regardless of how many interrupts land during it —
   * models a scheduler pause the engine's cancel signal cannot shorten, as opposed to a plain
   * try/sleep/catch (which lets the first interrupt truncate the wait, making the reproduction
   * timing-dependent instead of deterministic).
   */
  private static void uninterruptibleSleep(long millis) {
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    long remainingMs;
    while ((remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())) > 0) {
      try {
        Thread.sleep(remainingMs);
      } catch (InterruptedException ignored) {
        // Deliberately swallowed and not re-asserted: see method javadoc.
      }
    }
  }

  private static int pageIndexOf(List<String> command) {
    String image = command.get(1);
    int dash = image.lastIndexOf("page-");
    int dot = image.lastIndexOf(".png");
    if (dash < 0 || dot < 0) {
      return 0;
    }
    return Integer.parseInt(image.substring(dash + "page-".length(), dot));
  }

  private Path blankPdf(int pages) throws IOException {
    Path pdf = tempDir.resolve("doc-" + pages + "-" + System.nanoTime() + ".pdf");
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        document.addPage(new PDPage());
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }

  private Path pdfWithPages(PDRectangle... mediaBoxes) throws IOException {
    Path pdf = tempDir.resolve("sized-" + System.nanoTime() + ".pdf");
    try (PDDocument document = new PDDocument()) {
      for (PDRectangle box : mediaBoxes) {
        document.addPage(new PDPage(box));
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }

  /** Minimal {@link Process} stub whose {@code waitFor} sleeps (interruptible) and records kills. */
  private static final class StubProcess extends Process {
    private final int exitCode;
    private final long blockMs;
    private volatile boolean destroyedForcibly;
    private volatile boolean destroyedGracefully;

    StubProcess(int exitCode, long blockMs) {
      this.exitCode = exitCode;
      this.blockMs = blockMs;
    }

    boolean wasForceDestroyed() {
      return destroyedForcibly;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      long allottedMs = unit.toMillis(timeout);
      long sleepMs = Math.min(Math.max(0L, allottedMs), blockMs);
      if (sleepMs > 0) {
        Thread.sleep(sleepMs);
      }
      return blockMs <= allottedMs;
    }

    @Override
    public int waitFor() throws InterruptedException {
      Thread.sleep(blockMs);
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public Process destroyForcibly() {
      destroyedForcibly = true;
      return this;
    }

    @Override
    public void destroy() {
      destroyedGracefully = true;
    }

    @Override
    public boolean isAlive() {
      return !destroyedForcibly && !destroyedGracefully;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }
  }
}
