/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 931 §E item 10 — {@code POST /api/indexing/settle} is the route a paired evaluation calls
 * between the indexing phase and the query phase so both arms query indexes with equal merge state.
 * The before/after counts in the 202 body are the evidence of that, so they have to survive the
 * controller mapping; a refusal has to be legible as one (409), not swallowed into a success.
 */
@DisplayName("IndexingController settle (contract)")
final class IndexingControllerSettleTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Javalin app;
  private HttpClient client;
  private RecordingIndexingService indexing;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    indexing = new RecordingIndexingService();
    IndexingController controller =
        new IndexingController(
            () -> indexing,
            new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing),
            null,
            null,
            io.justsearch.app.api.OperationLeaseService.noOp());
    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post("/api/indexing/settle", controller::handleSettleIndex)
            .start(0);
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private HttpResponse<String> post(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + app.port() + "/api/indexing/settle"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("202 carries the before/after document counts")
  void acceptedCarriesCounts() throws Exception {
    HttpResponse<String> resp = post("{\"expungeDeletesOnly\":true}");

    assertEquals(202, resp.statusCode(), resp.body());
    var json = JSON.readTree(resp.body());
    assertEquals("settle completed", json.path("status").asString());
    assertEquals(2851, json.path("maxDocBefore").asLong());
    assertEquals(222, json.path("numDocsBefore").asLong());
    assertEquals(222, json.path("maxDocAfter").asLong());
    assertEquals(222, json.path("numDocsAfter").asLong());
    assertEquals(4, json.path("segmentsAfter").asInt());
    assertEquals(1234, json.path("elapsedMs").asLong());
    assertEquals(List.of("true/0"), indexing.calls);
  }

  @Test
  @DisplayName("defaults to expungeDeletesOnly=true on an empty body")
  void defaultsToExpungeOnly() throws Exception {
    assertEquals(202, post("{}").statusCode());
    assertEquals(List.of("true/0"), indexing.calls);
  }

  @Test
  @DisplayName("passes the force-merge arguments through verbatim")
  void passesForceMergeArgs() throws Exception {
    assertEquals(
        202, post("{\"expungeDeletesOnly\":false,\"maxSegments\":3}").statusCode());
    assertEquals(List.of("false/3"), indexing.calls);
  }

  @Test
  @DisplayName("a worker refusal is a 409 naming the reason, not a 202")
  void refusalIsConflict() throws Exception {
    indexing.refusal = "Index migration is MIGRATING";

    HttpResponse<String> resp = post("{}");

    assertEquals(409, resp.statusCode(), resp.body());
    var json = JSON.readTree(resp.body());
    assertEquals("settle rejected by worker", json.path("status").asString());
    assertTrue(
        json.path("error").asString().contains("MIGRATING"),
        "the refusal must name the reason: " + resp.body());
  }

  private static final class RecordingIndexingService implements IndexingService {
    final List<String> calls = new ArrayList<>();
    String refusal;

    @Override
    public List<Path> getWatchedPaths(EngineContext engineContext) {
      return List.of();
    }

    @Override
    public void addWatchedPath(Path path, EngineContext engineContext) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public int removeWatchedPath(Path path, EngineContext engineContext) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public void flush(EngineContext engineContext) {
      // no-op
    }

    @Override
    public SettleIndexOutcome settleIndex(boolean expungeDeletesOnly, int maxSegments, EngineContext engineContext) {
      calls.add(expungeDeletesOnly + "/" + maxSegments);
      if (refusal != null) {
        return SettleIndexOutcome.refused(refusal);
      }
      return new SettleIndexOutcome(true, 2851L, 222L, 222L, 222L, 4, 1234L, "");
    }
  }
}
