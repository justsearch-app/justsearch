package io.justsearch.systemtests.vdu;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.vdu.ImagePreparer;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import io.justsearch.systemtests.chaos.ExternalLlamaServerClient;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end system tests for VDU batch processing.
 *
 * <p>Tests the full VDU pipeline: real index + real LLM (llama-server).
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>llama-server running at localhost:8080 with a vision-capable model</li>
 * </ul>
 *
 * <p><b>Note:</b> Tests will FAIL if llama-server is not available.
 * This is intentional - silent skipping hides untested code paths.
 *
 * <p><b>Lane F stage A item A12 — this class now composes the Engine in-process.</b> The subject
 * was never the second process: it is what VDU does to an index. A document with no extractable
 * text must route to {@code vdu_status=PENDING}; a vision pass over it must yield non-blank text
 * containing what the image actually says; the enrichment pass must yield parseable JSON; the
 * document must leave the pending list afterwards; and the extracted text must become
 * <em>searchable</em>, which is the only reason any of it exists. An invalid image must reach a
 * recorded failure with a non-blank reason rather than failing silently. Every one of those is
 * index state plus LLM output, and both survive the collapse untouched.
 *
 * <p>What is gone is how the test reached the index. It used to spawn a Worker JVM
 * ({@code WorkerProcessManager}), read the gRPC port that process published into a memory-mapped
 * signal file ({@code MmfTestHarness}), and dial it ({@code GrpcTestClient}). The index half is now
 * built in this JVM by {@link EngineRoot}, and every index call — including all five VDU
 * operations — goes through the {@link KnowledgeClient} it returns. Those five have identical
 * signatures on {@code KnowledgeClient} (KnowledgeClient.java:1564-1593), so the calls translate
 * one-for-one. The class stays in {@code systemTest} rather than moving to the {@code app-engine}
 * unit tier (where the other A12 conversions landed) for one reason only: it is {@code @Tag("ai")}
 * and needs an external llama-server with a vision model.
 *
 * <p><b>What changed meaning, stated rather than smoothed over.</b>
 *
 * <ol>
 *   <li><b>What was dropped, and why.</b> {@code worker.spawnWorker()}'s returned PID (logged,
 *       never asserted), the {@code mmf.keepAlive()} heartbeat that kept a spawned Worker from
 *       honouring the suicide pact, and the {@code mmf.awaitPort(30_000, 100)} handshake. All
 *       three were about the second process; there is no process, no watchdog and no port. The
 *       "worker should be healthy" gate survives as {@code client.isHealthy()}.
 *   <li><b>{@code grpcClient.awaitIndexing(n, timeoutMs, pollMs)} is inlined here</b> as
 *       {@link #awaitIndexed}, condition-for-condition with the retired implementation
 *       (GrpcTestClient.java:410-434): the queue has drained AND the index holds at least
 *       {@code n} documents.
 *   <li><b>{@code TestableVduBatchProcessor} now drives a {@link KnowledgeClient}.</b> Only the
 *       field's type changed; the two-pass extract-then-enrich flow, the retry guard, the
 *       file-existence check, the outcome codes and the captured-result record are untouched.
 *   <li><b>The class-level {@link Timeout} is new.</b> {@code conventions.jvm-base} applies
 *       {@code junit.jupiter.execution.timeout.default=30s} to every {@code Test} task
 *       (JvmBaseConventionsPlugin.kt:118) and the {@code systemTest} task does not override it, so
 *       every method here ran under a 30s cap that its own 60s indexing waits and a real vision
 *       round trip cannot fit inside. That is a pre-existing condition, not something the collapse
 *       caused, but leaving it in place would mean shipping a converted test that still cannot
 *       pass. Flagged because it is an addition, not a translation.
 * </ol>
 *
 * <p><b>{@link TestEnvironmentProvisioner} is kept for its system properties, not its
 * fail-fast.</b> It points {@code justsearch.repo.root} / {@code justsearch.ssot.path} /
 * {@code justsearch.config} at the real project directories — the same values the spawned Worker
 * used to receive as {@code -D} JVM args, and which the Engine now needs in <em>this</em> JVM. Its
 * {@code verifyWorkerDist()} precondition is gone: item A13 deleted it, and the
 * {@code justsearch.worker.dist.dir} property it demanded, with the Worker distribution itself.
 */
@DisplayName("VDU Batch Processor E2E Tests")
@Tag("systemTest")
@Tag("ai")
@Timeout(900)
class VduBatchProcessorE2ETest {
  private static final Logger log = LoggerFactory.getLogger(VduBatchProcessorE2ETest.class);
  private static final int LLAMA_SERVER_PORT = 8080;
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final EngineContext TEST_ENGINE_CONTEXT =
      EngineProvenance.internal(
          "vdu-system-test", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  private static final EngineContext VDU_ENGINE_CONTEXT =
      EngineProvenance.internal(
          "vdu-batch-processor", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);

  // Expected text content from test images (used for output quality verification)
  private static final String EXPECTED_TEXT_KEYWORD = "TEST";  // Must appear in extracted text

  @RegisterExtension
  static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

  private static ExternalLlamaServerClient llamaClient;
  private static boolean llamaServerAvailable;

  private EngineRoot engine;
  private io.justsearch.app.api.operations.OperationStore operations;
  private KnowledgeClient client;
  private Path testImageDir;
  private TestableVduBatchProcessor vduProcessor;

  @BeforeAll
  static void checkLlamaServer() {
    llamaClient = new ExternalLlamaServerClient(LLAMA_SERVER_PORT);
    llamaServerAvailable = llamaClient.isHealthy();
    if (!llamaServerAvailable) {
      log.error("❌ llama-server not available at localhost:{}", LLAMA_SERVER_PORT);
      log.error("   Start server with:");
      log.error("   .\\native-bin\\llama-server\\llama-server.exe -m models\\... --mmproj ...");
    } else {
      log.info("llama-server healthy at localhost:{}", LLAMA_SERVER_PORT);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    assertTrue(llamaServerAvailable,
        "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT +
        ". Start it before running VDU tests.");

    // Clean the data directory to ensure test isolation
    Path dataDir = env.getTempDir();
    cleanDataDirectory(dataDir);

    // Create test image directory
    testImageDir = dataDir.resolve("test-images");
    Files.createDirectories(testImageDir);

    // Compose the Engine's index half in this JVM. This replaces spawn + port discovery +
    // channel: there is no second process to spawn, no signal file to read a port out of, and no
    // channel to open. startEngine() either hands back a working client or throws.
    startEngine(dataDir);
    assertTrue(client.isHealthy(TEST_ENGINE_CONTEXT), "Engine should be healthy");

    // Create testable VDU processor
    vduProcessor = new TestableVduBatchProcessor(llamaClient, client);
  }

  @AfterEach
  void cleanup() throws java.io.IOException {
    // EngineRoot.close() closes the KnowledgeClient it handed out, so the client is released by
    // dropping the reference rather than by a second close.
    if (engine != null) {
      engine.close();
      operations.close();
      operations = null;
      engine = null;
    }
    client = null;
    vduProcessor = null;
  }

  @Test
  @DisplayName("processes image document with real LLM and verifies output quality")
  void processesImageWithRealLlm() throws Exception {
    // 1. Create and index test image
    Path testImage = createTestImage("e2e-test.png");
    String filePath = testImage.toAbsolutePath().toString();
    // Worker normalizes paths (lowercase on Windows) for doc_id
    String docId = normalizeDocId(testImage);
    log.info("Created test image: {} (docId: {})", filePath, docId);

    int accepted = client.submitBatch(List.of(testImage), TEST_ENGINE_CONTEXT).getAcceptedCount();
    assertEquals(1, accepted, "Should accept 1 file");

    // 2. Wait for indexing
    assertTrue(awaitIndexed(1, 30_000, 200), "Should index within 30s");

    // 3. Verify document is pending VDU (poll to handle searcher refresh)
    assertTrue(awaitPending(docId, 5_000), "Document should be pending VDU");

    // 4. Process with real LLM and capture results
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(1, result.processedCount(), "Should process 1 document");

    // =========================================================================
    // STRONG ASSERTIONS: Verify AI output quality (not just that it ran)
    // =========================================================================

    // 5a. Verify extracted text is not blank
    assertFalse(result.lastExtractedText().isBlank(),
        "❌ Extracted text should NOT be blank - LLM returned empty response");

    // 5b. Verify extracted text contains expected keywords from the test image
    String extractedLower = result.lastExtractedText().toLowerCase(Locale.ROOT);
    assertTrue(extractedLower.contains(EXPECTED_TEXT_KEYWORD.toLowerCase(Locale.ROOT)),
        "❌ Extracted text should contain '" + EXPECTED_TEXT_KEYWORD + "' from test image. " +
        "Actual extracted: " + truncate(result.lastExtractedText(), 200));

    // 5c. Verify enrichment is valid JSON (not garbage)
    assertDoesNotThrow(() -> {
      JsonNode json = objectMapper.readTree(result.lastEnrichment());
      assertNotNull(json, "Enrichment should parse as valid JSON");
    }, "❌ Enrichment should be valid JSON, got: " + truncate(result.lastEnrichment(), 200));

    // 6. Verify document is no longer pending (poll to handle searcher refresh)
    assertTrue(awaitNotPending(docId, 5_000),
        "Document should no longer be pending after VDU processing");

    // 7. Verify extracted content is searchable (the real use case!)
    assertTrue(awaitSearchable(EXPECTED_TEXT_KEYWORD, 10_000),
        "❌ Document should be searchable by extracted text keyword: " + EXPECTED_TEXT_KEYWORD);

    log.info("✅ E2E test PASSED with output quality verification:");
    log.info("   Extracted text length: {} chars", result.lastExtractedText().length());
    log.info("   Contains expected keyword: '{}'", EXPECTED_TEXT_KEYWORD);
    log.info("   Enrichment is valid JSON: true");
  }

  @Test
  @DisplayName("processes scanned PDF (image-only) with real LLM and verifies searchability")
  void processesScannedPdfWithRealLlm() throws Exception {
    // 1. Copy scanned PDF fixture into test data directory
    Path pdf = copyResourceToTestDir("/fixtures/pdf/scanned-alpha.pdf", "scanned-alpha.pdf");
    String filePath = pdf.toAbsolutePath().toString();
    String docId = normalizeDocId(pdf);
    log.info("Copied scanned PDF fixture: {} (docId: {})", filePath, docId);

    int accepted = client.submitBatch(List.of(pdf), TEST_ENGINE_CONTEXT).getAcceptedCount();
    assertEquals(1, accepted, "Should accept 1 file");

    // 2. Wait for indexing
    assertTrue(awaitIndexed(1, 60_000, 200), "Should index within 60s");

    // 3. Verify pending VDU (image-only PDF => no text layer)
    assertTrue(awaitPending(docId, 10_000), "Scanned PDF should be pending VDU");

    // 4. Process with real LLM
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(1, result.processedCount(), "Should process 1 document");

    // 5. Verify OCR output contains expected keyword
    assertFalse(result.lastExtractedText().isBlank(), "Extracted text should not be blank");
    assertTrue(
        result.lastExtractedText().toLowerCase(Locale.ROOT).contains("alpha"),
        "Extracted text should contain 'ALPHA'. Actual: " + truncate(result.lastExtractedText(), 200));

    // 6. Verify document is no longer pending
    assertTrue(awaitNotPending(docId, 10_000), "Document should no longer be pending after VDU processing");

    // 7. Verify extracted content is searchable
    assertTrue(awaitSearchable("ALPHA", 15_000), "Scanned PDF should be searchable by OCR text");
  }

  @Test
  @DisplayName("processes multiple images in batch with quality verification")
  void processesMultipleImagesInBatch() throws Exception {
    // 1. Create multiple test images with DIFFERENT content
    Path img1 = createTestImageWithText("batch-1.png", "ALPHA DOCUMENT");
    Path img2 = createTestImageWithText("batch-2.png", "BETA DOCUMENT");

    // Worker normalizes paths (lowercase on Windows)
    String docId1 = normalizeDocId(img1);
    String docId2 = normalizeDocId(img2);

    // 2. Submit all for indexing (use actual paths)
    int accepted = client.submitBatch(List.of(img1, img2), TEST_ENGINE_CONTEXT).getAcceptedCount();
    assertEquals(2, accepted);

    // 3. Wait for indexing
    assertTrue(awaitIndexed(2, 60_000, 200));

    // 4. Verify all pending (poll to handle searcher refresh)
    assertTrue(awaitPending(docId1, 5_000), "Doc 1 should be pending");
    assertTrue(awaitPending(docId2, 5_000), "Doc 2 should be pending");

    // 5. Process all and capture results
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(2, result.processedCount(), "Should process both documents");

    // =========================================================================
    // STRONG ASSERTIONS: Verify batch processing quality
    // =========================================================================

    // 6a. Verify all extracted texts are non-blank
    assertEquals(2, result.allExtractedTexts().size(), "Should have extracted text for both docs");
    for (String text : result.allExtractedTexts()) {
      assertFalse(text.isBlank(),
          "❌ Each extracted text should NOT be blank");
    }

    // 6b. Verify all enrichments are valid JSON
    assertEquals(2, result.allEnrichments().size(), "Should have enrichment for both docs");
    for (String enrichment : result.allEnrichments()) {
      assertDoesNotThrow(() -> objectMapper.readTree(enrichment),
          "❌ Each enrichment should be valid JSON, got: " + truncate(enrichment, 100));
    }

    // 7. Verify none pending (poll to handle searcher refresh)
    assertTrue(awaitNotPending(docId1, 5_000), "Doc 1 should not be pending after processing");
    assertTrue(awaitNotPending(docId2, 5_000), "Doc 2 should not be pending after processing");

    // 8. Verify BOTH documents are searchable with their unique content
    assertTrue(awaitSearchable("ALPHA", 10_000),
        "❌ Doc 1 should be searchable by 'ALPHA'");
    assertTrue(awaitSearchable("BETA", 10_000),
        "❌ Doc 2 should be searchable by 'BETA'");

    log.info("✅ Batch test PASSED: {} documents processed with verified quality",
        result.processedCount());
  }

  @Test
  @DisplayName("invalid image reaches FAILED status with proper error handling")
  void invalidImageReachesFailed() throws Exception {
    // 1. Create an invalid "image" (text file with .png extension)
    Path invalidImage = testImageDir.resolve("invalid.png");
    Files.writeString(invalidImage, "This is not a valid PNG image - just random text");
    // Worker normalizes paths (lowercase on Windows)
    String docId = normalizeDocId(invalidImage);

    // 2. Submit for indexing
    client.submitBatch(List.of(invalidImage), TEST_ENGINE_CONTEXT);
    assertTrue(awaitIndexed(1, 30_000, 200));

    // 3. Verify pending (poll to handle searcher refresh)
    assertTrue(awaitPending(docId, 5_000), "Invalid image should be pending VDU initially");

    // 4. Process (should fail gracefully, not crash)
    TestableVduBatchProcessor.ProcessingResult result = vduProcessor.processPendingFilesWithResults();
    assertEquals(0, result.processedCount(),
        "Invalid image should NOT count as successfully processed");

    // =========================================================================
    // STRONG ASSERTIONS: Verify failure is properly recorded
    // =========================================================================

    // 5a. Verify we attempted to process but it failed
    assertEquals(1, result.failedCount(),
        "Should have exactly 1 failed document");

    // 5b. Verify failure reason is captured (not silent)
    assertFalse(result.lastFailureReason().isBlank(),
        "❌ Failure reason should NOT be blank - errors must be logged");
    log.info("Failure reason captured: {}", result.lastFailureReason());

    // 6. Verify no longer pending (marked as FAILED) - poll to handle searcher refresh
    assertTrue(awaitNotPending(docId, 5_000),
        "Failed document should not be in pending list");

    log.info("✅ Invalid image test PASSED: failure properly recorded");
  }

  // =========================================================================
  // Helper Methods
  // =========================================================================

  /**
   * Publishes the resolved config the Engine reads and composes the index half in-process.
   *
   * <p>{@code WorkerConfig.load()} reads {@code ConfigStore.global()} (WorkerConfig.java:44-58),
   * so the data directory and index base path have to be published before {@link EngineRoot#start}
   * builds the {@code KnowledgeServer}. This is the whole of what
   * {@code WorkerProcessManager.fromDistribution(...).withJvmArgs(...).spawnWorker()} plus
   * {@code MmfTestHarness.awaitPort} plus {@code new GrpcTestClient(port)} collapse to.
   *
   * @param dataDir the Engine's data directory — the same directory the spawned Worker used to
   *     receive as {@code -Djustsearch.data.dir}
   */
  private void startEngine(Path dataDir) throws Exception {
    Path indexBase = dataDir.resolve("index");
    Files.createDirectories(dataDir);
    Files.createDirectories(indexBase);
    ConfigStore.setGlobal(new ConfigStore(new ResolvedConfigBuilder()
        .contributeBaseSources()
        .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
        .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
        .build()));

    operations = new io.justsearch.app.observability.operations.SqliteOperationStore(dataDir.resolve("operations.db"));
    engine = new EngineRoot(operations, 30_000L, 5_000);
    client = engine.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
  }

  /**
   * Waits until the queue has drained and the index holds at least {@code expectedDocCount}
   * documents.
   *
   * <p>The condition is the retired {@code GrpcTestClient.awaitIndexing} verbatim
   * (GrpcTestClient.java:410-434); only the transport under {@code getStatus()} changed.
   */
  private boolean awaitIndexed(long expectedDocCount, long timeoutMs, long pollIntervalMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        StatusResponse status = client.getStatus(TEST_ENGINE_CONTEXT);
        if (status.getCore().getQueueDepth() == 0
            && status.getCore().getDocCount() >= expectedDocCount) {
          return true;
        }
      } catch (RuntimeException e) {
        // A status call can fail while the index half swaps a writer; the loop re-reads.
        log.debug("Status check failed: {}", e.getMessage());
      }
      Thread.sleep(pollIntervalMs);
    }
    return false;
  }

  private Path createTestImage(String filename) throws Exception {
    return createTestImageWithText(filename, "TEST DOCUMENT\nVDU E2E Test");
  }

  /**
   * Creates a test image with custom text content.
   * The text should be extractable by VDU for verification.
   */
  private Path createTestImageWithText(String filename, String text) throws Exception {
    Path imagePath = testImageDir.resolve(filename);

    // Create a test image with the specified text content
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
        300, 150, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = img.createGraphics();

    // White background for better OCR
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, 300, 150);

    // Black text, clear font
    g.setColor(java.awt.Color.BLACK);
    g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 18));

    // Draw each line of text
    String[] lines = text.split("\n");
    int y = 40;
    for (String line : lines) {
      g.drawString(line, 20, y);
      y += 30;
    }
    g.dispose();

    javax.imageio.ImageIO.write(img, "PNG", imagePath.toFile());
    log.debug("Created test image: {} with text: {}", imagePath, text.replace("\n", " | "));
    return imagePath;
  }

  /**
   * Normalizes a path to match Worker's document ID format.
   * Worker lowercases paths on Windows for case-insensitive comparison.
   */
  private String normalizeDocId(Path path) {
    String absolutePath = path.toAbsolutePath().toString();
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      return absolutePath.toLowerCase(Locale.ROOT);
    }
    return absolutePath;
  }

  /**
   * Waits until a document appears in the pending VDU list.
   * Handles searcher refresh latency.
   */
  private boolean awaitPending(String docId, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<String> pending = client.queryPendingVduDocIds(100, TEST_ENGINE_CONTEXT);
      if (pending.contains(docId)) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  /**
   * Waits until a document is no longer in the pending VDU list.
   * Handles searcher refresh latency.
   *
   * <p>A12: not converted with full fidelity — the failure mode is weaker here, and there is no
   * in-process call that restores it. {@code GrpcTestClient.queryPendingVduDocIds} propagated a
   * {@code StatusRuntimeException} (GrpcTestClient.java:584-586), so a broken query blew this
   * method up. {@code KnowledgeClient} delegates to {@code VduOps.queryPendingVduDocIds}, which
   * catches everything and returns an empty list (VduOps.java:119-125). An empty list satisfies
   * "not pending", so a query that is failing rather than answering now reads as success HERE
   * (it reads as a timeout in {@link #awaitPending}, which is still correct). No non-error path
   * changed; this only affects what a broken query looks like. Left as-is rather than papered
   * over with a substitute check: {@code countPendingVdu()} swallows the same way
   * (VduOps.java:45-53), so it would confirm nothing.
   */
  private boolean awaitNotPending(String docId, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      List<String> pending = client.queryPendingVduDocIds(100, TEST_ENGINE_CONTEXT);
      if (!pending.contains(docId)) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  /**
   * Waits until a search query returns at least one result.
   * This verifies that extracted content is actually searchable.
   */
  private boolean awaitSearchable(String query, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        SearchResponse response = client.search(query, 10, TEST_ENGINE_CONTEXT);
        if (response.getTotalHits() > 0) {
          log.debug("Search '{}' returned {} hits", query, response.getTotalHits());
          return true;
        }
      } catch (Exception e) {
        log.debug("Search failed: {}", e.getMessage());
      }
      Thread.sleep(200);
    }
    log.warn("Search '{}' timed out with no results", query);
    return false;
  }

  /**
   * Truncates a string for logging (avoids massive log output).
   */
  private static String truncate(String s, int maxLen) {
    if (s == null) return "<null>";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "... [truncated, total " + s.length() + " chars]";
  }

  /**
   * Cleans the data directory to ensure test isolation.
   * Removes index files but preserves the directory structure.
   */
  private void cleanDataDirectory(Path dataDir) throws Exception {
    // Clean index directory if it exists
    Path indexDir = dataDir.resolve("index");
    if (Files.exists(indexDir)) {
      try (var stream = Files.walk(indexDir)) {
        stream.sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (java.io.IOException e) {
                log.debug("Could not delete {}: {}", p, e.getMessage());
              }
            });
      }
    }

    // Clean queue database
    // (Worker currently uses jobs.db; keep legacy name deletion for back-compat.)
    Files.deleteIfExists(dataDir.resolve("jobs.db"));
    Files.deleteIfExists(dataDir.resolve("job_queue.db"));
  }

  private Path copyResourceToTestDir(String resourcePath, String filename) throws Exception {
    if (resourcePath == null || resourcePath.isBlank()) {
      throw new IllegalArgumentException("resourcePath is required");
    }
    String outName = (filename == null || filename.isBlank()) ? "fixture.bin" : filename;
    Path out = testImageDir.resolve(outName);

    try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
      assertNotNull(in, "Missing test resource: " + resourcePath);
      Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
    }
    return out;
  }

  // =========================================================================
  // Test Support Classes
  // =========================================================================

  /**
   * Test-friendly VDU batch processor that uses {@link KnowledgeClient} and
   * {@link ExternalLlamaServerClient}.
   *
   * <p>Mirrors the real VduBatchProcessor logic but works with test infrastructure.
   * <p>ENHANCED: Returns detailed results for test verification of AI output quality.
   *
   * <p>Lane F stage A item A12: the index-side collaborator used to be {@code GrpcTestClient}
   * (a raw gRPC stub over a spawned Worker's port). It is now the Engine's in-process
   * {@link KnowledgeClient}. The four VDU calls this class makes —
   * {@code queryPendingVduDocIds(int)}, {@code markVduProcessing(String, int)} and both
   * {@code updateVduResult(...)} sites — carry the same signatures and the same success-path
   * return values on {@code KnowledgeClient} (KnowledgeClient.java:1570-1589) as they did on the
   * retired stub (GrpcTestClient.java:584-686), so the processing logic below is unchanged.
   *
   * <p><b>The error path is not identical, and the difference is stated rather than hidden.</b>
   * The retired stub let a transport failure escape as a {@code StatusRuntimeException};
   * {@code VduOps} catches everything and returns a sentinel instead — {@code -1} from
   * {@code markVduProcessing} (VduOps.java:150-157) and {@code false} from
   * {@code updateVduResult} (VduOps.java:90-96). Both sentinels still land in a
   * {@code failed++} branch here with a non-blank {@code lastFailureReason}, so no assertion in
   * this class flips; what changes is that a genuine call failure now takes the "max retries
   * exceeded" / "failed to update result in index" branch instead of the {@code catch (Exception)}
   * branch, and therefore no longer writes {@code VDU_UPDATE_OUTCOME_FAILED} back to the index
   * via {@link #markFailed}.
   */
  static class TestableVduBatchProcessor {
    private static final Logger log = LoggerFactory.getLogger(TestableVduBatchProcessor.class);

    private static final String EXTRACTION_PROMPT = """
        Extract all text from this document image. Include:
        - Headings and paragraphs
        - Any visible text, numbers, or dates
        Output the extracted content.
        """;

    private static final String ENRICHMENT_PROMPT_TEMPLATE = """
        Based on the following extracted document content, provide:
        1. A brief summary (1-2 sentences)
        2. Document type
        Output as JSON: {"summary": "...", "doc_type": "..."}

        Document content:
        %s
        """;

    private static final int MAX_RETRIES = 3;

    private final ExternalLlamaServerClient llamaClient;
    private final KnowledgeClient knowledgeClient;
    private final ImagePreparer imagePreparer;

    TestableVduBatchProcessor(
        ExternalLlamaServerClient llamaClient, KnowledgeClient knowledgeClient) {
      this.llamaClient = llamaClient;
      this.knowledgeClient = knowledgeClient;
      this.imagePreparer = new ImagePreparer();
    }

    /**
     * Processes all pending VDU documents (simple version for backward compat).
     *
     * @return Number of successfully processed documents
     */
    int processPendingFiles() {
      return processPendingFilesWithResults().processedCount();
    }

    /**
     * Processes all pending VDU documents and returns detailed results.
     *
     * <p>This method captures extraction results for test verification,
     * ensuring we don't just check "it ran" but "it produced meaningful output".
     *
     * @return ProcessingResult containing counts and captured outputs
     */
    ProcessingResult processPendingFilesWithResults() {
      List<String> pending = knowledgeClient.queryPendingVduDocIds(100, VDU_ENGINE_CONTEXT);
      log.info("Processing {} pending VDU files", pending.size());

      int processed = 0;
      int failed = 0;
      List<String> extractedTexts = new java.util.ArrayList<>();
      List<String> enrichments = new java.util.ArrayList<>();
      String lastExtractedText = "";
      String lastEnrichment = "";
      String lastFailureReason = "";

      for (String docId : pending) {
        try {
          // Mark as PROCESSING (with retry protection)
          int retryCount = knowledgeClient.markVduProcessing(docId, MAX_RETRIES, VDU_ENGINE_CONTEXT);
          if (retryCount < 0) {
            log.warn("Skipping {} - max retries exceeded", docId);
            lastFailureReason = "Max retries exceeded";
            failed++;
            continue;
          }

          // Check file exists
          Path filePath = Path.of(docId);
          if (!Files.exists(filePath)) {
            lastFailureReason = "File no longer exists: " + docId;
            markFailed(docId, lastFailureReason);
            failed++;
            continue;
          }

          // Process with real LLM
          log.info("Processing {} (retry {})", docId, retryCount);
          VduResult result = processFile(filePath);

          // Capture results for verification
          lastExtractedText = result.extractedText();
          lastEnrichment = result.enrichment();
          extractedTexts.add(lastExtractedText);
          enrichments.add(lastEnrichment);

          // Update result
          boolean updated = knowledgeClient.updateVduResult(
              docId,
              result.extractedText(),
              VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT,
              result.enrichment(),
              result.pageCount(),
              VDU_ENGINE_CONTEXT);

          if (updated) {
            processed++;
            log.info("Successfully processed {} - extracted {} chars",
                docId, result.extractedText().length());
          } else {
            log.warn("Failed to update result for {}", docId);
            lastFailureReason = "Failed to update result in index";
            failed++;
          }

        } catch (Exception e) {
          log.error("VDU processing failed for {}: {}", docId, e.getMessage());
          lastFailureReason = e.getMessage();
          markFailed(docId, e.getMessage());
          failed++;
        }
      }

      return new ProcessingResult(
          processed,
          failed,
          lastExtractedText,
          lastEnrichment,
          lastFailureReason,
          extractedTexts,
          enrichments);
    }

    private VduResult processFile(Path filePath) throws Exception {
      // Pass 1: Extract text via vision
      byte[] imageBytes = imagePreparer.prepare(filePath);
      String extractedText = llamaClient.visionCompletion(EXTRACTION_PROMPT, imageBytes, 2048);

      // VALIDATION: Fail fast if extraction returned nothing
      if (extractedText == null || extractedText.isBlank()) {
        throw new RuntimeException("LLM returned blank/null extracted text for: " + filePath);
      }

      // Pass 2: Enrich (text only)
      String truncatedText = extractedText.length() > 4000
          ? extractedText.substring(0, 4000) + "..."
          : extractedText;
      String enrichmentPrompt = String.format(ENRICHMENT_PROMPT_TEMPLATE, truncatedText);
      String enrichment = llamaClient.textCompletion(enrichmentPrompt, 256);

      return new VduResult(extractedText, enrichment, 1);
    }

    private void markFailed(String docId, String reason) {
      String safeReason = reason.replace("\"", "'");
      knowledgeClient.updateVduResult(
          docId,
          null,
          VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED,
          "{\"error\": \"" + safeReason + "\"}",
          0,
          VDU_ENGINE_CONTEXT);
    }

    record VduResult(String extractedText, String enrichment, int pageCount) {}

    /**
     * Result of batch processing with captured outputs for test verification.
     */
    record ProcessingResult(
        int processedCount,
        int failedCount,
        String lastExtractedText,
        String lastEnrichment,
        String lastFailureReason,
        List<String> allExtractedTexts,
        List<String> allEnrichments) {}
  }
}
