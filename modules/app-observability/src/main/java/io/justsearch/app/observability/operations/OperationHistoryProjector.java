/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationHistoryMode;
import io.justsearch.app.api.operations.OperationHistoryRow;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One operations-owned source projector: live visibility, bounded startup replay, durable ack. */
public final class OperationHistoryProjector implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(OperationHistoryProjector.class);
  private final OperationStore operations;
  private final OperationHistoryStore history;
  private final OperationHistoryChangeRegistry changes;
  private final ActionLedgerChangeRegistry ledger;
  private final ReentrantLock deliveryGate = new ReentrantLock();
  private EngineExecutorRegistry.Registration registration;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> task;
  private AutoCloseable subscription;
  private volatile boolean stopping;
  private boolean closed;
  // Scheduler-owned enumeration only. Ack/durable progress always comes from the SQL pending bit.
  private OperationHistoryRow startupCursor;
  private long startupUpperId;
  private boolean startupComplete;

  public OperationHistoryProjector(OperationStore operations, OperationHistoryStore history,
      OperationHistoryChangeRegistry changes, ActionLedgerChangeRegistry ledger,
      EngineExecutorRegistry executors) {
    this.operations = Objects.requireNonNull(operations, "operations");
    this.history = Objects.requireNonNull(history, "history");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(executors, "executors");
    try {
      deliveryGate.lock();
      try {
      registration = executors.register(new EngineExecutorSpec("head.operations-history",
          EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.SCHEDULED, 1, 1, 1));
      scheduler = registration.openScheduled(Thread.ofPlatform().daemon().name("operations-history-", 0).factory());
      subscription = Objects.requireNonNull(operations.subscribeCompletions(this::onCompletion), "subscription");
      startupUpperId = operations.historyProjectionUpperId();
      startupComplete = startupUpperId == 0;
      task = scheduler.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
      } catch (RuntimeException | Error failure) {
        stopping = true;
        throw failure;
      } finally { deliveryGate.unlock(); }
    } catch (RuntimeException | Error failure) {
      try { close(); }
      catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private void onCompletion(OperationRecord record) {
    if (!record.state().terminal() || record.historyMode() == OperationHistoryMode.NONE) return;
    var row = OperationHistoryRow.from(record);
    deliveryGate.lock();
    try {
      if (stopping) return;
      var entry = OperationHistoryProjection.entry(row);
      history.append(entry);
      if (stopping) return;
      changes.broadcast(entry);
      if (!stopping && ownsLedger(row)) ledger.publishLiveOperation(entry);
    } finally { deliveryGate.unlock(); }
  }

  private static boolean ownsLedger(OperationHistoryRow row) {
    return row.kind() == OperationKind.MEMORY || row.kind() == OperationKind.NOTE
        || !TransportTag.AGENT_LOOP.name().equals(row.context().transport());
  }

  private boolean publishLive(OperationHistoryRow row, OperationHistoryEntry entry) {
    deliveryGate.lock();
    try {
      if (stopping) return false;
      if (ownsLedger(row)) ledger.publishLiveOperation(entry);
      return true;
    } finally { deliveryGate.unlock(); }
  }

  private void tick() {
    // A scheduler may begin before scheduleWithFixedDelay returns. Construction must finish first.
    deliveryGate.lock();
    try { if (stopping) return; }
    finally { deliveryGate.unlock(); }
    try { drainPending(); }
    catch (RuntimeException failure) { LOG.warn("Operations history delivery failed; pending source will retry", failure); }
    if (stopping) return;
    try { catchUpLive(); }
    catch (RuntimeException failure) { LOG.warn("Operations history startup visibility failed; page will retry", failure); }
  }

  private void drainPending() {
    for (var row : operations.pendingHistoryProjection(OperationStore.HISTORY_PROJECTION_BATCH_LIMIT)) {
      if (stopping) return;
      var entry = OperationHistoryProjection.entry(row);
      boolean accepted = !ownsLedger(row) || ledger.persistOperation(entry);
      // A successful ack may make this row disappear before startup enumeration reaches it.
      if (!publishLive(row, entry) || !accepted) return;
      if (!operations.acknowledgeHistoryProjection(row.key())) return;
    }
  }

  private void catchUpLive() {
    if (startupComplete) return;
    var rows = operations.pendingHistoryProjectionAfter(OperationStore.HISTORY_PROJECTION_BATCH_LIMIT,
        startupCursor == null ? 0 : startupCursor.completedAt(), startupCursor == null ? 0 : startupCursor.id(), startupUpperId);
    for (var row : rows) {
      if (!publishLive(row, OperationHistoryProjection.entry(row))) return;
      startupCursor = row;
    }
    if (rows.isEmpty()) startupComplete = true;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    stopping = true;
    RuntimeException unsubscribeFailure = null;
    if (subscription != null) {
      try { subscription.close(); subscription = null; }
      catch (Exception failure) { unsubscribeFailure = new IllegalStateException("History completion subscription did not close", failure); }
    }
    if (task != null) task.cancel(true);
    if (scheduler != null) scheduler.shutdownNow();
    try {
      if (deliveryGate.isHeldByCurrentThread() || !deliveryGate.tryLock(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("History completion callback did not quiesce within 5s");
      }
      deliveryGate.unlock();
      if (scheduler != null && !scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Operations history scheduler did not terminate within 5s");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while draining operations history", failure);
    }
    if (unsubscribeFailure != null) throw unsubscribeFailure;
    // A refused drain retains the registration and a later close can complete this same owner.
    if (registration != null) registration.close();
    closed = true;
  }
}
