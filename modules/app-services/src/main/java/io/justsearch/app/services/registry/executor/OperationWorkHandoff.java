/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.core.context.EngineContext;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Process-local caller/effect handoff; the runner alone accepts and completes durable rows. */
final class OperationWorkHandoff implements AutoCloseable {
  private final Object lock = new Object();
  private final EngineAdmissionService admission;
  private final EngineContext effect;
  private EngineWorkHandle parent;
  private EngineWorkHandle work;
  private EngineWorkHandle.Registration parentCancellation;
  private EngineWorkHandle.Registration parentCompletion;
  private EngineWorkHandle.Registration workCancellation;
  private RuntimeException unavailableParent;
  private String ended;
  private boolean accepted;
  private boolean observingCompletion;

  OperationWorkHandoff(EngineAdmissionService admission, EngineContext caller, EngineContext effect) {
    this.admission = admission;
    this.effect = effect;
    if (caller.workId().isPresent() && !caller.workId().equals(effect.workId())) {
      try {
        parent = admission.attach(caller);
        parentCancellation = parent.onCancel(reason -> parentEnded(reason, true));
        parentCompletion = parent.onCompletion(() -> parentEnded("Caller work completed", false));
      } catch (RuntimeException unavailable) {
        // Final runner lookup still wins over this refusal if another caller already accepted.
        unavailableParent = unavailable;
      }
    }
  }

  EngineContext accept(OperationAttemptRunner.AcceptanceScope scope,
      Function<EngineContext, EngineContext> authorize) {
    if (unavailableParent != null) throw unavailableParent;
    work = admission.attach(effect);
    workCancellation = work.onCancel(reason -> {
      synchronized (lock) {
        if (!accepted && ended == null) ended = reason;
      }
    });
    synchronized (lock) {
      if (ended != null || work.cancellationReason().isPresent()
          || (parent != null && parent.cancellationReason().isPresent())) {
        throw new CancellationException("Caller work cancelled before operation acceptance");
      }
      EngineContext authorized = authorize.apply(work.context());
      scope.accept(authorized);
      accepted = true;
      return authorized;
    }
  }

  EngineWorkHandle work() { return work; }

  /** Run outside the runner stripe, before the dispatcher relinquishes its effect reference. */
  void accepted() {
    var _ = work.onCompletion(this::removeParentListeners);
    observingCompletion = true;
    if (parent != null) parent.close();
  }

  private void parentEnded(String reason, boolean cancelled) {
    EngineWorkHandle child;
    synchronized (lock) {
      if (ended == null) ended = reason;
      child = accepted ? work : null;
    }
    if (child == null) return;
    if (child.context().survival() == EngineContext.Survival.DURABLE) child.waitingClientGone();
    else if (cancelled) child.cancel(reason);
  }

  private void removeParentListeners() {
    if (parentCancellation != null) parentCancellation.close();
    if (parentCompletion != null) parentCompletion.close();
    if (workCancellation != null) workCancellation.close();
  }

  @Override public void close() {
    if (!accepted || !observingCompletion) removeParentListeners();
    if (parent != null) parent.close();
    if (work != null) work.close();
  }
}
