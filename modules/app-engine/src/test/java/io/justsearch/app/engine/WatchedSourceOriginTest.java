/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.operations.handlers.AddWatchedRootHandler;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WatchedSourceOriginTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"auto", "family", "session", "direct"})
  void acceptedWatchedDeclarationRetainsOnlyItsActualApprovalInputs(String mode) throws Exception {
    Path root = Files.createDirectory(directory.resolve("watched"));
    Path db = directory.resolve("jobs.db");
    String basis = switch (mode) {
      case "family" -> new OperationAuthorizationBasis.FamilyGrant("indexing",
          io.justsearch.agent.api.registry.SourceTier.TRUSTED).encode();
      case "session" -> new OperationAuthorizationBasis.EphemeralCapsule().encode();
      default -> new OperationAuthorizationBasis.StructuralAuto().encode();
    };
    var transport = mode.equals("direct") ? io.justsearch.agent.api.registry.TransportTag.HTTP_API
        : io.justsearch.agent.api.registry.TransportTag.SYSTEM_INTERNAL;
    var context = EngineProvenance.context(EngineContext.ClientKind.CLI, "origin-test",
        Optional.empty(), Optional.of(basis), transport, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
    var ingest = mock(WorkerIngestService.class);
    when(ingest.watchRoot(any(), any())).thenReturn(io.justsearch.ipc.WatchRootResponse.getDefaultInstance());
    doAnswer(call -> {
      call.<Consumer<io.justsearch.ipc.ScanRootProgress>>getArgument(1).accept(
          io.justsearch.ipc.ScanRootProgress.newBuilder().setComplete(true).build());
      return null;
    }).when(ingest).scanRoot(any(), any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);
    var state = WatchedRootsState.load(directory);
    try (var queue = new SqliteJobQueue(db); var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()),
            5_000, 100, IpcTelemetry.noop(), () -> {}, new EngineAdmissionController(8, 8, 1), state)) {
      queue.open();
      // Reflection keeps this check compilable on main. Its signature-only before patch supplies
      // the binder; no before-state declaration calls this new persistence callback.
      var bind = EngineKnowledgeClient.class.getDeclaredMethod("bindSourceOrigins", Consumer.class);
      Consumer<Object> persist = origin -> assertDoesNotThrow(() -> JobQueue.class
          .getMethod("recordSourceOrigins", List.class).invoke(queue, List.of(origin)));
      bind.invoke(client, persist);
      if (mode.equals("direct")) {
        assertDoesNotThrow(() -> client.addWatchedRoot("documents", root.resolve("."), context));
        client.addWatchedRoot("documents", root, context);
      } else {
        var record = mock(OperationRecordHandle.class);
        when(record.key()).thenReturn("accepted-watched-declaration");
        var handler = new AddWatchedRootHandler(() -> client);
        var result = handler.executeRecorded("{\"path\": " + new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(root.toString()) + ",\"collection\":\"documents\"}", null, context, record);
        assertTrue(result.response().success(), result.response().message());
      }
      assertTrue(state.watchedPaths().contains(root));
    }
    assertTrue(WatchedRootsState.load(directory).watchedPaths().contains(root), "watched declaration is durable");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.createStatement()) {
        try (var tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE name='source_origin'")) {
          assertTrue(tables.next(), "watched declarations require durable origin evidence");
        }
        try (var rows = statement.executeQuery("SELECT * FROM source_origin")) {
          assertTrue(rows.next());
          assertEquals("WATCHED", rows.getString("kind"));
          assertEquals(root.toString(), rows.getString("normalized_root"));
          assertEquals("documents", rows.getString("collection"));
          assertEquals("[]", rows.getString("partitions"));
          assertEquals("[]", rows.getString("request_exclusions"));
          assertEquals(context.transport(), rows.getString("recorded_transport"));
          assertEquals(context.sourceTier(), rows.getString("source_tier"));
          assertEquals("CLI", rows.getString("client_kind"));
          assertEquals(mode.equals("session") ? "SESSION" : "DURABLE", rows.getString("approval_class"));
          if (mode.equals("direct")) {
            assertNull(rows.getString("authorization_basis"), "caller grant header is not AUTO approval");
            assertNull(rows.getString("producing_operation_id"));
            assertNull(rows.getString("operation_key"));
            assertEquals("CALLER_DECLARED", rows.getString("evidence_source"));
          } else {
            assertEquals(basis, rows.getString("authorization_basis"));
            assertEquals("core.add-watched-root", rows.getString("producing_operation_id"));
            assertEquals("accepted-watched-declaration", rows.getString("operation_key"));
            assertEquals("GATE", rows.getString("evidence_source"));
          }
          assertFalse(rows.next(), "idempotent re-add is not another accepted declaration");
        }
      }
    }
  }
}
