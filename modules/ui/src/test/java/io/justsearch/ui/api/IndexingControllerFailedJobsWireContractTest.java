/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 911 (885 UL.9) — the parse-boundary contract for the substrate-shaped failed-jobs
 * surfaces.
 *
 * <p>Both {@code GET /api/indexing-jobs/failed} and {@code GET /api/indexing-jobs/failed/by-prefix}
 * used to hand-build a {@code Map<String,Object>} that was <em>almost</em> an {@link
 * IndexingJobView} — {@code scanId} was dropped — so no schema described the wire, and the
 * FailedJobsDrawer read {@code state} (the RETRY_EXHAUSTED discriminator) off untyped JSON.
 *
 * <p>{@code WireRecordSchemaGenTest} pins record → schema. This pins the other end: the JSON the
 * live route actually returns validates against {@code
 * SSOT/schemas/failed-indexing-jobs-response.v1.json}. A handler that quietly reverts to a
 * hand-built map, or drops a field the schema requires, fails here rather than at the FE's Zod.
 */
@DisplayName("failed-jobs substrate endpoints conform to their wire schema")
class IndexingControllerFailedJobsWireContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SCHEMA_FILE = "SSOT/schemas/failed-indexing-jobs-response.v1.json";

  private Javalin app;
  private HttpClient client;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  /** A worker-reported row exactly as {@code KnowledgeClient} hands it to the Head. */
  private static IndexingService.FailedJobInfo info(
      String path, String error, int attempts, String collection, String state, String scanId) {
    return new IndexingService.FailedJobInfo(
        path, error, attempts, 1_700_000_000_000L, collection, state, scanId);
  }

  private static final Path ROOT = Path.of("C:", "corpus").toAbsolutePath();

  /**
   * Three rows that between them cover every branch of the projection: a plain FAILED row, the
   * terminal RETRY_EXHAUSTED row the drawer must be able to tell apart, and a row whose optional
   * fields the worker left null/blank (the defaulting branch). The three {@code scanId} values are
   * the three the queue can now report (911 §F): a real scan, a scan-less row, and a null the
   * projection must normalize.
   */
  private static final List<IndexingService.FailedJobInfo> ROWS =
      List.of(
          info(ROOT.resolve("a.pdf").toString(), "parse error", 3, "default", "FAILED", "scan-42"),
          info(
              ROOT.resolve("b.docx").toString(),
              "extraction timed out",
              41,
              "notes",
              IndexingJobView.STATE_RETRY_EXHAUSTED,
              ""),
          info(ROOT.resolve("c.txt").toString(), null, 1, null, "  ", null),
          // Tempdoc 941 round 19 (F2): the shape a real worker row actually has when it carries no
          // collection. proto3 has no null, so KnowledgeClient hands the Head "" — never the
          // null the row above simulates. Only the null branch was defaulted, so an untagged job
          // reached the drawer as an empty collection instead of "default".
          info(ROOT.resolve("d.md").toString(), "unreadable", 2, "", "FAILED", ""));

  private static IndexingService stubService() {
    return new IndexingService() {
      @Override
      public List<FailedJobInfo> listFailedJobs(int limit) {
        return ROWS;
      }

      @Override
      public List<FailedJobInfo> listFailedJobsByPathPrefix(Path pathPrefix, int limit) {
        return ROOT.equals(pathPrefix) ? ROWS : List.of();
      }

      @Override
      public List<WatchedRoot> getWatchedRoots() {
        return List.of(new WatchedRoot("default", ROOT, null, null, true, false, 0, 0L));
      }

      // The stub serves reads only; the mutating half of IndexingService is unreachable here.
      @Override
      public List<Path> getWatchedPaths() {
        return List.of(ROOT);
      }

      @Override
      public void addWatchedPath(Path path) {
        throw new UnsupportedOperationException();
      }

      @Override
      public int removeWatchedPath(Path path) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void flush() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private String startAndGet(String path) throws Exception {
    IndexingController controller =
        new IndexingController(
            IndexingControllerFailedJobsWireContractTest::stubService,
            null,
            null,
            null,
            io.justsearch.app.api.OperationLeaseService.noOp());
    WorkerCapability worker = new WorkerCapability();
    worker.transition(CapabilityHealth.READY, null);
    controller.setWorkerCapability(worker);

    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .get("/api/indexing-jobs/failed", controller::handleListFailedJobsSubstrate)
            .get(
                "/api/indexing-jobs/failed/by-prefix",
                controller::handleListFailedJobsByPathPrefix)
            .start(0);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), () -> path + " should return 200: " + resp.body());
    return resp.body();
  }

  private static void assertConformsToSchema(String label, String body) throws Exception {
    Path schemaPath = repoRoot().resolve(SCHEMA_FILE);
    assertTrue(Files.isRegularFile(schemaPath), () -> SCHEMA_FILE + " must exist");
    JsonNode schemaNode = JSON.readTree(Files.readString(schemaPath, StandardCharsets.UTF_8));
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    SchemaContext ctx =
        new SchemaContext(
            registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    Schema schema =
        ctx.newSchema(
            SchemaLocation.of("https://ssot.justsearch/v1/schemas/failed-indexing-jobs-response.v1.json"),
            schemaNode,
            null);
    var errors = schema.validate(JSON.readTree(body));
    assertTrue(
        errors.isEmpty(),
        () -> label + " does not conform to " + SCHEMA_FILE + ": " + errors + "\nbody: " + body);
  }

  private static Path repoRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    return cursor == null ? Path.of("").toAbsolutePath() : cursor;
  }

  @Test
  @DisplayName("GET /api/indexing-jobs/failed/by-prefix returns a schema-conformant body")
  void byPrefixConformsToSchema() throws Exception {
    String hash = sha256Hex(ROOT.toString());
    String body = startAndGet("/api/indexing-jobs/failed/by-prefix?pathHash=" + hash);
    assertConformsToSchema("GET /api/indexing-jobs/failed/by-prefix", body);

    JsonNode jobs = JSON.readTree(body).get("jobs");
    assertEquals(4, jobs.size());
    assertEquals(4, JSON.readTree(body).get("count").asInt());
    // The two facts the schema alone cannot state: the discriminator survives the projection, and
    // scanId is PRESENT (dropping it was what made the payload un-typeable in the first place).
    assertEquals("FAILED", jobs.get(0).get("state").asString());
    assertEquals(IndexingJobView.STATE_RETRY_EXHAUSTED, jobs.get(1).get("state").asString());
    assertTrue(jobs.get(0).has("scanId"), "scanId must be on the wire, not dropped");
    // 911 §F: scanId is the queue's own value now, not a placeholder every row shares. The three
    // rows must differ, or a hardcoded "" would pass the has()-check above unnoticed.
    assertEquals("scan-42", jobs.get(0).get("scanId").asString(), "the real scan id must survive");
    assertEquals("", jobs.get(1).get("scanId").asString(), "a scan-less row stays scan-less");
    assertEquals("", jobs.get(2).get("scanId").asString(), "a null scan id normalizes to empty");
    // A blank worker state defaults to FAILED rather than reaching the FE as "" — the drawer's
    // exhausted arm keys on an exact spelling, so an empty state must not be a third thing.
    assertEquals("FAILED", jobs.get(2).get("state").asString());
    assertEquals("default", jobs.get(2).get("collection").asString());
    assertEquals("", jobs.get(2).get("errorMessage").asString());
    // 941 F2: the blank the WIRE actually delivers defaults the same way the null does. Asserting
    // only the null branch is how "collection": "" reached the drawer.
    assertEquals(
        "default",
        jobs.get(3).get("collection").asString(),
        "a blank worker collection must default like a null one, not reach the FE empty");
    // ADR-0028: raw paths never appear on this wire.
    assertTrue(!body.contains("a.pdf"), "raw paths must not appear on the substrate wire");
  }

  @Test
  @DisplayName("GET /api/indexing-jobs/failed returns a schema-conformant body (same record)")
  void substrateConformsToSchema() throws Exception {
    String body = startAndGet("/api/indexing-jobs/failed");
    assertConformsToSchema("GET /api/indexing-jobs/failed", body);
    assertEquals(4, JSON.readTree(body).get("count").asInt());
  }

  private static String sha256Hex(String value) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
