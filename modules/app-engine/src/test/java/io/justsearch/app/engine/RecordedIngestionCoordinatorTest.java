/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.CanonicalOperationArguments;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.ReindexHandler;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.justsearch.app.api.operations.OperationRecord;
import org.junit.jupiter.api.io.TempDir;

/** Actual runner/store/queue tests; fake enumeration exit stands in for the later bounded client adapter. */
final class RecordedIngestionCoordinatorTest {
  private static final String GENERATION = "coordinator-generation";
  private static final Clock CLOCK = Clock.systemUTC();
  @TempDir Path temp;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void twoInterruptedImportsDoNotBlockFreshDispatch(boolean reindex) throws Exception {
    try (Fixture f = new Fixture(temp, 3)) {
      var dispatcher = capacityDispatcher(f);
      var reads = new ArrayList<Path>();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        Path root = plan.roots().getFirst().path();
        reads.add(root);
        f.queue.enqueueRecordedEntries(key, epoch,
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(root.resolve("pending.txt"))), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      String first = dispatchImport(f, dispatcher, f.plan.roots().get(0).path());
      String second = dispatchImport(f, dispatcher, f.plan.roots().get(1).path());
      assertEquals(2, f.admission.activeWorkCount());
      f.authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
      f.coordinator.maintain();
      assertRetainedImport(f.coordinator, f.operations, first, "RECOVERY_AUTHORIZATION_REFUSED");
      assertRetainedImport(f.coordinator, f.operations, second, "RECOVERY_AUTHORIZATION_REFUSED");
      f.authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
      if (reindex) {
        var seed = f.request();
        var operation = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.REINDEX.value()).orElseThrow();
        assertTrue(dispatcher.dispatch(operation, "{\"force\":false}", seed.provenance(), Optional.empty(),
            seed.context(), OperationKeys.generate(CLOCK)).success());
      } else {
        dispatchImport(f, dispatcher, f.plan.roots().get(2).path());
      }
      assertEquals(f.plan.roots().stream().map(RecordedRootPlan.Root::path).toList(), reads);
      assertEquals(1, f.admission.activeWorkCount(), "only the fresh dispatch consumes capacity");
      assertEquals(Set.of(first, second), f.coordinator.interruptedImports().stream()
          .map(item -> item.operationKey()).collect(java.util.stream.Collectors.toSet()));
    }
  }

  @Test
  void resumeRefusedAtFullCapacityKeepsTheImportVisible() throws Exception {
    try (Fixture f = new Fixture(temp, 3)) {
      var dispatcher = capacityDispatcher(f);
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        f.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(
            plan.roots().getFirst().path().resolve("pending.txt"))), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      String key = dispatchImport(f, dispatcher, f.plan.roots().getFirst().path());
      f.authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
      f.coordinator.maintain();
      var before = f.operations.importApprovalState(f.operations.find(key).orElseThrow().id()).orElseThrow();
      f.authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
      dispatchImport(f, dispatcher, f.plan.roots().get(1).path());
      dispatchImport(f, dispatcher, f.plan.roots().get(2).path());
      var context = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "resume-capacity", Optional.empty(),
          Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(CLOCK), Optional.empty());
      var operation = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.RESUME_IMPORT.value()).orElseThrow();
      String arguments = new tools.jackson.databind.ObjectMapper().writeValueAsString(
          Map.of("operationKey", key, "interruptionRevision", before.interruptionRevision()));
      var refused = assertThrows(io.justsearch.app.api.EngineAdmissionException.class,
          () -> dispatcher.dispatch(operation, arguments, provenance, Optional.empty(), context, OperationKeys.generate(CLOCK)));
      assertEquals(io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT, refused.reason());
      assertEquals(before, f.operations.importApprovalState(f.operations.find(key).orElseThrow().id()).orElseThrow());
      assertRetainedImport(f.coordinator, f.operations, key, "RECOVERY_AUTHORIZATION_REFUSED");
      assertEquals(2, f.admission.activeWorkCount());
    }
  }

  @Test
  void acceptedResumeWithoutWorkCapacityRelatchesTheSameImport() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var dispatcher = capacityDispatcher(f);
      var starts = new AtomicInteger();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        f.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(
            plan.roots().getFirst().path().resolve("pending.txt"))), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      String key = dispatchImport(f, dispatcher, f.plan.roots().getFirst().path());
      var parent = f.operations.find(key).orElseThrow();
      var prepared = f.operations.acceptedPreparation(parent.id()).orElseThrow();
      var plan = new RecordedIngestPlanResolver().resolve(parent, prepared);
      var child = f.operations.findIngestChild(key, plan).orElseThrow();
      f.authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
      f.coordinator.maintain();
      assertEquals(0, f.admission.activeWorkCount());
      try (var other = f.admission.admit(f.request().context(), false)) {
        assertNotNull(other);
        // The renewal occupies the last admission slot while its handler asks the import to run.
        dispatchResume(f, dispatcher, key);
        assertRetainedImport(f.coordinator, f.operations, key, "ENGINE_LIMIT");
        assertEquals(1, f.admission.activeWorkCount());
        assertEquals(1, starts.get());
      }
      f.coordinator.maintain();
      assertRetainedImport(f.coordinator, f.operations, key, "ENGINE_LIMIT");
      dispatchResume(f, dispatcher, key);
      assertTrue(f.coordinator.interruptedImports().isEmpty());
      assertEquals(1, f.admission.activeWorkCount());
      assertEquals(parent.id(), f.operations.find(key).orElseThrow().id());
      assertEquals(prepared, f.operations.acceptedPreparation(parent.id()).orElseThrow());
      assertEquals(child.id(), f.operations.findIngestChild(key, plan).orElseThrow().id());
      assertEquals(1, starts.get(), "closed enumeration resumes claims without a new producer");
      var claims = f.queue.pollPending(1);
      assertEquals(1, claims.size());
      f.queue.returnUnfinishedClaims(claims);
    }
  }

  private static void dispatchResume(Fixture f, OperationExecutorImpl dispatcher, String key) {
    var state = f.operations.importApprovalState(f.operations.find(key).orElseThrow().id()).orElseThrow();
    var context = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "resume-capacity", Optional.empty(),
        Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.now(CLOCK), Optional.empty());
    var operation = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.RESUME_IMPORT.value()).orElseThrow();
    String arguments = new tools.jackson.databind.ObjectMapper().writeValueAsString(
        Map.of("operationKey", key, "interruptionRevision", state.interruptionRevision()));
    assertTrue(dispatcher.dispatch(operation, arguments, provenance, Optional.empty(), context, OperationKeys.generate(CLOCK)).success());
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void interruptedImportKeepsCapacityUntilProducerAndIssuedClaimsDrain(boolean delayedExit) throws Exception {
    try (Fixture f = new Fixture(temp, 3)) {
      var dispatcher = capacityDispatcher(f);
      var exit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        f.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(
            plan.roots().getFirst().path().resolve("issued.txt"))), null);
        return delayedExit ? exit : CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      String key = dispatchImport(f, dispatcher, f.plan.roots().getFirst().path());
      var issued = f.queue.pollPending(1);
      assertEquals(1, issued.size());
      f.authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
      f.coordinator.maintain();
      assertRetainedImport(f.coordinator, f.operations, key, "RECOVERY_AUTHORIZATION_REFUSED");
      try (var other = f.admission.admit(f.request().context(), false)) {
        assertNotNull(other);
        assertEquals(2, f.admission.activeWorkCount());
        f.authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
        var refused = assertThrows(io.justsearch.app.api.EngineAdmissionException.class,
            () -> dispatchImport(f, dispatcher, f.plan.roots().get(1).path()));
        assertEquals(io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT, refused.reason());
        f.queue.returnUnfinishedClaims(issued);
        f.coordinator.maintain();
        assertEquals(delayedExit ? 2 : 1, f.admission.activeWorkCount());
        exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        f.coordinator.maintain();
        assertEquals(1, f.admission.activeWorkCount(), "drained interruption releases its work reference");
        assertTrue(f.queue.pollPending(1).isEmpty(), "returned claims remain retained and denied");
      }
      assertEquals(0, f.admission.activeWorkCount());
      assertRetainedImport(f.coordinator, f.operations, key, "RECOVERY_AUTHORIZATION_REFUSED");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void overlappingImportCannotTakeRetainedWalkMembers(boolean interrupted) throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var dispatcher = capacityDispatcher(f);
      var firstChild = new AtomicReference<String>();
      var collisions = new AtomicInteger();
      Path root = f.plan.roots().getFirst().path();
      Path file = root.resolve("same.txt");
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        var entries = List.of(JobQueue.EnqueueEntry.ofUnknownSize(file));
        if (firstChild.compareAndSet(null, key)) f.queue.enqueueRecordedEntries(key, epoch, entries, null);
        else {
          var refusal = assertThrows(IllegalStateException.class,
              () -> f.queue.enqueueRecordedEntries(key, epoch, entries, null));
          assertEquals("Path belongs to another active recorded walk", refusal.getMessage());
          collisions.incrementAndGet();
        }
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      String first = dispatchImport(f, dispatcher, root);
      if (interrupted) {
        f.authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
        f.coordinator.maintain();
        assertEquals(0, f.admission.activeWorkCount());
        f.authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
      }
      String second = dispatchImport(f, dispatcher, root);
      assertEquals(1, collisions.get());
      assertEquals(OperationState.COMPLETE, f.operations.find(second).orElseThrow().state());
      assertEquals(1, f.queue.jobStateCounts().pendingCount());
      assertNull(f.queue.recordedWalk(firstChild.get()).orElseThrow().sealedAt());
      assertFalse(f.operations.find(first).orElseThrow().state().terminal());
      if (interrupted) assertRetainedImport(f.coordinator, f.operations, first, "RECOVERY_AUTHORIZATION_REFUSED");
    }
  }

  private static OperationExecutorImpl capacityDispatcher(Fixture f) {
    var handlers = new HandlerRegistry();
    handlers.register(AgentToolsOperationCatalog.INGEST_FILES, new IngestTool(f.coordinator,
        context -> f.plan.roots().stream().map(root -> new RootBinding(root.path(), null)).toList(),
        context -> GENERATION, List::of));
    handlers.register(CoreOperationCatalog.REINDEX, new ReindexHandler(f.coordinator,
        context -> List.of(new RootBinding(f.plan.roots().getLast().path(), null)), context -> GENERATION, List::of));
    handlers.register(CoreOperationCatalog.RESUME_IMPORT,
        new io.justsearch.app.services.registry.operations.handlers.ResumeImportHandler(f.coordinator));
    var dispatcher = new OperationExecutorImpl(f.runner, f.admission, handlers, null, Map.of(),
        CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
    f.authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
    dispatcher.setDurableGrantStore(f.authority.grants(), f.authority.scope());
    return dispatcher;
  }

  private static String dispatchImport(Fixture f, OperationExecutorImpl dispatcher, Path root) {
    var seed = f.mcpRequest();
    var operation = new AgentToolsOperationCatalog().findByIdValue(AgentToolsOperationCatalog.INGEST_FILES.value()).orElseThrow();
    String key = OperationKeys.generate(CLOCK);
    String arguments = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("paths", List.of(root.toString())));
    assertTrue(dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(), seed.context(), key).success());
    return key;
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"auto", "family", "session"})
  void acceptedPartitionedImportsRetainOriginAndApprovalAfterHistoryExpiry(String approval) throws Exception {
    String basis = switch (approval) {
      case "auto" -> new OperationAuthorizationBasis.StructuralAuto().encode();
      case "family" -> new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.TRUSTED).encode();
      case "session" -> new OperationAuthorizationBasis.EphemeralCapsule().encode();
      default -> throw new AssertionError(approval);
    };
    String operationKey;
    Path root;
    Path nested;
    try (Fixture f = new Fixture(temp, 1)) {
      root = f.plan.roots().getFirst().path();
      nested = Files.createDirectory(root.resolve("nested"));
      var plan = RecordedRootPlan.partition(GENERATION, List.of(
          new RecordedRootPlan.Root(root.resolve("unused/.."), null, false, false, List.of("*.tmp", "private/**"), List.of()),
          new RecordedRootPlan.Root(nested, "child", false, false, List.of("*.bak"), List.of())));
      if (approval.equals("family")) f.authority.grants().grantFamilyAllowAlways("file-operations", SourceTier.TRUSTED);
      var request = f.request(OperationAuthorizationBasis.decode(basis));
      operationKey = request.key();
      var prepared = RecordedParentFixture.prepare(request, plan);
      f.runner.savePreparation(request, prepared);
      var accepted = f.runner.acceptPrepared(request, prepared.nonce());
      bindProducer(f.coordinator, (one, key, epoch, context, cancellation) ->
          CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE));
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.coordinator.maintain();
      }
      f.queue.enqueue(List.of(Files.writeString(root.resolve("expired.txt"), "expired queue history")));
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
          var statement = connection.createStatement()) {
        try (var tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE name='source_origin'")) {
          assertTrue(tables.next(), "accepted imports require origin evidence outside expiring operation history");
        }
        try (var rows = statement.executeQuery("SELECT * FROM source_origin ORDER BY normalized_root")) {
          assertTrue(rows.next());
          assertEquals("IMPORT", rows.getString("kind"));
          assertEquals(root.toString(), rows.getString("normalized_root"));
          assertNull(rows.getString("collection"), "retain the accepted unspecified collection");
          var json = new tools.jackson.databind.ObjectMapper();
          assertEquals(json.valueToTree(List.of(nested.toString())), json.readTree(rows.getString("partitions")));
          assertEquals(json.valueToTree(List.of("*.tmp", "private/**")), json.readTree(rows.getString("request_exclusions")));
          assertEquals(basis, rows.getString("authorization_basis"));
          assertEquals("SYSTEM_INTERNAL", rows.getString("recorded_transport"));
          assertEquals("TRUSTED", rows.getString("source_tier"));
          assertEquals("INTERNAL", rows.getString("client_kind"));
          assertEquals("core.ingest-files", rows.getString("producing_operation_id"));
          assertEquals(operationKey, rows.getString("operation_key"));
          assertEquals(approval.equals("session") ? "SESSION" : "DURABLE", rows.getString("approval_class"));
          assertEquals("GATE", rows.getString("evidence_source"));
          assertTrue(rows.next());
          assertEquals(nested.toString(), rows.getString("normalized_root"));
          assertEquals("child", rows.getString("collection"));
          assertEquals(json.valueToTree(List.of("*.bak")), json.readTree(rows.getString("request_exclusions")));
          assertFalse(rows.next(), "one origin per accepted partition");
        }
        statement.executeUpdate("UPDATE jobs SET state='DONE',last_updated=1");
      }
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET completed_at=1,history_pending=0 WHERE completed_at IS NOT NULL");
      }
      f.operations.pruneHistory();
      assertTrue(f.operations.find(operationKey).isEmpty(), "exercise actual operation retention");
      assertEquals(1, f.queue.cleanupOldJobs(0), "exercise actual queue history expiry");
      f.queue.cleanupOldLedgerEvents(0);
    }
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      queue.open();
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT count(*) FROM source_origin")) {
        assertTrue(rows.next()); assertEquals(2, rows.getInt(1), "origin evidence outlives history and reopen");
      }
    }
  }

  @Test
  void retainedChildPermissionRecoversAfterTransientDenialDespiteNewArrivals() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var unreadableGeneration = new AtomicBoolean();
      f.attachment.close();
      f.attachment = f.coordinator.attach(f.queue, () -> {
        if (unreadableGeneration.get()) throw new java.io.IOException("transient generation read");
        return Optional.of(GENERATION);
      }, () -> true);
      Path older = temp.resolve("older");
      var childKey = new AtomicReference<String>();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        childKey.set(key);
        f.queue.enqueueRecordedEntries(key, epoch, List.of(new JobQueue.EnqueueEntry(older, 1)), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.coordinator.maintain();
        assertEquals(JobQueue.RecordedClaimDecision.ALLOW, f.coordinator.recordedClaimDecision(childKey.get()));
        f.queue.enqueue(List.of(temp.resolve("new-0"), temp.resolve("new-1")));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
            var statement = connection.createStatement()) {
          statement.executeUpdate("UPDATE jobs SET last_updated=CASE WHEN scan_id IS NULL THEN 2 ELSE 1 END");
        }
        unreadableGeneration.set(true);
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(childKey.get()));
        assertEquals(temp.resolve("new-0"), f.queue.pollPending(1).getFirst().path());
        unreadableGeneration.set(false);
        f.coordinator.maintain();
        assertEquals(JobQueue.RecordedClaimDecision.ALLOW, f.coordinator.recordedClaimDecision(childKey.get()));
        f.queue.enqueue(List.of(temp.resolve("new-2")));
        var claim = f.queue.pollPending(1).getFirst();
        try {
          assertEquals(older, claim.path(),
              "refreshing retained authority must reconsider the older member on the next poll");
        } finally { f.queue.returnUnfinishedClaims(List.of(claim)); }
      }
    }
  }

  @Test
  void childPermissionGrantInvalidatesClaimsBeforeAttemptingOrphanRecovery() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      f.attachment.close();
      JobQueue observed = org.mockito.Mockito.mock(JobQueue.class,
          org.mockito.AdditionalAnswers.delegatesTo(f.queue));
      f.attachment = f.coordinator.attach(observed, () -> Optional.of(GENERATION), () -> true);
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) ->
          CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE));
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.coordinator.maintain();
        var calls = org.mockito.Mockito.mockingDetails(observed).getInvocations().stream()
            .map(call -> call.getMethod().getName())
            .filter(name -> name.equals("recordedClaimAuthorityChanged") || name.equals("recoverStuckJobs"))
            .toList();
        assertEquals(List.of("recordedClaimAuthorityChanged", "recoverStuckJobs"), calls);
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void dispatchesAcceptedWatchedReindexThroughMembershipFencedProducer(boolean reindex) throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var ingestCalls = new AtomicInteger();
      var watchedCalls = new AtomicInteger();
      RecordedIngestionCoordinator.Producer ingest = (plan, key, epoch, context, cancellation) -> {
        ingestCalls.incrementAndGet();
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      };
      f.coordinator.bindProducer(ingest, ingest, (plan, key, epoch, context, cancellation) -> {
        watchedCalls.incrementAndGet();
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var seed = f.request();
      var request = reindex ? new OperationAttemptRunner.Request(seed.key(),
          OperationDescriptor.invocation(OperationKind.REINDEX, "core.reindex", "{}", false),
          seed.context(), seed.provenance()) : seed;
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.coordinator.maintain();
        assertEquals(reindex ? 0 : 1, ingestCalls.get());
        assertEquals(reindex ? 1 : 0, watchedCalls.get(),
            "a frozen watched-root plan must not reach the unrestricted ingestion adapter");
      }
    }
  }

  @Test
  void servicePublicationResumesAcceptedParentOnceWithoutSpendingAnotherAttempt() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var writerReady = new AtomicBoolean();
      f.attachment.close();
      f.attachment = f.coordinator.attach(f.queue,
          () -> writerReady.get() ? Optional.of(GENERATION) : Optional.empty(), () -> true);
      var producerCalls = new AtomicInteger();
      var producerExit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
      var childKey = new AtomicReference<String>();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        producerCalls.incrementAndGet();
        childKey.set(key);
        assertEquals(1L, epoch, "publication starts the first enumeration epoch");
        return producerExit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.attachment.servicesPublished();
        assertEquals(0, producerCalls.get());
        assertEquals(OperationState.RUNNING, f.operations.find(request.key()).orElseThrow().state());
        var pendingChild = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertEquals(OperationState.RUNNING, pendingChild.state());
        assertTrue(f.queue.recordedWalk(pendingChild.key()).isEmpty(),
            "the accepted child waits without beginning a physical walk");
        writerReady.set(true);
        f.attachment.servicesPublished();
        assertEquals(1, producerCalls.get(), "publication re-drives the accepted owner immediately");
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertEquals(pendingChild.key(), child.key(), "publication resumes the same durable child");
        assertEquals(child.key(), childKey.get());
        assertEquals(1, child.attempts());
        assertEquals(1, f.operations.find(request.key()).orElseThrow().attempts());
        f.attachment.servicesPublished();
        assertEquals(1, producerCalls.get(), "duplicate publication does not replay enumeration");
        producerExit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE);
        f.coordinator.maintain();
        assertEquals(OperationState.COMPLETE, f.operations.find(request.key()).orElseThrow().state());
        var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertEquals(1L, receipt.enumerationEpoch());
      }
    }
  }

  @Test
  void servicePublicationAfterShutdownResumesPreWalkChildOnceAcrossBothStoreReopens() throws Exception {
    OperationRecord originalParent;
    OperationRecord originalChild;
    RecordedRootPlan frozen;
    var producerCalls = new AtomicInteger();
    try (Fixture old = new Fixture(temp, 1)) {
      old.attachment.close();
      old.attachment = old.coordinator.attach(old.queue, Optional::empty, () -> true);
      bindProducer(old.coordinator, (plan, key, epoch, context, cancellation) -> {
        producerCalls.incrementAndGet();
        throw new AssertionError("writer readiness must precede enumeration");
      });
      var request = old.request();
      var accepted = old.accept(request);
      try (var work = old.admission.admit(request.context(), false)) {
        var started = old.runner.start(accepted, handle -> old.coordinator.execute(handle, work.context()));
        old.attachment.servicesPublished();
        originalParent = old.operations.find(request.key()).orElseThrow();
        originalChild = old.operations.findIngestChild(request.key(), old.plan).orElseThrow();
        frozen = old.plan;
        assertEquals(OperationState.RUNNING, originalParent.state());
        assertEquals(OperationState.RUNNING, originalChild.state());
        assertEquals(1, originalParent.attempts());
        assertEquals(1, originalChild.attempts());
        assertTrue(originalChild.checkpointCursor() == null);
        assertTrue(old.queue.recordedWalk(originalChild.key()).isEmpty());
        assertEquals(0, producerCalls.get());
        old.admission.beginClosing();
        old.runner.beginClosing();
        old.coordinator.stopProducers(1_000);
        assertFalse(started.completion().toCompletableFuture().isDone());
      }
      old.attachment.close();
      assertEquals(0, old.admission.activeWorkCount());
      assertTrue(old.runner.awaitDrained(java.time.Duration.ZERO));
      assertEquals(originalParent, old.operations.find(originalParent.key()).orElseThrow());
      assertEquals(originalChild, old.operations.find(originalChild.key()).orElseThrow());
      assertTrue(old.queue.recordedWalk(originalChild.key()).isEmpty());
    }

    var writerReady = new AtomicBoolean();
    var admission = new EngineAdmissionController(2, 2, 1);
    var authority = OperationAuthority.load(temp.resolve("authority"));
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var recovered = new RecordedIngestionCoordinator(operations, runner, admission, authority);
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        queue.open();
        try (var attachment = recovered.attach(queue,
            () -> writerReady.get() ? Optional.of(GENERATION) : Optional.empty(), () -> true)) {
          var producerExit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
          bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
            assertEquals(frozen, plan);
            assertEquals(originalChild.key(), key);
            assertEquals(1L, epoch, "recovery starts the first physical enumeration");
            assertEquals("ingest-progress:1:" + queue.recordedWalk(key).orElseThrow().revision(),
                operations.find(key).orElseThrow().checkpointCursor(),
                "walk-start evidence must be durable before the producer can issue effects");
            assertEquals(1, producerCalls.incrementAndGet());
            return producerExit;
          });
          attachment.servicesPublished();
          assertEquals(originalChild, operations.find(originalChild.key()).orElseThrow(),
              "unready recovery must retain the never-created child without spending an attempt");
          assertEquals(originalParent, operations.find(originalParent.key()).orElseThrow());
          assertTrue(queue.recordedWalk(originalChild.key()).isEmpty());
          writerReady.set(true);
          attachment.servicesPublished();
          attachment.servicesPublished();
          recovered.maintain();
          assertEquals(1, producerCalls.get());
          assertEquals(originalChild.id(), operations.findIngestChild(originalParent.key(), frozen).orElseThrow().id());
          assertEquals(2, operations.find(originalChild.key()).orElseThrow().attempts(), "one budgeted boot resume");
          assertEquals(2, operations.find(originalParent.key()).orElseThrow().attempts(), "one budgeted boot resume");
          producerExit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE);
          recovered.maintain();
          assertEquals(OperationState.COMPLETE, operations.find(originalChild.key()).orElseThrow().state());
          assertEquals(OperationState.COMPLETE, operations.find(originalParent.key()).orElseThrow().state());
          assertEquals(2, operations.find(originalChild.key()).orElseThrow().attempts());
          assertEquals(2, operations.find(originalParent.key()).orElseThrow().attempts());
          var receipt = queue.recordedWalk(originalChild.key()).orElseThrow();
          assertEquals(1L, receipt.enumerationEpoch());
          assertEquals(receipt.revision(), receipt.acknowledgedRevision());
          assertEquals(0, admission.activeWorkCount());
        }
      }
    }

    // A second process recovery trusts the terminal receipt and cannot enumerate again.
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var recovered = new RecordedIngestionCoordinator(operations, runner,
          new EngineAdmissionController(2, 2, 1), authority);
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        queue.open();
        try (var attachment = recovered.attach(queue, () -> Optional.of(GENERATION), () -> true)) {
          bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
            producerCalls.incrementAndGet();
            throw new AssertionError("terminal recovery cannot enumerate again");
          });
          attachment.servicesPublished();
          recovered.maintain();
          assertEquals(1, producerCalls.get());
          assertEquals(OperationState.COMPLETE, operations.find(originalParent.key()).orElseThrow().state());
          assertEquals(OperationState.COMPLETE, operations.find(originalChild.key()).orElseThrow().state());
          assertEquals(2, operations.find(originalParent.key()).orElseThrow().attempts());
          assertEquals(2, operations.find(originalChild.key()).orElseThrow().attempts());
          assertEquals(1L, queue.recordedWalk(originalChild.key()).orElseThrow().enumerationEpoch());
        }
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"missing-checkpointed-walk", "missing-unit-walk", "missing-receipted-walk", "mismatched-plan", "decreased-completed", "decreased-failed"})
  void recoveryCannotRecreateOrEnumerateContradictoryChildEvidence(String fault) throws Exception {
    OperationRecord originalParent;
    OperationRecord originalChild;
    try (Fixture old = new Fixture(temp, 1)) {
      originalParent = old.accept(old.request()).accepted();
      assertTrue(old.operations.start(originalParent.id()));
      originalChild = old.operations.acceptIngestChild(originalParent.key(), OperationKeys.generate(CLOCK),
          old.operations.acceptedPreparation(originalParent.id()).orElseThrow(), old.plan).record();
      assertTrue(old.operations.start(originalChild.id()));
      var walk = old.queue.beginRecordedWalk(originalChild.key(),
          CanonicalOperationArguments.digest(old.plan.toReplayPayload()), true);
      if (fault.equals("missing-unit-walk") || fault.equals("missing-receipted-walk")) {
        Path file = old.plan.roots().getFirst().path().resolve("retained.txt");
        old.queue.enqueueRecordedEntries(originalChild.key(), walk.enumerationEpoch(),
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
        if (fault.equals("missing-receipted-walk")) {
          old.fixtureClaimOwner.set(true);
          var claim = old.queue.pollPending(1).getFirst();
          old.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, "a".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          old.queue.closeRecordedWalkEnumeration(originalChild.key(), walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
          old.queue.trySealRecordedWalk(originalChild.key());
          assertTrue(old.queue.sealedRecordedWalkReceipt(originalChild.key()).isPresent());
          // Leave only receipt evidence, so a guard checking jobs alone cannot pass.
          try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
              var delete = connection.prepareStatement("DELETE FROM jobs WHERE scan_id = ?")) {
            delete.setString(1, originalChild.key());
            assertEquals(1, delete.executeUpdate());
          }
        }
      } else {
        assertTrue(old.operations.checkpoint(originalChild.id(), "ingest-progress:1:" + walk.revision(),
            fault.equals("decreased-completed") ? 1 : 0, fault.equals("decreased-failed") ? 1 : 0));
      }
      if (fault.startsWith("missing-")) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
            var delete = connection.prepareStatement("DELETE FROM ingestion_walk_progress WHERE operation_key = ?")) {
          delete.setString(1, originalChild.key());
          assertEquals(1, delete.executeUpdate());
        }
      } else if (fault.equals("mismatched-plan")) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
            var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET plan_hash = ? WHERE operation_key = ?")) {
          update.setString(1, "b".repeat(64));
          update.setString(2, originalChild.key());
          assertEquals(1, update.executeUpdate());
        }
      }
    }
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var admission = new EngineAdmissionController(2, 2, 1);
      var recovered = new RecordedIngestionCoordinator(operations, runner, admission,
          OperationAuthority.load(temp.resolve("authority")));
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        queue.open();
        try (var attachment = recovered.attach(queue, () -> Optional.of(GENERATION), () -> true)) {
          var producerCalls = new AtomicInteger();
          bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
            producerCalls.incrementAndGet();
            throw new AssertionError("contradictory evidence cannot authorize enumeration");
          });
          attachment.servicesPublished();
          recovered.maintain();
          assertEquals(0, producerCalls.get());
          var child = operations.find(originalChild.key()).orElseThrow();
          var parent = operations.find(originalParent.key()).orElseThrow();
          assertFalse(child.state().terminal());
          assertRetainedImport(recovered, operations, parent.key(), RecordedIngestionSettlement.UNAVAILABLE);
          assertEquals(originalChild.id(), child.id());
          assertEquals(originalParent.id(), parent.id());
          assertEquals(0, admission.activeWorkCount());
          if (fault.startsWith("missing-")) assertTrue(queue.recordedWalk(child.key()).isEmpty());
          else assertEquals(1L, queue.recordedWalk(child.key()).orElseThrow().enumerationEpoch());
          assertEquals(JobQueue.RecordedClaimDecision.DENY, recovered.recordedClaimDecision(child.key()));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptedForegroundParentRequiresLiveOwnerProofForBackgroundInvocation(boolean detached) throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var seed = f.request();
      var context = seed.context().withUrgency(EngineContext.Urgency.FOREGROUND);
      var request = new OperationAttemptRunner.Request(seed.key(), seed.descriptor(), context, seed.provenance());
      var accepted = f.accept(request);
      AtomicInteger producers = new AtomicInteger();
      bindProducer(f.coordinator, (plan, key, epoch, incoming, cancellation) -> {
        producers.incrementAndGet();
        assertEquals(EngineContext.Urgency.BACKGROUND, incoming.urgency());
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      try (var work = f.admission.admit(context, false)) {
        if (detached) work.waitingClientGone();
        var background = work.context().withUrgency(EngineContext.Urgency.BACKGROUND);
        if (detached) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, background));
          assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
              .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
          assertEquals(1, producers.get());
        } else {
          assertThrows(IllegalArgumentException.class,
              () -> f.runner.start(accepted, handle -> f.coordinator.execute(handle, background)));
          assertEquals(0, producers.get(), "A forged scheduling label cannot enter the producer");
        }
        assertEquals(EngineContext.Urgency.FOREGROUND, f.operations.find(seed.key()).orElseThrow().context().urgency());
        assertEquals(1, f.admission.activeWorkCount());
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void freshRootsShareOneWorkAndAdvanceOnlyAfterDurableChildAcknowledgement() throws Exception {
    try (Fixture f = new Fixture(temp, 2)) {
      List<String> children = new ArrayList<>();
      List<EngineContext> contexts = new ArrayList<>();
      CompletableFuture<JobQueue.WalkEnumerationOutcome> firstExit = new CompletableFuture<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        children.add(key); contexts.add(context);
        assertEquals(OperationState.RUNNING, f.operations.find(key).orElseThrow().state());
        if (children.size() == 2) {
          var first = f.queue.recordedWalk(children.getFirst()).orElseThrow();
          assertEquals(first.revision(), first.acknowledgedRevision());
        }
        return children.size() == 1 ? firstExit : CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      OperationAttemptRunner.Result result;
      try (var work = f.admission.admit(request.context(), false)) {
        result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertEquals(1, children.size());
        assertEquals(1, f.admission.activeWorkCount());
        assertFalse(result.completion().toCompletableFuture().isDone());
        firstExit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE);
        f.coordinator.maintain();
        assertEquals(2, children.size());
        assertEquals(work.context().workId(), contexts.getFirst().workId());
        assertEquals(contexts.getFirst().workId(), contexts.getLast().workId());
      }
      f.coordinator.maintain();
      assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).state());
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void openImportKeepsCompletedChildEvidenceThroughRetentionAndRestart() throws Exception {
    String parentKey;
    String firstChild;
    String secondChild;
    RecordedRootPlan frozen;
    int prunedJobs;
    int prunedLedger;
    JobQueue.SealedWalkReceipt completedReceipt;
    try (Fixture f = new Fixture(temp, 2)) {
      frozen = f.plan;
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        Path root = plan.roots().getFirst().path();
        try {
          Path file = Files.writeString(root.resolve("member.txt"), "retained import member");
          f.queue.enqueueRecordedEntries(key, epoch,
              List.of(JobQueue.EnqueueEntry.ofUnknownSize(file).withinRoot(root)), null);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request(); parentKey = request.key();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
      }
      var first = f.queue.pollPending(1).getFirst(); firstChild = first.scanId();
      f.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(first, null, "a".repeat(64))),
          io.justsearch.indexerworker.ingest.IngestionOutcome.of(
              io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
              io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
      f.coordinator.maintain();
      secondChild = f.operations.findIngestChild(parentKey, oneRoot(frozen, 1)).orElseThrow().key();
      assertEquals(OperationState.COMPLETE, f.operations.find(firstChild).orElseThrow().state());
      assertFalse(f.operations.find(parentKey).orElseThrow().state().terminal());
      var receipt = f.queue.sealedRecordedWalkReceipt(firstChild).orElseThrow();
      completedReceipt = receipt;
      assertEquals(receipt.revision(), f.queue.recordedWalk(firstChild).orElseThrow().acknowledgedRevision());
      // Interrupt B before retention runs; cleanup must use the open durable parent, not live work.
      f.coordinator.stopProducers(1000);
      f.attachment.close();
      // Controlled record age also reproduces the defect on the base, before the clock seam exists.
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
          var update = connection.prepareStatement("UPDATE jobs SET last_updated=1 WHERE scan_id=?")) {
        update.setString(1, firstChild); assertEquals(1, update.executeUpdate());
      }
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
          var update = connection.prepareStatement("UPDATE ingestion_ledger SET observed_at=1 WHERE operation_key=?")) {
        update.setString(1, firstChild); assertEquals(1, update.executeUpdate());
      }
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
          var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET sealed_at=1,enumeration_closed_at=1 WHERE operation_key=?")) {
        update.setString(1, firstChild); assertEquals(1, update.executeUpdate());
      }
      prunedJobs = f.queue.cleanupOldJobs(30);
      prunedLedger = f.queue.cleanupOldLedgerEvents(30);
    }
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
          new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(operations, runner,
          new EngineAdmissionController(2, 2, 1), OperationAuthority.load(temp.resolve("authority")));
      try (var queue = queueWithAgedRetentionClock(temp.resolve("jobs.db"), coordinator::recordedClaimDecision)) {
        queue.open();
        try (var _ = coordinator.attach(queue, () -> Optional.of(GENERATION), () -> true)) {
          bindProducer(coordinator, (plan, key, epoch, context, cancellation) -> {
            throw new AssertionError("closed enumeration must drain recorded members without traversal");
          });
          coordinator.maintain();
          var pending = queue.pollPending(10);
          // Let the base reach its actual unavailable-recovery outcome before judging it.
          if (!pending.isEmpty()) {
            assertEquals(1, pending.size()); assertEquals(secondChild, pending.getFirst().scanId());
            queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(pending.getFirst(), null, "b".repeat(64))),
                io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                    io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                    io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          }
          coordinator.maintain();
          var parent = operations.find(parentKey).orElseThrow();
          assertEquals(OperationState.COMPLETE, parent.state(),
              "retention must preserve A's receipt so restart can continue B: " + parent);
          assertEquals(1, pending.size());
          assertEquals(0, prunedJobs, "open parent must retain its completed child's job");
          assertEquals(0, prunedLedger, "open parent must retain its child's ledger");
          assertEquals(completedReceipt, queue.sealedRecordedWalkReceipt(firstChild).orElseThrow(), "exact sealed evidence survives");
          assertEquals(2, parent.unitsCompleted());
          assertEquals(2, queue.cleanupOldJobs(30), "terminal parent releases job retention");
          assertEquals(2, queue.cleanupOldLedgerEvents(30), "terminal parent releases ledger retention");
          assertTrue(queue.recordedWalk(firstChild).isEmpty(), "released receipt can expire");
          assertTrue(queue.recordedWalk(secondChild).isEmpty());
          coordinator.stopProducers(1000);
        }
      }
    }
  }

  @Test
  void synchronousRootsDoNotLoseTheirWakeupOrNeedTheMaintenanceClock() throws Exception {
    try (Fixture f = new Fixture(temp, 9)) {
      AtomicInteger starts = new AtomicInteger();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertEquals(9, starts.get());
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
      }
    }
  }

  @Test
  void cancellationRequestDoesNotFinishBeforeActualEnumerationExit() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      List<String> childKeys = new ArrayList<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> { childKeys.add(key); return exit; });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        work.cancel("user cancelled");
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(childKeys.getFirst()), "cancellation fences claims before maintenance");
        f.coordinator.maintain();
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertEquals(OperationState.RUNNING, f.operations.find(childKeys.getFirst()).orElseThrow().state());
        exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        f.coordinator.maintain();
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).state());
      }
    }
  }

  @Test
  void terminalChildCatchupRequiresNoProducerOrAdmission() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var request = f.request();
      var parent = f.accept(request).accepted();
      f.operations.start(parent.id());
      String childKey = OperationKeys.generate(CLOCK);
      var child = f.operations.acceptIngestChild(parent.key(), childKey,
          f.operations.acceptedPreparation(parent.id()).orElseThrow(), f.plan).record();
      f.operations.start(child.id());
      var progress = f.queue.beginRecordedWalk(childKey, CanonicalOperationArguments.digest(f.plan.toReplayPayload()), true);
      f.queue.closeRecordedWalkEnumeration(childKey, progress.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      f.queue.trySealRecordedWalk(childKey);
      var receipt = f.queue.sealedRecordedWalkReceipt(childKey).orElseThrow();
      f.operations.checkpoint(child.id(), RecordedIngestionReceipt.cursor(receipt), 0, 0);
      f.operations.finish(child.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      f.attachment.close();
      f.admission.freezeAdmission("receipt-only proof");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        coordinator.maintain();
        assertEquals(OperationState.COMPLETE, f.operations.find(parent.key()).orElseThrow().state());
        assertEquals(receipt.revision(), f.queue.recordedWalk(childKey).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @Test
  void indexReplacementKeepsPendingWorkButDropsFreshCapsuleAuthority() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      AtomicInteger starts = new AtomicInteger();
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        return exit;
      });
      var request = f.request(new OperationAuthorizationBasis.EphemeralCapsule());
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.coordinator.stopProducers(1000);
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertTrue(work.cancellationReason().isEmpty());
        f.attachment.close();
        assertFalse(result.completion().toCompletableFuture().isDone());
        f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
        bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
          starts.incrementAndGet(); return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        f.coordinator.maintain();
        assertEquals(1, starts.get());
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertRetainedImport(f.coordinator, f.operations, request.key(), "RECOVERY_AUTHORIZATION_REFUSED");
        assertTrue(work.cancellationReason().isEmpty());
      }
    }
  }

  @Test
  void replacementRevalidatesPersistedParentAndChildInsteadOfCachedAuthority() throws Exception {
    for (String corruption : List.of("parent", "child-payload", "child-attribution")) {
      Path directory = temp.resolve(corruption);
      try (Fixture f = new Fixture(directory, 1)) {
        AtomicInteger starts = new AtomicInteger();
        List<String> children = new ArrayList<>();
        CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
        bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
          starts.incrementAndGet(); children.add(key);
          cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
          return exit;
        });
        var request = f.request();
        var accepted = f.accept(request);
        try (var work = f.admission.admit(request.context(), false)) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          f.coordinator.stopProducers(1000);
          f.attachment.close();
          String key = "parent".equals(corruption) ? request.key() : children.getFirst();
          String column = "child-attribution".equals(corruption) ? "client_id" : "preparation_payload";
          corruptOperation(directory.resolve("operations.db"), key, column, "changed-binding");
          f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
          bindProducer(f.coordinator, (root, childKey, epoch, context, cancellation) -> {
            starts.incrementAndGet(); return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          assertFalse(result.completion().toCompletableFuture().isDone(), corruption);
          assertRetainedImport(f.coordinator, f.operations, request.key(), RecordedIngestionSettlement.UNAVAILABLE);
          assertEquals(1, starts.get(), "no replacement effect from cached binding: " + corruption);
          assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(children.getFirst()));
          assertEquals(0, f.queue.recordedWalk(children.getFirst()).orElseThrow().acknowledgedRevision());
          assertTrue(work.cancellationReason().isEmpty());
        }
      }
    }
  }

  @Test
  void neverStartedProducerSurvivesReplacementInTheSameAttemptAndWork() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertTrue(f.queue.recordedWalk(child.key()).isEmpty());
        f.coordinator.stopProducers(1000);
        f.attachment.close();
        assertFalse(result.completion().toCompletableFuture().isDone());
        f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
        bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
          assertEquals(work.context().workId(), context.workId());
          return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        assertEquals(1, f.operations.find(request.key()).orElseThrow().attempts());
        assertEquals(1, f.operations.find(child.key()).orElseThrow().attempts());
      }
    }
  }

  private static void corruptOperation(Path database, String key, String column, String value) throws Exception {
    if (!Set.of("client_id", "preparation_payload").contains(column)) throw new IllegalArgumentException("fixture column");
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        var update = connection.prepareStatement("UPDATE operations SET " + column + " = ? WHERE operation_key = ?")) {
      update.setString(1, value); update.setString(2, key);
      assertEquals(1, update.executeUpdate());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void bootFailedChildRepairsMissingOrStaleParentCheckpointBeforeFailure(
      boolean staleParentCheckpoint) throws Exception {
    Path directory = temp.resolve(staleParentCheckpoint ? "failed-parent-stale" : "failed-parent-missing");
    try (Fixture f = new Fixture(directory, 1)) {
      SeededChild seeded = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.FAILED);
      if (staleParentCheckpoint) {
        assertTrue(f.operations.checkpoint(seeded.parent().id(), "stale-parent-cursor", 0, 0));
      }
      assertEquals(0, f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());

      RecordedIngestionCoordinator coordinator = restartForReceiptOnly(f);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        coordinator.maintain();

        OperationRecord parent = f.operations.find(seeded.parent().key()).orElseThrow();
        assertEquals(OperationState.FAILED, parent.state());
        assertEquals(new OperationReceipt("INGEST_ENUMERATION_FAILED", null), parent.receipt());
        assertEquals("ingest-parent:1:1", parent.checkpointCursor());
        assertEquals(seeded.receipt().completedUnits(), parent.unitsCompleted());
        assertEquals(seeded.receipt().failedUnits(), parent.unitsFailed());
        assertEquals(1, parent.attempts(), "I10: durable receipt bookkeeping executes no new attempt");
        assertEquals(seeded.receipt().revision(),
            f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount(), "receipt bookkeeping needs no work slot");
      }
    }
  }

  @Test
  void bootCancelledChildRepairsParentAndPropagatesCancellationWithoutAdmission() throws Exception {
    try (Fixture f = new Fixture(temp.resolve("cancelled-parent"), 1)) {
      SeededChild seeded = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.CANCELLED);
      assertEquals(0, f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());

      RecordedIngestionCoordinator coordinator = restartForReceiptOnly(f);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        coordinator.maintain();

        OperationRecord parent = f.operations.find(seeded.parent().key()).orElseThrow();
        assertEquals(OperationState.CANCELLED, parent.state());
        assertEquals(new OperationReceipt("cancelled", null), parent.receipt());
        assertEquals("ingest-parent:1:1", parent.checkpointCursor());
        assertEquals(seeded.receipt().completedUnits(), parent.unitsCompleted());
        assertEquals(seeded.receipt().failedUnits(), parent.unitsFailed());
        assertEquals(1, parent.attempts(), "I10: durable receipt bookkeeping executes no new attempt");
        assertEquals(seeded.receipt().revision(),
            f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount(), "receipt bookkeeping needs no work slot");
      }
    }
  }

  @Test
  void bootParentWithMoreConfirmedUnitsThanChildFailsUnavailableAndPreservesCheckpoint() throws Exception {
    try (Fixture f = new Fixture(temp.resolve("parent-confirmed-ahead"), 1)) {
      SeededChild seeded = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.FAILED);
      assertTrue(f.operations.checkpoint(seeded.parent().id(), "already-confirmed-parent", 1, 0));
      assertEquals(0, f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());

      RecordedIngestionCoordinator coordinator = restartForReceiptOnly(f);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        coordinator.maintain();

        OperationRecord parent = f.operations.find(seeded.parent().key()).orElseThrow();
        assertRetainedImport(coordinator, f.operations, parent.key(), RecordedIngestionSettlement.UNAVAILABLE);
        assertEquals("already-confirmed-parent", parent.checkpointCursor());
        assertEquals(1, parent.unitsCompleted());
        assertEquals(0, parent.unitsFailed());
        assertEquals(1, parent.attempts(), "contradictory progress refuses without a new resume");
        assertEquals(seeded.receipt().revision(),
            f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount(), "receipt bookkeeping needs no work slot");
      }
    }
  }

  private static SeededChild seedTerminalChild(Fixture f,
      JobQueue.WalkEnumerationOutcome outcome) {
    OperationRecord parent = f.accept(f.request()).accepted();
    assertTrue(f.operations.start(parent.id()));
    String childKey = OperationKeys.generate(CLOCK);
    RecordedRootPlan childPlan = oneRoot(f.plan, 0);
    OperationRecord child = f.operations.acceptIngestChild(parent.key(), childKey,
        f.operations.acceptedPreparation(parent.id()).orElseThrow(), childPlan).record();
    assertTrue(f.operations.start(child.id()));

    var walk = f.queue.beginRecordedWalk(childKey,
        CanonicalOperationArguments.digest(childPlan.toReplayPayload()), true);
    f.queue.closeRecordedWalkEnumeration(childKey, walk.enumerationEpoch(), outcome);
    f.queue.trySealRecordedWalk(childKey);
    JobQueue.SealedWalkReceipt receipt = f.queue.sealedRecordedWalkReceipt(childKey).orElseThrow();
    assertTrue(f.operations.checkpoint(child.id(), RecordedIngestionReceipt.cursor(receipt),
        receipt.completedUnits(), receipt.failedUnits()));
    assertTrue(f.operations.finish(child.id(), RecordedIngestionReceipt.terminalState(receipt),
        RecordedIngestionReceipt.terminalReceipt(receipt)).isPresent());
    assertEquals(0, f.queue.recordedWalk(childKey).orElseThrow().acknowledgedRevision());
    return new SeededChild(parent, child, receipt);
  }

  private static RecordedRootPlan oneRoot(RecordedRootPlan plan, int index) {
    return new RecordedRootPlan(plan.generation(), List.of(plan.roots().get(index)));
  }

  private static RecordedIngestionCoordinator restartForReceiptOnly(Fixture f) throws Exception {
    f.attachment.close();
    f.admission.freezeAdmission("receipt-only parent outcome recovery");
    var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null,
        new RecordedIngestPlanResolver());
    return new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
  }

  private record SeededChild(OperationRecord parent, OperationRecord child,
      JobQueue.SealedWalkReceipt receipt) {}

  @Test
  void cancellationFencesPendingClaimsAndParentWaitsForTheExactIssuedOwner() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      List<String> children = new ArrayList<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        children.add(key);
        f.queue.enqueueRecordedEntries(key, epoch, List.of(
            JobQueue.EnqueueEntry.ofUnknownSize(root.roots().getFirst().path().resolve("first.txt")),
            JobQueue.EnqueueEntry.ofUnknownSize(root.roots().getFirst().path().resolve("second.txt"))), null);
        return exit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var issued = f.queue.pollPending(1);
        assertEquals(1, issued.size());
        work.cancel("cancel with actual queue owner");
        assertTrue(f.queue.pollPending(1).isEmpty(), "pending second member is denied before maintenance");
        f.coordinator.maintain();
        exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        assertTrue(f.queue.hasIssuedRecordedClaims(children.getFirst()));
        assertFalse(result.completion().toCompletableFuture().isDone());
        f.queue.returnUnfinishedClaims(issued);
        assertFalse(f.queue.hasIssuedRecordedClaims(children.getFirst()));
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var receipt = f.queue.recordedWalk(children.getFirst()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
      }
    }
  }

  @Test
  void deferredAdmissionIsBoundedAndThawContinuesWithoutFailingAcceptedWork() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      List<OperationRecord> accepted = new ArrayList<>();
      for (int index = 0; index < 3; index++) accepted.add(f.accept(f.request()).accepted());
      f.attachment.close();
      var freeze = f.admission.freezeAdmission("hold ingestion activation");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        assertEquals(1, f.operations.find(accepted.get(0).key()).orElseThrow().attempts());
        assertEquals(1, f.operations.find(accepted.get(1).key()).orElseThrow().attempts());
        assertEquals(0, f.operations.find(accepted.get(2).key()).orElseThrow().attempts(), "bounded third parent remains Wait");
        assertEquals(0, f.admission.activeWorkCount());
        AtomicInteger effects = new AtomicInteger();
        bindProducer(coordinator, (root, key, epoch, context, cancellation) -> {
          effects.incrementAndGet();
          return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        assertEquals(0, effects.get());
        f.admission.releaseAdmission(freeze.preparationId());
        coordinator.maintain();
        assertEquals(3, effects.get());
        for (OperationRecord row : accepted) assertEquals(OperationState.COMPLETE, f.operations.find(row.key()).orElseThrow().state());
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @Test
  void malformedParentCannotAcknowledgeHiddenTerminalChildOrOutrunIssuedSibling() throws Exception {
    try (Fixture f = new Fixture(temp, 2)) {
      SeededChild first = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertTrue(f.operations.checkpoint(first.parent().id(), "last-confirmed-parent", 4, 1));
      RecordedRootPlan secondPlan = oneRoot(f.plan, 1);
      String secondKey = OperationKeys.generate(CLOCK);
      var second = f.operations.acceptIngestChild(first.parent().key(), secondKey,
          f.operations.acceptedPreparation(first.parent().id()).orElseThrow(), secondPlan).record();
      f.operations.start(second.id());
      var walk = f.queue.beginRecordedWalk(secondKey, CanonicalOperationArguments.digest(secondPlan.toReplayPayload()), true);
      f.queue.enqueueRecordedEntries(secondKey, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(temp.resolve("issued-sibling.txt"))), null);
      f.fixtureClaimOwner.set(true);
      var issued = f.queue.pollPending(1);
      f.fixtureClaimOwner.set(false);
      assertEquals(1, issued.size());
      assertTrue(f.queue.hasIssuedRecordedClaims(secondKey));
      f.attachment.close();
      corruptOperation(temp.resolve("operations.db"), first.parent().key(), "preparation_payload", "lost-parent-binding");
      assertFalse(f.operations.openRecords().stream().anyMatch(row -> row.key().equals(first.child().key())),
          "terminal unacknowledged child is absent from the captured recovery cohort");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        assertEquals(OperationState.RUNNING, f.operations.find(first.parent().key()).orElseThrow().state());
        assertEquals(OperationState.RUNNING, f.operations.find(secondKey).orElseThrow().state());
        assertEquals(JobQueue.RecordedClaimDecision.DENY, coordinator.recordedClaimDecision(secondKey));
        f.queue.returnUnfinishedClaims(issued);
        coordinator.maintain();
        var parent = f.operations.find(first.parent().key()).orElseThrow();
        assertRetainedImport(coordinator, f.operations, parent.key(), "RECOVERY_BINDING_INVALID");
        assertEquals("last-confirmed-parent", parent.checkpointCursor());
        assertEquals(4, parent.unitsCompleted()); assertEquals(1, parent.unitsFailed());
        assertEquals(OperationState.FAILED, f.operations.find(secondKey).orElseThrow().state());
        assertEquals(0, f.queue.recordedWalk(first.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.queue.recordedWalk(secondKey).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @Test
  void contradictoryTerminalChildDoesNotPreventLaterSiblingSettlementAndAcknowledgement() throws Exception {
    try (Fixture f = new Fixture(temp, 2)) {
      SeededChild first = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.COMPLETE);
      RecordedRootPlan secondPlan = oneRoot(f.plan, 1);
      String secondKey = OperationKeys.generate(CLOCK);
      var second = f.operations.acceptIngestChild(first.parent().key(), secondKey,
          f.operations.acceptedPreparation(first.parent().id()).orElseThrow(), secondPlan).record();
      f.operations.start(second.id());
      f.queue.beginRecordedWalk(secondKey, CanonicalOperationArguments.digest(secondPlan.toReplayPayload()), true);
      f.attachment.close();
      corruptOperation(temp.resolve("operations.db"), first.child().key(), "client_id", "wrong-child-attribution");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      List<String> terminals = new ArrayList<>();
      var subscription = f.operations.subscribeCompletions(row -> terminals.add(row.key()));
      try (subscription; var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        coordinator.maintain();
        assertEquals(List.of(secondKey, first.parent().key()), terminals);
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(first.parent().key()).orElseThrow().receipt().code());
        assertEquals(0, f.queue.recordedWalk(first.child().key()).orElseThrow().acknowledgedRevision());
        var receipt = f.queue.recordedWalk(secondKey).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void finalAttachmentFlushSealsCheckpointsAndCompletesAfterActualIndexDrain(boolean exitsDuringStop) throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      List<String> children = new ArrayList<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        children.add(key);
        f.queue.enqueueRecordedEntries(key, epoch,
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(root.roots().getFirst().path().resolve("last.txt"))), null);
        CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
        if (exitsDuringStop) cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE));
        else exit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE);
        return exit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var issued = f.queue.pollPending(1).getFirst();
        f.coordinator.stopProducers(1000);
        assertThrows(java.io.IOException.class, f.attachment::close,
            "failed final close retains the attachment while an issued index owner is live");
        f.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(issued, null, "a".repeat(64))),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        assertFalse(f.queue.hasIssuedRecordedClaims(children.getFirst()));
        assertFalse(result.completion().toCompletableFuture().isDone(), "normal maintenance has already stopped");
        f.attachment.close();
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var receipt = f.queue.recordedWalk(children.getFirst()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertEquals(1, f.operations.find(request.key()).orElseThrow().unitsCompleted());
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void cancellationBeforeFirstEnumerationPersistsAnEmptyCancelledReceipt() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertTrue(f.queue.recordedWalk(child.key()).isEmpty());
        work.cancel("cancel before any producer exists");
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(child.key()));
        f.coordinator.maintain();
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var terminal = f.operations.find(child.key()).orElseThrow();
        assertEquals(OperationState.CANCELLED, terminal.state());
        assertEquals(0, terminal.unitsCompleted());
        assertEquals(0, terminal.unitsFailed());
        var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertTrue(f.queue.pollPending(1).isEmpty());
        AtomicInteger starts = new AtomicInteger();
        bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
          starts.incrementAndGet(); return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        assertEquals(0, starts.get(), "later producer binding cannot resurrect cancelled work");
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void cancellationBetweenAuthorizationAndTokenPublicationCannotStartTheProducer() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      f.attachment.close();
      JobQueue bridge = org.mockito.Mockito.mock(JobQueue.class,
          org.mockito.AdditionalAnswers.delegatesTo(f.queue));
      f.attachment = f.coordinator.attach(bridge, () -> Optional.of(GENERATION), () -> true);
      var request = f.request();
      var accepted = f.accept(request);
      AtomicInteger starts = new AtomicInteger();
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        return exit;
      });
      try (var work = f.admission.admit(request.context(), false)) {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Void> cancellationResult = new CompletableFuture<>();
        Thread canceller = Thread.ofPlatform().start(() -> {
          try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            work.cancel("cancel before producer token publication");
            cancellationResult.complete(null);
          } catch (Throwable failure) { cancellationResult.completeExceptionally(failure); }
          finally { cancelled.countDown(); }
        });
        org.mockito.Mockito.doAnswer(invocation -> {
          var progress = f.queue.beginRecordedWalk(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
          entered.countDown();
          assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS), "cancellation must not wait for coordinator lock");
          cancellationResult.join();
          return progress;
        }).when(bridge).beginRecordedWalk(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean());
        try {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          assertEquals(0, starts.get(), "cancelled context cannot start a producer after token publication");
          f.coordinator.maintain();
          assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
              .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
          var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
          assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(child.key()));
          var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
          assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        } finally {
          exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
          entered.countDown();
          canceller.join(3000);
          assertFalse(canceller.isAlive());
        }
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void freshOriginWriteFailureRetainsTheImportAndAcceptedChild() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var starts = new AtomicInteger();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
          var statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TRIGGER refuse_origin BEFORE INSERT ON source_origin BEGIN SELECT RAISE(FAIL,'origin unavailable'); END");
      }
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = assertDoesNotThrow(
            () -> f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context())));
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertEquals(OperationState.RUNNING, f.operations.find(request.key()).orElseThrow().state());
        assertTrue(f.operations.findIngestChild(request.key(), f.plan).isPresent());
        assertEquals(0, starts.get());
        var interruption = f.coordinator.interruptedImports().getFirst();
        assertEquals(request.key(), interruption.operationKey());
        assertTrue(interruption.resumeAvailable());
        assertEquals("RECOVERY_ORIGIN_UNAVAILABLE", interruption.reason());
      }
    }
  }

  @Test
  void synchronousProducerRejectionSettlesChildBeforeParentFailure() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        throw new java.util.concurrent.RejectedExecutionException("bounded producer queue full");
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertEquals(OperationState.FAILED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertEquals(OperationState.FAILED, child.state(), "producer rejection must not orphan the accepted child");
        assertEquals("INGEST_ENUMERATION_FAILED", child.receipt().code());
        var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertEquals("INGEST_ENUMERATION_FAILED", f.operations.find(request.key()).orElseThrow().receipt().code());
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(child.key()));
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void cancellationCannotRecreateProgressAfterAnEnumerationHasStarted() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      bindProducer(f.coordinator, (root, key, epoch, context, cancellation) -> {
        cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        return exit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertTrue(f.queue.recordedWalk(child.key()).isPresent());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
            var delete = connection.prepareStatement("DELETE FROM ingestion_walk_progress WHERE operation_key = ?")) {
          delete.setString(1, child.key());
          assertEquals(1, delete.executeUpdate());
        }
        work.cancel("cancel after recorded progress was lost");
        f.coordinator.maintain();
        assertEquals(OperationState.FAILED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(request.key()).orElseThrow().receipt().code());
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(child.key()).orElseThrow().receipt().code());
        assertTrue(f.queue.recordedWalk(child.key()).isEmpty(), "lost evidence cannot be rebuilt as an empty cancellation");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reopenedQueueClaimsForceOnlyFromTheRecoveredPersistedParentPlan(boolean force) throws Exception {
    try (Fixture f = new Fixture(temp, 1, force)) {
      var parent = f.accept(f.request()).accepted();
      assertTrue(f.operations.start(parent.id()));
      String childKey = OperationKeys.generate(CLOCK);
      var child = f.operations.acceptIngestChild(parent.key(), childKey,
          f.operations.acceptedPreparation(parent.id()).orElseThrow(), f.plan).record();
      assertTrue(f.operations.start(child.id()));
      Path file = f.plan.roots().getFirst().path().resolve("recorded.txt");
      var walk = f.queue.beginRecordedWalk(childKey, CanonicalOperationArguments.digest(f.plan.toReplayPayload()), true);
      f.queue.enqueueRecordedEntries(childKey, walk.enumerationEpoch(), List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      f.attachment.close();
      f.queue.close();
      var recoveredRunner = new OperationAttemptRunnerImpl(f.operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null, new RecordedIngestPlanResolver());
      var recovered = new RecordedIngestionCoordinator(f.operations, recoveredRunner, f.admission, f.authority);
      try (var reopened = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        reopened.open();
        assertTrue(reopened.pollPending(1).isEmpty(), "reopen does not remember the old runtime permission");
        try (var attachment = recovered.attach(reopened, () -> Optional.of(GENERATION), () -> true)) {
          assertNotNull(attachment);
          bindProducer(recovered, (root, key, epoch, context, cancellation) -> {
            assertEquals(force, root.roots().getFirst().force(), "producer receives the persisted plan, not a new public flag");
            reopened.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
            return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          var claim = reopened.pollPending(1).getFirst();
          assertEquals(force, claim.recordedForce());
          assertEquals(childKey, claim.scanId());
          assertEquals(1, f.admission.activeWorkCount(), "recovered children share the admitted parent work");
          reopened.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, "d".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          recovered.maintain();
          assertEquals(OperationState.COMPLETE, f.operations.find(parent.key()).orElseThrow().state());
          assertEquals(0, f.admission.activeWorkCount());
          var receipt = reopened.recordedWalk(childKey).orElseThrow();
          assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        }
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void bothStoresReopenUnderNewOwnersAndResumeTheOriginalPreparedOperation(
      boolean reindex, boolean changedGeneration) throws Exception {
    OperationRecord originalParent;
    OperationRecord originalChild;
    io.justsearch.app.api.operations.OperationStore.Preparation originalPreparation;
    io.justsearch.app.api.operations.OperationStore.Preparation originalChildPreparation;
    RecordedRootPlan frozen;
    Path file;
    var preparations = new AtomicInteger();
    var oldExited = new AtomicBoolean();
    try (Fixture old = new Fixture(temp, 1)) {
      Path root = old.plan.roots().getFirst().path();
      file = Files.writeString(root.resolve("resume.txt"), "persisted producer epoch");
      var handlers = new HandlerRegistry();
      var operation = reindex ? new CoreOperationCatalog().findById(CoreOperationCatalog.REINDEX).orElseThrow()
          : new AgentToolsOperationCatalog().findById(AgentToolsOperationCatalog.INGEST_FILES).orElseThrow();
      if (reindex) {
        handlers.register(operation.id(), new ReindexHandler(old.coordinator, context -> {
          preparations.incrementAndGet(); return List.of(new RootBinding(root, "documents"));
        }, context -> GENERATION, List::of));
      } else {
        handlers.register(operation.id(), new IngestTool(old.coordinator, context -> {
          preparations.incrementAndGet(); return List.of(new RootBinding(root, "documents"));
        }, context -> GENERATION, List::of));
      }
      var dispatcher = new OperationExecutorImpl(old.runner, old.admission, handlers, null, Map.of(),
          CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
      var exit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
      var childKey = new AtomicReference<String>();
      bindProducer(old.coordinator, (plan, key, epoch, context, cancellation) -> {
        assertTrue(childKey.compareAndSet(null, key), "one producer before shutdown");
        old.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
        cancellation.onCancel(() -> {
          oldExited.set(true);
          exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        });
        return exit;
      });
      var seed = old.request();
      String arguments = reindex ? "{\"force\":true}"
          : tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(root.toString()), "collection", "documents"));
      var result = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(), seed.context(), seed.key());
      assertTrue(result.success());
      assertEquals(1, preparations.get());
      old.coordinator.stopProducers(1000);
      assertTrue(oldExited.get(), "actual producer completion precedes either store close");
      assertTrue(exit.isDone());
      originalParent = old.operations.find(seed.key()).orElseThrow();
      originalChild = old.operations.find(childKey.get()).orElseThrow();
      originalPreparation = old.operations.acceptedPreparation(originalParent.id()).orElseThrow();
      originalChildPreparation = old.operations.acceptedPreparation(originalChild.id()).orElseThrow();
      frozen = new RecordedIngestPlanResolver().resolve(originalParent, originalPreparation);
      assertEquals(OperationState.RUNNING, originalParent.state());
      assertEquals(OperationState.RUNNING, originalChild.state());
      assertTrue(old.queue.recordedWalk(originalChild.key()).orElseThrow().enumerationClosedAt() == null);
      // Fixture close retires attachment, queue and operation store, in that order.
      // Its abandoned volatile admission graph is not the successor's authority.
    }

    var admission = new EngineAdmissionController(2, 2, 1);
    var authority = OperationAuthority.load(temp.resolve("authority"));
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var recovered = new RecordedIngestionCoordinator(operations, runner, admission, authority);
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        queue.open();
        assertTrue(queue.pollPending(1).isEmpty(), "persisted rows alone grant no producer permission");
        String servingGeneration = changedGeneration ? "replacement-generation" : GENERATION;
        try (var attachment = recovered.attach(queue, () -> Optional.of(servingGeneration), () -> true)) {
          assertNotNull(attachment);
          var resumed = new AtomicInteger();
          if (changedGeneration) {
            var successorProducers = new AtomicInteger();
            bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
              successorProducers.incrementAndGet();
              throw new AssertionError("changed-generation recovery must not invoke a successor producer");
            });
            recovered.maintain();
            var parent = operations.find(originalParent.key()).orElseThrow();
            var child = operations.find(originalChild.key()).orElseThrow();
            if (!reindex) {
              assertRetainedImport(recovered, operations, parent.key(), "RECOVERY_GENERATION_MISMATCH");
              assertFalse(child.state().terminal());
              assertEquals(originalParent.id(), parent.id());
              assertEquals(originalChild.id(), child.id());
              assertEquals(originalPreparation, operations.acceptedPreparation(parent.id()).orElseThrow());
              assertEquals(originalChildPreparation, operations.acceptedPreparation(child.id()).orElseThrow());
              assertEquals(1, preparations.get());
              assertEquals(0, successorProducers.get());
              assertEquals(1, queue.jobStateCounts().pendingCount());
              assertTrue(queue.pollPending(1).isEmpty());
              assertNull(queue.recordedWalk(child.key()).orElseThrow().enumerationClosedAt());
              assertEquals(0, admission.activeWorkCount());
              return;
            }
            assertEquals(OperationState.FAILED, parent.state());
            assertEquals("RECOVERY_GENERATION_MISMATCH", parent.receipt().code());
            assertEquals("ingest-refusal:1:RECOVERY_GENERATION_MISMATCH", parent.checkpointCursor());
            assertEquals(originalParent.id(), parent.id());
            assertEquals(originalChild.id(), child.id());
            assertEquals(originalParent.context(), parent.context());
            assertEquals(originalChild.context(), child.context());
            assertEquals(originalPreparation, operations.acceptedPreparation(parent.id()).orElseThrow());
            assertEquals(originalChildPreparation, operations.acceptedPreparation(child.id()).orElseThrow());
            assertEquals(1, preparations.get(), "recovery must not resample either handler preparation");
            assertEquals(0, successorProducers.get());
            assertEquals(OperationState.FAILED, child.state());
            assertEquals("INGEST_ENUMERATION_FAILED", child.receipt().code());
            assertEquals(0, parent.unitsCompleted());
            assertEquals(0, parent.unitsFailed());
            assertEquals(0, child.unitsCompleted());
            assertEquals(0, child.unitsFailed());
            var jobCounts = queue.jobStateCounts();
            assertEquals(0, jobCounts.pendingCount(), "refused recovery must not leave successor work pending");
            assertEquals(0, jobCounts.processingCount(), "refused recovery must not leave an issued job in flight");
            assertEquals(1, jobCounts.doneCount(), "the old enumerated member is retired without indexing it");
            assertEquals(0, jobCounts.failedCount());
            assertFalse(queue.hasIssuedRecordedClaims(child.key()), "refused recovery must not issue claims");
            assertEquals(0, admission.activeWorkCount());
            var progress = queue.recordedWalk(child.key()).orElseThrow();
            assertEquals(JobQueue.WalkEnumerationOutcome.FAILED, progress.enumerationOutcome(),
                "the cancelled old producer is closed as failed enumeration, not rewritten as complete");
            var receipt = queue.sealedRecordedWalkReceipt(child.key()).orElseThrow();
            assertEquals(JobQueue.WalkEnumerationOutcome.FAILED, receipt.enumerationOutcome());
            assertEquals(0, receipt.completedUnits(), "enumeration refusal cannot claim indexed work");
            assertEquals(0, receipt.failedUnits());
            assertEquals(receipt.revision(), progress.acknowledgedRevision(),
                "terminal child evidence is acknowledged only after the mismatch outcome is durable");
            return;
          }
          bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
            assertTrue(oldExited.get());
            assertEquals(1, resumed.incrementAndGet());
            assertEquals(frozen, plan);
            assertEquals(originalChild.key(), key);
            assertEquals(EngineContext.Survival.DURABLE, context.survival());
            assertEquals(1, admission.activeWorkCount());
            queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
            return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          recovered.maintain();
          recovered.maintain();
          assertEquals(1, resumed.get(), "winning Resume bodies execute once");
          assertEquals(1, preparations.get(), "recovery uses stored preparation without sampling handlers");
          assertEquals(originalPreparation, operations.acceptedPreparation(originalParent.id()).orElseThrow());
          assertEquals(originalChildPreparation, operations.acceptedPreparation(originalChild.id()).orElseThrow());
          var parent = operations.find(originalParent.key()).orElseThrow();
          var child = operations.find(originalChild.key()).orElseThrow();
          assertEquals(originalParent.id(), parent.id());
          assertEquals(originalChild.id(), child.id());
          assertEquals(originalParent.context(), parent.context());
          assertEquals(originalChild.context(), child.context());
          assertEquals(originalParent.attempts() + 1, parent.attempts());
          assertEquals(originalChild.attempts() + 1, child.attempts());
          assertEquals(OperationState.RUNNING, parent.state(), "enumeration alone cannot complete unsettled units");
          assertEquals(OperationState.RUNNING, child.state());
          var claim = queue.pollPending(1).getFirst();
          assertEquals(originalChild.key(), claim.scanId());
          assertEquals(reindex, claim.recordedForce());
          queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, "d".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          recovered.maintain();
          assertEquals(OperationState.COMPLETE, operations.find(originalParent.key()).orElseThrow().state());
          assertEquals(OperationState.COMPLETE, operations.find(originalChild.key()).orElseThrow().state());
          assertEquals(1, operations.find(originalParent.key()).orElseThrow().unitsCompleted());
          var receipt = queue.recordedWalk(originalChild.key()).orElseThrow();
          assertNotNull(receipt.sealedAt());
          assertEquals(receipt.revision(), receipt.acknowledgedRevision());
          assertEquals(0, admission.activeWorkCount());
        }
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"none", "before-marker", "after-marker", "after-retirement", "after-terminal", "exhausted"})
  void refusedRecoveryRetainsUnsettledUnitsAfterEnumerationAlreadyCompleted(String cut) throws Exception {
    String parentKey;
    String childKey;
    try (Fixture old = new Fixture(temp, 1)) {
      var child = new AtomicReference<String>();
      bindProducer(old.coordinator, (plan, key, epoch, context, cancellation) -> {
        child.set(key);
        old.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(
            plan.roots().getFirst().path().resolve("pending.txt"))), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = old.request();
      parentKey = request.key();
      var accepted = old.accept(request);
      try (var work = old.admission.admit(request.context(), false)) {
        old.runner.start(accepted, handle -> old.coordinator.execute(handle, work.context()));
      }
      childKey = child.get();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, old.queue.recordedWalk(childKey).orElseThrow().enumerationOutcome());
      if ("exhausted".equals(cut)) {
        for (String key : List.of(parentKey, childKey)) {
          long id = old.operations.find(key).orElseThrow().id();
          assertTrue(old.operations.resume(id)); assertTrue(old.operations.resume(id));
        }
      } else if (!"none".equals(cut) && !"before-marker".equals(cut)) {
        // These old cut names once depended on retirement/terminalization. I8 prohibits both;
        // persist the interruption and prove repeated recovery keeps every member instead.
        old.operations.interruptImport(accepted.accepted().id(), "RECOVERY_GENERATION_MISMATCH");
      }
    }
    if ("before-marker".equals(cut)) {
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
          var statement = connection.createStatement()) {
        statement.executeUpdate("CREATE TRIGGER refuse_latch BEFORE UPDATE OF interruption_reason ON operations BEGIN SELECT RAISE(FAIL,'latch unavailable'); END");
      }
    }
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null, new RecordedIngestPlanResolver());
      var admission = new EngineAdmissionController(2, 2, 1);
      var recovered = new RecordedIngestionCoordinator(operations, runner, admission,
          OperationAuthority.load(temp.resolve("authority")));
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        queue.open();
        String generation = "none".equals(cut) || "before-marker".equals(cut) ? "different-generation" : GENERATION;
        try (var attachment = recovered.attach(queue, () -> Optional.of(generation), () -> true)) {
          assertNotNull(attachment);
          bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
            throw new AssertionError("interrupted recovery cannot enumerate");
          });
          recovered.maintain(); recovered.maintain();
          String reason = "exhausted".equals(cut) ? RecordedIngestionSettlement.EXHAUSTED : "RECOVERY_GENERATION_MISMATCH";
          assertRetainedImport(recovered, operations, parentKey, reason);
          assertFalse(operations.find(childKey).orElseThrow().state().terminal());
          assertEquals(1, queue.jobStateCounts().pendingCount());
          assertTrue(queue.pollPending(1).isEmpty());
          assertNull(queue.recordedWalk(childKey).orElseThrow().sealedAt());
          assertEquals(0, queue.recordedWalk(childKey).orElseThrow().acknowledgedRevision());
          assertEquals(0, admission.activeWorkCount());
        }
      }
    }
  }

  @Test
  void liveRefusalSurvivesAuthorityRestorationAndCountCheckpointsAfterAnIssuedClaimDrains() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var generation = new AtomicReference<>(GENERATION);
      f.attachment.close();
      f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(generation.get()), () -> true);
      var childKey = new AtomicReference<String>();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        childKey.set(key);
        Path root = plan.roots().getFirst().path();
        f.queue.enqueueRecordedEntries(key, epoch, List.of(
            JobQueue.EnqueueEntry.ofUnknownSize(root.resolve("issued.txt")),
            JobQueue.EnqueueEntry.ofUnknownSize(root.resolve("pending.txt"))), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var issued = f.queue.pollPending(1).getFirst();
        generation.set("replacement-generation");
        f.coordinator.maintain();
        var refusing = f.operations.find(request.key()).orElseThrow();
        assertEquals(OperationState.RUNNING, refusing.state());
        assertRetainedImport(f.coordinator, f.operations, request.key(), "RECOVERY_GENERATION_MISMATCH");
        assertEquals(1, f.queue.jobStateCounts().pendingCount(), "issued claim blocks retirement of other members");
        generation.set(GENERATION);
        f.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(issued, null, "a".repeat(64))),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        f.coordinator.maintain();
        var terminal = f.operations.find(request.key()).orElseThrow();
        assertRetainedImport(f.coordinator, f.operations, terminal.key(), "RECOVERY_GENERATION_MISMATCH");
        var receipt = f.queue.recordedWalk(childKey.get()).orElseThrow();
        assertEquals(1, receipt.completedUnits(), "the issued owner's committed effect remains counted");
        assertNull(receipt.sealedAt());
        assertEquals(1, f.queue.jobStateCounts().pendingCount());
        assertTrue(f.queue.pollPending(1).isEmpty());
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"ingest-refusal:2:RECOVERY_GENERATION_MISMATCH", "ingest-refusal:1:UNKNOWN", "ingest-refusal:1:RECOVERY_GENERATION_MISMATCH"})
  void malformedRefusalWitnessCannotFallBackToCurrentAuthority(String marker) throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var childKey = new AtomicReference<String>();
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        childKey.set(key);
        f.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(
            plan.roots().getFirst().path().resolve("unresolved.txt"))), null);
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
      }
      var row = f.operations.find(request.key()).orElseThrow();
      f.coordinator.stopProducers(1000);
      f.attachment.close();
      f.operations.checkpoint(row.id(), marker, 0, 0);
      var unresolved = f.queue.recordedWalk(childKey.get()).orElseThrow();
      var runner = new OperationAttemptRunnerImpl(f.operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null, new RecordedIngestPlanResolver());
      var recoveredAdmission = new EngineAdmissionController(2, 2, 1);
      var recovered = new RecordedIngestionCoordinator(f.operations, runner, recoveredAdmission, f.authority);
      try (var attachment = recovered.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        assertNotNull(attachment);
        bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
          throw new AssertionError("malformed refusal must not execute");
        });
        recovered.maintain();
        var terminal = f.operations.find(request.key()).orElseThrow();
        assertRetainedImport(recovered, f.operations, terminal.key(),
            marker.equals("ingest-refusal:1:RECOVERY_GENERATION_MISMATCH")
                ? "RECOVERY_GENERATION_MISMATCH" : "RECOVERY_BINDING_INVALID");
        assertEquals(marker, terminal.checkpointCursor());
        assertEquals(unresolved, f.queue.recordedWalk(childKey.get()).orElseThrow(),
            "corrupt decision cannot retire, seal or acknowledge unresolved queue evidence");
        assertEquals(1, f.queue.jobStateCounts().pendingCount());
        assertTrue(f.queue.pollPending(1).isEmpty());
        assertEquals(0, recoveredAdmission.activeWorkCount());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualEngineProducerJournalsAndAcknowledgesARealFilesystemRoot(boolean singleFile) throws Exception {
    var generations = new io.justsearch.indexerworker.index.IndexGenerationManager(temp.resolve("index"));
    var layout = generations.initializeOrLoad();
    String generation = layout.activeGenerationPath().getFileName().toString();
    try (Fixture f = new Fixture(temp.resolve("fixture"), 1, true, generation, singleFile);
        var executors = new DefaultEngineExecutorRegistry()) {
      var runtime = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(f.queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), layout.basePath(),
          layout.activeGenerationPath(), runtime, runtime, null, 0L);
      var services = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      org.mockito.Mockito.when(services.ingestService()).thenReturn(service);
      Path file = singleFile ? f.plan.roots().getFirst().path()
          : Files.writeString(f.plan.roots().getFirst().path().resolve("probe.txt"), "recorded producer probe");
      var enumerated = new java.util.concurrent.CountDownLatch(1);
      try (var client = new EngineKnowledgeClient(executors, () -> services,
          new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()),
          5_000, 100, io.justsearch.app.services.worker.IpcTelemetry.noop(), () -> {}, f.admission, f.authority.roots());
          var _ = f.queue.subscribeRecordedWalks(key -> {
            if (f.queue.recordedWalk(key).orElseThrow().enumerationClosedAt() != null) enumerated.countDown();
          })) {
        bindProducer(f.coordinator, client::enumerateRecordedRoot);
        var request = f.request();
        var accepted = f.accept(request);
        try (var work = f.admission.admit(request.context(), false)) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          assertTrue(enumerated.await(5, java.util.concurrent.TimeUnit.SECONDS), "real walk must reach enumeration closure");
          var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
          var progress = f.queue.recordedWalk(child.key()).orElseThrow();
          assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, progress.enumerationOutcome());
          assertFalse(result.completion().toCompletableFuture().isDone(), "enumeration is not index completion");
          var jobs = f.queue.pollPending(10);
          assertEquals(1, jobs.size(), "only the frozen file was admitted");
          var issued = jobs.getFirst();
          assertEquals(file, issued.path());
          assertEquals(child.key(), issued.scanId());
          assertEquals(progress.enumerationEpoch(), issued.walkEpoch());
          assertTrue(issued.recordedForce(), "force is supplied by recorded claim authority");
          assertEquals(new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL"), issued.provenance());
          f.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(issued, null, "e".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          var completed = result.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(OperationState.COMPLETE, completed.state());
          assertEquals(1, completed.unitsCompleted());
          var sealed = f.queue.recordedWalk(child.key()).orElseThrow();
          assertEquals(sealed.revision(), sealed.acknowledgedRevision());
        }
        f.coordinator.stopProducers(1_000);
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void gatedImportPublishesItsParentOriginOnAllRowsIncludingReopenedReplay(boolean missingOrigin) throws Exception {
    Path fixturePath = temp.resolve("gated-origin");
    String parentKey;
    String childKey;
    String origin;
    Path file;
    RecordedRootPlan frozen;
    try (Fixture f = new Fixture(fixturePath, 1)) {
      Path root = Files.createDirectory(temp.resolve("unwatched-import"));
      // Isolate durable grant containment from source ownership: this approved fixture root
      // is not a watched declaration. Approval re-evaluation itself belongs to P2.
      f.authority.scope().bindIndexedRoots(context -> List.of(root));
      file = Files.writeString(root.resolve("long.txt"), "gated import provenance words and sentences.\n".repeat(600));
      var handlers = new HandlerRegistry();
      handlers.register(AgentToolsOperationCatalog.INGEST_FILES,
          new IngestTool(f.coordinator, context -> List.of(new RootBinding(root, "documents")),
              context -> GENERATION, List::of));
      var dispatcher = new OperationExecutorImpl(f.runner, f.admission, handlers, null, Map.of(),
          CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
      var operation = new AgentToolsOperationCatalog().findByIdValue(AgentToolsOperationCatalog.INGEST_FILES.value()).orElseThrow();
      var seed = f.request(); parentKey = OperationKeys.generate(CLOCK);
      bindProducer(f.coordinator, (plan, key, epoch, context, cancellation) -> {
        f.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)
            .withinRoot(plan.roots().getFirst().path())), "documents");
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      String arguments = new tools.jackson.databind.ObjectMapper().writeValueAsString(
          Map.of("paths", List.of(root.toString()), "collection", "documents"));
      assertTrue(dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(), seed.context(), parentKey).success());
      var parent = f.operations.find(parentKey).orElseThrow();
      frozen = new RecordedIngestPlanResolver().resolve(parent, f.operations.acceptedPreparation(parent.id()).orElseThrow());
      var child = f.operations.findIngestChild(parentKey, frozen).orElseThrow(); childKey = child.key();
      assertFalse(parentKey.equals(childKey), "exercise the real gate/ingest-child mismatch");
      assertEquals(parentKey, io.justsearch.app.api.operations.RecordedIngestChild.parentKey(child.descriptor()));
      try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + fixturePath.resolve("jobs.db"));
          var query = db.createStatement(); var rows = query.executeQuery("SELECT origin_id,operation_key FROM source_origin WHERE kind='IMPORT'")) {
        assertTrue(rows.next()); origin = rows.getString(1); assertEquals(parentKey, rows.getString(2));
      }
      if (missingOrigin) {
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + fixturePath.resolve("jobs.db")); var query = db.createStatement()) {
          query.executeUpdate("DELETE FROM source_origin");
        }
      }
      // Leave the walk's claim unacknowledged so a real recovery replays it after reopen.
      var claims = f.queue.pollPending(1); assertEquals(1, claims.size()); assertEquals(childKey, claims.getFirst().scanId());
      publishRecordedOrigin(f.queue, claims, temp.resolve("origin-index"), missingOrigin ? null : origin, false);
      f.queue.returnUnfinishedClaims(claims);
    }
    try (var operations = new SqliteOperationStore(fixturePath.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS), null, new RecordedIngestPlanResolver());
      var authority = OperationAuthority.load(fixturePath.resolve("authority"));
      authority.scope().bindIndexedRoots(context -> List.of(file.getParent()));
      var coordinator = new RecordedIngestionCoordinator(operations, runner, new EngineAdmissionController(2, 2, 1), authority);
      try (var queue = new SqliteJobQueue(fixturePath.resolve("jobs.db"), coordinator::recordedClaimDecision)) {
        queue.open();
        try (var _ = coordinator.attach(queue, () -> Optional.of(GENERATION), () -> true)) {
          bindProducer(coordinator, (plan, key, epoch, context, cancellation) -> {
            assertEquals(childKey, key, "recovery keeps the accepted child identity");
            queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)
                .withinRoot(plan.roots().getFirst().path())), "documents");
            return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          coordinator.maintain();
          // Recovery restores the immutable accepted origin if it was lost at the handoff.
          var claims = queue.pollPending(1); assertEquals(1, claims.size()); assertEquals(childKey, claims.getFirst().scanId());
          publishRecordedOrigin(queue, claims, temp.resolve("origin-index"), origin, true);
          coordinator.maintain();
          var terminal = operations.find(parentKey).orElseThrow();
          assertEquals(OperationState.COMPLETE, terminal.state(), terminal.toString());
          coordinator.stopProducers(1000);
        }
      }
    }
  }

  private static void publishRecordedOrigin(JobQueue queue, List<JobQueue.IndexJob> claims, Path index,
      String expectedOrigin, boolean acknowledge) throws Exception {
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var lucene = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
            new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog())
            .atPath(index).withExecutorRegistrations(lucene).open()) {
      runtime.commitOps().stopCommitTimer();
      var parser = org.mockito.Mockito.mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
      var policy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
      org.mockito.Mockito.when(parser.extractionPolicy()).thenReturn(policy);
      org.mockito.Mockito.when(parser.extractArtifact(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
          io.justsearch.indexerworker.extract.ExtractionArtifact.full(new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(
              Files.readString(call.getArgument(0)), "long", "text/plain"), policy, "gated-origin", false));
      var metrics = org.mockito.Mockito.mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class);
      var journal = new io.justsearch.indexerworker.loop.IngestionOutcomeJournal(queue, metrics, null, () -> false);
      var authorityType = Class.forName("io.justsearch.indexerworker.loop.WorkerIngestionAuthority");
      var authorityConstructor = authorityType.getDeclaredConstructor(); authorityConstructor.setAccessible(true);
      Object authority = authorityConstructor.newInstance();
      var roots = org.mockito.Mockito.mock(io.justsearch.indexerworker.loop.SourcePresence.RootCheck.class);
      org.mockito.Mockito.when(roots.admits(org.mockito.ArgumentMatchers.any())).thenReturn(true);
      org.mockito.Mockito.when(roots.admitsFile(org.mockito.ArgumentMatchers.any())).thenReturn(true);
      var useRootCheck = authorityType.getDeclaredMethod("useRootCheck", io.justsearch.indexerworker.loop.SourcePresence.RootCheck.class);
      useRootCheck.setAccessible(true); useRootCheck.invoke(authority, roots);
      var identities = org.mockito.Mockito.mock(io.justsearch.indexerworker.identity.DocumentIdentityStore.class);
      org.mockito.Mockito.when(identities.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
          .thenReturn(new io.justsearch.indexerworker.identity.DocumentIdentityStore.Identity("hash", "gated-stable-uid", 1, 1));
      var fence = new io.justsearch.indexerworker.loop.FileMutationFence();
      var stats = new io.justsearch.indexerworker.loop.ops.BatchStats();
      var stale = org.mockito.Mockito.mock(io.justsearch.indexerworker.loop.StaleSnapshotResolver.class);
      var extractor = (io.justsearch.indexerworker.loop.JobBatchExtractor)
          io.justsearch.indexerworker.loop.JobBatchExtractor.class.getConstructors()[0].newInstance(authority, journal, queue, parser,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          org.mockito.Mockito.mock(io.justsearch.indexerworker.loop.StaleSourceHandler.class),
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), new AtomicBoolean(true), new java.util.HashSet<>(),
          (java.util.function.Supplier<io.justsearch.indexerworker.path.PathResolutionStore>) () ->
              org.mockito.Mockito.mock(io.justsearch.indexerworker.path.PathResolutionStore.class),
          (java.util.function.Supplier<io.justsearch.indexerworker.identity.DocumentIdentityStore>) () -> identities,
          (io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.StageRecorder) (stage, ms, reason) -> {},
          (java.util.function.BooleanSupplier) () -> false, (java.util.function.LongConsumer) ignored -> {}, fence);
      var writer = (io.justsearch.indexerworker.loop.JobBatchWriter)
          io.justsearch.indexerworker.loop.JobBatchWriter.class.getConstructors()[0].newInstance(authority, runtime.indexingCoordinator(),
          org.mockito.Mockito.mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
          org.mockito.Mockito.mock(io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle.class),
          (java.util.function.Supplier<io.justsearch.indexerworker.splade.SpladeEncoder>) () -> null,
          journal, queue, parser, metrics, stats, stale, (java.util.function.LongConsumer) ignored -> {},
          (io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.StageRecorder) (stage, ms, reason) -> {},
          (java.util.function.BooleanSupplier) () -> false, (java.util.function.BooleanSupplier) () -> false,
          (java.util.function.Supplier<io.justsearch.adapters.lucene.runtime.RunningRuntime>) () -> null, fence);
      for (var job : extractor.extractAll(claims)) writer.write(job, null);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      try (var directory = org.apache.lucene.store.FSDirectory.open(index); var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
        var searcher = new org.apache.lucene.search.IndexSearcher(reader);
        var rows = searcher.search(new org.apache.lucene.search.MatchAllDocsQuery(), 1000);
        assertTrue(rows.totalHits.value() > 2, "inspect a committed parent and multiple chunks");
        for (var hit : rows.scoreDocs) {
          var stored = searcher.storedFields().document(hit.doc);
          assertEquals(expectedOrigin, stored.get("origin_id"), "the gate's origin follows the distinct child walk key");
          assertEquals("1", stored.get("envelope_version"));
          assertEquals("documents", stored.get("collection"));
        }
      }
      if (acknowledge) journal.drainPending();
    }
  }

  private enum RegisteredDispatchCase {
    FILE_INGEST,
    DIRECTORY_INGEST,
    DIRECTORY_REINDEX,
    FORCED_DIRECTORY_REINDEX
  }

  private enum RegisteredRecoveryRefusalCase {
    INGEST_STALE_GENERATION(RegisteredDispatchCase.DIRECTORY_INGEST,
        "replacement-generation", false, "RECOVERY_GENERATION_MISMATCH"),
    REINDEX_STALE_GENERATION(RegisteredDispatchCase.DIRECTORY_REINDEX,
        "replacement-generation", false, "RECOVERY_GENERATION_MISMATCH"),
    INGEST_REVOKED_AUTHORITY(RegisteredDispatchCase.DIRECTORY_INGEST,
        GENERATION, true, "RECOVERY_AUTHORIZATION_REFUSED"),
    REINDEX_REVOKED_AUTHORITY(RegisteredDispatchCase.DIRECTORY_REINDEX,
        GENERATION, true, "RECOVERY_AUTHORIZATION_REFUSED");

    final RegisteredDispatchCase dispatchCase;
    final String servingGeneration;
    final boolean revokeAuthority;
    final String refusalCode;

    RegisteredRecoveryRefusalCase(RegisteredDispatchCase dispatchCase, String servingGeneration,
        boolean revokeAuthority, String refusalCode) {
      this.dispatchCase = dispatchCase;
      this.servingGeneration = servingGeneration;
      this.revokeAuthority = revokeAuthority;
      this.refusalCode = refusalCode;
    }
  }

  @ParameterizedTest
  @EnumSource(RegisteredDispatchCase.class)
  void registeredPreparedHandlersReachTheActualProducerAndRetryOneRecordedIdentity(
      RegisteredDispatchCase scenario) throws Exception {
    boolean ingest = scenario == RegisteredDispatchCase.FILE_INGEST
        || scenario == RegisteredDispatchCase.DIRECTORY_INGEST;
    boolean singleFile = scenario == RegisteredDispatchCase.FILE_INGEST;
    boolean force = scenario == RegisteredDispatchCase.FORCED_DIRECTORY_REINDEX;
    Path scenarioDirectory = temp.resolve(scenario.name().toLowerCase(java.util.Locale.ROOT));
    var generations = new io.justsearch.indexerworker.index.IndexGenerationManager(
        scenarioDirectory.resolve("index"));
    var layout = generations.initializeOrLoad();
    String generation = layout.activeGenerationPath().getFileName().toString();
    try (Fixture f = new Fixture(scenarioDirectory.resolve("fixture"), 1, false, generation, singleFile);
        var executors = new DefaultEngineExecutorRegistry()) {
      Path target = f.plan.roots().getFirst().path();
      Path enumeratedFile = singleFile ? target
          : Files.writeString(target.resolve("frozen.txt"), "frozen recorded target");
      Path replacement = Files.createDirectory(scenarioDirectory.resolve("replacement-root"));
      Files.writeString(replacement.resolve("replacement.txt"), "must not be enumerated");
      Path outside = Files.writeString(scenarioDirectory.resolve("outside.txt"), "outside frozen target");
      var roots = new AtomicReference<>(List.of(new RootBinding(
          singleFile ? target.getParent() : target, "documents")));
      var currentGeneration = new AtomicReference<>(generation);
      var exclusions = new AtomicReference<List<String>>(List.of());
      AtomicInteger preparations = new AtomicInteger();
      AtomicInteger producerCalls = new AtomicInteger();
      var handlers = new HandlerRegistry();
      if (ingest) {
        handlers.register(AgentToolsOperationCatalog.INGEST_FILES,
            new IngestTool(f.coordinator, context -> {
              preparations.incrementAndGet();
              return roots.get();
            }, context -> currentGeneration.get(), exclusions::get));
      } else {
        handlers.register(CoreOperationCatalog.REINDEX,
            new ReindexHandler(f.coordinator, context -> {
              preparations.incrementAndGet();
              return roots.get();
            }, context -> currentGeneration.get(), exclusions::get));
      }
      var dispatcher = new OperationExecutorImpl(f.runner, f.admission, handlers, null, Map.of(),
          CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
      var operation = ingest
          ? new AgentToolsOperationCatalog().findByIdValue(
              AgentToolsOperationCatalog.INGEST_FILES.value()).orElseThrow()
          : new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.REINDEX.value()).orElseThrow();
      String arguments = ingest
          ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(target.toString()), "collection", "documents"))
          : "{\"force\":" + force + "}";
      String changedArguments = ingest
          ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(replacement.toString()), "collection", "documents"))
          : "{\"force\":" + !force + "}";
      var seed = f.request();
      String operationKey = OperationKeys.generate(CLOCK);
      var enumerated = new java.util.concurrent.CountDownLatch(1);
      var parentCompletion = new CompletableFuture<OperationRecord>();
      var runtime = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      // This is the production filesystem enumerator and queue journal. The controlled terminal
      // transition below is deliberately not evidence of a Lucene write or flush.
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(f.queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), layout.basePath(),
          layout.activeGenerationPath(), runtime, runtime, null, 0L);
      var services = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      org.mockito.Mockito.when(services.ingestService()).thenReturn(service);
      try (var client = new EngineKnowledgeClient(executors, () -> services,
          new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()),
          5_000, 100, io.justsearch.app.services.worker.IpcTelemetry.noop(), () -> {}, f.admission,
          f.authority.roots());
          var _ = f.queue.subscribeRecordedWalks(key -> {
            if (f.queue.recordedWalk(key).orElseThrow().enumerationClosedAt() != null) {
              enumerated.countDown();
            }
          });
          var _ = f.operations.subscribeCompletions(row -> {
            if (operationKey.equals(row.key())) parentCompletion.complete(row);
          })) {
        bindProducer(f.coordinator, (acceptedPlan, childKey, epoch, context, cancellation) -> {
          producerCalls.incrementAndGet();
          var parent = f.operations.find(operationKey).orElseThrow();
          var preparation = f.operations.acceptedPreparation(parent.id()).orElseThrow();
          RecordedRootPlan frozen = new RecordedIngestPlanResolver().resolve(parent, preparation);
          var child = f.operations.findIngestChild(operationKey, frozen).orElseThrow();
          assertEquals(OperationState.RUNNING, parent.state());
          assertEquals(OperationState.RUNNING, child.state());
          assertEquals(frozen, RecordedRootPlan.fromReplayPayload(
              f.operations.acceptedPreparation(child.id()).orElseThrow().payload().value()));
          assertEquals(frozen, acceptedPlan);
          assertEquals(parent.context().withWorkId(context.workId().orElseThrow()), context);
          return client.enumerateRecordedRoot(acceptedPlan, childKey, epoch, context, cancellation);
        });
        var first = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(),
            seed.context(), operationKey);
        assertTrue(first.success());
        assertEquals(operationKey, first.structuredData().get("operationKey"));
        long parentId = ((Number) first.structuredData().get("operationRecordId")).longValue();
        assertEquals(1, preparations.get());
        assertEquals(1, producerCalls.get());

        roots.set(List.of(new RootBinding(replacement, "replacement")));
        currentGeneration.set("replacement-generation");
        exclusions.set(List.of("**/frozen.txt"));
        var runningRetry = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(),
            seed.context(), operationKey);
        assertTrue(runningRetry.success());
        assertEquals(parentId, ((Number) runningRetry.structuredData().get("operationRecordId")).longValue());
        assertEquals(1, preparations.get(), "running retry cannot resample preparation suppliers");
        assertEquals(1, producerCalls.get(), "running retry cannot start another producer");
        var conflict = assertThrows(OperationStoreException.class,
            () -> dispatcher.dispatch(operation, changedArguments, seed.provenance(), Optional.empty(),
                seed.context(), operationKey));
        assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, conflict.code());
        assertEquals(1, preparations.get());
        assertEquals(1, producerCalls.get());

        assertTrue(enumerated.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "real filesystem enumeration must close");
        var parent = f.operations.find(operationKey).orElseThrow();
        var frozen = new RecordedIngestPlanResolver().resolve(parent,
            f.operations.acceptedPreparation(parent.id()).orElseThrow());
        assertEquals(generation, frozen.generation());
        assertEquals(target.toAbsolutePath().normalize(), frozen.roots().getFirst().path());
        assertEquals(force, frozen.roots().getFirst().force());
        var child = f.operations.findIngestChild(operationKey, frozen).orElseThrow();
        var progress = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, progress.enumerationOutcome());
        assertFalse(f.operations.find(operationKey).orElseThrow().state().terminal(),
            "enumeration alone cannot complete the recorded parent");
        var jobs = f.queue.pollPending(10);
        assertEquals(1, jobs.size(), "only the frozen target is enumerated");
        var issued = jobs.getFirst();
        assertEquals(enumeratedFile.toAbsolutePath().normalize(), issued.path());
        assertFalse(issued.path().equals(outside));
        assertFalse(issued.path().startsWith(replacement));
        assertEquals(force, issued.recordedForce());
        assertEquals(child.key(), issued.scanId());
        assertTrue(f.queue.pollPending(10).isEmpty(), "retry cannot duplicate queue work");

        f.queue.markDoneTransitions(List.of(
            new JobQueue.IngestionLedgerTransition(issued, null, "f".repeat(64))),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        OperationRecord completed = parentCompletion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETE, completed.state());
        assertEquals(1, completed.unitsCompleted());
        assertEquals(0, completed.unitsFailed());
        var completedChild = f.operations.find(child.key()).orElseThrow();
        assertEquals(OperationState.COMPLETE, completedChild.state());
        assertEquals(1, completedChild.unitsCompleted());
        assertEquals(0, completedChild.unitsFailed());
        var sealed = f.queue.sealedRecordedWalkReceipt(child.key()).orElseThrow();
        assertEquals(1, sealed.completedUnits());
        assertEquals(0, sealed.failedUnits());
        var acknowledged = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(sealed.revision(), acknowledged.acknowledgedRevision());

        var terminalRetry = dispatcher.dispatch(operation, arguments, seed.provenance(),
            Optional.empty(), seed.context(), operationKey);
        assertTrue(terminalRetry.success());
        assertEquals(parentId,
            ((Number) terminalRetry.structuredData().get("operationRecordId")).longValue());
        assertEquals(1, preparations.get(), "terminal retry cannot resample preparation suppliers");
        assertEquals(1, producerCalls.get(), "terminal retry cannot start another producer");
        assertTrue(f.queue.pollPending(10).isEmpty(), "terminal retry cannot duplicate queue work");
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(RegisteredRecoveryRefusalCase.class)
  void registeredPreparedRecoveryRefusesStaleGenerationAndRevokedAuthorityWithoutEffect(
      RegisteredRecoveryRefusalCase scenario) throws Exception {
    boolean ingest = scenario.dispatchCase == RegisteredDispatchCase.DIRECTORY_INGEST;
    Path scenarioDirectory = temp.resolve(scenario.name().toLowerCase(java.util.Locale.ROOT));
    try (Fixture f = new Fixture(scenarioDirectory, 1)) {
      Path target = f.plan.roots().getFirst().path();
      AtomicInteger preparations = new AtomicInteger();
      AtomicInteger producerCalls = new AtomicInteger();
      AtomicBoolean oldProducerExited = new AtomicBoolean();
      var handlers = new HandlerRegistry();
      if (ingest) {
        handlers.register(AgentToolsOperationCatalog.INGEST_FILES,
            new IngestTool(f.coordinator, context -> {
              preparations.incrementAndGet();
              return List.of(new RootBinding(target, "documents"));
            }, context -> GENERATION, List::of));
      } else {
        handlers.register(CoreOperationCatalog.REINDEX,
            new ReindexHandler(f.coordinator, context -> {
              preparations.incrementAndGet();
              return List.of(new RootBinding(target, "documents"));
            }, context -> GENERATION, List::of));
      }
      var dispatcher = new OperationExecutorImpl(f.runner, f.admission, handlers, null, Map.of(),
          CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
      var operation = ingest
          ? new AgentToolsOperationCatalog().findByIdValue(
              AgentToolsOperationCatalog.INGEST_FILES.value()).orElseThrow()
          : new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.REINDEX.value()).orElseThrow();
      String arguments = ingest
          ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(target.toString()), "collection", "documents"))
          : "{\"force\":false}";
      if (ingest) {
        f.authority.grants().grantAllowAlways(operation.id().value(), SourceTier.UNTRUSTED);
        dispatcher.setDurableGrantStore(f.authority.grants(), f.authority.scope());
      }
      String operationKey = OperationKeys.generate(CLOCK);
      var producerExit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
      bindProducer(f.coordinator, (plan, childKey, epoch, context, cancellation) -> {
        producerCalls.incrementAndGet();
        cancellation.onCancel(() -> {
          oldProducerExited.set(true);
          producerExit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        });
        return producerExit;
      });

      var seed = f.mcpRequest();
      var response = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(),
          seed.context(), operationKey);
      assertTrue(response.success());
      assertEquals(1, preparations.get());
      assertEquals(1, producerCalls.get());
      var parentBefore = f.operations.find(operationKey).orElseThrow();
      var parentPreparation = f.operations.acceptedPreparation(parentBefore.id()).orElseThrow();
      var frozenPlan = new RecordedIngestPlanResolver().resolve(parentBefore, parentPreparation);
      var childBefore = f.operations.findIngestChild(operationKey, frozenPlan).orElseThrow();
      var childPreparation = f.operations.acceptedPreparation(childBefore.id()).orElseThrow();
      OperationAuthorizationBasis acceptedBasis = ingest
          ? new OperationAuthorizationBasis.OperationGrant(operation.id().value(), SourceTier.UNTRUSTED)
          : new OperationAuthorizationBasis.StructuralAuto();
      assertEquals(Optional.of(acceptedBasis.encode()), parentBefore.context().grantReference());
      assertEquals(SourceTier.UNTRUSTED.name(), parentBefore.context().sourceTier());
      assertEquals(TransportTag.MCP.name(), parentBefore.context().transport());
      assertTrue(f.authority.evaluateRecordedIngest(parentBefore, parentPreparation,
          Optional.of(GENERATION), ignored -> true)
          instanceof OperationAuthority.RecordedIngestRecoveryDecision.Authorized,
          "the dispatcher-selected grant must authorize recovery before revocation");

      f.coordinator.stopProducers(1_000);
      assertTrue(oldProducerExited.get(), "the old producer must exit before replacement");
      f.attachment.close();
      if (scenario.revokeAuthority) f.authority.hardStop().engage();
      var parentCompletion = new CompletableFuture<OperationRecord>();
      try (var _ = f.operations.subscribeCompletions(row -> {
        if (operationKey.equals(row.key())) parentCompletion.complete(row);
      })) {
        f.attachment = f.coordinator.attach(f.queue,
            () -> Optional.of(scenario.servingGeneration), () -> true);
        bindProducer(f.coordinator, (plan, childKey, epoch, context, cancellation) -> {
          producerCalls.incrementAndGet();
          return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        if (ingest) {
          assertFalse(parentCompletion.isDone(), "I8/I9: refusal retains the import instead of terminalizing it");
          assertRetainedImport(f.coordinator, f.operations, operationKey, scenario.refusalCode);
        } else {
          OperationRecord refused = parentCompletion.get(2, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(OperationState.FAILED, refused.state());
          assertEquals(scenario.refusalCode, refused.receipt().code());
        }
      }

      var parentAfter = f.operations.find(operationKey).orElseThrow();
      var childAfter = f.operations.find(childBefore.key()).orElseThrow();
      assertEquals(parentBefore.id(), parentAfter.id());
      assertEquals(childBefore.id(), childAfter.id());
      assertEquals(parentPreparation,
          f.operations.acceptedPreparation(parentAfter.id()).orElseThrow());
      assertEquals(childPreparation,
          f.operations.acceptedPreparation(childAfter.id()).orElseThrow());
      assertEquals(1, preparations.get(), "recovery cannot invoke handler preparation");
      assertEquals(1, producerCalls.get(), "replacement producer cannot run refused work");
      assertTrue(f.queue.pollPending(10).isEmpty());
      assertFalse(f.queue.hasIssuedRecordedClaims(childAfter.key()));
      assertEquals(0, parentAfter.unitsCompleted());
      assertEquals(0, parentAfter.unitsFailed());
      assertEquals(0, childAfter.unitsCompleted());
      assertEquals(0, childAfter.unitsFailed());
      var progress = f.queue.recordedWalk(childAfter.key()).orElseThrow();
      assertEquals(0, progress.completedUnits());
      assertEquals(0, progress.failedUnits());
      f.queue.sealedRecordedWalkReceipt(childAfter.key()).ifPresent(receipt -> {
        assertEquals(0, receipt.completedUnits(), "an empty receipt is not an index effect");
        assertEquals(0, receipt.failedUnits(), "an empty receipt is not an index effect");
      });
    }
  }

  @Test
  void actualProducerReplacementWaitsForExitWithoutCancellingDurableParent() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (Fixture f = new Fixture(temp, 1); var executors = new DefaultEngineExecutorRegistry()) {
      var firstService = org.mockito.Mockito.mock(io.justsearch.indexerworker.services.WorkerIngestService.class);
      var seen = new AtomicReference<io.justsearch.indexerworker.services.CallContext>();
      org.mockito.Mockito.doAnswer(call -> {
        seen.set(call.getArgument(2));
        entered.countDown();
        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
        call.<java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress>>getArgument(1).accept(
            io.justsearch.ipc.ScanRootProgress.newBuilder().setComplete(true).setTerminalReasonCode("CLIENT_CANCELLED").build());
        return null;
      }).when(firstService).scanRecordedRoot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var services = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      org.mockito.Mockito.when(services.ingestService()).thenReturn(firstService);
      var current = new AtomicReference<>(services);
      var firstExit = new AtomicReference<java.util.concurrent.CompletionStage<JobQueue.WalkEnumerationOutcome>>();
      try (var client = new EngineKnowledgeClient(executors, current::get,
          new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()), 5_000, 100,
          io.justsearch.app.services.worker.IpcTelemetry.noop(), () -> {}, f.admission, f.authority.roots())) {
        bindProducer(f.coordinator, (plan, key, epoch, context, token) -> {
          var exit = client.enumerateRecordedRoot(plan, key, epoch, context, token);
          firstExit.set(exit);
          return exit;
        });
        var request = f.request();
        var accepted = f.accept(request);
        try (var work = f.admission.admit(request.context(), false)) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
          assertThrows(java.io.IOException.class, () -> f.coordinator.stopProducers(10));
          assertTrue(seen.get().cancelled());
          assertTrue(work.cancellationReason().isEmpty(), "physical replacement must retain durable parent authority");
          assertFalse(result.completion().toCompletableFuture().isDone());
          assertThrows(java.io.IOException.class, f.attachment::close);
          release.countDown();
          assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
              firstExit.get().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS));
          f.coordinator.stopProducers(1_000);
          f.attachment.close();
          assertFalse(result.completion().toCompletableFuture().isDone(), "same parent remains pending across replacement");
          var replacement = org.mockito.Mockito.mock(io.justsearch.indexerworker.services.WorkerIngestService.class);
          org.mockito.Mockito.doAnswer(call -> {
            call.<java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress>>getArgument(1).accept(
                io.justsearch.ipc.ScanRootProgress.newBuilder().setComplete(true).build());
            return null;
          }).when(replacement).scanRecordedRoot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
          var replacementServices = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
          org.mockito.Mockito.when(replacementServices.ingestService()).thenReturn(replacement);
          current.set(replacementServices);
          f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
          bindProducer(f.coordinator, client::enumerateRecordedRoot);
          assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS).state());
          assertEquals(1, f.operations.find(request.key()).orElseThrow().attempts());
          org.mockito.Mockito.verify(replacement).scanRecordedRoot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
      }
    } finally { release.countDown(); }
  }

  @Test
  void processShutdownHandsOffPendingIngestParentAndChildAfterProducerExit() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var producerEntered = new java.util.concurrent.CountDownLatch(1);
      var producerExit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
      bindProducer(f.coordinator, (plan, key, epoch, context, token) -> {
        token.onCancel(() -> producerExit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        producerEntered.countDown();
        return producerExit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      String childKey;
      try (var work = f.admission.admit(request.context(), false)) {
        var started = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertTrue(producerEntered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        childKey = f.operations.openRecords().stream()
            .filter(row -> row.descriptor().operationRef() == null)
            .map(OperationRecord::key).findFirst().orElseThrow();
        f.admission.beginClosing();
        f.runner.beginClosing();
        f.coordinator.stopProducers(1_000);
        assertFalse(started.completion().toCompletableFuture().isDone(),
            "handoff must keep the accepted durable result pending for the next process");
      }
      assertEquals(0, f.admission.activeWorkCount());
      assertTrue(f.runner.awaitDrained(java.time.Duration.ZERO));
      assertEquals(OperationState.RUNNING, f.operations.find(request.key()).orElseThrow().state());
      assertEquals(OperationState.RUNNING, f.operations.find(childKey).orElseThrow().state());
    }
  }

  /**
   * S16-D1 (Spec F §4.3 QB2, as ruled against the D1 shutdown design): a quit during a long ingest
   * unit whose body never yields. The producer drain gives up at the end of the quit's cooperative
   * window instead of holding the quit; the durable parent and child stay RUNNING (abandoned, not
   * terminalized); the process then ends without the ordered close it could not finish; and the
   * next process replays the unit exactly once to COMPLETE. What the quit loses is the unit's
   * progress, never the unit.
   */
  @Test
  void aQuitDuringAUnitThatNeverYieldsAbandonsItToReplayOnceToComplete() throws Exception {
    long window = QuitBudget.QB2_COOPERATIVE_MS + QuitBudget.QB2_CANCEL_ACK_MS;
    Path file;
    String parentKey;
    String childKey;
    Fixture old = new Fixture(temp, 1);
    try {
      file = Files.writeString(old.plan.roots().getFirst().path().resolve("long-unit.txt"),
          "a long ingest unit");
      var entered = new java.util.concurrent.CountDownLatch(1);
      var enumerated = new AtomicReference<String>();
      bindProducer(old.coordinator, (plan, key, epoch, context, token) -> {
        enumerated.set(key);
        old.queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
        entered.countDown();
        return new CompletableFuture<>(); // ignores its cancellation: the body never yields
      });
      var request = old.request();
      var accepted = old.accept(request);
      var work = old.admission.admit(request.context(), false);
      var started = old.runner.start(accepted, handle -> old.coordinator.execute(handle, work.context()));
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));

      old.admission.beginClosing();
      old.runner.beginClosing();
      long drainStarted = System.nanoTime();
      assertThrows(java.io.IOException.class, () -> old.coordinator.stopProducers(window),
          "an undrained producer is reported, so the close is unclean and the index steps refuse");
      long drainMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - drainStarted);
      assertTrue(drainMs < window + 1_000L, "the drain held the quit for " + drainMs + " ms");
      assertFalse(started.completion().toCompletableFuture().isDone(),
          "the abandoned unit keeps its durable result pending for the next process");
      parentKey = request.key();
      childKey = enumerated.get();
      assertEquals(OperationState.RUNNING, old.operations.find(parentKey).orElseThrow().state());
      assertEquals(OperationState.RUNNING, old.operations.find(childKey).orElseThrow().state());
    } finally {
      // The process ends: its stores are released without the ordered close it could not finish.
      old.queue.close();
      old.operations.close();
    }

    var admission = new EngineAdmissionController(2, 2, 1);
    var authority = OperationAuthority.load(temp.resolve("authority"));
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      var recovered = new RecordedIngestionCoordinator(operations, runner, admission, authority);
      try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        queue.open();
        try (var attachment = recovered.attach(queue, () -> Optional.of(GENERATION), () -> true)) {
          assertNotNull(attachment);
          var replays = new AtomicInteger();
          bindProducer(recovered, (plan, key, epoch, context, cancellation) -> {
            replays.incrementAndGet();
            assertEquals(childKey, key, "the abandoned unit is the one replayed");
            queue.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
            return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          recovered.maintain();
          recovered.maintain();
          assertEquals(1, replays.get(), "the unit replays once");
          var claims = queue.pollPending(10);
          assertEquals(1, claims.size(), "one unit, not a duplicate from the abandoned attempt");
          queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claims.getFirst(), null,
                  "d".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          recovered.maintain();
          var parent = operations.find(parentKey).orElseThrow();
          assertEquals(OperationState.COMPLETE, parent.state());
          assertEquals(OperationState.COMPLETE, operations.find(childKey).orElseThrow().state());
          assertEquals(1, parent.unitsCompleted(), "exactly the one unit is indexed");
          assertEquals(0, parent.unitsFailed());
          assertEquals(0, admission.activeWorkCount());
        }
      }
    }
  }

  private static SqliteJobQueue queueWithAgedRetentionClock(Path db,
      java.util.function.Function<String, JobQueue.RecordedClaimDecision> policy) throws Exception {
    try {
      // The base has no clock constructor. Its assertion uses explicitly aged job/ledger rows.
      return SqliteJobQueue.class.getConstructor(Path.class, java.util.function.Function.class, Clock.class)
          .newInstance(db, policy, Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));
    } catch (NoSuchMethodException baseWithoutClockSeam) {
      return new SqliteJobQueue(db, policy);
    }
  }

  private static void assertRetainedImport(RecordedIngestionCoordinator coordinator,
      io.justsearch.app.api.operations.OperationStore operations, String key, String reason) {
    var row = operations.find(key).orElseThrow();
    assertFalse(row.state().terminal(), "interrupted import remains open");
    assertNull(row.receipt());
    var interruption = coordinator.interruptedImports().stream().filter(item -> key.equals(item.operationKey())).findFirst().orElseThrow();
    assertEquals(reason, interruption.reason());
  }

  private static final class Fixture implements AutoCloseable {
    final SqliteOperationStore operations;
    final SqliteJobQueue queue;
    final AtomicBoolean fixtureClaimOwner = new AtomicBoolean();
    final OperationAuthority authority;
    final EngineAdmissionController admission = new EngineAdmissionController(2, 2, 1);
    final OperationAttemptRunnerImpl runner;
    final RecordedIngestionCoordinator coordinator;
    final RecordedRootPlan plan;
    io.justsearch.indexerworker.server.RecordedIngestionLifecycle.Attachment attachment;
    Fixture(Path directory, int roots) throws Exception { this(directory, roots, false); }
    Fixture(Path directory, int roots, boolean force) throws Exception {
      this(directory, roots, force, GENERATION, false);
    }
    Fixture(Path directory, int roots, boolean force, String generation, boolean singleFile) throws Exception {
      Files.createDirectories(directory);
      List<RecordedRootPlan.Root> plans = new ArrayList<>();
      for (int index = 0; index < roots; index++) plans.add(new RecordedRootPlan.Root(
          singleFile ? Files.writeString(directory.resolve("root-" + index + ".txt"), "recorded producer probe")
              : Files.createDirectory(directory.resolve("root-" + index)),
          null, force, singleFile, List.of(), List.of()));
      plan = new RecordedRootPlan(generation, plans);
      Path authorityDirectory = directory.resolve("authority");
      Files.createDirectories(authorityDirectory);
      Files.writeString(authorityDirectory.resolve("watched_roots.json"),
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
              "schemaVersion", 1, "roots", List.of(Map.of("path", directory.toAbsolutePath().toString())))));
      authority = OperationAuthority.load(authorityDirectory);
      operations = new SqliteOperationStore(directory.resolve("operations.db"));
      runner = new OperationAttemptRunnerImpl(operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      coordinator = new RecordedIngestionCoordinator(operations, runner, admission, authority);
      queue = queueWithAgedRetentionClock(directory.resolve("jobs.db"), key -> fixtureClaimOwner.get() ? JobQueue.RecordedClaimDecision.ALLOW : coordinator.recordedClaimDecision(key));
      queue.open();
      attachment = coordinator.attach(queue, () -> Optional.of(generation), () -> true);
    }
    OperationAttemptRunner.Request request() { return request(new OperationAuthorizationBasis.StructuralAuto()); }
    OperationAttemptRunner.Request mcpRequest() {
      EngineContext context = EngineProvenance.context(EngineContext.ClientKind.MCP_CLIENT,
          "coordinator-test", Optional.empty(), Optional.empty(), TransportTag.MCP,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.now(CLOCK), Optional.empty()));
    }
    OperationAttemptRunner.Request request(OperationAuthorizationBasis basis) {
      EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "coordinator-test",
          Optional.empty(), Optional.of(basis.encode()), TransportTag.SYSTEM_INTERNAL,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.now(CLOCK), Optional.empty()));
    }
    OperationAttemptRunner.PreparedAttempt accept(OperationAttemptRunner.Request request) {
      var preparation = RecordedParentFixture.prepare(request, plan);
      runner.savePreparation(request, preparation);
      return runner.acceptPrepared(request, preparation.nonce());
    }
    @Override public void close() throws Exception {
      coordinator.stopProducers(1000);
      attachment.close();
      queue.close();
      operations.close();
    }
  }

  private static void bindProducer(
      RecordedIngestionCoordinator coordinator, RecordedIngestionCoordinator.Producer producer) {
    coordinator.bindProducer(producer, producer, producer);
  }
}
