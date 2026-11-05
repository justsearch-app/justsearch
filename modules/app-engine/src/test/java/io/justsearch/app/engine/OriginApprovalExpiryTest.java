/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OriginApprovalExpiryTest {
  @TempDir Path directory;

  @Test
  void originOnlyEvaluationSurvivesActualCompletedOperationAndJobHistoryExpiry() throws Exception {
    var authority = OperationAuthority.inMemory();
    Path operationDb = directory.resolve("operations.db");
    Path queueDb = directory.resolve("jobs.db");
    var root = new RecordedRootPlan.Root(directory.resolve("approved"), "docs", false, false, List.of("*.tmp"), List.of());
    String planHash = CanonicalOperationArguments.digest(new RecordedRootPlan("generation", List.of(root)).toReplayPayload());
    var ids = new java.util.ArrayList<String>();
    try (var operations = new SqliteOperationStore(operationDb); var queue = new SqliteJobQueue(queueDb)) {
      queue.open();
      for (OperationAuthorizationBasis basis : List.of(new OperationAuthorizationBasis.StructuralAuto(),
          new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.UNTRUSTED),
          new OperationAuthorizationBasis.EphemeralCapsule())) {
        String key = OperationKeys.generate(Clock.systemUTC());
        TransportTag transport = basis instanceof OperationAuthorizationBasis.StructuralAuto ? TransportTag.SYSTEM_INTERNAL : TransportTag.MCP;
        var context = EngineProvenance.context(EngineContext.ClientKind.MCP_CLIENT, "expiry", Optional.empty(), Optional.of(basis.encode()),
            transport, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
        var row = operations.accept(key, OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context, null).record();
        assertTrue(operations.start(row.id()));
        assertTrue(operations.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
        var origin = new SourceOrigin(UUID.randomUUID().toString(), SourceOrigin.Kind.IMPORT, root,
            SourceOrigin.Approval.gated(context, "core.ingest-files", key), row.acceptedAt(),
            new SourceOrigin.RestartBinding(1, UUID.randomUUID().toString(), planHash, null));
        ids.add(origin.id()); queue.recordSourceOrigins(List.of(origin));
      }
      queue.enqueue(List.of(directory.resolve("expired.txt")));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + operationDb); var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET completed_at=1,history_pending=0");
      }
      operations.pruneHistory();
      assertTrue(operations.recentHistory(200).isEmpty());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + operationDb); var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT count(*) FROM operations")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + queueDb); var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE jobs SET state='DONE',last_updated=1");
      }
      assertEquals(1, queue.cleanupOldJobs(30)); queue.cleanupOldLedgerEvents(30);
    }
    try (var queue = new SqliteJobQueue(queueDb)) {
      queue.open();
      var auto = queue.sourceOrigin(ids.get(0)).orElseThrow();
      var family = queue.sourceOrigin(ids.get(1)).orElseThrow();
      var session = queue.sourceOrigin(ids.get(2)).orElseThrow();
      assertTrue(authority.evaluateOriginApproval(auto));
      authority.grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
      assertTrue(authority.evaluateOriginApproval(family));
      authority.grants().revokeFamily("file-operations", SourceTier.UNTRUSTED);
      authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
      assertFalse(authority.evaluateOriginApproval(family));
      assertFalse(authority.evaluateOriginApproval(session));
    }
  }
}
