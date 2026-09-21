/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.BulkReindexProgress;
import io.justsearch.app.api.operations.OperationOutcomeView;
import io.justsearch.app.services.registry.executor.RecordedBulkPlanResolver;
import io.justsearch.app.api.operations.CanonicalOperationArguments;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAttemptRunner.Reconciliation;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedIngestChild;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.bootstrap.OperationAuthority.RecordedIngestRecoveryDecision;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Stable Engine owner of finite parent/child attempts; physical queue attachments are replaceable. */
final class RecordedIngestionCoordinator implements RecordedIngestionService, RecordedIngestionLifecycle {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecordedIngestionCoordinator.class);
  private static final String REFUSAL_CURSOR = "ingest-refusal:1:";
  private static final Set<String> DURABLE_REFUSALS = Set.of("RECOVERY_BINDING_INVALID",
      "RECOVERY_SCOPE_REFUSED", "RECOVERY_AUTHORIZATION_REFUSED", "RECOVERY_GENERATION_MISMATCH",
      RecordedIngestionSettlement.EXHAUSTED);
  /** Completion is actual producer exit, including cancellation/deadline cleanup, not caller release. */
  @FunctionalInterface
  interface Producer {
    CompletionStage<JobQueue.WalkEnumerationOutcome> enumerate(RecordedRootPlan plan,
        String childKey, long epoch, EngineContext context, CancelToken cancellation);
  }

  private final Object lock = new Object();
  private final OperationStore operations;
  private final OperationAttemptRunner attempts;
  private final EngineAdmissionService admission;
  private final OperationAuthority authority;
  private final RecordedIngestPlanResolver resolver = new RecordedIngestPlanResolver();
  private final Map<String, Parent> parents = new HashMap<>();
  private final Map<String, Bulk> bulks = new HashMap<>();
  private final ConcurrentHashMap<String, Bulk> bulkPermissions = new ConcurrentHashMap<>();
  // The queue predicate never takes lock or reads operations/jobs. Entries only exist for live children.
  private final ConcurrentHashMap<String, Permission> permissions = new ConcurrentHashMap<>();
  private volatile Attached attached;
  private boolean pumping;
  private boolean advanced;
  private boolean unknownBindingSeen;
  private long passes;

  RecordedIngestionCoordinator(OperationStore operations, OperationAttemptRunner attempts,
      EngineAdmissionService admission, OperationAuthority authority) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.admission = Objects.requireNonNull(admission, "admission");
    this.authority = Objects.requireNonNull(authority, "authority");
  }

  @Override
  public IndexGenerationManager.BootOwnership bootOwnership(JobQueue queue) throws IOException {
    synchronized (lock) {
      var pending = operations.openRecords().stream().filter(row -> isBulk(row)).toList();
      if (pending.isEmpty()) return new IndexGenerationManager.BootOwnership.Native();
      if (pending.size() != 1) return new IndexGenerationManager.BootOwnership.Fenced();
      var row = pending.getFirst();
      Bulk owner = bulks.get(row.key());
      if (owner != null && owner.cancelled) return new IndexGenerationManager.BootOwnership.Fenced();
      try {
        if (operations.bulkReindexProgress(row.id()).map(progress -> progress.refusalCode() != null).orElse(false)) {
          return new IndexGenerationManager.BootOwnership.Fenced();
        }
        var decision = authority.evaluateRecordedBulk(row, preparation(row));
        if (!(decision instanceof OperationAuthority.RecordedBulkRecoveryDecision.Authorized authorized)) {
          return new IndexGenerationManager.BootOwnership.Fenced();
        }
        RecordedBulkPlan plan = authorized.plan();
        var walk = queue.recordedWalk(row.key());
        boolean complete = walk.isPresent() && walk.orElseThrow().capturedPlan()
            && plan.planHash().equals(walk.orElseThrow().planHash())
            && walk.orElseThrow().enumerationOutcome() == JobQueue.WalkEnumerationOutcome.COMPLETE;
        return new IndexGenerationManager.BootOwnership.Recorded(row.key(), plan.scope().generation(),
            plan.source(), plan.target().fingerprint(), complete);
      } catch (IllegalArgumentException | JobQueue.RecordedWalkGapException invalid) {
        return new IndexGenerationManager.BootOwnership.Fenced();
      }
    }
  }

  private static boolean isBulk(OperationRecord row) {
    if (row.descriptor().kind() != OperationKind.REINDEX) return false;
    for (RecordedBulkPlan.Profile profile : RecordedBulkPlan.Profile.values()) {
      if (profile.operationRef().equals(row.descriptor().operationRef())) return true;
    }
    return false;
  }

  @Override
  public OperationExecution execute(OperationRecordHandle handle, EngineContext context) {
    if (context.workId().isEmpty()) throw new IllegalArgumentException("Recorded parent requires admitted work");
    try (var admitted = admission.attach(context)) {
    synchronized (lock) {
      OperationRecord row = operations.find(handle.key()).orElseThrow();
      var live = admitted.context();
      boolean detached = row.context().survival() == EngineContext.Survival.DURABLE
          && row.context().urgency() == EngineContext.Urgency.FOREGROUND
          && context.urgency() == EngineContext.Urgency.BACKGROUND;
      if (row.id() != handle.id() || row.state() != OperationState.RUNNING
          || live.survival() != context.survival() || live.urgency() != context.urgency()
          || !unattached(detached ? context.withUrgency(row.context().urgency()) : context).equals(unattached(row.context()))) {
        throw new IllegalArgumentException("Recorded parent requires its exact admitted context");
      }
      var stored = preparation(row);
      if (isBulk(row)) {
        if (bulks.containsKey(row.key())) throw new IllegalStateException("Recorded bulk already has an owner");
        if (operations.openRecords().stream().anyMatch(other -> isBulk(other) && !other.key().equals(row.key()))) {
          return OperationExecution.finished(OperationResult.failure("Another bulk rebuild owns the index",
              "BULK_GENERATION_CONFLICT", Map.of(), false));
        }
        var decision = authority.evaluateRecordedBulk(row, stored);
        if (!(decision instanceof OperationAuthority.RecordedBulkRecoveryDecision.Authorized authorized)) {
          return OperationExecution.finished(OperationResult.failure("Bulk authorization refused",
              "RECOVERY_AUTHORIZATION_REFUSED", Map.of(), false));
        }
        var bulk = new Bulk(row, stored, authorized.plan(), handle, true);
        bulk.work = admitted.retain();
        observeBulkCancellation(bulk);
        bulks.put(row.key(), bulk);
        attempts.checkpointBulkReindex(handle, capturing(bulk));
        maintain();
        return pending(bulk.completion);
      }
      var plan = resolver.resolve(row, stored);
      if (parents.containsKey(row.key())) throw new IllegalStateException("Recorded parent already has an owner");
      if (plan.roots().isEmpty()) return OperationExecution.finished(OperationResult.success("No ingestion roots"));
      if (parents.size() >= admission.limits().aggregateLimit()) {
        throw new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, admission.retryAfterSeconds());
      }
      // This private runner check precedes retention of any caller-supplied handle or fresh origin.
      var child = attempts.acceptIngestChild(handle, plan.roots().getFirst());
      var parent = new Parent(row, stored, plan, handle, true);
      parent.physical = attached;
      parent.work = admitted.retain();
      observeCancellation(parent);
      parents.put(row.key(), parent);
      startFreshChild(parent, child, oneRoot(plan, 0));
      maintain();
      return pending(parent.completion);
    }
    }
  }

  @Override
  public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
    Bulk bulk = bulkPermissions.get(key);
    if (bulk != null) return bulkClaimAllowed(bulk) ? JobQueue.RecordedClaimDecision.ALLOW_FORCE
        : JobQueue.RecordedClaimDecision.DENY;
    Permission permission = permissions.get(key);
    Attached physical = attached;
    if (permission == null || physical == null || physical != permission.physical || physical.stopping
        || permission.owner.cancelled || permission.plan.roots().size() != 1) return JobQueue.RecordedClaimDecision.DENY;
    try {
      if (!physical.online.getAsBoolean()
          || !physical.generation.current().filter(permission.plan.generation()::equals).isPresent()) return JobQueue.RecordedClaimDecision.DENY;
      boolean authorized = permission.fresh
          ? authority.allowsFreshRecordedIngest(permission.parent, permission.plan)
          : authority.evaluateRecordedIngest(permission.parent, permission.preparation,
              Optional.of(permission.plan.generation()), required -> required instanceof RequiredCapability.WorkerOnline)
              instanceof RecordedIngestRecoveryDecision.Authorized;
      if (!authorized) return JobQueue.RecordedClaimDecision.DENY;
      return permission.plan.roots().getFirst().force()
          ? JobQueue.RecordedClaimDecision.ALLOW_FORCE : JobQueue.RecordedClaimDecision.ALLOW;
    } catch (IOException unavailableGeneration) {
      return JobQueue.RecordedClaimDecision.DENY;
    }
  }

  @Override
  public Attachment attach(JobQueue queue, CheckedServingGeneration generation, BooleanSupplier online) throws IOException {
    return attach(queue, generation, online, Optional::empty);
  }

  @Override
  public Attachment attach(JobQueue queue, CheckedServingGeneration generation, BooleanSupplier online,
      CheckedBulkRuntime bulkRuntime) throws IOException {
    synchronized (lock) {
      if (attached != null) throw new IOException("Recorded ingestion still owns its prior queue");
      Attached physical = new Attached(queue, generation, online, bulkRuntime);
      attached = physical;
      try {
        physical.queueSubscription = queue.subscribeRecordedWalks(key -> {
          synchronized (lock) {
            Bulk bulk = bulks.get(key);
            // Queue delivery follows the committed enqueue and runs before the captured
            // producer can finish this root. Initial begin-walk delivery has no exit future.
            if (attached == physical && bulk != null && bulk.physical == physical
                && !bulk.started && bulk.exit != null && !bulk.exit.isDone()) {
              attempts.observeBulkBoundary(bulk.handle,
                  OperationAttemptRunner.BulkBoundary.PARTIAL_CAPTURE);
            }
            maintain();
          }
        });
        physical.operationSubscription = operations.subscribeCompletions(row -> {
          synchronized (lock) {
            if (row.descriptor().kind() == OperationKind.INGEST || row.descriptor().kind() == OperationKind.REINDEX) advanced = true;
            maintain();
          }
        });
        maintain();
        return physical;
      } catch (RuntimeException | Error failure) {
        permissions.clear();
        attached = null;
        try { physical.closeSubscriptions(); }
        catch (Exception cleanup) { failure.addSuppressed(cleanup); }
        throw failure;
      }
    }
  }

  /** Bind only the current EngineKnowledgeClient's bounded recorded adapter. */
  void bindProducer(Producer producer) {
    synchronized (lock) {
      Attached physical = Objects.requireNonNull(attached, "No recorded ingestion attachment");
      if (physical.stopping) throw new IllegalStateException("Recorded ingestion attachment is stopping");
      if (physical.producer != null) throw new IllegalStateException("Recorded producer already bound");
      physical.producer = Objects.requireNonNull(producer, "producer");
      maintain();
    }
  }

  void bindBulkProducer(Producer producer, IndexingService indexing, Runnable restart) {
    synchronized (lock) {
      Attached physical = Objects.requireNonNull(attached, "No recorded ingestion attachment");
      if (physical.stopping || physical.bulkProducer != null) throw new IllegalStateException("Bulk producer cannot bind");
      physical.bulkProducer = Objects.requireNonNull(producer, "producer");
      physical.bulkIndexing = Objects.requireNonNull(indexing, "indexing");
      physical.bulkRestart = Objects.requireNonNull(restart, "restart");
      maintain();
    }
  }

  /** Revoke and request actual producer exit before closing its client; preserve pending parent work. */
  void stopProducers(long timeoutMillis) throws IOException {
    CompletableFuture<?>[] exits;
    synchronized (lock) {
      Attached physical = attached;
      if (physical == null) return;
      physical.stopping = true;
      permissions.clear();
      bulkPermissions.clear();
      List<CompletableFuture<?>> pending = new ArrayList<>();
      for (Bulk bulk : bulks.values()) {
        if (bulk.cancellation != null) bulk.cancellation.cancel("index attachment stopping");
        if (bulk.exit != null) pending.add(bulk.exit.handle((ignored, failure) -> null));
        if (bulk.notification != null) pending.add(bulk.notification);
      }
      for (Parent parent : parents.values()) {
        parent.fresh = false;
        Child child = parent.child;
        if (child != null) {
          child.fresh = false;
          if (child.cancellation != null) child.cancellation.cancel("index attachment stopping");
          if (child.exit != null) pending.add(child.exit.handle((ignored, failure) -> null));
          if (child.notification != null) pending.add(child.notification);
        }
      }
      exits = pending.toArray(CompletableFuture<?>[]::new);
    }
    try {
      CompletableFuture.allOf(exits).get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted draining recorded ingestion producers", interrupted);
    } catch (java.util.concurrent.ExecutionException | TimeoutException incomplete) {
      throw new IOException("Recorded ingestion producer drain is incomplete", incomplete);
    }
  }

  @Override
  public void maintain() {
    synchronized (lock) {
      if (pumping || attached == null) return;
      pumping = true;
      try {
        // Only winning/consumed owner transitions repeat a pass. They are finite in the frozen
        // root plans and captured runner cohort. Queue writes/notifications alone never repeat it.
        do {
          advanced = false;
          pump(attached);
        } while (advanced);
        // Retry a failed restart on the next maintenance call, not each internal progress pass.
        restartAfterBulkRefusal(attached);
      } finally {
        pumping = false;
      }
    }
  }

  private void pump(Attached physical) {
    passes++;
    Set<String> openChildParents = new HashSet<>();
    boolean[] unknownChild = {false};
    // Inventory is restricted to the runner's captured, not-yet-started cohort. It grants no verdict.
    attempts.reconcile(OperationKind.INGEST, row -> {
      if (row.descriptor().operationRef() == null) {
        try { openChildParents.add(RecordedIngestChild.parentKey(row.descriptor())); }
        catch (IllegalArgumentException invalid) { unknownChild[0] = true; unknownBindingSeen = true; }
      }
      return new Reconciliation.Wait();
    });
    for (Parent parent : List.copyOf(parents.values())) drive(parent, physical, openChildParents, unknownChild[0]);
    for (Bulk bulk : List.copyOf(bulks.values())) driveBulk(bulk, physical);
    attempts.reconcile(OperationKind.REINDEX,
        row -> isBulk(row) ? reconcileBulk(row, physical)
            : reconcileParent(row, physical, openChildParents, unknownChild[0]));
    for (Bulk bulk : List.copyOf(bulks.values())) driveBulk(bulk, physical);
    repairBulkAcknowledgements(physical);
    attempts.reconcile(OperationKind.INGEST, row -> row.descriptor().operationRef() == null
        ? reconcileChild(row, physical)
        : reconcileParent(row, physical, openChildParents, unknownChild[0]));
    for (Parent parent : List.copyOf(parents.values())) drive(parent, physical, openChildParents, unknownChild[0]);
  }

  private Reconciliation reconcileParent(OperationRecord row, Attached physical,
      Set<String> openChildParents, boolean unknownChild) {
    if (row.state() == OperationState.COMPLETE_WITH_GAPS) return new Reconciliation.Wait();
    final OperationStore.Preparation stored;
    final RecordedRootPlan plan;
    try {
      stored = preparation(row);
      plan = resolver.resolve(row, stored);
      recordedRefusal(row);
    } catch (IllegalArgumentException invalid) {
      return passes == 1 || unknownChild || openChildParents.contains(row.key())
          ? new Reconciliation.Wait() : failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    if (unknownBindingSeen) {
      return unknownChild || openChildParents.contains(row.key()) ? new Reconciliation.Wait()
          : failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    RecordedIngestRecoveryDecision decision = recovery(row, stored, physical);
    if (decision instanceof RecordedIngestRecoveryDecision.Refused refused) {
      if (!unknownChild && row.state() == OperationState.RUNNING && recordedRefusal(row) == null
          && DURABLE_REFUSALS.contains(refused.receipt().code()) && fenceChildren(row, plan)) {
        advanced = true;
        return new Reconciliation.CheckpointAndWait(REFUSAL_CURSOR + refused.receipt().code(),
            row.unitsCompleted(), row.unitsFailed());
      }
      Reconciliation outcome = refusalAfterChildren(row, plan, physical, refused.receipt());
      return unknownChild || openChildParents.contains(row.key()) ? new Reconciliation.Wait() : outcome;
    }
    Optional<Reconciliation> completed = completedParent(row, plan, physical);
    if (completed.isPresent()) return completed.orElseThrow();
    if (row.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS) {
      if (!unknownChild && row.state() == OperationState.RUNNING && recordedRefusal(row) == null
          && fenceChildren(row, plan)) {
        advanced = true;
        return new Reconciliation.CheckpointAndWait(REFUSAL_CURSOR + RecordedIngestionSettlement.EXHAUSTED,
            row.unitsCompleted(), row.unitsFailed());
      }
      Reconciliation outcome = refusalAfterChildren(row, plan, physical, new OperationReceipt(RecordedIngestionSettlement.EXHAUSTED, null));
      return unknownChild || openChildParents.contains(row.key()) ? new Reconciliation.Wait() : outcome;
    }
    if (decision instanceof RecordedIngestRecoveryDecision.Wait || physical.stopping
        || parents.size() >= admission.limits().aggregateLimit()) return new Reconciliation.Wait();
    return new Reconciliation.Resume(handle -> {
      Parent parent = new Parent(row, stored, plan, handle, false);
      parent.physical = physical;
      parents.put(row.key(), parent);
      advanced = true;
      acquireWork(parent);
      return pending(parent.completion);
    });
  }

  private Optional<Reconciliation> completedParent(OperationRecord row, RecordedRootPlan plan, Attached physical) {
    long completed = 0;
    long failed = 0;
    boolean unfinished = false;
    String failure = null;
    boolean unavailable = false;
    for (int index = 0; index < plan.roots().size(); index++) {
      try {
        var found = findChild(row.key(), oneRoot(plan, index));
        if (found.isEmpty() || !found.orElseThrow().state().terminal()) { unfinished = true; continue; }
        var child = found.orElseThrow();
        if (!acknowledgeOrUnavailable(child, oneRoot(plan, index), physical)) unavailable = true;
        else if (child.state() != OperationState.COMPLETE) failure = child.receipt().code();
        completed = Math.addExact(completed, child.unitsCompleted());
        failed = Math.addExact(failed, child.unitsFailed());
      } catch (JobQueue.RecordedWalkGapException invalidBinding) { unfinished = true; }
    }
    if (unfinished) return Optional.empty();
    if (unavailable || completed < row.unitsCompleted() || failed < row.unitsFailed()) return Optional.of(failed(RecordedIngestionSettlement.UNAVAILABLE));
    String outcomeCode = failure == null ? "SUCCESS" : failure;
    OperationReceipt outcome = new OperationReceipt(outcomeCode, null);
    String cursor = "ingest-parent:1:" + plan.roots().size();
    if (cursor.equals(row.checkpointCursor()) && completed == row.unitsCompleted() && failed == row.unitsFailed()) {
      return Optional.of("SUCCESS".equals(outcomeCode) ? new Reconciliation.Complete(outcome)
          : "cancelled".equals(outcomeCode) ? new Reconciliation.Cancelled(outcome) : new Reconciliation.Failed(outcome));
    }
    if (row.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS) return Optional.of(failed(RecordedIngestionSettlement.EXHAUSTED));
    long confirmedCompleted = completed;
    long confirmedFailed = failed;
    return Optional.of(new Reconciliation.Resume(handle -> {
      handle.checkpoint(cursor, confirmedCompleted, confirmedFailed);
      if ("SUCCESS".equals(outcomeCode)) return OperationExecution.finished(OperationResult.success("Recorded ingestion completed"));
      CompletableFuture<OperationResult> result = new CompletableFuture<>();
      finish(result, outcome);
      return pending(result);
    }));
  }

  private Reconciliation refusalAfterChildren(OperationRecord row, RecordedRootPlan plan,
      Attached physical, OperationReceipt refusal) {
    boolean unavailable = false;
    boolean open = false;
    for (int index = 0; index < plan.roots().size(); index++) {
      final Optional<OperationRecord> child;
      try { child = findChild(row.key(), oneRoot(plan, index)); }
      catch (JobQueue.RecordedWalkGapException invalidBinding) { unavailable = true; continue; }
      if (child.isEmpty()) continue;
      OperationRecord found = child.orElseThrow();
      permissions.remove(found.key());
      if (!found.state().terminal()) open = true;
      else if (!acknowledgeOrUnavailable(found, oneRoot(plan, index), physical)) unavailable = true;
    }
    if (open) return new Reconciliation.Wait();
    return unavailable ? failed(RecordedIngestionSettlement.UNAVAILABLE) : new Reconciliation.Failed(refusal);
  }

  private Reconciliation reconcileChild(OperationRecord row, Attached physical) {
    final RecordedIngestChild binding;
    final OperationRecord parentRow;
    final OperationStore.Preparation parentPreparation;
    try {
      binding = RecordedIngestChild.from(row.descriptor(), preparation(row).payload());
      parentRow = operations.find(binding.parentOperationKey()).orElseThrow(
          () -> new IllegalArgumentException("Recorded parent disappeared"));
      parentPreparation = preparation(parentRow);
      var parentPlan = resolver.resolve(parentRow, parentPreparation);
      recordedRefusal(parentRow);
      if (parentRow.state().terminal() || !parentPlan.generation().equals(binding.plan().generation())
          || !parentPlan.roots().contains(binding.plan().roots().getFirst())
          || findChild(parentRow.key(), binding.plan()).filter(found -> found.id() == row.id()).isEmpty()) {
        throw new IllegalArgumentException("Recorded child membership is unavailable");
      }
    } catch (IllegalArgumentException | JobQueue.RecordedWalkGapException invalid) {
      permissions.remove(row.key());
      return physical.queue.hasIssuedRecordedClaims(row.key()) ? new Reconciliation.Wait()
          : failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    if (parentRow.state() == OperationState.COMPLETE_WITH_GAPS) return new Reconciliation.Wait();
    String hash = planHash(binding.plan());
    var policy = recovery(parentRow, parentPreparation, physical);
    Parent currentParent = parents.get(parentRow.key());
    boolean refused = unknownBindingSeen || (currentParent != null && currentParent.refusal != null)
        || policy instanceof RecordedIngestRecoveryDecision.Refused
        || (parentRow.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS
            && !parents.containsKey(parentRow.key()));
    try {
      var progress = physical.queue.recordedWalk(row.key()).orElseThrow(
          () -> new JobQueue.RecordedWalkGapException("Recorded child progress disappeared"));
      if (!hash.equals(progress.planHash())) throw new JobQueue.RecordedWalkGapException("Child plan mismatch");
      if (refused && progress.enumerationClosedAt() == null) {
        permissions.remove(row.key());
        physical.queue.closeRecordedWalkEnumeration(row.key(), progress.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.FAILED);
      }
      if (recordedRefusal(parentRow) != null && progress.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.COMPLETE) {
        permissions.remove(row.key());
        physical.queue.retireRefusedRecordedWalk(row.key(), hash);
      }
      physical.queue.trySealRecordedWalk(row.key());
      if (physical.queue.sealedRecordedWalkReceipt(row.key()).isPresent() || refused) {
        permissions.remove(row.key());
        var settlement = recordedRefusal(parentRow) == null ? physical.settlement.reconcile(row, hash, true)
            : physical.settlement.reconcileRefused(row, hash);
        if (settlement instanceof Reconciliation.CheckpointAndWait) advanced = true;
        return settlement;
      }
    } catch (JobQueue.RecordedWalkGapException unavailable) {
      permissions.remove(row.key());
      return physical.queue.hasIssuedRecordedClaims(row.key()) ? new Reconciliation.Wait()
          : failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    Parent parent = parents.get(parentRow.key());
    if (policy instanceof RecordedIngestRecoveryDecision.Wait || physical.stopping || parent == null
        || parent.work == null || parent.child != null || parent.nextRoot >= parent.plan.roots().size()
        || !oneRoot(parent.plan, parent.nextRoot).equals(binding.plan())) return new Reconciliation.Wait();
    if (row.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS) {
      permissions.remove(row.key());
      return physical.queue.hasIssuedRecordedClaims(row.key()) ? new Reconciliation.Wait()
          : failed(RecordedIngestionSettlement.EXHAUSTED);
    }
    return new Reconciliation.Resume(handle -> installChild(parent, row, handle, binding.plan(), false, false));
  }

  private void drive(Parent parent, Attached physical, Set<String> openChildParents, boolean unknownChild) {
    OperationRecord row = operations.find(parent.row.key()).orElseThrow();
    if (row.state().terminal()) {
      release(parent);
      return;
    }
    if (parent.completion.isDone()) return; // Await durable terminal persistence; retain admission on failure.
    if (physical.stopping && !physical.flushing) return;
    if (parent.physical != physical) validateReplacement(parent, physical);
    if (unknownBindingSeen && parent.fromBoot) parent.refusal = new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null);
    if (!physical.flushing) acquireWork(parent);
    var generation = physical.flushing ? Optional.<String>empty() : physical.generationValue();
    if (generation.isPresent() && !parent.plan.generation().equals(generation.orElseThrow())) {
      parent.refusal = new OperationReceipt("RECOVERY_GENERATION_MISMATCH", null);
    }
    var decision = physical.flushing ? new RecordedIngestRecoveryDecision.Wait()
        : parent.fresh ? null : recovery(parent.row, parent.preparation, physical);
    if (decision instanceof RecordedIngestRecoveryDecision.Refused refused) parent.refusal = refused.receipt();
    if (parent.work != null && parent.work.cancellationReason().isPresent()) {
      parent.refusal = new OperationReceipt("cancelled", null);
    }
    if (recordedRefusal(row) != null) parent.refusal = recordedRefusal(row);
    if (parent.refusal != null && DURABLE_REFUSALS.contains(parent.refusal.code())
        && recordedRefusal(row) == null && fenceChildren(row, parent.plan)) {
      parent.handle.checkpoint(REFUSAL_CURSOR + parent.refusal.code(), row.unitsCompleted(), row.unitsFailed());
      row = operations.find(row.key()).orElseThrow();
    }
    Child child = parent.child;
    if (child != null) {
      driveChild(parent, child, physical, decision);
      OperationRecord childRow = operations.find(child.row.key()).orElseThrow();
      if (!childRow.state().terminal()) return;
      permissions.remove(childRow.key());
      if (parent.bindingUnavailable || !acknowledgeOrUnavailable(childRow, child.plan, physical)) {
        parent.refusal = new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null);
      } else if (childRow.state() != OperationState.COMPLETE && parent.refusal == null) {
        parent.refusal = childRow.receipt();
      }
      parent.child = null;
      advanced = true;
    }
    if (parent.bindingUnavailable) {
      if (!unknownChild && !openChildParents.contains(parent.row.key())) finish(parent.completion, parent.refusal);
      return;
    }
    while (parent.nextRoot < parent.plan.roots().size()) {
      var rootPlan = oneRoot(parent.plan, parent.nextRoot);
      final Optional<OperationRecord> existing;
      try { existing = findChild(parent.row.key(), rootPlan); }
      catch (JobQueue.RecordedWalkGapException invalidBinding) {
        parent.refusal = new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null);
        break;
      }
      if (existing.isPresent()) {
        var found = existing.orElseThrow();
        if (!found.state().terminal()) return;
        if (!acknowledgeOrUnavailable(found, rootPlan, physical)) {
          parent.refusal = new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null);
          break;
        }
        parent.completed = Math.addExact(parent.completed, found.unitsCompleted());
        parent.failed = Math.addExact(parent.failed, found.unitsFailed());
        parent.nextRoot++;
        advanced = true;
        if (found.state() != OperationState.COMPLETE && parent.refusal == null) parent.refusal = found.receipt();
        checkpointParent(parent, row);
        continue;
      }
      if (parent.refusal != null) break;
      if (parent.work == null || decision instanceof RecordedIngestRecoveryDecision.Wait || physical.producer == null) return;
      if (!physical.generationValue().filter(parent.plan.generation()::equals).isPresent()) return;
      if (parent.fresh && !authority.allowsFreshRecordedIngest(parent.row, rootPlan)) {
        parent.refusal = new OperationReceipt("RECOVERY_AUTHORIZATION_REFUSED", null);
        break;
      }
      startFreshChild(parent, attempts.acceptIngestChild(parent.handle, rootPlan.roots().getFirst()), rootPlan);
      return;
    }
    if (parent.refusal != null) {
      if (unknownChild || openChildParents.contains(parent.row.key())) return;
      Reconciliation finalOutcome = refusalAfterChildren(row, parent.plan, physical, parent.refusal);
      if (finalOutcome instanceof Reconciliation.Wait) return;
      finish(parent.completion, ((Reconciliation.Failed) finalOutcome).receipt());
    }
    else if (parent.completed < row.unitsCompleted() || parent.failed < row.unitsFailed()) {
      finish(parent.completion, new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null));
    } else {
      checkpointParent(parent, row);
      parent.completion.complete(OperationResult.success("Recorded ingestion completed"));
    }
  }

  private void validateReplacement(Parent parent, Attached physical) {
    try {
      var current = operations.find(parent.row.key()).orElseThrow(
          () -> new IllegalArgumentException("Recorded parent disappeared"));
      var stored = preparation(current);
      if (!stored.equals(parent.preparation) || !current.context().equals(parent.row.context())
          || !resolver.resolve(current, stored).equals(parent.plan)) {
        throw new IllegalArgumentException("Recorded parent binding changed");
      }
      Child child = parent.child;
      if (child != null && findChild(parent.row.key(), child.plan).filter(found -> found.id() == child.row.id()).isEmpty()) {
        throw new IllegalArgumentException("Recorded child binding changed");
      }
    } catch (IllegalArgumentException | JobQueue.RecordedWalkGapException unavailable) {
      parent.bindingUnavailable = true;
      parent.refusal = new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null);
    }
    if (parent.physical != null) parent.fresh = false;
    parent.physical = physical;
  }

  private void acquireWork(Parent parent) {
    if (parent.work != null || parent.refusal != null) return;
    try {
      parent.work = admission.attach(unattached(parent.row.context()));
      observeCancellation(parent);
    }
    catch (EngineAdmissionException refused) {
      if (refused.reason() == EngineAdmissionException.Reason.WORK_FINISHED) throw refused;
    }
  }

  private void checkpointParent(Parent parent, OperationRecord observed) {
    if (parent.completed >= observed.unitsCompleted() && parent.failed >= observed.unitsFailed()) {
      parent.handle.checkpoint(parentCursor(observed, parent.nextRoot), parent.completed, parent.failed);
    }
  }

  private void startFreshChild(Parent parent, OperationAttemptRunner.PreparedAttempt accepted, RecordedRootPlan plan) {
    attempts.start(accepted, handle -> installChild(parent, accepted.accepted(), handle, plan, parent.fresh, true));
  }

  private OperationExecution installChild(Parent parent, OperationRecord row, OperationRecordHandle handle,
      RecordedRootPlan plan, boolean fresh, boolean createIfMissing) {
    if (parent.child != null) throw new IllegalStateException("Recorded parent already owns a child");
    Child child = new Child(row, handle, plan, fresh, createIfMissing);
    parent.child = child;
    advanced = true;
    return pending(child.completion);
  }

  private void driveChild(Parent parent, Child child, Attached physical, RecordedIngestRecoveryDecision parentDecision) {
    OperationRecord row = operations.find(child.row.key()).orElseThrow();
    if (row.state().terminal() || child.completion.isDone() || physical.stopping) return;
    if (parent.bindingUnavailable) {
      permissions.remove(row.key());
      if (child.cancellation != null) child.cancellation.cancel("recorded binding unavailable");
      if ((child.exit == null || child.exit.isDone()) && !physical.queue.hasIssuedRecordedClaims(row.key())) {
        finish(child.completion, new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null));
      }
      return;
    }
    boolean allowed = !parent.cancelled && parent.refusal == null && parent.work != null
        && !(parentDecision instanceof RecordedIngestRecoveryDecision.Wait)
        && physical.generationValue().filter(child.plan.generation()::equals).isPresent()
        && physical.online.getAsBoolean()
        && (!child.fresh || authority.allowsFreshRecordedIngest(parent.row, child.plan));
    if (!allowed) {
      permissions.remove(row.key());
      if (child.cancellation != null) child.cancellation.cancel("recorded authority unavailable");
      if (parent.fresh && parent.refusal == null
          && !authority.allowsFreshRecordedIngest(parent.row, child.plan)) {
        parent.refusal = new OperationReceipt("RECOVERY_AUTHORIZATION_REFUSED", null);
      }
    }
    if (!allowed && parent.refusal == null) child.retryEnumeration = true;
    if (child.exit != null && !child.exit.isDone()) {
      checkpointLive(parent, child, row, physical);
      return;
    }
    try {
      Optional<JobQueue.WalkProgress> observed = physical.queue.recordedWalk(row.key());
      if (child.physical != physical) {
        child.exit = null;
        child.cancellation = null;
        child.physical = physical;
        if (!child.createIfMissing && observed.isEmpty()) throw new JobQueue.RecordedWalkGapException("Recorded progress disappeared");
      }
      if (observed.isPresent() && !planHash(child.plan).equals(observed.orElseThrow().planHash())) {
        throw new JobQueue.RecordedWalkGapException("Recorded child plan changed");
      }
      if (parent.refusal != null) {
        permissions.remove(row.key());
        if (observed.isEmpty() && child.createIfMissing) {
          // Same-attempt proof of no producer activity permits an empty refused receipt.
          var empty = physical.queue.beginRecordedWalk(row.key(), planHash(child.plan), true);
          child.createIfMissing = false;
          child.epoch = empty.enumerationEpoch();
          observed = Optional.of(empty);
        }
        if (observed.isEmpty()) throw new JobQueue.RecordedWalkGapException("Refused child has no recorded progress");
        var progress = observed.orElseThrow();
        if (progress.enumerationClosedAt() == null) physical.queue.closeRecordedWalkEnumeration(row.key(),
            progress.enumerationEpoch(), "cancelled".equals(parent.refusal.code())
                ? JobQueue.WalkEnumerationOutcome.CANCELLED : JobQueue.WalkEnumerationOutcome.FAILED);
        else if (progress.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.COMPLETE
            && recordedRefusal(operations.find(parent.row.key()).orElseThrow()) != null) {
          physical.queue.retireRefusedRecordedWalk(row.key(), planHash(child.plan));
        }
      } else if (allowed) {
        if (child.retryEnumeration) {
          child.exit = null;
          child.cancellation = null;
          child.retryEnumeration = false;
        }
        Permission previous = permissions.put(row.key(), new Permission(physical, parent.row, parent.preparation, child.plan, child.fresh, parent));
        if (previous == null) physical.queue.recoverStuckJobs();
        if (observed.isEmpty() || observed.orElseThrow().enumerationClosedAt() == null) {
          if (child.exit == null) {
            if (physical.producer == null) return;
            var progress = physical.queue.beginRecordedWalk(row.key(), planHash(child.plan), child.createIfMissing);
            child.createIfMissing = false;
            child.epoch = progress.enumerationEpoch();
            child.cancellation = new CancelToken();
            if (parent.cancelled) {
              child.cancellation.cancel("parent cancelled before producer publication");
              child.exit = CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.CANCELLED);
            } else {
              try {
                child.exit = physical.producer.enumerate(child.plan, row.key(), child.epoch,
                    parent.work.context(), child.cancellation).toCompletableFuture();
              } catch (RuntimeException rejected) {
                // A synchronous rejection owns no producer; settle through the same receipt barrier.
                child.exit = CompletableFuture.failedFuture(rejected);
              }
            }
            child.notification = child.exit.handle((ignored, failure) -> {
              try { maintain(); }
              catch (RuntimeException retryable) {
                log.error("Recorded ingestion completion notification failed; maintenance will retry", retryable);
              }
              return null;
            });
            if (!child.exit.isDone()) return;
          }
          JobQueue.WalkEnumerationOutcome outcome;
          try { outcome = child.exit.join(); }
          catch (java.util.concurrent.CompletionException | CancellationException failed) {
            outcome = child.cancellation.isCancelled() ? JobQueue.WalkEnumerationOutcome.CANCELLED
                : JobQueue.WalkEnumerationOutcome.FAILED;
          }
          physical.queue.closeRecordedWalkEnumeration(row.key(), child.epoch, outcome);
        }
      } else return;
      var progress = physical.queue.trySealRecordedWalk(row.key());
      if (progress.completedUnits() < row.unitsCompleted() || progress.failedUnits() < row.unitsFailed()) {
        throw new JobQueue.RecordedWalkGapException("Recorded progress decreased");
      }
      if (progress.sealedAt() == null) {
        String cursor = "ingest-progress:1:" + progress.revision();
        if (!cursor.equals(row.checkpointCursor())) child.handle.checkpoint(cursor, progress.completedUnits(), progress.failedUnits());
        return;
      }
      permissions.remove(row.key());
      physical.settlement.settleRunning(row, planHash(child.plan), true, child.handle)
          .ifPresent(outcome -> transferSettlement(child, outcome));
    } catch (JobQueue.RecordedWalkGapException unavailable) {
      permissions.remove(row.key());
      if (!physical.queue.hasIssuedRecordedClaims(row.key())) {
        finish(child.completion, new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null));
      }
    }
  }

  private void checkpointLive(Parent parent, Child child, OperationRecord row, Attached physical) {
    try {
      var progress = physical.queue.recordedWalk(row.key()).orElseThrow(
          () -> new JobQueue.RecordedWalkGapException("Live recorded progress disappeared"));
      if (!planHash(child.plan).equals(progress.planHash()) || progress.completedUnits() < row.unitsCompleted()
          || progress.failedUnits() < row.unitsFailed()) {
        throw new JobQueue.RecordedWalkGapException("Live recorded progress contradicted its operation");
      }
      String cursor = "ingest-progress:1:" + progress.revision();
      if (progress.sealedAt() == null && !cursor.equals(row.checkpointCursor())) {
        child.handle.checkpoint(cursor, progress.completedUnits(), progress.failedUnits());
      }
      var parentRow = operations.find(parent.row.key()).orElseThrow();
      long completed = Math.addExact(parent.completed, progress.completedUnits());
      long failed = Math.addExact(parent.failed, progress.failedUnits());
      if (completed >= parentRow.unitsCompleted() && failed >= parentRow.unitsFailed()) {
        parent.handle.checkpoint(parentCursor(parentRow, parent.nextRoot), completed, failed);
      }
    } catch (JobQueue.RecordedWalkGapException unavailable) {
      permissions.remove(row.key());
      parent.refusal = new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null);
      if (child.cancellation != null) child.cancellation.cancel("recorded progress unavailable");
    }
  }

  private static void transferSettlement(Child child, OperationExecution outcome) {
    var completed = outcome.completion().toCompletableFuture();
    if (!completed.isDone()) throw new IllegalStateException("Receipt settlement must be synchronous");
    try { child.completion.complete(completed.join()); }
    catch (java.util.concurrent.CompletionException failed) { child.completion.completeExceptionally(failed.getCause()); }
    catch (CancellationException cancelled) { child.completion.completeExceptionally(cancelled); }
  }

  private boolean acknowledgeOrUnavailable(OperationRecord child, RecordedRootPlan plan, Attached physical) {
    if (child.receipt() != null && (RecordedIngestionSettlement.UNAVAILABLE.equals(child.receipt().code())
        || RecordedIngestionSettlement.EXHAUSTED.equals(child.receipt().code()))) return false;
    try { return physical.settlement.acknowledge(child.key(), planHash(plan)); }
    catch (JobQueue.RecordedWalkGapException unavailable) { return false; }
  }

  private RecordedIngestRecoveryDecision recovery(OperationRecord row, OperationStore.Preparation preparation, Attached physical) {
    var refused = recordedRefusal(row);
    if (refused != null) return new RecordedIngestRecoveryDecision.Refused(refused);
    return authority.evaluateRecordedIngest(row, preparation, physical.generationValue(),
        required -> required instanceof RequiredCapability.WorkerOnline && physical.online.getAsBoolean());
  }

  /** Fence the exact frozen family before persisting a decision; never grant a cleanup permit. */
  private boolean fenceChildren(OperationRecord row, RecordedRootPlan plan) {
    for (int index = 0; index < plan.roots().size(); index++) {
      final Optional<OperationRecord> child;
      try { child = findChild(row.key(), oneRoot(plan, index)); }
      catch (JobQueue.RecordedWalkGapException invalid) { return false; }
      child.ifPresent(found -> permissions.remove(found.key()));
    }
    var parent = parents.get(row.key());
    if (parent != null && parent.child != null && parent.child.cancellation != null) {
      parent.child.cancellation.cancel("recorded recovery permanently refused");
    }
    return true;
  }

  private static OperationReceipt recordedRefusal(OperationRecord row) {
    String cursor = row.checkpointCursor();
    if (cursor == null || !cursor.startsWith("ingest-refusal:")) return null;
    if (!cursor.startsWith(REFUSAL_CURSOR) || !DURABLE_REFUSALS.contains(cursor.substring(REFUSAL_CURSOR.length()))) {
      throw new IllegalArgumentException("Recorded ingestion refusal is malformed");
    }
    return new OperationReceipt(cursor.substring(REFUSAL_CURSOR.length()), null);
  }

  private static String parentCursor(OperationRecord row, int nextRoot) {
    return recordedRefusal(row) == null ? "ingest-parent:1:" + nextRoot : row.checkpointCursor();
  }

  private Optional<OperationRecord> findChild(String parentKey, RecordedRootPlan plan) {
    try { return operations.findIngestChild(parentKey, plan); }
    catch (io.justsearch.app.api.operations.OperationStoreException invalid) {
      if (invalid.code() != io.justsearch.app.api.operations.OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED) throw invalid;
      throw new JobQueue.RecordedWalkGapException("Recorded child binding is unavailable");
    }
  }

  private OperationStore.Preparation preparation(OperationRecord row) {
    return operations.acceptedPreparation(row.id()).orElseThrow(
        () -> new IllegalArgumentException("Recorded preparation disappeared"));
  }

  private void release(Parent parent) {
    if (parents.remove(parent.row.key(), parent)) advanced = true;
    if (parent.child != null) permissions.remove(parent.child.row.key());
    if (parent.cancellationSubscription != null) parent.cancellationSubscription.close();
    if (parent.work != null) { parent.work.close(); parent.work = null; }
  }

  private void observeCancellation(Parent parent) {
    parent.cancellationSubscription = parent.work.onCancel(reason -> {
      parent.cancelled = true;
      Child child = parent.child;
      if (child != null) {
        permissions.remove(child.row.key());
        CancelToken cancellation = child.cancellation;
        if (cancellation != null) cancellation.cancel(reason);
      }
    });
  }

  private static EngineContext unattached(EngineContext context) {
    return new EngineContext(context.clientKind(), context.clientId(), context.sessionId(), context.grantReference(),
        context.sourceTier(), context.transport(), context.survival(), context.urgency());
  }

  private static RecordedRootPlan oneRoot(RecordedRootPlan plan, int index) {
    return new RecordedRootPlan(plan.generation(), List.of(plan.roots().get(index)));
  }

  private static String planHash(RecordedRootPlan plan) { return CanonicalOperationArguments.digest(plan.toReplayPayload()); }
  private static Reconciliation failed(String code) { return new Reconciliation.Failed(new OperationReceipt(code, null)); }
  private static OperationExecution pending(CompletableFuture<OperationResult> completion) {
    return new OperationExecution(OperationResult.success("Recorded ingestion accepted"), completion.minimalCompletionStage());
  }
  private static void finish(CompletableFuture<OperationResult> completion, OperationReceipt receipt) {
    if ("cancelled".equals(receipt.code())) completion.completeExceptionally(new CancellationException("Recorded ingestion cancelled"));
    else completion.complete(OperationResult.failure("Recorded ingestion could not complete", receipt.code(), Map.of(), false));
  }

  private static BulkReindexProgress capturing(Bulk bulk) {
    return new BulkReindexProgress("g-" + bulk.row.key(), bulk.plan.target(),
        BulkReindexProgress.Phase.CAPTURING, null, null);
  }

  private BulkReindexProgress bulkProgress(Bulk bulk) {
    var progress = operations.bulkReindexProgress(bulk.row.id()).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Bulk operation progress disappeared"));
    if (!progress.generationId().equals("g-" + bulk.row.key()) || !progress.target().equals(bulk.plan.target())) {
      throw new JobQueue.RecordedWalkGapException("Bulk target binding changed");
    }
    return progress;
  }

  private JobQueue.WalkProgress bulkWalk(Bulk bulk, Attached physical) {
    var walk = physical.queue.recordedWalk(bulk.row.key()).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Captured bulk walk disappeared"));
    if (!walk.capturedPlan() || !walk.planHash().equals(bulk.plan.planHash())) {
      throw new JobQueue.RecordedWalkGapException("Captured bulk plan binding changed");
    }
    return walk;
  }

  private static BulkReindexProgress.Capture capture(JobQueue.WalkProgress walk) {
    if (walk.enumerationOutcome() != JobQueue.WalkEnumerationOutcome.COMPLETE) {
      throw new JobQueue.RecordedWalkGapException("Bulk capture is not complete");
    }
    return new BulkReindexProgress.Capture(walk.manifestSha256(), walk.plannedUnits());
  }

  private String bulkRefusalReason(Bulk bulk) {
    if (bulk.refusalCode != null) return bulk.refusalCode;
    if (bulk.cancelled || (bulk.work != null && bulk.work.cancellationReason().isPresent())) return "cancelled";
    if (bulk.work == null) return "RECOVERY_AUTHORIZATION_REFUSED";
    var decision = authority.evaluateRecordedBulk(bulk.row, bulk.preparation);
    if (decision instanceof OperationAuthority.RecordedBulkRecoveryDecision.Refused refused) return refused.receipt().code();
    var authorized = (OperationAuthority.RecordedBulkRecoveryDecision.Authorized) decision;
    return bulk.plan.equals(authorized.plan()) ? null : "RECOVERY_BINDING_INVALID";
  }

  private boolean bulkAuthorized(Bulk bulk) {
    return bulkRefusalReason(bulk) == null;
  }

  private boolean bulkClaimAllowed(Bulk bulk) {
    Attached physical = attached;
    if (physical == null || physical != bulk.physical || physical.stopping || !bulk.ready
        || !physical.online.getAsBoolean() || !bulkAuthorized(bulk)) return false;
    try {
      var runtime = physical.bulkRuntime.current();
      return runtime.isPresent() && runtime.orElseThrow().disposition() == IndexGenerationManager.BootDisposition.BUILDING
          && ("g-" + bulk.row.key()).equals(runtime.orElseThrow().writableGeneration());
    } catch (IOException unavailable) { return false; }
  }

  @Override public boolean recordedCutoverReady(String operationKey) {
    Bulk bulk = bulkPermissions.get(operationKey);
    return bulk != null && bulkClaimAllowed(bulk);
  }

  @Override public boolean beforeRecordedPromotion(String operationKey, JobQueue queue) {
    synchronized (lock) {
      Bulk bulk = bulks.get(operationKey);
      Attached physical = attached;
      if (bulk == null || physical == null || physical.queue != queue || !bulkClaimAllowed(bulk)) return false;
      if (bulkProgress(bulk).refusalCode() != null) return false;
      if (!checkpointBulkSettlement(bulk, physical)) return false;
      return bulkAuthorized(bulk) && bulkClaimAllowed(bulk);
    }
  }

  @Override public IndexGenerationManager.State promoteRecordedGeneration(String operationKey,
      JobQueue queue, CheckedPromotion promotion) throws IOException {
    synchronized (lock) {
      if (!beforeRecordedPromotion(operationKey, queue)) return null;
      var promoted = promotion.promote();
      if (promoted != null) attempts.observeBulkBoundary(bulks.get(operationKey).handle,
          OperationAttemptRunner.BulkBoundary.AFTER_PROMOTION);
      return promoted;
    }
  }

  private Reconciliation reconcileBulk(OperationRecord row, Attached physical) {
    if (physical.stopping || physical.bulkProducer == null || bulks.containsKey(row.key())) return new Reconciliation.Wait();
    OperationStore.Preparation stored;
    RecordedBulkPlan plan;
    try {
      stored = preparation(row);
      plan = new RecordedBulkPlanResolver().resolve(row, stored);
      var progress = operations.bulkReindexProgress(row.id());
      if (progress.isPresent() && progress.orElseThrow().refusalCode() != null) {
        return reconcileBulkRefusal(row, plan, physical, progress.orElseThrow().refusalCode());
      }
      var decision = authority.evaluateRecordedBulk(row, stored);
      if (decision instanceof OperationAuthority.RecordedBulkRecoveryDecision.Refused refused) {
        return reconcileBulkRefusal(row, plan, physical, refused.receipt().code());
      }
      var runtime = physical.bulkRuntime.current();
      if (progress.isPresent() && progress.orElseThrow().phase() == BulkReindexProgress.Phase.SETTLED
          && promotedTarget(row.key(), runtime)) {
        requireBulkSettlement(row, plan, physical, progress.orElseThrow());
        return progress.orElseThrow().settlement().gaps().isEmpty()
            ? new Reconciliation.Complete(new OperationReceipt("SUCCESS", null))
            : failed("PROMOTED_WITH_GAPS");
      }
    } catch (IOException unavailable) { return new Reconciliation.Wait(); }
    catch (IllegalArgumentException | JobQueue.RecordedWalkGapException invalid) {
      return physical.queue.hasIssuedRecordedClaims(row.key()) ? new Reconciliation.Wait()
          : failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    if (row.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS) {
      return reconcileBulkRefusal(row, plan, physical, RecordedIngestionSettlement.EXHAUSTED);
    }
    return new Reconciliation.Resume(handle -> {
      var bulk = new Bulk(row, stored, plan, handle, row.state() == OperationState.ACCEPTED);
      bulks.put(row.key(), bulk);
      if (operations.bulkReindexProgress(row.id()).isEmpty()) attempts.checkpointBulkReindex(handle, capturing(bulk));
      advanced = true;
      return pending(bulk.completion);
    });
  }

  private Reconciliation reconcileBulkRefusal(OperationRecord row, RecordedBulkPlan plan,
      Attached physical, String reason) {
    physical.bulkRefusalRestartKeys.add(row.key());
    if (physical.queue.hasIssuedRecordedClaims(row.key())) return new Reconciliation.Wait();
    var found = physical.queue.recordedWalk(row.key());
    var observedProgress = operations.bulkReindexProgress(row.id());
    if (observedProgress.isEmpty()) return bulkRefusalDecision(found.isEmpty() ? reason : RecordedIngestionSettlement.UNAVAILABLE);
    var progress = observedProgress.orElseThrow();
    if (!progress.generationId().equals("g-" + row.key()) || !progress.target().equals(plan.target())) {
      return failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    if (progress.refusalCode() == null) {
      advanced = true;
      return new Reconciliation.CheckpointBulkAndWait(progress.withRefusal(reason));
    }
    reason = progress.refusalCode();
    if (found.isEmpty()) return bulkRefusalDecision(reason);
    var walk = found.orElseThrow();
    if (!walk.capturedPlan() || !walk.planHash().equals(plan.planHash())) {
      return failed(RecordedIngestionSettlement.UNAVAILABLE);
    }
    if (walk.enumerationClosedAt() == null) {
      physical.queue.closeRecordedWalkEnumeration(row.key(), walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.FAILED);
    } else if (walk.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.COMPLETE) {
      physical.queue.retireRefusedRecordedWalk(row.key(), plan.planHash());
    }
    physical.queue.trySealRecordedWalk(row.key());
    if (physical.queue.sealedRecordedWalkReceipt(row.key()).isEmpty()) return new Reconciliation.Wait();
    if (progress.phase() != BulkReindexProgress.Phase.CAPTURING) {
      var settled = physical.queue.capturedWalkSettlement(row.key()).orElseThrow(
          () -> new JobQueue.RecordedWalkGapException("Refused bulk settlement disappeared"));
      var next = settlementProgress(row.key(), plan, settled).withRefusal(reason);
      if (!progress.capture().equals(next.capture())
          || (progress.phase() == BulkReindexProgress.Phase.SETTLED && !progress.equals(next))) {
        return failed(RecordedIngestionSettlement.UNAVAILABLE);
      }
      if (!progress.equals(next)) {
        advanced = true;
        return new Reconciliation.CheckpointBulkAndWait(next);
      }
      requireBulkSettlement(row, plan, physical, progress);
    }
    return bulkRefusalDecision(reason);
  }

  private static Reconciliation bulkRefusalDecision(String reason) {
    return "cancelled".equals(reason) ? new Reconciliation.Cancelled(new OperationReceipt(reason, null)) : failed(reason);
  }

  private void observeBulkCancellation(Bulk bulk) {
    bulk.cancellationSubscription = bulk.work.onCancel(reason -> {
      bulk.cancelled = true;
      synchronized (lock) {
        bulkPermissions.remove(bulk.row.key());
        CancelToken cancellation = bulk.cancellation;
        if (cancellation != null) cancellation.cancel(reason);
      }
      maintain();
    });
  }

  private void driveBulk(Bulk bulk, Attached physical) {
    var row = operations.find(bulk.row.key()).orElseThrow();
    if (row.state().terminal()) {
      bulkPermissions.remove(row.key());
      bulk.ready = false;
      if (bulk.refusalCode != null) physical.bulkRefusalRestartKeys.add(row.key());
      acknowledgeBulk(row, bulk.plan, physical);
      if (bulks.remove(row.key(), bulk)) advanced = true;
      if (bulk.cancellationSubscription != null) bulk.cancellationSubscription.close();
      if (bulk.work != null) bulk.work.close();
      return;
    }
    if (bulk.completion.isDone() || physical.stopping || physical.bulkProducer == null) return;
    if (bulk.physical != physical) {
      bulkPermissions.remove(row.key());
      bulk.physical = physical;
      bulk.ready = false;
      bulk.exit = null;
      bulk.notification = null;
      bulk.cancellation = null;
      bulk.restartRequested = false;
      bulk.started = false;
    }
    if (bulk.work == null) {
      try { bulk.work = admission.attach(unattached(row.context())); observeBulkCancellation(bulk); }
      catch (EngineAdmissionException unavailable) {
        if (unavailable.reason() == EngineAdmissionException.Reason.WORK_FINISHED) throw unavailable;
        return;
      }
    }
    try {
      var progress = bulkProgress(bulk);
      bulk.refusalCode = progress.refusalCode();
      String refusal = bulkRefusalReason(bulk);
      if (refusal != null) {
        refuseBulk(bulk, physical, refusal);
        return;
      }
      var runtime = physical.bulkRuntime.current();
      if (progress.phase() == BulkReindexProgress.Phase.SETTLED && promotedTarget(row.key(), runtime)) {
        requireBulkSettlement(row, bulk.plan, physical, progress);
        bulkPermissions.remove(row.key());
        if (progress.settlement().gaps().isEmpty()) bulk.completion.complete(OperationResult.success("Bulk rebuild completed"));
        else finish(bulk.completion, new OperationReceipt("PROMOTED_WITH_GAPS", null));
        return;
      }
      if (progress.phase() == BulkReindexProgress.Phase.SETTLED && runtime.isPresent()
          && runtime.orElseThrow().disposition() == IndexGenerationManager.BootDisposition.PROMOTED
          && progress.generationId().equals(runtime.orElseThrow().activeGeneration())
          && (!runtime.orElseThrow().promotedBoot() || runtime.orElseThrow().writableGeneration() == null)) {
        // The old process can observe the pointer before its replacement proves the new writer.
        bulkPermissions.remove(row.key());
        bulk.ready = false;
        return;
      }
      if (runtime.isPresent() && runtime.orElseThrow().disposition() == IndexGenerationManager.BootDisposition.FENCED) {
        refuseBulk(bulk, physical, "BULK_GENERATION_REFUSED");
        return;
      }
      if (bulk.started && runtime.isEmpty()) {
        finishBulkStart(bulk, physical, bulkWalk(bulk, physical));
        return;
      }
      if (progress.phase() == BulkReindexProgress.Phase.CAPTURING) {
        if (runtime.isPresent() && runtime.orElseThrow().disposition() == IndexGenerationManager.BootDisposition.BUILDING) {
          // Exact state-before-binding crash: queue capture and the boot target witness already agree.
          progress = new BulkReindexProgress(progress.generationId(), bulk.plan.target(),
              BulkReindexProgress.Phase.BUILDING, capture(bulkWalk(bulk, physical)), null);
          attempts.checkpointBulkReindex(bulk.handle, progress);
        } else {
          captureBulk(bulk, physical);
          return;
        }
      }
      if (runtime.isPresent() && runtime.orElseThrow().disposition() != IndexGenerationManager.BootDisposition.BUILDING) {
        refuseBulk(bulk, physical, "BULK_GENERATION_REFUSED");
        return;
      }
      if (runtime.isPresent() && runtime.orElseThrow().disposition() == IndexGenerationManager.BootDisposition.BUILDING
          && progress.generationId().equals(runtime.orElseThrow().writableGeneration())) {
        if (!progress.capture().equals(capture(bulkWalk(bulk, physical)))) {
          throw new JobQueue.RecordedWalkGapException("Bulk capture differs from durable operation progress");
        }
        bulk.ready = true;
        Bulk previous = bulkPermissions.put(row.key(), bulk);
        if (previous == null) physical.queue.recoverStuckJobs();
      }
    } catch (IOException unavailable) {
      bulkPermissions.remove(row.key());
      log.debug("Bulk generation observation awaits readable current state", unavailable);
    } catch (JobQueue.RecordedWalkGapException unavailable) {
      bulkPermissions.remove(row.key());
      if (!physical.queue.hasIssuedRecordedClaims(row.key())) {
        finish(bulk.completion, new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null));
      }
    }
  }

  private void captureBulk(Bulk bulk, Attached physical) {
    if (bulk.restartRequested || !physical.online.getAsBoolean()) return;
    var context = bulk.work.context();
    if (!bulk.plan.scope().generation().equals(physical.bulkIndexing.captureRebuildGeneration(context))
        || !bulk.plan.target().equals(physical.bulkIndexing.captureIndexTarget(context))) {
      refuseBulk(bulk, physical, "BULK_GENERATION_REFUSED");
      return;
    }
    if (bulk.exit == null) {
      var existing = physical.queue.recordedWalk(bulk.row.key());
      if (existing.isEmpty() && !bulk.createIfMissing) {
        throw new JobQueue.RecordedWalkGapException("Interrupted bulk capture lost its queue evidence");
      }
      var walk = physical.queue.beginCapturedWalk(bulk.row.key(), bulk.plan.planHash(), bulk.createIfMissing);
      bulk.createIfMissing = false;
      bulk.epoch = walk.enumerationEpoch();
      if (walk.enumerationClosedAt() == null) {
        bulk.cancellation = new CancelToken();
        if (!bulkAuthorized(bulk)) return;
        try {
          bulk.exit = physical.bulkProducer.enumerate(bulk.plan.scope(), bulk.row.key(), bulk.epoch,
              context, bulk.cancellation).toCompletableFuture();
        } catch (RuntimeException failure) {
          bulk.exit = CompletableFuture.failedFuture(failure);
        }
        bulk.notification = bulk.exit.handle((ignored, failure) -> {
          try { maintain(); }
          catch (RuntimeException retryable) { log.error("Captured producer completion awaits maintenance", retryable); }
          return null;
        });
      }
    }
    if (bulk.exit != null && !bulk.exit.isDone()) return;
    flushBulkEnumeration(bulk, physical);
    var walk = bulkWalk(bulk, physical);
    if (walk.enumerationClosedAt() == null) return;
    if (walk.enumerationOutcome() != JobQueue.WalkEnumerationOutcome.COMPLETE) {
      refuseBulk(bulk, physical, bulk.cancelled ? "cancelled" : "BULK_CAPTURE_FAILED");
      return;
    }
    if (!bulkAuthorized(bulk)) { refuseBulk(bulk, physical, "RECOVERY_AUTHORIZATION_REFUSED"); return; }
    if (!bulk.plan.scope().generation().equals(physical.bulkIndexing.captureRebuildGeneration(context))
        || !bulk.plan.target().equals(physical.bulkIndexing.captureIndexTarget(context))) {
      refuseBulk(bulk, physical, "BULK_GENERATION_REFUSED");
      return;
    }
    finishBulkStart(bulk, physical, walk);
  }

  private void finishBulkStart(Bulk bulk, Attached physical, JobQueue.WalkProgress walk) {
    if (bulk.restartRequested || !bulkAuthorized(bulk)) return;
    var outcome = physical.bulkIndexing.startRecordedMigration(bulk.row.key(), bulk.plan.source(),
        bulk.plan.target().fingerprint(), bulk.plan.scope().generation(), bulk.work.context());
    String target = "g-" + bulk.row.key();
    if (!outcome.accepted() || !target.equals(outcome.buildingGenerationId())
        || !bulk.plan.scope().generation().equals(outcome.activeGenerationId())
        || !("MIGRATING".equals(outcome.migrationState()) || "SWITCHING".equals(outcome.migrationState()))) {
      refuseBulk(bulk, physical, "BULK_GENERATION_REFUSED");
      return;
    }
    bulk.started = true;
    attempts.checkpointBulkReindex(bulk.handle, new BulkReindexProgress(target, bulk.plan.target(),
        BulkReindexProgress.Phase.BUILDING, capture(walk), null));
    if (!bulkAuthorized(bulk)) { refuseBulk(bulk, physical, "RECOVERY_AUTHORIZATION_REFUSED"); return; }
    bulk.restartRequested = true;
    try { physical.bulkRestart.run(); }
    catch (RuntimeException unavailable) { bulk.restartRequested = false; throw unavailable; }
  }

  private void flushBulkEnumeration(Bulk bulk, Attached physical) {
    if (bulk.physical != physical || bulk.exit == null || !bulk.exit.isDone()) return;
    JobQueue.WalkEnumerationOutcome outcome;
    try { outcome = bulk.exit.join(); }
    catch (java.util.concurrent.CompletionException | CancellationException failure) {
      if (physical.stopping && !bulk.cancelled) return;
      outcome = bulk.cancelled ? JobQueue.WalkEnumerationOutcome.CANCELLED : JobQueue.WalkEnumerationOutcome.FAILED;
    }
    if (outcome == JobQueue.WalkEnumerationOutcome.CANCELLED && physical.stopping && !bulk.cancelled) return;
    var walk = bulkWalk(bulk, physical);
    if (walk.enumerationEpoch() != bulk.epoch) throw new JobQueue.RecordedWalkGapException("Captured producer epoch changed");
    if (walk.enumerationClosedAt() == null) physical.queue.closeRecordedWalkEnumeration(bulk.row.key(), bulk.epoch, outcome);
  }

  private boolean checkpointBulkSettlement(Bulk bulk, Attached physical) {
    var progress = bulkProgress(bulk);
    if (progress.phase() == BulkReindexProgress.Phase.CAPTURING) return false;
    var walk = bulkWalk(bulk, physical);
    if (!progress.capture().equals(capture(walk))) throw new JobQueue.RecordedWalkGapException("Bulk capture changed before settlement");
    if (physical.queue.hasIssuedRecordedClaims(bulk.row.key())) return false;
    physical.queue.trySealRecordedWalk(bulk.row.key());
    var settled = physical.queue.capturedWalkSettlement(bulk.row.key());
    if (settled.isEmpty()) return false;
    var next = settlementProgress(bulk.row.key(), bulk.plan, settled.orElseThrow());
    if (progress.refusalCode() != null) next = next.withRefusal(progress.refusalCode());
    if (progress.phase() == BulkReindexProgress.Phase.SETTLED && !progress.equals(next)) {
      throw new JobQueue.RecordedWalkGapException("Immutable bulk settlement changed");
    }
    if (!progress.equals(next)) attempts.checkpointBulkReindex(bulk.handle, next);
    return true;
  }

  private static BulkReindexProgress settlementProgress(String key, RecordedBulkPlan plan,
      JobQueue.CapturedWalkSettlement settlement) {
    var gaps = settlement.gaps().stream().map(unit -> new OperationOutcomeView.Gap(unit.pathHash(), unit.reasonCode())).toList();
    var history = settlement.processingHistory().stream().map(unit -> new BulkReindexProgress.ProcessingEvent(
        unit.pathHash(), unit.unitRevision(), unit.plannedSourceSha256(), unit.contentHash(), unit.coverage(),
        unit.outcomeClass(), unit.reasonCode(), unit.retryPolicy())).toList();
    return new BulkReindexProgress("g-" + key, plan.target(), BulkReindexProgress.Phase.SETTLED,
        new BulkReindexProgress.Capture(settlement.manifestSha256(), settlement.plannedUnits()),
        new BulkReindexProgress.Settlement(settlement.revision(), settlement.sha256(), settlement.failedEvents(),
            settlement.supersededEvents(), gaps, history));
  }

  private static boolean promotedTarget(String key, Optional<BulkRuntime> runtime) {
    return runtime.isPresent() && runtime.orElseThrow().promotedBoot()
        && runtime.orElseThrow().disposition() == IndexGenerationManager.BootDisposition.PROMOTED
        && ("g-" + key).equals(runtime.orElseThrow().activeGeneration())
        && ("g-" + key).equals(runtime.orElseThrow().writableGeneration())
        && "IDLE".equals(runtime.orElseThrow().migrationState());
  }

  private static void requireBulkSettlement(OperationRecord row, RecordedBulkPlan plan,
      Attached physical, BulkReindexProgress progress) {
    var walk = physical.queue.recordedWalk(row.key()).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Bulk settlement walk disappeared"));
    if (!walk.capturedPlan() || !walk.planHash().equals(plan.planHash())) {
      throw new JobQueue.RecordedWalkGapException("Bulk settlement plan changed");
    }
    var settled = physical.queue.capturedWalkSettlement(row.key()).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Bulk settlement disappeared"));
    var expected = settlementProgress(row.key(), plan, settled);
    if (progress.refusalCode() != null) expected = expected.withRefusal(progress.refusalCode());
    if (!progress.equals(expected)
        || progress.unitsCompleted() != row.unitsCompleted() || progress.unitsFailed() != row.unitsFailed()) {
      throw new JobQueue.RecordedWalkGapException("Bulk operation and queue settlement disagree");
    }
  }

  private void refuseBulk(Bulk bulk, Attached physical, String reason) {
    bulkPermissions.remove(bulk.row.key());
    bulk.ready = false;
    var progress = bulkProgress(bulk);
    if (progress.refusalCode() == null) {
      String currentRefusal = bulkRefusalReason(bulk);
      if (currentRefusal != null) reason = currentRefusal;
      progress = progress.withRefusal(reason);
      attempts.checkpointBulkReindex(bulk.handle, progress);
    }
    reason = progress.refusalCode();
    bulk.refusalCode = reason;
    if (bulk.cancellation != null) bulk.cancellation.cancel(reason);
    if ((bulk.exit != null && !bulk.exit.isDone()) || physical.queue.hasIssuedRecordedClaims(bulk.row.key())) return;
    var found = physical.queue.recordedWalk(bulk.row.key());
    if (found.isPresent()) {
      var walk = bulkWalk(bulk, physical);
      if (walk.enumerationClosedAt() == null) physical.queue.closeRecordedWalkEnumeration(bulk.row.key(),
          walk.enumerationEpoch(), "cancelled".equals(reason) ? JobQueue.WalkEnumerationOutcome.CANCELLED : JobQueue.WalkEnumerationOutcome.FAILED);
      else if (walk.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.COMPLETE) {
        physical.queue.retireRefusedRecordedWalk(bulk.row.key(), bulk.plan.planHash());
      }
      physical.queue.trySealRecordedWalk(bulk.row.key());
      if (bulkProgress(bulk).phase() != BulkReindexProgress.Phase.CAPTURING
          && !checkpointBulkSettlement(bulk, physical)) return;
    }
    finish(bulk.completion, new OperationReceipt(reason, null));
  }

  private void acknowledgeBulk(OperationRecord row, RecordedBulkPlan plan, Attached physical) {
    if (!row.state().terminal()) return;
    var walk = physical.queue.recordedWalk(row.key());
    if (walk.isEmpty()) return;
    if (!walk.orElseThrow().capturedPlan() || !walk.orElseThrow().planHash().equals(plan.planHash())) {
      throw new JobQueue.RecordedWalkGapException("Terminal bulk plan changed");
    }
    var receipt = physical.queue.sealedRecordedWalkReceipt(row.key());
    if (receipt.isEmpty()) return;
    var progress = operations.bulkReindexProgress(row.id()).orElseThrow(
        () -> new JobQueue.RecordedWalkGapException("Terminal bulk progress disappeared"));
    if (!progress.generationId().equals("g-" + row.key()) || !progress.target().equals(plan.target())) {
      throw new JobQueue.RecordedWalkGapException("Terminal bulk target changed");
    }
    String code = row.receipt() == null ? null : row.receipt().code();
    if (progress.refusalCode() != null) {
      if (!progress.refusalCode().equals(code)
          || row.state() != ("cancelled".equals(code) ? OperationState.CANCELLED : OperationState.FAILED)) {
        throw new JobQueue.RecordedWalkGapException("Terminal bulk refusal does not match its durable decision");
      }
    } else if (!((row.state() == OperationState.COMPLETE && "SUCCESS".equals(code))
        || (row.state() == OperationState.FAILED && "PROMOTED_WITH_GAPS".equals(code)))) {
      throw new JobQueue.RecordedWalkGapException("Terminal bulk lacks a matching completion or refusal witness");
    }
    if (progress.phase() == BulkReindexProgress.Phase.SETTLED) {
      requireBulkSettlement(row, plan, physical, progress);
      if ((row.state() == OperationState.COMPLETE && !progress.settlement().gaps().isEmpty())
          || ("PROMOTED_WITH_GAPS".equals(code) && progress.settlement().gaps().isEmpty())) {
        throw new JobQueue.RecordedWalkGapException("Terminal bulk completion contradicts its gaps");
      }
    } else if (progress.phase() != BulkReindexProgress.Phase.CAPTURING || progress.refusalCode() == null
        || receipt.orElseThrow().completedUnits() != 0 || receipt.orElseThrow().failedUnits() != 0
        || receipt.orElseThrow().currentFailedUnits() != 0 || row.unitsCompleted() != 0 || row.unitsFailed() != 0) {
      throw new JobQueue.RecordedWalkGapException("Terminal bulk refusal lacks matching evidence");
    }
    physical.queue.acknowledgeRecordedWalk(row.key(), receipt.orElseThrow().revision());
  }

  private void restartAfterBulkRefusal(Attached physical) {
    if (physical.stopping || physical.bulkRefusalRestartRequested || physical.bulkRestart == null
        || physical.bulkRefusalRestartKeys.isEmpty()) return;
    for (String key : physical.bulkRefusalRestartKeys) {
      if (!operations.find(key).map(row -> row.state().terminal()).orElse(false)) return;
    }
    // A replacement releases an obsolete CAPTURING boot or fences an unowned refused Green.
    physical.bulkRefusalRestartRequested = true;
    try { physical.bulkRestart.run(); }
    catch (RuntimeException unavailable) {
      physical.bulkRefusalRestartRequested = false;
      log.warn("Recorded refusal awaits its owned restart", unavailable);
    }
  }

  private void repairBulkAcknowledgements(Attached physical) {
    if (physical.bulkProducer == null || physical.stopping) return;
    var keys = physical.queue.unacknowledgedCapturedWalkKeys(physical.bulkAckCursor, 256);
    for (String key : keys) {
      var row = operations.find(key);
      if (row.isEmpty() || !row.orElseThrow().state().terminal() || !isBulk(row.orElseThrow())) continue;
      try {
        var plan = new RecordedBulkPlanResolver().resolve(row.orElseThrow(), preparation(row.orElseThrow()));
        acknowledgeBulk(row.orElseThrow(), plan, physical);
      } catch (IllegalArgumentException | JobQueue.RecordedWalkGapException mismatch) {
        log.warn("Retaining contradictory terminal bulk acknowledgement evidence for {}", key, mismatch);
      }
    }
    physical.bulkAckCursor = keys.size() == 256 ? keys.getLast() : null;
    // The cursor strictly advances, including across retained contradictions; finish this inventory.
    if (physical.bulkAckCursor != null) advanced = true;
  }

  private static final class Bulk {
    final OperationRecord row;
    final OperationStore.Preparation preparation;
    final RecordedBulkPlan plan;
    final OperationRecordHandle handle;
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    volatile Attached physical;
    EngineWorkHandle work;
    EngineWorkHandle.Registration cancellationSubscription;
    volatile boolean cancelled;
    volatile String refusalCode;
    volatile boolean ready;
    boolean createIfMissing;
    boolean restartRequested;
    boolean started;
    long epoch;
    volatile CancelToken cancellation;
    CompletableFuture<JobQueue.WalkEnumerationOutcome> exit;
    CompletableFuture<Void> notification;
    Bulk(OperationRecord row, OperationStore.Preparation preparation, RecordedBulkPlan plan,
        OperationRecordHandle handle, boolean createIfMissing) {
      this.row = row; this.preparation = preparation; this.plan = plan; this.handle = handle;
      this.createIfMissing = createIfMissing;
    }
  }

  private static final class Parent {
    final OperationRecord row;
    final OperationStore.Preparation preparation;
    final RecordedRootPlan plan;
    final OperationRecordHandle handle;
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    final boolean fromBoot;
    boolean fresh;
    boolean bindingUnavailable;
    Attached physical;
    EngineWorkHandle work;
    EngineWorkHandle.Registration cancellationSubscription;
    volatile boolean cancelled;
    volatile Child child;
    OperationReceipt refusal;
    int nextRoot;
    long completed;
    long failed;
    Parent(OperationRecord row, OperationStore.Preparation preparation, RecordedRootPlan plan,
        OperationRecordHandle handle, boolean fresh) {
      this.row = row; this.preparation = preparation; this.plan = plan; this.handle = handle; this.fresh = fresh; this.fromBoot = !fresh;
    }
  }

  private static final class Child {
    final OperationRecord row;
    final OperationRecordHandle handle;
    final RecordedRootPlan plan;
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    boolean fresh;
    boolean createIfMissing;
    boolean retryEnumeration;
    Attached physical;
    long epoch;
    volatile CancelToken cancellation;
    CompletableFuture<JobQueue.WalkEnumerationOutcome> exit;
    CompletableFuture<Void> notification;
    Child(OperationRecord row, OperationRecordHandle handle, RecordedRootPlan plan, boolean fresh, boolean createIfMissing) {
      this.row = row; this.handle = handle; this.plan = plan; this.fresh = fresh; this.createIfMissing = createIfMissing;
    }
  }

  private record Permission(Attached physical, OperationRecord parent, OperationStore.Preparation preparation,
      RecordedRootPlan plan, boolean fresh, Parent owner) {}

  private final class Attached implements Attachment {
    final JobQueue queue;
    final CheckedServingGeneration generation;
    final BooleanSupplier online;
    final CheckedBulkRuntime bulkRuntime;
    final RecordedIngestionSettlement settlement;
    volatile boolean stopping;
    boolean flushing;
    Producer producer;
    Producer bulkProducer;
    IndexingService bulkIndexing;
    Runnable bulkRestart;
    String bulkAckCursor;
    final Set<String> bulkRefusalRestartKeys = new HashSet<>();
    boolean bulkRefusalRestartRequested;
    JobQueue.WalkSubscription queueSubscription;
    AutoCloseable operationSubscription;
    Attached(JobQueue queue, CheckedServingGeneration generation, BooleanSupplier online,
        CheckedBulkRuntime bulkRuntime) {
      this.bulkRuntime = Objects.requireNonNull(bulkRuntime, "bulkRuntime");
      this.queue = Objects.requireNonNull(queue, "queue"); this.generation = Objects.requireNonNull(generation, "generation");
      this.online = Objects.requireNonNull(online, "online"); this.settlement = new RecordedIngestionSettlement(operations, queue);
    }
    Optional<String> generationValue() {
      try { return generation.current(); }
      catch (IOException unavailable) { throw new UncheckedIOException(unavailable); }
    }
    @Override public void close() throws IOException {
      synchronized (lock) {
        if (attached != this) return;
        permissions.clear();
        bulkPermissions.clear();
        stopping = true;
        for (Bulk bulk : bulks.values()) {
          if (bulk.exit != null && !bulk.exit.isDone()) throw new IOException("Captured producer still running");
          if (queue.hasIssuedRecordedClaims(bulk.row.key())) throw new IOException("Captured claim still running");
          flushBulkEnumeration(bulk, this);
        }
        for (Parent parent : parents.values()) {
          parent.fresh = false;
          if (parent.child != null) {
            parent.child.fresh = false;
            if (parent.child.exit != null && !parent.child.exit.isDone()) throw new IOException("Recorded producer still running");
            if (queue.hasIssuedRecordedClaims(parent.child.row.key())) throw new IOException("Recorded claim still running");
          }
        }
        // Queue drain has finished. Consume completed enumeration and receipts without starting new work.
        for (Parent parent : List.copyOf(parents.values())) {
          Child child = parent.child;
          if (child == null) continue;
          OperationRecord row = operations.find(child.row.key()).orElseThrow();
          boolean neverCreated = false;
          if (child.createIfMissing) {
            try { neverCreated = queue.recordedWalk(row.key()).isEmpty(); }
            catch (JobQueue.RecordedWalkGapException unavailable) { /* Settlement preserves the unavailable result. */ }
          }
          if (!row.state().terminal() && !neverCreated) {
            flushEnumeration(parent, child);
            settlement.settleRunning(row, planHash(child.plan), true, child.handle)
                .ifPresent(outcome -> transferSettlement(child, outcome));
          }
          row = operations.find(child.row.key()).orElseThrow();
          if (row.state().terminal() && !parent.bindingUnavailable) acknowledgeOrUnavailable(row, child.plan, this);
        }
        flushing = true;
        try {
          for (Parent parent : List.copyOf(parents.values())) drive(parent, this, Set.of(), false);
        } finally { flushing = false; }
        try { closeSubscriptions(); }
        catch (Exception failure) { throw new IOException("Recorded ingestion subscription close failed", failure); }
        attached = null;
      }
    }
    private void flushEnumeration(Parent parent, Child child) throws IOException {
      if (child.exit == null || child.physical != this || child.retryEnumeration || parent.bindingUnavailable) return;
      JobQueue.WalkEnumerationOutcome outcome;
      try { outcome = child.exit.join(); }
      catch (java.util.concurrent.CompletionException | CancellationException stopped) {
        if (!parent.cancelled) return; // Physical replacement preserves unfinished enumeration for replay.
        outcome = JobQueue.WalkEnumerationOutcome.CANCELLED;
      }
      if (outcome == JobQueue.WalkEnumerationOutcome.CANCELLED && !parent.cancelled) return;
      try {
        var progress = queue.recordedWalk(child.row.key()).orElseThrow(
            () -> new JobQueue.RecordedWalkGapException("Completed producer progress disappeared"));
        if (!progress.planHash().equals(planHash(child.plan)) || progress.enumerationEpoch() != child.epoch) {
          throw new JobQueue.RecordedWalkGapException("Completed producer binding changed");
        }
        if (progress.enumerationClosedAt() == null) {
          queue.closeRecordedWalkEnumeration(child.row.key(), child.epoch, outcome);
        }
      } catch (JobQueue.RecordedWalkGapException unavailable) {
        throw new IOException("Cannot flush completed recorded producer", unavailable);
      }
    }

    void closeSubscriptions() throws Exception {
      if (queueSubscription != null) queueSubscription.close();
      if (operationSubscription != null) operationSubscription.close();
    }
  }
}
