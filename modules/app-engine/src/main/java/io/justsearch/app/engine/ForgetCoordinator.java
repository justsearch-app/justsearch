/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.CanonicalOperationArguments;
import io.justsearch.app.api.operations.ForgetOutcome;
import io.justsearch.app.api.operations.ForgetPage;
import io.justsearch.app.api.operations.ForgetPlan;
import io.justsearch.app.api.operations.ForgetProgress;
import io.justsearch.app.api.operations.ForgetService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.services.settings.ForgetPreparation;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One bounded synchronous segment per explicit UI request. Durable intent lives in operations.db. */
final class ForgetCoordinator implements ForgetService {
  private final EngineRoot root;
  private final OperationStore store;
  private final OperationAttemptRunner attempts;
  private final EngineAdmissionService admission;
  private final ReentrantReadWriteLock publication;

  ForgetCoordinator(EngineRoot root, OperationStore store, OperationAttemptRunner attempts,
      EngineAdmissionService admission, ReentrantReadWriteLock publication) {
    this.root = root;
    this.store = store;
    this.attempts = attempts;
    this.admission = admission;
    this.publication = publication;
  }

  @Override public ForgetPlan prepare(String path, LiteralExclusion.Scope scope, EngineContext context) {
    requireWebview(context);
    var request = new ForgetPlan.Request(path, scope);
    final ForgetPlan plan;
    try { plan = root.withForgetAuthority((bindings, worker) -> {
      Path target = Path.of(request.path());
      validateRootScope(target, scope, bindings);
      worker.validateForgetTarget(target, scope);
      var covering = coveringRoots(target, bindings);
      return new ForgetPlan(1, target.toString(), scope,
          scope == LiteralExclusion.Scope.FOLDER || !covering.isEmpty()
              ? LiteralExclusion.create(target, scope) : null,
          covering, ForgetPlan.Source.FILE_SYSTEM);
    }); } catch (io.justsearch.indexerworker.services.WorkerServiceException unavailable) {
      if (unavailable.status() == io.justsearch.indexerworker.services.WorkerServiceException.Status.INVALID_ARGUMENT) {
        throw new IllegalArgumentException("This target belongs to protected data controls");
      }
      throw new IllegalStateException("The indexed target's ownership could not be verified");
    }
    attempts.preflightForget(plan);
    return plan;
  }

  @Override public OperationExecution execute(OperationRecordHandle handle, EngineContext context) {
    requireWebview(context);
    if (!root.forgetQueueEntryAllowed()) {
      throw new IllegalStateException("Forget cannot reenter from a queue owner or callback");
    }
    try (var ignoredWork = admission.attach(context)) {
      return OperationExecution.finished(runSegment(handle));
    }
  }

  @Override public OperationResult retry(String key, EngineContext context) {
    requireWebview(context);
    rejectQueueReentry();
    OperationKeys.timestampMillis(key);
    attempts.resumeForget(key, handle -> execute(handle, context));
    return response(observe(key, context).orElseGet(() -> unknown(key, null)));
  }

  @Override public OperationResult withdraw(String key, EngineContext context) {
    requireWebview(context);
    rejectQueueReentry();
    OperationKeys.timestampMillis(key);
    OperationRecord row = store.find(key).orElseThrow(() -> new IllegalArgumentException("Forget intent is unavailable"));
    requireForget(row);
    // Monotonic and durable even when the current bounded segment has not exited yet.
    attempts.requestForgetWithdrawal(key);
    OperationResult result = retry(key, context);
    if (result.structuredData().get("forget") instanceof ForgetOutcome actual
        && actual.status() == ForgetOutcome.Status.COMPLETE) {
      return response(new ForgetOutcome(actual.operationKey(), actual.path(), actual.scope(),
          actual.status(), actual.removal(), actual.keepOut(), actual.reason(),
          "This forget is already complete. Withdrawal cannot reverse it. "
              + switch (actual.keepOut()) {
                case NOT_REQUIRED -> "This file has no permanent keep-out; a new deliberate add may restore it. ";
                case INSTALLED -> "To permit later indexing, remove the exact keep-out separately in Library. ";
                case RELEASED -> "Its exact keep-out has already been released. ";
                case NOT_INSTALLED -> "This intent has no installed keep-out to withdraw. ";
                case UNKNOWN -> "The current keep-out could not be confirmed. Review it in Library. ";
              } + "No indexed rows or original files are restored."));
    }
    return result;
  }

  private OperationResult runSegment(OperationRecordHandle handle) {
    ForgetPlan plan = null;
    try {
      OperationRecord initial = current(handle);
      plan = plan(initial);
      if (!attempts.reconcileForgetProjection(handle)) return park(handle, ForgetProgress.Reason.POLICY_UNKNOWN);
      var progress = progress(handle);
      if (progress.withdrawalRequested()) return withdrawSegment(handle, plan);

      if (!progress.admitted() || plan.keepOutRequired() && progress.installedToken() == null) {
        // Prepare outside physical ownership. Release the logical reservation without observers
        // under those owners; notification follows the entire first scope's unwind.
        var projection = attempts.prepareForgetProjection(handle);
        try {
          ForgetPlan accepted = plan;
          root.withForgetAuthority((bindings, worker) -> {
            validateAcceptedScope(accepted, bindings);
            worker.validateForgetTarget(Path.of(accepted.path()), accepted.scope());
            if (progress(handle).withdrawalRequested()) return null;
            handle.checkpoint(progress(handle).with(ForgetProgress.Phase.ADMITTED,
                ForgetProgress.Reason.NONE).cursor(), 0, 0);
            projection.publish();
            return null;
          });
        } finally { projection.afterOwnersReleased(); }
      }
      if (progress(handle).withdrawalRequested()) return withdrawSegment(handle, plan);

      Long sequence = null;
      if (!plan.keepOutRequired()) {
        var observation = ForgetProducerObservation.inspect(plan, handle.id(),
            store.captureForgetAuthority(), store);
        if (observation.status() != ForgetProducerObservation.Status.CLEAR) {
          return park(handle, ForgetProgress.Reason.OLD_PRODUCER);
        }
        sequence = observation.acceptanceSequence();
      }
      deleteSegment(handle, plan, sequence);
      if (!current(handle).state().terminal() && progress(handle).withdrawalRequested()) {
        return withdrawSegment(handle, plan);
      }
      if (!current(handle).state().terminal()) return park(handle, progress(handle).reason());
      return response(observeRow(current(handle)));
    } catch (SettingsCommitOwner.Refused refused) {
      if ("FORGET_SUPERSEDED".equals(refused.response().errorCode().orElse(null))
          && plan != null && progress(handle).installedToken() != null) {
        return superseded(handle);
      }
      return refuseOrPark(handle, ForgetProgress.Reason.POLICY_UNKNOWN);
    } catch (RuntimeException unavailable) {
      return refuseOrPark(handle, ForgetProgress.Reason.AUTHORITY_UNAVAILABLE);
    }
  }

  private void deleteSegment(OperationRecordHandle handle, ForgetPlan plan, Long sequence) {
    // Kept outside the callback so the guaranteed finally closes queue LAST, including a thrown
    // commit/refresh/checkpoint/receipt or an observer exception during queue delivery.
    WorkerIngestService.ForgetMutation[] mutation = new WorkerIngestService.ForgetMutation[1];
    OperationStore.DeferredForgetCompletion[] completion = new OperationStore.DeferredForgetCompletion[1];
    try {
      root.withForgetAuthority((bindings, worker) -> {
        validateAcceptedScope(plan, bindings);
        worker.validateForgetTarget(Path.of(plan.path()), plan.scope());
        var policy = attempts.forgetPolicy(handle);
        if (policy == SettingsCommitOwner.ForgetPolicy.SUPERSEDED) {
          handle.checkpoint(progress(handle).with(ForgetProgress.Phase.SUPERSEDED,
              ForgetProgress.Reason.SUPERSEDED).cursor(), 0, 0);
          completion[0] = attempts.finishForget(handle, null, OperationState.CANCELLED,
              new OperationReceipt("forget_superseded", null));
          return null;
        }
        if (policy != SettingsCommitOwner.ForgetPolicy.INSTALLED
            && policy != SettingsCommitOwner.ForgetPolicy.NO_KEEP_OUT) {
          throw new IllegalStateException("The exact keep-out is not confirmed");
        }
        if (progress(handle).withdrawalRequested()) return null;
        mutation[0] = worker.tryBeginForgetMutation().orElseThrow(() ->
            new IllegalStateException("An index writer currently owns the mutation boundary"));
        try {
          // Repeat trusted internal-collection validation inside the actual file fence.
          worker.validateForgetTarget(Path.of(plan.path()), plan.scope());
          if (progress(handle).withdrawalRequested()) return null;
          handle.checkpoint(progress(handle).with(ForgetProgress.Phase.REMOVAL_UNCONFIRMED,
              ForgetProgress.Reason.REMOVAL_UNKNOWN).cursor(), 0, 0);
          if (!mutation[0].removeAndVerify(plan)) {
            throw new IllegalStateException("Index and queue absence could not be confirmed");
          }
          handle.checkpoint(progress(handle).with(ForgetProgress.Phase.READY,
              ForgetProgress.Reason.NONE).cursor(), 0, 0);
          completion[0] = attempts.finishForget(handle, sequence, OperationState.COMPLETE,
              new OperationReceipt("forget_complete", null));
          if (completion[0].record().isEmpty()) {
            handle.checkpoint(progress(handle).with(ForgetProgress.Phase.READY,
                ForgetProgress.Reason.ACCEPTANCE_CHANGED).cursor(), 0, 0);
          }
        } finally { mutation[0].releaseFile(); }
        return null;
      });
    } finally {
      try { if (mutation[0] != null) mutation[0].afterOwnersReleased(); }
      finally { if (completion[0] != null) completion[0].publish(); }
    }
  }

  private OperationResult withdrawSegment(OperationRecordHandle handle, ForgetPlan plan) {
    var progress = progress(handle);
    if (!progress.withdrawalRequested()) throw new IllegalStateException("Withdrawal was not recorded");
    if (progress.phase() == ForgetProgress.Phase.WITHDRAWN) return withdrawn(handle);
    if (!plan.keepOutRequired() || !progress.admitted() || progress.installedToken() == null) {
      // Earlier arming has been reconciled before reaching here. No installed token is invented.
      handle.checkpoint(new ForgetProgress(1, ForgetProgress.Phase.WITHDRAWN, progress.admitted(),
          progress.installedToken(), progress.settingsBase(), progress.priorFileWitness(),
          progress.installedFileWitness(), true, ForgetProgress.Reason.WITHDRAWN).cursor(), 0, 0);
      return withdrawn(handle);
    }
    var projection = attempts.prepareForgetProjection(handle);
    try {
      // Reversal needs settings authority, even when the index is offline. It never deletes rows.
      publication.writeLock().lock();
      try { projection.publish(); }
      finally { publication.writeLock().unlock(); }
    } finally { projection.afterOwnersReleased(); }
    return withdrawn(handle);
  }

  private OperationResult withdrawn(OperationRecordHandle handle) {
    var completion = attempts.finishForget(handle, null, OperationState.CANCELLED,
        new OperationReceipt("forget_withdrawn", null));
    completion.publish();
    if (completion.record().isEmpty()) return park(handle, ForgetProgress.Reason.POLICY_UNKNOWN);
    return response(observeRow(completion.record().orElseThrow()));
  }

  private OperationResult superseded(OperationRecordHandle handle) {
    handle.checkpoint(progress(handle).with(ForgetProgress.Phase.SUPERSEDED,
        ForgetProgress.Reason.SUPERSEDED).cursor(), 0, 0);
    var completion = attempts.finishForget(handle, null, OperationState.CANCELLED,
        new OperationReceipt("forget_superseded", null));
    completion.publish();
    if (completion.record().isEmpty()) return park(handle, ForgetProgress.Reason.POLICY_UNKNOWN);
    return response(observeRow(completion.record().orElseThrow()));
  }

  private OperationResult refuseOrPark(OperationRecordHandle handle, ForgetProgress.Reason reason) {
    OperationRecord row = current(handle);
    if (row.state().terminal()) return response(observeRow(row));
    if (!ForgetProgress.read(row.checkpointCursor()).admitted() && row.expectedSettingsRevision() == null) {
      var completion = attempts.finishForget(handle, null, OperationState.FAILED,
          new OperationReceipt("forget_refused", null));
      completion.publish();
      if (completion.record().isPresent()) return response(observeRow(completion.record().orElseThrow()));
    }
    return park(handle, reason);
  }

  private OperationResult park(OperationRecordHandle handle, ForgetProgress.Reason reason) {
    var row = current(handle);
    var previous = ForgetProgress.read(row.checkpointCursor());
    // An unresolved arm retains its exact phase and witnesses. Merely reporting an error cannot
    // turn unknown replacement evidence into an uncommitted or installed save.
    var pending = new ForgetProgress(1, previous.phase(), previous.admitted(), previous.installedToken(),
        previous.settingsBase(), previous.priorFileWitness(), previous.installedFileWitness(),
        previous.withdrawalRequested(), reason);
    attempts.parkForget(handle, pending);
    return response(observeRow(current(handle)));
  }

  @Override public ForgetPage unfinished(long afterId, int limit, EngineContext context) {
    requireWebview(context);
    if (afterId < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid forget page");
    var rows = store.openRecords().stream().filter(row -> isForget(row) && row.id() > afterId)
        .limit((long) limit + 1).toList();
    boolean complete = rows.size() <= limit;
    var page = rows.stream().limit(limit).toList();
    return new ForgetPage(page.stream().map(this::observeRow).toList(), complete,
        complete ? null : page.getLast().id());
  }

  @Override public Optional<ForgetOutcome> observe(String key, EngineContext context) {
    requireWebview(context);
    OperationKeys.timestampMillis(key);
    return store.find(key).filter(ForgetCoordinator::isForget).map(this::observeRow);
  }

  private ForgetOutcome observeRow(OperationRecord row) {
    ForgetPlan plan;
    ForgetProgress progress;
    try { plan = plan(row); progress = ForgetProgress.read(row.checkpointCursor()); }
    catch (RuntimeException unreadable) {
      if (row.state() == OperationState.FAILED && row.receipt() != null
          && ("FORGET_PREPARATION_REFUSED".equals(row.receipt().code())
              || "forget_refused".equals(row.receipt().code()))) {
        // Pure preparation refusals retain the existing generic audit row, without trusted plan
        // metadata. Its known pre-effect receipt is still readable after a lost HTTP response.
        return new ForgetOutcome(row.key(), null, null, ForgetOutcome.Status.REFUSED,
            ForgetOutcome.Removal.UNCONFIRMED, ForgetOutcome.KeepOut.NOT_INSTALLED,
            ForgetOutcome.Reason.AUTHORITY_CHANGED,
            "Forget was refused before operational admission. This request installed no keep-out and removed no indexed content.");
      }
      return unknown(row.key(), null);
    }
    if (row.state() == OperationState.COMPLETE && row.receipt() != null
        && "forget_complete".equals(row.receipt().code())) {
      return outcome(row, plan, ForgetOutcome.Status.COMPLETE, ForgetOutcome.Removal.CONFIRMED,
          keepOut(row, plan), ForgetOutcome.Reason.NONE,
          "Removal from the search index completed. Original files and retained answers, logs and backups were not erased.");
    }
    if (row.state() == OperationState.CANCELLED && row.receipt() != null) {
      boolean withdrawn = "forget_withdrawn".equals(row.receipt().code());
      return outcome(row, plan, withdrawn ? ForgetOutcome.Status.WITHDRAWN : ForgetOutcome.Status.SUPERSEDED,
          ForgetOutcome.Removal.UNCONFIRMED, plan.keepOutRequired() ? ForgetOutcome.KeepOut.RELEASED
              : ForgetOutcome.KeepOut.NOT_REQUIRED,
          withdrawn ? ForgetOutcome.Reason.WITHDRAWN : ForgetOutcome.Reason.ENTRY_REMOVED,
          withdrawn ? "This forget intent ended. "
              + (plan.keepOutRequired() ? "Its own keep-out was released. " : "No permanent keep-out was needed. ")
              + "Prior index removal may already have happened; no rows or original files were restored."
              : "This forget's keep-out was removed or replaced. This intent will not remove more indexed content.");
    }
    if (row.state() == OperationState.FAILED && !progress.admitted()) {
      return outcome(row, plan, ForgetOutcome.Status.REFUSED, ForgetOutcome.Removal.UNCONFIRMED,
          plan.keepOutRequired() ? ForgetOutcome.KeepOut.NOT_INSTALLED : ForgetOutcome.KeepOut.NOT_REQUIRED,
          ForgetOutcome.Reason.AUTHORITY_CHANGED,
          "Forget was refused before operational admission. No keep-out or index removal was performed by this request.");
    }
    var keepOut = keepOut(row, plan);
    boolean unknown = row.expectedSettingsRevision() != null || keepOut == ForgetOutcome.KeepOut.UNKNOWN;
    return outcome(row, plan, unknown ? ForgetOutcome.Status.UNKNOWN : ForgetOutcome.Status.INCOMPLETE,
        progress.phase() == ForgetProgress.Phase.READY
            || progress.phase() == ForgetProgress.Phase.REMOVAL_UNCONFIRMED ? ForgetOutcome.Removal.UNKNOWN
                : ForgetOutcome.Removal.UNCONFIRMED,
        keepOut, progress.withdrawalRequested() ? ForgetOutcome.Reason.WITHDRAWAL_PENDING
            : progress.reason() == ForgetProgress.Reason.OLD_PRODUCER ? ForgetOutcome.Reason.OLD_WORK
            : unknown ? ForgetOutcome.Reason.OUTCOME_UNCONFIRMED : ForgetOutcome.Reason.AUTHORITY_CHANGED,
        progress.withdrawalRequested()
            ? "Withdrawal is not confirmed yet. This intent will not begin another removal while withdrawal remains unresolved. Prior removal may already have happened."
            : "Forget is unfinished. Its removal and keep-out observations are shown separately. Retry or withdraw this intent; original files have not been erased.");
  }

  private ForgetOutcome.KeepOut keepOut(OperationRecord row, ForgetPlan plan) {
    if (!plan.keepOutRequired()) return ForgetOutcome.KeepOut.NOT_REQUIRED;
    if (!root.forgetQueueEntryAllowed()) return ForgetOutcome.KeepOut.UNKNOWN;
    publication.writeLock().lock();
    try {
      return switch (attempts.observeForgetPolicy(row.key())) {
        case INSTALLED -> ForgetOutcome.KeepOut.INSTALLED;
        case NOT_INSTALLED -> ForgetOutcome.KeepOut.NOT_INSTALLED;
        case SUPERSEDED -> ForgetOutcome.KeepOut.RELEASED;
        case NO_KEEP_OUT, UNKNOWN -> ForgetOutcome.KeepOut.UNKNOWN;
      };
    } catch (RuntimeException unavailable) { return ForgetOutcome.KeepOut.UNKNOWN; }
    finally { publication.writeLock().unlock(); }
  }

  private static ForgetOutcome outcome(OperationRecord row, ForgetPlan plan, ForgetOutcome.Status status,
      ForgetOutcome.Removal removal, ForgetOutcome.KeepOut keepOut, ForgetOutcome.Reason reason, String text) {
    return new ForgetOutcome(row.key(), plan.path(), plan.scope(), status, removal, keepOut, reason, text);
  }

  private static ForgetOutcome unknown(String key, ForgetPlan plan) {
    return new ForgetOutcome(key, plan == null ? null : plan.path(), plan == null ? null : plan.scope(),
        ForgetOutcome.Status.UNKNOWN, ForgetOutcome.Removal.UNKNOWN, ForgetOutcome.KeepOut.UNKNOWN,
        ForgetOutcome.Reason.OUTCOME_UNCONFIRMED, "The forget outcome could not be established. No completed removal is being claimed.");
  }

  private static OperationResult response(ForgetOutcome outcome) {
    boolean success = outcome.status() == ForgetOutcome.Status.COMPLETE || outcome.status() == ForgetOutcome.Status.WITHDRAWN;
    return success ? OperationResult.success(outcome.explanation(), Map.of("forget", outcome))
        : OperationResult.failure(outcome.explanation(), "FORGET_" + outcome.status().name(),
            Map.of("forget", outcome), outcome.status() == ForgetOutcome.Status.INCOMPLETE
                || outcome.status() == ForgetOutcome.Status.UNKNOWN);
  }

  private OperationRecord current(OperationRecordHandle handle) {
    OperationRecord row = store.find(handle.key()).orElseThrow();
    if (row.id() != handle.id()) throw new IllegalStateException("Forget identity changed");
    requireForget(row);
    return row;
  }
  private ForgetProgress progress(OperationRecordHandle handle) { return ForgetProgress.read(current(handle).checkpointCursor()); }
  private ForgetPlan plan(OperationRecord row) { return ForgetPreparation.decode(row, store.acceptedPreparation(row.id()).orElseThrow()); }
  private static boolean isForget(OperationRecord row) {
    return row.descriptor().kind() == OperationKind.FORGET && ForgetPlan.OPERATION_ID.equals(row.descriptor().operationRef());
  }
  private static void requireForget(OperationRecord row) {
    if (!isForget(row)) throw new IllegalArgumentException("An accepted forget intent is required");
  }
  private static void requireWebview(EngineContext context) {
    if (context == null || context.clientKind() != EngineContext.ClientKind.WEBVIEW) {
      throw new IllegalArgumentException("Only the webview can control indexed-path forget");
    }
  }

  private void rejectQueueReentry() {
    if (!root.forgetQueueEntryAllowed()) {
      throw new IllegalStateException("Forget cannot reenter from a queue owner or callback");
    }
  }
  private static List<ForgetPlan.WatchedRoot> coveringRoots(Path target, List<RootBinding> bindings) {
    return bindings.stream().filter(binding -> target.startsWith(binding.path()))
        .map(binding -> new ForgetPlan.WatchedRoot(CanonicalOperationArguments.digest(binding.path().toString()),
            binding.path().toString())).toList();
  }
  private static void validateRootScope(Path target, LiteralExclusion.Scope scope, List<RootBinding> bindings) {
    if (target.getParent() == null || bindings.stream().anyMatch(binding -> binding.path().equals(target))
        || scope == LiteralExclusion.Scope.FOLDER
        && bindings.stream().anyMatch(binding -> binding.path().startsWith(target))) {
      throw new IllegalArgumentException("A watched root or its containing folder must use root removal controls");
    }
  }
  private static void validateAcceptedScope(ForgetPlan plan, List<RootBinding> bindings) {
    Path target = Path.of(plan.path());
    validateRootScope(target, plan.scope(), bindings);
    if (!plan.watchedRoots().equals(coveringRoots(target, bindings))) {
      throw new IllegalStateException("The watched-root scope changed since this forget was prepared");
    }
  }
}
