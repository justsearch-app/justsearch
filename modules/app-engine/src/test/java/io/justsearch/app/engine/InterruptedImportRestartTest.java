/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite databases are closed and reopened at the acceptance/queue crash boundary. */
final class InterruptedImportRestartTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final String GENERATION = "interrupted-import-generation";
  @TempDir Path directory;

  @Test void i1SessionRestartRetainsInterruptionWithoutReadingOrClaiming() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty(), "session approval must not survive restart");
      assertInterrupted(runtime, seed.key());
      assertEquals(0, runtime.store.find(seed.key()).orElseThrow().attempts());
    }
  }

  @Test void i2DurableRestartAutomaticallyContinuesAcceptedPlan() throws Exception {
    var seed = seed(false, true, false);
    try (var runtime = new Runtime()) {
      assertNotNull(runtime.store.importApprovalState(seed.id()).orElseThrow().binding(), "approval must be bound in acceptance before any origin or child exists");
      runtime.bind();
      assertEquals(List.of(seed.plan()), runtime.reads);
      assertEquals(OperationState.COMPLETE, runtime.store.find(seed.key()).orElseThrow().state());
    }
  }

  @Test void i3FrozenPartitionsAndOptionsSurviveChangedWatchConfiguration() throws Exception {
    var seed = seed(false, true, true);
    Files.writeString(directory.resolve("authority/watched_roots.json"),
        "{\"schemaVersion\":1,\"roots\":[]}");
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertEquals(seed.plan().roots(), runtime.reads.stream().flatMap(plan -> plan.roots().stream()).toList());
    }
  }

  @Test void i4DurableImportOutsideWatchedFoldersResumesWithoutBecomingWatched() throws Exception {
    var seed = seed(false, false, false);
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertEquals(List.of(seed.plan()), runtime.reads, "bound imports do not require watched coverage");
      assertTrue(runtime.authority.roots().watchedPaths().isEmpty());
    }
  }

  @Test void i9UnknownFamilyLabelNeverAuthorizesOrDisappears() throws Exception {
    var seed = seed(false, false, false);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var update = connection.prepareStatement("UPDATE operations SET grant_ref=? WHERE id=?")) {
      update.setString(1, new OperationAuthorizationBasis.FamilyGrant("indexing",
          io.justsearch.agent.api.registry.SourceTier.TRUSTED).encode());
      update.setLong(2, seed.id()); update.executeUpdate();
    }
    var authority = OperationAuthority.load(directory.resolve("authority"));
    authority.grants().grantFamilyAllowAlways("indexing", io.justsearch.agent.api.registry.SourceTier.TRUSTED);
    try (var runtime = new Runtime(authority, true)) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty());
      runtime.store.pruneHistory();
      assertInterrupted(runtime, seed.key());
      assertEquals("RECOVERY_AUTHORIZATION_REFUSED", runtime.coordinator.interruptedImports().getFirst().reason());
    }
  }

  @Test void i9LegacyApprovalNeverCreatesAuthorityButRemainsVisible() throws Exception {
    var seed = seed(false, true, false);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE operations SET active_approval_binding=NULL");
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty(), "unversioned records are not restart authority");
      assertInterrupted(runtime, seed.key());
    }
  }

  @Test void i12AlreadyFinishedWorkCompletesWithoutReauthorizingOrReading() throws Exception {
    var seed = seed(true, false, false);
    finishChild(seed);
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty());
      assertEquals(OperationState.COMPLETE, runtime.store.find(seed.key()).orElseThrow().state(),
          "durable completion is observed before authorization for new effects");
    }
  }

  @Test void i13InterruptionAndItsFrozenPlanSurviveRetentionWithoutIndexedDocuments() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      runtime.bind();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET completed_at=1,history_pending=0 WHERE completed_at IS NOT NULL");
      }
      runtime.store.pruneHistory();
      runtime.queue.cleanupOldJobs(0);
      runtime.queue.cleanupOldLedgerEvents(0);
      assertInterrupted(runtime, seed.key());
      assertEquals(seed.preparation(), runtime.store.acceptedPreparation(seed.id()).orElseThrow());
    }
  }

  @Test void i5PreparingOrCallingWithoutAcceptedApprovalChangesNothing() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      var before = runtime.store.importApprovalState(seed.id()).orElseThrow();
      var plan = runtime.coordinator.prepareImportResume(seed.key(), before.interruptionRevision());
      assertEquals(seed.plan(), plan.plan());
      assertThrows(RuntimeException.class, () -> runtime.coordinator.resumeImport(plan, null,
          runtime.store.find(seed.key()).orElseThrow().context()));
      assertEquals(before, runtime.store.importApprovalState(seed.id()).orElseThrow());
      assertEquals(1, runtime.coordinator.interruptedImports().size());
      assertTrue(runtime.coordinator.interruptedImports().getFirst().resumeAvailable());
    }
  }

  @Test void i6RenewedSessionInterruptsAgainAfterAnotherRestart() throws Exception {
    var seed = seed(true, false, false);
    String child;
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      runtime.renew(seed, true);
      assertEquals(1, runtime.reads.size());
      child = runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow().key();
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE, runtime.coordinator.recordedClaimDecision(child));
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertInterrupted(runtime, seed.key());
      assertTrue(runtime.reads.isEmpty());
      assertEquals(child, runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow().key());
      assertEquals(JobQueue.RecordedClaimDecision.DENY, runtime.coordinator.recordedClaimDecision(child));
      assertTrue(runtime.coordinator.interruptedImports().getFirst().resumeAvailable());
    }
  }

  @Test void i7ConcurrentDuplicateAndStaleRenewalsSelectOnlyOneRunner() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      var first = runtime.prepareRenewal(seed, true);
      var competing = runtime.prepareRenewal(seed, true);
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      var winners = new ArrayList<java.util.Map.Entry<Renewal, OperationAttemptRunner.PreparedAttempt>>();
      int refused = 0;
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var calls = List.of(first, competing).stream().map(renewal -> executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
          return java.util.Map.entry(renewal,
              runtime.runner.acceptPrepared(renewal.request(), renewal.preparation().nonce()));
        })).toList();
        try {
          assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally { start.countDown(); }
        for (var call : calls) {
          try { winners.add(call.get(5, java.util.concurrent.TimeUnit.SECONDS)); }
          catch (java.util.concurrent.ExecutionException lost) {
            assertInstanceOf(OperationStoreException.class, lost.getCause());
            refused++;
          }
        }
      }
      assertEquals(1, winners.size());
      assertEquals(1, refused);
      var winning = winners.getFirst();
      // Maintenance in the acceptance/body gap must wait, rather than consume session authority.
      runtime.coordinator.maintain();
      assertNull(runtime.store.importApprovalState(seed.id()).orElseThrow().interruptionReason());
      runtime.startRenewal(winning.getKey(), winning.getValue());
      runtime.startRenewal(winning.getKey(), winning.getValue());
      runtime.coordinator.maintain();
      assertEquals(1, runtime.reads.size());
      assertThrows(IllegalArgumentException.class, () -> runtime.coordinator.prepareImportResume(seed.key(), first.plan().interruptionRevision()));
      assertEquals(seed.preparation(), runtime.store.acceptedPreparation(seed.id()).orElseThrow(), "original preparation stays immutable");
    }
  }

  @Test void i8RevocationStopsClaimsAndBoundariesWithoutRetiringPendingMembers() throws Exception {
    var seed = seed(false, false, false);
    // A real persisted grant replaces structural auto, so revocation exercises the shared reader.
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var update = connection.prepareStatement("UPDATE operations SET grant_ref=? WHERE id=?")) {
      update.setString(1, new OperationAuthorizationBasis.OperationGrant("core.ingest-files",
          io.justsearch.agent.api.registry.SourceTier.TRUSTED).encode());
      update.setLong(2, seed.id()); update.executeUpdate();
    }
    var authority = OperationAuthority.load(directory.resolve("authority"));
    authority.grants().grantAllowAlways("core.ingest-files", io.justsearch.agent.api.registry.SourceTier.TRUSTED);
    try (var runtime = new Runtime(authority, true)) {
      runtime.bindPending();
      var child = runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow();
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE, runtime.coordinator.recordedClaimDecision(child.key()));
      runtime.authority.grants().revoke("core.ingest-files", io.justsearch.agent.api.registry.SourceTier.TRUSTED);
      assertEquals(JobQueue.RecordedClaimDecision.DENY, runtime.coordinator.recordedClaimDecision(child.key()));
      assertThrows(JobQueue.IngestionApprovalInterruptedException.class, () -> runtime.queue.allowsIngestionBoundary(
          JobQueue.IngestionBoundary.TRAVERSAL, child.key(), null));
      runtime.coordinator.maintain();
      assertInterrupted(runtime, seed.key());
      assertFalse(runtime.store.find(child.key()).orElseThrow().state().terminal());
      assertTrue(runtime.queue.pollPending(10).isEmpty());
      assertNull(runtime.queue.recordedWalk(child.key()).orElseThrow().sealedAt());
      assertEquals(0, runtime.queue.cleanupOldJobs(0));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "{}", "{", "unsupported"})
  void i9InvalidBindingsRemainInterruptedWithProvableResumeScope(String corruption) throws Exception {
    var seed = seed(false, false, false);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var update = connection.prepareStatement("UPDATE operations SET active_approval_binding=?")) {
      update.setString(1, corruption.equals("null") ? null : corruption); update.executeUpdate();
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty());
      assertInterrupted(runtime, seed.key());
      assertTrue(runtime.coordinator.interruptedImports().getFirst().resumeAvailable());
      runtime.renew(seed, false);
      assertEquals(OperationState.COMPLETE, runtime.store.find(seed.key()).orElseThrow().state());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"child", "walk", "admission", "closed"})
  void i10QueueCrashPointsRecoverTheSameChildWithoutRefusalSkips(String crashPoint) throws Exception {
    var seed = seed(false, false, false);
    String child;
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"));
        var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      store.start(seed.id());
      var accepted = store.acceptIngestChild(seed.key(), OperationKeys.generate(CLOCK), seed.preparation(), seed.plan()).record();
      child = accepted.key();
      if (!crashPoint.equals("child")) {
        store.start(accepted.id());
        var walk = queue.beginRecordedWalk(child, CanonicalOperationArguments.digest(seed.plan().toReplayPayload()), true);
        store.checkpoint(accepted.id(), "ingest-progress:1:" + walk.revision(), 0, 0);
        if (!crashPoint.equals("walk")) enqueue(queue, seed.plan(), child, walk.enumerationEpoch());
        if (crashPoint.equals("closed")) queue.closeRecordedWalkEnumeration(child, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      }
    }
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      assertEquals(child, runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow().key());
      assertEquals(crashPoint.equals("closed") ? 0 : 1, runtime.reads.size());
      assertNull(runtime.store.importApprovalState(seed.id()).orElseThrow().interruptionReason());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("jobs.db"));
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT count(*) FROM ingestion_ledger WHERE reason_code='RECOVERY_REFUSED'")) {
        assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
      }
    }
  }

  @Test void i11CompletedFoldersStayAndOnlyUnfinishedFoldersContinue() throws Exception {
    var seed = seed(false, false, true);
    finishChild(new Seed(seed.key(), seed.id(), new RecordedRootPlan(GENERATION, List.of(seed.plan().roots().getFirst())), seed.preparation()));
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertEquals(List.of(seed.plan().roots().get(1)), runtime.reads.stream().flatMap(plan -> plan.roots().stream()).toList());
      assertEquals(OperationState.COMPLETE, runtime.store.find(seed.key()).orElseThrow().state());
    }
  }

  @Test void i14CancellationWinsAgainstPreparedResumeAndRestart() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      var pending = runtime.prepareRenewal(seed, true);
      var context = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "cancel-test", Optional.empty(),
          Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      assertTrue(runtime.coordinator.cancelImport(seed.key(), context).success());
      assertThrows(OperationStoreException.class, () -> runtime.runner.acceptPrepared(pending.request(), pending.preparation().nonce()));
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty());
      assertEquals(OperationState.CANCELLED, runtime.store.find(seed.key()).orElseThrow().state());
    }
  }

  @Test void i15RenewalSelectsNewOriginAndRetainsHistoricalEvidence() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      runtime.renew(seed, true);
      var claim = runtime.queue.pollPending(1).getFirst();
      String prior = runtime.queue.sourceOriginId(claim).orElseThrow();
      var historical = runtime.queue.sourceOrigin(prior).orElseThrow();
      runtime.queue.returnUnfinishedClaims(List.of(claim));
      runtime.store.interruptImport(seed.id(), "revoked");
      runtime.coordinator.maintain();
      runtime.renew(seed, true);
      var next = runtime.queue.pollPending(1).getFirst();
      try {
        assertNotEquals(prior, runtime.queue.sourceOriginId(next).orElseThrow());
        assertEquals(historical, runtime.queue.sourceOrigin(prior).orElseThrow());
      } finally { runtime.queue.returnUnfinishedClaims(List.of(next)); }
    }
  }

  @Test void i8ClaimRereadsDurableLatchBeforeMaintenance() throws Exception {
    var seed = seed(false, false, false);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      var child = runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow();
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE, runtime.coordinator.recordedClaimDecision(child.key()));
      runtime.store.interruptImport(seed.id(), "external-revocation");
      assertEquals(JobQueue.RecordedClaimDecision.DENY, runtime.coordinator.recordedClaimDecision(child.key()));
      assertThrows(JobQueue.IngestionApprovalInterruptedException.class, () -> runtime.queue.allowsIngestionBoundary(
          JobQueue.IngestionBoundary.PUBLICATION, child.key(), null));
      assertTrue(runtime.queue.pollPending(1).isEmpty());
    }
  }

  @Test void i7PreviousRenewalProofCannotAuthorizeTheNextSelectedSession() throws Exception {
    var seed = seed(true, false, false);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      runtime.renew(seed, true);
      var child = runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow();
      runtime.store.interruptImport(seed.id(), "renew-again");
      runtime.coordinator.maintain();
      var renewal = runtime.prepareRenewal(seed, true);
      var accepted = runtime.runner.acceptPrepared(renewal.request(), renewal.preparation().nonce());
      runtime.coordinator.maintain();
      assertNull(runtime.store.importApprovalState(seed.id()).orElseThrow().interruptionReason(),
          "the selected body may still supply its own live proof");
      assertEquals(JobQueue.RecordedClaimDecision.DENY, runtime.coordinator.recordedClaimDecision(child.key()));
      runtime.startRenewal(renewal, accepted);
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE, runtime.coordinator.recordedClaimDecision(child.key()));
    }
  }

  @Test void i14CancellationDrainsClosedEnumerationAndItsIssuedClaim() throws Exception {
    var seed = seed(false, false, false);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      var child = runtime.store.findIngestChild(seed.key(), seed.plan()).orElseThrow();
      var claim = runtime.queue.pollPending(1).getFirst();
      var context = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "cancel-test", Optional.empty(),
          Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      assertTrue(runtime.coordinator.cancelImport(seed.key(), context).success());
      assertFalse(runtime.store.find(seed.key()).orElseThrow().state().terminal());
      assertEquals(JobQueue.RecordedClaimDecision.DENY, runtime.coordinator.recordedClaimDecision(child.key()));
      runtime.queue.returnUnfinishedClaims(List.of(claim));
      runtime.coordinator.maintain();
      assertEquals(OperationState.CANCELLED, runtime.store.find(seed.key()).orElseThrow().state());
      assertTrue(runtime.queue.pollPending(1).isEmpty());
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty());
      assertEquals(OperationState.CANCELLED, runtime.store.find(seed.key()).orElseThrow().state());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void i12LiveInterruptedOwnerCompletesAfterItsLastConfirmedClaim(boolean anotherRoot) throws Exception {
    var seed = seed(false, false, anotherRoot);
    try (var runtime = new Runtime()) {
      runtime.bindPending();
      var claim = runtime.queue.pollPending(1).getFirst();
      runtime.store.interruptImport(seed.id(), "revoked-after-publication");
      runtime.coordinator.maintain();
      runtime.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, "c".repeat(64))),
          io.justsearch.indexerworker.ingest.IngestionOutcome.of(
              io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
              io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
      runtime.coordinator.maintain();
      runtime.coordinator.maintain();
      if (anotherRoot) {
        assertInterrupted(runtime, seed.key());
        assertEquals(1, runtime.reads.size(), "interruption cannot start the remaining partition");
        runtime.renew(seed, false);
        assertEquals(2, runtime.reads.size());
        var remaining = runtime.queue.pollPending(1).getFirst();
        runtime.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(remaining, null, "d".repeat(64))),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        runtime.coordinator.maintain();
      }
      assertEquals(OperationState.COMPLETE, runtime.store.find(seed.key()).orElseThrow().state());
      assertEquals(anotherRoot ? 2 : 1, runtime.store.find(seed.key()).orElseThrow().unitsCompleted());
      assertEquals(anotherRoot ? 2 : 1, runtime.reads.size());
      assertEquals(0, runtime.admission.activeWorkCount());
    }
  }

  @Test void i16ExhaustedAttemptsStayVisibleWithoutReading() throws Exception {
    var seed = seed(false, false, false);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE operations SET attempts=" + OperationAttemptRunner.MAX_DURABLE_ATTEMPTS);
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertTrue(runtime.reads.isEmpty());
      assertInterrupted(runtime, seed.key());
      assertEquals(RecordedIngestionSettlement.EXHAUSTED, runtime.coordinator.interruptedImports().getFirst().reason());
    }
  }

  @Test void i16RecoveryWriteFailureRetainsVisibleExplanationAndRetries() throws Exception {
    var seed = seed(true, false, false);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TRIGGER refuse_latch BEFORE UPDATE OF interruption_reason ON operations BEGIN SELECT RAISE(FAIL,'latch unavailable'); END");
    }
    try (var runtime = new Runtime()) {
      runtime.bind();
      assertFalse(runtime.store.find(seed.key()).orElseThrow().state().terminal());
      assertFalse(runtime.coordinator.interruptedImports().isEmpty());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
          var statement = connection.createStatement()) { statement.executeUpdate("DROP TRIGGER refuse_latch"); }
      runtime.coordinator.maintain();
      assertInterrupted(runtime, seed.key());
    }
  }

  @Test void n1ReadinessWaitConsumesNoApprovalOrRecoveryAttempt() throws Exception {
    var seed = seed(false, false, false);
    try (var runtime = new Runtime(OperationAuthority.load(directory.resolve("authority")), false)) {
      runtime.bind();
      for (int pass = 0; pass < 5; pass++) runtime.coordinator.maintain();
      assertEquals(0, runtime.store.find(seed.key()).orElseThrow().attempts());
      assertTrue(runtime.coordinator.interruptedImports().isEmpty());
      runtime.online.set(true);
      runtime.coordinator.maintain();
      assertEquals(OperationState.COMPLETE, runtime.store.find(seed.key()).orElseThrow().state());
    }
  }

  private static void enqueue(SqliteJobQueue queue, RecordedRootPlan plan, String key, long epoch) {
    try {
      var root = plan.roots().getFirst();
      Path file = root.singleFile() ? root.path() : Files.writeString(root.path().resolve("pending.txt"), "pending import content");
      queue.enqueueRecordedEntries(key, epoch, List.of(new JobQueue.EnqueueEntry(file, Files.size(file)).withinRoot(root.path())), root.collection());
    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }

  private record Renewal(OperationAttemptRunner.Request request, OperationStore.Preparation preparation, RecordedImportResume plan) {}

  private void assertInterrupted(Runtime runtime, String key) {
    var row = runtime.store.find(key).orElseThrow();
    assertFalse(row.state().terminal(), "interruption must retain the open import");
    assertNotNull(runtime.store.importApprovalState(row.id()).orElseThrow().interruptionReason(),
        "retained import must explain why continuation stopped");
  }

  private Seed seed(boolean session, boolean watched, boolean partitioned) throws Exception {
    Path root = Files.createDirectories(directory.resolve("original"));
    var roots = new ArrayList<RecordedRootPlan.Root>();
    roots.add(new RecordedRootPlan.Root(root, "frozen", true, false, List.of("*.secret"), List.of()));
    if (partitioned) {
      Path file = Files.writeString(Files.createDirectories(root.resolve("nested")).resolve("only.txt"), "frozen");
      roots.add(new RecordedRootPlan.Root(file, "single", false, true, List.of("*.tmp"), List.of()));
    }
    var plan = RecordedRootPlan.partition(GENERATION, roots);
    Path authority = Files.createDirectories(directory.resolve("authority"));
    Files.writeString(authority.resolve("watched_roots.json"),
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
            "schemaVersion", 1, "roots", watched ? List.of(java.util.Map.of("path", directory.toString())) : List.of())));
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "restart-test", Optional.empty(),
          Optional.of(session ? new OperationAuthorizationBasis.EphemeralCapsule().encode()
              : new OperationAuthorizationBasis.StructuralAuto().encode()), TransportTag.SYSTEM_INTERNAL,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.now(CLOCK), Optional.empty()));
      var prepared = RecordedParentFixture.prepare(request, plan);
      store.savePreparation(request.key(), request.descriptor(), prepared);
      var row = store.acceptPrepared(request.key(), request.descriptor(), context, request.provenance(), prepared.nonce()).record();
      return new Seed(row.key(), row.id(), plan, prepared);
    }
  }

  private void finishChild(Seed seed) throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"));
        var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      assertTrue(store.start(seed.id()));
      var child = store.acceptIngestChild(seed.key(), OperationKeys.generate(CLOCK), seed.preparation(), seed.plan()).record();
      assertTrue(store.start(child.id()));
      var walk = queue.beginRecordedWalk(child.key(), CanonicalOperationArguments.digest(seed.plan().toReplayPayload()), true);
      queue.closeRecordedWalkEnumeration(child.key(), walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      queue.trySealRecordedWalk(child.key());
      var receipt = queue.sealedRecordedWalkReceipt(child.key()).orElseThrow();
      store.checkpoint(child.id(), RecordedIngestionReceipt.cursor(receipt), 0, 0);
      assertTrue(store.finish(child.id(), OperationState.COMPLETE, RecordedIngestionReceipt.terminalReceipt(receipt)).isPresent());
    }
  }

  private record Seed(String key, long id, RecordedRootPlan plan, OperationStore.Preparation preparation) {}

  private final class Runtime implements AutoCloseable {
    final SqliteOperationStore store = new SqliteOperationStore(directory.resolve("operations.db"));
    final OperationAuthority authority;
    final java.util.concurrent.atomic.AtomicBoolean online;
    final EngineAdmissionController admission = new EngineAdmissionController(2, 2, 1);
    final OperationAttemptRunnerImpl runner = new OperationAttemptRunnerImpl(store, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null, new RecordedIngestPlanResolver());
    final RecordedIngestionCoordinator coordinator;
    final SqliteJobQueue queue;
    final io.justsearch.indexerworker.server.RecordedIngestionLifecycle.Attachment attachment;
    final List<RecordedRootPlan> reads = new ArrayList<>();
    Runtime() throws Exception { this(OperationAuthority.load(directory.resolve("authority")), true); }
    Runtime(OperationAuthority authority, boolean ready) throws Exception {
      this.authority = authority;
      this.online = new java.util.concurrent.atomic.AtomicBoolean(ready);
      coordinator = new RecordedIngestionCoordinator(store, runner, admission, authority);
      queue = new SqliteJobQueue(directory.resolve("jobs.db"), coordinator::recordedClaimDecision);
      queue.open();
      attachment = coordinator.attach(queue, () -> Optional.of(GENERATION), online::get);
    }
    Renewal prepareRenewal(Seed seed, boolean session) {
      var state = store.importApprovalState(seed.id()).orElseThrow();
      var plan = coordinator.prepareImportResume(seed.key(), state.interruptionRevision());
      String args = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
          "operationKey", seed.key(), "interruptionRevision", state.interruptionRevision()));
      var context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "resume-test", Optional.empty(),
          Optional.of(session ? new OperationAuthorizationBasis.EphemeralCapsule().encode() : new OperationAuthorizationBasis.StructuralAuto().encode()),
          TransportTag.SYSTEM_INTERNAL, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK), OperationDescriptor.invocation(
          OperationKind.INGEST, RecordedImportResume.OPERATION_ID, args, false), context,
          EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(CLOCK), Optional.empty()));
      var preparation = RecordedParentFixture.prepare(request, java.util.UUID.randomUUID(),
          new io.justsearch.agent.api.registry.OperationPreparation(args, RecordedImportResume.SCHEMA, plan.encode()));
      runner.savePreparation(request, preparation);
      return new Renewal(request, preparation, plan);
    }
    void startRenewal(Renewal renewal, OperationAttemptRunner.PreparedAttempt accepted) {
      runner.start(accepted, handle -> coordinator.resumeImport(renewal.plan(), handle, renewal.request().context()));
    }
    void renew(Seed seed, boolean session) {
      var renewal = prepareRenewal(seed, session);
      startRenewal(renewal, runner.acceptPrepared(renewal.request(), renewal.preparation().nonce()));
    }
    void bindPending() {
      RecordedIngestionCoordinator.Producer producer = (plan, key, epoch, context, cancellation) -> {
        reads.add(plan); enqueue(queue, plan, key, epoch);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      };
      coordinator.bindProducer(producer, producer, producer);
      coordinator.maintain();
    }
    void bind() {
      RecordedIngestionCoordinator.Producer producer = (plan, key, epoch, context, cancellation) -> {
        reads.add(plan);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      };
      coordinator.bindProducer(producer, producer, producer);
      coordinator.maintain();
    }
    @Override public void close() throws Exception {
      coordinator.stopProducers(1000);
      attachment.close();
      queue.close();
      store.close();
    }
  }
}
