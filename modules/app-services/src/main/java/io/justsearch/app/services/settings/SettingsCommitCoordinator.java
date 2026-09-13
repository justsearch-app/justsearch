/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ConfigChangedEvent;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** One physical settings-file/config owner; the operation runner alone terminalizes its row. */
public final class SettingsCommitCoordinator implements SettingsCommitOwner {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SettingsCommitCoordinator.class);
  @FunctionalInterface
  interface Replacement {
    void replace(UiSettingsStore.PreparedSettings prepared) throws IOException;
  }

  private enum Phase { PREPARING, COMMITTED, UNCERTAIN }

  private static final class SettingsCommitFence implements Reservation {
    private final long id;
    private final String key;
    private final SettingsWitness prior;
    private Phase phase = Phase.PREPARING;
    private boolean preparationStarted;

    private SettingsCommitFence(long id, String key, SettingsWitness prior) {
      this.id = id; this.key = key; this.prior = prior;
    }
  }

  private final UiSettingsStore store;
  private final ConfigStore config;
  private final Runnable restart;
  private final Function<UiSettings, ResolvedConfig> prepareConfig;
  private final Function<UiSettings, OperationResult> prepareResponse;
  private final Replacement replacement;
  private final ReentrantLock mutex = new ReentrantLock();
  private final CompletableFuture<RecoveryIssue> recoveryIssue = new CompletableFuture<>();
  private volatile SettingsCommitFence fence;
  private volatile RecoveryIssue issue;
  private boolean inspected;
  private boolean blocked;
  private boolean restartIssued;
  private Long recoveredId;
  private OperationAttemptRunner.Reconciliation recoveredDecision;

  public SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, OperationResult> prepareResponse) {
    this(store, config, restart, ConfigStoreRebuilder::prepare, prepareResponse, store::replacePrepared);
  }

  /** Fault seams stay package-private; production always uses the strict store replacement. */
  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement) {
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.restart = Objects.requireNonNull(restart, "restart");
    this.prepareConfig = Objects.requireNonNull(prepareConfig, "prepareConfig");
    this.prepareResponse = Objects.requireNonNull(prepareResponse, "prepareResponse");
    this.replacement = Objects.requireNonNull(replacement, "replacement");
  }

  @Override public CompletionStage<RecoveryIssue> recoveryIssue() { return recoveryIssue.minimalCompletionStage(); }

  @Override
  public Reservation reserve(long id, String key, SettingsWitness expected) {
    if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    try {
      if (!inspected || blocked) throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings recovery is unresolved", Map.of());
      if (fence != null) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
      if (!store.mode().isWritable()) throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled", Map.of());
      OperationKeys.timestampMillis(key);
      Objects.requireNonNull(expected, "expected settings witness");
      if (id <= 0 || expected.acceptedRevision() == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Invalid settings reservation");
      }
      final SettingsWitness prior;
      try { prior = store.inspect().witness(); }
      catch (RuntimeException failure) {
        block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, id));
        throw refused("SETTINGS_RECOVERY_REQUIRED", "The settings revision cannot be verified", Map.of());
      }
      if (!prior.equals(expected)) {
        throw refused("VERSION_CONFLICT", "Settings changed since this candidate was read",
            Map.of("currentRevision", prior.acceptedRevision()));
      }
      var reserved = new SettingsCommitFence(id, key, prior);
      fence = reserved;
      return reserved;
    } finally {
      mutex.unlock();
      publishIssue();
    }
  }

  @Override
  public void apply(Reservation reservation, UiSettings candidate, AttemptControl control) {
    Objects.requireNonNull(control, "control");
    ConfigChangedEvent event;
    mutex.lock();
    try {
      SettingsCommitFence active = requireFence(reservation);
      if (active.preparationStarted) throw new IllegalStateException("Settings preparation already started");
      active.preparationStarted = true;
      var next = new SettingsWitness(Math.addExact(active.prior.acceptedRevision(), 1), active.key);
      var prepared = store.prepare(candidate, next);
      ResolvedConfig resolved = Objects.requireNonNull(prepareConfig.apply(prepared.settings()), "Prepared config");
      var receipt = new Receipt(active.key, next.acceptedRevision(), prepareResponse.apply(prepared.settings()));
      try {
        replacement.replace(prepared);
      } catch (IOException | RuntimeException failure) {
        final SettingsWitness observed;
        try { observed = store.inspect().witness(); }
        catch (RuntimeException inspectionFailure) {
          if (failure != inspectionFailure) failure.addSuppressed(inspectionFailure);
          uncertain(active, control, RecoveryReason.UNREADABLE_WITNESS);
          throw new IllegalStateException("Settings replacement has no readable witness", failure);
        }
        if (!next.equals(observed)) {
          if (active.prior.equals(observed)) throw new IllegalStateException("Settings replacement did not commit", failure);
          uncertain(active, control, RecoveryReason.CONTRADICTORY_WITNESS);
          throw new IllegalStateException("Settings replacement has a contradictory witness", failure);
        }
        // A move may report failure after committing. The exact file witness resolves that ambiguity.
      }
      active.phase = Phase.COMMITTED;
      control.committed(receipt);
      event = config.swap(resolved);
    } finally {
      mutex.unlock();
      publishIssue();
    }
    // Arbitrary notification code is outside the physical mutex, with the logical fence retained.
    config.notifyListeners(event);
    store.notifyRecoveryCleared();
  }

  private SettingsCommitFence requireFence(Reservation reservation) {
    if (!(reservation instanceof SettingsCommitFence active) || active != fence
        || active.phase != Phase.PREPARING || blocked) {
      throw new IllegalArgumentException("Settings reservation is foreign, retired or unresolved");
    }
    return active;
  }

  private void uncertain(SettingsCommitFence active, AttemptControl control, RecoveryReason reason) {
    active.phase = Phase.UNCERTAIN;
    control.uncertain();
    block(new RecoveryIssue(reason, active.id));
  }

  @Override
  public void releaseAfterTerminal(long id) {
    // A refused contender never owned this fence; its terminal publication must not wait
    // for another transaction's preparation merely to perform unrelated cleanup.
    SettingsCommitFence observed = fence;
    if (observed == null || observed.id != id) return;
    mutex.lock();
    try {
      if (fence != null && fence.id == id && !blocked) fence = null;
    } finally { mutex.unlock(); }
  }

  @Override
  public void retainForRestart(long id, Throwable failure) {
    SettingsCommitFence observed = fence;
    if (observed == null || observed.id != id) return;
    boolean request = false;
    mutex.lock();
    try {
      if (fence != null && fence.id == id) {
        fence.phase = Phase.UNCERTAIN;
        blocked = true;
        if (!restartIssued) { restartIssued = true; request = true; }
      }
    } finally { mutex.unlock(); }
    if (request) {
      LOG.error("Retaining settings operation {} for ordered restart", id, failure);
      restart.run();
    }
  }

  @Override
  public void inspectRecovery(List<RecoveryInput> rows) {
    mutex.lock();
    try {
      if (inspected || fence != null) throw new IllegalStateException("Settings recovery already inspected");
      inspected = true;
      var armed = List.copyOf(rows).stream().filter(input -> input.row().expectedSettingsRevision() != null).toList();
      if (armed.size() > 1) {
        block(new RecoveryIssue(RecoveryReason.MULTIPLE_ARMED_ROWS, null));
        return;
      }
      if (armed.isEmpty()) return;
      OperationRecord row = armed.getFirst().row();
      recoveredId = row.id();
      recoveredDecision = new OperationAttemptRunner.Reconciliation.Wait();
      if (!store.mode().isWritable()) {
        block(new RecoveryIssue(RecoveryReason.PERSISTENCE_DISABLED, row.id()));
        return;
      }
      final SettingsWitness witness;
      try { witness = store.inspect().witness(); }
      catch (RuntimeException failure) {
        block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, row.id()));
        return;
      }
      long expected = row.expectedSettingsRevision();
      if (expected < 0 || expected == Long.MAX_VALUE) {
        block(new RecoveryIssue(RecoveryReason.CONTRADICTORY_WITNESS, row.id()));
        return;
      }
      if (row.key().equals(witness.lastCommittedOperationKey()) && witness.acceptedRevision() == expected + 1) {
        recoveredDecision = new OperationAttemptRunner.Reconciliation.Complete(new OperationReceipt("SUCCESS", null));
      } else if (witness.acceptedRevision() == expected) {
        recoveredDecision = precommitFailure();
      } else {
        block(new RecoveryIssue(RecoveryReason.CONTRADICTORY_WITNESS, row.id()));
        return;
      }
      // Keep the bounded witness reserved until the runner persists the recovery result.
      fence = new SettingsCommitFence(row.id(), row.key(), witness);
    } finally {
      mutex.unlock();
      publishIssue();
    }
  }

  @Override
  public OperationAttemptRunner.Reconciliation reconcile(OperationRecord row) {
    mutex.lock();
    try {
      if (!inspected) throw new IllegalStateException("Settings recovery must inspect the complete row set first");
      if (row.expectedSettingsRevision() == null) return precommitFailure();
      return Objects.equals(recoveredId, row.id()) ? recoveredDecision : new OperationAttemptRunner.Reconciliation.Wait();
    } finally { mutex.unlock(); }
  }

  private static OperationAttemptRunner.Reconciliation precommitFailure() {
    return new OperationAttemptRunner.Reconciliation.Failed(new OperationReceipt("interrupted_before_settings_commit", null));
  }

  private void block(RecoveryIssue recovery) {
    blocked = true;
    if (issue == null) issue = recovery;
  }

  private void publishIssue() {
    // A nested refused call may have released only one reentrant hold. Let the outer owner
    // publish after its final unlock rather than invoking Health callbacks under that hold.
    if (mutex.isHeldByCurrentThread()) return;
    RecoveryIssue observed = issue;
    if (observed != null) recoveryIssue.complete(observed);
  }

  private static Refused refused(String code, String message, Map<String, Object> details) {
    return new Refused(OperationResult.failure(message, code, details, false));
  }
}
