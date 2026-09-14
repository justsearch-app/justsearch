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
  public OperationExecution execute(OperationRecordHandle handle, EngineContext context) {
    synchronized (lock) {
      OperationRecord row = operations.find(handle.key()).orElseThrow();
      if (row.id() != handle.id() || row.state() != OperationState.RUNNING
          || context.workId().isEmpty() || !unattached(context).equals(unattached(row.context()))) {
        throw new IllegalArgumentException("Recorded parent requires its exact admitted context");
      }
      var stored = preparation(row);
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
      parent.work = admission.attach(context);
      observeCancellation(parent);
      parents.put(row.key(), parent);
      startFreshChild(parent, child, oneRoot(plan, 0));
      maintain();
      return pending(parent.completion);
    }
  }

  @Override
  public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
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
    synchronized (lock) {
      if (attached != null) throw new IOException("Recorded ingestion still owns its prior queue");
      Attached physical = new Attached(queue, generation, online);
      attached = physical;
      try {
        physical.queueSubscription = queue.subscribeRecordedWalks(ignored -> maintain());
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

  /** Revoke and request actual producer exit before closing its client; preserve pending parent work. */
  void stopProducers(long timeoutMillis) throws IOException {
    CompletableFuture<?>[] exits;
    synchronized (lock) {
      Attached physical = attached;
      if (physical == null) return;
      physical.stopping = true;
      permissions.clear();
      List<CompletableFuture<?>> pending = new ArrayList<>();
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
    attempts.reconcile(OperationKind.REINDEX,
        row -> reconcileParent(row, physical, openChildParents, unknownChild[0]));
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
      Reconciliation outcome = refusalAfterChildren(row, plan, physical, refused.receipt());
      return unknownChild || openChildParents.contains(row.key()) ? new Reconciliation.Wait() : outcome;
    }
    Optional<Reconciliation> completed = completedParent(row, plan, physical);
    if (completed.isPresent()) return completed.orElseThrow();
    if (row.attempts() >= OperationAttemptRunner.MAX_DURABLE_ATTEMPTS) {
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
      physical.queue.trySealRecordedWalk(row.key());
      if (physical.queue.sealedRecordedWalkReceipt(row.key()).isPresent() || refused) {
        permissions.remove(row.key());
        return physical.settlement.reconcile(row, hash, true);
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
      parent.handle.checkpoint("ingest-parent:1:" + parent.nextRoot, parent.completed, parent.failed);
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
        parent.handle.checkpoint("ingest-parent:1:" + parent.nextRoot, completed, failed);
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
    return authority.evaluateRecordedIngest(row, preparation, physical.generationValue(),
        required -> required instanceof RequiredCapability.WorkerOnline && physical.online.getAsBoolean());
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
    final RecordedIngestionSettlement settlement;
    volatile boolean stopping;
    boolean flushing;
    Producer producer;
    JobQueue.WalkSubscription queueSubscription;
    AutoCloseable operationSubscription;
    Attached(JobQueue queue, CheckedServingGeneration generation, BooleanSupplier online) {
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
        stopping = true;
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
