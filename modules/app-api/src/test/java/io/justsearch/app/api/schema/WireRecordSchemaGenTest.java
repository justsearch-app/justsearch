package io.justsearch.app.api.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.SchemaGenerator;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.AiPackImportStatus;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.agent.AgentHistoryResponse;
import io.justsearch.app.api.agent.AgentSessionsResponse;
import io.justsearch.app.api.indexing.FailedIndexingJobsResponse;
import io.justsearch.app.api.indexing.FailedJobsResponse;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.operations.OperationOutcomeView;
import io.justsearch.app.api.run.LiveRunsResponse;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.status.InferenceStatusResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 564 Phase B (4b breadth) — JSON Schemas for the FE drift-surface wire records.
 *
 * <p>These are the records behind the FE surfaces that still validate the wire with the fail-open
 * {@code .loose()} hand-Zod ({@code validateWithFallback}): the AI-runtime status, the offline-pack
 * policy + import status, and folder browse/files. Emitting a faithful JSON Schema for each (via the
 * shared {@link WireSchemaConfig}) lets the FE generate a precise {@code parseWireContract} Zod and
 * retire the hand schema — the same record→schema→{TS,Zod} pipeline proven for search/status.
 *
 * <p>Capture-or-verify into {@code SSOT/schemas/}; {@code -PupdateSchemas=true} rewrites the baselines.
 */
@DisplayName("Wire-record JSON Schema generation (4b drift surfaces)")
final class WireRecordSchemaGenTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static Path schemasDir;

  @BeforeAll
  static void setup() {
    schemaGenerator = WireSchemaConfig.generator();
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    schemasDir =
        cursor == null ? Path.of("SSOT/schemas").toAbsolutePath() : cursor.resolve("SSOT/schemas");
  }

  @Test
  @DisplayName("AiRuntimeStatusResponse")
  void aiRuntimeStatus() throws IOException {
    captureOrVerify(AiRuntimeStatusResponse.class, "ai-runtime-status-response.v1.json");
  }

  @Test
  @DisplayName("EffectivePolicy")
  void effectivePolicy() throws IOException {
    captureOrVerify(EffectivePolicy.class, "effective-policy.v1.json");
  }

  @Test
  @DisplayName("AiPackImportStatus")
  void aiPackImportStatus() throws IOException {
    captureOrVerify(AiPackImportStatus.class, "ai-pack-import-status.v1.json");
  }

  // Tempdoc 840 Phase 4 — the AI-install status. Not a record but a mutable public-field DTO, which
  // victools' PLAIN_JSON preset projects the same way; the shape it emits is the shape Jackson
  // serializes. It had THREE hand-maintained authorities (this class plus two independent TS
  // interfaces, one of them still modelling the retired v1 `assets[]`), and every field the staged
  // install added widened the drift. `AiInstallResourceCatalog.SCHEMA_URL` has pointed at
  // `ai-install-status.v1.json` since tempdoc 575 — the file it named simply never existed.
  @Test
  @DisplayName("AiInstallStatus")
  void aiInstallStatus() throws IOException {
    captureOrVerify(AiInstallStatus.class, "ai-install-status.v1.json");
  }

  @Test
  @DisplayName("FolderBrowseResponse")
  void folderBrowse() throws IOException {
    captureOrVerify(FolderBrowseResponse.class, "folder-browse-response.v1.json");
  }

  @Test
  @DisplayName("FolderFilesResponse")
  void folderFiles() throws IOException {
    captureOrVerify(FolderFilesResponse.class, "folder-files-response.v1.json");
  }

  // Tempdoc 564 Phase 3: the search-trace types — the last FE proto consumers move to the generated
  // Zod (the StageId/StageStatus @JsonValue enums emit as kebab/lowercase string enums).
  @Test
  @DisplayName("SearchTrace")
  void searchTrace() throws IOException {
    captureOrVerify(SearchTrace.class, "search-trace.v1.json");
  }

  @Test
  @DisplayName("SearchTrace.TraceStage")
  void traceStage() throws IOException {
    captureOrVerify(SearchTrace.TraceStage.class, "trace-stage.v1.json");
  }

  @Test
  @DisplayName("SearchTrace.HitStage")
  void hitStage() throws IOException {
    captureOrVerify(SearchTrace.HitStage.class, "hit-stage.v1.json");
  }

  // Tempdoc 564 Phase 3: the agent sessions/history surface — record-backed so the FE retires its
  // fail-open `.loose()` hand-Zod for a generated record→schema→Zod projection. The envelopes pull
  // in AgentSessionSummary / AgentBatchSummary / AgentTerminationReason as $defs.
  @Test
  @DisplayName("AgentSessionsResponse")
  void agentSessions() throws IOException {
    captureOrVerify(AgentSessionsResponse.class, "agent-sessions-response.v1.json");
  }

  @Test
  @DisplayName("AgentHistoryResponse")
  void agentHistory() throws IOException {
    captureOrVerify(AgentHistoryResponse.class, "agent-history-response.v1.json");
  }

  // Tempdoc 834 §5.1 (S4): the live-run enumeration — the FE's run-discovery authority, replacing a
  // localStorage pointer. Typed for the same reason the agent surface above is: a Map<String,Object>
  // here would be exactly the fail-open hole the 564 chain retired, on the one endpoint that hands
  // out the runIds every other run read is keyed by. The envelope pulls in LiveRunSummary /
  // ParkSummary / RunStateSnapshotView / PendingApprovalView as $defs.
  @Test
  @DisplayName("LiveRunsResponse")
  void liveRuns() throws IOException {
    captureOrVerify(LiveRunsResponse.class, "live-runs-response.v1.json");
  }

  // Tempdoc 564 Phase 5: the failed-jobs surface — record-backed so the FE validates it at the parse
  // boundary instead of an unchecked raw cast.
  @Test
  @DisplayName("FailedJobsResponse")
  void failedJobs() throws IOException {
    captureOrVerify(FailedJobsResponse.class, "failed-jobs-response.v1.json");
  }

  // Tempdoc 911 (885 UL.9): the substrate-shaped failed-jobs surfaces
  // (/api/indexing-jobs/failed and .../by-prefix). Both hand-built a Map that was almost an
  // IndexingJobView (scanId dropped), so nothing described the wire the FailedJobsDrawer reads
  // `state` off — the field the RETRY_EXHAUSTED rendering depends on. Both records are PreciseWire,
  // so the schema (and the FE Zod it generates) states required + non-null.
  @Test
  @DisplayName("FailedIndexingJobsResponse")
  void failedIndexingJobs() throws IOException {
    captureOrVerify(FailedIndexingJobsResponse.class, "failed-indexing-jobs-response.v1.json");
  }

  // Tempdoc 663 §L/Stage 4 — /api/inference/status moved off a hand-built Map onto this record; a
  // faithful JSON Schema (and the generated FE type it produces) closes the "hand-built endpoint,
  // hand-typed FE field, no schema between them" gap the design named.
  @Test
  @DisplayName("InferenceStatusResponse")
  void inferenceStatus() throws IOException {
    captureOrVerify(InferenceStatusResponse.class, "inference-status-response.v1.json");
  }

  // Tempdoc 683 — the /api/settings/v2 contract record (moved to app-api from modules/ui). Nulls
  // are semantically meaningful ("absent from request"), so SettingsV2 intentionally does NOT
  // implement PreciseWire; UiSettingsV2 / LlmSettingsV2 emit as $defs.
  @Test
  @DisplayName("SettingsV2")
  void settingsV2() throws IOException {
    captureOrVerify(SettingsV2.class, "settings-v2.v1.json");
  }

  /** The Library's recorded gap action consumes the live operation-history outcome. */
  @Test
  @DisplayName("OperationOutcomeView")
  void operationOutcomeView() throws IOException {
    captureOrVerify(OperationOutcomeView.class, "operation-outcome-view.v1.json");
  }

  private static void captureOrVerify(Class<?> type, String fileName) throws IOException {
    JsonNode current = schemaGenerator.generateSchema(type);
    // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
    String currentJson =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current).replace("\r\n", "\n");
    Path path = schemasDir.resolve(fileName);

    if ("true".equals(System.getProperty("updateSchemas"))) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + "\n");
      return;
    }
    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + "\n");
      fail("Schema captured at " + path + ". Re-run to verify (expected on first run).");
    }
    JsonNode baseline = MAPPER.readTree(Files.readString(path));
    assertEquals(
        baseline,
        current,
        "Schema for " + type.getSimpleName() + " diverged from baseline at " + path
            + ". Regenerate with: ./gradlew.bat :modules:app-api:updateSchemas");
    assertTrue(baseline.has("$schema"), "Baseline should declare $schema");
  }
}
