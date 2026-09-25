/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatchPlan;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.BulkReindexProgress;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationOutcomeView;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the captured bulk lifecycle through the real SQLite owner and queue. */
final class RecordedBulkIngestionCoordinatorTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final String SERVING_GENERATION = "serving-generation-1";
  private static final String TARGET_INPUTS = "{\"dimension\":768}";

  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preparedBulkWaitsForPromotedBootAndRepairsTerminalAcknowledgementFromQueueInventory(
      boolean sourceChangedAfterCapture) throws Exception {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Path member = Files.writeString(watchedRoot.resolve("one.txt"), "captured source bytes");
    String sourceHash = sha256(Files.readAllBytes(member));
    IndexTargetSnapshot target = new IndexTargetSnapshot(sha256(TARGET_INPUTS), TARGET_INPUTS);
    long memberSize = Files.size(member);
    Path authorityDirectory = Files.createDirectory(temp.resolve("authority"));
    tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    Files.writeString(authorityDirectory.resolve("watched_roots.json"), mapper.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "roots", List.of(Map.of("path", watchedRoot.toAbsolutePath().normalize().toString())))));

    var authority = OperationAuthority.load(authorityDirectory);
    AtomicReference<RecordedIngestionCoordinator> coordinatorRef = new AtomicReference<>();
    AtomicBoolean rejectQueueAcknowledgement = new AtomicBoolean(true);
    try (var operations = new SqliteOperationStore(temp.resolve("operations.db"));
        var sqliteQueue = new SqliteJobQueue(temp.resolve("jobs.db"), key -> {
      RecordedIngestionCoordinator current = coordinatorRef.get();
      return current == null ? JobQueue.RecordedClaimDecision.DENY : current.recordedClaimDecision(key);
    })) {
    sqliteQueue.open();
    var admission = new EngineAdmissionController(4, 8, 1);
    var attempts = new OperationAttemptRunnerImpl(operations, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
        null, new RecordedIngestPlanResolver());
    var coordinator = new RecordedIngestionCoordinator(operations, attempts, admission, authority);
    coordinatorRef.set(coordinator);
    JobQueue queue = acknowledgeGate(sqliteQueue, rejectQueueAcknowledgement);

    AtomicReference<RecordedIngestionLifecycle.BulkRuntime> runtime = new AtomicReference<>(
        new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.CAPTURING,
            SERVING_GENERATION, null, "IDLE", null, false));
    IndexingService indexing = mock(IndexingService.class);
    when(indexing.captureServingGeneration(any())).thenReturn(SERVING_GENERATION);
    when(indexing.captureRebuildGeneration(any())).thenReturn(SERVING_GENERATION);
    when(indexing.captureIndexTarget(any())).thenReturn(target);

    AtomicInteger producerStarts = new AtomicInteger();
    AtomicInteger migrationStarts = new AtomicInteger();
    AtomicBoolean restartObserved = new AtomicBoolean();
    AtomicReference<String> acceptedKey = new AtomicReference<>();
    var attachment = coordinator.attach(queue, () -> Optional.of(SERVING_GENERATION), () -> true,
        () -> Optional.of(runtime.get()));
    AtomicReference<JobQueue.IndexJob> unfinishedClaim = new AtomicReference<>();
    try {
    RecordedIngestionCoordinator.Producer producer = (scope, key, epoch, context, cancellation) -> {
      producerStarts.incrementAndGet();
      acceptedKey.set(key);
      assertEquals(SERVING_GENERATION, scope.generation());
      assertEquals(1, scope.roots().size());
      assertEquals(watchedRoot, scope.roots().getFirst().path());
      queue.enqueueRecordedEntries(key, epoch, List.of(new JobQueue.EnqueueEntry(member,
          memberSize, new JobQueue.EnqueueProvenance("user", TransportTag.BUTTON.name()), sourceHash)), null);
      return java.util.concurrent.CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
    };
    Runnable restart = () -> {
      String key = acceptedKey.get();
      assertNotNull(key, "Restart must follow a completed captured walk");
      OperationRecord row = operations.find(key).orElseThrow();
      BulkReindexProgress progress = operations.bulkReindexProgress(row.id()).orElseThrow();
      assertEquals(BulkReindexProgress.Phase.BUILDING, progress.phase(),
          "The exact target and COMPLETE capture must be durable before requesting the restart");
      assertEquals("g-" + key, progress.generationId());
      assertEquals(target, progress.target());
      assertEquals(1, progress.capture().plannedUnits());
      JobQueue.WalkProgress walk = queue.recordedWalk(key).orElseThrow();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, walk.enumerationOutcome());
      assertEquals(1L, walk.plannedUnits());
      assertEquals(0, walk.acknowledgedRevision());
      assertTrue(queue.pollPending(1).isEmpty(), "No captured member may be claimed before the restart boundary");
      restartObserved.set(true);
    };
    coordinator.bindBulkProducer(producer, indexing, restart);

    doAnswer(call -> {
      migrationStarts.incrementAndGet();
      String key = call.getArgument(0);
      assertEquals(RecordedBulkPlan.Profile.USER_BULK.defaultSource(), call.getArgument(1));
      assertEquals(target.fingerprint(), call.getArgument(2));
      assertEquals(SERVING_GENERATION, call.getArgument(3));
      return new IndexingService.MigrationOutcome(true, true, SERVING_GENERATION,
          "g-" + key, "MIGRATING");
    }).when(indexing).startRecordedMigration(anyString(), anyString(), anyString(), anyString(), any());

    String arguments = "{\"corpusIds\":[\"docs\"]}";
    EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
        "recorded-bulk-test", Optional.of("bulk-session"), Optional.empty(), TransportTag.BUTTON,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
    var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI, Instant.now(CLOCK), Optional.empty());
    Operation operation = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.BULK_REINDEX.value())
        .orElseThrow();
    var handler = new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK, coordinator,
        ignored -> List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(watchedRoot, "docs")),
        () -> indexing, List::of);
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.BULK_REINDEX, handler);
    ConsentCapsuleService capsules = new ConsentCapsuleService();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, null, Map.of(), CLOCK,
        new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, capsules);
    String key = OperationKeys.generate(CLOCK);
    acceptedKey.set(key);
    var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments, provenance,
        origin, key, true);
    assertEquals(key, prepared.operationKey());
    assertNotNull(prepared.preparationNonce());
    var rowAbsentBeforeApproval = operations.find(key);
    assertTrue(rowAbsentBeforeApproval.isEmpty(), "Preparation must not accept the operation or create effects");
    String approval = capsules.mintPrepared(operation.id().value(), arguments,
        SourceTier.valueOf(origin.sourceTier()), key, prepared.preparationNonce());

    OperationResult accepted = executor.dispatch(operation, arguments, provenance, Optional.of(approval),
        origin, key, prepared.preparationNonce());
    assertTrue(accepted.success());
    assertEquals(key, accepted.structuredData().get("operationKey"));
    OperationRecord running = operations.find(key).orElseThrow();
    assertEquals(OperationState.RUNNING, running.state(),
        "Unexpected bulk terminal state: receipt=" + running.receipt() + ", failureReason=" + running.failureReason());
    assertInstanceOfContinuation(running, key, prepared.preparationNonce());
    assertEquals(1, producerStarts.get());
    assertEquals(1, migrationStarts.get());
    assertTrue(restartObserved.get());

    // A successful restart request does not change the attachment's boot witness. Simulate the
    // replacement lifecycle explicitly before the new physical binding can authorize claims.
    attachment.close();
    runtime.set(buildingRuntime(key));
    attachment = coordinator.attach(queue, () -> Optional.of(SERVING_GENERATION), () -> true,
        () -> Optional.of(runtime.get()));
    coordinator.bindBulkProducer(producer, indexing, restart);
    coordinator.maintain();
    assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE, coordinator.recordedClaimDecision(key));
    var claim = queue.pollPending(1).getFirst();
    unfinishedClaim.set(claim);
    assertEquals(key, acceptedKey.get());
    assertEquals(sourceHash, claim.plannedSourceSha256());
    assertTrue(claim.recordedForce());
    if (sourceChangedAfterCapture) Files.writeString(member, "source changed after frozen capture");
    String committedHash = sha256(Files.readAllBytes(member));
    queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, committedHash)),
        IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
    unfinishedClaim.set(null);

    AtomicBoolean promotionReached = new AtomicBoolean();
    assertNull(coordinator.promoteRecordedGeneration(key, queue, () -> {
      BulkReindexProgress atPromotion = operations.bulkReindexProgress(running.id()).orElseThrow();
      assertEquals(BulkReindexProgress.Phase.SETTLED, atPromotion.phase(),
          "The owner must persist settlement before invoking the physical promotion");
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state(),
          "Promotion cannot precede the terminal operation receipt");
      promotionReached.set(true);
      return null;
    }));
    assertTrue(promotionReached.get(), "The exact physical promotion callback runs only after settlement");
    BulkReindexProgress settled = operations.bulkReindexProgress(running.id()).orElseThrow();
    assertEquals(BulkReindexProgress.Phase.SETTLED, settled.phase());
    assertTrue(settled.settlement().revision() > 0);
    assertEquals(1, settled.capture().plannedUnits());
    assertEquals(1, operations.find(key).orElseThrow().unitsCompleted());
    assertTrue(settled.settlement().gaps().isEmpty());
    assertEquals(0, settled.settlement().failedEvents());
    assertEquals(sourceChangedAfterCapture ? 1 : 0, settled.settlement().supersededEvents());
    if (sourceChangedAfterCapture) {
      assertEquals(1, settled.settlement().processingHistory().size());
      var history = settled.settlement().processingHistory().getFirst();
      assertEquals("INDEXED", history.coverage());
      assertEquals(sourceHash, history.plannedSourceSha256());
      assertEquals(committedHash, history.contentHash());
    } else {
      assertTrue(settled.settlement().processingHistory().isEmpty(),
          "Stable successful coverage does not add a superseded history event");
    }
    assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state(),
        "A durable settlement is still not a terminal operation receipt");

    // The old process can observe current.json's promoted target. It cannot finish while it has
    // not observed the boot that opened the target writer, or if that physical writer binding is absent.
    runtime.set(promotedRuntime(key, false, "g-" + key));
    coordinator.maintain();
    assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
    assertFalse(coordinator.recordedCutoverReady(key));

    runtime.set(promotedRuntime(key, true, null));
    coordinator.maintain();
    assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state(),
        "A promoted pointer without the exact writable binding cannot produce terminal success");

    runtime.set(promotedRuntime(key, true, "g-" + key));
    coordinator.maintain();
    assertEquals(OperationState.COMPLETE, operations.find(key).orElseThrow().state());
    JobQueue.WalkProgress unacknowledged = queue.recordedWalk(key).orElseThrow();
    assertTrue(unacknowledged.sealedAt() != null);
    assertTrue(unacknowledged.acknowledgedRevision() < unacknowledged.revision(),
        "A failed queue ACK must leave the durable terminal settlement discoverable");
    assertEquals(List.of(key), queue.unacknowledgedCapturedWalkKeys(null, 1));

    rejectQueueAcknowledgement.set(false);
    coordinator.maintain();
    JobQueue.WalkProgress acknowledged = queue.recordedWalk(key).orElseThrow();
    assertEquals(acknowledged.revision(), acknowledged.acknowledgedRevision(),
        "The queue-owned inventory must repair terminal-before-ACK after the acknowledgement path recovers");
    assertTrue(queue.unacknowledgedCapturedWalkKeys(null, 1).isEmpty());
    verify(indexing, times(1)).startRecordedMigration(anyString(), anyString(), anyString(), anyString(), any());

    } finally {
      JobQueue.IndexJob unfinished = unfinishedClaim.get();
      if (unfinished != null && queue.hasIssuedRecordedClaims(acceptedKey.get())) {
        queue.returnUnfinishedClaims(List.of(unfinished));
      }
      coordinator.stopProducers(1_000);
      attachment.close();
    }
    }
  }

  @Test
  void promotedBulkWaitsForReplaySettlementBeforeCompletingExactlyOnce() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("promoted-replay-settlement"))) {
      harness.completeOneCapturedClaim();
      assertEquals(BulkReindexProgress.Phase.SETTLED, harness.progress().phase());
      var queueCounts = harness.queue.jobStateCountsStrict();
      assertEquals(0, queueCounts.processingCount());
      assertEquals(0, queueCounts.pendingReadyCount());
      assertEquals(0, queueCounts.pendingBackoffCount());

      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, false));
      harness.coordinator.maintain();
      assertEquals(OperationState.RUNNING, harness.operations.find(harness.key).orElseThrow().state(),
          "a promoted row cannot finish before replay settlement is observed");

      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, true));
      harness.coordinator.maintain();
      OperationRecord completed = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.COMPLETE, completed.state());
      assertEquals("SUCCESS", completed.receipt().code());

      harness.coordinator.maintain();
      OperationRecord stillCompleted = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.COMPLETE, stillCompleted.state());
      assertEquals("SUCCESS", stillCompleted.receipt().code());
      assertEquals(1, stillCompleted.unitsCompleted());
      assertAcknowledged(harness.queue, harness.key);
    }
  }

  @Test
  void unsupersededGapCannotAuthorizeRecordedPromotion() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("gap-awaiting-acceptance"))) {
      harness.replacePhysical(buildingRuntime(harness.key));
      harness.coordinator.maintain();
      JobQueue.IndexJob claim = harness.queue.pollPending(1).getFirst();
      harness.queue.markClaimFailed(claim, IngestionOutcome.of(
          IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE), null);

      assertEquals(RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE,
          harness.coordinator.recordedGapDecision(harness.key,
              new RecordedIngestionLifecycle.JournalWitness(List.of(), Set.of())),
          "the monitor must seal the gap before entering successor preparation");
      assertFalse(harness.coordinator.beforeRecordedPromotion(harness.key, harness.queue),
          "an unsuperseded accepted unit must wait for a keyed gap decision before pointer B");
      assertEquals(OperationState.COMPLETE_WITH_GAPS,
          harness.operations.find(harness.key).orElseThrow().state());
      assertEquals("awaiting_acceptance",
          harness.operations.outcome(harness.key).phase());
      assertEquals(1, harness.progress().settlement().gaps().size());
      assertEquals(0, harness.promotions.get());

      String hash = harness.progress().gapsListHash();
      assertEquals(hash, harness.operations.outcome(harness.key).result().gapListHash());
      harness.closeOwner();
      harness.runtime.set(buildingRuntime(harness.key));
      harness.openOwner(true, false);
      assertEquals(OperationState.COMPLETE_WITH_GAPS,
          harness.operations.find(harness.key).orElseThrow().state(),
          "recovery must keep the durable user wait visible before the monitor resumes");
      assertEquals(RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE,
          harness.coordinator.recordedGapDecision(harness.key),
          "an interrupted user wait resumes the same durable gap list and candidate");
      assertEquals(OperationStore.BulkGapAcceptance.GAP_LIST_STALE,
          harness.operations.acceptBulkGaps(harness.key, "f".repeat(64), "webview-user"));
      assertEquals(OperationState.COMPLETE_WITH_GAPS,
          harness.operations.find(harness.key).orElseThrow().state());
      assertEquals(OperationStore.BulkGapAcceptance.ACCEPTED,
          harness.operations.acceptBulkGaps(harness.key, hash, "webview-user"));
      assertEquals(OperationState.RUNNING,
          harness.operations.find(harness.key).orElseThrow().state());
      assertTrue(harness.coordinator.beforeRecordedPromotion(harness.key, harness.queue),
          "the exact keyed gap decision may resume the same accepted promotion");

      var laterFailure = new OperationOutcomeView.Gap("b".repeat(64),
          "CANDIDATE_PROJECTION_MISSING");
      assertEquals(RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE,
          harness.coordinator.recordedGapDecision(harness.key,
              new RecordedIngestionLifecycle.JournalWitness(List.of(laterFailure), Set.of())),
          "a later failed candidate projection must revoke the old approval");
      String refreshedHash = harness.operations.outcome(harness.key).result().gapListHash();
      assertFalse(hash.equals(refreshedHash));
      assertEquals(OperationState.COMPLETE_WITH_GAPS,
          harness.operations.find(harness.key).orElseThrow().state());
      assertFalse(harness.coordinator.beforeRecordedPromotion(harness.key, harness.queue));
      assertEquals(OperationStore.BulkGapAcceptance.GAP_LIST_STALE,
          harness.operations.acceptBulkGaps(harness.key, hash, "webview-user"));
      assertEquals(OperationStore.BulkGapAcceptance.ACCEPTED,
          harness.operations.acceptBulkGaps(harness.key, refreshedHash, "webview-user"));
      String capturedUnit = harness.progress().settlement().gaps().getFirst().unitId();
      assertEquals(RecordedIngestionLifecycle.GapDecision.NONE,
          harness.coordinator.recordedGapDecision(harness.key,
              new RecordedIngestionLifecycle.JournalWitness(List.of(),
                  Set.of(capturedUnit, laterFailure.unitId()))),
          "later exact success supersedes both failed projection obligations");
      assertEquals(OperationStore.BulkGapAcceptance.NOT_AWAITING,
          harness.operations.acceptBulkGaps(harness.key, refreshedHash, "webview-user"));
      assertTrue(harness.coordinator.beforeRecordedPromotion(harness.key, harness.queue));
      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, true));
      harness.coordinator.maintain();
      assertEquals("SUCCESS", harness.operations.find(harness.key).orElseThrow().receipt().code(),
          "covered captured and candidate gaps cannot leave a false promoted-with-gaps receipt");
    }
  }

  @Test
  void candidateOnlyGapControlsPromotedReceiptAndFencedApprovalRejectsStaleHash()
      throws Exception {
    try (var harness = new BulkHarness(temp.resolve("candidate-only-gap"))) {
      harness.completeOneCapturedClaim();
      var lateGap = new OperationOutcomeView.Gap("unit:candidate", "CANDIDATE_PROJECTION_MISSING");
      var witness = new RecordedIngestionLifecycle.JournalWitness(List.of(lateGap), Set.of());
      assertEquals(RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE,
          harness.coordinator.recordedGapDecision(harness.key, witness));
      String firstHash = harness.operations.outcome(harness.key).result().gapListHash();
      EngineContext webview = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
          "gap-owner", Optional.of("bulk-session"), Optional.empty(), TransportTag.BUTTON,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      assertFalse(harness.coordinator.acceptGaps(harness.key, firstHash, webview).success(),
          "an attachment without the physical witness fence cannot authorize approval");
      var changedGap = new OperationOutcomeView.Gap("unit:later", "PROJECTION_WITNESS_MISSING");
      var latest = new RecordedIngestionLifecycle.JournalWitness(List.of(lateGap, changedGap), Set.of());
      var fenceCalls = new AtomicInteger();
      harness.replacePhysical(buildingRuntime(harness.key), decision -> {
        fenceCalls.incrementAndGet();
        return decision.apply(latest);
      });
      harness.runtime.set(new RecordedIngestionLifecycle.BulkRuntime(
          IndexGenerationManager.BootDisposition.FENCED, SERVING_GENERATION,
          "g-" + harness.key, "AWAITING_ACCEPTANCE", null, false));
      assertFalse(harness.coordinator.acceptGaps(harness.key, firstHash, webview).success(),
          "a fenced generation cannot borrow an old gap witness");
      harness.runtime.set(buildingRuntime(harness.key));
      OperationResult stale = harness.coordinator.acceptGaps(harness.key, firstHash, webview);
      assertFalse(stale.success());
      assertEquals("GAP_LIST_STALE", stale.errorCode().orElseThrow());
      String currentHash = harness.operations.outcome(harness.key).result().gapListHash();
      assertFalse(firstHash.equals(currentHash));
      assertTrue(harness.coordinator.acceptGaps(harness.key, currentHash, webview).success());
      assertEquals(3, fenceCalls.get(), "every decision requires a fresh physical witness");
      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, true));
      harness.coordinator.maintain();
      OperationRecord terminal = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals("PROMOTED_WITH_GAPS", terminal.receipt().code(),
          "candidate-only approved gaps must not finish as full success");
    }
  }

  @Test
  void exhaustedBulkRecoveryPersistsRefusalAndExactAcknowledgementWithoutAnotherAttempt() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("exhausted-recovery"))) {
      OperationRecord firstAttempt = harness.operations.find(harness.key).orElseThrow();
      assertEquals(1, firstAttempt.attempts());
      assertNull(harness.progress().refusalCode(), "exhaustion recovery starts without a prior refusal witness");
      harness.closeOwner();

      // Model two earlier durable process resumes using the store's supported transition. The
      // replacement runner then observes a genuinely RUNNING row at its persisted attempt limit.
      try (var spentAttempts = new SqliteOperationStore(harness.directory.resolve("operations.db"))) {
        assertTrue(spentAttempts.resume(firstAttempt.id()));
        assertTrue(spentAttempts.resume(firstAttempt.id()));
        assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS,
            spentAttempts.find(harness.key).orElseThrow().attempts());
      }

      harness.runtime.set(new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.FENCED,
          SERVING_GENERATION, null, "IDLE", null, false));
      harness.openOwner(true, false);

      OperationRecord refused = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.FAILED, refused.state());
      assertEquals(RecordedIngestionSettlement.EXHAUSTED, refused.receipt().code());
      assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS, refused.attempts(),
          "exhaustion reconciliation checkpoints and terminalizes without spending another attempt");
      BulkReindexProgress progress = harness.progress();
      assertEquals(RecordedIngestionSettlement.EXHAUSTED, progress.refusalCode());
      assertEquals(BulkReindexProgress.Phase.SETTLED, progress.phase());
      assertEquals(1, progress.settlement().gaps().size());
      assertEquals(1, harness.producerStarts.get());
      assertEquals(1, harness.migrationStarts.get());
      assertEquals(JobQueue.RecordedClaimDecision.DENY, harness.coordinator.recordedClaimDecision(harness.key));
      assertTrue(harness.queue.pollPending(1).isEmpty());
      assertAcknowledged(harness.queue, harness.key);
    }
  }

  @Test
  void acceptedBulkWaitsForReplacementWhenOldBootIsFencedAndRetriesFailedRestartCallback() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("accepted-start-handoff"), true)) {
      assertEquals(1, harness.initialRestartFailures.get());
      assertEquals(1, harness.restartCalls.get());
      assertEquals(1, harness.migrationStarts.get());

      // The accepted process still owns the old attachment, whose live state can report FENCED
      // after it wrote the requested target. That observation cannot refuse the accepted start.
      harness.runtime.set(new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.FENCED,
          SERVING_GENERATION, null, "IDLE", null, false));
      JobQueue.WalkProgress beforeRetry = harness.queue.recordedWalk(harness.key).orElseThrow();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, beforeRetry.enumerationOutcome());
      assertEquals(1L, beforeRetry.plannedUnits());
      assertNull(beforeRetry.sealedAt());

      harness.coordinator.maintain();
      assertEquals(2, harness.restartCalls.get(), "maintenance retries the failed accepted restart callback");
      assertEquals(1, harness.migrationStarts.get(), "dispatch retry does not repeat the accepted migration");
      assertEquals(OperationState.RUNNING, harness.operations.find(harness.key).orElseThrow().state());
      assertEquals(BulkReindexProgress.Phase.BUILDING, harness.progress().phase());
      assertEquals("g-" + harness.key, harness.progress().generationId());
      assertNull(harness.progress().refusalCode(), "the old physical FENCED witness is not a start refusal");
      assertEquals(JobQueue.RecordedClaimDecision.DENY,
          harness.coordinator.recordedClaimDecision(harness.key), "the old physical binding cannot claim Green");
      assertTrue(harness.queue.pollPending(1).isEmpty());

      harness.coordinator.maintain();
      assertEquals(2, harness.restartCalls.get(), "an armed handoff waits without repeating its callback");
      assertEquals(1, harness.migrationStarts.get());
      assertEquals(OperationState.RUNNING, harness.operations.find(harness.key).orElseThrow().state());
      assertNull(harness.progress().refusalCode());
      assertNull(harness.queue.recordedWalk(harness.key).orElseThrow().sealedAt());

      harness.replacePhysical(buildingRuntime(harness.key));
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE,
          harness.coordinator.recordedClaimDecision(harness.key), "the replacement BUILDING binding can claim");
      JobQueue.IndexJob claim = harness.queue.pollPending(1).getFirst();
      assertEquals(harness.sourceHash, claim.plannedSourceSha256());
      harness.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, harness.sourceHash)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
    }
  }

  @Test
  void acknowledgementRepairCrossesFullPageOfRetainedContradictionsToLaterValidTerminalRow() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("ack-inventory-pages"))) {
      harness.replacePhysical(buildingRuntime(harness.key));
      harness.coordinator.maintain();
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE,
          harness.coordinator.recordedClaimDecision(harness.key));
      JobQueue.IndexJob claim = harness.queue.pollPending(1).getFirst();
      assertEquals(harness.sourceHash, claim.plannedSourceSha256());

      // Keep fixture writes from repeatedly invoking the repair pass while constructing its exact
      // 256-row first page. Operation rows are created first, before any contradiction is visible
      // to the queue-owned inventory.
      harness.suppressWalkNotifications.set(true);
      Clock earlierKeys = Clock.fixed(CLOCK.instant().minusSeconds(60), CLOCK.getZone());
      var contradictions = new java.util.ArrayList<String>();
      for (int index = 0; index < 256; index++) {
        String key = OperationKeys.generate(earlierKeys);
        contradictions.add(key);
        harness.seedTerminalBulkWithoutPreparation(key);
      }
      for (String key : contradictions) {
        harness.seedContradictoryCapturedWalk(key);
      }
      assertTrue(harness.key.compareTo(contradictions.getLast()) > 0,
          "the valid terminal row must sort after the retained contradictory first page");
      harness.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(
          claim, null, harness.sourceHash)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
      assertTrue(harness.coordinator.beforeRecordedPromotion(harness.key, harness.queue));
      var firstPage = harness.queue.unacknowledgedCapturedWalkKeys(null, 256);
      var expectedFirstPage = new java.util.ArrayList<>(contradictions);
      expectedFirstPage.sort(String::compareTo);
      assertEquals(expectedFirstPage, firstPage, "all retained contradictions occupy page one");
      assertEquals(List.of(harness.key), harness.queue.unacknowledgedCapturedWalkKeys(firstPage.getLast(), 256),
          "the valid terminal candidate is reachable only from page two");
      JobQueue.WalkProgress sealedWalk = harness.queue.recordedWalk(harness.key).orElseThrow();
      long sealedAcknowledgedRevision = sealedWalk.acknowledgedRevision();
      assertTrue(sealedAcknowledgedRevision < sealedWalk.revision());
      var contradictionAcknowledgements = new java.util.HashMap<String, Long>();
      for (String key : List.of(contradictions.getFirst(), contradictions.getLast())) {
        contradictionAcknowledgements.put(key,
            harness.queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
      }

      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key));
      harness.suppressWalkNotifications.set(false);
      harness.rejectAcknowledgement.set(true);
      harness.coordinator.maintain();

      assertEquals(OperationState.COMPLETE, harness.operations.find(harness.key).orElseThrow().state());
      JobQueue.WalkProgress terminalWalk = harness.queue.recordedWalk(harness.key).orElseThrow();
      assertEquals(sealedAcknowledgedRevision, terminalWalk.acknowledgedRevision(),
          "the injected exact-ACK refusal leaves the terminal row for the inventory-only repair pass");
      assertEquals(List.of(harness.key), harness.queue.unacknowledgedCapturedWalkKeys(
          firstPage.getLast(), 256));

      harness.rejectAcknowledgement.set(false);
      harness.coordinator.maintain();
      terminalWalk = harness.queue.recordedWalk(harness.key).orElseThrow();
      assertEquals(terminalWalk.revision(), terminalWalk.acknowledgedRevision());
      var retained = harness.queue.unacknowledgedCapturedWalkKeys(null, 256);
      assertEquals(256, retained.size(), "contradictory terminal bindings remain discoverable");
      assertFalse(retained.contains(harness.key), "repair continues beyond the retained first page");
      assertTrue(harness.queue.unacknowledgedCapturedWalkKeys(retained.getLast(), 256).isEmpty());
      for (String key : List.of(contradictions.getFirst(), contradictions.getLast())) {
        var walk = harness.queue.recordedWalk(key).orElseThrow();
        assertEquals(contradictionAcknowledgements.get(key), walk.acknowledgedRevision(),
            "a contradictory row cannot release captured evidence");
        assertTrue(harness.operations.find(key).orElseThrow().state().terminal());
        assertTrue(harness.operations.acceptedPreparation(
            harness.operations.find(key).orElseThrow().id()).isEmpty());
      }
    }
  }

  @Test
  void cancelledRefusalBeforeQueueRetirementSurvivesRestartUnderRestoredAllowingPolicy() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("cancel-before-retirement"))) {
      OperationRecord before = harness.operations.find(harness.key).orElseThrow();
      long attempts = before.attempts();
      harness.failRetirement.set(true);
      harness.cancellableWork.get().cancel("cancel before queue retirement");

      BulkReindexProgress refused = harness.progress();
      assertEquals(BulkReindexProgress.Phase.BUILDING, refused.phase());
      assertEquals("cancelled", refused.refusalCode());
      assertTrue(harness.coordinator.recordedPrecommitRefused(harness.key),
          "the durable refusal fences Worker restoration before terminal receipt");
      assertEquals(OperationState.RUNNING, harness.operations.find(harness.key).orElseThrow().state());
      JobQueue.WalkProgress open = harness.queue.recordedWalk(harness.key).orElseThrow();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, open.enumerationOutcome());
      assertNull(open.sealedAt(), "the injected cut is after durable refusal but before queue retirement");
      assertEquals(JobQueue.RecordedClaimDecision.DENY, harness.coordinator.recordedClaimDecision(harness.key));

      harness.reopen();
      OperationRecord cancelled = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.CANCELLED, cancelled.state());
      assertEquals("cancelled", cancelled.receipt().code());
      assertEquals(attempts, cancelled.attempts(), "refusal reconciliation does not spend another attempt");
      assertEquals("cancelled", harness.progress().refusalCode());
      assertTrue(harness.coordinator.recordedPrecommitRefused(harness.key));
      assertEquals(BulkReindexProgress.Phase.SETTLED, harness.progress().phase());
      assertEquals(1, harness.progress().settlement().gaps().size());
      assertEquals(1, harness.producerStarts.get(), "restored allowing policy cannot restart a refused walk");
      assertEquals(1, harness.migrationStarts.get(), "a durable cancellation cannot start another generation");
      assertEquals(JobQueue.RecordedClaimDecision.DENY, harness.coordinator.recordedClaimDecision(harness.key));
      assertTrue(harness.queue.pollPending(1).isEmpty());
      assertAcknowledged(harness.queue, harness.key);
      assertNull(harness.coordinator.promoteRecordedGeneration(harness.key, harness.queue, () -> {
        harness.promotions.incrementAndGet();
        return null;
      }));
      assertEquals(0, harness.promotions.get());
    }
  }

  @Test
  void cancellationAfterSettledRefusalCheckpointRecoversTerminalReceiptAndExactQueueAck() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("cancel-after-settled"))) {
      var claim = harness.completeOneCapturedClaim();
      assertEquals(1, harness.operations.find(harness.key).orElseThrow().unitsCompleted());
      assertEquals(BulkReindexProgress.Phase.SETTLED, harness.progress().phase());
      long attempts = harness.operations.find(harness.key).orElseThrow().attempts();

      harness.failFinishAfterRefusal.set(true);
      harness.cancellableWork.get().cancel("cancel after settlement");

      OperationRecord pendingTerminal = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.RUNNING, pendingTerminal.state(),
          "the injected crash cut leaves the durable cancellation witness before terminal persistence");
      assertEquals(attempts, pendingTerminal.attempts());
      BulkReindexProgress refused = harness.progress();
      assertEquals(BulkReindexProgress.Phase.SETTLED, refused.phase());
      assertEquals("cancelled", refused.refusalCode());
      assertEquals(harness.sourceHash, claim.plannedSourceSha256());
      assertEquals(0, refused.settlement().gaps().size());
      assertEquals(refused.settlement().revision(), harness.queue.recordedWalk(harness.key).orElseThrow().revision());

      harness.reopen();
      OperationRecord cancelled = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.CANCELLED, cancelled.state());
      assertEquals("cancelled", cancelled.receipt().code());
      assertEquals(attempts, cancelled.attempts(), "recovery terminalizes from the refusal witness without a new attempt");
      assertEquals(BulkReindexProgress.Phase.SETTLED, harness.progress().phase());
      assertEquals("cancelled", harness.progress().refusalCode());
      assertEquals(1, cancelled.unitsCompleted());
      assertEquals(1, harness.producerStarts.get());
      assertEquals(1, harness.migrationStarts.get());
      assertEquals(JobQueue.RecordedClaimDecision.DENY, harness.coordinator.recordedClaimDecision(harness.key));
      assertTrue(harness.queue.pollPending(1).isEmpty());
      assertAcknowledged(harness.queue, harness.key);
      assertNull(harness.coordinator.promoteRecordedGeneration(harness.key, harness.queue, () -> {
        harness.promotions.incrementAndGet();
        return null;
      }));
      assertEquals(0, harness.promotions.get());
    }
  }

  @Test
  void cancellationWithNoIssuedClaimsFinishesWithoutAnExternalQueueEventAndRetriesRefusalRestartOnce()
      throws Exception {
    try (var harness = new BulkHarness(temp.resolve("cancel-no-issued-claims"))) {
      long attempts = harness.operations.find(harness.key).orElseThrow().attempts();
      assertFalse(harness.queue.hasIssuedRecordedClaims(harness.key));
      assertTrue(harness.queue.unacknowledgedCapturedWalkKeys(null, 1).isEmpty());
      harness.failRefusalRestartOnce.set(true);

      harness.cancellableWork.get().cancel("cancel without issued claims");

      OperationRecord cancelled = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.CANCELLED, cancelled.state(),
          "the cancellation callback must finish without a later queue or maintenance event");
      assertEquals("cancelled", cancelled.receipt().code());
      assertEquals(attempts, cancelled.attempts());
      assertAcknowledged(harness.queue, harness.key);
      assertTrue(harness.queue.pollPending(1).isEmpty());
      assertEquals(2, harness.restartCalls.get(),
          "the terminal refusal restart is attempted after the initial migration restart");
      assertEquals(0, harness.refusalRestartSuccesses.get());

      harness.coordinator.maintain();
      assertEquals(3, harness.restartCalls.get(), "a failed refusal restart is retried on the next maintenance pass");
      assertEquals(1, harness.refusalRestartSuccesses.get());
      harness.coordinator.maintain();
      assertEquals(3, harness.restartCalls.get(), "one successful refusal restart is not repeated");
      harness.replacePhysical(new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.FENCED,
          SERVING_GENERATION, null, "IDLE", null, false));
      assertEquals(IndexGenerationManager.BootDisposition.FENCED, harness.runtime.get().disposition());
      assertEquals(1, harness.producerStarts.get());
      assertEquals(1, harness.migrationStarts.get());
      assertFalse(harness.coordinator.recordedCutoverReady(harness.key));
    }
  }

  @Test
  void cancellationBeforePromotionActionSuppressesAction() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("cancel-before-promotion-action"))) {
      harness.cancellableWork.get().cancel("cancel before promotion entry");
      AtomicInteger actions = new AtomicInteger();
      assertNull(harness.coordinator.promoteRecordedGeneration(harness.key, harness.queue, () -> {
        actions.incrementAndGet();
        return null;
      }));
      assertEquals(0, actions.get(), "durable cancellation prevents entering the promotion effect");
      assertEquals(OperationState.CANCELLED, harness.operations.find(harness.key).orElseThrow().state());
      assertEquals("cancelled", harness.progress().refusalCode());
    }
  }

  @Test
  void cancellationOverlappingEnteredPromotionLinearizesAfterCommittedEffect() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("cancel-during-promotion"))) {
      harness.completeOneCapturedClaim();
      CountDownLatch promotionEntered = new CountDownLatch(1);
      CountDownLatch releasePromotion = new CountDownLatch(1);
      CountDownLatch promotionFinished = new CountDownLatch(1);
      CountDownLatch cancellationReturned = new CountDownLatch(1);
      AtomicReference<Throwable> threadFailure = new AtomicReference<>();
      AtomicInteger effects = new AtomicInteger();

      Thread promoter = new Thread(() -> {
        try {
          harness.coordinator.promoteRecordedGeneration(harness.key, harness.queue, () -> {
            effects.incrementAndGet();
            promotionEntered.countDown();
            try {
              if (!releasePromotion.await(5, TimeUnit.SECONDS)) {
                throw new java.io.IOException("promotion release timed out");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException("promotion test interrupted", interrupted);
            }
            harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, false));
            return new IndexGenerationManager.State(1, "g-" + harness.key, null,
                SERVING_GENERATION, "IDLE", false, null, null, System.currentTimeMillis(),
                null, null, null);
          });
        } catch (Throwable failure) {
          threadFailure.compareAndSet(null, failure);
        } finally {
          promotionFinished.countDown();
        }
      }, "recorded-bulk-promotion-test");
      promoter.start();
      assertTrue(promotionEntered.await(5, TimeUnit.SECONDS), "promotion action must be entered");

      Thread canceller = new Thread(() -> {
        try {
          harness.cancellableWork.get().cancel("cancel overlaps committed promotion");
        } catch (Throwable failure) {
          threadFailure.compareAndSet(null, failure);
        } finally {
          cancellationReturned.countDown();
        }
      }, "recorded-bulk-cancellation-test");
      canceller.start();
      assertTrue(harness.cancellationPublished.await(5, TimeUnit.SECONDS), "cancellation must be published");
      assertFalse(cancellationReturned.await(100, TimeUnit.MILLISECONDS),
          "cancellation waits for an already-entered promotion effect to finish");
      assertEquals(OperationState.RUNNING, harness.operations.find(harness.key).orElseThrow().state());
      assertNull(harness.progress().refusalCode(), "the refusal witness follows the in-flight committed effect");

      releasePromotion.countDown();
      assertTrue(promotionFinished.await(5, TimeUnit.SECONDS));
      assertTrue(cancellationReturned.await(5, TimeUnit.SECONDS));
      promoter.join(5_000);
      canceller.join(5_000);
      assertNull(threadFailure.get());
      assertEquals(1, effects.get(), "the entered physical promotion completes exactly once");
      OperationRecord committed = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.RUNNING, committed.state(),
          "committed B waits for replay settlement despite a late cancellation");
      assertEquals(BulkReindexProgress.Phase.SETTLED, harness.progress().phase());
      assertNull(harness.progress().refusalCode());
      assertFalse(harness.coordinator.recordedPrecommitRefused(harness.key),
          "a committed pointer cannot be classified as precommit refusal");
      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, true));
      harness.coordinator.maintain();
      committed = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.COMPLETE, committed.state());
      assertEquals("SUCCESS", committed.receipt().code());
      assertEquals(1, committed.unitsCompleted());
      assertFalse(harness.coordinator.recordedCutoverReady(harness.key));
      AtomicInteger repeatedEffect = new AtomicInteger();
      assertNull(harness.coordinator.promoteRecordedGeneration(harness.key, harness.queue, () -> {
        repeatedEffect.incrementAndGet();
        return null;
      }));
      assertEquals(0, repeatedEffect.get(), "terminal committed activation cannot promote again");
      assertAcknowledged(harness.queue, harness.key);
    }
  }

  @Test
  void committedPointerReconcilesAfterWatchedRootAuthorityChanges() throws Exception {
    try (var harness = new BulkHarness(temp.resolve("committed-policy-change"))) {
      harness.completeOneCapturedClaim();
      harness.closeOwner();
      Files.writeString(harness.authorityDirectory.resolve("watched_roots.json"),
          "{\"schemaVersion\":1,\"roots\":[]}");
      harness.runtime.set(promotedRuntime(harness.key, true, "g-" + harness.key, true));
      harness.openOwner(true, false, false);

      OperationRecord committed = harness.operations.find(harness.key).orElseThrow();
      assertEquals(OperationState.COMPLETE, committed.state());
      assertEquals("SUCCESS", committed.receipt().code());
      assertEquals(1, harness.migrationStarts.get(), "committed recovery cannot start another build");
    }
  }

  private static void assertAcknowledged(JobQueue queue, String key) {
    JobQueue.WalkProgress walk = queue.recordedWalk(key).orElseThrow();
    assertNotNull(walk.sealedAt());
    assertEquals(walk.revision(), walk.acknowledgedRevision());
    assertTrue(queue.unacknowledgedCapturedWalkKeys(null, 1).isEmpty());
  }

  private final class BulkHarness implements AutoCloseable {
    final Path directory;
    final Path watchedRoot;
    final Path member;
    final long memberSize;
    final String sourceHash;
    final IndexTargetSnapshot target;
    final Path authorityDirectory;
    final AtomicReference<RecordedIngestionCoordinator> coordinatorRef = new AtomicReference<>();
    final AtomicReference<RecordedIngestionLifecycle.BulkRuntime> runtime = new AtomicReference<>();
    final AtomicReference<EngineWorkHandle> cancellableWork = new AtomicReference<>();
    final AtomicReference<EngineWorkHandle.Registration> cancellationSignal = new AtomicReference<>();
    final CountDownLatch cancellationPublished = new CountDownLatch(1);
    final AtomicBoolean failRetirement = new AtomicBoolean();
    final AtomicBoolean rejectAcknowledgement = new AtomicBoolean();
    final AtomicBoolean failFinishAfterRefusal = new AtomicBoolean();
    final AtomicBoolean failRefusalRestartOnce = new AtomicBoolean();
    final AtomicBoolean failInitialRestartOnce = new AtomicBoolean();
    final AtomicBoolean initialRestartPending = new AtomicBoolean(true);
    final AtomicBoolean suppressWalkNotifications = new AtomicBoolean();
    final AtomicInteger producerStarts = new AtomicInteger();
    final AtomicInteger migrationStarts = new AtomicInteger();
    final AtomicInteger restartCalls = new AtomicInteger();
    final AtomicInteger refusalRestartSuccesses = new AtomicInteger();
    final AtomicInteger initialRestartFailures = new AtomicInteger();
    final AtomicInteger promotions = new AtomicInteger();
    SqliteOperationStore operations;
    OperationStore operationOwner;
    SqliteJobQueue sqliteQueue;
    JobQueue queue;
    EngineAdmissionController admission;
    OperationAttemptRunnerImpl attempts;
    RecordedIngestionCoordinator coordinator;
    OperationAuthority authority;
    IndexingService indexing;
    RecordedIngestionLifecycle.Attachment attachment;
    String key;
    private final boolean expectInitialRestartFailure;

    BulkHarness(Path directory) throws Exception {
      this(directory, false);
    }

    BulkHarness(Path directory, boolean failInitialRestartOnce) throws Exception {
      this.directory = Files.createDirectories(directory);
      this.expectInitialRestartFailure = failInitialRestartOnce;
      this.failInitialRestartOnce.set(failInitialRestartOnce);
      watchedRoot = Files.createDirectory(directory.resolve("watched"));
      member = Files.writeString(watchedRoot.resolve("one.txt"), "captured cancellation source");
      memberSize = Files.size(member);
      sourceHash = sha256(Files.readAllBytes(member));
      target = new IndexTargetSnapshot(sha256(TARGET_INPUTS), TARGET_INPUTS);
      authorityDirectory = Files.createDirectory(directory.resolve("authority"));
      Files.writeString(authorityDirectory.resolve("watched_roots.json"),
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
              "schemaVersion", 1, "roots", List.of(Map.of("path", watchedRoot.toAbsolutePath().normalize().toString())))));
      runtime.set(new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.CAPTURING,
          SERVING_GENERATION, null, "IDLE", null, false));
      openOwner(false);
      try { acceptPreparedBulk(); }
      catch (Exception | Error failure) {
        try { closeOwner(); }
        catch (Exception | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        throw failure;
      }
    }

    private void openOwner(boolean recovered) throws Exception {
      openOwner(recovered, recovered);
    }

    private void openOwner(boolean recovered, boolean refusalPersisted) throws Exception {
      openOwner(recovered, refusalPersisted, true);
    }

    private void openOwner(boolean recovered, boolean refusalPersisted,
        boolean expectCurrentAuthorization) throws Exception {
      authority = OperationAuthority.load(authorityDirectory);
      admission = new EngineAdmissionController(4, 8, 1);
      operations = new SqliteOperationStore(directory.resolve("operations.db"));
      if (recovered && expectCurrentAuthorization) {
        OperationRecord accepted = operations.find(key).orElseThrow();
        var acceptedPreparation = operations.acceptedPreparation(accepted.id()).orElseThrow();
        assertInstanceOf(OperationAuthority.RecordedBulkRecoveryDecision.Authorized.class,
            authority.evaluateRecordedBulk(accepted, acceptedPreparation),
            "the restarted fixture restores an allowing policy; the durable refusal still wins");
      }
      operationOwner = operationStoreWithCrashCut(operations);
      attempts = new OperationAttemptRunnerImpl(operationOwner, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS),
          null, new RecordedIngestPlanResolver());
      sqliteQueue = new SqliteJobQueue(directory.resolve("jobs.db"), operationKey -> {
        RecordedIngestionCoordinator current = coordinatorRef.get();
        return current == null ? JobQueue.RecordedClaimDecision.DENY
            : current.recordedClaimDecision(operationKey);
      });
      sqliteQueue.open();
      queue = refusalRetirementGate(acknowledgeGate(sqliteQueue, rejectAcknowledgement,
          suppressWalkNotifications), failRetirement);
      coordinator = new RecordedIngestionCoordinator(operationOwner, attempts, admission, authority);
      coordinatorRef.set(coordinator);
      if (recovered && !expectCurrentAuthorization) {
        var ownership = assertInstanceOf(IndexGenerationManager.BootOwnership.Recorded.class,
            coordinator.bootOwnership(queue));
        assertFalse(ownership.continuationAuthorized(),
            "current scope revocation fences only an uncommitted continuation");
      }
      if (recovered && refusalPersisted) {
        var ownership = assertInstanceOf(IndexGenerationManager.BootOwnership.Recorded.class,
            coordinator.bootOwnership(queue));
        assertFalse(ownership.continuationAuthorized(),
            "a durable cancellation marker fences a precommit rebuild while retaining pointer identity");
      }
      attachment = coordinator.attach(queue, () -> Optional.of(SERVING_GENERATION), () -> true,
          () -> Optional.of(runtime.get()));
      indexing = mock(IndexingService.class);
      when(indexing.captureServingGeneration(any())).thenReturn(SERVING_GENERATION);
      when(indexing.captureRebuildGeneration(any())).thenReturn(SERVING_GENERATION);
      when(indexing.captureIndexTarget(any())).thenReturn(target);
      doAnswer(call -> {
        migrationStarts.incrementAndGet();
        String operationKey = call.getArgument(0);
        assertEquals(key, operationKey);
        assertEquals(RecordedBulkPlan.Profile.USER_BULK.defaultSource(), call.getArgument(1));
        assertEquals(target.fingerprint(), call.getArgument(2));
        assertEquals(SERVING_GENERATION, call.getArgument(3));
        return new IndexingService.MigrationOutcome(true, true, SERVING_GENERATION,
            "g-" + operationKey, "MIGRATING");
      }).when(indexing).startRecordedMigration(anyString(), anyString(), anyString(), anyString(), any());
      bindBulkProducer();
    }

    private void bindBulkProducer() {
      coordinator.bindBulkProducer((scope, operationKey, epoch, context, cancellation) -> {
        producerStarts.incrementAndGet();
        queue.enqueueRecordedEntries(operationKey, epoch, List.of(new JobQueue.EnqueueEntry(member,
            memberSize, new JobQueue.EnqueueProvenance("user", TransportTag.BUTTON.name()), sourceHash)), null);
        return java.util.concurrent.CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      }, indexing, () -> {
        restartCalls.incrementAndGet();
        if (initialRestartPending.get()) {
          if (failInitialRestartOnce.compareAndSet(true, false)) {
            runtime.set(new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.FENCED,
                SERVING_GENERATION, null, "IDLE", null, false));
            initialRestartFailures.incrementAndGet();
            throw new IllegalStateException("injected initial bulk restart callback failure");
          }
          initialRestartPending.set(false);
          return;
        }
        if (failRefusalRestartOnce.compareAndSet(true, false)) {
          throw new IllegalStateException("injected refusal restart cut");
        }
        refusalRestartSuccesses.incrementAndGet();
      });
    }

    private OperationStore operationStoreWithCrashCut(SqliteOperationStore delegate) {
      return (OperationStore) Proxy.newProxyInstance(OperationStore.class.getClassLoader(),
          new Class<?>[] {OperationStore.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("finish") && failFinishAfterRefusal.get()) {
              long id = (Long) arguments[0];
              BulkReindexProgress progress = delegate.bulkReindexProgress(id).orElse(null);
              if (progress != null && progress.phase() == BulkReindexProgress.Phase.SETTLED
                  && "cancelled".equals(progress.refusalCode())
                  && failFinishAfterRefusal.compareAndSet(true, false)) {
                throw new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null);
              }
            }
            try { return method.invoke(delegate, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
          });
    }

    private EngineAdmissionService capturingAdmission() {
      return new EngineAdmissionService() {
        @Override public EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen) {
          return capture(admission.admit(context, allowWhileFrozen));
        }
        @Override public EngineWorkHandle attach(EngineContext context) {
          return capture(admission.attach(context));
        }
        private EngineWorkHandle capture(EngineWorkHandle work) {
          EngineWorkHandle retained = work.retain();
          if (cancellableWork.compareAndSet(null, retained)) {
            cancellationSignal.set(retained.onCancel(ignored -> cancellationPublished.countDown()));
          } else {
            retained.close();
          }
          return work;
        }
        @Override public void cancelInteractive(String reason) { admission.cancelInteractive(reason); }
        @Override public void beginClosing() { admission.beginClosing(); }
        @Override public boolean isClosing() { return admission.isClosing(); }
        @Override public int retryAfterSeconds() { return admission.retryAfterSeconds(); }
        @Override public Limits limits() { return admission.limits(); }
        @Override public int activeWorkCount() { return admission.activeWorkCount(); }
        @Override public boolean awaitDrained(java.time.Duration timeout) {
          return admission.awaitDrained(timeout);
        }
      };
    }

    private void acceptPreparedBulk() throws Exception {
      key = OperationKeys.generate(CLOCK);
      String arguments = "{\"corpusIds\":[\"docs\"]}";
      EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
          "recorded-bulk-cancel-test", Optional.of("bulk-session"), Optional.empty(), TransportTag.BUTTON,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI, Instant.now(CLOCK), Optional.empty());
      Operation operation = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.BULK_REINDEX.value())
          .orElseThrow();
      var handler = new BulkReindexHandler(RecordedBulkPlan.Profile.USER_BULK, coordinator,
          ignored -> List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(watchedRoot, "docs")),
          () -> indexing, List::of);
      HandlerRegistry handlers = new HandlerRegistry();
      handlers.register(CoreOperationCatalog.BULK_REINDEX, handler);
      ConsentCapsuleService capsules = new ConsentCapsuleService();
      var executor = new OperationExecutorImpl(attempts, capturingAdmission(), handlers, null, Map.of(), CLOCK,
          new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, capsules);
      var prepared = (OperationDispatchPlan.Ready) executor.prepare(operation, arguments, provenance,
          origin, key, true);
      String approval = capsules.mintPrepared(operation.id().value(), arguments,
          SourceTier.valueOf(origin.sourceTier()), key, prepared.preparationNonce());
      OperationResult accepted = executor.dispatch(operation, arguments, provenance, Optional.of(approval),
          origin, key, prepared.preparationNonce());
      assertTrue(accepted.success());
      if (expectInitialRestartFailure) assertEquals(1, initialRestartFailures.get());
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
      assertNotNull(cancellableWork.get());
      assertEquals(1, producerStarts.get());
      assertEquals(1, migrationStarts.get());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE,
          queue.recordedWalk(key).orElseThrow().enumerationOutcome());
      assertEquals(BulkReindexProgress.Phase.BUILDING, progress().phase());
    }

    JobQueue.IndexJob completeOneCapturedClaim() throws Exception {
      if (runtime.get().disposition() != IndexGenerationManager.BootDisposition.BUILDING) {
        replacePhysical(buildingRuntime(key));
      }
      coordinator.maintain();
      assertEquals(JobQueue.RecordedClaimDecision.ALLOW_FORCE, coordinator.recordedClaimDecision(key));
      JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
      assertEquals(sourceHash, claim.plannedSourceSha256());
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, sourceHash)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
      assertTrue(coordinator.beforeRecordedPromotion(key, queue));
      return claim;
    }

    void replacePhysical(RecordedIngestionLifecycle.BulkRuntime replacementRuntime) throws Exception {
      replacePhysical(replacementRuntime, null);
    }

    void replacePhysical(RecordedIngestionLifecycle.BulkRuntime replacementRuntime,
        RecordedIngestionLifecycle.CheckedGapAcceptance gapAcceptance) throws Exception {
      attachment.close();
      runtime.set(replacementRuntime);
      attachment = coordinator.attach(queue, () -> Optional.of(SERVING_GENERATION), () -> true,
          () -> Optional.of(runtime.get()), gapAcceptance);
      bindBulkProducer();
    }

    BulkReindexProgress progress() {
      OperationRecord row = operations.find(key).orElseThrow();
      return operations.bulkReindexProgress(row.id()).orElseThrow();
    }

    void reopen() throws Exception {
      closeOwner();
      runtime.set(new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.FENCED,
          SERVING_GENERATION, null, "IDLE", null, false));
      openOwner(true);
    }

    void seedTerminalBulkWithoutPreparation(String operationKey) {
      String arguments = "{\"corpusIds\":[\"docs\"]}";
      EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW,
          "recorded-bulk-ack-fixture", Optional.of("bulk-session"), Optional.empty(), TransportTag.BUTTON,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var provenance = EngineProvenance.invocation(origin, ExecutorTag.UI, Instant.now(CLOCK), Optional.empty());
      OperationDescriptor descriptor = OperationDescriptor.invocation(OperationKind.REINDEX,
          CoreOperationCatalog.BULK_REINDEX.value(), arguments, false);
      OperationRecord accepted = operations.accept(operationKey, descriptor, origin, provenance).record();
      assertTrue(operations.start(accepted.id()));
      assertTrue(operations.finish(accepted.id(), OperationState.FAILED,
          new OperationReceipt("FIXTURE_CONTRADICTION", null)).isPresent());
    }

    void seedContradictoryCapturedWalk(String operationKey) {
      JobQueue.WalkProgress walk = queue.beginCapturedWalk(operationKey, "0".repeat(64), true);
      queue.closeRecordedWalkEnumeration(operationKey, walk.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      queue.trySealRecordedWalk(operationKey);
    }

    private void closeOwner() throws Exception {
      if (coordinator != null) coordinator.stopProducers(1_000);
      if (attachment != null) attachment.close();
      EngineWorkHandle.Registration registration = cancellationSignal.getAndSet(null);
      if (registration != null) registration.close();
      EngineWorkHandle work = cancellableWork.getAndSet(null);
      if (work != null) work.close();
      if (sqliteQueue != null) sqliteQueue.close();
      if (operations != null) operations.close();
    }

    @Override public void close() throws Exception { closeOwner(); }
  }

  private static void assertInstanceOfContinuation(OperationRecord row, String key,
      java.util.UUID nonce) {
    var basis = OperationAuthorizationBasis.decode(row.context().grantReference().orElseThrow());
    assertInstanceOf(OperationAuthorizationBasis.PreparedContinuation.class, basis);
    var continuation = (OperationAuthorizationBasis.PreparedContinuation) basis;
    assertEquals(key, continuation.operationKey());
    assertEquals(nonce, continuation.preparationNonce());
  }

  private static RecordedIngestionLifecycle.BulkRuntime buildingRuntime(String key) {
    return new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.BUILDING,
        SERVING_GENERATION, "g-" + key, "MIGRATING", "g-" + key, false);
  }

  private static RecordedIngestionLifecycle.BulkRuntime promotedRuntime(String key,
      boolean promotedBoot, String writableGeneration) {
    return promotedRuntime(key, promotedBoot, writableGeneration, true);
  }

  private static RecordedIngestionLifecycle.BulkRuntime promotedRuntime(String key,
      boolean promotedBoot, String writableGeneration, boolean promotedReplaySettled) {
    return new RecordedIngestionLifecycle.BulkRuntime(IndexGenerationManager.BootDisposition.PROMOTED,
        "g-" + key, null, "IDLE", writableGeneration, promotedBoot, promotedReplaySettled);
  }

  private static JobQueue acknowledgeGate(JobQueue delegate, AtomicBoolean rejectAcknowledgement) {
    return acknowledgeGate(delegate, rejectAcknowledgement, new AtomicBoolean());
  }

  private static JobQueue acknowledgeGate(JobQueue delegate, AtomicBoolean rejectAcknowledgement,
      AtomicBoolean suppressWalkNotifications) {
    return (JobQueue) Proxy.newProxyInstance(JobQueue.class.getClassLoader(), new Class<?>[] {JobQueue.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("acknowledgeRecordedWalk") && rejectAcknowledgement.get()) return false;
          Object[] forwarded = arguments;
          if (method.getName().equals("subscribeRecordedWalks")) {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> subscriber = (java.util.function.Consumer<String>) arguments[0];
            forwarded = arguments.clone();
            forwarded[0] = (java.util.function.Consumer<String>) key -> {
              if (!suppressWalkNotifications.get()) subscriber.accept(key);
            };
          }
          try { return method.invoke(delegate, forwarded); }
          catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
  }

  private static JobQueue refusalRetirementGate(JobQueue delegate, AtomicBoolean failRetirement) {
    return (JobQueue) Proxy.newProxyInstance(JobQueue.class.getClassLoader(), new Class<?>[] {JobQueue.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("retireRefusedRecordedWalk") && failRetirement.compareAndSet(true, false)) {
            throw new IllegalStateException("injected refusal retirement cut");
          }
          try { return method.invoke(delegate, arguments); }
          catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String sha256(String value) throws Exception {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
