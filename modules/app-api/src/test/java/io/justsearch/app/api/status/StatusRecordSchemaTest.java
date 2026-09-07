package io.justsearch.app.api.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import io.justsearch.app.api.knowledge.KnowledgeStatusView;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.KnowledgeStatusViewBuilder;
import io.justsearch.app.api.status.EnrichmentProgressViewBuilder;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.WorkerDebugViewBuilder;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Schema generation and contract validation for typed status records.
 *
 * <p>This test class serves two purposes:
 *
 * <ol>
 *   <li><b>Schema drift detection:</b> Generates JSON Schema from record types and validates that
 *       the generated schemas match the committed baseline schemas in {@code
 *       src/main/resources/schemas/}. If a record field is renamed or its type changes, the
 *       generated schema diverges from the baseline, and the test fails.
 *   <li><b>Contract validation:</b> Serializes sample record instances and validates them against
 *       the generated schemas. This ensures that Jackson serialization produces JSON that conforms
 *       to the schema derived from the same record type.
 * </ol>
 *
 * <p>Note on {@code @JsonUnwrapped}: The schema generator does not understand Jackson's {@code
 * @JsonUnwrapped}, so {@link StatusResponse}'s schema shows {@code worker} as a nested object while
 * the actual serialized form flattens its fields. Contract validation for StatusResponse therefore
 * validates the worker sub-view separately rather than the complete StatusResponse.
 */
@DisplayName("Status record JSON Schema generation and contract validation")
final class StatusRecordSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static SchemaRegistry schemaRegistry;

  @BeforeAll
  static void setupSchemaGenerator() {
    // Tempdoc 564: shared precise victools config (value-class string overrides + typed map-values).
    schemaGenerator = io.justsearch.app.api.schema.WireSchemaConfig.generator();
    schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  }

  // ---- Schema generation matches committed baseline ----

  @Nested
  @DisplayName("Schema drift detection")
  class SchemaDrift {

    @Test
    @DisplayName("StatusResponse schema matches baseline")
    void statusResponseSchemaMatchesBaseline() throws Exception {
      assertSchemaMatchesBaseline(StatusResponse.class, "status-response.schema.json");
    }

    @Test
    @DisplayName("KnowledgeStatusView schema matches baseline")
    void knowledgeStatusSchemaMatchesBaseline() throws Exception {
      assertSchemaMatchesBaseline(KnowledgeStatusView.class, "knowledge-status.schema.json");
    }

    @Test
    @DisplayName("WorkerDebugView schema matches baseline")
    void workerDebugSchemaMatchesBaseline() throws Exception {
      assertSchemaMatchesBaseline(WorkerDebugView.class, "debug-state.schema.json");
    }

    private void assertSchemaMatchesBaseline(Class<?> recordType, String baselineFileName)
        throws Exception {
      JsonNode generated = schemaGenerator.generateSchema(recordType);
      JsonNode baseline = loadResource("/schemas/" + baselineFileName);
      assertEquals(
          baseline,
          generated,
          "Generated schema for "
              + recordType.getSimpleName()
              + " differs from baseline "
              + baselineFileName
              + ". If the change is intentional, regenerate with: "
              + "./gradlew.bat :modules:app-api:updateSchemas");
    }
  }

  // ---- Contract validation: sample instances conform to their schema ----

  @Nested
  @DisplayName("Contract validation")
  class ContractValidation {

    @Test
    @DisplayName("KnowledgeStatusView sample conforms to schema")
    void knowledgeStatusSampleConformsToSchema() throws Exception {
      KnowledgeStatusView sample = sampleKnowledgeStatus();
      assertConformsToSchema(sample, KnowledgeStatusView.class);
    }

    @Test
    @DisplayName("WorkerDebugView sample conforms to schema")
    void workerDebugSampleConformsToSchema() throws Exception {
      WorkerDebugView sample = sampleWorkerDebugView();
      assertConformsToSchema(sample, WorkerDebugView.class);
    }

    @Test
    @DisplayName("WorkerOperationalView sample conforms to schema")
    void workerOperationalSampleConformsToSchema() throws Exception {
      WorkerOperationalView sample = WorkerOperationalView.fallback("SERVING");
      assertConformsToSchema(sample, WorkerOperationalView.class);
    }

    @Test
    @DisplayName("Tempdoc 415 F4: agentSessions field is omitted from JSON when null")
    void agentSessionsFieldOmittedWhenNull() throws Exception {
      // Build a fixture identical to sampleStatusResponse() but with agentSessions = null.
      // The @JsonInclude(NON_NULL) at the StatusResponse class level should cause Jackson
      // to omit the field from the wire format. This signals "agent capability unavailable"
      // by absence rather than always emitting agentSessions.activeCount = 0.
      var withNull =
          new StatusResponse(
              1,
              "2025-01-01T00:00:00Z",
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Lifecycle(
                  io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Components(
                  new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
                  new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
                  new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY)),
              "ok",
              "JustSearch",
              "C:/data",
              60000,
              1024000,
              2048000,
              4096000,
              null,
              WorkerOperationalView.fallback("SERVING"),
              true,
              null,
              null,
              null,
              false,
              new InferenceRuntimeView(
                  "ONLINE",
                  new RuntimeIdentityView(1L, "test-model", 8080, 1700000000000L),
                  false,
                  null,
                  new LifecycleCounters(0L, 0L, 1L),
                  true,
                  "Healthy",
                  "engine-healthy",
                  "",
                  "CHAT"),
              null,
              true,
              true,
              null,
              null,
              null,
              null,
              null,
              GpuStatusView.unavailable(),
              AtRestProtectionView.unknown(),
          ConversationProtectionView.unknown(),
              null,
              null, // agentSessions = null → must be omitted from JSON
              null, // telemetryHealth = null → must be omitted from JSON
              new StatusMeta(System.currentTimeMillis(), false));

      JsonNode json = MAPPER.valueToTree(withNull);
      assertFalse(
          json.has("agentSessions"),
          "agentSessions should be omitted when null (verifies @JsonInclude(NON_NULL))");
      assertFalse(
          json.has("telemetryHealth"),
          "telemetryHealth should be omitted when null (verifies @JsonInclude(NON_NULL))");
    }

    @Test
    @DisplayName("StatusResponse serializes with expected top-level fields")
    void statusResponseSerializesExpectedFields() throws Exception {
      StatusResponse sample = sampleStatusResponse();
      JsonNode serialized = MAPPER.valueToTree(sample);

      // Head-level fields
      assertEquals("ok", serialized.get("status").asText());
      assertEquals("JustSearch", serialized.get("service").asText());
      assertEquals(1, serialized.get("schema_version").asInt());
      assertNotNull(serialized.get("observed_at"));
      assertNotNull(serialized.get("lifecycle"));
      assertNotNull(serialized.get("components"));
      assertTrue(serialized.get("uptimeMs").asLong() > 0);

      // Memory and resource fields (M10: expand coverage)
      assertTrue(serialized.get("memoryUsedBytes").asLong() > 0);
      assertTrue(serialized.get("memoryTotalBytes").asLong() > 0);
      assertTrue(serialized.get("memoryMaxBytes").asLong() > 0);

      // Index availability
      assertTrue(serialized.has("indexAvailable"));

      // Readiness booleans
      assertTrue(serialized.has("aiReady"));
      assertTrue(serialized.has("embeddingReady"));

      // 384: Worker is nested under "worker" key (no @JsonUnwrapped)
      assertNotNull(serialized.get("worker"), "worker object should be present");
      var worker = serialized.get("worker");
      assertNotNull(worker.get("core"), "worker.core should be present");
      assertNotNull(worker.get("core").get("indexHealthy"), "worker.core.indexHealthy");
      assertNotNull(worker.get("core").get("indexState"), "worker.core.indexState");
      assertNotNull(worker.get("searchConfig"), "worker.searchConfig should be present");

      // Nested sub-views — Tempdoc 412 Phase 3: `llm` + `onlineAi` replaced by `inference`.
      assertNotNull(serialized.get("inference"));
      assertNotNull(serialized.get("readiness"));

      // 330 §4: Grouped sub-objects
      assertNotNull(serialized.get("embedding"), "grouped embedding sub-object missing");
      assertNotNull(serialized.get("schema"), "grouped schema sub-object missing");
      assertNotNull(serialized.get("chunkCoverage"), "grouped chunkCoverage sub-object missing");
      assertNotNull(serialized.get("queueHealth"), "grouped queueHealth sub-object missing");
      assertNotNull(serialized.get("migration"), "grouped migration sub-object missing");
    }

    @Test
    @DisplayName("StatusResponse has no duplicate aiReady/embeddingReady keys")
    void noDuplicateAiReadyKeys() throws Exception {
      // Build a StatusResponse where worker has non-null aiReady/embeddingReady
      var workerView = WorkerOperationalViewBuilder.builder()
          .core(new CoreIndexView(true, 42, 0, "SERVING", 1000, 0))
          .failure(new FailureTrackingView(0, "", "", 0, 0, 0, Map.of()))
          .migration(MigrationGenerationViewBuilder.builder()
              .activeGenerationId("gen-1")
              .activeIndexedDocuments(42)
              .migrationEnumerator(
                  new MigrationEnumeratorView(false, false, 0, 0, 0, 0, 0, 0, ""))
              .build())
          .compatibility(new CompatibilityStatusView(
              "COMPATIBLE", "", "fp-a", "fp-a", "fp-b", "fp-b", "COMPATIBLE", false, ""))
          .queueDb(new QueueDbStatusView(true, 0, 0, true, 0))
          .enrichment(EnrichmentProgressViewBuilder.builder()
              .chunk(new ChunkCoverageView(42, 42, 0, 0, 100.0, true, true, 42, 0, 100.0))
              .embeddingDocCount(42).embeddingCompletedCount(42).embeddingCoveragePercent(100.0)
              .spladeDocCount(42)
              .enrichmentCompleted(Map.of())
              .batchTiming(BatchTimingView.empty())
              .encoderProfiles(Map.of())
              .build())
          .gpu(GpuDiagnosticsView.empty())
          .vectorFormat(VectorFormatView.empty())
          .telemetry(new TelemetryMetricsView(500.0, 10, 50000, 12.5, "STABLE"))
          .searchConfig(SearchConfigView.empty())
          .aiReady(true)
          .embeddingReady(true) // non-null aiReady and embeddingReady
          .build();
      StatusResponse sample =
          new StatusResponse(
              1,
              "2025-01-01T00:00:00Z",
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Lifecycle(
                  io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Components(
                  new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
                  new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
                  new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY)),
              "ok",
              "JustSearch",
              "C:/data/index",
              60000,
              1024000,
              2048000,
              4096000,
              null,
              workerView,
              true,
              null,
              null,
              null,
              false,
              new InferenceRuntimeView(
                  "ONLINE",
                  new RuntimeIdentityView(1L, "test-model", 8080, 1700000000000L),
                  false,
                  null,
                  new LifecycleCounters(0L, 0L, 1L),
                  true,
                  "Healthy",
                  "engine-healthy",
                  "",
                  "CHAT"),
              new ReadinessEnvelopeView(
                  1,
                  "2025-01-01T00:00:00Z",
                  Map.of(),
                  Map.of()),
              true,
              true,
              EmbeddingStatusGroup.from(workerView, true),
              SchemaStatusGroup.from(workerView),
              ChunkCoverageGroup.from(workerView),
              QueueHealthGroup.from(workerView),
              MigrationStatusGroup.from(workerView),
              GpuStatusView.unavailable(),
              AtRestProtectionView.unknown(),
          ConversationProtectionView.unknown(),
              null,
              new AgentSessionView(0),
              TelemetryHealthView.empty(),
              null);

      // Serialize to raw JSON string and count occurrences of "aiReady"
      String json = MAPPER.writeValueAsString(sample);
      int aiReadyCount = countOccurrences(json, "\"aiReady\"");
      int embeddingReadyCount = countOccurrences(json, "\"embeddingReady\"");
      assertEquals(1, aiReadyCount, "aiReady should appear exactly once, got: " + aiReadyCount);
      assertEquals(
          1,
          embeddingReadyCount,
          "embeddingReady should appear exactly once, got: " + embeddingReadyCount);
    }

    @Test
    @DisplayName("341: Flat @JsonUnwrapped fields match grouped sub-object values")
    void flatFieldsMatchGroupedSubObjects() throws Exception {
      // Build a StatusResponse with non-default values so the comparison is meaningful.
      var worker = WorkerOperationalViewBuilder.builder()
          .core(new CoreIndexView(true, 42, 5, "SERVING", 1000, 2))
          .failure(new FailureTrackingView(3, "/a/b.txt", "parse error", 1700000000000L, 1700000060000L, 7, Map.of("pdf", 2L)))
          .migration(MigrationGenerationView.empty())
          .compatibility(new CompatibilityStatusView(
              "BLOCKED_MISMATCH", "model_changed", "fp-current", "fp-stored",
              "schema-fp-a", "schema-fp-b", "BLOCKED_LEGACY", true, "schema_mismatch"))
          .queueDb(new QueueDbStatusView(false, 1700000000000L, 1700000001000L, false, 1700000002000L))
          .enrichment(EnrichmentProgressViewBuilder.builder()
              .chunk(new ChunkCoverageView(100, 95, 3, 2, 95.0, true, true, 95, 3, 95.0))
              .embeddingDocCount(200).embeddingCompletedCount(180)
              .embeddingPendingCount(15).embeddingFailedCount(5).embeddingCoveragePercent(90.0)
              .spladeDocCount(200).spladeCompletedCount(150)
              .spladePendingCount(40).spladeFailedCount(10).spladeCoveragePercent(75.0)
              .pendingNerCount(8).completedNerCount(4)
              .enrichmentCompleted(Map.of("embed", 500L, "splade", 400L, "ner", 100L))
              .batchTiming(new BatchTimingView(
                  Map.of("embed", 10L, "splade", 10L, "ner", 8L),
                  Map.of("embed", 5200L, "splade", 3800L, "ner", 1400L)))
              .encoderProfiles(Map.of())
              .build())
          .gpu(GpuDiagnosticsView.empty())
          .vectorFormat(VectorFormatView.empty())
          .telemetry(new TelemetryMetricsView(2500.0, 50, 80000, 12.5, "HEALTHY"))
          .searchConfig(SearchConfigView.empty())
          .build();

      var sample = new StatusResponse(
          1, "2025-01-01T00:00:00Z",
          new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Lifecycle(
              io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
          new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Components(
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                  io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                  io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
              new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                  io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY)),
          "ok", "JustSearch", "C:/data", 60000, 1024000, 2048000, 4096000, null,
          worker, true, null, null,
          null, false,
          new InferenceRuntimeView(
              "ONLINE",
              new RuntimeIdentityView(1L, "test-model", 8080, 1700000000000L),
              false,
              null,
              new LifecycleCounters(0L, 0L, 1L),
              true,
              "Healthy",
              "engine-healthy",
              "",
              "CHAT"),
          null, true, true,
          EmbeddingStatusGroup.from(worker, true),
          SchemaStatusGroup.from(worker),
          ChunkCoverageGroup.from(worker),
          QueueHealthGroup.from(worker),
          MigrationStatusGroup.from(worker),
          GpuStatusView.unavailable(),
          AtRestProtectionView.unknown(),
          ConversationProtectionView.unknown(),
          null,
          new AgentSessionView(0),
          TelemetryHealthView.empty(),
          new StatusMeta(System.currentTimeMillis(), false));

      JsonNode json = MAPPER.valueToTree(sample);

      // 384: Worker fields are now nested — verify grouped objects match worker sub-records
      var workerJson = json.path("worker");
      assertEquals(workerJson.path("compatibility").path("embeddingCompatState").asText(),
          json.path("embedding").path("compatState").asText(),
          "worker.compatibility.embeddingCompatState must match grouped embedding.compatState");
      assertEquals(workerJson.path("compatibility").path("embeddingFingerprintCurrent").asText(),
          json.path("embedding").path("fingerprintCurrent").asText(),
          "worker.compatibility.embeddingFingerprintCurrent must match grouped");

      assertEquals(workerJson.path("compatibility").path("indexSchemaCompatState").asText(),
          json.path("schema").path("compatState").asText(),
          "worker.compatibility.indexSchemaCompatState must match grouped schema.compatState");
      assertEquals(workerJson.path("compatibility").path("reindexRequired").asBoolean(),
          json.path("schema").path("reindexRequired").asBoolean(),
          "worker.compatibility.reindexRequired must match grouped");

      // 384: Chunk coverage: worker nested fields must match grouped sub-object
      assertEquals(workerJson.path("enrichment").path("chunk").path("chunkDocCount").asLong(),
          json.path("chunkCoverage").path("docCount").asLong(),
          "worker.enrichment.chunk.chunkDocCount must match grouped");
      assertEquals(workerJson.path("enrichment").path("chunk").path("chunkVectorsReady").asBoolean(),
          json.path("chunkCoverage").path("ready").asBoolean(),
          "worker.enrichment.chunk.chunkVectorsReady must match grouped");

      // Queue DB health: worker nested fields must match grouped sub-object
      assertEquals(workerJson.path("queueDb").path("queueDbHealthy").asBoolean(),
          json.path("queueHealth").path("healthy").asBoolean(),
          "worker.queueDb.queueDbHealthy must match grouped");
      assertEquals(workerJson.path("queueDb").path("queueDbLastErrorAtMs").asLong(),
          json.path("queueHealth").path("lastErrorAtMs").asLong(),
          "worker.queueDb.queueDbLastErrorAtMs must match grouped");
    }

    private int countOccurrences(String haystack, String needle) {
      int count = 0;
      int idx = 0;
      while ((idx = haystack.indexOf(needle, idx)) != -1) {
        count++;
        idx += needle.length();
      }
      return count;
    }

    private <T> void assertConformsToSchema(T instance, Class<T> recordType) throws Exception {
      JsonNode schemaNode = schemaGenerator.generateSchema(recordType);
      var ctx = new com.networknt.schema.SchemaContext(
          schemaRegistry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()),
          schemaRegistry);
      Schema schema = ctx.newSchema(
          com.networknt.schema.SchemaLocation.of("mem://generated/" + recordType.getSimpleName()),
          schemaNode, null);

      JsonNode serialized = MAPPER.valueToTree(instance);
      var errors = schema.validate(serialized);
      assertTrue(
          errors.isEmpty(),
          "Serialized "
              + recordType.getSimpleName()
              + " does not conform to its schema: "
              + errors);
    }
  }

  // ---- Backward compatibility: versioned examples still deserialize ----

  @Nested
  @DisplayName("Backward compatibility")
  class BackwardCompat {

    @Test
    @DisplayName("status-v1.json contains required top-level fields")
    void statusV1ContainsRequiredFields() throws Exception {
      // StatusResponse uses @JsonUnwrapped which prevents round-trip deserialization
      // to the record type. Validate field presence on the JSON tree instead.
      JsonNode example = loadResource("/contract/status-v1.json");
      assertEquals("ok", example.get("status").asText());
      assertEquals(1, example.get("schema_version").asInt());
      assertNotNull(example.get("lifecycle"));
      assertNotNull(example.get("components"));
      assertTrue(example.get("indexAvailable").asBoolean());
      assertNotNull(example.get("llm"));
      assertNotNull(example.get("readiness"));
    }

    @Test
    @DisplayName("knowledge-status-v1.json deserializes into KnowledgeStatusView")
    void knowledgeStatusV1Deserializes() throws Exception {
      JsonNode example = loadResource("/contract/knowledge-status-v1.json");
      KnowledgeStatusView parsed = MAPPER.treeToValue(example, KnowledgeStatusView.class);
      assertTrue(parsed.ready());
      assertEquals(42, parsed.indexedDocuments());
    }

    @Test
    @DisplayName("KnowledgeStatusView emits @JsonProperty legacy field names (M11)")
    void knowledgeStatusViewEmitsLegacyFieldNames() throws Exception {
      KnowledgeStatusView sample = sampleKnowledgeStatus();
      JsonNode serialized = MAPPER.valueToTree(sample);
      // Legacy @JsonProperty names must appear for backward compatibility
      assertNotNull(serialized.get("queueDepth"), "legacy alias queueDepth missing");
      assertNotNull(serialized.get("docCount"), "legacy alias docCount missing");
      assertNotNull(serialized.get("activeDocCount"), "legacy alias activeDocCount missing");
      assertNotNull(serialized.get("buildingDocCount"), "legacy alias buildingDocCount missing");
      // Canonical names should also be present
      assertNotNull(serialized.get("pendingJobs"), "canonical pendingJobs missing");
      assertNotNull(serialized.get("indexedDocuments"), "canonical indexedDocuments missing");
    }

    @Test
    @DisplayName("debug-state-v1.json deserializes into WorkerDebugView")
    void debugStateV1Deserializes() throws Exception {
      JsonNode example = loadResource("/contract/debug-state-v1.json");
      WorkerDebugView parsed = MAPPER.treeToValue(example, WorkerDebugView.class);
      assertFalse(parsed.status().isEmpty());
      assertEquals(42, parsed.docCount());
    }
  }

  // ---- Cross-language contract fixture (368 RC1) ----

  @Nested
  @DisplayName("Cross-language contract fixture")
  class CrossLanguageContract {

    // Separate mapper with sorted keys for deterministic fixture output.
    private static final ObjectMapper CONTRACT_MAPPER =
        tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final Path STATUS_FIXTURE = resolveUiWebFixture("status-response-live.json");
    private static final Path SEARCH_FIXTURE = resolveUiWebFixture("search-response-live.json");

    private static Path resolveUiWebFixture(String filename) {
      Path fixtureRelative = Path.of("src", "api", "__fixtures__", filename);
      Path fromModule = Path.of("..").resolve("ui-web").resolve(fixtureRelative);
      Path fromRoot = Path.of("modules").resolve("ui-web").resolve(fixtureRelative);
      if (Files.isDirectory(fromModule.getParent())) {
        return fromModule;
      }
      return fromRoot;
    }

    @Test
    @DisplayName("StatusResponse fixture matches current Java serialization")
    void statusResponseFixtureMatchesCurrentSerialization() throws Exception {
      assertFixtureMatchesCurrentJson(STATUS_FIXTURE, sampleStatusResponse());
    }

    @Test
    @DisplayName("KnowledgeSearchResponse fixture matches current Java serialization")
    void searchResponseFixtureMatchesCurrentSerialization() throws Exception {
      assertFixtureMatchesCurrentJson(SEARCH_FIXTURE, sampleSearchResponse());
    }

    @Test
    @DisplayName("Regenerate contract fixtures when -PupdateSchemas=true")
    void regenerateContractFixtures() throws Exception {
      if (!"true".equals(System.getProperty("updateSchemas"))) {
        return;
      }
      writeFixture(STATUS_FIXTURE, sampleStatusResponse());
      writeFixture(SEARCH_FIXTURE, sampleSearchResponse());
    }

    private void assertFixtureMatchesCurrentJson(Path fixturePath, Object sample) throws Exception {
      String currentJson =
          CONTRACT_MAPPER.writerWithDefaultPrettyPrinter()
              .writeValueAsString(CONTRACT_MAPPER.valueToTree(sample))
              .replace("\r\n", "\n");

      if (!Files.exists(fixturePath)) {
        Files.createDirectories(fixturePath.getParent());
        Files.writeString(fixturePath, currentJson + "\n", StandardCharsets.UTF_8);
        return;
      }

      String fixtureJson =
          Files.readString(fixturePath, StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
      assertEquals(
          currentJson,
          fixtureJson,
          "Shared fixture " + fixturePath.getFileName() + " drifted from current Java "
              + "serialization. Regenerate with: "
              + "./gradlew.bat :modules:app-api:updateSchemas");
    }

    private void writeFixture(Path fixturePath, Object sample) throws Exception {
      // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
      String pretty =
          CONTRACT_MAPPER.writerWithDefaultPrettyPrinter()
              .writeValueAsString(CONTRACT_MAPPER.valueToTree(sample))
              .replace("\r\n", "\n");
      Files.createDirectories(fixturePath.getParent());
      Files.writeString(fixturePath, pretty + "\n", StandardCharsets.UTF_8);
    }
  }

  // ---- SSOT field completeness (380) ----

  @Nested
  @DisplayName("SSOT field completeness")
  class SsotFieldCompleteness {

    // Stored fields that are internal backend metadata — NOT consumed by the
    // frontend's SearchHit type. Each entry is a deliberate "this field is not
    // for the frontend" decision. Adding a new stored field to fields.v1.json
    // that is NOT in this set and NOT in the fixture will fail the test below.
    private static final Set<String> INTERNAL_FIELDS = Set.of(
        "author",                        // raw — meta_author is the normalized version
        "chunk_content",                 // chunk search only (ChunkSearchOps allowlist)
        "chunk_embedding_retry_count",   // enrichment metadata
        "chunk_embedding_status",        // enrichment metadata
        "chunk_end_char",                // chunk geometry
        "chunk_end_line",                // chunk geometry
        "chunk_heading_level",           // chunk geometry
        "chunk_heading_text",            // chunk geometry
        "chunk_index",                   // chunk geometry
        "chunk_parent_content_sha256",   // tempdoc 931 §C.1 — write-path RMW guard, never projected
        "chunk_start_char",              // chunk geometry
        "chunk_start_line",              // chunk geometry
        "chunk_total",                   // chunk geometry
        "content",                       // excluded by SearchResultFormatter (too large)
        "content_sha256",                // tempdoc 931 §C.6 — feedback capture key, stripped before the HTTP response
        "source_sha256",                 // source-byte extraction provenance, surfaced through preview
        "created_at",                    // not displayed in search results
        "doc_id",                        // identity — carried as top-level Hit.id, not fields
        "doc_uid",                       // internal dedup key
        "embedding_retry_count",         // enrichment metadata
        "embedding_status",              // enrichment metadata
        "entity_locations_raw",          // NER entity values
        "entity_organizations_raw",      // NER entity values
        "entity_persons_raw",            // NER entity values
        "extraction_method",             // indexing metadata
        "extraction_quality_score",      // indexing metadata
        "extraction_status",             // tempdoc 410 §11 — indexing provenance, not surfaced
        "content_truncated",             // tempdoc 410 §11 — indexing provenance, not surfaced
        "extraction_reason_code",        // tempdoc 410 §11 — indexing provenance, not surfaced
        "extraction_policy_id",          // tempdoc 410 §11 — indexing provenance, not surfaced
        "extraction_parser_id",          // tempdoc 410 §11 — indexing provenance, not surfaced
        "embedded_resource_count",       // tempdoc 410 §11 — indexing provenance, not surfaced
        "parser_warnings_count",         // tempdoc 410 §11 (Slice A.1) — indexing provenance, not surfaced
        "indexed_at",                    // indexing metadata
        "is_chunk",                      // chunk flag
        "ner_retry_count",               // enrichment metadata
        "ner_status",                    // enrichment metadata
        "ocr_present",                   // indexing metadata
        "parent_doc_id",                 // chunk parent reference
        "parent_token_count",            // indexing metadata
        "vdu_enrichment",                // VDU metadata
        "vdu_page_count",                // VDU metadata
        "vdu_processed",                 // VDU metadata
        "vdu_retry_count",               // VDU metadata
        "vdu_status",                    // VDU metadata
        "visual_extraction_evidence"     // OCR/VDU routing evidence surfaced through preview
    );

    @Test
    @DisplayName("380: every non-internal stored field in SSOT catalog appears in search fixture")
    void storedFieldsCoveredByFixture() throws Exception {
      // Read the SSOT field catalog
      Path catalogPath = Path.of("SSOT", "catalogs", "fields.v1.json");
      if (!Files.exists(catalogPath)) {
        catalogPath = Path.of("..", "..", "SSOT", "catalogs", "fields.v1.json");
      }
      assertTrue(Files.exists(catalogPath),
          "Cannot find fields.v1.json — run test from repo root or module dir");

      JsonNode catalog = MAPPER.readTree(Files.readString(catalogPath, StandardCharsets.UTF_8));
      JsonNode fields = catalog.get("fields");
      assertNotNull(fields, "fields.v1.json missing 'fields' array");

      // Collect stored field names from catalog
      Set<String> storedFields = new java.util.HashSet<>();
      for (JsonNode field : fields) {
        if (field.has("stored") && field.get("stored").asBoolean()) {
          storedFields.add(field.get("id").asText());
        }
      }

      // Expected in fixture = stored - internal
      Set<String> expectedInFixture = new java.util.HashSet<>(storedFields);
      expectedInFixture.removeAll(INTERNAL_FIELDS);

      // Get actual fixture fields
      var fixtureFields = sampleSearchResponse().results().get(0).fields().keySet();

      for (String expected : expectedInFixture) {
        assertTrue(fixtureFields.contains(expected),
            "Stored field '" + expected + "' is not internal but missing from fixture. "
                + "Either add it to sampleSearchResponse() or add it to INTERNAL_FIELDS "
                + "with a comment explaining why the frontend doesn't need it.");
      }
    }
  }

  // ---- Schema regeneration (updateSchemas mode) ----

  @Test
  @DisplayName("Regenerate baseline schemas when -PupdateSchemas=true")
  void regenerateSchemas() throws Exception {
    if (!"true".equals(System.getProperty("updateSchemas"))) {
      return; // Skip unless explicitly requested
    }
    Path schemasDir = Path.of("src", "main", "resources", "schemas");
    Files.createDirectories(schemasDir);
    writeSchema(StatusResponse.class, schemasDir.resolve("status-response.schema.json"));
    writeSchema(KnowledgeStatusView.class, schemasDir.resolve("knowledge-status.schema.json"));
    writeSchema(WorkerDebugView.class, schemasDir.resolve("debug-state.schema.json"));
  }

  private void writeSchema(Class<?> recordType, Path target) throws IOException {
    JsonNode schema = schemaGenerator.generateSchema(recordType);
    // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
    String pretty =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema).replace("\r\n", "\n");
    Files.writeString(target, pretty + "\n", StandardCharsets.UTF_8);
  }

  // ---- Helpers ----

  private static JsonNode loadResource(String path) throws IOException {
    try (InputStream is = StatusRecordSchemaTest.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new IOException("Resource not found: " + path);
      }
      return MAPPER.readTree(is);
    }
  }

  static StatusResponse sampleStatusResponse() {
    return new StatusResponse(
        1,
        "2025-01-01T00:00:00Z",
        new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Lifecycle(
            io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
        new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Components(
            new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
            new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY),
            new io.justsearch.app.api.lifecycle.LifecycleSnapshotV1.Component(
                io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY)),
        "ok",
        "JustSearch",
        "C:/data/index",
        60000,
        1024000,
        2048000,
        4096000,
        null,
        WorkerOperationalView.fallback("SERVING"),
        true,
        null,
        null,
        null,
        false,
        new InferenceRuntimeView(
            "ONLINE",
            new RuntimeIdentityView(1L, "test-model", 8080, 1700000000000L),
            false,
            null,
            new LifecycleCounters(0L, 0L, 1L),
            true,
            "Healthy",
            "engine-healthy",
            "",
            "CHAT"),
        new ReadinessEnvelopeView(
            1,
            "2025-01-01T00:00:00Z",
            Map.of(
                "workerControlPlane",
                new ReadinessComponentView("READY", null, "worker", "2025-01-01T00:00:00Z", false, 0),
                "indexServing",
                new ReadinessComponentView("READY", null, "worker", "2025-01-01T00:00:00Z", false, 0),
                "ai",
                new ReadinessComponentView("READY", null, "inference", "2025-01-01T00:00:00Z", false, 0),
                "embedding",
                new ReadinessComponentView("READY", null, "worker", "2025-01-01T00:00:00Z", false, 0)),
            Map.of(
                "retrieval", new ReadinessCompositeView("READY", List.of(), false, 0),
                "aiFeatures", new ReadinessCompositeView("READY", List.of(), false, 0))),
        true,
        true,
        // 330 §4: Grouped sub-objects (fixed timestamps for reproducible fixture)
        new EmbeddingStatusGroup(1704067200000L, "COMPATIBLE", "", "fp-a", "fp-a",
            true, 0, 0, 0, 0, 0.0),
        new SchemaStatusGroup(1704067200000L, "fp-b", "fp-b", "COMPATIBLE", false, ""),
        new ChunkCoverageGroup(1704067200000L, 0, 0, 0, 0, 0.0, false),
        new QueueHealthGroup(1704067200000L, true, 0, 0, true, 0),
        new MigrationStatusGroup(1704067200000L, "", "", "", false, "", 0, 0,
            new MigrationEnumeratorView(false, false, 0, 0, 0, 0, 0, 0, ""), ""),
        // 335 §9: GPU status
        GpuStatusView.unavailable(),
        // 629 (FLOOR): at-rest protection
        AtRestProtectionView.unknown(),
          ConversationProtectionView.unknown(),
        // 381: Model distribution status
        null,
        // 415: Active agent session count
        new AgentSessionView(0),
        // 419 C3 V1: telemetry-subsystem health counters
        TelemetryHealthView.empty(),
        // 333 §5: Freshness metadata (fixed timestamp for reproducible fixture)
        new StatusMeta(1704067200000L, false));
  }

  static KnowledgeStatusView sampleKnowledgeStatus() {
    return KnowledgeStatusViewBuilder.builder()
        .state("READY").ready(true)
        .indexedDocuments(42).activeIndexedDocuments(42)
        .servingSearchGenerationId("gen-1").servingIngestGenerationId("gen-1")
        .healthy(true).indexState("SERVING")
        .embeddingCompatState("COMPATIBLE")
        .embeddingFingerprintCurrent("fp-abc123").embeddingFingerprintStored("fp-abc123")
        .embeddingCoveragePercent(100.0).spladeCoveragePercent(100.0)
        .completedNerCount(42)
        .build();
  }

  static WorkerDebugView sampleWorkerDebugView() {
    return WorkerDebugViewBuilder.builder()
        .status("ok")
        .docCount(42)
        .activeDocCount(42)
        .servingSearchGenerationId("gen-1")
        .servingIngestGenerationId("gen-1")
        .isHealthy(true)
        .lastCommitTimestamp(1700000000000L)
        .migrationEnumerator(
            new DebugMigrationEnumeratorView(
                false, true, 1, 1, 10, 10, 1700000000000L, 1700000001000L, ""))
        .uptimeMs(60000)
        .healthCheck(new HealthNodeView(true, "1.0.0", 12345, "RUNNING", true, true))
        .effectiveConfig(Map.of("ort.version", "1.20.0"))
        .build();
  }

  static KnowledgeSearchResponse sampleSearchResponse() {
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(2)
        .tookMs(42)
        .results(List.of(
            new KnowledgeSearchResponse.Hit(
                "doc-1",
                0.95,
                Map.ofEntries(
                    Map.entry("collection", "docs"),
                    Map.entry("content_preview", "Overview of the system architecture..."),
                    Map.entry("file_kind", "markdown"),
                    Map.entry("filename", "architecture.md"),
                    Map.entry("language", "en"),
                    Map.entry("meta_author", "engineering"),
                    Map.entry("meta_category", "architecture"),
                    Map.entry("meta_published_at", "2025-06-01T00:00:00Z"),
                    Map.entry("meta_source", "documentation"),
                    Map.entry("mime", "text/markdown"),
                    Map.entry("mime_base", "text"),
                    Map.entry("modified_at", "2025-06-15T10:30:00Z"),
                    Map.entry("path", "C:/docs/architecture.md"),
                    Map.entry("size_bytes", "12345"),
                    Map.entry("title", "System Architecture"),
                    Map.entry("vdu_demand_kind", "visual_enrichment"),
                    Map.entry(
                        "visual_extraction_evidence",
                        "{\"schemaVersion\":1,\"route\":\"structured\",\"textQualityScore\":0.95}")),
                List.of("title", "content_preview"),
                List.of(
                    new KnowledgeSearchResponse.MatchSpan("title", 7, 19, "architecture")),
                List.of(
                    new KnowledgeSearchResponse.ExcerptRegion(
                        "Overview of the system architecture and its components.",
                        0, 55, 1, List.of())),
                // Tempdoc 549 Phase A: per-hit slice of the unified stage vocabulary.
                List.of(
                    new SearchTrace.HitStage(
                        SearchTrace.StageId.SPARSE_RETRIEVAL, 1, 12.5f, null),
                    new SearchTrace.HitStage(
                        SearchTrace.StageId.DENSE_RETRIEVAL, 2, 0.8f, null),
                    new SearchTrace.HitStage(
                        SearchTrace.StageId.CROSS_ENCODER, 1, 0.95f, Map.of("score", 0.95f)))),
            KnowledgeSearchResponseHitBuilder.builder()
                .id("doc-2").score(0.72)
                .fields(Map.of("path", "C:/docs/design.pdf", "title", "Design Document"))
                .matchedFields(List.of("title"))
                .build()))
        .facets(Map.of(
            "file_kind", Map.of("markdown", 5L, "pdf", 3L),
            "language", Map.of("de", 1L, "en", 7L)))
        .facetsTruncated(false)
        // Tempdoc 549 U4 (Slice 6): flat query-trace fields removed; carried by introspection.
        .entityFacetVariants(Map.of(
            "person",
            List.of(
                new KnowledgeSearchResponse.EntityVariantBreakdown(
                    "John Doe", 3, Map.of("J. Doe", 1L, "John", 2L)))))
        .indexCapabilities(
            new KnowledgeSearchResponse.IndexCapabilities(0.95, 0.80, 0.90, true))
        // Tempdoc 549 Phase E3: pipelineExecution retired — per-stage timing/status on searchTrace.
        .queryUnderstanding(new KnowledgeSearchResponse.QueryUnderstanding(
            Map.of("meta_source", List.of("techcrunch")),
            42L,
            "COMPARISON"))
        // Tempdoc 549 Phase E4: introspection retired — the unified canonical stage-keyed trace
        // (query-level) below is the single source for query-trace data.
        .searchTrace(
            new SearchTrace(
                SearchTrace.SCHEMA_VERSION,
                "hybrid",
                "multi_leg",
                new SearchTrace.Qpp(8.5f, 0.42f, 0.73f),
                new SearchTrace.Degradation(false, null, false, null, true, null),
                List.of(
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.QUERY_UNDERSTANDING,
                        SearchTrace.StageStatus.EXECUTED,
                        null,
                        null,
                        "COMPARISON",
                        null),
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.SPARSE_RETRIEVAL,
                        SearchTrace.StageStatus.EXECUTED,
                        null,
                        3L,
                        "bm25",
                        null),
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.DENSE_RETRIEVAL,
                        SearchTrace.StageStatus.EXECUTED,
                        null,
                        4L,
                        null,
                        null),
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.FUSION,
                        SearchTrace.StageStatus.EXECUTED,
                        null,
                        null,
                        "cc",
                        null),
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.CHUNK_MERGE,
                        SearchTrace.StageStatus.EXECUTED,
                        null,
                        5L,
                        null,
                        null),
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.CROSS_ENCODER,
                        SearchTrace.StageStatus.EXECUTED,
                        null,
                        8L,
                        null,
                        null),
                    new SearchTrace.TraceStage(
                        SearchTrace.StageId.FRESHNESS,
                        SearchTrace.StageStatus.DISABLED,
                        null,
                        null,
                        null,
                        null))))
        // Tempdoc 366 §1b: the filter echo. The sample carries an ACTIVE filter set so the
        // cross-language fixture exercises the shape the FE actually receives (a scoped search),
        // not the null the unscoped case emits.
        .appliedFilters(
            KnowledgeSearchResponse.AppliedFilters.of(
                io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder.builder()
                    .pathPrefix("C:/docs")
                    .fileKind(List.of("markdown"))
                    .build(),
                null))
        .build();
  }
}
