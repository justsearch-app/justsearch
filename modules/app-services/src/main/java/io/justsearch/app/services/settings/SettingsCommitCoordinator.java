/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.resolved.ConfigChangedEvent;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

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
    private boolean restartRequired;
    private final String quarantineFingerprint;
    private final boolean reset;
    @Override public long expectedRevision() { return prior.acceptedRevision(); }
    private boolean recoveryReset() { return quarantineFingerprint != null; }

    private SettingsCommitFence(long id, String key, SettingsWitness prior) {
      this(id, key, prior, null, false);
    }
    private SettingsCommitFence(long id, String key, SettingsWitness prior, String fingerprint, boolean reset) {
      this.id = id; this.key = key; this.prior = prior;
      this.quarantineFingerprint = fingerprint; this.reset = reset;
    }
  }

  private final UiSettingsStore store;
  private final ConfigStore config;
  private final Runnable restart;
  private final Function<UiSettings, ResolvedConfig> prepareConfig;
  private final Function<UiSettings, OperationResult> prepareResponse;
  private final Replacement replacement;
  private final BooleanSupplier processClosing;
  private final SettingsComponentComposer components;
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
    this(store, config, restart, prepareResponse, () -> false);
  }

  public SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, OperationResult> prepareResponse, BooleanSupplier processClosing) {
    this(store, config, restart, prepareResponse, processClosing, unavailableComponents());
  }

  public SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, OperationResult> prepareResponse, BooleanSupplier processClosing,
      SettingsComponentComposer components) {
    this(store, config, restart, ConfigStoreRebuilder::prepare, prepareResponse,
        store::replacePrepared, processClosing, components);
  }

  /** Fault seams stay package-private; production always uses the strict store replacement. */
  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement) {
    this(store, config, restart, prepareConfig, prepareResponse, replacement, () -> false);
  }

  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement,
      BooleanSupplier processClosing) {
    this(store, config, restart, prepareConfig, prepareResponse, replacement, processClosing,
        unavailableComponents());
  }

  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement,
      BooleanSupplier processClosing, SettingsComponentComposer components) {
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.restart = Objects.requireNonNull(restart, "restart");
    this.prepareConfig = Objects.requireNonNull(prepareConfig, "prepareConfig");
    this.prepareResponse = Objects.requireNonNull(prepareResponse, "prepareResponse");
    this.replacement = Objects.requireNonNull(replacement, "replacement");
    this.processClosing = Objects.requireNonNull(processClosing, "processClosing");
    this.components = Objects.requireNonNull(components, "components");
  }

  private static SettingsComponentComposer unavailableComponents() {
    return (candidate, desired, affected) -> {
      throw refused("COMPONENT_PREPARATION_REQUIRED",
          "The affected runtime components cannot yet be committed atomically",
          Map.of("components", affected));
    };
  }

  @Override public CompletionStage<RecoveryIssue> recoveryIssue() { return recoveryIssue.minimalCompletionStage(); }

  @Override
  public Reservation reserve(long id, String key, SettingsWitness expected) {
    if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    try {
      return reserveOwned(id, key, expected, false);
    } finally {
      mutex.unlock();
      publishIssue();
    }
  }

  /** Shared reservation checks; both public entry points hold the physical mutex. */
  private SettingsCommitFence reserveOwned(long id, String key, SettingsWitness expected, boolean reset) {
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
    var reserved = new SettingsCommitFence(id, key, prior, null, reset);
    fence = reserved;
    return reserved;
  }

  @Override
  public Reservation reserveReset(OperationRecord row, OperationStore.Preparation accepted) {
    var intent = SettingsResetPreparation.decode(row, accepted);
    if (!intent.recovery()) {
      // The common checks create the final reset fence once under this mutex.
      if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
      try {
        return reserveOwned(row.id(), row.key(), intent.expected(), true);
      } finally { mutex.unlock(); publishIssue(); }
    }
    if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    try {
      if (!inspected || recoveredId != null
          || (blocked && (issue == null || issue.reason() != RecoveryReason.UNREADABLE_WITNESS))) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings recovery is unresolved", Map.of());
      }
      if (fence != null) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
      if (!store.mode().isWritable()) throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled", Map.of());
      if (row.id() <= 0) throw new IllegalArgumentException("Invalid settings reservation");
      if (!matchesQuarantine(intent.quarantineFingerprint())) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings quarantine evidence changed", Map.of());
      }
      block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, row.id()));
      fence = new SettingsCommitFence(row.id(), row.key(), new SettingsWitness(0, null),
          intent.quarantineFingerprint(), true);
      return fence;
    } finally { mutex.unlock(); publishIssue(); }
  }

  @Override
  public void applyReset(Reservation reservation, AttemptControl control) {
    applyOwned(reservation, null, control, true);
  }

  @Override
  public void apply(Reservation reservation, UiSettings candidate, AttemptControl control) {
    applyOwned(reservation, candidate, control, false);
  }

  private void applyOwned(Reservation reservation, UiSettings candidate, AttemptControl control, boolean reset) {
    Objects.requireNonNull(control, "control");
    ConfigChangedEvent[] event = new ConfigChangedEvent[1];
    SettingsComponentComposer.Prepared preparedComponents = null;
    boolean[] committed = {false};
    Throwable failed = null;
    mutex.lock();
    try {
      SettingsCommitFence active = requireFence(reservation);
      if (active.reset != reset) throw new IllegalArgumentException("Settings reservation purpose mismatch");
      if (active.preparationStarted) throw new IllegalStateException("Settings preparation already started");
      active.preparationStarted = true;
      if (reset) {
        if (active.recoveryReset()) {
          if (!matchesQuarantine(active.quarantineFingerprint)) {
            throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings quarantine evidence changed", Map.of());
          }
          candidate = new UiSettings();
        } else {
          final UiSettingsStore.Snapshot snapshot;
          try { snapshot = store.inspect(); }
          catch (RuntimeException unreadable) {
            block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, active.id));
            throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings witness became unreadable", Map.of());
          }
          if (!active.prior.equals(snapshot.witness())) {
            throw refused("VERSION_CONFLICT", "Settings changed since reset was prepared", Map.of());
          }
          candidate = snapshot.settings();
          SettingsResetDefaults.applyTo(candidate);
        }
      }
      var next = new SettingsWitness(Math.addExact(active.prior.acceptedRevision(), 1), active.key);
      var prepared = store.prepare(candidate, next);
      boolean chatComponentChanged = !Objects.equals(
          store.inspect().settings().getChatEnabled(), prepared.settings().getChatEnabled());
      ResolvedConfig resolved = Objects.requireNonNull(prepareConfig.apply(prepared.settings()), "Prepared config");
      ResolvedConfig serving = config.get();
      var changedKeys = ConfigApplyScopes.classify(serving, resolved);
      if (!changedKeys.generationBound().isEmpty()) {
        throw refused("GENERATION_BOUND_REQUIRES_REINDEX",
            "Generation-bound settings require a separate reindex operation",
            Map.of("keys", List.copyOf(changedKeys.generationBound()),
                "operation", "core.bulk-reindex"));
      }
      boolean restartRequired = !changedKeys.restartRequired().isEmpty();
      // API_PORT is the only restart-required value this settings candidate can write. If an
      // unrelated process source drifted since boot, refuse before touching a component owner.
      if (!java.util.Set.of("justsearch.api.port").containsAll(changedKeys.restartRequired())) {
        throw refused("RESTART_SOURCE_DRIFT",
            "A restart-required process source changed; restart before applying settings",
            Map.of("keys", List.copyOf(changedKeys.restartRequired())));
      }
      ResolvedConfig servingResolved = changedKeys.restartRequired().contains("justsearch.api.port")
          ? resolved.retainingApiPortFrom(serving) : resolved;
      if (!changedKeys.component().isEmpty() || chatComponentChanged) {
        var affected = new java.util.TreeMap<String, java.util.Set<String>>(changedKeys.component());
        if (chatComponentChanged) {
          affected.merge("generative", java.util.Set.of("ui.chatEnabled"), (left, right) -> {
            var keys = new java.util.TreeSet<>(left);
            keys.addAll(right);
            return java.util.Set.copyOf(keys);
          });
        }
        preparedComponents = Objects.requireNonNull(
            components.prepare(prepared.settings(), resolved, Map.copyOf(affected)),
            "Prepared component transaction");
      }
      OperationResult preparedResponse = prepareResponse.apply(prepared.settings());
      if (restartRequired) preparedResponse = withRestartScheduled(preparedResponse);
      var receipt = new Receipt(active.key, next.acceptedRevision(), preparedResponse);
      ConfigStore.PreparedSwap preparedConfig = config.prepareSwap(servingResolved);
      var publication = config.publicationLock();
      SettingsComponentComposer.Prepared preparedForPublish = preparedComponents;
      Runnable publish = () -> {
        publication.writeLock().lock();
        try {
          requireFence(reservation);
          config.validatePrepared(preparedConfig);
          if (preparedForPublish != null) preparedForPublish.validate();
          if (processClosing.getAsBoolean()) {
            throw refused("ENGINE_CLOSING", "Engine shutdown has closed settings admission", Map.of());
          }
          if (!control.admitCommit(receipt)) {
            throw new CancellationException("Settings apply was cancelled before commit admission");
          }
          try {
            replacement.replace(prepared);
          } catch (IOException | RuntimeException failure) {
            final SettingsWitness observed;
            try { observed = store.inspect().witness(); }
            catch (RuntimeException inspectionFailure) {
              if (active.recoveryReset() && matchesQuarantine(active.quarantineFingerprint)) {
                throw new IllegalStateException("Settings recovery replacement did not commit", failure);
              }
              if (failure != inspectionFailure) failure.addSuppressed(inspectionFailure);
              uncertain(active, control, RecoveryReason.UNREADABLE_WITNESS);
              throw new IllegalStateException("Settings replacement has no readable witness", failure);
            }
            if (!next.equals(observed)) {
              if (!active.recoveryReset() && active.prior.equals(observed)) throw new IllegalStateException("Settings replacement did not commit", failure);
              uncertain(active, control, RecoveryReason.CONTRADICTORY_WITNESS);
              throw new IllegalStateException("Settings replacement has a contradictory witness", failure);
            }
            // A move may report failure after committing. The exact file witness resolves that ambiguity.
          }
          active.phase = Phase.COMMITTED;
          committed[0] = true;
          active.restartRequired = restartRequired;
          control.committed(receipt);
          try {
            config.installPrepared(preparedConfig);
            if (preparedForPublish != null) preparedForPublish.install();
          } catch (RuntimeException | Error publicationFailure) {
            // The durable witness already names B. A broken prepared installer must not be
            // reported as a clean serving commit; boot reconstructs from that witness.
            control.uncertain();
            throw publicationFailure;
          }
          event[0] = preparedConfig.event();
        } finally {
          publication.writeLock().unlock();
        }
      };
      if (preparedForPublish == null) publish.run();
      else preparedForPublish.withOwnerLocks(publish);
    } catch (RuntimeException | Error failure) {
      failed = failure;
      throw failure;
    } finally {
      mutex.unlock();
      publishIssue();
      if (!committed[0] && preparedComponents != null) {
        SettingsCommitFence active = fence;
        if (active != null && active.phase == Phase.PREPARING) {
          try { preparedComponents.abort(); }
          catch (RuntimeException | Error cleanupFailure) {
            // A refused close leaves a physical owner alive. Preserve the armed row for boot
            // reconciliation and ask the runner for ordered recovery instead of reporting a
            // clean precommit failure while the registry permit remains held.
            control.uncertain();
            if (failed != null) failed.addSuppressed(cleanupFailure);
            else LOG.error("Prepared component abort failed without a commit failure", cleanupFailure);
          }
        }
      }
    }
    // Arbitrary notification code is outside the physical mutex, with the logical fence retained.
    Throwable postCommitFailure = null;
    try {
      config.notifyListeners(event[0]);
      if (preparedComponents != null) preparedComponents.notifyObservers();
    } catch (RuntimeException | Error notificationFailure) {
      postCommitFailure = notificationFailure;
    }
    if (preparedComponents != null) {
      try { preparedComponents.retire(); }
      catch (RuntimeException | Error retirementFailure) {
        // The file and service publication are committed. Keep that receipt authoritative,
        // retain the apply permit, and schedule an ordered successor after the row completes.
        mutex.lock();
        try {
          SettingsCommitFence active = fence;
          if (active != null && active.phase == Phase.COMMITTED) active.restartRequired = true;
        } finally { mutex.unlock(); }
        if (postCommitFailure == null) postCommitFailure = retirementFailure;
        else postCommitFailure.addSuppressed(retirementFailure);
      }
    }
    if (postCommitFailure instanceof RuntimeException runtime) throw runtime;
    if (postCommitFailure instanceof Error error) throw error;
  }

  private SettingsCommitFence requireFence(Reservation reservation) {
    if (!(reservation instanceof SettingsCommitFence active) || active != fence
        || active.phase != Phase.PREPARING || (blocked && !active.recoveryReset())) {
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
    boolean clear = false;
    boolean request = false;
    mutex.lock();
    try {
      if (fence != null && fence.id == id && fence.phase != Phase.UNCERTAIN) {
        clear = fence.phase == Phase.COMMITTED;
        if (clear && fence.recoveryReset()) blocked = false;
        if (clear && (fence.recoveryReset() || fence.restartRequired) && !restartIssued) {
          restartIssued = true;
          request = true;
        }
        fence = null;
        if (Objects.equals(recoveredId, id)) recoveredId = null;
      }
    } finally { mutex.unlock(); }
    // Only durable COMPLETE may clear recovery; callback faults cannot undo that outcome.
    Error fatal = null;
    try {
      if (clear) store.notifyRecoveryCleared();
    } catch (RuntimeException notificationFailure) {
      LOG.warn("Settings recovery notification failed after durable completion", notificationFailure);
    } catch (Error failure) {
      fatal = failure;
      throw failure;
    } finally {
      if (request) {
        try { restart.run(); }
        catch (RuntimeException | Error restartFailure) {
          if (fatal == null) throw restartFailure;
          if (fatal != restartFailure) fatal.addSuppressed(restartFailure);
        }
      }
    }
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
        var reset = recoveryIntent(armed.getFirst());
        if (reset != null && matchesQuarantine(reset.quarantineFingerprint())) {
          recoveredDecision = precommitFailure();
          fence = new SettingsCommitFence(row.id(), row.key(), new SettingsWitness(0, null),
              reset.quarantineFingerprint(), true);
        }
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
      } else if (witness.acceptedRevision() == expected
          && normalResetBaseMatches(armed.getFirst(), witness)) {
        recoveredDecision = precommitFailure();
      } else {
        block(new RecoveryIssue(RecoveryReason.CONTRADICTORY_WITNESS, row.id()));
        return;
      }
      // Keep the bounded witness reserved until the runner persists the recovery result.
      fence = new SettingsCommitFence(row.id(), row.key(), witness);
      if (recoveredDecision instanceof OperationAttemptRunner.Reconciliation.Complete) fence.phase = Phase.COMMITTED;
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

  private static boolean normalResetBaseMatches(RecoveryInput input, SettingsWitness witness) {
    if (!SettingsResetPreparation.OPERATION_ID.equals(input.row().descriptor().operationRef())) return true;
    if (input.preparation().isEmpty()) return false;
    try {
      var intent = SettingsResetPreparation.decode(input.row(), input.preparation().orElseThrow());
      return !intent.recovery() && witness.equals(intent.expected());
    } catch (IllegalArgumentException unavailable) { return false; }
  }

  private static SettingsResetPreparation.Intent recoveryIntent(RecoveryInput input) {
    if (input.preparation().isEmpty()) return null;
    try {
      var intent = SettingsResetPreparation.decode(input.row(), input.preparation().orElseThrow());
      return intent.recovery() ? intent : null;
    } catch (IllegalArgumentException unavailable) {
      return null;
    }
  }

  private boolean matchesQuarantine(String fingerprint) {
    try { return fingerprint.equals(store.recoveryFingerprint()); }
    catch (IOException | RuntimeException unavailable) { return false; }
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

  private static OperationResult withRestartScheduled(OperationResult response) {
    var data = new java.util.LinkedHashMap<String, Object>(response.structuredData());
    data.put("restartScheduled", true);
    return new OperationResult(response.success(), response.message(), response.executionId(), data,
        response.errorCode(), response.errorDetails(), response.retryable());
  }
}
