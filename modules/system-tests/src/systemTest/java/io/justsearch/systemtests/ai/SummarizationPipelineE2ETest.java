package io.justsearch.systemtests.ai;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.gpu.VramDetector;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.systemtests.chaos.ExternalLlamaServerClient;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end system tests for the summarization pipeline.
 *
 * <p>Tests the full summarization flow: ChunkSplitter → llama-server → streaming response.
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>llama-server running at localhost:8080 with a text model (e.g., Qwen, Llama)</li>
 * </ul>
 *
 * <p><b>Note:</b> Tests will FAIL if llama-server is not available.
 * This is intentional - silent skipping hides untested code paths.
 *
 * <p><b>Lane F stage A item A12 — this class now composes the Engine in-process.</b> It used to
 * reach a real index through the chaos tier's three-part rig: {@code WorkerProcessManager} spawned
 * a Worker JVM from the installed distribution, {@code MmfTestHarness} read the gRPC port that
 * process published into a memory-mapped signal file, and {@code GrpcTestClient} dialled that port.
 * All three are gone with the process collapse. The index half is now built in this JVM by
 * {@link EngineRoot}, and every index call goes through the {@link KnowledgeClient} it returns.
 * The class stays in {@code systemTest} rather than moving to the {@code app-engine} unit tier
 * (where the other A12 conversions landed) for one reason only: it is {@code @Tag("ai")} and needs
 * an external llama-server, which a unit-tier suite may not require.
 *
 * <p><b>What changed meaning, stated rather than smoothed over.</b>
 *
 * <ol>
 *   <li><b>What was dropped, and why.</b> {@code worker.spawnWorker()}'s returned PID (logged,
 *       never asserted), the {@code mmf.keepAlive()} heartbeat that kept a spawned Worker from
 *       honouring the suicide pact, and the {@code mmf.awaitPort(30_000, 100)} handshake. All
 *       three were about the second process; there is no process, no watchdog and no port. The
 *       "worker should be healthy" gate survives as {@code client.isHealthy()}, which now answers
 *       from the composed index half rather than from a health RPC over a channel.
 *   <li><b>{@code grpcClient.awaitIndexing(n, timeoutMs, pollMs)} is inlined here</b> as
 *       {@link #awaitIndexed}, condition-for-condition with the retired implementation
 *       (GrpcTestClient.java:410-434): the queue has drained AND the index holds at least
 *       {@code n} documents.
 *   <li><b>The class-level {@link Timeout} is new.</b> {@code conventions.jvm-base} applies
 *       {@code junit.jupiter.execution.timeout.default=30s} to every {@code Test} task
 *       (JvmBaseConventionsPlugin.kt:118) and the {@code systemTest} task does not override it, so
 *       every method here ran under a 30s cap that its own 30s/60s waits and a real LLM round trip
 *       cannot fit inside. That is a pre-existing condition, not something the collapse caused, but
 *       leaving it in place would mean shipping a converted test that still cannot pass. Flagged
 *       because it is an addition, not a translation.
 * </ol>
 *
 * <p><b>{@link TestEnvironmentProvisioner} is kept for its system properties, not its
 * fail-fast.</b> Under the split architecture it served two purposes: it verified the worker
 * distribution existed, and it pointed {@code justsearch.repo.root} / {@code justsearch.ssot.path}
 * / {@code justsearch.config} at the real project directories. The Engine runs in <em>this</em>
 * JVM now, so the second purpose is exactly what it needs — those are the same values the spawned
 * Worker used to receive as {@code -D} JVM args. Item A13 deleted the first: {@code
 * verifyWorkerDist()} and the {@code justsearch.worker.dist.dir} property it demanded are gone
 * with the distribution, so the extension no longer has a precondition that nothing in this class
 * exercises.
 */
@DisplayName("Summarization Pipeline E2E Tests")
@Tag("systemTest")
@Tag("ai")
@Timeout(600)
class SummarizationPipelineE2ETest {
  private static final Logger log = LoggerFactory.getLogger(SummarizationPipelineE2ETest.class);
  private static final int LLAMA_SERVER_PORT = 8080;
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final EngineContext TEST_ENGINE_CONTEXT =
      EngineProvenance.internal(
          "summarization-system-test",
          EngineContext.Survival.INTERACTIVE,
          EngineContext.Urgency.FOREGROUND);

  // Test document content (long enough to require chunking)
  private static final String TEST_DOCUMENT = """
      # Quarterly Business Report Q4 2024

      ## Executive Summary

      This report presents the quarterly performance metrics for Q4 2024. The company achieved
      significant growth across all major business segments, exceeding targets set at the beginning
      of the fiscal year. Revenue increased by 23% year-over-year, driven primarily by strong
      performance in our cloud services division and the successful launch of new product lines.

      ## Financial Highlights

      Total revenue for Q4 2024 reached $4.2 billion, representing a 23% increase compared to
      Q4 2023. Operating income grew to $890 million, with an operating margin of 21.2%. Net
      income was $720 million, or $3.45 per diluted share, compared to $580 million, or $2.78
      per diluted share, in the prior year period.

      ### Revenue Breakdown by Segment

      - Cloud Services: $1.8 billion (43% of total revenue)
      - Enterprise Software: $1.2 billion (29% of total revenue)
      - Professional Services: $800 million (19% of total revenue)
      - Hardware and Other: $400 million (9% of total revenue)

      ## Operational Metrics

      Customer acquisition remained strong throughout the quarter. We added 12,500 new enterprise
      customers, bringing our total customer base to over 185,000 organizations worldwide. Customer
      retention rate improved to 94.5%, up from 92.3% in the previous quarter.

      Our cloud infrastructure handled an average of 2.3 billion API requests per day during Q4,
      with 99.99% uptime across all regions. We expanded our global footprint by opening three
      new data centers in Singapore, Frankfurt, and São Paulo.

      ## Strategic Initiatives

      During the quarter, we completed the acquisition of DataFlow Analytics, a leading provider
      of real-time data processing solutions. This acquisition strengthens our data analytics
      capabilities and adds 50 specialized engineers to our team.

      We also launched our new AI-powered business intelligence platform, which has already been
      adopted by over 2,000 customers. Early feedback indicates significant productivity gains,
      with users reporting an average 35% reduction in time spent on data analysis tasks.

      ## Outlook for 2025

      Looking ahead to 2025, we expect continued strong growth driven by:
      - Expansion of cloud services to additional geographic regions
      - Integration of acquired technologies into our core platform
      - Launch of next-generation enterprise security solutions
      - Continued investment in AI and machine learning capabilities

      We project full-year 2025 revenue in the range of $18-19 billion, representing 15-20%
      year-over-year growth.

      ## Conclusion

      Q4 2024 was an outstanding quarter that demonstrated the strength of our diversified
      business model and the value we deliver to customers. We remain committed to innovation
      and customer success as we enter 2025.
      """;

  @RegisterExtension
  static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

  private static ExternalLlamaServerClient llamaClient;
  private static boolean llamaServerAvailable;
  private static HttpClient httpClient;

  private EngineRoot engine;
  private io.justsearch.app.api.operations.OperationStore operations;
  private KnowledgeClient client;
  private Path testDataDir;

  @BeforeAll
  static void checkLlamaServer() {
    llamaClient = new ExternalLlamaServerClient(LLAMA_SERVER_PORT);
    llamaServerAvailable = llamaClient.isHealthy();
    httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    if (!llamaServerAvailable) {
      log.error("❌ llama-server not available at localhost:{}", LLAMA_SERVER_PORT);
      log.error("   Start server with:");
      log.error("   .\\native-bin\\llama-server\\llama-server.exe -m models\\... -c 4096");
    } else {
      log.info("llama-server healthy at localhost:{}", LLAMA_SERVER_PORT);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    // Create test data directory
    testDataDir = env.getTempDir().resolve("test-data");
    Files.createDirectories(testDataDir);
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
  }

  // =========================================================================
  // Test 1: Chunking Creates Valid Chunks
  // =========================================================================

  @Test
  @DisplayName("ChunkSplitter creates valid chunks with correct overlap and token estimates")
  void testChunkingCreatesValidChunks() {
    // 1. Split test document with default settings
    List<ChunkSplitter.Chunk> chunks = ChunkSplitter.splitWithMetadata(
        TEST_DOCUMENT,
        ChunkSplitter.DEFAULT_CHUNK_TOKENS,
        ChunkSplitter.DEFAULT_OVERLAP_TOKENS,
        ChunkSplitter.Mode.MARKDOWN);

    log.info("Split document into {} chunks", chunks.size());

    // =========================================================================
    // ASSERTIONS: Verify chunking correctness
    // =========================================================================

    // 2a. Document is long enough to require multiple chunks
    assertTrue(chunks.size() > 1,
        "❌ Document should split into multiple chunks. " +
        "Document length: " + TEST_DOCUMENT.length() + " chars");

    // 2b. Each chunk has valid metadata
    for (int i = 0; i < chunks.size(); i++) {
      ChunkSplitter.Chunk chunk = chunks.get(i);

      // Index is sequential
      assertEquals(i, chunk.index(),
          "❌ Chunk index should be sequential");

      // Content is not empty
      assertFalse(chunk.content().isBlank(),
          "❌ Chunk " + i + " content should not be blank");

      // Start < End
      assertTrue(chunk.startChar() < chunk.endChar(),
          "❌ Chunk " + i + " startChar should be less than endChar");

      // Estimated tokens are reasonable (not zero, not absurdly high)
      assertTrue(chunk.estimatedTokens() > 0,
          "❌ Chunk " + i + " should have positive token estimate");
      assertTrue(chunk.estimatedTokens() <= ChunkSplitter.DEFAULT_CHUNK_TOKENS + 100,
          "❌ Chunk " + i + " tokens (" + chunk.estimatedTokens() +
          ") exceeds target by too much");

      log.debug("Chunk {}: {} chars, ~{} tokens, range [{}-{}]",
          i, chunk.content().length(), chunk.estimatedTokens(),
          chunk.startChar(), chunk.endChar());
    }

    // 2c. Verify overlap between consecutive chunks
    for (int i = 1; i < chunks.size(); i++) {
      ChunkSplitter.Chunk prev = chunks.get(i - 1);
      ChunkSplitter.Chunk curr = chunks.get(i);

      // Current chunk's start should be before previous chunk's end (overlap)
      assertTrue(curr.startChar() < prev.endChar(),
          "❌ Chunks " + (i-1) + " and " + i + " should overlap. " +
          "Prev ends at " + prev.endChar() + ", curr starts at " + curr.startChar());
    }

    // 2d. All content is covered (no gaps)
    String reconstructed = reconstructFromChunks(chunks, TEST_DOCUMENT);
    assertFalse(reconstructed.isEmpty(),
        "❌ Reconstructed content should not be empty");

    log.info("✅ Chunking test PASSED: {} chunks with valid overlap", chunks.size());
  }

  // =========================================================================
  // Test 2: GPU Usage or CPU Fallback
  // =========================================================================

  @Test
  @DisplayName("Verifies llama-server is using GPU via /props and VRAM monitoring")
  void testGpuUsageOrCpuFallback() throws Exception {
    assertTrue(llamaServerAvailable,
        "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    VramDetector detector = new VramDetector();
    long totalVram = detector.getTotalVramBytes();

    if (totalVram < 0) {
      log.info("No NVIDIA GPU detected (nvidia-smi unavailable)");
      log.info("Testing CPU inference fallback...");

      // CPU fallback: verify llama-server still works
      String response = llamaClient.textCompletion("Say hello in exactly 5 words.", 50);
      assertFalse(response.isBlank(),
          "❌ CPU inference should return non-blank response");

      log.info("✅ CPU fallback test PASSED: received response of {} chars", response.length());
      return;
    }

    // GPU detected - verify llama-server is actually using it
    log.info("GPU detected with {} total VRAM", detector.getVramDescription());

    // =========================================================================
    // METHOD 1: Query /props endpoint for n_gpu_layers
    // =========================================================================
    GpuUsageInfo gpuInfo = queryLlamaServerGpuInfo();
    log.info("llama-server /props response: n_gpu_layers={}, total_slots={}",
        gpuInfo.nGpuLayers(), gpuInfo.totalSlots());

    // =========================================================================
    // METHOD 2: Monitor VRAM usage via nvidia-smi
    // =========================================================================
    long vramUsedBefore = getUsedVramBytes();
    log.info("VRAM used before inference: {} MB", vramUsedBefore / (1024 * 1024));

    // Run inference to ensure model is loaded
    llamaClient.textCompletion("Hello", 10);

    long vramUsedAfter = getUsedVramBytes();
    log.info("VRAM used after inference: {} MB", vramUsedAfter / (1024 * 1024));

    // =========================================================================
    // ASSERTIONS: Verify GPU is actually being used
    // =========================================================================

    // Check 1: /props reports GPU layers (if available)
    if (gpuInfo.nGpuLayers() != null) {
      assertTrue(gpuInfo.nGpuLayers() > 0,
          "❌ llama-server reports n_gpu_layers=" + gpuInfo.nGpuLayers() +
          " but expected > 0 for GPU inference");
      log.info("✅ llama-server using {} GPU layers", gpuInfo.nGpuLayers());
    } else {
      log.warn("⚠️ /props did not include n_gpu_layers - cannot verify via API");
    }

    // Check 2: VRAM is being used (model should consume significant VRAM)
    // A model typically uses at least 500MB, often several GB
    long minExpectedVram = 500L * 1024 * 1024; // 500MB
    if (vramUsedAfter > minExpectedVram) {
      log.info("✅ VRAM usage ({} MB) indicates GPU is being used",
          vramUsedAfter / (1024 * 1024));
    } else if (vramUsedBefore > minExpectedVram) {
      // Model was already loaded before our test
      log.info("✅ VRAM was already in use ({} MB) - model pre-loaded",
          vramUsedBefore / (1024 * 1024));
    } else {
      log.warn("⚠️ Low VRAM usage ({} MB) - model may be running on CPU",
          vramUsedAfter / (1024 * 1024));
    }

    // Combined assertion: at least one method should confirm GPU usage
    boolean gpuConfirmed =
        (gpuInfo.nGpuLayers() != null && gpuInfo.nGpuLayers() > 0) ||
        (vramUsedAfter > minExpectedVram || vramUsedBefore > minExpectedVram);

    assertTrue(gpuConfirmed,
        "❌ Could not confirm GPU usage. n_gpu_layers=" + gpuInfo.nGpuLayers() +
        ", VRAM used=" + (vramUsedAfter / (1024 * 1024)) + " MB. " +
        "Ensure llama-server was started with -ngl flag.");

    log.info("✅ GPU usage verification PASSED");
  }

  /**
   * Queries llama-server /props endpoint to get GPU layer configuration.
   */
  private GpuUsageInfo queryLlamaServerGpuInfo() {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:" + LLAMA_SERVER_PORT + "/props"))
          .timeout(Duration.ofSeconds(5))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.debug("/props returned status {}", response.statusCode());
        return new GpuUsageInfo(null, null, null);
      }

      JsonNode root = objectMapper.readTree(response.body());
      log.debug("/props response: {}", truncate(response.body(), 500));

      // Try to extract n_gpu_layers from various locations
      Integer nGpuLayers = extractIntField(root, "n_gpu_layers");
      if (nGpuLayers == null) {
        JsonNode dgs = root.get("default_generation_settings");
        if (dgs != null) {
          nGpuLayers = extractIntField(dgs, "n_gpu_layers");
        }
      }

      Integer totalSlots = extractIntField(root, "total_slots");
      String modelPath = root.has("model_path") ? root.get("model_path").asText() : null;

      return new GpuUsageInfo(nGpuLayers, totalSlots, modelPath);

    } catch (Exception e) {
      log.debug("Failed to query /props: {}", e.getMessage());
      return new GpuUsageInfo(null, null, null);
    }
  }

  private Integer extractIntField(JsonNode node, String fieldName) {
    if (node == null || !node.has(fieldName)) return null;
    JsonNode field = node.get(fieldName);
    if (field.isNumber()) return field.asInt();
    return null;
  }

  /**
   * Gets currently used VRAM via nvidia-smi.
   */
  private long getUsedVramBytes() {
    try {
      ProcessBuilder pb = new ProcessBuilder(
          "nvidia-smi",
          "--query-gpu=memory.used",
          "--format=csv,noheader,nounits");
      pb.redirectErrorStream(true);
      Process proc = pb.start();

      String output;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.readLine();
      }

      boolean exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      if (!exited) {
        proc.destroyForcibly();
        return -1;
      }

      if (proc.exitValue() != 0 || output == null || output.isBlank()) {
        return -1;
      }

      // nvidia-smi returns MB, convert to bytes
      String firstGpu = output.trim().lines().findFirst().orElse("").trim();
      long vramMb = Long.parseLong(firstGpu);
      return vramMb * 1024 * 1024;

    } catch (Exception e) {
      log.debug("Failed to query nvidia-smi: {}", e.getMessage());
      return -1;
    }
  }

  record GpuUsageInfo(Integer nGpuLayers, Integer totalSlots, String modelPath) {}

  // =========================================================================
  // Test 3: Streaming Response Arrives
  // =========================================================================

  @Test
  @DisplayName("Streaming chat completion returns SSE chunks with valid content")
  void testStreamingResponseArrives() throws Exception {
    assertTrue(llamaServerAvailable,
        "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    // Build streaming request
    String requestBody = objectMapper.writeValueAsString(Map.of(
        "model", "default",
        "messages", List.of(
            Map.of("role", "user", "content", "Count from 1 to 5, one number per line.")),
        "max_tokens", 100,
        "stream", true));

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + LLAMA_SERVER_PORT + "/v1/chat/completions"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMinutes(2))
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();

    log.info("Sending streaming request to llama-server...");
    long startTime = System.currentTimeMillis();

    HttpResponse<java.io.InputStream> response = httpClient.send(
        request, HttpResponse.BodyHandlers.ofInputStream());

    assertEquals(200, response.statusCode(),
        "❌ Expected 200 OK, got " + response.statusCode());

    // =========================================================================
    // ASSERTIONS: Verify streaming response
    // =========================================================================

    List<String> chunks = new ArrayList<>();
    StringBuilder fullContent = new StringBuilder();

    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data: ")) {
          String data = line.substring(6).trim();
          if ("[DONE]".equals(data)) {
            log.debug("Received [DONE] marker");
            break;
          }

          try {
            JsonNode json = objectMapper.readTree(data);
            String content = json.path("choices").path(0)
                .path("delta").path("content").asText("");
            if (!content.isEmpty()) {
              chunks.add(content);
              fullContent.append(content);
            }
          } catch (Exception e) {
            log.debug("Could not parse SSE data: {}", data);
          }
        }
      }
    }

    long elapsed = System.currentTimeMillis() - startTime;
    log.info("Received {} chunks in {}ms", chunks.size(), elapsed);

    // 4a. Received at least some chunks
    assertFalse(chunks.isEmpty(),
        "❌ Should receive at least one content chunk");

    // 4b. Full content is not empty
    String result = fullContent.toString();
    assertFalse(result.isBlank(),
        "❌ Assembled response should not be blank");

    // 4c. Response contains expected content (numbers)
    assertTrue(result.contains("1") || result.contains("one"),
        "❌ Response should contain counting content");

    log.info("✅ Streaming test PASSED: {} chunks, {} chars total",
        chunks.size(), result.length());
    log.info("Response preview: {}", truncate(result, 200));
  }

  // =========================================================================
  // Test 4: End-to-End Summarization
  // =========================================================================

  @Test
  @DisplayName("End-to-end summarization: chunk → index → retrieve → summarize")
  void testEndToEndSummarization() throws Exception {
    assertTrue(llamaServerAvailable,
        "❌ llama-server not running at localhost:" + LLAMA_SERVER_PORT);

    // Clean data directory for isolation
    cleanDataDirectory(env.getTempDir());

    // 1-2. Compose the Engine's index half in this JVM. This replaces spawn + port discovery +
    // channel: there is no second process to spawn, no signal file to read a port out of, and no
    // channel to open. startEngine() either hands back a working client or throws.
    startEngine(env.getTempDir());
    assertTrue(client.isHealthy(TEST_ENGINE_CONTEXT), "Engine should be healthy");

    // 3. Create test document file
    Path testDoc = testDataDir.resolve("quarterly-report.md");
    Files.writeString(testDoc, TEST_DOCUMENT);
    String filePath = testDoc.toAbsolutePath().toString();
    String docId = normalizeDocId(testDoc);
    log.info("Created test document: {} (docId: {})", filePath, docId);

    // 4. Submit for indexing
    int accepted = client.submitBatch(List.of(testDoc), TEST_ENGINE_CONTEXT).getAcceptedCount();
    assertEquals(1, accepted, "Should accept 1 file");

    // 5. Wait for indexing
    assertTrue(awaitIndexed(1, 30_000, 200), "Should index within 30s");

    // 6. Verify document is indexed and searchable
    assertTrue(awaitSearchable("quarterly", 10_000),
        "❌ Document should be searchable by 'quarterly'");
    assertTrue(awaitSearchable("revenue", 10_000),
        "❌ Document should be searchable by 'revenue'");

    // 7. Perform summarization via llama-server
    String summaryPrompt = """
        Summarize the following document in 2-3 sentences. Focus on the key financial metrics.

        Document:
        %s
        """.formatted(truncate(TEST_DOCUMENT, 4000));

    String summary = llamaClient.textCompletion(summaryPrompt, 256);

    // =========================================================================
    // ASSERTIONS: Verify E2E summarization quality
    // =========================================================================

    // 7a. Summary is not blank
    assertFalse(summary.isBlank(),
        "❌ Summary should not be blank");

    // 7b. Summary is reasonably sized (not too short, not just echoing input)
    assertTrue(summary.length() >= 50,
        "❌ Summary seems too short (" + summary.length() + " chars)");
    assertTrue(summary.length() < TEST_DOCUMENT.length(),
        "❌ Summary should be shorter than original document");

    // 7c. Summary mentions key topics (financial terms)
    String summaryLower = summary.toLowerCase(java.util.Locale.ROOT);
    boolean mentionsFinancials =
        summaryLower.contains("revenue") ||
        summaryLower.contains("growth") ||
        summaryLower.contains("billion") ||
        summaryLower.contains("quarter") ||
        summaryLower.contains("percent") ||
        summaryLower.contains("%");

    assertTrue(mentionsFinancials,
        "❌ Summary should mention key financial terms. Got: " + truncate(summary, 300));

    log.info("✅ E2E summarization test PASSED");
    log.info("Summary ({} chars): {}", summary.length(), truncate(summary, 500));
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
    var attempts = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(
        operations, java.time.Clock.systemUTC(), java.util.Set.of(
            io.justsearch.agent.api.registry.OperationKind.INGEST,
            io.justsearch.agent.api.registry.OperationKind.REINDEX,
            io.justsearch.agent.api.registry.OperationKind.RECONFIGURE,
            io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
            io.justsearch.agent.api.registry.OperationKind.ACCEPT_GAPS,
            io.justsearch.agent.api.registry.OperationKind.SCHEDULED_RUN));
    engine = new EngineRoot(operations, attempts, 30_000L, 5_000);
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

  /**
   * Reconstructs original content from chunks (verifies no gaps).
   */
  private String reconstructFromChunks(List<ChunkSplitter.Chunk> chunks, String original) {
    if (chunks.isEmpty()) return "";

    // Chunks may overlap, so we track covered positions
    StringBuilder result = new StringBuilder();
    int lastEnd = 0;

    for (ChunkSplitter.Chunk chunk : chunks) {
      if (chunk.startChar() > lastEnd) {
        // Gap - should not happen with proper overlap
        log.warn("Gap detected: {} to {}", lastEnd, chunk.startChar());
      }
      if (chunk.endChar() > lastEnd) {
        int start = Math.max(chunk.startChar(), lastEnd);
        result.append(original, start, chunk.endChar());
        lastEnd = chunk.endChar();
      }
    }

    return result.toString();
  }

  /**
   * Normalizes path to match Worker's document ID format.
   */
  private String normalizeDocId(Path path) {
    String absolutePath = path.toAbsolutePath().toString();
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
      return absolutePath.toLowerCase(java.util.Locale.ROOT);
    }
    return absolutePath;
  }

  /**
   * Waits until a search query returns at least one result.
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
   * Truncates string for logging.
   */
  private static String truncate(String s, int maxLen) {
    if (s == null) return "<null>";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "... [truncated, total " + s.length() + " chars]";
  }

  /**
   * Cleans data directory for test isolation.
   */
  private void cleanDataDirectory(Path dataDir) throws Exception {
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
    Files.deleteIfExists(dataDir.resolve("jobs.db"));
    Files.deleteIfExists(dataDir.resolve("job_queue.db"));
  }
}
