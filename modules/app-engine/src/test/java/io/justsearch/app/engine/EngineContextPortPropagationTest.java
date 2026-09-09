/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.search.SearchPort;
import io.justsearch.core.dto.Query;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

final class EngineContextPortPropagationTest {
  @Test
  void agentAdmissionSurvivesSwitchingAndQueueRestart(@TempDir Path directory) throws Exception {
    Path index = directory.resolve("index");
    Files.createDirectories(index.resolve("indices/g-building"));
    Files.writeString(index.resolve("state.json"), """
        {"format_version":2,"active_generation":"g-active","building_generation":"g-building",
         "previous_generation":null,"migration_state":"SWITCHING","migration_paused":false,
         "pause_reason":null,"paused_at_ms":null,"updated_at_ms":1}
        """);
    Path document = directory.resolve("agent-document.txt");
    Files.writeString(document, "agent-originated switching admission");
    Path database = directory.resolve("jobs.db");
    var caller = new EngineContext(EngineContext.ClientKind.MCP_CLIENT, "mcp-switch-test",
        java.util.Optional.of("agent-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    try (var queue = new io.justsearch.indexerworker.queue.SqliteJobQueue(database)) {
      queue.open();
      var ingest = new WorkerIngestService(queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), index,
          index.resolve("indices/g-building"), null, null, null, 0L);
      WorkerAppServices services = mock(WorkerAppServices.class);
      when(services.ingestService()).thenReturn(ingest);
      try (var client = new EngineKnowledgeClient(() -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop())) {
        client.submitBatch(List.of(document), caller);
        assertEquals(1, queue.switchBufferDepth());
        assertEquals(0, queue.pollPending(1).size(), "SWITCHING must really buffer admission");
      }
    }
    try (var queue = new io.justsearch.indexerworker.queue.SqliteJobQueue(database)) {
      queue.open();
      io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, null, null, null, index, index.resolve("indices/g-building"),
              tools.jackson.databind.json.JsonMapper.builder().build(), () -> false,
              org.slf4j.LoggerFactory.getLogger(getClass())));
      assertEquals(0, queue.switchBufferDepth());
      var claimed = queue.pollPending(1).getFirst();
      queue.markDone(document, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
          io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
          io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE, "Indexed"),
          io.justsearch.indexerworker.loop.LedgerEntryFactory.forPathOnly(
              claimed.path(), claimed.collection(), claimed.provenance()));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT originator, transport FROM ingestion_ledger")) {
      assertTrue(row.next());
      assertEquals("agent", row.getString("originator"));
      assertEquals("MCP", row.getString("transport"));
      assertFalse(row.next());
    }
  }

  @Test
  void actualPortCallsCarryBothAxesToWorkerService() {
    WorkerAppServices services = mock(WorkerAppServices.class);
    WorkerSearchService search = mock(WorkerSearchService.class);
    WorkerIngestService ingest = mock(WorkerIngestService.class);
    when(services.searchService()).thenReturn(search);
    when(services.ingestService()).thenReturn(ingest);
    List<CallContext> searches = new ArrayList<>();
    List<CallContext> indexing = new ArrayList<>();
    when(search.search(any(), any())).thenAnswer(call -> {
      searches.add(call.getArgument(1));
      return SearchResponse.getDefaultInstance();
    });
    when(ingest.listFailedJobs(any(), any())).thenAnswer(call -> {
      indexing.add(call.getArgument(1));
      return ListFailedJobsResponse.getDefaultInstance();
    });
    when(ingest.clearFailedJobs(any(), any())).thenAnswer(call -> {
      indexing.add(call.getArgument(1));
      return ClearFailedJobsResponse.getDefaultInstance();
    });
    try (var client = new EngineKnowledgeClient(() -> services,
        new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop())) {
      // The SearchPort signature is exercised directly, through KnowledgeClient and the bridge.
      SearchPort searchPort = client;
      searchPort.search(query("foreground"), TestEngineContexts.FOREGROUND);
      searchPort.search(query("background"), TestEngineContexts.BACKGROUND);
      IndexingService indexingPort = client;
      indexingPort.listFailedJobs(10, TestEngineContexts.BACKGROUND);
      indexingPort.clearFailedJobs(TestEngineContexts.FOREGROUND);
    }
    assertEquals(2, searches.size());
    assertSame(TestEngineContexts.FOREGROUND, searches.get(0).engineContext());
    assertSame(TestEngineContexts.BACKGROUND, searches.get(1).engineContext());
    assertEquals(2, indexing.size());
    assertSame(TestEngineContexts.BACKGROUND, indexing.get(0).engineContext());
    assertSame(TestEngineContexts.FOREGROUND, indexing.get(1).engineContext());
    for (CallContext call : searches) {
      assertEquals(call.engineContext().transport(), call.provenance().transport());
      assertEquals("system", call.provenance().originator());
    }
    assertEquals(EngineContext.Survival.DURABLE, searches.get(1).engineContext().survival());
  }

  private static Query query(String text) {
    return new Query(10, 0, false, null, List.of(),
        List.of(new Query.Clause("text", null, text, List.of())), null);
  }
}
